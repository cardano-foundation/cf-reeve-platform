package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator.ValidatedCredential;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;
import org.cardanofoundation.signify.app.Exchanging.ExchangeMessageResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.credentialing.credentials.Credentials;
import org.cardanofoundation.signify.app.credentialing.ipex.Ipex;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexApplyArgs;
import org.cardanofoundation.signify.cesr.Serder;

@ExtendWith(MockitoExtension.class)
class KeriCredentialServiceTest {

    private static final String CEREMONY_ID = "cer-1";
    private static final String USER_ID = "user-1";
    private static final int GENERATION = 2;
    private static final String LINKED_AID = "ELINKEDAID000000000000000000000000000";
    private static final String OTHER_AID = "EOTHERAID000000000000000000000000000A";
    private static final String AGENT_NAME = "keriAttestationAgent";
    private static final String AGENT_OOBI = "https://agent.example.org/oobi/EAGENT/agent/EAGENT";
    private static final String SCHEMA_SAID = "ESCHEMA00000000000000000000000000000000";
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
    private static final List<String> OFFER_ROUTES = List.of("/exn/ipex/offer");
    private static final List<String> GRANT_ROUTES = List.of("/exn/ipex/grant");

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Ipex ipex;
    @Mock
    private Credentials credentials;
    @Mock
    private KeriAgentService agentService;
    @Mock
    private KeriNotificationCorrelator correlator;
    @Mock
    private CredentialChainValidator validator;
    @Mock
    private CeremonyService ceremonyService;
    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private CeremonyAsyncRunner asyncRunner;

    private KeriCredentialService service;

    @BeforeEach
    void setUp() {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.ipex()).thenReturn(ipex);
        lenient().when(client.credentials()).thenReturn(credentials);
        lenient().when(agentService.agentName()).thenReturn(AGENT_NAME);
        lenient().when(agentService.agentOobi()).thenReturn(AGENT_OOBI);
        // F8 fix: awaitPresentation's normal (non-resumed) flow persists the AGREE_SENT phase transition
        // via a guarded update after sending the agree, before waiting for the grant. Default this to
        // succeed (without applying the mutator) so every test not specifically about that persist can
        // still reach the rest of the flow; tests that DO care override this with a more specific stub
        // defined after this one, which Mockito resolves in preference to this default.
        lenient().when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), any())).thenReturn(true);

        service = new KeriCredentialService(keriClient, agentService, correlator, validator, ceremonyService,
                ceremonyRepository, identityLinkRepository, properties(), asyncRunner);
    }

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier",
                new KeriAttestationProperties.CredentialPolicy(List.of(SCHEMA_SAID), List.of(ROOT_AID)),
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

    private static Serder serderWithSaid(String said) {
        Serder serder = mock(Serder.class, RETURNS_DEFAULTS);
        lenient().when(serder.getKed()).thenReturn(Map.of("d", said));
        return serder;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> grantExn(String senderAid, String credentialSaid) {
        return Map.of("i", senderAid, "e", Map.of("acdc", Map.of("d", credentialSaid)));
    }

    // ==================== startPresentation ====================

    /** Stubs {@code ceremonyService.updateWaitingStepData} (F2 fix) to apply the mutator to
     *  {@code ceremony} — the same object identity {@code startPresentation}'s caller holds — and report
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

    @Test
    void startPresentationBuildsAndSendsApplyAndPersistsRequestExnSaidBeforeSubmit() throws Exception {
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder exn = serderWithSaid(APPLY_SAID);
        when(ipex.apply(any())).thenReturn(new ExchangeMessageResult(exn, List.of("sig1"), "atc1"));

        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubGuardedUpdateSuccess(ceremony);
        Either<ProblemDetail, Void> result = service.startPresentation(ceremony);

        assertTrue(result.isRight());
        assertEquals(APPLY_SAID, ceremony.getRequestExnSaid());
        verify(ceremonyService).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), any());
        verify(ceremonyRepository, never()).save(any());

        ArgumentCaptor<IpexApplyArgs> captor = ArgumentCaptor.forClass(IpexApplyArgs.class);
        verify(ipex).apply(captor.capture());
        IpexApplyArgs args = captor.getValue();
        assertEquals(AGENT_NAME, args.getSenderName());
        assertEquals(LINKED_AID, args.getRecipient());
        assertEquals("", args.getMessage());
        assertEquals(SCHEMA_SAID, args.getSchemaSaid());
        assertEquals(Map.of("oobiUrl", AGENT_OOBI), args.getAttributes());

        verify(ipex).submitApply(AGENT_NAME, exn, List.of("sig1"), List.of(LINKED_AID));
    }

    @Test
    void startPresentationWithNoIdentityLinkFailsWithIdentityNotLinkedAndNeverTouchesIpex() {
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, Void> result = service.startPresentation(ceremony(null));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_NOT_LINKED, result.getLeft().getTitle());
        verifyNoInteractions(ipex);
        verifyNoInteractions(ceremonyRepository, ceremonyService);
    }

    @Test
    void startPresentationApplyBuildFailureReturnsLeftWithoutPersistingRequestExnSaid() throws Exception {
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(ipex.apply(any())).thenThrow(new IOException("agent unreachable"));

        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        Either<ProblemDetail, Void> result = service.startPresentation(ceremony);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, result.getLeft().getTitle());
        assertNull(ceremony.getRequestExnSaid());
        verifyNoInteractions(ceremonyRepository, ceremonyService);
    }

    @Test
    void startPresentationSubmitApplyFailureStillPersistsRequestExnSaidBeforeReturningLeft() throws Exception {
        // Demonstrates the persist-before-send ordering: the SAID is computed locally (deterministic
        // from the built, not-yet-sent exn) and saved before the network call, so a transport failure
        // in the send itself does not lose track of what was about to go out.
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder exn = serderWithSaid(APPLY_SAID);
        when(ipex.apply(any())).thenReturn(new ExchangeMessageResult(exn, List.of("sig1"), "atc1"));
        when(ipex.submitApply(any(), any(), any(), any())).thenThrow(new IOException("network blip"));

        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        stubGuardedUpdateSuccess(ceremony);
        Either<ProblemDetail, Void> result = service.startPresentation(ceremony);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, result.getLeft().getTitle());
        assertEquals(APPLY_SAID, ceremony.getRequestExnSaid());
        verify(ceremonyService).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), any());
    }

    @Test
    void startPresentationStaleGuardedUpdateReturnsInvalidStateAndNeverSends() throws Exception {
        // F2 fix: a concurrent retry/sweep transition beat this attempt's guarded update — the apply
        // must never be sent for a step that has already moved on.
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder exn = serderWithSaid(APPLY_SAID);
        when(ipex.apply(any())).thenReturn(new ExchangeMessageResult(exn, List.of("sig1"), "atc1"));
        when(ceremonyService.updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), any())).thenReturn(false);

        Either<ProblemDetail, Void> result = service.startPresentation(ceremony(null));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
        verify(ipex, never()).submitApply(any(), any(), any(), any());
    }

    // ==================== startCredentialRequest ====================

    @Test
    void startCredentialRequestHappyPathSendsApplyThenDispatchesAsyncWait() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder exn = serderWithSaid(APPLY_SAID);
        when(ipex.apply(any())).thenReturn(new ExchangeMessageResult(exn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);

        Either<ProblemDetail, Void> result = service.startCredentialRequest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isRight());
        verify(ipex).submitApply(AGENT_NAME, exn, List.of("sig1"), List.of(LINKED_AID));
        verify(asyncRunner).awaitPresentation(CEREMONY_ID, GENERATION);
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void startCredentialRequestBeginStepFailureReturnsLeftWithoutOtherInteractions() {
        ProblemDetail problem = KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE, "x");
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.left(problem));

        Either<ProblemDetail, Void> result = service.startCredentialRequest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(problem, result.getLeft());
        verifyNoInteractions(identityLinkRepository, correlator, ipex, asyncRunner);
    }

    @Test
    void startCredentialRequestWithNoIdentityLinkFailsWithIdentityNotLinked() {
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony(null)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, Void> result = service.startCredentialRequest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_NOT_LINKED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.IDENTITY_NOT_LINKED,
                "User user-1 has no linked identity to request a credential presentation from.");
        verifyNoInteractions(correlator, ipex, asyncRunner);
    }

    @Test
    void startCredentialRequestApplyBuildFailureFailsWithCredentialRequestFailed() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(ipex.apply(any())).thenThrow(new IOException("agent unreachable"));

        Either<ProblemDetail, Void> result = service.startCredentialRequest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(asyncRunner);
    }

    @Test
    void startCredentialRequestDispatchFailureFailsWithCredentialRequestFailedInsteadOfPropagating() throws Exception {
        // If CeremonyAsyncRunner's executor rejects the dispatch (pool/queue saturated), the apply has
        // already been sent to the wallet — the ceremony must still land in a terminal, retryable state
        // rather than letting the rejection exception escape startCredentialRequest uncaught.
        KeriAttestationCeremonyEntity ceremony = ceremony(null);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, false)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        Serder exn = serderWithSaid(APPLY_SAID);
        when(ipex.apply(any())).thenReturn(new ExchangeMessageResult(exn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("pool saturated"))
                .when(asyncRunner).awaitPresentation(CEREMONY_ID, GENERATION);

        Either<ProblemDetail, Void> result = service.startCredentialRequest(CEREMONY_ID, USER_ID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, result.getLeft().getTitle());
        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
    }

    @Test
    void startCredentialRequestRetryWithLateArrivedOfferSkipsResendAndDispatchesAsyncWait() throws Exception {
        KeriAttestationCeremonyEntity ceremony = ceremony(APPLY_SAID);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));

        Either<ProblemDetail, Void> result = service.startCredentialRequest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verifyNoInteractions(ipex);
        verify(asyncRunner).awaitPresentation(CEREMONY_ID, GENERATION);
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void startCredentialRequestRetryWithNoLateOfferFallsThroughToTheNormalSendFlow() throws Exception {
        String oldApplySaid = "EOLDAPPLYSAID000000000000000000000000000";
        KeriAttestationCeremonyEntity ceremony = ceremony(oldApplySaid);
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(oldApplySaid), any()))
                .thenReturn(Optional.empty());
        Serder exn = serderWithSaid(APPLY_SAID);
        when(ipex.apply(any())).thenReturn(new ExchangeMessageResult(exn, List.of("sig1"), "atc1"));
        stubGuardedUpdateSuccess(ceremony);

        Either<ProblemDetail, Void> result = service.startCredentialRequest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verify(ipex).submitApply(AGENT_NAME, exn, List.of("sig1"), List.of(LINKED_AID));
        verify(asyncRunner).awaitPresentation(CEREMONY_ID, GENERATION);
    }

    @Test
    void startCredentialRequestRetryWithAgreeSentPhaseNeverResendsAndDispatchesAsyncWait() {
        // F8 fix: a retry resuming at AGREE_SENT already has BOTH the apply and agree sent by a
        // previous attempt -- must never re-send either, and must never re-run the offer precheck.
        KeriAttestationCeremonyEntity ceremony = ceremony(AGREE_SAID);
        ceremony.setStepPhase("AGREE_SENT");
        when(ceremonyService.beginStep(CEREMONY_ID, USER_ID, CeremonyState.OOBI_RESOLVED,
                CeremonyState.CREDENTIAL_REQUESTED, true)).thenReturn(Either.right(ceremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));

        Either<ProblemDetail, Void> result = service.startCredentialRequest(CEREMONY_ID, USER_ID, true);

        assertTrue(result.isRight());
        verify(asyncRunner).awaitPresentation(CEREMONY_ID, GENERATION);
        verifyNoInteractions(correlator, ipex);
    }

    // ==================== awaitPresentation ====================

    @Test
    void awaitPresentationHappyPathWalksOfferAgreeGrantAdmitValidatesAndCompletesTheStep() throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        KeriIdentityLinkEntity initialLink = link(LINKED_AID);
        KeriIdentityLinkEntity freshLink = link(LINKED_AID);
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(initialLink));
        // F3 fix: persistCredentialIfIdentityStillCurrent's re-fetch is row-locked, a different mocked
        // method than awaitPresentation's own initial (plain) lookup above.
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(freshLink));

        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));

        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));

        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));

        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));

        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, List.of(SCHEMA_SAID), List.of(ROOT_AID)))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(true);

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ipex).submitAgree(AGENT_NAME, agreeExn, List.of("sig2"), List.of(LINKED_AID));
        verify(ipex).submitAdmit(AGENT_NAME, admitExn, List.of("sig3"), "atc3", List.of(LINKED_AID));
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());

        // F8 fix: sending the agree persists the AGREE_SENT phase transition (and overwrites
        // requestExnSaid with the agree's SAID) via a guarded update, separate from completeStep's own.
        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> phaseMutatorCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).updateWaitingStepData(eq(CEREMONY_ID), eq(GENERATION),
                eq(CeremonyState.CREDENTIAL_REQUESTED), phaseMutatorCaptor.capture());
        KeriAttestationCeremonyEntity phaseScratch = ceremony(APPLY_SAID);
        phaseMutatorCaptor.getValue().accept(phaseScratch);
        assertEquals("AGREE_SENT", phaseScratch.getStepPhase());
        assertEquals(AGREE_SAID, phaseScratch.getRequestExnSaid());

        // F5 fix: the link write happens inside completeStep's mutator (mirrors
        // KeriAuthBeginService#persistAuthBeginIfIdentityStillCurrent) — capture it and run it the way
        // CeremonyService really would, then assert its effect. It must not have run as a side effect of
        // the mocked completeStep call itself.
        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), mutatorCaptor.capture());
        verify(identityLinkRepository, never()).save(any());

        KeriAttestationCeremonyEntity completeScratch = ceremony(APPLY_SAID);
        completeScratch.setStepPhase("AGREE_SENT");
        mutatorCaptor.getValue().accept(completeScratch);

        assertEquals(CREDENTIAL_SAID, freshLink.getCredentialSaid());
        assertEquals(RESULT_SCHEMA_SAID, freshLink.getCredentialSchemaSaid());
        verify(identityLinkRepository).save(freshLink);
        // F8 fix: the step is done — no phase marker should linger on the row.
        assertNull(completeScratch.getStepPhase());

        // Notifications are only claimed (marked+deleted) once completeStep reports success.
        verify(correlator).markAndDelete(OFFER_NOTIF_ID);
        verify(correlator).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void awaitPresentationResumingAtAgreeSentSkipsOfferAndAgreeAndCorrelatesGrantOnThePersistedAgreeSaid()
            throws Exception {
        // F8 fix: a retry resuming at AGREE_SENT already sent both apply and agree in a previous
        // attempt -- requestExnSaid holds the agree's SAID. This attempt must never wait for an offer or
        // send a second agree; it correlates the grant directly on that persisted SAID.
        KeriAttestationCeremonyEntity resumedCeremony = ceremony(AGREE_SAID);
        resumedCeremony.setStepPhase("AGREE_SENT");
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(resumedCeremony));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));

        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, List.of(SCHEMA_SAID), List.of(ROOT_AID)))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(true);

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        // agree/submitAgree are never called (no re-send); admit() below still happens normally, once
        // the correlated grant arrives.
        verify(ipex, never()).agree(any());
        verify(ipex, never()).submitAgree(any(), any(), any(), any());
        verify(ipex).admit(any());
        verify(correlator, never()).awaitCorrelated(eq(OFFER_ROUTES), any(), any(), any());
        verify(correlator).awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());

        // No offer notification was ever claimed (this attempt never fetched one).
        verify(correlator, never()).markAndDelete(OFFER_NOTIF_ID);
        verify(correlator).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void awaitPresentationCeremonyNotFoundNoOps() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.empty());

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verifyNoInteractions(identityLinkRepository, correlator, ceremonyService, ipex);
    }

    @Test
    void awaitPresentationWithNoIdentityLinkFailsWithIdentityNotLinked() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.IDENTITY_NOT_LINKED), any());
        verifyNoInteractions(correlator, ipex);
    }

    @Test
    void awaitPresentationOfferTimeoutFailsWithKeriWalletTimeoutAndNeverBuildsAgree() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.empty());

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.KERI_WALLET_TIMEOUT, "Timed out waiting for /exn/ipex/offer.");
        verifyNoInteractions(ipex);
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitPresentationGrantTimeoutFailsWithKeriWalletTimeoutAndNeverAdmits() throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.empty());

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.KERI_WALLET_TIMEOUT, "Timed out waiting for /exn/ipex/grant.");
        verify(ipex, never()).admit(any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitPresentationAgreeSendFailureFailsWithCredentialRequestFailed() throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        when(ipex.agree(any())).thenThrow(new IOException("agent unreachable"));

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(credentials);
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitPresentationMissingAcdcInGrantExnFailsWithCredentialRequestFailed() throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID, Map.of("i", LINKED_AID))));

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verify(ipex, never()).admit(any());
    }

    @Test
    void awaitPresentationAdmitSendFailureFailsWithCredentialRequestFailed() throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        when(ipex.admit(any())).thenThrow(new IOException("agent unreachable"));

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(credentials);
    }

    @Test
    void awaitPresentationCredentialNotFoundAfterAdmitFailsWithCredentialRequestFailed() throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.empty());

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED), any());
        verifyNoInteractions(validator);
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitPresentationValidatorRejectionFailsWithCredentialRejectedAndDoesNotPersistOrMarkNotifications()
            throws Exception {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));

        ProblemDetail rejection = KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REJECTED,
                "issuee mismatch");
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, List.of(SCHEMA_SAID), List.of(ROOT_AID)))
                .thenReturn(Either.left(rejection));

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(CEREMONY_ID, GENERATION, CeremonyState.CREDENTIAL_REQUESTED,
                KeriAttestationProblems.CREDENTIAL_REJECTED, "issuee mismatch");
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        // Only one identityLinkRepository.findById call (the initial linkedAid lookup) — rejection
        // returns before completeStep is ever called, so its link-writing mutator never runs and
        // nothing is ever persisted to the link.
        verify(identityLinkRepository, never()).save(any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitPresentationValidatorThrowingFailsTheStepInsteadOfPropagatingAndLeavingTheCeremonyStuck()
            throws Exception {
        // The validator call is the one external-boundary call in this method without a checked-
        // exception contract — easy to forget it can still throw on a malformed/hostile chain. An
        // escaped RuntimeException here must not propagate out of an unsupervised async worker and
        // leave the ceremony stuck at CREDENTIAL_REQUESTED forever.
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, List.of(SCHEMA_SAID), List.of(ROOT_AID)))
                .thenThrow(new RuntimeException("null issuer somewhere in the chain"));

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REJECTED), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(identityLinkRepository, never()).save(any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitPresentationValidatedLeafSaidMismatchingFetchedCredentialSaidIsRejected() throws Exception {
        // Defense-in-depth: the validator finds its leaf independently (by issuee match), so its result
        // must be cross-checked against the SAID this whole round trip actually fetched and admitted.
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));
        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        String someOtherCredentialSaid = "ESOMEOTHERCREDSAID00000000000000000000";
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, List.of(SCHEMA_SAID), List.of(ROOT_AID)))
                .thenReturn(Either.right(new ValidatedCredential(someOtherCredentialSaid, RESULT_SCHEMA_SAID)));

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(KeriAttestationProblems.CREDENTIAL_REJECTED), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(identityLinkRepository, never()).save(any());
        verify(correlator, never()).markAndDelete(any());
    }

    @Test
    void awaitPresentationRelinkMidFlightCompletesTheStepButSkipsPersistingTheStaleCredential() throws Exception {
        // F5 fix: the relink check now lives inside completeStep's mutator (mirrors
        // KeriAuthBeginService's identical mid-flight-relink skip), not as an earlier explicit failStep
        // guard — the ceremony's own (state, attemptGeneration) CAS has no way to know about a relink,
        // so the step still completes; the write to the (now stale) link is what's skipped.
        // CeremonyService#validateAndConsume's own bindingVersion check is the final safety net.
        KeriAttestationCeremonyEntity ceremonyEntity = ceremony(APPLY_SAID);
        ceremonyEntity.setBindingVersion(1);
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremonyEntity));
        KeriIdentityLinkEntity initialLink = link(LINKED_AID);
        initialLink.setBindingVersion(1);
        KeriIdentityLinkEntity relinkedLink = link(OTHER_AID);
        relinkedLink.setBindingVersion(2);
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(initialLink));
        // F3 fix: persistCredentialIfIdentityStillCurrent's re-fetch is row-locked, a different mocked
        // method than awaitPresentation's own initial (plain) lookup above.
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(relinkedLink));

        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, List.of(SCHEMA_SAID), List.of(ROOT_AID)))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(true);

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutatorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), mutatorCaptor.capture());

        mutatorCaptor.getValue().accept(ceremonyEntity);

        assertNull(relinkedLink.getCredentialSaid());
        verify(identityLinkRepository, never()).save(any());
        verify(correlator).markAndDelete(OFFER_NOTIF_ID);
        verify(correlator).markAndDelete(GRANT_NOTIF_ID);
    }

    @Test
    void awaitPresentationStaleCompleteStepNeverMarksNotificationsAsClaimedOrWritesTheLink() throws Exception {
        // completeStep returning false means a retry's generation bump superseded this attempt's CAS —
        // the winning attempt's own correlator wait still needs these notifications unread/undeleted, so
        // this attempt must not claim them despite having otherwise "succeeded" up to this point. F5
        // fix: since the link write now happens inside completeStep's own mutator, a stale (false) CAS
        // means the mutator never ran in the first place, so the link must never be written either.
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(APPLY_SAID)));
        when(identityLinkRepository.findById(USER_ID)).thenReturn(Optional.of(link(LINKED_AID)));

        when(correlator.awaitCorrelated(eq(OFFER_ROUTES), eq(LINKED_AID), eq(APPLY_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(OFFER_NOTIF_ID, OFFER_SAID, Map.of())));
        Serder agreeExn = serderWithSaid(AGREE_SAID);
        when(ipex.agree(any())).thenReturn(new ExchangeMessageResult(agreeExn, List.of("sig2"), "atc2"));
        when(correlator.awaitCorrelated(eq(GRANT_ROUTES), eq(LINKED_AID), eq(AGREE_SAID), any()))
                .thenReturn(Optional.of(new CorrelatedNotification(GRANT_NOTIF_ID, GRANT_SAID,
                        grantExn(LINKED_AID, CREDENTIAL_SAID))));
        Serder admitExn = serderWithSaid(ADMIT_SAID);
        when(ipex.admit(any())).thenReturn(new ExchangeMessageResult(admitExn, List.of("sig3"), "atc3"));
        when(credentials.get(CREDENTIAL_SAID)).thenReturn(Optional.of("FULL-CESR-STREAM"));
        when(validator.validate("FULL-CESR-STREAM", LINKED_AID, List.of(SCHEMA_SAID), List.of(ROOT_AID)))
                .thenReturn(Either.right(new ValidatedCredential(CREDENTIAL_SAID, RESULT_SCHEMA_SAID)));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.CREDENTIAL_REQUESTED),
                eq(CeremonyState.CREDENTIAL_RECEIVED), any())).thenReturn(false);

        service.awaitPresentation(CEREMONY_ID, GENERATION);

        verify(correlator, never()).markAndDelete(any());
        verify(identityLinkRepository, never()).save(any());
    }
}
