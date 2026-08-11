package org.cardanofoundation.lob.app.keri_attestation.job;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
 * Ceremony-row housekeeping, same {@code @Scheduled(fixedDelayString = ...)} /
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
 *   <li>{@code failStaleWaitingSteps()} — a ceremony can also get stuck
 *       <em>inside</em> a single waiting step (e.g. the backend restarts mid-wait and no worker is ever
 *       left to complete or fail it) well before its overall ceremony TTL is up. This sweep fails a
 *       WAITING-state ceremony (CREDENTIAL_REQUESTED, AUTH_BEGIN_SUBMITTED, ATTEST_REQUESTED) whose
 *       {@code updatedAt} is older than that step's own timeout plus
 *       {@link KeriAttestationProperties#stepTimeoutGrace()} with
 *       {@code FAILED(KERI_STEP_TIMED_OUT)}. FAILED is terminal for this ceremony; recovery means the
 *       user creates a new ceremony, which fast-forwards past already-completed one-time steps via the
 *       identity link — so this sweep frees a stuck flow rather than dead-ending the user.</li>
 *   <li>{@code deleteOldTerminalCeremonies()} — FAILED/EXPIRED rows are pure audit trail
 *       once the ceremony is done; this purges anything older than 7 days so the table does not grow
 *       unbounded. {@code CONSUMED} rows are deliberately excluded from this purge — see the method's
 *       own javadoc. This only ever deletes ceremony rows in this module; freeze-row cleanup for
 *       terminal ceremonies is a separate job, not cascaded from here.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CeremonyCleanupJob {

    private static final Set<CeremonyState> TERMINAL =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);
    /** The subset of {@link #TERMINAL} eligible for {@link #deleteOldTerminalCeremonies}'s
     *  purge — see that method's javadoc for why {@code CONSUMED} is excluded. */
    private static final Set<CeremonyState> PURGEABLE_TERMINAL =
            EnumSet.of(CeremonyState.FAILED, CeremonyState.EXPIRED);
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

    /**
     * Step-level stale detection — see this class's javadoc.
     *
     * <p><b>Per-state budget:</b> {@code CREDENTIAL_REQUESTED} legitimately spans up to
     * <em>two</em> wallet-approval waits — offer, then grant, per {@code KeriCredentialService}'s
     * two-phase IPEX flow — so its budget is {@code 2 × remotesignTimeout + grace}
     * rather than the single {@code remotesignTimeout + grace} every other waiting state gets. Without
     * this, a ceremony legitimately still waiting on the SECOND wallet approval (grant, after an already
     * -completed offer/agree round trip) could be swept as stale mid-flight.
     *
     * <p><b>Synchronous refactor note:</b> {@code KeriCredentialService#presentCredential} now runs the
     * whole apply→offer→agree→grant→admit round trip on the ORIGINAL request thread (no more background
     * continuation, no more mid-flight phase persist/heartbeat between the two waits).
     * A ceremony genuinely still in flight therefore never reaches this sweep at all — its row simply
     * isn't touched again until the request thread returns (success or failure). This sweep now only
     * ever fires for a ceremony whose request thread died mid-flight (e.g. the process crashed) and
     * therefore never will complete or fail the step itself; the doubled budget remains the correct
     * bound for that case, since a live (uncrashed) request can legitimately take up to that long.
     */
    private void failStaleWaitingSteps() {
        LocalDateTime now = LocalDateTime.now();
        Map<CeremonyState, Duration> budgets = stepTimeoutBudgets();
        // Broad, unlocked discovery read: the shortest of the per-state budgets above, plus grace. Every
        // other (longer) budget is re-checked precisely per-candidate below, so a candidate that isn't
        // actually overdue for its specific state is simply skipped, never clobbered.
        Duration shortestBudget = budgets.values().stream().min(Duration::compareTo).orElseThrow();
        LocalDateTime discoveryCutoff = now.minus(shortestBudget).minus(properties.stepTimeoutGrace());
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
            LocalDateTime stepCutoff = now.minus(budgets.get(waitingState)).minus(properties.stepTimeoutGrace());
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

    /** Per-state sweep budget: the maximum time a ceremony may legitimately sit in a given
     *  {@link #WAITING} state before the sweep considers it stale (before {@link #properties}'
     *  {@code stepTimeoutGrace} is added on top). */
    private Map<CeremonyState, Duration> stepTimeoutBudgets() {
        Duration remotesignTimeout = properties.remotesignTimeout();
        return Map.of(
                CeremonyState.CREDENTIAL_REQUESTED, remotesignTimeout.multipliedBy(2),
                CeremonyState.ATTEST_REQUESTED, remotesignTimeout,
                CeremonyState.AUTH_BEGIN_SUBMITTED, properties.authBeginRollbackWindow());
    }

    /**
     * Purges FAILED/EXPIRED rows older than the retention window. {@code CONSUMED} rows are
     * deliberately excluded: they are the durable attestation record that {@code blockchain_publisher}'s
     * dispatch (M3) reloads on every retry to build label-170 metadata — the dispatcher can legitimately
     * retry a stuck publish well past 7 days after the ceremony itself was consumed, and a purged row
     * would make that late dispatch impossible (it has nothing left to read the attestation from). One
     * {@code CONSUMED} row persists per published document; that growth is accepted as the cost of the
     * dispatch retry sweep always having what it needs.
     */
    private void deleteOldTerminalCeremonies() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long deleted = ceremonyRepository.deleteByStateInAndUpdatedAtBefore(PURGEABLE_TERMINAL, cutoff);
        if (deleted > 0) {
            log.info("keri_attestation cleanup deleted {} terminal ceremony(ies) older than {} days",
                    deleted, RETENTION_DAYS);
        }
    }
}
