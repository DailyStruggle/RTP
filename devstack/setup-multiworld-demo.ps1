#requires -Version 5.1
<#
.SYNOPSIS
    One-shot pre-configuration of the devstack for the multi-server / multi-world
    RTP_GuiAddon demo.

.DESCRIPTION
    Wires up everything the "DonutSMP-style" GUI menu needs to show a rich,
    cross-server destination picker:

      1. Supplies a pre-generated world for every backend's overworld, Nether,
         and End (copied into the gitignored world dirs) so the default regions
         never hit null-chunk timeouts and the backlog cache has real `.mca`
         files to prefilter (delegates to seed-pregen-worlds.ps1, full copy by
         default).
      2. Installs the RTP_GuiAddon into the Bukkit-family instances so a bare
         /rtp opens the chest menu (delegates to add-gui-addon.ps1). Opt out
         with -NoGuiAddon.

    Each backend's single `default` region is committed under its
    rtp-config/regions/default.yml and seeded into the running containers by the
    docker-compose entrypoint shims - no action needed here. Each default region
    lands in a distinct dimension and carries a configurable displayName:

      backend-a (Paper)  : Overworld  ->  Verdant Wilds
      backend-b (Folia)  : Nether     ->  Ashen Wastes
      backend-c (Fabric) : End        ->  Void Reaches

    On a lobby (lobby-a / lobby-b) the menu shows the cross-server peer regions
    advertised by all three backends (each labelled by its displayName), so /rtp
    from the lobby dispatches across the network - the multi-server demo.

    Run while the stack is DOWN, then `docker compose up`.

.PARAMETER NoGuiAddon
    Opt out of installing the GUI addon (regions + pre-gen worlds only).

.PARAMETER Source
    Passed through to seed-pregen-worlds.ps1 (pre-generated world root).

.PARAMETER RegionRadius
    Passed through to seed-pregen-worlds.ps1. Default -1 copies the full world;
    pass a value >= 0 for a small spawn-area window only.

.PARAMETER SkipPregen
    Skip the world pre-generation step (e.g. you already ran it).

.PARAMETER SkipBuild
    Passed through to add-gui-addon.ps1 (install the already-built jar).

.EXAMPLE
    .\setup-multiworld-demo.ps1                 # full demo setup (+ GUI addon)
    .\setup-multiworld-demo.ps1 -NoGuiAddon     # regions + worlds, no GUI addon
    .\setup-multiworld-demo.ps1 -SkipPregen     # just (re)install the GUI addon
#>
[CmdletBinding()]
param(
    [switch]$NoGuiAddon,
    [string]$Source = 'C:\GameServers\Minecraft\testServer\RTP-Spigot\1.21.11',
    [int]$RegionRadius = -1,
    [switch]$SkipPregen,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$devstack = $PSScriptRoot

Write-Host "=== RTP multi-server / multi-world GUI demo setup ===" -ForegroundColor Cyan

if (-not $SkipPregen) {
    Write-Host "`n[1/2] Supplying pre-generated world(s) ..." -ForegroundColor Cyan
    & (Join-Path $devstack 'seed-pregen-worlds.ps1') -Source $Source -RegionRadius $RegionRadius
} else {
    Write-Host "`n[1/2] -SkipPregen: leaving world dirs as-is." -ForegroundColor Yellow
}

if (-not $NoGuiAddon) {
    Write-Host "`n[2/2] Installing RTP_GuiAddon into Bukkit-family instances ..." -ForegroundColor Cyan
    $guiArgs = @{}
    if ($SkipBuild) { $guiArgs['SkipBuild'] = $true }
    & (Join-Path $devstack 'add-gui-addon.ps1') @guiArgs
} else {
    Write-Host "`n[2/2] -NoGuiAddon: skipping GUI addon install." -ForegroundColor Yellow
}

Write-Host "`nDone. Next:" -ForegroundColor Green
Write-Host "  cd `"$devstack`""
Write-Host "  docker compose up -d"
Write-Host "  # connect a 1.21.x client to localhost:25577 (proxy-a) -> lands on a lobby"
Write-Host "  # run /rtp to open the GUI; lobby shows cross-server peer regions,"
Write-Host "  # /server backend-a|backend-b|backend-c then /rtp teleports into that"
Write-Host "  # backend's default region (Overworld / Nether / End respectively)."
