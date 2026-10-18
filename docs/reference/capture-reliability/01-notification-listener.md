# NotificationListenerService — capture-relevant facts

**Fetched:** 2026-10-02  
**Primary:** https://developer.android.com/reference/android/service/notification/NotificationListenerService  
**Ranking:** https://developer.android.com/reference/android/service/notification/NotificationListenerService.Ranking

## Callbacks that matter

- Prefer `onNotificationPosted(StatusBarNotification, RankingMap)` so ranking (importance, suppressed visual effects, ambient, last audible alert, channel) is available for diagnostics.
- The no-Ranking overload exists; this app currently uses it (gap called out in the audit).
- After `onListenerDisconnected()`, only `requestRebind(ComponentName)` is safe; no further events until reconnect.
- `getActiveNotifications()` is valid after `onListenerConnected()` and is the supported catch-up surface for **still-active** shade rows. Dismissed / History-only rows are not available here.
- This app’s live path is immediate `onNotificationPosted`. When the network is down and the listener is still connected, shade catch-up reads `getActiveNotifications()` every 3000 ms (`ShadeReconcilePace.DEGRADED_INTERVAL_MS`). When the listener is disconnected, Android will not allow a shade read; the reliability service asks for a reconnect every 3000 ms, and the app reads the shade once as soon as the listener is back. The same one-time shade read runs when the network returns. While the listener is connected and the network is up, the app does not poll the shade. The periodic loop starts in `onListenerConnected` and is cancelled in `onListenerDisconnected`.
- Saved rows record `captured_notifications.capturePath`: `LIVE`, `SHADE`, or `UNKNOWN` (`UNKNOWN` is the Room migration default for older rows). Durable listener diagnostics are `LISTENER_CONNECTED` and `LISTENER_DISCONNECTED`.
- `delivery_outbox` drain is oldest-first: `ORDER BY captured_notifications.postTimeEpochMillis, rowid`.

## Type filters

Manifest meta-data:

- `android.service.notification.default_filter_types` — pipe-separated: `conversations|alerting|silent|ongoing`
- Flags: `FLAG_FILTER_TYPE_CONVERSATIONS`, `ALERTING`, `SILENT`, `ONGOING`

This collector already declares all four types in [`AndroidManifest.xml`](../../../app/src/main/AndroidManifest.xml). OEM or Settings can still narrow granted types at runtime; the app must detect that.

## Autogroup / summaries

System autogroup summaries commonly carry `FLAG_GROUP_SUMMARY` and `FLAG_LOCAL_ONLY`. Skipping those summaries is correct; **child** notifications under `g:Aggregate_*` remain separate `StatusBarNotification` instances and must still be delivered to listeners if the OS posts them.

## Implications for this project

1. Quiet / non-heads-up presentation does not by itself mean NLS will not receive the post.
2. Catch-up via `getActiveNotifications()` cannot reconstruct Notification History.
3. RankingMap is for diagnostics and policy context, not a substitute for durable persistence.
