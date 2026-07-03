-- Restructure spending events: the standalone funding_spending_item tables are removed and the spend
-- detail now lives on each milestone allocation (populated for SPENDING events only).

ALTER TABLE funding_event_milestone_allocation
    ADD COLUMN IF NOT EXISTS category   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS vendor     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS amount_fcy NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS amount_rcy NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS currency   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS fx_rate    NUMERIC(30, 15),
    ADD COLUMN IF NOT EXISTS spend_date DATE,
    ADD COLUMN IF NOT EXISTS hash       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS notes      VARCHAR(255);

ALTER TABLE funding_event_milestone_allocation_aud
    ADD COLUMN IF NOT EXISTS category   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS vendor     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS amount_fcy NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS amount_rcy NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS currency   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS fx_rate    NUMERIC(30, 15),
    ADD COLUMN IF NOT EXISTS spend_date DATE,
    ADD COLUMN IF NOT EXISTS hash       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS notes      VARCHAR(255);

DROP TABLE IF EXISTS funding_spending_item_aud;
DROP TABLE IF EXISTS funding_spending_item;
