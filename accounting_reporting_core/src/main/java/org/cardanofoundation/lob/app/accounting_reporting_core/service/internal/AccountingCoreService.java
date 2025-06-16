package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.zalando.problem.Status.BAD_REQUEST;
import static org.zalando.problem.Status.NOT_FOUND;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.apache.commons.lang3.Range;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.assistance.AccountingPeriodCalculator;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.ProcessorFlags;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;
import org.cardanofoundation.lob.app.support.reactive.DebouncerManager;
import org.cardanofoundation.lob.app.support.security.AntiVirusScanner;
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
    private final DebouncerManager debouncerManager;
    private final AntiVirusScanner antiVirusScanner;

    @Value("${lob.max.transaction.numbers.per.batch:600}")
    private int maxTransactionNumbersPerBatch = 600;

    @Transactional(readOnly = true)
    public Either<Problem, Void> scheduleIngestion(UserExtractionParameters userExtractionParameters, ExtractorType extractorType, MultipartFile file, Map<String, Object> parameters) {
        log.info("scheduleIngestion, parameters: {}", userExtractionParameters);

        String organisationId = userExtractionParameters.getOrganisationId();
        LocalDate fromDate = userExtractionParameters.getFrom();
        LocalDate toDate = userExtractionParameters.getTo();

        Either<Problem, Void> dateRangeCheckE = checkIfWithinAccountPeriodRange(organisationId, fromDate, toDate);
        if (dateRangeCheckE.isLeft()) {
            return dateRangeCheckE;
        }

        if (userExtractionParameters.getTransactionNumbers().size() > maxTransactionNumbersPerBatch) {
            return Either.left(Problem.builder()
                    .withTitle("TOO_MANY_TRANSACTIONS")
                    .withDetail("Too many transactions requested, maximum is %s".formatted(maxTransactionNumbersPerBatch))
                    .withStatus(BAD_REQUEST)
                    .build());
        }
        byte[] fileBytes;
        try {
            if(file != null && !file.isEmpty()) {
                fileBytes = file.getBytes();
                if(!antiVirusScanner.isFileSafe(fileBytes)) {
                    return Either.left(Problem.builder()
                            .withTitle("FILE_VIRUS_DETECTED")
                            .withDetail("The uploaded file contains a virus and cannot be processed.")
                            .withStatus(BAD_REQUEST)
                            .build());
                }
            } else {
                fileBytes = null;
            }
        } catch (IOException e) {
            return Either.left(Problem.builder()
                    .withTitle("FILE_READ_ERROR")
                    .withDetail("Error reading file")
                    .withStatus(BAD_REQUEST)
                    .build());
        }
        ScheduledIngestionEvent event = ScheduledIngestionEvent.builder().metadata(EventMetadata.create(ScheduledIngestionEvent.VERSION, keycloakSecurityHelper.getCurrentUser()))
                .organisationId(userExtractionParameters.getOrganisationId())
                .userExtractionParameters(userExtractionParameters)
                .extractorType(extractorType)
                .file(fileBytes)
                .parameters(parameters)
                .build();

        applicationEventPublisher.publishEvent(event);

        return Either.right(null); // all fine
    }

    @Transactional(readOnly = true)
    public Either<Problem, Void> scheduleReconcilation(String organisationId,
                                                       LocalDate fromDate,
                                                       LocalDate toDate, ExtractorType extractorType, MultipartFile file, Map<String, Object> parameters) {
        log.info("scheduleReconilation, organisationId: {}, from: {}, to: {}", organisationId, fromDate, toDate);

        Either<Problem, Void> dateRangeCheckE = checkIfWithinAccountPeriodRange(organisationId, fromDate, toDate);
        if (dateRangeCheckE.isLeft()) {
            return dateRangeCheckE;
        }
        byte[] fileBytes;
        try {
            if(file != null) {
                fileBytes = file.getBytes();
            } else {
                fileBytes = null;
            }
        } catch (IOException e) {
            return Either.left(Problem.builder()
                    .withTitle("FILE_READ_ERROR")
                    .withDetail("Error reading file")
                    .withStatus(BAD_REQUEST)
                    .build());
        }

        ScheduledReconcilationEvent event = ScheduledReconcilationEvent.builder()
                .organisationId(organisationId)
                .from(fromDate)
                .to(toDate)
                .metadata(EventMetadata.create(ScheduledReconcilationEvent.VERSION))
                .extractorType(extractorType)
                .file(fileBytes)
                .parameters(parameters)
                .build();

        applicationEventPublisher.publishEvent(event);

        return Either.right(null); // all fine
    }

    @Transactional
    public Either<Problem, Void> scheduleReIngestionForFailed(String batchId) {
        log.info("scheduleReIngestion..., batchId: {}", batchId);

        Optional<TransactionBatchEntity> txBatchM = transactionBatchRepository.findById(batchId);
        if (txBatchM.isEmpty()) {
            return Either.left(Problem.builder()
                        .withTitle("TX_BATCH_NOT_FOUND")
                        .withDetail(STR."Transaction batch with id: \{batchId} not found")
                        .withStatus(NOT_FOUND)
                    .build());
        }
        try {
            // calling it in a different thread
            debouncerManager.getDebouncer(batchId + "reprocess", () -> processReIngestionForFailed(batchId), Duration.ZERO).call();
        } catch (ExecutionException e) {
            processReIngestionForFailed(batchId);
        }


        return Either.right(null);
    }

    private void processReIngestionForFailed(String batchId) {
        Optional<TransactionBatchEntity> txBatchM = transactionBatchRepository.findById(batchId);
        TransactionBatchEntity txBatch = txBatchM.get();

        Set<TransactionEntity> txs =  txBatch.getTransactions().stream()
                //.filter(tx -> tx.getAutomatedValidationStatus() == FAILED)
                // reprocess only the ones that have not been approved to dispatch yet, actually it is just a sanity check because it should never happen
                // and we should never allow approving failed transactions
                .filter(tx -> !tx.allApprovalsPassedForTransactionDispatch())
                // we are interested only  in the ones that have LOB violations (conversion issues) or rejection issues and additionally those who don't have any processing status, this shouldn't happen in normal environments.
                // Should be only the case for dev data
                .filter(tx -> tx.getProcessingStatus().map(status -> status.equals(TransactionProcessingStatus.PENDING)).orElse(true))
                .collect(Collectors.toSet());

        if (txs.isEmpty()) {
            return;
        }

        ProcessorFlags processorFlags = new ProcessorFlags(ProcessorFlags.Trigger.REPROCESSING);

        String organisationId = txBatch.getOrganisationId();

        erpIncomingDataProcessor.continueIngestion(organisationId, batchId, txs.size(), txs, processorFlags);
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
        Range<LocalDate> userExtractionRange = Range.of(fromDate, toDate);

        Optional<Organisation> orgM = organisationPublicApi.findByOrganisationId(organisationId);

        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Organisation with id: \{organisationId} not found")
                    .withStatus(BAD_REQUEST)
                    .build());
        }
        Organisation org = orgM.orElseThrow();
        Range<LocalDate> accountingPeriod = accountingPeriodCalculator.calculateAccountingPeriod(org);

        boolean withinRange = accountingPeriod.containsRange(userExtractionRange);
        boolean outsideOfRange = !withinRange;

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
