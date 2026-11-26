-- Envers audit tables for document_vault.
--
-- VaultDocumentEntity, VaultKeyEntity and AddressbookEntryEntity are @Audited, so every insert, update
-- and delete through JPA writes a revision row here: who changed a document, key or contact, when, and
-- what it looked like. A separate file because V1.7_100_14 is already applied on shared environments.
--
-- Encrypted payloads are @NotAudited and deliberately have no column here, so history never holds a
-- second copy of them, including after a DRAFT is deleted:
--   * document_vault_document.ciphertext       (content_hash still identifies it per revision)
--   * document_vault_document_slot.wrapped_dek  (key_id / recipient_key_hash still record the recipient)
--
-- Not audited at all:
--   * document_vault_wrapped_record     only an opaque client-encrypted blob
--   * document_vault_attestation_freeze insert-only and immutable; the row is its own history
--
-- Bulk JPQL deletes skip Envers, so DocumentRetentionJob's DRAFT purge writes no DEL revision. Earlier
-- revisions of a purged document stay here.
--
-- Columns mirror the base tables, all nullable except the ids, because a DEL revision stores only the id.

CREATE TABLE IF NOT EXISTS document_vault_key_aud (
    key_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(255),
    organisation_id VARCHAR(255),
    account_name VARCHAR(255),
    credential_id VARCHAR(512),
    public_key VARCHAR(64),
    label VARCHAR(255),
    origin VARCHAR(20),
    assurance VARCHAR(20),
    attestation_oobi VARCHAR(2048),
    attestation_aid VARCHAR(255),
    attestation_credential_said VARCHAR(255),
    attestation_schema_said VARCHAR(255),
    attestation_kel_sequence VARCHAR(32),
    attestation_kel_event_said VARCHAR(255),
    attestation_metadata_label VARCHAR(32),
    attestation_card_digest VARCHAR(255),
    attestation_payload_said VARCHAR(255),
    attestation_credential_cesr TEXT,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,

    CONSTRAINT pk_document_vault_key_aud PRIMARY KEY (key_id, rev, revtype),
    CONSTRAINT fk_document_vault_key_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE TABLE IF NOT EXISTS document_vault_addressbook_entry_aud (
    entry_id VARCHAR(36) NOT NULL,
    organisation_id VARCHAR(255),
    display_name VARCHAR(255),
    email VARCHAR(320),
    description VARCHAR(255),
    public_key VARCHAR(64),
    assurance VARCHAR(20),
    home_organisation_id VARCHAR(255),
    attestation_oobi VARCHAR(2048),
    attestation_aid VARCHAR(255),
    attestation_credential_said VARCHAR(255),
    attestation_schema_said VARCHAR(255),
    attestation_kel_sequence VARCHAR(32),
    attestation_kel_event_said VARCHAR(255),
    attestation_metadata_label VARCHAR(32),
    attestation_card_digest VARCHAR(255),
    attestation_payload_said VARCHAR(255),
    attestation_credential_cesr TEXT,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,

    CONSTRAINT pk_document_vault_addressbook_entry_aud PRIMARY KEY (entry_id, rev, revtype),
    CONSTRAINT fk_document_vault_addressbook_entry_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- No ciphertext column: see the header.
CREATE TABLE IF NOT EXISTS document_vault_document_aud (
    document_id VARCHAR(36) NOT NULL,
    organisation_id VARCHAR(255),
    status VARCHAR(16),
    envelope_version INT,
    content_hash VARCHAR(64),
    plaintext_hash VARCHAR(64),
    payload_nonce VARCHAR(24),
    file_name VARCHAR(255),
    content_type VARCHAR(255),
    description VARCHAR(1024),
    size_bytes BIGINT,
    created_by_account VARCHAR(255),
    created_by_name VARCHAR(255),
    published_at TIMESTAMP WITHOUT TIME ZONE,
    ledger_dispatch_status VARCHAR(32),
    dispatch_retry_at TIMESTAMP WITHOUT TIME ZONE,
    ledger_dispatch_error VARCHAR(1024),
    tx_hash VARCHAR(255),
    ipfs_cid VARCHAR(255),
    attestation_ceremony_id VARCHAR(64),
    attestation_aid VARCHAR(128),
    attestation_payload_said VARCHAR(128),
    attestation_kel_sequence VARCHAR(32),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,

    CONSTRAINT pk_document_vault_document_aud PRIMARY KEY (document_id, rev, revtype),
    CONSTRAINT fk_document_vault_document_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- Audit rows for the @ElementCollection slots, which record who a document was wrapped to.
-- No wrapped_dek column: see the header. No FK to document_vault_document, so history outlives a
-- deleted DRAFT.
CREATE TABLE IF NOT EXISTS document_vault_document_slot_aud (
    document_id VARCHAR(36) NOT NULL,
    slot_index INT NOT NULL,
    key_id VARCHAR(36),
    recipient_ref VARCHAR(255),
    ephemeral_pub VARCHAR(64),
    recipient_key_hash VARCHAR(64),

    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,

    CONSTRAINT pk_document_vault_document_slot_aud PRIMARY KEY (document_id, slot_index, rev, revtype),
    CONSTRAINT fk_document_vault_document_slot_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);
