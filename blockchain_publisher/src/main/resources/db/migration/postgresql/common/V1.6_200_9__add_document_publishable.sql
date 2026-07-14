-- Document publishing tables (blockchain_publisher module).
-- Mirrors org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity.
--
-- Built exclusively from DocumentPublishCommand fields (spec B5 #3): no lookups back into
-- document_vault tables, so this table carries no e-mails, key ids, file names or account ids.
-- Deliberately has NO _aud counterpart - the ciphertext must never get an audit history copy.

CREATE TABLE blockchain_publisher_document (
   document_id VARCHAR(36) NOT NULL,
   organisation_id VARCHAR(255) NOT NULL,
   envelope_version INTEGER NOT NULL,
   content_hash VARCHAR(64) NOT NULL,
   plaintext_hash VARCHAR(64) NOT NULL,
   payload_nonce VARCHAR(24) NOT NULL,
   ciphertext_base64 TEXT NOT NULL,
   ipfs_cid VARCHAR(255),

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

   PRIMARY KEY (document_id)
);

CREATE TABLE blockchain_publisher_document_slot (
   document_id VARCHAR(36) NOT NULL,
   slot_index INTEGER NOT NULL,
   ephemeral_pub VARCHAR(64) NOT NULL,
   wrapped_dek VARCHAR(96) NOT NULL,

   PRIMARY KEY (document_id, slot_index),
   CONSTRAINT fk_bp_document_slot_document FOREIGN KEY (document_id)
       REFERENCES blockchain_publisher_document (document_id)
);

CREATE INDEX idx_blockchain_publisher_document_org_status
ON blockchain_publisher_document (organisation_id, l1_publish_status, created_at);
