package org.cardanofoundation.lob.app.blockchain_publisher.job;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.BlockchainReportsDispatcher;

@Service("reportsV2DispatcherJob")
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "lob.blockchain_publisher.enabled", havingValue = "true", matchIfMissing = true)
public class ReportsDispatcherJob {

    private final BlockchainReportsDispatcher blockchainReportsDispatcher;

    @PostConstruct
    public void init() {
        log.info("blockchain_publisher.ReportsDispatcherJob is enabled.");
    }

    @Scheduled(
            fixedDelayString = "${lob.blockchain_publisher.dispatcher.report.fixed_delay:PT10S}",
            initialDelayString = "${lob.blockchain_publisher.dispatcher.report.initial_delay:PT1M}")
    public void execute() {
        log.debug("Pooling for report blockchain transactions to be send to the blockchain...");

        blockchainReportsDispatcher.dispatchReports();

        log.debug("Pooling for report blockchain transactions to be send to the blockchain...done");
    }

}
