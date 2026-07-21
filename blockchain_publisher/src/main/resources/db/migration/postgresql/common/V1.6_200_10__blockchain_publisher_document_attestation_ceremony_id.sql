-- KERI wallet-attestation (design §5.1, Task 14): the ceremony id consumed by document_vault's
-- attested publish flows through DocumentPublishCommand into this dispatch row (via
-- DocumentConverter#convertToDbDetached), so a retry-sweep re-emission (same command factory)
-- carries the binding forward too. NULL for every plain (non-attested) publish.
ALTER TABLE blockchain_publisher_document
    ADD COLUMN attestation_ceremony_id VARCHAR(64);
