package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

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
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.*;
import org.cardanofoundation.lob.app.organisation.service.AccountEventService;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;

@RestController
@RequestMapping("/api/organisation")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class ChartOfAccountController {

    private final AccountEventService eventCodeService;
    private final OrganisationService organisationService;
    @Operation(description = "Chart of Account tree", responses = {
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
                                                    chartOfAccount.getEventRefCode(),
                                                    chartOfAccount.getEventRefCode(),
                                                    chartOfAccount.getName()
                                            );
                                        }).collect(Collectors.toSet())
                                );

                            }).collect(Collectors.toSet())
                    );
                }).toList());

    }

    @Operation(description = "Reference Code update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = AccountEventView.class))}
            ),
    })
    @PostMapping(value = "/{orgId}/event-codes", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<?> upsertReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @Valid @RequestBody EventCodeUpdate eventCodeUpdate) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }
        Optional<AccountEventView> eventCode = eventCodeService.upsertAccountEvent(orgId, eventCodeUpdate);

        if (eventCode.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find refernce code by Id: \{orgId} and \{eventCodeUpdate.getDebitReferenceCode()}:\{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        return ResponseEntity.ok(eventCode);
    }

    @Operation(description = "Reference Code delete", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationView.class)))}
            ),
    })
    @DeleteMapping(value = "/{orgId}/{refCode}", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<?> deleteReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @PathVariable("refCode") String referenceCode) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }
        return null;
    }


}
