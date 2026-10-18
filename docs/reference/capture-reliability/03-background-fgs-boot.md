# Background, FGS, boot — capture reliability

**Fetched:** 2026-10-02  
**FGS background start:** https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

## What this app already uses

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` with subtype describing notification-listener reliability / outbox recovery.
- `RECEIVE_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` via `CollectorRestartReceiver`.
- WorkManager keep-alive (WIP) for bind/FGS watchdog without tying capture to network.

## Exemptions that help FGS / survival

Official exemptions include (non-exhaustive): user turns off battery optimizations for the app; `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` receivers; visible UI transitions. Exact One UI paths belong in install docs (Phase 3).

## Limits

- Battery unrestricted / never-sleeping does **not** guarantee every History row is delivered to NLS.
- Doze and App Standby can delay workers; they should not prevent an already-bound `NotificationListenerService` from receiving binder callbacks while connected.
- Direct boot (locked after reboot): listener behavior before first unlock needs explicit verification in soak; product must not claim coverage without that evidence.

## App standby / idle

ADB (no root) can show and adjust standby/idle for diagnosis (`dumpsys deviceidle`, `am get-inactive`, `appops`). Scripts for apply/verify are Phase 3 — not implemented in this stop-after-Phase-2 cut.
