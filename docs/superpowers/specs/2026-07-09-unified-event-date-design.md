# Unified `eventDate` across all funding event types

## Problem

Funding events have three types — `FUNDING`, `SPENDING`, `REFUND` — but only a date for
`SPENDING`. Today the SPENDING-only, user-supplied `spendDate` (part of the "spend detail"
block) is the only date ever written on-chain, serialized as the `"date"` metadata key.
The blockchain-publisher entity also carries a dead `eventDate` field (populated from
`createdAt`, never serialized). FUNDING and REFUND events therefore carry no date on-chain
at all.

## Goal

Collapse the two dates into a single user-supplied **`eventDate`** that:

- applies to **all** event types (FUNDING / SPENDING / REFUND),
- is **optional / nullable**,
- is serialized on-chain as the `"date"` metadata key **whenever present**, for every type.

`spendDate` is removed entirely; `eventDate` replaces it as "the date". For a SPENDING
event, `eventDate` is the spend date.

## Decisions

- **One unified `eventDate`**, replacing `spendDate` (not a second field alongside it).
- **Optional / nullable** — no `@NotNull`; serialized on-chain only when present; no
  fallback to `createdAt`.
- **On-chain scope**: include the date in the 1447 metadata record for all event types.
  No new settlement-status concept.
- **Migrations**: additive new forward migrations (do not edit released-series files).
- **Postman**: add `eventDate` to FUNDING, SPENDING and REFUND request bodies.

## Changes

### Funding module (`funding/`)
- `domain/request/SpendingEventCreateRequest` — remove `spendDate`; add nullable
  `eventDate` (`yyyy-MM-dd`), general description.
- `domain/entity/FundingEventEntity` — remove `spendDate`; add `eventDate`
  (`@Column(name = "event_date")`), outside the spend-detail block.
- `domain/view/SpendingEventView` — `spendDate` → `eventDate`.
- `domain/view/SpendingEventPublishView` — remove `spendDate`; rename the existing `date`
  field to `eventDate` (now sourced from the user value, not `createdAt`).
- `service/SpendingEventService` — set `eventDate` from the request in `toEntity`; map it
  in `toView`; source `toPublishView.eventDate` from `event.getEventDate()`;
  `validateEventSpendDetail` drops the `spendDate` argument.
- `util/FundingValidations.spendDetail(...)` — remove the `spendDate` parameter; drop it
  from the "not allowed for non-SPENDING" and "required for SPENDING" checks.
  **Consequence: SPENDING no longer requires a date.**
- New migration `V1.6_100_12__rename_spend_date_to_event_date.sql` — rename column
  `spend_date` → `event_date` on `funding_event` and `funding_event_aud`.

### Blockchain publisher module (`blockchain_publisher/`)
- `domain/entity/spending/SpendingEventEntity` — remove `spendDate` (keep `eventDate`).
- `service/converter/SpendingEventConverter` — drop `setSpendDate`; keep
  `setEventDate(view.getEventDate())`.
- `service/publish/module/spendingevent/SpendingEventMetadataSerialiser` — emit `"date"`
  from `event.getEventDate()`, for all event types.
- New migration `V1.6_200_8_5__drop_spend_date.sql` — back-fill `event_date` from `spend_date`
  for in-flight rows, then drop `spend_date` on `blockchain_publisher_spending_event` and `_aud`.
  (`V1.6_200_8_4` was already taken by `add_sub_project_id.sql`.)

### Docs, schema, Postman
- `blockchain_common/.../spending_event_blockchain_transaction_metadata-schema.json` —
  update `grantEvent.date` description to "event date, all types" (stays optional; no
  structural change).
- `docs/onChainFormat.md` — `date` becomes "Event date (ISO 8601); all event types /
  Optional"; remove `date` from the SPENDING-only spend-record note.
- `funding/.../resource/SpendingEventController.java` — OpenAPI example JSON
  `spendDate` → `eventDate`.
- `cf-reeve-application/postman/Reeve_Integration.postman_collection.json` — SPENDING:
  `spendDate` → `eventDate`; FUNDING + REFUND: add `eventDate`; adjust assertions.

### Tests
`SpendingEventsPublishCommandSerdeTest`, `SpendingEventServiceTest`,
`FundingValidationsTest`, `SpendingEventConverterTest`,
`SpendingEventMetadataSerialiserTest` — swap `spendDate` → `eventDate` and update the
validation test (date no longer required for SPENDING).

## Out of scope
- No settlement-status / settled-at concept.
- No `createdAt` fallback for a missing `eventDate`.
