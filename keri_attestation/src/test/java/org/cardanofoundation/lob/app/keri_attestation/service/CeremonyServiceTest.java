package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;

@ExtendWith(MockitoExtension.class)
class CeremonyServiceTest {

    private static final Set<CeremonyState> TERMINAL =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);
    private static final String USER = "user-1";
    private static final String CEREMONY_ID = "cer-1";

    private final KeriAttestationProperties properties = new KeriAttestationProperties(
            true, null, "identifier", null,
            Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT1.5S"),
            3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")));

    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;

    private CeremonyService service;

    @BeforeEach
    void setUp() {
        service = new CeremonyService(ceremonyRepository, identityLinkRepository, properties);
        // Not every test needs the active-ceremony count stubbed (only create() reads it); STRICT_STUBS
        // would otherwise flag it as unnecessary in every other test.
        lenient().when(ceremonyRepository.countByUserIdAndStateNotIn(anyString(), any())).thenReturn(0L);
    }

    private KeriAttestationCeremonyEntity ceremony(CeremonyState state) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(CEREMONY_ID);
        ceremony.setUserId(USER);
        ceremony.setBindingVersion(1);
        ceremony.setTargetType("DOCUMENT");
        ceremony.setTargetId("doc-1");
        ceremony.setState(state);
        ceremony.setAttemptGeneration(0);
        ceremony.setUpdatedAt(LocalDateTime.now());
        ceremony.setExpiresAt(LocalDateTime.now().plusHours(1));
        return ceremony;
    }

    private KeriIdentityLinkEntity link(int bindingVersion, String aid, String credentialSaid, String authBeginTxHash) {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER);
        link.setBindingVersion(bindingVersion);
        link.setAid(aid);
        link.setCredentialSaid(credentialSaid);
        link.setAuthBeginTxHash(authBeginTxHash);
        return link;
    }

    // --- create: fast-forward from the identity link (design §4.2) ---

    @Test
    void createWithNoLinkStartsAtCreatedWithAllStepsRequired() {
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.empty());
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, CeremonyView> result = service.create(USER, "DOCUMENT", "doc-1");

        assertTrue(result.isRight());
        CeremonyView view = result.get();
        assertEquals(CeremonyState.CREATED, view.state());
        assertEquals(true, view.requiredSteps().oobi());
        assertEquals(true, view.requiredSteps().credential());
        assertEquals(true, view.requiredSteps().authBegin());
    }

    @Test
    void createWithLinkHavingAidButNoCredentialStartsAtOobiResolved() {
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", null, null)));
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CeremonyView view = service.create(USER, "DOCUMENT", "doc-1").get();

        assertEquals(CeremonyState.OOBI_RESOLVED, view.state());
        assertFalse(view.requiredSteps().oobi());
        assertTrue(view.requiredSteps().credential());
        assertTrue(view.requiredSteps().authBegin());
    }

    @Test
    void createWithLinkHavingCredentialButNoAuthBeginStartsAtCredentialReceived() {
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", "Ecred", null)));
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CeremonyView view = service.create(USER, "DOCUMENT", "doc-1").get();

        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, view.state());
        assertFalse(view.requiredSteps().oobi());
        assertFalse(view.requiredSteps().credential());
        assertTrue(view.requiredSteps().authBegin());
    }

    @Test
    void createWithFullyConfirmedLinkStartsAtAuthBeginConfirmedWithOnlyAttestRemaining() {
        when(identityLinkRepository.findById(USER))
                .thenReturn(Optional.of(link(1, "Eaid", "Ecred", "a".repeat(64))));
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CeremonyView view = service.create(USER, "DOCUMENT", "doc-1").get();

        assertEquals(CeremonyState.AUTH_BEGIN_CONFIRMED, view.state());
        assertFalse(view.requiredSteps().oobi());
        assertFalse(view.requiredSteps().credential());
        assertFalse(view.requiredSteps().authBegin());
        assertEquals("a".repeat(64), view.authBeginTxHash());
    }

    @Test
    void createRejectsWhenActiveCeremonyLimitReached() {
        when(ceremonyRepository.countByUserIdAndStateNotIn(USER, TERMINAL)).thenReturn(3L);

        Either<ProblemDetail, CeremonyView> result = service.create(USER, "DOCUMENT", "doc-1");

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_LIMIT_REACHED, result.getLeft().getTitle());
    }

    // --- get: ownership + lazy expiry ---

    @Test
    void getReturnsNotFoundWhenCeremonyDoesNotExist() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, USER);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_NOT_FOUND, result.getLeft().getTitle());
    }

    @Test
    void getByNonOwnerIsForbidden() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony(CeremonyState.CREATED)));

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, "someone-else");

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_FORBIDDEN, result.getLeft().getTitle());
    }

    @Test
    void getOnAnOverdueCeremonyReportsAndPersistsExpired() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED);
        ceremony.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, USER);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.EXPIRED, result.get().state());
        assertEquals(CeremonyState.EXPIRED, ceremony.getState());
        verify(ceremonyRepository).save(ceremony);
    }

    // --- beginStep: CAS + cooldown ---

    @Test
    void beginStepInWrongStateIsInvalidState() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony(CeremonyState.CREATED)));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> result = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_REQUESTED, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
    }

    @Test
    void beginStepNonRetryMovesFromExpectedToWaitingState() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony(CeremonyState.OOBI_RESOLVED)));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> result = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_REQUESTED, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, result.get().getState());
        assertEquals(0, result.get().getAttemptGeneration());
    }

    @Test
    void retryBeforeCooldownIsRejected() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        ceremony.setUpdatedAt(LocalDateTime.now());
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> result = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_REQUESTED, true);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.STEP_COOLDOWN, result.getLeft().getTitle());
        assertEquals(0, ceremony.getAttemptGeneration());
    }

    @Test
    void retryAfterCooldownBumpsGeneration() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        ceremony.setUpdatedAt(LocalDateTime.now().minusSeconds(30));
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> result = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_REQUESTED, true);

        assertTrue(result.isRight());
        assertEquals(1, result.get().getAttemptGeneration());
        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, result.get().getState());
    }

    @Test
    void retryFromAStateOtherThanTheWaitingStateIsInvalidState() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony(CeremonyState.OOBI_RESOLVED)));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> result = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_REQUESTED, true);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
    }

    @Test
    void beginStepOnAnOverdueCeremonyReturnsExpired() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREATED);
        ceremony.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> result = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.CREATED, CeremonyState.OOBI_RESOLVED, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_EXPIRED, result.getLeft().getTitle());
        assertEquals(CeremonyState.EXPIRED, ceremony.getState());
    }

    // --- completeStep / failStep: CAS on (attemptGeneration, state) ---

    @SuppressWarnings("unchecked")
    @Test
    void completeStepWithStaleGenerationMutatesNothing() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        ceremony.setAttemptGeneration(1);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        Consumer<KeriAttestationCeremonyEntity> mutator = org.mockito.Mockito.mock(Consumer.class);

        service.completeStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED, CeremonyState.CREDENTIAL_RECEIVED, mutator);

        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, ceremony.getState());
        assertEquals(1, ceremony.getAttemptGeneration());
        verify(mutator, never()).accept(any());
        verify(ceremonyRepository, never()).save(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void completeStepWithWrongCurrentStateMutatesNothing() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_RECEIVED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        Consumer<KeriAttestationCeremonyEntity> mutator = org.mockito.Mockito.mock(Consumer.class);

        service.completeStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED, CeremonyState.CREDENTIAL_RECEIVED, mutator);

        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, ceremony.getState());
        verify(mutator, never()).accept(any());
    }

    @Test
    void completeStepHappyPathAppliesMutatorAndMovesState() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        service.completeStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED, CeremonyState.CREDENTIAL_RECEIVED,
                c -> c.setKelSequence("5"));

        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, ceremony.getState());
        assertEquals("5", ceremony.getKelSequence());
        verify(ceremonyRepository, times(1)).save(ceremony);
    }

    @Test
    void completeStepOnUnknownCeremonyIsANoOp() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.empty());

        service.completeStep(CEREMONY_ID, 0, CeremonyState.CREATED, CeremonyState.OOBI_RESOLVED, c -> { });

        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void failStepWithStaleGenerationMutatesNothing() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        ceremony.setAttemptGeneration(1);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        service.failStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED, "SOME_TITLE", "detail");

        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, ceremony.getState());
        assertEquals(null, ceremony.getErrorTitle());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void failStepWithWrongCurrentStateMutatesNothing() {
        // Same generation, but the ceremony has since moved on to a different waiting state — the
        // failure signal is for a step that is no longer the one in flight.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_RECEIVED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        service.failStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED, "SOME_TITLE", "detail");

        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, ceremony.getState());
        assertEquals(null, ceremony.getErrorTitle());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void failStepHappyPathSetsFailedStateAndError() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        service.failStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED, "KERI_WALLET_TIMEOUT",
                "wallet did not respond");

        assertEquals(CeremonyState.FAILED, ceremony.getState());
        assertEquals("KERI_WALLET_TIMEOUT", ceremony.getErrorTitle());
        assertEquals("wallet did not respond", ceremony.getErrorDetail());
    }

    @Test
    void lateFailureForACompletedStepDoesNotClobberTheNextStepsWaitingState() {
        // Regression for the reported race: step A (CREDENTIAL_REQUESTED) begins and completes at
        // generation 0, moving the ceremony into CREDENTIAL_RECEIVED. beginStep for step B then moves it
        // into AUTH_BEGIN_SUBMITTED, still at generation 0 (no retry occurred, so no bump). A late
        // failure signal for step A then arrives and calls failStep with A's own waiting state
        // (CREDENTIAL_REQUESTED) and generation 0. A generation-only CAS would match and incorrectly
        // fail the ceremony while it is legitimately waiting on step B; the state guard must reject it.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        // Step A completes: CREDENTIAL_REQUESTED -> CREDENTIAL_RECEIVED, generation stays 0.
        service.completeStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED, CeremonyState.CREDENTIAL_RECEIVED,
                c -> { });
        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, ceremony.getState());
        assertEquals(0, ceremony.getAttemptGeneration());

        // Step B begins: CREDENTIAL_RECEIVED -> AUTH_BEGIN_SUBMITTED, still generation 0 (non-retry).
        Either<ProblemDetail, KeriAttestationCeremonyEntity> beginResult = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.CREDENTIAL_RECEIVED, CeremonyState.AUTH_BEGIN_SUBMITTED, false);
        assertTrue(beginResult.isRight());
        assertEquals(CeremonyState.AUTH_BEGIN_SUBMITTED, ceremony.getState());
        assertEquals(0, ceremony.getAttemptGeneration());

        // Late failure for step A arrives: same generation (0), but A's waiting state
        // (CREDENTIAL_REQUESTED) no longer matches the ceremony's actual state.
        service.failStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED, "STALE_TITLE", "stale detail");

        assertEquals(CeremonyState.AUTH_BEGIN_SUBMITTED, ceremony.getState());
        assertEquals(null, ceremony.getErrorTitle());
        assertEquals(null, ceremony.getErrorDetail());
    }

    // --- validateAndConsume ---

    @Test
    void validateAndConsumeHappyPathFlipsAttestAnchoredToConsumed() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_ANCHORED);
        ceremony.setMetadataDigest("Edigest");
        ceremony.setMetadataLabel("1447");
        ceremony.setKelSequence("3");
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", "Ecred", "a".repeat(64))));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", USER);

        assertTrue(result.isRight());
        ConsumedAttestation consumed = result.get();
        assertEquals(CEREMONY_ID, consumed.ceremonyId());
        assertEquals("Eaid", consumed.aid());
        assertEquals("Edigest", consumed.digestQb64());
        assertEquals("1447", consumed.metadataLabel());
        assertEquals("3", consumed.kelSequence());
        assertEquals(CeremonyState.CONSUMED, ceremony.getState());
    }

    @Test
    void validateAndConsumeTwiceReturnsInvalidStateOnTheSecondCall() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CONSUMED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", USER);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
    }

    @Test
    void validateAndConsumeWithWrongTargetIsTargetMismatch() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_ANCHORED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "some-other-doc", USER);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.TARGET_MISMATCH, result.getLeft().getTitle());
    }

    @Test
    void validateAndConsumeByNonOwnerIsForbidden() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID))
                .thenReturn(Optional.of(ceremony(CeremonyState.ATTEST_ANCHORED)));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", "someone-else");

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_FORBIDDEN, result.getLeft().getTitle());
    }

    @Test
    void validateAndConsumeOnAnExpiredCeremonyIsRejected() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_ANCHORED);
        ceremony.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", USER);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_EXPIRED, result.getLeft().getTitle());
        assertEquals(CeremonyState.EXPIRED, ceremony.getState());
    }

    @Test
    void validateAndConsumeWithStaleBindingVersionIsIdentityRelinked() {
        // ceremony was created under binding_version=1 (see the ceremony() helper); the link has since
        // moved to binding_version=2, i.e. the user relinked to a different AID after this ceremony started.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_ANCHORED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(2, "Eaid-new", "Ecred", "a".repeat(64))));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", USER);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, result.getLeft().getTitle());
    }
}
