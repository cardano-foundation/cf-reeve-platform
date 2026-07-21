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
public class DocumentVaultContextIntegrationTest {

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
        // document_vault_key, document_vault_addressbook_entry, document_vault_wrapped_record,
        // document_vault_document, document_vault_document_slot (V1.6_100_13 migration). The
        // addressbook split added document_vault_addressbook_entry as its own table rather than a
        // column on document_vault_key, so a card naming someone else's account is unrepresentable
        // rather than merely rejected (see AddressbookEntryEntity's javadoc).
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name like 'document_vault_%'",
                Integer.class);
        assertEquals(5, tables,
                "Expected the 5 document_vault_* tables (key, addressbook_entry, wrapped_record, "
                        + "document, document_slot) created by V1.6_100_13__lob_service_app_document_vault_module.sql");
    }
}
