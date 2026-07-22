-- Payload SAID (design §4.4 rev 3, user-directed 2026-07-22 after live wallet testing): Veridian's
-- remotesign flow anchors the SAID of the whole saidified remotesign request payload
-- ({i, d, metadataLabel, metadataDigest} run through Saider.saidify, per the proven cip113 wallet
-- contract) as the KEL interaction-event seal, NOT the raw metadata_digest directly — the
-- direct-digest shape (design §4.4 rev 2) was tried first and never produced a wallet notification at
-- all. payload_said is therefore the value KeriAttestService#resolveAndComplete now verifies against
-- the wallet's KEL seal, and the value that becomes the on-chain 170.d. metadata_digest is kept
-- unchanged for 1447/freeze-digest matching only (DocumentAttestationLookup).
ALTER TABLE keri_attestation_ceremony ADD COLUMN IF NOT EXISTS payload_said VARCHAR(255);
