package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;

public interface CustomTransactionRepository {

    // TODO Replace this function with a JPA Query to get rid of the
    // CustomTransactionRepository
    List<TransactionEntity> findAllByStatus(String organisationId,
            List<TxValidationStatus> txValidationStatuses,
            List<TransactionType> transactionType,
            Pageable pageRequest);

    // TODO Replace this function with a JPA Query, and return type a dto
    Object findCalcReconciliationStatistic();

}
