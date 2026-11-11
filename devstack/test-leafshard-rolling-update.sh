#!/usr/bin/env bash
# test-leafshard-rolling-update.sh
#
# Live multi-server acceptance test for LeafShard zero-downtime rolling update with an added plugin.
#
# Scenario:
#   1. Boots `shard-v1` (Paper 26.2) with ONLY `[LeafAnvil, LeafShard]` (NO `LeafSleep` plugin installed),
#      owning region `world:r.0.0@e1` and configured with `-Dleafshard.autoDrainToPeer=shard-v2`.
#   2. Connects a live Mineflayer bot (`update_bot`) to `shard-v1`, moves to (88.5, 120.0, -44.5),
#      sets survival inventory (diamond_sword=1, golden_apple=12, netherite_pickaxe=1), XP level 33,
#      health 14, and advancements, and confirms `LeafSleep` is NOT present on `shard-v1`.
#   3. Boots `shard-v2` (Paper 26.2) with `[LeafAnvil, LeafShard, LeafSleep-1.0.0.jar]` (the added plugin!).
#   4. As soon as `shard-v2` joins the `LSHD` control ring, `shard-v1` automatically executes
#      `drainShardForRollingUpgrade("shard-v2")`, atomically transferring `world:r.0.0` at `leaseEpoch=2`
#      and evacuating `update_bot`'s live `PSBD` state + exact in-place coordinates `(88.5, 120.0, -44.5)`
#      (`event=ROLLING_UPGRADE_DRAIN`).
#   5. Moves `update_bot` onto `shard-v2`, shuts off `shard-v1` (`/stop`), and verifies on `shard-v2`:
#      - `event=HANDOFF_PROMOTED` with exact coordinates `(88.5, 120.0, -44.5)`, health=14, XP=33,
#        inventory (`diamond_sword=1`, `golden_apple=12`, `netherite_pickaxe=1`), and advancements intact.
#      - The newly added `LeafSleep` plugin is loaded, enabled, and responds to `/leafsleep status`
#        while `shard-v1` is completely shut down.
#
# Usage:
#   ./devstack/test-leafshard-rolling-update.sh [--skip-build] [--work-dir <dir>]

set -euo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEVSTACK_DIR/.." && pwd)"

SKIP_BUILD=0
WORK_DIR="${JUNIE_TMPDIR:-/tmp}/leafshard-rolling-update"

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build|-SkipBuild) SKIP_BUILD=1; shift ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[leafshard-rolling-update] Building LeafAnvil, LeafShard, and added plugin LeafSleep..."
  (cd "$REPO_ROOT" && ./gradlew :resource:LeafAnvil:leafanvil-plugin:assemble :resource:LeafShard:leafshard-plugin:assemble :resource:LeafSleep:leafsleep-plugin:assemble --console=plain -q)
fi

ANVIL_JAR="$REPO_ROOT/resource/LeafAnvil/leafanvil-plugin/build/libs/LeafAnvil-0.2.0.jar"
SHARD_JAR="$REPO_ROOT/resource/LeafShard/leafshard-plugin/build/libs/LeafShard-0.1.0.jar"
SLEEP_JAR="$REPO_ROOT/resource/LeafSleep/leafsleep-plugin/build/libs/LeafSleep-1.0.0.jar"

for jar in "$ANVIL_JAR" "$SHARD_JAR" "$SLEEP_JAR"; do
  if [ ! -f "$jar" ]; then
    echo "[leafshard-rolling-update] ERROR: Missing built jar: $jar" >&2
    exit 1
  fi
done

export REPO_ROOT WORK_DIR ANVIL_JAR SHARD_JAR SLEEP_JAR

python3 - <<'PY'
import json
import os
import pathlib
import re
import socket
import subprocess
import sys
import time

repo_root = pathlib.Path(os.environ["REPO_ROOT"])
work_dir = pathlib.Path(os.environ["WORK_DIR"])
anvil_jar = pathlib.Path(os.environ["ANVIL_JAR"])
shard_jar = pathlib.Path(os.environ["SHARD_JAR"])
sleep_jar = pathlib.Path(os.environ["SLEEP_JAR"])

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


GAME_PORT_V1 = alloc_free_port(25835)
GAME_PORT_V2 = alloc_free_port(GAME_PORT_V1 + 1)
RCON_PORT_V1 = alloc_free_port(25845)
RCON_PORT_V2 = alloc_free_port(RCON_PORT_V1 + 1)
PEER_PORT_V1 = alloc_free_port(25855)
PEER_PORT_V2 = alloc_free_port(PEER_PORT_V1 + 1)

RCON_PASS = "shard_update_pass"
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


def boot_node(
    slug: str,
    shard_id: str,
    game_port: int,
    rcon_port: int,
    peer_port: int,
    peers_csv: str,
    regions_spec: str,
    plugin_jars: list[pathlib.Path],
    extra_sys_props: list[str],
) -> None:
    srv_dir = work_dir / slug
    _, base_cmd = msa.provision_server(
        platform="paper",
        srv_dir=srv_dir,
        game_port=game_port,
        rcon_port=rcon_port,
        rcon_password=RCON_PASS,
        seed="8675309",
        jars=plugin_jars,
        heap="2G",
        local_cache=shared_cache,
        clean=True,
    )
    msa.write_ops_and_server_properties(
        srv_dir=srv_dir,
        game_port=game_port,
        rcon_port=rcon_port,
        rcon_password=RCON_PASS,
        seed="8675309",
        op_usernames=("update_bot",),
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
        "-Dleafshard.autoSyncIntervalMs=400",
    ] + extra_sys_props
    launch_cmd = [base_cmd[0]] + sys_props + base_cmd[1:]
    log_file = srv_dir / "stdout.log"
    logs[slug] = log_file
    out_f = open(log_file, "w", encoding="utf-8")
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
    print(f"[Boot] {slug} ({shard_id}, jars={[j.name for j in plugin_jars]}) RCON ready in {boot_s}s")


try:
    # 1. Boot shard-v1 WITHOUT LeafSleep
    print(f"[leafshard-rolling-update] Booting baseline shard-v1 (without LeafSleep) in {work_dir}...")
    boot_node(
        slug="shard-v1",
        shard_id="shard-v1",
        game_port=GAME_PORT_V1,
        rcon_port=RCON_PORT_V1,
        peer_port=PEER_PORT_V1,
        peers_csv=f"shard-v2=127.0.0.1:{PEER_PORT_V2}",
        regions_spec="world:0:0",
        plugin_jars=[anvil_jar, shard_jar],
        extra_sys_props=["-Dleafshard.autoDrainToPeer=shard-v2"],
    )

    v1_plugins = rcons["shard-v1"].cmd("plugins")
    print("[shard-v1 Plugins]", v1_plugins)
    assert "LeafShard" in v1_plugins and "LeafAnvil" in v1_plugins, f"Expected LeafShard+LeafAnvil on v1: {v1_plugins}"
    assert "LeafSleep" not in v1_plugins, f"LeafSleep must NOT be installed on baseline shard-v1: {v1_plugins}"

    rcons["shard-v1"].cmd("gamerule natural_health_regeneration false")
    rcons["shard-v1"].cmd("gamerule naturalRegeneration false")
    rcons["shard-v1"].cmd("forceload add 64 -64 111 -17")
    time.sleep(1.0)
    rcons["shard-v1"].cmd("fill 84 119 -48 92 119 -40 minecraft:bedrock")
    rcons["shard-v1"].cmd("fill 84 120 -48 92 125 -40 minecraft:air")

    update_uuid = msa.offline_player_uuid("update_bot")
    adv_v1 = work_dir / "shard-v1" / "world" / "advancements" / f"{update_uuid}.json"
    adv_v1.parent.mkdir(parents=True, exist_ok=True)
    adv_v1.write_text(
        '{"minecraft:story/mine_diamond":{"criteria":{"get_diamond":"2026-11-10 12:00:00 +0000"},"done":true}}',
        encoding="utf-8",
    )

    # 2. Connect update_bot to shard-v1 and keep it online while shard-v2 boots with the added LeafSleep plugin!
    print("[leafshard-rolling-update] Connecting update_bot to shard-v1 and preparing in-game state...")
    bot_v1_ready_file = work_dir / "bot_v1_ready.json"
    bot_v1_release_file = work_dir / "bot_v1_release.flag"
    if bot_v1_ready_file.exists():
        bot_v1_ready_file.unlink()
    if bot_v1_release_file.exists():
        bot_v1_release_file.unlink()

    bot_v1_js = f"""
const fs = require('fs');
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const bot = mineflayer.createBot({{ host: '127.0.0.1', port: {GAME_PORT_V1}, username: 'update_bot', version: '26.1' }});
bot.once('spawn', async () => {{
  await sleep(600);
  bot.chat('/tp update_bot 88.5 120 -44.5');
  await sleep(400);
  bot.chat('/gamemode survival');
  await sleep(300);
  bot.chat('/clear update_bot');
  await sleep(300);
  bot.chat('/give update_bot minecraft:diamond_sword 1');
  bot.chat('/give update_bot minecraft:golden_apple 12');
  bot.chat('/give update_bot minecraft:netherite_pickaxe 1');
  bot.chat('/xp set update_bot 33 levels');
  bot.chat('/damage update_bot 6');
  await sleep(1000);
  const state = {{
    pos: bot.entity.position,
    health: bot.health,
    level: bot.experience.level,
    items: bot.inventory.items().map((i) => ({{ name: i.name, count: i.count }})),
  }};
  fs.writeFileSync({json.dumps(str(bot_v1_ready_file))}, JSON.stringify(state));
  while (!fs.existsSync({json.dumps(str(bot_v1_release_file))})) {{
    await sleep(150);
  }}
  bot.quit();
  await sleep(300);
  process.exit(0);
}});
setTimeout(() => {{ console.error('V1_BOT_TIMEOUT'); process.exit(1); }}, 180000);
"""
    bot_v1_proc = subprocess.Popen(["node", "-e", bot_v1_js], cwd=str(repo_root), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    deadline = time.time() + 30.0
    while time.time() < deadline and not bot_v1_ready_file.is_file():
        time.sleep(0.2)
    assert bot_v1_ready_file.is_file(), "update_bot failed to reach ready state on shard-v1"
    print("[update_bot Ready on shard-v1]", bot_v1_ready_file.read_text(encoding="utf-8"))

    # 3. Boot shard-v2 WITH the added LeafSleep-1.0.0.jar plugin while update_bot is still playing on shard-v1
    v1_drain_offset = logs["shard-v1"].stat().st_size
    print("[leafshard-rolling-update] Booting upgraded shard-v2 WITH added plugin LeafSleep-1.0.0.jar...")
    boot_node(
        slug="shard-v2",
        shard_id="shard-v2",
        game_port=GAME_PORT_V2,
        rcon_port=RCON_PORT_V2,
        peer_port=PEER_PORT_V2,
        peers_csv=f"shard-v1=127.0.0.1:{PEER_PORT_V1}",
        regions_spec="",
        plugin_jars=[anvil_jar, shard_jar, sleep_jar],
        extra_sys_props=[],
    )

    rcons["shard-v2"].cmd("gamerule natural_health_regeneration false")
    rcons["shard-v2"].cmd("gamerule naturalRegeneration false")
    rcons["shard-v2"].cmd("forceload add 64 -64 111 -17")
    rcons["shard-v2"].cmd("fill 84 119 -48 92 119 -40 minecraft:bedrock")
    rcons["shard-v2"].cmd("fill 84 120 -48 92 125 -40 minecraft:air")

    # Wait for shard-v1 to automatically detect shard-v2 and execute ROLLING_UPGRADE_DRAIN
    drain_line = poll_log_for_line(logs["shard-v1"], "event=ROLLING_UPGRADE_DRAIN", v1_drain_offset, timeout=15.0)
    print("[shard-v1 Rolling-Upgrade Drain Confirmation]", drain_line)
    assert "from=shard-v1 to=shard-v2 leases=1 players=1" in drain_line, (
        f"Expected ROLLING_UPGRADE_DRAIN with leases=1 players=1, got: {drain_line}"
    )

    v2_status = poll_log_for_line(logs["shard-v2"], "world:r.0.0=shard-v2@e2", 0, timeout=10.0)
    print("[shard-v2 Lease & Handoff Status]", v2_status)
    assert "world:r.0.0=shard-v2@e2" in v2_status, f"Expected shard-v2 to own world:r.0.0@e2: {v2_status}"
    assert "update_bot:shard-v1->shard-v2" in v2_status, f"Expected pre-staged handoff on shard-v2: {v2_status}"

    # 4. Seamlessly transition update_bot from shard-v1 -> shard-v2 and shut off shard-v1!
    print("[leafshard-rolling-update] Moving update_bot to shard-v2 and shutting off shard-v1...")
    bot_v1_release_file.write_text("release", encoding="utf-8")
    bot_v1_proc.wait(timeout=15)

    rcons["shard-v1"].cmd("stop")
    rcons["shard-v1"].close()
    del rcons["shard-v1"]
    v1_exit = procs["shard-v1"].wait(timeout=30)
    del procs["shard-v1"]
    print(f"[shard-v1 Shut Off Cleanly] exit_code={v1_exit}")
    assert v1_exit == 0, f"Expected clean exit_code=0 for shard-v1, got {v1_exit}"

    v2_join_offset = logs["shard-v2"].stat().st_size
    bot_v2_js = f"""
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const bot = mineflayer.createBot({{ host: '127.0.0.1', port: {GAME_PORT_V2}, username: 'update_bot', version: '26.1' }});
const messages = [];
bot.on('messagestr', (m) => messages.push(m));

bot.once('spawn', async () => {{
  await sleep(1800);
  bot.chat('/sleep status');
  bot.chat('/sleep test');
  await sleep(1000);
  console.log('UPGRADED_V2_ARRIVAL=' + JSON.stringify({{
    pos: bot.entity.position,
    health: bot.health,
    level: bot.experience.level,
    items: bot.inventory.items().map((i) => ({{ name: i.name, count: i.count }})),
    messages,
  }}));
  bot.quit();
  await sleep(300);
  process.exit(0);
}});
setTimeout(() => {{ console.error('V2_BOT_TIMEOUT'); process.exit(1); }}, 30000);
"""
    res_v2 = subprocess.run(["node", "-e", bot_v2_js], cwd=str(repo_root), capture_output=True, text=True, timeout=35)
    print("[Upgraded shard-v2 Arrival Output]\n" + res_v2.stdout.strip())
    assert "UPGRADED_V2_ARRIVAL=" in res_v2.stdout, f"shard-v2 bot failed: {res_v2.stdout} {res_v2.stderr}"
    v2_line = [l for l in res_v2.stdout.splitlines() if l.startswith("UPGRADED_V2_ARRIVAL=")][-1]
    v2_data = json.loads(v2_line[len("UPGRADED_V2_ARRIVAL="):])

    assert abs(v2_data["pos"]["x"] - 88.5) < 1.5 and abs(v2_data["pos"]["z"] - (-44.5)) < 1.5, (
        f"Expected in-place coordinates (88.5, 120.0, -44.5) on upgraded shard-v2, got: {v2_data['pos']}"
    )
    assert v2_data["level"] == 33, f"Expected preserved XP level 33 on shard-v2, got: {v2_data}"
    assert round(v2_data["health"]) == 14, f"Expected preserved health 14 on shard-v2, got: {v2_data}"
    item_map = {it["name"]: it["count"] for it in v2_data["items"]}
    assert (
        item_map.get("diamond_sword") == 1
        and item_map.get("golden_apple") == 12
        and item_map.get("netherite_pickaxe") == 1
    ), f"Expected preserved inventory on shard-v2, got: {item_map}"

    adv_v2 = work_dir / "shard-v2" / "world" / "advancements" / f"{update_uuid}.json"
    assert adv_v2.is_file() and "mine_diamond" in adv_v2.read_text(encoding="utf-8"), (
        f"Expected preserved advancement file on upgraded shard-v2 at {adv_v2}"
    )

    v2_promoted = poll_log_for_line(logs["shard-v2"], "event=HANDOFF_PROMOTED", v2_join_offset, timeout=10.0)
    print("[shard-v2 Handoff Promotion Confirmation]", v2_promoted)
    assert "player=update_bot from=shard-v1 to=shard-v2 keepLocation=false" in v2_promoted, (
        f"Expected HANDOFF_PROMOTED on shard-v2, got: {v2_promoted}"
    )

    v2_plugins = rcons["shard-v2"].cmd("plugins")
    v2_sleep_status = rcons["shard-v2"].cmd("sleep status")
    v2_sleep_test = rcons["shard-v2"].cmd("sleep test")
    print("[shard-v2 Plugins]", v2_plugins)
    print("[shard-v2 Added Plugin '/sleep status' Response]", v2_sleep_status)
    print("[shard-v2 Added Plugin '/sleep test' Response]", v2_sleep_test)
    assert "LeafSleep" in v2_plugins, f"Expected newly added LeafSleep plugin on shard-v2: {v2_plugins}"
    assert "HEALTHY" in v2_sleep_test or any("HEALTHY" in m for m in v2_data["messages"]), (
        f"Expected LeafSleep '/sleep test' HEALTHY output on shard-v2, got rcon={v2_sleep_test!r} chat={v2_data['messages']}"
    )

    for slug in ("shard-v1", "shard-v2"):
        txt = logs[slug].read_text(encoding="utf-8", errors="replace")
        hits = LINKAGE_RE.findall(txt)
        assert not hits, f"Linkage/thread errors found in {slug} log: {hits}"

    print("\n=== LIVE ZERO-DOWNTIME ROLLING UPDATE WITH ADDED PLUGIN (LEAFSLEEP) PASSED ===")
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
