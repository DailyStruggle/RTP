# Strips trailing inline comments baked into quoted scalar values in locale YAML files.
# Pattern: key: "value # comment text"  ->  key: "value"
# Operates on rtp-plugin/src/main/resources/lang/<loc>/*.yml (excluding *.lang.yml).

$ErrorActionPreference = 'Stop'

$scriptPath = if ($PSScriptRoot) { $PSScriptRoot } elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { (Get-Location).Path }
$repoRoot = Split-Path -Parent $scriptPath
$resourcesRoot = Join-Path $repoRoot 'rtp-plugin\src\main\resources'

$files = Get-ChildItem $resourcesRoot -Recurse -Filter *.yml |
    Where-Object { $_.Name -notin @('plugin.yml','language.yml') -and $_.Name -notlike '*.lang.yml' }

$totalChanged = 0
$totalLines = 0

foreach ($f in $files) {
    $lines = Get-Content $f.FullName -Encoding UTF8
    $changed = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # Match: optional indent, key, :, optional space, "value # comment"
        if ($line -match '^(\s*[A-Za-z_][\w.-]*:\s*")([^"]*?)\s+#\s+[^"]*(".*)$') {
            $lines[$i] = $matches[1] + $matches[2] + $matches[3]
            $changed = $true
            $totalLines++
        }
    }
    if ($changed) {
        $totalChanged++
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($f.FullName, ($lines -join "`n") + "`n", $utf8NoBom)
    }
}

Write-Host "Stripped inline comments from $totalLines lines across $totalChanged files."
