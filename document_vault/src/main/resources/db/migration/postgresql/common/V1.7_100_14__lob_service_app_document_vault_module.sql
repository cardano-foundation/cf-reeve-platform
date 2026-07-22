-- ORGANISATION KEYS. A key whose holder is a Keycloak user in this organisation and who owns the
-- private half. Identity comes from the login, so there is no e-mail column: the account IS the
-- contact. Contrast document_vault_addressbook_entry below, which is a contact and not an account.
CREATE TABLE IF NOT EXISTS document_vault_key (
    key_id VARCHAR(36) PRIMARY KEY,
    -- a Keycloak sub. Only ever a real account id: imported cards about somebody else go to the
    -- addressbook table, so there is nothing here for them to collide with.
    account_id VARCHAR(255) NOT NULL,
    organisation_id VARCHAR(255) NOT NULL,
    -- the Keycloak username, snapshotted from the registering user's own JWT. There is no way to
    -- resolve a username from a sub in this platform (no admin client), so this cannot be refreshed
    -- and goes stale if the user renames themselves. Display only.
    account_name VARCHAR(255),
    credential_id VARCHAR(512),
    public_key VARCHAR(64) NOT NULL,
    label VARCHAR(255) NOT NULL,
    origin VARCHAR(20) NOT NULL,
    assurance VARCHAR(20) NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT uq_document_vault_key_account_org_pub UNIQUE (account_id, organisation_id, public_key)
);
-- origin:    SELF_ENROLLED (passkey enrollment) | INDEXER_ISSUED (a card the holder imported about
--            themselves). Kept alongside assurance because neither implies the other.
-- assurance: PASSKEY (private half never left the owner's device) | PORTABLE (Indexer-minted, handed over
--            on a card — an operator has seen it). Provenance, not storage: it NEVER upgrades.

CREATE INDEX IF NOT EXISTS idx_document_vault_key_account ON document_vault_key (account_id);
CREATE INDEX IF NOT EXISTS idx_document_vault_key_org ON document_vault_key (organisation_id);

-- ADDRESSBOOK. A public key somebody gave you: a contact, not an account. Nobody logs in as one, so
-- there is no account_id — entry_id is the only handle, and an e-mail is the only contact detail there
-- is. Because these rows live in their own table, an imported card can never collide with, or be
-- mistaken for, a Keycloak account in document_vault_key above.
CREATE TABLE IF NOT EXISTS document_vault_addressbook_entry (
    entry_id VARCHAR(36) PRIMARY KEY,
    -- whose addressbook this contact is in
    organisation_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    -- nullable: a hand-entered contact may not have one
    email VARCHAR(320),
    description VARCHAR(255),
    public_key VARCHAR(64) NOT NULL,
    -- NULL = unknown, which is the honest answer for a hand-entered contact: the backend cannot know
    -- how a stranger's key was born. Only a card can claim a tier, and even then it is self-asserted.
    assurance VARCHAR(20),
    -- the holder's OWN organisation as claimed by their card (e.g. 'Privat'). Provenance shown to
    -- senders; NEVER compared against organisation_id — doing so is what once made it impossible to
    -- import a card issued outside this platform.
    home_organisation_id VARCHAR(255),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    -- the idempotency key for card re-import: re-importing a card refreshes the entry in place rather
    -- than duplicating it. Display name, e-mail and description are free to repeat; a public key is not.
    CONSTRAINT uq_document_vault_addressbook_entry_org_pub UNIQUE (organisation_id, public_key)
);

CREATE INDEX IF NOT EXISTS idx_document_vault_addressbook_entry_org
    ON document_vault_addressbook_entry (organisation_id);

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
    -- Deliberately NOT a foreign key. A slot's key may live in document_vault_key OR in
    -- document_vault_addressbook_entry, and a column cannot reference two tables. Nothing is lost:
    -- Hibernate never navigated this (DocumentSlot.keyId is a plain @Column on an @Embeddable, not a
    -- @ManyToOne), and the check it implied already runs in VaultDocumentService, which verifies each
    -- slot key's organisation on upload.
    --
    -- The FK also had to go: with no ON DELETE clause it defaulted to NO ACTION, so deleting a key any
    -- document had ever been wrapped to was rejected outright — contradicting VaultKeyService.delete's
    -- contract that a delete removes the directory entry and leaves already-wrapped documents intact.
    -- Slots are immutable and hold their own wrapped DEK; a deleted key simply stops being offered.
    key_id VARCHAR(36) NOT NULL,
    -- a display label, never a trust anchor (I6)
    recipient_ref VARCHAR(255) NOT NULL,
    ephemeral_pub VARCHAR(64) NOT NULL,
    wrapped_dek VARCHAR(96) NOT NULL,
    CONSTRAINT pk_document_vault_document_slot PRIMARY KEY (document_id, slot_index)
);

CREATE INDEX IF NOT EXISTS idx_document_vault_document_slot_key ON document_vault_document_slot (key_id);
