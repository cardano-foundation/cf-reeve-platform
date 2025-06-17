package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.request.ReferenceCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReferenceCodeView;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;
import org.cardanofoundation.lob.app.organisation.service.ReferenceCodeService;

@RestController
@RequestMapping("/api/organisation")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class ReferenceCodeResource {

    private final ReferenceCodeService referenceCodeService;
    private final OrganisationService organisationService;

    @Operation(description = "Reference Codes list", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReferenceCodeView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/reference-codes", produces = "application/json")
    public List<ReferenceCodeView> getReferenceCodes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return referenceCodeService.getAllReferenceCodes(orgId);
    }

    @Operation(description = "Reference Code insert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReferenceCodeView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/reference-codes/insert", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @Valid @RequestBody ReferenceCodeUpdate referenceCodeUpdate) {
        ReferenceCodeView referenceCode = referenceCodeService.insertReferenceCode(orgId, referenceCodeUpdate);
        if (referenceCode.getError().isPresent()) {
            return ResponseEntity.status(referenceCode.getError().get().getStatus().getStatusCode()).body(referenceCode);
        }
        return ResponseEntity.ok(referenceCode);
    }

    @Operation(description = "Reference Code insert by CSV", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReferenceCodeView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/reference-codes/insert-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertRefCodeByCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                       @RequestParam(value = "file") MultipartFile file) {
        Either<Set<Problem>, Set<ReferenceCodeView>> refCodeE = referenceCodeService.insertReferenceCodeByCsv(orgId, file);
        if (refCodeE.isLeft()) {
            return ResponseEntity.status(500).body(refCodeE.getLeft());
        }
        Set<ReferenceCodeView> referenceCodeViews = refCodeE.get();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(referenceCodeViews);
    }

    @Operation(description = "Reference Code update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReferenceCodeView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/reference-codes/update", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> updateReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @Valid @RequestBody ReferenceCodeUpdate referenceCodeUpdate) {
        ReferenceCodeView referenceCode = referenceCodeService.updateReferenceCode(orgId, referenceCodeUpdate);
        if (referenceCode.getError().isPresent()) {
            return ResponseEntity.status(referenceCode.getError().get().getStatus().getStatusCode()).body(referenceCode);
        }
        return ResponseEntity.ok(referenceCode);
    }


    @Deprecated
    @Operation(description = "Reference Code upsert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReferenceCodeView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/reference-codes", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> upsertReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @Valid @RequestBody ReferenceCodeUpdate referenceCodeUpdate) {
        ReferenceCodeView referenceCode = referenceCodeService.upsertReferenceCode(orgId, referenceCodeUpdate);
        if (referenceCode.getError().isPresent()) {
            return ResponseEntity.status(referenceCode.getError().get().getStatus().getStatusCode()).body(referenceCode);
        }
        return ResponseEntity.ok(referenceCode);
    }

    @Operation(description = "Reference Code delete")
    // Removing the mapping to keep the code but disable the endpoint
//    @DeleteMapping(value = "/{orgId}/reference-codes/{refCode}", produces = "application/json") // Removing the mapping to keep the code but disable the endpoint
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
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
