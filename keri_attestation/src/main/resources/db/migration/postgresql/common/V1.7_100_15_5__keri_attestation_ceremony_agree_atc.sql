-- Wallet wire-flow contract (user-directed 2026-07-22): submitAdmit must be given the IPEX AGREE
-- exchange's own atc (attachment) -- not the admit's own -- a proven wallet-contract quirk this
-- module matches exactly. The agree's atc is deterministic from the
-- built (already-sent) agree exn, so it is persisted alongside its SAID (request_exn_said) at the
-- AGREE_SENT phase transition, surviving a worker restart the same way payload_said/request_exn_said
-- already do (KeriCredentialService#awaitPresentation's F8 two-phase resume).
ALTER TABLE keri_attestation_ceremony ADD COLUMN IF NOT EXISTS agree_atc TEXT;
