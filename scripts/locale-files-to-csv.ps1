# locale-files-to-csv.ps1
#
# Collapses the per-locale YAML file tree under rtp-plugin/src/main/resources/
# into ONE CSV per locale (plus one baseline CSV), so contributors maintain a
# single file per language instead of ~16 separate YAMLs (config, messages,
# safety, performance, economy, effects, logging, regions, worlds, plus the
# nested shape/<*>.lang.yml and vert/<*>.lang.yml maps, plus the *.lang.yml
# key-rename maps under lang/).
#
# Output files (under scripts/out/):
#   - baseline.csv         <- rtp-plugin/src/main/resources/*.yml (excluding
#                              plugin.yml, language.yml) AND
#                              rtp-plugin/src/main/resources/lang/*.lang.yml
#                              (the baseline key-rename maps)
#   - locale-<lang>.csv    <- everything under
#                              rtp-plugin/src/main/resources/lang/<lang>/
#                              for each locale (cat, de, es, fr, ja, ko, nl,
#                              pt, ru, zh)
#
# Each CSV has the columns:
#   relpath, parent_path, key, index, value, preceding_comment, blank_before, base_key
# (Same semantics as locale-config-to-csv.ps1; the per-row `language` column
# is dropped because the CSV filename now identifies the locale.)
#
# `base_key` records the baseline-equivalent identifier so the mapping from
# baseline key (e.g. `teleportDelay`) to the locale's translated key
# (e.g. `teleportVerzoegerung`) is preserved in the row itself. Resolution:
#   - baseline rows: identity (base_key == key, or the parent_path top
#     segment for list items).
#   - locale `<file>.yml` rows: looked up via the sibling
#     `<file>.lang.yml` reverse map (translated_key -> baseline_key);
#     identity fallback.
#   - locale `<file>.lang.yml` rows: identity (the row's `key` IS the
#     baseline key by definition of the lang-map contract).
#
# Sister script: locale-files-from-csv.ps1 regenerates the YAML tree from
# the per-locale CSVs.
#
# This script does NOT modify the YAML tree; it is a read-only export.

[CmdletBinding()]
param(
    [string] $ResourcesRoot,
    [string] $OutputDir
)

$ErrorActionPreference = 'Stop'

# Resolve script dir robustly (mirrors locale-config-to-csv.ps1).
$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    if ($MyInvocation.MyCommand.Path) {
        $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    }
}
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$repoRoot = Split-Path -Parent $scriptDir
if (-not $repoRoot) { $repoRoot = (Get-Location).Path }

if (-not $ResourcesRoot) {
    $ResourcesRoot = Join-Path $repoRoot 'rtp-plugin\src\main\resources'
}
if (-not $OutputDir) {
    $OutputDir = Join-Path $scriptDir 'out'
}

if (-not (Test-Path -LiteralPath $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
}

function Strip-Quotes([string]$s) {
    if ($null -eq $s) { return $s }
    $t = $s.Trim()
    if ($t.Length -ge 2) {
        if (($t.StartsWith('"') -and $t.EndsWith('"')) -or
            ($t.StartsWith("'") -and $t.EndsWith("'"))) {
            return $t.Substring(1, $t.Length - 2)
        }
    }
    return $t
}

# Parse one YAML file into a list of row pscustomobjects. Shape supported:
#   - top-level scalars
#   - top-level lists of scalars
#   - one-level-nested maps of scalars and/or lists
# (Matches the live tree.)
function Convert-YamlFileToRows {
    param(
        [Parameter(Mandatory)] [string] $FullPath,
        [Parameter(Mandatory)] [string] $RelPath
    )

    $bytes = [System.IO.File]::ReadAllBytes($FullPath)
    $text  = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    $lines = $text -split "`r?`n"

    $rows = New-Object System.Collections.Generic.List[object]
    $pendingComments = New-Object System.Collections.Generic.List[string]
    $pendingBlank = $false
    # Indentation-aware parent stack. Each frame is @{ indent = <int>; key = <string> }.
    # The effective parent_path is the dot-join of the frames' keys. Frames are
    # popped whenever a non-list line dedents to (or above) their indent, so
    # sibling maps that follow a deeper nested map (e.g. the multi-level
    # `loadBalancer.terms` block) are attributed to the correct parent instead
    # of being appended onto the previous branch.
    $stack = New-Object System.Collections.Generic.List[object]
    $listIndex = -1

    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]

        if ($line -match '^\s*$') {
            $pendingBlank = $true
            continue
        }

        if ($line -match '^\s*#') {
            $c = $line -replace '^\s+', ''
            $pendingComments.Add($c) | Out-Null
            continue
        }

        $indentMatch = [regex]::Match($line, '^(\s*)')
        $indent = $indentMatch.Groups[1].Value.Length
        $stripped = $line.Substring($indent)

        $isListItem = ($stripped.StartsWith('- ') -or $stripped -eq '-')

        # Dedent handling: pop any frames at or below the current indent. List
        # items are exempt so a list whose items share their parent key's
        # indent (block sequence) stays attributed to that key.
        if (-not $isListItem) {
            while ($stack.Count -gt 0 -and $stack[$stack.Count - 1].indent -ge $indent) {
                $stack.RemoveAt($stack.Count - 1)
            }
        }
        $currentParent = (($stack | ForEach-Object { $_.key }) -join '.')

        if ($isListItem) {
            $listIndex++
            $value = $stripped.Substring(1).TrimStart()
            $rows.Add([pscustomobject]@{
                relpath           = $RelPath
                parent_path       = $currentParent
                key               = ''
                index             = $listIndex
                value             = Strip-Quotes $value
                preceding_comment = ($pendingComments -join "`n")
                blank_before      = [int]$pendingBlank
                base_key          = ''
            }) | Out-Null
            $pendingComments.Clear()
            $pendingBlank = $false
            continue
        }

        # Any non-list line terminates an in-progress block sequence.
        $listIndex = -1

        $kv = [regex]::Match($stripped, '^([^:#\s][^:]*?)\s*:(?:\s*(.*))?$')
        if (-not $kv.Success) {
            $pendingComments.Clear()
            $pendingBlank = $false
            continue
        }
        $key   = $kv.Groups[1].Value.Trim()
        $value = if ($kv.Groups[2].Success) { $kv.Groups[2].Value } else { '' }

        if ($value -eq '' -or $value -match '^\s*$') {
            $rows.Add([pscustomobject]@{
                relpath           = $RelPath
                parent_path       = $currentParent
                key               = $key
                index             = ''
                value             = '__MAP_OR_LIST_PARENT__'
                preceding_comment = ($pendingComments -join "`n")
                blank_before      = [int]$pendingBlank
                base_key          = ''
            }) | Out-Null
            $pendingComments.Clear()
            $pendingBlank = $false
            $stack.Add([pscustomobject]@{ indent = $indent; key = $key }) | Out-Null
            continue
        }

        $rows.Add([pscustomobject]@{
            relpath           = $RelPath
            parent_path       = $currentParent
            key               = $key
            index             = ''
            value             = Strip-Quotes $value
            preceding_comment = ($pendingComments -join "`n")
            blank_before      = [int]$pendingBlank
            base_key          = ''
        }) | Out-Null
        $pendingComments.Clear()
        $pendingBlank = $false
    }

    return $rows
}

# Load the top-level baseline_key -> translated_key map from a `<file>.lang.yml`
# and return its REVERSE (translated_key -> baseline_key). Returns an empty
# hashtable if the file is absent or unreadable.
function Get-ReverseLangMap {
    param([Parameter(Mandatory)][string] $LangYmlPath)
    $rev = @{}
    if (-not (Test-Path -LiteralPath $LangYmlPath)) { return $rev }
    $bytes = [System.IO.File]::ReadAllBytes($LangYmlPath)
    $text  = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    foreach ($line in ($text -split "`r?`n")) {
        if ($line -match '^\s*#') { continue }
        if ($line -match '^\s*$') { continue }
        $m = [regex]::Match($line, '^([^:#\s][^:]*?)\s*:\s*(.*)$')
        if (-not $m.Success) { continue }
        $base = $m.Groups[1].Value.Trim()
        $val  = (Strip-Quotes $m.Groups[2].Value).Trim()
        if ($val -eq '' -or $val -eq '__MAP_OR_LIST_PARENT__') { continue }
        if (-not $rev.ContainsKey($val)) { $rev[$val] = $base }
    }
    return $rev
}

# Populate the `base_key` field on each row of $Rows in-place.
# $RelPathToReverseMap: hashtable relpath -> (hashtable translated_key -> baseline_key)
function Set-BaseKeys {
    param(
        [Parameter(Mandatory)] [AllowEmptyCollection()] [System.Collections.Generic.List[object]] $Rows,
        [Parameter(Mandatory)] [hashtable] $RelPathToReverseMap
    )
    foreach ($r in $Rows) {
        $rev = $RelPathToReverseMap[$r.relpath]
        if (-not $rev) { $rev = @{} }
        if ($r.key -and $r.key -ne '') {
            if ($rev.ContainsKey($r.key)) { $r.base_key = $rev[$r.key] } else { $r.base_key = $r.key }
        } elseif ($r.parent_path -and $r.parent_path -ne '') {
            $top = ($r.parent_path -split '\.')[0]
            if ($rev.ContainsKey($top)) { $r.base_key = $rev[$top] } else { $r.base_key = $top }
        } else {
            $r.base_key = ''
        }
    }
}

function Write-Csv {
    param(
        [Parameter(Mandatory)] [AllowNull()] [AllowEmptyCollection()] $Rows,
        [Parameter(Mandatory)] [string] $OutPath
    )
    if ($null -eq $Rows) { $Rows = @() }
    # Tab-separated. Values/comments may contain commas, so a comma delimiter is unsafe.
    # We also avoid CSV quoting entirely by stripping tab/CR/LF-meaningful characters
    # from cell content: tabs in cells become spaces, embedded newlines stay LF (only
    # legal in `preceding_comment` cells, which we explicitly re-encode below).
    #
    # Locale TSVs are the single source of truth for translated keys, values, AND
    # comments; there is no separate overlay file. Empty `preceding_comment` cells
    # fall through to baseline English at regeneration time.
    $cols = @('relpath','parent_path','key','index','value','preceding_comment','blank_before','base_key')
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append(($cols -join "`t")).Append("`n")
    foreach ($r in $Rows) {
        $vals = foreach ($c in $cols) {
            $v = [string]$r.$c
            # Escape backslash first, then newlines, then tabs. Reversed by from-csv.
            $v = $v -replace '\\', '\\\\'
            $v = $v -replace "`r`n", "`n"
            $v = $v -replace "`n", '\n'
            $v = $v -replace "`t", '\t'
            $v
        }
        [void]$sb.Append(($vals -join "`t")).Append("`n")
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllBytes($OutPath, $utf8NoBom.GetBytes($sb.ToString()))
}

# --- Baseline: top-level *.yml (excluding plugin.yml/language.yml) + lang/*.lang.yml ---
$baselineRows = New-Object System.Collections.Generic.List[object]
$baselineFileCount = 0

Get-ChildItem -LiteralPath $ResourcesRoot -Filter '*.yml' -File |
    Where-Object { $_.Name -notin @('plugin.yml','language.yml') } |
    Sort-Object Name |
    ForEach-Object {
        $rel = $_.Name
        $baselineFileCount++
        foreach ($r in (Convert-YamlFileToRows -FullPath $_.FullName -RelPath $rel)) {
            $baselineRows.Add($r) | Out-Null
        }
    }

$langRoot = Join-Path $ResourcesRoot 'lang'
# NOTE: baseline `lang/*.lang.yml` files are no longer exported. The
# `(base_key, key)` columns on every row make those rename-maps redundant;
# `from-csv` synthesizes them on regeneration.

$baselineOut = Join-Path $OutputDir 'baseline.tsv'
# Baseline rows: identity (empty reverse map per relpath).
Set-BaseKeys -Rows $baselineRows -RelPathToReverseMap @{}
Write-Csv -Rows $baselineRows -OutPath $baselineOut
Write-Host ("Wrote {0} rows from {1} files -> {2}" -f $baselineRows.Count, $baselineFileCount, $baselineOut)

# --- Per-locale: everything under lang/<locale>/ ---
$localeDirs = Get-ChildItem -LiteralPath $langRoot -Directory | Sort-Object Name
foreach ($ld in $localeDirs) {
    $locale = $ld.Name
    # Skip shape/vert: these are baseline-shared (no per-locale namespace under lang/<locale>/).
    # Wait: they DO live under lang/<locale>/shape/ and lang/<locale>/vert/, so they are
    # per-locale. The top-level lang/shape and lang/vert directories are also per-locale
    # carriers (no baseline equivalent). Treat any subdir of lang/ that is not a 2-letter
    # locale code as a "shared" carrier and skip from per-locale CSVs.
    if ($locale -in @('shape','vert')) {
        # Shared shape/vert templates under lang/ (no locale prefix). Fold into baseline.
        # NOTE: shape/vert files exist on disk ONLY as `<x>.lang.yml` (they are
        # value files, not rename-maps), so they must be exported. Drop the
        # `.lang.yml` skip that applies elsewhere.
        Get-ChildItem -LiteralPath $ld.FullName -Recurse -File -Filter '*.yml' |
            Sort-Object FullName |
            ForEach-Object {
                $rel = $_.FullName.Substring($ResourcesRoot.Length).TrimStart('\','/').Replace('\','/')
                $baselineFileCount++
                foreach ($r in (Convert-YamlFileToRows -FullPath $_.FullName -RelPath $rel)) {
                    $baselineRows.Add($r) | Out-Null
                }
            }
        continue
    }

    $localeRows = New-Object System.Collections.Generic.List[object]
    $localeFileCount = 0
    # Build reverse lang-map per relpath. For each `<file>.yml` under the locale
    # directory, look for its sibling `<file>.lang.yml` (same directory) and
    # invert. `.lang.yml` files themselves get no reverse map (their `key` is
    # already the baseline key).
    $relToReverse = @{}
    Get-ChildItem -LiteralPath $ld.FullName -Recurse -File -Filter '*.yml' |
        ForEach-Object {
            $rel = $_.FullName.Substring($ResourcesRoot.Length).TrimStart('\','/').Replace('\','/')
            if ($_.Name -like '*.lang.yml') { return }
            $langSibling = [System.IO.Path]::ChangeExtension($_.FullName, $null).TrimEnd('.') + '.lang.yml'
            if (Test-Path -LiteralPath $langSibling) {
                $relToReverse[$rel] = Get-ReverseLangMap -LangYmlPath $langSibling
            }
        }
    Get-ChildItem -LiteralPath $ld.FullName -Recurse -File -Filter '*.yml' |
        Where-Object {
            # Drop top-level `lang/<loc>/<file>.lang.yml` rename-maps; their
            # content is recoverable from base_key/key in the value-file rows.
            # KEEP `lang/<loc>/shape/<x>.lang.yml` and `.../vert/<x>.lang.yml` -
            # those are value files, not rename-maps.
            $isLangMap = $_.Name -like '*.lang.yml'
            if (-not $isLangMap) { $true } else {
                $parent = Split-Path -Leaf (Split-Path -Parent $_.FullName)
                ($parent -eq 'shape' -or $parent -eq 'vert')
            }
        } |
        Sort-Object FullName |
        ForEach-Object {
            $rel = $_.FullName.Substring($ResourcesRoot.Length).TrimStart('\','/').Replace('\','/')
            $localeFileCount++
            foreach ($r in (Convert-YamlFileToRows -FullPath $_.FullName -RelPath $rel)) {
                $localeRows.Add($r) | Out-Null
            }
        }
    Set-BaseKeys -Rows $localeRows -RelPathToReverseMap $relToReverse
    $localeOut = Join-Path $OutputDir ("locale-$locale.tsv")
    # Locale TSVs preserve their existing `preceding_comment`. When a row has
    # no comment, `from-csv` falls back to baseline English (lookup by
    # (baseline_relpath, base_key)). The locale TSV is the single source of
    # truth for translated values, keys, AND comments; there is no separate
    # overlay file.
    Write-Csv -Rows $localeRows -OutPath $localeOut
    Write-Host ("Wrote {0} rows from {1} files -> {2}" -f $localeRows.Count, $localeFileCount, $localeOut)
}

# Rewrite baseline with shape/vert folded in (still identity).
Set-BaseKeys -Rows $baselineRows -RelPathToReverseMap @{}
Write-Csv -Rows $baselineRows -OutPath $baselineOut
Write-Host ("Final baseline: {0} rows from {1} files -> {2}" -f $baselineRows.Count, $baselineFileCount, $baselineOut)
