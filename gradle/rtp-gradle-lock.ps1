# Serializes concurrent gradlew.bat runs (mirrors the flock logic in ./gradlew).
# gradlew.bat sets RTP_GRADLE_SELF (its own path) and RTP_GRADLE_RAW_ARGS (its verbatim %*), then runs
# this file. The arguments are only data here: they are never spliced into PowerShell source, and
# gradlew.bat is re-run through cmd.exe with the same raw command line, so quoting survives unchanged.
# Module-scoped runs (every target is :module:...:task) lock every top-level module in the targets'
# in-tree project-dependency closure, not just the target: two runs that share a dependency (e.g.
# :rtp-core:test and :rtp-plugin:build both run :effects-api:remapJar) would otherwise write the same
# build outputs from two daemons, and on Windows the loser can keep the jar handle open until its
# daemon is stopped. The closure set is taken all-or-nothing while holding the global lock. Anything
# else takes the global lock and then waits for every module lock named in settings.gradle to drain,
# so a full build never overlaps a running module build. Both paths take the global lock first, so
# they cannot deadlock. A lock left by a killed process is abandoned, which the next waiter treats as
# acquired.

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

$projectPaths = @()
$hasOtherTasks = $false
for ($i = 0; $i -lt $tokens.Count; $i++) {
    $t = $tokens[$i]
    if ($t.StartsWith('-')) {
        if ($valueOptions -ccontains $t) { $i++ }
        continue
    }
    if ($t -match '^:[a-zA-Z0-9_\-\.:]+$') {
        $parts = $t.TrimStart(':').Split(':')
        if ($parts.Length -gt 1) {
            $projectPaths += (':' + (($parts[0..($parts.Length - 2)]) -join ':'))
        } else {
            $hasOtherTasks = $true
        }
    } else {
        $hasOtherTasks = $true
    }
}
$projectPaths = @($projectPaths | Select-Object -Unique)

$rootDir = Split-Path -Parent $self
$settings = Join-Path $rootDir 'settings.gradle'
$settingsText = ''
if (Test-Path -LiteralPath $settings) { $settingsText = [IO.File]::ReadAllText($settings) }

# Top-level module names of the targets plus every project they reference (project(':x'),
# findProject(':x'), project(path: ':x')) in their build files, transitively. Over-matching only
# serialises more; it never lets two writers of one module overlap.
function Get-ModuleLockNames([string[]] $starts) {
    $dirs = @{}
    foreach ($m in [regex]::Matches($settingsText, 'project\(\s*[''"](:[^''"]+)[''"]\s*\)\.projectDir\s*=\s*file\(\s*[''"]([^''"]+)[''"]')) {
        $dirs[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    $seen = @{}
    $queue = [System.Collections.Generic.Queue[string]]::new()
    foreach ($s in $starts) { if (-not $seen.ContainsKey($s)) { $seen[$s] = $true; $queue.Enqueue($s) } }
    while ($queue.Count -gt 0) {
        $p = $queue.Dequeue()
        $rel = if ($dirs.ContainsKey($p)) { $dirs[$p] } else { $p.TrimStart(':').Replace(':', '/') }
        $dir = Join-Path $rootDir $rel
        foreach ($name in @('build.gradle', 'build.gradle.kts')) {
            $file = Join-Path $dir $name
            if (-not (Test-Path -LiteralPath $file)) { continue }
            $text = [IO.File]::ReadAllText($file)
            foreach ($m in [regex]::Matches($text, 'project\(\s*(?:path\s*[:=]\s*)?[''"](:[^''"]+)[''"]')) {
                $dep = $m.Groups[1].Value
                if (-not $seen.ContainsKey($dep)) { $seen[$dep] = $true; $queue.Enqueue($dep) }
            }
        }
    }
    return @($seen.Keys | ForEach-Object { $_.TrimStart(':').Split(':')[0] } | Sort-Object -Unique)
}

function Wait-Mutex([System.Threading.Mutex] $m, [int] $ms) {
    try { return $m.WaitOne($ms) } catch [System.Threading.AbandonedMutexException] { return $true }
}

$globalMutex = [System.Threading.Mutex]::new($false, 'Global\RTP_Gradle_Build_Mutex')
$held = [System.Collections.Generic.List[System.Threading.Mutex]]::new()
$waited = 0
if ($projectPaths.Count -gt 0 -and -not $hasOtherTasks) {
    $lockNames = Get-ModuleLockNames $projectPaths
    $modMutexes = @($lockNames | ForEach-Object { [System.Threading.Mutex]::new($false, ('Global\RTP_Gradle_Lock_' + $_)) })
    $label = ':' + ($lockNames -join ', :')
    while ($held.Count -eq 0) {
        $haveGlobal = Wait-Mutex $globalMutex 500
        if ($haveGlobal) {
            $got = [System.Collections.Generic.List[System.Threading.Mutex]]::new()
            try {
                foreach ($m in $modMutexes) {
                    if (Wait-Mutex $m 4500) { $got.Add($m) } else { break }
                }
                if ($got.Count -eq $modMutexes.Count) {
                    $held = $got
                } else {
                    foreach ($g in $got) { $g.ReleaseMutex() }
                }
            } finally {
                $globalMutex.ReleaseMutex()
            }
        }
        if ($held.Count -gt 0) { break }
        $waited += 5
        $what = if ($haveGlobal) { 'module locks ' + $label } else { 'global build lock (for ' + $label + ')' }
        [Console]::Out.WriteLine('[gradlew] Waiting for ' + $what + '... (' + $waited + 's)')
        if ($waited -ge $timeoutSeconds) {
            [Console]::Error.WriteLine('[gradlew] Timed out waiting for module locks ' + $label)
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
    $held.Add($globalMutex)
    # Module runs need the global lock to start, so once each module lock is free it stays free.
    $tops = @([regex]::Matches($settingsText, 'include\s*\(?\s*[''"]:?([A-Za-z0-9_.\-]+)') |
        ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
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
    foreach ($h in $held) { try { $h.ReleaseMutex() } catch {} }
}
exit $exitCode
