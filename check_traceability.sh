#!/usr/bin/env bash
# check_traceability.sh
# Fails with exit code 1 if any REQ-* ID found in a REQUIREMENTS.md file
# is not present as a row in docs/TRACEABILITY.md.
#
# Usage: ./check_traceability.sh
# Run from the project root.

set -euo pipefail

REQUIREMENTS_FILES=(
  "docs/dev/REQUIREMENTS.md"
  "rtp-api/REQUIREMENTS.md"
  "rtp-core/REQUIREMENTS.md"
  "rtp-spigot/REQUIREMENTS.md"
  "rtp-paper/REQUIREMENTS.md"
  "rtp-folia/REQUIREMENTS.md"
)
TRACEABILITY_FILE="docs/dev/TRACEABILITY.md"

# Extract all REQ-* IDs from requirements files (bold label prefix: **REQ-...)
req_ids=$(grep -ahoE 'REQ-[A-Z]+-[A-Z]+-[0-9]+' "${REQUIREMENTS_FILES[@]}" | sort -u)

# Extract all REQ-* IDs present in the traceability matrix
traced_ids=$(grep -aoE 'REQ-[A-Z]+-[A-Z]+-[0-9]+' "$TRACEABILITY_FILE" | sort -u)

missing=()
while IFS= read -r id; do
  if ! grep -aqF "$id" "$TRACEABILITY_FILE"; then
    missing+=("$id")
  fi
done <<< "$req_ids"

if [ ${#missing[@]} -eq 0 ]; then
  echo "✅ All requirements are traced in $TRACEABILITY_FILE."
  exit 0
else
  echo "❌ The following requirement IDs are missing from $TRACEABILITY_FILE:"
  for id in "${missing[@]}"; do
    echo "   - $id"
  done
  echo ""
  echo "Add a row for each missing ID to $TRACEABILITY_FILE before merging."
  exit 1
fi
