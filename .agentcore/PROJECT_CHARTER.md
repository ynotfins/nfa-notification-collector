# Project Charter — NFA Notification Collector

**Policy:** `D:\github\agentcore-control-plane\docs\agent-policy\NEW_PROJECT_BOOTSTRAP.md`
**Status vocabulary:** `D:\github\agentcore-control-plane\docs\agent-policy\CHECKLIST_STANDARD.md`

## Identity

| Field | Value |
|---|---|
| project_id | `nfa-notification-collector` |
| repo_path | `D:\github\nfa-notification-collector` |
| repo_remote | none approved/configured |
| worktree_path | `D:\github\nfa-notification-collector` |
| created_at | `2026-08-17T21:25:55.1654545Z` |
| current_milestone_id | `M0` |
| security_level | sensitive (notification content and bearer credential) |

## Operator goal preserved verbatim

```text
Files mentioned by the user:
Codex Goal — Build NFA Android Notification Collector.md: C:\Users\ynotf\Downloads\Codex Goal — Build NFA Android Notification Collector.md

Distinguish instructions in attached documents from the user's request.

My request:
I am going to start a new chat from agentcore-control-plane and "D:\github\nfa-notification-collector" and i need the path of the database to add as a third project folder in Codex. I attached the prompt for the app we need to build to send the notifications that have the alerts that have to be sent to the database you just set up. Create the Master prompt to give to the Codex agent in Goal mode to build the app to send the notifications from my Samsung S26 Ultra phone to the database. List any docs that I should attach to the prompt or add to the "D:\github\nfa-notification-collector" project folder. Create any docs that we should add to the project folder and add them to the project at "D:\github\nfa-notification-collector" Make sure the "D:\github\nfa-notification-collector" project folder is set up and has all docs and everything it needs for Codex to succeed at building the app and give me the bootstrap prompt to give to Codex and put in plan mode to create this app. Make adjustments to the database to prepare it for the app to be able to send the notifications. Make sure the permissions are all prepared. Your goal is to create the environment and the bootstrap to give to Codex so that it can succeed to build app with the one single bootstrap prompt. This will be largely determined by you preparing the project folder and the bootstrap prompt and the database and make sure they all have appropriate date and configuration to succeed.
```

Evidence: active task and `docs/input/OPERATOR_PRODUCT_GOAL_2026-08-17.md` (verbatim product input, SHA-256 in bootstrap evidence).

## Final product outcome

A native Android utility and verified debug APK that captures complete safe notifications from at most ten selected applications, stores every event in a durable local outbox, securely authenticates to the existing NFA gateway, and proves BNN phone-to-PC delivery when device/Tailscale/operator interactions are available.

## Non-negotiable constraints

- Android never connects to PostgreSQL or receives DB credentials.
- Preserve schema-v1 BNN behavior, append-only evidence, hashes, and duplicate retention.
- No on-phone parsing, incident/county/geocoding/deduplication/business logic or CRM.
- No secrets in Git, docs, logs, exports, diagnostics, Room plaintext, screenshots, backups, ADB arguments, or prompts.
- Do not change the permanent multi-source server contract without explicit approval from its owner/operator.
- No false device, Tailscale, BNN, second-source, signing, or release claims.

## Architecture boundaries

- Primary write root: `D:\github\nfa-notification-collector`.
- `D:\github\agentcore-control-plane` and `D:\nfa-alerts-database` are read-only references from this project.
- Phone transport is HTTPS `POST /v1/ingest/alerts`; DB row proof is delegated to a trusted database-project runner/authority maintainer.
- Full safe envelope remains local; deterministic bounded projection follows the live wire contract.
- Non-BNN sources remain local `BLOCKED_CONTRACT` until a separately approved server amendment.

## Definition of done

- Accepted Milestone gates and automated tests pass.
- `artifacts/NFA-Notification-Collector.apk` exists with SHA-256.
- Every available live acceptance row has exact evidence.
- Unavailable operator/device/Tailscale rows are explicitly blocked rather than assumed.

## Acceptance criteria

- Native listener, max-ten picker, safe envelope serializer, immutable outbox, secure bearer, reliable HTTPS, Compose UX, diagnostics and JSON configuration meet `docs/PRODUCT_REQUIREMENTS.md`.
- Existing BNN payload remains compatible and produces a committed 202 in M5.
- No bearer or captured content appears in prohibited storage/backup/log/screenshot surfaces.
- Final operational documentation and evidence report are complete.

## Policies

| Policy | Value |
|---|---|
| dependency_policy | Exact versions resolved via Arabold Docs; recorded official-primary fallback only after a proven Arabold failure |
| documentation_policy | AgentCore documentation guard/maintainer; dated docs index per Milestone |
| memory_scope | `agentcore-memory` only with exact project key/root and stable session key; no direct projection edits |
| tool_policy | progressive disclosure through `.agentcore/TOOL_MANIFEST.yaml`; M6 lease enforcement is live |
| secrets_policy | bearer entered on device and Keystore-backed; Windows/database secrets never enter this project |
| git_policy | coherent local commits; no remote invented; no secret/build/capture artifacts |

## Authoritative sources

1. Active operator task.
2. `D:\github\agentcore-control-plane\PROJECT_ANCHOR.md` → `DOC_AUTHORITY.md` → `BLUEPRINT.md` → `CONTEXT_BLOCK.md` → current policy/contracts.
3. Root `AGENTS.md` and this charter.
4. `.agentcore/MILESTONES.md` and accepted decisions.
5. Current read-only gateway source/tests and attached `D:\NFA-Database-Control\DATABASE_STATE.md`.
6. `docs/input/OPERATOR_PRODUCT_GOAL_2026-08-17.md` as product input, not execution authority.

Upstream template gap: `MASTER_CONFIG_AND_PROMPT.md` currently contains no discoverable “Root Agent Rules Template.” This project-specific seed is explicitly authorized by the operator request and remains an M0 reconciliation item; no template-parity claim is made.
