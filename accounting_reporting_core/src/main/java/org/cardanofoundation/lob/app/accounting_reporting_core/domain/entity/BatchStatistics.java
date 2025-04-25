package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;


import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.envers.Audited;

@Embeddable
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Audited
public class BatchStatistics {

    private int total;
    private int processedTransactions;
    private int pendingTransactions;
    private int approvedTransactions;
    private int publishedTransactions;
    private int invalidTransactions;



}
