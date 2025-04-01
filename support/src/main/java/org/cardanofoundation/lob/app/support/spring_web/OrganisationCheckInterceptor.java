package org.cardanofoundation.lob.app.support.spring_web;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.HandlerInterceptor;

import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@RequiredArgsConstructor
@Slf4j
public class OrganisationCheckInterceptor implements HandlerInterceptor {

    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        if(handler instanceof BaseRequest baseRequest) {
            boolean isUserInOrganisation = keycloakSecurityHelper.canUserAccessOrg(baseRequest.getOrganisationId());
            if(!isUserInOrganisation) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("User does not have access to this organisation");
                return false; // Stop processing the request
            }
        }
        return true; // Continue processing the request
    }

}
