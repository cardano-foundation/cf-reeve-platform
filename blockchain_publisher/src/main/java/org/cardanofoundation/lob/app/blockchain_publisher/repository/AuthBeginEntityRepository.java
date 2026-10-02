package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.authbegin.AuthBeginEntity;

public interface AuthBeginEntityRepository extends JpaRepository<AuthBeginEntity, String> {

    @Query("""
            SELECT a FROM blockchain_publisher.authbegin.AuthBeginEntity a
            WHERE a.organisationId = :organisationId
            AND a.l1SubmissionData.publishStatus IN :publishStatuses
            AND (a.lockedAt IS NULL OR a.lockedAt < :lockTime)
            ORDER BY a.createdAt ASC, a.id ASC""")
    Set<AuthBeginEntity> findFreeByStatus(@Param("organisationId") String organisationId,
                                          @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                          @Param("lockTime") LocalDateTime lockTime,
                                          Limit limit);

    @Query("""
            SELECT a FROM blockchain_publisher.authbegin.AuthBeginEntity a
            WHERE a.organisationId = :organisationId
            AND a.l1SubmissionData.publishStatus IN :publishStatuses
            AND a.l1SubmissionData IS NOT NULL
            ORDER BY a.createdAt ASC, a.id ASC""")
    Set<AuthBeginEntity> findDispatchedThatAreNotFinalizedYet(@Param("organisationId") String organisationId,
                                                              @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                                              Limit limit);

}
