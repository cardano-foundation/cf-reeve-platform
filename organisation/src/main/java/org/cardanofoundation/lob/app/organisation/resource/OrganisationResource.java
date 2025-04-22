package org.cardanofoundation.lob.app.organisation.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
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
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@RestController
@RequestMapping("/api")
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
                    required = false,
                    array = @ArraySchema(schema = @Schema(type = "string"))
            )
        }, responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationView.class)))}
            ),
    })
    @GetMapping(value = "/organisation", produces = "application/json")
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
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = OrganisationView.class))}
            ),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = "application/json", schema = @Schema(example = "{\n" +
                    "    \"title\": \"Organisation not found\",\n" +
                    "    \"status\": 404,\n" +
                    "    \"detail\": \"Unable to get the organisation\"\n" +
                    "}"))})
    })
    @GetMapping(value = "/organisation/{orgId}", produces = "application/json")
    public ResponseEntity<?> organisationDetailSpecific(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        Optional<OrganisationView> organisation = organisationService.findById(orgId).map(organisation1 -> {

            return organisationService.getOrganisationView(organisation1);
        });
        if (organisation.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        return ResponseEntity.ok().body(organisation);
    }

    @Operation(description = "Organisation cost center", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationCostCenterView.class)))}
            ),
    })
    @GetMapping(value = "/organisation/{orgId}/cost-center", produces = "application/json")
    public ResponseEntity<?> organisationCostCenter(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                organisationService.getAllCostCenter(orgId).stream().map(OrganisationCostCenterView::fromEntity).toList());

    }

    @Operation(description = "Organisation cost center", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationCostCenterView.class)))}
            ),
    })
    @GetMapping(value = "/organisation/{orgId}/project", produces = "application/json")
    public ResponseEntity<?> organisationProject(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                organisationService.getAllProjects(orgId).stream().map(OrganisationProjectView::fromEntity).toList());
    }

    @Operation(description = "Organisation Events", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationEventView.class)))}
            ),
    })
    @GetMapping(value = "/organisation/{orgId}/events", produces = "application/json")
    public ResponseEntity<List<OrganisationEventView>> organisationEvent(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                organisationService.getOrganisationEventCode(orgId).stream().map(accountEvent -> {
                    return new OrganisationEventView(
                            accountEvent.getCustomerCode(),
                            accountEvent.getId().getOrganisationId(),
                            accountEvent.getName()
                    );
                }).toList()
        );

    }

    @Operation(description = "Organisation Chart of acount type", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationCurrencyView.class)))}
            ),
    })
    @GetMapping(value = "/organisation/{orgId}/currencies", produces = "application/json")
    public ResponseEntity<Set<OrganisationCurrencyView>> organisationCurrencies(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                organisationService.getOrganisationCurrencies(orgId).stream().map(organisationCurrency -> {
                    return new OrganisationCurrencyView(
                            organisationCurrency.getId().getCustomerCode(),
                            organisationCurrency.getCurrencyId()
                    );
                }).collect(Collectors.toSet())
        );

    }

    @Operation(description = "Organistion create", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = OrganisationView.class))}
            ),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = "application/json", schema = @Schema(example = "{\n" +
                    "    \"title\": \"ORGANISATION_ALREADY_EXIST\",\n" +
                    "    \"status\": 404,\n" +
                    "    \"detail\": \"Unable to crate Organisation with IdNumber\"\n" +
                    "}"))})
    })
    @PostMapping(value = "/organisation", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> organisationCreate(@Valid @RequestBody OrganisationCreate organisationCreate) {

        Optional<Organisation> organisationChe = organisationService.findById(Organisation.id(organisationCreate.getCountryCode(), organisationCreate.getTaxIdNumber()));
        if (organisationChe.isPresent()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_ALREADY_EXIST")
                    .withDetail(STR."Unable to crate Organisation with IdNumber: \{organisationCreate.getTaxIdNumber()} and CountryCode: \{organisationCreate.getCountryCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        Optional<OrganisationView> organisation = organisationService.createOrganisation(organisationCreate).map(organisationService::getOrganisationView);
        if (organisation.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_CREATE_ERROR")
                    .withDetail(STR."Unable to create Organisation by Id: \{organisationCreate.getName()}")
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
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = "application/json", schema = @Schema(example = "{\n" +
                    "    \"title\": \"Organisation not found\",\n" +
                    "    \"status\": 404,\n" +
                    "    \"detail\": \"Unable to get the organisation\"\n" +
                    "}"))}),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = "application/json", schema = @Schema(example = "{\n" +
                    "    \"title\": \"ORGANISATION_UPDATE_ERROR\",\n" +
                    "    \"status\": 404,\n" +
                    "    \"detail\": \"Unable to create Organisation\"\n" +
                    "}"))})
    })
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @PostMapping(value = "/organisation/{orgId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> organisationUpdate(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody OrganisationUpdate organisationUpdate) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        Optional<OrganisationView> organisation = organisationService.upsertOrganisation(organisationChe.get(), organisationUpdate).map(organisationService::getOrganisationView);
        if (organisation.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_UPDATE_ERROR")
                    .withDetail(STR."Unable to create Organisation by Id: \{organisationUpdate.getName()}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        return ResponseEntity.ok().body(organisation.get());


    }

    @Operation(description = "Organistion validation", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = OrganisationValidationView.class))}
            ),
            @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = "application/json", schema = @Schema(example = "{\n" +
                    "    \"title\": \"Organisation not found\",\n" +
                    "    \"status\": 404,\n" +
                    "    \"detail\": \"Unable to get the organisation\"\n" +
                    "}"))})
    })
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    @GetMapping(value = "/organisation/validate/{orgId}", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateOrganisation(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")  String orgId) {
        if(keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            Optional<Organisation> organisationOptional = organisationService.findById(orgId);
            if(organisationOptional.isEmpty()) {
                ThrowableProblem issue = Problem.builder()
                        .withTitle("ORGANISATION_NOT_FOUND")
                        .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
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
