package org.cardanofoundation.lob.app.accounting_reporting_core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.ValidateIngestionResponseEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ValidateIngestionResponseRepository;

class ValidateIngestionResponseWaiterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Stands in for the shared database; each waiter below stands in for one replica. */
    private final Map<String, ValidateIngestionResponseEntity> sharedDatabase = new ConcurrentHashMap<>();

    private ValidateIngestionResponseWaiter requestingReplica;
    private ValidateIngestionResponseWaiter consumingReplica;

    @BeforeEach
    void setUp() {
        ValidateIngestionResponseRepository repository = mock(ValidateIngestionResponseRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            ValidateIngestionResponseEntity entity = invocation.getArgument(0);
            sharedDatabase.put(entity.getCorrelationId(), entity);
            return entity;
        });
        when(repository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(sharedDatabase.get(invocation.<String>getArgument(0))));
        doAnswer(invocation -> sharedDatabase.remove(invocation.<String>getArgument(0)))
                .when(repository).deleteById(anyString());

        requestingReplica = new ValidateIngestionResponseWaiter(repository, Clock.systemUTC());
        consumingReplica = new ValidateIngestionResponseWaiter(repository, Clock.systemUTC());
    }

    @Test
    void responseConsumedByAnotherReplicaReachesTheWaitingReplica() throws Exception {
        CompletableFuture<Optional<ValidateIngestionResponseEvent>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return requestingReplica.await("correlation", TIMEOUT);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        consumingReplica.complete("correlation", response("correlation", false, List.of("no connection")));

        Optional<ValidateIngestionResponseEvent> received = waiting.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(received).isPresent();
        assertThat(received.orElseThrow().isValid()).isFalse();
        assertThat(received.orElseThrow().getErrors()).containsExactly("no connection");
    }

    @Test
    void responseArrivingBeforeTheWaitStartsIsNotLost() throws Exception {
        consumingReplica.complete("correlation", response("correlation", true, List.of()));

        assertThat(requestingReplica.await("correlation", TIMEOUT)).isPresent();
    }

    @Test
    void responseIsRemovedOnceRead() throws Exception {
        consumingReplica.complete("correlation", response("correlation", true, List.of()));

        requestingReplica.await("correlation", TIMEOUT);

        assertThat(sharedDatabase).isEmpty();
    }

    @Test
    void anotherRequestsResponseIsIgnored() throws Exception {
        consumingReplica.complete("other", response("other", true, List.of()));

        assertThat(requestingReplica.await("correlation", Duration.ofMillis(50))).isEmpty();
        assertThat(sharedDatabase).containsOnlyKeys("other");
    }

    @Test
    void emptyWhenNoResponseArrivesInTime() throws Exception {
        assertThat(requestingReplica.await("correlation", Duration.ofMillis(50))).isEmpty();
    }

    private static ValidateIngestionResponseEvent response(String correlationId, boolean valid, List<String> errors) {
        return ValidateIngestionResponseEvent.builder()
                .correlationId(correlationId)
                .valid(valid)
                .errors(errors)
                .build();
    }

}
