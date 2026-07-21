package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
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

    private KeriOobiService service;

    @BeforeEach
    void setUp() throws Exception {
        // Shared "happy path" client stubs — not every test reaches the client (invalid-URL tests
        // must never touch it at all), so these are lenient and simply unused in those cases.
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.oobis()).thenReturn(oobis);
        lenient().when(client.operations()).thenReturn(operations);
        lenient().when(client.contacts()).thenReturn(contacts);
        lenient().when(oobis.resolve(anyString(), anyString())).thenReturn(Map.of("done", true));
        lenient().when(operations.wait(any(), any())).thenReturn(null);
        lenient().when(contacts.get(anyString())).thenReturn(Optional.of(new Object()));

        service = new KeriOobiService(keriClient, identityLinkRepository, ceremonyRepository);
    }

    private KeriIdentityLinkEntity link(int bindingVersion, String aid, String oobiUrl) {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER);
        link.setBindingVersion(bindingVersion);
        link.setAid(aid);
        link.setOobiUrl(oobiUrl);
        return link;
    }

    // --- validation runs before any client call (design §4.3) ---

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
    }

    // --- same AID re-resolve: refresh oobiUrl only, no version bump ---

    @Test
    void reResolvingSameAidRefreshesOobiUrlWithoutBumpingBindingVersion() {
        KeriIdentityLinkEntity existing = link(3, AID, "https://old.example.org/oobi/EAID12345");
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(existing));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isRight());
        assertEquals(AID, result.get());
        assertEquals(3, existing.getBindingVersion());
        assertEquals(VALID_OOBI, existing.getOobiUrl());
        verify(identityLinkRepository).save(existing);
        verifyNoInteractions(ceremonyRepository);
    }

    // --- different AID, relink not requested: IDENTITY_RELINKED conflict ---

    @Test
    void differentAidWithoutRelinkIsIdentityRelinkedConflictAndLeavesLinkUntouched() {
        KeriIdentityLinkEntity existing = link(1, "EOLDAID000", "https://old.example.org/oobi/EOLDAID000");
        when(identityLinkRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(existing));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI_OTHER_AID, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, result.getLeft().getTitle());
        assertEquals(HttpStatus.CONFLICT.value(), result.getLeft().getStatus());
        // Link must not have been mutated or saved.
        assertEquals("EOLDAID000", existing.getAid());
        verify(identityLinkRepository, never()).save(any());
        verifyNoInteractions(ceremonyRepository);
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
    }

    @Test
    void relinkDoesNotClobberACeremonyThatWasConcurrentlyConsumedBetweenTheDiscoveryReadAndTheLock() {
        // Regression guard for the race the row-lock re-check exists for: the unlocked
        // findByUserIdAndStateNotIn discovery read still lists a ceremony that, by the time
        // invalidateOpenCeremonies takes its row lock, a concurrent CeremonyService.validateAndConsume
        // has already legitimately moved to CONSUMED. The stale in-memory candidate must not be used
        // to overwrite that outcome back to FAILED.
        KeriIdentityLinkEntity existing = link(1, "EOLDAID000", "https://old.example.org/oobi/EOLDAID000");
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

    @Test
    void clientExceptionDuringResolveIsOobiInvalidWithActionableDetailAndDoesNotPersist() throws Exception {
        when(oobis.resolve(anyString(), anyString())).thenThrow(new java.io.IOException("agent unreachable"));

        Either<ProblemDetail, String> result = service.resolveUserOobi(USER, VALID_OOBI, false);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.OOBI_INVALID, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("agent unreachable"));
        verifyNoInteractions(identityLinkRepository);
    }
}
