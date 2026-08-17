# Configuration

## Non-secret JSON model

The implementation must define a versioned `collector-config.json` with at least:

```json
{
  "configVersion": 1,
  "deviceId": "nfa-primary-phone",
  "activeEndpointProfile": "tailscale",
  "endpointProfiles": {
    "tailscale": {
      "baseUrl": "https://chaoscentral.tailb71e7e.ts.net",
      "ingestPath": "/v1/ingest/alerts"
    }
  },
  "sources": [],
  "delivery": {
    "connectTimeoutMs": 15000,
    "maxAttempts": 12,
    "initialBackoffMs": 5000,
    "maxBackoffMs": 3600000
  },
  "diagnostics": {
    "retentionDays": 14
  }
}
```

Values are a design baseline, not dependency/source-server authorization. The Plan-mode spec defines exact validation ranges and migrations.

## Atomic last-known-good behavior

Validate complete input and return exact JSON-path errors before replacing current configuration. Write a temporary internal file, flush/close it, atomically replace the last-known-good file, then update reactive state. Invalid import/editor content never replaces the last-known-good configuration.

Import/export uses Android's Storage Access Framework. Export contains no bearer, key alias, ciphertext, notification payload, delivery rows or captured content.

## Secret configuration

The ingest bearer is not part of JSON. It is entered through a secure Settings field and encrypted/decrypted through an Android Keystore-backed key. Never redisplay, log, screenshot in tests, export, back up, include in diagnostics/Room rows, or pass through ADB.

## Source behavior

- Up to ten enabled package configurations.
- BNN top-level source remains `bnn`.
- Before server approval, non-BNN events are captured locally as `BLOCKED_CONTRACT` and are not transmitted/retried.
- Preserve ordered raw-text candidates and every candidate value locally.

## Backup and data extraction policy

The plan must explicitly choose and test Android backup/data-extraction rules. Default safe posture for this sensitive utility is to exclude the bearer, Room database, captured envelopes, queue, config containing private source selection, logs and diagnostics from cloud/device-transfer backup unless an encrypted operator-approved recovery design is implemented.

Use manifest backup/data-extraction rules appropriate to the selected min/target SDK and test them. Treat screenshots of the token field and full-envelope screens as sensitive; use `FLAG_SECURE` where the accepted UX/security design requires it. Never place captured content in clipboard/notifications without explicit action and warning.
