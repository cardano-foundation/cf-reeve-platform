-- Widen the spending-event id columns from VARCHAR(36) to VARCHAR(64).
--
-- The funding module derives every entity id with SHA3-256 (SHA3.digestAsHex -> 64 hex chars),
-- not a 36-char UUID. The original V1.6_200_8 migration sized event_id / milestone_id / item_id as
-- VARCHAR(36), so publishing a spending event failed with "value too long for type character
-- varying(36)". Widen the id columns (and their FK/audit mirrors) to hold the 64-char hashes.

-- Primary / live tables
ALTER TABLE blockchain_publisher_spending_event
    ALTER COLUMN event_id TYPE VARCHAR(64);

ALTER TABLE blockchain_publisher_event_project_allocation
    ALTER COLUMN event_id TYPE VARCHAR(64);

ALTER TABLE blockchain_publisher_event_milestone_allocation
    ALTER COLUMN milestone_id TYPE VARCHAR(64);

ALTER TABLE blockchain_publisher_spending_item
    ALTER COLUMN item_id TYPE VARCHAR(64),
    ALTER COLUMN event_id TYPE VARCHAR(64);

-- Envers audit mirrors
ALTER TABLE blockchain_publisher_spending_event_aud
    ALTER COLUMN event_id TYPE VARCHAR(64);

ALTER TABLE blockchain_publisher_event_project_allocation_aud
    ALTER COLUMN event_id TYPE VARCHAR(64);

ALTER TABLE blockchain_publisher_event_milestone_allocation_aud
    ALTER COLUMN milestone_id TYPE VARCHAR(64);

ALTER TABLE blockchain_publisher_spending_item_aud
    ALTER COLUMN item_id TYPE VARCHAR(64),
    ALTER COLUMN event_id TYPE VARCHAR(64);
