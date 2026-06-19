#!/usr/bin/env python3
"""setup-multiworld-demo.py

Cross-platform (Python 3.12) replacement for setup-multiworld-demo.ps1.

One-shot pre-configuration of the devstack for the multi-server / multi-world
LeafRTPGuiAddon demo. Wires up everything the "DonutSMP-style" GUI menu needs:

  1. Supplies a pre-generated world for every backend's overworld, Nether, and
     End (delegates to seed-pregen-worlds.py, full copy by default).
  2. Installs the LeafRTPGuiAddon into the Bukkit-family instances so a bare
     /rtp opens the chest menu (delegates to add-gui-addon.py). Opt out with
     --no-gui-addon.

Each backend's single `default` region is committed under its
rtp-config/regions/default.yml and seeded into the running containers by the
docker-compose entrypoint shims - no action needed here:

  backend-a (Paper)  : Overworld  ->  Verdant Wilds
  backend-b (Folia)  : Nether     ->  Ashen Wastes
  backend-c (Folia)  : End        ->  Void Reaches

Run while the stack is DOWN, then `docker compose up`.

Usage:
  python devstack/setup-multiworld-demo.py                 # full demo setup (+ GUI addon)
  python devstack/setup-multiworld-demo.py --no-gui-addon  # regions + worlds only
  python devstack/setup-multiworld-demo.py --skip-pregen   # just (re)install the GUI addon
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

DEFAULT_SOURCE = r"C:\GameServers\Minecraft\testServer\RTP-Spigot\1.21.11"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Set up the multi-world GUI demo devstack.")
    parser.add_argument("--no-gui-addon", action="store_true",
                        help="opt out of installing the GUI addon (regions + pre-gen worlds only)")
    parser.add_argument("--source", default=DEFAULT_SOURCE,
                        help="pre-generated world root (passed through to seed-pregen-worlds.py)")
    parser.add_argument("--region-radius", type=int, default=-1,
                        help="-1 copies the full world; >=0 for a small spawn-area window only")
    parser.add_argument("--skip-pregen", action="store_true", help="skip the world pre-generation step")
    parser.add_argument("--skip-build", action="store_true",
                        help="passed through to add-gui-addon.py (install the already-built jar)")
    args = parser.parse_args(argv[1:])

    devstack = Path(__file__).resolve().parent
    python = sys.executable

    print("=== RTP multi-server / multi-world GUI demo setup ===")

    if not args.skip_pregen:
        print("\n[1/2] Supplying pre-generated world(s) ...")
        subprocess.run(
            [python, str(devstack / "seed-pregen-worlds.py"),
             "--source", args.source, "--region-radius", str(args.region_radius)],
            check=True,
        )
    else:
        print("\n[1/2] --skip-pregen: leaving world dirs as-is.")

    if not args.no_gui_addon:
        print("\n[2/2] Installing LeafRTPGuiAddon into Bukkit-family instances ...")
        gui_cmd = [python, str(devstack / "add-gui-addon.py")]
        if args.skip_build:
            gui_cmd.append("--skip-build")
        subprocess.run(gui_cmd, check=True)
    else:
        print("\n[2/2] --no-gui-addon: skipping GUI addon install.")

    print("\nDone. Next:")
    print(f'  cd "{devstack}"')
    print("  docker compose up -d")
    print("  # connect a 1.21.x client to localhost:25577 (proxy-a) -> lands on a lobby")
    print("  # run /rtp to open the GUI; lobby shows cross-server peer regions,")
    print("  # /server backend-a|backend-b|backend-c then /rtp teleports into that")
    print("  # backend's default region (Overworld / Nether / End respectively).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
