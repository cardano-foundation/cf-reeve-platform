-- report_template_field_aud.report_template_id was mistakenly typed BIGINT, but
-- report_template.id (and report_template_field.report_template_id) is VARCHAR(64).
ALTER TABLE report_template_field_aud ALTER COLUMN report_template_id TYPE VARCHAR(64);

-- report_template_aud never got an "active" column, even though report_template
-- and the ReportTemplateEntity both have one.
ALTER TABLE report_template_aud ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- report_template_aud never got a "report_count" column either, even though
-- report_template and the ReportTemplateEntity both have one.
ALTER TABLE report_template_aud ADD COLUMN report_count BIGINT NOT NULL DEFAULT 0;
