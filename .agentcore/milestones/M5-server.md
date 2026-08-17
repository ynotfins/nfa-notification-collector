# M5 — Local Server Compatibility

## Rationale

Prove the Android wire implementation against the existing boundary before APK/device claims.

**Risk:** medium (append-only test row, contract/secret boundary)
**Approved tools:** local Android-equivalent client test, trusted database-project verifier/handoff
**Dependencies:** M4 passed; local gateway healthy
**Rollback:** no raw-row deletion; revoke/disable only dedicated test credential/device when used

## Outcome

A dedicated BNN-equivalent schema-v1 event receives a validated 202/UUID/UTC response and the database owner proves a new append-only row with correct bytes/hashes/provenance.

## Entry criteria

- Transport projection fits measured limits.
- BNN-only contract unchanged and test bearer is supplied without entering project storage.
- Trusted verifier/handoff path agreed with `D:\nfa-alerts-database` owner.

## Exit criteria

- 202 response and committed-row proof agree.
- No DB credentials/direct SQL are exposed to the collector project/IDE.
- Rejection/duplicate/hash/limit compatibility tests pass.
- Historical rows remain untouched.

## Acceptance checks

- `AT-M5-1`, `AT-M5-2`; secret/log scan; gateway-owner evidence; independent review.

## Required artifacts

- Android-equivalent request fixture/runner, measured wire report, response evidence and database-owner verification handoff.

## Macro records

| Macro | Outcome | Dependencies | Responsible | Micro-checklist reference | Acceptance evidence |
|---|---|---|---|---|---|
| M5.A1 | Revalidated gateway/test boundary | M4 exit + gateway healthy | compatibility owner | defined at M5 entry | release/contract/preflight evidence |
| M5.A2 | Validated BNN 202 response | M5.A1 | Android transport owner | defined at M5 entry | request/response evidence |
| M5.A3 | Trusted committed-row proof | M5.A2 | database authority maintainer | defined at M5 entry | row/hash/provenance handoff |
| M5.A4 | Reviewed append-only checkpoint | M5.A3 | Codex authority maintainer | defined at M5 entry | review, commit, tool audit, handoff |

## Micro checklists

Exact `M5.A*.m*` rows are intentionally deferred until the M5 entry-refinement gate, when the live release and trusted verifier are revalidated. They must be added to canonical checklist JSON before M5 execution.

## Checkpoints

- Context Fabric: M5 entry/exit.
- Official docs: HTTP/TLS client behavior used.
- Memory/handoff: compatibility evidence and server-owner refs.
- Tool audit: live gateway/test capability released at exit.
