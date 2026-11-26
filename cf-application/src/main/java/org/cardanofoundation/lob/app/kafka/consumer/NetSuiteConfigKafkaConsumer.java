package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;

/**
 * Configuration events are deliberately NOT handled by {@link NetSuiteKafkaConsumer}.
 * <p>
 * That consumer is enabled for {@code lob.netsuite.enabled || lob.csv.enabled}, so a CSV-only
 * worker would join the same consumer group, receive a configuration record, republish it locally
 * where no NetSuite handler exists, and commit the offset — silently discarding the tenant's
 * configuration. This listener is gated on the netsuite module alone and carries its own group so
 * only a pod that can actually apply the configuration ever receives it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.netsuite.enabled", "spring.kafka.enabled"}, havingValue = "true")
public class NetSuiteConfigKafkaConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Overrides the global {@code auto-offset-reset: latest}. This group is new, so on first
     * deployment it has no committed offsets; with {@code latest} it would silently skip every
     * configuration record produced before its first partition assignment — exactly the records
     * written during a rollout. Configuration events are idempotent (guarded by revision), so
     * replaying from the beginning is safe and losing one is not.
     */
    @KafkaListener(topics = "${lob.netsuite.topics.netsuite-config-upserted}",
            groupId = "${lob.netsuite.config-consumer-group}",
            properties = {"auto.offset.reset=earliest"})
    public void listen(NetSuiteConfigUpsertedEvent message) {
        log.info("Received NetSuiteConfigUpsertedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

}
