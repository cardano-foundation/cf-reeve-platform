ALTER TABLE organisation_currency
    ADD COLUMN active BOOLEAN DEFAULT TRUE;
ALTER TABLE organisation_currency_aud
    ADD COLUMN active BOOLEAN DEFAULT TRUE;