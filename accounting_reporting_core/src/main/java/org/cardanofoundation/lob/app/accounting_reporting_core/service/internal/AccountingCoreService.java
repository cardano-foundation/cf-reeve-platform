package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.apache.commons.lang3.Range;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.ValidateIngestionResponseWaiter;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.ProcessorFlags;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.util.AccountingPeriodCalculator;
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
    private final ValidateIngestionResponseWaiter responseWaiter;

    @Value("${lob.max.transaction.numbers.per.batch:600}")
    private int maxTransactionNumbersPerBatch = 600;

    @Transactional(readOnly = true)
    public Either<ProblemDetail, Void> scheduleIngestion(UserExtractionParameters userExtractionParameters, ExtractorType extractorType, Optional<MultipartFile> file, Map<String, Object> parameters) {
        log.info("scheduleIngestion, parameters: {}", userExtractionParameters);

        Either<ProblemDetail, Void> dateRangeCheckE = checkIfWithinAccountPeriodRange(
                userExtractionParameters.getOrganisationId(),
                userExtractionParameters.getFrom(),
                userExtractionParameters.getTo());
        if (dateRangeCheckE.isLeft()) {
            return dateRangeCheckE;
        }

        if (userExtractionParameters.getTransactionNumbers().size() > maxTransactionNumbersPerBatch) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Too many transactions requested, maximum is %s".formatted(maxTransactionNumbersPerBatch));
            problem.setTitle("TOO_MANY_TRANSACTIONS");
            return Either.left(problem);
        }
        Either<ProblemDetail, byte[]> bytesE = antiVirusScanner.readFileBytes(file);
        if (bytesE.isLeft()) {
            return Either.left(bytesE.getLeft());
        }

        ScheduledIngestionEvent event = ScheduledIngestionEvent.builder().metadata(EventMetadata.create(ScheduledIngestionEvent.VERSION, keycloakSecurityHelper.getCurrentUser()))
                .organisationId(userExtractionParameters.getOrganisationId())
                .userExtractionParameters(userExtractionParameters)
                .extractorType(extractorType)
                .file(bytesE.get())
                .parameters(parameters)
                .build();

        applicationEventPublisher.publishEvent(event);

        return Either.right(null); // all fine
    }

    public Either<List<ProblemDetail>, Void> validateIngestion(UserExtractionParameters userExtractionParameters, ExtractorType extractorType, Optional<MultipartFile> file, Map<String, Object> parameters) {
        Either<ProblemDetail, Void> dateRangeCheckE = checkIfWithinAccountPeriodRange(
                userExtractionParameters.getOrganisationId(),
                userExtractionParameters.getFrom(),
                userExtractionParameters.getTo());
        if (dateRangeCheckE.isLeft()) {
            return Either.left(List.of(dateRangeCheckE.getLeft()));
        }

        if (userExtractionParameters.getTransactionNumbers().size() > maxTransactionNumbersPerBatch) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Too many transactions requested, maximum is %s".formatted(maxTransactionNumbersPerBatch));
            problem.setTitle("TOO_MANY_TRANSACTIONS");
            return Either.left(List.of(problem));
        }
        Either<ProblemDetail, byte[]> bytesE = antiVirusScanner.readFileBytes(file);
        if (bytesE.isLeft()) {
            return Either.left(List.of(bytesE.getLeft()));
        }
        String correlationId = UUID.randomUUID().toString();
        ValidateIngestionEvent validateIngestionEvent = ValidateIngestionEvent.builder()
                .correlationId(correlationId)
                .scheduledIngestionEvent(ScheduledIngestionEvent.builder().metadata(EventMetadata.create(ScheduledIngestionEvent.VERSION, keycloakSecurityHelper.getCurrentUser()))
                        .organisationId(userExtractionParameters.getOrganisationId())
                        .userExtractionParameters(userExtractionParameters)
                        .extractorType(extractorType)
                        .file(bytesE.get())
                        .parameters(parameters)
                        .build())
                .build();

        CompletableFuture<ValidateIngestionResponseEvent> future = responseWaiter.createFuture(correlationId);

        applicationEventPublisher.publishEvent(validateIngestionEvent);
        // wait synchronously, with timeout for ValidateIngestionResponseEvent to be published to mimic a synchronous call
        try {
            ValidateIngestionResponseEvent response = future.get(10, TimeUnit.SECONDS);
            if (!response.isValid()) {
                // return the list of problems
                return Either.left(response.getErrors().stream().map(error -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, error);
                    problem.setTitle("VALIDATION_ERROR");
                    return problem;
                }).collect(Collectors.toList()));
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Error waiting for ValidateIngestionResponseEvent", e);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Error validating ingestion: %s".formatted(e.getMessage()));
            problem.setTitle("VALIDATION_ERROR");
            return Either.left(List.of(problem));
        }
        return Either.right(null);
    }

    @Transactional(readOnly = true)
    public Either<ProblemDetail, Void> scheduleReconcilation(String organisationId,
                                                       LocalDate fromDate,
                                                       LocalDate toDate, ExtractorType extractorType, Optional<MultipartFile> file, Map<String, Object> parameters) {
        log.info("scheduleReconilation, organisationId: {}, from: {}, to: {}", organisationId, fromDate, toDate);

        Either<ProblemDetail, Void> dateRangeCheckE = checkIfWithinAccountPeriodRange(organisationId, fromDate, toDate);
        if (dateRangeCheckE.isLeft()) {
            return dateRangeCheckE;
        }
        Either<ProblemDetail, byte[]> bytesE = antiVirusScanner.readFileBytes(file);
        if (bytesE.isLeft()) {
            return Either.left(bytesE.getLeft());
        }

        ScheduledReconcilationEvent event = ScheduledReconcilationEvent.builder()
                .organisationId(organisationId)
                .from(fromDate)
                .to(toDate)
                .metadata(EventMetadata.create(ScheduledReconcilationEvent.VERSION))
                .extractorType(extractorType)
                .file(bytesE.get())
                .parameters(parameters)
                .build();

        applicationEventPublisher.publishEvent(event);

        return Either.right(null); // all fine
    }

    @Transactional
    public Either<ProblemDetail, Void> scheduleReIngestionForFailed(String batchId) {
        log.info("scheduleReIngestion..., batchId: {}", batchId);

        Optional<TransactionBatchEntity> txBatchM = transactionBatchRepository.findById(batchId);
        if (txBatchM.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(NOT_FOUND, "Transaction batch with id: %s not found".formatted(batchId));
            problem.setTitle("TX_BATCH_NOT_FOUND");
            return Either.left(problem);
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
        TransactionBatchEntity txBatch = txBatchM.orElseThrow();

        Set<TransactionEntity> txs = txBatch.getTransactions().stream()
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

    private Either<ProblemDetail, Void> checkIfWithinAccountPeriodRange(String organisationId,
                                                                  LocalDate fromDate,
                                                                  LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "From date must be before to date");
            problem.setTitle("INVALID_DATE_RANGE");
            return Either.left(problem);
        }
        Range<LocalDate> userExtractionRange = Range.of(fromDate, toDate);

        Optional<Organisation> orgM = organisationPublicApi.findByOrganisationId(organisationId);

        if (orgM.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Organisation with id: %s not found".formatted(organisationId));
            problem.setTitle("ORGANISATION_NOT_FOUND");
            return Either.left(problem);
        }
        Organisation org = orgM.orElseThrow();
        Range<LocalDate> accountingPeriod = accountingPeriodCalculator.calculateAccountingPeriod(org);

        boolean withinRange = accountingPeriod.containsRange(userExtractionRange);
        boolean outsideOfRange = !withinRange;

        if (outsideOfRange) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Date range must be within the accounting period: %s".formatted(accountingPeriod));
            problem.setTitle("ORGANISATION_DATE_MISMATCH");
            problem.setProperty("accountingPeriodFrom", accountingPeriod.getMinimum());
            problem.setProperty("accountingPeriodTo", accountingPeriod.getMaximum());
            return Either.left(problem);
        }

        return Either.right(null);
    }


}
