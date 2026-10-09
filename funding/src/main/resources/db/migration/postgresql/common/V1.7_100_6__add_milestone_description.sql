-- LOB-2365: adds a free-text description field to milestones, so it can be included in the
-- locked/editable field set alongside milestone_amount and milestone_date (see MilestoneService#update).
-- Nullable — existing milestones simply have no description, which is expected, not backfilled.

-- VARCHAR(255) matches the funding event's own free-text "notes" column (see
-- V1.6_100_10__merge_spend_detail_into_allocation.sql) — no other free-text field in this module
-- uses a different length, so this follows that existing convention rather than picking a new one.
ALTER TABLE funding_milestone ADD COLUMN description VARCHAR(255);
ALTER TABLE funding_milestone_aud ADD COLUMN description VARCHAR(255);
