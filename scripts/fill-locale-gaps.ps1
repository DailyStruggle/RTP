# fill-locale-gaps.ps1
#
# Phase 3 helper: edit scripts\out\locale-config.csv in place so every locale
# <file>.yml contains a row for every key present in the baseline <file>.yml,
# using the locale's <file>.lang.yml to resolve the translated key name.
#
# Resolution rule (matches ConfigParser / LocaleParityTest):
#   translatedKey = localeLangMap[baselineKey]
#                ?? baselineLangMap[baselineKey]
#                ?? baselineKey
# If a row already exists for `translatedKey` in the locale <file>.yml, skip;
# otherwise inject one (seeded with the English baseline value).
#
# Also drops stale rows whose key has no entry in the locale's lang map (after
# falling back to baseline) AND no entry in baseline <file>.yml. Currently
# limited to the known-stale `language` row in de/fr config.lang.yml.

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

function Get-LangMap([string]$relpath) {
    $map = @{}
    if (-not $byFile.Contains($relpath)) { return $map }
    foreach ($r in $byFile[$relpath]) {
        if ($r.parent_path -eq '' -and $r.key -ne '' -and $r.value -ne '__MAP_OR_LIST_PARENT__') {
            $map[$r.key] = $r.value
        }
    }
    return $map
}

$baselineFiles = $byFile.Keys | Where-Object { $_ -notmatch '^lang/' -and $_ -notmatch '\.lang\.yml$' }

$staleDrops = @{
    'lang/de/config.lang.yml' = @('language')
    'lang/fr/config.lang.yml' = @('language')
}

$addedTotal = 0
$droppedTotal = 0

foreach ($baseRel in $baselineFiles) {
    $baseName = $baseRel  # e.g. "messages.yml"
    $baseRows = $byFile[$baseRel]

    # Baseline lang.yml lives at lang/<baseName-without-.yml>.lang.yml.
    $baseStem = [System.IO.Path]::GetFileNameWithoutExtension($baseName)
    $baselineLangRel = "lang/$baseStem.lang.yml"
    $baselineLangMap = Get-LangMap $baselineLangRel

    # Baseline top-level rows by key.
    $baselineKeyRows = [ordered]@{}
    foreach ($r in $baseRows) {
        if ($r.parent_path -ne '') { continue }
        if ($r.key -eq '') { continue }
        if (-not $baselineKeyRows.Contains($r.key)) { $baselineKeyRows[$r.key] = $r }
    }

    # Every locale file matching this baseline.
    $localeRelpaths = $byFile.Keys | Where-Object {
        $_ -match "^lang/[^/]+/$([regex]::Escape($baseName))$"
    }

    foreach ($localeRel in $localeRelpaths) {
        $localeRows = $byFile[$localeRel]
        $localeLang = ($localeRel -split '/')[1]
        $localeLangRel = "lang/$localeLang/$baseStem.lang.yml"
        $localeLangMap = Get-LangMap $localeLangRel

        # Set of top-level keys already present in this locale file.
        $present = @{}
        foreach ($lr in $localeRows) {
            if ($lr.parent_path -eq '' -and $lr.key -ne '') { $present[$lr.key] = $true }
        }

        foreach ($baseKey in $baselineKeyRows.Keys) {
            # Resolve translated name.
            $translatedKey = $null
            if ($localeLangMap.ContainsKey($baseKey)) {
                $translatedKey = $localeLangMap[$baseKey]
            } elseif ($baselineLangMap.ContainsKey($baseKey)) {
                $translatedKey = $baselineLangMap[$baseKey]
            } else {
                $translatedKey = $baseKey
            }
            if ([string]::IsNullOrEmpty($translatedKey)) { $translatedKey = $baseKey }

            if ($present.ContainsKey($translatedKey)) { continue }

            $b = $baselineKeyRows[$baseKey]
            $newRow = [pscustomobject]@{
                relpath           = $localeRel
                language          = $localeLang
                parent_path       = ''
                key               = $translatedKey
                index             = $b.index
                value             = $b.value
                preceding_comment = ''
                blank_before      = '0'
            }
            $localeRows.Add($newRow) | Out-Null
            $present[$translatedKey] = $true
            $addedTotal++

            if ($b.value -eq '__MAP_OR_LIST_PARENT__') {
                foreach ($cr in $baseRows) {
                    if ($cr.parent_path -eq $baseKey) {
                        $newChild = [pscustomobject]@{
                            relpath           = $localeRel
                            language          = $localeLang
                            parent_path       = $translatedKey
                            key               = $cr.key
                            index             = $cr.index
                            value             = $cr.value
                            preceding_comment = ''
                            blank_before      = '0'
                        }
                        $localeRows.Add($newChild) | Out-Null
                        $addedTotal++
                    }
                }
            }
        }
    }
}

foreach ($rel in $staleDrops.Keys) {
    if (-not $byFile.Contains($rel)) { continue }
    $keysToDrop = $staleDrops[$rel]
    $filtered = New-Object System.Collections.Generic.List[object]
    foreach ($r in $byFile[$rel]) {
        if ($keysToDrop -contains $r.key) { $droppedTotal++; continue }
        $filtered.Add($r) | Out-Null
    }
    $byFile[$rel] = $filtered
}

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

Write-Host "Added $addedTotal rows, dropped $droppedTotal stale rows. CSV: $($out.Count) rows."
