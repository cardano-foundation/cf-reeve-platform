-- LOB-2492: rename organisation "accounting regime" catalog to "standard".
-- Table + Envers _aud table + primary-key constraint names. Data preserved.
ALTER TABLE organisation_accounting_regime     RENAME TO organisation_standard;
ALTER TABLE organisation_accounting_regime_aud RENAME TO organisation_standard_aud;
ALTER TABLE organisation_standard     RENAME CONSTRAINT pk_organisation_accounting_regime     TO pk_organisation_standard;
ALTER TABLE organisation_standard_aud RENAME CONSTRAINT pk_organisation_accounting_regime_aud TO pk_organisation_standard_aud;
