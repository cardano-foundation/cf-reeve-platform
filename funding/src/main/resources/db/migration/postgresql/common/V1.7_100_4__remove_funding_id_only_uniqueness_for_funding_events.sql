-- Superseded by widening FundingEventEntity#id() to include funding_entity: a FUNDING event's
-- identity is now its full natural key (organisation, funding_id, funding_hash, funding_entity,
-- currency_rcy, event_date), enforced by the primary key itself (event_id IS that key, hashed) —
-- exactly how SPENDING/REFUND events were already identified. This partial index forced a
-- coarser, Funding-ID-alone uniqueness on top of that (added by
-- V1.7_100_2__unique_funding_id_per_funding_event.sql), which now incorrectly rejects two
-- legitimately distinct FUNDING events that happen to share a Funding ID but differ in Hash,
-- Entity, Currency or Date (e.g. two grants reusing an external reference number under different
-- funders) — that must now be allowed, so the constraint is dropped rather than widened.
DROP INDEX IF EXISTS uq_funding_event_org_funding_id_funding_type;
