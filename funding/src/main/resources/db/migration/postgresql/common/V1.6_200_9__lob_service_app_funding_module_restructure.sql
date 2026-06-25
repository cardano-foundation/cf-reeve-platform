-- Funding module – Restructure: event is now the root; one event can span multiple projects/milestones.
-- V1.6_100 created the initial tables; this migration reshapes them for the new data model.

-- ============================================================
-- 1. Rename funding_spending_event → funding_event
--    Drop single-project/milestone columns; add organisation_id
-- ============================================================

ALTER TABLE funding_spending_event RENAME TO funding_event;
ALTER TABLE funding_spending_event_aud RENAME TO funding_event_aud;

-- Drop old FK constraints before dropping columns
ALTER TABLE funding_event DROP CONSTRAINT IF EXISTS fk_funding_event_project;
ALTER TABLE funding_event DROP CONSTRAINT IF EXISTS fk_funding_event_milestone;

ALTER TABLE funding_event DROP COLUMN IF EXISTS project_id;
ALTER TABLE funding_event DROP COLUMN IF EXISTS activity_id;
ALTER TABLE funding_event DROP COLUMN IF EXISTS milestone_id;

ALTER TABLE funding_event ADD COLUMN organisation_id VARCHAR(255) NOT NULL DEFAULT '';

-- Also update audit table columns
ALTER TABLE funding_event_aud DROP COLUMN IF EXISTS project_id;
ALTER TABLE funding_event_aud DROP COLUMN IF EXISTS activity_id;
ALTER TABLE funding_event_aud DROP COLUMN IF EXISTS milestone_id;

ALTER TABLE funding_event_aud ADD COLUMN IF NOT EXISTS organisation_id VARCHAR(255);

-- ============================================================
-- 2. Add activity_sub_id to funding_project
-- ============================================================

ALTER TABLE funding_project     ADD COLUMN IF NOT EXISTS activity_sub_id VARCHAR(255);
ALTER TABLE funding_project_aud ADD COLUMN IF NOT EXISTS activity_sub_id VARCHAR(255);

-- ============================================================
-- 3. Create funding_event_project_allocation
--    Links one event to one project (M:M via composite PK)
-- ============================================================

CREATE TABLE IF NOT EXISTS funding_event_project_allocation (
    event_id   VARCHAR(36)  NOT NULL,
    project_id CHAR(64)     NOT NULL,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    CONSTRAINT pk_funding_epa PRIMARY KEY (event_id, project_id),
    CONSTRAINT fk_fepa_event   FOREIGN KEY (event_id)   REFERENCES funding_event   (event_id)   ON DELETE CASCADE,
    CONSTRAINT fk_fepa_project FOREIGN KEY (project_id) REFERENCES funding_project (project_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS funding_event_project_allocation_aud (
    event_id   VARCHAR(36),
    project_id CHAR(64),

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    rev     INTEGER  NOT NULL,
    revtype SMALLINT,

    CONSTRAINT pk_funding_epa_aud PRIMARY KEY (event_id, project_id, rev, revtype),
    CONSTRAINT fk_funding_epa_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ============================================================
-- 4. Restructure funding_event_milestone_allocation
--    Old PK: (event_id, milestone_id)
--    New PK: (event_id, project_id, milestone_id)
-- ============================================================

-- Drop old constraint and old FK referencing funding_spending_event
ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT IF EXISTS pk_funding_event_milestone_allocation;
ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT IF EXISTS fk_fema_event;

-- Add project_id column (nullable during migration; will be NOT NULL after data fill)
ALTER TABLE funding_event_milestone_allocation ADD COLUMN IF NOT EXISTS project_id CHAR(64);

-- New composite PK
ALTER TABLE funding_event_milestone_allocation
    ADD CONSTRAINT pk_funding_ema PRIMARY KEY (event_id, project_id, milestone_id);

-- FK back to the new allocation table
ALTER TABLE funding_event_milestone_allocation
    ADD CONSTRAINT fk_fema_allocation FOREIGN KEY (event_id, project_id)
        REFERENCES funding_event_project_allocation (event_id, project_id) ON DELETE CASCADE;

-- FK on milestone — drop first (already exists from V1.6_100) then recreate
ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT IF EXISTS fk_fema_milestone;
ALTER TABLE funding_event_milestone_allocation
    ADD CONSTRAINT fk_fema_milestone FOREIGN KEY (milestone_id)
        REFERENCES funding_milestone (milestone_id) ON DELETE CASCADE;

-- Audit table
ALTER TABLE funding_event_milestone_allocation_aud DROP CONSTRAINT IF EXISTS pk_funding_event_milestone_allocation_aud;
ALTER TABLE funding_event_milestone_allocation_aud ADD COLUMN IF NOT EXISTS project_id CHAR(64);
ALTER TABLE funding_event_milestone_allocation_aud
    ADD CONSTRAINT pk_funding_ema_aud PRIMARY KEY (event_id, project_id, milestone_id, rev, revtype);

-- ============================================================
-- 5. Update FK on funding_spending_item to point to funding_event
-- ============================================================

ALTER TABLE funding_spending_item DROP CONSTRAINT IF EXISTS fk_funding_item_event;
ALTER TABLE funding_spending_item
    ADD CONSTRAINT fk_funding_item_event FOREIGN KEY (event_id) REFERENCES funding_event (event_id) ON DELETE CASCADE;
