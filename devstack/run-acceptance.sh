#!/usr/bin/env bash
# run-acceptance.sh
#
# Bash port of run-acceptance.ps1. Acceptance harness for the rtp-proxy devstack.
# Drives the cross-server /rtp verification scenarios. Each scenario writes a
# pass/fail line to stdout and a structured block to the per-run evidence log.
#
# Usage:
#   ./run-acceptance.sh [--scenario all|boot|heartbeat|roundtrip|killmidflight|killswitch|down|logs]
#                       [--wait-seconds N] [--skip-up] [--skip-build] [--no-logs]
#                       [--purge] [--lite]
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
NoLogs=0
Purge=0
Lite=0
while [ $# -gt 0 ]; do
  case "$1" in
    --scenario) Scenario="$2"; shift 2 ;;
    --wait-seconds) WaitSeconds="$2"; shift 2 ;;
    --skip-up) SkipUp=1; shift ;;
    --skip-build) SkipBuild=1; shift ;;
    --no-logs) NoLogs=1; shift ;;
    --purge) Purge=1; shift ;;
    --lite) Lite=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
case "$Scenario" in
  all|boot|heartbeat|roundtrip|killmidflight|killswitch|down|logs) ;;
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

# redis-cli wrapper: prefer host redis-cli, else docker exec into redis.
redis_cli() {
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -h localhost -p 6379 "$@"
  else
    docker compose exec -T redis redis-cli "$@"
  fi
}

initialize_secrets() {
  local envPath="$scriptDir/.env" fwdPath="$scriptDir/shared/forwarding.secret"
  if [ ! -f "$envPath" ] || ! grep -Eq '^[[:space:]]*RTP_NET_SECRET[[:space:]]*=[[:space:]]*[^[:space:]]' "$envPath"; then
    echo "[secrets] seeding RTP_NET_SECRET in .env"
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
  if [ "$SkipBuild" -eq 1 ]; then echo "[build] --skip-build set; skipping gradle build"; return; fi
  local gradlew="$repoRoot/gradlew"
  if [ ! -f "$gradlew" ]; then echo "[build] WARN - gradlew not found at $gradlew; skipping auto-build"; return; fi
  local pluginTask=":rtp-plugin:remapJar"
  [ "$Lite" -eq 1 ] && pluginTask=":rtp-plugin:remapLiteJar"
  echo "[build] running gradle (clean + rtp-proxy-velocity:jar + $pluginTask) [edition: $([ "$Lite" -eq 1 ] && echo LITE || echo Pro)]..."
  local out
  out="$(cd "$repoRoot" && "$gradlew" \
    ':rtp-proxy:rtp-proxy-common:clean' \
    ':rtp-proxy:rtp-proxy-velocity:clean' \
    ':rtp-plugin:clean' \
    ':rtp-proxy:rtp-proxy-velocity:jar' \
    "$pluginTask" \
    '--console=plain' 2>&1)" || {
      write_evidence 'build' "$out"
      echo "[build] FAIL - gradle failed (see acceptance-evidence.log)" >&2
      echo "$out" | tail -n 40 >&2
      exit 1
    }
  write_evidence 'build' "$out"
  echo "[build] gradle OK"

  # Stage Velocity jar(s).
  local velocityLibs="$repoRoot/rtp-proxy/rtp-proxy-velocity/build/libs"
  local velocityDst="$scriptDir/jars/velocity"
  mkdir -p "$velocityDst"
  if [ -d "$velocityLibs" ]; then
    find "$velocityDst" -maxdepth 1 -name '*.jar' -delete 2>/dev/null || true
    find "$velocityLibs" -maxdepth 1 -name 'rtp-proxy-velocity-*.jar' \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' -exec cp -f {} "$velocityDst/" \;
    echo "[build] staged Velocity jar(s) -> jars/velocity"
  fi
  find "$velocityDst" -maxdepth 1 -name '.gitkeep' -delete 2>/dev/null || true

  # Stage Paper/Bukkit plugin jar into backends + lobbies + fabric mods.
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
  echo "[roundtrip] manual checkpoint - a live Minecraft client is required."
  echo "  1. Connect a 1.21.1 client to localhost:25577 (proxy-a)."
  echo "  2. Run '/server backend-b' then '/server backend-c' once each to seed all backends."
  echo "  3. From the client, run '/rtp' and observe a cross-server teleport."
  echo "  4. Press <Enter> AFTER the redeem completes to capture evidence."
  read -r _ || true
  local tokens audit
  tokens="$(redis_cli KEYS 'rtp:net:reservation:*' 2>/dev/null)"
  audit="$(cd "$scriptDir" && docker compose logs --tail=100 backend-a backend-b backend-c 2>&1 | grep -F -e redeem -e JoinTriggerSource || true)"
  write_evidence 'roundtrip' "tokens at sample time:"$'\n'"$tokens"$'\n'"audit lines:"$'\n'"$audit"
  echo "[roundtrip] EVIDENCE CAPTURED (operator must visually confirm)"; return 0
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
  if [ "$Lite" -eq 1 ]; then plan=(boot roundtrip); else plan=(boot heartbeat roundtrip killmidflight killswitch); fi
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
