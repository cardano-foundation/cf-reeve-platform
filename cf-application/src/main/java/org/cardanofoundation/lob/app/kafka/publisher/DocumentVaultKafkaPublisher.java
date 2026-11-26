package org.cardanofoundation.lob.app.kafka.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * Bridges {@link DocumentPublishCommand} from document_vault's local bus onto Kafka.
 *
 * <p>Without this the split deployment cannot publish a document at all: the vault runs on the `api`
 * service and raises the command, while the only consumer - blockchain_publisher - runs on the
 * `publisher` service, so the event was raised into a process that has nobody listening and silently
 * went nowhere. The command was simply never bridged, unlike its four siblings.
 *
 * <p>Gated on document_vault (the PRODUCING module) plus Kafka, matching the convention the other
 * publisher-side bridges follow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = {"lob.document_vault.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = false)
public class DocumentVaultKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${lob.document_vault.topics.document-publish-command}")
    private String documentPublishCommandTopic;

    @EventListener
    public void handleDocumentPublishCommand(DocumentPublishCommand command) {
        // The command carries the base64 ciphertext, so log the id only - never the event.
        log.info("Sending DocumentPublishCommand to Kafka for document: {}", command.documentId());
        kafkaTemplate.send(documentPublishCommandTopic, command);
    }

}
