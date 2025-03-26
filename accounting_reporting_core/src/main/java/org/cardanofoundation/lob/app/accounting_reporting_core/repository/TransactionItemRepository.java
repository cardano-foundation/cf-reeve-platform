package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

public interface TransactionItemRepository extends JpaRepository<TransactionItemEntity, String> {

    @Query("SELECT t FROM accounting_reporting_core.TransactionItemEntity t WHERE t.transaction.id = :txId AND t.id = :txItemId")
    Optional<TransactionItemEntity> findByTxIdAndItemId(@Param("txId") String txId,
                                                        @Param("txItemId") String txItemId);

    @Query("SELECT t FROM accounting_reporting_core.TransactionItemEntity t WHERE t.accountDebit.code = :accountCode or t.accountCredit.code = :accountCode AND t.amountFcy <> 0")
    List<TransactionItemEntity> findByItemAccount(@Param("accountCode") String accountCode);

    @Query("""
        SELECT t FROM accounting_reporting_core.TransactionItemEntity t
        WHERE t.transaction.entryDate >= :startDate AND t.transaction.entryDate <= :endDate
        AND t.accountDebit.code IN :customerCodes OR t.accountCredit.code IN :customerCodes
        AND t.amountLcy <> 0
        AND t.transaction.ledgerDispatchStatus <> 'NOT_DISPATCHED'
        """)
    List<TransactionItemEntity> findTransactionItemsByAccountCodeAndDateRange(@Param("customerCodes") List<String> customerCodes, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
