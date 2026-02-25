package org.cardanofoundation.lob.app.support.problem_support;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import org.springframework.http.ProblemDetail;

/**
 * Wrapper class for ProblemDetail that includes an identifier for tracking specific entities
 * (e.g., transaction IDs, transaction item IDs) that caused the error.
 */
@Getter
@AllArgsConstructor
@ToString
public class IdentifiableProblem {

    private final String id;
    private final ProblemDetail problem;
    private final IdType idType;

    /**
     * Type of identifier being wrapped
     */
    public enum IdType {
        TRANSACTION,
        TRANSACTION_ITEM
    }

}
