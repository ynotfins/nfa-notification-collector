# NFA Notification Collector Design

**Status:** Operator-approved implementation design
**Project:** `nfa-notification-collector`
**Repository:** `D:\github\nfa-notification-collector`
**Application ID:** `com.nfaalerts.collector`
**Target device:** Samsung Galaxy S26 Ultra

## Outcome and boundaries

Build a native Kotlin, Jetpack Compose, Material 3 Android application that captures posted notifications from at most ten explicitly selected packages through `NotificationListenerService`, persists every accepted capture before network delivery, and sends only schema-v1 BNN requests to the existing NFA HTTPS gateway.

Android performs capture and reliable transport only. It does not parse alerts, infer incidents or counties, geocode, correlate, deduplicate, implement CRM behavior, connect to PostgreSQL, receive database credentials, use Accessibility Service, poll notifications, disable TLS validation, or silently fail over endpoints.

The collector repository is the only write root. `D:\github\agentcore-control-plane` and `D:\nfa-alerts-database` are read-only references. Live PGDATA, the deployed gateway runtime, and the phone are outside normal write scope.

## Toolchain

- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, Build Tools `36.0.0`.
- Android Gradle Plugin `9.2.1`, Gradle `9.4.1`, external JDK 17, AGP built-in Kotlin, Compose compiler `2.3.10`, KSP `2.3.10`.
- Compose BOM `2026.06.00`, Activity Compose `1.13.0`, Lifecycle `2.11.0`, Navigation Compose `2.9.8`.
- Room 3 `3.0.1`, SQLite Framework `2.7.0`, WorkManager `2.11.2`.
- Coroutines `1.11.0`, kotlinx-serialization JSON `1.11.0`, OkHttp/MockWebServer `5.3.0`.
- Ktlint Gradle plugin `14.2.0`, ktlint `1.8.0`.

All Gradle execution goes through a repository script that validates `JAVA_HOME` resolves to JDK 17. PATH Java 21 is never trusted implicitly.

## Capture architecture

The main manifest declares one non-exported `NotificationListenerService` protected by `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` with the official listener action. The debug source-set manifest includes `QUERY_ALL_PACKAGES` only for the privately sideloaded arbitrary-installed-app picker. Release variants omit it and therefore cannot be represented as complete app-picker builds; any future internal/release or Play distribution must explicitly reopen and redesign/approve package visibility before it can be called functional.

`onNotificationPosted` performs only an immutable allowlist read, UUID/time creation, lightweight `StatusBarNotification` primitive extraction, and coroutine dispatch. Application label lookup, notification cloning, Bundle traversal, JSON generation, Room access, and network work occur off the main thread. Only posted callbacks create capture events; an updated notification with the same Android key is a distinct event.

The safe serializer produces canonical, key-sorted, type-tagged JSON. Strings preserve the code points exposed by Android. Known notification types, messaging values, people, actions, RemoteInputs, bundles, arrays, collections, icons, bitmaps, and URIs receive structured non-executable representations. Bitmap/icon/binary bytes and PendingIntents are never stored or transmitted. Unknown values preserve their key, runtime type, and a bounded safe representation. Per-key failures, cycles, omissions, and truncations are explicit.

Local safety ceilings are 2 MiB UTF-8 per envelope, depth 16, 4,096 nodes, 1,024 array entries, and 1 MiB per string. If a live event exceeds one of these limits, persist a minimal immutable limit envelope with the event/SBN identity, encountered JSON path, limit name, measured value, and original safe type; set the outbox to `QUARANTINED/LOCAL_ENVELOPE_LIMIT`; never send it. If any representative BNN fixture reaches this path, the active milestone stops for operator wire-limit review rather than accepting the serializer as complete.

Raw-text candidates are configurable and ordered. Defaults are big text, normal text, text lines, then ticker. The chosen text and every candidate remain locally preserved without parsing or normalization.

## Persistence

Room owns four tables:

- `captured_notifications`: immutable capture payload and hashes.
- `delivery_outbox`: mutable delivery state and lease/attempt/result fields.
- `capture_retention_tombstones`: permanent non-content audit created before an eligible SENT payload is removed.
- `diagnostic_events`: bounded structured operational events without bearer or full content.

Persistent delivery states are `PENDING`, `SENDING`, `RETRY_WAIT`, `PAUSED_AUTH`, `BLOCKED_CONTRACT`, `QUARANTINED`, and `SENT`.

One database transaction inserts the immutable capture and initial outbox row. A guarded SQL update claims one due row; an immediate coordinator and WorkManager cannot claim the same event. `SENDING` leases older than ten minutes are recovered after restart.

Only SENT payloads are automatically eligible for retention: 90 days, with the oldest committed rows removed when more than 10,000 remain. A tombstone containing only event/hash/server identifiers and the retention reason is written first. Every non-SENT payload requires explicit operator-confirmed purge.

## Configuration and secrets

`collector-config.json` version 1 contains device ID, one active HTTPS endpoint profile, endpoint profiles, up to ten source configurations, ordered raw-text candidates, timeout/backoff/retention values, and diagnostics settings. It never contains the bearer, ciphertext, notification data, or delivery rows.

Validation emits typed JSON-path errors. Unknown future versions are rejected. A deterministic legacy v0-to-v1 importer proves migration behavior. `AtomicFile.startWrite/finishWrite/failWrite` plus file-descriptor sync preserves the last known good config. SAF import/export includes non-secret configuration only.

The bearer is encrypted with a non-exportable Android Keystore AES-256/GCM key, randomized encryption, a new IV per save, and no user-authentication requirement because unattended delivery is required. Only versioned IV/ciphertext is stored in `noBackupFilesDir`. Key invalidation erases unusable ciphertext and requires manual re-entry. Token characters and transient Authorization values are never logged, diagnosed, exported, stored in Room, placed in ADB arguments, or redisplayed after save.

`FLAG_SECURE` applies only while token entry is visible. Other screens may be captured by the operator; delivery previews are redacted by default and full-envelope display requires an explicit privacy warning.

The manifest disables backup and provides both legacy and API-31+ exclusion rules. The canonical XML exclusion domains are `database`, `sharedpref`, `file`, `root`, and `external`; API-31+ rules apply all five to both `cloud-backup` and `device-transfer`. `ManifestSecurityContractTest` parses the source manifests and both rules documents and asserts the exact contract listed in the implementation plan. `Test-RepositoryHygiene.ps1` repeats the cross-file assertions, scans every manifest outside `app/src/debug` for `QUERY_ALL_PACKAGES`, emits only safe path/error labels, and exits 1 on any mismatch. Its caller must propagate every nonzero exit code by throwing, so the milestone/CI gate fails rather than continuing.

## Wire contract and projection

The only current request is schema version 1, top-level source `bnn`, device ID `nfa-primary-phone` by default, offset-aware `capturedAt`, exact `rawText`, and object metadata. Required headers are JSON content type, bearer Authorization, and `X-NFA-Schema-Version: 1`.

Non-BNN events are persisted as `BLOCKED_CONTRACT` and never enter the scheduler or HTTP client. A second source requires a separately approved backward-compatible server amendment owned by `D:\nfa-alerts-database`.

`rawText` is never truncated. Values above 131,072 UTF-8 bytes are locally quarantined as `WIRE_RAW_TEXT_LIMIT`. Metadata always includes the client event UUID, package, configured source, local envelope hash/size, notification identity, raw-candidate provenance, and projection version. Optional values are admitted by stable priority and then lexicographic JSON path. UTF-8-safe metadata truncation and omission record original/retained sizes, paths, counts, and marker overflow.

Before HTTP, the projector enforces metadata at most 32,768 UTF-8 bytes, depth 6, 128 total keys, 64 keys per object, 128 array entries, 8,192 bytes per string, raw text at most 131,072 bytes, and the complete body at most 262,144 bytes.

Only a validated `202` response with `accepted=true`, UUID `ingestId`, and offset-aware `receivedAt` becomes SENT. Network/timeout/429/503 becomes RETRY_WAIT. A 401 becomes PAUSED_AUTH until bearer or relevant configuration revision changes. 400/413/415, unexpected statuses, and malformed 202 bodies become QUARANTINED. Database `nextAttemptAt` controls exponential delay from 30 seconds to a six-hour cap with injectable jitter and no total transient-attempt limit.

Response loss can create a second append-only server row. This is accepted: `clientEventId` is provenance, not idempotency, and Android performs no deduplication.

## User experience

The application has guided setup and four primary areas: Status, Sources, Delivery, and Settings. Readiness is green only with notification access, valid HTTPS endpoint, saved bearer, valid device ID, and at least one enabled source.

The picker shows user apps by default, classifies system/updated-system apps with Android flags, retains hidden selected system packages, searches/sorts labels, and blocks selection eleven. BNN mapping requires explicit operator confirmation; historical package names are not trusted.

Delivery lists are redacted, immutable, and show state, attempts, safe error code, and server identifiers. Full local envelopes are view-only. Diagnostics answer listener/filter/persist/send/status/retry questions without bearer or private body export.

## Verification and external gates

Pure logic is tested on the JVM; Room, Keystore, listener, backup, and Compose behavior are tested on an existing API-36 emulator. MockWebServer verifies wire behavior. A local collector-owned runner performs one labeled synthetic BNN POST using the bearer only from the Windows User environment. Committed-row proof comes from the trusted database-project owner.

Physical Samsung installation, Notification Access, token entry, battery settings, actual BNN package, phone-to-PC Tailscale HTTPS, real BNN comparison, and any second source are reported only when observed. Debug signing is the only baseline; no signing secret is created.
