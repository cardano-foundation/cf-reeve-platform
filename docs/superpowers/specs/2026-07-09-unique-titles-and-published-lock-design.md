# Unique project/milestone titles + published-edit locking

Date: 2026-07-09
Module: `funding` (no `blockchain_publisher` changes required)

## Goal

1. Milestone names (`milestone_title`) unique within their associated project.
2. Project names (`project_title`) unique — root titles per organisation, sub-project titles within their parent.
3. Lock editing of any data structure associated with a **published** event so it cannot be mutated; the lock propagates to ancestor projects. Structures not tied to a published event stay editable (a sibling milestone, or a newly added milestone, remains editable).

## Locked decisions

- **Project title uniqueness scope**: mirror the existing external-ID rules — root titles unique per `organisation_id` (where `parent_project_id IS NULL`); sub-project titles unique within `parent_project_id`.
- **Lock trigger**: `EventStatus.PUBLISHED` (the definition already used across the funding module). Not the downstream `LedgerDispatchStatus`.
- **Lock propagation**: editing a project is blocked when the project **or any descendant sub-project** owns a milestone tied to a published event ("lock ancestors too").
- **Matching**: exact-match, case-sensitive (consistent with the existing `external_id` / `funding_id` constraints).
- **Adding children stays allowed**: adding a new (unpublished) milestone under a project that already has a published milestone is permitted. The lock freezes an entity's own mutable attributes and its published children, not the addition of new unpublished children.

## What already exists (no change needed)

- `MilestoneService.update` blocks updating a milestone tied to a published event (`existsByMilestoneIdAndEventStatus`).
- `FundingCascadeDeleteService` blocks project/milestone deletion when anything in scope is tied to a published event.
- `SpendingEventService.requireDraft` makes a published event immutable.
- DB uniqueness on `external_project_id` (root + sub) and `external_milestone_id` — but **not** on titles.

## Changes

### 1. DB migration — `V1.6_100_13__unique_titles.sql`

New forward migration (does not edit released files), mirroring the existing partial-unique-index style:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_project_org_title_root
    ON funding_project (organisation_id, project_title)
    WHERE parent_project_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_project_parent_title_sub
    ON funding_project (parent_project_id, project_title)
    WHERE parent_project_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_funding_milestone_project_title
    ON funding_milestone (project_id, milestone_title);
```

Indexes go on the main tables only, not the Envers `_aud` tables (historical revisions intentionally allow duplicates). **Caveat**: if a DB already holds duplicate titles the migration will fail — acceptable on unreleased `release/1.6.0`.

### 2. Repository methods

`FundingProjectRepository`:
- `existsByOrganisationIdAndProjectTitleAndParentProjectIsNull(orgId, title)`
- `existsByOrganisationIdAndProjectTitleAndParentProjectIsNullAndIdNot(orgId, title, id)`
- `existsByParentProjectIdAndProjectTitle(parentId, title)`
- `existsByParentProjectIdAndProjectTitleAndIdNot(parentId, title, id)`

`MilestoneRepository`:
- `existsByProjectIdAndMilestoneTitle(projectId, title)`
- `existsByProjectIdAndMilestoneTitleAndIdNot(projectId, title, id)`

`EventMilestoneAllocationRepository`:
- `existsByMilestoneProjectIdInAndEventStatus(Collection<String> projectIds, EventStatus)` (JPQL, for the subtree lock).

### 3. Service enforcement (friendly 409s)

- `ProjectService.createRootProject`: reject duplicate root title (`PROJECT_TITLE_ALREADY_EXISTS`).
- `ProjectStructureService.createSubProject`: reject duplicate sub-project title within parent.
- `ProjectService.updateProject`:
  - Replace the direct-only published check with the **subtree** check (`existsByMilestoneProjectIdInAndEventStatus` over the project + descendants).
  - On title change, reject duplicate against the final parent scope (self-excluded).
- `MilestoneService.validateAndSave`: reject duplicate milestone title within project.
- `MilestoneService.update`: on title change, reject duplicate within project (self-excluded).

### 4. Error constants

Add `PROJECT_TITLE_ALREADY_EXISTS`, `MILESTONE_TITLE_ALREADY_EXISTS` to `ErrorTitleConstants`.

## Testing

- Repository/service unit tests: duplicate root title, duplicate sub-project title (and same title allowed under a different parent), duplicate milestone title within a project (and same title allowed in a different project), self-exclusion on update.
- Ancestor lock: published milestone under a sub-project blocks editing the parent project; an unrelated sub-project stays editable; adding a new milestone under a published-locked project is allowed.
- Migration integrity check via the existing Testcontainers Postgres integration path.

## Out of scope

- No `blockchain_publisher` changes (published on-chain snapshots copy titles at publish time; later renames can't corrupt them, and renames of published structures are blocked anyway).
- The unrelated `organisation` cost-center `Project` entity is untouched.
