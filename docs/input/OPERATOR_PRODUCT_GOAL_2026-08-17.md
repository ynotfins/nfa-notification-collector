# GOAL — BUILD NFA ANDROID NOTIFICATION COLLECTOR AND PRODUCE INSTALLABLE APK

Build a production-grade native Android notification collector for my Samsung Galaxy S26 Ultra.

This is a NEW standalone utility application whose only responsibility is:

**capture complete notifications from user-selected Android applications and reliably forward the raw notification data to the existing NFA ingestion gateway on my Windows PC.**

Do not build alert parsing, incident interpretation, county detection, BNN parsing, deduplication, geocoding, or business logic on the phone.

The PC/server owns all parsing and downstream processing.

---

# 1. READ EXISTING NFA AUTHORITY FIRST

Before writing code, inspect the current machine/project state and read the authoritative NFA database/ingestion documentation.

At minimum read:

`D:\github\nfa-platform\DATABASE.md`

and:

`D:\NFA-Database-Control\DATABASE_STATE.md`

Also inspect the deployed ingestion-gateway implementation/configuration/documentation under:

`D:\NFA-Database-Control`

and:

`I:\LocalApps\NFAAlerts`

where relevant.

DO NOT modify the NFA commercial application merely to build this collector.

DO NOT modify PostgreSQL infrastructure unnecessarily.

DO NOT create a second ingestion architecture.

The existing permanent phone contract is the starting authority.

---

# 2. EXISTING SERVER ARCHITECTURE

PostgreSQL already exists and has already been prepared for NFA.

Existing machine database:

- PostgreSQL 18.x
- host: `127.0.0.1`
- port: `55433`
- raw-capture database: `nfa_ingest_capture`

The Android phone MUST NOT connect directly to PostgreSQL.

Permanent path:

`Android notification`
→ `NFA Notification Collector`
→ `HTTPS`
→ `POST /v1/ingest/alerts`
→ existing Fastify capture gateway
→ `nfa_ingest_capture`
→ later PC parsing/canonicalization

Existing local route:

`http://127.0.0.1:8787/v1/ingest/alerts`

Expected private Tailscale route:

`https://chaoscentral.tailb71e7e.ts.net/v1/ingest/alerts`

The endpoint must be configurable in the Android app.

Do not hardcode the Tailscale hostname as an irreversible implementation assumption.

---

# 3. PRESERVE THE PERMANENT V1 CONTRACT

Existing conceptual schema:

```json
{
  "schemaVersion": 1,
  "source": "bnn",
  "deviceId": "nfa-primary-phone",
  "capturedAt": "ISO-8601 timestamp",
  "rawText": "exact original notification text",
  "metadata": {}
}
```

Required headers include:

```text
Content-Type: application/json
Authorization: Bearer <device-specific-ingest-key>
X-NFA-Schema-Version: 1
```

Successful committed capture:

`202 Accepted`

Before implementing multi-app support, inspect the actual deployed gateway validator and tests.

The app must remain backward-compatible with the existing BNN contract.

We now need the same collector to support notifications from multiple alert applications.

Do NOT break existing BNN ingestion.

If `source` already accepts arbitrary source identifiers, configure a source identifier per selected application.

If the current gateway limits `source` specifically to `bnn`, make only the smallest backward-compatible server-side extension needed to support additional notification-source identifiers.

Do not change the schema-v1 envelope shape unless absolutely required.

If changing the permanent contract would actually be necessary, STOP and ask me before doing it.

---

# 4. NEW ANDROID PROJECT

Create this as a standalone native Android project/repository.

Preferred default:

`D:\github\nfa-notification-collector`

Application name:

**NFA Notification Collector**

Preferred package/application ID:

`com.nfaalerts.collector`

Before writing:

1. verify the target directory does not contain unrelated work;
2. search for any existing collector project/package ID that should be reused;
3. inspect the installed Android SDK/toolchain;
4. inspect the target Samsung device through ADB if it is currently connected.

If an existing authoritative project conflicts with these defaults, stop before overwriting it.

Use:

- Kotlin
- Jetpack Compose
- Material 3
- strong typing
- coroutines/Flow
- Room for durable delivery queue/state where appropriate
- Android Keystore-backed secret storage
- current supported Android/Gradle/Kotlin APIs

No Flutter for this utility unless repository/device evidence provides a compelling technical reason. Notification capture is an Android-native responsibility.

---

# 5. DOCUMENTATION REQUIREMENT

Before selecting version-sensitive Android APIs or dependency versions:

1. use `arabold-docs` MCP first;
2. use current official Android/Jetpack documentation second;
3. never choose APIs or versions from model memory.

Specifically verify current documentation for:

- `NotificationListenerService`
- notification-listener permission/settings
- Android package visibility
- `QUERY_ALL_PACKAGES`
- current Samsung-target-compatible SDK behavior
- WorkManager/background execution
- battery optimization APIs
- Room
- Compose/Material 3
- Android Keystore
- current target/compile SDK requirements

Do not use stale examples.

---

# 6. CORE NOTIFICATION CAPTURE

Implement a real:

`NotificationListenerService`

with the appropriate Android service declaration and notification-listener binding permission.

The app must provide a prominent setup control that opens Android's Notification Access settings so I can grant the collector maximum legitimate notification access.

The app must detect whether notification-listener access is currently granted and show live status in the UI.

Do not use Accessibility Service as a substitute for NotificationListenerService.

Do not use polling.

Do not rely on MacroDroid.

---

# 7. APP SELECTION — MAXIMUM 10 SOURCES

The user must be able to select which installed Android applications are captured.

Requirements:

- list installed applications;
- show app icon;
- show human-readable app name;
- show package name;
- search/filter applications;
- sort cleanly by app name;
- persist selections across restarts;
- maximum exactly **10 selected applications**;
- clearly display `X / 10 selected`;
- prevent selection of an 11th application;
- allow apps to be deselected/replaced easily.

The selected package list is the ONLY package allowlist used by the listener.

Notifications from unselected packages must not be sent.

---

# 8. SYSTEM APPLICATION VISIBILITY

System applications should NOT clutter the normal list.

Default application picker:

**User-installed apps only**

Provide a clear UI control:

**Show system apps**

When enabled, also display system/updated-system applications.

When disabled, hide them again without clearing already-selected packages.

Correctly distinguish normal applications from Android system applications using current PackageManager/ApplicationInfo behavior.

Because this collector needs a comprehensive application picker, determine and implement the correct modern Android package-visibility configuration.

This utility is intended for private/internal sideloading on my own phone, not initially for Google Play distribution.

---

# 9. CAPTURE THE COMPLETE NOTIFICATION ENVELOPE

Do not parse alerts on Android.

For every notification posted by a selected application, capture as much safely serializable source information as Android exposes.

At minimum preserve:

## Source/application

- package name
- application label
- source mapping/configured source identifier
- Android UID where available

## StatusBarNotification

- notification key
- notification ID
- tag
- post time
- package
- group key
- override group key where available
- clearable
- ongoing
- user/profile information where safely serializable

## Notification fields

Capture applicable fields including:

- `when`
- category
- channel ID
- flags
- priority
- visibility
- number
- color
- group
- sort key
- shortcut ID
- timeout
- local-only state
- badge/icon metadata
- ticker text
- group-summary information
- progress fields
- chronometer fields

## Standard extras

Preserve all important Android notification extras, including when present:

- title
- titleBig
- text
- bigText
- textLines
- subText
- summaryText
- infoText
- conversation title
- messages
- people/person data
- picture/image metadata
- progress values
- media/session metadata that can be safely serialized

## Actions

Capture non-secret serializable action metadata such as:

- action title
- semantic action
- contextual/authentication flags where available
- RemoteInput metadata

Do NOT attempt to serialize executable `PendingIntent` objects.

## Raw extras

Create a robust recursive Bundle-to-JSON serializer.

For unknown serializable values:

- preserve their key;
- preserve a safe structured or string representation;
- preserve type information when useful.

Do not crash because an application adds an unfamiliar Parcelable or custom extra.

Do not silently discard the entire extras bundle because one field cannot be serialized.

Do not embed massive bitmap/icon binary blobs into the HTTP payload by default.

Preserve useful metadata for those objects instead.

---

# 10. RAW TEXT MUST REMAIN UNPARSED

The server expects `rawText`.

For BNN especially, this must represent the exact original alert notification text, not a normalized or interpreted version.

Implement a configurable per-application raw-text extraction strategy.

Default priority can consider fields such as:

1. big text
2. normal text
3. text lines
4. ticker text

BUT:

- preserve every candidate field independently inside metadata;
- never normalize punctuation/case/address/county/etc.;
- never parse BNN on-device;
- never remove fields simply because they appear redundant.

Make the raw-text field priority configurable per source application.

---

# 11. MULTI-SOURCE CONFIGURATION

Each selected app must have an editable source configuration.

Conceptually:

```json
{
  "packageName": "com.example.alerts",
  "source": "example-alert-source",
  "enabled": true,
  "rawTextPriority": [
    "android.bigText",
    "android.text",
    "android.textLines",
    "tickerText"
  ]
}
```

BNN should map to:

```json
{
  "source": "bnn"
}
```

Determine BNN's actual installed package name from the connected Samsung phone rather than guessing if ADB is available.

If the phone is not connected during development, make package selection dynamic so I can simply choose BNN from the UI after installation.

---

# 12. EDITABLE JSON CONFIGURATION

I specifically want the transmission configuration to remain editable.

Create a versioned JSON configuration model, for example:

`collector-config.json`

It should control non-secret transport/capture settings such as:

- schema/config version
- device ID
- active server base URL
- ingest path
- selected app package names
- source mappings
- rawText extraction priority
- connection timeout
- retry policy
- backoff policy
- optional metadata behavior
- capture behavior
- diagnostics options

Provide an **Advanced → JSON Configuration** screen containing:

- readable formatted JSON
- direct editor
- Validate button
- Save/Apply button
- validation errors with exact JSON path
- Reset to defaults
- Export JSON
- Import JSON
- schema/version migration

Use Android's Storage Access Framework for user-controlled import/export where appropriate.

A malformed config must never replace the last known-good configuration.

Save atomically:

`validate → write temp → fsync/close → atomic replace`

or use an equivalent robust Android strategy.

---

# 13. SECRETS MUST NOT LIVE IN EXPORTED JSON

The ingestion bearer credential is a secret.

Do not save the plaintext bearer token in exported `collector-config.json`.

Provide a secure Settings field:

**Ingest Authentication Token**

Store it using Android Keystore-backed secure storage.

Never:

- display it after storage;
- log it;
- include it in diagnostics;
- export it with JSON;
- include it in crash reports;
- commit it into Git.

The existing server credential model remains device-specific and independently revocable.

---

# 14. RELIABLE DELIVERY — DO NOT LOSE ALERTS

Delivery reliability matters more than instantaneous code simplicity.

The app must use a durable local outbox.

On every matching notification:

1. snapshot the notification immediately;
2. create a unique client event UUID;
3. persist the complete serializable payload locally;
4. attempt immediate HTTPS transmission;
5. only mark SENT after receiving a valid `202`;
6. retain and retry transient failures.

Do NOT require network connectivity at capture time.

The local delivery record should minimally track:

- event UUID
- package/source
- captured time
- notification post time
- raw text
- complete serialized payload
- delivery state
- attempt count
- next retry time
- last failure code
- last HTTP status
- returned server ingest UUID
- returned server receive time

Suggested states:

`PENDING`
`SENDING`
`SENT`
`RETRY_WAIT`
`FAILED`

Use bounded exponential backoff with jitter.

Respect server behavior:

- `202` = committed success
- `400` = invalid payload
- `401` = authentication problem
- `413` = payload too large
- `415` = wrong content type
- `429` = rate limited
- `503` = temporary DB/gateway failure

Retry transient conditions intelligently.

Do not blindly retry permanent schema/auth failures forever.

---

# 15. DO NOT DEDUPLICATE ON THE PHONE

The existing capture architecture intentionally retains duplicates.

Therefore:

- every matching posted-notification event gets its own collector event ID;
- do not dedupe BNN alerts on the phone;
- do not correlate incident updates;
- do not suppress an event because its text resembles another notification.

Canonical deduplication/update handling belongs on the PC.

---

# 16. BACKGROUND RELIABILITY

The collector must remain dependable when the UI is closed.

Use the Android architecture appropriate for a NotificationListenerService plus a durable outbox/retry worker.

Do not perform expensive JSON processing/network calls synchronously on the notification callback thread.

Snapshot/persist immediately and dispatch work safely.

Add a **Reliability** setup section showing:

- Notification Access: Granted / Missing
- Battery optimization: Unrestricted / Optimized / Unknown
- network state
- queue length
- last successful send
- last captured notification
- last error
- service/listener state

Provide the appropriate Android settings button(s) to let me configure the app for maximum reliable background operation on the Samsung.

Verify current Android documentation before requesting battery-optimization exemption or special background permissions.

---

# 17. FIRST-RUN SETUP EXPERIENCE

Build a polished guided setup.

Suggested first-run checklist:

### 1. Notification Access
Status + button to open Android Notification Access settings.

### 2. Reliability/Battery
Status + button to relevant battery/background settings.

### 3. PC Endpoint
Editable server URL/path.

### 4. Authentication
Secure bearer-token input.

### 5. Select Alert Apps
Application picker, maximum 10.

### 6. Verify
Connectivity/config validation.

### 7. Collector Ready
Show green operational status only when required configuration is actually valid.

The UI must be clean, simple and obvious enough to operate without reading developer documentation.

---

# 18. MAIN UI

Use a clean modern Material 3 interface.

Recommended top-level navigation:

## Status

Show:

- Collector Active / Needs Setup
- Notification Access
- server endpoint
- selected apps count
- queued alerts
- last capture
- last successful upload
- current error if any

## Sources

Installed-app selector:

- icon
- app name
- package
- selected indicator
- search
- `X / 10`
- Show system apps toggle

Tapping a selected source should allow editing:

- source ID
- enabled state
- rawText priority

## Delivery

Show recent delivery records:

- timestamp
- source app
- preview of raw text
- state
- HTTP result
- attempts

Allow:

- retry failed
- inspect complete local serialized envelope
- copy diagnostics without secrets

Do not allow users to mutate previously captured notification content.

## Settings

- endpoint
- device ID
- secure authentication token
- retry settings
- JSON configuration
- import/export
- notification permission
- battery/reliability settings

---

# 19. CONFIGURATION DEFAULTS

Use the existing NFA values as defaults where they remain authoritative:

Device ID:

`nfa-primary-phone`

Ingest path:

`/v1/ingest/alerts`

Preferred remote endpoint:

`https://chaoscentral.tailb71e7e.ts.net`

Do not assume the remote endpoint is currently reachable.

The UI must allow changing it.

Do not connect to:

`127.0.0.1`

from the phone and expect that to mean the PC; loopback on Android is the Android device itself.

---

# 20. OPTIONAL ENDPOINT PROFILES

Implement endpoint profiles if it remains simple.

For example:

- Tailscale
- LAN/Test
- Custom

Only one is active at a time.

Do not silently fail over to an untrusted/public endpoint.

Persist the selected profile.

---

# 21. NETWORK SECURITY

Use HTTPS for the remote operational endpoint.

Do not disable TLS certificate validation.

Do not add permissive trust-all SSL code.

Do not expose PostgreSQL.

Do not send DB credentials to Android.

Only the collector's application-layer ingest credential belongs on the phone.

---

# 22. OBSERVABILITY

Add local structured diagnostics sufficient to answer:

- Did Android deliver the notification to our listener?
- Did our package filter accept it?
- Was it persisted?
- Was HTTP attempted?
- What status was returned?
- Is it waiting for retry?
- What ingest UUID did the PC return?

Never log bearer credentials.

Implement bounded log/storage retention so the utility cannot grow forever.

---

# 23. TEST MATRIX

Build meaningful automated tests.

At minimum test:

### App selection

- user apps displayed
- system apps hidden by default
- system apps visible when requested
- 10 selections accepted
- 11th selection rejected
- selections survive restart

### Notification extraction

- BNN-style normal text
- bigText
- textLines
- title/subtitle
- null fields
- unfamiliar Bundle value
- group summary
- ongoing notification
- updated notification using same Android notification key
- action metadata
- Unicode
- multiline text
- very long notification

### Raw preservation

Prove:

- no parsing
- no normalization
- raw text unchanged
- all candidate text fields preserved in metadata

### JSON config

- valid config loads
- invalid config rejected
- last-good config preserved
- import/export round trip
- version migration
- secret never exported

### Delivery

- `202` success
- `400`
- `401`
- `413`
- `415`
- `429`
- `503`
- network unavailable
- timeout
- process restart with pending queue
- retry/backoff
- duplicate notification events remain distinct

### Security

- auth token absent from logs
- auth token absent from exported JSON
- auth token absent from Room delivery diagnostics
- HTTPS validation remains enabled

---

# 24. LIVE SERVER COMPATIBILITY TEST

After unit tests, test against the existing NFA capture gateway without modifying previously captured data.

First inspect its current documented export/capture contract.

Prove at minimum:

`Android-equivalent schema-v1 payload`
→ `/v1/ingest/alerts`
→ `202`
→ new unique `ingestId`
→ committed row in `nfa_ingest_capture`

Then verify the payload contains sufficient metadata for the downstream PC to distinguish source applications while preserving BNN compatibility.

If Tailscale is still unavailable, validate locally using an equivalent test client and leave remote-device validation clearly pending.

Do not claim phone-to-PC success until it is actually tested from the phone.

---

# 25. BNN MUST BE THE FIRST REAL SOURCE TEST

Once the APK is on the Samsung device:

1. grant Notification Access;
2. configure endpoint/auth;
3. select BNN;
4. trigger or wait for a real BNN notification;
5. verify Android captured it;
6. verify the complete notification envelope in local diagnostics;
7. verify the PC returned `202`;
8. query/inspect `nfa_ingest_capture`;
9. compare the original phone notification against stored `rawText` and metadata;
10. prove no information needed for later parsing was lost.

Do not delete the existing 246+ historical alerts.

---

# 26. MULTI-APP REAL TEST

After BNN succeeds:

select at least one second application if available.

Prove:

- both sources are independently selectable;
- each source retains package/app identity;
- each source may have its own raw-text extraction priority;
- notifications from an unselected app are ignored;
- selected-source notifications reach the same versioned PC capture boundary;
- BNN compatibility remains unchanged.

---

# 27. BUILD OUTPUT

Run full verification:

- Gradle build
- unit tests
- Android lint
- formatting/static checks
- instrumentation tests where practical
- release/debug assembly
- dependency review
- Git diff review

Produce an installable APK.

At minimum produce:

`app-debug.apk`

for immediate sideload testing.

If an approved signing configuration already exists, also produce a signed internal/release APK.

Do not commit signing secrets.

Copy the final testable APK to an obvious repository artifact location such as:

`artifacts/NFA-Notification-Collector.apk`

and report the exact absolute path.

If the Samsung phone is connected over ADB, install the APK and perform the setup/runtime verification.

---

# 28. DOCUMENTATION DELIVERABLES

Create concise operational documentation:

`README.md`

`docs/INSTALL-SAMSUNG.md`

`docs/CONFIGURATION.md`

`docs/INGEST-CONTRACT.md`

`docs/TROUBLESHOOTING.md`

Document:

- APK location
- install/update procedure
- granting Notification Access
- Samsung battery/reliability setup
- selecting sources
- system-app toggle
- max-10 behavior
- endpoint setup
- bearer-token provisioning
- JSON config editing/import/export
- delivery diagnostics
- retry behavior
- how to verify a PC capture
- how to update the app without losing configuration/queued events

---

# 29. GIT / SCOPE

Initialize/use Git for this standalone project.

Commit coherent milestones.

Do not make unrelated changes to:

`D:\github\nfa-platform`

or unrelated PC infrastructure.

Do not alter the existing capture database schema/gateway unless a backward-compatible change is genuinely required for multi-source support.

If such a gateway change is needed:

- inspect current authority first;
- preserve schema-v1 BNN behavior;
- test old payloads;
- test new multi-source payloads;
- update `DATABASE_STATE.md` as appropriate;
- update durable `DATABASE.md` only if a durable contract decision actually changes.

---

# 30. DEFINITION OF DONE

Do not report completion merely because the Android project compiles.

Done means:

- native Android application exists;
- NotificationListenerService works;
- Notification Access setup works;
- selected-app filtering works;
- maximum 10 apps enforced;
- system apps hidden by default;
- Show system apps works;
- app icons/names/packages displayed;
- BNN selectable;
- complete serializable notification envelope captured;
- raw notification text remains unchanged;
- no parsing/business logic on phone;
- durable local outbox implemented;
- retries survive network/process interruption;
- permanent `/v1/ingest/alerts` contract preserved;
- bearer secret stored securely;
- editable versioned JSON config exists;
- JSON import/export works;
- secrets excluded from config export;
- clean Material 3 UI complete;
- diagnostics available;
- all tests/lint/build pass;
- installable APK produced;
- exact APK path reported;
- real BNN phone test performed if the Samsung is connected and remote endpoint is reachable;
- any untestable external dependency is explicitly identified rather than assumed operational.

Return a final report containing:

1. architecture implemented;
2. repository path;
3. package/application ID;
4. files/modules created;
5. permissions used and why;
6. exact notification fields captured;
7. JSON config schema;
8. secure-secret storage implementation;
9. HTTP contract;
10. selected-app implementation;
11. retry/outbox implementation;
12. tests and exact results;
13. APK absolute path;
14. ADB install/test result if available;
15. BNN live-capture result;
16. second-app capture result if available;
17. current endpoint/Tailscale status;
18. Git status;
19. commit SHA(s);
20. any actual blocker.

Continue autonomously through implementation, testing, APK generation, and device installation/testing when possible.

Do not stop for routine implementation decisions.

Stop only when an unresolved decision would materially change the permanent server contract, overwrite existing unrelated work, require unavailable credentials, or otherwise require operator input.