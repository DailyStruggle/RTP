#!/usr/bin/env bash
# bake-lobby-world.sh
#
# Bash port of bake-lobby-world.ps1. Archives a populated devstack lobby world
# (lobby-a/world or lobby-b/world) into shared/lobby-world.zip so future
# `docker compose up` runs (with docker-compose.lobby-world.yml layered by
# run-acceptance.sh) boot both lobbies from the baked world.
#
# This is the manual half of the schematic workflow: it does NOT paste a
# schematic. Paste it in-game first (see the .ps1 header), then bake.
#
# Usage:
#   ./bake-lobby-world.sh [--source lobby-a|lobby-b] [--force] [--output PATH]
set -euo pipefail

Source="lobby-a"
Force=0
Output=""
while [ $# -gt 0 ]; do
  case "$1" in
    --source) Source="$2"; shift 2 ;;
    --force)  Force=1; shift ;;
    --output) Output="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
case "$Source" in lobby-a|lobby-b) ;; *) echo "--source must be lobby-a or lobby-b" >&2; exit 2 ;; esac

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DevstackRoot="$(cd "$scriptDir/.." && pwd)"
[ -f "$DevstackRoot/docker-compose.yml" ] || { echo "Could not locate devstack root from $scriptDir (expected docker-compose.yml in parent)." >&2; exit 1; }

WorldDir="$DevstackRoot/$Source/world"
[ -d "$WorldDir" ] || { echo "Source world directory not found: $WorldDir. Boot $Source via 'docker compose up -d' and paste the schematic before baking." >&2; exit 1; }
[ -f "$WorldDir/level.dat" ] || { echo "$WorldDir exists but contains no level.dat - the lobby has not been booted yet, or the world is corrupt." >&2; exit 1; }

[ -n "$Output" ] || Output="$DevstackRoot/shared/lobby-world.zip"

if [ -e "$Output" ]; then
  if [ "$Force" -eq 0 ]; then
    echo "[bake] $Output already exists. Re-run with --force to overwrite."
    exit 0
  fi
  rm -f "$Output"
fi

# Refuse to bake if a container still holds session.lock (touched <30s ago).
SessionLock="$WorldDir/session.lock"
if [ -f "$SessionLock" ]; then
  nowEpoch="$(date +%s)"
  lockEpoch="$(stat -c %Y "$SessionLock" 2>/dev/null || stat -f %m "$SessionLock")"
  age=$(( nowEpoch - lockEpoch ))
  if [ "$age" -lt 30 ]; then
    echo "[bake] $SessionLock was touched <30s ago - $Source may still be running. Stop the stack first ('docker compose stop $Source') and retry." >&2
    echo "session.lock on $Source is fresh; refusing to bake a live world." >&2
    exit 1
  fi
  echo "[bake] Stale session.lock detected (older than 30s); proceeding."
fi

# Sanity check: world dirs typically run 5-100 MB. < 1 MB means empty paste.
sizeBytes="$(du -sb "$WorldDir" 2>/dev/null | awk '{print $1}')"
[ -n "$sizeBytes" ] || sizeBytes=0
sizeMb="$(awk -v b="$sizeBytes" 'BEGIN{printf "%.2f", b/1048576}')"
if awk -v b="$sizeBytes" 'BEGIN{exit !(b/1048576 < 1)}'; then
  if [ "$Force" -eq 0 ]; then
    echo "[bake] $WorldDir is only $sizeMb MB - looks empty. Paste the schematic first, or re-run with --force."
    exit 0
  fi
  echo "[bake] WARN $WorldDir is only $sizeMb MB; baking anyway because --force."
fi

echo "[bake] archiving $WorldDir ($sizeMb MB) -> $Output ..."

command -v zip >/dev/null 2>&1 || { echo "[bake] 'zip' command not found; install zip and retry." >&2; exit 1; }

# Stage under world/ so the archive is self-describing (itzg WORLD handler
# accepts a single world/ dir at root). zip on POSIX writes forward-slash
# entry names, so no backslash workaround is needed (unlike the .ps1).
mkdir -p "$(dirname "$Output")"
Staging="$(mktemp -d)"
trap 'rm -rf "$Staging"' EXIT
StagedWorld="$Staging/world"
mkdir -p "$StagedWorld"
cp -a "$WorldDir/." "$StagedWorld/"
rm -f "$StagedWorld/session.lock"

( cd "$Staging" && zip -q -X -r "$Output" world )

outBytes="$(stat -c %s "$Output" 2>/dev/null || stat -f %z "$Output")"
outSize="$(awk -v b="$outBytes" 'BEGIN{printf "%.2f", b/1048576}')"
echo "[bake] PASS - wrote $Output ($outSize MB). Next 'docker compose up' will boot lobbies from this world."
