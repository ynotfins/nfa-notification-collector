# Samsung One UI — operator surface (S26 Ultra)

**Captured:** 2026-10-02 (operator + prior repo evidence)  
**Confidence:** High for “History is not an app API”; Medium for exact menu labels (OEM strings change by One UI build).

## Battery / background

Typical paths (confirm on device before soak claims):

- **Settings → Battery / Device care → Battery → Background usage limits → Never sleeping apps** — add Collector, MacroDroid (backup), and source apps such as BNN.
- **App info → Battery → Unrestricted** for `com.nfaalerts.collector`.
- Avoid putting the collector into sleeping / deep sleeping lists.

## Notification Access

- **Settings → Notifications → Notification access** (or Advanced) — enable NFA Notification Collector.
- Type filters: Silent / Alerting / Conversations / Ongoing — operator verified Silent already enabled; dump showed no narrowed `NotificationListenerFilter` for the collector at capture time.

## Notification History

- Operator proof only. Compare counts manually as a **secondary** check. Do not scrape this screen. Shade catch-up is the degraded 3000 ms rule in [01-notification-listener.md](01-notification-listener.md), not a connected 60-second poll and not a history reader.
- Primary soak oracle (Phase 5): `logcat -b events` notification_enqueue / notification_canceled (see findings doc). Never commit those logs.

## Competing listeners / modifiers

Dump showed MacroDroid, OpenClaw, AlertSheets, MobileClaw, Buzzkill among allowed/installed listeners. Buzzkill can modify notifications. Prior WIP reduced competitors; dump later showed several re-enabled (~14:12 on 2026-10-02). Treat competition as operational risk, not proof of OS non-delivery by itself.
