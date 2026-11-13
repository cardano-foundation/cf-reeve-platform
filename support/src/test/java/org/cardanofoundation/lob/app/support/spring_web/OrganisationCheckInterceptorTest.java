package org.cardanofoundation.lob.app.support.spring_web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

class OrganisationCheckInterceptorTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";

    private final KeycloakSecurityHelper securityHelper = mock(KeycloakSecurityHelper.class);
    private final OrganisationCheckInterceptor interceptor = new OrganisationCheckInterceptor(securityHelper, new ObjectMapper());

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null));
        when(securityHelper.canUserAccessOrg(ORG_A)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameOrganisationEverywhere_passes() throws Exception {
        MockHttpServletRequest request = json("{\"organisationId\":\"org-a\"}");
        pathVariables(request, Map.of("orgId", ORG_A));
        request.addParameter("organisationId", ORG_A);

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
    }

    @Test
    void pathAndBodyDiffer_isRefused() throws Exception {
        MockHttpServletRequest request = json("{\"organisationId\":\"org-b\"}");
        pathVariables(request, Map.of("orgId", ORG_A));

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void pathAndQueryDiffer_isRefused() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        pathVariables(request, Map.of("organisationId", ORG_A));
        request.addParameter("organisationId", ORG_B);

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void twoQueryValues_isRefused() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("organisationId", ORG_A, ORG_B);

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void multipartFieldDiffersFromPath_isRefused() throws Exception {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addParameter("organisationId", ORG_B);
        pathVariables(request, Map.of("orgId", ORG_A));

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void organisationTheUserIsNotIn_isForbiddenNotUnauthorized() throws Exception {
        MockHttpServletRequest request = json("{\"organisationId\":\"org-b\",\"other\":1}");

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        // 401 would sign the user out in the frontend
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void multipartOrganisationTheUserIsNotIn_isForbidden() throws Exception {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addParameter("organisationId", ORG_B);

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void withoutOrganisation_isLeftToTheEndpoint() throws Exception {
        assertThat(interceptor.preHandle(json("{}"), response, null)).isTrue();
        assertThat(interceptor.preHandle(json("not json"), response, null)).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), response, null)).isTrue();
    }

    @Test
    void headerIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Organisation-Id", ORG_B);
        request.addParameter("organisationId", ORG_A);

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
    }

    private static MockHttpServletRequest json(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void pathVariables(MockHttpServletRequest request, Map<String, String> variables) {
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
    }

}
