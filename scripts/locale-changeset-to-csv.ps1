# locale-changeset-to-csv.ps1
#
# SECONDARY component of the locale config pipeline (the "changeset" workflow).
#
# The primary pipeline (locale-files-to-csv -> reconcile-locale-csvs ->
# locale-files-from-csv) maintains ONE full tab-separated locale-<lang>.tsv per
# language. That is the right surface when you want to translate (or re-review)
# a whole language end to end.
#
# This script addresses the OTHER common case: you added an option to the
# English baseline, or changed a description, and now the SAME finite set of
# keys needs translating across EVERY language. Editing each per-locale TSV by
# hand is tedious and error prone. Instead this exports a single, wide,
# spreadsheet-friendly comma-separated file:
#
#     scripts/out/changeset.csv
#
# with one ROW per changed baseline key and one COLUMN per language:
#
#     relpath, parent_path, base_key, index, english, de, es, fr, it, ja, ...
#
# A translator (human or agent) fills in the per-language columns for just
# those rows, then locale-changeset-from-csv.ps1 propagates the translations
# back into every locale-<lang>.tsv (matched by the same langmap logic the
# reconcile step uses). The normal locale-files-from-csv.ps1 then regenerates
# the YAML tree.
#
# It is deliberately a real CSV (RFC-4180 comma-quoted), not the tab-separated
# .tsv the internal pipeline uses, because it is meant to be opened in a
# spreadsheet by a human translator.
#
# Row selection (which baseline keys go into the changeset):
#   -Keys <list>        Explicit selection. Each entry is one of:
#                         "<relpath>"               -> every translatable key
#                                                      in that baseline file
#                         "<relpath>:<base_key>"    -> a single key in a file
#                         "<base_key>"              -> that key in any file
#   -UntranslatedOnly   (default when -Keys is omitted) include only rows whose
#                       value in at least one locale still equals the English
#                       baseline value (i.e. a freshly-seeded placeholder).
#   -All                include every translatable baseline value row.
#
# This script is read-only with respect to the YAML tree and the per-locale
# TSVs; it only writes scripts/out/changeset.csv.

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
if (-not $OutFile) { $OutFile = Join-Path $OutDir 'changeset.csv' }

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

# Load each locale TSV and build, per locale:
#   - a lookup index keyed by "relpath|parent_path|key|index"
#   - the per-file langmap (baseValueRelpath -> baseKey -> translatedKey)
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

# Decide whether a baseline row is a translatable value row.
function Test-IsTranslatableValueRow($r) {
    if ($r.value -eq '__MAP_OR_LIST_PARENT__') { return $false }
    if ($r.key -eq '' -and $r.index -eq '') { return $false }
    # Skip baseline rename-map rows (lang/<file>.lang.yml). shape/vert .lang.yml
    # ARE value files and stay in.
    if ($r.relpath -like 'lang/*.lang.yml' -and
        $r.relpath -notlike 'lang/shape/*' -and
        $r.relpath -notlike 'lang/vert/*') { return $false }
    return $true
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

# Resolve the locale's current value for a baseline row (using the langmap to
# compute the translated key/parent). Returns $null when the locale has no row.
function Get-LocaleValue($loc, $br, $basePath) {
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
    if ($idx.ContainsKey($lk)) { return $idx[$lk].value }
    return $null
}

if (-not $Keys -and -not $All) { $UntranslatedOnly = $true }

$outRows = New-Object System.Collections.Generic.List[object]
foreach ($basePath in $baselineByPath.Keys) {
    foreach ($br in $baselineByPath[$basePath]) {
        if (-not (Test-IsTranslatableValueRow $br)) { continue }
        if ($Keys) {
            if (-not (Test-MatchesKeysSelection $br $Keys)) { continue }
        }

        # Gather per-locale current values.
        $localeVals = @{}
        $anyUntranslated = $false
        foreach ($loc in $locales) {
            $v = Get-LocaleValue $loc $br $basePath
            if ($null -eq $v) { $v = '' }
            $localeVals[$loc] = $v
            if ($v -eq '' -or $v -eq $br.value) { $anyUntranslated = $true }
        }

        if ($UntranslatedOnly -and -not $anyUntranslated) { continue }

        $row = [ordered]@{
            relpath     = $basePath
            parent_path = $br.parent_path
            base_key    = $br.base_key
            index       = $br.index
            english     = $br.value
        }
        foreach ($loc in $locales) { $row[$loc] = $localeVals[$loc] }
        $outRows.Add([pscustomobject]$row) | Out-Null
    }
}

$header = @('relpath','parent_path','base_key','index','english') + $locales
Write-Csv -Rows $outRows -Columns $header -Path $OutFile

Write-Host ("Wrote {0} changeset row(s) for {1} locale(s) -> {2}" -f $outRows.Count, $locales.Count, $OutFile)
if ($outRows.Count -eq 0) {
    Write-Host "Nothing to translate with the current selection."
}
