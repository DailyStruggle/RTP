#!/usr/bin/env python3
"""locale-files-from-csv.py

Cross-platform (Python 3.12) replacement for locale-files-from-csv.ps1 / .sh.

Regenerates the rtp-plugin/src/main/resources/ YAML tree from the per-locale
TSVs under scripts/out/ (baseline.tsv + locale-<lang>.tsv).

Options:
  --only <patterns...>  Regenerate only files whose relpath / leaf name /
                        baseline-equivalent relpath matches one of the wildcard
                        patterns (e.g. --only "integrations.yml" "lang/de/*").
  --verify              Scan every regenerated file for AI mojibake markers and
                        U+FFFD; exit non-zero if any are found.
"""

from __future__ import annotations

import argparse
import fnmatch
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402

COLS = lc.TSV_COLS


def test_in_scope(relpath: str, only: list[str] | None) -> bool:
    if not only:
        return True
    leaf = relpath.rsplit("/", 1)[-1]
    base_rel = relpath
    m = re.match(r"^lang/[a-z]{2,3}/(shape|vert)/(.+)$", relpath)
    if m:
        base_rel = f"lang/{m.group(1)}/{m.group(2)}"
    else:
        m = re.match(r"^lang/[a-z]{2,3}/(.+)$", relpath)
        if m:
            base_rel = m.group(1)
    for p in only:
        if fnmatch.fnmatchcase(relpath, p) or fnmatch.fnmatchcase(leaf, p) or fnmatch.fnmatchcase(base_rel, p):
            return True
    return False


def test_is_bare_scalar(v: str) -> bool:
    if v is None or v == "":
        return False
    if re.match(r"^-?\d+$", v):
        return True
    if re.match(r"^-?\d+\.\d+$", v):
        return True
    if re.match(r"^(true|false)$", v):
        return True
    if re.match(r"^(null|~)$", v):
        return True
    return False


def format_scalar(v: str) -> str:
    if v == "__MAP_OR_LIST_PARENT__":
        raise ValueError("format_scalar called with parent sentinel")
    if v == "":
        return '""'
    if test_is_bare_scalar(v):
        return v
    escaped = v.replace("\\", "\\\\").replace('"', '\\"')
    return '"' + escaped + '"'


def emit_comment(parts: list[str], comment: str, indent: str) -> None:
    if not comment:
        return
    for line in comment.split("\n"):
        if line == "":
            parts.append("\n")
        else:
            parts.append(indent + line + "\n")


def resolve_baseline_relpath(relpath: str) -> str:
    m = re.match(r"^lang/[a-z]{2,3}/(shape|vert)/(.+)$", relpath)
    if m:
        return f"lang/{m.group(1)}/{m.group(2)}"
    m = re.match(r"^lang/[a-z]{2,3}/(.+)$", relpath)
    if m:
        return m.group(1)
    return relpath


LANG_MAP_STEMS = ["config", "economy", "effects", "logging", "messages",
                  "performance", "regions", "safety", "worlds"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--resources-root", dest="resources_root")
    ap.add_argument("--input-dir", dest="input_dir")
    ap.add_argument("--only", nargs="*", dest="only")
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()

    resources_root = Path(args.resources_root) if args.resources_root else lc.resources_root()
    input_dir = Path(args.input_dir) if args.input_dir else lc.out_dir()
    only = args.only
    verify = args.verify

    if not input_dir.exists():
        raise SystemExit(f"Input directory not found: {input_dir}")

    csv_files = [
        p for p in sorted(input_dir.glob("*.tsv"), key=lambda x: x.name)
        if p.name == "baseline.tsv" or re.match(r"^locale-[a-z]{2,3}\.tsv$", p.name)
    ]
    if not csv_files:
        raise SystemExit(f"No baseline.tsv or locale-*.tsv found in {input_dir}")

    grouped: dict[str, list[dict]] = {}
    for csv in csv_files:
        for r in lc.read_tsv(csv):
            grouped.setdefault(r["relpath"], []).append(r)

    mojibake_hits: list[str] = []
    written_count = 0

    # Build baseline comment lookup keyed by "<relpath>||<base_key>||<parent>||<index>".
    baseline_comment_lookup: dict[str, str] = {}
    for relpath, rlist in grouped.items():
        if re.match(r"^lang/[a-z]{2,3}/", relpath):
            continue
        for r in rlist:
            bk = r["base_key"] if r["base_key"] else r["key"]
            lk = f"{r['relpath']}||{bk}||{r['parent_path']}||{r['index']}"
            baseline_comment_lookup.setdefault(lk, r["preceding_comment"])

    for relpath, file_rows in grouped.items():
        if not test_in_scope(relpath, only):
            continue
        parts: list[str] = []
        first_row = True

        m_loc = re.match(r"^lang/([a-z]{2,3})/", relpath)
        is_locale = bool(m_loc)
        baseline_rel = resolve_baseline_relpath(relpath)

        for r in file_rows:
            parent = r["parent_path"]
            key = r["key"]
            idx = r["index"]
            val = r["value"]
            comment = r["preceding_comment"]
            if is_locale and not comment:
                bk = r["base_key"] if r["base_key"] else r["key"]
                blk = f"{baseline_rel}||{bk}||{parent}||{idx}"
                if blk in baseline_comment_lookup:
                    comment = baseline_comment_lookup[blk]
            blank = (r["blank_before"] == "1" or r["blank_before"] == 1)

            depth = 0 if not parent else len(parent.split("."))
            indent = "  " * depth

            if blank and not first_row:
                parts.append("\n")

            emit_comment(parts, comment, indent)

            if val == "__MAP_OR_LIST_PARENT__":
                parts.append(indent + key + ":\n")
            elif idx is not None and str(idx) != "":
                parts.append(indent + "- " + format_scalar(val) + "\n")
            else:
                parts.append(indent + key + ": " + format_scalar(val) + "\n")

            first_row = False

        out_full = resources_root.joinpath(*relpath.split("/"))
        out_full.parent.mkdir(parents=True, exist_ok=True)
        content = "".join(parts)
        if verify:
            hits = lc.find_mojibake(content)
            if hits:
                mojibake_hits.append(f"{relpath}: {', '.join(hits)}")
        lc.write_bytes_utf8(out_full, content)
        written_count += 1

    # --- Synthesize <file>.lang.yml rename-maps from (base_key, key) pairs ---
    lang_synth_count = 0
    for relpath in list(grouped.keys()):
        name = relpath.rsplit("/", 1)[-1]
        if name.endswith(".lang.yml"):
            continue
        if not name.endswith(".yml"):
            continue
        stem = name[:-len(".yml")]
        if stem not in LANG_MAP_STEMS:
            continue
        if not test_in_scope(relpath, only):
            continue
        d = relpath.rsplit("/", 1)[0] if "/" in relpath else ""
        if relpath.startswith("lang/"):
            # Locale value file: the lang map is its direct sibling.
            lang_rel = f"{d}/{stem}.lang.yml"
        elif d == "":
            # Baseline root value file: lang map lives at lang/<stem>.lang.yml.
            lang_rel = f"lang/{stem}.lang.yml"
        else:
            # Baseline sub-tier value file (e.g. advanced/<file>.yml): the lang map
            # lives under lang/<subdir>/, not in the value file's own directory.
            lang_rel = f"lang/{d}/{stem}.lang.yml"

        pairs: dict[str, str] = {}
        for r in grouped[relpath]:
            if not r["key"]:
                continue
            if r["parent_path"]:
                continue
            b = r["base_key"] if r["base_key"] else r["key"]
            if b not in pairs:
                pairs[b] = r["key"]
        if not pairs:
            continue

        lparts: list[str] = []
        lparts.append(f"# --- Language Mapping: {name} ---\n")
        lparts.append("# Maps internal baseline keys (left) to user-visible key names used in\n")
        lparts.append(f"# {name} (right). Generated from the per-locale TSV; edit the TSV's\n")
        lparts.append("# base_key/key columns and regenerate, do not hand-edit this file.\n")
        for k, v in pairs.items():
            lparts.append(k + ": " + format_scalar(str(v)) + "\n")

        out_full = resources_root.joinpath(*lang_rel.split("/"))
        out_full.parent.mkdir(parents=True, exist_ok=True)
        lang_content = "".join(lparts)
        if verify:
            hits = lc.find_mojibake(lang_content)
            if hits:
                mojibake_hits.append(f"{lang_rel}: {', '.join(hits)}")
        lc.write_bytes_utf8(out_full, lang_content)
        lang_synth_count += 1

    print(f"Wrote {written_count} files from {len(csv_files)} CSV input(s) under {input_dir} "
          f"(+ {lang_synth_count} synthesized .lang.yml)")
    if only:
        print(f"  (scoped by --only: {', '.join(only)})")
    if verify:
        if mojibake_hits:
            print("")
            print("Mojibake detected in regenerated output:")
            for h in mojibake_hits:
                print(f"  {h}")
            raise SystemExit(
                f"Mojibake found in {len(mojibake_hits)} file(s); fix the offending TSV cell(s) and regenerate.")
        else:
            print("Verify: no mojibake markers found in regenerated output.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
