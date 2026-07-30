# KERI credential-schema trust registry

Generalises credential verification from a flat, global allowlist to a per-schema trust registry, so
the platform can accept vLEI Legal Entity, Foundation Employee and future schemas — each with its own
trust rules — by configuration alone.

Status: specification, revised after adversarial review. §14 records the disposition of every review
finding. No production code is described as written; every file below is a proposal.

> **Headline outcome of review.** The registry is necessary but **not sufficient**. Three
> authenticity gaps (§5) mean the current pipeline cannot prove what a persisted "verified" badge
> would assert. Those gaps are prerequisites, not follow-ups: §7 (result plumbing) and §8 (badge)
> must not ship before them.

---

## 1. Problem

`keri_attestation` verifies the credential a key card claims at import. It cannot express "different
schemas are trusted differently":

- Configuration is flat and global: `lob.keri-attestation.credential-policy.schema-saids` and
  `.trusted-root-aids` apply to every schema at once.
- Both policy checks in `CredentialChainValidator` are **disabled**, and disabled means
  *unconditional acceptance*, not "empty list = trust any". `validateAncestry` returns
  `Either.right(null)` at the terminal node regardless of `trustedRootAids`, which is threaded
  through only to name it in a WARN (`TODO(policy)`, commit `4813d1a2`).
- Only one trust model is expressible. Root-chaining fits vLEI (leaf → QVI → GLEIF) but cannot
  express Foundation Employee, issued directly with no chain to a root.

Structural validation — CESR parse, issuee match, per-link TEL/revocation, edge-to-issuer chaining,
recursive ancestry — **is** enforced today and must not be weakened (§12.1).

Second gap: **nothing about a verified credential reaches the API.**
`AttestationImportVerifier.verify` returns `Either<ProblemDetail, Void>` and discards success;
`CardImportService.importCard` checks only the `Left`. None of `VaultKeyView`,
`AddressbookEntryView`, `RecipientKeyView` carries attestation data. The badge is unbuildable
without closing this.

## 2. Prior art

`reeve-indexing-example` already solves the trust half and is **not** vLEI-hardcoded. Its design is
ported; its fail-open defaults are deliberately **not** (§9).

| Indexer | Role |
|---|---|
| `config/CredentialSchema.java` | entry: `said`, `name`, `chained`, `trustedRoots`, `trustedIssuers`, `oobis` |
| `config/CredentialSchemaRegistry.java` | index by SAID |
| `service/keri/KeriService#verifyCredentialEntity` | schema gate → leaf lookup → per-model trust |
| `model/response/IdentityAttestationView.java` | API shape |
| `frontend/.../IdentityAttestationBadge/` | schema name primary, SAID fallback, claims in tooltip |

### 2.1 No coupling

No shared module, dependency, runtime call, shared configuration or shared registry instance, in
either direction. The two implementations are independent copies of a design. §11 proposes changes
made *inside the indexer*; none imports from or calls the platform.

## 3. Module ownership

```
keri_attestation   owns  CredentialSchema, CredentialSchemaRegistry, CredentialChainValidator,
                         AttestationImportVerifier, authenticity checks, lob.keri-attestation.* config
document_vault     uses  AttestationImportVerifier via CardImportService; persists the returned
                         result on rows it already owns (§7.2 — flagged for ratification)
frontend           uses  what the document_vault API exposes
```

`document_vault` gains **no** verification logic: it computes nothing and decides nothing. See §7.2
for the open question about whether it may *store* the result at all.

## 4. The registry

### 4.1 Entry

```java
// keri_attestation/.../config/CredentialSchema.java
public record CredentialSchema(
        String said,
        String name,
        TrustModel trustModel,        // REQUIRED enum, not a defaulting boolean (§4.5)
        List<String> trustedRoots,
        List<String> trustedIssuers,
        List<String> oobis) {

    public enum TrustModel { CHAINED, STANDALONE }
}
```

`trustModel` is an enum, not the indexer's `boolean chained`. A boolean silently defaults to `false`
on a mistyped or omitted key, choosing STANDALONE — the model whose trust anchor is the leaf's own
issuer. Silently selecting a trust model is a security defect.

### 4.2 Two trust models

| Model | Rule | Example |
|---|---|---|
| `CHAINED` | walk `e` edges from the leaf to a credential with no further edges; that terminal credential's issuer must be in `trustedRoots` | vLEI → GLEIF |
| `STANDALONE` | the leaf's own issuer (`i`) must be in `trustedIssuers` | Foundation Employee |

Both mandatory. Foundation Employee is the current test schema and is standalone.

### 4.3 Configuration

Replaces `credential-policy.{schema-saids,trusted-root-aids}`:

```yaml
lob:
  keri-attestation:
    credential-schemas:
      - said: ${LOB_KERI_VLEI_SCHEMA_SAID:}
        name: "vLEI Legal Entity"
        trust-model: CHAINED
        trusted-roots:
          - ${LOB_KERI_VLEI_GLEIF_ROOT_AID:}
        trusted-issuers: []
        oobis: ${LOB_KERI_VLEI_OOBIS:}

      - said: "EL9oOWU_7zQn_rD--Xsgi3giCWnFDaNvFMUGTOZx1ARO"
        name: "Foundation Employee"
        trust-model: STANDALONE
        trusted-roots: []
        trusted-issuers:
          - ${LOB_KERI_FOUNDATION_ISSUER_AID:}
        oobis: ${LOB_KERI_FOUNDATION_OOBIS:}
```

### 4.4 Adding a schema is configuration only

No production-code change. A third schema is one list entry:

```yaml
      - said: "EXAMPLE3rdSchemaSaid00000000000000000000000"
        name: "Partner Membership"
        trust-model: STANDALONE
        trusted-roots: []
        trusted-issuers:
          - "EPartnerIssuerAid0000000000000000000000000"
        oobis:
          - "https://partner.example/oobi/EPartnerIssuerAid0000000000000000000000000/agent"
```

**Config-only means no code change; it does not mean hot reload.** The registry is built at startup
and a configuration change requires a restart. Live reload is explicitly out of scope: it would need
an atomic swap, cache invalidation and a concurrency model for in-flight verifications, none of which
this spec defines. §10.2's "no restart" phrasing in the previous revision was wrong and is retracted.

**Adding a schema does not make a ceremony request it.** `KeriCredentialService.sendApply` selects
`schemaSaids.get(0)` — always the first configured SAID. Registry order therefore silently decides
which schema is requested. The IPEX apply must take an explicit schema selector (ceremony parameter
or a registry entry flagged as the ceremony default); relying on list order is not acceptable for a
security-relevant registry.

`schemaBaseUrl` stays on `credential-policy` — it is the credential *schema server* for IPEX apply
and agent-side schema-OOBI resolution, unrelated to trust.

### 4.5 Startup validation — fail fast, atomically

The registry is a security policy; a malformed one must not start. Reject at startup, listing every
problem rather than the first:

| Condition | Action |
|---|---|
| duplicate SAID | reject — trust must not depend on list order |
| blank/absent SAID or `name` | reject |
| absent/unparseable `trust-model` | reject — never default |
| `CHAINED` with empty/blank-only `trustedRoots` | reject (§9) |
| `STANDALONE` with empty/blank-only `trustedIssuers` | reject (§9) |
| non-empty list for the *inactive* model | reject — signals a mis-set trust model |
| blank entry inside a trust list | reject — an unresolved `${VAR:}` must never silently empty a list |
| malformed OOBI URL | reject |

The indexer's first-wins-and-warn and blank-filtering behaviour is deliberately **not** ported: it
converts a mis-templated environment variable into a weaker policy.

## 5. Authenticity prerequisites

Adversarial review found three gaps that make a persisted, user-visible `verified` flag
unsupportable on today's pipeline. They are prerequisites for §7–§8.

### 5.1 Card-supplied CESR is not cryptographically authenticated

`AttestationImportVerifier` passes `claim.credentialCesr()` — bytes from an untrusted card file —
straight to `CredentialChainValidator`. That validator states it does **not** verify KEL/TEL
signatures, key state or witness receipts, on the stated basis that KERIA already admitted the
stream. **That premise does not hold for card-carried CESR**, which never passed through KERIA's
admission path. Trust decisions are then made on issuer AIDs, schema SAIDs, TEL events and edges
supplied by the attacker.

**Required:** before any schema or trust decision, either (a) admit the stream into KERIA and re-fetch
the credential by SAID from its store, or (b) specify independent verification of SAIDs, KEL/TEL
signatures, key state and receipts. OOBI resolution is discovery, not authentication.

### 5.2 The attestation being verified is the wrong artifact entirely

Verification compares on-chain CIP-170 label-170 metadata `t`, `i` and `d` for the card's `txHash`.
**The indexer publishes no such transaction, and its cards no longer carry a `txHash`** — see §6.4.
The attestation is the attesting wallet's own KEL interaction-event seal. Today's check therefore
fails on every genuine attested card and, where an attacker supplies a card that *does* satisfy it,
passes on plaintext metadata anyone can write: publish `{t: ATTEST, i: <victim aid>,
d: <forged card digest>}` and it passes, because no KEL seal is checked.

The value the wallet seals is not the card digest but the **payload SAID** over the saidified
remotesign request (§6.4.2). This module already states that rule for documents —
`AttestationDigest`: *"`170.d` from `ConsumedAttestation#payloadSaid()`, never from `digestQb64`"* —
and the card path violates it.

**Required:** replace the on-chain read with KEL-seal verification against the claimed AID (§6.5).

That closes authorship. It does **not** bind `credentialSaid` / `schemaSaid`: the seal proves *AID X
attested card digest D*, while the CESR chain independently proves *credential C of schema S was
issued to X* (the validator already pins the issuee to the claimed AID). What stays unbound is which
of X's credentials was presented during the ceremony. That is a materially narrower gap than an
unbound attestation, and closing it needs the indexer to carry `credentialSaid`/`schemaSaid` inside
the signed payload — a wire-format change to a live wallet-tested contract (§6.4.2), so it is
recorded here and deferred, not silently assumed.

### 5.3 Stale CESR can hide revocation at import

`checkTel` asks only whether the *supplied* stream contains `iss` and lacks `rev`. An older card and
CESR captured before revocation still validates, because the later `rev` is simply absent from the
replayed stream. §13.3 covers revocation *after* import; this is revocation *before* it, undetected.

**Required:** authoritative current TEL state at import, with a defined freshness bound. Inability to
establish current state fails closed. Covers revoked leaf, intermediate and terminal credentials.

### 5.4 What the registry does and does not protect

Cards are unsigned and permissionless by design, and attestation is optional:
`verifyAttestationIfPresent` returns success immediately when the block is absent. **An attacker can
therefore strip the attestation block from an untrusted card and import it through trust-on-first-use.**

The registry governs **the verified assertion, not admission**. Trust-gated *admission* would require
an authenticated "attestation expected" signal and is out of scope. The badge must not imply that an
unbadged key was rejected — it was never checked.

## 6. Verification

### 6.1 Algorithm

1. **Authenticity (§5)** — must pass before anything below is meaningful.
2. **Structural validation — unchanged** (§12.1).
3. **Schema gate** — the claimed schema SAID must resolve in the registry. Unknown ⇒ reject.
4. **Unique leaf identification** (§6.2).
5. **Trust check** by the schema's model (§4.2).

### 6.2 Exactly one leaf, identified by credential SAID

The leaf must be the credential matching **all three** of: expected credential SAID (the card carries
`credentialSaid`), expected issuee AID (`a.i`), and the gated schema SAID (`s`). **Exactly one** must
match; zero or several ⇒ reject.

Two distinct defects motivate this:

- **Schema confusion.** Matching on issuee alone lets a caller name a lenient schema while embedding
  an ACDC of a *different* schema with a matching issuee, applying the lenient policy to a credential
  it does not govern.
- **Ambiguity.** Today `findByIssuee` returns the *first* insertion-order match, and ACDCs are indexed
  by their claimed `d` with later duplicates overwriting earlier ones. With several same-issuee,
  same-schema credentials, which one is trust-checked depends on stream order — attacker-controlled.

Also reject: duplicate `d` in one stream, non-string or missing identifiers, malformed or non-map
`e`, unresolved edges, and edge-schema mismatch. A missing `e` is currently treated as terminal,
which is how a chained credential's root is found — that must remain a *structural* determination,
not an attacker-selectable shortcut.

### 6.3 Signature and callers

```java
Either<ProblemDetail, ValidatedCredential> validate(String fullCesr,
                                                    String expectedIssueeAid,
                                                    String expectedCredentialSaid,
                                                    String claimedSchemaSaid);
```

There are **three** production callers, not two:

| Caller | Source of the expected identifiers |
|---|---|
| `AttestationImportVerifier:95` | the card's `credentialSaid` / `schemaSaid`, bound per §5.2 |
| `KeriAuthBeginService:166` | the identity link's `credentialSaid` / `credentialSchemaSaid` |
| `KeriCredentialService:356` | the credential just admitted via IPEX; SAID from the grant |

Every caller must supply identifiers bound to something authenticated. A caller that forwards an
untrusted field unbound re-opens §6.2.

### 6.4 The card-attestation contract with the indexer

The indexer issues and attests the cards the platform imports. The two sides were verified
field-by-field against the source; this records the contract as it actually is. Both sides are read
here as *documentation of an existing wire format* — no code is shared, imported or called across the
two systems (§2.1).

#### 6.4.1 The card digest formula — matches, keep it

`CardAttestationDigestFactory` on both sides builds the same `MetadataMap` from the card JSON
**minus the `attestation` block**:

```
{ v: 1, type: "REEVE_KEY_CARD",
  subject: { subjectType, subjectId, [displayName], [email], organisationId },
  key:     { publicKey, [label], assurance, createdAt } }
```

`[bracketed]` fields are **omitted when null or blank**, on both sides. Canonical CBOR sorts keys, so
insertion order is irrelevant and an importer rebuilding the map from the received JSON gets a
byte-identical digest. Excluding the `attestation` block is deliberate and correct: the block does not
exist when the digest is computed, and an importer has only the base card to work from.

Two parity defects in that formula:

- **`organisationId` is always emitted by the indexer, as an empty string when unset — never omitted,
  never null.** The platform puts it unconditionally, so a card missing the field yields `null` and a
  digest that cannot match. The platform must normalise absent ⇒ `""`.
- **`displayName`, `email` and `label` are nullable on `IssuedCardEntity` and omitted when blank, but
  `KeyCardDto` marks all three `@NotBlank` (`email` also `@Email`).** A genuine minimal card is
  rejected at bean validation before verification runs, and the platform's own `putIfPresent` branches
  are unreachable. They must be `@Nullable` to match the issuer.

#### 6.4.2 What is actually attested — a KEL seal, not a transaction

The indexer is a chain **reader** (yaci-store sync) with no submitter, and deliberately stays one.
`CardAttestService`: *"**NOTHING IS PUBLISHED TO CARDANO.** The attestation IS the wallet's own KEL
interaction event."* Version 1.12 dropped the former `txHash` from the card outright.

The ceremony:

1. `cardDigest` = the §6.4.1 digest.
2. `payloadSaid` = `Saider.saidify({ i: <walletAid>, d: "", metadataLabel: "<label>",
   metadataDigest: <cardDigest> })` → its `d`.
3. The paired Veridian wallet anchors **`payloadSaid`** as the seal of a KEL interaction event.
4. The indexer verifies that event exists strictly after a pre-send floor sequence, then binds it to
   the card.

`metadataLabel` is `keri.metadata-label` (default `170`) fed in **as a string** and recorded on the
card as the exact string used. It is an input to the SAID, so a verifier must take it from the card
verbatim and must not hardcode `"170"` — a deployment on another label would otherwise never verify.

The payload shape is live-wallet-tested: Veridian's `processRemoteSignReq` silently drops anything
that is not self-addressing with `i` present before saidifying. Changing it is a wallet-compatibility
change, which is why §5.2 defers binding `credentialSaid`/`schemaSaid` into it.

#### 6.4.3 Field-by-field: what the indexer emits vs what the platform expects

| Card `attestation` field | Indexer emits | Platform `CardAttestation` | Verdict |
|---|---|---|---|
| `oobi` | yes (omit if blank) | `@NotBlank` | ok |
| `aid` | yes, always | `@NotBlank` | ok |
| `credentialSaid` | yes (omit if blank) | `@NotBlank` | tighten to `@Nullable` |
| `schemaSaid` | yes (omit if blank) | `@NotBlank` | tighten to `@Nullable` |
| `credentialCesr` | yes (omit if blank) | `@Nullable` | ok |
| `kelSequence` | yes | **missing** | add — names the anchoring event |
| `kelEventSaid` | yes | **missing** | add — names the anchoring event |
| `metadataLabel` | yes | **missing** | add — **SAID input**, §6.4.2 |
| `payloadSaid` | yes (informational) | **missing** | add — must be **recomputed**, never trusted |
| `cardDigest` | yes (informational) | recomputed only | keep recomputing; compare for a better error |
| `txHash` | **removed in v1.12** | `@NotBlank` | **remove** |

`txHash` being `@NotBlank` is the immediate blocker: a current indexer card has no such field, so
import fails at bean validation before any verification logic is reached.

### 6.5 Required platform changes

1. **`KeyCardDto.CardAttestation`** — drop `txHash`; add `kelSequence`, `kelEventSaid`,
   `metadataLabel`, `payloadSaid`, `cardDigest`; relax the §6.4.3 nullability. Unknown fields are
   already ignored, so a card carrying extras stays forward-compatible.
2. **`KeyCardDto.Subject` / `Key`** — `displayName`, `email`, `label` become `@Nullable` (§6.4.1).
3. **`CardAttestationDigestFactory`** — normalise absent `organisationId` to `""`; correct the javadoc,
   which currently claims the digest is carried in an on-chain `170.d`.
4. **`AttestationImportVerifier`** — replace step 3 (on-chain read) with:
   - recompute `cardDigest` from the card body (already done);
   - recompute `payloadSaid` from `{i: aid, d: "", metadataLabel: <card's label string>,
     metadataDigest: <recomputed cardDigest>}`;
   - fetch the AID's KEL after the §6.1 OOBI refresh, locate the event named by
     `kelSequence`/`kelEventSaid`, and require its seal to contain the **recomputed** `payloadSaid`;
   - reject when the card's asserted `cardDigest`/`payloadSaid` disagree with the recomputed values.
5. **Extract a `KelAnchorVerifier` in `keri_attestation`.** `KeriAttestService` already holds
   `locateAnchoringEvent`, `scanForSealMatch`, `satisfiesFloorAndDigest` and `sealContainsDigest`, all
   private. Import verification needs exactly this logic; two copies of a seal check is how the two
   paths drift apart. One caller supplies a floor sequence (the ceremony), the other an expected event
   identity (import) — the seal predicate is shared.
6. **Drop `CardanoMetadataReader` from this path**, along with the "no Cardano metadata reader"
   `CARD_ATTESTATION_UNVERIFIABLE` branch.
7. **Persistence.** `VaultKeyEntity` / `AddressbookEntryEntity` lose `attestation_tx_hash` and gain
   `attestation_kel_sequence`, `attestation_kel_event_said`, `attestation_metadata_label`,
   `attestation_payload_said`, `attestation_card_digest`. The branch's migrations are already
   consolidated and unreleased, so this folds into the existing files rather than adding another.
8. **Frontend.** The badge shows the KEL anchor (sequence + event SAID), not a transaction link; there
   is no explorer URL to link to.

> Blocked on §5. Until authenticity is established, no verified result may be persisted or displayed.

### 7.1 What verification returns

```java
public record VerifiedCredential(
        AttestationStatus status,     // enum, not boolean (§7.3)
        Instant verifiedAt,
        String holderAid,
        String schemaSaid,
        String schemaName,
        String leafIssuerAid,         // who issued the credential
        String trustAnchorAid,        // what trust was established against
        TrustModel trustModel,
        String policyFingerprint,     // hash of the schema entry applied
        Map<String, Object> claims,
        String kelSequence,           // the KEL anchor, not a txHash (§6.4.2)
        String kelEventSaid) {
}
```

`leafIssuerAid` and `trustAnchorAid` are **separate**. In a vLEI chain they are different AIDs — the
leaf issuer is the QVI, the anchor is GLEIF. Presenting one as the other misstates both who issued
the credential and why it was trusted. For `STANDALONE` they coincide by definition.

`policyFingerprint` and `trustModel` record which policy produced the verdict, so a later
configuration change is reconstructable rather than silently rewriting history.

`claims` is the ACDC's `a` block minus `i` and `d`, schema-agnostic — no field is special-cased.

**Schema conformance is not checked.** A matching schema SAID does not prove the credential's `a`
block conforms to that schema; `isAcdc` requires only that `s`, `a` and `i` are present. Claims are
therefore *displayed as presented*, not validated. Either resolve and validate against the schema
(schema-agnostically, owned by `keri_attestation`) or state this limitation in the badge tooltip.
This spec requires the limitation be stated; conformance validation is a recorded follow-up.

### 7.2 Where the result is persisted — **open, requires ratification**

Adversarial review rated storing this in `document_vault` a **Critical** violation of "nothing in
`document_vault`". The brief, however, explicitly offers it:

> *"If the badge needs persisted data, decide and justify whether it belongs on document_vault's own
> rows … or in a keri_attestation-owned store — but the verification logic itself must not leak."*

The reviewer was given the compressed constraint, not that sentence, so its finding is correct
against what it was told and does not settle the question. Both options, for ratification:

| | (a) `document_vault` rows | (b) `keri_attestation` store |
|---|---|---|
| Surface | entities, views, mapping, migration in `document_vault` | new table, key, cleanup, join on every read |
| Lifecycle | shares the key/contact's — delete cascades naturally | independent; needs its own reaping |
| Constraint | permitted by the brief; still zero logic in `document_vault` | unambiguously satisfies the strict reading |

**Recommendation: (b).** The brief permits (a), but review is right that it spreads attestation
surface across a module the constraint names, and the badge is the *only* consumer — so the join cost
is small and the boundary becomes unarguable. This reverses the previous revision, which chose (a).

Whichever is chosen: `document_vault` computes nothing. New migration file only, never an edit to the
three consolidated ones.

### 7.3 Status is an enum, and `false` needs an admission decision

```java
public enum AttestationStatus { VERIFIED_AT_IMPORT, UNKNOWN_LEGACY, REVOKED_AFTER_IMPORT }
```

A primitive `boolean` cannot express the grandfathered "unknown" state §13.4 requires.

There is also **no path that persists a failed verification**: `verify` returns `Left`, and
`CardImportService` aborts the import, so nothing is stored. The previous revision's "unverified
renders distinctly" was therefore incoherent — that state cannot exist. Either drop failed-badge UI
(this spec does) or change admission to persist failed attestations, which is a separate security
decision and is not taken here.

### 7.4 Re-import semantics

Attestation provenance is written **once, at creation**: a re-imported own key refreshes only the
label, a re-imported contact only its display fields. So a card re-imported with a *newly valid*
attestation would keep showing the old state.

**Decision: attestation is immutable per row, except that a re-import carrying a verifying
attestation where the row has none may set it.** A re-import must never *downgrade* or *replace* an
existing verified attestation — that would let a later, weaker card overwrite a stronger claim.

### 7.5 What the API exposes

Null when absent; never exposes `attestation_credential_cesr` (bulk verification input, no display
value):

```java
public record CredentialAttestationView(AttestationStatus status, Instant verifiedAt,
                                        String schemaSaid, String schemaName,
                                        String leafIssuerAid, String trustAnchorAid,
                                        TrustModel trustModel,
                                        Map<String, Object> claims,
                                        String kelSequence, String kelEventSaid) {
}
```

## 8. Frontend

> Blocked on §5 and §7.

`CredentialBadge`, modelled on the indexer's `IdentityAttestationBadge`:

- Primary label `schemaName` → `schemaSaid` → generic "Verified credential".
- Tooltip: **both** `leafIssuerAid` (issued by) and `trustAnchorAid` (trusted via), `trustModel`,
  `verifiedAt`, claims as key/value rows, tx hash.
- Tooltip must state **"verified at import"** — not a live claim (§13.3) — and that claims are shown
  as presented (§7.1).
- `UNKNOWN_LEGACY` renders neutrally, visibly distinct from verified.
- No attestation ⇒ no badge. Absence is not a claim and does not mean rejected (§5.4).

**Reconciliation with `AssuranceChip`.** Different questions; both stay, side by side:

| | Question | Source |
|---|---|---|
| `AssuranceChip` | how was this key generated? | self-asserted `KeyAssurance` |
| `CredentialBadge` | who vouched for this holder? | verified credential |

Rendered at the five existing `AssuranceChip` sites: `card-import`, `addressbook-panel`, `keys`,
`document-create`, `document-detail`. `AssuranceChip` is unchanged. No GLEIF lookup is ported.

## 9. Trust decisions — fail closed

| Condition | Decision |
|---|---|
| Unknown schema SAID | **Fail closed** — an unconfigured schema has no policy |
| `CHAINED` with empty `trustedRoots` | **Startup rejection** (§4.5) — unreachable at runtime |
| `STANDALONE` with empty `trustedIssuers` | **Startup rejection** (§4.5) |
| No `credential-schemas` at all | **Fail closed** + startup WARN; attested cards rejected, unattested import unaffected |
| Card carries no attestation | **Pass** — optional; see §5.4 |

**The permissive "warn release" is removed.** The previous revision accepted empty trust lists for one
release while *already persisting a user-visible verified flag* — publishing "verified" for a
credential checked on structure alone. An empty active list means "anyone may issue this schema",
which is not a trust policy and must never produce a verified outcome. Empty lists are now a startup
rejection from release one, which is loud, immediate and fixable by configuration.

Operators upgrade by populating their lists **before** deploying. §13.5 keeps a legacy-config
fallback so an unconfigured deployment starts, but such a deployment verifies nothing.

## 10. Resolved questions

### 10.1 Split deployment — dissolved by §6.4

Previously recorded as a prerequisite: `AttestationImportVerifier` needs `CardanoMetadataReader`,
supplied by `blockchain_publisher`, which is disabled on the api pod where card import runs — so
attested import failed closed there, and the fix looked like routing a chain read through the
publisher or re-introducing a chain client to a tier deliberately built without one.

**§6.4 removes the problem rather than solving it.** There is no transaction to read: the attestation
is a KEL seal, and KERIA is already reachable from the api pod because every other ceremony step needs
it. Card import verifies fully on the api pod with no chain access, and the cross-pod round trip,
its failure matrix and the read-only-client alternative are all moot.

No longer a prerequisite. §5 still is.

### 10.2 Per-schema OOBIs

Resolved by `keri_attestation` on the agent, at first use per schema, memoised per process.

Not at startup: that makes boot depend on every issuer being reachable. Memoisation must be
invalidatable on resolution failure so a rotated OOBI is not pinned to a stale entry for the process
lifetime; a stale cache must not be able to mask §5.3.

Resolution failure is not by itself a verification failure — but per §5.1 the chain must be
authenticated by some path, and "OOBI failed, chain still parsed" is **not** that path.

### 10.3 Revocation after import

**Decision: out of scope, bounded and stated.** The badge asserts "verified at import" and the
tooltip must say so. Continuous revalidation needs a revocation-polling design larger than this work.
`REVOKED_AFTER_IMPORT` exists in the enum so a later sweep has a state to write.

This is distinct from §5.3, which is revocation *before* import and **is** in scope.

### 10.4 Grandfathered rows

**Decision: grandfather as `UNKNOWN_LEGACY`.** Rows imported while policy checks were no-ops were
structurally validated but never trust-checked. `false` would assert a check that never ran.
Retro-verification is impossible offline anyway (needs §5, §10.1).

### 10.5 Configuration backward compatibility

**Decision: one deprecation release.** If `credential-schemas` is absent and legacy keys are set,
synthesise one `CHAINED` entry per SAID with the legacy roots and WARN. If both are set,
`credential-schemas` wins and legacy keys are ignored with a WARN. Remove the fallback next release.

A synthesised entry is subject to §4.5 and §9: legacy `trusted-root-aids` is empty in practice, so
such a deployment **fails startup** — correct, since it has no trust policy. The fallback exists to
keep `schema-saids` feeding `KeriCredentialService`'s IPEX apply (§4.4), not to preserve permissive
trust.

## 11. Indexer improvements

Required by the brief ("improve the indexer with anything the port surfaces … zero coupling").
Changes made **inside the indexer**; nothing imports from or calls the platform.

- **11.1 Retire the `lei` special case.** `IdentityAttestationView` carries a dedicated `lei` field and
  the frontend keys a GLEIF lookup on it — the last vLEI-shaped assumption. Derive it from `claims`
  and drive the cross-check from an optional per-schema flag.
- **11.2 Memoise OOBI resolution.** `verifyCredentialEntity` calls `resolveOobis()` every time.
- **11.3 Close the revocation TODO** in `verifyStandaloneTrust`, and consider §5.3 — the indexer reads
  TEL state from the presented chain too.
- **11.4 Trust-list hygiene.** `blankFiltered` silently drops blanks, so a mis-templated
  `${KERI_VLEI_GLEIF_ROOT_AID:}` turns a configured root into an empty list, which means *accept
  everything*. At minimum WARN; preferably adopt §4.5's rejection.
- **11.5 Test gaps.** No coverage of the §6.2 schema-confusion defence or the trust models end to end.

## 12. Test plan

### 12.1 Structural validation must not weaken (hard constraint 5)

Regression tests locking current behaviour, run identically before **both** trust models: missing
`iss` at leaf / intermediate / root; revoked intermediate; revoked root; null issuer; unresolved
parent edge; child-issuer ≠ parent-issuee; cycles; multi-edge; converging DAG; malformed or non-map
`e`. Without these, "unchanged" is an assertion, not a guarantee.

### 12.2 Authenticity (§5)

Forged CESR naming a trusted issuer is rejected; an attestation whose payload SAID is not sealed in
the claimed AID's KEL is rejected (§6.4.2, cases in §12.6); a replayed pre-revocation stream is
rejected against authoritative TEL state.

### 12.3 Registry, gate and trust

Startup rejection for every §4.5 row. Unknown SAID rejected. `CHAINED` accepts a chain terminating in
a trusted root and rejects one terminating elsewhere. `STANDALONE` accepts a trusted issuer, rejects
an untrusted one, rejects a revoked leaf. **Schema confusion**: claim a lenient standalone schema,
embed a vLEI-schema ACDC with matching issuee ⇒ rejected. **Ambiguity**: two same-issuee, same-schema
credentials ⇒ rejected, not first-wins. **Extensibility**: bind a third schema from configuration
only, with no production-code change in the test's diff.

### 12.4 Failure matrix

Each with status/code, retryability, timeout, idempotency and fail-open/closed: KERIA unreachable,
KEL fetch timeout, key state behind the claimed anchor sequence, AID rotation between issue and
import; CESR parse failure and wrong field types; oversized CESR, chain depth, edge fan-out, claims
depth — with explicit input bounds and bounded (non-recursive-unbounded) traversal; OOBI rotation,
partial resolution, stale cache; claims JSON serialisation failure; interruption/shutdown mid-verify.
Note `AttestationImportVerifier` does **not** currently wrap the validator call, while
`KeriCredentialService` does precisely because hostile chains can throw.

### 12.5 Contract, migration, frontend

Legacy keys alone synthesise a registry and warn; both set ⇒ registry wins and warns; IPEX apply
still gets a SAID list and an explicit selector (§4.4). Grandfathered rows read back
`UNKNOWN_LEGACY`. Re-import never downgrades an existing verified attestation (§7.4). Views never
expose `attestation_credential_cesr`. Badge renders `schemaName` → `schemaSaid` → generic; shows both
issuer and anchor; `UNKNOWN_LEGACY` neutral; absent ⇒ nothing; `AssuranceChip` unaffected at all five
sites.

### 12.6 The indexer card contract (§6.4)

The regression that motivated §6.4 is a *contract* break, so the tests must be pinned to real issuer
output, not to the platform's own assumptions — a platform-authored fixture would have agreed with the
platform and caught none of this.

- **Golden card fixture, captured from indexer output**, checked into the platform. Digest parity is
  asserted against a digest value recorded from the indexer, so a change on either side fails here.
- Digest parity across the omit-if-blank matrix: absent `displayName`, absent `email`, absent `label`,
  and all three together. A minimal card must import, not fail validation (§6.4.1).
- Absent `organisationId` normalises to `""` and reproduces the digest.
- `payloadSaid` recomputation reproduces the indexer's recorded value; a card whose asserted
  `payloadSaid` or `cardDigest` disagrees with the recomputation is rejected.
- `metadataLabel` is taken from the card: a card issued under a non-`170` label verifies, and one
  whose label was altered fails the seal check. Hardcoding `"170"` must fail this test.
- Seal verification: correct anchor passes; wrong event, seal for a different payload SAID, missing
  event, and an AID whose KEL is unreachable each fail closed with distinct reasons.
- A card carrying an unknown extra field still imports (forward compatibility).
- A legacy card carrying `txHash` does not fail validation on that field alone.
- Card import verifies with `blockchain_publisher` absent — no `CardanoMetadataReader` on the path
  (§10.1).

## 13. Files

Deferred until §7.2 is ratified — option (a) and (b) touch different modules. The
`keri_attestation` and prerequisite work below is unaffected either way.

### `cf-reeve-platform` — `keri_attestation`

| File | Change |
|---|---|
| `config/CredentialSchema.java` | add (with `TrustModel` enum) |
| `config/CredentialSchemaRegistry.java` | add, with §4.5 startup validation |
| `config/KeriAttestationProperties.java` | `credentialSchemas`; deprecate legacy keys (§10.5) |
| `service/CredentialChainValidator.java` | gate + trust + unique leaf; remove both `TODO(policy)`; new signature (§6.3) |
| `service/AttestationImportVerifier.java` | authenticity (§5); KEL-seal instead of on-chain read, drop `CardanoMetadataReader` (§6.5); return `VerifiedCredential`; wrap the validator call (§12.4) |
| `service/KeriAuthBeginService.java` | adapt to the new signature |
| `service/KeriCredentialService.java` | adapt; explicit IPEX schema selector (§4.4) |
| `service/KelAnchorVerifier.java` | **add** — seal logic extracted from `KeriAttestService`'s privates, shared by ceremony and import (§6.5.5) |
| `service/KeriAttestService.java` | delegate seal location/matching to `KelAnchorVerifier` |
| `service/CardAttestationDigestFactory.java` *(document_vault today)* | normalise `organisationId`; correct the on-chain javadoc (§6.5.3) — relocation decided with §7.2 |
| `REUSING.md` | document the registry as the extension point |

### `cf-reeve-platform` — `document_vault`

| File | Change |
|---|---|
| `domain/card/KeyCardDto.java` | `CardAttestation`: drop `txHash`, add the KEL anchor fields; relax nullability on `displayName`/`email`/`label` (§6.4.3) |
| `service/CardImportService.java` | build the new claim; persist the anchor fields instead of `attestationTxHash` |
| `domain/entity/{VaultKeyEntity,AddressbookEntryEntity}.java` | replace `attestation_tx_hash` with the five anchor columns (§6.5.7) |
| Flyway | fold the column change into the branch's existing consolidated migrations |

### `cf-reeve-application`

`application.yml` gains `credential-schemas` (§4.3), keeping legacy keys one release;
`docker-compose.yml` keeps env-var parity.

### `cf-lob-frontend` (`feat+document-module`)

`features/credential-badge/{component,spec}`; `CredentialAttestation` type in
`document-vault-api.types.ts`; render beside `AssuranceChip` in `card-import`, `addressbook-panel`,
`keys`, `document-create`, `document-detail`; translations.

### `reeve-indexing-example`

`IdentityAttestationView` (§11.1); `IdentityAttestationBadge` (§11.1); `KeriService` (§11.2–11.4);
`KeriServiceTest` (§11.5).

## 14. Review disposition

Adversarial review (Codex, session `019fb15b-0b58-7770-9f1e-1e51bf1e8589`) raised 18 findings.

| # | Severity | Disposition |
|---|---|---|
| 1 | Critical | **Accepted** — §5.1 authenticity boundary; blocks §7–§8 |
| 2 | Critical | **Accepted, and worse than reported** — the reviewer read the missing KEL-seal check as a weakness in the on-chain path. Checking the indexer showed there is no on-chain path: nothing is published, `txHash` was removed in v1.12, and the sealed value is the payload SAID, not the card digest. Attested import is broken today, not merely weak. §6.4–§6.5; §5.2 rewritten; §10.1 dissolved |
| 3 | Critical | **Accepted** — §5.3 authoritative TEL at import |
| 4 | Critical | **Open, escalated** — §7.2. The brief explicitly permits `document_vault` rows; the reviewer had only the compressed constraint. Recommendation reversed to a `keri_attestation` store; needs ratification |
| 5 | Critical | **Accepted** — §9, permissive warn release removed |
| 6 | High | **Accepted** — §5.4 states the property; admission-gating out of scope |
| 7 | High | **Accepted** — §6.2 exactly-one leaf incl. credential SAID |
| 8 | High | **Accepted** — §4.1 enum, §4.5 startup rejection |
| 9 | High | **Accepted** — §7.3 status enum; failed-badge UI dropped |
| 10 | High | **Accepted** — §12.1 structural regression suite |
| 11 | High | **Accepted, factual error corrected** — three callers, §6.3 |
| 12 | High | **Accepted, factual error corrected** — §1 (acceptance is unconditional, not empty-list-conditional); "unchanged call site" removed |
| 13 | High | **Accepted** — §7.1 `leafIssuerAid` / `trustAnchorAid` split |
| 14 | High | **Accepted** — §7.1 conformance limitation stated; validation a follow-up |
| 15 | Med-high | **Accepted** — §4.4 config-only ≠ hot reload; IPEX selector |
| 16 | Med-high | **Accepted** — §7.1 `verifiedAt` + `policyFingerprint`, §7.4 re-import |
| 17 | Med-high | **Accepted** — §12.4 failure matrix |
| 18 | Medium | **Rejected** — the brief requires indexer improvements ("improve the indexer with anything the port surfaces … zero coupling"). The reviewer was told the indexer was prior art only. §11 stays; no coupling is introduced |

## 14b. Implementation review disposition

Two adversarial review passes ran against the implementation (Codex, sessions `019fb1a1-…` and
`019fb1c8-…`). What they found and what was done:

| Finding | Disposition |
|---|---|
| Card CESR not cryptographically authenticated | **Fixed, partially.** Every ACDC must now re-derive to its own SAID, every SAID is confirmed against its issuing registry's live TEL state, and every credential must come from a registry its own issuer controls (`vcp.ii == acdc.i`, with the registry id tied to its inception SAID). Full closure needs KEL/TEL signature verification — stated explicitly in `CredentialChainValidator`'s javadoc rather than implied away |
| Unattested re-import rewrites an attested row's signed fields | **Fixed.** `CardImportService.checkAttestedFieldsNotRewritten`, plus the addressbook update endpoint, which was the easier way around it |
| KEL sequence and event SAID matched as alternatives | **Fixed.** `KelAnchorVerifier.matchesEveryCoordinate` — both must name one event |
| Registry disconnected from IPEX presentation | **Fixed.** `KeriCredentialService` now resolves and requests schemas from the registry. This was a live break: production config had been left with blank legacy keys the apply still read |
| Duplicate ACDC SAIDs; edge schema never checked | **Fixed.** Duplicates rejected before indexing; every edge's `s` must match the parent's actual schema |
| `metadataLabel` / `credentialCesr` unbounded | **Fixed.** Size limits on the DTO, mirrored in the entity columns |
| Nested secret-bearing fields silently dropped | **Fixed.** `subject` and `key` reject unknown fields outright — both are digest-covered, so an unknown field there is either a smuggled secret or proof the formula diverged. `attestation` stays tolerant, since it evolves |
| Explicitly empty `credential-schemas` fell back to legacy anchors | **Fixed.** Absent and empty now mean different things |
| Stock config could not boot with KERI enabled | **Fixed.** The registry ships empty (accepts nothing, boots) with the entries as a filled-in commented template |
| Migration edited in place | **Not a defect here** — the file exists only on this branch and has never been released. A header note now says when that stops being true |
| Stale test-compile errors | **Not reproducible.** Reviewed a mid-flight tree; the sandbox blocked Gradle so nothing was compiled |
| TOCTOU between the re-import guard and the write | **Open.** Needs row locking or a conditional update; the window is narrow and requires a concurrent attested import |
| Secret-name matching still bypassable (`private.key`, nested under `attestation`) | **Open.** Normalisation strips only `_` and `-` |
| No bound on parsed event count, chain depth or edge fan-out | **Open.** Only the input size is bounded |
| Registry validation: inactive lists, names, OOBI syntax; defensive list copies | **Open, low** |

### 14b.1 Third review pass (M5–M7)

| Finding | Disposition |
|---|---|
| Unattested rows crash the badge: backend serialises `NON_NULL`, so `attestation` arrives ABSENT, and the component only handled `null` | **Fixed.** Type is optional-nullable, guard is `== null`, and a test drives the omitted-field shape — the case that covers most real cards |
| `/keys/me` attaches one organisation's verdict to another's row | **Fixed.** `/keys/me` spans organisations; the lookup now groups by organisation and keys results on `(organisationId, publicKey)` |
| Indexer memoised FAILED OOBI resolves | **Fixed.** `wait()` returns a done-with-error operation rather than throwing, so "no exception" was never success. Only error-free completions are cached |
| A verdict survives while the row's provenance columns are null, so the re-import guard misses it | **Fixed.** The guard consults the verdict store as well as the columns — provenance is written only at row creation, so the two disagree exactly when it matters |
| Verdict table appended to an existing versioned migration | **Not a defect here** — `V1.7_100_15` is branch-only, like `_14`. Both now carry a header note saying when that stops being true |
| Verdict not bound to the card digest: delete-and-recreate revives it; two accounts sharing a key inherit one verdict | **Open.** Needs the recomputed digest persisted with the verdict and projected only on an exact match |
| `policyFingerprint` stored but never compared with current config | **Open.** Removing a compromised root leaves old rows plainly green; needs a `POLICY_CHANGED` state |
| Credential claims (possible PII) returned to every org member in bulk lists | **Open, and a product decision.** Claims are whatever the ACDC carried — names, e-mails, roles. Either drop them from list DTOs or allowlist per schema |
| Concurrent OOBI resolution not coalesced | **Open, low.** Thread-safe set, non-atomic check-resolve-add |
| Badge tooltip unreachable by keyboard | **Open, low** |
| `VERIFIED` overstates what is proven while signatures are unverified | **Open, and the decision worth making first** — see §5.2. Everything structural is checked and the registry↔issuer link is bound, but no signature is. Whether that earns the word "verified" is a product call, not one to settle in code |

## 15. Sequencing

1. **§6.4–§6.5 the card contract.** First, and independent of everything else: attested card import is
   broken outright today (§6.4.3), the fix is well-understood, and it removes the §10.1 blocker instead
   of working around it. Doing it after the registry would mean building trust decisions on a path
   that cannot run.
2. **§5 authenticity** — prerequisite. Nothing downstream is sound without it.
3. Registry, startup validation, schema gate, unique leaf, both trust models — fail closed from day one (§9).
4. Ratify §7.2, then result plumbing.
5. Frontend badge.
6. Indexer improvements (§11) — independent.
7. Remove the §10.5 legacy fallback one release later.

## 16. Out of scope

Continuous revocation revalidation (§10.3); retro-verification of grandfathered rows (§10.4); schema
conformance validation (§7.1); trust-gated *admission* of unattested cards (§5.4); hot reload
(§4.4); GLEIF lookups in the platform frontend; changes to structural validation (§12.1) or
`AssuranceChip` semantics; any indexer↔platform integration.
