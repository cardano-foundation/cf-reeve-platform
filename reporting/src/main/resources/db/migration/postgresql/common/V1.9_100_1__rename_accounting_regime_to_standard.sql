-- LOB-2492: rename report "accounting_regime" field to "standard" (report + report_template, incl. Envers _aud).
-- Plain metadata-only RENAME; preserves all legacy data.
ALTER TABLE report_template     RENAME COLUMN accounting_regime TO standard;
ALTER TABLE report_template_aud RENAME COLUMN accounting_regime TO standard;
ALTER TABLE report              RENAME COLUMN accounting_regime TO standard;
ALTER TABLE report_aud          RENAME COLUMN accounting_regime TO standard;
