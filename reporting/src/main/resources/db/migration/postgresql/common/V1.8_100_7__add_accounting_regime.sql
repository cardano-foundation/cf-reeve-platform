-- Mandatory, free-text accounting regime disclosure on report templates, and its immutable
-- snapshot carried onto each report at the moment it is published.

ALTER TABLE report_template ADD COLUMN accounting_regime VARCHAR(255);

ALTER TABLE report_template_aud ADD COLUMN accounting_regime VARCHAR(255);

ALTER TABLE report ADD COLUMN accounting_regime VARCHAR(255);

ALTER TABLE report_aud ADD COLUMN accounting_regime VARCHAR(255);
