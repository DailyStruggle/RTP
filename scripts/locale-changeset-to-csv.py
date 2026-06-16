#!/usr/bin/env python3
"""locale-changeset-to-csv.py

Cross-platform (Python 3.12) replacement for locale-changeset-to-csv.ps1 / .sh.

SECONDARY component of the locale config pipeline (the "changeset" workflow).
Exports a single wide, spreadsheet-friendly comma-separated scripts/out/changeset.csv
with one row per changed baseline key and a triple of columns per language
(translated value, its comment, and the localized key name).

Row selection:
  --keys <list...>     Explicit selection. Each entry is "<relpath>",
                       "<relpath>:<base_key>", or "<base_key>".
  --untranslated-only  (default when --keys omitted) include only rows whose
                       value/comment/key in at least one locale is still a
                       freshly-seeded English placeholder.
  --all                include every translatable baseline value row.

Read-only with respect to the YAML tree and per-locale TSVs; writes only
scripts/out/changeset.csv.
"""

from __future__ import annotations

import argparse
import fnmatch
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def is_translatable_value_row(r: dict) -> bool:
    if r["value"] == "__MAP_OR_LIST_PARENT__":
        return False
    if r["key"] == "" and r["index"] == "":
        return False
    relpath = r["relpath"]
    if (fnmatch.fnmatchcase(relpath, "lang/*.lang.yml")
            and not fnmatch.fnmatchcase(relpath, "lang/shape/*")
            and not fnmatch.fnmatchcase(relpath, "lang/vert/*")):
        return False
    return True


def matches_keys_selection(r: dict, sel: list[str]) -> bool:
    for entry in sel:
        if ":" in entry:
            parts = entry.split(":", 1)
            if r["relpath"] == parts[0] and r["base_key"] == parts[1]:
                return True
        elif fnmatch.fnmatchcase(entry, "*.yml"):
            if r["relpath"] == entry:
                return True
        else:
            if r["base_key"] == entry:
                return True
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", dest="out_dir")
    ap.add_argument("--keys", nargs="*", dest="keys")
    ap.add_argument("--untranslated-only", action="store_true", dest="untranslated_only")
    ap.add_argument("--all", action="store_true", dest="all_rows")
    ap.add_argument("--out-file", dest="out_file")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    out_file = Path(args.out_file) if args.out_file else out_dir / "changeset.csv"
    keys = args.keys
    untranslated_only = args.untranslated_only
    all_rows = args.all_rows

    baseline_csv = out_dir / "baseline.tsv"
    if not baseline_csv.exists():
        raise SystemExit(f"baseline.tsv not found at {baseline_csv}. Run locale-files-to-csv.py first.")

    baseline = lc.read_tsv(baseline_csv)
    baseline_by_path: dict[str, list[dict]] = {}
    for r in baseline:
        baseline_by_path.setdefault(r["relpath"], []).append(r)

    lang_root = lc.repo_root() / "rtp-plugin" / "src" / "main" / "resources" / "lang"
    locales = sorted(p.name for p in lang_root.iterdir() if p.is_dir() and p.name not in ("shape", "vert"))

    locale_idx_by_loc: dict[str, dict[str, dict]] = {}
    langmaps_by_loc: dict[str, dict[str, dict[str, str]]] = {}
    for loc in locales:
        locale_csv = out_dir / f"locale-{loc}.tsv"
        if not locale_csv.exists():
            continue
        rows = lc.read_tsv(locale_csv)
        idx: dict[str, dict] = {}
        for r in rows:
            k = f"{r['relpath']}|{r['parent_path']}|{r['key']}|{r['index']}"
            idx[k] = r
        locale_idx_by_loc[loc] = idx
        langmaps_by_loc[loc] = lc.build_langmaps(rows, loc)

    def get_locale_row(loc: str, br: dict, base_path: str) -> dict | None:
        idx = locale_idx_by_loc.get(loc)
        if idx is None:
            return None
        loc_path = lc.get_locale_relpath(base_path, loc)
        langmap = None
        if not base_path.startswith("lang/") and base_path in langmaps_by_loc[loc]:
            langmap = langmaps_by_loc[loc][base_path]
        eff_key = br["key"]
        if langmap is not None and br["key"] != "" and br["key"] in langmap:
            eff_key = langmap[br["key"]]
        eff_parent = br["parent_path"]
        if langmap is not None and br["parent_path"] != "" and br["parent_path"] in langmap:
            eff_parent = langmap[br["parent_path"]]
        lk = f"{loc_path}|{eff_parent}|{eff_key}|{br['index']}"
        return idx.get(lk)

    if not keys and not all_rows:
        untranslated_only = True

    out_rows: list[dict] = []
    for base_path in baseline_by_path:
        for br in baseline_by_path[base_path]:
            if not is_translatable_value_row(br):
                continue
            if keys and not matches_keys_selection(br, keys):
                continue

            locale_vals: dict[str, str] = {}
            locale_comments: dict[str, str] = {}
            locale_keys: dict[str, str] = {}
            any_untranslated = False
            for loc in locales:
                lr = get_locale_row(loc, br, base_path)
                v = "" if lr is None else str(lr["value"])
                c = "" if lr is None else str(lr["preceding_comment"])
                k = "" if lr is None else str(lr["key"])
                locale_vals[loc] = v
                locale_comments[loc] = c
                locale_keys[loc] = k
                if v == "" or v == br["value"]:
                    any_untranslated = True
                if br["preceding_comment"] != "" and (c == "" or c == br["preceding_comment"]):
                    any_untranslated = True
                if br["key"] != "" and (k == "" or k == br["key"]):
                    any_untranslated = True

            if untranslated_only and not any_untranslated:
                continue

            row: dict = {
                "relpath": base_path,
                "parent_path": br["parent_path"],
                "base_key": br["base_key"],
                "index": br["index"],
                "english": br["value"],
                "english_comment": lc.to_comment_cell(br["preceding_comment"]),
            }
            for loc in locales:
                row[loc] = locale_vals[loc]
                row[f"{loc}_comment"] = lc.to_comment_cell(locale_comments[loc])
                row[f"{loc}_key"] = locale_keys[loc]
            out_rows.append(row)

    header = ["relpath", "parent_path", "base_key", "index", "english", "english_comment"]
    for loc in locales:
        header += [loc, f"{loc}_comment", f"{loc}_key"]
    lc.write_csv(out_rows, header, out_file)

    print(f"Wrote {len(out_rows)} changeset row(s) for {len(locales)} locale(s) -> {out_file}")
    if len(out_rows) == 0:
        print("Nothing to translate with the current selection.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
