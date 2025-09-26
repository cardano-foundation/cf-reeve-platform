package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionWithViolationDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterSource;

public interface ReconcilationRepository extends JpaRepository<ReconcilationEntity, String> {

        @Query("""
                SELECT DISTINCT new org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionWithViolationDto(tr, rv)
                FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
                JOIN r.violations rv
                LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
                WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation.id IS NULL) AND ((rv.rejectionCode = 'TX_NOT_IN_ERP' AND tr.ledgerDispatchApproved = true) OR (rv.rejectionCode != 'TX_NOT_IN_ERP'))
                AND (:rejectionCodes IS NULL OR rv.rejectionCode IN :rejectionCodes)
                AND (CAST(:startDate AS date) IS NULL OR tr.entryDate > :startDate)
                AND (CAST(:endDate AS date) IS NULL OR tr.entryDate < :endDate)
                AND (:source IS NULL OR ( :source = 'ERP' AND tr.reconcilation.source = 'OK' ) OR ( :source = 'BLOCKCHAIN' AND tr.reconcilation.sink = 'OK') )
                AND (:transactionTypes IS NULL OR tr.transactionType IN :transactionTypes OR rv.transactionType IN :transactionTypes)
                AND (:transactionId IS NULL OR tr.id LIKE %:transactionId% OR rv.transactionId LIKE %:transactionId%)
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
                            (CAST(:startDate AS date) IS NULL OR tr.entryDate > :startDate)
                            AND (CAST(:endDate AS date) IS NULL OR tr.entryDate < :endDate)
                            AND (:transactionTypes IS NULL OR tr.transactionType IN :transactionTypes)
                            AND (:transactionId IS NULL OR tr.id LIKE %:transactionId% )
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
                        @Param("source") Optional<ReconciliationFilterSource> source, Pageable pageable);

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

}
