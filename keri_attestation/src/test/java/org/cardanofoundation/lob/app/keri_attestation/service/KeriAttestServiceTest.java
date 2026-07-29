package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.RequiredSteps;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.Exchanging.ExchangeMessageResult;
import org.cardanofoundation.signify.app.aiding.Identifier;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.KeyStates;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.cesr.Serder;
import org.cardanofoundation.signify.core.States;

/**
 * Tests {@link KeriAttestService#attest}, the single synchronous entry point that replaced the old
 * async split ({@code startAttest} + {@code awaitAnchor}, dispatched via the now-removed
 * {@code CeremonyAsyncRunner}). The happy path drives authorize→freeze→build→send→wait→verify→complete
 * in one call and asserts both the returned {@link CeremonyView} and the ordering of the underlying
 * wire calls.
 */
@ExtendWith(MockitoExtension.class)
class KeriAttestServiceTest {

    private static final String CEREMONY_ID = "cer-1";
    private static final String USER_ID = "user-1";
    private static final int GENERATION = 2;
    private static final String WALLET_AID = "EWALLETAID00000000000000000000000000000";
    private static final String AGENT_NAME = "keriAttestationAgent";
    private static final String AGENT_PREFIX = "EAGENTPREFIX000000000000000000000000000";
    private static final String TARGET_TYPE = "DOCUMENT";
    private static final String TARGET_ID = "doc-1";
    private static final String DIGEST = "Edigest0000000000000000000000000000000000000";
    private static final String METADATA_LABEL = "1447";
    private static final String PAYLOAD_SAID = "Epayloadsaid00000000000000000000000000000000";
    private static final String OLD_REQUEST_EXN_SAID = "EOLDREQ0000000000000000000000000000000";
    private static final String NEW_REQUEST_EXN_SAID = "ENEWREQ0000000000000000000000000000000";
    private static final String REF_EXN_SAID = "EREFEXN0000000000000000000000000000000";
    private static final String NOTIF_ID = "0ANOTIFID0000000000000000000000000000";
    private static final String EVENT_SAID = "EEVENT00000000000000000000000000000000";
    private static final String SEQUENCE = "3";
    private static final String FLOOR_SEQUENCE = "2";
    private static final List<String> REMOTESIGN_REF_ROUTES =
            List.of("/remotesign/ixn/ref", "/exn/remotesign/ixn/ref");

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Identifier identifiers;
    @Mock
    private Exchanging.Exchanges exchanges;
    @Mock
    private KeyStates keyStates;
    @Mock
    private Coring.KeyEvents keyEvents;
    @Mock
    private Operations operations;
    @Mock
    private KeriAgentService agentService;
    @Mock
    private RemotesignRequestFactory kedFactory;
    @Mock
    private AttestationTargetProviderRegistry providerRegistry;
    @Mock
    private AttestationTargetProvider provider;
    @Mock
    private KeriNotificationCorrelator correlator;
    @Mock
    private CeremonyService ceremonyService;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private KeriOobiService oobiService;

    private KeriAttestService service;

    @BeforeEach
    void setUp() {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.identifiers()).thenReturn(identifiers);
        lenient().when(client.exchanges()).thenReturn(exchanges);
        lenient().when(client.keyStates()).thenReturn(keyStates);
        lenient().when(client.keyEvents()).thenReturn(keyEvents);
        lenient().when(client.operations()).thenReturn(operations);
        lenient().when(agentService.agentName()).thenReturn(AGENT_NAME);
        // Default: no pre-existing remotesign-ref debris. The snapshot-and-exclude test overrides this.
        lenient().when(correlator.outstandingNoteIds(any())).thenReturn(Set.of());

        service = new KeriAttestService(keriClient, agentService, kedFactory, providerRegistry, correlator,
                ceremonyService, identityLinkRepository, properties(), oobiService);
    }

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier", null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT0.01S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT0.01S"), Duration.parse("PT0.05S"), Duration.parse("PT0.001S"),
                Duration.parse("PT0.001S"), Duration.parse("PT2M"), null);
    }

    private static KeriAttestationCeremonyEntity ceremony(CeremonyState state, String requestExnSaid) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(CEREMONY_ID);
        ceremony.setUserId(USER_ID);
        ceremony.setTargetType(TARGET_TYPE);
        ceremony.setTargetId(TARGET_ID);
        ceremony.setState(state);
        ceremony.setAttemptGeneration(GENERATION);
        ceremony.setRequestExnSaid(requestExnSaid);
        ceremony.setMetadataDigest(DIGEST);
        return ceremony;
    }

    private static KeriIdentityLinkEntity link(String aid) {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setAid(aid);
        return link;
    }

    private static States.HabState habState(String prefix) {
        States.HabState hab = new States.HabState();
        hab.setPrefix(prefix);
        hab.setName(AGENT_NAME);
        return hab;
    }

    private static Serder serderWithSaid(String said) {
        Serder serder = mock(Serder.class, RETURNS_DEFAULTS);
        lenient().when(serder.getKed()).thenReturn(Map.of("d", said));
        return serder;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> refExn(String senderAid, String sequence, String said) {
        Map<String, Object> payload = sequence == null && said == null
                ? Map.of()
                : sequenceSaidPayload(sequence, said);
        return Map.of("i", senderAid, "a", payload);
    }

    private static Map<String, Object> sequenceSaidPayload(String sequence, String said) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        if (sequence != null) {
            payload.put("s", sequence);
        }
        if (said != null) {
            payload.put("d", said);
        }
        return payload;
    }

    private static Map<String, Object> kelEvent(String type, String sequence, String said, Object seals) {
        java.util.LinkedHashMap<String, Object> ked = new java.util.LinkedHashMap<>();
        ked.put("t", type);
        ked.put("s", sequence);
        ked.put("d", said);
        ked.put("a", seals);
        return Map.of("ked", ked);
    }

    private static CeremonyView ceremonyView(CeremonyState state) {
        return new CeremonyView(CEREMONY_ID, state, new RequiredSteps(false, false, false), null, null, null, null,
                null, null);
    }

    private Serder stubHappySend() throws Exception {
        when(identifiers.get(AGENT_NAME)).thenReturn(Optional.of(habState(AGENT_PREFIX)));
        Serder builtExn = serderWithSaid(NEW_REQUEST_EXN_SAID);
        when(exchanges.createExchangeMessage(any(), eq("/remotesign/ixn/req"), anyMap(), anyMap(), eq(WALLET_AID),
                any(), any())).thenReturn(new ExchangeMessageResult(builtExn, List.of("sig1"), "atc1"));
        lenient().when(exchanges.sendFromEvents(eq(AGENT_NAME), eq("remotesign"), eq(builtExn), eq(List.of("sig1")),
                eq("atc1"), eq(List.of(WALLET_AID)))).thenReturn(Map.of());
        return builtExn;
    }

    /** Stubs the key-state query ({@code keyStates.query} + {@code operations.wait}) that
     *  {@code queryLatestSequenceWithRetries} performs — used both by the pre-send floor query and by
     *  {@code resolveAndComplete}'s no-candidate bounded-scan fallback (the current-sequence query). */
    private void stubKeyStateSequence(String sequence) throws Exception {
        when(keyStates.query(eq(WALLET_AID), any())).thenReturn(Map.of());
        Operation<Object> op = Operation.builder().response(Map.of("s", sequence)).build();
        when(operations.wait(any(), any(Operations.WaitOptions.class))).thenReturn(op);
    }

    /** Stubs {@code ceremonyService.updateWaitingStepData} to apply whichever mutator it is
     *  called with to {@code ceremony} and report success. {@code attest} calls this three times per
     *  attempt (digest, floor, requestExnSaid/payloadSaid) with the same
     *  {@code (ceremonyId, generation, ATTEST_REQUESTED)} arguments, so one stub serves all three. */
    private void stubGuardedUpdateSuccess(KeriAttestationCeremonyEntity ceremony) {
        when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                any())).thenAnswer(inv -> {
                    Consumer<KeriAttestationCeremonyEntity> mutator = inv.getArgument(3);
                    mutator.accept(ceremony);
                    return true;
                });
    }

    /** Stubs the full happy-path send-and-anchor round trip so individual tests only override the one
     *  thing they care about. {@code lenient()} throughout: several tests built on top of this helper
     *  deliberately diverge (or override matchers) before reaching a later stub — e.g. a seal-mismatch
     *  test never reaches {@code completeStep}/{@code ceremonyService.get} at all, and the retry test
     *  re-stubs {@code beginStep}/{@code correlator.awaitByRoute} for {@code retry=true} — which would
     *  otherwise trip Mockito's strict-stubbing "unnecessary stubbing" check on the unused ones. */
    private void stubHappyPath(KeriAttestationCeremonyEntity ceremony) throws Exception {
        lenient().when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        lenient().when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        lenient().when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        lenient().when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        lenient().when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        lenient().when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST))
                .thenReturn(Map.of("d", PAYLOAD_SAID));
        stubHappySend();
        stubKeyStateSequence(FLOOR_SEQUENCE);
        stubGuardedUpdateSuccess(ceremony);

        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        lenient().when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        lenient().when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, seal)));
        // Applies the mutator to `ceremony` (the same object identity the caller holds) so tests can
        // assert kelSequence/kelEventSaid/attesterAid directly on it, mirroring CeremonyService's real
        // completeStep behavior when the row lock still matches.
        lenient().when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any())).thenAnswer(inv -> {
                    Consumer<KeriAttestationCeremonyEntity> mutator = inv.getArgument(4);
                    mutator.accept(ceremony);
                    return true;
                });
        lenient().when(ceremonyService.get(CEREMONY_ID, USER_ID))
                .thenReturn(Either.right(ceremonyView(CeremonyState.ATTEST_ANCHORED)));
    }

    // ==================== attest: happy path ====================

    @Test
    void attestHappyPathAuthorizesFreezesBuildsSendsWaitsVerifiesAndCompletesInOrder() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        stubHappyPath(ceremony);

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.ATTEST_ANCHORED, result.get().state());
        assertEquals(METADATA_LABEL, ceremony.getMetadataLabel());
        assertEquals(NEW_REQUEST_EXN_SAID, ceremony.getRequestExnSaid());
        assertEquals(PAYLOAD_SAID, ceremony.getPayloadSaid());
        assertEquals(FLOOR_SEQUENCE, ceremony.getKelFloorSequence());
        assertEquals(SEQUENCE, ceremony.getKelSequence());
        assertEquals(EVENT_SAID, ceremony.getKelEventSaid());
        assertEquals(WALLET_AID, ceremony.getAttesterAid());

        verify(ceremonyService, times(3)).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.ATTEST_REQUESTED), any());
        verify(exchanges).createExchangeMessage(any(), eq("/remotesign/ixn/req"), eq(Map.of("d", PAYLOAD_SAID)),
                eq(Map.of()), eq(WALLET_AID), any(), any());
        verify(exchanges).sendFromEvents(eq(AGENT_NAME), eq("remotesign"), any(), eq(List.of("sig1")), eq("atc1"),
                eq(List.of(WALLET_AID)));
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());

        InOrder inOrder = inOrder(exchanges, correlator, ceremonyService);
        inOrder.verify(exchanges).sendFromEvents(any(), any(), any(), any(), any(), any());
        inOrder.verify(correlator).awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), any());
        inOrder.verify(ceremonyService).completeStep(any(), anyInt(), any(), any(), any());
        inOrder.verify(correlator).markAndDelete(NOTIF_ID);
    }

    @Test
    void attestSnapshotsPreexistingRefsBeforeSendingAndExcludesThemFromTheWait() throws Exception {
        // Regression: a leftover unread remotesign-ref from an earlier failed attempt must NOT be
        // claimed as this request's reply (which would fail ATTEST_SEAL_MISMATCH before the wallet
        // signs). The snapshot is taken BEFORE the send, and its ids are passed to the wait's exclude set.
        String staleNoteId = "0ASTALEREFNOTE00000000000000000000000";
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        stubHappyPath(ceremony);
        when(correlator.outstandingNoteIds(REMOTESIGN_REF_ROUTES)).thenReturn(Set.of(staleNoteId));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        // The snapshot happens before the request is sent, and the same set is handed to the wait.
        InOrder inOrder = inOrder(correlator, exchanges);
        inOrder.verify(correlator).outstandingNoteIds(REMOTESIGN_REF_ROUTES);
        inOrder.verify(exchanges).sendFromEvents(any(), any(), any(), any(), any(), any());
        inOrder.verify(correlator).awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), eq(Set.of(staleNoteId)));
    }

    /**
     * The wallet's OOBI is re-resolved twice, and both matter:
     *
     * <ul>
     *   <li><b>Before the send</b> — a contact resolved once at pairing goes stale when the wallet
     *       rotates keys or its KERIA endpoints change. KERIA then accepts the exn but has nowhere to
     *       route it, so the wallet never surfaces the signing request and the step can only time out.
     *       This was a live defect: credential presentation re-resolved before its send, the ATTEST
     *       send did not, which is exactly why presentation worked and attestation did not.</li>
     *   <li><b>Before the KEL read</b> — cross-KERIA, our view of the wallet's KEL can lag the ixn it
     *       just anchored, and the fresh anchor would be missed as ATTEST_SEAL_MISMATCH.</li>
     * </ul>
     */
    @Test
    void attestReResolvesTheWalletOobiBeforeBothTheSendAndTheKelRead() throws Exception {
        String oobiUrl = "http://wallet.example/oobi/EWALLET/agent";
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        stubHappyPath(ceremony);
        KeriIdentityLinkEntity linkWithOobi = link(WALLET_AID);
        linkWithOobi.setOobiUrl(oobiUrl);
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(linkWithOobi));
        when(oobiService.refreshResolve(USER_ID, oobiUrl, WALLET_AID)).thenReturn(Either.right(null));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.ATTEST_ANCHORED, result.get().state());
        InOrder inOrder = inOrder(oobiService, exchanges, keyEvents);
        inOrder.verify(oobiService).refreshResolve(USER_ID, oobiUrl, WALLET_AID);
        inOrder.verify(exchanges).sendFromEvents(any(), any(), any(), any(), any(), any());
        inOrder.verify(oobiService).refreshResolve(USER_ID, oobiUrl, WALLET_AID);
        inOrder.verify(keyEvents).get(WALLET_AID);
    }

    @Test
    void attestSkipsTheOobiReResolveWhenTheLinkHasNoStoredOobi() throws Exception {
        // Best-effort: a link with no stored OOBI just reads the KEL on current state, no re-resolve.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        stubHappyPath(ceremony);

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        verifyNoInteractions(oobiService);
    }

    // ==================== attest: guard failures ====================

    @Test
    void attestBeginStepFailureReturnsLeftWithoutOtherInteractions() {
        ProblemDetail problem = KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE, "x");
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.left(problem));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(problem, result.getLeft());
        verifyNoInteractions(identityLinkRepository, providerRegistry, correlator);
    }

    @Test
    void attestWithNoIdentityLinkFailsWithIdentityNotLinked() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_NOT_LINKED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                KeriAttestationProblems.IDENTITY_NOT_LINKED, "User user-1 has no linked identity to attest with.");
        verifyNoInteractions(providerRegistry);
    }

    @Test
    void attestWithNoProviderRegisteredFailsWithAttestRequestFailed() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_REQUEST_FAILED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_REQUEST_FAILED), any());
    }

    @Test
    void attestProviderAuthorizeRejectionFailsWithTheProvidersProblem() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        ProblemDetail authProblem = KeriAttestationProblems.forbidden("not your document");
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.of(authProblem));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(authProblem, result.getLeft());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                authProblem.getTitle(), authProblem.getDetail());
    }

    @Test
    void attestStaleGuardedUpdateOnDigestWriteReturnsInvalidStateAndNeverSends() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                any())).thenReturn(false);

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
        verifyNoInteractions(kedFactory, exchanges, identifiers);
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void attestPrepareDigestFailureFailsWithTheProvidersProblemAndNeverPersistsDigest() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        ProblemDetail digestProblem = KeriAttestationProblems.unprocessable("FREEZE_FAILED", "could not freeze");
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID)).thenReturn(Either.left(digestProblem));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(digestProblem, result.getLeft());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                digestProblem.getTitle(), digestProblem.getDetail());
        verify(ceremonyService, never()).updateWaitingStepData(any(), anyInt(), any(), any());
    }

    @Test
    void attestExchangeSendFailureFailsWithAttestRequestFailedButStillPersistsTheDigest() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST)).thenReturn(Map.of("d", PAYLOAD_SAID));
        when(identifiers.get(AGENT_NAME)).thenReturn(Optional.of(habState(AGENT_PREFIX)));
        when(exchanges.createExchangeMessage(any(), anyString(), anyMap(), anyMap(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("agent unreachable"));
        stubKeyStateSequence(FLOOR_SEQUENCE);
        stubGuardedUpdateSuccess(ceremony);

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_REQUEST_FAILED, result.getLeft().getTitle());
        assertNull(ceremony.getRequestExnSaid());
        assertEquals(FLOOR_SEQUENCE, ceremony.getKelFloorSequence());
        verify(ceremonyService, times(2)).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.ATTEST_REQUESTED), any());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_REQUEST_FAILED), any());
    }

    // ==================== attest: retry pre-check ====================

    @Test
    void attestRetryWithLateArrivedCorrelatedRefCompletesWithoutResending() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        ceremony.setKelFloorSequence(FLOOR_SEQUENCE);
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));

        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        when(keyEvents.get(WALLET_AID))
                .thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, seal)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any())).thenReturn(true);
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.ATTEST_ANCHORED)));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verifyNoInteractions(providerRegistry, kedFactory);
        verify(exchanges, never()).createExchangeMessage(any(), any(), any(), any(), any(), any(), any());
        verify(exchanges, never()).sendFromEvents(any(), any(), any(), any(), any(), any());

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), mutatorCaptor.capture());
        mutatorCaptor.getValue().accept(ceremony);
        assertEquals(SEQUENCE, ceremony.getKelSequence());
        assertEquals(EVENT_SAID, ceremony.getKelEventSaid());
        assertEquals(WALLET_AID, ceremony.getAttesterAid());
        verify(correlator).markAndDelete(NOTIF_ID);
    }

    @Test
    void attestRetryWithNoLateRefFallsThroughToTheNormalSendFlow() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        stubHappyPath(ceremony);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        // The retry pre-check (2-arg) finds no late ref, so the flow re-sends and the main wait (3-arg,
        // stubbed by stubHappyPath) returns the fresh ref.
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any())).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verify(exchanges).createExchangeMessage(any(), eq("/remotesign/ixn/req"), anyMap(), anyMap(), eq(WALLET_AID),
                any(), any());
    }

    @Test
    void attestRetryWithAStaleNonAnchoringPrecheckRefFallsThroughToResendInsteadOfFailing() throws Exception {
        // Regression (the self-perpetuating retry loop): the retry pre-check claims a leftover unread ref
        // that does NOT anchor this ceremony's payload. It must NOT fail the ceremony (which would loop
        // forever on the same undeleted debris) — it falls through, re-sends, and the excluded main wait
        // completes against the genuine fresh reply.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        ceremony.setKelFloorSequence(FLOOR_SEQUENCE);
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        stubHappyPath(ceremony);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        // Pre-check (2-arg) claims a STALE ref whose explicit candidate names an event not in the KEL, so
        // locateAnchoringEvent returns empty for it (the KEL, stubbed by stubHappyPath, holds only the
        // genuine event). The main wait (3-arg, stubHappyPath) then returns the real anchoring ref.
        String staleNoteId = "0ASTALEPRECHECK000000000000000";
        Map<String, Object> staleRef = refExn(WALLET_AID, "99", "ENOTINKEL000000000000000000000000000");
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(staleNoteId, "ESTALEEXN000000000000000000000", staleRef)));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.ATTEST_ANCHORED, result.get().state());
        // Fell through to a fresh send rather than failing on the stale ref.
        verify(exchanges).sendFromEvents(any(), any(), any(), any(), any(), any());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
        // The genuine reply's notification is consumed; the stale pre-check ref is left untouched.
        verify(correlator).markAndDelete(NOTIF_ID);
        verify(correlator, never()).markAndDelete(staleNoteId);
    }

    // ==================== attest: waiting for the ref / verifying the anchor ====================

    @Test
    void attestTimeoutFailsWithKeriWalletTimeout() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST)).thenReturn(Map.of("d", PAYLOAD_SAID));
        stubHappySend();
        stubKeyStateSequence(FLOOR_SEQUENCE);
        stubGuardedUpdateSuccess(ceremony);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), any())).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.KERI_WALLET_TIMEOUT, result.getLeft().getTitle());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                KeriAttestationProblems.KERI_WALLET_TIMEOUT, "Timed out waiting for the wallet's remotesign ref.");
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void attestSealMismatchFailsWithAttestSealMismatchAndDigestDetail() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        stubHappyPath(ceremony);
        Object wrongSeal = List.of(Map.of("d", "EWRONGDIGEST"));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, wrongSeal)));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_SEAL_MISMATCH, result.getLeft().getTitle());
        assertEquals("No interaction (ixn) event strictly after the floor sequence and at or before the wallet's "
                + "current key state anchored payload SAID " + PAYLOAD_SAID + " (checked the wallet ref's explicit "
                + "candidate and a bounded scan).", result.getLeft().getDetail());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void attestFallsBackToKeyStateQueryWhenRefExnCarriesNoCandidate() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST)).thenReturn(Map.of("d", PAYLOAD_SAID));
        stubHappySend();
        stubGuardedUpdateSuccess(ceremony);
        when(keyStates.query(eq(WALLET_AID), any())).thenReturn(Map.of());
        Operation<Object> floorOp = Operation.builder().response(Map.of("s", FLOOR_SEQUENCE)).build();
        Operation<Object> currentOp = Operation.builder().response(Map.of("s", SEQUENCE)).build();
        when(operations.wait(any(), any(Operations.WaitOptions.class))).thenReturn(floorOp).thenReturn(currentOp);

        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, seal)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any())).thenReturn(true);
        when(ceremonyService.get(CEREMONY_ID, USER_ID)).thenReturn(Either.right(ceremonyView(CeremonyState.ATTEST_ANCHORED)));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        verify(keyStates, times(2)).query(eq(WALLET_AID), any());
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any());
        verify(correlator).markAndDelete(NOTIF_ID);
    }

    // --- floor sequence ---

    @Test
    void attestRejectsAnOldEventWithTheSameDigestBelowTheFloor() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST)).thenReturn(Map.of("d", PAYLOAD_SAID));
        stubHappySend();
        stubGuardedUpdateSuccess(ceremony);
        when(keyStates.query(eq(WALLET_AID), any())).thenReturn(Map.of());
        Operation<Object> floorOp = Operation.builder().response(Map.of("s", "5")).build();
        Operation<Object> currentOp = Operation.builder().response(Map.of("s", "6")).build();
        when(operations.wait(any(), any(Operations.WaitOptions.class))).thenReturn(floorOp).thenReturn(currentOp);

        // No explicit candidate on the ref exn — goes through the bounded-scan fallback.
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        // Below the floor (sequence 2 < floor 5) but carries the matching digest — must be rejected.
        Map<String, Object> oldEvent = kelEvent("ixn", "2", "EOLDEVENT00000000000000000000000000000", seal);
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(oldEvent));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_SEAL_MISMATCH, result.getLeft().getTitle());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void attestWithMismatchedExplicitCandidateDoesNotFallBackToScanningTheKel() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST)).thenReturn(Map.of("d", PAYLOAD_SAID));
        stubHappySend();
        stubKeyStateSequence("1");
        stubGuardedUpdateSuccess(ceremony);

        // Explicit candidate naming a SAID/sequence that doesn't exist in the KEL at all.
        Map<String, Object> refExn = refExn(WALLET_AID, "9", "ENONEXISTENTEVENT000000000000000000000");
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any(), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        // A different event that WOULD satisfy digest+floor if a scan ever considered it.
        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        Map<String, Object> otherEvent = kelEvent("ixn", SEQUENCE, EVENT_SAID, seal);
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(otherEvent));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_SEAL_MISMATCH, result.getLeft().getTitle());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
        // No fallback: the explicit-candidate branch never queries key state a second time to bound a scan.
        verify(keyStates, times(1)).query(eq(WALLET_AID), any());
    }

    @Test
    void attestWithNullFloorAndNoExplicitCandidateRefusesToScanAndFails() throws Exception {
        // A null floor (a pre-upgrade in-flight ceremony that reached ATTEST_REQUESTED before the
        // kel_floor_sequence column existed) must fail outright via the retry-with-late-ref path, never
        // falling back to the bounded scan.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        assertNull(ceremony.getKelFloorSequence());
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_SEAL_MISMATCH, result.getLeft().getTitle());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
        verifyNoInteractions(keyEvents, keyStates);
    }

    @Test
    void attestWithNullFloorStillFailsEvenWithAValidExplicitCandidate() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        assertNull(ceremony.getKelFloorSequence());
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        // A well-formed explicit candidate that WOULD resolve to a real, digest-matching KEL event if
        // ever looked up -- proving the rejection is unconditional on the floor alone.
        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_SEAL_MISMATCH, result.getLeft().getTitle());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
        verifyNoInteractions(keyEvents, keyStates);
    }

    @Test
    void attestStaleCompleteStepReturnsConflictAndNeverMarksTheNotificationAsClaimed() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        stubHappyPath(ceremony);
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any())).thenReturn(false);

        Either<ProblemDetail, CeremonyView> result = service.attest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
        verify(correlator, never()).markAndDelete(any());
    }
}
