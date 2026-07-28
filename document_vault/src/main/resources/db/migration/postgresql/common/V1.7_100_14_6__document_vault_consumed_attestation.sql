-- The consumed wallet attestation, recorded on the document at publish time.
--
-- blockchain_publisher previously read these back from keri_attestation via DocumentAttestationLookup.
-- It no longer depends on that module at all, and in the split deployment the two run in separate
-- processes (`api` vs `publisher`), so a synchronous lookup is not merely undesirable but impossible.
-- The values now travel on DocumentPublishCommand, sourced from these columns.
--
-- All nullable: a plain, unattested publish is the default and leaves every one of them NULL.
ALTER TABLE document_vault_document
    ADD COLUMN attestation_aid          VARCHAR(128),
    ADD COLUMN attestation_payload_said VARCHAR(128),
    ADD COLUMN attestation_kel_sequence VARCHAR(32);
