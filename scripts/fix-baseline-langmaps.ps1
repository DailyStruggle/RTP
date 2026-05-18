# Adds missing identity rows to baseline <file>.lang.yml files so LocaleParityTest FullParity passes.
$ErrorActionPreference = 'Stop'
$root = Join-Path (Split-Path -Parent $PSScriptRoot) 'rtp-plugin\src\main\resources\lang'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Add-Rows([string]$file, [string[]]$keys) {
    $c = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($file))
    foreach ($k in $keys) {
        $esc = [regex]::Escape($k)
        if ($c -notmatch "(?m)^${esc}:") {
            $c = $c.TrimEnd("`n") + "`n${k}: `"${k}`"`n"
        }
    }
    [System.IO.File]::WriteAllBytes($file, $utf8.GetBytes($c))
}

Add-Rows "$root\config.lang.yml" @('menu')
Add-Rows "$root\performance.lang.yml" @('visitorEnabled','loginCacheEnabled','loginCacheCap','pregeneratedPreference','backlogRefillThreshold')
Add-Rows "$root\safety.lang.yml" @('staleChunkRetryLimit','anvilPrefilterEnabled')
Add-Rows "$root\messages.lang.yml" @(
    'busy','invalidCommand','infoLoadsByOrigin','infoQueueDepth','infoPendingTeleports','infoAvgPipelineMs','infoHeap',
    'rtp_description','help_description','reload_description','scan_description','config_description','info_description','test_description',
    'menuInvalid','menuExpired','menuUnknownPlayer','menuHoverFallbackType','menuHoverFallbackBounds','menuBack','menuExecute',
    'menuPickValue','menuTypeValue','menuConstructed','menuRootTitle','menuRootHint','menuPagePrev','menuPageNext'
)

Write-Host "done"
