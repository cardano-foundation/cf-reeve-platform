package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFinalisationEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.accounting_reporting_core.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = true)
public class AccountingCoreKafkaConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    @KafkaListener(topics = "${lob.blockchain_publisher.topics.ledger-update-command}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(LedgerUpdatedEvent message) {
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.accounting_reporting_core.topics.validate-ingestion-response-event}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(ValidateIngestionResponseEvent message) {
        log.info("Received ValidateIngestionResponseEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.accounting_reporting_core.topics.transaction-batch-failed-event}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(TransactionBatchFailedEvent message) {
        log.info("Received TransactionBatchFailedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.accounting_reporting_core.topics.transaction-batch-started-event}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(TransactionBatchStartedEvent message) {
        log.info("Received TransactionBatchStartedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.accounting_reporting_core.topics.transaction-batch-chunk-event}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(TransactionBatchChunkEvent message) {
        log.info("Received TransactionBatchChunkEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.accounting_reporting_core.topics.reconcilation-failed-event}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(ReconcilationFailedEvent message) {
        log.info("Received ReconcilationFailedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.accounting_reporting_core.topics.reconcilation-started-event}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(ReconcilationStartedEvent message) {
        log.info("Received ReconcilationStartedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.accounting_reporting_core.topics.reconcilation-chunk-event}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(ReconcilationChunkEvent message) {
        log.info("Received ReconcilationChunkEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }

    @KafkaListener(topics = "${lob.accounting_reporting_core.topics.reconcilation-finalisation-event}", groupId = "${lob.accounting_reporting_core.consumer-group}")
    public void listen(ReconcilationFinalisationEvent message) {
        log.info("Received ReconcilationFinalisationEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }


}
