package org.cardanofoundation.lob.app.organisation.resource;

import java.util.List;
import java.util.Optional;

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
import org.zalando.problem.Status;
import org.zalando.problem.StatusType;

import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.CurrencyView;
import org.cardanofoundation.lob.app.organisation.service.CurrencyService;

@RestController
@RequestMapping("/api/v1/organisations")
@Tag(name = "Organisation", description = "Organisation API")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.organisation.enabled", havingValue = "true", matchIfMissing = true)
public class CurrencyController {

    private final CurrencyService currencyService;

    @Operation(description = "Get all currencies", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CurrencyView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/currencies", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CurrencyView>> getAllCurrencies(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId) {
        return ResponseEntity.ok().body(currencyService.getAllCurrencies(orgId));
    }

    @Operation(description = "Get currency", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CurrencyView.class)))}
            ),
    })
    @GetMapping(value = "/{orgId}/currencies/{customerCode}", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CurrencyView> getCurrency(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @PathVariable("customerCode") @Parameter(example = "CHF") String customerCode) {
        return currencyService.getCurrency(orgId, customerCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(description = "Currency Insert", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CurrencyView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/currencies", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<CurrencyView> insertCurrency(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody CurrencyUpdate currencyUpdate) {
        CurrencyView currencyView = currencyService.insertCurrency(orgId, currencyUpdate, false);
        return currencyView.getError().map(error -> ResponseEntity.status(Optional.ofNullable(error.getStatus()).map(StatusType::getStatusCode).orElse(Status.BAD_REQUEST.getStatusCode()))
                        .body(currencyView))
                .orElse(ResponseEntity.ok(currencyView));
    }

    @Operation(description = "Currency Update", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CurrencyView.class)))}
            ),
    })
    @PutMapping(value = "/{orgId}/currencies", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<CurrencyView> updateCurrency(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @Valid @RequestBody CurrencyUpdate currencyUpdate) {
        CurrencyView currencyView = currencyService.updateCurrency(orgId, currencyUpdate);
        return currencyView.getError().map(error -> ResponseEntity.status(Optional.ofNullable(error.getStatus()).map(StatusType::getStatusCode).orElse(Status.BAD_REQUEST.getStatusCode()))
                        .body(currencyView))
                .orElse(ResponseEntity.ok(currencyView));
    }

    @Operation(description = "Currency Upload", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CurrencyView.class)))}
            ),
    })
    @PostMapping(value = "/{orgId}/currencies", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> insertCurrenciesCsv(@PathVariable("orgId") @Parameter(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String orgId, @RequestParam(value = "file") MultipartFile file) {
        return currencyService.insertViaCsv(orgId, file).fold(
                problem -> ResponseEntity.status(Status.BAD_REQUEST.getStatusCode()).body(problem),
                ResponseEntity::ok
        );
    }
}
