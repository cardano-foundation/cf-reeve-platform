package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;

public interface TransactionEntityRepository extends JpaRepository<TransactionEntity, String> {

    @Query("SELECT t FROM blockchain_publisher.txs.TransactionEntity t WHERE t.organisation.id = :organisationId AND t.l1SubmissionData.publishStatus IN :publishStatuses ORDER BY t.createdAt ASC, t.id ASC")
    Set<TransactionEntity> findTransactionsByStatus(@Param("organisationId") String organisationId,
                                                     @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                                     Limit limit);

    @Query("""
            SELECT t FROM blockchain_publisher.txs.TransactionEntity t
            WHERE t.organisation.id = :organisationId
            AND t.l1SubmissionData.publishStatus IN :publishStatuses
            AND (t.lockedAt IS NULL OR t.lockedAt < :lockTime)
            ORDER BY t.createdAt ASC, t.id ASC""")
    Set<TransactionEntity> findFreeTransactionsByStatus(@Param("organisationId") String organisationId,
                                                    @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                                    @Param("lockTime") LocalDateTime lockTime,
                                                    Limit limit);

    @Query("SELECT t FROM blockchain_publisher.txs.TransactionEntity t WHERE t.organisation.id = :organisationId AND t.l1SubmissionData.publishStatus IN :publishStatuses AND t.l1SubmissionData is NOT NULL ORDER BY t.createdAt ASC, t.id ASC")
    Set<TransactionEntity> findDispatchedTransactionsThatAreNotFinalizedYet(@Param("organisationId") String organisationId,
                                                                            @Param("publishStatuses") Set<BlockchainPublishStatus> publishStatuses,
                                                                            Limit limit);

}
