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
| D6 | Delivery via transactional outbox with an ACK event | A dropped event must not silently lose a tenant's credentials |
| D7 | Credentials are validated synchronously against NetSuite before the write commits | Chosen explicitly; bad credentials never reach storage |
| D8 | Hard cutover — no environment-variable fallback | Chosen explicitly; avoids one tenant's credentials silently serving another organisation |
| D9 | Credentials and instance id are per organisation; tuning parameters stay global | Batch sizes and debug flags are operator concerns, not tenant data |
| D10 | The UI is scoped to the caller's own organisation | Chosen explicitly; no organisation picker needs to be built |
| D11 | Encryption happens in the organisation service layer, not in a JPA `AttributeConverter` | The ciphertext must travel in the outbox row and the event payload, both of which sit above persistence; a converter would leave plaintext in the event |
| D12 | Event classes live in the `organisation` module | netsuite already depends on organisation, so no new shared module and no circular edge |
| D13 | The netsuite module gains no REST controller | Consistent with `blockchain_publisher` / `blockchain_reader` |

### Accepted trade-offs

These follow from D6 + D7 and are accepted knowingly.

1. **The encrypted credentials cross Kafka twice** — once for the pre-flight validation exchange, once for the
   durable upsert. Both transmissions are ciphertext.
2. **The admin write path depends on NetSuite being reachable.** A save cannot succeed while the netsuite module is
   down or the NetSuite instance is unreachable; the endpoint returns `503` after the validation timeout.
3. **Hard cutover breaks ingestion until every organisation is reconfigured.** With D8 there is no fallback, so the
   upgrade window requires each organisation's admin to re-enter credentials, each save making a live NetSuite call.

## 6. Architecture

### 6.1 Write path

```
Admin (frontend)
  │  POST/PUT /api/v1/organisations/{orgId}/netsuite-configuration
  ▼
organisation module
  │  1. validate payload
  │  2. encrypt secret fields (AES-256-GCM)
  │  3. publish NetSuiteConfigValidationRequestEvent(correlationId)  ─── blocks on CompletableFuture (10s)
  ▼                                                                        │
[Kafka]  ──────────────────────────────────────────────────────────────────┤
  ▼                                                                        │
netsuite module                                                            │
  │  decrypt into a throwaway client, testConnection()                     │
  │  publish NetSuiteConfigValidationResponseEvent(correlationId, status) ─┘
  ▼
organisation module
  │  invalid   → 422, nothing stored
  │  timeout   → 503, nothing stored
  │  valid     → COMMIT: outbox row + projection row (syncState = PENDING) → 200/201
  ▼
outbox relay (@Scheduled)
  │  publish NetSuiteConfigUpsertedEvent, mark row published
  ▼
[Kafka] → netsuite module: upsert netsuite_adapter_organisation_config, bump revision
  │  publish NetSuiteConfigAppliedEvent(organisationId, revision, status)
  ▼
[Kafka] → organisation module: projection syncState = APPLIED, purge outbox payload
```

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

The same resolution applies to `NetSuiteReconcilationService` and to `NetSuiteExtractionService.validateIngestion`,
which returns the problem directly because it already runs through the correlation-id reply channel.

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
| `base_url`, `token_url`, `client_id`, `certificate_id`, `netsuite_instance_id` | non-secret, returned by the status endpoint |
| `private_key_fingerprint` | SHA3 digest, display only |
| `sync_state` | `PENDING` \| `APPLIED` \| `FAILED` |
| `sync_message` | last ACK failure detail, nullable |
| `revision` | monotonic, incremented per accepted write |
| `last_validated_at`, `last_validation_status` | from the pre-flight validation |
| `created_at`, `updated_at`, `updated_by` | audit, matching the module's `CommonEntity` convention |

**No secret column.** This table never holds key material.

### 8.2 `organisation` module — outbox

Table `organisation_netsuite_config_outbox`.

| Column | Notes |
|---|---|
| `id` | PK |
| `organisation_id`, `revision` | identifies the payload |
| `payload` | JSON serialisation of `NetSuiteConfigUpsertedEvent`, with the private key already ciphertext |
| `created_at`, `published_at`, `acknowledged_at` | lifecycle |
| `attempts`, `last_error` | relay diagnostics |

The `payload` column is set to `NULL` once `NetSuiteConfigAppliedEvent` arrives, so ciphertext does not linger.

### 8.3 `netsuite_altavia_erp_adapter` module — authoritative store

Table `netsuite_adapter_organisation_config`, migration in the netsuite module's `common` folder, following the
existing `netsuite_adapter_*` prefix convention.

| Column | Notes |
|---|---|
| `organisation_id` | PK |
| `base_url`, `token_url`, `client_id`, `certificate_id`, `netsuite_instance_id` | plaintext |
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
| `POST` | `/organisations/{orgId}/netsuite-configuration` | `201` | create; `409` if a row already exists |
| `PUT` | `/organisations/{orgId}/netsuite-configuration` | `200` | update; `404` if no row exists |
| `GET` | `/organisations/{orgId}/netsuite-configuration/status` | `200` | non-secret fields + sync/validation state |

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
| `NETSUITE_CREDENTIALS_INVALID` | 422 | pre-flight validation rejected the credentials |
| `NETSUITE_VALIDATION_UNAVAILABLE` | 503 | validation timed out or the netsuite module is not deployed |

`NETSUITE_CONFIGURATION_NOT_FOUND` is reused as the failure title on the ingestion path (§6.2), where it surfaces on
a `TransactionBatchFailedEvent` rather than an HTTP response.

## 10. Events

Four classes in `organisation/src/main/java/org/cardanofoundation/lob/app/organisation/domain/event/netsuite/`,
each carrying `EventMetadata` with a `VERSION` constant, per the platform convention.

| Event | Direction | Payload |
|---|---|---|
| `NetSuiteConfigValidationRequestEvent` | organisation → netsuite | `correlationId`, `organisationId`, non-secret fields, encrypted private key |
| `NetSuiteConfigValidationResponseEvent` | netsuite → organisation | `correlationId`, status, message |
| `NetSuiteConfigUpsertedEvent` | organisation → netsuite | `organisationId`, `revision`, non-secret fields, encrypted private key |
| `NetSuiteConfigAppliedEvent` | netsuite → organisation | `organisationId`, `revision`, status, message |

`toString()` must exclude the encrypted key so it cannot reach logs — the publishers in `cf-reeve-application` log the
whole event at INFO today.

A `NetSuiteConfigResponseWaiter` in the organisation module mirrors `ValidateIngestionResponseWaiter`.

### 10.1 Kafka wiring (`cf-reeve-application`)

Topics, added to `cf-application/src/main/resources/application.yml`:

```yaml
lob:
  organisation:
    consumer-group: lob-consumer-organisation
    topics:
      netsuite-config-validation-response: organisation.domain.event.netsuite.NetSuiteConfigValidationResponseEvent
      netsuite-config-applied: organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent
  netsuite:
    topics:
      netsuite-config-validation-request: organisation.domain.event.netsuite.NetSuiteConfigValidationRequestEvent
      netsuite-config-upserted: organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent
```

New `kafka/publisher/OrganisationKafkaPublisher.java` and `kafka/consumer/OrganisationKafkaConsumer.java`; the
existing `NetsuiteKafkaPublisher` and `NetSuiteKafkaConsumer` gain one handler each per direction. All gated with the
established `@ConditionalOnProperty({"lob.<module>.enabled", "spring.kafka.enabled"})`.

**Consumer group scoping.** The applied-ACK listener uses the shared `lob-consumer-organisation` group — any instance
may update the projection. The **validation-response listener must use a per-instance group**
(`${lob.organisation.consumer-group}-${random UUID}`), because the `CompletableFuture` it completes lives in one JVM's
heap. With a shared group the reply can be delivered to an instance that is not waiting, and the request hangs until
timeout. Every instance therefore receives every reply and only the one holding the correlation id acts on it. The
existing `ValidateIngestionResponseWaiter` has this same latent defect; fixing it there is out of scope but should be
recorded.

### 10.2 Updating without re-entering the private key

The organisation module keeps no copy of the key (D5, §8.1), so it cannot re-send one on a `PUT` where the admin left
the field blank. The netsuite module owns the key, so it supplies it:

- In both `NetSuiteConfigValidationRequestEvent` and `NetSuiteConfigUpsertedEvent`, a **null** `privateKeyEncrypted`
  means *"reuse the key already stored for this organisation"*.
- On validation, the netsuite module loads the stored ciphertext for that organisation, decrypts it, and tests the
  connection using the **new** non-secret fields together with the **existing** key. This is what makes "change the
  account URL without retyping the key" work.
- On upsert, it writes the new non-secret fields and leaves `private_key_encrypted` untouched.
- If the field is null and the netsuite module has no stored configuration for that organisation — the projection and
  the authoritative store having diverged — it replies with a validation failure carrying
  `NETSUITE_CONFIGURATION_NOT_FOUND`, and the organisation module surfaces `422`. The admin recovers by supplying the
  key explicitly.

`privateKeyEncrypted` is mandatory on the create path; the controller rejects a `POST` without it before any event is
published.

## 11. NetSuite client resolution

- `NetSuiteClient` loses `@PostConstruct init()` and its `privateKeyFilePath` parameter. It takes a resolved
  `PrivateKey` (or the decrypted PEM) plus the per-organisation connection fields. Token refresh becomes lazy, on
  first use.
- A new `NetSuiteClientRegistry` resolves and caches one client per organisation, each with its own token cache. It
  evicts an organisation's client when `NetSuiteConfigUpsertedEvent` is applied, so a credential change takes effect
  without a restart.
- `NetSuiteConfigService` handles the upsert and lookup; `NetSuiteConfigEventHandler` listens for the two inbound
  events and publishes the two outbound ones.
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
- **Status chip:** `configured` / `pending` / `failed`, with `lastValidatedAt`.
- **Wiring:** route in `settings.routes.tsx` wrapped in `<RequirePermission resource="netsuite_configuration"
  action="view">`, path constant in `consts/routes/routes.consts.ts`, nav entry in
  `NavigationSidebar.service.tsx`, strings in `libs/translations/en-US.json`.
- **Error surfacing:** `422` shows the NetSuite validation message inline against the credential fields; `503` shows a
  distinct "could not reach NetSuite to verify — try again" message, since the two mean different things to the admin.

## 13. Application repository

- **`cf-application/src/main/resources/application.yml`** — add the four topics and `lob.organisation.consumer-group`;
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

- The key material is protected in transit and at rest by D1. The Kafka topic, the outbox row and the netsuite table
  all hold ciphertext only.
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
- **Organisation service:** create/update happy paths; `409` on `POST` with an existing row in each `syncState`;
  `404` on `PUT` with no row; `GET` returns `{"configured": false}` rather than `404` for a fresh organisation;
  validation rejection leaves no row written; validation timeout leaves no row written; outbox and projection written
  in the same transaction; `POST` without `privateKey` is rejected before any event is published.
- **Outbox relay:** publishes unpublished rows, marks them, retries on failure, purges payload on ACK.
- **Netsuite module:** upsert idempotency by `revision`, out-of-order event handling, registry caching and eviction on
  update, `NETSUITE_CONFIGURATION_NOT_FOUND` when an ingestion runs for an unconfigured organisation.
- **Null-key update path (§10.2):** `PUT` without `privateKey` validates against the stored key and the new non-secret
  fields; the upsert leaves `private_key_encrypted` byte-identical; a null key with no stored configuration yields
  `422` rather than writing a configuration with no key.
- **Boundary:** a test asserting no `organisation` package imports a `netsuite_altavia_erp_adapter` repository or
  entity. Since there is no ArchUnit in the repository today, this is the one place worth adding a focused check —
  D3 is otherwise unenforced.
- **Frontend:** the private key is never rendered from a status response; the field submits only when dirty; the
  route is inaccessible to non-admin roles.

## 16. Rollout

Hard cutover, per D8. Sequence:

1. Deploy the platform and application changes with `LOB_CONFIG_ENCRYPTION_KEY` set on both services. Ingestion for
   every organisation fails with `NETSUITE_CONFIGURATION_NOT_FOUND` from this point.
2. Each organisation's admin enters credentials through the new UI. Each save performs a live NetSuite call.
3. Confirm every organisation shows `APPLIED`.
4. Remove the now-unused `LOB_NETSUITE_CLIENT_*` credential variables and the PEM mount from deployment manifests.

The window between steps 1 and 3 is a genuine outage for NetSuite ingestion. If that is unacceptable operationally,
the decision to revisit is D8, not the design.

## 17. Open items and follow-ups

- `ValidateIngestionResponseWaiter` has the same multi-replica defect described in §10.1. Out of scope here; worth a
  separate ticket.
- Encryption key rotation. The `v1:` envelope makes it possible; no tooling ships with this work.
- Moving `LOB_CONFIG_ENCRYPTION_KEY` into a secret manager.
- The Kafka layer has no DLQ or retry anywhere in the platform. The outbox covers the organisation → netsuite
  direction, but a lost `NetSuiteConfigAppliedEvent` leaves the projection stuck at `PENDING` even though the
  configuration was stored correctly. The status is then pessimistic rather than wrong; a resync/reconcile action
  would close the gap.
- `NetSuite10Api` and `HMACSha256SignatureService` in the netsuite module appear to be dead OAuth1 code. Removing them
  is not required here but would reduce the surface being reasoned about.
