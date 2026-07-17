# Split the key store: organisation keys vs. addressbook

Status: approved, not yet implemented
Module: `document_vault`
Builds on: [2026-07-16-addressbook-external-card-import-design.md](2026-07-16-addressbook-external-card-import-design.md)
Amends: `docs/documentVault.md` §2.8.5 (re-import), §5.13 (card import), the key/recipient endpoints in §5

## Goal

Stop storing two different things in one table.

- **Organisation keys** — a key whose holder is a Keycloak user in the organisation and who owns the
  private half. Identity comes from the login.
- **Addressbook entries** — a public key somebody handed you. A contact, not an account. Nobody logs in
  as one.

Today both live in `document_vault_key`, distinguished only by a boolean (`external`). Every consequence
below follows from that conflation.

## Why one table stopped working

The previous spec fixed card import by namespacing imported account ids (`ext:<subjectId>`), because
`account_id` was one namespace shared by Keycloak subs and card subject ids, and an unsigned card could
otherwise claim a colleague's account. **The split subsumes that fix.** An addressbook entry is not in
the org-keys table, so there is nothing for it to collide with. The prefix, the `external` flag, and the
entire collision class are deleted rather than defended.

Second: the two rows want different columns. An org key does not need an `email` — the Keycloak user is
the identity, and the address is theirs, not the key's. An addressbook entry needs one, because an email
is all the contact information there is.

## Locked decisions

1. Two tables. `document_vault_key` holds only org keys; `document_vault_addressbook_entry` is new.
2. An addressbook entry has **no account id**. Its `entry_id` is the only handle. External holders were
   never able to log in, so nothing is lost.
3. `UNIQUE (organisation_id, public_key)` on the addressbook. Re-import refreshes in place (§2.8.5
   survives). Everything else — display name, email, description — may repeat freely.
4. `email` is removed from org keys entirely, including `RegisterKeyRequest`.
5. The `ext:` prefix is removed. `account_id` returns to `VARCHAR(255)`.
6. Card import routes: subject is the caller ⇒ org key; anyone else ⇒ addressbook entry. The response
   states which.
7. The `document_vault_document_slot.key_id` foreign key is dropped; a lookup facade replaces it.
8. Schema is branch-only (`V1.6_100_13`, absent from `main`), so it is edited in place. No backfill.

### Note on decision 3

The instruction was "the id must be unique, everything else not", and idempotent re-import was chosen in
the same breath. These conflict: idempotency needs `(organisation_id, public_key)` to be unique, so
`public_key` **is** unique within an organisation. Decision 3 is the reconciliation — "everything else"
means name, email and description. Flagged because it is a deliberate narrowing of the literal
instruction, not an oversight.

## Schema

Both edited into `V1.6_100_13__lob_service_app_document_vault_module.sql`.

```sql
-- Org keys: a Keycloak user who owns the private half.
document_vault_key
  key_id           VARCHAR(36)  PK
  account_id       VARCHAR(255) NOT NULL   -- Keycloak sub. Back from 260: no 'ext:' prefix exists now.
  account_name     VARCHAR(255)            -- Keycloak username, snapshotted at registration (see below)
  organisation_id  VARCHAR(255) NOT NULL
  public_key       VARCHAR(64)  NOT NULL
  label            VARCHAR(255) NOT NULL
  credential_id    VARCHAR(512)
  origin           VARCHAR(20)  NOT NULL
  assurance        VARCHAR(20)  NOT NULL
  created_by/updated_by/created_at/updated_at
  UNIQUE (account_id, organisation_id, public_key)
  -- REMOVED: email, external, home_organisation_id

-- Addressbook: a public key someone gave you. No account, no login, no private half here.
document_vault_addressbook_entry
  entry_id             VARCHAR(36)  PK
  organisation_id      VARCHAR(255) NOT NULL
  display_name         VARCHAR(255) NOT NULL
  email                VARCHAR(320)          -- nullable: a hand-entered contact may have none
  description          VARCHAR(255)
  public_key           VARCHAR(64)  NOT NULL
  assurance            VARCHAR(20)           -- nullable, see below
  home_organisation_id VARCHAR(255)          -- the card's "Privat"; provenance, never compared
  created_by/updated_by/created_at/updated_at
  UNIQUE (organisation_id, public_key)
  INDEX (organisation_id)
```

`assurance` is nullable on the addressbook because a hand-entered entry has no honest tier to claim: the
backend cannot know how a stranger's key was born, and defaulting to `PORTABLE` would assert something
unknown. Null means unknown and must render as such. A card-imported entry copies the card's claim,
which is self-asserted but at least attributed.

`account_name` is a **snapshot**, not a lookup. There is no way to resolve a Keycloak username from a
`sub` anywhere in this repo — no admin client, no user API; `KeycloakSecurityHelper.getCurrentUser()`
reads the `name` claim off the *current* JWT only. The username is therefore captured at registration
from the registering user's own token and goes stale if they rename themselves in Keycloak. Accepted:
adding a Keycloak admin-client integration to refresh it is disproportionate, and the value is display
only.

## The slot foreign key

`document_vault_document_slot.key_id` currently carries
`REFERENCES document_vault_key (key_id)` (migration line 87). A column cannot reference two tables, so
it is dropped. This costs less than it appears:

- **Hibernate never navigates it.** `DocumentSlot.keyId` is a plain `@Column` on an `@Embeddable`
  (`DocumentSlot.java:23`), not a `@ManyToOne`. No entity graph changes.
- **The check it implies already runs in application code.** `VaultDocumentService.java:130` verifies
  each slot key's `organisationId` against the request's org before accepting an upload.
- **The FK currently forbids a documented behaviour.** `VaultKeyService.delete`'s javadoc
  (`VaultKeyService.java:116-121`) promises "deleting only removes the directory entry" while slots stay
  immutable. With `NO ACTION` (the default — no `ON DELETE` clause is present), deleting a key that any
  document wrapped to must instead raise an integrity violation. Every delete test mocks the repository
  (`VaultKeyServiceTest.java:149-195`), so nothing has ever exercised this against a real database. See
  Testing: this is proven with a failing test before it is fixed, not assumed.

### Replacement: `VaultKeyLookupService`

One small facade resolving a `keyId` across both tables and returning a common shape:

```
KeyRef { String id, String organisationId, @Nullable String accountId, String publicKey }
```

`accountId` is null exactly when the ref is an addressbook entry. Both id spaces are UUIDs, so a merged
lookup is unambiguous.

Exactly four call sites resolve a key by id alone, and none of them care which table it came from — they
read only `organisationId` and `accountId`:

| Call site | Uses | Effect of the split |
|---|---|---|
| `VaultDocumentService.java:126` | upload slot validation | reads `organisationId` — unchanged behaviour |
| `VaultDocumentService.java:285` | fetch authorisation | `accountId` null ⇒ never matches ⇒ contacts cannot fetch |
| `DocumentLedgerUpdateHandler.java:89` | ledger recipient fan-out | null `accountId`s filtered out |
| `VaultKeyService.java:123` | delete | **not** via the facade — org keys and entries get separate delete endpoints |

The fetch and fan-out consequences are both correct rather than regrettable. An addressbook contact has
no Reeve login, so they read published documents in the Indexer (design §9) and were never notifiable
in-app. `DocumentPublishedEvent` carries `recipientAccountIds` and is already documented as
metadata-minimised; filtering nulls keeps it honest.

This also **resolves** the fetch lockout the previous spec accepted as a known regression: it applied to
a colleague filed under `ext:<their-sub>`, an id that no longer exists.

## API

### Organisation keys — unchanged except email

| Method | Path | Change |
|---|---|---|
| `POST` | `/keys` | `RegisterKeyRequest` loses `email` |
| `DELETE` | `/keys/{keyId}` | unchanged (but see the FK bug) |
| `GET` | `/keys/me` | slimmed `VaultKeyView` |
| `GET` | `/organisations/{organisationId}/keys` | slimmed `VaultKeyView` |

`VaultKeyView` drops `email`, `external` and `homeOrganisationId`. It keeps the Keycloak username
(`accountName`), `createdAt` and `label`/description that the listing shows, plus `keyId`, `publicKey`,
`assurance`, `origin` and `organisationId`, which the wrap and management flows need functionally.

`origin` stays. With self-cards routing here (decision 6), it is the only field distinguishing a passkey
the user enrolled from a portable card they imported about themselves. `assurance` correlates but does
not imply it: neither can be derived from the other.

### Addressbook — new

The module already has a convention, and this follows it rather than inventing one: **writes carry
`organisationId` in the body** (the request extends `BaseRequest`, so `OrganisationCheckInterceptor`
parses it out and checks membership pre-handle), **reads take it from the path** (checked in-service, as
`GET /organisations/{id}/keys` does). Putting the org in both would give one value two sources of truth.

| Method | Path | Org comes from | Returns |
|---|---|---|---|
| `GET` | `/organisations/{organisationId}/addressbook` | path | paged `AddressbookEntryView` |
| `POST` | `/addressbook` | body (`BaseRequest`) | `201` + view |
| `PUT` | `/addressbook/{entryId}` | **the entry row** | `200` + view |
| `DELETE` | `/addressbook/{entryId}` | **the entry row** | `204` |
| `POST` | `/addressbook/import` | body (`BaseRequest`) | `200`/`201` + import result |

`POST /cards/import` moves to `POST /addressbook/import` — cards are addressbook entries, and the path
should say so.

`PUT` and `DELETE` take the organisation from the stored entry, not from the caller: the entry id already
determines which addressbook the row is in, and accepting an org alongside it would let the two disagree.
Both load the entry, then check `canUserAccessOrg(entry.getOrganisationId())` in-service — the same shape
as `DELETE /keys/{keyId}`, which likewise derives its authorisation from the row.

Missing entry ⇒ `404 ADDRESSBOOK_ENTRY_NOT_FOUND`; entry in an organisation the caller does not belong to
⇒ `403`. This mirrors `VaultKeyService.delete` (`404 KEY_NOT_FOUND` then `403 NOT_KEY_OWNER`) and does
distinguish "exists but forbidden" from "does not exist" to a caller holding the id. That is a deliberate
consistency choice, not an oversight: entry ids are UUIDs, so the disclosure is limited to someone who
already possesses one, and diverging from the module's established shape for a near-zero gain would cost
more in surprise than it buys.

Editable by `PUT`: `displayName`, `email`, `description`. **Not** editable: `publicKey`,
`organisationId`, `assurance`, `homeOrganisationId`. A public key is the entry's substance — changing it
in place would silently repoint a contact a sender already verified out-of-band, which is the
key-substitution attack wearing a different hat. Change the key by deleting the entry and adding the new
one, which forces the sender to re-verify.

### Card import routes to one of two tables

```
subject.subjectId == caller's Keycloak sub  →  org key row      (lands in /keys/me, fetchable)
otherwise                                   →  addressbook entry (a contact)
```

The branch keys on the caller's sub, never on the card's `subjectType`: the card is unsigned, so
`subjectType` is only what the importer typed, and branching on it would let a forged card choose its
own destination. This is the same rule as the previous spec's namespacing, now selecting a *table*
instead of an id prefix — which is what made the prefix redundant.

The endpoint therefore sometimes does not write to the addressbook, despite its name. That is a
deliberate, accepted cost. It is mitigated, not hidden: the response carries an explicit destination so
no caller has to infer it, and the OpenAPI description states the rule.

```json
{ "destination": "ORG_KEY" | "ADDRESSBOOK_ENTRY", "key": { ... } }
```

`CardSubjectType` remains inert — declared on the card, read by nothing. Removing it from the wire
format stays out of scope.

## Recipient resolution

`ResolveRecipientsRequest` resolves everything through `accountId` today. Since entries have no account,
it takes two explicit lists instead of one opaque id list:

```
{ organisationId, recipientAccountIds[], recipientEntryIds[], senderKeyIds[] }
```

Two lists rather than one because the failure modes differ and should read differently: "no key bound to
organisation X for account Y" and "no such addressbook entry Z" are not the same problem. Collapsing
them into one id space would reintroduce, at the API layer, exactly the ambiguity the table split
removes at the storage layer.

Sender auto-include draws from org keys only — the sender is always a Reeve account. `SENDER_KEY_MISSING`
is unchanged.

`RecipientKeyView` must now represent rows from both tables, so it gains a `kind` discriminator
(`ORG_KEY` | `ADDRESSBOOK_ENTRY`) and nullable fields for what only one side has (`accountId` for org
keys; `email`, `homeOrganisationId` for entries). One list, because the picker shows one list.
`assurance` and `homeOrganisationId` must still be rendered (blueprint I2): trust here is the sender's
out-of-band judgement, and both are inputs to it.

## What this undoes from the previous spec

| Previous | Now |
|---|---|
| `ext:` prefix on imported account ids | **removed** — separate tables make collision impossible |
| `account_id VARCHAR(260)` | back to `VARCHAR(255)` |
| `recipient_ref VARCHAR(260)` + `@Size(max = 260)` | back to `255` — the prefix was the only reason |
| `external` flag | **removed** — the table it lives in says it |
| `home_organisation_id` on `document_vault_key` | moves to the addressbook table |
| Fetch lockout for `ext:<colleague-sub>` | **resolved** — that id no longer exists |
| `aMaximumLengthNamespacedAccountIdRoundTripsWithoutTruncation` | delete — the boundary it pins is gone |

Retained from it: the org-match check stays gone, `subject.organisationId` stays optional provenance,
and the caller's-sub rule survives as the routing branch.

## Testing

Prove first, then fix:

- **The FK/delete bug** gets a real integration test — register a key, upload a document wrapping to it,
  delete the key — asserted against a real Postgres, watched failing with an integrity violation before
  the FK is dropped. It is the one claim in this spec inferred from schema and javadoc rather than
  observed, and every existing delete test mocks the repository, so it must be observed before it is
  believed.

Then:

- Addressbook CRUD: create, list scoped to the organisation, edit name/email/description, delete.
- `PUT` refuses to change `publicKey`.
- Re-import of the same card into the same org updates in place and does not duplicate
  (`UNIQUE (organisation_id, public_key)`).
- Two entries may share a display name and email; only `(org, public_key)` collides.
- A card about the caller creates an **org key** and the response says `ORG_KEY`; a card about anyone
  else creates an entry and says `ADDRESSBOOK_ENTRY`.
- An addressbook entry never appears in `/keys/me` or `/organisations/{id}/keys`.
- Fetch refuses a caller whose only claim is an addressbook entry.
- `VaultKeyLookupService` resolves ids from both tables in one call and returns null `accountId` for
  entries.
- Ledger fan-out filters entry recipients out of `DocumentPublishedEvent.recipientAccountIds`.
- The reported payload still works end to end: a card naming "Privat" imports into a hex org and stores
  `homeOrganisationId = "Privat"` on the entry.

Rewritten: `CardImportServiceTest` moves from asserting id prefixes to asserting destination tables.
Deleted: the `ext:`-prefix tests and the 260-char boundary test.

## Out of scope / follow-ups

- **`listMyKeys` is not org-scoped** (`VaultKeyService.java:78`) — `/keys/me` still returns a caller's
  keys across every org. Untouched by this split; needs an API signature change.
- **Signed cards.** `docs/documentVault.md` §2.8.3 still describes an issuer allowlist and Ed25519
  signature that no code implements. Unchanged here and still a product decision.
- **`POST /keys` hardcodes `assurance = PASSKEY`** (`VaultKeyService.java:71`) while accepting a
  client-supplied `publicKey`, so the server cannot know the key was born on-device. Pre-existing and
  unverifiable. It matters more once self-import is the only honest route to a portable own-key; worth
  revisiting if that flow gets real use.
