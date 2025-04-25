package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;

public interface TransactionBatchRepository extends JpaRepository<TransactionBatchEntity, String>, CustomTransactionBatchRepository {

    List<TransactionBatchEntity> findAllByFilteringParametersOrganisationId(String organisationId);

    @Query("""
            SELECT new org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView(
                tb.id,
                CAST(COUNT(DISTINCT CASE WHEN tx.processingStatus = 'INVALID' THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT CASE WHEN tx.processingStatus = 'PENDING' THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT CASE WHEN tx.processingStatus = 'APPROVE' THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT CASE WHEN tx.processingStatus = 'PUBLISH' THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT CASE WHEN tx.processingStatus = 'PUBLISHED' THEN tx.id END) AS java.lang.Integer),
                CAST(COUNT(DISTINCT tx.id) AS java.lang.Integer)
            )
            FROM accounting_reporting_core.TransactionBatchEntity tb
            JOIN tb.transactions tx
            WHERE tb.id IN :batchId
            GROUP BY tb.id
            """)
    List<BatchStatisticsView> getBatchStatisticViewForBatchId(@Param("batchId") List<String> batchId);
}
