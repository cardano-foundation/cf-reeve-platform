package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterSource;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterStatusRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRejectionCodeRequest;
import org.springframework.data.domain.Pageable;

public interface CustomTransactionRepository {

//    List<TransactionEntity> findAllByStatus(String organisationId,
//                                            List<TxValidationStatus> txValidationStatuses,
//                                            List<TransactionType> transactionType,
//                                            PageRequest pageRequest);
        List<TransactionEntity> findAllByStatus(String organisationId,
                                                List<TxValidationStatus> txValidationStatuses,
                                                List<TransactionType> transactionType,
                                                Pageable pageRequest);

    List<Object[]> findAllReconciliationSpecial(Set<ReconciliationRejectionCodeRequest> rejectionCodes,
                                                Optional<LocalDate> getDateFrom,
                                                Optional<LocalDate> getDateTo,
                                                Pageable pageable);

    List<Object[]> findAllReconciliationSpecialCount(Set<ReconciliationRejectionCodeRequest> rejectionCodes,
                                                     Optional<LocalDate> getDateFrom,
                                                     Optional<LocalDate> getDateTo,
                                                     Pageable pageable);

    List<TransactionEntity> findAllReconciliation(ReconciliationFilterStatusRequest filter,
                                                  Optional<ReconciliationFilterSource> sourceO,
                                                  Pageable pageable);

    List<TransactionEntity> findAllReconciliationCount(ReconciliationFilterStatusRequest filter,
                                                       Optional<ReconciliationFilterSource> sourceO,
                                                       Pageable pageable);

    Object findCalcReconciliationStatistic();

}
