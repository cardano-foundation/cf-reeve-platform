package org.cardanofoundation.lob.app.funding.resource;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.cardanofoundation.lob.app.funding.domain.view.ErrorAware;

/** Builds responses whose HTTP status is driven by the {@code error} carried in the response body. */
final class Responses {

    private Responses() {
    }

    /**
     * Returns the body with the error's HTTP status when an error is present, otherwise with the
     * given success status.
     */
    static <T extends ErrorAware> ResponseEntity<T> respond(T body, HttpStatus successStatus) {
        return body.getError()
                .map(problem -> ResponseEntity.status(problem.getStatus()).body(body))
                .orElseGet(() -> ResponseEntity.status(successStatus).body(body));
    }

    /** Delete responses: 204 No Content on success, or the problem detail with its status on failure. */
    static ResponseEntity<ProblemDetail> respondDelete(Optional<ProblemDetail> error) {
        return error.map(problem -> ResponseEntity.status(problem.getStatus()).body(problem))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
