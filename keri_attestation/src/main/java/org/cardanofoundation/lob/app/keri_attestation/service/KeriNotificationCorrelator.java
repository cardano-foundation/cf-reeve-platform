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
 * claimed if ALL of the following hold (F4 fix — a same-wallet ceremony's own notification is not
 * automatically safe, since SAIDs are public and one ceremony's response can otherwise be crafted to
 * satisfy another):
 * <ol>
 *   <li>the notification is unread and its own claimed route is one of {@code routes} (a cheap
 *       pre-filter before ever fetching anything);</li>
 *   <li>the FETCHED exchange's own route ({@code exn.r}) is <em>also</em> one of {@code routes} — the
 *       notification's claimed route is not trusted on its own, since nothing forces it to agree with
 *       the exchange it actually references;</li>
 *   <li>the exchange was sent by the expected sender ({@code exn.i});</li>
 *   <li>the exchange's addressee, when the shape carries one ({@code exn.a.i} or {@code exn.rp}), is
 *       this agent's own prefix — checked defensively (skipped, not rejected, when neither field is
 *       present) since the exact addressee field hasn't been confirmed against a live exchange
 *       (Task 8);</li>
 *   <li>the exchange threads back to the exact request {@code exn} SAID this caller is waiting on
 *       ({@link #threadsBackToRequest}) — a <em>bounded</em>, protocol-shaped check, not a general
 *       recursive search: {@code p} equality is primary; failing that, only {@code exn.a}'s direct
 *       values and each direct child map of {@code exn.e} (checking that child's own {@code p}/
 *       {@code d}) are examined. A SAID buried deeper than that (e.g. inside a nested embed two levels
 *       down) does not count — that shape is exactly how one ceremony's crafted response could
 *       otherwise be made to satisfy an unrelated ceremony waiting on the same wallet.</li>
 * </ol>
 * A notification that fails any check is left exactly as it was found (unread, undeleted) so a
 * legitimate poller can still pick it up.
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
    private final KeriAgentService agentService;

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
            Optional<CorrelatedNotification> correlated = tryCorrelate(routes, note, expectedSenderAid,
                    requestExnSaid);
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

    // --- correlation: fetch the referenced exchange and verify route + sender + recipient + thread
    //     (design §4.3, F4 fix — see this class's javadoc for the full list of checks) ---

    private Optional<CorrelatedNotification> tryCorrelate(List<String> routes, Notification note,
            String expectedSenderAid, String requestExnSaid) throws InterruptedException {
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

        if (exn == null
                // (F4 fix) the FETCHED exchange's own route, not just the notification's claimed one.
                || !routes.contains(exn.get("r"))
                || !expectedSenderAid.equals(exn.get("i"))
                || !addresseeMatchesAgent(exn)
                || !threadsBackToRequest(exn, requestExnSaid)) {
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

    /**
     * Recipient check (F4 fix): the exn's addressee, when the shape carries one, must be this agent's
     * own prefix — a notification for a request the agent never sent to this wallet, or a reply misrouted
     * to a different agent, must not be claimed. Checked defensively: the exact field carrying the
     * addressee (an {@code i} inside the payload map {@code a}, or a top-level {@code rp} "recipient
     * prefix") hasn't been confirmed against a live exchange (Task 8), and different exn shapes may carry
     * neither — a shape with no addressee field at all is not evidence of a wrong recipient, so this
     * skips (returns {@code true}) rather than rejects when neither is present.
     */
    private boolean addresseeMatchesAgent(Map<String, Object> exn) {
        String agentPrefix = agentService.agentPrefix();
        Object a = exn.get("a");
        if (a instanceof Map<?, ?> aMap && aMap.get("i") != null) {
            return agentPrefix.equals(aMap.get("i"));
        }
        Object rp = exn.get("rp");
        if (rp != null) {
            return agentPrefix.equals(rp);
        }
        return true;
    }

    /**
     * Bounded, protocol-shaped thread-back check (F4 fix — replaces an earlier unbounded recursive
     * search over the whole exn subtree, which let a SAID buried anywhere — including deep inside an
     * unrelated ceremony's own embeds — satisfy correlation). Primary path: the exn's own {@code p}
     * (prior) field names {@code requestExnSaid} directly. Otherwise, only these specific locations are
     * examined, matching the shapes IPEX/remotesign exchanges are actually known to use:
     * <ul>
     *   <li>{@code exn.a}'s direct values (not nested further);</li>
     *   <li>each direct child map of {@code exn.e}, checking only that child's own {@code p} and
     *       {@code d} values (not walking further into it).</li>
     * </ul>
     * No general recursion beyond that — a reference nested any deeper does not count.
     */
    private static boolean threadsBackToRequest(Map<String, Object> exn, String requestExnSaid) {
        if (requestExnSaid.equals(exn.get("p"))) {
            return true;
        }
        Object a = exn.get("a");
        if (a instanceof Map<?, ?> aMap) {
            for (Object value : aMap.values()) {
                if (requestExnSaid.equals(value)) {
                    return true;
                }
            }
        }
        Object e = exn.get("e");
        if (e instanceof Map<?, ?> eMap) {
            for (Object child : eMap.values()) {
                if (child instanceof Map<?, ?> childMap
                        && (requestExnSaid.equals(childMap.get("p")) || requestExnSaid.equals(childMap.get("d")))) {
                    return true;
                }
            }
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
