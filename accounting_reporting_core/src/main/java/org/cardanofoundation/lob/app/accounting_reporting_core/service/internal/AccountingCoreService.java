package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.LOB;
import static org.zalando.problem.Status.BAD_REQUEST;
import static org.zalando.problem.Status.NOT_FOUND;

import java.time.LocalDate;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.apache.commons.lang3.Range;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.assistance.AccountingPeriodCalculator;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.ProcessorFlags;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountingCoreService {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionBatchRepository transactionBatchRepository;
    private final ERPIncomingDataProcessor erpIncomingDataProcessor;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final AccountingPeriodCalculator accountingPeriodCalculator;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @Value("${lob.max.transaction.numbers.per.batch:600}")
    private int maxTransactionNumbersPerBatch = 600;

    @Transactional
    public Either<Problem, Void> scheduleIngestion(UserExtractionParameters userExtractionParameters) {
        log.info("scheduleIngestion, parameters: {}", userExtractionParameters);

        val organisationId = userExtractionParameters.getOrganisationId();
        val fromDate = userExtractionParameters.getFrom();
        val toDate = userExtractionParameters.getTo();

        val dateRangeCheckE = checkIfWithinAccountPeriodRange(organisationId, fromDate, toDate);
        if (dateRangeCheckE.isLeft()) {
            return dateRangeCheckE;
        }

        if (userExtractionParameters.getTransactionNumbers().size() > maxTransactionNumbersPerBatch) {
            return Either.left(Problem.builder()
                    .withTitle("TOO_MANY_TRANSACTIONS")
                    .withDetail(STR."Too many transactions requested, maximum is \{maxTransactionNumbersPerBatch}")
                    .withStatus(BAD_REQUEST)
                    .build());
        }

        val event = ScheduledIngestionEvent.builder().metadata(EventMetadata.create(ScheduledIngestionEvent.VERSION, keycloakSecurityHelper.getCurrentUser()))
                .organisationId(userExtractionParameters.getOrganisationId())
                .userExtractionParameters(userExtractionParameters)
                .build();

        applicationEventPublisher.publishEvent(event);

        return Either.right(null); // all fine
    }

    @Transactional
    public Either<Problem, Void> scheduleReconcilation(String organisationId,
                                                       LocalDate fromDate,
                                                       LocalDate toDate) {
        log.info("scheduleReconilation, organisationId: {}, from: {}, to: {}", organisationId, fromDate, toDate);

        val dateRangeCheckE = checkIfWithinAccountPeriodRange(organisationId, fromDate, toDate);
        if (dateRangeCheckE.isLeft()) {
            return dateRangeCheckE;
        }

        val event = ScheduledReconcilationEvent.builder()
                .organisationId(organisationId)
                .from(fromDate)
                .to(toDate)
                .metadata(EventMetadata.create(ScheduledReconcilationEvent.VERSION))
                .build();

        applicationEventPublisher.publishEvent(event);

        return Either.right(null); // all fine
    }

    @Transactional
    public Either<Problem, Void> scheduleReIngestionForFailed(String batchId) {
        log.info("scheduleReIngestion..., batchId: {}", batchId);

        val txBatchM = transactionBatchRepository.findById(batchId);
        if (txBatchM.isEmpty()) {
            return Either.left(Problem.builder()
                        .withTitle("TX_BATCH_NOT_FOUND")
                        .withDetail(STR."Transaction batch with id: \{batchId} not found")
                        .withStatus(NOT_FOUND)
                    .build());
        }

        val txBatch = txBatchM.get();

        val txs =  txBatch.getTransactions().stream()
                //.filter(tx -> tx.getAutomatedValidationStatus() == FAILED)
                // reprocess only the ones that have not been approved to dispatch yet, actually it is just a sanity check because it should never happen
                // and we should never allow approving failed transactions
                .filter(tx -> !tx.allApprovalsPassedForTransactionDispatch())
                // we are interested only  in the ones that have LOB violations (conversion issues) or rejection issues
                .filter(tx -> tx.getViolations().stream().anyMatch(v -> v.getSource() == LOB) || tx.getItems().stream().anyMatch(i -> i.getRejection().stream().anyMatch(rr -> rr.getRejectionReason().getSource().equals(LOB))))
                .collect(Collectors.toSet());

        if (txs.isEmpty()) {
            return Either.right(null);
        }

        val processorFlags = new ProcessorFlags(ProcessorFlags.Trigger.REPROCESSING);

        val organisationId = txBatch.getOrganisationId();

        erpIncomingDataProcessor.continueIngestion(organisationId, batchId, txs.size(), txs, processorFlags);

        return Either.right(null);
    }

    private Either<Problem, Void> checkIfWithinAccountPeriodRange(String organisationId,
                                                                  LocalDate fromDate,
                                                                  LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            return Either.left(Problem.builder()
                    .withTitle("INVALID_DATE_RANGE")
                    .withDetail("From date must be before to date")
                    .withStatus(BAD_REQUEST)
                    .build());
        }
        val userExtractionRange = Range.of(fromDate, toDate);

        val orgM = organisationPublicApi.findByOrganisationId(organisationId);

        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Organisation with id: \{organisationId} not found")
                    .withStatus(BAD_REQUEST)
                    .build());
        }
        val org = orgM.orElseThrow();
        val accountingPeriod = accountingPeriodCalculator.calculateAccountingPeriod(org);

        val withinRange = accountingPeriod.containsRange(userExtractionRange);
        val outsideOfRange = !withinRange;

        if (outsideOfRange) {
            return Either.left(Problem.builder()
                    .withTitle("ORGANISATION_DATE_MISMATCH")
                    .withDetail(STR."Date range must be within the accounting period: \{accountingPeriod}")
                    .withStatus(BAD_REQUEST)
                    .with("accountingPeriodFrom", accountingPeriod.getMinimum())
                    .with("accountingPeriodTo", accountingPeriod.getMaximum())
                    .build()
            );
        }

        return Either.right(null);
    }

}
