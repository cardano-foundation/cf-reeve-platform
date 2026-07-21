-- KEL floor sequence (F5 fix): the wallet's KEL sequence queried at ATTEST-request time, BEFORE the
-- remotesign request is sent. awaitAnchor/resolveAndComplete requires any candidate/fallback
-- anchoring event to have a sequence >= this floor, so an old KEL event that happens to carry the same
-- metadata digest (e.g. left over from a prior attestation of the same content) can never satisfy a
-- fresh request.
ALTER TABLE keri_attestation_ceremony ADD COLUMN IF NOT EXISTS kel_floor_sequence VARCHAR(64);

-- IPEX presentation phase (F8 fix): persists which half of the two-phase CREDENTIAL_REQUESTED wait
-- (apply/offer, then agree/grant) a retry last reached, so a retry after the agree was already sent
-- does not re-send a duplicate apply/agree and instead resumes waiting on the already-sent agree's
-- grant. Null/absent means "no phase recorded yet" (equivalent to APPLY_SENT for retry purposes).
ALTER TABLE keri_attestation_ceremony ADD COLUMN IF NOT EXISTS step_phase VARCHAR(32);
