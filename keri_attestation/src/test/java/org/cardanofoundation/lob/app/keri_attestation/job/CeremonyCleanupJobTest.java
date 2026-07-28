package org.cardanofoundation.lob.app.keri_attestation.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAttestationProblems;

@ExtendWith(MockitoExtension.class)
class CeremonyCleanupJobTest {

    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;

    private CeremonyCleanupJob job;

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier", null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT1.5S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT15S"), Duration.parse("PT30M"), Duration.parse("PT2S"), Duration.parse("PT3S"),
                Duration.parse("PT2M"), null);
    }

    @BeforeEach
    void setUp() {
        job = new CeremonyCleanupJob(ceremonyRepository, properties());
        lenient().when(ceremonyRepository.findByStateNotInAndExpiresAtBefore(anyCollection(), any()))
                .thenReturn(List.of());
        lenient().when(ceremonyRepository.findByStateInAndUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of());
        lenient().when(ceremonyRepository.deleteByStateInAndUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(0L);
    }

    private KeriAttestationCeremonyEntity ceremony(CeremonyState state, LocalDateTime expiresAt) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId("cer-1");
        ceremony.setUserId("user-1");
        ceremony.setBindingVersion(1);
        ceremony.setTargetType("DOCUMENT");
        ceremony.setTargetId("doc-1");
        ceremony.setState(state);
        ceremony.setAttemptGeneration(0);
        ceremony.setExpiresAt(expiresAt);
        return ceremony;
    }

    @Test
    void sweepTransitionsOverdueNonTerminalCeremoniesToExpired() {
        KeriAttestationCeremonyEntity overdue = ceremony(CeremonyState.ATTEST_REQUESTED, LocalDateTime.now().minusHours(2));
        when(ceremonyRepository.findByStateNotInAndExpiresAtBefore(anyCollection(), any()))
                .thenReturn(List.of(overdue));
        // Re-verified under the lock before writing (see CeremonyCleanupJob's race-safety comment) —
        // here the locked re-read agrees with the unlocked discovery read, so it should still expire.
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(overdue));

        job.sweep();

        assertEquals(CeremonyState.EXPIRED, overdue.getState());
        verify(ceremonyRepository).save(overdue);
    }

    @Test
    void sweepSkipsSavingWhenNothingIsOverdue() {
        job.sweep();

        verify(ceremonyRepository, never()).save(any());
    }

    /**
     * A candidate can legitimately move on between the unlocked discovery read and the locked
     * re-check — e.g. {@code CeremonyService#completeStep} landed a real transition to a terminal
     * state in between. The sweep must not clobber that back to EXPIRED.
     */
    @Test
    void sweepDoesNotExpireACandidateThatMovedOnBeforeTheLockedRecheck() {
        KeriAttestationCeremonyEntity staleView = ceremony(CeremonyState.ATTEST_REQUESTED, LocalDateTime.now().minusHours(2));
        when(ceremonyRepository.findByStateNotInAndExpiresAtBefore(anyCollection(), any()))
                .thenReturn(List.of(staleView));
        KeriAttestationCeremonyEntity currentView = ceremony(CeremonyState.CONSUMED, LocalDateTime.now().minusHours(2));
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(currentView));

        job.sweep();

        assertEquals(CeremonyState.CONSUMED, currentView.getState());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void sweepDeletesTerminalCeremoniesOlderThanRetentionWindow() {
        when(ceremonyRepository.deleteByStateInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(2L);

        job.sweep();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ceremonyRepository).deleteByStateInAndUpdatedAtBefore(anyCollection(), cutoffCaptor.capture());

        LocalDateTime expectedCutoff = LocalDateTime.now().minusDays(7);
        assertTrue(Duration.between(cutoffCaptor.getValue(), expectedCutoff).abs().toSeconds() < 5,
                "cutoff should be ~7 days before now, was " + cutoffCaptor.getValue());
    }

    /** CONSUMED rows are the durable attestation record blockchain_publisher's dispatch retry
     *  reloads on every attempt, well past any reasonable ceremony-completion age -- purging them would
     *  make late dispatch impossible. Only FAILED/EXPIRED may be purged. */
    @Test
    void sweepPurgesOnlyFailedAndExpiredNeverConsumed() {
        when(ceremonyRepository.deleteByStateInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(0L);

        job.sweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<CeremonyState>> statesCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(ceremonyRepository).deleteByStateInAndUpdatedAtBefore(statesCaptor.capture(), any());

        java.util.Collection<CeremonyState> purgedStates = statesCaptor.getValue();
        assertTrue(purgedStates.contains(CeremonyState.FAILED));
        assertTrue(purgedStates.contains(CeremonyState.EXPIRED));
        assertTrue(!purgedStates.contains(CeremonyState.CONSUMED),
                "CONSUMED must never be included in the purge -- it's the durable record dispatch retry reloads");
    }

    // --- step-level stale detection ---

    @Test
    void sweepFailsAnOverdueWaitingCeremonyWithKeriStepTimedOut() {
        // remotesignTimeout=PT3M + stepTimeoutGrace=PT2M = stale past 5 minutes; 10 minutes is well
        // past that for CREDENTIAL_REQUESTED.
        KeriAttestationCeremonyEntity stale = ceremony(CeremonyState.CREDENTIAL_REQUESTED, LocalDateTime.now().plusHours(1));
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        when(ceremonyRepository.findByStateInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(List.of(stale));
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(stale));

        job.sweep();

        assertEquals(CeremonyState.FAILED, stale.getState());
        assertEquals(KeriAttestationProblems.KERI_STEP_TIMED_OUT, stale.getErrorTitle());
        assertTrue(stale.getErrorDetail().contains("CREDENTIAL_REQUESTED"));
        verify(ceremonyRepository).save(stale);
    }

    @Test
    void sweepLeavesAFreshWaitingCeremonyUntouched() {
        KeriAttestationCeremonyEntity fresh = ceremony(CeremonyState.CREDENTIAL_REQUESTED, LocalDateTime.now().plusHours(1));
        fresh.setUpdatedAt(LocalDateTime.now().minusSeconds(5));
        when(ceremonyRepository.findByStateInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(List.of(fresh));
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(fresh));

        job.sweep();

        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, fresh.getState());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void sweepUsesTheLongerAuthBeginRollbackWindowRatherThanTheShorterRemotesignTimeoutForAuthBeginSubmitted() {
        // 10 minutes old is well past remotesignTimeout(3m)+grace(2m)=5m, but nowhere near
        // authBeginRollbackWindow(30m)+grace(2m)=32m — AUTH_BEGIN_SUBMITTED must not be failed yet.
        KeriAttestationCeremonyEntity ceremony = ceremony(CeremonyState.AUTH_BEGIN_SUBMITTED, LocalDateTime.now().plusHours(1));
        ceremony.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        when(ceremonyRepository.findByStateInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(List.of(ceremony));
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(ceremony));

        job.sweep();

        assertEquals(CeremonyState.AUTH_BEGIN_SUBMITTED, ceremony.getState());
        verify(ceremonyRepository, never()).save(any());
    }

    /**
     * CREDENTIAL_REQUESTED legitimately spans up to TWO wallet-approval waits (offer, then
     * grant), so its budget is {@code 2 x remotesignTimeout + grace} = {@code 2x3m+2m = 8m}, not the
     * single-wait {@code remotesignTimeout + grace} = 5m every other waiting state gets. 6 minutes old is
     * past the old (pre-F7) single-wait budget but still within the new doubled one.
     */
    @Test
    void sweepDoesNotFailACredentialRequestedCeremonyWithinTheDoubledTwoPhaseBudget() {
        KeriAttestationCeremonyEntity ceremony =
                ceremony(CeremonyState.CREDENTIAL_REQUESTED, LocalDateTime.now().plusHours(1));
        ceremony.setUpdatedAt(LocalDateTime.now().minusMinutes(6));
        when(ceremonyRepository.findByStateInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(List.of(ceremony));
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(ceremony));

        job.sweep();

        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, ceremony.getState());
        verify(ceremonyRepository, never()).save(any());
    }

    /** Same budget as {@link #sweepDoesNotFailACredentialRequestedCeremonyWithinTheDoubledTwoPhaseBudget},
     *  past its end this time: 9 minutes is past {@code 2x3m+2m = 8m}. */
    @Test
    void sweepFailsACredentialRequestedCeremonyPastTheDoubledTwoPhaseBudget() {
        KeriAttestationCeremonyEntity ceremony =
                ceremony(CeremonyState.CREDENTIAL_REQUESTED, LocalDateTime.now().plusHours(1));
        ceremony.setUpdatedAt(LocalDateTime.now().minusMinutes(9));
        when(ceremonyRepository.findByStateInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(List.of(ceremony));
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(ceremony));

        job.sweep();

        assertEquals(CeremonyState.FAILED, ceremony.getState());
        assertEquals(KeriAttestationProblems.KERI_STEP_TIMED_OUT, ceremony.getErrorTitle());
        verify(ceremonyRepository).save(ceremony);
    }

    /**
     * Mirrors {@link #sweepDoesNotExpireACandidateThatMovedOnBeforeTheLockedRecheck}: the discovery
     * read is unlocked, so a candidate can have legitimately moved on to a resting or terminal state
     * (e.g. a retry's {@code beginStep}, or a completed step) by the time the locked re-check runs —
     * it must be skipped, not clobbered back to FAILED.
     */
    @Test
    void sweepSkipsAWaitingCandidateThatMovedToARestingOrTerminalStateBeforeTheLockedRecheck() {
        KeriAttestationCeremonyEntity staleView = ceremony(CeremonyState.CREDENTIAL_REQUESTED, LocalDateTime.now().plusHours(1));
        staleView.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        when(ceremonyRepository.findByStateInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(List.of(staleView));
        KeriAttestationCeremonyEntity currentView = ceremony(CeremonyState.CREDENTIAL_RECEIVED, LocalDateTime.now().plusHours(1));
        currentView.setUpdatedAt(LocalDateTime.now());
        when(ceremonyRepository.findByIdForUpdate("cer-1")).thenReturn(Optional.of(currentView));

        job.sweep();

        assertEquals(CeremonyState.CREDENTIAL_RECEIVED, currentView.getState());
        verify(ceremonyRepository, never()).save(any());
    }
}
