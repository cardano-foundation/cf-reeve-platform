# KERI/Veridian Wallet Attestation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users attest document_vault publish transactions with their own KERI identity in the Veridian mobile wallet, via a frontend-guided popup (OOBI exchange → credential presentation → one-time AUTH_BEGIN → wallet-anchored ATTEST), producing CIP-170 label-170 metadata alongside the existing label-1447 metadata.

**Architecture:** New reusable Gradle module `keri_attestation` (backend-orchestrated ceremony state machine + signify-java agent + REST API), integrated into `blockchain_publisher` (ports: tx submitter, DOCUMENT target provider, metadata freeze, dispatch hook) and `document_vault` (optional `attestationCeremonyId` on publish, fail-closed). Frontend wizard in the cf-lob-frontend `feat+document-module` worktree.

**Tech Stack:** Java 21 / Spring Boot 3.5.8 / Gradle multi-module / vavr Either / signify-java (`org.cardanofoundation:signify:0.1.2-PR62-d6aea58`) / bloxbean cardano-client / Flyway / JPA · React 19 / Vite 7 / MUI v7 / @tanstack/react-query v5 / `qrcode.react` / `qr-scanner`.

**Spec:** `docs/superpowers/specs/2026-07-21-keri-wallet-attestation-design.md` — read it before starting any task. Section references (§) below point there.

## Global Constraints

- **JDK 21 for all Gradle commands**: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` first — default JDK 26 breaks the Kotlin DSL.
- **Never modify** `blockchain_publisher`'s existing KERI code: `config/KeriConfig.java`, `service/KeriService.java`, `API3L1TransactionCreator.java`, nor its config namespace `lob.blockchain-publisher.keri.*`.
- New config namespace is exactly `lob.keri-attestation.*`; module default **off** (`enabled: false`); every consumer must degrade gracefully per spec §3.4.
- CIP-170 field values come from the **pinned CIP commit and the in-repo reference `docs/keri/AttestTransaction.java` / `docs/keri/advanced/PublishExistingCredential.java`**: ATTEST `v = {v:"1.0"}`; AUTH_BEGIN `v = {v:"1.0", k:"KERI10", a:"ACDC10"}` (NOT `KERI10JSON`), `s` = leaf **schema** SAID, `m` includes `l = [1447]`.
- `170.d` is the **direct Blake3-256 Diger qb64 digest of the 1447 metadata CBOR bytes** — never a wrapper-payload SAID (§4.4).
- All service methods return `Either<ProblemDetail, T>` (vavr) or `Optional<ProblemDetail>`; error titles are SCREAMING_SNAKE constants; controllers use a `Responses` helper (copy the document_vault pattern).
- Backend repo work happens on branch `feat/document-module` in `/Users/thkammer/Documents/dev/cardano/java/cf-reeve-platform`. Frontend work happens in `/Users/thkammer/Documents/dev/cardano/typescript/cf-lob-frontend/.claude/worktrees/feat+document-module` (paths in M4 are relative to that worktree).
- Commit after each task with `feat(keri_attestation): …` / `feat(document_vault): …` / `feat(document-vault-ui): …` prefixes; every commit message ends with the Claude co-author trailer.
- Milestone gates: after M2, M3, M4 run the full build + tests via the test-runner subagent, then code-reviewer subagent, then Codex review. Do not start the next milestone before findings are addressed.

---

## Milestone 2 — `keri_attestation` module

### Task 1: Module scaffold + feature flag + context tests

**Files:**
- Modify: `settings.gradle.kts` (add `keri_attestation` to the `include(...)` list, alphabetical position)
- Create: `keri_attestation/build.gradle.kts`
- Create: `keri_attestation/src/main/java/org/cardanofoundation/lob/app/config/KeriAttestationModuleConfig.java`
- Create: `keri_attestation/src/main/java/org/cardanofoundation/lob/app/keri_attestation/config/KeriAttestationProperties.java`
- Test: `keri_attestation/src/test/java/org/cardanofoundation/lob/app/keri_attestation/config/KeriAttestationModuleFlagTest.java`

**Interfaces:**
- Produces: Gradle module `:keri_attestation`; `KeriAttestationProperties` record bound to `lob.keri-attestation.*` with fields `enabled`, `keria.url`, `keria.bootUrl`, `keria.bran`, `identifierName`, `credentialPolicy.schemaSaids (List<String>)`, `credentialPolicy.trustedRootAids (List<String>)`, `ceremonyTtl (Duration)`, `freezeMaxAge (Duration)`, `remotesignTimeout (Duration)`, `notificationPollInterval (Duration)`, `authBeginConfirmations (int)`, `limits.maxActiveCeremoniesPerUser (int)`, `limits.stepCooldown (Duration)`.

- [ ] **Step 1:** Read `document_vault/build.gradle.kts` and `document_vault/src/main/java/org/cardanofoundation/lob/app/config/DocumentVaultModuleConfig.java` to copy the exact module idiom (dependency style, `@ConditionalOnProperty` + `@ComponentScan` shape).
- [ ] **Step 2:** Add `"keri_attestation"` to `settings.gradle.kts`. Create `keri_attestation/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":support"))
    implementation(project(":organisation"))
    implementation(project(":blockchain_common"))
    implementation("org.cardanofoundation:signify:0.1.2-PR62-d6aea58")
}
```

(The signify version must match `blockchain_publisher/build.gradle.kts` line 14 — check and copy verbatim.)
- [ ] **Step 3:** Create `KeriAttestationModuleConfig`:

```java
package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;

@Configuration
@ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "org.cardanofoundation.lob.app.keri_attestation")
@EnableConfigurationProperties(KeriAttestationProperties.class)
@EnableAsync
public class KeriAttestationModuleConfig {
}
```

Create `KeriAttestationProperties` as a `@ConfigurationProperties(prefix = "lob.keri-attestation")` record with the nested records listed under Interfaces, each with sensible defaults (`ceremonyTtl = PT1H`, `freezeMaxAge = PT24H`, `remotesignTimeout = PT3M`, `notificationPollInterval = PT1.5S`, `authBeginConfirmations = 3`, `maxActiveCeremoniesPerUser = 3`, `stepCooldown = PT10S`).
- [ ] **Step 4:** Write `KeriAttestationModuleFlagTest`: an `ApplicationContextRunner` test asserting (a) with `lob.keri-attestation.enabled=false` (and unset) no bean from `org.cardanofoundation.lob.app.keri_attestation` exists, (b) with `=true` the properties bean binds the defaults above.
- [ ] **Step 5:** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :keri_attestation:test` → PASS; `./gradlew compileJava` at root → OK.
- [ ] **Step 6:** Commit `feat(keri_attestation): module scaffold with feature flag`.

### Task 2: Flyway migration, entities, repositories

**Files:**
- Create: `keri_attestation/src/main/resources/db/migration/postgresql/common/V…__lob_service_app_keri_attestation_module.sql` (version prefix: run `find . -path '*/db/migration/*' -name '*.sql' | sort` and pick the next free slot in the platform's scheme — document_vault's latest is the reference)
- Create: `keri_attestation/src/main/java/org/cardanofoundation/lob/app/keri_attestation/domain/entity/KeriIdentityLinkEntity.java`
- Create: `…/domain/entity/KeriAttestationCeremonyEntity.java`
- Create: `…/domain/core/CeremonyState.java`
- Create: `…/repository/KeriIdentityLinkRepository.java`, `…/repository/KeriAttestationCeremonyRepository.java`
- Test: `keri_attestation/src/test/java/…/repository/CeremonyRepositoryTest.java` (copy the H2/postgres test setup used by document_vault repository tests — check `document_vault/src/test` for the pattern)

**Interfaces:**
- Produces:

```java
public enum CeremonyState { CREATED, OOBI_RESOLVED, CREDENTIAL_REQUESTED, CREDENTIAL_RECEIVED,
    AUTH_BEGIN_SUBMITTED, AUTH_BEGIN_CONFIRMED, ATTEST_REQUESTED, ATTEST_ANCHORED,
    CONSUMED, FAILED, EXPIRED }
// KeriIdentityLinkEntity: userId(PK String), bindingVersion(int), aid, oobiUrl,
//   credentialSaid, credentialSchemaSaid, authBeginTxHash, authBeginBlock(Long), authBeginAt(Instant),
//   createdAt, updatedAt
// KeriAttestationCeremonyEntity: id(PK String UUID), userId, bindingVersion(int), targetType, targetId,
//   state(CeremonyState STRING), attemptGeneration(int), errorTitle, errorDetail, requestExnSaid,
//   metadataDigest, metadataLabel, kelSequence, kelEventSaid, createdAt, updatedAt, expiresAt
// KeriAttestationCeremonyRepository extends JpaRepository<KeriAttestationCeremonyEntity, String> with:
//   long countByUserIdAndStateNotIn(String userId, Collection<CeremonyState> terminal);
//   List<KeriAttestationCeremonyEntity> findByUserIdAndStateNotIn(String userId, Collection<CeremonyState> terminal);
//   @Lock(PESSIMISTIC_WRITE) Optional<KeriAttestationCeremonyEntity> findByIdForUpdate(String id);
```

- [ ] **Step 1:** Write the failing repository test: persist a ceremony, reload, assert enum round-trip; `findByIdForUpdate` returns it; `countByUserIdAndStateNotIn` excludes CONSUMED/FAILED/EXPIRED.
- [ ] **Step 2:** Run `./gradlew :keri_attestation:test` → FAIL (classes missing).
- [ ] **Step 3:** Create the SQL migration (both tables, `keri_attestation_ceremony` with index on `(user_id, state)` and `(target_type, target_id)`), entities (`@DynamicUpdate`, `@PrePersist`/`@PreUpdate` timestamps, `@Version` not needed — explicit CAS via state+generation), repositories. Match column types to document_vault's migration style (VARCHAR ids, TIMESTAMP WITHOUT TIME ZONE).
- [ ] **Step 4:** Run tests → PASS. Commit `feat(keri_attestation): identity link and ceremony persistence`.

### Task 3: Ceremony state machine service

**Files:**
- Create: `…/service/CeremonyService.java`, `…/service/KeriAttestationProblems.java`
- Create: `…/domain/view/CeremonyView.java`, `…/domain/view/RequiredSteps.java`, `…/domain/core/ConsumedAttestation.java`
- Create: `…/service/AttestationConsumptionApi.java` (interface, implemented by CeremonyService — this is what document_vault consumes)
- Test: `…/service/CeremonyServiceTest.java`

**Interfaces:**
- Consumes: Task 2 entities/repos; `KeriAttestationProperties`.
- Produces:

```java
public interface AttestationConsumptionApi {
    Either<ProblemDetail, ConsumedAttestation> validateAndConsume(
        String ceremonyId, String targetType, String targetId, String userId);
}
public record ConsumedAttestation(String ceremonyId, String aid, String digestQb64,
                                  String metadataLabel, String kelSequence) {}
public record CeremonyView(String id, CeremonyState state, RequiredSteps requiredSteps,
                           String errorTitle, String errorDetail,
                           String metadataDigest, String kelSequence, String kelEventSaid,
                           String authBeginTxHash) {}
public record RequiredSteps(boolean oobi, boolean credential, boolean authBegin) {}
// CeremonyService public methods:
//   Either<ProblemDetail, CeremonyView> create(String userId, String targetType, String targetId)
//   Either<ProblemDetail, CeremonyView> get(String ceremonyId, String userId)
//   Either<ProblemDetail, KeriAttestationCeremonyEntity> beginStep(String ceremonyId, String userId,
//        CeremonyState expectedState, CeremonyState waitingState, boolean retry)   // CAS + cooldown + generation bump on retry
//   void completeStep(String ceremonyId, int expectedGeneration, CeremonyState from, CeremonyState to,
//        java.util.function.Consumer<KeriAttestationCeremonyEntity> mutator)        // CAS; drops silently on stale generation
//   void failStep(String ceremonyId, int expectedGeneration, CeremonyState expectedWaitingState,
//        String errorTitle, String errorDetail)   // CAS on (state, generation), same as completeStep
// KeriAttestationProblems: titles CEREMONY_NOT_FOUND, CEREMONY_FORBIDDEN, CEREMONY_INVALID_STATE,
//   CEREMONY_EXPIRED, CEREMONY_LIMIT_REACHED, STEP_COOLDOWN, IDENTITY_NOT_LINKED, IDENTITY_RELINKED,
//   KERI_WALLET_TIMEOUT, KERI_STEP_TIMED_OUT, CREDENTIAL_REJECTED, AUTH_BEGIN_ROLLED_BACK,
//   AUTH_BEGIN_UNVERIFIED, ATTESTATION_UNAVAILABLE, OOBI_INVALID, TARGET_MISMATCH
//   + static ProblemDetail factories (copy VaultProblems style: notFound/forbidden/conflict/unprocessable)
```

- [ ] **Step 1:** Write failing tests covering: create fast-forwards state from link (no link → CREATED w/ all steps required; link with aid+credential+confirmed auth-begin → AUTH_BEGIN_CONFIRMED w/ only attest remaining); per-user active-ceremony limit → `CEREMONY_LIMIT_REACHED`; `get` by non-owner → `CEREMONY_FORBIDDEN`; expiry: `expires_at` in the past → state reported EXPIRED; `beginStep` in wrong state → `CEREMONY_INVALID_STATE`; retry before cooldown → `STEP_COOLDOWN`; retry bumps generation; `completeStep` with stale generation mutates nothing; `validateAndConsume` happy path flips ATTEST_ANCHORED→CONSUMED and returns digest/sequence/aid from link+ceremony; double consume → `CEREMONY_INVALID_STATE`; wrong target → `TARGET_MISMATCH`; stale `binding_version` (link relinked after ceremony creation) → `IDENTITY_RELINKED`.
- [ ] **Step 2:** Run → FAIL. Implement `CeremonyService` (`@Service @Transactional`, row-lock via `findByIdForUpdate` for every transition). State fast-forward derives from the link exactly per spec §4.2. Implement `KeriAttestationProblems`.
- [ ] **Step 3:** Add `CeremonyCleanupJob` (`@Component @Scheduled(fixedDelayString = "${lob.keri-attestation.cleanup.fixed_delay:PT10M}")`, same idiom as `document_vault`'s `DocumentDispatchRetryJob`): marks over-TTL non-terminal ceremonies EXPIRED and deletes ceremonies (and, once Task 13 exists, their freeze rows via cascade on ceremony_id is NOT used — blockchain_publisher cleans its own freeze rows for terminal ceremonies in a matching job added in Task 13) older than 7 days in terminal states. Test: expired ceremony transitions; terminal-old ceremony deleted.
- [ ] **Step 4:** Run → PASS. Commit `feat(keri_attestation): ceremony state machine with CAS transitions`.

### Task 4: Agent bootstrap + OOBI services

**Files:**
- Create: `…/config/SignifyClientConfig.java` (beans: `SignifyClient` + agent `IdentifierRecord(prefix, name)` under qualifier `keriAttestationSignifyClient` — names must not collide with the legacy `KeriConfig` beans)
- Create: `…/service/KeriAgentService.java`, `…/service/KeriOobiService.java`
- Test: `…/service/KeriOobiServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `KeriIdentityLinkRepository`; Task 3 problems.
- Produces:

```java
// KeriAgentService:
//   String agentOobi()                       // client.oobis().get(name, "agent") → first oobi string
//   String agentPrefix(); String agentName()
// KeriOobiService:
//   Either<ProblemDetail, String /*aid*/> resolveUserOobi(String userId, String oobiUrl, boolean relink)
```

- [ ] **Step 1:** Failing tests (mock `SignifyClient` deep-stubs): resolve happy path extracts AID via regex `/oobi/([^/]+)` and persists a link with `bindingVersion=1`; invalid URL (non-https, no `/oobi/` segment, >2048 chars) → `OOBI_INVALID` without touching the client; same user re-resolve same AID → refreshes `oobiUrl`, no version bump; different AID without `relink` → `IDENTITY_RELINKED` problem (409); different AID with `relink=true` → bumps `bindingVersion`, clears `credentialSaid/credentialSchemaSaid/authBegin*`, marks the user's non-terminal ceremonies FAILED(`IDENTITY_RELINKED`).
- [ ] **Step 2:** Run → FAIL. Implement. `SignifyClientConfig` copies the connect-with-boot-fallback idiom from the legacy `KeriConfig.signifyClient` (read it, reimplement — do not import), using `KeriAttestationProperties.keria()`. `KeriAgentService` ensures the AID exists at startup exactly like `KeriConfig.createIdentifier`/`createAid` (reimplemented locally; witnesses logic copied). OOBI resolve: `client.oobis().resolve(oobiUrl, alias)` + `client.operations().wait(Operation.fromObject(result))` with a 15s timeout, then `client.contacts().get(aid)` to verify.
- [ ] **Step 3:** Run → PASS. Commit `feat(keri_attestation): agent bootstrap and user OOBI resolution`.

### Task 5: CIP-170 metadata factory (golden vectors)

**Files:**
- Create: `…/service/Cip170MetadataFactory.java`
- Test: `…/service/Cip170MetadataFactoryTest.java`

**Interfaces:**
- Produces:

```java
// Cip170MetadataFactory (stateless @Service):
//   MetadataMap attestMap(String aid, String digestQb64, String kelSequence)
//   MetadataMap authBeginMap(String aid, String leafSchemaSaid, byte[] reducedCesrChain,
//                            Map<String,Object> optionalM /* nullable; contains e.g. "LEI" */,
//                            List<Long> authorizedLabels /* -> m.l */)
//   String digestOf(MetadataMap map1447)   // Blake3-256 Diger qb64 of CborSerializationUtil.serialize(map.getMap())
```

- [ ] **Step 1:** Failing tests, with expected structures taken **verbatim** from the in-repo references:
  - `attestMap`: keys exactly `t="ATTEST"`, `s=<kelSequence>`, `i=<aid>`, `d=<digest>`, `v={v:"1.0"}` (see `docs/keri/AttestTransaction.java:188-198`).
  - `authBeginMap`: `t="AUTH_BEGIN"`, `s=<leafSchemaSaid>`, `i=<aid>`, `c=<MetadataList of 64-byte byte[] chunks>` (last chunk shorter), `v={v:"1.0", k:"KERI10", a:"ACDC10"}`, `m={l:[1447], …optionalM}` (see `PublishExistingCredential.java:219-246`); assert chunk reassembly (concatenation of `c` equals input bytes).
  - `digestOf`: build a small `MetadataMap`, assert result equals `new Diger(new RawArgs(), CborSerializationUtil.serialize(map.getMap())).getQb64()` and starts with `"E"` (Blake3-256 code).
- [ ] **Step 2:** Run → FAIL. Implement (chunking = `Arrays.copyOfRange` loop, 64 bytes; identical to `PublishExistingCredential.splitIntoChunks`).
- [ ] **Step 3:** Run → PASS. Commit `feat(keri_attestation): CIP-170 metadata factory`.

### Task 6: Notification correlator

**Files:**
- Create: `…/service/KeriNotificationCorrelator.java`
- Test: `…/service/KeriNotificationCorrelatorTest.java`

**Interfaces:**
- Produces:

```java
// KeriNotificationCorrelator:
//   Optional<CorrelatedNotification> awaitCorrelated(List<String> routes, String expectedSenderAid,
//       String requestExnSaid, Duration timeout)
//   record CorrelatedNotification(String notificationId, String exnSaid, Map<String,Object> exn)
//   void markAndDelete(String notificationId)
```

- [ ] **Step 1:** Failing tests (mock client): claims only a notification whose route matches AND whose referenced exchange (`client.exchanges().get(said)`) has sender == expectedSenderAid AND whose exn `p` (prior) or embedded ref equals `requestExnSaid`; ignores others (leaves them unread); returns empty on timeout; `markAndDelete` calls `.mark()` then `.delete()`.
- [ ] **Step 2:** Run → FAIL. Implement polling loop at `notificationPollInterval` (structure per `PublishExistingCredential.waitForNotifications`, plus the correlation checks; `Thread.sleep` is fine — callers run on the async executor). NOTE for implementer: the exact field carrying the prior-exn link must be confirmed against a live exchange during the Task 8 spike; code the check against `exn.get("p")` with a fallback to `exn.get("a")`-embedded ref and leave both paths unit-tested.
- [ ] **Step 3:** Run → PASS. Commit `feat(keri_attestation): correlated notification await`.

### Task 7: Credential presentation + chain validation + reduction

**Files:**
- Create: `…/service/KeriCredentialService.java`, `…/service/CredentialChainValidator.java`, `…/service/CesrChainReducer.java`
- Create: `…/src/test/resources/fixtures/` CESR fixtures — export via the `docs/keri` scripts or copy existing fixtures from `docs/keri/test-vlei` (inspect that directory; it contains test chain material)
- Test: `…/service/CesrChainReducerTest.java`, `…/service/CredentialChainValidatorTest.java`, `…/service/KeriCredentialServiceTest.java`

**Interfaces:**
- Consumes: Task 6 correlator; Task 3 `CeremonyService.completeStep/failStep`; Task 2 link repo.
- Produces:

```java
// CesrChainReducer:  byte[] reduceToVcpIssAcdc(String fullCesr)   // port of PublishExistingCredential.strip()
// CredentialChainValidator:
//   Either<ProblemDetail, ValidatedCredential> validate(String fullCesr, String expectedIssueeAid,
//        List<String> allowedSchemaSaids, List<String> trustedRootAids)
//   record ValidatedCredential(String credentialSaid, String schemaSaid)
// KeriCredentialService:
//   Either<ProblemDetail, Void> startPresentation(KeriAttestationCeremonyEntity ceremony)  // sync part: ipex apply + persist requestExnSaid
//   (async continuation) awaitPresentation(ceremonyId, generation)  // offer→agree→grant→admit→fetch CESR→validate→persist link, complete/fail step
```

- [ ] **Step 1:** `CesrChainReducerTest` first (pure function): reduced stream contains exactly vcp+iss+ACDC events in order with attachments, and **round-trips**: `CESRStreamUtil.parseCESRData(new String(reduced))` re-parses without loss. Run → FAIL → implement as a direct port of `strip()` (`PublishExistingCredential.java:168-217`) → PASS.
- [ ] **Step 2:** `CredentialChainValidatorTest`: happy fixture passes and returns leaf said/schema; issuee mismatch → `CREDENTIAL_REJECTED`; schema not allowlisted → `CREDENTIAL_REJECTED`; chain not terminating in a trusted root AID → `CREDENTIAL_REJECTED`; revoked TEL (rev event present for the leaf) → `CREDENTIAL_REJECTED`. Implement by walking the parsed CESR events: ACDC `a.i` == issuee, ACDC `s` in allowlist, follow `e` edges up verifying each issuer, root issuer AID in `trustedRootAids`, and iss-not-followed-by-rev per registry. (This validator works on the *presented* chain contents; deep KEL signature verification stays with KERIA, which already refuses inconsistent streams on fetch.)
- [ ] **Step 3:** `KeriCredentialServiceTest` (mock client + correlator): `startPresentation` sends ipex apply built via `client.exchanges().createExchangeMessage(...)` for route `/ipex/apply` with payload `{m:"", s:<first allowed schema>, a:{}, oobiUrl:<agent oobi>}` to the linked AID and persists the exn SAID on the ceremony; the async continuation drives offer→`IpexAgreeArgs`→grant→`IpexAdmitArgs` (exact call sequence per the cip113 `KeriService.presentCredential` description in the spec §2.2 — the signify-java calls are `client.ipex().submitApply/agree/submitAgree/admit/submitGrant`, check what exists in the installed signify-java jar with `javap` or IDE before coding), then fetches `client.credentials().get(said)` full CESR, validates, persists `credentialSaid/SchemaSaid` on the link, completes the step; validator rejection → `failStep(CREDENTIAL_REJECTED)`; timeout → `failStep(KERI_WALLET_TIMEOUT)`.
- [ ] **Step 4:** All green. Commit `feat(keri_attestation): IPEX presentation, chain validation and reduction`.

### Task 8: Veridian remotesign SPIKE (blocker gate, §4.4)

**Files:**
- Create: `docs/keri/spike/RemotesignAnchorSpike.java` (jbang, modeled on `docs/keri/AttestTransaction.java` header)
- Create: `keri_attestation/README.md` (spike outcome section + pinned CIP-170 commit hash)

**Interfaces:**
- Produces: documented, verified remotesign request KED shape such that the wallet-anchored ixn seal equals a caller-chosen digest; consumed by Task 9.

- [ ] **Step 1:** Write the jbang spike: connect a client, resolve the operator's Veridian wallet OOBI (env `WALLET_OOBI`), build KED `{"d": "<digest qb64 of fixed test bytes>"}` and send `client.exchanges().send(name, "remotesign", hab, "/remotesign/ixn/req", ked, Map.of(), List.of(walletAid))`; await `/remotesign/ixn/ref`; fetch the wallet's KEL (`client.keyStates().query` + fetch event) and print the anchoring event's `a` seal + `s`.
- [ ] **Step 2:** **Manual gate — requires the human operator with a Veridian wallet.** Run it against the Reeve KERIA (URLs in `PublishExistingCredential.java:64-65`), approve in Veridian, and record in `keri_attestation/README.md`: the exact KED shape that produced seal == our digest, the ref exn's structure (where the event/seq lives), and the notification correlation field. **If the wallet only anchors a request-envelope SAID and no KED variant yields the raw digest as seal → STOP, report to the user (spec §4.4 hard blocker) before implementing Task 9.**
- [ ] **Step 3:** Commit `docs(keri): remotesign anchoring spike + findings`.

### Task 9: Attest service + AUTH_BEGIN service

**Files:**
- Create: `…/service/KeriAttestService.java`, `…/service/KeriAuthBeginService.java`
- Create: `…/service/CardanoMetadataTxSubmitter.java` (port interface), `…/service/AttestationTargetProvider.java` (port interface), `…/domain/core/AttestationDigest.java`
- Test: `…/service/KeriAttestServiceTest.java`, `…/service/KeriAuthBeginServiceTest.java`

**Interfaces:**
- Consumes: Tasks 3, 5, 6, 8 findings.
- Produces: the two port interfaces **exactly as in spec §3.3** (copy the code block verbatim), plus:

```java
// KeriAttestService:
//   Either<ProblemDetail, Void> startAttest(String ceremonyId, String userId, boolean retry)
//     - beginStep(…, expected AUTH_BEGIN_CONFIRMED, waiting ATTEST_REQUESTED, retry)
//     - provider = registry.forType(ceremony.targetType) ; provider.authorize(targetId, userId)
//     - prepareDigest(targetId, ceremonyId) → persist metadataDigest/metadataLabel
//     - build KED per Task 8 findings; persist requestExnSaid; exchanges().send(...); dispatch async continuation
//   (async) awaitAnchor(ceremonyId, generation): awaitCorrelated ref → extract event sn + said from ref exn
//     → fetch that KEL event → assert seal d == metadataDigest (mismatch → failStep) → completeStep(persist kelSequence/kelEventSaid → ATTEST_ANCHORED)
// KeriAuthBeginService:
//   Either<ProblemDetail, Void> submitAuthBegin(String ceremonyId, String userId, String externalTxHash /*nullable*/, boolean retry)
//     - externalTxHash != null → verifyExternal: submitter.readCip170Metadata(hash) must contain t=AUTH_BEGIN, i == link aid,
//       s in allowed schemas → persist link.authBeginTxHash=hash → state AUTH_BEGIN_CONFIRMED; else AUTH_BEGIN_UNVERIFIED
//     - else: fetch credential CESR (credentials().get(link.credentialSaid)) → reduce → authBeginMap(aid, schemaSaid, reduced, m, [1447])
//       → submitter.submitMetadataTransaction(170, map) → AUTH_BEGIN_SUBMITTED(txHash) → async poll submitter.confirmations(txHash)
//       until >= authBeginConfirmations → AUTH_BEGIN_CONFIRMED + persist link; poll timeout (rollback window 30 min) → failStep(AUTH_BEGIN_ROLLED_BACK)
// AttestationTargetProviderRegistry: @Service collecting List<AttestationTargetProvider> into a Map<String,Provider>
```

- [ ] **Step 1:** Failing tests for both services with mocked ports/client/correlator, covering every path listed above plus: seal mismatch → `failStep` with digest detail; stale generation on completion → no state change; retry re-checks for a late correlated ref before re-sending (assert exchanges().send NOT called when a matching ref already exists).
- [ ] **Step 2:** Run → FAIL. Implement. Async continuations via a dedicated `@Bean(name="keriAttestationExecutor") ThreadPoolTaskExecutor` (2 threads) and `@Async("keriAttestationExecutor")` methods on a separate `CeremonyAsyncRunner` bean (self-invocation doesn't proxy — keep async methods on their own bean).
- [ ] **Step 3:** Run → PASS. Commit `feat(keri_attestation): wallet-anchored attest and AUTH_BEGIN services`.

### Task 10: REST controller

**Files:**
- Create: `…/resource/KeriAttestationController.java`, `…/resource/Responses.java` (copy `document_vault/…/resource/Responses.java` verbatim, adjust package)
- Create: `…/domain/request/ResolveOobiRequest.java`, `…/domain/request/CreateCeremonyRequest.java`, `…/domain/request/AuthBeginRequest.java`, `…/domain/request/StepRetryRequest.java`
- Create: `…/domain/view/IdentityView.java`, `…/domain/view/AgentOobiView.java`
- Test: `…/resource/KeriAttestationControllerTest.java` (MockMvc slice, mocked services — copy the document_vault controller-test setup)

**Interfaces:**
- Consumes: Tasks 3/4/7/9 services.
- Produces: the REST surface of spec §4.8 — base `@RequestMapping("/api/v1/keri-attestation")`; endpoints `GET /identity`, `GET /agent/oobi`, `POST /identity/oobi/resolve`, `POST /ceremonies`, `POST /ceremonies/{id}/credential/request`, `POST /ceremonies/{id}/auth-begin`, `POST /ceremonies/{id}/attest`, `GET /ceremonies/{id}`; step POSTs return 202, sync ones 200/201; `@PreAuthorize` allows the platform roles that may publish (reference how `VaultDocumentController.publish` guards with `@securityConfig.getManagerRole()/getAdminRole()` — same SpEL here) and user identity comes from the same security-context accessor document_vault uses (find it via `getCurrentUserId` usages in `document_vault`).

- [ ] **Step 1:** Failing MockMvc tests: each endpoint's happy path status + JSON shape; 404 unknown ceremony; 403 non-owner (service returns forbidden problem → mapped status); validation errors (missing oobiUrl) → 400; ProblemDetail body contract (`title` used by the frontend).
- [ ] **Step 2:** Run → FAIL. Implement controller + request DTOs (`@Getter @Setter @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)` extending `BaseRequest`, jakarta validation) + swagger `@Tag`/`@Operation` annotations per platform convention.
- [ ] **Step 3:** Run → PASS. Run the **whole** backend build `./gradlew build -x integrationTest` → green. Commit `feat(keri_attestation): REST API`.

### Task 11 — M2 gate

- [ ] test-runner subagent: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean build` — full suite green, paste summary.
- [ ] code-reviewer subagent over `git diff main...HEAD -- keri_attestation settings.gradle.kts` with the spec as requirements reference.
- [ ] Codex review (`codex exec --sandbox read-only`) of the same diff; address findings; re-run tests.
- [ ] Commit fixes `fix(keri_attestation): M2 review findings`.

---

## Milestone 3 — platform integration

### Task 12: `CardanoMetadataTxSubmitter` implementation

**Files:**
- Create: `blockchain_publisher/src/main/java/…/blockchain_publisher/service/keri/OrganiserWalletMetadataTxSubmitter.java`
- Modify: `blockchain_publisher/build.gradle.kts` (add `implementation(project(":keri_attestation"))`)
- Modify: `blockchain_publisher/src/main/java/…/config/TransactionSubmissionConfig.java` (new `@Bean @ConditionalOnProperty("lob.keri-attestation.enabled")` wiring: organiser `Account`, `BackendService` — reuse the exact params the `documentL1TransactionCreator` bean already receives)
- Test: `blockchain_publisher/src/test/java/…/service/keri/OrganiserWalletMetadataTxSubmitterTest.java`

**Interfaces:**
- Consumes: `CardanoMetadataTxSubmitter` port (Task 9).
- Produces: bean implementing it: `submitMetadataTransaction` builds `Metadata` with `metadata.put(label, map)`, `Tx().payToAddress(organiser, Amount.ada(2.0)).attachMetadata(metadata).from(organiser)`, `quickTxBuilder.compose(tx).withSigner(SignerProviders.signerFrom(organiserWallet)).completeAndWait()` → txHash (mirror `DocumentL1TransactionCreator.serialiseTransaction` + `PublishExistingCredential.buildTransaction`); `confirmations(txHash)` and `readCip170Metadata(txHash)` via `backendService.getTransactionService()/getMetadataService()` (same Blockfrost APIs `AttestTransaction.java:134` uses).

- [ ] **Step 1:** Failing unit tests with mocked `BackendService`/`QuickTxBuilder` collaborators (extract a thin seam if QuickTxBuilder resists mocking — wrap tx submission in a protected method and test the metadata/label assembly + error mapping to ProblemDetail).
- [ ] **Step 2:** Implement + wire. Run module tests → PASS. Commit `feat(blockchain_publisher): metadata tx submitter for keri attestation`.

### Task 13: Freeze record + DOCUMENT target provider

**Files:**
- Create: `blockchain_publisher/src/main/resources/db/migration/postgresql/common/V…__document_attestation_freeze.sql` (next free version)
- Create: `blockchain_publisher/src/main/java/…/domain/entity/documents/DocumentAttestationFreezeEntity.java` + `…/repository/DocumentAttestationFreezeRepository.java`
- Create: `blockchain_publisher/src/main/java/…/service/keri/DocumentAttestationTargetProvider.java`
- Test: `…/service/keri/DocumentAttestationTargetProviderTest.java`

**Interfaces:**
- Consumes: `AttestationTargetProvider` port; `DocumentIpfsSerialiser`, `DocumentMetadataSerialiser`, `IpfsPublisher`, `BlockchainReaderPublicApiIF` (same collaborators as `DocumentL1TransactionCreator` — read that class first); document lookup via the module's existing gateway (find how `DocumentPublishable` loads `DocumentEntity` by organisationId — reuse); vault-side authorization: check the source `VaultDocumentEntity` status/org via document_vault's public service (it's a compile dependency of blockchain_publisher already).
- Produces:

```java
// DocumentAttestationTargetProvider implements AttestationTargetProvider:
//   targetType() -> "DOCUMENT"
//   authorize(documentId, userId): document exists, user has vault publish permission in its organisation,
//       status DRAFT — reuse VaultDocumentService checks; return VaultProblems-style ProblemDetail otherwise
//   prepareDigest(documentId, ceremonyId): serialise envelope → sha256 → ipfs publish → chain tip →
//       DocumentMetadataSerialiser.serialiseToMetadataMap(doc, cid, slot) → Cip170MetadataFactory.digestOf(map)
//       → save DocumentAttestationFreezeEntity(documentId, ceremonyId UNIQUE PAIR, ipfsCid,
//         frozenMetadataCbor = CborSerializationUtil.serialize(map.getMap()), digestQb64,
//         metadataCreationSlot, envelopeSha256, createdAt) → AttestationDigest(digest, "1447")
//       idempotent: existing row for (documentId, ceremonyId) → return its digest
```

- [ ] **Step 1:** Failing tests: happy path persists the freeze and returns the digest; idempotent second call; DRAFT check; digest equals `Cip170MetadataFactory.digestOf` of the same map (determinism); envelope hash recorded.
- [ ] **Step 2:** Migration + entity + provider implementation. Run → PASS. Commit `feat(blockchain_publisher): document attestation freeze + target provider`.

### Task 14: Publish endpoint accepts ceremony (document_vault, fail-closed)

**Files:**
- Modify: `document_vault/build.gradle.kts` (+ `implementation(project(":keri_attestation"))`)
- Modify: `document_vault/src/main/resources/db/migration/…` (new migration: `ALTER TABLE … ADD COLUMN attestation_ceremony_id VARCHAR(64)` on the vault document table — read the module migration to get the exact table name)
- Modify: `document_vault/…/domain/entity/VaultDocumentEntity.java` (+ field), `…/domain/events/DocumentPublishCommand.java` (+ `Optional`-free nullable `String attestationCeremonyId` component), `…/service/VaultDocumentService.java` (`publish(String documentId, @Nullable String attestationCeremonyId)`; update `toPublishCommand`)
- Modify: `document_vault/…/resource/VaultDocumentController.java` (publish endpoint: optional `@RequestBody(required=false) PublishDocumentRequest`)
- Create: `document_vault/…/domain/request/PublishDocumentRequest.java`
- Test: extend `document_vault`'s existing `VaultDocumentService` publish tests
- Modify: `blockchain_publisher/…/service/event_handle/BlockchainPublisherEventHandler.java` + the document dispatch entity/gateway to persist `attestationCeremonyId` from the command (read `storeDocumentForDispatchLater` chain first; add the column to `blockchain_publisher`'s document entity migration from Task 13's file)

**Interfaces:**
- Consumes: `AttestationConsumptionApi` (Task 3) via `ObjectProvider<AttestationConsumptionApi>`.
- Produces: publish flow carrying `attestationCeremonyId` end-to-end: vault row → command → publisher dispatch row.

- [ ] **Step 1:** Failing tests: bodiless publish unchanged (regression tests still green untouched); body with ceremony + module disabled (`ObjectProvider` empty) → 422 `ATTESTATION_UNAVAILABLE`, document stays DRAFT; body with ceremony + consumption success → published, ceremony id persisted on entity and present in emitted command; consumption failure (service returns problem) → 422 propagated, document stays DRAFT (same transaction rolls back nothing — assert status unchanged).
- [ ] **Step 2:** Implement (consumption call inside the existing `@Transactional` publish method, after the DRAFT/org checks, before the save). Run document_vault + blockchain_publisher tests → PASS. Commit `feat(document_vault): attested publish via ceremony consumption`.

### Task 15: Dispatch hook (fail-closed) in `DocumentL1TransactionCreator`

**Files:**
- Create: `blockchain_publisher/…/service/keri/DocumentAttestationLookup.java` (`@ConditionalOnProperty("lob.keri-attestation.enabled")`; reads freeze repo + ceremony state via a small query interface exposed from keri_attestation — add `Optional<ConsumedAttestation> findConsumed(String ceremonyId)` to `AttestationConsumptionApi`)
- Modify: `blockchain_publisher/…/service/publish/module/document/DocumentL1TransactionCreator.java` (+ `private final Optional<DocumentAttestationLookup> attestationLookup;` — new constructor arg)
- Modify: `TransactionSubmissionConfig.documentL1TransactionCreator(...)` bean (inject `ObjectProvider` → `Optional`)
- Test: `blockchain_publisher/…/service/publish/module/document/DocumentL1TransactionCreatorAttestationTest.java`

**Interfaces:**
- Consumes: Task 13 freeze repo, Task 5 factory, Task 14 dispatch-row ceremony id.
- Produces: in `pullBlockchainTransaction`, when the document dispatch record carries `attestationCeremonyId`:

```java
// 1. lookup.freezeFor(documentId, ceremonyId): missing → Left(ATTESTATION_FREEZE_MISSING)  [fail closed]
// 2. lookup.findConsumed(ceremonyId): not CONSUMED → Left(ATTESTATION_FREEZE_MISSING)
// 3. MetadataMap frozen = deserialize(freeze.frozenMetadataCbor)   // CBORMetadataMap over CborSerializationUtil.deserialize
// 4. recomputed = cip170MetadataFactory.digestOf(frozen); if (!recomputed.equals(freeze.digestQb64)) → Left(ATTESTED_METADATA_MISMATCH)
// 5. freeze age > freezeMaxAge → Left(ATTESTED_METADATA_MISMATCH with age detail)
// 6. skip IPFS re-upload (reuse freeze.ipfsCid), skip fresh serialiseToMetadataMap;
//    metadata.put(1447, frozen); metadata.put(170, cip170MetadataFactory.attestMap(att.aid(), att.digestQb64(), att.kelSequence()))
// 7. creationSlot for API3BlockchainTransaction = FRESH chain tip (dispatcher aging), not freeze.metadataCreationSlot
// No ceremony id on the record → existing code path untouched (all current tests must stay green unmodified)
```

- [ ] **Step 1:** Failing tests for every branch above (mock lookup/publisher/reader), plus a regression test asserting the non-attested path never touches the lookup.
- [ ] **Step 2:** Implement. Run → PASS. Commit `feat(blockchain_publisher): attested dispatch with frozen metadata, fail closed`.

### Task 16: Cross-module context tests + M3 gate

- [ ] Add `ApplicationContextRunner`-based tests (location: `blockchain_publisher/src/test/…/config/ModuleFlagCombinationsTest.java`) asserting clean context + correct bean presence/absence for all four combinations of `lob.keri-attestation.enabled` × `lob.document_vault.enabled` (publisher on).
- [ ] test-runner subagent: full `./gradlew clean build` green.
- [ ] code-reviewer subagent + Codex review over `git diff main...HEAD -- blockchain_publisher document_vault keri_attestation`; fix findings; commit `fix: M3 review findings`.

---

## Milestone 4 — frontend (all paths relative to the `feat+document-module` worktree)

### Task 17: API connector + react-query models

**Files:**
- Create: `src/libs/api-connectors/backend-connector-lob/api/keri-attestation/keri-attestation-api.consts.ts`, `.types.ts`, `.service.ts`
- Create: `src/libs/models/keri-attestation-model/GetKeriIdentityModel/GetKeriIdentity.service.ts`, `…/ResolveOobiModel/ResolveOobi.service.ts`, `…/CreateCeremonyModel/CreateCeremony.service.ts`, `…/GetCeremonyModel/GetCeremony.service.ts`, `…/RequestCredentialModel/RequestCredential.service.ts`, `…/SubmitAuthBeginModel/SubmitAuthBegin.service.ts`, `…/RequestAttestModel/RequestAttest.service.ts`
- Modify: `src/libs/api-connectors/backend-connector-lob/api/document-vault/document-vault-api.service.ts` (`publishDocument` optional body) + `.types.ts`
- Test: `src/libs/models/keri-attestation-model/GetCeremonyModel/GetCeremony.service.test.ts` (+ mirror the test style of `GetVaultDocumentModel`)

**Interfaces:**
- Produces (consts): `KERI_ATTESTATION_API_BASE = 'api/v1/keri-attestation'`; `KERI_ERROR_TITLES` mirroring `KeriAttestationProblems` titles (Task 3 list, verbatim).
- Produces (types):

```typescript
export type CeremonyState = 'CREATED' | 'OOBI_RESOLVED' | 'CREDENTIAL_REQUESTED' | 'CREDENTIAL_RECEIVED'
  | 'AUTH_BEGIN_SUBMITTED' | 'AUTH_BEGIN_CONFIRMED' | 'ATTEST_REQUESTED' | 'ATTEST_ANCHORED'
  | 'CONSUMED' | 'FAILED' | 'EXPIRED';
export interface KeriIdentity { linked: boolean; aid?: string;
  credential?: { said: string; schemaSaid: string };
  authBegin?: { txHash: string; at: string; external: boolean } }
export interface Ceremony { id: string; state: CeremonyState;
  requiredSteps: { oobi: boolean; credential: boolean; authBegin: boolean };
  errorTitle?: string; errorDetail?: string;
  metadataDigest?: string; kelSequence?: string; kelEventSaid?: string; authBeginTxHash?: string }
export const WAITING_STATES: CeremonyState[] = ['CREDENTIAL_REQUESTED', 'AUTH_BEGIN_SUBMITTED', 'ATTEST_REQUESTED'];
```

- Produces (hooks): `useGetKeriIdentityModel()` (returns `identityUnavailable: true` on 404 instead of erroring — that's the module-disabled probe), `useGetCeremonyModel(ceremonyId)` with `refetchInterval: (q) => q.state.data && WAITING_STATES.includes(q.state.data.state) ? 2000 : false`, and mutation hooks `useResolveOobiModel`, `useCreateCeremonyModel`, `useRequestCredentialModel`, `useSubmitAuthBeginModel`, `useRequestAttestModel`.

- [ ] **Step 1:** Read `GetVaultDocumentModel/GetVaultDocument.service.ts` and `PublishVaultDocumentModel/PublishVaultDocument.service.ts` and copy their structure exactly (queryKey arrays, `httpService` usage, invalidation).
- [ ] **Step 2:** Failing test for `useGetCeremonyModel` polling logic (interval on waiting state, off on terminal) and the 404→`identityUnavailable` mapping; run the repo's test command (check `package.json` scripts — the document-vault suite currently runs 234 tests) → FAIL.
- [ ] **Step 3:** Implement all files; `publishDocument(documentId, body?: { attestationCeremonyId: string })` posts the body only when present.
- [ ] **Step 4:** Tests PASS; commit `feat(document-vault-ui): keri attestation api layer`.

### Task 18: Wizard components

**Files:**
- Create: `src/modules/document-vault/features/attest-publish/attest-publish-modal.component.tsx`, `attest-publish.hooks.ts`, `oobi-scanner.component.tsx`, `pair-step.component.tsx`, `credential-step.component.tsx`, `auth-begin-step.component.tsx`, `attest-step.component.tsx`
- Modify: `src/modules/document-vault/features/publish-action/publish-action.component.tsx` + `publish-action.hooks.ts` (open `AttestPublishModal`; keep the no-attestation path byte-identical; when `identityUnavailable` render today's `ConfirmationModal` exactly as now)
- Modify: `package.json` (+ `qrcode.react`, `+ qr-scanner`)
- Modify: `src/libs/translations/en-US.json` (all new copy keys, prefix `documentVaultAttest*` — including the "AUTH_BEGIN is required only once per identity" explanation and the "starting attestation uploads your encrypted envelope" note, spec §5.2/§6.2)
- Create: `src/modules/document-vault/hooks/useKeriErrorMessage.ts` (mirror `useVaultErrorMessage` over `KERI_ERROR_TITLES`)
- Test: `attest-publish/attest-publish-modal.component.test.tsx`, `oobi-scanner.component.test.tsx`

**Interfaces:**
- Consumes: Task 17 hooks; existing `Modal`, `StepperManager`, `Alert`, `Button` from `src/features/common`/`ui-kit`.
- Produces: `useAttestPublishFlow(documentId, organisationId)` hook driving: identity load → ceremony create → step index derived from `requiredSteps` + `state` → per-step trigger functions → on `ATTEST_ANCHORED` calls `triggerPublishVaultDocument({ parameters: { documentId }, body: { attestationCeremonyId } })` → `onPublished`.

- [ ] **Step 1:** Read `enrollment.component.tsx`, `document-create.component.tsx` (StepperManager use) and `card-import.component.tsx` before writing anything; match their state-enum + Alert conventions.
- [ ] **Step 2:** Failing component tests: modal offers both publish paths; wizard renders only required steps (mock identity: fully linked → single Attest step); pair step shows agent QR (assert `QRCodeSVG` with the oobi) and paste input; `oobi-scanner` renders camera button, and when `QrScanner.hasCamera()` resolves false or permission is denied it falls back to paste-only (mock `qr-scanner`); attest step shows waiting copy while polling and the mapped error + retry button on `FAILED` (`KERI_WALLET_TIMEOUT` → its i18n string).
- [ ] **Step 3:** Implement components. `oobi-scanner.component.tsx` wraps `qr-scanner`: instantiate on user click (`new QrScanner(videoEl, r => onScan(r.data), { returnDetailedScanResult: true })`), `.start()`/`.stop()` + `.destroy()` on unmount, permission-denied → callback to show paste field. Scanned text goes through the same `useResolveOobiModel` as pasted text.
- [ ] **Step 4:** Tests PASS; run the full frontend suite → all green (234 existing + new). Commit `feat(document-vault-ui): veridian attestation wizard`.

### Task 19: M4 gate + end-to-end verification

- [ ] test-runner subagent: frontend full test + build (`npm run build` or the repo's script) AND backend `./gradlew clean build` — both green.
- [ ] Manual verification list for the human operator (document in the session summary): backend up with `lob.keri-attestation.enabled=true` + KERIA config; walk the wizard with a real Veridian wallet on preprod; confirm on-chain tx carries labels 1447 + 170 with `d` = digest of 1447 bytes (verify with `docs/keri/AttestTransaction.java` logic or Blockfrost).
- [ ] code-reviewer subagent + Codex review over the frontend worktree diff; fix findings.
- [ ] Commit `fix(document-vault-ui): M4 review findings`.

---

## Self-review notes (already applied)

- Spec §3.4 flag matrix → Tasks 1 (module flag test), 14 (`ObjectProvider` + `ATTESTATION_UNAVAILABLE`), 15 (`Optional` lookup), 16 (combination context tests), 17 (404 probe → fallback).
- Spec §4.4 direct-digest rule → Task 5 golden vectors, Task 8 spike gate, Task 9 seal assertion, Task 15 recompute assertion, Task 19 on-chain check.
- Spec §4.5 schema-SAID + external verification → Task 5 (authBeginMap arg is `leafSchemaSaid`), Task 9 (`AUTH_BEGIN_UNVERIFIED` path).
- Spec §4.2 generations/cooldown/correlation → Tasks 3, 6, 9.
- Type-consistency check: `AttestationConsumptionApi.validateAndConsume` (Tasks 3/14), `ConsumedAttestation` (Tasks 3/15), `AttestationDigest(digestQb64, metadataLabel)` (Tasks 9/13), `Cip170MetadataFactory.digestOf/attestMap/authBeginMap` (Tasks 5/13/15) — names match across tasks.
