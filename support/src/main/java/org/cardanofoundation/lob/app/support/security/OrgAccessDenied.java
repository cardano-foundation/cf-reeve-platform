package org.cardanofoundation.lob.app.support.security;

import static org.springframework.http.HttpStatus.FORBIDDEN;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Shared 403 response for endpoints guarded by {@link KeycloakSecurityHelper#canUserAccessOrg(String)}. Never 401: the
 * frontend signs the user out on 401, which is only for a missing or invalid token.
 * {@link #response()} is generic so callers can keep their endpoint's concrete success return type
 * instead of widening it to {@code ResponseEntity<?>} just to accommodate this one error case.
 */
public final class OrgAccessDenied {

    private OrgAccessDenied() {
    }

    public static ProblemDetail problem() {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(FORBIDDEN, "The user doesn't have access to this org");
        problemDetail.setTitle("NO_ACCESS_TO_ORG");
        return problemDetail;
    }

    @SuppressWarnings("unchecked")
    public static <T> ResponseEntity<T> response() {
        return (ResponseEntity<T>) ResponseEntity.status(FORBIDDEN.value()).body(problem());
    }

}
