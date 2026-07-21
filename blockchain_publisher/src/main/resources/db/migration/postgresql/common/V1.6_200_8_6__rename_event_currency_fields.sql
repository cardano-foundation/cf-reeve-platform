-- Clarify the published event's two currency fields: currency (the reporting currency) is renamed
-- to currency_rcy, and spend_currency (the foreign currency of the spend) to currency_fcy —
-- mirroring the existing amount_rcy/amount_fcy naming.

ALTER TABLE blockchain_publisher_spending_event
    RENAME COLUMN currency TO currency_rcy;

ALTER TABLE blockchain_publisher_spending_event
    RENAME COLUMN currency_id TO currency_rcy_id;

ALTER TABLE blockchain_publisher_spending_event
    RENAME COLUMN spend_currency TO currency_fcy;

ALTER TABLE blockchain_publisher_spending_event
    RENAME COLUMN spend_currency_id TO currency_fcy_id;

ALTER TABLE blockchain_publisher_spending_event_aud
    RENAME COLUMN currency TO currency_rcy;

ALTER TABLE blockchain_publisher_spending_event_aud
    RENAME COLUMN currency_id TO currency_rcy_id;

ALTER TABLE blockchain_publisher_spending_event_aud
    RENAME COLUMN spend_currency TO currency_fcy;

ALTER TABLE blockchain_publisher_spending_event_aud
    RENAME COLUMN spend_currency_id TO currency_fcy_id;
