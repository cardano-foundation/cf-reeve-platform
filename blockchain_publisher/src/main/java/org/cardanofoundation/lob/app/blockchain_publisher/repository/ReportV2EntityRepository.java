package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reportsV2.ReportV2Entity;

public interface ReportV2EntityRepository extends JpaRepository<ReportV2Entity, String> {

    @Query("SELECT r FROM blockchain_publisher.report.ReportEntityV2 r WHERE r.organisationId = :organisationId AND r.l1SubmissionData.publishStatus IN :publishStatuses ORDER BY r.createdAt ASC, r.id ASC")
    Set<ReportV2Entity> findReportsByStatus(@Param("organisationId") String organisationId,
            @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses, Limit limit);

    @Query("SELECT r FROM blockchain_publisher.report.ReportEntityV2 r WHERE r.organisationId = :organisationId AND r.l1SubmissionData.publishStatus IN :publishStatuses AND r.l1SubmissionData IS NOT NULL ORDER BY r.createdAt ASC, r.id ASC")
    Set<ReportV2Entity> findDispatchedReportsThatAreNotFinalizedYet(
            @Param("organisationId") String organisationId,
            @Param("publishStatuses") Set<BlockchainPublishStatus> notFinalisedButVisibleOnChain,
            Limit limit);

}
