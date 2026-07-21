package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentConverter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.document_vault.service.VaultProblems;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationTargetProvider;
import org.cardanofoundation.lob.app.keri_attestation.service.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * {@code blockchain_publisher}'s implementation of the {@code keri_attestation} module's {@link
 * AttestationTargetProvider} port for {@code DOCUMENT} targets (design §5.2, Task 13).
 *
 * <p><b>Byte-identity argument</b> — why the frozen bytes equal what dispatch will later produce:
 * {@link #prepareDigest} builds its serialiser input via {@code
 * DocumentConverter#convertToDbDetached(VaultDocumentService#toPublishCommand(document))} — the
 * EXACT SAME two static/bean calls the real publish path applies, in the same order, to the same
 * {@link VaultDocumentEntity} fields: {@code VaultDocumentService#publish} (and the retry job)
 * builds a {@code DocumentPublishCommand} via {@code toPublishCommand}, which {@code
 * BlockchainPublisherEventHandler#handleDocumentPublishCommand} hands to {@code
 * BlockchainPublisherService#storeDocumentForDispatchLater}, which calls {@code
 * DocumentConverter#convertToDbDetached} to build the persisted {@link DocumentEntity} that {@code
 * DocumentL1TransactionCreator#pullBlockchainTransaction} later serialises. No field mapping is
 * re-derived here — reusing the same mapper is what makes the two paths byte-identical by
 * construction rather than by two implementations happening to agree. From there, {@link
 * #prepareDigest} calls the identical collaborators {@code DocumentL1TransactionCreator} calls
 * ({@link DocumentIpfsSerialiser#serialise}, {@link IpfsPublisher#publish}, {@link
 * BlockchainReaderPublicApiIF#getChainTip()}, {@link DocumentMetadataSerialiser#serialiseToMetadataMap})
 * and freezes the exact result, which a later task's dispatch hook reuses verbatim rather than
 * recomputing (design §5.3) - the only non-deterministic input ({@code
 * DocumentMetadataSerialiser}'s wall-clock {@code metadata.timestamp}) is captured once here and
 * never regenerated.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentAttestationTargetProvider implements AttestationTargetProvider {

    public static final String TARGET_TYPE = "DOCUMENT";

    /** Cardano metadata label the frozen 1447 map is (later) published under. */
    private static final String METADATA_LABEL = "1447";

    public static final String ERROR_FREEZING_DOCUMENT_METADATA = "ERROR_FREEZING_DOCUMENT_METADATA";

    private final VaultDocumentService vaultDocumentService;
    private final DocumentConverter documentConverter;
    private final DocumentIpfsSerialiser documentIpfsSerialiser;
    private final DocumentMetadataSerialiser documentMetadataSerialiser;
    private final BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private final Optional<IpfsPublisher> ipfsPublisher;
    private final Cip170MetadataFactory cip170MetadataFactory;
    private final DocumentAttestationFreezeRepository freezeRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final Clock clock;

    @Override
    public String targetType() {
        return TARGET_TYPE;
    }

    @Override
    public Optional<ProblemDetail> authorize(String targetId, String userId) {
        return vaultDocumentService.loadForAttestation(targetId, userId).fold(Optional::of, document -> Optional.empty());
    }

    @Override
    public Either<ProblemDetail, AttestationDigest> prepareDigest(String targetId, String ceremonyId) {
        // Idempotent per (documentId, ceremonyId) - design §5.2: an existing row is authoritative and
        // is returned unchanged, without touching IPFS or the chain again.
        Optional<DocumentAttestationFreezeEntity> existing =
                freezeRepository.findByDocumentIdAndCeremonyId(targetId, ceremonyId);
        if (existing.isPresent()) {
            return Either.right(new AttestationDigest(existing.get().getDigestQb64(), METADATA_LABEL));
        }

        if (ipfsPublisher.isEmpty()) {
            return Either.left(ipfsUnavailableProblem());
        }

        // authorize(targetId, userId) was already run synchronously by the caller (keri_attestation's
        // CeremonyService#create / KeriAttestService#startAttest both call authorize immediately
        // before prepareDigest, in the same request thread) - this re-derives the same current user
        // via the same SecurityContextHolder-backed helper loadForAttestation itself uses, rather than
        // fabricating a userId, since this port method carries no userId parameter of its own.
        String userId = securityHelper.getCurrentUserId();
        return vaultDocumentService.loadForAttestation(targetId, userId)
                .flatMap(document -> freezeAndDigest(document, ceremonyId));
    }

    private Either<ProblemDetail, AttestationDigest> freezeAndDigest(VaultDocumentEntity vaultDocument, String ceremonyId) {
        DocumentPublishCommand command = VaultDocumentService.toPublishCommand(vaultDocument);
        DocumentEntity document = documentConverter.convertToDbDetached(command);

        String envelopeJson = documentIpfsSerialiser.serialise(document);
        String envelopeSha256 = sha256Hex(envelopeJson);

        return ipfsPublisher.get().publish(envelopeJson).flatMap(cid ->
                blockchainReaderPublicApi.getChainTip().flatMap(chainTip -> {
                    long creationSlot = chainTip.getAbsoluteSlot();
                    MetadataMap metadataMap = documentMetadataSerialiser.serialiseToMetadataMap(document, cid, creationSlot);

                    try {
                        byte[] frozenBytes = CborSerializationUtil.serialize(metadataMap.getMap());
                        String digestQb64 = cip170MetadataFactory.digestOf(metadataMap);

                        DocumentAttestationFreezeEntity freeze = new DocumentAttestationFreezeEntity();
                        freeze.setId(UUID.randomUUID().toString());
                        freeze.setDocumentId(vaultDocument.getId());
                        freeze.setCeremonyId(ceremonyId);
                        freeze.setIpfsCid(cid);
                        freeze.setFrozenMetadataCbor(frozenBytes);
                        freeze.setDigestQb64(digestQb64);
                        freeze.setMetadataCreationSlot(creationSlot);
                        freeze.setEnvelopeSha256(envelopeSha256);
                        freeze.setCreatedAt(LocalDateTime.now(clock));

                        freezeRepository.save(freeze);

                        log.info("Froze DOCUMENT attestation metadata for ceremony:{}, document:{}, cid:{}",
                                ceremonyId, vaultDocument.getId(), cid);

                        return Either.right(new AttestationDigest(digestQb64, METADATA_LABEL));
                    } catch (CborException e) {
                        log.error("Error CBOR-serialising frozen document attestation metadata for ceremony:{}, document:{}",
                                ceremonyId, vaultDocument.getId(), e);
                        return Either.left(cborSerialisationErrorProblem(e));
                    }
                }));
    }

    private static String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static ProblemDetail ipfsUnavailableProblem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Document attestation requires IPFS; no IpfsPublisher is configured in this deployment.");
        problem.setTitle(VaultProblems.DOCUMENT_PUBLISHING_UNAVAILABLE);
        return problem;
    }

    private static ProblemDetail cborSerialisationErrorProblem(CborException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Error serialising frozen document metadata to cbor: %s".formatted(e.getMessage()));
        problem.setTitle(ERROR_FREEZING_DOCUMENT_METADATA);
        return problem;
    }

}
