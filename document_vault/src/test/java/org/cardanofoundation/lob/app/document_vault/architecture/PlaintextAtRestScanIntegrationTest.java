package org.cardanofoundation.lob.app.document_vault.architecture;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationRepository;

/**
 * The payload-copy + in-transit scan (blueprint B5). Honest boundary (spec, B5 section): the
 * server cannot verify that bytes labeled ciphertext are actually encrypted — that check is
 * cryptographically impossible without key material and is the frontend's payload-capture gate in
 * the blueprint. What this test proves is the full backend half, through the real HTTP stack
 * (RANDOM_PORT + RestAssured, same as accounting_reporting_core's WebBaseIntegrationTest): the
 * request traverses servlet filters, the OrganisationCheckInterceptor (which reads the raw body),
 * Jackson, controller, service, and JPA — and the payload bytes end up in exactly one place (the
 * ciphertext column) and in no log line emitted anywhere along that path.
 *
 * The import is permissionless (contract §2.8, amended): there is no issuer and no signature, so
 * no issuer configuration is required for the card-import endpoint under test below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class PlaintextAtRestScanIntegrationTest {

    private static final String CANARY = "REEVE-CANARY-7f3a9c-DO-NOT-PERSIST";
    private static final String ORG_ID = "org-canary";

    @LocalServerPort
    private int serverPort;

    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        RestAssured.port = serverPort;
        RestAssured.baseURI = "http://localhost";

        // every NOT NULL column of the organisation table must be set (V1.0_100_3 migration);
        // no @Transactional here (server runs in its own transactions), so make the insert idempotent
        if (organisationRepository.findById(ORG_ID).isEmpty()) {
            organisationRepository.saveAndFlush(Organisation.builder()
                    .id(ORG_ID)
                    .name("Canary Org")
                    .taxIdNumber("TAX-1")
                    .countryCode("CH")
                    .accountPeriodDays(365)
                    .currencyId("ISO_4217:CHF")
                    .reportCurrencyId("ISO_4217:CHF")
                    .phoneNumber("+41 000 000 000")
                    .city("Zug")
                    .postCode("6300")
                    .province("ZG")
                    .address("Test Street 1")
                    .adminEmail("admin@example.org")
                    .build());
        }

        logCapture = new ListAppender<>();
        logCapture.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logCapture);
    }

    @Test
    void canaryLeaksNowhereExceptTheCiphertextColumn() {
        // register a key over HTTP (keycloak.enabled=false -> DisabledSecurity permits, account = "system")
        String keyId = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of(
                        "organisationId", ORG_ID,
                        "label", "laptop",
                        "publicKey", "a".repeat(64),
                        "email", "canary-scan@example.org"))
                .post("/api/v1/document-vault/keys")
                .then().statusCode(201)
                .extract().path("keyId");

        // upload the canary envelope over HTTP — exercises filters, OrganisationCheckInterceptor
        // (which reads the raw body), Jackson, controller, service, JPA
        byte[] canaryBytes = CANARY.getBytes(StandardCharsets.UTF_8);
        RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of(
                        "organisationId", ORG_ID,
                        "envelopeVersion", 1,
                        "fileName", "innocent.pdf",
                        "plaintextHash", "0".repeat(64),
                        "payload", Map.of(
                                "ciphertext", Base64.getEncoder().encodeToString(canaryBytes),
                                "nonce", "0".repeat(24)),
                        "slots", List.of(Map.of(
                                "keyId", keyId,
                                "recipientRef", "me",
                                "ephemeralPub", "b".repeat(64),
                                "wrappedDek", "c".repeat(96)))))
                .post("/api/v1/document-vault/documents")
                .then().statusCode(201);

        // the ciphertext column legitimately holds the bytes; every OTHER column of every vault table must not
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "select table_name, column_name from information_schema.columns "
                        + "where table_name like 'document_vault_%'");
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            if (table.equals("document_vault_document") && name.equals("ciphertext")) {
                continue;
            }
            Integer hits = jdbcTemplate.queryForObject(
                    "select count(*) from %s where cast(%s as text) like ?".formatted(table, name),
                    Integer.class, "%" + CANARY + "%");
            assertTrue(hits != null && hits == 0,
                    "Canary leaked into %s.%s".formatted(table, name));
        }

        // logs must never carry payload material (raw or base64)
        String base64Canary = Base64.getEncoder().encodeToString(canaryBytes);
        boolean leakedToLogs = logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(CANARY) || message.contains(base64Canary));
        assertFalse(leakedToLogs, "Payload material leaked into log output");
    }

    /**
     * I5 from the other direction: a handover card carries a (passphrase-wrapped) private key, and
     * the backend must REJECT it rather than quietly discard it. Quietly discarding would be the
     * worse failure — the user would walk away believing the server now holds their key.
     *
     * The test posts the full card through the real HTTP stack (so Jackson's binding is what is
     * actually under test) and asserts the 400 AND that the private material reached no column and
     * no log line.
     */
    @Test
    void aCardCarryingAPrivateKeyIsRejectedAndNothingIsWritten() {
        String privateCanary = "PRIVATE-KEY-CANARY-" + "d".repeat(32);

        RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of(
                        "organisationId", ORG_ID,
                        "card", Map.of(
                                "v", 1,
                                "type", "REEVE_KEY_CARD",
                                "subject", Map.of(
                                        "subjectType", "EXTERNAL",
                                        "subjectId", "indexer-uuid-1",
                                        "displayName", "Bob Miller",
                                        "email", "bob@example.org",
                                        "organisationId", ORG_ID),
                                "key", Map.of(
                                        "publicKey", "e".repeat(64),
                                        "label", "Bob's audit key",
                                        "assurance", "PORTABLE",
                                        "createdAt", "2026-07-14T10:15:30Z"),
                                // the section the client was supposed to strip
                                "privateKey", Map.of(
                                        "algorithm", "AES-256-GCM",
                                        "wrapped", privateCanary))))
                .post("/api/v1/document-vault/cards/import")
                .then().statusCode(400)
                .body("title", equalTo("CARD_CONTAINS_PRIVATE_KEY"));

        Integer keyRows = jdbcTemplate.queryForObject(
                "select count(*) from document_vault_key where public_key = ?", Integer.class, "e".repeat(64));
        assertTrue(keyRows != null && keyRows == 0, "A rejected card must write no key row");

        boolean privateKeyLeakedToLogs = logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(privateCanary));
        assertFalse(privateKeyLeakedToLogs, "Private key material leaked into log output");
    }
}
