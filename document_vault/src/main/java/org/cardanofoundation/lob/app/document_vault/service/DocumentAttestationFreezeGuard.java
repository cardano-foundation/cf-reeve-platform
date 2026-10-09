package org.cardanofoundation.lob.app.document_vault.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.repository.DocumentAttestationFreezeRepository;

/**
 * The publish-time freshness gate for an attested document. Moved here from {@code
 * blockchain_publisher} along with the freeze store it reads: the pod that runs {@code publish} is the
 * same one that ran the ceremony, and it does not load {@code blockchain_publisher} at all.
 *
 * <p>Runs inside {@code VaultDocumentService#publish}'s row-locked transaction, immediately BEFORE
 * {@code AttestationConsumptionApi#validateAndConsume} — a stale or drifted freeze must never reach
 * (and burn) the ceremony's compare-and-set to {@code CONSUMED}.
 *
 * <p>Checks run cheapest-first so an obviously-missing or expired freeze costs no serialisation.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentAttestationFreezeGuard implements AttestationFreezeGuard {

    private final DocumentAttestationFreezeRepository freezeRepository;
    private final DocumentIpfsSerialiser documentIpfsSerialiser;
    private final Duration freezeMaxAge;
    private final Clock clock;

    @Override
    public Optional<ProblemDetail> verifyFreshness(VaultDocumentEntity document, String ceremonyId) {
        Optional<DocumentAttestationFreezeEntity> freezeM =
                freezeRepository.findByDocumentIdAndCeremonyId(document.getId(), ceremonyId);
        if (freezeM.isEmpty()) {
            return Optional.of(VaultProblems.unprocessable(VaultProblems.ATTESTATION_FREEZE_MISSING,
                    "No attestation freeze for document %s and ceremony %s."
                            .formatted(document.getId(), ceremonyId)));
        }
        DocumentAttestationFreezeEntity freeze = freezeM.get();

        Duration age = Duration.between(freeze.getCreatedAt(), LocalDateTime.now(clock));
        if (age.compareTo(freezeMaxAge) > 0) {
            return Optional.of(VaultProblems.unprocessable(VaultProblems.ATTESTED_METADATA_MISMATCH,
                    "Attestation freeze for document %s is %s old, older than the %s limit."
                            .formatted(document.getId(), age, freezeMaxAge)));
        }

        // Content-drift check last: it re-serialises the envelope, so it is the expensive one. A
        // mismatch means the document changed after the wallet attested it — publishing would anchor an
        // attestation the holder never gave for these bytes.
        String currentEnvelopeSha256 = DocumentAttestationTargetProvider.sha256Hex(
                documentIpfsSerialiser.serialise(VaultDocumentService.toPublishCommand(document)));
        if (!currentEnvelopeSha256.equals(freeze.getEnvelopeSha256())) {
            log.warn("Envelope drifted since attestation for document:{}, ceremony:{}", document.getId(), ceremonyId);

            return Optional.of(VaultProblems.unprocessable(VaultProblems.ATTESTED_CONTENT_CHANGED,
                    "Document %s changed since ceremony %s attested it.".formatted(document.getId(), ceremonyId)));
        }

        return Optional.empty();
    }
}
