# M1 — Toolchain and Compiling Shell

## Rationale

Lock a reproducible supported build before feature code.

**Risk:** medium (version/toolchain compatibility)
**Approved tools:** official docs, JDK/SDK/Gradle wrapper, Android build/test/lint
**Dependencies:** M0 passed and plan approved
**Rollback:** revert M1 commits; preserve wrapper/version evidence

## Outcome

Current official dependency pins, explicit JDK selection, Gradle wrapper, and a minimal `com.nfaalerts.collector` Compose application pass baseline tests, lint and debug assembly.

## Entry criteria

- M0 exit evidence and approved implementation plan.
- Arabold or policy-valid official-primary dependency checkpoint.
- SDK/JDK paths reverified.

## Exit criteria

- Wrapper and version catalog are exact and reproducible with JDK 17 if current AGP requires it.
- Minimal app/test/lint/build gates pass twice from clean state.
- No local SDK path, secret, signing material or generated build output is committed.

## Acceptance checks

- `AT-M1-1`; dependency audit; clean build manifest comparison; independent review.

## Required artifacts

- Gradle wrapper, settings/build/version-catalog files, app manifest/module shell, baseline tests and M1 evidence.

## Macro records

| Macro | Outcome | Dependencies | Responsible | Micro-checklist reference | Acceptance evidence |
|---|---|---|---|---|---|
| M1.A1 | Official supported toolchain decision | M0 docs checkpoint | build owner | defined at M1 entry | version/source decision record |
| M1.A2 | Reproducible wrapper and app shell | M1.A1 | Android owner | defined at M1 entry | clean wrapper/build output |
| M1.A3 | Baseline verification gates | M1.A2 | test owner | defined at M1 entry | unit/lint/static/build logs |
| M1.A4 | Reviewed restore checkpoint | M1.A3 | Codex authority maintainer | defined at M1 entry | review, commit, tool audit, handoff |

## Micro checklists

Exact `M1.A*.m*` rows are intentionally deferred until the M1 entry-refinement gate, when version decisions and file paths are evidenced. They must be added to canonical checklist JSON before M1 implementation.

## Checkpoints

- Context Fabric: M1 entry/exit.
- Official docs: exact version/source index.
- Memory/handoff: M1 evidence event and exit packet.
- Tool audit: build tools dormant/active state at exit.
