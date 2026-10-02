# LOB-2365 follow-up — design notes

Rationale for the changes made on `feat/LOB-2365_item_level_locking_backend` in this working session
(project/milestone deletion, dangling allocations, currency-change event flagging, review fixes). This
file exists so the reasoning below doesn't have to live as comments in the source. Organized by file.

## Domain model

### `EventMilestoneAllocationEntity` (and migration `V1.8_200_9__drop_milestone_allocation_fk.sql`)
`funding_event_milestone_allocation.milestone_id` is no longer FK-enforced. Deleting a
milestone/sub-project/project must not silently cascade-delete the allocation rows that reference it —
the allocation is real recorded money and must survive, standing as a deliberately dangling reference
that the owning event's `ERROR` status flags for a human to review. Dropping the FK gives up the
database's guarantee that `milestone_id` always resolves to a live row.

The entity's `milestone` field is a plain lazy `@ManyToOne` with no `@NotFound(IGNORE)`. `@NotFound(IGNORE)`
was considered and rejected: it forces the association EAGER regardless of `FetchType.LAZY`, which
conflicts with `FundingEventEntity#milestoneAllocations`'s `cascade = ALL` and throws a spurious
`TransientObjectException` on flush (reproduced in `ProjectTreeUpdateE2ETest`). Consequence: calling
`allocation.getMilestone()` once the milestone might be deleted throws `EntityNotFoundException` — always
resolve a possibly-dangling milestone via `MilestoneRepository#findById` instead. JPQL path navigation
(`a.milestone.project.id`) is safe: it compiles to a SQL join and simply excludes dangling rows.

### `EventStatus.ERROR`
Means the event no longer fits the current project/milestone structure (deleted, shrunk below its
allocation, or moved to another currency). Kept for a human to review; never auto-corrected or
auto-deleted. See `FundingCascadeDeleteService#flagEventsAllocatedTo`.

### New view types
- `OrphanedAllocationView` — one of an event's allocations whose milestone was deleted. Only carries
  `milestoneId` and `allocatedAmount` (the allocation row's own data); nothing about the deleted
  milestone (title, amount, currency) survives to show.
- `AffectedEventView` — minimal `(eventId, fundingId)` pair used to report which events a
  delete/shrink/currency-change touched, so the UI can link to them.
- `CascadeDeletionView` / `OrphanEventsCleanupView` — response shapes for the project/milestone delete
  endpoints and the bulk orphan-cleanup endpoint, each carrying the list of affected events.
- `EventProjectAllocationView.containsDeletedMilestones` — true for a single synthetic, project-less
  entry that groups an event's orphaned allocations so they also show up in `projectAllocations` (not
  only in the separate `orphanedAllocations` list), matching how a live allocation is displayed.
- `EventMilestoneAllocationView.milestoneDeleted` — true on an orphaned entry; all the milestone-derived
  fields (title, amount, currency, date) are null then, since only the allocation's own id/amount survive.

## Deletion (`FundingCascadeDeleteService`, `MilestoneService`, `ProjectService`, `ProjectTreeUpdateService`)

- Deleting a project subtree or a single milestone: fails outright if any allocated event is
  `PUBLISHED` (real recorded money can't be un-recorded by force); otherwise every non-published event
  allocated to something being deleted is flagged `ERROR`, and the delete proceeds. This applies
  regardless of whether the event also allocates to something *outside* the deleted scope — it no longer
  fits either way.
- The narrow `PUT /projects/{projectId}` was merged into the whole-tree `PUT /projects/{projectId}`
  (`ProjectTreeUpdateService.updateWithMilestones`), matching how `PUT /events/{eventId}` already works
  (id in path, single full-replace endpoint) — this is why `ProjectService.updateProject` and
  `ProjectUpdateRequest` were removed.
- A tree-update PUT node identifies an existing sub-project/milestone by `proId`, falling back to title;
  a node not matched creates a new one; an existing one left out of the request is untouched; one matched
  with `action: "DELETE"` is deleted (a sub-project's whole subtree goes with it).
- **Race condition fix:** the published-check and the flip to `ERROR` in
  `FundingCascadeDeleteService.flagEventsAllocatedTo` used to be two separate queries — a concurrent
  `SpendingEventService#publish` on the same event could interleave between them, letting one write
  silently overwrite the other. Fixed by locking the affected event rows
  (`FundingEventRepository#findAllByIdForUpdate`, ids sorted first so overlapping calls can't deadlock)
  before reading their status at all, and by locking the single event the same way in `publish()`
  (`findByIdForUpdate`). `flagEventsAllocatedTo`'s actual logic lives in a private
  `flagEventsAllocatedToInternal`, called directly by `deleteProjectSubtree`/`deleteMilestone` (same
  class) instead of through the public `@Transactional` method — a same-class call to a `@Transactional`
  method never goes through Spring's proxy, so it would silently drop that annotation's effect (Sonar
  java:S6809; same pattern already used in `ProjectStructureService.createSubProjectInternal`).
- **Milestone id reuse fix:** a milestone's id is `SHA3(projectId::proId)`. Deleting a milestone leaves
  its allocations dangling by design, but nothing stopped a *later* milestone in the same project from
  being created with that same `proId` — which would get the identical id and silently inherit the old,
  unrelated allocations. `MilestoneService.validateAndSave` now checks
  `EventMilestoneAllocationRepository#existsById_MilestoneId` against the candidate id before assigning
  it, and refuses (`MILESTONE_PROID_PREVIOUSLY_USED`) if anything, even dangling, still references it.
  Auto-assigned proIds can't hit this (their per-parent counter never repeats a value), so in practice
  this only matters for an explicit proId — CSV import.
- **Tree-update PUT no longer honors a client-supplied milestone proId on create**
  (`ProjectTreeUpdateService.applyMilestone`): it always calls the 2-arg, auto-assigning
  `MilestoneService.create(projectId, request)` now, matching `MilestoneCreateRequest#proId`'s documented
  contract ("ignored if no existing milestone matches"). This differs from a sub-project node, where
  `ProjectTreeNodeRequest#proId`'s contract explicitly *does* let the caller choose it on create — that
  asymmetry is intentional and documented on each request class.
- **Ambiguous-request guards:** a tree-update PUT request can't reference the same milestone/sub-project
  `proId` on two nodes (regardless of action — e.g. one update node and one delete node naming the same
  thing), and can't give two new milestones/sub-projects the same title. Both used to be silently
  mis-applied (second node overwrites the first) or, for the delete+anything-else case, could crash
  `validateWholeTreeCoverage` looking up a project id that a sibling node had already deleted. Extracted
  into `ProjectTreeUpdateService.checkDuplicateMilestoneIdentifiers` /
  `checkDuplicateSubProjectIdentifiers` (also keeps `applyChildren`'s cognitive complexity down — Sonar
  java:S3776). `validateWholeTreeCoverage` also skips (rather than throws on) a touched project id that
  no longer exists, as a defensive backstop.
- Deleting a milestone/sub-project/project never deletes the `funding_event_milestone_allocation` rows
  themselves — see the entity note above. `DELETE /events/orphans`
  (`SpendingEventService.deleteOrphanedErrorEvents`) is the explicit, human-triggered bulk cleanup for
  `ERROR` events where no allocation resolves to a live milestone any more.

## Shrink and currency-change parity with delete

Originally, shrinking a milestone below its allocations blocked the whole update if the affected event
also allocated to a milestone outside the shrunk set (`EVENT_ALLOCATED_TO_OTHER_PROJECTS`), unlike delete,
which just flags the event. That inconsistency was removed: shrink now always flags, same as delete.
Changing a project's or a milestone's currency didn't flag any events at all, even though an event books
in one currency that must match every milestone it allocates to — fixed the same way. All three cases
(delete, shrink, currency change) now funnel through the same
`FundingCascadeDeleteService.flagEventsAllocatedTo`. `ProjectTreeUpdateService` collects every
milestone shrunk or moved to another currency across a whole tree-update request into one
`changedMilestoneIds` set and flags them together at the end (`flagEventsOfChangedMilestones`), once the
request is otherwise fully accepted — never per-milestone immediately, so an event allocating to two
milestones both being changed in the same request is flagged once, correctly, rather than tripping a
false cross-project block.

## `proId` format

Auto-assigned child proIds now carry a level letter, shared per-parent counter: a sub-project gets
`<parent>-S<n>`, a milestone gets `<parent>-M<n>` (`ProjectChildSequenceService.ChildKind`). Previously
both used a bare `-<n>`, which was ambiguous — a project could have a milestone and a sub-project with
the same suffix. Existing rows keep the old `-<n>` form; explicit CSV-supplied proIds are unchanged.

## `externalProjectId` / `externalMilestoneId`

Confirmed unused for matching/lookup anywhere (backend or frontend) and never populated in production
data. Left in place, in requests and responses, for backward compatibility, but no longer required
(`@NotBlank` dropped from `ProjectWithMilestonesCreateRequest`/`ProjectTreeNodeRequest`) — full removal
(response fields, entity columns, DB columns) is a later, separate cleanup once the frontend stops
sending them.
