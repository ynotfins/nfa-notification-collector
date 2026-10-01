# Install on Samsung Galaxy S26 Ultra

Measured device truth (2026-10-01, serial `R3GL605J0AH`):

- Model: SM-S948U (Galaxy S26 Ultra), Android 16 / One UI
- Package installed: `com.nfaalerts.collector` versionName `0.1.0`
- Debug APK baseline: `artifacts/NFA-Notification-Collector.apk`
- BNN package discovered on device: `us.bnn.newsapp`
- Notification Access is granted for the collector listener
- Tailscale HTTPS host `chaoscentral.tailb71e7e.ts.net` is reachable from the phone

This document is the operator install + reliability checklist. Do not claim locked-screen overnight acceptance until that test has fresh device evidence.

## Build artifact contract

- Required debug artifact: `artifacts/NFA-Notification-Collector.apk`
- Required package ID: `com.nfaalerts.collector`
- Record APK SHA-256 before installation.
- A signed internal/release APK is produced only when an approved signing configuration already exists; never create or commit signing secrets.

## Device discovery

Use only the SDK-owned ADB for one acceptance run:

`C:\Users\ynotf\AppData\Local\Android\Sdk\platform-tools\adb.exe`

Record authorization, model, product, Android release/API, security patch and installed package version. If no/unauthorized device is present, stop only device acceptance; do not claim installation.

Never pass the ingest bearer in ADB commands, intents, shell history or logcat filters.

## Operator setup after installation

1. Open NFA Notification Collector.
2. Open Notification Access settings from the guided setup and grant access manually.
3. Apply the One UI reliability stack below for **both** collector and BNN.
4. Set device ID `nfa-primary-phone`.
5. Enter the bearer manually into the secure token field. It must not be displayed afterward.
6. Select BNN from the device-installed app list; discover its package rather than trusting historical evidence.
7. Confirm the active endpoint is the Tailscale HTTPS profile.
8. Run the app's authenticated verification with clear disclosure that it creates an append-only test capture.

BNN is the first real notification test. A second app is tested only after the server multi-source amendment is approved and deployed.

## One UI / Android 16 reliability stack (S26 Ultra)

Verify these after every reboot. Record before/after with Settings screenshots or ADB dumps when accepting a Milestone.

### A) `com.nfaalerts.collector` (must keep listening)

1. **Notification access** — Settings → Notifications → Advanced settings → Notification access → NFA Notification Collector → On.
2. **Battery unrestricted** — Settings → Apps → NFA Notification Collector → Battery → Unrestricted.
3. **Never sleeping** — Settings → Battery and device care → Battery → Background usage limits:
   - Remove collector from Sleeping apps and Deep sleeping apps.
   - Add collector to Never sleeping apps / Never auto sleeping apps.
4. **Unused-app controls** — Settings → Apps → NFA Notification Collector → Permissions → disable auto-remove / pause unused app if present.
5. **Background data** — Settings → Apps → NFA Notification Collector → Mobile data → Allow background data; exempt from Data saver.
6. **Ongoing reliability notification** — leave the collector foreground service notification enabled; it is intentional.
7. **Do not force-stop** the collector. Force-stop disables listeners until the user opens the app again.

### B) `us.bnn.newsapp` (must keep posting)

1. Battery → Unrestricted.
2. Remove from Sleeping / Deep sleeping; add to Never sleeping apps.
3. Lock screen notifications enabled for BNN (Settings → Notifications → lock screen → show content / allow BNN).
4. BNN channel importance must remain high enough to post (not blocked).
5. Allow background data / exempt from Data saver.

### Reboot check

After reboot, confirm without opening the collector UI first:

- Notification access still On
- Collector reliability foreground notification returns
- Listener reconnects (`requestRebind` + boot receiver)
- Next BNN notification creates a local outbox row within seconds

## Acceptance proof required

Claim phone-to-PC success only with all of:

1. Screen off/locked ≥15 minutes, every new BNN **notification** creates a local outbox row within seconds.
2. Each row reaches ingest HTTP 202 over Tailscale HTTPS, preserving order and duplicates.
3. After airplane-mode gap, reconnect catch-up delivers missed rows in original outbox order.
4. After reboot, listener reconnects without requiring the operator to reopen the app, or the exact OS limitation is documented.

Important boundary: BNN Incidents UI entries are not proof that Android posted a notification. Collector authenticity is measured against `us.bnn.newsapp` status-bar notifications, not the in-app incident list alone.

## Update without data loss

Use an APK with the same application ID/signing identity. Before update, confirm no schema migration is destructive and queued events remain in Room. Do not clear app data, uninstall, or use `adb pm clear` when pending events/configuration must survive.
