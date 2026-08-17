# M4 — Product UI and Diagnostics

## Rationale

Expose the proven capture/delivery core through a clear, safe Samsung workflow.

**Risk:** medium (operator confusion, accessibility, secret/content exposure)
**Approved tools:** Compose tests, accessibility/visual verification, Android settings intents
**Dependencies:** M3 passed
**Rollback:** revert M4 commits; data/config schemas remain compatible

## Outcome

Guided first run and accessible Status, Sources, Delivery and Settings screens manage the collector without exposing secrets or mutating captured content.

## Entry criteria

- Capture, configuration, secret and outbox APIs are stable/tested.
- Accepted UI/security design and settings-intent docs.

## Exit criteria

- Setup readiness is truthful and all navigation/state-restoration tests pass.
- Source picker, delivery detail/retry, JSON editor/import/export and reliability controls pass.
- Secret/full-content screenshot policy, diagnostics redaction and accessibility pass.

## Acceptance checks

- `AT-M4-1`; Compose/UI/accessibility tests; visual review; independent product/security review.

## Required artifacts

- Compose screens/components/navigation/view models, state reducers, previews/test fixtures and UI evidence.

## Macro records

| Macro | Outcome | Dependencies | Responsible | Micro-checklist reference | Acceptance evidence |
|---|---|---|---|---|---|
| M4.A1 | Guided setup/readiness/status | M3 exit | UI owner | defined at M4 entry | Compose/state tests |
| M4.A2 | Sources picker/editor | M4.A1 + M2 APIs | UI/source owner | defined at M4 entry | max-ten/system-toggle tests |
| M4.A3 | Delivery inspection/retry/diagnostics | M4.A1 + M3 APIs | UI/delivery owner | defined at M4 entry | immutable-detail/retry tests |
| M4.A4 | Settings/secret/config/reliability | M4.A1 + M3 APIs | UI/security owner | defined at M4 entry | settings/import/export tests |
| M4.A5 | Reviewed restore checkpoint | M4.A2-M4.A4 | Codex authority maintainer | defined at M4 entry | accessibility/visual/security review and handoff |

## Micro checklists

Exact `M4.A*.m*` rows are intentionally deferred until the M4 entry-refinement gate, after stable M2/M3 interfaces exist. They must be added to canonical checklist JSON before M4 implementation.

## Checkpoints

- Context Fabric: M4 entry/exit.
- Official docs: Compose/Material/accessibility/settings intents.
- Memory/handoff: UI/security decisions and exit packet.
- Tool audit: visual/device tools at exit.
