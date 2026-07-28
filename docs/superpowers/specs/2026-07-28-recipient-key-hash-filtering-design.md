# Recipient key-hash filtering — design

**Date:** 2026-07-28
**Repos:** `cf-reeve-platform` (producer) and `reeve-indexing-example` (consumer)
**Status:** design, approved for planning

---

## 1. Goal

A recipient of a published Reeve document should be able to open the public Indexer, press
**"Filter for my documents"**, present their key, and see only the documents addressed to them.

Nothing on-chain identifies a recipient today, so the filter has no anchor to match on. This design adds
one: a list of **recipient key hashes** in the label-1447 `DOCUMENT` manifest, which the Indexer ingests
into its database and exposes as a query filter.

The work spans both repos, and the wire format in §3 is the contract between them.

---

## 2. What exists today

### 2.1 Producer — `cf-reeve-platform`

A published document is an end-to-end-encrypted envelope pinned to IPFS, with a manifest anchored on
Cardano under metadata label **1447**, type `DOCUMENT`.

| Concern | Where |
|---|---|
| Manifest body | `blockchain_common/.../service_assistance/DocumentMetadataSerialiser.java:40-62` |
| IPFS envelope | `blockchain_common/.../service_assistance/DocumentIpfsSerialiser.java:31-50` |
| Publish contract | `blockchain_common/.../domain/events/DocumentPublishCommand.java` |
| Persisted slot | `document_vault/.../domain/entity/DocumentSlot.java` |
| Slot collection | `document_vault/.../domain/entity/VaultDocumentEntity.java:141-144` |
| Publish factory | `document_vault/.../service/VaultDocumentService.java:402-415` |
| Shape validation | `blockchain_common/src/main/resources/document_lob_blockchain_transaction_metadata_schema.json` |

A recipient is identified internally by an **X25519 public key, 32 bytes lowercase hex**, held on
`VaultKeyEntity.publicKey` (an organisation member with a Keycloak account) or
`AddressbookEntryEntity.publicKey` (an external contact with no account). `VaultKeyLookupService`
reduces both to a `KeyRef` so the document paths need not care which store a slot names.

Three properties of the current design are load-bearing for what follows:

1. **The publish path is deliberately PII-free.** `DocumentConverter` strips `keyId` and `recipientRef`
   from every slot, so only `ephemeralPub` and `wrappedDek` reach IPFS or L1. Three tests enforce it:
   `DocumentPublishCommandPiiTest`, and `NoPiiOnDocumentPublishPathArchTest` in both `blockchain_common`
   and `blockchain_publisher`.
2. **Slots are self-contained and immutable.** The migration that created
   `document_vault_document_slot` dropped the foreign key to the key table on purpose — "Slots are
   immutable and hold their own wrapped DEK; a deleted key simply stops being offered." A dangling
   `keyId` is an expected, tolerated state, not a fault.
3. **Slot order is persisted.** `@OrderColumn(name = "slot_index")` on the collection, and a
   `(document_id, slot_index)` primary key on the table.

On the **attested** publish path the entire 1447 map is frozen at `prepareDigest` time and signed by the
holder's KERI wallet; the publisher then submits those exact bytes. `DocumentDispatchRetryJob`
re-invokes the same static `toPublishCommand` factory on a retry sweep.

### 2.2 Consumer — `reeve-indexing-example`

Spring Boot 3.3.3 / Java 21 / PostgreSQL, schema owned by Flyway
(`src/main/resources/db/store/postgresql/`, latest `V1.10`). React 18 + TypeScript + Vite + MUI v7
frontend with `@tanstack/react-query`.

| Concern | Where |
|---|---|
| Metadata intake | `.../yaci/ReeveMetadataStorage.java:97-154` |
| `DOCUMENT` parser | `.../processor/DocumentProcessor.java:111-124` |
| Entity / table | `.../model/entity/DocumentEntity.java` → `reeve_document` |
| Query service | `.../service/DocumentService.java:46-64` |
| REST controller | `.../controller/DocumentController.java:38-45` |
| Documents table UI | `frontend/src/modules/public-documents/view/ViewPublicDocuments.component.tsx` |
| Table toolbar | same file, `:188-216` |
| Crypto utilities | `frontend/src/libs/document-vault-crypto/` |

The frontend already carries everything the browser side needs: hex/byte codecs (`codecs.ts`),
`crypto.subtle.digest('SHA-256', …)` (`decrypt.ts:96`), a deterministic WebAuthn-PRF X25519 derivation
whose **public-key-only** entry point is `deriveX25519PublicKeyFromPrf` (`passkey.ts:94-101`), and a
`passkey | raw` key-source toggle in `DecryptPanel.component.tsx:131-141`.

`DocumentController` is unauthenticated by deliberate choice, and no key material is persisted anywhere
in the frontend — `passkey.ts` and `DecryptPanel.hooks.ts` both zero it on every path.

---

## 3. Wire format

### 3.1 The hash

```
recipient_key_hash = sha256( 32 raw bytes decoded from the lowercase-hex X25519 public key )
```

rendered as **64 lowercase hex characters**.

No salt, no domain-separation prefix, no truncation. That is a decision, not an omission:

- **Reproducibility.** This is a public, permissionless format. Anyone auditing a document from a block
  explorer must be able to recompute a hash with `printf %s <pubkey> | xxd -r -p | sha256sum` and
  nothing else. A prefix would trade that away for domain separation the system does not need — the
  X25519 public key is not used as a hash preimage anywhere else in Reeve.
- **Browser support.** WebCrypto implements no SHA-3 family member, so SHA-256 is what
  `crypto.subtle.digest` can compute with zero added dependencies. `decrypt.ts:96` already calls it.
  (The organisation `id` elsewhere in label 1447 uses SHA3-256; that value is computed server-side only,
  which is why it can afford an algorithm the browser lacks.)

**Golden vectors.** These are the RFC 7748 §6.1 X25519 public keys, and every implementation of this
hash in either repo asserts against them:

| X25519 public key (hex) | `recipient_key_hash` |
|---|---|
| `8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a` | `300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae` |
| `de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f` | `f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4` |

### 3.2 The manifest field

`data` gains one field, `recipient_key_hashes`:

```json
{
  "1447": {
    "org": { "...": "unchanged" },
    "metadata": {
      "creation_slot": 12345,
      "timestamp": "2026-07-28T10:15:30Z",
      "version": "1.1"
    },
    "type": "DOCUMENT",
    "data": {
      "id": "0b0f7d1e-6f0a-4d9e-9d5e-1c2b3a4d5e6f",
      "ipfs_cid": "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
      "content_hash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "plaintext_hash": "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752",
      "envelope_version": 1,
      "slot_count": 2,
      "recipient_key_hashes": [
        "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae",
        "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4"
      ]
    }
  }
}
```

Rules:

1. **Order matters.** `recipient_key_hashes[i]` corresponds to `slots[i]` in the IPFS envelope. This
   costs nothing — slot order is already persisted by `@OrderColumn` and the
   `(document_id, slot_index)` primary key — and lets a recipient address their slot directly instead
   of trial-decrypting every one. Both lists are public, so the alignment discloses nothing the flat
   list did not already.
2. **`recipient_key_hashes.length == slot_count`**, enforced on both sides.
3. **Minimum one entry.** A document with no slots cannot be published today (`slot_count` has
   `minimum: 1`), so the array has `minItems: 1`.
4. `DocumentMetadataSerialiser.VERSION` moves `"1.0"` → `"1.1"`. The field is **required** in the JSON
   schema, which is safe because `MetadataChecker` runs write-side only — `AbstractL1TransactionCreator.java:189`
   validates outgoing metadata before submission and never reads anything back. Documents already
   anchored are unaffected; see §5.3 for how the Indexer treats them.

### 3.3 The IPFS envelope is unchanged

The hash goes on-chain **only**. Two reasons:

- The Indexer ingests Cardano metadata. Hashes reachable without an IPFS fetch mean filtering keeps
  working while IPFS is unreachable — `IPFS_UNAVAILABLE` is an existing `DocumentVerdict`, so that is a
  real operational state, not a hypothetical.
- It keeps `DocumentIpfsSerialiser` and the envelope wire format untouched, so `envelope_version` stays
  at `1` and existing readers keep parsing.

### 3.4 Privacy consequence

**This makes every published document permanently and publicly linkable to its recipients.** Anyone
holding a person's X25519 public key — and cards carrying public keys are handed around by design — can
compute their hash and enumerate every document ever addressed to them, across every organisation, for
as long as the chain exists. The hash cannot be revoked, rotated away from, or deleted.

This reverses a deliberate property of the original design, which stated that the format "intentionally
contains no personal data" and that slots carry "no recipient identifiers". It is accepted knowingly as
the cost of recipient-side filtering, and it must be recorded plainly in `docs/onChainFormat.md` rather
than left implicit.

Two mitigations were considered and rejected: a per-document salted hash (`H(doc_id || pubkey)`), which
is not indexable and forces an O(n) scan per query; and a truncated hash prefix for k-anonymity, which
buys little at realistic document volumes — the bucket would contain mostly the querying user's own
documents anyway — while complicating format, storage and UI.

---

## 4. Producer changes — `cf-reeve-platform`

### 4.1 `blockchain_common`

**New** `service_assistance/RecipientKeyHasher` — a single static
`hash(String publicKeyHex) → String`, decoding the hex and returning lowercase-hex SHA-256. It lives
here because `document_vault → blockchain_common` already exists in the module graph, so the vault
(which computes the hash) and `DocumentMetadataSerialiser` (which emits it) can both reach it without a
new dependency edge.

**`DocumentPublishCommand.PublishSlot`** gains `recipientKeyHash`:

```java
public record PublishSlot(String ephemeralPub, String wrappedDek, String recipientKeyHash) { }
```

**`DocumentMetadataSerialiser`** emits `recipient_key_hashes` from `command.slots()` in list order, and
bumps `VERSION` to `"1.1"`.

**JSON schema** adds a `recipientKeyHashArray` definition (`type: array`, `minItems: 1`, items
`$ref: hexHash64Pattern`), lists `recipient_key_hashes` in `data.properties` and in `data.required`, and
retains `additionalProperties: false`. The schema's description text, which currently says "exactly the
six fields below, nothing else", is corrected to seven.

### 4.2 `document_vault`

**`DocumentSlot`** gains `recipientKeyHash` (`VARCHAR(64) NOT NULL`), documented as *derived* server-side
from the recipient's stored public key — never client-supplied, and unlike `keyId`/`recipientRef` it is
exported to L1.

**`VaultDocumentService`** — slot construction at `:145-146` already holds `Map<String, KeyRef>` from
`keyLookupService.findAllById(keyIds)`, so the hash is computed there from `KeyRef.publicKey()` with no
additional lookup. `toPublishCommand` (`:402-415`) remains a static pure function of the entity and
simply reads the stored value.

**Migration** `V1.7_100_14_4__document_vault_slot_recipient_key_hash.sql`:

1. `ALTER TABLE document_vault_document_slot ADD COLUMN recipient_key_hash VARCHAR(64)` (nullable).
2. Backfill by joining `key_id` against `document_vault_key.key_id` and
   `document_vault_addressbook_entry.entry_id`, computing
   `encode(sha256(decode(public_key, 'hex')), 'hex')` — `sha256(bytea)` is built into PostgreSQL ≥ 11.
3. Resolve any remaining NULLs (slots whose key row was deleted — a state the design explicitly
   tolerates), then `SET NOT NULL`.

Step 3 needs a decision the plan must make against real data rather than in the abstract: count the
affected rows first, and choose between a deterministic unmatchable sentinel and leaving the column
nullable for legacy rows. Publishing is blocked on a NULL either way, so the choice only affects how
loudly an already-undeliverable draft fails.

### 4.3 `blockchain_publisher`

`DocumentEntity.Slot` gains the field; a migration adds `recipient_key_hash VARCHAR(64) NOT NULL` to
`blockchain_publisher_document_slot`; and `DocumentConverter.convertSlots` plus its reverse carry it in
both directions. Rows in this table are transient dispatch records, so the backfill question of §4.2
does not recur here.

### 4.4 The PII guards

`recipientKeyHash` matches the `recipient` term in the forbidden-field pattern shared by
`DocumentPublishCommandPiiTest.java:12-13` and both copies of `NoPiiOnDocumentPublishPathArchTest`.

**The field is not renamed to evade the pattern.** Each guard gets an explicit, commented exemption for
this one name, following the precedent already set at `DocumentPublishCommandPiiTest.java:18`, where
`organisationId` is exempted as "org id is public on-chain data, not PII". The new comment records the
same reasoning for a hash of a public key, and points at §3.4 for the trade-off. The guard keeps its
force against e-mails, labels, file names and account ids; the single exception is visible in review
rather than hidden behind a euphemistic field name.

### 4.5 Interaction with the attested publish path

Freezing the hash into the slot at upload — rather than resolving it at publish — is what keeps the
attested path correct. The 1447 map is frozen and wallet-signed at `prepareDigest`, and
`DocumentDispatchRetryJob` re-invokes the same static factory on a retry sweep. Had the hashes been
derived from mutable key rows, a key deleted between freeze and re-emission would produce different
bytes and invalidate the signature. Reading an immutable slot column makes that divergence
unrepresentable.

---

## 5. Consumer changes — `reeve-indexing-example`

### 5.1 Schema

`V1.11__add_document_recipient_key_hashes.sql`:

```sql
ALTER TABLE reeve_document
    ADD COLUMN recipient_key_hashes text[] NOT NULL DEFAULT '{}';

CREATE INDEX idx_reeve_document_recipient_key_hashes
    ON reeve_document USING GIN (recipient_key_hashes);
```

A Postgres array beats the alternatives here: the list is short, bounded, never queried independently of
its document and never mutated after ingest, so a child table would add an entity, a repository and a
join for no benefit; and `hypersistence-utils-hibernate-63` is already a dependency, so
`@Type(ListArrayType.class)` maps it directly. Filtering via the existing `raw` JSONB column would avoid
the migration but indexes poorly and leaves the field undiscoverable in the schema.

The `DEFAULT '{}'` is what lets documents anchored before format 1.1 coexist as empty lists.

### 5.2 Entity

`DocumentEntity` gains:

```java
@Type(ListArrayType.class)
@Column(name = "recipient_key_hashes", columnDefinition = "text[]")
private List<String> recipientKeyHashes = new ArrayList<>();
```

### 5.3 Ingest

`DocumentProcessor` reads `recipient_key_hashes` alongside the existing six fields:

| Manifest state | Behaviour |
|---|---|
| Field absent | Empty list. **Valid** — this is a pre-1.1 anchor, and it must keep indexing normally. It can never match a filter. |
| Present, every entry matches `HASH_64_HEX`, length equals `slot_count` | Stored as-is, order preserved. |
| Present, any entry malformed | `MALFORMED_MANIFEST` — the treatment `content_hash` and `plaintext_hash` already receive. |
| Present, length ≠ `slot_count` | `MALFORMED_MANIFEST`. |

The processor keeps its existing contract of never throwing out of the block-ingest transaction.

### 5.4 Query path

`DocumentService.list` currently branches four ways across `orgId × verdict` (`:46-64`), dispatching to
four repository methods. A third filter dimension would make that eight.

It is replaced with a **single repository query taking nullable parameters** — `(:orgId IS NULL OR …)`
per filter — collapsing the combinatorial branching permanently and letting the GIN index serve the
array match. This is a cleanup the change forces rather than an optional refactor, and it is confined to
the query path.

One detail must be settled empirically during implementation, not assumed: a native query needs the
existing sort whitelist (`slot | blockTime | createdAt`, `DocumentService.java:137-153`) mapped to
column names, and needs an explicit `countQuery` for pagination to work. The plan verifies both against
a running PostgreSQL before the approach is locked in; if `Pageable` sorting proves unworkable over a
native query, the fallback is a JPA `Specification` with the array match expressed through
`cb.function("array_position", …)`.

### 5.5 API

`GET /api/v1/documents` gains an optional `recipientKeyHash` parameter, validated as 64 lowercase hex
and rejected with 400 otherwise. The endpoint remains unauthenticated, consistent with the existing
deliberate choice — and necessarily so, since a hash is public data and the recipients this serves have
no Reeve account to authenticate with.

`DocumentView` exposes `recipientKeyHashes`; it is public on-chain data, and showing it makes the
filter's behaviour inspectable.

### 5.6 Frontend

**New** `libs/document-vault-crypto/recipientKeyHash.ts`:

```ts
export const hashPublicKey = async (publicKeyHex: string): Promise<string> =>
  bytesToHex(new Uint8Array(
    await crypto.subtle.digest('SHA-256', hexToBytes(publicKeyHex))))
```

built on the existing `codecs.ts` helpers.

**New** `MyDocumentsFilter` component, rendered in the toolbar `Box` at
`ViewPublicDocuments.component.tsx:188-216` beside the verdict filter. Activating it reveals a
`passkey | raw` `ToggleButtonGroup` mirroring `DecryptPanel.component.tsx:131-141`:

- **Passkey** — `deriveX25519PublicKeyFromPrf`, which returns the **public key only** and zeroes the
  seed. No private scalar is ever derived for filtering.
- **Paste key** — a 64-hex **public** key field.

Either source feeds `hashPublicKey`, and the resulting hash goes into the query.

`usePublicDocuments` holds the hash in React state — **in-memory only**, cleared on reload, matching the
decrypt panel's stance that nothing is persisted. `documentsApi.getDocuments` passes it through, and
`GetDocumentsParams` gains the field.

### 5.7 Error and empty states

| Situation | Behaviour |
|---|---|
| No PRF support / no authenticator / user cancels | Inline error on the passkey tab; the paste tab remains available. |
| Malformed hex | Field-level validation, no request issued. |
| Valid key, zero results | An empty state distinct from "no documents at all": *no published document is addressed to this key*. It also states that documents anchored before format 1.1 carry no recipient hashes and can never match — without that line, correct behaviour reads as a bug. |

---

## 6. Documentation

`docs/onChainFormat.md` §"Type: Document" is the canonical description of this format and is currently
wrong in one respect and thin in another.

1. **Add** `recipient_key_hashes` to the `data` field table, with the ordering and length rules.
2. **Replace** the two-sentence prose summary of the IPFS envelope (`:448-450`) with a proper
   subsection: its own field table covering `version`, `type`, `org_id`, `content_hash`,
   `plaintext_hash`, `payload{ciphertext, nonce}` and `slots[]{ephemeral_pub, wrapped_dek}`, plus a
   worked example. The envelope is half the format and currently gets one sentence.
3. **Add** a "Recipient key hashes" subsection giving the derivation, the reproduction one-liner, and
   the golden vectors from §3.1.
4. **Correct** the note at `:455-456` asserting the format "intentionally contains no personal data" and
   that slots carry "no recipient identifiers". Replace it with the §3.4 privacy note stating the
   linkability trade-off plainly. Leaving that text in place would make the specification actively
   misleading about what publishing now discloses.
5. **Update** the example JSON and the version to `1.1`.

`docs/keri-document-flow.md` §5 gains a line noting that the frozen 1447 map now includes recipient key
hashes read from immutable slot columns (§4.5).

---

## 7. Testing

The central risk is **two independent implementations of one hash drifting apart**, so the golden
vectors of §3.1 are asserted in both repos: in `RecipientKeyHasherTest` (Java) and in a Vitest test for
`hashPublicKey` (TypeScript). Identical vectors on both sides, and in the published documentation, is
what actually pins the contract.

**Producer**

- `RecipientKeyHasherTest` — golden vectors; rejects malformed hex.
- `DocumentMetadataSerialiserTest` — the `data` key-set assertion at `:85` goes from six fields to
  seven; new assertions on hash order matching slot order and on `length == slot_count`.
- The three PII guards — updated with the §4.4 exemption, and still failing for an added `email` or
  `recipientLabel` field.
- Schema validation — a manifest missing `recipient_key_hashes`, or carrying a malformed entry, fails
  `MetadataChecker`.
- Migration — backfill produces the golden-vector hash for a slot naming a key with the corresponding
  public key, for both the org-key and the addressbook path.
- `DocumentConverter` — round-trips the hash in both directions.
- `DocumentIpfsSerialiserTest:52` already asserts each envelope slot's field list is exactly
  `["ephemeral_pub", "wrapped_dek"]`. That existing test needs no change and becomes the guarantee that
  the new field cannot leak into the IPFS envelope (§3.3) — it fails if anyone ever adds it there.

**Consumer**

- `DocumentProcessor` — one case per row of the §5.3 table.
- Repository — the array filter returns exactly the documents containing a given hash, and an unknown
  hash returns none.
- `DocumentService` — the collapsed query reproduces the existing `orgId`/`verdict` behaviour, so the
  refactor is provably behaviour-preserving before the new dimension is exercised.
- `DocumentController` — the parameter is accepted, and a malformed value yields 400.
- Frontend — `hashPublicKey` against the golden vectors; `MyDocumentsFilter` renders, computes and
  applies a hash; the empty state appears for a key with no matches.

---

## 8. Build order

The wire format is the contract, so the producer leads and each repo stays releasable on its own.

1. **Producer, format** — `RecipientKeyHasher`, `DocumentSlot` + migration and backfill,
   `PublishSlot`, `DocumentMetadataSerialiser`, JSON schema, PII-guard exemptions, publisher entity and
   converter, tests.
2. **Producer, docs** — `docs/onChainFormat.md` per §6, including the golden vectors, so the consumer
   work has a published contract to implement against.
3. **Consumer, backend** — migration, entity, `DocumentProcessor`, the query-path collapse, the API
   parameter, tests.
4. **Consumer, frontend** — `recipientKeyHash.ts`, `MyDocumentsFilter`, hook and API wiring, empty and
   error states, tests.

Step 3 tolerates absent hashes from step 1, so the two repos need not deploy together, and the Indexer
can ship its filter before any 1.1 document has been anchored.

---

## 9. Out of scope

- Backfilling or re-anchoring documents published before format 1.1. They carry no hashes, and the
  chain is immutable; §5.7 makes that visible in the UI instead.
- Any change to the IPFS envelope or `envelope_version`.
- Proof of key possession. The filter matches a public hash, so anyone knowing a recipient's public key
  can run it — inherent to the format, and stated in §3.4 rather than mitigated.
- Authentication on the Indexer's document endpoints, which remain public by existing design.
- Filtering by sender, or any other new dimension.
