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

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;

@ExtendWith(MockitoExtension.class)
class CeremonyCleanupJobTest {

    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;

    private CeremonyCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new CeremonyCleanupJob(ceremonyRepository);
        lenient().when(ceremonyRepository.findByStateNotInAndExpiresAtBefore(anyCollection(), any()))
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
}
