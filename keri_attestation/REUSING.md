# Reusing `keri_attestation` for a new target type

This module attests **anything**, not just documents. It runs a KERI ceremony that gets a user's
Veridian wallet to anchor a digest of *your* content in its KEL, and publishes the resulting
authority on-chain. What is being attested is entirely up to the calling module.

`DOCUMENT` (in `document_vault`) is currently the only implementation. This guide is how you add
another — the running example is **`REPORT`**, attesting the content of a report.

> **Audience:** humans and coding agents. Follow the checklist in order; each step says what to write
> and what must be true when you are done. Nothing in `keri_attestation` needs to change.

---

## 1. The one thing you implement

`AttestationTargetProvider` is the whole contract. Implement it in **your** module, expose it as a
Spring bean, and `AttestationTargetProviderRegistry` picks it up by `targetType()`.

```java
public interface AttestationTargetProvider {
    String targetType();                                                    // "REPORT"
    Optional<ProblemDetail> authorize(String targetId, String userId);      // may this user attest it?
    Optional<String> organisationId(String targetId);                       // which org owns it?
    Either<ProblemDetail, AttestationDigest> prepareDigest(String targetId, String ceremonyId);
}
```

| Method | When it runs | Contract |
|---|---|---|
| `targetType()` | bean registration | Unique, stable forever. Existing ceremony rows store this string. |
| `authorize()` | ceremony creation **and** the ATTEST step | Return a problem to refuse. Never mutate. |
| `organisationId()` | ceremony creation | Empty refuses creation. Required because AUTH_BEGIN is published by `blockchain_publisher`'s **organisation-scoped** dispatcher. |
| `prepareDigest()` | the ATTEST step | Freeze what is being attested, return its digest. **Must be idempotent per `(targetId, ceremonyId)`.** |

### `prepareDigest` is the interesting one

It has two jobs:

1. **Compute a digest** the wallet will anchor.
2. **Freeze** exactly what that digest covers, so publish-time can prove the content has not changed
   since the user attested it.

Build the digest from a **commitment map** — a canonical CBOR map of the fields that identify your
content — and take its CESR Blake3-256 digest with `Cip170MetadataFactory.digestOf(map)`.

Two hard rules, both learned the expensive way:

- **Only include fields you can compute offline.** The ceremony runs in the API tier, which has no
  IPFS credentials and no chain access. `DOCUMENT` originally tried to freeze a finished on-chain
  manifest and could not, because it needs an IPFS CID and a chain tip. See
  `DocumentAttestationCommitment` for the shape that works.
- **Key insertion order is the wire format.** The digest is taken over serialised CBOR. Reordering
  fields invalidates every existing attestation. Append only, and bump your version constant.

---

## 2. Checklist for a new target type

Using `REPORT` as the example. `document_vault` is the reference implementation for every step.

### Step 1 — Commitment

Create `ReportAttestationCommitment` in `blockchain_common/service_assistance` (it must be reachable
from both tiers).

- A `VERSION` and `TYPE` constant.
- One static `toMetadataMap(...)` building an insertion-ordered `MetadataMap`.
- Include everything that identifies the report's content; nothing that needs a network call.
- Copy the ordering warning from `DocumentAttestationCommitment` into its javadoc.

### Step 2 — Freeze store

A table and entity in **your** module (not in `keri_attestation`, and not in `blockchain_publisher`).

- Key: `(target_id, ceremony_id)`, unique.
- Columns: the digest, plus whatever you re-check at publish time (`DOCUMENT` stores
  `envelope_sha256`), plus `created_at`.
- Immutable: re-attestation inserts a new row under a new ceremony id. No `updated_at`.
- No FK to the target — a freeze may outlive it and is purged by age.

### Step 3 — Provider

`ReportAttestationTargetProvider implements AttestationTargetProvider`.

- `authorize()` — reuse your existing read-and-authorise path. `DOCUMENT` calls
  `VaultDocumentService.loadForAttestation`, which checks existence, org membership and that the
  target is still in an attestable state.
- `organisationId()` — the target's organisation.
- `prepareDigest()` — return an existing freeze if present (idempotency), otherwise build the
  commitment, save the freeze, return `new AttestationDigest(digestQb64, metadataLabel)`. Handle the
  unique-constraint race by re-reading the winner; see `DocumentAttestationTargetProvider.saveFreeze`.

### Step 4 — Freshness guard

`AttestationFreezeGuard` (in `document_vault` today — lift it to a shared spot if you need it too, or
write the equivalent for your module).

At publish time, before consuming the ceremony:

1. The freeze exists.
2. It is younger than `lob.keri-attestation.freeze-max-age`.
3. Recomputing the commitment over the **current** content still matches the frozen value.

### Step 5 — Consume the ceremony

At the point your module does its irreversible thing (publish, anchor, sign off):

```java
Either<ProblemDetail, ConsumedAttestation> consumed =
        attestationConsumptionApi.validateAndConsume(ceremonyId, "REPORT", reportId, userId);
```

`AttestationConsumptionApi` is the **only** interface other modules get. It checks ownership, target
match, state, expiry and that the identity has not been relinked, then compare-and-sets the ceremony
to `CONSUMED`. Run your freshness guard **before** this — a stale freeze must not burn the ceremony.

Order matters, and `VaultDocumentService.publish` is the reference: row-lock the target, authorise,
freshness guard, consume, *then* mutate.

### Step 6 — Module wiring

- `build.gradle.kts`: `implementation(project(":keri_attestation"))`.
- Register your provider bean. If it should only exist when attestation is on, gate the
  configuration class on `lob.keri-attestation.enabled` — and gate the **class**, not just the bean
  method. See §4.

### Step 7 — Config and tests

- Nothing new in `application.yml`: `lob.keri-attestation.*` is shared.
- Test the provider directly (authorize / organisationId / prepareDigest idempotency).
- Test that your publish path fails closed when the ceremony or freeze is missing, stale or drifted.

---

## 3. What you get for free

Implement the provider and the rest of the ceremony already works:

- OOBI resolve, credential presentation (IPEX), credential-chain validation.
- **AUTH_BEGIN publication.** `keri_attestation` owns no wallet: it emits `AuthBeginPublishCommand`,
  `blockchain_publisher` builds and submits the CIP-170 transaction through its normal dispatcher,
  and the resulting `LedgerUpdatedEvent` completes the ceremony step. You write none of this.
- The remotesign round trip with the wallet, and KEL seal verification.
- Retry, per-step cooldown, TTL expiry, stale-step sweeps, relink invalidation.
- The whole `/api/v1/keri-attestation` REST surface — no new endpoints needed.

---

## 4. Traps

Each of these has already cost someone a debugging session.

**Module gates compose by AND.** A bean method carrying the right `@ConditionalOnProperty` still
never registers if its enclosing configuration class sits in a package that is not scanned. The
`DOCUMENT` provider lived in `blockchain_publisher` and silently did not exist on the API pod, where
that module is disabled — every ceremony failed with `422 TARGET_MISMATCH`. **Put your provider in a
module that is actually enabled wherever ceremonies run.**

**The API tier has no chain and no IPFS.** No wallet, no submitter, no reader. Anything needing the
chain goes through `blockchain_publisher` via an event. Do not add a submitter port.

**`digestQb64` ≠ the on-chain `170.d`.** The wallet anchors the SAID of the whole saidified remotesign
payload, not your raw digest. `ConsumedAttestation.payloadSaid()` is what goes on-chain;
`metadataDigest` is for freeze matching only. Do not conflate them.

**Freeze before consume, never after.** `validateAndConsume` is a one-way compare-and-set. A stale
freeze reaching it burns the ceremony and forces the user to start over.

**Ceremonies outlive their targets.** There is deliberately no FK from a ceremony to its target. Your
`authorize()` must handle a target that has since been deleted.

**Attestation is optional.** A caller without a ceremony id must still get the plain, unattested path.
Gate on the ceremony id being present, not on the module being enabled.

---

## 5. Reference implementation

| Concern | File |
|---|---|
| Commitment | `blockchain_common/.../service_assistance/DocumentAttestationCommitment.java` |
| Provider | `document_vault/.../service/DocumentAttestationTargetProvider.java` |
| Freeze entity | `document_vault/.../domain/entity/DocumentAttestationFreezeEntity.java` |
| Freshness guard | `document_vault/.../service/DocumentAttestationFreezeGuard.java` |
| Consumption | `document_vault/.../service/VaultDocumentService.java` (`publish`, `consumeAttestation`) |
| Wiring | `document_vault/.../config/DocumentVaultAttestationConfig.java` |
| AUTH_BEGIN publication | `blockchain_publisher/.../service/publish/module/authbegin/` |
