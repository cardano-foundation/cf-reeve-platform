-- A Funding ID must identify a single FUNDING (allocation) event per organisation, so every
-- allocation is independently identifiable and traceable on-chain and on the Transparency
-- Dashboard. This is the final safety net behind the application-level check in
-- SpendingEventService#fundingEventIdAvailable (manual entry, single-event API, and CSV bulk
-- import all share it) — it catches anything that slips past that check, e.g. a race between two
-- concurrent submissions.
--
-- Scoped to event_type = 'FUNDING' only (partial index), not the whole table: a SPENDING or REFUND
-- event is expected to reuse the Funding ID of the FUNDING event it spends against or refunds —
-- that is the normal, intended pattern, not a duplicate.
CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_event_org_funding_id_funding_type
    ON funding_event (organisation_id, funding_id)
    WHERE event_type = 'FUNDING';
