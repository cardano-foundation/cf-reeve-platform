package org.cardanofoundation.lob.app.accounting_reporting_core.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.ValidateIngestionResponseEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ValidateIngestionResponseRepository;

/**
 * Lets the replica serving a validation request wait for a response that any replica may consume.
 * The consumer group is shared, so the response is stored by correlation id for the waiting replica
 * to read, instead of completing an in-memory future that only exists on the requesting replica.
 */
@Component
@RequiredArgsConstructor
public class ValidateIngestionResponseWaiter {

    private final ValidateIngestionResponseRepository repository;
    private final Clock clock;

    @Value("${lob.accounting_reporting_core.validate-ingestion.poll-interval:PT0.1S}")
    private Duration pollInterval = Duration.ofMillis(100);

    public void complete(String correlationId, ValidateIngestionResponseEvent response) {
        repository.save(new ValidateIngestionResponseEntity(
                correlationId,
                response.isValid(),
                response.getErrors(),
                Instant.now(clock)));
    }

    /**
     * Must not be called inside a transaction: this blocks for up to {@code timeout}, and would hold
     * a pooled database connection for the whole wait.
     *
     * @return the response, or empty if none arrived in time
     */
    public Optional<ValidateIngestionResponseEvent> await(String correlationId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (true) {
            Optional<ValidateIngestionResponseEntity> response = repository.findById(correlationId);
            if (response.isPresent()) {
                repository.deleteById(correlationId);

                return response.map(ValidateIngestionResponseWaiter::toEvent);
            }

            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                return Optional.empty();
            }
            Thread.sleep(Math.min(pollInterval.toMillis(), remainingMillis));
        }
    }

    private static ValidateIngestionResponseEvent toEvent(ValidateIngestionResponseEntity entity) {
        return ValidateIngestionResponseEvent.builder()
                .correlationId(entity.getCorrelationId())
                .valid(entity.isValid())
                .errors(entity.getErrors())
                .build();
    }

}
