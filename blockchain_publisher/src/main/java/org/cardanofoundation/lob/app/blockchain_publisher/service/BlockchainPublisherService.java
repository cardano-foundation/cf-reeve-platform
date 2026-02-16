package org.cardanofoundation.lob.app.blockchain_publisher.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionStatusRequestEvent;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.reporting.dto.events.PublishReportEvent;

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

        Set<TransactionEntity> storedTransactions = transactionEntityRepositoryGateway.storeOnlyNew(txEntities);

        Set<TransactionEntity> rollbackTransaction = transactionEntityRepositoryGateway.updateErrorRollbackTransactions(txEntities);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents(organisationId, storedTransactions);
        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents(organisationId, rollbackTransaction);
    }


    public void storeReportsForDispatchLater(PublishReportEvent event) {
        ReportEntity reportEntity = reportConverter.convertToDbDetached(event);
        reportEntityRepositoryGateway.storeReportV2IfNew(reportEntity);
    }

    public void handleTxStatusRequest(TransactionStatusRequestEvent event) {
        log.info("Handling TransactionStatusRequestEvent for {} transactions", event.getOrganisationTransactionIdMap().values().stream().mapToInt(List::size).sum());
        Set<String> txIds = event.getOrganisationTransactionIdMap().values().stream().flatMap(List::stream).collect(Collectors.toSet());
        List<TransactionEntity> transactionEntities = transactionEntityRepositoryGateway.findAllById(txIds);
        Map<String, Set<TransactionEntity>> organisationIdTransactionEntityMap = transactionEntities.stream().collect(Collectors.groupingBy(
                o -> o.getOrganisation().getId(),
                Collectors.mapping(Function.identity(), Collectors.toSet())
        ));
        organisationIdTransactionEntityMap.forEach(ledgerUpdatedEventPublisher::sendTxLedgerUpdatedEvents);
    }
}
