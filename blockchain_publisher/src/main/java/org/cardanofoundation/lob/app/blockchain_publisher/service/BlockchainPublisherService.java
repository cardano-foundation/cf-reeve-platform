package org.cardanofoundation.lob.app.blockchain_publisher.service;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.Report;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionItemEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;

@Service("blockchain_publisher.blockchainPublisherService")
@RequiredArgsConstructor
@Slf4j
public class BlockchainPublisherService {

    private final TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;
    private final ReportEntityRepositoryGateway reportEntityRepositoryGateway;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;
    private final TransactionConverter transactionConverter;
    private final ReportConverter reportConverter;

    @Transactional
    public void storeTransactionForDispatchLater(String organisationId,
                                                 Set<Transaction> txs) {
        log.info("dispatchTransactionsToBlockchains..., orgId:{}", organisationId);

        Set<TransactionEntity> txEntities = txs.stream()
                .map(transactionConverter::convertToDbDetached)
                .collect(Collectors.toSet());

        // Function to aaggregate Txs
        txEntities.forEach(tx -> tx.setItems(aggregateTxItems(tx.getItems())));

        Set<TransactionEntity> storedTransactions = transactionEntityRepositoryGateway.storeOnlyNew(txEntities);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents(organisationId, storedTransactions);
    }

    // This method aggregates transaction items by their aggregated hash and sums their amounts.
    // the hash is currently derived from all the fields of the TransactionItemEntity except the amount.
    // this is to ensure that items with the same details but different amounts are treated as separate items.
    private Set<TransactionItemEntity> aggregateTxItems(Set<TransactionItemEntity> items) {
        return items.stream()
                .collect(Collectors.groupingBy(TransactionItemEntity::aggregatedHash, Collectors.toSet()))
                .values().stream()
                .map(itemSet -> {
                    TransactionItemEntity aggregatedItem = itemSet.iterator().next();
                    aggregatedItem.setAmountFcy(BigDecimal.valueOf(itemSet.stream()
                            .mapToLong(value -> value.getAmountFcy().longValue())
                            .sum()));
                    return aggregatedItem;
                })
                .collect(Collectors.toSet());
    }

    @Transactional
    public void storeReportsForDispatchLater(String organisationId,
                                             Set<Report> reports) {
        log.info("storeReportsForDispatchLater..., orgId:{}", organisationId);

        Set<ReportEntity> storedReports = reportEntityRepositoryGateway.storeOnlyNew(reports.stream()
                .map(reportConverter::convertToDbDetached)
                .collect(Collectors.toSet()));

        ledgerUpdatedEventPublisher.sendReportLedgerUpdatedEvents(organisationId, storedReports);
    }

}
