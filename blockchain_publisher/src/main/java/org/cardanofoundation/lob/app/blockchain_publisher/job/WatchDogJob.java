package org.cardanofoundation.lob.app.blockchain_publisher.job;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.service.WatchDogService;

@Service
@Slf4j
@ConditionalOnProperty(value = "lob.blockchain_publisher.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class WatchDogJob {

    private final WatchDogService watchDogService;

    @Value("${lob.blockchain_publisher.watchdog.tx_limit_per_org_pull_size:1000}")
    private int txStatusInspectionLimitPerOrgPullSize = 1000; // limit per org in one go as in, per one job run

    @PostConstruct
    public void init() {
        log.info("WatchDogJob is enabled.");
    }

    @Scheduled(
            fixedDelayString = "${lob.blockchain_publisher.watchdog.transaction.fixed_delay:PT1M}",
            initialDelayString = "${lob.blockchain_publisher.watchdog.transaction.initial_delay:PT1M}"
    )
    public void executeTransactionStatusCheck() {
        log.info("Inspecting all organisations for on chain transaction status changes...");

        watchDogService.checkTransactionStatusForOrganisations(txStatusInspectionLimitPerOrgPullSize);
    }

    @Scheduled(
            fixedDelayString = "${lob.blockchain_publisher.watchdog.report.fixed_delay:PT1M}",
            initialDelayString = "${lob.blockchain_publisher.watchdog.report.initial_delay:PT1M}"
    )
    public void executeReportStatusCheck() {
        log.info("Inspecting all organisations for on chain report status changes...");

        watchDogService.checkReportStatusForOrganisations(txStatusInspectionLimitPerOrgPullSize);
    }

}
