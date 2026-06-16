#!/usr/bin/env python3
"""reconcile-locale-csvs.py

Cross-platform (Python 3.12) replacement for reconcile-locale-csvs.ps1 / .sh.

For each scripts/out/locale-<lang>.tsv, ensure it has the same set of
(relpath-shape, parent_path, key, index) rows as scripts/out/baseline.tsv:
  - Add baseline rows missing from the locale (seeded with the baseline value,
    comment and blank_before).
  - Drop locale rows whose (parent_path, key, index) tuple is absent from the
    corresponding baseline file.
  - Preserve existing locale values/comments where the row already matches.

Row identity key: (parent_path, key, index)
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", dest="out_dir")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    baseline_csv = out_dir / "baseline.tsv"
    if not baseline_csv.exists():
        raise SystemExit(f"baseline.tsv not found at {baseline_csv}. Run locale-files-to-csv.py first.")

    baseline = lc.read_tsv(baseline_csv)

    # Group baseline by relpath (preserve order).
    baseline_by_path: dict[str, list[dict]] = {}
    for r in baseline:
        baseline_by_path.setdefault(r["relpath"], []).append(r)

    # Pre-pass: ensure baseline lang/<file>.lang.yml has an identity row for
    # every top-level key in baseline <file>.yml.
    value_files = [k for k in baseline_by_path
                   if not k.startswith("lang/") and k not in ("plugin.yml", "language.yml")]
    for vf in value_files:
        lang_relpath = "lang/" + re.sub(r"\.yml$", ".lang.yml", vf)
        if lang_relpath not in baseline_by_path:
            continue
        existing: dict[str, bool] = {}
        for lr in baseline_by_path[lang_relpath]:
            if lr["key"] != "" and lr["parent_path"] == "" and lr["index"] == "":
                existing[lr["key"]] = True
        for vr in baseline_by_path[vf]:
            if vr["parent_path"] != "" or vr["key"] == "" or vr["index"] != "":
                continue
            if vr["key"] in existing:
                continue
            baseline_by_path[lang_relpath].append({
                "relpath": lang_relpath,
                "parent_path": "",
                "key": vr["key"],
                "index": "",
                "value": vr["key"],
                "preceding_comment": "",
                "blank_before": "0",
                "base_key": vr["key"],
            })
            existing[vr["key"]] = True

    # Rewrite baseline.tsv to include the added identity rows.
    baseline_out: list[dict] = []
    for p in baseline_by_path:
        baseline_out.extend(baseline_by_path[p])
    lc.write_tsv(baseline_out, baseline_csv)

    lang_root = lc.repo_root() / "rtp-plugin" / "src" / "main" / "resources" / "lang"
    locales = sorted(p.name for p in lang_root.iterdir() if p.is_dir() and p.name not in ("shape", "vert"))

    for loc in locales:
        locale_csv = out_dir / f"locale-{loc}.tsv"
        if not locale_csv.exists():
            print(f"Skipping {loc} (no TSV)")
            continue
        locale_rows = lc.read_tsv(locale_csv)

        locale_idx: dict[str, dict] = {}
        for r in locale_rows:
            k = f"{r['relpath']}|{r['parent_path']}|{r['key']}|{r['index']}"
            locale_idx[k] = r

        langmaps = lc.build_langmaps(locale_rows, loc)

        out: list[dict] = []
        added = 0
        kept = 0

        for base_path in baseline_by_path:
            loc_path = lc.get_locale_relpath(base_path, loc)
            is_value_file = not base_path.startswith("lang/")
            langmap = langmaps.get(base_path) if (is_value_file and base_path in langmaps) else None

            for br in baseline_by_path[base_path]:
                eff_key = br["key"]
                if langmap is not None and br["key"] != "" and br["key"] in langmap:
                    eff_key = langmap[br["key"]]
                eff_parent = br["parent_path"]
                if langmap is not None and br["parent_path"] != "" and br["parent_path"] in langmap:
                    eff_parent = langmap[br["parent_path"]]

                base_placeholders = set(re.findall(r"\[[^\]\s]+\]", br["value"]))

                def value_has_stale_placeholder(v: str) -> bool:
                    for m in re.findall(r"\[[^\]\s]+\]", v):
                        if m not in base_placeholders:
                            return True
                    return False

                lk_eff = f"{loc_path}|{eff_parent}|{eff_key}|{br['index']}"
                lk_base = f"{loc_path}|{br['parent_path']}|{br['key']}|{br['index']}"
                if lk_eff in locale_idx:
                    existing = locale_idx[lk_eff]
                    use_value = existing["value"]
                    if value_has_stale_placeholder(use_value):
                        use_value = br["value"]
                    use_comment = br["preceding_comment"] if not existing["preceding_comment"] else existing["preceding_comment"]
                    out.append({
                        "relpath": loc_path,
                        "parent_path": eff_parent,
                        "key": eff_key,
                        "index": br["index"],
                        "value": use_value,
                        "preceding_comment": use_comment,
                        "blank_before": br["blank_before"],
                        "base_key": br["base_key"],
                    })
                    kept += 1
                elif lk_base in locale_idx and eff_key != br["key"]:
                    existing = locale_idx[lk_base]
                    use_value = existing["value"]
                    if value_has_stale_placeholder(use_value):
                        use_value = br["value"]
                    use_comment = br["preceding_comment"] if not existing["preceding_comment"] else existing["preceding_comment"]
                    out.append({
                        "relpath": loc_path,
                        "parent_path": eff_parent,
                        "key": eff_key,
                        "index": br["index"],
                        "value": use_value,
                        "preceding_comment": use_comment,
                        "blank_before": br["blank_before"],
                        "base_key": br["base_key"],
                    })
                    kept += 1
                else:
                    out.append({
                        "relpath": loc_path,
                        "parent_path": eff_parent,
                        "key": eff_key,
                        "index": br["index"],
                        "value": br["value"],
                        "preceding_comment": br["preceding_comment"],
                        "blank_before": br["blank_before"],
                        "base_key": br["base_key"],
                    })
                    added += 1

        dropped = 0
        for r in locale_rows:
            base_path = lc.get_baseline_relpath(r["relpath"], loc)
            if base_path is None or base_path not in baseline_by_path:
                dropped += 1
                continue
            found = False
            for br in baseline_by_path[base_path]:
                if br["parent_path"] == r["parent_path"] and br["key"] == r["key"] and br["index"] == r["index"]:
                    found = True
                    break
            if not found:
                dropped += 1

        lc.write_tsv(out, locale_csv)
        print(f"{loc}: kept={kept} added={added} dropped={dropped} total={len(out)}")

    print("Done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
