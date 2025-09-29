package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem.IdType.TRANSACTION;
import static org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem.IdType.TRANSACTION_ITEM;
import static org.zalando.problem.Status.METHOD_NOT_ALLOWED;

import java.util.List;

import org.springframework.dao.DataAccessException;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.TransactionItemsRejectionRequest;
import org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem;

public final class FailureResponses {

    private FailureResponses() {
        // Utility class, no instantiation
    }

    private static final String TRANSACTION_ID = "transactionId";

    public static Either<IdentifiableProblem, TransactionEntity> transactionFailedResponse(String transactionId) {
        ThrowableProblem problem = Problem.builder()
                .withTitle("CANNOT_APPROVE_FAILED_TX")
                .withDetail("Cannot approve a failed transaction, transactionId: %s".formatted(transactionId))
                .with(TRANSACTION_ID, transactionId)
                .build();

        return Either.left(new IdentifiableProblem(transactionId, problem, TRANSACTION));
    }

    public static Either<IdentifiableProblem, TransactionEntity> transactionRejectedResponse(String transactionId) {
        ThrowableProblem problem = Problem.builder()
                .withTitle("CANNOT_APPROVE_REJECTED_TX")
                .withDetail("Cannot approve a rejected transaction, transactionId: %s".formatted(transactionId))
                .withStatus(METHOD_NOT_ALLOWED)
                .with(TRANSACTION_ID, transactionId)
                .build();

        return Either.left(new IdentifiableProblem(transactionId, problem, TRANSACTION));
    }

    public static Either<IdentifiableProblem, TransactionEntity> transactionNotFoundResponse(String txId) {
        ThrowableProblem problem = Problem.builder()
                .withTitle("TX_NOT_FOUND")
                .withDetail("Transaction with id %s not found".formatted(txId))
                .with(TRANSACTION_ID, txId)
                .build();

        return Either.left(new IdentifiableProblem(txId, problem, TRANSACTION));
    }

    public static ThrowableProblem createTransactionDBError(String transactionId, DataAccessException dae) {
        return Problem.builder()
                .withTitle("DB_ERROR")
                .withDetail("DB error approving transaction publish:%s".formatted(transactionId))
                .with(TRANSACTION_ID, transactionId)
                .with("error", dae.getMessage())
                .build();
    }

    public static List<Either<IdentifiableProblem, TransactionItemEntity>> transactionNotFoundResponse(TransactionItemsRejectionRequest transactionItemsRejectionRequest,
                                                                                                        String transactionId) {
        return transactionItemsRejectionRequest.getTransactionItemsRejections()
                .stream()
                .map(txItemRejectionRequest -> {
                    ThrowableProblem problem = Problem.builder()
                            .withTitle("TX_NOT_FOUND")
                            .withDetail("Transaction with id %s not found".formatted(transactionId))
                            .with(TRANSACTION_ID, transactionId)
                            .build();

                    return Either.<IdentifiableProblem, TransactionItemEntity>left(new IdentifiableProblem(transactionId, problem, TRANSACTION));
                }).toList();
    }

    public static Either<IdentifiableProblem, TransactionItemEntity> transactionItemCannotRejectAlreadyApprovedForDispatchResponse(String transactionId,
                                                                                                                                   String txItemId) {
        ThrowableProblem problem = Problem.builder()
                .withTitle("TX_ALREADY_APPROVED_CANNOT_REJECT_TX_ITEM")
                .withDetail("Cannot reject transaction item %s because transaction %s has already been approved for dispatch".formatted(txItemId, transactionId))
                .with(TRANSACTION_ID, transactionId)
                .with("transactionItemId", txItemId)
                .build();

        return Either.left(new IdentifiableProblem(txItemId, problem, TRANSACTION_ITEM));
    }

    public static Either<IdentifiableProblem, TransactionItemEntity> transactionItemNotFoundResponse(String transactionId,
                                                                                                     String txItemId) {
        ThrowableProblem problem = Problem.builder()
                .withTitle("TX_ITEM_NOT_FOUND")
                .withDetail("Transaction item with id %s not found".formatted(txItemId))
                .with(TRANSACTION_ID, transactionId)
                .with("transactionItemId", txItemId)
                .build();

        return Either.left(new IdentifiableProblem(txItemId, problem, TRANSACTION_ITEM));
    }

}
