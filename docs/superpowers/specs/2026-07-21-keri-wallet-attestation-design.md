# KERI/Veridian Wallet Attestation for document_vault Publishes — Design

**Date:** 2026-07-21 (rev 2 — after Codex cross-review, verdict on rev 1: needs-changes)
**Status:** Draft — pending user review
**Repos:**
- Backend: `cf-reeve-platform` (branch `feat/document-module`)
- Frontend: `cf-lob-frontend`, worktree `.claude/worktrees/feat+document-module`

## 1. Goal

Let a platform user attest a document_vault publish transaction with their own KERI identity held in the Veridian mobile wallet. When the user clicks **Publish**, a popup offers attestation. If accepted, the frontend guides the user through:

1. **OOBI exchange** — backend agent OOBI shown as a QR code (scanned with Veridian); the user pastes their wallet's OOBI URL back; the backend resolves it.
2. **Credential presentation** (first time) — the user shares their credential (e.g. vLEI chain) from Veridian via IPEX so the backend can obtain and validate the full CESR chain.
3. **AUTH_BEGIN** (first time, explicitly once) — the backend publishes the credential chain on-chain in a standalone CIP-170 `AUTH_BEGIN` transaction, establishing the user's signing authority. The UI states clearly that this is a **one-time** step and offers a verified skip for users whose authority already exists on-chain.
4. **ATTEST** — the backend computes the digest of the complete label-1447 metadata for this document publish and asks the user's wallet to anchor **exactly that digest** in their KEL; the user approves the notification in Veridian. The publish transaction is then built with both label 1447 (document metadata) and label 170 (`ATTEST`).

The KERI orchestration services must be **reusable** for other transaction types (e.g. the existing report publish path) without modification.

### Non-goals

- No changes to the current backend-driven KERI implementation (`KeriConfig`, `KeriService`, `API3L1TransactionCreator` in `blockchain_publisher`). It keeps working as-is; it may *later* be migrated onto the new module, but not in this work.
- No `AUTH_END` **emission** in v1 (observation of existing AUTH_ENDs is a documented limitation, §11).
- No multi-instance backend coordination (matches the existing single-instance dispatch-job assumption).

### Normative reference

CIP-170 is **Proposed**, not Active. Implementation pins a specific commit of `cardano-foundation/CIPs` CIP-0170/README.md (recorded in the module README at implementation time) and follows its exact field names, values (including the `v` version object contents), and verification rules. The cip113 platform and `cf-reeve-document-demo` are treated as *mechanical* references (how to drive signify/Veridian), **not** as normative sources — rev 1 of this spec inherited two protocol deviations from them, caught in review (§4.4, §4.6).

## 2. Background

### 2.1 Current state

- **Existing KERI code** lives in `blockchain_publisher` (`config/KeriConfig.java`, `service/KeriService.java`), gated by `lob.blockchain-publisher.keri.enabled`. The backend owns the KERI seed (`bran`) and anchors digests itself via `signifyClient.identifiers().interact(prefix, digestQb64)`. Wired only into the report path (`API3L1TransactionCreator`). **Untouched by this design.**
- **Document publish flow** is asynchronous: `POST /api/v1/document-vault/documents/{id}/publish` → `VaultDocumentService.publish` sets `PUBLISHED` + `MARK_DISPATCH` and emits `DocumentPublishCommand` → `BlockchainPublisherEventHandler` stores it → a scheduled dispatcher calls `DocumentL1TransactionCreator.pullBlockchainTransaction`, which uploads the envelope to IPFS, fetches the chain tip, builds the 1447 `MetadataMap` via `DocumentMetadataSerialiser`, CBOR-serializes, validates, signs with the organiser wallet, and submits. A retry sweep (`DocumentDispatchRetryJob`) re-emits stuck publishes.
- **signify-java** (`org.cardanofoundation:signify`) is already a resolvable dependency (used by `blockchain_publisher`).

### 2.2 References (mechanical, non-normative)

- **cip113 `KeriService`** (`cip113-programmable-tokens-platform`): backend-orchestrated OOBI resolve, IPEX presentation, CESR chain fetch/reduction, wallet interaction via `/remotesign/ixn/req` exchange + notification polling.
- **`cf-reeve-document-demo`** (`src/lib/keri/*`, `src/lib/cardano/cip170.ts`): the Veridian pairing UX (OOBI QR display + paste), `remotesignAnchor` waiting behavior, IPEX flows.
- **`docs/keri/` jbang scripts** (this repo): `AttestTransaction.java` demonstrates the *correct* CIP-170 anchoring (raw metadata digest as the KEL seal); CESR fixtures for tests.

## 3. Architecture

**Backend-orchestrated ceremony, frontend-guided.** A new Gradle module `keri_attestation` owns a signify-java client with the platform's agent identity and exposes REST endpoints for each ceremony step. The frontend wizard calls them and polls ceremony state. The user's private keys never leave Veridian; the backend orchestrates and pays for the Cardano transactions.

### 3.1 Module and dependency graph

New subproject `keri_attestation` (package `org.cardanofoundation.lob.app.keri_attestation`), added to `settings.gradle.kts`.

```
support, organisation, blockchain_common
        ▲
   keri_attestation          (new; depends on support, organisation, blockchain_common; declares signify dep)
        ▲              ▲
  document_vault   blockchain_publisher
        ▲              │
        └──────────────┘   (blockchain_publisher already depends on document_vault)
```

- `document_vault` gains a **soft** dependency on `keri_attestation`: compile-time dependency for the port interface, runtime wiring via `ObjectProvider` so document_vault works fully with the module disabled (§5.1).
- `blockchain_publisher` gains a dependency on `keri_attestation` (implements its ports; attaches ATTEST metadata at dispatch).
- No cycles: `keri_attestation` never depends on `document_vault` or `blockchain_publisher`.

Module activation follows the platform idiom: `KeriAttestationModuleConfig` with `@ConditionalOnProperty("lob.keri-attestation.enabled", havingValue = "true")` + `@ComponentScan`. Config namespace is **separate** from the existing `lob.blockchain-publisher.keri.*` so both implementations can coexist. Spring context tests cover all enabled/disabled combinations of `keri_attestation` × `document_vault` × `blockchain_publisher` (§9).

### 3.2 Configuration

```yaml
lob:
  keri-attestation:
    enabled: false                      # default off
    keria:
      url: …                            # KERIA admin URL
      boot-url: …
      bran: …                           # backend agent passcode (secret)
    identifier-name: reeve-agent        # backend agent AID alias
    credential-policy:
      schema-saids: [<leaf-schema-said>]   # acceptable leaf-credential schemas
      trusted-root-aids: [<root-aid>]      # trust anchors the chain must terminate in (e.g. GLEIF)
    ceremony-ttl: PT1H                  # ceremony expiry
    freeze-max-age: PT24H               # attested freeze older than this requires re-attestation
    remotesign-timeout: PT3M            # wallet approval wait
    notification-poll-interval: PT1.5S
    auth-begin-confirmations: 3         # blocks before AUTH_BEGIN counts as CONFIRMED
    limits:
      max-active-ceremonies-per-user: 3
      step-cooldown: PT10S              # min interval between retries of the same step
```

### 3.3 Ports (the reuse seam)

Defined in `keri_attestation`, implemented by host modules:

```java
public interface CardanoMetadataTxSubmitter {
    /** Builds, signs and submits a tx carrying only the given metadata. Returns tx hash. */
    Either<ProblemDetail, String> submitMetadataTransaction(long label, MetadataMap metadata);
    /** Confirmation depth of a tx, empty if unknown/not found. */
    Optional<Long> confirmations(String txHash);
    /** Reads label-170 metadata of an on-chain tx (for EXTERNAL authority verification). */
    Optional<Map<String, Object>> readCip170Metadata(String txHash);
}

public interface AttestationTargetProvider {
    String targetType();                                   // e.g. "DOCUMENT"
    /** Authorization check (publish rights, target in attestable state). Called at ceremony
        creation AND again at the attest step. */
    Optional<ProblemDetail> authorize(String targetId, String userId);
    /** Freeze the target's metadata for this ceremony and return the digest to attest.
        Idempotent per (targetId, ceremonyId). */
    Either<ProblemDetail, AttestationDigest> prepareDigest(String targetId, String ceremonyId);
}

public record AttestationDigest(String digestQb64, String metadataLabel) {}
```

- `CardanoMetadataTxSubmitter` is implemented in `blockchain_publisher` using the existing organiser `Account` + `QuickTxBuilder` + Blockfrost backend (backend wallet pays for AUTH_BEGIN, per decision).
- `AttestationTargetProvider` for `DOCUMENT` is implemented in `blockchain_publisher` (§5.2). The future report path adds its own provider without touching `keri_attestation`.

### 3.4 Feature flag & graceful degradation (hard requirement)

The module is **off by default** (`lob.keri-attestation.enabled: false`) and every consumer must work correctly with it disabled:

| Consumer | Behavior with module disabled |
|---|---|
| `keri_attestation` itself | No beans created, no Flyway objects touched at runtime, no KERIA connection attempted, no REST endpoints (404). |
| `document_vault` | Wires the consumption port via `ObjectProvider`; bodiless publish is completely unaffected; a publish body carrying `attestationCeremonyId` → `422 ATTESTATION_UNAVAILABLE`. No hard bean dependency — context starts cleanly. |
| `blockchain_publisher` | `DocumentAttestationLookup` and the port implementations are conditional on the same flag; dispatch of non-attested documents is byte-for-byte the current path. Existing report-path KERI code is independent of this flag. |
| Frontend | `GET /identity` 404 → the attest option is hidden and `PublishAction` falls back to today's plain confirmation dialog. No console errors, no dead UI. |

Spring context tests assert clean startup and correct degradation for every enabled/disabled combination of `keri_attestation` × `document_vault` × `blockchain_publisher` (§9).

## 4. `keri_attestation` module detail

### 4.1 Persistence (Flyway, module-scoped migrations)

**`keri_identity_link`** — one row per platform user (Keycloak user id, per decision):

| column | notes |
|---|---|
| `user_id` (PK) | Keycloak subject |
| `binding_version` | integer, incremented on every relink (§4.7) |
| `aid` | user's Veridian AID (from OOBI resolve) |
| `oobi_url` | as pasted/resolved (kept for audit/verifier discovery, §11) |
| `credential_said`, `credential_schema_said` | set after validated IPEX presentation |
| `auth_begin_tx_hash`, `auth_begin_block`, `auth_begin_at` | set once AUTH_BEGIN is CONFIRMED, or verified external tx (§4.5) |
| `created_at`, `updated_at` | audit |

**`keri_attestation_ceremony`**:

| column | notes |
|---|---|
| `id` (PK, UUID) | |
| `user_id`, `binding_version` | owner + the link version it was created under; a relink invalidates open ceremonies |
| `target_type`, `target_id` | e.g. `DOCUMENT`, documentId |
| `state` | enum, §4.2 |
| `attempt_generation` | integer; every retry increments it; async completions CAS on (state, generation) |
| `error_title`, `error_detail` | populated on FAILED |
| `request_exn_said` | SAID of the last sent exchange (IPEX apply / remotesign req) for notification correlation |
| `metadata_digest`, `metadata_label` | digest handed to the wallet (= on-chain `170.d`) |
| `kel_sequence`, `kel_event_said` | anchoring event: sequence (hex, = on-chain `170.s`) + event SAID (audit) |
| `created_at`, `updated_at`, `expires_at` | TTL from config |

Data note: these tables hold **pseudonymous personal data** (Keycloak subject linked to AID, OOBI URL, credential identifiers) — not "no PII". They are covered by the platform's GDPR data-subject processes; deleting a user's link removes the linkage (on-chain data is immutable by nature and disclosed to the user in the wizard, §6).

### 4.2 Ceremony state machine

```
CREATED ─▶ OOBI_RESOLVED ─▶ CREDENTIAL_REQUESTED ─▶ CREDENTIAL_RECEIVED
   │                │                                      │
   │ (link has aid) │ (link has credential)                ▼
   └────────────────┴───────────────▶ AUTH_BEGIN_SUBMITTED ─▶ AUTH_BEGIN_CONFIRMED
                                             │ (verified external / link has confirmed auth_begin)
                                             └────────────────────────────────▼
                                                     ATTEST_REQUESTED ─▶ ATTEST_ANCHORED ─▶ CONSUMED
Any non-terminal state ─▶ FAILED (retryable) or EXPIRED (TTL sweep / lazy check)
```

- On `POST /ceremonies`, the service inspects the caller's `keri_identity_link` and fast-forwards past already-completed one-time steps, returning which steps the UI must still show.
- The one-time steps write their results to `keri_identity_link` (OOBI resolve is identity-level; credential and auth-begin are ceremony-scoped but persist to the link). Ceremony state is re-derived from the link on read, so completing an identity-level step advances any open ceremony automatically.
- Waiting steps run on a dedicated async executor; the triggering POST returns `202` immediately and the frontend polls `GET /ceremonies/{id}`.
- **Retry/atomicity discipline:** every step POST is idempotent against the current state (repeat POST in the same waiting state → `409` with current state, unless `retry=true`). A retry increments `attempt_generation` after the step cooldown; the superseded async worker's completion then fails its CAS and is discarded. Before re-sending a wallet request, the orchestrator checks for a late-arriving matching notification (correlated by `request_exn_said`) and completes with it instead of re-sending.
- Stale detection: a ceremony sitting in a waiting state longer than the step timeout + grace is reported `FAILED(KERI_STEP_TIMED_OUT)` (covers backend restarts mid-wait).

### 4.3 Services

- **`KeriAgentService`** — builds the `SignifyClient` (connect, boot-fallback), ensures the agent AID exists (alias lookup or create, mirroring the existing `KeriConfig.createIdentifier` logic — reimplemented here, not imported, to keep zero coupling to the old code), exposes the agent OOBI.
- **`KeriOobiService`** — `resolveUserOobi(userId, oobiUrl)`: URL shape validation (https, syntactically valid, length-capped), then `client.oobis().resolve(oobi, alias)` + operation wait, AID extraction, contact verification, persist to `keri_identity_link` (with relink semantics, §4.7). Synchronous, ~15s timeout. SSRF note: resolution is performed by the KERIA agent, which must be deployed egress-restricted (documented ops requirement); the app server never fetches the URL itself.
- **`KeriNotificationCorrelator`** — polls `notifications().list()` and claims a notification **only if** route matches AND the referenced exchange's sender is the linked AID, recipient is the agent AID, and its thread/prior links back to `request_exn_said`. Mark-and-delete only after the ceremony transition is durably committed.
- **`KeriCredentialService`** — IPEX presentation: `apply` (schema SAIDs from `credential-policy`) → wait offer → `agree` → wait grant → `admit` (all correlated per above); fetch full CESR chain (`Accept: application/json+cesr`). Then **`CredentialChainValidator`** (see below) must pass before the credential is persisted to the link.
- **`CredentialChainValidator`** — validates the presented chain before it is accepted or published: parses the full CESR stream; verifies the ACDC chain edge-by-edge up to a **trusted root AID** (`credential-policy.trusted-root-aids`); verifies TEL state (issued, not revoked) for every link; asserts the leaf credential's **issuee equals the linked AID** and its **schema SAID is allowlisted**. Rejection reasons map to distinct problem titles.
- **`CesrChainReducer`** — reduces the full CESR stream to the minimal on-chain chain (registry inception + issuance + ACDC per link, canonical order). Acceptance criterion: the reduced stream must **round-trip through the validator** (parse + verify) — a reduction that drops needed events or attachments fails the build, not the verifier.
- **`KeriAuthBeginService`** — §4.5.
- **`KeriAttestService`** — §4.6.
- **`CeremonyService`** — state machine guards, ownership checks, expiry, rate limits (`limits.*`), `validateAndConsume` (§5.1).
- **`Cip170MetadataFactory`** — pure builders for the label-170 `AUTH_BEGIN` and `ATTEST` `MetadataMap`s, field-for-field per the pinned CIP-170 commit (including the exact `v` object contents and the 64-byte chunk encoding of `c` — chunks are the byte-slices of the reduced CESR stream in order; a verifier reassembles by concatenation).

### 4.4 ATTEST semantics (corrected in rev 2)

**`170.d` is the CESR digest of the label-1447 metadata value itself** — not the SAID of any wrapper payload — and the KEL seal anchored by the wallet must be **that same digest**. (Rev 1 followed the demo's wrapper-SAID pattern; review confirmed CIP-170 and the repo's own `docs/keri/AttestTransaction.java` require the direct digest.)

- Digest: `CborSerializationUtil.serialize(metadataMap.getMap())` → CESR `Diger` Blake3-256, qb64 (`E…`) — same algorithm as the existing report-path implementation.
- The remotesign request to the wallet must therefore result in an interaction event whose seal digest **equals `metadata_digest`**. The exact request KED shape is verified against Veridian behavior in an **M2 spike** (first implementation task): send the request, approve in a real wallet, fetch the KEL event, assert the seal. If Veridian's remotesign can only anchor a request-payload SAID (not a caller-chosen digest), this is a **hard blocker to escalate** — a transport-envelope SAID is not interchangeable with the CIP digest, and we would need wallet-side or spec-side coordination before proceeding.
- On-chain map: `{t:"ATTEST", i:<userAid>, d:<metadata_digest>, s:<kel_sequence hex>, v:{…pinned…}}`.

**Verifier story:** read the tx; digest the label-1447 value bytes (Blake3-256 Diger qb64); compare with `170.d`; resolve the signer's KEL; fetch the event at sequence `170.s`; assert its seal equals `170.d`; check authority via the AUTH_BEGIN chain.

### 4.5 AUTH_BEGIN (hardened in rev 2)

- On-chain map: `{t:"AUTH_BEGIN", i:<userAid>, s:<credential_schema_said>, c:[chunks], v:{…pinned…}}`. **`s` is the leaf credential's schema SAID** (rev 1 wrongly used the credential instance SAID); the chain is *fetched* by `credential_said` but *identified* on-chain by schema, after the validator confirms the leaf's schema matches.
- Submission: build map → `CardanoMetadataTxSubmitter.submitMetadataTransaction(170, map)` → state `AUTH_BEGIN_SUBMITTED` with tx hash. A lightweight poll (same async executor) waits until `confirmations(txHash) >= auth-begin-confirmations` → `AUTH_BEGIN_CONFIRMED`, persist hash/block to the link. Not-found after a rollback-window timeout → `FAILED(AUTH_BEGIN_ROLLED_BACK)`, resubmittable.
- **External authority (the "skip"):** instead of a blind flag, the user provides their existing AUTH_BEGIN tx hash. The backend verifies via `readCip170Metadata(txHash)`: label 170 present, `t == "AUTH_BEGIN"`, `i` equals the linked AID, schema allowlisted. Only then is the link marked with that tx hash. An unverifiable hash → `422`.
- Authority is treated as established-once for UX, but is **not proven permanent**: AUTH_END observation is a documented v1 limitation (§11) — verifiers, not this platform, are the authority oracle.

### 4.6 Wallet anchoring flow (ATTEST step, corrected ordering)

1. Re-run `authorize` (target may have changed since ceremony creation).
2. `prepareDigest(targetId, ceremonyId)` → freeze + `metadata_digest` (§5.2).
3. Build the remotesign request KED for anchoring `metadata_digest` (shape per M2 spike, §4.4); persist `request_exn_said` **before** sending; send `/remotesign/ixn/req` to the linked AID.
4. Await the correlated `/remotesign/ixn/ref` (≤ `remotesign-timeout`) via `KeriNotificationCorrelator`.
5. From the correlated ref (not from "latest key state"): obtain the anchoring interaction event — sequence and SAID; **fetch that exact KEL event and assert its seal equals `metadata_digest`**. (Rev 1 read the latest key-state sequence, which races with unrelated wallet events.) Key-state query is used only to confirm KEL availability, with bounded retries.
6. Persist `kel_sequence` + `kel_event_said`; CAS state (with generation) → `ATTEST_ANCHORED`.

### 4.7 Relinking

`POST /identity/oobi/resolve` when a link already exists and resolves to a **different AID**: requires explicit `relink=true`, increments `binding_version`, clears `credential_*` and `auth_begin_*` (they belong to the old AID), and invalidates all non-terminal ceremonies of the user (state → `FAILED(IDENTITY_RELINKED)`). Same AID → no-op refresh of `oobi_url`.

### 4.8 REST API

Base path `/api/v1/keri-attestation`. Conventions match document_vault: services return `Either<ProblemDetail, T>`, controllers map via a `Responses` helper, error titles in `KeriAttestationProblems`, `@PreAuthorize` on every endpoint (authenticated platform user; step endpoints enforce ceremony ownership; target authorization via provider).

| Endpoint | Purpose |
|---|---|
| `GET /identity` | `{linked, aid?, credential?: {said, schemaSaid}, authBegin?: {txHash, at, external}}` |
| `GET /agent/oobi` | `{oobiUrl}` for the QR code |
| `POST /identity/oobi/resolve` `{oobiUrl, relink?}` | Resolve wallet OOBI (sync), persist link → `{aid}` |
| `POST /ceremonies` `{targetType, targetId}` | Create; `{id, state, requiredSteps: {oobi, credential, authBegin}}` (respects `limits.*`) |
| `POST /ceremonies/{id}/credential/request` `{retry?}` | Start IPEX presentation → `202` |
| `POST /ceremonies/{id}/auth-begin` `{externalTxHash?, retry?}` | Publish chain tx, or verify external authority → `202` |
| `POST /ceremonies/{id}/attest` `{retry?}` | Freeze digest + remotesign → `202` |
| `GET /ceremonies/{id}` | `{id, state, error?, attest?: {digest, sequence, eventSaid}, authBegin?: {txHash, confirmations}}` |

Module disabled → beans absent → 404, which the frontend treats as "attestation unavailable" and falls back to the plain publish modal.

## 5. Document integration

### 5.1 Publish endpoint (document_vault, additive, fail-closed)

`POST /api/v1/document-vault/documents/{id}/publish` accepts an **optional** JSON body `{attestationCeremonyId}`. No body → behavior unchanged. With a ceremony id, inside the **same transaction** that row-locks the document and flips its status:

1. All existing checks (org membership, DRAFT, IPFS availability).
2. `CeremonyService.validateAndConsume(ceremonyId, "DOCUMENT", documentId, currentUserId)` — must be `ATTEST_ANCHORED`, owned by the caller, current `binding_version`, targeting this document; snapshot fingerprint check (§5.2); compare-and-set → `CONSUMED`. Any failure → `422`, document stays DRAFT, ceremony state unchanged (CAS semantics; same datasource/transaction manager, so rollback reverts both sides together).
3. The consumed `ceremonyId` is **persisted on the document row** (new nullable column `attestation_ceremony_id`) and carried in `DocumentPublishCommand` (new optional field) into `blockchain_publisher`'s dispatch record. The retry sweep re-emits via the same factory, so the binding survives retries. Wiring uses `ObjectProvider` — with `keri_attestation` disabled, a request body with a ceremony id is rejected `422 ATTESTATION_UNAVAILABLE`; bodiless requests never touch it.

### 5.2 Metadata freeze (blockchain_publisher) — immutable per ceremony

Because publishing is asynchronous but the user attests **now**, the 1447 metadata must be final at attest time. The `DOCUMENT` provider's `prepareDigest` does, at attest time, what `DocumentL1TransactionCreator` would do later: serialize the envelope, publish to IPFS (cid), fetch the tip (`creation_slot`), build the 1447 `MetadataMap` via `DocumentMetadataSerialiser`, CBOR-serialize, digest, and store a **freeze record**:

**`document_attestation_freeze`** — **immutable, keyed by `(document_id, ceremony_id)`** (rev 1's replace-by-document allowed an attested publish to race to the plain path): `document_id`, `ceremony_id` (unique pair), `ipfs_cid`, `frozen_metadata_cbor`, `digest_qb64`, `metadata_creation_slot`, `envelope_sha256`, `created_at`. Re-attestation creates a new row under the new ceremony; old rows are garbage (cleaned with expired ceremonies).

- `envelope_sha256` — SHA-256 over the exact serialized envelope bytes uploaded to IPFS. This is the **snapshot fingerprint**: `validateAndConsume` (via the provider) re-serializes the envelope and compares; any drift (content, slots, nonce, envelope version — all inputs to the serialization) → `422 ATTESTED_CONTENT_CHANGED`. (Rev 1 compared only `content_hash`, which does not cover all serialized inputs.)
- Freeze age: consuming a freeze older than `freeze-max-age` is rejected → re-attest.
- Privacy note (explicit decision): the IPFS upload happens at attest time, before the final publish click. The envelope is end-to-end encrypted ciphertext, identical to what publish would upload; the wizard copy states that starting attestation uploads the encrypted envelope.

### 5.3 Transaction build hook (blockchain_publisher, additive, fail-closed)

`DocumentL1TransactionCreator` gains one optional collaborator (`DocumentAttestationLookup`, `ObjectProvider`-wired). In `pullBlockchainTransaction`:

- Dispatch record carries `attestation_ceremony_id` → load the freeze by `(document_id, ceremony_id)`. **Missing freeze, non-CONSUMED ceremony, or digest mismatch → the dispatch attempt fails with a distinct error (`ATTESTED_METADATA_MISMATCH` / `ATTESTATION_FREEZE_MISSING`); it never silently falls back to a plain publish.** Otherwise: deserialize `frozen_metadata_cbor`, recompute the digest and assert it equals `digest_qb64` (guards non-deterministic re-encoding), reuse the frozen `ipfs_cid` (no re-upload), attach label 1447 from the frozen map **plus** label 170 `ATTEST` from the ceremony.
- The frozen `metadata_creation_slot` lives **only inside the 1447 metadata**; the transaction's own validity/creation slot for dispatcher bookkeeping (rollback aging) is a **fresh tip per submission attempt** — freezing must not make retries look immediately stale.
- No `attestation_ceremony_id` on the record → the current code path, byte-for-byte unchanged.

## 6. Frontend (worktree `feat+document-module`)

### 6.1 Entry point

`PublishAction` (`features/publish-action/`) swaps its `ConfirmationModal` for `AttestPublishModal`:

- **Publish without attestation** → existing `usePublishAction` path, unchanged.
- **Attest & publish with Veridian** → wizard below. Option hidden (falls back to today's confirm dialog) when `GET /identity` 404s (module disabled).

### 6.2 Wizard — `features/attest-publish/`

`Modal` + `StepperManager` (the `document-create` / `EnrollmentModal` conventions: async states inside `Modal.Content`, `Alert` for errors, primary action disabled while a wallet prompt is pending). Steps computed from `GET /identity` + `requiredSteps` — returning users see only **Attest**.

1. **Pair** (`pair-step`) — both directions of the OOBI exchange, camera-assisted:
   - *Backend → wallet:* backend agent OOBI as QR (`qrcode.react` `QRCodeSVG`, new dependency), scanned with the Veridian app.
   - *Wallet → backend:* an in-browser **camera scanner** (`qr-scanner`, new dependency — small, no transitive deps, Vite-friendly) reads the OOBI QR shown by the Veridian app directly from the webcam; a paste field remains as the always-available fallback (no camera, permission denied, unsupported browser). Camera permission is requested only when the user activates the scanner; scanned/pasted input goes through the same validation → `POST /identity/oobi/resolve`.
2. **Credential** (`credential-step`) — `POST …/credential/request`, "Open Veridian and share your credential", ceremony polling; validation-rejection errors surfaced with the mapped problem title.
3. **One-time authorization** (`auth-begin-step`) — copy explains AUTH_BEGIN establishes on-chain signing authority **once per identity**; choices: *Publish my credential chain now* / *I already have on-chain authority* → requires pasting the existing AUTH_BEGIN tx hash (verified server-side, §4.5). Shows confirmation progress for the submitted tx.
4. **Attest** (`attest-step`) — `POST …/attest`; copy notes that starting this step uploads the encrypted envelope (§5.2); "Approve the signing request in your Veridian wallet"; polling until `ATTEST_ANCHORED`; timeout → retry (`retry=true`) with duplicate-notification caveat in copy.
5. **Publish** — `triggerPublishVaultDocument` with `{attestationCeremonyId}`; close; existing dispatch-status polling on document detail takes over.

### 6.3 API layer (repo conventions)

- `src/libs/api-connectors/backend-connector-lob/api/keri-attestation/` — `keri-attestation-api.service.ts`, `.types.ts`, `.consts.ts` (`KERI_ATTESTATION_API_BASE = 'api/v1/keri-attestation'`, error titles).
- `src/libs/models/keri-attestation-model/` — react-query hooks per operation; `GetCeremonyModel` uses `refetchInterval: 2000` while in a waiting state, `false` otherwise.
- `publishDocument` gains the optional body; `usePublishAction` gains an optional ceremony id.
- Errors route through the problem-detail pattern (`extractProblem` + `useKeriErrorMessage` mirroring `useVaultErrorMessage`); every hook catch block uses the resolver.
- i18n strings in `en-US.json`; `qrcode.react` (QR display) and `qr-scanner` (camera scanning) added to `package.json`.

## 7. Errors & edge cases

| Case | Handling |
|---|---|
| Wallet approval timeout | `FAILED(KERI_WALLET_TIMEOUT)`; retry re-checks for a late correlated notification before re-sending (§4.2). |
| Backend restart mid-wait | Stale-state detection → `FAILED(KERI_STEP_TIMED_OUT)` → retry with generation bump. |
| Concurrent ceremonies / cross-talk | Notification correlation by `request_exn_said` + sender/recipient (§4.3); per-user ceremony limits. |
| Abandoned ceremony | `EXPIRED` after TTL; freeze rows of terminal ceremonies cleaned by the same sweep. |
| Document changed after attest | `ATTESTED_CONTENT_CHANGED` at publish (envelope fingerprint) or `ATTESTED_METADATA_MISMATCH` at dispatch (digest assertion). Both fail closed. |
| Freeze replaced / missing at dispatch | Impossible by construction (immutable per ceremony, binding carried on the dispatch record); missing → `ATTESTATION_FREEZE_MISSING`, dispatch fails, never plain-publishes. |
| Plain publish after attested ceremony | Ceremony never `CONSUMED`; bodiless publish takes the normal path; ceremony expires. |
| Double consumption / replay | CAS on `CONSUMED` inside the publish transaction; ceremony bound to (user, binding_version, target). |
| AUTH_BEGIN rollback | Confirmation tracking → `FAILED(AUTH_BEGIN_ROLLED_BACK)`, resubmittable. |
| Relink to a different AID | Explicit `relink=true`; dependent state cleared; open ceremonies invalidated (§4.7). |
| Invalid/unverifiable external AUTH_BEGIN hash | `422` with reason. |
| OOBI URL garbage | Shape validation + resolve timeout → `422`. |
| Module disabled | Endpoints 404 → frontend falls back to plain publish; publish body with ceremony id → `422 ATTESTATION_UNAVAILABLE`. |

## 8. Security

- The agent `bran` is a server-side secret (config/env), never exposed via API.
- All endpoints require an authenticated Keycloak user; ceremonies and links are owner-scoped; target authorization runs at ceremony creation **and** at attest **and** inside the locked publish transaction (existing checks).
- Credential acceptance requires full chain validation to configured trust roots; issuee must equal the linked AID; schema allowlisted (§4.3). Schema allowlisting alone is *not* trust.
- Remotesign requests go only to the AID on the caller's own identity link; responses accepted only with full correlation.
- Pseudonymous personal data handling per §4.1; the 1447 metadata remains PII-free by construction (existing serialiser).
- Pasted OOBI URLs and tx hashes are untrusted input: validated; OOBI resolution happens on an egress-restricted KERIA (ops requirement, documented in the module README).
- Resource protection: per-user ceremony limits and step cooldowns (§3.2); AUTH_BEGIN spending is bounded by those limits (one chain publish per identity in the normal case).

## 9. Testing

- **keri_attestation unit tests:** `Cip170MetadataFactory` against vectors derived from the pinned CIP text (not from the PoCs); `CesrChainReducer` round-trip-through-validator on `docs/keri` fixtures; `CredentialChainValidator` negative cases (untrusted root, revoked link, issuee mismatch, wrong schema); ceremony state machine incl. generation CAS, relink invalidation, expiry, consume CAS; notification correlation (wrong sender / unlinked thread / stale generation rejected); services against a mocked `SignifyClient`.
- **Negative/concurrency vectors (from review):** wrapper-SAID `d` rejected by the verifier test; credential-instance `s` rejected; incomplete reduced chain; wrong/stale KEL sequence; freeze under superseded ceremony; late async completion after retry; AUTH_BEGIN rollback; dispatch fail-closed on missing freeze.
- **Integration:** controller slice tests (auth, ownership, problem mapping); freeze → publish → dispatch with mocked IPFS/chain proving frozen bytes are reused, digest assertion trips on tampering, and attested dispatch never falls back to plain. Spring context tests for module enabled/disabled combinations.
- **document_vault:** publish-with-ceremony validation paths (wrong owner, wrong target, not anchored, double consume, fingerprint drift, module disabled).
- **Frontend:** hook tests (polling start/stop, error mapping) and wizard step component tests, including camera-scanner fallback behavior (no camera / permission denied → paste path), following existing document-vault patterns.
- Full multi-module build + test suite green before each review milestone (JDK 21).

## 10. Milestones & reviews

| Milestone | Content | Review |
|---|---|---|
| M1 | This spec | Codex review — done (rev 1: needs-changes → rev 2 addresses all findings) |
| M2 | **Veridian remotesign spike (§4.4 blocker check)**, then `keri_attestation` module (config, entities, services, state machine, REST) + tests | code-reviewer subagent + Codex |
| M3 | Ports impl in blockchain_publisher, freeze, publish/dispatch integration + tests | code-reviewer subagent + Codex |
| M4 | Frontend wizard + API layer + tests | code-reviewer subagent + Codex |

## 11. Documented v1 limitations & future work

- **Verifier-side discovery:** the signer's OOBI is stored server-side, not published; a public AID→OOBI/watcher publication channel (and fork protection per CIP-170's OOBI appendix) is future work. Until then, independent verifiers need the OOBI out-of-band.
- **AUTH_END:** neither emitted nor observed; the platform treats authority as once-established for UX, while real authority validity is the verifier's judgment. Revocation support is future work.
- Migrate the report path (`API3L1TransactionCreator`) onto `keri_attestation` via its own provider.
- Multi-instance-safe orchestration (distributed locks / outbox).
