CREATE TABLE organisation_netsuite_config_state (
    organisation_id         VARCHAR(255) NOT NULL,
    base_url                TEXT         NOT NULL,
    token_url               TEXT         NOT NULL,
    client_id               VARCHAR(255) NOT NULL,
    certificate_id          VARCHAR(255) NOT NULL,
    private_key_fingerprint VARCHAR(64)  NOT NULL,
    sync_state              VARCHAR(16)  NOT NULL,
    sync_message            TEXT,
    revision                BIGINT       NOT NULL DEFAULT 0,
    version                 BIGINT,
    netsuite_valid          BOOLEAN,
    last_validated_at       TIMESTAMP WITHOUT TIME ZONE,
    validation_message      TEXT,
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,

    CONSTRAINT organisation_netsuite_config_state_pk PRIMARY KEY (organisation_id)
);

COMMENT ON TABLE organisation_netsuite_config_state IS
    'Organisation-owned projection of NetSuite configuration state. Holds no secret material; the private key lives only in the netsuite module.';

COMMENT ON COLUMN organisation_netsuite_config_state.sync_state IS
    'Whether the netsuite module received and stored the configuration.';

COMMENT ON COLUMN organisation_netsuite_config_state.netsuite_valid IS
    'Whether the credentials authenticate against NetSuite. NULL until the first acknowledgement arrives.';
