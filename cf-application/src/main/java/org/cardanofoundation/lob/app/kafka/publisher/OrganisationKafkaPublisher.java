package org.cardanofoundation.lob.app.kafka.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = {"lob.organisation.enabled", "spring.kafka.enabled"}, havingValue = "true")
public class OrganisationKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${lob.netsuite.topics.netsuite-config-upserted}")
    private String netSuiteConfigUpsertedTopic;

    /**
     * Keyed by organisationId so a tenant's configuration events stay ordered on one partition.
     * The event's toString excludes the encrypted key, so this log line is safe.
     */
    @EventListener
    public void handleNetSuiteConfigUpsertedEvent(NetSuiteConfigUpsertedEvent event) {
        log.info("Sending NetSuiteConfigUpsertedEvent to Kafka: {}", event);
        kafkaTemplate.send(netSuiteConfigUpsertedTopic, event.getOrganisationId(), event);
    }

}
