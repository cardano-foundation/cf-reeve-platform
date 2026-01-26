package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Limit;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_common.domain.OnChainTxDetails;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class WatchDogServiceTest {

    @InjectMocks
    private WatchDogService watchDogService;

    @Mock
    OrganisationPublicApiIF organisationPublicApiIF;
    @Mock
    BlockchainPublishStatusMapper blockchainPublishStatusMapper;
    @Mock
    BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    @Mock
    TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;
    @Mock
    ReportEntityRepositoryGateway reportEntityRepositoryGateway;
    @Mock
    LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @BeforeEach
    public void setup() {
        watchDogService.setRollbackGracePeriodMinutes(1);
    }

    @Test
    void emptyOrganisationListTest() {
        when(organisationPublicApiIF.listAll()).thenReturn(List.of());

        watchDogService.checkTransactionStatusForOrganisations(1);

        verify(organisationPublicApiIF).listAll();
        verifyNoInteractions(blockchainReaderPublicApi);
        verifyNoInteractions(transactionEntityRepositoryGateway);
        verifyNoInteractions(reportEntityRepositoryGateway);
        verifyNoInteractions(blockchainPublishStatusMapper);
        verifyNoInteractions(blockchainReaderPublicApi);
    }

    @Test
    void chainTipNotSyncedTest() {
        when(organisationPublicApiIF.listAll()).thenReturn(List.of(new Organisation()));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(ChainTip.builder().isSynced(false).build()));


        watchDogService.checkTransactionStatusForOrganisations(1);

        verify(organisationPublicApiIF).listAll();
        verify(blockchainReaderPublicApi).getChainTip();
        verifyNoMoreInteractions(organisationPublicApiIF);
        verifyNoInteractions(blockchainPublishStatusMapper);
        verifyNoMoreInteractions(blockchainReaderPublicApi);
        verifyNoInteractions(transactionEntityRepositoryGateway);
        verifyNoInteractions(reportEntityRepositoryGateway);
    }

    @Test
    void chainTipProblemTest() {
        when(organisationPublicApiIF.listAll()).thenReturn(List.of(new Organisation()));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.left(null));

        assertThrows(RuntimeException.class, () -> watchDogService.checkTransactionStatusForOrganisations(1));

        verify(organisationPublicApiIF).listAll();
        verify(blockchainReaderPublicApi).getChainTip();
        verifyNoMoreInteractions(organisationPublicApiIF);
        verifyNoInteractions(blockchainPublishStatusMapper);
        verifyNoMoreInteractions(blockchainReaderPublicApi);
        verifyNoInteractions(transactionEntityRepositoryGateway);
        verifyNoInteractions(reportEntityRepositoryGateway);
    }

    @Test
    void updateTransactionStatusErrorOnChainTxDetails() {
        when(organisationPublicApiIF.listAll()).thenReturn(List.of(new Organisation()));
        when(transactionEntityRepositoryGateway.findDispatchedTransactionsThatAreNotFinalizedYet(isNull(), any())).thenReturn(Set.of(
                TransactionEntity.builder()
                        .l1SubmissionData(L1SubmissionData.builder()
                                .creationSlot(1L)
                                .transactionHash("txHash")
                                .build())
                        .build()));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(ChainTip.builder().isSynced(true).absoluteSlot(2L).build()));
        when(blockchainReaderPublicApi.getTxDetails(anyString())).thenReturn(Either.left(null));

        assertThrows(RuntimeException.class, () -> watchDogService.checkTransactionStatusForOrganisations(1));

        verify(organisationPublicApiIF).listAll();
        verify(blockchainReaderPublicApi).getChainTip();
        verify(transactionEntityRepositoryGateway).findDispatchedTransactionsThatAreNotFinalizedYet(null, Limit.of(1));
        verify(blockchainReaderPublicApi).getTxDetails(anyString());
        verifyNoMoreInteractions(organisationPublicApiIF);
        verifyNoInteractions(blockchainPublishStatusMapper);
        verifyNoMoreInteractions(blockchainReaderPublicApi);
        verifyNoMoreInteractions(transactionEntityRepositoryGateway);
        verifyNoInteractions(reportEntityRepositoryGateway);
    }

    @Test
    void updateTransactionStatusFinalTransaction() {
        TransactionEntity txEntity = TransactionEntity.builder()
                .l1SubmissionData(L1SubmissionData.builder()
                        .creationSlot(1L)
                        .transactionHash("txHash")
                        .build())
                .build();


        when(organisationPublicApiIF.listAll()).thenReturn(List.of(new Organisation()));
        when(transactionEntityRepositoryGateway.findDispatchedTransactionsThatAreNotFinalizedYet(isNull(), any())).thenReturn(Set.of(txEntity));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(ChainTip.builder().isSynced(true).absoluteSlot(2L).build()));
        when(blockchainReaderPublicApi.getTxDetails(anyString())).thenReturn(Either.right(Optional.of(OnChainTxDetails.builder().finalityScore(FinalityScore.FINAL).build())));
        when(blockchainPublishStatusMapper.convert(FinalityScore.FINAL)).thenReturn(BlockchainPublishStatus.FINALIZED);

        watchDogService.checkTransactionStatusForOrganisations(1);
        verify(ledgerUpdatedEventPublisher).sendTxLedgerUpdatedEvents(null,Set.of(txEntity));

        verify(organisationPublicApiIF).listAll();
        verify(blockchainReaderPublicApi).getChainTip();
        verify(transactionEntityRepositoryGateway).findDispatchedTransactionsThatAreNotFinalizedYet(null, Limit.of(1));
        verify(blockchainReaderPublicApi).getTxDetails(anyString());
        verify(blockchainPublishStatusMapper).convert(FinalityScore.FINAL);
        txEntity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .creationSlot(1L)
                .transactionHash("txHash")
                .finalityScore(FinalityScore.FINAL)
                .publishStatus(BlockchainPublishStatus.FINALIZED)
                .build()));
        verify(transactionEntityRepositoryGateway).storeTransactions(Set.of(txEntity));

        verifyNoMoreInteractions(organisationPublicApiIF);
        verifyNoMoreInteractions(blockchainPublishStatusMapper);
        verifyNoMoreInteractions(blockchainReaderPublicApi);
        verifyNoMoreInteractions(transactionEntityRepositoryGateway);
    }

    @Test
    void updateTransactionStatusRollBackTransaction() {
        TransactionEntity txEntity = TransactionEntity.builder()
                .l1SubmissionData(L1SubmissionData.builder()
                        .creationSlot(1L)
                        .transactionHash("txHash")
                        .build())
                .build();


        when(organisationPublicApiIF.listAll()).thenReturn(List.of(new Organisation()));
        when(transactionEntityRepositoryGateway.findDispatchedTransactionsThatAreNotFinalizedYet(isNull(), any())).thenReturn(Set.of(txEntity));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(
                Either.right(
                        ChainTip.builder()
                                .isSynced(true)
                                .absoluteSlot(20L)
                                .build()));

        when(blockchainReaderPublicApi.getTxDetails(anyString())).thenReturn(Either.right(Optional.empty()));

        watchDogService.checkTransactionStatusForOrganisations(1);

        verify(organisationPublicApiIF).listAll();
        verify(blockchainReaderPublicApi).getChainTip();
        verify(transactionEntityRepositoryGateway).findDispatchedTransactionsThatAreNotFinalizedYet(null, Limit.of(1));
        verify(blockchainReaderPublicApi).getTxDetails(anyString());
        txEntity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .creationSlot(null)
                .transactionHash(null)
                .finalityScore(null)
                .publishStatus(BlockchainPublishStatus.ROLLBACKED)
                .build()));
        verify(transactionEntityRepositoryGateway).storeTransactions(Set.of(txEntity));

        verifyNoMoreInteractions(organisationPublicApiIF);
        verifyNoInteractions(blockchainPublishStatusMapper);
        verifyNoMoreInteractions(blockchainReaderPublicApi);
        verifyNoMoreInteractions(transactionEntityRepositoryGateway);
    }

    @Test
    void updateTransactionStatusNotOnChainTransaction() {
        TransactionEntity txEntity = TransactionEntity.builder()
                .l1SubmissionData(L1SubmissionData.builder()
                        .creationSlot(1L)
                        .transactionHash("txHash")
                        .build())
                .build();


        when(organisationPublicApiIF.listAll()).thenReturn(List.of(new Organisation()));
        when(transactionEntityRepositoryGateway.findDispatchedTransactionsThatAreNotFinalizedYet(isNull(), any())).thenReturn(Set.of(txEntity));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(
                Either.right(
                        ChainTip.builder()
                                .isSynced(true)
                                .absoluteSlot(5L)
                                .build()));

        when(blockchainReaderPublicApi.getTxDetails(anyString())).thenReturn(Either.right(Optional.empty()));

        watchDogService.checkTransactionStatusForOrganisations(1);

        verify(organisationPublicApiIF).listAll();
        verify(blockchainReaderPublicApi).getChainTip();
        verify(transactionEntityRepositoryGateway).findDispatchedTransactionsThatAreNotFinalizedYet(null, Limit.of(1));
        verify(blockchainReaderPublicApi).getTxDetails(anyString());
        txEntity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .creationSlot(1l)
                .transactionHash("txHash")
                .finalityScore(FinalityScore.VERY_LOW)
                .publishStatus(BlockchainPublishStatus.SUBMITTED)
                .build()));
        verify(transactionEntityRepositoryGateway).storeTransactions(Set.of(txEntity));

        verifyNoMoreInteractions(organisationPublicApiIF);
        verifyNoInteractions(blockchainPublishStatusMapper);
        verifyNoMoreInteractions(blockchainReaderPublicApi);
        verifyNoMoreInteractions(transactionEntityRepositoryGateway);
    }

}
