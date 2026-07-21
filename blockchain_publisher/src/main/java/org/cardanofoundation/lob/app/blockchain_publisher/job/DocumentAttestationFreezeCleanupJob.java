package org.cardanofoundation.lob.app.blockchain_publisher.job;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi;

/**
 * Freeze-row housekeeping for KERI wallet-attestation of document publishes (design §5.2/§7, Task
 * 13). {@code document_attestation_freeze} rows are created at ATTEST time, before the ceremony is
 * necessarily ever consumed - an abandoned ceremony (the user never finishes the wizard, or the
 * ceremony expires/fails at a later step) leaves an orphan freeze row behind forever unless
 * something sweeps it.
 *
 * <p>Deletion is deliberately conservative: only rows for ceremonies {@code
 * AttestationConsumptionApi#findTerminalNonConsumedCeremonyIds} reports as {@code FAILED} or {@code
 * EXPIRED} are removed. A ceremony id that query does not return - whether it is still open,
 * {@code CONSUMED}, or (rarely) already purged by {@code keri_attestation}'s own {@code
 * CeremonyCleanupJob} - is left alone: {@code CONSUMED} freezes are load-bearing for {@code
 * DocumentL1TransactionCreator}'s dispatch hook (a later task), which can retry a stuck publish long
 * after the ceremony itself was consumed, so they must NEVER be deleted here, and there is no way to
 * safely tell "purged FAILED" apart from "unknown" from this side of the module boundary. The
 * consequence of that conservatism is a handful of freeze rows may occasionally outlive
 * {@code keri_attestation}'s own ceremony-row retention window before this sweep catches them on a
 * later run - never the reverse.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentAttestationFreezeCleanupJob {

    private static final int RETENTION_DAYS = 7;

    private final DocumentAttestationFreezeRepository freezeRepository;
    private final AttestationConsumptionApi attestationConsumptionApi;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${lob.blockchain_publisher.document_attestation_freeze_cleanup.fixed_delay:PT30M}")
    @Transactional
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(RETENTION_DAYS);
        List<DocumentAttestationFreezeEntity> candidates = freezeRepository.findByCreatedAtBefore(cutoff);
        if (candidates.isEmpty()) {
            return;
        }

        Set<String> candidateCeremonyIds = candidates.stream()
                .map(DocumentAttestationFreezeEntity::getCeremonyId)
                .collect(Collectors.toSet());
        Set<String> deletable = Set.copyOf(attestationConsumptionApi.findTerminalNonConsumedCeremonyIds(candidateCeremonyIds));
        if (deletable.isEmpty()) {
            return;
        }

        List<DocumentAttestationFreezeEntity> toDelete = candidates.stream()
                .filter(freeze -> deletable.contains(freeze.getCeremonyId()))
                .toList();
        freezeRepository.deleteAll(toDelete);

        log.info("blockchain_publisher document attestation freeze cleanup deleted {} row(s) for terminal "
                + "(FAILED/EXPIRED) ceremonies older than {} days", toDelete.size(), RETENTION_DAYS);
    }

}
