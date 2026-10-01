# Server Readiness Snapshot

Updated: 2026-10-01. Mutable operational counts remain in `D:\NFA-Database-Control\DATABASE_STATE.md` when attached. Phone ingest **contract** authority is nfa-platform (`contracts/ingest/phone-alerts.contract.json`, `DATABASE.md` §10, `scripts/ingest-gateway.ts`).

## Ready

- PostgreSQL capture database `nfa_ingest_capture` on the AgentCore/NFA cluster (loopback `127.0.0.1:55433` on CHAOSCENTRAL).
- Windows service `NFA-Platform-Ingest` serves `scripts/ingest-gateway.ts` on port **8787**.
- Local route `http://127.0.0.1:8787/v1/ingest/alerts` returns committed **202** responses for valid schema-v1 bodies.
- Loopback liveness: `GET http://127.0.0.1:8787/health` → `{"status":"ok"}` (**health exists**; older “no GET health” wording was wrong).
- Tailscale MagicDNS `https://chaoscentral.tailb71e7e.ts.net` is used by the phone for `POST /v1/ingest/alerts`; authenticated **202** is the acceptance proof (ICMP alone is not).
- Device credentials are issued/rotated via Windows env (`NFA_INGEST_DEVICE_BEARER_CURRENT` and successors); plaintext bearer is entered on Android only and never committed to this repository.
- Runtime DB role is least privilege; Android needs no database role, HBA line, grant, or password.
- Live top-level `source` is **BNN-only**.

## Not ready / still gated

- Overnight / ≥15 minute **locked-screen idle** capture proof for the native collector (shade → Room → 202) before claiming “never miss.”
- Airplane-mode catch-up and reboot reconnect proofs (session-dependent).
- Second-source live delivery (requires nfa-platform multi-source amendment). Non-BNN stays `BLOCKED_CONTRACT`.
- 100k soak: pace to rate limits; raise SENT/diagnostics retention for post-mortems (see operator soak requirements).

## Database adjustment result

No Android direct database access. Schema-v1 phone body remains the compatibility surface. Multi-source top-level `source` expansion is a separate nfa-platform change only.
