# l1_metadata Extraction Implementation Plan (spec steps 0–1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the duplicated CIP-1447 `metadata`/`org` section building into a new dependency-free `blockchain_common` module, and move `Cip170MetadataFactory` there, without changing a single byte of on-chain output for any publish type.

**Architecture:** `blockchain_common` is a **primitives-only** module: pure functions from scalars to `MetadataMap`. It holds no entity types and depends on no other platform module. The four concrete serialisers stay where they are in `blockchain_publisher` and call into it. This is a deliberate narrowing of spec §10 step 1 — see "Design correction" below.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), Spring Boot 3.5.8, cardano-client-lib (`MetadataBuilder`/`MetadataMap`), JUnit 5 + AssertJ.

## Design correction to the spec

Spec §10 step 1 says the four serialisers "collapse onto" a shared `L1ManifestSerialiser` and that `DocumentIpfsSerialiser` moves into `blockchain_common`. **That is not implementable as written**, for two reasons found while surveying the code:

1. **Dependency cycle.** Every serialiser's public signature takes a `blockchain_publisher` entity (`DocumentEntity`, `ReportEntity`, `Set<TransactionEntity>`, `Set<SpendingEventEntity>`) and the `domain.entity.txs.Organisation` value object. Moving the serialiser classes into `blockchain_common` would make `blockchain_common` depend on `blockchain_publisher`, while `blockchain_publisher` must depend on `blockchain_common` — illegal. `DocumentIpfsSerialiser.serialise(DocumentEntity)` has the same problem.
2. **The sections are not byte-identical.** The `metadata` put-sequence is the same in all four, but each class has its own `VERSION` constant: Document `"1.0"`, API3 `"1.2"`, API1 `"1.1"`, SpendingEvent `"1.0"`. A shared helper with a hardcoded version would change on-chain bytes for three of the four types. And API1 attaches `org` **conditionally** (only when `isOrganisationCollapsable`), nesting it per-transaction otherwise, unlike the other three.

**Resolution:** `blockchain_common` exposes primitives-only builders — `metadataSection(long creationSlot, Instant timestamp, String version)` and `orgSection(String id, String name, String taxIdNumber, String currencyId, String countryCode)`. Callers pass fields, so no entity type crosses the boundary and no cycle exists. Per-serialiser `VERSION` and API1's conditional attachment are preserved at the call site. `Cip170MetadataFactory` moves wholesale because it is already primitives-only. `DocumentIpfsSerialiser` **stays in `blockchain_publisher` for now**; making it callable from `document_vault` is a D15 concern and belongs to Plan 2.

This still achieves D7's actual goal — one place owns the shared shape — with a fraction of the blast radius.

## Global Constraints

- **JDK 21 only.** Every Gradle invocation must run with `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. The default JDK 26 breaks Gradle's Kotlin DSL.
- **Zero on-chain byte change.** No task may alter the CBOR emitted by any of the four serialisers. Task 1's characterization tests are the gate and must pass unchanged after every subsequent task.
- **Never run two Gradle builds concurrently** in this repo — they share `build/` and produce unreliable results.
- **Branch `feat/document-module`.** Commit per task; do not push.
- `blockchain_publisher` must not gain a dependency on `document_vault` or `keri_attestation` (it already has both; this plan does not remove them — that is Plan 2 — but must not add more).
- Preserve the exact `IllegalArgumentException` message `"Organisation not found for id: %s"` wherever org lookup is touched.
- Do not add an `OrganisationPublicApi` dependency to `API1MetadataSerialiser` or `SpendingEventMetadataSerialiser` — they deliberately read the embedded `Organisation` off the entity graph and have no "organisation not found" failure mode.

## File Structure

**Created:**
- `l1_metadata/build.gradle.kts` — new module; depends on nothing but cardano-client metadata.
- `l1_metadata/src/main/java/org/cardanofoundation/lob/app/l1_metadata/L1MetadataSections.java` — the two shared section builders.
- `l1_metadata/src/main/java/org/cardanofoundation/lob/app/l1_metadata/Cip170MetadataFactory.java` — moved from `keri_attestation`.
- `l1_metadata/src/test/java/org/cardanofoundation/lob/app/l1_metadata/L1MetadataSectionsTest.java`
- `blockchain_publisher/src/test/java/org/cardanofoundation/lob/app/blockchain_publisher/service/publish/CborCharacterizationTest.java` — the byte-level safety net for all four serialisers.

**Modified:**
- `settings.gradle.kts` — add `":l1_metadata"`.
- `blockchain_publisher/build.gradle.kts` — add `implementation(project(":l1_metadata"))`.
- `keri_attestation/build.gradle.kts` — add `implementation(project(":l1_metadata"))`.
- The four serialisers, to call the shared builders.
- Every importer of `keri_attestation...Cip170MetadataFactory` (import line only).
- `/Users/thkammer/Documents/dev/cardano/java/cf-reeve-application/cf-application/build.gradle.kts` — spec step 0.

---

### Task 1: Characterization tests pinning current CBOR output

No golden/snapshot infrastructure exists in this repo — existing serialiser tests assert individual fields, never bytes. This task creates the safety net **before** any production code moves. These tests must pass on unmodified code.

**Files:**
- Create: `blockchain_publisher/src/test/java/org/cardanofoundation/lob/app/blockchain_publisher/service/publish/CborCharacterizationTest.java`

**Interfaces:**
- Consumes: the four serialisers' existing public methods, unchanged.
- Produces: a hex-encoded CBOR assertion per serialiser that later tasks must keep green.

- [x] **Step 1: Write the characterization test**

Build one fixture per serialiser with a **fixed** `Clock` so `timestamp` is deterministic, serialise, CBOR-encode, hex it, and assert against the literal produced by the current code.

```java
package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import org.junit.jupiter.api.Test;

/**
 * Byte-level safety net for the l1_metadata extraction. These assertions pin the CBOR the four
 * 1447 serialisers emit today; any refactor that changes on-chain output fails here first.
 * The expected hex literals are produced by running this test against unmodified code and
 * pasting the actual value — that is intentional, not a shortcut.
 */
class CborCharacterizationTest {

    static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    static final long CREATION_SLOT = 1_000_000L;

    static String hex(MetadataMap map) {
        return HexFormat.of().formatHex(CborSerializationUtil.serialize(map.getMap()));
    }

    @Test
    void documentManifestCborIsUnchanged() {
        MetadataMap map = DocumentFixtures.serialiser(FIXED)
                .serialiseToMetadataMap(DocumentFixtures.entity(), "QmFixedCidForTest", CREATION_SLOT);
        assertThat(hex(map)).isEqualTo(DocumentFixtures.EXPECTED_CBOR_HEX);
    }
}
```

Add one `@Test` per serialiser following the same shape. Reuse the fixture builders the existing `DocumentMetadataSerialiserTest`, `API3MetadataSerialiserTest`, `API1MetadataSerialiserTest` and `SpendingEventMetadataSerialiserTest` already use rather than writing new ones — extract them to package-private `*Fixtures` helpers if they are currently inline.

- [x] **Step 2: Run with a deliberately wrong expected value to confirm the test can fail**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :blockchain_publisher:test --tests '*CborCharacterizationTest*'
```

Expected: FAIL, with the assertion printing the actual hex. Copy each actual value into the corresponding `EXPECTED_CBOR_HEX` constant.

- [x] **Step 3: Re-run to confirm all four pass**

```bash
./gradlew :blockchain_publisher:test --tests '*CborCharacterizationTest*'
```

Expected: PASS, 4 tests.

- [x] **Step 4: Commit**

```bash
git add blockchain_publisher/src/test/java/org/cardanofoundation/lob/app/blockchain_publisher/service/publish/CborCharacterizationTest.java
git commit -m "test(blockchain_publisher): pin 1447 serialiser CBOR output before l1_metadata extraction"
```

---

### Task 2: Create the `blockchain_common` module with primitives-only section builders

**Files:**
- Modify: `settings.gradle.kts`
- Create: `l1_metadata/build.gradle.kts`
- Create: `l1_metadata/src/main/java/org/cardanofoundation/lob/app/l1_metadata/L1MetadataSections.java`
- Create: `l1_metadata/src/test/java/org/cardanofoundation/lob/app/l1_metadata/L1MetadataSectionsTest.java`

**Interfaces:**
- Produces: `L1MetadataSections.metadataSection(long creationSlot, Instant timestamp, String version) -> MetadataMap` and `L1MetadataSections.orgSection(String id, String name, String taxIdNumber, String currencyId, String countryCode) -> MetadataMap`. Task 4 calls both.

- [x] **Step 1: Add the module to settings**

In `settings.gradle.kts`, add `":l1_metadata",` to the `include(...)` list, after `":blockchain_common",`.

- [x] **Step 2: Create the module build file**

`l1_metadata/build.gradle.kts`:

```kotlin
dependencies {
}
```

Empty, exactly like `blockchain_common/build.gradle.kts` — the root `subprojects {}` block already supplies Spring, vavr and the cardano-client artifacts. If `com.bloxbean.cardano.client.metadata` does not resolve, add the single artifact that provides it and nothing else.

- [x] **Step 3: Write the failing test**

`l1_metadata/src/test/java/org/cardanofoundation/lob/app/l1_metadata/L1MetadataSectionsTest.java`:

```java
package org.cardanofoundation.lob.app.l1_metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;

import com.bloxbean.cardano.client.metadata.MetadataMap;
import org.junit.jupiter.api.Test;

class L1MetadataSectionsTest {

    @Test
    void metadataSectionEmitsSlotTimestampVersionInOrder() {
        MetadataMap m = L1MetadataSections.metadataSection(42L, Instant.parse("2026-01-01T00:00:00Z"), "1.2");

        assertThat(m.keys()).containsExactly("creation_slot", "timestamp", "version");
        assertThat(m.get("creation_slot")).isEqualTo(BigInteger.valueOf(42L));
        assertThat(m.get("timestamp")).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(m.get("version")).isEqualTo("1.2");
    }

    @Test
    void orgSectionEmitsFiveFieldsInOrder() {
        MetadataMap m = L1MetadataSections.orgSection("org-1", "Acme", "TAX-1", "ISO4217:CHF", "CH");

        assertThat(m.keys()).containsExactly("id", "name", "tax_id_number", "currency_id", "country_code");
        assertThat(m.get("name")).isEqualTo("Acme");
    }
}
```

If `MetadataMap` has no `keys()` accessor, assert order by CBOR-encoding and comparing hex against a literal captured the same way as Task 1.

- [x] **Step 4: Run to verify it fails**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :l1_metadata:test
```

Expected: FAIL — `L1MetadataSections` does not exist.

- [x] **Step 5: Write the implementation**

`l1_metadata/src/main/java/org/cardanofoundation/lob/app/l1_metadata/L1MetadataSections.java`:

```java
package org.cardanofoundation.lob.app.l1_metadata;

import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

/**
 * The two CIP-1447 sections every publishable type emits identically. Primitives in, MetadataMap
 * out — deliberately no entity types, so this module depends on nothing and no module that owns
 * entities has to depend on it in the wrong direction.
 *
 * <p>{@code version} is a parameter, not a constant: each publishable type carries its own
 * ({@code 1.0} document, {@code 1.1} transactions, {@code 1.2} reports, {@code 1.0} spending
 * events), and hardcoding one would change on-chain bytes for the others.
 */
public final class L1MetadataSections {

    private L1MetadataSections() {
    }

    public static MetadataMap metadataSection(long creationSlot, Instant timestamp, String version) {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        metadataMap.put("creation_slot", BigInteger.valueOf(creationSlot));
        metadataMap.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp));
        metadataMap.put("version", version);

        return metadataMap;
    }

    public static MetadataMap orgSection(String id,
                                         String name,
                                         String taxIdNumber,
                                         String currencyId,
                                         String countryCode) {
        MetadataMap orgMap = MetadataBuilder.createMap();
        orgMap.put("id", id);
        orgMap.put("name", name);
        orgMap.put("tax_id_number", taxIdNumber);
        orgMap.put("currency_id", currencyId);
        orgMap.put("country_code", countryCode);

        return orgMap;
    }
}
```

- [x] **Step 6: Run to verify it passes**

```bash
./gradlew :l1_metadata:test
```

Expected: PASS, 2 tests.

- [x] **Step 7: Commit**

```bash
git add settings.gradle.kts l1_metadata
git commit -m "feat(l1_metadata): new module with shared CIP-1447 metadata/org section builders"
```

---

### Task 3: Move `Cip170MetadataFactory` into `blockchain_common`

It is already primitives-only (`attestMap(String, String, String)`, `authBeginMap(...)`, `digestOf(MetadataMap)`) with no injected dependencies, so it moves wholesale. Plan 2 needs it reachable from `blockchain_publisher` without depending on `keri_attestation`.

**Files:**
- Create: `l1_metadata/src/main/java/org/cardanofoundation/lob/app/l1_metadata/Cip170MetadataFactory.java`
- Delete: `keri_attestation/src/main/java/org/cardanofoundation/lob/app/keri_attestation/service/Cip170MetadataFactory.java`
- Modify: `keri_attestation/build.gradle.kts`, plus the import line in every file referencing the class.
- Move: its test class alongside it.

**Interfaces:**
- Produces: `org.cardanofoundation.lob.app.l1_metadata.Cip170MetadataFactory` with identical method signatures. Plan 2's `AuthBeginL1TransactionCreator` consumes it.

- [x] **Step 1: Find every reference**

```bash
grep -rln "Cip170MetadataFactory" --include=*.java . | grep -v worktrees
```

Record the list; every one needs its import updated.

- [x] **Step 2: Move the class and its test**

```bash
git mv keri_attestation/src/main/java/org/cardanofoundation/lob/app/keri_attestation/service/Cip170MetadataFactory.java \
       l1_metadata/src/main/java/org/cardanofoundation/lob/app/l1_metadata/Cip170MetadataFactory.java
```

Change its `package` line to `org.cardanofoundation.lob.app.l1_metadata;`. Move the matching test the same way if one exists (check `keri_attestation/src/test` for `Cip170MetadataFactoryTest`).

- [x] **Step 3: Add the dependency and fix imports**

In `keri_attestation/build.gradle.kts` add `implementation(project(":l1_metadata"))`. In each file from Step 1, replace
`import org.cardanofoundation.lob.app.keri_attestation.service.Cip170MetadataFactory;`
with
`import org.cardanofoundation.lob.app.l1_metadata.Cip170MetadataFactory;`.

- [x] **Step 4: Verify both modules compile and their tests pass**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :l1_metadata:test :keri_attestation:test
```

Expected: PASS. `keri_attestation`'s suite was 191/191 green at last full run; it must stay at that count.

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(l1_metadata): move Cip170MetadataFactory out of keri_attestation"
```

---

### Task 4: Rewire the four serialisers onto the shared builders

**Files:**
- Modify: `blockchain_publisher/build.gradle.kts`
- Modify: `.../publish/module/document/DocumentMetadataSerialiser.java`
- Modify: `.../publish/module/report/API3MetadataSerialiser.java`
- Modify: `.../publish/module/transaction/API1MetadataSerialiser.java`
- Modify: `.../publish/module/spendingevent/SpendingEventMetadataSerialiser.java`

**Interfaces:**
- Consumes: `L1MetadataSections.metadataSection(...)` / `.orgSection(...)` from Task 2.
- Produces: no signature changes. Every serialiser keeps its exact public method.

- [x] **Step 1: Add the dependency**

In `blockchain_publisher/build.gradle.kts` add `implementation(project(":l1_metadata"))`.

- [x] **Step 2: Replace each private `createMetadataSection` body**

In all four, delete the private helper and call the shared one, passing that class's own `VERSION`. For example in `API3MetadataSerialiser`, replace the `createMetadataSection(long creationSlot)` method and its call site with:

```java
globalMetadataMap.put("metadata", L1MetadataSections.metadataSection(creationSlot, Instant.now(clock), VERSION));
```

Keep each class's `VERSION` constant exactly as it is (`"1.0"` / `"1.1"` / `"1.2"` / `"1.0"`). Do not unify them.

- [x] **Step 3: Replace each private org-serialising helper body**

Keep the method — only its body changes, so call sites and conditional logic stay untouched. For example in `DocumentMetadataSerialiser`:

```java
private static MetadataMap serialiseOrganisation(Organisation organisation) {
    return L1MetadataSections.orgSection(
            organisation.getId(),
            organisation.getName(),
            organisation.getTaxIdNumber(),
            organisation.getCurrencyId(),
            organisation.getCountryCode());
}
```

Apply the identical body to `API3MetadataSerialiser.serialiseOrganisation`, `API1MetadataSerialiser.serialise(Organisation)` and `SpendingEventMetadataSerialiser.serialise(Organisation)`. **Do not touch API1's `isOrganisationCollapsable` branch** — the conditional top-level `put("org", ...)` must remain conditional, and the per-transaction nested `org` must keep being emitted when it is not collapsable.

- [x] **Step 4: Run the characterization tests — the real gate**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :blockchain_publisher:test --tests '*CborCharacterizationTest*'
```

Expected: PASS, 4 tests, unchanged hex. **If any hex differs, the refactor changed on-chain output — revert and find out why before continuing.**

- [x] **Step 5: Run the full publisher suite**

```bash
./gradlew :blockchain_publisher:test
```

Expected: PASS, including `DocumentMetadataSerialiserTest`, `API3MetadataSerialiserTest`, `API1MetadataSerialiserTest`, `SpendingEventMetadataSerialiserTest` and the two schema round-trip tests (`serialisedManifestValidatesAgainstSchema`, `serialisedBundleValidatesAgainstSchema`).

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(blockchain_publisher): serialisers use shared l1_metadata sections"
```

---

### Task 5: Declare platform modules explicitly in cf-application (spec step 0)

`document_vault` and `blockchain_reader` currently reach `cf-application` only transitively through `blockchain_publisher`. Plan 2 removes that path, so declare them now while it is a no-op.

**Files:**
- Modify: `/Users/thkammer/Documents/dev/cardano/java/cf-reeve-application/cf-application/build.gradle.kts`

- [x] **Step 1: Add the two declarations**

After the `keri_attestation` line in the `dependencies` block:

```kotlin
implementation("org.cardanofoundation:cf-lob-platform-document_vault:${property("cfLobPlatformVersion")}")
implementation("org.cardanofoundation:cf-lob-platform-blockchain_reader:${property("cfLobPlatformVersion")}")
```

- [x] **Step 2: Publish the platform locally so the app can resolve it**

`cf-application` pins `cfLobPlatformVersion = "1.7.0"` and lists `mavenLocal()` first, so local platform changes are picked up only after publishing.

```bash
cd /Users/thkammer/Documents/dev/cardano/java/cf-reeve-platform
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew publishToMavenLocal
```

Expected: BUILD SUCCESSFUL, including a `cf-lob-platform-blockchain_common` artifact.

- [x] **Step 3: Verify the app still builds**

```bash
cd /Users/thkammer/Documents/dev/cardano/java/cf-reeve-application
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Commit (in the cf-reeve-application repo)**

```bash
git add cf-application/build.gradle.kts
git commit -m "build: declare document_vault and blockchain_reader explicitly"
```

---

### Task 6: Full-suite regression gate

- [x] **Step 1: Run the whole platform build**

```bash
cd /Users/thkammer/Documents/dev/cardano/java/cf-reeve-platform
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew build --continue
```

Expected: same pass/fail set as the pre-change baseline recorded in Task 0 of the session log — no new failures, and the same total test count plus the 6 tests added by Tasks 1–2.

- [x] **Step 2: Confirm no new module dependency crept into blockchain_publisher**

```bash
grep -n "project(" blockchain_publisher/build.gradle.kts
```

Expected: the pre-existing list plus exactly one new line, `implementation(project(":l1_metadata"))`.

---

## Implementation log (2026-07-27)

Deviations and details discovered while executing, recorded so the plan matches what was actually built.

**Baseline (pre-change, commit `2746344a`, JDK 21):** `./gradlew build --continue` → BUILD FAILED, 1924 tests, **1 failure**: `support` `DebouncerTest` ("Wanted but not invoked... zero interactions"). Re-run in isolation 3/3 green, so it is a load-sensitive flake, not a real failure. Reference for regression comparison: 1924 tests, 0 deterministic failures, 1 known flake.

**Task 1** — implemented as `CborCharacterizationTest` with **5** tests, not 4: API1 got two, covering both the organisation-collapsable branch and the non-collapsable branch where `org` is omitted at top level and nested per-transaction. Fixtures deliberately use singleton `Set.of(...)` and single-entry `Map.of(...)` because `Set`/`Map.of` iteration order is salted per JVM run and would otherwise make the CBOR bytes non-deterministic across runs.

**Discovery:** CBOR serialisation sorts map keys canonically (shortest encoding first), so `org` precedes `metadata` on the wire regardless of `put` order — the characterization hex starts `a4636f7267`. Insertion order is therefore cosmetic for byte-identity; the characterization test, not the ordering, is the guard.

**Task 2** — `l1_metadata/build.gradle.kts` is not empty: it needs `implementation("org.cardanofoundation:signify:0.1.2-PR62-d6aea58")` because `Cip170MetadataFactory` derives its digest via signify's CESR `Diger`. Test helpers calling `MetadataMap.getMap()` must declare `throws CborException` (`co.nstant.in.cbor.CborException`).

**Task 3** — an extra file was required that the plan did not anticipate: `l1_metadata/src/main/java/org/cardanofoundation/lob/app/config/L1MetadataModuleConfig.java`. `Cip170MetadataFactory` is a `@Service` that was previously registered only by `KeriAttestationModuleConfig`'s `@ComponentScan("...keri_attestation")` behind `lob.keri-attestation.enabled`. Moving it out of that package would have unregistered the bean for any deployment that scans per-module. The new config is deliberately **unconditional** — three modules (`blockchain_publisher`, `keri_attestation`, `document_vault`) need the helpers independently of each other's flags, so gating on any one flag would break the others.

Three modules needed `implementation(project(":l1_metadata"))`, not one: `keri_attestation`, `blockchain_publisher` and `document_vault` (the last via `CardAttestationDigestFactory`). `implementation` is not transitive, so each is explicit.

Three files referenced `Cip170MetadataFactory` with no import because they shared its old package (`KeriAuthBeginService` and two of its tests); they needed an import **added**, not rewritten.

**Task 4** — the org helper variable name differs per serialiser (`organisation` in Document/API3, `org` in API1/SpendingEvent), and API3's signature uses a fully-qualified parameter type. Method signatures were kept identical so every call site — including API1's `isOrganisationCollapsable` branch — is untouched.

**Post-rewiring characterization gate: PASSED** — `CborCharacterizationTest` 5/5 green on a full `--rerun-tasks` recompile. The CBOR is byte-identical, so the extraction changed no on-chain output for any of the four publish types.

**Full regression gate:** `./gradlew build --continue` → **tests 1933, failures 0, errors 0** (baseline 1924 + the 9 tests added here; even the `DebouncerTest` flake passed). No test regressions anywhere.

**Correction — spotless DOES run in `build`.** An earlier note in this log inferred otherwise from the absence of spotless lines in the baseline log; that inference was wrong. The gate failed on `:blockchain_publisher:spotlessJavaCheck` and `:keri_attestation:spotlessJavaCheck`. Two real defects, both introduced by this work:

- The new `L1MetadataSections` import was inserted before the `blockchain_publisher` imports, violating the declared `importOrder("java", "jakarta", "javax", "lombok", "org.springframework", "", "org.junit", "org.cardanofoundation", "#")` — within the `org.cardanofoundation` group it must sort after `blockchain_publisher` and before `organisation`.
- Delegating the two helpers left `DateTimeFormatter` unused in all four serialisers, and `BigInteger` unused in `API1MetadataSerialiser` and `SpendingEventMetadataSerialiser`. `removeUnusedImports()` catches these.

Fixed with **module-scoped** `./gradlew :blockchain_publisher:spotlessApply :keri_attestation:spotlessApply`, deliberately NOT a repo-wide `spotlessApply` — the latter targets `**/src/**/*.java` across every module and would have reformatted files this work never touched. Verified afterwards that spotless only rewrote files already in the diff, and that `./gradlew spotlessCheck` is green repo-wide.

**Lesson for later plans:** add `./gradlew spotlessCheck` as a per-task verification step, not just at the end. It is cheap (~26s) and catches this class of defect immediately instead of after a 31-minute build.

## Final verification record (2026-07-27)

| Gate | Result |
|---|---|
| `CborCharacterizationTest` after rewiring, `--rerun-tasks` | **5/5 PASS** — CBOR byte-identical for all four publish types |
| `:l1_metadata:test` | 4/4 PASS |
| `./gradlew build --continue` (platform) | tests **1933**, failures **0**, errors **0** (baseline 1924/1) |
| `./gradlew spotlessCheck` (repo-wide) | PASS, after module-scoped `spotlessApply` |
| Affected-module re-run post-spotless | **609** tests, 0 failures, 0 errors |
| `cf-reeve-application` `build -x test` | BUILD SUCCESSFUL |
| `blockchain_common` on `runtimeClasspath` + inside `BOOT-INF/lib/` of the fat jar | Confirmed |

Nothing committed — awaiting review.

## Codex adversarial review of the implemented diff (2026-07-27)

**Verdict: SAFE TO COMMIT** — "the helper extraction itself preserves the serializer call sites and versions; the biggest issue is under-constrained byte-characterization coverage, not observed byte drift." No blockers.

| Severity | Finding | Action |
|---|---|---|
| SHOULD-FIX | `CborCharacterizationTest` **overclaims**. Its javadoc says any byte change "must fail here first", but every fixture is a singleton — one report-data entry, one transaction, one tx item, one spending event — while production iterates real collections. Multi-transaction batches, multi-item transactions, multi-key/nested report data and multi-event bundles are unpinned. The non-collapsable case proves nested `org` *exists*, not that every transaction in a mixed batch gets its own correct one. | Fixed: fixtures moved to deterministically-ordered `LinkedHashSet`/`LinkedHashMap` so multi-entity cases can be pinned without reintroducing `Set.of`/`Map.of` iteration-order flakiness, plus honest javadoc. |
| NIT | `CardAttestationDigestFactory`'s comment said `Cip170MetadataFactory` "only exists when keri_attestation is enabled" — no longer true now that `L1MetadataModuleConfig` is unconditional. Codex confirmed no behavioural break. | Comment corrected. |
| NIT | `ModuleFlagCombinationsTest` constructs `Cip170MetadataFactory` manually and its `ApplicationContextRunner` never imports `L1MetadataModuleConfig`, so that matrix would not catch a broken L1 registration. Not a runtime issue — the real app scans module configs. | Accepted as-is; noted for whoever next touches that matrix. |

The SHOULD-FIX is worth taking seriously beyond this step: later plans touch far more of the API1/API3/SpendingEvent paths, so the safety net needs to cover multi-entity shapes before then, not after.


---

## Revision: no new module — consolidated into `blockchain_common` (2026-07-27)

On review the separate `l1_metadata` module was dropped and its two classes moved into
`blockchain_common/service_assistance`, which already hosts `MetadataChecker`/`JsonSchemaMetadataChecker`
and, in `domain`, `LedgerUpdatedEvent`/`LedgerUpdateType`. All seven publishable-owning modules already
depend on `blockchain_common`, so this removes a module, a published artifact, a build file, three
`implementation(project(...))` lines, one `cf-application` dependency line, and the
`L1MetadataModuleConfig` class outright.

**Bean registration follows the host module's convention.** `blockchain_common` contains **zero**
`@Service`/`@Component` annotations — every bean is an explicit `@Bean` in `BlockchainCommonConfig`. So
`@Service` was removed from `Cip170MetadataFactory` and it is now declared as an ungated `@Bean` there.
That is strictly better than the deleted `L1MetadataModuleConfig`: no component scan is introduced, and
it works identically whether the host application scans broadly or per-module.

**Tradeoff accepted:** `blockchain_common` now carries the `signify` dependency for
`Cip170MetadataFactory`'s CESR `Diger`. Since `blockchain_common` is depended on by
`accounting_reporting_core`, `blockchain_reader`, `document_vault`, `blockchain_publisher`,
`keri_attestation`, `funding` and `reporting`, that jar reaches all of their runtime classpaths. One jar,
in exchange for deleting a module — worth it, but it is a real widening and should not pass unnoticed.

**Mistake made during the move, recorded honestly:** `L1MetadataSections.java` was untracked, so
`git mv` failed on it; the script continued and the subsequent `rm -rf l1_metadata` deleted the file.
It was recreated from scratch. **`git mv` cannot move untracked files — stage new files before
restructuring, or use plain `mv`.**

**Verification after the move:** `blockchain_common` 21 / `keri_attestation` 253 / `document_vault` 141 /
`blockchain_publisher` 203 = **618 tests, 0 failures, 0 errors**. `CborCharacterizationTest` **10/10 with
unchanged hex**, which is the proof the consolidation altered no on-chain bytes. `spotlessCheck` green
repo-wide after module-scoped `spotlessApply`.
