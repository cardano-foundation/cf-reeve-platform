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
 * Access of the user behind the current request, and the organisation the request is made for.
 * <p>
 * The frontend sends the selected organisation in the {@value #ORGANISATION_HEADER} header. Permissions are
 * evaluated against that organisation, and organisation ids in request bodies/paths must match it, so a role
 * held in one organisation can never be used in another.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserAccess {

    public static final String ORGANISATION_HEADER = "X-Organisation-Id";

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

    /**
     * @return the organisation id sent in the {@value #ORGANISATION_HEADER} header, if any
     */
    public Optional<String> requestedOrganisationId() {
        return currentRequest()
                .map(request -> request.getHeader(ORGANISATION_HEADER))
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    /**
     * The organisation the current request acts on: the one in the header, or the user's only organisation
     * when the header is missing.
     */
    public Optional<OrganisationAccess> currentOrganisation() {
        UserAccess access = get();
        Optional<String> requested = requestedOrganisationId();
        if (requested.isPresent()) {
            return access.organisation(requested.get());
        }
        if (access.organisations().size() == 1) {
            return access.organisations().values().stream().findFirst();
        }
        return Optional.empty();
    }

    private static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.of(attributes.getRequest());
        }
        return Optional.empty();
    }

}
