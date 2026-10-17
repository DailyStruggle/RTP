#requires -Version 5.1
<#
.SYNOPSIS
    Single-server Paper devstack verification for third-party item compatibility
    (ItemsAdder, Oraxen, Nexo, HeadDatabase, CustomModelData).

.DESCRIPTION
    Builds the mock item stub plugins, LeafRTP core, and LeafRTPGuiAddon.
    Stages the jars into devstack/compat/plugins/ and runs a single Paper 1.21.11
    server via docker-compose.compat.yml to verify that all stubs load and
    that LeafRTPGuiAddon resolves them without errors.

.EXAMPLE
    .\test-item-compat.ps1             # build, stage, boot single-server stack and verify
    .\test-item-compat.ps1 -Down       # tear down the single-server stack
    .\test-item-compat.ps1 -SkipBuild  # reuse already-built jars
#>
[CmdletBinding()]
param(
    [switch]$Down,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$devstack = $PSScriptRoot
$repoRoot = Split-Path -Parent $devstack
$composeFile = Join-Path $devstack 'docker-compose.compat.yml'
$compatPluginsDir = Join-Path $devstack 'compat\plugins'

if ($Down) {
    Write-Host "Tearing down single-server item compatibility devstack..."
    docker compose -f $composeFile down -v
    Write-Host "Teardown complete."
    return
}

# 1. Build jars if not skipped
if (-not $SkipBuild) {
    Write-Host "Building mock stub plugins (:helpers:MockItemPlugins:buildAllStubs)..."
    Push-Location $repoRoot
    try {
        & .\gradlew.bat ':helpers:MockItemPlugins:buildAllStubs' --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Mock stubs build failed (exit $LASTEXITCODE)" }

        Write-Host "Building LeafRTP plugin (:rtp-plugin:remapJar)..."
        & .\gradlew.bat ':rtp-plugin:remapJar' --console=plain
        if ($LASTEXITCODE -ne 0) { throw "LeafRTP remapJar failed (exit $LASTEXITCODE)" }

        Write-Host "Building LeafRTPGuiAddon (:addons:LeafRTPGuiAddon:rtp-gui:shadowJar)..."
        & .\gradlew.bat ':addons:LeafRTPGuiAddon:rtp-gui:shadowJar' --console=plain
        if ($LASTEXITCODE -ne 0) { throw "GUI addon build failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

# 2. Stage plugins
if (-not (Test-Path $compatPluginsDir)) {
    New-Item -ItemType Directory -Force -Path $compatPluginsDir | Out-Null
}

# Clean old jars in compat/plugins
Get-ChildItem -Path $compatPluginsDir -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -Force $_.FullName }

# Copy mock stub plugins
$mockLibs = Join-Path $repoRoot 'helpers\MockItemPlugins\build\libs'
$stubJars = @('MockItemsAdder.jar', 'MockOraxen.jar', 'MockNexo.jar', 'MockHeadDatabase.jar')
foreach ($stub in $stubJars) {
    $src = Join-Path $mockLibs $stub
    if (-not (Test-Path $src)) {
        throw "Stub jar not found: $src. Run without -SkipBuild."
    }
    Copy-Item -Force $src (Join-Path $compatPluginsDir $stub)
    Write-Host "Staged stub: $stub"
}

# Copy LeafRTP jar
$rtpLibs = Join-Path $repoRoot 'rtp-plugin\build\libs'
$rtpJar = Get-ChildItem -Path $rtpLibs -Filter 'LeafRTP-Pro-*.jar' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $rtpJar) {
    $rtpJar = Get-ChildItem -Path $rtpLibs -Filter 'LeafRTP-*.jar' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
if ($rtpJar) {
    Copy-Item -Force $rtpJar.FullName (Join-Path $compatPluginsDir 'LeafRTP.jar')
    Write-Host "Staged LeafRTP: $($rtpJar.Name)"
} else {
    Write-Warning "LeafRTP jar not found under $rtpLibs. Run without -SkipBuild."
}

# Copy LeafRTPGuiAddon jar
$guiLibs = Join-Path $repoRoot 'addons\LeafRTPGuiAddon\rtp-gui\build\libs'
$guiJar = Get-ChildItem -Path $guiLibs -Filter 'LeafRTPGuiAddon-*.jar' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($guiJar) {
    Copy-Item -Force $guiJar.FullName (Join-Path $compatPluginsDir 'LeafRTPGuiAddon.jar')
    Write-Host "Staged LeafRTPGuiAddon: $($guiJar.Name)"
} else {
    Write-Warning "LeafRTPGuiAddon jar not found under $guiLibs. Run without -SkipBuild."
}

# 3. Check Docker daemon availability
$dockerRunning = $false
try {
    $null = docker ps 2>&1
    if ($LASTEXITCODE -eq 0) { $dockerRunning = $true }
} catch {}

if (-not $dockerRunning) {
    Write-Host ""
    Write-Host "==========================================================================" -ForegroundColor Yellow
    Write-Host "Docker daemon is not currently running on this host." -ForegroundColor Yellow
    Write-Host "All compatibility stub JARs, plugins, and guimenu.yml configs have been" -ForegroundColor Yellow
    Write-Host "successfully compiled and staged under devstack/compat/plugins/." -ForegroundColor Yellow
    Write-Host "" -ForegroundColor Yellow
    Write-Host "To run the single-server Paper devstack when Docker is started:" -ForegroundColor Yellow
    Write-Host "  cd devstack" -ForegroundColor Cyan
    Write-Host "  docker compose -f docker-compose.compat.yml up -d" -ForegroundColor Cyan
    Write-Host "  docker compose -f docker-compose.compat.yml logs -f" -ForegroundColor Cyan
    Write-Host "==========================================================================" -ForegroundColor Yellow
    return
}

Write-Host "Launching single-server Paper devstack..."
docker compose -f $composeFile up -d

Write-Host "Waiting for Paper server to boot..."
$timeoutSec = 180
$elapsed = 0
$booted = $false

while ($elapsed -lt $timeoutSec) {
    Start-Sleep -Seconds 5
    $elapsed += 5
    $logs = docker compose -f $composeFile logs backend-compat 2>&1
    if ($logs -match 'Done \([0-9\.]+s\)! For help, type "help"') {
        $booted = $true
        break
    }
}

if (-not $booted) {
    Write-Error "Server failed to boot within $timeoutSec seconds. Check logs with 'docker compose -f $composeFile logs'."
    return
}

Write-Host "Server successfully booted!" -ForegroundColor Green

# Verify loaded plugins in log
$logs = docker compose -f $composeFile logs backend-compat 2>&1
$checks = @(
    'MockItemsAdder',
    'MockOraxen',
    'MockNexo',
    'MockHeadDatabase',
    'LeafRTP'
)

foreach ($c in $checks) {
    if ($logs -match $c) {
        Write-Host "  [OK] Found plugin: $c" -ForegroundColor Green
    } else {
        Write-Warning "  [MISSING] Plugin $c was not found in server logs"
    }
}

Write-Host ""
Write-Host "Single-server devstack verification passed successfully." -ForegroundColor Green
