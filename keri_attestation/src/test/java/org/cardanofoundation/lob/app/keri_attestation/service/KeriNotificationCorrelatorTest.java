package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.clienting.SignifyClient;

@ExtendWith(MockitoExtension.class)
class KeriNotificationCorrelatorTest {

    private static final String ROUTE = "/exn/ipex/grant";
    private static final String OTHER_ROUTE = "/exn/ipex/offer";
    private static final String SENDER_AID = "EAIDSENDER00000000000000000000000000";
    private static final String OTHER_AID = "EAIDOTHER000000000000000000000000000";
    private static final String REQUEST_EXN_SAID = "EREQEXNSAID0000000000000000000000000";
    private static final String OTHER_SAID = "EOTHEREXNSAID000000000000000000000000";
    private static final String NOTIFICATION_ID = "0ANOTIFICATIONID000000000000000";
    private static final String REFERENCED_EXN_SAID = "EREFERENCEDEXNSAID00000000000000000";
    private static final String AGENT_PREFIX = "EAGENTPREFIX00000000000000000000000000";
    private static final String OTHER_AGENT_PREFIX = "EOTHERAGENTPREFIX0000000000000000000000";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Notifying.Notifications notifications;
    @Mock
    private Exchanging.Exchanges exchanges;
    @Mock
    private KeriAgentService agentService;

    private KeriNotificationCorrelator correlator;

    @BeforeEach
    void setUp() {
        // Not every test reaches both sub-APIs (a route/read mismatch never touches exchanges()), so
        // these are lenient the same way KeriOobiServiceTest's shared client stubs are.
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.notifications()).thenReturn(notifications);
        lenient().when(client.exchanges()).thenReturn(exchanges);
        lenient().when(agentService.agentPrefix()).thenReturn(AGENT_PREFIX);

        correlator = new KeriNotificationCorrelator(keriClient, properties(Duration.ofMillis(5)), agentService);
    }

    private static KeriAttestationProperties properties(Duration pollInterval) {
        return new KeriAttestationProperties(
                true, null, "identifier", null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), pollInterval,
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT15S"), Duration.parse("PT30M"), Duration.parse("PT2S"), Duration.parse("PT3S"),
                Duration.parse("PT2M"), null);
    }

    // --- notification / exchange fixture builders ---

    @SafeVarargs
    private static Notifying.Notifications.NotificationListResponse responseOf(Map<String, Object>... notes) {
        String json = writeJson(List.of(notes));
        return new Notifying.Notifications.NotificationListResponse(0, notes.length, notes.length, json);
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Captures {@link KeriNotificationCorrelator}'s own log events for the duration of {@code body}. */
    private static List<ILoggingEvent> captureLogs(Runnable body) {
        Logger logger = (Logger) LoggerFactory.getLogger(KeriNotificationCorrelator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
            return appender.list;
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Map<String, Object> note(String id, boolean read, String route, String exnSaid) {
        return Map.of("i", id, "dt", "2026-07-21T00:00:00.000000+00:00", "r", read,
                "a", Map.of("r", route, "d", exnSaid, "m", ""));
    }

    private static Optional<Object> exchangeWithPrior(String sender, String route, String prior) {
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", prior, "a", Map.of(), "e", Map.of())));
    }

    private static Optional<Object> exchangeWithEmbeddedReference(String sender, String route, String embeddedSaid) {
        // No "p" thread link, but the referenced SAID is buried inside an embedded message ("e").
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", "", "a", Map.of(),
                        "e", Map.of("grant", Map.of("d", embeddedSaid)))));
    }

    private static Optional<Object> exchangeWithPayloadReference(String sender, String route, String payloadSaid) {
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", "", "a", Map.of("ref", payloadSaid), "e", Map.of())));
    }

    private static Optional<Object> exchangeWithRoute(String sender, String route, String prior) {
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", prior, "a", Map.of(), "e", Map.of())));
    }

    private static Optional<Object> exchangeWithAddressee(String sender, String route, String prior,
            String addresseeAid) {
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", prior, "a", Map.of("i", addresseeAid), "e", Map.of())));
    }

    private static Optional<Object> exchangeWithRecipientPrefix(String sender, String route, String prior,
            String recipientPrefix) {
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", prior, "a", Map.of(), "e", Map.of(), "rp", recipientPrefix)));
    }

    /** Both {@code rp} and payload {@code a.i} set independently — for proving {@code rp} is
     *  authoritative and {@code a.i} never substitutes for it either way (item 2 round-2 fix). */
    private static Optional<Object> exchangeWithRecipientPrefixAndPayloadAddressee(String sender, String route,
            String prior, String recipientPrefix, String payloadAddressee) {
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", prior, "a", Map.of("i", payloadAddressee), "e", Map.of(),
                        "rp", recipientPrefix)));
    }

    /** {@code p} present and non-blank but different from the requested SAID, with the requested SAID
     *  ALSO present (coincidentally or by crafting) as an unrelated payload value — for proving a
     *  present-but-different {@code p} is authoritative and rejects outright, never falling through to
     *  consult {@code a} (item 2 round-2 fix). */
    private static Optional<Object> exchangeWithMismatchedPriorButMatchingPayloadValue(String sender, String route,
            String differentPrior, String saidBuriedInPayloadAnyway) {
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", differentPrior,
                        "a", Map.of("unrelated", saidBuriedInPayloadAnyway), "e", Map.of())));
    }

    /** SAID is buried two levels deep inside {@code e}'s child map ({@code grant.acdc.d}) — the F4 fix's
     *  bounded thread-back check must NOT walk this far; only a direct {@code e} child's own {@code p}/
     *  {@code d} count. This is the shape one ceremony's crafted response could otherwise use to satisfy
     *  an unrelated ceremony waiting on the same wallet. */
    private static Optional<Object> exchangeWithNestedUnrelatedEmbed(String sender, String route,
            String nestedSaid) {
        return Optional.of(Map.of("exn",
                Map.of("i", sender, "r", route, "p", "", "a", Map.of(),
                        "e", Map.of("grant", Map.of("acdc", Map.of("d", nestedSaid))))));
    }

    // --- claims: both correlation paths ---

    @Test
    void claimsNotificationWhenRouteMatchesSenderMatchesAndPriorLinksToRequest() throws Exception {
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID)).thenReturn(exchangeWithPrior(SENDER_AID, ROUTE, REQUEST_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofSeconds(2));

        assertTrue(result.isPresent());
        assertEquals(NOTIFICATION_ID, result.get().notificationId());
        assertEquals(REFERENCED_EXN_SAID, result.get().exnSaid());
        assertEquals(SENDER_AID, result.get().exn().get("i"));
        assertEquals(REQUEST_EXN_SAID, result.get().exn().get("p"));
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void claimsNotificationViaEmbeddedReferenceWhenPriorDoesNotMatch() throws Exception {
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithEmbeddedReference(SENDER_AID, ROUTE, REQUEST_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofSeconds(2));

        assertTrue(result.isPresent());
        assertEquals(NOTIFICATION_ID, result.get().notificationId());
        assertEquals(REFERENCED_EXN_SAID, result.get().exnSaid());
    }

    @Test
    void claimsNotificationViaPayloadReferenceWhenPriorDoesNotMatch() throws Exception {
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithPayloadReference(SENDER_AID, ROUTE, REQUEST_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofSeconds(2));

        assertTrue(result.isPresent());
    }

    // --- claims: recipient checks (F4 fix, tightened by item 2 round-2 fix) ---

    @Test
    void claimsNotificationEvenWhenPayloadAddresseeMismatchesTheAgentPrefixSinceRpIsAbsentAndAiIsNeverConsulted()
            throws Exception {
        // item 2(a) round-2 fix: a.i is ordinary payload data, never consulted for the recipient check
        // at all. With no rp field present, the check must skip (claim), regardless of what a.i says.
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithAddressee(SENDER_AID, ROUTE, REQUEST_EXN_SAID, OTHER_AGENT_PREFIX));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofSeconds(2));

        assertTrue(result.isPresent());
    }

    @Test
    void claimsNotificationWhenRecipientPrefixFieldMatchesTheAgentPrefix() throws Exception {
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithRecipientPrefix(SENDER_AID, ROUTE, REQUEST_EXN_SAID, AGENT_PREFIX));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofSeconds(2));

        assertTrue(result.isPresent());
    }

    @Test
    void claimsNotificationWhenRpMatchesEvenIfPayloadAddresseeMismatches() throws Exception {
        // item 2(a) round-2 fix: rp is authoritative when present; a mismatching a.i must not cause a
        // false rejection when rp itself is correct.
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID)).thenReturn(exchangeWithRecipientPrefixAndPayloadAddressee(
                SENDER_AID, ROUTE, REQUEST_EXN_SAID, AGENT_PREFIX, OTHER_AGENT_PREFIX));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofSeconds(2));

        assertTrue(result.isPresent());
    }

    // --- rejects: each guard, notification stays unread/unclaimed, times out ---

    @Test
    void ignoresNotificationWhenTheFetchedExchangesOwnRouteDoesNotMatchEvenIfTheNotificationClaimedAMatchingRoute()
            throws Exception {
        // F4 fix: the notification's own claimed route (used as the pre-filter above) is not trusted on
        // its own — the FETCHED exchange's own r field must also be one of the requested routes.
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithRoute(SENDER_AID, OTHER_ROUTE, REQUEST_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void ignoresNotificationWhenRecipientPrefixFieldDoesNotMatchTheAgentPrefix() throws Exception {
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithRecipientPrefix(SENDER_AID, ROUTE, REQUEST_EXN_SAID, OTHER_AGENT_PREFIX));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void ignoresNotificationWhenRpMismatchesEvenIfPayloadAddresseeHappensToMatch() throws Exception {
        // item 2(a) round-2 fix, the core vulnerability closed: a.i must never rescue a notification
        // whose signed rp names a different agent. Without this, a forged/misdirected notification could
        // set a.i to our prefix to bypass the recipient check even though rp says otherwise.
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID)).thenReturn(exchangeWithRecipientPrefixAndPayloadAddressee(
                SENDER_AID, ROUTE, REQUEST_EXN_SAID, OTHER_AGENT_PREFIX, AGENT_PREFIX));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void ignoresNotificationWhenPriorIsPresentButDifferentEvenIfPayloadHappensToContainTheRequestSaid()
            throws Exception {
        // item 2(b) round-2 fix: p, when present and non-blank, is authoritative -- a mismatching p must
        // reject outright, never falling through to consult a/e as a fallback (which could otherwise let
        // a coincidental or crafted payload value rescue an exn that already explicitly names a
        // different prior).
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID)).thenReturn(
                exchangeWithMismatchedPriorButMatchingPayloadValue(SENDER_AID, ROUTE, OTHER_SAID, REQUEST_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void ignoresACraftedResponseForAnUnrelatedCeremonyThatBuriesOurSaidTwoLevelsDeepInANestedEmbed() throws Exception {
        // The exact cross-ceremony hijack the F4 fix closes: the same wallet is party to two ceremonies
        // (ours, waiting on REQUEST_EXN_SAID; an unrelated one, "ceremony B") and a response actually
        // belonging to ceremony B happens to carry OUR request's SAID buried in an unrelated nested field
        // two levels down (e.g. inside an embedded ACDC's own "d"). SAIDs are public, so this is easy to
        // construct, deliberately or not. The bounded thread-back check (only exn.p, exn.a's direct
        // values, and each direct exn.e child's own p/d) must not walk deep enough to find it.
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithNestedUnrelatedEmbed(SENDER_AID, ROUTE, REQUEST_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void ignoresNonMatchingRouteAndTimesOutWithoutTouchingExchanges() throws Exception {
        when(notifications.list())
                .thenReturn(responseOf(note(NOTIFICATION_ID, false, OTHER_ROUTE, REFERENCED_EXN_SAID)));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verifyNoInteractions(exchanges);
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void ignoresAlreadyReadNotificationAndTimesOutWithoutTouchingExchanges() throws Exception {
        when(notifications.list())
                .thenReturn(responseOf(note(NOTIFICATION_ID, true, ROUTE, REFERENCED_EXN_SAID)));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verifyNoInteractions(exchanges);
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void ignoresNotificationFromWrongSenderAndTimesOut() throws Exception {
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID)).thenReturn(exchangeWithPrior(OTHER_AID, ROUTE, REQUEST_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void ignoresNotificationThatDoesNotThreadBackToTheRequestAndTimesOut() throws Exception {
        when(notifications.list()).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID)).thenReturn(exchangeWithPrior(SENDER_AID, ROUTE, OTHER_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void returnsEmptyOnTimeoutWhenNoNotificationsArrive() throws Exception {
        when(notifications.list()).thenReturn(responseOf());

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(30));

        assertTrue(result.isEmpty());
    }

    @Test
    void timesOutCleanlyInsteadOfPropagatingWhenTheClientRepeatedlyThrows() throws Exception {
        // A transient agent hiccup (network blip, 5xx, ...) must not blow up the caller's async
        // worker — it's logged and retried on the next poll, same as any other empty round, until the
        // deadline is reached.
        when(notifications.list()).thenThrow(new RuntimeException("agent unreachable"));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(40));

        assertTrue(result.isEmpty());
    }

    @Test
    void transportIOExceptionRetriesAtWarnUntilTheFullTimeoutRatherThanAbortingEarly() throws Exception {
        when(notifications.list()).thenThrow(new IOException("agent unreachable"));

        AtomicReference<Optional<KeriNotificationCorrelator.CorrelatedNotification>> resultRef = new AtomicReference<>();
        Instant start = Instant.now();
        List<ILoggingEvent> events = captureLogs(() -> resultRef.set(
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofMillis(60))));
        Duration elapsed = Duration.between(start, Instant.now());

        assertTrue(resultRef.get().isEmpty());
        // A transport failure must never trip the parse-failure early-abort path: with this test's 5ms
        // poll interval, that path would return in well under 20ms (3 strikes) if it were wrongly
        // triggered here. Waiting most of the way to the full 60ms timeout instead proves each failed
        // poll was treated as retryable, exactly as before this fix.
        assertTrue(elapsed.toMillis() >= 45, "expected to wait close to the full timeout, took " + elapsed);
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                "expected a WARN-level log for the transport failure");
        assertTrue(events.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
                "a transport failure must not log at ERROR — that's reserved for wire-shape parse failures");
    }

    @Test
    void parseFailureLogsAtErrorAndAbortsEarlyAfterThreeConsecutiveFailures() throws Exception {
        // Malformed JSON body: every poll fails to deserialize into List<Notification>, surfacing as
        // signify's SerializeException rather than a transport exception.
        when(notifications.list())
                .thenReturn(new Notifying.Notifications.NotificationListResponse(0, 0, 0, "not-valid-json"));

        AtomicReference<Optional<KeriNotificationCorrelator.CorrelatedNotification>> resultRef = new AtomicReference<>();
        Instant start = Instant.now();
        List<ILoggingEvent> events = captureLogs(() -> resultRef.set(
                correlator.awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofSeconds(5))));
        Duration elapsed = Duration.between(start, Instant.now());

        assertTrue(resultRef.get().isEmpty());
        // The 3-strike abort must fire well before the 5s deadline (poll interval is 5ms, so 3 rounds
        // complete in milliseconds) — this is the "fail in seconds, not minutes" behavior.
        assertTrue(elapsed.toMillis() < 1000, "expected an early abort well before the 5s deadline, took " + elapsed);

        long errorLogCount = events.stream().filter(e -> e.getLevel() == Level.ERROR).count();
        assertTrue(errorLogCount >= 3, "expected at least 3 ERROR-level parse-failure logs, saw " + errorLogCount);
        assertTrue(events.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                "a wire-shape parse failure must not log at WARN — that would hide it as transient");
    }

    // --- markAndDelete ---

    @Test
    void markAndDeleteCallsMarkThenDelete() throws Exception {
        correlator.markAndDelete(NOTIFICATION_ID);

        InOrder inOrder = Mockito.inOrder(notifications);
        inOrder.verify(notifications).mark(NOTIFICATION_ID);
        inOrder.verify(notifications).delete(NOTIFICATION_ID);
    }

    // --- interruption: restore the flag, never swallow it ---

    @Test
    void interruptedWhileWaitingReturnsEmptyPromptlyAndRestoresInterruptFlag() throws Exception {
        when(notifications.list()).thenReturn(responseOf());

        // A slower poll interval and a long timeout: if the interrupt were swallowed instead of
        // honored, the worker would keep looping all the way out to the 5s timeout below instead of
        // returning within the ~1s join bound this test asserts.
        KeriNotificationCorrelator slowCorrelator =
                new KeriNotificationCorrelator(keriClient, properties(Duration.ofMillis(200)), agentService);

        AtomicReference<Optional<KeriNotificationCorrelator.CorrelatedNotification>> resultRef =
                new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean(false);

        Thread worker = new Thread(() -> {
            Optional<KeriNotificationCorrelator.CorrelatedNotification> result = slowCorrelator
                    .awaitCorrelated(List.of(ROUTE), SENDER_AID, REQUEST_EXN_SAID, Duration.ofSeconds(5));
            resultRef.set(result);
            interruptFlagRestored.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(2000);

        assertFalse(worker.isAlive(), "worker must return promptly on interrupt rather than waiting out the timeout");
        assertTrue(resultRef.get().isEmpty());
        assertTrue(interruptFlagRestored.get(), "interrupt flag must be restored, not swallowed");
    }
}
