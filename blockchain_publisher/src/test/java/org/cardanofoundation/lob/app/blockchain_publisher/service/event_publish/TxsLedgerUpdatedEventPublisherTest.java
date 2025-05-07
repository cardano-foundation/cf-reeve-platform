package org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.springframework.context.ApplicationEventPublisher;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TxsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublishStatusMapper;

class TxsLedgerUpdatedEventPublisherTest {

    private LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private BlockchainPublishStatusMapper blockchainPublishStatusMapper;

    @Captor
    private ArgumentCaptor<TxsLedgerUpdatedEvent> eventCaptor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ledgerUpdatedEventPublisher = new LedgerUpdatedEventPublisher(
                applicationEventPublisher,
                blockchainPublishStatusMapper
        );
    }

    @Test
    void testSendTxLedgerUpdatedEvents_NoTransactions() {
        Set<TransactionEntity> emptyTransactions = Set.of();

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents("org1", emptyTransactions);

        verify(applicationEventPublisher, never()).publishEvent(any(TxsLedgerUpdatedEvent.class));
    }

    @Test
    void testSendTxLedgerUpdatedEvents_SingleTransaction() {
        TransactionEntity transaction = createTransactionEntity(BlockchainPublishStatus.SUBMITTED, FinalityScore.HIGH);
        when(blockchainPublishStatusMapper.convert(any(Optional.class), any(Optional.class)))
                .thenReturn(LedgerDispatchStatus.COMPLETED);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents("org1", Set.of(transaction));

        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        TxsLedgerUpdatedEvent event = eventCaptor.getValue();

        assertThat(event.getOrganisationId()).isEqualTo("org1");
        assertThat(event.getStatusUpdates()).hasSize(1);

        TxStatusUpdate statusUpdate = event.getStatusUpdates().iterator().next();
        assertThat(statusUpdate.getTxId()).isEqualTo(transaction.getId());
        assertThat(statusUpdate.getStatus()).isEqualTo(LedgerDispatchStatus.COMPLETED);
    }

    @Test
    void testSendTxLedgerUpdatedEvents_MultipleTransactions_SingleBatch() {
        TransactionEntity transaction1 = createTransactionEntity(BlockchainPublishStatus.STORED, FinalityScore.MEDIUM);
        TransactionEntity transaction2 = createTransactionEntity(BlockchainPublishStatus.ROLLBACKED, FinalityScore.LOW);
        when(blockchainPublishStatusMapper.convert(any(Optional.class), any(Optional.class)))
                .thenReturn(LedgerDispatchStatus.DISPATCHED);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents("org1", Set.of(transaction1, transaction2));

        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        TxsLedgerUpdatedEvent event = eventCaptor.getValue();

        assertThat(event.getOrganisationId()).isEqualTo("org1");
        assertThat(event.getStatusUpdates()).hasSize(2);
    }

    @Test
    void testSendTxLedgerUpdatedEvents_MultipleTransactions_MultipleBatches() {
        ledgerUpdatedEventPublisher.dispatchBatchSize = 1; // Set batch size to 1 for testing

        TransactionEntity transaction1 = createTransactionEntity(BlockchainPublishStatus.STORED, FinalityScore.MEDIUM);
        TransactionEntity transaction2 = createTransactionEntity(BlockchainPublishStatus.ROLLBACKED, FinalityScore.LOW);
        when(blockchainPublishStatusMapper.convert(any(Optional.class), any(Optional.class)))
                .thenReturn(LedgerDispatchStatus.DISPATCHED);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents("org1", Set.of(transaction1, transaction2));

        verify(applicationEventPublisher, times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).hasSize(2);
    }

    @Test
    void testSendTxLedgerUpdatedEvents_PublishStatusEmpty() {
        TransactionEntity transaction = createTransactionEntity(null, FinalityScore.HIGH);
        when(blockchainPublishStatusMapper.convert(any(Optional.class), any(Optional.class)))
                .thenReturn(LedgerDispatchStatus.NOT_DISPATCHED);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents("org1", Set.of(transaction));

        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        TxsLedgerUpdatedEvent event = eventCaptor.getValue();

        assertThat(event.getOrganisationId()).isEqualTo("org1");
        assertThat(event.getStatusUpdates()).hasSize(1);

        TxStatusUpdate statusUpdate = event.getStatusUpdates().iterator().next();
        assertThat(statusUpdate.getTxId()).isEqualTo(transaction.getId());
        assertThat(statusUpdate.getStatus()).isEqualTo(LedgerDispatchStatus.NOT_DISPATCHED);
    }

    @Test
    void testSendTxLedgerUpdatedEvents_FinalityScoreEmpty() {
        TransactionEntity transaction = createTransactionEntity(BlockchainPublishStatus.STORED, null);
        when(blockchainPublishStatusMapper.convert(any(Optional.class), any(Optional.class)))
                .thenReturn(LedgerDispatchStatus.MARK_DISPATCH);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents("org1", Set.of(transaction));

        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        TxsLedgerUpdatedEvent event = eventCaptor.getValue();

        assertThat(event.getOrganisationId()).isEqualTo("org1");
        assertThat(event.getStatusUpdates()).hasSize(1);

        TxStatusUpdate statusUpdate = event.getStatusUpdates().iterator().next();
        assertThat(statusUpdate.getTxId()).isEqualTo(transaction.getId());
        assertThat(statusUpdate.getStatus()).isEqualTo(LedgerDispatchStatus.MARK_DISPATCH);
    }

    private TransactionEntity createTransactionEntity(@Nullable BlockchainPublishStatus publishStatus,
                                                      @Nullable FinalityScore finalityScore) {
        TransactionEntity transactionEntity = new TransactionEntity();
        transactionEntity.setId(UUID.randomUUID().toString());

        L1SubmissionData submissionData = new L1SubmissionData();
        submissionData.setPublishStatus(publishStatus);
        submissionData.setFinalityScore(finalityScore);

        transactionEntity.setL1SubmissionData(Optional.of(submissionData));

        return transactionEntity;
    }

}
