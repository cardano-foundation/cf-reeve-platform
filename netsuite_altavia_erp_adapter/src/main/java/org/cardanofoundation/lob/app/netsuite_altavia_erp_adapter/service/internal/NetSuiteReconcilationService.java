package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static java.util.Objects.requireNonNull;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError.Code.ADAPTER_ERROR;
import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.MoreCompress.decompress;
import static org.cardanofoundation.lob.app.support.crypto.MD5Hashing.md5;
import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteIngestionEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.IngestionRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.MoreCompress;
import org.cardanofoundation.lob.app.support.collections.Partitions;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@Slf4j
@RequiredArgsConstructor
public class NetSuiteReconcilationService {

    private final IngestionRepository ingestionRepository;
    private final NetSuiteClient netSuiteClient;
    private final TransactionConverter transactionConverter;
    private final ExtractionParametersFilteringService extractionParametersFilteringService;
    private final NetSuiteParser netSuiteParser;
    private final ApplicationEventPublisher applicationEventPublisher;

    private final int sendBatchSize;
    private final String netsuiteInstanceId;

    @Value("${lob.events.netsuite.to.core.netsuite.instance.debug.mode:true}")
    private final boolean isNetSuiteInstanceDebugMode;

    @Transactional
    public void startERPReconcilation(String organisationId,
                                      String initiator,
                                      LocalDate reconcileFrom,
                                      LocalDate reconcileTo) {
        log.info("Running reconciliation...");

        val reconcilationRequestId = digestAsHex(UUID.randomUUID().toString());

        val netSuiteJsonE = netSuiteClient.retrieveLatestNetsuiteTransactionLines(reconcileFrom, reconcileTo);

        if (netSuiteJsonE.isLeft()) {
            log.error("Error retrieving data from NetSuite API: {}", netSuiteJsonE.getLeft().getDetail());

            val problem = netSuiteJsonE.getLeft();

            val bag = Map.<String, Object>of(
                    "adapterInstanceId", netsuiteInstanceId,
                    "netsuiteUrl", netSuiteClient.getBaseUrl(),
                    "technicalErrorTitle", problem.getTitle(),
                    "technicalErrorDetail", problem.getDetail()
            );

            val reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationRequestId)
                    .organisationId(organisationId)
                    .error(new FatalError(FatalError.Code.ADAPTER_ERROR, "CLIENT_ERROR", bag))
                    .build();

            applicationEventPublisher.publishEvent(reconcilationFailedEvent);
            return;
        }
        val bodyM = netSuiteJsonE.get();
        if (bodyM.isEmpty()) {
            log.warn("No data to read from NetSuite API..., bailing out!");

            val problem = netSuiteJsonE.getLeft();

            val bag = Map.<String, Object>of(
                    "adapterInstanceId", netsuiteInstanceId,
                    "netsuiteUrl", netSuiteClient.getBaseUrl(),
                    "technicalErrorTitle", problem.getTitle(),
                    "technicalErrorDetail", problem.getDetail()
            );

            val reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationRequestId)
                    .organisationId(organisationId)
                    .error(new FatalError(FatalError.Code.ADAPTER_ERROR, "NO_DATA", bag))
                    .build();

            applicationEventPublisher.publishEvent(reconcilationFailedEvent);
            return;
        }

        try {
            val netsuiteTransactionLinesJson = bodyM.get();
            val ingestionBodyChecksum = md5(netsuiteTransactionLinesJson);
            val netSuiteIngestion = new NetSuiteIngestionEntity();
            netSuiteIngestion.setId(reconcilationRequestId);

            val compressedBody = MoreCompress.compress(netsuiteTransactionLinesJson);
            log.info("Before compression: {}, compressed: {}", netsuiteTransactionLinesJson.length(), compressedBody.length());

            netSuiteIngestion.setIngestionBody(compressedBody);
            if (isNetSuiteInstanceDebugMode) {
                netSuiteIngestion.setIngestionBodyDebug(netsuiteTransactionLinesJson);
            }
            netSuiteIngestion.setAdapterInstanceId(netsuiteInstanceId);
            netSuiteIngestion.setIngestionBodyChecksum(ingestionBodyChecksum);

            val storedNetsuiteIngestion = ingestionRepository.saveAndFlush(netSuiteIngestion);

            applicationEventPublisher.publishEvent(ReconcilationStartedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationStartedEvent.VERSION))
                    .reconciliationId(storedNetsuiteIngestion.getId())
                    .organisationId(organisationId)
                    .from(reconcileFrom)
                    .to(reconcileTo)
                    .build()
            );

            log.info("NetSuite ingestion started.");
        } catch (Exception e) {
            val bag = Map.<String, Object>of(
                    "adapterInstanceId", netsuiteInstanceId,
                    "technicalErrorMessage", e.getMessage()
            );

            val reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationRequestId)
                    .organisationId(organisationId)
                    .error(new FatalError(FatalError.Code.ADAPTER_ERROR, "EXCEPTION", bag))
                    .build();

            applicationEventPublisher.publishEvent(reconcilationFailedEvent);
        }
    }

    @Transactional
    public void continueReconcilation(String reconcilationId,
                                      String organisationId,
                                      LocalDate from,
                                      LocalDate to
    ) {
        try {
            log.info("Continue reconcilation..., reconcilationId: {}", reconcilationId);

            val netsuiteIngestionM = ingestionRepository.findById(reconcilationId);
            if (netsuiteIngestionM.isEmpty()) {
                log.error("NetSuite ingestion not found, reconcilationId: {}", reconcilationId);

                val bag = Map.<String, Object>of(
                        "organisationId", organisationId,
                        "reconcilationId", reconcilationId
                );

                val reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                        .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                        .reconciliationId(reconcilationId)
                        .organisationId(organisationId)
                        .error(new FatalError(ADAPTER_ERROR, "INGESTION_NOT_FOUND", bag))
                        .build();

                applicationEventPublisher.publishEvent(reconcilationFailedEvent);
                return;
            }

            val netsuiteIngestion = netsuiteIngestionM.orElseThrow();

            val transactionDataSearchResultE = netSuiteParser.parseSearchResults(requireNonNull(decompress(netsuiteIngestion.getIngestionBody())));

            if (transactionDataSearchResultE.isEmpty()) {
                val problem = transactionDataSearchResultE.getLeft();

                val bag = Map.<String, Object>of(
                        "reconcilationId", reconcilationId,
                        "organisationId", organisationId,
                        "technicalErrorTitle", problem.getTitle(),
                        "technicalErrorDetail", problem.getDetail()
                );
                val reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                        .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                        .reconciliationId(reconcilationId)
                        .organisationId(organisationId)
                        .error(new FatalError(ADAPTER_ERROR, "TRANSACTIONS_PARSING_FAILED", bag))
                        .build();

                applicationEventPublisher.publishEvent(reconcilationFailedEvent);
                return;
            }

            val transactionDataSearchResult = transactionDataSearchResultE.get();
            val transactionsE = transactionConverter.convert(organisationId, reconcilationId, transactionDataSearchResult);

            if (transactionsE.isLeft()) {
                val reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                        .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                        .reconciliationId(reconcilationId)
                        .organisationId(organisationId)
                        .error(transactionsE.getLeft())
                        .build();

                applicationEventPublisher.publishEvent(reconcilationFailedEvent);
                return;
            }

            val transactions = transactionsE.get();
            val txs = transactions.transactions();

            // sanity check, actually this should be already pre-filtered by the previious business processes (e.g. asking for transactions only for the organisation or within certain date range)
            val transactionsWithExtractionParametersApplied = extractionParametersFilteringService.applyExtractionParameters(
                    txs,
                    organisationId,
                    from,
                    to
            );

            val totalTransactions = transactionsWithExtractionParametersApplied.size();

            Partitions.partition(transactionsWithExtractionParametersApplied, sendBatchSize).forEach(txPartition -> {
                val reconcilationChunkEventBuilder = ReconcilationChunkEvent.builder()
                        .metadata(EventMetadata.create(ReconcilationChunkEvent.VERSION))
                        .reconciliationId(reconcilationId)
                        .organisationId(organisationId)
                        .transactions(txPartition.asSet())
                        .totalTransactionsCount(totalTransactions)
                        .from(from)
                        .to(to);

                applicationEventPublisher.publishEvent(reconcilationChunkEventBuilder.build());
            });

            log.info("NetSuite reconcilation fully completed.");
        } catch (Exception e) {
            log.error("Fatal error while processing NetSuite ingestion", e);

            val bag = Map.<String, Object>of(
                    "adapterInstanceId", netsuiteInstanceId,
                    "technicalErrorMessage", e.getMessage()
            );

            val reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, "EXCEPTION", bag))
                    .build();

            applicationEventPublisher.publishEvent(reconcilationFailedEvent);
        }
    }

}
