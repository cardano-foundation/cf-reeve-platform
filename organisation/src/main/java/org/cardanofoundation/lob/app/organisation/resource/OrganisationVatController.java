package org.cardanofoundation.lob.app.organisation.resource;


import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationVatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationVatView;
import org.cardanofoundation.lob.app.organisation.service.OrganisationVatService;

@RestController
@RequestMapping("/api/organisation")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class OrganisationVatController {

    private final OrganisationVatService organisationVatService;

    @Operation(description = "Vat Codes", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganisationVatView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/vat-codes", produces = "application/json")
    public ResponseEntity<List<OrganisationVatView>> getVatCodes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(organisationVatService.findAllByOrganisationId(orgId));

    }

    @Operation(description = "Vat code insert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = OrganisationVatView.class))}
            ),
    })
    @PostMapping(value = "/{orgId}/vat-codes/insert", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertVatCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                           @Valid @RequestBody OrganisationVatUpdate organisationVatUpdate) {

        OrganisationVatView eventCode = organisationVatService.insert(orgId, organisationVatUpdate);
        if (eventCode.getError().isPresent()) {
            return ResponseEntity.status(eventCode.getError().get().getStatus().getStatusCode()).body(eventCode);
        }
        return ResponseEntity.ok(eventCode);
    }



    @Operation(description = "Reference Code update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = OrganisationVatUpdate.class))}
            ),
    })
    @PostMapping(value = "/{orgId}/vat-codes/update", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> updateReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @Valid @RequestBody OrganisationVatUpdate organisationVatUpdate) {

        OrganisationVatView eventCode = organisationVatService.update(orgId, organisationVatUpdate);
        if (eventCode.getError().isPresent()) {
            return ResponseEntity.status(eventCode.getError().get().getStatus().getStatusCode()).body(eventCode);
        }
        return ResponseEntity.ok(eventCode);
    }

    @Operation(description = "Vat codes insert via csv", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = OrganisationVatView.class))}
            ),
    })
    @PostMapping(value = "/{orgId}/vat-codes/insert-csv", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertVatCodesCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                               @RequestParam(value = "file") MultipartFile file) {

        return organisationVatService.insertVatCodesCsv(orgId, file).fold(
                problem -> ResponseEntity.status(BAD_REQUEST).body(problem),
                ResponseEntity::ok
        );
    }


}
