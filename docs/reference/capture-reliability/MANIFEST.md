# Capture reliability docs pack — manifest

**Project:** `nfa-notification-collector`  
**Fetch date:** 2026-10-02  
**Device context:** Samsung SM-S948U (S26 Ultra), Android 16 / API 36, One UI  
**App compile/targetSdk:** 36 ([`app/build.gradle.kts`](../../../app/build.gradle.kts))

## Doc source policy

| Order | Source | Result this session |
|---|---|---|
| 1 | Arabold Docs via `agentcore-gateway` MCP | **Failed** — gateway namespace in `error` state during discovery; no memory/docs tools available |
| 2 | Official `developer.android.com` | **Used** (WebFetch + local citations) |
| 3 | Context7 bridge (`/websites/developer_android`) | Partial; NLS Ranking page fetched; some Android 16 behavior pages timed out |

Failures are recorded here so later phases can retry Arabold before pinning new APIs.

## Pack index

| File | Topic |
|---|---|
| [01-notification-listener.md](01-notification-listener.md) | NLS, RankingMap, type filters, active notifications, autogroup |
| [02-android16-notification-behavior.md](02-android16-notification-behavior.md) | API 36 delivery-adjacent behavior, quiet posts, sensitive content, History limits |
| [03-background-fgs-boot.md](03-background-fgs-boot.md) | FGS specialUse, Doze/standby, boot / package replaced |
| [04-samsung-one-ui.md](04-samsung-one-ui.md) | Battery / sleeping apps / Notification History as operator proof only |
| [05-jetpack-deps-in-use.md](05-jetpack-deps-in-use.md) | Libraries this app actually depends on |

## Current collector pace (2026-10-18)

Live capture stays immediate in `onNotificationPosted`. When the network is down and the listener is still connected, shade catch-up reads `getActiveNotifications()` every 3000 ms (`ShadeReconcilePace.DEGRADED_INTERVAL_MS`). When the listener is disconnected, Android will not allow a shade read; the reliability service asks for a reconnect every 3000 ms, and the app reads the shade once as soon as the listener is back. The same one-time shade read runs when the network returns. While the listener is connected and the network is up, the app does not poll the shade. Outbox drain is oldest-first (`ORDER BY captured_notifications.postTimeEpochMillis, rowid`). The reliability notice is posted once (package `com.nfaalerts.collector`, group `com.nfaalerts.collector.reliability`) and is not reposted every minute. Detail: [01-notification-listener.md](01-notification-listener.md). Handoff: [../../handoff/2026-10-18-shade-catch-up.md](../../handoff/2026-10-18-shade-catch-up.md).

## Non-goals for this pack

- No Flutter docs (app is native Kotlin).
- No Notification History public API claims (none exist for third-party apps).
- No notification content from device dumps.
