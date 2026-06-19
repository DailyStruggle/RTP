#!/usr/bin/env python3
"""seed-pregen-worlds.py

Cross-platform (Python 3.12) replacement for seed-pregen-worlds.ps1.

Seed each devstack backend with PRE-GENERATED spawn-area chunks so the
multi-world /rtp demo never hits null-chunk timeouts.

Each backend's single `default` region (backend-a -> Overworld, backend-b ->
Nether, backend-c -> End) is centered on (0,0) with radii well inside +/-512
blocks. A freshly-booted itzg world generates those chunks on demand, and
under load the async chunk loads can time out and surface as null-chunk
pipeline failures. This script copies the pre-generated single-server world
into each backend's GITIGNORED, bind-mounted world dirs.

By default it copies the FULL world (every `.mca`). Pass --region-radius N
(>= 0) to copy only the small spawn-area window instead (N=1 => the 3x3 block
of `.mca` around the origin).

World layout (all backends run the Bukkit/Paper-family layout): overworld ->
world/, Nether -> world_nether/, End -> world_the_end/.

Run while the stack is DOWN (the world dirs are host binds). Idempotent.

Usage:
  python devstack/seed-pregen-worlds.py
  python devstack/seed-pregen-worlds.py --region-radius 1
  python devstack/seed-pregen-worlds.py --backends backend-a backend-b
  python devstack/seed-pregen-worlds.py --source <path-to-pregenerated-server>
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

DEFAULT_SOURCE = r"C:\GameServers\Minecraft\testServer\RTP-Spigot\1.21.11"
ALL_BACKENDS = ["backend-a", "backend-b", "backend-c"]


def _count_mca(root: Path) -> int:
    return sum(1 for _ in root.rglob("*.mca"))


def copy_region_subset(src_world: Path, dst_world: Path, radius: int) -> None:
    """Copy region-style files (region/, entities/, poi/) within +/-radius of
    the origin, or the whole world verbatim when radius < 0."""
    if not src_world.exists():
        print(f"    (source world missing: {src_world}) - skipping")
        return
    dst_world.mkdir(parents=True, exist_ok=True)

    # Full-world mode: copy the whole source world verbatim.
    if radius < 0:
        for item in src_world.iterdir():
            dest = dst_world / item.name
            if item.is_dir():
                shutil.copytree(item, dest, dirs_exist_ok=True)
            else:
                shutil.copy2(item, dest)
        print(f"    full copy: {_count_mca(dst_world)} .mca file(s)")
        return

    # Top-level world files needed for the world to load cleanly.
    for f in ("level.dat", "level.dat_old", "paper-world.yml"):
        src = src_world / f
        if src.exists():
            shutil.copy2(src, dst_world / f)
    # data/ holds idcounts / scoreboard / raids etc.; small and safe to copy.
    src_data = src_world / "data"
    if src_data.exists():
        shutil.copytree(src_data, dst_world / "data", dirs_exist_ok=True)

    names = [f"r.{x}.{z}.mca"
             for x in range(-radius, radius + 1)
             for z in range(-radius, radius + 1)]

    for sub in ("region", "entities", "poi"):
        src_sub = src_world / sub
        if not src_sub.exists():
            continue
        dst_sub = dst_world / sub
        dst_sub.mkdir(parents=True, exist_ok=True)
        copied = 0
        for n in names:
            sf = src_sub / n
            if sf.exists():
                shutil.copy2(sf, dst_sub / n)
                copied += 1
        print(f"    {sub}/: copied {copied} of {len(names)} spawn-area .mca")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Seed devstack backends with pre-generated worlds.")
    parser.add_argument("--source", default=DEFAULT_SOURCE,
                        help="pre-generated single-server world root (world/, world_nether/, world_the_end/)")
    parser.add_argument("--region-radius", type=int, default=-1,
                        help="-1 (default) copies the full world; >=0 copies only that origin window")
    parser.add_argument("--backends", nargs="+", default=ALL_BACKENDS, choices=ALL_BACKENDS,
                        help="which backends to seed (default: all three)")
    args = parser.parse_args(argv[1:])

    devstack = Path(__file__).resolve().parent
    source = Path(args.source)
    if not source.exists():
        raise SystemExit(f"Source world root not found: {source}. "
                         f"Pass --source <path-to-pregenerated-server>.")

    src_over = source / "world"
    src_nether = source / "world_nether"
    src_end = source / "world_the_end"

    for b in args.backends:
        print(f"===== {b} =====")
        root = devstack / b
        print("  overworld -> world/")
        copy_region_subset(src_over, root / "world", args.region_radius)
        print("  nether    -> world_nether/")
        copy_region_subset(src_nether, root / "world_nether", args.region_radius)
        print("  end       -> world_the_end/")
        copy_region_subset(src_end, root / "world_the_end", args.region_radius)

    print()
    print("Done. The demo regions' spawn-area chunks are now pre-generated.")
    print("Run 'docker compose up' (from devstack) to boot the stack.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
