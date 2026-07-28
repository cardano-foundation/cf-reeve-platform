-- Recipient key hash: sha256 of the recipient's 32-byte X25519 public key, lowercase hex.
--
-- Stored on the slot rather than resolved at publish time on purpose. Slots are already immutable and
-- self-contained (this table deliberately has NO foreign key to the key tables - "a deleted key simply
-- stops being offered"), and the attested publish path freezes and wallet-signs the 1447 metadata map.
-- Re-deriving from mutable key rows on a retry sweep could change the frozen bytes and invalidate that
-- signature; reading a frozen column cannot.
--
-- Unlike key_id and recipient_ref, this column IS exported to L1. See docs/onChainFormat.md.
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

-- Safe because a slot whose key row was already deleted cannot be backfilled, and there are none:
-- the orphan count was verified to be 0 before this migration was written. Should a future environment
-- disagree, this statement fails loudly at migration time rather than letting an unpublishable
-- document reach the manifest serialiser with a short recipient_key_hashes list.
ALTER TABLE document_vault_document_slot
    ALTER COLUMN recipient_key_hash SET NOT NULL;
