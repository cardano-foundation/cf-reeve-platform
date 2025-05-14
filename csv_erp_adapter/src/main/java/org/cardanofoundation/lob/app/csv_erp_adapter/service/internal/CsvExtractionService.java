package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError.Code.ADAPTER_ERROR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent.Status.FINISHED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent.Status.PROCESSING;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent.Status.STARTED;
import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.LocalDate;
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
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.SystemExtractionParametersFactory;
import org.cardanofoundation.lob.app.csv_erp_adapter.config.Constants;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.ExtractionData;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.TransactionLine;
import org.cardanofoundation.lob.app.support.collections.Partitions;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@Service
@Slf4j
@RequiredArgsConstructor
public class CsvExtractionService {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final Cache<String, ExtractionData> temporaryFileCache;
    private final SystemExtractionParametersFactory systemExtractionParametersFactory;
    @Qualifier("CsvTransactionConverter")
    private final TransactionConverter transactionConverter;
    @Value("${lob.csv.delimiter:;}")
    private final String delimiter = ";";
    @Value("${lob.csv.send-batch-size:100}")
    private final int sendBatchSize = 100;

    public void startNewExtraction(@NotNull String organisationId, String user, @NotNull UserExtractionParameters userExtractionParameters, byte[] file) {
        String batchId = digestAsHex(UUID.randomUUID().toString());
        Either<Problem, SystemExtractionParameters> systemExtractionParametersE = systemExtractionParametersFactory.createSystemExtractionParameters(organisationId);

        if (systemExtractionParametersE.isLeft()) {
            Map<String, Object> bag = Map.of("organisationId", organisationId);

            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION, user))
                    .batchId(batchId)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.NO_SYSTEM_PARAMETERS, bag))
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
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.EMPTY_FILE, Map.of(Constants.BATCH_ID, batchId)))
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
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.BATCH_NOT_FOUND, Map.of(Constants.BATCH_ID, batchId)))
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
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.ORGANISATION_MISMATCH, Map.of(Constants.BATCH_ID, batchId)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }
        // TODO evaluate other parameters to see if they match

        List<TransactionLine> transactionLines;
        try {
            transactionLines = parseCsv(extractionData.file());
        } catch (Exception e) {
            log.error("Error parsing CSV file", e);
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .batchId(batchId)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.CSV_PARSING_ERROR, Map.of(Constants.BATCH_ID, batchId)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }

        if (transactionLines.isEmpty()) {
            log.error("No transaction lines found in CSV file");
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .batchId(batchId)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.NO_TRANSACTION_LINES, Map.of(Constants.BATCH_ID, batchId)))
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
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, Constants.TRANSACTION_CONVERSION_ERROR, Map.of(Constants.BATCH_ID, batchId)))
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

    private List<TransactionLine> parseCsv(byte[] file) throws IOException {
        if(Objects.isNull(file)) {
            log.error("File is null");
            throw new IOException("File is null");
        }
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(file))) {
            CsvToBean<TransactionLine> csvToBean = new CsvToBeanBuilder<TransactionLine>(reader)
                    .withType(TransactionLine.class)
                    .withIgnoreLeadingWhiteSpace(true)
                    .withSeparator(delimiter.charAt(0))
                    .withIgnoreEmptyLine(true)
                    .build();
            return csvToBean.parse();
        }
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
                    .error(new FatalError(ADAPTER_ERROR, Constants.RECONCILATION_NOT_FOUND, Map.of(Constants.RECONCILATION_ID, reconcilationId)))
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
                    .error(new FatalError(ADAPTER_ERROR, Constants.ORGANISATION_MISMATCH, Map.of(Constants.RECONCILATION_ID, reconcilationId)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }

        List<TransactionLine> transactionLines;
        try {
            transactionLines = parseCsv(extractionData.file());
        } catch (IOException e) {
            log.error("Error parsing CSV file", e);
            ReconcilationFailedEvent batchFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, Constants.CSV_PARSING_ERROR, Map.of(Constants.RECONCILATION_ID, reconcilationId)))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
            return;
        }

        if (transactionLines.isEmpty()) {
            log.error("No transaction lines found in CSV file");
            ReconcilationFailedEvent batchFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, Constants.NO_TRANSACTION_LINES, Map.of(Constants.RECONCILATION_ID, reconcilationId)))
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
                    .error(new FatalError(ADAPTER_ERROR, Constants.TRANSACTION_CONVERSION_ERROR, Map.of(Constants.RECONCILATION_ID, reconcilationId)))
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
}
