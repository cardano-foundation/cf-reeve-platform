package org.cardanofoundation.lob.app.funding.util;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Factory for the {@link ProblemDetail}s the funding services attach to their view responses. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String detail, String title) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    public static ProblemDetail badRequest(String detail, String title) {
        return of(HttpStatus.BAD_REQUEST, detail, title);
    }

    public static ProblemDetail notFound(String detail, String title) {
        return of(HttpStatus.NOT_FOUND, detail, title);
    }

    public static ProblemDetail conflict(String detail, String title) {
        return of(HttpStatus.CONFLICT, detail, title);
    }

    public static ProblemDetail unauthorized() {
        return of(HttpStatus.UNAUTHORIZED, "User does not have access to this organisation", "UNAUTHORIZED");
    }
}
