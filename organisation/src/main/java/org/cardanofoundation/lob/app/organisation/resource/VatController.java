package org.cardanofoundation.lob.app.organisation.resource;


import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.zalando.problem.Status;
import org.zalando.problem.StatusType;

import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.VatView;
import org.cardanofoundation.lob.app.organisation.service.VatService;

@RestController
@RequestMapping("/api/v1/organisations")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class VatController {

    private final VatService vatService;

    @Operation(description = "Vat Codes", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = VatView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/vat-codes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<VatView>> getVatCodes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(vatService.findAllByOrganisationId(orgId));

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
        return vatView.getError().map(error -> ResponseEntity.status(Optional.ofNullable(error.getStatus()).map(StatusType::getStatusCode).orElse(Status.BAD_REQUEST.getStatusCode()))
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
        return vatView.getError().map(error -> ResponseEntity.status(Optional.ofNullable(error.getStatus()).map(StatusType::getStatusCode).orElse(Status.BAD_REQUEST.getStatusCode()))
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
