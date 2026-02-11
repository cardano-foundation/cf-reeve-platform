package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

public record ReconciliationStatisticDto(
        Integer year,
        Integer month,
        Long reconciledCount,
        Long unreconciledCount) {
}
