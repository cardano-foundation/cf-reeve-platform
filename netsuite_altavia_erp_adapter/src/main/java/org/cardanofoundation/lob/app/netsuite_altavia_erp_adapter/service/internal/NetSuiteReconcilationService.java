package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static java.util.Objects.requireNonNull;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError.Code.ADAPTER_ERROR;
import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.Transactions;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TxLine;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteIngestionEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.IngestionRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.Constants;
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
    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;

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

        String reconcilationRequestId = digestAsHex(UUID.randomUUID().toString());

        Either<Problem, Optional<List<String>>> netSuiteJsonE = netSuiteClient.retrieveLatestNetsuiteTransactionLines(reconcileFrom, reconcileTo);

        if (netSuiteJsonE.isLeft()) {
            log.error("Error retrieving data from NetSuite API: {}", netSuiteJsonE.getLeft().getDetail());

            Problem problem = netSuiteJsonE.getLeft();

            Map<String, Object> bag = Map.of(
                    Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID, netsuiteInstanceId,
                    Constants.NETSUITE_BAG_NETSUITE_URL, netSuiteClient.getBaseUrl(),
                    Constants.NETSUITE_BAG_TECHNICAL_ERROR_TITLE, requireNonNull(problem.getTitle()),
                    Constants.NETSUITE_BAG_TECHNICAL_ERROR_DETAIL, requireNonNull(problem.getDetail())
            );

            ReconcilationFailedEvent reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationRequestId)
                    .organisationId(organisationId)
                    .error(new FatalError(FatalError.Code.ADAPTER_ERROR, "CLIENT_ERROR", bag))
                    .build();

            applicationEventPublisher.publishEvent(reconcilationFailedEvent);
            return;
        }
        Optional<List<String>> bodyM = netSuiteJsonE.get();
        if (bodyM.isEmpty()) {
            log.warn("No data to read from NetSuite API..., bailing out!");

            Problem problem = netSuiteJsonE.getLeft();

            Map<String, Object> bag = Map.of(
                    Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID, netsuiteInstanceId,
                    Constants.NETSUITE_BAG_NETSUITE_URL, netSuiteClient.getBaseUrl(),
                    Constants.NETSUITE_BAG_TECHNICAL_ERROR_TITLE, requireNonNull(problem.getTitle()),
                    Constants.NETSUITE_BAG_TECHNICAL_ERROR_DETAIL, requireNonNull(problem.getDetail())
            );

            ReconcilationFailedEvent reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationRequestId)
                    .organisationId(organisationId)
                    .error(new FatalError(FatalError.Code.ADAPTER_ERROR, "NO_DATA", bag))
                    .build();

            applicationEventPublisher.publishEvent(reconcilationFailedEvent);
            return;
        }

        try {

            NetSuiteIngestionEntity netSuiteIngestion = new NetSuiteIngestionEntity();
            netSuiteIngestion.setId(reconcilationRequestId);
            netSuiteIngestion.setAdapterInstanceId(netsuiteInstanceId);
            netSuiteParser.addLinesToNetsuiteIngestion(bodyM, reconcilationRequestId, netSuiteIngestion, isNetSuiteInstanceDebugMode);

            NetSuiteIngestionEntity storedNetsuiteIngestion = ingestionRepository.saveAndFlush(netSuiteIngestion);

            assert storedNetsuiteIngestion.getId() != null;
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
            Map<String, Object> bag = Map.of(
                    Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID, netsuiteInstanceId,
                    Constants.NETSUITE_BAG_TECHNICAL_ERROR_MESSAGE, e.getMessage()
            );

            ReconcilationFailedEvent reconcilationFailedEvent = ReconcilationFailedEvent.builder()
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

            Optional<NetSuiteIngestionEntity> netsuiteIngestionM = ingestionRepository.findById(reconcilationId);
            if (netsuiteIngestionM.isEmpty()) {
                log.error("NetSuite ingestion not found, reconcilationId: {}", reconcilationId);

                Map<String, Object> bag = Map.of(
                        Constants.NETSUITE_BAG_ORGANISATION_ID, organisationId,
                        Constants.NETSUITE_BAG_RECONCILATION_ID, reconcilationId
                );

                ReconcilationFailedEvent reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                        .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                        .reconciliationId(reconcilationId)
                        .organisationId(organisationId)
                        .error(new FatalError(ADAPTER_ERROR, "INGESTION_NOT_FOUND", bag))
                        .build();

                applicationEventPublisher.publishEvent(reconcilationFailedEvent);
                return;
            }

            NetSuiteIngestionEntity netsuiteIngestion = netsuiteIngestionM.orElseThrow();

            Either<Problem, List<TxLine>> transactionDataSearchResultE = netSuiteParser.getAllTxLinesFromBodies(netsuiteIngestion.getIngestionBodies());

            if (transactionDataSearchResultE.isEmpty()) {
                Problem problem = transactionDataSearchResultE.getLeft();

                Map<String, Object> bag = Map.of(
                        Constants.NETSUITE_BAG_RECONCILATION_ID, reconcilationId,
                        Constants.NETSUITE_BAG_ORGANISATION_ID, organisationId,
                        Constants.NETSUITE_BAG_TECHNICAL_ERROR_TITLE, requireNonNull(problem.getTitle()),
                        Constants.NETSUITE_BAG_TECHNICAL_ERROR_DETAIL, requireNonNull(problem.getDetail())
                );
                ReconcilationFailedEvent reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                        .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                        .reconciliationId(reconcilationId)
                        .organisationId(organisationId)
                        .error(new FatalError(ADAPTER_ERROR, "TRANSACTIONS_PARSING_FAILED", bag))
                        .build();

                applicationEventPublisher.publishEvent(reconcilationFailedEvent);
                return;
            }

            List<TxLine> transactionDataSearchResult = transactionDataSearchResultE.get();
            Either<FatalError, Transactions> transactionsE = transactionConverter.convert(organisationId, reconcilationId, transactionDataSearchResult);

            if (transactionsE.isLeft()) {
                ReconcilationFailedEvent reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                        .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                        .reconciliationId(reconcilationId)
                        .organisationId(organisationId)
                        .error(transactionsE.getLeft())
                        .build();

                applicationEventPublisher.publishEvent(reconcilationFailedEvent);
                return;
            }

            Transactions transactions = transactionsE.get();
            Set<Transaction> txs = transactions.transactions();

            // sanity check, actually this should be already pre-filtered by the previious business processes (e.g. asking for transactions only for the organisation or within certain date range)
            Set<Transaction> transactionsWithExtractionParametersApplied = extractionParametersFilteringService.applyExtractionParameters(
                    txs,
                    organisationId,
                    from,
                    to
            );

            int totalTransactions = transactionsWithExtractionParametersApplied.size();

            // Update all transactions that are not in the reconciliation but in the same date range.
            Set<String> attachedTxIds = transactionsWithExtractionParametersApplied.stream()
                    .map(Transaction::getId)
                    .collect(Collectors.toSet());

            accountingCoreTransactionRepository.findByEntryDateRangeAndNotInReconciliation(organisationId,
                    from,
                    to,
                    attachedTxIds
            ).forEach(tx -> {
                tx.setReconcilation(Optional.empty());
                accountingCoreTransactionRepository.save(tx);
            });

            Partitions.partition(transactionsWithExtractionParametersApplied, sendBatchSize).forEach(txPartition -> {
                ReconcilationChunkEvent.ReconcilationChunkEventBuilder reconcilationChunkEventBuilder = ReconcilationChunkEvent.builder()
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

            Map<String, Object> bag = Map.of(
                    Constants.NETSUITE_BAG_ADAPTER_INSTANCE_ID, netsuiteInstanceId,
                    Constants.NETSUITE_BAG_TECHNICAL_ERROR_MESSAGE, e.getMessage()
            );

            ReconcilationFailedEvent reconcilationFailedEvent = ReconcilationFailedEvent.builder()
                    .metadata(EventMetadata.create(ReconcilationFailedEvent.VERSION))
                    .reconciliationId(reconcilationId)
                    .organisationId(organisationId)
                    .error(new FatalError(ADAPTER_ERROR, "EXCEPTION", bag))
                    .build();

            applicationEventPublisher.publishEvent(reconcilationFailedEvent);
        }
    }

}
