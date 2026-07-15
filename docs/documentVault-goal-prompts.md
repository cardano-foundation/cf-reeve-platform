# Document Vault — goal prompts

Three workstreams, three repositories, one frozen contract.

**The contract is `documentVault.md`** — the agreed, adversarially-reviewed frontend/backend/Indexer
contract. Two ways to reach it:

- In **this** repository (the backend prompt): [`docs/documentVault.md`](./documentVault.md).
- From **any other** repository (the frontend and Indexer prompts): the published gist —
  <https://gist.github.com/Kammerlo/48f3f37bd4933e880994f1d44831767e>
  (raw, always-latest: `https://gist.githubusercontent.com/Kammerlo/48f3f37bd4933e880994f1d44831767e/raw/documentVault.md`)

The gist is a **copy**. If you change the contract here, re-publish the gist, or the other two teams
will build against a stale document — which is exactly the failure this contract exists to prevent.

Repositories:

| Workstream | Repository |
|---|---|
| Backend (`document_vault` module) | `cardano-foundation/cf-reeve-platform` — *this* repo |
| Reeve frontend | the Reeve frontend repo |
| Indexer (backend **and** frontend) | `cardano-foundation/cf-reeve-indexer` |

Every prompt below ends with the same rule: **Codex adversarial review is the final step, and it
loops until Codex returns AGREE.** That is not decoration. Across the design of this contract, Codex
caught a live security hole (draft `slots` leaking to non-participants), an amplification path in the
key-card trust model, and three separate cases where my own verification tooling reported "clean"
while checking nothing. Assume the same will happen to you.

Two couplings to know before you start:

- **The card signing input (§2.8.3) is the hard coupling between the backend and the Indexer.** It is
  a 14-field length-prefixed Ed25519 input. If the two sides disagree by one field, every card fails
  with `422 CARD_SIGNATURE_INVALID`. The golden test vector should be **shared, not written twice**.
- **The crypto constants (§2.1) are the hard coupling between the frontend and the Indexer**, which
  both run the same client crypto core. A KAT round-trip must pass on both.

---

## 1. Backend — the `document_vault` module

Runs in **this** repository (`cf-reeve-platform`). The plan is written and reviewed; this prompt
executes it.

```
/goal Implement the document_vault backend module.

WHAT EXISTS ALREADY — read all three before writing any code:
- docs/documentVault.md — the frozen frontend/backend contract.
- docs/superpowers/specs/2026-07-13-document-vault-module-design.md — the design.
- docs/superpowers/plans/2026-07-13-document-vault-module.md — a task-by-task TDD
  plan (Tasks 1–13), already through a long Codex adversarial review loop ending in
  AGREE. Execute it; do not redesign it.

Use superpowers:subagent-driven-development (a fresh subagent per task, reviewed
between tasks) or superpowers:executing-plans. Work task by task, in order — later
tasks consume types the earlier ones produce.

NON-NEGOTIABLE (these are product decisions, already settled — do not relitigate):
- E-mail addresses and every other piece of PII NEVER reach IPFS or Cardano L1
  (invariant I10). The publish command is PII-free by construction: no e-mails, no
  key ids, no file names, no account ids.
- The backend never holds a plaintext, a DEK, or an unwrapped private key (I5). A
  key card arriving with a privateKey section is REJECTED (400
  CARD_CONTAINS_PRIVATE_KEY), never quietly stripped and stored.
- No key revocation. Containment is at the ISSUER level: drop a compromised issuer
  from lob.document_vault.card.issuers and every key it ever vouched for stops
  being addressable — in the addressbook, in resolve, AND at upload. The upload
  re-check is not redundant: a client that resolved before the de-trust would
  otherwise still upload a slot wrapped to the compromised issuer's key.
- Published documents are immutable: never edited, never deleted.
- One key ↔ one organisation.
- No IPFS configured ⇒ no publishing (503), never an inline fallback.
- Every list endpoint is paged.
- Publish is manager-or-admin (@PreAuthorize); this mirrors funding's publish, and
  the precedent was verified in the repo, not guessed.

VERIFY EVERY ASSUMPTION. The plan's claims about this codebase were checked against
real files, but you are the one compiling it — if the repo disagrees with the plan,
the repo wins, and say so rather than forcing the plan through.

BUILD: JDK 21 is mandatory. JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...
A stale or missing JAVA_HOME fails in a way that looks like a Kotlin DSL bug.

DONE MEANS:
- Every task's tests actually run and actually pass — show the output. A test you
  did not run is a test that does not pass.
- Note that @PreAuthorize is INERT in module tests (SecurityConfig is conditional on
  keycloak.enabled=true), so a "403" integration test would pass no matter what the
  annotation said. The plan pins the publish gate reflectively instead. Do not
  "improve" this into a test that proves nothing.
- The plan ships an import checker at docs/superpowers/plans/.import-sweep.py. If you
  change the plan's code blocks, re-run it; it refuses to run against stale captured
  classpaths rather than reporting a comforting zero.

FINAL STEP, MANDATORY: Codex adversarial review of the implemented diff (not the
plan) — /codex:adversarial-review. Loop until Codex returns AGREE. Fix what it
finds; if you disagree with a finding, argue it explicitly rather than ignoring it.
```

---

## 2. Reeve frontend

Runs in the **Reeve frontend repository**.

```
/goal Implement the Document Vault UI in the Reeve frontend.

CONTRACT (frozen — fetch and read it first, in full):
https://gist.githubusercontent.com/Kammerlo/48f3f37bd4933e880994f1d44831767e/raw/documentVault.md
It is the frontend/backend contract, already agreed and adversarially reviewed. Save a
copy into this repo (e.g. docs/documentVault.md) so your work is reproducible. Do not
redesign it. If you believe you have found a contract bug, STOP and report it — never
silently diverge, because the backend is being built against this exact document.

SCOPE. Your work is exactly the client crypto core CC1–CC6 (§1.1) plus the UI
gestures that trigger them. If a task is not CC1–CC6 or its UI, it is backend work
and is already planned — do not reimplement it.

THE JOURNEY you are building is §0: log in → see all org documents → open one →
decrypt with a key you hold → create (upload + encrypt, choosing a key and
recipients, importing a key card for a new recipient) → publish if your role allows
→ follow the on-chain / IPFS / Indexer links.

NORMATIVE, NOT NEGOTIABLE:
- §2.1 constants are exact: PRF_SALT, the two HKDF info strings, AES-256-GCM, X25519,
  the zero nonce for the DEK wrap (keep the comment explaining WHY it is safe), and
  the encodings (lowercase hex vs base64). A KAT round-trip must pass against them.
- Invariants §2.7 I1–I10. The ones that will bite you:
  I1/I5 no private key, KEK, DEK or plaintext ever reaches any backend — prove it
  with a payload-capture test, do not merely assert it.
  I2 show the PASSKEY/PORTABLE assurance tier wherever a key is chosen or a recipient
  is picked, and flag PORTABLE recipients BEFORE encrypting.
  I3 WebAuthn runs inside the user gesture — do it first when mixing with async work.
  I4 prf.enabled === false is terminal. No silent downgrade.
  I6 select the slot by GCM-authenticated trial decryption, never by identifier.
- Strip a card's privateKey section before the request leaves the browser (§5.13);
  the backend rejects it (400 CARD_CONTAINS_PRIVATE_KEY) rather than dropping it.
- The publish button exists only for manager and admin (§4).
- The backend returns raw txHash / ipfsCid and NO urls. The explorer, IPFS gateway and
  Indexer URL bases are frontend deployment config (§9.5) — compose the links.
- Every list is paged.

ACCEPTANCE GATES: §8, items 1–9. Treat them as the definition of done.

PROCESS:
- Verify every assumption. Read the actual API contract and the real error catalog
  (§6) rather than guessing shapes. Do not invent endpoints.
- Brainstorm → spec → plan before code.
- Run the real tests and show the output before claiming anything passes.

FINAL STEP, MANDATORY: Codex adversarial review — /codex:adversarial-review. Loop
until Codex returns AGREE. Ask it specifically to attack the crypto core against the
§2.1 constants and to try to catch key material escaping to the network.
```

---

## 3. The Indexer — `cardano-foundation/cf-reeve-indexer`

Runs in the **Indexer repository**, which already holds its backend *and* its frontend. This is an
**additive** job, not a greenfield one — the repo is already a Spring + yaci-store indexer for Reeve
metadata, already configured for label 1447 and an `ipfs.gateway`, with a Vite/TypeScript frontend.

```
/goal Extend this repo (cf-reeve-indexer) into the independent verifier for published
Document Vault documents, per §9 of the contract.

CONTRACT (frozen — fetch and read it first, in full):
https://gist.githubusercontent.com/Kammerlo/48f3f37bd4933e880994f1d44831767e/raw/documentVault.md
§9 is your normative design; §2.1 (crypto constants), §2.6 (decrypt flow), §2.7
(invariants) and §2.8 (key cards) bind you too. Save a copy into docs/. Do not change the
contract — if you think it is wrong, STOP and say so; two other teams are building against
it right now.

SCOPE: teach the existing pipeline the `type: DOCUMENT` manifest, add the document read API
and card issuance (§9.6), and build the document / verification / decrypt views in the
existing frontend/. This is ADDITIVE — read the repo and follow its conventions before
writing anything; the contract's description of it is secondhand.
(The ledger-follower app inside the Reeve monolith is a DIFFERENT app. Not your repo.)

THE FOUR THINGS THAT ARE EASY TO GET WRONG:
1. NEVER read Reeve's database. Everything is reconstructed from Cardano L1 + IPFS alone.
   A verifier wired into the system it verifies proves nothing — that independence IS the
   product.
2. The private key is generated in the browser and NEVER reaches your backend (I1/I5). Your
   backend signs only the public part; the issuer key never reaches the browser. Issuance is
   a trust root: authenticate it. Verification is public — a verifier you must log into is
   not a verifier.
3. The card signing input (§2.8.3) must be BYTE-IDENTICAL to the Reeve backend's, or every
   card fails with 422 CARD_SIGNATURE_INVALID. Share the golden test vector with that team;
   do not write it twice. Same for the §2.1 crypto constants, which the Reeve frontend also
   implements — a KAT round-trip must pass, or documents encrypted in Reeve will not open
   here.
4. State §9.3's two honest limits; do not engineer around them or overclaim. In particular a
   forged label-1447 tx claiming any org id indexes as PUBLISHER_UNKNOWN and must render as
   a WARNING, never as a document. Per-org publisher keys would fix that properly and are
   OUT OF SCOPE — raise it, do not quietly build it.

PROCESS: verify every assumption against this repo's real source and real chain/IPFS
behaviour, including anything I have asserted above. Brainstorm → spec → plan before code.
JDK 21 (JAVA_HOME=$(/usr/libexec/java_home -v 21)). Run the real tests and show the output
before claiming anything passes.

FINAL STEP, MANDATORY: /codex:adversarial-review — loop until Codex returns AGREE. Point it
specifically at the card signing input and at whether any code path can leak a private key
to the backend.
```
