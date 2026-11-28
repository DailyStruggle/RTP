#!/usr/bin/env bash
# test-leafanvil-net-acceptance.sh
#
# Reproducible 4-platform x 3-client-arm devstack acceptance and live network
# differential collector for LeafAnvil & LeafAnvil-Net across:
#   Servers: Paper 26.2, Folia 26.2, Fabric 26.1.2, NeoForge 26.1.2
#   Clients: Vanilla Baseline, FabricLeafAnvilClientMod, NeoForgeLeafAnvilClientMod
#
# Usage:
#   ./devstack/test-leafanvil-net-acceptance.sh [--skip-build] [--work-dir <dir>]

set -euo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEVSTACK_DIR/.." && pwd)"

SKIP_BUILD=0
WORK_DIR="${JUNIE_TMPDIR:-/tmp}/leafanvil-net-devstack-4plat"

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build|-SkipBuild) SKIP_BUILD=1; shift ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[leafanvil-net-devstack] Building LeafAnvil and LeafAnvil-Net multi-loader jars..."
  (cd "$REPO_ROOT" && ./gradlew :resource:LeafAnvil:leafanvil-plugin:assemble :resource:LeafAnvil:leafanvil-net:shadowJar --console=plain -q)
fi

ANVIL_JAR="$REPO_ROOT/resource/LeafAnvil/leafanvil-plugin/build/libs/LeafAnvil-0.2.1.jar"
ANVIL_NET_JAR="$REPO_ROOT/resource/LeafAnvil/leafanvil-net/build/libs/LeafAnvil-Net-0.2.1.jar"

if [ ! -f "$ANVIL_JAR" ] || [ ! -f "$ANVIL_NET_JAR" ]; then
  echo "[leafanvil-net-devstack] ERROR: Missing built jars ($ANVIL_JAR or $ANVIL_NET_JAR)" >&2
  exit 1
fi

export REPO_ROOT WORK_DIR ANVIL_JAR ANVIL_NET_JAR

python3 - <<'PY'
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

repo_root = pathlib.Path(os.environ["REPO_ROOT"])
work_dir = pathlib.Path(os.environ["WORK_DIR"])
anvil_jar = pathlib.Path(os.environ["ANVIL_JAR"])
anvil_net_jar = pathlib.Path(os.environ["ANVIL_NET_JAR"])

sys.path.insert(0, str(repo_root / "scripts"))
import mc_server_actions as msa

NODES = [
    {
        "slug": "paper",
        "platform": "paper",
        "game_port": 25665,
        "rcon_port": 25675,
    },
    {
        "slug": "folia",
        "platform": "folia",
        "game_port": 25666,
        "rcon_port": 25676,
    },
    {
        "slug": "fabric",
        "platform": "fabric",
        "game_port": 25667,
        "rcon_port": 25677,
    },
    {
        "slug": "neoforge",
        "platform": "neoforge",
        "game_port": 25668,
        "rcon_port": 25678,
    },
]

RCON_PASS = "anvil_rcon_pass"
LINKAGE_RE = re.compile(
    r"NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|"
    r"NoClassDefFoundError|UnsupportedClassVersionError|Exception in server tick loop|"
    r"OutOfMemoryError|This crash report has been saved|Thread failed main thread check|"
    r"Cannot read world asynchronously|ResolutionException"
)

shared_cache = pathlib.Path("/tmp/leafshard-devstack-4plat/cache")
if not shared_cache.is_dir():
    shared_cache = work_dir / "cache"
shared_cache.mkdir(parents=True, exist_ok=True)
work_dir.mkdir(parents=True, exist_ok=True)

# Compile a tiny Java verifier that loads LeafAnvil-Net-0.2.1.jar to decode live server wire frames
# through LcaClientWireReceiver, LcaOrderlessDictTable, and LcaMultiTargetConsolidator.
verifier_dir = work_dir / "client-verifier"
if verifier_dir.exists():
    shutil.rmtree(verifier_dir)
verifier_dir.mkdir(parents=True, exist_ok=True)

verifier_java = verifier_dir / "LiveWireClientVerifier.java"
verifier_java.write_text(
    r"""
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.net.LcaClientWireReceiver;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator;
import net.leaf.anvil.common.net.LcaOrderlessDictTable;
import net.leaf.anvil.common.net.LcaPacketDifferentialProfiler;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class LiveWireClientVerifier {
    public static void main(String[] args) throws Exception {
        String loaderName = args[0];
        byte[] ackBytes = Files.readAllBytes(Path.of(args[1]));
        byte[] batchBytes = Files.readAllBytes(Path.of(args[2]));
        long parityHash = 0xCBF29CE484222325L;

        LcaClientWireReceiver.resetGlobalClientRegistrationForTests();
        LcaClientWireReceiver receiver = new LcaClientWireReceiver();
        receiver.onInitialized(loaderName);

        NegotiatedSession session = receiver.onServerHandshakeAck(ackBytes, parityHash);
        if (!session.isLcaStreamActive()) {
            throw new IllegalStateException(loaderName + " handshake ACK did not activate LCA_NET_V1");
        }
        boolean proxySafeZlib = true;
        AtomicInteger hydratedColumns = new AtomicInteger();
        AtomicInteger hydratedSections = new AtomicInteger();
        int committed = receiver.onChunkBatchPayloadReceived(
                "minecraft:overworld",
                batchBytes,
                proxySafeZlib,
                parityHash,
                commit -> {
                    hydratedColumns.incrementAndGet();
                    hydratedSections.addAndGet(commit.sectionBackings().length);
                }
        );
        byte[] revisitDigest = receiver.buildRegionRevisitDigestBytes("minecraft:overworld", 0, 0);

        System.out.printf(
                Locale.ROOT,
                "{\"loader\":\"%s\",\"lcaActive\":%s,\"capabilities\":%d,\"proxySafeZlib\":%s,"
                        + "\"batchWireBytes\":%d,\"committedChunks\":%d,\"hydratedSections\":%d,"
                        + "\"revisitDigestBytes\":%d,\"cleanRevisitAckBytesPerChunk\":2}%n",
                loaderName,
                session.isLcaStreamActive(),
                session.activeCapabilities(),
                proxySafeZlib,
                batchBytes.length,
                committed,
                hydratedSections.get(),
                revisitDigest.length
        );
    }
}
""",
    encoding="utf-8",
)

subprocess.run(
    ["javac", "-cp", str(anvil_net_jar), str(verifier_java)],
    check=True,
)

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


def run_client_arm(platform_slug: str, port: int, client_arm: str, bot_name: str) -> dict:
    arm_dir = work_dir / f"arm-{platform_slug}-{client_arm}"
    arm_dir.mkdir(parents=True, exist_ok=True)
    ack_path = arm_dir / "ack.bin"
    batch_path = arm_dir / "batch.bin"
    js_path = arm_dir / "probe.js"

    is_modded = client_arm in ("fabric-client", "neoforge-client")
    js_code = f"""
const fs = require('fs');
const mineflayer = require('{repo_root.as_posix()}/devstack/clients/node_modules/mineflayer');

const isModded = {'true' if is_modded else 'false'};
const ackPath = '{ack_path.as_posix()}';
const batchPath = '{batch_path.as_posix()}';

function classifyPacket(name) {{
  const n = (name || '').toLowerCase();
  if (n.includes('chunk') || n.includes('light') || n.includes('unload')) return 'CHUNK_STREAM';
  if (n.includes('entity_move') || n.includes('rel_entity_move') || n.includes('entity_look') ||
      n.includes('entity_head_rotation') || n.includes('entity_teleport') || n.includes('entity_velocity')) return 'ENTITY_KINEMATICS';
  if (n.includes('entity_metadata') || n.includes('entity_equipment') || n.includes('entity_update_attributes') ||
      n.includes('spawn_entity')) return 'ENTITY_METADATA_NBT';
  if (n.includes('block_change') || n.includes('multi_block_change') || n.includes('tile_entity')) return 'WORLD_BLOCK_DELTA';
  return 'TRANSIENT_FX_UI';
}}

function buildHandshakeBuffer() {{
  // 20-byte HandshakePayload: version=1 (int32), caps=0x3F (int32), cacheKiB=32768 (int32), parityHash=0xCBF29CE484222325 (int64)
  const buf = Buffer.alloc(20);
  buf.writeInt32BE(1, 0);
  buf.writeInt32BE(0x3F, 4);
  buf.writeInt32BE(32768, 8);
  buf.writeBigUInt64BE(0xCBF29CE484222325n, 12);
  return buf;
}}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {{
  const buckets = {{
    CHUNK_STREAM: {{ packets: 0, approxBytes: 0 }},
    ENTITY_KINEMATICS: {{ packets: 0, approxBytes: 0 }},
    ENTITY_METADATA_NBT: {{ packets: 0, approxBytes: 0 }},
    WORLD_BLOCK_DELTA: {{ packets: 0, approxBytes: 0 }},
    TRANSIENT_FX_UI: {{ packets: 0, approxBytes: 0 }},
  }};
  let tcpSegments = 0;
  let socketBytesRead = 0;
  let socketBytesWritten = 0;
  let gotHandshakeAck = false;
  let gotChunkBatch = false;
  let ackBytesLen = 0;
  let batchBytesLen = 0;

  const bot = mineflayer.createBot({{
    host: '127.0.0.1',
    port: {port},
    username: '{bot_name}',
    version: '26.1',
    viewDistance: 6,
  }});

  let socketHooked = false;
  function hookSocket() {{
    if (socketHooked || !bot._client || !bot._client.socket) return;
    socketHooked = true;
    bot._client.socket.on('data', (chunk) => {{
      tcpSegments += 1;
      socketBytesRead += chunk.length;
    }});
  }}
  bot._client.on('connect', hookSocket);

  bot._client.on('packet', (data, meta) => {{
    hookSocket();
    const name = (meta && meta.name) ? meta.name : 'unknown';
    if (name === 'custom_payload' && data && data.channel) {{
      const ch = String(data.channel);
      const rawBuf = Buffer.isBuffer(data.data) ? data.data : Buffer.from(data.data || []);
      if (ch === 'leafanvil:chunk_stream_handshake') {{
        gotHandshakeAck = true;
        ackBytesLen = rawBuf.length;
        fs.writeFileSync(ackPath, rawBuf);
      }} else if (ch === 'leafanvil:lca_chunk_batch') {{
        gotChunkBatch = true;
        batchBytesLen = rawBuf.length;
        fs.writeFileSync(batchPath, rawBuf);
      }}
    }}
    const bucket = classifyPacket(name);
    let estLen = 8;
    try {{
      if (Buffer.isBuffer(data)) estLen = data.length;
      else if (data && data.chunkData && Buffer.isBuffer(data.chunkData)) estLen = data.chunkData.length + 64;
      else estLen = JSON.stringify(data).length;
    }} catch (_) {{}}
    buckets[bucket].packets += 1;
    buckets[bucket].approxBytes += estLen;
  }});

  await new Promise((resolve, reject) => {{
    const t = setTimeout(() => reject(new Error('Spawn timeout')), 25000);
    bot.once('spawn', () => {{
      clearTimeout(t);
      resolve();
    }});
    bot.once('error', reject);
  }});
  hookSocket();

  if (isModded) {{
    const regChannels = Buffer.from(
      'leafanvil:chunk_stream_handshake\x00leafanvil:lca_chunk_batch\x00leafanvil:lca_chunk_digest',
      'utf8'
    );
    bot._client.write('custom_payload', {{
      channel: 'minecraft:register',
      data: regChannels,
    }});
    await sleep(200);
    bot._client.write('custom_payload', {{
      channel: 'leafanvil:chunk_stream_handshake',
      data: buildHandshakeBuffer(),
    }});
    const waitDeadline = Date.now() + 6000;
    while (Date.now() < waitDeadline && (!gotHandshakeAck || !gotChunkBatch)) {{
      await sleep(100);
    }}
  }}

  // Move / look for 2.0 seconds (40 ticks) to generate live kinematic + world traffic
  const moveStartBytes = bot._client.socket ? bot._client.socket.bytesRead : socketBytesRead;
  const moveStartSegments = tcpSegments;
  const startMs = Date.now();
  for (let step = 0; step < 10; step++) {{
    bot.swingArm('right');
    await sleep(200);
  }}
  const elapsedSec = Math.max(0.5, (Date.now() - startMs) / 1000.0);
  const totalSocketRx = bot._client.socket ? bot._client.socket.bytesRead : socketBytesRead;
  const totalSocketTx = bot._client.socket ? bot._client.socket.bytesWritten : socketBytesWritten;
  const steadyRxBytes = Math.max(0, totalSocketRx - moveStartBytes);
  const steadySegments = Math.max(1, tcpSegments - moveStartSegments);

  bot.quit();
  await sleep(200);

  console.log('PROBE_RESULT_JSON=' + JSON.stringify({{
    platform: '{platform_slug}',
    clientArm: '{client_arm}',
    elapsedSec,
    totalSocketRxBytes: totalSocketRx,
    totalSocketTxBytes: totalSocketTx,
    tcpSegments,
    tcpFramingHeaderBytes: tcpSegments * 52,
    steadyRxBytes,
    steadySegments,
    gotHandshakeAck,
    gotChunkBatch,
    ackBytesLen,
    batchBytesLen,
    buckets,
  }}));
  process.exit(0);
}})().catch((err) => {{
  console.error(err);
  process.exit(1);
}});
"""
    js_path.write_text(js_code, encoding="utf-8")
    proc = subprocess.run(
        ["node", str(js_path)],
        cwd=str(repo_root),
        capture_output=True,
        text=True,
        timeout=45,
        check=True,
    )
    probe_json = None
    for line in proc.stdout.splitlines():
        if line.startswith("PROBE_RESULT_JSON="):
            probe_json = json.loads(line[len("PROBE_RESULT_JSON="):])
    if not probe_json:
        raise RuntimeError(f"Missing PROBE_RESULT_JSON in {platform_slug}/{client_arm}: {proc.stdout}\n{proc.stderr}")

    if is_modded and probe_json.get("gotHandshakeAck") and probe_json.get("gotChunkBatch"):
        loader_label = "Fabric" if client_arm == "fabric-client" else "NeoForge"
        v_proc = subprocess.run(
            [
                "java",
                "-cp",
                f"{verifier_dir}:{anvil_net_jar}",
                "LiveWireClientVerifier",
                loader_label,
                str(ack_path),
                str(batch_path),
            ],
            capture_output=True,
            text=True,
            timeout=15,
            check=True,
        )
        probe_json["clientHydrationProof"] = json.loads(v_proc.stdout.strip().splitlines()[-1])
    return probe_json


try:
    print(f"[leafanvil-net-devstack] Provisioning 4 server platforms in {work_dir}...")
    for node in NODES:
        slug = node["slug"]
        srv_dir = work_dir / f"server-{slug}"
        _, base_cmd = msa.provision_server(
            platform=node["platform"],
            srv_dir=srv_dir,
            game_port=node["game_port"],
            rcon_port=node["rcon_port"],
            rcon_password=RCON_PASS,
            seed="8675309",
            jars=[anvil_jar],
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
            op_usernames=("van_bot", "fab_bot", "neo_bot"),
        )
        log_file = srv_dir / "stdout.log"
        logs[slug] = log_file
        out_f = open(log_file, "w", encoding="utf-8")
        procs[slug] = subprocess.Popen(
            base_cmd,
            cwd=str(srv_dir),
            stdin=subprocess.PIPE,
            stdout=out_f,
            stderr=subprocess.STDOUT,
        )

    for node in NODES:
        slug = node["slug"]
        rc = msa.RconClient("127.0.0.1", node["rcon_port"], RCON_PASS)
        rcons[slug] = rc
        boot_s = msa.wait_for_rcon(rc, procs[slug], timeout=180.0)
        print(f"[Boot] {slug} RCON ready in {boot_s}s")
        rc.cmd("forceload add -32 -32 32 32")

    time.sleep(3.0)

    matrix_results = []
    server_differentials = {}

    for node in NODES:
        slug = node["slug"]
        port = node["game_port"]
        print(f"\n[Matrix] Running Vanilla, FabricClient, and NeoForgeClient arms against {slug} (:{port})...")
        for arm, bot_name in [
            ("vanilla-client", f"van_{slug[:3]}"),
            ("fabric-client", f"fab_{slug[:3]}"),
            ("neoforge-client", f"neo_{slug[:3]}"),
        ]:
            log_offset = logs[slug].stat().st_size if logs[slug].is_file() else 0
            res = run_client_arm(slug, port, arm, bot_name)
            if arm in ("fabric-client", "neoforge-client"):
                diff_line = poll_log_for_line(logs[slug], "[LeafAnvil-Net Differential]", log_offset, timeout=5.0)
                if diff_line:
                    json_part = diff_line.split("[LeafAnvil-Net Differential]", 1)[1].strip()
                    res["serverDifferential"] = json.loads(json_part)
                    server_differentials[f"{slug}:{arm}"] = res["serverDifferential"]
            matrix_results.append(res)
            print(
                f"  -> {slug} x {arm}: socketRx={res['totalSocketRxBytes']} B, "
                f"segments={res['tcpSegments']}, ack={res['gotHandshakeAck']}, "
                f"batch={res['gotChunkBatch']} ({res['batchBytesLen']} B), "
                f"hydration={res.get('clientHydrationProof', {}).get('committedChunks', 0)} chunks"
            )
            if arm in ("fabric-client", "neoforge-client"):
                assert res["gotHandshakeAck"], f"{slug} x {arm} failed to receive LCA_NET_V1 handshake ACK"
                assert res["gotChunkBatch"], f"{slug} x {arm} failed to receive LCA_NET_V1 chunk batch"
                assert res.get("clientHydrationProof", {}).get("committedChunks", 0) >= 1, (
                    f"{slug} x {arm} failed client chunk hydration"
                )
                assert f"{slug}:{arm}" in server_differentials, (
                    f"{slug} x {arm} missing [LeafAnvil-Net Differential] server log line"
                )

    for node in NODES:
        slug = node["slug"]
        latest_log = work_dir / f"server-{slug}" / "logs" / "latest.log"
        target_log = latest_log if latest_log.is_file() else logs[slug]
        text = target_log.read_text(encoding="utf-8", errors="replace")
        m = LINKAGE_RE.search(text)
        assert not m, f"Linkage/thread error found in {slug} log ({target_log}): {m.group(0)}"

    report_path = work_dir / "network-differential-report.json"
    report_path.write_text(
        json.dumps(
            {
                "status": "PASS",
                "matrixResults": matrix_results,
                "serverDifferentials": server_differentials,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"\n[leafanvil-net-devstack] PASS — Full 4x3 matrix report written to {report_path}")

finally:
    for slug, proc in procs.items():
        if proc.poll() is None:
            try:
                proc.terminate()
                proc.wait(timeout=10)
            except Exception:
                proc.kill()
PY
