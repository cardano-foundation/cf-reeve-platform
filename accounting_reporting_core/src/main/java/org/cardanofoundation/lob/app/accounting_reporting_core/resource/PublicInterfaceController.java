package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.zalando.problem.Status.NOT_FOUND;

import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ExtractionItemService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ReportViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.PublicInterfaceTransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.PublicReportSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Slf4j
public class PublicInterfaceController {
    private final ExtractionItemService extractionItemService;
    private final ReportViewService reportViewService;
    private final ReportService reportService;
    private final OrganisationPublicApi organisationPublicApi;


    @Operation(description = "Search transactions items published",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ReportResponseView.class)))
                    })
            }
    )
    @Tag(name = "Public", description = "Public search for reporting")
    @PostMapping(value = "/reports", produces = "application/json")
    public ResponseEntity<ReportResponseView> reportSearchPublicInterface(@Valid @RequestBody PublicReportSearchRequest reportSearchRequest) {

        val orgM = organisationPublicApi.findByOrganisationId(reportSearchRequest.getOrganisationId());

        if (orgM.isEmpty()) {
            val issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{reportSearchRequest.getOrganisationId()}")
                    .withStatus(NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(ReportResponseView.createFail(issue));
        }

        return ResponseEntity.ok().body(ReportResponseView.createSuccess(reportService.findAllByTypeAndPeriod(
                        reportSearchRequest.getOrganisationId(),
                        reportSearchRequest.getReportType(),
                        reportSearchRequest.getIntervalType(),
                        reportSearchRequest.getYear(),
                        reportSearchRequest.getPeriod()
                ).stream().map(reportViewService::responseView).collect(Collectors.toList()))
        );
    }

    @Tag(name = "Public", description = "Extraction search")
    @PostMapping(value = "/transactions", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @Operation(description = "Search transactions items published - Public interface",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ExtractionTransactionView.class)))
                    })
            }
    )
    public ResponseEntity<ExtractionTransactionView> transactionSearchPublicInterface(@Valid @RequestBody PublicInterfaceTransactionsRequest transactionsRequest) {
        val orgM = organisationPublicApi.findByOrganisationId(transactionsRequest.getOrganisationId());

        if (orgM.isEmpty()) {
            val issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{transactionsRequest.getOrganisationId()}")
                    .withStatus(NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(ExtractionTransactionView.createFail(issue));
        }

        return ResponseEntity
                .ok()
                .body(ExtractionTransactionView.createSuccess(extractionItemService.findTransactionItemsPublic(
                                transactionsRequest.getOrganisationId(),
                                transactionsRequest.getDateFrom(),
                                transactionsRequest.getDateTo(),
                                transactionsRequest.getEvents(),
                                transactionsRequest.getCurrency(),
                                transactionsRequest.getMinAmount(),
                                transactionsRequest.getMaxAmount(),
                                transactionsRequest.getTransactionHashes()
                        ))
                );
    }
}
