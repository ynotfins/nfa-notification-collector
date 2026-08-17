# Authority and Reference Map

Verified: 2026-08-17

## Authority order

1. Operator messages in the active task.
2. `D:\github\agentcore-control-plane\PROJECT_ANCHOR.md`, `DOC_AUTHORITY.md`, `BLUEPRINT.md`, `CONTEXT_BLOCK.md`, and `docs/agent-policy/`.
3. This repository's `AGENTS.md`, `.agentcore/PROJECT_CHARTER.md`, Milestones, and accepted decisions.
4. Current NFA server source/tests in read-only `D:\nfa-alerts-database`.
5. Current mutable database/runtime state in attached `D:\NFA-Database-Control\DATABASE_STATE.md`.
6. Durable NFA platform database reference in attached `D:\github\nfa-platform\DATABASE.md`.
7. `docs/input/OPERATOR_PRODUCT_GOAL_2026-08-17.md` as product-requirement input.

Chat history, downloaded prompts, old Android projects, runtime copies, and generated reports are evidence to verify, not automatic authority.

## Multi-root roles

| Root | Role | Mutation policy |
|---|---|---|
| `D:\github\nfa-notification-collector` | Collector source and project governance | Normal project write target |
| `D:\github\agentcore-control-plane` | AgentCore authority, enrollment, policies, templates | Read-only from collector task |
| `D:\nfa-alerts-database` | Gateway/database migrations, validators, tests | Read-only from collector task; submit proposals to owner |
| `I:\LocalApps\NFAAlerts` | Deployed immutable gateway runtime | Runtime evidence only; never a project folder |
| `F:\PostgreSQL18\data` | Live PostgreSQL PGDATA | Never add, index, or edit as a project folder |

## External attachments for the new Codex task

Attach these because they are outside the three project roots:

- `D:\NFA-Database-Control\DATABASE_STATE.md`
- `D:\github\nfa-platform\DATABASE.md`

The downloaded product goal has already been preserved in this repository and does not need to be attached again.

Known terminology drift: the current `DATABASE_STATE.md` says “Flutter collector” in one sentence. The durable `DATABASE.md`, active operator goal, and live architecture require **Android collector and application clients use the ingest gateway, never direct PostgreSQL**. The first governed edit attempt lacked the inherited worker key; a fresh keyed documentation-maintainer attempt later returned `Changed=false` and wrote nothing. The external file therefore remains unchanged. Treat the wording as stale terminology, not permission to use Flutter or direct DB access.

## Legacy implementation evidence

Existing notification-listener code may be mined read-only for behavior, never copied wholesale without review:

- `D:\github\android-alerts` — strongest historical BNN package evidence (`us.bnn.newsapp`), but old/orphan build topology.
- `D:\github\alerts-sheets\android` and `D:\github\alert-collector\alerts-sheets\android` — duplicate package identity; historical only.
- `D:\github\notification-database` — independent notification journal; not the new collector authority.
- `D:\github\Alert Tracker\android` — conflicting build files; do not reuse build configuration blindly.

No existing source-controlled project uses `com.nfaalerts.collector`.
