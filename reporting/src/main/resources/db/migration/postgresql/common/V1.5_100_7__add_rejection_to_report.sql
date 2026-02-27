ALTER TABLE report ADD COLUMN rejected boolean NOT NULL DEFAULT false;
ALTER TABLE report ADD COLUMN rejection_reason TEXT;

ALTER TABLE report_aud ADD COLUMN rejected boolean NOT NULL DEFAULT false;
ALTER TABLE report_aud ADD COLUMN rejection_reason TEXT;