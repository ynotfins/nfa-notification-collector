# Authority and Reference Map

Verified: 2026-10-01

## Authority order

1. Operator messages in the active task.
2. This repository's `AGENTS.md`, `.agentcore/PROJECT_CHARTER.md`, Milestones, and accepted decisions (`docs/DECISIONS_AND_STOP_GATES.md`).
3. **Phone ingest contract (nfa-platform owns):**
   - `D:\github\nfa-platform\contracts\ingest\phone-alerts.contract.json` (poll ≈ daily)
   - `D:\github\nfa-platform\DATABASE.md` §10–12
   - `D:\github\nfa-platform\scripts\ingest-gateway.ts`
   - Local mirror `docs/INGEST-CONTRACT.md` (must not contradict the three above)
4. Current mutable database/runtime state in `D:\NFA-Database-Control\DATABASE_STATE.md` when attached.
5. `docs/input/OPERATOR_PRODUCT_GOAL_2026-08-17.md` as product-requirement **input** only.

Chat history, downloaded prompts, old Android projects, runtime copies, and generated reports are evidence to verify, not automatic authority.

**Do not use** `D:\github\nfa-platform\contracts\data-exports\sheets-alert-feed.schema.json` as the phone POST body contract — that file is for Sheets consumers.

## Multi-root roles

| Root | Role | Mutation policy |
|---|---|---|
| `D:\github\nfa-notification-collector` | Collector source and project governance | Normal project write target |
| `D:\github\nfa-platform` | Ingest contract, gateway source (`scripts/ingest-gateway.ts`), capture ops docs | Read-only from collector task unless operator opens an nfa-platform worktree |
| `D:\github\agentcore-control-plane` | AgentCore authority, enrollment, policies, templates | Read-only from collector task |
| `D:\NFA-Database-Control` | Mutable DB/runtime state markdown | Read-only evidence |
| `I:\LocalApps\NFAAlerts` | Deployed immutable gateway runtime (if present) | Runtime evidence only; never a project folder |
| `F:\PostgreSQL18\data` | Live PostgreSQL PGDATA | Never add, index, or edit as a project folder |

Historical note: older bootstrap docs pointed at `D:\nfa-alerts-database` as the gateway/DB home. For NFA phone ingest today, **nfa-platform** is the contract and `scripts/ingest-gateway.ts` source of truth.

## Capture clients coexistence

| Client | Role |
|---|---|
| `com.nfaalerts.collector` | Native NotificationListener + Room outbox; intended primary |
| MacroDroid | Still allowed parallel capture until cutover gates close; same schema-v1 |

Both must obey `phone-alerts.contract.json`. Neither parses BNN on the phone.

## Legacy implementation evidence

Existing notification-listener code may be mined read-only for behavior, never copied wholesale without review:

- `D:\github\android-alerts` — strongest historical BNN package evidence (`us.bnn.newsapp`), but old/orphan build topology.
- `D:\github\alerts-sheets\android` and `D:\github\alert-collector\alerts-sheets\android` — duplicate package identity; historical only.
- `D:\github\notification-database` — independent notification journal; not the new collector authority.
- `D:\github\Alert Tracker\android` — conflicting build files; do not reuse build configuration blindly.
