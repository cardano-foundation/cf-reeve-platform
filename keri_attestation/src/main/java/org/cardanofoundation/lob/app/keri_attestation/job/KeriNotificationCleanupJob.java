package org.cardanofoundation.lob.app.keri_attestation.job;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator;

/**
 * Prunes the KERI agent's notification queue.
 *
 * <p>Notifications are otherwise deleted only when a step SUCCEEDS, so every failed, abandoned or
 * timed-out attempt leaves one behind unread and the queue only ever grows. That eventually breaks the
 * flow outright: a wait pages the whole queue on every poll, and a sufficiently clogged agent makes
 * each one slower while burying nothing useful. This sweep is what keeps it bounded.
 *
 * <p>Deletes strictly by AGE, never by "this looks stale". An earlier attempt purged every unread
 * notification on a route just before sending an apply, on the theory that the apply is what prompts
 * the wallet; that deleted real grants — Veridian presents spontaneously, so a genuine reply can
 * already be queued — and was reverted. Age is the one criterion that is provable rather than
 * inferred: no ceremony outlives {@code ceremony-ttl}, so nothing can still be waiting on a
 * notification older than the retention window, which is expected to be a comfortable multiple of it.
 *
 * <p>Scheduled, never inline on a request path, so it cannot race a send.
 *
 * <p>Component-scanned only when {@code lob.keri-attestation.enabled=true}, additionally gated on a
 * configured KERIA url (there is no agent to prune without one), and inert unless the consuming
 * application enables Spring scheduling.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriNotificationCleanupJob {

    private final KeriNotificationCorrelator correlator;

    /**
     * How long a notification is kept before it is considered debris. Must stay comfortably above
     * {@code lob.keri-attestation.ceremony-ttl} (default PT1H) — that is what makes deletion provably
     * safe rather than a guess.
     */
    @Value("${lob.keri-attestation.notification-retention:PT24H}")
    private Duration notificationRetention;

    @Scheduled(fixedDelayString = "${lob.keri-attestation.notification-cleanup.fixed_delay:PT1H}",
            initialDelayString = "${lob.keri-attestation.notification-cleanup.initial_delay:PT5M}")
    public void sweep() {
        try {
            correlator.pruneOlderThan(notificationRetention);
        } catch (RuntimeException e) {
            // Housekeeping must never take the scheduler down; the next run retries.
            log.warn("KERI notification prune failed, will retry on the next sweep: {}", e.getMessage());
        }
    }

}
