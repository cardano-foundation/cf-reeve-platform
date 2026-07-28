-- The consumed wallet attestation, carried on the dispatch record.
--
-- This module used to read these back from keri_attestation at dispatch time via
-- DocumentAttestationLookup. It no longer depends on that module, and in the split deployment the two
-- run in separate processes, so the values travel on DocumentPublishCommand instead.
--
-- All nullable: a plain, unattested publish leaves every one of them NULL.
ALTER TABLE blockchain_publisher_document
    ADD COLUMN attestation_aid          VARCHAR(128),
    ADD COLUMN attestation_payload_said VARCHAR(128),
    ADD COLUMN attestation_kel_sequence VARCHAR(32);
