# Multi-tenant NetSuite configuration — design

**Ticket:** LOB-2166 — migrate NetSuite configuration from environment variables to database-backed storage
**Date:** 2026-08-13
**Status:** Approved for planning

## 1. Problem

The platform can serve exactly one NetSuite tenant. `NetSuiteClient` is a single application-wide bean built from
`LOB_NETSUITE_CLIENT_*` environment variables, and its RSA signing key is a PEM file mounted into the container at
`LOB_NETSUITE_CLIENT_PRIVATE_KEY_FILE_PATH`. Under multitenancy each organisation needs its own NetSuite instance,
credentials and signing key, and an ingestion must use the configuration belonging to the organisation it was
triggered for.

## 2. Goals

- An organisation admin can create and update that organisation's NetSuite configuration through the organisation module.
- The organisation module forwards the configuration to the netsuite module over a Kafka-bridged domain event.
- The netsuite module owns and stores the configuration per organisation.
- An ingestion resolves the configuration for its organisation and fails with a clear, specific error when none exists.
- Secret material is never stored or transported in plaintext.

## 3. Non-goals

- Multiple NetSuite configurations per organisation. Exactly one configuration per organisation.
- Deleting a configuration. There is no delete endpoint and no delete event.
- Fleet-wide administration UI. Each organisation's admin configures their own organisation.
- Encryption key rotation tooling. The envelope format is versioned so rotation can be added later, but no rotation
  job ships with this work.
- Migrating the operational tuning parameters (`records-per-call`, `send-batch-size`, debug mode,
  financial-period source) to the database. These remain global operator configuration.

## 4. Current state

Established facts from the three repositories, which the design relies on.

**`cf-reeve-platform`**

- The netsuite module is the gradle module `:netsuite_altavia_erp_adapter`, package root
  `org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter`, gated by
  `org.cardanofoundation.lob.app.config.NetsuiteModuleConfig` on `lob.netsuite.enabled`.
- `netsuite_altavia_erp_adapter/build.gradle.kts` already declares `implementation(project(":organisation"))`.
  The dependency edge netsuite → organisation exists; the reverse does not.
- `client/NetSuiteClient.java` is the sole consumer of credentials. It holds `baseUrl`, `tokenUrl`,
  `privateKeyFilePath`, `certificateId`, `clientId`, `recordsPerCall` as plain constructor parameters, refreshes an
  OAuth2 token in `@PostConstruct init()`, and signs a PS256 JWT with a PKCS8 PEM loaded from the local filesystem in
  `loadPrivateKeyFromFile`.
- Ingestion already carries `organisationId` end to end:
  `ExtractionController` (`POST /api/v1/extraction/`) → `AccountingCoreService.scheduleIngestion` →
  `ScheduledIngestionEvent` → `NetSuiteEventHandler.handleScheduledIngestionEvent` →
  `NetSuiteExtractionService.startNewERPExtraction(organisationId, user, params)`.
- All modules share one `lob_service` schema, one `DataSource` and one `EntityManagerFactory`. Modules are separated
  by table-name prefix (`organisation_*`, `netsuite_adapter_*`, `accounting_core_*`). Flyway merges every module's
  `classpath:db/migration/{vendor}/common` folder.
- Module boundaries are **convention only**. There is no ArchUnit suite, no Spring Modulith enforcement and no
  `@ApplicationModule` declaration; `jmolecules` supplies `@DomainEvent` annotations but no verification.
- The netsuite module exposes **no** `@RestController`, matching `blockchain_publisher` and `blockchain_reader`.
  HTTP surface lives in `organisation`, `accounting_reporting_core`, `funding` and `reporting`.
- Cross-module command/ACK precedent: `blockchain_publisher` publishes `LedgerUpdatedEvent` (in the neutral
  `blockchain_common`) with a `LedgerUpdateType` discriminator, consumed and filtered by three modules.
- Synchronous request/reply precedent: `AccountingCoreService.validateAndPersistIngestionFile` publishes
  `ValidateIngestionEvent` with a correlation id and blocks on a `CompletableFuture` held by
  `ValidateIngestionResponseWaiter` (a `ConcurrentHashMap`), with a 10 second timeout.
- Disabled-module precedent: `BlockchainReaderPublicApiIF` has a real bean and a `Noop` bean, mutually gated by
  `@ConditionalOnProperty` on the same `lob.<module>.enabled` flag, so injection sites never need `Optional`.
- Authorisation is method-level `@PreAuthorize` against role names resolved from the `securityConfig` bean
  (`support/spring_web/SecurityConfig.java`); `KeycloakSecurityHelper.canUserAccessOrg(orgId)` provides org scoping.
- `support/crypto/` contains only `MD5Hashing` and `SHA3` — one-way digests. There is **no** AES, no `Cipher`, no
  envelope encryption, no encrypting `AttributeConverter` and no key management anywhere in the repository.
  BouncyCastle `bcprov-jdk18on` is on the root classpath; `bcpkix` is not.

**`cf-reeve-application`**

- The Kafka bridge is uniform: a module raises a Spring event → `kafka/publisher/<Module>KafkaPublisher` catches it
  with `@EventListener` and calls `kafkaTemplate.send(topic, event)` → `kafka/consumer/<Module>KafkaConsumer` receives
  it with `@KafkaListener` and re-publishes it locally via `ApplicationEventPublisher`.
- Topic names are the event class's fully-qualified name minus the `org.cardanofoundation.lob.app.` prefix, declared
  as properties under `lob.<module>.topics.*`, with a per-module `lob.<module>.consumer-group`.
- Serialization is Spring Kafka's JSON serializer/deserializer with `spring.json.trusted.packages: '*'`. Topics are
  auto-created. There are **no** `NewTopic` beans, no schema registry, and **no DLQ, retry or error handler anywhere**.
- Both bridge sides are gated with `@ConditionalOnProperty({"lob.<module>.enabled", "spring.kafka.enabled"})`.
- `api` and `publisher` are two deployments of the same image with disjoint `LOB_*_ENABLED` flags. NetSuite runs on
  `publisher`.
- Postgres runs as a single superuser against a single database; only Keycloak gets its own database via
  `init-scripts/create_databases.sql`. There is no Vault or K8s-secret integration; all secrets are environment
  variables.

**`cf-lob-frontend`**

- React 19 + Vite, react-router v7, TanStack Query v5, Formik + Yup, MUI v7.
- Permissions are runtime **data**: `window.__PERMISSIONS__` from `public/permissions.global.js`, checked by
  `hasPermission(resource, action)` and enforced on routes by `<RequirePermission>`.
- `UserRole.ADMIN = 'reeve_admin'`. No resource is currently admin-exclusive; `organization_details` (admin +
  account manager) is the strictest precedent.
- There is no organisation picker. `useSelectedOrganisation()` returns `token.organisations[0]`.
- `modules/organizationDetails` is the structural analogue: one record per organisation, GET on load, PUT to save,
  no delete, permission-gated view/edit toggle.
- No write-only or masked secret field exists outside the login flow, where `InputPassword` / `FieldPassword` provide
  the show/hide pattern.

## 5. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Secrets encrypted with application-level AES-256-GCM before leaving the organisation module | DB dumps, backups and Kafka topics never contain plaintext key material |
| D2 | Same `DataSource`, same schema; isolation enforced at the infrastructure layer | Explicitly chosen; a second `EntityManagerFactory` has no precedent in the modulith and buys little given infra-level controls |
| D3 | The organisation module never reads netsuite tables | In a decentralized deployment the netsuite tables hold no data on the organisation tier; the boundary must hold by construction |
| D4 | Status and non-secret fields are readable; the private key is strictly write-only | Without it the admin form cannot show whether an organisation is configured or prefill anything |
| D5 | The netsuite module is the sole owner of the configuration; the organisation module keeps only a projection | One authoritative copy of the secret |
| D6 | The event is published directly after the projection commits; no outbox | The projection row is already the durable record of the write, so a lost event is visible as a stuck `PENDING` rather than a silent loss. An outbox would add a table, a relay job and retry/purge logic, and would be the only place key material lived on the organisation side |
| D7 | Credentials are validated **asynchronously**; the verdict is persisted on the projection as `netsuite_valid` | Any organisation pod may consume the reply, so an in-memory `CompletableFuture` cannot be relied on. Whichever pod receives the ACK writes the flag; the UI reads it on refresh |
| D8 | Hard cutover — no environment-variable fallback | Chosen explicitly; avoids one tenant's credentials silently serving another organisation |
| D9 | Credentials are per organisation; tuning parameters and the adapter instance id stay global | Batch sizes and debug flags are operator concerns, not tenant data. `netsuiteInstanceId` is adapter identity and a code-mapping key — see §17 |
| D10 | The UI is scoped to the caller's own organisation | Chosen explicitly; no organisation picker needs to be built |
| D11 | Encryption happens in the organisation service layer, not in a JPA `AttributeConverter` | The ciphertext must travel in the event payload, which sits above persistence; a converter fires only on write to a column the organisation module does not have, so it would leave plaintext in the event |
| D12 | Event classes live in the `organisation` module | netsuite already depends on organisation, so no new shared module and no circular edge |
| D13 | The netsuite module gains no REST controller | Consistent with `blockchain_publisher` / `blockchain_reader` |

### Accepted trade-offs

These follow from D6 + D7 and are accepted knowingly.

1. **Invalid credentials are stored, not rejected.** The write path cannot tell the admin whether the credentials
   work, because the answer arrives later and possibly on a different pod. A configuration that fails verification is
   persisted with `netsuite_valid = false`; ingestion for that organisation will fail until it is corrected.
2. **The admin gets no immediate verdict.** `POST`/`PUT` returns `202 Accepted`. The outcome appears on the status
   endpoint once the ACK is processed — typically sub-second, but the UI must be written for "not yet known".
3. **A lost upsert event is recovered manually, not automatically.** If the process dies between the projection
   commit and the Kafka publish, the row stays `PENDING` and nothing reaches the netsuite module. Because the
   organisation module holds no copy of the key (D5), it cannot retransmit; the admin re-submits the form. The failure
   is visible rather than silent, which is what makes this acceptable.
4. **Hard cutover breaks ingestion until every organisation is reconfigured.** With D8 there is no fallback, so the
   upgrade window requires each organisation's admin to re-enter credentials.

## 6. Architecture

### 6.1 Write path

Every step is asynchronous and every piece of state is persisted, so no request depends on which pod handles it.

```
Admin (frontend)
  │  POST/PUT /api/v1/organisations/{orgId}/netsuite-configuration
  ▼
organisation module  (any pod)
  │  1. validate payload shape
  │  2. encrypt private key (AES-256-GCM)
  │  3. COMMIT projection row (syncState = PENDING, netsuiteValid = null)
  │  4. publish NetSuiteConfigUpsertedEvent  — after commit, never before
  │  5. return 202 Accepted with the current status view
  ▼
[Kafka]
  ▼
netsuite module
  │  1. upsert netsuite_adapter_organisation_config (idempotent on revision)
  │  2. build a client from the stored config and call testConnection()
  │  3. publish NetSuiteConfigAppliedEvent(organisationId, revision,
  │                                        storeStatus, validationStatus, message)
  ▼
[Kafka]
  ▼
organisation module  (whichever pod is assigned the partition)
  │  projection: syncState = APPLIED | FAILED
  │              netsuiteValid = true | false
  │              lastValidatedAt, validationMessage
  ▼
Frontend polls / refetches GET .../status → shows the verdict
```

The configuration is stored **before** it is verified. Storing is the durable act; verification is a report on it.
That ordering is what lets any pod handle any step.

**Publish strictly after commit.** The `cf-reeve-application` bridge listens with a plain `@EventListener`, which fires
synchronously at `publishEvent()` — that is, *before* the surrounding transaction commits. Publishing from inside the
transactional method would therefore let the netsuite module store a configuration whose projection row was
subsequently rolled back, leaving the admin looking at "not configured" for an organisation that is in fact
configured. The service must commit the projection first and publish after the transactional method returns.

### 6.2 Ingestion path

```
POST /api/v1/extraction/  (unchanged)
  → AccountingCoreService.scheduleIngestion
  → ScheduledIngestionEvent(organisationId, ...)
  → NetSuiteEventHandler.handleScheduledIngestionEvent
  → NetSuiteExtractionService.startNewERPExtraction(organisationId, ...)
      → NetSuiteClientRegistry.forOrganisation(organisationId)
          ├─ config found  → decrypt, build or reuse cached client, extract
          └─ config absent → TransactionBatchFailedEvent(NETSUITE_CONFIGURATION_NOT_FOUND)
```

The same resolution applies to `NetSuiteReconcilationService` and to `NetSuiteExtractionService.validateIngestion`.
The latter reports the missing configuration through the pre-existing `ValidateIngestionResponseEvent` channel it
already uses — that channel is untouched by this work, and is unrelated to the configuration ACK described in §10.

## 7. Cryptography

New package members in `support/src/main/java/org/cardanofoundation/lob/app/support/crypto/`, beside the existing
`MD5Hashing` and `SHA3`.

- **Algorithm:** AES-256-GCM, 96-bit random IV per encryption, 128-bit authentication tag. JDK `javax.crypto`; no new
  dependency required.
- **Envelope format:** `v1:` + Base64(`iv ‖ ciphertext ‖ tag`). The version prefix exists so a future key rotation can
  introduce `v2:` and decrypt both.
- **Key:** 32 raw bytes, Base64-encoded, supplied as `LOB_CONFIG_ENCRYPTION_KEY` and bound to
  `lob.security.config-encryption.key`. The bean fails fast at startup when the property is missing or does not decode
  to 32 bytes, so a misconfigured deployment cannot start and silently write unreadable data.
- **Distribution:** both the organisation tier and the netsuite tier need the key — the former to encrypt, the latter
  to decrypt. In a split deployment the same value is set on both services.
- **Fingerprint:** the organisation projection stores `SHA3(privateKeyPem)` truncated for display, so an admin can
  tell which key is installed without the key being readable.

Secret fields: `privateKey` only. `clientId` and `certificateId` are identifiers, not secrets, and stay plaintext so
the status endpoint can return them.

## 8. Data model

### 8.1 `organisation` module — projection

Table `organisation_netsuite_config_state`, migration in
`organisation/src/main/resources/db/migration/postgresql/common/`.

| Column | Notes |
|---|---|
| `organisation_id` | PK |
| `base_url`, `token_url`, `client_id`, `certificate_id` | non-secret, returned by the status endpoint |
| `private_key_fingerprint` | SHA3 digest, display only |
| `sync_state` | `PENDING` \| `APPLIED` \| `FAILED` — did the netsuite module receive and store it |
| `sync_message` | last ACK failure detail, nullable |
| `revision` | monotonic, incremented per accepted write |
| `netsuite_valid` | `NULL` \| `TRUE` \| `FALSE` — do the credentials actually authenticate against NetSuite |
| `last_validated_at`, `validation_message` | when the verdict was recorded, and why it failed |
| `created_at`, `updated_at`, `updated_by` | audit, matching the module's `CommonEntity` convention |

**No secret column.** This table never holds key material.

`sync_state` and `netsuite_valid` answer two different questions and must not be collapsed into one column. A
configuration can be stored successfully (`APPLIED`) and still not work (`netsuite_valid = false`) — that is the
common case for a typo in a client id. `netsuite_valid` is `NULL` from the moment of the write until the ACK is
processed, which is exactly the "not yet known" state the UI has to render.

Both fields are written by whichever organisation pod consumes `NetSuiteConfigAppliedEvent`. Nothing about the write
path depends on the pod that served the original HTTP request still being alive, or on it being the same pod.

This is the **only** table the organisation module adds. There is no outbox (D6): the encrypted key is held just long
enough to build the event and is never written to the organisation schema, so key material exists in exactly one
place — the netsuite module's table below.

### 8.2 `netsuite_altavia_erp_adapter` module — authoritative store

Table `netsuite_adapter_organisation_config`, migration in the netsuite module's `common` folder, following the
existing `netsuite_adapter_*` prefix convention.

| Column | Notes |
|---|---|
| `organisation_id` | PK |
| `base_url`, `token_url`, `client_id`, `certificate_id` | plaintext |
| `private_key_encrypted` | `v1:`-prefixed envelope |
| `revision` | from the event; used for idempotent, out-of-order-safe upsert |
| `created_at`, `updated_at` | audit |

Upsert is idempotent: an event whose `revision` is not greater than the stored one is acknowledged and ignored. This
makes at-least-once delivery safe.

## 9. API surface

All under the existing `/api/v1` base, in a new
`organisation/src/main/java/org/cardanofoundation/lob/app/organisation/resource/NetSuiteConfigurationController.java`.

| Method | Path | Success | Purpose |
|---|---|---|---|
| `POST` | `/organisations/{orgId}/netsuite-configuration` | `202` | create; `409` if a row already exists |
| `PUT` | `/organisations/{orgId}/netsuite-configuration` | `202` | update; `404` if no row exists |
| `GET` | `/organisations/{orgId}/netsuite-configuration/status` | `200` | non-secret fields + sync/validation state |

`POST` and `PUT` return `202 Accepted` rather than `201`/`200`, because acceptance is all they can honestly report:
the configuration has been recorded and queued, but whether NetSuite accepts the credentials is not known yet. The
response body is the same `NetSuiteConfigurationStatusView` the `GET` returns, so the client can render the
`PENDING` / `netsuiteValid: null` state immediately without a second call.

Authorisation on every endpoint: `@PreAuthorize("hasRole(@securityConfig.getAdminRole())")` plus an explicit
`KeycloakSecurityHelper.canUserAccessOrg(orgId)` check. This is the first admin-exclusive endpoint in the platform;
every existing `@PreAuthorize` combines the admin role with at least the manager role.

`GET .../status` always returns `200` for an organisation the caller may access. When no configuration exists it
returns `{"configured": false}` with no other fields. It does **not** return `404` — "not configured yet" is the
expected steady state for a fresh organisation, and the UI must distinguish it from an error.

`POST` returns `409` whenever a projection row exists, regardless of its `syncState`. A row stuck at `PENDING` or
`FAILED` is corrected with `PUT`, not by creating a second one.

Request DTOs follow the module convention (`NetSuiteConfigurationCreate`, `NetSuiteConfigurationUpdate`, Lombok
accessors, `@Schema` examples, jakarta validation). `privateKey` is **mandatory on `POST`** and **optional on `PUT`** —
see §10.2 for how an omitted key is resolved, since the organisation module holds no copy of it.

The response DTO is `NetSuiteConfigurationStatusView` and **never** carries the private key.

Errors use RFC 7807 `ProblemDetail`, consistent with the rest of the platform, with titles:

| Title | Status | Condition |
|---|---|---|
| `NETSUITE_CONFIGURATION_ALREADY_EXISTS` | 409 | `POST` against an organisation that already has a row |
| `NETSUITE_CONFIGURATION_NOT_FOUND` | 404 | `PUT` against an organisation with no row |

There is no `422` for bad credentials and no `503` for an unreachable netsuite module. Neither condition is knowable
at request time under D7; both surface later as `netsuiteValid = false` or a projection left at `PENDING`.

`NETSUITE_CONFIGURATION_NOT_FOUND` is reused as the failure title on the ingestion path (§6.2), where it surfaces on
a `TransactionBatchFailedEvent` rather than an HTTP response.

## 10. Events

Two classes in `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/event/netsuite/`,
each carrying `EventMetadata` with a `VERSION` constant, per the platform convention.

| Event | Direction | Payload |
|---|---|---|
| `NetSuiteConfigUpsertedEvent` | organisation → netsuite | `organisationId`, `revision`, non-secret fields, encrypted private key (nullable, see §10.2) |
| `NetSuiteConfigAppliedEvent` | netsuite → organisation | `organisationId`, `revision`, `storeStatus`, `validationStatus`, `message` |

A single round trip carries both outcomes. `storeStatus` reports whether the configuration was persisted and drives
`sync_state`; `validationStatus` reports whether `testConnection()` succeeded and drives `netsuite_valid`. Folding
them together means the credentials cross Kafka exactly once, and there is no separate validation exchange to keep
consistent with the upsert.

There is **no** correlation-id waiter and no `CompletableFuture` anywhere in this design. That is the point of D7:
the reply may be delivered to any organisation pod, so the only durable place to put the answer is the database.

`toString()` must exclude the encrypted key so it cannot reach logs — the publishers in `cf-reeve-application` log the
whole event at INFO today.

### 10.1 Kafka wiring (`cf-reeve-application`)

Topics, added to `cf-application/src/main/resources/application.yml`:

```yaml
lob:
  organisation:
    consumer-group: lob-consumer-organisation
    topics:
      netsuite-config-applied: organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent
  netsuite:
    topics:
      netsuite-config-upserted: organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent
```

New `kafka/publisher/OrganisationKafkaPublisher.java` and `kafka/consumer/OrganisationKafkaConsumer.java`; the
existing `NetsuiteKafkaPublisher` and `NetSuiteKafkaConsumer` gain one handler each. All gated with the established
`@ConditionalOnProperty({"lob.<module>.enabled", "spring.kafka.enabled"})`.

**Consumer group scoping.** Both listeners use their module's ordinary shared group — `lob-consumer-organisation` for
the ACK, `lob-consumer-netsuite` for the upsert. Exactly one pod in each group receives a given message, does the
work, and writes the result to the database. This is the normal, correct use of a consumer group, and it is only
available because D7 removed the in-memory future: there is no longer any pod-affinity requirement to work around.

Per the platform's group-scoping rule, these group ids must not be shared with listeners that have different topic
subscriptions. `lob-consumer-organisation` is new and belongs solely to the organisation module's listeners.

### 10.2 Updating without re-entering the private key

The organisation module keeps no copy of the key (D5, §8.1), so it cannot re-send one on a `PUT` where the admin left
the field blank. The netsuite module owns the key, so it supplies it:

- In `NetSuiteConfigUpsertedEvent`, a **null** `privateKeyEncrypted` means *"reuse the key already stored for this
  organisation"*.
- The netsuite module writes the new non-secret fields and leaves `private_key_encrypted` untouched, then verifies
  using the **new** connection fields together with the **existing** key. This is what makes "change the account URL
  without retyping the key" work.
- If the field is null and the netsuite module has no stored configuration for that organisation — the projection and
  the authoritative store having diverged — it ACKs with `storeStatus = FAILED` and a message of
  `NETSUITE_CONFIGURATION_NOT_FOUND`. The projection lands on `syncState = FAILED`, and the admin recovers by
  submitting the key explicitly.

`privateKeyEncrypted` is mandatory on the create path; the controller rejects a `POST` without it before anything is
written.

## 11. NetSuite client resolution

- `NetSuiteClient` loses `@PostConstruct init()` and its `privateKeyFilePath` parameter. It takes a resolved
  `PrivateKey` (or the decrypted PEM) plus the per-organisation connection fields. Token refresh becomes lazy, on
  first use.
- A new `NetSuiteClientRegistry` resolves and caches one client per organisation, each with its own token cache. It
  evicts an organisation's client when `NetSuiteConfigUpsertedEvent` is applied, so a credential change takes effect
  without a restart.
- `NetSuiteConfigService` handles the upsert, the verification call and the lookup; `NetSuiteConfigEventHandler`
  listens for `NetSuiteConfigUpsertedEvent` and publishes `NetSuiteConfigAppliedEvent` carrying both outcomes.
- `NetSuiteExtractionService` and `NetSuiteReconcilationService` resolve through the registry by `organisationId`
  instead of holding an injected client.
- `cf_netsuite_altavia_erp_connector/config/CFConfig.java` in the application repo stops constructing a
  `NetSuiteClient` from `@Value`-injected environment properties and constructs the registry instead. The `RestClient`
  bean remains shared.

The global tuning parameters (`recordsPerCall`, `sendBatchSize`, debug mode, financial-period source) continue to be
injected from configuration, unchanged.

## 12. Frontend

- **Permissions:** new `netsuite_configuration` resource with `view` / `create` / `edit` in
  `public/permissions.global.js`, granted to `reeve_admin` only. This is the first admin-exclusive resource in the
  table; the other three roles get `false`.
- **API layer:** `src/libs/api-connectors/backend-connector-lob/api/netsuite-config/netsuite-config-api.service.ts`
  and `.types.ts`, using `httpService` from `services`, registered in `backendLobApi.ts`.
- **Models:** `GetNetsuiteConfigStatusModel.service.ts`, `CreateNetsuiteConfigModel.service.ts`,
  `UpdateNetsuiteConfigModel.service.ts` under `src/libs/models/netsuite-config/`.
- **View:** `src/modules/settings/views/netsuite-configuration/`, copying the `modules/organizationDetails` shape —
  view/edit toggle, Formik + Yup, `disabled={!isEditMode}` per field.
- **Organisation scope:** `useSelectedOrganisation()`. No picker.
- **Private key field:** write-only. When a configuration exists the form shows the fingerprint and a "Replace key"
  affordance rather than a value; the field is submitted only when the admin types a new key. Reuses `FieldPassword` /
  `InputPassword`, or a masked multiline variant if a PEM does not fit the single-line control.
- **Wiring:** route in `settings.routes.tsx` wrapped in `<RequirePermission resource="netsuite_configuration"
  action="view">`, path constant in `consts/routes/routes.consts.ts`, nav entry in
  `NavigationSidebar.service.tsx`, strings in `libs/translations/en-US.json`.

### 12.1 Rendering an asynchronous verdict

The save returns `202` with no verdict, so the UI cannot render a simple success/failure. `syncState` and
`netsuiteValid` combine into five states the admin can actually be in:

| `syncState` | `netsuiteValid` | Chip | Meaning shown to the admin |
|---|---|---|---|
| no row | n/a | *Not configured* | Nothing has been set up |
| `PENDING` | `null` | *Checking…* | Saved, verification in progress |
| `APPLIED` | `true` | *Connected* | Stored and verified, with `lastValidatedAt` |
| `APPLIED` | `false` | *Credentials rejected* | Stored but NetSuite refused them; shows `validationMessage` |
| `FAILED` | any | *Not applied* | The netsuite module could not store it; shows `syncMessage` |

After a successful submit the form switches out of edit mode and shows *Checking…*. The verdict appears on the next
fetch — a manual page refresh is sufficient and is the guaranteed path. As a convenience, the react-query hook sets
`refetchInterval` while `syncState === 'PENDING'`, stopping once it resolves, so the chip usually settles on its own
within a second or two without the admin doing anything. That polling is an enhancement, not the mechanism; the
design is correct with it removed.

Two error cases remain synchronous and are surfaced inline as before: `409` on a duplicate create and `404` on an
update with no existing row. Credential rejection is **not** a form error — it is a status on a saved record.

## 13. Application repository

- **`cf-application/src/main/resources/application.yml`** — add the two topics and `lob.organisation.consumer-group`;
  add `lob.security.config-encryption.key: ${LOB_CONFIG_ENCRYPTION_KEY}`; **remove** the `lob.netsuite.client.*`
  credential block, keeping only the tuning keys.
- **`docker-compose.yml`** — remove `LOB_NETSUITE_CLIENT_URL`, `_CERTIFICATE_ID`, `_CLIENT_ID`,
  `_PRIVATE_KEY_FILE_PATH`, `_TOKEN_URL` from the `publisher` service and delete the PEM volume mount; keep
  `LOB_NETSUITE_CLIENT_RECORDSPERCALL`; add `LOB_CONFIG_ENCRYPTION_KEY` to **both** `api` and `publisher`.
- **`docker-compose.lightweight.yml`** — the same edits on the merged `backend` service. Note this profile runs with
  `SPRING_KAFKA_ENABLED: false`, so the whole flow degrades to in-process Spring events; both modules are in one JVM
  and the design works unchanged.
- **`docker-compose-kafka-ssl.yml`** — the same edits on its `publisher` overlay.
- **`certs/netsuiteConfiguration.md`** — rewritten: how to obtain the NetSuite integration record, how to generate the
  encryption key, and that credentials are now entered through the UI rather than mounted.
- No `init-scripts/` change. D2 keeps the existing datasource and schema; access restriction is an infrastructure
  concern outside these repositories.

## 14. Security considerations

- The key material is protected in transit and at rest by D1. The Kafka topic and the netsuite table hold ciphertext
  only, and with D6 the organisation schema holds no key material at all — encrypted or otherwise.
- `LOB_CONFIG_ENCRYPTION_KEY` is a plain environment variable, consistent with every other secret in the deployment.
  It is a single point of compromise for all tenants' NetSuite keys; moving it to a real secret manager is the natural
  follow-up and is listed in §17.
- Event `toString()` must exclude ciphertext because the application repository's publishers log entire events at INFO.
- The status endpoint is deliberately narrow: identifiers and state, never key material.
- Admin-exclusive authorisation is new in this codebase. The permission table entry and the `@PreAuthorize` must be
  reviewed together — the frontend gate is cosmetic, the backend gate is the control.
- At-least-once delivery plus the `revision` guard means a replayed event cannot downgrade a newer configuration.

## 15. Testing

- **Crypto:** round-trip, tamper detection (GCM tag failure), IV uniqueness across encryptions, startup failure on a
  missing or malformed key, `v1:` prefix handling.
- **Organisation service:** create/update happy paths return `202` with `syncState = PENDING` and
  `netsuiteValid = null`; `409` on `POST` with an existing row in each `syncState`; `404` on `PUT` with no row;
  `GET` returns `{"configured": false}` rather than `404` for a fresh organisation; `POST` without `privateKey` is
  rejected before anything is written.
- **Publish ordering (D6):** the event is published only after the projection transaction commits. A rolled-back
  transaction must publish nothing — worth an explicit test, since the default `@EventListener` bridge would
  otherwise fire before commit.
- **ACK handling (the pod-independence requirement):** a `NetSuiteConfigAppliedEvent` processed by an instance that
  never served the original request still updates `netsuite_valid`, `sync_state` and `lastValidatedAt`. This is the
  behaviour D7 exists to guarantee, so it deserves a test that explicitly exercises the handler in isolation from any
  request context.
- **ACK for a stale revision** is ignored rather than overwriting a newer verdict.
- **Netsuite module:** upsert idempotency by `revision`, out-of-order event handling, registry caching and eviction on
  update, `NETSUITE_CONFIGURATION_NOT_FOUND` when an ingestion runs for an unconfigured organisation; a failed
  `testConnection()` still stores the configuration and reports `validationStatus = INVALID`.
- **Null-key update path (§10.2):** `PUT` without `privateKey` verifies against the stored key and the new non-secret
  fields; the upsert leaves `private_key_encrypted` byte-identical; a null key with no stored configuration ACKs
  `storeStatus = FAILED` rather than writing a configuration with no key.
- **Boundary:** a test asserting no `organisation` package imports a `netsuite_altavia_erp_adapter` repository or
  entity. Since there is no ArchUnit in the repository today, this is the one place worth adding a focused check —
  D3 is otherwise unenforced.
- **Frontend:** the private key is never rendered from a status response; the field submits only when dirty; the
  route is inaccessible to non-admin roles; each of the five states in §12.1 renders its own chip, and *Checking…*
  resolves to the right terminal state on refetch.

## 16. Rollout

Hard cutover, per D8. Sequence:

1. Deploy the platform and application changes with `LOB_CONFIG_ENCRYPTION_KEY` set on both services. Ingestion for
   every organisation fails with `NETSUITE_CONFIGURATION_NOT_FOUND` from this point.
2. Each organisation's admin enters credentials through the new UI.
3. Confirm every organisation reaches `syncState = APPLIED` **and** `netsuiteValid = true`. `APPLIED` alone is not
   sufficient — it means stored, not working.
4. Remove the now-unused `LOB_NETSUITE_CLIENT_*` credential variables and the PEM mount from deployment manifests.

The window between steps 1 and 3 is a genuine outage for NetSuite ingestion. If that is unacceptable operationally,
the decision to revisit is D8, not the design.

## 17. Open items and follow-ups

- **`netsuiteInstanceId` stays global.** An earlier draft listed it as a per-organisation field. It is
  `CFConfig.NETSUITE_CONNECTOR_ID`, and it is the first component of `netsuite_adapter_code_mapping`'s primary key —
  `TransactionConverter.getOrganisationIdFromTxLine` looks up `(netsuiteInstanceId, subsidiary, ORGANISATION)` to
  decide which organisation a transaction line belongs to. Making it per-organisation would orphan every existing
  mapping row. It is adapter identity, not a credential.
- **Per-organisation credentials do not make attribution per-organisation.** A single NetSuite account can already
  serve many organisations: `TransactionConverter` resolves the owning organisation *per transaction line* from the
  subsidiary mapping, independently of the organisation whose ingestion was triggered. This change controls *which
  NetSuite account is called*, not *how lines are attributed*, so an ingestion triggered for org X can still emit
  lines attributed to org Y if the mapping says so. Pre-existing behaviour; aligning the two is separate work and
  should be scoped before the first tenant with its own NetSuite account goes live.
- **A re-verify action.** Credentials can be revoked or expire at NetSuite without anything changing on our side, so
  `netsuite_valid` goes stale. Today it is only recomputed when a configuration is written. A "check connection now"
  endpoint republishing the upsert event — or a periodic revalidation job — would keep it honest. Deliberately left
  out of this scope.
- `ValidateIngestionResponseWaiter` still uses an in-memory correlation-id future and therefore breaks with more than
  one instance of the pod hosting `accounting_reporting_core` — the same defect D7 avoids here. This design no longer
  depends on it, but the existing extraction-validation path does. Worth a separate ticket.
- Encryption key rotation. The `v1:` envelope makes it possible; no tooling ships with this work.
- Moving `LOB_CONFIG_ENCRYPTION_KEY` into a secret manager.
- The Kafka layer has no DLQ or retry anywhere in the platform, and with D6 there is no outbox either, so a lost
  event in **either** direction leaves the projection stuck at `PENDING` / `netsuiteValid = null`. The two cases
  differ in seriousness and the UI cannot tell them apart:
  - a lost `NetSuiteConfigUpsertedEvent` means the configuration never arrived — ingestion will fail, and the admin
    must re-submit including the private key;
  - a lost `NetSuiteConfigAppliedEvent` means it arrived and works — ingestion succeeds, but the admin sees
    *Checking…* forever.

  The re-verify action above resolves both: republishing the upsert re-delivers the configuration if it was missing
  and regenerates the ACK if it was not. That makes it the highest-value follow-up rather than a nicety, and it is
  the first thing to add if stuck-`PENDING` reports appear in practice.
- `NetSuite10Api` and `HMACSha256SignatureService` in the netsuite module appear to be dead OAuth1 code. Removing them
  is not required here but would reduce the surface being reasoned about.
