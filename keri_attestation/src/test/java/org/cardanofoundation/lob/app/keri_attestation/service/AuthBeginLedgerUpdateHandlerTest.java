package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;

/**
 * Closes the AUTH_BEGIN loop: the publisher reports what it published, and this advances the ceremony.
 * The entity id on the ledger update IS the ceremony id — that is the only correlation handle the two
 * modules share across a process boundary.
 */
@ExtendWith(MockitoExtension.class)
class AuthBeginLedgerUpdateHandlerTest {

    private static final String CEREMONY_ID = "cer-1";
    private static final String USER_ID = "user-1";
    private static final String ORG_ID = "org-1";
    private static final int GENERATION = 3;
    private static final int BINDING_VERSION = 7;
    private static final String TX_HASH = "a".repeat(64);

    @Mock
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Mock
    private KeriIdentityLinkRepository identityLinkRepository;
    @Mock
    private CeremonyService ceremonyService;

    private AuthBeginLedgerUpdateHandler handler() {
        return new AuthBeginLedgerUpdateHandler(ceremonyRepository, identityLinkRepository, ceremonyService);
    }

    private static KeriAttestationCeremonyEntity ceremony() {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(CEREMONY_ID);
        ceremony.setUserId(USER_ID);
        ceremony.setOrganisationId(ORG_ID);
        ceremony.setState(CeremonyState.AUTH_BEGIN_SUBMITTED);
        ceremony.setAttemptGeneration(GENERATION);
        ceremony.setBindingVersion(BINDING_VERSION);
        return ceremony;
    }

    private static LedgerUpdatedEvent event(LedgerUpdateType type, LedgerDispatchStatus status, String txHash) {
        LedgerStatusUpdate update = new LedgerStatusUpdate(CEREMONY_ID, status, null,
                txHash == null ? Set.of() : Set.of(new BlockchainReceipt("CARDANO_L1", txHash)));

        return LedgerUpdatedEvent.builder()
                .organisationId(ORG_ID)
                .type(type)
                .statusUpdates(Set.of(update))
                .build();
    }

    @Test
    void ignoresLedgerUpdatesForOtherPublishableTypes() {
        handler().handleLedgerUpdatedEvent(event(LedgerUpdateType.DOCUMENT, LedgerDispatchStatus.DISPATCHED, TX_HASH));

        verifyNoInteractions(ceremonyRepository, ceremonyService, identityLinkRepository);
    }

    @Test
    void dispatchedCompletesTheStepAndRecordsTheTxHashOnTheLink() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony()));
        when(ceremonyService.completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any())).thenReturn(true);

        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId(USER_ID);
        link.setBindingVersion(BINDING_VERSION);
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(link));

        handler().handleLedgerUpdatedEvent(event(LedgerUpdateType.AUTH_BEGIN, LedgerDispatchStatus.DISPATCHED, TX_HASH));

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutator = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(CeremonyState.AUTH_BEGIN_CONFIRMED), mutator.capture());

        KeriAttestationCeremonyEntity target = ceremony();
        mutator.getValue().accept(target);

        assertEquals(TX_HASH, target.getAuthBeginTxHash());
        assertEquals(TX_HASH, link.getAuthBeginTxHash());
        verify(identityLinkRepository).save(link);
    }

    /** A relink while the transaction was in flight must not have its link overwritten. */
    @Test
    void relinkedIdentitySkipsTheLinkWriteButStillCompletesTheCeremony() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony()));
        when(ceremonyService.completeStep(any(), anyInt(), any(), any(), any())).thenReturn(true);

        KeriIdentityLinkEntity relinked = new KeriIdentityLinkEntity();
        relinked.setUserId(USER_ID);
        relinked.setBindingVersion(BINDING_VERSION + 1);
        when(identityLinkRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(relinked));

        handler().handleLedgerUpdatedEvent(event(LedgerUpdateType.AUTH_BEGIN, LedgerDispatchStatus.DISPATCHED, TX_HASH));

        ArgumentCaptor<Consumer<KeriAttestationCeremonyEntity>> mutator = ArgumentCaptor.forClass(Consumer.class);
        verify(ceremonyService).completeStep(any(), anyInt(), any(), any(), mutator.capture());
        mutator.getValue().accept(ceremony());

        verify(identityLinkRepository, never()).save(any());
    }

    @Test
    void failedDispatchFailsTheStep() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony()));

        handler().handleLedgerUpdatedEvent(event(LedgerUpdateType.AUTH_BEGIN, LedgerDispatchStatus.FAILED, null));

        verify(ceremonyService).failStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                eq(KeriAttestationProblems.AUTH_BEGIN_ROLLED_BACK), any());
        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
    }

    /** MARK_DISPATCH means stored-not-published; the ceremony must keep waiting. */
    @Test
    void notYetOnChainLeavesTheCeremonyWaiting() {
        handler().handleLedgerUpdatedEvent(
                event(LedgerUpdateType.AUTH_BEGIN, LedgerDispatchStatus.MARK_DISPATCH, null));

        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
        verify(ceremonyService, never()).failStep(any(), anyInt(), any(), any(), any());
    }

    @Test
    void unknownCeremonyIsIgnored() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.empty());

        handler().handleLedgerUpdatedEvent(event(LedgerUpdateType.AUTH_BEGIN, LedgerDispatchStatus.DISPATCHED, TX_HASH));

        verify(ceremonyService, never()).completeStep(any(), anyInt(), any(), any(), any());
    }

    /** COMPLETED sits between DISPATCHED and FINALIZED and must also complete the step. */
    @Test
    void completedAlsoCompletesTheStep() {
        when(ceremonyRepository.findById(CEREMONY_ID)).thenReturn(Optional.of(ceremony()));
        when(ceremonyService.completeStep(any(), anyInt(), any(), any(), any())).thenReturn(true);

        for (LedgerDispatchStatus status : List.of(LedgerDispatchStatus.COMPLETED, LedgerDispatchStatus.FINALIZED)) {
            handler().handleLedgerUpdatedEvent(event(LedgerUpdateType.AUTH_BEGIN, status, TX_HASH));
        }

        verify(ceremonyService, org.mockito.Mockito.times(2))
                .completeStep(eq(CEREMONY_ID), eq(GENERATION), eq(CeremonyState.AUTH_BEGIN_SUBMITTED),
                        eq(CeremonyState.AUTH_BEGIN_CONFIRMED), any());
    }
}
