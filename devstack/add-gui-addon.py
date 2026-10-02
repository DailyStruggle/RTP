#!/usr/bin/env python3
"""add-gui-addon.py

Cross-platform (Python 3.12) replacement for add-gui-addon.ps1.

Opt-in: drop the LeafRTPGuiAddon (DonutSMP-style menu) into the devstack's
Bukkit-family instances. OFF by default - the normal devstack does not ship it.

Builds addons:LeafRTPGuiAddon:rtp-gui-bukkit (a shaded plugin jar that bundles
rtp-gui-common) and copies it into each Bukkit backend/lobby plugins dir that is
bind-mounted into the container at /data/plugins. Run before `docker compose up`.

Usage:
  python devstack/add-gui-addon.py             # build + install into backend-a/b/c, lobby-a/b
  python devstack/add-gui-addon.py --remove    # remove the addon jar from those instances
  python devstack/add-gui-addon.py --skip-build # install the already-built jar
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

# Bukkit-family instances (Paper/Folia backends and lobbies).
BUKKIT_TARGETS = ["backend-a", "backend-b", "lobby-a", "lobby-b"]

# Modded instances (Fabric on backend-c, NeoForge on backend-d).
MOD_TARGETS = ["backend-c", "backend-d"]

# plugin.yml declares name: LeafRTPGuiAddon, so any LeafRTPGuiAddon*.jar /
# rtp-gui-bukkit*.jar is "ours" for the remove pass. Also purge legacy
# RTP_GuiAddon*.jar so deprecated pre-3.2 GUI jars are cleaned up and don't shadow LeafRTPGuiAddon.
JAR_GLOBS = ["LeafRTPGuiAddon*.jar", "rtp-gui-bukkit*.jar", "RTP_GuiAddon*.jar"]


def _gradlew(repo_root: Path) -> str:
    return str(repo_root / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew"))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Install/remove the LeafRTPGuiAddon in the devstack.")
    parser.add_argument("--remove", action="store_true", help="remove the addon jar from the instances")
    parser.add_argument("--skip-build", action="store_true", help="install the already-built jar")
    args = parser.parse_args(argv[1:])

    devstack = Path(__file__).resolve().parent
    repo_root = devstack.parent

    if args.remove:
        for t in BUKKIT_TARGETS:
            plugins_dir = devstack / t / "plugins"
            if not plugins_dir.exists():
                continue
            for glob in JAR_GLOBS:
                for jar in plugins_dir.glob(glob):
                    jar.unlink()
                    print(f"removed {jar}")
        for t in MOD_TARGETS:
            mods_dir = devstack / t / "mods"
            if not mods_dir.exists():
                continue
            for glob in JAR_GLOBS:
                for jar in mods_dir.glob(glob):
                    jar.unlink()
                    print(f"removed {jar}")
        print("LeafRTPGuiAddon removed from devstack instances.")
        return 0

    if not args.skip_build:
        print("Building addons:LeafRTPGuiAddon:rtp-gui:shadowJar ...")
        result = subprocess.run(
            [_gradlew(repo_root), ":addons:LeafRTPGuiAddon:rtp-gui:shadowJar", "--console=plain"],
            cwd=repo_root,
        )
        if result.returncode != 0:
            raise SystemExit(f"gradle build failed (exit {result.returncode})")

    libs_dir = repo_root / "addons" / "LeafRTPGuiAddon" / "rtp-gui" / "build" / "libs"
    jars = sorted(libs_dir.glob("LeafRTPGuiAddon-*.jar"),
                  key=lambda p: p.stat().st_mtime, reverse=True) if libs_dir.exists() else []
    if not jars:
        raise SystemExit(f"Built jar not found under {libs_dir}. "
                         f"Run without --skip-build, or build the module first.")
    jar = jars[0]

    jar_bytes = jar.read_bytes()

    for t in BUKKIT_TARGETS:
        plugins_dir = devstack / t / "plugins"
        plugins_dir.mkdir(parents=True, exist_ok=True)
        # Clear any prior copy so a rename/version bump does not leave two jars.
        for glob in JAR_GLOBS:
            for old in plugins_dir.glob(glob):
                old.unlink()
        dest = plugins_dir / "LeafRTPGuiAddon.jar"
        dest.write_bytes(jar_bytes)
        print(f"installed {jar.name} -> {t}/plugins/LeafRTPGuiAddon.jar")

    for t in MOD_TARGETS:
        mods_dir = devstack / t / "mods"
        mods_dir.mkdir(parents=True, exist_ok=True)
        for glob in JAR_GLOBS:
            for old in mods_dir.glob(glob):
                old.unlink()
        dest = mods_dir / "LeafRTPGuiAddon.jar"
        dest.write_bytes(jar_bytes)
        print(f"installed {jar.name} -> {t}/mods/LeafRTPGuiAddon.jar")

        # Stage guimenu.yml into rtp-config/addons if missing
        addons_dir = devstack / t / "rtp-config" / "addons"
        addons_dir.mkdir(parents=True, exist_ok=True)
        cfg_dest = addons_dir / "guimenu.yml"
        if not cfg_dest.exists():
            src_cfg = devstack / "lobby-a" / "plugins" / "RTP" / "addons" / "guimenu.yml"
            if src_cfg.exists():
                cfg_dest.write_bytes(src_cfg.read_bytes())
                print(f"staged guimenu.yml -> {t}/rtp-config/addons/guimenu.yml")

    print()
    print("Done. Run 'docker compose up' (or restart the instances) to load it.")
    print("guimenu.yml self-creates on first boot in each instance's config/rtp/ or plugins/RTP/ folder.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
