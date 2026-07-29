package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.cesr.exceptions.serialize.SerializeException;
import org.cardanofoundation.signify.cesr.util.Utils;

/**
 * Claims KERI agent notifications for an outstanding ceremony request, by route.
 *
 * <p>A notification is claimed when it is unread and its own claimed route ({@code note.a.r}) is one
 * of the routes the caller is waiting on — the FIRST such match wins. There is no sender check, no
 * thread-back check, and no recipient-prefix check: a real Veridian wallet's IPEX offer/grant and
 * remotesign ref replies were found, under live wallet testing, not to reliably carry those extra
 * correlation fields, so requiring them unconditionally would silently discard a legitimate reply. A
 * single agent only ever has one ceremony outstanding on a given route at a time, so the route match
 * alone is enough to identify which reply belongs to which wait.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriNotificationCorrelator {

    /** Diagnostic de-dupe for {@link #pollOnceByRoute}'s per-poll notification summary: the wait polls
     *  every ~1.5s, so logging the whole notification list every tick would be noise. Instead we log the
     *  list only when its (route, read-state) shape CHANGES — which is exactly when a wallet's
     *  offer/grant first appears — so a stuck "waiting for offer" is diagnosable from the log alone:
     *  either nothing ever appears (a routing/mailbox/contact problem, wallet-side) or something appears
     *  under a route we don't match (a wire-shape problem here). Final-with-initializer, so Lombok's
     *  {@code @RequiredArgsConstructor} excludes it. */
    private final AtomicReference<String> lastByRouteNotificationSummary = new AtomicReference<>("");

    private final KeriAttestationClient client;
    private final KeriAttestationProperties properties;

    /** A notification that was claimed: {@code notificationId} is the agent's own notification
     *  identifier (for {@link #markAndDelete}), {@code exnSaid} is the referenced exchange's SAID, and
     *  {@code exn} is that exchange's full decoded message (sender {@code i}, route {@code r}, payload
     *  {@code a}, embeds {@code e}, ...). {@code claimedRoute} is the notification's OWN claimed route
     *  ({@code note.a.r}, populated by {@link #pollOnceByRoute}) — used by callers awaiting more than one
     *  route at once ({@link KeriCredentialService#presentCredential} awaits an offer AND a grant route
     *  together and must tell which one actually matched). */
    /** KERIA's own page size for {@code Range: notes=start-end}. */
    private static final int NOTIFICATION_PAGE_SIZE = 25;

    /** Upper bound on how many notifications one poll will scan. */
    private static final int MAX_NOTIFICATIONS = 500;

    public record CorrelatedNotification(String notificationId, String exnSaid, Map<String, Object> exn,
            String claimedRoute) {
        public CorrelatedNotification(String notificationId, String exnSaid, Map<String, Object> exn) {
            this(notificationId, exnSaid, exn, null);
        }
    }

    /**
     * Polls the agent's notification queue at {@link KeriAttestationProperties#notificationPollInterval()}
     * until an unread notification whose own claimed route is one of {@code routes} arrives, or
     * {@code timeout} elapses. The FIRST such match is claimed — see this class's javadoc for why there
     * is no further correlation check.
     *
     * <p>The claimed {@link CorrelatedNotification#exnSaid()} is the FETCHED exchange's own {@code d}
     * field where present, falling back to the notification's claimed {@code note.a.d} only when the
     * fetched exn is missing its own {@code d}.
     *
     * <p>Never calls {@code mark}/{@code delete} itself — that is left to the caller, once the
     * corresponding ceremony state transition has been durably committed; see {@link #markAndDelete}.
     *
     * @return the route-matched notification, or {@link Optional#empty()} if none arrived before the
     *         timeout. Never throws on timeout.
     */
    public Optional<CorrelatedNotification> awaitByRoute(List<String> routes, Duration timeout) {
        return awaitByRoute(routes, timeout, Set.of());
    }

    /**
     * As {@link #awaitByRoute(List, Duration)}, but never claims a notification whose id is in
     * {@code excludeNoteIds}. Callers snapshot the ids currently outstanding on a route
     * ({@link #outstandingNoteIds}) BEFORE they send their own request, then pass that snapshot here so
     * a leftover unread reply from an earlier, failed/timed-out request cannot be mistaken for the reply
     * to the current one — only a notification that arrives AFTER the snapshot (a genuinely new id) is
     * claimed. This is non-destructive: the stale notifications are ignored, not marked/deleted, so a
     * concurrent legitimate reply is never at risk of being purged.
     */
    public Optional<CorrelatedNotification> awaitByRoute(List<String> routes, Duration timeout,
            Set<String> excludeNoteIds) {
        Instant deadline = Instant.now().plus(timeout);
        Instant start = Instant.now();
        int poll = 0;
        while (true) {
            try {
                Optional<CorrelatedNotification> claimed = pollOnceByRoute(routes, excludeNoteIds);
                if (claimed.isPresent()) {
                    return claimed;
                }
                if (!Instant.now().isBefore(deadline)) {
                    log.info("Timed out after {}s waiting for KERI notification on routes {}",
                            Duration.between(start, Instant.now()).toSeconds(), routes);
                    return Optional.empty();
                }
                // Heartbeat every ~15s: proves the poll loop is alive and, alongside the notification
                // summary logged by pollOnceByRoute, tells whether an empty list means "nothing is being
                // routed to this agent" (mailbox/contact issue) rather than "the code stopped polling".
                if (++poll % 10 == 0) {
                    log.info("Still waiting for KERI notification on routes {} ({}s elapsed)",
                            routes, Duration.between(start, Instant.now()).toSeconds());
                }
                Thread.sleep(properties.notificationPollInterval().toMillis());
            } catch (InterruptedException e) {
                // Restore the interrupt flag rather than swallow it (e.g. executor shutdown mid-wait) —
                // the caller's async executor is responsible for deciding what an interrupt means next.
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /**
     * Snapshots the ids of notifications currently claimable on {@code routes} (unread route matches).
     * Callers take this snapshot BEFORE sending their own request and pass it to
     * {@link #awaitByRoute(List, Duration, Set)} as the exclude set, so pre-existing debris from an
     * earlier request cannot be claimed as the reply to the new one.
     *
     * <p>Returns an empty set if the notification list can't be read/parsed. This is a fail-OPEN
     * fallback, chosen deliberately: an empty exclude set means the subsequent wait behaves as it did
     * before this guard (claim the first route match) for that one attempt — no worse than before, and
     * strictly better than failing an otherwise-valid attest on a transient agent hiccup at snapshot
     * time. The window is narrow (a single list failure exactly here) and the failure is logged by
     * {@link #listNotifications()}.
     */
    public Set<String> outstandingNoteIds(List<String> routes) {
        Set<String> ids = listNotifications().stream()
                .filter(note -> isUnreadRouteMatch(note, routes))
                .map(note -> note.i)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (!ids.isEmpty()) {
            log.info("Snapshotted {} pre-existing notification(s) on routes {} to exclude from the next wait",
                    ids.size(), routes);
        }
        return ids;
    }

    // --- one polling round ---

    /** Lists + parses the agent's notifications; empty list on any transport/parse failure (logged). */
    /**
     * Every notification on the agent, paged.
     *
     * <p>Pages deliberately, rather than calling signify's no-arg {@code notifications().list()}: that
     * overload requests {@code Range: notes=0-24} and so only ever returns the OLDEST 25. Notifications
     * are removed only on success ({@link #markAndDelete}), so every failed or timed-out step leaves one
     * behind unread — and once 25 accumulate on an agent, the reply to the CURRENT request lands beyond
     * that window and becomes permanently invisible. The symptom is a flow that works for a while and
     * then times out forever, with the wallet reporting success. Notifications live on the KERIA
     * account (the passcode), not the identifier, so minting a new agent AID does not clear them.
     *
     * <p>Bounded by {@link #MAX_NOTIFICATIONS} so a pathological queue cannot make a single poll
     * unbounded; hitting the cap is logged, because at that point the queue needs pruning rather than a
     * bigger scan.
     */
    private List<Notification> listNotifications() {
        List<Notification> all = new ArrayList<>();
        int start = 0;
        while (start < MAX_NOTIFICATIONS) {
            Notifying.Notifications.NotificationListResponse response;
            try {
                response = client.client().notifications().list(start, start + NOTIFICATION_PAGE_SIZE - 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return all;
            } catch (Exception e) {
                log.warn("Failed to list KERI notifications from index {}, will retry: {}", start, e.getMessage());
                return all;
            }

            List<Notification> page;
            try {
                page = Utils.fromJson(response.notes(), new TypeReference<List<Notification>>() {
                });
            } catch (SerializeException e) {
                log.error("Failed to parse the KERI notification list while route-matching: {}", e.getMessage());
                return all;
            }
            if (page == null || page.isEmpty()) {
                return all;
            }
            all.addAll(page);

            if (all.size() >= response.total()) {
                return all;
            }
            start += NOTIFICATION_PAGE_SIZE;
        }

        log.warn("Stopped listing KERI notifications at {} entries: the agent's queue is larger than this "
                + "scan and unread debris may be hiding a reply. Prune it (notifications are only deleted "
                + "on a successful step).", MAX_NOTIFICATIONS);

        return all;
    }

    private Optional<CorrelatedNotification> pollOnceByRoute(List<String> routes, Set<String> excludeNoteIds)
            throws InterruptedException {
        List<Notification> notes = listNotifications();

        logNotificationState(notes, routes);

        for (Notification note : notes) {
            if (!isUnreadRouteMatch(note, routes)) {
                continue;
            }
            if (note.i != null && excludeNoteIds.contains(note.i)) {
                // Pre-existing debris snapshotted before the caller's request was sent — cannot be the
                // reply to it. Skip it so the wait continues for a genuinely new notification.
                continue;
            }
            String noteExnSaid = note.a != null ? note.a.d : null;
            if (noteExnSaid == null) {
                continue;
            }
            Map<String, Object> exn;
            try {
                Optional<Object> exchange = client.client().exchanges().get(noteExnSaid);
                if (exchange.isEmpty()) {
                    // The notification is present and its route matches, but the referenced exchange is
                    // not yet retrievable from the agent — surface it and retry on the next poll rather
                    // than looping silently until timeout.
                    log.info("KERI notification matched route {} (exn {}) but the exchange is not yet "
                            + "retrievable from the agent — retrying", note.a.r, noteExnSaid);
                    continue;
                }
                exn = extractExn(exchange.get());
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to fetch KERI exchange {} while route-matching: {}", noteExnSaid, e.getMessage());
                continue;
            }
            if (exn == null) {
                continue;
            }
            log.info("Claimed KERI notification on route {} (exn {})", note.a.r, noteExnSaid);
            // Prefer the FETCHED exn's own "d" over the notification's claimed note.a.d.
            Object fetchedSaid = exn.get("d");
            String exnSaid = fetchedSaid instanceof String s ? s : noteExnSaid;
            return Optional.of(new CorrelatedNotification(note.i, exnSaid, exn, note.a.r));
        }
        return Optional.empty();
    }

    /**
     * Logs the agent's current notification list (count + each notification's route and read state) at
     * INFO, but only when that shape has CHANGED since the previous poll (see
     * {@link #lastByRouteNotificationSummary}). This is the diagnostic that makes a stuck wait ("waiting
     * for offer" forever) explainable from the log: when the list stays empty, nothing is being routed to
     * this agent at all (a KERI mailbox/contact/OOBI problem on the sending side); when the list becomes
     * non-empty but no entry matches the awaited routes, the reply arrived under an unexpected route (a
     * wire-shape problem here). An empty list resets the de-dupe so the next arrival always logs.
     */
    private void logNotificationState(List<Notification> notes, List<String> routes) {
        if (notes.isEmpty()) {
            lastByRouteNotificationSummary.set("");
            return;
        }
        // Include each notification's dt (delivery timestamp) so a fresh arrival is unmistakable against
        // old debris — even if it were somehow delivered already-read — directly answering "did a new
        // notification arrive and get read/deleted?": any entry with today's date is a live delivery.
        String summary = notes.stream()
                .map(n -> (n.a != null && n.a.r != null ? n.a.r : "?")
                        + (Boolean.TRUE.equals(n.r) ? "(read)" : "(unread)")
                        + "@" + (n.dt != null ? n.dt : "?"))
                .collect(Collectors.joining(", "));
        if (!summary.equals(lastByRouteNotificationSummary.getAndSet(summary))) {
            log.info("KERI notifications present ({}): [{}] — awaiting routes {}", notes.size(), summary, routes);
        }
    }

    /**
     * Marks {@code notificationId} as read and removes it from the agent's queue.
     *
     * <p><strong>Callers must invoke this only after the corresponding ceremony state transition has
     * already been durably committed.</strong> Deleting the notification is what stops a retry (or a
     * concurrent poller) from re-claiming the same signal; doing that before the transition is committed
     * would let a crash between the two silently lose the wallet's response.
     */
    public void markAndDelete(String notificationId) {
        try {
            client.client().notifications().mark(notificationId);
            client.client().notifications().delete(notificationId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while marking/deleting KERI notification " + notificationId, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mark/delete KERI notification " + notificationId, e);
        }
    }

    /**
     * Deletes notifications older than {@code retention}, returning how many went.
     *
     * <p>Notifications are otherwise removed only by {@link #markAndDelete} on a SUCCESSFUL step, so
     * every failed or abandoned attempt leaves one behind unread and the agent's queue grows without
     * bound. That is not merely untidy: {@link #listNotifications()} pages the whole queue on every
     * poll, so an unpruned agent makes each wait progressively more expensive.
     *
     * <p><b>Age is the only criterion, deliberately.</b> An earlier attempt at this deleted every
     * unread notification on a route immediately before sending an apply, reasoning that the apply is
     * what prompts the wallet so anything already queued had to be debris. That is false here: Veridian
     * presents spontaneously — it grants without waiting for an offer round trip — so a genuine reply
     * can legitimately already be sitting there. The purge ate real grants and was reverted. Nothing
     * about "arrived before my request" is safe to delete on.
     *
     * <p>Age is safe because it is provable rather than inferred. Every ceremony expires after
     * {@code ceremony-ttl}, so no live ceremony can still be waiting on a notification older than that;
     * {@code retention} is expected to be a comfortable multiple of it. A notification whose
     * {@code dt} cannot be parsed is never deleted — undatable means unprovable.
     *
     * <p>Runs only from the scheduled sweep, never from a request path, so it cannot race a send.
     */
    public int pruneOlderThan(Duration retention) {
        Instant cutoff = Instant.now().minus(retention);
        int pruned = 0;
        int undatable = 0;

        for (Notification note : listNotifications()) {
            if (note.i == null) {
                continue;
            }
            Optional<Instant> sentAt = parseNotificationTimestamp(note.dt);
            if (sentAt.isEmpty()) {
                undatable++;
                continue;
            }
            if (sentAt.get().isAfter(cutoff)) {
                continue;
            }
            try {
                markAndDelete(note.i);
                pruned++;
            } catch (RuntimeException e) {
                // One stubborn notification must not abort the sweep; the next run retries it.
                log.warn("Failed to prune KERI notification {}: {}", note.i, e.getMessage());
            }
        }

        if (pruned > 0 || undatable > 0) {
            log.info("Pruned {} KERI notification(s) older than {} ({} skipped as undatable)",
                    pruned, retention, undatable);
        }

        return pruned;
    }

    /** KERI timestamps are ISO-8601 with an explicit offset, e.g. {@code 2026-07-21T00:00:00.000000+00:00}. */
    private static Optional<Instant> parseNotificationTimestamp(String dt) {
        if (dt == null || dt.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(dt).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static boolean isUnreadRouteMatch(Notification note, List<String> routes) {
        boolean unread = !note.r;
        boolean routeMatches = note.a != null && note.a.r != null && routes.contains(note.a.r);
        return unread && routeMatches;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractExn(Object exchangeResult) {
        if (!(exchangeResult instanceof Map<?, ?> outer)) {
            return null;
        }
        Object exnObj = outer.get("exn");
        return exnObj instanceof Map<?, ?> ? (Map<String, Object>) exnObj : null;
    }

    // --- raw notification shape ---

    private static class Notification {
        public String i;
        public String dt;
        public boolean r;
        public NotificationAction a;

        public static class NotificationAction {
            public String r;
            public String d;
            public String m;
        }
    }
}
