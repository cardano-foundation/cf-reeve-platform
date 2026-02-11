package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionWithViolationDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconciliationStatisticProjection;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;

public interface ReconcilationRepository extends JpaRepository<ReconcilationEntity, String> {

    @Query("""
            SELECT new org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionWithViolationDto(tr, rv)
            FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
            JOIN r.violations rv
            LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
            WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation.id IS NULL) AND ((rv.rejectionCode = 'TX_NOT_IN_ERP' AND tr.ledgerDispatchApproved = true) OR (rv.rejectionCode != 'TX_NOT_IN_ERP'))
            AND (:rejectionCodes IS NULL OR rv.rejectionCode IN :rejectionCodes)
            AND (CAST(:startDate AS date) IS NULL OR tr.entryDate >= :startDate OR rv.transactionEntryDate >= :startDate)
            AND (CAST(:endDate AS date) IS NULL OR tr.entryDate <= :endDate OR rv.transactionEntryDate <= :endDate)
            AND (:source IS NULL OR ( :source = 'ERP' AND tr.reconcilation.source = 'OK' ) OR ( :source = 'BLOCKCHAIN' AND tr.reconcilation.sink = 'OK') )
            AND (:transactionTypes IS NULL OR tr.transactionType IN :transactionTypes OR rv.transactionType IN :transactionTypes)
            AND (:transactionId IS NULL OR LOWER(tr.internalTransactionNumber) LIKE LOWER(CONCAT('%', CAST(:transactionId AS string), '%')) OR LOWER(rv.transactionInternalNumber) LIKE LOWER(CONCAT('%', CAST(:transactionId AS string), '%')))
            """)
    Page<TransactionWithViolationDto> findAllReconciliationSpecial(
            @Param("rejectionCodes") Set<ReconcilationRejectionCode> rejectionCodes,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("source") String source,
            @Param("transactionTypes") Set<TransactionType> transactionTypes,
            @Param("transactionId") String transactionId,
            Pageable pageable);

    @Query(value = """
            SELECT tr
            FROM accounting_reporting_core.TransactionEntity tr
            WHERE
                (CAST(:startDate AS date) IS NULL OR tr.entryDate >= :startDate)
                AND (CAST(:endDate AS date) IS NULL OR tr.entryDate <= :endDate)
                AND (:transactionTypes IS NULL OR tr.transactionType IN :transactionTypes)
                AND (:transactionId IS NULL OR LOWER(tr.id) LIKE LOWER(CONCAT('%', CAST(:transactionId AS string), '%')))
                AND (:filter = 'RECONCILED'
                    AND tr.reconcilation.finalStatus = 'OK'
                    AND (:source IS NULL
                         OR (:source = 'ERP' AND tr.reconcilation.source = 'OK')
                         OR (:source = 'BLOCKCHAIN' AND tr.reconcilation.sink = 'OK'))
                    )
                OR (:filter = 'UNRECONCILED'
                    AND tr.reconcilation.source IS NULL
                    )
            """, countQuery = """
            SELECT COUNT(tr)
            FROM accounting_reporting_core.TransactionEntity tr
            WHERE
                (:filter = 'RECONCILED'
                    AND tr.reconcilation.finalStatus = 'OK'
                    AND (:source IS NULL
                         OR (:source = 'ERP' AND tr.reconcilation.source = 'OK')
                         OR (:source = 'BLOCKCHAIN' AND tr.reconcilation.sink = 'OK')))
                OR (:filter = 'UNRECONCILED'
                    AND tr.reconcilation.source IS NULL)
            """)
    Page<TransactionEntity> findAllReconcilation(
            @Param("filter") String filter,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("transactionTypes") Set<TransactionType> transactionTypes,
            @Param("transactionId") String transactionId,
            @Param("source") String source, Pageable pageable);

    @Query("""
                SELECT
                (SELECT COUNT(missingInERP) FROM (
                    SELECT rv.transactionId missingInERP
                    FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
                    JOIN r.violations rv
                    LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
                    WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation IS NULL)
                    AND tr.ledgerDispatchApproved IS TRUE
                    AND rv.rejectionCode = 'TX_NOT_IN_ERP'
                    GROUP BY rv.transactionId)
                ) as missingInERP,

                (SELECT COUNT(inProcessing) FROM (
                    SELECT rv.transactionId inProcessing
                    FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
                    JOIN r.violations rv
                    LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
                    WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation IS NULL)
                    AND rv.rejectionCode = 'SINK_RECONCILATION_FAIL'
                    GROUP BY rv.transactionId)
                ) as inProcessing,

                (SELECT COUNT(newInERP) FROM (
                    SELECT rv.transactionId newInERP
                    FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
                    JOIN r.violations rv
                    LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
                    WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation IS NULL)
                    AND rv.rejectionCode = 'TX_NOT_IN_LOB'
                    GROUP BY rv.transactionId)
                ) as newInERP,

                (SELECT COUNT(newVersionNotPublished) FROM (
                    SELECT rv.transactionId newVersionNotPublished
                    FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
                    JOIN r.violations rv
                    LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
                    WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation IS NULL)
                    AND rv.rejectionCode = 'SOURCE_RECONCILATION_FAIL'
                    AND tr.ledgerDispatchApproved IS FALSE
                    GROUP BY rv.transactionId)
                ) as newVersionNotPublished,

                (SELECT COUNT(newVersion) FROM (
                    SELECT rv.transactionId newVersion
                    FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
                    JOIN r.violations rv
                    LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
                    WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation IS NULL)
                    AND rv.rejectionCode = 'SOURCE_RECONCILATION_FAIL'
                    AND tr.ledgerDispatchApproved IS TRUE
                    GROUP BY rv.transactionId)
                ) as newVersion,

                (SELECT COUNT(txOk) FROM (
                    SELECT tx.id txOk
                    FROM accounting_reporting_core.TransactionEntity tx
                    WHERE tx.reconcilation.finalStatus = 'OK'
                    GROUP BY tx.id)
                ) as txOk,

                (SELECT COUNT(txNok) FROM (
                    SELECT rv.transactionId txNok
                    FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
                    JOIN r.violations rv
                    LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
                    WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation IS NULL)
                    AND ((rv.rejectionCode = 'TX_NOT_IN_ERP' AND tr.ledgerDispatchApproved IS TRUE) OR (rv.rejectionCode != 'TX_NOT_IN_ERP'))
                    GROUP BY rv.transactionId, tr.id, rv.amountLcySum, rv.transactionEntryDate, rv.transactionInternalNumber, rv.transactionType)
                ) as txNok,

                (SELECT COUNT(txNever) FROM (
                    SELECT tx.id txNever
                    FROM accounting_reporting_core.TransactionEntity tx
                    WHERE tx.lastReconcilation IS NULL
                    GROUP BY tx.id)
                ) as txNever
            """)
    Object findCalcReconciliationStatistic();

    //-- SUM(CASE WHEN tr.reconcilation.finalStatus = 'OK' THEN 1 ELSE 0 END),
    @Query(value = """
            SELECT
                EXTRACT(YEAR FROM COALESCE(tx.entry_date, rv.transaction_entry_date))::int as year,
                EXTRACT(MONTH FROM COALESCE(tx.entry_date, rv.transaction_entry_date))::int as month,
                COUNT(DISTINCT CASE
                    WHEN tx.reconcilation_final_status = 'OK' THEN tx.transaction_id
                    ELSE NULL
                END) as reconciledCount,
                COUNT(DISTINCT CASE
                    WHEN (r.reconcilation_id = tx.reconcilation_id OR tx.reconcilation_id IS NULL)
                        AND (
                            (rv.rejection_code = 'TX_NOT_IN_ERP' AND tx.ledger_dispatch_approved = true)
                            OR (rv.rejection_code <> 'TX_NOT_IN_ERP')
                        )
                    THEN COALESCE(tx.transaction_id, rv.transaction_id)
                    ELSE NULL
                END) as unreconciledCount
            FROM accounting_core_transaction tx
            FULL OUTER JOIN accounting_core_reconcilation_violation rv
                ON tx.transaction_id = rv.transaction_id
            LEFT JOIN accounting_core_reconcilation r
                ON rv.reconcilation_id = r.reconcilation_id
            WHERE (tx.organisation_id = :orgId OR r.organisation_id = :orgId)
              AND COALESCE(tx.entry_date, rv.transaction_entry_date) >= :dateFrom
              AND COALESCE(tx.entry_date, rv.transaction_entry_date) <= :dateTo
            GROUP BY
                EXTRACT(YEAR FROM COALESCE(tx.entry_date, rv.transaction_entry_date))::int,
                EXTRACT(MONTH FROM COALESCE(tx.entry_date, rv.transaction_entry_date))::int
            ORDER BY
                EXTRACT(YEAR FROM COALESCE(tx.entry_date, rv.transaction_entry_date))::int,
                EXTRACT(MONTH FROM COALESCE(tx.entry_date, rv.transaction_entry_date))::int
            """, nativeQuery = true)
    List<ReconciliationStatisticProjection> findReconciliationStatisticByDateRange(
            @Param("orgId") String orgId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);

}
