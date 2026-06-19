#!/usr/bin/env python3
"""audit-locale-bleed.py

Cross-platform (Python 3.12) replacement for audit-locale-bleed.ps1.

Audits English bleed across all shipped locale TSVs. For every row in
scripts/out/locale-<lang>.tsv whose value column is byte-identical to the
matching English baseline value, write the row to scripts/out/bleed-<lang>.tsv.
Joins on (filename, parent_path, base_key, index) where filename is the locale
TSV relpath stripped of its lang/<lang>/ prefix.

Empty values and rows without a matching baseline are excluded. Read-only with
respect to lang/<locale>/*.yml - only produces the bleed-<lang>.tsv manifests
consumed by the bundle export/import pair.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def _split8(line: str) -> list[str]:
    return line.split("\t", 7)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--locale", default="")
    ap.add_argument("--out-dir", dest="out_dir")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    baseline = out_dir / "baseline.tsv"
    if not baseline.exists():
        raise SystemExit(f"baseline.tsv not found at {baseline}. Run locale-files-to-csv.py first.")

    base: dict[str, tuple[str, str]] = {}
    header: str | None = None
    for line in re.split(r"\r?\n", lc.read_text_no_bom(baseline)):
        if line == "":
            continue
        if header is None:
            header = line
            continue
        c = _split8(line)
        if len(c) != 8:
            continue
        key = f"{c[0]}|{c[1]}|{c[7]}|{c[3]}"
        base[key] = (c[4], c[5])

    if args.locale:
        locale_files = [out_dir / f"locale-{args.locale}.tsv"]
    else:
        locale_files = sorted(out_dir.glob("locale-*.tsv"))

    summary: list[dict] = []
    for tsv in locale_files:
        if not tsv.exists():
            continue
        lang = re.sub(r"^locale-", "", tsv.stem)
        bleed_path = out_dir / f"bleed-{lang}.tsv"
        rows: list[str] = []
        matched = empty = bled = nativised = 0
        first = True
        for line in re.split(r"\r?\n", lc.read_text_no_bom(tsv)):
            if first:
                first = False
                continue
            if line == "":
                continue
            c = _split8(line)
            if len(c) != 8:
                continue
            file = re.sub(r"^lang/[^/]+/", "", c[0])
            key = f"{file}|{c[1]}|{c[7]}|{c[3]}"
            if key not in base:
                continue
            matched += 1
            bv = base[key][0]
            lv = c[4]
            if lv == "":
                empty += 1
                continue
            if lv == bv and bv != "":
                bled += 1
                rows.append(line)
            else:
                nativised += 1

        out_lines = [header or "\t".join(lc.TSV_COLS)] + rows
        lc.write_bytes_utf8(bleed_path, "\n".join(out_lines) + "\n")

        pct = (100.0 * bled / max(bled + nativised, 1)) if matched > 0 else 0.0
        summary.append({
            "Locale": lang, "Matched": matched, "Bled": bled,
            "Nativised": nativised, "Empty": empty,
            "BleedPctNonEmpty": f"{pct:.1f}%",
            "ManifestPath": str(bleed_path),
        })

    summary.sort(key=lambda s: -s["Bled"])
    print(f"{'Locale':<8} {'Matched':>8} {'Bled':>6} {'Nativised':>10} {'Empty':>6} {'Bleed%':>8}")
    for s in summary:
        print(f"{s['Locale']:<8} {s['Matched']:>8} {s['Bled']:>6} {s['Nativised']:>10} "
              f"{s['Empty']:>6} {s['BleedPctNonEmpty']:>8}")

    print()
    print("Wrote bleed manifests to scripts/out/bleed-<lang>.tsv. Pass one to:")
    print("  python scripts/export-translation-bundle.py --locale <lang>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
