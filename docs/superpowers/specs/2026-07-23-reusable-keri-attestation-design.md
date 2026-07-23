# Reusable KERI/Veridian Attestation — Design

**Date:** 2026-07-23
**Status:** Approved — implementing
**Repos:** backend `cf-reeve-platform` (branch `feat/document-module`); frontend worktree `cf-lob-frontend/.claude/worktrees/feat+document-module`

## Goal

Turn the working KERI/Veridian attestation flow (currently coupled to document publishing in the UI) into a **reusable capability** any module can mount, and add two user capabilities: **publish my credential chain** (AUTH_BEGIN) and **reset my identity**. The end-to-end procedure is the same everywhere:

1. **Pair** (OOBI exchange)
2. **Credential presentation** (produces the credential chain)
3. **Optional one-time AUTH_BEGIN** — publish the credential chain on-chain (once per identity)
4. **Authorize**
5. **Attest & publish the transaction** (label 1447 + label 170)

The wizard is the single home for all of pair / credential / AUTH_BEGIN / reset / attest.

## Backend (`keri_attestation`)

The module is already target-agnostic: the ceremony state machine, `KeriCredentialService`/`KeriAuthBeginService`/`KeriAttestService`, and the REST API key off `targetType` via the `AttestationTargetProvider` port. `DOCUMENT` is one provider (in `blockchain_publisher`). A future reports module registers a `REPORT` provider (implements `authorize` + `prepareDigest`) with **zero** changes here.

1. **Audit for residual document coupling.** Confirm nothing in `keri_attestation` hardcodes `"DOCUMENT"`; the only document-specific code lives behind the provider in `blockchain_publisher`. Fix any leak.
2. **Reset identity** — `DELETE /api/v1/keri-attestation/identity` → `KeriOobiService.resetIdentity(userId)`: delete the caller's `keri_identity_link` row and fail all their non-terminal ceremonies with a new `IDENTITY_RESET` problem title. Idempotent (no link → 200 no-op). `@PreAuthorize` same roles as the other endpoints; owner-scoped (only the caller's own link/ceremonies). Returns 200.
3. **Publish credential chain (AUTH_BEGIN)** — already `POST /ceremonies/{id}/auth-begin`, one-time (once `auth_begin_tx_hash` is recorded on the link, `requiredSteps.authBegin` is false and the wizard skips it). Ensure the own-chain path validates the presented chain (see #4) before building the AUTH_BEGIN metadata + submitting.
4. **Chain validation → structure-only, with TODOs.** `CredentialChainValidator` currently enforces: issuee == presenting AID, leaf schema in `schema-saids`, chain terminates in a trusted root, per-link revocation/TEL state. Relax to **structure-only for now**: keep the parse + issuee check + TEL/revocation state (these establish "is this a valid chain"), but **drop the schema-allowlist and trusted-root enforcement**, each replaced by a `// TODO(policy): re-enable …` marker. Apply the same validation at both credential-presentation and AUTH_BEGIN. (The empty-trusted-root "trust any" + loud warning stays; the schema check becomes a no-op TODO.)

## Frontend (extract to a shared module)

5. **New `src/modules/keri-attestation`** — the home for the reusable flow:
   - `AttestationWizard` component parameterized by `{ targetType, targetId, organisationId, onPublished, publish, copy? }`. `publish` is the module's own publish call (e.g. document publish with the ceremony id); `copy?` overrides step titles/body for the host domain.
   - Steps: Pair → Credential presentation → one-time AUTH_BEGIN (publish chain) → Authorize → Attest & publish, plus a **Reset identity** control (confirm dialog → `DELETE /identity`, then reset local wizard state).
   - The keri-attestation API connector (`.../api/keri-attestation`) and react-query models (`libs/models/keri-attestation-model/*`) are the shared data layer; add `useResetIdentityModel` (mutation → `DELETE /identity`, invalidates `['KERI_IDENTITY']`).
6. **`document-vault` becomes a thin adapter.** `features/attest-publish` mounts `AttestationWizard` with `targetType="DOCUMENT"`, `publish = triggerPublishVaultDocument`, and document copy. Existing behavior (plain-publish fallback, error mapping, polling) preserved.

## Reuse for reports / other modules later

- Backend: add a `REPORT` (or other) `AttestationTargetProvider` in the owning module.
- Frontend: mount `AttestationWizard` with the new `targetType` + that module's `publish`.
- No changes to `keri_attestation` or the shared wizard.

## Out of scope (now)

- Trusted-root / schema policy enforcement (TODO markers left in place).
- The reports provider itself (this spec only makes the seam reusable).
- Changing the on-chain metadata shape.

## Testing

- Backend: reset-identity (deletes link, fails open ceremonies, idempotent, owner-scoped); structure-only validation (valid chain accepted regardless of schema/root; malformed/wrong-issuee/revoked still rejected); auth-begin validates the chain.
- Frontend: `AttestationWizard` renders/derives steps from a generic target; document-vault adapter still drives publish with the ceremony id; reset control calls `DELETE /identity` and resets state; existing attest-publish tests adapted to the shared component.
- Full module gates green (JDK 21) each side.
