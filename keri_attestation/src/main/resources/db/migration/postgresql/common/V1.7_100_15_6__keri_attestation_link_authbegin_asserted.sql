-- "I've already published it" AUTH_BEGIN escape hatch (user-directed, reusable-attestation rev):
-- the caller asserts on-chain signing authority is already published for their identity WITHOUT
-- supplying a tx hash to verify. Distinct from auth_begin_tx_hash (a real verified/submitted hash):
-- this flag records an UNVERIFIED, user-asserted completion so future ceremonies skip the step, and
-- stays queryable for the day the policy TODO re-enables mandatory verification. See
-- KeriAuthBeginService#markAssumedPublished.
ALTER TABLE keri_identity_link ADD COLUMN IF NOT EXISTS auth_begin_asserted BOOLEAN NOT NULL DEFAULT FALSE;
