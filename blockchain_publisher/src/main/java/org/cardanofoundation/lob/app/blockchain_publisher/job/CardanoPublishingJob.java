package org.cardanofoundation.lob.app.blockchain_publisher.job;

import java.util.List;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoDispatcher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoPublishable;

/**
 * Single scheduled job that dispatches every registered {@link CardanoPublishable} type (transactions, reports,
 * spending events, ...) to Cardano L1 through the generic {@link CardanoDispatcher}. Adding a new publishable type
 * requires no change here - the new module bean is picked up automatically.
 */
@Service("blockchain_publisher.CardanoPublishingJob")
@Slf4j
@RequiredArgsConstructor
public class CardanoPublishingJob {

    private final CardanoDispatcher cardanoDispatcher;
    private final List<CardanoPublishable<?>> publishables;

    @PostConstruct
    public void init() {
        log.info("CardanoPublishingJob enabled for {} publishable types: {}", publishables.size(),
                publishables.stream().map(CardanoPublishable::type).toList());
    }

    @Scheduled(
            fixedDelayString = "${lob.blockchain_publisher.dispatcher.fixed_delay:PT10S}",
            initialDelayString = "${lob.blockchain_publisher.dispatcher.initial_delay:PT1M}")
    public void execute() {
        log.debug("Polling for entities to dispatch to the blockchain...");

        publishables.forEach(cardanoDispatcher::dispatch);

        log.debug("Polling for entities to dispatch to the blockchain...done");
    }

}
