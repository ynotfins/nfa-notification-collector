# Install on Samsung Galaxy S26 Ultra

Bootstrap status: no APK exists yet and no ADB device was connected on 2026-08-17. This document defines the required installation flow; the implementation Milestones must update it with the verified APK path/version.

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
3. Review Samsung battery/background settings and choose the approved reliable mode manually.
4. Set device ID `nfa-primary-phone`.
5. Enter the bearer manually into the secure token field. It must not be displayed afterward.
6. Select BNN from the device-installed app list; discover its package rather than trusting historical evidence.
7. Configure the Tailscale HTTPS base URL only after reachability is available.
8. Run the app's authenticated verification with clear disclosure that it creates an append-only test capture.

BNN is the first real notification test. A second app is tested only after the server multi-source amendment is approved and deployed.

## Update without data loss

Use an APK with the same application ID/signing identity. Before update, confirm no schema migration is destructive and queued events remain in Room. Do not clear app data, uninstall, or use `adb pm clear` when pending events/configuration must survive.
