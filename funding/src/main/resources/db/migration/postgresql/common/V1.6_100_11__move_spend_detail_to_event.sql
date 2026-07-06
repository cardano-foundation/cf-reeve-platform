-- Move the spend detail from each milestone allocation up to the event: a SPENDING event now carries
-- a single spend record, and allocations keep only the allocated amount.

ALTER TABLE funding_event
    ADD COLUMN IF NOT EXISTS category       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS vendor         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS amount_fcy     NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS amount_rcy     NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS spend_currency VARCHAR(255),
    ADD COLUMN IF NOT EXISTS fx_rate        NUMERIC(30, 15),
    ADD COLUMN IF NOT EXISTS spend_date     DATE,
    ADD COLUMN IF NOT EXISTS spend_hash     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS notes          VARCHAR(255);

ALTER TABLE funding_event_aud
    ADD COLUMN IF NOT EXISTS category       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS vendor         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS amount_fcy     NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS amount_rcy     NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS spend_currency VARCHAR(255),
    ADD COLUMN IF NOT EXISTS fx_rate        NUMERIC(30, 15),
    ADD COLUMN IF NOT EXISTS spend_date     DATE,
    ADD COLUMN IF NOT EXISTS spend_hash     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS notes          VARCHAR(255);

ALTER TABLE funding_event_milestone_allocation
    DROP COLUMN IF EXISTS category,
    DROP COLUMN IF EXISTS vendor,
    DROP COLUMN IF EXISTS amount_fcy,
    DROP COLUMN IF EXISTS amount_rcy,
    DROP COLUMN IF EXISTS currency,
    DROP COLUMN IF EXISTS fx_rate,
    DROP COLUMN IF EXISTS spend_date,
    DROP COLUMN IF EXISTS hash,
    DROP COLUMN IF EXISTS notes;

ALTER TABLE funding_event_milestone_allocation_aud
    DROP COLUMN IF EXISTS category,
    DROP COLUMN IF EXISTS vendor,
    DROP COLUMN IF EXISTS amount_fcy,
    DROP COLUMN IF EXISTS amount_rcy,
    DROP COLUMN IF EXISTS currency,
    DROP COLUMN IF EXISTS fx_rate,
    DROP COLUMN IF EXISTS spend_date,
    DROP COLUMN IF EXISTS hash,
    DROP COLUMN IF EXISTS notes;
