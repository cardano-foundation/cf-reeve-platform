# keri_attestation

Wallet-based KERI attestation module: links a platform user's Veridian wallet AID, walks them
through IPEX credential presentation and AUTH_BEGIN authority, then anchors a document's CIP-170
metadata digest via a Veridian **remotesign** request before the document is published on-chain.

Design: `docs/superpowers/specs/2026-07-21-keri-wallet-attestation-design.md`.

## CIP-170 reference

This module implements CIP-170 field-for-field, including the exact `v` version object contents
and the 64-byte chunk encoding of `AUTH_BEGIN.c`. CIP-170 is **Proposed**, not Active, so
implementation pins one commit of the upstream spec and treats it as the source of truth over any
demo/reference repo behavior (see design §3, "protocol deviations caught in review").

- Upstream repo: [`cardano-foundation/CIPs`](https://github.com/cardano-foundation/CIPs), path
  `CIP-0170/README.md`.
- **Pinned commit:
  [`86b89208d3b2aabb5dcc5b778dfbe09096b4e114`](https://github.com/cardano-foundation/CIPs/blob/86b89208d3b2aabb5dcc5b778dfbe09096b4e114/CIP-0170/README.md)**
  — the latest commit touching `CIP-0170` as of 2026-06-04. CIP status at this revision:
  **Proposed**.
- `Cip170MetadataFactory`'s field set was cross-checked against this revision's field tables and
  matches exactly:
  - `ATTEST`: `{t, s, i, d, v:{v:"1.0"}}` — matches the CIP's "Creation of verifiable records"
    field list (`t`, `i`, `d`, `s`, `v`) and its worked vLEI example (`v:{v:"1.0"}`). Map key
    insertion order differs from the CIP's JSON examples in a few fields, but this is immaterial:
    canonical CBOR (RFC 7049 §3.9) sorts keys deterministically regardless of insertion order, so
    on-chain byte identity comes from the field *set*, not example ordering.
  - `AUTH_BEGIN`: `{t, s, i, c, v:{v:"1.0", k:"KERI10", a:"ACDC10"}, m:{l:[...]}}` — matches the
    CIP's "Establishment of signing authority" field list (`t`, `i`, `s`, `c`, `v`, `m`) and its
    worked vLEI example (`v:{v:"1.0", k:"KERI10", a:"ACDC10"}`, `m:{l:[1447], LEI:"..."}` — this
    module generalizes the reference's hardcoded `l:[1447]`/`LEI` pair to caller-supplied labels
    and extra entries, per `Cip170MetadataFactory#authBeginMap`'s own javadoc).
  - Golden vectors in `Cip170MetadataFactoryTest` additionally match the in-repo reference scripts
    (`docs/keri/AttestTransaction.java`, `docs/keri/advanced/PublishExistingCredential.java`).

## Remotesign spike

**Status: not yet run.** This is a manual, human-operated gate — it requires a real Veridian
wallet to approve a signing request interactively. It answers the design's hard-blocker question
(spec §4.4): does Veridian's remotesign flow anchor a caller-chosen digest as the KEL interaction
seal (`a: [{d: <digest>}]`), or only the SAID of the request envelope? If only the latter, this is
a hard blocker — a transport-envelope SAID is not interchangeable with the CIP-170 digest, and
Task 9 (the ATTEST wallet-anchoring implementation) cannot proceed as designed without wallet-side
or spec-side coordination.

Script: `docs/keri/spike/RemotesignAnchorSpike.java`.

### Prerequisites

- [jbang](https://www.jbang.dev/) installed (`brew install jbang` or see jbang docs).
- A Veridian wallet app (mobile) with an existing AID, connected to the same KERIA instance as the
  spike script (default: the Reeve KERIA deployment — see the script's `KERI_URL`/`KERI_BOOT_URL`
  defaults).
- The wallet's OOBI URL. In the Veridian app: **Identifiers → select your AID → Share/Connect**,
  which shows a QR code and/or a copyable OOBI URL of the form
  `http://<host>/oobi/<walletAid>/agent/<agentAid>`. Scan the QR with any QR reader to get the
  text, or use the app's "copy OOBI" action if present.
- Before running: pair the spike's own agent OOBI (printed by the script on first run, under "Our
  agent OOBI") into Veridian as a contact, if the wallet requires a known contact to accept
  incoming remotesign requests.

### Run

```bash
WALLET_OOBI="http://<host>/oobi/<walletAid>/agent/<agentAid>" \
  jbang docs/keri/spike/RemotesignAnchorSpike.java
```

Environment variables:

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `WALLET_OOBI` | **yes** | — | The Veridian wallet's OOBI URL (see above). |
| `KERI_URL` | no | Reeve KERIA agent URL | KERIA agent base URL. |
| `KERI_BOOT_URL` | no | Reeve KERIA boot URL | KERIA boot base URL. |
| `PASSCODE` | no | generated + printed | signify client passcode (bran); set it to reuse the same local identifier across runs. |
| `IDENTIFIER_NAME` | no | `RemotesignSpike` | Local identifier alias created/reused for the spike. |
| `KED_VARIANT` | no | `A` | Which request KED shape to send — `A`, `B`, or `C` (see the script's Javadoc for the exact shape of each). |

The script prints, in order: the digest under test, the sent request's SAID, the full raw
`/remotesign/ixn/ref` notification and exn JSON once approved in Veridian, the wallet's anchoring
KEL event (sequence, SAID, full seal list), and an explicit verdict line:

```
VERDICT SEAL_MATCHES_DIGEST: true|false
```

If variant `A` does not match, the script prints the exact command to re-run with `KED_VARIANT=B`,
then `C`. If none of the three variants match, it prints the spec §4.4 hard-blocker message — stop
and escalate before implementing Task 9.

## Spike findings

*(Fill in after running the spike against a real Veridian wallet. Leave sections blank, not
deleted, if a given run did not get far enough to answer them.)*

### KED variant that worked

- Variant (A / B / C / none):
- Exact KED shape sent (copy the "KED payload" block from the script output):
- Notes on why this shape works (e.g. does the wallet require `i`, does it ignore extra fields):

### Ref exn structure

- Route observed (`/remotesign/ixn/ref`, `/exn/remotesign/ixn/ref`, or other — paste the exact
  string from the script's "route (a.r)" line):
- Full ref exn JSON (paste the "Full ref exn (ExchangeResource) JSON" block):

### Thread-back / correlation field

- Which field on the ref exn (or its containing notification) identifies the originating request
  (`request_exn_said`) — e.g. `p` (prior), `q.d`, an embed, or something else. This field is what
  Task 6's `KeriNotificationCorrelator` must match on:

### Seal semantics

- Anchoring event sequence (`s`):
- Anchoring event SAID (`d`):
- Anchoring event seal list (`a`), full JSON:
- Does the wallet only ever anchor `[{"d": ...}]`-shaped seals, or does it accept/preserve
  additional fields (e.g. `i`) in the seal too?

### Verdict

- `SEAL_MATCHES_DIGEST`:
- If `false` for all three variants: **hard blocker — spec §4.4**. Escalation notes:
- Pinned CIP-170 commit used for this run: see "Pinned commit" under [CIP-170 reference](#cip-170-reference)
  above — confirm it is still the latest commit touching `CIP-0170` at the time this spike is actually
  run, and re-pin (with a fresh cross-check of the field tables) if not.
