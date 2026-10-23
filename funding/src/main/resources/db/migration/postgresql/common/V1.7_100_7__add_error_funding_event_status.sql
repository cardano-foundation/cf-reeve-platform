-- LOB-2365: adds the ERROR event status — a DRAFT event whose allocations no longer reconcile against
-- its project/milestone structure after a total-amount shrink was allowed through instead of rejected
-- outright (see FundingCascadeDeleteService#markContainedEventsAsErrorOrBlock). Existing rows are
-- untouched; this only widens what the column accepts going forward.

ALTER TABLE funding_event DROP CONSTRAINT chk_funding_event_status;
ALTER TABLE funding_event ADD CONSTRAINT chk_funding_event_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ERROR'));
