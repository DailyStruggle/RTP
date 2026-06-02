<#
.SYNOPSIS
  Bakes a live lobby world into a reusable zip for the rtp-proxy devstack.

.DESCRIPTION
  Reads a populated `world/` directory under one of the devstack's lobby
  service bind mounts (`lobby-a/world` or `lobby-b/world`) and archives it
  to `shared/lobby-world.zip`. When that zip is present on the next
  `docker compose up`, `run-acceptance.ps1` automatically layers
  `docker-compose.lobby-world.yml`, which mounts the zip into each lobby
  container so both lobbies boot directly into the baked world (via the
  itzg/minecraft-server `WORLD` + `FORCE_WORLD_COPY` env contract).

  This is the manual half of the schematic workflow: this script does NOT
  paste a schematic for you. The intended cycle is:

    1. Drop a WorldEdit schematic into `shared/lobby-world/*.schem`
       (gitignored).
    2. `docker compose up -d` (without the lobby-world override).
    3. Install FastAsyncWorldEdit into `lobby-a/plugins/`, restart the
       lobby, join from a Minecraft client, run `//schem load <name>`,
       `//paste -a`, `/setworldspawn`, then disconnect and stop the stack.
    4. Run this script. It zips `lobby-a/world/` to `shared/lobby-world.zip`.
    5. Future boots automatically pick up the zip.

  Schematic-to-Anvil pasting on modern Paper has no fully headless tool, so
  the paste step stays manual. The bake step itself is fully scripted and
  idempotent.

.PARAMETER Source
  Which lobby world to archive. Defaults to `lobby-a`. Accepts `lobby-a`
  or `lobby-b`.

.PARAMETER Force
  Overwrite an existing `shared/lobby-world.zip` without prompting.

.PARAMETER Output
  Override the output path. Defaults to `<devstack>/shared/lobby-world.zip`.

.EXAMPLE
  .\bake-lobby-world.ps1

.EXAMPLE
  .\bake-lobby-world.ps1 -Source lobby-b -Force
#>
[CmdletBinding()]
param(
  [ValidateSet('lobby-a', 'lobby-b')]
  [string]$Source = 'lobby-a',
  [switch]$Force,
  [string]$Output
)

$ErrorActionPreference = 'Stop'

# Resolve devstack root (parent of this script's directory).
$DevstackRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $DevstackRoot 'docker-compose.yml'))) {
  throw "Could not locate devstack root from $PSScriptRoot (expected docker-compose.yml in parent)."
}

$WorldDir = Join-Path $DevstackRoot "$Source\world"
if (-not (Test-Path $WorldDir)) {
  throw "Source world directory not found: $WorldDir. Boot $Source via 'docker compose up -d' and paste the schematic before baking."
}
if (-not (Test-Path (Join-Path $WorldDir 'level.dat'))) {
  throw "$WorldDir exists but contains no level.dat - the lobby has not been booted yet, or the world is corrupt."
}

if (-not $Output) {
  $Output = Join-Path $DevstackRoot 'shared\lobby-world.zip'
}

if (Test-Path $Output) {
  if (-not $Force) {
    Write-Host "[bake] $Output already exists. Re-run with -Force to overwrite." -ForegroundColor Yellow
    return
  }
  Remove-Item -Force $Output
}

# Refuse to bake if a container still holds the world's session.lock - the
# archive would capture mid-write region files and corrupt downstream boots.
$SessionLock = Join-Path $WorldDir 'session.lock'
if (Test-Path $SessionLock) {
  $lockInfo = Get-Item $SessionLock
  if (((Get-Date) - $lockInfo.LastWriteTime).TotalSeconds -lt 30) {
    Write-Host "[bake] $SessionLock was touched <30s ago - $Source may still be running. Stop the stack first ('docker compose stop $Source') and retry." -ForegroundColor Red
    throw "session.lock on $Source is fresh; refusing to bake a live world."
  }
  Write-Host "[bake] Stale session.lock detected (older than 30s); proceeding." -ForegroundColor DarkGray
}

# Sanity check: world dirs typically run 5-100 MB. A < 1 MB world means the
# paste likely was not applied; warn but allow if -Force.
$sizeBytes = (Get-ChildItem -Recurse -Force $WorldDir -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
$sizeMb = [Math]::Round($sizeBytes / 1MB, 2)
if ($sizeMb -lt 1) {
  if (-not $Force) {
    Write-Host "[bake] $WorldDir is only $sizeMb MB - looks empty. Paste the schematic first, or re-run with -Force." -ForegroundColor Yellow
    return
  }
  Write-Host "[bake] WARN $WorldDir is only $sizeMb MB; baking anyway because -Force." -ForegroundColor Yellow
}

Write-Host "[bake] archiving $WorldDir ($sizeMb MB) -> $Output ..." -ForegroundColor Cyan

# Build the zip with `world/` as the top-level entry. itzg/minecraft-server's
# WORLD handler accepts either a flat `level.dat`-at-root zip or a single
# `world/` directory at root; we use the latter so the archive is
# self-describing when inspected by hand.
$Staging = Join-Path $env:TEMP "rtp-devstack-bake-$([Guid]::NewGuid().ToString('N'))"
try {
  $StagedWorld = Join-Path $Staging 'world'
  New-Item -ItemType Directory -Force -Path $StagedWorld | Out-Null
  Copy-Item -Recurse -Force -Path (Join-Path $WorldDir '*') -Destination $StagedWorld
  # Drop the session.lock from the archive - the destination container will
  # write its own on boot, and a stale lock here just costs unpack time.
  $StagedLock = Join-Path $StagedWorld 'session.lock'
  if (Test-Path $StagedLock) { Remove-Item -Force $StagedLock }
  # Build the zip entry-by-entry with explicit forward-slash names. Both
  # Compress-Archive and System.IO.Compression.ZipFile.CreateFromDirectory on
  # .NET Framework / Windows PowerShell 5.1 write entry names with Windows
  # backslashes, which violates the ZIP spec (APPNOTE 4.4.17: forward slashes
  # only) and causes Linux `unzip` inside itzg/minecraft-server to bail with
  #   "warning: ... appears to use backslashes as path separators"
  # followed by an extraction failure. Manual enumeration sidesteps the bug.
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  Add-Type -AssemblyName System.IO.Compression
  if (Test-Path $Output) { Remove-Item -Force $Output }
  $zipStream = [System.IO.File]::Open($Output, [System.IO.FileMode]::CreateNew)
  $archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
  try {
    $stagingFull = (Resolve-Path $Staging).Path.TrimEnd('\','/')
    Get-ChildItem -Recurse -Force -File $Staging | ForEach-Object {
      $rel = $_.FullName.Substring($stagingFull.Length + 1) -replace '\\', '/'
      $entry = $archive.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
      $es = $entry.Open()
      try {
        $fs = [System.IO.File]::OpenRead($_.FullName)
        try { $fs.CopyTo($es) } finally { $fs.Dispose() }
      } finally { $es.Dispose() }
    }
  } finally {
    $archive.Dispose()
    $zipStream.Dispose()
  }
} finally {
  if (Test-Path $Staging) { Remove-Item -Recurse -Force $Staging -ErrorAction SilentlyContinue }
}

$outSize = [Math]::Round((Get-Item $Output).Length / 1MB, 2)
Write-Host "[bake] PASS - wrote $Output ($outSize MB). Next 'docker compose up' will boot lobbies from this world." -ForegroundColor Green
