package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.reporting.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = true)
public class ReportingKafkaConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    @KafkaListener(topics = "${lob.blockchain_publisher.topics.ledger-update-command}", groupId = "${lob.reporting.consumer-group}")
    public void listen(LedgerUpdatedEvent message) {
        log.info("Received ReportsLedgerUpdatedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }
}
