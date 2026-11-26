package org.cardanofoundation.lob.app.kafka.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.AuthBeginPublishCommand;

/**
 * Bridges {@link AuthBeginPublishCommand} from keri_attestation's local bus onto Kafka.
 *
 * <p>keri_attestation has no Cardano wallet and no transaction submitter: it asks
 * blockchain_publisher to publish the CIP-170 AUTH_BEGIN transaction. In the split deployment the two
 * run in different services, so without this bridge the command is raised into a process with nobody
 * listening and the ceremony waits in AUTH_BEGIN_SUBMITTED until the cleanup sweep fails it.
 *
 * <p>Gated on keri-attestation (the PRODUCING module) plus Kafka, matching the convention the other
 * publisher-side bridges follow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = {"lob.keri-attestation.enabled", "spring.kafka.enabled"}, havingValue = "true", matchIfMissing = false)
public class KeriAttestationKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${lob.keri-attestation.topics.auth-begin-publish-command}")
    private String authBeginPublishCommandTopic;

    @EventListener
    public void handleAuthBeginPublishCommand(AuthBeginPublishCommand command) {
        log.info("Sending AuthBeginPublishCommand to Kafka for ceremony: {}", command.ceremonyId());
        kafkaTemplate.send(authBeginPublishCommandTopic, command);
    }

}
