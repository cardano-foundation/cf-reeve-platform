package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
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
import org.cardanofoundation.lob.app.keri_attestation.domain.view.RequiredSteps;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator.ValidatedCredential;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;
import org.cardanofoundation.signify.app.Contacting;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.Exchanging.ExchangeMessageResult;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.aiding.Identifier;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Oobis;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.app.credentialing.credentials.Credentials;
import org.cardanofoundation.signify.app.credentialing.ipex.Ipex;
import org.cardanofoundation.signify.cesr.Serder;
import org.cardanofoundation.signify.core.States;

/**
 * Tests {@link KeriCredentialService#presentCredential}, the single synchronous entry point that
 * replaced the old async split ({@code startCredentialRequest} + {@code awaitPresentation}, dispatched
 * via the now-removed {@code CeremonyAsyncRunner}). The happy path drives the entire
 * apply→offer→agree→grant→admit→validate→complete round trip in one call and asserts both the returned
 * {@link CeremonyView} and the exact submit/wait/mark ordering.
 */
@ExtendWith(MockitoExtension.class)
class KeriCredentialServiceTest {

    private static final String CEREMONY_ID = "cer-1";
    private static final String USER_ID = "user-1";
    private static final int GENERATION = 2;
    private static final String LINKED_AID = "ELINKEDAID000000000000000000000000000";
    private static final String AGENT_NAME = "keriAttestationAgent";
    private static final String SCHEMA_SAID = "ESCHEMA00000000000000000000000000000000";
    // Deliberately distinct from the record's own @DefaultValue -- proves the apply payload and the
    // schema-resolution URL are actually built from the CONFIGURED value, not a coincidentally-matching
    // default.
    private static final String SCHEMA_BASE_URL = "https://schema.example.org/oobi";
    private static final String ROOT_AID = "EROOT00000000000000000000000000000000A";
    private static final String APPLY_SAID = "EAPPLYSAID00000000000000000000000000000";
    private static final String AGREE_SAID = "EAGREESAID00000000000000000000000000000";
    private static final String OFFER_SAID = "EOFFERSAID00000000000000000000000000000";
    private static final String GRANT_SAID = "EGRANTSAID00000000000000000000000000000";
    private static final String ADMIT_SAID = "EADMITSAID00000000000000000000000000000";
    private static final String CREDENTIAL_SAID = "ECREDSAID000000000000000000000000000000";
    private static final String RESULT_SCHEMA_SAID = "ELEAFSCHEMA0000000000000000000000000000";
    private static final String OFFER_NOTIF_ID = "0AOFFERNOTIFID0000000000000000000";
    private static final String GRANT_NOTIF_ID = "0AGRANTNOTIFID0000000000000000000";
    // Mirrors KeriCredentialService's constants: both the "/exn/"-prefixed and bare route forms, since
    // KERIA surfaces the notification route in either form.
    private static final List<String> GRANT_ROUTES = List.of("/exn/ipex/grant", "/ipex/grant");
    // Dual-path presentation: the initial post-apply wait (and the
    // retry pre-check) awaits offer AND grant routes together, since a real Veridian build was observed
    // to send the grant directly with no offer at all.
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
    private Identifier identifiers;
    @Mock
    private Credentials credentials;
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
    private KeriNotificationCorrelator correlator;
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

    private KeriCredentialService service;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.ipex()).thenReturn(ipex);
        lenient().when(client.exchanges()).thenReturn(exchanges);
        lenient().when(client.identifiers()).thenReturn(identifiers);
        lenient().when(client.credentials()).thenReturn(credentials);
        lenient().when(client.oobis()).thenReturn(oobis);
        lenient().when(client.operations()).thenReturn(operations);
        lenient().when(client.contacts()).thenReturn(contacts);
        lenient().when(client.notifications()).thenReturn(notifications);
        lenient().when(agentService.agentName()).thenReturn(AGENT_NAME);
        // Receive-side diagnostics (logReceiveDiagnostics): agentPrefix/agentOobi/contacts.get/
        // notifications.list are all called once, up front, at the start of every presentCredential
        // call — stubbed to succeed here so the diagnostics never hit their own outer catch (which would
        // otherwise mask a real wiring mistake in the diagnostic code itself behind a caught exception).
        lenient().when(agentService.agentPrefix()).thenReturn("EAGENTPREFIX000000000000000000000000000");
        lenient().when(agentService.agentOobi()).thenReturn("http://keria.example/oobi/EAGENTPREFIX/agent");
        lenient().when(contacts.get(any())).thenReturn(Optional.empty());
        lenient().when(notifications.list())
                .thenReturn(new Notifying.Notifications.NotificationListResponse(0, 0, 0, "[]"));
        // Live-testing fix: presentCredential resolves every configured schema SAID as an OOBI on our
        // own agent before beginStep. Defaulted here to succeed so every test not specifically about
        // schema resolution can still reach the rest of the flow; the schema-resolution tests themselves
        // override oobis.resolve to assert on call counts / simulate a failure.
        lenient().when(oobis.resolve(any(), any())).thenReturn(Map.of("done", true));
        lenient().when(operations.wait(any(), any())).thenReturn(null);
        // Every IPEX submit* is followed by an operations().wait(Operation.fromObject(...)) call (the
        // 1-arg overload, distinct from the 2-arg WaitOptions one stubbed above).
        // Operation.fromObject throws IllegalArgumentException on anything that isn't itself an
        // Operation/Map/JSON-String, so submitApply/submitAgree/submitAdmit — otherwise unstubbed, and
        // therefore null by Mockito's own default for an Object-returning method — must return a benign
        // non-null value here for every test not specifically about a submit failure.
        lenient().when(operations.wait(any())).thenReturn(null);
        lenient().when(ipex.submitApply(any(), any(), any(), any())).thenReturn(Map.of());
        lenient().when(ipex.submitAgree(any(), any(), any(), any())).thenReturn(Map.of());
        lenient().when(ipex.submitAdmit(any(), any(), any(), any(), any())).thenReturn(Map.of());
        // Every apply-send fetches the agent's own HabState first (wallet contract — the hand-built
        // /ipex/apply createExchangeMessage call needs it as the signing sender).
        lenient().when(identifiers.get(AGENT_NAME)).thenReturn(Optional.of(habState(AGENT_NAME)));
        lenient().when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), any())).thenReturn(true);
        // Wallet-OOBI re-resolve before presentation is best-effort and skipped when the link has no
        // stored OOBI (the shared link(aid) helper sets none), so most tests never reach it; default it
        // to success so the tests that DO set an OOBI on the link still reach the rest of the flow.
        lenient().when(oobiService.refreshResolve(any(), any(), any())).thenReturn(Either.right(null));

        // The registry is what the apply now asks for, so it carries the schema these tests expect.
        service = new KeriCredentialService(keriClient, agentService, correlator, validator, ceremonyService,
                identityLinkRepository, properties(), registry(), schemaOobiResolver, oobiService);
    }

    private static CredentialSchemaRegistry registry() {
        return new CredentialSchemaRegistry(List.of(new CredentialSchema(SCHEMA_SAID, "Foundation Employee",
                TrustModel.STANDALONE, List.of(), List.of(ROOT_AID), List.of())));
    }

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier",
                new KeriAttestationProperties.CredentialPolicy(List.of(SCHEMA_SAID), List.of(ROOT_AID),
                        SCHEMA_BASE_URL),
                null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT0.01S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT0.01S"), Duration.parse("PT0.05S"), Duration.parse("PT0.01S"),
                Duration.parse("PT0.01S"), Duration.parse("PT2M"), null);
    }

    private static KeriAttestationCeremonyEntity ceremony(String requestExnSaid) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(CEREMONY_ID);
        ceremony.setUserId(USER_ID);
        ceremony.setState(CeremonyState.CREDENTIAL_REQUESTED);
        ceremony.setAttemptGeneration(GENERATION);
        ceremony.setRequestExnSaid(requestExnSaid);
        return ceremony;
    }

    private static KeriIdentityLinkEntity link(String aid) {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid(aid);
        return link;
    }

    private static States.HabState habState(String name) {
        States.HabState hab = new States.HabState();
        hab.setName(name);
        return hab;
    }

    private static Serder serderWithSaid(String said) {
        Serder serder = mock(Serder.class, RETURNS_DEFAULTS);
        lenient().when(serder.getKed()).thenReturn(Map.of("d", said));
        return serder;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> grantExn(String senderAid, String credentialSaid) {
        return Map.of("i", senderAid, "e", Map.of("acdc", Map.of("d", credentialSaid)));
    }

    /** Same shape as {@link #grantExn}, but also carries its own {@code r} on the grant route — the
     *  primary signal {@code KeriCredentialService#isGrantRoute} checks first (falling back to the
     *  notification's claimed route only when this is absent/unrecognized). Used for the spontaneous
     *  (direct) grant tests, which claim off the combined offer-or-grant wait and must be told apart
     *  from an offer. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> directGrantExn(String senderAid, String credentialSaid) {
        return Map.of("i", senderAid, "r", "/exn/ipex/grant", "e", Map.of("acdc", Map.of("d", credentialSaid)));
    }

    private static CeremonyView ceremonyView(CeremonyState state) {
        return new CeremonyView(CEREMONY_ID, state, new RequiredSteps(false, false, false), null, null, null, null,
                null, null);
    }

    /** Stubs {@code ceremonyService.updateWaitingStepData} to apply the mutator to
     *  {@code ceremony} — the same object identity {@code presentCredential}'s caller holds — and report
     *  success, mirroring how {@code CeremonyService}'s real guarded update would behave when the row
     *  lock still matches. */
    private void stubGuardedUpdateSuccess(KeriAttestationCeremonyEntity ceremony) {
        when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), any())).thenAnswer(inv -> {
                    Consumer<KeriAttestationCeremonyEntity> mutator = inv.getArgument(3);
                    mutator.accept(ceremony);
                    return true;
                });
    }

    /** Stubs the full happy-path wallet round trip (apply build/send, offer, agree, grant, admit,
     *  fetch, validate, complete) so individual tests only need to override the one thing they care
     *  about. {@code lenient()} throughout: several tests built on top of this helper deliberately
     *  diverge (fail before reaching a later stub, or re-stub an earlier matcher for a different
     *  {@code retry} value), which would otherwise trip Mockito's strict-stubbing "unnecessary
     *  stubbing" check. */
    private void stubHappyPath(KeriAttestationCeremonyEntity ceremony) throws Exception {
        lenient().when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        lenient().when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder applyExn = serderWithSaid(APPLY_SAID);
        lenient().when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID),
                any(), any())).thenReturn(new ExchangeMessageResult(applyExn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);

        lenient().when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));

        Serder agreeExn = serderWithSaid(AGREE_SAID);
        lenient().when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));

        lenient().when(correlator.awaitByRoute(eq(GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));

        Serder admitExn = serderWithSaid(ADMIT_SAID);
        lenient().when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));

        lenient().when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        lenient().when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID, "Foundation Employee", ROOT_AID, ROOT_AID,
                        TrustModel.STANDALONE, Map.of(), "fingerprint")));
        lenient().when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(true);
        lenient().when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREDENTIAL_RECEIVED)));
    }

    // ==================== presentCredential: happy path ====================

    @Test
    void presentCredentialHappyPathWalksApplyOfferAgreeGrantAdmitValidatesAndCompletesInOrder() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        KeriIdentityLinkEntity freshLink = link(LINKED_AID);
        stubHappyPath(ceremony);
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(freshLink));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, result.get().state());
        assertEquals(APPLY_SAID, ceremony.getRequestExnSaid());

        verify(ipex).submitApply(eq(AGENT_NAME), any(), eq(List.of("sig1")), eq(List.of(LINKED_AID)));
        verify(ipex).submitAgree(eq(AGENT_NAME), any(), eq(List.of("sig2")), eq(List.of(LINKED_AID)));
        // submitAdmit is given the AGREE's atc ("atc2"), NOT the admit's own ("atc3") — a proven
        // wallet-contract quirk this module matches.
        verify(ipex).submitAdmit(eq(AGENT_NAME), any(), eq(List.of("sig3")), eq("atc2"), eq(List.of(LINKED_AID)));
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());

        // Exact expected order: apply -> wait -> offer -> mark/delete offer -> agree -> wait -> grant ->
        // admit -> wait -> mark/delete grant.
        InOrder inOrder = inOrder(ipex, operations, correlator);
        inOrder.verify(ipex).submitApply(any(), any(), any(), any());
        inOrder.verify(operations, times(1)).wait(any());
        inOrder.verify(correlator).markAndDelete(OFFER_NOTIF_ID);
        inOrder.verify(ipex).agree(any());
        inOrder.verify(ipex).submitAgree(any(), any(), any(), any());
        inOrder.verify(ipex).admit(any());
        inOrder.verify(ipex).submitAdmit(any(), any(), any(), any(), any());
        inOrder.verify(correlator).markAndDelete(GRANT_NOTIF_ID);

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), mutatorCaptor.capture());
        verify(identityLinkRepository, never()).save(any());
        mutatorCaptor.getValue().accept(ceremony);
        assertEquals(CREDENTIAL_SAID, freshLink.getCredentialSaid());
        assertEquals(RESULT_SCHEMA_SAID, freshLink.getCredentialSchemaSaid());
        verify(identityLinkRepository).save(freshLink);

        verify(correlator, times(1)).markAndDelete(OFFER_NOTIF_ID);
        verify(correlator, times(1)).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void presentCredentialAppliesSendsPayloadWithTopLevelOobiUrl() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubHappyPath(ceremony);

        service.presentCredential(CEREMONY_ID, USER_ID, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(exchanges).createExchangeMessage(any(), eq("/ipex/apply"), payloadCaptor.capture(), anyMap(),
                eq(LINKED_AID), any(), any());
        assertEquals(Map.of("m", "", "s", SCHEMA_SAID, "a", Map.of(), "oobiUrl", SCHEMA_BASE_URL + "/"),
                payloadCaptor.getValue());
    }

    // ==================== presentCredential: guard failures ====================

    @Test
    void presentCredentialBeginStepFailureReturnsLeftWithoutOtherInteractions() {
        ProblemDetail problem = KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE, "x");
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.left(problem));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(problem, result.getLeft());
        verifyNoInteractions(identityLinkRepository, correlator, ipex);
    }

    @Test
    void presentCredentialWithNoIdentityLinkFailsWithIdentityNotLinked() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony(null)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_NOT_LINKED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.IDENTITY_NOT_LINKED,
                "User user-1 has no linked identity to request a credential presentation from.");
        verifyNoInteractions(correlator, ipex);
    }

    @Test
    void presentCredentialReResolvesTheWalletOobiBeforeSendingTheApply() throws Exception {
        String walletOobiUrl = "https://keria-ext.dev.example.org/oobi/" + LINKED_AID + "/agent/EAGENT";
        KeriIdentityLinkEntity linkWithOobi = link(LINKED_AID);
        linkWithOobi.setOobiUrl(walletOobiUrl);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony(null)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkWithOobi));
        // Fail the apply build right after, so the flow stops early — we only care that the re-resolve
        // ran, and ran BEFORE the apply is built/sent.
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenThrow(new RuntimeException("agent unreachable"));

        service.presentCredential(CEREMONY_ID, USER_ID, false);

        InOrder inOrder = inOrder(oobiService, exchanges);
        inOrder.verify(oobiService).refreshResolve(USER_ID, walletOobiUrl, LINKED_AID);
        inOrder.verify(exchanges).createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(),
                eq(LINKED_AID), any(), any());
    }

    @Test
    void presentCredentialProceedsBestEffortWhenTheWalletOobiReResolveFails() throws Exception {
        String walletOobiUrl = "https://keria-ext.dev.example.org/oobi/" + LINKED_AID + "/agent/EAGENT";
        KeriIdentityLinkEntity linkWithOobi = link(LINKED_AID);
        linkWithOobi.setOobiUrl(walletOobiUrl);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony(null)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkWithOobi));
        when(oobiService.refreshResolve(USER_ID, walletOobiUrl, LINKED_AID))
                .thenReturn(Either.left(ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "agent down")));
        // A re-resolve failure must NOT block the presentation: the apply is still attempted.
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenThrow(new RuntimeException("stop here"));

        service.presentCredential(CEREMONY_ID, USER_ID, false);

        verify(exchanges).createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(),
                eq(LINKED_AID), any(), any());
    }

    @Test
    void presentCredentialApplyBuildFailureFailsWithCredentialRequestFailed() throws Exception {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony(null)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenThrow(new RuntimeException("agent unreachable"));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(correlator);
    }

    @Test
    void presentCredentialSubmitApplyFailureFailsWithCredentialRequestFailedButStillPersistsRequestExnSaid()
            throws Exception {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony(null)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder exn = serderWithSaid(APPLY_SAID);
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenReturn(new ExchangeMessageResult(exn, List.of("sig1"), "atc1"));
        when(ipex.submitApply(any(), any(), any(), any())).thenThrow(new IOException("network blip"));
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubGuardedUpdateSuccess(ceremony);

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, result.getLeft().getTitle());
        assertEquals(APPLY_SAID, ceremony.getRequestExnSaid());
        verifyNoInteractions(correlator);
    }

    @Test
    void presentCredentialOfferOrGrantTimeoutFailsWithKeriWalletTimeoutAndNeverBuildsAgreeOrAdmits() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder exn = serderWithSaid(APPLY_SAID);
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenReturn(new ExchangeMessageResult(exn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);
        // Neither an offer nor a grant ever arrives.
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any())).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.KERI_WALLET_TIMEOUT, result.getLeft().getTitle());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.KERI_WALLET_TIMEOUT),
                argThat(detail -> detail.startsWith("Timed out waiting for /exn/ipex/offer or /exn/ipex/grant.")));
        verify(ipex, never()).agree(any());
        verify(ipex, never()).admit(any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void presentCredentialGrantTimeoutFailsWithKeriWalletTimeoutAndNeverAdmits() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder applyExn = serderWithSaid(APPLY_SAID);
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenReturn(new ExchangeMessageResult(applyExn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitByRoute(eq(GRANT_ROUTES), any())).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.KERI_WALLET_TIMEOUT, result.getLeft().getTitle());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.KERI_WALLET_TIMEOUT),
                argThat(detail -> detail.startsWith("Timed out waiting for /exn/ipex/grant.")));
        verify(ipex, never()).admit(any());
        // The offer was already claimed before the grant wait even started.
        verify(correlator).markAndDelete(OFFER_NOTIF_ID);
        verify(correlator, never()).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void presentCredentialValidatorRejectionFailsWithCredentialRejectedAndDoesNotMarkTheGrantNotification()
            throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubHappyPath(ceremony);
        ProblemDetail rejection = KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REJECTED,
                "issuee mismatch");
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenReturn(Either.left(rejection));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.CREDENTIAL_REJECTED, "issuee mismatch");
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(identityLinkRepository, never()).save(any());
        verify(correlator).markAndDelete(OFFER_NOTIF_ID);
        verify(correlator, never()).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void presentCredentialValidatorThrowingFailsTheStepInsteadOfPropagating() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubHappyPath(ceremony);
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenThrow(new RuntimeException("null issuer somewhere in the chain"));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void presentCredentialValidatedLeafSaidMismatchingFetchedCredentialSaidIsRejected() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubHappyPath(ceremony);
        String someOtherCredentialSaid = "ESOMEOTHERCREDSAID00000000000000000000";
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenReturn(Either.right(new ValidatedCredential(someOtherCredentialSaid, RESULT_SCHEMA_SAID, "Foundation Employee", ROOT_AID, ROOT_AID,
                        TrustModel.STANDALONE, Map.of(), "fingerprint")));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void presentCredentialStaleCompleteStepNeverMarksTheGrantNotificationAsClaimedOrWritesTheLink() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubHappyPath(ceremony);
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(false);

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
        verify(correlator).markAndDelete(OFFER_NOTIF_ID);
        verify(correlator, never()).markAndDelete(GRANT_NOTIF_ID);
        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void presentCredentialRelinkMidFlightCompletesTheStepButSkipsPersistingTheStaleCredential() throws Exception {
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony(null);
        ceremonyEntity.setBindingVersion(1);
        stubHappyPath(ceremonyEntity);
        KeriIdentityLinkEntity relinkedLink = link("EOTHERAID000000000000000000000000000A");
        relinkedLink.setBindingVersion(2);
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(relinkedLink));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), mutatorCaptor.capture());
        mutatorCaptor.getValue().accept(ceremonyEntity);
        assertNull(relinkedLink.getCredentialSaid());
        verify(identityLinkRepository, never()).save(any());
        verify(correlator).markAndDelete(GRANT_NOTIF_ID);
    }

    // ==================== presentCredential: retry pre-check ====================

    @Test
    void presentCredentialRetryWithLateArrivedOfferSkipsResendAndProceedsThroughTheRestOfTheFlow() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(APPLY_SAID);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitByRoute(eq(GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID, "Foundation Employee", ROOT_AID, ROOT_AID,
                        TrustModel.STANDALONE, Map.of(), "fingerprint")));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(true);
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREDENTIAL_RECEIVED)));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verify(exchanges, never()).createExchangeMessage(any(), eq("/ipex/apply"), any(), any(), any(), any(), any());
        verify(ipex, never()).submitApply(any(), any(), any(), any());
        verify(ipex).admit(any());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void presentCredentialRetryWithNoLateOfferFallsThroughToTheNormalSendFlow() throws Exception {
        String oldApplySaid = "EOLDAPPLYSAID000000000000000000000000000";
        KeriAttestationCeremonyEntity ceremony = ceremony(oldApplySaid);
        stubHappyPath(ceremony);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, true)).thenReturn(Either.right(ceremony));
        // The FIRST awaitByRoute(OFFER_OR_GRANT_ROUTES, ...) call is the short retry pre-check (must
        // find nothing, so the apply actually gets resent); the SECOND is the normal post-apply wait
        // (stubHappyPath's own present-offer default, reused here for the second invocation).
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verify(ipex).submitApply(eq(AGENT_NAME), any(), eq(List.of("sig1")), eq(List.of(LINKED_AID)));
    }

    // ==================== presentCredential: dual-path (spontaneous grant) ====================

    @Test
    void presentCredentialGrantArrivesDirectlyAdmitsWithoutOfferOrAgreeUsingTheAdmitsOwnAtc() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder applyExn = serderWithSaid(APPLY_SAID);
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenReturn(new ExchangeMessageResult(applyExn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);

        // Only an unread GRANT notification is ever present -- the live Veridian evidence this
        // dual-path branch exists for: after the apply, the wallet sends a grant directly, with no
        // offer (and therefore no agree) at all.
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        directGrantExn(LINKED_AID, CREDENTIAL_SAID), "/exn/ipex/grant")));

        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "directAdmitAtc"));

        KeriIdentityLinkEntity freshLink = link(LINKED_AID);
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(freshLink));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID, "Foundation Employee", ROOT_AID, ROOT_AID,
                        TrustModel.STANDALONE, Map.of(), "fingerprint")));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(true);
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREDENTIAL_RECEIVED)));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, result.get().state());

        verify(ipex).submitApply(eq(AGENT_NAME), any(), eq(List.of("sig1")), eq(List.of(LINKED_AID)));
        // The admit's OWN atc ("directAdmitAtc"), never an agree's -- there is no agree in this branch
        // at all to borrow one from.
        verify(ipex).submitAdmit(eq(AGENT_NAME), any(), eq(List.of("sig3")), eq("directAdmitAtc"),
                eq(List.of(LINKED_AID)));
        verify(ipex, never()).agree(any());
        verify(ipex, never()).submitAgree(any(), any(), any(), any());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());

        // The grant notification is claimed only AFTER fetch/validate/persist -- same durability
        // contract as the negotiated path's own deferred delete (KeriNotificationCorrelator#markAndDelete's
        // javadoc): nothing durable records which credential was admitted until completeStep commits, so
        // deleting any earlier would let a crash between admit and persist silently lose the wallet's
        // grant with no way for a retry to recover it.
        InOrder inOrder = inOrder(ipex, operations, credentials, validator, ceremonyService, correlator);
        inOrder.verify(ipex).admit(any());
        inOrder.verify(ipex).submitAdmit(any(), any(), any(), any(), any());
        inOrder.verify(operations).wait(any());
        inOrder.verify(credentials).get(CREDENTIAL_SAID);
        inOrder.verify(validator).validate(any(), any(), any(), any());
        inOrder.verify(ceremonyService).completeStep(any(), anyInt(), any(), any(), any());
        inOrder.verify(correlator).markAndDelete(GRANT_NOTIF_ID);
        verify(correlator, times(1)).markAndDelete(GRANT_NOTIF_ID);

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), mutatorCaptor.capture());
        mutatorCaptor.getValue().accept(ceremony);
        assertEquals(CREDENTIAL_SAID, freshLink.getCredentialSaid());
        assertEquals(RESULT_SCHEMA_SAID, freshLink.getCredentialSchemaSaid());
    }

    @Test
    void presentCredentialGrantArrivesDirectlyButValidatorRejectsFailsWithCredentialRejectedAndDoesNotMarkTheGrantNotification()
            throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder applyExn = serderWithSaid(APPLY_SAID);
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenReturn(new ExchangeMessageResult(applyExn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        directGrantExn(LINKED_AID, CREDENTIAL_SAID), "/exn/ipex/grant")));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "directAdmitAtc"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        ProblemDetail rejection = KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REJECTED,
                "issuee mismatch");
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenReturn(Either.left(rejection));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.CREDENTIAL_REJECTED, "issuee mismatch");
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(identityLinkRepository, never()).save(any());
        // Same durability contract as the negotiated path (see
        // presentCredentialValidatorRejectionFailsWithCredentialRejectedAndDoesNotMarkTheGrantNotification):
        // the grant is never deleted unless the credential was actually persisted.
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void presentCredentialGrantArrivesDirectlyOnRetryPrecheckSkipsResendAndAdmitsWithoutAgree() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(APPLY_SAID);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        directGrantExn(LINKED_AID, CREDENTIAL_SAID), "/exn/ipex/grant")));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "directAdmitAtc"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID, "Foundation Employee", ROOT_AID, ROOT_AID,
                        TrustModel.STANDALONE, Map.of(), "fingerprint")));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(true);
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREDENTIAL_RECEIVED)));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verify(exchanges, never()).createExchangeMessage(any(), eq("/ipex/apply"), any(), any(), any(), any(), any());
        verify(ipex, never()).submitApply(any(), any(), any(), any());
        verify(ipex, never()).agree(any());
        verify(ipex).admit(any());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void presentCredentialGrantArrivesDirectlyIsRecognizedViaClaimedRouteWhenTheFetchedExnHasNoOwnRoute()
            throws Exception {
        // isGrantRoute's fallback (KeriCredentialService javadoc): the fetched exn carries no "r" at
        // all here, so the branch decision must fall back to the notification's own claimed route
        // rather than default to (incorrectly) treating this as an offer.
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder applyExn = serderWithSaid(APPLY_SAID);
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenReturn(new ExchangeMessageResult(applyExn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID), "/exn/ipex/grant")));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "directAdmitAtc"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, CREDENTIAL_SAID, null))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID, "Foundation Employee", ROOT_AID, ROOT_AID,
                        TrustModel.STANDALONE, Map.of(), "fingerprint")));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(true);
        when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.CREDENTIAL_RECEIVED)));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        verify(ipex, never()).agree(any());
        verify(ipex, never()).submitAgree(any(), any(), any(), any());
        verify(ipex).admit(any());
        verify(ipex).submitAdmit(eq(AGENT_NAME), any(), eq(List.of("sig3")), eq("directAdmitAtc"),
                eq(List.of(LINKED_AID)));
    }

    @Test
    void presentCredentialGrantArrivesDirectlyWithoutAnEmbeddedAcdcFailsWithCredentialRequestFailedAndNeverAdmits()
            throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder applyExn = serderWithSaid(APPLY_SAID);
        when(exchanges.createExchangeMessage(any(), eq("/ipex/apply"), anyMap(), anyMap(), eq(LINKED_AID), any(), any()))
                .thenReturn(new ExchangeMessageResult(applyExn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);
        Map<String, Object> grantExnWithoutAcdc = Map.of("i", LINKED_AID, "r", "/exn/ipex/grant");
        when(correlator.awaitByRoute(eq(OFFER_OR_GRANT_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID, grantExnWithoutAcdc,
                        "/exn/ipex/grant")));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                "IPEX grant exchange did not embed an ACDC (e.acdc.d missing).");
        verify(ipex, never()).admit(any());
        verify(correlator, never()).markAndDelete(any());
    }

    // ==================== schema OOBI resolution (Fix 3, live-testing) ====================

    @Test
    void presentCredentialResolvesEachSchemaSaidOnOurAgentOnceThenCacheIsWarmOnASecondRequest() throws Exception {
        String schemaUrl = SCHEMA_BASE_URL + "/" + SCHEMA_SAID;
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubHappyPath(ceremony);

        Either<ProblemDetail, CeremonyView> first = service.presentCredential(CEREMONY_ID, USER_ID, false);
        Either<ProblemDetail, CeremonyView> second = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(first.isRight());
        assertTrue(second.isRight());
        verify(oobis, times(1)).resolve(schemaUrl, null);
        verify(operations, times(1)).wait(any(), any());
    }

    @Test
    void presentCredentialSchemaResolutionFailureReturnsKeriAgentUnavailableBeforeAnyCeremonyStateIsTouched()
            throws Exception {
        String schemaUrl = SCHEMA_BASE_URL + "/" + SCHEMA_SAID;
        when(oobis.resolve(schemaUrl, null)).thenThrow(new RuntimeException("agent unreachable"));

        Either<ProblemDetail, CeremonyView> result = service.presentCredential(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.KERI_AGENT_UNAVAILABLE, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains(schemaUrl));
        verifyNoInteractions(ceremonyService, identityLinkRepository, ipex);
    }
}
