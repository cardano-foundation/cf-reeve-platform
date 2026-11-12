package org.cardanofoundation.lob.app.support.spring_web;


import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Checks the {@code organisationId} of a request before the endpoint runs, wherever it arrives: path, query, multipart
 * or form field, or top-level JSON body field ({@code organisationId}, or {@code orgId} in paths and parameters).
 * <ul>
 *     <li>A request that carries it in more than one place with different values is refused, so one value can't be
 *     used for the permission check while another one is used to read data.</li>
 *     <li>A user who isn't a member of the organisation gets 403. Never 401: the frontend signs the user out on 401,
 *     which is only for a missing or invalid token.</li>
 * </ul>
 * Each endpoint still checks its permission in that organisation ({@code @permissions.can(#organisationId, ...)}).
 */
@RequiredArgsConstructor
@Slf4j
@Component
public class OrganisationCheckInterceptor implements HandlerInterceptor {

    private static final Set<String> ORGANISATION_NAMES = Set.of("organisationId", "orgId");
    private static final String BODY_ORGANISATION = "organisationId";

    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Anonymous requests aren't part of any organisation
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || "anonymousUser".equals(authentication.getPrincipal())) {
            return true;
        }

        Set<String> organisationIds = new LinkedHashSet<>();
        addPathVariables(request, organisationIds);
        addParameters(request, organisationIds);
        addJsonBody(request, organisationIds);

        if (organisationIds.size() > 1) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("The request names more than one organisation");
            return false;
        }
        if (organisationIds.size() == 1 && !keycloakSecurityHelper.canUserAccessOrg(organisationIds.iterator().next())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("User does not have access to this organisation");
            return false;
        }
        return true;
    }

    private static void addPathVariables(HttpServletRequest request, Set<String> organisationIds) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables) {
            ORGANISATION_NAMES.forEach(name -> add(variables.get(name), organisationIds));
        }
    }

    // Query string, and the fields of multipart and url-encoded forms
    private static void addParameters(HttpServletRequest request, Set<String> organisationIds) {
        ORGANISATION_NAMES.forEach(name -> {
            String[] values = request.getParameterValues(name);
            if (values != null) {
                for (String value : values) {
                    add(value, organisationIds);
                }
            }
        });
    }

    private void addJsonBody(HttpServletRequest request, Set<String> organisationIds) {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.contains("application/json")) {
            return;
        }
        try {
            String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return;
            }
            JsonNode organisationId = objectMapper.readTree(body).path(BODY_ORGANISATION);
            if (organisationId.isTextual()) {
                add(organisationId.asText(), organisationIds);
            }
        } catch (Exception e) {
            // Not JSON the endpoint can read either: it fails on its own, and its permission check still applies
            log.debug("Error parsing request body: {}", e.getMessage());
        }
    }

    private static void add(Object value, Set<String> organisationIds) {
        if (value instanceof String organisationId && !organisationId.isBlank()) {
            organisationIds.add(organisationId.trim());
        }
    }

}
