-- Veridian card attestation, credential chain (design doc WS2/B2): carry the full CESR credential
-- chain the wallet presented during the indexer ceremony, so the platform can re-validate the
-- credential itself on import (it cannot fetch it via the OOBI alone). TEXT (a chain can be large),
-- nullable, set once at import like the other attestation_* provenance columns.
ALTER TABLE document_vault_key
    ADD COLUMN attestation_credential_cesr TEXT;

ALTER TABLE document_vault_addressbook_entry
    ADD COLUMN attestation_credential_cesr TEXT;
