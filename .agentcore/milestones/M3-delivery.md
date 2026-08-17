# M3 — Secure Configuration and Reliable Delivery

## Rationale

Make every captured event durable and secret-safe before product UI completion.

**Risk:** high (bearer, private notification data, retries/process death)
**Approved tools:** Room/WorkManager/Keystore/HTTPS official docs, unit/Android DB/security tests
**Dependencies:** M2 passed
**Rollback:** revert M3 commits; preserve test-only DB evidence, never secrets

## Outcome

Keystore-backed bearer storage, atomic versioned JSON configuration, Room outbox, HTTPS client, exact result policy, retry/recovery and retention are implemented.

## Entry criteria

- Immutable local envelope/source state exists.
- Current Keystore, Room, WorkManager, backup and TLS docs accepted.

## Exit criteria

- Persist-before-send and process/reboot recovery pass.
- 202/400/401/413/415/429/503/network/timeout policy passes with fake clocks.
- Bearer absent from every prohibited surface.
- Config last-known-good/import/export/migration passes without secret.
- Backup/data-extraction, screenshot, clipboard, crash and log policies pass.

## Acceptance checks

- `AT-M3-1` through `AT-M3-5`; Room migrations; security scan; TLS validation; independent review.

## Required artifacts

- Keystore store, config schema/repository/migrations, Room schema/DAO/outbox state machine, transport/retry scheduler, retention/diagnostics and tests.

## Macro records

| Macro | Outcome | Dependencies | Responsible | Micro-checklist reference | Acceptance evidence |
|---|---|---|---|---|---|
| M3.A1 | Keystore secret + atomic config | M2 exit | security/config owner | defined at M3 entry | secret/config tests |
| M3.A2 | Transactional Room outbox | M3.A1 | persistence owner | defined at M3 entry | schema/migration/state tests |
| M3.A3 | Exact HTTPS/retry/recovery behavior | M3.A2 | transport owner | defined at M3 entry | status/fake-clock/restart tests |
| M3.A4 | Retention/diagnostic/backup protections | M3.A1-M3.A3 | security owner | defined at M3 entry | security/device/log evidence |
| M3.A5 | Reviewed restore checkpoint | M3.A4 | Codex authority maintainer | defined at M3 entry | review, commit, tool audit, handoff |

## Micro checklists

Exact `M3.A*.m*` rows are intentionally deferred until the M3 entry-refinement gate, after M2 fixes the envelope and source interfaces. They must be added to canonical checklist JSON before M3 implementation.

## Checkpoints

- Context Fabric: M3 entry/exit.
- Official docs: Keystore/Room/WorkManager/TLS/backup.
- Memory/handoff: security and delivery decisions.
- Tool audit: security/build/device tools at exit.
