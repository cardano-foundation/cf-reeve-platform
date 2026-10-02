-- LOB-2384: proId decouples a Project/Sub-project/Milestone's identity from its title so the title
-- can become freely renameable. proId is a separate, permanent, human-readable identifier: for every
-- existing row it starts out equal to that row's current title (this backfill), and going forward the
-- application sets it once at creation and never updates it again — unlike project_id/milestone_id
-- (the primary key), which stays a deterministic hash for existing rows and is not touched here.

ALTER TABLE funding_project ADD COLUMN pro_id VARCHAR(255);
UPDATE funding_project SET pro_id = project_title WHERE pro_id IS NULL;
ALTER TABLE funding_project ALTER COLUMN pro_id SET NOT NULL;
ALTER TABLE funding_project_aud ADD COLUMN pro_id VARCHAR(255);

-- Backs the auto-assigned proId suffix for a project's children (sub-projects or milestones, never
-- both — see funding_project's self-referential structure): incremented atomically each time a child
-- is created, so a sub-project/milestone's proId is "<parent pro_id>-<n>". Starting every existing row
-- at 0 is safe even for projects that already have children from before this column existed — those
-- children keep their (title-based) proId from the backfill above untouched; this counter only affects
-- children created from now on, and a fresh "<pro_id>-1" can't collide with any pre-existing value.
ALTER TABLE funding_project ADD COLUMN next_child_sequence INTEGER NOT NULL DEFAULT 0;
ALTER TABLE funding_project_aud ADD COLUMN next_child_sequence INTEGER;

ALTER TABLE funding_milestone ADD COLUMN pro_id VARCHAR(255);
UPDATE funding_milestone SET pro_id = milestone_title WHERE pro_id IS NULL;
ALTER TABLE funding_milestone ALTER COLUMN pro_id SET NOT NULL;
ALTER TABLE funding_milestone_aud ADD COLUMN pro_id VARCHAR(255);

-- pro_id uniqueness mirrors title's own sibling-scoped uniqueness (see
-- uq_funding_project_org_title_root / uq_funding_project_parent_title_sub in V1.6_100_13): unique per
-- organisation for root projects, per parent for sub-projects, per project for milestones. This is a
-- separate constraint from title's own uniqueness below — proId and title are independently unique
-- within the same scope, not a compound key.
CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_project_org_pro_id_root
    ON funding_project (organisation_id, pro_id)
    WHERE parent_project_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_project_parent_pro_id_sub
    ON funding_project (parent_project_id, pro_id)
    WHERE parent_project_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_milestone_project_pro_id
    ON funding_milestone (project_id, pro_id);

-- Milestone title uniqueness was never enforced at the DB level (unlike project title, see
-- V1.6_100_13) — only incidentally, by the milestone_id hash collision this feature is removing as a
-- safety net. Adding it now that title stops being tied to the row's identity.
CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_milestone_project_title
    ON funding_milestone (project_id, milestone_title);
