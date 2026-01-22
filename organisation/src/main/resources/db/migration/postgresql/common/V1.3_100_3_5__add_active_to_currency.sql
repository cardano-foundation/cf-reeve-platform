-- Add 'active' column to organisation_currency and organisation_currency_aud tables
ALTER TABLE organisation_currency
    ADD COLUMN active BOOLEAN DEFAULT TRUE;
ALTER TABLE organisation_currency_aud
    ADD COLUMN active BOOLEAN DEFAULT TRUE;

-- Renaming customer code to code
ALTER TABLE organisation_currency
    RENAME COLUMN customer_code TO code;
ALTER TABLE organisation_currency_aud
    RENAME COLUMN customer_code TO code;
-- Renaming currency_id to iso_code
ALTER TABLE organisation_currency
    RENAME COLUMN currency_id TO iso_code;
ALTER TABLE organisation_currency_aud
    RENAME COLUMN currency_id TO iso_code;

-- Ensuring code is exactly 3 characters long
ALTER TABLE organisation_currency
    ALTER COLUMN code TYPE VARCHAR(3);
ALTER TABLE organisation_currency_aud
    ALTER COLUMN code TYPE VARCHAR(3);
ALTER TABLE organisation_currency
    ADD CONSTRAINT chk_code_length
        CHECK (char_length(code) = 3);
ALTER TABLE organisation_currency_aud
    ADD CONSTRAINT chk_code_length
        CHECK (char_length(code) = 3);