package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem.IdType.TRANSACTION;
import static org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem.IdType.TRANSACTION_ITEM;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Cannot approve a failed transaction, transactionId: %s".formatted(transactionId));
        problem.setTitle("CANNOT_APPROVE_FAILED_TX");
        problem.setProperty(TRANSACTION_ID, transactionId);

        return Either.left(new IdentifiableProblem(transactionId, problem, TRANSACTION));
    }

    public static Either<IdentifiableProblem, TransactionEntity> transactionRejectedResponse(String transactionId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Cannot approve a rejected transaction, transactionId: %s".formatted(transactionId));
        problem.setTitle("CANNOT_APPROVE_REJECTED_TX");
        problem.setProperty(TRANSACTION_ID, transactionId);

        return Either.left(new IdentifiableProblem(transactionId, problem, TRANSACTION));
    }

    public static Either<IdentifiableProblem, TransactionEntity> transactionNotFoundResponse(String txId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Transaction with id %s not found".formatted(txId));
        problem.setTitle("TX_NOT_FOUND");
        problem.setProperty(TRANSACTION_ID, txId);

        return Either.left(new IdentifiableProblem(txId, problem, TRANSACTION));
    }

    public static ProblemDetail createTransactionDBError(String transactionId, DataAccessException dae) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "DB error approving transaction publish:%s".formatted(transactionId));
        problem.setTitle("DB_ERROR");
        problem.setProperty(TRANSACTION_ID, transactionId);
        problem.setProperty("error", dae.getMessage());
        return problem;
    }

    public static List<Either<IdentifiableProblem, TransactionItemEntity>> transactionNotFoundResponse(TransactionItemsRejectionRequest transactionItemsRejectionRequest,
                                                                                                        String transactionId) {
        return transactionItemsRejectionRequest.getTransactionItemsRejections()
                .stream()
                .map(txItemRejectionRequest -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Transaction with id %s not found".formatted(transactionId));
                    problem.setTitle("TX_NOT_FOUND");
                    problem.setProperty(TRANSACTION_ID, transactionId);

                    return Either.<IdentifiableProblem, TransactionItemEntity>left(new IdentifiableProblem(transactionId, problem, TRANSACTION));
                }).toList();
    }

    public static Either<IdentifiableProblem, TransactionItemEntity> transactionItemCannotRejectAlreadyApprovedForDispatchResponse(String transactionId,
                                                                                                                                   String txItemId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Cannot reject transaction item %s because transaction %s has already been approved for dispatch".formatted(txItemId, transactionId));
        problem.setTitle("TX_ALREADY_APPROVED_CANNOT_REJECT_TX_ITEM");
        problem.setProperty(TRANSACTION_ID, transactionId);
        problem.setProperty("transactionItemId", txItemId);

        return Either.left(new IdentifiableProblem(txItemId, problem, TRANSACTION_ITEM));
    }

    public static Either<IdentifiableProblem, TransactionItemEntity> transactionItemNotFoundResponse(String transactionId,
                                                                                                     String txItemId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Transaction item with id %s not found".formatted(txItemId));
        problem.setTitle("TX_ITEM_NOT_FOUND");
        problem.setProperty(TRANSACTION_ID, transactionId);
        problem.setProperty("transactionItemId", txItemId);

        return Either.left(new IdentifiableProblem(txItemId, problem, TRANSACTION_ITEM));
    }

}
