package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.BatchStatisticsView;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionBatchRepositoryGateway {

    private final TransactionBatchRepository transactionBatchRepository;
    private final AccountingCoreTransactionRepository transactionRepository;

    public Optional<TransactionBatchEntity> findById(String batchId) {
        return transactionBatchRepository.findById(batchId);
    }

    // TODO: Pagination need to be implemented
    public List<TransactionBatchEntity> findByOrganisationId(String organisationId) {
        return transactionBatchRepository.findAllByFilteringParametersOrganisationId(organisationId);
    }

    public Either<Problem, List<TransactionBatchEntity>> findByFilter(BatchSearchRequest body, Sort sort) {
        return transactionBatchRepository.findByFilter(body, sort);
    }

    public Long findByFilterCount(BatchSearchRequest body) {
        return transactionBatchRepository.findByFilterCount(body);
    }

    public Set<TransactionEntity> findAllTransactionsByBatchId(String batchId) {
        return transactionRepository.findAllByBatchId(batchId);
    }

    public List<BatchStatisticsView> getBatchStatisticViewForBatchId(List<String> batchId, PageRequest pageRequest) {
        return transactionBatchRepository.getBatchStatisticViewForBatchId(batchId, pageRequest);
    }
    public List<String> findBatchUsersList(String orgId){
        return transactionBatchRepository.findBatchUsersList(orgId);

    }

}
