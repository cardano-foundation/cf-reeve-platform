# TARGET_MISMATCH: no DOCUMENT attestation provider in the api service

**Date:** 2026-07-28
**Status:** diagnosed, **not implemented** — fix option deliberately left open
**Repos:** `cf-reeve-platform` (branch `feat/document-module`), `cf-reeve-application` (deployment)

---

## 1. Symptom

In the decentralized docker-compose deployment at `cf-reeve-application`, starting an attestation
ceremony fails:

```http
POST /api/v1/keri-attestation/ceremonies
```

```json
{
  "type": "about:blank",
  "title": "TARGET_MISMATCH",
  "status": 422,
  "detail": "No provider for target type DOCUMENT.",
  "instance": "/api/v1/keri-attestation/ceremonies"
}
```

The monolith is unaffected. Only the split deployment fails, and it fails for every DOCUMENT
attestation — the feature is entirely unavailable there.

---

## 2. Root cause

`DocumentAttestationTargetProvider` is the **only** implementation of `AttestationTargetProvider` in
the repo, and it is registered as a `@Bean` inside `blockchain_publisher`'s
`TransactionSubmissionConfig`. On the `api` service `LOB_BLOCKCHAIN_PUBLISHER_ENABLED=false`, so
`BlockchainPublisherModuleConfig`'s `@ConditionalOnProperty` never matches and its package is never
component-scanned. `AttestationTargetProviderRegistry` therefore receives an **empty**
`List<AttestationTargetProvider>`, `forType("DOCUMENT")` returns empty, and `CeremonyService.create`
returns `TARGET_MISMATCH`.

The bean's own condition — `lob.keri-attestation.enabled` **and** `lob.document_vault.enabled`, both
true on `api` — is satisfied. It is simply never evaluated, because the class that declares it is not
scanned. The gate that actually decides is the enclosing module's, not the bean's.

### Evidence

| Fact | Location |
|---|---|
| Registry takes a plain `List<AttestationTargetProvider>`; empty list is not itself an error | `keri_attestation/.../service/AttestationTargetProviderRegistry.java:24-30` |
| `create()` raises `TARGET_MISMATCH` when `forType` is empty | `keri_attestation/.../service/CeremonyService.java:108-111` |
| `unprocessable` maps to HTTP 422 | `keri_attestation/.../KeriAttestationProblems.java:89-91` |
| The sole provider, no stereotype annotation, `targetType()` returns `"DOCUMENT"` | `blockchain_publisher/.../service/keri/DocumentAttestationTargetProvider.java:85-87` |
| Registered only here, inside a `blockchain_publisher` config class | `blockchain_publisher/.../config/TransactionSubmissionConfig.java:226-266` |
| Module gate that never fires on `api` | `blockchain_publisher/.../config/BlockchainPublisherModuleConfig.java:7-9` |
| Single entrypoint scans only `...app.config` and `...app.kafka`, so module configs are the only switch | `cf-reeve-application/cf-application/.../LobServiceApp.java:41-42` |

Service flags in `cf-reeve-application/docker-compose.yml`:

| Flag | `api` | `publisher` |
|---|---|---|
| `LOB_KERI_ATTESTATION_ENABLED` | `true` | unset → false |
| `LOB_DOCUMENT_VAULT_ENABLED` | `true` | unset → false |
| `LOB_BLOCKCHAIN_PUBLISHER_ENABLED` | **`false`** | `true` |
| `LOB_BLOCKCHAIN_READER_ENABLED` | `false` | unset → false |

`blockchain_publisher` **is** on the `api` classpath (one build artifact), so this is purely a bean
registration and component-scan problem, not a packaging one.

### This was anticipated

`docs/keri-document-flow.md:37` already names the `blockchain_publisher → document_vault` edge as
"the edge that must be removed", and step 4 of
`docs/superpowers/specs/2026-07-24-decentralized-document-keri-attestation-design.md` specifies
relocating the DOCUMENT attestation provider into `document_vault`. The **packaging** half of that
plan has landed in `cf-reeve-application` (`cf-application/build.gradle.kts:33-37` declares
`document_vault` and `blockchain_reader` explicitly); the **code move** in `cf-reeve-platform` has
not. The 422 is that gap surfacing.

---

## 3. Why the obvious fix does not compile

`document_vault` cannot simply take the provider, because `blockchain_publisher` **already depends on
`document_vault`** (`blockchain_publisher/build.gradle.kts:9`). Adding the reverse edge is a Gradle
cycle.

What the provider's constructor needs, against what `document_vault` has today:

| Dependency | Lives in | `document_vault` has it? |
|---|---|---|
| `VaultDocumentService` | `document_vault` | yes — native |
| `DocumentIpfsSerialiser`, `DocumentMetadataSerialiser`, `Cip170MetadataFactory` | `blockchain_common` | yes — existing dependency |
| `KeycloakSecurityHelper`, `OrganisationPublicApiIF` | `support`, `organisation` | yes — existing dependencies |
| `BlockchainReaderPublicApiIF` (chain tip) | `blockchain_reader` | **no** — but addable without a cycle |
| `IpfsPublisher` (+ `BlockfrostPublisher` / `IpfsNodePublisher`) | `blockchain_publisher.service.ipfs` | **no** — cycle |
| Freeze store (`DocumentAttestationFreezeEntity`/`Repository` + its Flyway migration) | `blockchain_publisher` | **no** — cycle |

So the last two must physically move (to `document_vault` or `blockchain_common`) before the provider
can follow. That is what makes this more than a one-line fix.

---

## 4. Options

### Option A — Full relocation (the existing spec's step 4)

Move `DocumentAttestationTargetProvider`, the freeze store (entity, repository, and its Flyway
migration), and the IPFS publisher port out of `blockchain_publisher`; add
`document_vault → blockchain_reader`; register the provider from a `document_vault` config gated only
on `lob.keri-attestation.enabled` + `lob.document_vault.enabled`; set
`LOB_BLOCKCHAIN_READER_ENABLED=true` on `api` (or make the chain-tip read tolerate absence).

Removes the `blockchain_publisher → document_vault` edge permanently and completes the intended
architecture. Largest change: several modules, a migration relocation, and deployment config.

### Option B — Move only the `@Bean` registration

Leave every class where it is; move just the `@Bean` method into an always-scanned config package
(`org.cardanofoundation.lob.app.config`), gated on keri-attestation + document_vault.

Much smaller, but requires the freeze repository/entity, the IPFS publisher and `blockchain_reader` to
be active on `api` — i.e. re-enabling parts of `blockchain_publisher` there. The `api` pod still holds
no wallet mnemonic, so it stays hardened in the sense that matters, but the module boundary stays
tangled and step 4 remains outstanding. Reasonable as a stopgap; a poor destination.

**Recommendation:** A, because the deployment side already assumes it and B leaves the same work to do
later with an extra migration of its own. But B is defensible if the split deployment needs to work
before the refactor can be scheduled.

---

## 5. Verification, whichever option is chosen

`document_vault/src/test/.../config/DocumentVaultWithKeriNoPublisherContextTest.java:70-107` already
proves this exact module combination (`document_vault` on, `keri_attestation` on,
`blockchain_publisher` absent) starts cleanly — but only for the `AttestationFreezeGuard` seam. **No
test covers `AttestationTargetProviderRegistry` in that combination**, which is precisely where this
bug lives, and why it reached a running deployment.

Any fix must add a context test asserting that with those three flags set as `api` sets them, the
registry resolves a provider for `DOCUMENT`. Without it, the same regression can recur silently — the
registry treats "no providers at all" as a normal state.

---

## 6. Relationship to the recipient key-hash work

None functionally. This spec was written while implementing
`2026-07-28-recipient-key-hash-filtering-design.md`; the two touch different code, and the key-hash
feature works in the monolith and in the Indexer regardless of how this is resolved. It is recorded
separately so it can be planned on its own.
