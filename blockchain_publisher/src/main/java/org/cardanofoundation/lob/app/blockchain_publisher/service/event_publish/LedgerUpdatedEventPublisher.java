package org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TxsLedgerUpdatedEvent.VERSION;

import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.BlockchainReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ReportStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.ReportsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TxsLedgerUpdatedEvent;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublishStatusMapper;
import org.cardanofoundation.lob.app.support.collections.Partitions;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerUpdatedEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final BlockchainPublishStatusMapper blockchainPublishStatusMapper;

    private static String BLOCKCHAIN_TYPE = "CARDANO_L1";

    @Value("${lob.blockchain_publisher.send.batch.size:100}")
    protected int dispatchBatchSize = 100;

    @Transactional
    public void sendTxLedgerUpdatedEvents(String organisationId,
                                          Set<TransactionEntity> allTxs) {
        log.info("Sending tx ledger updated event for organisation:{}, submittedTransactions:{}", organisationId, allTxs.size());

        val partitions = Partitions.partition(allTxs, dispatchBatchSize);

        for (val partition : partitions) {
            val txStatuses = partition.asSet().stream()
                    .map(txEntity -> {
                        val publishStatusM = txEntity.getL1SubmissionData().flatMap(L1SubmissionData::getPublishStatus);
                        val cardanoFinalityScoreM = txEntity.getL1SubmissionData().flatMap(L1SubmissionData::getFinalityScore);
                        val ledgerDispatchStatus = blockchainPublishStatusMapper.convert(publishStatusM, cardanoFinalityScoreM);
                        val txId = txEntity.getId();

                        val blockchainHashM = txEntity.getL1SubmissionData().flatMap(L1SubmissionData::getTransactionHash);

                        String blockchainHash = blockchainHashM.orElse(null);

                        val blockchainReceipts = Set.of(
                                new BlockchainReceipt(
                                        BLOCKCHAIN_TYPE,
                                        blockchainHash
                                )
                        );

                        return new TxStatusUpdate(txId, ledgerDispatchStatus, txEntity.getL1SubmissionData().get().getPublishStatusErrorReason().orElse(null), blockchainReceipts);
                    })
                    .collect(Collectors.toSet());

            log.info("Sending txs ledger updated event for organisation:{}, statuses:{}", organisationId, txStatuses);

            val event = TxsLedgerUpdatedEvent.builder()
                    .metadata(EventMetadata.create(VERSION))
                    .organisationId(organisationId)
                    .statusUpdates(txStatuses)
                    .build();

            applicationEventPublisher.publishEvent(event);
        }
    }

    @Transactional
    public void sendReportLedgerUpdatedEvents(String organisationId,
                                              Set<ReportEntity> reports) {
        log.info("Sending report ledger updated event for organisation:{}, reports:{}", organisationId, reports.size());

        val partitions = Partitions.partition(reports, dispatchBatchSize);
        for (val partition : partitions) {
            val reportStatuses = partition.asSet().stream()
                    .map(reportEntity -> {
                        val publishStatusM = reportEntity.getL1SubmissionData().flatMap(L1SubmissionData::getPublishStatus);
                        val cardanoFinalityScoreM = reportEntity.getL1SubmissionData().flatMap(L1SubmissionData::getFinalityScore);
                        val ledgerDispatchStatus = blockchainPublishStatusMapper.convert(publishStatusM, cardanoFinalityScoreM);
                        val reportId = reportEntity.getReportId();

                        val blockchainHashM = reportEntity.getL1SubmissionData().flatMap(L1SubmissionData::getTransactionHash);

                        String blockchainHash = blockchainHashM.orElse(null);

                        val blockchainReceipts = Set.of(
                                new BlockchainReceipt(
                                        BLOCKCHAIN_TYPE,
                                        blockchainHash
                                )
                        );

                        return new ReportStatusUpdate(reportId, ledgerDispatchStatus, reportEntity.getL1SubmissionData().get().getPublishStatusErrorReason().orElse(null), blockchainReceipts);
                    })
                    .collect(Collectors.toSet());

            log.info("Sending report ledger updated event for organisation:{}, statuses: {}", organisationId, reportStatuses);

            val event = ReportsLedgerUpdatedEvent.builder()
                    .metadata(EventMetadata.create(VERSION))
                    .organisationId(organisationId)
                    .statusUpdates(reportStatuses)
                    .build();

            applicationEventPublisher.publishEvent(event);
        }
    }

}
