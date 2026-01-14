package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionItemEntity;

public interface TransactionItemEntityRepository extends JpaRepository<TransactionItemEntity, String> {

    @Query("SELECT t FROM blockchain_publisher.txs.TransactionItemEntity t WHERE t.transaction.id = :id")
    Set<TransactionItemEntity> findAllByTransactionId(@Param("id") String id);

    @Modifying
    @Transactional
    @Query("DELETE FROM blockchain_publisher.txs.TransactionItemEntity t WHERE t.transaction.id = :transactionId")
    void deleteAllByTransactionId(@Param("transactionId") String transactionId);
}
