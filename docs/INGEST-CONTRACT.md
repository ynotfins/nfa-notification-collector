# NFA Ingest Contract — Android Client View

Verified against `D:\nfa-alerts-database` and live state on 2026-08-17.

## Boundary

Android never connects to PostgreSQL. It sends HTTPS requests to the existing gateway.

- Permanent route: `POST /v1/ingest/alerts`
- Local PC-only URL: `http://127.0.0.1:8787/v1/ingest/alerts`
- Expected private phone URL: `https://chaoscentral.tailb71e7e.ts.net/v1/ingest/alerts`
- Do not use `127.0.0.1` from Android; that is the phone itself.
- `PHONE_TAILSCALE_ENDPOINT_READY = NO` at bootstrap. Do not claim otherwise without a real phone test.

## Required headers

```text
Content-Type: application/json
Authorization: Bearer <device-specific token>
X-NFA-Schema-Version: 1
```

The bearer is a 43-character opaque base64url token entered in the app UI. It is stored only through the Android secure-secret design. Never use `NFA_INGEST_DB_PASSWORD` or any PostgreSQL credential on Android.

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

Rules:

- `schemaVersion` is exactly `1` and matches the header.
- `source` is currently exactly `bnn` in both the live TypeScript validator and PostgreSQL constraints/function.
- `deviceId` is 1–128 characters matching `[A-Za-z0-9._:-]` and must match the credential binding.
- `capturedAt` is omitted/null or offset-aware ISO-8601.
- `rawText` is required; empty is allowed; U+0000 is forbidden.
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

| Result | Collector action |
|---|---|
| `202` | Mark SENT only after validating `accepted`, UUID `ingestId`, and UTC `receivedAt` |
| Network/timeout | Retry with bounded exponential backoff and jitter |
| `429` | Retry later; the current gateway does not send `Retry-After` |
| `503` | Retry later; commit or database outcome was not confirmed |
| `401` | Pause delivery until token/config changes; do not retry forever |
| `400`, `413`, `415` | Quarantine as permanent/configuration failure; preserve local row |

Duplicates are retained. A stable `clientEventId` belongs in metadata for provenance, but it is not a server idempotency key; a response-loss retry can create another server row.

## Multi-source decision gate

The requested app picker supports up to ten applications, but the live top-level source contract is BNN-only. Before a second source is sent live, obtain operator approval for a bounded backward-compatible gateway/database migration. Recommended proposal:

- retain schema version 1 and the same envelope;
- accept 1–128 lowercase source characters matching `[a-z0-9][a-z0-9._:-]{0,127}`;
- pass the validated source into the commit function instead of hardcoding `bnn`;
- preserve all existing BNN behavior, hashes, append-only triggers, authentication, and least privileges;
- deploy a new immutable gateway release and test old/new payloads.

This repository must not apply that server change itself.
