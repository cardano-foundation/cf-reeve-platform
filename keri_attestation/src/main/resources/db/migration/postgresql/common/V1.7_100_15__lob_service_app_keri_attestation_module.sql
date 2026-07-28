-- IDENTITY LINK. One row per platform user (Keycloak subject): their Veridian AID and the one-time
-- identity-level steps (OOBI resolve, credential presentation, AUTH_BEGIN) completed so far.
-- binding_version increments on every relink to a different AID; ceremonies created
-- under a stale binding_version are invalidated rather than deleted.
CREATE TABLE IF NOT EXISTS keri_identity_link (
    user_id VARCHAR(255) PRIMARY KEY,
    binding_version INT NOT NULL DEFAULT 0,
    aid VARCHAR(255),
    oobi_url VARCHAR(2048),
    credential_said VARCHAR(255),
    credential_schema_said VARCHAR(255),
    auth_begin_tx_hash VARCHAR(255),
    auth_begin_block BIGINT,
    auth_begin_at TIMESTAMP WITHOUT TIME ZONE,
    -- The "already published" escape hatch: the caller asserts that on-chain signing authority is
    -- already published for their identity without supplying a tx hash to verify. Distinct from
    -- auth_begin_tx_hash, which is a real submitted hash — this records an unverified, user-asserted
    -- completion so later ceremonies skip the step, and stays queryable if verification is ever made
    -- mandatory. See KeriAuthBeginService#markAssumedPublished.
    auth_begin_asserted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- CEREMONY. One user attesting one target, walking the state machine in CeremonyState. Deliberately
-- no FK to keri_identity_link.user_id (nor to the target) — a ceremony outlives a relink or a
-- deleted target and must still be readable/reportable as FAILED/EXPIRED rather than being cascaded
-- away; ownership and target validity are enforced by the service layer instead.
CREATE TABLE IF NOT EXISTS keri_attestation_ceremony (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    binding_version INT NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    state VARCHAR(32) NOT NULL,
    -- bumped on every retry; async step completions CAS on (state, attempt_generation) so a
    -- superseded worker's late completion is discarded instead of applied
    attempt_generation INT NOT NULL DEFAULT 0,
    error_title VARCHAR(255),
    error_detail VARCHAR(1024),
    request_exn_said VARCHAR(255),
    -- The atc (attachment) of the already-sent IPEX agree exchange. submitAdmit must be given the
    -- agree's atc rather than the admit's own, so it is persisted alongside request_exn_said at the
    -- AGREE_SENT phase transition and survives a restart.
    agree_atc TEXT,
    metadata_digest VARCHAR(255),
    metadata_label VARCHAR(255),
    -- The SAID of the whole saidified remotesign request payload, which is what the wallet anchors as
    -- its KEL interaction-event seal and what becomes the on-chain 170.d. metadata_digest is the raw
    -- label metadata digest, kept separately for freeze matching only; the two are not the same value.
    payload_said VARCHAR(255),
    kel_sequence VARCHAR(64),
    kel_event_said VARCHAR(255),
    -- The wallet's KEL sequence read at ATTEST-request time, before the remotesign request is sent.
    -- Any anchoring event accepted afterwards must sit at or above this floor, so an old event
    -- carrying the same digest (from a prior attestation of identical content) cannot satisfy a fresh
    -- request.
    kel_floor_sequence VARCHAR(64),
    -- Pending AUTH_BEGIN transaction hash while a ceremony sits in AUTH_BEGIN_SUBMITTED. The confirmed
    -- hash lives on keri_identity_link.auth_begin_tx_hash, one per identity; this one is
    -- ceremony-scoped and transient.
    auth_begin_tx_hash VARCHAR(255),
    -- Which half of the two-phase CREDENTIAL_REQUESTED wait (apply/offer, then agree/grant) a retry
    -- last reached, so a retry after the agree was already sent resumes waiting on that agree's grant
    -- instead of re-sending a duplicate. NULL means no phase recorded yet.
    step_phase VARCHAR(32),
    -- The KERI AID that attested this ceremony, persisted immutably at ATTEST_ANCHORED from the wallet
    -- AID the remotesign request was answered by. Persisting it, rather than re-deriving it from the
    -- current identity link at consume time, is what stops a consume -> relink -> delayed dispatch
    -- sequence from publishing the new AID alongside a digest anchored under the old one.
    attester_aid VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- fast-forward lookup ("does this user already have an open ceremony?") and per-user active-count
-- enforcement (limits.max-active-ceremonies-per-user)
CREATE INDEX IF NOT EXISTS idx_keri_attestation_ceremony_user_state ON keri_attestation_ceremony (user_id, state);
-- "is there already an attested/anchored ceremony for this target?" lookups from the consuming side
-- (document_vault publish)
CREATE INDEX IF NOT EXISTS idx_keri_attestation_ceremony_target ON keri_attestation_ceremony (target_type, target_id);
