package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ProblemDetail;

import id.veridian.signify.app.Contacting;
import id.veridian.signify.app.Exchanging;
import id.veridian.signify.app.Exchanging.ExchangeMessageResult;
import id.veridian.signify.app.Notifying;
import id.veridian.signify.app.aiding.IdentifierController;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.app.coring.Oobis;
import id.veridian.signify.app.coring.Operations;
import id.veridian.signify.app.credentialing.ipex.Ipex;
import id.veridian.signify.cesr.Serder;
import id.veridian.signify.generated.keria.model.ExchangeResource;
import id.veridian.signify.generated.keria.model.Exn;
import id.veridian.signify.generated.keria.model.HabState;
import id.veridian.signify.generated.keria.model.Notification;
import id.veridian.signify.generated.keria.model.NotificationData;
import id.veridian.signify.generated.keria.model.Operation;
import id.veridian.signify.generated.keria.model.PendingExchangeOperation;
import id.veridian.signify.generated.keria.model.PendingOOBIOperation;
import io.vavr.control.Either;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchemaRegistry;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;

/**
 * A presented credential whose schema ({@code e.acdc.s} on the grant) is not one this deployment
 * accepts is never admitted, on either the spontaneous-grant or the negotiated path. Any CONFIGURED
 * schema is accepted, not only the one the apply asked for, because Veridian presents spontaneously.
 * The rejected grant is marked and deleted once the failure is committed, so it cannot be re-claimed
 * by the next presentation off the agent's shared notification queue.
 */
@ExtendWith(MockitoExtension.class)
class KeriCredentialServiceSchemaGuardTest {

    private static final String CEREMONY_ID = "cer-1";
    private static final String USER_ID = "user-1";
    private static final int GENERATION = 2;
    private static final String LINKED_AID = "ELINKEDAID000000000000000000000000000";
    private static final String AGENT_NAME = "keriAttestationAgent";
    private static final String SCHEMA_SAID = "ESCHEMA00000000000000000000000000000000";
    private static final String SECOND_SCHEMA_SAID = "ESCHEMATWO0000000000000000000000000000";
    private static final String FOREIGN_SCHEMA_SAID = "EFOREIGNSCHEMA000000000000000000000000";
    private static final String ROOT_AID = "EROOT00000000000000000000000000000000A";
    private static final String APPLY_SAID = "EAPPLYSAID00000000000000000000000000000";
    private static final String AGREE_SAID = "EAGREESAID00000000000000000000000000000";
    private static final String OFFER_SAID = "EOFFERSAID00000000000000000000000000000";
    private static final String GRANT_SAID = "EGRANTSAID00000000000000000000000000000";
    private static final String CREDENTIAL_SAID = "ECREDSAID000000000000000000000000000000";
    private static final String OFFER_NOTIF_ID = "0AOFFERNOTIFID0000000000000000000";
    private static final String GRANT_NOTIF_ID = "0AGRANTNOTIFID0000000000000000000";
    private static final List<String> GRANT_ROUTES = List.of("/exn/ipex/grant", "/ipex/grant");
    private static final List<String> OFFER_OR_GRANT_ROUTES =
            List.of("/exn/ipex/offer", "/ipex/offer", "/exn/ipex/grant", "/ipex/grant");

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Ipex ipex;
    @Mock
    private Exchanging.Exchanges exchanges;
    @Mock
    private IdentifierController identifiers;
    @Mock
    private CredentialCesrFetcher cesrFetcher;
    @Mock
    private Oobis oobis;
    @Mock
    private Operations operations;
    @Mock
    private Contacting.Contacts contacts;
    @Mock
    private Notifying.Notifications notifications;
    @Mock
    private KeriAgentService agentService;
    @Mock
    private KeriNotificationCorrelator mockCorrelator;
    @Mock
    private CredentialChainValidator validator;
    @Mock
    private CeremonyService ceremonyService;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private KeriOobiService oobiService;
    @Mock
    private SchemaOobiResolver schemaOobiResolver;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.ipex()).thenReturn(ipex);
        lenient().when(client.exchanges()).thenReturn(exchanges);
        lenient().when(client.identifiers()).thenReturn(identifiers);
        lenient().when(client.oobis()).thenReturn(oobis);
        lenient().when(client.operations()).thenReturn(operations);
        lenient().when(client.contacts()).thenReturn(contacts);
        lenient().when(client.notifications()).thenReturn(notifications);
        lenient().when(agentService.agentName()).thenReturn(AGENT_NAME);
        lenient().when(agentService.agentPrefix()).thenReturn("EAGENTPREFIX000000000000000000000000000");
        lenient().when(agentService.agentOobi()).thenReturn("http://keria.example/oobi/EAGENTPREFIX/agent");
        lenient().when(contacts.get(any())).thenReturn(Optional.empty());
        lenient().when(notifications.list())
                .thenReturn(new Notifying.Notifications.NotificationListResponse(0, 0, 0, List.of()));
        lenient().when(oobis.resolve(any(), any())).thenReturn(new PendingOOBIOperation());
        lenient().when(operations.wait(any(Operation.class), any(Operations.WaitOptions.class))).thenReturn(null);
        lenient().when(operations.wait(any(Operation.class))).thenReturn(null);
        lenient().when(ipex.submitApply(any(), any(), any(), any())).thenReturn(new PendingExchangeOperation());
        lenient().when(ipex.submitAgree(any(), any(), any(), any())).thenReturn(new PendingExchangeOperation());
        lenient().when(ipex.submitAdmit(any(), any(), any(), any(), any())).thenReturn(new PendingExchangeOperation());
        lenient().when(identifiers.get(AGENT_NAME)).thenReturn(Optional.of(habState()));
        lenient().when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), any())).thenReturn(true);
        lenient().when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link()));
        // Built before stubbing: creating a mock inside thenReturn(...) would nest stubbings.
        Serder applyExn = serderWithSaid(APPLY_SAID);
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        lenient().when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID),
                any(), any())).thenReturn(new ExchangeMessageResult(applyExn, List.of("sig1"), "atc1"));
        lenient().when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
    }

    private KeriCredentialService service(KeriNotificationCorrelator correlator) {
        return new KeriCredentialService(keriClient, agentService, correlator, validator, ceremonyService,
                identityLinkRepository, properties(), registry(), schemaOobiResolver, oobiService, cesrFetcher);
    }

    /** Two accepted schemas: the apply asks for the first; a presentation of either is acceptable. */
    private static CredentialSchemaRegistry registry() {
        return new CredentialSchemaRegistry(List.of(
                new CredentialSchema(SCHEMA_SAID, "Foundation Employee", TrustModel.STANDALONE, List.of(),
                        List.of(ROOT_AID), List.of()),
                new CredentialSchema(SECOND_SCHEMA_SAID, "Foundation Contractor", TrustModel.STANDALONE, List.of(),
                        List.of(ROOT_AID), List.of())));
    }

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier",
                new KeriAttestationProperties.CredentialPolicy(List.of(SCHEMA_SAID), List.of(ROOT_AID),
                        "https://schema.example.org/oobi"),
                null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT0.05S"), Duration.parse("PT0.01S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT0.01S"), Duration.parse("PT0.05S"), Duration.parse("PT0.01S"),
                Duration.parse("PT0.01S"), Duration.parse("PT2M"), null);
    }

    private static KeriAttestationCeremonyEntity ceremony() {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(CEREMONY_ID);
        ceremony.setUserId(USER_ID);
        ceremony.setState(CeremonyState.CREDENTIAL_REQUESTED);
        ceremony.setAttemptGeneration(GENERATION);
        return ceremony;
    }

    private static KeriIdentityLinkEntity link() {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid(LINKED_AID);
        return link;
    }

    private static HabState habState() {
        HabState hab = new HabState();
        hab.setName(AGENT_NAME);
        return hab;
    }

    private static Serder serderWithSaid(String said) {
        Serder serder = mock(Serder.class, RETURNS_DEFAULTS);
        lenient().when(serder.getKed()).thenReturn(Map.of("d", said));
        return serder;
    }

    private static Map<String, Object> grantExn(String schemaSaid) {
        return Map.of("d", GRANT_SAID, "i", LINKED_AID, "r", "/exn/ipex/grant",
                "e", Map.of("acdc", Map.of("d", CREDENTIAL_SAID, "s", schemaSaid)));
    }

    private void beginFreshStep() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony()));
    }

    @Test
    void rejectsGrantWithForeignSchemaBeforeAdmit() throws Exception {
        beginFreshStep();
        when(mockCorrelator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(FOREIGN_SCHEMA_SAID), "/exn/ipex/grant")));

        Either<ProblemDetail, CeremonyView> result = service(mockCorrelator)
                .presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains(FOREIGN_SCHEMA_SAID), result.getLeft().getDetail());
        verify(ipex, never()).admit(any());
        verify(ipex, never()).submitAdmit(any(), any(), any(), any(), any());
        // The failure is committed first; only then is the rejected grant cleared off the queue.
        InOrder inOrder = inOrder(ceremonyService, mockCorrelator);
        inOrder.verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), eq(KeriAttestationProblems.CREDENTIAL_REJECTED), any());
        inOrder.verify(mockCorrelator).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void rejectsNegotiatedGrantWithForeignSchemaBeforeAdmit() throws Exception {
        beginFreshStep();
        when(mockCorrelator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        when(mockCorrelator.awaitByRoute(eq(GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(FOREIGN_SCHEMA_SAID))));

        Either<ProblemDetail, CeremonyView> result = service(mockCorrelator)
                .presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        verify(ipex).submitAgree(any(), any(), any(), any());
        verify(ipex, never()).admit(any());
        InOrder inOrder = inOrder(ceremonyService, mockCorrelator);
        inOrder.verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), eq(KeriAttestationProblems.CREDENTIAL_REJECTED), any());
        inOrder.verify(mockCorrelator).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void rejectsGrantWhoseAcdcNamesNoSchema() throws Exception {
        beginFreshStep();
        when(mockCorrelator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        Map.of("i", LINKED_AID, "r", "/exn/ipex/grant",
                                "e", Map.of("acdc", Map.of("d", CREDENTIAL_SAID))), "/exn/ipex/grant")));

        Either<ProblemDetail, CeremonyView> result = service(mockCorrelator)
                .presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        verify(ipex, never()).admit(any());
        verify(mockCorrelator).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void admitsAnyConfiguredSchemaNotOnlyTheRequestedOne() throws Exception {
        // The apply asks for SCHEMA_SAID, but Veridian presents spontaneously: a credential of the
        // other configured schema is still admitted.
        beginFreshStep();
        when(mockCorrelator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(SECOND_SCHEMA_SAID), "/exn/ipex/grant")));
        Serder admitExn = serderWithSaid("EADMIT");
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));

        service(mockCorrelator).presentCredential(CEREMONY_ID, USER_ID, false);

        verify(ipex).admit(any());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(),
                eq(KeriAttestationProblems.CREDENTIAL_REJECTED), any());
    }

    @Test
    void rejectedGrantIsDeletedSoNextPresentationDoesNotReclaimIt() throws Exception {
        // The REAL correlator over a simulated agent queue: mark/delete really removes the notification,
        // exactly as KERIA does, so a second presentation sees what the first one left behind.
        List<Notification> queue = new ArrayList<>();
        queue.add(new Notification().i(GRANT_NOTIF_ID).dt("2026-07-21T00:00:00.000000+00:00").r(false)
                .a(new NotificationData().r("/exn/ipex/grant").d(GRANT_SAID).m("")));
        when(notifications.list(anyInt(), anyInt())).thenAnswer(inv -> new Notifying.Notifications
                .NotificationListResponse(0, queue.size(), queue.size(), List.copyOf(queue)));
        doAnswer(inv -> queue.removeIf(n -> n.getI().equals(inv.getArgument(0))))
                .when(notifications).delete(anyString());
        when(exchanges.get(GRANT_SAID)).thenReturn(Optional.of(new ExchangeResource().exn(new Exn()
                .d(GRANT_SAID).i(LINKED_AID).r("/ipex/grant").a(Map.of())
                .e(Map.of("acdc", Map.of("d", CREDENTIAL_SAID, "s", FOREIGN_SCHEMA_SAID))))));
        KeriCredentialService service = service(new KeriNotificationCorrelator(keriClient, properties()));
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony()))
                .thenReturn(Either.right(ceremony()));

        Either<ProblemDetail, CeremonyView> first = service.presentCredential(CEREMONY_ID, USER_ID, false);
        Either<ProblemDetail, CeremonyView> second = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, first.getLeft().getTitle());
        assertTrue(queue.isEmpty(), "the rejected grant must be gone from the agent's queue");
        // The next presentation waits for a genuinely new reply instead of re-claiming the rejected one.
        assertEquals(KeriAttestationProblems.KERI_WALLET_TIMEOUT, second.getLeft().getTitle());
        verify(exchanges, times(1)).get(GRANT_SAID);
        verify(ipex, never()).admit(any());
    }
}
