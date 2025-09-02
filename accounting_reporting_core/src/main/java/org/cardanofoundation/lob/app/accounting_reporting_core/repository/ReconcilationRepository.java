package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconcilationRepository extends JpaRepository<ReconcilationEntity, String> {

    @Query("""
            SELECT tr, rv
            FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
            JOIN r.violations rv
            LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
            WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation.id IS NULL) AND ((rv.rejectionCode = 'TX_NOT_IN_ERP' AND tr.ledgerDispatchApproved = true) OR (rv.rejectionCode != 'TX_NOT_IN_ERP'))
            AND rv.rejectionCode IN :rejectionCodes
            AND (:startDate IS NULL OR tr.entryDate > :startDate)
            AND (:endDate IS NULL OR tr.entryDate < :endDate)
            AND (rv.rejectionCode IN :rejectionCodes)
            GROUP BY rv.transactionId, tr.id, rv.amountLcySum, rv.rejectionCode, rv.sourceDiff, rv.transactionEntryDate, rv.transactionInternalNumber, rv.transactionType
            """)
    Page<Object[]> findAllReconciliationSpecial(@Param("rejectionCodes") Set<ReconcilationRejectionCode> rejectionCodes, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, Pageable pageable);

    Page<TransactionEntity> findAllReconcilation(@Param("rejectionCodes") Set<ReconcilationRejectionCode> rejectionCodes,
            @Param("source") Optional<ReconciliationFilterSource> source, Pageable pageable);

}
