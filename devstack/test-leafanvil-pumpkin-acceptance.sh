#!/usr/bin/env bash
# test-leafanvil-pumpkin-acceptance.sh
#
# Reproducible live-server acceptance and bidirectional .lca (LCA-ICD-1.0) world
# handoff verification between:
#   1. Paper 26.2 running LeafAnvil-0.2.1.jar (:anvil-api LcaRegionReader/LcaRegionWriter)
#   2. PumpkinMC Rust server running lca-format Direct SectionFrame Bridge ([world.chunk] type = "lca")
#   3. Paper 26.2 reloading the PumpkinMC-mutated .lca world and verifying blocks over RCON
#
# Usage:
#   ./devstack/test-leafanvil-pumpkin-acceptance.sh [--skip-build] [--work-dir <dir>]

set -euo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEVSTACK_DIR/.." && pwd)"

SKIP_BUILD=0
WORK_DIR="${JUNIE_TMPDIR:-/tmp}/leafanvil-pumpkin-devstack"
PUMPKIN_SRC_DIR="${JUNIE_TMPDIR:-/tmp}/Pumpkin"
PUMPKIN_BIN="/home/leaf26/minecraft/26.2-lca-ab/server-cache/pumpkin-lca"
CARGO_BIN="${CARGO_BIN:-/tmp/junie-session-261111-193633-170y/cargo/bin/cargo}"

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build|-SkipBuild) SKIP_BUILD=1; shift ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[leafanvil-pumpkin-devstack] Building LeafAnvil-0.2.1.jar..."
  (cd "$REPO_ROOT" && ./gradlew :resource:LeafAnvil:leafanvil-plugin:assemble --console=plain -q)
  if [ -d "$PUMPKIN_SRC_DIR" ] && [ -x "$CARGO_BIN" ]; then
    echo "[leafanvil-pumpkin-devstack] Building PumpkinMC server binary with lca-format bridge..."
    "$CARGO_BIN" build --manifest-path "$PUMPKIN_SRC_DIR/Cargo.toml" --bin pumpkin -q
    cp -f "$PUMPKIN_SRC_DIR/target/debug/pumpkin" "$PUMPKIN_BIN"
  fi
fi

ANVIL_JAR="$REPO_ROOT/resource/LeafAnvil/leafanvil-plugin/build/libs/LeafAnvil-0.2.1.jar"
if [ ! -f "$ANVIL_JAR" ]; then
  echo "[leafanvil-pumpkin-devstack] ERROR: Missing $ANVIL_JAR" >&2
  exit 1
fi
if [ ! -x "$PUMPKIN_BIN" ]; then
  echo "[leafanvil-pumpkin-devstack] ERROR: Missing executable $PUMPKIN_BIN" >&2
  exit 1
fi

export REPO_ROOT WORK_DIR ANVIL_JAR PUMPKIN_BIN

python3 - <<'PY'
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time

repo_root = pathlib.Path(os.environ["REPO_ROOT"])
work_dir = pathlib.Path(os.environ["WORK_DIR"])
anvil_jar = pathlib.Path(os.environ["ANVIL_JAR"])
pumpkin_bin = pathlib.Path(os.environ["PUMPKIN_BIN"])

sys.path.insert(0, str(repo_root / "scripts"))
import mc_server_actions as msa

if work_dir.exists():
    shutil.rmtree(work_dir)
work_dir.mkdir(parents=True, exist_ok=True)

paper_dir = work_dir / "server-paper"
pumpkin_dir = work_dir / "server-pumpkin"
RCON_PORT = 25685
GAME_PORT_PAPER = 25675
GAME_PORT_PUMPKIN = 25679
RCON_PASS = "lca_pumpkin_pass"

print("[Stage 1] Provisioning & booting live Paper 26.2 server with LeafAnvil-0.2.1.jar...")
_, paper_cmd = msa.provision_server(
    platform="paper",
    srv_dir=paper_dir,
    game_port=GAME_PORT_PAPER,
    rcon_port=RCON_PORT,
    rcon_password=RCON_PASS,
    seed="8675309",
    jars=[anvil_jar],
    heap="2G",
    clean=True,
)

paper_log = paper_dir / "stdout.log"
with open(paper_log, "w", encoding="utf-8") as log_f:
    paper_proc = subprocess.Popen(
        paper_cmd,
        cwd=str(paper_dir),
        stdin=subprocess.PIPE,
        stdout=log_f,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        rc = msa.RconClient("127.0.0.1", RCON_PORT, RCON_PASS)
        boot_s = msa.wait_for_rcon(rc, paper_proc, timeout=180.0)
        print(f"  -> Paper 26.2 RCON ready in {boot_s}s")
        print("  -> Forceloading (0,0)..(16,16), placing diamond_block at (8,80,8), and flushing .lca...")
        rc.cmd("forceload add 0 0 16 16")
        time.sleep(2.5)
        set_resp = rc.cmd("setblock 8 80 8 minecraft:diamond_block replace")
        chk_resp = rc.cmd("execute if block 8 80 8 minecraft:diamond_block")
        print(f"  -> Paper RCON setblock={set_resp!r}, check={chk_resp!r}")
        assert "Test passed" in chk_resp, f"Paper failed to place diamond_block: {chk_resp}"
        rc.cmd("save-all flush")
        time.sleep(2.0)
        rc.close()
        assert paper_proc.stdin is not None
        paper_proc.stdin.write("stop\n")
        paper_proc.stdin.flush()
        paper_proc.wait(timeout=45.0)
    finally:
        if paper_proc.poll() is None:
            paper_proc.kill()

paper_overworld = paper_dir / "world" / "dimensions" / "minecraft" / "overworld"
if not (paper_overworld / "region" / "r.0.0.lca").is_file():
    paper_overworld = paper_dir / "world"
paper_lca = paper_overworld / "region" / "r.0.0.lca"
assert paper_lca.is_file(), f"Missing Paper .lca region file at {paper_lca}"
stage1_inspect = msa.inspect_world_regions(
    target_path=str(paper_overworld),
    block_filter="diamond_block,emerald_block",
    biome_filter="",
    max_samples=4,
)
stage1_blocks = stage1_inspect.get("matched_block_totals", {})
print(f"  -> Stage 1 (:anvil-api inspect): r.0.0.lca ({paper_lca.stat().st_size} B), blocks={stage1_blocks}")
assert stage1_blocks.get("minecraft:diamond_block", 0) >= 1, f"Missing diamond_block in Stage 1 .lca: {stage1_inspect}"

print("\n[Stage 2] Booting live PumpkinMC server ([world.chunk] type = 'lca') on Paper's r.0.0.lca...")
pumpkin_dir.mkdir(parents=True, exist_ok=True)
subprocess.run([str(pumpkin_bin), "--init-settings"], cwd=str(pumpkin_dir), check=True)
toml_path = pumpkin_dir / "pumpkin.toml"
toml_text = toml_path.read_text(encoding="utf-8")
toml_text = toml_text.replace('type = "pump"', 'type = "lca"')
toml_text = toml_text.replace('address = "0.0.0.0:25565"', f'address = "127.0.0.1:{GAME_PORT_PUMPKIN}"')
toml_text = toml_text.replace('encryption = true', 'encryption = false')
toml_text = toml_text.replace('online_mode = true', 'online_mode = false')
toml_text = toml_text.replace('view_distance = 16', 'view_distance = 4')
toml_text = toml_text.replace('simulation_distance = 10', 'simulation_distance = 4')
toml_text = toml_text.replace('use_tty = true', 'use_tty = false')
toml_text = toml_text.replace('default_op_level = 0', 'default_op_level = 4')
toml_text = toml_text.replace('allow_nether = true', 'allow_nether = false')
toml_text = toml_text.replace('allow_end = true', 'allow_end = false')
toml_path.write_text(toml_text, encoding="utf-8")

pumpkin_reg_dir = pumpkin_dir / "world" / "dimensions" / "minecraft" / "overworld" / "region"
pumpkin_reg_dir.mkdir(parents=True, exist_ok=True)
pumpkin_lca = pumpkin_reg_dir / "r.0.0.lca"
shutil.copy2(paper_lca, pumpkin_lca)

pumpkin_log = pumpkin_dir / "stdout.log"
with open(pumpkin_log, "w", encoding="utf-8") as log_f:
    pumpkin_proc = subprocess.Popen(
        [str(pumpkin_bin)],
        cwd=str(pumpkin_dir),
        stdin=subprocess.PIPE,
        stdout=log_f,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        deadline = time.time() + 45.0
        while time.time() < deadline:
            if pumpkin_log.is_file() and "Server is now running" in pumpkin_log.read_text(encoding="utf-8", errors="replace"):
                break
            if pumpkin_proc.poll() is not None:
                raise RuntimeError("PumpkinMC exited prematurely during boot")
            time.sleep(0.2)
        print("  -> PumpkinMC booted with [world.chunk] type = 'lca'")
        assert pumpkin_proc.stdin is not None
        pumpkin_proc.stdin.write("forceload add 0 0 16 16\n")
        pumpkin_proc.stdin.flush()
        time.sleep(4.0)
        # Verify (8, 80, 8) is already non-air (diamond_block from Paper) using 'keep' mode,
        # then place emerald_block at (9, 80, 8)
        pumpkin_proc.stdin.write("setblock 8 80 8 minecraft:gold_block keep\n")
        pumpkin_proc.stdin.write("setblock 9 80 8 minecraft:emerald_block replace\n")
        pumpkin_proc.stdin.flush()
        time.sleep(1.5)
        pumpkin_proc.stdin.write("save-all\n")
        pumpkin_proc.stdin.flush()
        time.sleep(2.5)
        pumpkin_proc.stdin.write("stop\n")
        pumpkin_proc.stdin.flush()
        pumpkin_proc.wait(timeout=30.0)
    finally:
        if pumpkin_proc.poll() is None:
            pumpkin_proc.kill()

p_log_text = pumpkin_log.read_text(encoding="utf-8", errors="replace")
assert "Changed the block at 9, 80, 8" in p_log_text, f"PumpkinMC failed to setblock (9,80,8):\n{p_log_text[-1500:]}"
assert "Completed save!" in p_log_text, f"PumpkinMC did not complete save:\n{p_log_text[-1500:]}"

stage2_inspect = msa.inspect_world_regions(
    target_path=str(pumpkin_dir / "world" / "dimensions" / "minecraft" / "overworld"),
    block_filter="diamond_block,emerald_block,gold_block",
    biome_filter="",
    max_samples=4,
)
stage2_blocks = stage2_inspect.get("matched_block_totals", {})
print(f"  -> Stage 2 (:anvil-api inspect of PumpkinMC .lca): blocks={stage2_blocks}")
assert stage2_blocks.get("minecraft:diamond_block", 0) >= 1, f"PumpkinMC lost Paper's diamond_block: {stage2_blocks}"
assert stage2_blocks.get("minecraft:emerald_block", 0) >= 1, f"PumpkinMC failed to persist emerald_block: {stage2_blocks}"
assert stage2_blocks.get("minecraft:gold_block", 0) == 0, f"setblock keep overwrote non-air diamond_block: {stage2_blocks}"

print("\n[Stage 3] Copying PumpkinMC-saved r.0.0.lca back to Paper 26.2 and verifying live in-world blocks over RCON...")
shutil.copy2(pumpkin_lca, paper_lca)
paper_log2 = paper_dir / "stdout-reload.log"
with open(paper_log2, "w", encoding="utf-8") as log_f:
    paper_proc2 = subprocess.Popen(
        paper_cmd,
        cwd=str(paper_dir),
        stdin=subprocess.PIPE,
        stdout=log_f,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        rc2 = msa.RconClient("127.0.0.1", RCON_PORT, RCON_PASS)
        boot2_s = msa.wait_for_rcon(rc2, paper_proc2, timeout=180.0)
        print(f"  -> Paper 26.2 (reload) RCON ready in {boot2_s}s")
        rc2.cmd("forceload add 0 0 16 16")
        time.sleep(2.0)
        chk_diamond = rc2.cmd("execute if block 8 80 8 minecraft:diamond_block")
        chk_emerald = rc2.cmd("execute if block 9 80 8 minecraft:emerald_block")
        print(f"  -> Paper RCON verify (8,80,8) diamond_block: {chk_diamond!r}")
        print(f"  -> Paper RCON verify (9,80,8) emerald_block: {chk_emerald!r}")
        assert "Test passed" in chk_diamond, f"Paper failed to verify diamond_block at (8,80,8): {chk_diamond}"
        assert "Test passed" in chk_emerald, f"Paper failed to verify PumpkinMC's emerald_block at (9,80,8): {chk_emerald}"
        rc2.close()
        assert paper_proc2.stdin is not None
        paper_proc2.stdin.write("stop\n")
        paper_proc2.stdin.flush()
        paper_proc2.wait(timeout=45.0)
    finally:
        if paper_proc2.poll() is None:
            paper_proc2.kill()

report = {
    "status": "PASS",
    "stage1PaperLcaBytes": paper_lca.stat().st_size,
    "stage1Blocks": stage1_blocks,
    "stage2PumpkinLcaBytes": pumpkin_lca.stat().st_size,
    "stage2Blocks": stage2_blocks,
    "stage3PaperRconDiamondCheck": chk_diamond,
    "stage3PaperRconEmeraldCheck": chk_emerald,
}
report_path = work_dir / "pumpkin-lca-acceptance-report.json"
report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
print(f"\n[PASS] Bidirectional Paper 26.2 (LeafAnvil) <-> PumpkinMC (.lca) live server handoff verified! Report: {report_path}")
PY
