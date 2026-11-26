-- Envers audit tables for keri_attestation.
--
-- KeriAttestationCeremonyEntity and KeriIdentityLinkEntity are @Audited, so the trail of who attested
-- what survives the row being overwritten:
--   * keri_attestation_ceremony_aud records every step a ceremony takes (about ten small rows per
--     attestation): who started it (user_id), for which target, which wallet answered (attester_aid),
--     the anchoring KEL event, and how and when it failed or was consumed.
--   * keri_identity_link_aud records every link and relink of a user to a wallet AID. The live row
--     keeps only the current AID; a relink otherwise erases which wallet the user held before.
--
-- CeremonyCleanupJob purges FAILED/EXPIRED ceremonies with a bulk JPQL delete, which skips Envers. That
-- writes no DEL revision, but the earlier revisions stay here, so failed attempts remain on record
-- after the live row is gone.
--
-- Not audited: the dead ceremony columns auth_begin_tx_hash, step_phase and agree_atc, which are no
-- longer written (@NotAudited, so they have no column here), and keri_attestation_credential_verification.
--
-- Columns mirror the base tables, all nullable except the ids, because a DEL revision stores only the id.

CREATE TABLE IF NOT EXISTS keri_attestation_ceremony_aud (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(255),
    binding_version INT,
    target_type VARCHAR(64),
    organisation_id VARCHAR(255),
    target_id VARCHAR(255),
    state VARCHAR(32),
    attempt_generation INT,
    error_title VARCHAR(255),
    error_detail VARCHAR(1024),
    request_exn_said VARCHAR(255),
    metadata_digest VARCHAR(255),
    metadata_label VARCHAR(255),
    payload_said VARCHAR(255),
    kel_sequence VARCHAR(64),
    kel_event_said VARCHAR(255),
    attester_aid VARCHAR(255),
    kel_floor_sequence VARCHAR(64),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    expires_at TIMESTAMP WITHOUT TIME ZONE,

    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,

    CONSTRAINT pk_keri_attestation_ceremony_aud PRIMARY KEY (id, rev, revtype),
    CONSTRAINT fk_keri_attestation_ceremony_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE TABLE IF NOT EXISTS keri_identity_link_aud (
    user_id VARCHAR(255) NOT NULL,
    binding_version INT,
    aid VARCHAR(255),
    oobi_url VARCHAR(2048),
    credential_said VARCHAR(255),
    credential_schema_said VARCHAR(255),
    auth_begin_tx_hash VARCHAR(255),
    auth_begin_block BIGINT,
    auth_begin_at TIMESTAMP WITHOUT TIME ZONE,
    auth_begin_asserted BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,

    CONSTRAINT pk_keri_identity_link_aud PRIMARY KEY (user_id, rev, revtype),
    CONSTRAINT fk_keri_identity_link_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);
