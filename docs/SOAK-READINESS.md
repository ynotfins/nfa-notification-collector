# Soak readiness — phone outbox drain

Goal: prove capture → Room → paced POST `/v1/ingest/alerts` → 202 under backlog without silent drops.

## Limits to respect

- Burst ~240, refill ~120/minute/device (DrainPaceLimiter)
- Ordered claim by `postTimeEpochMillis` (never skip older retry)
- SENT retention default 100,000 rows / 180 days, purged in 100-row batches on the IO dispatcher
- Diagnostics default 50,000 rows / 90 days (protected codes 2,000 rows)
- Storage pressure: 50,000 rows or 200 MiB triggers immediate oldest-SENT pruning
- Backpressure: unsent > 5,000 rows adds a bounded 250 ms capture pause (hard cap 1,000 ms)
- Unsent rows are never pruned by age, count, or pressure

Full policy and measured numbers: `docs/RETENTION-AND-STORAGE.md`.

## Local harness (this repo)

```powershell
cd D:\github\nfa-notification-collector
.\gradlew.bat :app:testDebugUnitTest --tests "com.nfaalerts.collector.delivery.DrainPaceLimiterTest" --tests "com.nfaalerts.collector.delivery.DeliveryCoordinatorTest" --tests "com.nfaalerts.collector.delivery.WireProjectorTest"
```

For a device soak, install the debug APK, keep Notification Access + Unrestricted battery, then drive BNN (or a selected test package) while recording:

1. capture count before/after
2. SENT / QUARANTINED / BLOCKED counts
3. max backlog age
4. any silent gap versus shade inventory

Write evidence to `.agentcore/evidence/soak-<date>.json`.

Run the compressed policy/latency soak on an emulator (never the primary S26):

```powershell
adb -s emulator-5554 shell am instrument -w `
  -e class com.nfaalerts.collector.retention.RetentionSoakInstrumentedTest `
  com.nfaalerts.collector.test/androidx.test.runner.AndroidJUnitRunner
```

The test simulates 72 hours at 100 posts/hour (7,200 captures) with hourly maintenance and fails on
p95 post→Room ≥ 300 ms, max write ≥ 250 ms, or retained rows above the policy.

**Wall-clock 72-hour soak: not yet run.** The compressed simulation is recorded in
`docs/RETENTION-AND-STORAGE.md`. A wall-clock run still needs a secondary device or emulator,
sustained 100 posts/hour, the same assertions, and a logcat check for
`already posted or enqueued 50`.

## 100,000-alert target

Do not prune undelivered rows. SENT retention is already 100,000 rows by default. Expect paced drain
(~2/sec steady after burst). Quarantine rate must stay explainable (empty rawText / schema) — never
silent drop.
