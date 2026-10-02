-- CIP-170 AUTH_BEGIN publication queue.
--
-- keri_attestation has no Cardano wallet and no transaction submitter: it hands an
-- AuthBeginPublishCommand to this module, which builds and submits the label-170 transaction through
-- the same dispatcher every other publishable type uses. The ledger update this module emits is what
-- completes the ceremony's AUTH_BEGIN step.
--
-- Keyed by ceremony_id: that is the correlation handle carried back on the ledger update, and it makes
-- a redelivered publish command naturally idempotent.
--
-- No _aud counterpart: rows are a transient publication queue, and the durable record of an AUTH_BEGIN
-- is the transaction on-chain plus keri_identity_link.auth_begin_tx_hash.

CREATE TABLE blockchain_publisher_auth_begin (
    ceremony_id VARCHAR(36) NOT NULL,
    organisation_id VARCHAR(255) NOT NULL,
    -- The KERI AID whose signing authority is published, and the leaf credential's schema SAID.
    aid VARCHAR(255) NOT NULL,
    leaf_schema_said VARCHAR(255) NOT NULL,
    -- The reduced CESR credential chain, chunked into the map's `c` field at build time.
    reduced_cesr_chain BYTEA NOT NULL,
    -- Comma-separated metadata labels this AID is authorised for; the map's `m.l` list.
    authorized_labels VARCHAR(255) NOT NULL,

    l1_transaction_hash CHAR(64),
    l1_absolute_slot BIGINT,
    l1_creation_slot BIGINT,
    l1_publish_status blockchain_publisher_blockchain_publish_status_type,
    l1_finality_score blockchain_publisher_finality_score_type,
    l1_publish_status_error_reason TEXT,
    l1_publish_retry SMALLINT,

    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    locked_at TIMESTAMP WITHOUT TIME ZONE,

    PRIMARY KEY (ceremony_id)
);

CREATE INDEX idx_blockchain_publisher_auth_begin_org_status
ON blockchain_publisher_auth_begin (organisation_id, l1_publish_status, created_at);
