-- external_project_id is now a legacy pass-through field with no application-level uniqueness
-- management (project identity/matching is title-based — see uq_funding_project_org_title_root /
-- uq_funding_project_parent_title_sub, added in V1.6_100_13). Keeping these old indexes causes a raw
-- unique-constraint violation whenever two differently-titled projects happen to share an
-- external_project_id value, which the application no longer guards against.
DROP INDEX IF EXISTS uq_funding_project_org_external_id_root;
DROP INDEX IF EXISTS uq_funding_project_parent_external_id_sub;

-- Same reasoning for external_milestone_id (milestone identity/matching is now title-based too).
ALTER TABLE funding_milestone DROP CONSTRAINT IF EXISTS uq_funding_milestone_project_external_id;
