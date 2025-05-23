ALTER TABLE organisation_cost_center
ADD COLUMN active BOOLEAN DEFAULT TRUE;
ALTER TABLE organisation_cost_center_aud
    ADD COLUMN active BOOLEAN DEFAULT TRUE;