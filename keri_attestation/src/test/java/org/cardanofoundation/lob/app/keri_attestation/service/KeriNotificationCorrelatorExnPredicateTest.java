package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import id.veridian.signify.app.Exchanging;
import id.veridian.signify.app.Notifying;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.generated.keria.model.ExchangeResource;
import id.veridian.signify.generated.keria.model.Exn;
import id.veridian.signify.generated.keria.model.Notification;
import id.veridian.signify.generated.keria.model.NotificationData;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;

/**
 * The predicate overload of {@link KeriNotificationCorrelator#awaitByRoute}: a route match is claimed only
 * if the FETCHED exn answers our request ({@code p}/{@code i}/{@code rp}, mirroring cip113's
 * {@code matchesRemoteSignRef}) and is the exchange the notification points at ({@code exn.d == note.a.d}).
 * Runs the real correlator against a mocked {@code exchanges().get} returning a typed {@link Exn}.
 */
@ExtendWith(MockitoExtension.class)
class KeriNotificationCorrelatorExnPredicateTest {

    private static final List<String> REF_ROUTES = List.of("/remotesign/ixn/ref", "/exn/remotesign/ixn/ref");
    private static final String WALLET_AID = "EWALLETAID000000000000000000000000000000000";
    private static final String AGENT_AID = "EAGENTAID0000000000000000000000000000000000";
    private static final String OUR_REQUEST_SAID = "EOURREQUESTSAID0000000000000000000000000000";
    private static final String OTHER_REQUEST_SAID = "EOTHERREQUESTSAID00000000000000000000000000";
    private static final String OUR_REF_SAID = "EOURREFSAID00000000000000000000000000000000";
    private static final String OTHER_REF_SAID = "EOTHERREFSAID000000000000000000000000000000";

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

    private static Notification note(String id, String exnSaid) {
        return new Notification()
                .i(id)
                .dt("2026-07-21T00:00:00.000000+00:00")
                .r(false)
                .a(new NotificationData().r("/exn/remotesign/ixn/ref").d(exnSaid).m(""));
    }

    /** A remotesign ref as Veridian builds it: {@code createExchangeMessage(hab, RemoteSignRef, {sn}, [],
     *  recipient, undefined, requestSaid)} — so {@code p} is our request, {@code rp} our agent, {@code i}
     *  the wallet. */
    private static Optional<ExchangeResource> ref(String said, String requestSaid) {
        return Optional.of(new ExchangeResource().exn(new Exn()
                .d(said).i(WALLET_AID).rp(AGENT_AID).p(requestSaid).r("/remotesign/ixn/ref")
                .a(Map.of("sn", "3")).e(Map.of())));
    }

    private static Notifying.Notifications.NotificationListResponse responseOf(Notification... notes) {
        return new Notifying.Notifications.NotificationListResponse(0, notes.length, notes.length, List.of(notes));
    }

    /** The caller-side predicate KeriAttestService uses, for the request {@code requestSaid}. */
    private static Predicate<Map<String, Object>> answers(String requestSaid) {
        return exn -> "/remotesign/ixn/ref".equals(exn.get("r"))
                && requestSaid.equals(exn.get("p"))
                && WALLET_AID.equals(exn.get("i"))
                && AGENT_AID.equals(exn.get("rp"));
    }

    @Test
    void matchesRefForOurRequest() {
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note("note-ours", OUR_REF_SAID)));
        when(exchanges.get(OUR_REF_SAID)).thenReturn(ref(OUR_REF_SAID, OUR_REQUEST_SAID));

        Optional<CorrelatedNotification> claimed = correlator.awaitByRoute(REF_ROUTES, Duration.ofMillis(200),
                Set.of(), answers(OUR_REQUEST_SAID));

        assertTrue(claimed.isPresent());
        assertEquals("note-ours", claimed.get().notificationId());
        assertEquals(OUR_REF_SAID, claimed.get().exnSaid());
    }

    @Test
    void skipsRefForOtherRequestAndLeavesItUnread() {
        // A ref answering some OTHER request sits first in the queue; ours arrives behind it.
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(
                note("note-other", OTHER_REF_SAID), note("note-ours", OUR_REF_SAID)));
        when(exchanges.get(OTHER_REF_SAID)).thenReturn(ref(OTHER_REF_SAID, OTHER_REQUEST_SAID));
        when(exchanges.get(OUR_REF_SAID)).thenReturn(ref(OUR_REF_SAID, OUR_REQUEST_SAID));

        Optional<CorrelatedNotification> claimed = correlator.awaitByRoute(REF_ROUTES, Duration.ofMillis(200),
                Set.of(), answers(OUR_REQUEST_SAID));

        assertTrue(claimed.isPresent());
        assertEquals("note-ours", claimed.get().notificationId());
        // Skipping is non-destructive: the other request's ref may still be someone's reply.
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void refForOtherRequestAloneTimesOutWithoutBeingTouched() {
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note("note-other", OTHER_REF_SAID)));
        when(exchanges.get(OTHER_REF_SAID)).thenReturn(ref(OTHER_REF_SAID, OTHER_REQUEST_SAID));

        Optional<CorrelatedNotification> claimed = correlator.awaitByRoute(REF_ROUTES, Duration.ofMillis(50),
                Set.of(), answers(OUR_REQUEST_SAID));

        assertTrue(claimed.isEmpty());
        verify(notifications, never()).mark(anyString());
        verify(notifications, never()).delete(anyString());
    }

    @Test
    void skipsExnWhoseOwnSaidIsNotTheNotifiedOne() {
        // The notification points at OUR_REF_SAID but the fetched exn claims a different d: it is not the
        // exchange the notification announced, so it is not claimed even though p/i/rp match.
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note("note-ours", OUR_REF_SAID)));
        when(exchanges.get(OUR_REF_SAID)).thenReturn(ref(OTHER_REF_SAID, OUR_REQUEST_SAID));

        Optional<CorrelatedNotification> claimed = correlator.awaitByRoute(REF_ROUTES, Duration.ofMillis(50),
                Set.of(), answers(OUR_REQUEST_SAID));

        assertTrue(claimed.isEmpty());
    }

    @Test
    void conversionKeepsPIRp() {
        when(notifications.list(anyInt(), anyInt())).thenReturn(responseOf(note("note-ours", OUR_REF_SAID)));
        when(exchanges.get(OUR_REF_SAID)).thenReturn(ref(OUR_REF_SAID, OUR_REQUEST_SAID));
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();

        correlator.awaitByRoute(REF_ROUTES, Duration.ofMillis(200), Set.of(), exn -> {
            seen.set(exn);
            return true;
        });

        assertEquals(OUR_REQUEST_SAID, seen.get().get("p"));
        assertEquals(WALLET_AID, seen.get().get("i"));
        assertEquals(AGENT_AID, seen.get().get("rp"));
        assertEquals("/remotesign/ixn/ref", seen.get().get("r"));
        assertEquals(OUR_REF_SAID, seen.get().get("d"));
    }
}
