package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;

public interface AccountingCoreTransactionRepository extends JpaRepository<TransactionEntity, String>, CustomTransactionRepository {

    @Query("""
            SELECT t FROM accounting_reporting_core.TransactionEntity t
            WHERE t.organisation.id = :organisationId
            AND t.overallStatus = 'OK'
            AND t.ledgerDispatchStatus = 'NOT_DISPATCHED'
            ORDER BY t.createdAt ASC, t.id ASC
            """)
    Set<TransactionEntity> findDispatchableTransactions(@Param("organisationId") String organisationId,
                                                        Limit limit);

    @Query("""
            SELECT t FROM accounting_reporting_core.TransactionEntity t
            WHERE t.organisation.id = :organisationId
            AND t.entryDate BETWEEN :startDate AND :endDate
            AND (t.reconcilation.source IS NULL OR t.reconcilation.sink IS NULL)
            ORDER BY t.createdAt ASC, t.id ASC
            """)
    Set<TransactionEntity> findByEntryDateRangeAndNotReconciledYet(@Param("organisationId") String organisactionId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    @Query("""
        SELECT t FROM accounting_reporting_core.TransactionEntity t
        WHERE t.organisation.id = :organisationId
        AND t.entryDate BETWEEN :startDate AND :endDate
        AND t.ledgerDispatchStatus <> 'NOT_DISPATCHED'
        """)
    Set<TransactionEntity> findDispatchedTransactionInDateRange(@Param("organisationId") String organisationId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

}
