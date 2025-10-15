package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

public interface TransactionItemRepository extends JpaRepository<TransactionItemEntity, String> {

    @Query("SELECT t FROM accounting_reporting_core.TransactionItemEntity t WHERE t.transaction.id = :txId AND t.id = :txItemId")
    Optional<TransactionItemEntity> findByTxIdAndItemId(@Param("txId") String txId,
                                                        @Param("txItemId") String txItemId);

    @Query("SELECT t FROM accounting_reporting_core.TransactionItemEntity t WHERE t.accountDebit.code = :accountCode or t.accountCredit.code = :accountCode AND t.amountFcy <> 0")
    List<TransactionItemEntity> findByItemAccount(@Param("accountCode") String accountCode);

    @Query("""
        SELECT t FROM accounting_reporting_core.TransactionItemEntity t
        WHERE t.transaction.entryDate >= :startDate
        AND t.transaction.entryDate <= :endDate
        AND (t.accountDebit.code IN :customerCodes OR t.accountCredit.code IN :customerCodes)
        AND t.amountLcy <> 0
        AND t.transaction.ledgerDispatchStatus = 'FINALIZED'
        """)
    List<TransactionItemEntity> findTransactionItemsByAccountCodeAndDateRange(@Param("customerCodes") List<String> customerCodes, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("""
        SELECT DISTINCT t.document.num FROM accounting_reporting_core.TransactionItemEntity t
        """)
    List<String> getAllDocumentNumbers();

    @Query("""
        SELECT DISTINCT t.document.counterparty.customerCode as customerCode, t.document.counterparty.name as name FROM accounting_reporting_core.TransactionItemEntity t
        """)
    List<Map<String, String>> getAllCounterParty();

    @Query("""
            SELECT t FROM accounting_reporting_core.TransactionItemEntity t
            WHERE t.transaction.entryDate >= :startDate
            AND t.transaction.entryDate <= :endDate
            AND (t.accountDebit.code IN :customerCodes OR t.accountCredit.code IN :customerCodes)
            AND t.amountLcy <> 0
            AND t.transaction.automatedValidationStatus = 'VALIDATED'
            AND t.transaction.processingStatus NOT IN ('PENDING','INVALID')
            """)
    List<TransactionItemEntity> findPreviewTransactionItemsByAccountCodeAndDateRange(
            @Param("customerCodes") List<String> customerCodes,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("""
        SELECT ti FROM accounting_reporting_core.TransactionItemEntity ti
        JOIN ti.transaction transaction
        WHERE ti.status = 'OK'
        AND ti.transaction.ledgerDispatchStatus = 'FINALIZED'
        AND ti.transaction.entryDate >= :dateFrom
        AND ti.transaction.entryDate <= :dateTo
        AND (:accountCode IS NULL OR (ti.accountDebit.code IN :accountCode) OR (ti.accountCredit.code IN :accountCode))
        AND (:costCenter IS NULL OR ti.costCenter.customerCode IN (SELECT cc.Id.customerCode from CostCenter cc
                    WHERE
                    cc.Id.customerCode in :costCenter OR
                    cc.parent.Id.customerCode in :costCenter OR
                    cc.Id.customerCode in (SELECT cc2.parent.Id.customerCode from CostCenter cc2 where cc2.Id.customerCode in :costCenter)
                    ))
        AND (:project IS NULL OR ti.project.customerCode IN :project)
        AND (:blockchainHash IS NULL OR LOWER(ti.transaction.ledgerDispatchReceipt.primaryBlockchainHash) LIKE LOWER(CONCAT('%', CAST(:blockchainHash AS string), '%')))
        AND (:transactionNumber IS NULL OR LOWER(ti.transaction.internalTransactionNumber) LIKE LOWER(CONCAT('%', CAST(:transactionNumber AS string), '%')))
        AND (:documentNumber IS NULL OR LOWER(ti.document.num) LIKE LOWER(CONCAT('%', CAST(:documentNumber AS string), '%')))
        AND (:currencys IS NULL OR ti.document.currency.customerCode IN :currencys)
        AND (:minFcy IS NULL OR ti.amountFcy >= :minFcy)
        AND (:maxFcy IS NULL OR ti.amountFcy <= :maxFcy)
        AND (:minLcy IS NULL OR ti.amountLcy >= :minLcy)
        AND (:maxLcy IS NULL OR ti.amountLcy <= :maxLcy)
        AND (:vatCodes IS NULL OR ti.document.vat.customerCode IN :vatCodes)
        AND (:counterPartyId IS NULL OR LOWER(ti.document.counterparty.customerCode) LIKE LOWER(CONCAT('%', CAST(:counterPartyId AS string), '%')))
        AND (:counterPartyName IS NULL OR LOWER(ti.document.counterparty.name) LIKE LOWER(CONCAT('%', CAST(:counterPartyName AS string), '%')))
        AND (:counterPartyTypes IS NULL OR ti.document.counterparty.type IN :counterPartyTypes)
        AND (:eventCodes IS NULL OR ti.accountEvent.code IN :eventCodes)
        AND (:reconciled IS NULL OR (:reconciled = True AND ti.transaction.reconcilation.finalStatus = 'OK') OR (:reconciled = False AND (ti.transaction.reconcilation IS NULL OR ti.transaction.reconcilation.finalStatus <> 'OK')))
        AND (:parentCostcenters IS NULL OR EXISTS (
            SELECT 1 FROM CostCenter cc
            WHERE cc.id.customerCode = ti.costCenter.customerCode AND cc.parentCustomerCode IN :parentCostcenters))
        AND (:parentProjects IS NULL OR EXISTS (
            SELECT 1 FROM Project p
            WHERE p.id.customerCode = ti.project.customerCode AND p.parentCustomerCode IN :parentProjects))
        AND (:accountCodesDebit IS NULL OR ti.accountDebit.code IN :accountCodesDebit)
        AND (:accountCodesCredit IS NULL OR ti.accountCredit.code IN :accountCodesCredit)
        """)
    Page<TransactionItemEntity> searchItems(@Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo, @Param("accountCode") List<String> accountCode,
            @Param("costCenter") List<String> costCenter, @Param("project") List<String> project, @Param("blockchainHash") String blockchainHash,
            @Param("transactionNumber") String transactionNumber, @Param("documentNumber") String documentNumber, @Param("currencys") List<String> currencys,
            @Param("minFcy") BigDecimal minFcy, @Param("maxFcy") BigDecimal maxFcy, @Param("minLcy") BigDecimal minLcy, @Param("maxLcy") BigDecimal maxLcy,
            @Param("vatCodes") List<String> vatCodes, @Param("counterPartyId") String counterPartyId, @Param("counterPartyName") String counterPartyName,
            @Param("counterPartyTypes") List<Counterparty.Type> counterPartyTypes, @Param("eventCodes") List<String> eventCodes, @Param("reconciled") Boolean reconciled,
            @Param("parentCostcenters") List<String> parentCostcenters, @Param("parentProjects") List<String> parentProjects,
            @Param("accountCodesDebit") List<String> accountCodesDebit, @Param("accountCodesCredit") List<String> accountCodesCredit, Pageable pageable);
}
