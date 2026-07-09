-- The published event date is no longer SPENDING-only: the general event_date column (already
-- present on the spending event table) now carries the date for every event type and is what gets
-- serialised on-chain. Drop the redundant SPENDING-only spend_date column.
--
-- Before dropping it, preserve the real user-supplied date of any in-flight SPENDING row: until now
-- event_date was derived from the event's creation timestamp while spend_date held the actual date,
-- so back-fill event_date from spend_date wherever a spend_date is present. This guarantees rows that
-- were stored but not yet dispatched to L1 publish the correct date after the switch-over.

UPDATE blockchain_publisher_spending_event
    SET event_date = spend_date
    WHERE spend_date IS NOT NULL;

UPDATE blockchain_publisher_spending_event_aud
    SET event_date = spend_date
    WHERE spend_date IS NOT NULL;

ALTER TABLE blockchain_publisher_spending_event
    DROP COLUMN IF EXISTS spend_date;

ALTER TABLE blockchain_publisher_spending_event_aud
    DROP COLUMN IF EXISTS spend_date;
