#!/usr/bin/env bash
# run-versions-smoke.sh
#
# Single-server smoke run on docker-compose.versions.yml (ADR-113 bug-report
# window). For each service: stage the Pro jar, boot, then require
#   1. `rtp test accessor` logs `pass=true`,
#   2. `rtp info` shows a non-zero hot or cold queue (the chunk-load path
#      returns chunks; the Fabric chunk-future defects read every chunk as
#      missing and left both queues at 0),
#   3. the server is still running and logged no tick-loop crash, OOM or
#      classfile-version error.
#
# Usage:
#   ./run-versions-smoke.sh [--service <name>]... [--wait-seconds N] [--fresh] [--keep]
#
#   --service       Compose service to check (repeatable). Default:
#                   versions-paper-26 versions-fabric-26.
#   --wait-seconds  Per-service budget for boot and queue fill. Default 420.
#   --fresh         Delete the service's world dir and plugins/RTP before boot.
#   --keep          Leave the containers running afterwards.
#
# Build the jar first: ./gradlew :rtp-plugin:remapJar
set -uo pipefail

Services=()
WaitSeconds=420
Fresh=0
Keep=0
while [ $# -gt 0 ]; do
  case "$1" in
    --service) Services+=("$2"); shift 2 ;;
    --wait-seconds) WaitSeconds="$2"; shift 2 ;;
    --fresh) Fresh=1; shift ;;
    --keep|--keep-running|--no-teardown) Keep=1; shift ;;
    --teardown) Keep=0; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ "${#Services[@]}" -gt 0 ] || Services=(versions-paper-26 versions-fabric-26)

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
composeFile="$scriptDir/docker-compose.versions.yml"
RunLogDir="$scriptDir/logs/versions-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RunLogDir"

compose() { docker compose -f "$composeFile" "$@"; }

# versions-paper-26 -> versions/paper-26/plugins; versions-fabric-26 -> versions/fabric-26/mods.
service_dir() { printf '%s/versions/%s' "$scriptDir" "${1#versions-}"; }
jar_dir() {
  case "$1" in
    *fabric*) printf '%s/mods' "$(service_dir "$1")" ;;
    *) printf '%s/plugins' "$(service_dir "$1")" ;;
  esac
}

mapfile -t jars < <(find "$repoRoot/rtp-plugin/build/libs" -maxdepth 1 -name 'LeafRTP-Pro-*.jar' \
  ! -name '*-dev.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | LC_ALL=C sort)
if [ "${#jars[@]}" -ne 1 ]; then
  echo "[stage] FAIL - expected one LeafRTP-Pro-*.jar under rtp-plugin/build/libs, found ${#jars[@]}" >&2
  exit 1
fi
jar="${jars[0]}"

for svc in "${Services[@]}"; do
  dst="$(jar_dir "$svc")"
  mkdir -p "$dst"
  find "$dst" -maxdepth 1 -name 'LeafRTP-*.jar' -delete 2>/dev/null || true
  cp -f "$jar" "$dst/"
  if [ "$Fresh" -eq 1 ]; then
    rm -rf "$(service_dir "$svc")/world" "$(service_dir "$svc")/plugins/RTP" "$(service_dir "$svc")/plugins/.paper-remapped"
  fi
  echo "[stage] $(basename "$jar") -> ${dst#"$scriptDir"/}"
done

trap 'if [ "$Keep" -eq 0 ]; then compose down >/dev/null 2>&1 || true; fi' EXIT
if ! compose up -d "${Services[@]}"; then
  echo "[up] FAIL - docker compose up failed" >&2
  exit 1
fi

svc_logs() { compose logs --no-log-prefix "$1" 2>/dev/null; }
svc_running() { [ -n "$(compose ps --status running -q "$1" 2>/dev/null)" ]; }

check_service() {
  local svc="$1" deadline verdict info
  deadline=$(( $(date +%s) + WaitSeconds ))
  echo "[$svc] waiting for RCON (budget ${WaitSeconds}s)..."
  until svc_logs "$svc" | grep -qF 'RCON running on'; do
    if ! svc_running "$svc"; then echo "[$svc] FAIL - container stopped during boot"; return 1; fi
    if [ "$(date +%s)" -ge "$deadline" ]; then echo "[$svc] FAIL - RCON not up in ${WaitSeconds}s"; return 1; fi
    sleep 5
  done

  compose exec -T "$svc" rcon-cli rtp test accessor >/dev/null 2>&1 || true
  verdict=""
  for _ in $(seq 1 15); do
    verdict="$(svc_logs "$svc" | grep -F '[RTP test/accessor] pass=' | tail -n1)"
    [ -n "$verdict" ] && break
    sleep 2
  done
  if ! printf '%s' "$verdict" | grep -q 'pass=true'; then
    echo "[$svc] FAIL - accessor: ${verdict:-no verdict line}"; return 1
  fi
  echo "[$svc] accessor pass=true"

  # Cache fill: any world reporting a non-zero hot or cold queue.
  until false; do
    compose exec -T "$svc" rcon-cli rtp info >/dev/null 2>&1 || true
    sleep 5
    info="$(svc_logs "$svc" | grep -oE 'keptCache \((hot|cold)\): [1-9][0-9]*' | tail -n1)"
    [ -n "$info" ] && break
    if ! svc_running "$svc"; then echo "[$svc] FAIL - container stopped during cache fill"; return 1; fi
    if [ "$(date +%s)" -ge "$deadline" ]; then echo "[$svc] FAIL - hot and cold queues still empty after ${WaitSeconds}s"; return 1; fi
    sleep 10
  done
  echo "[$svc] cache fill: $info"

  if ! svc_running "$svc"; then echo "[$svc] FAIL - container no longer running"; return 1; fi
  local pat
  for pat in 'Exception in server tick loop' 'OutOfMemoryError' 'UnsupportedClassVersionError' 'This crash report has been saved'; do
    if svc_logs "$svc" | grep -qF "$pat"; then echo "[$svc] FAIL - log contains '$pat'"; return 1; fi
  done
  echo "[$svc] PASS"
  return 0
}

anyFail=0
for svc in "${Services[@]}"; do
  check_service "$svc" || anyFail=1
  svc_logs "$svc" > "$RunLogDir/$svc.log" 2>&1 || true
done

echo "[versions] logs: ${RunLogDir#"$repoRoot"/}"
if [ "$anyFail" -eq 0 ]; then echo "[versions] PASS"; exit 0; fi
echo "[versions] FAIL"; exit 1
