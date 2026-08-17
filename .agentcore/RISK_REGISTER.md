# Risk Register — NFA Notification Collector

| ID | Risk | Probability | Impact | Mitigation | Owner | Status | Approval/evidence |
|---|---|---|---|---|---|---|---|
| R1 | Bearer leaks through source/logs/config/export/ADB | medium | critical | Keystore UI entry, redaction/security tests, no ADB args | security owner | open | |
| R2 | Callback work blocks or loses notifications | medium | high | minimal snapshot + transactional Room insert + stress tests | capture owner | open | |
| R3 | Custom extra/Parcelable crashes serializer | high | high | per-key bounded serializer/type fallback/fixtures | capture owner | open | |
| R4 | Useful envelope exceeds gateway limits | medium | high | measurements, full local retention, deterministic projection, stop gate | transport owner | open | |
| R5 | Multi-source behavior breaks BNN-only contract | high | high | BNN first; non-BNN `BLOCKED_CONTRACT`; separate approval | authority maintainer | open | |
| R6 | `QUERY_ALL_PACKAGES` is unjustified | medium | medium | official-doc decision and private-sideload justification | Android owner | open | |
| R7 | Samsung background restrictions delay delivery | medium | high | listener + Room + WorkManager + guided device setup | reliability owner | open | |
| R8 | Response-loss retry creates duplicate server rows | medium | low | stable clientEventId provenance; duplicates intentional | operator | accepted | `docs/input/OPERATOR_PRODUCT_GOAL_2026-08-17.md` §15 and `D:\github\nfa-platform\DATABASE.md` §8 |
| R9 | JDK/Gradle/AGP mismatch | high | medium | explicit JDK 17/toolchain pin and wrapper | build owner | open | |
| R10 | No device/Tailscale during build | high | medium | complete offline APK; block only live rows | validation owner | open | |
| R11 | Legacy code imports stale APIs/package conflicts | medium | high | mine read-only behavior, never copy build topology blindly | architecture owner | open | |
| R12 | Signing secret unavailable | high | low | debug APK baseline, signed build conditional | release owner | open | |
| R13 | Captured content leaks through backup/screenshot/log/data extraction | medium | high | `allowBackup=false` or approved encrypted backup policy; FLAG_SECURE on secret/full-envelope screens where appropriate; redacted bounded logs; device tests | security owner | open | |

Any future `accepted` risk requires explicit approver and evidence.
