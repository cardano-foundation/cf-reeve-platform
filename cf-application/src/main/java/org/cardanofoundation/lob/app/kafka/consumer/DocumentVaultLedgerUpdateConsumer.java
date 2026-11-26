package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;

/**
 * Carries publish results back the other way: the publisher raises {@link LedgerUpdatedEvent} after
 * submitting, and document_vault's {@code DocumentLedgerUpdateHandler} - which runs on the api service
 * - needs it to move a document out of MARK_DISPATCH.
 *
 * <p>Three consumers already existed for this topic (accounting_reporting_core, funding, reporting) but
 * none gated on document_vault, so in the split deployment a published document's status never
 * advanced: the event was produced on `publisher` and nothing on `api` was listening for it.
 *
 * <p>Its own group id. Sharing one with another listener would split the topic's partitions between
 * them instead of delivering every record to each - the defect FundingConsumer and
 * AccountingCoreKafkaConsumer currently share, and one not to repeat.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.document_vault.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = false)
public class DocumentVaultLedgerUpdateConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    @KafkaListener(topics = "${lob.blockchain_publisher.topics.ledger-update-command}",
            groupId = "${lob.document_vault.ledger-update-consumer-group}")
    public void listen(LedgerUpdatedEvent message) {
        // DocumentLedgerUpdateHandler guards on the DOCUMENT discriminator and returns early for the
        // rest, so republishing every ledger update onto the local bus is correct, not wasteful.
        applicationEventPublisher.publishEvent(message);
    }

}
