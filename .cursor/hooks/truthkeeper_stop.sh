#!/bin/sh
# docs-guardian stop hook.
# Fires when the agent session ends. Checks two things:
#   1. Were any material files changed this session?
#   2. If yes, does the agent's last response contain "Docs: CLEAR"?
# If material changes exist but the declaration is missing or is "NOT RUN",
# it re-injects a blocker so the agent cannot silently close without reconciling.

REPO_ROOT="$(git -C "$(dirname "$0")/../.." rev-parse --show-toplevel 2>/dev/null)"
if [ -z "$REPO_ROOT" ]; then
  echo '{"suppress": true}'
  exit 0
fi

# Check for material files changed vs HEAD (staged + unstaged)
MATERIAL_CHANGED=$(git -C "$REPO_ROOT" diff --name-only HEAD 2>/dev/null | grep -E \
  '(AGENTS\.md|NFA_ALIGNMENT\.md|CLAUDE\.md|README\.md|docs/INGEST-CONTRACT\.md|docs/AUTHORITY_AND_REFERENCE_MAP\.md|docs/PRODUCT_REQUIREMENTS\.md|docs/DECISIONS_AND_STOP_GATES\.md|docs/SECURITY-AND-DATA-HANDLING\.md|docs/ANDROID_TOOLCHAIN_BASELINE\.md|docs/contracts/phone-alerts\.contract\.snapshot\.json|app/src/main/AndroidManifest\.xml|app/src/main/java/com/nfaalerts/collector/.*\.kt|app/schemas/.*\.json|.*\.gradle\.kts|.*\.gradle$)' \
  | head -5)

if [ -z "$MATERIAL_CHANGED" ]; then
  echo '{"suppress": true}'
  exit 0
fi

# Read the agent's last response text from stdin JSON
LAST_RESPONSE=$(echo "$INPUT" | node -pe "JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).lastResponse" 2>/dev/null || echo "")

# Check if "Docs: CLEAR" appears in the last response
HAS_CLEAR=$(echo "$LAST_RESPONSE" | grep -c "Docs: CLEAR" 2>/dev/null || echo "0")

if [ "$HAS_CLEAR" -gt "0" ] 2>/dev/null; then
  # Declaration present and clear — allow session to end
  echo '{"suppress": true}'
  exit 0
fi

FIRST_FILE=$(echo "$MATERIAL_CHANGED" | head -1)

cat <<EOF
{
  "message": "🚫  DOCS-GUARDIAN GATE — task cannot close.\n\nMaterial files were changed (e.g. ${FIRST_FILE}) but your response does not contain 'Docs: CLEAR'.\n\nRequired steps:\n1. Run: Task(subagent_type=docs-guardian, run_in_background=false)\n2. Wait for Completion gate: CLEAR\n3. End your response with the exact line: Docs: CLEAR\n\nIf docs-guardian was already run and cleared in this session, re-state: Docs: CLEAR\nIf there is a documented reason it cannot run: Docs: NOT RUN — [explicit reason]"
}
EOF
