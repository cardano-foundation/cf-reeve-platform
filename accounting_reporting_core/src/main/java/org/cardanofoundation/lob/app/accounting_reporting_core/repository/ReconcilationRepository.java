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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterSource;

public interface ReconcilationRepository extends JpaRepository<ReconcilationEntity, String> {

    @Query("""
            SELECT DISTINCT tr, rv
            FROM accounting_reporting_core.reconcilation.ReconcilationEntity r
            JOIN r.violations rv
            LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id
            WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation.id IS NULL) AND ((rv.rejectionCode = 'TX_NOT_IN_ERP' AND tr.ledgerDispatchApproved = true) OR (rv.rejectionCode != 'TX_NOT_IN_ERP'))
            AND rv.rejectionCode IN :rejectionCodes
            AND (:startDate IS NULL OR tr.entryDate > :startDate)
            AND (:endDate IS NULL OR tr.entryDate < :endDate)
            AND (rv.rejectionCode IN :rejectionCodes)
            AND (:source IS NULL OR ( :source = 'ERP' AND r.source = 'OK' ) OR ( :source = 'BLOCKCHAIN' AND r.sink = 'OK') )
            AND (:transactionTypes IS NULL OR tr.type IN :transactionTypes OR rv.transactionType IN :transactionTypes)
            AND (:transactionId IS NULL OR tr.id LIKE %:transactionId% OR rv.transactionId LIKE %:transactionId%)
            """)
    Page<Object[]> findAllReconciliationSpecial(@Param("rejectionCodes") Set<ReconcilationRejectionCode> rejectionCodes,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate,
                                                @Param("source") ReconciliationFilterSource source,
                                                @Param("transactionTypes") Set<TransactionType> transactionTypes,
                                                @Param("transactionId") String transactionId,
                                                Pageable pageable);

    @Query("""
        SELECT tr
        FROM accounting_reporting_core.TransactionEntity tr
        WHERE
        (:filter = "RECONCILED"
                AND tr.reconcilation.finalStatus = "OK"
                AND ("ERP" NOT IN :source OR tr.reconcilation.source = "OK")
                AND ("BLOCKCHAIN" NOT IN :source OR tr.reconcilation.sink = "OK"))
        OR (:filter = "UNRECONCILED"
                AND tr.reconcilation.source IS NULL)
        """)
    Page<TransactionEntity> findAllReconcilation(
                    @Param("filter") String filter, @Param("rejectionCodes") Set<ReconcilationRejectionCode> rejectionCodes,
            @Param("source") Optional<ReconciliationFilterSource> source, Pageable pageable);

}
