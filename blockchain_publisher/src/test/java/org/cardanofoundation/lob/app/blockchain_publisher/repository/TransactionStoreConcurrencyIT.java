package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;
import static org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Collectors;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;

/**
 * Replays what two publisher replicas do when they receive the same command at once, on real Postgres.
 * Not transactional, because each simulated replica has to commit for the other to see its rows.
 */
@DataJpaTest(properties = "spring.flyway.enabled=true")
@AutoConfigureTestDatabase(replace = NONE)
@ContextConfiguration(classes = TransactionStoreConcurrencyIT.Config.class)
@Transactional(propagation = NOT_SUPPORTED)
@Testcontainers
class TransactionStoreConcurrencyIT {

    private static final int REPLICAS = 6;
    private static final int ROUNDS = 5;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.3");

    @Configuration
    @EntityScan("org.cardanofoundation.lob")
    @EnableJpaRepositories(basePackageClasses = TransactionEntityRepository.class)
    @Import(TransactionEntityRepositoryGateway.class)
    static class Config {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private TransactionEntityRepositoryGateway gateway;
    @Autowired
    private TransactionEntityRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void replicasStoringTheSameTransactionsAtOnceDoNotConflict() {
        for (int round = 0; round < ROUNDS; round++) {
            List<String> ids = ids(round);
            CyclicBarrier start = new CyclicBarrier(REPLICAS);

            List<CompletableFuture<Void>> replicas = IntStream.range(0, REPLICAS)
                    .mapToObj(replica -> CompletableFuture.runAsync(() -> store(ids, start)))
                    .toList();

            replicas.forEach(CompletableFuture::join);

            assertThat(repository.findAllById(ids)).hasSize(ids.size());
        }
    }

    private void store(List<String> ids, CyclicBarrier start) {
        try {
            start.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            gateway.lockStoresForOrganisation("org");
            gateway.storeOnlyNew(ids.stream().map(TransactionStoreConcurrencyIT::transaction).collect(Collectors.toSet()));
        });
    }

    private static List<String> ids(int round) {
        // 64 characters like real ids: the column is CHAR(64), so shorter ones come back padded and stop matching
        return IntStream.range(0, 20).mapToObj(i -> "%032d%032d".formatted(round, i)).toList();
    }

    private static TransactionEntity transaction(String id) {
        TransactionEntity tx = new TransactionEntity();
        tx.setId(id);
        tx.setInternalNumber(id);
        tx.setBatchId("batch");
        tx.setOrganisation(new Organisation("org", "Org", "tax", "CH", "ISO_4217:CHF"));
        tx.setTransactionType(TransactionType.values()[0]);
        tx.setEntryDate(LocalDate.of(2025, 1, 1));
        tx.setAccountingPeriod(YearMonth.of(2025, 1));
        tx.setItems(new HashSet<>(Set.of()));
        return tx;
    }

}
