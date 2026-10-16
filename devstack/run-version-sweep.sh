#!/usr/bin/env bash
# run-version-sweep.sh
#
# One-off player sweep across the advertised version range (ADR-113): boots
# one single server per row of the matrix below on docker-compose.sweep.yml,
# fresh world each time, and requires
#   1. `rtp test accessor` logs `pass=true`,
#   2. a non-zero hot or cold queue in `rtp info`,
#   3. bot phase "effects": a Mineflayer player runs /rtp, lands, and sees the
#      sweep effect group (sound, particle, potion, console + player COMMAND;
#      GLIDE and FIREWORK are also configured and reported),
#   4. bot phase "death": after a restart with the cooldown at 0 and DEATH as
#      the postteleport effect, /rtp kills the player, it respawns, and the
#      `when: death` COMMAND fires (a restart, not `rtp reload`, so the
#      phase does not depend on reload picking up effect groups),
#   5. no linkage error (NoSuchMethodError, AbstractMethodError, ...), crash,
#      OOM or classfile-version error in the server log.
# Rows whose bot column is `-` stop after step 2 (no client for that protocol).
#
# Usage:
#   ./run-version-sweep.sh [--only <slug-regex>] [--wait-seconds N]
#
# Build the jar first: ./gradlew :rtp-plugin:remapJar
# Results: logs/sweep-<ts>/summary.tsv plus per-row server.log / bot-*.log.
set -uo pipefail

Only=''
WaitSeconds=600
while [ $# -gt 0 ]; do
  case "$1" in
    --only) Only="$2"; shift 2 ;;
    --wait-seconds) WaitSeconds="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# slug | itzg TYPE | MC version | image tag | Mineflayer version (- = none) | memory | Modrinth projects
# One row per carrier boundary; Fabric 1.20.4 is a probe of an Unsupported range.
Matrix=$(cat <<'EOF'
paper-1_18_2|PAPER|1.18.2|java17|1.18.2|3G|
paper-1_19_4|PAPER|1.19.4|java17|1.19.4|3G|
paper-1_20_4|PAPER|1.20.4|java17|1.20.4|3G|
paper-1_20_6|PAPER|1.20.6|java21|1.20.6|3G|
paper-1_21_4|PAPER|1.21.4|java21|1.21.4|3G|
paper-1_21_8|PAPER|1.21.8|java21|1.21.8|3G|
paper-1_21_11|PAPER|1.21.11|java21|1.21.11|3G|
paper-26_1_2|PAPER|26.1.2|java25|26.1|3G|
paper-26_3|PAPER|26.3|java25|26.1|3G|viaversion,viabackwards
spigot-1_18_2|SPIGOT|1.18.2|java17|1.18.2|3G|
spigot-1_21_11|SPIGOT|1.21.11|java21|1.21.11|3G|
folia-1_21_11|FOLIA|1.21.11|java21|1.21.11|3G|
folia-26_1_2|FOLIA|26.1.2|java25|26.1|3G|
folia-26_3|FOLIA|26.3|java25|26.1|3G|viaversion,viabackwards
fabric-1_20_1|FABRIC|1.20.1|java17|1.20.1|6G|fabric-api
fabric-1_20_4|FABRIC|1.20.4|java17|1.20.4|6G|fabric-api
fabric-1_21_1|FABRIC|1.21.1|java21|1.21.1|6G|fabric-api
fabric-1_21_4|FABRIC|1.21.4|java21|1.21.4|6G|fabric-api
fabric-1_21_5|FABRIC|1.21.5|java21|1.21.5|6G|fabric-api
fabric-1_21_10|FABRIC|1.21.10|java21|1.21.9|6G|fabric-api
fabric-1_21_11|FABRIC|1.21.11|java21|1.21.11|6G|fabric-api
fabric-26_1_2|FABRIC|26.1.2|java25|26.1|6G|fabric-api
fabric-26_2|FABRIC|26.2|java25|26.1|6G|fabric-api,viafabric,viabackwards
fabric-26_3|FABRIC|26.3|java25|26.1|6G|fabric-api,viafabric,viabackwards
neoforge-1_21_1|NEOFORGE|1.21.1|java21|1.21.1|6G|
neoforge-1_21_4|NEOFORGE|1.21.4|java21|1.21.4|6G|
neoforge-1_21_11|NEOFORGE|1.21.11|java21|1.21.11|6G|
neoforge-26_1_2|NEOFORGE|26.1.2|java25|26.1|6G|
neoforge-26_3|NEOFORGE|26.3|java25|-|6G|
EOF
)

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
composeFile="$scriptDir/docker-compose.sweep.yml"
runDir="$scriptDir/logs/sweep-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$runDir"
summary="$runDir/summary.tsv"
printf 'slug\tresult\taccessor\tqueue\tbot_effects\tbot_death\tlinkage\tnote\n' > "$summary"

mapfile -t jars < <(find "$repoRoot/rtp-plugin/build/libs" -maxdepth 1 -name 'LeafRTP-Pro-*.jar' \
  ! -name '*-dev.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | LC_ALL=C sort)
if [ "${#jars[@]}" -ne 1 ]; then
  echo "[stage] FAIL - expected one LeafRTP-Pro-*.jar under rtp-plugin/build/libs, found ${#jars[@]}" >&2
  exit 1
fi
jar="${jars[0]}"

compose() { docker compose --project-directory "$scriptDir" -f "$composeFile" "$@"; }
rcon() { compose exec -T sweep rcon-cli "$@" </dev/null >/dev/null 2>&1 || true; }
running() { [ -n "$(compose ps --status running -q sweep 2>/dev/null)" ]; }

write_effects_phase1() {
  cat > "$1/sweep.yml" <<'EOF'
when: postteleport
effects:
  - SOUND.ENTITY_ENDERMAN_TELEPORT.100.100.0.0.0
  - PARTICLE.PORTAL.32
  - POTION.SLOW_FALLING.60.1.false.false.false
  - GLIDE.ELYTRA
  - FIREWORK.BALL.1.1.RED.YELLOW.true.true.true.0.0.0
  - NOTE.PIANO.12
  - COMMAND.PLAYER.me sweep-player
version: "1.0"
EOF
  # One COMMAND per stage: clones of the COMMAND prototype share one data map,
  # so a second COMMAND in the same stage would overwrite the first.
  cat > "$1/sweep-pre.yml" <<'EOF'
when: preteleport
effects:
  - COMMAND.CONSOLE.say sweep-console [player]
version: "1.0"
EOF
  cat > "$1/sweep-death.yml" <<'EOF'
when: death
effects:
  - COMMAND.CONSOLE.say sweep-death [player]
version: "1.0"
EOF
}

write_effects_phase2() {
  cat > "$1/sweep.yml" <<'EOF'
when: postteleport
effects:
  - DEATH
version: "1.0"
EOF
}

# Hard failures: a call that does not link on this version, or a dead server.
LinkagePattern='NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|NoClassDefFoundError|UnsupportedClassVersionError|Exception in server tick loop|OutOfMemoryError|This crash report has been saved'

# wait_rcon <log> <deadline>: 0 once the server logs RCON up; else sets waitNote.
waitNote=''
wait_rcon() {
  until [ -f "$1" ] && grep -qF 'RCON running on' "$1"; do
    if ! running; then waitNote='container stopped during boot'; return 1; fi
    if [ "$(date +%s)" -ge "$2" ]; then waitNote='RCON not up in time'; return 1; fi
    sleep 5
  done
}

run_bot() {
  local version="$1" phase="$2" out="$3"
  (cd "$scriptDir/clients" && node version-probe.js 127.0.0.1 25598 "$version" SweepBot "$phase") </dev/null > "$out" 2>&1
  local rc=$?
  grep -m1 '^RESULT ' "$out" | sed 's/^RESULT //' | cut -c1-400
  return $rc
}

sweep_row() {
  local slug="$1" type="$2" version="$3" image="$4" botv="$5" mem="$6" modrinth="$7"
  local dataDir="$scriptDir/sweep/$slug" rowDir="$runDir/$slug" cfgDir jarDir
  local accessor='-' queue='-' botE='-' botD='-' linkage='-' note='' result='FAIL'
  mkdir -p "$rowDir"
  case "$type" in
    FABRIC|NEOFORGE) cfgDir="$dataDir/config/rtp"; jarDir="$dataDir/mods" ;;
    *) cfgDir="$dataDir/plugins/RTP"; jarDir="$dataDir/plugins" ;;
  esac

  rm -rf "$dataDir"
  mkdir -p "$jarDir" "$cfgDir/definitions/effects"
  cp -f "$jar" "$jarDir/"
  write_effects_phase1 "$cfgDir/definitions/effects"
  # `/rtp region=default` needs rtp.region (op). itzg's OPS resolves the online
  # UUID, so write the offline-mode UUID (v3 of "OfflinePlayer:SweepBot").
  printf '[{"uuid":"b4343f8e-8f9f-3d74-a874-1974c282fe64","name":"SweepBot","level":4,"bypassesPlayerLimit":false}]\n' > "$dataDir/ops.json"

  export SWEEP_SLUG="$slug" SWEEP_TYPE="$type" SWEEP_VERSION="$version" SWEEP_IMAGE="$image" \
         SWEEP_MEMORY="$mem" SWEEP_MODRINTH="$modrinth" SWEEP_BUILD_FROM_SOURCE=FALSE
  [ "$type" = SPIGOT ] && export SWEEP_BUILD_FROM_SOURCE=TRUE
  # ViaFabric publishes 26.x builds as alpha only.
  export SWEEP_MODRINTH_TYPE=release
  case "$modrinth" in *viafabric*) export SWEEP_MODRINTH_TYPE=alpha ;; esac
  # BuildTools compiles Spigot on first boot.
  local budget=$WaitSeconds
  [ "$type" = SPIGOT ] && [ "$budget" -lt 1800 ] && budget=1800

  echo "=== [$slug] $type $version on $image (bot: $botv) ==="
  compose down >/dev/null 2>&1 || true
  if ! compose up -d sweep >/dev/null 2>&1; then
    note='compose up failed'
  else
    local deadline=$(( $(date +%s) + budget )) log="$dataDir/logs/latest.log"
    if ! wait_rcon "$log" "$deadline"; then note="$waitNote"; fi

    if [ -z "$note" ]; then
      rcon rtp test accessor
      local verdict=''
      for _ in $(seq 1 20); do
        verdict="$(grep -F '[RTP test/accessor] pass=' "$log" | tail -n1)"
        [ -n "$verdict" ] && break
        sleep 3
      done
      if printf '%s' "$verdict" | grep -q 'pass=true'; then accessor=pass; else accessor=fail; note="accessor: ${verdict:-no verdict}"; fi

      local info=''
      while :; do
        rcon rtp info
        sleep 5
        info="$(grep -oE 'keptCache \((hot|cold)\): [1-9][0-9]*' "$log" | tail -n1)"
        [ -n "$info" ] && break
        if ! running; then note="${note:+$note; }stopped during cache fill"; break; fi
        if [ "$(date +%s)" -ge "$deadline" ]; then break; fi
        sleep 10
      done
      if [ -n "$info" ]; then queue="${info#keptCache }"; else queue=empty; fi

      if [ "$botv" != '-' ] && running; then
        local r
        r="$(run_bot "$botv" effects "$rowDir/bot-effects.log")"; local rc=$?
        if [ $rc -eq 0 ]; then botE=pass; else botE="fail($rc)"; note="${note:+$note; }effects: $(printf '%s' "$r" | grep -oE '"why":"[^"]*"' | head -n1)"; fi
        echo "[$slug] effects: $r"

        if running; then
          compose stop sweep >/dev/null 2>&1 || true
          cp -f "$log" "$rowDir/server-phase1.log" 2>/dev/null || true
          rm -f "$log"
          write_effects_phase2 "$cfgDir/definitions/effects"
          sed -i 's/^teleportCooldown: .*/teleportCooldown: 0/' "$cfgDir/config.yml" 2>/dev/null || true
          # Rejoin fresh: a saved mid-glide position can kill the bot by fall damage before
          # /rtp, and the saved elytra/firework stacks break mineflayer's slot decoding on NeoForge.
          find "$dataDir" -name 'b4343f8e-8f9f-3d74-a874-1974c282fe64.dat*' -delete 2>/dev/null || true
          compose start sweep >/dev/null 2>&1 || true
          deadline=$(( $(date +%s) + budget ))
          if wait_rcon "$log" "$deadline"; then
            sleep 10
            r="$(run_bot "$botv" death "$rowDir/bot-death.log")"; rc=$?
            if [ $rc -eq 0 ]; then botD=pass; else botD="fail($rc)"; note="${note:+$note; }death: $(printf '%s' "$r" | grep -oE '"why":"[^"]*"' | head -n1)"; fi
            echo "[$slug] death: $r"
          else
            botD=fail; note="${note:+$note; }restart: $waitNote"
          fi
        fi
      fi
      sleep 3
    fi

    [ -f "$log" ] && cp -f "$log" "$rowDir/server.log"
    local logs=() f hits
    for f in "$rowDir/server-phase1.log" "$rowDir/server.log"; do [ -f "$f" ] && logs+=("$f"); done
    if [ "${#logs[@]}" -gt 0 ]; then
      hits="$(cat "${logs[@]}" | grep -cE "$LinkagePattern" || true)"
      if [ "${hits:-0}" -gt 0 ]; then
        linkage="$hits"
        grep -nE "$LinkagePattern" "${logs[@]}" | head -n 20 > "$rowDir/linkage.txt"
      else
        linkage=0
      fi
    fi
    running || note="${note:+$note; }container not running at end"
  fi
  compose logs --no-log-prefix sweep > "$rowDir/container.log" 2>&1 || true
  compose down >/dev/null 2>&1 || true

  if [ "$accessor" = pass ] && [ "$queue" != empty ] && [ "$queue" != '-' ] && [ "$linkage" = 0 ] \
     && { [ "$botv" = '-' ] || { [ "$botE" = pass ] && [ "$botD" = pass ]; }; } && [ -z "$note" ]; then
    result=PASS
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$slug" "$result" "$accessor" "$queue" "$botE" "$botD" "$linkage" "$note" >> "$summary"
  echo "[$slug] $result (accessor=$accessor queue=$queue effects=$botE death=$botD linkage=$linkage) $note"
}

# fd 3: docker and node inside the loop must not read the matrix from stdin.
while IFS='|' read -r -u 3 slug type version image botv mem modrinth; do
  [ -n "$slug" ] || continue
  if [ -n "$Only" ] && ! printf '%s' "$slug" | grep -qE "$Only"; then continue; fi
  sweep_row "$slug" "$type" "$version" "$image" "$botv" "$mem" "$modrinth"
done 3<<< "$Matrix"

echo
column -t -s $'\t' "$summary" 2>/dev/null || cat "$summary"
echo "[sweep] results: ${runDir#"$repoRoot"/}"
awk -F'\t' '$2 == "FAIL" { f = 1 } END { exit f }' "$summary"
