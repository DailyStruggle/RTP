#!/usr/bin/env bash
# run-acceptance.sh
#
# Bash port of run-acceptance.ps1. Acceptance harness for the rtp-proxy devstack.
# Drives the cross-server /rtp verification scenarios. Each scenario writes a
# pass/fail line to stdout and a structured block to the per-run evidence log.
#
# Usage:
#   ./run-acceptance.sh [--scenario all|boot|heartbeat|roundtrip|killmidflight|killswitch|rtptest|down|logs]
#                       [--wait-seconds N] [--skip-up] [--build] [--skip-build]
#                       [--no-logs] [--purge] [--lite]
#
#   --build       Opt IN to the gradle clean+jar build. Default: OFF - the harness
#                 does not invoke gradle; build the jars yourself, then it stages
#                 whatever is under */build/libs.
#
# Notes:
#   - Requires `docker compose` and (optionally) `redis-cli` on PATH; falls
#     back to redis-cli inside the compose-managed redis container.
#   - Unlike the .ps1, --no-logs is the practical default in a headless shell:
#     per-service `docker compose logs -f` streams are tee'd to per-run files
#     instead of spawning GUI windows.
set -uo pipefail

Scenario="all"
WaitSeconds=180
SkipUp=0
SkipBuild=0
# Build is opt-in: by default the harness does NOT invoke gradle (the operator/IDE
# builds the jars); it just stages whatever is under */build/libs. Pass --build to
# have the harness run the clean+jar build for you.
Build=0
NoLogs=0
Purge=0
Lite=0
while [ $# -gt 0 ]; do
  case "$1" in
    --scenario) Scenario="$2"; shift 2 ;;
    --wait-seconds) WaitSeconds="$2"; shift 2 ;;
    --skip-up) SkipUp=1; shift ;;
    --skip-build) SkipBuild=1; shift ;;
    --build) Build=1; shift ;;
    --no-logs) NoLogs=1; shift ;;
    --purge) Purge=1; shift ;;
    --lite) Lite=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
case "$Scenario" in
  all|boot|heartbeat|roundtrip|killmidflight|killswitch|rtptest|down|logs) ;;
  *) echo "invalid --scenario: $Scenario" >&2; exit 2 ;;
esac

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RunStamp="$(date +%Y%m%d-%H%M%S)"
RunLogDir="$scriptDir/logs/$RunStamp"
mkdir -p "$RunLogDir"
printf '%s' "$RunStamp" > "$scriptDir/logs/latest.txt"
EvidenceLog="$RunLogDir/acceptance-evidence.log"

write_evidence() {
  local tag="$1" body="$2"
  printf '==== %s %s ====\n' "$(date -Iseconds)" "$tag" >> "$EvidenceLog"
  printf '%s\n\n' "$body" >> "$EvidenceLog"
}

repoRoot="$(cd "$scriptDir/.." && pwd)"

new_secret() { head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '='; }

# A configured RTP_NET_SECRET is only usable if it base64-decodes to >= 32 bytes
# (rtp-proxy-ADR-010 / REQ-RTP-PROXY-007). A non-empty-but-too-short placeholder
# (e.g. 'replace-me-with-32-byte-base64') decodes to 22 bytes and must be treated
# as unset so it gets reseeded. Accepts URL-safe base64.
net_secret_valid() {
  local s="$1"
  [ -n "$s" ] || return 1
  s="$(printf '%s' "$s" | tr '\-_' '+/')"
  case $(( ${#s} % 4 )) in 2) s="$s==" ;; 3) s="$s=" ;; esac
  local n
  n=$(printf '%s' "$s" | base64 -d 2>/dev/null | wc -c) || return 1
  [ "$n" -ge 32 ]
}

# redis-cli wrapper: ALWAYS exec into the compose `redis` service (internal port
# 6379). We must NOT prefer a host-local redis-cli on localhost:6379 - the
# devstack maps Redis to host port ${REDIS_HOST_PORT:-6380} to avoid colliding
# with an operator's own local Redis on 6379. A host redis-cli on 6379 would
# query THAT unrelated server (or nothing), so the heartbeat poll saw zero keys
# and reported "no heartbeats" even though the backends were publishing fine to
# the compose-internal redis:6379. Exec-into-container is port-map agnostic.
redis_cli() {
  docker compose exec -T redis redis-cli "$@"
}

initialize_secrets() {
  local envPath="$scriptDir/.env" fwdPath="$scriptDir/shared/forwarding.secret"
  local curSecret=''
  if [ -f "$envPath" ]; then
    curSecret="$(sed -nE 's/^[[:space:]]*RTP_NET_SECRET[[:space:]]*=[[:space:]]*(.*)$/\1/p' "$envPath" | tail -n1 | tr -d '[:space:]')"
  fi
  if ! net_secret_valid "$curSecret"; then
    echo "[secrets] seeding RTP_NET_SECRET in .env (missing/too-short; needs >= 32 decoded bytes)"
    grep -v -E '^[[:space:]]*RTP_NET_SECRET[[:space:]]*=' "$envPath" 2>/dev/null > "$envPath.tmp" || true
    echo "RTP_NET_SECRET=$(new_secret)" >> "$envPath.tmp"
    mv "$envPath.tmp" "$envPath"
  fi
  mkdir -p "$(dirname "$fwdPath")"
  if [ ! -s "$fwdPath" ]; then
    echo "[secrets] seeding shared/forwarding.secret"
    printf '%s' "$(new_secret)" > "$fwdPath"
  fi
  local fwd; fwd="$(tr -d '[:space:]' < "$fwdPath")"
  [ -n "$fwd" ] || { echo "shared/forwarding.secret is empty after seeding - cannot continue" >&2; exit 1; }
  export CFG_VELOCITY_FORWARDING_SECRET="$fwd"
  # Persist it into `.env` too: the exported var only covers compose calls made
  # by this shell, so a later manual `docker compose up` aborted with
  # "required variable CFG_VELOCITY_FORWARDING_SECRET is missing a value".
  # Compose always reads `.env` from the project dir.
  grep -v -E '^[[:space:]]*CFG_VELOCITY_FORWARDING_SECRET[[:space:]]*=' "$envPath" 2>/dev/null > "$envPath.tmp" || true
  echo "CFG_VELOCITY_FORWARDING_SECRET=$fwd" >> "$envPath.tmp"
  mv "$envPath.tmp" "$envPath"
}

declare -a LOG_PIDS=()

show_log_streams() {
  # Headless analogue of the .ps1 per-service GUI windows: stream each service's
  # `docker compose logs -f` into a per-run file in the background.
  local services=(redis proxy-a proxy-b lobby-a lobby-b backend-a backend-b backend-c)
  echo "[logs] streaming per-service docker logs into $RunLogDir/<service>.log"
  local svc
  for svc in "${services[@]}"; do
    ( cd "$scriptDir" && docker compose logs -f --tail=100 "$svc" ) > "$RunLogDir/$svc.log" 2>&1 &
    LOG_PIDS+=("$!")
  done
  write_evidence 'logs' "streaming services: ${services[*]} -> $RunLogDir"
}

stop_log_streams() {
  local pid
  for pid in "${LOG_PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" >/dev/null 2>&1 || true
  done
}

invoke_gradle_build() {
  # Build is opt-in and staging ALWAYS runs. When --build is not passed (and
  # --skip-build honored), skip straight to staging the jar the operator/IDE
  # already produced under rtp-plugin/build/libs. This avoids the mandatory clean-build on
  # every run (which looked like a hang) and cross-JDK daemon jar locks.
  local gradlew="$repoRoot/gradlew"
  if [ "$Build" -ne 1 ] || [ "$SkipBuild" -eq 1 ]; then
    echo "[build] skipping gradle build (default; pass --build to have the harness build). Staging existing jar from rtp-plugin/build/libs. Build it yourself with:"
    echo "        ./gradlew :rtp-plugin:remapJar"
  elif [ ! -f "$gradlew" ]; then
    echo "[build] WARN - gradlew not found at $gradlew; skipping auto-build"
  else
    local pluginTask=":rtp-plugin:remapJar"
    [ "$Lite" -eq 1 ] && pluginTask=":rtp-plugin:remapLiteJar"
    echo "[build] running gradle ($pluginTask) [edition: $([ "$Lite" -eq 1 ] && echo LITE || echo Pro)]..."
    local out
    out="$(cd "$repoRoot" && "$gradlew" \
      ':rtp-plugin:clean' \
      "$pluginTask" \
      '--console=plain' 2>&1)" || {
        write_evidence 'build' "$out"
        echo "[build] FAIL - gradle failed (see acceptance-evidence.log)" >&2
        echo "$out" | tail -n 40 >&2
        exit 1
      }
    write_evidence 'build' "$out"
    echo "[build] gradle OK"
  fi

  # Stage Paper/Bukkit/Velocity plugin jar into backends + lobbies + fabric mods.
  local pluginLibs="$repoRoot/rtp-plugin/build/libs"
  local pluginStage="$scriptDir/jars/plugin"
  local backendDsts=("$scriptDir/backend-a/plugins" "$scriptDir/backend-b/plugins" "$scriptDir/lobby-a/plugins" "$scriptDir/lobby-b/plugins")
  local fabricModDsts=("$scriptDir/backend-c/mods")
  local d
  for d in "$pluginStage" "${backendDsts[@]}" "${fabricModDsts[@]}"; do mkdir -p "$d"; done
  if [ -d "$pluginLibs" ]; then
    local allJars proJars liteJars pJars variant
    mapfile -t allJars < <(find "$pluginLibs" -maxdepth 1 -name 'LeafRTP-*.jar' \
      ! -name '*-dev.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | LC_ALL=C sort)
    proJars=(); liteJars=()
    for j in "${allJars[@]}"; do
      case "$(basename "$j")" in LeafRTP-Pro-*) proJars+=("$j") ;; *) liteJars+=("$j") ;; esac
    done
    if [ "$Lite" -eq 1 ]; then
      if [ "${#liteJars[@]}" -gt 0 ]; then pJars=("${liteJars[@]}"); variant="lite"
      else pJars=("${proJars[@]}"); variant="Pro (lite jar not found - falling back)"
        echo "[build] WARN - lite jar not found; falling back to Pro jar."; fi
    elif [ "${#proJars[@]}" -gt 0 ]; then pJars=("${proJars[@]}"); variant="Pro"
    else pJars=("${liteJars[@]}"); variant="lite (Pro jar not found - falling back)"
      echo "[build] WARN - Pro jar not found; falling back to plain LeafRTP jar."; fi
    for d in "$pluginStage" "${backendDsts[@]}" "${fabricModDsts[@]}"; do
      find "$d" -maxdepth 1 -name 'LeafRTP-*.jar' ! -name '*-dev.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -delete 2>/dev/null || true
      for j in "${pJars[@]:-}"; do [ -n "$j" ] && cp -f "$j" "$d/"; done
    done
    echo "[build] staged ${#pJars[@]} RTP plugin jar(s) [$variant]"
  fi
  for d in "$pluginStage" "${backendDsts[@]}"; do
    find "$d" -maxdepth 1 -name '.gitkeep' -delete 2>/dev/null || true
    rm -rf "$d/.paper-remapped" 2>/dev/null || true
  done
}

sync_proxy_jars() {
  local src="$scriptDir/jars/plugin"
  mkdir -p "$src"
  mapfile -t jars < <(find "$src" -maxdepth 1 -name '*.jar' ! -name '*-dev.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | LC_ALL=C sort)
  if [ "${#jars[@]}" -eq 0 ]; then
    echo "[jars] No unified RTP plugin jars found in $src; build first." >&2
    return
  fi
  local proxy dst j
  for proxy in proxy-a proxy-b; do
    dst="$scriptDir/$proxy/plugins"
    mkdir -p "$dst"
    find "$dst" -maxdepth 1 -name '*.jar' -delete 2>/dev/null || true
    find "$dst" -maxdepth 1 -name '.gitkeep' -delete 2>/dev/null || true
    for j in "${jars[@]}"; do cp -f "$j" "$dst/"; done
    echo "[jars] synced ${#jars[@]} jar(s) -> $proxy/plugins"
  done
}

clear_stale_world_dirs() {
  local b dir lock
  for b in backend-a backend-b backend-c lobby-a lobby-b; do
    dir="$scriptDir/$b/world"
    if [ -d "$dir" ]; then
      if rm -rf "$dir" 2>/dev/null; then
        write_evidence 'up' "cleared stale world dir: $dir"
      else
        lock="$dir/session.lock"
        [ -f "$lock" ] && rm -f "$lock" 2>/dev/null && write_evidence 'up' "cleared stale lock: $lock" || true
      fi
    fi
  done
}

get_crashed_services() {
  local exited svc out=""
  exited="$(cd "$scriptDir" && docker compose ps --status exited --services 2>/dev/null)"
  for svc in backend-a backend-b backend-c lobby-a lobby-b proxy-a proxy-b; do
    if printf '%s\n' "$exited" | grep -qx "$svc"; then out="$out $svc"; fi
  done
  echo "${out# }"
}

compose_up() {
  invoke_gradle_build
  sync_proxy_jars
  clear_stale_world_dirs
  # Fresh configuration on every up: plugins/RTP/ is a host bind mount, so a
  # stale config.yml / messages.yml / network.yml extracted by an OLDER jar
  # survives across runs and masks changes in the freshly-built jar (new
  # baseline keys, ADR-071 network.yml/logging.yml relocation, migrated
  # defaults). compose_down already resets this, but a plain `up` would reuse
  # the stale tree. Wipe it here so each boot re-extracts the baseline and
  # re-seeds network.yml. --include-database keeps parity with the down path.
  local upResetScript="$scriptDir/reset-rtp-config.sh"
  if [ -f "$upResetScript" ]; then
    echo "[up] wiping plugins/RTP/ (incl. runtime DB) so the freshly-built jar re-extracts a fresh baseline..."
    local upResetOut; upResetOut="$(bash "$upResetScript" --include-database 2>&1)" || true
    write_evidence 'up.reset-rtp-config' "$upResetOut"
  else
    echo "[up] WARN - reset-rtp-config.sh not found at $upResetScript; skipping fresh-config reset"
  fi
  local attempt=0 crashed
  while true; do
    attempt=$((attempt+1))
    echo "[up] docker compose up -d (attempt $attempt)..."
    local upOut
    upOut="$(cd "$scriptDir" && docker compose up -d 2>&1)" || { write_evidence 'up' "$upOut"; echo "[up] FAIL - docker compose up failed" >&2; echo "$upOut" >&2; exit 1; }
    write_evidence 'up' "$upOut"
    sleep 8
    crashed="$(get_crashed_services)"
    [ -z "$crashed" ] && break
    if [ "$attempt" -ge 2 ]; then
      echo "[up] WARN - services still crashed after auto-recover: $crashed; continuing"
      write_evidence 'up' "crashed-after-recover: $crashed"
      break
    fi
    echo "[up] detected crashed services: $crashed; auto-recovering (down + world wipe + re-up)..."
    write_evidence 'up' "auto-recover triggered by crashed: $crashed"
    ( cd "$scriptDir" && docker compose down ) >> "$EvidenceLog" 2>&1 || true
    clear_stale_world_dirs
    sleep 2
  done
  echo "[up] waiting for Redis to answer PING..."
  local deadline=$(( $(date +%s) + 60 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if redis_cli PING 2>/dev/null | grep -q PONG; then echo "[up] Redis ready"; return; fi
    sleep 2
  done
  echo "[up] WARN - Redis did not answer PING within 60s; continuing anyway"
}

compose_down() {
  local includeVolumes="$1" out
  if [ "$includeVolumes" -eq 1 ]; then
    echo "[down] docker compose down -v (purging named volumes incl. mc-image-cache)..."
    out="$(cd "$scriptDir" && docker compose down -v 2>&1)" || true
  else
    echo "[down] docker compose down (preserving mc-image-cache; pass --purge to also drop it)..."
    out="$(cd "$scriptDir" && docker compose down 2>&1)" || true
  fi
  write_evidence 'down' "$out"
  clear_stale_world_dirs
  local resetScript="$scriptDir/reset-rtp-config.sh"
  if [ -f "$resetScript" ]; then
    echo "[down] wiping plugins/RTP/ (incl. runtime DB) so next up extracts a fresh baseline..."
    local resetOut; resetOut="$(bash "$resetScript" --include-database 2>&1)" || true
    write_evidence 'down.reset-rtp-config' "$resetOut"
  else
    echo "[down] WARN - reset-rtp-config.sh not found at $resetScript; skipping config reset"
  fi
}

container_liveness() {
  local out; out="$(cd "$scriptDir" && docker compose ps --format '{{.Service}}|{{.State}}|{{.Status}}' 2>/dev/null)"
  [ -n "$out" ] || { echo '(docker compose ps failed)'; return; }
  printf '%s' "$out" | sed 's/^/  /'
}

recent_log_tail() {
  local svc="$1" lines="${2:-4}" tail
  tail="$(cd "$scriptDir" && docker compose logs --tail="$lines" --no-log-prefix "$svc" 2>/dev/null)"
  [ -n "$tail" ] && printf '%s' "$tail" || echo '(no logs yet)'
}

show_full_service_logs() {
  local lines="${1:-200}" svc body
  echo ""
  echo "[heartbeat] ==== FULL LOG DUMP (last $lines lines per service) ===="
  for svc in redis backend-a backend-b backend-c lobby-a lobby-b proxy-a proxy-b; do
    body="$(cd "$scriptDir" && docker compose logs --tail="$lines" --no-log-prefix "$svc" 2>/dev/null)"
    [ -n "$body" ] || body='(no logs)'
    echo ""; echo "[heartbeat] ---- $svc (last $lines lines) ----"; echo "$body"
    write_evidence "logdump.$svc" "$body"
  done
  echo "[heartbeat] ==== END FULL LOG DUMP ===="
}

show_heartbeat_diagnostics() {
  local elapsed="$1" svc
  echo ""; echo "[heartbeat] ---- diagnostic snapshot at ${elapsed}s ----"
  echo "[heartbeat] container states:"; container_liveness
  for svc in backend-a backend-b backend-c lobby-a lobby-b proxy-a proxy-b; do
    echo "[heartbeat] last log lines for ${svc}:"
    echo "    $(recent_log_tail "$svc" 4)"
  done
  echo "[heartbeat] ---- end snapshot ----"
}

test_boot() {
  echo "[boot] checking docker compose ps..."
  local ps; ps="$(cd "$scriptDir" && docker compose ps --format json 2>&1)"
  write_evidence 'boot' "$ps"
  local expected=(rtp-devstack-redis proxy-a proxy-b lobby-a lobby-b backend-a backend-b backend-c)
  local missing="" name
  for name in "${expected[@]}"; do
    printf '%s' "$ps" | grep -qF "$name" || missing="$missing $name"
  done
  if [ -n "$missing" ]; then echo "[boot] FAIL - missing services:$missing"; return 1; fi
  echo "[boot] PASS"; return 0
}

test_heartbeat() {
  echo "[heartbeat] polling Redis for backend + proxy heartbeats (budget: ${WaitSeconds}s)"
  local deadline=$(( $(date +%s) + WaitSeconds )) pollStart; pollStart="$(date +%s)"
  show_heartbeat_diagnostics 0
  local lastDiag=0 earlyDump=0 backends proxies bCount pCount elapsed
  while [ "$(date +%s)" -lt "$deadline" ]; do
    backends="$(redis_cli KEYS 'rtp:net:backend:*' 2>/dev/null)"
    proxies="$(redis_cli KEYS 'rtp:net:proxy:*' 2>/dev/null)"
    bCount="$(printf '%s\n' "$backends" | grep -c '[^[:space:]]' || true)"
    pCount="$(printf '%s\n' "$proxies" | grep -c '[^[:space:]]' || true)"
    if [ "$bCount" -ge 5 ] && [ "$pCount" -ge 2 ]; then
      write_evidence 'heartbeat' "backend keys: $bCount"$'\n'"$backends"$'\n'"proxy keys: $pCount"$'\n'"$proxies"
      elapsed=$(( $(date +%s) - pollStart ))
      echo "[heartbeat] PASS (backends=$bCount, proxies=$pCount) after ${elapsed}s"; return 0
    fi
    elapsed=$(( $(date +%s) - pollStart ))
    echo "[heartbeat]   ${elapsed}s elapsed: backends=$bCount/5 proxies=$pCount/2 (still waiting)"
    if [ $(( elapsed - lastDiag )) -ge 30 ]; then show_heartbeat_diagnostics "$elapsed"; lastDiag=$elapsed; fi
    if [ "$earlyDump" -eq 0 ] && [ "$elapsed" -ge 90 ] && [ "$bCount" -eq 0 ] && [ "$pCount" -eq 0 ]; then
      echo "[heartbeat] no heartbeats after ${elapsed}s - dumping full service logs early:"
      show_full_service_logs 200; earlyDump=1
    fi
    sleep 5
  done
  show_heartbeat_diagnostics "$WaitSeconds"
  [ "$earlyDump" -eq 0 ] && show_full_service_logs 200
  write_evidence 'heartbeat' "timed out after $WaitSeconds s. backends=$backends proxies=$proxies"
  echo "[heartbeat] FAIL - heartbeats did not converge"; return 1
}

test_roundtrip() {
  echo "[roundtrip] executing automated headless client round-trip (ADR-091)..."
  local botScript="$scriptDir/clients/mineflayer-bot.js"
  local botSuccess=0

  if command -v node >/dev/null 2>&1 && [ -f "$botScript" ]; then
    echo "[roundtrip] running Node/Mineflayer headless client..."
    if ( cd "$scriptDir/clients" && [ ! -d "node_modules" ] ) && command -v npm >/dev/null 2>&1; then
      echo "[roundtrip] installing client dependencies..."
      ( cd "$scriptDir/clients" && npm install --silent --no-audit ) >/dev/null 2>&1 || true
    fi
    local botOut
    botOut="$(node "$botScript" --host 127.0.0.1 --port 25577 --timeout 35 2>&1)" || true
    echo "$botOut"
    if printf '%s' "$botOut" | grep -q '"status":"PASS"'; then
      echo "[roundtrip] headless client completed teleport successfully."
      write_evidence 'roundtrip.bot' "$botOut"
      botSuccess=1
    fi
  fi

  if [ "$botSuccess" -eq 0 ]; then
    echo "[roundtrip] headless bot unavailable or failed; falling back to manual checkpoint."
    echo "  1. Connect a 1.21.1 client to localhost:25577 (proxy-a)."
    echo "  2. Run '/server backend-b' then '/server backend-c' once each to seed all backends."
    echo "  3. From the client, run '/rtp' and observe a cross-server teleport."
    echo "  4. Press <Enter> AFTER the redeem completes to capture evidence."
    read -r _ || true
  fi

  local tokens audit
  tokens="$(redis_cli KEYS 'rtp:net:reservation:*' 2>/dev/null)"
  audit="$(cd "$scriptDir" && docker compose logs --tail=100 backend-a backend-b backend-c 2>&1 | grep -F -e redeem -e JoinTriggerSource || true)"
  write_evidence 'roundtrip' "tokens at sample time:"$'\n'"$tokens"$'\n'"audit lines:"$'\n'"$audit"
  echo "[roundtrip] EVIDENCE CAPTURED"; return 0
}

test_killmidflight() {
  echo "[killmidflight] forcing a reservation, then killing the destination backend (budget: ${WaitSeconds}s)..."
  local tokenId playerId proxyId backendId now expiry claim
  tokenId="$(cat /proc/sys/kernel/random/uuid)"; playerId="$(cat /proc/sys/kernel/random/uuid)"
  proxyId="proxy-a"; backendId="backend-a"
  now="$(( $(date +%s%3N) ))"; expiry=$(( now + 30000 ))
  claim="$(redis_cli EVALSHA dc7c1b0f2c85f26b513de303369728583aaf7dd1 0 "$tokenId" "$playerId" "$proxyId" "$backendId" "$now" "$expiry" 2>/dev/null)"
  write_evidence 'killmidflight.claim' "tokenId=$tokenId result=$claim"
  if [ "$claim" != "1" ]; then echo "[killmidflight] FAIL - claim returned: $claim"; return 1; fi
  echo "[killmidflight] reservation seeded; killing backend-a..."
  ( cd "$scriptDir" && docker compose kill backend-a ) >/dev/null 2>&1 || true
  local deadline=$(( $(date +%s) + WaitSeconds )) row
  while [ "$(date +%s)" -lt "$deadline" ]; do
    row="$(redis_cli HGETALL "rtp:net:reservation:$tokenId" 2>/dev/null)"
    if [ -z "$(printf '%s' "$row" | tr -d '[:space:]')" ]; then
      write_evidence 'killmidflight.reaped' "tokenId=$tokenId reaped within window"
      echo "[killmidflight] PASS"
      ( cd "$scriptDir" && docker compose start backend-a ) >/dev/null 2>&1 || true
      return 0
    fi
    sleep 2
  done
  write_evidence 'killmidflight.timeout' "tokenId=$tokenId still present after $WaitSeconds s: $row"
  echo "[killmidflight] FAIL - reservation not reaped within budget"
  ( cd "$scriptDir" && docker compose start backend-a ) >/dev/null 2>&1 || true
  return 1
}

wait_rcon_ready() {
  # Poll a service's container log until Paper/itzg reports the RCON listener is
  # up ('RCON running on 0.0.0.0:25575'), bounded by WaitSeconds. Returns 0 once
  # the listener is open (rcon-cli can connect), 1 on timeout. Closes the race
  # where the harness dispatches rcon-cli before the server has finished booting
  # and opened port 25575 (connection refused).
  local svc="$1" deadline
  deadline=$(( $(date +%s) + WaitSeconds ))
  echo "[rtptest] waiting for RCON on $svc (budget: ${WaitSeconds}s; first boot generates worlds, can take 1-3 min)..."
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if (cd "$scriptDir" && docker compose logs --tail=400 --no-log-prefix "$svc" 2>/dev/null | grep -qF 'RCON running on'); then
      return 0
    fi
    sleep 3
  done
  return 1
}

test_rtptest() {
  # Drives the in-game `/rtp test accessor` self-test on every backend and lobby
  # via the itzg `rcon-cli` console, then polls each service log for the
  # `[RTP test/accessor] pass=<bool>` verdict the probe always emits (even for a
  # console caller). Ties the server-bound RTPServerAccessor contract checks to a
  # live Paper/Folia runtime rather than a JVM mock, and (under the coverage
  # overlay) flushes the JaCoCo agent so accessor paths credit server-bound
  # coverage. See platforms/rtp-folia/rtp-folia-common/docs/SERVER_BOUND_COVERAGE.md.
  echo "[rtptest] dispatching '/rtp test accessor' to backends + lobbies via rcon (per-service budget: 30s)..."
  local services=(backend-a backend-b backend-c lobby-a lobby-b)
  local anyFail=0 svc rconOut deadline verdict
  for svc in "${services[@]}"; do
    # Gate on RCON readiness so we don't fire rcon-cli before port 25575 is open.
    if ! wait_rcon_ready "$svc"; then
      echo "[rtptest]    FAIL ($svc): RCON not ready within ${WaitSeconds}s (server still booting?)"
      write_evidence "rtptest.$svc" 'RCON not ready within budget; skipped dispatch'
      anyFail=1
      continue
    fi
    echo "[rtptest] -> $svc : rtp test accessor"
    rconOut="$(cd "$scriptDir" && docker compose exec -T "$svc" rcon-cli rtp test accessor 2>&1)" || true
    deadline=$(( $(date +%s) + 30 )); verdict=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
      verdict="$(cd "$scriptDir" && docker compose logs --tail=200 --no-log-prefix "$svc" 2>/dev/null | grep -F '[RTP test/accessor] pass=' | tail -n1)"
      [ -n "$verdict" ] && break
      sleep 2
    done
    write_evidence "rtptest.$svc" "rcon: $rconOut"$'\n'"verdict: ${verdict:-<none>}"
    if printf '%s' "$verdict" | grep -q 'pass=true'; then
      echo "[rtptest]    PASS ($svc)"
    else
      echo "[rtptest]    FAIL ($svc): ${verdict:-no verdict line in log}"
      anyFail=1
    fi
  done
  if [ "$anyFail" -eq 0 ]; then echo "[rtptest] PASS"; return 0; fi
  echo "[rtptest] FAIL - one or more services failed the accessor self-test"; return 1
}

test_killswitch() {
  echo "[killswitch] asserting claim rejection via the kill-switch sentinel (typical: <1s)..."
  local tokenId playerId now result
  tokenId="$(cat /proc/sys/kernel/random/uuid)"; playerId="$(cat /proc/sys/kernel/random/uuid)"
  now="$(( $(date +%s%3N) ))"
  result="$(redis_cli EVALSHA dc7c1b0f2c85f26b513de303369728583aaf7dd1 0 "$tokenId" "$playerId" 'KILL_SWITCH' 'backend-a' "$now" "$(( now + 30000 ))" 2>/dev/null)"
  write_evidence 'killswitch' "result=$result"
  if printf '%s' "$result" | grep -q 'KILL_SWITCH' || [ "$result" = "0" ]; then
    echo "[killswitch] PASS (claim correctly rejected)"; return 0
  fi
  echo "[killswitch] FAIL - claim was not rejected: $result"; return 1
}

# ---- entrypoint ----
write_evidence 'session' "start scenario=$Scenario waitSeconds=$WaitSeconds runDir=$RunLogDir"
initialize_secrets

# Optional compose overlays (lobby-world + lite), assembled into COMPOSE_FILE.
composeFiles=("$scriptDir/docker-compose.yml")
LobbyWorldZip="$scriptDir/shared/lobby-world.zip"
LobbyOverride="$scriptDir/docker-compose.lobby-world.yml"
if [ -f "$LobbyWorldZip" ] && [ -f "$LobbyOverride" ]; then
  composeFiles+=("$LobbyOverride")
  echo "[init] using baked lobby world: $LobbyWorldZip"
  write_evidence 'init' "lobby-world overlay active: $LobbyWorldZip"
else
  schemDir="$scriptDir/shared/lobby-world"
  if [ -d "$schemDir" ] && find "$schemDir" -maxdepth 1 \( -name '*.schem' -o -name '*.schematic' \) | grep -q .; then
    echo "[init] schematic detected but no shared/lobby-world.zip yet; lobbies will boot vanilla. Run bake-lobby-world.sh to enable the canned world."
    write_evidence 'init' "lobby-world overlay skipped: schematic present, zip missing"
  else
    write_evidence 'init' 'lobby-world overlay skipped: no schematic, no zip'
  fi
fi
if [ "$Lite" -eq 1 ]; then
  LiteOverride="$scriptDir/docker-compose.lite.yml"
  if [ -f "$LiteOverride" ]; then
    composeFiles+=("$LiteOverride")
    echo "[init] LITE edition: layering docker-compose.lite.yml (plugin-message transport, no Redis)"
    write_evidence 'init' 'lite overlay active: docker-compose.lite.yml'
  else
    echo "[init] WARN - --lite set but $LiteOverride not found; backends will seed the Redis network.yml"
  fi
fi
if [ "${#composeFiles[@]}" -gt 1 ]; then
  # docker compose on POSIX uses ':' as COMPOSE_FILE path separator.
  COMPOSE_FILE="$(IFS=:; echo "${composeFiles[*]}")"
  export COMPOSE_FILE
fi

if [ "$Scenario" = "down" ]; then
  compose_down "$Purge"
  if [ "$Purge" -eq 1 ]; then echo "[down] stack stopped; all named volumes removed (including mc-image-cache)"
  else echo "[down] stack stopped; mc-image-cache preserved (use --purge to also drop it)"; fi
  exit 0
fi

if [ "$Scenario" = "logs" ]; then
  show_log_streams
  echo "[logs] streaming in background; press Ctrl+C to stop."
  wait
  exit 0
fi

[ "$SkipUp" -eq 1 ] || compose_up
if [ "$NoLogs" -eq 0 ]; then
  show_log_streams
  trap stop_log_streams EXIT
fi

declare -a plan=()
if [ "$Scenario" = "all" ]; then
  if [ "$Lite" -eq 1 ]; then plan=(boot roundtrip); else plan=(boot heartbeat roundtrip killmidflight killswitch rtptest); fi
else
  plan=("$Scenario")
fi

declare -A results=()
anyFail=0
for s in "${plan[@]}"; do
  case "$s" in
    boot)          if test_boot;          then results[$s]=PASS; else results[$s]=FAIL; anyFail=1; fi ;;
    heartbeat)     if test_heartbeat;     then results[$s]=PASS; else results[$s]=FAIL; anyFail=1; fi ;;
    roundtrip)     if test_roundtrip;     then results[$s]=PASS; else results[$s]=FAIL; anyFail=1; fi ;;
    killmidflight) if test_killmidflight; then results[$s]=PASS; else results[$s]=FAIL; anyFail=1; fi ;;
    killswitch)    if test_killswitch;    then results[$s]=PASS; else results[$s]=FAIL; anyFail=1; fi ;;
    rtptest)       if test_rtptest;       then results[$s]=PASS; else results[$s]=FAIL; anyFail=1; fi ;;
  esac
done

echo ""
echo "==== summary ===="
for s in "${plan[@]}"; do
  printf '  %-16s %s\n' "$s" "${results[$s]:-FAIL}"
done
echo "Evidence written to: $EvidenceLog"
echo "Per-run log dir:    $RunLogDir"
[ "$anyFail" -eq 0 ] || exit 1
exit 0
