# Addressbook — import externally-issued key cards

Status: approved, not yet implemented
Module: `document_vault`
Supersedes: the org-match rule in `docs/documentVault.md` §2.8 and the "org match" step in
`docs/superpowers/specs/2026-07-13-document-vault-module-design.md`

## Goal

Give every organisation an addressbook it can put **any** public key into, including keys of holders
who have no Reeve login and whose card was minted outside the platform entirely.

Today this is impossible. Importing a card issued outside Reeve fails:

```json
{ "title": "CARD_ORG_MISMATCH", "status": 422,
  "detail": "The card was issued for organisation Privat, not 75f95560c1d883ee...",
  "instance": "/api/v1/document-vault/cards/import" }
```

## The bug

Two different meanings of "organisation" are compared as if they were one:

| Value | Meaning | Set from |
|---|---|---|
| `VaultKeyEntity.organisationId` (`VaultKeyEntity.java:40`) | **whose addressbook** the entry lives in — a tenancy column | the request (`CardImportService.java:79`) |
| `KeyCardDto.Subject.organisationId` (`KeyCardDto.java:72`) | **which org the holder belongs to** | the card |

`KeyCardVerifier.java:36` requires them equal. That restricts imports to cards whose holder is already
in the importing org, contradicting the module's own stated purpose — `CardImportService`'s javadoc
("This is how a NEW RECIPIENT enters an addressbook") and `CardSubjectType.EXTERNAL` ("the holder has
no Reeve login (e.g. an external auditor)"). An outside issuer cannot know the importer's hex org id,
so `EXTERNAL` cards are unimportable and both the enum value and the `external` column are unreachable
through the real flow.

### Root cause: it is the orphaned tail of the signed-card design

The check is not arbitrary — it is a leftover. `docs/documentVault.md` §2.8.3 specifies import
verification as a five-step chain, and only three steps were built:

| # | Step | Status |
|---|---|---|
| 1 | `v`/`type` supported → `400 UNSUPPORTED_CARD_VERSION` | implemented |
| 2 | no `privateKey` section → `400 CARD_CONTAINS_PRIVATE_KEY` | implemented |
| 3 | `issuer.publicKey` in the configured allowlist → `422 CARD_ISSUER_UNKNOWN` | **never built** |
| 4 | Ed25519 signature over the signing input → `422 CARD_SIGNATURE_INVALID` | **never built** |
| 5 | `subject.organisationId` == request org → `422 CARD_ORG_MISMATCH` | implemented |

In the original design (`specs/2026-07-13-document-vault-module-design.md:93`) the trust anchor was "the
**issuer's signature**, never the importer's word". `subject.organisationId` was a *signed* field, so
step 5 did real work: it stopped a legitimately-signed card minted for org X being replayed into org Y.

Steps 3 and 4 were then descoped — deliberately, and the code says so in three javadocs
(`KeyCardDto`, `KeyCardVerifier`, `CardImportService` all describe permissionless trust-on-first-use).
Step 5 was left behind. With nothing signing the field, step 5 compares client-supplied text against
client-supplied text.

**So the check now has no security value.** Anyone who can POST a card can edit
`subject.organisationId` first; it stops only honest users holding foreign cards. Removing it does not
weaken the model — it finishes the descope and makes the code self-consistent. The genuine boundaries
are elsewhere and are untouched by this work:

1. the caller must be a member of the request org — `OrganisationCheckInterceptor` plus
   `CardImportService.java:47`, anchored to the JWT;
2. the entry always lands in the caller's own org, because `CardImportService.java:79` takes the org
   from the request and never from the card.

This framing also decides the namespacing question below. The 2026-07-13 design already specified
"`subjectId == caller's sub` ⇒ the caller's own key; anything else ⇒ an addressbook entry for that
holder" — but it relied on the signature to stop a forged `subjectId`. That guard left with steps 3–4,
so namespacing is what replaces it.

## Approaches considered

1. **Store the card's org as provenance, never compare it.** Chosen. Keeps the real signal ("Privat")
   visible to the sender, which the trust-on-first-use model depends on. Costs one nullable column.
2. **Drop `subject.organisationId` from the card schema.** Rejected: loses origin, and breaks cards
   already minted with the field.
3. **Keep the field, ignore it.** Rejected: the server would accept and silently discard a documented
   field — a trap for the next reader.

Also rejected: relaxing the check only for `subjectType == EXTERNAL`. It protects nothing (a forger
just sets `subjectType`) and would block legitimate cross-org Reeve recipients.

## Locked decisions

1. `KeyCardVerifier` does not look at organisations at all. Signature becomes `verify(KeyCardDto card)`.
2. `subject.organisationId` is optional, free-form, holder-asserted. It is **stored, never compared**.
3. The card wire format is otherwise unchanged — externally-minted cards keep working as-is.
4. Imported ids are namespaced unless they are the caller's own `sub`, so an import can never claim a
   Reeve account.
5. `document_vault` schema is branch-only (`V1.6_100_13`, absent from `main`), so columns are edited in
   place. **No new migration file, no backfill.**

## Changes

### Verifier

`KeyCardVerifier.verify(card)` keeps exactly the two checks that are the server's job: supported
version/type (`400 UNSUPPORTED_CARD_VERSION`) and no private-key material (`400
CARD_CONTAINS_PRIVATE_KEY`, invariant I5). The org-match branch and its `organisationId` parameter are
removed. The class javadoc's claim that a card must "name the organisation it is being imported into"
is deleted — it describes the bug.

`VaultProblems.CARD_ORG_MISMATCH` (`VaultProblems.java:28`) is removed. Its four other references all go
with it: `KeyCardVerifier.java:37` (deleted with the branch), `KeyCardVerifierTest.java:91` and
`CardImportServiceTest.java:195` (both rewritten — see Testing), and `docs/documentVault.md` (see
Documentation). `docs/superpowers/plans/2026-07-13-document-vault-module.md` also names it, but that is a
historical implementation plan and is left as a record of what was built at the time.

### Card schema

`KeyCardDto.Subject.organisationId`: `@NotBlank` → optional, `@Size(max = 255)`. The JSON field name
stays `organisationId`; renaming it would break cards already in circulation, and the unknown-field
sink would then reject them.

### Data model

Edited into `V1.6_100_13__lob_service_app_document_vault_module.sql`, table `document_vault_key`:

```sql
home_organisation_id VARCHAR(255),          -- holder's own org, as claimed by the card. Never compared.
account_id           VARCHAR(260) NOT NULL, -- widened from 255: 'ext:' prefix + a 255-char subject id
```

`account_id` must widen to 260 because `subjectId` permits 255 characters and the `ext:` prefix adds 4.
Postgres rejects an over-long varchar rather than truncating it, so leaving the column at 255 would turn
a legitimate import into a 500 at flush time. Verified by narrowing the column back to 255 and watching
`aMaximumLengthNamespacedAccountIdRoundTripsWithoutTruncation` fail with SQLState 22001.

`document_vault_document_slot.recipient_ref` widens to 260 too, and `UploadDocumentRequest.SlotRequest`'s
`@Size` with it. It is only a display label and never a trust anchor (I6), but the obvious value for a
client to put there is the recipient's `accountId` — and the two were consistent at 255 before this
change. Widening `account_id` alone would leave a max-length imported id rejected with a 400 on upload.

Not widened: `account_name` (255) is fed by the card's `displayName`, itself `@Size(max = 255)`, so it
stays consistent. `document_vault_wrapped_record.account_id` (255) is only ever written from the caller's
own Keycloak `sub` and never receives an imported id.

`VaultKeyEntity` gains `@Nullable homeOrganisationId`. Its javadoc must state the distinction between
the two org columns explicitly — the two sitting side by side without that note is what caused this bug.

`VaultKeyView` and `RecipientKeyView` both expose `homeOrganisationId`, so a sender picking a recipient
sees the origin. This is load-bearing, not cosmetic: trust here is established out-of-band by a human,
and origin is part of what they judge.

**On re-import, `homeOrganisationId` is set once at creation and never refreshed.** Contract §2.8.5
restricts re-import to refreshing `label` and `email` only; everything else is provenance, fixed at
creation. `homeOrganisationId` is provenance — it records what the card claimed when the holder first
entered this addressbook, and letting a later import silently rewrite the origin a sender already
verified out-of-band would undermine the point of showing it. The existing test
`reimportingAnExistingRowRefreshesOnlyLabelAndEmailNotProvenance` is extended to cover it.

### Namespacing imported ids

In `CardImportService`, applied before the idempotency lookup so the lookup key stays consistent.
`callerSub` is `securityHelper.getCurrentUserId()`:

```
subjectId.equals(callerSub)  → accountId = subjectId          (self-import; lands in /keys/me)
otherwise                    → accountId = "ext:" + subjectId
external                     = !subjectId.equals(callerSub)
```

The branch keys on the **caller's own sub**, deliberately not on `subjectType`. The card is unsigned, so
`subjectType` is forgeable and branching on it would let a card opt out of namespacing. The caller's
`sub` is the only value in this flow anchored to the JWT rather than supplied by the card.

`CardSubjectType` is therefore no longer read by the import path at all. It stays in the card schema as
a holder-asserted hint, but nothing server-side branches on it. Removing it from the wire format is a
separate decision and is not made here.

Consequence, accepted: importing a card *about* a colleague who has a Reeve login files them under
`ext:<their-sub>` instead of binding to their account. That is correct — a user with a login should
self-enroll via `/keys/register`. They remain **addressable**: the addressbook returns each row's
`accountId` and `/recipients/resolve` accepts it back.

They are not, however, able to **fetch**. `VaultDocumentService.fetch` (`VaultDocumentService.java:288`)
gates envelope access on a slot key's `accountId` equalling the caller's login sub, which never matches
`ext:<sub>`. So a document encrypted to an imported card about a colleague cannot be opened by that
colleague through the API, even though they hold the private half. Previously such a card bound
unprefixed and they could. This is a real behavioural regression for that one flow, and it is accepted
rather than papered over: the fix is for the colleague to self-enroll, which is the flow the module
already prescribes. Loosening `fetch` to also match `ext:<own-sub>` would restore it, but would
re-couple the two id spaces that namespacing exists to separate — see follow-ups.

### `external` changes meaning — deliberately

`VaultKeyEntity.external` is currently documented as "true when the holder has no Reeve login", derived
from the card's `subjectType`. Under the rule above it becomes **true when the row is not bound to a
verified Reeve account** — exactly equivalent to `accountId` carrying the `ext:` prefix.

These differ for one case: importing a card about a colleague who *does* have a Reeve login now marks the
row `external`. That is the more honest claim of the two. The old meaning rested on a self-asserted,
unsigned field, so it asserted something the server could not know; the new meaning states something the
server can actually vouch for — this row was never proven to belong to the account it names. The entity
javadoc must be rewritten to say so, rather than left describing the old derivation.

## Why namespacing is in scope

`accountId` is one namespace shared by Keycloak subs (`REEVE_ACCOUNT`) and issuer-minted UUIDs
(`EXTERNAL`), with nothing separating them. `VaultKeyService.listMyKeys` (`VaultKeyService.java:78`)
queries `findByAccountId` **unscoped by org**, and `RecipientResolutionService.java:47` resolves
recipients by `accountId` and wraps to every key it finds (`:90-93`). So a card claiming a real user's
`sub` while carrying an attacker's public key injects that key into the victim's `/keys/me` and adds it
as a silent extra recipient on every document encrypted to them.

This is **pre-existing and not widened by removing the org check** — importing already requires org
membership, and an insider can already forge `subject.organisationId` to their own org on an unsigned
card. The gate never protected this. It is fixed here because this change makes foreign-card import the
normal path, so the collision stops being theoretical.

## Testing

`CardImportServiceTest` uses subject `sub-bob` throughout, exercises the real `KeyCardVerifier`, and
**never stubs `getCurrentUserId()`** — the service does not call it today. Namespacing introduces that
call, so an unstubbed mock would return `null`, every subject would be treated as "not the caller", and
several tests would fail for the wrong reason. `setUp` must therefore stub
`securityHelper.getCurrentUserId()` → `"sub-alice"`, establishing bob-is-not-the-caller and
alice-is-the-caller. Most of the churn below follows from that one fact.

Deleted — pins the bug:

- `KeyCardVerifierTest.rejectsACardIssuedForAnotherOrganisation` → replaced by
  `acceptsACardFromAForeignOrganisation`.

Rewritten — these assert behaviour that legitimately changes:

- `CardImportServiceTest.importingAContactCardCreatesAnAddressbookEntryForTheHolder` (`:98`, `:102`)
  asserts `accountId == "sub-bob"` and `external == false`. Both flip: `ext:sub-bob` and `true`.
- `anExternalHolderIsMarkedExternal` (`:114`) asserts `accountId == "indexer-uuid-1"` → `ext:indexer-uuid-1`.
  It must also stop deriving the expectation from `subjectType`, asserting instead that a `REEVE_ACCOUNT`
  card about a non-caller is *also* external.
- `aRejectedCardWritesNothing` (`:189-195`) uses an org mismatch as its rejection trigger and asserts
  `CARD_ORG_MISMATCH`. That code no longer exists — it must trigger rejection via an unsupported version
  instead, keeping its actual point (a rejected card writes nothing).
- `reimportingTheSameCardUpdatesInPlaceInsteadOfDuplicating` (`:137`) and
  `reimportingAnExistingRowRefreshesOnlyLabelAndEmailNotProvenance` (`:167`) stub the idempotency lookup
  on `"sub-bob"`. The lookup now uses the namespaced id, so the stubs must move to `"ext:sub-bob"` or the
  rows won't be found and the tests will silently exercise the create path instead.
- `reimportingAnExistingRowRefreshesOnlyLabelAndEmailNotProvenance` additionally gains
  `homeOrganisationId` to the set of fields a re-import must not overwrite.
- `importingOwnCardBindsTheKeyToTheCaller` (`:120`) keeps its assertion and finally earns its name: it
  uses subject `sub-alice`, which only means "the caller" once `getCurrentUserId()` is stubbed. Today it
  passes for a hollow reason — `accountId` is unconditionally the subject id.

New:

- A card with `subject.organisationId = "Privat"` imports into a hex org and persists
  `homeOrganisationId = "Privat"` — the exact reported payload, end to end → 200.
- A card claiming another user's `sub` is stored under `ext:<sub>` and does not appear in that user's
  `/keys/me`.
- A card omitting `subject.organisationId` imports and stores `homeOrganisationId = null`.
- A 255-char `subjectId` round-trips through `account_id` without truncation.

Kept unchanged and must still pass:

- `importIntoAForeignOrganisationIsForbidden` — covers the *request* org's 403, which this work does not
  touch. Its name refers to the request org, not the card's.
- `aCardCarryingAPrivateKeyWritesNothing`, `PlaintextAtRestScanIntegrationTest` (invariant I5 is
  untouched).

## Documentation

Three places in `docs/documentVault.md` state the org-match rule and are corrected here:

- `:288` — step 5 of the §2.8.3 verification chain.
- `:554` — the `/cards/import` error list.
- `:581` — the error-code table row.

Corrections are limited to the org-match rule. **Explicitly out of scope:** the same sections describe a
card format this codebase never implemented — `issuer.*` fields, a top-level Ed25519 `signature`, the
allowlist, `CARD_ISSUER_UNKNOWN`, `CARD_SIGNATURE_INVALID`, and `503 CARD_IMPORT_UNAVAILABLE` for a
deployment with no configured issuers. Editing `:554` and `:581` without touching their neighbouring
phantom error codes will look half-done, and that is deliberate: the doc describes steps 3–4 of the
chain above, the code implements trust-on-first-use, and deciding which one is intended is a product
call, not a docs edit. Flag it to the module owner rather than resolving it by rewriting the doc.

## Out of scope / follow-ups

- **Signed cards (steps 3–4).** The doc/code split described above. Either the docs come down to the
  implemented permissionless model, or the signature pipeline gets built. This spec assumes
  permissionless — the direction the code already took, and the one the user's externally-minted card
  requires — but does not foreclose the decision. If signatures are ever built, the org-match rule
  becomes meaningful again and should return *as a signed-field check*, not as it stands today.
- **`listMyKeys` is not org-scoped** (`VaultKeyService.java:78`), so `/keys/me` returns a caller's keys
  across every org they belong to. Pre-existing; namespacing removes the injection route into it, but
  the cross-org leak remains. Needs an API signature change; worth its own change.
- **A colleague cannot fetch a document sent to their imported card** (see Namespacing above). The
  product question is whether importing a card about an existing Reeve user should be supported at all,
  or rejected outright at import time with a "this person should enroll their own key" error — which
  would be clearer than today's silent lockout at fetch time. Decide before this reaches users.
- **`ext:` is a convention, not an enforced invariant.** Nothing stops a self-enrolled `accountId`
  (`VaultKeyService.registerKey`) from beginning with `ext:`; the guarantee rests on Keycloak subs being
  UUIDs. Practically safe, but a validation on registration would make it structural.
