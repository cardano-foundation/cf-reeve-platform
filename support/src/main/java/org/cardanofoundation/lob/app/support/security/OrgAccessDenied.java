package org.cardanofoundation.lob.app.support.security;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Shared 401 response for endpoints guarded by {@link KeycloakSecurityHelper#canUserAccessOrg(String)}.
 * {@link #response()} is generic so callers can keep their endpoint's concrete success return type
 * instead of widening it to {@code ResponseEntity<?>} just to accommodate this one error case.
 */
public final class OrgAccessDenied {

    private OrgAccessDenied() {
    }

    public static ProblemDetail problem() {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(UNAUTHORIZED, "The user doesn't have access to this org");
        problemDetail.setTitle("NO_ACCESS_TO_ORG");
        return problemDetail;
    }

    @SuppressWarnings("unchecked")
    public static <T> ResponseEntity<T> response() {
        return (ResponseEntity<T>) ResponseEntity.status(UNAUTHORIZED.value()).body(problem());
    }

}
