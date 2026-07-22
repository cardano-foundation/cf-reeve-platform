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
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
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
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private CeremonyAsyncRunner asyncRunner;

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

        service = new KeriAttestService(keriClient, agentService, kedFactory, providerRegistry, correlator,
                ceremonyService, ceremonyRepository, identityLinkRepository, properties(), asyncRunner);
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
        // payloadSaid deliberately NOT preset here (unlike metadataDigest): resolveAndComplete tests
        // that need it as an input set it explicitly (mirroring kelFloorSequence's own per-test pattern
        // below), so a test that's actually supposed to prove startAttest WRITES payloadSaid from the
        // built KED can't pass vacuously against a value the fixture already carried.
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
     *  {@code queryLatestSequenceWithRetries} performs — used both by {@code startAttest} (F5 fix: the
     *  floor query, before sending) and by {@code resolveAndComplete}'s no-candidate bounded-scan
     *  fallback (the current-sequence query). One stub covers whichever of the two a given test's
     *  execution path reaches, since both call the exact same underlying method with the same argument
     *  shapes. */
    private void stubKeyStateSequence(String sequence) throws Exception {
        when(keyStates.query(eq(WALLET_AID), any())).thenReturn(Map.of());
        Operation<Object> op = Operation.builder().response(Map.of("s", sequence)).build();
        when(operations.wait(any(), any(Operations.WaitOptions.class))).thenReturn(op);
    }

    /** Stubs {@code ceremonyService.updateWaitingStepData} (F2 fix) to apply whichever mutator it is
     *  called with to {@code ceremony} — the same object identity {@code startAttest}'s caller holds —
     *  and report success. {@code startAttest} calls this twice per attempt (digest, then requestExnSaid)
     *  with the same {@code (ceremonyId, generation, ATTEST_REQUESTED)} arguments, so one stub serves
     *  both calls. */
    private void stubGuardedUpdateSuccess(KeriAttestationCeremonyEntity ceremony) {
        when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                any())).thenAnswer(inv -> {
                    Consumer<KeriAttestationCeremonyEntity> mutator = inv.getArgument(3);
                    mutator.accept(ceremony);
                    return true;
                });
    }

    // ==================== startAttest ====================

    @Test
    void startAttestHappyPathAuthorizesFreezesBuildsAndSendsThenDispatchesAsyncWait() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST)).thenReturn(Map.of("d", PAYLOAD_SAID));
        Serder builtExn = stubHappySend();
        stubKeyStateSequence(FLOOR_SEQUENCE);
        stubGuardedUpdateSuccess(ceremony);

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        assertEquals(METADATA_LABEL, ceremony.getMetadataLabel());
        assertEquals(NEW_REQUEST_EXN_SAID, ceremony.getRequestExnSaid());
        // design §4.4 rev 3: payloadSaid is extracted from the built KED's own "d" and persisted
        // alongside requestExnSaid — ceremony() deliberately never presets this field, so this proves
        // startAttest actually wrote it (not that it was already there).
        assertEquals(PAYLOAD_SAID, ceremony.getPayloadSaid());
        // F5 fix: digest, floor sequence, and requestExnSaid — three guarded updates per attempt now.
        assertEquals(FLOOR_SEQUENCE, ceremony.getKelFloorSequence());
        verify(ceremonyService, times(3)).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.ATTEST_REQUESTED), any());
        verify(ceremonyRepository, never()).save(any());
        verify(exchanges).createExchangeMessage(any(), eq("/remotesign/ixn/req"), eq(Map.of("d", PAYLOAD_SAID)),
                eq(Map.of()), eq(WALLET_AID), any(), any());
        verify(exchanges).sendFromEvents(AGENT_NAME, "remotesign", builtExn, List.of("sig1"), "atc1",
                List.of(WALLET_AID));
        verify(asyncRunner).awaitAnchor(CEREMONY_ID, GENERATION);
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void startAttestDispatchFailureFailsWithAttestRequestFailedInsteadOfPropagating() throws Exception {
        // If CeremonyAsyncRunner's executor rejects the dispatch (pool/queue saturated), the remotesign
        // request has already been sent to the wallet — the ceremony must still land in a terminal,
        // retryable state rather than letting the rejection exception escape startAttest uncaught.
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
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("pool saturated"))
                .when(asyncRunner).awaitAnchor(CEREMONY_ID, GENERATION);

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_REQUEST_FAILED, result.getLeft().getTitle());
        assertEquals(NEW_REQUEST_EXN_SAID, ceremony.getRequestExnSaid());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_REQUEST_FAILED), any());
    }

    @Test
    void startAttestBeginStepFailureReturnsLeftWithoutOtherInteractions() {
        ProblemDetail problem = KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE, "x");
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.left(problem));

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(problem, result.getLeft());
        verifyNoInteractions(identityLinkRepository, providerRegistry, correlator, asyncRunner);
    }

    @Test
    void startAttestWithNoIdentityLinkFailsWithIdentityNotLinked() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_NOT_LINKED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                KeriAttestationProblems.IDENTITY_NOT_LINKED, "User user-1 has no linked identity to attest with.");
        verifyNoInteractions(providerRegistry, asyncRunner);
    }

    @Test
    void startAttestWithNoProviderRegisteredFailsWithAttestRequestFailed() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.empty());

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_REQUEST_FAILED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_REQUEST_FAILED), any());
        verifyNoInteractions(asyncRunner);
    }

    @Test
    void startAttestProviderAuthorizeRejectionFailsWithTheProvidersProblem() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        ProblemDetail authProblem = KeriAttestationProblems.forbidden("not your document");
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.of(authProblem));

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(authProblem, result.getLeft());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                authProblem.getTitle(), authProblem.getDetail());
        verifyNoInteractions(asyncRunner);
    }

    @Test
    void startAttestStaleGuardedUpdateOnDigestWriteReturnsInvalidStateAndNeverSends() {
        // F2 fix: a concurrent retry/sweep transition beat this attempt's digest write — the remotesign
        // request must never be built/sent for a step that has already moved on.
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

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
        verifyNoInteractions(kedFactory, exchanges, identifiers, asyncRunner);
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void startAttestPrepareDigestFailureFailsWithTheProvidersProblemAndNeverPersistsDigest() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        ProblemDetail digestProblem = KeriAttestationProblems.unprocessable("FREEZE_FAILED", "could not freeze");
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID)).thenReturn(Either.left(digestProblem));

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(digestProblem, result.getLeft());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                digestProblem.getTitle(), digestProblem.getDetail());
        verify(ceremonyService, never()).updateWaitingStepData(any(), anyInt(), any(), any());
        verifyNoInteractions(ceremonyRepository, asyncRunner);
    }

    @Test
    void startAttestExchangeSendFailureFailsWithAttestRequestFailedButStillPersistsTheDigest() throws Exception {
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

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_REQUEST_FAILED, result.getLeft().getTitle());
        assertNull(ceremony.getRequestExnSaid());
        // F5 fix: digest and floor writes both reached the guarded update — building the exchange
        // message threw before the requestExnSaid write, so that third call never happens.
        assertEquals(FLOOR_SEQUENCE, ceremony.getKelFloorSequence());
        verify(ceremonyService, times(2)).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.ATTEST_REQUESTED), any());
        verify(ceremonyRepository, never()).save(any());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_REQUEST_FAILED), any());
        verifyNoInteractions(asyncRunner);
    }

    @Test
    void startAttestRetryWithLateArrivedCorrelatedRefCompletesWithoutResendingOrDispatching() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        // A floor must be set (F4 fix): a null floor now hard-fails resolveAndComplete outright, before
        // ever looking at the ref exn's candidate -- see the dedicated null-floor test for that path.
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

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verifyNoInteractions(providerRegistry, kedFactory, asyncRunner);
        verify(exchanges, never()).createExchangeMessage(any(), any(), any(), any(), any(), any(), any());
        verify(exchanges, never()).sendFromEvents(any(), any(), any(), any(), any(), any());

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), mutatorCaptor.capture());
        mutatorCaptor.getValue().accept(ceremony);
        assertEquals(SEQUENCE, ceremony.getKelSequence());
        assertEquals(EVENT_SAID, ceremony.getKelEventSaid());
        // F1 fix: the wallet AID the remotesign request was actually sent to and answered by is
        // persisted immutably alongside the KEL coordinates it anchored.
        assertEquals(WALLET_AID, ceremony.getAttesterAid());
        verify(correlator).markAndDelete(NOTIF_ID);
    }

    @Test
    void startAttestRetryWithNoLateRefFallsThroughToTheNormalSendFlow() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.empty());
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, METADATA_LABEL, DIGEST)).thenReturn(Map.of("d", PAYLOAD_SAID));
        stubHappySend();
        stubKeyStateSequence(FLOOR_SEQUENCE);
        stubGuardedUpdateSuccess(ceremony);

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verify(exchanges).createExchangeMessage(any(), eq("/remotesign/ixn/req"), anyMap(), anyMap(), eq(WALLET_AID),
                any(), any());
        verify(asyncRunner).awaitAnchor(CEREMONY_ID, GENERATION);
    }

    // ==================== awaitAnchor ====================

    @Test
    void awaitAnchorHappyPathVerifiesSealAndCompletesInOrder() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        // A floor must be set (F4 fix): a null floor now hard-fails resolveAndComplete outright, before
        // ever looking at the ref exn's candidate -- see the dedicated null-floor test for that path.
        ceremony.setKelFloorSequence(FLOOR_SEQUENCE);
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));

        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, seal)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any())).thenReturn(true);

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), mutatorCaptor.capture());
        mutatorCaptor.getValue().accept(ceremony);
        assertEquals(SEQUENCE, ceremony.getKelSequence());
        assertEquals(EVENT_SAID, ceremony.getKelEventSaid());
        // F1 fix: the wallet AID the remotesign request was actually sent to and answered by is
        // persisted immutably alongside the KEL coordinates it anchored.
        assertEquals(WALLET_AID, ceremony.getAttesterAid());

        InOrder inOrder = inOrder(ceremonyService, correlator);
        inOrder.verify(ceremonyService).completeStep(any(), anyInt(), any(), any(), any());
        inOrder.verify(correlator).markAndDelete(NOTIF_ID);
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void awaitAnchorStaleCompleteStepNeverMarksTheNotificationAsClaimed() throws Exception {
        // completeStep returning false means a retry's generation bump superseded this attempt's CAS —
        // a concurrent, winning attempt still needs this notification unread/undeleted, so a stale
        // attempt must not claim it despite otherwise verifying the seal correctly.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        // A floor must be set (F4 fix): a null floor now hard-fails resolveAndComplete outright, before
        // ever looking at the ref exn's candidate -- see the dedicated null-floor test for that path.
        ceremony.setKelFloorSequence(FLOOR_SEQUENCE);
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, seal)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any())).thenReturn(false);

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitAnchorCeremonyNotFoundNoOps() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.empty());

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verifyNoInteractions(identityLinkRepository, correlator, ceremonyService);
    }

    @Test
    void awaitAnchorWithNoIdentityLinkFailsWithIdentityNotLinked() {
        when(ceremonyRepository.findById(CEREMONY_ID))
                .thenReturn(Optional.of(ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.IDENTITY_NOT_LINKED), any());
        verifyNoInteractions(correlator);
    }

    @Test
    void awaitAnchorTimeoutFailsWithKeriWalletTimeout() {
        when(ceremonyRepository.findById(CEREMONY_ID))
                .thenReturn(Optional.of(ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.empty());

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                KeriAttestationProblems.KERI_WALLET_TIMEOUT, "Timed out waiting for the wallet's remotesign ref.");
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void awaitAnchorSealMismatchFailsWithAttestSealMismatchAndDigestDetail() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        // A floor must be set (F4 fix): a null floor now hard-fails resolveAndComplete outright, before
        // ever looking at the ref exn's candidate -- see the dedicated null-floor test for that path.
        ceremony.setKelFloorSequence(FLOOR_SEQUENCE);
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object wrongSeal = List.of(Map.of("d", "EWRONGDIGEST"));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, wrongSeal)));

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        // F5 fix: the ref exn here carries an explicit candidate (both sequence and SAID), so this goes
        // through the explicit-candidate verification path, not the old generic "seal doesn't contain
        // digest" message.
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH),
                eq("The wallet ref's explicit anchoring-event candidate did not verify (not found, sequence "
                        + "at or before the floor, or seal does not contain payload SAID " + PAYLOAD_SAID + ")."));
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitAnchorNoIxnEventOnKelFailsWithAttestSealMismatch() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        // A floor must be set (F5 residual fix): a null floor short-circuits straight to failure without
        // ever reaching the key-state query this test wants to exercise -- see the dedicated null-floor
        // test for that behavior.
        ceremony.setKelFloorSequence(FLOOR_SEQUENCE);
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of());
        // Candidate is empty (refExn carries neither s nor d), so the code falls back to the key-state
        // query — stub it to resolve with no usable sequence either, so locateEvent has nothing to
        // match against and the (empty) KEL correctly yields "no event found".
        Operation<Object> emptyOp = Operation.builder().response(Map.of()).build();
        when(keyStates.query(eq(WALLET_AID), any())).thenReturn(Map.of());
        when(operations.wait(any(), any(Operations.WaitOptions.class))).thenReturn(emptyOp);

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void awaitAnchorFallsBackToKeyStateQueryWhenRefExnCarriesNoCandidate() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        ceremony.setKelFloorSequence(FLOOR_SEQUENCE);
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        when(keyStates.query(eq(WALLET_AID), any())).thenReturn(Map.of());
        Operation<Object> op = Operation.builder().response(Map.of("s", SEQUENCE)).build();
        when(operations.wait(any(), any(Operations.WaitOptions.class))).thenReturn(op);

        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, seal)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any())).thenReturn(true);

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(keyStates, times(1)).query(eq(WALLET_AID), any());
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any());
        verify(correlator).markAndDelete(NOTIF_ID);
    }

    // --- F5 fix: floor sequence ---

    @Test
    void awaitAnchorRejectsAnOldEventWithTheSameDigestBelowTheFloor() throws Exception {
        // An old ixn event that happens to carry the same metadata digest (e.g. left over from a prior
        // attestation of identical content) must never satisfy a fresh request just because the digest
        // matches — it must also be at or after the floor sequence queried before this request was sent.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        ceremony.setKelFloorSequence("5");
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        // No explicit candidate on the ref exn — goes through the bounded-scan fallback.
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        stubKeyStateSequence("6");

        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        // Below the floor (sequence 2 < floor 5) but carries the matching digest — must be rejected.
        Map<String, Object> oldEvent = kelEvent("ixn", "2", "EOLDEVENT00000000000000000000000000000", seal);
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(oldEvent));

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitAnchorWithMismatchedExplicitCandidateDoesNotFallBackToScanningTheKel() throws Exception {
        // A mismatched explicit ref-derived candidate must fail immediately — no fallback shopping for
        // a different KEL event, even when one exists that WOULD satisfy the digest/floor if the code
        // ever looked at it.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        ceremony.setKelFloorSequence("1");
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        // Explicit candidate naming a SAID/sequence that doesn't exist in the KEL at all.
        Map<String, Object> refExn = refExn(WALLET_AID, "9", "ENONEXISTENTEVENT000000000000000000000");
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        // A different event that WOULD satisfy digest+floor if a scan ever considered it.
        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        Map<String, Object> otherEvent = kelEvent("ixn", SEQUENCE, EVENT_SAID, seal);
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(otherEvent));

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
        // No fallback: the explicit-candidate branch never queries key state to bound a scan.
        verifyNoInteractions(keyStates);
    }

    @Test
    void awaitAnchorRejectsAnEventAtExactlyTheFloorSequenceViaBoundedScan() throws Exception {
        // F5 residual fix: the floor comparison must be STRICTLY greater, not "at or after". The floor
        // is the sequence observed BEFORE the remotesign request was even sent, so the genuine anchoring
        // event is always strictly newer -- an event at exactly the floor already existed at snapshot
        // time and must never satisfy a fresh request, even with a matching digest.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        ceremony.setKelFloorSequence("5");
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        stubKeyStateSequence("6");

        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        // Exactly at the floor (sequence 5 == floor 5) with the matching digest — must still be rejected.
        Map<String, Object> atFloorEvent = kelEvent("ixn", "5", "EATFLOOREVENT0000000000000000000000000", seal);
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(atFloorEvent));

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitAnchorRejectsAnExplicitCandidateAtExactlyTheFloorSequence() throws Exception {
        // Same strict-greater rule, explicit-candidate path this time: the candidate resolves to a real
        // KEL event with a matching digest, but its sequence equals the floor exactly.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        ceremony.setKelFloorSequence("5");
        ceremony.setPayloadSaid(PAYLOAD_SAID);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, "5", EVENT_SAID);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        Object seal = List.of(Map.of("d", PAYLOAD_SAID));
        Map<String, Object> atFloorEvent = kelEvent("ixn", "5", EVENT_SAID, seal);
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(atFloorEvent));

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitAnchorWithNullFloorAndNoExplicitCandidateRefusesToScanAndFails() throws Exception {
        // F5 residual fix: a null floor (a pre-upgrade in-flight ceremony that reached ATTEST_REQUESTED
        // before the kel_floor_sequence column existed) must NOT be treated as "no lower bound" -- that
        // would silently reopen the unbounded-scan risk F5 closes for exactly the rows that most need
        // protecting. With no floor and no explicit ref-derived candidate, this must fail outright,
        // never falling back to the bounded scan (which, without a real floor, would need to run
        // unbounded).
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        assertNull(ceremony.getKelFloorSequence());
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
        // No fallback scan attempted: never fetches the KEL or queries key state.
        verifyNoInteractions(keyEvents, keyStates);
    }

    @Test
    void awaitAnchorWithNullFloorStillFailsEvenWithAValidExplicitCandidate() throws Exception {
        // M3 cross-review F4 fix: an earlier version of resolveAndComplete still accepted an explicit
        // ref-derived candidate with a null floor, verifying only its digest with no lower bound at all.
        // That is exactly the unbounded-scan risk F5 closes, reopened for a single event. A null floor
        // must now hard-fail this ceremony BEFORE the candidate is ever consulted or the KEL is ever
        // fetched -- there is no candidate-acceptance path left for a ceremony with no recorded floor.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        assertNull(ceremony.getKelFloorSequence());
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        // A well-formed explicit candidate that WOULD resolve to a real, digest-matching KEL event if
        // ever looked up -- proving the rejection is unconditional on the floor alone, not a side effect
        // of a missing/invalid candidate.
        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
        // No candidate lookup attempted at all: never fetches the KEL or queries key state.
        verifyNoInteractions(keyEvents, keyStates);
    }

    @Test
    void awaitAnchorPassesTheDispatchedGenerationNotACurrentCeremonyGenerationSoStaleCompletionsAreRejectedByCas() {
        // The ceremony ROW's own attemptGeneration has since moved to 9 (some other retry happened),
        // but this worker was dispatched with the OLD generation (GENERATION=2). completeStep/failStep
        // must receive exactly GENERATION, unmodified — CeremonyService's own CAS (tested separately)
        // is what actually rejects a stale generation; this proves the correct value is threaded
        // through rather than silently re-read from the (now-stale) in-memory entity.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        ceremony.setAttemptGeneration(9);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(correlator.awaitByRoute(eq(REMOTESIGN_REF_ROUTES), any()))
                .thenReturn(Optional.empty());

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.KERI_WALLET_TIMEOUT), any());
    }
}
