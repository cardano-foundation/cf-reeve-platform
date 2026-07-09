-- The event's date is no longer SPENDING-only: every event type (FUNDING, SPENDING, REFUND) now
-- carries a general event date. Rename the SPENDING-only spend_date column to event_date.

ALTER TABLE funding_event
    RENAME COLUMN spend_date TO event_date;

ALTER TABLE funding_event_aud
    RENAME COLUMN spend_date TO event_date;
