package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;

public interface TransactionBatchRepository extends JpaRepository<TransactionBatchEntity, String> {

        List<TransactionBatchEntity> findAllByFilteringParametersOrganisationId(
                        String organisationId);

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
        List<BatchStatisticsView> getBatchStatisticViewForBatchId(
                        @Param("batchId") List<String> batchId, PageRequest pageRequest);

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
        Optional<BatchStatisticsView> getBatchStatisticViewForBatchId(
                        @Param("batchId") String batchId);

        @Query(" SELECT DISTINCT(tb.createdBy) FROM accounting_reporting_core.TransactionBatchEntity tb where tb.filteringParameters.organisationId = :organisationId")
        List<String> findBatchUsersList(@Param("organisationId") String organisationId);

        // Keeping it to have an alternative to speed things up with a native query
        // @Query(value = """
        // SELECT
        // tb.transaction_batch_id AS batch_id,
        // COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'INVALID') AS
        // invalid,
        // COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'PENDING') AS
        // pending,
        // COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'APPROVE') AS
        // approve,
        // COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'PUBLISH') AS
        // publish,
        // COUNT(DISTINCT tx.transaction_id) FILTER (WHERE tx.processing_status = 'PUBLISHED') AS
        // published,
        // COUNT(DISTINCT tx.transaction_id) AS total
        // FROM
        // lob_service.accounting_core_transaction_batch tb
        // JOIN
        // lob_service.accounting_core_transaction tx ON tx.batch_id = tb.transaction_batch_id
        // WHERE tb.transaction_batch_id IN :batchId
        // GROUP BY
        // tb.transaction_batch_id;
        // """, nativeQuery = true)
        // List<BatchStatisticsViewProjection> getBatchStatisticViewForBatchId(@Param("batchId")
        // List<String> batchId, PageRequest pageRequest);

        @Query("""
                SELECT DISTINCT tb FROM accounting_reporting_core.TransactionBatchEntity tb
                LEFT JOIN tb.transactions tx
                WHERE tb.filteringParameters.organisationId = :organisationId
                AND (
                :batchStatistics IS NULL OR
                (tb.batchStatistics.invalidTransactions >= 1 AND 'INVALID' IN :batchStatistics) OR
                (tb.batchStatistics.pendingTransactions >= 1 AND 'PENDING' IN :batchStatistics) OR
                (tb.batchStatistics.readyToApproveTransactions >= 1 AND 'APPROVE' IN :batchStatistics) OR
                (tb.batchStatistics.approvedTransactions >= 1 AND 'PUBLISH' IN :batchStatistics) OR
                (tb.batchStatistics.publishedTransactions >= 1 AND 'PUBLISHED' IN :batchStatistics)
                )
                AND (:txStatus IS NULL OR tx.overallStatus IN :txStatus)
                AND (:batchId IS NULL OR LOWER(tb.id) LIKE LOWER(CONCAT('%', CAST(:batchId AS string), '%')))
                AND (:createdBy IS NULL OR tb.createdBy IN :createdBy)
                AND (CAST(:dateFrom AS LocalDateTime) IS NULL OR tb.createdAt >= :dateFrom)
                AND (CAST(:dateTo AS LocalDateTime) IS NULL OR tb.createdAt <= :dateTo)
        """)
        Page<TransactionBatchEntity> findByFilter(@Param("organisationId") String organisationId,
                        @Param("batchStatistics") Set<String> batchStatistics,
                        @Param("txStatus") Set<String> txStatus,
                        @Param("dateFrom") LocalDateTime dateFrom,
                        @Param("dateTo") LocalDateTime dateTo,
                        @Param("createdBy") List<String> createdBy,
                        @Param("batchId") String batchId, Pageable pageable);
}
