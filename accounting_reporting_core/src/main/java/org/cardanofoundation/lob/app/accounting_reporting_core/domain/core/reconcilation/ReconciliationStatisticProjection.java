package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation;

public interface ReconciliationStatisticProjection {
    Integer getYear();

    Integer getMonth();

    Long getReconciledCount();

    Long getUnreconciledCount();
}
