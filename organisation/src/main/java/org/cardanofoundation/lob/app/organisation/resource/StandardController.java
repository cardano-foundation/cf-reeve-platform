package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.organisation.domain.request.StandardUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.StandardView;
import org.cardanofoundation.lob.app.organisation.service.StandardService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;
import org.cardanofoundation.lob.app.support.security.OrgAccessDenied;

@RestController
@RequestMapping("/api/v1/organisations")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class StandardController {

    private final StandardService standardService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Operation(description = "Get all approved standards for an organisation", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = StandardView.class))) }
            ),
    })
    @GetMapping(value = "/{orgId}/standards", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permissions.can('standards', 'view')")
    public ResponseEntity<List<StandardView>> getAllStandards(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return OrgAccessDenied.response();
        }
        return ResponseEntity.ok(standardService.getAllStandards(orgId));
    }

    @Operation(description = "Standard Insert", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = StandardView.class))) }
            ),
    })
    @PostMapping(value = "/{orgId}/standards", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permissions.can('standards', 'create')")
    public ResponseEntity<StandardView> insertStandard(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody StandardUpdate standardUpdate) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return OrgAccessDenied.response();
        }
        StandardView view = standardService.insertStandard(orgId, standardUpdate, false);
        return view.getError().map(error -> ResponseEntity.status(error.getStatus()).body(view))
                .orElse(ResponseEntity.ok(view));
    }

    @Operation(description = "Standard Update", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = StandardView.class))) }
            ),
    })
    @PutMapping(value = "/{orgId}/standards", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permissions.can('standards', 'edit')")
    public ResponseEntity<StandardView> updateStandard(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody StandardUpdate standardUpdate) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return OrgAccessDenied.response();
        }
        StandardView view = standardService.updateStandard(orgId, standardUpdate);
        return view.getError().map(error -> ResponseEntity.status(error.getStatus()).body(view))
                .orElse(ResponseEntity.ok(view));
    }

    @Operation(description = "Standard Upload", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = StandardView.class))) }
            ),
    })
    @PostMapping(value = "/{orgId}/standards", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@permissions.can('standards', 'create')")
    public ResponseEntity<Object> insertStandardsCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @RequestParam(value = "file") MultipartFile file) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return OrgAccessDenied.response();
        }
        return standardService.insertViaCsv(orgId, file).fold(
                problem -> ResponseEntity.status(HttpStatus.BAD_REQUEST.value()).body(problem),
                ResponseEntity::ok
        );
    }
}
