#!/usr/bin/env bash
# test-treegen-acceptance.sh
#
# Reproducible devstack acceptance verification for LeafTreeGen across Folia,
# Fabric, and NeoForge on docker-compose.sweep.yml (MC 26.1.2 / Java 25).
#
# For each platform row in the matrix, verifies:
#   1. Startup & Command Registration:
#      - Carrier entry point enables cleanly (`LeafTreeGen (Paper) enabled.` /
#        `LeafTreeGen (Fabric) initialized.` / `LeafTreeGen (NeoForge) initialized.`).
#      - Default `config.yml` and 25 bundled `species/*.json` files are extracted.
#      - `/leaftree debug` and `/leaftree list` respond via RCON and report
#        `Generation Mode: DATAPACK` with active species.
#   2. Datapack Generation (`generation-mode: DATAPACK`):
#      - `world/datapacks/leaf-treegen-generated` contains `pack.mcmeta`,
#        `.treegen-fingerprint`, structure sets (`structure_set/*.json`),
#        template pools (`template_pool/**/*.json`), biome tags
#        (`tags/worldgen/biome/has_structure/*.json`), and baked structure NBT
#        variants (`structure/**/*.nbt`).
#   3. Procedural Generation (`/leaftree generate` + `generation-mode: PROCEDURAL`):
#      - Command-driven placement: forceloads chunk `(0, 0)`, runs
#        `/leaftree generate species=oak-small-mid world=world x=8 y=80 z=8`,
#        and verifies `/leaftree stats` reports `Total Blocks Placed > 0` and
#        `Total Batches > 0`.
#      - Procedural mode & chunk-load placement: switches `config.yml` to
#        `generation-mode: PROCEDURAL`, runs `/leaftree reload`, verifies
#        `/leaftree debug` reports `Generation Mode: PROCEDURAL`, confirms the
#        datapack switches to vanilla-suppression-only (`placed_feature/*.json`
#        present and `structure_set/` removed), forceloads new chunks to trigger
#        `TreeGenEventController#onChunkLoad`, and confirms `Chunks Loaded > 0`.
#   4. Zero Linkage / Thread-Ownership / Crash Errors:
#      - Asserts zero `NoSuchMethodError`, `NoClassDefFoundError`,
#        `IncompatibleClassChangeError`, Folia region-thread violation, or server
#        tick-loop crash in `logs/latest.log`.
#
# Usage:
#   ./devstack/test-treegen-acceptance.sh [--only <slug-regex>] [--skip-build] [--wait-seconds N] [--keep] [--down]
#
# Results:
#   devstack/logs/treegen-<ts>/summary.tsv plus per-row server.log.

set -uo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEVSTACK_DIR/.." && pwd)"
COMPOSE_FILE="$DEVSTACK_DIR/docker-compose.sweep.yml"

ONLY=""
SKIP_BUILD=0
WAIT_SECONDS=420
KEEP=0
DOWN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    --skip-build|-SkipBuild) SKIP_BUILD=1; shift ;;
    --wait-seconds) WAIT_SECONDS="$2"; shift 2 ;;
    --keep|--keep-running|--no-teardown) KEEP=1; shift ;;
    --teardown) KEEP=0; shift ;;
    --down) DOWN=1; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

compose() {
  (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" "$@")
}

if [ "$DOWN" -eq 1 ]; then
  compose down >/dev/null 2>&1 || true
  exit 0
fi

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[treegen-devstack] Building :resource:LeafTreeGen:treegen-plugin:assemble..."
  if ! (cd "$REPO_ROOT" && ./gradlew :resource:LeafTreeGen:treegen-plugin:assemble --console=plain -q); then
    echo "[treegen-devstack] ERROR: Gradle build failed." >&2
    exit 1
  fi
fi

JAR="$REPO_ROOT/resource/LeafTreeGen/treegen-plugin/build/libs/LeafTreeGen-1.0.0.jar"
if [ ! -f "$JAR" ]; then
  echo "[treegen-devstack] ERROR: Expected $JAR not found." >&2
  exit 1
fi

# Ensure external image cache volume exists for docker-compose.sweep.yml
docker volume create devstack_mc-image-cache >/dev/null 2>&1 || true

# slug | itzg TYPE | MC version | image tag | memory | Modrinth projects | init log marker
MATRIX=$(cat <<'EOF'
folia-26_1_2|FOLIA|26.1.2|java25|4G||LeafTreeGen (Paper) enabled.
fabric-26_1_2|FABRIC|26.1.2|java25|4G|fabric-api|LeafTreeGen (Fabric) initialized.
neoforge-26_1_2|NEOFORGE|26.1.2|java25|4G||LeafTreeGen (NeoForge) initialized.
EOF
)

RUN_DIR="$DEVSTACK_DIR/logs/treegen-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RUN_DIR"
SUMMARY="$RUN_DIR/summary.tsv"
printf 'slug\tresult\tstartup\tdatapack\tprocedural_cmd\tprocedural_mode\tlinkage\tnote\n' > "$SUMMARY"

LINKAGE_PATTERN='NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|NoClassDefFoundError|UnsupportedClassVersionError|Exception in server tick loop|OutOfMemoryError|This crash report has been saved|Thread failed main thread check|Cannot read world asynchronously'

running() {
  [ "$(docker inspect -f '{{.State.Running}}' rtp-devstack-sweep 2>/dev/null)" = "true" ]
}

rcon() {
  docker exec -i rtp-devstack-sweep rcon-cli "$@" </dev/null 2>/dev/null || true
}

WAIT_NOTE=""
wait_rcon() {
  local log="$1" deadline="$2"
  WAIT_NOTE=""
  until [ -f "$log" ] && grep -qF 'RCON running on' "$log"; do
    if ! running; then
      WAIT_NOTE="container stopped during boot"
      return 1
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      WAIT_NOTE="RCON not ready within budget"
      return 1
    fi
    sleep 3
  done
  return 0
}

wait_log_pattern() {
  local log="$1" pattern="$2" timeout_sec="$3"
  local end=$(( $(date +%s) + timeout_sec ))
  while [ "$(date +%s)" -lt "$end" ]; do
    if [ -f "$log" ] && grep -qE "$pattern" "$log"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

clean_host_dir() {
  local target="$1"
  rm -rf "$target" 2>/dev/null || podman unshare rm -rf "$target" 2>/dev/null || true
}

test_row() {
  local slug="$1" type="$2" version="$3" image="$4" mem="$5" modrinth="$6" init_marker="$7"
  local data_dir="$DEVSTACK_DIR/sweep/$slug"
  local row_dir="$RUN_DIR/$slug"
  local cfg_dir jar_dir
  local startup="fail" datapack="fail" proc_cmd="fail" proc_mode="fail" linkage="-" note="" result="FAIL"

  mkdir -p "$row_dir"
  case "$type" in
    FABRIC|NEOFORGE)
      cfg_dir="$data_dir/config/leaf-treegen"
      jar_dir="$data_dir/mods"
      ;;
    *)
      cfg_dir="$data_dir/plugins/LeafTreeGen"
      jar_dir="$data_dir/plugins"
      ;;
  esac

  clean_host_dir "$data_dir"
  mkdir -p "$jar_dir"
  cp -f "$JAR" "$jar_dir/LeafTreeGen-1.0.0.jar"

  export SWEEP_SLUG="$slug" \
         SWEEP_TYPE="$type" \
         SWEEP_VERSION="$version" \
         SWEEP_IMAGE="$image" \
         SWEEP_MEMORY="$mem" \
         SWEEP_MODRINTH="$modrinth" \
         SWEEP_MODRINTH_TYPE="release" \
         SWEEP_BUILD_FROM_SOURCE="FALSE"

  echo "=== [$slug] $type $version on $image ==="
  compose down >/dev/null 2>&1 || true

  if ! compose up -d sweep >/dev/null 2>&1; then
    note="compose up failed"
  else
    local deadline=$(( $(date +%s) + WAIT_SECONDS ))
    local log="$data_dir/logs/latest.log"
    if ! wait_rcon "$log" "$deadline"; then
      note="$WAIT_NOTE"
    fi

    if [ -z "$note" ]; then
      # ------------------------------------------------------------------
      # 1. Startup & Command Registration
      # ------------------------------------------------------------------
      local species_count=0
      if [ -d "$cfg_dir/species" ]; then
        species_count="$(find "$cfg_dir/species" -maxdepth 1 -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]')"
      fi

      local rcon_debug rcon_list
      rcon_debug="$(rcon "leaftree debug")"
      rcon_list="$(rcon "leaftree list")"
      # Wait up to 10s for CommandsAPI 1-tick drain to log debug/list output
      wait_log_pattern "$log" "Generation Mode: DATAPACK" 10 || true

      local has_init=0 has_debug=0
      docker logs rtp-devstack-sweep > "$row_dir/container.log" 2>&1 || true
      if grep -qF "$init_marker" "$log" 2>/dev/null || grep -qF "$init_marker" "$row_dir/container.log" 2>/dev/null; then
        has_init=1
      fi
      if printf '%s\n' "$rcon_debug" | grep -qF "Generation Mode: DATAPACK" || grep -qF "Generation Mode: DATAPACK" "$log" 2>/dev/null; then
        has_debug=1
      fi

      if [ "$has_init" -eq 1 ] && [ -f "$cfg_dir/config.yml" ] && [ "${species_count:-0}" -ge 25 ] && [ "$has_debug" -eq 1 ]; then
        startup="pass(${species_count}sp)"
      else
        startup="fail"
        note="startup(init=$has_init,cfg=$([ -f "$cfg_dir/config.yml" ] && echo 1 || echo 0),species=${species_count:-0},debug=$has_debug)"
      fi

      # ------------------------------------------------------------------
      # 2. Datapack Generation (generation-mode: DATAPACK)
      # ------------------------------------------------------------------
      local dp_root="$data_dir/world/datapacks/leaf-treegen-generated"
      local nbt_count=0 ss_count=0 pool_count=0 tag_count=0
      if [ -d "$dp_root" ]; then
        nbt_count="$(find "$dp_root/data/minecraft/structure" -name '*.nbt' 2>/dev/null | wc -l | tr -d '[:space:]')"
        ss_count="$(find "$dp_root/data/minecraft/worldgen/structure_set" -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]')"
        pool_count="$(find "$dp_root/data/minecraft/worldgen/template_pool" -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]')"
        tag_count="$(find "$dp_root/data/minecraft/tags/worldgen/biome/has_structure" -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]')"
      fi

      if [ -f "$dp_root/pack.mcmeta" ] && [ -f "$dp_root/.treegen-fingerprint" ] \
         && [ "${ss_count:-0}" -gt 0 ] && [ "${pool_count:-0}" -gt 0 ] \
         && [ "${tag_count:-0}" -gt 0 ] && [ "${nbt_count:-0}" -gt 0 ]; then
        datapack="pass(${ss_count}ss,${nbt_count}nbt)"
      else
        datapack="fail"
        note="${note:+$note; }datapack(ss=${ss_count:-0},pool=${pool_count:-0},tag=${tag_count:-0},nbt=${nbt_count:-0})"
      fi

      # ------------------------------------------------------------------
      # 3A. Procedural Generation via /leaftree generate
      # ------------------------------------------------------------------
      rcon "forceload add -16 -16 31 31" >/dev/null
      sleep 2
      rcon "leaftree stats reset=true" >/dev/null
      sleep 1
      local gen_out
      gen_out="$(rcon "leaftree generate species=oak-small-mid world=world x=8 y=80 z=8")"
      wait_log_pattern "$log" "Generated .* at 8,80,8 in world" 10 || true

      # Allow TreeBlockBatcher regional/tick tasks to drain all slices
      local blocks_placed=0 batches_run=0
      for _ in $(seq 1 12); do
        sleep 1
        local stats_out
        stats_out="$(rcon "leaftree stats")"
        sleep 1
        local blocks_line batches_line
        blocks_line="$( (printf '%s\n' "$stats_out"; tail -n 80 "$log" 2>/dev/null) | grep -oE 'Total Blocks Placed: [0-9]+' | tail -n 1 || true )"
        batches_line="$( (printf '%s\n' "$stats_out"; tail -n 80 "$log" 2>/dev/null) | grep -oE 'Total Batches: [0-9]+' | tail -n 1 || true )"
        blocks_placed="${blocks_line#Total Blocks Placed: }"
        batches_run="${batches_line#Total Batches: }"
        if [ "${blocks_placed:-0}" -gt 0 ] && [ "${batches_run:-0}" -gt 0 ]; then
          break
        fi
      done

      if [ "${blocks_placed:-0}" -gt 0 ] && [ "${batches_run:-0}" -gt 0 ]; then
        proc_cmd="pass(${blocks_placed}blk,${batches_run}bat)"
      else
        proc_cmd="fail"
        note="${note:+$note; }proc_cmd(blocks=${blocks_placed:-0},batches=${batches_run:-0})"
      fi

      # ------------------------------------------------------------------
      # 3B. Procedural Generation Mode (generation-mode: PROCEDURAL)
      # ------------------------------------------------------------------
      if [ -f "$cfg_dir/config.yml" ]; then
        sed -i 's/^generation-mode:.*/generation-mode: PROCEDURAL/' "$cfg_dir/config.yml"
      fi
      rcon "leaftree reload" >/dev/null
      sleep 2
      rcon "leaftree debug" >/dev/null
      wait_log_pattern "$log" "Generation Mode: PROCEDURAL" 10 || true

      # Trigger chunk-load events across unvisited chunks in PROCEDURAL mode
      rcon "forceload add 160 160 191 191" >/dev/null
      sleep 4
      rcon "leaftree stats" >/dev/null
      sleep 2

      local sup_count=0 post_ss_count=0 chunks_loaded=0
      if [ -d "$dp_root/data/minecraft/worldgen/placed_feature" ]; then
        sup_count="$(find "$dp_root/data/minecraft/worldgen/placed_feature" -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]')"
      fi
      if [ -d "$dp_root/data/minecraft/worldgen/structure_set" ]; then
        post_ss_count="$(find "$dp_root/data/minecraft/worldgen/structure_set" -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]')"
      fi
      local chunks_line
      chunks_line="$(tail -n 120 "$log" 2>/dev/null | grep -oE 'Chunks Loaded: [0-9]+' | tail -n 1 || true)"
      chunks_loaded="${chunks_line#Chunks Loaded: }"

      if grep -qF "Generation Mode: PROCEDURAL" "$log" 2>/dev/null \
         && [ "${sup_count:-0}" -gt 0 ] && [ "${post_ss_count:-0}" -eq 0 ] \
         && [ "${chunks_loaded:-0}" -gt 0 ]; then
        proc_mode="pass(sup=${sup_count},chunks=${chunks_loaded:-0})"
      else
        proc_mode="fail"
        note="${note:+$note; }proc_mode(sup=${sup_count:-0},ss=${post_ss_count:-0},chunks=${chunks_loaded:-0})"
      fi

      rcon "forceload remove all" >/dev/null
    fi

    if [ -f "$log" ]; then
      cp -f "$log" "$row_dir/server.log"
      local hits
      hits="$(grep -cE "$LINKAGE_PATTERN" "$row_dir/server.log" || true)"
      if [ "${hits:-0}" -gt 0 ]; then
        linkage="$hits"
        grep -nE "$LINKAGE_PATTERN" "$row_dir/server.log" | head -n 20 > "$row_dir/linkage.txt"
      else
        linkage=0
      fi
    fi

    running || note="${note:+$note; }container not running at end"
  fi

  docker logs rtp-devstack-sweep > "$row_dir/container.log" 2>&1 || true
  if [ "$KEEP" -eq 0 ]; then
    compose down >/dev/null 2>&1 || true
    clean_host_dir "$data_dir"
  fi

  if [[ "$startup" == pass* ]] && [[ "$datapack" == pass* ]] \
     && [[ "$proc_cmd" == pass* ]] && [[ "$proc_mode" == pass* ]] \
     && [ "$linkage" = "0" ] && [ -z "$note" ]; then
    result="PASS"
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$slug" "$result" "$startup" "$datapack" "$proc_cmd" "$proc_mode" "$linkage" "$note" >> "$SUMMARY"
  echo "[$slug] $result (startup=$startup datapack=$datapack procedural_cmd=$proc_cmd procedural_mode=$proc_mode linkage=$linkage) $note"
}

trap 'if [ "$KEEP" -eq 0 ]; then compose down >/dev/null 2>&1 || true; fi' EXIT

while IFS='|' read -r -u 3 slug type version image mem modrinth init_marker; do
  [ -n "$slug" ] || continue
  if [ -n "$ONLY" ] && ! printf '%s' "$slug" | grep -qE "$ONLY"; then
    continue
  fi
  test_row "$slug" "$type" "$version" "$image" "$mem" "$modrinth" "$init_marker"
done 3<<< "$MATRIX"

echo
column -t -s $'\t' "$SUMMARY" 2>/dev/null || cat "$SUMMARY"
echo "[treegen-devstack] results: ${RUN_DIR#"$REPO_ROOT"/}"
awk -F'\t' 'NR > 1 && $2 != "PASS" { f = 1 } END { exit f }' "$SUMMARY"
