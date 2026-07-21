package org.cardanofoundation.lob.app.keri_attestation.job;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;

/**
 * Ceremony-row housekeeping (design §4.2), same {@code @Scheduled(fixedDelayString = ...)} /
 * {@code @Transactional} idiom as document_vault's {@code DocumentDispatchRetryJob}: component-scanned
 * only when {@code lob.keri-attestation.enabled=true}, and inert unless the consuming application also
 * enables Spring scheduling.
 *
 * <p>Two independent sweeps:
 * <ol>
 *   <li>{@code expireOverdueCeremonies()} — {@code CeremonyService} already applies TTL expiry
 *       lazily on every read/transition of a single ceremony, but a ceremony nobody ever looks at
 *       again (the user simply walked away mid-flow) would otherwise sit forever in a non-terminal
 *       state, still counting against that user's active-ceremony limit. This sweep is what actually
 *       frees the slot.</li>
 *   <li>{@code deleteOldTerminalCeremonies()} — terminal rows (CONSUMED/FAILED/EXPIRED) are pure
 *       audit trail once the ceremony is done; this purges anything older than
 *       7 days so the table does not grow unbounded. This only ever deletes
 *       ceremony rows in this module — {@code blockchain_publisher}'s own freeze-row cleanup for
 *       terminal ceremonies is a separate job (Task 13), not cascaded from here.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CeremonyCleanupJob {

    private static final Set<CeremonyState> TERMINAL =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);
    private static final int RETENTION_DAYS = 7;

    private final KeriAttestationCeremonyRepository ceremonyRepository;

    @Scheduled(fixedDelayString = "${lob.keri-attestation.cleanup.fixed_delay:PT10M}")
    @Transactional
    public void sweep() {
        expireOverdueCeremonies();
        deleteOldTerminalCeremonies();
    }

    private void expireOverdueCeremonies() {
        LocalDateTime now = LocalDateTime.now();
        List<KeriAttestationCeremonyEntity> candidates =
                ceremonyRepository.findByStateNotInAndExpiresAtBefore(TERMINAL, now);
        if (candidates.isEmpty()) {
            return;
        }
        int expiredCount = 0;
        for (KeriAttestationCeremonyEntity candidate : candidates) {
            // The discovery read above is unlocked, so a candidate can have legitimately moved on
            // (e.g. CeremonyService#completeStep landed a real transition) by the time we get here.
            // Re-fetch under the row lock and re-verify before writing — otherwise this sweep could
            // silently clobber a just-completed ceremony back to EXPIRED with no CAS to catch it,
            // since this entity deliberately has no @Version column (see its class javadoc).
            Optional<KeriAttestationCeremonyEntity> locked = ceremonyRepository.findByIdForUpdate(candidate.getId());
            if (locked.isEmpty()) {
                continue;
            }
            KeriAttestationCeremonyEntity ceremony = locked.get();
            if (TERMINAL.contains(ceremony.getState()) || ceremony.getExpiresAt().isAfter(now)) {
                continue;
            }
            ceremony.setState(CeremonyState.EXPIRED);
            ceremony.setUpdatedAt(now);
            ceremonyRepository.save(ceremony);
            expiredCount++;
        }
        if (expiredCount > 0) {
            log.info("keri_attestation cleanup expired {} overdue ceremony(ies)", expiredCount);
        }
    }

    private void deleteOldTerminalCeremonies() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long deleted = ceremonyRepository.deleteByStateInAndUpdatedAtBefore(TERMINAL, cutoff);
        if (deleted > 0) {
            log.info("keri_attestation cleanup deleted {} terminal ceremony(ies) older than {} days",
                    deleted, RETENTION_DAYS);
        }
    }
}
