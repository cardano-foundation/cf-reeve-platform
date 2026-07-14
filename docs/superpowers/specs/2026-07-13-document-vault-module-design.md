# `document_vault` — new backend module for creating, uploading & publishing E2E-encrypted documents

Date: 2026-07-13 · Revised: 2026-07-14 (publishing scope; revocation removed; addressbook e-mail; single-org keys + paged org-wide listing; **key cards, the Indexer, publish role gate, org-visible document detail**)
Module: new Gradle subproject `document_vault`
Source blueprint: passkey-gated E2EE implementation blueprint (gist `b1ea4595b968525ca2cef8484899019f`, "doc-impl.md" v2)
Normative API + crypto contract (frontend/backend parallel build): `docs/documentVault.md` (blueprint v4, Reeve-adapted) — **the user journey is §0 there, the Indexer is §9, the key-card format is §2.8.**

## Goal

Implement the **backend "creating" half** of the passkey-gated end-to-end-encryption blueprint (§3B: work packages B1–B5) as a new Reeve platform library module, plus **publishing**: anchoring encrypted documents on IPFS and referencing them on Cardano L1 via the platform's existing `blockchain_publisher` pipeline.

1. **B1 — Key directory & recipient resolution (addressbook)**: registry of X25519 public keys bound to authenticated accounts, each with a notification **e-mail address** and belonging to **exactly one organisation** (product decision), server-side resolve → validate → dedupe → auto-include-sender.
2. **B2 — Wrapped-record store**: PUT/GET of opaque wrapped-key records keyed by `(accountId, credentialId)` for multi-device sync.
3. **B3 — Envelope upload, storage, indexing**: accept `{ciphertext, slots}`, assign IDs, content-address, persist, index for listing.
4. **B4 — Listing, envelope fetch + notification event**: sent/received metadata listing; authorized **envelope fetch** (`GET /documents/{id}` returns ciphertext + slots so the frontend can decrypt — all crypto stays client-side, blueprint D2); publish a metadata-minimized internal event on share.
5. **B5 — The negative requirement**: no endpoint accepts or returns plaintext, DEKs, or unwrapped/private keys — enforced by an architectural CI test. Extended here: **e-mail addresses never reach IPFS or L1** (enforced the same way), and **no card carrying a private key is ever accepted** (400).
6. **PUB — Publishing**: on explicit request, the encrypted envelope is uploaded to IPFS (hard requirement: **no IPFS configured → no publishing**) and a manifest referencing the CID is anchored on Cardano L1 under metadata label 1447 as a new `DOCUMENT` type. Published documents are locked: no edit, no delete. **Role-gated to manager/admin** (platform convention for on-chain dispatch).
7. **B6 — Key cards (new)**: import an Ed25519-signed key card (`POST /cards/import`) to (a) add a **new recipient** to an org addressbook — the only safe way to obtain a public key you did not generate — or (b) adopt an Indexer-issued key as the caller's own. Cards are minted by the Indexer; the issuer signature is the trust anchor; the private-key section must be stripped before import. Format and signing input: contract §2.8.

**The Indexer** (contract §9) is the verifying side, and it is a **separate deployable with its own implementation plan** — an extension of `_backend-services/cf-reeve-ledger-follower-app` plus its own frontend. It is specified here only where it touches this module: the card format (§2.8) and the import endpoint. Its independence from Reeve's database is the point of it, so it is deliberately not a module of this monolith.

**Explicitly out of scope of THIS plan** (the vault module):
- The Indexer's own implementation (chain indexing of `type: DOCUMENT`, IPFS verification, its read API, card issuance, its frontend) — separate spec/plan; design in contract §9.
- All frontend work (§3A/§3C — the client crypto core lives in the separate frontend repo), including the wrapped-record v2 format (a pure client-side change: the server stores the record as an opaque string and never parses it).
- Capability extensions (§9 of the blueprint): rotation epochs, org recovery, per-purpose salts, PQ hybrid slots.
- KERI attestation for document transactions (today a report-only, optional feature; can be added to the document creator later).
- Per-org/legal-hold retention policies (a global configurable retention window for **unpublished** documents + manual delete ARE in scope, see Locked decisions).
- Key revocation — removed by product decision (see Locked decisions).
- Per-org publishing keys — Reeve signs every L1 transaction for every org with one platform-wide `organiserAccount`, which bounds what the Indexer can honestly claim about authenticity (contract §9.3). Fixing that is a platform-wide change, not a vault change.

The module is deliberately shaped so the verifying half can be added later without schema changes (envelope + slots already persisted with versioned wire format; published envelopes additionally carry CID + L1 tx hash for public verification).

## Verified platform facts this design rests on

All verified directly in the repo on 2026-07-13/14:

- Modules are **library subprojects** (no runnable app in-repo); each exposes one wiring class in `org.cardanofoundation.lob.app.config` gated by `lob.<module>.enabled` (e.g. `organisation/src/main/java/org/cardanofoundation/lob/app/config/OrganisationModuleConfig.java`). Consumers (cf-reeve-application) opt in per property.
- Newest module template is `funding`: packages `resource/`, `service/`, `repository/`, `domain/{entity,enums,events,request,view}`, `util/`, plus `config/`; deps `:support`, `:organisation`, `:blockchain_common`, `spring-boot-starter-security`, jMolecules.
- Auth is Keycloak JWT (OAuth2 resource server, `support/.../spring_web/SecurityConfig.java`); **no local user table exists**. Org membership = JWT claim `organisations` (list), checked via `KeycloakSecurityHelper.canUserAccessOrg(orgId)`; display name = claim `name`. The stable OIDC subject (`sub`) is not read anywhere yet.
- `OrganisationCheckInterceptor` validates `organisationId` **only when present in a JSON request body** (`BaseRequest`); GETs and path-param endpoints must call `canUserAccessOrg` explicitly.
- Method security via `@PreAuthorize("hasRole(@securityConfig.getManagerRole()) or ...")`; roles manager/admin/accountant/auditor from `keycloak.roles.*` (`support/.../spring_web/SecurityConfig.java`, bean name `securityConfig`; the filter chain itself is `.anyRequest().permitAll()` — ALL authorization is method-level).
- **Publishing is consistently gated more narrowly than reading** (verified 2026-07-14): `AccountingCoreResource.approveTransactionsPublish` (:289) and `ReportingController.publish` (:337) are **manager-only**; funding's `SpendingEventController.publishEvent` (:406, "Publish an event to the blockchain") is **manager or admin**, while its create/update are manager/admin/accountant and its reads add auditor. Auditor is never permitted on a publish action. The vault's publish follows funding (its closest analogue: an org-scoped entity dispatched to L1) → **manager or admin**.
- `GET /api/v1/organisations` (`OrganisationResource:61`) has **no `@PreAuthorize` and no org filtering** — it returns every organisation. An org switcher must filter client-side against the `organisations` claim. (Noted for the frontend contract; not this module's to fix.)
- **Config style: `@Value` everywhere, no `@ConfigurationProperties` anywhere in the repo** (verified 2026-07-14 across all modules). This matters for the card-issuer list: property names here contain underscores (`lob.document_vault.*`, `lob.blockchain_publisher.*`), and `@ConfigurationProperties` prefixes may not contain underscores (canonical names are lowercase alphanumeric + `-`), so a `@ConfigurationProperties` binding would force a lone hyphenated outlier into an otherwise consistent naming scheme. The issuer allowlist is therefore a single `@Value` string (comma-separated `id:hex`), parsed and validated at construction.
- **BouncyCastle is already a platform-wide dependency** — root `build.gradle.kts:136` adds `org.bouncycastle:bcprov-jdk18on:1.78.1` inside `subprojects { dependencies { … } }`, so `document_vault` inherits it with no new declaration. It is used today only for MD5 hashing (`support/.../crypto/MD5Hashing.java`). **No Ed25519/EdDSA/`Signature.getInstance` code exists anywhere in the repo** — card verification is greenfield, and BC's low-level `Ed25519Signer` takes raw 32-byte keys directly (no X.509 encoding dance, unlike the JDK's native EdDSA provider).
- **The chain-reading side already exists, outside the monolith**: `_backend-services/cf-reeve-ledger-follower-app` is a standalone Gradle project (not in root `settings.gradle.kts`) that follows the chain with yaci-store, listens to `TxMetadataEvent`, filters label 1447 (`LOBOnChainBatchProcessor`), CBOR-deserialises the envelope and **already fetches IPFS content through a configured gateway** (`MetadataDeserialiser`, `ipfs.gateway`, default `https://ipfs.io/ipfs/`). It understands only `type: INDIVIDUAL_TRANSACTIONS` today. The in-monolith `blockchain_reader` module is just a `RestClient` against this service. **This is the Indexer's foundation** — the verifying side is an extension of it, not a greenfield build.
- **One publishing wallet for the whole deployment**: `blockchain_publisher/.../config/CardanoClientLibConfig.java:21` builds a single `Account ownerAccount` from `lobOwnerMnemonics`, and `AbstractL1TransactionCreator` pays and signs `.from(organiserAccount.baseAddress())`. There is **no per-organisation publishing key** — which is exactly why the Indexer can verify "the Reeve deployment anchored this" but not "this organisation authorised it" (contract §9.3).
- DB is PostgreSQL; JPA + Lombok; `CommonEntity` base (created/updated audit); Flyway migrations per module under `src/main/resources/db/migration/postgresql/common/`, globally-ordered names `V<release>_<seq>__desc.sql`; highest existing vault-side seq is funding's `V1.6_100_12`; `blockchain_publisher` uses its own `200_x` series (highest `V1.6_200_8_5`).
- No blob/object storage exists (no S3/MinIO); ciphertext goes in a `bytea` column with a size cap. IPFS enters only at publish time.
- `notification_gateway` is an empty stub ("coming soon"); cross-module integration uses plain Spring events (`ApplicationEventPublisher` → `@EventListener @Async`, e.g. funding → blockchain_publisher's `BlockchainPublisherEventHandler`).
- **Publishing pipeline** (`blockchain_publisher`): source modules fire a command event; `BlockchainPublisherEventHandler` stores a publisher-side entity; the generic `CardanoPublishingJob` iterates `CardanoPublishable<?>` beans and dispatches via per-type `L1TransactionCreator` + `MetadataSerialiser` (label from `lob.l1.transaction.metadata_label:1447`); `CardanoWatchDogJob` tracks finality (`BlockchainPublishStatus`: STORED…FINALIZED/ERROR); status returns to source modules via `LedgerUpdatedEventPublisher` → `LedgerUpdatedEvent` (`blockchain_common`, fields `organisationId`, `type: LedgerUpdateType {TRANSACTION, REPORT, SPENDING_EVENT}`, `statusUpdates: Set<LedgerStatusUpdate {id, status: LedgerDispatchStatus, errorReason, blockchainReceipts[{type, hash}]}>`), consumed with `@EventListener @Async` (e.g. `funding/.../SpendingEventLedgerUpdateHandler`). Adding a publishable type needs: entity+migration, repository(+locking gateway), converter, creator, serialiser, `CardanoPublishable` bean (jobs auto-discover), handler method, config `@Bean` — the spending-event type is the complete recent example.
- **IPFS**: `IpfsPublisher` interface (`blockchain_publisher/.../service/ipfs/IpfsPublisher.java`): `Either<ProblemDetail, String> publish(String content)` → CID. Two impls, both `matchIfMissing=false`: `BlockfrostPublisher` (`lob.blockchain_publisher.ipfs.blockfrost.enabled/.url/.project_id`) and `IpfsNodePublisher` (`lob.blockchain_publisher.ipfs.local.enabled/.node`). Injected everywhere as `Optional<IpfsPublisher>` — absent = IPFS off. The impls are independently property-gated (NOT mutually exclusive in code): enabling both would already break every existing `Optional<IpfsPublisher>` injection point, so "at most one enabled" is an existing deployment invariant this design inherits (documented, not newly introduced). Today only `AbstractL1TransactionCreator` uses it (data-array offload; CID lands in metadata under key `"ipfs"`); reports (`API3L1TransactionCreator`) never use IPFS.
- **On-chain format** (`docs/onChainFormat.md`): label 1447, base structure `{org{id,name,currency_id,country_code,tax_id_number}, metadata{creation_slot,timestamp,version}, type, data}`; types INDIVIDUAL_TRANSACTIONS, REPORT, FUNDING. FUNDING already defines an **IPFS-anchored manifest mode**: `data = {id, ipfs_cid, interval, date, event_count}` with programmatic validators (CID matches document bytes, org_id matches on-chain org.id, event_count matches). This is the direct precedent for the DOCUMENT type.
- KERI attestation is optional, config-gated, and used only by the report creator (also has a known hyphen/underscore property-name split — irrelevant to v1 documents since we don't use KERI).
- No ArchUnit anywhere yet; Testcontainers Postgres integration-test pattern exists (`reporting/.../config/TestContainerConfig.java`); full-HTTP test precedent exists (`accounting_reporting_core/.../functionalTests/WebBaseIntegrationTest.java`).
- No WebAuthn/passkey or AES/X25519 code exists anywhere in the repo — this module is greenfield for that domain.

**Assumption flagged (not verifiable in this repo):** Keycloak access tokens carry the standard OIDC `sub` claim (they always do per spec; the realm config lives in the deployment repo). The design binds keys to `sub`. **Superseded:** the earlier "no external-recipient story in v1" no longer holds — key cards introduce holders who have no Reeve login (`external = true`, `account_id` = the card's Indexer-minted `subjectId`). They are addressable recipients and read published documents in the Indexer's frontend; they never authenticate to Reeve, so no Reeve endpoint ever serves them.

## Approaches considered

1. **One new module `document_vault` (chosen)** — key directory + record store + envelope store as one bounded context. Publisher-side pieces live in `blockchain_publisher` (where every other publishable's do).
2. Extend `reporting`/`accounting_reporting_core` — rejected: directive demands a new module; the capability is content-agnostic.
3. Two modules (key directory separate from document store) — rejected for v1 (YAGNI); internal packages preserve the boundary.

Ciphertext storage: Postgres `bytea` (chosen) — IPFS is used **only for explicitly published** documents (public network; publishing is the user's deliberate act of making the encrypted envelope public). Unpublished drafts never leave Postgres.

Publishing mechanics:
- **Document-specific L1 creator (chosen)** vs. reusing `AbstractL1TransactionCreator`'s IPFS offload: the abstract creator treats IPFS as an *optional optimization* (absent publisher → inline data on-chain). Documents need the opposite semantics — IPFS is *mandatory* (no IPFS → publish must fail, never inline a multi-MB envelope into tx metadata) and the on-chain `data` is a deliberate manifest, not an offloaded array. So the document creator performs the IPFS upload itself and fails dispatch with ERROR when no `IpfsPublisher` bean exists.
- **Direct event fire on publish request (chosen, reports style)** vs. a collector job (funding style): POST publish → command event immediately; no periodic vault-side job needed.
- **Status/CID flow-back**: reuse `LedgerUpdatedEvent` unchanged — `BlockchainReceipt{type, hash}` is generic, so the publisher sends two receipts per published document: `{type:"CARDANO_L1", hash:<txHash>}` and `{type:"IPFS", hash:<cid>}`. Only additive change in `blockchain_common`: new `LedgerUpdateType.DOCUMENT` enum value.

## Locked decisions

- **Account identity**: `accountId` = JWT `sub`. New helper `KeycloakSecurityHelper.getCurrentUserId()` (additive change in `support`), fallback `"system"` when `keycloak.enabled=false` (mirrors `getCurrentUser()`). Additionally, `canUserAccessOrg` gets a null-guard: today it NPEs (→ 500) when a token lacks the `organisations` claim; the vault relies on it heavily, so absent claim must mean `false`.
- **`credentialId` bounds**: path-variable `credentialId` is validated (`@Size(max = 512)`, matching the column) so malformed input yields a controlled 400, not a DB error.
- **No key revocation (product decision, deviates from blueprint B1)**: this backend model has no revoke mechanism — no status column, no revoke endpoint, no bindings-update endpoint. Key entries are permanent and immutable; a compromised or retired key is handled by registering a replacement entry. Recorded explicitly because the blueprint mentions revocation stopping future encryption; the product owner has decided the platform does not need it here.
- **Addressbook e-mail**: key registration requires an `email` (`@NotBlank @Email`), stored on the key row and shown in the org recipient directory (it is an addressbook; org members may see each other's contact address). Purpose: notifying recipients. **E-mail addresses NEVER reach IPFS or Cardano L1** — they are absent by construction from `DocumentPublishCommand`, the IPFS envelope document, and the L1 metadata, and this is enforced by an ArchUnit rule (no `(?i).*e?mail.*` field on the publish command or any publisher-side document class) plus an e-mail canary in the publish integration test. They also stay out of `DocumentSharedEvent` (events are logged; consumers resolve e-mails from the directory at notification time).
- **One key ↔ one organisation (product decision)**: a key entry is registered for exactly ONE `organisationId` (validated against the caller's `organisations` claim and org existence via `OrganisationPublicApiIF`). The recipient directory for org X returns only keys registered in X, and only to members of X. A user active in several orgs registers one key entry per org — the SAME public key may be reused across orgs (uniqueness is `(account_id, organisation_id, public_key)`), so no extra passkey ceremony or wrapped record is needed. There is no bindings-update endpoint (a key's org is immutable); stale entries after a user leaves an org are a documented v1 limitation (membership lives only in JWTs, and revocation was removed by product decision).
- **Roles**: every endpoint requires an authenticated platform role (manager/admin/accountant/auditor combined, per platform convention); org-scoped operations additionally require org membership. Self-scoped resources (own keys, own records) are bound to `sub`, never to a request parameter. **Exception — publishing is manager-or-admin** (`@PreAuthorize("hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())")`), matching funding's `publishEvent`, the platform's closest analogue (org-scoped entity → L1). Consequence, accepted deliberately: an accountant or auditor can create and upload a document but cannot anchor it on-chain — a manager must. That is the same separation of duties the platform already enforces for report publishing and transaction dispatch, and the org-wide list means the manager can see the draft. The frontend hides the button rather than surfacing a 403.
- **Pagination everywhere (product decision)**: every list endpoint is paged, sortable and filterable via Spring `Pageable` (`page`, `size`, `sort=field,asc|desc` request params; `@PageableDefault(size = Integer.MAX_VALUE)` like funding on the small self-scoped lists, but **default `size = 20`** on the org-wide documents list — that is the potentially large one the pagination requirement is about) and returns the platform's `PagedResponse<T>` shape `{content, total, totalPages, page, size}` (module-local copy of funding's, without the ErrorAware mix-in — the vault folds `Either` instead). Applies to: keys/me, recipients, records, documents.
- **Org-wide document listing (product decision)**: `GET /organisations/{orgId}/documents` returns ALL documents of the organisation to any org member — metadata is org-visible by design. Optional filters: `direction=SENT|RECEIVED` (relative to the caller), `status=DRAFT|PUBLISHED`, `q` (case-insensitive substring over fileName/description). Envelope fetch (ciphertext) remains restricted to creator/recipients — metadata visibility ≠ ciphertext access.
- **Document lifecycle & published lock**: vault-level `status: DRAFT | PUBLISHED` (funding's EventStatus precedent: the domain status locks at publish *request*, the chain progress is tracked separately). Documents are write-once (no update endpoint at any status). `DELETE` requires org membership AND (creator or admin role) AND `status = DRAFT` — published documents can never be edited or deleted. Chain progress lives in `ledger_dispatch_status` (`LedgerDispatchStatus` from `blockchain_common`: NOT_DISPATCHED → MARK_DISPATCH → DISPATCHED → COMPLETED → FINALIZED / FAILED) plus `tx_hash`, `ipfs_cid`, `error_reason`. A FAILED dispatch keeps the document locked (it was deliberately made public-bound; the publisher retries via its own machinery, and the error is visible in the listing).
- **Publish gating — "no IPFS, no publishing"**: new tiny interface `IpfsAvailability { boolean isAvailable(); }` in `blockchain_common` (which already hosts cross-module contracts like `LedgerUpdatedEvent`); `blockchain_publisher` provides the implementing bean (`isAvailable()` = an `IpfsPublisher` bean exists). The vault injects it as `ObjectProvider<IpfsAvailability>`: bean absent (publisher module off) or `isAvailable() == false` → `POST /publish` returns 503 `DOCUMENT_PUBLISHING_UNAVAILABLE`. Authoritative second check: the document L1 creator fails dispatch with ERROR if `Optional<IpfsPublisher>` is empty (race/misconfig defense).
- **Server-assigned IDs**: `documentId` and `keyId` are server-generated UUIDs; `contentHash` = SHA-256 over received ciphertext computed server-side. SHA-256 (not SHA3) to match the blueprint's WebCrypto client side.
- **Plaintext-hash commitment**: the upload carries the client-computed `plaintextHash` (SHA-256) as an opaque commitment stored with the envelope and later anchored on L1 (that anchoring is the point of publishing: an independent integrity commitment for verifying-side CC6). Low-entropy-content guessing risk documented in the class comment.
- **Document detail (blueprint D2) — org-visible metadata, participant-only envelope (SUPERSEDES the earlier uniform-404 rule)**: `GET /documents/{documentId}` returns to **any member of the document's organisation** the metadata and a derived `recipients[]` summary (`{keyId, accountId, displayName, label, assurance}` — **no key material**). The **envelope** — `payload{ciphertext(base64), nonce}` *and* `slots[{keyId, recipientRef, ephemeralPub, wrappedDek}]` — is returned **only** to the creator and recipients; otherwise both are `null` and `envelopeAccessible = false`. Unknown id or non-member → 404.
  **Why the 404 went:** the org-wide listing (added in the same revision) already discloses that the document exists, to every org member; a 404 on fetch therefore hid nothing while breaking the detail page for everyone but the participants.
  **Why the slots did NOT follow the metadata out:** an earlier draft of this decision released `slots` to all org members, reasoning that a `wrappedDek` is useless without the private key and becomes public on IPFS at publish time anyway. That reasoning fails for a **DRAFT**, which is not public and may never be — it would hand wrapped key material to people who cannot use it and have no reason to hold it (and who would keep holding it if a recipient's key later leaked). `recipients[]` answers "who can read this?" with no key material at all, which is what a detail page actually needs. Give out no more than the job requires.
  This remains the only endpoint that serves ciphertext, and only to participants (I5). The frontend decrypts: match slots by `keyId` against own keys, unwrap via passkey-derived KEK, trial-decrypt (I6).
- **Slot semantics (blueprint I6)**: `recipientRef`/`keyId` on slots are labels + indexing aids, never trust anchors. Received-listing joins slots → keys → `account_id`; decryption authority stays client-side. **Published artifacts carry no slot identifiers at all** (see IPFS document below) — trial decryption per I6 makes them unnecessary.
- **Sender self-access (blueprint §5)**: resolution auto-includes the sender's own org-bound keys. Upload requires ≥1 slot but does not hard-require a sender slot. **New — sender key selection**: `POST /recipients/resolve` accepts an optional `senderKeyIds[]` naming which of the caller's own keys in this org get a slot (the "choose a key to encrypt with" step of the user journey). Omitted or empty ⇒ all of them (the previous behaviour, and the right default). An id that is not one of the caller's keys in this org ⇒ `422 SENDER_KEY_INVALID`. The sender is always a recipient of their own document — a write-only document is not a feature, so `[]` cannot mean "no self-slot".
- **Key cards (B6, new)**: a card is an Ed25519-signed statement binding an X25519 public key to a holder within one organisation (format + exact signing input: contract §2.8). Rationale: **you cannot encrypt to someone whose public key you do not have**, and letting a user assert "this key is Bob's" is a key-substitution attack — the attacker registers their own key under Bob's name and receives everything meant for Bob. So the trust anchor is the **issuer's signature**, never the importer's word, and any org member (any role) may import a validly-signed card: the signature is the authority, so no extra role gate adds security. Verification on import, in order: version/type/`issuer.algorithm` supported — v1, `REEVE_KEY_CARD`, `Ed25519` (`400 UNSUPPORTED_CARD_VERSION`; the algorithm is checked even though it is signed, so a card can never name one algorithm while the server verifies it under another) → `privateKey` section absent (`400 CARD_CONTAINS_PRIVATE_KEY`, I5 — the backend must never hold private key material, even passphrase-wrapped; checked before the signature so an unsigned card full of key material is rejected too) → `issuer.publicKey` present in the configured allowlist **and** matching `issuer.issuerId` (`422 CARD_ISSUER_UNKNOWN`) → Ed25519 signature over the length-prefixed signing input (`422 CARD_SIGNATURE_INVALID`) → `subject.organisationId` equals the request's `organisationId` (`422 CARD_ORG_MISMATCH`) → caller is an org member (interceptor). Config `lob.document_vault.card.issuers` — a comma-separated list of `issuerId:ed25519PublicKeyHex` pairs bound with `@Value` (the repo has no `@ConfigurationProperties` anywhere, and its prefixes could not carry the underscores this platform's property names use); a malformed entry fails startup rather than silently dropping a trust anchor. **Empty (default) ⇒ import returns `503 CARD_IMPORT_UNAVAILABLE`** — a deployment with no Indexer simply has no cards, exactly as a deployment with no IPFS has no publishing. Import is **idempotent**: re-importing the same (org, publicKey, subject) refreshes `label`/`email` from the card and returns `200` with the existing entry — a user re-adding a recipient is normal, not an error. The card's subject decides ownership, not the client: `subjectType = REEVE_ACCOUNT` with `subjectId == caller's sub` ⇒ the caller's own key (shows in `/keys/me`); anything else ⇒ an addressbook entry for that holder.
- **Issuer compromise — containment without revocation (contract §2.8.5)**: an issuer key is a trust anchor, so whoever steals one can vouch for any public key, and `resolve` includes **all** of a recipient's keys — meaning one injected key would otherwise earn a slot in every future document addressed to its victim, silently. Documenting that risk is not a mitigation. Since key revocation was removed by product decision, the containment is at the **issuer** level instead, and it needs no lifecycle machinery at all: each key row stores the `issuer_id` that vouched for it, and **the addressbook (5.3) and resolve (5.4) only return an `INDEXER_ISSUED` key while its issuer is still in `lob.document_vault.card.issuers`**. Removing a compromised issuer from that config instantly makes every key it ever introduced un-addressable — one config change, no migration, no endpoint, no status column. The sender's own keys are filtered too (a `PORTABLE` key minted by a compromised issuer must be assumed known to the attacker). `GET /keys/me` is the one exception: it still returns your de-trusted keys, flagged `issuerTrusted: false`, because you need them to decrypt what you already received — they are simply not a target for anything new. Honest limit: **you cannot un-send.** Documents already encrypted to a hostile key stay readable by whoever holds it; only detection speed bounds that, which is why the Indexer keeps a registry of issued cards (§9.4) that an org can diff against its addressbook to spot cards signed outside the issuance flow.
- **Two key tiers (`assurance`), stored and always displayed — amends blueprint I2**: `PASSKEY` (generated on the owner's device, private half never leaves it) and `PORTABLE` (Indexer-issued: an operator generated it and handed it over on a card). The blueprint's I2 says "no fallback tier, passkey-only"; issuing keys for users **necessarily** breaks that, because whoever mints a key has seen it. Rather than break it silently, the tier is a stored column, returned by every key/recipient endpoint, and required to be shown in the UI wherever a key is chosen or a recipient picked. **The tier is provenance, not storage, so it never upgrades**: wrapping a portable key under a passkey afterwards is a convenience and does not un-see what the operator saw. The honest claim for a portable key is "only someone holding this key can read this", never "only the named person can read this". A holder who wants passkey assurance enrols a fresh key. `origin` (`SELF_ENROLLED | INDEXER_ISSUED`) records how the entry got in; `external` marks holders with no Reeve login.
- **Stateless resolve→upload**: no resolution token; upload re-validates every slot's `keyId` at upload time — it must exist, be bound to the org, **and** (if `INDEXER_ISSUED`) still have a trusted issuer, else `422 SLOT_KEY_INVALID`. The issuer check has to be repeated here, not only in resolve: a client that resolved before an issuer was de-trusted would otherwise still upload a slot wrapped to that issuer's key, which is exactly the amplification the containment model exists to stop. Resolution is a validation/convenience service, **not an authorization gate**: any org member may address any org member, so "skipping resolve" grants no capability that resolve wouldn't — the authorization boundary is org membership, enforced on both endpoints. The server can never verify that a `wrappedDek` is genuinely wrapped to the named key (doing so would require key material, violating I5); garbage slots are inherent to the custody model.
- **No Envers on vault tables**: envelopes are immutable and blobs would double in `_aud` tables. (Deviation from org/funding convention, deliberate. The publisher-side document table follows publisher conventions instead.)
- **Wire-format versioning (I7)**: envelope rows carry `envelope_version` (int); wrapped records carry client-declared `v` inside the opaque blob plus a `version` column. Upload validates against a `SUPPORTED_ENVELOPE_VERSIONS` set (currently `{1}`) — add-never-remove posture; unknown future versions rejected with 422. The IPFS document and L1 manifest carry the same version fields.
- **Size caps (config)**: `lob.document_vault.max-document-bytes` (default 10 MiB), `max-record-bytes` (8 KiB), `max-slots` (64) — validated before persist, 413/400 ProblemDetail on breach.
- **Notification (B4)**: publish `DocumentSharedEvent(documentId, organisationId, recipientAccountIds)` on upload and `DocumentPublishedEvent(documentId, organisationId, recipientAccountIds)` when the ledger update reports finality — both metadata-minimized (no filename, no content, no display names, **no e-mails**). `notification_gateway` is an empty stub, so these internal events plus pull-based listing ARE the v1 delivery; a future gateway resolves recipient e-mails from the key directory at send time.
- **Retention (B3)**: configurable automated retention — `lob.document_vault.retention-days` (default `0` = disabled) with a daily cleanup job deleting envelopes older than the cutoff, **DRAFT documents only** — published documents are never purged (they are anchored on public infrastructure). Manual delete remains available for DRAFT only.
- **Key supersession (B1)**: register a replacement key entry (key entries are immutable and permanent — no bindings-update, no revocation). No epoch/rotation bookkeeping in v1.
- **Search/indexing (B3)**: indexes on `organisation_id`, `created_by_account`, and slot `key_id` serve retrieval. Content search is impossible by design.
- **Module toggle**: `lob.document_vault.enabled` + `DocumentVaultModuleConfig`, same pattern as all modules.

## Publishing — flow and formats (PUB)

### Flow

1. `POST /api/v1/document-vault/documents/{documentId}/publish` — caller must be org member with a platform role; document must exist and be `DRAFT` (else 409 `ALREADY_PUBLISHED`); `IpfsAvailability` must report available (else 503 `DOCUMENT_PUBLISHING_UNAVAILABLE`).
2. Vault sets `status = PUBLISHED`, `published_at = now`, `ledger_dispatch_status = MARK_DISPATCH` and fires `DocumentPublishCommand` (fields: `organisationId`, `documentId`, `envelopeVersion`, `contentHash`, `plaintextHash`, `payloadNonce`, `ciphertextBase64`, `slots[{ephemeralPub, wrappedDek}]` — deliberately NO e-mails, NO recipientRefs, NO keyIds, NO fileName/description, NO account ids).
3. `blockchain_publisher` (new handler method) converts the command into its own `DocumentEntity` (table `blockchain_publisher_document`, publisher `200_x` migration series) and stores it for dispatch — exactly the spending-event pattern.
4. On dispatch, the new `DocumentL1TransactionCreator`: serialises the **IPFS envelope document** (below) → `IpfsPublisher.publish(json)` → CID (empty `Optional<IpfsPublisher>` → dispatch fails with ERROR "IPFS not configured"); stores the CID; builds the L1 tx whose 1447 metadata carries the **DOCUMENT manifest** (below); submits via the existing submission services; `CardanoWatchDogJob` tracks finality.
5. Status-back: the document `CardanoPublishable` sends `LedgerUpdatedEvent{type = DOCUMENT}` with receipts `{type:"CARDANO_L1", hash:txHash}` and `{type:"IPFS", hash:cid}` — via an additive overload of `LedgerUpdatedEventPublisher.send` taking a per-entity extra-receipts function (the existing method hardcodes the single L1 receipt). The vault's `DocumentLedgerUpdateHandler` (mirrors funding's) updates `ledger_dispatch_status`, `tx_hash`, `ipfs_cid`, `error_reason`, and fires `DocumentPublishedEvent` on first FINALIZED update.

### IPFS envelope document (proposed, versioned)

The IPFS content is the *encrypted envelope itself*, self-describing, mirroring the funding off-chain document's conventions (`org_id`, `version` + content):

```json
{
  "version": 1,
  "type": "REEVE_ENCRYPTED_DOCUMENT",
  "org_id": "75f95560c1d8...ca94",
  "content_hash": "<SHA-256 of the raw ciphertext bytes, hex>",
  "plaintext_hash": "<SHA-256 commitment over the plaintext, hex — client-computed>",
  "payload": {
    "ciphertext": "<base64>",
    "nonce": "<12-byte hex>"
  },
  "slots": [
    { "ephemeral_pub": "<32-byte hex>", "wrapped_dek": "<48-byte hex>" }
  ]
}
```

Deliberately absent: e-mails, recipient labels/refs, key ids, account ids, file names, descriptions, timestamps beyond what L1 already anchors. Slots carry zero identifiers — blueprint I6 decryption is trial-based, so identifiers are pure metadata leakage on a public network. Programmatic validation invariants (mirroring the FUNDING manifest validators): `org_id` matches the on-chain `org.id`; `content_hash` = SHA-256(base64-decode(`payload.ciphertext`)); the CID matches the document bytes; on-chain `slot_count` matches `slots.length`.

### L1 metadata (label 1447, new type `DOCUMENT`, proposed)

Standard 1447 envelope (org + metadata blocks exactly as REPORT/FUNDING build them), new `type`, manifest-shaped `data`:

```json
{
  "1447": {
    "org": { "id": "...", "name": "...", "currency_id": "...", "country_code": "...", "tax_id_number": "..." },
    "metadata": { "creation_slot": 12345, "timestamp": "2026-07-14T10:15:30Z", "version": "1.0" },
    "type": "DOCUMENT",
    "data": {
      "id": "<documentId (server UUID)>",
      "ipfs_cid": "<CID of the IPFS envelope document>",
      "content_hash": "<SHA-256 of ciphertext, hex>",
      "plaintext_hash": "<SHA-256 plaintext commitment, hex>",
      "envelope_version": 1,
      "slot_count": 2
    }
  }
}
```

Serialised by a new `DocumentMetadataSerialiser` (serialiser `VERSION = "1.0"`), label from `lob.l1.transaction.metadata_label:1447`, mirroring `API3MetadataSerialiser`'s org/metadata construction. The new type gets a section in `docs/onChainFormat.md`. `content_hash`/`plaintext_hash` anchor integrity; `ipfs_cid` locates the envelope; no PII anywhere.

## Data model (tables prefix `document_vault_`, plus one publisher-side table)

```
document_vault_key
  key_id            varchar PK (UUID)
  account_id        varchar NOT NULL         -- JWT sub; for an external card holder: the card's subjectId
  organisation_id   varchar NOT NULL         -- exactly ONE org per key entry (product decision)
  account_name      varchar                  -- display label snapshot (name claim, or the card's displayName)
  email             varchar(320) NOT NULL    -- notification address (addressbook); NEVER exported to IPFS/L1
  credential_id     varchar NULL             -- passkey credential this key is wrapped under
  public_key        varchar(64) NOT NULL     -- X25519, 32 bytes hex
  label             varchar NOT NULL
  origin            varchar(20) NOT NULL     -- SELF_ENROLLED | INDEXER_ISSUED
  assurance         varchar(20) NOT NULL     -- PASSKEY | PORTABLE  (provenance; never upgrades)
  external          boolean NOT NULL         -- true = holder has no Reeve login (card subjectType EXTERNAL)
  issuer_id         varchar(64) NULL         -- which card issuer vouched (NULL for SELF_ENROLLED)
  + audit columns (created/updated by/at — module-local base, not CommonEntity)
  UNIQUE (account_id, organisation_id, public_key)   -- also the idempotency key for card import
  INDEX (organisation_id)

document_vault_wrapped_record
  account_id        varchar NOT NULL
  credential_id     varchar NOT NULL
  record            text NOT NULL            -- opaque client blob, round-tripped byte-identical
  version           int NOT NULL             -- store schema version, starts 1
  + audit columns (created/updated by/at — module-local base, not CommonEntity)
  PRIMARY KEY (account_id, credential_id)

document_vault_document
  document_id       varchar PK (UUID)
  organisation_id   varchar NOT NULL
  status            varchar NOT NULL         -- DRAFT | PUBLISHED (locks edit/delete at publish request)
  envelope_version  int NOT NULL             -- wire format, starts 1
  content_hash      varchar(64) NOT NULL     -- SHA-256(ciphertext), server-computed
  plaintext_hash    varchar(64) NOT NULL     -- client commitment (CC6, opaque here)
  ciphertext        bytea NOT NULL
  payload_nonce     varchar(24) NOT NULL     -- 12 bytes hex
  file_name         varchar NULL             -- internal metadata only, never published
  content_type      varchar NULL
  description       varchar NULL
  size_bytes        bigint NOT NULL          -- ciphertext length
  created_by_account varchar NOT NULL        -- account_id (sub)
  created_by_name   varchar                  -- display snapshot
  published_at      timestamp NULL
  ledger_dispatch_status varchar NOT NULL    -- LedgerDispatchStatus, default NOT_DISPATCHED
  ledger_dispatch_error  varchar NULL
  tx_hash           varchar NULL             -- Cardano L1 tx hash (from CARDANO_L1 receipt)
  ipfs_cid          varchar NULL             -- from IPFS receipt
  + audit columns (created/updated by/at — module-local base, not CommonEntity)
  INDEX (organisation_id), INDEX (created_by_account)

document_vault_document_slot
  document_id       varchar FK -> document_vault_document
  slot_index        int NOT NULL
  key_id            varchar FK -> document_vault_key   -- indexing label, not a trust anchor
  recipient_ref     varchar NOT NULL                    -- display label from client; internal only, never published
  ephemeral_pub     varchar(64) NOT NULL                -- 32 bytes hex
  wrapped_dek       varchar(96) NOT NULL                -- AES-256-GCM(DEK): 32+16 bytes hex
  PRIMARY KEY (document_id, slot_index)

blockchain_publisher_document (publisher module, 200_x migration series, publisher conventions incl. L1SubmissionData columns)
  document_id, organisation_id, envelope_version, content_hash, plaintext_hash,
  payload_nonce, ciphertext_base64 (text — the publisher receives base64 and the IPFS serialiser emits base64;
  no bytea round-trip), slots (child table: ephemeral_pub, wrapped_dek, slot_index),
  ipfs_cid NULL, + L1SubmissionData (tx hash, slot, finality, publish status, error, retry count). NOT Envers-audited.
```

Migration files: vault `V1.6_100_13__lob_service_app_document_vault_module.sql` (re-check next free `100_x` at implementation time); publisher `V1.6_200_9__add_document_publishable.sql` (re-check next free `200_x`).

## API (base `/api/v1/document-vault`, all JSON, springdoc-annotated, ProblemDetail errors)

Keys / addressbook (B1):
- `POST /keys` — register `{organisationId, label, publicKey, email, credentialId?}` (body `extends BaseRequest` → interceptor-covered); validates org (membership + existence), hex/length of key, `@Email`, uniqueness per (account, org, publicKey). Returns key view. Same public key may be registered again for another org.
- `GET /keys/me` — own keys across orgs (paged).
- `GET /organisations/{orgId}/recipients` — addressbook for org (member-only, paged): `{accountId, displayName, email, keyId, publicKey, label, assurance, origin, external, issuerId}` entries from keys registered in the org. Keys whose `issuerId` is no longer in `lob.document_vault.card.issuers` are **omitted** — a de-trusted issuer's keys are not offerable as recipients (issuer containment, below). Because that predicate cannot live in the query, the repository read is unpaged and the filtered list is paged in memory (`PagedResponse.ofList`).
- `POST /recipients/resolve` — body `extends BaseRequest` `{organisationId, recipientAccountIds[], senderKeyIds[]?}`: resolve → validate (well-formed, org-bound) → dedupe by publicKey → add the sender's own org-bound keys (all of them, or only those named in `senderKeyIds`) → return the wrap-target set. 422 with per-recipient detail when an account has no usable key; 422 `SENDER_KEY_INVALID` when a `senderKeyIds` entry is not the caller's key in this org.
- `POST /cards/import` (B6) — body `extends BaseRequest` `{organisationId, card}`: verify the card (version → no `privateKey` → known issuer → Ed25519 signature over §2.8.3's length-prefixed input → org match), then upsert a key entry. Own key when `subjectType = REEVE_ACCOUNT` and `subjectId` = caller's `sub`; otherwise an addressbook entry for that holder (`external = true` for `subjectType = EXTERNAL`). Idempotent → `200 VaultKeyView`. `503` when no issuers are configured.

Wrapped records (B2):
- `PUT /records/{credentialId}` — upsert own record `{record, version}`; ≤ `max-record-bytes`; blob stored verbatim.
- `GET /records/{credentialId}`, `GET /records` (paged) — own records only (creating-side enrollment/keychain-load, blueprint §2.3).

Documents (B3/B4/PUB):
- `POST /documents` — body `extends BaseRequest`: `{organisationId, envelopeVersion, fileName?, contentType?, description?, plaintextHash, payload:{ciphertext(base64), nonce}, slots:[{keyId, recipientRef, ephemeralPub, wrappedDek}]}`. Validates size caps, slot refs (org-bound **and** issuer still trusted — `422 SLOT_KEY_INVALID`), ≥1 slot, hex formats; computes `contentHash`; persists as `DRAFT`; publishes `DocumentSharedEvent`; returns `{documentId, contentHash, createdAt}`.
- `POST /documents/{documentId}/publish` — **manager or admin** (`@PreAuthorize`), org member; `DRAFT` only (409 otherwise); IPFS available only (503 otherwise); locks the document (`PUBLISHED`), fires `DocumentPublishCommand`, returns the document view with dispatch status.
- `GET /organisations/{orgId}/documents` — org-wide, paged, sorted, filtered metadata listing (see the org-wide listing decision): ALL documents of the org; optional `direction=SENT|RECEIVED` (uppercase enum; `SENT`: `created_by_account` = me; `RECEIVED`: a slot references one of my keys), optional `status=DRAFT|PUBLISHED`, optional `q` substring filter; `page`/`size`/`sort` Pageable params (sortable: `createdAt`, `fileName`, `sizeBytes`, `status`). Returns `PagedResponse<DocumentView>` incl. `ledgerDispatchStatus`, `txHash`, `ipfsCid`.
- `GET /documents/{documentId}` — **document detail** (any org member; unknown id or non-member → 404): metadata + `recipients[]` summary (no key material) + `envelopeAccessible`; and **for the creator and recipients only** the envelope — `payload{ciphertext(base64), nonce}` and `slots[{keyId, recipientRef, ephemeralPub, wrappedDek}]` — for client-side decryption (both `null` otherwise). The ONLY endpoint that returns ciphertext.
- `DELETE /documents/{documentId}` — org member AND (creator or admin role) AND `DRAFT`; hard delete (cascades slots). Published documents: 409 `DOCUMENT_PUBLISHED_IMMUTABLE`.

## The negative requirement (B5) — enforcement (extended with the e-mail rule)

**Honest boundary statement (I5/I9):** the server cannot verify that bytes labeled `ciphertext` are actually encrypted — ciphertext is computationally indistinguishable from random, and any server-side "is this really encrypted?" check would require key material, itself violating I5. The blueprint therefore places the payload-capture test on the *frontend*. What the backend can and does enforce: (1) no API field exists where plaintext or key secrets could legitimately ride, (2) payload bytes are never copied outside the single `ciphertext` column (per module), (3) payload bytes never appear in server logs, (4) **PII (e-mail, recipient labels, file names, account ids) never appears in anything that leaves the operator's custody — the publish command, the IPFS document, or the L1 metadata**.

1. **API-shape rule**: no request/response DTO may carry plaintext content, DEKs, KEKs, PRF outputs, or private keys. Enforced by an **ArchUnit test** (new test-only dependency `com.tngtech.archunit:archunit-junit5`): field names of all classes under `..document_vault.domain.request|view..` must not match `(?i)(plaintext(?!Hash)|dek|kek|privateKey|prfOutput|secret)`; entities likewise.
2. **No-decrypt surface**: no endpoint accepts key material, and ciphertext leaves the API through exactly ONE view — `DocumentEnvelopeView`, served by the authorized fetch endpoint (serving ciphertext to recipients is blueprint D2 backend work; I5 bans plaintext/keys, not ciphertext). Asserted by an ArchUnit rule that no OTHER view exposes a `ciphertext` field.
3. **Publish-path PII rule**: ArchUnit rule over `DocumentPublishCommand` and the publisher-side document classes (`blockchain_publisher/.../document..` packages): no field matching `(?i)(e?mail|recipient|account|label|file_?name|description)`. The IPFS/L1 formats are generated exclusively from these classes, so the rule structurally prevents PII export.
4. **Payload-copy + in-transit scan test**: integration test uploads a canary envelope **through the real HTTP stack** (`RANDOM_PORT` + RestAssured, precedent `WebBaseIntegrationTest`), then asserts the canary appears in no `document_vault_*` column other than `ciphertext`. The publish-path test extends this: registers a key with a canary **e-mail**, publishes the document, and asserts the serialised IPFS content string and the metadata map contain neither the e-mail canary nor any recipient/file-name string.
5. **Log hygiene**: entities/DTOs exclude payload fields from `toString`; asserted via a Logback list-appender capture during the HTTP tests.
6. **No private key may enter, even wrapped (B6/I5)**: `KeyCardDto` declares **no** `privateKey` field — which alone would let Jackson silently discard one, so the DTO also carries an `@JsonAnySetter` sink (`Map<String,Object>`, not a name the ArchUnit rule bans) and the import service rejects the request with `400 CARD_CONTAINS_PRIVATE_KEY` when that sink contains a `privateKey` entry. Silently dropping it would be worse than rejecting: the user would believe the backend now holds their key. Covered by an HTTP-level test that posts a full handover card and asserts both the 400 and that no key row was written. Cards also carry PII (`displayName`, `email`), so the existing publish-path PII rules (#3, #4) already forbid any card-derived field from reaching the IPFS document or the L1 metadata — no card class is reachable from `DocumentPublishCommand`.

## Cross-module changes (all additive)

- `settings.gradle.kts`: add `:document_vault`.
- `support`: `KeycloakSecurityHelper.getCurrentUserId()` (reads `sub`) + null-guard in `canUserAccessOrg`.
- `blockchain_common`: `LedgerUpdateType.DOCUMENT` enum value; new `IpfsAvailability` interface.
- `blockchain_publisher`: `implementation(project(":document_vault"))`; document publishable (entity + `200_x` migration + repository/gateway + converter + `DocumentL1TransactionCreator` + `DocumentMetadataSerialiser` + `CardanoPublishable` bean + handler method + config `@Bean` + `IpfsAvailability` impl bean).
- `document_vault` config: `lob.document_vault.card.issuers` — comma-separated `issuerId:ed25519PublicKeyHex` allowlist (`@Value`); empty ⇒ card import disabled (503). **No new dependency**: BouncyCastle (`bcprov-jdk18on`) is already applied to every subproject from the root build file, and its `Ed25519Signer` verifies raw 32-byte keys directly.
- Root README module list: one bullet. `docs/onChainFormat.md`: new `DOCUMENT` type section. `db-tables.md`: new tables.

## Testing

- **Unit**: services with Mockito (resolution incl. dedupe/self-add/`senderKeyIds` selection + `SENDER_KEY_INVALID`; validation failures; size caps; publish preconditions incl. IPFS-unavailable and already-published; ledger-update handler mapping receipts to txHash/CID), serialiser unit tests against the proposed JSON shapes (golden fixtures), event payload minimization.
- **Card verification** (`KeyCardVerifier`, unit): a **known-answer test** with a fixture keypair — sign a card with a test Ed25519 key, verify it passes; then assert every rejection path individually, each by mutating exactly one thing: unknown issuer, signature over a tampered field (flip one character of `publicKey`, of `email`, of `subjectId` — each must fail, proving every field is really covered by the signing input), wrong org, unsupported version, present `privateKey`. The signing input is a cross-language contract (Java verifier, TypeScript issuer, TypeScript importer), so the test also pins the **exact bytes**: a golden hex fixture of the length-prefixed input for a fixed card, so any accidental reordering or encoding drift fails loudly here rather than silently in production.
- **Integration** (`@SpringBootTest` + Testcontainers Postgres + Flyway): key registration→addressbook→resolve→upload→list→fetch happy path; detail authorization (creator sees the envelope, recipient sees the envelope, other org member sees metadata + `recipients[]` with `envelopeAccessible=false` and **both `payload` and `slots` null** — the wrapped-DEK leak this closes is worth its own assertion, non-member 404, unknown id 404); org-wide listing filters (direction/status/q) + pagination; record store byte-identical round-trip (B2 gate); received-listing via slot join; delete authorization incl. published-lock; publish flow with a stubbed `IpfsPublisher` (returns fixed CID) asserting publisher-side entity, metadata map shape, status-back updates vault columns; e-mail canary never in IPFS content or metadata (B5 gate #4); payload-copy + log scans.
- **Card import** (integration, through the real HTTP stack): contact card → new addressbook entry, immediately resolvable as a recipient and **still there after a restart** (the "persisted for later use" requirement); own card → appears in `/keys/me`; re-import → `200`, no duplicate row, label/e-mail refreshed; handover card with `privateKey` → `400 CARD_CONTAINS_PRIVATE_KEY` **and no row written**; no issuers configured → `503 CARD_IMPORT_UNAVAILABLE`.
- **Issuer de-trust (the containment property)**: a key whose `issuer_id` is no longer in the configured allowlist is (a) absent from the addressbook, (b) never a wrap target in `resolve` — including when it belongs to the caller — and (c) still returned by `/keys/me` with `issuerTrusted=false` so old documents stay decryptable. A recipient left with nothing but de-trusted keys yields `422 RECIPIENT_KEY_MISSING`, never a silent drop. This is the test that proves a stolen issuer key cannot keep collecting slots.
- **Publish role gate**: asserted **declaratively**, not with a live 403. Method security is enabled by `support`'s `SecurityConfig`, which is `@ConditionalOnProperty(keycloak.enabled=true)`; the module's own tests run with Keycloak **disabled** (no `securityConfig` bean, `@PreAuthorize` inert), so a "an accountant gets 403" integration test is not achievable in this context and pretending otherwise would give false assurance. Instead a reflective test pins the annotation on `VaultDocumentController.publish` to exactly `hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())` — which is the thing that can actually regress (someone widening the expression). End-to-end role enforcement is a deployment-level concern, as it already is for every other `@PreAuthorize` in this platform.
- **ArchUnit**: B5 rules 1–3 and 6 run in the normal test task (CI).
- Controller tests mock services per platform convention.

Blueprint "Ensure it (creating, backend)" gates map to: contract tests (integration suite), storage/log scan (B5 tests), record round-trip (B2 test). The revocation-out-of-resolution gate is dropped with revocation itself (product decision). The payload-capture half of the blueprint's scan remains a frontend gate.

## The Indexer (separate deployable — design in contract §9)

The verifying side is no longer deferred; it is **out of this module**, which is a stronger position, not a weaker one. The Indexer derives everything from Cardano L1 + IPFS and never reads Reeve's database — a verifier sharing a database with the system it verifies would prove nothing. It extends `_backend-services/cf-reeve-ledger-follower-app` (which already follows the chain, filters label 1447 and fetches IPFS) with `type: DOCUMENT` parsing, a per-org document index with verification verdicts, a public read API, an authenticated card-issuance endpoint, and its own frontend where the user's key — never the server's — decrypts.

What the vault module owes it, and nothing more: the **card format** (contract §2.8), the **import endpoint** (`POST /cards/import`), and the on-chain `DOCUMENT` manifest it parses. Those are frozen here. Everything else about the Indexer — indexing, verdicts, issuance, UI — belongs to its own spec and plan, written after this module lands.

Verification verdicts it can produce without a key: anchor parses · publisher is the deployment's known wallet · CID resolves · `SHA-256(ciphertext) == on-chain content_hash` · envelope well-formed at its declared version. All five ⇒ `VERIFIED`. Two limits stated honestly in §9.3: `plaintext_hash` cannot be checked without decrypting (only a key holder closes that loop), and "known publisher" means the Reeve deployment's single `organiserAccount`, not a per-org key — none exists.

## Out of scope / follow-ups

- The Indexer's implementation (its own spec/plan) — including blueprint D1 verdict objects and the D3 slot-matching hint endpoint, which land there rather than in the vault. NOTE: envelope serving (D2) is IN scope here — see the document-detail decision.
- Per-org publishing keys (would let the Indexer verify *which organisation* authorised an anchor, not merely that the Reeve deployment did) — platform-wide change, deliberately not attempted here.
- Notification e-mails to card-imported external holders (the address is stored; the sending gateway is still a stub).
- notification_gateway consumption of `DocumentSharedEvent`/`DocumentPublishedEvent` (e-mail sending itself).
- Per-org/legal-hold retention; key rotation epochs; org recovery (§9); external recipients; KERI attestation for documents.
- Frontend client crypto core and enrollment UX (§3A), incl. PRF capability matrix (§6) — frontend repo.
