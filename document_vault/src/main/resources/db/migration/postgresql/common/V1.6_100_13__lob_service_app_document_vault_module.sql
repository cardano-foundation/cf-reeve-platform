CREATE TABLE IF NOT EXISTS document_vault_key (
    key_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    organisation_id VARCHAR(255) NOT NULL,
    account_name VARCHAR(255),
    email VARCHAR(320) NOT NULL,
    credential_id VARCHAR(512),
    public_key VARCHAR(64) NOT NULL,
    label VARCHAR(255) NOT NULL,
    origin VARCHAR(20) NOT NULL,
    assurance VARCHAR(20) NOT NULL,
    external BOOLEAN NOT NULL DEFAULT FALSE,
    issuer_id VARCHAR(64),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT uq_document_vault_key_account_org_pub UNIQUE (account_id, organisation_id, public_key)
);
-- origin:    SELF_ENROLLED (passkey enrollment) | INDEXER_ISSUED (imported key card)
-- assurance: PASSKEY (private half never left the owner's device) | PORTABLE (Indexer-minted, handed over
--            on a card — an operator has seen it). Provenance, not storage: it NEVER upgrades.
-- external:  true = the holder has no Reeve login (card subjectType EXTERNAL); account_id then holds the
--            card's Indexer-minted subjectId rather than a Keycloak sub.
-- The UNIQUE constraint above doubles as the idempotency key for card re-import.

CREATE INDEX IF NOT EXISTS idx_document_vault_key_account ON document_vault_key (account_id);
CREATE INDEX IF NOT EXISTS idx_document_vault_key_org ON document_vault_key (organisation_id);

CREATE TABLE IF NOT EXISTS document_vault_wrapped_record (
    account_id VARCHAR(255) NOT NULL,
    credential_id VARCHAR(512) NOT NULL,
    record TEXT NOT NULL,
    version INT NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_document_vault_wrapped_record PRIMARY KEY (account_id, credential_id)
);

CREATE TABLE IF NOT EXISTS document_vault_document (
    document_id VARCHAR(36) PRIMARY KEY,
    organisation_id VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    envelope_version INT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    plaintext_hash VARCHAR(64) NOT NULL,
    ciphertext BYTEA NOT NULL,
    payload_nonce VARCHAR(24) NOT NULL,
    file_name VARCHAR(255),
    content_type VARCHAR(255),
    description VARCHAR(1024),
    size_bytes BIGINT NOT NULL,
    created_by_account VARCHAR(255) NOT NULL,
    created_by_name VARCHAR(255),
    published_at TIMESTAMP WITHOUT TIME ZONE,
    ledger_dispatch_status VARCHAR(32) NOT NULL DEFAULT 'NOT_DISPATCHED',
    -- retry-fairness cursor for DocumentDispatchRetryJob's dispatch sweep: NULL = never attempted;
    -- stamped with the sweep time when a document's publish command is (re-)emitted, so the sweep's
    -- NULLS-FIRST ordering rotates attempted rows to the back instead of re-selecting the same
    -- oldest rows forever and starving younger stuck documents.
    dispatch_retry_at TIMESTAMP WITHOUT TIME ZONE,
    ledger_dispatch_error VARCHAR(1024),
    tx_hash VARCHAR(255),
    ipfs_cid VARCHAR(255),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_document_vault_document_org ON document_vault_document (organisation_id);
CREATE INDEX IF NOT EXISTS idx_document_vault_document_creator ON document_vault_document (created_by_account);

CREATE TABLE IF NOT EXISTS document_vault_document_slot (
    document_id VARCHAR(36) NOT NULL REFERENCES document_vault_document (document_id) ON DELETE CASCADE,
    slot_index INT NOT NULL,
    key_id VARCHAR(36) NOT NULL REFERENCES document_vault_key (key_id),
    recipient_ref VARCHAR(255) NOT NULL,
    ephemeral_pub VARCHAR(64) NOT NULL,
    wrapped_dek VARCHAR(96) NOT NULL,
    CONSTRAINT pk_document_vault_document_slot PRIMARY KEY (document_id, slot_index)
);

CREATE INDEX IF NOT EXISTS idx_document_vault_document_slot_key ON document_vault_document_slot (key_id);
