# NFA Notification Collector

Native Android utility (`com.nfaalerts.collector`) that captures selected app notifications on the Samsung Galaxy S26 Ultra, persists them in a Room outbox, and delivers a bounded **schema-v1** projection to the NFA ingest gateway over Tailscale HTTPS.

## Current status (2026-10-01)

- AgentCore project key: `nfa-notification-collector`
- Repository: `D:\github\nfa-notification-collector`
- Application ID: `com.nfaalerts.collector` — **installable Android app exists** (not Milestone 0)
- Device: Samsung Galaxy S26 Ultra `R3GL605J0AH` (Android 16)
- Ingest route: `POST /v1/ingest/alerts`
- Phone URL: `https://chaoscentral.tailb71e7e.ts.net/v1/ingest/alerts`
- Loopback (PC only): `http://127.0.0.1:8787/v1/ingest/alerts`
- Default device ID: `nfa-primary-phone` (must match credential binding)
- Live top-level `source`: `bnn` only; other selected apps stay local `BLOCKED_CONTRACT`

## Contract authority (do not invent a second format)

1. **Published dynamic contract (poll ≈ daily):**  
   `D:\github\nfa-platform\contracts\ingest\phone-alerts.contract.json`
2. **Durable prose authority:** `D:\github\nfa-platform\DATABASE.md` §10–12  
3. **Live validator:** `D:\github\nfa-platform\scripts\ingest-gateway.ts`  
4. **This repo mirror:** [`docs/INGEST-CONTRACT.md`](docs/INGEST-CONTRACT.md) — must stay aligned; **nfa-platform wins on drift**

Not phone ingest: `contracts/data-exports/sheets-alert-feed.schema.json` (Sheets export only).

## Boundary

Phone = capture + durable transport only. No BNN parse, geocode, dedupe, county, or entitlement logic on device.

Listener scope = Android **status-bar** notifications. BNN’s in-app Incidents list can show rows that never become shade notifications — those cannot be captured until BNN posts them.

## Operational docs

- `docs/INSTALL-SAMSUNG.md`
- `docs/CONFIGURATION.md`
- `docs/INGEST-CONTRACT.md`
- `docs/TROUBLESHOOTING.md`
- `docs/SECURITY-AND-DATA-HANDLING.md`
- `docs/DECISIONS_AND_STOP_GATES.md`
- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/AUTHORITY_AND_REFERENCE_MAP.md`

Operator product input (historical): `docs/input/OPERATOR_PRODUCT_GOAL_2026-08-17.md` — lower authority than live code and nfa-platform contract.
