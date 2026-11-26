package org.cardanofoundation.lob.app.kafka.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionalEventListener;

import org.mockito.invocation.Invocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;

/**
 * The Kafka publishers bridge local Spring events onto Kafka topics. Each handler must send the event it receives,
 * unchanged, to the topic configured for it, and no two handlers of one publisher may share a topic.
 */
class KafkaPublishersTest {

    static Stream<Class<?>> publishers() {
        return Stream.of(
                AccountingCoreKafkaPublisher.class,
                BlockchainPublisherKafkaPublisher.class,
                DocumentVaultKafkaPublisher.class,
                FundingPublisher.class,
                KeriAttestationKafkaPublisher.class,
                NetsuiteKafkaPublisher.class,
                OrganisationKafkaPublisher.class,
                ReportingKafkaPublisher.class);
    }

    @ParameterizedTest
    @MethodSource("publishers")
    void everyHandlerSendsItsEventToItsOwnConfiguredTopic(Class<?> publisherType) throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        Object publisher = publisherType.getConstructor(KafkaTemplate.class).newInstance(kafkaTemplate);
        List<String> configuredTopics = configureTopicsWithFieldNames(publisher);

        List<Method> handlers = Arrays.stream(publisherType.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(EventListener.class) || m.isAnnotationPresent(TransactionalEventListener.class))
                .toList();
        assertThat(handlers).isNotEmpty();

        List<String> usedTopics = new ArrayList<>();
        for (Method handler : handlers) {
            Object event = mock(handler.getParameterTypes()[0]);

            handler.invoke(publisher, event);

            Object[] sendArguments = lastSend(kafkaTemplate).getArguments();
            assertThat(sendArguments[sendArguments.length - 1]).as("%s sends its event unchanged", handler.getName()).isSameAs(event);
            usedTopics.add((String) sendArguments[0]);
        }

        assertThat(usedTopics).as("each handler sends to its own configured topic")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(configuredTopics);
    }

    @Test
    void netSuiteConfigUpsertedIsKeyedByOrganisationSoTenantEventsStayOrdered() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OrganisationKafkaPublisher publisher = new OrganisationKafkaPublisher(kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "netSuiteConfigUpsertedTopic", "config-upserted");
        NetSuiteConfigUpsertedEvent event = mock(NetSuiteConfigUpsertedEvent.class);
        when(event.getOrganisationId()).thenReturn("org-1");

        publisher.handleNetSuiteConfigUpsertedEvent(event);

        verify(kafkaTemplate).send("config-upserted", "org-1", event);
    }

    @Test
    void netSuiteConfigAppliedIsKeyedByOrganisationSoTenantEventsStayOrdered() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        NetsuiteKafkaPublisher publisher = new NetsuiteKafkaPublisher(kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "netSuiteConfigAppliedEventTopic", "config-applied");
        NetSuiteConfigAppliedEvent event = mock(NetSuiteConfigAppliedEvent.class);
        when(event.getOrganisationId()).thenReturn("org-1");

        publisher.handleNetSuiteConfigAppliedEvent(event);

        verify(kafkaTemplate).send("config-applied", "org-1", event);
    }

    /** Sets every {@code @Value} topic field to its own field name, so a sent topic shows which field it came from. */
    private static List<String> configureTopicsWithFieldNames(Object publisher) {
        List<String> topics = new ArrayList<>();
        for (Field field : publisher.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Value.class)) {
                ReflectionTestUtils.setField(publisher, field.getName(), field.getName());
                topics.add(field.getName());
            }
        }
        return topics;
    }

    private static Invocation lastSend(KafkaTemplate<String, Object> kafkaTemplate) {
        List<Invocation> sends = mockingDetails(kafkaTemplate).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .toList();
        assertThat(sends).isNotEmpty();
        return sends.get(sends.size() - 1);
    }

}
