-- Sub-project allocations publish the sub-project's own id (next to its title), nested under the
-- root project in the on-chain metadata.
ALTER TABLE blockchain_publisher_event_project_allocation
    ADD COLUMN sub_project_id VARCHAR(255);

ALTER TABLE blockchain_publisher_event_project_allocation_aud
    ADD COLUMN sub_project_id VARCHAR(255);
