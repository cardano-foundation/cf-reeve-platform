package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static java.util.Objects.requireNonNull;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError.Code.ADAPTER_ERROR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent.Status.*;
import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.time.LocalDate;
import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.utils.ErrorUtils;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.Transactions;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TxLine;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteIngestionEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.IngestionRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.Constants;
import org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters;
import org.cardanofoundation.lob.app.organisation.util.SystemExtractionParametersFactory;
import org.cardanofoundation.lob.app.support.collections.Partitions;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@Slf4j
@RequiredArgsConstructor
public class NetSuiteExtractionService {

    private final IngestionRepository ingestionRepository;
    private final NetSuiteClient netSuiteClient;
    private final TransactionConverter transactionConverter;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SystemExtractionParametersFactory systemExtractionParametersFactory;
    private final ExtractionParametersFilteringService extractionParametersFilteringService;
    private final NetSuiteParser netSuiteParser;

    private final int sendBatchSize;
    private final String netsuiteInstanceId;

    @Value("${lob.events.netsuite.to.core.netsuite.instance.debug.mode:true}")
    private final boolean isNetSuiteInstanceDebugMode;

    public void validateIngestion(String correlationId, String organisationId, String user) {
        log.info("Validating ingestion for organisationId: {}, user: {}, correlationId: {}", organisationId, user, correlationId);
        Either<ProblemDetail, SystemExtractionParameters> systemExtractionParametersE = systemExtractionParametersFactory.createSystemExtractionParameters(organisationId);

        List<ProblemDetail> errors = new ArrayList<>();
        if (systemExtractionParametersE.isLeft()) {
            errors.add(systemExtractionParametersE.getLeft());
        }

        Either<ProblemDetail, Void> connection = netSuiteClient.testConnection();
        if (connection.isLeft()) {
            log.error("Error testing NetSuite connection: {}", connection.getLeft().getDetail());
            errors.add(connection.getLeft());
        }

        ValidateIngestionResponseEvent build = ValidateIngestionResponseEvent.builder()
                .correlationId(correlationId)
                .errors(errors.stream().map(ProblemDetail::getDetail).toList())
                .valid(errors.isEmpty())
                .build();
        applicationEventPublisher.publishEvent(build);
    }

    public void startNewERPExtraction(String organisationId,
                                      String user,
                                      UserExtractionParameters userExtractionParameters) {
        String batchId = digestAsHex(UUID.randomUUID().toString());

        try {
            log.info("Running ingestion...");

            LocalDate fromExtractionDate = userExtractionParameters.getFrom();
            LocalDate toExtractionDate = userExtractionParameters.getTo();
            Either<ProblemDetail, Optional<List<String>>> netSuiteJsonE = netSuiteClient.retrieveLatestNetsuiteTransactionLines(fromExtractionDate, toExtractionDate);

            if (netSuiteJsonE.isLeft()) {
                log.error("Error retrieving data from NetSuite API: {}", netSuiteJsonE.getLeft().getDetail());

                ProblemDetail problem = netSuiteJsonE.getLeft();

                TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                        .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION, user))
                        .batchId(batchId)
                        .extractorType(ExtractorType.NETSUITE)
                        .organisationId(organisationId)
                        .userExtractionParameters(userExtractionParameters)
                        .error(new FatalError(ADAPTER_ERROR, "CLIENT_ERROR", ErrorUtils.getBag(problem, "CLIENT_ERROR")))
                        .build();

                applicationEventPublisher.publishEvent(batchFailedEvent);
                return;
            }

            Optional<List<String>> bodyM = netSuiteJsonE.get();
            if (bodyM.isEmpty()) {
                log.warn("No data to read from NetSuite API..., bailing out!");

                ProblemDetail problem = netSuiteJsonE.getLeft();
                TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                        .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION, user))
                        .batchId(batchId)
                        .extractorType(ExtractorType.NETSUITE)
                        .organisationId(organisationId)
                        .userExtractionParameters(userExtractionParameters)
                        .error(new FatalError(ADAPTER_ERROR, "NO_DATA", ErrorUtils.getBag(problem, "CLIENT_ERROR")))
                        .build();

                applicationEventPublisher.publishEvent(batchFailedEvent);
                return;
            }

            NetSuiteIngestionEntity storedNetsuiteIngestion = netSuiteParser.saveToDataBase(batchId, bodyM, isNetSuiteInstanceDebugMode, user);


            Either<ProblemDetail, SystemExtractionParameters> systemExtractionParametersE = systemExtractionParametersFactory.createSystemExtractionParameters(organisationId);
            if (systemExtractionParametersE.isLeft()) {
                ProblemDetail problem = systemExtractionParametersE.getLeft();

                TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                        .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION, user))
                        .batchId(batchId)
                        .extractorType(ExtractorType.NETSUITE)
                        .organisationId(organisationId)
                        .userExtractionParameters(userExtractionParameters)
                        .error(new FatalError(ADAPTER_ERROR, "NO_SYSTEM_PARAMETERS", ErrorUtils.getBag(problem, "NO_SYSTEM_PARAMETERS")))
                        .build();

                applicationEventPublisher.publishEvent(batchFailedEvent);
                return;
            }

            SystemExtractionParameters systemExtractionParameters = systemExtractionParametersE.get();

            assert storedNetsuiteIngestion.getId() != null;
            applicationEventPublisher.publishEvent(TransactionBatchStartedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchStartedEvent.VERSION, user))
                    .batchId(storedNetsuiteIngestion.getId())
                    .organisationId(userExtractionParameters.getOrganisationId())
                    .userExtractionParameters(userExtractionParameters)
                    .systemExtractionParameters(systemExtractionParameters)
                    .extractorType(ExtractorType.NETSUITE)
                    .build()
            );

            log.info("NetSuite ingestion started.");
        } catch (Exception e) {
            Map<String, Object> bag = Map.of(
                    Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID, netsuiteInstanceId,
                    Constants.NETSUITE_BAG_TECHNICAL_ERROR_MESSAGE, e.getMessage()
            );
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
            problemDetail.setTitle(Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID);
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION, user))
                    .batchId(batchId)
                    .extractorType(ExtractorType.NETSUITE)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .error(new FatalError(ADAPTER_ERROR, "EXCEPTION", ErrorUtils.getBag(problemDetail, "EXCEPTION")))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
        }
    }

    @Transactional
    public void continueERPExtraction(String batchId,
                                      String organisationId,
                                      UserExtractionParameters userExtractionParameters,
                                      SystemExtractionParameters systemExtractionParameters
    ) {
        try {
            log.info("Continuing ERP extraction..., batchId: {}, organisationId: {}", batchId, organisationId);

            Optional<NetSuiteIngestionEntity> netsuiteIngestionM = ingestionRepository.findById(batchId);
            if (netsuiteIngestionM.isEmpty()) {
                log.error("NetSuite ingestion not found, batchId: {}", batchId);

                Map<String, Object> bag = Map.of(
                        Constants.NETSUITE_BAG_BATCH_ID, batchId,
                        Constants.NETSUITE_BAG_ORGANISATION_ID, organisationId);
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, Constants.NETSUITE_BAG_ORGANISATION_ID);
                problemDetail.setTitle(Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID);
                TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                        .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                        .batchId(batchId)
                        .extractorType(ExtractorType.NETSUITE)
                        .organisationId(organisationId)
                        .userExtractionParameters(userExtractionParameters)
                        .systemExtractionParameters(Optional.of(systemExtractionParameters))
                        .error(new FatalError(ADAPTER_ERROR, "INGESTION_NOT_FOUND", ErrorUtils.getBag(problemDetail, "INGESTION_NOT_FOUND")))
                        .build();

                applicationEventPublisher.publishEvent(batchFailedEvent);
                return;
            }

            if (!userExtractionParameters.getOrganisationId().equals(systemExtractionParameters.getOrganisationId())) {
                Map<String, Object> bag = Map.of(
                        Constants.NETSUITE_BAG_BATCH_ID, batchId,
                        Constants.NETSUITE_BAG_ORGANISATION_ID, organisationId
                );
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, Constants.NETSUITE_BAG_ORGANISATION_ID);
                problemDetail.setTitle(Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID);
                TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                        .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                        .batchId(batchId)
                        .extractorType(ExtractorType.NETSUITE)
                        .organisationId(organisationId)
                        .userExtractionParameters(userExtractionParameters)
                        .systemExtractionParameters(Optional.of(systemExtractionParameters))
                        .error(new FatalError(ADAPTER_ERROR, "ORGANISATION_MISMATCH", ErrorUtils.getBag(problemDetail, "ORGANISATION_MISMATCH")))
                        .build();

                applicationEventPublisher.publishEvent(batchFailedEvent);
                return;
            }
            NetSuiteIngestionEntity netsuiteIngestion = netsuiteIngestionM.orElseThrow();

            Either<ProblemDetail, List<TxLine>> transactionDataSearchResultE = netSuiteParser.getAllTxLinesFromBodies(netsuiteIngestion.getIngestionBodies());

            if (transactionDataSearchResultE.isEmpty()) {
                ProblemDetail problem = transactionDataSearchResultE.getLeft();

                Map<String, Object> bag = Map.of(
                        Constants.NETSUITE_BAG_BATCH_ID, batchId,
                        Constants.NETSUITE_BAG_ORGANISATION_ID, organisationId,
                        Constants.NETSUITE_BAG_TECHNICAL_ERROR_TITLE, requireNonNull(problem.getTitle()),
                        Constants.NETSUITE_BAG_TECHNICAL_ERROR_DETAIL, requireNonNull(problem.getDetail())
                );
                TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                        .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                        .batchId(batchId)
                        .extractorType(ExtractorType.NETSUITE)
                        .organisationId(organisationId)
                        .userExtractionParameters(userExtractionParameters)
                        .systemExtractionParameters(Optional.of(systemExtractionParameters))
                        .error(new FatalError(ADAPTER_ERROR, "TRANSACTIONS_PARSING_FAILED", ErrorUtils.getBag(problem,"TRANSACTIONS_PARSING_FAILED")))
                        .build();

                applicationEventPublisher.publishEvent(batchFailedEvent);
                return;
            }

            List<TxLine> transactionDataSearchResult = transactionDataSearchResultE.get();
            Either<FatalError, Transactions> transactionsE = transactionConverter.convert(organisationId, batchId, transactionDataSearchResult);

            if (transactionsE.isLeft()) {
                TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                        .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                        .batchId(batchId)
                        .extractorType(ExtractorType.NETSUITE)
                        .organisationId(organisationId)
                        .userExtractionParameters(userExtractionParameters)
                        .systemExtractionParameters(Optional.of(systemExtractionParameters))
                        .error(transactionsE.getLeft())
                        .build();

                applicationEventPublisher.publishEvent(batchFailedEvent);
                return;
            }

            Transactions transactions = transactionsE.get();

            Set<Transaction> transactionsWithExtractionParametersApplied = extractionParametersFilteringService
                    .applyExtractionParameters(transactions.transactions(), userExtractionParameters, systemExtractionParameters);
            TransactionBatchChunkEvent.TransactionBatchChunkEventBuilder batchChunkEventBuilder = TransactionBatchChunkEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchChunkEvent.VERSION))
                    .batchId(netsuiteIngestion.getId())
                    .organisationId(organisationId)
                    .systemExtractionParameters(systemExtractionParameters)
                    .totalTransactionsCount(transactionsWithExtractionParametersApplied.size());
            Partitions.partition(transactionsWithExtractionParametersApplied, sendBatchSize).forEach(txPartition -> {
                assert netsuiteIngestion.getId() != null;

                batchChunkEventBuilder.transactions(txPartition.asSet());
                if (txPartition.isFirst()) {
                    batchChunkEventBuilder.status(STARTED);
                } else if (txPartition.isLast()) {
                    batchChunkEventBuilder.status(FINISHED);
                } else {
                    batchChunkEventBuilder.status(PROCESSING);
                }

                applicationEventPublisher.publishEvent(batchChunkEventBuilder.build());
            });
            if (transactionsWithExtractionParametersApplied.isEmpty()) {
                // Notifying the API component that the batch is empty
                applicationEventPublisher.publishEvent(batchChunkEventBuilder
                        .transactions(transactionsWithExtractionParametersApplied)
                        .status(FINISHED)
                        .build());
            }

            log.info("NetSuite ingestion fully completed.");
        } catch (Exception e) {
            log.error("Fatal error while processing NetSuite ingestion", e);

            Map<String, Object> bag = Map.<String, Object>of(
                    Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID, netsuiteInstanceId,
                    Constants.NETSUITE_BAG_TECHNICAL_ERROR_MESSAGE, e.getMessage()
            );
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            problemDetail.setTitle(Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID);
            TransactionBatchFailedEvent batchFailedEvent = TransactionBatchFailedEvent.builder()
                    .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION))
                    .batchId(batchId)
                    .extractorType(ExtractorType.NETSUITE)
                    .organisationId(organisationId)
                    .userExtractionParameters(userExtractionParameters)
                    .systemExtractionParameters(Optional.of(systemExtractionParameters))
                    .error(new FatalError(ADAPTER_ERROR, "EXCEPTION", ErrorUtils.getBag(problemDetail, "EXCEPTION")))
                    .build();

            applicationEventPublisher.publishEvent(batchFailedEvent);
        }
    }
}
