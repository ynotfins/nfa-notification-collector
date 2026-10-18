# Handoff — shade catch-up pace (2026-10-18)

**Branch:** `goal/capture-miss-hardening`  
**PR:** https://github.com/ynotfins/nfa-notification-collector/pull/2  
**Commits in that PR:** `9518fb85d14769ee967f2c076e3bfea98550a987` (harden capture when the listener drops) and `554eb612a66452965fd4ff3750a0544982555a78` (3-second degraded shade catch-up, oldest-first delivery).

This handoff and the capture-reliability notes are the docs follow-up on PR 2. Phone-alerts contract **v1.2.0** Kotlin, tests, and `docs/contracts/phone-alerts.contract.snapshot.json` stay uncommitted. Do not mix that contract change into PR 2.

## Current capture behavior

- Live capture stays immediate in `onNotificationPosted`.
- When the network is down and the listener is still connected, shade catch-up reads `getActiveNotifications()` every 3000 ms (`ShadeReconcilePace.DEGRADED_INTERVAL_MS`). When the listener is disconnected, Android will not allow a shade read; the reliability service asks for a reconnect every 3000 ms, and the app reads the shade once as soon as the listener is back. The same one-time shade read runs when the network returns. While the listener is connected and the network is up, the app does not poll the shade.
- `delivery_outbox` drain stays oldest-first: `ORDER BY captured_notifications.postTimeEpochMillis, rowid`.
- The foreground reliability notice is posted once for package `com.nfaalerts.collector`, group `com.nfaalerts.collector.reliability`. It is not reposted every minute.
- Durable diagnostics: `LISTENER_CONNECTED`, `LISTENER_DISCONNECTED`, and `captured_notifications.capturePath` values `LIVE`, `SHADE`, `UNKNOWN` (`UNKNOWN` is the migration default for older rows).

## Device result (2026-10-18)

Samsung S26 serial `R3GL605J0AH`: debug APK installed, Room `user_version` 4. Active BNN shade had 49 child notifications and 0 were missing from Room.

Operator screenshots of a dense burst about 7:05–7:23 PM and a 2:37 PM card whose visible text was Big Fire are not in the current `logcat -b events` `notification_enqueue` buffer. Room has no row whose text is Big Fire. No missing-count is claimed for that older window. No in-app BNN scanner was added. Accessibility and Settings notification-history scraping remain a stop gate.

## Milestones

`.agentcore/checklists/state.json` marks M0–M6 passed. M7 stays pending until a live speed proof of the 3-second catch-up exists. Do not mark M7 passed. Do not start platform queue items NP-004 through NP-007.
