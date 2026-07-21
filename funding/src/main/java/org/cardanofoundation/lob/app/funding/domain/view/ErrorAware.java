package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.Optional;

import org.springframework.http.ProblemDetail;

/**
 * A response object that may carry a {@link ProblemDetail} describing a failure. Controllers use the
 * presence of the error to drive the HTTP status of the response.
 */
public interface ErrorAware {

    Optional<ProblemDetail> getError();
}
