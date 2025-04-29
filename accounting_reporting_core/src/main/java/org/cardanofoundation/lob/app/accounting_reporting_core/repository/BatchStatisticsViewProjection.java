package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

public interface BatchStatisticsViewProjection {
    String getBatchId();
    Integer getInvalid();
    Integer getPending();
    Integer getApprove();
    Integer getPublish();
    Integer getPublished();
    Integer getTotal();
}
