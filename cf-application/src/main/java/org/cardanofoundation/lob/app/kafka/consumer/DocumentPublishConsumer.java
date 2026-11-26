package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * Receives {@link DocumentPublishCommand} on the `publisher` service and republishes it onto the local
 * bus, where blockchain_publisher's handler stores it for dispatch.
 *
 * <p>Gated on blockchain_publisher (the CONSUMING module) plus Kafka, so the `api` service - which
 * raises this command but does not run the publisher - never also consumes it and tries to dispatch
 * without a wallet.
 *
 * <p>Its own group id, deliberately NOT shared with any other listener. Two listeners sharing a group
 * on one topic split its partitions rather than each receiving every record; that is a live defect
 * between FundingConsumer and AccountingCoreKafkaConsumer today (documented in
 * docs/keri-document-flow.md), and it is not one to reproduce here.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.blockchain_publisher.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = false)
public class DocumentPublishConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    @KafkaListener(topics = "${lob.document_vault.topics.document-publish-command}",
            groupId = "${lob.document_vault.publish-command-consumer-group}")
    public void listen(DocumentPublishCommand message) {
        log.info("Received DocumentPublishCommand from Kafka for document: {}", message.documentId());
        applicationEventPublisher.publishEvent(message);
    }

}
