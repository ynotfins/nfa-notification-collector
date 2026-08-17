# Troubleshooting

## Setup state

- Notification Access missing: open the app's settings control and grant manually.
- No sources: select BNN first; maximum ten.
- Token missing/401: pause delivery and update the secure token; never inspect it through logs/ADB.
- Non-BNN `BLOCKED_CONTRACT`: expected until the separately approved multi-source gateway release exists.

## Delivery status

- `202`: validate response and mark SENT.
- `400`: payload/schema/config failure; quarantine local event.
- `401`: credential/device mismatch; pause until configuration changes.
- `413`: wire projection too large; quarantine and inspect measured fields/truncation markers.
- `415`: client content-type defect; quarantine.
- `429`: retry with bounded backoff/jitter; no `Retry-After` is currently supplied.
- `503`, timeout or no network: transient; retry without deleting the outbox row.

## Endpoint

Android must not use PC loopback `127.0.0.1`. The expected private URL is `https://chaoscentral.tailb71e7e.ts.net`; it was not live-verified at bootstrap. Do not disable TLS verification. Do not use Funnel or a public tunnel.

## Listener/background reliability

Show the app's listener/access/battery/network/queue timestamps before collecting logs. Samsung settings changes are manual. WorkManager can defer work under constraints; queued Room rows remain the delivery authority.

## Safe diagnostics

Diagnostics may contain timestamps, package/source, event UUID, state, attempt count, HTTP status and non-secret error codes. They must not contain bearer/ciphertext/keys, complete private notification content by default, request headers, exported JSON secrets, DB credentials or raw stack data that embeds payloads.

## ADB/build

Use SDK ADB 37 from the path recorded in `ANDROID_TOOLCHAIN_BASELINE.md`. Resolve JDK explicitly; do not mix PATH Java 21 with an AGP configuration requiring JDK 17. No connected device blocks only device tests, not unit/lint/build/APK production.
