# Decentralized Document KERI Attestation — Design

**Status:** Draft for review
**Date:** 2026-07-24
**Scope:** The DOCUMENT KERI-attestation flow only (reports & transactions follow later, via the same pattern — strangler).

## 1. Goal

Make the document KERI-attestation flow **decentralized and multi-pod safe**: three independently-deployable services communicating only over Kafka, each owning its own database, with **no cross-service shared-DB assumption** and **no "same pod handles the next step" assumption**. As part of this, remove the document-specific attestation and serialisation logic from `blockchain_publisher` (it "has too much document logic that isn't reusable") **without duplicating** the existing metadata-serialisation machinery.

## 2. Decisions locked in (from brainstorming)

| # | Decision | Rationale |
|---|---|---|
| D1 | **Do not assume a shared DB across bounded contexts.** Services coordinate via Kafka; a shared DB may exist but must not be relied on. | User constraint. |
| D2 | **Full service split now:** `document-vault-svc`, `keri-attest-svc`, `publisher-svc`, each with its own DB + versioned Kafka contracts. | User choice. |
| D3 | **Orchestrated saga**, with `keri-attest-svc`'s ceremony state machine as the orchestrator; it emits `DocumentAttested` as a *fact*, and `document-vault-svc` chooses to publish. | The ceremony is already a stateful, user-in-the-loop state machine; orchestration owns the attest-before-publish invariant and cross-pod recovery, and keeps the wizard's "current step" authoritative. |
| D4 | **Owning services own their metadata, but reuse the existing shared serialiser** — the CIP-1447 manifest serialisation is extracted into a shared library; it is **not** duplicated per service nor left as a document-specific class in the publisher. | User correction: "the specific services owns the metadata is good, but don't duplicate the code in the publisher — there are already the metadata serialisers, use these." |
| D5 | **`publisher-svc` becomes a generic Cardano/IPFS gateway** — it knows nothing about documents, reports, or attestations; it pins bytes and submits a metadata tx it is handed. | Removes the per-type modules that made it non-reusable. |

## 3. Current state (baseline)

- **Deployment:** one Spring Modulith app; cross-module signalling is **in-process Spring `ApplicationEvent`s** (no Kafka anywhere today — greenfield).
- **Publish path** already uses a **persist-then-poll queue**: a `PublishableEntity` is written, then `CardanoPublishingJob` polls and dispatches via `CardanoDispatcher` → per-type `*Publishable` (`DocumentPublishable`, `ReportPublishable`, `TransactionPublishable`, `SpendingEventPublishable`). This is a transactional-outbox *precursor* and maps cleanly onto Kafka (queue→topic, poller→consumer).
- **Attestation SPI** (in-process, `keri_attestation`): `AttestationTargetProvider` (`targetType`, `authorize`, `prepareDigest`) implemented **once per target type** — the DOCUMENT impl is `blockchain_publisher/service/keri/DocumentAttestationTargetProvider`; and `AttestationConsumptionApi` (`validateAndConsume` → CAS ceremony to `CONSUMED`; `findTerminalNonConsumedCeremonyIds`; dispatch-time re-lookup).
- **Ceremony:** `CeremonyService` is a row-locked `(state, attemptGeneration)` CAS state machine (`CREATED → … → ATTEST_ANCHORED → CONSUMED`, with `FAILED`/`EXPIRED`). It runs **synchronously in-thread** and correlates wallet replies in-thread via `KeriNotificationCorrelator` — the two places that bake in a same-pod assumption.
- **Metadata serialisation:** `DocumentMetadataSerialiser`, `API3MetadataSerialiser` (report), `API1MetadataSerialiser` (tx), `SpendingEventMetadataSerialiser` all emit the same CIP-1447 manifest shape (`metadata` / `org` / `type` / `data`), with `DocumentMetadataSerialiser` copying the `metadata`/`org` sections **verbatim** from the report serialiser (acknowledged duplication). `DocumentIpfsSerialiser` emits the IPFS envelope (already aligned with the indexer).
- **Data owners today:** `document_vault` → `VaultDocumentEntity`, `DocumentSlot`, vault keys, addressbook, cards. `keri_attestation` → `KeriAttestationCeremonyEntity`, `KeriIdentityLinkEntity`. `blockchain_publisher` → its own `DocumentEntity` copy, the publishable queue, document-attestation freeze rows, the organiser wallet.

## 4. Target architecture

```
                         ┌──────────────────────────────────────────────┐
   shared libraries →    │  lob-attestation-contracts  (event/command    │
   (versioned, no state) │      schemas + keys, JSON-Schema/Avro)        │
                         │  lob-l1-metadata            (CIP-1447 manifest │
                         │      serialiser: type + data → metadata map)  │
                         └──────────────────────────────────────────────┘
        ┌───────────────────┐        ┌────────────────────┐        ┌────────────────────┐
        │ document-vault-svc │  Kafka │  keri-attest-svc   │  Kafka │   publisher-svc    │
        │  (owns documents)  │◀──────▶│  (orchestrator +   │◀──────▶│ (generic Cardano/  │
        │                    │        │   ceremony + KERIA)│        │  IPFS gateway)     │
        └─────────┬──────────┘        └─────────┬──────────┘        └─────────┬──────────┘
                  │ own DB                        │ own DB                     │ own DB
             documents, slots,              ceremonies, identity          tx queue, confirmations
             cards, keys                    links, KERIA agent            (no domain knowledge)
```

### 4.1 Shared libraries (stateless, versioned — reuse, not duplication)

- **`lob-l1-metadata`** — extracts today's CIP-1447 manifest serialisation into ONE place: `L1ManifestSerialiser.serialise(type, dataMap, org, creationSlot, timestamp) → MetadataMap`, producing the `metadata`/`org`/`type`/`data` envelope. The **owning service** supplies only the type string and the type-specific `data` map (e.g. document: `id`, `ipfs_cid`, `content_hash`, `plaintext_hash`, `envelope_version`, `slot_count`). This is the direct answer to D4: `DocumentMetadataSerialiser`/`API3MetadataSerialiser`/… collapse into this lib; the publisher holds no copy. `DocumentIpfsSerialiser` moves here too (it is already the shared envelope contract with the indexer).
- **`lob-attestation-contracts`** — the versioned message payloads + topic/partition-key conventions (below). No logic, just schemas + a schema-registry-registered version.

### 4.2 Service responsibilities

**`document-vault-svc`** — owns document semantics + data.
- Gains from the publisher: the DOCUMENT `AttestationTargetProvider` behaviour (`authorize` + `prepareDigest`/freeze — it owns the document, therefore the digest), and the document *content* decisions.
- Uses `lob-l1-metadata` to build the document manifest `data` section; uses `DocumentIpfsSerialiser` (now in `lob-l1-metadata`) for the envelope.
- Requests attestation, reacts to `DocumentAttested`, then commands `publisher-svc` to pin + submit.

**`keri-attest-svc`** — orchestrator.
- Owns the ceremony state machine + identity links + the KERIA agent + `Cip170MetadataFactory` (the ATTEST metadata *content*, label 170).
- Drives the saga via messages: asks `document-vault-svc` for the frozen digest, runs the user-interactive OOBI/present/attest steps against KERIA, commands `publisher-svc` to submit the ATTEST tx, verifies the KEL anchor, emits `DocumentAttested`.
- Never learns what a document *is* — only `targetType=DOCUMENT`, `targetId`, and a digest.

**`publisher-svc`** — generic gateway (no domain knowledge).
- Capabilities: `PinToIpfs{bytes} → IpfsPinned{cid}`; `SubmitL1Tx{label, metadataMap, correlationId} → L1TxSubmitted{txHash} → L1TxConfirmed{txHash, block}`.
- Keeps: organiser wallet + `OrganiserWalletMetadataTxSubmitter`, `CardanoDispatcher`/`CardanoStatusWatcher`/`CardanoWatchDogJob`, the persist-then-poll queue (now generic over "a metadata tx to submit").
- Loses: every `publish/module/document/*` class, the two `DocumentAttestation*` guards, and its private `DocumentEntity` copy.

> `org` data for the manifest (`OrganisationPublicApi`) is a cross-cutting read dependency. `document-vault-svc` obtains it (a replicated read-model fed by the organisation context's events, or a request/reply topic) and passes the resolved `org` fields into `lob-l1-metadata`. The publisher never resolves org data.

## 5. Message contracts

**Conventions:** every topic is **partitioned by the saga key** so all messages for one saga land on one partition (ordered) while different sagas parallelize across pods:
- attestation-lifecycle topics → key = `ceremonyId`
- document-publish topics → key = `documentId`

Every message carries `messageId` (dedup), `correlationId`/`causationId` (tracing), and a `schemaVersion`. **Commands** are addressed to one service (imperative, expect a reply event); **events** are facts anyone may consume.

| Topic | Kind | Producer → Consumer | Payload (v1) |
|---|---|---|---|
| `attestation.requested` | event | document-vault → keri-attest | `{docId, targetType=DOCUMENT, orgId, userId, requestedAt}` |
| `attestation.prepare-digest` | command | keri-attest → document-vault | `{ceremonyId, targetId}` |
| `attestation.digest-prepared` | event | document-vault → keri-attest | `{ceremonyId, targetId, digestQb64}` (idempotent per `(targetId, ceremonyId)`) |
| `l1.submit-tx` | command | keri-attest / document-vault → publisher | `{correlationId, label, metadataMap}` |
| `l1.tx-submitted` | event | publisher → * | `{correlationId, txHash}` |
| `l1.tx-confirmed` | event | publisher → * | `{correlationId, txHash, block, slot}` |
| `ipfs.pin` | command | document-vault → publisher | `{correlationId, bytes}` (or a pre-signed blob ref) |
| `ipfs.pinned` | event | publisher → document-vault | `{correlationId, cid}` |
| `attestation.attested` | event | keri-attest → document-vault (+ any verifier) | `{docId, aid, digestQb64, txHash, kelSequence}` |
| `attestation.failed` | event | keri-attest → document-vault | `{docId, ceremonyId, reason}` (release the freeze) |
| `*.DLQ` | — | per topic | poison-message dead-letter |

**Versioning:** payloads live in `lob-attestation-contracts`, registered in a schema registry; consumers are forward-compatible (ignore unknown fields), producers only add optional fields within a major version. A breaking change publishes `…-v2` alongside `…-v1` until all consumers migrate.

The old in-process SPI maps onto messages 1:1: `AttestationTargetProvider.prepareDigest` → `prepare-digest`/`digest-prepared`; `authorize` → checked locally by document-vault before it emits `attestation.requested` (and re-checked at the attest step via a lightweight `authorize` command if needed); `AttestationConsumptionApi.validateAndConsume` → the `ATTEST_ANCHORED → CONSUMED` transition inside keri-attest, surfaced as the `attestation.attested` fact.

## 6. The orchestrated saga (end to end)

```mermaid
sequenceDiagram
    actor U as User (wizard)
    participant DV as document-vault-svc
    participant KA as keri-attest-svc (orchestrator)
    participant KRA as KERIA / wallet
    participant PB as publisher-svc
    participant L1 as Cardano + IPFS

    U->>DV: request attestation for document D (REST)
    DV->>DV: authorize(user, D); mark D "attestation pending"
    DV-->>KA: attestation.requested{docId=D} (key=D)
    KA->>KA: create ceremony (CREATED)
    KA-->>DV: attestation.prepare-digest{ceremonyId} (key=ceremonyId)
    DV->>DV: freeze D; digest via lob-l1-metadata
    DV-->>KA: attestation.digest-prepared{ceremonyId, digest}
    Note over U,KRA: user-interactive steps, driven by the wizard (REST -> commands in)
    U->>KA: pair / present-credential / attest (REST)
    KA->>KRA: OOBI resolve / IPEX present / remotesign
    KRA-->>KA: async notifications (via KERIA-notification bridge -> internal events, pod-agnostic)
    KA-->>PB: l1.submit-tx{label=170, ATTEST metadata} (key=ceremonyId)
    PB->>L1: submit ATTEST tx
    L1-->>PB: confirmed
    PB-->>KA: l1.tx-confirmed{txHash}
    KA->>KRA: re-resolve OOBI; verify the anchor hits the KEL
    KA->>KA: ATTEST_ANCHORED -> CONSUMED (CAS)
    KA-->>DV: attestation.attested{docId, aid, digest, txHash, kelSeq}
    DV->>DV: build manifest (lob-l1-metadata) + envelope
    DV-->>PB: ipfs.pin{envelope}
    PB->>L1: pin
    L1-->>PB: cid
    PB-->>DV: ipfs.pinned{cid}
    DV-->>PB: l1.submit-tx{label=1447, manifest incl. cid + attestation ref} (key=docId)
    PB->>L1: submit publish tx
    L1-->>PB: confirmed
    PB-->>DV: l1.tx-confirmed{txHash}
    DV->>U: document published + attested
```

The **attest-before-publish invariant** is owned by the orchestrator: `document-vault-svc` publishes only on the `attestation.attested` fact, which keri-attest emits only after the KEL anchor verifies and the ceremony CASes to `CONSUMED`. The user-interactive steps keep their existing state-machine semantics; only their *inputs* (REST → commands) and *outputs* (in-thread waits → the notification bridge) change.

## 7. The KERIA-notification bridge (the hard part)

Today `KeriNotificationCorrelator` blocks in the request thread waiting for the wallet's KERIA notification — assuming the same pod that sent the request receives the reply. Replace it with:

- A **notification poller** (any/every keri-attest-svc pod runs it) that reads the KERIA agent's notification stream and, for each notification, **resolves the owning ceremony** (by route + embedded SAID/correlation, exactly as the correlator does today) and emits an **internal `wallet.reply` event keyed by `ceremonyId`**.
- The ceremony's step handler consumes `wallet.reply` for its `ceremonyId`; because the topic is partitioned by `ceremonyId` and the handler is guarded by the `(state, attemptGeneration)` CAS, **whichever pod owns that partition advances the ceremony** — the sending pod need not be the receiving pod.
- Notifications that don't yet match a known ceremony (spontaneous IPEX grants — the dual-path case) are parked and re-evaluated, never dropped, mirroring today's exclude-snapshot / spontaneous-grant handling.
- **Idempotency:** KERIA `markAndDelete` + the ceremony CAS make re-delivery a no-op.

This is the single component that turns the synchronous ceremony into a pod-agnostic one; it is where most of the risk and test effort lives.

## 8. Reliability patterns

- **Transactional outbox** in every service: a state change and its outgoing message are written in one **local** DB transaction (an `outbox` table); a relay (Debezium CDC or a poller — the publisher's existing persist-then-poll queue is exactly this) ships rows to Kafka at-least-once. No distributed transactions, survives "not the same pod."
- **Idempotent consumers everywhere** (Kafka is at-least-once): dedup on `messageId` (a processed-messages table or the natural CAS). keri-attest's `(state, attemptGeneration)` CAS already gives this for ceremony steps; document-vault and publisher add a dedup table.
- **Exactly-once *effects*, not exactly-once delivery.** The two effects that must never double-fire — submitting an L1 tx and consuming an attestation — are guarded by keys: the ATTEST tx by `ceremonyId` (+ the existing "tx-only resume: anchor verified, txHash null → resubmit" logic), and consume-once by the `ATTEST_ANCHORED → CONSUMED` CAS.
- **Ordering** via partition key (`ceremonyId` / `documentId`); different sagas scale out across partitions/pods.

## 9. Error handling & recovery

- **Saga timeouts owned by the orchestrator:** each waiting step has a deadline (wallet never replies, user abandons); on expiry keri-attest `failStep`s and emits `attestation.failed`, which document-vault consumes to **release the freeze** and return the document to its pre-attestation state. `CeremonyCleanupJob` continues to reap terminal ceremonies.
- **Post-anchor tx failure** stays recoverable exactly as today: anchor is durably persisted before submission, so a submission failure leaves the ceremony resumable (tx-only resume) rather than `FAILED`.
- **Poison messages** → per-topic DLQ after N redeliveries, with an alert; never block the partition indefinitely.
- **Compensation is bounded:** the only compensating action is "release the freeze / mark the publish not-done." Nothing on-chain is ever rolled back (it can't be) — the design ensures the on-chain ATTEST is only submitted once the ceremony is committed to it, and the document publish only happens after a confirmed attestation.
- **Fail-closed:** a missing/late `attestation.attested` never falls back to an unattested publish; document-vault simply does not publish until the fact arrives (or the saga fails).

## 10. Migration / rollout (strangler, not big-bang)

1. **Extract shared libs first** (`lob-l1-metadata`, `lob-attestation-contracts`) inside the current monorepo — pure refactor, no behaviour change, collapses the duplicated serialisers (D4). Ship & verify against golden manifests.
2. **Introduce Kafka behind the existing SPI**: replace the in-process `AttestationTargetProvider`/`AttestationConsumptionApi` calls and the `LedgerUpdatedEvent` handoff with outbox→topic→consumer, still **within one deployable**. Prove multi-pod safety (run ≥2 pods) — this delivers the "don't assume same pod" property before any split.
3. **Move the DOCUMENT provider + serialisation out of the publisher** into document-vault; make the publisher generic (D5). Reports/transactions keep their in-publisher modules (still work) until migrated the same way.
4. **Split deployables + databases**: carve the three modules into services with their own schemas/migrations and CI/CD. Because steps 1–3 already made every boundary message-based and every store single-owner, this step is largely packaging + topology.
5. **Migrate reports & transactions** onto the generic gateway + shared serialiser (out of scope here; same recipe).

Each step is independently shippable and reversible; the system keeps working throughout.

## 11. Testing strategy

- **Golden-manifest tests** pin the `lob-l1-metadata` output byte-for-byte against the current serialisers before/after extraction (no on-chain shape drift).
- **Contract tests** (e.g. Pact / schema-registry compatibility checks) for every topic payload; CI fails on an incompatible schema change.
- **Consumer idempotency tests**: deliver every message twice (and out of order within allowed bounds) and assert a single effect.
- **Saga tests** with an embedded Kafka: happy path, wallet-timeout → freeze released, post-anchor tx failure → resume, duplicate `attestation.attested` → single publish.
- **Multi-pod test**: two keri-attest instances against one KERIA stream; assert the ceremony advances regardless of which pod receives the wallet reply (the core "not the same pod" guarantee).
- **DLQ tests**: a poison message dead-letters without blocking its partition.

## 12. Open questions / risks

1. **Org data across services** — replicated read-model vs request/reply topic for the manifest `org` section (§4.2 note). Leaning read-model to avoid a synchronous cross-service call on the publish path.
2. **IPFS blob transport** — pass bytes through Kafka (`ipfs.pin`) vs a shared blob store the publisher reads by reference. Large envelopes argue for a reference; needs a decision.
3. **Schema registry choice** (Confluent/Apicurio) and payload format (Avro vs JSON-Schema) — affects tooling and the contracts lib.
4. **KERIA-notification bridge** is the highest-risk component; it must preserve the spontaneous-grant dual-path and exclude-snapshot semantics exactly — heaviest test investment.
5. **Exactly-once tx submission** relies on the existing tx-only-resume + `ceremonyId` keying; verify no window allows a double ATTEST submission across pods.

---

*Next: on approval, this becomes an implementation plan (writing-plans), sequenced per §10 so each step ships independently.*
