-- Clarify the event's two currency fields: currency (the reporting currency) is renamed to
-- currency_rcy, and spend_currency (the foreign currency of the spend) to currency_fcy — mirroring
-- the existing amount_rcy/amount_fcy naming.

ALTER TABLE funding_event
    RENAME COLUMN currency TO currency_rcy;

ALTER TABLE funding_event
    RENAME COLUMN spend_currency TO currency_fcy;

ALTER TABLE funding_event_aud
    RENAME COLUMN currency TO currency_rcy;

ALTER TABLE funding_event_aud
    RENAME COLUMN spend_currency TO currency_fcy;
