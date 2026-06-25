-- Funding module – Restructure: event is the root entity spanning multiple projects/milestones.
-- Includes sub-project support and column renames aligned with the user data model.
-- V1.6_100 created the initial tables; this migration reshapes them entirely.

-- ============================================================
-- 1. Rename funding_spending_event → funding_event
--    Drop single-project/milestone columns; add organisation_id
-- ============================================================

ALTER TABLE funding_spending_event RENAME TO funding_event;
ALTER TABLE funding_spending_event_aud RENAME TO funding_event_aud;

ALTER TABLE funding_event DROP CONSTRAINT IF EXISTS fk_funding_event_project;
ALTER TABLE funding_event DROP CONSTRAINT IF EXISTS fk_funding_event_milestone;

ALTER TABLE funding_event DROP COLUMN IF EXISTS project_id;
ALTER TABLE funding_event DROP COLUMN IF EXISTS activity_id;
ALTER TABLE funding_event DROP COLUMN IF EXISTS milestone_id;

ALTER TABLE funding_event ADD COLUMN organisation_id VARCHAR(255) NOT NULL DEFAULT '';

-- funding_tx → funding_hash (aligns with user data model field "Funding Hash")
ALTER TABLE funding_event RENAME COLUMN funding_tx TO funding_hash;

-- funding_entity: name of the entity providing the funding (FUNDING events only)
ALTER TABLE funding_event ADD COLUMN IF NOT EXISTS funding_entity VARCHAR(255);

-- Audit table
ALTER TABLE funding_event_aud DROP COLUMN IF EXISTS project_id;
ALTER TABLE funding_event_aud DROP COLUMN IF EXISTS activity_id;
ALTER TABLE funding_event_aud DROP COLUMN IF EXISTS milestone_id;
ALTER TABLE funding_event_aud ADD COLUMN IF NOT EXISTS organisation_id VARCHAR(255);
ALTER TABLE funding_event_aud RENAME COLUMN funding_tx TO funding_hash;
ALTER TABLE funding_event_aud ADD COLUMN IF NOT EXISTS funding_entity VARCHAR(255);

-- ============================================================
-- 2. funding_project – Rename columns to align with user data model
--    "Project ID" and "Project Title" are user-defined fields;
--    the SHA256 PK is an internal key renamed to project_uid.
-- ============================================================

ALTER TABLE funding_project RENAME COLUMN project_id           TO project_uid;
ALTER TABLE funding_project RENAME COLUMN activity_id          TO project_id;
ALTER TABLE funding_project RENAME COLUMN activity_title       TO project_title;
ALTER TABLE funding_project RENAME COLUMN expected_total_amount TO total_amount;

-- Remove old sub-project string field (replaced by parent_project_uid FK)
ALTER TABLE funding_project DROP COLUMN IF EXISTS activity_sub_id;

-- Sub-projects do not carry total_amount or currency
ALTER TABLE funding_project ALTER COLUMN total_amount DROP NOT NULL;
ALTER TABLE funding_project ALTER COLUMN currency     DROP NOT NULL;

-- Self-referential parent FK for sub-projects
ALTER TABLE funding_project DROP CONSTRAINT IF EXISTS fk_fp_parent_project;
ALTER TABLE funding_project ADD COLUMN IF NOT EXISTS parent_project_uid CHAR(64);
ALTER TABLE funding_project
    ADD CONSTRAINT fk_fp_parent_project FOREIGN KEY (parent_project_uid)
        REFERENCES funding_project (project_uid) ON DELETE CASCADE;

-- Audit table
ALTER TABLE funding_project_aud RENAME COLUMN project_id            TO project_uid;
ALTER TABLE funding_project_aud RENAME COLUMN activity_id           TO project_id;
ALTER TABLE funding_project_aud RENAME COLUMN activity_title        TO project_title;
ALTER TABLE funding_project_aud RENAME COLUMN expected_total_amount TO total_amount;
ALTER TABLE funding_project_aud DROP COLUMN IF EXISTS activity_sub_id;
ALTER TABLE funding_project_aud ADD COLUMN IF NOT EXISTS parent_project_uid CHAR(64);

-- ============================================================
-- 3. funding_milestone – Rename columns to align with user data model
--    "Milestone ID" is user-defined; UUID PK renamed to milestone_uid.
-- ============================================================

-- Drop FK referencing milestone_id before renaming it
ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT IF EXISTS fk_fema_milestone;

ALTER TABLE funding_milestone RENAME COLUMN milestone_id  TO milestone_uid;
ALTER TABLE funding_milestone RENAME COLUMN label         TO milestone_title;
ALTER TABLE funding_milestone RENAME COLUMN expected_cost TO milestone_amount;
ALTER TABLE funding_milestone RENAME COLUMN due_date      TO milestone_date;
ALTER TABLE funding_milestone RENAME COLUMN project_id    TO project_uid;

-- User-defined milestone ID (unique within project)
ALTER TABLE funding_milestone ADD COLUMN IF NOT EXISTS milestone_id VARCHAR(255);

-- Audit table
ALTER TABLE funding_milestone_aud RENAME COLUMN milestone_id  TO milestone_uid;
ALTER TABLE funding_milestone_aud RENAME COLUMN label         TO milestone_title;
ALTER TABLE funding_milestone_aud RENAME COLUMN expected_cost TO milestone_amount;
ALTER TABLE funding_milestone_aud RENAME COLUMN due_date      TO milestone_date;
ALTER TABLE funding_milestone_aud RENAME COLUMN project_id    TO project_uid;
ALTER TABLE funding_milestone_aud ADD COLUMN IF NOT EXISTS milestone_id VARCHAR(255);

-- ============================================================
-- 4. Restructure funding_event_milestone_allocation
--    Old PK: (event_id, milestone_id)
--    New PK: (event_id, milestone_uid)
--    Project is implicit via milestone.project_uid FK — no separate allocation table needed.
-- ============================================================

ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT IF EXISTS pk_funding_event_milestone_allocation;
ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT IF EXISTS fk_fema_event;

-- Rename milestone_id → milestone_uid (fk_fema_milestone already dropped above)
ALTER TABLE funding_event_milestone_allocation RENAME COLUMN milestone_id TO milestone_uid;

-- New composite PK
ALTER TABLE funding_event_milestone_allocation
    ADD CONSTRAINT pk_funding_ema PRIMARY KEY (event_id, milestone_uid);

-- FK directly to event
ALTER TABLE funding_event_milestone_allocation
    ADD CONSTRAINT fk_fema_event FOREIGN KEY (event_id)
        REFERENCES funding_event (event_id) ON DELETE CASCADE;

-- FK to milestone
ALTER TABLE funding_event_milestone_allocation
    ADD CONSTRAINT fk_fema_milestone FOREIGN KEY (milestone_uid)
        REFERENCES funding_milestone (milestone_uid) ON DELETE CASCADE;

-- Audit table
ALTER TABLE funding_event_milestone_allocation_aud DROP CONSTRAINT IF EXISTS pk_funding_event_milestone_allocation_aud;
ALTER TABLE funding_event_milestone_allocation_aud RENAME COLUMN milestone_id TO milestone_uid;
ALTER TABLE funding_event_milestone_allocation_aud
    ADD CONSTRAINT pk_funding_ema_aud PRIMARY KEY (event_id, milestone_uid, rev, revtype);

-- ============================================================
-- 6. Update FK on funding_spending_item to point to funding_event
-- ============================================================

ALTER TABLE funding_spending_item DROP CONSTRAINT IF EXISTS fk_funding_item_event;
ALTER TABLE funding_spending_item
    ADD CONSTRAINT fk_funding_item_event FOREIGN KEY (event_id) REFERENCES funding_event (event_id) ON DELETE CASCADE;
