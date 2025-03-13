package org.cardanofoundation.lob.app.organisation.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.val;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.*;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;

@RestController
@RequestMapping("/api")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class OrganisationResource {

    private final OrganisationService organisationService;

    @Operation(description = "Transaction types", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationView.class)))}
            ),
    })
    @GetMapping(value = "/organisation", produces = "application/json")
    public ResponseEntity<?> organisationList() {
        return ResponseEntity.ok().body(
                organisationService.findAll().stream().map(organisation -> {
                    val today = LocalDate.now();
                    val monthsAgo = today.minusMonths(organisation.getAccountPeriodDays());
                    val yesterday = today.minusDays(1);

                    return new OrganisationView(
                            organisation.getId(),
                            organisation.getName(),
                            organisation.getTaxIdNumber(),
                            organisation.getCurrencyId(),
                            organisation.getReportCurrencyId(),
                            monthsAgo,
                            yesterday,
                            organisation.getAdminEmail(),
                            organisation.getPhoneNumber(),
                            organisation.getAddress(),
                            organisation.getCity(),
                            organisation.getPostCode(),
                            organisation.getProvince(),
                            organisation.getCountryCode(),
                            new LinkedHashSet<>(),
                            new LinkedHashSet<>(),
                            new LinkedHashSet<>(),
                            organisation.getWebsiteUrl(),
                            organisation.getLogo()
                    );
                }).toList()
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
            val issue = Problem.builder()
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
                organisationService.getAllCostCenter(orgId).stream().map(organisationCostCenter -> {
                    return new OrganisationCostCenterView(
                            organisationCostCenter.getId() != null ? organisationCostCenter.getId().getCustomerCode() : null,
                            organisationCostCenter.getExternalCustomerCode(),
                            organisationCostCenter.getName()
                    );
                }).toList());

    }

    @Operation(description = "Organisation cost center", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationCostCenterView.class)))}
            ),
    })
    @GetMapping(value = "/organisation/{orgId}/project", produces = "application/json")
    public ResponseEntity<?> organisationProject(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                organisationService.getAllProjects(orgId).stream().map(organisationProject -> {
                    return new OrganisationCostCenterView(
                            organisationProject.getId() != null ? organisationProject.getId().getCustomerCode() : null,
                            organisationProject.getExternalCustomerCode(),
                            organisationProject.getName()
                    );
                }).toList());

    }

    @Operation(description = "Organisation Chart of account type", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationChartOfAccountTypeView.class)))}
            ),
    })
    @GetMapping(value = "/organisation/{orgId}/chart-type", produces = "application/json")
    public ResponseEntity<List<OrganisationChartOfAccountTypeView>> organisationChartOfAccountType(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                organisationService.getAllChartType(orgId).stream().map(chartOfAccountType -> {

                    return new OrganisationChartOfAccountTypeView(
                            chartOfAccountType.getId(),
                            chartOfAccountType.getOrganisationId(),
                            chartOfAccountType.getName(),
                            chartOfAccountType.getSubType().stream().map(chartOfAccountSubType -> {
                                return new OrganisationChartOfAccountSubTypeView(
                                        chartOfAccountSubType.getId(),
                                        chartOfAccountSubType.getOrganisationId(),
                                        chartOfAccountSubType.getName(),
                                        organisationService.getBySubTypeId(chartOfAccountSubType.getId()).stream().map(chartOfAccount -> {
                                            return new OrganisationChartOfAccountView(
                                                    chartOfAccount.getId().getCustomerCode(),
                                                    chartOfAccount.getRefCode(),
                                                    chartOfAccount.getEventRefCode(),
                                                    chartOfAccount.getName()
                                            );
                                        }).collect(Collectors.toSet())
                                );

                            }).collect(Collectors.toSet())
                    );
                }).toList());

    }

    @Operation(description = "Organisation Chart of acount type", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationEventView.class)))}
            ),
    })
    @GetMapping(value = "/organisation/{orgId}/events", produces = "application/json")
    public ResponseEntity<List<OrganisationEventView>> organisationEvent(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                organisationService.getOrganisationEventCode(orgId).stream().map(accountEvent -> {
                    return new OrganisationEventView(
                            accountEvent.getId().getCustomerCode(),
                            accountEvent.getId().getOrganisationId(),
                            accountEvent.getName()
                    );
                }).toList()
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
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<?> organisationCreate(@Valid @RequestBody OrganisationCreate organisationCreate) {

        Optional<Organisation> organisationChe = organisationService.findById(Organisation.id(organisationCreate.getCountryCode(), organisationCreate.getTaxIdNumber()));
        if (organisationChe.isPresent()) {
            val issue = Problem.builder()
                    .withTitle("ORGANISATION_ALREADY_EXIST")
                    .withDetail(STR."Unable to crate Organisation with IdNumber: \{organisationCreate.getTaxIdNumber()} and CountryCode: \{organisationCreate.getCountryCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        Optional<OrganisationView> organisation = organisationService.createOrganisation(organisationCreate).map(organisationService::getOrganisationView);
        if (organisation.isEmpty()) {
            val issue = Problem.builder()
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
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    @PostMapping(value = "/organisation/{orgId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> organisationUpdate(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody OrganisationUpdate organisationUpdate) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            val issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        Optional<OrganisationView> organisation = organisationService.upsertOrganisation(organisationChe.get(), organisationUpdate).map(organisationService::getOrganisationView);
        if (organisation.isEmpty()) {
            val issue = Problem.builder()
                    .withTitle("ORGANISATION_UPDATE_ERROR")
                    .withDetail(STR."Unable to create Organisation by Id: \{organisationUpdate.getName()}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        return ResponseEntity.ok().body(organisation.get());


    }

}
