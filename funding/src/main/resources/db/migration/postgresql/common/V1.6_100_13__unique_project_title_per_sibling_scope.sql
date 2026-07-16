-- project_title must be unique among siblings: per organisation for root projects, and per
-- parent for sub-projects (mirrors the existing external_project_id sibling-scoped indexes).
-- Two sub-projects named "WP-1" under different parents remain distinct, as do two root
-- projects named "A" in different organisations.

CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_project_org_title_root
    ON funding_project (organisation_id, project_title)
    WHERE parent_project_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_project_parent_title_sub
    ON funding_project (parent_project_id, project_title)
    WHERE parent_project_id IS NOT NULL;
