package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * Correlates KERI agent notifications back to a specific outstanding request (design §4.3).
 *
 * <p>This is the safety net that prevents cross-ceremony notification hijacking: a matching
 * {@code route} alone is <strong>not</strong> enough to claim a notification, because any wallet the
 * agent is in contact with can raise one on the same route (e.g. two ceremonies waiting on
 * {@code /exn/ipex/grant} at once, or an unrelated party probing the agent). A notification is only
 * claimed if, in addition to the route match, the exchange ({@code exn}) it references was sent by
 * the expected sender <em>and</em> threads back to the exact request {@code exn} SAID this caller is
 * waiting on — either directly via the exn's {@code p} (prior) field, or indirectly via a reference to
 * that SAID buried in the exn's payload/embeds. A notification that fails either check is left exactly
 * as it was found (unread, undeleted) so a legitimate poller can still pick it up.
 *
 * <p>The exact field carrying the prior-exn link depends on which IPEX step produced the notification
 * (offer/agree/grant/admit all shape their {@code p}/{@code e} differently); both paths are checked and
 * unit-tested independently since this hasn't yet been confirmed against a live exchange (Task 8).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriNotificationCorrelator {

    /** After this many consecutive {@link SerializeException}s parsing the notification list,
     *  {@link #awaitCorrelated} gives up early instead of waiting out the full timeout — see its
     *  javadoc. */
    private static final int MAX_CONSECUTIVE_PARSE_FAILURES = 3;

    private final KeriAttestationClient client;
    private final KeriAttestationProperties properties;

    /** A notification that passed correlation: {@code notificationId} is the agent's own notification
     *  identifier (for {@link #markAndDelete}), {@code exnSaid} is the referenced exchange's SAID, and
     *  {@code exn} is that exchange's full decoded message (sender {@code i}, route {@code r}, prior
     *  {@code p}, payload {@code a}, embeds {@code e}, ...). */
    public record CorrelatedNotification(String notificationId, String exnSaid, Map<String, Object> exn) {
    }

    /**
     * Polls the agent's notification queue at {@link KeriAttestationProperties#notificationPollInterval()}
     * until a notification correlates to {@code requestExnSaid} or {@code timeout} elapses.
     *
     * <p>A notification is claimed only if it is unread, its route is one of {@code routes}, the exn it
     * references was sent by {@code expectedSenderAid}, and that exn threads back to
     * {@code requestExnSaid}. Every other notification — wrong route, wrong sender, unrelated thread, or
     * already read — is left completely untouched; this method never calls {@code mark}/{@code delete}
     * itself.
     *
     * <p>A transport failure listing notifications (network blip, 5xx, ...) is logged at {@code WARN}
     * and retried on the normal poll cadence, indistinguishable from an empty poll — the agent is still
     * reachable, so waiting out the full {@code timeout} is the right call. A response that <em>parses
     * incorrectly</em> is a different kind of failure: since the shape this class expects hasn't been
     * confirmed against a live agent (Task 8), a defect here would otherwise present as "every ceremony
     * times out" with the real cause buried in a WARN log identical to a flaky agent's. Those are
     * therefore logged at {@code ERROR} and counted; after {@value #MAX_CONSECUTIVE_PARSE_FAILURES}
     * consecutive parse failures this method gives up immediately instead of waiting out the rest of
     * {@code timeout}, so a caller fails in seconds rather than minutes. A single transport success (or
     * an empty/non-matching poll) between parse failures resets the count.
     *
     * @return the correlated notification, or {@link Optional#empty()} if none arrived before the
     *         timeout (or the wait was abandoned early after repeated parse failures). Never throws on
     *         timeout.
     */
    public Optional<CorrelatedNotification> awaitCorrelated(List<String> routes, String expectedSenderAid,
            String requestExnSaid, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        int consecutiveParseFailures = 0;
        while (true) {
            try {
                Optional<CorrelatedNotification> claimed;
                try {
                    claimed = pollOnce(routes, expectedSenderAid, requestExnSaid);
                    consecutiveParseFailures = 0;
                } catch (NotificationWireShapeException e) {
                    consecutiveParseFailures++;
                    if (consecutiveParseFailures >= MAX_CONSECUTIVE_PARSE_FAILURES) {
                        log.error("Giving up on notification wait after {} consecutive wire-shape parse "
                                        + "failures instead of waiting out the remaining timeout — this "
                                        + "looks like a defect, not agent flakiness.",
                                consecutiveParseFailures);
                        return Optional.empty();
                    }
                    claimed = Optional.empty();
                }
                if (claimed.isPresent()) {
                    return claimed;
                }
                if (!Instant.now().isBefore(deadline)) {
                    return Optional.empty();
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
     * Marks {@code notificationId} as read and removes it from the agent's queue.
     *
     * <p><strong>Callers must invoke this only after the corresponding ceremony state transition has
     * already been durably committed.</strong> Deleting the notification is what stops a retry (or a
     * concurrent poller) from re-claiming the same signal; doing that before the transition is
     * committed would let a crash between the two silently lose the wallet's response.
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

    // --- one polling round ---

    private Optional<CorrelatedNotification> pollOnce(List<String> routes, String expectedSenderAid,
            String requestExnSaid) throws InterruptedException {
        Notifying.Notifications.NotificationListResponse response;
        try {
            response = client.client().notifications().list();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            // Transport-level failure (network blip, 5xx, ...): the agent may simply be flaky right
            // now, so this is treated the same as an empty poll and retried on the normal cadence.
            log.warn("Failed to list KERI notifications, will retry: {}", e.getMessage());
            return Optional.empty();
        }

        List<Notification> notes;
        try {
            notes = Utils.fromJson(response.notes(), new TypeReference<List<Notification>>() {
            });
            if (notes == null) {
                notes = List.of();
            }
        } catch (SerializeException e) {
            // The agent answered, but the body didn't deserialize into the shape this class expects.
            // Distinct from a transport failure on purpose — see awaitCorrelated's javadoc for why this
            // is not treated as transient.
            log.error("Failed to parse the KERI notification list — this looks like a wire-shape "
                    + "mismatch, not a transient transport error: {}", e.getMessage());
            throw new NotificationWireShapeException(
                    "notifications().list() response did not match the expected shape", e);
        }

        for (Notification note : notes) {
            if (!isUnreadRouteMatch(note, routes)) {
                continue;
            }
            Optional<CorrelatedNotification> correlated = tryCorrelate(note, expectedSenderAid, requestExnSaid);
            if (correlated.isPresent()) {
                return correlated;
            }
        }
        return Optional.empty();
    }

    private static boolean isUnreadRouteMatch(Notification note, List<String> routes) {
        boolean unread = !note.r;
        boolean routeMatches = note.a != null && note.a.r != null && routes.contains(note.a.r);
        return unread && routeMatches;
    }

    // --- correlation: fetch the referenced exchange and verify sender + thread (design §4.3) ---

    private Optional<CorrelatedNotification> tryCorrelate(Notification note, String expectedSenderAid,
            String requestExnSaid) throws InterruptedException {
        String exnSaid = note.a != null ? note.a.d : null;
        if (exnSaid == null) {
            return Optional.empty();
        }

        Map<String, Object> exn;
        try {
            Optional<Object> exchange = client.client().exchanges().get(exnSaid);
            if (exchange.isEmpty()) {
                return Optional.empty();
            }
            exn = extractExn(exchange.get());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to fetch KERI exchange {} for correlation: {}", exnSaid, e.getMessage());
            return Optional.empty();
        }

        if (exn == null || !expectedSenderAid.equals(exn.get("i")) || !threadsBackToRequest(exn, requestExnSaid)) {
            return Optional.empty();
        }
        return Optional.of(new CorrelatedNotification(note.i, exnSaid, exn));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractExn(Object exchangeResult) {
        if (!(exchangeResult instanceof Map<?, ?> outer)) {
            return null;
        }
        Object exnObj = outer.get("exn");
        return exnObj instanceof Map<?, ?> ? (Map<String, Object>) exnObj : null;
    }

    /** Direct path: the exn's own {@code p} (prior) field names {@code requestExnSaid}. Fallback path:
     *  {@code requestExnSaid} is buried somewhere in the exn's payload ({@code a}) or embeds
     *  ({@code e}) — the shape of that reference varies by IPEX step and isn't pinned down until the
     *  Task 8 live spike, so this walks the whole subtree rather than a single fixed key. */
    private static boolean threadsBackToRequest(Map<String, Object> exn, String requestExnSaid) {
        if (requestExnSaid.equals(exn.get("p"))) {
            return true;
        }
        return referencesSaid(exn.get("a"), requestExnSaid) || referencesSaid(exn.get("e"), requestExnSaid);
    }

    private static boolean referencesSaid(Object node, String said) {
        if (node instanceof String s) {
            return s.equals(said);
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (referencesSaid(value, said)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (referencesSaid(value, said)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** Marks a {@link SerializeException} from parsing the notification list as a probable wire-shape
     *  defect rather than a transient transport error — see {@link #awaitCorrelated}'s javadoc. */
    private static final class NotificationWireShapeException extends RuntimeException {
        NotificationWireShapeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // --- raw notification shape, mirroring docs/keri/advanced/PublishExistingCredential.java ---

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
