#!/usr/bin/env bash
# test-leafshard-acceptance.sh
#
# Reproducible 4-platform devstack acceptance verification for LeafShard + LeafAnvil
# across Paper, Folia, Fabric, and NeoForge (MC 26.1.2 / 26.2 on Java 25).
#
# Boots a simultaneous 4-node heterogeneous LSHD (0x4C534844) UDP mesh cluster:
#   1. shard-paper    (Paper 26.2,       eu-central, game=25635, rcon=25645, udp=25655)
#   2. shard-folia    (Folia 26.2,       na-east,    game=25636, rcon=25646, udp=25656)
#   3. shard-fabric   (Fabric 26.1.2,    na-west,    game=25637, rcon=25647, udp=25657)
#   4. shard-neoforge (NeoForge 26.1.2,  ap-east,    game=25638, rcon=25648, udp=25658)
#
# Verifies across all 4 platforms:
#   1. Startup, LeafAnvil .lca world storage attachment, and declarative -Dleafshard.regions configuration.
#   2. Autonomous LSHD control-ring sync (0x06) & region lease convergence without commands.
#   3. Live Mineflayer bot (`bench_000`) 2-chunk hysteresis seam handoff (0x04) & arriving
#      player teleport promotion across Paper -> Folia and Fabric -> NeoForge.
#   4. Zero linkage, classloader, or Folia region-thread ownership errors across all 4 logs.
#
# Usage:
#   ./devstack/test-leafshard-acceptance.sh [--skip-build] [--work-dir <dir>]

set -euo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEVSTACK_DIR/.." && pwd)"

SKIP_BUILD=0
WORK_DIR="${JUNIE_TMPDIR:-/tmp}/leafshard-devstack-4plat"

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build|-SkipBuild) SKIP_BUILD=1; shift ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[leafshard-devstack] Building LeafAnvil and LeafShard multi-loader jars..."
  (cd "$REPO_ROOT" && ./gradlew :resource:LeafAnvil:leafanvil-plugin:assemble :resource:LeafShard:leafshard-plugin:assemble --console=plain -q)
fi

ANVIL_JAR="$REPO_ROOT/resource/LeafAnvil/leafanvil-plugin/build/libs/LeafAnvil-0.2.0.jar"
SHARD_JAR="$REPO_ROOT/resource/LeafShard/leafshard-plugin/build/libs/LeafShard-0.1.0.jar"

if [ ! -f "$ANVIL_JAR" ] || [ ! -f "$SHARD_JAR" ]; then
  echo "[leafshard-devstack] ERROR: Missing built jars ($ANVIL_JAR or $SHARD_JAR)" >&2
  exit 1
fi

export REPO_ROOT WORK_DIR ANVIL_JAR SHARD_JAR

python3 - <<'PY'
import hashlib
import os
import pathlib
import re
import subprocess
import sys
import time
import uuid

repo_root = pathlib.Path(os.environ["REPO_ROOT"])
work_dir = pathlib.Path(os.environ["WORK_DIR"])
anvil_jar = pathlib.Path(os.environ["ANVIL_JAR"])
shard_jar = pathlib.Path(os.environ["SHARD_JAR"])

sys.path.insert(0, str(repo_root / "scripts"))
import mc_server_actions as msa

NODES = [
    {
        "slug": "paper",
        "platform": "paper",
        "shard_id": "shard-paper",
        "locale": "eu-central",
        "game_port": 25635,
        "rcon_port": 25645,
        "peer_port": 25655,
        "rx": 0,
        "rz": 0,
        "mspt": "4.2",
    },
    {
        "slug": "folia",
        "platform": "folia",
        "shard_id": "shard-folia",
        "locale": "na-east",
        "game_port": 25636,
        "rcon_port": 25646,
        "peer_port": 25656,
        "rx": 1,
        "rz": 0,
        "mspt": "3.8",
    },
    {
        "slug": "fabric",
        "platform": "fabric",
        "shard_id": "shard-fabric",
        "locale": "na-west",
        "game_port": 25637,
        "rcon_port": 25647,
        "peer_port": 25657,
        "rx": 0,
        "rz": 1,
        "mspt": "3.6",
    },
    {
        "slug": "neoforge",
        "platform": "neoforge",
        "shard_id": "shard-neoforge",
        "locale": "ap-east",
        "game_port": 25638,
        "rcon_port": 25648,
        "peer_port": 25658,
        "rx": 1,
        "rz": 1,
        "mspt": "3.5",
    },
]

RCON_PASS = "shard_rcon_pass"
LINKAGE_RE = re.compile(
    r"NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|"
    r"NoClassDefFoundError|UnsupportedClassVersionError|Exception in server tick loop|"
    r"OutOfMemoryError|This crash report has been saved|Thread failed main thread check|"
    r"Cannot read world asynchronously|ResolutionException"
)

shared_cache = work_dir / "cache"
shared_cache.mkdir(parents=True, exist_ok=True)

procs = {}
logs = {}
rcons = {}

def poll_log_for_line(log_path: pathlib.Path, prefix: str, since_offset: int, timeout: float = 10.0) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if log_path.is_file():
            data = log_path.read_text(encoding="utf-8", errors="replace")[since_offset:]
            for line in reversed(data.splitlines()):
                if prefix in line:
                    idx = line.index(prefix)
                    return line[idx:].strip()
        time.sleep(0.15)
    return ""

def poll_status_line(slug: str, since_offset: int = 0, timeout: float = 10.0) -> str:
    return poll_log_for_line(logs[slug], "LEAFSHARD_STATUS", since_offset, timeout=timeout)

try:
    print(f"[leafshard-devstack] Provisioning 4 platforms in {work_dir}...")
    for node in NODES:
        slug = node["slug"]
        srv_dir = work_dir / f"shard-{slug}"
        peers_csv = ",".join(
            f"{peer['shard_id']}=127.0.0.1:{peer['peer_port']}"
            for peer in NODES
            if peer["slug"] != slug
        )
        _, base_cmd = msa.provision_server(
            platform=node["platform"],
            srv_dir=srv_dir,
            game_port=node["game_port"],
            rcon_port=node["rcon_port"],
            rcon_password=RCON_PASS,
            seed="8675309",
            jars=[anvil_jar, shard_jar],
            heap="2G",
            local_cache=shared_cache,
            clean=True,
        )
        sys_props = [
            f"-Dleafshard.shardId={node['shard_id']}",
            f"-Dleafshard.localeGroup={node['locale']}",
            "-Dleafshard.peerHost=127.0.0.1",
            f"-Dleafshard.peerPort={node['peer_port']}",
            f"-Dleafshard.peers={peers_csv}",
            f"-Dleafshard.regions=world:{node['rx']}:{node['rz']}",
            "-Dleafshard.logStatusTelemetry=true",
            f"-Dleafshard.msptMean={node['mspt']}",
        ]
        if node["platform"] == "neoforge":
            uargs = srv_dir / "user_jvm_args.txt"
            uargs.write_text(uargs.read_text(encoding="utf-8") + "\n".join(sys_props) + "\n", encoding="utf-8")
            launch_cmd = base_cmd
        else:
            launch_cmd = [base_cmd[0]] + sys_props + base_cmd[1:]

        log_file = srv_dir / "stdout.log"
        logs[slug] = log_file
        out_f = open(log_file, "w", encoding="utf-8")
        procs[slug] = subprocess.Popen(launch_cmd, cwd=str(srv_dir), stdout=out_f, stderr=subprocess.STDOUT)

    for node in NODES:
        slug = node["slug"]
        rc = msa.RconClient("127.0.0.1", node["rcon_port"], RCON_PASS)
        rcons[slug] = rc
        boot_s = msa.wait_for_rcon(rc, procs[slug], timeout=150.0)
        print(f"[Boot] {slug} ({node['shard_id']}, {node['locale']}) RCON ready in {boot_s}s")

    # 1. Verify autonomous LSHD control-ring sync & declarative region lease convergence across all 4 platforms
    time.sleep(2.5)
    for node in NODES:
        slug = node["slug"]
        status_out = poll_status_line(slug, 0, timeout=10.0)
        print(f"[Status {slug}] {status_out}")
        for peer in NODES:
            assert peer["shard_id"] in status_out, f"{slug} missing peer {peer['shard_id']} in {status_out}"
            region_token = f"world:r.{peer['rx']}.{peer['rz']}={peer['shard_id']}@"
            assert region_token in status_out, f"{slug} missing lease {region_token} in {status_out}"

    # 2. Live Mineflayer bot crosses 2-chunk hysteresis seam on Paper (r.0.0 -> r.1.0 owned by Folia)
    bot_paper_js = """
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25635, username: 'bench_000', version: '26.1' });
const origWrite = bot._client.write.bind(bot._client);
bot._client.write = function (name, params) {
  const res = origWrite(name, params);
  if (bot._client.state === 'play' && (name === 'position' || name === 'position_look' || name === 'look' || name === 'flying')) {
    origWrite('tick_end', {});
  }
  return res;
};
bot.once('spawn', async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  bot.chat('/gamemode creative');
  await sleep(400);
  bot.chat('/tp bench_000 15.5 120 16.5');
  await sleep(500);
  bot.entity.position.x = 16.5;
  await sleep(500);
  bot.chat('/tp bench_000 559.5 120 16.5');
  await sleep(500);
  bot.entity.position.x = 560.5;
  await sleep(700);
  console.log('BOT_PAPER_DONE pos=' + JSON.stringify(bot.entity.position));
  bot.quit();
  process.exit(0);
});
setTimeout(() => { console.error('BOT_PAPER_TIMEOUT'); process.exit(1); }, 25000);
"""
    res_bot_p = subprocess.run(["node", "-e", bot_paper_js], cwd=str(repo_root), capture_output=True, text=True, timeout=30)
    print("[Bot Paper Seam Cross]", res_bot_p.stdout.strip())
    assert "BOT_PAPER_DONE" in res_bot_p.stdout, f"Bot Paper failed: {res_bot_p.stdout} {res_bot_p.stderr}"

    folia_offset = logs["folia"].stat().st_size if logs["folia"].is_file() else 0
    folia_after_ho = poll_status_line("folia", folia_offset, timeout=10.0)
    print("[Status folia after handoff]", folia_after_ho)
    assert "bench_000:shard-paper->shard-folia" in folia_after_ho, f"Expected handoff on Folia: {folia_after_ho}"

    bot_folia_js = """
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25636, username: 'bench_000', version: '26.1' });
bot.once('spawn', async () => {
  await new Promise((r) => setTimeout(r, 1500));
  console.log('BOT_FOLIA_SPAWNED_AT=' + JSON.stringify(bot.entity.position));
  bot.quit();
  process.exit(0);
});
setTimeout(() => { console.error('BOT_FOLIA_TIMEOUT'); process.exit(1); }, 20000);
"""
    res_bot_f = subprocess.run(["node", "-e", bot_folia_js], cwd=str(repo_root), capture_output=True, text=True, timeout=25)
    print("[Bot Folia Arrival]", res_bot_f.stdout.strip())
    assert '"x":560.5' in res_bot_f.stdout, f"Expected arrival teleport at x=560.5 on Folia: {res_bot_f.stdout}"

    rcons["fabric"].cmd("setworldspawn 16 120 528")
    rcons["fabric"].cmd("forceload add 0 512 31 543")
    rcons["fabric"].cmd("forceload add 544 512 575 543")
    time.sleep(1.5)

    # 2B. Live Mineflayer bot crosses 2-chunk hysteresis seam on Fabric (r.0.1 -> r.1.1 owned by NeoForge)
    bot_fabric_js = """
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25637, username: 'rtp_bot', version: '26.1' });
const origWrite = bot._client.write.bind(bot._client);
bot._client.write = function (name, params) {
  const res = origWrite(name, params);
  if (bot._client.state === 'play' && (name === 'position' || name === 'position_look' || name === 'look' || name === 'flying')) {
    origWrite('tick_end', {});
  }
  return res;
};
bot.once('spawn', async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  bot.chat('/gamemode creative');
  await sleep(400);
  bot.chat('/tp rtp_bot 15.5 120 528.5');
  await sleep(600);
  bot.entity.position.x = 16.5;
  await sleep(600);
  bot.chat('/tp rtp_bot 559.5 120 528.5');
  await sleep(600);
  bot.entity.position.x = 560.5;
  await sleep(800);
  console.log('BOT_FABRIC_DONE pos=' + JSON.stringify(bot.entity.position));
  bot.quit();
  process.exit(0);
});
setTimeout(() => { console.error('BOT_FABRIC_TIMEOUT'); process.exit(1); }, 30000);
"""
    res_bot_fab = subprocess.run(["node", "-e", bot_fabric_js], cwd=str(repo_root), capture_output=True, text=True, timeout=30)
    print("[Bot Fabric Seam Cross]", res_bot_fab.stdout.strip())
    assert "BOT_FABRIC_DONE" in res_bot_fab.stdout, f"Bot Fabric failed: {res_bot_fab.stdout} {res_bot_fab.stderr}"

    neoforge_offset = logs["neoforge"].stat().st_size if logs["neoforge"].is_file() else 0
    neoforge_after_ho = poll_status_line("neoforge", neoforge_offset, timeout=10.0)
    print("[Status neoforge after handoff]", neoforge_after_ho)
    assert "rtp_bot:shard-fabric->shard-neoforge" in neoforge_after_ho, f"Expected handoff on NeoForge: {neoforge_after_ho}"

    bot_neoforge_js = """
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25638, username: 'rtp_bot', version: '26.1' });
bot.once('spawn', async () => {
  await new Promise((r) => setTimeout(r, 1500));
  console.log('BOT_NEOFORGE_SPAWNED_AT=' + JSON.stringify(bot.entity.position));
  bot.quit();
  process.exit(0);
});
setTimeout(() => { console.error('BOT_NEOFORGE_TIMEOUT'); process.exit(1); }, 20000);
"""
    res_bot_neo = subprocess.run(["node", "-e", bot_neoforge_js], cwd=str(repo_root), capture_output=True, text=True, timeout=25)
    print("[Bot NeoForge Arrival]", res_bot_neo.stdout.strip())
    assert ('"x":559.5' in res_bot_neo.stdout or '"x":560.5' in res_bot_neo.stdout) and '"z":528.5' in res_bot_neo.stdout, (
        f"Expected arrival teleport at x=559.5..560.5, z=528.5 on NeoForge: {res_bot_neo.stdout}"
    )

    # 3. Check all 4 platform logs (logs/latest.log) for linkage/thread errors
    for node in NODES:
        slug = node["slug"]
        latest_log = work_dir / f"shard-{slug}" / "logs" / "latest.log"
        target_log = latest_log if latest_log.is_file() else logs[slug]
        txt = target_log.read_text(encoding="utf-8", errors="replace")
        hits = LINKAGE_RE.findall(txt)
        assert not hits, f"Linkage/thread errors found in {slug} log ({target_log}): {hits}"

    print("\n=== ALL 4 PLATFORMS (PAPER, FOLIA, FABRIC, NEOFORGE) PASSED LEAFSHARD DEVSTACK ACCEPTANCE ===")
finally:
    for slug, rc in rcons.items():
        try:
            rc.cmd("stop")
        except Exception:
            pass
        rc.close()
    for slug, proc in procs.items():
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
