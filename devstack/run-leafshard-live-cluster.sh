#!/usr/bin/env bash
# =============================================================================
# devstack/run-leafshard-live-cluster.sh
#
# Consolidated 4-Node Live Cluster Orchestration & Walkthrough Script for LeafShard:
#   - shard-paper    (Paper 26.2)     Game :25635 | RCON :25645 | UDP :25655 | region r.0.0
#   - shard-folia    (Folia 26.2)     Game :25636 | RCON :25646 | UDP :25656 | region r.1.0
#   - shard-fabric   (Fabric 26.1.2)  Game :25637 | RCON :25647 | UDP :25657 | region r.0.1
#   - shard-neoforge (NeoForge 26.1.2)Game :25638 | RCON :25648 | UDP :25658 | region r.1.1
#
# Subcommands:
#   start                        Sync jars and start any stopped shards in /tmp/leafshard-live-cluster
#   status                       Show live process/port/RCON/player status for all 4 shards
#   prep-seam                    Teleport leaf_26 / leaf26 to the r.0.0 <-> r.1.0 seam (x=512) and grant test kit
#   rolling-update [src] [dst]   Hot-swap latest jars + drain players via native /transfer + PSBD checkpoint
#   crash-failover [victim] [to] Simulate SIGKILL (-9) on victim shard and promote PSBD checkpoint on survivor
#   restart-all                  Sync latest LeafShard-0.1.0.jar to all 4 shards and restart them cleanly
#   stop                         Gracefully stop all 4 live cluster shards
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_DIR="${LEAFSHARD_CLUSTER_DIR:-/tmp/leafshard-live-cluster}"
RCON_PASSWORD="${LEAFSHARD_RCON_PASSWORD:-shard_rcon_pass}"
JAVA25_BIN="${LEAFSHARD_JAVA_BIN:-/usr/lib/jvm/java-25-openjdk/bin/java}"
ACCOUNT_A="${LEAFSHARD_ACCOUNT_A:-leaf_26}"
ACCOUNT_B="${LEAFSHARD_ACCOUNT_B:-leaf26}"

declare -A SHARD_GAME_PORT=(
    ["shard-paper"]=25635
    ["shard-folia"]=25636
    ["shard-fabric"]=25637
    ["shard-neoforge"]=25638
)
declare -A SHARD_RCON_PORT=(
    ["shard-paper"]=25645
    ["shard-folia"]=25646
    ["shard-fabric"]=25647
    ["shard-neoforge"]=25648
)
declare -A SHARD_UDP_PORT=(
    ["shard-paper"]=25655
    ["shard-folia"]=25656
    ["shard-fabric"]=25657
    ["shard-neoforge"]=25658
)
declare -A SHARD_REGION=(
    ["shard-paper"]="world:0:0"
    ["shard-folia"]="world:1:0"
    ["shard-fabric"]="world:0:1"
    ["shard-neoforge"]="world:1:1"
)
declare -A SHARD_LOCALE=(
    ["shard-paper"]="eu-central"
    ["shard-folia"]="na-east"
    ["shard-fabric"]="na-west"
    ["shard-neoforge"]="ap-east"
)

SHARD_ORDER=("shard-paper" "shard-folia" "shard-fabric" "shard-neoforge")

rcon_exec() {
    local rcon_port="$1"
    local cmd="$2"
    python3 - "$rcon_port" "$RCON_PASSWORD" "$cmd" <<'PY'
import socket, struct, sys

port = int(sys.argv[1])
password = sys.argv[2]
command = sys.argv[3]

def send_pkt(sock, req_id, pkt_type, payload):
    body = struct.pack("<ii", req_id, pkt_type) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)

def recv_pkt(sock):
    raw_len = sock.recv(4)
    if len(raw_len) < 4:
        return -1, -1, ""
    length = struct.unpack("<i", raw_len)[0]
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    req_id, pkt_type = struct.unpack("<ii", data[:8])
    payload = data[8:-2].decode("utf-8", errors="replace")
    return req_id, pkt_type, payload

try:
    with socket.create_connection(("127.0.0.1", port), timeout=2.5) as s:
        send_pkt(s, 1, 3, password)
        rid, _, _ = recv_pkt(s)
        if rid == -1:
            sys.exit(2)
        send_pkt(s, 2, 2, command)
        _, _, out = recv_pkt(s)
        if out:
            print(out.strip())
except Exception:
    sys.exit(1)
PY
}

shard_pid() {
    local shard="$1"
    local target_cwd="${CLUSTER_DIR}/${shard}"
    local found
    found="$(python3 - "$target_cwd" <<'PY'
import os, sys

target = os.path.realpath(sys.argv[1])
for pid in sorted(os.listdir("/proc")):
    if not pid.isdigit():
        continue
    try:
        cwd = os.path.realpath(os.readlink(f"/proc/{pid}/cwd"))
        if cwd == target:
            cmd = open(f"/proc/{pid}/cmdline", "rb").read().decode("utf-8", errors="ignore")
            if "java" in cmd and ("paper" in cmd or "folia" in cmd or "fabric" in cmd or "neoforge" in cmd or "unix_args" in cmd):
                print(pid)
                sys.exit(0)
    except Exception:
        pass
sys.exit(1)
PY
)" || true
    if [[ -n "$found" ]]; then
        echo "$found"
        return 0
    fi
    return 1
}

wait_for_rcon() {
    local shard="$1"
    local timeout_sec="${2:-90}"
    local port="${SHARD_RCON_PORT[$shard]}"
    local deadline=$((SECONDS + timeout_sec))
    while (( SECONDS < deadline )); do
        if rcon_exec "$port" "list" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "[ERROR] Timed out waiting for ${shard} RCON on :${port}" >&2
    return 1
}

sync_jars_to_shard() {
    local shard="$1"
    local sdir="${CLUSTER_DIR}/${shard}"
    local unified_jar="${ROOT_DIR}/resource/LeafShard/leafshard-plugin/build/libs/LeafShard-0.1.0.jar"
    if [[ ! -f "$unified_jar" ]]; then
        echo "[WARN] ${unified_jar} not found; run ./gradlew :resource:LeafShard:leafshard-plugin:shadowJar first." >&2
        return 0
    fi
    case "$shard" in
        shard-paper|shard-folia)
            mkdir -p "${sdir}/plugins"
            cp -f "$unified_jar" "${sdir}/plugins/LeafShard-0.1.0.jar"
            ;;
        shard-fabric|shard-neoforge)
            mkdir -p "${sdir}/mods"
            cp -f "$unified_jar" "${sdir}/mods/LeafShard-0.1.0.jar"
            ;;
    esac
}

peer_list_for_shard() {
    local self="$1"
    local items=()
    for other in "${SHARD_ORDER[@]}"; do
        if [[ "$other" != "$self" ]]; then
            items+=("${other}=127.0.0.1:${SHARD_UDP_PORT[$other]}")
        fi
    done
    local IFS=,
    echo "${items[*]}"
}

start_single_shard() {
    local shard="$1"
    local sdir="${CLUSTER_DIR}/${shard}"
    if pid="$(shard_pid "$shard")"; then
        echo "[OK] ${shard} already running (PID ${pid}, game :${SHARD_GAME_PORT[$shard]})"
        return 0
    fi
    sync_jars_to_shard "$shard"
    rm -f "${sdir}/world/session.lock"
    local peers
    peers="$(peer_list_for_shard "$shard")"
    local common_props=(
        "-Dleafshard.shardId=${shard}"
        "-Dleafshard.localeGroup=${SHARD_LOCALE[$shard]}"
        "-Dleafshard.peerHost=127.0.0.1"
        "-Dleafshard.peerPort=${SHARD_UDP_PORT[$shard]}"
        "-Dleafshard.peers=${peers}"
        "-Dleafshard.regions=${SHARD_REGION[$shard]}"
        "-Dleafshard.logStatusTelemetry=true"
        "-Dleafshard.syncOnServerSwitch=true"
        "-Dleafshard.autoSyncIntervalMs=500"
        "-Dleafshard.crashCheckpointIntervalMs=400"
        "-Xms2G" "-Xmx2G" "-XX:+UseG1GC"
        "--enable-native-access=ALL-UNNAMED"
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
        "--add-opens=java.base/java.nio=ALL-UNNAMED"
        "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )
    echo "[START] Launching ${shard} on game :${SHARD_GAME_PORT[$shard]} (RCON :${SHARD_RCON_PORT[$shard]}, UDP :${SHARD_UDP_PORT[$shard]})..."
    case "$shard" in
        shard-paper)
            (
                cd "$sdir"
                nohup "$JAVA25_BIN" "${common_props[@]}" -Dleafshard.msptMean=4.2 -jar paper-26.2.jar nogui >> "${sdir}/stdout.log" 2>&1 < /dev/null &
                echo $! > "${sdir}/server.pid"
            )
            ;;
        shard-folia)
            (
                cd "$sdir"
                nohup "$JAVA25_BIN" "${common_props[@]}" -Dleafshard.msptMean=3.8 -jar folia-26.2.jar nogui >> "${sdir}/stdout.log" 2>&1 < /dev/null &
                echo $! > "${sdir}/server.pid"
            )
            ;;
        shard-fabric)
            (
                cd "$sdir"
                nohup "$JAVA25_BIN" "${common_props[@]}" -Dleafshard.msptMean=3.6 -jar fabric-server-launch.jar nogui >> "${sdir}/stdout.log" 2>&1 < /dev/null &
                echo $! > "${sdir}/server.pid"
            )
            ;;
        shard-neoforge)
            (
                cd "$sdir"
                nohup "$JAVA25_BIN" @user_jvm_args.txt @libraries/net/neoforged/neoforge/26.1.2.71/unix_args.txt nogui >> "${sdir}/stdout.log" 2>&1 < /dev/null &
                echo $! > "${sdir}/server.pid"
            )
            ;;
    esac
}

stop_single_shard() {
    local shard="$1"
    if pid="$(shard_pid "$shard")"; then
        echo "[STOP] Stopping ${shard} (PID ${pid})..."
        rcon_exec "${SHARD_RCON_PORT[$shard]}" "stop" >/dev/null 2>&1 || true
        local deadline=$((SECONDS + 20))
        while (( SECONDS < deadline )); do
            if ! shard_pid "$shard" >/dev/null 2>&1; then
                return 0
            fi
            sleep 1
        done
        if pid2="$(shard_pid "$shard")"; then
            kill "$pid2" 2>/dev/null || true
            sleep 1
        fi
    fi
}

stage_psbd_handoff() {
    local src_shard="$1"
    local dst_shard="$2"
    python3 - "${CLUSTER_DIR}/${src_shard}" "${CLUSTER_DIR}/${dst_shard}" <<'PY'
import pathlib, shutil, sys

src_root = pathlib.Path(sys.argv[1])
dst_root = pathlib.Path(sys.argv[2])
for src_rel in [
    pathlib.Path("leafshard-data/snapshots"),
    pathlib.Path("plugins/LeafShard/snapshots"),
    pathlib.Path("config/leafshard/snapshots"),
]:
    src_snap = src_root / src_rel
    if not src_snap.exists():
        continue
    for dst_rel in [
        pathlib.Path("leafshard-data/snapshots/staged-restore"),
        pathlib.Path("plugins/LeafShard/snapshots/staged-restore"),
        pathlib.Path("config/leafshard/snapshots/staged-restore"),
    ]:
        target_dir = dst_root / dst_rel
        target_dir.mkdir(parents=True, exist_ok=True)
        for psbd in src_snap.rglob("*.psbd"):
            if "staged-restore" in str(psbd):
                continue
            shutil.copy2(psbd, target_dir / psbd.name)
            print(f"[PSBD] Staged {psbd.name} -> {target_dir / psbd.name}")
PY
}

cmd_status() {
    printf "%-16s %-10s %-10s %-10s %-12s %s\n" "SHARD" "PID" "GAME_PORT" "UDP_PORT" "REGION" "ONLINE_PLAYERS"
    printf "%-16s %-10s %-10s %-10s %-12s %s\n" "-----" "---" "---------" "--------" "------" "--------------"
    for shard in "${SHARD_ORDER[@]}"; do
        local pid="-"
        if p="$(shard_pid "$shard")"; then
            pid="$p"
        fi
        local list_out="offline"
        if [[ "$pid" != "-" ]]; then
            list_out="$(rcon_exec "${SHARD_RCON_PORT[$shard]}" "list" 2>/dev/null || echo "starting...")"
        fi
        printf "%-16s %-10s :%-9s :%-9s %-12s %s\n" \
            "$shard" "$pid" "${SHARD_GAME_PORT[$shard]}" "${SHARD_UDP_PORT[$shard]}" "${SHARD_REGION[$shard]}" "$list_out"
    done
}

cmd_start() {
    for shard in "${SHARD_ORDER[@]}"; do
        start_single_shard "$shard"
    done
    for shard in "${SHARD_ORDER[@]}"; do
        wait_for_rcon "$shard" 90
    done
    cmd_status
}

cmd_stop() {
    for shard in "${SHARD_ORDER[@]}"; do
        stop_single_shard "$shard"
    done
}

cmd_restart_all() {
    for shard in "${SHARD_ORDER[@]}"; do
        stop_single_shard "$shard"
    done
    for shard in "${SHARD_ORDER[@]}"; do
        start_single_shard "$shard"
    done
    for shard in "${SHARD_ORDER[@]}"; do
        wait_for_rcon "$shard" 90
    done
    cmd_status
}

cmd_prep_seam() {
    echo "[PREP] Preparing seam at x=512 (r.0.0 <-> r.1.0) for ${ACCOUNT_A} and ${ACCOUNT_B}..."
    for shard in "${SHARD_ORDER[@]}"; do
        local rport="${SHARD_RCON_PORT[$shard]}"
        rcon_exec "$rport" "gamerule sendCommandFeedback false" >/dev/null 2>&1 || true
        rcon_exec "$rport" "gamerule keepInventory true" >/dev/null 2>&1 || true
        rcon_exec "$rport" "time set day" >/dev/null 2>&1 || true
        rcon_exec "$rport" "weather clear" >/dev/null 2>&1 || true
        rcon_exec "$rport" "forceload add 508 32 516 32" >/dev/null 2>&1 || true
        rcon_exec "$rport" "fill 504 63 24 520 63 40 minecraft:stone_bricks" >/dev/null 2>&1 || true
        rcon_exec "$rport" "fill 512 63 24 512 63 40 minecraft:gold_block" >/dev/null 2>&1 || true
        rcon_exec "$rport" "fill 504 64 24 520 68 40 minecraft:air" >/dev/null 2>&1 || true
    done
    rcon_exec "${SHARD_RCON_PORT[shard-paper]}" "tp ${ACCOUNT_A} 510.5 64 32.5 -90 0" >/dev/null 2>&1 || true
    rcon_exec "${SHARD_RCON_PORT[shard-folia]}" "tp ${ACCOUNT_B} 513.5 64 32.5 90 0" >/dev/null 2>&1 || true
    for shard in "shard-paper" "shard-folia"; do
        local rport="${SHARD_RCON_PORT[$shard]}"
        for acct in "$ACCOUNT_A" "$ACCOUNT_B"; do
            rcon_exec "$rport" "gamemode survival ${acct}" >/dev/null 2>&1 || true
            rcon_exec "$rport" "give ${acct} minecraft:diamond_sword 1" >/dev/null 2>&1 || true
            rcon_exec "$rport" "give ${acct} minecraft:shield 1" >/dev/null 2>&1 || true
            rcon_exec "$rport" "give ${acct} minecraft:bow 1" >/dev/null 2>&1 || true
            rcon_exec "$rport" "give ${acct} minecraft:arrow 64" >/dev/null 2>&1 || true
            rcon_exec "$rport" "give ${acct} minecraft:golden_apple 16" >/dev/null 2>&1 || true
            rcon_exec "$rport" "give ${acct} minecraft:chest 16" >/dev/null 2>&1 || true
            rcon_exec "$rport" "give ${acct} minecraft:diamond_helmet 1" >/dev/null 2>&1 || true
            rcon_exec "$rport" "give ${acct} minecraft:diamond_chestplate 1" >/dev/null 2>&1 || true
        done
    done
    echo "[OK] Seam platform (x=504..520, y=64, z=24..40, gold line x=512) ready."
}

cmd_rolling_update() {
    local src="${1:-shard-paper}"
    local dst="${2:-shard-folia}"
    local src_rcon="${SHARD_RCON_PORT[$src]}"
    local dst_game="${SHARD_GAME_PORT[$dst]}"
    local src_game="${SHARD_GAME_PORT[$src]}"

    echo "[ROLLING-UPDATE] Step 1: Flushing ${src} state and staging PSBD checkpoints -> ${dst}..."
    rcon_exec "$src_rcon" "save-all flush" >/dev/null 2>&1 || true
    sleep 1
    stage_psbd_handoff "$src" "$dst"

    echo "[ROLLING-UPDATE] Step 2: Evacuating online players from ${src} -> ${dst} (:${dst_game}) via /transfer..."
    for acct in "$ACCOUNT_A" "$ACCOUNT_B"; do
        rcon_exec "$src_rcon" "transfer 127.0.0.1 ${dst_game} ${acct}" >/dev/null 2>&1 || true
    done
    sleep 2

    echo "[ROLLING-UPDATE] Step 3: Stopping ${src}, syncing rebuilt jar, and restarting..."
    stop_single_shard "$src"
    start_single_shard "$src"
    wait_for_rcon "$src" 90
    echo "[ROLLING-UPDATE] Step 4: ${src} back online on :${src_game} with updated jar."
}

cmd_crash_failover() {
    local victim="${1:-shard-folia}"
    local survivor="${2:-shard-paper}"
    local survivor_game="${SHARD_GAME_PORT[$survivor]}"
    local survivor_rcon="${SHARD_RCON_PORT[$survivor]}"

    echo "[CRASH-FAILOVER] Step 1: Staging latest periodic PSBD checkpoints from ${victim} -> ${survivor}..."
    stage_psbd_handoff "$victim" "$survivor"

    if pid="$(shard_pid "$victim")"; then
        echo "[CRASH-FAILOVER] Step 2: Sending SIGKILL (kill -9) to ${victim} (PID ${pid})..."
        kill -9 "$pid"
    else
        echo "[WARN] ${victim} is not currently running."
    fi
    sleep 2
    echo "[CRASH-FAILOVER] Step 3: ${victim} terminated; ${survivor} (:${survivor_game}) holds staged PSBD restore."
    rcon_exec "$survivor_rcon" "list" || true
}

subcmd="${1:-status}"
shift || true
case "$subcmd" in
    start)          cmd_start "$@" ;;
    status)         cmd_status "$@" ;;
    prep-seam)      cmd_prep_seam "$@" ;;
    rolling-update) cmd_rolling_update "$@" ;;
    crash-failover) cmd_crash_failover "$@" ;;
    restart-all)    cmd_restart_all "$@" ;;
    stop)           cmd_stop "$@" ;;
    sync-jars)
        for s in "${SHARD_ORDER[@]}"; do sync_jars_to_shard "$s"; done
        ;;
    *)
        echo "Usage: $0 {start|status|prep-seam|rolling-update|crash-failover|restart-all|sync-jars|stop}" >&2
        exit 1
        ;;
esac
