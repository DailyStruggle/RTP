# reset-rtp-config.ps1
#
# Wipes the plugin-owned config tree out of every devstack instance so the
# next `docker compose up` lets the freshly-built jar re-extract the baseline
# (config.yml, messages.yml, lang/, worlds/, regions/, effects/, safety.yml,
# economy.yml, integrations.yml, language.yml, logging.yml, metrics.yml,
# performance.yml, docs/) from scratch.
#
# Devstack layout reminder (see docker-compose.yml):
#   ./<instance>/plugins/            -> bind-mounted to /data/plugins
#   ./<instance>/rtp-config/         -> seed-only dir; today the only file
#                                       used is network.yml, copied into
#                                       /data/plugins/RTP/network.yml by the
#                                       container entrypoint on every boot.
#
# So the plugin's runtime config lives entirely under
# ./<instance>/plugins/RTP/ on the host. Deleting that directory is safe and
# idempotent: the container's entrypoint shim re-seeds network.yml from
# ./<instance>/rtp-config/network.yml, and the jar re-extracts every other
# baseline file the first time the plugin loads.
#
# Usage (from repo root or devstack dir):
#   .\rtp-proxy\devstack\reset-rtp-config.ps1
#   .\rtp-proxy\devstack\reset-rtp-config.ps1 -IncludeDatabase   # also wipes runtime DB
#
# Safe to re-run; idempotent. Do NOT run while the devstack is up.

param(
    [switch]$IncludeDatabase
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Paper/Folia instances wired into docker-compose.yml. backend-c (Fabric)
# is intentionally excluded: its compose service binds only `mods/` and
# `world/` from the host, and its RTP runtime config tree lives inside the
# container at `/data/config/rtp/` (not bind-mounted). That tree is
# discarded on `docker compose down` + recreate, so backend-c needs no
# host-side reset to pick up a fresh `messages.yml` etc. - rebuild the
# Fabric mod jar, drop it into `./backend-c/mods/`, and `docker compose
# up --force-recreate backend-c` (or a plain `down -v` + `up`) is enough.
$instances = @('backend-a', 'backend-b', 'lobby-a', 'lobby-b')

foreach ($name in $instances) {
    $pluginRtp = Join-Path $scriptDir "$name\plugins\RTP"
    if (-not (Test-Path $pluginRtp)) {
        Write-Host "[$name] no plugins\RTP\ directory; skipping"
        continue
    }
    Write-Host "===== $name ====="

    if ($IncludeDatabase) {
        # Nuke the whole directory; entrypoint will recreate it and re-seed
        # network.yml on next boot.
        Write-Host "  del dir plugins\RTP\ (incl. database)"
        Remove-Item $pluginRtp -Recurse -Force
        continue
    }

    # Default behavior: wipe everything under plugins\RTP\ EXCEPT database\,
    # which holds runtime SQLite state (per-container) that operators usually
    # want to preserve between resets.
    Get-ChildItem $pluginRtp -Force | ForEach-Object {
        if ($_.Name -eq 'database') {
            Write-Host "  keep $($_.Name)\ (runtime; pass -IncludeDatabase to wipe)"
        } else {
            if ($_.PSIsContainer) {
                Write-Host "  del dir $($_.Name)\"
            } else {
                Write-Host "  del $($_.Name)"
            }
            Remove-Item $_.FullName -Recurse -Force
        }
    }
}

Write-Host ""
Write-Host "Done. Run 'docker compose up' (from rtp-proxy/devstack) to let"
Write-Host "the plugin self-populate the baseline on first boot."
