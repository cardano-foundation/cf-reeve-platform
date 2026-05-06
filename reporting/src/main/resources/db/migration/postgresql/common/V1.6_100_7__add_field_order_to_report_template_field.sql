-- Add field_order to preserve template field ordering when publishing reports.
-- The existing order is captured from the BIGSERIAL id (insertion order within each parent group).
ALTER TABLE report_template_field ADD COLUMN IF NOT EXISTS field_order INTEGER NOT NULL DEFAULT 0;

UPDATE report_template_field rtf
SET field_order = sub.rn
FROM (
    SELECT id,
           (ROW_NUMBER() OVER (
               PARTITION BY report_template_id, COALESCE(CAST(parent_field_id AS TEXT), 'null')
               ORDER BY id
           ) - 1) AS rn
    FROM report_template_field
) sub
WHERE rtf.id = sub.id;

ALTER TABLE report_template_field_aud ADD COLUMN IF NOT EXISTS field_order INTEGER;
