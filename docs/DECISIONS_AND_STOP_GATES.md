# Decisions and Stop Gates

## Accepted bootstrap decisions

- Standalone native Android/Kotlin app; no Flutter for this collector.
- Application ID: `com.nfaalerts.collector`.
- Notification capture: `NotificationListenerService` only.
- UI: Jetpack Compose + Material 3.
- Durable delivery: Room outbox + WorkManager/coroutines selected only after official-doc verification.
- Secret: Android Keystore-backed, UI-entered bearer; no exported/plaintext persistence.
- Default device ID: `nfa-primary-phone`.
- First live source and acceptance: BNN with top-level `source = bnn`.
- Maximum selected applications: exactly 10.
- User-installed apps shown by default; system apps behind an explicit toggle.
- Full safe envelope retained locally; bounded deterministic projection transmitted.
- Duplicates preserved.
- Debug APK is the required baseline artifact. Release signing is conditional on an already-approved signing configuration.

## Material stop gates

Stop and request operator direction for:

1. Any permanent gateway/schema-v1 change, including multi-source top-level `source` expansion.
2. Any measured representative envelope that cannot fit the current bounded wire contract without a product decision.
3. Broad Android privileges not justified by current official documentation.
4. Overwriting unrelated target contents or discovering a package-ID conflict.
5. Any plan that exposes the bearer through source, Git, logs, exported JSON, Room diagnostics, ADB arguments, or prompts.
6. New signing secrets or unavailable credentials.
7. Destructive server/database/history changes.
8. ADB unauthorized/missing for device-only acceptance, Notification Access/token entry, or Tailscale offline for phone-only acceptance. These block only corresponding live tests, not compilation/APK generation.

## Non-blocking current limitations

- No Samsung device is connected.
- Tailscale phone endpoint is not live-verified.
- No repository remote exists yet; commit locally, do not invent a remote.
- Arabold Docs returned SSE 404 during bootstrap; retry, then use official primary sources with citations if still unavailable.

## Explicit non-goals

- No alert parsing, incident correlation, county logic, geocoding, deduplication, CRM, or Flutter application features.
- No direct PostgreSQL access from Android.
- No Accessibility Service, notification polling, trust-all TLS, public tunnel, Tailscale Funnel, or silent endpoint failover.
