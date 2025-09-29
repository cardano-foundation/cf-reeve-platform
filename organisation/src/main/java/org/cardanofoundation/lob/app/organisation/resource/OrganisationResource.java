package org.cardanofoundation.lob.app.organisation.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.*;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class OrganisationResource {

    private final OrganisationService organisationService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Operation(description = "Organisations",
            parameters = {
                    @Parameter(
                            name = "orgIds",
                            description = "Optional list of organisation IDs",
                            in = ParameterIn.QUERY,
                            array = @ArraySchema(schema = @Schema(type = "string"))
                    )
            }, responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = OrganisationView.class)))}
            ),
    })
    @GetMapping(value = "/organisations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<OrganisationView>> organisationList(@RequestParam(value = "orgIds", required = false) Optional<String[]> orgIds) {
        return ResponseEntity.ok().body(
                orgIds.map(orgs -> Arrays.stream(orgs)
                        .map(organisationService::findById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(organisationService::getOrganisationView)
                        .toList()).orElse(organisationService.findAll().stream().map(organisationService::getOrganisationView).toList())
        );
    }

    @Operation(description = "Transaction types", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = OrganisationView.class))}
            ),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(example =
                    """
                            {
                            "title": "Organisation not found",
                            "status": 404,
                            "detail": "Unable to get the organisation"
                            }
                            """
            ))})
    })
    @GetMapping(value = "/organisations/{orgId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> organisationDetailSpecific(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        Optional<OrganisationView> organisation = organisationService.findById(orgId).map(organisationService::getOrganisationView);
        if (organisation.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND)
                    .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_ORGANISATION_BY_ID_S.formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        return ResponseEntity.ok().body(organisation);
    }

    @Operation(description = "Organisation Events", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = EventView.class)))}
            ),
    })
    @GetMapping(value = "/organisations/{orgId}/events", produces = "application/json")
    public ResponseEntity<List<EventView>> organisationEvent(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                organisationService.getOrganisationEventCode(orgId).stream().map(accountEvent -> {
                    return new EventView(
                            accountEvent.getCustomerCode(),
                            accountEvent.getId().getOrganisationId(),
                            accountEvent.getName()
                    );
                }).toList()
        );
    }

    @Operation(description = "Organistion create", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = OrganisationView.class))}
            ),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(example =
                    """
                            {
                            "title": "ORGANISATION_ALREADY_EXIST",
                            "status": 404,
                            "detail": "Unable to crate Organisation with IdNumber"
                            }
                            """
            ))})
    })
    @PostMapping(value = "/organisations", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> organisationCreate(@Valid @RequestBody OrganisationCreate organisationCreate) {

        Optional<Organisation> organisationChe = organisationService.findById(Organisation.id(organisationCreate.getCountryCode(), organisationCreate.getTaxIdNumber()));
        if (organisationChe.isPresent()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_ALREADY_EXIST")
                    .withDetail("Unable to crate Organisation with IdNumber: %s and CountryCode: %s".formatted(organisationCreate.getTaxIdNumber(), organisationCreate.getCountryCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        Optional<OrganisationView> organisation = organisationService.createOrganisation(organisationCreate).map(organisationService::getOrganisationView);
        if (organisation.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_CREATE_ERROR")
                    .withDetail("Unable to create Organisation by Id: %s".formatted(organisationCreate.getName()))
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        return ResponseEntity.ok().body(organisation.get());


    }

    @Operation(description = "Organistion update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = OrganisationView.class))}
            ),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = "application/json", schema = @Schema(example =
                    """
                            {
                            "title": "Organisation not found",
                            "status": 404,
                            "detail": "Unable to get the organisation"
                            }
                            """
            ))}),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = "application/json", schema = @Schema(example =
                    """
                            {
                            "title": "ORGANISATION_UPDATE_ERROR",
                            "status": 404,
                            "detail": "Unable to create Organisation"
                            }
                            """
            ))})
    })
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @PutMapping(value = "/organisations/{orgId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> organisationUpdate(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody OrganisationUpdate organisationUpdate) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND)
                    .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_ORGANISATION_BY_ID_S.formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        Optional<OrganisationView> organisation = organisationService.updateOrganisation(organisationChe.get(), organisationUpdate).map(organisationService::getOrganisationView);
        if (organisation.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_UPDATE_ERROR")
                    .withDetail("Unable to create Organisation by Id: %s".formatted(organisationUpdate.getName()))
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        return ResponseEntity.ok().body(organisation.get());


    }

    @Operation(description = "Organisation validation", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ValidationView.class))}
            ),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = "application/json", schema = @Schema(example =
                    """
                            {
                            "title: "Organisation not found",
                            "status": 404,
                            "detail": "Unable to find Organisation by Id"
                            }
                            """
            ))})
    })
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    @GetMapping(value = "/organisations/{orgId}/validate", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateOrganisation(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")  String orgId) {
        if (keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            Optional<Organisation> organisationOptional = organisationService.findById(orgId);
            if (organisationOptional.isEmpty()) {
                ThrowableProblem issue = Problem.builder()
                        .withTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND)
                        .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_ORGANISATION_BY_ID_S.formatted(orgId))
                        .withStatus(Status.NOT_FOUND)
                        .build();

                return ResponseEntity.status(Objects.requireNonNull(issue.getStatus()).getStatusCode()).body(issue);
            } else {
                return ResponseEntity.ok(organisationService.validateOrganisation(organisationOptional.get()));
            }
        } else {
            return ResponseEntity.status(HttpStatusCode.valueOf(403)).body("User is not allowed to access this organisation");
        }
    }

}
