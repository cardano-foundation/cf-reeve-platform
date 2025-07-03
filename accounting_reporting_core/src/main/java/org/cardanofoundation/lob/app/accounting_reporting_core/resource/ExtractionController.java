package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.time.LocalDate;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ExtractionItemService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionTransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionItemView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;
import org.cardanofoundation.lob.app.support.date.FlexibleDateParser;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@Slf4j
public class ExtractionController {
    private final ExtractionItemService extractionItemService;

    @Tag(name = "Extraction", description = "Extraction search")
    @PostMapping(value = "/extraction/search", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @Operation(description = "Search for published transaction items",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ExtractionTransactionItemView.class)))
                    })
            }
    )
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ExtractionTransactionView> transactionSearch(@Valid @RequestBody ExtractionTransactionsRequest transactionsRequest) {
        try {
            LocalDate dateFrom = FlexibleDateParser.parse(transactionsRequest.getDateFrom());
            LocalDate dateTo = FlexibleDateParser.parse(transactionsRequest.getDateTo());
            return ResponseEntity
                    .ok()
                    .body(extractionItemService.findTransactionItems(dateFrom, dateTo, transactionsRequest.getAccountCode(), transactionsRequest.getCostCenter(), transactionsRequest.getProject(), transactionsRequest.getAccountType(), transactionsRequest.getAccountSubType()));
        } catch (Exception e) {
            log.error("Error occurred while searching transactions");
            return ResponseEntity.status(500).body(null);
        }
    }

}
