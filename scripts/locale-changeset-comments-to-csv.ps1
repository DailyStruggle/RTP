# locale-changeset-comments-to-csv.ps1
#
# SECONDARY component of the locale config pipeline (the "changeset" workflow).
#
# Mirrors locale-changeset-to-csv.ps1 but operates on the preceding_comment
# column of each locale TSV row instead of the value column.
#
# Exports scripts/out/changeset-comments.csv - a wide, spreadsheet-friendly
# comma-separated file with one ROW per selected baseline key and one COLUMN
# per locale:
#
#     relpath, parent_path, base_key, index, english_comment, cat, de, es, ...
#
# A translator fills in the per-language columns, then
# locale-changeset-comments-from-csv.ps1 propagates the translations back into
# every locale-<lang>.tsv preceding_comment field. The normal
# locale-files-from-csv.ps1 then regenerates the YAML tree.
#
# Row selection (which baseline keys go into the changeset):
#   -Keys <list>        Explicit selection. Each entry is one of:
#                         "<relpath>"               -> every key in that file
#                         "<relpath>:<base_key>"    -> a single key in a file
#                         "<base_key>"              -> that key in any file
#   -UntranslatedOnly   (default when -Keys is omitted) include only rows whose
#                       preceding_comment in at least one locale still equals
#                       the English baseline comment (i.e. unseeded placeholder).
#   -All                include every baseline row that has a comment.
#
# This script is read-only with respect to the YAML tree and the per-locale
# TSVs; it only writes scripts/out/changeset-comments.csv.

[CmdletBinding()]
param(
    [string]   $OutDir,
    [string[]] $Keys,
    [switch]   $UntranslatedOnly,
    [switch]   $All,
    [string]   $OutFile
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

if (-not $OutDir)  { $OutDir = Join-Path $scriptDir 'out' }
if (-not $OutFile) { $OutFile = Join-Path $OutDir 'changeset-comments.csv' }

$baselineCsv = Join-Path $OutDir 'baseline.tsv'
if (-not (Test-Path -LiteralPath $baselineCsv)) {
    throw "baseline.tsv not found at $baselineCsv. Run locale-files-to-csv.ps1 first."
}

. (Join-Path $scriptDir 'locale-changeset-common.ps1')

$baseline = Read-Tsv $baselineCsv

# Group baseline by relpath (preserve order).
$baselineByPath = [ordered]@{}
foreach ($r in $baseline) {
    if (-not $baselineByPath.Contains($r.relpath)) {
        $baselineByPath[$r.relpath] = New-Object System.Collections.Generic.List[object]
    }
    $baselineByPath[$r.relpath].Add($r) | Out-Null
}

# Discover the shipped locales (mirrors reconcile-locale-csvs.ps1).
$langRoot = Join-Path $repoRoot 'rtp-plugin/src/main/resources/lang'
$locales = @(Get-ChildItem -LiteralPath $langRoot -Directory |
    Where-Object { $_.Name -notin @('shape','vert') } |
    Select-Object -ExpandProperty Name |
    Sort-Object)

# Load each locale TSV and build lookup indexes and langmaps.
$localeRowsByLoc = @{}
$localeIdxByLoc  = @{}
$langmapsByLoc   = @{}
foreach ($loc in $locales) {
    $localeCsv = Join-Path $OutDir "locale-$loc.tsv"
    if (-not (Test-Path -LiteralPath $localeCsv)) { continue }
    $rows = Read-Tsv $localeCsv
    $localeRowsByLoc[$loc] = $rows

    $idx = @{}
    foreach ($r in $rows) {
        $k = "{0}|{1}|{2}|{3}" -f $r.relpath, $r.parent_path, $r.key, $r.index
        $idx[$k] = $r
    }
    $localeIdxByLoc[$loc] = $idx
    $langmapsByLoc[$loc]  = Build-Langmaps -LocaleRows $rows -Loc $loc
}

# Decide whether a baseline row has a comment worth exporting.
function Test-HasComment($r) {
    if ($r.value -eq '__MAP_OR_LIST_PARENT__') { return $false }
    if ($r.key -eq '' -and $r.index -eq '') { return $false }
    if ($r.relpath -like 'lang/*.lang.yml' -and
        $r.relpath -notlike 'lang/shape/*' -and
        $r.relpath -notlike 'lang/vert/*') { return $false }
    return ($r.preceding_comment -ne '')
}

# Decide whether an explicit -Keys selection matches a baseline row.
function Test-MatchesKeysSelection($r, $sel) {
    foreach ($entry in $sel) {
        if ($entry -match ':') {
            $parts = $entry -split ':', 2
            if ($r.relpath -eq $parts[0] -and $r.base_key -eq $parts[1]) { return $true }
        } elseif ($entry -like '*.yml') {
            if ($r.relpath -eq $entry) { return $true }
        } else {
            if ($r.base_key -eq $entry) { return $true }
        }
    }
    return $false
}

# Resolve the locale's current preceding_comment for a baseline row.
function Get-LocaleComment($loc, $br, $basePath) {
    $idx = $localeIdxByLoc[$loc]
    if (-not $idx) { return $null }
    $locPath = Get-LocaleRelpath $basePath $loc
    $langmap = $null
    if ($basePath -notlike 'lang/*' -and $langmapsByLoc[$loc].ContainsKey($basePath)) {
        $langmap = $langmapsByLoc[$loc][$basePath]
    }
    $effKey = $br.key
    if ($null -ne $langmap -and $br.key -ne '' -and $langmap.ContainsKey($br.key)) {
        $effKey = $langmap[$br.key]
    }
    $effParent = $br.parent_path
    if ($null -ne $langmap -and $br.parent_path -ne '' -and $langmap.ContainsKey($br.parent_path)) {
        $effParent = $langmap[$br.parent_path]
    }
    $lk = "{0}|{1}|{2}|{3}" -f $locPath, $effParent, $effKey, $br.index
    if ($idx.ContainsKey($lk)) { return $idx[$lk].preceding_comment }
    return $null
}

if (-not $Keys -and -not $All) { $UntranslatedOnly = $true }

$outRows = New-Object System.Collections.Generic.List[object]
foreach ($basePath in $baselineByPath.Keys) {
    foreach ($br in $baselineByPath[$basePath]) {
        if (-not (Test-HasComment $br)) { continue }
        if ($Keys) {
            if (-not (Test-MatchesKeysSelection $br $Keys)) { continue }
        }

        # Gather per-locale current comments.
        $localeComments = @{}
        $anyUntranslated = $false
        foreach ($loc in $locales) {
            $c = Get-LocaleComment $loc $br $basePath
            if ($null -eq $c) { $c = '' }
            $localeComments[$loc] = $c
            if ($c -eq '' -or $c -eq $br.preceding_comment) { $anyUntranslated = $true }
        }

        if ($UntranslatedOnly -and -not $anyUntranslated) { continue }

        $row = [ordered]@{
            relpath         = $basePath
            parent_path     = $br.parent_path
            base_key        = $br.base_key
            index           = $br.index
            english_comment = $br.preceding_comment
        }
        foreach ($loc in $locales) { $row[$loc] = $localeComments[$loc] }
        $outRows.Add([pscustomobject]$row) | Out-Null
    }
}

$header = @('relpath','parent_path','base_key','index','english_comment') + $locales
Write-Csv -Rows $outRows -Columns $header -Path $OutFile

Write-Host ("Wrote {0} changeset-comments row(s) for {1} locale(s) -> {2}" -f $outRows.Count, $locales.Count, $OutFile)
if ($outRows.Count -eq 0) {
    Write-Host "Nothing to translate with the current selection."
}
