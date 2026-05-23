# apply-locale-translations.ps1
#
# Applies a curated translation map (`scripts/translations/<locale>.tsv`)
# onto the locale TSV (`scripts/out/locale-<locale>.tsv`) in a single
# atomic pass. Designed for the beta.4 locale translation campaign
# (see docs/dev/scratch/CHECKLIST-beta4-translations.md and AGENTS.md
# "Locale Config TSV Pipeline").
#
# Translation map schema (UTF-8, tab-separated, with header):
#
#   relpath  parent_path  base_key  index  new_key  new_value  new_comment
#
#   relpath      - baseline-form relpath, NO `lang/<locale>/` prefix
#                  (e.g. `messages.yml`, `lang/shape/CIRCLE.lang.yml`).
#                  The script prepends `lang/<locale>/` for files outside
#                  the shape/ vert/ rename-map tree, and leaves
#                  `lang/shape/...` / `lang/vert/...` paths verbatim
#                  (they're locale-prefixed on disk too).
#   parent_path  - dotted YAML path to the parent of the row, or empty
#                  for top-level keys. Locale TSV column 1 (0-based:
#                  $c[1]). REQUIRED for disambiguation: keys like `port`,
#                  `host`, `type`, `k` appear under multiple parents in
#                  nested configs.
#   base_key     - the English baseline key (anchor; never changes).
#                  Used to locate the locale row.
#   index        - scalar-list index for list-item rows, or empty for
#                  mapping rows. Locale TSV column 3 (0-based: $c[3]).
#                  Match must be exact: empty matches empty,
#                  "0" matches "0".
#   new_key      - empty = keep current locale key (column $c[2]).
#                  Non-empty = rewrite. To clear, use sentinel `<empty>`.
#   new_value    - empty = keep current locale value (column $c[4]).
#                  Non-empty = rewrite. Sentinel `<empty>` clears.
#   new_comment  - empty = keep current locale comment (column $c[5]).
#                  Non-empty = rewrite. Embed newlines as `\n` and tabs
#                  as `\t` (same escape rules as baseline.tsv).
#                  Sentinel `<empty>` clears.
#
# Idempotency: if a target row already matches the requested new_*, the
# row is reported as "skipped (already applied)" and the script does not
# count it as an error.
#
# Validation: every translation row MUST resolve to exactly one row in
# the locale TSV. Unresolved or ambiguous rows print a diagnostic and
# the script exits non-zero without writing anything (atomic).
#
# Usage:
#   .\scripts\apply-locale-translations.ps1 -Locale cat -DryRun
#   .\scripts\apply-locale-translations.ps1 -Locale cat
#
# After a successful apply, re-run the rest of the TSV pipeline:
#   .\scripts\locale-files-from-csv.ps1
#   .\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"

[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$Locale,

  [string]$TranslationsPath,
  [string]$LocaleTsvPath,

  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { (Get-Location).Path }

if (-not $TranslationsPath) {
  $TranslationsPath = Join-Path $scriptDir "translations\$Locale.tsv"
}
if (-not $LocaleTsvPath) {
  $LocaleTsvPath = Join-Path $scriptDir "out\locale-$Locale.tsv"
}

if (-not (Test-Path $TranslationsPath)) {
  Write-Error "Translations file not found: $TranslationsPath"
  exit 1
}
if (-not (Test-Path $LocaleTsvPath)) {
  Write-Error "Locale TSV not found: $LocaleTsvPath - run locale-files-to-csv.ps1 first."
  exit 1
}

Write-Host "Applying translations:"
Write-Host "  source: $TranslationsPath"
Write-Host "  target: $LocaleTsvPath"
if ($DryRun) { Write-Host "  mode:   DRY RUN (no file writes)" -ForegroundColor Yellow }
Write-Host ""

# --- Load translation map ----------------------------------------------------
$tLines = Get-Content $TranslationsPath -Encoding UTF8
if ($tLines.Count -lt 1) {
  Write-Error "Translations file is empty: $TranslationsPath"
  exit 1
}
$tHeader = ($tLines[0] -split "`t")
$expectedHeader = @('relpath','parent_path','base_key','index','new_key','new_value','new_comment')
for ($i = 0; $i -lt $expectedHeader.Count; $i++) {
  if ($tHeader[$i] -ne $expectedHeader[$i]) {
    Write-Error "Translation header column $i is '$($tHeader[$i])', expected '$($expectedHeader[$i])'. Schema mismatch."
    exit 1
  }
}

$translations = New-Object System.Collections.ArrayList
for ($i = 1; $i -lt $tLines.Count; $i++) {
  $ln = $tLines[$i]
  if (-not $ln) { continue }
  # Allow comment-prefixed rows in the translations file for human notes.
  if ($ln.StartsWith('#')) { continue }
  $c = $ln -split "`t", 7
  if ($c.Count -lt 7) {
    # Pad missing trailing tabs (e.g. trailing empty new_comment).
    while ($c.Count -lt 7) { $c += '' }
  }
  $rec = [PSCustomObject]@{
    line        = $i + 1   # 1-based for diagnostics
    relpath     = $c[0]
    parent_path = $c[1]
    base_key    = $c[2]
    index       = $c[3]
    new_key     = $c[4]
    new_value   = $c[5]
    new_comment = $c[6]
  }
  [void]$translations.Add($rec)
}

Write-Host "Loaded $($translations.Count) translation rows."

# --- Load locale TSV ---------------------------------------------------------
$localeLines = [System.Collections.ArrayList]@(Get-Content $LocaleTsvPath -Encoding UTF8)
if ($localeLines.Count -lt 1) {
  Write-Error "Locale TSV is empty: $LocaleTsvPath"
  exit 1
}
$lHeader = $localeLines[0]

# Build an index: (locale_relpath, parent_path, base_key, index) -> line index
# in $localeLines. Includes parent_path because keys like `port`/`host`/`type`
# legitimately repeat across nested YAML sections. Detect duplicates that
# survive even the 4-tuple key and refuse to proceed if any.
$rowIndex = @{}
$dupes = New-Object System.Collections.ArrayList
for ($i = 1; $i -lt $localeLines.Count; $i++) {
  $ln = $localeLines[$i]
  if (-not $ln) { continue }
  $c = $ln -split "`t"
  if ($c.Count -lt 8) { continue }
  $relpath = $c[0]
  $parent  = $c[1]
  $idx     = $c[3]
  $bk      = $c[7]
  $id = "$relpath`t$parent`t$bk`t$idx"
  if ($rowIndex.ContainsKey($id)) {
    [void]$dupes.Add("line $($i+1): duplicate id ($id) - first seen at line $($rowIndex[$id]+1)")
  } else {
    $rowIndex[$id] = $i
  }
}
if ($dupes.Count -gt 0) {
  Write-Host "ERROR: locale TSV has duplicate (relpath, base_key, index) tuples:" -ForegroundColor Red
  foreach ($d in $dupes) { Write-Host "  $d" -ForegroundColor Red }
  exit 1
}

# --- Resolve translation rows against the locale TSV -------------------------
function Resolve-LocaleRelpath {
  param([string]$BaselineRelpath, [string]$Locale)
  # shape/* and vert/* rename-map files are shared across locales and stored
  # under `lang/shape/...` / `lang/vert/...` in the LOCALE TSV too (not under
  # lang/<L>/shape/...). Treat them verbatim. Everything else gets the
  # `lang/<L>/` prefix.
  if ($BaselineRelpath -like 'lang/shape/*' -or $BaselineRelpath -like 'lang/vert/*') {
    return $BaselineRelpath
  }
  return "lang/$Locale/$BaselineRelpath"
}

function Unescape-Sentinel {
  param([string]$Value)
  if ($Value -eq '<empty>') { return '' }
  return $Value
}

$errors = New-Object System.Collections.ArrayList
$plannedChanges = New-Object System.Collections.ArrayList   # @{ lineIdx; before; after; relpath; base_key; index; tags }
$skippedNoop = 0

foreach ($t in $translations) {
  $localeRelpath = Resolve-LocaleRelpath -BaselineRelpath $t.relpath -Locale $Locale
  $id = "$localeRelpath`t$($t.parent_path)`t$($t.base_key)`t$($t.index)"
  if (-not $rowIndex.ContainsKey($id)) {
    [void]$errors.Add("translations line $($t.line): no locale row matches (relpath='$localeRelpath', parent_path='$($t.parent_path)', base_key='$($t.base_key)', index='$($t.index)')")
    continue
  }
  $li = $rowIndex[$id]
  $current = $localeLines[$li] -split "`t"
  if ($current.Count -lt 8) {
    [void]$errors.Add("translations line $($t.line): malformed locale row at line $($li+1) (fewer than 8 columns)")
    continue
  }

  $newCols = @($current)   # copy
  $changedTags = New-Object System.Collections.ArrayList

  if ($t.new_key) {
    $nk = Unescape-Sentinel $t.new_key
    if ($newCols[2] -cne $nk) {
      $newCols[2] = $nk
      [void]$changedTags.Add('key')
    }
  }
  if ($t.new_value) {
    $nv = Unescape-Sentinel $t.new_value
    if ($newCols[4] -cne $nv) {
      $newCols[4] = $nv
      [void]$changedTags.Add('value')
    }
  }
  if ($t.new_comment) {
    $nc = Unescape-Sentinel $t.new_comment
    if ($newCols[5] -cne $nc) {
      $newCols[5] = $nc
      [void]$changedTags.Add('comment')
    }
  }

  if ($changedTags.Count -eq 0) {
    $skippedNoop++
    continue
  }

  $newLine = ($newCols -join "`t")
  [void]$plannedChanges.Add([PSCustomObject]@{
    lineIdx  = $li
    before   = $localeLines[$li]
    after    = $newLine
    relpath  = $localeRelpath
    base_key = $t.base_key
    index    = $t.index
    tags     = ($changedTags -join ',')
  })
}

if ($errors.Count -gt 0) {
  Write-Host ""
  Write-Host "ERRORS ($($errors.Count)):" -ForegroundColor Red
  foreach ($e in $errors) { Write-Host "  $e" -ForegroundColor Red }
  Write-Host ""
  Write-Host "Refusing to write any changes (atomic mode)." -ForegroundColor Red
  exit 1
}

Write-Host ""
Write-Host "Summary:"
Write-Host "  planned changes: $($plannedChanges.Count)"
Write-Host "  skipped (already applied / no-op): $skippedNoop"
Write-Host ""

if ($plannedChanges.Count -eq 0) {
  Write-Host "Nothing to do."
  exit 0
}

# Per-tag breakdown.
$tagCounts = @{}
foreach ($p in $plannedChanges) {
  foreach ($t in ($p.tags -split ',')) {
    if (-not $tagCounts.ContainsKey($t)) { $tagCounts[$t] = 0 }
    $tagCounts[$t]++
  }
}
Write-Host "  by column:"
foreach ($k in ($tagCounts.Keys | Sort-Object)) {
  Write-Host "    $k`: $($tagCounts[$k])"
}
Write-Host ""

if ($DryRun) {
  Write-Host "Dry run: showing first 10 planned changes." -ForegroundColor Yellow
  $shown = [Math]::Min(10, $plannedChanges.Count)
  for ($i = 0; $i -lt $shown; $i++) {
    $p = $plannedChanges[$i]
    Write-Host ""
    Write-Host "  [$($p.tags)] $($p.relpath) :: $($p.base_key) (index='$($p.index)')" -ForegroundColor Cyan
    Write-Host "    - $($p.before)" -ForegroundColor DarkGray
    Write-Host "    + $($p.after)"  -ForegroundColor Green
  }
  Write-Host ""
  Write-Host "Dry run complete. Re-run without -DryRun to apply." -ForegroundColor Yellow
  exit 0
}

# --- Apply -------------------------------------------------------------------
foreach ($p in $plannedChanges) {
  $localeLines[$p.lineIdx] = $p.after
}

# Write atomically: temp file + replace.
$tmpPath = "$LocaleTsvPath.tmp"
Set-Content -Path $tmpPath -Value $localeLines -Encoding UTF8 -NoNewline:$false
Move-Item -Path $tmpPath -Destination $LocaleTsvPath -Force

Write-Host "Wrote $($plannedChanges.Count) changes to $LocaleTsvPath" -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:"
Write-Host "  .\scripts\locale-files-from-csv.ps1"
Write-Host "  .\gradlew :rtp-plugin:test --tests `"*LocaleParityTest*`""
