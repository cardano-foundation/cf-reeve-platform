package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason.getSourceBasedRejectionReasons;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.LedgerDispatchStatusView;

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

    public List<TransactionBatchEntity> findByFilter(BatchSearchRequest body) {
        return transactionBatchRepository.filterBatchEntities(
                body.getOrganisationId(),
                body.getFrom(),
                body.getTo(),
                body.getTransactionTypes().isEmpty() ? null : body.getTransactionTypes(),
                body.getTxStatus().isEmpty() ? null : body.getTxStatus(),
                body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.INVALID),
                body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.PENDING),
                body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.APPROVE),
                body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.PUBLISH),
                body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.PUBLISHED),
                body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.DISPATCHED),
                new HashSet<>(getSourceBasedRejectionReasons(Source.ERP)),
                new HashSet<>(getSourceBasedRejectionReasons(Source.LOB)),
                PageRequest.of(body.getPage(), body.getLimit())
        );
    }

    public Long findByFilterCount(BatchSearchRequest body) {
        return transactionBatchRepository.findByFilterCount(body);
    }

    public Set<TransactionEntity> findAllTransactionsByBatchId(String batchId) {
        return transactionRepository.findAllByBatchId(batchId);
    }

}
