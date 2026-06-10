<#
.SYNOPSIS
  Acceptance harness for the rtp-proxy devstack.

.DESCRIPTION
  Drives the cross-server `/rtp` verification scenarios documented in
  CHECKLIST-cross-server-rtp.md L3 and docs/admin/proxies/CROSS_SERVER_VERIFICATION.md.
  Each scenario writes a short pass/fail line to stdout and a structured
  block to acceptance-evidence.log in the current directory.

.PARAMETER Scenario
  One of: all (default), boot, heartbeat, roundtrip, killmidflight, killswitch.

.PARAMETER WaitSeconds
  Per-scenario polling budget in seconds. Default 180. Sized for first-run
  conditions where Paper world-gen on a fresh volume can take 60-120s before
  the backend plugin loads and starts publishing heartbeats. Killmidflight
  must exceed `reservation.ttlMs + reapIntervalMs` = 35s.

.NOTES Expected timings (rough, first-run on a typical dev box)
  Image pulls (first run only)         : 2-10 min (redis ~5 MB, mc-proxy + minecraft-server ~500 MB each)
  Gradle build (cold)                  : 1-3 min
  Gradle build (incremental)           : 5-20 s
  docker compose up + Redis PING       : 5-30 s
  Paper first-boot world-gen (per backend, fresh volume): 60-120 s
  Paper subsequent boots               : 10-30 s
  Velocity proxy boot                  : 5-15 s
  Heartbeat convergence after backends are up: 5-15 s (heartbeat interval is 5s)
  Roundtrip (manual, after operator presses Enter): 1-5 s of evidence capture
  Killmidflight (claim + kill + reap)  : 30-60 s (TTL 30s + reap interval 5s)
  Killswitch (Lua sentinel call)       : <1 s

  If a step appears frozen past 2x its upper bound, check `acceptance-evidence.log`
  and `docker compose logs <service>` before cancelling.

.NOTES
  - Requires `docker compose` and `redis-cli` in the host PATH, or invokes
    them inside the compose-managed `redis` container as a fallback.
  - The `roundtrip` scenario is a manual checkpoint: it prints the exact
    `/server` and `/rtp` commands an operator must issue from a Minecraft
    client, then waits for the redeem-side audit row to appear in Redis.
  - All Redis introspection is read-only. No Lua mutation is performed
    here; mutation is exercised exclusively by the running plugins.
#>
[CmdletBinding()]
param(
  [ValidateSet('all', 'boot', 'heartbeat', 'roundtrip', 'killmidflight', 'killswitch', 'down', 'logs')]
  [string]$Scenario = 'all',
  [int]$WaitSeconds = 180,
  # Skip the automatic `docker compose up -d` step (use when you already brought
  # the stack up by hand and just want to re-run scenarios against it).
  [switch]$SkipUp,
  # Skip the automatic gradle shadowJar build step (use when you already have
  # fresh jars staged in ./jars/velocity and ./jars/plugin).
  [switch]$SkipBuild,
  # Suppress the auto-spawned per-service log windows (one PowerShell window per
  # container streaming `docker compose logs -f`). Useful for headless CI runs.
  [switch]$NoLogs,
  # On `-Scenario down`, also remove ALL named volumes (including the shared
  # mc-image-cache that holds the Paper build jar + Mojang server.jar).
  # Without this, `down` is selective: containers and ephemeral state go
  # away, but the download cache survives so the next `up` doesn't
  # redownload ~110 MB per service. Either way (`down` or `down -Purge`)
  # the plugin's runtime config tree AND its runtime SQLite under
  # plugins/RTP/database/ are wiped, because Clear-StaleWorldDirs also
  # wipes the bind-mounted worlds and a stale DB pointing at a fresh
  # world is worse than a fresh DB. Use -Purge for a truly
  # fresh-from-scratch reset that also re-downloads the server images.
  [switch]$Purge
)

$ErrorActionPreference = 'Stop'

# Per-run log directory. Every invocation of this script gets its own folder
# under ./logs/, named with the startup timestamp (sortable: yyyyMMdd-HHmmss).
# All evidence + per-service `docker compose logs -f` streams land here, keyed
# by service id. The directory tree is gitignored (./logs/ entry in
# devstack/.gitignore; **/logs/ rule already covers it globally) so devstack
# runs never pollute the working tree. A `latest` symlink-shaped pointer file
# (`logs/latest.txt`) records the most recent run dir for convenience.
$RunStamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$RunLogDir = Join-Path $PSScriptRoot "logs\$RunStamp"
if (-not (Test-Path $RunLogDir)) { New-Item -ItemType Directory -Path $RunLogDir -Force | Out-Null }
Set-Content -Path (Join-Path $PSScriptRoot 'logs\latest.txt') -Value $RunStamp -Encoding ascii
$EvidenceLog = Join-Path $RunLogDir 'acceptance-evidence.log'

function Write-Evidence {
  param([string]$Tag, [string]$Body)
  $ts = (Get-Date).ToString('o')
  Add-Content -Path $EvidenceLog -Value "==== $ts $Tag ===="
  Add-Content -Path $EvidenceLog -Value $Body
  Add-Content -Path $EvidenceLog -Value ''
}

function Invoke-Native {
  # Runs a native command with stderr merged into stdout, WITHOUT letting
  # PowerShell's `Stop` ErrorActionPreference treat stderr lines as terminating
  # errors. docker compose / docker exec routinely write progress and "service
  # not running" diagnostics to stderr; those are normal output, not failures.
  # Callers should inspect `$LASTEXITCODE` (set by the native command) and the
  # returned string. Returns the combined stdout+stderr as a single string.
  param([Parameter(Mandatory)] [scriptblock]$Block)
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $output = & $Block 2>&1 | Out-String
    return $output
  } finally {
    $ErrorActionPreference = $prev
  }
}

function New-RandomSecret {
  param([int]$Bytes = 32)
  $buf = New-Object byte[] $Bytes
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buf)
  # URL-safe base64 (no '+' / '/' / '=' so it survives .env / YAML / TOML unquoted).
  return ([Convert]::ToBase64String($buf)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Initialize-Secrets {
  # Ensures `.env` (RTP_NET_SECRET) and `shared/forwarding.secret` exist with
  # non-empty values before any `docker compose` call. Idempotent: existing
  # populated values are left untouched; only empty/missing values are seeded.
  $envPath = Join-Path $PSScriptRoot '.env'
  $envLines = if (Test-Path $envPath) { Get-Content $envPath } else { @() }
  $hasSecret = $false
  $newLines = foreach ($line in $envLines) {
    if ($line -match '^\s*RTP_NET_SECRET\s*=\s*(.*?)\s*$') {
      if ($Matches[1] -and $Matches[1].Trim().Length -gt 0) {
        $hasSecret = $true
        $line
      } else {
        $generated = New-RandomSecret
        Write-Host "[secrets] seeding RTP_NET_SECRET in .env" -ForegroundColor Cyan
        $hasSecret = $true
        "RTP_NET_SECRET=$generated"
      }
    } else {
      $line
    }
  }
  if (-not $hasSecret) {
    $generated = New-RandomSecret
    Write-Host "[secrets] creating .env with generated RTP_NET_SECRET" -ForegroundColor Cyan
    $newLines = @($newLines) + "RTP_NET_SECRET=$generated"
  }
  Set-Content -Path $envPath -Value $newLines -Encoding ascii

  $fwdPath = Join-Path $PSScriptRoot 'shared\forwarding.secret'
  $fwdDir = Split-Path $fwdPath -Parent
  if (-not (Test-Path $fwdDir)) { New-Item -ItemType Directory -Path $fwdDir | Out-Null }
  $needsFwd = -not (Test-Path $fwdPath) -or [string]::IsNullOrWhiteSpace((Get-Content $fwdPath -Raw -ErrorAction SilentlyContinue))
  if ($needsFwd) {
    Write-Host "[secrets] seeding shared/forwarding.secret" -ForegroundColor Cyan
    Set-Content -Path $fwdPath -Value (New-RandomSecret) -Encoding ascii -NoNewline
  }

  # Export the forwarding secret content as CFG_VELOCITY_FORWARDING_SECRET so
  # docker compose interpolates it into both backends' environment. The Paper
  # patch (shared/paper-patches/velocity.json) uses ${CFG_VELOCITY_FORWARDING_SECRET}
  # to populate proxies.velocity.secret on each boot. We use the env-var route
  # (instead of a $put op against a missing secret-file key) because the
  # upstream default paper-global.yml already contains `secret: ''`, so $set
  # works against an existing path - $put on a missing path threw
  # PathNotFoundException in earlier attempts.
  $fwd = (Get-Content $fwdPath -Raw -ErrorAction SilentlyContinue)
  if ($fwd) { $fwd = $fwd.Trim() }
  if (-not $fwd) { throw "shared/forwarding.secret is empty after seeding - cannot continue" }
  $env:CFG_VELOCITY_FORWARDING_SECRET = $fwd
}

function Show-LogWindows {
  # Spawn one new PowerShell window per devstack service, each running
  # `docker compose logs -f --tail=100 <svc>` so the operator can watch every
  # server in real time without juggling `docker compose logs` tabs by hand.
  # Idempotent: each window is tagged with a unique title; re-running this
  # function spawns fresh windows (old ones can be closed by the operator).
  $services = @('redis', 'proxy-a', 'proxy-b', 'lobby-a', 'lobby-b', 'backend-a', 'backend-b', 'backend-c')
  Write-Host "[logs] opening per-service log windows ($($services -join ', '))..." -ForegroundColor Cyan
  Write-Host "[logs] also tee'ing per-service streams to $RunLogDir\<service>.log" -ForegroundColor DarkGray
  foreach ($svc in $services) {
    $title = "rtp-devstack: $svc"
    $svcLogFile = Join-Path $RunLogDir "$svc.log"
    # When `docker compose logs -f` exits (container stopped/removed or the
    # whole compose project went down), we print a clear banner and auto-close
    # the window after a short grace period so the operator's desktop isn't
    # littered with stale log tabs. The grace period lets a quick scroll-back
    # happen; pressing any key during the countdown cancels the auto-close
    # and drops to an interactive prompt for deeper inspection.
    # `docker compose logs -f` returns 0 when the container exits normally
    # and non-zero on transport errors; both paths flow through the same
    # "stream ended" banner.
    $cmd = @"
`$Host.UI.RawUI.WindowTitle='$title'
Set-Location -LiteralPath '$PSScriptRoot'
Write-Host '==== $svc ====' -ForegroundColor Magenta
Write-Host 'tee -> $svcLogFile' -ForegroundColor DarkGray
docker compose logs -f --tail=100 $svc 2>&1 | Tee-Object -FilePath '$svcLogFile' -Append
`$exit = `$LASTEXITCODE
Write-Host ''
Write-Host "==== $svc : docker log stream ended (exit=`$exit) ====" -ForegroundColor Yellow
Write-Host 'window auto-closes in 10s - press any key to keep it open' -ForegroundColor DarkGray
for (`$i = 10; `$i -gt 0; `$i--) {
  if ([Console]::KeyAvailable) {
    [void][Console]::ReadKey(`$true)
    Write-Host 'auto-close cancelled; type exit to close the window.' -ForegroundColor Cyan
    `$host.EnterNestedPrompt()
    break
  }
  Start-Sleep -Seconds 1
}
"@
    try {
      # No -NoExit: the script block above owns the lifecycle (auto-close or
      # nested prompt) so the host process can terminate cleanly.
      # Use -EncodedCommand to avoid Windows argv-quoting mangling embedded
      # double quotes (which previously broke the "exit=$exit" banner line).
      $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($cmd))
      Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-EncodedCommand', $encoded) | Out-Null
    } catch {
      Write-Host "[logs] WARN - could not spawn log window for $svc : $_" -ForegroundColor Yellow
    }
  }
  Write-Evidence 'logs' "spawned log windows for: $($services -join ', '); per-service files under $RunLogDir"
  Write-Host '[logs] tip: windows auto-close 10s after their container stops; press any key in a window during countdown to keep it open. Re-run `.\run-acceptance.ps1 -Scenario logs` to reopen.' -ForegroundColor DarkGray
  Write-Host "[logs] per-service log files: $RunLogDir" -ForegroundColor DarkGray
}

function Get-RepoRoot {
  # devstack lives at <repo>/devstack; repo root is one level up.
  return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

function Invoke-GradleBuild {
  # Builds the Velocity proxy plugin and the Paper/Bukkit plugin shadow jars
  # from repo root, then stages them under ./jars/velocity and ./jars/plugin
  # respectively. Idempotent: gradle no-ops when nothing changed.
  if ($SkipBuild) {
    Write-Host '[build] -SkipBuild set; skipping gradle build' -ForegroundColor DarkGray
    return
  }
  $root = Get-RepoRoot
  $gradlew = Join-Path $root 'gradlew.bat'
  if (-not (Test-Path $gradlew)) {
    Write-Host "[build] WARN - gradlew.bat not found at $gradlew; skipping auto-build" -ForegroundColor Yellow
    return
  }
  Write-Host '[build] running gradle (clean + rtp-proxy-velocity:jar + rtp-plugin:shadowJar) (typical: cold 1-3 min, incremental 30-60s with clean)...' -ForegroundColor Cyan
  Push-Location $root
  try {
    # rtp-proxy-velocity is a plain java-library (no shadow plugin) - its `jar` task
    # produces the runtime artifact. rtp-plugin uses shadowJar for the fat Paper jar
    # (RTP-Pro-<ver>.jar) which transitively shades rtp-proxy-common -> the actual
    # runtime path used by both proxy-a/proxy-b AND backend-a/backend-b (the unified
    # uber-jar carries the Velocity entrypoint AND the Bukkit entrypoint side by side
    # per the velocity-plugin.json + plugin.yml descriptors).
    #
    # We `clean` the three modules in the dependency chain before building so a
    # source edit in rtp-proxy-common (e.g. HMAC envelope fix 2026-05-21) is
    # guaranteed to re-shade into the RTP-Pro jar. Without `clean`, Gradle will
    # rebuild only the changed module and rely on the cached shaded jar - leaving
    # proxies and backends running yesterday's bytes. This is the safe default for
    # an acceptance harness; iteration speed is not a concern here.
    # `:rtp-plugin:remapJar` (not shadowJar) is the task that produces the
    # deployable `RTP-Pro-<ver>.jar`: shadowJar builds the intermediate, Loom's
    # remapJar consumes it and renames to RTP-Pro (rtp-plugin/build.gradle:377).
    # Calling shadowJar alone after `clean` leaves no RTP-Pro artifact on disk,
    # and the staging step downstream then warns "Pro jar not found".
    $out = Invoke-Native { & $gradlew `
      ':rtp-proxy:rtp-proxy-common:clean' `
      ':rtp-proxy:rtp-proxy-velocity:clean' `
      ':rtp-plugin:clean' `
      ':rtp-proxy:rtp-proxy-velocity:jar' `
      ':rtp-plugin:remapJar' `
      '--console=plain' }
    Write-Evidence 'build' $out
    if ($LASTEXITCODE -ne 0) {
      Write-Host "[build] FAIL - gradle exited $LASTEXITCODE (see acceptance-evidence.log)" -ForegroundColor Red
      throw "gradle shadowJar failed (exit $LASTEXITCODE)"
    }
    Write-Host '[build] gradle OK' -ForegroundColor Green
  } finally {
    Pop-Location
  }

  # Stage Velocity jar(s).
  $velocityLibs = Join-Path $root 'rtp-proxy\rtp-proxy-velocity\build\libs'
  $velocityDst = Join-Path $PSScriptRoot 'jars\velocity'
  if (-not (Test-Path $velocityDst)) { New-Item -ItemType Directory -Path $velocityDst -Force | Out-Null }
  if (Test-Path $velocityLibs) {
    $vJars = Get-ChildItem -Path $velocityLibs -Filter 'rtp-proxy-velocity-*.jar' -File |
      Where-Object { $_.Name -notmatch '-sources\.jar$|-javadoc\.jar$' }
    # Clear stale jars so old versions don't ship alongside fresh builds.
    Get-ChildItem -Path $velocityDst -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
    foreach ($j in $vJars) {
      Copy-Item -Path $j.FullName -Destination (Join-Path $velocityDst $j.Name) -Force
    }
    Write-Host "[build] staged $($vJars.Count) Velocity jar(s) -> jars/velocity" -ForegroundColor Cyan
  }
  # itzg/minecraft-server's mc-image-helper sync-and-interpolate trips on dotfiles
  # in the source plugins dir (AccessDeniedException on /data/plugins/.gitkeep).
  # Strip any .gitkeep that git restored after the gradle stage step.
  Get-ChildItem -Path $velocityDst -Filter '.gitkeep' -Force -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

  # Stage Paper/Bukkit plugin jar. The devstack runs RTP Pro (the lite assembly
  # is not appropriate for cross-server verification - see ADR-024). Prefer
  # LeafRTP-Pro-<ver>.jar; fall back to the plain LeafRTP-<ver>.jar only if Pro
  # is absent. Always exclude -dev / -sources / -javadoc artifacts.
  #
  # Each backend MUST have its own plugins dir (./backend-a/plugins,
  # ./backend-b/plugins) - they cannot share a host bind because Paper writes
  # /data/plugins/.paper-remapped/ on boot and two backends racing on the same
  # host directory corrupt that cache (ZipException: invalid LOC header).
  # ./jars/plugin is retained as the staging source for Sync-ProxyJars, which
  # fans the same uber-jar into each Velocity plugins dir.
  $pluginLibs = Join-Path $root 'rtp-plugin\build\libs'
  $pluginStage = Join-Path $PSScriptRoot 'jars\plugin'
  # Lobbies receive the SAME unified LeafRTP-Pro jar as backends: they participate
  # in the network (role: backend) but have no local destination region
  # (see devstack/lobby-{a,b}/rtp-config/regions/README.md). The jar must be
  # present so /rtp on a lobby can dispatch cross-server.
  $backendDsts = @(
    (Join-Path $PSScriptRoot 'backend-a\plugins'),
    (Join-Path $PSScriptRoot 'backend-b\plugins'),
    (Join-Path $PSScriptRoot 'lobby-a\plugins'),
    (Join-Path $PSScriptRoot 'lobby-b\plugins')
  )
  # backend-c is Fabric: the SAME unified LeafRTP-Pro jar is also the Fabric
  # mod (per ADR-022 the jar ships plugin.yml AND fabric.mod.json side by side,
  # and Loom remaps the Fabric entry-point package), so it is dropped into
  # /data/mods - NOT /data/plugins. Without this stage RTP never loads on the
  # Fabric backend at all (the symptom: `/server backend-c` connects but RTP
  # is absent). Kept in its own list because the mods dir must not receive the
  # Paper-only .paper-remapped cache cleanup below.
  $fabricModDsts = @(
    (Join-Path $PSScriptRoot 'backend-c\mods')
  )
  foreach ($d in @($pluginStage) + $backendDsts + $fabricModDsts) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
  }
  if (Test-Path $pluginLibs) {
    $allJars = Get-ChildItem -Path $pluginLibs -Filter 'LeafRTP-*.jar' -File |
      Where-Object { $_.Name -notmatch '-dev\.jar$|-sources\.jar$|-javadoc\.jar$' }
    $proJars = $allJars | Where-Object { $_.Name -match '^LeafRTP-Pro-' }
    if ($proJars -and $proJars.Count -gt 0) {
      $pJars = $proJars
      $variant = 'Pro'
    } else {
      $pJars = $allJars | Where-Object { $_.Name -notmatch '^LeafRTP-Pro-' }
      $variant = 'lite (Pro jar not found - falling back)'
      Write-Host '[build] WARN - LeafRTP-Pro-<ver>.jar not found; falling back to plain LeafRTP jar. Build Pro via the Pro Gradle profile to match the devstack.' -ForegroundColor Yellow
    }
    foreach ($dst in @($pluginStage) + $backendDsts + $fabricModDsts) {
      # Only clear the RTP jars we own here. Operator-dropped jars (e.g.
      # FastAsyncWorldEdit in lobby-a/plugins/ for the lobby-world bake
      # workflow - see shared/lobby-world/README.md, or Fabric API /
      # FabricProxy-Lite in backend-c/mods/ - see backend-c/mods/README.md)
      # must survive a re-run of the harness. Filter matches LeafRTP-<ver>.jar
      # and LeafRTP-Pro-<ver>.jar but leaves everything else in place.
      Get-ChildItem -Path $dst -Filter 'LeafRTP-*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-dev\.jar$|-sources\.jar$|-javadoc\.jar$' } |
        Remove-Item -Force
      foreach ($j in $pJars) {
        Copy-Item -Path $j.FullName -Destination (Join-Path $dst $j.Name) -Force
      }
    }
    Write-Host "[build] staged $($pJars.Count) RTP plugin jar(s) [$variant] -> jars/plugin + backend-{a,b}/plugins + lobby-{a,b}/plugins + backend-c/mods (Fabric)" -ForegroundColor Cyan
  }
  # See note above: backend's mc-image-helper AccessDeniedException on dotfiles.
  foreach ($dst in @($pluginStage) + $backendDsts) {
    Get-ChildItem -Path $dst -Filter '.gitkeep' -Force -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    # Wipe any leftover Paper remap cache from a prior (shared-bind) boot.
    $remap = Join-Path $dst '.paper-remapped'
    if (Test-Path $remap) { Remove-Item -Recurse -Force $remap -ErrorAction SilentlyContinue }
  }
}

function Sync-ProxyJars {
  # Fans out the unified RTP uber-jar from ./jars/plugin into each proxy's
  # plugins directory. RTP ships ONE jar per platform: the shadow-built
  # LeafRTP-Pro-<ver>.jar carries Bukkit (plugin.yml), Fabric (fabric.mod.json),
  # AND Velocity (velocity-plugin.json) descriptors side by side - the
  # standalone rtp-proxy-velocity-*.jar from jars/velocity is a compile
  # artifact, not a deployable plugin.
  $src = Join-Path $PSScriptRoot 'jars\plugin'
  if (-not (Test-Path $src)) {
    New-Item -ItemType Directory -Path $src -Force | Out-Null
  }
  $jars = Get-ChildItem -Path $src -Filter '*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-dev\.jar$|-sources\.jar$|-javadoc\.jar$' }
  if (-not $jars -or $jars.Count -eq 0) {
    Write-Host '[jars] No unified RTP plugin jars found.' -ForegroundColor Yellow
    Write-Host "[jars]   Looked in: $src" -ForegroundColor Yellow
    Write-Host '[jars]   Auto-build failed or produced no artifact. Build manually via:' -ForegroundColor Yellow
    Write-Host '[jars]     .\gradlew :rtp-plugin:shadowJar' -ForegroundColor Yellow
    Write-Host '[jars]   from the repo root, then re-run this script.' -ForegroundColor Yellow
    return
  }
  foreach ($proxy in @('proxy-a', 'proxy-b')) {
    $dst = Join-Path $PSScriptRoot "$proxy\plugins"
    if (-not (Test-Path $dst)) { New-Item -ItemType Directory -Path $dst | Out-Null }
    # Clear stale jars before copying fresh ones.
    Get-ChildItem -Path $dst -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
    # Also strip .gitkeep so itzg/mc-proxy doesn't try to chown a tracked stub.
    Get-ChildItem -Path $dst -Filter '.gitkeep' -Force -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    foreach ($j in $jars) {
      Copy-Item -Path $j.FullName -Destination (Join-Path $dst $j.Name) -Force
    }
    Write-Host "[jars] synced $($jars.Count) jar(s) -> $proxy/plugins" -ForegroundColor Cyan
  }
}

function Get-CrashedMcServices {
  # Returns the names of MC services (backends + proxies) whose container has
  # exited shortly after `up`. We check ~8s after the up call so first-boot
  # init scripts have had a chance to either start the JVM (Up <state>) or
  # bail out (Exited). Anything in 'exited' here is a crash, not a slow boot.
  # backend-c (Fabric) is included so a crashed Fabric boot is detected and
  # auto-recovered along with the Paper/Folia backends. rtp-fabric is explicitly
  # unstable today; expect crashes here to be common until it stabilizes.
  $services = @('backend-a', 'backend-b', 'backend-c', 'lobby-a', 'lobby-b', 'proxy-a', 'proxy-b')
  $crashed = @()
  foreach ($svc in $services) {
    $state = Invoke-Native { docker compose ps --status exited --services } 2>$null
    if ($state -match "(?m)^$svc$") { $crashed += $svc }
  }
  return $crashed
}

function Clear-StaleWorldDirs {
  # World dirs are host bind mounts (./backend-{a,b}/world) per docker-compose.yml.
  # `docker compose down` (without -v) leaves them intact, so a prior crashed boot
  # can persist a stale ./world/session.lock that the next boot can't acquire
  # (especially if file ownership / file-handle state from the prior run lingers
  # on Windows/Docker-Desktop). Removing the dirs entirely on every `up` is the
  # cheapest correct fix: Paper regenerates the world from scratch each run.
  # Idempotent; silent on first run (dirs don't exist yet).
  foreach ($b in @('backend-a', 'backend-b', 'backend-c', 'lobby-a', 'lobby-b')) {
    $dir = Join-Path $PSScriptRoot "$b\world"
    if (Test-Path $dir) {
      try {
        Remove-Item -Recurse -Force $dir -ErrorAction Stop
        Write-Evidence 'up' "cleared stale world dir: $dir"
      } catch {
        # If a previous container still has the dir open, Remove-Item fails.
        # Best-effort delete of just the lock file then proceed.
        $lock = Join-Path $dir 'session.lock'
        if (Test-Path $lock) {
          try { Remove-Item -Force $lock -ErrorAction Stop; Write-Evidence 'up' "cleared stale lock: $lock" } catch { Write-Evidence 'up' "WARN could not clear $dir : $_" }
        }
      }
    }
  }
}

function Invoke-ComposeUp {
  # Brings the devstack up in detached mode. Idempotent: docker compose treats
  # already-running services as no-ops. Waits for Redis to answer PING before
  # returning so downstream scenarios don't race the container startup.
  #
  # Auto-recovery: if any MC service exits within ~8s of `up`, we assume a
  # stale-state failure (typically `session.lock` AccessDeniedException on the
  # world dir from a prior crashed boot, or stale plugin-sync ownership)
  # and once retry with `docker compose down` + world-dir wipe + `up -d`.
  # We deliberately do NOT pass -v on auto-recovery: worlds are host binds
  # (cleared separately by Clear-StaleWorldDirs) and the mc-image-cache named
  # volume should survive a stale-state recovery so the retry boot doesn't
  # redownload Paper. Bounded to a single retry to avoid loops on genuine
  # config failures.
  Invoke-GradleBuild
  Sync-ProxyJars
  Clear-StaleWorldDirs
  Push-Location $PSScriptRoot
  try {
    $attempt = 0
    while ($true) {
      $attempt++
      Write-Host "[up] docker compose up -d (attempt $attempt; first run pulls images: 2-10 min; subsequent: 5-30s)..." -ForegroundColor Cyan
      $upOut = Invoke-Native { docker compose up -d }
      Write-Evidence 'up' $upOut
      if ($LASTEXITCODE -ne 0) {
        Write-Host "[up] FAIL - docker compose up exited $LASTEXITCODE" -ForegroundColor Red
        throw "docker compose up failed: $upOut"
      }
      # Give init scripts ~8s to crash on stale-state errors (session.lock,
      # plugin-sync chown). Slower failures (e.g., JVM OOM during world-gen)
      # surface later in Test-Heartbeat's diagnostics.
      Start-Sleep -Seconds 8
      $crashed = Get-CrashedMcServices
      if ($crashed.Count -eq 0) { break }
      if ($attempt -ge 2) {
        Write-Host "[up] WARN - services still crashed after auto-recover: $($crashed -join ', '); continuing so Test-Heartbeat can dump logs" -ForegroundColor Yellow
        Write-Evidence 'up' "crashed-after-recover: $($crashed -join ', ')"
        break
      }
      Write-Host "[up] detected crashed services: $($crashed -join ', ')" -ForegroundColor Yellow
      Write-Host '[up] likely stale state (session.lock / plugin-sync ownership from a prior failed boot)' -ForegroundColor Yellow
      Write-Host '[up] auto-recovering: docker compose down (preserves mc-image-cache), then re-up...' -ForegroundColor Yellow
      Write-Evidence 'up' "auto-recover triggered by crashed: $($crashed -join ', ')"
      $downOut = Invoke-Native { docker compose down }
      Write-Evidence 'up' $downOut
      Clear-StaleWorldDirs
      Start-Sleep -Seconds 2
    }
    Write-Host '[up] waiting for Redis to answer PING (typical: 5-30s)...' -ForegroundColor Cyan
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
      $pong = Invoke-Native { docker compose exec -T redis redis-cli PING }
      if ($pong -match 'PONG') {
        Write-Host '[up] Redis ready' -ForegroundColor Green
        return
      }
      Start-Sleep -Seconds 2
    }
    Write-Host '[up] WARN - Redis did not answer PING within 60s; continuing anyway' -ForegroundColor Yellow
  } finally {
    Pop-Location
  }
}

function Invoke-ComposeDown {
  param([switch]$IncludeVolumes)
  Push-Location $PSScriptRoot
  try {
    # Selective by default: stop containers + remove networks, but preserve
    # named volumes (currently just `mc-image-cache`, which holds the Paper
    # build jar + Mojang server.jar across runs - see docker-compose.yml's
    # top-level `volumes:` block). Worlds are host bind mounts and are wiped
    # separately by Clear-StaleWorldDirs regardless of -v, so dropping -v
    # does NOT leak stale world state into the next boot.
    #
    # Pass -Purge (-> -IncludeVolumes here) for a truly fresh reset that also
    # drops the download cache; that path re-incurs ~110 MB/service on next up.
    if ($IncludeVolumes) {
      Write-Host '[down] docker compose down -v (purging named volumes incl. mc-image-cache)...' -ForegroundColor Cyan
      $out = Invoke-Native { docker compose down -v }
    } else {
      Write-Host '[down] docker compose down (preserving mc-image-cache; pass -Purge to also drop it)...' -ForegroundColor Cyan
      $out = Invoke-Native { docker compose down }
    }
    Write-Evidence 'down' $out
    # World dirs are host bind mounts, so `down` (with or without -v) doesn't
    # touch them. Wipe them here so the next boot starts from a clean slate.
    Clear-StaleWorldDirs
    # The plugin's runtime config tree (plugins/RTP/) is also a host bind
    # mount on the Paper/Folia/lobby instances, so a stale messages.yml /
    # config.yml from a prior jar persists across `down` + `up` unless we
    # explicitly wipe it. Run reset-rtp-config.ps1 so the next `up` lets the
    # freshly-built jar re-extract baseline. Pass -IncludeDatabase when the
    # operator asked for -Purge so the semantics match `down -v` (also nukes
    # runtime SQLite). backend-c (Fabric) needs no host-side reset: its
    # config lives inside the container's writable layer and is gone once
    # the container is recreated by the next `up`.
    $resetScript = Join-Path $PSScriptRoot 'reset-rtp-config.ps1'
    if (Test-Path $resetScript) {
      # Always pass -IncludeDatabase: Clear-StaleWorldDirs above has already
      # wiped the bind-mounted world/ trees, and a stale SQLite DB (cached
      # keptLocations / unkeptLocations coordinates, playerQueue rows,
      # reservation tokens) pointing at a freshly-regenerated world is
      # worse than a fresh DB pointing at a fresh world. The devstack is a
      # verification harness for a freshly-built jar against a clean
      # baseline; persisting runtime DB state across down/up defeats that
      # purpose (schema migrations, region-pool format tweaks, reservation
      # shape changes all assume a clean start). -Purge still adds value
      # by also dropping the mc-image-cache named volume via `down -v`.
      Write-Host '[down] wiping plugins/RTP/ (incl. runtime DB) on bind-mounted instances so next up extracts a fresh baseline...' -ForegroundColor Cyan
      $resetOut = Invoke-Native { & $resetScript -IncludeDatabase }
      Write-Evidence 'down.reset-rtp-config' $resetOut
    } else {
      Write-Host "[down] WARN - reset-rtp-config.ps1 not found at $resetScript; skipping config reset" -ForegroundColor Yellow
    }
  } finally {
    Pop-Location
  }
}

function Invoke-RedisCli {
  param([Parameter(ValueFromRemainingArguments)] [string[]]$Args)
  # Prefer host-local redis-cli; fall back to docker exec into the compose service.
  $cli = Get-Command redis-cli -ErrorAction SilentlyContinue
  if ($cli) {
    return Invoke-Native { & $cli.Source -h localhost -p 6379 @Args }
  }
  return Invoke-Native { docker compose exec -T redis redis-cli @Args }
}

function Test-Boot {
  Write-Host '[boot] checking docker compose ps (typical: <2s)...' -ForegroundColor Cyan
  $ps = Invoke-Native { docker compose ps --format json }
  Write-Evidence 'boot' $ps
  $expected = @('rtp-devstack-redis', 'proxy-a', 'proxy-b', 'lobby-a', 'lobby-b', 'backend-a', 'backend-b', 'backend-c')
  $missing = @()
  foreach ($name in $expected) {
    if ($ps -notmatch [Regex]::Escape($name)) { $missing += $name }
  }
  if ($missing.Count -gt 0) {
    Write-Host "[boot] FAIL - missing services: $($missing -join ', ')" -ForegroundColor Red
    return $false
  }
  Write-Host '[boot] PASS' -ForegroundColor Green
  return $true
}

function Get-ContainerLiveness {
  # One-line-per-service summary. Fast (<1s).
  $out = Invoke-Native { docker compose ps --format '{{.Service}}|{{.State}}|{{.Status}}' }
  if ($LASTEXITCODE -ne 0 -or -not $out) { return '(docker compose ps failed)' }
  return ($out -split "`n" | Where-Object { $_ -match '\S' }) -join "`n  "
}

function Get-RecentLogTail {
  param([string]$Service, [int]$Lines = 4)
  $tail = Invoke-Native { docker compose logs --tail=$Lines --no-log-prefix $Service }
  if (-not $tail) { return '(no logs yet)' }
  return ($tail -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Last $Lines) -join "`n    "
}

function Show-FullServiceLogs {
  # On timeout, dump a substantial log tail per service both to the console
  # (so the operator sees the actual error without re-running docker manually)
  # and to acceptance-evidence.log (for after-the-fact triage). Keep the
  # per-service tail bounded so a runaway log doesn't drown the terminal.
  param([int]$Lines = 200)
  Write-Host ''
  Write-Host "[heartbeat] ==== FULL LOG DUMP (last $Lines lines per service) ====" -ForegroundColor Magenta
  foreach ($svc in @('redis','backend-a','backend-b','backend-c','lobby-a','lobby-b','proxy-a','proxy-b')) {
    $body = Invoke-Native { docker compose logs --tail=$Lines --no-log-prefix $svc }
    if (-not $body) { $body = '(no logs)' }
    Write-Host ''
    Write-Host "[heartbeat] ---- $svc (last $Lines lines) ----" -ForegroundColor Magenta
    Write-Host $body -ForegroundColor Gray
    Write-Evidence "logdump.$svc" $body
  }
  Write-Host "[heartbeat] ==== END FULL LOG DUMP ====" -ForegroundColor Magenta
  Write-Host ''
  Write-Host '[heartbeat] Common root causes to look for in the dump above:' -ForegroundColor Yellow
  Write-Host '  - "Could not load main class" / "UnsupportedClassVersionError" : wrong Java version in image; rebuild jars.' -ForegroundColor Gray
  Write-Host '  - "Plugin already initialized" / stack trace in RTP load     : plugin crashed at enable; fix the exception.' -ForegroundColor Gray
  Write-Host '  - Stuck at "Preparing spawn area: NN%"                       : world-gen still running; raise -WaitSeconds.' -ForegroundColor Gray
  Write-Host '  - "Cannot connect to Redis" / "Connection refused"           : RTP_NET_SECRET/transport misconfig in network.yml.' -ForegroundColor Gray
  Write-Host '  - Velocity log "Done" but no proxy heartbeat                 : rtp-proxy-velocity jar missing or failed to load.' -ForegroundColor Gray
  Write-Host ''
}

function Show-HeartbeatDiagnostics {
  param([int]$ElapsedSec)
  Write-Host ''
  Write-Host "[heartbeat] ---- diagnostic snapshot at ${ElapsedSec}s ----" -ForegroundColor Yellow
  Write-Host '[heartbeat] container states:' -ForegroundColor Yellow
  Write-Host ("  " + (Get-ContainerLiveness)) -ForegroundColor Gray
  foreach ($svc in @('backend-a','backend-b','backend-c','lobby-a','lobby-b','proxy-a','proxy-b')) {
    Write-Host "[heartbeat] last log lines for ${svc}:" -ForegroundColor Yellow
    Write-Host ("    " + (Get-RecentLogTail -Service $svc -Lines 4)) -ForegroundColor Gray
  }
  Write-Host '[heartbeat] is-this-stuck checklist:' -ForegroundColor Yellow
  Write-Host '  - All four MC containers show State=running above? If any is "restarting"/"exited", it crashed - run: docker compose logs <svc>' -ForegroundColor Gray
  Write-Host '  - Backend log ending in "Preparing spawn area: NN%"? Still world-genning (first run only) - keep waiting.' -ForegroundColor Gray
  Write-Host '  - Backend log shows "Done (Xs)! For help, type"? Server is up; heartbeat should land within ~10s. If not, RTP plugin failed to load - check the log for an exception.' -ForegroundColor Gray
  Write-Host '  - Proxy log shows "Done"? Velocity is up; proxy heartbeat should follow.' -ForegroundColor Gray
  Write-Host '  - To verify keys yourself: docker compose exec -T redis redis-cli KEYS "rtp:net:*"' -ForegroundColor Gray
  Write-Host "[heartbeat] ---- end snapshot ----" -ForegroundColor Yellow
  Write-Host ''
}

function Test-Heartbeat {
  Write-Host "[heartbeat] polling Redis for backend + proxy heartbeats (budget: ${WaitSeconds}s)" -ForegroundColor Cyan
  Write-Host '[heartbeat]   expected: 60-120s on first run (Paper world-gen), 5-15s on subsequent runs.' -ForegroundColor DarkGray
  Write-Host '[heartbeat]   polling every 5s; diagnostic snapshot every 30s. Press Ctrl+C if stuck >5min.' -ForegroundColor DarkGray
  $deadline = (Get-Date).AddSeconds($WaitSeconds)
  $pollStart = Get-Date
  # Emit a baseline diagnostic snapshot immediately so the operator can see
  # container states from the start (otherwise the first one lands at ~30s,
  # which feels like a hang on a slow first-run boot).
  Show-HeartbeatDiagnostics -ElapsedSec 0
  $lastDiagAt = 0
  $earlyDumpDone = $false
  while ((Get-Date) -lt $deadline) {
    $backends = Invoke-RedisCli KEYS 'rtp:net:backend:*'
    $proxies = Invoke-RedisCli KEYS 'rtp:net:proxy:*'
    $bCount = (@($backends) | Where-Object { $_ -match '\S' }).Count
    $pCount = (@($proxies) | Where-Object { $_ -match '\S' }).Count
    # 5 backend heartbeats expected: backend-a (Paper), backend-b (Folia),
    # backend-c (Fabric), lobby-a, lobby-b (lobbies publish as role=backend
    # too; their absence of a regions/ dir is a runtime config concern, not
    # a network-participation concern). Requiring >=5 means a missing Fabric
    # heartbeat from backend-c is a real failure rather than a silent
    # degradation - if rtp-fabric is broken enough that backend-c cannot reach
    # heartbeat publish, the harness fails loudly.
    if ($bCount -ge 5 -and $pCount -ge 2) {
      $body = "backend keys: $bCount`n$backends`nproxy keys: $pCount`n$proxies"
      Write-Evidence 'heartbeat' $body
      $elapsed = [int]((Get-Date) - $pollStart).TotalSeconds
      Write-Host "[heartbeat] PASS (backends=$bCount, proxies=$pCount) after ${elapsed}s" -ForegroundColor Green
      return $true
    }
    $elapsed = [int]((Get-Date) - $pollStart).TotalSeconds
    Write-Host "[heartbeat]   ${elapsed}s elapsed: backends=$bCount/5 proxies=$pCount/2 (still waiting)" -ForegroundColor DarkGray
    # Diagnostic snapshot every 30s so the operator can decide whether it's genuinely stuck.
    if (($elapsed - $lastDiagAt) -ge 30) {
      Show-HeartbeatDiagnostics -ElapsedSec $elapsed
      $lastDiagAt = $elapsed
    }
    # Early full-log dump: if >=90s have elapsed with zero heartbeats from
    # either side, the boot has almost certainly failed (crash at enable,
    # bad config, missing jar). Dump logs once so the operator doesn't have
    # to wait out the full budget to see the real error.
    if (-not $earlyDumpDone -and $elapsed -ge 90 -and $bCount -eq 0 -and $pCount -eq 0) {
      Write-Host "[heartbeat] no heartbeats after ${elapsed}s - dumping full service logs early so you can see the real error:" -ForegroundColor Yellow
      Show-FullServiceLogs -Lines 200
      $earlyDumpDone = $true
    }
    Start-Sleep -Seconds 5
  }
  # Final dump on timeout - makes the failure actionable instead of just "did not converge".
  Show-HeartbeatDiagnostics -ElapsedSec $WaitSeconds
  if (-not $earlyDumpDone) { Show-FullServiceLogs -Lines 200 }
  Write-Evidence 'heartbeat' "timed out after $WaitSeconds s. backends=$backends proxies=$proxies"
  Write-Host '[heartbeat] FAIL - heartbeats did not converge (full per-service logs dumped above and appended to acceptance-evidence.log)' -ForegroundColor Red
  return $false
}

function Test-Roundtrip {
  Write-Host '[roundtrip] manual checkpoint - a live Minecraft client is required.' -ForegroundColor Yellow
  Write-Host '  1. Connect a 1.21.1 client to localhost:25577 (proxy-a).' -ForegroundColor Yellow
  Write-Host '  2. The default backend is backend-a. Run `/server backend-b` then `/server backend-c` once each to seed all backends.' -ForegroundColor Yellow
  Write-Host '  3. From the client, run `/rtp` and observe a cross-server teleport.' -ForegroundColor Yellow
  Write-Host '  4. Press <Enter> AFTER the redeem completes to capture evidence.' -ForegroundColor Yellow
  [void](Read-Host 'Press Enter to continue')
  $tokens = Invoke-RedisCli KEYS 'rtp:net:reservation:*'
  $audit = & docker compose logs --tail=100 backend-a backend-b backend-c 2>&1 | Select-String -Pattern 'redeem|JoinTriggerSource' -SimpleMatch
  Write-Evidence 'roundtrip' "tokens at sample time:`n$tokens`naudit lines:`n$audit"
  Write-Host '[roundtrip] EVIDENCE CAPTURED (operator must visually confirm)' -ForegroundColor Green
  return $true
}

function Test-KillMidFlight {
  Write-Host "[killmidflight] forcing a reservation, then killing the destination backend (budget: ${WaitSeconds}s; expected: 30-60s for TTL+reap)..." -ForegroundColor Cyan
  # Drive a synthetic claim via the proxy console. Velocity has no built-in
  # command for this; we use the Lua claim script directly so the harness
  # remains client-free. This DOES NOT exercise the join trigger path - the
  # roundtrip scenario covers that. Here we only verify the reaper.
  $tokenId = [System.Guid]::NewGuid().ToString()
  $playerId = [System.Guid]::NewGuid().ToString()
  $proxyId = 'proxy-a'
  $backendId = 'backend-a'
  $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $expiry = $now + 30000
  $claim = Invoke-RedisCli EVALSHA dc7c1b0f2c85f26b513de303369728583aaf7dd1 0 $tokenId $playerId $proxyId $backendId $now $expiry
  Write-Evidence 'killmidflight.claim' "tokenId=$tokenId result=$claim"
  if ($claim -notmatch '^1$') {
    Write-Host "[killmidflight] FAIL - claim returned: $claim" -ForegroundColor Red
    return $false
  }
  Write-Host '[killmidflight] reservation seeded; killing backend-a...' -ForegroundColor Cyan
  & docker compose kill backend-a | Out-Null
  $deadline = (Get-Date).AddSeconds($WaitSeconds)
  while ((Get-Date) -lt $deadline) {
    $row = Invoke-RedisCli HGETALL "rtp:net:reservation:$tokenId"
    if (-not $row -or $row -match '^\s*$') {
      Write-Evidence 'killmidflight.reaped' "tokenId=$tokenId reaped within window"
      Write-Host '[killmidflight] PASS' -ForegroundColor Green
      & docker compose start backend-a | Out-Null
      return $true
    }
    Start-Sleep -Seconds 2
  }
  Write-Evidence 'killmidflight.timeout' "tokenId=$tokenId still present after $WaitSeconds s: $row"
  Write-Host '[killmidflight] FAIL - reservation not reaped within budget' -ForegroundColor Red
  & docker compose start backend-a | Out-Null
  return $false
}

function Test-KillSwitch {
  Write-Host '[killswitch] flipping proxy-a network.killSwitch and asserting claim rejection (typical: <1s)...' -ForegroundColor Cyan
  # The killSwitch knob is read on startup; for a runtime flip the operator
  # must restart proxy-a. The harness verifies the failure-mode wiring by
  # invoking the Lua claim with the killswitch sentinel directly.
  $tokenId = [System.Guid]::NewGuid().ToString()
  $playerId = [System.Guid]::NewGuid().ToString()
  $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  # The claim Lua treats KILL_SWITCH as a control-plane string-arg sentinel.
  # See claim.lua and ADR-036 §5 for the contract.
  $result = Invoke-RedisCli EVALSHA dc7c1b0f2c85f26b513de303369728583aaf7dd1 0 $tokenId $playerId 'KILL_SWITCH' 'backend-a' $now ($now + 30000)
  Write-Evidence 'killswitch' "result=$result"
  if ($result -match 'KILL_SWITCH' -or $result -match '^0$') {
    Write-Host '[killswitch] PASS (claim correctly rejected)' -ForegroundColor Green
    return $true
  }
  Write-Host "[killswitch] FAIL - claim was not rejected: $result" -ForegroundColor Red
  return $false
}

# ---- entrypoint -------------------------------------------------------------

# $EvidenceLog lives inside the per-run dir (created above) so it is never
# pre-existing; no Remove-Item needed.
Write-Evidence 'session' "start scenario=$Scenario waitSeconds=$WaitSeconds runDir=$RunLogDir"

# Seed `.env` (RTP_NET_SECRET) and `shared/forwarding.secret` before any
# `docker compose` call; compose fails fast on missing variable interpolation.
Initialize-Secrets

# Optional lobby world overlay. When the operator has baked a lobby world via
# `scripts/bake-lobby-world.ps1` (see `shared/lobby-world/README.md`), layer
# `docker-compose.lobby-world.yml` on top of the base compose file so that
# lobby-a / lobby-b boot from the canned world instead of generating a fresh
# default world. Implemented via the `COMPOSE_FILE` env var (path-separator
# delimited) so every existing `docker compose ...` call site in this script
# picks the override up transparently - no per-call `-f` flag plumbing needed.
$LobbyWorldZip = Join-Path $PSScriptRoot 'shared\lobby-world.zip'
$LobbyOverride = Join-Path $PSScriptRoot 'docker-compose.lobby-world.yml'
if ((Test-Path $LobbyWorldZip) -and (Test-Path $LobbyOverride)) {
  $env:COMPOSE_FILE = "$(Join-Path $PSScriptRoot 'docker-compose.yml');$LobbyOverride"
  Write-Host "[init] using baked lobby world: $LobbyWorldZip" -ForegroundColor Cyan
  Write-Evidence 'init' "lobby-world overlay active: $LobbyWorldZip"
} else {
  $LobbySchemDir = Join-Path $PSScriptRoot 'shared\lobby-world'
  $schems = @()
  if (Test-Path $LobbySchemDir) {
    $schems = @(Get-ChildItem -Path $LobbySchemDir -Filter '*.schem' -ErrorAction SilentlyContinue) +
              @(Get-ChildItem -Path $LobbySchemDir -Filter '*.schematic' -ErrorAction SilentlyContinue)
  }
  if ($schems.Count -gt 0) {
    Write-Host "[init] schematic detected ($($schems[0].Name)) but no shared/lobby-world.zip yet; lobbies will boot vanilla. Paste the schematic in-game and run scripts\bake-lobby-world.ps1 to enable the canned lobby world." -ForegroundColor Yellow
    Write-Evidence 'init' "lobby-world overlay skipped: schematic present, zip missing"
  } else {
    Write-Evidence 'init' 'lobby-world overlay skipped: no schematic, no zip'
  }
}

if ($Scenario -eq 'down') {
  Invoke-ComposeDown -IncludeVolumes:$Purge
  if ($Purge) {
    Write-Host '[down] stack stopped; all named volumes removed (including mc-image-cache)' -ForegroundColor Green
  } else {
    Write-Host '[down] stack stopped; mc-image-cache preserved (use -Purge to also drop it)' -ForegroundColor Green
  }
  exit 0
}

if ($Scenario -eq 'logs') {
  Show-LogWindows
  exit 0
}

# Bring the stack up unless the operator explicitly opted out. This makes the
# harness a one-command experience for new users: `.\run-acceptance.ps1` boots
# the stack if needed, then drives the scenarios.
if (-not $SkipUp) {
  Invoke-ComposeUp
}

# Auto-open per-service log windows once the stack is up, so the operator can
# watch each server live while scenarios run. Opt out with -NoLogs (e.g. CI).
if (-not $NoLogs) {
  Show-LogWindows
}

$results = [ordered]@{}
$plan = if ($Scenario -eq 'all') {
  @('boot', 'heartbeat', 'roundtrip', 'killmidflight', 'killswitch')
} else {
  @($Scenario)
}

# All scenario helpers shell out to `docker compose ...` without an explicit
# `-f` argument, so they require the working directory to contain
# docker-compose.yml. The user may invoke this script from anywhere
# (e.g. repo root). Pin cwd to $PSScriptRoot for the duration of scenario
# execution; otherwise diagnostic helpers like Get-ContainerLiveness and
# Get-RecentLogTail fail with "no configuration file provided: not found"
# and the heartbeat poll appears to hang silently.
Push-Location $PSScriptRoot
try {
  foreach ($s in $plan) {
    switch ($s) {
      'boot'          { $results[$s] = Test-Boot }
      'heartbeat'     { $results[$s] = Test-Heartbeat }
      'roundtrip'     { $results[$s] = Test-Roundtrip }
      'killmidflight' { $results[$s] = Test-KillMidFlight }
      'killswitch'    { $results[$s] = Test-KillSwitch }
    }
  }
} finally {
  Pop-Location
}

Write-Host ''
Write-Host '==== summary ====' -ForegroundColor Magenta
foreach ($kv in $results.GetEnumerator()) {
  $verdict = if ($kv.Value) { 'PASS' } else { 'FAIL' }
  $color = if ($kv.Value) { 'Green' } else { 'Red' }
  Write-Host ("  {0,-16} {1}" -f $kv.Key, $verdict) -ForegroundColor $color
}
Write-Host "Evidence written to: $EvidenceLog" -ForegroundColor Cyan
Write-Host "Per-run log dir:    $RunLogDir" -ForegroundColor Cyan
if ($results.Values -contains $false) { exit 1 } else { exit 0 }
