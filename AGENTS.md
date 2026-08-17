# NFA Notification Collector — Agent Contract

This repository is the canonical source for the standalone native Android **NFA Notification Collector**.

## Read order

1. `AGENTS.md` and `CLAUDE.md`
2. `.agentcore/PROJECT_CHARTER.md`
3. `.agentcore/MILESTONES.md` and the active Milestone file
4. `.agentcore/RISK_REGISTER.md` and `.agentcore/ACCEPTANCE_TESTS.md`
5. `docs/AUTHORITY_AND_REFERENCE_MAP.md`
6. `docs/PRODUCT_REQUIREMENTS.md`
7. `docs/INGEST-CONTRACT.md`
8. `docs/ANDROID_TOOLCHAIN_BASELINE.md`
9. `docs/SECURITY-AND-DATA-HANDLING.md`
10. `docs/DECISIONS_AND_STOP_GATES.md`

## Exact workspace boundary

- Primary project and normal write target: `D:\github\nfa-notification-collector`
- Read-only AgentCore authority reference: `D:\github\agentcore-control-plane`
- Read-only gateway/database source reference: `D:\nfa-alerts-database`
- External mutable-state authority to attach: `D:\NFA-Database-Control\DATABASE_STATE.md`
- External durable NFA database reference to attach: `D:\github\nfa-platform\DATABASE.md`

Never edit or clean the two read-only workspace roots from this project. Never add `F:\PostgreSQL18\data` or `I:\LocalApps\NFAAlerts` as editable source roots.

## Product boundary

- Build a native Kotlin Android application with application ID `com.nfaalerts.collector`.
- Capture notifications through `NotificationListenerService`; never use Accessibility Service or polling as a substitute.
- Persist every selected notification to a durable local outbox before network delivery.
- Do not parse BNN, deduplicate, infer incidents/counties, geocode, or implement CRM/business logic on Android.
- Android sends only HTTPS schema-v1 requests to `POST /v1/ingest/alerts`; it never receives PostgreSQL credentials or direct database access.
- `deviceId` defaults to `nfa-primary-phone`. The bearer is entered interactively and stored with an Android Keystore-backed design; it is never committed, logged, exported, printed, or passed through ADB arguments.
- Duplicates are intentional and must remain distinct.
- Preserve the complete safely serializable notification envelope locally. The wire projection must obey the current gateway limits and record explicit omission/truncation metadata.

## Current contract stop gates

- The deployed gateway currently accepts only `source: "bnn"`. Do not change the permanent server contract from this repository. Before second-source live delivery, submit a bounded backward-compatible proposal to the `D:\nfa-alerts-database` authority maintainer and obtain operator approval.
- Stop if the useful notification envelope cannot fit the existing 256 KiB body / 32 KiB metadata contract without an approved deterministic projection.
- Do not claim phone-to-PC success until the Samsung device performs a real Tailscale HTTPS POST and receives `202`.
- Notification Access, bearer entry, battery settings, and device authorization are operator interactions; never automate or log them.
- Do not create signing secrets. A debug APK is the baseline unless an approved signing configuration already exists.

## Engineering rules

- Use `agentcore-project-lifecycle` for every nontrivial task and preserve exact project identity `nfa-notification-collector` at `D:\github\nfa-notification-collector`.
- Use official current Android/Jetpack documentation before pinning versions. Arabold Docs is first choice; if unavailable, record the failure and use official `developer.android.com`, Gradle, and Kotlin sources only.
- Apply TDD, small focused files, bounded workers, independent review, and fresh verification before completion claims.
- Keep a structured append-only worker-performance ledger under `.agentcore/evidence/` when workers stall, drift, hallucinate, or provide strong review evidence.
- No `.env` files. No secrets in source, docs, Git, Gradle properties, manifests, diagnostics, exported JSON, Room rows, crash data, or prompts.
- Do not commit generated build directories, APKs, signing files, local SDK paths, or captured notification data. Commit coherent milestones; push only if an approved remote exists.
- Documentation follows AgentCore documentation-guard/maintainer governance. Generated AgentCore projections are projection-worker-only.
- Upstream Root Agent Rules Template status is `upstream_template_missing`: no discoverable template section currently exists in `MASTER_CONFIG_AND_PROMPT.md`. This project seed is explicitly authorized by the operator request and must be reconciled during M0; do not claim template parity.
- No CRM.

## Definition of a valid handoff

Report exact changed files, build/test/lint results, APK path/hash, ADB/device evidence, gateway response evidence, Git status/commits, blockers, rollback, and any device/Tailscale step that remains unverified.
