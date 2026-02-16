package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;

/**
 * Interface for indexer reconciliation services.
 * Implementations provide the ability to reconcile transactions with an on-chain indexer.
 */
public interface IndexerReconcilationServiceIF {

    /**
     * Reconciles transactions from the database with transactions from the On-Chain Indexer.
     *
     * @param organisationId The organisation ID to reconcile
     * @param dateFrom Start date for reconciliation
     * @param dateTo End date for reconciliation
     * @param dbTransactions Transactions from the database to compare
     * @return Either a Problem if the API call fails, or a Map of transaction IDs to their reconciliation status
     */
    Either<Problem, Map<String, IndexerReconcilationResult>> reconcileWithIndexer(
            String organisationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            Set<TransactionEntity> dbTransactions);

    /**
     * Result of reconciliation for a single transaction
     */
    record IndexerReconcilationResult(
            ReconcilationCode status,
            String mismatchReason
    ) {
    }
}
