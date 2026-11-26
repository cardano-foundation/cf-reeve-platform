package org.cardanofoundation.lob.app.kafka.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.reporting.dto.events.PublishReportEvent;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = {"lob.reporting.enabled", "spring.kafka.enabled"}, havingValue = "true")
public class ReportingKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${lob.reporting.topics.publish-report-event}")
    private String publishReportEventTopic;

    @EventListener
    public void handlePublishReportEvent(PublishReportEvent event) {
        log.info("Sending PublishReportEvent to Kafka: {}", event);
        kafkaTemplate.send(publishReportEventTopic, event);
    }
}
