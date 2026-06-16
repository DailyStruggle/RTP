#!/usr/bin/env python3
"""locale-changeset-from-csv.py

Cross-platform (Python 3.12) replacement for locale-changeset-from-csv.ps1 / .sh.

Reads the translated scripts/out/changeset.csv and propagates each per-language
value column, its paired '<locale>_comment' column, AND its paired
'<locale>_key' column back into the corresponding scripts/out/locale-<lang>.tsv
row (matched by the same langmap logic the reconcile step uses), seeding rows
that do not exist yet.

Options:
  --include-english    Also write per-language cells that equal the english
                       column.
  --untranslated-only  Only overwrite a locale TSV row when its CURRENT value is
                       still untranslated (empty or equal to the English baseline).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", dest="out_dir")
    ap.add_argument("--in-file", dest="in_file")
    ap.add_argument("--include-english", action="store_true", dest="include_english")
    ap.add_argument("--untranslated-only", action="store_true", dest="untranslated_only")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    in_file = Path(args.in_file) if args.in_file else out_dir / "changeset.csv"
    include_english = args.include_english
    untranslated_only = args.untranslated_only

    if not in_file.exists():
        raise SystemExit(f"changeset.csv not found at {in_file}. Run locale-changeset-to-csv.py first.")

    csv = lc.read_csv(in_file)
    header = csv["Header"]
    fixed_cols = ["relpath", "parent_path", "base_key", "index", "english", "english_comment"]
    for c in fixed_cols:
        if c not in header:
            raise SystemExit(
                f"changeset.csv is missing required column '{c}'. Expected header: {', '.join(fixed_cols)}, "
                f"<locale>, <locale>_comment, ...")
    locales = [h for h in header
               if h not in fixed_cols and not h.endswith("_comment") and not h.endswith("_key")]
    if not locales:
        raise SystemExit("changeset.csv has no per-locale columns to apply.")

    total_applied = 0
    missing_by_loc: dict[str, list[str]] = {}

    for loc in locales:
        locale_csv = out_dir / f"locale-{loc}.tsv"
        if not locale_csv.exists():
            print(f"Skipping {loc} (no locale-{loc}.tsv)")
            continue
        rows = lc.read_tsv(locale_csv)

        idx: dict[str, dict] = {}
        for r in rows:
            k = f"{r['relpath']}|{r['parent_path']}|{r['key']}|{r['index']}"
            idx[k] = r
        langmaps = lc.build_langmaps(rows, loc)

        applied = 0
        skipped = 0
        seeded = 0
        missing: list[str] = []

        comment_col = f"{loc}_comment"
        has_comment_col = comment_col in header
        key_col = f"{loc}_key"
        has_key_col = key_col in header

        for cr in csv["Rows"]:
            cell = str(cr.get(loc, ""))
            english = str(cr.get("english", ""))
            comment_cell = lc.from_comment_cell(str(cr.get(comment_col, ""))) if has_comment_col else ""
            english_comment = lc.from_comment_cell(str(cr.get("english_comment", "")))
            key_cell = str(cr.get(key_col, "")) if has_key_col else ""
            english_key = str(cr.get("base_key", ""))
            has_value = cell != "" and (include_english or cell != english)
            has_comment = comment_cell != "" and (include_english or comment_cell != english_comment)
            has_key = key_cell != "" and (include_english or key_cell != english_key)
            if not has_value and not has_comment and not has_key:
                continue

            base_path = str(cr.get("relpath", ""))
            loc_path = lc.get_locale_relpath(base_path, loc)
            langmap = None
            if not base_path.startswith("lang/") and base_path in langmaps:
                langmap = langmaps[base_path]

            base_key = str(cr.get("base_key", ""))
            base_parent = str(cr.get("parent_path", ""))
            index = str(cr.get("index", ""))

            base_leaf_key = "" if index != "" else base_key

            eff_key = base_leaf_key
            if langmap is not None and base_leaf_key != "" and base_leaf_key in langmap:
                eff_key = langmap[base_leaf_key]
            eff_parent = base_parent
            if langmap is not None and base_parent != "" and base_parent in langmap:
                eff_parent = langmap[base_parent]

            lk = f"{loc_path}|{eff_parent}|{eff_key}|{index}"
            if lk in idx:
                row = idx[lk]
                row_applied = False
                if has_value:
                    current = str(row["value"])
                    if untranslated_only and current != "" and current != english:
                        skipped += 1
                    elif current != cell:
                        row["value"] = cell
                        row_applied = True
                if has_comment:
                    current_comment = str(row["preceding_comment"])
                    if untranslated_only and current_comment != "" and current_comment != english_comment:
                        skipped += 1
                    elif current_comment != comment_cell:
                        row["preceding_comment"] = comment_cell
                        row_applied = True
                if has_key:
                    current_key = str(row["key"])
                    if untranslated_only and current_key != "" and current_key != english_key:
                        skipped += 1
                    elif current_key != key_cell:
                        row["key"] = key_cell
                        row_applied = True
                if row_applied:
                    applied += 1
            else:
                seed_key = eff_key
                if has_key:
                    seed_key = key_cell
                seed_value = cell if has_value else english
                seed_comment = comment_cell if has_comment else english_comment
                new_row = {
                    "relpath": loc_path,
                    "parent_path": eff_parent,
                    "key": seed_key,
                    "index": index,
                    "value": seed_value,
                    "preceding_comment": seed_comment,
                    "blank_before": "",
                    "base_key": base_key,
                }
                rows.append(new_row)
                idx[lk] = new_row
                seed_lk = f"{loc_path}|{eff_parent}|{seed_key}|{index}"
                idx[seed_lk] = new_row
                seeded += 1
                applied += 1

        if applied > 0:
            lc.write_tsv(rows, locale_csv)
        total_applied += applied
        missing_by_loc[loc] = missing
        if untranslated_only:
            print(f"{loc}: applied={applied} seeded={seeded} skipped(already-translated)={skipped} missing={len(missing)}")
        else:
            print(f"{loc}: applied={applied} seeded={seeded} missing={len(missing)}")

    print(f"Done. Applied {total_applied} translation(s) across {len(locales)} locale(s).")

    any_missing = False
    for loc in locales:
        m = missing_by_loc.get(loc)
        if m:
            if not any_missing:
                print("")
                print("Rows not found in some locale TSVs (run reconcile-locale-csvs.py to seed, then re-run):")
                any_missing = True
            for entry in m:
                print(f"  [{loc}] {entry}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
