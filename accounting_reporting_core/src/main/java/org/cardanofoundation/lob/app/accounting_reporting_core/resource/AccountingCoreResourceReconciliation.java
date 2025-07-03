package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service.AccountingCorePresentationViewService;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationFilterRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRejectionCodeRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconcileResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReconciliationResponseView;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
public class AccountingCoreResourceReconciliation {

    private final AccountingCorePresentationViewService accountingCorePresentationService;
    private final AccountingCoreService accountingCoreService;

    @Tag(name = "Reconciliation", description = "Reconciliation API")
    @Operation(description = "Start the Reconciliation", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReconcileResponseView.class))}
            )
    })
    @PostMapping(value = "/reconcile/trigger", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ReconcileResponseView> reconcileTriggerAction(@Valid @RequestBody ReconciliationRequest body) {
        return accountingCoreService.scheduleReconcilation(body.getOrganisationId(), body.getDateFrom(), body.getDateTo()).fold(
                problem -> ResponseEntity.status(problem.getStatus().getStatusCode()).body(ReconcileResponseView.createFail(problem.getTitle(), body.getDateFrom(), body.getDateTo(), problem)),
                success -> ResponseEntity.ok(ReconcileResponseView.createSuccess("We have received your reconcile request now.", body.getDateFrom(), body.getDateTo()))
        );
    }

    @Operation(description = "Get the Reconciliations", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReconciliationResponseView.class))}
            )
    })
    @Tag(name = "Reconciliation", description = "Reconciliation API")
    @PostMapping(value = "/transactions-reconcile", produces = "application/json")
    @PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAuditorRole()) or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAdminRole())")
    public ResponseEntity<ReconciliationResponseView> reconcileStart(@Valid @RequestBody ReconciliationFilterRequest body,
                                            @RequestParam(name = "page", defaultValue = "0") int page,
                                            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        body.setLimit(limit);
        body.setPage(page);

        ReconciliationResponseView reconciliationResponseView = accountingCorePresentationService.allReconciliationTransaction(body);

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

}
