-- Pending AUTH_BEGIN transaction hash while a ceremony sits in AUTH_BEGIN_SUBMITTED (Task 9). The
-- CONFIRMED hash lives on keri_identity_link.auth_begin_tx_hash (one per identity); this column is
-- ceremony-scoped and only meaningful transiently, between submitting the AUTH_BEGIN tx and its
-- confirmation sweep (KeriAuthBeginService#awaitAuthBeginConfirmation) completing or rolling back.
ALTER TABLE keri_attestation_ceremony ADD COLUMN IF NOT EXISTS auth_begin_tx_hash VARCHAR(255);
