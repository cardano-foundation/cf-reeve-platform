package org.cardanofoundation.lob.app.organisation.resource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.organisation.domain.csv.CostCenterUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.CostCenterView;
import org.cardanofoundation.lob.app.organisation.service.CostCenterService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class CostCenterController {

    private final CostCenterService costCenterService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Operation(description = "Organisation cost center", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CostCenterView.class)))}
            ),
    })
    @GetMapping(value = "/organisations/{orgId}/cost-centers", produces = "application/json")
    public ResponseEntity<?> getAllCostCenters(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                               @RequestParam(value = "customerCode", required = false) String customerCode,
                                               @RequestParam(value = "name", required = false) String name,
                                               @RequestParam(value = "parentCustomerCodes", required = false) List<String> parentCustomerCodes,
                                               @RequestParam(value = "active", required = false) Boolean active,
                                               @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return ResponseEntity.status(403).body("User is not allowed to access this organisation");
        }

        return costCenterService.getAllCostCenter(orgId, customerCode, name, parentCustomerCodes, active, pageable).fold(
                problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                ResponseEntity::ok);
    }

    @Operation(summary = "Download cost centers CSV file", description = "Download cost centers as a CSV file")
    @GetMapping(value = "/organisations/{orgId}/cost-centers/download", produces = "test/csv")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<StreamingResponseBody> downloadCostCentersCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                    @RequestParam(value = "customerCode", required = false) String customerCode,
                                                    @RequestParam(value = "name", required = false) String name,
                                                    @RequestParam(value = "parentCustomerCodes", required = false) List<String> parentCustomerCodes,
                                                    @RequestParam(value = "active", required = false) Boolean active) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            StreamingResponseBody errorBody = outputStream -> outputStream.write("User is not allowed to access this organisation".getBytes());
            return ResponseEntity.status(403).body(errorBody);
        }
        StreamingResponseBody responseBody = outputStream -> costCenterService.downloadCsv(orgId, customerCode, name, parentCustomerCodes, active, outputStream);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"cost-centers_%s.csv\"".formatted(orgId))
                .contentType(MediaType.TEXT_PLAIN)
                .body(responseBody);
    }


    @Operation(description = "Organisation cost center creation", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CostCenterView.class)))}
            ),
    })
    @PostMapping(value = "/organisations/{orgId}/cost-centers", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<CostCenterView> insertCostCenters(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody CostCenterUpdate costCenterUpdate) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(FORBIDDEN, "User is not allowed to access this organisation");
            return ResponseEntity.status(403).body(CostCenterView.createFail(costCenterUpdate, problem));
        }
        CostCenterView costCenterView = costCenterService.insertCostCenter(orgId, costCenterUpdate, false);
        return costCenterView.getError().map(error -> ResponseEntity.status(error.getStatus())
                        .body(costCenterView))
                .orElse(ResponseEntity.ok(costCenterView));
    }

    @Operation(description = "Organisation cost center update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CostCenterView.class)))}
            ),
    })
    @PutMapping(value = "/organisations/{orgId}/cost-centers", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<CostCenterView> updateCostCenters(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody CostCenterUpdate costCenterUpdate) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(FORBIDDEN, "User is not allowed to access this organisation");
            return ResponseEntity.status(403).body(CostCenterView.createFail(costCenterUpdate, problem));
        }
        CostCenterView costCenterView = costCenterService.updateCostCenter(orgId, costCenterUpdate);

        return costCenterView.getError().map(error -> ResponseEntity.status(error.getStatus())
                        .body(costCenterView))
                .orElse(ResponseEntity.ok(costCenterView));
    }

    @Operation(description = "Organisation cost center creation csv", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CostCenterView.class)))}
            ),
    })
    @PostMapping(value = "/organisations/{orgId}/cost-centers", produces = APPLICATION_JSON_VALUE, consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<?> insertCostCentersCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @RequestParam("file") MultipartFile file) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return ResponseEntity.status(403).body("User is not allowed to access this organisation");
        }
        return costCenterService.createCostCenterFromCsv(orgId, file).fold(
                problem -> ResponseEntity.status(BAD_REQUEST).body(problem),
                ResponseEntity::ok
        );
    }
}
