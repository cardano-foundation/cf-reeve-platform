package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.springframework.web.bind.annotation.RequestMethod.POST;

import java.time.LocalDate;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.ReportService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionBatchService;

@RestController
@RequestMapping("/api/core")
@Slf4j
@RequiredArgsConstructor
@Deprecated(forRemoval = true)
@ConditionalOnProperty(value = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = true)
public class ExperimentalAccountingCoreResource {

    private final AccountingCoreService accountingCoreService;
    private final TransactionBatchService transactionBatchService;
    private final ReportService reportService;

    @PostConstruct
    public void init() {
        log.info("AccountingCoreResource init.");
    }

    @RequestMapping(value = "/schedule/new", method = POST, produces = "application/json")
    public ResponseEntity<?> schedule() {
        val now = LocalDate.now();

        val userExtractionParameters = UserExtractionParameters.builder()
                .from(now.minusYears(20))
                .to(now.minusDays(1))
                .organisationId("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
                //.transactionTypes(List.of(TransactionType.CardCharge, TransactionType.FxRevaluation))
                //.transactionNumbers(List.of("JOURNAL226", "JOURNAL227"))
                .build();

        return accountingCoreService.scheduleIngestion(userExtractionParameters, ExtractorType.NETSUITE, null, null).fold(problem -> {
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }, success -> {
            return ResponseEntity.ok().build();
        });
    }

    @RequestMapping(value = "/reschedule/failed/{batch_id}", method = POST, produces = "application/json")
    public ResponseEntity<?> reprocessFailed(@Valid @PathVariable("batch_id") String batchId) {
        return accountingCoreService.scheduleReIngestionForFailed(batchId)
                .fold(problem -> {
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
                }, success -> {
                    return ResponseEntity.ok().build();
                });
    }

    @RequestMapping(value = "/reschedule/last-failed", method = POST, produces = "application/json")
    public ResponseEntity<?> reprocessFailedForLastBatch() {

        // q: sort by creation time, most recent first and take the most recent
        val lastBatchM = transactionBatchService.findAll().stream().min((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        return accountingCoreService.scheduleReIngestionForFailed(lastBatchM.orElseThrow().getId())
                .fold(problem -> {
                    return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
                }, success -> {
                    return ResponseEntity.ok().build();
                });
    }

    @RequestMapping(value = "/reconcile", method = POST, produces = "application/json")
    public ResponseEntity<?> reconcileStart() {
        val orgId = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94";
        val fromDate = LocalDate.now().minusYears(20);
        val toDate = LocalDate.now().minusDays(1);

        return accountingCoreService.scheduleReconcilation(orgId, fromDate, toDate, ExtractorType.NETSUITE, null, null).fold(problem -> {
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }, success -> {
            return ResponseEntity.ok().build();
        });
    }

    @RequestMapping(value = "/report-test-bs", method = POST, produces = "application/json")
    public ResponseEntity<?> exampleReportBs() {
        val orgId = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94";

        return reportService.storeBalanceSheetAsExample(orgId).fold(problem -> {
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }, success -> {
            return ResponseEntity.ok().build();
        });
    }

    @RequestMapping(value = "/report-test-is", method = POST, produces = "application/json")
    public ResponseEntity<?> exampleReportIs() {
        val orgId = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94";

        return reportService.storeIncomeStatementAsExample(orgId).fold(problem -> {
            return ResponseEntity.status(problem.getStatus().getStatusCode()).body(problem);
        }, success -> {
            return ResponseEntity.ok().build();
        });
    }

}
