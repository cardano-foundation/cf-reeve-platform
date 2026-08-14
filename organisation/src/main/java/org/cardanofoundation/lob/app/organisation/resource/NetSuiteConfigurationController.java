package org.cardanofoundation.lob.app.organisation.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.NetSuiteConfigurationStatusView;
import org.cardanofoundation.lob.app.organisation.service.NetSuiteConfigAdminService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Admin-only NetSuite credential administration.
 * <p>
 * There is deliberately no DELETE, and the private key is never returned by any endpoint.
 * Writes return {@code 202 Accepted} because whether NetSuite accepts the credentials is not
 * known at request time — the verdict arrives asynchronously and lands on the status endpoint.
 */
@RestController
@ConditionalOnProperty(name = "lob.organisation.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/v1")
@Tag(name = "NetSuite Configuration", description = "Per-organisation NetSuite configuration")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@Slf4j
public class NetSuiteConfigurationController {

    private final NetSuiteConfigAdminService netSuiteConfigAdminService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Operation(description = "NetSuite configuration status. Returns configured=false when none exists. Never returns the private key.",
            responses = {
                    @ApiResponse(responseCode = "200", content =
                            {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NetSuiteConfigurationStatusView.class))})
            })
    @GetMapping(value = "/organisations/{orgId}/netsuite-configuration/status", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> status(@PathVariable("orgId") String orgId) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return forbidden(orgId);
        }

        return ResponseEntity.ok(netSuiteConfigAdminService.status(orgId));
    }

    @Operation(description = "Create the NetSuite configuration for an organisation. Accepted asynchronously; poll the status endpoint for the verdict.",
            responses = {
                    @ApiResponse(responseCode = "202", content =
                            {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NetSuiteConfigurationStatusView.class))}),
                    @ApiResponse(responseCode = "409", description = "NETSUITE_CONFIGURATION_ALREADY_EXISTS", content =
                            {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))})
            })
    @PostMapping(value = "/organisations/{orgId}/netsuite-configuration",
            produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> create(@PathVariable("orgId") String orgId,
                                    @Valid @RequestBody NetSuiteConfigurationCreate body) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return forbidden(orgId);
        }

        return netSuiteConfigAdminService.create(orgId, body, keycloakSecurityHelper.getCurrentUser())
                .fold(problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                        view -> ResponseEntity.status(HttpStatus.ACCEPTED).body(view));
    }

    @Operation(description = "Update the NetSuite configuration. Leave privateKey empty to keep the stored key.",
            responses = {
                    @ApiResponse(responseCode = "202", content =
                            {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NetSuiteConfigurationStatusView.class))}),
                    @ApiResponse(responseCode = "404", description = "NETSUITE_CONFIGURATION_NOT_FOUND", content =
                            {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))})
            })
    @PutMapping(value = "/organisations/{orgId}/netsuite-configuration",
            produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> update(@PathVariable("orgId") String orgId,
                                    @Valid @RequestBody NetSuiteConfigurationUpdate body) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return forbidden(orgId);
        }

        return netSuiteConfigAdminService.update(orgId, body, keycloakSecurityHelper.getCurrentUser())
                .fold(problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                        view -> ResponseEntity.status(HttpStatus.ACCEPTED).body(view));
    }

    private ResponseEntity<ProblemDetail> forbidden(String orgId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "User cannot access organisation %s".formatted(orgId));
        problem.setTitle("ORGANISATION_ACCESS_DENIED");

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

}
