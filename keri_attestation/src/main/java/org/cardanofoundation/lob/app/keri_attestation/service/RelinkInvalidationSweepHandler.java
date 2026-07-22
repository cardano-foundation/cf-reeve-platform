package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;

/**
 * R2 fix (Codex re-verification): closes the phantom-insert window between {@code
 * KeriOobiService#persistLink}'s relink invalidation sweep (ceremony locks only, taken and released
 * BEFORE the link lock is ever acquired — see that method's javadoc) and {@code
 * KeriOobiService#lockAndUpsertLink}'s actual link-row write. A {@code CeremonyService#create} call
 * landing in exactly that window inserts a ceremony bound to the OLD {@code bindingVersion}: the
 * first (in-transaction) sweep already ran and never saw it, and by the time {@code create} captured
 * its {@code bindingVersion} the link row was not yet locked, so it read the pre-relink value. Also
 * closes {@code KeriOobiService#persistLink}'s documented "residual narrow race" (its own javadoc),
 * where the plain discovery read misses a concurrent relink and the locked re-read completes the
 * write without invalidating anything.
 *
 * <p><b>Why a second, separate AFTER_COMMIT pass instead of just re-running the in-transaction sweep
 * again right before the link write:</b> the identity-link row lock is the LAST thing {@code
 * lockAndUpsertLink} acquires (see its own javadoc for why: this module's global lock order is
 * ceremony-before-link, matching every completion path). Running a ceremony-locking sweep AFTER that
 * point but still inside the same transaction would require taking ceremony locks WHILE the link lock
 * is already held — the exact inversion of that order, and a Postgres deadlock risk against
 * completion paths (which always lock ceremony first, then link). Deferring this sweep to its own,
 * later, AFTER_COMMIT transaction — mirroring {@code document_vault}'s {@code
 * DocumentLedgerUpdateHandler} — means the link lock has long been released by commit time, so this
 * class only ever takes ceremony locks, never both at once.
 *
 * <p>Query shape mirrors {@code KeriOobiService#invalidateOpenCeremonies}: an unlocked discovery read
 * (candidates may already be stale or gone by the time they are revisited), then each candidate is
 * re-fetched and re-checked under {@link KeriAttestationCeremonyRepository#findByIdForUpdate} before
 * being mutated, so a concurrent legitimate transition (e.g. {@code CeremonyService#validateAndConsume}
 * finishing the very same ceremony between the discovery read and this write) is never clobbered back
 * to {@code FAILED}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RelinkInvalidationSweepHandler {

    private static final Set<CeremonyState> TERMINAL_STATES =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);

    private final KeriAttestationCeremonyRepository ceremonyRepository;

    // fallbackExecution = true and @Async mirror document_vault's DocumentLedgerUpdateHandler exactly
    // (see that class's javadoc for the rationale): some emitters — including this class's own tests —
    // invoke the publish with no active transaction synchronization present, and AFTER_COMMIT listeners
    // are silently skipped in that case without fallbackExecution. propagation = REQUIRES_NEW is
    // required (Spring refuses a @TransactionalEventListener method also carrying a plain REQUIRED
    // @Transactional) and is also correct on its own merits: this runs on the @Async executor thread,
    // decoupled from KeriOobiService's relink transaction, so it always needs its own fresh transaction.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRelinkCompleted(RelinkCompletedEvent event) {
        List<KeriAttestationCeremonyEntity> candidates =
                ceremonyRepository.findByUserIdAndStateNotIn(event.userId(), TERMINAL_STATES);
        for (KeriAttestationCeremonyEntity candidate : candidates) {
            ceremonyRepository.findByIdForUpdate(candidate.getId()).ifPresent(ceremony -> {
                if (TERMINAL_STATES.contains(ceremony.getState())
                        || ceremony.getBindingVersion() >= event.newVersion()) {
                    return;
                }
                log.info("Failing ceremony {} for user {}: bindingVersion {} is stale against relink "
                                + "version {}.", ceremony.getId(), event.userId(), ceremony.getBindingVersion(),
                        event.newVersion());
                ceremony.setState(CeremonyState.FAILED);
                ceremony.setErrorTitle(KeriAttestationProblems.IDENTITY_RELINKED);
                ceremony.setErrorDetail("Identity for user %s was relinked to a different AID."
                        .formatted(event.userId()));
                ceremonyRepository.save(ceremony);
            });
        }
    }
}
