package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;

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
import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.service.AccountEventService;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;

@RestController
@RequestMapping("/api/v1/organisations")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class AccountEventController {

    private final AccountEventService eventCodeService;
    private final OrganisationService organisationService;

    @Operation(description = "Reference Codes", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AccountEventView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/event-codes", produces = "application/json")
    public ResponseEntity<List<AccountEventView>> getReferenceCodes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(eventCodeService.getAllAccountEvent(orgId));

    }

    @Operation(description = "Reference Code insert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = AccountEventView.class))}
            ),
    })
    @PostMapping(value = "/{orgId}/event-codes", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @Valid @RequestBody EventCodeUpdate eventCodeUpdate) {

        AccountEventView eventCode = eventCodeService.insertAccountEvent(orgId, eventCodeUpdate, false);
        if (eventCode.getError().isPresent()) {
            return ResponseEntity.status(eventCode.getError().get().getStatus().getStatusCode()).body(eventCode);
        }

        return ResponseEntity.ok(eventCode);
    }

    @Operation(description = "Reference Code insert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = AccountEventView.class))}
            ),
    })
    @PostMapping(value = "/{orgId}/event-codes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertReferenceCodeByCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                      @RequestParam(value = "file")  MultipartFile file) {

        Either<List<Problem>, List<AccountEventView>> eventCodeE = eventCodeService.insertAccountEventByCsv(orgId, file);
        if (eventCodeE.isLeft()) {
            List<Problem> errors = eventCodeE.getLeft();
            return ResponseEntity.status(Status.BAD_REQUEST.getStatusCode()).body(errors);
        }
        return ResponseEntity.ok(eventCodeE.get());
    }
    @Operation(description = "Reference Code update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", schema = @Schema(implementation = AccountEventView.class))}
            ),
    })
    @PutMapping(value = "/{orgId}/event-codes", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> updateReferenceCode(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                 @Valid @RequestBody EventCodeUpdate eventCodeUpdate) {
        AccountEventView eventCode = eventCodeService.updateAccountEvent(orgId, eventCodeUpdate);
        if (eventCode.getError().isPresent()) {
            return ResponseEntity.status(eventCode.getError().get().getStatus().getStatusCode()).body(eventCode);
        }
        return ResponseEntity.ok(eventCode);
    }

}
