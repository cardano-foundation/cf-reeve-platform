package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError.Code.ADAPTER_ERROR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent.Status.FINISHED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent.Status.PROCESSING;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent.Status.STARTED;
import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.time.LocalDate;
import java.util.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.google.common.cache.Cache;
import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.SystemExtractionParametersFactory;
import org.cardanofoundation.lob.app.csv_erp_adapter.config.Constants;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.ExtractionData;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.TransactionLine;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.support.collections.Partitions;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@Service
@Slf4j
@RequiredArgsConstructor
public class CsvExtractionService {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final Cache<String, ExtractionData> temporaryFileCache;
    private final SystemExtractionParametersFactory systemExtractionParametersFactory;
    @Qualifier("csvTransactionConverter")
    private final TransactionConverter transactionConverter;
    private final CsvParser<TransactionLine> csvParser;
    @Value("${lob.csv.delimiter:;}")
    private String delimiter;
    @Value("${lob.csv.send-batch-size:100}")
    private int sendBatchSize;

    public void validateIngestion(String correlationId, String organisationId, byte[] file) {
        List<Problem> errors = new ArrayList<>();

        Either<Problem, SystemExtractionParameters> systemExtractionParametersE = systemExtractionParametersFactory.createSystemExtractionParameters(organisationId);
        if(systemExtractionParametersE.isLeft()) {
            errors.add(systemExtractionParametersE.getLeft());
        }

        if(Objects.isNull(file) || file.length == 0) {
            errors.add(Problem.builder()
                    .withTitle(Constants.EMPTY_FILE)
                    .withDetail("The provided file is empty.")
                    .build());
        } else {
            Either<Problem, List<TransactionLine>> lists = csvParser.parseCsv(file, TransactionLine.class);
            if(lists.isLeft()) {
                errors.add(lists.getLeft());
            } else {
                List<TransactionLine> transactionLines = lists.get();
                if (transactionLines.isEmpty()) {
                    errors.add(Problem.builder()
                            .withTitle(Constants.NO_TRANSACTION_LINES)
                            .withDetail("The provided CSV file does not contain any transaction lines.")
                            .build());
                }
            }
        }

        ValidateIngestionResponseEvent validateIngestionResponseEvent = ValidateIngestionResponseEvent.builder()
                .correlationId(correlationId)
                .errors(errors.stream().map(Problem::getDetail).toList())
                .valid(errors.isEmpty())
                .build();
        applicationEventPublisher.publishEvent(validateIngestionResponseEvent);
    }

    public void startNewExtraction(@NotNull String organisationId, String user, @NotNull UserExtractionParameters userExtractionParameters, byte[] file) {
        String batchId = digestAsHex(UUID.randomUUID().toString());
        Either<Problem, SystemExtractionParameters> systemExtractionParametersE = systemExtractionParametersFactory.createSystemExtractionParameters(organisationId);

        if (systemExtractionParametersE.isLeft()) {

            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION, user))
                    .batchId(batchId)
                    .extractorType(ExtractorType.CSV)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.NO_SYSTEM_PARAMETERS, this.getBag(Problem.builder()
                            .withTitle(Constants.EMPTY_FILE)
                            .withDetail("organisationId" +  organisationId)
                            .build(),Constants.EMPTY_FILE)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }
        SystemExtractionParameters systemExtractionParameters = systemExtractionParametersE.get();

        if(Objects.isNull(file) || file.length == 0) {
            log.error("File is empty");
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION, user))
                    .batchId(batchId)
                    .extractorType(ExtractorType.CSV)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.EMPTY_FILE, this.getBag(Problem.builder()
                            .withTitle(Constants.EMPTY_FILE)
                            .withDetail(Constants.BATCH_ID +  batchId)
                            .build(),Constants.EMPTY_FILE)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }

        ExtractionData extractionData = new ExtractionData(
                batchId,
                organisationId,
                user,
                userExtractionParameters,
                systemExtractionParameters,
                file
        );

        temporaryFileCache.put(batchId, extractionData);
        log.info(Constants.TEMPORARY_FILE_CACHE_SIZE_LOG, temporaryFileCache.size());
        applicationEventPublisher.publishEvent(TransactionBatchStartedEvent.builder()
                .metadata(EventMetadata.create(TransactionBatchStartedEvent.VERSION, user))
                .batchId(batchId)
                .extractorType(ExtractorType.CSV)
                .organisationId(userExtractionParameters.getOrganisationId())
                .userExtractionParameters(userExtractionParameters)
                .systemExtractionParameters(systemExtractionParameters)
                .build()
        );
    }

    public void continueERPExtraction(@NotBlank String batchId, @NotBlank String organisationId, @NotNull UserExtractionParameters userExtractionParameters, @NotNull SystemExtractionParameters systemExtractionParameters) {

        ExtractionData extractionData = temporaryFileCache.getIfPresent(batchId);
        if (extractionData == null) {
            log.error("BatchId {} not found in temporary file cache", batchId);
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .batchId(batchId)
                    .extractorType(ExtractorType.CSV)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.BATCH_NOT_FOUND, this.getBag(Problem.builder()
                            .withTitle(Constants.BATCH_NOT_FOUND)
                            .withDetail(Constants.BATCH_ID +  batchId)
                            .build(),Constants.BATCH_NOT_FOUND)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }
        // invalidating cache to avoid memory leak
        temporaryFileCache.invalidate(batchId);
        log.info(Constants.TEMPORARY_FILE_CACHE_SIZE_LOG, temporaryFileCache.size());

        if (!extractionData.organisationId().equals(organisationId)) {
            log.error("BatchId {} not found in temporary file cache for organisationId {}", batchId, organisationId);
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .batchId(batchId)
                    .extractorType(ExtractorType.CSV)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.ORGANISATION_MISMATCH,this.getBag(Problem.builder()
                            .withTitle(Constants.ORGANISATION_MISMATCH)
                            .withDetail(Constants.BATCH_ID +  batchId)
                            .build(),Constants.ORGANISATION_MISMATCH)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }

        Either<Problem, List<TransactionLine>> lists = csvParser.parseCsv(extractionData.file(), TransactionLine.class);
        if(lists.isLeft()) {
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .batchId(batchId)
                    .extractorType(ExtractorType.CSV)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.CSV_PARSING_ERROR, this.getBag(Problem.builder()
                            .withTitle(Constants.CSV_PARSING_ERROR)
                            .withDetail(Constants.BATCH_ID +  batchId)
                            .build(),Constants.CSV_PARSING_ERROR)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }
        List<TransactionLine> transactionLines = lists.get();

        if (transactionLines.isEmpty()) {
            log.error("No transaction lines found in CSV file");
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .batchId(batchId)
                    .extractorType(ExtractorType.CSV)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.NO_TRANSACTION_LINES, this.getBag(Problem.builder()
                            .withTitle(Constants.NO_TRANSACTION_LINES)
                            .withDetail(Constants.BATCH_ID +  batchId)
                            .build(),Constants.NO_TRANSACTION_LINES)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }

        Either<Problem, List<Transaction>> transactions = transactionConverter.convertToTransaction(organisationId, batchId, transactionLines);
        if (transactions.isLeft()) {
            log.error("Error converting transaction lines to transactions: {}", transactions.getLeft().getDetail());
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .batchId(batchId)
                    .extractorType(ExtractorType.CSV)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.TRANSACTION_CONVERSION_ERROR, this.getBag(transactions.getLeft(),Constants.TRANSACTION_CONVERSION_ERROR)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }
        List<Transaction> transactionList = transactions.get();
        TransactionBatchChunkEvent.TransactionBatchChunkEventBuilder batchChunkEventBuilder = TransactionBatchChunkEvent.builder()
                .metadata(EventMetadata.create(TransactionBatchChunkEvent.VERSION))
                .batchId(batchId)
                .organisationId(organisationId)
                .systemExtractionParameters(systemExtractionParameters)
                .totalTransactionsCount(transactionList.size());
        Partitions.partition(transactionList, sendBatchSize).forEach(txPartition -> {
            batchChunkEventBuilder.transactions(txPartition.asSet());
            batchChunkEventBuilder.status(PROCESSING);
            if (txPartition.isFirst()) {
                batchChunkEventBuilder.status(STARTED);
            }
            if (txPartition.isLast()) {
                batchChunkEventBuilder.status(FINISHED);
            }

            applicationEventPublisher.publishEvent(batchChunkEventBuilder.build());
        });
        if (transactionList.isEmpty()) {
            // Notifying the API component that the batch is empty
            applicationEventPublisher.publishEvent(batchChunkEventBuilder
                    .transactions(new HashSet<>(transactionList))
                    .status(FINISHED)
                    .build());
        }
        log.info("NetSuite ingestion fully completed.");
    }

    public void startNewReconciliation(@NotNull String organisationId, String user, byte[] file, LocalDate reconcileFrom, LocalDate reconcileTo) {
        log.info("Running reconciliation...");

        String reconcilationRequestId = digestAsHex(UUID.randomUUID().toString());

        ExtractionData extractionData = new ExtractionData(
                reconcilationRequestId,
                organisationId,
                user,
                null,
                null,
                file
        );
        temporaryFileCache.put(reconcilationRequestId, extractionData);
        log.info(Constants.TEMPORARY_FILE_CACHE_SIZE_LOG, temporaryFileCache.size());
        applicationEventPublisher.publishEvent(ReconcilationStartedEvent.builder()
                .metadata(EventMetadata.create(ReconcilationStartedEvent.VERSION, user))
                .reconciliationId(reconcilationRequestId)
                .organisationId(organisationId)
                .from(reconcileFrom)
                .to(reconcileTo)
                .extractorType(ExtractorType.CSV)
                .build()
        );
    }

    public void continueERPReconciliation(@NotBlank String reconcilationId, @NotBlank String organisationId) {
        ExtractionData extractionData = temporaryFileCache.getIfPresent(reconcilationId);
        log.info("Continue reconcilation..., reconcilationId: {}", reconcilationId);
        if (extractionData == null) {
            log.error("NetSuite ingestion not found, reconcilationId: {}", reconcilationId);
            ReconcilationFailedEvent reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, Constants.RECONCILATION_NOT_FOUND, this.getBag(Problem.builder()
                            .withTitle(Constants.RECONCILATION_NOT_FOUND)
                            .withDetail(Constants.RECONCILATION_ID +  reconcilationId)
                            .build(),Constants.RECONCILATION_NOT_FOUND)))
                    .build();

            applicationEventPublisher.publishEvent(reconcilationFailedEvent);
            return;
        }
        // invalidating cache to avoid memory leak
        temporaryFileCache.invalidate(reconcilationId);
        log.info(Constants.TEMPORARY_FILE_CACHE_SIZE_LOG, temporaryFileCache.size());

        if (!extractionData.organisationId().equals(organisationId)) {
            log.error("Reconcilation {} not found in temporary file cache for organisationId {}", reconcilationId, organisationId);
            ReconcilationFailedEvent batchFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, Constants.ORGANISATION_MISMATCH, this.getBag(Problem.builder()
                            .withTitle(Constants.ORGANISATION_MISMATCH)
                            .withDetail(Constants.RECONCILATION_ID +  reconcilationId)
                            .build(),Constants.ORGANISATION_MISMATCH)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }

        Either<Problem, List<TransactionLine>> lists = csvParser.parseCsv(extractionData.file(), TransactionLine.class);
        if(lists.isLeft()) {
            ReconcilationFailedEvent batchFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, Constants.CSV_PARSING_ERROR, this.getBag(Problem.builder()
                            .withTitle(Constants.CSV_PARSING_ERROR)
                            .withDetail(Constants.RECONCILATION_ID +  reconcilationId)
                            .build(),Constants.CSV_PARSING_ERROR)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }
        List<TransactionLine> transactionLines = lists.get();

        if (transactionLines.isEmpty()) {
            log.error("No transaction lines found in CSV file");
            ReconcilationFailedEvent batchFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, Constants.NO_TRANSACTION_LINES, this.getBag(Problem.builder()
                            .withTitle(Constants.NO_TRANSACTION_LINES)
                            .withDetail(Constants.RECONCILATION_ID +  reconcilationId)
                            .build(),Constants.NO_TRANSACTION_LINES)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }

        Either<Problem, List<Transaction>> transactions = transactionConverter.convertToTransaction(organisationId, reconcilationId, transactionLines);
        if (transactions.isLeft()) {
            log.error("Error converting transaction lines to transactions: {}", transactions.getLeft().getDetail());
            ReconcilationFailedEvent batchFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, Constants.TRANSACTION_CONVERSION_ERROR,  this.getBag(Problem.builder()
                            .withTitle(Constants.TRANSACTION_CONVERSION_ERROR)
                            .withDetail(Constants.RECONCILATION_ID +  reconcilationId)
                            .build(),Constants.TRANSACTION_CONVERSION_ERROR)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }
        List<Transaction> transactionList = transactions.get();
        ReconcilationChunkEvent.ReconcilationChunkEventBuilder reconcilationChunkEvent = ReconcilationChunkEvent.builder()
                .metadata(EventMetadata.create(ReconcilationChunkEvent.VERSION))
                .reconciliationId(reconcilationId)
                .organisationId(organisationId)
                .totalTransactionsCount(transactionList.size());
        Partitions.partition(transactionList, sendBatchSize).forEach(txPartition -> {
            reconcilationChunkEvent.transactions(txPartition.asSet());
            applicationEventPublisher.publishEvent(reconcilationChunkEvent.build());
        });
        if (transactionList.isEmpty()) {
            // Notifying the API component that the batch is empty
            applicationEventPublisher.publishEvent(reconcilationChunkEvent
                    .transactions(new HashSet<>(transactionList))
                    .build());
        }
        log.info("NetSuite ingestion fully completed.");
    }

    private Map<String, Object> getBag(Problem problem, String code) {
        try {
            if (problem == null) {
                return Map.of();
            }

            Map<String, Object> error = new HashMap<>();
            if (code != null && !code.isEmpty()) {
                error.put("code", code);
            }
            if (problem.getDetail() != null && !problem.getDetail().isEmpty()) {
                error.put("message", problem.getDetail());
            }

            // Only add error map if it's not empty
            Map<String, Object> bag = new HashMap<>();
            if (problem.getDetail() != null && !problem.getDetail().isEmpty()) {
                bag.put("detail", problem.getDetail());
            }
            if (problem.getTitle() != null && !problem.getTitle().isEmpty()) {
                bag.put("message", problem.getTitle());
            }
            if (!error.isEmpty()) {
                bag.put("error", error);
            }

            // Only include technicalErrorMessage if bag is not empty
            return bag.isEmpty() ? Map.of() : Map.of("technicalErrorMessage", bag);
        } catch (Exception e) {
            log.error("Error creating error bag", e);
            return Map.of();
        }
    }
}
