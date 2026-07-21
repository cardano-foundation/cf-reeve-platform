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
    private static final String OLD_REQUEST_EXN_SAID = "EOLDREQ0000000000000000000000000000000";
    private static final String NEW_REQUEST_EXN_SAID = "ENEWREQ0000000000000000000000000000000";
    private static final String REF_EXN_SAID = "EREFEXN0000000000000000000000000000000";
    private static final String NOTIF_ID = "0ANOTIFID0000000000000000000000000000";
    private static final String EVENT_SAID = "EEVENT00000000000000000000000000000000";
    private static final String SEQUENCE = "3";
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
        when(kedFactory.anchorRequestKed(WALLET_AID, DIGEST)).thenReturn(Map.of("d", DIGEST));
        Serder builtExn = stubHappySend();
        stubGuardedUpdateSuccess(ceremony);

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        assertEquals(METADATA_LABEL, ceremony.getMetadataLabel());
        assertEquals(NEW_REQUEST_EXN_SAID, ceremony.getRequestExnSaid());
        verify(ceremonyService, times(2)).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.ATTEST_REQUESTED), any());
        verify(ceremonyRepository, never()).save(any());
        verify(exchanges).createExchangeMessage(any(), eq("/remotesign/ixn/req"), eq(Map.of("d", DIGEST)),
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
        when(kedFactory.anchorRequestKed(WALLET_AID, DIGEST)).thenReturn(Map.of("d", DIGEST));
        stubHappySend();
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
        when(kedFactory.anchorRequestKed(WALLET_AID, DIGEST)).thenReturn(Map.of("d", DIGEST));
        when(identifiers.get(AGENT_NAME)).thenReturn(Optional.of(habState(AGENT_PREFIX)));
        when(exchanges.createExchangeMessage(any(), anyString(), anyMap(), anyMap(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("agent unreachable"));
        stubGuardedUpdateSuccess(ceremony);

        Either<ProblemDetail, Void> result = service.startAttest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.ATTEST_REQUEST_FAILED, result.getLeft().getTitle());
        assertNull(ceremony.getRequestExnSaid());
        // Only the digest write reached the guarded update — building the exchange message threw before
        // the requestExnSaid write, so that second call never happens.
        verify(ceremonyService, times(1)).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.ATTEST_REQUESTED), any());
        verify(ceremonyRepository, never()).save(any());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_REQUEST_FAILED), any());
        verifyNoInteractions(asyncRunner);
    }

    @Test
    void startAttestRetryWithLateArrivedCorrelatedRefCompletesWithoutResendingOrDispatching() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));

        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object seal = List.of(Map.of("d", DIGEST));
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
        verify(correlator).markAndDelete(NOTIF_ID);
    }

    @Test
    void startAttestRetryWithNoLateRefFallsThroughToTheNormalSendFlow() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.AUTH_BEGIN_CONFIRMED,
                CeremonyState.ATTEST_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
                .thenReturn(Optional.empty());
        when(providerRegistry.forType(TARGET_TYPE)).thenReturn(Optional.of(provider));
        when(provider.authorize(TARGET_ID, USER_ID)).thenReturn(Optional.empty());
        when(provider.prepareDigest(TARGET_ID, CEREMONY_ID))
                .thenReturn(Either.right(new AttestationDigest(DIGEST, METADATA_LABEL)));
        when(kedFactory.anchorRequestKed(WALLET_AID, DIGEST)).thenReturn(Map.of("d", DIGEST));
        stubHappySend();
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
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));

        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object seal = List.of(Map.of("d", DIGEST));
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
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object seal = List.of(Map.of("d", DIGEST));
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
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
                .thenReturn(Optional.empty());

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.ATTEST_REQUESTED,
                KeriAttestationProblems.KERI_WALLET_TIMEOUT, "Timed out waiting for the wallet's remotesign ref.");
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void awaitAnchorSealMismatchFailsWithAttestSealMismatchAndDigestDetail() throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID))
                .thenReturn(Optional.of(ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, SEQUENCE, EVENT_SAID);
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));
        Object wrongSeal = List.of(Map.of("d", "EWRONGDIGEST"));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, wrongSeal)));

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.ATTEST_SEAL_MISMATCH),
                eq("Anchoring event seal does not contain digest " + DIGEST + "."));
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitAnchorNoIxnEventOnKelFailsWithAttestSealMismatch() throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID))
                .thenReturn(Optional.of(ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
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
        when(ceremonyRepository.findById(CEREMONY_ID))
                .thenReturn(Optional.of(ceremony(CeremonyState.ATTEST_REQUESTED, OLD_REQUEST_EXN_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(WALLET_AID)));
        Map<String, Object> refExn = refExn(WALLET_AID, null, null);
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(NOTIF_ID, REF_EXN_SAID, refExn)));

        when(keyStates.query(eq(WALLET_AID), any())).thenReturn(Map.of());
        Operation<Object> op = Operation.builder().response(Map.of("s", SEQUENCE)).build();
        when(operations.wait(any(), any(Operations.WaitOptions.class))).thenReturn(op);

        Object seal = List.of(Map.of("d", DIGEST));
        when(keyEvents.get(WALLET_AID)).thenReturn(List.of(kelEvent("ixn", SEQUENCE, EVENT_SAID, seal)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any())).thenReturn(true);

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(keyStates, times(1)).query(eq(WALLET_AID), any());
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(CeremonyState.ATTEST_ANCHORED), any());
        verify(correlator).markAndDelete(NOTIF_ID);
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
        when(correlator.awaitCorrelated(eq(REMOTESIGN_REF_ROUTES), eq(WALLET_AID), eq(OLD_REQUEST_EXN_SAID), any()))
                .thenReturn(Optional.empty());

        service.awaitAnchor(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.ATTEST_REQUESTED),
                eq(KeriAttestationProblems.KERI_WALLET_TIMEOUT), any());
    }
}
