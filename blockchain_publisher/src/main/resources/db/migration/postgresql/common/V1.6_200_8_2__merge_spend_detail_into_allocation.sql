-- Restructure the published spending-event projection: the standalone
-- blockchain_publisher_spending_item tables are removed and the spend detail now lives on each
-- milestone allocation (populated for SPENDING events only).

ALTER TABLE blockchain_publisher_event_milestone_allocation
    ADD COLUMN IF NOT EXISTS allocated_amount NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS category         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS vendor           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS amount_fcy       NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS currency         VARCHAR(10),
    ADD COLUMN IF NOT EXISTS currency_id      VARCHAR(50),
    ADD COLUMN IF NOT EXISTS fx_rate          NUMERIC(30, 15),
    ADD COLUMN IF NOT EXISTS spend_date       DATE,
    ADD COLUMN IF NOT EXISTS document_hash    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS notes            TEXT;

ALTER TABLE blockchain_publisher_event_milestone_allocation_aud
    ADD COLUMN IF NOT EXISTS allocated_amount NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS category         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS vendor           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS amount_fcy       NUMERIC(30, 10),
    ADD COLUMN IF NOT EXISTS currency         VARCHAR(10),
    ADD COLUMN IF NOT EXISTS currency_id      VARCHAR(50),
    ADD COLUMN IF NOT EXISTS fx_rate          NUMERIC(30, 15),
    ADD COLUMN IF NOT EXISTS spend_date       DATE,
    ADD COLUMN IF NOT EXISTS document_hash    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS notes            TEXT;

DROP TABLE IF EXISTS blockchain_publisher_spending_item_aud;
DROP TABLE IF EXISTS blockchain_publisher_spending_item;
