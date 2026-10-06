# Serializes concurrent gradlew.bat runs (mirrors the flock logic in ./gradlew).
# gradlew.bat sets RTP_GRADLE_SELF (its own path) and RTP_GRADLE_RAW_ARGS (its verbatim %*), then runs
# this file. The arguments are only data here: they are never spliced into PowerShell source, and
# gradlew.bat is re-run through cmd.exe with the same raw command line, so quoting survives unchanged.
# Single-module runs (every target under one :module) take a per-module lock while briefly holding the
# global lock; anything else takes the global lock and then waits for every module lock named in
# settings.gradle to drain, so a full build never overlaps a running module build. Both paths take
# the global lock first, so they cannot deadlock. A lock left by a killed process is abandoned, which
# the next waiter treats as acquired.

$raw = $env:RTP_GRADLE_RAW_ARGS
$self = $env:RTP_GRADLE_SELF
$timeoutSeconds = 600

# Lock selection only: a quoted run stays inside one token, quotes dropped.
$tokens = @()
if ($raw) {
    $tokens = @([regex]::Matches($raw, '(?:"[^"]*"|[^\s"])+') | ForEach-Object { $_.Value.Replace('"', '') })
}

# Options whose value is the next token; that value is neither a task nor a target.
$valueOptions = @('--tests', '-x', '--exclude-task', '--console', '--warning-mode', '-p', '--project-dir',
    '-I', '--init-script', '-g', '--gradle-user-home', '-b', '--build-file', '-c', '--settings-file',
    '--include-build', '--priority', '-F', '--dependency-verification', '-M', '--write-verification-metadata')

$modules = @()
$hasOtherTasks = $false
for ($i = 0; $i -lt $tokens.Count; $i++) {
    $t = $tokens[$i]
    if ($t.StartsWith('-')) {
        if ($valueOptions -ccontains $t) { $i++ }
        continue
    }
    if ($t -match '^:[a-zA-Z0-9_\-\.:]+$') {
        $parts = $t.TrimStart(':').Split(':')
        if ($parts.Length -gt 1) { $modules += $parts[0] } else { $modules += '__ROOT__' }
    } else {
        $hasOtherTasks = $true
    }
}
$modules = @($modules | Select-Object -Unique)

function Wait-Mutex([System.Threading.Mutex] $m, [int] $ms) {
    try { return $m.WaitOne($ms) } catch [System.Threading.AbandonedMutexException] { return $true }
}

$globalMutex = [System.Threading.Mutex]::new($false, 'Global\RTP_Gradle_Build_Mutex')
$held = $null
$waited = 0
if ($modules.Count -eq 1 -and -not $hasOtherTasks -and $modules[0] -ne '__ROOT__') {
    $mod = $modules[0]
    $modMutex = [System.Threading.Mutex]::new($false, ('Global\RTP_Gradle_Lock_' + $mod))
    while ($null -eq $held) {
        if (Wait-Mutex $globalMutex 500) {
            try {
                if (Wait-Mutex $modMutex 4500) { $held = $modMutex }
            } finally {
                $globalMutex.ReleaseMutex()
            }
        }
        if ($null -ne $held) { break }
        $waited += 5
        [Console]::Out.WriteLine('[gradlew] Waiting for module lock :' + $mod + '... (' + $waited + 's)')
        if ($waited -ge $timeoutSeconds) {
            [Console]::Error.WriteLine('[gradlew] Timed out waiting for module lock :' + $mod)
            exit 1
        }
    }
} else {
    while (-not (Wait-Mutex $globalMutex 5000)) {
        $waited += 5
        [Console]::Out.WriteLine('[gradlew] Waiting for global build lock... (' + $waited + 's)')
        if ($waited -ge $timeoutSeconds) {
            [Console]::Error.WriteLine('[gradlew] Timed out waiting for Gradle build lock.')
            exit 1
        }
    }
    $held = $globalMutex
    # Module runs need the global lock to start, so once each module lock is free it stays free.
    $settings = Join-Path (Split-Path -Parent $self) 'settings.gradle'
    $tops = @()
    if (Test-Path -LiteralPath $settings) {
        $tops = @([regex]::Matches([IO.File]::ReadAllText($settings), 'include\s*\(?\s*[''"]:?([A-Za-z0-9_.\-]+)') |
            ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    }
    foreach ($top in $tops) {
        $m = [System.Threading.Mutex]::new($false, ('Global\RTP_Gradle_Lock_' + $top))
        try {
            while (-not (Wait-Mutex $m 5000)) {
                $waited += 5
                [Console]::Out.WriteLine('[gradlew] Waiting for module build :' + $top + ' to finish... (' + $waited + 's)')
                if ($waited -ge $timeoutSeconds) {
                    [Console]::Error.WriteLine('[gradlew] Timed out waiting for module lock :' + $top)
                    $globalMutex.ReleaseMutex()
                    exit 1
                }
            }
            $m.ReleaseMutex()
        } finally {
            $m.Dispose()
        }
    }
}

# Called at script level, not from a function: a function would capture Gradle's output as its
# return value instead of letting it stream to the console.
$exitCode = 1
try {
    if ([string]::IsNullOrEmpty($raw)) {
        & $self
    } else {
        # --% hands the rest to cmd.exe verbatim (with %VAR% expanded); /s strips only the outer quotes.
        & $env:ComSpec /d /s /c --% ""%RTP_GRADLE_SELF%" %RTP_GRADLE_RAW_ARGS%"
    }
    $exitCode = $LASTEXITCODE
} finally {
    try { $held.ReleaseMutex() } catch {}
}
exit $exitCode
