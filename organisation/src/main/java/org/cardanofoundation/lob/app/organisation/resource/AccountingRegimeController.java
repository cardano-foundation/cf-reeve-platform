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

import org.cardanofoundation.lob.app.organisation.domain.request.AccountingRegimeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountingRegimeView;
import org.cardanofoundation.lob.app.organisation.service.AccountingRegimeService;

@RestController
@RequestMapping("/api/v1/organisations")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class AccountingRegimeController {

    private final AccountingRegimeService accountingRegimeService;

    @Operation(description = "Get all approved accounting regimes for an organisation", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AccountingRegimeView.class))) }
            ),
    })
    @GetMapping(value = "/{orgId}/accounting-regimes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<AccountingRegimeView>> getAllAccountingRegimes(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok(accountingRegimeService.getAllAccountingRegimes(orgId));
    }

    @Operation(description = "Accounting Regime Insert", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AccountingRegimeView.class))) }
            ),
    })
    @PostMapping(value = "/{orgId}/accounting-regimes", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<AccountingRegimeView> insertAccountingRegime(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody AccountingRegimeUpdate accountingRegimeUpdate) {
        AccountingRegimeView view = accountingRegimeService.insertAccountingRegime(orgId, accountingRegimeUpdate, false);
        return view.getError().map(error -> ResponseEntity.status(error.getStatus()).body(view))
                .orElse(ResponseEntity.ok(view));
    }

    @Operation(description = "Accounting Regime Update", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AccountingRegimeView.class))) }
            ),
    })
    @PutMapping(value = "/{orgId}/accounting-regimes", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<AccountingRegimeView> updateAccountingRegime(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody AccountingRegimeUpdate accountingRegimeUpdate) {
        AccountingRegimeView view = accountingRegimeService.updateAccountingRegime(orgId, accountingRegimeUpdate);
        return view.getError().map(error -> ResponseEntity.status(error.getStatus()).body(view))
                .orElse(ResponseEntity.ok(view));
    }

    @Operation(description = "Accounting Regime Upload", responses = {
            @ApiResponse(content =
                    { @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AccountingRegimeView.class))) }
            ),
    })
    @PostMapping(value = "/{orgId}/accounting-regimes", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<Object> insertAccountingRegimesCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @RequestParam(value = "file") MultipartFile file) {
        return accountingRegimeService.insertViaCsv(orgId, file).fold(
                problem -> ResponseEntity.status(HttpStatus.BAD_REQUEST.value()).body(problem),
                ResponseEntity::ok
        );
    }
}
