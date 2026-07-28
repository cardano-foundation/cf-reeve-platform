-- The DOCUMENT attestation freeze store, now owned by document_vault instead of blockchain_publisher.
--
-- WHY IT MOVED: the ceremony runs in the user-facing tier. In the split deployment that tier is the
-- `api` service, which runs with LOB_BLOCKCHAIN_PUBLISHER_ENABLED=false - so every bean in that module,
-- including the freeze repository, is absent there. Freezing therefore could not happen at all, which
-- surfaced as `422 TARGET_MISMATCH - No provider for target type DOCUMENT`.
--
-- WHY THE SHAPE CHANGED: the old row froze the finished 1447 manifest (ipfs_cid + creation_slot +
-- the full CBOR). Neither of those two values is obtainable on the api pod - one needs an IPFS pin,
-- the other a chain tip, and that pod has neither. The wallet now attests a content COMMITMENT the
-- vault can compute offline (see DocumentAttestationCommitment); the publisher pins and stamps the
-- chain tip afterwards. So ipfs_cid, metadata_creation_slot and frozen_metadata_cbor are gone and
-- commitment_cbor takes their place.
--
-- A new table rather than an ALTER: three columns drop out, one arrives, and the old table's name
-- would then be actively misleading about which module owns it.
CREATE TABLE document_vault_attestation_freeze (
    id                     VARCHAR(36)  NOT NULL,
    -- The document_vault document id.
    document_id            VARCHAR(36)  NOT NULL,
    ceremony_id            VARCHAR(64)  NOT NULL,
    -- CBOR of the DocumentAttestationCommitment map - the exact bytes the wallet's KEL anchors.
    commitment_cbor        BYTEA        NOT NULL,
    -- CESR Blake3-256 digest of commitment_cbor.
    digest_qb64            VARCHAR(128) NOT NULL,
    -- SHA-256 (hex) of the exact envelope bytes the publisher will pin verbatim; re-checked at publish
    -- to detect the document changing between freeze and publish.
    envelope_sha256        VARCHAR(64)  NOT NULL,
    created_at             TIMESTAMP    NOT NULL,

    CONSTRAINT pk_document_vault_attestation_freeze PRIMARY KEY (id),
    CONSTRAINT uq_dv_attest_freeze_doc_ceremony UNIQUE (document_id, ceremony_id)
);

-- The cleanup job's discovery read.
CREATE INDEX IF NOT EXISTS idx_dv_attest_freeze_created_at
    ON document_vault_attestation_freeze (created_at);

-- The superseded table. Safe to drop: freeze rows are ephemeral by construction - a ceremony's TTL is
-- one hour and DocumentAttestationFreezeCleanupJob deletes terminal rows - so at worst an in-flight
-- ceremony is abandoned and the user restarts it. Its only reader, blockchain_publisher's
-- DocumentAttestationFreezeRepository, is deleted in the same change.
DROP TABLE IF EXISTS blockchain_publisher_document_attestation_freeze;
