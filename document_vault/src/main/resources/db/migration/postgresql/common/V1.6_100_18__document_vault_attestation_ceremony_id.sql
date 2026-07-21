-- KERI wallet-attestation (design §5.1, Task 14): the consumed ceremony id is persisted on the
-- document row once VaultDocumentService.publish's attested path validates and consumes it, so an
-- already-published document's attestation binding survives past the request (and past a crash) —
-- carried forward into DocumentPublishCommand and, from there, into blockchain_publisher's dispatch
-- record (see blockchain_publisher's own attestation_ceremony_id migration).
-- NULL for every plain (non-attested) publish, which is and remains the overwhelmingly common case.
ALTER TABLE document_vault_document
    ADD COLUMN attestation_ceremony_id VARCHAR(64);
