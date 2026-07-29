package org.cardanofoundation.lob.app.document_vault.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentAttestationCommitment;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationTargetProvider;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * The {@code DOCUMENT} implementation of {@link AttestationTargetProvider}.
 *
 * <p>It lives in document_vault because the ceremony endpoint runs in the user-facing tier, which may
 * be deployed without blockchain_publisher — and without an {@code IpfsPublisher} or a chain reader.
 * That is possible because the wallet attests a {@link DocumentAttestationCommitment} rather than the
 * finished manifest, and every field of it is computable from the vault's own row with no network
 * call. The publisher pins the envelope, reads the chain tip and assembles the manifest afterwards.
 *
 * <p>Since nothing is pinned during a ceremony, an abandoned ceremony leaves no content behind.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentAttestationTargetProvider implements AttestationTargetProvider {

    public static final String TARGET_TYPE = "DOCUMENT";

    private final VaultDocumentService vaultDocumentService;
    private final DocumentIpfsSerialiser documentIpfsSerialiser;
    private final Cip170MetadataFactory cip170MetadataFactory;
    private final DocumentAttestationFreezeRepository freezeRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final Clock clock;
    private final int metadataLabel;

    @Override
    public String targetType() {
        return TARGET_TYPE;
    }

    @Override
    public Optional<ProblemDetail> authorize(String targetId, String userId) {
        return vaultDocumentService.loadForAttestation(targetId, userId)
                .fold(Optional::of, document -> Optional.empty());
    }

    @Override
    public Optional<String> organisationId(String targetId) {
        return vaultDocumentService.loadForAttestation(targetId, securityHelper.getCurrentUserId())
                .fold(problem -> Optional.empty(), document -> Optional.of(document.getOrganisationId()));
    }

    @Override
    @Transactional
    public Either<ProblemDetail, AttestationDigest> prepareDigest(String targetId, String ceremonyId) {
        // Idempotent per (documentId, ceremonyId): an existing row is authoritative and returned
        // unchanged. The wallet may have already anchored that exact digest.
        Optional<DocumentAttestationFreezeEntity> existing =
                freezeRepository.findByDocumentIdAndCeremonyId(targetId, ceremonyId);
        if (existing.isPresent()) {
            return Either.right(new AttestationDigest(existing.get().getDigestQb64(), String.valueOf(metadataLabel)));
        }

        // authorize(targetId, userId) has already run on this request thread. The port method carries
        // no userId, so re-derive the current user from the same security helper rather than inventing
        // one.
        String userId = securityHelper.getCurrentUserId();

        return vaultDocumentService.loadForAttestation(targetId, userId)
                .flatMap(document -> freezeAndDigest(document, ceremonyId));
    }

    private Either<ProblemDetail, AttestationDigest> freezeAndDigest(VaultDocumentEntity vaultDocument, String ceremonyId) {
        DocumentPublishCommand command = VaultDocumentService.toPublishCommand(vaultDocument);

        // The exact bytes the publisher will pin verbatim. Hashing them here is what lets publish-time
        // detect the document changing between attestation and dispatch.
        String envelopeJson = documentIpfsSerialiser.serialise(command);
        String envelopeSha256 = sha256Hex(envelopeJson);

        MetadataMap commitment = DocumentAttestationCommitment.toMetadataMap(command, envelopeSha256);

        try {
            byte[] commitmentCbor = CborSerializationUtil.serialize(commitment.getMap());
            String digestQb64 = cip170MetadataFactory.digestOf(commitment);

            DocumentAttestationFreezeEntity freeze = new DocumentAttestationFreezeEntity();
            freeze.setId(UUID.randomUUID().toString());
            freeze.setDocumentId(vaultDocument.getId());
            freeze.setCeremonyId(ceremonyId);
            freeze.setCommitmentCbor(commitmentCbor);
            freeze.setDigestQb64(digestQb64);
            freeze.setEnvelopeSha256(envelopeSha256);
            freeze.setCreatedAt(LocalDateTime.now(clock));

            return saveFreeze(freeze, digestQb64);
        } catch (CborException e) {
            log.error("Error CBOR-serialising the attestation commitment for ceremony:{}, document:{}",
                    ceremonyId, vaultDocument.getId(), e);

            return Either.left(VaultProblems.unprocessable(VaultProblems.ATTESTED_METADATA_MISMATCH,
                    "Could not serialise the attestation commitment: %s".formatted(e.getMessage())));
        }
    }

    /**
     * The unique {@code (document_id, ceremony_id)} constraint is the source of truth, not the upstream
     * ceremony-row lock. A {@link DataIntegrityViolationException} means a concurrent call already
     * committed this freeze, so the winner's digest is re-read and returned rather than surfacing an
     * exception for what the contract calls idempotent.
     */
    private Either<ProblemDetail, AttestationDigest> saveFreeze(DocumentAttestationFreezeEntity freeze, String digestQb64) {
        try {
            freezeRepository.save(freeze);

            log.info("Froze DOCUMENT attestation commitment for ceremony:{}, document:{}",
                    freeze.getCeremonyId(), freeze.getDocumentId());

            return Either.right(new AttestationDigest(digestQb64, String.valueOf(metadataLabel)));
        } catch (DataIntegrityViolationException e) {
            log.info("Lost the freeze unique-constraint race for document:{}, ceremony:{} - "
                            + "returning the concurrent winner's digest instead",
                    freeze.getDocumentId(), freeze.getCeremonyId());

            return freezeRepository.findByDocumentIdAndCeremonyId(freeze.getDocumentId(), freeze.getCeremonyId())
                    .<Either<ProblemDetail, AttestationDigest>>map(winner ->
                            Either.right(new AttestationDigest(winner.getDigestQb64(), String.valueOf(metadataLabel))))
                    .orElseGet(() -> Either.left(VaultProblems.conflict(VaultProblems.ATTESTATION_FREEZE_MISSING,
                            "Concurrent freeze race for document %s, ceremony %s left no row."
                                    .formatted(freeze.getDocumentId(), freeze.getCeremonyId()))));
        }
    }

    static String sha256Hex(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
