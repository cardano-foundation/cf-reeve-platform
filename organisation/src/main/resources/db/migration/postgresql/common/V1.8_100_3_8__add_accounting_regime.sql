-- Org-scoped, centrally-configurable list of approved accounting regimes (IFRS, US GAAP, Swiss FER, German HGB, Not applicable, ...)

CREATE TABLE IF NOT EXISTS organisation_accounting_regime (
   organisation_id CHAR(64) NOT NULL,
   code VARCHAR(255) NOT NULL,
   label VARCHAR(255) NOT NULL,
   active BOOLEAN NOT NULL DEFAULT true,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_organisation_accounting_regime PRIMARY KEY (organisation_id, code)
);

CREATE TABLE IF NOT EXISTS organisation_accounting_regime_aud (
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
   CONSTRAINT pk_organisation_accounting_regime_aud PRIMARY KEY (organisation_id, code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);
