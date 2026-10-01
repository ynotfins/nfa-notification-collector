# NFA Ingest Contract — Android Client View

Authority: `D:\github\nfa-platform\DATABASE.md` §10 and `D:\github\nfa-platform\scripts\ingest-gateway.ts`.
If this file drifts, **nfa-platform wins**. Sheets export schemas are not the phone POST body.

## Why this collector exists

Never miss BNN notifications. Persist every selected notification in Room first. Drain ordered HTTPS posts to the PC over Tailscale. The phone never parses, dedupes, geocodes, or routes — the server does.

## Boundary

Android never connects to PostgreSQL. It sends HTTPS requests to the existing gateway.

- Permanent route: `POST /v1/ingest/alerts`
- Local PC-only URL: `http://127.0.0.1:8787/v1/ingest/alerts`
- Expected private phone URL: `https://chaoscentral.tailb71e7e.ts.net/v1/ingest/alerts`
- Do not use `127.0.0.1` from Android; that is the phone itself.

## Required headers

```text
Content-Type: application/json
Authorization: Bearer <device-specific token>
X-NFA-Schema-Version: 1
```

The bearer is entered in the app UI and stored only through the Android Keystore-backed design. Never use database credentials on Android.

## Current schema-v1 body

```json
{
  "schemaVersion": 1,
  "source": "bnn",
  "deviceId": "nfa-primary-phone",
  "capturedAt": "2026-08-17T07:57:10.755Z",
  "rawText": "exact exposed notification text",
  "metadata": {}
}
```

Rules (aligned to live gateway):

- `schemaVersion` is exactly `1` and matches the header.
- `source` is currently exactly `bnn` (BNN-only). Non-BNN stays local `BLOCKED_CONTRACT` — never fake `source=bnn`.
- `deviceId` is 1–128 characters matching `[A-Za-z0-9._:-]` and must match the credential binding.
- `capturedAt` is omitted/null or offset-aware ISO-8601.
- `rawText` is required. **Empty / whitespace-only is invalid → HTTP 400 quarantine.** Unresolved template variables like `{not_text_big}` also → 400.
- `metadata` is omitted or a JSON object.

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

The collector keeps the full safe notification envelope locally. Its wire projection must be deterministic, bounded, and contain explicit omission/truncation markers. Do not silently drop the local original.

## Delivery result policy

| Result | Collector action | Operator label |
|---|---|---|
| `202` | Mark SENT only after validating `accepted`, UUID `ingestId`, and UTC `receivedAt` | Done |
| Network/timeout | Retry with bounded exponential backoff and jitter | Will retry |
| `429` | Retry later; current gateway does not send `Retry-After` | Will retry |
| `503` | Retry later; commit or database outcome was not confirmed | Will retry |
| `401` | Pause delivery until token/config changes | Will retry (token/device) |
| `400`, `413`, `415` | Quarantine as permanent failure; preserve local row | Needs fix |

Duplicates are retained. A stable `clientEventId` belongs in metadata for provenance, but it is not a server idempotency key.

## Daily contract sync

The collector reloads the published contract about once per 24 hours (idle-friendly). Preferred publish path:

`D:\github\nfa-platform\contracts\ingest\phone-alerts.contract.json`

Until that file exists, the daily check re-reads DATABASE.md §10 + ingest-gateway rules against this document and the bundled snapshot. New contract versions apply on next app start / next daily tick. In-flight outbox rows keep the `contractVersion` stamped at insert time — no mid-send hot-swap.

## Multi-source decision gate

The app picker supports up to ten applications, but live transport is BNN-only until nfa-platform expands allowed sources. This repository must not change the server contract.
