package org.cardanofoundation.lob.app.kafka.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = {"lob.blockchain_publisher.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = false)
public class BlockchainPublisherKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    @Value("${lob.blockchain_publisher.topics.ledger-update-command}")
    private String ledgerUpdatedEventTopic;

    @EventListener
    public void handleTxLedgerUpdatedEvent(LedgerUpdatedEvent event) {
        log.info("Sending TxsLedgerUpdateEvent to Kafka: {}", event);
        kafkaTemplate.send(ledgerUpdatedEventTopic, event);
    }

}
