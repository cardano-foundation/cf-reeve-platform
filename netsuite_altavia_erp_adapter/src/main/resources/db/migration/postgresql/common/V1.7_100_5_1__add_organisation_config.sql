CREATE TABLE netsuite_adapter_organisation_config (
    organisation_id       VARCHAR(255) NOT NULL,
    base_url              TEXT         NOT NULL,
    token_url             TEXT         NOT NULL,
    client_id             VARCHAR(255) NOT NULL,
    certificate_id        VARCHAR(255) NOT NULL,
    private_key_encrypted TEXT         NOT NULL,
    revision              BIGINT       NOT NULL DEFAULT 0,
    validation_status     VARCHAR(16),
    validation_message    TEXT,
    created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,

    CONSTRAINT netsuite_adapter_organisation_config_pk PRIMARY KEY (organisation_id)
);

COMMENT ON TABLE netsuite_adapter_organisation_config IS
    'Authoritative per-organisation NetSuite configuration. Owned by the netsuite module; the organisation module must never read it.';

COMMENT ON COLUMN netsuite_adapter_organisation_config.private_key_encrypted IS
    'AES-256-GCM envelope (v1: prefix). Decryptable only with LOB_CONFIG_ENCRYPTION_KEY.';

COMMENT ON COLUMN netsuite_adapter_organisation_config.revision IS
    'Assigned by the organisation module. An event whose revision is not greater than this one is ignored.';

COMMENT ON COLUMN netsuite_adapter_organisation_config.validation_status IS
    'Verdict of the last verification for this revision. Replayed events are acknowledged with this stored value rather than a fabricated success.';
