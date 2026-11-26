package org.cardanofoundation.lob.app.keri_attestation.resource;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;

final class Responses {

    private Responses() {
    }

    static <T> ResponseEntity<Object> respond(Either<ProblemDetail, T> result, HttpStatus successStatus) {
        return result.<ResponseEntity<Object>>fold(
                problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                body -> ResponseEntity.status(successStatus).body(body));
    }

    static ResponseEntity<Object> respondDelete(Optional<ProblemDetail> error) {
        return error.<ResponseEntity<Object>>map(problem -> ResponseEntity.status(problem.getStatus()).body(problem))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
