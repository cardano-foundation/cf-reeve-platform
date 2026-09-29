-- LOB-2384: snapshot the funding module's new permanent proId identifier (see funding_project.pro_id /
-- funding_milestone.pro_id) into the publisher's own allocation records, alongside the existing
-- id/title columns already captured here at publish time. Nullable and never backfilled — these are
-- point-in-time snapshots of already-published events; a record published before this column existed
-- simply has no value for it, which is expected, not an error.

ALTER TABLE blockchain_publisher_event_project_allocation ADD COLUMN pro_id VARCHAR(255);
ALTER TABLE blockchain_publisher_event_project_allocation ADD COLUMN sub_project_pro_id VARCHAR(255);
ALTER TABLE blockchain_publisher_event_project_allocation_aud ADD COLUMN pro_id VARCHAR(255);
ALTER TABLE blockchain_publisher_event_project_allocation_aud ADD COLUMN sub_project_pro_id VARCHAR(255);

ALTER TABLE blockchain_publisher_event_milestone_allocation ADD COLUMN pro_id VARCHAR(255);
ALTER TABLE blockchain_publisher_event_milestone_allocation_aud ADD COLUMN pro_id VARCHAR(255);
