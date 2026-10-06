package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;

@ExtendWith(MockitoExtension.class)
class SpendingEventLedgerUpdateHandlerTest {

    @Mock
    private FundingEventRepository spendingEventRepository;
    @Mock
    private SpendingEventOnChainLookup onChainLookup;

    @InjectMocks
    private SpendingEventLedgerUpdateHandler handler;

    private LedgerUpdatedEvent eventFor(LedgerUpdateType type, LedgerStatusUpdate... updates) {
        return LedgerUpdatedEvent.builder()
                .organisationId("org1")
                .type(type)
                .statusUpdates(Set.of(updates))
                .build();
    }

    @Test
    void knownEvent_updatesLedgerStatusAndTxHash() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .status(EventStatus.PUBLISHED).eventType(EventType.SPENDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED)
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.DISPATCHED, null,
                        Set.of(new BlockchainReceipt("CARDANO_L1", "tx-hash-1")))));

        assertThat(entity.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.DISPATCHED);
        assertThat(entity.getTxHash()).isEqualTo("tx-hash-1");
        assertThat(entity.getLedgerDispatchStatusErrorReason()).isNull();
        verify(spendingEventRepository).save(entity);
    }

    @Test
    void knownEvent_failure_revertsToDraftWithFailureDetail() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .organisationId("org1")
                .status(EventStatus.PUBLISHED).eventType(EventType.FUNDING)
                .ledgerDispatchApproved(true)
                .ledgerDispatchStatus(LedgerDispatchStatus.DISPATCHED)
                .publishedAt(LocalDateTime.now().minusHours(1))
                .txHash("stale-hash")
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));
        when(onChainLookup.findOnChainTxHash("org1", "event-1")).thenReturn(Optional.empty());
        LocalDateTime before = LocalDateTime.now();

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.FAILED, "Cardano node timeout", Set.of())));

        assertThat(entity.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(entity.isLedgerDispatchApproved()).isFalse();
        assertThat(entity.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.FAILED);
        assertThat(entity.getLedgerDispatchStatusErrorReason()).isEqualTo("Cardano node timeout");
        assertThat(entity.getLastFailureMessage()).isEqualTo("Cardano node timeout");
        assertThat(entity.getLastFailureAt()).isAfterOrEqualTo(before);
        assertThat(entity.getTxHash()).isNull();
        assertThat(entity.getPublishedAt()).isNull();
        verify(spendingEventRepository).save(entity);
    }

    @Test
    void knownEvent_failureButAlreadyOnChain_staysPublished() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .organisationId("org1")
                .status(EventStatus.PUBLISHED).eventType(EventType.SPENDING)
                .ledgerDispatchApproved(true)
                .ledgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH)
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));
        when(onChainLookup.findOnChainTxHash("org1", "event-1")).thenReturn(Optional.of("landed-hash"));

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.FAILED, "Cardano node timeout", Set.of())));

        assertThat(entity.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(entity.isLedgerDispatchApproved()).isTrue();
        assertThat(entity.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.DISPATCHED);
        assertThat(entity.getTxHash()).isEqualTo("landed-hash");
        assertThat(entity.getLastFailureMessage()).isNull();
        verify(spendingEventRepository).save(entity);
    }

    @Test
    void notPublishedEvent_lateUpdateIsIgnored() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .status(EventStatus.DRAFT).eventType(EventType.SPENDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.FAILED)
                .lastFailureMessage("Cardano node timeout")
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.MARK_DISPATCH, null, Set.of())));

        assertThat(entity.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.FAILED);
        assertThat(entity.getLastFailureMessage()).isEqualTo("Cardano node timeout");
        verify(spendingEventRepository, never()).save(entity);
    }

    @Test
    void republishedEvent_reachingTheChain_clearsLastFailure() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .status(EventStatus.PUBLISHED).eventType(EventType.SPENDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED)
                .lastFailureMessage("rejected")
                .lastFailureAt(LocalDateTime.of(2026, 10, 5, 12, 0))
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.DISPATCHED, null,
                        Set.of(new BlockchainReceipt("CARDANO_L1", "tx-hash-ok")))));

        assertThat(entity.getLastFailureMessage()).isNull();
        assertThat(entity.getLastFailureAt()).isNull();
        assertThat(entity.getTxHash()).isEqualTo("tx-hash-ok");
    }

    @Test
    void republishedEvent_stillPending_keepsLastFailure() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .status(EventStatus.PUBLISHED).eventType(EventType.SPENDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED)
                .lastFailureMessage("rejected")
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.MARK_DISPATCH, "retrying", Set.of())));

        assertThat(entity.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.MARK_DISPATCH);
        assertThat(entity.getLastFailureMessage()).isEqualTo("rejected");
    }

    @Test
    void knownEvent_recoveryAfterError_clearsErrorReason() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .status(EventStatus.PUBLISHED).eventType(EventType.FUNDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.FAILED)
                .ledgerDispatchStatusErrorReason("Cardano node timeout")
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.DISPATCHED, null,
                        Set.of(new BlockchainReceipt("CARDANO_L1", "tx-hash-ok")))));

        assertThat(entity.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.DISPATCHED);
        assertThat(entity.getLedgerDispatchStatusErrorReason()).isNull();
        assertThat(entity.getTxHash()).isEqualTo("tx-hash-ok");
        verify(spendingEventRepository).save(entity);
    }

    @Test
    void unknownEvent_isIgnored() {
        when(spendingEventRepository.findById("missing")).thenReturn(Optional.empty());

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("missing", LedgerDispatchStatus.DISPATCHED, null, Set.of())));

        verify(spendingEventRepository).findById("missing");
        verify(spendingEventRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonSpendingEventType_isIgnored() {
        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.TRANSACTION,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.DISPATCHED, null, Set.of())));

        verify(spendingEventRepository, never()).findById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void knownEvent_allReceiptsHaveNullHash_txHashNotSet() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .status(EventStatus.PUBLISHED).eventType(EventType.SPENDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED)
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.DISPATCHED, null,
                        Set.of(new BlockchainReceipt("CARDANO_L1", null)))));

        assertThat(entity.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.DISPATCHED);
        assertThat(entity.getTxHash()).isNull();
        verify(spendingEventRepository).save(entity);
    }

    @Test
    void knownEvent_noReceipts_txHashNotSet() {
        FundingEventEntity entity = FundingEventEntity.builder()
                .id("event-1")
                .status(EventStatus.PUBLISHED).eventType(EventType.SPENDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED)
                .build();
        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity));

        handler.handleLedgerUpdatedEvent(eventFor(LedgerUpdateType.SPENDING_EVENT,
                new LedgerStatusUpdate("event-1", LedgerDispatchStatus.DISPATCHED, null, Set.of())));

        assertThat(entity.getTxHash()).isNull();
        verify(spendingEventRepository).save(entity);
    }

    @Test
    void multipleUpdatesInOneEvent_allProcessed() {
        FundingEventEntity entity1 = FundingEventEntity.builder()
                .id("event-1").status(EventStatus.PUBLISHED).eventType(EventType.SPENDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED).build();
        FundingEventEntity entity2 = FundingEventEntity.builder()
                .id("event-2").status(EventStatus.PUBLISHED).eventType(EventType.SPENDING)
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED).build();

        when(spendingEventRepository.findById("event-1")).thenReturn(Optional.of(entity1));
        when(spendingEventRepository.findById("event-2")).thenReturn(Optional.of(entity2));

        LedgerUpdatedEvent event = LedgerUpdatedEvent.builder()
                .organisationId("org1")
                .type(LedgerUpdateType.SPENDING_EVENT)
                .statusUpdates(Set.of(
                        new LedgerStatusUpdate("event-1", LedgerDispatchStatus.DISPATCHED, null,
                                Set.of(new BlockchainReceipt("CARDANO_L1", "hash-1"))),
                        new LedgerStatusUpdate("event-2", LedgerDispatchStatus.FINALIZED, null,
                                Set.of(new BlockchainReceipt("CARDANO_L1", "hash-2")))
                ))
                .build();

        handler.handleLedgerUpdatedEvent(event);

        assertThat(entity1.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.DISPATCHED);
        assertThat(entity1.getTxHash()).isEqualTo("hash-1");
        assertThat(entity2.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.FINALIZED);
        assertThat(entity2.getTxHash()).isEqualTo("hash-2");
        verify(spendingEventRepository).save(entity1);
        verify(spendingEventRepository).save(entity2);
    }

}
