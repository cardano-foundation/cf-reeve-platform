# Multi-tenant NetSuite Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single application-wide NetSuite configuration with per-organisation, database-backed configuration owned by the netsuite module, administered through the organisation module and delivered over the existing Kafka bridge.

**Architecture:** An admin writes credentials to the `organisation` module, which encrypts the private key (AES-256-GCM), commits a status projection row, and publishes `NetSuiteConfigUpsertedEvent`. The netsuite module stores the configuration, verifies it against NetSuite, and publishes `NetSuiteConfigAppliedEvent`; whichever organisation pod consumes that ACK writes the verdict onto the projection. Ingestion resolves a per-organisation `NetSuiteClient` from a registry keyed by `organisationId`.

**Tech Stack:** Java 21, Spring Boot, Spring Kafka, JPA/Hibernate, Flyway, vavr `Either`, Lombok, JUnit 5 + Mockito, AssertJ. Frontend: React 19, TanStack Query v5, Formik + Yup, MUI v7.

**Spec:** `docs/superpowers/specs/2026-08-13-multi-tenant-netsuite-configuration-design.md`

## Global Constraints

- **JDK 21.** Set `JAVA_HOME` to a JDK 21 install before any Gradle command; the default JDK 26 breaks Gradle's Kotlin DSL.
- **The `organisation` module must never read netsuite tables** (spec D3). No `import ...netsuite_altavia_erp_adapter...` may appear anywhere under `organisation/src/main`. Task 20 enforces this with a test.
- **The `netsuite_altavia_erp_adapter` module gains no `@RestController`** (spec D13).
- **No new Gradle module dependency edges.** `netsuite_altavia_erp_adapter → organisation` already exists and is the only edge used. `organisation` must NOT gain a dependency on `netsuite_altavia_erp_adapter`.
- **Encryption happens in the organisation service layer**, never in a JPA `AttributeConverter` (spec D11).
- **Events are published only after the projection transaction commits** (spec §6.1).
- **Secret material never enters the organisation schema.** The projection stores a SHA3 fingerprint only.
- **Event `toString()` must exclude the encrypted key** — `cf-reeve-application` publishers log whole events at INFO.
- Encryption key property: `lob.security.config-encryption.key`, env `LOB_CONFIG_ENCRYPTION_KEY`, Base64 of exactly 32 bytes.
- Envelope format: `v1:` + Base64(`iv‖ciphertext‖tag`), 96-bit IV, 128-bit tag, `AES/GCM/NoPadding`.
- Platform modules are libraries with no `@Service` annotations on service classes; beans are declared explicitly in `cf-reeve-application`'s `CFConfig`. Follow that convention for every new netsuite-module service class. The `organisation` module DOES use component scanning (`OrganisationModuleConfig` scans `org.cardanofoundation.lob.app.organisation`), so organisation classes use `@Service`/`@Repository`/`@RestController` normally.

## Spec deviations discovered during planning

Two spec statements are contradicted by the code and are corrected here. Both are recorded in the spec's §17 by Task 0.

1. **`netsuite-instance-id` is NOT per-organisation.** The spec listed it as a per-org field. In reality it is `CFConfig.NETSUITE_CONNECTOR_ID = "fEU237r9rqAPEGEFY1yr"`, a hardcoded connector identity, and it is the **primary key component** of `netsuite_adapter_code_mapping` — `TransactionConverter.getOrganisationIdFromTxLine` calls `codesMappingService.getCodeMapping(netsuiteInstanceId, txLine.subsidiary(), ORGANISATION)`. Making it per-organisation would orphan every existing code-mapping row and break subsidiary→organisation resolution. It stays a global constant.
2. **One NetSuite instance can already serve many organisations.** `TransactionConverter` resolves the owning organisation *per transaction line* from the subsidiary mapping, independently of the organisation the ingestion was triggered for. Per-organisation credentials therefore change *which NetSuite account is called*, not *how lines are attributed*. An ingestion triggered for org X can still emit lines attributed to org Y if the mapping says so. This is pre-existing behaviour, out of scope to change, and must be recorded as a known limitation.

---

## File Structure

**`cf-reeve-platform` — support**
- Create `support/src/main/java/org/cardanofoundation/lob/app/support/crypto/SecretCipher.java` — interface, so tests can stub it.
- Create `support/src/main/java/org/cardanofoundation/lob/app/support/crypto/AesGcmSecretCipher.java` — the implementation.
- Create `support/src/main/java/org/cardanofoundation/lob/app/support/crypto/SecretCipherConfig.java` — `@Configuration` declaring the bean, imported by both module configs.
- Create `support/src/test/java/org/cardanofoundation/lob/app/support/crypto/AesGcmSecretCipherTest.java`

**`cf-reeve-platform` — organisation** (owns the admin API and the projection)
- Create `domain/entity/NetSuiteConfigState.java`, `domain/entity/NetSuiteSyncState.java` (enum)
- Create `domain/request/NetSuiteConfigurationCreate.java`, `domain/request/NetSuiteConfigurationUpdate.java`
- Create `domain/view/NetSuiteConfigurationStatusView.java`
- Create `domain/event/netsuite/NetSuiteConfigUpsertedEvent.java`, `domain/event/netsuite/NetSuiteConfigAppliedEvent.java`, `domain/event/netsuite/NetSuiteConfigStatus.java` (enum)
- Create `repository/NetSuiteConfigStateRepository.java`
- Create `service/NetSuiteConfigAdminService.java` — validation, encryption, commit, publish-after-commit
- Create `service/NetSuiteConfigAckHandler.java` — consumes the ACK, writes the verdict
- Create `resource/NetSuiteConfigurationController.java`
- Create migration `db/migration/postgresql/common/V1.7_100_3_7__add_netsuite_config_state.sql`
- Modify `org/cardanofoundation/lob/app/config/OrganisationModuleConfig.java` — `@Import(SecretCipherConfig.class)`

**`cf-reeve-platform` — netsuite_altavia_erp_adapter** (owns the configuration)
- Create `domain/entity/NetSuiteConfigEntity.java`
- Create `repository/NetSuiteConfigRepository.java`
- Create `service/internal/NetSuiteConfigService.java` — upsert + verify + lookup
- Create `service/internal/NetSuiteClientRegistry.java` — per-org client cache
- Create `service/event_handle/NetSuiteConfigEventHandler.java`
- Modify `client/NetSuiteClient.java` — drop `@PostConstruct`, take PEM instead of file path
- Modify `service/internal/NetSuiteExtractionService.java`, `service/internal/NetSuiteReconcilationService.java` — resolve client per org
- Modify `service/event_handle/NetSuiteEventHandler.java` — unchanged signatures, no edit expected
- Create migration `db/migration/postgresql/common/V1.7_100_5_1__add_organisation_config.sql`
- Modify `org/cardanofoundation/lob/app/config/NetsuiteModuleConfig.java` — `@Import(SecretCipherConfig.class)`

**`cf-reeve-application`**
- Create `cf-application/src/main/java/org/cardanofoundation/lob/app/kafka/publisher/OrganisationKafkaPublisher.java`
- Create `cf-application/src/main/java/org/cardanofoundation/lob/app/kafka/consumer/OrganisationKafkaConsumer.java`
- Modify `kafka/publisher/NetsuiteKafkaPublisher.java`, `kafka/consumer/NetSuiteKafkaConsumer.java`
- Modify `cf_netsuite_altavia_erp_connector/.../config/CFConfig.java`
- Modify `cf-application/src/main/resources/application.yml`
- Modify `docker-compose.yml`, `docker-compose.lightweight.yml`, `docker-compose-kafka-ssl.yml`
- Rewrite `certs/netsuiteConfiguration.md`

**`cf-lob-frontend`**
- Modify `public/permissions.global.js`
- Create `src/libs/api-connectors/backend-connector-lob/api/netsuite-config/netsuite-config-api.{types,service}.ts`
- Modify `src/libs/api-connectors/backend-connector-lob/api/backendLobApi.ts`
- Create `src/libs/models/netsuite-config/{GetNetsuiteConfigStatus,CreateNetsuiteConfig,UpdateNetsuiteConfig}Model.service.ts`
- Create `src/modules/settings/views/netsuite-configuration/**`
- Modify `src/consts/routes/routes.consts.ts`, `src/modules/settings/settings.routes.tsx`, `NavigationSidebar.service.tsx`, `src/libs/translations/en-US.json`

---

# Phase 0 — Record the spec corrections

### Task 0: Correct the spec

**Files:**
- Modify: `docs/superpowers/specs/2026-08-13-multi-tenant-netsuite-configuration-design.md`

- [ ] **Step 1: Remove `netsuite-instance-id` from every per-org field list**

In §4, §8.1, §8.2 and §10, delete `netsuite_instance_id` / `netsuiteInstanceId` from the per-organisation column and payload lists. The remaining per-org fields are exactly: `base_url`, `token_url`, `client_id`, `certificate_id`, `private_key_encrypted`.

- [ ] **Step 2: Add both corrections to §17**

```markdown
- **`netsuiteInstanceId` stays global.** It is `CFConfig.NETSUITE_CONNECTOR_ID`, and it is the first component of
  `netsuite_adapter_code_mapping`'s primary key — `TransactionConverter.getOrganisationIdFromTxLine` looks up
  `(netsuiteInstanceId, subsidiary, ORGANISATION)` to decide which organisation a transaction line belongs to.
  Making it per-organisation would orphan every existing mapping row. It is adapter identity, not a credential.
- **Per-organisation credentials do not make attribution per-organisation.** A single NetSuite account can already
  serve many organisations: `TransactionConverter` resolves the owning organisation per transaction line from the
  subsidiary mapping, independently of the organisation whose ingestion was triggered. This change controls *which
  NetSuite account is called*, not *how lines are attributed*, so an ingestion triggered for org X can still emit
  lines attributed to org Y. Pre-existing behaviour; changing it is a separate piece of work.
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-08-13-multi-tenant-netsuite-configuration-design.md
git commit -m "docs: [LOB-2166] correct spec — netsuiteInstanceId stays global"
```

---

# Phase 1 — Encryption primitive (`support`)

### Task 1: `SecretCipher` and `AesGcmSecretCipher`

**Files:**
- Create: `support/src/main/java/org/cardanofoundation/lob/app/support/crypto/SecretCipher.java`
- Create: `support/src/main/java/org/cardanofoundation/lob/app/support/crypto/AesGcmSecretCipher.java`
- Test: `support/src/test/java/org/cardanofoundation/lob/app/support/crypto/AesGcmSecretCipherTest.java`

**Interfaces:**
- Produces: `SecretCipher.encrypt(String plaintext) -> String` (returns `v1:`-prefixed envelope), `SecretCipher.decrypt(String envelope) -> String`, `AesGcmSecretCipher(String base64Key)` constructor throwing `IllegalArgumentException` on a key that is not Base64 of exactly 32 bytes.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.support.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class AesGcmSecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsPlaintext() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);
        String secret = "-----BEGIN PRIVATE KEY-----\nMIIEvQ==\n-----END PRIVATE KEY-----";

        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void prefixesEnvelopeWithVersion() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);

        assertThat(cipher.encrypt("x")).startsWith("v1:");
    }

    @Test
    void producesADifferentEnvelopeEachTimeSoIvsAreNotReused() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);

        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);
        String envelope = cipher.encrypt("secret");
        char[] chars = envelope.toCharArray();
        chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> cipher.decrypt(new String(chars)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnUnknownEnvelopeVersion() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);

        assertThatThrownBy(() -> cipher.decrypt("v2:AAAA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsAKeyOfTheWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new AesGcmSecretCipher(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsAMissingKey() {
        assertThatThrownBy(() -> new AesGcmSecretCipher("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :support:test --tests '*AesGcmSecretCipherTest*'`
Expected: FAIL — `SecretCipher` / `AesGcmSecretCipher` do not exist (compilation error).

- [ ] **Step 3: Write the interface**

```java
package org.cardanofoundation.lob.app.support.crypto;

/**
 * Reversible encryption for secret configuration values that must travel through
 * domain events and be stored at rest. Implementations must be thread-safe.
 */
public interface SecretCipher {

    /**
     * @return a versioned envelope, never the raw ciphertext
     */
    String encrypt(String plaintext);

    /**
     * @throws IllegalArgumentException if the envelope version is unknown
     * @throws IllegalStateException    if authentication fails (tampered or wrong key)
     */
    String decrypt(String envelope);
}
```

- [ ] **Step 4: Write the implementation**

```java
package org.cardanofoundation.lob.app.support.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a random 96-bit IV per encryption.
 * <p>
 * Envelope: {@code v1:} + Base64(iv ‖ ciphertext ‖ tag). The version prefix exists so a future
 * key rotation can introduce {@code v2:} and still decrypt {@code v1:} values.
 */
public class AesGcmSecretCipher implements SecretCipher {

    private static final String VERSION_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException(
                    "Config encryption key is not set. Provide lob.security.config-encryption.key (LOB_CONFIG_ENCRYPTION_KEY).");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Config encryption key is not valid Base64.", e);
        }

        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "Config encryption key must decode to exactly 32 bytes, got %d.".formatted(keyBytes.length));
        }

        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(UTF_8));

            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret value", e);
        }
    }

    @Override
    public String decrypt(String envelope) {
        if (envelope == null || !envelope.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("Unsupported secret envelope version");
        }

        byte[] raw = Base64.getDecoder().decode(envelope.substring(VERSION_PREFIX.length()));
        if (raw.length <= IV_LENGTH_BYTES) {
            throw new IllegalStateException("Secret envelope is truncated");
        }

        byte[] iv = new byte[IV_LENGTH_BYTES];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH_BYTES);
        byte[] ciphertext = new byte[raw.length - IV_LENGTH_BYTES];
        System.arraycopy(raw, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(ciphertext), UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt secret value — wrong key or tampered data", e);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :support:test --tests '*AesGcmSecretCipherTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add support/src/main/java/org/cardanofoundation/lob/app/support/crypto/ support/src/test/java/org/cardanofoundation/lob/app/support/crypto/
git commit -m "feat: [LOB-2166] add AES-256-GCM SecretCipher to support"
```

### Task 2: `SecretCipherConfig` bean

**Files:**
- Create: `support/src/main/java/org/cardanofoundation/lob/app/support/crypto/SecretCipherConfig.java`
- Modify: `organisation/src/main/java/org/cardanofoundation/lob/app/config/OrganisationModuleConfig.java`
- Modify: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/config/NetsuiteModuleConfig.java`

**Interfaces:**
- Consumes: `AesGcmSecretCipher(String)` from Task 1.
- Produces: a single `SecretCipher` bean available to both modules in split and merged deployments.

- [ ] **Step 1: Write the configuration class**

`@ConditionalOnMissingBean` is essential: in the lightweight profile both module configs are active in one JVM and would otherwise declare two beans of the same type.

```java
package org.cardanofoundation.lob.app.support.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Imported by every module config that needs to encrypt or decrypt stored secrets.
 * Guarded with {@link ConditionalOnMissingBean} because more than one module config
 * may import it in a merged (single-JVM) deployment.
 */
@Configuration
public class SecretCipherConfig {

    @Bean
    @ConditionalOnMissingBean(SecretCipher.class)
    public SecretCipher secretCipher(@Value("${lob.security.config-encryption.key:}") String base64Key) {
        return new AesGcmSecretCipher(base64Key);
    }
}
```

- [ ] **Step 2: Import it from `OrganisationModuleConfig`**

Add the import and the annotation, leaving the existing `@ConditionalOnProperty` and `@ComponentScan` untouched:

```java
import org.springframework.context.annotation.Import;
import org.cardanofoundation.lob.app.support.crypto.SecretCipherConfig;

// ... on the class, alongside the existing annotations:
@Import(SecretCipherConfig.class)
```

- [ ] **Step 3: Import it from `NetsuiteModuleConfig`**

Apply the identical change to `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/config/NetsuiteModuleConfig.java`.

- [ ] **Step 4: Verify both modules still compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:compileJava :netsuite_altavia_erp_adapter:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add support/src/main/java/org/cardanofoundation/lob/app/support/crypto/SecretCipherConfig.java organisation/src/main/java/org/cardanofoundation/lob/app/config/OrganisationModuleConfig.java netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/config/NetsuiteModuleConfig.java
git commit -m "feat: [LOB-2166] expose SecretCipher bean to organisation and netsuite modules"
```

---

# Phase 2 — Event contracts (`organisation`)

### Task 3: Event classes and status enum

**Files:**
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/event/netsuite/NetSuiteConfigStatus.java`
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/event/netsuite/NetSuiteConfigUpsertedEvent.java`
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/event/netsuite/NetSuiteConfigAppliedEvent.java`
- Test: `organisation/src/test/java/org/cardanofoundation/lob/app/organisation/domain/event/netsuite/NetSuiteConfigUpsertedEventTest.java`

**Interfaces:**
- Produces: `NetSuiteConfigUpsertedEvent` with `getMetadata()`, `getOrganisationId()`, `getRevision()` (`long`), `getBaseUrl()`, `getTokenUrl()`, `getClientId()`, `getCertificateId()`, `getPrivateKeyEncrypted()` (nullable — null means "reuse stored key"), and `VERSION = "1.0"`. `NetSuiteConfigAppliedEvent` with `getOrganisationId()`, `getRevision()`, `getStoreStatus()`, `getValidationStatus()`, `getMessage()`, `VERSION = "1.0"`. Both built with Lombok `@Builder`.

- [ ] **Step 1: Write the failing test**

The only behaviour worth testing on a DTO is the one that has a security consequence: the encrypted key must not appear in `toString()`.

```java
package org.cardanofoundation.lob.app.organisation.domain.event.netsuite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

class NetSuiteConfigUpsertedEventTest {

    @Test
    void doesNotLeakTheEncryptedKeyInToString() {
        NetSuiteConfigUpsertedEvent event = NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, "admin"))
                .organisationId("org-1")
                .revision(3L)
                .baseUrl("https://example.restlets.api.netsuite.com")
                .tokenUrl("https://example.suitetalk.api.netsuite.com/token")
                .clientId("client-1")
                .certificateId("cert-1")
                .privateKeyEncrypted("v1:SUPERSECRETENVELOPE")
                .build();

        assertThat(event.toString())
                .doesNotContain("SUPERSECRETENVELOPE")
                .contains("org-1");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:test --tests '*NetSuiteConfigUpsertedEventTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the status enum**

```java
package org.cardanofoundation.lob.app.organisation.domain.event.netsuite;

public enum NetSuiteConfigStatus {
    SUCCESS,
    FAILED
}
```

- [ ] **Step 4: Write `NetSuiteConfigUpsertedEvent`**

`@ToString.Exclude` on the key is the constraint that matters. `@DomainEvent` matches the platform convention.

```java
package org.cardanofoundation.lob.app.organisation.domain.event.netsuite;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.jmolecules.event.annotation.DomainEvent;

import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

/**
 * Published by the organisation module after an admin create/update commits.
 * Consumed by the netsuite module, which owns the configuration.
 * <p>
 * A null {@code privateKeyEncrypted} means "reuse the key already stored for this
 * organisation" — the organisation module keeps no copy of it.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@Builder
@DomainEvent
public class NetSuiteConfigUpsertedEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotNull
    private String organisationId;

    private long revision;

    @NotNull
    private String baseUrl;

    @NotNull
    private String tokenUrl;

    @NotNull
    private String clientId;

    @NotNull
    private String certificateId;

    /** Nullable. {@code v1:}-prefixed envelope; never logged. */
    @ToString.Exclude
    private String privateKeyEncrypted;
}
```

- [ ] **Step 5: Write `NetSuiteConfigAppliedEvent`**

```java
package org.cardanofoundation.lob.app.organisation.domain.event.netsuite;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.jmolecules.event.annotation.DomainEvent;

import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

/**
 * Published by the netsuite module after it stores and verifies a configuration.
 * Consumed by whichever organisation pod is assigned the partition, which writes
 * the verdict onto the projection. Carries both outcomes in one round trip:
 * {@code storeStatus} drives sync_state, {@code validationStatus} drives netsuite_valid.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@Builder
@DomainEvent
public class NetSuiteConfigAppliedEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotNull
    private String organisationId;

    private long revision;

    @NotNull
    private NetSuiteConfigStatus storeStatus;

    /** Null when the configuration could not be stored, so verification never ran. */
    private NetSuiteConfigStatus validationStatus;

    private String message;
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:test --tests '*NetSuiteConfigUpsertedEventTest*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/event/netsuite/ organisation/src/test/java/org/cardanofoundation/lob/app/organisation/domain/event/netsuite/
git commit -m "feat: [LOB-2166] add NetSuite config domain events"
```

---

# Phase 3 — Projection and admin API (`organisation`)

### Task 4: Projection entity, enum and migration

**Files:**
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/entity/NetSuiteSyncState.java`
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/entity/NetSuiteConfigState.java`
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/repository/NetSuiteConfigStateRepository.java`
- Create: `organisation/src/main/resources/db/migration/postgresql/common/V1.7_100_3_7__add_netsuite_config_state.sql`

**Interfaces:**
- Produces: `NetSuiteConfigState` with `organisationId` (PK), `baseUrl`, `tokenUrl`, `clientId`, `certificateId`, `privateKeyFingerprint`, `syncState` (`NetSuiteSyncState`), `syncMessage`, `revision` (`long`), `netsuiteValid` (`Boolean`, nullable), `lastValidatedAt` (`Instant`), `validationMessage`, `updatedBy`. `NetSuiteConfigStateRepository extends JpaRepository<NetSuiteConfigState, String>`.

- [ ] **Step 1: Write the enum**

```java
package org.cardanofoundation.lob.app.organisation.domain.entity;

public enum NetSuiteSyncState {
    /** Written locally, not yet acknowledged by the netsuite module. */
    PENDING,
    /** The netsuite module stored it. */
    APPLIED,
    /** The netsuite module rejected it. */
    FAILED
}
```

- [ ] **Step 2: Write the entity**

Note it does NOT extend `CommonEntity` — this is a projection, not an audited domain aggregate, and the module's Envers setup would otherwise demand an `_aud` table.

```java
package org.cardanofoundation.lob.app.organisation.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Organisation-owned read model of the NetSuite configuration held by the netsuite module.
 * <p>
 * Deliberately holds NO secret material — only a fingerprint of the private key. The
 * organisation module must never read the netsuite module's tables, so this projection is
 * the only thing the status endpoint reads, and it is fed exclusively by
 * {@code NetSuiteConfigAppliedEvent}.
 */
@Entity
@Table(name = "organisation_netsuite_config_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NetSuiteConfigState {

    @Id
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "token_url", nullable = false)
    private String tokenUrl;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "certificate_id", nullable = false)
    private String certificateId;

    @Column(name = "private_key_fingerprint", nullable = false)
    private String privateKeyFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_state", nullable = false)
    private NetSuiteSyncState syncState;

    @Column(name = "sync_message")
    private String syncMessage;

    @Column(name = "revision", nullable = false)
    private long revision;

    /** Null until the first acknowledgement is processed. */
    @Column(name = "netsuite_valid")
    private Boolean netsuiteValid;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "validation_message")
    private String validationMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;
}
```

- [ ] **Step 3: Write the repository**

```java
package org.cardanofoundation.lob.app.organisation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;

@Repository
public interface NetSuiteConfigStateRepository extends JpaRepository<NetSuiteConfigState, String> {
}
```

- [ ] **Step 4: Write the migration**

```sql
CREATE TABLE organisation_netsuite_config_state (
    organisation_id         VARCHAR(255) NOT NULL,
    base_url                TEXT         NOT NULL,
    token_url               TEXT         NOT NULL,
    client_id               VARCHAR(255) NOT NULL,
    certificate_id          VARCHAR(255) NOT NULL,
    private_key_fingerprint VARCHAR(64)  NOT NULL,
    sync_state              VARCHAR(16)  NOT NULL,
    sync_message            TEXT,
    revision                BIGINT       NOT NULL DEFAULT 0,
    netsuite_valid          BOOLEAN,
    last_validated_at       TIMESTAMP WITHOUT TIME ZONE,
    validation_message      TEXT,
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,

    CONSTRAINT organisation_netsuite_config_state_pk PRIMARY KEY (organisation_id)
);

COMMENT ON TABLE organisation_netsuite_config_state IS
    'Organisation-owned projection of NetSuite configuration state. Holds no secret material; the private key lives only in the netsuite module.';
```

- [ ] **Step 5: Verify compilation**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/entity/NetSuite* organisation/src/main/java/org/cardanofoundation/lob/app/organisation/repository/NetSuiteConfigStateRepository.java organisation/src/main/resources/db/migration/postgresql/common/V1.7_100_3_7__add_netsuite_config_state.sql
git commit -m "feat: [LOB-2166] add organisation NetSuite config projection"
```

### Task 5: Request and view DTOs

**Files:**
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/request/NetSuiteConfigurationCreate.java`
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/request/NetSuiteConfigurationUpdate.java`
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/view/NetSuiteConfigurationStatusView.java`

**Interfaces:**
- Produces: `NetSuiteConfigurationCreate` with `baseUrl`, `tokenUrl`, `clientId`, `certificateId`, `privateKey` — all `@NotBlank`. `NetSuiteConfigurationUpdate` identical except `privateKey` is optional. `NetSuiteConfigurationStatusView.notConfigured()` static factory, and a full-state factory `of(NetSuiteConfigState)`.

- [ ] **Step 1: Write `NetSuiteConfigurationCreate`**

```java
package org.cardanofoundation.lob.app.organisation.domain.request;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class NetSuiteConfigurationCreate {

    @NotBlank
    @Schema(example = "https://1234567.restlets.api.netsuite.com/app/site/hosting/restlet.nl?script=123&deploy=1")
    private String baseUrl;

    @NotBlank
    @Schema(example = "https://1234567.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token")
    private String tokenUrl;

    @NotBlank
    @Schema(example = "b9c1f0e2...")
    private String clientId;

    @NotBlank
    @Schema(example = "a1b2c3d4...")
    private String certificateId;

    /** PKCS#8 PEM. Write-only: never returned by any endpoint. */
    @NotBlank
    @ToString.Exclude
    @Schema(example = "-----BEGIN PRIVATE KEY-----\\nMIIEvg...\\n-----END PRIVATE KEY-----")
    private String privateKey;
}
```

- [ ] **Step 2: Write `NetSuiteConfigurationUpdate`**

Identical but `privateKey` carries no `@NotBlank` — blank or absent means "keep the stored key".

```java
package org.cardanofoundation.lob.app.organisation.domain.request;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class NetSuiteConfigurationUpdate {

    @NotBlank
    @Schema(example = "https://1234567.restlets.api.netsuite.com/app/site/hosting/restlet.nl?script=123&deploy=1")
    private String baseUrl;

    @NotBlank
    @Schema(example = "https://1234567.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token")
    private String tokenUrl;

    @NotBlank
    private String clientId;

    @NotBlank
    private String certificateId;

    /** Optional. Blank or absent keeps the key already stored by the netsuite module. */
    @ToString.Exclude
    @Schema(description = "Leave empty to keep the existing key")
    private String privateKey;
}
```

- [ ] **Step 3: Write the status view**

```java
package org.cardanofoundation.lob.app.organisation.domain.view;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;

/** Never carries the private key. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NetSuiteConfigurationStatusView {

    private boolean configured;
    private String baseUrl;
    private String tokenUrl;
    private String clientId;
    private String certificateId;
    private String privateKeyFingerprint;
    private String syncState;
    private String syncMessage;
    private Boolean netsuiteValid;
    private Instant lastValidatedAt;
    private String validationMessage;
    private Instant updatedAt;
    private String updatedBy;

    public static NetSuiteConfigurationStatusView notConfigured() {
        return NetSuiteConfigurationStatusView.builder().configured(false).build();
    }

    public static NetSuiteConfigurationStatusView of(NetSuiteConfigState state) {
        return NetSuiteConfigurationStatusView.builder()
                .configured(true)
                .baseUrl(state.getBaseUrl())
                .tokenUrl(state.getTokenUrl())
                .clientId(state.getClientId())
                .certificateId(state.getCertificateId())
                .privateKeyFingerprint(state.getPrivateKeyFingerprint())
                .syncState(state.getSyncState().name())
                .syncMessage(state.getSyncMessage())
                .netsuiteValid(state.getNetsuiteValid())
                .lastValidatedAt(state.getLastValidatedAt())
                .validationMessage(state.getValidationMessage())
                .updatedAt(state.getUpdatedAt())
                .updatedBy(state.getUpdatedBy())
                .build();
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/request/NetSuiteConfiguration*.java organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/view/NetSuiteConfigurationStatusView.java
git commit -m "feat: [LOB-2166] add NetSuite configuration DTOs"
```

### Task 6: `NetSuiteConfigAdminService`

**Files:**
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/service/NetSuiteConfigAdminService.java`
- Test: `organisation/src/test/java/org/cardanofoundation/lob/app/organisation/service/NetSuiteConfigAdminServiceTest.java`

**Interfaces:**
- Consumes: `SecretCipher` (Task 1), `NetSuiteConfigStateRepository` (Task 4), DTOs (Task 5), `NetSuiteConfigUpsertedEvent` (Task 3).
- Produces:
  - `Either<ProblemDetail, NetSuiteConfigurationStatusView> create(String organisationId, NetSuiteConfigurationCreate request, String user)`
  - `Either<ProblemDetail, NetSuiteConfigurationStatusView> update(String organisationId, NetSuiteConfigurationUpdate request, String user)`
  - `NetSuiteConfigurationStatusView status(String organisationId)`

**Design note — publish after commit.** The `cf-reeve-application` bridge uses a plain `@EventListener`, which fires synchronously inside `publishEvent()`, i.e. *before* the surrounding transaction commits. The service therefore splits into a `@Transactional` persist method and a non-transactional public method that publishes after it returns. Do not merge them.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.organisation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.NetSuiteConfigurationStatusView;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;
import org.cardanofoundation.lob.app.support.crypto.SecretCipher;

@ExtendWith(MockitoExtension.class)
class NetSuiteConfigAdminServiceTest {

    private static final String ORG = "org-1";
    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQ==\n-----END PRIVATE KEY-----";

    @Mock
    private NetSuiteConfigStateRepository repository;
    @Mock
    private SecretCipher secretCipher;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private NetSuiteConfigAdminService service;

    @BeforeEach
    void setUp() {
        service = new NetSuiteConfigAdminService(repository, secretCipher, eventPublisher,
                Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC));
    }

    private NetSuiteConfigurationCreate createRequest() {
        return new NetSuiteConfigurationCreate("https://base", "https://token", "client", "cert", PEM);
    }

    private NetSuiteConfigState existingState() {
        return NetSuiteConfigState.builder()
                .organisationId(ORG)
                .baseUrl("https://old")
                .tokenUrl("https://oldtoken")
                .clientId("oldclient")
                .certificateId("oldcert")
                .privateKeyFingerprint("oldfp")
                .syncState(NetSuiteSyncState.APPLIED)
                .revision(4L)
                .netsuiteValid(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedBy("someone")
                .build();
    }

    @Test
    void createPersistsPendingStateAndPublishesTheEvent() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        when(secretCipher.encrypt(PEM)).thenReturn("v1:ENVELOPE");

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result =
                service.create(ORG, createRequest(), "admin");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getSyncState()).isEqualTo("PENDING");
        assertThat(result.get().getNetsuiteValid()).isNull();

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRevision()).isEqualTo(1L);
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.PENDING);

        ArgumentCaptor<NetSuiteConfigUpsertedEvent> published =
                ArgumentCaptor.forClass(NetSuiteConfigUpsertedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getPrivateKeyEncrypted()).isEqualTo("v1:ENVELOPE");
        assertThat(published.getValue().getRevision()).isEqualTo(1L);
    }

    @Test
    void createNeverStoresTheSecretInTheProjection() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        when(secretCipher.encrypt(PEM)).thenReturn("v1:ENVELOPE");

        service.create(ORG, createRequest(), "admin");

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKeyFingerprint())
                .isNotBlank()
                .isNotEqualTo(PEM)
                .isNotEqualTo("v1:ENVELOPE");
    }

    @Test
    void createRejectsAnOrganisationThatAlreadyHasARow() {
        when(repository.findById(ORG)).thenReturn(Optional.of(existingState()));

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result =
                service.create(ORG, createRequest(), "admin");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_ALREADY_EXISTS");
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(NetSuiteConfigUpsertedEvent.class));
    }

    @Test
    void updateRejectsAnOrganisationWithNoRow() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result = service.update(ORG,
                new NetSuiteConfigurationUpdate("https://base", "https://token", "client", "cert", null), "admin");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_NOT_FOUND");
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(eventPublisher, never()).publishEvent(any(NetSuiteConfigUpsertedEvent.class));
    }

    @Test
    void updateWithoutAKeyPublishesNullSoTheNetsuiteModuleReusesTheStoredOne() {
        when(repository.findById(ORG)).thenReturn(Optional.of(existingState()));

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result = service.update(ORG,
                new NetSuiteConfigurationUpdate("https://base", "https://token", "client", "cert", "  "), "admin");

        assertThat(result.isRight()).isTrue();

        ArgumentCaptor<NetSuiteConfigUpsertedEvent> published =
                ArgumentCaptor.forClass(NetSuiteConfigUpsertedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getPrivateKeyEncrypted()).isNull();
        assertThat(published.getValue().getRevision()).isEqualTo(5L);

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKeyFingerprint()).isEqualTo("oldfp");
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.PENDING);
        assertThat(saved.getValue().getNetsuiteValid()).isNull();
    }

    @Test
    void updateWithANewKeyEncryptsItAndRefreshesTheFingerprint() {
        when(repository.findById(ORG)).thenReturn(Optional.of(existingState()));
        when(secretCipher.encrypt(PEM)).thenReturn("v1:NEWENVELOPE");

        service.update(ORG, new NetSuiteConfigurationUpdate("https://base", "https://token", "client", "cert", PEM), "admin");

        ArgumentCaptor<NetSuiteConfigUpsertedEvent> published =
                ArgumentCaptor.forClass(NetSuiteConfigUpsertedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getPrivateKeyEncrypted()).isEqualTo("v1:NEWENVELOPE");

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKeyFingerprint()).isNotEqualTo("oldfp");
    }

    @Test
    void statusReportsNotConfiguredRatherThanFailingForAFreshOrganisation() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        NetSuiteConfigurationStatusView view = service.status(ORG);

        assertThat(view.isConfigured()).isFalse();
        assertThat(view.getBaseUrl()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:test --tests '*NetSuiteConfigAdminServiceTest*'`
Expected: FAIL — `NetSuiteConfigAdminService` does not exist.

- [ ] **Step 3: Write the service**

```java
package org.cardanofoundation.lob.app.organisation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.NetSuiteConfigurationStatusView;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;
import org.cardanofoundation.lob.app.support.crypto.SHA3;
import org.cardanofoundation.lob.app.support.crypto.SecretCipher;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

/**
 * Admin-facing write path for per-organisation NetSuite configuration.
 * <p>
 * This module is NOT the owner of the configuration — the netsuite module is. Everything
 * persisted here is a projection used to answer the status endpoint; the private key is
 * encrypted, handed to the event, and immediately forgotten.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigAdminService {

    private final NetSuiteConfigStateRepository repository;
    private final SecretCipher secretCipher;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public NetSuiteConfigurationStatusView status(String organisationId) {
        return repository.findById(organisationId)
                .map(NetSuiteConfigurationStatusView::of)
                .orElseGet(NetSuiteConfigurationStatusView::notConfigured);
    }

    public Either<ProblemDetail, NetSuiteConfigurationStatusView> create(String organisationId,
                                                                        NetSuiteConfigurationCreate request,
                                                                        String user) {
        if (repository.findById(organisationId).isPresent()) {
            return Either.left(problem(HttpStatus.CONFLICT, "NETSUITE_CONFIGURATION_ALREADY_EXISTS",
                    "NetSuite configuration already exists for organisation %s. Use PUT to update it."
                            .formatted(organisationId)));
        }

        String encryptedKey = secretCipher.encrypt(request.getPrivateKey());
        String fingerprint = SHA3.digestAsHex(request.getPrivateKey());

        NetSuiteConfigState state = persist(organisationId, request.getBaseUrl(), request.getTokenUrl(),
                request.getClientId(), request.getCertificateId(), fingerprint, user, Optional.empty());

        publish(state, encryptedKey, user);

        return Either.right(NetSuiteConfigurationStatusView.of(state));
    }

    public Either<ProblemDetail, NetSuiteConfigurationStatusView> update(String organisationId,
                                                                        NetSuiteConfigurationUpdate request,
                                                                        String user) {
        Optional<NetSuiteConfigState> existingM = repository.findById(organisationId);
        if (existingM.isEmpty()) {
            return Either.left(problem(HttpStatus.NOT_FOUND, "NETSUITE_CONFIGURATION_NOT_FOUND",
                    "No NetSuite configuration for organisation %s. Use POST to create one."
                            .formatted(organisationId)));
        }
        NetSuiteConfigState existing = existingM.orElseThrow();

        boolean replacingKey = request.getPrivateKey() != null && !request.getPrivateKey().isBlank();
        String encryptedKey = replacingKey ? secretCipher.encrypt(request.getPrivateKey()) : null;
        String fingerprint = replacingKey
                ? SHA3.digestAsHex(request.getPrivateKey())
                : existing.getPrivateKeyFingerprint();

        NetSuiteConfigState state = persist(organisationId, request.getBaseUrl(), request.getTokenUrl(),
                request.getClientId(), request.getCertificateId(), fingerprint, user, Optional.of(existing));

        publish(state, encryptedKey, user);

        return Either.right(NetSuiteConfigurationStatusView.of(state));
    }

    /**
     * Commits the projection. Kept separate from {@link #publish} so the event is never
     * published inside the transaction — the Kafka bridge listens with a plain
     * {@code @EventListener}, which fires before commit.
     */
    @Transactional
    protected NetSuiteConfigState persist(String organisationId,
                                          String baseUrl,
                                          String tokenUrl,
                                          String clientId,
                                          String certificateId,
                                          String fingerprint,
                                          String user,
                                          Optional<NetSuiteConfigState> existing) {
        Instant now = Instant.now(clock);

        NetSuiteConfigState state = NetSuiteConfigState.builder()
                .organisationId(organisationId)
                .baseUrl(baseUrl)
                .tokenUrl(tokenUrl)
                .clientId(clientId)
                .certificateId(certificateId)
                .privateKeyFingerprint(fingerprint)
                .syncState(NetSuiteSyncState.PENDING)
                .syncMessage(null)
                .revision(existing.map(s -> s.getRevision() + 1).orElse(1L))
                .netsuiteValid(null)
                .lastValidatedAt(null)
                .validationMessage(null)
                .createdAt(existing.map(NetSuiteConfigState::getCreatedAt).orElse(now))
                .updatedAt(now)
                .updatedBy(user)
                .build();

        repository.save(state);

        return state;
    }

    private void publish(NetSuiteConfigState state, String encryptedKey, String user) {
        eventPublisher.publishEvent(NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, user))
                .organisationId(state.getOrganisationId())
                .revision(state.getRevision())
                .baseUrl(state.getBaseUrl())
                .tokenUrl(state.getTokenUrl())
                .clientId(state.getClientId())
                .certificateId(state.getCertificateId())
                .privateKeyEncrypted(encryptedKey)
                .build());

        log.info("Published NetSuiteConfigUpsertedEvent for organisation {} revision {}",
                state.getOrganisationId(), state.getRevision());
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);

        return problem;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:test --tests '*NetSuiteConfigAdminServiceTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add organisation/src/main/java/org/cardanofoundation/lob/app/organisation/service/NetSuiteConfigAdminService.java organisation/src/test/java/org/cardanofoundation/lob/app/organisation/service/NetSuiteConfigAdminServiceTest.java
git commit -m "feat: [LOB-2166] add NetSuite config admin service"
```

### Task 7: `NetSuiteConfigAckHandler`

**Files:**
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/service/NetSuiteConfigAckHandler.java`
- Test: `organisation/src/test/java/org/cardanofoundation/lob/app/organisation/service/NetSuiteConfigAckHandlerTest.java`

**Interfaces:**
- Consumes: `NetSuiteConfigAppliedEvent` (Task 3), `NetSuiteConfigStateRepository` (Task 4).
- Produces: `handleNetSuiteConfigApplied(NetSuiteConfigAppliedEvent event)`, annotated `@EventListener`.

This is the pod-independence guarantee from spec D7: any pod may run this, with no request context.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.organisation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class NetSuiteConfigAckHandlerTest {

    private static final String ORG = "org-1";
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    @Mock
    private NetSuiteConfigStateRepository repository;

    private NetSuiteConfigAckHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NetSuiteConfigAckHandler(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private NetSuiteConfigState pendingState(long revision) {
        return NetSuiteConfigState.builder()
                .organisationId(ORG)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert").privateKeyFingerprint("fp")
                .syncState(NetSuiteSyncState.PENDING)
                .revision(revision)
                .createdAt(NOW).updatedAt(NOW).updatedBy("admin")
                .build();
    }

    private NetSuiteConfigAppliedEvent ack(long revision,
                                           NetSuiteConfigStatus store,
                                           NetSuiteConfigStatus validation,
                                           String message) {
        return NetSuiteConfigAppliedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigAppliedEvent.VERSION))
                .organisationId(ORG)
                .revision(revision)
                .storeStatus(store)
                .validationStatus(validation)
                .message(message)
                .build();
    }

    @Test
    void marksAppliedAndValidWhenBothSucceeded() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(3L)));

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.APPLIED);
        assertThat(saved.getValue().getNetsuiteValid()).isTrue();
        assertThat(saved.getValue().getLastValidatedAt()).isEqualTo(NOW);
    }

    @Test
    void marksAppliedButInvalidWhenCredentialsAreRejected() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(3L)));

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.FAILED, "401 Unauthorized"));

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.APPLIED);
        assertThat(saved.getValue().getNetsuiteValid()).isFalse();
        assertThat(saved.getValue().getValidationMessage()).isEqualTo("401 Unauthorized");
    }

    @Test
    void marksFailedWhenTheConfigurationCouldNotBeStored() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(3L)));

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.FAILED, null, "NETSUITE_CONFIGURATION_NOT_FOUND"));

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.FAILED);
        assertThat(saved.getValue().getSyncMessage()).isEqualTo("NETSUITE_CONFIGURATION_NOT_FOUND");
        assertThat(saved.getValue().getNetsuiteValid()).isNull();
    }

    @Test
    void ignoresAnAcknowledgementForAnOlderRevision() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(5L)));

        handler.handleNetSuiteConfigApplied(
                ack(4L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        verify(repository, never()).save(any());
    }

    @Test
    void ignoresAnAcknowledgementForAnUnknownOrganisation() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        handler.handleNetSuiteConfigApplied(
                ack(1L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        verify(repository, never()).save(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:test --tests '*NetSuiteConfigAckHandlerTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the handler**

```java
package org.cardanofoundation.lob.app.organisation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;

/**
 * Writes the netsuite module's verdict onto the projection.
 * <p>
 * Runs on whichever organisation pod is assigned the partition — never necessarily the one
 * that served the original HTTP request. That is the whole point: the verdict is durable
 * state, not an in-memory future.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigAckHandler {

    private final NetSuiteConfigStateRepository repository;
    private final Clock clock;

    @EventListener
    @Transactional
    public void handleNetSuiteConfigApplied(NetSuiteConfigAppliedEvent event) {
        Optional<NetSuiteConfigState> stateM = repository.findById(event.getOrganisationId());

        if (stateM.isEmpty()) {
            log.warn("Ignoring NetSuiteConfigAppliedEvent for unknown organisation {}", event.getOrganisationId());
            return;
        }

        NetSuiteConfigState state = stateM.orElseThrow();

        if (event.getRevision() < state.getRevision()) {
            log.info("Ignoring stale NetSuiteConfigAppliedEvent for organisation {}: ack revision {} < current {}",
                    event.getOrganisationId(), event.getRevision(), state.getRevision());
            return;
        }

        boolean stored = event.getStoreStatus() == NetSuiteConfigStatus.SUCCESS;

        state.setSyncState(stored ? NetSuiteSyncState.APPLIED : NetSuiteSyncState.FAILED);
        state.setSyncMessage(stored ? null : event.getMessage());

        if (stored && event.getValidationStatus() != null) {
            state.setNetsuiteValid(event.getValidationStatus() == NetSuiteConfigStatus.SUCCESS);
            state.setValidationMessage(event.getValidationStatus() == NetSuiteConfigStatus.SUCCESS
                    ? null
                    : event.getMessage());
            state.setLastValidatedAt(Instant.now(clock));
        }

        state.setUpdatedAt(Instant.now(clock));

        repository.save(state);

        log.info("Applied NetSuiteConfigAppliedEvent for organisation {}: syncState={}, netsuiteValid={}",
                event.getOrganisationId(), state.getSyncState(), state.getNetsuiteValid());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:test --tests '*NetSuiteConfigAckHandlerTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add organisation/src/main/java/org/cardanofoundation/lob/app/organisation/service/NetSuiteConfigAckHandler.java organisation/src/test/java/org/cardanofoundation/lob/app/organisation/service/NetSuiteConfigAckHandlerTest.java
git commit -m "feat: [LOB-2166] apply NetSuite config ACK to the projection"
```

### Task 8: `NetSuiteConfigurationController`

**Files:**
- Create: `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/resource/NetSuiteConfigurationController.java`

**Interfaces:**
- Consumes: `NetSuiteConfigAdminService` (Task 6), `KeycloakSecurityHelper`.
- Produces: `POST`/`PUT` `/api/v1/organisations/{orgId}/netsuite-configuration` returning `202`, `GET` `/api/v1/organisations/{orgId}/netsuite-configuration/status` returning `200`.

Before writing, open `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/resource/OrganisationResource.java` and copy its exact class-level annotations, `KeycloakSecurityHelper` injection style and `Either.fold` idiom.

- [ ] **Step 1: Write the controller**

```java
package org.cardanofoundation.lob.app.organisation.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.NetSuiteConfigurationStatusView;
import org.cardanofoundation.lob.app.organisation.service.NetSuiteConfigAdminService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Admin-only NetSuite credential administration. There is deliberately no DELETE, and the
 * private key is never returned by any endpoint.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "NetSuite Configuration", description = "Per-organisation NetSuite configuration")
@RequiredArgsConstructor
@Slf4j
public class NetSuiteConfigurationController {

    private final NetSuiteConfigAdminService netSuiteConfigAdminService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;

    @GetMapping(value = "/organisations/{orgId}/netsuite-configuration/status", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getAdminRole())")
    @Operation(description = "NetSuite configuration status. Returns configured=false when none exists; never returns the private key.")
    public ResponseEntity<?> status(@PathVariable("orgId") String orgId) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return forbidden(orgId);
        }

        return ResponseEntity.ok(netSuiteConfigAdminService.status(orgId));
    }

    @PostMapping(value = "/organisations/{orgId}/netsuite-configuration",
            produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getAdminRole())")
    @Operation(description = "Create the NetSuite configuration. Accepted asynchronously; poll the status endpoint for the verdict.")
    public ResponseEntity<?> create(@PathVariable("orgId") String orgId,
                                    @Valid @RequestBody NetSuiteConfigurationCreate body) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return forbidden(orgId);
        }

        return netSuiteConfigAdminService.create(orgId, body, currentUser())
                .fold(problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                        this::accepted);
    }

    @PutMapping(value = "/organisations/{orgId}/netsuite-configuration",
            produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole(@securityConfig.getAdminRole())")
    @Operation(description = "Update the NetSuite configuration. Leave privateKey empty to keep the stored key.")
    public ResponseEntity<?> update(@PathVariable("orgId") String orgId,
                                    @Valid @RequestBody NetSuiteConfigurationUpdate body) {
        if (!keycloakSecurityHelper.canUserAccessOrg(orgId)) {
            return forbidden(orgId);
        }

        return netSuiteConfigAdminService.update(orgId, body, currentUser())
                .fold(problem -> ResponseEntity.status(problem.getStatus()).body(problem),
                        this::accepted);
    }

    private ResponseEntity<NetSuiteConfigurationStatusView> accepted(NetSuiteConfigurationStatusView view) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    private ResponseEntity<ProblemDetail> forbidden(String orgId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "User cannot access organisation %s".formatted(orgId));
        problem.setTitle("ORGANISATION_ACCESS_DENIED");

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    private String currentUser() {
        return keycloakSecurityHelper.getUserName();
    }
}
```

- [ ] **Step 2: Reconcile the helper method names with the real `KeycloakSecurityHelper`**

Run: `grep -n "public " support/src/main/java/org/cardanofoundation/lob/app/support/security/KeycloakSecurityHelper.java`

If `getUserName()` does not exist, use whatever accessor `OrganisationResource` uses to obtain the acting user; if none exists, replace `currentUser()` with `SecurityContextHolder.getContext().getAuthentication().getName()`. If `canUserAccessOrg` has a different name or signature, adapt the two call sites. Do not invent methods.

- [ ] **Step 3: Verify compilation**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the whole organisation module test suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:test`
Expected: PASS, no regressions.

- [ ] **Step 5: Commit**

```bash
git add organisation/src/main/java/org/cardanofoundation/lob/app/organisation/resource/NetSuiteConfigurationController.java
git commit -m "feat: [LOB-2166] add admin NetSuite configuration endpoints"
```

---

# Phase 4 — Configuration ownership (`netsuite_altavia_erp_adapter`)

### Task 9: Config entity, repository and migration

**Files:**
- Create: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/domain/entity/NetSuiteConfigEntity.java`
- Create: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/repository/NetSuiteConfigRepository.java`
- Create: `netsuite_altavia_erp_adapter/src/main/resources/db/migration/postgresql/common/V1.7_100_5_1__add_organisation_config.sql`

**Interfaces:**
- Produces: `NetSuiteConfigEntity` with `organisationId` (PK), `baseUrl`, `tokenUrl`, `clientId`, `certificateId`, `privateKeyEncrypted`, `revision`, `createdAt`, `updatedAt`. `NetSuiteConfigRepository extends JpaRepository<NetSuiteConfigEntity, String>`.

- [ ] **Step 1: Write the entity**

Follow `NetSuiteIngestionEntity` for style — no `schema=` attribute, table-prefix isolation only.

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** The authoritative per-organisation NetSuite configuration. Owned solely by this module. */
@Entity
@Table(name = "netsuite_adapter_organisation_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class NetSuiteConfigEntity {

    @Id
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "token_url", nullable = false)
    private String tokenUrl;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "certificate_id", nullable = false)
    private String certificateId;

    /** {@code v1:}-prefixed AES-GCM envelope. Never logged. */
    @Column(name = "private_key_encrypted", nullable = false)
    @ToString.Exclude
    private String privateKeyEncrypted;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

- [ ] **Step 2: Write the repository**

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;

@Repository
public interface NetSuiteConfigRepository extends JpaRepository<NetSuiteConfigEntity, String> {
}
```

- [ ] **Step 3: Write the migration**

```sql
CREATE TABLE netsuite_adapter_organisation_config (
    organisation_id       VARCHAR(255) NOT NULL,
    base_url              TEXT         NOT NULL,
    token_url             TEXT         NOT NULL,
    client_id             VARCHAR(255) NOT NULL,
    certificate_id        VARCHAR(255) NOT NULL,
    private_key_encrypted TEXT         NOT NULL,
    revision              BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,

    CONSTRAINT netsuite_adapter_organisation_config_pk PRIMARY KEY (organisation_id)
);

COMMENT ON COLUMN netsuite_adapter_organisation_config.private_key_encrypted IS
    'AES-256-GCM envelope (v1: prefix). Decryptable only with LOB_CONFIG_ENCRYPTION_KEY.';
```

- [ ] **Step 4: Verify compilation**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/domain/entity/NetSuiteConfigEntity.java netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/repository/NetSuiteConfigRepository.java netsuite_altavia_erp_adapter/src/main/resources/db/migration/postgresql/common/V1.7_100_5_1__add_organisation_config.sql
git commit -m "feat: [LOB-2166] add authoritative per-org NetSuite config store"
```

### Task 10: Refactor `NetSuiteClient` to take a PEM

**Files:**
- Modify: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/client/NetSuiteClient.java`
- Test: `netsuite_altavia_erp_adapter/src/test/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/client/NetSuiteClientTest.java`

**Interfaces:**
- Produces: constructor `NetSuiteClient(ObjectMapper, RestClient, String baseUrl, String tokenUrl, String privateKeyPem, String certificateId, String clientId, Integer recordsPerCall)` — the `privateKeyFilePath` parameter becomes `privateKeyPem` **in the same position**, so no call site changes argument order. `@PostConstruct init()` is deleted.

Deleting `init()` is safe: `callForTransactionLinesData` already refreshes when `accessTokenExpiration` is empty, because `Optional.orElse(LocalDateTime.MIN)` makes the first call always "expired".

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.client.RestClient;

class NetSuiteClientTest {

    private static String generatePkcs8Pem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();

        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----";
    }

    private NetSuiteClient clientWith(String pem) {
        return new NetSuiteClient(new ObjectMapper(), RestClient.create(),
                "https://base", "https://token", pem, "cert-1", "client-1", 100);
    }

    @Test
    void parsesAPemSuppliedDirectlyRatherThanFromAFile() throws Exception {
        NetSuiteClient client = clientWith(generatePkcs8Pem());

        Method loadPrivateKey = NetSuiteClient.class.getDeclaredMethod("loadPrivateKey");
        loadPrivateKey.setAccessible(true);

        assertThat(loadPrivateKey.invoke(client)).isNotNull();
    }

    @Test
    void toleratesPemsWithCarriageReturnsAndSurroundingWhitespace() throws Exception {
        String pem = "  \r\n" + generatePkcs8Pem().replace("\n", "\r\n") + "\r\n  ";
        NetSuiteClient client = clientWith(pem);

        Method loadPrivateKey = NetSuiteClient.class.getDeclaredMethod("loadPrivateKey");
        loadPrivateKey.setAccessible(true);

        assertThatCode(() -> loadPrivateKey.invoke(client)).doesNotThrowAnyException();
    }

    @Test
    void exposesTheBaseUrlItWasConstructedWith() throws Exception {
        assertThat(clientWith(generatePkcs8Pem()).getBaseUrl()).isEqualTo("https://base");
    }
}
```

The whitespace test matters: the old code stripped only `System.lineSeparator()`, which silently fails on a CRLF PEM pasted into a form on a Linux server. The new code must strip all whitespace.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test --tests '*NetSuiteClientTest*'`
Expected: FAIL — no `loadPrivateKey()` method; constructor still takes a file path.

- [ ] **Step 3: Replace the field, the loader and delete `init()`**

Replace line 63 `private final String privateKeyFilePath;` with:

```java
    /** PKCS#8 PEM contents, already decrypted by the caller. */
    private final String privateKeyPem;
```

Replace the whole `loadPrivateKeyFromFile` method (lines 81-91) with:

```java
    private PrivateKey loadPrivateKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
        String base64 = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64));

        return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }
```

Delete the `@PostConstruct public void init()` block (lines 73-79) and the now-unused imports: `jakarta.annotation.PostConstruct`, `java.io.File`, `java.nio.charset.Charset`, `java.nio.file.Files`.

- [ ] **Step 4: Update `getJwtTokenFromCertifikate` and `refreshToken` signatures**

Change line 93 to drop `IOException`:

```java
    private String getJwtTokenFromCertifikate() throws NoSuchAlgorithmException, InvalidKeySpecException {
        PrivateKey privateKey = loadPrivateKey();
```

And in `refreshToken`, narrow the catch:

```java
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("Error generating jwt Token: {}", e.getMessage());
            return;
        }
```

Keep `java.io.IOException` imported — `callForTransactionLinesData` still declares it.

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test --tests '*NetSuiteClientTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/client/NetSuiteClient.java netsuite_altavia_erp_adapter/src/test/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/client/NetSuiteClientTest.java
git commit -m "refactor: [LOB-2166] NetSuiteClient takes a PEM instead of a file path"
```

### Task 11: `NetSuiteClientRegistry`

**Files:**
- Create: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteClientRegistry.java`
- Test: `netsuite_altavia_erp_adapter/src/test/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteClientRegistryTest.java`

**Interfaces:**
- Consumes: `NetSuiteConfigRepository` (Task 9), `SecretCipher` (Task 1), `NetSuiteClient` (Task 10).
- Produces: `Either<ProblemDetail, NetSuiteClient> forOrganisation(String organisationId)` and `void evict(String organisationId)`.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.NetSuiteConfigRepository;
import org.cardanofoundation.lob.app.support.crypto.SecretCipher;

@ExtendWith(MockitoExtension.class)
class NetSuiteClientRegistryTest {

    private static final String ORG = "org-1";

    @Mock
    private NetSuiteConfigRepository repository;
    @Mock
    private SecretCipher secretCipher;

    private NetSuiteClientRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NetSuiteClientRegistry(repository, secretCipher, new ObjectMapper(),
                RestClient.create(), 100);
    }

    private NetSuiteConfigEntity config() {
        return NetSuiteConfigEntity.builder()
                .organisationId(ORG)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert")
                .privateKeyEncrypted("v1:ENVELOPE")
                .revision(1L)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .build();
    }

    @Test
    void returnsAProblemWhenTheOrganisationHasNoConfiguration() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        Either<ProblemDetail, NetSuiteClient> result = registry.forOrganisation(ORG);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_NOT_FOUND");
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED.value());
    }

    @Test
    void buildsAClientFromTheDecryptedConfiguration() {
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenReturn("PEM");

        Either<ProblemDetail, NetSuiteClient> result = registry.forOrganisation(ORG);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getBaseUrl()).isEqualTo("https://base");
    }

    @Test
    void cachesTheClientSoRepeatedLookupsDoNotHitTheDatabase() {
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenReturn("PEM");

        NetSuiteClient first = registry.forOrganisation(ORG).get();
        NetSuiteClient second = registry.forOrganisation(ORG).get();

        assertThat(first).isSameAs(second);
        verify(repository, times(1)).findById(ORG);
    }

    @Test
    void rebuildsTheClientAfterEviction() {
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE")).thenReturn("PEM");

        NetSuiteClient first = registry.forOrganisation(ORG).get();
        registry.evict(ORG);
        NetSuiteClient second = registry.forOrganisation(ORG).get();

        assertThat(first).isNotSameAs(second);
        verify(repository, times(2)).findById(ORG);
    }

    @Test
    void surfacesADecryptionFailureAsAProblemRatherThanThrowing() {
        when(repository.findById(ORG)).thenReturn(Optional.of(config()));
        when(secretCipher.decrypt("v1:ENVELOPE"))
                .thenThrow(new IllegalStateException("wrong key"));

        Either<ProblemDetail, NetSuiteClient> result = registry.forOrganisation(ORG);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_UNREADABLE");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test --tests '*NetSuiteClientRegistryTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the registry**

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.NetSuiteConfigRepository;
import org.cardanofoundation.lob.app.support.crypto.SecretCipher;

/**
 * Resolves and caches one {@link NetSuiteClient} per organisation. Each cached client keeps
 * its own OAuth token, so tenants never share credentials or tokens.
 * <p>
 * Entries are evicted when a configuration changes, so a credential update takes effect
 * without restarting the pod.
 */
@Slf4j
@RequiredArgsConstructor
public class NetSuiteClientRegistry {

    public static final String CONFIGURATION_NOT_FOUND = "NETSUITE_CONFIGURATION_NOT_FOUND";
    public static final String CONFIGURATION_UNREADABLE = "NETSUITE_CONFIGURATION_UNREADABLE";

    private final NetSuiteConfigRepository netSuiteConfigRepository;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Integer recordsPerCall;

    private final Map<String, NetSuiteClient> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Either<ProblemDetail, NetSuiteClient> forOrganisation(String organisationId) {
        NetSuiteClient cached = cache.get(organisationId);
        if (cached != null) {
            return Either.right(cached);
        }

        Optional<NetSuiteConfigEntity> configM = netSuiteConfigRepository.findById(organisationId);
        if (configM.isEmpty()) {
            log.warn("No NetSuite configuration for organisation {}", organisationId);

            return Either.left(problem(HttpStatus.PRECONDITION_REQUIRED, CONFIGURATION_NOT_FOUND,
                    "No NetSuite configuration exists for organisation %s. An administrator must create one before ingestion can run."
                            .formatted(organisationId)));
        }

        NetSuiteConfigEntity config = configM.orElseThrow();

        String pem;
        try {
            pem = secretCipher.decrypt(config.getPrivateKeyEncrypted());
        } catch (RuntimeException e) {
            log.error("Cannot decrypt NetSuite private key for organisation {}: {}", organisationId, e.getMessage());

            return Either.left(problem(HttpStatus.INTERNAL_SERVER_ERROR, CONFIGURATION_UNREADABLE,
                    "Stored NetSuite credentials for organisation %s cannot be decrypted. The configuration encryption key may have changed."
                            .formatted(organisationId)));
        }

        NetSuiteClient client = new NetSuiteClient(objectMapper, restClient,
                config.getBaseUrl(), config.getTokenUrl(), pem,
                config.getCertificateId(), config.getClientId(), recordsPerCall);

        cache.put(organisationId, client);

        return Either.right(client);
    }

    public void evict(String organisationId) {
        if (cache.remove(organisationId) != null) {
            log.info("Evicted cached NetSuite client for organisation {}", organisationId);
        }
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);

        return problem;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test --tests '*NetSuiteClientRegistryTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteClientRegistry.java netsuite_altavia_erp_adapter/src/test/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteClientRegistryTest.java
git commit -m "feat: [LOB-2166] add per-organisation NetSuiteClient registry"
```

### Task 12: `NetSuiteConfigService`

**Files:**
- Create: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteConfigService.java`
- Test: `netsuite_altavia_erp_adapter/src/test/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteConfigServiceTest.java`

**Interfaces:**
- Consumes: `NetSuiteConfigRepository`, `NetSuiteClientRegistry`, `NetSuiteConfigUpsertedEvent`, `NetSuiteConfigAppliedEvent`.
- Produces: `NetSuiteConfigAppliedEvent apply(NetSuiteConfigUpsertedEvent event)` — stores, verifies, and returns the ACK to publish.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.NetSuiteConfigRepository;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class NetSuiteConfigServiceTest {

    private static final String ORG = "org-1";

    @Mock
    private NetSuiteConfigRepository repository;
    @Mock
    private NetSuiteClientRegistry registry;

    private NetSuiteConfigService service;

    @BeforeEach
    void setUp() {
        service = new NetSuiteConfigService(repository, registry,
                Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC));
    }

    private NetSuiteConfigUpsertedEvent event(long revision, String encryptedKey) {
        return NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, "admin"))
                .organisationId(ORG)
                .revision(revision)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert")
                .privateKeyEncrypted(encryptedKey)
                .build();
    }

    private NetSuiteConfigEntity stored(long revision) {
        return NetSuiteConfigEntity.builder()
                .organisationId(ORG)
                .baseUrl("https://old").tokenUrl("https://oldtoken")
                .clientId("oldclient").certificateId("oldcert")
                .privateKeyEncrypted("v1:OLDENVELOPE")
                .revision(revision)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .build();
    }

    private void stubSuccessfulConnection() {
        NetSuiteClient client = mock(NetSuiteClient.class);
        when(client.testConnection()).thenReturn(Either.right(null));
        when(registry.forOrganisation(ORG)).thenReturn(Either.right(client));
    }

    @Test
    void storesANewConfigurationAndReportsBothSuccesses() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        stubSuccessfulConnection();

        NetSuiteConfigAppliedEvent ack = service.apply(event(1L, "v1:ENVELOPE"));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        assertThat(ack.getValidationStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        assertThat(ack.getRevision()).isEqualTo(1L);

        ArgumentCaptor<NetSuiteConfigEntity> saved = ArgumentCaptor.forClass(NetSuiteConfigEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKeyEncrypted()).isEqualTo("v1:ENVELOPE");
    }

    @Test
    void evictsTheCachedClientSoTheNewCredentialsTakeEffect() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        stubSuccessfulConnection();

        service.apply(event(1L, "v1:ENVELOPE"));

        verify(registry).evict(ORG);
    }

    @Test
    void keepsTheStoredKeyWhenTheEventCarriesNone() {
        when(repository.findById(ORG)).thenReturn(Optional.of(stored(1L)));
        stubSuccessfulConnection();

        NetSuiteConfigAppliedEvent ack = service.apply(event(2L, null));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);

        ArgumentCaptor<NetSuiteConfigEntity> saved = ArgumentCaptor.forClass(NetSuiteConfigEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKeyEncrypted()).isEqualTo("v1:OLDENVELOPE");
        assertThat(saved.getValue().getBaseUrl()).isEqualTo("https://base");
    }

    @Test
    void failsWhenNoKeyIsSuppliedAndNoneIsStored() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        NetSuiteConfigAppliedEvent ack = service.apply(event(1L, null));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.FAILED);
        assertThat(ack.getValidationStatus()).isNull();
        assertThat(ack.getMessage()).contains("NETSUITE_CONFIGURATION_NOT_FOUND");
        verify(repository, never()).save(any());
    }

    @Test
    void storesTheConfigurationEvenWhenVerificationFails() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        NetSuiteClient client = mock(NetSuiteClient.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "invalid credentials");
        when(client.testConnection()).thenReturn(Either.left(problem));
        when(registry.forOrganisation(ORG)).thenReturn(Either.right(client));

        NetSuiteConfigAppliedEvent ack = service.apply(event(1L, "v1:ENVELOPE"));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        assertThat(ack.getValidationStatus()).isEqualTo(NetSuiteConfigStatus.FAILED);
        assertThat(ack.getMessage()).contains("invalid credentials");
        verify(repository).save(any());
    }

    @Test
    void ignoresAnEventWhoseRevisionIsNotNewerButStillAcknowledges() {
        when(repository.findById(ORG)).thenReturn(Optional.of(stored(5L)));

        NetSuiteConfigAppliedEvent ack = service.apply(event(5L, "v1:ENVELOPE"));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        verify(repository, never()).save(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test --tests '*NetSuiteConfigServiceTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the service**

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.NetSuiteConfigRepository;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

/**
 * Owns the per-organisation NetSuite configuration.
 * <p>
 * Storing happens before verification: storing is the durable act, verification is a report
 * on it. A configuration whose credentials NetSuite rejects is still stored, and the ACK
 * carries {@code validationStatus = FAILED} so the organisation projection can show it.
 */
@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigService {

    private final NetSuiteConfigRepository netSuiteConfigRepository;
    private final NetSuiteClientRegistry netSuiteClientRegistry;
    private final Clock clock;

    @Transactional
    public NetSuiteConfigAppliedEvent apply(NetSuiteConfigUpsertedEvent event) {
        String organisationId = event.getOrganisationId();
        Optional<NetSuiteConfigEntity> existingM = netSuiteConfigRepository.findById(organisationId);

        if (existingM.isPresent() && event.getRevision() <= existingM.orElseThrow().getRevision()) {
            log.info("Ignoring already-applied NetSuiteConfigUpsertedEvent for organisation {} revision {}",
                    organisationId, event.getRevision());

            return ack(organisationId, event.getRevision(), NetSuiteConfigStatus.SUCCESS,
                    NetSuiteConfigStatus.SUCCESS, null);
        }

        String encryptedKey = event.getPrivateKeyEncrypted();
        if (encryptedKey == null) {
            if (existingM.isEmpty()) {
                log.error("NetSuiteConfigUpsertedEvent for organisation {} carries no key and none is stored",
                        organisationId);

                return ack(organisationId, event.getRevision(), NetSuiteConfigStatus.FAILED, null,
                        "NETSUITE_CONFIGURATION_NOT_FOUND: no private key supplied and none stored for this organisation");
            }
            encryptedKey = existingM.orElseThrow().getPrivateKeyEncrypted();
        }

        Instant now = Instant.now(clock);

        netSuiteConfigRepository.save(NetSuiteConfigEntity.builder()
                .organisationId(organisationId)
                .baseUrl(event.getBaseUrl())
                .tokenUrl(event.getTokenUrl())
                .clientId(event.getClientId())
                .certificateId(event.getCertificateId())
                .privateKeyEncrypted(encryptedKey)
                .revision(event.getRevision())
                .createdAt(existingM.map(NetSuiteConfigEntity::getCreatedAt).orElse(now))
                .updatedAt(now)
                .build());

        netSuiteClientRegistry.evict(organisationId);

        return verify(organisationId, event.getRevision());
    }

    private NetSuiteConfigAppliedEvent verify(String organisationId, long revision) {
        Either<ProblemDetail, NetSuiteClient> clientE = netSuiteClientRegistry.forOrganisation(organisationId);
        if (clientE.isLeft()) {
            return ack(organisationId, revision, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.FAILED,
                    clientE.getLeft().getDetail());
        }

        Either<ProblemDetail, Void> connection = clientE.get().testConnection();
        if (connection.isLeft()) {
            log.warn("NetSuite credentials for organisation {} were stored but rejected: {}",
                    organisationId, connection.getLeft().getDetail());

            return ack(organisationId, revision, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.FAILED,
                    connection.getLeft().getDetail());
        }

        return ack(organisationId, revision, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null);
    }

    private NetSuiteConfigAppliedEvent ack(String organisationId,
                                           long revision,
                                           NetSuiteConfigStatus storeStatus,
                                           NetSuiteConfigStatus validationStatus,
                                           String message) {
        return NetSuiteConfigAppliedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigAppliedEvent.VERSION))
                .organisationId(organisationId)
                .revision(revision)
                .storeStatus(storeStatus)
                .validationStatus(validationStatus)
                .message(message)
                .build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test --tests '*NetSuiteConfigServiceTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteConfigService.java netsuite_altavia_erp_adapter/src/test/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteConfigServiceTest.java
git commit -m "feat: [LOB-2166] store and verify per-org NetSuite config"
```

### Task 13: `NetSuiteConfigEventHandler`

**Files:**
- Create: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/event_handle/NetSuiteConfigEventHandler.java`
- Test: `netsuite_altavia_erp_adapter/src/test/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/event_handle/NetSuiteConfigEventHandlerTest.java`

**Interfaces:**
- Consumes: `NetSuiteConfigService.apply` (Task 12).
- Produces: `handleNetSuiteConfigUpserted(NetSuiteConfigUpsertedEvent)`, publishing the returned ACK.

- [ ] **Step 1: Write the failing test**

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.event_handle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteConfigService;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class NetSuiteConfigEventHandlerTest {

    @Mock
    private NetSuiteConfigService netSuiteConfigService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private NetSuiteConfigEventHandler handler;

    @Test
    void publishesTheAcknowledgementReturnedByTheService() {
        NetSuiteConfigUpsertedEvent event = NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, "admin"))
                .organisationId("org-1").revision(1L)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert")
                .privateKeyEncrypted("v1:ENVELOPE")
                .build();

        NetSuiteConfigAppliedEvent ack = NetSuiteConfigAppliedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigAppliedEvent.VERSION))
                .organisationId("org-1").revision(1L)
                .storeStatus(NetSuiteConfigStatus.SUCCESS)
                .validationStatus(NetSuiteConfigStatus.SUCCESS)
                .build();

        when(netSuiteConfigService.apply(any(NetSuiteConfigUpsertedEvent.class))).thenReturn(ack);

        handler.handleNetSuiteConfigUpserted(event);

        verify(applicationEventPublisher).publishEvent(ack);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test --tests '*NetSuiteConfigEventHandlerTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the handler**

Note: no `@Service` — this module's beans are declared in `CFConfig` (Task 16). `@Async` is deliberately omitted so the ACK is published on the Kafka consumer thread, keeping ordering predictable.

```java
package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.event_handle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteConfigService;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;

@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigEventHandler {

    private final NetSuiteConfigService netSuiteConfigService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @EventListener
    public void handleNetSuiteConfigUpserted(NetSuiteConfigUpsertedEvent event) {
        log.info("Handling NetSuiteConfigUpsertedEvent for organisation {} revision {}",
                event.getOrganisationId(), event.getRevision());

        NetSuiteConfigAppliedEvent ack = netSuiteConfigService.apply(event);
        applicationEventPublisher.publishEvent(ack);

        log.info("Published NetSuiteConfigAppliedEvent for organisation {}: store={}, validation={}",
                ack.getOrganisationId(), ack.getStoreStatus(), ack.getValidationStatus());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test --tests '*NetSuiteConfigEventHandlerTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/event_handle/NetSuiteConfigEventHandler.java netsuite_altavia_erp_adapter/src/test/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/event_handle/NetSuiteConfigEventHandlerTest.java
git commit -m "feat: [LOB-2166] bridge config upsert event to the netsuite config service"
```

### Task 14: Resolve the client per organisation in extraction and reconciliation

**Files:**
- Modify: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteExtractionService.java`
- Modify: `netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteReconcilationService.java`

**Interfaces:**
- Consumes: `NetSuiteClientRegistry.forOrganisation` (Task 11).
- Produces: both services take `NetSuiteClientRegistry` in place of `NetSuiteClient`, in the **same constructor position**, so `CFConfig` changes are minimal.

- [ ] **Step 1: Swap the dependency in `NetSuiteExtractionService`**

Replace the field at line 47:

```java
    private final NetSuiteClientRegistry netSuiteClientRegistry;
```

- [ ] **Step 2: Resolve per organisation in `validateIngestion`**

Replace the `netSuiteClient.testConnection()` block (lines 69-73) with:

```java
        Either<ProblemDetail, NetSuiteClient> clientE = netSuiteClientRegistry.forOrganisation(organisationId);
        if (clientE.isLeft()) {
            log.error("No usable NetSuite configuration for organisation {}: {}",
                    organisationId, clientE.getLeft().getDetail());
            errors.add(clientE.getLeft());
        } else {
            Either<ProblemDetail, Void> connection = clientE.get().testConnection();
            if (connection.isLeft()) {
                log.error("Error testing NetSuite connection: {}", connection.getLeft().getDetail());
                errors.add(connection.getLeft());
            }
        }
```

- [ ] **Step 3: Resolve per organisation in `startNewERPExtraction`**

Immediately after `String batchId = digestAsHex(UUID.randomUUID().toString());` and inside the `try`, before the existing `retrieveLatestNetsuiteTransactionLines` call, insert:

```java
            Either<ProblemDetail, NetSuiteClient> clientE = netSuiteClientRegistry.forOrganisation(organisationId);
            if (clientE.isLeft()) {
                ProblemDetail problem = clientE.getLeft();
                log.error("Cannot start extraction for organisation {}: {}", organisationId, problem.getDetail());

                applicationEventPublisher.publishEvent(TransactionBatchFailedEvent.builder()
                        .metadata(EventMetadata.create(TransactionBatchFailedEvent.VERSION, user))
                        .batchId(batchId)
                        .extractorType(ExtractorType.NETSUITE)
                        .organisationId(organisationId)
                        .userExtractionParameters(userExtractionParameters)
                        .error(new FatalError(ADAPTER_ERROR, "NETSUITE_CONFIGURATION_NOT_FOUND",
                                ErrorUtils.getBag(problem, "NETSUITE_CONFIGURATION_NOT_FOUND")))
                        .build());
                return;
            }
            NetSuiteClient netSuiteClient = clientE.get();
```

The existing line `Either<ProblemDetail, Optional<List<String>>> netSuiteJsonE = netSuiteClient.retrieveLatestNetsuiteTransactionLines(...)` then resolves against the new local variable and needs no edit.

- [ ] **Step 4: Repeat for `continueERPExtraction` and every `netSuiteClient` use in `NetSuiteReconcilationService`**

Run `grep -n "netSuiteClient" netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteExtractionService.java netsuite_altavia_erp_adapter/src/main/java/org/cardanofoundation/lob/app/netsuite_altavia_erp_adapter/service/internal/NetSuiteReconcilationService.java` and resolve a local `NetSuiteClient` at the top of every method that uses one, following the same shape. In `NetSuiteReconcilationService`, failures publish `ReconcilationFailedEvent` — match whatever failure event that method already publishes rather than inventing one.

- [ ] **Step 5: Verify compilation**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:compileJava`
Expected: BUILD SUCCESSFUL. Fix any remaining references to the removed `netSuiteClient` field.

- [ ] **Step 6: Run the module test suite and repair existing tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :netsuite_altavia_erp_adapter:test`

Existing tests construct these services with a `NetSuiteClient`. Update them to pass a mocked `NetSuiteClientRegistry` whose `forOrganisation(...)` returns `Either.right(mockClient)`. Do not weaken assertions.

- [ ] **Step 7: Commit**

```bash
git add netsuite_altavia_erp_adapter/src/
git commit -m "feat: [LOB-2166] resolve NetSuite client per organisation during ingestion"
```

---

# Phase 5 — Application wiring (`cf-reeve-application`)

All paths in this phase are relative to `/Users/thkammer/Documents/dev/cardano/java/cf-reeve-application`.

### Task 15: Kafka bridge

**Files:**
- Create: `cf-application/src/main/java/org/cardanofoundation/lob/app/kafka/publisher/OrganisationKafkaPublisher.java`
- Create: `cf-application/src/main/java/org/cardanofoundation/lob/app/kafka/consumer/OrganisationKafkaConsumer.java`
- Modify: `cf-application/src/main/java/org/cardanofoundation/lob/app/kafka/publisher/NetsuiteKafkaPublisher.java`
- Modify: `cf-application/src/main/java/org/cardanofoundation/lob/app/kafka/consumer/NetSuiteKafkaConsumer.java`
- Modify: `cf-application/src/main/resources/application.yml`

- [ ] **Step 1: Add the topics and consumer group to `application.yml`**

Under the existing `lob.netsuite.topics` block add:

```yaml
      netsuite-config-upserted: organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent
```

Add a new `lob.organisation` block (place it beside the other module blocks):

```yaml
  organisation:
    consumer-group: lob-consumer-organisation
    topics:
      netsuite-config-applied: organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent
```

Add the encryption key beside the other `lob` properties:

```yaml
  security:
    config-encryption:
      key: ${LOB_CONFIG_ENCRYPTION_KEY:}
```

- [ ] **Step 2: Write `OrganisationKafkaPublisher`**

```java
package org.cardanofoundation.lob.app.kafka.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = {"lob.organisation.enabled", "spring.kafka.enabled"}, havingValue = "true")
public class OrganisationKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${lob.netsuite.topics.netsuite-config-upserted}")
    private String netSuiteConfigUpsertedTopic;

    @EventListener
    public void handleNetSuiteConfigUpsertedEvent(NetSuiteConfigUpsertedEvent event) {
        // Keyed by organisationId so a tenant's config events stay ordered on one partition.
        log.info("Sending NetSuiteConfigUpsertedEvent to Kafka for organisation {} revision {}",
                event.getOrganisationId(), event.getRevision());
        kafkaTemplate.send(netSuiteConfigUpsertedTopic, event.getOrganisationId(), event);
    }
}
```

- [ ] **Step 3: Write `OrganisationKafkaConsumer`**

```java
package org.cardanofoundation.lob.app.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.organisation.enabled", "spring.kafka.enabled"}, havingValue = "true")
public class OrganisationKafkaConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Shared consumer group on purpose: exactly one organisation pod processes each
     * acknowledgement and writes the verdict to the projection. No pod affinity is needed
     * because the verdict is durable state, not an in-memory future.
     */
    @KafkaListener(topics = "${lob.organisation.topics.netsuite-config-applied}",
            groupId = "${lob.organisation.consumer-group}")
    public void listen(NetSuiteConfigAppliedEvent message) {
        log.info("Received NetSuiteConfigAppliedEvent from Kafka: {}", message);
        applicationEventPublisher.publishEvent(message);
    }
}
```

- [ ] **Step 4: Add the upsert listener to `NetSuiteKafkaConsumer`**

Append inside the class, matching the existing style:

```java
    @KafkaListener(topics = "${lob.netsuite.topics.netsuite-config-upserted}", groupId = "${lob.netsuite.consumer-group}")
    public void listen(NetSuiteConfigUpsertedEvent message) {
        log.info("Received NetSuiteConfigUpsertedEvent from Kafka for organisation {}", message.getOrganisationId());
        applicationEventPublisher.publishEvent(message);
    }
```

Add the import `org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent`.

- [ ] **Step 5: Add the ACK publisher to `NetsuiteKafkaPublisher`**

Open the file, copy its existing field/annotation style, and add:

```java
    @Value("${lob.organisation.topics.netsuite-config-applied}")
    private String netSuiteConfigAppliedTopic;

    @EventListener
    public void handleNetSuiteConfigAppliedEvent(NetSuiteConfigAppliedEvent event) {
        log.info("Sending NetSuiteConfigAppliedEvent to Kafka for organisation {}", event.getOrganisationId());
        kafkaTemplate.send(netSuiteConfigAppliedTopic, event.getOrganisationId(), event);
    }
```

- [ ] **Step 6: Verify compilation**

Run: `cd /Users/thkammer/Documents/dev/cardano/java/cf-reeve-application && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava`
Expected: BUILD SUCCESSFUL. This requires the platform artifacts from Phase 1–4 to be published locally first; if resolution fails, run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew publishToMavenLocal` in `cf-reeve-platform` and retry.

- [ ] **Step 7: Commit**

```bash
git add cf-application/src/main/java/org/cardanofoundation/lob/app/kafka/ cf-application/src/main/resources/application.yml
git commit -m "feat: [LOB-2166] bridge NetSuite config events over Kafka"
```

### Task 16: Rewire `CFConfig`

**Files:**
- Modify: `cf_netsuite_altavia_erp_connector/src/main/java/org/cardanofoundation/lob/app/cf_netsuite_altavia_erp_connector/config/CFConfig.java`

- [ ] **Step 1: Replace the `netSuiteClient` bean with a registry bean**

Delete the `netSuiteClient(...)` method entirely and add:

```java
    @Bean
    public NetSuiteClientRegistry netSuiteClientRegistry(NetSuiteConfigRepository netSuiteConfigRepository,
                                                         SecretCipher secretCipher,
                                                         ObjectMapper objectMapper,
                                                         @Qualifier("netsuiteRestClient") RestClient restClient,
                                                         @Value("${lob.netsuite.client.recordspercall}") int recordsPerCall) {
        return new NetSuiteClientRegistry(netSuiteConfigRepository, secretCipher, objectMapper, restClient, recordsPerCall);
    }

    @Bean
    public NetSuiteConfigService netSuiteConfigService(NetSuiteConfigRepository netSuiteConfigRepository,
                                                       NetSuiteClientRegistry netSuiteClientRegistry,
                                                       Clock clock) {
        return new NetSuiteConfigService(netSuiteConfigRepository, netSuiteClientRegistry, clock);
    }

    @Bean
    public NetSuiteConfigEventHandler netSuiteConfigEventHandler(NetSuiteConfigService netSuiteConfigService,
                                                                 ApplicationEventPublisher applicationEventPublisher) {
        return new NetSuiteConfigEventHandler(netSuiteConfigService, applicationEventPublisher);
    }
```

Add imports for `NetSuiteClientRegistry`, `NetSuiteConfigService`, `NetSuiteConfigEventHandler`, `NetSuiteConfigRepository` and `org.cardanofoundation.lob.app.support.crypto.SecretCipher`.

- [ ] **Step 2: Swap the parameter on the two service beans**

In `netSuiteExtractionService(...)` and `netsuiteReconcilationService(...)`, change the `NetSuiteClient netSuiteClient` parameter to `NetSuiteClientRegistry netSuiteClientRegistry` and pass it through in the same position.

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/thkammer/Documents/dev/cardano/java/cf-reeve-application && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add cf_netsuite_altavia_erp_connector/src/main/java/org/cardanofoundation/lob/app/cf_netsuite_altavia_erp_connector/config/CFConfig.java
git commit -m "feat: [LOB-2166] wire per-org NetSuite client registry"
```

### Task 17: Compose and documentation

**Files:**
- Modify: `docker-compose.yml`, `docker-compose.lightweight.yml`, `docker-compose-kafka-ssl.yml`
- Modify: `cf-application/src/main/resources/application.yml`
- Rewrite: `certs/netsuiteConfiguration.md`

- [ ] **Step 1: Remove the credential env vars and the PEM mount**

In `docker-compose.yml`, from the `publisher` service delete `LOB_NETSUITE_CLIENT_URL`, `LOB_NETSUITE_CLIENT_CERTIFICATE_ID`, `LOB_NETSUITE_CLIENT_CLIENT_ID`, `LOB_NETSUITE_CLIENT_PRIVATE_KEY_FILE_PATH`, `LOB_NETSUITE_CLIENT_TOKEN_URL` and the `${LOCAL_PRIVATE_KEY_PATH:-./certs/dummy.pem}:...` volume line. **Keep** `LOB_NETSUITE_CLIENT_RECORDSPERCALL`.

- [ ] **Step 2: Add the encryption key to both services**

Add to the environment of **both** `api` and `publisher`:

```yaml
      LOB_CONFIG_ENCRYPTION_KEY: ${LOB_CONFIG_ENCRYPTION_KEY:-}
```

Both need it: `api` hosts the organisation module (encrypts), `publisher` hosts netsuite (decrypts).

- [ ] **Step 3: Apply the same edits to the other two compose files**

`docker-compose.lightweight.yml` (merged `backend` service) and `docker-compose-kafka-ssl.yml` (`publisher` overlay).

- [ ] **Step 4: Remove the credential defaults from `application.yml`**

Delete the `lob.netsuite.client.url`, `.token-url`, `.private-key-file-path`, `.client-id`, `.certificate-id` entries. Keep `recordspercall`.

- [ ] **Step 5: Rewrite `certs/netsuiteConfiguration.md`**

Cover: generating the encryption key (`openssl rand -base64 32`), that it must be identical on every service and must never change once configurations exist, that credentials are now entered per organisation through Settings → NetSuite Configuration, and that the old PEM mount is gone. State plainly that changing `LOB_CONFIG_ENCRYPTION_KEY` makes every stored configuration undecryptable and forces re-entry.

- [ ] **Step 6: Verify the compose files parse**

Run: `cd /Users/thkammer/Documents/dev/cardano/java/cf-reeve-application && docker compose -f docker-compose.yml config > /dev/null && docker compose -f docker-compose.lightweight.yml config > /dev/null && echo OK`
Expected: `OK`.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml docker-compose.lightweight.yml docker-compose-kafka-ssl.yml cf-application/src/main/resources/application.yml certs/netsuiteConfiguration.md
git commit -m "chore: [LOB-2166] drop NetSuite credential env vars, add encryption key"
```

---

# Phase 6 — Frontend (`cf-lob-frontend`)

All paths in this phase are relative to `/Users/thkammer/Documents/dev/cardano/typescript/cf-lob-frontend`.

### Task 18: Permissions, API layer and query models

**Files:**
- Modify: `public/permissions.global.js`
- Create: `src/libs/api-connectors/backend-connector-lob/api/netsuite-config/netsuite-config-api.types.ts`
- Create: `src/libs/api-connectors/backend-connector-lob/api/netsuite-config/netsuite-config-api.service.ts`
- Modify: `src/libs/api-connectors/backend-connector-lob/api/backendLobApi.ts`
- Create: `src/libs/models/netsuite-config/GetNetsuiteConfigStatusModel.service.ts`
- Create: `src/libs/models/netsuite-config/CreateNetsuiteConfigModel.service.ts`
- Create: `src/libs/models/netsuite-config/UpdateNetsuiteConfigModel.service.ts`

- [ ] **Step 1: Add the permission resource**

In `public/permissions.global.js`, add to `reeve_admin` only:

```javascript
    netsuite_configuration: { view: true, create: true, edit: true },
```

and to `reeve_account_manager`, `reeve_accountant` and `reeve_auditor`:

```javascript
    netsuite_configuration: { view: false, create: false, edit: false },
```

- [ ] **Step 2: Write the API types**

```typescript
export type NetsuiteSyncState = 'PENDING' | 'APPLIED' | 'FAILED';

export interface NetsuiteConfigStatus {
  configured: boolean;
  baseUrl?: string;
  tokenUrl?: string;
  clientId?: string;
  certificateId?: string;
  privateKeyFingerprint?: string;
  syncState?: NetsuiteSyncState;
  syncMessage?: string;
  netsuiteValid?: boolean | null;
  lastValidatedAt?: string;
  validationMessage?: string;
  updatedAt?: string;
  updatedBy?: string;
}

export interface NetsuiteConfigCreateBody {
  baseUrl: string;
  tokenUrl: string;
  clientId: string;
  certificateId: string;
  privateKey: string;
}

/** privateKey omitted keeps the stored key. */
export type NetsuiteConfigUpdateBody = Omit<NetsuiteConfigCreateBody, 'privateKey'> & {
  privateKey?: string;
};
```

- [ ] **Step 3: Write the API service**

```typescript
import { httpService } from 'services';

import {
  NetsuiteConfigCreateBody,
  NetsuiteConfigStatus,
  NetsuiteConfigUpdateBody,
} from './netsuite-config-api.types';

const basePath = (organisationId: string) =>
  `/api/v1/organisations/${organisationId}/netsuite-configuration`;

export const getNetsuiteConfigStatus = (organisationId: string) =>
  httpService.get<NetsuiteConfigStatus>(`${basePath(organisationId)}/status`);

export const createNetsuiteConfig = (organisationId: string, body: NetsuiteConfigCreateBody) =>
  httpService.post<NetsuiteConfigStatus>(basePath(organisationId), body);

export const updateNetsuiteConfig = (organisationId: string, body: NetsuiteConfigUpdateBody) =>
  httpService.put<NetsuiteConfigStatus>(basePath(organisationId), body);
```

Open `cost-centers-api.service.ts` first and match its exact `httpService` call signature — if it passes config objects or unwraps `.data`, do the same here.

- [ ] **Step 4: Register in `backendLobApi.ts`**

Add `netsuiteConfigApi` to the returned object, following the existing entries exactly.

- [ ] **Step 5: Write the query models**

`GetNetsuiteConfigStatusModel.service.ts` — note the polling, which is what makes the *Checking…* chip resolve on its own:

```typescript
import { useQuery } from '@tanstack/react-query';

import { backendLobApi } from 'libs/api-connectors/backend-connector-lob/api/backendLobApi';

export const NETSUITE_CONFIG_STATUS_KEY = 'netsuite-config-status';

export const useGetNetsuiteConfigStatus = (organisationId: string) =>
  useQuery({
    queryKey: [NETSUITE_CONFIG_STATUS_KEY, organisationId],
    queryFn: () => backendLobApi().netsuiteConfigApi.getNetsuiteConfigStatus(organisationId),
    enabled: Boolean(organisationId),
    // The verdict arrives asynchronously from the netsuite module. Poll only while it is
    // still unknown; a manual refresh is the guaranteed path, this is a convenience.
    refetchInterval: (query) =>
      query.state.data?.syncState === 'PENDING' ? 3000 : false,
  });
```

Write `CreateNetsuiteConfigModel.service.ts` and `UpdateNetsuiteConfigModel.service.ts` as `useMutation` wrappers that invalidate `[NETSUITE_CONFIG_STATUS_KEY, organisationId]` on success, mirroring `CreateCostCenterModel.service.ts`.

- [ ] **Step 6: Verify the build**

Run: `cd /Users/thkammer/Documents/dev/cardano/typescript/cf-lob-frontend && npm run build`
Expected: build succeeds with no type errors.

- [ ] **Step 7: Commit**

```bash
git add public/permissions.global.js src/libs/api-connectors/backend-connector-lob/api/netsuite-config/ src/libs/api-connectors/backend-connector-lob/api/backendLobApi.ts src/libs/models/netsuite-config/
git commit -m "feat: [LOB-2166] add NetSuite configuration API and query models"
```

### Task 19: Settings view

**Files:**
- Create: `src/modules/settings/views/netsuite-configuration/netsuite-configuration.component.tsx`
- Create: `src/modules/settings/views/netsuite-configuration/netsuite-configuration.hooks.ts`
- Create: `src/modules/settings/views/netsuite-configuration/components/NetsuiteStatusChip.component.tsx`
- Modify: `src/consts/routes/routes.consts.ts`, `src/modules/settings/settings.routes.tsx`
- Modify: `src/libs/layout-kit/layout-auth/components/NavigationSidebar/NavigationSidebar.service.tsx`
- Modify: `src/libs/translations/en-US.json`

- [ ] **Step 1: Write the status chip**

The five-state mapping from spec §12.1, as one pure function so it is testable and cannot drift:

```tsx
import { Chip } from '@mui/material';

import { NetsuiteConfigStatus } from 'libs/api-connectors/backend-connector-lob/api/netsuite-config/netsuite-config-api.types';

type ChipState = {
  label: string;
  color: 'default' | 'info' | 'success' | 'error' | 'warning';
  detail?: string;
};

export const resolveChipState = (status?: NetsuiteConfigStatus): ChipState => {
  if (!status?.configured) {
    return { label: 'Not configured', color: 'default' };
  }
  if (status.syncState === 'FAILED') {
    return { label: 'Not applied', color: 'error', detail: status.syncMessage };
  }
  if (status.syncState === 'PENDING' || status.netsuiteValid === null || status.netsuiteValid === undefined) {
    return { label: 'Checking…', color: 'info' };
  }
  return status.netsuiteValid
    ? { label: 'Connected', color: 'success' }
    : { label: 'Credentials rejected', color: 'warning', detail: status.validationMessage };
};

export const NetsuiteStatusChip = ({ status }: { status?: NetsuiteConfigStatus }) => {
  const { label, color, detail } = resolveChipState(status);

  return <Chip label={label} color={color} title={detail} size="small" />;
};
```

- [ ] **Step 2: Write the form hook**

Copy the structure of `src/modules/organizationDetails/hooks/useOrganization.tsx`. Requirements:
- `useSelectedOrganisation()` supplies the organisation id; there is no picker.
- Yup schema requires `baseUrl`, `tokenUrl`, `clientId`, `certificateId`; requires `privateKey` **only when** `status.configured` is false.
- On submit, call create when `!status.configured`, otherwise update; **omit `privateKey` from the update body entirely when the field is untouched or blank.**
- After a successful submit, leave edit mode; the chip shows *Checking…* and the polling query resolves it.
- Surface `409` and `404` inline; there are no other synchronous error cases.

- [ ] **Step 3: Write the view component**

Copy `ViewOrganizationDetails.component.tsx`. The private key field is the only novel part: when `status.configured`, render the fingerprint as read-only text plus a "Replace key" button that reveals an empty `FieldPassword`; when not configured, render the field directly and required.

- [ ] **Step 4: Wire the route, nav entry and translations**

Add `SETTINGS_NETSUITE_CONFIGURATION` to `routes.consts.ts`, wrap the route in `<RequirePermission resource="netsuite_configuration" action="view">` in `settings.routes.tsx` exactly as the `organization_details` route does, add a nav entry gated with `enabled: hasPermission('netsuite_configuration', 'view')`, and add every user-facing string to `en-US.json`.

- [ ] **Step 5: Verify build and lint**

Run: `cd /Users/thkammer/Documents/dev/cardano/typescript/cf-lob-frontend && npm run build && npm run lint`
Expected: both succeed.

- [ ] **Step 6: Commit**

```bash
git add src/modules/settings/views/netsuite-configuration/ src/consts/routes/routes.consts.ts src/modules/settings/settings.routes.tsx src/libs/layout-kit/ src/libs/translations/en-US.json
git commit -m "feat: [LOB-2166] add NetSuite configuration settings page"
```

---

# Phase 7 — Architecture verification

### Task 20: Module boundary test

**Files:**
- Create: `organisation/src/test/java/org/cardanofoundation/lob/app/organisation/ModuleBoundaryTest.java`

This is the only automated enforcement of spec D3. There is no ArchUnit in the repository, so the test walks sources directly rather than adding a dependency.

- [ ] **Step 1: Write the test**

```java
package org.cardanofoundation.lob.app.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The organisation module must never read the netsuite module's tables or types.
 * <p>
 * In a decentralized deployment the netsuite tables hold no data on the organisation tier,
 * so any such reference would work in a merged deployment and fail silently in a split one.
 * Nothing else in this repository enforces module boundaries.
 */
class ModuleBoundaryTest {

    @Test
    void organisationDoesNotDependOnTheNetsuiteModule() throws IOException {
        Path sourceRoot = Path.of("src/main/java");

        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("netsuite_altavia_erp_adapter");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(sourceRoot::relativize)
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("organisation module must not reference the netsuite module — "
                            + "cross-module access must go through domain events only")
                    .isEmpty();
        }
    }
}
```

- [ ] **Step 2: Run the test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :organisation:test --tests '*ModuleBoundaryTest*'`
Expected: PASS.

- [ ] **Step 3: Confirm no new Gradle edge was introduced**

Run: `grep -n "project(" organisation/build.gradle.kts`
Expected: no `netsuite_altavia_erp_adapter` entry.

- [ ] **Step 4: Commit**

```bash
git add organisation/src/test/java/org/cardanofoundation/lob/app/organisation/ModuleBoundaryTest.java
git commit -m "test: [LOB-2166] enforce organisation/netsuite module boundary"
```

### Task 21: Full build and Codex adversarial review

- [ ] **Step 1: Run the whole platform test suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean test`
Expected: BUILD SUCCESSFUL. Every pre-existing test still passes.

- [ ] **Step 2: Publish and build the application repo**

```bash
cd /Users/thkammer/Documents/dev/cardano/java/cf-reeve-platform && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew publishToMavenLocal
cd /Users/thkammer/Documents/dev/cardano/java/cf-reeve-application && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

- [ ] **Step 3: Codex adversarial review**

Run `/codex:adversarial-review` over the full diff across all three repositories, directing it specifically at:
- the decentralized deployment: does anything break when `lob.netsuite.enabled=false` on the organisation tier, or vice versa?
- unwanted dependencies: any new coupling between `organisation` and `netsuite_altavia_erp_adapter` beyond the domain events?
- the publish-after-commit ordering in `NetSuiteConfigAdminService`;
- key handling: can the private key or its plaintext reach a log, a `toString()`, or the organisation schema?
- revision handling: can a replayed or out-of-order event downgrade a newer configuration?

- [ ] **Step 4: Address findings, then commit**

Fix anything confirmed, re-run the affected test suites, and commit.

---

## Self-Review

**Spec coverage.** §7 crypto → Tasks 1–2. §8.1 projection → Task 4. §8.2 authoritative store → Task 9. §9 API → Tasks 5, 8. §10 events → Task 3; §10.1 Kafka → Task 15; §10.2 null-key reuse → Task 12 (`keepsTheStoredKeyWhenTheEventCarriesNone`, `failsWhenNoKeyIsSuppliedAndNoneIsStored`). §11 client resolution → Tasks 10, 11, 14, 16. §12 frontend → Tasks 18, 19; §12.1 five states → Task 19 `resolveChipState`. §13 application repo → Tasks 15, 17. §14 security → Task 3 (`toString` exclusion), Task 4 (no secret column), Task 20 (boundary). §15 testing → embedded per task. §16 rollout → Task 17 documentation. §6.1 publish-after-commit → Task 6.

**Gap found and closed:** the spec's §15 asks for a test that a rolled-back transaction publishes nothing. That is a Spring integration concern rather than a unit test, and the organisation module has no `@SpringBootTest` harness. Task 6 instead enforces the property structurally by splitting `persist` (`@Transactional`) from `publish` (not transactional), and the design note states why they must not be merged. Recorded here rather than left implicit.

**Placeholder scan:** no TBD/TODO. Tasks 8, 14, 18 and 19 deliberately instruct the implementer to read a named existing file and match its exact idiom rather than guessing a signature — the surrounding code is given, the pattern source is named.

**Type consistency:** `SecretCipher.encrypt/decrypt` used identically in Tasks 6, 11, 16. `NetSuiteConfigUpsertedEvent.getPrivateKeyEncrypted()` nullable in Tasks 3, 6, 12. `NetSuiteClientRegistry.forOrganisation` returns `Either<ProblemDetail, NetSuiteClient>` in Tasks 11, 12, 14, and `evict` in Tasks 11, 12. `NetSuiteConfigService.apply` returns `NetSuiteConfigAppliedEvent` in Tasks 12, 13. `NetSuiteClient` constructor parameter order is identical in Tasks 10, 11.
