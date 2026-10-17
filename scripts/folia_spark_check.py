#!/usr/bin/env python3
"""Do the spark profiles of a Folia run contain samples from the region threads? (stdlib only)

Decodes the newest `.sparkprofile` files under <server-dir>/plugins/spark with the
harness's spark_summary.py and prints each profile's per-thread weight. Folia ticks on
`Region Scheduler Thread #N`, not `Server thread`, so a profile taken with the Paper
thread selector is empty there. The check passes only when region-thread samples exist.

  python scripts/folia_spark_check.py --server-dir ~/threadripper-dir/folia/26.2 --since-min 15

Exit codes: 0 = region-thread samples found in every checked profile, 1 = at least one
checked profile has none, 2 = no profile newer than --since-min (nothing was checked).
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "helpers", "StressTestRTP", "scripts"))

REGION_MARKER = "region scheduler thread"


def region_weight(threads: list[dict]) -> float:
    """Summed weight of the Folia region threads in a spark_summary `thread_cpu_top` list."""
    return sum(float(t.get("weight") or 0.0) for t in threads if REGION_MARKER in str(t.get("thread", "")).lower())


def newest_profiles(spark_dir: str, since_epoch: float, limit: int) -> list[str]:
    found = []
    for p in glob.glob(os.path.join(spark_dir, "*.sparkprofile")):
        try:
            m = os.path.getmtime(p)
        except OSError:
            continue
        if m >= since_epoch:
            found.append((m, p))
    found.sort(reverse=True)
    return [p for _m, p in found[:limit]]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server-dir", required=True)
    ap.add_argument("--since-min", type=float, default=30.0, help="only profiles modified within this many minutes")
    ap.add_argument("--limit", type=int, default=6, help="check at most this many (newest first)")
    ap.add_argument("--top-threads", type=int, default=8)
    a = ap.parse_args(argv)

    import spark_summary as ss  # noqa: E402  (path set above)

    files = newest_profiles(os.path.join(a.server_dir, "plugins", "spark"), time.time() - a.since_min * 60, a.limit)
    if not files:
        print(f"no .sparkprofile newer than {a.since_min:g} min in {a.server_dir}/plugins/spark; nothing checked")
        return 2
    bad = 0
    for path in files:
        summary = ss.summarise(ss.load(path), None, a.top_threads)
        threads = summary.get("thread_cpu_top") or []
        rw = region_weight(threads)
        ok = rw > 0
        bad += 0 if ok else 1
        names = ", ".join(f"{t['thread']}={float(t.get('weight') or 0):.0f}" for t in threads) or "(no thread samples)"
        print(f"{'OK  ' if ok else 'FAIL'} {os.path.basename(path)} platform={summary.get('platform')} "
              f"region-thread weight={rw:.0f}; threads: {names}")
    if bad:
        print(f"{bad} of {len(files)} profile(s) have no region-thread samples: check spark.threads in "
              "plugins/StressTestRTP/config.yml (use \"*Region Scheduler Thread*,RTP-Anvil-IO-*\" on Folia, or \"*\" "
              "once to see every thread name)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
