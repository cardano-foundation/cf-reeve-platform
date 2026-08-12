package org.cardanofoundation.lob.app.support.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.junit.jupiter.api.Test;

class OrgAccessDeniedTest {

    @Test
    void problem_setsUnauthorizedStatusTitleAndDetail() {
        ProblemDetail problem = OrgAccessDenied.problem();

        assertEquals(HttpStatus.UNAUTHORIZED.value(), problem.getStatus());
        assertEquals("NO_ACCESS_TO_ORG", problem.getTitle());
        assertEquals("The user doesn't have access to this org", problem.getDetail());
    }

    @Test
    void response_returns401WithProblemBody() {
        ResponseEntity<Object> response = OrgAccessDenied.response();

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ProblemDetail.class, response.getBody().getClass());

        ProblemDetail body = (ProblemDetail) response.getBody();
        assertEquals("NO_ACCESS_TO_ORG", body.getTitle());
    }
}
