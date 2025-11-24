package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.NOT_DISPATCHED;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;

@Service
@Slf4j
public class BlockchainPublishStatusMapper {

    public LedgerDispatchStatus convert(BlockchainPublishStatus blockchainPublishStatus,
                                        FinalityScore finalityScore) {
        return convert(Optional.of(blockchainPublishStatus), Optional.of(finalityScore));
    }

    public LedgerDispatchStatus convert(BlockchainPublishStatus blockchainPublishStatus) {
        return convert(Optional.of(blockchainPublishStatus), Optional.empty());
    }

    protected LedgerDispatchStatus convertToLedgerDispatchStatus(FinalityScore finalityScore) {
        return switch (finalityScore) {
            case VERY_LOW, LOW, MEDIUM -> LedgerDispatchStatus.DISPATCHED;
            case HIGH, VERY_HIGH, ULTRA_HIGH -> LedgerDispatchStatus.COMPLETED;
            case FINAL -> LedgerDispatchStatus.FINALIZED;
        };
    }

    public BlockchainPublishStatus convert(FinalityScore finalityScore) {
        return switch (finalityScore) {
            case VERY_LOW, LOW, MEDIUM -> BlockchainPublishStatus.VISIBLE_ON_CHAIN;
            case HIGH, VERY_HIGH, ULTRA_HIGH -> BlockchainPublishStatus.COMPLETED;
            case FINAL -> BlockchainPublishStatus.FINALIZED;
        };
    }

    public BlockchainPublishStatus convert(LedgerDispatchStatus ledgerDispatchStatus) {
        Optional<BlockchainPublishStatus> blockchainPublishStatusM = switch (ledgerDispatchStatus) {
            case NOT_DISPATCHED -> Optional.<BlockchainPublishStatus>empty();
            case MARK_DISPATCH -> Optional.of(BlockchainPublishStatus.STORED);
            case DISPATCHED -> Optional.of(BlockchainPublishStatus.VISIBLE_ON_CHAIN);
            case COMPLETED -> Optional.of(BlockchainPublishStatus.COMPLETED);
            case FINALIZED -> Optional.of(BlockchainPublishStatus.FINALIZED);
            case RETRYING -> Optional.of(BlockchainPublishStatus.ROLLBACKED);
            case FAILED -> Optional.of(BlockchainPublishStatus.ERROR);
        };

        return blockchainPublishStatusM.orElse(BlockchainPublishStatus.STORED);
    }

    public LedgerDispatchStatus convert(Optional<BlockchainPublishStatus> blockchainPublishStatus,
                                        Optional<FinalityScore> cardanoFinalityScore) {
        return blockchainPublishStatus.map(status -> {
            return switch (status) {
                case STORED -> LedgerDispatchStatus.MARK_DISPATCH;
                case VISIBLE_ON_CHAIN, SUBMITTED -> LedgerDispatchStatus.DISPATCHED;
                case COMPLETED -> cardanoFinalityScore.map(this::convertToLedgerDispatchStatus)
                        .orElse(LedgerDispatchStatus.DISPATCHED);
                case FINALIZED -> LedgerDispatchStatus.FINALIZED;
                case ROLLBACKED -> LedgerDispatchStatus.RETRYING;
                case ERROR -> LedgerDispatchStatus.FAILED;
            };
        }).orElse(NOT_DISPATCHED);
    }

}
