# Milestones — NFA Notification Collector

**Standard:** `D:\github\agentcore-control-plane\docs\agent-policy\MILESTONE_EXECUTION_STANDARD.md`
**Canonical checklist state:** `.agentcore/checklists/state.json`; Markdown checklists are projections of that JSON.

| ID | Name | Outcome | Status | File | Entry dependency | Exit evidence |
|---|---|---|---|---|---|---|
| M0 | Bootstrap and accepted plan | Exact identity/governance, authority/docs/tool checkpoints, accepted design and plan | in_progress | `milestones/M0-bootstrap.md` | enrollment + operator input | M0 acceptance matrix |
| M1 | Toolchain and compiling shell | Official pins, Gradle wrapper, minimal Compose shell and clean baseline | pending | `milestones/M1-toolchain.md` | M0 passed | build/test/lint evidence |
| M2 | Capture and source selection | Listener, picker, max-ten filter, safe serializer/raw strategy | pending | `milestones/M2-capture.md` | M1 passed | capture fixture/UI tests |
| M3 | Secure configuration and delivery | Keystore, atomic config, Room outbox, HTTPS retry/recovery/retention | pending | `milestones/M3-delivery.md` | M2 passed | security/DB/restart tests |
| M4 | Product UI and diagnostics | Guided setup and Status/Sources/Delivery/Settings UX | pending | `milestones/M4-ui.md` | M3 passed | Compose/accessibility tests |
| M5 | Local server compatibility | Android-equivalent BNN request gets 202 and trusted committed-row proof | pending | `milestones/M5-server.md` | M4 passed | response + authority-handoff evidence |
| M6 | APK and Samsung acceptance | Verified APK and every available device/BNN/second-source/Tailscale row | pending | `milestones/M6-apk-device.md` | M5 passed | artifact/device evidence |
| M7 | Closeout and handoff | Operational docs, independent review, Git/evidence/handoff complete | pending | `milestones/M7-closeout.md` | M6 non-device gates passed | final report/handoff |

## Rules

- Entry and exit gates are mandatory; no Milestone closes from narrative claims.
- Each Milestone records official documentation, tool audit, tests, rollback, Context Fabric when available, AgentCore memory/handoff, and independent review.
- Device/Tailscale absence may block only corresponding live acceptance rows; it does not excuse APK/test/build gates.
- Permanent server changes remain a separate owner/operator decision and are never smuggled into an Android Milestone.
