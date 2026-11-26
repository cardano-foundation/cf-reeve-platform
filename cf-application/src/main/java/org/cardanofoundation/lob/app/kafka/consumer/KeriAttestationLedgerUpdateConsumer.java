package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;

/**
 * Carries AUTH_BEGIN publish results back to the api service: the publisher raises
 * {@link LedgerUpdatedEvent} after submitting, and keri_attestation's
 * {@code AuthBeginLedgerUpdateHandler} needs it to move the ceremony from AUTH_BEGIN_SUBMITTED to
 * AUTH_BEGIN_CONFIRMED. Without it the ceremony only ever times out.
 *
 * <p>Its own group id. Sharing one with another listener on this topic would split its partitions
 * between them instead of delivering every record to each.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.keri-attestation.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = false)
public class KeriAttestationLedgerUpdateConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    @KafkaListener(topics = "${lob.blockchain_publisher.topics.ledger-update-command}",
            groupId = "${lob.keri-attestation.ledger-update-consumer-group}")
    public void listen(LedgerUpdatedEvent message) {
        // AuthBeginLedgerUpdateHandler guards on the AUTH_BEGIN discriminator and returns early for the
        // rest, so republishing every ledger update onto the local bus is correct, not wasteful.
        applicationEventPublisher.publishEvent(message);
    }

}
