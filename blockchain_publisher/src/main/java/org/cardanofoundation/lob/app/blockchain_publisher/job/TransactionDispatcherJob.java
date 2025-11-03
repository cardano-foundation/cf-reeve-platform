package org.cardanofoundation.lob.app.blockchain_publisher.job;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.BlockchainTransactionsDispatcher;

@Service("blockchain_publisher.TransactionDispatcherJob")
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.blockchain_publisher.enabled", havingValue = "true", matchIfMissing = true)
public class TransactionDispatcherJob {

    private final BlockchainTransactionsDispatcher blockchainTransactionsDispatcher;

    @PostConstruct
    public void init() {
        log.info("blockchain_publisher.TransactionDispatcherJob is enabled.");
    }

    @Scheduled(
            fixedDelayString = "${lob.blockchain_publisher.dispatcher.txs.fixed_delay:PT10S}",
            initialDelayString = "${lob.blockchain_publisher.dispatcher.txs.initial_delay:PT1M}")
    public void execute() {
        log.debug("Pooling for blockchain transactions to be send to the blockchain...");

        blockchainTransactionsDispatcher.dispatchTransactions();

        log.debug("Pooling for blockchain transactions to be send to the blockchain...done");
    }

}
