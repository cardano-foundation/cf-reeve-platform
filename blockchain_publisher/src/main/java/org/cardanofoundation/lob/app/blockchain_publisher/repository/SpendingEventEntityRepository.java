package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;

public interface SpendingEventEntityRepository extends JpaRepository<SpendingEventEntity, String> {

    @Query("""
            SELECT s FROM blockchain_publisher.spending.SpendingEventEntity s
            WHERE s.organisation.id = :organisationId
            AND s.l1SubmissionData.publishStatus IN :publishStatuses
            AND (s.lockedAt IS NULL OR s.lockedAt < :lockTime)
            ORDER BY s.createdAt ASC, s.id ASC""")
    Set<SpendingEventEntity> findFreeByStatus(@Param("organisationId") String organisationId,
                                              @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                              @Param("lockTime") LocalDateTime lockTime,
                                              Limit limit);

    @Query("""
            SELECT s FROM blockchain_publisher.spending.SpendingEventEntity s
            WHERE s.organisation.id = :organisationId
            AND s.l1SubmissionData.publishStatus IN :publishStatuses
            AND s.l1SubmissionData IS NOT NULL
            ORDER BY s.createdAt ASC, s.id ASC""")
    Set<SpendingEventEntity> findDispatchedThatAreNotFinalizedYet(@Param("organisationId") String organisationId,
                                                                  @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                                                  Limit limit);

}
