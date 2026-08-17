# M6 — APK and Samsung Acceptance

## Rationale

Produce the installable artifact and separate actual device proof from offline confidence.

**Risk:** high (device permissions, bearer entry, Tailscale/signing)
**Approved tools:** Gradle verification, SDK ADB, operator-driven Android settings, trusted server verifier
**Dependencies:** M5 passed
**Rollback:** uninstall/update only with operator approval; never clear app data/pending queue

## Outcome

A verified debug APK exists and every available Samsung installation, setup, BNN, second-source and Tailscale acceptance row is evidenced or accurately blocked.

## Entry criteria

- Full offline feature/test gates pass.
- SDK-owned ADB/device state rechecked.
- Operator present for Notification Access, bearer and battery settings.

## Exit criteria

- Tests/lint/static/dependency/clean builds pass.
- `artifacts/NFA-Notification-Collector.apk` and SHA-256 recorded.
- ADB install/package/version evidence exists when device connected.
- BNN phone/local/server comparison exists when Tailscale/operator interactions available.
- Second-source live test occurs only after explicit server amendment approval.

## Acceptance checks

- `AT-M6-1` through `AT-M6-5`; no false live/signing claims; independent release/security review.

## Required artifacts

- APK/hash/build logs, dependency report, ADB/device evidence, operator setup checklist, BNN/second-source/Tailscale reports or exact blockers.

## Macro records

| Macro | Outcome | Dependencies | Responsible | Micro-checklist reference | Acceptance evidence |
|---|---|---|---|---|---|
| M6.A1 | Reproducible verified APK | M5 exit | release owner | defined at M6 entry | full build/lint/test logs |
| M6.A2 | Hashed artifact/dependency security review | M6.A1 | release/security owners | defined at M6 entry | APK path/hash/audit |
| M6.A3 | Device install and operator setup | M6.A2 + authorized ADB/device | device validation owner + operator | defined at M6 entry | package/permission/setup evidence |
| M6.A4 | Real BNN comparison | M6.A3 + Tailscale reachable | device + database authority owners | defined at M6 entry | phone/local/server evidence |
| M6.A5 | Approved second-source result or blocker | M6.A3 + server approval | authority maintainer | defined at M6 entry | decision/live evidence or blocker |
| M6.A6 | Reviewed restore checkpoint | M6.A1-M6.A5 | Codex authority maintainer | defined at M6 entry | review, commit, tool audit, handoff |

## Micro checklists

Exact `M6.A*.m*` rows are intentionally deferred until the M6 entry-refinement gate, when device/ADB/Tailscale/signing availability is current. They must be added to canonical checklist JSON before M6 execution.

## Checkpoints

- Context Fabric: M6 entry/exit.
- Official docs: target device/platform/distribution/signing updates.
- Memory/handoff: artifact and live acceptance evidence.
- Tool audit: ADB/operator capabilities released at exit.
