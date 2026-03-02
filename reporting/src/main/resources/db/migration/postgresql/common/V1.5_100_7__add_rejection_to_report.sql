ALTER TABLE report ADD COLUMN rejected boolean NOT NULL DEFAULT false;
ALTER TABLE report ADD COLUMN rejected_by VARCHAR(255);

ALTER TABLE report_aud ADD COLUMN rejected boolean NOT NULL DEFAULT false;
ALTER TABLE report_aud ADD COLUMN rejected_by VARCHAR(255);