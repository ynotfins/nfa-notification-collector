# NFA Notification Collector

Bootstrap repository for a standalone native Android utility that captures notifications from user-selected applications and delivers an exact, bounded schema-v1 projection to the existing NFA ingest gateway.

## Current status

- AgentCore project key: `nfa-notification-collector`
- Repository: `D:\github\nfa-notification-collector`
- Phase: Milestone 0 bootstrap; no Android application code has been generated yet
- Application ID reserved by repository audit: `com.nfaalerts.collector`
- Device target: Samsung Galaxy S26 Ultra; no ADB device was connected during bootstrap
- Gateway: locally healthy at `http://127.0.0.1:8787/v1/ingest/alerts`
- Expected private phone route: `https://chaoscentral.tailb71e7e.ts.net/v1/ingest/alerts` (not yet live-verified)
- Default device ID: `nfa-primary-phone`

## Start the build

Open a new Codex task in **Plan mode** with these project folders:

1. `D:\github\nfa-notification-collector`
2. `D:\github\agentcore-control-plane`
3. `D:\nfa-alerts-database`

Attach:

- `D:\NFA-Database-Control\DATABASE_STATE.md`
- `D:\github\nfa-platform\DATABASE.md`

Paste the single prompt in [`CODEX_GOAL_BOOTSTRAP.md`](CODEX_GOAL_BOOTSTRAP.md).

The operator product goal is preserved verbatim at `docs/input/OPERATOR_PRODUCT_GOAL_2026-08-17.md`; it is product-input evidence, not higher authority than current code, live state, or the repository contract.

Operational documentation already scaffolded for the build:

- `docs/INSTALL-SAMSUNG.md`
- `docs/CONFIGURATION.md`
- `docs/INGEST-CONTRACT.md`
- `docs/TROUBLESHOOTING.md`
- `docs/SECURITY-AND-DATA-HANDLING.md`
