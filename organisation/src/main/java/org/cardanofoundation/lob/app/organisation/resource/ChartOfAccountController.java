package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.*;
import org.cardanofoundation.lob.app.organisation.service.ChartOfAccountsService;

@RestController
@RequestMapping("/api/organisations")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class ChartOfAccountController {

    private final ChartOfAccountsService chartOfAccountsService;

    @Operation(description = "Chart of Account tree", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ChartOfAccountTypeView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/chart-types", produces = "application/json")
    @Transactional
    public ResponseEntity<List<ChartOfAccountTypeView>> getChartOfAccountTypes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(
                chartOfAccountsService.getAllChartType(orgId).stream().map(chartOfAccountType -> {

                    return new ChartOfAccountTypeView(
                            chartOfAccountType.getId(),
                            chartOfAccountType.getOrganisationId(),
                            chartOfAccountType.getName(),
                            chartOfAccountType.getSubTypes().stream().map(chartOfAccountSubType -> {
                                return new ChartOfAccountSubTypeView(
                                        chartOfAccountSubType.getId(),
                                        chartOfAccountSubType.getOrganisationId(),
                                        chartOfAccountSubType.getName(),
                                        chartOfAccountsService.getBySubTypeId(chartOfAccountSubType.getId()).stream().map(ChartOfAccountView::createSuccess).collect(Collectors.toSet())
                                );

                            }).collect(Collectors.toSet())
                    );
                }).toList());

    }

    @Operation(description = "Chart of Account list", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ChartOfAccountView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/chart-of-accounts", produces = "application/json")
    public ResponseEntity<Set<ChartOfAccountView>> getChartOfAccounts(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(chartOfAccountsService.getAllChartOfAccount(orgId));
    }

    @Operation(description = "Chart Of Account insert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ChartOfAccountView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/chart-of-accounts", produces = MediaType.MULTIPART_FORM_DATA_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertChartOfAccount(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                  @Valid @RequestBody ChartOfAccountUpdate chartOfAccountUpdate) {

        ChartOfAccountView referenceCode = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate);
        if(referenceCode.getError().isPresent()){
            return ResponseEntity.status(referenceCode.getError().get().getStatus().getStatusCode()).body(referenceCode);
        }

        return ResponseEntity.ok(referenceCode);
    }

    @Operation(description = "Chart Of Account insert by csv", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ChartOfAccountView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/chart-of-accounts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertChartOfAccountByCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                        @RequestParam(value = "file") MultipartFile file) {

        Either<Set<Problem>, Set<ChartOfAccountView>> chartOfAccountE = chartOfAccountsService.insertChartOfAccountByCsv(orgId, file);
        if (chartOfAccountE.isEmpty()) {
            return ResponseEntity.status(500).body(chartOfAccountE.getLeft());
        }
        return ResponseEntity.ok(chartOfAccountE.get());
    }


    @Operation(description = "Reference Code update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ChartOfAccountView.class)))}
            ),
    })
    @PutMapping(value = "/{orgId}/chart-of-accounts", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> updateChartOfAccount(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                  @Valid @RequestBody ChartOfAccountUpdate chartOfAccountUpdate) {

        ChartOfAccountView referenceCode = chartOfAccountsService.updateChartOfAccount(orgId, chartOfAccountUpdate);
        if(referenceCode.getError().isPresent()){
            return ResponseEntity.status(referenceCode.getError().get().getStatus().getStatusCode()).body(referenceCode);
        }

        return ResponseEntity.ok(referenceCode);
    }
}
