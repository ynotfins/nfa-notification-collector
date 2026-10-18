# Notification History ingest — blocked 2026-10-02

## Operator ask
Use Android Settings → Notification history as capture source because the UI shows more BNN posts than the collector outbox.

## Verdict
**Not implementable for a third-party app.** No public Android API exposes Notification History to `com.nfaalerts.collector`.

## Evidence
1. AOSP `NotificationManager` privileged surfaces that touch notification archives require `ACCESS_NOTIFICATIONS` / system APIs (`@SystemApi` / `@hide`). Play/sideload apps cannot hold that permission.
2. `cmd notification` exposes listener allow/disallow, list/get active keys, snooze — **no** history export subcommand for apps.
3. Product contract (`AGENTS.md`, `docs/PRODUCT_REQUIREMENTS.md`, `docs/DECISIONS_AND_STOP_GATES.md`): NotificationListenerService only; **no Accessibility Service** and **no polling** as a substitute. Scraping the Settings history UI would violate that stop gate and would be fragile OEM UI automation, not a contract API.
4. Device Room (`com.nfaalerts.collector`, pulled 2026-10-02): captures exist through **2026-10-01 14:24**, but **zero** captures in the **12:00** local hour while Notification History showed dense BNN posts in that window. History proves the system posted them; the gap is **missed listener capture**, not “Android never notified.”

## What history cannot do
Dismissed/history-only rows are **not** recoverable later via `getActiveNotifications()` or any allowed app API. Merge/install cannot backfill them from Settings history.

## Correct path
Keep hardening live `NotificationListenerService` + active-shade catch-up + reliability FGS so posts are persisted **at post time**. Investigate noon-hour miss separately; do not build history scrapers.
