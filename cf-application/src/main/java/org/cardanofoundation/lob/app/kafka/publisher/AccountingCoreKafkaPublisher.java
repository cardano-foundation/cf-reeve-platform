package org.cardanofoundation.lob.app.kafka.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionLedgerUpdateCommand;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionStatusRequestEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TxRollbackEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = {"lob.accounting_reporting_core.enabled", "spring.kafka.enabled"}, havingValue = "true")
public class AccountingCoreKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    // Netsuite Topics
    @Value("${lob.netsuite.topics.scheduled-ingestion-event}")
    private String scheduledIngestionEventTopic;
    @Value("${lob.netsuite.topics.validate-ingestion-event}")
    private String validateIngestionEventTopic;
    @Value("${lob.netsuite.topics.scheduled-reconcilation-event}")
    private String scheduledReconcilationEventTopic;
    @Value("${lob.netsuite.topics.transaction-batch-created-event}")
    private String transactionBatchCreatedEventTopic;
    @Value("${lob.netsuite.topics.reconcilation-created-event}")
    private String reconcilationCreatedEventTopic;

    // Blockchain Publisher Topics
    @Value("${lob.blockchain_publisher.topics.transaction-ledger-update-commander}")
    private String transactionLedgerUpdateCommanderTopic;
    @Value("${lob.blockchain_publisher.topics.tx-rollback-event}")
    private String txRollbackEvent;
    @Value("${lob.blockchain_publisher.topics.transaction-status-request-event}")
    private String txStatusRequestEvent;

    @EventListener
    public void handleScheduledIngestionEvent(ScheduledIngestionEvent event) {
        log.info("Sending ScheduledIngestionEvent to Kafka: {}", event);
        kafkaTemplate.send(scheduledIngestionEventTopic, event);
    }

    @EventListener
    public void handleValidationIngestionResponseEvent(ValidateIngestionEvent event) {
        log.info("Sending ScheduledIngestionEvent to Kafka: {}", event);
        kafkaTemplate.send(validateIngestionEventTopic, event);
    }

    @EventListener
    public void handleScheduledReconcilationEvent(ScheduledReconcilationEvent event) {
        log.info("Sending ScheduledReconcilationEvent to Kafka: {}", event);
        kafkaTemplate.send(scheduledReconcilationEventTopic, event);
    }

    // After commit: the adapter's chunks can reach another replica, which must be able to see the new row
    @TransactionalEventListener(fallbackExecution = true)
    public void handleReconcilationCreatedEvent(ReconcilationCreatedEvent event) {
        log.info("Sending ReconcilationCreatedEvent to Kafka: {}", event);
        kafkaTemplate.send(reconcilationCreatedEventTopic, event);
    }

    // After commit: the adapter's chunks can reach another replica, which must be able to see the new row
    @TransactionalEventListener(fallbackExecution = true)
    public void handleTransactionBatchCreatedEvent(TransactionBatchCreatedEvent event) {
        log.info("Sending TransactionBatchCreatedEvent to Kafka: {}", event);
        kafkaTemplate.send(transactionBatchCreatedEventTopic, event);
    }

    @EventListener
    public void handleTransactionLedgerUpdateCommanderEvent(TransactionLedgerUpdateCommand event) {
        log.info("Sending TransactionLedgerUpdateCommand to Kafka: {}", event);
        kafkaTemplate.send(transactionLedgerUpdateCommanderTopic, event);
    }

    @EventListener
    public void handleTxRollbackEvent(TxRollbackEvent event) {
        log.info("Sending TxRollbackEvent to Kafka: {}", event);
        kafkaTemplate.send(txRollbackEvent, event);
    }

    @EventListener
    public void handleTxStatusRequestEvent(TransactionStatusRequestEvent event) {
        log.info("Sending TransactionStatusRequestEvent to Kafka: {}", event);
        kafkaTemplate.send(txStatusRequestEvent, event);
    }

}
