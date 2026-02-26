package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.Map;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRejectionCodeRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationStatisticRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconcileResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationStatisticView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.accounting_reporting_core.utils.PageableFieldMappings;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class AccountingCoreResourceReconciliation {

    private final AccountingCorePresentationViewService accountingCorePresentationService;
    private final AccountingCoreService accountingCoreService;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    @Tag(name = "Reconciliation", description = "Reconciliation API")
    @Operation(description = "Start the Reconciliation", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReconcileResponseView.class))}
            )
    })
    @PostMapping(value = "/reconcile/trigger", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ReconcileResponseView> reconcileTriggerAction(@Valid @RequestBody ReconciliationRequest body) {
        return accountingCoreService.scheduleReconcilation(body.getOrganisationId(), body.getDateFrom(), body.getDateTo(), body.getExtractorType(), Optional.ofNullable(body.getFile()), body.getParameters()).fold(
                problem -> ResponseEntity.status(problem.getStatus()).body(ReconcileResponseView.createFail(problem.getTitle(), body.getDateFrom(), body.getDateTo(), problem)),
                success -> ResponseEntity.ok(ReconcileResponseView.createSuccess("We have received your reconcile request now.", body.getDateFrom(), body.getDateTo()))
        );
    }

    @PostMapping(value = "/reconcile/triggercsv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReconcileResponseView> reconcileTriggerCsvAction(@ModelAttribute ReconciliationRequest body) {
        return reconcileTriggerAction(body);
    }

    @Operation(description = "Get the Reconciliations", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReconciliationResponseView.class))}
            )
    })
    @Tag(name = "Reconciliation", description = "Reconciliation API")
    @PostMapping(value = "/transactions-reconcile", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<?> reconcileStart(@Valid @RequestBody ReconciliationFilterRequest body,
                                            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        Either<ProblemDetail, Pageable> pageableEither = jpaSortFieldValidator.convertPageable(pageable,
                        PageableFieldMappings.RECONCILATION_FIELD_MAPPINGS, TransactionEntity.class);
        if (pageableEither.isLeft()) {
            return ResponseEntity.badRequest().body(pageableEither.getLeft());
        }
        ReconciliationResponseView reconciliationResponseView = accountingCorePresentationService.allReconciliationTransaction(body, pageableEither.get());

        return ResponseEntity.ok().body(reconciliationResponseView);
    }

    @Operation(description = "Reconciliation Rejection Codes", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReconciliationRejectionCodeRequest.class))}
            )
    })
    @Tag(name = "Reconciliation", description = "Reconciliation API")
    @GetMapping(value = "/transactions-rejection-codes", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ReconciliationRejectionCodeRequest[]> reconciliationRejectionCode() {
        return ResponseEntity.ok().body(ReconciliationRejectionCodeRequest.values());
    }

    @Tag(name = "Reconciliation", description = "Reconciliation API")
    @Operation(description = "Get reconciliation statistics for a date range with optional aggregation", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReconciliationStatisticView.class))}
            )
    })
    @PostMapping(value = "/reconciliation-statistic", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<Map<String, ReconciliationStatisticView>> reconciliationStatistic(@Valid @RequestBody ReconciliationStatisticRequest body) {
        Map<String, ReconciliationStatisticView> result = accountingCorePresentationService.getReconciliationStatisticByDateRange(body);
        return ResponseEntity.ok().body(result);
    }

}
