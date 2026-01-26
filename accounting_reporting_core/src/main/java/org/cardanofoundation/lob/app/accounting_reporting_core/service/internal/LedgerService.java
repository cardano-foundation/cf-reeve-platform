package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;


import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.BlockchainReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.LedgerDispatchReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionLedgerUpdateCommand;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.collections.Partitions;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;


@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerService {

    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionConverter transactionConverter;
    private final PIIDataFilteringService piiDataFilteringService;
    private final OrganisationPublicApi organisationPublicApi;


    @Value("${ledger.dispatch.batch.size:100}")
    private int dispatchBatchSize;

    @Transactional(readOnly = true)
    public List<TransactionEntity> updateTransactionsWithNewStatuses(Map<String, TxStatusUpdate> statuses) {
        log.info("Updating dispatch status for statusMapCount: {}", statuses.size());

        Set<String> txIds = statuses.keySet();

        List<TransactionEntity> transactionEntities = accountingCoreTransactionRepository.findAllById(txIds);

        for (TransactionEntity tx : transactionEntities) {
            TxStatusUpdate txStatusUpdate = statuses.get(tx.getId());
            tx.setLedgerDispatchStatus(txStatusUpdate.getStatus());
            tx.setLedgerDispatchStatusErrorReason(txStatusUpdate.getLedgerDispatchStatusErrorReason());
            // TODO for now we only support one blockchain but this is open for adding more blockchains
            if (!txStatusUpdate.getBlockchainReceipts().isEmpty()) {
                BlockchainReceipt firstBlockchainReceipt = txStatusUpdate.getBlockchainReceipts().stream().iterator().next();

                String type = firstBlockchainReceipt.getType();
                String hash = firstBlockchainReceipt.getHash();

                tx.setLedgerDispatchReceipt(new LedgerDispatchReceipt(type, hash));
            }
        }
        return transactionEntities;

    }

    @Transactional
    public void saveAllTransactionEntities(Collection<TransactionEntity> transactionEntities) {
        accountingCoreTransactionRepository.saveAll(transactionEntities);
    }


    @Transactional(readOnly = true)
    public void dispatchPending(int limit) {
        for (Organisation organisation : organisationPublicApi.listAll()) {
            Set<TransactionEntity> dispatchTransactions = accountingCoreTransactionRepository.findDispatchableTransactions(organisation.getId(), Limit.of(limit));
            if(dispatchTransactions.isEmpty()) {
                log.debug("No pending transactions or reports to dispatch for organisationId: {}", organisation.getId());
                continue;
            }
            dispatchPendingTransactions(organisation.getId(), dispatchTransactions);
        }
    }

    @Transactional(readOnly = true)
    public void dispatchPendingTransactions(String organisationId,
                                            Set<TransactionEntity> transactions) {
        log.info("dispatchTransactionToBlockchainPublisher, total tx count: {} for org: {}", transactions.size(), organisationId);

        Set<Transaction> canonicalTxs = transactionConverter.convertFromDb(transactions);
        Set<Transaction> piiFilteredOutTransactions = piiDataFilteringService.apply(canonicalTxs);

        for (Partitions.Partition<Transaction> partition : Partitions.partition(piiFilteredOutTransactions, dispatchBatchSize)) {
            Set<Transaction> txs = partition.asSet();

            log.info("dispatchTransactionToBlockchainPublisher, txs, partitionSize: {}", txs.size());

            applicationEventPublisher.publishEvent(TransactionLedgerUpdateCommand.create(
                    EventMetadata.create(TransactionLedgerUpdateCommand.VERSION),
                    organisationId,
                    txs)
            );
        }
    }

}
