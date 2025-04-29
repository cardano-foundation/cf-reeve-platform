package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.zalando.problem.Status.NOT_FOUND;
import static org.zalando.problem.Status.OK;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.*;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
// TODO Should this endpoint be only accessible for registered users? If so, we should add the @PreAuthorize("authenticated()") annotation
public class AccountingCoreResource {

    private final AccountingCorePresentationViewService accountingCorePresentationService;
    private final OrganisationPublicApi organisationPublicApi;
    private final ObjectMapper objectMapper;

    @Tag(name = "Transactions", description = "Transactions API")
    @Operation(description = "Transaction list", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = TransactionView.class)))}
            )
    })
    @PostMapping(value = "/transactions", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<TransactionView>> listAllAction(@Valid @RequestBody SearchRequest body) {
        List<TransactionView> transactions = accountingCorePresentationService.allTransactions(body);
        return ResponseEntity.ok().body(transactions);
    }

    @Tag(name = "Transactions", description = "Transactions API")
    @Operation(description = "Transaction detail", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = TransactionView.class))}
            )
    })
    @GetMapping(value = "/transactions/{id}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> transactionDetailSpecific(@Valid @PathVariable("id") @Parameter(example = "7e9e8bcbb38a283b41eab57add98278561ab51d23a16f3e3baf3daa461b84ab4") String id) {
        Optional<TransactionView> transactionEntity = accountingCorePresentationService.transactionDetailSpecific(id);
        if (transactionEntity.isEmpty()) {
            val issue = Problem.builder()
                    .withTitle("TX_NOT_FOUND")
                    .withDetail(STR."Transaction with id: {\{id}} could not be found")
                    .withStatus(NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        return ResponseEntity.ok().body(transactionEntity);
    }

    @Tag(name = "Transactions", description = "Transactions API")
    @Operation(description = "Transaction types", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(example = "[{\"id\":\"CardCharge\",\"title\":\"Card Charge\"},{\"id\":\"VendorBill\",\"title\":\"Vendor Bill\"},{\"id\":\"CardRefund\",\"title\":\"Card Refund\"},{\"id\":\"Journal\",\"title\":\"Journal\"},{\"id\":\"FxRevaluation\",\"title\":\"Fx Revaluation\"},{\"id\":\"Transfer\",\"title\":\"Transfer\"},{\"id\":\"CustomerPayment\",\"title\":\"Customer Payment\"},{\"id\":\"ExpenseReport\",\"title\":\"Expense Report\"},{\"id\":\"VendorPayment\",\"title\":\"Vendor Payment\"},{\"id\":\"BillCredit\",\"title\":\"Bill Credit\"}]"))}
            )
    })
    @GetMapping(value = "/transaction-types", produces = APPLICATION_JSON_VALUE, name = "Transaction types")
    public ResponseEntity<String> transactionType() throws JsonProcessingException {
        val jsonArray = objectMapper.createArrayNode();

        for (val transactionType : TransactionType.values()) {
            val jsonObject = objectMapper.createObjectNode();
            jsonObject.put("id", transactionType.name());
            jsonObject.put("title", transactionType.name().replaceAll("(\\p{Lower})(\\p{Upper})", "$1 $2"));

            jsonArray.add(jsonObject);
        }

        return ResponseEntity.ok().body(objectMapper.writeValueAsString(jsonArray));
    }

    @Tag(name = "Transactions", description = "Transactions API")
    @Operation(description = "Rejection reasons", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = RejectionReason.class)))}
            )
    })
    @GetMapping(value = "/rejection-reasons", produces = APPLICATION_JSON_VALUE, name = "Rejection reasons")
    public ResponseEntity<RejectionReason[]> rejectionReasons() {
        return ResponseEntity.ok().body(RejectionReason.values());
    }

    @Tag(name = "Transactions", description = "Transactions API")
    @PostMapping(value = "/extraction", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(description = "Trigger the extraction from the ERP system(s)", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE,
                            schema = @Schema(example = "{\"event\": \"EXTRACTION\",\"message\":\"We have received your extraction request now. Please review imported transactions from the batch list.\"}"))},
                    responseCode = "202"
            )
    })
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> extractionTrigger(@Valid @RequestBody ExtractionRequest body) {
        val orgM = organisationPublicApi.findByOrganisationId(body.getOrganisationId());

        if (orgM.isEmpty()) {
            val issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{body.getOrganisationId()}")
                    .withStatus(NOT_FOUND)
                    .build();

            return ResponseEntity.status(issue.getStatus().getStatusCode()).body(issue);
        }

        val org = orgM.orElseThrow();

        val extractionResultE = accountingCorePresentationService.extractionTrigger(body);

        return extractionResultE.fold(
                problem -> {
                    return ResponseEntity
                            .status(problem.getStatus().getStatusCode())
                            .body(problem);
                },
                success -> {
                    log.info("Extraction triggered successfully for organisation: {}", org.getId());

                    val response = objectMapper.createObjectNode();

                    response.put("event", "EXTRACTION");
                    response.put("message", "We have received your extraction request now. Please review imported transactions from the batch list.");

                    return ResponseEntity
                            .status(HttpStatusCode.valueOf(202))
                            .body(response);
                }
        );
    }

    @Tag(name = "Transactions", description = "Transactions Approval API")
    @PostMapping(value = "/transactions/approve", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @Operation(description = "Approve one or more transactions",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = TransactionProcessView.class)))
                    })
            }
    )
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<List<TransactionProcessView>> approveTransactions(@Valid @RequestBody TransactionsRequest transactionsRequest) {
        val transactionProcessViews = accountingCorePresentationService.approveTransactions(transactionsRequest);

        return ResponseEntity
                .status(HttpStatusCode.valueOf(OK.getStatusCode()))
                .body(transactionProcessViews);
    }

    @Tag(name = "Transactions", description = "Transactions Publish / Dispatch Approval API")
    @PostMapping(value = "/transactions/publish", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @Operation(description = "Approve to publish one or more transactions",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = TransactionProcessView.class)))
                    })
            }
    )
    @PreAuthorize("hasRole(@securityConfig.getManagerRole())")
    public ResponseEntity<List<TransactionProcessView>> approveTransactionsPublish(@Valid @RequestBody TransactionsRequest transactionsRequest) {
        val transactionProcessViewList = accountingCorePresentationService.approveTransactionsPublish(transactionsRequest);

        return ResponseEntity
                .status(HttpStatusCode.valueOf(OK.getStatusCode()))
                .body(transactionProcessViewList);
    }

    @Tag(name = "Transactions", description = "Transaction Items Rejection API")
    @PostMapping(value = "/transaction/reject", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @Operation(description = "Reject one or more transaction items per a given transaction",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = TransactionItemsProcessRejectView.class))
                    })
            }
    )
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<TransactionItemsProcessRejectView> rejectTransactionItems(@Valid @RequestBody TransactionItemsRejectionRequest transactionItemsRejectionRequest) {
        TransactionItemsProcessRejectView transactionProcessViewsResult = accountingCorePresentationService.rejectTransactionItems(transactionItemsRejectionRequest);

        return ResponseEntity
                .status(HttpStatusCode.valueOf(OK.getStatusCode()))
                .body(transactionProcessViewsResult);
    }

    @Tag(name = "Batches", description = "Batches API")
    @PostMapping(value = "/batches", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @Operation(description = "Batch list",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = BatchsDetailView.class)))
                    })
            }
    )
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<BatchsDetailView> listAllBatches(@Valid @RequestBody BatchSearchRequest body,
                                                           @RequestParam(name = "page", defaultValue = "0") int page,
                                                           @RequestParam(name = "limit", defaultValue = "10") int limit) {
        body.setLimit(limit);
        body.setPage(page);

        BatchsDetailView batchs = accountingCorePresentationService.listAllBatch(body);

        return ResponseEntity.ok().body(batchs);
    }

    @Tag(name = "Batches", description = "Batches API")
    @GetMapping(value = "/batches/reprocess/{batchId}",  produces = APPLICATION_JSON_VALUE)
    @Operation(description = "Batch reprocess",
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = BatchReprocessView.class))
                    }),
                    @ApiResponse(responseCode = "404", description = "Error: response status is 404", content = {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(example = "{\"title\": \"BATCH_NOT_FOUND\",\"status\": 404,\"detail\": \"Batch with id: {batchId} could not be found\"" +
                            "}"))})
            }
    )
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<BatchReprocessView> batchReprocess(@Valid @PathVariable("batchId") @Parameter(example = "TESTd12027c0788116d14723a4ab4a67636a7d6463d84f0c6f7adf61aba32c04") String batchId) {
        BatchReprocessView transactionProcessViewsResult = accountingCorePresentationService.scheduleReIngestionForFailed(batchId);

        return ResponseEntity
                .status(HttpStatusCode.valueOf(OK.getStatusCode()))
                .body(transactionProcessViewsResult);
    }

    @Tag(name = "Batches", description = "Batches API")
    @GetMapping(value = "/batches/{batchId}", produces = APPLICATION_JSON_VALUE)
    @Operation(
            description = "Returns the details of a batch, including a pageable list of transactions. " +
                    "Optionally, transactions can be filtered by their processing status.",
            parameters = {
                    @Parameter(name = "page", description = "Page number (zero-based). Default is null, returning all transactions.", example = "0"),
                    @Parameter(name = "size", description = "Page size (number of elements per page). Default is null, returning all transactions.", example = "10"),
                    @Parameter(name = "txStatus", description = "Filter transactions by their processing statuses. Accepts multiple statuses.",
                            array = @ArraySchema(schema = @Schema(implementation = TransactionProcessingStatus.class)))
            },
            responses = {
                    @ApiResponse(content = {
                            @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = BatchView.class))
                    }),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Error: response status is 404",
                            content = @Content(mediaType = APPLICATION_JSON_VALUE,
                                    schema = @Schema(example = "{\"title\": \"BATCH_NOT_FOUND\",\"status\": 404,\"detail\": \"Batch with id: {batchId} could not be found\"}")
                            )
                    )
            }
    )
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> batchesDetail(@Valid @PathVariable("batchId") @Parameter(example = "TESTd12027c0788116d14723a4ab4a67636a7d6463d84f0c6f7adf61aba32c04") String batchId,
                                           @RequestParam(name = "page", required = false) Optional<Integer> page,
                                           @RequestParam(name = "size", required = false) Optional<Integer> size,
                                           @RequestParam(name = "txStatus", required = false) List <TransactionProcessingStatus> txStatus) {
        Pageable pageable;
        if(page.isEmpty() || size.isEmpty()) {
            pageable = Pageable.unpaged();
        } else {
            pageable = PageRequest.of(page.get(), size.get());
        }
        Optional<BatchView> txBatchM = accountingCorePresentationService.batchDetail(batchId, txStatus, pageable);
        if (txBatchM.isEmpty()) {
            ThrowableProblem issue = Problem.builder()
                    .withTitle("BATCH_NOT_FOUND")
                    .withDetail(STR."Batch with id: {\{batchId}} could not be found")
                    .withStatus(NOT_FOUND)
                    .build();

            return ResponseEntity
                    .status(issue.getStatus().getStatusCode())
                    .body(issue);
        }

        return ResponseEntity
                .ok()
                .body(txBatchM.orElseThrow());
    }

}
