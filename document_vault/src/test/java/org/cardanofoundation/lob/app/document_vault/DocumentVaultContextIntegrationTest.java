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
import org.springframework.context.annotation.FilterType;
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
    // Task 14: document_vault now depends on keri_attestation (compile-time, for the
    // AttestationConsumptionApi port) but must keep working with that module fully "disabled" (design
    // §3.4) - this test context never sets lob.keri-attestation.enabled, so keri_attestation's own
    // KeriAttestationModuleConfig stays inert (its @ConditionalOnProperty gate never opens). Excluding
    // the package here too keeps this broad, test-only scan from reaching directly into
    // keri_attestation's plain (unconditional) @Component classes (e.g. CeremonyCleanupJob) the way a
    // properly-scoped composing application never would - it only ever picks up a module's
    // XxxModuleConfig entry point (org.cardanofoundation.lob.app.config), never the module's internals
    // directly, and lets that module's own nested conditional @ComponentScan be the sole gate.
    @ComponentScan(basePackages = "org.cardanofoundation.lob",
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "org\\.cardanofoundation\\.lob\\.app\\.keri_attestation\\..*"))
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
                        + "document, document_slot) created by V1.7_100_14__lob_service_app_document_vault_module.sql");
    }
}
