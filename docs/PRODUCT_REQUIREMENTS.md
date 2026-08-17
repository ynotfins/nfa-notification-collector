# Product Requirements — NFA Notification Collector

This is the normalized product contract derived from the operator input at `docs/input/OPERATOR_PRODUCT_GOAL_2026-08-17.md`, reconciled with verified server/toolchain state. If this summary conflicts with live authority or `AGENTS.md`, stop and resolve the conflict; do not silently follow the older input.

## Outcome

Produce an installable native Android utility for a Samsung Galaxy S26 Ultra that captures notifications from up to ten user-selected applications, persists every event locally, and reliably sends a bounded raw notification projection to the existing NFA ingest gateway.

Package/application ID: `com.nfaalerts.collector`

The phone performs capture and reliable transport only. Parsing and downstream business logic remain on the PC.

## Architecture

```text
Selected Android app notification
  -> NotificationListenerService callback
  -> minimal callback-thread snapshot
  -> complete safe local envelope + unique clientEventId
  -> Room append-only delivery row/outbox
  -> immediate coroutine attempt + WorkManager durable retry
  -> HTTPS POST /v1/ingest/alerts
  -> existing Fastify/PostgreSQL append-only capture
```

Use clear boundaries for notification serialization, configuration, secure secret storage, outbox state machine, transport, retry policy, installed-app discovery, and Compose UI.

## Functional requirements

### Notification access

- Declare a real `NotificationListenerService` with `BIND_NOTIFICATION_LISTENER_SERVICE` and the required service intent filter.
- Provide a prominent button to open Notification Access settings.
- Detect and display current access status.
- Never use Accessibility Service or polling as a substitute.
- Leave callback work quickly: snapshot/persist/dispatch; no network or expensive recursive conversion on the callback thread.

### Source selection

- List installed apps with icon, label, and package name.
- Search and sort by label.
- Show user-installed apps by default; provide `Show system apps` without clearing selected system packages.
- Persist selections and enforce exactly 10 maximum; block the 11th.
- The selected package set is the only listener allowlist.
- Each selected package has enabled state, source ID, and ordered raw-text field priority.
- BNN source ID is `bnn`; discover its actual package from the device. Historical evidence `us.bnn.newsapp` is not authority.
- Until a multi-source server amendment is separately approved, non-BNN selected notifications are captured and retained locally with delivery state `BLOCKED_CONTRACT`. They must not be sent under a false `bnn` source and must not loop retries.

### Notification envelope

Retain all safely exposed, serializable information locally, including:

- application package/label/UID and configured source;
- StatusBarNotification key, ID, tag, post time, package, group fields, clearable/ongoing, and safe user/profile metadata;
- notification timing/category/channel/flags/priority/visibility/number/color/group/sort/shortcut/timeout/local-only/badge/icon/ticker/summary/progress/chronometer fields;
- standard title/text/bigText/textLines/subtext/summary/info/conversation/messages/people/progress/media metadata;
- non-executable action and RemoteInput metadata;
- robust per-key Bundle serialization with type and bounded fallback for unfamiliar values;
- metadata—not binary payloads—for large bitmaps/icons or unsafe/executable objects.

One unsupported value must not discard the rest of the extras bundle. The local envelope is immutable after capture.

### Raw text

- Select `rawText` from configurable ordered candidates, defaulting to bigText, text, textLines, ticker.
- Preserve every candidate separately in local metadata.
- Preserve the exact code-point sequence Android exposes after safe `CharSequence` conversion.
- Do not normalize, parse, enrich, geocode, deduplicate, or infer.

### Configuration

- Versioned non-secret `collector-config.json` model.
- Defaults: device ID `nfa-primary-phone`, base URL `https://chaoscentral.tailb71e7e.ts.net`, path `/v1/ingest/alerts`.
- Editable endpoint profiles: Tailscale, optional explicitly configured LAN/Test, and Custom; one active, no silent failover.
- Advanced JSON editor with formatted text, validation and JSON-path errors, atomic last-known-good save, reset, import/export through Storage Access Framework, and schema migration.
- Export never includes the bearer.

### Secret storage

- Secure `Ingest Authentication Token` field.
- Use a current official Android Keystore-backed design; do not choose a deprecated convenience API without documentation proof.
- Never redisplay the token after save.
- Never log, diagnose, export, back up, commit, or pass it via ADB arguments.

### Durable delivery

- Assign a unique client event UUID to every matching posted event; do not deduplicate repeated Android notification keys or text.
- Persist before sending.
- States: `PENDING`, `SENDING`, `SENT`, `RETRY_WAIT`, `FAILED`/quarantined.
- Record attempt count, next retry, failure classification, HTTP status, server ingest UUID and receive time.
- `202` is the only success and is accepted only after response validation.
- Retry network failures, timeouts, `429`, and `503` with bounded exponential backoff and jitter.
- Pause on `401` until configuration changes.
- Quarantine `400`, `413`, and `415`; never delete the local original.
- Recover in-flight state after process death/reboot and schedule due work reliably.
- Bound sent/history/diagnostic retention while never deleting pending work silently.

### UI

Use Material 3 with a guided first run and four primary areas:

- Status: readiness, permission, endpoint, selected count, queue, last capture/send/error/listener state.
- Sources: searchable app picker, icons, `X / 10`, system toggle, per-source editor.
- Delivery: immutable captured rows, state/status/attempts, retry failed, safe detail view, secret-free diagnostics.
- Settings: endpoint profiles, device ID, secure bearer field, retry/retention settings, JSON config, import/export, permission and battery links.

The collector is green/ready only when notification access, valid endpoint, token, device ID, and at least one enabled source are configured.

### Reliability

- Show Notification Access, battery optimization status, network state, queue length, listener state, last capture/send/error.
- Provide settings intents justified by current official docs and Samsung behavior.
- Require standard TLS validation; no trust-all code or cleartext operational endpoint.
- Keep structured diagnostics bounded and secret-free.

## Verification requirements

- Unit tests: selection limit/filtering, system toggle, serializer edge cases, raw preservation, config validation/atomic last-good behavior, secret exclusion, outbox transitions, retry matrix, duplicates, retention.
- Android tests: Room migration/restart, Compose setup/source/delivery/settings flows, listener access status/intents, package visibility on target API.
- Build gates: Gradle wrapper, unit tests, lint, formatting/static analysis, dependency review, debug APK assembly, clean Git diff.
- Server compatibility: Android-equivalent BNN payload gets 202 and a committed unique row; this app test intentionally appends one row. The committed-row proof uses a trusted runner/handoff owned by `D:\nfa-alerts-database`; this project never reads DB passwords or performs direct SQL.
- Device acceptance: install via ADB when connected, operator grants Notification Access and enters token, BNN first, compare phone notification against local envelope and server row, then test a second source only after server multi-source approval.
- Artifact: copy a verified debug APK to `artifacts/NFA-Notification-Collector.apk` and record SHA-256. Signed release is conditional.

## Definition of done

The project is not done merely because it compiles. All non-device gates must pass, an APK must exist at an absolute path, and every external/device-only gap must be named accurately. Never claim Samsung/Tailscale/BNN/second-source success without live evidence.
