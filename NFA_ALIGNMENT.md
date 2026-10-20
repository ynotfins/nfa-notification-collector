# NFA alignment — nfa-notification-collector

This repository is an NFA-owned sister. It does not copy the nfa-platform
authority bundle.

**Index:** `D:\github\nfa-platform\docs\SISTER_ALIGNMENT.md`

## Consume

- Phone POST contract: `D:\github\nfa-platform\contracts\ingest\phone-alerts.contract.json`
- Prose + validator: nfa-platform `DATABASE.md` §10–12 and `scripts/ingest-gateway.ts`
- Local detail map: `docs/AUTHORITY_AND_REFERENCE_MAP.md` (must not contradict the three above)

## Never own

- BNN parse, incident dedupe, geocode, county / FIPS commercial routing
- CRM or business logic on Android
- A second POST body (including the Sheets export schema)
- PostgreSQL credentials or direct database access
- NFA billing, seats, or entitlements

`deviceId` defaults to `nfa-primary-phone`. The bearer is never committed.
Duplicates stay distinct. MacroDroid may remain a parallel schema-v1 client
until cutover gates close.
