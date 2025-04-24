package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason.getSourceBasedRejectionReasons;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
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
        String organisationId = body.getOrganisationId();
        LocalDate from = Optional.ofNullable(body.getFrom()).orElse(LocalDate.EPOCH);
        LocalDate to = Optional.ofNullable(body.getTo()).orElse(LocalDate.MAX);
        Set<String> transactionTypes = body.getTransactionTypes().isEmpty() ? null : body.getTransactionTypes().stream().map(Enum::toString).collect(Collectors.toSet());
        Set<String> transactionStatuses = body.getTxStatus().isEmpty() ? null : body.getTxStatus().stream().map(Enum::toString).collect(Collectors.toSet());
        boolean hasInvalid = body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.INVALID);
        boolean hasPending = body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.PENDING);
        boolean hasApprove = body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.APPROVE);
        boolean hasPublish = body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.PUBLISH);
        boolean hasPublished = body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.PUBLISHED);
        boolean hasDispatched = body.getBatchStatistics().stream().anyMatch(ledgerDispatchStatusView -> ledgerDispatchStatusView == LedgerDispatchStatusView.DISPATCHED);
        HashSet<RejectionReason> erpRejectionReasons = new HashSet<>(getSourceBasedRejectionReasons(Source.ERP));
        HashSet<RejectionReason> lobRejectionReasons = new HashSet<>(getSourceBasedRejectionReasons(Source.LOB));
        PageRequest pageRequest = PageRequest.of(body.getPage(), body.getLimit());
        return transactionBatchRepository.filterBatchEntities(
                            organisationId,
                            transactionTypes,
                            transactionStatuses,
                            hasApprove,
                            hasPending,
                            hasInvalid,
                            hasPublish,
                            hasPublished,
                            hasDispatched,
                            erpRejectionReasons,
                            lobRejectionReasons,
                            pageRequest
        );
    }

    public Long findByFilterCount(BatchSearchRequest body) {
        return transactionBatchRepository.findByFilterCount(body);
    }

    public Set<TransactionEntity> findAllTransactionsByBatchId(String batchId) {
        return transactionRepository.findAllByBatchId(batchId);
    }

}
