-- LOB-2380: reason and time of the last failed on-chain publish of an event that was reverted to draft
ALTER TABLE funding_event
    ADD COLUMN last_failure_message TEXT,
    ADD COLUMN last_failure_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE funding_event_aud
    ADD COLUMN last_failure_message TEXT,
    ADD COLUMN last_failure_at TIMESTAMP WITHOUT TIME ZONE;
