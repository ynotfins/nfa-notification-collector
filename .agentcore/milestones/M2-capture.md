# M2 — Notification Capture and Source Selection

## Rationale

Prove loss-resistant, bounded capture before transport or UI breadth.

**Risk:** high (main-thread callbacks, private content, unfamiliar extras)
**Approved tools:** Android SDK tests, bounded fixtures, Compose source-picker tests
**Dependencies:** M1 passed
**Rollback:** revert M2 commits; no server changes

## Outcome

A real NotificationListenerService snapshots selected notifications, a max-ten package allowlist/source configuration persists, and a robust safe serializer/raw-text strategy preserves the local envelope.

## Entry criteria

- Compiling shell and fixture/test infrastructure pass.
- NotificationListenerService/package-visibility official docs checkpoint accepted.

## Exit criteria

- Listener permission/declaration/status flows pass.
- User/system app picker and exact 10/11 behavior pass.
- Serializer survives null/unknown/custom/large/Unicode/action/message/icon cases per key.
- Raw text is unchanged; all candidates retained.
- Non-BNN is stored locally as `BLOCKED_CONTRACT` and never sent/retried.

## Acceptance checks

- `AT-M2-1` through `AT-M2-5`; callback-time budget; manifest permission review; independent review.

## Required artifacts

- Listener, app discovery/selection, source model/repository, serializer, raw-text extractor, fixtures and tests.

## Macro records

| Macro | Outcome | Dependencies | Responsible | Micro-checklist reference | Acceptance evidence |
|---|---|---|---|---|---|
| M2.A1 | Listener declaration/lifecycle/status | M1 exit | capture owner | defined at M2 entry | manifest/lifecycle tests |
| M2.A2 | Exact installed-app/max-ten allowlist | M2.A1 | source-picker owner | defined at M2 entry | app discovery/UI/state tests |
| M2.A3 | Bounded complete safe envelope | M2.A1 | serializer owner | defined at M2 entry | fixture/stress matrix |
| M2.A4 | Raw-text/source/BLOCKED_CONTRACT policy | M2.A2,M2.A3 | capture owner | defined at M2 entry | preservation/no-send tests |
| M2.A5 | Reviewed restore checkpoint | M2.A4 | Codex authority maintainer | defined at M2 entry | review, commit, tool audit, handoff |

## Micro checklists

Exact `M2.A*.m*` rows are intentionally deferred until the M2 entry-refinement gate, after M1 establishes concrete modules and test infrastructure. They must be added to canonical checklist JSON before M2 implementation.

## Checkpoints

- Context Fabric: M2 entry/exit.
- Official docs: listener/package visibility/platform behavior.
- Memory/handoff: capture architecture decisions and exit packet.
- Tool audit: listener/device tools at exit.
