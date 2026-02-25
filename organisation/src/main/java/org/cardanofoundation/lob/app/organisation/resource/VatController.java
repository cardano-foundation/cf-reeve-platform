package org.cardanofoundation.lob.app.organisation.resource;


import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.VatView;
import org.cardanofoundation.lob.app.organisation.service.VatService;

@RestController
@RequestMapping("/api/v1/organisations")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class VatController {

    private final VatService vatService;

    @Operation(description = "Vat Codes", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = VatView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/vat-codes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getVatCodes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                     @RequestParam(value = "customerCode", required = false) String customerCode,
                                                     @RequestParam(value = "minRate", required = false) Double minRate,
                                                     @RequestParam(value = "maxRate", required = false) Double maxRate,
                                                     @RequestParam(value = "description", required = false) String description,
                                                     @RequestParam(value = "countryCodes", required = false) List<String> countryCodes,
                                                     @RequestParam(value = "active", required = false) Boolean active,
                                                     @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return vatService.findAllByOrganisationId(orgId, customerCode, minRate, maxRate, description, countryCodes, active, pageable).fold(
                problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                ResponseEntity::ok);

    }

    @Operation(summary = "Download vat CSV file", description = "Download vat codes as a CSV file")
    @GetMapping(value = "/{orgId}/vat-codes/download", produces = "test/csv")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<StreamingResponseBody> downloadVatCodesCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                                     @RequestParam(value = "customerCode", required = false) String customerCode,
                                                                     @RequestParam(value = "minRate", required = false) Double minRate,
                                                                     @RequestParam(value = "maxRate", required = false) Double maxRate,
                                                                     @RequestParam(value = "description", required = false) String description,
                                                                     @RequestParam(value = "countryCodes", required = false) List<String> countryCodes,
                                                                     @RequestParam(value = "active", required = false) Boolean active) {
        StreamingResponseBody responseBody = outputStream -> vatService.downloadCsv(orgId, customerCode, minRate, maxRate, description, countryCodes, active, outputStream);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"vat-codes_%s.csv\"".formatted(orgId))
                .contentType(MediaType.TEXT_PLAIN)
                .body(responseBody);
    }

    @Operation(description = "Vat code insert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VatView.class))}
            ),
    })
    @PostMapping(value = "/{orgId}/vat-codes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertVatCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                           @Valid @RequestBody VatUpdate vatUpdate) {

        VatView vatView = vatService.insert(orgId, vatUpdate, false);
        return vatView.getError().map(error -> ResponseEntity.status(error.getStatus())
                        .body(vatView))
                .orElse(ResponseEntity.ok(vatView));
    }



    @Operation(description = "Reference Code update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VatUpdate.class))}
            ),
    })
    @PutMapping(value = "/{orgId}/vat-codes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> updateReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @Valid @RequestBody VatUpdate vatUpdate) {

        VatView vatView = vatService.update(orgId, vatUpdate);
        return vatView.getError().map(error -> ResponseEntity.status(error.getStatus())
                        .body(vatView))
                .orElse(ResponseEntity.ok(vatView));
    }

    @Operation(description = "Vat codes insert via csv", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = VatView.class))}
            ),
    })
    @PostMapping(value = "/{orgId}/vat-codes", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertVatCodesCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                               @RequestParam(value = "file") MultipartFile file) {

        return vatService.insertVatCodesCsv(orgId, file).fold(
                problem -> ResponseEntity.status(BAD_REQUEST).body(problem),
                ResponseEntity::ok
        );
    }


}
