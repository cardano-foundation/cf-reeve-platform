package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_common.domain.OnChainTxDetails;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.OnChainStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublishStatusMapper;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

/**
 * Shared {@link L1SubmissionData} state transitions used by both the dispatcher (on submission failure) and the
 * watchdog (on-chain status polling). Type-agnostic - it only ever touches {@link L1SubmissionData}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class L1SubmissionDataUpdater {

    /** After this many failed publish attempts an entity is parked in {@link BlockchainPublishStatus#ERROR}. */
    public static final long MAX_PUBLISH_RETRIES = 5L;

    private final BlockchainPublishStatusMapper blockchainPublishStatusMapper;
    private final BlockchainReaderPublicApiIF blockchainReaderPublicApi;

    @Value("${lob.blockchain_publisher.watchdog.rollback.grace.period.minutes:15}")
    @Getter
    @Setter
    private int rollbackGracePeriodMinutes = 15;

    /**
     * Build the {@link L1SubmissionData} to persist after a failed dispatch attempt: increment the retry counter
     * and either keep the entity dispatchable ({@code STORED}) or park it in {@code ERROR} once it has exhausted
     * its retries.
     */
    public L1SubmissionData applyFailure(Optional<L1SubmissionData> previous, String errorReason) {
        long previousRetry = previous.map(L1SubmissionData::getPublishRetry).orElse(0L);

        return L1SubmissionData.builder()
                .publishRetry(previousRetry + 1L)
                .publishStatusErrorReason(errorReason)
                .publishStatus(previousRetry >= MAX_PUBLISH_RETRIES ? BlockchainPublishStatus.ERROR : BlockchainPublishStatus.STORED)
                .build();
    }

    /**
     * Mutate the given {@link L1SubmissionData} according to the current on-chain status of its transaction:
     * advance the finality score, or - if the transaction has vanished past the rollback grace period - reset it
     * back to {@link BlockchainPublishStatus#ROLLBACKED} so it gets re-dispatched.
     */
    public L1SubmissionData updateFromChain(L1SubmissionData submissionData, ChainTip chainTip) {
        Long txCreationSlot = submissionData.getCreationSlot().orElseThrow(() -> new RuntimeException("Failed to get tx creation slot"));
        String txHash = submissionData.getTransactionHash().orElseThrow(() -> new RuntimeException("Failed to get tx hash"));
        log.info("Checking transaction status changes for txHash:{}", txHash);

        Either<ProblemDetail, Optional<OnChainTxDetails>> txDetails = blockchainReaderPublicApi.getTxDetails(txHash);

        Optional<OnChainTxDetails> onChainTxDetails = txDetails.getOrElseThrow(() -> {
            log.error("Failed to get tx details for txHash:{}", txHash);
            return new RuntimeException("Failed to get tx details for txHash:" + txHash);
        });

        OnChainStatus onChainStatus = getOnChainStatus(onChainTxDetails, txCreationSlot, chainTip);

        if (onChainStatus.status().equals(BlockchainPublishStatus.ROLLBACKED)) {
            submissionData.setPublishStatus(BlockchainPublishStatus.ROLLBACKED);
            submissionData.setCreationSlot(null);
            submissionData.setAbsoluteSlot(null);
            submissionData.setTransactionHash(null);
            submissionData.setFinalityScore(null);
        } else {
            submissionData.setFinalityScore(onChainStatus.finalityScore().get());
            submissionData.setPublishStatus(onChainStatus.status());
        }

        return submissionData;
    }

    private OnChainStatus getOnChainStatus(Optional<OnChainTxDetails> onChainTxDetails, Long txCreationSlot, ChainTip chainTip) {
        if (onChainTxDetails.isPresent()) {
            return new OnChainStatus(blockchainPublishStatusMapper.convert(onChainTxDetails.get().getFinalityScore()), Optional.of(onChainTxDetails.get().getFinalityScore()));
        }

        // means tx is not on chain yet or was rolledback
        long txAgeInSlots = chainTip.getAbsoluteSlot() - txCreationSlot;
        // we have a grace period for rollback, this is to avoid premature rollbacks (e.g. when transaction is in the mempool still)
        boolean isRollbackReadyTimewise = txAgeInSlots > (rollbackGracePeriodMinutes * 60L);
        if (isRollbackReadyTimewise) {
            return new OnChainStatus(BlockchainPublishStatus.ROLLBACKED, Optional.empty());
        }

        return new OnChainStatus(BlockchainPublishStatus.SUBMITTED, Optional.of(FinalityScore.VERY_LOW));
    }

}
