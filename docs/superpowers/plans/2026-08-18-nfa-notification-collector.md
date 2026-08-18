# NFA Notification Collector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task. Every behavior change follows red-green-refactor and every task receives independent review.

**Goal:** Build, verify, and package a native Android notification collector that preserves immutable local envelopes and reliably sends only schema-v1 BNN events.

**Architecture:** One Android application module uses a real notification listener, application-owned capture dispatcher, canonical safe serializer, Room capture/outbox database, Android Keystore bearer store, database-driven WorkManager drain, deterministic bounded wire projection, and Compose UI. Server/database authority remains external and unchanged.

**Tech Stack:** Kotlin, AGP 9.2.1, Gradle 9.4.1, JDK 17, Android API 36, Compose Material 3, Lifecycle 2.10.0, Room 3, WorkManager, coroutines/Flow, kotlinx JSON, OkHttp, Android Keystore AES-GCM.

**Spec:** `docs/superpowers/specs/2026-08-18-nfa-notification-collector-design.md`

## Global Constraints

- Application ID is exactly `com.nfaalerts.collector`; app label is `NFA Notification Collector`.
- Only `D:\github\nfa-notification-collector` is writable.
- No alert parsing, deduplication, direct database access, Accessibility Service, polling, cleartext operational endpoint, trust-all TLS, public tunnel, silent failover, CRM, or signing-secret creation.
- Top-level schema-v1 source remains `bnn`; non-BNN events remain local `BLOCKED_CONTRACT`.
- No production code is written before its failing test is observed, except generated/configuration scaffolding.
- Existing managed documentation uses documentation guard/maintainer governance. Generated AgentCore projections are never hand edited.
- Lifecycle version is pinned to 2.10.0; 2.11.0 is excluded.
- Each milestone ends with deterministic tests, independent review, hygiene scan, rollback evidence, local commit, memory/handoff checkpoint, and tool audit. Do not push the existing unapproved remote.

## Task 0: Close M0 planning gate

**Files:** this plan, its spec, `.agentcore/evidence/agent-performance-ledger.json`, `.agentcore/evidence/m0-plan-acceptance-2026-08-18.json`.

- [ ] Run documentation guard over the actual spec and plan; correct every blocking finding.
- [ ] Write `.agentcore/evidence/m0-plan-acceptance-2026-08-18.json` with `schema_version`, UTC capture time, project key/root/branch/HEAD, enrollment result, signed session/startup booleans and session id, Arabold error code only, gateway test total/pass/fail, SDK/JDK/ADB version strings, connected-device count, Context Fabric disposition/reason, remote presence/approval boolean, guard verdict, independent-review verdict, and non-secret worker route/status records. Include no environment values, bearer, notification content, DB identifiers beyond documented database names, or hidden reasoning.
- [ ] Parse the M0 evidence JSON and assert every named field exists with its documented string/boolean/integer/object type; run the sanitized active-secret comparison and reject any matched value without printing it.
- [ ] Run JSON/YAML/placeholder/path/secret/junk/Git checks.
- [ ] Record the plan approval and M0 outcome through AgentCore memory/checklist governance.
- [ ] Commit only the accepted M0 files with `docs: approve Android collector design and plan`.

## Task 1: Reproducible Android shell

**Files:** root Gradle files, wrapper, version catalog, `.editorconfig`, `scripts/Invoke-GradleJdk17.ps1`, `scripts/Test-RepositoryHygiene.ps1`, `app/build.gradle.kts`, main/debug manifests, backup/extraction/network resources, `NfaCollectorApp.kt`, `MainActivity.kt`, theme, smoke and manifest-security contract tests.

- [ ] Add a smoke test that fails because the app identity/readiness shell is absent.
- [ ] Add exact supported pins and AGP built-in Kotlin configuration; no dynamic dependency versions.
- [ ] Add the listener and backup/network contract to the main manifest; add `QUERY_ALL_PACKAGES` only to `app/src/debug/AndroidManifest.xml` so an unapproved release variant cannot silently inherit the broad permission.
- [ ] Create `app/src/test/java/com/nfaalerts/collector/security/ManifestSecurityContractTest.kt`. It reads `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml`, and `app/src/main/res/xml/data_extraction_rules.xml` and asserts: main application has `android:allowBackup="false"`, `android:fullBackupContent="@xml/backup_rules"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, and `android:usesCleartextTraffic="false"`; the debug manifest contains exactly one `android.permission.QUERY_ALL_PACKAGES`; the main manifest contains none; legacy rules contain excludes for `database`, `sharedpref`, `file`, `root`, and `external`; API-31+ rules contain both `cloud-backup` and `device-transfer`, each excluding `database`, `sharedpref`, `file`, `root`, and `external`.
- [ ] Implement the same contract in `scripts/Test-RepositoryHygiene.ps1`: enumerate all `AndroidManifest.xml` files, reject `QUERY_ALL_PACKAGES` unless the normalized path is exactly `app/src/debug/AndroidManifest.xml`, parse the main manifest and both XML resources, collect safe error identifiers, print only PASS or path/error labels, and exit nonzero if any assertion fails. Also reject tracked `.env*`, `local.properties`, keystores, Room databases, notification captures, APK/AAB files, Gradle/IDE caches, or active known-secret values without printing matched values.
- [ ] Implement the minimal Compose shell and manual `AppContainer` composition root.
- [ ] Verify JDK 17, ktlint, JVM tests, lint, and two clean debug builds.
- [ ] Review and commit `build: add reproducible Android shell`.

## Task 2: Capture, selection, and immutable envelope

**Files:** `model/`, `capture/`, `config/` selection files, initial `data/` entities/DAO/database/schema exports, JVM and instrumentation tests.

- [ ] Test source selection, max-ten enforcement, system visibility, and persistence contracts before implementation.
- [ ] Test raw-text selection and exact candidate preservation before implementation.
- [ ] Test canonical serializer fixtures including Unicode, large/unknown/cyclic/binary/action/message values and per-key failures before implementation.
- [ ] Test BNN initial PENDING versus non-BNN BLOCKED_CONTRACT persistence before implementation.
- [ ] Implement immutable types, installed-app repository, allowlist snapshot, listener dispatch, serializer, and one capture/outbox transaction.
- [ ] Prove the callback path performs no JSON/Room/network work and record emulator timing.
- [ ] Review and commit `feat: capture selected notifications`.

## Task 3: Secure config, Room state machine, and transport

**Files:** `config/`, `security/`, remaining `data/`, `delivery/`, `diagnostics/`, tests and Room schemas.

- [ ] Test typed config validation, JSON-path errors, v0 migration, AtomicFile failure recovery, SAF payload rules, and secret exclusion.
- [ ] Test Keystore AES-GCM save/load/replace/clear/invalidation and backup rules.
- [ ] Test Room migrations, atomic due-row claim, stale lease recovery, transition legality, and retention tombstones.
- [ ] Test deterministic projection against every server byte/depth/key/array/string limit and raw-text quarantine.
- [ ] Test every HTTP result, malformed 202, transient jitter bounds, missing bearer, duplicate retry, and logging redaction.
- [ ] Implement minimal config, secret, database, projector, client, retry policy, coordinator, WorkManager drain, and diagnostics needed to pass.
- [ ] Review and commit `feat: add secure durable delivery`.

## Task 4: Guided Compose product UI

**Files:** `ui/setup/`, `ui/status/`, `ui/sources/`, `ui/delivery/`, `ui/settings/`, navigation/shared components/view models, Compose tests.

- [ ] Add failing tests for readiness, setup navigation, source picker, 10/11 state, delivery immutability, endpoint/token/config behavior, accessibility, and restoration.
- [ ] Implement guided setup and Status/Sources/Delivery/Settings using existing repositories and flows.
- [ ] Apply and test `FLAG_SECURE` only during token entry.
- [ ] Keep previews redacted, full envelopes view-only behind a warning, and diagnostics export content/secret safe.
- [ ] Run Compose/accessibility tests and visual review with synthetic fixtures only.
- [ ] Review and commit `feat: add collector setup and diagnostics UI`.

## Task 5: Existing schema-v1 compatibility

**Files:** collector-owned local compatibility runner, sanitized evidence, tests; no database-repository edits.

- [ ] Re-run read-only gateway unit tests and require 42/42.
- [ ] Test the compatibility runner with a fake endpoint and sentinel bearer that must not appear in output.
- [ ] Send one labeled synthetic BNN event to loopback using the bearer only from the Windows User environment.
- [ ] Validate response shape and capture non-secret sizes/hashes/UUID/time.
- [ ] Obtain append-only committed-row proof through the database owner/trusted runner without exposing DB credentials.
- [ ] Review and commit `test: prove schema-v1 gateway compatibility`.

## Task 6: APK and conditional device acceptance

**Files:** generated APK/hash outside Git, sanitized build/emulator/device evidence, no signing secrets.

- [ ] Run clean ktlint, unit, lint, API-36 emulator instrumentation, Compose, Room, Keystore, process-restart, dependency, hygiene, and debug-assembly gates.
- [ ] Copy the verified APK to `artifacts/NFA-Notification-Collector.apk` and write its SHA-256 record.
- [ ] Recheck SDK-owned ADB. If no authorized Samsung exists, mark only physical rows blocked.
- [ ] If present, record device facts, discover BNN, install/update without data clearing, then pause for manual Notification Access/token/battery steps.
- [ ] Run real BNN and phone-to-PC comparison only when operator steps and private HTTPS are available. Never run second-source delivery without separate approval.
- [ ] Review and commit source/evidence only with `build: produce verified collector APK`.

## Task 7: Operational closeout

**Files:** README, INSTALL-SAMSUNG, CONFIGURATION, INGEST-CONTRACT, TROUBLESHOOTING, SECURITY-AND-DATA-HANDLING, final evidence/handoff/ledger.

- [ ] Reconcile each operational document to verified behavior through the documentation maintainer and guard.
- [ ] Run the full verification suite, dependency review, secret/junk/build-artifact scan, Git diff/status review, and final independent product/security/code review.
- [ ] Record exact APK path/hash, offline results, device/server/Tailscale truth, local commits, unapproved remote, worker ledger, blockers, rollback, and next safe action.
- [ ] Build and verify the AgentCore handoff; close the governed session only if every non-blocked outcome is durable.
- [ ] Commit `docs: complete collector operations handoff`; do not push.

## Standard verification commands

```powershell
.\scripts\Invoke-GradleJdk17.ps1 --version
.\scripts\Invoke-GradleJdk17.ps1 ktlintCheck testDebugUnitTest lintDebug assembleDebug
.\scripts\Invoke-GradleJdk17.ps1 clean ktlintCheck testDebugUnitTest lintDebug connectedDebugAndroidTest assembleDebug dependencies
& .\scripts\Test-RepositoryHygiene.ps1
if ($LASTEXITCODE -ne 0) { throw "Repository hygiene gate failed with exit code $LASTEXITCODE" }
npm test  # from D:\nfa-alerts-database\gateway, read-only
```

## Rollback

Use timestamped backups before existing managed-file changes and `git revert` for milestone commits. Never reset, clean inherited work, delete captured server history, clear Android app data, uninstall over a pending queue, mutate the gateway/database from this project, or touch live PGDATA/deployed runtime.
