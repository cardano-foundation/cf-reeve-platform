package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;

public interface TransactionBatchRepository extends JpaRepository<TransactionBatchEntity, String>, CustomTransactionBatchRepository {

    List<TransactionBatchEntity> findAllByFilteringParametersOrganisationId(String organisationId);

    @Query("""
            SELECT new org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView(
                tb.id,
                CAST(COUNT(DISTINCT CASE WHEN EXISTS (SELECT 1 FROM tx.violations v WHERE v.source = 'ERP')
                    OR EXISTS(SELECT 1 FROM tx.items ti WHERE ti.rejection.rejectionReason IN :erpRejectionReasons) THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT CASE WHEN (EXISTS (SELECT 1 FROM tx.violations v WHERE v.source = 'LOB') AND NOT EXISTS (SELECT 1 FROM tx.violations v WHERE v.source = 'ERP')) OR
                        (EXISTS(SELECT 1 FROM tx.items ti WHERE ti.rejection.rejectionReason IN :lobRejectReasons)
                        AND NOT EXISTS(SELECT 1 FROM tx.items ti WHERE ti.rejection.rejectionReason IN :erpRejectionReasons)
                        ) THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT CASE WHEN tx.automatedValidationStatus = 'VALIDATED' AND tx.ledgerDispatchApproved = false AND tx.ledgerDispatchStatus = 'NOT_DISPATCHED' AND tx.transactionApproved = false
                AND NOT EXISTS (SELECT 1 FROM tx.items ti WHERE ti.rejection.rejectionReason IN :lobRejectReasons OR ti.rejection.rejectionReason IN :erpRejectionReasons)
                AND NOT EXISTS (SELECT 1 FROM tx.violations v) THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT CASE WHEN tx.automatedValidationStatus = 'VALIDATED' AND tx.transactionApproved = true THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT CASE WHEN tx.ledgerDispatchStatus = 'FINALIZED' THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT tx.id) AS java.lang.Integer)
            )
            FROM accounting_reporting_core.TransactionBatchEntity tb
            JOIN tb.transactions tx
            WHERE tb.id IN :batchId
            GROUP BY tb.id
            """)
    List<BatchStatisticsView> getBatchStatisticViewForBatchId(@Param("batchId") List<String> batchId, @Param("lobRejectReasons") Set<RejectionReason> lobRejectionReasons, @Param("erpRejectionReasons") Set<RejectionReason> erpRejectionReasons);
}
