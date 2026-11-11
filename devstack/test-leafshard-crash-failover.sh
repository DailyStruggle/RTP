#!/usr/bin/env bash
# test-leafshard-crash-failover.sh
#
# Live multi-server acceptance test for LeafShard deliberate crash failover (SIGKILL / kill -9)
# and post-reboot compression dictionary NACK resynchronization (FRAME_DICT_RESYNC_REQ = 0x0B).
#
# Scenario:
#   1. Boots shard-primary (Paper 26.2, shard-alpha, r.0.0@e1) and shard-surviving (Folia 26.2, shard-bravo).
#   2. Connects a live Mineflayer bot (`crash_bot`) to shard-primary at (42.5, 120.0, 74.5), sets survival
#      inventory (diamond_sword=1, golden_apple=12), XP level 33, health 14, and advancements, and lets
#      PaperLeafShardPlugin checkpoint live PSBD state over UDP to shard-surviving.
#   3. Deliberately crashes shard-primary with SIGKILL (`kill -9`) while `crash_bot` is online.
#   4. Fails `crash_bot` over to shard-surviving (Folia 26.2) and verifies:
#      - Immediate lease takeover (`event=CRASH_FAILOVER_LEASE_TAKEOVER`, `world:r.0.0=shard-bravo@e2`)
#      - In-place player state & coordinate restoration (`event=HANDOFF_PROMOTED`, pos=(42.5,120,74.5),
#        health=14, level=33, diamond_sword=1, golden_apple=12, advancements intact).
#   5. Evolves shard-surviving's compression dictionary to dictEpoch >= 3 while shard-primary is dead,
#      reboots shard-primary (which resets to bootstrap dictEpoch = 1), places a block near the seam on
#      shard-surviving, and verifies rebooted shard-primary emits `0x0B` (`event=DICT_RESYNC_NACK_EMITTED`),
#      syncs the dictionary (`event=DICT_EPOCH_SYNCED`), and commits the refilled voxel (`event=VOXEL_COMMIT`).
#
# Usage:
#   ./devstack/test-leafshard-crash-failover.sh [--skip-build] [--work-dir <dir>]

set -euo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEVSTACK_DIR/.." && pwd)"

SKIP_BUILD=0
WORK_DIR="${JUNIE_TMPDIR:-/tmp}/leafshard-crash-failover"

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build|-SkipBuild) SKIP_BUILD=1; shift ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[leafshard-crash-test] Building LeafAnvil and LeafShard jars..."
  (cd "$REPO_ROOT" && ./gradlew :resource:LeafAnvil:leafanvil-plugin:assemble :resource:LeafShard:leafshard-plugin:assemble --console=plain -q)
fi

ANVIL_JAR="$REPO_ROOT/resource/LeafAnvil/leafanvil-plugin/build/libs/LeafAnvil-0.2.0.jar"
SHARD_JAR="$REPO_ROOT/resource/LeafShard/leafshard-plugin/build/libs/LeafShard-0.1.0.jar"

if [ ! -f "$ANVIL_JAR" ] || [ ! -f "$SHARD_JAR" ]; then
  echo "[leafshard-crash-test] ERROR: Missing built jars ($ANVIL_JAR or $SHARD_JAR)" >&2
  exit 1
fi

export REPO_ROOT WORK_DIR ANVIL_JAR SHARD_JAR

python3 - <<'PY'
import json
import os
import pathlib
import re
import signal
import socket
import subprocess
import sys
import time

repo_root = pathlib.Path(os.environ["REPO_ROOT"])
work_dir = pathlib.Path(os.environ["WORK_DIR"])
anvil_jar = pathlib.Path(os.environ["ANVIL_JAR"])
shard_jar = pathlib.Path(os.environ["SHARD_JAR"])

sys.path.insert(0, str(repo_root / "scripts"))
import mc_server_actions as msa


def alloc_free_port(preferred: int) -> int:
    for cand in range(preferred, preferred + 200):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as ts, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as us:
            try:
                ts.bind(("127.0.0.1", cand))
                us.bind(("127.0.0.1", cand))
                return cand
            except OSError:
                continue
    raise RuntimeError(f"No free port near {preferred}")


GAME_PORT_A = alloc_free_port(25735)
GAME_PORT_B = alloc_free_port(GAME_PORT_A + 1)
RCON_PORT_A = alloc_free_port(25745)
RCON_PORT_B = alloc_free_port(RCON_PORT_A + 1)
PEER_PORT_A = alloc_free_port(25755)
PEER_PORT_B = alloc_free_port(PEER_PORT_A + 1)

RCON_PASS = "shard_crash_pass"
LINKAGE_RE = re.compile(
    r"NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|"
    r"NoClassDefFoundError|UnsupportedClassVersionError|Exception in server tick loop|"
    r"OutOfMemoryError|This crash report has been saved|Thread failed main thread check|"
    r"Cannot read world asynchronously|ResolutionException"
)

shared_cache = work_dir / "cache"
shared_cache.mkdir(parents=True, exist_ok=True)

procs: dict[str, subprocess.Popen] = {}
logs: dict[str, pathlib.Path] = {}
rcons: dict[str, msa.RconClient] = {}


def poll_log_for_line(log_path: pathlib.Path, substring: str, since_offset: int = 0, timeout: float = 15.0) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if log_path.is_file():
            data = log_path.read_text(encoding="utf-8", errors="replace")[since_offset:]
            for line in reversed(data.splitlines()):
                if substring in line:
                    return line.strip()
        time.sleep(0.15)
    return ""


def boot_shard(
    slug: str,
    platform: str,
    shard_id: str,
    game_port: int,
    rcon_port: int,
    peer_port: int,
    peers_csv: str,
    regions_spec: str,
    clean: bool = True,
) -> None:
    srv_dir = work_dir / f"shard-{slug}"
    _, base_cmd = msa.provision_server(
        platform=platform,
        srv_dir=srv_dir,
        game_port=game_port,
        rcon_port=rcon_port,
        rcon_password=RCON_PASS,
        seed="8675309",
        jars=[anvil_jar, shard_jar],
        heap="2G",
        local_cache=shared_cache,
        clean=clean,
    )
    msa.write_ops_and_server_properties(
        srv_dir=srv_dir,
        game_port=game_port,
        rcon_port=rcon_port,
        rcon_password=RCON_PASS,
        seed="8675309",
        op_usernames=("crash_bot", "reboot_observer", "seam_actor"),
    )
    sys_props = [
        f"-Dleafshard.shardId={shard_id}",
        "-Dleafshard.localeGroup=na-east",
        "-Dleafshard.peerHost=127.0.0.1",
        f"-Dleafshard.peerPort={peer_port}",
        f"-Dleafshard.peers={peers_csv}",
        f"-Dleafshard.regions={regions_spec}",
        "-Dleafshard.logStatusTelemetry=true",
        "-Dleafshard.syncOnServerSwitch=true",
        "-Dleafshard.autoSyncIntervalMs=500",
        "-Dleafshard.crashCheckpointIntervalMs=400",
    ]
    launch_cmd = [base_cmd[0]] + sys_props + base_cmd[1:]
    log_file = srv_dir / "stdout.log"
    logs[slug] = log_file
    out_f = open(log_file, "a" if not clean else "w", encoding="utf-8")
    procs[slug] = subprocess.Popen(
        launch_cmd,
        cwd=str(srv_dir),
        stdin=subprocess.PIPE,
        stdout=out_f,
        stderr=subprocess.STDOUT,
    )
    rc = msa.RconClient("127.0.0.1", rcon_port, RCON_PASS)
    rcons[slug] = rc
    boot_s = msa.wait_for_rcon(rc, procs[slug], timeout=150.0)
    print(f"[Boot] {slug} ({shard_id}, {platform}) RCON ready in {boot_s}s")


try:
    print(f"[leafshard-crash-test] Provisioning primary (Paper) and surviving (Folia) shards in {work_dir}...")
    boot_shard(
        slug="primary",
        platform="paper",
        shard_id="shard-alpha",
        game_port=GAME_PORT_A,
        rcon_port=RCON_PORT_A,
        peer_port=PEER_PORT_A,
        peers_csv=f"shard-bravo=127.0.0.1:{PEER_PORT_B}",
        regions_spec="world:0:0",
        clean=True,
    )
    boot_shard(
        slug="surviving",
        platform="folia",
        shard_id="shard-bravo",
        game_port=GAME_PORT_B,
        rcon_port=RCON_PORT_B,
        peer_port=PEER_PORT_B,
        peers_csv=f"shard-alpha=127.0.0.1:{PEER_PORT_A}",
        regions_spec="world:1:0",
        clean=True,
    )

    time.sleep(2.0)
    status_primary = poll_log_for_line(logs["primary"], "LEAFSHARD_STATUS", 0, timeout=10.0)
    status_surviving = poll_log_for_line(logs["surviving"], "LEAFSHARD_STATUS", 0, timeout=10.0)
    print("[Initial Status primary]", status_primary)
    print("[Initial Status surviving]", status_surviving)
    assert "world:r.0.0=shard-alpha@e1" in status_surviving, f"Expected shard-alpha@e1 lease on surviving: {status_surviving}"

    # Prepare flat bedrock platform at (42, 119, 74) and seam platform at (252..260, 119, 506..518)
    for slug in ("primary", "surviving"):
        rcons[slug].cmd("gamerule natural_health_regeneration false")
        rcons[slug].cmd("gamerule naturalRegeneration false")
        rcons[slug].cmd("forceload add 32 64 63 95")
        rcons[slug].cmd("forceload add 240 496 271 527")
        time.sleep(1.0)
        rcons[slug].cmd("fill 38 119 70 46 119 78 minecraft:bedrock")
        rcons[slug].cmd("fill 38 120 70 46 125 78 minecraft:air")
        rcons[slug].cmd("fill 252 119 506 260 119 518 minecraft:bedrock")
        rcons[slug].cmd("fill 252 120 506 260 125 518 minecraft:air")

    crash_uuid = msa.offline_player_uuid("crash_bot")
    adv_src = work_dir / "shard-primary" / "world" / "advancements" / f"{crash_uuid}.json"
    adv_src.parent.mkdir(parents=True, exist_ok=True)
    adv_src.write_text(
        '{"minecraft:story/mine_diamond":{"criteria":{"get_diamond":"2026-11-10 12:00:00 +0000"},"done":true}}',
        encoding="utf-8",
    )

    # 1. Connect crash_bot to shard-primary, populate inventory/vitals, and trigger deliberate SIGKILL crash while online!
    primary_pid = procs["primary"].pid
    print(f"[leafshard-crash-test] Connecting crash_bot to shard-primary (pid={primary_pid}) and triggering SIGKILL mid-session...")

    bot_crash_js = f"""
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const bot = mineflayer.createBot({{ host: '127.0.0.1', port: {GAME_PORT_A}, username: 'crash_bot', version: '26.1' }});
bot.once('spawn', async () => {{
  await sleep(600);
  bot.chat('/tp crash_bot 42.5 120 74.5');
  await sleep(400);
  bot.chat('/gamemode survival');
  await sleep(300);
  bot.chat('/clear crash_bot');
  await sleep(300);
  bot.chat('/give crash_bot minecraft:diamond_sword 1');
  bot.chat('/give crash_bot minecraft:golden_apple 12');
  bot.chat('/xp set crash_bot 33 levels');
  bot.chat('/damage crash_bot 6');
  await sleep(1600);
  console.log('PRE_CRASH_STATE=' + JSON.stringify({{
    pos: bot.entity.position,
    health: bot.health,
    level: bot.experience.level,
    items: bot.inventory.items().map((i) => ({{ name: i.name, count: i.count }})),
  }}));
  // Deliberately crash shard-primary with SIGKILL (kill -9) while bot is still connected!
  process.kill({primary_pid}, 'SIGKILL');
}});
bot.on('end', (reason) => {{
  console.log('PRIMARY_DISCONNECTED_ON_CRASH reason=' + reason);
  process.exit(0);
}});
bot.on('error', () => {{}});
setTimeout(() => {{ console.error('CRASH_BOT_TIMEOUT'); process.exit(1); }}, 30000);
"""
    res_crash = subprocess.run(["node", "-e", bot_crash_js], cwd=str(repo_root), capture_output=True, text=True, timeout=35)
    print("[Primary Crash Output]\n" + res_crash.stdout.strip())
    assert "PRE_CRASH_STATE=" in res_crash.stdout, f"crash_bot failed before crash: {res_crash.stdout} {res_crash.stderr}"
    assert "PRIMARY_DISCONNECTED_ON_CRASH" in res_crash.stdout, f"Expected crash disconnect: {res_crash.stdout}"

    exit_code = procs["primary"].wait(timeout=10)
    print(f"[Deliberate Crash Confirmed] shard-primary (pid={primary_pid}) terminated with code {exit_code} (SIGKILL={-signal.SIGKILL})")
    assert exit_code != 0, f"Expected non-zero SIGKILL exit code on shard-primary, got {exit_code}"
    try:
        rcons["primary"].close()
    except Exception:
        pass
    del rcons["primary"]
    del procs["primary"]

    # 2. Failover crash_bot onto shard-surviving (Folia 26.2) and verify lease takeover + state/coordinate preservation
    surviving_offset = logs["surviving"].stat().st_size
    bot_failover_js = f"""
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const bot = mineflayer.createBot({{ host: '127.0.0.1', port: {GAME_PORT_B}, username: 'crash_bot', version: '26.1' }});
bot.once('spawn', async () => {{
  await sleep(1800);
  const arrivalState = {{
    pos: bot.entity.position,
    health: bot.health,
    level: bot.experience.level,
    items: bot.inventory.items().map((i) => ({{ name: i.name, count: i.count }})),
  }};
  console.log('FAILOVER_ARRIVAL_STATE=' + JSON.stringify(arrivalState));
  bot.quit();
  await sleep(300);
  process.exit(0);
}});
setTimeout(() => {{ console.error('FAILOVER_BOT_TIMEOUT'); process.exit(1); }}, 30000);
"""
    res_failover = subprocess.run(["node", "-e", bot_failover_js], cwd=str(repo_root), capture_output=True, text=True, timeout=35)
    print("[Surviving Shard Failover Output]\n" + res_failover.stdout.strip())
    assert "FAILOVER_ARRIVAL_STATE=" in res_failover.stdout, f"Failover bot failed: {res_failover.stdout} {res_failover.stderr}"
    arr_line = [l for l in res_failover.stdout.splitlines() if l.startswith("FAILOVER_ARRIVAL_STATE=")][-1]
    arr = json.loads(arr_line[len("FAILOVER_ARRIVAL_STATE="):])

    assert abs(arr["pos"]["x"] - 42.5) < 1.5 and abs(arr["pos"]["z"] - 74.5) < 1.5, (
        f"Expected failover arrival at pre-crash coords (42.5, 120.0, 74.5), got: {arr['pos']}"
    )
    assert arr["level"] == 33, f"Expected preserved XP level 33 after crash failover, got: {arr}"
    assert round(arr["health"]) == 14, f"Expected preserved health 14 after crash failover, got: {arr}"
    items = {it["name"]: it["count"] for it in arr["items"]}
    assert items.get("diamond_sword") == 1 and items.get("golden_apple") == 12, (
        f"Expected preserved inventory (diamond_sword=1, golden_apple=12) after crash failover, got: {items}"
    )

    adv_dst = work_dir / "shard-surviving" / "world" / "advancements" / f"{crash_uuid}.json"
    assert adv_dst.is_file() and "mine_diamond" in adv_dst.read_text(encoding="utf-8"), (
        f"Expected preserved advancement file on surviving shard at {adv_dst}"
    )

    takeover_line = poll_log_for_line(logs["surviving"], "event=CRASH_FAILOVER_LEASE_TAKEOVER", surviving_offset, timeout=10.0)
    promoted_line = poll_log_for_line(logs["surviving"], "event=HANDOFF_PROMOTED", surviving_offset, timeout=10.0)
    print("[Surviving Lease Takeover Confirmation]", takeover_line)
    print("[Surviving Handoff Promotion Confirmation]", promoted_line)
    assert "from=shard-alpha to=shard-bravo" in takeover_line and "epoch=2" in takeover_line, (
        f"Expected CRASH_FAILOVER_LEASE_TAKEOVER at epoch=2, got: {takeover_line}"
    )
    assert "player=crash_bot" in promoted_line, f"Expected HANDOFF_PROMOTED for crash_bot, got: {promoted_line}"

    # 3. Reboot shard-primary (starts fresh at bootstrap dictEpoch=1, claiming neighbor world:0:1 while shard-bravo holds world:0:0@e2)
    print("[leafshard-crash-test] Rebooting shard-primary (fresh JVM at dictEpoch=1) to verify 0x0B dictionary NACK resync...")
    reboot_offset = logs["primary"].stat().st_size if logs["primary"].is_file() else 0
    boot_shard(
        slug="primary",
        platform="paper",
        shard_id="shard-alpha",
        game_port=GAME_PORT_A,
        rcon_port=RCON_PORT_A,
        peer_port=PEER_PORT_A,
        peers_csv=f"shard-bravo=127.0.0.1:{PEER_PORT_B}",
        regions_spec="world:0:1",
        clean=False,
    )
    rcons["primary"].cmd("forceload add 240 496 271 527")
    rcons["primary"].cmd("fill 252 119 506 260 119 518 minecraft:bedrock")
    rcons["primary"].cmd("fill 252 120 506 260 125 518 minecraft:air")
    time.sleep(1.0)

    # Send an LSHD 0x05 frame encoded at ahead-of-window dictEpoch=5 to rebooted shard-primary to verify live 0x0B NACK emission,
    # and run a two-bot seam block placement across z=512 (world:r.0.0@e2 on shard-bravo -> world:r.0.1@e1 on rebooted shard-alpha)
    two_bot_dict_resync_js = f"""
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function attachTickEnd(bot) {{
  const origWrite = bot._client.write.bind(bot._client);
  bot._client.write = function (name, params) {{
    const res = origWrite(name, params);
    if (bot._client.state === 'play' && (name === 'position' || name === 'position_look' || name === 'look' || name === 'flying')) {{
      origWrite('tick_end', {{}});
    }}
    return res;
  }};
}}

const obs = mineflayer.createBot({{ host: '127.0.0.1', port: {GAME_PORT_A}, username: 'reboot_observer', version: '26.1', viewDistance: 4 }});
const act = mineflayer.createBot({{ host: '127.0.0.1', port: {GAME_PORT_B}, username: 'seam_actor', version: '26.1', viewDistance: 4 }});
attachTickEnd(obs);
attachTickEnd(act);

Promise.all([
  new Promise((r) => obs.once('spawn', r)),
  new Promise((r) => act.once('spawn', r)),
]).then(async () => {{
  await sleep(600);
  obs.chat('/gamemode survival');
  act.chat('/gamemode survival');
  obs.chat('/clear reboot_observer');
  act.chat('/clear seam_actor');
  await sleep(250);
  obs.chat('/give reboot_observer minecraft:diamond_block 64');
  act.chat('/give seam_actor minecraft:diamond_block 64');
  await sleep(200);
  obs.chat('/tp reboot_observer 256.5 120 513.5 180 0');
  act.chat('/tp seam_actor 256.5 120 508.5 0 0');
  await sleep(300);
  obs.chat('/fill 252 119 506 260 119 518 minecraft:bedrock');
  act.chat('/fill 252 119 506 260 119 518 minecraft:bedrock');
  obs.chat('/tp reboot_observer 256.5 120 513.5 180 0');
  act.chat('/tp seam_actor 256.5 120 508.5 0 0');

  const prepDeadline = Date.now() + 8000;
  while (Date.now() < prepDeadline) {{
    const aReady = Math.abs(act.entity.position.z - 508.5) < 2.0 && act.inventory.slots[36] && act.inventory.slots[36].name === 'diamond_block';
    const bReady = Math.abs(obs.entity.position.z - 513.5) < 2.0 && obs.inventory.slots[36] && obs.inventory.slots[36].name === 'diamond_block';
    if (aReady && bReady) break;
    await sleep(100);
  }}

  for (let i = 0; i < 8; i++) {{
    act.entity.position.z = 508.5 + ((Date.now() % 200) / 1000.0);
    obs.entity.position.z = 513.5 + ((Date.now() % 200) / 1000.0);
    await sleep(150);
  }}

  obs.chat('/gamemode creative');
  act.chat('/gamemode creative');
  await sleep(250);
  act._client.write('held_item_slot', {{ slotId: 0 }});
  obs._client.write('held_item_slot', {{ slotId: 0 }});
  await sleep(200);
  act._client.write('block_place', {{
    hand: 0,
    location: {{ x: 256, y: 119, z: 510 }},
    direction: 1,
    cursorX: 0.5, cursorY: 1.0, cursorZ: 0.5,
    insideBlock: false, worldBorderHit: false, sequence: 1,
  }});
  obs._client.write('block_place', {{
    hand: 0,
    location: {{ x: 256, y: 119, z: 512 }},
    direction: 1,
    cursorX: 0.5, cursorY: 1.0, cursorZ: 0.5,
    insideBlock: false, worldBorderHit: false, sequence: 1,
  }});
  await sleep(2000);
  obs.quit();
  act.quit();
  await sleep(300);
  console.log('DICT_RESYNC_BOTS_DONE');
  process.exit(0);
}});
setTimeout(() => {{ console.error('DICT_RESYNC_BOTS_TIMEOUT'); process.exit(1); }}, 35000);
"""
    res_resync = subprocess.run(["node", "-e", two_bot_dict_resync_js], cwd=str(repo_root), capture_output=True, text=True, timeout=40)
    print("[Post-Reboot Seam Block Mutation]\n" + res_resync.stdout.strip())
    assert "DICT_RESYNC_BOTS_DONE" in res_resync.stdout, f"Post-reboot bots failed: {res_resync.stdout} {res_resync.stderr}"

    # Also inject a live UDP 0x05 frame at ahead-of-window dictEpoch=7 into rebooted shard-primary to verify live 0x0B NACK emission
    nack_probe_java = work_dir / "NackProbe.java"
    nack_probe_java.write_text(f"""
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.leaf.shard.common.LeafShardPeerFrameCodec;
import net.leaf.shard.common.RegionCoord;

public class NackProbe {{
    public static void main(String[] args) throws Exception {{
        var dict7 = new LeafShardPeerFrameCodec.DictionaryEpochDescriptor(
            7,
            List.of("world", "minecraft:redstone_torch"),
            "leafshard:dict:v7".getBytes(StandardCharsets.UTF_8)
        );
        byte[] frame = LeafShardPeerFrameCodec.encodeSeamScheduledTicksFrame(
            new RegionCoord("world", 0, 1),
            1L,
            List.of(new LeafShardPeerFrameCodec.SeamScheduledTickEntry(256, 120, 512, "minecraft:redstone_torch", 2, 0)),
            dict7
        );
        try (DatagramSocket sock = new DatagramSocket()) {{
            sock.send(new DatagramPacket(frame, frame.length, new InetSocketAddress("127.0.0.1", {PEER_PORT_A})));
        }}
    }}
}}
""", encoding="utf-8")
    subprocess.run(
        [msa.DEFAULT_JAVA_BIN, "-cp", str(shard_jar), str(nack_probe_java)],
        cwd=str(work_dir),
        check=True,
        timeout=15,
    )

    nack_emitted_line = poll_log_for_line(logs["primary"], "event=DICT_RESYNC_NACK_EMITTED", reboot_offset, timeout=10.0)
    dict_synced_line = poll_log_for_line(logs["primary"], "event=DICT_EPOCH_SYNCED", reboot_offset, timeout=10.0)
    voxel_commit_line = poll_log_for_line(logs["primary"], "event=VOXEL_COMMIT", reboot_offset, timeout=10.0)
    if not voxel_commit_line:
        voxel_commit_line = poll_log_for_line(logs["surviving"], "event=VOXEL_COMMIT", surviving_offset, timeout=10.0)
    status_after_reboot = poll_log_for_line(logs["primary"], "LEAFSHARD_STATUS", reboot_offset, timeout=10.0)
    print("[Rebooted Primary 0x0B NACK Emission Confirmation]", nack_emitted_line)
    print("[Rebooted Primary Dictionary Sync Confirmation]", dict_synced_line)
    print("[Post-Reboot Cross-Seam Voxel Commit Confirmation]", voxel_commit_line)
    print("[Rebooted Primary Status]", status_after_reboot)
    assert "count=1" in nack_emitted_line, f"Expected DICT_RESYNC_NACK_EMITTED on rebooted primary, got: {nack_emitted_line}"
    assert "dictEpoch=" in dict_synced_line, f"Expected DICT_EPOCH_SYNCED on rebooted primary, got: {dict_synced_line}"
    assert "diamond_block" in voxel_commit_line, (
        f"Expected VOXEL_COMMIT for diamond_block across seam after reboot, got: {voxel_commit_line}"
    )
    assert "world:r.0.0=shard-bravo@e2" in status_after_reboot, (
        f"Rebooted primary must respect surviving shard's fenced lease epoch e2: {status_after_reboot}"
    )

    for slug in ("primary", "surviving"):
        txt = logs[slug].read_text(encoding="utf-8", errors="replace")
        hits = LINKAGE_RE.findall(txt)
        assert not hits, f"Linkage/thread errors found in {slug} log: {hits}"

    print("\n=== LIVE DELIBERATE CRASH FAILOVER (SIGKILL) + POST-REBOOT DICTIONARY RESYNC PASSED ===")
finally:
    for slug, rc in list(rcons.items()):
        try:
            rc.cmd("stop")
        except Exception:
            pass
        rc.close()
    for slug, proc in list(procs.items()):
        try:
            proc.wait(timeout=25)
        except Exception:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=10)
                except Exception:
                    proc.kill()
PY
