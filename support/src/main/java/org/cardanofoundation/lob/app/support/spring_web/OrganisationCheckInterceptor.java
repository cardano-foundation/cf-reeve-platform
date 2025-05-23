package org.cardanofoundation.lob.app.support.spring_web;


import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@RequiredArgsConstructor
@Slf4j
@Component
public class OrganisationCheckInterceptor implements HandlerInterceptor {

    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Skipping anomymous user in this step, they aren't part of any organisation
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if(authentication != null && authentication.getPrincipal().equals("anonymousUser")) {
            return true;
        }
        if (request.getContentType() != null && request.getContentType().contains("application/json")) {
            String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            try {
                BaseRequest baseRequest = objectMapper.readValue(body, BaseRequest.class);
                // orgId is not set within the body, thus skipping as well
                if(baseRequest.getOrganisationId().isBlank()) {
                    return true;
                }
                boolean isUserInOrganisation = keycloakSecurityHelper.canUserAccessOrg(baseRequest.getOrganisationId());
                if (!isUserInOrganisation) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("User does not have access to this organisation");
                    return false;
                }
            } catch (Exception e) {
                log.debug("Error parsing request body: {}", e.getMessage());
            }
        }


        return true;
    }

}
