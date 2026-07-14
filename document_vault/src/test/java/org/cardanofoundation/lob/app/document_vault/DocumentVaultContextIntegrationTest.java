package org.cardanofoundation.lob.app.document_vault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.config.TestContainerConfig;

@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class DocumentVaultContextIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories("org.cardanofoundation.lob")
    @EntityScan("org.cardanofoundation.lob")
    @ComponentScan(basePackages = "org.cardanofoundation.lob")
    @Import({TestContainerConfig.class})
    public static class TestConfig {

        // support's AuditConfig/AuditDataProvider requires a Clock bean (reporting's test config does the same)
        @Bean
        public Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatedVaultTables() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name like 'document_vault_%'",
                Integer.class);
        assertEquals(4, tables);
    }
}
