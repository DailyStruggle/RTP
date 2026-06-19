#!/usr/bin/env python3
"""verify-tsv-parity.py

Cross-platform (Python 3.12) replacement for verify-tsv-parity.ps1.

Structural parity check: for every scripts/out/locale-<lang>.tsv, confirm its
rows line up positionally with scripts/out/baseline.tsv on the
(shape-normalized relpath, base_key, index) triple. Reports a per-locale
mismatch count and prints up to 3 sample mismatches for the German locale (the
historical canary locale).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def _norm_baseline_rel(rel: str) -> str:
    return re.sub(r"^lang/(shape|vert)/", r"\1/", rel)


def _norm_locale_rel(rel: str, loc: str) -> str:
    rel = re.sub(rf"^lang/{re.escape(loc)}/(shape|vert)/", r"\1/", rel)
    rel = re.sub(rf"^lang/{re.escape(loc)}/", "", rel)
    return rel


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", dest="out_dir")
    ap.add_argument("--sample-locale", dest="sample_locale", default="de")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    baseline_csv = out_dir / "baseline.tsv"
    if not baseline_csv.exists():
        raise SystemExit(f"baseline.tsv not found at {baseline_csv}. Run locale-files-to-csv.py first.")

    bl_rows = [
        (_norm_baseline_rel(r["relpath"]), r["base_key"], r["index"])
        for r in lc.read_tsv(baseline_csv)
    ]

    locales = lc.list_locales()
    for loc in locales:
        path = out_dir / f"locale-{loc}.tsv"
        if not path.exists():
            print(f"{loc:<4} (no TSV)")
            continue
        loc_rows = [
            (_norm_locale_rel(r["relpath"], loc), r["base_key"], r["index"])
            for r in lc.read_tsv(path)
        ]
        mismatch = 0
        samples: list[str] = []
        for i in range(min(len(bl_rows), len(loc_rows))):
            if bl_rows[i] != loc_rows[i]:
                mismatch += 1
                if len(samples) < 3:
                    b = bl_rows[i]
                    lr = loc_rows[i]
                    samples.append(
                        f"row {i} baseline=[{b[0]}|{b[1]}|{b[2]}] "
                        f"locale=[{lr[0]}|{lr[1]}|{lr[2]}]"
                    )
        print(f"{loc:<4} mismatches={mismatch}")
        if loc == args.sample_locale:
            for s in samples:
                print(f"    {s}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
