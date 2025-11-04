package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return transactionBatchRepository
                .findAllByFilteringParametersOrganisationId(organisationId);
    }

    public Page<TransactionBatchEntity> findByFilter(BatchSearchRequest body, Pageable pageable) {
        // Apply default sort if no sort is provided
        Pageable pageableWithSort = pageable.getSort().isSorted() ? pageable // Use the provided
                                                                             // sort
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "createdAt")); // Use default sort
        return transactionBatchRepository.findByFilter(body.getOrganisationId(),
                Optional.ofNullable(body.getBatchStatistics().isEmpty() ? null : body.getBatchStatistics()).map(bs -> bs.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())).orElse(null),
                Optional.ofNullable(body.getTxStatus().isEmpty() ? null
                        : body.getTxStatus()).map(ts -> ts.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())).orElse(null),
                Optional.ofNullable(body.getFrom()).map(t -> t.atStartOfDay()).orElse(LocalDate.EPOCH.atStartOfDay()),
                Optional.ofNullable(body.getTo()).map(t -> t.atStartOfDay()).orElse(LocalDateTime.now()),
                body.getCreatedBy(),
                body.getBatchId(),
                pageableWithSort);
    }

    public Set<TransactionEntity> findAllTransactionsByBatchId(String batchId) {
        return transactionRepository.findAllByBatchId(batchId);
    }

    public List<BatchStatisticsView> getBatchStatisticViewForBatchId(List<String> batchId,
            PageRequest pageRequest) {
        return transactionBatchRepository.getBatchStatisticViewForBatchId(batchId, pageRequest);
    }

    public List<String> findBatchUsersList(String orgId) {
        return transactionBatchRepository.findBatchUsersList(orgId);

    }

}
