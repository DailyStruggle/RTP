#requires -Version 5.1
<#
.SYNOPSIS
    Standalone acceptance test verifying LeafRTPGuiAddon chest menu interaction
    via headless Mineflayer bot.

.DESCRIPTION
    1. Verifies devstack lobby-a has LeafRTPGuiAddon configured with menuStyle: "chest".
    2. Runs the headless Mineflayer bot targeting proxy-a (or specified host/port) with --gui.
    3. Issues a bare /rtp command from the lobby.
    4. Confirms windowOpen event is received with the expected chest menu.
    5. Clicks the destination slot and confirms cross-server teleportation.
    6. Outputs structured telemetry: window open latency, slot clicked, total transfer
       latency, and destination coordinates.
#>
[CmdletBinding()]
param(
    [string]$TargetHost = '127.0.0.1',
    [int]$TargetPort = 25577,
    [int]$TimeoutSeconds = 35,
    [string]$Username = 'RtpGuiAcceptanceBot',
    [string]$TargetRegion = 'backend-a:default',
    [string]$Slot = $null,
    [int]$DebounceMs = 250,
    [string]$GuiScenario = 'teleport',
    [ValidateSet('backend-a', 'backend-b', 'backend-c', 'backend-d', 'lobby-a', 'lobby-b')]
    [string]$TargetServer = $null,
    [switch]$Lite,
    [switch]$AssertEffects
)

$ErrorActionPreference = 'Stop'
$devstack = $PSScriptRoot

Write-Host "=== LeafRTPGuiAddon Headless Bot Acceptance Test ===" -ForegroundColor Cyan
if ($TargetServer) {
    Write-Host "[target] Directed backend platform target: $TargetServer" -ForegroundColor Cyan
}

# 1. Verify target server and lobby configuration
$configsToVerify = @()
if ($TargetServer) {
    if ($TargetServer -in @('backend-c', 'backend-d')) {
        $configsToVerify += @{ Server = $TargetServer; Path = (Join-Path $devstack "$TargetServer\rtp-config\addons\guimenu.yml") }
    } else {
        $configsToVerify += @{ Server = $TargetServer; Path = (Join-Path $devstack "$TargetServer\plugins\RTP\addons\guimenu.yml") }
    }
}
$configsToVerify += @{ Server = 'lobby-a'; Path = (Join-Path $devstack 'lobby-a\plugins\RTP\addons\guimenu.yml') }

foreach ($entry in $configsToVerify) {
    $cfgPath = $entry.Path
    $srvName = $entry.Server
    if (Test-Path $cfgPath) {
        $cfgContent = Get-Content -Raw $cfgPath
        if ($cfgContent -match 'menuStyle:\s*"chest"') {
            Write-Host "[verify] $srvName guimenu.yml menuStyle is 'chest' (OK)" -ForegroundColor Green
        } else {
            Write-Host "[verify] $srvName guimenu.yml menuStyle is not 'chest'. Updating to 'chest'..." -ForegroundColor Yellow
            $cfgContent = $cfgContent -replace 'menuStyle:\s*"[^"]*"', 'menuStyle: "chest"'
            Set-Content -Path $cfgPath -Value $cfgContent -Encoding UTF8
        }
    } else {
        Write-Host "[verify] Notice: $cfgPath not found on host filesystem (using container volume/seed)" -ForegroundColor DarkGray
    }
}

# 2. Check Node.js and dependencies
$botScript = Join-Path $devstack 'clients\mineflayer-bot.js'
$clientsDir = Join-Path $devstack 'clients'
$nodeCmd = Get-Command 'node' -ErrorAction SilentlyContinue
$dockerCmd = Get-Command 'docker' -ErrorAction SilentlyContinue

if (-not $nodeCmd -and -not $dockerCmd) {
    Write-Error "[error] Node.js or Docker is required to run the headless Mineflayer client."
    exit 1
}

if (-not (Test-Path $botScript)) {
    Write-Error "[error] Mineflayer client script not found at: $botScript"
    exit 1
}

$nodeModules = Join-Path $clientsDir 'node_modules'
$npmCmd = Get-Command 'npm' -ErrorAction SilentlyContinue
if ($nodeCmd -and -not (Test-Path $nodeModules) -and $npmCmd) {
    Write-Host "[client] Installing npm dependencies in $clientsDir..." -ForegroundColor Cyan
    & npm --prefix $clientsDir install --silent --no-audit | Out-Null
}

# 3. Build command arguments
$botArgs = @(
    '--host', $TargetHost,
    '--port', $TargetPort,
    '--username', $Username,
    '--timeout', $TimeoutSeconds,
    '--gui',
    '--gui-scenario', $GuiScenario,
    '--debounce-ms', $DebounceMs
)

if ($TargetRegion) {
    $botArgs += @('--target-region', $TargetRegion)
}
if ($TargetServer) {
    $botArgs += @('--target-server', $TargetServer)
}
if ($Slot -ne $null -and $Slot -ne '') {
    $botArgs += @('--slot', $Slot)
}
if ($Lite) {
    $botArgs += '--lite'
}
if ($AssertEffects) {
    $botArgs += '--assert-effects'
}

Write-Host "[client] Launching Mineflayer bot in GUI mode (host: $TargetHost, port: $TargetPort)..." -ForegroundColor Cyan
if ($nodeCmd) {
    $botProcess = & node $botScript @botArgs 2>&1
} else {
    Write-Host "[client] Host node not found; running via Docker node:20 container..." -ForegroundColor Cyan
    $botProcess = & docker run --rm --network host -v "${clientsDir}:/app" -w /app node:20 node mineflayer-bot.js @botArgs 2>&1
}
$botOutput = $botProcess | Out-String
Write-Host $botOutput

# 4. Evaluate results & telemetry
if ($botOutput -match '"status"\s*:\s*"PASS"') {
    Write-Host "`n================================================" -ForegroundColor Green
    Write-Host "  GUI ACCEPTANCE TEST PASSED" -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green

    # Extract JSON telemetry
    $match = [regex]::Match($botOutput, '\{"status"\s*:\s*"PASS"[^}]+\}')
    if ($match.Success) {
        try {
            $telemetry = $match.Value | ConvertFrom-Json
            Write-Host "`nTelemetry Summary:" -ForegroundColor Cyan
            Write-Host "  Interaction mode      : $($telemetry.mode)" -ForegroundColor Green
            Write-Host "  Window open latency   : $($telemetry.windowOpenLatencyMs) ms" -ForegroundColor Green
            Write-Host "  Slot clicked          : $($telemetry.slotClicked) (Item: $($telemetry.itemClicked))" -ForegroundColor Green
            Write-Host "  Total transfer latency: $($telemetry.teleportLatencyMs) ms" -ForegroundColor Green
            Write-Host "  Destination coords    : X=$($telemetry.targetX), Y=$($telemetry.targetY), Z=$($telemetry.targetZ)" -ForegroundColor Green
            if ($telemetry.effects) {
                Write-Host "  Effects observed      : $($telemetry.effects.soundCount) sounds, $($telemetry.effects.particleCount) particles, $($telemetry.effects.titleCount) titles" -ForegroundColor Green
            }
        } catch {
            Write-Host "  Raw PASS summary captured." -ForegroundColor Green
        }
    }
    exit 0
} else {
    Write-Host "`n================================================" -ForegroundColor Red
    Write-Host "  GUI ACCEPTANCE TEST FAILED" -ForegroundColor Red
    Write-Host "================================================" -ForegroundColor Red
    exit 1
}
