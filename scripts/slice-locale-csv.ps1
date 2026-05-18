# slice-locale-csv.ps1
#
# Extracts a single locale's rows from scripts\out\locale-config.csv into a
# smaller per-language CSV at scripts\out\locale-<lang>.csv, paired with the
# baseline English rows for the same files (so a translator can see the
# English source and the locale target side by side).
#
# Output columns: relpath, base_key, locale_key, base_value, locale_value, masked_base_value
#   relpath          - file relative path (e.g. lang/de/messages.yml)
#   base_key         - English baseline top-level key (e.g. "millis")
#   locale_key       - locale's translated key name from <file>.lang.yml resolution
#                      (empty string if the locale has no row for this key)
#   base_value       - the English baseline value
#   locale_value     - the current locale value (empty if untranslated)
#   masked_base_value - base_value with [xxx] placeholders replaced by __RTPPHn__
#                       sentinels, so machine translation won't mangle them.
#                       Also lists the mask map at the end of the row in the
#                       `placeholders_json` column.
#   placeholders_json - JSON array of the original [xxx] tokens in order, so
#                       the un-mask pass can restore them.
#
# Usage:
#   .\scripts\slice-locale-csv.ps1 -Locale de
#   # edit scripts\out\locale-de.csv (fill in locale_value column)
#   .\scripts\unslice-locale-csv.ps1 -Locale de
#   .\scripts\locale-config-from-csv.ps1

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $Locale,
    [string] $InputCsv,
    [string] $OutputCsv
)
$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    if ($MyInvocation.MyCommand.Path) { $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
}
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
if (-not $InputCsv) { $InputCsv = Join-Path $scriptDir 'out\locale-config.csv' }
if (-not $OutputCsv) { $OutputCsv = Join-Path $scriptDir "out\locale-$Locale.csv" }

$rows = Import-Csv -LiteralPath $InputCsv

# Index by file.
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

function Mask-Placeholders([string]$s) {
    # Replace every [xxx] token (where xxx is non-bracket text) with __RTPPHn__.
    # Returns a tuple [masked, tokenList].
    $tokens = New-Object System.Collections.Generic.List[string]
    $masked = [regex]::Replace($s, '\[[^\[\]]+\]', {
        param($m)
        $idx = $tokens.Count
        $tokens.Add($m.Value) | Out-Null
        return "__RTPPH${idx}__"
    })
    return ,@($masked, $tokens)
}

$baselineFiles = $byFile.Keys | Where-Object { $_ -notmatch '^lang/' -and $_ -notmatch '\.lang\.yml$' }
$out = New-Object System.Collections.Generic.List[object]

foreach ($baseRel in $baselineFiles) {
    $baseStem = [System.IO.Path]::GetFileNameWithoutExtension($baseRel)
    $baselineLangMap = Get-LangMap "lang/$baseStem.lang.yml"
    $localeRel = "lang/$Locale/$baseRel"
    $localeLangRel = "lang/$Locale/$baseStem.lang.yml"
    if (-not $byFile.Contains($localeRel)) { continue }
    $localeLangMap = Get-LangMap $localeLangRel

    # Locale rows by key for lookup.
    $localeByKey = @{}
    foreach ($lr in $byFile[$localeRel]) {
        if ($lr.parent_path -eq '' -and $lr.key -ne '' -and $lr.value -ne '__MAP_OR_LIST_PARENT__') {
            $localeByKey[$lr.key] = $lr.value
        }
    }

    foreach ($br in $byFile[$baseRel]) {
        if ($br.parent_path -ne '') { continue }
        if ($br.key -eq '') { continue }
        if ($br.value -eq '__MAP_OR_LIST_PARENT__') { continue }
        $baseKey = $br.key
        # Resolve translated key name.
        $translated = $null
        if ($localeLangMap.ContainsKey($baseKey)) { $translated = $localeLangMap[$baseKey] }
        elseif ($baselineLangMap.ContainsKey($baseKey)) { $translated = $baselineLangMap[$baseKey] }
        else { $translated = $baseKey }
        $localeVal = ''
        if ($localeByKey.ContainsKey($translated)) { $localeVal = $localeByKey[$translated] }
        elseif ($localeByKey.ContainsKey($baseKey)) { $localeVal = $localeByKey[$baseKey] }

        $mr = Mask-Placeholders $br.value
        $masked = $mr[0]
        $tokens = $mr[1]
        $tokensJson = if ($tokens.Count -eq 0) { '[]' } else {
            '[' + (($tokens | ForEach-Object {
                '"' + ($_ -replace '\\','\\' -replace '"','\"') + '"'
            }) -join ',') + ']'
        }

        $out.Add([pscustomobject]@{
            relpath           = $baseRel
            base_key          = $baseKey
            locale_key        = $translated
            base_value        = $br.value
            locale_value      = $localeVal
            masked_base_value = $masked
            placeholders_json = $tokensJson
        }) | Out-Null
    }
}

$csv = $out |
    Select-Object relpath, base_key, locale_key, base_value, locale_value, masked_base_value, placeholders_json |
    ConvertTo-Csv -NoTypeInformation
$joined = ($csv -join "`n") + "`n"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllBytes($OutputCsv, $utf8.GetBytes($joined))

Write-Host "Wrote $($out.Count) rows for locale '$Locale' -> $OutputCsv"
