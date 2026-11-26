package org.cardanofoundation.lob.app.kafka.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.funding.domain.events.SpendingEventsPublishCommand;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = {"lob.funding.enabled", "spring.kafka.enabled"}, havingValue = "true")
public class FundingPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${lob.funding.topics.spending-events-publish-command}")
    private String spendingEventsPublishCommandTopic;

    @EventListener
    public void handleSpendingSchedulePublishCommand(SpendingEventsPublishCommand event) {
        log.info("Sending SpendingEventsPublishCommand to Kafka: {}", event);
        kafkaTemplate.send(spendingEventsPublishCommandTopic, event);
    }

}
