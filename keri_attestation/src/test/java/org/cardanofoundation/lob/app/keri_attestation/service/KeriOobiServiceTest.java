package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
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
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.signify.app.Contacting;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Oobis;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.exception.OperationTimeoutException;
import org.cardanofoundation.signify.exception.SignifyAgentException;
import org.cardanofoundation.signify.exception.SignifyInterruptedException;
import org.cardanofoundation.signify.exception.SignifyTransportException;
import org.cardanofoundation.signify.generated.keria.model.Contact;
import org.cardanofoundation.signify.generated.keria.model.Operation;
import org.cardanofoundation.signify.generated.keria.model.PendingOOBIOperation;

@ExtendWith(MockitoExtension.class)
class KeriOobiServiceTest {

    private static final String USER = "user-1";
    private static final String VALID_OOBI = "https://witness.example.org/oobi/EAID12345/agent/EAGENT6789";
    private static final String VALID_OOBI_OTHER_AID = "https://witness.example.org/oobi/ENEWAID999/agent/EAGENT6789";
    private static final String AID = "EAID12345";
    private static final String OTHER_AID = "ENEWAID999";

    @Mock
    private SignifyClient client;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private Oobis oobis;
    @Mock
    private Operations operations;
    @Mock
    private Contacting.Contacts contacts;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private KeriOobiService service;

    @BeforeEach
    void setUp() throws Exception {
        // Shared "happy path" client stubs — not every test reaches the client (invalid-URL tests
        // must never touch it at all), so these are lenient and simply unused in those cases.
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.oobis()).thenReturn(oobis);
        lenient().when(client.operations()).thenReturn(operations);
        lenient().when(client.contacts()).thenReturn(contacts);
        lenient().when(oobis.resolve(anyString(), anyString())).thenReturn(new PendingOOBIOperation());
        lenient().when(operations.wait(any(Operation.class), any(Operations.WaitOptions.class))).thenReturn(null);
        lenient().when(contacts.get(anyString())).thenReturn(Optional.of(new Contact()));

        service = new KeriOobiService(keriClient, identityLinkRepository, ceremonyRepository, eventPublisher);
    }

    private KeriIdentityLinkEntity link(int bindingVersion, String aid, String oobiUrl) {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER);
        link.setBindingVersion(bindingVersion);
        link.setAid(aid);
        link.setOobiUrl(oobiUrl);
        return link;
    }

    // --- validation runs before any client call ---

    @Test
    void nonHttpsUrlIsOobiInvalidAndNeverTouchesTheClient() {
        Either<ProblemDetail, String> result =
                service.resolveUserOobi(USER, "http://witness.example.org/oobi/EAID12345", false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), result.getLeft().getStatus());
        verifyNoInteractions(client);
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void urlWithoutOobiSegmentIsOobiInvalidAndNeverTouchesTheClient() {
        Either<ProblemDetail, String> result =
                service.resolveUserOobi(USER, "https://witness.example.org/not-oobi/EAID12345", false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        verifyNoInteractions(client);
    }

    @Test
    void urlWithEmptyAidSegmentIsOobiInvalidAndNeverTouchesTheClient() {
        Either<ProblemDetail, String> result =
                service.resolveUserOobi(USER, "https://witness.example.org/oobi/", false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        verifyNoInteractions(client);
    }

    @Test
    void urlExceedingMaxLengthIsOobiInvalidAndNeverTouchesTheClient() {
        String longUrl = "https://witness.example.org/oobi/" + "E".repeat(2048) + "/agent/x";

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, longUrl, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        verifyNoInteractions(client);
    }

    @Test
    void blankUrlIsOobiInvalidAndNeverTouchesTheClient() {
        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, "   ", false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        verifyNoInteractions(client);
    }

    @Test
    void syntacticallyInvalidUrlIsOobiInvalidAndNeverTouchesTheClient() {
        // An unescaped space is illegal in a URI and fails java.net.URI parsing outright, distinct
        // from the other validation failures (which are all syntactically valid URIs that fail a
        // semantic check instead).
        Either<ProblemDetail, String> result =
                service.resolveUserOobi(USER, "https://witness.example.org/oobi/E AID 12345", false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        verifyNoInteractions(client);
    }

    // --- happy path: no existing link ---

    @Test
    void happyPathWithNoExistingLinkExtractsAidAndPersistsAtBindingVersion1() throws Exception {
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.empty());
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isRight());
        assertEquals(AID, result.get());

        verify(oobis).resolve(VALID_OOBI, USER);
        verify(contacts).get(AID);

        var captor = org.mockito.ArgumentCaptor.forClass(KeriIdentityLinkEntity.class);
        verify(identityLinkRepository).save(captor.capture());
        KeriIdentityLinkEntity saved = captor.getValue();
        assertEquals(USER, saved.getUserId());
        assertEquals(1, saved.getBindingVersion());
        assertEquals(AID, saved.getAid());
        assertEquals(VALID_OOBI, saved.getOobiUrl());
        // RelinkCompletedEvent is only for a genuine relink (a version bump on an EXISTING
        // link) - a first-ever link create is not one.
        verifyNoInteractions(eventPublisher);
    }

    // --- same AID re-resolve: refresh oobiUrl only, no version bump ---

    @Test
    void reResolvingSameAidRefreshesOobiUrlWithoutBumpingBindingVersion() {
        KeriIdentityLinkEntity existing = link(3, AID, "https://old.example.org/oobi/EAID12345");
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(existing));
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(existing));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isRight());
        assertEquals(AID, result.get());
        assertEquals(3, existing.getBindingVersion());
        assertEquals(VALID_OOBI, existing.getOobiUrl());
        verify(identityLinkRepository).save(existing);
        verifyNoInteractions(ceremonyRepository);
        // a same-AID refresh never bumps bindingVersion, so it is not a relink either.
        verifyNoInteractions(eventPublisher);
    }

    // --- different AID, relink not requested: IDENTITY_RELINKED conflict ---

    @Test
    void differentAidWithoutRelinkIsIdentityRelinkedConflictAndLeavesLinkUntouched() {
        KeriIdentityLinkEntity existing = link(1, "EOLDAID000", "https://old.example.org/oobi/EOLDAID000");
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(existing));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI_OTHER_AID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, result.getLeft().getTitle());
        assertEquals(HttpStatus.CONFLICT.value(), result.getLeft().getStatus());
        // Link must not have been mutated or saved.
        assertEquals("EOLDAID000", existing.getAid());
        verify(identityLinkRepository, never()).save(any());
        verifyNoInteractions(ceremonyRepository);
        // Rejected from the plain (unlocked) read alone -- the link row is never
        // even locked for a call that's going to be rejected anyway.
        verify(identityLinkRepository, never()).findByUserIdForUpdate(any());
        // rejected, no write at all -> no RelinkCompletedEvent.
        verifyNoInteractions(eventPublisher);
    }

    // --- different AID, relink=true: version bump, dependent fields cleared, ceremonies invalidated ---

    @Test
    void differentAidWithRelinkBumpsVersionClearsDependentFieldsAndInvalidatesOpenCeremonies() {
        KeriIdentityLinkEntity existing = link(1, "EOLDAID000", "https://old.example.org/oobi/EOLDAID000");
        existing.setCredentialSaid("Ecred");
        existing.setCredentialSchemaSaid("Eschema");
        existing.setAuthBeginTxHash("a".repeat(64));
        existing.setAuthBeginBlock(999L);
        existing.setAuthBeginAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(existing));
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(existing));

        KeriAttestationCeremonyEntity openCeremony = new KeriAttestationCeremonyEntity();
        openCeremony.setId("cer-1");
        openCeremony.setUserId(USER);
        openCeremony.setState(CeremonyState.CREDENTIAL_REQUESTED);
        openCeremony.setUpdatedAt(LocalDateTime.now());
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of(openCeremony));
        // invalidateOpenCeremonies re-fetches each candidate under the row lock before mutating it
        // (guards against a concurrent legitimate transition landing between the discovery read and
        // this write) — same object reference, so the assertions below observe the mutation either way.
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(openCeremony));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI_OTHER_AID, true);

        assertTrue(result.isRight());
        assertEquals(OTHER_AID, result.get());

        assertEquals(2, existing.getBindingVersion());
        assertEquals(OTHER_AID, existing.getAid());
        assertEquals(VALID_OOBI_OTHER_AID, existing.getOobiUrl());
        assertNull(existing.getCredentialSaid());
        assertNull(existing.getCredentialSchemaSaid());
        assertNull(existing.getAuthBeginTxHash());
        assertNull(existing.getAuthBeginBlock());
        assertNull(existing.getAuthBeginAt());
        verify(identityLinkRepository).save(existing);

        assertEquals(CeremonyState.FAILED, openCeremony.getState());
        assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, openCeremony.getErrorTitle());
        verify(ceremonyRepository).save(openCeremony);

        // Lock-order inversion: the ceremony row lock (findByIdForUpdate, inside
        // invalidateOpenCeremonies) must be taken and released BEFORE the link row is ever locked
        // (findByUserIdForUpdate) -- the same order completion paths use (ceremony, then link).
        InOrder lockOrder = inOrder(ceremonyRepository, identityLinkRepository);
        lockOrder.verify(ceremonyRepository).findByIdForUpdate("cer-1");
        lockOrder.verify(identityLinkRepository).findByUserIdForUpdate(USER);

        // a genuine relink publishes RelinkCompletedEvent with the NEW bindingVersion, closing
        // the phantom-insert window between the in-transaction sweep above and this write actually
        // landing - see RelinkInvalidationSweepHandler for the AFTER_COMMIT sweep this event drives.
        ArgumentCaptor<RelinkCompletedEvent> eventCaptor = ArgumentCaptor.forClass(RelinkCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(USER, eventCaptor.getValue().userId());
        assertEquals(2, eventCaptor.getValue().newVersion());
    }

    @Test
    void residualRaceWherePlainReadMissesAConcurrentAidChangeStillRejectsWithoutRelinkGranted() {
        // Regression guard for the residual race persistLink's javadoc documents: the plain (unlocked)
        // read sees the SAME aid being requested (looks like a simple refresh, no relink needed and
        // relink=false is fine), but a concurrent request already changed the AID by the time the link
        // row is actually locked. Even though relink was never requested, lockAndUpsertLink's own
        // under-lock check must still reject with IDENTITY_RELINKED rather than silently performing an
        // unrequested relink.
        KeriIdentityLinkEntity plainReadView = link(1, AID, "https://old.example.org/oobi/" + AID);
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(plainReadView));
        KeriIdentityLinkEntity lockedView = link(1, OTHER_AID, "https://concurrent.example.org/oobi/" + OTHER_AID);
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(lockedView));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, result.getLeft().getTitle());
        assertEquals(OTHER_AID, lockedView.getAid());
        verify(identityLinkRepository, never()).save(any());
        verifyNoInteractions(ceremonyRepository);
        // rejected, no write at all -> no RelinkCompletedEvent.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void relinkDoesNotClobberACeremonyThatWasConcurrentlyConsumedBetweenTheDiscoveryReadAndTheLock() {
        // Regression guard for the race the row-lock re-check exists for: the unlocked
        // findByUserIdAndStateNotIn discovery read still lists a ceremony that, by the time
        // invalidateOpenCeremonies takes its row lock, a concurrent CeremonyService.validateAndConsume
        // has already legitimately moved to CONSUMED. The stale in-memory candidate must not be used
        // to overwrite that outcome back to FAILED.
        KeriIdentityLinkEntity existing = link(1, "EOLDAID000", "https://old.example.org/oobi/EOLDAID000");
        when(identityLinkRepository.findById(USER)).thenReturn(Optional.of(existing));
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(existing));

        KeriAttestationCeremonyEntity staleCandidate = new KeriAttestationCeremonyEntity();
        staleCandidate.setId("cer-2");
        staleCandidate.setUserId(USER);
        staleCandidate.setState(CeremonyState.CREDENTIAL_REQUESTED);
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of(staleCandidate));

        KeriAttestationCeremonyEntity nowConsumed = new KeriAttestationCeremonyEntity();
        nowConsumed.setId("cer-2");
        nowConsumed.setUserId(USER);
        nowConsumed.setState(CeremonyState.CONSUMED);
        when(ceremonyRepository.findByIdForUpdate("cer-2")).thenReturn(Optional.of(nowConsumed));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI_OTHER_AID, true);

        assertTrue(result.isRight());
        assertEquals(CeremonyState.CONSUMED, nowConsumed.getState());
        verify(ceremonyRepository, never()).save(nowConsumed);
    }

    // --- resetIdentity (full unlink) ---

    @Test
    void resetIdentityDeletesTheLinkAndFailsOpenCeremoniesWithIdentityReset() {
        KeriIdentityLinkEntity existing = link(2, AID, VALID_OOBI);
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(existing));

        KeriAttestationCeremonyEntity openCeremony = new KeriAttestationCeremonyEntity();
        openCeremony.setId("cer-1");
        openCeremony.setUserId(USER);
        openCeremony.setState(CeremonyState.CREDENTIAL_REQUESTED);
        openCeremony.setUpdatedAt(LocalDateTime.now());
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of(openCeremony));
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(openCeremony));

        Either<ProblemDetail, Void> result = service.resetIdentity(USER);

        assertTrue(result.isRight());
        verify(identityLinkRepository).delete(existing);

        assertEquals(CeremonyState.FAILED, openCeremony.getState());
        assertEquals(KeriAttestationProblems.IDENTITY_RESET, openCeremony.getErrorTitle());
        verify(ceremonyRepository).save(openCeremony);

        // Global lock order: ceremony before link -- same rule the relink path follows (see
        // KeriOobiService#persistLink's javadoc).
        InOrder lockOrder = inOrder(ceremonyRepository, identityLinkRepository);
        lockOrder.verify(ceremonyRepository).findByIdForUpdate("cer-1");
        lockOrder.verify(identityLinkRepository).findByUserIdForUpdate(USER);
    }

    @Test
    void resetIdentityWithNoLinkIsIdempotentButStillSweepsOpenCeremonies() {
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());

        KeriAttestationCeremonyEntity openCeremony = new KeriAttestationCeremonyEntity();
        openCeremony.setId("cer-2");
        openCeremony.setUserId(USER);
        openCeremony.setState(CeremonyState.OOBI_RESOLVED);
        openCeremony.setUpdatedAt(LocalDateTime.now());
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of(openCeremony));
        when(ceremonyRepository.findByIdForUpdate("cer-2")).thenReturn(Optional.of(openCeremony));

        Either<ProblemDetail, Void> result = service.resetIdentity(USER);

        assertTrue(result.isRight());
        verify(identityLinkRepository, never()).delete(any());
        assertEquals(CeremonyState.FAILED, openCeremony.getState());
        assertEquals(KeriAttestationProblems.IDENTITY_RESET, openCeremony.getErrorTitle());
    }

    @Test
    void resetIdentityWithNoLinkAndNoOpenCeremoniesIsANoOpRightWithoutTouchingTheRepositories() {
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of());

        Either<ProblemDetail, Void> result = service.resetIdentity(USER);

        assertTrue(result.isRight());
        verify(identityLinkRepository, never()).delete(any());
        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void resetIdentityIsOwnerScopedToTheCallingUsersOwnLinkAndCeremonies() {
        // Every repository call resetIdentity makes is keyed by the calling userId alone -- a
        // regression here (e.g. a userId typo/swap) would show up as these exact calls no longer
        // happening for USER.
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of());

        service.resetIdentity(USER);

        verify(ceremonyRepository).findByUserIdAndStateNotIn(eq(USER), any());
        verify(identityLinkRepository).findByUserIdForUpdate(USER);
    }

    // --- resolve/verify failures after validation ---

    @Test
    void contactVerificationFailureAfterResolveIsOobiInvalidAndDoesNotPersist() throws Exception {
        when(contacts.get(AID)).thenReturn(Optional.empty());

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        verify(oobis).resolve(VALID_OOBI, USER);
        verifyNoInteractions(identityLinkRepository);
    }

    // --- FF1 (whole-branch review fast-follow): KERIA transient failures vs. genuinely invalid OOBIs ---

    @Test
    void ioExceptionDuringResolveIsKeriAgentUnavailableWithActionableDetailAndDoesNotPersist() throws Exception {
        // Covers connection-refused/timeout/any other network-level transport failure. The JDK
        // HttpClient underneath SignifyClient#fetch still raises an IOException subclass, but the client
        // wraps it: no signify method declares a checked IOException any more, so an unwrapped one can
        // no longer reach this classifier and testing with one would prove nothing.
        when(oobis.resolve(anyString(), anyString()))
                .thenThrow(new SignifyTransportException("agent unreachable",
                        new java.io.IOException("agent unreachable")));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.KERI_AGENT_UNAVAILABLE, result.getLeft().getTitle());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), result.getLeft().getStatus());
        assertTrue(result.getLeft().getDetail().contains("agent unreachable"));
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void abortSignalTimeoutWhileWaitingOnTheResolveIsKeriAgentUnavailable() throws Exception {
        // The resolve wait's own timeout (RESOLVE_TIMEOUT_MILLIS) fires as a TYPED timeout now -- the
        // agent accepted the resolve request but the long-running operation never completed in time,
        // i.e. the agent is slow/unresponsive, not the request being malformed.
        when(operations.wait(any(Operation.class), any(Operations.WaitOptions.class)))
                .thenThrow(new OperationTimeoutException("oobi-resolve", 15_000L));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.KERI_AGENT_UNAVAILABLE, result.getLeft().getTitle());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), result.getLeft().getStatus());
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void nonTimeoutInterruptedExceptionWhileWaitingOnTheResolveStaysOobiInvalid() throws Exception {
        // A plain thread interruption unrelated to the abort-signal timeout must not be misclassified as
        // agent unavailability -- only a genuine operation timeout/abort is.
        when(operations.wait(any(Operation.class), any(Operations.WaitOptions.class)))
                .thenThrow(new SignifyInterruptedException(new InterruptedException("thread pool shutting down")));

        try {
            Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

            assertTrue(result.isLeft());
            assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), result.getLeft().getStatus());
            // Reported as an ordinary resolve failure, but the flag still has to survive: the caller
            // goes straight on to a notification wait that must not poll out its full timeout.
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must survive the failure");
            verifyNoInteractions(identityLinkRepository);
        } finally {
            // Clear it again, or every later test on this thread inherits an interrupted state.
            Thread.interrupted();
        }
    }

    @Test
    void serverErrorResponseFromTheAgentIsKeriAgentUnavailable() throws Exception {
        // The agent responded, but with an HTTP 5xx -- a server-side failure, not a rejection of the
        // request itself.
        when(oobis.resolve(anyString(), anyString())).thenThrow(
                SignifyAgentException.from("POST", "/oobis", 503, "Service Unavailable"));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.KERI_AGENT_UNAVAILABLE, result.getLeft().getTitle());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), result.getLeft().getStatus());
        verifyNoInteractions(identityLinkRepository);
    }

    @Test
    void clientRejectionResponseFromTheAgentStaysOobiInvalidNotAgentUnavailable() throws Exception {
        // The agent was reachable and understood the request well enough to reject it (HTTP 4xx) -- this
        // is a genuine problem with the OOBI/AID, not agent unavailability, so it must stay OOBI_INVALID.
        when(oobis.resolve(anyString(), anyString())).thenThrow(
                SignifyAgentException.from("POST", "/oobis", 400, "Bad Request"));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), result.getLeft().getStatus());
        verifyNoInteractions(identityLinkRepository);
    }
}
