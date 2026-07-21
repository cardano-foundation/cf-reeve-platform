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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private SignifyClient client;
    @Mock
    private Notifying.Notifications notifications;
    @Mock
    private Exchanging.Exchanges exchanges;

    private KeriNotificationCorrelator correlator;

    @BeforeEach
    void setUp() {
        // Not every test reaches both sub-APIs (a route/read mismatch never touches exchanges()), so
        // these are lenient the same way KeriOobiServiceTest's shared client stubs are.
        lenient().when(client.notifications()).thenReturn(notifications);
        lenient().when(client.exchanges()).thenReturn(exchanges);

        correlator = new KeriNotificationCorrelator(client, properties(Duration.ofMillis(5)));
    }

    private static KeriAttestationProperties properties(Duration pollInterval) {
        return new KeriAttestationProperties(
                true, null, "identifier", null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), pollInterval,
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")));
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

    // --- rejects: each guard, notification stays unread/unclaimed, times out ---

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
                new KeriNotificationCorrelator(client, properties(Duration.ofMillis(200)));

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
