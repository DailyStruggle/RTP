#!/usr/bin/env python3
"""bake-lobby-world.py

Cross-platform (Python 3.12) replacement for bake-lobby-world.ps1.

Bakes a live lobby world into a reusable zip for the rtp-proxy devstack.

Reads a populated `world/` directory under one of the devstack's lobby service
bind mounts (`lobby-a/world` or `lobby-b/world`) and archives it to
`shared/lobby-world.zip`. When that zip is present on the next
`docker compose up`, run-acceptance layers docker-compose.lobby-world.yml, which
mounts the zip into each lobby container so both lobbies boot directly into the
baked world (via the itzg/minecraft-server WORLD + FORCE_WORLD_COPY contract).

This is the manual half of the schematic workflow: this script does NOT paste a
schematic for you. The bake step itself is fully scripted and idempotent.

Usage:
  python devstack/scripts/bake-lobby-world.py
  python devstack/scripts/bake-lobby-world.py --source lobby-b --force
  python devstack/scripts/bake-lobby-world.py --output <path>
"""

from __future__ import annotations

import argparse
import sys
import time
import zipfile
from pathlib import Path


def _dir_size_mb(root: Path) -> float:
    total = 0
    for p in root.rglob("*"):
        if p.is_file():
            try:
                total += p.stat().st_size
            except OSError:
                pass
    return round(total / (1024 * 1024), 2)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Bake a devstack lobby world into a reusable zip.")
    parser.add_argument("--source", default="lobby-a", choices=["lobby-a", "lobby-b"],
                        help="which lobby world to archive (default: lobby-a)")
    parser.add_argument("--force", action="store_true",
                        help="overwrite an existing zip / bake despite warnings")
    parser.add_argument("--output", default=None, help="override the output zip path")
    args = parser.parse_args(argv[1:])

    # Resolve devstack root (parent of this script's directory).
    devstack_root = Path(__file__).resolve().parent.parent
    if not (devstack_root / "docker-compose.yml").exists():
        raise SystemExit(f"Could not locate devstack root from {Path(__file__).parent} "
                         f"(expected docker-compose.yml in parent).")

    world_dir = devstack_root / args.source / "world"
    if not world_dir.exists():
        raise SystemExit(f"Source world directory not found: {world_dir}. Boot {args.source} "
                         f"via 'docker compose up -d' and paste the schematic before baking.")
    if not (world_dir / "level.dat").exists():
        raise SystemExit(f"{world_dir} exists but contains no level.dat - the lobby has not "
                         f"been booted yet, or the world is corrupt.")

    output = Path(args.output) if args.output else (devstack_root / "shared" / "lobby-world.zip")

    if output.exists():
        if not args.force:
            print(f"[bake] {output} already exists. Re-run with --force to overwrite.")
            return 0
        output.unlink()

    # Refuse to bake if a container still holds the world's session.lock - the
    # archive would capture mid-write region files and corrupt downstream boots.
    session_lock = world_dir / "session.lock"
    if session_lock.exists():
        age = time.time() - session_lock.stat().st_mtime
        if age < 30:
            print(f"[bake] {session_lock} was touched <30s ago - {args.source} may still be "
                  f"running. Stop the stack first ('docker compose stop {args.source}') and retry.")
            raise SystemExit(f"session.lock on {args.source} is fresh; refusing to bake a live world.")
        print("[bake] Stale session.lock detected (older than 30s); proceeding.")

    # Sanity check: world dirs typically run 5-100 MB. A < 1 MB world means the
    # paste likely was not applied; warn but allow if --force.
    size_mb = _dir_size_mb(world_dir)
    if size_mb < 1:
        if not args.force:
            print(f"[bake] {world_dir} is only {size_mb} MB - looks empty. "
                  f"Paste the schematic first, or re-run with --force.")
            return 0
        print(f"[bake] WARN {world_dir} is only {size_mb} MB; baking anyway because --force.")

    print(f"[bake] archiving {world_dir} ({size_mb} MB) -> {output} ...")

    # Build the zip with `world/` as the top-level entry, using explicit
    # forward-slash entry names (APPNOTE 4.4.17: forward slashes only) so Linux
    # `unzip` inside itzg/minecraft-server does not choke on backslashes.
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(world_dir.rglob("*")):
            if not f.is_file():
                continue
            # Drop session.lock from the archive - the destination container
            # writes its own on boot.
            if f.name == "session.lock":
                continue
            rel = f.relative_to(world_dir).as_posix()
            zf.write(f, f"world/{rel}")

    out_size = round(output.stat().st_size / (1024 * 1024), 2)
    print(f"[bake] PASS - wrote {output} ({out_size} MB). "
          f"Next 'docker compose up' will boot lobbies from this world.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
