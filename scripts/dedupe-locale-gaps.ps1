# dedupe-locale-gaps.ps1
#
# Repairs the duplicates introduced by fill-locale-gaps.ps1 when the locale's
# <file>.lang.yml had an identity mapping (e.g. de/messages.lang.yml said
# `millis: "millis"`) even though the locale <file>.yml already contained the
# real translation under a different key (e.g. `millisekunden: "ms"`).
#
# Strategy, per locale <file>.yml:
#   1. Take the set of baseline top-level keys (from baseline <file>.yml).
#   2. For every baseline key `bk` that appears in the locale <file>.yml as
#      itself (English key in a locale file), look for ANOTHER row in the same
#      locale file whose value matches the English row's value and whose key
#      is NOT a baseline key. That other row is the existing translation.
#   3. Drop the English duplicate row from the locale <file>.yml.
#   4. Update lang/<loc>/<file>.lang.yml so `bk: "<translatedKey>"`.
#
# Operates in-place on scripts\out\locale-config.csv.

[CmdletBinding()]
param([string] $InputCsv)
$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    if ($MyInvocation.MyCommand.Path) { $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
}
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
if (-not $InputCsv) { $InputCsv = Join-Path $scriptDir 'out\locale-config.csv' }

$rows = Import-Csv -LiteralPath $InputCsv

$byFile = [ordered]@{}
foreach ($r in $rows) {
    if (-not $byFile.Contains($r.relpath)) {
        $byFile[$r.relpath] = New-Object System.Collections.Generic.List[object]
    }
    $byFile[$r.relpath].Add($r) | Out-Null
}

# Build the set of baseline keys per baseline file.
$baselineFiles = $byFile.Keys | Where-Object { $_ -notmatch '^lang/' -and $_ -notmatch '\.lang\.yml$' }
$baselineKeysByFile = @{}
foreach ($bf in $baselineFiles) {
    $set = @{}
    foreach ($r in $byFile[$bf]) {
        if ($r.parent_path -eq '' -and $r.key -ne '') { $set[$r.key] = $true }
    }
    $baselineKeysByFile[$bf] = $set
}

$droppedTotal = 0
$mappedTotal = 0

foreach ($baseRel in $baselineFiles) {
    $baseName = $baseRel
    $baseStem = [System.IO.Path]::GetFileNameWithoutExtension($baseName)
    $baselineKeys = $baselineKeysByFile[$baseRel]

    $localeRelpaths = $byFile.Keys | Where-Object {
        $_ -match "^lang/[^/]+/$([regex]::Escape($baseName))$"
    }

    foreach ($localeRel in $localeRelpaths) {
        $localeLang = ($localeRel -split '/')[1]
        $localeLangRel = "lang/$localeLang/$baseStem.lang.yml"
        $localeRows = $byFile[$localeRel]

        # Index locale rows by value (top-level scalars only) for fast lookup.
        $rowsByValue = @{}
        foreach ($lr in $localeRows) {
            if ($lr.parent_path -ne '') { continue }
            if ($lr.key -eq '') { continue }
            if ($lr.value -eq '__MAP_OR_LIST_PARENT__') { continue }
            if (-not $rowsByValue.ContainsKey($lr.value)) {
                $rowsByValue[$lr.value] = New-Object System.Collections.Generic.List[object]
            }
            $rowsByValue[$lr.value].Add($lr) | Out-Null
        }

        # Find baseline-keyed rows in the locale that have a non-baseline-keyed
        # sibling with the same value (i.e. duplicates).
        $toDrop = New-Object System.Collections.Generic.List[object]
        $renames = @{}  # baseKey -> translatedKey
        foreach ($lr in $localeRows) {
            if ($lr.parent_path -ne '') { continue }
            if ($lr.key -eq '') { continue }
            if (-not $baselineKeys.ContainsKey($lr.key)) { continue }
            if ($lr.value -eq '__MAP_OR_LIST_PARENT__') { continue }
            $val = $lr.value
            if (-not $rowsByValue.ContainsKey($val)) { continue }
            $siblings = $rowsByValue[$val]
            # Find a sibling whose key is NOT a baseline key (i.e. translation).
            $translated = $null
            foreach ($sib in $siblings) {
                if ($sib -eq $lr) { continue }
                if ($baselineKeys.ContainsKey($sib.key)) { continue }
                # Skip empty/numeric/boolean values - too ambiguous to pair.
                if ($val -eq '') { continue }
                if ($val -match '^-?\d+(\.\d+)?$') { continue }
                if ($val -match '^(true|false)$') { continue }
                $translated = $sib.key
                break
            }
            if ($translated) {
                $toDrop.Add($lr) | Out-Null
                $renames[$lr.key] = $translated
            }
        }

        if ($toDrop.Count -gt 0) {
            $filtered = New-Object System.Collections.Generic.List[object]
            foreach ($lr in $localeRows) {
                if ($toDrop -contains $lr) { $droppedTotal++; continue }
                $filtered.Add($lr) | Out-Null
            }
            $byFile[$localeRel] = $filtered
            Write-Host "$localeRel : dropped $($toDrop.Count) duplicate row(s)"
        }

        # Apply renames to the locale lang.yml.
        if ($renames.Count -gt 0 -and $byFile.Contains($localeLangRel)) {
            $langRows = $byFile[$localeLangRel]
            foreach ($lrow in $langRows) {
                if ($lrow.parent_path -ne '') { continue }
                if ($renames.ContainsKey($lrow.key)) {
                    $newVal = $renames[$lrow.key]
                    if ($lrow.value -ne $newVal) {
                        $lrow.value = $newVal
                        $mappedTotal++
                    }
                }
            }
        }
    }
}

# Rewrite CSV.
$out = New-Object System.Collections.Generic.List[object]
foreach ($rel in $byFile.Keys) {
    foreach ($r in $byFile[$rel]) { $out.Add($r) | Out-Null }
}
$csv = $out |
    Select-Object relpath, language, parent_path, key, index, value, preceding_comment, blank_before |
    ConvertTo-Csv -NoTypeInformation
$joined = ($csv -join "`n") + "`n"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllBytes($InputCsv, $utf8.GetBytes($joined))

Write-Host "Dropped $droppedTotal duplicate rows, updated $mappedTotal lang.yml mappings. CSV: $($out.Count) rows."
