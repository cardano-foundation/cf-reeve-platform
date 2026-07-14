# `document_vault` Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend "creating + publishing" half of the passkey-gated E2EE blueprint as a new Reeve library module: key directory/addressbook (with notification e-mail) + recipient resolution, **signed key-card import** (how a new recipient gets into an addressbook), opaque wrapped-record store, encrypted-envelope upload/storage/listing, **publishing to IPFS + Cardano L1** (metadata label 1447, new `DOCUMENT` type, manager/admin only) through the existing `blockchain_publisher` pipeline, and CI enforcement that no endpoint ever handles plaintext or key secrets and that no PII (e-mails, labels, file names) ever reaches IPFS or L1. No key revocation (product decision). The **Indexer** — the verifying side that consumes what this module publishes — is a separate deployable with its own plan (contract §9); this module owes it only the card format and the import endpoint.

**Architecture:** One new Gradle library subproject `document_vault` following the `funding` module template (newest module, release 1.6.0): `resource/` controllers → `service/` → Spring Data JPA repositories → PostgreSQL via a module Flyway migration. Services return vavr `Either<ProblemDetail, T>` folded by a module-local `Responses` helper — a deliberate, documented simplification of funding's `ErrorAware`-view pattern with identical observable behavior (ProblemDetail body carrying its own HTTP status). Wired by `DocumentVaultModuleConfig` gated on `lob.document_vault.enabled`. The server stores only ciphertext and opaque blobs (blueprint invariant I5); slot identifiers are labels, never trust anchors (I6).

**Tech Stack:** Java 21, Spring Boot 3.5.8 (root-managed), Spring Security OAuth2 resource server (Keycloak JWT), Spring Data JPA + Flyway + PostgreSQL, vavr, Lombok, springdoc, JUnit 5 + Mockito + Testcontainers, ArchUnit (new, test-only).

**Spec:** `docs/superpowers/specs/2026-07-13-document-vault-module-design.md` (revised 2026-07-14) — read it first; the invariants, formats (IPFS envelope document, L1 `DOCUMENT` manifest, key card), and scope boundaries there are binding. **API contract:** `docs/documentVault.md` is the frozen frontend/backend contract — endpoint shapes, error titles, and crypto constants implemented here MUST match it exactly (it is what the frontend team builds against in parallel; its §0 is the end-to-end user journey every endpoint here serves). Key revocation is OUT of scope — do not add it. The **verifying side** (chain/IPFS verification, verdicts, trial decryption, card issuance) is out of scope *of this module* because it belongs to the Indexer, a separate deployable (contract §9) — do not build it here. Envelope fetch/serving (blueprint D2) IS in scope: Task 8's `GET /documents/{documentId}`.

## Global Constraints

- **JDK 21 mandatory**: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` before any `./gradlew` call. The Gradle daemon fails on newer JDKs (`gradle/gradle-daemon-jvm.properties` pins `toolchainVersion=21`).
- **Blueprint I5 (never weaken):** no endpoint, DTO, log line, or table may accept, return, or store plaintext content, DEKs, KEKs, PRF outputs, or unwrapped/private keys. `plaintextHash` (a SHA-256 commitment) and `wrappedDek` (AES-GCM-encrypted DEK) are explicitly allowed.
- **Blueprint I6:** `keyId`/`recipientRef` on slots are labels and indexing aids only — never authorization or decryption authority.
- **Blueprint I7:** envelopes carry `envelope_version` (int, only `1` accepted for now); wrapped records carry a `version` column.
- **PII export ban (spec B5 #3):** `DocumentPublishCommand` and every publisher-side document class must not carry e-mails, recipient labels/refs, account ids, file names, or descriptions — the IPFS document and L1 metadata are generated exclusively from those classes. Enforced by ArchUnit + an e-mail canary test.
- **No IPFS → no publishing:** the vault publish endpoint 503s unless an `IpfsAvailability` bean reports available; the publisher-side document creator fails dispatch with ERROR when `Optional<IpfsPublisher>` is empty. Never inline the envelope into L1 metadata.
- **No trusted issuer → no card import:** `lob.document_vault.card.issuers` (comma-separated `issuerId:ed25519PublicKeyHex`) is empty by default, and card import then 503s. The issuer's Ed25519 signature is the ONLY trust anchor for an imported key — never the importer's word, never the caller's role. A malformed issuer entry fails startup rather than silently disabling a trust anchor.
- **Never accept private key material, not even wrapped (I5, both directions):** a key card arriving with its `privateKey` section is rejected with `400 CARD_CONTAINS_PRIVATE_KEY` — never silently dropped, which would leave the user believing the server holds their key. Inside `KeyCardVerifier.verify` the check runs **before the issuer and signature checks**, so an unsigned card stuffed with key material is still rejected on those grounds; it never reaches a column or a log line either way.
- **Key tiers are honest (I2 as amended):** every key row carries `assurance` (`PASSKEY` | `PORTABLE`) and `origin`; the API returns them everywhere a key or recipient appears. A `PORTABLE` key never upgrades — provenance, not storage.
- **Publishing is manager-or-admin.** Every other endpoint takes any of the four platform roles. Verified precedents: funding's `publishEvent` (manager/admin), `ReportingController.publish` and `approveTransactionsPublish` (manager only). Auditor never publishes.
- **Published lock:** documents with `status != DRAFT` can never be updated, deleted, or purged by retention.
- Root Gradle build applies Spotless (import order `java, jakarta, javax, lombok, org.springframework, "", org.junit, org.cardanofoundation, #`, 2-space indent). Run `./gradlew :document_vault:spotlessApply` (plus `:support:spotlessApply` in Task 2) before every commit.
- Vault entities must NOT extend `support`'s `CommonEntity` — it is `@Audited` (Envers) and would demand `_aud` tables; vault rows are immutable and blobs must not be duplicated. Use the module-local `VaultBaseEntity` (Task 3).
- New API paths live under `/api/v1/document-vault`. Platform conventions: `@CrossOrigin(origins = "http://localhost:3000")`, springdoc `@Tag`/`@Operation`, method-level `@PreAuthorize` with `@securityConfig` role getters, RFC7807 `ProblemDetail` errors.
- Work on a feature branch: `git checkout -b feat/document-vault-module` from `release/1.6.0` before Task 1.
- Commit after every task (Conventional Commits style, as in recent history).

---

### Task 1: Module scaffold, Flyway schema, context-load integration test

**Files:**
- Modify: `settings.gradle.kts`
- Create: `document_vault/build.gradle.kts`
- Create: `document_vault/src/main/java/org/cardanofoundation/lob/app/document_vault/package-info.java`
- Create: `document_vault/src/main/java/org/cardanofoundation/lob/app/config/DocumentVaultModuleConfig.java`
- Create: `document_vault/src/main/resources/db/migration/postgresql/common/V1.6_100_13__lob_service_app_document_vault_module.sql`
- Create: `document_vault/src/test/resources/application-test.yml`
- Create: `document_vault/src/test/java/org/cardanofoundation/lob/app/document_vault/config/TestContainerConfig.java`
- Create: `document_vault/src/test/java/org/cardanofoundation/lob/app/document_vault/DocumentVaultContextIntegrationTest.java`

**Interfaces:**
- Produces: the `:document_vault` Gradle project; table schema all later tasks map entities onto; `DocumentVaultContextIntegrationTest.TestConfig` — the `@SpringBootConfiguration` every later integration test reuses via `@ContextConfiguration`.

- [ ] **Step 1: Verify the migration version slot is still free**

Run: `git grep -l "100_13" -- '*.sql' | grep -v docs || echo FREE`
Expected: `FREE`. If a file appears, bump every `100_13` in this plan to the next free number (`100_14`, …) — the global sequence must stay unique and ordered.

- [ ] **Step 2: Register the subproject**

In `settings.gradle.kts`, extend the `include(...)` list (keep alphabetic feel, order is not semantic):

```kotlin
include (
 ":support",
 ":accounting_reporting_core",
 ":blockchain_publisher",
 ":blockchain_reader",
 ":blockchain_common",
 ":document_vault",
 ":netsuite_altavia_erp_adapter",
 ":csv_erp_adapter",
 ":notification_gateway",
 ":organisation",
 ":reporting",
 ":funding"
)
```

- [ ] **Step 3: Create `document_vault/build.gradle.kts`**

Root `build.gradle.kts` supplies all plugins/BOMs; the module only declares extras (mirrors `funding/build.gradle.kts`):

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    // required for @Valid/@Size enforcement at runtime — starter-web does NOT include it since Boot 2.3
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jmolecules:jmolecules-events")
    implementation("org.jmolecules:jmolecules-ddd")
    implementation(project(":support"))
    implementation(project(":organisation"))
    // LedgerDispatchStatus, LedgerUpdatedEvent, BlockchainReceipt, IpfsAvailability (publish flow)
    implementation(project(":blockchain_common"))

    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
```

- [ ] **Step 4: Create `package-info.java`**

```java
@org.springframework.lang.NonNullApi
package org.cardanofoundation.lob.app.document_vault;
```

- [ ] **Step 5: Create the module wiring config**

`DocumentVaultModuleConfig.java` (package `org.cardanofoundation.lob.app.config`, exactly like `FundingModuleConfig`):

```java
package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "lob.document_vault.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "org.cardanofoundation.lob.app.document_vault")
public class DocumentVaultModuleConfig {
}
```

- [ ] **Step 6: Create the Flyway migration**

`V1.6_100_13__lob_service_app_document_vault_module.sql`:

```sql
CREATE TABLE IF NOT EXISTS document_vault_key (
    key_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    organisation_id VARCHAR(255) NOT NULL,
    account_name VARCHAR(255),
    email VARCHAR(320) NOT NULL,
    credential_id VARCHAR(512),
    public_key VARCHAR(64) NOT NULL,
    label VARCHAR(255) NOT NULL,
    origin VARCHAR(20) NOT NULL,
    assurance VARCHAR(20) NOT NULL,
    external BOOLEAN NOT NULL DEFAULT FALSE,
    issuer_id VARCHAR(64),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT uq_document_vault_key_account_org_pub UNIQUE (account_id, organisation_id, public_key)
);
-- origin:    SELF_ENROLLED (passkey enrollment) | INDEXER_ISSUED (imported key card)
-- assurance: PASSKEY (private half never left the owner's device) | PORTABLE (Indexer-minted, handed over
--            on a card — an operator has seen it). Provenance, not storage: it NEVER upgrades.
-- external:  true = the holder has no Reeve login (card subjectType EXTERNAL); account_id then holds the
--            card's Indexer-minted subjectId rather than a Keycloak sub.
-- The UNIQUE constraint above doubles as the idempotency key for card re-import.

CREATE INDEX IF NOT EXISTS idx_document_vault_key_account ON document_vault_key (account_id);
CREATE INDEX IF NOT EXISTS idx_document_vault_key_org ON document_vault_key (organisation_id);

CREATE TABLE IF NOT EXISTS document_vault_wrapped_record (
    account_id VARCHAR(255) NOT NULL,
    credential_id VARCHAR(512) NOT NULL,
    record TEXT NOT NULL,
    version INT NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_document_vault_wrapped_record PRIMARY KEY (account_id, credential_id)
);

CREATE TABLE IF NOT EXISTS document_vault_document (
    document_id VARCHAR(36) PRIMARY KEY,
    organisation_id VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    envelope_version INT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    plaintext_hash VARCHAR(64) NOT NULL,
    ciphertext BYTEA NOT NULL,
    payload_nonce VARCHAR(24) NOT NULL,
    file_name VARCHAR(255),
    content_type VARCHAR(255),
    description VARCHAR(1024),
    size_bytes BIGINT NOT NULL,
    created_by_account VARCHAR(255) NOT NULL,
    created_by_name VARCHAR(255),
    published_at TIMESTAMP WITHOUT TIME ZONE,
    ledger_dispatch_status VARCHAR(32) NOT NULL DEFAULT 'NOT_DISPATCHED',
    ledger_dispatch_error VARCHAR(1024),
    tx_hash VARCHAR(255),
    ipfs_cid VARCHAR(255),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_document_vault_document_org ON document_vault_document (organisation_id);
CREATE INDEX IF NOT EXISTS idx_document_vault_document_creator ON document_vault_document (created_by_account);

CREATE TABLE IF NOT EXISTS document_vault_document_slot (
    document_id VARCHAR(36) NOT NULL REFERENCES document_vault_document (document_id) ON DELETE CASCADE,
    slot_index INT NOT NULL,
    key_id VARCHAR(36) NOT NULL REFERENCES document_vault_key (key_id),
    recipient_ref VARCHAR(255) NOT NULL,
    ephemeral_pub VARCHAR(64) NOT NULL,
    wrapped_dek VARCHAR(96) NOT NULL,
    CONSTRAINT pk_document_vault_document_slot PRIMARY KEY (document_id, slot_index)
);

CREATE INDEX IF NOT EXISTS idx_document_vault_document_slot_key ON document_vault_document_slot (key_id);
```

No `_aud` tables — deliberate (see Global Constraints).

- [ ] **Step 7: Create test resources**

`document_vault/src/test/resources/application-test.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
  flyway:
    enabled: true
    locations: classpath:db/migration/postgresql/common
keycloak:
  enabled: false
lob:
  document_vault:
    enabled: true
```

`TestContainerConfig.java` (exact copy of `reporting/src/test/java/.../config/TestContainerConfig.java`, adjusted package):

```java
package org.cardanofoundation.lob.app.document_vault.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestContainerConfig {

    public static final String POSTGRES_IMAGE = "postgres:16.3";

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "testcontainers.enabled", havingValue = "true", matchIfMissing = true)
    public PostgreSQLContainer<?> postgreSQLContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
    }
}
```

- [ ] **Step 8: Write the failing context-load test**

`DocumentVaultContextIntegrationTest.java`. Its inner `TestConfig` is the canonical bootstrap reused by ALL later integration tests in this module (mirrors `ReportingServiceAggregationIntegrationTest`):

```java
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
```

- [ ] **Step 9: Run the test — verify it fails before the migration exists, passes after**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*DocumentVaultContextIntegrationTest*"`
Expected: PASS (4 tables). If you want the strict TDD red first: run once with the migration file absent → count is 0, test FAILS; then add the SQL file → PASS. Docker must be running for Testcontainers.

- [ ] **Step 10: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add settings.gradle.kts document_vault docs/superpowers
git commit -m "feat(document_vault): scaffold module with schema and wiring config"
```

---

### Task 2: `KeycloakSecurityHelper.getCurrentUserId()` (support module)

**Files:**
- Modify: `support/src/main/java/org/cardanofoundation/lob/app/support/security/KeycloakSecurityHelper.java`
- Create: `support/src/test/java/org/cardanofoundation/lob/app/support/security/KeycloakSecurityHelperTest.java` (none exists today)

**Interfaces:**
- Produces: `public String getCurrentUserId()` — returns the JWT `sub` claim (stable OIDC subject), `"system"` fallback when unauthenticated. All vault services key accounts on this value.
- Also hardens the existing `canUserAccessOrg(String)`: today it NPEs (→ HTTP 500) when a token lacks the `organisations` claim; the vault calls it on every org-scoped endpoint, so a missing claim must mean `false`.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.support.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KeycloakSecurityHelperTest {

    private final KeycloakSecurityHelper helper = new KeycloakSecurityHelper();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), claims);
        TestingAuthenticationToken auth = new TestingAuthenticationToken(jwt, null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void getCurrentUserIdReturnsSubClaim() {
        authenticateWith(Map.of("sub", "user-uuid-1", "name", "Alice"));
        assertEquals("user-uuid-1", helper.getCurrentUserId());
    }

    @Test
    void getCurrentUserIdFallsBackToSystemWhenUnauthenticated() {
        assertEquals("system", helper.getCurrentUserId());
    }

    @Test
    void canUserAccessOrgIsFalseWhenOrganisationsClaimMissing() {
        org.springframework.test.util.ReflectionTestUtils.setField(helper, "keycloakEnabled", true);
        authenticateWith(Map.of("sub", "user-uuid-1", "name", "Alice")); // no "organisations" claim
        org.junit.jupiter.api.Assertions.assertFalse(helper.canUserAccessOrg("org1"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :support:test --tests "*KeycloakSecurityHelperTest*"`
Expected: COMPILE FAILURE — `getCurrentUserId()` not defined.

- [ ] **Step 3: Implement**

Add to `KeycloakSecurityHelper` (below `getCurrentUser()`, same style):

```java
    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("sub");
        }
        return SYSTEM_USER;
    }
```

And null-guard the existing `canUserAccessOrg` — replace its `Jwt` branch body:

```java
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            List<String> organisations = jwt.getClaimAsStringList("organisations");
            return organisations != null && organisations.contains(orgId);
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :support:test --tests "*KeycloakSecurityHelperTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :support:spotlessApply
git add support
git commit -m "feat(support): expose stable user id (JWT sub) in KeycloakSecurityHelper"
```

---

### Task 3: Entities, repositories, persistence round-trip

**Files:**
- Create: `document_vault/src/main/java/org/cardanofoundation/lob/app/document_vault/domain/entity/VaultBaseEntity.java`
- Create: `.../domain/entity/VaultKeyEntity.java`
- Create: `.../domain/entity/WrappedRecordEntity.java` (+ embedded `WrappedRecordId`)
- Create: `.../domain/entity/VaultDocumentEntity.java` (+ embeddable `DocumentSlot`)
- Create: `.../domain/enums/VaultDocumentStatus.java`
- Create: `.../repository/VaultKeyRepository.java`
- Create: `.../repository/WrappedRecordRepository.java`
- Create: `.../repository/VaultDocumentRepository.java`
- Test: `document_vault/src/test/java/org/cardanofoundation/lob/app/document_vault/repository/VaultRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: schema from Task 1; `TestConfig` bootstrap from Task 1.
- Produces (exact signatures used by Tasks 4–12):
  - `VaultKeyEntity` — `String id`, `String accountId` (JWT `sub`, or an external card holder's `subjectId`), `String organisationId` (exactly one org per key entry), `String accountName`, `String email`, `String credentialId` (nullable), `String publicKey`, `String label`, `KeyOrigin origin`, `KeyAssurance assurance`, `boolean external`, `String issuerId` (nullable) — the last four are NOT NULL in the DB except `issuerId`, so **every fixture must set `origin` and `assurance`** or the flush fails
  - `VaultKeyRepository.findByAccountId(String, Pageable): Page<VaultKeyEntity>`; `findByIdAndAccountId(String, String): Optional<VaultKeyEntity>`; `existsByAccountIdAndOrganisationIdAndPublicKey(String, String, String): boolean`; `findByAccountIdAndOrganisationIdAndPublicKey(String, String, String): Optional<VaultKeyEntity>` (card-import idempotency, Task 4a); `findByOrganisationId(String): List<VaultKeyEntity>` (the addressbook filters by issuer trust before paging, so it cannot page in the query); `findByAccountIdInAndOrganisationId(Collection<String>, String): List<VaultKeyEntity>`; `findAllById` (inherited)
  - `WrappedRecordEntity` — `WrappedRecordId id (accountId, credentialId)`, `String record`, `int version`; `WrappedRecordRepository.findByIdAccountId(String, Pageable): Page<WrappedRecordEntity>`
  - `VaultDocumentEntity` — `String id`, `String organisationId`, `VaultDocumentStatus status`, `int envelopeVersion`, `String contentHash`, `String plaintextHash`, `byte[] ciphertext`, `String payloadNonce`, `String fileName`, `String contentType`, `String description`, `long sizeBytes`, `String createdByAccount`, `String createdByName`, `LocalDateTime publishedAt`, `LedgerDispatchStatus ledgerDispatchStatus` (from `blockchain_common`), `String ledgerDispatchError`, `String txHash`, `String ipfsCid`, `List<DocumentSlot> slots`
  - `VaultDocumentStatus` — enum `DRAFT | PUBLISHED`
  - `DocumentSlot` — `String keyId`, `String recipientRef`, `String ephemeralPub`, `String wrappedDek`
  - `VaultDocumentRepository.search(String organisationId, String accountId, String direction, VaultDocumentStatus status, String q, Pageable): Page<VaultDocumentEntity>` — one filtered/paged query serving the org-wide listing (direction/status/q are nullable filters)

- [ ] **Step 1: Write the failing repository round-trip test**

```java
package org.cardanofoundation.lob.app.document_vault.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordId;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class VaultRepositoryIntegrationTest {

    private static final String HEX64 = "a".repeat(64);
    private static final String HEX96 = "b".repeat(96);

    @Autowired
    private VaultKeyRepository keyRepository;
    @Autowired
    private WrappedRecordRepository recordRepository;
    @Autowired
    private VaultDocumentRepository documentRepository;

    private VaultKeyEntity key(String id, String accountId, String publicKey, String org) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(id);
        key.setAccountId(accountId);
        key.setOrganisationId(org);
        key.setAccountName("Name " + accountId);
        key.setEmail(accountId + "@example.org");
        key.setPublicKey(publicKey);
        key.setLabel("laptop");
        // origin/assurance are NOT NULL columns — a fixture without them fails on flush, not on assert
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        return key;
    }

    @Test
    void keyRoundTripAndOrgQueryScopesToOrganisation() {
        keyRepository.save(key("k1", "acc1", HEX64, "org1"));
        keyRepository.save(key("k3", "acc2", "d".repeat(64), "org2"));
        // same public key, same account, DIFFERENT org — allowed by design (one entry per org)
        keyRepository.save(key("k4", "acc1", HEX64, "org2"));

        List<VaultKeyEntity> org1Keys = keyRepository.findByOrganisationId("org1");
        assertEquals(1, org1Keys.size());
        assertEquals("k1", org1Keys.get(0).getId());
        assertEquals("acc1@example.org", org1Keys.get(0).getEmail());
        assertEquals("org1", org1Keys.get(0).getOrganisationId());
        assertEquals(KeyOrigin.SELF_ENROLLED, org1Keys.get(0).getOrigin());
        assertEquals(KeyAssurance.PASSKEY, org1Keys.get(0).getAssurance());

        assertEquals(1, keyRepository.findByAccountIdInAndOrganisationId(List.of("acc1", "acc2"), "org1").size());
        assertTrue(keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey("acc1", "org1", HEX64));
        assertTrue(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey("acc1", "org1", HEX64).isPresent());
    }

    @Test
    void wrappedRecordRoundTripsByteIdentical() {
        String blob = "{\"v\":1,\"wrappedPriv\":\"00ff\",\"unicode\":\"snowman \\u2603 emoji 🎉\"}";
        WrappedRecordEntity record = new WrappedRecordEntity();
        record.setId(new WrappedRecordId("acc1", "cred-1"));
        record.setRecord(blob);
        record.setVersion(1);
        recordRepository.save(record);

        WrappedRecordEntity reloaded = recordRepository.findById(new WrappedRecordId("acc1", "cred-1")).orElseThrow();
        assertEquals(blob, reloaded.getRecord());
        assertArrayEquals(blob.getBytes(StandardCharsets.UTF_8), reloaded.getRecord().getBytes(StandardCharsets.UTF_8));
        assertEquals(1, recordRepository.findByIdAccountId("acc1", Pageable.unpaged()).getTotalElements());
    }

    @Test
    void documentRoundTripAndSentReceivedQueries() {
        keyRepository.save(key("k1", "sender", HEX64, "org1"));
        keyRepository.save(key("k2", "recipient", "e".repeat(64), "org1"));

        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setStatus(VaultDocumentStatus.DRAFT);
        doc.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        doc.setEnvelopeVersion(1);
        doc.setContentHash(HEX64);
        doc.setPlaintextHash(HEX64);
        doc.setCiphertext(new byte[] {1, 2, 3});
        doc.setPayloadNonce("f".repeat(24));
        doc.setFileName("report.pdf");
        doc.setSizeBytes(3L);
        doc.setCreatedByAccount("sender");
        doc.setCreatedByName("Sender Name");
        doc.setSlots(List.of(
                new DocumentSlot("k1", "me", HEX64, HEX96),
                new DocumentSlot("k2", "recipient label", HEX64, HEX96)));
        documentRepository.save(doc);

        // second document, authored by "recipient", shared with "sender" (slot -> k1)
        VaultDocumentEntity doc2 = new VaultDocumentEntity();
        doc2.setId("doc2");
        doc2.setOrganisationId("org1");
        doc2.setEnvelopeVersion(1);
        doc2.setContentHash(HEX64);
        doc2.setPlaintextHash(HEX64);
        doc2.setCiphertext(new byte[] {4, 5, 6});
        doc2.setPayloadNonce("f".repeat(24));
        doc2.setFileName("invoice.pdf");
        doc2.setSizeBytes(3L);
        doc2.setCreatedByAccount("recipient");
        doc2.setCreatedByName("Recipient Name");
        doc2.setSlots(List.of(new DocumentSlot("k1", "back at you", HEX64, HEX96)));
        documentRepository.save(doc2);

        VaultDocumentEntity reloaded = documentRepository.findById("doc1").orElseThrow();
        assertArrayEquals(new byte[] {1, 2, 3}, reloaded.getCiphertext());
        assertEquals(2, reloaded.getSlots().size());
        assertEquals("k2", reloaded.getSlots().get(1).getKeyId());
        assertEquals(VaultDocumentStatus.DRAFT, reloaded.getStatus());
        assertEquals(LedgerDispatchStatus.NOT_DISPATCHED, reloaded.getLedgerDispatchStatus());

        // org-wide, unfiltered
        assertEquals(2, documentRepository.search("org1", "anyone", null, null, null, Pageable.unpaged()).getTotalElements());
        // REAL multi-page scenario exercising the explicit countQuery: 2 rows, page size 1 -> 2 pages
        Page<VaultDocumentEntity> firstPage = documentRepository.search("org1", "anyone", null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 1,
                        org.springframework.data.domain.Sort.by("createdAt").descending()));
        assertEquals(2, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(1, firstPage.getContent().size());
        // direction filters (relative to the caller)
        assertEquals(1, documentRepository.search("org1", "sender", "SENT", null, null, Pageable.unpaged()).getTotalElements());
        assertEquals(1, documentRepository.search("org1", "recipient", "RECEIVED", null, null, Pageable.unpaged()).getTotalElements());
        // sender's key k1 sits in BOTH docs' slots (doc1 self-slot + doc2 shared-back) -> 2;
        // self-slots deliberately count as RECEIVED (sender self-access, blueprint §5)
        assertEquals(2, documentRepository.search("org1", "sender", "RECEIVED", null, null, Pageable.unpaged()).getTotalElements());
        assertEquals(0, documentRepository.search("org1", "stranger", "RECEIVED", null, null, Pageable.unpaged()).getTotalElements());
        // status + q filters
        assertEquals(1, documentRepository.search("org1", "anyone", null, VaultDocumentStatus.DRAFT, "report", Pageable.unpaged()).getTotalElements());
        assertEquals(0, documentRepository.search("org1", "anyone", null, VaultDocumentStatus.PUBLISHED, null, Pageable.unpaged()).getTotalElements());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultRepositoryIntegrationTest*"`
Expected: COMPILE FAILURE (entities/repositories missing).

- [ ] **Step 3: Implement the base entity**

`VaultBaseEntity.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.entity;

import static jakarta.persistence.TemporalType.TIMESTAMP;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Temporal;
import jakarta.persistence.Transient;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Vault tables deliberately do not extend {@code CommonEntity}: that superclass is Envers-@Audited,
 * which would demand {@code _aud} copies of immutable rows including ciphertext blobs.
 */
@Setter
@Getter
@MappedSuperclass
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public abstract class VaultBaseEntity {

    @Column(name = "created_by")
    @CreatedBy
    protected String createdBy;

    @Column(name = "updated_by")
    @LastModifiedBy
    protected String updatedBy;

    @Temporal(TIMESTAMP)
    @Column(name = "created_at")
    @CreatedDate
    protected LocalDateTime createdAt;

    @Temporal(TIMESTAMP)
    @Column(name = "updated_at")
    @LastModifiedDate
    protected LocalDateTime updatedAt;

    @Transient
    protected boolean isNew = true;

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public boolean isNew() {
        return isNew;
    }
}
```

- [ ] **Step 4: Implement enum + key entity**

`VaultDocumentStatus.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.enums;

/** DRAFT documents are mutable-in-lifecycle (deletable, purgeable); PUBLISHED locks forever. */
public enum VaultDocumentStatus {
    DRAFT,
    PUBLISHED
}
```

`VaultKeyEntity.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.entity;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.VaultKeyEntity")
@Table(name = "document_vault_key")
public class VaultKeyEntity extends VaultBaseEntity implements Persistable<String> {

    @Id
    @Column(name = "key_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "account_id", nullable = false)
    private String accountId;

    /** Exactly ONE organisation per key entry (product decision). Immutable after registration. */
    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Nullable
    @Column(name = "account_name")
    private String accountName;

    /** Notification address (addressbook). Internal only — must NEVER be exported to IPFS or L1 (spec B5 #3). */
    @NotBlank
    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Nullable
    @Column(name = "credential_id")
    private String credentialId;

    /** X25519 public key, 32 bytes lowercase hex. Public material — never a secret. */
    @NotBlank
    @Column(name = "public_key", nullable = false, length = 64)
    private String publicKey;

    @NotBlank
    @Column(name = "label", nullable = false)
    private String label;

    /** How this entry got here: passkey enrollment, or an imported key card. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    private KeyOrigin origin;

    /**
     * Custody tier (blueprint I2, amended). PASSKEY = the private half never left the owner's device.
     * PORTABLE = an Indexer operator minted it and handed it over on a card, so it has existed outside
     * that device. This is PROVENANCE, not storage: wrapping a portable key under a passkey later does
     * not un-see what the operator saw, so the value NEVER upgrades. The UI must show it wherever a key
     * is chosen or a recipient picked — the honest claim differs between the two.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "assurance", nullable = false, length = 20)
    private KeyAssurance assurance;

    /** True when the holder has no Reeve login (they read published documents in the Indexer instead). */
    @Column(name = "external", nullable = false)
    private boolean external;

    /** Which card issuer vouched for this key; null for SELF_ENROLLED. */
    @Nullable
    @Column(name = "issuer_id", length = 64)
    private String issuerId;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
```

- [ ] **Step 5: Implement wrapped record entity**

`WrappedRecordId.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class WrappedRecordId implements Serializable {

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "credential_id", nullable = false)
    private String credentialId;
}
```

`WrappedRecordEntity.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.domain.Persistable;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.WrappedRecordEntity")
@Table(name = "document_vault_wrapped_record")
public class WrappedRecordEntity extends VaultBaseEntity implements Persistable<WrappedRecordId> {

    @EmbeddedId
    private WrappedRecordId id;

    /** Opaque, client-encrypted blob. The server must never parse or transform it (blueprint B2). */
    @NotBlank
    @ToString.Exclude
    @Column(name = "record", nullable = false, columnDefinition = "text")
    private String record;

    @Column(name = "version", nullable = false)
    private int version;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
```

- [ ] **Step 6: Implement document entity + slot embeddable**

`DocumentSlot.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One recipient slot of an envelope. {@code keyId}/{@code recipientRef} are labels and indexing
 * aids only — never trust anchors (blueprint I6). {@code wrappedDek} is AES-256-GCM-encrypted
 * under an ECDH-derived slot KEK; the server cannot unwrap it (blueprint I5).
 */
@Getter
@Setter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSlot {

    @Column(name = "key_id", nullable = false)
    private String keyId;

    @Column(name = "recipient_ref", nullable = false)
    private String recipientRef;

    @Column(name = "ephemeral_pub", nullable = false, length = 64)
    private String ephemeralPub;

    @Column(name = "wrapped_dek", nullable = false, length = 96)
    private String wrappedDek;
}
```

`VaultDocumentEntity.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.domain.Persistable;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.VaultDocumentEntity")
@Table(name = "document_vault_document")
public class VaultDocumentEntity extends VaultBaseEntity implements Persistable<String> {

    @Id
    @Column(name = "document_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    /** DRAFT until publish is requested; PUBLISHED locks the document forever (no edit/delete/purge). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VaultDocumentStatus status = VaultDocumentStatus.DRAFT;

    /** Envelope wire-format version (blueprint I7). Only version 1 is accepted today. */
    @Column(name = "envelope_version", nullable = false)
    private int envelopeVersion;

    /** SHA-256 of the ciphertext, computed server-side (content address). */
    @NotBlank
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** Client-supplied SHA-256 commitment over the plaintext — opaque here, consumed by the (future) verifying side. */
    @NotBlank
    @Column(name = "plaintext_hash", nullable = false, length = 64)
    private String plaintextHash;

    @ToString.Exclude
    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    @NotBlank
    @Column(name = "payload_nonce", nullable = false, length = 24)
    private String payloadNonce;

    @Nullable
    @Column(name = "file_name")
    private String fileName;

    @Nullable
    @Column(name = "content_type")
    private String contentType;

    @Nullable
    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @NotBlank
    @Column(name = "created_by_account", nullable = false)
    private String createdByAccount;

    @Nullable
    @Column(name = "created_by_name")
    private String createdByName;

    @Nullable
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** Chain progress, updated by DocumentLedgerUpdateHandler from LedgerUpdatedEvent (publisher). */
    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_dispatch_status", nullable = false)
    private LedgerDispatchStatus ledgerDispatchStatus = LedgerDispatchStatus.NOT_DISPATCHED;

    @Nullable
    @Column(name = "ledger_dispatch_error", length = 1024)
    private String ledgerDispatchError;

    @Nullable
    @Column(name = "tx_hash")
    private String txHash;

    @Nullable
    @Column(name = "ipfs_cid")
    private String ipfsCid;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "document_vault_document_slot", joinColumns = @JoinColumn(name = "document_id"))
    @OrderColumn(name = "slot_index")
    private List<DocumentSlot> slots = new ArrayList<>();

    @Override
    public boolean isNew() {
        return isNew;
    }
}
```

- [ ] **Step 7: Implement repositories**

`VaultKeyRepository.java`:

```java
package org.cardanofoundation.lob.app.document_vault.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;

public interface VaultKeyRepository extends JpaRepository<VaultKeyEntity, String> {

    Page<VaultKeyEntity> findByAccountId(String accountId, Pageable pageable);

    Optional<VaultKeyEntity> findByIdAndAccountId(String id, String accountId);

    boolean existsByAccountIdAndOrganisationIdAndPublicKey(String accountId, String organisationId, String publicKey);

    /**
     * Unpaged on purpose: the addressbook must drop keys whose issuer has been de-trusted
     * (contract §2.8.5) BEFORE paging, or pages would come back short. One row per key per org.
     */
    List<VaultKeyEntity> findByOrganisationId(String organisationId);

    List<VaultKeyEntity> findByAccountIdInAndOrganisationId(Collection<String> accountIds, String organisationId);
}
```

`WrappedRecordRepository.java`:

```java
package org.cardanofoundation.lob.app.document_vault.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordId;

public interface WrappedRecordRepository extends JpaRepository<WrappedRecordEntity, WrappedRecordId> {

    Page<WrappedRecordEntity> findByIdAccountId(String accountId, Pageable pageable);
}
```

`VaultDocumentRepository.java`:

```java
package org.cardanofoundation.lob.app.document_vault.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

public interface VaultDocumentRepository extends JpaRepository<VaultDocumentEntity, String> {

    /**
     * Org-wide listing with optional filters (all nullable) — direction is a String ('SENT'/'RECEIVED')
     * to keep the null-check portable; status is typed. Sorting/paging via Pageable.
     */
    String SEARCH_WHERE = "where d.organisationId = :organisationId "
            + "and (:status is null or d.status = :status) "
            + "and (:q is null or lower(d.fileName) like lower(concat('%', cast(:q as string), '%')) "
            + "     or lower(d.description) like lower(concat('%', cast(:q as string), '%'))) "
            + "and (:direction is null "
            + "     or (:direction = 'SENT' and d.createdByAccount = :accountId) "
            + "     or (:direction = 'RECEIVED' and exists ("
            + "         select 1 from document_vault.VaultKeyEntity k, in(d.slots) s "
            + "         where s.keyId = k.id and k.accountId = :accountId)))";

    // explicit countQuery: don't rely on Spring Data deriving a count over the exists-subquery form
    @Query(value = "select d from document_vault.VaultDocumentEntity d " + SEARCH_WHERE,
           countQuery = "select count(d) from document_vault.VaultDocumentEntity d " + SEARCH_WHERE)
    Page<VaultDocumentEntity> search(@Param("organisationId") String organisationId,
                                     @Param("accountId") String accountId,
                                     @Param("direction") String direction,
                                     @Param("status") VaultDocumentStatus status,
                                     @Param("q") String q,
                                     Pageable pageable);
}
```

(If Hibernate complains about the typed null `:status` parameter on some dialect, pass it as `String` and compare `cast` — note for the executor, not expected on Postgres/Hibernate 6.)

- [ ] **Step 8: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultRepositoryIntegrationTest*"`
Expected: PASS (3 tests).

- [ ] **Step 9: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): entities and repositories for keys, records and envelopes"
```

---

### Task 4: Key directory / addressbook — register / list mine / bindings / org recipients

**Files:**
- Create: `.../domain/request/RegisterKeyRequest.java`
- Create: `.../domain/view/VaultKeyView.java`
- Create: `.../domain/view/RecipientKeyView.java`
- Create: `.../domain/view/PagedResponse.java` (module-local mirror of funding's, without the ErrorAware mix-in)
- Create: `.../service/VaultProblems.java`
- Create: `.../service/VaultKeyService.java`
- Create: `.../resource/Responses.java`
- Create: `.../resource/VaultKeyController.java`
- Test: `.../service/VaultKeyServiceTest.java` (unit, Mockito)

Also create the two key-tier enums here (`.../domain/enums/KeyOrigin.java`, `.../domain/enums/KeyAssurance.java`) — the entity from Task 3 and the card import in Task 4a both depend on them.

**Interfaces:**
- Consumes: Task 3 entities/repos; `KeycloakSecurityHelper.getCurrentUserId()/getCurrentUser()/canUserAccessOrg(String)`; `OrganisationPublicApiIF.findByOrganisationId(String): Optional<Organisation>`.
- Produces (used by Tasks 4a, 5, 7, 8):
  - `VaultProblems` static factories: `notFound(String title, String detail)`, `forbidden(String detail)`, `conflict(String title, String detail)`, `unprocessable(String title, String detail)`, `payloadTooLarge(String detail)`, `badRequest(String title, String detail)`, `serviceUnavailable(String title, String detail)` — all returning `ProblemDetail`
  - `VaultKeyService.registerKey(RegisterKeyRequest): Either<ProblemDetail, VaultKeyView>`; `listMyKeys(Pageable): PagedResponse<VaultKeyView>`; `listRecipients(String organisationId, Pageable): Either<ProblemDetail, PagedResponse<RecipientKeyView>>` — NO revoke method and NO bindings-update method (one key ↔ one org, immutable; product decisions)
  - `VaultKeyService.toView(VaultKeyEntity, boolean issuerTrusted): VaultKeyView` and `VaultKeyService.toRecipientView(VaultKeyEntity): RecipientKeyView` — package-private statics, the SINGLE mapping reused by the resolver (Task 5) and the card importer (Task 4a) so the addressbook and the wrap-target set can never disagree about what a key exposes. `toView` takes the trust flag as a parameter rather than looking it up: the card importer has just verified the issuer, so it already knows the answer, and passing it in keeps `CardImportService` free of a `KeyCardVerifier` dependency it would otherwise need only for this. `toRecipientView` needs no flag — a de-trusted key never reaches a recipient view at all (contract §2.8.5)
  - `PagedResponse<T>{content, total, totalPages, page, size}` with two static factories — `of(Page<E>, Function<E,V>)` for the normal case where the database does the paging (Tasks 4, 6, 8), and `ofList(List<V>, Pageable)` for the one case where it cannot: the addressbook and resolve must drop keys from de-trusted issuers *before* paging, and that predicate has no SQL form, so the repository read is unpaged and the filtered list is paged in memory. Paging first would return short pages.
  - `Responses.respond(Either<ProblemDetail, T>, HttpStatus): ResponseEntity<Object>`; `Responses.respondDelete(Optional<ProblemDetail>): ResponseEntity<Object>`
  - `RecipientKeyView(String accountId, String displayName, String email, String keyId, String publicKey, String label, KeyAssurance assurance, KeyOrigin origin, String issuerId, boolean external)` (record — the addressbook entry; e-mail is org-internal contact data, never published; `assurance` MUST be shown in the picker per I2; `issuerId` answers "who vouched for this key?", the question you ask the moment something looks wrong)
  - `VaultKeyView(... KeyAssurance assurance, KeyOrigin origin, String issuerId, boolean issuerTrusted, boolean external, ...)` (record — the own-key view; `issuerTrusted` is `false` for an `INDEXER_ISSUED` key whose issuer has left the allowlist. Such a key still appears in `/keys/me` so you can decrypt what you already received, but it is no longer a wrap target anywhere — contract §2.8.5)
  - `KeyOrigin {SELF_ENROLLED, INDEXER_ISSUED}`, `KeyAssurance {PASSKEY, PORTABLE}`

- [ ] **Step 1: Write the failing service unit test**

```java
package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class VaultKeyServiceTest {

    private static final String HEX64 = "a".repeat(64);

    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private KeyCardVerifier cardVerifier;

    @InjectMocks
    private VaultKeyService service;

    @BeforeEach
    void currentUser() {
        // lenient: MockitoExtension defaults to STRICT_STUBS and early-return tests never consume this stub
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("acc1");
        // without this, Mockito's default `false` would make every key look de-trusted and the
        // addressbook would come back empty for reasons unrelated to what these tests check
        lenient().when(cardVerifier.isTrustedIssuer(any())).thenReturn(true);
    }

    private RegisterKeyRequest request(String publicKey, String org) {
        RegisterKeyRequest request = new RegisterKeyRequest();
        request.setOrganisationId(org);
        request.setLabel("laptop");
        request.setPublicKey(publicKey);
        request.setEmail("alice@example.org");
        return request;
    }

    @Test
    void registerKeyHappyPath() {
        when(securityHelper.getCurrentUser()).thenReturn("Alice");
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey("acc1", "org1", HEX64)).thenReturn(false);
        when(keyRepository.save(any(VaultKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, VaultKeyView> result = service.registerKey(request(HEX64, "org1"));

        assertTrue(result.isRight());
        assertEquals(HEX64, result.get().publicKey());
        assertEquals("alice@example.org", result.get().email());
        assertEquals("org1", result.get().organisationId());
    }

    @Test
    void registerKeyRejectsForeignOrganisation() {
        when(securityHelper.canUserAccessOrg("other-org")).thenReturn(false);

        Either<ProblemDetail, VaultKeyView> result = service.registerKey(request(HEX64, "other-org"));

        assertTrue(result.isLeft());
        assertEquals(403, result.getLeft().getStatus());
    }

    @Test
    void registerKeyRejectsDuplicatePublicKeyWithinTheSameOrg() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey("acc1", "org1", HEX64)).thenReturn(true);

        Either<ProblemDetail, VaultKeyView> result = service.registerKey(request(HEX64, "org1"));

        assertTrue(result.isLeft());
        assertEquals(409, result.getLeft().getStatus());
    }

    @Test
    void samePublicKeyIsAllowedInAnotherOrg() {
        when(securityHelper.canUserAccessOrg("org2")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org2")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey("acc1", "org2", HEX64)).thenReturn(false);
        when(keyRepository.save(any(VaultKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(service.registerKey(request(HEX64, "org2")).isRight());
    }

    @Test
    void listRecipientsRequiresMembership() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        assertTrue(service.listRecipients("org1", Pageable.unpaged()).isLeft());
    }

    @Test
    void listRecipientsExposesPagedAddressbookEntries() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(keyRepository.findByOrganisationId("org1")).thenReturn(List.of(orgKey("k1", "acc2", null)));

        var result = service.listRecipients("org1", Pageable.unpaged());

        assertTrue(result.isRight());
        assertEquals(1, result.get().total());
        assertEquals("bob@example.org", result.get().content().get(0).email());
    }

    /**
     * The containment property (contract §2.8.5): de-trust an issuer and every key it vouched for
     * leaves the addressbook. Nobody can pick it as a recipient again.
     */
    @Test
    void addressbookWithholdsKeysFromADeTrustedIssuer() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(keyRepository.findByOrganisationId("org1")).thenReturn(List.of(
                orgKey("k1", "acc2", null),                        // self-enrolled, always trusted
                orgKey("k-evil", "acc2", "compromised-issuer")));  // vouched for by a stolen key
        when(cardVerifier.isTrustedIssuer("compromised-issuer")).thenReturn(false);

        var result = service.listRecipients("org1", Pageable.unpaged());

        assertTrue(result.isRight());
        assertEquals(1, result.get().total());
        assertEquals("k1", result.get().content().get(0).keyId());
    }

    private VaultKeyEntity orgKey(String keyId, String accountId, String issuerId) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(keyId);
        key.setAccountId(accountId);
        key.setOrganisationId("org1");
        key.setAccountName("Bob");
        key.setEmail("bob@example.org");
        key.setPublicKey(HEX64);
        key.setLabel("phone");
        key.setIssuerId(issuerId);
        key.setOrigin(issuerId == null ? KeyOrigin.SELF_ENROLLED : KeyOrigin.INDEXER_ISSUED);
        key.setAssurance(issuerId == null ? KeyAssurance.PASSKEY : KeyAssurance.PORTABLE);
        return key;
    }
}
```

(`listRecipients` now reads the org's keys unpaged so it can filter by issuer trust before paging, so the test no longer needs `anyString`, `PageImpl` or `java.util.Set`.)

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultKeyServiceTest*"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement requests, views, problems**

`RegisterKeyRequest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.request;

import java.util.Set;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegisterKeyRequest extends BaseRequest {
    // organisationId comes from BaseRequest (single org — one key entry per organisation, product
    // decision) and is therefore also validated by the OrganisationCheckInterceptor.

    @NotBlank
    @Size(max = 255)
    private String label;

    @Schema(description = "X25519 public key, 32 bytes lowercase hex")
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "publicKey must be 32 bytes of lowercase hex.")
    private String publicKey;

    @Schema(description = "Notification e-mail (addressbook). Stays server-side — never exported to IPFS or L1.")
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;

    @Size(max = 512)
    private String credentialId;
}
```

`VaultKeyView.java` and `RecipientKeyView.java` (records) and `PagedResponse.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;
import java.util.Set;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;

public record VaultKeyView(String keyId,
                           String organisationId,
                           String label,
                           String publicKey,
                           String email,
                           String credentialId,
                           KeyAssurance assurance,
                           KeyOrigin origin,
                           String issuerId,
                           /** False once this key's issuer is de-trusted: still yours, still able to
                            *  decrypt what you already received, but no longer an encryption target
                            *  for anyone (contract §2.8.5). Null issuer (self-enrolled) ⇒ always true. */
                           boolean issuerTrusted,
                           boolean external,
                           LocalDateTime createdAt) {
}
```

```java
package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Paged list response — platform shape (mirror of funding's PagedResponse, minus the ErrorAware
 * mix-in: vault services return Either and controllers fold it). Used by every list endpoint.
 */
public record PagedResponse<T>(List<T> content, long total, int totalPages, int page, int size) {

    public static <E, V> PagedResponse<V> of(Page<E> page, Function<E, V> mapper) {
        return new PagedResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(), page.getTotalPages(), page.getNumber(), page.getSize());
    }
}
```

```java
package org.cardanofoundation.lob.app.document_vault.domain.view;

import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;

/**
 * Addressbook entry. E-mail is org-internal contact data — it never reaches IPFS or L1 (spec B5 #3).
 *
 * `assurance` MUST be rendered in the recipient picker (blueprint I2 as amended): encrypting to a
 * PORTABLE key is a weaker promise than encrypting to a PASSKEY key, and the sender is entitled to
 * know which one they are choosing.
 */
public record RecipientKeyView(String accountId,
                               String displayName,
                               String email,
                               String keyId,
                               String publicKey,
                               String label,
                               KeyAssurance assurance,
                               KeyOrigin origin,
                               String issuerId,
                               boolean external) {
}
```

Only keys with a **currently trusted** issuer ever reach this view (contract §2.8.5), so there is no `issuerTrusted` flag here — a de-trusted key is simply not a recipient any more. `issuerId` is carried for attribution: it answers "who vouched for this key?", which is the question you ask the moment something looks wrong.

`domain/enums/KeyOrigin.java` and `domain/enums/KeyAssurance.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.enums;

/** How a key entry entered the directory. */
public enum KeyOrigin {
    /** The holder enrolled it themselves with a passkey (blueprint §2.2). */
    SELF_ENROLLED,
    /** It arrived on an Ed25519-signed key card issued by the Indexer (contract §2.8). */
    INDEXER_ISSUED
}
```

```java
package org.cardanofoundation.lob.app.document_vault.domain.enums;

/**
 * Custody tier — blueprint I2 as amended (contract §2.8.4).
 *
 * This is PROVENANCE, not storage. A PORTABLE key can afterwards be wrapped under a passkey for
 * convenience, and it stays PORTABLE: the operator who minted it saw the private half, and no later
 * storage decision un-sees that. The tier therefore never upgrades — a holder who wants PASSKEY
 * assurance enrols a fresh key, which is cheap and honest.
 */
public enum KeyAssurance {
    /** Generated on the owner's device; the private half has never left it. */
    PASSKEY,
    /** Indexer-minted and handed over on a card; custody is "whoever holds the card". */
    PORTABLE
}
```

`VaultProblems.java`:

```java
package org.cardanofoundation.lob.app.document_vault.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class VaultProblems {

    public static final String KEY_NOT_FOUND = "KEY_NOT_FOUND";
    public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";
    public static final String RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    public static final String ORGANISATION_NOT_FOUND = "ORGANISATION_NOT_FOUND";
    public static final String USER_NOT_IN_ORGANISATION = "USER_NOT_IN_ORGANISATION";
    public static final String DUPLICATE_PUBLIC_KEY = "DUPLICATE_PUBLIC_KEY";
    public static final String RECIPIENT_KEY_MISSING = "RECIPIENT_KEY_MISSING";
    public static final String SENDER_KEY_MISSING = "SENDER_KEY_MISSING";
    public static final String SENDER_KEY_INVALID = "SENDER_KEY_INVALID";
    public static final String SLOT_KEY_INVALID = "SLOT_KEY_INVALID";
    public static final String UNSUPPORTED_ENVELOPE_VERSION = "UNSUPPORTED_ENVELOPE_VERSION";
    public static final String INVALID_PAYLOAD = "INVALID_PAYLOAD";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String TOO_MANY_SLOTS = "TOO_MANY_SLOTS";
    public static final String NOT_DOCUMENT_CREATOR = "NOT_DOCUMENT_CREATOR";
    public static final String DOCUMENT_PUBLISHED_IMMUTABLE = "DOCUMENT_PUBLISHED_IMMUTABLE";
    public static final String ALREADY_PUBLISHED = "ALREADY_PUBLISHED";
    public static final String DOCUMENT_PUBLISHING_UNAVAILABLE = "DOCUMENT_PUBLISHING_UNAVAILABLE";
    // Key cards (Task 4a)
    public static final String CARD_IMPORT_UNAVAILABLE = "CARD_IMPORT_UNAVAILABLE";
    public static final String CARD_ISSUER_UNKNOWN = "CARD_ISSUER_UNKNOWN";
    public static final String CARD_SIGNATURE_INVALID = "CARD_SIGNATURE_INVALID";
    public static final String CARD_ORG_MISMATCH = "CARD_ORG_MISMATCH";
    public static final String CARD_CONTAINS_PRIVATE_KEY = "CARD_CONTAINS_PRIVATE_KEY";
    public static final String UNSUPPORTED_CARD_VERSION = "UNSUPPORTED_CARD_VERSION";

    private VaultProblems() {
    }

    private static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    public static ProblemDetail notFound(String title, String detail) {
        return of(HttpStatus.NOT_FOUND, title, detail);
    }

    public static ProblemDetail forbidden(String detail) {
        return of(HttpStatus.FORBIDDEN, USER_NOT_IN_ORGANISATION, detail);
    }

    public static ProblemDetail conflict(String title, String detail) {
        return of(HttpStatus.CONFLICT, title, detail);
    }

    public static ProblemDetail unprocessable(String title, String detail) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, title, detail);
    }

    public static ProblemDetail payloadTooLarge(String detail) {
        return of(HttpStatus.PAYLOAD_TOO_LARGE, PAYLOAD_TOO_LARGE, detail);
    }

    public static ProblemDetail badRequest(String title, String detail) {
        return of(HttpStatus.BAD_REQUEST, title, detail);
    }

    /** Capability is switched off in this deployment (no IPFS → no publishing; no issuers → no cards). */
    public static ProblemDetail serviceUnavailable(String title, String detail) {
        return of(HttpStatus.SERVICE_UNAVAILABLE, title, detail);
    }
}
```

- [ ] **Step 4: Implement `VaultKeyService`**

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Service
@RequiredArgsConstructor
@Transactional
public class VaultKeyService {

    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;

    public Either<ProblemDetail, VaultKeyView> registerKey(RegisterKeyRequest request) {
        String accountId = securityHelper.getCurrentUserId();
        String organisationId = request.getOrganisationId();

        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }
        if (keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey(accountId, organisationId,
                request.getPublicKey())) {
            return Either.left(VaultProblems.conflict(VaultProblems.DUPLICATE_PUBLIC_KEY,
                    "This public key is already registered for the current account in this organisation."));
        }

        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(UUID.randomUUID().toString());
        key.setAccountId(accountId);
        key.setOrganisationId(organisationId);
        key.setAccountName(securityHelper.getCurrentUser());
        key.setEmail(request.getEmail());
        key.setCredentialId(request.getCredentialId());
        key.setPublicKey(request.getPublicKey());
        key.setLabel(request.getLabel());
        // Self-enrollment is the passkey path by definition: the key was born on the caller's device.
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        key.setExternal(false);

        // self-enrolled: no issuer vouched for it, so nothing can de-trust it
        return Either.right(toView(keyRepository.save(key), true));
    }

    /**
     * Own keys are NOT filtered by issuer trust — a de-trusted key still appears, flagged
     * issuerTrusted=false. You need it to decrypt documents you already received; you simply cannot
     * encrypt anything new to it (contract §2.8.5).
     */
    @Transactional(readOnly = true)
    public PagedResponse<VaultKeyView> listMyKeys(Pageable pageable) {
        return PagedResponse.of(keyRepository.findByAccountId(securityHelper.getCurrentUserId(), pageable),
                key -> toView(key, cardVerifier.isTrustedIssuer(key.getIssuerId())));
    }

    /**
     * The addressbook withholds keys whose issuer is no longer trusted (contract §2.8.5). De-trusting
     * a compromised issuer must make every key it vouched for un-addressable immediately — that is the
     * whole containment story, and it has to happen here, where recipients are chosen.
     *
     * Filtering after paging would return short pages, so the filter is applied to the org's keys and
     * the page is built from the survivors. Directories are small (one row per key per org); if one
     * ever is not, push the predicate into the query.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, PagedResponse<RecipientKeyView>> listRecipients(String organisationId,
                                                                                 Pageable pageable) {
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        List<RecipientKeyView> addressable = keyRepository.findByOrganisationId(organisationId).stream()
                .filter(key -> cardVerifier.isTrustedIssuer(key.getIssuerId()))
                .map(VaultKeyService::toRecipientView)
                .toList();
        return Either.right(PagedResponse.ofList(addressable, pageable));
    }

    /** Shared with RecipientResolutionService and CardImportService — one mapping, one place. */
    static RecipientKeyView toRecipientView(VaultKeyEntity key) {
        return new RecipientKeyView(key.getAccountId(), key.getAccountName(), key.getEmail(),
                key.getId(), key.getPublicKey(), key.getLabel(),
                key.getAssurance(), key.getOrigin(), key.getIssuerId(), key.isExternal());
    }

    /**
     * Package-private + static so CardImportService (Task 4a) reuses the exact same mapping.
     * The trust flag is passed in rather than looked up here: the card importer has just verified
     * the issuer against the allowlist, so it knows the answer, and this keeps the mapping free of
     * dependencies.
     */
    static VaultKeyView toView(VaultKeyEntity key, boolean issuerTrusted) {
        return new VaultKeyView(key.getId(), key.getOrganisationId(), key.getLabel(), key.getPublicKey(),
                key.getEmail(), key.getCredentialId(),
                key.getAssurance(), key.getOrigin(), key.getIssuerId(), issuerTrusted,
                key.isExternal(), key.getCreatedAt());
    }
}
```

Add the verifier as a dependency field of `VaultKeyService` (import `KeyCardVerifier` is unnecessary — same package):

```java
    private final KeyCardVerifier cardVerifier;
```

The repository's `findByOrganisationId` is declared unpaged in Task 3 for exactly this reason — the issuer-trust predicate has to run before paging, or pages come back short.

`PagedResponse` gains an in-memory pager for the filtered list:

```java
    /** Page an already-filtered list. Used where a predicate cannot live in the query (issuer trust). */
    public static <V> PagedResponse<V> ofList(List<V> all, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PagedResponse<>(all, all.size(), 1, 0, all.size());
        }
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        int totalPages = (int) Math.ceil((double) all.size() / pageable.getPageSize());
        return new PagedResponse<>(all.subList(from, to), all.size(), totalPages,
                pageable.getPageNumber(), pageable.getPageSize());
    }
```



- [ ] **Step 5: Run to verify service tests pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultKeyServiceTest*"`
Expected: PASS (6 tests: register happy/foreign-org/duplicate, update-unknown-404, recipients membership + addressbook).

- [ ] **Step 6: Implement `Responses` + controller**

`Responses.java` (module-local; funding's equivalent is package-private, so we keep our own — folding `Either` directly instead of the `ErrorAware` view mix-in, same observable behavior):

```java
package org.cardanofoundation.lob.app.document_vault.resource;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.vavr.control.Either;

final class Responses {

    private Responses() {
    }

    static <T> ResponseEntity<Object> respond(Either<ProblemDetail, T> result, HttpStatus successStatus) {
        return result.<ResponseEntity<Object>>fold(
                problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                body -> ResponseEntity.status(successStatus).body(body));
    }

    static ResponseEntity<Object> respondDelete(Optional<ProblemDetail> error) {
        return error.<ResponseEntity<Object>>map(problem -> ResponseEntity.status(problem.getStatus()).body(problem))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
```

`VaultKeyController.java`:

```java
package org.cardanofoundation.lob.app.document_vault.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.service.VaultKeyService;

@RestController
@RequestMapping("/api/v1/document-vault")
@Tag(name = "Document Vault — Keys", description = "Encryption-key directory / addressbook: registration, bindings, org recipients")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class VaultKeyController {

    private static final String ALL_ROLES = "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) "
            + "or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole())";

    private final VaultKeyService keyService;

    @Operation(description = "Register a new X25519 public key for the current account")
    @PostMapping(value = "/keys", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> registerKey(@Valid @RequestBody RegisterKeyRequest request) {
        return Responses.respond(keyService.registerKey(request), HttpStatus.CREATED);
    }

    @Operation(description = "List the current account's keys across organisations (paged)")
    @GetMapping(value = "/keys/me", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<PagedResponse<VaultKeyView>> listMyKeys(
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return ResponseEntity.ok(keyService.listMyKeys(pageable));
    }

    @Operation(description = "Addressbook of an organisation the caller belongs to: recipients with keys and contact e-mail (paged)")
    @GetMapping(value = "/organisations/{organisationId}/recipients", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> listRecipients(@PathVariable String organisationId,
                                                 @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return Responses.respond(keyService.listRecipients(organisationId, pageable), HttpStatus.OK);
    }
}
```

There is deliberately NO revoke endpoint and NO bindings-update endpoint (spec: one key ↔ one org, immutable; no revocation — product decisions).

- [ ] **Step 7: Run all module tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): key directory with registration, e-mail addressbook and org recipients"
```

---

### Task 4a: Key cards — import a signed key to add a recipient (blueprint B6)

**Why this exists.** You cannot encrypt to someone whose public key you do not have. So "add a new recipient to the addressbook" (contract §0 step 4.3) needs a public key to arrive from somewhere — and if any user could simply assert *"this key is Bob's"*, that is a key-substitution attack: the attacker registers their own key under Bob's name and quietly receives every document meant for Bob. The trust anchor is therefore the **issuer's Ed25519 signature**, never the importer's word. A card signed by a key nobody configured is worthless, which is exactly the property we want.

Because the signature is the authority, **any** org role may import a card — a role gate would add no security here, and would break the accountant who is adding a recipient mid-upload.

**Files:**
- Create: `.../domain/enums/CardSubjectType.java`
- Create: `.../domain/card/KeyCardDto.java`
- Create: `.../domain/request/ImportCardRequest.java`
- Create: `.../service/KeyCardVerifier.java`
- Create: `.../service/CardImportService.java`
- Modify: `.../repository/VaultKeyRepository.java` (add `findByAccountIdAndOrganisationIdAndPublicKey`)
- Modify: `.../resource/VaultKeyController.java` (add `POST /cards/import`)
- Test: `.../service/KeyCardVerifierTest.java`, `.../service/CardImportServiceTest.java`

**Interfaces:**
- Consumes: `VaultKeyRepository`, `KeycloakSecurityHelper`, `OrganisationPublicApiIF`, `VaultProblems`, `VaultKeyService.toView`, `KeyOrigin`/`KeyAssurance` (Task 4).
- Produces: `KeyCardVerifier.hasIssuers(): boolean`, `KeyCardVerifier.verify(KeyCardDto, String organisationId): Either<ProblemDetail, KeyCardDto>`, `KeyCardVerifier.signingInput(KeyCardDto): byte[]` (package-private, tested directly); `CardImportService.importCard(ImportCardRequest): Either<ProblemDetail, VaultKeyView>`.

- [ ] **Step 1: Write the failing verifier test**

`KeyCardVerifierTest.java`. The signing input is a **cross-language contract** (a Java verifier, a TypeScript issuer in the Indexer, a TypeScript importer in the Reeve frontend), so the test pins the exact bytes by building them a second, independent way — if anyone reorders a field or changes an encoding, this fails loudly here instead of silently rejecting real cards in production.

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyCardVerifierTest {

    /** Fixed seed -> deterministic issuer keypair, so this test has no hidden randomness. */
    private static final byte[] SEED = HexFormat.of()
            .parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final String ISSUER_ID = "reeve-indexer-test";
    private static final String X25519_PUB = "a".repeat(64);

    private Ed25519PrivateKeyParameters issuerPriv;
    private String issuerPubHex;
    private KeyCardVerifier verifier;

    @BeforeEach
    void setUp() {
        issuerPriv = new Ed25519PrivateKeyParameters(SEED, 0);
        issuerPubHex = HexFormat.of().formatHex(issuerPriv.generatePublicKey().getEncoded());
        verifier = new KeyCardVerifier(ISSUER_ID + ":" + issuerPubHex);
    }

    private KeyCardDto card() {
        KeyCardDto card = new KeyCardDto();
        card.setV(1);
        card.setType("REEVE_KEY_CARD");
        card.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Bob Miller", "bob@example.org", "org1"));
        card.setKey(new KeyCardDto.Key(X25519_PUB, "Bob's audit key", KeyAssurance.PORTABLE,
                "2026-07-14T10:15:30Z"));
        card.setIssuer(new KeyCardDto.Issuer(ISSUER_ID, "Ed25519", issuerPubHex));
        card.setSignature(sign(card));
        return card;
    }

    private String sign(KeyCardDto card) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, issuerPriv);
        byte[] input = KeyCardVerifier.signingInput(card);
        signer.update(input, 0, input.length);
        return HexFormat.of().formatHex(signer.generateSignature());
    }

    @Test
    void acceptsAGenuineCard() {
        assertTrue(verifier.verify(card(), "org1").isRight());
    }

    /**
     * The signing input, built independently from contract §2.8.3: 14 length-prefixed UTF-8 fields,
     * each preceded by its 4-byte big-endian length, in exactly this order.
     */
    @Test
    void signingInputIsLengthPrefixedInTheContractOrder() {
        byte[] expected = concat(
                lp("REEVE_KEY_CARD"), lp("1"),
                lp("REEVE_ACCOUNT"), lp("sub-bob"), lp("Bob Miller"), lp("bob@example.org"), lp("org1"),
                lp(X25519_PUB), lp("Bob's audit key"), lp("PORTABLE"), lp("2026-07-14T10:15:30Z"),
                lp(ISSUER_ID), lp("Ed25519"), lp(issuerPubHex));

        assertArrayEquals(expected, KeyCardVerifier.signingInput(card()));
    }

    @Test
    void rejectsAnUnknownIssuer() {
        KeyCardDto card = card();
        card.setIssuer(new KeyCardDto.Issuer("someone-else", "Ed25519", "b".repeat(64)));

        Either<ProblemDetail, KeyCardDto> result = verifier.verify(card, "org1");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.CARD_ISSUER_UNKNOWN, result.getLeft().getTitle());
    }

    /** An issuer id we know, but paired with a public key we do not: the pairing itself is checked. */
    @Test
    void rejectsAKnownIssuerIdCarryingAForeignPublicKey() {
        KeyCardDto card = card();
        card.setIssuer(new KeyCardDto.Issuer(ISSUER_ID, "Ed25519", "b".repeat(64)));

        assertEquals(VaultProblems.CARD_ISSUER_UNKNOWN, verifier.verify(card, "org1").getLeft().getTitle());
    }

    /** Every signed field must really be covered — tamper with each in turn, all must fail. */
    @Test
    void rejectsTamperingWithAnySignedField() {
        KeyCardDto tamperedKey = card();
        tamperedKey.setKey(new KeyCardDto.Key("b".repeat(64), "Bob's audit key", KeyAssurance.PORTABLE,
                "2026-07-14T10:15:30Z"));
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID,
                verifier.verify(tamperedKey, "org1").getLeft().getTitle());

        KeyCardDto tamperedSubject = card();
        tamperedSubject.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-mallory",
                "Bob Miller", "bob@example.org", "org1"));
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID,
                verifier.verify(tamperedSubject, "org1").getLeft().getTitle());

        KeyCardDto tamperedEmail = card();
        tamperedEmail.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Bob Miller", "mallory@example.org", "org1"));
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID,
                verifier.verify(tamperedEmail, "org1").getLeft().getTitle());

        KeyCardDto tamperedAssurance = card();
        tamperedAssurance.setKey(new KeyCardDto.Key(X25519_PUB, "Bob's audit key", KeyAssurance.PASSKEY,
                "2026-07-14T10:15:30Z"));
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID,
                verifier.verify(tamperedAssurance, "org1").getLeft().getTitle());
    }

    @Test
    void rejectsACardIssuedForAnotherOrganisation() {
        assertEquals(VaultProblems.CARD_ORG_MISMATCH,
                verifier.verify(card(), "other-org").getLeft().getTitle());
    }

    @Test
    void rejectsAnUnsupportedCardVersion() {
        KeyCardDto card = card();
        card.setV(2);

        assertEquals(VaultProblems.UNSUPPORTED_CARD_VERSION,
                verifier.verify(card, "org1").getLeft().getTitle());
    }

    /** A card must not be able to claim one algorithm while we verify it under another. */
    @Test
    void rejectsAnAlgorithmOtherThanEd25519() {
        KeyCardDto card = card();
        card.setIssuer(new KeyCardDto.Issuer(ISSUER_ID, "RSA", issuerPubHex));

        assertEquals(VaultProblems.UNSUPPORTED_CARD_VERSION,
                verifier.verify(card, "org1").getLeft().getTitle());
    }

    /** I5: a private key must never enter the backend — not even wrapped. Reject, never silently drop. */
    @Test
    void rejectsACardStillCarryingItsPrivateKeySection() {
        KeyCardDto card = card();
        card.putUnknown("privateKey", java.util.Map.of("wrapped", "deadbeef"));

        assertEquals(VaultProblems.CARD_CONTAINS_PRIVATE_KEY,
                verifier.verify(card, "org1").getLeft().getTitle());
    }

    @Test
    void reportsWhenNoIssuersAreConfigured() {
        assertFalse(new KeyCardVerifier("").hasIssuers());
        assertTrue(verifier.hasIssuers());
    }

    // --- test-local helpers: an independent implementation of §2.8.3 ---

    private static byte[] lp(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return concat(ByteBuffer.allocate(4).putInt(bytes.length).array(), bytes);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*KeyCardVerifierTest*"`
Expected: COMPILE FAILURE (`KeyCardDto`, `KeyCardVerifier`, `CardSubjectType` do not exist).

- [ ] **Step 3: Implement the card DTO and enums**

`domain/enums/CardSubjectType.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.enums;

/** Who a key card is about. */
public enum CardSubjectType {
    /** The holder logs into Reeve; subjectId is their Keycloak `sub`. */
    REEVE_ACCOUNT,
    /** The holder has no Reeve login (e.g. an external auditor); subjectId is an Indexer-minted UUID.
     *  They are addressable as a recipient and read published documents in the Indexer. */
    EXTERNAL
}
```

`domain/card/KeyCardDto.java` — contract §2.8.2. Note what is deliberately **absent**: any `privateKey` field. Declaring one would violate the B5 ArchUnit rule, and Jackson would otherwise discard an incoming one silently — leaving the user believing the backend now holds their key. Instead an `@JsonAnySetter` sink captures it so we can **reject** it explicitly.

```java
package org.cardanofoundation.lob.app.document_vault.domain.card;

import java.util.HashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

/**
 * An Ed25519-signed statement: "this X25519 public key belongs to this holder, in this organisation,
 * and I — the issuer — vouch for it" (contract §2.8). Minted by the Indexer.
 *
 * NOTE: no `privateKey` field, on purpose (blueprint I5). A handover card carries one; the client
 * strips it before import, and if it does not, the unknown-field sink below catches it and the
 * request is REJECTED (400 CARD_CONTAINS_PRIVATE_KEY) rather than silently accepted minus the key.
 */
@Getter
@Setter
@NoArgsConstructor
public class KeyCardDto {

    @Schema(description = "Card wire-format version.")
    private int v;

    @NotBlank
    private String type;

    @NotNull
    @Valid
    private Subject subject;

    @NotNull
    @Valid
    private Key key;

    @NotNull
    @Valid
    private Issuer issuer;

    @Schema(description = "Ed25519 signature over the length-prefixed signing input (contract §2.8.3).")
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{128}$", message = "signature must be 64 bytes of lowercase hex.")
    private String signature;

    /** Everything we do not model — including a `privateKey` section, which must be rejected. */
    private final Map<String, Object> unknown = new HashMap<>();

    @JsonAnySetter
    public void putUnknown(String name, Object value) {
        unknown.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getUnknown() {
        return unknown;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subject(@NotNull CardSubjectType subjectType,
                          @NotBlank @Size(max = 255) String subjectId,
                          @NotBlank @Size(max = 255) String displayName,
                          @NotBlank @Email @Size(max = 320) String email,
                          @NotBlank String organisationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Key(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$",
                              message = "publicKey must be 32 bytes of lowercase hex.") String publicKey,
                      @NotBlank @Size(max = 255) String label,
                      @NotNull KeyAssurance assurance,
                      @NotBlank @Size(max = 64) String createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issuer(@NotBlank @Size(max = 64) String issuerId,
                         @NotBlank String algorithm,
                         @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$",
                                 message = "issuer publicKey must be 32 bytes of lowercase hex.")
                         String publicKey) {
    }
}
```

- [ ] **Step 4: Implement `KeyCardVerifier`**

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Nullable;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;

/**
 * Verifies key cards (contract §2.8). BouncyCastle is already a platform-wide dependency (root
 * build.gradle.kts), and its low-level Ed25519Signer takes raw 32-byte keys — no X.509 encoding
 * dance, which is what makes hex-encoded keys straightforward here.
 *
 * Issuers are configured as a comma-separated `issuerId:publicKeyHex` list. A plain @Value string
 * rather than @ConfigurationProperties on purpose: the platform's property names contain underscores
 * (`lob.document_vault.*`), and @ConfigurationProperties prefixes may not — so @Value keeps the
 * naming consistent with every other module instead of introducing a lone hyphenated outlier.
 *
 * Empty list = no issuer this deployment trusts = card import is off (503), exactly as "no IPFS
 * configured" means publishing is off.
 */
@Slf4j
@Component
public class KeyCardVerifier {

    private static final int SUPPORTED_CARD_VERSION = 1;
    private static final String CARD_TYPE = "REEVE_KEY_CARD";
    private static final String SUPPORTED_ALGORITHM = "Ed25519";
    private static final String PRIVATE_KEY_FIELD = "privateKey";

    /** issuerId -> Ed25519 public key (lowercase hex). */
    private final Map<String, String> issuers;

    public KeyCardVerifier(@Value("${lob.document_vault.card.issuers:}") String rawIssuers) {
        this.issuers = parse(rawIssuers);
        log.info("Document vault key-card issuers configured: {}", issuers.keySet());
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return Map.copyOf(parsed);
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":", 2);
            // Fail fast at startup: a malformed issuer entry would silently disable a trust anchor,
            // and a deployment that thinks it trusts an issuer but does not is worse than one that
            // refuses to boot.
            if (parts.length != 2 || parts[0].isBlank() || !parts[1].matches("^[0-9a-f]{64}$")) {
                throw new IllegalStateException(
                        "Invalid lob.document_vault.card.issuers entry (expected id:64-hex-ed25519-key): "
                                + trimmed);
            }
            parsed.put(parts[0], parts[1]);
        }
        return Map.copyOf(parsed);
    }

    public boolean hasIssuers() {
        return !issuers.isEmpty();
    }

    /**
     * The kill switch (contract §2.8.5). A key is only as trustworthy as the issuer still vouching
     * for it: drop a compromised issuer from the config and every key it ever introduced stops being
     * offered as a wrap target — no revocation endpoint, no status column, no migration.
     *
     * SELF_ENROLLED keys have no issuer and are always trusted (issuerId == null): they were born on
     * their owner's device and no third party ever vouched for them.
     */
    public boolean isTrustedIssuer(@Nullable String issuerId) {
        return issuerId == null || issuers.containsKey(issuerId);
    }

    public Either<ProblemDetail, KeyCardDto> verify(KeyCardDto card, String organisationId) {
        // The algorithm is a SIGNED field, so a mismatch cannot be a silent downgrade — but it must
        // still be checked, or a card could name (say) "RSA" while we verify it as Ed25519 and the
        // holder would believe a guarantee we never made.
        if (card.getV() != SUPPORTED_CARD_VERSION
                || !CARD_TYPE.equals(card.getType())
                || !SUPPORTED_ALGORITHM.equals(card.getIssuer().algorithm())) {
            return Either.left(VaultProblems.badRequest(VaultProblems.UNSUPPORTED_CARD_VERSION,
                    "Unsupported key card: type=%s v=%d algorithm=%s (this server understands %s v%d, %s)."
                            .formatted(card.getType(), card.getV(), card.getIssuer().algorithm(),
                                    CARD_TYPE, SUPPORTED_CARD_VERSION, SUPPORTED_ALGORITHM)));
        }
        if (card.getUnknown().containsKey(PRIVATE_KEY_FIELD)) {
            return Either.left(VaultProblems.badRequest(VaultProblems.CARD_CONTAINS_PRIVATE_KEY,
                    "This card still carries its privateKey section. Strip it in the client before "
                            + "importing: the server must never hold private key material."));
        }

        String expectedIssuerKey = issuers.get(card.getIssuer().issuerId());
        if (expectedIssuerKey == null || !expectedIssuerKey.equals(card.getIssuer().publicKey())) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.CARD_ISSUER_UNKNOWN,
                    "Issuer %s is not trusted by this deployment.".formatted(card.getIssuer().issuerId())));
        }
        if (!verifySignature(card, expectedIssuerKey)) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.CARD_SIGNATURE_INVALID,
                    "The card's signature does not verify — it is corrupt or forged."));
        }
        if (!card.getSubject().organisationId().equals(organisationId)) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.CARD_ORG_MISMATCH,
                    "The card was issued for organisation %s, not %s."
                            .formatted(card.getSubject().organisationId(), organisationId)));
        }
        return Either.right(card);
    }

    private boolean verifySignature(KeyCardDto card, String issuerPublicKeyHex) {
        try {
            Ed25519PublicKeyParameters publicKey =
                    new Ed25519PublicKeyParameters(HexFormat.of().parseHex(issuerPublicKeyHex), 0);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, publicKey);
            byte[] input = signingInput(card);
            signer.update(input, 0, input.length);
            return signer.verifySignature(HexFormat.of().parseHex(card.getSignature()));
        } catch (IllegalArgumentException e) { // malformed hex slipped past bean validation
            log.warn("Malformed key card signature material: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Contract §2.8.3 — the exact bytes three implementations must agree on (this verifier, the
     * Indexer's issuer, the frontend's importer).
     *
     * Length-prefixed concatenation rather than canonical JSON, deliberately: it removes every
     * canonicalisation question (key order, whitespace, unicode escaping) that JSON would force all
     * three to answer identically. Each field is its 4-byte big-endian UTF-8 length, then its bytes.
     * Changing this list means a new card version — never an in-place edit.
     */
    static byte[] signingInput(KeyCardDto card) {
        List<String> fields = List.of(
                CARD_TYPE,
                String.valueOf(card.getV()),
                card.getSubject().subjectType().name(),
                card.getSubject().subjectId(),
                card.getSubject().displayName(),
                card.getSubject().email(),
                card.getSubject().organisationId(),
                card.getKey().publicKey(),
                card.getKey().label(),
                card.getKey().assurance().name(),
                card.getKey().createdAt(),
                card.getIssuer().issuerId(),
                card.getIssuer().algorithm(),
                card.getIssuer().publicKey());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
            out.write(ByteBuffer.allocate(4).putInt(bytes.length).array(), 0, 4);
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }
}
```

- [ ] **Step 5: Run the verifier tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*KeyCardVerifierTest*"`
Expected: PASS (10 tests).

- [ ] **Step 6: Write the failing import-service test**

`CardImportServiceTest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.ImportCardRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CardImportServiceTest {

    private static final String X25519_PUB = "a".repeat(64);

    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private KeyCardVerifier verifier;

    private CardImportService service;

    @BeforeEach
    void setUp() {
        service = new CardImportService(keyRepository, securityHelper, organisationPublicApi, verifier);
        when(verifier.hasIssuers()).thenReturn(true);
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(securityHelper.getCurrentUserId()).thenReturn("sub-alice");
        when(organisationPublicApi.findByOrganisationId("org1"))
                .thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(keyRepository.save(any(VaultKeyEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ImportCardRequest request(CardSubjectType subjectType, String subjectId) {
        KeyCardDto card = new KeyCardDto();
        card.setV(1);
        card.setType("REEVE_KEY_CARD");
        card.setSubject(new KeyCardDto.Subject(subjectType, subjectId, "Bob Miller",
                "bob@example.org", "org1"));
        card.setKey(new KeyCardDto.Key(X25519_PUB, "Bob's audit key", KeyAssurance.PORTABLE,
                "2026-07-14T10:15:30Z"));
        card.setIssuer(new KeyCardDto.Issuer("reeve-indexer-test", "Ed25519", "b".repeat(64)));
        card.setSignature("c".repeat(128));

        when(verifier.verify(any(), any())).thenReturn(Either.right(card));

        ImportCardRequest request = new ImportCardRequest();
        request.setOrganisationId("org1");
        request.setCard(card);
        return request;
    }

    /** The headline requirement: adding a new recipient persists them for later use. */
    @Test
    void importingAContactCardCreatesAnAddressbookEntryForTheHolder() {
        Either<ProblemDetail, VaultKeyView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight());
        ArgumentCaptor<VaultKeyEntity> saved = ArgumentCaptor.forClass(VaultKeyEntity.class);
        verify(keyRepository).save(saved.capture());
        // the SUBJECT owns the key, not the importer — that is what the issuer signature attests to
        assertEquals("sub-bob", saved.getValue().getAccountId());
        assertEquals("bob@example.org", saved.getValue().getEmail());
        assertEquals(KeyOrigin.INDEXER_ISSUED, saved.getValue().getOrigin());
        assertEquals(KeyAssurance.PORTABLE, saved.getValue().getAssurance());
        assertEquals("reeve-indexer-test", saved.getValue().getIssuerId());
        assertFalse(saved.getValue().isExternal());
    }

    @Test
    void anExternalHolderIsMarkedExternal() {
        service.importCard(request(CardSubjectType.EXTERNAL, "indexer-uuid-1"));

        ArgumentCaptor<VaultKeyEntity> saved = ArgumentCaptor.forClass(VaultKeyEntity.class);
        verify(keyRepository).save(saved.capture());
        assertTrue(saved.getValue().isExternal());
        assertEquals("indexer-uuid-1", saved.getValue().getAccountId());
    }

    /** A card whose subject IS the caller lands in their own keychain — no branch, the subject decides. */
    @Test
    void importingOwnCardBindsTheKeyToTheCaller() {
        service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-alice"));

        ArgumentCaptor<VaultKeyEntity> saved = ArgumentCaptor.forClass(VaultKeyEntity.class);
        verify(keyRepository).save(saved.capture());
        assertEquals("sub-alice", saved.getValue().getAccountId()); // shows up in GET /keys/me
    }

    /** Re-adding a recipient is a normal thing for a user to do, not an error. */
    @Test
    void reimportingTheSameCardUpdatesInPlaceInsteadOfDuplicating() {
        VaultKeyEntity existing = new VaultKeyEntity();
        existing.setId("existing-key");
        existing.setAccountId("sub-bob");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setLabel("stale label");
        existing.setEmail("stale@example.org");
        when(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey("sub-bob", "org1", X25519_PUB))
                .thenReturn(Optional.of(existing));

        Either<ProblemDetail, VaultKeyView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight());
        assertEquals("existing-key", result.get().keyId()); // same row, no duplicate
        assertEquals("bob@example.org", result.get().email()); // refreshed from the card
    }

    @Test
    void importIsUnavailableWhenNoIssuersAreConfigured() {
        when(verifier.hasIssuers()).thenReturn(false);

        Either<ProblemDetail, VaultKeyView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isLeft());
        assertEquals(503, result.getLeft().getStatus());
        assertEquals(VaultProblems.CARD_IMPORT_UNAVAILABLE, result.getLeft().getTitle());
        verify(keyRepository, never()).save(any());
    }

    @Test
    void aRejectedCardWritesNothing() {
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        when(verifier.verify(any(), any())).thenReturn(Either.left(
                VaultProblems.unprocessable(VaultProblems.CARD_SIGNATURE_INVALID, "nope")));

        Either<ProblemDetail, VaultKeyView> result = service.importCard(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID, result.getLeft().getTitle());
        verify(keyRepository, never()).save(any());
    }

    @Test
    void importIntoAForeignOrganisationIsForbidden() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        Either<ProblemDetail, VaultKeyView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isLeft());
        assertEquals(403, result.getLeft().getStatus());
        verify(keyRepository, never()).save(any());
    }
}
```

- [ ] **Step 7: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*CardImportServiceTest*"`
Expected: COMPILE FAILURE (`ImportCardRequest`, `CardImportService`, `findByAccountIdAndOrganisationIdAndPublicKey` missing).

- [ ] **Step 8: Implement request, repository method and service**

`domain/request/ImportCardRequest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

/** Extends BaseRequest so OrganisationCheckInterceptor guards the organisationId as well. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImportCardRequest extends BaseRequest {

    @NotNull
    @Valid
    private KeyCardDto card;
}
```

Add to `VaultKeyRepository`:

```java
    Optional<VaultKeyEntity> findByAccountIdAndOrganisationIdAndPublicKey(String accountId,
                                                                         String organisationId,
                                                                         String publicKey);
```

`service/CardImportService.java`:

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.ImportCardRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B6 — import an Ed25519-signed key card (contract §2.8, 5.13).
 *
 * This is how a NEW RECIPIENT enters an addressbook, and it is deliberately the only way: a public
 * key you did not verify is a key-substitution attack waiting to happen. The issuer's signature is
 * the trust anchor, so the importer's role is irrelevant — any org member may import a valid card,
 * and nobody can forge one without the issuer key.
 *
 * The card's SUBJECT decides ownership, never the caller: a card about Bob creates Bob's entry (an
 * addressbook contact), a card about the caller lands in their own /keys/me. No branch is needed —
 * account_id is simply the subject id in both cases.
 */
@Service
@RequiredArgsConstructor
public class CardImportService {

    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final KeyCardVerifier verifier;

    @Transactional
    public Either<ProblemDetail, VaultKeyView> importCard(ImportCardRequest request) {
        if (!verifier.hasIssuers()) {
            return Either.left(VaultProblems.serviceUnavailable(VaultProblems.CARD_IMPORT_UNAVAILABLE,
                    "This deployment trusts no key-card issuer, so cards cannot be imported."));
        }
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }

        Either<ProblemDetail, KeyCardDto> verified = verifier.verify(request.getCard(), organisationId);
        if (verified.isLeft()) {
            return Either.left(verified.getLeft());
        }
        KeyCardDto card = verified.get();
        String holderId = card.getSubject().subjectId();

        // Idempotent on (account, org, publicKey) — the table's UNIQUE constraint. Re-importing a
        // recipient is normal user behaviour; it refreshes their label/e-mail from the card.
        VaultKeyEntity key = keyRepository
                .findByAccountIdAndOrganisationIdAndPublicKey(holderId, organisationId,
                        card.getKey().publicKey())
                .orElseGet(() -> {
                    VaultKeyEntity fresh = new VaultKeyEntity();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setAccountId(holderId);
                    fresh.setOrganisationId(organisationId);
                    fresh.setPublicKey(card.getKey().publicKey());
                    return fresh;
                });

        key.setAccountName(card.getSubject().displayName());
        key.setEmail(card.getSubject().email());
        key.setLabel(card.getKey().label());
        key.setOrigin(KeyOrigin.INDEXER_ISSUED);
        // The tier is the issuer's assertion — the backend cannot check how a key was born, and does
        // not pretend to. It stores what was vouched for and shows it to every user who picks the key.
        key.setAssurance(card.getKey().assurance());
        key.setExternal(card.getSubject().subjectType() == CardSubjectType.EXTERNAL);
        key.setIssuerId(card.getIssuer().issuerId());

        // The issuer was just checked against the allowlist, so it is trusted by definition right now.
        return Either.right(VaultKeyService.toView(keyRepository.save(key), true));
    }
}
```

- [ ] **Step 9: Add the endpoint**

Add these imports to `VaultKeyController.java`:

```java
import org.cardanofoundation.lob.app.document_vault.domain.request.ImportCardRequest;
import org.cardanofoundation.lob.app.document_vault.service.CardImportService;
```

Then inject `private final CardImportService cardImportService;` and add:

```java
    @Operation(description = "Import a signed key card: adds a recipient to the organisation's addressbook, "
            + "or adopts an Indexer-issued key as your own. The issuer's signature is the trust anchor.")
    @PostMapping(value = "/cards/import", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> importCard(@Valid @RequestBody ImportCardRequest request) {
        return Responses.respond(cardImportService.importCard(request), HttpStatus.OK);
    }
```

- [ ] **Step 10: Run the module suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): signed key cards — import a recipient into the addressbook"
```

---

### Task 5: Recipient resolution (blueprint B1 core)

**Files:**
- Create: `.../domain/request/ResolveRecipientsRequest.java`
- Create: `.../service/RecipientResolutionService.java`
- Modify: `.../resource/VaultKeyController.java` (add endpoint)
- Test: `.../service/RecipientResolutionServiceTest.java`

**Interfaces:**
- Consumes: `VaultKeyRepository.findByAccountIdInAndOrganisationId`, `VaultProblems`, `RecipientKeyView`, `KeycloakSecurityHelper`, and `KeyCardVerifier.isTrustedIssuer(String)` (Task 4a) — the resolver injects `private final KeyCardVerifier cardVerifier` so it can drop keys from a de-trusted issuer before they ever become wrap targets (contract §2.8.5).
- Produces: `RecipientResolutionService.resolve(ResolveRecipientsRequest): Either<ProblemDetail, List<RecipientKeyView>>` — the wrap-target set the client encrypts to.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.ResolveRecipientsRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecipientResolutionServiceTest {

    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private KeyCardVerifier cardVerifier;

    @InjectMocks
    private RecipientResolutionService service;

    @BeforeEach
    void setUp() {
        // lenient: STRICT_STUBS would fail early-return tests that never consume these
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("sender");
        lenient().when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        // Default: every issuer is trusted. Mockito would otherwise return false for this boolean and
        // silently filter out EVERY key — the tests would fail for a reason that has nothing to do
        // with what they are testing. The de-trust tests override this per issuer id.
        lenient().when(cardVerifier.isTrustedIssuer(any())).thenReturn(true);
    }

    private VaultKeyEntity key(String id, String accountId, String publicKey) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(id);
        key.setAccountId(accountId);
        key.setOrganisationId("org1");
        key.setAccountName("Name " + accountId);
        key.setEmail(accountId + "@example.org");
        key.setPublicKey(publicKey);
        key.setLabel("k");
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        return key;
    }

    private ResolveRecipientsRequest request(List<String> recipients) {
        ResolveRecipientsRequest request = new ResolveRecipientsRequest();
        request.setOrganisationId("org1");
        request.setRecipientAccountIds(recipients);
        return request;
    }

    @Test
    void resolvesRecipientsAddsSenderAndDedupes() {
        // recipient appears twice in the request; sender auto-added
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r", "recipient", "a".repeat(64)),
                        key("k-s", "sender", "b".repeat(64))));

        Either<ProblemDetail, List<RecipientKeyView>> result =
                service.resolve(request(List.of("recipient", "recipient")));

        assertTrue(result.isRight());
        assertEquals(2, result.get().size());
        assertTrue(result.get().stream().anyMatch(v -> v.accountId().equals("sender")));
    }

    @Test
    void failsWhenRecipientHasNoKey() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(key("k-s", "sender", "b".repeat(64))));

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("keyless")));

        assertTrue(result.isLeft());
        assertEquals(422, result.getLeft().getStatus());
        assertTrue(result.getLeft().getDetail().contains("keyless"));
    }

    @Test
    void failsWhenSenderHasNoKey() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(key("k-r", "recipient", "a".repeat(64))));

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("recipient")));

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SENDER_KEY_MISSING, result.getLeft().getTitle());
    }

    @Test
    void failsForForeignOrganisation() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        assertTrue(service.resolve(request(List.of("recipient"))).isLeft());
    }

    @Test
    void dedupesByPublicKey() {
        // same public key registered under two key rows -> one wrap target
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r1", "recipient", "a".repeat(64)),
                        key("k-r2", "recipient", "a".repeat(64)),
                        key("k-s", "sender", "b".repeat(64))));

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("recipient")));

        assertTrue(result.isRight());
        assertEquals(2, result.get().size());
    }

    /** "Choose a key to encrypt with": the sender narrows the self-slots to the device they picked. */
    @Test
    void senderKeyIdsNarrowTheSendersOwnSlots() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r", "recipient", "a".repeat(64)),
                        key("k-s1", "sender", "b".repeat(64)),
                        key("k-s2", "sender", "c".repeat(64))));

        ResolveRecipientsRequest request = request(List.of("recipient"));
        request.setSenderKeyIds(List.of("k-s1"));

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request);

        assertTrue(result.isRight());
        assertEquals(2, result.get().size()); // the recipient + only the chosen sender key
        assertTrue(result.get().stream().noneMatch(view -> view.keyId().equals("k-s2")));
    }

    @Test
    void emptySenderKeyIdsMeansAllOwnKeys() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r", "recipient", "a".repeat(64)),
                        key("k-s1", "sender", "b".repeat(64)),
                        key("k-s2", "sender", "c".repeat(64))));

        ResolveRecipientsRequest request = request(List.of("recipient"));
        request.setSenderKeyIds(List.of());

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request);

        assertTrue(result.isRight());
        assertEquals(3, result.get().size()); // a write-only document is not a feature
    }

    /**
     * The containment test (contract §2.8.5). This is the whole answer to "a compromised issuer can
     * seed a hostile key": drop the issuer from the config and the key it vouched for stops being a
     * wrap target — so it never gets a slot in another document. Without this filter, resolve's
     * include-all-of-a-recipient's-keys behaviour would hand the attacker a slot in every future
     * document addressed to their victim.
     */
    @Test
    void keysFromADeTrustedIssuerAreNotWrapTargets() {
        VaultKeyEntity honest = key("k-r", "recipient", "a".repeat(64));
        VaultKeyEntity hostile = key("k-evil", "recipient", "d".repeat(64));
        hostile.setOrigin(KeyOrigin.INDEXER_ISSUED);
        hostile.setAssurance(KeyAssurance.PORTABLE);
        hostile.setIssuerId("compromised-issuer");
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(honest, hostile, key("k-s", "sender", "b".repeat(64))));
        when(cardVerifier.isTrustedIssuer("compromised-issuer")).thenReturn(false);

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("recipient")));

        assertTrue(result.isRight());
        assertEquals(2, result.get().size()); // the honest recipient key + the sender's own
        assertTrue(result.get().stream().noneMatch(view -> view.keyId().equals("k-evil")),
                "a key vouched for by a de-trusted issuer must never become a wrap target");
    }

    /** If de-trusting leaves a recipient with no usable key, say so — never drop them silently. */
    @Test
    void aRecipientLeftWithOnlyDeTrustedKeysIsReportedMissing() {
        VaultKeyEntity onlyKey = key("k-evil", "recipient", "d".repeat(64));
        onlyKey.setOrigin(KeyOrigin.INDEXER_ISSUED);
        onlyKey.setIssuerId("compromised-issuer");
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(onlyKey, key("k-s", "sender", "b".repeat(64))));
        when(cardVerifier.isTrustedIssuer("compromised-issuer")).thenReturn(false);

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("recipient")));

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.RECIPIENT_KEY_MISSING, result.getLeft().getTitle());
    }

    @Test
    void senderKeyIdsRejectsAKeyThatIsNotTheCallers() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r", "recipient", "a".repeat(64)),
                        key("k-s1", "sender", "b".repeat(64))));

        ResolveRecipientsRequest request = request(List.of("recipient"));
        request.setSenderKeyIds(List.of("k-r")); // the recipient's key, not mine

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SENDER_KEY_INVALID, result.getLeft().getTitle());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*RecipientResolutionServiceTest*"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement request + service**

`ResolveRecipientsRequest.java` (extends `BaseRequest` so `OrganisationCheckInterceptor` also guards it):

```java
package org.cardanofoundation.lob.app.document_vault.domain.request;

import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotEmpty;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResolveRecipientsRequest extends BaseRequest {

    @NotEmpty(message = "At least one recipient is required.")
    private List<String> recipientAccountIds;

    /**
     * Optional: which of the CALLER'S OWN keys in this organisation get a slot — i.e. which of their
     * devices can reopen the document later ("choose a key to encrypt with", contract §0 step 4.2).
     * Null or empty means all of them, which is the right default and the previous behaviour. It can
     * never mean "none": the sender is always a recipient of their own document.
     */
    @Nullable
    private List<String> senderKeyIds;
}
```

`RecipientResolutionService.java`:

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.request.ResolveRecipientsRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B1: resolve -> validate -> dedupe -> auto-include the sender's own keys. The client
 * never assembles the recipient set itself.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipientResolutionService {

    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final KeyCardVerifier cardVerifier;

    public Either<ProblemDetail, List<RecipientKeyView>> resolve(ResolveRecipientsRequest request) {
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        String senderId = securityHelper.getCurrentUserId();

        Set<String> wanted = new HashSet<>(request.getRecipientAccountIds());
        wanted.add(senderId);

        // The kill switch, applied where it matters most (contract §2.8.5): a key whose issuer has been
        // de-trusted is not a wrap target. This is the ONE place a hostile injected key would otherwise
        // earn a slot in every future document addressed to its subject — resolve includes ALL of a
        // recipient's keys, so a substituted key rides along silently unless it is dropped here.
        // A PORTABLE key from a compromised issuer must be assumed known to the attacker (that issuer
        // minted it), so the sender's own keys are filtered too — no exception for the caller.
        List<VaultKeyEntity> keys = keyRepository.findByAccountIdInAndOrganisationId(wanted, organisationId)
                .stream()
                .filter(key -> cardVerifier.isTrustedIssuer(key.getIssuerId()))
                .toList();

        Set<String> accountsWithKeys = new HashSet<>(keys.stream().map(VaultKeyEntity::getAccountId).toList());
        List<String> missingRecipients = request.getRecipientAccountIds().stream()
                .distinct()
                .filter(accountId -> !accountsWithKeys.contains(accountId))
                .toList();
        if (!missingRecipients.isEmpty()) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.RECIPIENT_KEY_MISSING,
                    "No key bound to organisation %s for account(s): %s"
                            .formatted(organisationId, String.join(", ", missingRecipients))));
        }
        if (!accountsWithKeys.contains(senderId)) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.SENDER_KEY_MISSING,
                    "The sender has no key bound to organisation %s; enroll a key before encrypting."
                            .formatted(organisationId)));
        }

        // The sender may narrow the wrap targets to a subset of their OWN keys (contract §5.4).
        // Empty/absent = all of them. Foreign key ids are rejected, never silently dropped: silently
        // dropping one would produce a document the sender cannot reopen on the device they chose.
        List<String> senderKeyIds = request.getSenderKeyIds();
        List<VaultKeyEntity> effectiveKeys = keys;
        if (senderKeyIds != null && !senderKeyIds.isEmpty()) {
            Set<String> ownKeyIds = keys.stream()
                    .filter(key -> key.getAccountId().equals(senderId))
                    .map(VaultKeyEntity::getId)
                    .collect(Collectors.toSet());
            List<String> foreign = senderKeyIds.stream().distinct()
                    .filter(keyId -> !ownKeyIds.contains(keyId))
                    .toList();
            if (!foreign.isEmpty()) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SENDER_KEY_INVALID,
                        "Not a key of the current account in organisation %s: %s"
                                .formatted(organisationId, String.join(", ", foreign))));
            }
            Set<String> selected = Set.copyOf(senderKeyIds);
            effectiveKeys = keys.stream()
                    .filter(key -> !key.getAccountId().equals(senderId) || selected.contains(key.getId()))
                    .toList();
        }

        // dedupe by public key, first occurrence wins (stable order for the client)
        Map<String, RecipientKeyView> byPublicKey = new LinkedHashMap<>();
        for (VaultKeyEntity key : effectiveKeys) {
            byPublicKey.putIfAbsent(key.getPublicKey(), VaultKeyService.toRecipientView(key));
        }
        return Either.right(List.copyOf(byPublicKey.values()));
    }
}
```

(`VaultKeyService.toRecipientView` is the shared mapping from Task 4 — one definition, so the addressbook and the wrap-target set can never drift apart in what they expose. Both classes live in the same `..document_vault.service` package, so the package-private static is directly callable.)

- [ ] **Step 4: Run to verify the tests pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*RecipientResolutionServiceTest*"`
Expected: PASS (5 tests).

- [ ] **Step 5: Add the endpoint to `VaultKeyController`**

Add these imports to `VaultKeyController.java`:

```java
import org.cardanofoundation.lob.app.document_vault.domain.request.ResolveRecipientsRequest;
import org.cardanofoundation.lob.app.document_vault.service.RecipientResolutionService;
```

Then inject `private final RecipientResolutionService recipientResolutionService;` and add:

```java
    @Operation(description = "Resolve recipient account ids into the validated, deduped public-key set to encrypt to (sender auto-included)")
    @PostMapping(value = "/recipients/resolve", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> resolveRecipients(@Valid @RequestBody ResolveRecipientsRequest request) {
        return Responses.respond(recipientResolutionService.resolve(request), HttpStatus.OK);
    }
```

- [ ] **Step 6: Run all module tests, commit**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test`
Expected: PASS.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): server-side recipient resolution with sender auto-include"
```

---

### Task 6: Wrapped-record store (blueprint B2)

**Files:**
- Create: `.../domain/request/UpsertWrappedRecordRequest.java`
- Create: `.../domain/view/WrappedRecordView.java`
- Create: `.../service/WrappedRecordService.java`
- Create: `.../resource/WrappedRecordController.java`
- Test: `.../service/WrappedRecordServiceTest.java` (unit)
- Test: `.../service/WrappedRecordRoundTripIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 `WrappedRecordEntity/WrappedRecordId/WrappedRecordRepository`, `VaultProblems`, `Responses`.
- Produces: `WrappedRecordService.upsert(String credentialId, UpsertWrappedRecordRequest): Either<ProblemDetail, WrappedRecordView>`; `get(String credentialId): Either<ProblemDetail, WrappedRecordView>`; `listMine(Pageable): PagedResponse<WrappedRecordView>`; `WrappedRecordView(String credentialId, String record, int version, LocalDateTime updatedAt)` (record).

- [ ] **Step 1: Write the failing unit test**

```java
package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordId;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpsertWrappedRecordRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.WrappedRecordView;
import org.cardanofoundation.lob.app.document_vault.repository.WrappedRecordRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class WrappedRecordServiceTest {

    @Mock
    private WrappedRecordRepository recordRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;

    @InjectMocks
    private WrappedRecordService service;

    @BeforeEach
    void setUp() {
        // lenient: STRICT_STUBS would fail the oversize test, which returns before reading the user
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("acc1");
        ReflectionTestUtils.setField(service, "maxRecordBytes", 8192L);
    }

    private UpsertWrappedRecordRequest request(String blob) {
        UpsertWrappedRecordRequest request = new UpsertWrappedRecordRequest();
        request.setRecord(blob);
        request.setVersion(1);
        return request;
    }

    @Test
    void upsertStoresBlobVerbatim() {
        when(recordRepository.save(any(WrappedRecordEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, WrappedRecordView> result = service.upsert("cred-1", request("{\"v\":1}"));

        assertTrue(result.isRight());
        assertEquals("{\"v\":1}", result.get().record());
        assertEquals("cred-1", result.get().credentialId());
    }

    @Test
    void upsertRejectsOversizedBlob() {
        Either<ProblemDetail, WrappedRecordView> result = service.upsert("cred-1", request("x".repeat(9000)));

        assertTrue(result.isLeft());
        assertEquals(413, result.getLeft().getStatus());
    }

    @Test
    void getReturnsOwnRecordOnly() {
        when(recordRepository.findById(new WrappedRecordId("acc1", "cred-1"))).thenReturn(Optional.empty());

        Either<ProblemDetail, WrappedRecordView> result = service.get("cred-1");

        assertTrue(result.isLeft());
        assertEquals(404, result.getLeft().getStatus());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*WrappedRecordServiceTest*"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement request, view, service, controller**

`UpsertWrappedRecordRequest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpsertWrappedRecordRequest {

    @Schema(description = "Opaque, client-encrypted wrapped-key record. Stored and returned verbatim.")
    @NotBlank
    private String record;

    @Min(1)
    private int version;
}
```

`WrappedRecordView.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

public record WrappedRecordView(String credentialId, String record, int version, LocalDateTime updatedAt) {
}
```

`WrappedRecordService.java`:

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.nio.charset.StandardCharsets;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordId;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpsertWrappedRecordRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.WrappedRecordView;
import org.cardanofoundation.lob.app.document_vault.repository.WrappedRecordRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B2: opaque wrapped-record store keyed by (accountId, credentialId). Blobs are stored
 * and returned verbatim — the server must never parse, normalise or transform them.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WrappedRecordService {

    private final WrappedRecordRepository recordRepository;
    private final KeycloakSecurityHelper securityHelper;

    @Value("${lob.document_vault.max-record-bytes:8192}")
    private long maxRecordBytes;

    public Either<ProblemDetail, WrappedRecordView> upsert(String credentialId, UpsertWrappedRecordRequest request) {
        if (request.getRecord().getBytes(StandardCharsets.UTF_8).length > maxRecordBytes) {
            return Either.left(VaultProblems.payloadTooLarge(
                    "Wrapped record exceeds the maximum of %d bytes.".formatted(maxRecordBytes)));
        }
        WrappedRecordEntity entity = recordRepository
                .findById(new WrappedRecordId(securityHelper.getCurrentUserId(), credentialId))
                .orElseGet(() -> {
                    WrappedRecordEntity fresh = new WrappedRecordEntity();
                    fresh.setId(new WrappedRecordId(securityHelper.getCurrentUserId(), credentialId));
                    return fresh;
                });
        entity.setRecord(request.getRecord());
        entity.setVersion(request.getVersion());
        return Either.right(toView(recordRepository.save(entity)));
    }

    @Transactional(readOnly = true)
    public Either<ProblemDetail, WrappedRecordView> get(String credentialId) {
        return recordRepository.findById(new WrappedRecordId(securityHelper.getCurrentUserId(), credentialId))
                .<Either<ProblemDetail, WrappedRecordView>>map(entity -> Either.right(toView(entity)))
                .orElseGet(() -> Either.left(VaultProblems.notFound(VaultProblems.RECORD_NOT_FOUND,
                        "No wrapped record for credential %s on the current account.".formatted(credentialId))));
    }

    @Transactional(readOnly = true)
    public PagedResponse<WrappedRecordView> listMine(Pageable pageable) {
        return PagedResponse.of(recordRepository.findByIdAccountId(securityHelper.getCurrentUserId(), pageable),
                this::toView);
    }

    private WrappedRecordView toView(WrappedRecordEntity entity) {
        return new WrappedRecordView(entity.getId().getCredentialId(), entity.getRecord(),
                entity.getVersion(), entity.getUpdatedAt());
    }
}
```

`WrappedRecordController.java`:

```java
package org.cardanofoundation.lob.app.document_vault.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.document_vault.domain.request.UpsertWrappedRecordRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.WrappedRecordView;
import org.cardanofoundation.lob.app.document_vault.service.WrappedRecordService;

@RestController
@RequestMapping("/api/v1/document-vault")
@Tag(name = "Document Vault — Wrapped Records", description = "Opaque wrapped-key record store for multi-device sync")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class WrappedRecordController {

    private static final String ALL_ROLES = "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) "
            + "or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole())";

    private final WrappedRecordService recordService;

    @Operation(description = "Create or replace the wrapped record for one of the caller's passkey credentials")
    @PutMapping(value = "/records/{credentialId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> upsert(@PathVariable @Size(max = 512) String credentialId,
                                         @Valid @RequestBody UpsertWrappedRecordRequest request) {
        return Responses.respond(recordService.upsert(credentialId, request), HttpStatus.OK);
    }

    @Operation(description = "Fetch the caller's wrapped record for a credential (keychain-load on a new device)")
    @GetMapping(value = "/records/{credentialId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> get(@PathVariable @Size(max = 512) String credentialId) {
        return Responses.respond(recordService.get(credentialId), HttpStatus.OK);
    }

    @Operation(description = "List all wrapped records of the caller (paged)")
    @GetMapping(value = "/records", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<PagedResponse<WrappedRecordView>> listMine(
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return ResponseEntity.ok(recordService.listMine(pageable));
    }
}
```

- [ ] **Step 4: Run unit tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*WrappedRecordServiceTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the byte-identical round-trip integration test (blueprint B2 gate)**

`WrappedRecordRoundTripIntegrationTest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpsertWrappedRecordRequest;

@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class WrappedRecordRoundTripIntegrationTest {

    @Autowired
    private WrappedRecordService service;

    @Test
    void blobRoundTripsByteIdenticalThroughTheFullStack() {
        // adversarial blob: JSON-ish with unicode, base64 of random bytes, embedded quotes/backslashes
        byte[] random = new byte[512];
        new Random(42).nextBytes(random);
        String blob = "{\"v\":1,\"label\":\"emoji 🎉 snowman ☃\",\"wrapped\":\""
                + Base64.getEncoder().encodeToString(random) + "\",\"tricky\":\"a\\\\b\\\"c\"}";

        UpsertWrappedRecordRequest request = new UpsertWrappedRecordRequest();
        request.setRecord(blob);
        request.setVersion(1);
        service.upsert("cred-rt", request);

        String reloaded = service.get("cred-rt").get().record();
        assertEquals(blob, reloaded);
        assertEquals(new String(blob.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), reloaded);
    }
}
```

- [ ] **Step 6: Run it**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*WrappedRecordRoundTripIntegrationTest*"`
Expected: PASS. (With `keycloak.enabled=false`, `getCurrentUserId()` returns `"system"` — both calls use the same account, which is exactly what the test needs.)

- [ ] **Step 7: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): opaque wrapped-record store with byte-identical round-trip"
```

---

### Task 7: Envelope upload + share event (blueprint B3 + B4 event)

**Files:**
- Create: `.../domain/request/UploadDocumentRequest.java` (with nested `PayloadRequest`, `SlotRequest`)
- Create: `.../domain/view/DocumentUploadedView.java`
- Create: `.../domain/events/DocumentSharedEvent.java`
- Create: `.../service/VaultDocumentService.java`
- Create: `.../resource/VaultDocumentController.java`
- Test: `.../service/VaultDocumentServiceTest.java`

**Interfaces:**
- Consumes: Task 3 entities/repos, `VaultProblems`, `KeycloakSecurityHelper`, `OrganisationPublicApiIF`, Spring `ApplicationEventPublisher`.
- Produces (used by Task 8): `VaultDocumentService.upload(UploadDocumentRequest): Either<ProblemDetail, DocumentUploadedView>`; `DocumentSharedEvent(String documentId, String organisationId, Set<String> recipientAccountIds)` (record, jMolecules `@DomainEvent`); `DocumentUploadedView(String documentId, String contentHash, LocalDateTime createdAt)` (record).

- [ ] **Step 1: Write the failing unit test**

```java
package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentSharedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class VaultDocumentServiceTest {

    private static final String HEX64 = "a".repeat(64);
    private static final String HEX96 = "b".repeat(96);
    private static final String HEX24 = "c".repeat(24);
    private static final byte[] CIPHERTEXT = "not-really-encrypted-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private VaultDocumentRepository documentRepository;
    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private KeyCardVerifier cardVerifier;

    @InjectMocks
    private VaultDocumentService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxDocumentBytes", 10_485_760L);
        ReflectionTestUtils.setField(service, "maxSlots", 64);
        // lenient: STRICT_STUBS would fail early-return tests that never consume these
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("sender");
        lenient().when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        // default: issuers are trusted. Mockito's `false` would make every slot key look de-trusted
        // and fail the upload tests for a reason that has nothing to do with what they test.
        lenient().when(cardVerifier.isTrustedIssuer(any())).thenReturn(true);
    }

    /** The stale-client window: the addressbook was cached before the issuer was de-trusted. */
    @Test
    void uploadRejectsASlotWrappedToAKeyFromADeTrustedIssuer() {
        VaultKeyEntity hostile = orgKey("k-s", "sender", "org1");
        hostile.setOrigin(KeyOrigin.INDEXER_ISSUED);
        hostile.setIssuerId("compromised-issuer");
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findAllById(any())).thenReturn(List.of(hostile));
        when(cardVerifier.isTrustedIssuer("compromised-issuer")).thenReturn(false);

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SLOT_KEY_INVALID, result.getLeft().getTitle());
        verify(documentRepository, never()).save(any());
    }

    private VaultKeyEntity orgKey(String id, String accountId, String org) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(id);
        key.setAccountId(accountId);
        key.setOrganisationId(org);
        key.setAccountName("Name " + accountId);
        key.setEmail(accountId + "@example.org");
        key.setPublicKey("e".repeat(64));
        key.setLabel("k");
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        return key;
    }

    private UploadDocumentRequest request() {
        UploadDocumentRequest request = new UploadDocumentRequest();
        request.setOrganisationId("org1");
        request.setEnvelopeVersion(1);
        request.setFileName("q3-report.pdf");
        request.setPlaintextHash(HEX64);
        UploadDocumentRequest.PayloadRequest payload = new UploadDocumentRequest.PayloadRequest();
        payload.setCiphertext(Base64.getEncoder().encodeToString(CIPHERTEXT));
        payload.setNonce(HEX24);
        request.setPayload(payload);
        UploadDocumentRequest.SlotRequest slot1 = new UploadDocumentRequest.SlotRequest();
        slot1.setKeyId("k-s");
        slot1.setRecipientRef("me");
        slot1.setEphemeralPub(HEX64);
        slot1.setWrappedDek(HEX96);
        UploadDocumentRequest.SlotRequest slot2 = new UploadDocumentRequest.SlotRequest();
        slot2.setKeyId("k-r");
        slot2.setRecipientRef("Bob");
        slot2.setEphemeralPub(HEX64);
        slot2.setWrappedDek(HEX96);
        request.setSlots(List.of(slot1, slot2));
        return request;
    }

    @Test
    void uploadPersistsEnvelopeAndPublishesMinimizedEvent() throws Exception {
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findAllById(any())).thenReturn(List.of(
                orgKey("k-s", "sender", "org1"),
                orgKey("k-r", "recipient", "org1")));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isRight());
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CIPHERTEXT));
        assertEquals(expectedHash, result.get().contentHash());

        ArgumentCaptor<VaultDocumentEntity> saved = ArgumentCaptor.forClass(VaultDocumentEntity.class);
        verify(documentRepository).save(saved.capture());
        assertEquals(2, saved.getValue().getSlots().size());
        assertEquals(CIPHERTEXT.length, saved.getValue().getSizeBytes());

        ArgumentCaptor<DocumentSharedEvent> event = ArgumentCaptor.forClass(DocumentSharedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(Set.of("sender", "recipient"), event.getValue().recipientAccountIds());
        assertEquals("org1", event.getValue().organisationId());
    }

    @Test
    void uploadRejectsUnknownSlotKey() {
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        // slot k-r references a key that does not exist in the directory
        when(keyRepository.findAllById(any())).thenReturn(List.of(
                orgKey("k-s", "sender", "org1")));

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SLOT_KEY_INVALID, result.getLeft().getTitle());
    }

    @Test
    void uploadRejectsSlotKeyOfAnotherOrganisation() {
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findAllById(any())).thenReturn(List.of(
                orgKey("k-s", "sender", "org1"),
                orgKey("k-r", "recipient", "other-org")));

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SLOT_KEY_INVALID, result.getLeft().getTitle());
    }

    @Test
    void uploadRejectsUnknownEnvelopeVersion() {
        UploadDocumentRequest request = request();
        request.setEnvelopeVersion(2);

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.UNSUPPORTED_ENVELOPE_VERSION, result.getLeft().getTitle());
    }

    @Test
    void uploadRejectsOversizedCiphertext() {
        ReflectionTestUtils.setField(service, "maxDocumentBytes", 10L);

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isLeft());
        assertEquals(413, result.getLeft().getStatus());
    }

    @Test
    void uploadRejectsInvalidBase64() {
        UploadDocumentRequest request = request();
        request.getPayload().setCiphertext("!!!not-base64!!!");

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request);

        assertTrue(result.isLeft());
        assertEquals(400, result.getLeft().getStatus());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultDocumentServiceTest*"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement request, view, event**

`UploadDocumentRequest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UploadDocumentRequest extends BaseRequest {

    @Schema(description = "Envelope wire-format version; only 1 is supported")
    @NotNull
    private Integer envelopeVersion;

    @Size(max = 255)
    private String fileName;

    @Size(max = 255)
    private String contentType;

    @Size(max = 1024)
    private String description;

    @Schema(description = "SHA-256 commitment over the plaintext, computed client-side; opaque to the server")
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "plaintextHash must be 32 bytes of lowercase hex.")
    private String plaintextHash;

    @NotNull
    @Valid
    private PayloadRequest payload;

    @NotEmpty
    @Valid
    private List<SlotRequest> slots;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PayloadRequest {

        @Schema(description = "AES-256-GCM ciphertext, base64")
        @NotBlank
        private String ciphertext;

        @Schema(description = "GCM nonce, 12 bytes lowercase hex")
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{24}$", message = "nonce must be 12 bytes of lowercase hex.")
        private String nonce;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SlotRequest {

        @NotBlank
        private String keyId;

        @NotBlank
        @Size(max = 255)
        private String recipientRef;

        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "ephemeralPub must be 32 bytes of lowercase hex.")
        private String ephemeralPub;

        @Schema(description = "AES-256-GCM-wrapped DEK (encrypted; the server cannot unwrap it)")
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{96}$", message = "wrappedDek must be 48 bytes of lowercase hex.")
        private String wrappedDek;
    }
}
```

`DocumentUploadedView.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

public record DocumentUploadedView(String documentId, String contentHash, LocalDateTime createdAt) {
}
```

`DocumentSharedEvent.java` (metadata-minimized — no filename, no content, no display names):

```java
package org.cardanofoundation.lob.app.document_vault.domain.events;

import java.util.Set;

import org.jmolecules.event.annotation.DomainEvent;

@DomainEvent
public record DocumentSharedEvent(String documentId, String organisationId, Set<String> recipientAccountIds) {
}
```

- [ ] **Step 4: Implement `VaultDocumentService` (upload half)**

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentSharedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B3: the client delivers exactly two crypto outputs (ciphertext + slots); the server
 * assigns the ID, content-addresses, persists and indexes. Blueprint B5/I5: nothing in here may
 * decrypt, unwrap or otherwise process secret material — validation is structural only.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VaultDocumentService {

    /**
     * Blueprint I7 posture: this is a SET, not a single value. When envelope v2 ships, 2 is added
     * and 1 stays — old client versions must keep being accepted. Unknown (future) versions are
     * rejected: a server cannot store an envelope schema it does not know.
     */
    public static final Set<Integer> SUPPORTED_ENVELOPE_VERSIONS = Set.of(1);

    private final VaultDocumentRepository documentRepository;
    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final ApplicationEventPublisher eventPublisher;
    /** Used at upload to reject slots wrapped to a key whose issuer has been de-trusted (§2.8.5). */
    private final KeyCardVerifier cardVerifier;

    @Value("${lob.document_vault.max-document-bytes:10485760}")
    private long maxDocumentBytes;

    @Value("${lob.document_vault.max-slots:64}")
    private int maxSlots;

    public Either<ProblemDetail, DocumentUploadedView> upload(UploadDocumentRequest request) {
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }
        if (!SUPPORTED_ENVELOPE_VERSIONS.contains(request.getEnvelopeVersion())) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.UNSUPPORTED_ENVELOPE_VERSION,
                    "Envelope version %d is not supported; supported versions: %s."
                            .formatted(request.getEnvelopeVersion(), SUPPORTED_ENVELOPE_VERSIONS)));
        }
        if (request.getSlots().size() > maxSlots) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.TOO_MANY_SLOTS,
                    "Envelope has %d slots; the maximum is %d.".formatted(request.getSlots().size(), maxSlots)));
        }

        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(request.getPayload().getCiphertext());
        } catch (IllegalArgumentException e) {
            return Either.left(VaultProblems.badRequest(VaultProblems.INVALID_PAYLOAD,
                    "ciphertext is not valid base64."));
        }
        if (ciphertext.length == 0) {
            return Either.left(VaultProblems.badRequest(VaultProblems.INVALID_PAYLOAD, "ciphertext is empty."));
        }
        if (ciphertext.length > maxDocumentBytes) {
            return Either.left(VaultProblems.payloadTooLarge(
                    "Ciphertext exceeds the maximum of %d bytes.".formatted(maxDocumentBytes)));
        }

        List<String> keyIds = request.getSlots().stream().map(UploadDocumentRequest.SlotRequest::getKeyId).toList();
        Map<String, VaultKeyEntity> keysById = keyRepository.findAllById(keyIds).stream()
                .collect(Collectors.toMap(VaultKeyEntity::getId, Function.identity()));
        for (UploadDocumentRequest.SlotRequest slot : request.getSlots()) {
            VaultKeyEntity key = keysById.get(slot.getKeyId());
            if (key == null || !key.getOrganisationId().equals(organisationId)) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SLOT_KEY_INVALID,
                        "Slot key %s is unknown or not registered in organisation %s."
                                .formatted(slot.getKeyId(), organisationId)));
            }
            // Closes the stale-client window in the issuer containment (contract §2.8.5): a client that
            // cached the addressbook BEFORE an issuer was de-trusted would otherwise still upload a slot
            // wrapped to a key that issuer vouched for. Resolve is not an authorization gate and a
            // hostile client can put anything in a slot — but an HONEST client with stale state is the
            // likely case, and it costs one condition to stop it. Re-resolve and re-encrypt.
            if (!cardVerifier.isTrustedIssuer(key.getIssuerId())) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SLOT_KEY_INVALID,
                        "Slot key %s was vouched for by issuer %s, which is no longer trusted. "
                                .formatted(slot.getKeyId(), key.getIssuerId())
                                + "Re-resolve the recipients and encrypt again."));
            }
        }

        VaultDocumentEntity document = new VaultDocumentEntity();
        document.setId(UUID.randomUUID().toString());
        document.setOrganisationId(organisationId);
        document.setEnvelopeVersion(request.getEnvelopeVersion());
        document.setContentHash(sha256Hex(ciphertext));
        document.setPlaintextHash(request.getPlaintextHash());
        document.setCiphertext(ciphertext);
        document.setPayloadNonce(request.getPayload().getNonce());
        document.setFileName(request.getFileName());
        document.setContentType(request.getContentType());
        document.setDescription(request.getDescription());
        document.setSizeBytes(ciphertext.length);
        document.setCreatedByAccount(securityHelper.getCurrentUserId());
        document.setCreatedByName(securityHelper.getCurrentUser());
        document.setSlots(request.getSlots().stream()
                .map(slot -> new DocumentSlot(slot.getKeyId(), slot.getRecipientRef(),
                        slot.getEphemeralPub(), slot.getWrappedDek()))
                .toList());

        VaultDocumentEntity saved = documentRepository.save(document);

        Set<String> recipientAccountIds = request.getSlots().stream()
                .map(slot -> keysById.get(slot.getKeyId()).getAccountId())
                .collect(Collectors.toSet());
        eventPublisher.publishEvent(new DocumentSharedEvent(saved.getId(), organisationId, recipientAccountIds));

        return Either.right(new DocumentUploadedView(saved.getId(), saved.getContentHash(), saved.getCreatedAt()));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 5: Implement the controller (upload endpoint only for now)**

`VaultDocumentController.java`:

```java
package org.cardanofoundation.lob.app.document_vault.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;

@RestController
@RequestMapping("/api/v1/document-vault")
@Tag(name = "Document Vault — Documents", description = "Encrypted-envelope upload and listing; the server can never read content")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class VaultDocumentController {

    private static final String ALL_ROLES = "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) "
            + "or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole())";

    private final VaultDocumentService documentService;

    @Operation(description = "Upload an encrypted envelope: ciphertext plus per-recipient wrapped-DEK slots")
    @PostMapping(value = "/documents", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> upload(@Valid @RequestBody UploadDocumentRequest request) {
        return Responses.respond(documentService.upload(request), HttpStatus.CREATED);
    }
}
```

- [ ] **Step 6: Run to verify tests pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultDocumentServiceTest*"`
Expected: PASS (6 tests).

- [ ] **Step 7: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): encrypted envelope upload with slot validation and share event"
```

---

### Task 8: Listing, envelope fetch + delete with published lock (blueprint B4/D2)

**Files:**
- Create: `.../domain/view/DocumentView.java`
- Create: `.../domain/view/DocumentEnvelopeView.java`
- Modify: `.../service/VaultDocumentService.java` (add `list`, `fetch`, `delete`)
- Modify: `.../resource/VaultDocumentController.java` (add endpoints)
- Test: extend `.../service/VaultDocumentServiceTest.java`
- Test: `.../service/VaultDocumentFlowIntegrationTest.java` (end-to-end happy path)

**Interfaces:**
- Consumes: Task 7 service and Task 3 repository queries.
- Produces: `VaultDocumentService.list(String organisationId, DocumentDirection direction, VaultDocumentStatus status, String q, Pageable): Either<ProblemDetail, PagedResponse<DocumentView>>` — org-wide, all filters optional; `fetch(String documentId): Either<ProblemDetail, DocumentEnvelopeView>` (any org member; the envelope — `payload` AND `slots` — populated only for the creator/recipients, else BOTH null with `envelopeAccessible=false`; `recipients[]` always present and key-material-free; 404 only for unknown id or non-member); `delete(String documentId): Optional<ProblemDetail>` (DRAFT only — published documents are immutable); `DocumentDirection` enum `SENT|RECEIVED` (in `domain/enums`, now an optional filter); `DocumentView(String documentId, String fileName, String contentType, String description, long sizeBytes, String contentHash, int envelopeVersion, VaultDocumentStatus status, LedgerDispatchStatus ledgerDispatchStatus, String ledgerDispatchError, String txHash, String ipfsCid, String createdByName, LocalDateTime createdAt)` (record — deliberately NO ciphertext, NO slots: those are served exclusively by `fetch()`/`DocumentEnvelopeView`, the single authorized envelope endpoint).

- [ ] **Step 1: Add failing unit tests to `VaultDocumentServiceTest`**

Add these imports to `VaultDocumentServiceTest.java`:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.springframework.data.domain.Pageable;

import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentEnvelopeView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentView;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
```

```java
    @Test
    void listRequiresMembership() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        assertTrue(service.list("org1", null, null, null, Pageable.unpaged()).isLeft());
    }

    @Test
    void deleteByNonCreatorWithoutAdminRoleIsRejected() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setCreatedByAccount("someone-else");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isPresent());
        assertEquals(VaultProblems.NOT_DOCUMENT_CREATOR, problem.get().getTitle());
    }

    @Test
    void deleteOutsideOwnOrganisationIsRejectedEvenForCreator() {
        // Keycloak admin roles are realm-wide; membership of the document's org is checked separately
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("other-org");
        doc.setCreatedByAccount("sender");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(securityHelper.canUserAccessOrg("other-org")).thenReturn(false);

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isPresent());
        assertEquals(403, problem.get().getStatus());
    }

    @Test
    void deleteByCreatorSucceeds() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setCreatedByAccount("sender");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isEmpty());
        verify(documentRepository).delete(doc);
    }

    @Test
    void deleteOnPublishedDocumentIsRejectedEvenForCreator() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setCreatedByAccount("sender");
        doc.setStatus(VaultDocumentStatus.PUBLISHED);
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isPresent());
        assertEquals(VaultProblems.DOCUMENT_PUBLISHED_IMMUTABLE, problem.get().getTitle());
    }

    @Test
    void fetchByCreatorReturnsEnvelopeWithCiphertext() {
        VaultDocumentEntity doc = draftDoc();
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(keyRepository.findAllById(any())).thenReturn(List.of(orgKey("k-s", "sender", "org1")));

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("doc1");

        assertTrue(result.isRight());
        assertTrue(result.get().envelopeAccessible());
        assertEquals(Base64.getEncoder().encodeToString(CIPHERTEXT), result.get().payload().ciphertext());
        assertEquals(1, result.get().slots().size());
        assertEquals("k-s", result.get().slots().get(0).keyId());
        assertEquals("k-s", result.get().recipients().get(0).keyId());
    }

    @Test
    void fetchByRecipientReturnsEnvelope() {
        when(securityHelper.getCurrentUserId()).thenReturn("recipient");
        VaultDocumentEntity doc = draftDoc(); // created by "sender", slot references key k-s
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(keyRepository.findAllById(any())).thenReturn(List.of(orgKey("k-s", "recipient", "org1")));

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("doc1");

        assertTrue(result.isRight());
        assertTrue(result.get().envelopeAccessible());
        assertNotNull(result.get().payload());
    }

    /**
     * The access change: an org member who is neither creator nor recipient still gets the detail
     * page — the org-wide list already told them the document exists — but NEITHER the ciphertext
     * NOR the slots. The slots carry wrapped DEKs; a draft is not public, so there is no reason to
     * hand them to someone who cannot use them.
     */
    @Test
    void fetchByOtherOrgMemberReturnsMetadataWithoutTheEnvelope() {
        when(securityHelper.getCurrentUserId()).thenReturn("stranger");
        VaultDocumentEntity doc = draftDoc();
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(keyRepository.findAllById(any())).thenReturn(List.of(orgKey("k-s", "sender", "org1")));

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("doc1");

        assertTrue(result.isRight());
        assertFalse(result.get().envelopeAccessible());
        assertNull(result.get().payload());
        assertNull(result.get().slots(), "wrapped DEKs must not reach a non-participant");
        // metadata and "who can read this" stay visible — they are org-visible by design
        assertEquals("q3-report.pdf", result.get().fileName());
        assertEquals(1, result.get().recipients().size());
        assertEquals("sender", result.get().recipients().get(0).accountId());
        assertEquals("k-s", result.get().recipients().get(0).keyId());
    }

    @Test
    void fetchOutsideOwnOrganisationIs404() {
        VaultDocumentEntity doc = draftDoc();
        doc.setOrganisationId("other-org");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(securityHelper.canUserAccessOrg("other-org")).thenReturn(false);

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("doc1");

        assertTrue(result.isLeft());
        assertEquals(404, result.getLeft().getStatus());
    }

    @Test
    void fetchUnknownDocumentIs404() {
        when(documentRepository.findById("nope")).thenReturn(Optional.empty());

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("nope");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.DOCUMENT_NOT_FOUND, result.getLeft().getTitle());
    }
```

Add the `draftDoc()` fixture to the same test class (Tasks 8 and 11 both use it — define it once, here):

```java
    private VaultDocumentEntity draftDoc() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setEnvelopeVersion(1);
        doc.setStatus(VaultDocumentStatus.DRAFT);
        doc.setContentHash("a".repeat(64));
        doc.setPlaintextHash("a".repeat(64));
        doc.setCiphertext(CIPHERTEXT);
        doc.setPayloadNonce(HEX24);
        doc.setFileName("q3-report.pdf");
        doc.setSizeBytes(CIPHERTEXT.length);
        doc.setCreatedByAccount("sender");
        doc.setSlots(List.of(new DocumentSlot("k-s", "canary-recipient-label", HEX64, HEX96)));
        return doc;
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultDocumentServiceTest*"`
Expected: COMPILE FAILURE (`DocumentDirection`, `list`, `delete` missing).

- [ ] **Step 3: Implement**

`DocumentDirection.java` (package `domain/enums`):

```java
package org.cardanofoundation.lob.app.document_vault.domain.enums;

public enum DocumentDirection {
    SENT,
    RECEIVED
}
```

`DocumentView.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

/** Listing metadata only — ciphertext/slots are served exclusively by the envelope-fetch endpoint. */
public record DocumentView(String documentId,
                           String fileName,
                           String contentType,
                           String description,
                           long sizeBytes,
                           String contentHash,
                           int envelopeVersion,
                           VaultDocumentStatus status,
                           LedgerDispatchStatus ledgerDispatchStatus,
                           String ledgerDispatchError,
                           String txHash,
                           String ipfsCid,
                           String createdByName,
                           LocalDateTime createdAt) {
}

// imports: org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus,
//          org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus
```

`DocumentEnvelopeView.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;
import java.util.List;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

/**
 * Document detail (blueprint D2) — the ONLY view allowed to carry ciphertext (spec B5 #2;
 * enforced by ArchUnit in Task 13).
 *
 * Access is two-tier:
 *  - ANY member of the document's organisation gets the metadata and {@code recipients} (who can
 *    read this — derived from the slots' keys, carrying NO key material). The org-wide listing
 *    already reveals that the document exists to every org member, so hiding the detail behind a
 *    404 protected nothing and broke the detail page for the org.
 *  - ONLY the creator and the recipients get the ENVELOPE: {@code payload} AND {@code slots}. For
 *    everyone else both are null and {@code envelopeAccessible} is false.
 *
 * The slots are inside the participant gate deliberately. A wrappedDek is useless without the
 * matching private key, but it is still wrapped key material, and a DRAFT is not public — the
 * "it's on IPFS anyway" argument applies only to published documents, and may never apply at all.
 * Handing wrapped DEKs to org members who cannot use them buys nothing and would leave them
 * holding material that becomes interesting the day a recipient's key leaks.
 *
 * Slots keep keyId/recipientRef labels: this is an org-internal, authorized API — unlike the
 * public IPFS document, which strips them.
 */
public record DocumentEnvelopeView(String documentId,
                                   String organisationId,
                                   VaultDocumentStatus status,
                                   int envelopeVersion,
                                   String fileName,
                                   String contentType,
                                   String description,
                                   long sizeBytes,
                                   String contentHash,
                                   String plaintextHash,
                                   boolean envelopeAccessible,
                                   PayloadView payload,
                                   List<SlotView> slots,
                                   List<RecipientView> recipients,
                                   LedgerDispatchStatus ledgerDispatchStatus,
                                   String ledgerDispatchError,
                                   String txHash,
                                   String ipfsCid,
                                   String createdByName,
                                   LocalDateTime createdAt) {

    public record PayloadView(String ciphertext, String nonce) {
    }

    public record SlotView(String keyId, String recipientRef, String ephemeralPub, String wrappedDek) {
    }

    /** "Who can read this?" — derived from the slots' keys. Carries NO key material. */
    public record RecipientView(String keyId, String accountId, String displayName, String label,
                                KeyAssurance assurance) {
    }
}
```

(`KeyAssurance` comes from Task 4, which creates both key-tier enums.)

Add these imports to `VaultDocumentService.java`:

```java
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentEnvelopeView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentView;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
```

Then add to `VaultDocumentService` (a new `@Value` field for the admin role name, plus the three methods):

```java
    @Value("${keycloak.roles.admin:admin}")
    private String adminRoleName;

    /**
     * Org-wide listing (product decision): every org member sees ALL org documents' metadata.
     * Optional filters: direction (relative to the caller), status, q (fileName/description substring).
     * Envelope fetch stays restricted — metadata visibility does not grant ciphertext access.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, PagedResponse<DocumentView>> list(String organisationId,
                                                                   @Nullable DocumentDirection direction,
                                                                   @Nullable VaultDocumentStatus status,
                                                                   @Nullable String q,
                                                                   Pageable pageable) {
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        String accountId = securityHelper.getCurrentUserId();
        Page<VaultDocumentEntity> page = documentRepository.search(organisationId, accountId,
                direction == null ? null : direction.name(), status,
                (q == null || q.isBlank()) ? null : q, pageable);
        return Either.right(PagedResponse.of(page, this::toView));
    }

    private DocumentView toView(VaultDocumentEntity document) {
        return new DocumentView(document.getId(), document.getFileName(), document.getContentType(),
                document.getDescription(), document.getSizeBytes(), document.getContentHash(),
                document.getEnvelopeVersion(), document.getStatus(), document.getLedgerDispatchStatus(),
                document.getLedgerDispatchError(), document.getTxHash(), document.getIpfsCid(),
                document.getCreatedByName(), document.getCreatedAt());
    }

    /**
     * Blueprint D2. Detail for any org member; ciphertext ONLY for the creator and recipients.
     * Decryption is strictly client-side — the backend cannot decrypt, and never tries.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, DocumentEnvelopeView> fetch(String documentId) {
        String accountId = securityHelper.getCurrentUserId();
        Optional<VaultDocumentEntity> documentM = documentRepository.findById(documentId);
        // 404 for a missing document AND for a non-member: to an outsider the two are the same thing.
        if (documentM.isEmpty() || !securityHelper.canUserAccessOrg(documentM.get().getOrganisationId())) {
            return Either.left(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s accessible to the current account.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();

        // Resolve the slot keys once: they answer both "who can read this?" and "may I?".
        List<String> keyIds = document.getSlots().stream().map(DocumentSlot::getKeyId).toList();
        Map<String, VaultKeyEntity> slotKeys = keyRepository.findAllById(keyIds).stream()
                .collect(Collectors.toMap(VaultKeyEntity::getId, key -> key));

        boolean envelopeAccessible = document.getCreatedByAccount().equals(accountId)
                || slotKeys.values().stream().anyMatch(key -> key.getAccountId().equals(accountId));

        return Either.right(new DocumentEnvelopeView(
                document.getId(), document.getOrganisationId(), document.getStatus(),
                document.getEnvelopeVersion(), document.getFileName(), document.getContentType(),
                document.getDescription(), document.getSizeBytes(), document.getContentHash(),
                document.getPlaintextHash(),
                envelopeAccessible,
                // payload AND slots are the envelope: both go to participants only. A non-participant
                // has no use for a wrappedDek and no business holding one — a draft is not public.
                envelopeAccessible
                        ? new DocumentEnvelopeView.PayloadView(
                                Base64.getEncoder().encodeToString(document.getCiphertext()),
                                document.getPayloadNonce())
                        : null,
                envelopeAccessible
                        ? document.getSlots().stream()
                                .map(slot -> new DocumentEnvelopeView.SlotView(slot.getKeyId(),
                                        slot.getRecipientRef(), slot.getEphemeralPub(), slot.getWrappedDek()))
                                .toList()
                        : null,
                // "who can read this?" — org-visible, and carries no key material whatsoever
                document.getSlots().stream()
                        .map(slot -> slotKeys.get(slot.getKeyId()))
                        .filter(Objects::nonNull)
                        .map(key -> new DocumentEnvelopeView.RecipientView(key.getId(), key.getAccountId(),
                                key.getAccountName(), key.getLabel(), key.getAssurance()))
                        .toList(),
                document.getLedgerDispatchStatus(), document.getLedgerDispatchError(),
                document.getTxHash(), document.getIpfsCid(),
                document.getCreatedByName(), document.getCreatedAt()));
    }

    public Optional<ProblemDetail> delete(String documentId) {
        Optional<VaultDocumentEntity> documentM = documentRepository.findById(documentId);
        if (documentM.isEmpty()) {
            return Optional.of(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();
        // membership first: Keycloak admin roles are realm-wide, so an out-of-org admin must not delete
        if (!securityHelper.canUserAccessOrg(document.getOrganisationId())) {
            return Optional.of(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(document.getOrganisationId())));
        }
        if (document.getStatus() != VaultDocumentStatus.DRAFT) {
            return Optional.of(VaultProblems.conflict(VaultProblems.DOCUMENT_PUBLISHED_IMMUTABLE,
                    "Document %s is published and can never be edited or deleted.".formatted(documentId)));
        }
        boolean isCreator = document.getCreatedByAccount().equals(securityHelper.getCurrentUserId());
        if (!isCreator && !hasAdminRole()) {
            return Optional.of(VaultProblems.of403NotCreator());
        }
        documentRepository.delete(document);
        return Optional.empty();
    }

    private boolean hasAdminRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + adminRoleName));
    }
```

Add to `VaultProblems`:

```java
    public static ProblemDetail of403NotCreator() {
        return of(HttpStatus.FORBIDDEN, NOT_DOCUMENT_CREATOR,
                "Only the document creator or an admin may delete a document.");
    }
```

Add these imports to `VaultDocumentController.java`:

```java
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
```

Then add to `VaultDocumentController`:

```java
    @Operation(description = "Org-wide document metadata listing: paged, sortable (createdAt, fileName, sizeBytes, status), filterable by direction/status/free text")
    @GetMapping(value = "/organisations/{organisationId}/documents", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> list(@PathVariable String organisationId,
                                       @RequestParam(required = false) DocumentDirection direction,
                                       @RequestParam(required = false) VaultDocumentStatus status,
                                       @RequestParam(required = false) @Size(max = 255) String q,
                                       @PageableDefault(size = 20, sort = "createdAt",
                                               direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return Responses.respond(documentService.list(organisationId, direction, status, q, pageable), HttpStatus.OK);
    }
```

```java

    @Operation(description = "Fetch the full encrypted envelope for client-side decryption (creator or recipient only; 404 otherwise)")
    @GetMapping(value = "/documents/{documentId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> fetch(@PathVariable String documentId) {
        return Responses.respond(documentService.fetch(documentId), HttpStatus.OK);
    }

    @Operation(description = "Delete a document (creator or admin only; DRAFT only)")
    @DeleteMapping(value = "/documents/{documentId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> delete(@PathVariable String documentId) {
        return Responses.respondDelete(documentService.delete(documentId));
    }
```

- [ ] **Step 4: Run unit tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultDocumentServiceTest*"`
Expected: PASS (15 tests). Notes: with no `Authentication` in the test context, `hasAdminRole()` is false — the non-creator test passes without extra stubbing. The delete tests rely on `canUserAccessOrg("org1")` stubbed lenient-true in `setUp()`. New-entity `status` defaults to DRAFT via the field initializer, so only the published-immutable test sets it explicitly.

- [ ] **Step 5: Write the end-to-end integration test**

`VaultDocumentFlowIntegrationTest.java` — the full creating-side flow against real Postgres. With `keycloak.enabled=false` every call runs as account `"system"`, so the flow is: register key → resolve (self) → upload → listed as SENT and RECEIVED (self-slot) → delete. Requires one organisation row.

```java
package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.ResolveRecipientsRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationRepository;

@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class VaultDocumentFlowIntegrationTest {

    private static final String ORG_ID = "org-flow-test";

    @Autowired
    private VaultKeyService keyService;
    @Autowired
    private RecipientResolutionService resolutionService;
    @Autowired
    private VaultDocumentService documentService;
    @Autowired
    private OrganisationRepository organisationRepository;

    @BeforeEach
    void organisation() {
        // every NOT NULL column of the organisation table must be set (V1.0_100_3 migration)
        organisationRepository.saveAndFlush(Organisation.builder()
                .id(ORG_ID)
                .name("Flow Test Org")
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

    @Test
    void fullCreatingFlow() {
        // 1. enroll a key
        RegisterKeyRequest keyRequest = new RegisterKeyRequest();
        keyRequest.setOrganisationId(ORG_ID);
        keyRequest.setLabel("laptop");
        keyRequest.setPublicKey("a".repeat(64));
        keyRequest.setEmail("system@example.org");
        String keyId = keyService.registerKey(keyRequest).get().keyId();

        // 2. resolve (sender auto-included -> self key returned)
        ResolveRecipientsRequest resolve = new ResolveRecipientsRequest();
        resolve.setOrganisationId(ORG_ID);
        resolve.setRecipientAccountIds(List.of("system"));
        List<RecipientKeyView> targets = resolutionService.resolve(resolve).get();
        assertEquals(1, targets.size());
        assertEquals(keyId, targets.get(0).keyId());

        // 3. upload an envelope wrapped to the resolved key
        UploadDocumentRequest upload = new UploadDocumentRequest();
        upload.setOrganisationId(ORG_ID);
        upload.setEnvelopeVersion(1);
        upload.setFileName("statement.pdf");
        upload.setPlaintextHash("0".repeat(64));
        UploadDocumentRequest.PayloadRequest payload = new UploadDocumentRequest.PayloadRequest();
        payload.setCiphertext(Base64.getEncoder()
                .encodeToString("ciphertext-bytes".getBytes(StandardCharsets.UTF_8)));
        payload.setNonce("0".repeat(24));
        upload.setPayload(payload);
        UploadDocumentRequest.SlotRequest slot = new UploadDocumentRequest.SlotRequest();
        slot.setKeyId(targets.get(0).keyId());
        slot.setRecipientRef("me");
        slot.setEphemeralPub("b".repeat(64));
        slot.setWrappedDek("c".repeat(96));
        upload.setSlots(List.of(slot));
        DocumentUploadedView uploaded = documentService.upload(upload).get();

        // 4. org-wide listing sees it; direction filters both match (self-slot)
        assertEquals(1, documentService.list(ORG_ID, null, null, null, Pageable.unpaged()).get().total());
        assertEquals(1, documentService.list(ORG_ID, DocumentDirection.SENT, null, null, Pageable.unpaged()).get().total());
        assertEquals(1, documentService.list(ORG_ID, DocumentDirection.RECEIVED, null, null, Pageable.unpaged()).get().total());
        // filters: status + free text + pagination shape
        assertEquals(1, documentService.list(ORG_ID, null, VaultDocumentStatus.DRAFT, "statement", Pageable.unpaged()).get().total());
        assertEquals(0, documentService.list(ORG_ID, null, VaultDocumentStatus.PUBLISHED, null, Pageable.unpaged()).get().total());

        // 5. envelope fetch round-trips the ciphertext for client-side decryption
        var envelope = documentService.fetch(uploaded.documentId()).get();
        assertEquals(Base64.getEncoder().encodeToString("ciphertext-bytes".getBytes(StandardCharsets.UTF_8)),
                envelope.payload().ciphertext());
        assertEquals(1, envelope.slots().size());
        assertEquals(keyId, envelope.slots().get(0).keyId());

        // 6. creator can delete a DRAFT document
        assertTrue(documentService.delete(uploaded.documentId()).isEmpty());
        assertEquals(0, documentService.list(ORG_ID, null, null, null, Pageable.unpaged()).get().total());
    }
}
```

Note: `OrganisationRepository` is `org.cardanofoundation.lob.app.organisation.repository.OrganisationRepository` (already on the classpath via the `:organisation` dependency). The builder above covers every `NOT NULL` column of the `organisation` table (id, name, tax_id_number, country_code, accounting_period_days, currency_id, report_currency_id, phone_number, city, post_code, province, address, admin_email).

- [ ] **Step 6: Run it**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultDocumentFlowIntegrationTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): sent/received listing and creator-or-admin delete"
```

---

### Task 9: Retention job (blueprint B3 retention/deletion policy)

**Files:**
- Create: `.../job/DocumentRetentionJob.java`
- Modify: `.../repository/VaultDocumentRepository.java` (add delete query)
- Test: `.../job/DocumentRetentionJobTest.java`

**Interfaces:**
- Consumes: `VaultDocumentRepository` from Task 3.
- Produces: `DocumentRetentionJob.purgeExpiredDocuments()` — scheduled purge of DRAFT documents only (published are locked forever), inert unless `lob.document_vault.retention-days > 0`. NOTE: `@Scheduled` only fires if the consuming application enables scheduling (`@EnableScheduling`) — same situation as funding's `EventPublishJob`; document this in the class javadoc.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.document_vault.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;

import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

@ExtendWith(MockitoExtension.class)
class DocumentRetentionJobTest {

    @Mock
    private VaultDocumentRepository documentRepository;

    @InjectMocks
    private DocumentRetentionJob job;

    @Test
    void disabledByDefaultDoesNothing() {
        ReflectionTestUtils.setField(job, "retentionDays", 0L);

        job.purgeExpiredDocuments();

        verifyNoInteractions(documentRepository);
    }

    @Test
    void purgesDocumentsOlderThanTheRetentionWindow() {
        ReflectionTestUtils.setField(job, "retentionDays", 30L);
        when(documentRepository.deleteByStatusAndCreatedAtBefore(any(), any())).thenReturn(2L);

        job.purgeExpiredDocuments();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documentRepository).deleteByStatusAndCreatedAtBefore(
                org.mockito.ArgumentMatchers.eq(org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus.DRAFT),
                cutoff.capture());
        Assertions.assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusDays(29)));
        Assertions.assertTrue(cutoff.getValue().isAfter(LocalDateTime.now().minusDays(31)));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*DocumentRetentionJobTest*"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement**

Add to `VaultDocumentRepository` (DRAFT only — published documents are never purged):

```java
    long deleteByStatusAndCreatedAtBefore(
            org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus status,
            java.time.LocalDateTime cutoff);
```

`DocumentRetentionJob.java`:

```java
package org.cardanofoundation.lob.app.document_vault.job;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

/**
 * Blueprint B3 retention policy: hard-deletes DRAFT envelopes older than the configured window.
 * Disabled by default ({@code lob.document_vault.retention-days=0}). Requires the consuming
 * application to enable Spring scheduling ({@code @EnableScheduling}) — without it the job is
 * inert, matching how other module jobs (e.g. funding's EventPublishJob) behave.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentRetentionJob {

    private final VaultDocumentRepository documentRepository;

    @Value("${lob.document_vault.retention-days:0}")
    private long retentionDays;

    @Scheduled(cron = "${lob.document_vault.retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpiredDocuments() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        // DRAFT only: published documents are anchored on IPFS/L1 and are never purged (spec: published lock)
        long deleted = documentRepository.deleteByStatusAndCreatedAtBefore(VaultDocumentStatus.DRAFT, cutoff);
        if (deleted > 0) {
            log.info("document_vault retention purged {} draft envelopes older than {} days", deleted, retentionDays);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*DocumentRetentionJobTest*"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): configurable retention purge job"
```

---

### Task 10: `blockchain_common` — `LedgerUpdateType.DOCUMENT` + `IpfsAvailability`

**Files:**
- Modify: `blockchain_common/src/main/java/org/cardanofoundation/lob/app/blockchain_common/domain/LedgerUpdateType.java`
- Create: `blockchain_common/src/main/java/org/cardanofoundation/lob/app/blockchain_common/service/IpfsAvailability.java`

**Interfaces:**
- Produces: `LedgerUpdateType.DOCUMENT` (event discriminator for the vault); `IpfsAvailability { boolean isAvailable(); }` — implemented by `blockchain_publisher` (Task 12), consumed by the vault publish endpoint (Task 11) via `ObjectProvider` so the bean's absence (publisher off) also means "no publishing".

- [ ] **Step 1: Implement (no dedicated test — an enum value and a capability interface, exercised by Tasks 11–12 tests)**

Add to `LedgerUpdateType` (after `SPENDING_EVENT`):

```java
    SPENDING_EVENT,

    DOCUMENT
```

`IpfsAvailability.java`:

```java
package org.cardanofoundation.lob.app.blockchain_common.service;

/**
 * Cross-module capability probe: is an IPFS publisher configured in this deployment?
 * Implemented by blockchain_publisher; consumed by document_vault to gate publishing
 * ("no IPFS -> no document publishing"). Lives here so the vault never depends on the publisher.
 */
public interface IpfsAvailability {

    boolean isAvailable();
}
```

- [ ] **Step 2: Build + commit**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_common:build`
Expected: BUILD SUCCESSFUL.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_common:spotlessApply
git add blockchain_common
git commit -m "feat(blockchain_common): DOCUMENT ledger update type and IpfsAvailability probe"
```

---

### Task 11: Vault publish endpoint, `DocumentPublishCommand`, ledger-update handler

**Files:**
- Create: `.../domain/events/DocumentPublishCommand.java`
- Create: `.../domain/events/DocumentPublishedEvent.java`
- Modify: `.../service/VaultDocumentService.java` (add `publish`)
- Modify: `.../resource/VaultDocumentController.java` (add endpoint)
- Create: `.../service/DocumentLedgerUpdateHandler.java`
- Test: extend `.../service/VaultDocumentServiceTest.java`; create `.../service/DocumentLedgerUpdateHandlerTest.java`; create `.../service/VaultPublishIntegrationTest.java`; create `.../resource/VaultDocumentControllerSecurityTest.java` (pins the manager/admin publish gate)

**Interfaces:**
- Consumes: Task 10's `IpfsAvailability` + `LedgerUpdateType.DOCUMENT`; `blockchain_common`'s `LedgerUpdatedEvent`/`LedgerStatusUpdate` (all-args ctor `(String id, LedgerDispatchStatus status, String errorReason, Set<BlockchainReceipt> receipts)`)/`BlockchainReceipt`/`LedgerDispatchStatus`.
- Produces (consumed by Task 12): `DocumentPublishCommand(String organisationId, String documentId, int envelopeVersion, String contentHash, String plaintextHash, String payloadNonce, String ciphertextBase64, List<PublishSlot> slots)` with nested `PublishSlot(String ephemeralPub, String wrappedDek)` — **PII-free by design** (no e-mails, no recipientRefs, no keyIds, no fileName/description, no account ids; spec B5 #3). Receipt-type contract with the publisher: `"IPFS"` → CID, any other receipt with a hash → L1 tx hash.

- [ ] **Step 1: Write the failing tests**

Add these imports to `VaultDocumentServiceTest.java` (`assertFalse` is already there from Task 8):

```java
import org.springframework.beans.factory.ObjectProvider;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
```

Then add to `VaultDocumentServiceTest` (new mock: `@Mock private ObjectProvider<IpfsAvailability> ipfsAvailability;` — `@InjectMocks` wires it into the service). The `draftDoc()` fixture these tests use already exists — it was added in Task 8; do not redefine it.

```java
    @Test
    void publishLocksDocumentAndFiresPiiFreeCommand() {
        VaultDocumentEntity doc = draftDoc();
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ipfsAvailability.getIfAvailable()).thenReturn(() -> true);

        Either<ProblemDetail, DocumentView> result = service.publish("doc1");

        assertTrue(result.isRight());
        assertEquals(VaultDocumentStatus.PUBLISHED, result.get().status());
        assertEquals(LedgerDispatchStatus.MARK_DISPATCH, result.get().ledgerDispatchStatus());

        ArgumentCaptor<DocumentPublishCommand> command = ArgumentCaptor.forClass(DocumentPublishCommand.class);
        verify(eventPublisher).publishEvent(command.capture());
        assertEquals("doc1", command.getValue().documentId());
        assertEquals(1, command.getValue().slots().size());
        // PII-free: the record has no email/label/filename fields at all; belt-and-braces on the serialised form
        assertFalse(command.getValue().toString().contains("q3-report.pdf"));
        assertFalse(command.getValue().toString().contains("canary-recipient-label"));
    }

    @Test
    void publishRejectedWhenIpfsUnavailable() {
        when(ipfsAvailability.getIfAvailable()).thenReturn(null);

        Either<ProblemDetail, DocumentView> result = service.publish("doc1");

        assertTrue(result.isLeft());
        assertEquals(503, result.getLeft().getStatus());
        assertEquals(VaultProblems.DOCUMENT_PUBLISHING_UNAVAILABLE, result.getLeft().getTitle());
    }

    @Test
    void publishRejectedWhenAlreadyPublished() {
        VaultDocumentEntity doc = draftDoc();
        doc.setStatus(VaultDocumentStatus.PUBLISHED);
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(ipfsAvailability.getIfAvailable()).thenReturn(() -> true);

        Either<ProblemDetail, DocumentView> result = service.publish("doc1");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.ALREADY_PUBLISHED, result.getLeft().getTitle());
    }
```

`DocumentLedgerUpdateHandlerTest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishedEvent;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;

@ExtendWith(MockitoExtension.class)
class DocumentLedgerUpdateHandlerTest {

    @Mock
    private VaultDocumentRepository documentRepository;
    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DocumentLedgerUpdateHandler handler;

    private LedgerUpdatedEvent event(LedgerUpdateType type, LedgerDispatchStatus status, Set<BlockchainReceipt> receipts) {
        return LedgerUpdatedEvent.builder()
                .organisationId("org1")
                .type(type)
                .statusUpdates(Set.of(new LedgerStatusUpdate("doc1", status, null, receipts)))
                .build();
    }

    @Test
    void ignoresNonDocumentUpdates() {
        handler.handleLedgerUpdatedEvent(event(LedgerUpdateType.REPORT, LedgerDispatchStatus.DISPATCHED, Set.of()));

        verifyNoInteractions(documentRepository);
    }

    @Test
    void mapsReceiptsToTxHashAndCid() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        handler.handleLedgerUpdatedEvent(event(LedgerUpdateType.DOCUMENT, LedgerDispatchStatus.DISPATCHED,
                Set.of(new BlockchainReceipt("CARDANO_L1", "tx-hash-1"),
                        new BlockchainReceipt("IPFS", "bafy-cid-1"))));

        assertEquals("tx-hash-1", doc.getTxHash());
        assertEquals("bafy-cid-1", doc.getIpfsCid());
        assertEquals(LedgerDispatchStatus.DISPATCHED, doc.getLedgerDispatchStatus());
        verify(documentRepository).save(doc);
    }

    @Test
    void finalizedUpdateFiresPublishedEvent() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(keyRepository.findAllById(any())).thenReturn(List.of());

        handler.handleLedgerUpdatedEvent(event(LedgerUpdateType.DOCUMENT, LedgerDispatchStatus.FINALIZED, Set.of()));

        verify(eventPublisher).publishEvent(any(DocumentPublishedEvent.class));
    }

    @Test
    void repeatedFinalizedUpdateDoesNotRefireThePublishedEvent() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED); // already finalized earlier
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        handler.handleLedgerUpdatedEvent(event(LedgerUpdateType.DOCUMENT, LedgerDispatchStatus.FINALIZED, Set.of()));

        verifyNoInteractions(eventPublisher);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultDocumentServiceTest*" --tests "*DocumentLedgerUpdateHandlerTest*"`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement events, problems, service, controller, handler**

`DocumentPublishCommand.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.events;

import java.util.List;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * Publish request handed to blockchain_publisher. PII-FREE BY DESIGN (spec B5 #3): the IPFS document
 * and L1 metadata are generated exclusively from these fields, so nothing here may ever carry e-mails,
 * recipient labels, key ids, file names, descriptions, or account ids. Enforced by tests in Task 12.
 */
@DomainEvent
public record DocumentPublishCommand(String organisationId,
                                     String documentId,
                                     int envelopeVersion,
                                     String contentHash,
                                     String plaintextHash,
                                     String payloadNonce,
                                     String ciphertextBase64,
                                     List<PublishSlot> slots) {

    public record PublishSlot(String ephemeralPub, String wrappedDek) {
    }
}
```

`DocumentPublishedEvent.java`:

```java
package org.cardanofoundation.lob.app.document_vault.domain.events;

import java.util.Set;

import org.jmolecules.event.annotation.DomainEvent;

/** Fired when a published document reaches FINALIZED on-chain. Metadata-minimized (no content, no e-mails). */
@DomainEvent
public record DocumentPublishedEvent(String documentId, String organisationId, Set<String> recipientAccountIds) {
}
```

`VaultProblems.serviceUnavailable(...)` and the `DOCUMENT_PUBLISHING_UNAVAILABLE` constant already exist (added in Task 4) — nothing to add here.

Add these imports to `VaultDocumentService.java`:

```java
import java.time.LocalDateTime;

import org.springframework.beans.factory.ObjectProvider;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
```

Then add to `VaultDocumentService` (new dependency field: `private final ObjectProvider<IpfsAvailability> ipfsAvailability;`):

```java
    public Either<ProblemDetail, DocumentView> publish(String documentId) {
        IpfsAvailability ipfs = ipfsAvailability.getIfAvailable();
        if (ipfs == null || !ipfs.isAvailable()) {
            return Either.left(VaultProblems.serviceUnavailable(VaultProblems.DOCUMENT_PUBLISHING_UNAVAILABLE,
                    "Document publishing requires a configured IPFS publisher; none is available in this deployment."));
        }
        Optional<VaultDocumentEntity> documentM = documentRepository.findById(documentId);
        if (documentM.isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();
        if (!securityHelper.canUserAccessOrg(document.getOrganisationId())) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(document.getOrganisationId())));
        }
        if (document.getStatus() != VaultDocumentStatus.DRAFT) {
            return Either.left(VaultProblems.conflict(VaultProblems.ALREADY_PUBLISHED,
                    "Document %s is already published.".formatted(documentId)));
        }

        document.setStatus(VaultDocumentStatus.PUBLISHED);
        document.setPublishedAt(LocalDateTime.now());
        document.setLedgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH);
        VaultDocumentEntity saved = documentRepository.save(document);

        eventPublisher.publishEvent(new DocumentPublishCommand(
                saved.getOrganisationId(),
                saved.getId(),
                saved.getEnvelopeVersion(),
                saved.getContentHash(),
                saved.getPlaintextHash(),
                saved.getPayloadNonce(),
                Base64.getEncoder().encodeToString(saved.getCiphertext()),
                saved.getSlots().stream()
                        .map(slot -> new DocumentPublishCommand.PublishSlot(slot.getEphemeralPub(), slot.getWrappedDek()))
                        .toList()));

        return Either.right(toView(saved));
    }
```

(The publish method reuses Task 8's `toView(VaultDocumentEntity)` shown there.)

Add to `VaultDocumentController`:

```java
    @Operation(description = "Publish a draft document: encrypted envelope to IPFS, manifest to Cardano L1 (label 1447, type DOCUMENT). Requires IPFS; locks the document forever. Manager or admin only.")
    @PostMapping(value = "/documents/{documentId}/publish", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<Object> publish(@PathVariable String documentId) {
        return Responses.respond(documentService.publish(documentId), HttpStatus.OK);
    }
```

Add the constant next to `ALL_ROLES` in `VaultDocumentController`:

```java
    /**
     * Anchoring on-chain is irreversible, so it is gated more narrowly than everything else — the
     * platform's existing separation of duties. Verified precedents: funding's `publishEvent`
     * ("Publish an event to the blockchain") is manager-or-admin; `ReportingController.publish` and
     * `AccountingCoreResource.approveTransactionsPublish` are manager-only. Auditor is never allowed
     * to publish anywhere in this platform, and neither is accountant on a dispatch action.
     *
     * Consequence, accepted: an accountant can upload a draft but needs a manager to publish it.
     */
    private static final String PUBLISH_ROLES =
            "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())";
```

Pin the gate with a reflective test — `.../resource/VaultDocumentControllerSecurityTest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.resource;

import org.junit.jupiter.api.Test;

import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Why reflection and not a live 403: method security is switched on by support's SecurityConfig,
 * which is @ConditionalOnProperty(keycloak.enabled=true). The module's tests run with Keycloak
 * DISABLED — there is no `securityConfig` bean and @PreAuthorize is inert — so an "accountant gets
 * 403" test here would pass no matter what the annotation said, which is worse than no test.
 *
 * What CAN regress is the expression itself (someone widening it to ALL_ROLES). That is what this
 * pins. End-to-end role enforcement is a deployment concern, as for every other @PreAuthorize here.
 */
class VaultDocumentControllerSecurityTest {

    @Test
    void publishIsRestrictedToManagerAndAdmin() throws NoSuchMethodException {
        PreAuthorize annotation = VaultDocumentController.class
                .getMethod("publish", String.class)
                .getAnnotation(PreAuthorize.class);

        assertNotNull(annotation, "publish must be role-gated: anchoring on-chain is irreversible");
        assertEquals("hasRole(@securityConfig.getManagerRole()) "
                        + "or hasRole(@securityConfig.getAdminRole())",
                annotation.value());
    }
}
```

`DocumentLedgerUpdateHandler.java` (mirrors `funding/.../service/SpendingEventLedgerUpdateHandler.java`):

```java
package org.cardanofoundation.lob.app.document_vault.service;

import java.util.HashSet;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishedEvent;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentLedgerUpdateHandler {

    private final VaultDocumentRepository documentRepository;
    private final VaultKeyRepository keyRepository;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Async
    @Transactional
    public void handleLedgerUpdatedEvent(LedgerUpdatedEvent event) {
        if (event.getType() != LedgerUpdateType.DOCUMENT) {
            return;
        }
        log.info("Received document ledger update for organisation:{}, updates:{}",
                event.getOrganisationId(), event.getStatusUpdates().size());

        for (LedgerStatusUpdate update : event.getStatusUpdates()) {
            documentRepository.findById(update.getId()).ifPresentOrElse(
                    document -> apply(document, update),
                    () -> log.debug("Ignoring ledger update for unknown document: {}", update.getId()));
        }
    }

    private void apply(VaultDocumentEntity document, LedgerStatusUpdate update) {
        // capture BEFORE overwriting: DocumentPublishedEvent must fire exactly once, on the FIRST finality
        boolean firstFinality = update.getStatus() == LedgerDispatchStatus.FINALIZED
                && document.getLedgerDispatchStatus() != LedgerDispatchStatus.FINALIZED;

        document.setLedgerDispatchStatus(update.getStatus());
        document.setLedgerDispatchError(update.getLedgerDispatchStatusErrorReason());
        for (BlockchainReceipt receipt : update.getBlockchainReceipts()) {
            if ("IPFS".equals(receipt.getType())) {
                document.setIpfsCid(receipt.getHash());
            } else if (receipt.getHash() != null) {
                document.setTxHash(receipt.getHash());
            }
        }
        documentRepository.save(document);

        if (firstFinality) {
            Set<String> keyIds = new HashSet<>();
            document.getSlots().forEach(slot -> keyIds.add(slot.getKeyId()));
            Set<String> recipientAccountIds = new HashSet<>();
            keyRepository.findAllById(keyIds).forEach(key -> recipientAccountIds.add(key.getAccountId()));
            eventPublisher.publishEvent(new DocumentPublishedEvent(
                    document.getId(), document.getOrganisationId(), recipientAccountIds));
        }
    }
}
```

- [ ] **Step 4: Run the unit tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*VaultDocumentServiceTest*" --tests "*DocumentLedgerUpdateHandlerTest*"`
Expected: PASS.

- [ ] **Step 5: Write the vault-side publish integration test**

`VaultPublishIntegrationTest.java` — full vault flow with a stubbed availability bean and a command-capturing listener; the ledger-update handler is invoked synchronously (direct call bypasses `@Async`):

```java
package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;
import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentView;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationRepository;

@SpringBootTest
@ContextConfiguration(classes = {DocumentVaultContextIntegrationTest.TestConfig.class,
        VaultPublishIntegrationTest.PublishTestConfig.class})
@ActiveProfiles("test")
@Transactional
class VaultPublishIntegrationTest {

    private static final String ORG_ID = "org-publish";
    private static final String CANARY_EMAIL = "canary-mail@example.org";

    @TestConfiguration
    static class PublishTestConfig {

        static final List<DocumentPublishCommand> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        public IpfsAvailability testIpfsAvailability() {
            return () -> true;
        }

        @Bean
        public PublishCommandCapture publishCommandCapture() {
            return new PublishCommandCapture();
        }

        static class PublishCommandCapture {
            @EventListener
            public void on(DocumentPublishCommand command) {
                CAPTURED.add(command);
            }
        }
    }

    @Autowired
    private VaultKeyService keyService;
    @Autowired
    private RecipientResolutionService resolutionService;
    @Autowired
    private VaultDocumentService documentService;
    @Autowired
    private DocumentLedgerUpdateHandler ledgerUpdateHandler;
    @Autowired
    private OrganisationRepository organisationRepository;

    @BeforeEach
    void setUp() {
        PublishTestConfig.CAPTURED.clear();
        organisationRepository.saveAndFlush(Organisation.builder()
                .id(ORG_ID)
                .name("Publish Org")
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

    @Test
    void publishFlowLocksDocumentAndCommandCarriesNoPii() {
        RegisterKeyRequest keyRequest = new RegisterKeyRequest();
        keyRequest.setOrganisationId(ORG_ID);
        keyRequest.setLabel("laptop");
        keyRequest.setPublicKey("a".repeat(64));
        keyRequest.setEmail(CANARY_EMAIL);
        String keyId = keyService.registerKey(keyRequest).get().keyId();

        UploadDocumentRequest upload = new UploadDocumentRequest();
        upload.setOrganisationId(ORG_ID);
        upload.setEnvelopeVersion(1);
        upload.setFileName("very-secret-filename.pdf");
        upload.setPlaintextHash("0".repeat(64));
        UploadDocumentRequest.PayloadRequest payload = new UploadDocumentRequest.PayloadRequest();
        payload.setCiphertext(Base64.getEncoder()
                .encodeToString("ciphertext-bytes".getBytes(StandardCharsets.UTF_8)));
        payload.setNonce("0".repeat(24));
        upload.setPayload(payload);
        UploadDocumentRequest.SlotRequest slot = new UploadDocumentRequest.SlotRequest();
        slot.setKeyId(keyId);
        slot.setRecipientRef("canary-recipient-label");
        slot.setEphemeralPub("b".repeat(64));
        slot.setWrappedDek("c".repeat(96));
        upload.setSlots(List.of(slot));
        String documentId = documentService.upload(upload).get().documentId();

        // publish
        DocumentView published = documentService.publish(documentId).get();
        assertEquals(VaultDocumentStatus.PUBLISHED, published.status());
        assertEquals(LedgerDispatchStatus.MARK_DISPATCH, published.ledgerDispatchStatus());

        // the command that will feed IPFS/L1 carries no PII
        assertEquals(1, PublishTestConfig.CAPTURED.size());
        String serialisedCommand = PublishTestConfig.CAPTURED.get(0).toString();
        assertFalse(serialisedCommand.contains(CANARY_EMAIL));
        assertFalse(serialisedCommand.contains("very-secret-filename"));
        assertFalse(serialisedCommand.contains("canary-recipient-label"));
        assertFalse(serialisedCommand.contains(keyId));

        // republish rejected, delete locked
        assertTrue(documentService.publish(documentId).isLeft());
        assertTrue(documentService.delete(documentId).isPresent());

        // simulate the publisher's status-back (handler called synchronously)
        ledgerUpdateHandler.handleLedgerUpdatedEvent(LedgerUpdatedEvent.builder()
                .organisationId(ORG_ID)
                .type(LedgerUpdateType.DOCUMENT)
                .statusUpdates(Set.of(new LedgerStatusUpdate(documentId, LedgerDispatchStatus.FINALIZED, null,
                        Set.of(new BlockchainReceipt("CARDANO_L1", "tx-1"),
                                new BlockchainReceipt("IPFS", "bafy-1")))))
                .build());

        DocumentView finalized = documentService.list(ORG_ID, DocumentDirection.SENT, null, null, Pageable.unpaged())
                .get().content().get(0);
        assertEquals(LedgerDispatchStatus.FINALIZED, finalized.ledgerDispatchStatus());
        assertEquals("tx-1", finalized.txHash());
        assertEquals("bafy-1", finalized.ipfsCid());
    }
}
```

- [ ] **Step 6: Run everything, commit**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test`
Expected: PASS.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "feat(document_vault): publish endpoint with IPFS gating and ledger status handling"
```

---

### Task 12: `blockchain_publisher` — document publishable (mandatory IPFS + 1447 `DOCUMENT` manifest)

**Files** (all in `blockchain_publisher` unless noted; mirror the spending-event publishable — the complete recent example of adding a type, see the `CardanoPublishable` javadoc: "Adding a fourth type therefore means implementing this interface (plus an entity + migration); the engine itself never changes"):
- Modify: `blockchain_publisher/build.gradle.kts` — add `implementation(project(":document_vault"))` and `testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")`
- Create: `src/main/resources/db/migration/postgresql/common/V1.6_200_9__add_document_publishable.sql` (re-check first: `git grep -l "200_9" -- '*.sql' || echo FREE`)
- Create: `.../domain/entity/documents/DocumentEntity.java` (+ embeddable slot) — mirrors `.../domain/entity/spending/SpendingEventEntity.java` (same `PublishableEntity` base + `L1SubmissionData` embeddable), but NOT `@Audited` (ciphertext must not get `_aud` copies)
- Create: `.../repository/DocumentEntityRepository.java` + `.../repository/DocumentEntityRepositoryGateway.java` — mirror `SpendingEventEntityRepository`/`...Gateway` (locking via `lockedAt`, `findFreeByStatus`, `findDispatchedThatAreNotFinalizedYet`, lock timeout `lob.blockchain_publisher.dispatcher.lock_timeout:PT3H`)
- Create: `.../service/publish/module/document/DocumentConverter.java` — `DocumentPublishCommand` → `DocumentEntity`, field-by-field, slots in order
- Create: `.../service/publish/module/document/DocumentIpfsSerialiser.java`
- Create: `.../service/publish/module/document/DocumentMetadataSerialiser.java`
- Create: `.../service/publish/module/document/DocumentL1TransactionCreator.java`
- Create: `.../service/publish/module/document/DocumentPublishable.java`
- Modify: `.../service/event_handle/BlockchainPublisherEventHandler.java` + `.../service/BlockchainPublisherService.java`
- Modify: `.../service/event_publish/LedgerUpdatedEventPublisher.java` — additive overload for extra receipts (the existing `toStatusUpdate` hardcodes a single `CARDANO_L1` receipt, so the IPFS receipt cannot ride through the current 3-arg `send`)
- Modify: `.../config/TransactionSubmissionConfig.java` — `@Bean documentL1TransactionCreator(...)`
- Create: `.../service/ipfs/IpfsAvailabilityProvider.java`
- Test: `DocumentIpfsSerialiserTest`, `DocumentMetadataSerialiserTest`, `DocumentL1TransactionCreatorTest`, `DocumentPublishCommandPiiTest` (all under `blockchain_publisher/src/test/...`)

**Interfaces:**
- Consumes: `DocumentPublishCommand` (Task 11), `Optional<IpfsPublisher>` (existing), `LedgerUpdatedEventPublisher` (existing), `LedgerUpdateType.DOCUMENT` + `IpfsAvailability` (Task 10).
- Produces: `LedgerUpdatedEvent{type=DOCUMENT}` whose `LedgerStatusUpdate` receipts include `{"CARDANO_L1", txHash}` AND `{"IPFS", cid}`; the 1447 `DOCUMENT` metadata; the IPFS envelope document; the `IpfsAvailability` bean the vault gates on.

The two formats are **normative from the spec** ("Publishing — flow and formats") — copy them exactly.

- [ ] **Step 1: Write the failing serialiser tests**

`DocumentIpfsSerialiserTest.java` — asserts the normative envelope document:

```java
package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;

class DocumentIpfsSerialiserTest {

    private final DocumentIpfsSerialiser serialiser = new DocumentIpfsSerialiser(new ObjectMapper());

    static DocumentEntity fixture() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId("doc-1");
        entity.setOrganisationId("org-hash-1");
        entity.setEnvelopeVersion(1);
        entity.setContentHash("a".repeat(64));
        entity.setPlaintextHash("b".repeat(64));
        entity.setPayloadNonce("c".repeat(24));
        entity.setCiphertextBase64("Y2lwaGVydGV4dA==");
        entity.setSlots(List.of(
                new DocumentEntity.Slot("d".repeat(64), "e".repeat(96)),
                new DocumentEntity.Slot("f".repeat(64), "0".repeat(96))));
        return entity;
    }

    @Test
    void producesTheNormativeEnvelopeDocument() throws Exception {
        String json = serialiser.serialise(fixture());

        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals(1, root.get("version").asInt());
        assertEquals("REEVE_ENCRYPTED_DOCUMENT", root.get("type").asText());
        assertEquals("org-hash-1", root.get("org_id").asText());
        assertEquals("a".repeat(64), root.get("content_hash").asText());
        assertEquals("b".repeat(64), root.get("plaintext_hash").asText());
        assertEquals("Y2lwaGVydGV4dA==", root.get("payload").get("ciphertext").asText());
        assertEquals("c".repeat(24), root.get("payload").get("nonce").asText());
        assertEquals(2, root.get("slots").size());
        // slots carry ONLY crypto material — no identifiers of any kind (blueprint I6, spec B5 #3)
        root.get("slots").forEach(slot -> {
            List<String> fields = new ArrayList<>();
            slot.fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("ephemeral_pub", "wrapped_dek"), fields);
        });
        assertFalse(json.toLowerCase().contains("mail"));
        assertFalse(json.toLowerCase().contains("recipient"));
        assertFalse(json.toLowerCase().contains("file"));
    }
}
```

`DocumentMetadataSerialiserTest.java` — mocked `OrganisationPublicApi` returning a fixture `Organisation`, fixed `Clock`; assert the returned `MetadataMap` has `type = "DOCUMENT"` and its `data` map carries exactly `id`, `ipfs_cid`, `content_hash`, `plaintext_hash`, `envelope_version`, `slot_count` (use the map's key set — nothing else may be present), plus the standard `org` and `metadata` sections.

- [ ] **Step 2: Implement the serialisers**

`DocumentIpfsSerialiser.java`:

```java
package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;

/**
 * Serialises the encrypted envelope into the IPFS document (spec: "IPFS envelope document").
 * PII-free by construction: slots carry only ephemeral_pub + wrapped_dek; no e-mails, labels,
 * key ids, file names, or account ids exist in this format (spec B5 #3).
 */
@Service
@RequiredArgsConstructor
public class DocumentIpfsSerialiser {

    public static final int VERSION = 1;
    public static final String TYPE = "REEVE_ENCRYPTED_DOCUMENT";

    private final ObjectMapper objectMapper;

    public String serialise(DocumentEntity document) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", VERSION);
        root.put("type", TYPE);
        root.put("org_id", document.getOrganisationId());
        root.put("content_hash", document.getContentHash());
        root.put("plaintext_hash", document.getPlaintextHash());

        ObjectNode payload = root.putObject("payload");
        payload.put("ciphertext", document.getCiphertextBase64());
        payload.put("nonce", document.getPayloadNonce());

        ArrayNode slots = root.putArray("slots");
        document.getSlots().forEach(slot -> {
            ObjectNode slotNode = slots.addObject();
            slotNode.put("ephemeral_pub", slot.getEphemeralPub());
            slotNode.put("wrapped_dek", slot.getWrappedDek());
        });
        return root.toString();
    }
}
```

`DocumentMetadataSerialiser.java` — the L1 manifest. Copy the `org` and `metadata` section construction **verbatim from `API3MetadataSerialiser`** (`createMetadataSection(...)` + `serialiseOrganisation(...)` — the on-chain org/metadata sections must stay byte-compatible across types), which also brings that class's imports. On top of those, this file needs:

```java
import java.math.BigInteger;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
```

Then:

```java
    public static final String VERSION = "1.0";

    public MetadataMap serialiseToMetadataMap(DocumentEntity document, String ipfsCid, long creationSlot) {
        MetadataMap globalMetadataMap = MetadataBuilder.createMap();
        globalMetadataMap.put("metadata", createMetadataSection(creationSlot));   // copied from API3

        // resolve exactly as API3 does: serialiseOrganisation takes the publisher Organisation value object,
        // NOT a String — look up via OrganisationPublicApi, map with Organisation.fromOrganisationEntity
        var organisationEntity = organisationPublicApi.findByOrganisationId(document.getOrganisationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Organisation not found for id: %s".formatted(document.getOrganisationId())));
        globalMetadataMap.put("org", serialiseOrganisation(Organisation.fromOrganisationEntity(organisationEntity)));

        globalMetadataMap.put("type", "DOCUMENT");

        MetadataMap data = MetadataBuilder.createMap();
        data.put("id", document.getId());
        data.put("ipfs_cid", ipfsCid);
        data.put("content_hash", document.getContentHash());
        data.put("plaintext_hash", document.getPlaintextHash());
        data.put("envelope_version", BigInteger.valueOf(document.getEnvelopeVersion()));
        data.put("slot_count", BigInteger.valueOf(document.getSlots().size()));
        globalMetadataMap.put("data", data);

        return globalMetadataMap;
    }
```

- [ ] **Step 3: Entity, migration, repository, converter, creator, publishable, wiring**

Mirror the spending-event pieces one-to-one; the deltas that matter:

- **`DocumentEntity`** (publisher side): `id (document_id)`, `organisationId`, `envelopeVersion`, `contentHash`, `plaintextHash`, `payloadNonce`, `ciphertextBase64` (TEXT column — the publisher receives base64 and the IPFS serialiser needs base64; no reason to round-trip through bytea here), `ipfsCid` (nullable, set at dispatch), embeddable `Slot(ephemeralPub, wrappedDek)` as `@ElementCollection` (table `blockchain_publisher_document_slot` with `slot_index` order column), plus the same `L1SubmissionData` embeddable and `PublishableEntity` contract `SpendingEventEntity` uses. NOT `@Audited`, and `@ToString.Exclude` on `ciphertextBase64`.
- **Migration `V1.6_200_9__add_document_publishable.sql`**: `blockchain_publisher_document` + `blockchain_publisher_document_slot`; copy the L1-submission columns exactly from `V1.6_200_8__add_spending_event.sql`; no `_aud` tables.
- **`DocumentConverter.convertToDbDetached(DocumentPublishCommand command)`**: field-by-field; mirrors `SpendingEventConverter`.
- **`DocumentL1TransactionCreator`** — the genuinely new behavior (mandatory IPFS). Standalone creator in the style of `API3L1TransactionCreator` (reports), NOT `AbstractL1TransactionCreator` — the abstract creator treats IPFS as an optional offload and inlines data when it's absent, the exact opposite of the requirement. Beyond the imports carried over with the `API3L1TransactionCreator` skeleton, this file needs:

```java
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
```

Core logic:

```java
    public Either<ProblemDetail, /* the tx type the publishable expects, mirror API3 */> pullBlockchainTransaction(
            String organisationId, DocumentEntity document) {
        if (ipfsPublisher.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Document publishing requires IPFS; no IpfsPublisher is configured in this deployment.");
            problem.setTitle("DOCUMENT_PUBLISHING_UNAVAILABLE");
            return Either.left(problem);
        }
        String envelopeJson = documentIpfsSerialiser.serialise(document);
        return ipfsPublisher.get().publish(envelopeJson).flatMap(cid -> {
            document.setIpfsCid(cid);
            long creationSlot = /* same chain-tip lookup API3L1TransactionCreator uses */;
            MetadataMap metadataMap = documentMetadataSerialiser.serialiseToMetadataMap(document, cid, creationSlot);
            /* assemble + sign the tx exactly as API3L1TransactionCreator does with its metadata map:
               label from lob.l1.transaction.metadata_label:1447 — and NO KERI branch (documents don't use it) */
        });
    }
```

  Copy the class skeleton, chain-tip lookup, tx assembly and signing tail from `API3L1TransactionCreator`, dropping every KERI reference. IPFS upload happens at dispatch time (same place `AbstractL1TransactionCreator` does it), so a failed IPFS upload surfaces as a dispatch error and the publisher's normal retry machinery applies.
- **`LedgerUpdatedEventPublisher` overload (additive)** — the existing `toStatusUpdate` hardcodes `Set.of(new BlockchainReceipt(BLOCKCHAIN_TYPE, blockchainHash))`, so documents need an overload that unions extra receipts per entity; the existing 3-arg `send` delegates to it unchanged. The file already imports `Set`, `Transactional`, `PublishableEntity`, `LedgerUpdateType` and `BlockchainReceipt`; the only new import is:

```java
import java.util.function.Function;
```

```java
    @Transactional
    public <E extends PublishableEntity> void send(String organisationId,
                                                   LedgerUpdateType type,
                                                   Set<E> entities) {
        send(organisationId, type, entities, entity -> Set.of());
    }

    @Transactional
    public <E extends PublishableEntity> void send(String organisationId,
                                                   LedgerUpdateType type,
                                                   Set<E> entities,
                                                   Function<E, Set<BlockchainReceipt>> extraReceipts) {
        // identical body to today's send(...), but toStatusUpdate(entity) becomes
        // toStatusUpdate(entity, extraReceipts.apply(entity)) which unions the standard
        // CARDANO_L1 receipt with the supplied extras
    }
```

- **`DocumentPublishable implements CardanoPublishable<DocumentEntity>`**: `type() = "documents"`; one document per tx (`groupForDispatch` → singleton sets, like reports); `store`/`storeAll`/`findReadyToDispatch`/`findNotFinalizedYet` delegate to the gateway. Imports (on top of the `SpendingEventPublishable` skeleton this mirrors):

```java
import java.util.Set;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
```

`notifyLedgerUpdate` →

```java
    ledgerUpdatedEventPublisher.send(organisationId, LedgerUpdateType.DOCUMENT, entities,
            entity -> entity.getIpfsCid() == null
                    ? Set.of()
                    : Set.of(new BlockchainReceipt("IPFS", entity.getIpfsCid())));
```

  The vault handler maps `"IPFS"` to `ipfs_cid` and any other hash-bearing receipt to `tx_hash` (Task 11 contract).
- **`BlockchainPublisherEventHandler`**: the file already imports `@EventListener` and `@Async`; add the one new import

```java
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
```

  and the handler:

```java
    @EventListener
    @Async
    public void handleDocumentPublishCommand(DocumentPublishCommand command) {
        // do NOT log the command — it carries ciphertext; log ids only
        log.info("Received DocumentPublishCommand for organisation:{}, document:{}",
                command.organisationId(), command.documentId());
        blockchainPublisherService.storeDocumentForDispatchLater(command);
    }
```

  plus the corresponding `storeDocumentForDispatchLater` in `BlockchainPublisherService` (converter + gateway store-if-new, mirroring `storeEventsForDispatchLater`).
- **`TransactionSubmissionConfig`**: `@Bean documentL1TransactionCreator(...)` mirroring `api3L1TransactionCreator(...)` minus the KERI parameters.
- **`IpfsAvailabilityProvider`**:

```java
package org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs;

import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;

@Component
@RequiredArgsConstructor
public class IpfsAvailabilityProvider implements IpfsAvailability {

    private final Optional<IpfsPublisher> ipfsPublisher;

    @Override
    public boolean isAvailable() {
        return ipfsPublisher.isPresent();
    }
}
```

- [ ] **Step 4: Write the creator + PII tests**

`DocumentL1TransactionCreatorTest` (unit, Mockito): (a) empty `Optional<IpfsPublisher>` → `Either.left` with status 503 and title `DOCUMENT_PUBLISHING_UNAVAILABLE`; (b) stubbed publisher returning `Either.right("bafy-cid-1")` → the resulting metadata map's `data.ipfs_cid` equals `"bafy-cid-1"` and `entity.getIpfsCid()` is set; (c) stubbed publisher returning `Either.left(problem)` → creator propagates the left.

`DocumentPublishCommandPiiTest` (plain JUnit — reflective, cannot rot):

```java
package org.cardanofoundation.lob.app.blockchain_publisher.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;

/** Spec B5 #3: everything on the IPFS/L1 export path must be PII-free. */
class DocumentPublishCommandPiiTest {

    private static final Pattern FORBIDDEN =
            Pattern.compile("(?i).*(e?mail|recipient|account|label|file_?name|description|display).*");

    @Test
    void documentPublishCommandCarriesNoPiiFields() {
        for (var component : DocumentPublishCommand.class.getRecordComponents()) {
            if (component.getName().equals("organisationId")) {
                continue; // org id is public on-chain data, not PII
            }
            assertFalse(FORBIDDEN.matcher(component.getName()).matches(),
                    "PII-looking field on the publish path: " + component.getName());
        }
        for (var component : DocumentPublishCommand.PublishSlot.class.getRecordComponents()) {
            assertFalse(FORBIDDEN.matcher(component.getName()).matches());
        }
    }
}
```

`DocumentPublishArtifactsPiiCanaryTest` — the spec-mandated canary on the FINAL artifacts (not just the command): serialise both export formats from a fixture and assert no PII strings appear anywhere. The command type makes it impossible to even construct a PII-carrying input — this test documents and guards the formats if fields are ever added:

```java
package org.cardanofoundation.lob.app.blockchain_publisher.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

class DocumentPublishArtifactsPiiCanaryTest {

    private static final List<String> CANARIES = List.of(
            "canary-mail@example.org", "canary-recipient-label", "very-secret-filename");

    @Test
    void neitherIpfsDocumentNorL1MetadataCanCarryPii() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId("doc-1");
        entity.setOrganisationId("org-1");
        entity.setEnvelopeVersion(1);
        entity.setContentHash("a".repeat(64));
        entity.setPlaintextHash("b".repeat(64));
        entity.setPayloadNonce("c".repeat(24));
        entity.setCiphertextBase64("Y2lwaGVydGV4dA==");
        entity.setSlots(List.of(new DocumentEntity.Slot("d".repeat(64), "e".repeat(96))));

        String ipfsJson = new DocumentIpfsSerialiser(new ObjectMapper()).serialise(entity);

        OrganisationPublicApi organisationPublicApi = Mockito.mock(OrganisationPublicApi.class);
        Mockito.when(organisationPublicApi.findByOrganisationId("org-1")).thenReturn(Optional.of(Organisation.builder()
                .id("org-1").name("Org").taxIdNumber("TAX").countryCode("CH")
                .accountPeriodDays(365).currencyId("ISO_4217:CHF").reportCurrencyId("ISO_4217:CHF")
                .phoneNumber("x").city("x").postCode("x").province("x").address("x")
                .adminEmail("canary-mail@example.org") // the org admin e-mail exists server-side...
                .build()));
        // CBORMetadataMap does NOT override toString() — toJson() is the scannable serialised form
        String metadata = new DocumentMetadataSerialiser(organisationPublicApi, Clock.systemUTC())
                .serialiseToMetadataMap(entity, "bafy-1", 1L)
                .toJson();

        for (String canary : CANARIES) {
            assertFalse(ipfsJson.contains(canary), "PII canary in IPFS document: " + canary);
            assertFalse(metadata.contains(canary), "PII canary in L1 metadata: " + canary);
        }
    }
}
```

(`MetadataMap.toJson()` is the serialised content view — `CBORMetadataMap` does not override `toString()`. If `toJson()` output ever proves lossy for nested maps, walk the map's keys/values recursively instead; the assertion intent is: no canary string anywhere in either artifact, including the org section, which deliberately carries only the public org fields.)

Plus an ArchUnit rule over the publisher's document packages:

```java
package org.cardanofoundation.lob.app.blockchain_publisher.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/** Spec B5 #3: the IPFS/L1 formats are generated exclusively from these classes — no PII fields allowed. */
@AnalyzeClasses(packages = {
        "org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents",
        "org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document"})
class NoPiiOnDocumentPublishPathArchTest {

    @ArchTest
    static final ArchRule publishPathCarriesNoPii = ArchRuleDefinition.noFields()
            .should().haveNameMatching("(?i).*(e?mail|recipient|account|label|file_?name|description|display).*");
}
```

- [ ] **Step 5: Run publisher tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_publisher:test --tests "*Document*" --tests "*NoPii*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_publisher:spotlessApply
git add blockchain_publisher
git commit -m "feat(blockchain_publisher): document publishable with mandatory IPFS and 1447 DOCUMENT manifest"
```

---

### Task 13: Blueprint B5 enforcement — ArchUnit rules, payload-copy scan, log hygiene

**Files:**
- Create: `document_vault/src/test/java/org/cardanofoundation/lob/app/document_vault/architecture/NoSecretMaterialArchTest.java`
- Create: `document_vault/src/test/java/org/cardanofoundation/lob/app/document_vault/architecture/PlaintextAtRestScanIntegrationTest.java`

**Interfaces:**
- Consumes: everything built in Tasks 3–11; ArchUnit test dependency from Task 1. (The publisher-side PII rules live in Task 12; this task covers the vault module.)

- [ ] **Step 1: Write the ArchUnit rules (they must pass immediately — they gate future regressions)**

`NoSecretMaterialArchTest.java`:

```java
package org.cardanofoundation.lob.app.document_vault.architecture;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Blueprint B5 / invariant I5: no API schema may accept or return plaintext content, DEKs, KEKs,
 * PRF outputs, or private/unwrapped keys. These rules are a naming-discipline gate: any new DTO
 * field that even looks like secret material fails CI and forces an explicit review.
 * Allowed by design: plaintextHash (commitment), wrappedDek (encrypted), publicKey/ephemeralPub.
 */
@AnalyzeClasses(packages = "org.cardanofoundation.lob.app.document_vault")
class NoSecretMaterialArchTest {

    /** Bare names that always denote secret material. Compared case-insensitively. */
    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "dek", "kek", "plaintext", "privatekey", "prf", "prfoutput", "secret", "unwrappedkey", "contentkey");

    private static final ArchCondition<JavaField> NOT_BE_SECRET_MATERIAL =
            new ArchCondition<>("not be named like secret material (blueprint I5)") {
                @Override
                public void check(JavaField field, ConditionEvents events) {
                    String name = field.getName().toLowerCase();
                    boolean forbidden = FORBIDDEN_FIELD_NAMES.contains(name)
                            || (name.contains("plaintext") && !name.equals("plaintexthash"))
                            || name.contains("privatekey")
                            || name.contains("unwrapped");
                    if (forbidden) {
                        events.add(SimpleConditionEvent.violated(field,
                                "Field %s.%s looks like secret material — forbidden by blueprint I5"
                                        .formatted(field.getOwner().getName(), field.getName())));
                    }
                }
            };

    @ArchTest
    static final ArchRule apiDtosCarryNoSecretMaterial = ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat()
            .resideInAnyPackage("..domain.request..", "..domain.view..", "..domain.card..")
            .should(NOT_BE_SECRET_MATERIAL);

    @ArchTest
    static final ArchRule entitiesCarryNoSecretMaterial = ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().resideInAPackage("..domain.entity..")
            .should(NOT_BE_SECRET_MATERIAL);

    private static final String ENVELOPE_VIEW =
            "org.cardanofoundation.lob.app.document_vault.domain.view.DocumentEnvelopeView";

    /**
     * Ciphertext leaves the API through exactly ONE view: DocumentEnvelopeView (the authorized
     * envelope-fetch endpoint, blueprint D2). Every other view stays ciphertext-free. Exact-name
     * match (class or its nested records, "$"-separated) — a substring match could be bypassed
     * by naming a new view "...DocumentEnvelopeViewX".
     */
    @ArchTest
    static final ArchRule onlyTheEnvelopeViewExposesCiphertext = ArchRuleDefinition.noFields()
            .that().areDeclaredInClassesThat().resideInAPackage("..domain.view..")
            .and().areDeclaredInClassesThat(DescribedPredicate.describe(
                    "outside DocumentEnvelopeView",
                    javaClass -> !javaClass.getFullName().equals(ENVELOPE_VIEW)
                            && !javaClass.getFullName().startsWith(ENVELOPE_VIEW + "$")))
            .should().haveNameMatching("(?i).*ciphertext.*");
}
```

- [ ] **Step 2: Run — expect immediate PASS, then prove the rule bites**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*NoSecretMaterialArchTest*"`
Expected: PASS.

Now temporarily add `private String dek;` to `DocumentUploadedView`'s package (e.g. a scratch class in `domain/view`), rerun, and confirm the rule FAILS with the I5 message. Delete the scratch class afterwards. This proves the gate is live — do not skip it.

- [ ] **Step 3: Write the at-rest + log scan test**

`PlaintextAtRestScanIntegrationTest.java` — the **payload-copy + in-transit scan**. Honest boundary (spec, B5 section): the server cannot verify that bytes labeled ciphertext are actually encrypted — that check is cryptographically impossible without key material and is the *frontend's* payload-capture gate in the blueprint. What this test proves is the full backend half, **through the real HTTP stack** (RANDOM_PORT + RestAssured, same as `accounting_reporting_core`'s `WebBaseIntegrationTest`): the request traverses servlet filters, the `OrganisationCheckInterceptor` (which reads the raw body), Jackson, controller, service, and JPA — and the payload bytes end up in exactly one place (the `ciphertext` column) and in no log line emitted anywhere along that path:

```java
package org.cardanofoundation.lob.app.document_vault.architecture;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationRepository;

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
                                "issuer", Map.of(
                                        "issuerId", "reeve-indexer-test",
                                        "algorithm", "Ed25519",
                                        "publicKey", "f".repeat(64)),
                                "signature", "a".repeat(128),
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
```

This test needs an issuer configured (otherwise the endpoint short-circuits with 503 before it ever inspects the card), so change the class's `@SpringBootTest` annotation to declare one. Annotation values must be compile-time constants, so the key is written out literally rather than built with `"f".repeat(64)` (which would not compile there):

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "lob.document_vault.card.issuers=reeve-indexer-test:"
                + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
```

Inside the test body `"f".repeat(64)` is ordinary code and is fine — it produces the same 64 characters. Also add the static import `org.hamcrest.Matchers.equalTo` for the RestAssured body matcher.

Note the ordering this pins down: the private-key check runs **before** the issuer/signature checks, so a card carrying key material is rejected even when its signature is garbage. A rejection that depended on the signature being valid first would leave a hole — anyone could post an unsigned card full of private key material and have the server parse it.

- [ ] **Step 4: Run both**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test --tests "*architecture*"`
Expected: PASS (ArchUnit rules + canary scan).

- [ ] **Step 5: Commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:spotlessApply
git add document_vault
git commit -m "test(document_vault): enforce no-secret-material invariant (blueprint B5/I5)"
```

---

### Task 14: Docs, on-chain format, full-suite verification

**Files:**
- Modify: `README.md` (module list)
- Modify: `db-tables.md`

**Interfaces:** none — closing housekeeping.

- [ ] **Step 1: Document the module**

`README.md` — add to the "Repository Structure" bullet list (after **Organisation**):

```markdown
- **Document Vault:** Backend half of passkey-gated end-to-end-encrypted document exchange. Stores an organisation-scoped addressbook of member encryption keys (with notification e-mail and a visible custody tier), opaque wrapped-key records for multi-device sync, and uploaded ciphertext envelopes with per-recipient key slots. New recipients are added by importing an Ed25519-signed key card issued by the Indexer — the signature is the trust anchor, never the importer's word. Documents can be published by a manager or admin: the encrypted envelope is pinned to IPFS and a manifest is anchored on Cardano L1 (metadata label 1447, type DOCUMENT); published documents are immutable and are verified independently by the Indexer. The server can never read document content, never accepts private key material, and e-mail addresses never leave the operator's custody (all enforced by architecture tests).
```

`db-tables.md` — add sections (before "Spring Data Envers"):

```markdown
### Document Vault
- `document_vault_key` - Encryption public-key / addressbook entry (one organisation per entry; notification e-mail; origin + assurance tier)
- `document_vault_wrapped_record` - Opaque wrapped-key record (multi-device sync)
- `document_vault_document` - Encrypted envelope (ciphertext + metadata + publish status)
- `document_vault_document_slot` - Per-recipient wrapped-DEK slot
```

and under the existing "### Blockchain Publisher" section:

```markdown
- `blockchain_publisher_document` - Encrypted document pending/after L1+IPFS publication
- `blockchain_publisher_document_slot` - Envelope slot (ephemeral pub + wrapped DEK)
```

`docs/onChainFormat.md` — add a new type section after "## Type: Funding" (before "## Glossary"), copying the normative formats from the spec:

```markdown
## Type: Document

The `DOCUMENT` type anchors an **end-to-end-encrypted document** published by an organisation. The encrypted
envelope itself is stored on IPFS; the on-chain record is a manifest referencing it. The operator and the
public can verify integrity (hashes, CID) but can never read content — decryption keys exist only on the
recipients' devices.

`data` is a manifest object:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Server-assigned document identifier (UUID) |
| `ipfs_cid` | string | Yes | IPFS CID of the encrypted envelope document |
| `content_hash` | string | Yes | SHA-256 of the raw ciphertext bytes (hex) |
| `plaintext_hash` | string | Yes | SHA-256 commitment over the plaintext, computed client-side (hex) |
| `envelope_version` | integer | Yes | Envelope wire-format version |
| `slot_count` | integer | Yes | Number of recipient slots in the referenced envelope |

The referenced IPFS document carries `version`, `type` (`REEVE_ENCRYPTED_DOCUMENT`), `org_id`,
`content_hash`, `plaintext_hash`, `payload` (`ciphertext` base64 + `nonce`), and `slots`
(each only `ephemeral_pub` + `wrapped_dek` — deliberately no recipient identifiers).

> **Note on validation**: as with `FUNDING` manifests, several rules are enforced programmatically:
> `org_id` in the IPFS document matching the on-chain `org.id`, `content_hash` matching the decoded
> `payload.ciphertext`, the CID matching the document bytes, and `slot_count` matching `slots.length`.
> The format intentionally contains no personal data (no e-mail addresses, recipient names/labels, or
> file names) — such data stays inside the Reeve deployment.

### Example: Document record

```json
{
  "1447": {
    "org": {
      "id": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
      "name": "Cardano Foundation",
      "currency_id": "ISO_4217:CHF",
      "country_code": "CH",
      "tax_id_number": "CHE-184477354"
    },
    "metadata": {
      "creation_slot": 12345,
      "timestamp": "2026-07-14T10:15:30Z",
      "version": "1.0"
    },
    "type": "DOCUMENT",
    "data": {
      "id": "0b0f7d1e-6f0a-4d9e-9d5e-1c2b3a4d5e6f",
      "ipfs_cid": "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
      "content_hash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "plaintext_hash": "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752",
      "envelope_version": 1,
      "slot_count": 2
    }
  }
}
```
```

- [ ] **Step 2: Contract-consistency check**

Diff every endpoint path, request/response field, error title, size cap, and default in the implemented code against `docs/documentVault.md` §4–§6. Fix code (or, with explicit approval, the contract) on any mismatch — the frontend is building against that document.

Pay particular attention to the items this revision added, because the frontend and the Indexer are both coding against them right now:
- the **key-card signing input** (§2.8.3) — the byte-for-byte cross-language contract; `KeyCardVerifierTest.signingInputIsLengthPrefixedInTheContractOrder` is its pin;
- `POST /cards/import` (§5.13) and its six error titles;
- `envelopeAccessible` + `recipients[]` on `GET /documents/{id}` (§5.9);
- `senderKeyIds` on `POST /recipients/resolve` (§5.4) and `SENDER_KEY_INVALID`;
- `assurance` / `origin` / `external` on every key and recipient view (§5.1–5.3);
- publish restricted to manager/admin (§4, §5.11).

- [ ] **Step 3: Run the affected modules' full test suites**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:build :support:build :blockchain_common:build :blockchain_publisher:build`
Expected: BUILD SUCCESSFUL — all tests green, spotless clean. (A full `./gradlew build` also works but takes much longer; run it if time permits.)

- [ ] **Step 4: Commit**

```bash
git add README.md db-tables.md docs/onChainFormat.md docs/documentVault.md
git commit -m "docs: document the document_vault module, its tables and the DOCUMENT on-chain type"
```

---

## Plan Self-Review (revised 2026-07-14 after scope change; original review + Codex rounds 1–3 retained in git history)

- **Spec coverage:** B1/addressbook incl. e-mail → Tasks 4–5; B2 → Task 6; B3 → Tasks 7 + 9 (retention, DRAFT-only); B4 incl. envelope fetch (D2) → Tasks 7–8 + `DocumentPublishedEvent` (Task 11); B5 incl. PII-export ban → Tasks 13 (vault) + 12 (publish path); PUB (publish flow, IPFS envelope document, L1 DOCUMENT manifest, IPFS gating, published lock) → Tasks 10–12 with formats normative from the spec; support hardening → Task 2; docs incl. `onChainFormat.md` DOCUMENT section → Task 14. Revocation is intentionally absent everywhere (product decision recorded in the spec).
- **Scope-change consistency sweep:** the only remaining revocation mentions are the deliberate "product decision" records; key fixtures set `email`; document fixtures/status default DRAFT; retention purges DRAFT only; delete requires DRAFT; listing exposes publish/dispatch state.
- **Round-10 scope change (2026-07-14, second wave):** one key ↔ one organisation (join table dropped, `organisation_id` on the key row, uniqueness per (account, org, publicKey), no bindings endpoint); org-wide paged/filtered/sorted document listing via a single `search` JPQL + `PagedResponse` (funding pattern, module-local record); pagination on ALL list endpoints (keys/me, recipients, records, documents).
- **Codex round-4 findings addressed:** stale keys-tag/commit/test-name wording; publisher ciphertext storage aligned to `ciphertext_base64` TEXT in the spec; `LedgerUpdatedEventPublisher` receipts overload (the generic 3-arg send hardcodes the L1 receipt); `Organisation.builder().accountPeriodDays` (real Lombok name); first-FINALIZED guard + refire test in the ledger-update handler; ArchUnit/reflective PII regexes aligned to the spec's field-ban list; PII canary now asserts on the final IPFS + L1 artifacts.
- **Type consistency:** `RecipientKeyView` (now with `email`), `DocumentView` (now with status/dispatch/txHash/ipfsCid), `DocumentPublishCommand`/`PublishSlot`, receipt-type contract (`"IPFS"` → CID) cross-checked between Tasks 11 and 12; `LedgerStatusUpdate` all-args ctor and `LedgerUpdatedEvent.builder()` verified against `blockchain_common` sources.
- **Known judgment calls for the executor:** Flyway sequence re-checks (Task 1 Step 1 for `100_x`, Task 12 for `200_x`); Task 12 deliberately instructs mirroring named publisher classes (`SpendingEventEntity`/`...Publishable`/`API3L1TransactionCreator`) rather than reproducing their boilerplate — the genuinely new logic (both serialisers, mandatory-IPFS creator core, availability provider, PII tests) is fully coded; copy the org/metadata section builders verbatim from `API3MetadataSerialiser` for byte-compatibility; check how existing publishables assemble `LedgerStatusUpdate` receipts and add the `"IPFS"` receipt there.

### Round-14 scope change (2026-07-14, third wave — user flow, key cards, the Indexer)

- **User flow (contract §0)** is now written end-to-end and each step is mapped to the endpoint that serves it. Two steps did not work as designed and are fixed here rather than papered over:
  1. *"Click a document and see its detail"* — the org-wide list shows every org document, but `GET /documents/{id}` 404'd for non-participants, so most rows led to a dead end. The fetch now returns metadata + a key-material-free `recipients[]` to any org member, while the **envelope** (`payload` AND `slots`) stays participant-only (`envelopeAccessible`). Task 8.
  2. *"Choose recipients … or add new ones, persisted to the DB"* — impossible as stated: you cannot encrypt to a public key you do not have, and letting a user assert someone else's key is a key-substitution attack. Solved with **signed key cards** (new Task 4a): the Indexer issues them, the Ed25519 signature is the trust anchor, and an import creates a permanent addressbook entry. Deployments with no configured issuer get a clean `503` and simply have no cards.
- **Publish role gate:** manager or admin, verified against the platform's own precedents (funding `publishEvent` = manager/admin; reporting `publish` and `approveTransactionsPublish` = manager-only; auditor never publishes). Task 11. Consequence accepted: an accountant's draft needs a manager to publish it.
- **`senderKeyIds`** on resolve (Task 5) implements "choose a key to encrypt with"; absent/empty = all of the sender's keys, and it can never mean "none".
- **Key tiers (I2 amended):** "the Indexer creates keys for users" cannot coexist with the blueprint's *"no fallback tier, passkey-only"* — whoever mints a key has seen it. Rather than break the invariant silently, keys carry `assurance` (`PASSKEY`|`PORTABLE`) + `origin`, the API returns them everywhere, and the tier never upgrades. The honest claim for a portable key is "only someone holding this key can read this" — not "only Bob can".
- **The Indexer is NOT in this plan.** It is a separate deployable (contract §9) extending `_backend-services/cf-reeve-ledger-follower-app`, which already follows the chain, filters label 1447 and fetches IPFS. Its independence from Reeve's database is exactly what makes its verdicts worth anything, so folding it in here would weaken it. This module owes it only the card format and `POST /cards/import`, both frozen in the contract. Its plan is the next document.
- **Type consistency (this wave):** `KeyAssurance`/`KeyOrigin`/`CardSubjectType` (Task 4/4a) are used by `VaultKeyView`, `RecipientKeyView`, `DocumentEnvelopeView.RecipientView`, `VaultKeyEntity` and `KeyCardDto`; `VaultKeyService.toView`/`toRecipientView` are the single mapping shared by the key service, the resolver and the card importer, so the addressbook and the wrap-target set cannot drift apart; `VaultProblems.serviceUnavailable` is defined once (Task 4) and reused by publish (Task 11) and card import (Task 4a).

### Codex round-14 findings addressed (2026-07-14)

Codex returned DISAGREE on the first pass of this wave. All four blocking issues are fixed above:

1. **Wrapped DEKs leaked to non-participants (the real one).** The first draft returned `slots` to every org member, arguing a `wrappedDek` is useless without the private key and goes public on IPFS at publish anyway. That argument silently assumes the document *gets* published — it is false for a DRAFT, which may stay private forever. Fixed: `slots` moved inside the participant gate alongside `payload` (hence the flag's rename from `payloadAccessible` to **`envelopeAccessible`** — it gates the whole envelope). Non-participants get `recipients[]`, which answers "who can read this?" with no key material at all. A dedicated assertion (`assertNull(result.get().slots())`) now guards it.
2. **Contract self-contradiction.** §6's error catalog still said `DOCUMENT_NOT_FOUND` covers "caller is neither creator nor recipient" — the very rule 5.9 had just replaced. Catalog corrected: 404 now means unknown id *or* non-member only.
3. **Issuer config disagreed across the three documents** (`issuers[]` as `{id, publicKey}` objects in contract/spec vs. a comma-separated `id:hex` string in the plan's code). Contract and spec moved to the string form — the one that actually binds, given `@ConfigurationProperties` prefixes cannot contain the underscores this platform's property names use.
4. **Trailing "add these imports" notes** had drifted into real compile errors (`Collectors` in `RecipientResolutionService`, the key-tier enums in `VaultKeyService` and its tests, `KeyAssurance` in `DocumentEnvelopeView`). All folded into the actual import blocks; Task 3's `VaultKeyEntity` interface summary now lists the four new columns and flags that fixtures must set `origin`/`assurance`.

Also fixed from Codex's non-blocking list: `KeyCardVerifier` now rejects any `issuer.algorithm` other than `Ed25519` (a card must not be able to name one algorithm while the server verifies it under another), with a test.

### Codex round-15 findings addressed (2026-07-14)

1. **Import drift was still real.** My "all folded in" claim was overstated: Codex swept the whole plan and found four more blocks using the key-tier enums without importing them, and a scripted sweep of every full-file `java` block found three more (`VaultDocumentStatus`/`LedgerDispatchStatus` in the document entity, the `DocumentView` record, and the retention-job test). All are now in the import blocks, and the redundant trailing "imports to add" notes — the pattern that kept producing this bug — are gone. The sweep now reports clean.
2. **Issuer compromise needed a mitigation, not a disclaimer.** Codex was right that disclosure alone is not production-acceptable: key entries are permanent, and `resolve` includes *every* key an account has, so a single hostile key injected with a stolen issuer key would silently earn a slot in **every future document** addressed to its victim. Fixed with **issuer-level containment** (contract §2.8.5): every key stores the `issuerId` that vouched for it, and the addressbook and resolve return an `INDEXER_ISSUED` key only while that issuer is still in `lob.document_vault.card.issuers`. Drop the compromised issuer from the config and every key it introduced becomes un-addressable at once — no revocation endpoint, no status column, no migration, so it does not violate the "no key revocation" product decision (it revokes the *issuer*, not the key). The caller's own keys are filtered too: a `PORTABLE` key minted by a compromised issuer must be assumed known to the attacker. `/keys/me` is the sole exception — it still returns de-trusted keys, flagged `issuerTrusted: false`, because you need them to decrypt what you already received.
   Honest limit, stated in the contract rather than engineered around: **you cannot un-send.** Documents already encrypted to a hostile key stay readable. Only detection speed bounds that, which is what the Indexer's issued-card registry is for (§9.4) — diffing it against an org's addressbook exposes cards signed outside the issuance flow.

Consequences threaded through: `VaultKeyRepository.findByOrganisationId` is now unpaged (the trust filter must run before paging or pages come back short) with `PagedResponse.ofList` paging the survivors; `RecipientKeyView` gains `issuerId`; `VaultKeyView` gains `issuerId` + `issuerTrusted`; `VaultKeyService.toView` takes the trust flag as a parameter (so `CardImportService` needs no new dependency — it has just verified the issuer, so it knows the answer); both service tests stub `isTrustedIssuer(any()) → true` by default, because Mockito's `false` would otherwise filter every key out and fail the tests for a reason unrelated to what they check.

### Round 16 (Codex: DISAGREE → fixed)

1. **Import drift, actually fixed this time — and my checker was the bug.** Round 15's sweep script reported "clean" while ten package-declared blocks were still uncompilable. The reason is worth recording: the script validated a *hardcoded list* of types (the ones I already knew were missing), so it was structurally incapable of finding `Pageable`, `Page`, `LocalDateTime`, `EnumType`, `Enumerated`, `BaseRequest`, `Email` or `Nullable`. A checker that can only find what you already thought of is not a checker.

   The sweep is now generic (`docs/superpowers/plans/.import-sweep.py`): it extracts *every* capitalised identifier from each block, and resolves it against the block's imports, its own declarations, `java.lang`, same-package siblings **declared anywhere in the plan**, and — by walking the actual repo — same-package siblings that already exist in the codebase. It validates **fragments** too (the "add this method to an existing class" blocks), by attributing each to its target file and accumulating that file's imports across the whole document; for a file the plan modifies rather than creates, it loads the real file's imports from disk. It reports **0 unresolved blocks**, and a negative control (deleting one live import) makes it fire — so the clean result means something.

   Fixed as a result: `LocalDateTime`/`EnumType`/`Enumerated` in `VaultDocumentEntity`; `Page`/`Pageable` in all three repositories; `Pageable` in `VaultKeyServiceTest`, `PagedResponse` (needed by `ofList`), and both integration tests; `BaseRequest`/`Email` in `RegisterKeyRequest`; `Nullable` in `ResolveRecipientsRequest`; and `java.util.List` in `VaultKeyService` — which Codex had not flagged either. `VaultKeyEntity` also shed six imports left over from the deleted join table.

2. **The trailing "(Imports to add: …)" pattern is gone, not merely trimmed.** Every remaining out-of-band note became an explicit `import` code fence directly above the code that needs it, naming its target file (`VaultDocumentService`, `VaultDocumentController`, `VaultDocumentServiceTest`, `VaultKeyController`, and Task 12's publisher-side classes). Prose lists drift silently; a fence next to the code does not, and the sweep can check it. Where a fence sits against an *existing* repo class, it lists only what that class does not already import — verified against the files: `LedgerUpdatedEventPublisher` needs only `java.util.function.Function`, `BlockchainPublisherEventHandler` only `DocumentPublishCommand`, and `KeycloakSecurityHelper` needs nothing at all.

3. **Issuer containment is now stated identically in all three documents.** It was implemented in the plan but under-specified in the contract and spec, which is how a reader ends up building the un-contained version. The contract's `POST /keys` example carries `issuerId`/`issuerTrusted` with the rule spelled out (self-enrolled ⇒ `issuerId: null`, always trusted); `/cards/import` returns them and says the key stops being addressable the moment its issuer leaves the allowlist; the spec's addressbook entry carries `issuerId` and states the omission rule (plus *why* the repository read is unpaged); and both spec upload lines now require the issuer-trust re-check at upload with `422 SLOT_KEY_INVALID` — including the reason it cannot live in `resolve` alone: a client that resolved *before* a de-trust would otherwise still upload a slot wrapped to the compromised issuer's key, which is precisely the amplification the model exists to stop.

### Round 17 (Codex: DISAGREE → fixed)

1. **The checker had exactly the hole Codex probed for.** It resolved imports by *simple name only*, so swapping `org.springframework.data.domain.Pageable` for `com.example.DoesNotExist.Pageable` still passed. Two checks added:
   - **FQN validation.** Internal (`org.cardanofoundation.*`) imports must resolve to a package that exists in the repo, is declared by the plan, or is one of the three packages the plan explicitly creates (`blockchain_publisher.domain.entity.documents`, `blockchain_publisher.service.publish.module.document`, `blockchain_common.service`). Third-party imports must be a real dependency root or an FQN the repo already uses. Codex's bogus-FQN attack now fires.
   - **Static-import validation.** `assert*` and the Mockito verbs are lowercase, so the type sweep was blind to them by construction. They are now checked against each file's accumulated static imports, ignoring dot-qualified calls and methods the file declares itself (`KeyCardVerifier.verify` is its own method, not Mockito's).

   This found a real defect neither Codex nor I had caught: `VaultDocumentServiceTest` uses `assertFalse`, `assertNull` and `assertNotNull` without importing any of them. Fixed in Task 8's fence; Task 11's fence no longer repeats `assertFalse`.

   Three negative controls now fire (bogus FQN, deleted type import, deleted static import), and the plan reports **0 unresolved types, 0 bad FQNs, 0 missing static imports**. Two flags the checker raised turned out to be gaps in the *checker*, verified against the build files rather than assumed: rest-assured is a real dependency (`build.gradle.kts:163`), and `service/publish/module/document/` is a package this plan creates.

2. **The last stale import note is gone** (`Also add imports: …` in Task 8, made redundant by the fence above it). My round-16 claim that the pattern was eliminated was overstated by one instance — Codex was right to check rather than believe it.

3. **The Task 4 interface summary no longer contradicts the code it summarises.** It still advertised `toView(VaultKeyEntity)` and a `RecipientKeyView` without `issuerId`, both of which the issuer-containment work had changed. The summary now carries `toView(VaultKeyEntity, boolean issuerTrusted)`, `RecipientKeyView(… String issuerId, boolean external)` and `VaultKeyView(… String issuerId, boolean issuerTrusted …)`, with the reason `toView` takes the flag as a parameter (the card importer has just verified the issuer, so it already knows the answer) and why `toRecipientView` needs none (a de-trusted key never reaches a recipient view at all).

### Round 18 (Codex: DISAGREE on the checker → rebuilt)

Codex confirmed the stale import note and the interface-summary contradiction were fixed, then broke the checker three more ways. It was right to: a regex-plus-allowlist scheme **cannot** prove an import exists. `org.springframework.nope.Pageable` passed because my dependency-root allowlist accepted anything under `org.springframework`; `java.awt.List` passed because the class is real and I only matched simple names; a bare nested `PublishSlot` passed because I treated nested records as same-package siblings, which javac does not.

The checker no longer guesses. It now resolves **every import against the real classpath** by running `javap` — initially over the whole Gradle cache, and (after round 19) against each module's actual `testCompileClasspath`, so a package that does not exist cannot pass. That is a fact about the world, not an allowlist I maintain. On top of that:

- **Shadowing**: an import whose simple name this repo consistently takes from another package is flagged. `javap` cannot catch `java.awt.List` — the class exists, it is simply the wrong one — but the repo's own import corpus can.
- **Nested types**: declarations are tracked at brace depth, so only *top-level* types count as same-package siblings. A bare nested type now reports "needs `Outer.Nested` or an explicit import".
- **Plan-introduced dependencies**: coordinates the plan itself adds (`archunit-junit5`) are read out of the plan's own `build.gradle.kts` blocks, so they are not false-flagged for being absent from a cache they have never been in.

All five attacks now fire (bogus FQN, wrong package under a valid root, shadowed simple name, bare nested type, deleted type import — plus the deleted-static-import control from round 17), and the plan reports **0 unresolved types, 0 bad FQNs, 0 shadowed imports, 0 missing static imports**. One of my own negative controls was initially invalid — I injected the bare `PublishSlot` probe *into* the record that declares it, where it legitimately resolves — and a control that cannot fail proves nothing, so it was rewritten to reference the nested type from a different file in the same package, where it correctly fires.

Rebuilding the checker also surfaced a gap neither of us had flagged: ArchUnit is imported by two test classes but exists nowhere in the repo today. The plan does add `testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")` — in the new `document_vault/build.gradle.kts` it creates, and in the `blockchain_publisher/build.gradle.kts` modification — so the plan is self-consistent. To be precise about what was checked: those are the plan's *own snippets*, not the repo's current build files (`document_vault/build.gradle.kts` does not exist yet, and today's `blockchain_publisher/build.gradle.kts` has no ArchUnit). The executor must apply both build-file changes before those tests compile.

**`PagedResponse` summary corrected** (Codex, medium): the Task 4 interface block advertised only `of(Page, Function)`. It now also carries `ofList(List, Pageable)` and says why it exists — the issuer-trust predicate has no SQL form, so the addressbook and resolve read unpaged and page the filtered list in memory; paging first would return short pages.

### Round 19 (Codex: DISAGREE on the checker → two residual unsoundnesses closed)

Codex confirmed the `PagedResponse` fix and found no contract/spec/plan disagreement on issuer containment, key views or upload validation. It then broke the checker twice more, and both were real:

1. **Static imports named a member, but I only proved the owning *type* existed.** `import static org.junit.jupiter.api.Assertions.assertBogus;` passed. The checker now runs `javap -p` on the owning type and verifies the type actually **declares that member**; a nonexistent one is reported as `BAD STATIC MEMBER`.

2. **The classpath was the whole Gradle cache, not the module's.** So `com.bloxbean.cardano.yaci.store...` resolved — even though yaci-store is declared *only* by the out-of-tree `_backend-services/cf-reeve-ledger-follower-app`, which is not in `settings.gradle.kts` and is on no module's classpath. Approving an import from a jar the module does not depend on is exactly the failure mode the javap check was supposed to end. The checker now resolves each import against the **real per-module `testCompileClasspath`**, captured from Gradle itself into `.module-classpaths.txt` (`:funding` 178 entries, `:blockchain_publisher` 186, `:blockchain_common` 168, `:support` 210) and keyed by the owning package. `document_vault` does not exist yet, so `:funding`'s classpath stands in for it — its `build.gradle.kts` is a copy of funding's, and that substitution is stated rather than hidden.

Fixing (2) exposed a regression I had introduced myself: after switching to per-module resolution I captured only javap's `stdout`, but "class not found" goes to **stderr** — so the FQN check silently passed *everything* for one run. It looked like a clean bill of health and was nothing of the kind. Both streams are now read. Seven negative controls fire: bogus FQN, wrong package under a valid root, shadowed simple name, bare nested type, deleted type import, deleted static import, bogus static member, and cached-but-undeclared jar. The plan reports **0 across all five checks**.

**Two low findings from Codex, both fixed:**
- Task 5's `Consumes` omitted `KeyCardVerifier`, which the resolver injects to drop de-trusted issuers' keys. Added.
- My round-18 changelog claimed ArchUnit was "verified against the build files". That was an overclaim: what I verified was the plan's *own* build snippets — `document_vault/build.gradle.kts` does not exist yet, and today's `blockchain_publisher/build.gradle.kts` has no ArchUnit. The wording now says exactly that, and notes the executor must apply both build-file changes before those tests compile.

### Round 20 (Codex: DISAGREE → both skips replaced with resolution)

Codex passed both low findings and found no contract/spec/plan disagreement, then broke the checker on the two places where it *skipped* instead of *resolved*. A skip is a hole:

1. **Typos under a plan-introduced dependency root.** I waved through anything starting with `com.tngtech.archunit.`, so `com.tngtech.archunit.nope.DoesNotExist` passed — under the very namespace the plan's only new dependency lives in. Fixed by actually **resolving** the dependency: a Gradle configuration resolves `archunit-junit5:1.3.0` into `.plan-new-deps-cp.txt` (9 jars), which is appended to the module classpath. The typo now reports `BAD FQN`.

2. **Cross-module imports that exist but are not reachable.** `document_vault` importing `funding.domain.view.ProjectView` passed because the package exists *somewhere* in the repo — but `document_vault/build.gradle.kts` declares only `support`, `organisation` and `blockchain_common`, so javac would never see funding. **Existence is not reachability.** The checker now maps every package to its owning Gradle module and requires the importing module to declare `project(":thatModule")` — reading `document_vault`'s dependency block out of the plan itself, since the module does not exist yet. That import now reports `UNREACHABLE (document_vault does not declare project(":funding"))`.

**Staleness now fails loudly.** Codex noted the captured classpath could silently go out of date — which is the worst failure mode available, since it would report clean against a world that no longer exists. `.module-classpaths.txt` is hash-stamped against every build file, and the sweep **exits with an error** rather than reporting a comforting zero when they no longer match (verified by perturbing a build file).

**Residual gap, stated rather than papered over:** this validates imports, not semantics. Codex's `@String` probe (a class used as an annotation) resolves fine here and javac rejects it. That is compilation, not import resolution — a plan document is not compiled, and the executor's first `./gradlew test` catches it. This is where I stop hardening: the checker now answers "would these imports resolve in this module", which is the question a plan can actually be wrong about.

Nine negative controls fire: bogus FQN, wrong package under a valid root, typo under a plan-introduced dep, unreachable cross-module import, shadowed simple name, bare nested type, deleted type import, deleted static import, bogus static member, plus a stale-classpath refusal. The plan reports **0 across all six checks**.

### Round 21 (Codex: DISAGREE → plan-added dependencies are no longer global)

Codex accepted the `@String` judgement (imports, not semantics, is the right boundary for a plan document) and confirmed the final contract/spec/plan sweep is clean. It then found the last hole: **`.plan-new-deps-cp.txt` was appended to every module's classpath unconditionally**, so a probe putting `@AnalyzeClasses` in `:support` — which declares no ArchUnit dependency — passed. A dependency the plan adds to two modules is not a dependency every module has.

Fixed by scoping: the new-dep classpath is granted only to the modules whose build files actually add it, derived from the plan's own snippets (`:funding`, standing in for `document_vault`, and `:blockchain_publisher`). `:support` and `:blockchain_common` get nothing.

That fix exposed a second, quieter bug of mine underneath it. `imports_seen` was keyed by FQN alone, so the same import appearing in two different modules was **deduplicated to its first occurrence** — meaning Codex's probe was never even re-checked against `:support`'s classpath. Scoping the dependency was necessary but would have changed nothing on its own. Imports are now keyed by (import, owning package) and checked once per module.

Ten negative controls fire — bogus FQN, wrong package under a valid root, shadowed simple name, typo under a plan-introduced dep, unreachable cross-module import, bogus static member, cached-but-undeclared jar, deleted type import, deleted static import, bare nested type — plus the stale-classpath refusal. The plan reports **0 across all six checks**.
