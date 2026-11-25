# Handoff — shade hygiene for Android's 50-notification cap (2026-11-24)

**Branch:** `goal/shade-hygiene` (based on the previously unmerged `goal/phase3-reliability-standing` at `86c871c`)
**PR:** https://github.com/ynotfins/nfa-notification-collector/pull/8
**APK:** `app/build/outputs/apk/debug/app-debug.apk`
**SHA-256:** `60f5b304cffb454668d19da124f172a8a20fb8f8773224d80b83a873d6b3a7af`
**Version:** 0.2.0 (versionCode 2)

## Root cause

Android allows one app at most 50 active notifications. BNN posts one notification per
alert (channel `jobs`, unique tag, `AUTO_CANCEL`) and never cancels old ones. At 50,
`NotificationManagerService` rejects every new post before any listener sees it:

```
E/NotificationService: Package has already posted or enqueued 50 notifications.
Not showing more. package=us.bnn.newsapp
```

The collector was healthy throughout (listener bound, outbox 100% SENT/202). The
interim `cmd notification snooze` of the 44 oldest BNN notifications restored capture
immediately but refills.

## What changed

- **Shade policy** (`config/CollectorConfig.kt`): new `shade.keepNewestPerPackage`
  (1–30, default 10), validated and normalized for older configs.
- **Planner/controller** (`capture/ShadeHygiene.kt`): pure planner + single-flight
  controller. Routine dismissal only for `SENT` rows older than the newest N; cap
  guard at 40 dismisses persisted rows down to N (SENT first); group summaries are
  ignored and never counted; non-clearable notifications are never dismissed;
  nothing is dismissed without a Room row; non-selected packages are untouched.
- **Room lookup** (`data/CaptureDaos.kt`): `deliveryStatesForPackage` reads
  key/postTime -> delivery state; `deliveryStatesForPackage` and
  `captureCountByTagPrefix` support the proof test.
- **Wiring** (`NfaCollectorApp.kt`, `capture/NfaNotificationListenerService.kt`,
  `delivery/DeliveryCoordinator.kt`): sweep on every post, on reconcile, and after a
  successful `markSent`; `cancelNotification` via the bound listener; 250 ms settle
  before the recount that decides whether to alert.
- **Operator alert** (`ShadeAlertNotifier.kt`): high-priority local notification when
  a selected package is still >=45 after the guard ran; cleared when resolved.
- **Status UI** (`ui/*`): Home "Shade hygiene" tile and Delivery "Capture health"
  lines (dismissals, cap guards, largest selected app, at-risk packages).
- **Diagnostics** (`diagnostics/DiagnosticSanitizer.kt`, `data/CaptureDaos.kt`):
  `SHADE_DISMISSED`, `SHADE_CAP_GUARD`, `SHADE_ALERT` added; selected-source and
  shade events keep their own 2,000-row protected retention; non-selected
  `ALLOWLIST_REJECT` is aggregated (one row per 5 minutes with `queue=count`).
- **Bounded dispatch** (`capture/NotificationCaptureProcessor.kt`):
  `DurableCaptureDispatcher` now wraps the listener-thread `runBlocking` write in a
  5 s `withTimeoutOrNull` and records `DISPATCH_TIMEOUT`, so a stalled Room write
  cannot stall every later capture.
- **Version:** 0.2.0 (versionCode 2).
- **Adopted phase3** (`86c871c`): durable save-before-return, drop ledger, ranking
  snapshot, sensitive-content disposition, capture-health UI, soak-oracle scripts.
  A formatting-only commit makes that branch pass ktlint. This PR therefore lands the
  build that was already on the phone, not a regression.
- **Test dependencies:** explicit `androidx.test.espresso:espresso-core:3.7.0`
  (Compose still pulls 3.5.0 transitively, which is broken on Android 16).
- **Lint:** version-guarded the phase3 `Ranking` API calls and documented the
  intentional unbounded partial wake lock (released in `onDestroy`).

## Verification

- `ktlintCheck testDebugUnitTest lintDebug assembleDebug`: **BUILD SUCCESSFUL**.
- `ShadeHygieneTest`: 12/12. Covers dismissal only after SENT, newest-N ordering,
  cap guard at 40/45, group summaries, non-selected packages, no Room row,
  non-clearable, SENT-before-persisted, and the alert recount.
- Instrumentation on SM-S948U: **78/78 OK** via `adb shell am instrument`.
  `ShadeHygieneInstrumentedTest` posts 60 selected-source notifications: all 60 in
  Room, max shade count <= 40, no OS rejection.
- Live device: 19 BNN rows, all `SENT`/202 with UUID ingestIds. Brand-new live
  alerts reached 202 in **620 ms** and **698 ms** (post -> Room -> 202); catch-up
  imports reached 202 in 210–1149 ms. `capturePath='SHADE'` rows exist (one alert
  missed during an instrumentation restart was recovered and delivered). BNN shade
  settled at exactly 10 alerts + 1 summary. Zero `already posted or enqueued 50`
  lines for BNN in the current buffer.

## Test-runner incident (important)

`./gradlew connectedDebugAndroidTest` made the AGP/UTP driver **uninstall**
`com.nfaalerts.collector`, deleting Room and the Keystore bearer. See
`.agentcore/evidence/shade-hygiene-incident-2026-11-24.txt`. Recovery: `adb install -r`
0.2.0, operator re-granted Notification Access and battery exemption, re-entered the
bearer, and re-selected BNN. No undelivered rows were lost (the outbox was 100%
SENT/202 before the incident). **Do not run `connectedDebugAndroidTest` on the
primary phone.** Use `adb install -r` + `am instrument`.

## Device-test side effects

- `connectedDebugAndroidTest` uninstalls the app (above).
- The settings import/export test previously restored approved defaults instead of
  the real config, which wiped the operator's BNN selection (`"sources":[]`) and
  silently stopped capture. It now backs up and restores the real config. Always
  re-check Sources after a device test run.
- The diagnostics export test inserts metadata-only rows into the real database and
  now uses a unique id per run.

## Rollback

1. `adb install -r -d <previous app-debug.apk>` (versionCode 1 / 0.1.0). Never
   uninstall, clear data, or force-stop the collector.
2. The shade policy is additive; older builds ignore `shade` and keep the old
   (no-dismissal) behavior.
3. The incident evidence records the exact uninstall/reinstall sequence.

## Still unverified

- 24-hour soak with zero BNN cap rejections has not elapsed.
- Locked-screen overnight idle proof remains open (pre-existing M7 gate).
- Uninstall/clear-data is the only event that loses settings/token; the operator
  accepted re-entry after an uninstall. Normal restarts, reboots, and
  `adb install -r` preserve the config, BNN selection, and Keystore bearer.

## Out of scope (report only)

- `com.nfaalerts.nfa_supe` had 50 NotificationRecord lines on the same phone
  (27 un-dismissed alert notifications reported earlier). The same 50-cap risk
  applies. File for `nfa-platform`; this repo does not edit it.
- Gateway/server contracts and MacroDroid repair remain unchanged.
