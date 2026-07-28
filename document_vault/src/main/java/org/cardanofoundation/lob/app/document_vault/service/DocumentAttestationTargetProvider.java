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
 * <h2>Why this lives in document_vault</h2>
 *
 * It used to live in {@code blockchain_publisher}. In the split deployment the ceremony endpoint runs
 * on the {@code api} service, which sets {@code LOB_BLOCKCHAIN_PUBLISHER_ENABLED=false} — so that
 * module's package is never component-scanned there, the registry received an empty provider list, and
 * every ceremony failed with {@code 422 TARGET_MISMATCH}. Registering the bean from somewhere always
 * scanned would not have been enough: the old implementation also needed an {@code IpfsPublisher} and
 * a {@code BlockchainReaderPublicApiIF}, and that pod has neither.
 *
 * <h2>What changed to make that possible</h2>
 *
 * The wallet now attests a {@link DocumentAttestationCommitment} — org, document, envelope hash,
 * content hashes and recipient key hashes — instead of the finished 1447 manifest. Everything in it is
 * computable from the vault's own row with no network call at all. The publisher pins the envelope,
 * reads the chain tip and assembles the manifest afterwards, in the tier that actually holds those
 * credentials.
 *
 * <p>A side effect worth having: IPFS is no longer written during a ceremony, so an abandoned ceremony
 * no longer leaves pinned content behind — the leak recorded in docs/keri-document-flow.md §9.
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
    @Transactional
    public Either<ProblemDetail, AttestationDigest> prepareDigest(String targetId, String ceremonyId) {
        // Idempotent per (documentId, ceremonyId): an existing row is authoritative and returned
        // unchanged. The wallet may have already anchored that exact digest.
        Optional<DocumentAttestationFreezeEntity> existing =
                freezeRepository.findByDocumentIdAndCeremonyId(targetId, ceremonyId);
        if (existing.isPresent()) {
            return Either.right(new AttestationDigest(existing.get().getDigestQb64(), String.valueOf(metadataLabel)));
        }

        // authorize(targetId, userId) has already run synchronously in this same request thread
        // (keri_attestation's CeremonyService#create and KeriAttestService#attest both call it
        // immediately before this). This port method carries no userId parameter, so re-derive the same
        // current user from the same SecurityContextHolder-backed helper loadForAttestation itself uses,
        // rather than fabricating one.
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
     * The unique {@code (document_id, ceremony_id)} constraint is the actual source of truth, not the
     * upstream ceremony-row lock. A {@link DataIntegrityViolationException} means a concurrent call
     * already committed the freeze this one was about to insert — re-read and return THAT row's digest,
     * so a caller never sees an exception for a condition this method's contract calls idempotent.
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
