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
        AND (:internalTransactionNumber IS NULL OR LOWER(t.internalTransactionNumber) LIKE LOWER(CONCAT('%', CAST(:internalTransactionNumber AS string), '%')))
        AND (:minTotalLcy IS NULL OR t.totalAmountLcy >= :minTotalLcy)
        AND (:maxTotalLcy IS NULL OR t.totalAmountLcy <= :maxTotalLcy)
        AND t.entryDate >= COALESCE(:dateFrom, t.entryDate)
        AND t.entryDate <= COALESCE(:dateTo, t.entryDate)
        AND (
                (:documentNumber IS NULL AND :documentNumbers IS NULL AND :currencyCustomerCodes IS NULL AND
                :minFCY IS NULL AND :maxFCY IS NULL AND :minLCY IS NULL AND :maxLCY IS NULL AND
                :vatCustomerCodes IS NULL AND :costCenterCustomerCodes IS NULL AND :projectCustomerCodes IS NULL AND
                :counterPartyCustomerCodes IS NULL AND :counterPartyTypes IS NULL AND
                :debitAccountCodes IS NULL AND :creditAccountCodes IS NULL AND :eventCodes IS NULL AND
                :parentCostCenterCustomerCodes IS NULL AND :parentProjectCustomerCodes IS NULL)
                OR EXISTS (
                SELECT 1 FROM t.items i2
                WHERE
                        i2.status = 'OK'
                        AND (
                        (:documentNumber IS NULL OR i2.document.num LIKE CONCAT('%', CAST(:documentNumber AS string), '%'))
                        AND (:documentNumbers IS NULL OR i2.document.num IN :documentNumbers)
                        AND (:currencyCustomerCodes IS NULL OR i2.document.currency.customerCode IN :currencyCustomerCodes)
                        AND (:minFCY IS NULL OR i2.amountFcy >= :minFCY)
                        AND (:maxFCY IS NULL OR i2.amountFcy <= :maxFCY)
                        AND (:minLCY IS NULL OR i2.amountLcy >= :minLCY)
                        AND (:maxLCY IS NULL OR i2.amountLcy <= :maxLCY)
                        AND (:vatCustomerCodes IS NULL OR i2.document.vat.customerCode IN :vatCustomerCodes)
                        AND (:costCenterCustomerCodes IS NULL OR i2.costCenter.customerCode IN :costCenterCustomerCodes)
                        AND (:projectCustomerCodes IS NULL OR i2.project.customerCode IN :projectCustomerCodes)
                        AND (:counterPartyCustomerCodes IS NULL OR i2.document.counterparty.customerCode IN :counterPartyCustomerCodes)
                        AND (:counterPartyTypes IS NULL OR i2.document.counterparty.type IN :counterPartyTypes)
                        AND (:debitAccountCodes IS NULL OR i2.accountDebit.code IN :debitAccountCodes)
                        AND (:creditAccountCodes IS NULL OR i2.accountCredit.code IN :creditAccountCodes)
                        AND (:eventCodes IS NULL OR i2.accountEvent.code IN :eventCodes)
                        AND (:parentCostCenterCustomerCodes IS NULL OR EXISTS (
                                SELECT 1 FROM CostCenter cc
                                WHERE cc.id.customerCode = i2.costCenter.customerCode
                                AND cc.parentCustomerCode IN :parentCostCenterCustomerCodes))
                        AND (:parentProjectCustomerCodes IS NULL OR EXISTS (
                                SELECT 1 FROM Project cc
                                WHERE cc.id.customerCode = i2.project.customerCode
                                AND cc.parentCustomerCode IN :parentProjectCustomerCodes))
                        )
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
