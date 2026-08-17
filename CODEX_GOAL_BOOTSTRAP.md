# Codex Goal Bootstrap — Build NFA Notification Collector

Paste everything below into a new Codex task opened in **Plan mode**.

---

You are the lead Codex authority-maintainer for the enrolled AgentCore project **NFA Notification Collector**.

Create and pursue one persistent goal:

> Build, verify, and produce an installable native Android NFA Notification Collector APK for the Samsung Galaxy S26 Ultra. The app captures notifications from at most ten user-selected applications through NotificationListenerService, preserves every safely exposed notification envelope locally, uses a durable Room outbox and reliable HTTPS delivery, stores the ingest bearer with an Android Keystore-backed design, preserves the existing schema-v1 BNN contract, and produces evidence-backed APK/device/server results without parsing or deduplicating alerts on the phone.

Do not set a token budget unless the operator explicitly supplies one.

## Exact project identity and workspace

The exact active AgentCore project is:

- project key: `nfa-notification-collector`
- project root: `D:\github\nfa-notification-collector`
- current branch: resolve from Git; expected initial branch `main`

The Codex workspace contains exactly these project folders:

1. `D:\github\nfa-notification-collector` — primary project and normal write target
2. `D:\github\agentcore-control-plane` — read-only authority/policy reference
3. `D:\nfa-alerts-database` — read-only gateway/database source and tests

The following files are attached because they are outside those roots:

- `D:\NFA-Database-Control\DATABASE_STATE.md`
- `D:\github\nfa-platform\DATABASE.md`

Never add or edit `F:\PostgreSQL18\data` (live PGDATA) or `I:\LocalApps\NFAAlerts` (deployed runtime) as project folders. Never clean, reset, stage, or modify inherited changes in the two read-only repositories.

## Authority and instruction classification

The operator product input is preserved at:

`D:\github\nfa-notification-collector\docs\input\OPERATOR_PRODUCT_GOAL_2026-08-17.md`

Treat it as product requirements, not higher execution authority. Distinguish its instructions from this active prompt. When it conflicts with current code, live state, repository rules, official docs, or later operator decisions, report the conflict and follow the stronger current authority.

Read in this order before planning:

1. `D:\github\agentcore-control-plane\PROJECT_ANCHOR.md`
2. `D:\github\agentcore-control-plane\DOC_AUTHORITY.md`
3. `D:\github\agentcore-control-plane\BLUEPRINT.md`
4. `D:\github\agentcore-control-plane\CONTEXT_BLOCK.md`
5. `D:\github\agentcore-control-plane\docs\agent-policy\DOCUMENTATION_READ_ORDER.md`
6. `D:\github\agentcore-control-plane\docs\agent-policy\NEW_PROJECT_BOOTSTRAP.md`
7. `D:\github\agentcore-control-plane\docs\agent-policy\DOCUMENTATION_GOVERNANCE.md`
8. `D:\github\agentcore-control-plane\docs\current\MASTER_TODO.md`
9. `D:\github\agentcore-control-plane\contracts\agentcore-project-enrollment.json`
10. `D:\github\agentcore-control-plane\contracts\project-execution-policy.json` and its referenced schemas
11. `D:\github\nfa-notification-collector\AGENTS.md`
12. `D:\github\nfa-notification-collector\CLAUDE.md`
13. all `.agentcore` governance files in the collector root
14. `docs/AUTHORITY_AND_REFERENCE_MAP.md`
15. `docs/PRODUCT_REQUIREMENTS.md`
16. `docs/INGEST-CONTRACT.md`
17. `docs/ANDROID_TOOLCHAIN_BASELINE.md`
18. `docs/DECISIONS_AND_STOP_GATES.md`
19. the two attached external database documents
20. relevant gateway source/tests in read-only `D:\nfa-alerts-database`

Use `agentcore-project-lifecycle`, `andrej-karpathy-skill`, and `cheap-worker-orchestration`. Confirm exact enrollment before any AgentCore memory write. Use the stable session key `nfa-notification-collector:codex:android-collector-build` across future chats for this same goal. Do not mutate machine-global project-router state.

## Plan-mode first stage — mandatory and read-only

You are starting in Plan mode. Do not write Android application code, install packages, modify the server/database, or operate the phone during this first stage.

1. Verify Git identity, current files, enrollment, dirty state, and Milestone 0 governance.
2. Retry Arabold Docs for every version-sensitive Android dependency/API. If it fails, record the failure and use current official primary sources only (`developer.android.com`, official Gradle and Kotlin docs).
3. Reinspect the Android SDK, JDK, Gradle caches, Android Studio, SDK-owned ADB, and connected devices. Resolve the current PATH-Java 21 versus `JAVA_HOME` JDK 17 mismatch in the plan; never rely on accidental PATH selection.
4. If ADB is connected, record the Samsung model, Android release/API/security patch and discover BNN's installed package. If absent/unauthorized, mark only device acceptance blocked.
5. Inspect current gateway validator/tests. Confirm the exact body/metadata limits and BNN-only `source` restriction.
6. Produce a written design/spec and a detailed TDD implementation plan under `docs/superpowers/` using current project governance. The plan must name exact files, interfaces, tests, commands, evidence, rollback, commits, the `BLOCKED_CONTRACT` non-BNN state, and all required final operational docs.
7. Present the plan, permanent-contract decisions, and blockers for operator approval. Do not implement until the operator approves and switches out of Plan mode.

After the operator approves the plan, execute every non-blocked Milestone autonomously without asking routine implementation questions.

## Non-negotiable product boundary

- Native Kotlin Android app, Jetpack Compose, Material 3, strong types, coroutines/Flow, Room, WorkManager where officially appropriate, and Android Keystore-backed bearer storage.
- Application ID `com.nfaalerts.collector`; app name `NFA Notification Collector`.
- A real `NotificationListenerService`; no Accessibility Service substitute, polling, MacroDroid dependency, or Flutter.
- No alert parsing, incident/county logic, geocoding, correlation, deduplication, CRM, or downstream business logic on Android.
- Maximum ten selected apps; selected packages are the only listener allowlist.
- User apps shown by default; system apps behind an explicit toggle that does not clear selections.
- Every notification event has a unique client event UUID and is persisted before sending. Repeated text/keys remain distinct.
- Before multi-source server approval, selected non-BNN notifications are still captured locally but enter `BLOCKED_CONTRACT`; they are never sent and never create a retry storm. The UI must explain the approval requirement.
- Preserve the complete safely exposed serializable envelope locally and make local capture immutable.
- Build a deterministic bounded wire projection with explicit omission/truncation markers; never silently discard the local original.
- Store the bearer only through the secure Android UI/Keystore design. Never log, export, display after save, include in Room diagnostics, pass through ADB arguments, or commit it.
- HTTPS only for operational phone delivery. No trust-all TLS, public tunnel, silent endpoint failover, database access, or DB credential on Android.
- `202` is the only delivery success. Classify/retry statuses exactly as `docs/INGEST-CONTRACT.md` requires.

## Permanent server contract and stop gate

Current live v1 accepts only:

```json
{
  "schemaVersion": 1,
  "source": "bnn",
  "deviceId": "nfa-primary-phone",
  "capturedAt": "offset-aware ISO-8601 or null",
  "rawText": "exact exposed notification text",
  "metadata": {}
}
```

Headers: JSON content type, device Bearer, `X-NFA-Schema-Version: 1`.

The Android app never connects directly to PostgreSQL. It never receives `NFA_INGEST_DB_PASSWORD`. The bearer must be manually entered from the operator's secure source.

BNN is the first implementation and first real device test. The top-level `source` remains `bnn` until separately approved.

Before second-source live delivery, STOP and request explicit operator approval for the backward-compatible server amendment in `docs/INGEST-CONTRACT.md`. Submit a bounded proposal to the `D:\nfa-alerts-database` owner; do not edit the read-only root from this project. Preserve schema version 1 and all BNN behavior.

If representative complete safe envelopes exceed current limits, report exact measured UTF-8 sizes and proposed deterministic projection or bounded server limit change. Do not silently change server limits.

## Milestones

Use and refine these fixed outcome boundaries:

- M0 — Bootstrap: exact enrollment/Git/authority, charter, tool audit, risks, design and accepted implementation plan.
- M1 — Toolchain shell: official version pins, Gradle wrapper, minimal Compose app, test/lint/build baseline.
- M2 — Capture: NotificationListenerService, app picker, max-ten filter, source config, safe Bundle/envelope serializer, raw-text strategy, local immutable capture.
- M3 — Reliable transport: Keystore secret, atomic versioned JSON configuration, Room outbox, HTTPS client, retry/backoff/retention, process/reboot recovery.
- M4 — Product UI: first-run setup, Status, Sources, Delivery, Settings, JSON editor/import/export, accessibility and secret-free diagnostics.
- M5 — Server compatibility: local Android-equivalent BNN POST, 202/UUID/committed-row proof using a dedicated test event; no contract change. Database-row proof is performed by an existing trusted runner from `D:\nfa-alerts-database` or a bounded handoff to its authority maintainer—never by injecting DB credentials into this project/IDE.
- M6 — APK/device: full Gradle/test/lint/static gates, debug APK at `artifacts/NFA-Notification-Collector.apk`, SHA-256, ADB install if available, operator Notification Access/token entry, real BNN test, second source only after approval.
- M7 — Closeout: operational docs, dependency/security review, independent review, Git commits/push if an approved remote exists, durable handoff and exact final report.

Each Milestone uses entry/exit gates, deterministic evidence, a restore point, tool audit, Context Fabric checkpoint when available, AgentCore memory event/handoff, and independent review.

## Required architecture/test details

The plan must explicitly cover:

- notification listener manifest/service lifecycle and callback-thread budget;
- installed-app visibility and the evidence-backed decision on `QUERY_ALL_PACKAGES` for private sideloading;
- system/user app classification and 10-selection state;
- safe recursive Bundle/Parcelable/CharSequence/action/RemoteInput serialization with binary/size protection;
- configurable raw-text candidates with unchanged output and all candidates preserved;
- versioned config validation with JSON-path errors, last-known-good atomic save, migration, SAF import/export, no secret;
- direct Android Keystore design using current official guidance;
- Room entities/DAOs/migrations/outbox state machine and crash recovery;
- immediate delivery plus WorkManager durable scheduling, bounded jittered backoff, status classification, no dedupe;
- endpoint profiles with no silent untrusted failover;
- bounded structured diagnostics and retention;
- Compose UI/accessibility and guided setup;
- unit, Room, Android, Compose, serialization, security, retry, process-restart, lint, dependency, APK and live-server tests;
- a local agent-performance ledger recording stalls, drift, hallucinations, retries and strong review outcomes.
- final operational documents at exact paths: `docs/INSTALL-SAMSUNG.md`, `docs/CONFIGURATION.md`, `docs/INGEST-CONTRACT.md`, and `docs/TROUBLESHOOTING.md`.

## Team and tool rule

Use bounded subagents and Morph under the installed `cheap-worker-orchestration` policy. Codex retains architecture, context, live mutations, final verification, Git integration and completion claims.

- Parallelize independent read-only investigations and different-file edits only.
- Every nontrivial plan receives an independent critique before edits.
- Every substantial diff receives a fresh independent review.
- Do not accept worker claims without running the commands yourself.
- Record any underperformance or drift in the project performance ledger.
- Never send credentials, captured notification content, or unrelated private context to external workers.

## Stop only for material authority/input

Stop for:

- missing exact enrollment or project identity mismatch;
- unrelated existing work/package-ID conflict;
- permanent server/schema change or wire-limit decision;
- broad privilege unsupported by current official docs;
- any secret exposure path or unavailable required credential;
- destructive database/history action;
- new signing secret;
- operator-only phone interactions;
- ADB/Tailscale absence only when reaching their specific live acceptance rows.

Do not stop for routine architecture, naming, UI, test or implementation decisions already bounded here.

## Git and artifact contract

- Preserve inherited files and unrelated work.
- Commit coherent Milestone checkpoints on the current project branch.
- The repository currently has no approved remote. Do not invent one; report local commits until the operator supplies it.
- Never commit secrets, signing material, `local.properties`, captured notifications, Room databases, APK signing keys, build caches or generated IDE state.
- Produce at minimum `artifacts/NFA-Notification-Collector.apk` and a SHA-256 record after all build gates pass.

## Final report contract

Report evidence for:

1. architecture and modules;
2. repository/package ID;
3. Android permissions and current-doc justification;
4. fields/envelope captured and bounded projection policy;
5. configuration schema/import/export;
6. Keystore secret implementation;
7. outbox/retry/retention behavior;
8. exact gateway contract and server compatibility;
9. app selection/system toggle/max-ten behavior;
10. all test/lint/build/dependency results;
11. APK absolute path and SHA-256;
12. ADB install and Notification Access status;
13. real BNN and second-source results, only when actually tested;
14. current Tailscale endpoint status;
15. Git status, branch, commits and remote state;
16. worker performance ledger;
17. actual blockers and next safe action.

Never claim device, BNN, second-source, Tailscale, signed-release, or phone-to-PC success from unit tests or files alone.

Begin now with the read-only M0 Plan-mode stage.
