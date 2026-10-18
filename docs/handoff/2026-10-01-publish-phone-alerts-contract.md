# Handoff — publish phone-alerts.contract.json

**Status:** DONE on nfa-platform `integration/commercial-readiness` (PR #78, tip includes `0425f06`).

**Published paths:**

- `D:\github\nfa-platform\contracts\ingest\phone-alerts.contract.json` (`version` 1.2.0)
- `D:\github\nfa-platform\contracts\ingest\phone-alerts.request.schema.json`

**Collector mirror:** `docs/contracts/phone-alerts.contract.snapshot.json` + bundled `PhoneAlertsContractCatalog` (`contractVersion` `1.2.0`).

**Still planned:** authenticated phone HTTP route to serve the contract (poll policy notes in the published JSON). Until then, APK/bundled snapshot + agent redeploy is the phone refresh path.
