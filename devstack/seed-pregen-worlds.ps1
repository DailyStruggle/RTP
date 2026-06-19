#requires -Version 5.1
<#
.SYNOPSIS
    Seed each devstack backend with PRE-GENERATED spawn-area chunks so the
    multi-world /rtp demo never hits null-chunk timeouts.

.DESCRIPTION
    Each backend's single `default` region (backend-a -> Overworld, backend-b ->
    Nether, backend-c -> End) is centered on (0,0) with radii well inside +/-512
    blocks. A freshly-booted itzg world generates those chunks on demand, and
    under load the async chunk loads can time out and surface as null-chunk
    pipeline failures. All three dimensions are still pre-generated per backend
    so the supplied world is complete regardless of which dimension a default
    region targets (backend-a -> Overworld, backend-b -> Nether,
    backend-c -> End).

    This script copies the pre-generated single-server world into each backend's
    GITIGNORED, bind-mounted world dirs, so the chunks the demo regions land in
    are already on disk. By default it copies the FULL world (every `.mca`), so
    there are plenty of `.mca` files for the L3 backlog cache (backlogCacheCap)
    to anvil-prefilter and to stop surfacing null-chunk timeouts.

    The world dirs (`world/`, `world_nether/`, `world_the_end/`) are already
    gitignored, so the supplied pre-generated world is never committed; it is
    purely a local runtime artifact.

    Pass -RegionRadius N (>= 0) to copy only the small spawn-area window instead
    (N=1 => the 3x3 block of `.mca` around the origin, ~120 MB/world). The full
    source overworld is ~14 GB; the window is cheaper but supplies too few
    `.mca` files for the backlog cache to do meaningful prefiltering.

    World layout (all backends run the Bukkit/Paper-family layout): overworld
    -> world/, Nether -> world_nether/, End -> world_the_end/ (separate
    top-level dirs).

    Run while the stack is DOWN (the world dirs are host binds). Idempotent.

.PARAMETER Source
    The pre-generated single-server world root containing world/, world_nether/,
    world_the_end/. Defaults to the RTP-Spigot 1.21.11 test server.

.PARAMETER RegionRadius
    How many region files out from the origin to copy on each axis. The default
    (-1) copies the FULL world (every `.mca`) so the backlog cache has real data
    to prefilter and Fabric stops hitting null chunks. A value >= 0 copies only
    that window: 0 => just r.0.0, 1 => the 3x3 grid (blocks -512..1023), etc.

.PARAMETER Backends
    Which backends to seed. Defaults to all three.

.EXAMPLE
    .\seed-pregen-worlds.ps1                       # full world (recommended)
    .\seed-pregen-worlds.ps1 -RegionRadius 1       # small spawn-area window only
    .\seed-pregen-worlds.ps1 -Backends backend-a,backend-b
#>
[CmdletBinding()]
param(
    [string]$Source = 'C:\GameServers\Minecraft\testServer\RTP-Spigot\1.21.11',
    [int]$RegionRadius = -1,
    [ValidateSet('backend-a', 'backend-b', 'backend-c')]
    [string[]]$Backends = @('backend-a', 'backend-b', 'backend-c')
)

$ErrorActionPreference = 'Stop'
$devstack = $PSScriptRoot

if (-not (Test-Path $Source)) {
    throw "Source world root not found: $Source. Pass -Source <path-to-pregenerated-server>."
}

# Copy the subset of region-style files (region/, entities/, poi/) whose
# r.X.Z coordinates fall within +/-RegionRadius of the origin.
function Copy-RegionSubset {
    param(
        [Parameter(Mandatory)] [string]$SrcWorld,
        [Parameter(Mandatory)] [string]$DstWorld,
        [Parameter(Mandatory)] [int]$Radius
    )
    if (-not (Test-Path $SrcWorld)) {
        Write-Host "    (source world missing: $SrcWorld) - skipping"
        return
    }
    New-Item -ItemType Directory -Force -Path $DstWorld | Out-Null

    # Full-world mode: copy the whole source world verbatim. This supplies every
    # `.mca` so the L3 backlog cache (backlogCacheCap) has real chunks to anvil-
    # prefilter and Fabric stops surfacing null-chunk timeouts.
    if ($Radius -lt 0) {
        Copy-Item -Recurse -Force (Join-Path $SrcWorld '*') $DstWorld
        $mca = (Get-ChildItem $DstWorld -Recurse -Filter *.mca -ErrorAction SilentlyContinue | Measure-Object).Count
        Write-Host ("    full copy: {0} .mca file(s)" -f $mca)
        return
    }

    # Top-level world files needed for the world to load cleanly.
    foreach ($f in @('level.dat', 'level.dat_old', 'paper-world.yml')) {
        $src = Join-Path $SrcWorld $f
        if (Test-Path $src) { Copy-Item -Force $src (Join-Path $DstWorld $f) }
    }
    # data/ holds idcounts / scoreboard / raids etc.; small and safe to copy.
    $srcData = Join-Path $SrcWorld 'data'
    if (Test-Path $srcData) {
        Copy-Item -Recurse -Force $srcData (Join-Path $DstWorld 'data')
    }

    $names = @()
    for ($x = -$Radius; $x -le $Radius; $x++) {
        for ($z = -$Radius; $z -le $Radius; $z++) {
            $names += "r.$x.$z.mca"
        }
    }

    foreach ($sub in @('region', 'entities', 'poi')) {
        $srcSub = Join-Path $SrcWorld $sub
        if (-not (Test-Path $srcSub)) { continue }
        $dstSub = Join-Path $DstWorld $sub
        New-Item -ItemType Directory -Force -Path $dstSub | Out-Null
        $copied = 0
        foreach ($n in $names) {
            $sf = Join-Path $srcSub $n
            if (Test-Path $sf) {
                Copy-Item -Force $sf (Join-Path $dstSub $n)
                $copied++
            }
        }
        Write-Host ("    {0}/: copied {1} of {2} spawn-area .mca" -f $sub, $copied, $names.Count)
    }
}

$srcOver   = Join-Path $Source 'world'
$srcNether = Join-Path $Source 'world_nether'
$srcEnd    = Join-Path $Source 'world_the_end'

foreach ($b in $Backends) {
    Write-Host "===== $b ====="
    $root = Join-Path $devstack $b

    # Paper / Folia: separate top-level dirs.
    Write-Host "  overworld -> world/"
    Copy-RegionSubset -SrcWorld $srcOver   -DstWorld (Join-Path $root 'world')           -Radius $RegionRadius
    Write-Host "  nether    -> world_nether/"
    Copy-RegionSubset -SrcWorld $srcNether -DstWorld (Join-Path $root 'world_nether')     -Radius $RegionRadius
    Write-Host "  end       -> world_the_end/"
    Copy-RegionSubset -SrcWorld $srcEnd    -DstWorld (Join-Path $root 'world_the_end')    -Radius $RegionRadius
}

Write-Host ""
Write-Host "Done. The demo regions' spawn-area chunks are now pre-generated."
Write-Host "Run 'docker compose up' (from devstack) to boot the stack."
