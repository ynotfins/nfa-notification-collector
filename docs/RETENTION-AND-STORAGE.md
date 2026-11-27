# Retention and local storage

Status: **implemented/current** (collector 0.2.x). This document is the local-data authority for the
collector. It is not the nfa-platform server retention policy.

## Exact policy

| Data | Pruned by age | Pruned by count | Notes |
| --- | --- | --- | --- |
| Captured rows whose outbox state is `SENT` | `retention.sentDays` (default 180) | Keep only the newest `retention.maxSentRows` (default 100,000) | Tombstoned before the payload row is removed |
| Unsent rows (`PENDING`, `SENDING`, `RETRY_WAIT`, `PAUSED_AUTH`, `BLOCKED_CONTRACT`, `QUARANTINED`) | Never | Never | A purge can only delete rows that are currently `SENT` |
| Retention tombstones | Never | Never | One row per pruned event: `eventId`, `envelopeSha256`, `serverIngestId`, `retainedAtEpochMillis`, `reason` (`SENT_RETENTION` or `STORAGE_PRESSURE`) |
| Diagnostics | `diagnostics.retentionDays` (default 90) | `diagnostics.maxRows` (default 50,000) | Protected event codes keep their own 2,000-row budget |

The live primary phone config (verified 2026-11-27) is `sentDays=180`, `maxSentRows=100000`,
`diagnostics.retentionDays=90`, `diagnostics.maxRows=50000`. The earlier assumption of
"10 days / 10,000 rows" is **not** the shipped policy and was corrected during this work.

The exact values are configurable in `collector-config.json` and in the Settings screen. Older
configs that omit the new fields are normalized to the defaults below.

## Purge mechanics

- Sent retention runs on app start and after each delivery-drain worker pass.
- Rows are deleted in bounded batches of **100** per transaction (`RetentionDao.DEFAULT_PURGE_BATCH_SIZE`),
  with a 5 ms pause between batches so a capture write can interleave.
- Each batch uses bulk SQL: delete the `SENT` outbox rows, delete now-orphaned capture rows, then insert
  tombstones for every event whose outbox row disappeared.
- The whole pass runs on `Dispatchers.IO`, never on the notification-listener callback thread.
- The `delivery_outbox(state, sentAtEpochMillis)` index (schema v5) keeps oldest-row selection cheap.

## Storage-pressure release

The collector samples local storage every 15 seconds and after every 500 persisted captures.

- Thresholds: `retention.pressureMaxRows` (default 50,000 rows) or `retention.pressureMaxBytes`
  (default 200 MiB, counting the database plus `-wal` and `-shm`).
- On the row threshold, oldest `SENT` rows are pruned immediately until total rows reach 75% of the
  threshold, minus any unsent rows that cannot be pruned.
- On the byte threshold, one bounded slice of the oldest `SENT` rows is pruned (10% of
  `pressureMaxRows`, at least one purge batch, hard-capped at 10,000 rows). Repeated checks continue
  if the file stays large.
- Unsent rows are never candidates. If only unsent rows remain, the pass records `pruned=0` rather
  than dropping alerts.
- Every release writes the durable diagnostic `STORAGE_PRESSURE_PRUNED` with `pruned`, `queue`,
  `dbBytes`, `thresholdRows`, and `thresholdBytes`.
- SQLite reuses freed pages for new writes. The file may not shrink immediately; the collector does
  not run `VACUUM` on the capture path.

The Status screen shows the captured row count and current database size (`Local database: …`).

## Delivery backpressure

- When the unsent outbox exceeds `retention.backpressureUnsentRows` (default 5,000), capture
  persistence adds `retention.backpressureDelayMs` (default 250 ms, hard-capped at 1,000 ms) before
  the next write.
- Backpressure never rejects a notification and never drops a row. The notification is already cloned
  and dispatched; the pause only paces Room writes.
- The delay is bounded so a shade catch-up burst cannot be stalled indefinitely.

## Measured evidence

Emulator `Pixel_8_API_36` (Android API 36), 2026-11-27. Primary S26 was not used for these runs.

| Scenario | Result |
| --- | --- |
| 100,000 captured rows → 10,000-row policy | 90,000 SENT rows deleted in 900 batches; 31–37 s; max concurrent capture write **28–44 ms** (a cold first run measured 80 ms) |
| 72-hour compressed soak (100 posts/hour, 7,200 posts, hourly maintenance, 1,000-row policy) | 1,000 rows retained; 6,200 tombstones; p95 post→Room **2 ms**; max **7 ms**; 0 `already posted or enqueued 50` logcat lines |
| Unsent rows across all six non-SENT states | Survived age purge and pressure purge; no tombstone created |
| Tombstone consistency | Every deleted event has a tombstone; surviving/newest rows do not; a second pass deletes 0 |
| Migration 4→5 | `index_delivery_outbox_state_sentAtEpochMillis` present and validated |

**Not yet proven:** a wall-clock 72-hour soak on a secondary physical device. The compressed
simulation above proves the policy and latency under simulated load; it is not a substitute for a
real 72-hour run. See `docs/SOAK-READINESS.md` for the wall-clock procedure.

## Phone vs nfa-platform retention boundary

These are different systems and must not be conflated:

- **Collector (this app):** keeps captured notification envelopes locally so delivery can be retried
  and audited. Defaults are 180 days / 100,000 `SENT` rows, with the pressure and backpressure rules
  above. Unsent work is never pruned.
- **nfa-platform server:** read, delivered in-app notifications are pruned after 180 days; unread
  notifications are never pruned regardless of age. `user_locations` are pruned after 90 days and
  `audit_logs` are never pruned (`packages/ts/observability/src/retention.ts` and
  `docs/operations/SECRET_ROTATION.md`).

No verified nfa-platform source defines a 10-day/30-day app retention policy. If an operator expects
that number, it belongs to a different surface and must be documented there before being cited here.

## Configuration example

```json
"retention": {
  "sentDays": 180,
  "maxSentRows": 100000,
  "pressureMaxRows": 50000,
  "pressureMaxBytes": 209715200,
  "backpressureUnsentRows": 5000,
  "backpressureDelayMs": 250
}
```

Validation: `sentDays`, `maxSentRows`, `pressureMaxRows`, and `backpressureUnsentRows` must be
positive integers; `pressureMaxBytes` must be 1 byte–10 GiB; `backpressureDelayMs` must be
0–5,000 ms (runtime caps the actual pause at 1,000 ms).
