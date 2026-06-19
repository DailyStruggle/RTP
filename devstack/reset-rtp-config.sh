#!/usr/bin/env bash
# reset-rtp-config.sh
#
# Bash port of reset-rtp-config.ps1. Wipes the plugin-owned config tree out of
# every devstack instance so the next `docker compose up` lets the freshly-built
# jar re-extract the baseline from scratch.
#
# Devstack layout reminder (see docker-compose.yml):
#   ./<instance>/plugins/    -> bind-mounted to /data/plugins
#   ./<instance>/rtp-config/ -> seed-only dir (network.yml re-seeded on boot)
#
# Usage (from repo root or devstack dir):
#   ./rtp-proxy/devstack/reset-rtp-config.sh
#   ./rtp-proxy/devstack/reset-rtp-config.sh --include-database
#
# Safe to re-run; idempotent. Do NOT run while the devstack is up.
set -euo pipefail

IncludeDatabase=0
while [ $# -gt 0 ]; do
  case "$1" in
    --include-database) IncludeDatabase=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Paper/Folia instances wired into docker-compose.yml. All three backends and
# both lobbies bind-mount plugins/, so their RTP runtime config lives at
# ./<instance>/plugins/RTP/ and is wiped here for a fresh baseline.
instances=(backend-a backend-b backend-c lobby-a lobby-b)

for name in "${instances[@]}"; do
  pluginRtp="$scriptDir/$name/plugins/RTP"
  if [ ! -d "$pluginRtp" ]; then
    echo "[$name] no plugins/RTP/ directory; skipping"
    continue
  fi
  echo "===== $name ====="

  if [ "$IncludeDatabase" -eq 1 ]; then
    echo "  del dir plugins/RTP/ (incl. database)"
    rm -rf "$pluginRtp"
    continue
  fi

  # Default: wipe everything under plugins/RTP/ EXCEPT database/ (runtime SQLite).
  for entry in "$pluginRtp"/* "$pluginRtp"/.[!.]* "$pluginRtp"/..?*; do
    [ -e "$entry" ] || continue
    base="$(basename "$entry")"
    if [ "$base" = "database" ]; then
      echo "  keep database/ (runtime; pass --include-database to wipe)"
    else
      if [ -d "$entry" ]; then echo "  del dir $base/"; else echo "  del $base"; fi
      rm -rf "$entry"
    fi
  done
done

echo ""
echo "Done. Run 'docker compose up' (from devstack) to let"
echo "the plugin self-populate the baseline on first boot."
