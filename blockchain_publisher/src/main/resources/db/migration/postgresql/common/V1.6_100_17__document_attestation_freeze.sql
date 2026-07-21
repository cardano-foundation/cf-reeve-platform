-- Freeze record for KERI wallet-attestation of document_vault publishes (design §5.2, Task 13).
-- At ATTEST time DocumentAttestationTargetProvider does exactly what DocumentL1TransactionCreator
-- will later do at dispatch (serialise envelope -> IPFS -> chain tip -> 1447 metadata map) and
-- freezes the exact result here, keyed by (document_id, ceremony_id), so the digest the user
-- attests in their Veridian wallet equals the bytes actually published later.
--
-- IMMUTABLE per ceremony: re-attestation creates a NEW row under a new ceremony id rather than
-- updating this one - there is deliberately no updated_at column and no update path.
--
-- No FK to blockchain_publisher_document: at ATTEST time no publisher-side DocumentEntity exists
-- yet (the document is still DRAFT in document_vault) - document_id here is the vault document id,
-- a different module's table entirely.

CREATE TABLE blockchain_publisher_document_attestation_freeze (
   id VARCHAR(36) NOT NULL,
   document_id VARCHAR(36) NOT NULL,
   ceremony_id VARCHAR(36) NOT NULL,
   ipfs_cid VARCHAR(255) NOT NULL,
   frozen_metadata_cbor BYTEA NOT NULL,
   digest_qb64 VARCHAR(255) NOT NULL,
   metadata_creation_slot BIGINT NOT NULL,
   envelope_sha256 CHAR(64) NOT NULL,
   created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,

   PRIMARY KEY (id),
   CONSTRAINT uq_bp_doc_attest_freeze_doc_ceremony UNIQUE (document_id, ceremony_id)
);

-- DocumentAttestationFreezeCleanupJob's discovery read (Task 13): freeze rows older than the
-- retention window.
CREATE INDEX idx_bp_doc_attest_freeze_created_at
ON blockchain_publisher_document_attestation_freeze (created_at);
