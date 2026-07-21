package org.cardanofoundation.lob.app.keri_attestation.job;

import java.time.Duration;
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

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAttestationProblems;

/**
 * Ceremony-row housekeeping (design §4.2), same {@code @Scheduled(fixedDelayString = ...)} /
 * {@code @Transactional} idiom as document_vault's {@code DocumentDispatchRetryJob}: component-scanned
 * only when {@code lob.keri-attestation.enabled=true}, and inert unless the consuming application also
 * enables Spring scheduling.
 *
 * <p>Three independent sweeps:
 * <ol>
 *   <li>{@code expireOverdueCeremonies()} — {@code CeremonyService} already applies TTL expiry
 *       lazily on every read/transition of a single ceremony, but a ceremony nobody ever looks at
 *       again (the user simply walked away mid-flow) would otherwise sit forever in a non-terminal
 *       state, still counting against that user's active-ceremony limit. This sweep is what actually
 *       frees the slot.</li>
 *   <li>{@code failStaleWaitingSteps()} (F4 fix, design §4.2/§7) — a ceremony can also get stuck
 *       <em>inside</em> a single waiting step (e.g. the backend restarts mid-wait and no worker is ever
 *       left to complete or fail it) well before its overall ceremony TTL is up. This sweep fails a
 *       WAITING-state ceremony (CREDENTIAL_REQUESTED, AUTH_BEGIN_SUBMITTED, ATTEST_REQUESTED) whose
 *       {@code updatedAt} is older than that step's own timeout plus
 *       {@link KeriAttestationProperties#stepTimeoutGrace()} with
 *       {@code FAILED(KERI_STEP_TIMED_OUT)}. FAILED is terminal for this ceremony; recovery means the
 *       user creates a new ceremony, which fast-forwards past already-completed one-time steps via the
 *       identity link — so this sweep frees a stuck flow rather than dead-ending the user.</li>
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
    private static final Set<CeremonyState> WAITING =
            EnumSet.of(CeremonyState.CREDENTIAL_REQUESTED, CeremonyState.AUTH_BEGIN_SUBMITTED,
                    CeremonyState.ATTEST_REQUESTED);
    private static final int RETENTION_DAYS = 7;

    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final KeriAttestationProperties properties;

    @Scheduled(fixedDelayString = "${lob.keri-attestation.cleanup.fixed_delay:PT10M}")
    @Transactional
    public void sweep() {
        expireOverdueCeremonies();
        failStaleWaitingSteps();
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

    /** Step-level stale detection (F4 fix, design §4.2/§7) — see this class's javadoc. */
    private void failStaleWaitingSteps() {
        LocalDateTime now = LocalDateTime.now();
        // Broad, unlocked discovery read: the shortest possible step timeout (remotesignTimeout, used
        // by CREDENTIAL_REQUESTED/ATTEST_REQUESTED) plus grace. AUTH_BEGIN_SUBMITTED's own longer
        // authBeginRollbackWindow is re-checked precisely per-candidate below, so a candidate that isn't
        // actually overdue for its specific state is simply skipped, never clobbered.
        LocalDateTime discoveryCutoff = now.minus(shortestStepTimeout()).minus(properties.stepTimeoutGrace());
        List<KeriAttestationCeremonyEntity> candidates =
                ceremonyRepository.findByStateInAndUpdatedAtBefore(WAITING, discoveryCutoff);
        if (candidates.isEmpty()) {
            return;
        }
        int failedCount = 0;
        for (KeriAttestationCeremonyEntity candidate : candidates) {
            // Same race-safety idiom as expireOverdueCeremonies(): re-fetch under the row lock and
            // re-verify (including the precise per-state cutoff) before writing, since the discovery
            // read above is unlocked and this entity has no @Version column.
            Optional<KeriAttestationCeremonyEntity> locked = ceremonyRepository.findByIdForUpdate(candidate.getId());
            if (locked.isEmpty()) {
                continue;
            }
            KeriAttestationCeremonyEntity ceremony = locked.get();
            CeremonyState waitingState = ceremony.getState();
            if (!WAITING.contains(waitingState)) {
                continue;
            }
            LocalDateTime stepCutoff = now.minus(stepTimeoutFor(waitingState)).minus(properties.stepTimeoutGrace());
            if (ceremony.getUpdatedAt().isAfter(stepCutoff)) {
                continue;
            }
            ceremony.setState(CeremonyState.FAILED);
            ceremony.setErrorTitle(KeriAttestationProblems.KERI_STEP_TIMED_OUT);
            ceremony.setErrorDetail(
                    "Ceremony %s timed out waiting in state %s.".formatted(ceremony.getId(), waitingState));
            ceremony.setUpdatedAt(now);
            ceremonyRepository.save(ceremony);
            failedCount++;
        }
        if (failedCount > 0) {
            log.info("keri_attestation cleanup failed {} stale waiting-step ceremony(ies) with {}", failedCount,
                    KeriAttestationProblems.KERI_STEP_TIMED_OUT);
        }
    }

    private Duration shortestStepTimeout() {
        Duration remotesign = properties.remotesignTimeout();
        Duration authBeginRollback = properties.authBeginRollbackWindow();
        return remotesign.compareTo(authBeginRollback) <= 0 ? remotesign : authBeginRollback;
    }

    private Duration stepTimeoutFor(CeremonyState waitingState) {
        return switch (waitingState) {
            case CREDENTIAL_REQUESTED, ATTEST_REQUESTED -> properties.remotesignTimeout();
            case AUTH_BEGIN_SUBMITTED -> properties.authBeginRollbackWindow();
            default -> throw new IllegalStateException("Not a waiting state: " + waitingState);
        };
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
