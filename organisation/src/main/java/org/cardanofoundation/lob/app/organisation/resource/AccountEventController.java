package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.service.AccountEventService;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;

@RestController
@RequestMapping("/api/v1/organisations")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class AccountEventController {

    private final AccountEventService eventCodeService;
    private final OrganisationService organisationService;

    @Operation(description = "Reference Codes", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AccountEventView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/event-codes", produces = "application/json")
    public ResponseEntity<?> getReferenceCodes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                               @RequestParam(value = "customerCode", required = false) String customerCode,
                                               @RequestParam(value = "name", required = false) String name,
                                               @RequestParam(value = "creditRefCodes", required = false) List<String> creditRefCodes,
                                               @RequestParam(value = "debitRefCodes", required = false) List<String> debitRefCodes,
                                               @RequestParam(value = "active", required = false) Boolean active,
                                               @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable
    ) {
        return eventCodeService.getAllAccountEvent(orgId, customerCode, name, creditRefCodes, debitRefCodes, active, pageable).fold(
                problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                ResponseEntity::ok);

    }

    @Operation(summary = "Download event codes CSV file", description = "Download event codes as a CSV file")
    @GetMapping(value = "/{orgId}/event-codes/download", produces = "test/csv")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) or hasRole(@securityConfig.getAccountantRole())")
    public ResponseEntity<StreamingResponseBody> downloadEventCodesCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId,
                                                                       @RequestParam(value = "customerCode", required = false) String customerCode,
                                                                       @RequestParam(value = "name", required = false) String name,
                                                                       @RequestParam(value = "creditRefCodes", required = false) List<String> creditRefCodes,
                                                                       @RequestParam(value = "debitRefCodes", required = false) List<String> debitRefCodes,
                                                                       @RequestParam(value = "active", required = false) Boolean active) {
        StreamingResponseBody responseBody = outputStream -> eventCodeService.downloadCsv(orgId, customerCode, name, creditRefCodes, debitRefCodes, active, outputStream);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"event-codes_%s.csv\"".formatted(orgId))
                .contentType(MediaType.TEXT_PLAIN)
                .body(responseBody);
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
            return ResponseEntity.status(eventCode.getError().get().getStatus()).body(eventCode);
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
                                                      @RequestParam(value = "file") MultipartFile file) {

        Either<List<ProblemDetail>, List<AccountEventView>> eventCodeE = eventCodeService.insertAccountEventByCsv(orgId, file);
        if (eventCodeE.isLeft()) {
            List<ProblemDetail> errors = eventCodeE.getLeft();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST.value()).body(errors);
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
            return ResponseEntity.status(eventCode.getError().get().getStatus()).body(eventCode);
        }
        return ResponseEntity.ok(eventCode);
    }

}
