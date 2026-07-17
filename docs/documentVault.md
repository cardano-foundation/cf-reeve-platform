# Reeve Document Vault — Passkey-Gated E2E Encryption: Blueprint & API Contract

**Status:** v4 (Reeve-adapted) · supersedes the product-agnostic blueprint v2 for this platform.
**Purpose:** the single normative contract for the Document Vault. Backend (`document_vault` module), frontend (client crypto core + UI) and the Indexer (§9) are built **in parallel against this document**. Every placeholder from the original blueprint is resolved here; nothing normative lives anywhere else. Backend design details: `docs/superpowers/specs/2026-07-13-document-vault-module-design.md`; on-chain format: `docs/onChainFormat.md` (type `DOCUMENT`).

**What this capability is, in one sentence:** every user gets an encryption keypair generated on their device, whose private half only their passkey (Face ID / Touch ID / Windows Hello) can unlock — so Reeve runs the whole service while remaining cryptographically unable to read protected documents; published documents are additionally anchored on IPFS + Cardano L1, where the independent **Indexer** verifies them without any access to Reeve's database.

**New here? Read §0 (the end-to-end user journey) first — it maps every screen to the endpoints that serve it.**

## Changes vs. the original blueprint (v2 → v4, product decisions)

| # | Change | Consequence |
|---|--------|-------------|
| 1 | **No key revocation.** | Key entries are permanent and immutable. Retiring a key = register a replacement entry. No status field, no revoke endpoint, no bindings-update endpoint anywhere. |
| 2 | **Addressbook e-mail.** | Key registration requires a notification e-mail, shown to fellow org members in the recipient directory. E-mail addresses NEVER leave the Reeve deployment: not in the publish pipeline, not on IPFS, not on L1 (CI-enforced). |
| 3 | **Publishing added.** | `POST /documents/{id}/publish` pins the encrypted envelope to IPFS and anchors a manifest on Cardano L1 (metadata label 1447, type `DOCUMENT`). Hard gate: no IPFS configured → publishing unavailable (503). Published documents are locked forever — no edit, no delete, no retention purge. |
| 4 | **Envelope fetch added (blueprint D2).** | `GET /documents/{id}` serves the encrypted envelope to the creator and recipients. ALL cryptography stays in the frontend — the backend never decrypts, and cannot. |
| 5 | **One key ↔ one organisation.** | A key entry belongs to exactly one organisation (that is where it is discoverable and addressable). Users active in several orgs register one entry per org — the SAME public key may be reused (no extra passkey ceremony, no extra wrapped record); uniqueness is per (account, organisation, publicKey). Key entries are immutable (no bindings-update endpoint). |
| 6 | **All list responses are paged, sortable and filterable.** | Every list endpoint takes `page`, `size`, `sort=field,asc|desc` params and returns the platform `PagedResponse` shape (§4). The org documents list additionally filters by `direction`, `status` and free text `q`. |
| 7 | **The Indexer is the verifying side (blueprint D1/D3).** | Verification is no longer deferred: it lives in a **separate, independent service** (§9) that derives everything from Cardano L1 + IPFS and never reads Reeve's database — which is precisely what makes its verdicts worth anything. It indexes published documents per org, verifies the chain↔IPFS integrity link, and lets anyone trial-decrypt with a key they hold (in its own frontend, client-side). |
| 8 | **Key cards.** | The Indexer can mint an X25519 keypair for a person and issue a **key card** (§2.8): an Ed25519-signed, portable record of a public key + its holder. Importing a card (5.13) is how a user adds a **new recipient** to the org addressbook, and how someone who cannot run a passkey gets a key at all. The issuer signature — not the importer's word — is the trust anchor. |
| 9 | **Two key tiers, always labeled (amends I2).** | `PASSKEY` keys are generated on the owner's device and never leave it. `PORTABLE` keys are Indexer-issued: their private half existed, however briefly, outside the owner's device. The tier is stored, returned by every key/recipient endpoint, and MUST be shown in the UI. A portable key never upgrades to passkey tier — see §2.8.4 for the honest claim this permits. |
| 10 | **Publishing is role-gated (manager or admin).** | Uploading a document is open to any platform role; anchoring it on-chain is not. This follows the platform's existing separation of duties — `ReportingController.publish` and `approveTransactionsPublish` are manager-only, funding's `publishEvent` is manager-or-admin. An accountant's draft therefore needs a manager to publish it. |
| 11 | **Document detail is org-visible; the envelope is not.** | The org-wide list (5.10) already reveals that a document exists, so hiding it behind a 404 on fetch was incoherent. `GET /documents/{id}` now returns the metadata and a key-material-free recipient list to **any org member**, while the envelope — `payload` **and** `slots` — stays restricted to the creator and recipients (`envelopeAccessible`). Non-members still get 404. Knowing a document exists is not the same as holding its wrapped key material. |
| 12 | **Wrapped record v2 (frontend-only change).** | One passkey must be able to hold several keypairs (a self-enrolled key plus imported portable ones), so the record's payload becomes a `keys[]` array. The server stores it as an opaque string, so this costs zero backend work; v1 records stay readable (I7). |

---

## 0. The user journey (start here)

The whole product, end to end, with the endpoint behind every step. "Client-side" below means: the plaintext, the DEK and the private key never leave the browser — the backend could not read the document if it wanted to.

**Step 0 — Log in.** Unchanged Reeve login (Keycloak). The token's `organisations` claim lists the user's orgs; `GET /api/v1/organisations` (existing platform endpoint) supplies their names. *Note for the frontend: that endpoint is unfiltered — it returns every organisation in the system — so the org switcher MUST intersect it with the `organisations` claim itself.*

**Step 1 — The documents page.** `GET /organisations/{orgId}/documents` (5.10) — every document of the organisation, paged, sortable, filterable by `direction` (all / sent by me / shared with me), `status` and free text. Each row shows: file name, description, creator, date, size, a `status` badge (DRAFT / PUBLISHED) and dispatch progress.
On first visit, `GET /keys/me` (5.2) tells the UI whether the user has a key **in this org**. If not, the page leads with an enrollment banner — nothing else here works without a key.

**Step 2 — Open a document (detail).** `GET /documents/{documentId}` (5.9). Any org member sees the full metadata, the recipient list, the hashes, and (once published) the transaction and IPFS links. The page says plainly what it is: *encrypted; only its recipients can read it*. The **envelope** — the ciphertext `payload` and the `slots` — is served **only** to the creator and the recipients; for everyone else both come back `null` with `envelopeAccessible: false`, and the UI shows "You are not a recipient of this document" instead of a decrypt button.

**Step 3 — Decrypt.** The user picks one of their keys and clicks decrypt — a deliberate gesture, never on page load (I3). Loading the keychain costs one biometric prompt: passkey assertion → PRF → KEK → unwrap the wrapped record (§2.5) → the user's keypairs in memory. Imported portable keys (§2.8) join the same keychain. Then, client-side: trial-decrypt the envelope's slots (the first GCM success is authoritative — I6), decrypt the payload with the recovered DEK, recompute `SHA-256(plaintext)` and compare it with `plaintextHash` (CC6). The UI surfaces that verdict. If no slot opens, the honest message is "none of your keys can open this document".

**Step 4 — Create a document ("+").**
1. **Pick the file.** It is never uploaded in the clear, and never leaves the browser un-encrypted.
2. **Choose the key to encrypt with** — i.e. which of the user's own keys must be able to re-open the document later. Existing key from the keychain, or *create a new one* inline: that is the standard enrollment ceremony (§2.2 → `POST /keys` + `PUT /records/{credentialId}`), and it works mid-flow.
3. **Choose recipients.** From the org addressbook (`GET /organisations/{orgId}/recipients`, 5.3), **or add a new one** by importing a key card (`POST /cards/import`, 5.13). An imported recipient is persisted: it joins the org addressbook and is there for everyone, next time and every time. *You cannot encrypt to a person whose public key you do not have — a card is how that key arrives, and its issuer signature is why it can be trusted (§2.8).*
4. **Resolve.** `POST /recipients/resolve` (5.4) with the chosen recipients and `senderKeyIds` → the authoritative, deduped wrap-target set. Never assemble this set client-side.
5. **Encrypt** (CC3–CC5): fresh DEK → AES-GCM over the file → one slot per target key.
6. **Upload** `POST /documents` (5.8): ciphertext + slots + org-internal metadata. The document is now a `DRAFT`.

**Step 5 — Publish.** From the list row or the detail page, for `DRAFT` documents only, and only for a **manager or admin** (change #10) — the button is hidden for other roles. `POST /documents/{documentId}/publish` (5.11) pins the PII-free envelope to IPFS and anchors the manifest on Cardano L1. It is **irreversible** (warn before, no delete afterwards) and it needs IPFS to be configured server-side (`503` otherwise — disable the button). Progress is polled from `status`, `ledgerDispatchStatus`, `txHash`, `ipfsCid`.

**Step 6 — Published rows carry their proof.** Once `txHash` and `ipfsCid` are present, every list row and the detail page link out to: the **L1 transaction** in a Cardano explorer, the **raw IPFS document** via a gateway, and the **Indexer's verification page** for that document (§9). The backend returns the raw `txHash` / `ipfsCid` only — the frontend composes the URLs from its own configured bases (§9.5); there is no explorer or gateway URL in the Reeve API.

### 0.1 Feasibility — what already exists and what this revision adds

| Journey step | Status |
|---|---|
| 0 · Login, org switcher | **Exists.** Keycloak + `GET /api/v1/organisations`, filtered client-side by the `organisations` claim. |
| 1 · Org-wide documents list | **Exists** (5.10, paged/sorted/filtered). |
| 2 · Detail view for any org member | **Changed** — 5.9 now serves metadata + recipients to all org members and ciphertext only to participants (change #11). |
| 3 · Decrypt with a chosen key | **Exists** client-side; the keychain now holds several keys per passkey via **wrapped record v2** (change #12, frontend-only). |
| 4.2 · Choose *which* of my keys can reopen it | **New** — `senderKeyIds` on 5.4; previously all of the sender's keys were auto-included. |
| 4.3 · Add a new recipient, persisted | **New** — key card import (5.13). This is the only safe way: a public key you did not verify is a key-substitution attack waiting to happen, so the card must be signed by a trusted issuer. |
| 5 · Publish button gated by role | **Changed** — manager or admin (change #10); was any role. |
| 6 · Links to the transaction and the IPFS document | **Exists** in the data (`txHash`, `ipfsCid`); the URLs are composed frontend-side (§9.5). |
| — · Verification, decrypt-with-any-key, key issuance | **New — the Indexer (§9)**, a separate service. |

---

## 1. Architecture — thin client, fat backend, independent verifier

```
┌──────────── REEVE FRONTEND — the client crypto core ONLY ──────────────────────┐
│  Passkey PRF ─▶ KEK ─▶ unwrap X25519 priv   │  generate DEK · AES-GCM payload  │
│  wrap/unwrap DEK to recipient keys          │  recompute plaintext hash        │
│  Touch ID / Face ID prompt + user gesture.  Everything here needs a secret.    │
└──────────────▲—— plaintext & unwrapped keys never cross this line ——▲──────────┘
               │                                                       │
   ciphertext + slots + opaque wrapped records          recipient public keys, envelopes
               │                                                       │
┌──────────────┴───────────────────────────────────────────────────────┴────────┐
│  REEVE BACKEND (document_vault + blockchain_publisher):                        │
│  addressbook & recipient resolution · envelope storage/listing/fetch           │
│  wrapped-record store (multi-device sync) · key-card import & verification     │
│  publishing to IPFS + Cardano L1 · retention (drafts only) · audit.            │
│  It orchestrates & stores; it can never read content, and PII (e-mails,        │
│  names, file names) never reaches IPFS/L1.                                     │
└──────────────────────────────┬──────────────────────────────────────────────────┘
                               │  publish: encrypted envelope ─▶ IPFS
                               │           manifest (label 1447) ─▶ Cardano L1
                               ▼
                    ╔══════════════════════╗          ╔═══════════════════════════╗
                    ║  IPFS  +  Cardano L1 ║ ◀─reads─ ║  THE INDEXER (§9)         ║
                    ║  (public, permanent) ║          ║  independent verifier +   ║
                    ╚══════════════════════╝          ║  key issuer. Never touches║
                                                      ║  Reeve's database.        ║
                        signed key cards ◀────────────╢  Its own frontend does    ║
                        (§2.8, imported into Reeve)   ║  client-side decryption.  ║
                                                      ╚═══════════════════════════╝
```

Three components, three jobs. The **Reeve frontend** is the only place secrets exist. The **Reeve backend** stores and orchestrates, and is cryptographically unable to read what it stores. The **Indexer** verifies — and it can only be believed *because* it reads nothing but the public record (Cardano L1 + IPFS); a verifier that shared a database with the thing it verifies would be worth very little. The one artefact that flows from the Indexer back into Reeve is a signed **key card** (§2.8), and it carries no secret.

### 1.1 The irreducible client crypto core (all frontend, nothing else is)

- **CC1 — PRF evaluation** (passkey assertion). Hardware/browser; cannot move.
- **CC2 — KEK derivation + private-key unwrap** (needs the PRF output).
- **CC3 — DEK generation** on create.
- **CC4 — payload AES-GCM** encrypt on create / decrypt on open.
- **CC5 — DEK wrap to recipient public keys** on create / unwrap from a matched slot on open.
- **CC6 — plaintext-hash recompute + compare** on open (the one integrity check needing decrypted bytes).

If a task is not CC1–CC6 (or the UI gesture that triggers them), it is backend work and already covered by the backend plan.

---

## 2. Cryptographic construction (NORMATIVE — all constants resolved)

**Primitives:** WebAuthn PRF extension (CTAP2 `hmac-secret`) · HKDF-SHA-256 · X25519 · AES-256-GCM · SHA-256. All available in browsers via WebCrypto plus a small X25519 library (e.g. `@noble/curves`).

### 2.1 Fixed constants

| Constant | Value | Notes |
|---|---|---|
| RP ID | the deployment's product domain (frontend deployment config) | **Fixed forever once users enroll** — credentials are origin-bound. Choose deliberately per environment; document per deployment. |
| `PRF_SALT` (32 bytes) | `SHA-256("reeve/document-vault/prf-salt/v1")` = `37a30c186dd48cee6d01227a35960275d4c1f243ef8bbc68c53108dc0a7d7eaf` | The single app-wide PRF eval salt (`prf.eval.first`). Per-purpose salts are a future extension. |
| KEK derivation | `KEK = HKDF-SHA-256(ikm = prfOutput, salt = empty, info = UTF-8("reeve/document-vault/kek/v1"), L = 32)` | `prfOutput` = the 32 bytes returned by the PRF extension. |
| Slot-KEK derivation | `slotKEK = HKDF-SHA-256(ikm = X25519(ephPriv, recipientPub), salt = empty, info = UTF-8("reeve/document-vault/slot-kek/v1"), L = 32)` | Fresh ephemeral X25519 keypair **per slot**. |
| Private-key wrap | `wrappedPriv = AES-256-GCM(KEK, nonce = random 12 B, plaintext = raw 32-byte X25519 private key)` | Nonce stored in the wrapped record. |
| DEK wrap (slot) | `wrappedDek = AES-256-GCM(slotKEK, nonce = 12 zero bytes, plaintext = 32-byte DEK)` | Zero nonce is safe **only because** each slotKEK derives from a single-use ephemeral key — keep this comment in code. |
| Payload encryption | `ciphertext = AES-256-GCM(DEK = random 32 B, nonce = random 12 B, plaintext = file bytes)` | One nonce per document, stored in the envelope. |
| Hashes | `plaintext_hash = SHA-256(file bytes)`, `content_hash = SHA-256(ciphertext bytes)` | `content_hash` is server-computed on upload; the client MAY verify it against the upload response. |

**Encodings (everywhere, both directions):** raw keys, hashes, nonces, wrapped DEKs → **lowercase hex**; ciphertext → **base64 (standard, padded)**; `credentialId` → **base64url as produced by WebAuthn** — a CLIENT-side convention: the server treats it as an opaque string (≤ 512 chars) and never validates or decodes it. On `POST /keys` the field is **optional server-side** (the server does not require a passkey linkage), but the standard enrollment flow (§2.2) always supplies it — without it, keychain-loading on a second device (§2.3) cannot find the wrapped record. Field-length summary: `publicKey`/`ephemeralPub` 64 hex chars; `wrappedDek` 96 hex chars (32+16 B); payload `nonce` 24 hex chars; hashes 64 hex chars.

### 2.2 Enrollment — create a NEW passkey (two biometric prompts, inside one user gesture)

1. `navigator.credentials.create()` with `authenticatorAttachment: "platform"`, `residentKey: "required"`, `userVerification: "required"`, `extensions: { prf: {} }`, unique `user.id`, human-readable `user.name`.
2. If `getClientExtensionResults().prf?.enabled === false` → **terminal for this credential** (this store lacks PRF); show store-specific guidance (§7); create no records.
3. Immediately assert: `navigator.credentials.get()` with `allowCredentials: [newCredential]`, `extensions: { prf: { eval: { first: PRF_SALT } } }`.
4. Derive `KEK` (table above). Generate an X25519 keypair in memory; wrap the private key; zero raw key + KEK.
5. Persist server-side, in this order:
   a. `POST /keys` (public key + label + e-mail + the ONE `organisationId` this key serves + credentialId) → `keyId`. A user active in more orgs repeats this call per org (same `publicKey` is fine — each org gets its own key entry).
   b. `PUT /records/{credentialId}` with the **wrapped record** (schema §2.5) — one record per credential, shared by all key entries that reuse the keypair.

### 2.3 Load an EXISTING passkey / second device (one biometric prompt)

1. `navigator.credentials.get()` with `allowCredentials` empty/omitted + the PRF eval — the OS picker lists the user's passkeys for the RP ID.
2. The assertion returns the chosen `credentialId` and the PRF output.
3. `GET /records/{credentialId}` → wrapped record → derive KEK → unwrap → done. (`404` = this passkey has no vault key yet → offer to create one under this credential: generate X25519, wrap with the just-derived KEK, `POST /keys` + `PUT /records/{credentialId}` — no extra prompt needed.)

### 2.4 Create & share a document

1. `GET /organisations/{orgId}/recipients` → render the addressbook; the user picks recipients. A recipient who is not there yet is added by importing a key card (§2.8, `POST /cards/import`) — after which they are a permanent addressbook entry.
2. The user picks **which of their own keys** must be able to reopen the document (usually the one key they have; a chooser only when there are several).
3. `POST /recipients/resolve` with the chosen `recipientAccountIds` **and `senderKeyIds`** → the validated, deduped **wrap-target set**. Never assemble this set client-side. Omitting `senderKeyIds` keeps the old behaviour: all of the sender's keys in the org are included.
4. CC3–CC5: random DEK; encrypt the file; for each returned key, fresh ephemeral X25519 → slotKEK → `wrappedDek`; compute `plaintext_hash`.
5. `POST /documents` with the envelope (§5.8). Keep nothing sensitive client-side afterwards; zero DEK.

**Warn on portable recipients.** Every key returned by `resolve` carries its `assurance` tier (§2.8.4). If any wrap target is `PORTABLE`, the UI must say so before encrypting — the sender is choosing to share with a key whose private half has existed outside its owner's device.

### 2.5 Wrapped record — wire formats v1 and v2 (opaque to the server)

The record is the user's keychain, encrypted under their passkey. **v2** is the format to write: it holds *several* keypairs under one credential, which v1 could not — a user may have a self-enrolled key and one or more imported portable keys (§2.8), and one passkey has to unlock all of them.

```json
{
  "v": 2,
  "credentialId": "<base64url, as produced by WebAuthn>",
  "keys": [
    {
      "pub": "<64 hex — X25519 public key>",
      "wrappedPriv": "<96 hex — AES-256-GCM(KEK, nonce, 32-byte private key) incl. tag>",
      "nonce": "<24 hex — the wrap nonce>",
      "label": "<human-readable key label>",
      "assurance": "PASSKEY | PORTABLE"
    }
  ]
}
```

Every entry is wrapped under the **same KEK** (§2.1) derived from this credential's PRF output — including imported portable keys, which is how a card's key survives a page reload without ever being stored in the clear. `assurance` is a local display hint; the authoritative value is the one the server returns for the key entry.

v1 (single key, flat `pub`/`wrappedPriv`/`nonce`/`label` fields) remains valid and MUST still be read — versions are added, never removed (I7). Readers that meet a `v` they do not know MUST fail visibly rather than guess.

```json
{ "v": 1, "credentialId": "…", "label": "…", "pub": "<64 hex>", "wrappedPriv": "<96 hex>", "nonce": "<24 hex>" }
```

Serialised as a JSON string into the `record` field of `PUT /records/{credentialId}` (≤ 8192 bytes UTF-8 — roughly 30 keys, far beyond any real keychain). The server stores and returns it **byte-identical** and never parses it, so v2 costs the backend nothing.

### 2.6 Open (decrypt) a document — the flow the new fetch endpoint enables

1. `GET /organisations/{orgId}/documents` (org-wide list, §5.10 — filter/sort/page as the UI needs) → user clicks a document.
2. `GET /documents/{documentId}` → metadata + recipients for any org member; the envelope (`payload` + `slots`) only if the caller is the creator or a recipient (§5.9). When `envelopeAccessible` is `false`, there is nothing to decrypt — say so, and do not offer the button.
3. Match `slots[].keyId` against the user's own keys (`GET /keys/me`, cacheable). Matching slots are **candidates only** — labels, never trust anchors.
4. From a click (never on page load; I3): the user picks the key to try (their keychain, §2.5 — self-enrolled and imported portable keys alike); passkey assertion + PRF (CC1) → KEK → unwrap the private keys (CC2; wrapped record from local cache or `GET /records/{credentialId}`).
5. For each candidate slot (fall back to ALL slots if none match): `slotKEK = HKDF(X25519(priv, slot.ephemeralPub), …)`; try `AES-256-GCM-decrypt(slotKEK, zero nonce, slot.wrappedDek)`. GCM authentication rejects wrong keys; **the first success is authoritative** (I6).
6. Decrypt the payload with the DEK (CC4); recompute `SHA-256(plaintext)` and compare with `plaintextHash` (CC6); surface pass/fail; zero all key material.

The same six steps run in the Indexer's frontend (§9.4) against the IPFS copy of a published document — same crypto core, different source of bytes, no Reeve login required.

### 2.7 Non-negotiable invariants (adopted from the blueprint, adjusted)

- **I1** Unwrapped private keys, KEKs and DEKs exist only in frontend memory (Reeve's or the Indexer's), are zeroed after use, and are never transmitted to any backend.
- **I2 (amended by change #9)** Every unwrap of a `PASSKEY`-tier key requires a fresh, user-verified PRF evaluation, and its private half never leaves the owner's device. `PORTABLE`-tier keys (Indexer-issued, §2.8) are the **one** exception, and they are never silent: the tier is stored server-side, returned by every key and recipient endpoint, and MUST be displayed wherever a key is chosen or a recipient is picked. A portable key never becomes a passkey key (§2.8.4).
- **I3** WebAuthn calls run directly inside a user gesture; do the WebAuthn step first when mixing with slow async work.
- **I4** `prf.enabled === false` is terminal for that credential; no silent downgrade.
- **I5** The backend stores only ciphertext and opaque records; no API accepts or returns a DEK, plaintext, or unwrapped private key. Serving *ciphertext* to authorized recipients is expressly allowed — that is what `GET /documents/{id}` does. Card import (5.13) enforces the same line from the other side: a card that still carries its `privateKey` section is **rejected** (`400 CARD_CONTAINS_PRIVATE_KEY`), not quietly stored.
- **I6** Slot selection by GCM-authenticated trial, never by trusting identifiers.
- **I7** Wire formats are versioned (`v` in records and cards, `envelopeVersion` in envelopes); versions are added, never removed.
- **I8** Confidentiality (encryption) and authenticity (signatures/L1 anchoring) are separate mechanisms; the UI never conflates them. A key card's signature proves **who vouched for the key**, never that the holder is trustworthy.
- **I9** Honest claims: "the operator cannot read content **provided the delivered client is genuine**" — pair with CSP/SRI and signed builds.
- **I10** PII (e-mail, display names, recipient labels, file names, account ids) never reaches IPFS or Cardano L1. Key cards carry PII (name, e-mail) and are therefore **never** published — they travel only between the Indexer, the user, and the Reeve backend.

### 2.8 Key cards (NORMATIVE)

A **key card** is a signed, portable statement: *"this X25519 public key belongs to this person, in this organisation — and I, the issuer, vouch for it."* It exists because of one hard fact: you cannot encrypt to someone whose public key you do not have. Letting any user assert "this key is Bob's" would be a key-substitution attack — the attacker registers their own key under Bob's name and silently receives everything meant for Bob. The issuer's signature, not the importer's word, is what makes adding a recipient safe.

Cards are minted by the Indexer (§9.4) and imported into Reeve with `POST /cards/import` (5.13).

#### 2.8.1 Two kinds

| Kind | Contains | Used for |
|---|---|---|
| **Contact card** | public part only | Adding a **recipient** to an org addressbook — the common case in the create-document flow (§0, step 4.3). Safe to pass around: it holds no secret. |
| **Handover card** | public part **+** a passphrase-wrapped private key | Giving a key to a person who cannot run a passkey (an external auditor, a device with no PRF support). The passphrase travels **out of band** — never with the card. |

#### 2.8.2 Wire format v1

```json
{
  "v": 1,
  "type": "REEVE_KEY_CARD",
  "subject": {
    "subjectType": "REEVE_ACCOUNT",
    "subjectId": "8d9e…keycloak-sub… | …indexer-minted uuid…",
    "displayName": "Bob Miller",
    "email": "bob@example.org",
    "organisationId": "75f95560c1d8…ca94"
  },
  "key": {
    "publicKey": "<64 hex — X25519>",
    "label": "Bob's audit key",
    "assurance": "PORTABLE",
    "createdAt": "2026-07-14T10:15:30Z"
  },
  "issuer": {
    "issuerId": "reeve-indexer-prod",
    "algorithm": "Ed25519",
    "publicKey": "<64 hex — Ed25519>"
  },
  "signature": "<128 hex — Ed25519 over the signing input below>",

  "privateKey": {
    "algorithm": "AES-256-GCM",
    "kdf": { "name": "PBKDF2-HMAC-SHA-256", "iterations": 600000, "salt": "<32 hex>" },
    "nonce": "<24 hex>",
    "wrapped": "<96 hex — AES-256-GCM(cardKey, nonce, 32-byte X25519 private key)>"
  }
}
```

- `subjectType` is `REEVE_ACCOUNT` (the holder logs into Reeve; `subjectId` is their Keycloak `sub`) or `EXTERNAL` (they do not; `subjectId` is a UUID minted by the Indexer). External holders are addressable in the addressbook and decrypt published documents in the Indexer's frontend — they never sign into Reeve.
- `privateKey` is **present only on a handover card** and is **stripped by the client before import** — the Reeve backend rejects any card that still carries it (I5). `cardKey = PBKDF2-HMAC-SHA-256(passphrase, salt, iterations, 32 bytes)`.
- The card is a JSON file the user downloads and imports. It is not a bearer token: possessing a contact card grants nothing.

#### 2.8.3 Signing input (exact — three implementations must agree)

The signature is Ed25519 over a **length-prefixed concatenation**, not over the JSON text. This removes every canonicalisation question (key order, whitespace, unicode escaping) that would otherwise have to be answered identically by a Java verifier, a TypeScript issuer and a TypeScript importer.

Let `enc(s)` = 4-byte big-endian length of the UTF-8 bytes of `s`, followed by those bytes. The signing input is `enc(...)` of exactly these fields, in exactly this order:

```
"REEVE_KEY_CARD" , "1" ,
subject.subjectType , subject.subjectId , subject.displayName , subject.email , subject.organisationId ,
key.publicKey , key.label , key.assurance , key.createdAt ,
issuer.issuerId , issuer.algorithm , issuer.publicKey
```

Absent optional strings encode as empty (`enc("")` = four zero bytes). `signature` and `privateKey` are never part of the input. Any change to this list is a new card version (I7).

Verification (backend, on import), in this order:
1. `v` = 1, `type` = `REEVE_KEY_CARD`, and `issuer.algorithm` = `Ed25519` — otherwise `400 UNSUPPORTED_CARD_VERSION`. The algorithm is checked even though it is a signed field: a card must never name one algorithm while the server verifies it under another.
2. No `privateKey` section — otherwise `400 CARD_CONTAINS_PRIVATE_KEY`. Checked **before** the signature, so an unsigned card stuffed with key material is rejected too.
3. `issuer.publicKey` appears in the deployment's configured issuer allowlist **and** is the key registered for that `issuerId` — otherwise `422 CARD_ISSUER_UNKNOWN`. A card signed by a key nobody configured is worthless; that is the whole point.
4. The Ed25519 signature verifies over the signing input above — otherwise `422 CARD_SIGNATURE_INVALID`.
5. ~~`subject.organisationId` equals the request's `organisationId`.~~ **Removed.** `subject.organisationId` is the HOLDER's own organisation — a free-form label like "Privat" on a card minted outside Reeve — and has nothing to do with which addressbook the card is imported into. It is stored as provenance (`document_vault_key.home_organisation_id`), shown to senders picking a recipient, and never compared. The rule made sense only while the field was issuer-signed (steps 3–4): with no signature it compared client input against client input, and it made external cards — the ones the addressbook exists to hold — unimportable. Which organisation the entry lands in is decided by the request, whose org is authorised against the caller's JWT.

> **Note:** steps 3 and 4 above are **not implemented**. The shipped code accepts unsigned, self-asserted cards (trust-on-first-use); there is no issuer allowlist and no signature check, so `CARD_ISSUER_UNKNOWN`, `CARD_SIGNATURE_INVALID` and `CARD_IMPORT_UNAVAILABLE` are not returned by any code path. This section describes the originally designed signed-card model. Resolving the split — build the signature pipeline, or bring this doc down to the implemented model — is an open product decision. If signatures are ever built, the org-match rule becomes meaningful again and should return **as a check on a signed field**, which is not what it was.

#### 2.8.4 Assurance tiers — and the honest claim

| Tier | How the private key was born | What you may claim |
|---|---|---|
| `PASSKEY` | Generated on the owner's device, wrapped under a passkey-derived KEK, never exported. | "Only the owner, present at their device with their biometric, can read this." |
| `PORTABLE` | Generated in the Indexer's frontend by an operator and handed over on a card. | "Only someone holding this key can read this." **Not**: "only the named person can read this." An operator saw the private key when it was minted; a card can be copied. |

The tier is **provenance, not storage**, so it never improves. Importing a portable key into Reeve and wrapping it under a passkey is a convenience — it does not un-see what the operator saw, and the tier stays `PORTABLE` for the life of the key. A holder who wants a `PASSKEY`-tier key enrols a new one (§2.2); that is cheap, and it is the honest path.

#### 2.8.5 When an issuer is compromised — the containment model

An issuer key is a trust anchor: whoever holds it can vouch for any public key, and every Reeve deployment that lists it will believe them. That is not a flaw in the design, it is what a trust anchor *is* — but it means "we documented the risk" is not an answer. The damage a stolen issuer key can do must be **bounded**, and it is, by three properties that hold without any key-revocation mechanism (there is none, by product decision — see change #1):

1. **De-trusting an issuer withdraws every key it ever vouched for — instantly, by config.** Each key entry stores the `issuerId` that vouched for it. The addressbook (5.3) and recipient resolution (5.4) return an `INDEXER_ISSUED` key **only while its issuer is still in `lob.document_vault.card.issuers`**. Remove the compromised issuer from that list and every key it introduced — hostile or not — stops being offered as a wrap target and vanishes from the recipient picker. Nothing can be encrypted to them again. This is the kill switch, and it is one config change with no migration, no endpoint, and no per-key lifecycle.
   Its limit, stated plainly: **you cannot un-send.** Documents already encrypted to a hostile key stay readable by whoever holds it. No mechanism in any design fixes that; only detection speed does.
2. **Substitution is visible at the moment of encryption.** `resolve` returns the exact wrap-target set, each entry carrying `assurance`, `origin` and `issuerId`. The UI **MUST** render that set before encrypting, and **MUST** flag (a) any recipient account holding more than one key — the signature of a substituted key — and (b) any `INDEXER_ISSUED` key. A sender about to encrypt to "Bob (2 keys: 1 passkey, 1 indexer-issued)" is being shown the attack.
3. **Forged cards are detectable.** The Indexer keeps a registry of every card it issued (§9.4, public parts only). An `INDEXER_ISSUED` addressbook entry whose `(subjectId, publicKey)` is **not** in that registry was signed by the issuer key *outside* the issuance flow — i.e. with a stolen key. Diffing the two is a cheap, periodic anomaly check, and it is the thing that turns "compromised six months ago" into "compromised on Tuesday".

**Operationally**, therefore: the issuer signing key lives server-side in the Indexer only (never in a browser, never in the Reeve backend), belongs in an HSM/KMS, and issuance is an authenticated, logged action. Rotation is a config change. A deployment that wants none of this configures no issuer at all and loses only the ability to add recipients who never enrolled (`503`, §5.13).

Own keys are a deliberate exception to rule 1: `GET /keys/me` still returns your `INDEXER_ISSUED` keys after their issuer is de-trusted, marked `issuerTrusted: false`, because you still need them to *decrypt documents you already received*. They are simply no longer usable as a target for anything new — including by you.

---

## 3. Publishing (IPFS + Cardano L1)

Explicit user action on a `DRAFT` document, **restricted to managers and admins** (§4). The backend pins the **PII-free** envelope document to IPFS and anchors a manifest on Cardano L1 (label 1447, type `DOCUMENT` — full format in `docs/onChainFormat.md`). What the frontend needs to know:

- Publish requires IPFS to be configured server-side; otherwise the endpoint returns `503 DOCUMENT_PUBLISHING_UNAVAILABLE`. Surface this state (e.g. disable the button after a failed probe).
- Publishing **locks the document forever** (no delete). Warn before the action; it is irreversible even if on-chain dispatch later fails (the operator retries server-side).
- **Only managers and admins see the button.** An accountant or auditor who created a draft needs a manager to publish it — the same separation of duties the platform already applies to report publishing and transaction dispatch. Hide the action for other roles rather than letting it fail with a 403.
- Progress is visible via the listing/fetch fields: `status` (`DRAFT` | `PUBLISHED`) and `ledgerDispatchStatus` (`NOT_DISPATCHED → MARK_DISPATCH → DISPATCHED → COMPLETED → FINALIZED`, or `FAILED` with `ledgerDispatchError` — note the vault surfaces FAILED while the publisher may still retry). `txHash` and `ipfsCid` appear as the pipeline progresses.
- The published IPFS document contains: `version`, `type: "REEVE_ENCRYPTED_DOCUMENT"`, `org_id`, `content_hash`, `plaintext_hash`, `payload{ciphertext, nonce}` and `slots[{ephemeral_pub, wrapped_dek}]` — **no recipient identifiers, no keyIds, no file name, no e-mails**. Recipients decrypt a published document exactly like a private one (trial decryption; identifiers unnecessary).
- Once `txHash` and `ipfsCid` are set, the row links out: the L1 transaction, the raw IPFS document, and the Indexer's verification page (§9.5 — the frontend composes all three URLs from its own configured bases).

---

## 4. Authentication, authorization & conventions (all endpoints)

- **Auth:** `Authorization: Bearer <Keycloak access token>` (the platform's existing OAuth2 setup).
- **Roles:** any platform role (`manager`, `admin`, `accountant`, `auditor`) is accepted on every vault endpoint **except publishing**:

| Endpoint | Roles |
|---|---|
| `POST /documents/{id}/publish` (5.11) | **`manager` or `admin` only** — anchoring on-chain is irreversible. Matches funding's `publishEvent`; the platform is even stricter elsewhere (report publish and transaction dispatch are manager-only). |
| everything else (keys, records, addressbook, resolve, upload, list, fetch, card import, delete) | any of the four roles, subject to org membership and the per-endpoint ownership rules. |

- **Identity:** the account id is the token's OIDC `sub` claim. Org membership comes from the token's `organisations` claim; org-scoped endpoints reject non-members. There is no user table — display names come from the `name` claim, captured when a key is registered.
- **Conventions:** JSON everywhere; errors are RFC 7807 `application/problem+json` bodies whose `title` carries the machine-readable code (§6) and `status` the HTTP status. `organisationId` values are the platform's 64-char org hashes. Timestamps are ISO-8601 (server local, consistent with the rest of the platform API).
- **Pagination (all list endpoints):** request params `page` (0-based), `size`, `sort=<field>,<asc|desc>` (repeatable); defaults return everything sorted by the endpoint's default. Response shape:

```json
{ "content": [ … ], "total": 123, "totalPages": 7, "page": 0, "size": 20 }
```

- **Base path:** `/api/v1/document-vault`. Live OpenAPI (springdoc) is served by the running backend; this section is the frozen contract for parallel development.

## 5. API reference

### 5.1 `POST /keys` — register an encryption key (enrollment)

Request (one org per key entry; repeat the call per org, same `publicKey` allowed):
```json
{
  "organisationId": "75f95560c1d8…ca94",
  "label": "MacBook Touch ID",
  "publicKey": "a1b2…64-hex…",
  "email": "alice@example.org",
  "credentialId": "kFj3…base64url…"
}
```
`201` → `VaultKeyView`:
```json
{
  "keyId": "0b0f7d1e-6f0a-4d9e-9d5e-1c2b3a4d5e6f",
  "organisationId": "75f95560c1d8…ca94",
  "label": "MacBook Touch ID",
  "publicKey": "a1b2…",
  "email": "alice@example.org",
  "credentialId": "kFj3…",
  "assurance": "PASSKEY",
  "origin": "SELF_ENROLLED",
  "external": false,
  "issuerId": null,
  "issuerTrusted": true,
  "createdAt": "2026-07-14T10:15:30"
}
```
Self-enrollment is always `assurance: PASSKEY` / `origin: SELF_ENROLLED` — the key was born on this device (§2.8.4). Keys that arrive by card import (5.13) carry `INDEXER_ISSUED` and whatever tier the card asserts.

`issuerId` is the card issuer that vouched for the key, and is `null` for a self-enrolled key — nobody vouched for it but you. `issuerTrusted` says whether that issuer is *still* configured in `lob.document_vault.card.issuers` (§2.8.5); a self-enrolled key has no issuer to de-trust, so it is always `true`. A key whose issuer has been de-trusted stays in `GET /keys/me` with `issuerTrusted: false` — you keep it to decrypt what you already received — but it is no longer offered as a wrap target anywhere (§5.3, §5.4) and is rejected if a stale client tries to use it in a slot (§5.8).
Errors: `403 USER_NOT_IN_ORGANISATION`, `404 ORGANISATION_NOT_FOUND`, `409 DUPLICATE_PUBLIC_KEY` (same key, same account, same org), `400` (bean validation: hex format, e-mail, blank fields).

### 5.2 `GET /keys/me` — own keys (paged)

`200` → `PagedResponse<VaultKeyView>` across all the caller's orgs. Use to map envelope slots to "my" keys (§2.6 step 3) and to detect "no key in this org yet → enroll". Key entries are immutable — there is no bindings-update endpoint; to use a key in another org, register it there (5.1).

Each entry also carries `issuerTrusted`. It is `false` for an `INDEXER_ISSUED` key whose issuer has since been de-trusted (§2.8.5): the key still appears here — you need it to decrypt documents you already received — but it can no longer be used as a target for anything new, by you or anyone else. Show that state; do not silently offer it as an encryption key.

### 5.3 `GET /organisations/{organisationId}/recipients` — addressbook (paged)

`200` → `PagedResponse` whose `content` entries are:
```json
{
  "accountId": "8d9e…keycloak-sub… (or the card's subjectId for an external holder)",
  "displayName": "Bob Miller",
  "email": "bob@example.org",
  "keyId": "…uuid…",
  "publicKey": "…64 hex…",
  "label": "Bob's iPhone",
  "assurance": "PASSKEY",
  "origin": "SELF_ENROLLED",
  "issuerId": null,
  "external": false
}
```
One entry per key registered in this org (an account with two keys appears twice — group by `accountId` in the UI). Entries created by importing a key card (5.13) carry `origin: "INDEXER_ISSUED"`, the vouching `issuerId`, the card's `assurance`, and `external: true` when the holder has no Reeve login (an auditor who reads published documents in the Indexer, §9).

**Keys whose issuer is no longer trusted are not returned at all** (§2.8.5) — de-trusting an issuer removes every key it vouched for from this directory.

The picker **MUST** show `assurance` (I2 — a `PORTABLE` recipient is a weaker promise than a `PASSKEY` one) and **MUST** flag any account that appears with **more than one key**, which is what a substituted key looks like. `403 USER_NOT_IN_ORGANISATION` for non-members.

### 5.4 `POST /recipients/resolve` — the wrap-target set

Request (`senderKeyIds` optional):
```json
{ "organisationId": "75f9…", "recipientAccountIds": ["8d9e…", "7c1a…"], "senderKeyIds": ["…uuid…"] }
```
`200` → `[RecipientKeyView]` (plain array — bounded by the request, not paged; entry shape as in 5.3): validated, deduped by public key — encrypt the DEK to exactly this set, nothing else.

**This response is a security surface, not a convenience.** It names every key the document will be readable by, including keys the sender never picked (a recipient with two devices has two). The UI **MUST** show it before encrypting, flagging multi-key recipients and `INDEXER_ISSUED` keys (§2.8.5). Keys from a de-trusted issuer are excluded here too — if that leaves a named recipient with no usable key, the call fails with `422 RECIPIENT_KEY_MISSING` rather than quietly dropping them; and if it leaves the *caller* with none, `422 SENDER_KEY_MISSING`.

`senderKeyIds` names which of the **caller's own** keys in this org get a slot, i.e. which of their devices can reopen the document later (§0 step 4.2). Omit it and every key the caller has in the org is included, which is the old behaviour and the right default. Pass `[]` and it is treated as omitted — the sender is always a recipient of their own document; a write-only document is not a feature.

Errors: `422 RECIPIENT_KEY_MISSING` (detail names the accounts without usable keys), `422 SENDER_KEY_MISSING` (enroll first), `422 SENDER_KEY_INVALID` (a listed `senderKeyIds` entry is not one of the caller's keys in this org), `403 USER_NOT_IN_ORGANISATION`.

### 5.5 `PUT /records/{credentialId}` — store the wrapped record

Request: `{"record": "<the §2.5 JSON as a string>", "version": 1}` → `200 WrappedRecordView {credentialId, record, version, updatedAt}`. `413 PAYLOAD_TOO_LARGE` above 8192 bytes. Upsert; scoped to the caller's account.

### 5.6 `GET /records/{credentialId}` / 5.7 `GET /records` (paged)

`200 WrappedRecordView` / `200 PagedResponse<WrappedRecordView>`, own records only; `404 RECORD_NOT_FOUND` if absent (= this passkey has no vault key yet, §2.3 step 3).

### 5.8 `POST /documents` — upload an envelope

Request:
```json
{
  "organisationId": "75f9…",
  "envelopeVersion": 1,
  "fileName": "q3-report.pdf",
  "contentType": "application/pdf",
  "description": "Q3 board report",
  "plaintextHash": "…64 hex…",
  "payload": { "ciphertext": "<base64>", "nonce": "…24 hex…" },
  "slots": [
    { "keyId": "…uuid from resolve…", "recipientRef": "Bob Miller", "ephemeralPub": "…64 hex…", "wrappedDek": "…96 hex…" }
  ]
}
```
`fileName`/`contentType`/`description` are optional, org-internal metadata (never published). `201` → `{"documentId": "…", "contentHash": "…64 hex…", "createdAt": "…"}` — the client MAY verify `contentHash == SHA-256(ciphertext)`.

Upload re-checks every slot's key: it must exist, belong to this org, **and still have a trusted issuer** (§2.8.5). That last check closes the window where a client resolved recipients, an issuer was de-trusted, and the stale client uploaded anyway — `422 SLOT_KEY_INVALID`, re-resolve and re-encrypt. (Resolve was never an authorization gate — a hostile client can put any key in a slot — but an honest client with stale state is the case worth catching.)
Errors: `422 SLOT_KEY_INVALID | TOO_MANY_SLOTS | UNSUPPORTED_ENVELOPE_VERSION`, `413 PAYLOAD_TOO_LARGE` (default cap 10 MiB ciphertext), `400 INVALID_PAYLOAD`, `403`, `404 ORGANISATION_NOT_FOUND`. Caps: ≤ 64 slots, ≥ 1 slot.

### 5.9 `GET /documents/{documentId}` — document detail ("open a document")

**Any member of the document's organisation** gets the detail. `404 DOCUMENT_NOT_FOUND` for an unknown id or a caller outside the org. The **envelope** — `payload` *and* `slots` — is returned **only to the creator and the recipients**; everyone else gets both as `null` with `envelopeAccessible: false`, and a key-material-free `recipients` summary instead.

Why not a blanket 404 for non-recipients? Because the org-wide list (5.10) already shows that the document exists, to every member, by design. Hiding it here would have protected nothing and broken the detail page for the org. But "you may know it exists" is not "you may have its crypto material" — hence the split below.

```json
{
  "documentId": "…",
  "organisationId": "75f9…",
  "status": "DRAFT",
  "envelopeVersion": 1,
  "fileName": "q3-report.pdf",
  "contentType": "application/pdf",
  "description": "Q3 board report",
  "sizeBytes": 482133,
  "contentHash": "…64 hex…",
  "plaintextHash": "…64 hex…",
  "envelopeAccessible": true,
  "payload": { "ciphertext": "<base64>", "nonce": "…24 hex…" },
  "slots": [
    { "keyId": "…", "recipientRef": "Bob Miller", "ephemeralPub": "…", "wrappedDek": "…" }
  ],
  "recipients": [
    { "keyId": "…", "accountId": "8d9e…", "displayName": "Bob Miller", "label": "Bob's iPhone", "assurance": "PASSKEY" }
  ],
  "ledgerDispatchStatus": "NOT_DISPATCHED",
  "ledgerDispatchError": null,
  "txHash": null,
  "ipfsCid": null,
  "createdByName": "Alice",
  "createdAt": "2026-07-14T10:15:30"
}
```

- `envelopeAccessible` — `true` iff the caller is the creator or holds a key referenced by a slot. When `false`, **both `payload` and `slots` are `null`**: show "You are not a recipient of this document" and no decrypt button.
- `payload` + `slots` — the envelope. Participants only. A `wrappedDek` is useless without the matching private key, but it is still wrapped key material, and there is no reason to hand it to someone who cannot use it: a draft is not public, so the "it is on IPFS anyway" argument does not apply to it. Give out no more than the job needs.
- `recipients` — who this document was encrypted for, i.e. the answer to "who can read this?". Org-visible, derived from the slots' `keyId`s, and carrying **no key material at all** (no `wrappedDek`, no `ephemeralPub`). This is what makes a useful detail page for a non-participant.
- This remains the **only** Reeve endpoint that returns ciphertext, and only to participants (I5). A published document's ciphertext is additionally, and deliberately, public on IPFS — anyone may fetch it from there by `ipfsCid`. Decryption flow: §2.6.

### 5.10 `GET /organisations/{organisationId}/documents` — org-wide document list (paged, sorted, filtered)

Returns ALL documents of the organisation to any org member (metadata is org-visible by design; ciphertext access stays restricted to 5.9's participant check). Query params, all optional:

| Param | Values | Meaning |
|---|---|---|
| `direction` | `SENT` \| `RECEIVED` | Relative to the caller: `SENT` = created by me; `RECEIVED` = a slot references one of my keys. Omit for all. |
| `status` | `DRAFT` \| `PUBLISHED` | Publish state filter. |
| `q` | free text ≤ 255 | Case-insensitive substring over `fileName` and `description`. |
| `page`, `size`, `sort` | pagination (§4) | Sortable fields: `createdAt`, `fileName`, `sizeBytes`, `status`. Defaults: `sort=createdAt,desc`, `size=20` (this list can grow large; the other, self-scoped lists default to returning everything). |

`200` → `PagedResponse<DocumentView>` (envelope-free `content` entries):
```json
{
  "content": [{
    "documentId": "…", "fileName": "q3-report.pdf", "contentType": "application/pdf",
    "description": "Q3 board report", "sizeBytes": 482133, "contentHash": "…",
    "envelopeVersion": 1, "status": "PUBLISHED", "ledgerDispatchStatus": "FINALIZED",
    "ledgerDispatchError": null,
    "txHash": "3f9a…", "ipfsCid": "bafy…", "createdByName": "Alice", "createdAt": "…"
  }],
  "total": 42, "totalPages": 3, "page": 0, "size": 20
}
```
`403 USER_NOT_IN_ORGANISATION` for non-members.

### 5.11 `POST /documents/{documentId}/publish`

**Roles: `manager` or `admin` only** (§4) — plus org membership. No body. `200` → `DocumentView` with `status: "PUBLISHED"`, `ledgerDispatchStatus: "MARK_DISPATCH"`. Irreversible.
Errors: `503 DOCUMENT_PUBLISHING_UNAVAILABLE` (no IPFS in this deployment), `409 ALREADY_PUBLISHED`, `403` (role not permitted — Spring's method-security response), `403 USER_NOT_IN_ORGANISATION`, `404 DOCUMENT_NOT_FOUND`.

### 5.12 `DELETE /documents/{documentId}`

`204` on success. Creator or admin, org member, **`DRAFT` only**: `409 DOCUMENT_PUBLISHED_IMMUTABLE` for published documents, `403 USER_NOT_IN_ORGANISATION` (not a member of the document's org), `403 NOT_DOCUMENT_CREATOR`, `404 DOCUMENT_NOT_FOUND`.

### 5.13 `POST /cards/import` — import a key card

The one way a key enters Reeve without a passkey ceremony, and the way a **new recipient** is added to an addressbook (§0 step 4.3). Any role; org membership required. The card's issuer signature is the trust anchor — see §2.8.3 for exactly what is verified.

Request — the card (§2.8.2) with its `privateKey` section **removed by the client**:
```json
{
  "organisationId": "75f95560c1d8…ca94",
  "card": { "v": 1, "type": "REEVE_KEY_CARD", "subject": { … }, "key": { … }, "issuer": { … }, "signature": "…" }
}
```

The server decides what the card *is* from its subject — the client does not get to choose:

| Card subject | Result |
|---|---|
| `subjectType: REEVE_ACCOUNT` and `subjectId` == the caller's `sub` | **The caller's own key.** Appears in `GET /keys/me`; usable to encrypt and decrypt. Wrap its private half into the caller's wrapped record (§2.5, v2) to keep it across sessions. |
| any other subject (another account, or `EXTERNAL`) | **An addressbook entry** for that holder in this org. Immediately selectable as a recipient, permanently, by every member of the org. |

`200` → `VaultKeyView` (with `assurance`, `origin: "INDEXER_ISSUED"`, `external`, `issuerId` = the issuer that signed the card, and `issuerTrusted: true` — the issuer was just checked against the allowlist, so it is trusted by definition at this moment). **Idempotent**: re-importing the same card (same org, same public key, same subject) returns the existing entry and refreshes its `label`/`email` from the card — importing a recipient twice is a normal thing for a user to do, and it is not an error.

A key imported here stops being addressable the moment its issuer leaves `lob.document_vault.card.issuers` (§2.8.5): it disappears from the addressbook (§5.3) and from resolve (§5.4), and upload rejects any slot naming it (§5.8). Nothing about the key row changes — the trust decision lives entirely in config.

Errors (as implemented): `400 CARD_CONTAINS_PRIVATE_KEY` (strip it client-side; the backend must never hold one — I5), `400 UNSUPPORTED_CARD_VERSION`, `403 USER_NOT_IN_ORGANISATION`, `404 ORGANISATION_NOT_FOUND`. A card naming a different organisation is **accepted** — that is the normal case for an externally-issued card (§2.8.3). The signed-card errors `503 CARD_IMPORT_UNAVAILABLE`, `422 CARD_ISSUER_UNKNOWN` and `422 CARD_SIGNATURE_INVALID` belong to the unimplemented issuer/signature steps and are never returned; see the note in §2.8.3.

## 6. Error catalog (ProblemDetail `title` values)

| Title | Status | Meaning / frontend action |
|---|---|---|
| `USER_NOT_IN_ORGANISATION` | 403 | Caller's token lacks the org — usually a stale session; re-login or org switch. |
| `KEY_NOT_FOUND` | 404 | Key id unknown or not owned by caller. |
| `RECORD_NOT_FOUND` | 404 | No wrapped record for this credential → offer enrollment under it (§2.3). |
| `DOCUMENT_NOT_FOUND` | 404 | Unknown id OR the caller is not a member of the document's organisation (indistinguishable by design). A non-participant **org member** does NOT get this — they get the detail with `envelopeAccessible: false` (5.9). |
| `ORGANISATION_NOT_FOUND` | 404 | Org id does not exist. |
| `DUPLICATE_PUBLIC_KEY` | 409 | This public key is already registered for the account. |
| `ALREADY_PUBLISHED` | 409 | Publish pressed twice — refresh state. |
| `DOCUMENT_PUBLISHED_IMMUTABLE` | 409 | Delete attempted on a published document — hide/disable the action. |
| `RECIPIENT_KEY_MISSING` | 422 | Named accounts have no usable key in this org — prompt them to enroll, or add them by importing a key card (5.13). |
| `SENDER_KEY_MISSING` | 422 | Caller has no key in this org — run enrollment first. |
| `SENDER_KEY_INVALID` | 422 | A `senderKeyIds` entry is not one of the caller's keys in this org — refresh `/keys/me`. |
| `SLOT_KEY_INVALID` | 422 | A slot references a key that is unknown, not in this org, **or whose issuer has been de-trusted since you resolved** (§2.8.5) — re-resolve and re-encrypt. |
| `UNSUPPORTED_ENVELOPE_VERSION` | 422 | Client is newer than the server — hard version mismatch. |
| `TOO_MANY_SLOTS` | 422 | > 64 recipients — split the share. |
| `INVALID_PAYLOAD` | 400 | Ciphertext not valid base64 / empty. |
| `PAYLOAD_TOO_LARGE` | 413 | Ciphertext > 10 MiB (default) or record > 8 KiB. |
| `NOT_DOCUMENT_CREATOR` | 403 | Delete by non-creator without admin role. |
| `DOCUMENT_PUBLISHING_UNAVAILABLE` | 503 | No IPFS configured in this deployment — disable publishing UI. |
| `CARD_IMPORT_UNAVAILABLE` | 503 | No card issuers configured — hide "add recipient by card" entirely. |
| `CARD_ISSUER_UNKNOWN` | 422 | The card's issuer is not in this deployment's allowlist — it proves nothing here. |
| `CARD_SIGNATURE_INVALID` | 422 | Signature does not verify over §2.8.3's signing input — corrupt or forged card. |
| ~~`CARD_ORG_MISMATCH`~~ | — | **Removed** (§2.8.3). A card names its holder's organisation, not the importing one; the two are never compared. |
| `CARD_CONTAINS_PRIVATE_KEY` | 400 | The `privateKey` section must be stripped client-side; the backend never stores one (I5). |
| `UNSUPPORTED_CARD_VERSION` | 400 | Card `v` newer than the server understands. |

## 7. Platform support matrix (from the blueprint; re-verify at frontend implementation time)

PRF support depends on **where the passkey is stored**, not just the browser: Apple Passwords via Safari (macOS 15+/iOS 18+) ✅ · Apple Passwords via Chrome ≥132 on macOS 15+ ✅ (only when saved to iCloud Keychain) · Chrome/Google Password Manager desktop profile store ❌ (`prf.enabled:false`) · Brave (macOS) ❌ · Windows Hello: recent builds only — always capability-check · security keys (hmac-secret) ✅. The frontend MUST capability-check on every enrollment path (`PublicKeyCredential.getClientCapabilities()` + post-`create()` `prf.enabled`), show store-specific guidance on failure, and never silently downgrade (I4).

## 8. Frontend build checklist (acceptance gates)

1. **Enrollment**: exactly two biometric prompts (new passkey) / one (existing); ends with `POST /keys` + `PUT /records/{credentialId}`; no plaintext private key ever persisted or transmitted (payload-capture test proves it).
2. **Second device**: discoverable assertion → `GET /records/{credentialId}` → same KEK re-derives; one prompt total.
3. **Share**: addressbook → (optionally import a card for a new recipient) → choose the sender key → resolve → encrypt → upload; KAT: encrypt→decrypt round-trip for N recipients passes against the §2.1 constants. `PORTABLE` recipients are flagged before encryption (I2).
4. **Documents list view**: one screen per organisation backed by `GET /organisations/{orgId}/documents` — a paged table/list (or a richer layout, product's choice) with columns/facets: file name, description, creator (`createdByName`), created date, size, `status` badge (DRAFT/PUBLISHED), dispatch progress (`ledgerDispatchStatus`, with `txHash`/`ipfsCid` rendered as explorer/IPFS-gateway links once present, and `ledgerDispatchError` surfaced on FAILED). Controls: free-text search (`q`), filters for `direction` (All / Sent by me / Shared with me) and `status`, column sorting (`createdAt`, `fileName`, `sizeBytes`, `status`), and pagination bound to `page`/`size`/`total`/`totalPages`. Row actions: open (5.9; only meaningful for own/received docs — handle the 404), publish (5.11, DRAFT only, with irreversibility warning), delete (5.12, DRAFT only).
5. **Open**: list → fetch → slot match via `/keys/me` → key chooser → one prompt → trial decrypt (I6) → CC6 hash verdict shown; zero WebAuthn prompts on page load (I3). A non-recipient sees `envelopeAccessible: false` and no decrypt button (never a broken one).
6. **Publish**: button visible **only** to managers and admins; irreversibility warning; 503 state handled; dispatch progress surfaced from `status`/`ledgerDispatchStatus`/`txHash`/`ipfsCid`; published rows link to the transaction, the IPFS document and the Indexer (§9.5).
7. **Key cards**: import (5.13) adds a recipient to the addressbook and it survives a reload; the `privateKey` section is stripped before the request leaves the browser (payload-capture test proves it); a handover card can be imported as the user's own key, is wrapped into their record v2, and its `PORTABLE` tier is shown wherever the key appears (I2).
8. **Failure UX**: distinct states for wrong key, no PRF on this store (§7 guidance), record missing (→ §2.3 enroll-under-credential), not a recipient (`envelopeAccessible: false`), unknown card issuer, invalid card signature.
9. **Client integrity** (I9): CSP + SRI on the crypto core bundle.

---

## 9. The Indexer — the verifying side

Reeve's backend cannot read the documents it stores. That is a strong claim, and a claim nobody should have to take on faith. The **Indexer** is what makes it checkable: an independent service that reconstructs the whole picture from **Cardano L1 + IPFS alone**, never touching Reeve's database. If Reeve vanished tomorrow, every published document would still be there, still verifiable, still decryptable by whoever holds a key. That independence is the entire product argument — a verifier wired into the system it verifies proves nothing.

### 9.1 Where it lives

It lives in its own repository: **`cardano-foundation/cf-reeve-indexer`** — "a standalone indexing application for Reeve Metadata". It is a **separate deployable** from the Reeve monolith, and stays that way. It already does most of the hard part, and it already carries both halves it needs:

- **Backend** (Spring, `org.cardanofoundation.reeve.indexer`): follows the chain with yaci-store, is already configured for metadata **label 1447** and an **`ipfs.gateway`** (default `https://ipfs.io/ipfs/`), and already deserialises Reeve's on-chain metadata into an organisation/transaction/report domain model with a read API (`OrganisationController`, `TransactionController`, `ReportController`).
- **Frontend** (`frontend/`, Vite + TypeScript, same repo): the viewer that the decrypt-in-the-browser flow (§9.4) plugs into.

So the work is *additive*, not greenfield: teach the existing pipeline the new `type: DOCUMENT` manifest, add the document read API (§9.6) and the card-issuance endpoints, and build the document + verification + decrypt views in the existing frontend. Read the repo before assuming any of this — it is the source of truth, not this paragraph.

(Note: `_backend-services/cf-reeve-ledger-follower-app` inside the Reeve monolith is a *different* app and is **not** the Indexer. Do not build the Indexer there.)

### 9.2 What it indexes

Every label-1447 transaction of type `DOCUMENT` yields one index row: `documentId`, `organisationId`, `ipfsCid`, `contentHash`, `plaintextHash`, `envelopeVersion`, `slotCount`, `txHash`, absolute slot, timestamp.

**Only published documents exist here.** Drafts live in Reeve's database and are invisible to the Indexer by construction — there is nothing on-chain to see. This is a feature (drafts are private), not a gap.

**The list is hash-identified, not name-identified.** File names, descriptions, recipient names and e-mails are PII and never leave Reeve (I10), so the Indexer's list shows ids, hashes, sizes, dates and verdicts — not "q3-report.pdf". Once a viewer decrypts a document with a key they hold, the Indexer shows them the real thing. (A future envelope v2 could carry the metadata *inside* the encrypted payload, making it visible to recipients only; that is a deliberate non-goal for v1.)

### 9.3 What it verifies

| Check | How | Verdict on failure |
|---|---|---|
| Anchor exists | the tx is on-chain and its manifest parses | `MALFORMED_MANIFEST` |
| Publisher is known | the anchoring tx was signed by a publisher address in the Indexer's configured allowlist | `PUBLISHER_UNKNOWN` |
| IPFS resolves | the CID fetches through the gateway | `IPFS_UNAVAILABLE` |
| Chain ↔ IPFS integrity | `SHA-256(base64-decode(payload.ciphertext))` equals the on-chain `content_hash` | `CONTENT_HASH_MISMATCH` |
| Envelope is well-formed | parses at its declared `envelopeVersion`; `slots.length` equals the on-chain `slot_count` | `MALFORMED_ENVELOPE` |

All five pass → `VERIFIED`. This is the strongest statement possible **without a key**: *the bytes on IPFS are exactly the bytes this organisation anchored on Cardano, at this slot, and nobody has swapped them since.*

Two honest limits, stated because a verifier that overstates its own reach is worse than none:
- **`plaintext_hash` cannot be checked without decrypting.** Only a key holder completes that link (§9.4) — and they should, because it is the one check that ties the ciphertext to a real file.
- **"Publisher is known" means the deployment's wallet, not the organisation's.** Reeve signs every L1 transaction, for every org, with a single platform-wide `organiserAccount`. So the Indexer can confirm "this was anchored by the Reeve deployment", and cannot confirm "this org authorised it" — no per-org publishing key exists to check against. Anyone can post label-1447 metadata claiming any org id; such a transaction indexes as `PUBLISHER_UNKNOWN` and must be rendered as a **warning, not a document**. Per-org publisher keys are the natural fix and are out of scope here.

### 9.4 What it does with keys

- **Decrypt with a provided key — in the browser, never on the server.** The user supplies a key card (or a raw private key); the Indexer's frontend fetches the envelope, runs the same crypto core as Reeve (§2.6: trial-decrypt the slots, recover the DEK, decrypt the payload, recompute `SHA-256(plaintext)` and compare it with the on-chain `plaintext_hash`), and shows the complete end-to-end verdict. **The private key never reaches the Indexer's backend** — I1 and I5 bind the Indexer exactly as they bind Reeve. No Reeve login is required: an external auditor with a card and a CID can verify a document with no account at all.
- **Issue keys and cards.** An operator generates an X25519 keypair **in the browser** (WebCrypto), enters the holder's identity, and the *public* part is sent to the Indexer backend to be signed with the issuer's Ed25519 key (`POST /cards/issue`). The card is then assembled client-side — signed public part, plus, for a handover card, the private key wrapped under a passphrase (§2.8.2) — and downloaded. The backend never sees the private key; the issuer key never reaches the browser.
- **Re-issue for existing keys.** The Indexer keeps a registry of the cards it has issued (**public parts only**). Any of them can be re-exported as a fresh contact card — which is how a recipient gets added to a second organisation's addressbook, or replaces a card they lost.
- **Getting the subject right matters, and it is the one manual step.** A card for a Reeve user must carry `subjectType: REEVE_ACCOUNT` and their Keycloak `sub` as `subjectId` — that is what makes the key land in *their* `GET /keys/me` and lets them decrypt inside Reeve. The operator reads that `sub` from Keycloak (it is not something the Indexer can guess). Get it wrong and the key becomes an addressbook contact nobody owns: documents can be encrypted to it, and the intended holder cannot open them in Reeve. For anyone without a Reeve login — an external auditor, a regulator — use `EXTERNAL` and let the Indexer mint the `subjectId`; they read published documents in the Indexer's frontend, which is what it is for.
- **Issuance is privileged; verification is public.** The read endpoints are open to anyone (a verifier you must log into is not a verifier). `POST /cards/issue` is authenticated — it is a trust root: whoever holds the issuer key can vouch for any public key, and every Reeve deployment that lists that issuer will believe it.

### 9.5 Configuration and links

| Setting | Where | Notes |
|---|---|---|
| `lob.document_vault.card.issuers` | Reeve backend | Allowlist of trusted Ed25519 issuers as a comma-separated list of `issuerId:publicKeyHex` pairs, e.g. `reeve-indexer-prod:3b6a…64-hex,partner-indexer:9f1c…`. A malformed entry fails startup — a deployment that believes it trusts an issuer but does not is worse than one that refuses to boot. **Empty (the default) ⇒ card import is disabled** (`503 CARD_IMPORT_UNAVAILABLE`): a deployment with no Indexer simply has no cards, and nothing else changes. |
| issuer signing key | Indexer backend | Ed25519 private key, server-side only. The single most sensitive secret in the system. |
| publisher address allowlist | Indexer backend | The deployment's `organiserAccount` base address(es) — see the second limit in §9.3. |
| `ipfs.gateway` | Indexer backend | Already exists (`https://ipfs.io/ipfs/`). |
| explorer tx URL base, IPFS gateway URL base, Indexer document URL base | **Reeve frontend** | The Reeve API returns `txHash` and `ipfsCid` as raw values and no URLs (none exist in the backend today, by design — they are deployment concerns). The frontend composes all three links for published rows (§0 step 6). |

### 9.6 API sketch

Read (public): `GET /api/v1/documents?orgId=&page=&size=&sort=` (paged index with verdicts) · `GET /api/v1/documents/{documentId}` (manifest + verdict detail) · `GET /api/v1/documents/{documentId}/envelope` (the IPFS envelope, proxied — it spares the browser a CORS fight with public gateways).
Issuance (authenticated): `POST /api/v1/cards/issue` (sign a card's public part) · `GET /api/v1/cards` (registry of issued cards, public parts only).

The Indexer's own implementation plan is a **separate document** — it is a separate deployable with its own frontend, and folding it into the vault module's plan would help nobody. This section is its normative design; the vault-side contract it depends on (the card format §2.8 and the import endpoint 5.13) is frozen here.
