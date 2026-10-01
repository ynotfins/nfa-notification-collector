# NFA Ingest Contract — Android Client View

**Authority order (highest wins):**

1. `D:\github\nfa-platform\contracts\ingest\phone-alerts.contract.json` (published; poll ≈ once per day)
2. `D:\github\nfa-platform\DATABASE.md` §10–12
3. `D:\github\nfa-platform\scripts\ingest-gateway.ts` (live HTTP validator)
4. This file (must not drift ahead of 1–3)

Verified against live gateway behavior 2026-10-01. Older references to `D:\nfa-alerts-database` as the only server root are historical; phone ingest source of truth for NFA is **nfa-platform**.

## Boundary

Android never connects to PostgreSQL. It sends HTTPS requests to the existing gateway.

- Permanent route: `POST /v1/ingest/alerts`
- Local PC-only URL: `http://127.0.0.1:8787/v1/ingest/alerts`
- Expected private phone URL: `https://chaoscentral.tailb71e7e.ts.net/v1/ingest/alerts`
- Do not use `127.0.0.1` from Android; that is the phone itself.
- `PHONE_TAILSCALE_ENDPOINT_READY = YES` for production capture traffic (2026-10-01). Re-verify after Tailscale/Serve outages. Acceptance remains authenticated HTTPS **202**, not ICMP alone.
- Loopback ingest liveness: `GET http://127.0.0.1:8787/health` → `{"status":"ok"}`.

## Required headers

```text
Content-Type: application/json
Authorization: Bearer <device-specific token>
X-NFA-Schema-Version: 1
```

The bearer is an opaque device token entered in the app UI (Keystore-backed). Never use `NFA_INGEST_DB_PASSWORD` or any PostgreSQL credential on Android.

## Current schema-v1 body

```json
{
  "schemaVersion": 1,
  "source": "bnn",
  "deviceId": "nfa-primary-phone",
  "capturedAt": "2026-10-01T18:24:00.311Z",
  "rawText": "exact exposed notification text",
  "metadata": {}
}
```

Rules:

- `schemaVersion` is exactly `1` and matches the header.
- `source` is currently exactly `bnn` in the live TypeScript validator / capture commit path.
- `deviceId` is 1–128 characters matching `[A-Za-z0-9._:-]` and must match the credential binding.
- `capturedAt` is omitted/null or offset-aware ISO-8601 (invalid → 400).
- `rawText` is required and **must be non-empty after trim**. Empty/whitespace → **400** (quarantine). Unresolved MacroDroid templates like `{not_text_big}` → **400**. U+0000 is forbidden.
- `metadata` is omitted or a JSON object (non-authoritative).

## Hard transport bounds

| Item | Limit |
|---|---:|
| Complete HTTP body | 262,144 bytes |
| `rawText` UTF-8 | 131,072 bytes |
| Serialized metadata UTF-8 | 32,768 bytes |
| Metadata depth | 6 |
| Total metadata keys | 128 |
| Keys in one object | 64 |
| Entries in one array | 128 |
| One metadata string UTF-8 | 8,192 bytes |
| Authenticated rate burst | 240 |
| Refill | 120/minute/device |

The collector keeps the full safe notification envelope locally. Its wire projection must be deterministic, bounded, and contain explicit omission/truncation markers. Do not silently drop the local original. Pace drains for large soaks; treat **429** as retryable.

## Delivery result policy

| Result | Collector action |
|---|---|
| `202` | Mark SENT only after validating `accepted`, UUID `ingestId`, and `receivedAt` (`attemptId` may also be present) |
| Network/timeout | Retry with bounded exponential backoff and jitter |
| `429` | Retry later; gateway may not send `Retry-After` |
| `503` | Retry later; commit or database outcome was not confirmed |
| `401` | Pause delivery until token/config changes; do not retry forever |
| `400`, `413`, `415` | Quarantine as permanent/configuration failure; preserve local row |

Duplicates are retained. A stable `clientEventId` belongs in metadata for provenance, but it is not a server idempotency key; a response-loss retry can create another server row.

## Capture scope (honest)

Only Android **status-bar** notifications delivered to `NotificationListenerService` are in scope. BNN’s in-app Incidents UI can show alerts that never become shade notifications — those are outside this contract until BNN posts them. NFA Chaser/Supe FCM “Alert update” shade items are **not** BNN originals.

## Multi-source decision gate

The app picker supports up to ten applications, but the live top-level source contract is BNN-only. Before a second source is sent live, obtain operator approval for a bounded backward-compatible gateway/database migration (owned by nfa-platform). Non-BNN selections stay local `BLOCKED_CONTRACT` and must never be forged as `source=bnn`.

This repository must not apply that server change itself.

## Daily contract sync

Reload `phone-alerts.contract.json` about once per 24 hours (and on app start). Stamp `contractVersion` on outbox rows. Do not hot-swap mid-send in a way that corrupts ordered drain. Faster changes arrive via operator/collector-agent prompt.
