package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransactionEntityExcludedFieldTest {

    @Test
    void excluded_isNullByDefault_whenCreatedViaNoArgsConstructor() {
        TransactionEntity entity = new TransactionEntity();

        assertThat(entity.getExcludedReport()).isNull();
    }

    @Test
    void excluded_isNullByDefault_whenCreatedViaBuilder() {
        TransactionEntity entity = TransactionEntity.builder()
                .id("test-id")
                .build();

        assertThat(entity.getExcludedReport()).isNull();
    }

    @Test
    void excluded_canBeSetToTrue_viaBuilder() {
        TransactionEntity entity = TransactionEntity.builder()
                .id("test-id")
                .excludedReport(true)
                .build();

        assertThat(entity.getExcludedReport()).isTrue();
    }

    @Test
    void excluded_canBeSetToFalse_viaBuilder() {
        TransactionEntity entity = TransactionEntity.builder()
                .id("test-id")
                .excludedReport(false)
                .build();

        assertThat(entity.getExcludedReport()).isFalse();
    }

    @Test
    void excluded_canBeSetToNull_viaBuilder() {
        TransactionEntity entity = TransactionEntity.builder()
                .id("test-id")
                .excludedReport(null)
                .build();

        assertThat(entity.getExcludedReport()).isNull();
    }

    @Test
    void excluded_setter_setsToTrue() {
        TransactionEntity entity = new TransactionEntity();

        entity.setExcludedReport(true);

        assertThat(entity.getExcludedReport()).isTrue();
    }

    @Test
    void excluded_setter_setsToFalse() {
        TransactionEntity entity = new TransactionEntity();

        entity.setExcludedReport(false);

        assertThat(entity.getExcludedReport()).isFalse();
    }

    @Test
    void excluded_setter_setsToNull() {
        TransactionEntity entity = TransactionEntity.builder()
                .id("test-id")
                .excludedReport(true)
                .build();

        entity.setExcludedReport(null);

        assertThat(entity.getExcludedReport()).isNull();
    }

    @Test
    void excluded_setter_canToggleValue() {
        TransactionEntity entity = new TransactionEntity();

        entity.setExcludedReport(true);
        assertThat(entity.getExcludedReport()).isTrue();

        entity.setExcludedReport(false);
        assertThat(entity.getExcludedReport()).isFalse();

        entity.setExcludedReport(null);
        assertThat(entity.getExcludedReport()).isNull();
    }

    @Test
    void excluded_nullIsTreatedAsNotExcluded_byThreeValuedLogic() {
        // Verify that null behaves as "not excluded" in Java boolean logic
        // This mirrors the SQL "IS NOT TRUE" semantics used in repository queries
        TransactionEntity entityWithNullExcluded = TransactionEntity.builder()
                .id("test-id")
                .excludedReport(null)
                .build();

        TransactionEntity entityWithFalseExcluded = TransactionEntity.builder()
                .id("test-id-2")
                .excludedReport(false)
                .build();

        TransactionEntity entityWithTrueExcluded = TransactionEntity.builder()
                .id("test-id-3")
                .excludedReport(true)
                .build();

        // Both null and false are "not excluded" (IS NOT TRUE = TRUE)
        assertThat(entityWithNullExcluded.getExcludedReport()).isNotEqualTo(Boolean.TRUE);
        assertThat(entityWithFalseExcluded.getExcludedReport()).isNotEqualTo(Boolean.TRUE);

        // Only true is "excluded" (IS NOT TRUE = FALSE)
        assertThat(entityWithTrueExcluded.getExcludedReport()).isEqualTo(Boolean.TRUE);
    }

}
