# Recipient Key-Hash Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a list of recipient key hashes in the label-1447 `DOCUMENT` manifest so a recipient can open the public Indexer, present their key, and see only the documents addressed to them.

**Architecture:** The producer (`cf-reeve-platform`) derives `sha256(x25519_public_key)` server-side at document upload, freezes it into the immutable slot row, and emits the ordered list as `data.recipient_key_hashes`. The consumer (`reeve-indexing-example`) ingests that list into a Postgres `text[]` column with a GIN index, exposes it as an optional `recipientKeyHash` query parameter, and the browser computes the same hash from a passkey-derived or pasted **public** key.

**Tech Stack:** Java 21 / Spring Boot / Gradle / Flyway / PostgreSQL / cardano-client-lib (`MetadataMap`, `MetadataList`) on both backends; React 18 + TypeScript + Vite + MUI v7 + Vitest on the Indexer frontend.

**Spec:** `docs/superpowers/specs/2026-07-28-recipient-key-hash-filtering-design.md`

## Global Constraints

- **Two repos.** Producer = `/Users/thkammer/Documents/dev/cardano/java/cf-reeve-platform`. Consumer = `/Users/thkammer/Documents/dev/cardano/java/reeve-indexing-example`. Tasks 1–5 are producer; Tasks 6–10 are consumer. Commit in the repo the task names.
- **JDK 21 is mandatory for both Gradle builds.** The machine defaults to JDK 26, which breaks Gradle's Kotlin DSL. Prefix every Gradle command with `JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **The hash is exactly** `sha256(32 raw bytes decoded from the lowercase-hex X25519 public key)`, rendered lowercase hex, 64 chars. No salt, no domain-separation prefix, no truncation.
- **Golden vectors** (RFC 7748 §6.1 public keys) — asserted in Java, in TypeScript, and published in the docs:
  - `8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a` → `300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae`
  - `de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f` → `f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4`
- **`recipient_key_hashes[i]` corresponds to `slots[i]`.** Order is never sorted, deduplicated, or reshuffled.
- **`recipient_key_hashes.length == slot_count`**, enforced on both sides.
- **The IPFS envelope is never touched.** `DocumentIpfsSerialiser` and `envelope_version` stay exactly as they are.
- **Never rename a field to evade the PII guard.** Task 3 adds explicit, commented exemptions instead.
- **The frontend only ever handles the PUBLIC key for filtering.** No private scalar is derived, stored, or transmitted. The computed hash lives in React state only — no `localStorage`, no `sessionStorage`.

---

## File Structure

**Producer — `cf-reeve-platform`**

| File | Responsibility |
|---|---|
| `blockchain_common/src/main/java/.../service_assistance/RecipientKeyHasher.java` | **Create.** The one definition of the hash. |
| `blockchain_common/src/test/java/.../service_assistance/RecipientKeyHasherTest.java` | **Create.** Golden vectors + rejection cases. |
| `blockchain_common/src/main/java/.../domain/events/DocumentPublishCommand.java` | **Modify.** `PublishSlot` gains `recipientKeyHash`. |
| `blockchain_common/src/main/java/.../service_assistance/DocumentMetadataSerialiser.java` | **Modify.** Emit the list; `VERSION` → `"1.1"`. |
| `blockchain_common/src/main/resources/document_lob_blockchain_transaction_metadata_schema.json` | **Modify.** Seventh required field. |
| `blockchain_common/src/test/java/.../domain/events/DocumentPublishCommandPiiTest.java` | **Modify.** Exemption. |
| `blockchain_common/src/test/java/.../architecture/NoPiiOnDocumentPublishPathArchTest.java` | **Modify.** Exemption. |
| `blockchain_publisher/src/test/java/.../architecture/NoPiiOnDocumentPublishPathArchTest.java` | **Modify.** Exemption. |
| `document_vault/src/main/java/.../domain/entity/DocumentSlot.java` | **Modify.** New column. |
| `document_vault/src/main/java/.../service/VaultDocumentService.java` | **Modify.** Compute at upload; pass through at publish. |
| `document_vault/src/main/resources/db/migration/postgresql/common/V1.7_100_14_4__document_vault_slot_recipient_key_hash.sql` | **Create.** Column + backfill. |
| `blockchain_publisher/src/main/java/.../domain/entity/documents/DocumentEntity.java` | **Modify.** `Slot` gains the field. |
| `blockchain_publisher/src/main/java/.../service/publish/module/document/DocumentConverter.java` | **Modify.** Both directions. |
| `blockchain_publisher/src/main/resources/db/migration/postgresql/common/V1.6_200_9_1__document_slot_recipient_key_hash.sql` | **Create.** Column. |
| `docs/onChainFormat.md` | **Modify.** The user-visible deliverable of §6. |
| `docs/keri-document-flow.md` | **Modify.** One line in §5. |

**Consumer — `reeve-indexing-example`**

| File | Responsibility |
|---|---|
| `src/main/resources/db/store/postgresql/V1.11__add_document_recipient_key_hashes.sql` | **Create.** `text[]` + GIN. |
| `src/main/java/.../model/entity/DocumentEntity.java` | **Modify.** Array mapping. |
| `src/main/java/.../processor/DocumentProcessor.java` | **Modify.** Parse + validate. |
| `src/main/java/.../model/repository/DocumentRepository.java` | **Modify.** One nullable-parameter query. |
| `src/main/java/.../service/DocumentService.java` | **Modify.** Collapse the branching. |
| `src/main/java/.../controller/DocumentController.java` | **Modify.** New parameter. |
| `src/main/java/.../model/view/document/DocumentView.java` | **Modify.** Expose the list. |
| `frontend/src/libs/document-vault-crypto/recipientKeyHash.ts` | **Create.** Browser hash. |
| `frontend/src/libs/document-vault-crypto/recipientKeyHash.spec.ts` | **Create.** Golden vectors. |
| `frontend/src/modules/public-documents/components/MyDocumentsFilter/MyDocumentsFilter.component.tsx` | **Create.** The button + key input. |
| `frontend/src/modules/public-documents/components/MyDocumentsFilter/MyDocumentsFilter.spec.tsx` | **Create.** Component test. |
| `frontend/src/modules/public-documents/constants/documents.consts.ts` | **Modify.** Copy. |
| `frontend/src/modules/public-documents/hooks/usePublicDocuments.ts` | **Modify.** Filter state. |
| `frontend/src/modules/public-documents/view/ViewPublicDocuments.component.tsx` | **Modify.** Toolbar + empty state. |
| `frontend/src/libs/api-connectors/backend-connector-reeve/api/documents/documentsApi.ts` | **Modify.** Query param. |
| `frontend/src/libs/api-connectors/backend-connector-reeve/api/documents/documentsApi.types.ts` | **Modify.** Types. |

---

# PRODUCER — `cf-reeve-platform`

All commands in Tasks 1–5 run from `/Users/thkammer/Documents/dev/cardano/java/cf-reeve-platform`.

---

### Task 1: RecipientKeyHasher

The single definition of the hash. Everything else in both repos derives from this.

**Files:**
- Create: `blockchain_common/src/main/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/RecipientKeyHasher.java`
- Test: `blockchain_common/src/test/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/RecipientKeyHasherTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RecipientKeyHasher.hash(String publicKeyHex) → String` (static, 64 lowercase hex). Used by Task 2 (`VaultDocumentService`) and by the Task 4 serialiser tests.

- [ ] **Step 1: Write the failing test**

Create `blockchain_common/src/test/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/RecipientKeyHasherTest.java`:

```java
package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The hash is a PUBLISHED wire-format contract (docs/onChainFormat.md), reimplemented independently
 * in the Indexer frontend. These golden vectors are the RFC 7748 section 6.1 X25519 public keys and
 * are asserted identically in reeve-indexing-example's recipientKeyHash.spec.ts. If you change what
 * this produces, you have changed the on-chain format and broken every already-anchored document.
 */
class RecipientKeyHasherTest {

    private static final String ALICE_PUB = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
    private static final String ALICE_HASH = "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae";
    private static final String BOB_PUB = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";
    private static final String BOB_HASH = "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4";

    @Test
    void matchesTheGoldenVectors() {
        assertThat(RecipientKeyHasher.hash(ALICE_PUB)).isEqualTo(ALICE_HASH);
        assertThat(RecipientKeyHasher.hash(BOB_PUB)).isEqualTo(BOB_HASH);
    }

    @Test
    void isCaseInsensitiveOnInputAndAlwaysLowercaseOnOutput() {
        assertThat(RecipientKeyHasher.hash(ALICE_PUB.toUpperCase())).isEqualTo(ALICE_HASH);
    }

    @Test
    void hashesTheDECODEDBytesNotTheHexString() {
        // The single most likely reimplementation bug: hashing the ASCII hex instead of the 32 bytes.
        // sha256 of the 64-char ASCII string is a completely different value, so pinning the decoded
        // form here is what stops a "working" but incompatible implementation shipping.
        assertThat(RecipientKeyHasher.hash(ALICE_PUB))
                .isNotEqualTo("f52e1b2fb8b0f4e2a15bd28f9ea6c1d0d0e5c2a3b4c5d6e7f8091a2b3c4d5e6f");
        assertThat(RecipientKeyHasher.hash(ALICE_PUB)).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void rejectsInputThatIsNotA32ByteHexKey() {
        assertThatThrownBy(() -> RecipientKeyHasher.hash("not-hex"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecipientKeyHasher.hash("abcd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
        assertThatThrownBy(() -> RecipientKeyHasher.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_common:test --tests '*RecipientKeyHasherTest'
```

Expected: FAIL — compilation error, `RecipientKeyHasher` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `blockchain_common/src/main/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/RecipientKeyHasher.java`:

```java
package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives a recipient's PUBLIC, on-chain-publishable identifier from their X25519 public key:
 *
 * <pre>recipient_key_hash = sha256( 32 raw bytes decoded from the lowercase-hex public key )</pre>
 *
 * <p>rendered as 64 lowercase hex characters. No salt, no domain-separation prefix, no truncation —
 * this is a public permissionless format, and anyone auditing a document from a block explorer must
 * be able to reproduce a hash with {@code printf %s <pubkey> | xxd -r -p | sha256sum} and nothing
 * else. SHA-256 rather than the SHA3-256 used for the organisation id because WebCrypto implements
 * no SHA-3 member, and the Indexer frontend recomputes this in the browser.
 *
 * <p><b>Publishing this makes a document permanently linkable to its recipients</b> — see
 * docs/onChainFormat.md "Recipient key hashes" and the design doc §3.4. That is an accepted,
 * deliberate trade-off, not an oversight.
 *
 * <p>Not annotated {@code @Service}: a pure static function with no state and no dependencies,
 * matching how {@code Cip170MetadataFactory}'s static helpers are used.
 */
public final class RecipientKeyHasher {

    /** X25519 public keys are 32 bytes, so 64 hex characters. */
    private static final int PUBLIC_KEY_HEX_LENGTH = 64;

    private RecipientKeyHasher() {
    }

    public static String hash(String publicKeyHex) {
        if (publicKeyHex == null || publicKeyHex.length() != PUBLIC_KEY_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "X25519 public key must be %d hex characters, got: %s"
                            .formatted(PUBLIC_KEY_HEX_LENGTH,
                                    publicKeyHex == null ? "null" : String.valueOf(publicKeyHex.length())));
        }
        byte[] publicKey;
        try {
            // Decode FIRST: hashing the hex string instead of the bytes it denotes would produce a
            // plausible-looking but wrong digest that no other implementation would agree with.
            publicKey = HexFormat.of().parseHex(publicKeyHex.toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("X25519 public key is not valid hex: " + e.getMessage(), e);
        }
        return HexFormat.of().formatHex(sha256(publicKey));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_common:test --tests '*RecipientKeyHasherTest'
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add blockchain_common/src/main/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/RecipientKeyHasher.java \
        blockchain_common/src/test/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/RecipientKeyHasherTest.java
git commit -m "feat(blockchain_common): add RecipientKeyHasher with published golden vectors"
```

---

### Task 2: Freeze the hash onto the document slot

The hash is computed once, at upload, from the recipient's stored public key, and stored on the immutable slot row. Publish only ever reads it.

This is what keeps the attested path correct: the 1447 map is frozen and wallet-signed at `prepareDigest`, and `DocumentDispatchRetryJob` re-invokes the same static `toPublishCommand` factory on a retry sweep. Deriving from mutable key rows would let a key deleted between freeze and re-emission change the bytes and invalidate the signature.

**Files:**
- Modify: `document_vault/src/main/java/org/cardanofoundation/lob/app/document_vault/domain/entity/DocumentSlot.java`
- Modify: `document_vault/src/main/java/org/cardanofoundation/lob/app/document_vault/service/VaultDocumentService.java:170-173`
- Create: `document_vault/src/main/resources/db/migration/postgresql/common/V1.7_100_14_4__document_vault_slot_recipient_key_hash.sql`

**Interfaces:**
- Consumes: `RecipientKeyHasher.hash(String)` from Task 1; `KeyRef.publicKey()` (existing).
- Produces: `DocumentSlot.getRecipientKeyHash()` / 5-arg constructor `DocumentSlot(keyId, recipientRef, ephemeralPub, wrappedDek, recipientKeyHash)`. Task 3 reads this in `toPublishCommand`.

- [ ] **Step 1: Count the rows the backfill cannot resolve**

Before writing `NOT NULL`, find out whether any existing slot names a key row that no longer exists. `VaultKeyLookupService`'s javadoc states a dangling `keyId` is an expected, tolerated state, so this is a real possibility, not a hypothetical.

```bash
psql "$REEVE_DB_URL" -c "
SELECT count(*) AS orphan_slots
FROM document_vault_document_slot s
LEFT JOIN document_vault_key k ON k.key_id = s.key_id
LEFT JOIN document_vault_addressbook_entry a ON a.entry_id = s.key_id
WHERE k.key_id IS NULL AND a.entry_id IS NULL;"
```

If the count is **0** (the expected case on any environment where keys have not been deleted), use the `NOT NULL` migration in Step 2 as written.

If the count is **greater than 0**, drop the final `SET NOT NULL` statement from the migration and leave the column nullable, then in Step 4 make `VaultDocumentService` reject a publish whose slot has a null hash with `VaultProblems.unprocessable`. Do not invent a sentinel value — a fake hash would silently occupy a recipient's filter namespace. Record which branch you took in the commit message.

If you cannot reach a database at all, take the nullable branch: it is correct in both cases and only costs one extra guard.

- [ ] **Step 2: Write the migration**

Create `document_vault/src/main/resources/db/migration/postgresql/common/V1.7_100_14_4__document_vault_slot_recipient_key_hash.sql`:

```sql
-- Recipient key hash: sha256 of the recipient's 32-byte X25519 public key, lowercase hex.
--
-- Stored on the slot rather than resolved at publish time on purpose. Slots are already immutable and
-- self-contained (this table deliberately has NO foreign key to the key tables — "a deleted key simply
-- stops being offered"), and the attested publish path freezes and wallet-signs the 1447 metadata map.
-- Re-deriving from mutable key rows on a retry sweep could change the bytes and invalidate that
-- signature; reading a frozen column cannot.
--
-- Unlike key_id and recipient_ref, this column IS exported to IPFS/L1. See docs/onChainFormat.md.
ALTER TABLE document_vault_document_slot
    ADD COLUMN recipient_key_hash VARCHAR(64);

-- Backfill from whichever store the slot's key_id names. sha256(bytea) is built into PostgreSQL >= 11,
-- so this needs no extension. decode(...,'hex') is what makes it hash the 32 DECODED bytes rather than
-- the 64-character hex string - the same distinction RecipientKeyHasher enforces in Java.
UPDATE document_vault_document_slot s
SET recipient_key_hash = encode(sha256(decode(lower(k.public_key), 'hex')), 'hex')
FROM document_vault_key k
WHERE k.key_id = s.key_id
  AND s.recipient_key_hash IS NULL;

UPDATE document_vault_document_slot s
SET recipient_key_hash = encode(sha256(decode(lower(a.public_key), 'hex')), 'hex')
FROM document_vault_addressbook_entry a
WHERE a.entry_id = s.key_id
  AND s.recipient_key_hash IS NULL;

-- Only when Step 1 reported zero orphan slots. If it reported more than zero, delete the next
-- statement and keep the column nullable; VaultDocumentService refuses to publish a null-hash slot.
ALTER TABLE document_vault_document_slot
    ALTER COLUMN recipient_key_hash SET NOT NULL;
```

- [ ] **Step 3: Add the entity field**

In `document_vault/src/main/java/org/cardanofoundation/lob/app/document_vault/domain/entity/DocumentSlot.java`, update the class javadoc and add the field after `wrappedDek`:

```java
/**
 * One recipient slot of an envelope. {@code keyId}/{@code recipientRef} are labels and indexing
 * aids only — never trust anchors (blueprint I6). {@code wrappedDek} is AES-256-GCM-encrypted
 * under an ECDH-derived slot KEK; the server cannot unwrap it (blueprint I5).
 *
 * <p>{@code recipientKeyHash} is the odd one out: it is DERIVED server-side from the recipient's
 * stored public key (never accepted from a client, which could otherwise stamp someone else's hash
 * onto a document) and, unlike every other identifier here, it IS exported to IPFS/L1. It is frozen
 * at upload so the attested publish path's wallet-signed metadata cannot drift on a retry sweep.
 */
@Getter
@Setter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSlot {

    @Column(name = "key_id", nullable = false)
    private String keyId;

    @Column(name = "recipient_ref", nullable = false)
    private String recipientRef;

    @Column(name = "ephemeral_pub", nullable = false, length = 64)
    private String ephemeralPub;

    @Column(name = "wrapped_dek", nullable = false, length = 96)
    private String wrappedDek;

    /** sha256 of the recipient's 32-byte X25519 public key, lowercase hex. Published on-chain. */
    @Column(name = "recipient_key_hash", nullable = false, length = 64)
    private String recipientKeyHash;
}
```

If Step 1 took the nullable branch, use `@Column(name = "recipient_key_hash", length = 64)` (no `nullable = false`).

- [ ] **Step 4: Compute it at upload**

In `VaultDocumentService.java`, replace the slot-construction block (currently at `:170-173`):

```java
        document.setSlots(request.getSlots().stream()
                .map(slot -> new DocumentSlot(slot.getKeyId(), slot.getRecipientRef(),
                        slot.getEphemeralPub(), slot.getWrappedDek()))
                .toList());
```

with:

```java
        // recipientKeyHash is derived from the key we just authorised above, never taken from the
        // request: a client-supplied hash would let an uploader stamp someone else's identifier onto a
        // document and inject it into their Indexer filter. keysById is already loaded and every
        // getKeyId() was proven present and in-organisation by the validation loop above.
        document.setSlots(request.getSlots().stream()
                .map(slot -> new DocumentSlot(slot.getKeyId(), slot.getRecipientRef(),
                        slot.getEphemeralPub(), slot.getWrappedDek(),
                        RecipientKeyHasher.hash(keysById.get(slot.getKeyId()).publicKey())))
                .toList());
```

Add the import:

```java
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.RecipientKeyHasher;
```

**Only if Step 1 took the nullable branch**, additionally add this guard inside `publish(...)`, immediately after the `VaultDocumentStatus.DRAFT` check (currently `:249-252`):

```java
        // Legacy slots predating the recipient_key_hash backfill: the key row was already gone when the
        // migration ran, so no hash could be derived. Publishing would emit a short list and fail the
        // manifest schema at submission time - refuse here, where the caller gets a usable message.
        if (document.getSlots().stream().anyMatch(s -> s.getRecipientKeyHash() == null)) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.SLOT_KEY_INVALID,
                    "Document %s has a slot whose recipient key is no longer registered and cannot be published."
                            .formatted(documentId)));
        }
```

- [ ] **Step 5: Run the module's tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :document_vault:test
```

Expected: existing tests that construct `DocumentSlot` fail to compile with "constructor DocumentSlot cannot be applied to given types". Fix each by passing a fifth argument — use the Task 1 golden hash `300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae` where the value is irrelevant to the assertion. Re-run until green.

- [ ] **Step 6: Commit**

```bash
git add document_vault/
git commit -m "feat(document_vault): freeze recipient key hash onto the document slot at upload"
```

---

### Task 3: Carry the hash through the publish contract

**Files:**
- Modify: `blockchain_common/src/main/java/org/cardanofoundation/lob/app/blockchain_common/domain/events/DocumentPublishCommand.java`
- Modify: `blockchain_common/src/test/java/org/cardanofoundation/lob/app/blockchain_common/domain/events/DocumentPublishCommandPiiTest.java`
- Modify: `blockchain_common/src/test/java/org/cardanofoundation/lob/app/blockchain_common/architecture/NoPiiOnDocumentPublishPathArchTest.java`
- Modify: `blockchain_publisher/src/test/java/org/cardanofoundation/lob/app/blockchain_publisher/architecture/NoPiiOnDocumentPublishPathArchTest.java`
- Modify: `blockchain_publisher/src/main/java/org/cardanofoundation/lob/app/blockchain_publisher/domain/entity/documents/DocumentEntity.java`
- Modify: `blockchain_publisher/src/main/java/org/cardanofoundation/lob/app/blockchain_publisher/service/publish/module/document/DocumentConverter.java`
- Modify: `document_vault/src/main/java/org/cardanofoundation/lob/app/document_vault/service/VaultDocumentService.java:402-415`
- Create: `blockchain_publisher/src/main/resources/db/migration/postgresql/common/V1.6_200_9_1__document_slot_recipient_key_hash.sql`

**Interfaces:**
- Consumes: `DocumentSlot.getRecipientKeyHash()` from Task 2.
- Produces: `DocumentPublishCommand.PublishSlot(String ephemeralPub, String wrappedDek, String recipientKeyHash)` — Task 4's serialiser reads `command.slots().get(i).recipientKeyHash()`.

- [ ] **Step 1: Extend the publish contract**

In `DocumentPublishCommand.java`, replace the `PublishSlot` record and extend the class javadoc:

```java
/**
 * Publish request handed to blockchain_publisher. PII-FREE BY DESIGN (spec B5 #3): the IPFS document
 * and L1 metadata are generated exclusively from these fields, so nothing here may ever carry e-mails,
 * recipient labels, key ids, file names, descriptions, or account ids. Enforced by tests in Task 12.
 *
 * <p>{@code PublishSlot.recipientKeyHash} is the single deliberate exception to that rule, and it is
 * exempted by name in {@code DocumentPublishCommandPiiTest} and both {@code
 * NoPiiOnDocumentPublishPathArchTest}s rather than renamed to slip past their pattern. It is a SHA-256
 * digest of a public key — publishable in the same sense {@code organisationId} is — and it exists so
 * the public Indexer can filter documents by recipient. It DOES make a published document permanently
 * linkable to its recipients; see docs/onChainFormat.md and the design doc §3.4.
 *
 * @param attestationCeremonyId The KERI wallet-attestation ceremony consumed by an attested publish
 *                              (design §5.1, Task 14) — null for a plain publish, which remains the
 *                              default. Carried into blockchain_publisher's dispatch record so the
 *                              binding survives a retry-sweep re-emission (same static factory,
 *                              {@code VaultDocumentService#toPublishCommand}).
 */
@DomainEvent
public record DocumentPublishCommand(String organisationId,
                                     String documentId,
                                     int envelopeVersion,
                                     String contentHash,
                                     String plaintextHash,
                                     String payloadNonce,
                                     String ciphertextBase64,
                                     List<PublishSlot> slots,
                                     String attestationCeremonyId) {

    /**
     * @param recipientKeyHash sha256 of the recipient's X25519 public key, lowercase hex. Exported to
     *                         L1 (NOT to the IPFS envelope). Order-significant: the manifest's
     *                         {@code recipient_key_hashes[i]} must line up with {@code slots[i]}.
     */
    public record PublishSlot(String ephemeralPub, String wrappedDek, String recipientKeyHash) {
    }
}
```

- [ ] **Step 2: Exempt the field in all three PII guards, explicitly**

In `DocumentPublishCommandPiiTest.java`, replace the `PublishSlot` loop at `:24-26`:

```java
        for (var component : DocumentPublishCommand.PublishSlot.class.getRecordComponents()) {
            if (component.getName().equals("recipientKeyHash")) {
                // sha256 of a PUBLIC key — publishable on-chain data, like organisationId above, and
                // the anchor the Indexer's recipient filter matches on. Exempted by name on purpose:
                // renaming the field to dodge the pattern would hide a real format decision. The guard
                // still rejects recipientEmail, recipientLabel, recipientRef and every other variant.
                continue;
            }
            assertFalse(FORBIDDEN.matcher(component.getName()).matches(),
                    "PII-looking field on the publish path: " + component.getName());
        }
```

In **both** `NoPiiOnDocumentPublishPathArchTest.java` files, replace the rule (keeping each file's existing package, imports and javadoc):

```java
    @ArchTest
    static final ArchRule publishPathCarriesNoPii = ArchRuleDefinition.noFields()
            .that().doNotHaveName("recipientKeyHash")
            .should().haveNameMatching("(?i).*(e?mail|recipient|account|label|file_?name|description|display).*")
            .because("recipientKeyHash is a sha256 of a PUBLIC key, deliberately published on-chain so the "
                    + "Indexer can filter by recipient (docs/onChainFormat.md). It is exempted by name rather "
                    + "than renamed, so the decision stays visible in review; every other matching field name "
                    + "is still forbidden.");
```

Add to the imports of both files:

```java
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
```

(already present — no change needed if so).

- [ ] **Step 3: Carry it through the publisher entity and converter**

In `DocumentEntity.java`, add to the nested `Slot` class:

```java
        /** sha256 of the recipient's X25519 public key, lowercase hex. Published in the 1447 manifest. */
        @Column(name = "recipient_key_hash", nullable = false)
        private String recipientKeyHash;
```

Create `blockchain_publisher/src/main/resources/db/migration/postgresql/common/V1.6_200_9_1__document_slot_recipient_key_hash.sql`:

```sql
-- Recipient key hash carried through the dispatch record so the publisher can emit
-- data.recipient_key_hashes without re-resolving key rows. Rows here are transient dispatch state,
-- so unlike document_vault_document_slot there is nothing to backfill from: any row present at
-- migration time is a queued publish whose command predates the field. Default and then drop it,
-- so an in-flight queue does not block the migration.
ALTER TABLE blockchain_publisher_document_slot
    ADD COLUMN recipient_key_hash VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE blockchain_publisher_document_slot
    ALTER COLUMN recipient_key_hash DROP DEFAULT;
```

In `DocumentConverter.java`, update `convertSlots`:

```java
    private List<DocumentEntity.Slot> convertSlots(List<DocumentPublishCommand.PublishSlot> slots) {
        if (slots == null) {
            return List.of();
        }
        return slots.stream()
                .map(slot -> new DocumentEntity.Slot(slot.ephemeralPub(), slot.wrappedDek(), slot.recipientKeyHash()))
                .collect(Collectors.toList());
    }
```

Find the reverse mapping in the same file (the method rebuilding a `DocumentPublishCommand` from a persisted `DocumentEntity`) and add the third argument to its `PublishSlot` construction identically.

- [ ] **Step 4: Pass it through the publish factory**

In `VaultDocumentService.java:411-413`, update `toPublishCommand`:

```java
                document.getSlots().stream()
                        .map(slot -> new DocumentPublishCommand.PublishSlot(
                                slot.getEphemeralPub(), slot.getWrappedDek(), slot.getRecipientKeyHash()))
                        .toList(),
```

- [ ] **Step 5: Build and fix every compile break**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_common:test :document_vault:test :blockchain_publisher:test
```

Expected: several test fixtures fail to compile on the 2-arg `PublishSlot` / `Slot` constructors. Add the third argument to each — use `300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae` and `f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4` for first and second slots where the value is not asserted. `DocumentMetadataSerialiserTest` will still fail its key-set assertion — that is Task 4's job; leave it failing.

Confirm all three PII guards **pass**.

- [ ] **Step 6: Commit**

```bash
git add blockchain_common/ blockchain_publisher/ document_vault/
git commit -m "feat: carry recipientKeyHash through the document publish contract

Exempts the field by name in the three PII guards rather than renaming it,
so the decision to publish a recipient identifier stays visible in review."
```

---

### Task 4: Emit `recipient_key_hashes` in the 1447 manifest

**Files:**
- Modify: `blockchain_common/src/main/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/DocumentMetadataSerialiser.java`
- Modify: `blockchain_common/src/main/resources/document_lob_blockchain_transaction_metadata_schema.json`
- Test: `blockchain_common/src/test/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/DocumentMetadataSerialiserTest.java`

**Interfaces:**
- Consumes: `PublishSlot.recipientKeyHash()` from Task 3.
- Produces: the on-chain wire format Tasks 6–8 parse. `DocumentMetadataSerialiser.VERSION == "1.1"`.

- [ ] **Step 1: Write the failing tests**

In `DocumentMetadataSerialiserTest.java`, update the fixture's slots to carry the golden hashes:

```java
                List.of(
                        new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96),
                                "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae"),
                        new DocumentPublishCommand.PublishSlot("f".repeat(64), "0".repeat(96),
                                "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4")),
```

Replace the key-set assertion at `:82-85`:

```java
        // nothing else may be present in the data section (spec B5 #3 — no PII-capable field can sneak in)
        Set<String> dataKeys = ((List<?>) data.keys()).stream().map(Object::toString).collect(Collectors.toSet());
        assertThat(dataKeys).containsExactlyInAnyOrder(
                "id", "ipfs_cid", "content_hash", "plaintext_hash", "envelope_version", "slot_count",
                "recipient_key_hashes");
```

And add two new tests:

```java
    /**
     * Order is load-bearing: recipient_key_hashes[i] must line up with slots[i] in the IPFS envelope,
     * which is what lets a recipient address their slot directly instead of trial-decrypting all of
     * them. Sorting or deduplicating the list would break that silently.
     */
    @Test
    void emitsRecipientKeyHashesInSlotOrder() {
        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(fixture(), "bafy-cid-1", CREATION_SLOT,
                "org-1", "Acme", "TAX-1", "ISO_4217:CHF", "CH");

        MetadataMap data = (MetadataMap) metadataMap.get("data");
        MetadataList hashes = (MetadataList) data.get("recipient_key_hashes");

        assertThat(hashes.size()).isEqualTo(2);
        assertThat(hashes.getValueAt(0))
                .isEqualTo("300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae");
        assertThat(hashes.getValueAt(1))
                .isEqualTo("f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4");
        assertThat(data.get("slot_count")).isEqualTo(BigInteger.valueOf(hashes.size()));
    }

    @Test
    void declaresMetadataVersion11() {
        // The version bump is what tells a reader whether absent recipient_key_hashes means "this
        // producer predates the field" or "this manifest is malformed".
        assertThat(DocumentMetadataSerialiser.VERSION).isEqualTo("1.1");
    }
```

Add the import `com.bloxbean.cardano.client.metadata.MetadataList;`.

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_common:test --tests '*DocumentMetadataSerialiserTest'
```

Expected: FAIL — `recipient_key_hashes` missing from the key set, `data.get("recipient_key_hashes")` is null, `VERSION` is `"1.0"`.

- [ ] **Step 3: Emit the field**

In `DocumentMetadataSerialiser.java`: change `VERSION` to `"1.1"`, update the javadoc's field list, and add the list after `slot_count`:

```java
    public static final String VERSION = "1.1";
```

```java
        data.put("slot_count", BigInteger.valueOf(command.slots().size()));
        // Order matters: recipient_key_hashes[i] corresponds to slots[i] in the IPFS envelope. Never
        // sort or deduplicate — a recipient uses the index to address their own slot directly.
        MetadataList recipientKeyHashes = MetadataBuilder.createList();
        command.slots().forEach(slot -> recipientKeyHashes.add(slot.recipientKeyHash()));
        data.put("recipient_key_hashes", recipientKeyHashes);
        globalMetadataMap.put("data", data);
```

Add the import `com.bloxbean.cardano.client.metadata.MetadataList;`, and amend the class javadoc's PII paragraph:

```java
 * <p>The {@code data} section carries only id / ipfs_cid / content_hash / plaintext_hash /
 * envelope_version / slot_count / recipient_key_hashes - nothing else. Every field but the last is
 * PII-free in the original sense; recipient_key_hashes is a deliberate, documented exception that
 * makes a published document linkable to its recipients (docs/onChainFormat.md, design doc §3.4).
```

- [ ] **Step 4: Update the JSON schema**

In `document_lob_blockchain_transaction_metadata_schema.json`, add to `definitions`:

```json
    "recipientKeyHashArray": {
      "description": "sha256 of each recipient's X25519 public key, lowercase hex. Index-aligned with the IPFS envelope's slots array, so length must equal slot_count.",
      "type": "array",
      "minItems": 1,
      "items": {
        "$ref": "#/definitions/hexHash64Pattern"
      }
    }
```

Add to `data.properties`:

```json
        "recipient_key_hashes": {
          "$ref": "#/definitions/recipientKeyHashArray"
        }
```

Replace `data.required` and both "six fields" descriptions:

```json
      "required": ["id", "ipfs_cid", "content_hash", "plaintext_hash", "envelope_version", "slot_count", "recipient_key_hashes"],
```

Top-level `description` → `"... The data section is the manifest body: exactly the seven fields below, nothing else."`
`data.description` → `"1447 DOCUMENT manifest body: exactly these seven fields, nothing else. Six are PII-free; recipient_key_hashes deliberately identifies recipients by a hash of their public key (see docs/onChainFormat.md)."`

- [ ] **Step 5: Run tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_common:test :blockchain_publisher:test :document_vault:test
```

Expected: PASS, all three modules. `serialisedManifestValidatesAgainstSchema` and `manifestWithExtraDataFieldFailsSchemaValidation` both still pass, and `DocumentIpfsSerialiserTest` still asserts envelope slots are exactly `["ephemeral_pub", "wrapped_dek"]` — that unchanged test is now the guarantee the hash cannot leak into IPFS.

- [ ] **Step 6: Commit**

```bash
git add blockchain_common/
git commit -m "feat(blockchain_common): emit data.recipient_key_hashes in the 1447 DOCUMENT manifest

Metadata version 1.0 -> 1.1. The list is index-aligned with the IPFS
envelope's slots array and its length equals slot_count."
```

---

### Task 5: Documentation

The user-facing deliverable. `docs/onChainFormat.md` is the canonical description of this format and is currently **wrong** about it — it asserts the format "intentionally contains no personal data" and that slots carry "no recipient identifiers".

**Files:**
- Modify: `docs/onChainFormat.md:430-486`
- Modify: `docs/keri-document-flow.md` §5

**Interfaces:**
- Consumes: the wire format from Task 4.
- Produces: the published contract Tasks 6–10 implement against.

- [ ] **Step 1: Rewrite the Document section**

Replace everything in `docs/onChainFormat.md` from `## Type: Document` up to (not including) `## Glossary` with:

````markdown
## Type: Document

The `DOCUMENT` type anchors an **end-to-end-encrypted document** published by an organisation. The
encrypted envelope itself is stored on IPFS; the on-chain record is a manifest referencing it. The
operator and the public can verify integrity (hashes, CID) but can never read content — decryption
keys exist only on the recipients' devices.

### On-chain manifest (`data`)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Server-assigned document identifier (UUID) |
| `ipfs_cid` | string | Yes | IPFS CID of the encrypted envelope document |
| `content_hash` | string | Yes | SHA-256 of the raw ciphertext bytes (hex) |
| `plaintext_hash` | string | Yes | SHA-256 commitment over the plaintext, computed client-side (hex) |
| `envelope_version` | integer | Yes | Envelope wire-format version |
| `slot_count` | integer | Yes | Number of recipient slots in the referenced envelope |
| `recipient_key_hashes` | array of string | Yes | One SHA-256 recipient key hash per slot (see below). Length equals `slot_count`; entry `i` corresponds to `slots[i]` in the envelope. Present from metadata version `1.1` onward. |

### IPFS envelope document

The document at `ipfs_cid` is JSON with this structure:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `version` | integer | Yes | Envelope wire-format version (currently `1`), matching the manifest's `envelope_version` |
| `type` | string | Yes | Always `"REEVE_ENCRYPTED_DOCUMENT"` |
| `org_id` | string | Yes | Publishing organisation's id, matching the on-chain `org.id` |
| `content_hash` | string | Yes | SHA-256 of the raw ciphertext bytes (hex), matching the manifest |
| `plaintext_hash` | string | Yes | SHA-256 commitment over the plaintext (hex), matching the manifest |
| `payload` | object | Yes | The ciphertext and its nonce (see below) |
| `slots` | array | Yes | One entry per recipient (see below); length equals the manifest's `slot_count` |

#### `payload` object

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `ciphertext` | string | Yes | The AES-256-GCM-encrypted document, base64 |
| `nonce` | string | Yes | The AEAD nonce for `ciphertext` |

#### `slots[]` entry

Each slot holds the material one recipient needs to unwrap the document encryption key, and nothing
else. There are **no recipient identifiers inside the envelope** — recipients locate their slot either
by its index in `recipient_key_hashes` or by trial decryption.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `ephemeral_pub` | string | Yes | Per-slot ephemeral X25519 public key, 32 bytes hex |
| `wrapped_dek` | string | Yes | The document encryption key, AES-256-GCM-wrapped under an ECDH-derived slot KEK |

The organisation-internal identifiers a slot carries inside a Reeve deployment (`key_id`,
`recipient_ref`) are stripped before publication and never appear here or on-chain. Neither do
e-mail addresses, recipient names or labels, or file names.

### Example: IPFS envelope document

```json
{
  "version": 1,
  "type": "REEVE_ENCRYPTED_DOCUMENT",
  "org_id": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
  "content_hash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "plaintext_hash": "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752",
  "payload": {
    "ciphertext": "Y2lwaGVydGV4dA==",
    "nonce": "cccccccccccccccccccccccc"
  },
  "slots": [
    {
      "ephemeral_pub": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
      "wrapped_dek": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    },
    {
      "ephemeral_pub": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "wrapped_dek": "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    }
  ]
}
```

### Recipient key hashes

A recipient is a holder of an X25519 key pair. Their on-chain identifier is:

```
recipient_key_hash = sha256( 32 raw bytes decoded from the lowercase-hex X25519 public key )
```

rendered as 64 lowercase hex characters. No salt, no domain-separation prefix, no truncation — so any
third party can reproduce it from a public key with one command:

```console
$ printf %s 8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a | xxd -r -p | sha256sum
300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae
```

SHA-256 rather than the SHA3-256 used for `org.id`, because readers recompute this in a browser and
WebCrypto implements no SHA-3 member.

**Reference vectors** (the RFC 7748 §6.1 X25519 public keys):

| X25519 public key | `recipient_key_hash` |
|---|---|
| `8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a` | `300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae` |
| `de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f` | `f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4` |

The list is what lets a public indexer answer "which documents are addressed to this key?" without
decrypting anything.

> **Privacy: this makes published documents linkable to their recipients.** A recipient key hash is a
> stable, public, permanent identifier. Anyone holding a person's X25519 public key — and key cards
> carrying public keys are exchanged by design — can compute their hash and enumerate every document
> ever addressed to them, across every organisation, for as long as the chain exists. The hash cannot
> be revoked, rotated away from, or deleted. This is a deliberate trade-off accepted in exchange for
> recipient-side filtering; it replaces this format's earlier property of carrying no recipient
> identifiers at all. Everything else stays as it was: no e-mail addresses, recipient names or labels,
> or file names appear in either the manifest or the envelope, and no content is readable by anyone
> but a key holder.

> **Note on validation**: as with `FUNDING` manifests, several rules are enforced programmatically:
> `org_id` in the IPFS document matching the on-chain `org.id`, `content_hash` matching the decoded
> `payload.ciphertext`, the CID matching the document bytes, `slot_count` matching `slots.length`, and
> `recipient_key_hashes.length` matching `slot_count`.

### Example: Document record

```json
{
  "1447": {
    "org": {
      "id": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
      "name": "Cardano Foundation",
      "currency_id": "ISO_4217:CHF",
      "country_code": "CH",
      "tax_id_number": "CHE-184477354"
    },
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

### Metadata versions

| Version | Change |
|---------|--------|
| `1.0` | Initial `DOCUMENT` manifest. |
| `1.1` | Adds `recipient_key_hashes`. Documents anchored at `1.0` carry no hashes and can never match a recipient filter; the chain is immutable, so they are not backfilled. |
````

- [ ] **Step 2: Note the change in the KERI flow doc**

In `docs/keri-document-flow.md` §5, immediately after the paragraph ending "the wallet's signature commits to those exact bytes.", add:

```markdown
The frozen 1447 map includes `data.recipient_key_hashes`, read from immutable `document_vault_document_slot`
columns written at upload. That is why the hashes are stored rather than derived at publish: re-deriving
them from key rows on a retry sweep could change the frozen bytes and invalidate the wallet's signature.
```

- [ ] **Step 3: Verify the docs match the code**

```bash
grep -n 'recipient_key_hashes' docs/onChainFormat.md blockchain_common/src/main/resources/document_lob_blockchain_transaction_metadata_schema.json
grep -n '300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae' docs/onChainFormat.md blockchain_common/src/test/java/org/cardanofoundation/lob/app/blockchain_common/service_assistance/RecipientKeyHasherTest.java
printf %s 8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a | xxd -r -p | shasum -a 256
```

Expected: the field appears in both docs and schema; the golden hash appears in both docs and test; the shell command prints `300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae`, proving the documented one-liner actually works.

- [ ] **Step 4: Commit**

```bash
git add docs/onChainFormat.md docs/keri-document-flow.md
git commit -m "docs: document recipient key hashes and the IPFS envelope structure

Replaces the claim that the DOCUMENT format carries no recipient
identifiers - it now does - with an explicit privacy note, and expands
the two-sentence envelope summary into a full field-by-field spec."
```

---

# CONSUMER — `reeve-indexing-example`

All commands in Tasks 6–10 run from `/Users/thkammer/Documents/dev/cardano/java/reeve-indexing-example`.

---

### Task 6: Store the list

**Files:**
- Create: `src/main/resources/db/store/postgresql/V1.11__add_document_recipient_key_hashes.sql`
- Modify: `src/main/java/org/cardanofoundation/reeve/indexer/model/entity/DocumentEntity.java`

**Interfaces:**
- Consumes: the wire format from Task 4.
- Produces: `DocumentEntity.getRecipientKeyHashes() → List<String>` and the `recipient_key_hashes text[]` column, used by Tasks 7 and 8.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/store/postgresql/V1.11__add_document_recipient_key_hashes.sql`:

```sql
-- Recipient key hashes from the 1447 DOCUMENT manifest (metadata version 1.1+): sha256 of each
-- recipient's X25519 public key, lowercase hex, index-aligned with the IPFS envelope's slots.
--
-- A Postgres array rather than a child table: the list is short, bounded by slot_count, never queried
-- independently of its document and never mutated after ingest, so a join would cost more than it buys.
--
-- DEFAULT '{}' is what lets pre-1.1 anchors coexist. They carry no hashes and can never match a
-- filter, which is correct - the chain is immutable and they cannot be backfilled.
ALTER TABLE reeve_document
    ADD COLUMN recipient_key_hashes text[] NOT NULL DEFAULT '{}';

-- GIN serves the `:hash = ANY(recipient_key_hashes)` containment lookup the recipient filter issues.
CREATE INDEX IF NOT EXISTS idx_reeve_document_recipient_key_hashes
    ON reeve_document USING GIN (recipient_key_hashes);
```

- [ ] **Step 2: Map it on the entity**

In `DocumentEntity.java`, add after the `slotCount` field:

```java
    /**
     * Recipient key hashes from the manifest, index-aligned with the envelope's slots. Empty for
     * pre-1.1 anchors, which carry no hashes and therefore never match a recipient filter.
     */
    @Type(ListArrayType.class)
    @Column(name = "recipient_key_hashes", columnDefinition = "text[]")
    @Builder.Default
    private List<String> recipientKeyHashes = new ArrayList<>();
```

Add the imports:

```java
import java.util.ArrayList;
import java.util.List;

import io.hypersistence.utils.hibernate.type.array.ListArrayType;
import org.hibernate.annotations.Type;
```

`@Builder.Default` is required — the class is `@Builder`, and without it Lombok drops the initialiser and the field arrives null.

- [ ] **Step 3: Verify the app starts and the migration applies**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*DocumentProcessorTest*'
```

Expected: compiles; existing `DocumentProcessor` tests still pass (the field defaults to empty).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/store/postgresql/V1.11__add_document_recipient_key_hashes.sql \
        src/main/java/org/cardanofoundation/reeve/indexer/model/entity/DocumentEntity.java
git commit -m "feat: store recipient_key_hashes as an indexed text[] on reeve_document"
```

---

### Task 7: Ingest the list

**Files:**
- Modify: `src/main/java/org/cardanofoundation/reeve/indexer/processor/DocumentProcessor.java`
- Test: `src/test/java/org/cardanofoundation/reeve/indexer/processor/DocumentProcessorTest.java`

**Interfaces:**
- Consumes: `DocumentEntity.builder().recipientKeyHashes(List<String>)` from Task 6.
- Produces: populated rows for Task 8's filter.

- [ ] **Step 1: Write the failing tests**

Add to `DocumentProcessorTest.java`. Note the file's existing conventions, which these follow: `metadata(String dataJson)` takes raw JSON text, `validData()` returns a JSON **string** declaring `"slot_count":3`, and every assertion goes through an `ArgumentCaptor` on `documentRepository.save`. The helper below rewrites `validData()`'s `slot_count` and appends the array, so the two always agree unless a test deliberately breaks them.

```java
    private static final String HASH_A = "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae";
    private static final String HASH_B = "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4";

    /** validData() with its slot_count replaced and a recipient_key_hashes array spliced in. */
    private static String dataWithHashes(int slotCount, String hashesJson) {
        return validData()
                .replace("\"slot_count\":3", "\"slot_count\":" + slotCount)
                .replaceFirst("}\\s*$", ",\"recipient_key_hashes\":" + hashesJson + "}");
    }

    private DocumentEntity processAndCapture(String dataJson) throws Exception {
        processor.process(metadata(dataJson));
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void storesRecipientKeyHashesInManifestOrder() throws Exception {
        DocumentEntity entity = processAndCapture(
                dataWithHashes(2, "[\"" + HASH_A + "\",\"" + HASH_B + "\"]"));

        assertEquals(CheckStatus.PASS, entity.getManifestCheck());
        // Order preserved exactly — index i must still line up with the envelope's slots[i].
        assertEquals(List.of(HASH_A, HASH_B), entity.getRecipientKeyHashes());
    }

    @Test
    void treatsAnAbsentRecipientKeyHashesFieldAsAValidPre11Anchor() throws Exception {
        // Documents anchored before metadata version 1.1 have no such field. They must keep indexing
        // normally as PASS — an empty list simply never matches a recipient filter. validData() has
        // no recipient_key_hashes, so this is also asserted implicitly by every pre-existing test.
        DocumentEntity entity = processAndCapture(validData());

        assertEquals(CheckStatus.PASS, entity.getManifestCheck());
        assertTrue(entity.getRecipientKeyHashes().isEmpty());
    }

    @Test
    void rejectsAMalformedRecipientKeyHash() throws Exception {
        DocumentEntity entity = processAndCapture(
                dataWithHashes(2, "[\"" + HASH_A + "\",\"NOT-A-HASH\"]"));

        assertEquals(CheckStatus.FAIL, entity.getManifestCheck());
        assertEquals(DocumentVerdict.MALFORMED_MANIFEST, entity.getVerdict());
    }

    @Test
    void rejectsARecipientKeyHashListWhoseLengthDisagreesWithSlotCount() throws Exception {
        // The two must agree, or index alignment with the envelope's slots claims nothing.
        DocumentEntity entity = processAndCapture(
                dataWithHashes(3, "[\"" + HASH_A + "\",\"" + HASH_B + "\"]"));

        assertEquals(CheckStatus.FAIL, entity.getManifestCheck());
    }

    @Test
    void rejectsARecipientKeyHashesValueThatIsNotAnArray() throws Exception {
        DocumentEntity entity = processAndCapture(dataWithHashes(1, "\"" + HASH_A + "\""));

        assertEquals(CheckStatus.FAIL, entity.getManifestCheck());
    }
```

Add the imports `java.util.List` and `static org.junit.jupiter.api.Assertions.assertTrue` if not already present.

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*DocumentProcessorTest'
```

Expected: FAIL — hashes are never read, so the list is always empty and malformed input still passes.

- [ ] **Step 3: Parse and validate**

In `DocumentProcessor.java`, add to `toEntity` — extend the `valid` expression:

```java
        boolean valid = organisationId != null && data != null && data.isObject()
                && isNonBlankText(data.get("id"))
                && isText(data.get("ipfs_cid"), CID_SHAPE)
                && isText(data.get("content_hash"), HASH_64_HEX)
                && isText(data.get("plaintext_hash"), HASH_64_HEX)
                && isPositiveInt(data.get("envelope_version"))
                && isPositiveInt(data.get("slot_count"))
                && isValidRecipientKeyHashes(data.get("recipient_key_hashes"), data.get("slot_count"));
```

and populate the field inside the existing `if (data != null && data.isObject())` block:

```java
                    .slotCount(intOrNull(data.get("slot_count")))
                    .recipientKeyHashes(recipientKeyHashesOrEmpty(data.get("recipient_key_hashes")));
```

Add the two helpers alongside the existing ones:

```java
    /**
     * Absent is VALID: documents anchored before metadata version 1.1 carry no recipient_key_hashes,
     * and a verifier that condemned them would mark most of the chain's history malformed. Present
     * means it must be a well-formed array of 64-hex hashes whose length equals slot_count — without
     * that equality, index alignment with the envelope's slots claims nothing.
     */
    private static boolean isValidRecipientKeyHashes(JsonNode node, JsonNode slotCount) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (!node.isArray()) {
            return false;
        }
        for (JsonNode entry : node) {
            if (!isText(entry, HASH_64_HEX)) {
                return false;
            }
        }
        return isPositiveInt(slotCount) && node.size() == slotCount.asInt();
    }

    /** Order is preserved verbatim: entry i corresponds to the envelope's slots[i]. */
    private static List<String> recipientKeyHashesOrEmpty(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> hashes = new ArrayList<>();
        for (JsonNode entry : node) {
            if (isText(entry, HASH_64_HEX)) {
                hashes.add(entry.asText());
            }
        }
        return hashes;
    }
```

Add imports `java.util.ArrayList` and `java.util.List`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*DocumentProcessorTest'
```

Expected: PASS, including all pre-existing cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/cardanofoundation/reeve/indexer/processor/DocumentProcessor.java \
        src/test/java/org/cardanofoundation/reeve/indexer/processor/DocumentProcessorTest.java
git commit -m "feat: ingest recipient_key_hashes, tolerating pre-1.1 anchors that have none"
```

---

### Task 8: Filter by recipient key hash

`DocumentService.list` currently branches four ways over `orgId × verdict`. A third dimension makes that eight, so the branching is collapsed into one query.

**Files:**
- Modify: `src/main/java/org/cardanofoundation/reeve/indexer/model/repository/DocumentRepository.java`
- Modify: `src/main/java/org/cardanofoundation/reeve/indexer/service/DocumentService.java:31-65`
- Modify: `src/main/java/org/cardanofoundation/reeve/indexer/controller/DocumentController.java:37-45`
- Modify: `src/main/java/org/cardanofoundation/reeve/indexer/model/view/document/DocumentView.java`
- Test: `src/test/java/org/cardanofoundation/reeve/indexer/service/DocumentServiceTest.java`, `src/test/java/org/cardanofoundation/reeve/indexer/controller/DocumentControllerTest.java`

**Interfaces:**
- Consumes: `DocumentEntity.getRecipientKeyHashes()` from Task 6.
- Produces: `DocumentService.list(String orgId, DocumentVerdict verdict, String recipientKeyHash, int page, int size, String sort)`; `GET /api/v1/documents?recipientKeyHash=<64-hex>`; `DocumentView.recipientKeyHashes`.

- [ ] **Step 1: Write the failing tests**

Add to `DocumentServiceTest.java`:

```java
    private static final String HASH_A = "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae";
    private static final String HASH_B = "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4";

    @Test
    void filtersByRecipientKeyHash() {
        DocumentListResponse response = documentService.list(null, null, HASH_A, 0, 20, "slot,desc");

        assertThat(response.content()).isNotEmpty();
        assertThat(response.content()).allSatisfy(
                view -> assertThat(view.recipientKeyHashes()).contains(HASH_A));
    }

    @Test
    void returnsNothingForAKeyHashNoDocumentIsAddressedTo() {
        DocumentListResponse response = documentService.list(null, null, "0".repeat(64), 0, 20, "slot,desc");

        assertThat(response.content()).isEmpty();
        assertThat(response.total()).isZero();
    }

    @Test
    void combinesTheRecipientFilterWithOrgAndVerdict() {
        DocumentListResponse response =
                documentService.list("org-1", DocumentVerdict.VERIFIED, HASH_B, 0, 20, "slot,desc");

        assertThat(response.content()).allSatisfy(view -> {
            assertThat(view.organisationId()).isEqualTo("org-1");
            assertThat(view.verdict()).isEqualTo(DocumentVerdict.VERIFIED);
            assertThat(view.recipientKeyHashes()).contains(HASH_B);
        });
    }

    /** The collapse of the four-way branching must not change existing behaviour. */
    @Test
    void preservesTheExistingOrgAndVerdictBehaviourWithNoRecipientFilter() {
        DocumentListResponse byOrg = documentService.list("org-1", null, null, 0, 20, "slot,desc");
        DocumentListResponse byVerdict = documentService.list(null, DocumentVerdict.VERIFIED, null, 0, 20, "slot,desc");
        DocumentListResponse unfiltered = documentService.list(null, null, null, 0, 20, "slot,desc");

        assertThat(byOrg.content()).allSatisfy(v -> assertThat(v.organisationId()).isEqualTo("org-1"));
        assertThat(byVerdict.content()).allSatisfy(v -> assertThat(v.verdict()).isEqualTo(DocumentVerdict.VERIFIED));
        assertThat(unfiltered.total()).isGreaterThanOrEqualTo(byOrg.total());
    }
```

Add to `DocumentControllerTest.java`:

```java
    @Test
    void acceptsAWellFormedRecipientKeyHash() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .param("recipientKeyHash", "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAMalformedRecipientKeyHash() throws Exception {
        mockMvc.perform(get("/api/v1/documents").param("recipientKeyHash", "nope"))
                .andExpect(status().isBadRequest());
        // Uppercase is malformed too: the on-chain format is lowercase hex, so accepting mixed case
        // would silently return nothing rather than telling the caller their input was wrong.
        mockMvc.perform(get("/api/v1/documents")
                        .param("recipientKeyHash", "300C9C9603B92A4B39ED3958BF9240114804DB4FD373012C0CA47432D63425AE"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*DocumentServiceTest' --tests '*DocumentControllerTest'
```

Expected: FAIL — `list` takes five arguments, `DocumentView` has no `recipientKeyHashes`.

- [ ] **Step 3: Add the single filtering query**

In `DocumentRepository.java`, add (keeping the four existing `findBy…` methods — other call sites use them):

```java
    /**
     * The one query behind the public listing: three independent, independently-optional filters.
     * Four nullable-parameter branches beat 2^3 named methods, and `= ANY(...)` is what the GIN index
     * on recipient_key_hashes serves.
     *
     * <p>Native rather than JPQL because Postgres array containment has no portable JPQL spelling.
     * CAST(:recipientKeyHash AS text) is required, not decoration: without it Postgres cannot infer a
     * type for the bind parameter in the IS NULL branch and fails with "could not determine data type".
     */
    @Query(value = """
            SELECT * FROM reeve_document d
            WHERE (CAST(:orgId AS text) IS NULL OR d.organisation_id = CAST(:orgId AS text))
              AND (CAST(:verdict AS text) IS NULL OR d.verdict = CAST(:verdict AS text))
              AND (CAST(:recipientKeyHash AS text) IS NULL
                   OR CAST(:recipientKeyHash AS text) = ANY(d.recipient_key_hashes))
            """,
            countQuery = """
            SELECT count(*) FROM reeve_document d
            WHERE (CAST(:orgId AS text) IS NULL OR d.organisation_id = CAST(:orgId AS text))
              AND (CAST(:verdict AS text) IS NULL OR d.verdict = CAST(:verdict AS text))
              AND (CAST(:recipientKeyHash AS text) IS NULL
                   OR CAST(:recipientKeyHash AS text) = ANY(d.recipient_key_hashes))
            """,
            nativeQuery = true)
    Page<DocumentEntity> search(@Param("orgId") String orgId,
            @Param("verdict") String verdict,
            @Param("recipientKeyHash") String recipientKeyHash,
            Pageable pageable);
```

Add imports `org.springframework.data.jpa.repository.Query` and `org.springframework.data.repository.query.Param`.

- [ ] **Step 4: Collapse the service branching**

In `DocumentService.java`, replace the whole body of `list` and update `parseSort`:

```java
    public DocumentListResponse list(String orgId, DocumentVerdict verdict, String recipientKeyHash,
            int page, int size, String sort) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page),
                Math.min(Math.max(1, size), 200), parseSort(sort));
        Page<DocumentEntity> result = documentRepository.search(orgId,
                verdict != null ? verdict.name() : null, recipientKeyHash, pageRequest);
        return new DocumentListResponse(result.getContent().stream()
                .map(e -> DocumentView.from(e, resolveIdentities(e))).toList(),
                result.getTotalElements(), result.getTotalPages(), result.getNumber(),
                result.getSize());
    }
```

`parseSort` must now emit **column** names, because Spring appends the `ORDER BY` to a native query verbatim and `blockTime`/`createdAt` are not columns — sorting by either would fail at runtime with "column does not exist". Replace the `SORTABLE` constant and `parseSort` together, keeping the existing fallback semantics exactly:

```java
    /**
     * Sort fields are whitelisted — everything else silently falls back (no SQL surprises). The VALUES
     * are column names, not entity properties: `search` is a native query, so Spring appends this
     * ORDER BY straight into raw SQL. The keys stay the API's property names so the public contract
     * (`?sort=blockTime,asc`) is unchanged.
     */
    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "slot", "slot",
            "blockTime", "block_time",
            "createdAt", "created_at");
```

```java
    private Sort parseSort(String sort) {
        String field = "slot";
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String requestedField = parts[0].trim();
            // An unwhitelisted field falls back to the default field AND direction together —
            // a caller-supplied direction paired with a rejected field must not leak through.
            String column = SORTABLE_COLUMNS.get(requestedField);
            if (column != null) {
                field = column;
                if (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())) {
                    direction = Sort.Direction.ASC;
                }
            }
        }
        return Sort.by(direction, field);
    }
```

Add `java.util.Map`; drop `java.util.Set` if nothing else in the file uses it.

- [ ] **Step 5: Add the controller parameter and the view field**

In `DocumentController.java`:

```java
    @Operation(summary = "Paged index of published documents with verification verdicts")
    @GetMapping
    public ResponseEntity<DocumentListResponse> list(
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) DocumentVerdict verdict,
            // sha256 of a recipient's X25519 public key, lowercase hex (docs/onChainFormat.md).
            // Lowercase-only on purpose: the on-chain values are lowercase, so silently accepting
            // uppercase would return an empty page instead of telling the caller their input is wrong.
            @RequestParam(required = false) @Pattern(regexp = "^[0-9a-f]{64}$") String recipientKeyHash,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "slot,desc") String sort) {
        return ResponseEntity.ok(documentService.list(orgId, verdict, recipientKeyHash, page, size, sort));
    }
```

Add `@Validated` to the class annotations and import `jakarta.validation.constraints.Pattern` plus `org.springframework.validation.annotation.Validated`. If the project has no handler mapping `ConstraintViolationException` to 400, add one to its existing `@RestControllerAdvice`; verify with the Step 2 test rather than assuming.

In `DocumentView.java`, add `List<String> recipientKeyHashes` after `slotCount` and pass `e.getRecipientKeyHashes()` in `from`.

- [ ] **Step 6: Run tests to verify they pass**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
```

Expected: PASS for the whole suite. If sorting by `blockTime` fails with "column ... does not exist", Step 4's column mapping was not applied everywhere. If the native query fails with "could not determine data type of parameter", a `CAST(... AS text)` is missing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/cardanofoundation/reeve/indexer/ src/test/java/org/cardanofoundation/reeve/indexer/
git commit -m "feat: filter documents by recipientKeyHash

Collapses list()'s four-way orgId x verdict branching into one
nullable-parameter query rather than growing it to eight."
```

---

### Task 9: Hash a public key in the browser

**Files:**
- Create: `frontend/src/libs/document-vault-crypto/recipientKeyHash.ts`
- Test: `frontend/src/libs/document-vault-crypto/recipientKeyHash.spec.ts`

**Interfaces:**
- Consumes: `hexToBytes`, `bytesToHex` from `./codecs`.
- Produces: `hashPublicKey(publicKeyHex: string): Promise<string>` and `PUBLIC_KEY_HEX_REGEX`, used by Task 10.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/libs/document-vault-crypto/recipientKeyHash.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'

import { PUBLIC_KEY_HEX_REGEX, hashPublicKey } from './recipientKeyHash'

/**
 * These vectors are the RFC 7748 section 6.1 X25519 public keys and are asserted identically in
 * cf-reeve-platform's RecipientKeyHasherTest.java and published in docs/onChainFormat.md. The whole
 * feature rests on the two implementations agreeing; if this file and that one ever disagree, the
 * filter silently matches nothing.
 */
const ALICE_PUB = '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a'
const ALICE_HASH = '300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae'
const BOB_PUB = 'de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f'
const BOB_HASH = 'f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4'

describe('hashPublicKey', () => {
  it('matches the golden vectors shared with the platform', async () => {
    await expect(hashPublicKey(ALICE_PUB)).resolves.toBe(ALICE_HASH)
    await expect(hashPublicKey(BOB_PUB)).resolves.toBe(BOB_HASH)
  })

  it('accepts uppercase input and always returns lowercase', async () => {
    await expect(hashPublicKey(ALICE_PUB.toUpperCase())).resolves.toBe(ALICE_HASH)
  })

  it('hashes the decoded bytes, not the hex string', async () => {
    // sha256 over the 64-char ASCII hex is a different digest entirely. Pinning the length and shape
    // is what catches an implementation that "works" but agrees with nothing else.
    const hash = await hashPublicKey(ALICE_PUB)
    expect(hash).toHaveLength(64)
    expect(hash).toMatch(/^[0-9a-f]{64}$/)
  })

  it('rejects anything that is not a 32-byte hex key', async () => {
    await expect(hashPublicKey('not-hex')).rejects.toThrow()
    await expect(hashPublicKey('abcd')).rejects.toThrow()
    await expect(hashPublicKey('')).rejects.toThrow()
  })

  it('exposes a regex the UI can validate input with before hashing', () => {
    expect(PUBLIC_KEY_HEX_REGEX.test(ALICE_PUB)).toBe(true)
    expect(PUBLIC_KEY_HEX_REGEX.test(ALICE_PUB.toUpperCase())).toBe(true)
    expect(PUBLIC_KEY_HEX_REGEX.test('abcd')).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npx vitest run src/libs/document-vault-crypto/recipientKeyHash.spec.ts
```

Expected: FAIL — cannot resolve `./recipientKeyHash`.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/libs/document-vault-crypto/recipientKeyHash.ts`:

```ts
import { bytesToHex, hexToBytes } from './codecs'

/**
 * A recipient's on-chain identifier:
 *
 *   recipient_key_hash = sha256( 32 raw bytes decoded from the lowercase-hex X25519 public key )
 *
 * rendered as 64 lowercase hex characters. This is a PUBLISHED wire format (docs/onChainFormat.md)
 * reimplemented from cf-reeve-platform's RecipientKeyHasher.java; the golden vectors in the spec file
 * are what keep the two in agreement. SHA-256 rather than SHA3-256 because WebCrypto has no SHA-3.
 *
 * Takes the PUBLIC key only. Filtering never needs, derives, or touches a private scalar.
 */
export const PUBLIC_KEY_HEX_REGEX = /^[0-9a-fA-F]{64}$/

export const hashPublicKey = async (publicKeyHex: string): Promise<string> => {
  if (!PUBLIC_KEY_HEX_REGEX.test(publicKeyHex)) {
    throw new Error('An X25519 public key must be 64 hexadecimal characters.')
  }
  // Decode first: hashing the hex string instead of the bytes it denotes yields a plausible but
  // wrong digest that the platform would never produce.
  const digest = await crypto.subtle.digest('SHA-256', hexToBytes(publicKeyHex.toLowerCase()))

  return bytesToHex(new Uint8Array(digest))
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend && npx vitest run src/libs/document-vault-crypto/recipientKeyHash.spec.ts
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/libs/document-vault-crypto/recipientKeyHash.ts \
        frontend/src/libs/document-vault-crypto/recipientKeyHash.spec.ts
git commit -m "feat(frontend): hash an X25519 public key to its recipient key hash"
```

---

### Task 10: "Filter for my documents"

**Files:**
- Create: `frontend/src/modules/public-documents/components/MyDocumentsFilter/MyDocumentsFilter.component.tsx`
- Test: `frontend/src/modules/public-documents/components/MyDocumentsFilter/MyDocumentsFilter.spec.tsx`
- Modify: `frontend/src/modules/public-documents/constants/documents.consts.ts`
- Modify: `frontend/src/modules/public-documents/hooks/usePublicDocuments.ts`
- Modify: `frontend/src/modules/public-documents/view/ViewPublicDocuments.component.tsx:188-216`
- Modify: `frontend/src/libs/api-connectors/backend-connector-reeve/api/documents/documentsApi.ts`
- Modify: `frontend/src/libs/api-connectors/backend-connector-reeve/api/documents/documentsApi.types.ts`

**Interfaces:**
- Consumes: `hashPublicKey`, `PUBLIC_KEY_HEX_REGEX` from Task 9; `deriveX25519PublicKeyFromPrf`, `evaluatePrf`, `isPasskeySupported` from `libs/document-vault-crypto/passkey`; `recipientKeyHash` on the API from Task 8.
- Produces: the finished feature.

- [ ] **Step 1: Wire the parameter through the API client**

In `documentsApi.types.ts`, add `recipient_key_hashes: string[]` to `DocumentView` (after `slot_count`) and `recipientKeyHash?: string` to `GetDocumentsParams`.

In `documentsApi.ts`:

```ts
    const { orgId, verdict, recipientKeyHash, page, size, sort } = params
```

and, after the `verdict` line:

```ts
    if (recipientKeyHash) queryParams.push(`recipientKeyHash=${encodeURIComponent(recipientKeyHash)}`)
```

- [ ] **Step 2: Add the copy**

Append to `documents.consts.ts`:

```ts
// "Filter for my documents": the user presents a PUBLIC key, the browser hashes it, and the list is
// filtered by that hash. No private key is ever derived, requested or transmitted — and nothing is
// persisted, matching the decrypt panel's stance that key material never outlives the page.
export const MY_DOCUMENTS_FILTER_BUTTON_LABEL = 'Filter for my documents'
export const MY_DOCUMENTS_FILTER_CLEAR_LABEL = 'Clear filter'
export const MY_DOCUMENTS_SOURCE_SELECTOR_LABEL = 'Key source'
export const MY_DOCUMENTS_SOURCE_PASSKEY = 'Passkey'
export const MY_DOCUMENTS_SOURCE_RAW = 'Paste public key'
export const MY_DOCUMENTS_PASSKEY_DESCRIPTION =
  'Unlock with the passkey your key was issued from. Only your public key is derived — the private key never leaves your device and is not needed to filter.'
export const MY_DOCUMENTS_PASSKEY_UNLOCK_BUTTON_LABEL = 'Unlock with passkey'
export const MY_DOCUMENTS_RAW_KEY_LABEL = 'X25519 public key (64 hex characters)'
export const MY_DOCUMENTS_USE_KEY_BUTTON_LABEL = 'Apply'
export const MY_DOCUMENTS_ACTIVE_PREFIX = 'Showing documents addressed to'
export const MY_DOCUMENTS_INVALID_KEY_MESSAGE = 'An X25519 public key must be 64 hexadecimal characters.'
export const MY_DOCUMENTS_PASSKEY_UNSUPPORTED_MESSAGE =
  'This browser or device cannot use passkeys. Paste your public key instead.'
export const MY_DOCUMENTS_PASSKEY_FAILED_MESSAGE =
  'Could not read a key from that passkey. Paste your public key instead.'

// Shown when a recipient filter is active and matched nothing. It must say why a correct key can
// still return nothing, or correct behaviour reads as a bug.
export const DOCUMENTS_NO_RECIPIENT_MATCH_MESSAGE =
  'No published document is addressed to this key. Documents anchored before metadata version 1.1 carry no recipient hashes and can never match.'
```

- [ ] **Step 3: Build the component**

Create `frontend/src/modules/public-documents/components/MyDocumentsFilter/MyDocumentsFilter.component.tsx`:

```tsx
import { useState } from 'react'

import { Alert, Box, Button, Chip, TextField, ToggleButton, ToggleButtonGroup, Typography, useTheme } from '@mui/material'

import { deriveX25519PublicKeyFromPrf, evaluatePrf, isPasskeySupported } from 'libs/document-vault-crypto/passkey'
import { PUBLIC_KEY_HEX_REGEX, hashPublicKey } from 'libs/document-vault-crypto/recipientKeyHash'
import {
  MY_DOCUMENTS_ACTIVE_PREFIX,
  MY_DOCUMENTS_FILTER_BUTTON_LABEL,
  MY_DOCUMENTS_FILTER_CLEAR_LABEL,
  MY_DOCUMENTS_INVALID_KEY_MESSAGE,
  MY_DOCUMENTS_PASSKEY_DESCRIPTION,
  MY_DOCUMENTS_PASSKEY_FAILED_MESSAGE,
  MY_DOCUMENTS_PASSKEY_UNLOCK_BUTTON_LABEL,
  MY_DOCUMENTS_PASSKEY_UNSUPPORTED_MESSAGE,
  MY_DOCUMENTS_RAW_KEY_LABEL,
  MY_DOCUMENTS_SOURCE_PASSKEY,
  MY_DOCUMENTS_SOURCE_RAW,
  MY_DOCUMENTS_SOURCE_SELECTOR_LABEL,
  MY_DOCUMENTS_USE_KEY_BUTTON_LABEL
} from 'modules/public-documents/constants/documents.consts'

type Props = {
  recipientKeyHash: string | null
  onRecipientKeyHashChange: (hash: string | null) => void
}

type KeySource = 'passkey' | 'raw'

/**
 * Lets a recipient narrow the public list to documents addressed to them. Mirrors DecryptPanel's
 * passkey|raw source toggle deliberately — it is the same choice users already know from decrypting.
 *
 * The crucial difference from DecryptPanel: this only ever handles the PUBLIC key. The passkey path
 * calls deriveX25519PublicKeyFromPrf (which zeroes the seed and returns the public half alone), and
 * the raw field asks for a public key, so nothing secret is typed, held or sent. The resulting hash
 * lives in React state only and is gone on reload.
 */
export const MyDocumentsFilter = ({ recipientKeyHash, onRecipientKeyHashChange }: Props) => {
  const theme = useTheme()
  const [isOpen, setIsOpen] = useState(false)
  const [source, setSource] = useState<KeySource>('passkey')
  const [rawKeyInput, setRawKeyInput] = useState('')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [isBusy, setIsBusy] = useState(false)

  const apply = async (publicKeyHex: string) => {
    const hash = await hashPublicKey(publicKeyHex)
    onRecipientKeyHashChange(hash)
    setIsOpen(false)
    setRawKeyInput('')
    setErrorMessage(null)
  }

  const handleUnlockPasskey = async () => {
    setErrorMessage(null)
    if (!isPasskeySupported()) {
      setErrorMessage(MY_DOCUMENTS_PASSKEY_UNSUPPORTED_MESSAGE)
      setSource('raw')

      return
    }
    setIsBusy(true)
    try {
      const { prfOutput } = await evaluatePrf()
      await apply(await deriveX25519PublicKeyFromPrf(prfOutput))
    } catch {
      // Cancelled prompt, no PRF support on the authenticator, no matching credential — all land
      // here, and all have the same remedy: paste the public key instead.
      setErrorMessage(MY_DOCUMENTS_PASSKEY_FAILED_MESSAGE)
    } finally {
      setIsBusy(false)
    }
  }

  const handleUseRawKey = async () => {
    setErrorMessage(null)
    if (!PUBLIC_KEY_HEX_REGEX.test(rawKeyInput.trim())) {
      setErrorMessage(MY_DOCUMENTS_INVALID_KEY_MESSAGE)

      return
    }
    setIsBusy(true)
    try {
      await apply(rawKeyInput.trim())
    } catch {
      setErrorMessage(MY_DOCUMENTS_INVALID_KEY_MESSAGE)
    } finally {
      setIsBusy(false)
    }
  }

  if (recipientKeyHash) {
    return (
      <Box alignItems="center" display="flex" gap={1}>
        <Chip
          label={`${MY_DOCUMENTS_ACTIVE_PREFIX} ${recipientKeyHash.slice(0, 8)}…${recipientKeyHash.slice(-8)}`}
          variant="outlined"
        />
        <Button size="small" onClick={() => onRecipientKeyHashChange(null)}>
          {MY_DOCUMENTS_FILTER_CLEAR_LABEL}
        </Button>
      </Box>
    )
  }

  return (
    <Box display="flex" flexDirection="column" gap={2}>
      <Box>
        <Button variant="outlined" onClick={() => setIsOpen((open) => !open)}>
          {MY_DOCUMENTS_FILTER_BUTTON_LABEL}
        </Button>
      </Box>

      {isOpen && (
        <Box display="flex" flexDirection="column" gap={2}>
          <Box display="flex" flexDirection="column" gap={0.75}>
            <Typography color={theme.palette.text.secondary} variant="caption">
              {MY_DOCUMENTS_SOURCE_SELECTOR_LABEL}
            </Typography>
            <ToggleButtonGroup
              aria-label={MY_DOCUMENTS_SOURCE_SELECTOR_LABEL}
              color="primary"
              disabled={isBusy}
              exclusive
              size="small"
              value={source}
              onChange={(_, value: KeySource | null) => value && setSource(value)}>
              <ToggleButton value="passkey">{MY_DOCUMENTS_SOURCE_PASSKEY}</ToggleButton>
              <ToggleButton value="raw">{MY_DOCUMENTS_SOURCE_RAW}</ToggleButton>
            </ToggleButtonGroup>
          </Box>

          {source === 'passkey' && (
            <Box display="flex" flexDirection="column" gap={1}>
              <Typography color={theme.palette.text.secondary} variant="body2">
                {MY_DOCUMENTS_PASSKEY_DESCRIPTION}
              </Typography>
              <Box>
                <Button disabled={isBusy} variant="contained" onClick={() => void handleUnlockPasskey()}>
                  {MY_DOCUMENTS_PASSKEY_UNLOCK_BUTTON_LABEL}
                </Button>
              </Box>
            </Box>
          )}

          {source === 'raw' && (
            <Box alignItems="center" display="flex" gap={1}>
              <TextField
                autoComplete="off"
                fullWidth
                label={MY_DOCUMENTS_RAW_KEY_LABEL}
                size="small"
                value={rawKeyInput}
                onChange={(event) => setRawKeyInput(event.target.value)}
              />
              <Button disabled={!rawKeyInput || isBusy} variant="outlined" onClick={() => void handleUseRawKey()}>
                {MY_DOCUMENTS_USE_KEY_BUTTON_LABEL}
              </Button>
            </Box>
          )}

          {errorMessage && <Alert severity="error">{errorMessage}</Alert>}
        </Box>
      )}
    </Box>
  )
}
```

Check `evaluatePrf`'s real signature at `frontend/src/libs/document-vault-crypto/passkey.ts:38` before finalising — if it requires arguments (e.g. an allow-list or a `CredentialsGetter`), pass what `DecryptPanel.hooks.ts` passes at its call site.

- [ ] **Step 4: Wire the hook**

In `usePublicDocuments.ts`, add the state and pass it through:

```ts
  // In-memory only: no localStorage, no sessionStorage. A recipient key hash is a public identifier,
  // but persisting it would leave a durable, correlatable trace on the machine — the decrypt panel
  // keeps nothing either, and this filter follows it.
  const [recipientKeyHash, setRecipientKeyHashValue] = useState<string | null>(null)

  const setRecipientKeyHash = (value: string | null) => {
    handlePagination(0, rowsPerPage)
    setRecipientKeyHashValue(value)
  }
```

```ts
  const { documents, isFetching, isError } = useGetDocumentsModel({
    orgId: selectedOrganisation || undefined,
    page,
    size: rowsPerPage,
    ...(verdict !== VERDICT_FILTER_ALL ? { verdict: verdict as DocumentVerdict } : {}),
    ...(recipientKeyHash ? { recipientKeyHash } : {})
  })
```

and add `recipientKeyHash` and `onRecipientKeyHashChange: setRecipientKeyHash` to the returned object.

- [ ] **Step 5: Place it in the toolbar and add the empty state**

In `ViewPublicDocuments.component.tsx`, destructure the two new values from `usePublicDocuments()`, then render the component inside the existing toolbar `Box` — between the verdict `FormControl` and the "Issue key card" `Button`:

```tsx
          <MyDocumentsFilter onRecipientKeyHashChange={onRecipientKeyHashChange} recipientKeyHash={recipientKeyHash} />
```

Where the table's empty message is chosen, prefer the recipient-specific message when that filter is active:

```tsx
recipientKeyHash ? DOCUMENTS_NO_RECIPIENT_MATCH_MESSAGE : (verdict !== VERDICT_FILTER_ALL ? DOCUMENTS_NO_MATCHING_MESSAGE : DOCUMENTS_EMPTY_MESSAGE)
```

Add the imports for `MyDocumentsFilter` and `DOCUMENTS_NO_RECIPIENT_MATCH_MESSAGE`.

- [ ] **Step 6: Write the component test**

Create `frontend/src/modules/public-documents/components/MyDocumentsFilter/MyDocumentsFilter.spec.tsx`:

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { MyDocumentsFilter } from './MyDocumentsFilter.component'

const ALICE_PUB = '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a'
const ALICE_HASH = '300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae'

describe('MyDocumentsFilter', () => {
  it('hashes a pasted public key and reports it upward', async () => {
    const onChange = vi.fn()
    render(<MyDocumentsFilter recipientKeyHash={null} onRecipientKeyHashChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: /filter for my documents/i }))
    fireEvent.click(screen.getByRole('button', { name: /paste public key/i }))
    fireEvent.change(screen.getByLabelText(/x25519 public key/i), { target: { value: ALICE_PUB } })
    fireEvent.click(screen.getByRole('button', { name: /apply/i }))

    await waitFor(() => expect(onChange).toHaveBeenCalledWith(ALICE_HASH))
  })

  it('rejects a malformed key without calling back', async () => {
    const onChange = vi.fn()
    render(<MyDocumentsFilter recipientKeyHash={null} onRecipientKeyHashChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: /filter for my documents/i }))
    fireEvent.click(screen.getByRole('button', { name: /paste public key/i }))
    fireEvent.change(screen.getByLabelText(/x25519 public key/i), { target: { value: 'nope' } })
    fireEvent.click(screen.getByRole('button', { name: /apply/i }))

    await screen.findByText(/64 hexadecimal characters/i)
    expect(onChange).not.toHaveBeenCalled()
  })

  it('shows the active filter and can clear it', () => {
    const onChange = vi.fn()
    render(<MyDocumentsFilter recipientKeyHash={ALICE_HASH} onRecipientKeyHashChange={onChange} />)

    expect(screen.getByText(/showing documents addressed to/i)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /clear filter/i }))
    expect(onChange).toHaveBeenCalledWith(null)
  })
})
```

- [ ] **Step 7: Run the frontend checks**

```bash
cd frontend && npx vitest run src/modules/public-documents src/libs/document-vault-crypto && npm run ts && npm run lint
```

Expected: all tests PASS, `tsc` clean, eslint clean (the config runs `--max-warnings 0`).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/
git commit -m "feat(frontend): add a 'Filter for my documents' recipient filter

Presents a public key via passkey or paste, hashes it in the browser, and
filters the list by that hash. No private key is derived and nothing is
persisted."
```

---

## Verification

After Task 10, from `reeve-indexing-example`:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
cd frontend && npx vitest run && npm run ts && npm run lint
```

From `cf-reeve-platform`:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blockchain_common:test :document_vault:test :blockchain_publisher:test
```

End-to-end, against a running stack: publish a document to a known recipient, wait for the Indexer to ingest the anchor, confirm `psql -c "SELECT recipient_key_hashes FROM reeve_document ORDER BY slot DESC LIMIT 1;"` shows the expected hash, then press **Filter for my documents** in the UI with that recipient's public key and confirm the document appears — and that a different key returns the pre-1.1 empty-state message.

---

## Out of scope

- Backfilling or re-anchoring documents published before format 1.1.
- Any change to the IPFS envelope or `envelope_version`.
- Proof of key possession — the filter matches a public hash, so anyone knowing a recipient's public key can run it.
- Authentication on the Indexer's document endpoints, which stay public by existing design.
- Filtering by sender or any other new dimension.
