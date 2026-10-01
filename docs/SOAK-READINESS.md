# Soak readiness — phone outbox drain

Goal: prove capture → Room → paced POST `/v1/ingest/alerts` → 202 under backlog without silent drops.

## Limits to respect

- Burst ~240, refill ~120/minute/device (DrainPaceLimiter)
- Ordered claim by `postTimeEpochMillis` (never skip older retry)
- SENT retention default 100,000 rows / 180 days
- Diagnostics default 50,000 rows / 90 days

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

## 100,000-alert target

Do not prune undelivered rows. Raise SENT retention to 100,000 before soak. Expect paced drain (~2/sec steady after burst). Quarantine rate must stay explainable (empty rawText / schema) — never silent drop.
