# M0 — Bootstrap and Accepted Implementation Plan

## Rationale

Establish exact identity, authority, evidence and a reviewed plan before high-risk Android/secret/background work.

**Risk classification:** low repository mutation; sensitive requirements
**Approved tools:** bootstrap profile in `.agentcore/TOOL_MANIFEST.yaml`
**Dependencies:** exact enrollment, operator request and product input
**Rollback:** initial clean local Git commit; leave M0 open on any failed gate

## Outcome

The project has accurate governance, a verified environment/server/product contract, a current official-doc checkpoint, and an operator-approved TDD implementation plan. No Android application code has begun.

## Entry criteria

- Exact target directory exists and contains no unrelated work.
- Project key/root are present in the default-deny enrollment contract.
- Operator request and product input are available.

## Exit criteria

- Root and `.agentcore` files validate and documentation governance evidence is recorded.
- Global/project read order, AgentCore session/degraded state, Context Fabric state, official docs and tool audit are evidenced.
- Design/spec and implementation plan have independent ACCEPT and operator approval.
- Clean local restore commit exists; no remote invented.
- M0 acceptance rows pass or use policy-valid `skipped_with_reason` where authorized.

## Acceptance checks

- `AT-M0-1` through `AT-M0-4` in `.agentcore/ACCEPTANCE_TESTS.md`.
- JSON/YAML parsing, checklist projection, placeholder, path, secret, junk and Git checks.
- Independent package and documentation-guard review.

## Required artifacts

- `AGENTS.md`, `CLAUDE.md`, `README.md`, `CODEX_GOAL_BOOTSTRAP.md`.
- `.agentcore` charter, milestones, checklist, manifest, state seed, risks, acceptance and evidence.
- Product/contract/toolchain/security/operational docs under `docs/`.
- Design/spec and detailed plan under `docs/superpowers/` (created in Plan mode).

## Macro/Micro projection

This table projects only canonical `id`, `action`, and `status` from `.agentcore/checklists/state.json`; all other canonical fields remain JSON-only and are not abbreviated here.

| ID | Action | Status |
|---|---|---|
| M0.A1 | Identity and authority | in_progress |
| M0.A1.m1 | Resolve exact enrollment and identity without router mutation | passed |
| M0.A1.m2 | Initialize standalone Git repository and preserve inherited metadata | passed |
| M0.A1.m3 | Complete required global/project authority reading | pending |
| M0.A1.m4 | Open/resume governed memory session and startup context | blocked |
| M0.A2 | Repository, server, toolchain and docs reality | in_progress |
| M0.A2.m1 | Inspect target and legacy package/listener evidence | passed |
| M0.A2.m2 | Verify live ingest contract and permissions | passed |
| M0.A2.m3 | Inventory Android SDK, JDK, Gradle, ADB and device | passed |
| M0.A2.m4 | Resolve official dependency/API docs through Arabold | pending |
| M0.A2.m5 | Capture Context Fabric entry state/drift | pending |
| M0.A3 | Governance, design, plan and restore point | in_progress |
| M0.A3.m1 | Create and validate bootstrap governance/package documents | passed |
| M0.A3.m2 | Write and independently review design and detailed TDD plan | pending |
| M0.A3.m3 | Obtain operator approval for written plan | pending |
| M0.A3.m4 | Run M0 tool audit | pending |
| M0.A3.m5 | Create clean local bootstrap restore commit | pending |
| M0.A3.m6 | Run M0 acceptance gate and build handoff | pending |

## Checkpoints

- Context Fabric: pending repo-local initialization/capture.
- Arabold docs: retry pending; bootstrap SSE 404 and official-primary fallback recorded.
- Memory/handoff: signed device assertion required; retry with stable session key in new task.
- Tool audit: pending M0 exit.
