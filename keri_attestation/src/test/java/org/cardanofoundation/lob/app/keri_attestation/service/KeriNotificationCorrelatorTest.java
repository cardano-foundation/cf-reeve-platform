package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.generated.keria.model.ExchangeResource;
import org.cardanofoundation.signify.generated.keria.model.Exn;
import org.cardanofoundation.signify.generated.keria.model.Notification;
import org.cardanofoundation.signify.generated.keria.model.NotificationData;

@ExtendWith(MockitoExtension.class)
class KeriNotificationCorrelatorTest {

    private static final String ROUTE = "/exn/ipex/grant";
    private static final String OTHER_ROUTE = "/exn/ipex/offer";
    private static final String SENDER_AID = "EAIDSENDER00000000000000000000000000";
    private static final String OTHER_AID = "EAIDOTHER000000000000000000000000000";
    private static final String NOTIFICATION_ID = "0ANOTIFICATIONID000000000000000";
    private static final String REFERENCED_EXN_SAID = "EREFERENCEDEXNSAID00000000000000000";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Notifying.Notifications notifications;
    @Mock
    private Exchanging.Exchanges exchanges;

    private KeriNotificationCorrelator correlator;

    @BeforeEach
    void setUp() {
        // Not every test reaches both sub-APIs (a route/read mismatch never touches exchanges()), so
        // these are lenient the same way KeriOobiServiceTest's shared client stubs are.
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.notifications()).thenReturn(notifications);
        lenient().when(client.exchanges()).thenReturn(exchanges);

        correlator = new KeriNotificationCorrelator(keriClient, properties(Duration.ofMillis(5)));
    }

    private static KeriAttestationProperties properties(Duration pollInterval) {
        return new KeriAttestationProperties(
                true, null, "identifier", null, null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), pollInterval,
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT15S"), Duration.parse("PT30M"), Duration.parse("PT2S"), Duration.parse("PT3S"),
                Duration.parse("PT2M"), null);
    }

    // --- notification / exchange fixture builders ---

    /** The client parses the notification list itself now, so fixtures build the typed records
     *  directly rather than round-tripping JSON through a local mirror of the shape. */
    private static Notifying.Notifications.NotificationListResponse responseOf(Notification... notes) {
        return new Notifying.Notifications.NotificationListResponse(0, notes.length, notes.length, List.of(notes));
    }

    /** One page of a larger queue: {@code total} is the whole queue, not this page. */
    private static Notifying.Notifications.NotificationListResponse page(int start, int total,
            List<Notification> notes) {
        return new Notifying.Notifications.NotificationListResponse(start, start + notes.size(), total, notes);
    }

    private static List<Notification> filler(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> note("stale-" + i, false, "/exn/ipex/other", "ESTALE" + i))
                .toList();
    }

    private static Notification noteAt(String id, String dt, String route) {
        return note(id, false, route, "E" + id).dt(dt);
    }

    private static String isoMinutesAgo(long minutes) {
        return java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(minutes)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx"));
    }

    private static Notification note(String id, boolean read, String route, String exnSaid) {
        return new Notification()
                .i(id)
                .dt("2026-07-21T00:00:00.000000+00:00")
                .r(read)
                .a(new NotificationData().r(route).d(exnSaid).m(""));
    }

    private static Optional<ExchangeResource> exchangeWithRoute(String sender, String route, String prior) {
        return Optional.of(new ExchangeResource().exn(
                new Exn().i(sender).r(route).p(prior).a(Map.of()).e(Map.of())));
    }

    private static Optional<ExchangeResource> exchangeWithRouteAndSaid(String sender, String route, String said) {
        return Optional.of(new ExchangeResource().exn(
                new Exn().i(sender).r(route).d(said).a(Map.of()).e(Map.of())));
    }

    // --- awaitByRoute: claims the first unread route-matching notification, no other checks ---

    @Test
    void awaitByRouteClaimsTheFirstUnreadRouteMatchingNotificationRegardlessOfSenderOrThread() throws Exception {
        // OTHER_AID (not some "expected" sender) and no thread-back at all -- awaitByRoute claims it
        // anyway, since it never looks at sender or thread-back, only route + unread.
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithRouteAndSaid(OTHER_AID, ROUTE, REFERENCED_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofSeconds(2));

        assertTrue(result.isPresent());
        assertEquals(NOTIFICATION_ID, result.get().notificationId());
        assertEquals(OTHER_AID, result.get().exn().get("i"));
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void awaitByRouteClaimsTheBareRouteFormWhenBothFormsAreAccepted() throws Exception {
        // KERIA can surface an IPEX offer/grant notification's route in the bare form ("/ipex/offer")
        // rather than the "/exn/"-prefixed one; both forms are accepted. When the caller passes both
        // forms (as OFFER_ROUTES/GRANT_ROUTES do), a bare-route notification must still be claimed --
        // otherwise the credential step hangs after the wallet has already responded.
        String bareRoute = "/ipex/offer";
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note(NOTIFICATION_ID, false, bareRoute, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithRouteAndSaid(SENDER_AID, bareRoute, REFERENCED_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of("/exn/ipex/offer", bareRoute), Duration.ofSeconds(2));

        assertTrue(result.isPresent());
        assertEquals(NOTIFICATION_ID, result.get().notificationId());
    }

    @Test
    void awaitByRoutePrefersTheFetchedExchangesOwnDOverTheNotificationsClaimedSaid() throws Exception {
        // The fetched exchange's own d (offerResource/grantResource's own getExn().getD(), read after
        // the fetch) is preferred over the notification body's own a.d.
        String fetchedOwnSaid = "EFETCHEDOWNSAID0000000000000000000000";
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithRouteAndSaid(SENDER_AID, ROUTE, fetchedOwnSaid));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofSeconds(2));

        assertTrue(result.isPresent());
        assertEquals(fetchedOwnSaid, result.get().exnSaid());
    }

    @Test
    void awaitByRouteFallsBackToTheNotificationsClaimedSaidWhenTheFetchedExchangeHasNoOwnD() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID)).thenReturn(exchangeWithRoute(SENDER_AID, ROUTE, ""));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofSeconds(2));

        assertTrue(result.isPresent());
        assertEquals(REFERENCED_EXN_SAID, result.get().exnSaid());
    }

    @Test
    void awaitByRouteIgnoresNonMatchingRouteAndTimesOutWithoutTouchingExchanges() throws Exception {
        when(notifications.list(anyInt(), anyInt()))
                .thenReturn(responseOf(note(NOTIFICATION_ID, false, OTHER_ROUTE, REFERENCED_EXN_SAID)));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verifyNoInteractions(exchanges);
    }

    @Test
    void awaitByRouteIgnoresAlreadyReadNotificationAndTimesOutWithoutTouchingExchanges() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note(NOTIFICATION_ID, true, ROUTE, REFERENCED_EXN_SAID)));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofMillis(40));

        assertTrue(result.isEmpty());
        verifyNoInteractions(exchanges);
    }

    @Test
    void awaitByRouteReturnsEmptyOnTimeoutWhenNoNotificationsArrive() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf());

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofMillis(30));

        assertTrue(result.isEmpty());
    }

    @Test
    void awaitByRouteTimesOutCleanlyInsteadOfPropagatingWhenTheClientRepeatedlyThrows() throws Exception {
        // A transient agent hiccup (network blip, 5xx, ...) must not blow up the caller's async
        // worker — it's logged and retried on the next poll, same as any other empty round, until the
        // deadline is reached.
        when(notifications.list(anyInt(), anyInt())).thenThrow(new RuntimeException("agent unreachable"));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofMillis(40));

        assertTrue(result.isEmpty());
    }

    @Test
    void awaitByRouteNeverMarksOrDeletesRegardlessOfOutcome() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithRouteAndSaid(SENDER_AID, ROUTE, REFERENCED_EXN_SAID));

        correlator.awaitByRoute(List.of(ROUTE), Duration.ofSeconds(2));

        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    // --- excludeNoteIds: ignore pre-existing debris so a stale reply isn't mistaken for a new one ---

    @Test
    void awaitByRouteSkipsANotificationWhoseIdIsExcludedAndTimesOut() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID)));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofMillis(40), Set.of(NOTIFICATION_ID));

        assertTrue(result.isEmpty());
        verifyNoInteractions(exchanges);
    }

    @Test
    void awaitByRouteClaimsAFreshNotificationEvenWhenAnExcludedStaleOneIsAlsoPresent() throws Exception {
        String freshId = "0AFRESHNOTE00000000000000000000";
        String freshExnSaid = "EFRESHEXNSAID000000000000000000000";
        // The stale note (NOTIFICATION_ID) is excluded; only the fresh one is claimed — the stale note's
        // exchange is never even fetched.
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(
                note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID),
                note(freshId, false, ROUTE, freshExnSaid)));
        when(exchanges.get(freshExnSaid)).thenReturn(exchangeWithRouteAndSaid(SENDER_AID, ROUTE, freshExnSaid));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofSeconds(2), Set.of(NOTIFICATION_ID));

        assertTrue(result.isPresent());
        assertEquals(freshId, result.get().notificationId());
    }

    @Test
    void outstandingNoteIdsReturnsOnlyUnreadRouteMatchingIds() throws Exception {
        String readId = "0AREADNOTE0000000000000000000000";
        String otherRouteId = "0AOTHERROUTE000000000000000000000";
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(
                note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID),      // unread + route match -> in
                note(readId, true, ROUTE, REFERENCED_EXN_SAID),               // already read -> out
                note(otherRouteId, false, OTHER_ROUTE, REFERENCED_EXN_SAID))); // different route -> out

        Set<String> ids = correlator.outstandingNoteIds(List.of(ROUTE));

        assertEquals(Set.of(NOTIFICATION_ID), ids);
    }

    @Test
    void outstandingNoteIdsReturnsEmptyWhenTheListingFails() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenThrow(new RuntimeException("agent unreachable"));

        assertTrue(correlator.outstandingNoteIds(List.of(ROUTE)).isEmpty());
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
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf());

        // A slower poll interval and a long timeout: if the interrupt were swallowed instead of
        // honored, the worker would keep looping all the way out to the 5s timeout below instead of
        // returning within the ~1s join bound this test asserts.
        KeriNotificationCorrelator slowCorrelator =
                new KeriNotificationCorrelator(keriClient, properties(Duration.ofMillis(200)));

        AtomicReference<Optional<KeriNotificationCorrelator.CorrelatedNotification>> resultRef =
                new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean(false);

        Thread worker = new Thread(() -> {
            Optional<KeriNotificationCorrelator.CorrelatedNotification> result =
                    slowCorrelator.awaitByRoute(List.of(ROUTE), Duration.ofSeconds(5));
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

    /**
     * The live defect this guards against: signify's no-arg {@code notifications().list()} requests
     * {@code Range: notes=0-24}, so only the OLDEST 25 notifications are ever seen. Notifications are
     * deleted only on a successful step, so failed attempts accumulate unread — and once 25 pile up on
     * an agent, the reply to the CURRENT request sits past the window and is invisible forever. The
     * flow works for a while, then times out permanently while the wallet reports success. Notifications
     * belong to the KERIA account rather than the identifier, so minting a new agent AID does not
     * clear them.
     */
    @Test
    void claimsAReplyThatSitsBeyondTheFirstPageOfAnAgentCloggedWithStaleNotifications() throws Exception {
        List<Notification> firstPage = filler(25);
        List<Notification> secondPage = new java.util.ArrayList<>(filler(3));
        secondPage.add(note(NOTIFICATION_ID, false, ROUTE, REFERENCED_EXN_SAID));

        when(notifications.list(0, 24)).thenReturn(page(0, 29, firstPage));
        when(notifications.list(25, 49)).thenReturn(page(25, 29, secondPage));
        when(exchanges.get(REFERENCED_EXN_SAID))
                .thenReturn(exchangeWithRouteAndSaid(SENDER_AID, ROUTE, REFERENCED_EXN_SAID));

        Optional<KeriNotificationCorrelator.CorrelatedNotification> claimed =
                correlator.awaitByRoute(List.of(ROUTE), Duration.ofSeconds(1));

        assertTrue(claimed.isPresent(), "a reply past index 24 must still be claimed");
        assertEquals(NOTIFICATION_ID, claimed.get().notificationId());
        // Both pages were actually fetched — proving the scan does not stop at the first page.
        verify(notifications).list(0, 24);
        verify(notifications).list(25, 49);
    }


    // ==================== queue pruning ====================

    /**
     * Notifications are otherwise deleted only on a successful step, so failed attempts accumulate and
     * every poll pages a longer queue. Anything older than the retention window cannot belong to a live
     * ceremony (nothing outlives ceremony-ttl), so it is provably debris.
     */
    @Test
    void pruneDeletesOnlyNotificationsOlderThanTheRetentionWindow() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(page(0, 3, List.of(
                noteAt("old-1", isoMinutesAgo(180), ROUTE),
                noteAt("recent", isoMinutesAgo(5), ROUTE),
                noteAt("old-2", isoMinutesAgo(240), "/exn/ipex/other"))));

        int pruned = correlator.pruneOlderThan(Duration.ofMinutes(60));

        assertEquals(2, pruned);
        verify(notifications).delete("old-1");
        verify(notifications).delete("old-2");
        verify(notifications, never()).delete("recent");
    }

    /**
     * The safety property that matters. An earlier purge deleted every unread notification on a route
     * right before sending an apply, reasoning that the apply is what prompts the wallet — but Veridian
     * presents spontaneously, so a real grant can already be queued. That ate real grants and was
     * reverted. Age must be the ONLY criterion: a fresh notification survives regardless of route,
     * read state, or how much older debris surrounds it.
     */
    @Test
    void pruneNeverDeletesAFreshNotificationEvenAmongOlderDebris() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(page(0, 3, List.of(
                noteAt("ancient", isoMinutesAgo(600), ROUTE),
                noteAt("the-real-grant", isoMinutesAgo(1), ROUTE),
                noteAt("also-ancient", isoMinutesAgo(500), ROUTE))));

        int pruned = correlator.pruneOlderThan(Duration.ofMinutes(60));

        assertEquals(2, pruned);
        verify(notifications, never()).delete("the-real-grant");
        verify(notifications, never()).mark("the-real-grant");
    }

    /** Undatable means unprovable, so it is never deleted. */
    @Test
    void pruneSkipsNotificationsWhoseTimestampCannotBeParsed() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(page(0, 2, List.of(
                noteAt("no-date", "not-a-timestamp", ROUTE),
                noteAt("old", isoMinutesAgo(300), ROUTE))));

        int pruned = correlator.pruneOlderThan(Duration.ofMinutes(60));

        assertEquals(1, pruned);
        verify(notifications, never()).delete("no-date");
        verify(notifications).delete("old");
    }

    /** One stubborn notification must not abort the sweep. */
    @Test
    void pruneContinuesAfterAFailedDelete() throws Exception {
        when(notifications.list(anyInt(), anyInt())).thenReturn(page(0, 2, List.of(
                noteAt("wont-go", isoMinutesAgo(300), ROUTE),
                noteAt("will-go", isoMinutesAgo(300), ROUTE))));
        doThrow(new RuntimeException("agent refused")).when(notifications).delete("wont-go");

        int pruned = correlator.pruneOlderThan(Duration.ofMinutes(60));

        assertEquals(1, pruned);
        verify(notifications).delete("will-go");
    }

}
