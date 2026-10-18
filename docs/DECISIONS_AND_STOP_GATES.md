# Decisions and Stop Gates

## Accepted bootstrap decisions

- Standalone native Android/Kotlin app; no Flutter for this collector.
- Application ID: `com.nfaalerts.collector`.
- Notification capture: `NotificationListenerService` only.
- UI: Jetpack Compose + Material 3.
- Durable delivery: Room outbox + WorkManager/coroutines selected only after official-doc verification.
- Secret: Android Keystore-backed, UI-entered bearer; never exported/plaintext persistence.
- Default device ID: `nfa-primary-phone`.
- First live source and acceptance: BNN with top-level `source = bnn`.
- Maximum selected applications: exactly 10.
- User-installed apps shown by default; system apps behind an explicit toggle.
- Full safe envelope retained locally; bounded deterministic projection transmitted.
- Duplicates preserved.
- Debug APK is the required baseline artifact. Release signing is conditional on an already-approved signing configuration.

## ANCHOR / anti-drift (hard locks)

These are decision anchors. Do not silently swap architecture:

1. **NotificationListenerService only** — never Accessibility Service, never polling as a substitute for live capture, never a Settings notification-history scraper, never an in-app BNN scanner. Live capture stays immediate in `onNotificationPosted`. When the network is down and the listener is still connected, shade catch-up reads `getActiveNotifications()` every 3000 ms (`ShadeReconcilePace.DEGRADED_INTERVAL_MS`). When the listener is disconnected, Android will not allow a shade read; the reliability service asks for a reconnect every 3000 ms, and the app reads the shade once as soon as the listener is back. The same one-time shade read runs when the network returns. While the listener is connected and the network is up, the app does not poll the shade.
2. **Outbox-first** — persist every selected notification locally before network delivery; never skip Room for “faster” live POST.
3. **Schema-v1 ingest only** — HTTPS `POST /v1/ingest/alerts`; Android never receives PostgreSQL credentials or talks to Postgres.
4. **No on-phone business logic** — no BNN parse, incident/county inference, geocode, CRM, or collapsing duplicates.
5. **Duplicates remain distinct** — every live listener callback is a new event; catch-up may skip only already-persisted package/key/postTime identities. `delivery_outbox` drain stays oldest-first: `ORDER BY captured_notifications.postTimeEpochMillis, rowid`. The foreground reliability notice is posted once for package `com.nfaalerts.collector`, group `com.nfaalerts.collector.reliability`, and is not reposted every minute. Durable diagnostics include `LISTENER_CONNECTED`, `LISTENER_DISCONNECTED`, and `captured_notifications.capturePath` values `LIVE`, `SHADE`, and `UNKNOWN` (`UNKNOWN` is the migration default for older rows).
6. **Max 10 sources; BNN-first** — until an approved multi-source server amendment exists, non-BNN selections stay local `BLOCKED_CONTRACT` and must not be falsely sent as `bnn`.
7. **Secrets stay on the Keystore path** — bearer is UI-entered only; never commit, log, export, print, or ADB-pass secrets.
8. **No success claim without locked-screen idle proof** — compile/APK/install success is not phone-to-PC acceptance. Require screen-off/locked idle evidence plus ingest `202` before declaring capture reliable.
9. **No permanent third-party mirror dependency** — MacroDroid may run as a **parallel backup** posting the same schema-v1 body until cutover, but production capture authority remains this collector. Pushbullet/OpenClaw/alertsheets are diagnosis-only and should stay disabled on the primary phone. Never scrape Settings → Notification history (no public API; violates NLS-only).

## Material stop gates

Stop and request operator direction for:

1. Any permanent gateway/schema-v1 change, including multi-source top-level `source` expansion.
2. Any measured representative envelope that cannot fit the current bounded wire contract without a product decision.
3. Broad Android privileges not justified by current official documentation.
4. Overwriting unrelated target contents or discovering a package-ID conflict.
5. Any plan that exposes the bearer through source, Git, logs, exported JSON, Room diagnostics, ADB arguments, or prompts.
6. New signing secrets or unavailable credentials.
7. Destructive server/database/history changes.
8. ADB unauthorized/missing for device-only acceptance, Notification Access/token entry, or Tailscale offline for phone-only acceptance. These block only corresponding live tests, not compilation/APK generation.

## Measured device status (2026-10-01)

- Samsung SM-S948U serial `R3GL605J0AH` is connected and has `com.nfaalerts.collector` 0.1.0 installed.
- Notification Access is enabled; reliability foreground service is running; battery whitelist includes collector and BNN.
- Tailscale host `chaoscentral.tailb71e7e.ts.net` answers ICMP from the phone.
- Inventory check: every currently active `us.bnn.newsapp` status-bar notification tag was already present in the local collector outbox/capture DB (0 misses versus active shade).
- Locked-screen ≥15 minute / overnight idle proof is still required before claiming “never miss” acceptance.
- BNN Incidents UI can show alerts that are not presently represented as status-bar notifications; those are outside NotificationListenerService capture until BNN posts them.

## Measured device status (2026-10-18)

- Samsung S26 serial `R3GL605J0AH`: debug APK installed; Room `user_version` 4.
- Active BNN shade had 49 child notifications and 0 were missing from Room.
- Operator screenshots of a dense burst about 7:05–7:23 PM and a 2:37 PM card whose visible text was Big Fire are not in the current `logcat -b events` `notification_enqueue` buffer. Room has no row whose text is Big Fire. No missing-count is claimed for that older window.
- No in-app BNN scanner was added. That remains a stop gate: Accessibility and notification-history scraping stay forbidden.
- Platform queue items NP-004 through NP-007 are not started from this note.

## Explicit non-goals

- No alert parsing, incident correlation, county logic, geocoding, deduplication, CRM, or Flutter application features.
- No direct PostgreSQL access from Android.
- No Accessibility Service, no Settings notification-history scraper, no in-app BNN scanner, no shade polling while the listener is connected and online, no trust-all TLS, public tunnel, Tailscale Funnel, or silent endpoint failover.
