# Capture miss findings — 2026-10-02

**Branch:** `goal/capture-miss-hardening` (WIP treated as unverified; re-validated below)  
**Stop:** Phase 2 only — no Phase 3+ code/grants until operator approval.  
**Private data:** `docs/evidence/notif_dump.txt` is gitignored. This doc has **no** notification titles/text.

## Executive verdict

1. **Current active shade is not a collector discard bug.** All 33 BNN child tags active at dump time were already in Room and **SENT**.
2. Those 33 were captured on the **live** path (first-capture lag ~233–402 ms), not via shade catch-up.
3. The historical **~60 History vs 18 Room/MacroDroid** miss (2026-10-01 08:41–11:22 ET) fits **selective non-delivery to third-party listeners** (or History rows that never became NLS-visible posts), **not** a long “listener dead” stretch with later catch-up recovery.
4. “Quiet” posts are explained by **no heads-up** (`isAllowPopup=false`) and burst cooldown — not by NLS type filters excluding silent.

---

## FIRST TEST — active shade tags vs Room / delivery

**Source:** dumpsys snapshot ~2026-10-02 14:28; Room DB pulled from `com.nfaalerts.collector` the same day.

| Metric | Result |
|---|---|
| Active BNN child tags in shade | 33 |
| Tags present in Room | **33 / 33** |
| Missing from Room while active | **0** |
| Delivery state for those tags | **SENT** (some tags have multiple SENT rows from updates/reposts) |

**Conclusion:** Any tag missing from Room **while still active in the shade** would be a collector-side bug. That did **not** occur for this snapshot. The historical 18/60 problem is a different failure class (posts never persisted at all).

---

## FIRST TEST follow-up — live callback vs shade catch-up

The schema has **no** `capturePath` column. Inference method (documented for Phase 4 improvement):

- Measure `capturedAtEpochMillis - postTimeEpochMillis` for the **first** Room row per tag.
- **Live `onNotificationPosted`:** lag typically hundreds of ms (binder → async persist).
- **Shade catch-up:** lag typically seconds to minutes (post sat in shade until reconcile).

### Results for the 33 active tags (first capture each)

| Lag bucket | Count | Min lag | Max lag | Avg lag |
|---|---|---|---|---|
| &lt; 2 s (live-likely) | **33** | 233 ms | 402 ms | 254 ms |
| 2 s–60 s | 0 | — | — | — |
| &gt; 5 min (catch-up suspect) | 0 | — | — | — |

Every first capture is live-likely. **Meaningful share via catch-up: none.**

**Implication for audit ranking:** do **not** rank “live callback path broken for current posts” as a primary cause of the active-shade set. Live path worked for these 33. Catch-up remains a **safety net** for disconnect gaps, not the reason these tags exist in Room.

**Caveat:** Catch-up cannot be proven/disproven for posts that **never** entered Room. The Oct 1 misses left no Room row at all.

---

## Oct 1 08:41–11:22 ET — the shared 18

### The 18 Room captures (timestamps only)

| # | post_local (ET) | cap_local (ET) | lag_ms | tag |
|---|---|---|---|---|
| 1 | 2026-10-01 08:48:01 | 08:48:01 | 239 | 1907790 |
| 2 | 08:51:57 | 08:51:57 | 261 | 1907797 |
| 3 | 08:57:43 | 08:57:43 | 252 | 1907805 |
| 4 | 09:08:26 | 09:08:27 | 254 | 1907806 |
| 5 | 09:10:16 | 09:10:16 | 243 | 1907806 |
| 6 | 09:14:00 | 09:14:00 | 222 | 1907808 |
| 7 | 09:15:34 | 09:15:34 | 255 | 1907803 |
| 8 | 09:24:46 | 09:24:46 | 243 | 1907806 |
| 9 | 09:27:06 | 09:27:06 | 254 | 1907811 |
| 10 | 09:33:23 | 09:33:24 | 266 | 1907814 |
| 11 | 09:37:30 | 09:37:30 | 245 | 1907580 |
| 12 | 10:14:05 | 10:14:05 | 263 | 1907822 |
| 13 | 10:24:26 | 10:24:26 | 259 | 1907824 |
| 14 | 10:36:56 | 10:36:56 | 248 | 1907826 |
| 15 | 10:43:51 | 10:43:51 | 249 | 1907827 |
| 16 | 10:44:45 | 10:44:45 | 235 | 1907827 |
| 17 | 11:13:27 | 11:13:28 | 266 | 1907831 |
| 18 | 11:22:24 | 11:22:24 | 263 | 1907832 |

- **18 rows, 15 distinct tags** (multi-capture on 1907806 ×3, 1907827 ×2).
- All lags **222–266 ms** → live path when captured; **no catch-up flood** after a dead stretch.
- Largest gaps **between** captured posts inside the window: ~36.6 min (09:37→10:14), ~28.7 min (10:44→11:13). Captures continue before and after those gaps with the same live lag — not a single reconnect importing a backlog.

### Listener / FGS / process records for that window

| Source | What it has for Oct 1 morning |
|---|---|
| Room `diagnostic_events` | **Empty table** (0 rows). Allowed codes include `LISTENER_ACCEPTED` / `CAPTURE_PERSISTED`, but production code does not currently write them (only tests exercise `DiagnosticRepository.record`). |
| In-memory `ListenerStatusRepository` | Connect/disconnect counters — **not durable**; lost on process death; unavailable historically. |
| dumpsys notification listener enable history | Entries start **2026-10-02** (e.g. collector/MacroDroid enable at 12:18:12). **No Oct 1 collector connect/disconnect lines** in the dump. |
| FGS | Collector reliability notification was active at dump time; **no durable FGS timeline** for Oct 1 morning in app DB. |

**Pattern call:** misses fit **selective non-delivery** (History shows posts that neither collector nor MacroDroid received as live NLS events), **not** “listener dead for a stretch then recovered.” A dead-listener pattern would typically show a multi-hour zero-capture hole followed by catch-up imports with large lag — neither MacroDroid nor Room show that for the 18 that did arrive.

---

## Why posts felt “quiet”

- BNN channel `jobs` importance DEFAULT (3) with sound configured.
- App `isAllowPopup=false` → no heads-up.
- Android 16 cooldown can suppress repeated audible alerts during bursts.
- Quiet presentation **does not** equal “listeners skip these.” Current active quiet children were still captured live.

---

## Rebind / competing apps (dump chronology)

- Device boot ~01:48 on 2026-10-02 (prior evidence).
- **12:18:12** — collector and MacroDroid `enabled=true` within ~130 ms; OpenClaw flipped `enabled=false` immediately after. This matches **operator/ADB listener allowlist changes**, not a spontaneous triple crash.
- **~14:12** — OpenClaw, AlertSheets, MobileClaw briefly re-enabled (MobileClaw then disabled). Competing listeners can still interfere; Buzzkill remains installed as a notification modifier.

`mMaxPackageEnqueueRate=5.0`: noted. Rate-limit drops would also be absent from History, so this does not explain History≫listener alone.

---

## Ranked root causes (adjusted after items 1–2)

| Rank | Hypothesis | Status |
|---|---|---|
| **1** | OS / framework did not deliver many History-visible BNN posts to third-party NLS (or delivered only ephemeral forms both apps missed) | **Lead** — identical 18 across collector + MacroDroid; live lag on received posts; no catch-up backlog |
| **2** | Simultaneous multi-listener outage windows | **Weaker** — captures continue throughout the morning with live lag; no app-side connect telemetry to prove/disprove |
| **3** | Process death after binder return before Room persist | **Real code risk** for future misses (async dispatch) but **not** evidenced as the Oct 1 pattern (received posts persisted within ~250 ms; misses never appeared) |
| **4** | Listener type filters excluding silent | **Weakened** — manifest includes silent; dump shows no NLF narrowing; operator confirmed Silent enabled |
| **5** | Quiet / demoted / cooldown | Explains UX quiet; **does not** explain shared miss |
| **6** | Autogroup summary discard dropping children | **Disproven** for children — summaries skipped correctly; children captured when delivered |
| **7** | Live callback path generally broken | **Disproven for current 33** — all live-likely first captures |

---

## Machine-readable oracle (design for Phase 5; scripts after Phase 3 approval)

**Primary soak oracle (not History):**

```text
adb -s R3GL605J0AH logcat -b events -v time
```

Filter for `notification_enqueue` / `notification_canceled` (and related) for selected packages. Compare to a Room export of `(packageName, notificationKey/tag, postTimeEpochMillis, cancel reason if any)`.

**Planned artifacts (do not commit — gitignore already covers):**

- `docs/evidence/*-oracle*` / `*.events.log` / `room-export*` / `logcat*`
- PC scripts (Phase 3+): capture events log; compare against Room export.

**Secondary:** Notification History manual count for the same hour window.

---

## WIP re-validation (no code changes this phase)

Uncommitted WIP already adds: binder clone before async work, posted-callback failure counter, shade reconcile on connect + 60s, `CaptureKeepAlive` worker, faster FGS watchdog when disconnected. Useful scaffolding. **Does not** yet: durable-before-return journal, RankingMap callback, path telemetry (live vs catch-up), durable listener connect diagnostics, or events-log oracle. Keep WIP; do not discard; do not expand until Phase 3+ approval.

---

## Next step

Await operator approval for **Phase 3** (permissions/scripts/diagnostics grants UI) and later Phase 4 code. Immediate ask only if you want a different primary oracle package filter list before scripts are written.
