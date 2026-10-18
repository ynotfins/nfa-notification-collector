# MacroDroid parallel capture — 2026-10-02

## Role

MacroDroid remains a **parallel** BNN capture path until cutover. `com.nfaalerts.collector` is the product primary (NotificationListenerService + Room). The Kotlin app must not depend on MacroDroid.

## Device actions already applied

- Deviceidle whitelist: collector, MacroDroid, BNN
- App standby inactive=false for those three
- Background app-ops allow for collector + MacroDroid + BNN
- Notification Access kept for collector + MacroDroid
- Disabled competing listeners: Affiora Claw, Pushbullet, OpenClaw, Link to Windows phone notifications, AlertSheets

## Macro expectations (operator)

Keep **only** these NFA macros enabled (duplicates OFF):

1. `NFA BNN Capture + Queue` — Notification Received `us.bnn.newsapp` → append queue → HTTP POST schema-v1
2. `NFA Queue Flush` — connectivity / periodic flush of `nfa_alert_queue.jsonl`

Disable: old `NFA BNN Capture`, `...Queue1`, TEST macros, and any sheets-only path if ingest is the authority.

## Secret hygiene

Macro exports on the phone have historically embedded a plaintext ingest bearer in the HTTP header. That is not allowed in Git. Prefer a MacroDroid **secure/global string** for the bearer (never commit the value). Rotate the gateway bearer if it was ever pasted into chat, screenshots, or Downloads macros that left the phone.

## Limits

Neither MacroDroid nor the collector can read Android **Notification history**. That Settings screen is proof the OS posted the alert; if history has BNN rows that never hit Room / `nfa_alerts_24h.jsonl`, both listeners missed the live callback. Dismissed history cannot be recovered.

Evidence 2026-10-01 morning (operator Notification history screenshot ~08:41–11:22 ET): history showed ~60 BNN rows; collector Room and MacroDroid history each had **18** in that window (~70% miss). Both stopped tracking the same stretch — not a MacroDroid-vs-collector split.
