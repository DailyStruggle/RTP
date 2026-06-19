#requires -Version 5.1
<#
.SYNOPSIS
    Opt-in: drop the LeafRTPGuiAddon (DonutSMP-style menu) into the devstack's
    Bukkit-family instances. OFF by default - the normal devstack does not ship it.

.DESCRIPTION
    Builds addons:LeafRTPGuiAddon:rtp-gui-bukkit (a shaded plugin jar that bundles
    rtp-gui-common) and copies it into each Bukkit backend/lobby plugins dir that is
    bind-mounted into the container at /data/plugins. All three backends now run
    the Bukkit/Paper-family platform, so each receives the addon.

    Run before `docker compose up`. Use -Remove to take it back out.

.EXAMPLE
    .\add-gui-addon.ps1            # build + install into backend-a/b/c and lobby-a/b
    .\add-gui-addon.ps1 -Remove    # remove the addon jar from those instances
    .\add-gui-addon.ps1 -SkipBuild # install the already-built jar
#>
[CmdletBinding()]
param(
    [switch]$Remove,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$devstack = $PSScriptRoot
$repoRoot = Split-Path -Parent $devstack

# Bukkit-family instances (all three backends run Paper/Folia).
$targets = @('backend-a', 'backend-b', 'backend-c', 'lobby-a', 'lobby-b')

# plugin.yml declares name: LeafRTPGuiAddon, so any LeafRTPGuiAddon*.jar / rtp-gui-bukkit*.jar
# is "ours" for the remove pass.
$jarGlobs = @('LeafRTPGuiAddon*.jar', 'rtp-gui-bukkit*.jar')

if ($Remove) {
    foreach ($t in $targets) {
        $pluginsDir = Join-Path $devstack "$t\plugins"
        if (-not (Test-Path $pluginsDir)) { continue }
        foreach ($glob in $jarGlobs) {
            Get-ChildItem -Path $pluginsDir -Filter $glob -File -ErrorAction SilentlyContinue |
                ForEach-Object {
                    Remove-Item -Force $_.FullName
                    Write-Host "removed $($_.FullName)"
                }
        }
    }
    Write-Host "LeafRTPGuiAddon removed from devstack Bukkit instances."
    return
}

if (-not $SkipBuild) {
    Write-Host "Building addons:LeafRTPGuiAddon:rtp-gui-bukkit:shadowJar ..."
    Push-Location $repoRoot
    try {
        & .\gradlew ':addons:LeafRTPGuiAddon:rtp-gui-bukkit:shadowJar' --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

$libsDir = Join-Path $repoRoot 'addons\LeafRTPGuiAddon\rtp-gui-bukkit\build\libs'
$jar = Get-ChildItem -Path $libsDir -Filter 'rtp-gui-bukkit-*.jar' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) {
    throw "Built jar not found under $libsDir. Run without -SkipBuild, or build the module first."
}

foreach ($t in $targets) {
    $pluginsDir = Join-Path $devstack "$t\plugins"
    if (-not (Test-Path $pluginsDir)) {
        New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
    }
    # Clear any prior copy so a rename/version bump does not leave two jars.
    foreach ($glob in $jarGlobs) {
        Get-ChildItem -Path $pluginsDir -Filter $glob -File -ErrorAction SilentlyContinue |
            ForEach-Object { Remove-Item -Force $_.FullName }
    }
    Copy-Item -Force $jar.FullName (Join-Path $pluginsDir 'LeafRTPGuiAddon.jar')
    Write-Host "installed $($jar.Name) -> $t\plugins\LeafRTPGuiAddon.jar"
}

Write-Host ""
Write-Host "Done. Run 'docker compose up' (or restart the instances) to load it."
Write-Host "guimenu.yml self-creates on first boot in each instance's plugins/RTP/ folder."
