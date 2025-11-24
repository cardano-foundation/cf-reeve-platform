package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.Map;
import java.util.Optional;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;

public class BlockchainPublishStatusMapperTest {

    private BlockchainPublishStatusMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new BlockchainPublishStatusMapper();
    }

    @Test
    void testConvertWithEmptyInputs() {
        assertThat(mapper.convert(Optional.empty(), Optional.empty()))
                .isEqualTo(LedgerDispatchStatus.NOT_DISPATCHED);
    }

    @Test
    void testConvertWithVariousBlockchainPublishStatusWithoutAssurance() {
        assertAll(
                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.STORED), Optional.empty()))
                        .isEqualTo(LedgerDispatchStatus.MARK_DISPATCH),

                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.ROLLBACKED), Optional.empty()))
                        .isEqualTo(LedgerDispatchStatus.RETRYING),

                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.SUBMITTED), Optional.empty()))
                        .isEqualTo(LedgerDispatchStatus.DISPATCHED),

                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.VISIBLE_ON_CHAIN), Optional.empty()))
                        .isEqualTo(LedgerDispatchStatus.DISPATCHED),

                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), Optional.empty()))
                        .isEqualTo(LedgerDispatchStatus.DISPATCHED),

                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.FINALIZED), Optional.empty()))
                        .isEqualTo(LedgerDispatchStatus.FINALIZED),

                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.ERROR), Optional.empty()))
                        .isEqualTo(LedgerDispatchStatus.FAILED)
        );
    }

    @Test
    void testConvertWithCompletedStatusAndHighAssurance() {
        assertAll(
                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), Optional.of(FinalityScore.VERY_LOW)))
                        .isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), Optional.of(FinalityScore.LOW)))
                        .isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), Optional.of(FinalityScore.HIGH)))
                        .isEqualTo(LedgerDispatchStatus.COMPLETED),
                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), Optional.of(FinalityScore.VERY_HIGH)))
                        .isEqualTo(LedgerDispatchStatus.COMPLETED),
                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), Optional.of(FinalityScore.ULTRA_HIGH)))
                        .isEqualTo(LedgerDispatchStatus.COMPLETED),
                () -> assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), Optional.of(FinalityScore.FINAL)))
                        .isEqualTo(LedgerDispatchStatus.FINALIZED)
        );
    }

    @Test
    void testConvertWithOnlyCardanoFinalityScore() {
        assertAll(
                () -> assertThat(mapper.convertToLedgerDispatchStatus(FinalityScore.VERY_LOW)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convertToLedgerDispatchStatus(FinalityScore.LOW)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convertToLedgerDispatchStatus(FinalityScore.MEDIUM)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convertToLedgerDispatchStatus(FinalityScore.HIGH)).isEqualTo(LedgerDispatchStatus.COMPLETED),
                () -> assertThat(mapper.convertToLedgerDispatchStatus(FinalityScore.VERY_HIGH)).isEqualTo(LedgerDispatchStatus.COMPLETED),
                () -> assertThat(mapper.convertToLedgerDispatchStatus(FinalityScore.ULTRA_HIGH)).isEqualTo(LedgerDispatchStatus.COMPLETED),
                () -> assertThat(mapper.convertToLedgerDispatchStatus(FinalityScore.FINAL)).isEqualTo(LedgerDispatchStatus.FINALIZED)
        );
    }

    @Test
    void testConvertWithOnlyBlockchainPublishStatus() {
        assertAll(
                () -> assertThat(mapper.convert(BlockchainPublishStatus.STORED)).isEqualTo(LedgerDispatchStatus.MARK_DISPATCH),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.ROLLBACKED)).isEqualTo(LedgerDispatchStatus.RETRYING),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.SUBMITTED)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.VISIBLE_ON_CHAIN)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.COMPLETED)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.FINALIZED)).isEqualTo(LedgerDispatchStatus.FINALIZED),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.ERROR)).isEqualTo(LedgerDispatchStatus.FAILED)
        );
    }

    @Test
    void testConvertWithCompletedStatusAndNoFinalityScore() {
        assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), Optional.empty()))
                .isEqualTo(LedgerDispatchStatus.DISPATCHED);
    }

    @Test
    void testConvertWithCompletedStatusAndInvalidFinalityScore() {
        // Assuming we may have an invalid score or handling null
        Optional<FinalityScore> invalidScore = Optional.empty();
        assertThat(mapper.convert(Optional.of(BlockchainPublishStatus.COMPLETED), invalidScore))
                .isEqualTo(LedgerDispatchStatus.DISPATCHED);
    }

    @Test
    void testReverseConvertWithEachLedgerDispatchStatus() {
        assertAll(
                () -> assertThat(mapper.convert(LedgerDispatchStatus.NOT_DISPATCHED)).isEqualTo(BlockchainPublishStatus.STORED),
                () -> assertThat(mapper.convert(LedgerDispatchStatus.MARK_DISPATCH)).isEqualTo(BlockchainPublishStatus.STORED),
                () -> assertThat(mapper.convert(LedgerDispatchStatus.DISPATCHED)).isEqualTo(BlockchainPublishStatus.VISIBLE_ON_CHAIN),
                () -> assertThat(mapper.convert(LedgerDispatchStatus.COMPLETED)).isEqualTo(BlockchainPublishStatus.COMPLETED),
                () -> assertThat(mapper.convert(LedgerDispatchStatus.FINALIZED)).isEqualTo(BlockchainPublishStatus.FINALIZED),
                () -> assertThat(mapper.convert(LedgerDispatchStatus.FAILED)).isEqualTo(BlockchainPublishStatus.ERROR)
        );
    }

    @Test
    void testConvertWithNullInputs() {
        assertThat(mapper.convert(Optional.empty(), Optional.ofNullable(null)))
                .isEqualTo(LedgerDispatchStatus.NOT_DISPATCHED);
    }

    @Test
    void testConvertBlockchainPublishStatusToLedgerDispatchStatus() {
        assertAll(
                () -> assertThat(mapper.convert(BlockchainPublishStatus.STORED)).isEqualTo(LedgerDispatchStatus.MARK_DISPATCH),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.ROLLBACKED)).isEqualTo(LedgerDispatchStatus.RETRYING),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.SUBMITTED)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.VISIBLE_ON_CHAIN)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.COMPLETED)).isEqualTo(LedgerDispatchStatus.DISPATCHED),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.FINALIZED)).isEqualTo(LedgerDispatchStatus.FINALIZED),
                () -> assertThat(mapper.convert(BlockchainPublishStatus.ERROR)).isEqualTo(LedgerDispatchStatus.FAILED)
        );
    }

    @Test
    void testConvert_withAllFinalityScores() {
        // Arrange
        Map<FinalityScore, BlockchainPublishStatus> testCases = Map.of(
                FinalityScore.VERY_LOW, BlockchainPublishStatus.VISIBLE_ON_CHAIN,
                FinalityScore.LOW, BlockchainPublishStatus.VISIBLE_ON_CHAIN,
                FinalityScore.MEDIUM, BlockchainPublishStatus.VISIBLE_ON_CHAIN,
                FinalityScore.HIGH, BlockchainPublishStatus.COMPLETED,
                FinalityScore.VERY_HIGH, BlockchainPublishStatus.COMPLETED,
                FinalityScore.ULTRA_HIGH, BlockchainPublishStatus.COMPLETED,
                FinalityScore.FINAL, BlockchainPublishStatus.FINALIZED
        );

        // Act & Assert
        testCases.forEach((cardanoFinalityScore, expectedStatus) -> {
            val result = mapper.convert(cardanoFinalityScore);
            assertThat(result).isEqualTo(expectedStatus);
        });
    }

}
