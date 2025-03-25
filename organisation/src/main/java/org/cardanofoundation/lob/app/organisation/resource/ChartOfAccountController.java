package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.*;
import org.cardanofoundation.lob.app.organisation.service.AccountEventService;
import org.cardanofoundation.lob.app.organisation.service.ChartOfAccountsService;
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
    private final ChartOfAccountsService chartOfAccountsService;

    @Operation(description = "Chart of Account tree", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationChartOfAccountTypeView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/chart-types", produces = "application/json")
    @Transactional
    public ResponseEntity<List<OrganisationChartOfAccountTypeView>> getChartOfAccountTypes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                chartOfAccountsService.getAllChartType(orgId).stream().map(chartOfAccountType -> {

                    return new OrganisationChartOfAccountTypeView(
                            chartOfAccountType.getId(),
                            chartOfAccountType.getOrganisationId(),
                            chartOfAccountType.getName(),
                            chartOfAccountType.getSubTypes().stream().map(chartOfAccountSubType -> {
                                return new OrganisationChartOfAccountSubTypeView(
                                        chartOfAccountSubType.getId(),
                                        chartOfAccountSubType.getOrganisationId(),
                                        chartOfAccountSubType.getName(),
                                        chartOfAccountsService.getBySubTypeId(chartOfAccountSubType.getId()).stream().map(OrganisationChartOfAccountView::createSuccess).collect(Collectors.toSet())
                                );

                            }).collect(Collectors.toSet())
                    );
                }).toList());

    }

    @Operation(description = "Chart of Account list", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationChartOfAccountView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/chart-of-accounts", produces = "application/json")
    public ResponseEntity<?> getChartOfAccounts(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(chartOfAccountsService.getAllChartOfAccount(orgId));
    }

    @Operation(description = "Reference Code upsert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationChartOfAccountView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/chart-of-accounts", produces = "application/json")
    public ResponseEntity<?> upsertChartOfAccount(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                  @Valid @RequestBody ChartOfAccountUpdate chartOfAccountUpdate) {

        OrganisationChartOfAccountView referenceCode = chartOfAccountsService.upsertChartOfAccount(orgId, chartOfAccountUpdate);
        if(referenceCode.getError().isPresent()){
            return ResponseEntity.status(referenceCode.getError().get().getStatus().getStatusCode()).body(referenceCode);
        }

        return ResponseEntity.ok(referenceCode);
    }

}
