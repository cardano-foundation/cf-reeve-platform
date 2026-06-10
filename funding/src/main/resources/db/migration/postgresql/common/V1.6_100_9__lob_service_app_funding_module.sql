-- Funding module – Spending feature tables

CREATE TABLE IF NOT EXISTS funding_project (
    project_id       CHAR(64)       NOT NULL,
    organisation_id  VARCHAR(255)   NOT NULL,
    funding_id       VARCHAR(255)   NOT NULL,
    activity_id      VARCHAR(255)   NOT NULL,
    activity_title   VARCHAR(255)   NOT NULL,
    expected_total_amount NUMERIC(30, 10)   NOT NULL,
    currency         VARCHAR(10)    NOT NULL,

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,

    CONSTRAINT pk_funding_project PRIMARY KEY (project_id),
    CONSTRAINT uq_funding_project_org_activity UNIQUE (organisation_id, activity_id),
    CONSTRAINT uq_funding_project_funding_id    UNIQUE (funding_id)
);

CREATE TABLE IF NOT EXISTS funding_project_aud (
    project_id       CHAR(64)       NOT NULL,
    organisation_id  VARCHAR(255),
    funding_id       VARCHAR(255),
    activity_id      VARCHAR(255),
    activity_title   VARCHAR(255),
    expected_total_amount NUMERIC(30, 10),
    currency         VARCHAR(10),

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,

    rev     INTEGER  NOT NULL,
    revtype SMALLINT,

    CONSTRAINT pk_funding_project_aud PRIMARY KEY (project_id, rev, revtype),
    CONSTRAINT fk_funding_project_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS funding_milestone (
    milestone_id  VARCHAR(36)  NOT NULL,
    project_id    CHAR(64)     NOT NULL,
    label         VARCHAR(255) NOT NULL,
    expected_cost NUMERIC(30, 10)      NOT NULL,
    currency      VARCHAR(10)  NOT NULL,
    due_date      DATE         NOT NULL,

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,

    CONSTRAINT pk_funding_milestone PRIMARY KEY (milestone_id),
    CONSTRAINT fk_funding_milestone_project FOREIGN KEY (project_id) REFERENCES funding_project (project_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS funding_milestone_aud (
    milestone_id  VARCHAR(36)  NOT NULL,
    project_id    CHAR(64),
    label         VARCHAR(255),
    expected_cost NUMERIC(30, 10),
    currency      VARCHAR(10),
    due_date      DATE,

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,

    rev     INTEGER  NOT NULL,
    revtype SMALLINT,

    CONSTRAINT pk_funding_milestone_aud PRIMARY KEY (milestone_id, rev, revtype),
    CONSTRAINT fk_funding_milestone_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS funding_spending_event (
    event_id     VARCHAR(36)  NOT NULL,
    project_id   CHAR(64)     NOT NULL,
    event_type   VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    funding_id   VARCHAR(255) NOT NULL,
    activity_id  VARCHAR(255) NOT NULL,
    tx_hash      VARCHAR(255),
    funding_tx   VARCHAR(255),
    total_amount NUMERIC(30, 10)      NOT NULL DEFAULT 0,
    currency     VARCHAR(10)  NOT NULL,
    milestone_id VARCHAR(36),
    ledger_dispatch_approved BOOLEAN,
    ledger_dispatch_status VARCHAR(20),

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,
    published_at  TIMESTAMP WITHOUT TIME ZONE,

    CONSTRAINT pk_funding_spending_event PRIMARY KEY (event_id),
    CONSTRAINT fk_funding_event_project   FOREIGN KEY (project_id)   REFERENCES funding_project   (project_id)   ON DELETE CASCADE,
    CONSTRAINT fk_funding_event_milestone FOREIGN KEY (milestone_id) REFERENCES funding_milestone (milestone_id) ON DELETE SET NULL,
    CONSTRAINT chk_funding_event_type   CHECK (event_type IN ('FUNDING', 'SPENDING', 'REFUND')),
    CONSTRAINT chk_funding_event_status CHECK (status     IN ('DRAFT', 'PUBLISHED'))
);

CREATE TABLE IF NOT EXISTS funding_spending_event_aud (
    event_id     VARCHAR(36)  NOT NULL,
    project_id   CHAR(64),
    event_type   VARCHAR(20),
    status       VARCHAR(20),
    funding_id   VARCHAR(255),
    activity_id  VARCHAR(255),
    tx_hash      VARCHAR(255),
    funding_tx   VARCHAR(255),
    total_amount NUMERIC(30, 10),
    currency     VARCHAR(10),
    milestone_id VARCHAR(36),

    ledger_dispatch_approved BOOLEAN,
    ledger_dispatch_status   VARCHAR(20),

    created_by   VARCHAR(255),
    updated_by   VARCHAR(255),
    created_at   TIMESTAMP WITHOUT TIME ZONE,
    updated_at   TIMESTAMP WITHOUT TIME ZONE,
    published_at TIMESTAMP WITHOUT TIME ZONE,

    rev     INTEGER  NOT NULL,
    revtype SMALLINT,

    CONSTRAINT pk_funding_spending_event_aud PRIMARY KEY (event_id, rev, revtype),
    CONSTRAINT fk_funding_spending_event_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS funding_spending_item (
    item_id      VARCHAR(36)  NOT NULL,
    event_id     VARCHAR(36)  NOT NULL,
    category     VARCHAR(255) NOT NULL,
    vendor       VARCHAR(255) NOT NULL,
    amount_fcy   NUMERIC(30, 10)      NOT NULL,
    currency     VARCHAR(10)  NOT NULL,
    fx_rate      NUMERIC(30, 15),
    amount_rcy   NUMERIC(30, 10),
    spend_date   DATE         NOT NULL,
    hash         VARCHAR(255),
    notes        TEXT,

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,

    CONSTRAINT pk_funding_spending_item PRIMARY KEY (item_id),
    CONSTRAINT fk_funding_item_event FOREIGN KEY (event_id) REFERENCES funding_spending_event (event_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS funding_spending_item_aud (
    item_id      VARCHAR(36)  NOT NULL,
    event_id     VARCHAR(36),
    category     VARCHAR(255),
    vendor       VARCHAR(255),
    amount_fcy   NUMERIC(30, 10),
    currency     VARCHAR(10),
    fx_rate      NUMERIC(30, 15),
    amount_rcy   NUMERIC(30, 10),
    spend_date   DATE,
    hash         VARCHAR(255),
    notes        TEXT,

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,

    rev     INTEGER  NOT NULL,
    revtype SMALLINT,

    CONSTRAINT pk_funding_spending_item_aud PRIMARY KEY (item_id, rev, revtype),
    CONSTRAINT fk_funding_spending_item_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS funding_event_milestone_allocation (
    event_id         VARCHAR(36) NOT NULL,
    milestone_id     VARCHAR(36) NOT NULL,
    allocated_amount NUMERIC(30, 10),

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,

    CONSTRAINT pk_funding_event_milestone_allocation PRIMARY KEY (event_id, milestone_id),
    CONSTRAINT fk_fema_event     FOREIGN KEY (event_id)     REFERENCES funding_spending_event (event_id)     ON DELETE CASCADE,
    CONSTRAINT fk_fema_milestone FOREIGN KEY (milestone_id) REFERENCES funding_milestone      (milestone_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS funding_event_milestone_allocation_aud (
    event_id         VARCHAR(36) NOT NULL,
    milestone_id     VARCHAR(36) NOT NULL,
    allocated_amount NUMERIC(30, 10),

    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITHOUT TIME ZONE,
    updated_at  TIMESTAMP WITHOUT TIME ZONE,

    rev     INTEGER  NOT NULL,
    revtype SMALLINT,

    CONSTRAINT pk_funding_event_milestone_allocation_aud PRIMARY KEY (event_id, milestone_id, rev, revtype),
    CONSTRAINT fk_funding_event_milestone_allocation_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);
