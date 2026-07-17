# Document Vault — Addressbook: what the frontend needs to change

Status: backend implemented on `feat/document-module`, not yet merged. Base path for everything below is
`/api/v1/document-vault`.

## The idea in one paragraph

There are now **two kinds of key**, and they were previously mixed in one table and one list.

- An **organisation key** belongs to a Keycloak user in your org who holds the private half. Their identity
  *is* their account, so we no longer ask for an e-mail — show the Keycloak username, the created date, and
  the label.
- An **addressbook entry** is a public key somebody gave you. Nobody logs in as one. It has no account, so
  its `entryId` is its only handle, and an e-mail (optional) is the only contact detail there is.

The addressbook is per organisation and shared: any member can read, add, edit, and delete entries. There
is no per-entry owner. Nothing in the addressbook is verified — the key is self-asserted, and the sender is
expected to confirm it out-of-band before encrypting to it.

## Breaking changes

| Change | Was | Is now |
|---|---|---|
| Card import moved | `POST /cards/import` | `POST /addressbook/import` |
| Import response | a `VaultKeyView` | an `ImportCardResultView` (see below) |
| `CARD_ORG_MISMATCH` | 422 on any card issued for another org | **gone** — importing an external card is the point |
| Register a key | `RegisterKeyRequest.email` | field removed; sending it is ignored |
| Key view | `VaultKeyView.email` | field removed |
| Resolve recipients | `recipientAccountIds` only | `recipientAccountIds` **and** `recipientEntryIds` |
| Recipient view | one flat shape | adds `kind`, `recipientId`, `homeOrganisationId`; `email`/`origin`/`assurance` may be null |

## New endpoints

Per module convention, **writes carry `organisationId` in the body; reads take it from the path.**

### `GET /organisations/{organisationId}/addressbook`
Paged (`?page=&size=`, default size 20). Member-only. Returns `PagedResponse<AddressbookEntryView>`:

```json
{
  "entryId": "0d9a…-uuid",
  "organisationId": "75f955…",
  "displayName": "Jane Doe",
  "email": "jane@example.org",
  "description": "auditor, Q3 engagement",
  "publicKey": "<64 lowercase hex chars>",
  "assurance": null,
  "homeOrganisationId": "Privat",
  "createdAt": "2026-07-17T10:12:00"
}
```

`entryId` is the only unique field. Names, e-mails and descriptions may repeat freely; a **public key may
not repeat within one organisation**.

### `POST /addressbook` — create
```json
{ "organisationId": "75f955…", "displayName": "Jane Doe",
  "email": "jane@example.org", "description": "auditor", "publicKey": "<64 hex>" }
```
`201` with the created `AddressbookEntryView`. `email` and `description` are optional; `displayName` and
`publicKey` are required. `publicKey` must match `^[0-9a-f]{64}$` — **lowercase hex, no `0x`**; validate it
client-side, users will paste uppercase.

`409 DUPLICATE_PUBLIC_KEY` if that key is already in this org's addressbook. Worth handling properly: offer
to jump to the existing entry rather than showing a raw error.

There is no `assurance` field on create, by design — nobody typing a key into a form can attest to how the
private half was generated. Hand-entered entries keep `assurance: null`.

### `PUT /addressbook/{entryId}` — edit
```json
{ "displayName": "Jane Doe", "email": "jane@example.org", "description": "auditor" }
```
`200` with the updated view. No `organisationId` (the entry already determines it).

**`publicKey` is deliberately not editable.** The key *is* the entry; repointing it would silently redirect
a contact the sender already verified. The UI should not offer a key field on the edit form — to change a
key, delete the entry and create a new one, so the user is forced to verify again.

### `DELETE /addressbook/{entryId}`
`204`. Documents already encrypted to this contact are untouched and stay decryptable; the contact just
stops being offered as a future recipient. Say that in the confirm dialog — it is the question users will
have.

### `POST /addressbook/import` — key card
Same request body as the old `/cards/import`. The response changed:

```json
{ "destination": "ADDRESSBOOK_ENTRY", "key": null, "entry": { …AddressbookEntryView… } }
```
or
```json
{ "destination": "ORG_KEY", "key": { …VaultKeyView… }, "entry": null }
```

**Branch on `destination`, never on the path.** A card whose subject is *you* becomes one of your own
organisation keys and shows up in `/keys/me` — not in the addressbook — even though you posted it to an
addressbook URL. Exactly one of `key`/`entry` is non-null. Re-importing the same card refreshes the
existing row instead of duplicating it.

Still rejected: `400 CARD_CONTAINS_PRIVATE_KEY` (the backend must never hold private key material — it
rejects rather than silently stripping) and `UNSUPPORTED_CARD_VERSION`.

## Recipient picker

`GET /organisations/{organisationId}/recipients` returns colleagues **and** addressbook contacts in one
list. Each `RecipientKeyView` carries a `kind` of `ORG_KEY` or `ADDRESSBOOK_ENTRY`, which decides which
nullable fields mean anything:

| field | `ORG_KEY` | `ADDRESSBOOK_ENTRY` |
|---|---|---|
| `recipientId` | Keycloak sub | entry id |
| `email` | always null | optional |
| `origin` | `SELF_ENROLLED` / `INDEXER_ISSUED` | always null |
| `assurance` | set | often null |
| `homeOrganisationId` | null | the org the card claimed (e.g. `"Privat"`) |

Then `POST /recipients/resolve` splits them back apart:

```json
{ "organisationId": "75f955…",
  "recipientAccountIds": ["<sub>"],
  "recipientEntryIds": ["<entryId>"],
  "senderKeyIds": ["<optional: which of my own devices get a slot>"] }
```

Both lists are individually optional; at least one must be non-empty (`400` otherwise). Route each picked
recipient by its `kind` — the two are kept apart so that "no key bound for account X"
(`RECIPIENT_KEY_MISSING`) and "no such entry Y" (`RECIPIENT_ENTRY_MISSING`) can read differently.

The sender is always added to their own document. `senderKeyIds` narrows which of the caller's own keys get
a slot; null or empty means all of them, and it can never mean none.

## Rendering rules that are not cosmetic

1. **`assurance: null` must render as "unknown"**, not as blank and not as a safe default. Null means nobody
   ever claimed anything about how that key was generated. `PASSKEY` is a stronger promise than `PORTABLE`,
   which is stronger than unknown.
2. **Show `homeOrganisationId` next to it.** An imported card asserts its own home org and we do not check
   it. That string is unverified provenance — it exists so a human can look at it and judge.
3. **Nothing in the addressbook is a trust anchor.** Don't render entries with checkmarks, "verified"
   badges, or anything that implies the backend vouched for them. It didn't. The UI should nudge toward
   out-of-band confirmation on first use.
4. **`email` never leaves the backend** — it is not exported to IPFS or L1. Treat it as org-internal contact
   data.

## Error titles you may see

`ADDRESSBOOK_ENTRY_NOT_FOUND` (404), `DUPLICATE_PUBLIC_KEY` (409), `USER_NOT_IN_ORGANISATION` (403),
`ORGANISATION_NOT_FOUND` (404), `RECIPIENT_ENTRY_MISSING` (422), `RECIPIENT_KEY_MISSING` (422),
`SENDER_KEY_MISSING` (422), `SENDER_KEY_INVALID` (422), `CARD_CONTAINS_PRIVATE_KEY` (400),
`UNSUPPORTED_CARD_VERSION` (422). All are RFC-7807 `ProblemDetail` with the title in `title`.

`CARD_ORG_MISMATCH` no longer exists — remove any handling for it.

## Suggested migration order

1. Repoint `/cards/import` → `/addressbook/import` and branch on `destination`. This alone unblocks
   importing external cards.
2. Drop `email` from the register-key form and the key list; show Keycloak username + created + label.
3. Add `recipientEntryIds` to resolve, splitting by `kind`.
4. Build the addressbook management screen (list / add / edit / delete).