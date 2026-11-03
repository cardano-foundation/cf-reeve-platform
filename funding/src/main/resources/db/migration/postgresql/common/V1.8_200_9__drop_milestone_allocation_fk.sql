-- LOB-2365 follow-up: deleting a milestone/sub-project/project must no longer silently take its
-- funding_event_milestone_allocation rows with it. fk_fema_milestone (see V1.6_100_9) is
-- ON DELETE CASCADE, so today deleting a milestone row cascades straight through to any allocation row
-- referencing it. Instead, an allocation row must survive its milestone being deleted, standing as a
-- deliberately dangling reference that an event's own ERROR status already flags for a human to review
-- (see EventMilestoneAllocationEntity's @NotFound(IGNORE) mapping, which reads a dangling milestone_id
-- as "no milestone" instead of throwing). Dropping the FK gives up the database's own guarantee that
-- milestone_id always resolves to a live row — a deliberate trade for keeping the allocation itself,
-- and the money it recorded, intact.
ALTER TABLE funding_event_milestone_allocation DROP CONSTRAINT fk_fema_milestone;
