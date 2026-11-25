package org.cardanofoundation.lob.app.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;

import org.mockito.invocation.Invocation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Kafka consumers bridge Kafka topics back onto the local Spring event bus. Each listener must republish
 * exactly the message it received, once.
 */
class KafkaConsumersTest {

    static Stream<Class<?>> consumers() {
        return Stream.of(
                AccountingCoreKafkaConsumer.class,
                AuthBeginPublishConsumer.class,
                BlockchainPublisherKafkaConsumer.class,
                DocumentPublishConsumer.class,
                DocumentVaultLedgerUpdateConsumer.class,
                FundingConsumer.class,
                KeriAttestationLedgerUpdateConsumer.class,
                NetSuiteConfigKafkaConsumer.class,
                NetSuiteKafkaConsumer.class,
                OrganisationKafkaConsumer.class,
                ReportingKafkaConsumer.class);
    }

    @ParameterizedTest
    @MethodSource("consumers")
    void everyListenerRepublishesItsMessageLocally(Class<?> consumerType) throws Exception {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Object consumer = newConsumer(consumerType, eventPublisher);

        List<Method> listeners = Arrays.stream(consumerType.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(KafkaListener.class))
                .toList();
        assertThat(listeners).isNotEmpty();

        for (Method listener : listeners) {
            assertThat(listener.getAnnotation(KafkaListener.class).topics())
                    .as("%s listens on a configured topic", listener)
                    .allSatisfy(topic -> assertThat(topic).startsWith("${").endsWith("}"));
            Object message = mock(listener.getParameterTypes()[0]);
            clearInvocations(eventPublisher);

            listener.invoke(consumer, message);

            List<Invocation> published = mockingDetails(eventPublisher).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("publishEvent"))
                    .toList();
            assertThat(published).as("%s republishes once", listener).hasSize(1);
            assertThat(published.get(0).getArgument(0, Object.class)).as("%s republishes its message", listener).isSameAs(message);
        }
    }

    private static Object newConsumer(Class<?> consumerType, ApplicationEventPublisher eventPublisher) throws Exception {
        Constructor<?> constructor = consumerType.getConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(type -> type == ApplicationEventPublisher.class ? eventPublisher : mock(type))
                .toArray();
        return constructor.newInstance(arguments);
    }

}
