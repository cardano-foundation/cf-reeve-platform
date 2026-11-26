package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("${spring.kafka.enabled:true} && (${lob.netsuite.enabled:true} || ${lob.csv.enabled:false})")
public class NetSuiteKafkaConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${lob.netsuite.topics.scheduled-ingestion-event}", groupId = "${lob.netsuite.consumer-group}")
    public void listen(ScheduledIngestionEvent message) {
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.netsuite.topics.validate-ingestion-event}", groupId = "${lob.netsuite.consumer-group}")
    public void listen(ValidateIngestionEvent message) {
        log.info("Received ValidateIngestionEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.netsuite.topics.transaction-batch-created-event}", groupId = "${lob.netsuite.consumer-group}")
    public void listen(TransactionBatchCreatedEvent message) {
        log.info("Received TransactionBatchCreatedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.netsuite.topics.scheduled-reconcilation-event}", groupId = "${lob.netsuite.consumer-group}")
    public void listen(ScheduledReconcilationEvent message) {
        log.info("Received ScheduledReconcilationEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.netsuite.topics.reconcilation-created-event}", groupId = "${lob.netsuite.consumer-group}")
    public void listen(ReconcilationCreatedEvent message) {
        log.info("Received ReconcilationCreatedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

}
