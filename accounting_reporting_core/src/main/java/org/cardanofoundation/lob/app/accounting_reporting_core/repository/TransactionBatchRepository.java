package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
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
    List<BatchStatisticsView> getBatchStatisticViewForBatchId(@Param("batchId") List<String> batchId, PageRequest pageRequest);

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
            WHERE tb.id = :batchId
            GROUP BY tb.id
            """)
    Optional<BatchStatisticsView> getBatchStatisticViewForBatchId(@Param("batchId") String batchId);

    // Keeping it to have an alternative to speed things up with a native query
//    @Query(value = """
//            SELECT
//                tb.transaction_batch_id AS batch_id,
//                COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'INVALID') AS invalid,
//                COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'PENDING') AS pending,
//                COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'APPROVE') AS approve,
//                COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'PUBLISH') AS publish,
//                COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'PUBLISHED') AS published,
//                COUNT(DISTINCT tx.transaction_id) AS total
//            FROM
//                lob_service.accounting_core_transaction_batch tb
//            JOIN
//                lob_service.accounting_core_transaction tx ON tx.batch_id = tb.transaction_batch_id
//            WHERE tb.transaction_batch_id IN :batchId
//            GROUP BY
//                tb.transaction_batch_id;
//            """, nativeQuery = true)
//    List<BatchStatisticsViewProjection> getBatchStatisticViewForBatchId(@Param("batchId") List<String> batchId, PageRequest pageRequest);
}
