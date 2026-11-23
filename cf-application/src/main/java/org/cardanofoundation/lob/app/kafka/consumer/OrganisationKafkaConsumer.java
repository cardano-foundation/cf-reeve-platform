package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.organisation.enabled", "spring.kafka.enabled"}, havingValue = "true")
public class OrganisationKafkaConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Shared consumer group on purpose: exactly one organisation pod processes each
     * acknowledgement and writes the verdict to the projection. No pod affinity is required
     * because the verdict is durable state rather than an in-memory future — any instance can
     * apply it, including one that never served the originating request.
     */
    @KafkaListener(topics = "${lob.organisation.topics.netsuite-config-applied}",
            groupId = "${lob.organisation.consumer-group}")
    public void listen(NetSuiteConfigAppliedEvent message) {
        log.info("Received NetSuiteConfigAppliedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

}
