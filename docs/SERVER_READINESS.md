# Server Readiness Snapshot

Captured: 2026-08-17. Mutable truth remains `D:\NFA-Database-Control\DATABASE_STATE.md` and live source under `D:\nfa-alerts-database`.

## Ready

- PostgreSQL 18.4 at loopback `127.0.0.1:55433`.
- Capture database `nfa_ingest_capture`.
- Gateway scheduled task `\NFA\AlertsIngestGateway` and immutable release `0.1.0-8792f908df7e9ab7` were locally healthy.
- Local route `http://127.0.0.1:8787/v1/ingest/alerts` returned committed 202 responses.
- Device `nfa-primary-phone` is enabled with one active credential.
- The plaintext bearer exists only in Windows User variable `NFA_INGEST_DEVICE_BEARER_CURRENT`; it must be manually entered on Android and is never copied into this repository.
- Runtime DB role is least privilege; Android needs no database role, HBA line, grant, or password.
- Append-only, duplicate, rate-limit, rotation/revocation, logging-redaction, export, and isolation tests have passed according to the state authority.

## Not ready / decision required

- Tailscale phone reachability has not been proven; the expected private URL is not operational evidence.
- Top-level `source` is BNN-only. Second-source delivery requires the explicit server amendment described in `INGEST-CONTRACT.md`.
- The gateway has no GET health endpoint. An authenticated POST verification creates an append-only capture and must be labelled accordingly.

## Database adjustment result for this bootstrap

No database schema, role, permission, HBA, or credential change is required for the first BNN-compatible collector build. Do not grant Android direct database access. The only future database change is the separately approved multi-source migration, if the operator accepts it.
