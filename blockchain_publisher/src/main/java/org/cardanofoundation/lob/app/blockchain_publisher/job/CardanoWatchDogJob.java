package org.cardanofoundation.lob.app.blockchain_publisher.job;

import java.util.List;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoPublishable;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoStatusWatcher;

/**
 * Single scheduled watchdog job that polls the on-chain status of every registered {@link CardanoPublishable}
 * type through the generic {@link CardanoStatusWatcher}.
 */
@Service("blockchain_publisher.CardanoWatchDogJob")
@Slf4j
@RequiredArgsConstructor
public class CardanoWatchDogJob {

    private final CardanoStatusWatcher cardanoStatusWatcher;
    private final List<CardanoPublishable<?>> publishables;

    @Value("${lob.blockchain_publisher.watchdog.limit_per_org:1000}")
    private int statusInspectionLimitPerOrg = 1000;

    @PostConstruct
    public void init() {
        log.info("CardanoWatchDogJob enabled for {} publishable types", publishables.size());
    }

    @Scheduled(
            fixedDelayString = "${lob.blockchain_publisher.watchdog.fixed_delay:PT1M}",
            initialDelayString = "${lob.blockchain_publisher.watchdog.initial_delay:PT1M}")
    public void execute() {
        log.debug("Inspecting all organisations for on chain status changes...");

        publishables.forEach(module -> cardanoStatusWatcher.checkStatuses(module, statusInspectionLimitPerOrg));
    }

}
