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
import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationTargetProvider;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * The {@code DOCUMENT} implementation of {@link AttestationTargetProvider}.
 *
 * <p>It lives in document_vault because the ceremony endpoint runs in the user-facing tier, which may
 * be deployed without blockchain_publisher — and without a chain reader. That is possible because the
 * manifest no longer carries anything decided at dispatch: {@code creation_slot} and {@code timestamp}
 * are gone from it, and the CID is obtained here from IPFS WITHOUT pinning
 * ({@link IpfsPublisher#contentId}). So the wallet attests the finished manifest itself, byte for
 * byte, and the publisher reproduces that same manifest later rather than the two tiers committing to
 * different things.
 *
 * <p>Since nothing is pinned during a ceremony, an abandoned ceremony still leaves no content behind:
 * on a node the CID is computed in only-hash mode, and on Blockfrost an unpinned add is garbage
 * collected. That is what makes a compensating "unpin on cancel" unnecessary — and it would have been
 * unreliable, since a crash between pinning and cancelling would orphan the pin.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentAttestationTargetProvider implements AttestationTargetProvider {

    public static final String TARGET_TYPE = "DOCUMENT";

    private final VaultDocumentService vaultDocumentService;
    private final DocumentIpfsSerialiser documentIpfsSerialiser;
    private final DocumentMetadataSerialiser documentMetadataSerialiser;
    private final OrganisationPublicApiIF organisationPublicApi;
    /** Empty when this tier has no IPFS configured — see {@link #freezeAndDigest}. */
    private final Optional<IpfsPublisher> ipfsPublisher;
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

    /**
     * Builds the manifest this document will be published under and freezes its digest for the wallet.
     *
     * <p>The wallet attests the REAL manifest, byte for byte — including {@code data.ipfs_cid}. That is
     * possible because a CID is a pure function of the envelope bytes, so
     * {@link IpfsPublisher#contentId} can name it here without pinning anything; an abandoned ceremony
     * still leaves no content behind. The alternative — attesting a side-structure that omits the CID —
     * is what forced the indexer to keep a mirrored copy of the format and to defer correlation until
     * it had fetched the envelope from IPFS just to learn one hash.
     *
     * <p>Fails closed when this tier has no IPFS configured: without a CID the manifest cannot be
     * completed, and attesting an incomplete one would produce a digest the publisher can never
     * reproduce.
     */
    private Either<ProblemDetail, AttestationDigest> freezeAndDigest(VaultDocumentEntity vaultDocument, String ceremonyId) {
        DocumentPublishCommand command = VaultDocumentService.toPublishCommand(vaultDocument);

        // The exact bytes the publisher will pin verbatim. Hashing them here is what lets publish-time
        // detect the document changing between attestation and dispatch.
        String envelopeJson = documentIpfsSerialiser.serialise(command);
        String envelopeSha256 = sha256Hex(envelopeJson);

        if (ipfsPublisher.isEmpty()) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.ATTESTED_METADATA_MISMATCH,
                    "This deployment has no IPFS publisher configured, so the document's CID cannot be "
                            + "determined and its manifest cannot be attested."));
        }
        Either<ProblemDetail, String> cid = ipfsPublisher.get().contentId(envelopeJson);
        if (cid.isLeft()) {
            return Either.left(cid.getLeft());
        }

        Optional<Organisation> organisation = organisationPublicApi.findByOrganisationId(command.organisationId());
        if (organisation.isEmpty()) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.ATTESTED_METADATA_MISMATCH,
                    "Organisation %s was not found, so the document's manifest cannot be built."
                            .formatted(command.organisationId())));
        }
        Organisation org = organisation.get();
        MetadataMap commitment = documentMetadataSerialiser.serialiseToMetadataMap(command, cid.get(),
                org.getId(), org.getName(), org.getTaxIdNumber(), org.getCurrencyId(), org.getCountryCode());

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
