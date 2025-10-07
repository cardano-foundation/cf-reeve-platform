package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;

public interface AccountingCoreTransactionRepository extends JpaRepository<TransactionEntity, String> {

    @Query("""
            SELECT t FROM accounting_reporting_core.TransactionEntity t
            WHERE t.organisation.id = :organisationId
            AND t.overallStatus = 'OK'
            AND t.ledgerDispatchStatus = 'NOT_DISPATCHED'
            ORDER BY t.createdAt ASC, t.id ASC
            """)
    Set<TransactionEntity> findDispatchableTransactions(@Param("organisationId") String organisationId,
                                                        Limit limit);

    @Query("""
            SELECT t FROM accounting_reporting_core.TransactionEntity t
            WHERE t.organisation.id = :organisationId
            AND t.entryDate BETWEEN :startDate AND :endDate
            AND (t.reconcilation.source IS NULL OR t.reconcilation.sink IS NULL)
            ORDER BY t.createdAt ASC, t.id ASC
            """)
    Set<TransactionEntity> findByEntryDateRangeAndNotReconciledYet(@Param("organisationId") String organisactionId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    @Query("""
        SELECT t FROM accounting_reporting_core.TransactionEntity t
        WHERE t.organisation.id = :organisationId
        AND t.entryDate BETWEEN :startDate AND :endDate
        AND t.ledgerDispatchStatus <> 'NOT_DISPATCHED'
        """)
    Set<TransactionEntity> findDispatchedTransactionInDateRange(@Param("organisationId") String organisationId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

@Query("SELECT t FROM accounting_reporting_core.TransactionEntity t" +
            " WHERE t.organisation.id = :organisationId" +
            " AND t.entryDate BETWEEN :startDate AND :endDate" +
            " AND t.id not in (:ids)" +
            " ORDER BY t.createdAt ASC, t.id ASC")
    Set<TransactionEntity> findByEntryDateRangeAndNotInReconciliation(@Param("organisationId") String organisactionId,
                                                                      @Param("startDate") LocalDate startDate,
                                                                      @Param("endDate") LocalDate endDate,
                                                                      @Param("ids") Set<String> ids);

    Set<TransactionEntity> findAllByBatchId(String batchId);

    @Query("""
    SELECT t FROM accounting_reporting_core.TransactionEntity t
    JOIN t.batches b
    WHERE b.id = :batchId
    AND (:txStatus IS NULL OR t.processingStatus IN :txStatus)
    AND (:types IS NULL OR t.transactionType IN :types)
    AND (:internalTransactionNumber IS NULL OR LOWER(t.internalTransactionNumber) LIKE LOWER(CONCAT('%', :internalTransactionNumber, '%')))
    AND (:minTotalLcy IS NULL OR t.totalAmountLcy >= :minTotalLcy)
    AND (:maxTotalLcy IS NULL OR t.totalAmountLcy <= :maxTotalLcy)
    AND t.entryDate >= COALESCE(:dateFrom, t.entryDate)
    AND t.entryDate <= COALESCE(:dateTo, t.entryDate)
    AND NOT EXISTS (
        SELECT 1 FROM t.items i2
        WHERE
            i2.status = 'OK'
            AND (
            (:documentNumber IS NOT NULL AND LOWER(i2.document.num) NOT LIKE LOWER(CONCAT('%', :documentNumber, '%')))
            OR (:documentNumbers IS NOT NULL AND (i2.document.num IS NULL OR i2.document.num NOT IN :documentNumbers))
            OR (:currencyCustomerCodes IS NOT NULL AND (i2.document.currency.customerCode is NULL OR i2.document.currency.customerCode NOT IN :currencyCustomerCodes))
            OR (:minFCY IS NOT NULL AND i2.amountFcy < :minFCY)
            OR (:maxFCY IS NOT NULL AND i2.amountFcy > :maxFCY)
            OR (:minLCY IS NOT NULL AND i2.amountLcy < :minLCY)
            OR (:maxLCY IS NOT NULL AND i2.amountLcy > :maxLCY)
            OR (:vatCustomerCodes IS NOT NULL AND (i2.document.vat.customerCode IS NULL OR i2.document.vat.customerCode NOT IN :vatCustomerCodes))
            OR (:costCenterCustomerCodes IS NOT NULL AND (i2.costCenter.customerCode IS NULL OR i2.costCenter.customerCode NOT IN :costCenterCustomerCodes))
            OR (:projectCustomerCodes IS NOT NULL AND (i2.project.customerCode IS NULL OR i2.project.customerCode NOT IN :projectCustomerCodes))
            OR (:counterPartyCustomerCodes IS NOT NULL AND (i2.document.counterparty.customerCode IS NULL OR i2.document.counterparty.customerCode NOT IN :counterPartyCustomerCodes))
            OR (:counterPartyTypes IS NOT NULL AND (i2.document.counterparty.type IS NULL OR i2.document.counterparty.type NOT IN :counterPartyTypes))
            OR (:debitAccountCodes IS NOT NULL AND (i2.accountDebit.code IS NULL OR i2.accountDebit.code NOT IN :debitAccountCodes))
            OR (:creditAccountCodes IS NOT NULL AND (i2.accountCredit.code IS NULL OR i2.accountCredit.code NOT IN :creditAccountCodes))
            OR (:eventCodes IS NOT NULL AND (i2.accountEvent.code IS NULL OR i2.accountEvent.code NOT IN :eventCodes))
            OR (:parentCostCenterCustomerCodes IS NOT NULL AND (i2.costCenter.customerCode IS NULL OR NOT EXISTS (
                SELECT 1 FROM CostCenter cc
                WHERE cc.id.customerCode = i2.costCenter.customerCode AND cc.parentCustomerCode IN :parentCostCenterCustomerCodes)))
            OR (:parentProjectCustomerCodes IS NOT NULL AND (i2.project.customerCode IS NULL OR NOT EXISTS (
                SELECT 1 FROM Project cc
                WHERE cc.id.customerCode = i2.project.customerCode AND cc.parentCustomerCode IN :parentProjectCustomerCodes)))
        )
    )
            """)
    Page<TransactionEntity> findAllByBatchId(
            @Param("batchId") String batchId,
            @Param("txStatus") List<TransactionProcessingStatus> txStatus,
            @Param("internalTransactionNumber") String internalTransactionNumber,
            @Param("types") List<TransactionType> types,
            @Param("documentNumbers") List<String> documentNumbers,
            @Param("documentNumber") String documentNumber,
            @Param("currencyCustomerCodes") List<String> currencyCustomerCodes,
            @Param("minFCY") BigDecimal minFCY,
            @Param("maxFCY") BigDecimal maxFCY,
            @Param("minLCY") BigDecimal minLCY,
            @Param("maxLCY") BigDecimal maxLCY,
            @Param("minTotalLcy") BigDecimal minTotalLcy,
            @Param("maxTotalLcy") BigDecimal maxTotalLcy,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            @Param("vatCustomerCodes") List<String> vatCustomerCodes,
            @Param("parentCostCenterCustomerCodes") List<String> parentCostCenterCustomerCodes,
            @Param("costCenterCustomerCodes") List<String> costCenterCustomerCodes,
            @Param("counterPartyCustomerCodes") List<String> counterPartyCustomerCodes,
            @Param("counterPartyTypes") List<String> counterPartyTypes,
            @Param("debitAccountCodes") List<String> debitAccountCodes,
            @Param("creditAccountCodes") List<String> creditAccountCodes,
            @Param("eventCodes") List<String> eventCodes,
            @Param("projectCustomerCodes") List<String> projectCustomerCodes,
            @Param("parentProjectCustomerCodes") List<String> parentProjectCustomerCodes,
            Pageable page
    );

    @Query("""
            SELECT t FROM accounting_reporting_core.TransactionEntity t
            WHERE t.organisation.id = :organisationId
            AND (:validationStatuses IS NULL OR t.automatedValidationStatus in (:validationStatuses))
            AND (:transactionTypes IS NULL OR t.transactionType in (:transactionTypes))
            """)
    List<TransactionEntity> findAllByStatus(@Param("organisationId") String organisationId,
                                            @Param("validationStatuses") List<TxValidationStatus> validationStatuses,
                                            @Param("transactionTypes") List<TransactionType> transactionTypes,
                                            Pageable pageable);
}
