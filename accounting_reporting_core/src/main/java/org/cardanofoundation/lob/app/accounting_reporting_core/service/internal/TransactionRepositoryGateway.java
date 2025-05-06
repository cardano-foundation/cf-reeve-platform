package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.FailureResponses.*;
import static org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem.IdType.TRANSACTION;
import static org.zalando.problem.Status.METHOD_NOT_ALLOWED;

import java.time.LocalDate;
import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Rejection;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
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
        accountingCoreTransactionRepository.saveAll(txs);
    }

    @Transactional
    // TODO optimise performance because we have to load transaction from db each time and we don't save it in bulk
    protected Either<IdentifiableProblem, TransactionEntity> approveTransaction(String transactionId) {
        log.info("Approving transaction: {}", transactionId);

        Optional<TransactionEntity> txM = accountingCoreTransactionRepository.findById(transactionId);

        if (txM.isEmpty()) {
            return transactionNotFoundResponse(transactionId);
        }

        TransactionEntity tx = txM.orElseThrow();

        if (tx.getAutomatedValidationStatus() == FAILED) {
            return transactionFailedResponse(transactionId);
        }
        if (tx.hasAnyRejection()) {
            return transactionRejectedResponse(transactionId);
        }

        tx.setTransactionApproved(true);

        TransactionEntity savedTx = accountingCoreTransactionRepository.save(tx);

        return Either.right(savedTx);
    }

    @Transactional
    // TODO optimise performance because we have to load transaction from db each time and we don't save it in bulk
    private Either<IdentifiableProblem, TransactionEntity> approveTransactionsDispatch(String transactionId) {
        log.info("Approving transaction to dispatch: {}", transactionId);

        Optional<TransactionEntity> txM = accountingCoreTransactionRepository.findById(transactionId);

        if (txM.isEmpty()) {
            return transactionNotFoundResponse(transactionId);
        }

        TransactionEntity tx = txM.orElseThrow();

        if (tx.getAutomatedValidationStatus() == FAILED) {
            return transactionFailedResponse(transactionId);
        }

        if (tx.hasAnyRejection()) {
            return transactionRejectedResponse(transactionId);
        }

        if (!tx.getTransactionApproved()) {
            ThrowableProblem problem = Problem.builder()
                    .withTitle("TX_NOT_APPROVED")
                    .withDetail(STR."Cannot approve for dispatch / publish a transaction that has not been approved before, transactionId: \{transactionId}")
                    .withStatus(METHOD_NOT_ALLOWED)
                    .with("transactionId", transactionId)
                    .build();

            return Either.left(new IdentifiableProblem(transactionId, problem, TRANSACTION));
        }

        tx.setLedgerDispatchApproved(true);

        TransactionEntity savedTx = accountingCoreTransactionRepository.save(tx);

        return Either.right(savedTx);
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionEntity>> approveTransactions(TransactionsRequest transactionsRequest) {
        Set<TransactionsRequest.TransactionId> transactionIds = transactionsRequest.getTransactionIds();

        ArrayList<Either<IdentifiableProblem, TransactionEntity>> transactionsApprovalResponseListE = new ArrayList<Either<IdentifiableProblem, TransactionEntity>>();
        Set<String> batchIds = new HashSet<>();
        for (TransactionsRequest.TransactionId transactionId : transactionIds) {
            try {
                Either<IdentifiableProblem, TransactionEntity> transactionEntities = approveTransaction(transactionId.getId());
                if(transactionEntities.isRight()) {
                    batchIds.add(transactionEntities.get().getBatchId());
                }
                transactionsApprovalResponseListE.add(transactionEntities);
            } catch (DataAccessException dae) {
                log.error("Error approving transaction: {}", transactionId, dae);

                ThrowableProblem problem = FailureResponses.createTransactionDBError(transactionId.getId(), dae);

                transactionsApprovalResponseListE.add(Either.left(new IdentifiableProblem(transactionId.getId(), problem, TRANSACTION)));
            }
        }

        batchIds.forEach(batchId -> transactionBatchService.invokeUpdateTransactionBatchStatusAndStats(batchId, Optional.empty(), Optional.empty()));


        return transactionsApprovalResponseListE;
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionEntity>> approveTransactionsDispatch(TransactionsRequest transactionsRequest) {
        Set<TransactionsRequest.TransactionId> transactionIds = transactionsRequest.getTransactionIds();

        ArrayList<Either<IdentifiableProblem, TransactionEntity>> transactionsApprovalResponseListE = new ArrayList<Either<IdentifiableProblem, TransactionEntity>>();
        for (TransactionsRequest.TransactionId transactionId : transactionIds) {
            try {
                Either<IdentifiableProblem, TransactionEntity> transactionEntities = approveTransactionsDispatch(transactionId.getId());

                transactionsApprovalResponseListE.add(transactionEntities);
            } catch (DataAccessException dae) {
                log.error("Error approving transaction publish / dispatch: {}", transactionId, dae);

                ThrowableProblem problem = createTransactionDBError(transactionId.getId(), dae);

                transactionsApprovalResponseListE.add(Either.left(new IdentifiableProblem(transactionId.getId(), problem, TRANSACTION)));
            }
        }

        transactionsApprovalResponseListE.stream().filter(Either::isRight).forEach(e -> {
            TransactionEntity transaction = e.get();
            transactionBatchService.invokeUpdateTransactionBatchStatusAndStats(transaction.getBatchId(), Optional.empty(), Optional.empty());
        });

        return transactionsApprovalResponseListE;
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionItemEntity>> rejectTransactionItems(TransactionEntity tx, Set<TxItemRejectionRequest> transactionItemsRejections) {
        log.info("Rejecting transaction items: {}", transactionItemsRejections);

        ArrayList<Either<IdentifiableProblem, TransactionItemEntity>> transactionItemEntitiesE = new ArrayList<Either<IdentifiableProblem, TransactionItemEntity>>();

        for (TxItemRejectionRequest txItemRejection : transactionItemsRejections) {
            String txItemId = txItemRejection.getTxItemId();
            RejectionReason rejectionReason = txItemRejection.getRejectionReason();

            Optional<TransactionItemEntity> txItemM = transactionItemRepository.findByTxIdAndItemId(tx.getId(), txItemId);

            if (txItemM.isEmpty()) {
                transactionItemEntitiesE.add(transactionItemNotFoundResponse(tx.getId(), txItemId));
                continue;
            }

            TransactionItemEntity txItem = txItemM.orElseThrow();
            if (tx.getLedgerDispatchApproved()) {
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
        transactionBatchService.invokeUpdateTransactionBatchStatusAndStats(tx.getBatchId(), Optional.empty(), Optional.empty());

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
        return accountingCoreTransactionRepository.findAllByStatus(organisationId, validationStatuses, transactionType, pageRequest);
    }

    public List<TransactionEntity> listAll() {
        return accountingCoreTransactionRepository.findAll();
    }

    public Set<TransactionEntity> findAllByDateRangeAndNotReconciledYet(String organisationId,
                                                                        LocalDate from,
                                                                        LocalDate to) {
        return accountingCoreTransactionRepository.findByEntryDateRangeAndNotReconciledYet(organisationId, from, to);
    }

}
