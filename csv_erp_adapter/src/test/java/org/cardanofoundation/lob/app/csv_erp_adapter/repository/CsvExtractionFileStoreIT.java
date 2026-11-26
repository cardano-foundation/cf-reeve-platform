package org.cardanofoundation.lob.app.csv_erp_adapter.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;
import static org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.csv_erp_adapter.domain.ExtractionData;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.entity.CsvExtractionFileEntity;
import org.cardanofoundation.lob.app.csv_erp_adapter.service.internal.CsvExtractionFileStore;

/** Runs the real migration, mapping and row lock on Postgres; not transactional so each call commits. */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = CsvExtractionFileStoreIT.Config.class)
@Transactional(propagation = NOT_SUPPORTED)
@Testcontainers
class CsvExtractionFileStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.3");

    @Configuration
    @EntityScan(basePackageClasses = CsvExtractionFileEntity.class)
    @EnableJpaRepositories(basePackageClasses = CsvExtractionFileRepository.class)
    @Import(CsvExtractionFileStore.class)
    static class Config {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private CsvExtractionFileStore store;

    @Test
    void storesAndRemovesTheFile() {
        store.put("batch", "org", new byte[]{1, 2, 3});

        Optional<ExtractionData> taken = store.take("batch");

        assertThat(taken).isPresent();
        assertThat(taken.orElseThrow().organisationId()).isEqualTo("org");
        assertThat(taken.orElseThrow().file()).containsExactly(1, 2, 3);
        assertThat(store.take("batch")).isEmpty();
    }

    @Test
    void storesAReconciliationWithoutAFile() {
        store.put("reconciliation", "org", null);

        assertThat(store.take("reconciliation").orElseThrow().file()).isNull();
    }

    @Test
    void onlyOneOfManyConcurrentReplicasGetsTheFile() {
        store.put("contended", "org", new byte[]{1});

        List<CompletableFuture<Optional<ExtractionData>>> takes = IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> store.take("contended")))
                .toList();

        assertThat(takes.stream().map(CompletableFuture::join).filter(Optional::isPresent)).hasSize(1);
    }

}
