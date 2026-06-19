#!/usr/bin/env python3
"""scan-locale-untranslated.py

Cross-platform (Python 3.12) replacement for scan-locale-untranslated.ps1.

First-pass triage scanner over the TSV pipeline produced by
locale-files-to-csv.py. Identifies rows in each scripts/out/locale-<lang>.tsv
that likely need translation attention, in four categories:

  1. DEPRECATED   - locale row whose (relpath, base_key) is absent from
                    baseline.tsv (stale; next reconcile pass will drop it).
  2. SAME_KEY     - translated `key` column identical to baseline `base_key`.
  3. SAME_VALUE   - `value` identical to baseline English (seeded-but-untranslated
                    or intentionally language-agnostic). Empty-both is skipped.
  4. SAME_COMMENT - `preceding_comment` byte-identical to baseline. Doc-tag-only
                    blocks (@type/@range/...) are skipped.

Read-only: modifies no TSV. Use --csv for machine-readable output.

  python scripts/scan-locale-untranslated.py
  python scripts/scan-locale-untranslated.py --locales de fr --sample-count 5
  python scripts/scan-locale-untranslated.py --csv > scripts/out/untranslated.tsv

See AGENTS.md "Locale Config TSV Pipeline" for the surrounding workflow.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def is_doc_tag_only_comment(comment: str) -> bool:
    if not comment or not comment.strip():
        return True
    for ln in comment.split("\n"):
        s = ln.lstrip()
        if not s.strip():
            continue
        if re.match(r"^#\s*@(type|range|unit|default|options|source):", s):
            continue
        if re.match(r"^#\s*#+\s*$", s):
            continue
        if re.match(r"^#\s*$", s):
            continue
        return False
    return True


def is_language_agnostic_value(value: str) -> bool:
    if not value or not value.strip():
        return True
    if re.match(r"^-?\d+(\.\d+)?$", value):
        return True
    if re.match(r"^(true|false|null|yes|no)$", value):
        return True
    if re.match(r"^&?#?[0-9A-Fa-f]{3,8}$", value):
        return True
    if re.match(r"^\[[^\]]+\]$", value):
        return True
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--locales", nargs="*", default=None)
    ap.add_argument("--sample-count", type=int, default=3)
    ap.add_argument("--csv", action="store_true")
    ap.add_argument("--out-dir", dest="out_dir")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    baseline_path = out_dir / "baseline.tsv"
    if not baseline_path.exists():
        raise SystemExit(f"baseline.tsv not found at {baseline_path} - run locale-files-to-csv.py first.")

    locales = args.locales if args.locales else lc.list_locales()

    # Key baseline by (normalized relpath, base_key).
    baseline_by_key: dict[str, dict] = {}
    for r in lc.read_tsv(baseline_path):
        norm = re.sub(r"^lang/", "", r["relpath"])
        baseline_by_key[f"{norm}\t{r['base_key']}"] = {
            "relpath": r["relpath"],
            "base_key": r["base_key"],
            "key": r["key"],
            "value": r["value"],
            "comment": r["preceding_comment"],
        }

    csv_rows: list[dict] = []

    for loc in locales:
        localepath = out_dir / f"locale-{loc}.tsv"
        if not localepath.exists():
            if not args.csv:
                print(f"{loc:<4}  (no locale-{loc}.tsv)")
            continue

        rows = lc.read_tsv(localepath)
        total = 0
        deprecated: list[dict] = []
        same_key: list[dict] = []
        same_value: list[dict] = []
        same_comment: list[dict] = []

        for r in rows:
            total += 1
            relpath = r["relpath"]
            key = r["key"]
            value = r["value"]
            comment = r["preceding_comment"]
            base_key = r["base_key"]

            base_relpath = re.sub(rf"^lang/{re.escape(loc)}/", "", relpath)
            rid = f"{base_relpath}\t{base_key}"

            def mkrow(category: str) -> dict:
                return {"locale": loc, "relpath": relpath, "base_key": base_key,
                        "key": key, "category": category}

            if rid not in baseline_by_key:
                row = mkrow("DEPRECATED")
                deprecated.append(row)
                csv_rows.append(row)
                continue

            b = baseline_by_key[rid]
            if key == b["base_key"]:
                row = mkrow("SAME_KEY")
                same_key.append(row)
                csv_rows.append(row)
            if value == b["value"] and not is_language_agnostic_value(value):
                row = mkrow("SAME_VALUE")
                same_value.append(row)
                csv_rows.append(row)
            if comment == b["comment"] and not is_doc_tag_only_comment(comment):
                row = mkrow("SAME_COMMENT")
                same_comment.append(row)
                csv_rows.append(row)

        if not args.csv:
            print(
                f"{loc:<4}  total={total:4}  deprecated={len(deprecated):3}  "
                f"same_key={len(same_key):4}  same_value={len(same_value):4}  "
                f"same_comment={len(same_comment):4}"
            )
            if args.sample_count > 0:
                for label, lst in (("DEPRECATED", deprecated), ("SAME_KEY", same_key),
                                   ("SAME_VALUE", same_value), ("SAME_COMMENT", same_comment)):
                    for e in lst[:args.sample_count]:
                        print(f"       [{label}] {e['relpath']} :: {e['base_key']} (key={e['key']})")

    if args.csv:
        print("locale\trelpath\tbase_key\tkey\tcategory")
        for r in csv_rows:
            print(f"{r['locale']}\t{r['relpath']}\t{r['base_key']}\t{r['key']}\t{r['category']}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
