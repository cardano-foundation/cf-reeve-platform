-- Funding module – Rename uid/id columns for clarity:
--   milestone_id (user-defined) → external_milestone_id
--   project_id   (user-defined) → external_project_id
--   milestone_uid (DB PK)       → milestone_id
--   project_uid   (DB PK)       → project_id
--   parent_project_uid (FK)     → parent_project_id
--   project_uid in funding_milestone (FK) → project_id

-- ============================================================
-- 1. Rename user-defined columns first (avoid naming conflicts)
-- ============================================================

ALTER TABLE funding_project   RENAME COLUMN project_id   TO external_project_id;
ALTER TABLE funding_milestone  RENAME COLUMN milestone_id TO external_milestone_id;

ALTER TABLE funding_project_aud   RENAME COLUMN project_id   TO external_project_id;
ALTER TABLE funding_milestone_aud  RENAME COLUMN milestone_id TO external_milestone_id;

-- ============================================================
-- 2. Drop FK/PK constraints that reference the columns being renamed
-- ============================================================

ALTER TABLE funding_project DROP CONSTRAINT IF EXISTS fk_fp_parent_project;
ALTER TABLE funding_milestone DROP CONSTRAINT IF EXISTS fk_funding_milestone_project;
ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT IF EXISTS fk_fema_milestone;
ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT IF EXISTS pk_funding_ema;
ALTER TABLE funding_event_milestone_allocation_aud DROP CONSTRAINT IF EXISTS pk_funding_ema_aud;

-- ============================================================
-- 3. Rename PK columns
-- ============================================================

ALTER TABLE funding_project   RENAME COLUMN project_uid   TO project_id;
ALTER TABLE funding_milestone  RENAME COLUMN milestone_uid TO milestone_id;

ALTER TABLE funding_project_aud   RENAME COLUMN project_uid   TO project_id;
ALTER TABLE funding_milestone_aud  RENAME COLUMN milestone_uid TO milestone_id;

-- ============================================================
-- 4. Rename FK columns that referenced the renamed PKs
-- ============================================================

ALTER TABLE funding_project   RENAME COLUMN parent_project_uid TO parent_project_id;
ALTER TABLE funding_milestone  RENAME COLUMN project_uid        TO project_id;

ALTER TABLE funding_project_aud   RENAME COLUMN parent_project_uid TO parent_project_id;
ALTER TABLE funding_milestone_aud  RENAME COLUMN project_uid        TO project_id;

ALTER TABLE funding_event_milestone_allocation     RENAME COLUMN milestone_uid TO milestone_id;
ALTER TABLE funding_event_milestone_allocation_aud RENAME COLUMN milestone_uid TO milestone_id;

-- ============================================================
-- 5. Re-add dropped constraints using renamed columns
-- ============================================================

ALTER TABLE funding_project
    ADD CONSTRAINT fk_fp_parent_project FOREIGN KEY (parent_project_id)
        REFERENCES funding_project (project_id) ON DELETE CASCADE;

ALTER TABLE funding_milestone
    ADD CONSTRAINT fk_funding_milestone_project FOREIGN KEY (project_id)
        REFERENCES funding_project (project_id) ON DELETE CASCADE;

ALTER TABLE funding_event_milestone_allocation
    ADD CONSTRAINT pk_funding_ema PRIMARY KEY (event_id, milestone_id);

ALTER TABLE funding_event_milestone_allocation
    ADD CONSTRAINT fk_fema_milestone FOREIGN KEY (milestone_id)
        REFERENCES funding_milestone (milestone_id) ON DELETE CASCADE;

ALTER TABLE funding_event_milestone_allocation_aud
    ADD CONSTRAINT pk_funding_ema_aud PRIMARY KEY (event_id, milestone_id, rev, revtype);
