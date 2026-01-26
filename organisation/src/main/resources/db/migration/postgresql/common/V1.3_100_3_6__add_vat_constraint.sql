
ALTER TABLE organisation_vat
    ADD CONSTRAINT chk_organisation_vat_rate_positive
        CHECK (rate >= 0);