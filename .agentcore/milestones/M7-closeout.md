# M7 — Closeout and Handoff

## Rationale

Leave one reproducible operational package and an honest final state.

**Risk:** medium (documentation drift, artifact/secret/Git claims)
**Approved tools:** documentation guard/maintainer, independent reviewer, Git/secret/junk validators
**Dependencies:** M6 offline gates passed; live rows resolved or explicitly blocked
**Rollback:** revert bounded closeout commits; preserve prior verified artifact/evidence

## Outcome

Operational documentation, final independent review, Git history/status, performance ledger, evidence report and durable handoff accurately describe the built collector.

## Entry criteria

- Verified APK and all available acceptance evidence.
- Known external/device blockers classified.

## Exit criteria

- README and exact INSTALL/CONFIGURATION/INGEST-CONTRACT/TROUBLESHOOTING/security docs match behavior.
- Secret/junk/build-artifact scans pass; no signing/capture data committed.
- Performance ledger and final report include worker outcomes.
- Local commits are coherent; push only if an approved remote exists.
- Handoff includes exact next action for blocked device/Tailscale/server decision rows.

## Acceptance checks

- `AT-M7-1`; documentation guard ACCEPT; independent final review; Git/status/artifact/hash/secret evidence.

## Required artifacts

- Updated operational docs, final report, performance ledger, Git evidence and AgentCore handoff.

## Macro records

| Macro | Outcome | Dependencies | Responsible | Micro-checklist reference | Acceptance evidence |
|---|---|---|---|---|---|
| M7.A1 | Behavior/docs reconciliation | M6 exit | documentation maintainer | defined at M7 entry | documentation diff/guard evidence |
| M7.A2 | Complete verification and scans | M7.A1 | validation owner | defined at M7 entry | test/lint/build/secret/junk/Git results |
| M7.A3 | Independent final review | M7.A2 | fresh product/security reviewers | defined at M7 entry | ACCEPT verdicts |
| M7.A4 | Git/handoff/final outcome | M7.A3 | Codex authority maintainer | defined at M7 entry | commits/status/handoff/final report |

## Micro checklists

Exact `M7.A*.m*` rows are intentionally deferred until the M7 entry-refinement gate, when final artifacts and blockers are known. They must be added to canonical checklist JSON before M7 execution.

## Checkpoints

- Context Fabric: M7 exit.
- Official docs: final dependency/platform index.
- Memory/handoff: final outcome and continuation packet.
- Tool audit: all task-only capabilities dormant/released.
