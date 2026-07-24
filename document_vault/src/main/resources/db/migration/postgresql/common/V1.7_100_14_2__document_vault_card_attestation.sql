-- Veridian card attestation (design doc "The card-format contract", Part B/B1): an imported
-- REEVE_KEY_CARD may carry an optional `attestation` block (indexer ceremony result). Store it as
-- provenance on whichever row the card lands in — set once at import, alongside the existing
-- home_organisation_id/assurance provenance columns, and never rewritten by a later re-import.
-- All five columns are NULL for the overwhelmingly common unattested card. B2 (not yet implemented)
-- is what will actually verify these against KERIA/on-chain; this migration only adds storage.
-- column sizes match keri_attestation's own columns for the same values (oobi_url/aid/*_said in
-- V1.7_100_15__lob_service_app_keri_attestation_module.sql), which is what B2 will read these back
-- through.
ALTER TABLE document_vault_key
    ADD COLUMN attestation_oobi VARCHAR(2048),
    ADD COLUMN attestation_aid VARCHAR(255),
    ADD COLUMN attestation_credential_said VARCHAR(255),
    ADD COLUMN attestation_schema_said VARCHAR(255),
    ADD COLUMN attestation_tx_hash VARCHAR(255);

ALTER TABLE document_vault_addressbook_entry
    ADD COLUMN attestation_oobi VARCHAR(2048),
    ADD COLUMN attestation_aid VARCHAR(255),
    ADD COLUMN attestation_credential_said VARCHAR(255),
    ADD COLUMN attestation_schema_said VARCHAR(255),
    ADD COLUMN attestation_tx_hash VARCHAR(255);
