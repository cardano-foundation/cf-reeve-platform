package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ReportStatusUpdate {

    private String txId;

    private LedgerDispatchStatus status;

    private String ledgerDispatchStatusErrorReason;

    private Set<BlockchainReceipt> blockchainReceipts = Set.of();

}
