-- Recipient key hash carried through the dispatch record so the publisher can emit
-- data.recipient_key_hashes without re-resolving key rows (which would break the attested path's
-- frozen, wallet-signed metadata on a retry sweep).
--
-- Rows here are transient dispatch state, so unlike document_vault_document_slot there is nothing to
-- backfill from: any row present at migration time is a queued publish whose command predates the
-- field. Default then drop it, so an in-flight queue does not block the migration.
ALTER TABLE blockchain_publisher_document_slot
    ADD COLUMN recipient_key_hash VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE blockchain_publisher_document_slot
    ALTER COLUMN recipient_key_hash DROP DEFAULT;
