ALTER TABLE organisation_cost_center
ADD COLUMN active BOOLEAN DEFAULT TRUE;
ALTER TABLE organisation_cost_center_aud
    ADD COLUMN active BOOLEAN DEFAULT TRUE;

ALTER TABLE organisation_vat 
ADD COLUMN active BOOLEAN,
ADD COLUMN country_code VARCHAR(255),
ADD COLUMN description VARCHAR(255);

ALTER TABLE organisation_vat_aud 
    ADD COLUMN active BOOLEAN,
    ADD COLUMN country_code VARCHAR(255),
    ADD COLUMN description VARCHAR(255);