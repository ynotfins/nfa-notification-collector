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

Android must not use PC loopback `127.0.0.1`. The expected private URL is `https://chaoscentral.tailb71e7e.ts.net`. As of 2026-10-01 phones obtain authenticated HTTPS ingest `202` on that host; still prove each acceptance with **202**, not ping alone. Loopback ingest health on the PC is `GET http://127.0.0.1:8787/health` → `{"status":"ok"}`. Do not disable TLS verification. Do not use Funnel or a public tunnel.

Contract drift: if the phone payload disagrees with `D:\github\nfa-platform\contracts\ingest\phone-alerts.contract.json`, fix the phone (or request an nfa-platform contract bump) — do not invent a third shape.

## Queue truth

- **Sendable** = PENDING + SENDING + RETRY_WAIT + PAUSED_AUTH
- **Held** = BLOCKED_CONTRACT + QUARANTINED
- Home must never call Held rows a “send backlog.” Send now only drains sendable rows.
- Operator labels: Done / Sending / Will retry / Needs fix / Held

## Quarantine (HTTP 400/413/415)

Quarantined rows stay forever for evidence unless an explicit operator policy discards them later. Preview rejected rawText from the queue card. Empty rawText is invalid under the live gateway and quarantines locally before POST when possible.

## Listener/background reliability (One UI / Android 16)

Show the app's listener/access/battery/network/queue timestamps before collecting logs.

Measured checklist that kept capture alive on SM-S948U (`R3GL605J0AH`):

1. Notification access On for collector.
2. App battery = Unrestricted for collector and BNN.
3. Device care → Battery → Background usage limits: both apps removed from Sleeping/Deep sleeping and added to Never sleeping apps.
4. Unused-app auto sleep / auto permission revoke disabled for collector.
5. Background data allowed; Data saver exempt for collector and BNN.
6. Lock-screen notifications enabled for BNN.
7. Collector ongoing reliability notification left enabled.
8. After disconnect, collector calls `NotificationListenerService.requestRebind`; reliability service repeats rebind on a watchdog interval and boot/package-replaced receivers restart it.
9. On reconnect, collector reconciles `getActiveNotifications()` and imports only package/key/postTime identities not already in Room.

Samsung settings changes are manual. WorkManager can defer work under constraints; queued Room rows remain the delivery authority.

### Competing listeners

MacroDroid, Pushbullet, OpenClaw/MobileClaw, and `com.example.alertsheets` may also have Notification Access. They are diagnosis aids only. If another listener sees a BNN status-bar notification that collector does not, treat that as a collector defect. Do not make production depend on a third-party mirror.

### BNN UI versus notifications

BNN Incidents UI can list alerts that never appear as Android notifications. Collector can only capture what `us.bnn.newsapp` posts to the notification shade. Compare against active/history status-bar notifications, not the Incidents list alone.

## Safe diagnostics

Diagnostics may contain timestamps, package/source, event UUID, state, attempt count, HTTP status and non-secret error codes. They must not contain bearer/ciphertext/keys, complete private notification content by default, request headers, exported JSON secrets, DB credentials or raw stack data that embeds payloads.

## ADB/build

Use SDK ADB 37 from the path recorded in `ANDROID_TOOLCHAIN_BASELINE.md`. Resolve JDK explicitly; do not mix PATH Java 21 with an AGP configuration requiring JDK 17. No connected device blocks only device tests, not unit/lint/build/APK production.
