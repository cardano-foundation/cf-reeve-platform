package org.cardanofoundation.lob.app.blockchain_common.domain;

import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Blockchain-dispatch status of a single published item (transaction, report or spending event). Consolidates the
 * previously duplicated {@code TxStatusUpdate} / {@code ReportStatusUpdate} / {@code SpendingEventStatusUpdate}.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class LedgerStatusUpdate {

    /** Id of the published item (transaction id / report id / spending event id). */
    private String id;

    private LedgerDispatchStatus status;

    private String ledgerDispatchStatusErrorReason;

    private Set<BlockchainReceipt> blockchainReceipts = Set.of();

}
