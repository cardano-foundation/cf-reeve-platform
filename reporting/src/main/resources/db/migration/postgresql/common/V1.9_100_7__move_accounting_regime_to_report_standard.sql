-- LOB-2492: "accounting regime" becomes "report standard", and its catalogue moves from the
-- organisation module to the reporting module (the only module that uses it).
-- Not yet in production, but done as a new migration (create, copy, drop) rather than by editing
-- V1.8_100_3_8 / V1.8_100_7, so environments that already ran those keep their data.

-- 1. Catalogue: organisation_accounting_regime -> report_standard (same columns)

CREATE TABLE IF NOT EXISTS report_standard (
   organisation_id CHAR(64) NOT NULL,
   code VARCHAR(255) NOT NULL,
   label VARCHAR(255) NOT NULL,
   active BOOLEAN NOT NULL DEFAULT true,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_report_standard PRIMARY KEY (organisation_id, code)
);

CREATE TABLE IF NOT EXISTS report_standard_aud (
   organisation_id CHAR(64) NOT NULL,
   code VARCHAR(255) NOT NULL,
   label VARCHAR(255) NOT NULL,
   active BOOLEAN NOT NULL DEFAULT true,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_report_standard_aud PRIMARY KEY (organisation_id, code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

INSERT INTO report_standard (organisation_id, code, label, active, created_by, updated_by, created_at, updated_at)
SELECT organisation_id, code, label, active, created_by, updated_by, created_at, updated_at
FROM organisation_accounting_regime;

-- Envers history is copied too, so the standards' audit trail survives the move
INSERT INTO report_standard_aud (organisation_id, code, label, active, created_by, updated_by, created_at, updated_at, rev, revtype)
SELECT organisation_id, code, label, active, created_by, updated_by, created_at, updated_at, rev, revtype
FROM organisation_accounting_regime_aud;

DROP TABLE organisation_accounting_regime_aud;
DROP TABLE organisation_accounting_regime;

-- 2. The standard declared on report templates and copied onto reports

ALTER TABLE report_template     RENAME COLUMN accounting_regime TO report_standard;
ALTER TABLE report_template_aud RENAME COLUMN accounting_regime TO report_standard;
ALTER TABLE report              RENAME COLUMN accounting_regime TO report_standard;
ALTER TABLE report_aud          RENAME COLUMN accounting_regime TO report_standard;
