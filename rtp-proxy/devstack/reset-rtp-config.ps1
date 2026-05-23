# reset-rtp-config.ps1
#
# Wipes auto-populatable files out of every devstack instance's rtp-config/
# directory, keeping ONLY the genuinely per-instance file (network.yml).
# The plugin re-extracts the baseline (config.yml, messages.yml, lang/,
# worlds/, regions/, effects/, safety.yml, economy.yml, integrations.yml,
# language.yml, logging.yml, metrics.yml, performance.yml, docs/) on the
# next boot, so running this before `docker compose up` guarantees the
# devstack picks up the current jar's baseline instead of a stale shadow
# from a prior session.
#
# Usage (from repo root or devstack dir):
#   .\rtp-proxy\devstack\reset-rtp-config.ps1
#   .\rtp-proxy\devstack\reset-rtp-config.ps1 -IncludeDatabase   # also wipes runtime DB
#
# Safe to re-run; idempotent.

param(
    [switch]$IncludeDatabase
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$instances = @('backend-a', 'backend-b', 'lobby-a', 'lobby-b')

# Files at the top level of rtp-config/ that the plugin owns. network.yml
# is intentionally excluded because it carries per-instance identity
# (serverId / proxyId / transport host / lobby flag) that the plugin
# cannot regenerate from baseline.
$preservedTopLevel = @('network.yml')

# Subdirectories the plugin owns end-to-end. database/ is runtime state
# (per-container SQLite), only nuked when -IncludeDatabase is passed.
$pluginOwnedDirs   = @('lang', 'worlds', 'regions', 'effects', 'docs')
$runtimeDirs       = @('database')

foreach ($name in $instances) {
    $root = Join-Path $scriptDir "$name\rtp-config"
    if (-not (Test-Path $root)) {
        Write-Host "[$name] no rtp-config/ directory; skipping"
        continue
    }
    Write-Host "===== $name ====="

    # Top-level *.yml except preserved
    Get-ChildItem $root -File -Filter '*.yml' | ForEach-Object {
        if ($preservedTopLevel -notcontains $_.Name) {
            Write-Host "  del $($_.Name)"
            Remove-Item $_.FullName -Force
        } else {
            Write-Host "  keep $($_.Name)"
        }
    }

    # Plugin-owned subdirs
    foreach ($d in $pluginOwnedDirs) {
        $p = Join-Path $root $d
        if (Test-Path $p) {
            Write-Host "  del dir $d\"
            Remove-Item $p -Recurse -Force
        }
    }

    # Runtime DB (opt-in)
    if ($IncludeDatabase) {
        foreach ($d in $runtimeDirs) {
            $p = Join-Path $root $d
            if (Test-Path $p) {
                Write-Host "  del dir $d\ (runtime)"
                Remove-Item $p -Recurse -Force
            }
        }
    }
}

Write-Host ""
Write-Host "Done. Run 'docker compose up' (from rtp-proxy/devstack) to let"
Write-Host "the plugin self-populate the baseline on first boot."
