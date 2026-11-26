package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;

public interface DocumentEntityRepository extends JpaRepository<DocumentEntity, String> {

    @Query("""
            SELECT d FROM blockchain_publisher.documents.DocumentEntity d
            WHERE d.organisationId = :organisationId
            AND d.l1SubmissionData.publishStatus IN :publishStatuses
            AND (d.lockedAt IS NULL OR d.lockedAt < :lockTime)
            ORDER BY d.createdAt ASC, d.id ASC""")
    Set<DocumentEntity> findFreeByStatus(@Param("organisationId") String organisationId,
                                         @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                         @Param("lockTime") LocalDateTime lockTime,
                                         Limit limit);

    @Query("""
            SELECT d FROM blockchain_publisher.documents.DocumentEntity d
            WHERE d.organisationId = :organisationId
            AND d.l1SubmissionData.publishStatus IN :publishStatuses
            AND d.l1SubmissionData IS NOT NULL
            ORDER BY d.createdAt ASC, d.id ASC""")
    Set<DocumentEntity> findDispatchedThatAreNotFinalizedYet(@Param("organisationId") String organisationId,
                                                              @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                                              Limit limit);

}
