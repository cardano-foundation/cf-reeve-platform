-- IDENTITY LINK. One row per platform user (Keycloak subject): their Veridian AID and the one-time
-- identity-level steps (OOBI resolve, credential presentation, AUTH_BEGIN) completed so far.
-- binding_version increments on every relink to a different AID (design §4.7); ceremonies created
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
    metadata_digest VARCHAR(255),
    metadata_label VARCHAR(255),
    kel_sequence VARCHAR(64),
    kel_event_said VARCHAR(255),
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
