package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.FailureResponses.*;
import static org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem.IdType.TRANSACTION;
import static org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Rejection;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.TransactionItemsRejectionRequest.TxItemRejectionRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.TransactionsRequest;
import org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem;

@Service
@org.jmolecules.ddd.annotation.Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionRepositoryGateway {

    private final TransactionItemRepository transactionItemRepository;
    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    private final TransactionBatchService transactionBatchService;
    private final LedgerService ledgerService;

    @Transactional
    public void store(TransactionEntity transactionEntity) {
        accountingCoreTransactionRepository.save(transactionEntity);
    }

    @Transactional
    public void storeAll(Collection<TransactionEntity> txs) {
        List<TransactionEntity> sortedTxs = txs.stream()
                .sorted(Comparator.comparing(TransactionEntity::getId))
                .toList();
        accountingCoreTransactionRepository.saveAllAndFlush(sortedTxs);
    }

    private Either<IdentifiableProblem, TransactionEntity> approveTransaction(TransactionEntity tx) {
        tx.setTransactionApproved(true);
        return Either.right(tx);
    }

    private Either<IdentifiableProblem, TransactionEntity> approveTransactionDispatch(TransactionEntity tx) {
        if (Boolean.FALSE.equals(tx.getTransactionApproved())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(METHOD_NOT_ALLOWED, "Cannot approve for dispatch / publish a transaction that has not been approved before, transactionId: %s".formatted(tx.getId()));
            problem.setTitle("TX_NOT_APPROVED");
            problem.setProperty("transactionId", tx.getId());

            return Either.left(new IdentifiableProblem(tx.getId(), problem, TRANSACTION));
        }

        tx.setLedgerDispatchApproved(true);
        return Either.right(tx);
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionEntity>> applyTransactions(TransactionsRequest transactionsRequest, Function<TransactionEntity, Either<IdentifiableProblem, TransactionEntity>> transformer) {
        // Extract and sort transaction IDs for consistent lock ordering
        List<String> sortedTransactionIds = transactionsRequest.getTransactionIds().stream()
                .map(TransactionsRequest.TransactionId::getId)
                .sorted()
                .toList();

        List<TransactionEntity> lockedTransactions;
        try {
            // Lock all transactions upfront in a single query with pessimistic lock
            log.info("Attempting to acquire pessimistic locks for transactions: {}", sortedTransactionIds);
            lockedTransactions = accountingCoreTransactionRepository
                    .findAllByIdWithPessimisticLock(new HashSet<>(sortedTransactionIds));
            log.info("Acquired pessimistic locks for {} transactions", lockedTransactions.size());
        } catch (DataAccessException dae) {
            log.error("Error acquiring locks for transactions: {}", sortedTransactionIds, dae);
            // Create error responses for all transactions
            List<Either<IdentifiableProblem, TransactionEntity>> errorResults = new ArrayList<>();
            for (String transactionId : sortedTransactionIds) {
                ProblemDetail problem = createTransactionDBError(transactionId, dae);
                errorResults.add(Either.left(new IdentifiableProblem(transactionId, problem, TRANSACTION)));
            }
            return errorResults;
        }

        // Create lookup map for O(1) access
        Map<String, TransactionEntity> transactionMap = lockedTransactions.stream()
                .collect(Collectors.toMap(tx -> tx.getId().trim(), tx -> tx));

        ArrayList<Either<IdentifiableProblem, TransactionEntity>> transactionsApprovalResponseListE = new ArrayList<>();
        Set<String> batchIds = new HashSet<>();

        // Process transactions in the same sorted order they were locked
        for (String transactionId : sortedTransactionIds) {
            TransactionEntity tx = transactionMap.get(transactionId);

            if (tx == null) {
                transactionsApprovalResponseListE.add(transactionNotFoundResponse(transactionId));
                continue;
            }

            if (tx.getAutomatedValidationStatus() == FAILED) {
                transactionsApprovalResponseListE.add(transactionFailedResponse(transactionId));
                continue;
            }

            if (tx.hasAnyRejection()) {
                transactionsApprovalResponseListE.add(transactionRejectedResponse(transactionId));
                continue;
            }

            var result = transformer.apply(tx);
            if (result.isLeft()) {
                transactionsApprovalResponseListE.add(result);
                continue;
            }
            tx = result.get();

            batchIds.addAll(tx.getBatches().stream()
                    .map(TransactionBatchEntity::getId)
                    .collect(Collectors.toSet()));

            transactionsApprovalResponseListE.add(result);
        }

        try {
            // Flush all changes at once
            accountingCoreTransactionRepository.saveAll(lockedTransactions);

            // Update batch statuses
            batchIds.forEach(batchId -> transactionBatchService
                    .invokeUpdateTransactionBatchStatusAndStats(batchId, Optional.empty(), Optional.empty()));
        } catch (DataAccessException dae) {
            log.error("Error saving approved transactions: {}", sortedTransactionIds, dae);
            // Return error responses for all transactions
            List<Either<IdentifiableProblem, TransactionEntity>> errorResults = new ArrayList<>();
            for (String transactionId : sortedTransactionIds) {
                ProblemDetail problem = createTransactionDBError(transactionId, dae);
                errorResults.add(Either.left(new IdentifiableProblem(transactionId, problem, TRANSACTION)));
            }
            return errorResults;
        }

        return transactionsApprovalResponseListE;
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionEntity>> approveTransactions(TransactionsRequest transactionsRequest) {
        return applyTransactions(transactionsRequest, this::approveTransaction);
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionEntity>> approveTransactionsDispatch(TransactionsRequest transactionsRequest) {
        return applyTransactions(transactionsRequest, this::approveTransactionDispatch);
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionItemEntity>> rejectTransactionItems(TransactionEntity tx, Set<TxItemRejectionRequest> transactionItemsRejections) {
        log.info("Rejecting transaction items: {}", transactionItemsRejections);

        ArrayList<Either<IdentifiableProblem, TransactionItemEntity>> transactionItemEntitiesE = new ArrayList<>();

        for (TxItemRejectionRequest txItemRejection : transactionItemsRejections) {
            String txItemId = txItemRejection.getTxItemId();
            RejectionReason rejectionReason = txItemRejection.getRejectionReason();

            Optional<TransactionItemEntity> txItemM = transactionItemRepository.findByTxIdAndItemId(tx.getId(), txItemId);

            if (txItemM.isEmpty()) {
                transactionItemEntitiesE.add(transactionItemNotFoundResponse(tx.getId(), txItemId));
                continue;
            }

            TransactionItemEntity txItem = txItemM.orElseThrow();
            if (Boolean.TRUE.equals(tx.getLedgerDispatchApproved())) {
                transactionItemEntitiesE.add(transactionItemCannotRejectAlreadyApprovedForDispatchResponse(tx.getId(), txItemId));
                continue;
            }

            txItem.setRejection(Optional.of(new Rejection(rejectionReason)));

            TransactionItemEntity savedTxItem = transactionItemRepository.save(txItem);

            transactionItemEntitiesE.add(Either.right(savedTxItem));
        }
        // Updating the transaction batch status and stats
        tx.updateProcessingStatus();
        accountingCoreTransactionRepository.save(tx);
        tx.getBatches().forEach(batch -> {
            transactionBatchService.invokeUpdateTransactionBatchStatusAndStats(batch.getId(), Optional.empty(), Optional.empty());
        });

        return transactionItemEntitiesE;
    }

    public Optional<TransactionEntity> findById(String transactionId) {
        return accountingCoreTransactionRepository.findById(transactionId);
    }

    public List<TransactionEntity> findByAllId(Set<String> transactionIds) {
        return accountingCoreTransactionRepository.findAllById(transactionIds);
    }

    public List<TransactionEntity> findAllByStatus(String organisationId,
                                                   List<TxValidationStatus> validationStatuses,
                                                   List<TransactionType> transactionType, PageRequest pageRequest) {
        // TODO add Pagerequest once the frontend is ready
        return accountingCoreTransactionRepository.findAllByStatusAndType(organisationId, validationStatuses, transactionType, pageRequest);
    }

    public List<TransactionEntity> listAll() {
        return accountingCoreTransactionRepository.findAll();
    }

    public Set<TransactionEntity> findAllByDateRangeAndNotReconciledYet(String organisationId,
                                                                        LocalDate from,
                                                                        LocalDate to) {
        return accountingCoreTransactionRepository.findByEntryDateRangeAndNotReconciledYet(organisationId, from, to);
    }

    public Set<TransactionEntity> findAllByDateRange(String organisationId,
                                                                        LocalDate from,
                                                                        LocalDate to) {
        return accountingCoreTransactionRepository.findByEntryDateRange(organisationId, from, to);
    }

}
