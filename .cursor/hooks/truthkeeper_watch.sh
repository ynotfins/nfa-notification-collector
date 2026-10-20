#!/bin/sh
# docs-guardian file-edit watcher.
# Fires after every Write tool call. If the file is a material surface,
# injects a mandatory requirement into the session:
#   1. Run docs-guardian before completing this task.
#   2. End the final response with the structured declaration line.

INPUT=$(cat)
FILEPATH=$(echo "$INPUT" | node -pe "JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).filepath" 2>/dev/null || echo "")

if [ -z "$FILEPATH" ]; then
  echo '{"suppress": true}'
  exit 0
fi

IS_MATERIAL=0
case "$FILEPATH" in
  *AGENTS.md|*NFA_ALIGNMENT.md|*CLAUDE.md|*README.md) IS_MATERIAL=1 ;;
  *docs/INGEST-CONTRACT.md|*docs/AUTHORITY_AND_REFERENCE_MAP.md) IS_MATERIAL=1 ;;
  *docs/PRODUCT_REQUIREMENTS.md|*docs/DECISIONS_AND_STOP_GATES.md) IS_MATERIAL=1 ;;
  *docs/SECURITY-AND-DATA-HANDLING.md|*docs/ANDROID_TOOLCHAIN_BASELINE.md) IS_MATERIAL=1 ;;
  *docs/contracts/phone-alerts.contract.snapshot.json) IS_MATERIAL=1 ;;
  *app/src/main/AndroidManifest.xml) IS_MATERIAL=1 ;;
  *app/src/main/java/com/nfaalerts/collector/*) IS_MATERIAL=1 ;;
  *app/schemas/*) IS_MATERIAL=1 ;;
  *build.gradle.kts|*settings.gradle.kts|*build.gradle) IS_MATERIAL=1 ;;
esac

if [ "$IS_MATERIAL" -eq 0 ]; then
  echo '{"suppress": true}'
  exit 0
fi

cat <<'EOF'
{
  "message": "⚠️  DOCS-GUARDIAN REQUIRED — material file edited.\n\nBefore this task is complete you MUST:\n1. Invoke: Task(subagent_type=docs-guardian, run_in_background=false)\n2. Wait for its Completion gate to read CLEAR.\n3. End your FINAL response with this exact line:\n\n   Docs: CLEAR\n\n   or if not yet run:\n\n   Docs: NOT RUN — [reason]\n\nThis declaration is mandatory. The stop hook will re-fire if it is missing."
}
EOF
