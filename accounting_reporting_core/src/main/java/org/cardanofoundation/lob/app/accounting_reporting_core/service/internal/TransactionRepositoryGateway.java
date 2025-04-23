package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.FailureResponses.*;
import static org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem.IdType.TRANSACTION;
import static org.zalando.problem.Status.METHOD_NOT_ALLOWED;

import java.time.LocalDate;
import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Rejection;
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

        val txM = accountingCoreTransactionRepository.findById(transactionId);

        if (txM.isEmpty()) {
            return transactionNotFoundResponse(transactionId);
        }

        val tx = txM.orElseThrow();

        if (tx.getAutomatedValidationStatus() == FAILED) {
            return transactionFailedResponse(transactionId);
        }
        if (tx.hasAnyRejection()) {
            return transactionRejectedResponse(transactionId);
        }

        tx.setTransactionApproved(true);

        val savedTx = accountingCoreTransactionRepository.save(tx);

        return Either.right(savedTx);
    }

    @Transactional
    // TODO optimise performance because we have to load transaction from db each time and we don't save it in bulk
    private Either<IdentifiableProblem, TransactionEntity> approveTransactionsDispatch(String transactionId) {
        log.info("Approving transaction to dispatch: {}", transactionId);

        val txM = accountingCoreTransactionRepository.findById(transactionId);

        if (txM.isEmpty()) {
            return transactionNotFoundResponse(transactionId);
        }

        val tx = txM.orElseThrow();

        if (tx.getAutomatedValidationStatus() == FAILED) {
            return transactionFailedResponse(transactionId);
        }

        if (tx.hasAnyRejection()) {
            return transactionRejectedResponse(transactionId);
        }

        if (!tx.getTransactionApproved()) {
            val problem = Problem.builder()
                    .withTitle("TX_NOT_APPROVED")
                    .withDetail(STR."Cannot approve for dispatch / publish a transaction that has not been approved before, transactionId: \{transactionId}")
                    .withStatus(METHOD_NOT_ALLOWED)
                    .with("transactionId", transactionId)
                    .build();

            return Either.left(new IdentifiableProblem(transactionId, problem, TRANSACTION));
        }

        tx.setLedgerDispatchApproved(true);

        val savedTx = accountingCoreTransactionRepository.save(tx);

        return Either.right(savedTx);
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionEntity>> approveTransactions(TransactionsRequest transactionsRequest) {
        val transactionIds = transactionsRequest.getTransactionIds();

        val transactionsApprovalResponseListE = new ArrayList<Either<IdentifiableProblem, TransactionEntity>>();
        for (val transactionId : transactionIds) {
            try {
                val transactionEntities = approveTransaction(transactionId.getId());

                transactionsApprovalResponseListE.add(transactionEntities);
            } catch (DataAccessException dae) {
                log.error("Error approving transaction: {}", transactionId, dae);

                val problem = FailureResponses.createTransactionDBError(transactionId.getId(), dae);

                transactionsApprovalResponseListE.add(Either.left(new IdentifiableProblem(transactionId.getId(), problem, TRANSACTION)));
            }
        }

        return transactionsApprovalResponseListE;
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionEntity>> approveTransactionsDispatch(TransactionsRequest transactionsRequest) {
        val transactionIds = transactionsRequest.getTransactionIds();

        val transactionsApprovalResponseListE = new ArrayList<Either<IdentifiableProblem, TransactionEntity>>();
        for (val transactionId : transactionIds) {
            try {
                val transactionEntities = approveTransactionsDispatch(transactionId.getId());

                transactionsApprovalResponseListE.add(transactionEntities);
            } catch (DataAccessException dae) {
                log.error("Error approving transaction publish / dispatch: {}", transactionId, dae);

                val problem = createTransactionDBError(transactionId.getId(), dae);

                transactionsApprovalResponseListE.add(Either.left(new IdentifiableProblem(transactionId.getId(), problem, TRANSACTION)));
            }
        }

        return transactionsApprovalResponseListE;
    }

    @Transactional
    public List<Either<IdentifiableProblem, TransactionItemEntity>> rejectTransactionItems(TransactionEntity tx, Set<TxItemRejectionRequest> transactionItemsRejections) {
        log.info("Rejecting transaction items: {}", transactionItemsRejections);

        val transactionItemEntitiesE = new ArrayList<Either<IdentifiableProblem, TransactionItemEntity>>();

        for (val txItemRejection : transactionItemsRejections) {
            val txItemId = txItemRejection.getTxItemId();
            val rejectionReason = txItemRejection.getRejectionReason();

            val txItemM = transactionItemRepository.findByTxIdAndItemId(tx.getId(), txItemId);

            if (txItemM.isEmpty()) {
                transactionItemEntitiesE.add(transactionItemNotFoundResponse(tx.getId(), txItemId));
                continue;
            }

            val txItem = txItemM.orElseThrow();
            if (tx.getLedgerDispatchApproved()) {
                transactionItemEntitiesE.add(transactionItemCannotRejectAlreadyApprovedForDispatchResponse(tx.getId(), txItemId));
                continue;
            }

            txItem.setRejection(Optional.of(new Rejection(rejectionReason)));

            val savedTxItem = transactionItemRepository.save(txItem);

            transactionItemEntitiesE.add(Either.right(savedTxItem));
        }

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
                                                   List<TransactionType> transactionType) {
        return accountingCoreTransactionRepository.findAllByStatus(organisationId, validationStatuses, transactionType);
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
