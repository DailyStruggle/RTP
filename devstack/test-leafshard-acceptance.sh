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
import json
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
        msa.write_ops_and_server_properties(
            srv_dir=srv_dir,
            game_port=node["game_port"],
            rcon_port=node["rcon_port"],
            rcon_password=RCON_PASS,
            seed="8675309",
            op_usernames=(
                "bench_000",
                "treegen_bot",
                "rtp_bot",
                "seam_actor",
                "seam_observer",
                "husksync_bot",
            ),
        )
        sys_props = [
            f"-Dleafshard.shardId={node['shard_id']}",
            f"-Dleafshard.localeGroup={node['locale']}",
            "-Dleafshard.peerHost=127.0.0.1",
            f"-Dleafshard.peerPort={node['peer_port']}",
            f"-Dleafshard.peers={peers_csv}",
            f"-Dleafshard.regions=world:{node['rx']}:{node['rz']}",
            "-Dleafshard.logStatusTelemetry=true",
            "-Dleafshard.syncOnServerSwitch=true",
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
        procs[slug] = subprocess.Popen(
            launch_cmd,
            cwd=str(srv_dir),
            stdin=subprocess.PIPE,
            stdout=out_f,
            stderr=subprocess.STDOUT,
        )

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

    def run_bidirectional_two_bot_seam_suite(left_slug: str, left_port: int, right_slug: str, right_port: int, seam_z: int, isolated_slugs: list[str]):
        z_min = (seam_z >> 4) * 16 - 16
        z_max = z_min + 47
        rcons[left_slug].cmd(f"forceload add 496 {z_min} 543 {z_max}")
        rcons[right_slug].cmd(f"forceload add 496 {z_min} 543 {z_max}")
        time.sleep(1.5)
        rcons[left_slug].cmd(f"fill 506 119 {seam_z - 2} 518 119 {seam_z + 2} minecraft:bedrock")
        rcons[left_slug].cmd(f"fill 506 120 {seam_z - 2} 518 125 {seam_z + 2} minecraft:air")
        rcons[right_slug].cmd(f"fill 506 119 {seam_z - 2} 518 119 {seam_z + 2} minecraft:bedrock")
        rcons[right_slug].cmd(f"fill 506 120 {seam_z - 2} 518 125 {seam_z + 2} minecraft:air")
        time.sleep(1.0)

        actor_name = "seam_actor"
        observer_name = "seam_observer"
        seam_z_f = seam_z + 0.5
        pre_offsets = {s: (logs[s].stat().st_size if logs[s].is_file() else 0) for s in ["paper", "folia", "fabric", "neoforge"]}

        two_bot_seam_js = f"""
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const Vec3 = require('./devstack/clients/node_modules/vec3').Vec3;

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

function attachBlockChangeListener(bot) {{
  const Chunk = require('./devstack/clients/node_modules/prismarine-chunk')(bot.registry);
  bot._client.on('block_change', (pkt) => {{
    const cx = pkt.location.x >> 4;
    const cz = pkt.location.z >> 4;
    if (!bot.world.getColumn(cx, cz)) {{
      bot.world.setColumn(cx, cz, new Chunk({{ minY: -64, worldHeight: 384 }}));
    }}
    bot.world.setBlockStateId(new Vec3(pkt.location.x, pkt.location.y, pkt.location.z), pkt.type);
  }});
}}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {{
  const botA = mineflayer.createBot({{ host: '127.0.0.1', port: {left_port}, username: '{actor_name}', version: '26.1', viewDistance: 4 }});
  const botB = mineflayer.createBot({{ host: '127.0.0.1', port: {right_port}, username: '{observer_name}', version: '26.1', viewDistance: 4 }});
  attachTickEnd(botA);
  attachTickEnd(botB);

  let swingSeenByB = false;
  let swingSeenByA = false;
  let crouchSeenByB = false;
  let crouchSeenByA = false;
  let hurtAnimSeenOnB = false;
  let hurtAnimSeenOnA = false;
  let itemEntitySeenByB = false;
  let itemEntitySeenByA = false;
  let takeItemPacketSeenOnB = false;
  let takeItemPacketSeenOnA = false;

  botB.on('entitySwingArm', (ent) => {{
    if (ent && (ent.username === '{actor_name}' || ent.type === 'player')) swingSeenByB = true;
  }});
  botA.on('entitySwingArm', (ent) => {{
    if (ent && (ent.username === '{observer_name}' || ent.type === 'player')) swingSeenByA = true;
  }});
  botB.on('entityCrouch', (ent) => {{
    if (ent && (ent.username === '{actor_name}' || ent.type === 'player')) crouchSeenByB = true;
  }});
  botA.on('entityCrouch', (ent) => {{
    if (ent && (ent.username === '{observer_name}' || ent.type === 'player')) crouchSeenByA = true;
  }});
  botB._client.on('animation', (pkt) => {{
    if (pkt && pkt.animation === 0) swingSeenByB = true;
  }});
  botA._client.on('animation', (pkt) => {{
    if (pkt && pkt.animation === 0) swingSeenByA = true;
  }});
  botB._client.on('hurt_animation', () => {{
    hurtAnimSeenOnB = true;
  }});
  botA._client.on('hurt_animation', () => {{
    hurtAnimSeenOnA = true;
  }});
  botB._client.on('collect', () => {{
    takeItemPacketSeenOnB = true;
  }});
  botA._client.on('collect', () => {{
    takeItemPacketSeenOnA = true;
  }});

  await Promise.all([
    new Promise((r) => botA.once('spawn', r)),
    new Promise((r) => botB.once('spawn', r)),
  ]);
  await sleep(600);
  attachBlockChangeListener(botA);
  attachBlockChangeListener(botB);

  botA.chat('/gamemode survival');
  botB.chat('/gamemode survival');
  botA.chat('/clear {actor_name}');
  botB.chat('/clear {observer_name}');
  await sleep(250);
  botA.chat('/give {actor_name} minecraft:diamond_block 64');
  botB.chat('/give {observer_name} minecraft:diamond_block 64');
  await sleep(150);
  botA.chat('/give {actor_name} minecraft:diamond_sword 1');
  botB.chat('/give {observer_name} minecraft:diamond_sword 1');
  await sleep(150);
  botA.chat('/give {actor_name} minecraft:emerald 5');
  botB.chat('/give {observer_name} minecraft:diamond 5');
  await sleep(150);
  botA.chat('/tp {actor_name} 508.5 120 {seam_z_f} -90 0');
  botB.chat('/tp {observer_name} 513.5 120 {seam_z_f} 90 0');
  await sleep(300);
  botA.chat('/fill 506 119 {seam_z - 2} 518 119 {seam_z + 2} minecraft:bedrock');
  botB.chat('/fill 506 119 {seam_z - 2} 518 119 {seam_z + 2} minecraft:bedrock');
  botA.chat('/tp {actor_name} 508.5 120 {seam_z_f} -90 0');
  botB.chat('/tp {observer_name} 513.5 120 {seam_z_f} 90 0');

  const prepDeadline = Date.now() + 8000;
  while (Date.now() < prepDeadline) {{
    const aReady = Math.abs(botA.entity.position.x - 508.5) < 2.0 && botA.inventory.slots[36] && botA.inventory.slots[36].name === 'diamond_block';
    const bReady = Math.abs(botB.entity.position.x - 513.5) < 2.0 && botB.inventory.slots[36] && botB.inventory.slots[36].name === 'diamond_block';
    if (aReady && bReady) break;
    await sleep(100);
  }}

  // 1. Mutual synthetic ServerPlayer visibility across x=512
  let actorSeenByB = null;
  let observerSeenByA = null;
  let entBRef = null;
  let entARef = null;
  const visDeadline = Date.now() + 8000;
  while (Date.now() < visDeadline) {{
    botA.entity.position.x = 508.5 + ((Date.now() % 200) / 1000.0);
    botB.entity.position.x = 513.5 + ((Date.now() % 200) / 1000.0);
    const pA = botB.players['{actor_name}'];
    const pB = botA.players['{observer_name}'];
    const entA = Object.values(botB.entities).find((e) => e && e.id !== botB.entity.id && (e.username === '{actor_name}' || (pA && pA.entity && e.id === pA.entity.id) || (e.type === 'player' && (e.id & 0x40000000) !== 0)));
    const entB = Object.values(botA.entities).find((e) => e && e.id !== botA.entity.id && (e.username === '{observer_name}' || (pB && pB.entity && e.id === pB.entity.id) || (e.type === 'player' && (e.id & 0x40000000) !== 0)));
    if (pA && entA) {{
      actorSeenByB = {{ username: pA.username, id: entA.id, pos: entA.position }};
      entARef = entA;
    }}
    if (pB && entB) {{
      observerSeenByA = {{ username: pB.username, id: entB.id, pos: entB.position }};
      entBRef = entB;
    }}
    if (actorSeenByB && observerSeenByA) break;
    await sleep(150);
  }}

  // 2. Bidirectional sneak pose & arm swing across the seam
  botA.setControlState('sneak', true);
  botB.setControlState('sneak', true);
  botA.swingArm('right');
  botB.swingArm('right');
  const actionDeadline = Date.now() + 4000;
  while (Date.now() < actionDeadline) {{
    const entA = Object.values(botB.entities).find((e) => e && e.id !== botB.entity.id && (e.username === '{actor_name}' || (actorSeenByB && e.id === actorSeenByB.id)));
    const entB = Object.values(botA.entities).find((e) => e && e.id !== botA.entity.id && (e.username === '{observer_name}' || (observerSeenByA && e.id === observerSeenByA.id)));
    if (entA && entA.crouching) crouchSeenByB = true;
    if (entB && entB.crouching) crouchSeenByA = true;
    if (swingSeenByB && swingSeenByA && crouchSeenByB && crouchSeenByA) break;
    botA.swingArm('right');
    botB.swingArm('right');
    await sleep(150);
  }}
  botA.setControlState('sneak', false);
  botB.setControlState('sneak', false);

  // 3A. Left bot places & breaks a diamond_block at (510, 120, {seam_z}) observed by Right bot
  botA.chat('/gamemode creative');
  botB.chat('/gamemode creative');
  await sleep(250);
  botA._client.write('held_item_slot', {{ slotId: 0 }});
  await sleep(150);
  botA._client.write('block_place', {{
    hand: 0,
    location: {{ x: 510, y: 119, z: {seam_z} }},
    direction: 1,
    cursorX: 0.5,
    cursorY: 1.0,
    cursorZ: 0.5,
    insideBlock: false,
    worldBorderHit: false,
    sequence: 1,
  }});

  let blockPlacedSeenByB = null;
  const placeDeadlineB = Date.now() + 6000;
  while (Date.now() < placeDeadlineB) {{
    const blk = botB.blockAt(new Vec3(510, 120, {seam_z}));
    if (blk && blk.name === 'diamond_block') {{
      blockPlacedSeenByB = blk.name;
      break;
    }}
    await sleep(150);
  }}

  botA._client.write('block_dig', {{
    status: 0,
    location: {{ x: 510, y: 120, z: {seam_z} }},
    face: 1,
    sequence: 2,
  }});

  let blockBrokenSeenByB = null;
  const breakDeadlineB = Date.now() + 6000;
  while (Date.now() < breakDeadlineB) {{
    const blk = botB.blockAt(new Vec3(510, 120, {seam_z}));
    if (blk && blk.name === 'air') {{
      blockBrokenSeenByB = blk.name;
      break;
    }}
    await sleep(150);
  }}

  // 3B. Right bot places & breaks a diamond_block at (512, 120, {seam_z}) observed by Left bot
  botB._client.write('held_item_slot', {{ slotId: 0 }});
  await sleep(150);
  botB._client.write('block_place', {{
    hand: 0,
    location: {{ x: 512, y: 119, z: {seam_z} }},
    direction: 1,
    cursorX: 0.5,
    cursorY: 1.0,
    cursorZ: 0.5,
    insideBlock: false,
    worldBorderHit: false,
    sequence: 1,
  }});

  let blockPlacedSeenByA = null;
  const placeDeadlineA = Date.now() + 6000;
  while (Date.now() < placeDeadlineA) {{
    const blk = botA.blockAt(new Vec3(512, 120, {seam_z}));
    if (blk && blk.name === 'diamond_block') {{
      blockPlacedSeenByA = blk.name;
      break;
    }}
    await sleep(150);
  }}

  botB._client.write('block_dig', {{
    status: 0,
    location: {{ x: 512, y: 120, z: {seam_z} }},
    face: 1,
    sequence: 2,
  }});

  let blockBrokenSeenByA = null;
  const breakDeadlineA = Date.now() + 6000;
  while (Date.now() < breakDeadlineA) {{
    const blk = botA.blockAt(new Vec3(512, 120, {seam_z}));
    if (blk && blk.name === 'air') {{
      blockBrokenSeenByA = blk.name;
      break;
    }}
    await sleep(150);
  }}

  // 4. Bidirectional Cross-Seam PvP Combat (A attacks B, and B attacks A across x=512)
  botA.chat('/gamemode survival');
  botB.chat('/gamemode survival');
  botA.chat('/tp {actor_name} 510.5 120 {seam_z_f} -90 0');
  botB.chat('/tp {observer_name} 512.5 120 {seam_z_f} 90 0');
  await sleep(450);
  botA.entity.position.x = 510.55;
  botB.entity.position.x = 512.45;
  await sleep(300);

  botA._client.write('held_item_slot', {{ slotId: 1 }});
  botB._client.write('held_item_slot', {{ slotId: 1 }});
  await sleep(150);
  const healthBeforeCombatB = botB.health || 20;
  let healthAfterCombatB = healthBeforeCombatB;
  const combatDeadlineB = Date.now() + 6000;
  while (Date.now() < combatDeadlineB) {{
    const targetShadowB = Object.values(botA.entities).find((e) => e && e.id !== botA.entity.id && (e.id & 0x40000000) !== 0 && e.type === 'player');
    if (targetShadowB) {{
      botA.attack(targetShadowB);
    }} else if (entBRef && entBRef.id !== botA.entity.id) {{
      botA.attack(entBRef);
    }}
    await sleep(250);
    if (botB.health < healthBeforeCombatB) {{
      healthAfterCombatB = botB.health;
      break;
    }}
  }}

  // Reposition after knockback so B can attack A across x=512
  botA.chat('/tp {actor_name} 510.5 120 {seam_z_f} -90 0');
  botB.chat('/tp {observer_name} 512.5 120 {seam_z_f} 90 0');
  await sleep(400);
  botA.entity.position.x = 510.55;
  botB.entity.position.x = 512.45;
  await sleep(250);

  const healthBeforeCombatA = botA.health || 20;
  let healthAfterCombatA = healthBeforeCombatA;
  const combatDeadlineA = Date.now() + 6000;
  while (Date.now() < combatDeadlineA) {{
    const targetShadowA = Object.values(botB.entities).find((e) => e && e.id !== botB.entity.id && (e.id & 0x40000000) !== 0 && e.type === 'player');
    if (targetShadowA) {{
      botB.attack(targetShadowA);
    }} else if (entARef && entARef.id !== botB.entity.id) {{
      botB.attack(entARef);
    }}
    await sleep(250);
    if (botA.health < healthBeforeCombatA) {{
      healthAfterCombatA = botA.health;
      break;
    }}
  }}

  // 5A. Left bot drops 5 emeralds at 511.2 picked up by Right bot at 512.2
  botA.chat('/tp {actor_name} 511.2 120 {seam_z_f} -90 90');
  botB.chat('/tp {observer_name} 512.2 120 {seam_z_f} 90 0');
  await sleep(450);
  botA._client.write('held_item_slot', {{ slotId: 2 }});
  await sleep(150);
  botA._client.write('block_dig', {{
    status: 3,
    location: {{ x: 0, y: 0, z: 0 }},
    face: 0,
    sequence: 3,
  }});

  let pickedUpEmeraldsOnB = 0;
  const itemDeadlineB = Date.now() + 8000;
  while (Date.now() < itemDeadlineB) {{
    const remoteItemEnt = Object.values(botB.entities).find((e) => e && e.id !== botB.entity.id && (e.name === 'item' || e.type === 'object'));
    if (remoteItemEnt) itemEntitySeenByB = true;
    botB.entity.position.x = 512.1 + ((Date.now() % 150) / 1000.0);
    const bEmeralds = botB.inventory.items().filter((i) => i.name === 'emerald').reduce((acc, i) => acc + i.count, 0);
    if (bEmeralds >= 5) {{
      pickedUpEmeraldsOnB = bEmeralds;
      break;
    }}
    await sleep(150);
  }}

  // 5B. Right bot drops 5 diamonds at 512.5 picked up by Left bot at 511.5
  botA.chat('/tp {actor_name} 511.5 120 {seam_z_f} -90 0');
  botB.chat('/tp {observer_name} 512.5 120 {seam_z_f} 90 90');
  await sleep(450);
  botB._client.write('held_item_slot', {{ slotId: 2 }});
  await sleep(150);
  botB._client.write('block_dig', {{
    status: 3,
    location: {{ x: 0, y: 0, z: 0 }},
    face: 0,
    sequence: 4,
  }});

  let pickedUpDiamondsOnA = 0;
  const itemDeadlineA = Date.now() + 8000;
  while (Date.now() < itemDeadlineA) {{
    const remoteItemEnt = Object.values(botA.entities).find((e) => e && e.id !== botA.entity.id && (e.name === 'item' || e.type === 'object'));
    if (remoteItemEnt) itemEntitySeenByA = true;
    botA.entity.position.x = 511.6 + ((Date.now() % 150) / 1000.0);
    const aDiamonds = botA.inventory.items().filter((i) => i.name === 'diamond').reduce((acc, i) => acc + i.count, 0);
    if (aDiamonds >= 5) {{
      pickedUpDiamondsOnA = aDiamonds;
      break;
    }}
    await sleep(150);
  }}

  console.log('TWO_BOT_SEAM_RESULT=' + JSON.stringify({{
    pair: '{left_slug}<->{right_slug}',
    actorSeenByB,
    observerSeenByA,
    swingSeenByB,
    swingSeenByA,
    crouchSeenByB,
    crouchSeenByA,
    blockPlacedSeenByB,
    blockBrokenSeenByB,
    blockPlacedSeenByA,
    blockBrokenSeenByA,
    healthBeforeCombatB,
    healthAfterCombatB,
    hurtAnimSeenOnB,
    healthBeforeCombatA,
    healthAfterCombatA,
    hurtAnimSeenOnA,
    itemEntitySeenByB,
    takeItemPacketSeenOnB,
    pickedUpEmeraldsOnB,
    itemEntitySeenByA,
    takeItemPacketSeenOnA,
    pickedUpDiamondsOnA,
  }}));
  botA.quit();
  botB.quit();
  await sleep(500);
  process.exit(0);
}})();
setTimeout(() => {{ console.error('TWO_BOT_SEAM_TIMEOUT'); process.exit(1); }}, 55000);
"""
        res_two_bot = subprocess.run(["node", "-e", two_bot_seam_js], cwd=str(repo_root), capture_output=True, text=True, timeout=60)
        print(f"[2-Bot Seam {left_slug} <-> {right_slug}]", res_two_bot.stdout.strip())
        assert "TWO_BOT_SEAM_RESULT=" in res_two_bot.stdout, f"2-bot seam script ({left_slug}<->{right_slug}) failed: {res_two_bot.stdout} {res_two_bot.stderr}"
        seam_json_line = [l for l in res_two_bot.stdout.splitlines() if l.startswith("TWO_BOT_SEAM_RESULT=")][-1]
        import json
        seam_res = json.loads(seam_json_line[len("TWO_BOT_SEAM_RESULT="):])
        assert seam_res["actorSeenByB"] is not None, f"{right_slug} did not see {left_slug} player: {seam_res}"
        assert seam_res["observerSeenByA"] is not None, f"{left_slug} did not see {right_slug} player: {seam_res}"
        assert seam_res["swingSeenByB"] is True and seam_res["swingSeenByA"] is True, f"Expected bidirectional arm-swing across seam: {seam_res}"
        assert seam_res["crouchSeenByB"] is True and seam_res["crouchSeenByA"] is True, f"Expected bidirectional sneak pose across seam: {seam_res}"
        assert seam_res["blockPlacedSeenByB"] == "diamond_block" and seam_res["blockBrokenSeenByB"] == "air", f"Expected {left_slug}->{right_slug} block place/break sync: {seam_res}"
        assert seam_res["blockPlacedSeenByA"] == "diamond_block" and seam_res["blockBrokenSeenByA"] == "air", f"Expected {right_slug}->{left_slug} block place/break sync: {seam_res}"
        assert seam_res["healthAfterCombatB"] < seam_res["healthBeforeCombatB"], f"Expected {left_slug}->{right_slug} PvP attack to reduce victim health: {seam_res}"
        assert seam_res["healthAfterCombatA"] < seam_res["healthBeforeCombatA"], f"Expected {right_slug}->{left_slug} PvP attack to reduce victim health: {seam_res}"
        assert seam_res["pickedUpEmeraldsOnB"] == 5, f"Expected {right_slug} to pick up 5 emeralds dropped on {left_slug}: {seam_res}"
        assert seam_res["pickedUpDiamondsOnA"] == 5, f"Expected {left_slug} to pick up 5 diamonds dropped on {right_slug}: {seam_res}"

        expected_confirm_events = (
            "event=NETTY_BRIDGE_ATTACHED",
            "event=SHADOW_SPAWN",
            "event=SHADOW_SWING",
            "event=VOXEL_COMMIT",
            "event=NETTY_COMBAT_INTERCEPT",
            "event=COMBAT_HIT_APPLIED",
            "event=HALO_ITEM_PROJECTED",
            "event=HALO_ITEM_CAS_CLAIMED",
            "event=HALO_ITEM_GRANTED",
        )
        for active_slug in (left_slug, right_slug):
            active_slice = logs[active_slug].read_text(encoding="utf-8", errors="replace")[pre_offsets[active_slug]:]
            confirm_lines = [l.strip() for l in active_slice.splitlines() if "[LeafShard-Confirm]" in l]
            print(f"[Confirmations on {active_slug} during {left_slug}<->{right_slug} ({len(confirm_lines)} lines)]:")
            for cl in confirm_lines:
                print(f"  {cl}")
            for ev in expected_confirm_events:
                assert any(ev in cl for cl in confirm_lines), (
                    f"Missing expected confirmation '{ev}' on {active_slug} during {left_slug}<->{right_slug}; saw: {confirm_lines}"
                )

        for iso_slug in isolated_slugs:
            iso_slice = logs[iso_slug].read_text(encoding="utf-8", errors="replace")[pre_offsets[iso_slug]:]
            iso_confirms = [l.strip() for l in iso_slice.splitlines() if "[LeafShard-Confirm]" in l]
            assert not iso_confirms, (
                f"ERROR: Non-proximate server {iso_slug} emitted confirmation logs during {left_slug}<->{right_slug}: {iso_confirms}"
            )
            iso_status = poll_status_line(iso_slug, pre_offsets[iso_slug], timeout=5.0)
            print(f"[Isolation Verified on {iso_slug} during {left_slug}<->{right_slug}: 0 confirmations] {iso_status}")
            assert "shadows=0" in iso_status, f"Distant shard-{iso_slug} should have shadows=0: {iso_status}"

    # Configure interior world spawns inside each shard's authoritative region (>250 blocks from any border)
    rcons["paper"].cmd("setworldspawn 256 120 256")
    rcons["folia"].cmd("setworldspawn 768 120 256")
    rcons["fabric"].cmd("setworldspawn 256 120 768")
    rcons["neoforge"].cmd("setworldspawn 768 120 768")
    time.sleep(1.0)

    # 2C-1. Bidirectional 2-Bot Seam Equivalence across Paper (r.0.0) <-> Folia (r.1.0) at z=256 (isolated from Fabric & NeoForge)
    run_bidirectional_two_bot_seam_suite("paper", 25635, "folia", 25636, 256, ["fabric", "neoforge"])

    # 2C-2. Bidirectional 2-Bot Seam Equivalence across Fabric (r.0.1) <-> NeoForge (r.1.1) at z=768 (isolated from Paper & Folia)
    run_bidirectional_two_bot_seam_suite("fabric", 25637, "neoforge", 25638, 768, ["paper", "folia"])

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
    assert ('"x":559.5' in res_bot_f.stdout or '"x":560.5' in res_bot_f.stdout), (
        f"Expected arrival teleport at x=559.5..560.5 on Folia: {res_bot_f.stdout}"
    )

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

    # 3. Check all 4 platform logs (logs/latest.log) for linkage/thread errors and handoff confirmation
    for handoff_slug in ("folia", "neoforge"):
        ho_txt = logs[handoff_slug].read_text(encoding="utf-8", errors="replace")
        ho_confirms = [l.strip() for l in ho_txt.splitlines() if "event=HANDOFF_PROMOTED" in l]
        print(f"[Handoff Confirmation on {handoff_slug}]:", ho_confirms)
        assert ho_confirms, f"Expected event=HANDOFF_PROMOTED on {handoff_slug}"

    for node in NODES:
        slug = node["slug"]
        latest_log = work_dir / f"shard-{slug}" / "logs" / "latest.log"
        target_log = latest_log if latest_log.is_file() else logs[slug]
        txt = target_log.read_text(encoding="utf-8", errors="replace")
        hits = LINKAGE_RE.findall(txt)
        assert not hits, f"Linkage/thread errors found in {slug} log ({target_log}): {hits}"
        warn_hits = [l.strip() for l in txt.splitlines() if "Failed sending" in l or "Failed creating" in l or "Failed attaching" in l or "Failed despawning" in l]
        assert not warn_hits, f"Unexpected platform warning(s) found in {slug} log: {warn_hits}"

    # Stop the 4 sharded nodes before booting the 2 independent HuskSync world-remap servers
    for slug, rc in list(rcons.items()):
        try:
            rc.cmd("stop")
        except Exception:
            pass
        rc.close()
    rcons.clear()
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
    procs.clear()

    # 4. Async Independent Server Switch ("HuskSync-Style" world_survival -> world_resource, keepDestinationLocation=true, REQ-SHARD-013..015)
    print("[leafshard-devstack] Provisioning independent HuskSync world-remap servers (world_survival -> world_resource)...")
    ASYNC_NODES = [
        {
            "slug": "survival",
            "shard_id": "shard-survival",
            "peer_id": "shard-resource",
            "level_name": "world_survival",
            "game_port": 25635,
            "rcon_port": 25645,
            "peer_port": 25655,
            "remote_peer_port": 25656,
        },
        {
            "slug": "resource",
            "shard_id": "shard-resource",
            "peer_id": "shard-survival",
            "level_name": "world_resource",
            "game_port": 25636,
            "rcon_port": 25646,
            "peer_port": 25656,
            "remote_peer_port": 25655,
        },
    ]
    for anode in ASYNC_NODES:
        slug = anode["slug"]
        srv_dir = work_dir / f"async-{slug}"
        _, base_cmd = msa.provision_server(
            platform="paper",
            srv_dir=srv_dir,
            game_port=anode["game_port"],
            rcon_port=anode["rcon_port"],
            rcon_password=RCON_PASS,
            seed="8675309",
            jars=[anvil_jar, shard_jar],
            heap="2G",
            local_cache=shared_cache,
            clean=True,
        )
        msa.write_ops_and_server_properties(
            srv_dir=srv_dir,
            game_port=anode["game_port"],
            rcon_port=anode["rcon_port"],
            rcon_password=RCON_PASS,
            seed="8675309",
            op_usernames=("husksync_bot", "bench_000"),
            extra_properties={"level-name": anode["level_name"]},
        )
        sys_props = [
            f"-Dleafshard.shardId={anode['shard_id']}",
            "-Dleafshard.localeGroup=na-east",
            "-Dleafshard.peerHost=127.0.0.1",
            f"-Dleafshard.peerPort={anode['peer_port']}",
            f"-Dleafshard.peers={anode['peer_id']}=127.0.0.1:{anode['remote_peer_port']}",
            f"-Dleafshard.regions={anode['level_name']}:0:0",
            "-Dleafshard.syncOnServerSwitch=true",
            "-Dleafshard.logStatusTelemetry=true",
        ]
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

    for anode in ASYNC_NODES:
        slug = anode["slug"]
        rc = msa.RconClient("127.0.0.1", anode["rcon_port"], RCON_PASS)
        rcons[slug] = rc
        boot_s = msa.wait_for_rcon(rc, procs[slug], timeout=120.0)
        print(f"[Boot Async] {slug} ({anode['shard_id']}, level={anode['level_name']}) RCON ready in {boot_s}s")

    time.sleep(2.0)
    rcons["survival"].cmd("gamerule natural_health_regeneration false")
    rcons["survival"].cmd("gamerule naturalRegeneration false")
    rcons["survival"].cmd("forceload add 0 0 31 31")
    rcons["survival"].cmd("fill 12 119 12 20 119 20 minecraft:bedrock")
    rcons["survival"].cmd("fill 12 120 12 20 124 20 minecraft:air")
    rcons["survival"].cmd("setworldspawn 16 120 16")

    rcons["resource"].cmd("gamerule natural_health_regeneration false")
    rcons["resource"].cmd("gamerule naturalRegeneration false")
    rcons["resource"].cmd("forceload add 48 -48 79 -17")
    rcons["resource"].cmd("fill 60 119 -36 68 119 -28 minecraft:bedrock")
    rcons["resource"].cmd("fill 60 120 -36 68 124 -28 minecraft:air")
    rcons["resource"].cmd("setworldspawn 64 120 -32")
    time.sleep(1.5)

    husk_uuid = msa.offline_player_uuid("husksync_bot")
    adv_src = work_dir / "async-survival" / "world_survival" / "advancements" / f"{husk_uuid}.json"
    adv_src.parent.mkdir(parents=True, exist_ok=True)
    adv_payload = '{"minecraft:story/mine_diamond":{"criteria":{"get_diamond":"2026-11-10 12:00:00 +0000"},"done":true}}'
    adv_src.write_text(adv_payload, encoding="utf-8")

    bot_survival_js = """
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25635, username: 'husksync_bot', version: '26.1' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
bot.once('spawn', async () => {
  await sleep(600);
  bot.chat('/tp husksync_bot 16.5 120 16.5');
  await sleep(400);
  bot.chat('/gamemode survival');
  await sleep(300);
  bot.chat('/clear husksync_bot');
  await sleep(300);
  bot.chat('/give husksync_bot minecraft:diamond_sword 1');
  bot.chat('/give husksync_bot minecraft:golden_apple 12');
  bot.chat('/xp set husksync_bot 33 levels');
  bot.chat('/damage husksync_bot 6');
  await sleep(800);
  console.log('HUSKSYNC_SURVIVAL_STATE=' + JSON.stringify({
    pos: bot.entity.position,
    health: bot.health,
    level: bot.experience.level,
    items: bot.inventory.items().map((i) => ({ name: i.name, count: i.count })),
  }));
  bot.quit();
  await sleep(500);
  process.exit(0);
});
setTimeout(() => { console.error('HUSKSYNC_SURVIVAL_TIMEOUT'); process.exit(1); }, 25000);
"""
    resource_offset_pre = logs["resource"].stat().st_size if logs["resource"].is_file() else 0
    res_husk_a = subprocess.run(["node", "-e", bot_survival_js], cwd=str(repo_root), capture_output=True, text=True, timeout=30)
    print("[HuskSync Survival Quit]", res_husk_a.stdout.strip())
    assert "HUSKSYNC_SURVIVAL_STATE=" in res_husk_a.stdout, f"Survival bot failed: {res_husk_a.stdout} {res_husk_a.stderr}"

    resource_after_quit = poll_status_line("resource", resource_offset_pre, timeout=10.0)
    print("[Status resource after survival quit]", resource_after_quit)
    adv_dst = work_dir / "async-resource" / "world_resource" / "advancements" / f"{husk_uuid}.json"
    deadline_adv = time.time() + 5.0
    while time.time() < deadline_adv and not adv_dst.is_file():
        time.sleep(0.2)
    assert adv_dst.is_file(), f"Expected remapped advancement file at {adv_dst}"
    assert "mine_diamond" in adv_dst.read_text(encoding="utf-8"), "Remapped advancement content mismatch"

    bot_resource_js = """
const mineflayer = require('./devstack/clients/node_modules/mineflayer');
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25636, username: 'husksync_bot', version: '26.1' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
bot.once('spawn', async () => {
  await sleep(1500);
  console.log('HUSKSYNC_RESOURCE_ARRIVAL=' + JSON.stringify({
    pos: bot.entity.position,
    health: bot.health,
    level: bot.experience.level,
    items: bot.inventory.items().map((i) => ({ name: i.name, count: i.count })),
  }));
  bot.quit();
  await sleep(400);
  process.exit(0);
});
setTimeout(() => { console.error('HUSKSYNC_RESOURCE_TIMEOUT'); process.exit(1); }, 25000);
"""
    res_husk_b = subprocess.run(["node", "-e", bot_resource_js], cwd=str(repo_root), capture_output=True, text=True, timeout=30)
    print("[HuskSync Resource Arrival]", res_husk_b.stdout.strip())
    assert "HUSKSYNC_RESOURCE_ARRIVAL=" in res_husk_b.stdout, f"Resource bot failed: {res_husk_b.stdout} {res_husk_b.stderr}"
    husk_b_line = [l for l in res_husk_b.stdout.splitlines() if l.startswith("HUSKSYNC_RESOURCE_ARRIVAL=")][-1]
    husk_b = json.loads(husk_b_line[len("HUSKSYNC_RESOURCE_ARRIVAL="):])
    assert abs(husk_b["pos"]["x"] - 64.0) < 16.0 and abs(husk_b["pos"]["z"] - (-32.0)) < 16.0, (
        f"Expected resource server destination spawn near (64, -32) (keepDestinationLocation=true), got: {husk_b['pos']}"
    )
    assert husk_b["level"] == 33, f"Expected synced XP level 33 on resource server, got: {husk_b}"
    assert round(husk_b["health"]) == 14, f"Expected synced health ~14 on resource server, got: {husk_b}"
    item_map = {it["name"]: it["count"] for it in husk_b["items"]}
    assert item_map.get("diamond_sword") == 1 and item_map.get("golden_apple") == 12, (
        f"Expected synced inventory (diamond_sword=1, golden_apple=12) on resource server, got: {item_map}"
    )

    res_txt_confirm = logs["resource"].read_text(encoding="utf-8", errors="replace")
    async_confirms = [l.strip() for l in res_txt_confirm.splitlines() if "event=ASYNC_STATE_RESTORED" in l]
    print("[Async Restore Confirmation on resource]:", async_confirms)
    assert async_confirms, f"Expected event=ASYNC_STATE_RESTORED on resource server"

    for anode in ASYNC_NODES:
        slug = anode["slug"]
        latest_log = work_dir / f"async-{slug}" / "logs" / "latest.log"
        target_log = latest_log if latest_log.is_file() else logs[slug]
        txt = target_log.read_text(encoding="utf-8", errors="replace")
        hits = LINKAGE_RE.findall(txt)
        assert not hits, f"Linkage/thread errors found in async-{slug} log ({target_log}): {hits}"

    print("\n=== ALL 4 PLATFORMS (PAPER, FOLIA, FABRIC, NEOFORGE) + 2-BOT SEAM + ASYNC HUSKSYNC PASSED LEAFSHARD DEVSTACK ACCEPTANCE ===")
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
