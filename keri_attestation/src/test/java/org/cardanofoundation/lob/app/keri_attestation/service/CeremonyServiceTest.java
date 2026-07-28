package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
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
            3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
            Duration.parse("PT15S"), Duration.parse("PT30M"), Duration.parse("PT2S"), Duration.parse("PT3S"),
            Duration.parse("PT2M"), null);

    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private AttestationTargetProviderRegistry targetProviderRegistry;
    @Mock
    private AttestationTargetProvider targetProvider;

    private CeremonyService service;

    @BeforeEach
    void setUp() {
        service = new CeremonyService(ceremonyRepository, identityLinkRepository, properties, targetProviderRegistry);
        // Not every test needs the active-ceremony count stubbed (only create() reads it); STRICT_STUBS
        // would otherwise flag it as unnecessary in every other test.
        lenient().when(ceremonyRepository.countByUserIdAndStateNotIn(anyString(), any())).thenReturn(0L);
        // Default create() to an authorized DOCUMENT provider so every pre-existing create() test keeps
        // exercising fast-forward/limit behavior without also having to stub target authorization (F2).
        lenient().when(targetProviderRegistry.forType(anyString())).thenReturn(Optional.of(targetProvider));
        lenient().when(targetProvider.authorize(anyString(), anyString())).thenReturn(Optional.empty());
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

    // --- create: fast-forward from the identity link ---

    @Test
    void createWithNoLinkStartsAtCreatedWithAllStepsRequired() {
        // create() locks the link row (findByUserIdForUpdate), not a plain findById.
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());
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
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(link(1, "Eaid", null, null)));
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CeremonyView view = service.create(USER, "DOCUMENT", "doc-1").get();

        assertEquals(CeremonyState.OOBI_RESOLVED, view.state());
        assertFalse(view.requiredSteps().oobi());
        assertTrue(view.requiredSteps().credential());
        assertTrue(view.requiredSteps().authBegin());
    }

    @Test
    void createWithLinkHavingCredentialButNoAuthBeginStartsAtCredentialReceived() {
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(link(1, "Eaid", "Ecred", null)));
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CeremonyView view = service.create(USER, "DOCUMENT", "doc-1").get();

        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, view.state());
        assertFalse(view.requiredSteps().oobi());
        assertFalse(view.requiredSteps().credential());
        assertTrue(view.requiredSteps().authBegin());
    }

    @Test
    void createWithFullyConfirmedLinkStartsAtAuthBeginConfirmedWithOnlyAttestRemaining() {
        when(identityLinkRepository.findByUserIdForUpdate(USER))
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
    void createWithAssertedAuthBeginLinkStartsAtAuthBeginConfirmedEvenWithoutATxHash() {
        // "I already published it" (user-asserted, unverified): no tx hash, but the asserted flag still
        // counts AUTH_BEGIN as complete, so the step is skipped just like a real confirmed hash would.
        KeriIdentityLinkEntity assertedLink = link(1, "Eaid", "Ecred", null);
        assertedLink.setAuthBeginAsserted(true);
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(assertedLink));
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CeremonyView view = service.create(USER, "DOCUMENT", "doc-1").get();

        assertEquals(CeremonyState.AUTH_BEGIN_CONFIRMED, view.state());
        assertFalse(view.requiredSteps().authBegin());
        assertNull(view.authBeginTxHash());
    }

    @Test
    void createRejectsWhenActiveCeremonyLimitReached() {
        when(ceremonyRepository.countByUserIdAndStateNotIn(USER, TERMINAL)).thenReturn(3L);

        Either<ProblemDetail, CeremonyView> result = service.create(USER, "DOCUMENT", "doc-1");

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_LIMIT_REACHED, result.getLeft().getTitle());
    }

    // --- create: target authorization ---

    @Test
    void createRejectsAnUnknownTargetTypeWithNoRegisteredProvider() {
        when(targetProviderRegistry.forType("UNKNOWN")).thenReturn(Optional.empty());

        Either<ProblemDetail, CeremonyView> result = service.create(USER, "UNKNOWN", "target-1");

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.TARGET_MISMATCH, result.getLeft().getTitle());
        verifyNoInteractions(identityLinkRepository);
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void createPropagatesTheProvidersAuthorizationProblem() {
        ProblemDetail authProblem = KeriAttestationProblems.forbidden("user may not publish document doc-1");
        when(targetProvider.authorize("doc-1", USER)).thenReturn(Optional.of(authProblem));

        Either<ProblemDetail, CeremonyView> result = service.create(USER, "DOCUMENT", "doc-1");

        assertTrue(result.isLeft());
        assertEquals(authProblem, result.getLeft());
        verifyNoInteractions(identityLinkRepository);
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void createProceedsAndCallsTheProviderWhenTheTargetIsAuthorized() {
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, CeremonyView> result = service.create(USER, "DOCUMENT", "doc-1");

        assertTrue(result.isRight());
        verify(targetProvider).authorize("doc-1", USER);
    }

    @Test
    void createLocksTheIdentityLinkRowInsteadOfPlainReadingIt() {
        // create() must serialize against KeriOobiService's relink via the identity link's row
        // lock, not an unlocked read that a relink's own ceremony-invalidation-then-link-update window
        // could otherwise race. A genuine interleaving test would need real concurrent transactions,
        // which this Mockito-based unit test cannot exercise; a true race requires H2/testcontainers-level
        // concurrency this suite does not set up elsewhere either. This pins the actual, enforceable
        // contract instead: create() must go through the locked finder and must never fall back to the
        // plain one.
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());
        when(ceremonyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, CeremonyView> result = service.create(USER, "DOCUMENT", "doc-1");

        assertTrue(result.isRight());
        verify(identityLinkRepository).findByUserIdForUpdate(USER);
        verify(identityLinkRepository, never()).findById(anyString());
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

    // --- link-derived fast-forward floor ---

    @Test
    void getAdvancesARestingCeremonyToTheCurrentLinkDerivedFloor() {
        // Ceremony was created before the user resolved OOBI; the user has since resolved it — a plain
        // GET must reflect that identity-level progress instead of leaving the ceremony stuck at CREATED.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREATED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", null, null)));

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, USER);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.OOBI_RESOLVED, result.get().state());
        assertEquals(CeremonyState.OOBI_RESOLVED, ceremony.getState());
        verify(ceremonyRepository).save(ceremony);
    }

    @Test
    void beginStepAdvancesARestingCeremonyBeforeCheckingTheExpectedState() {
        // Without the link-derived fast-forward this beginStep call 409s forever: the ceremony is stuck at CREATED even
        // though the identity link already has an AID, so credential/request (which expects
        // OOBI_RESOLVED) would never be reachable.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREATED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", null, null)));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> result = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_REQUESTED, false);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, result.get().getState());
    }

    @Test
    void getNeverAdvancesACeremonyThatIsActivelyWaitingOnAStep() {
        // The link is far ahead (through AUTH_BEGIN), but a step in flight must never be silently
        // fast-forwarded out from under its own in-flight async worker.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", "Ecred", "a".repeat(64))));

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, USER);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, result.get().state());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void beginStepNeverAdvancesAWaitingStateViaTheLinkFloor() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.AUTH_BEGIN_SUBMITTED);
        ceremony.setUpdatedAt(LocalDateTime.now().minusSeconds(30));
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> result = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.CREDENTIAL_RECEIVED, CeremonyState.AUTH_BEGIN_SUBMITTED, true);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.AUTH_BEGIN_SUBMITTED, result.get().getState());
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void getNeverAdvancesACeremonyWhoseBindingVersionIsBehindTheCurrentLink() {
        // The ceremony (helper default bindingVersion=1) was created under the old identity; the link
        // has since been relinked to bindingVersion=2 — its progress must never be used to fast-forward
        // a ceremony that belongs to a now-superseded identity.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREATED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(2, "Eaid-new", null, null)));

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, USER);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.CREATED, result.get().state());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void getNeverAdvancesAttestAnchoredEvenWhenTheLinkLooksFurtherAlong() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_ANCHORED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", "Ecred", "a".repeat(64))));

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, USER);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.ATTEST_ANCHORED, result.get().state());
        verify(ceremonyRepository, never()).save(any());
    }

    // --- initial-link adoption: a ceremony created before any link existed (bindingVersion=0)
    //     must adopt the first-linked binding version rather than being permanently stuck at CREATED ---

    @Test
    void getAdoptsTheLinksBindingVersionForACeremonyCreatedBeforeAnyLinkExisted() {
        // create() captured bindingVersion=0 (the "no link yet" marker) when this ceremony was made; the
        // user has since resolved their first OOBI, creating the link at bindingVersion=1. Without F6,
        // the plain version-match guard (0 != 1) would reject this forever.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREATED);
        ceremony.setBindingVersion(0);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", null, null)));

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, USER);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.OOBI_RESOLVED, result.get().state());
        assertEquals(CeremonyState.OOBI_RESOLVED, ceremony.getState());
        assertEquals(1, ceremony.getBindingVersion());
        verify(ceremonyRepository).save(ceremony);
    }

    @Test
    void getStillRejectsARealRelinkEvenWhenTheCeremonyWasCreatedUnderAnAlreadyLinkedBinding() {
        // Distinguishes initial-link adoption from a genuine relink: this ceremony was created under a
        // REAL binding (v2, not the bindingVersion=0 "no link yet" marker) and the link has since moved
        // to v3 — that is relink semantics, which must still reject exactly as before F6.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREATED);
        ceremony.setBindingVersion(2);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(3, "Eaid-new", null, null)));

        Either<ProblemDetail, CeremonyView> result = service.get(CEREMONY_ID, USER);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.CREATED, result.get().state());
        assertEquals(2, ceremony.getBindingVersion());
        verify(ceremonyRepository, never()).save(any());
    }

    // --- completeStep / failStep: CAS on (attemptGeneration, state) ---

    @SuppressWarnings("unchecked")
    @Test
    void completeStepWithStaleGenerationMutatesNothing() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        ceremony.setAttemptGeneration(1);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        Consumer<KeriAttestationCeremonyEntity> mutator = org.mockito.Mockito.mock(Consumer.class);

        boolean completed = service.completeStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED,
                CeremonyState.CREDENTIAL_RECEIVED, mutator);

        assertFalse(completed);
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

        boolean completed = service.completeStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED,
                CeremonyState.CREDENTIAL_RECEIVED, mutator);

        assertFalse(completed);
        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, ceremony.getState());
        verify(mutator, never()).accept(any());
    }

    @Test
    void completeStepHappyPathAppliesMutatorAndMovesState() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CREDENTIAL_REQUESTED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        boolean completed = service.completeStep(CEREMONY_ID, 0, CeremonyState.CREDENTIAL_REQUESTED,
                CeremonyState.CREDENTIAL_RECEIVED, c -> c.setKelSequence("5"));

        assertTrue(completed);
        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, ceremony.getState());
        assertEquals("5", ceremony.getKelSequence());
        verify(ceremonyRepository, times(1)).save(ceremony);
    }

    @Test
    void completeStepOnUnknownCeremonyIsANoOp() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.empty());

        boolean completed = service.completeStep(CEREMONY_ID, 0, CeremonyState.CREATED, CeremonyState.OOBI_RESOLVED,
                c -> { });

        assertFalse(completed);
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

    // --- updateWaitingStepData: guarded step-data write ---

    @Test
    void updateWaitingStepDataHappyPathAppliesMutatorAndPersistsWithoutChangingState() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        boolean updated = service.updateWaitingStepData(CEREMONY_ID, 0, CeremonyState.ATTEST_REQUESTED,
                c -> c.setRequestExnSaid("Eexn123"));

        assertTrue(updated);
        assertEquals("Eexn123", ceremony.getRequestExnSaid());
        assertEquals(CeremonyState.ATTEST_REQUESTED, ceremony.getState());
        assertEquals(0, ceremony.getAttemptGeneration());
        verify(ceremonyRepository).save(ceremony);
    }

    @Test
    void updateWaitingStepDataWithStaleGenerationMutatesNothing() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED);
        ceremony.setAttemptGeneration(1);
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        boolean updated = service.updateWaitingStepData(CEREMONY_ID, 0, CeremonyState.ATTEST_REQUESTED,
                c -> c.setRequestExnSaid("Eexn123"));

        assertFalse(updated);
        assertEquals(null, ceremony.getRequestExnSaid());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void updateWaitingStepDataWithWrongCurrentStateMutatesNothing() {
        // Regression for the detached-entity-save race (F2): a concurrent transition moved the ceremony
        // on to a different state (e.g. a sweep failed it, or another attempt completed the step) between
        // beginStep's row lock releasing and this write — the guard must reject rather than resurrect the
        // stale waiting state or silently overwrite the ceremony's real current state.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.FAILED);
        ceremony.setErrorTitle("KERI_STEP_TIMED_OUT");
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        boolean updated = service.updateWaitingStepData(CEREMONY_ID, 0, CeremonyState.ATTEST_REQUESTED,
                c -> c.setRequestExnSaid("Eexn123"));

        assertFalse(updated);
        assertEquals(null, ceremony.getRequestExnSaid());
        assertEquals(CeremonyState.FAILED, ceremony.getState());
        assertEquals("KERI_STEP_TIMED_OUT", ceremony.getErrorTitle());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void updateWaitingStepDataOnUnknownCeremonyIsANoOp() {
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.empty());

        boolean updated = service.updateWaitingStepData(CEREMONY_ID, 0, CeremonyState.ATTEST_REQUESTED,
                c -> c.setRequestExnSaid("Eexn123"));

        assertFalse(updated);
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void updateWaitingStepDataConcurrentTransitionBetweenBeginStepAndUpdateIsRejected() {
        // Simulates the race updateWaitingStepData closes: beginStep returns a ceremony at generation 0 in
        // ATTEST_REQUESTED; before the service gets to persist requestExnSaid, a concurrent retry bumps
        // the generation (simulating a superseding beginStep(retry=true) call). The guarded update must
        // reject and must not resurrect/overwrite the now-current generation's data.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_REQUESTED);
        ceremony.setUpdatedAt(LocalDateTime.now().minusSeconds(30));
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, KeriAttestationCeremonyEntity> begun = service.beginStep(
                CEREMONY_ID, USER, CeremonyState.ATTEST_REQUESTED, CeremonyState.ATTEST_REQUESTED, true);
        assertTrue(begun.isRight());
        int staleGeneration = 0;
        assertEquals(1, ceremony.getAttemptGeneration());

        boolean updated = service.updateWaitingStepData(CEREMONY_ID, staleGeneration, CeremonyState.ATTEST_REQUESTED,
                c -> c.setRequestExnSaid("EstaleExn"));

        assertFalse(updated);
        assertEquals(null, ceremony.getRequestExnSaid());
        assertEquals(1, ceremony.getAttemptGeneration());
    }

    // --- validateAndConsume ---

    @Test
    void validateAndConsumeHappyPathFlipsAttestAnchoredToConsumed() {
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_ANCHORED);
        ceremony.setMetadataDigest("Edigest");
        // Deliberately distinct from metadataDigest: payloadSaid is the value the
        // wallet's KEL seal actually anchors / the on-chain 170.d, digestQb64 is the raw 1447/freeze
        // digest — a swap of the two in validateAndConsume's ConsumedAttestation construction must fail
        // this test.
        ceremony.setPayloadSaid("Epayloadsaid");
        ceremony.setMetadataLabel("1447");
        ceremony.setKelSequence("3");
        ceremony.setAttesterAid("Eaid");
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Eaid", "Ecred", "a".repeat(64))));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", USER);

        assertTrue(result.isRight());
        ConsumedAttestation consumed = result.get();
        assertEquals(CEREMONY_ID, consumed.ceremonyId());
        assertEquals("Eaid", consumed.aid());
        assertEquals("Edigest", consumed.digestQb64());
        assertEquals("Epayloadsaid", consumed.payloadSaid());
        assertEquals("1447", consumed.metadataLabel());
        assertEquals("3", consumed.kelSequence());
        assertEquals(CeremonyState.CONSUMED, ceremony.getState());
    }

    @Test
    void validateAndConsumeFailsClosedWhenAttesterAidIsNull() {
        // this module has never been deployed, so no CONSUMED row
        // anywhere can lack an attesterAid in practice - a null here on an ATTEST_ANCHORED ceremony
        // indicates data corruption and must be rejected rather than silently resurrecting the
        // CURRENT identity link's AID (the relink-misattribution hole the removed fallback reopened).
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_ANCHORED);
        ceremony.setMetadataDigest("Edigest");
        ceremony.setMetadataLabel("1447");
        ceremony.setKelSequence("3");
        // attesterAid deliberately left null.
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", USER);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CEREMONY_INVALID_STATE, result.getLeft().getTitle());
        assertEquals(CeremonyState.ATTEST_ANCHORED, ceremony.getState());
        verify(ceremonyRepository, never()).save(any());
        // Fails before ever consulting the identity link.
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void validateAndConsumeUsesThePersistedAttesterAidNotTheCurrentLinkAid() {
        // ceremony.attesterAid (persisted by KeriAttestService#resolveAndComplete at
        // ATTEST_ANCHORED time) is authoritative whenever set - never re-derived from the identity
        // link's current aid, even though in this test they happen to differ only to prove which one
        // actually won.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.ATTEST_ANCHORED);
        ceremony.setMetadataDigest("Edigest");
        ceremony.setMetadataLabel("1447");
        ceremony.setKelSequence("3");
        ceremony.setAttesterAid("Eattester-aid");
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(1, "Elink-aid", "Ecred", "a".repeat(64))));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", USER);

        assertTrue(result.isRight());
        assertEquals("Eattester-aid", result.get().aid());
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
        // attesterAid must be set for this ceremony to reach the binding-version check at all -
        // a null attesterAid is now rejected earlier (CEREMONY_INVALID_STATE), see
        // validateAndConsumeFailsClosedWhenAttesterAidIsNull.
        ceremony.setAttesterAid("Eaid-old");
        when(ceremonyRepository.findByIdForUpdate(CEREMONY_ID)).thenReturn(Optional.of(ceremony));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(link(2, "Eaid-new", "Ecred", "a".repeat(64))));

        Either<ProblemDetail, ConsumedAttestation> result =
                service.validateAndConsume(CEREMONY_ID, "DOCUMENT", "doc-1", USER);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, result.getLeft().getTitle());
    }

    // --- findTerminalNonConsumedCeremonyIds (freeze cleanup) ---

    @Test
    void findTerminalNonConsumedCeremonyIdsDelegatesToTheFailedOrExpiredQuery() {
        Set<String> ids = Set.of("cer-1", "cer-2", "cer-3");
        when(ceremonyRepository.findIdsByIdInAndStateIn(ids, EnumSet.of(CeremonyState.FAILED, CeremonyState.EXPIRED)))
                .thenReturn(List.of("cer-1", "cer-3"));

        List<String> result = service.findTerminalNonConsumedCeremonyIds(ids);

        assertEquals(List.of("cer-1", "cer-3"), result);
    }

    @Test
    void findTerminalNonConsumedCeremonyIdsShortCircuitsOnEmptyInput() {
        List<String> result = service.findTerminalNonConsumedCeremonyIds(Set.of());

        assertTrue(result.isEmpty());
        verifyNoInteractions(ceremonyRepository, identityLinkRepository, targetProviderRegistry);
    }

    // --- findConsumed (dispatch-time attestation lookup) ---

    @Test
    void findConsumedIsEmptyWhenNoAttesterAidIsPersisted() {
        // a CONSUMED row with no attesterAid ever persisted used to
        // fall back to the CURRENT identity link - this module has never been deployed, so no such row
        // can exist in a real database, and the fallback itself reopened the relink-misattribution
        // hole F1 exists to close. findConsumed now fails closed (empty) instead.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CONSUMED);
        ceremony.setMetadataDigest("Edigest");
        ceremony.setMetadataLabel("1447");
        ceremony.setKelSequence("3");
        // attesterAid deliberately left null.
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Optional<ConsumedAttestation> result = service.findConsumed(CEREMONY_ID);

        assertFalse(result.isPresent());
        verifyNoInteractions(identityLinkRepository);
        // Read-only: no row lock, no state mutation, no write.
        verify(ceremonyRepository, never()).findByIdForUpdate(anyString());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void findConsumedReturnsTheOriginalAttesterAidEvenAfterARelinkChangedTheCurrentIdentityLink() {
        // The core scenario the immutable attester AID closes: consume -> relink -> a delayed dispatch retry
        // calling findConsumed must still see the ORIGINAL attesting aid (and the digest/kelSequence
        // that were actually anchored under it), never the identity's NEW (post-relink) aid. Simulated
        // here by simply never stubbing identityLinkRepository at all - proving the link is not even
        // consulted once attesterAid is persisted, so a concurrent/subsequent relink of the CURRENT
        // link cannot influence this result no matter what it changes to.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.CONSUMED);
        ceremony.setMetadataDigest("Edigest");
        // Deliberately distinct from metadataDigest — see validateAndConsumeHappyPathFlipsAttestAnchoredToConsumed's
        // comment for why a swap here must fail this test.
        ceremony.setPayloadSaid("Epayloadsaid");
        ceremony.setMetadataLabel("1447");
        ceremony.setKelSequence("3");
        ceremony.setAttesterAid("Eoriginal-attester-aid");
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony));

        Optional<ConsumedAttestation> result = service.findConsumed(CEREMONY_ID);

        assertTrue(result.isPresent());
        assertEquals("Eoriginal-attester-aid", result.get().aid());
        assertEquals("Edigest", result.get().digestQb64());
        assertEquals("Epayloadsaid", result.get().payloadSaid());
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void findConsumedIsEmptyWhenTheCeremonyIsOnlyAttestAnchored() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(CeremonyState.ATTEST_ANCHORED)));

        Optional<ConsumedAttestation> result = service.findConsumed(CEREMONY_ID);

        assertFalse(result.isPresent());
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void findConsumedIsEmptyWhenTheCeremonyFailed() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony(CeremonyState.FAILED)));

        Optional<ConsumedAttestation> result = service.findConsumed(CEREMONY_ID);

        assertFalse(result.isPresent());
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void findConsumedIsEmptyWhenTheCeremonyDoesNotExist() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.empty());

        Optional<ConsumedAttestation> result = service.findConsumed(CEREMONY_ID);

        assertFalse(result.isPresent());
        verifyNoInteractions(identityLinkRepository);
    }

}
