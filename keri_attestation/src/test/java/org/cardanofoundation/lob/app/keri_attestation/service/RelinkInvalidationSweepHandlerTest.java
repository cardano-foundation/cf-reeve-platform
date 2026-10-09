package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;

/**
 * {@link RelinkInvalidationSweepHandler#onRelinkCompleted} is the
 * AFTER_COMMIT second pass that closes the phantom-insert window {@code KeriOobiService}'s
 * in-transaction sweep cannot — see that class's javadoc for the full race. Exercised directly here
 * (not through a real transactional event) since the annotation shape itself only needs a real
 * Spring context to prove wiring, not behavior; the sweep logic is plain and unit-testable like every
 * other CAS/re-verify method in this package.
 */
@ExtendWith(MockitoExtension.class)
class RelinkInvalidationSweepHandlerTest {

    private static final String USER = "user-1";

    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;

    private RelinkInvalidationSweepHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RelinkInvalidationSweepHandler(ceremonyRepository);
    }

    private KeriAttestationCeremonyEntity ceremony(String id, CeremonyState state, int bindingVersion) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(id);
        ceremony.setUserId(USER);
        ceremony.setState(state);
        ceremony.setBindingVersion(bindingVersion);
        ceremony.setUpdatedAt(LocalDateTime.now());
        return ceremony;
    }

    @Test
    void ceremonyWithStaleBindingVersionIsFailedWithIdentityRelinked() {
        KeriAttestationCeremonyEntity stale = ceremony("cer-stale", CeremonyState.CREDENTIAL_REQUESTED, 1);
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of(stale));
        when(ceremonyRepository.findByIdForUpdate("cer-stale")).thenReturn(java.util.Optional.of(stale));

        handler.onRelinkCompleted(new RelinkCompletedEvent(USER, 2));

        assertEquals(CeremonyState.FAILED, stale.getState());
        assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, stale.getErrorTitle());
        verify(ceremonyRepository).save(stale);
    }

    @Test
    void ceremonyAtTheCurrentBindingVersionIsLeftUntouched() {
        // Adopted the new binding already (e.g. KeriOobiService's own in-transaction sweep, or a
        // ceremony created after the relink committed) - must never be re-failed by the AFTER_COMMIT
        // pass just because it happens to share the user.
        KeriAttestationCeremonyEntity current = ceremony("cer-current", CeremonyState.CREDENTIAL_REQUESTED, 2);
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of(current));
        when(ceremonyRepository.findByIdForUpdate("cer-current")).thenReturn(java.util.Optional.of(current));

        handler.onRelinkCompleted(new RelinkCompletedEvent(USER, 2));

        assertEquals(CeremonyState.CREDENTIAL_REQUESTED, current.getState());
        verify(ceremonyRepository, never()).save(any());
    }

    @Test
    void aTerminalCeremonyFoundUnderTheLockIsNeverReFailed() {
        // Regression guard mirroring KeriOobiService#invalidateOpenCeremonies: the unlocked discovery
        // read can return a stale candidate that has since (legitimately) reached CONSUMED by the time
        // the row lock is taken - must not be clobbered back to FAILED.
        KeriAttestationCeremonyEntity staleCandidate = ceremony("cer-2", CeremonyState.CREDENTIAL_REQUESTED, 1);
        when(ceremonyRepository.findByUserIdAndStateNotIn(eq(USER), any())).thenReturn(List.of(staleCandidate));
        KeriAttestationCeremonyEntity nowConsumed = ceremony("cer-2", CeremonyState.CONSUMED, 1);
        when(ceremonyRepository.findByIdForUpdate("cer-2")).thenReturn(java.util.Optional.of(nowConsumed));

        handler.onRelinkCompleted(new RelinkCompletedEvent(USER, 2));

        assertEquals(CeremonyState.CONSUMED, nowConsumed.getState());
        verify(ceremonyRepository, never()).save(nowConsumed);
    }
}
