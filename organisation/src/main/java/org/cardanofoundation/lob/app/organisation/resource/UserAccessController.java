package org.cardanofoundation.lob.app.organisation.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.view.UserAccessView;
import org.cardanofoundation.lob.app.organisation.domain.view.UserAccessView.OrganisationAccessView;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;
import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;
import org.cardanofoundation.lob.app.support.security.access.OrganisationAccess;
import org.cardanofoundation.lob.app.support.security.access.UserAccess;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "User access", description = "Organisations, roles, modules and permissions of the logged in user")
@RequiredArgsConstructor
public class UserAccessController {

    private final CurrentUserAccess currentUserAccess;
    private final OrganisationService organisationService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Operation(description = "Everything the logged in user can access, per organisation. The frontend uses it to build the organisation switcher and to show or hide features.")
    @GetMapping(value = "/access", produces = APPLICATION_JSON_VALUE)
    // Any logged in user: it's how the frontend learns the permissions, also of a user without an organisation
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserAccessView> access() {
        UserAccess access = currentUserAccess.get();

        List<OrganisationAccessView> organisations = access.organisations().values().stream()
                .map(this::toView)
                .toList();

        return ResponseEntity.ok(new UserAccessView(access.username(), keycloakSecurityHelper.getCurrentUser(), organisations,
                access.platformPermissions().stream().sorted().toList()));
    }

    private OrganisationAccessView toView(OrganisationAccess organisation) {
        String name = organisationService.findById(organisation.organisationId())
                .map(Organisation::getName)
                .orElse(null);
        return new OrganisationAccessView(
                organisation.organisationId(),
                name,
                organisation.roles().stream().sorted().toList(),
                organisation.modules().stream().sorted().toList(),
                organisation.permissions().stream().sorted().toList());
    }

}
