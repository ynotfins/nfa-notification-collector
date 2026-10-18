# Android 16 / API 36 — notification delivery notes

**Fetched:** 2026-10-02  
**SDK setup:** https://developer.android.com/about/versions/16/setup-sdk  
**Sensitive / accessibility (related):** https://developer.android.com/blog/posts/building-a-safer-android-and-google-play-together  
**Behavior-changes-all page:** fetch timed out this session — retry before Phase 4 pins any Android 16-only API.

## Device-measured “quiet” posts (BNN)

From operator dumpsys (`docs/evidence/notif_dump.txt`, gitignored; content not copied here):

- BNN posts on channel `jobs` / “Incidents”, importance **DEFAULT (3)**, custom sound + vibration configured.
- App-level `isAllowPopup=false` → no heads-up popup even when importance is DEFAULT.
- Burst posts can still be “quiet” under notification cooldown while remaining **active in the shade**.
- Active shade children were still present for listeners; quiet ≠ undelivered to NLS.

## Sensitive content

Android 16 strengthens sensitive-content / accessibility protections. Third-party listeners must:

- Detect empty or redacted visible fields when sensitivity flags apply.
- Record an explicit redaction/empty reason.
- **Never invent** title/text.

Privileged unredacted access (`ACCESS_NOTIFICATIONS` / system APIs) is **not** available to sideloaded non-system apps. That is a hard ceiling.

## Notification History

Settings → Notification history is an operator/UI proof surface. There is **no** public API for third-party apps to read History. Scraping it would violate this project’s NLS-only stop gate. See [`.agentcore/evidence/notification-history-api-blocked-2026-10-02.md`](../../../.agentcore/evidence/notification-history-api-blocked-2026-10-02.md).

## Autogrouping

Android may force-group notifications under system aggregate keys (e.g. `g:Aggregate_NormalNotificationSection`). Listeners still receive children when the OS posts them; summary-only filtering must not drop children.
