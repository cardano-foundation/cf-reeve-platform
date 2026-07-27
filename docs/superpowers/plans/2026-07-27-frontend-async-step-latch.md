# Frontend: sticky waiting indicator for async ceremony steps (spec step 9)

**Repo:** `cf-lob-frontend`, worktree `.claude/worktrees/feat+document-module`, branch `worktree-feat+document-module`.

**Goal:** Keep the per-step "waiting" indicator visible across the window where a ceremony-step POST has resolved but the next ceremony poll has not yet observed the resulting `*_REQUESTED` state.

**Why this is the only frontend change needed:** the wizard was already built for an asynchronous backend. `AttestationWizard`'s `activeStepId` is a `useMemo` over `STATE_TO_STEP[ceremony.state]` — derived from backend state, never from local step bookkeeping — `useGetCeremonyModel` self-polls, and `WAITING_STATES = ['CREDENTIAL_REQUESTED', 'AUTH_BEGIN_SUBMITTED', 'ATTEST_REQUESTED']` already covers exactly the three steps this redesign makes async. The API service comments already say *"202 Accepted; poll the ceremony for the result"*. So correctness is intact; only a presentation gap exists.

## The defect

`credential-step.component.tsx`, `auth-begin-step.component.tsx` and `attest-step.component.tsx` each derive their in-flight flags as roughly `(isWaiting || isPending) && !error`, and each carries a comment stating it leans on the mutation's `isPending` *"because the call is synchronous/blocking on the backend"*. That assumption dies with this redesign:

- Today: POST blocks until the work is done → `isPending` stays true throughout → indicator continuous.
- After: POST returns promptly → `isPending` drops immediately → up to one poll interval (~2s) where neither `isPending` nor `isWaiting` is true → indicator vanishes and the step looks idle or reverted.

This is cosmetic, not a correctness break — `WAITING_STATES` polling still advances the wizard — but it reads as a hang or a silent failure to the user at exactly the moment they are waiting on their wallet.

## Constraints

- **Minimal diff.** The publish flow does not change: `POST /documents/{id}/publish` keeps its contract and the wizard keeps deciding success from the resolved promise. Do **not** add document-status polling — nothing in the codebase does that today, and making publish event-gated is explicitly out of scope while `document_vault` and `keri_attestation` are co-deployed (spec D3).
- No new or changed API endpoint, react-query key, or `CeremonyState` member. There is no new ceremony state: AUTH_BEGIN moving onto the publisher queue reuses `AUTH_BEGIN_SUBMITTED`/`AUTH_BEGIN_CONFIRMED`.
- Leave the existing recovery contract alone. `refreshStatus` re-fetches rather than re-firing a mutation, precisely because the underlying step may still be in flight — which becomes *more* true after this work, not less.
- Nothing outside `src/modules/keri-attestation/` unless strictly necessary.

## Approach

Latch "request fired" once per step and clear it when the ceremony state actually advances, the step errors, or the ceremony reaches `FAILED`/`EXPIRED`. Implement the latch **once** in `useAttestationWizardFlow` and pass it down, rather than repeating it in three components — the three current copies of the `(isWaiting || isPending)` idiom are precisely why this defect appears three times.

## Verification

Existing tests passing unchanged is the evidence that the change is genuinely minimal:

```
npx vitest run src/modules/keri-attestation
npx tsc --noEmit
npx eslint src/modules/keri-attestation
```

Plus a new test per affected step asserting the indicator stays visible in the gap — mutation resolved, ceremony state not yet advanced. That gap is the whole point of the change and is currently untested.

## Explicitly not doing

- Converting publish to a polled flow (see Constraints).
- Touching `pairWithOobi`'s single `await refetchCeremony()`. `CREATED` is not in `WAITING_STATES`, so this step has no polling fallback — but OOBI resolution stays a synchronous KERIA call and does not go through the notification bridge, so it is unaffected. Noted as a latent fragility, not fixed here.
- AUTH_BEGIN copy changes. Worth revisiting once the queued path's real latency is measurable; guessing at wording now is premature.

---

## Implementation record (2026-07-27)

**Implemented** in `attestation-wizard.hooks.ts` as a single latch: capture `ceremony?.state` as a baseline immediately before `requestCredential`/`submitAuthBegin`/`requestAttest` fires, set `isRequestLatched`, and clear it as soon as a poll reports **any** state different from that baseline. That one rule covers all the release conditions without special-casing — advancing to the waited-for state, advancing past it, or reaching `FAILED`/`EXPIRED` all differ from the baseline. Also cleared on a caught mutation error and reset in `start()`/`close()`.

Exposed as `isActiveStepWaiting = isRequestLatched || WAITING_STATES.includes(ceremony.state)` — one flag rather than three, because exactly one step's mutation can be in flight at a time and `WAITING_STATES` entries map 1:1 to their steps. The three step components now receive `isWaiting` as a prop instead of each deriving it from `ceremony.state`, which is what caused the same defect to appear three times.

**Verification (clean, nothing else running):** `npx vitest run src/modules/keri-attestation` → **33 passed (33)**, three consecutive runs, exit 0. `npx tsc --noEmit` exit 0. `npx eslint src/modules/keri-attestation` exit 0. Baseline before the change was 31 passing, so this is +2 net tests covering the post-resolve/pre-poll gap.

## The flaky failures: diagnosed, and it is pre-existing

Intermediate runs reported 1–3 failures in pre-existing reset-identity, pair-step and AUTH_BEGIN tests, which looked like a regression from this change. It is not. Two things were going on, and the dominant one is a **latent, pre-existing flakiness in the spec file**.

Proven by stashing only the `src/modules/keri-attestation/*` edits and running the **untouched** suite five times under artificial CPU load (10× busy loops, load average ~40–55 on 8 cores):

```
ORIGINAL-CODE RUN 1: 1 failed | 22 passed (24)
ORIGINAL-CODE RUN 2: 1 failed | 22 passed (24)
ORIGINAL-CODE RUN 3: 5 failed | 19 passed (24)
ORIGINAL-CODE RUN 4: 24 passed (24)
ORIGINAL-CODE RUN 5: 24 passed (24)
```

An ever-changing *set* of unrelated tests times out at the default 5000ms `asyncUtilTimeout` when the event loop is CPU-starved — pair-step OOBI/QR, both reset-identity tests, and `AUTH_BEGIN "already published" … filled hash still submits { externalTxHash }`. This is a wall-clock budget problem, not a logic race, and it predates this work. My own intermediate readings were taken while a heavy Gradle build was saturating the machine, which is exactly this condition.

A secondary contributor: running `git stash` in this worktree while a subagent was doing its own stash/pop cycles there. **Never run git state operations in a worktree an agent is actively working in**, and treat measurements from such a window as void.

**Hardening applied to the three new tests only** (matching the 8000/12000/15000ms overrides this file already uses elsewhere for multi-hop async chains): longer waits on the `findByText` and on `waitFor(() => expect(ceremonyGetCalls)…)`, plus a per-test timeout. No assertion was weakened and nothing changed about *what* is awaited — each still waits on a deterministic signal (`ceremonyGetCalls` incrementing, proving the invalidation refetch landed), never on elapsed time or a fake timer. Re-stress-tested under the same load: all three pass in 170–330ms.

**Follow-up worth a ticket (not done here):** the whole spec file relies on the default 5000ms timeout and several tests it already contained flake under contention. Fixing that file-wide is a separate, pre-existing concern; bumping timeouts on tests this work did not author would have been an unrequested broader change.
