package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.time.LocalDateTime;
import java.util.Set;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;

public interface ReportEntityRepository extends JpaRepository<ReportEntity, String> {

    @Query("SELECT r FROM blockchain_publisher.report.ReportEntityV2 r WHERE r.organisationId = :organisationId AND r.l1SubmissionData.publishStatus IN :publishStatuses ORDER BY r.createdAt ASC, r.id ASC")
    Set<ReportEntity> findReportsByStatus(@Param("organisationId") String organisationId,
                                          @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses, Limit limit);

    // Atomic claim read: FOR UPDATE SKIP LOCKED (lock timeout -2) so concurrent dispatcher instances
    // never see the same "free" rows; must run inside the transaction that also writes lockedAt.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT r FROM blockchain_publisher.report.ReportEntityV2 r
            WHERE r.organisationId = :organisationId
            AND r.l1SubmissionData.publishStatus IN :publishStatuses
            AND (r.lockedAt IS NULL OR r.lockedAt < :lockTime)
            ORDER BY r.createdAt ASC, r.id ASC""")
    Set<ReportEntity> findFreeReportsByStatus(@Param("organisationId") String organisationId,
                                              @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                              @Param("lockTime") LocalDateTime lockTime,
                                              Limit limit);

    @Query("SELECT r FROM blockchain_publisher.report.ReportEntityV2 r WHERE r.organisationId = :organisationId AND r.l1SubmissionData.publishStatus IN :publishStatuses AND r.l1SubmissionData IS NOT NULL ORDER BY r.createdAt ASC, r.id ASC")
    Set<ReportEntity> findDispatchedReportsThatAreNotFinalizedYet(
            @Param("organisationId") String organisationId,
            @Param("publishStatuses") Set<BlockchainPublishStatus> notFinalisedButVisibleOnChain,
            Limit limit);

}
