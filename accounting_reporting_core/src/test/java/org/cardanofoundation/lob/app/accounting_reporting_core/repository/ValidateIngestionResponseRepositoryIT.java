package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;
import static org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.ValidateIngestionResponseEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ValidateIngestionResponseEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.ValidateIngestionResponseWaiter;

/**
 * Runs the real migration and mapping on Postgres, with two waiters sharing the database as two API
 * replicas do. Not transactional, because a response must be visible once its writer has committed.
 */
@DataJpaTest(properties = "spring.flyway.enabled=true")
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = ValidateIngestionResponseRepositoryIT.Config.class)
@Transactional(propagation = NOT_SUPPORTED)
@Testcontainers
class ValidateIngestionResponseRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.3");

    @Configuration
    @EntityScan("org.cardanofoundation.lob")
    @EnableJpaRepositories(basePackageClasses = ValidateIngestionResponseRepository.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*(?<!ValidateIngestionResponseRepository)$"))
    @Import(ValidateIngestionResponseWaiter.class)
    static class Config {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private ValidateIngestionResponseRepository repository;
    @Autowired
    private ValidateIngestionResponseWaiter waiter;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void storesAndReturnsTheResponseOnce() throws Exception {
        waiter.complete("correlation", ValidateIngestionResponseEvent.builder()
                .correlationId("correlation").valid(false).errors(List.of("no connection")).build());

        var response = waiter.await("correlation", Duration.ofSeconds(1));

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().isValid()).isFalse();
        assertThat(response.orElseThrow().getErrors()).containsExactly("no connection");
        assertThat(repository.findById("correlation")).isEmpty();
    }

    @Test
    void redeliveredResponseIsIdempotent() {
        var event = ValidateIngestionResponseEvent.builder().correlationId("correlation").valid(true).errors(List.of()).build();

        waiter.complete("correlation", event);
        waiter.complete("correlation", event);

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void deletesOnlyResponsesOlderThanTheCutoff() {
        Instant now = Instant.now();
        repository.save(new ValidateIngestionResponseEntity("old", true, List.of(), now.minus(Duration.ofHours(2))));
        repository.save(new ValidateIngestionResponseEntity("recent", true, List.of(), now));

        assertThat(repository.deleteCreatedBefore(now.minus(Duration.ofHours(1)))).isEqualTo(1);

        assertThat(repository.findAll()).extracting(ValidateIngestionResponseEntity::getCorrelationId).containsExactly("recent");
    }

}
