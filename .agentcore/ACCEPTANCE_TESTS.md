# Acceptance Test Matrix — NFA Notification Collector

| ID | Milestone | Description | Expected evidence | Status | Evidence path |
|---|---|---|---|---|---|
| AT-M0-1 | M0 | Exact enrollment/root/Git and governance validate | boundary/Git/schema outputs | pending | |
| AT-M0-2 | M0 | Operator request/product input preserved and classified | hash + authority review | passed | `.agentcore/evidence/bootstrap-evidence.json`; `.agentcore/evidence/documentation-guard-2026-08-17.json` |
| AT-M0-3 | M0 | Official dependency/API docs checkpoint | Arabold success or recorded failure plus dated official-primary sources | pending | `.agentcore/docs/DOCS_INDEX.md` |
| AT-M0-4 | M0 | Design/spec and detailed plan independently accepted | review + operator approval | pending | |
| AT-M1-1 | M1 | Gradle/JDK/AGP/SDK shell passes tests/lint/build | command evidence | pending | |
| AT-M2-1 | M2 | Listener manifest/status works | unit/Android/device evidence | pending | |
| AT-M2-2 | M2 | User/system picker and max ten | tests/UI evidence | pending | |
| AT-M2-3 | M2 | Serializer preserves fields and survives unknown values | fixture matrix | pending | |
| AT-M2-4 | M2 | Raw text unchanged/candidates retained | Unicode/multiline fixtures | pending | |
| AT-M2-5 | M2 | Non-BNN remains local `BLOCKED_CONTRACT` before approval | state/UI/no-send tests | pending | |
| AT-M3-1 | M3 | Bearer Keystore-backed and absent from all prohibited surfaces | tests/scans/device policy | pending | |
| AT-M3-2 | M3 | Config validates/migrates/imports/exports atomically without secret | tests | pending | |
| AT-M3-3 | M3 | Room outbox persists before send and recovers process/reboot | DB/migration/restart tests | pending | |
| AT-M3-4 | M3 | Status/retry/backoff matrix exact; duplicates distinct | fake-clock/transport tests | pending | |
| AT-M3-5 | M3 | Backup/data-extraction/screenshot/log policies protect secret/content | manifest/UI/log/device evidence | pending | |
| AT-M4-1 | M4 | Setup and four UI areas accessible/functional | Compose tests/screenshots | pending | |
| AT-M5-1 | M5 | BNN-equivalent request gets 202 and committed-row trusted proof | response + database-owner handoff | pending | |
| AT-M5-2 | M5 | Payload fits limits with explicit projection markers | UTF-8 measurements | pending | |
| AT-M6-1 | M6 | Full tests/lint/static/dependency/build gates pass | command logs | pending | |
| AT-M6-2 | M6 | Debug APK path/hash verified | artifact + SHA-256 | pending | |
| AT-M6-3 | M6 | APK installed on exact Samsung when connected | ADB evidence | pending | |
| AT-M6-4 | M6 | Real BNN captured locally and committed remotely | phone/local/server comparison | pending | |
| AT-M6-5 | M6 | Second source passes only after server approval | decision + evidence | pending | |
| AT-M7-1 | M7 | Operational docs, Git, ledger and handoff complete | final review/commits | pending | |

Device/Tailscale rows may be `blocked_external`; they prevent corresponding live claims but not offline APK completion.
