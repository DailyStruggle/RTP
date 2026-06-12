# locale-files-from-csv.ps1
#
# Regenerates the rtp-plugin/src/main/resources/ YAML tree from a directory
# of per-locale CSVs produced (and optionally edited) by
# locale-files-to-csv.ps1.
#
# Input layout under $InputDir (default: scripts/out/):
#   - baseline.csv        : top-level *.yml + lang/*.lang.yml + lang/shape/*
#                           + lang/vert/*  (shared/no-locale carriers)
#   - locale-<lang>.csv   : everything under lang/<lang>/ for that locale
#
# Each CSV must have columns:
#   relpath, parent_path, key, index, value, preceding_comment, blank_before, base_key
# (same as locale-files-to-csv.ps1). `base_key` is metadata only; it is
# preserved in the TSV but not emitted into the regenerated YAML.
#
# Emission rules (identical to locale-config-from-csv.ps1):
#   - One file per distinct `relpath`, written under $ResourcesRoot, in
#     CSV row order.
#   - String scalars are double-quoted; ints/floats/bools/null are bare.
#   - "__MAP_OR_LIST_PARENT__" sentinel emits "<key>:" with no value.
#   - UTF-8 no BOM, LF endings.
#
# Options:
#   -Only <patterns>  Regenerate only files whose relpath / leaf name /
#                     baseline-equivalent relpath matches one of the wildcard
#                     patterns (e.g. -Only "integrations.yml" or
#                     -Only "lang/de/*"). Keeps a scoped change from flushing
#                     the whole locale tree. Untouched files are left as-is.
#   -Verify           Scan every regenerated file for AI mojibake markers
#                     (see .junie/AGENTS.md) and U+FFFD; throw if any are found.

[CmdletBinding()]
param(
    [string]   $ResourcesRoot,
    [string]   $InputDir,
    [string[]] $Only,
    [switch]   $Verify
)

$ErrorActionPreference = 'Stop'

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
if (-not $InputDir) {
    $InputDir = Join-Path $scriptDir 'out'
}

# Optional mojibake scan helper (shared with the changeset scripts).
$commonScript = Join-Path $scriptDir 'locale-changeset-common.ps1'
if ($Verify -and (Test-Path -LiteralPath $commonScript)) {
    . $commonScript
}

# Decide whether a given baseline relpath is in scope for -Only. Locale copies
# (lang/<loc>/<file>) and synthesized <file>.lang.yml are matched against the
# same patterns by their leaf filename / baseline-equivalent relpath.
function Test-InScope([string]$relpath) {
    if (-not $Only) { return $true }
    $leaf = Split-Path -Leaf $relpath
    $baseRel = $relpath
    if ($relpath -match '^lang/[a-z]{2,3}/(shape|vert)/(.+)$') {
        $baseRel = "lang/$($matches[1])/$($matches[2])"
    } elseif ($relpath -match '^lang/[a-z]{2,3}/(.+)$') {
        $baseRel = $matches[1]
    }
    foreach ($p in $Only) {
        if ($relpath -like $p -or $leaf -like $p -or $baseRel -like $p) { return $true }
    }
    return $false
}

$mojibakeHits = New-Object System.Collections.Generic.List[string]

function Test-IsBareScalar([string]$v) {
    if ($null -eq $v) { return $false }
    if ($v -eq '') { return $false }
    if ($v -match '^-?\d+$')          { return $true }
    if ($v -match '^-?\d+\.\d+$')     { return $true }
    if ($v -match '^(true|false)$')   { return $true }
    if ($v -match '^(null|~)$')       { return $true }
    return $false
}

function Format-Scalar([string]$v) {
    if ($v -eq '__MAP_OR_LIST_PARENT__') {
        throw "Format-Scalar called with parent sentinel"
    }
    if ($v -eq '') { return '""' }
    if (Test-IsBareScalar $v) { return $v }
    $escaped = $v.Replace('\', '\\').Replace('"', '\"')
    return '"' + $escaped + '"'
}

function Emit-Comment([System.Text.StringBuilder]$sb, [string]$comment, [string]$indent) {
    if ([string]::IsNullOrEmpty($comment)) { return }
    foreach ($line in ($comment -split "`n")) {
        if ($line -eq '') {
            [void]$sb.Append("`n")
        } else {
            [void]$sb.Append($indent).Append($line).Append("`n")
        }
    }
}

# Collect all rows from every CSV in the input directory.
if (-not (Test-Path -LiteralPath $InputDir)) {
    throw "Input directory not found: $InputDir"
}

# Match only baseline.tsv and locale-<2-or-3-letter-code>.tsv. Legacy *.csv
# inputs are silently ignored (delimiter migration: see locale-files-to-csv.ps1).
$csvFiles = Get-ChildItem -LiteralPath $InputDir -Filter '*.tsv' -File |
    Where-Object { $_.Name -eq 'baseline.tsv' -or $_.Name -match '^locale-[a-z]{2,3}\.tsv$' }

if (-not $csvFiles -or $csvFiles.Count -eq 0) {
    throw "No baseline.tsv or locale-*.tsv found in $InputDir"
}

function Unescape-Cell([string]$v) {
    if ($null -eq $v) { return '' }
    # Reverse of to-csv encoding. Use a placeholder for escaped backslash so
    # we don't decode a literal "\\n" as a newline.
    $PH = [char]0x1
    $v = $v -replace '\\\\', [string]$PH
    $v = $v -replace '\\n', "`n"
    $v = $v -replace '\\t', "`t"
    $v = $v -replace [string]$PH, '\'
    return $v
}

$cols = @('relpath','parent_path','key','index','value','preceding_comment','blank_before','base_key')

$grouped = [ordered]@{}
foreach ($csv in $csvFiles) {
    $text = [System.IO.File]::ReadAllText($csv.FullName, [System.Text.Encoding]::UTF8)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    $lines = $text -split "`r?`n"
    $headerSeen = $false
    foreach ($line in $lines) {
        if ($line -eq '') { continue }
        if (-not $headerSeen) { $headerSeen = $true; continue }
        $parts = $line -split "`t", $cols.Count
        # Pad to expected column count.
        while ($parts.Count -lt $cols.Count) { $parts += '' }
        $r = [pscustomobject]@{
            relpath           = Unescape-Cell $parts[0]
            parent_path       = Unescape-Cell $parts[1]
            key               = Unescape-Cell $parts[2]
            index             = Unescape-Cell $parts[3]
            value             = Unescape-Cell $parts[4]
            preceding_comment = Unescape-Cell $parts[5]
            blank_before      = Unescape-Cell $parts[6]
            base_key          = Unescape-Cell $parts[7]
        }
        if (-not $grouped.Contains($r.relpath)) {
            $grouped[$r.relpath] = New-Object System.Collections.Generic.List[object]
        }
        $grouped[$r.relpath].Add($r) | Out-Null
    }
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$writtenCount = 0

# --- Build baseline comment lookup: (baseline_relpath, base_key|parent_path|index) -> comment ---
#
# Locale TSVs carry their own `preceding_comment` (translated by the locale
# maintainer). When a locale row's `preceding_comment` is empty we fall back
# to the baseline English comment keyed by the corresponding baseline relpath.
# A locale's `lang/<loc>/<file>.yml` maps to baseline `<file>.yml`;
# `lang/<loc>/shape/<x>.lang.yml` maps to baseline `lang/shape/<x>.lang.yml`;
# same for vert.
function Resolve-BaselineRelpath([string]$relpath) {
    if ($relpath -match '^lang/[a-z]{2,3}/(shape|vert)/(.+)$') {
        return "lang/$($matches[1])/$($matches[2])"
    }
    if ($relpath -match '^lang/[a-z]{2,3}/(.+)$') {
        return $matches[1]
    }
    return $relpath
}

# Index baseline rows for comment lookup. Key shape: "<relpath>||<base_key>||<parent_path>||<index>".
$baselineCommentLookup = @{}
foreach ($relpath in $grouped.Keys) {
    if ($relpath -match '^lang/[a-z]{2,3}/') { continue }  # locale row, not baseline
    foreach ($r in $grouped[$relpath]) {
        $bk = if ([string]::IsNullOrEmpty($r.base_key)) { $r.key } else { $r.base_key }
        $lkKey = "$($r.relpath)||$bk||$($r.parent_path)||$($r.index)"
        if (-not $baselineCommentLookup.ContainsKey($lkKey)) {
            $baselineCommentLookup[$lkKey] = $r.preceding_comment
        }
    }
}

foreach ($relpath in $grouped.Keys) {
    if (-not (Test-InScope $relpath)) { continue }
    $fileRows = $grouped[$relpath]
    $sb = New-Object System.Text.StringBuilder
    $firstRow = $true

    # Detect locale rows so we can resolve fallback comments.
    $isLocale = $relpath -match '^lang/([a-z]{2,3})/'
    $localeName = if ($isLocale) { $matches[1] } else { $null }
    $baselineRel = Resolve-BaselineRelpath $relpath

    foreach ($r in $fileRows) {
        $parent  = $r.parent_path
        $key     = $r.key
        $idx     = $r.index
        $val     = $r.value
        $comment = $r.preceding_comment
        if ($isLocale -and [string]::IsNullOrEmpty($comment)) {
            $bk = if ([string]::IsNullOrEmpty($r.base_key)) { $r.key } else { $r.base_key }
            $blk = "$baselineRel||$bk||$parent||$idx"
            if ($baselineCommentLookup.ContainsKey($blk)) {
                $comment = $baselineCommentLookup[$blk]
            }
        }
        $blank   = ($r.blank_before -eq '1' -or $r.blank_before -eq 1)

        $depth = if ([string]::IsNullOrEmpty($parent)) { 0 } else { ($parent -split '\.').Length }
        $indent = '  ' * $depth

        if ($blank -and -not $firstRow) {
            [void]$sb.Append("`n")
        }

        Emit-Comment $sb $comment $indent

        if ($val -eq '__MAP_OR_LIST_PARENT__') {
            [void]$sb.Append($indent).Append($key).Append(":`n")
        }
        elseif (-not [string]::IsNullOrEmpty($idx) -and $idx -ne '') {
            [void]$sb.Append($indent).Append('- ').Append((Format-Scalar $val)).Append("`n")
        }
        else {
            [void]$sb.Append($indent).Append($key).Append(': ').Append((Format-Scalar $val)).Append("`n")
        }

        $firstRow = $false
    }

    $outFull = Join-Path $ResourcesRoot ($relpath -replace '/', [System.IO.Path]::DirectorySeparatorChar)
    $outDir  = Split-Path -Parent $outFull
    if (-not (Test-Path -LiteralPath $outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }
    $content = $sb.ToString()
    if ($Verify) {
        $hits = Find-Mojibake $content
        if ($hits.Count -gt 0) {
            $mojibakeHits.Add(("{0}: {1}" -f $relpath, ($hits -join ', '))) | Out-Null
        }
    }
    [System.IO.File]::WriteAllBytes($outFull, $utf8NoBom.GetBytes($content))
    $writtenCount++
}

# --- Synthesize `<file>.lang.yml` rename-maps from (base_key, key) pairs ---
#
# Rationale: the TSV's `base_key` column records the baseline-equivalent
# identifier for every row, so `<file>.lang.yml` (baseline key -> translated
# key map) is fully derivable and no longer maintained in the TSV directly.
# Emit one `.lang.yml` per `<file>.yml` written above, with one row per
# distinct top-level baseline key. Header comment is fixed boilerplate so
# admins can still read the file standalone.
# Files that are known to ship a sibling `.lang.yml` (rename-map). Other
# baseline files (e.g. metrics.yml, integrations.yml) do not, and must not
# get a synthesized one.
$langMapStems = @('config','economy','effects','logging','messages','performance','regions','safety','worlds')

$langSynthCount = 0
foreach ($relpath in $grouped.Keys) {
    $name = Split-Path -Leaf $relpath
    if ($name -like '*.lang.yml') { continue }
    if (-not ($name -like '*.yml')) { continue }
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($name)
    if ($langMapStems -notcontains $stem) { continue }
    if (-not (Test-InScope $relpath)) { continue }
    $dir  = Split-Path -Parent $relpath
    # Baseline root files (e.g. `messages.yml`) map their `.lang.yml` into
    # `lang/<stem>.lang.yml`, matching the on-disk layout. Locale files map
    # to a sibling under `lang/<loc>/`.
    if ([string]::IsNullOrEmpty($dir)) {
        $langRel = "lang/$stem.lang.yml"
    } else {
        $langRel = "$dir/$stem.lang.yml"
    }

    # Collect distinct (base_key -> key) pairs from top-level rows only:
    # ignore list items (no key) and nested rows (parent_path non-empty).
    $pairs = [ordered]@{}
    foreach ($r in $grouped[$relpath]) {
        if ([string]::IsNullOrEmpty($r.key)) { continue }
        if (-not [string]::IsNullOrEmpty($r.parent_path)) { continue }
        $b = if ([string]::IsNullOrEmpty($r.base_key)) { $r.key } else { $r.base_key }
        if (-not $pairs.Contains($b)) { $pairs[$b] = $r.key }
    }
    if ($pairs.Count -eq 0) { continue }

    $lsb = New-Object System.Text.StringBuilder
    [void]$lsb.Append("# --- Language Mapping: $name ---`n")
    [void]$lsb.Append("# Maps internal baseline keys (left) to user-visible key names used in`n")
    [void]$lsb.Append("# $name (right). Generated from the per-locale TSV; edit the TSV's`n")
    [void]$lsb.Append("# base_key/key columns and regenerate, do not hand-edit this file.`n")
    foreach ($k in $pairs.Keys) {
        $v = [string]$pairs[$k]
        [void]$lsb.Append($k).Append(': ').Append((Format-Scalar $v)).Append("`n")
    }

    $outFull = Join-Path $ResourcesRoot ($langRel -replace '/', [System.IO.Path]::DirectorySeparatorChar)
    $outDir  = Split-Path -Parent $outFull
    if (-not (Test-Path -LiteralPath $outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }
    $langContent = $lsb.ToString()
    if ($Verify) {
        $hits = Find-Mojibake $langContent
        if ($hits.Count -gt 0) {
            $mojibakeHits.Add(("{0}: {1}" -f $langRel, ($hits -join ', '))) | Out-Null
        }
    }
    [System.IO.File]::WriteAllBytes($outFull, $utf8NoBom.GetBytes($langContent))
    $langSynthCount++
}

Write-Host ("Wrote {0} files from {1} CSV input(s) under {2} (+ {3} synthesized .lang.yml)" -f $writtenCount, $csvFiles.Count, $InputDir, $langSynthCount)
if ($Only) {
    Write-Host ("  (scoped by -Only: {0})" -f ($Only -join ', '))
}
if ($Verify) {
    if ($mojibakeHits.Count -gt 0) {
        Write-Host ""
        Write-Host "Mojibake detected in regenerated output:"
        foreach ($h in $mojibakeHits) { Write-Host ("  {0}" -f $h) }
        throw ("Mojibake found in {0} file(s); fix the offending TSV cell(s) and regenerate." -f $mojibakeHits.Count)
    } else {
        Write-Host "Verify: no mojibake markers found in regenerated output."
    }
}
