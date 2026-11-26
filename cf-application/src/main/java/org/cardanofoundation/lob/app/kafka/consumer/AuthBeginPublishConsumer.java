package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.AuthBeginPublishCommand;

/**
 * Receives {@link AuthBeginPublishCommand} on the `publisher` service and republishes it onto the
 * local bus, where blockchain_publisher's handler queues it for dispatch.
 *
 * <p>Gated on blockchain_publisher (the CONSUMING module) plus Kafka, so the `api` service - which
 * raises this command but owns no wallet - never also consumes it.
 *
 * <p>Its own group id, deliberately not shared with any other listener: two listeners sharing a group
 * on one topic split its partitions rather than each receiving every record.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.blockchain_publisher.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = false)
public class AuthBeginPublishConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    @KafkaListener(topics = "${lob.keri-attestation.topics.auth-begin-publish-command}",
            groupId = "${lob.keri-attestation.publish-command-consumer-group}")
    public void listen(AuthBeginPublishCommand message) {
        log.info("Received AuthBeginPublishCommand from Kafka for ceremony: {}", message.ceremonyId());
        applicationEventPublisher.publishEvent(message);
    }

}
