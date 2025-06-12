package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;
import static org.zalando.problem.Status.BAD_REQUEST;
import static org.zalando.problem.Status.NOT_FOUND;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.ExtractionItemService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionTransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.response.ExtractionValidationResponse;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionItemView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.date.FlexibleDateParser;

@RestController
@RequestMapping("/api/extraction")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
@PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
public class ExtractionController {

    private final ExtractionItemService extractionItemService;
    private final OrganisationPublicApi organisationPublicApi;
    private final AccountingCorePresentationViewService accountingCorePresentationService;
    private final ObjectMapper objectMapper;

    @Tag(name = "Extraction", description = "Extraction search")
    @PostMapping(value = "/search", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @Operation(description = "Search for published transaction items",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ExtractionTransactionItemView.class)))
                    })
            }
    )
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

    @Tag(name = "Transactions", description = "Transactions API")
    @PostMapping(value = "/", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(description = "Trigger the extraction from the ERP system(s)", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(example = "{\"event\": \"EXTRACTION\",\"message\":\"We have received your extraction request now. Please review imported transactions from the batch list.\"}"))},
                    responseCode = "202"
            )
    })
    public ResponseEntity<?> extractionTrigger(@Valid @RequestBody ExtractionRequest body) {
        return extractTriggerProcessing(body);
    }

    private ResponseEntity<?> extractTriggerProcessing(ExtractionRequest body) {
        Optional<Organisation> orgM = organisationPublicApi.findByOrganisationId(body.getOrganisationId());

        if (orgM.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail("Unable to find Organisation by Id: %s".formatted(body.getOrganisationId()))
                    .withStatus(NOT_FOUND)
                    .build();

            return ResponseEntity.status(Objects.requireNonNull(issue.getStatus()).getStatusCode()).body(issue);
        }

        Organisation org = orgM.orElseThrow();

        Either<Problem, Void> extractionResultE = accountingCorePresentationService.extractionTrigger(body);
        if (extractionResultE.isLeft()) {
            Problem problem = extractionResultE.getLeft();
            log.error("Extraction trigger failed with problem: {}", problem);
            return ResponseEntity
                    .status(Objects.requireNonNull(problem.getStatus()).getStatusCode())
                    .body(problem);
        } else {
            log.info("Extraction triggered successfully for organisation: {}", org.getId());

            ObjectNode response = objectMapper.createObjectNode();

            response.put("event", "EXTRACTION");
            response.put("message", "We have received your extraction request now. Please review imported transactions from the batch list.");

            return ResponseEntity
                    .status(HttpStatusCode.valueOf(202))
                    .body(response);
        }
    }

    @Tag(name = "Transactions", description = "Transactions API")
    @PostMapping(value = "/", consumes = MULTIPART_FORM_DATA_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(description = "Trigger the extraction from the ERP system(s)", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(example = "{\"event\": \"EXTRACTION\",\"message\":\"We have received your extraction request now. Please review imported transactions from the batch list.\"}"))},
                    responseCode = "202"
            )
    })
    public ResponseEntity<?> extractionTriggerForm(@ModelAttribute ExtractionRequest body) {
        return extractTriggerProcessing(body);
    }

    @Tag(name = "Transactions", description = "Transactions API")
    @PostMapping(value = "/validation", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(description = "Validate the extraction request", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ExtractionValidationResponse.class))},
                    responseCode = "202"
            )
    })
    public ResponseEntity<ExtractionValidationResponse> extractionValidation(@Valid @RequestBody ExtractionRequest body) {
        return extractionValidationProcessing(body);
    }

    private ResponseEntity<ExtractionValidationResponse> extractionValidationProcessing(ExtractionRequest body) {
        Either<List<Problem>, Void> extractionValidation = accountingCorePresentationService.extractionValidation(body);
        if (extractionValidation.isLeft()) {
            List<Problem> problems = extractionValidation.getLeft();
            log.error("Extraction validation failed with problems: {}", problems);
            return ResponseEntity
                    .status(BAD_REQUEST.getStatusCode())
                    .body(new ExtractionValidationResponse(false, problems));
        } else {
            return ResponseEntity.ok(
                    new ExtractionValidationResponse(true, List.of())
            );
        }
    }

    @Tag(name = "Transactions", description = "Transactions API")
    @PostMapping(value = "/validation", consumes = MULTIPART_FORM_DATA_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(description = "Validate the extraction request", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ExtractionValidationResponse.class))},
                    responseCode = "202"
            )
    })
    public ResponseEntity<ExtractionValidationResponse> extractionValidationForm(@ModelAttribute ExtractionRequest body) {
        return extractionValidationProcessing(body);
    }


}
