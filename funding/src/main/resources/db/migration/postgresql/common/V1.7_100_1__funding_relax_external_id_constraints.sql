-- external_project_id and external_milestone_id are no longer used to derive identity or drive
-- lookups (project/milestone identity and matching are now title-based). external_milestone_id was
-- already nullable; external_project_id was NOT NULL and callers may now omit it.
ALTER TABLE funding_project ALTER COLUMN external_project_id DROP NOT NULL;
