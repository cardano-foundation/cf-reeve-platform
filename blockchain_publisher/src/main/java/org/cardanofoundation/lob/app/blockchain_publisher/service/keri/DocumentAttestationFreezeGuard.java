package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentConverter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.service.AttestationFreezeGuard;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.document_vault.service.VaultProblems;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;

/**
 * {@code blockchain_publisher}'s implementation of document_vault's {@link AttestationFreezeGuard}
 * port (design §5.1 step 2 / §5.2, Task 14): runs inside {@code VaultDocumentService#publish}'s
 * row-locked transaction, immediately BEFORE {@code AttestationConsumptionApi#validateAndConsume},
 * to catch two ways an attested ceremony can go stale between ATTEST and the final publish click:
 *
 * <p>Checked in this order (cheapest first — see {@link #verifyFreshness}'s own comments):
 * <ol>
 *   <li><b>missing freeze</b> — no row for this {@code (documentId, ceremonyId)} pair; impossible by
 *       construction unless the ceremony never reached ATTEST.</li>
 *   <li><b>stale freeze</b> — the freeze is older than {@code keri_attestation}'s configured
 *       {@code freeze-max-age}, so the chain tip / IPFS upload it captured at ATTEST time is no
 *       longer considered fresh enough to publish against.</li>
 *   <li><b>content drift</b> — the envelope changed since the freeze was taken. Detected by
 *       re-serialising it via the EXACT same chain Task 13's {@code DocumentAttestationTargetProvider}
 *       used to produce the frozen {@code envelope_sha256} in the first place:
 *       {@code VaultDocumentService#toPublishCommand} -&gt; {@link DocumentConverter#convertToDbDetached}
 *       -&gt; {@link DocumentIpfsSerialiser#serialise} -&gt; SHA-256. No field mapping is re-derived
 *       here, so the two computations cannot drift apart independently of the data itself.</li>
 * </ol>
 *
 * <p>Takes the {@link VaultDocumentEntity} directly (not a documentId re-lookup): the caller already
 * holds the row-locked entity inside its own transaction (design decision, Task 14) — document_vault
 * owns that entity type, so handing it over here is the natural seam rather than a second, redundant
 * lookup this class would otherwise need its own repository access to perform.
 *
 * <p>Not annotated {@code @Service} — wired as a {@code @Bean} (matching the {@code
 * DocumentAttestationTargetProvider} precedent, Task 13), conditional on
 * {@code lob.keri-attestation.enabled}.
 */
@RequiredArgsConstructor
public class DocumentAttestationFreezeGuard implements AttestationFreezeGuard {

    private final DocumentAttestationFreezeRepository freezeRepository;
    private final DocumentConverter documentConverter;
    private final DocumentIpfsSerialiser documentIpfsSerialiser;
    private final KeriAttestationProperties keriAttestationProperties;
    private final Clock clock;

    @Override
    public Optional<ProblemDetail> verifyFreshness(VaultDocumentEntity document, String ceremonyId) {
        Optional<DocumentAttestationFreezeEntity> freezeM =
                freezeRepository.findByDocumentIdAndCeremonyId(document.getId(), ceremonyId);
        if (freezeM.isEmpty()) {
            return Optional.of(freezeMissingProblem(document.getId(), ceremonyId));
        }
        DocumentAttestationFreezeEntity freeze = freezeM.get();

        // Cheapest-first (matches VaultDocumentService.upload's own ordering rationale): the age
        // check is a subtraction over an already-loaded timestamp, no serialisation work needed.
        Duration age = Duration.between(freeze.getCreatedAt(), LocalDateTime.now(clock));
        Duration maxAge = keriAttestationProperties.freezeMaxAge();
        if (age.compareTo(maxAge) > 0) {
            return Optional.of(freezeTooOldProblem(document.getId(), ceremonyId, age, maxAge));
        }

        String currentEnvelopeSha256 = recomputeEnvelopeSha256(document);
        if (!currentEnvelopeSha256.equals(freeze.getEnvelopeSha256())) {
            return Optional.of(contentChangedProblem(document.getId(), ceremonyId));
        }

        return Optional.empty();
    }

    /**
     * Re-serialises the envelope via the identical chain that produced the frozen fingerprint — see
     * the class javadoc's byte-identity argument. Never touches IPFS or the chain: this recomputes
     * only the fingerprint, it does not re-freeze anything.
     */
    private String recomputeEnvelopeSha256(VaultDocumentEntity document) {
        DocumentPublishCommand command = VaultDocumentService.toPublishCommand(document);
        DocumentEntity entity = documentConverter.convertToDbDetached(command);
        String envelopeJson = documentIpfsSerialiser.serialise(entity);
        return sha256Hex(envelopeJson);
    }

    private static String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static ProblemDetail freezeMissingProblem(String documentId, String ceremonyId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "No attestation freeze found for document %s / ceremony %s.".formatted(documentId, ceremonyId));
        problem.setTitle(VaultProblems.ATTESTATION_FREEZE_MISSING);
        return problem;
    }

    private static ProblemDetail contentChangedProblem(String documentId, String ceremonyId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "Document %s content changed since ceremony %s attested it; re-attest required."
                        .formatted(documentId, ceremonyId));
        problem.setTitle(VaultProblems.ATTESTED_CONTENT_CHANGED);
        return problem;
    }

    private static ProblemDetail freezeTooOldProblem(String documentId, String ceremonyId, Duration age, Duration maxAge) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "Attestation freeze for document %s / ceremony %s is %s old, exceeding the configured maximum of %s; re-attest required."
                        .formatted(documentId, ceremonyId, age, maxAge));
        problem.setTitle(VaultProblems.ATTESTED_METADATA_MISMATCH);
        return problem;
    }

}
