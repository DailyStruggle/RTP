#!/usr/bin/env python3
"""reset-rtp-config.py

Cross-platform (Python 3.12) replacement for reset-rtp-config.ps1.

Wipes the plugin-owned config tree out of every devstack instance so the
next `docker compose up` lets the freshly-built jar re-extract the baseline
(config.yml, messages.yml, lang/, worlds/, regions/, effects/, safety.yml,
economy.yml, integrations.yml, language.yml, logging.yml, metrics.yml,
performance.yml, docs/) from scratch.

Devstack layout reminder (see docker-compose.yml):
  ./<instance>/plugins/            -> bind-mounted to /data/plugins
  ./<instance>/rtp-config/         -> seed-only dir; today the only file
                                      used is network.yml, copied into
                                      /data/plugins/RTP/network.yml by the
                                      container entrypoint on every boot.

So the plugin's runtime config lives entirely under
./<instance>/plugins/RTP/ on the host. Deleting that directory is safe and
idempotent: the container's entrypoint shim re-seeds network.yml from
./<instance>/rtp-config/network.yml, and the jar re-extracts every other
baseline file the first time the plugin loads.

Usage (from repo root or devstack dir):
  python devstack/reset-rtp-config.py
  python devstack/reset-rtp-config.py --include-database   # also wipes runtime DB

Safe to re-run; idempotent. Do NOT run while the devstack is up.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

# Paper/Folia instances wired into docker-compose.yml. All three backends and
# both lobbies bind-mount `plugins/` from the host, so the plugin's runtime
# config tree lives at `./<instance>/plugins/RTP/` and must be wiped here to
# pick up a fresh baseline (messages.yml etc.) from a newly-built jar.
INSTANCES = ["backend-a", "backend-b", "backend-c", "lobby-a", "lobby-b"]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Reset the devstack RTP config tree.")
    parser.add_argument("--include-database", action="store_true",
                        help="also wipe the runtime SQLite database directory")
    args = parser.parse_args(argv[1:])

    script_dir = Path(__file__).resolve().parent

    for name in INSTANCES:
        plugin_rtp = script_dir / name / "plugins" / "RTP"
        if not plugin_rtp.exists():
            print(f"[{name}] no plugins/RTP/ directory; skipping")
            continue
        print(f"===== {name} =====")

        if args.include_database:
            # Nuke the whole directory; entrypoint will recreate it and re-seed
            # network.yml on next boot.
            print("  del dir plugins/RTP/ (incl. database)")
            shutil.rmtree(plugin_rtp)
            continue

        # Default behavior: wipe everything under plugins/RTP/ EXCEPT database/,
        # which holds runtime SQLite state (per-container) that operators usually
        # want to preserve between resets.
        for child in sorted(plugin_rtp.iterdir()):
            if child.name == "database":
                print(f"  keep {child.name}/ (runtime; pass --include-database to wipe)")
                continue
            if child.is_dir():
                print(f"  del dir {child.name}/")
                shutil.rmtree(child)
            else:
                print(f"  del {child.name}")
                child.unlink()

    print()
    print("Done. Run 'docker compose up' (from devstack) to let")
    print("the plugin self-populate the baseline on first boot.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
