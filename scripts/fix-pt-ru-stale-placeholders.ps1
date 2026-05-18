# fix-pt-ru-stale-placeholders.ps1
#
# Drops 5 stale keys from pt/messages.yml and ru/messages.yml that reference
# placeholders no longer in baseline messages.yml, and resets infoAvgPipelineMs
# in both locales to the English baseline value (locales translated the
# placeholder token, which the parity test forbids).
#
# Operates on scripts\out\locale-config.csv in place.

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

$staleKeys = @('infoTps','infoMSPTLive','infoSoftCap','infoDatabaseLatencyMs','infoFoliaRegion')
$targetFiles = @('lang/pt/messages.yml','lang/ru/messages.yml')

# Find baseline infoAvgPipelineMs value.
$baselineVal = ($rows | Where-Object {
    $_.relpath -eq 'messages.yml' -and $_.key -eq 'infoAvgPipelineMs' -and $_.parent_path -eq ''
} | Select-Object -First 1).value

$out = New-Object System.Collections.Generic.List[object]
$dropped = 0
$reset = 0
foreach ($r in $rows) {
    if ($targetFiles -contains $r.relpath) {
        if ($staleKeys -contains $r.key) { $dropped++; continue }
        if ($r.key -eq 'infoAvgPipelineMs' -and $r.parent_path -eq '' -and $r.value -ne $baselineVal) {
            $r.value = $baselineVal
            $reset++
        }
    }
    $out.Add($r) | Out-Null
}

$csv = $out |
    Select-Object relpath, language, parent_path, key, index, value, preceding_comment, blank_before |
    ConvertTo-Csv -NoTypeInformation
$joined = ($csv -join "`n") + "`n"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllBytes($InputCsv, $utf8.GetBytes($joined))

Write-Host "Dropped $dropped stale rows, reset $reset infoAvgPipelineMs values. CSV: $($out.Count) rows."
