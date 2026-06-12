package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Optional;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_common.domain.OnChainTxDetails;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublishStatusMapper;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

@ExtendWith(MockitoExtension.class)
class L1SubmissionDataUpdaterTest {

    @Mock
    private BlockchainPublishStatusMapper blockchainPublishStatusMapper;
    @Mock
    private BlockchainReaderPublicApiIF blockchainReaderPublicApi;

    @InjectMocks
    private L1SubmissionDataUpdater updater;

    @Test
    void applyFailure_incrementsRetryAndStaysStored() {
        L1SubmissionData previous = L1SubmissionData.builder().publishRetry(0L).build();

        L1SubmissionData result = updater.applyFailure(Optional.of(previous), "boom");

        assertEquals(1L, result.getPublishRetry());
        assertEquals(Optional.of(BlockchainPublishStatus.STORED), result.getPublishStatus());
        assertEquals(Optional.of("boom"), result.getPublishStatusErrorReason());
    }

    @Test
    void applyFailure_noPreviousDataStartsAtRetryOne() {
        L1SubmissionData result = updater.applyFailure(Optional.empty(), "boom");

        assertEquals(1L, result.getPublishRetry());
        assertEquals(Optional.of(BlockchainPublishStatus.STORED), result.getPublishStatus());
    }

    @Test
    void applyFailure_parksInErrorAfterMaxRetries() {
        L1SubmissionData previous = L1SubmissionData.builder().publishRetry(L1SubmissionDataUpdater.MAX_PUBLISH_RETRIES).build();

        L1SubmissionData result = updater.applyFailure(Optional.of(previous), "boom");

        assertEquals(L1SubmissionDataUpdater.MAX_PUBLISH_RETRIES + 1L, result.getPublishRetry());
        assertEquals(Optional.of(BlockchainPublishStatus.ERROR), result.getPublishStatus());
    }

    @Test
    void updateFromChain_finalizedTransaction() {
        updater.setRollbackGracePeriodMinutes(1);
        L1SubmissionData submissionData = L1SubmissionData.builder().creationSlot(1L).transactionHash("txHash").build();

        when(blockchainReaderPublicApi.getTxDetails(anyString()))
                .thenReturn(Either.right(Optional.of(OnChainTxDetails.builder().finalityScore(FinalityScore.FINAL).build())));
        when(blockchainPublishStatusMapper.convert(FinalityScore.FINAL)).thenReturn(BlockchainPublishStatus.FINALIZED);

        L1SubmissionData result = updater.updateFromChain(submissionData, ChainTip.builder().isSynced(true).absoluteSlot(2L).build());

        assertEquals(Optional.of(BlockchainPublishStatus.FINALIZED), result.getPublishStatus());
        assertEquals(Optional.of(FinalityScore.FINAL), result.getFinalityScore());
        assertEquals(Optional.of("txHash"), result.getTransactionHash());
    }

    @Test
    void updateFromChain_rollbackWhenMissingPastGracePeriod() {
        updater.setRollbackGracePeriodMinutes(1);
        L1SubmissionData submissionData = L1SubmissionData.builder().creationSlot(1L).transactionHash("txHash").build();

        when(blockchainReaderPublicApi.getTxDetails(anyString())).thenReturn(Either.right(Optional.empty()));

        L1SubmissionData result = updater.updateFromChain(submissionData, ChainTip.builder().isSynced(true).absoluteSlot(1000L).build());

        assertEquals(Optional.of(BlockchainPublishStatus.ROLLBACKED), result.getPublishStatus());
        assertNull(result.getTransactionHash().orElse(null));
        assertNull(result.getCreationSlot().orElse(null));
        assertNull(result.getAbsoluteSlot().orElse(null));
        assertNull(result.getFinalityScore().orElse(null));
    }

    @Test
    void updateFromChain_staysSubmittedWithinGracePeriod() {
        updater.setRollbackGracePeriodMinutes(1);
        L1SubmissionData submissionData = L1SubmissionData.builder().creationSlot(1L).transactionHash("txHash").build();

        when(blockchainReaderPublicApi.getTxDetails(anyString())).thenReturn(Either.right(Optional.empty()));

        L1SubmissionData result = updater.updateFromChain(submissionData, ChainTip.builder().isSynced(true).absoluteSlot(5L).build());

        assertEquals(Optional.of(BlockchainPublishStatus.SUBMITTED), result.getPublishStatus());
        assertEquals(Optional.of(FinalityScore.VERY_LOW), result.getFinalityScore());
    }

    @Test
    void updateFromChain_throwsWhenTxDetailsUnavailable() {
        L1SubmissionData submissionData = L1SubmissionData.builder().creationSlot(1L).transactionHash("txHash").build();

        when(blockchainReaderPublicApi.getTxDetails(anyString())).thenReturn(Either.left(null));

        ChainTip chainTip = ChainTip.builder().isSynced(true).absoluteSlot(2L).build();
        assertThrows(RuntimeException.class,
                () -> updater.updateFromChain(submissionData, chainTip));
    }

}
