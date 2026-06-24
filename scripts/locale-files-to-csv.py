#!/usr/bin/env python3
"""locale-files-to-csv.py

Cross-platform (Python 3.12) replacement for locale-files-to-csv.ps1 / .sh.

Collapses the per-locale YAML file tree under rtp-plugin/src/main/resources/
into ONE TSV per locale (plus one baseline TSV) under scripts/out/, so
contributors maintain a single file per language instead of ~16 separate YAMLs.

Output files (under scripts/out/):
  - baseline.tsv         <- rtp-plugin/src/main/resources/*.yml (excluding
                            plugin.yml, language.yml) + lang/shape/* + lang/vert/*
  - locale-<lang>.tsv    <- everything under lang/<lang>/ for each locale

Each TSV has the columns:
  relpath, parent_path, key, index, value, preceding_comment, blank_before, base_key

This script does NOT modify the YAML tree; it is a read-only export.
Sister script: locale-files-from-csv.py regenerates the YAML tree.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def strip_quotes(s: str) -> str:
    if s is None:
        return s
    t = s.strip()
    if len(t) >= 2:
        if (t.startswith('"') and t.endswith('"')) or (t.startswith("'") and t.endswith("'")):
            return t[1:-1]
    return t


KV_RE = re.compile(r"^([^:#\s][^:]*?)\s*:(?:\s*(.*))?$")
LANGMAP_RE = re.compile(r"^([^:#\s][^:]*?)\s*:\s*(.*)$")


def convert_yaml_file_to_rows(full_path: Path, rel_path: str) -> list[dict]:
    text = lc.read_text_no_bom(full_path)
    lines = re.split(r"\r?\n", text)

    rows: list[dict] = []
    pending_comments: list[str] = []
    pending_blank = False
    # Indentation-aware parent stack: list of {indent, key}.
    stack: list[dict] = []
    list_index = -1

    for line in lines:
        if re.match(r"^\s*$", line):
            pending_blank = True
            continue

        if re.match(r"^\s*#", line):
            c = re.sub(r"^\s+", "", line)
            pending_comments.append(c)
            continue

        m = re.match(r"^(\s*)", line)
        indent = len(m.group(1))
        stripped = line[indent:]

        is_list_item = stripped.startswith("- ") or stripped == "-"

        if not is_list_item:
            while stack and stack[-1]["indent"] >= indent:
                stack.pop()
        current_parent = ".".join(f["key"] for f in stack)

        if is_list_item:
            list_index += 1
            value = stripped[1:].lstrip()
            rows.append({
                "relpath": rel_path,
                "parent_path": current_parent,
                "key": "",
                "index": list_index,
                "value": strip_quotes(value),
                "preceding_comment": "\n".join(pending_comments),
                "blank_before": int(pending_blank),
                "base_key": "",
            })
            pending_comments.clear()
            pending_blank = False
            continue

        list_index = -1

        kv = KV_RE.match(stripped)
        if not kv:
            pending_comments.clear()
            pending_blank = False
            continue
        key = kv.group(1).strip()
        value = kv.group(2) if kv.group(2) is not None else ""

        if value == "" or re.match(r"^\s*$", value):
            rows.append({
                "relpath": rel_path,
                "parent_path": current_parent,
                "key": key,
                "index": "",
                "value": "__MAP_OR_LIST_PARENT__",
                "preceding_comment": "\n".join(pending_comments),
                "blank_before": int(pending_blank),
                "base_key": "",
            })
            pending_comments.clear()
            pending_blank = False
            stack.append({"indent": indent, "key": key})
            continue

        rows.append({
            "relpath": rel_path,
            "parent_path": current_parent,
            "key": key,
            "index": "",
            "value": strip_quotes(value),
            "preceding_comment": "\n".join(pending_comments),
            "blank_before": int(pending_blank),
            "base_key": "",
        })
        pending_comments.clear()
        pending_blank = False

    return rows


def get_reverse_lang_map(lang_yml_path: Path) -> dict[str, str]:
    rev: dict[str, str] = {}
    if not lang_yml_path.exists():
        return rev
    text = lc.read_text_no_bom(lang_yml_path)
    for line in re.split(r"\r?\n", text):
        if re.match(r"^\s*#", line):
            continue
        if re.match(r"^\s*$", line):
            continue
        m = LANGMAP_RE.match(line)
        if not m:
            continue
        base = m.group(1).strip()
        val = strip_quotes(m.group(2)).strip()
        if val == "" or val == "__MAP_OR_LIST_PARENT__":
            continue
        if val not in rev:
            rev[val] = base
    return rev


def set_base_keys(rows: list[dict], relpath_to_reverse: dict[str, dict[str, str]]) -> None:
    for r in rows:
        rev = relpath_to_reverse.get(r["relpath"]) or {}
        if r["key"]:
            r["base_key"] = rev.get(r["key"], r["key"])
        elif r["parent_path"]:
            top = r["parent_path"].split(".")[0]
            r["base_key"] = rev.get(top, top)
        else:
            r["base_key"] = ""


def write_tsv_rows(rows: list[dict], out_path: Path) -> None:
    # to-csv writes the same escaping/encoding used by locale_common.write_tsv.
    lc.write_tsv(rows, out_path)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--resources-root", dest="resources_root")
    ap.add_argument("--output-dir", dest="output_dir")
    args = ap.parse_args()

    resources_root = Path(args.resources_root) if args.resources_root else lc.resources_root()
    output_dir = Path(args.output_dir) if args.output_dir else lc.out_dir()
    output_dir.mkdir(parents=True, exist_ok=True)

    lang_root = resources_root / "lang"

    # --- Baseline: top-level *.yml (excluding plugin.yml/language.yml) ---
    baseline_rows: list[dict] = []
    baseline_file_count = 0
    baseline_files = [
        p for p in resources_root.glob("*.yml")
        if p.is_file() and p.name not in ("plugin.yml", "language.yml")
    ]
    # ADR-071 sub-tier directories whose single-file parsers live under a
    # subdirectory but ship per-locale value copies under lang/<locale>/<sub>/.
    # Fold them into the baseline with their "<sub>/<file>.yml" relpath so the
    # locale TSVs (scanned recursively below) have a baseline counterpart and
    # reconcile does not drop their rows.
    for sub in ("advanced", "messages"):
        sub_dir = resources_root / sub
        if sub_dir.is_dir():
            baseline_files.extend(p for p in sub_dir.glob("*.yml") if p.is_file())
    for f in sorted(baseline_files, key=lambda p: str(p.relative_to(resources_root)).replace("\\", "/")):
        rel = str(f.relative_to(resources_root)).replace("\\", "/")
        baseline_file_count += 1
        baseline_rows.extend(convert_yaml_file_to_rows(f, rel))

    baseline_out = output_dir / "baseline.tsv"
    set_base_keys(baseline_rows, {})
    write_tsv_rows(baseline_rows, baseline_out)
    print(f"Wrote {len(baseline_rows)} rows from {baseline_file_count} files -> {baseline_out}")

    # --- Per-locale: everything under lang/<locale>/ ---
    locale_dirs = sorted((p for p in lang_root.iterdir() if p.is_dir()), key=lambda p: p.name)
    for ld in locale_dirs:
        locale = ld.name
        # ADR-071: lang/advanced/ holds the baseline advanced-tier lang maps, not a
        # locale; its value files are folded into baseline above, so skip it here.
        if locale == "advanced":
            continue
        if locale in ("shape", "vert"):
            for f in sorted((p for p in ld.rglob("*.yml") if p.is_file()), key=lambda p: str(p)):
                rel = str(f.relative_to(resources_root)).replace("\\", "/")
                baseline_file_count += 1
                baseline_rows.extend(convert_yaml_file_to_rows(f, rel))
            continue

        locale_rows: list[dict] = []
        locale_file_count = 0
        rel_to_reverse: dict[str, dict[str, str]] = {}
        for f in (p for p in ld.rglob("*.yml") if p.is_file()):
            rel = str(f.relative_to(resources_root)).replace("\\", "/")
            if f.name.endswith(".lang.yml"):
                continue
            lang_sibling = f.with_name(f.name[:-len(".yml")] + ".lang.yml")
            if lang_sibling.exists():
                rel_to_reverse[rel] = get_reverse_lang_map(lang_sibling)

        def keep(f: Path) -> bool:
            is_lang_map = f.name.endswith(".lang.yml")
            if not is_lang_map:
                return True
            parent = f.parent.name
            return parent in ("shape", "vert")

        for f in sorted((p for p in ld.rglob("*.yml") if p.is_file() and keep(p)), key=lambda p: str(p)):
            rel = str(f.relative_to(resources_root)).replace("\\", "/")
            locale_file_count += 1
            locale_rows.extend(convert_yaml_file_to_rows(f, rel))

        set_base_keys(locale_rows, rel_to_reverse)
        locale_out = output_dir / f"locale-{locale}.tsv"
        write_tsv_rows(locale_rows, locale_out)
        print(f"Wrote {len(locale_rows)} rows from {locale_file_count} files -> {locale_out}")

    # Rewrite baseline with shape/vert folded in (still identity).
    set_base_keys(baseline_rows, {})
    write_tsv_rows(baseline_rows, baseline_out)
    print(f"Final baseline: {len(baseline_rows)} rows from {baseline_file_count} files -> {baseline_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
