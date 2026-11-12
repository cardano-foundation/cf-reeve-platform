package org.cardanofoundation.lob.app.support.security.access;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Access of the user behind the current request. The organisation a request acts on is not part of it: endpoints pass
 * their own {@code organisationId} to {@link PermissionChecker#can}.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserAccess {

    private static final String REQUEST_ATTRIBUTE = CurrentUserAccess.class.getName();

    private final UserAccessResolver userAccessResolver;

    public Optional<Jwt> jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(jwt);
        }
        return Optional.empty();
    }

    public UserAccess get() {
        Optional<Jwt> jwt = jwt();
        if (jwt.isEmpty()) {
            return UserAccess.none(null);
        }
        Optional<HttpServletRequest> request = currentRequest();
        if (request.isPresent() && request.get().getAttribute(REQUEST_ATTRIBUTE) instanceof UserAccess cached) {
            return cached;
        }
        UserAccess access = userAccessResolver.resolve(jwt.get());
        request.ifPresent(r -> r.setAttribute(REQUEST_ATTRIBUTE, access));
        return access;
    }

    public void evict() {
        jwt().ifPresent(userAccessResolver::evict);
        currentRequest().ifPresent(request -> request.removeAttribute(REQUEST_ATTRIBUTE));
    }

    private static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.of(attributes.getRequest());
        }
        return Optional.empty();
    }

}
