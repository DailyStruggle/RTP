#!/usr/bin/env python3
"""import-translation-bundle.py

Cross-platform (Python 3.12) replacement for import-translation-bundle.ps1.

Reads scripts/out/bundle-<lang>.md (or --bundle-path), validates each block's
sentinel preservation and mojibake hygiene, unmasks sentinels using the per-row
legend, and patches the matching rows in scripts/out/locale-<lang>.tsv in place.

Match key: (relpath, parent_path, base_key, index).

Block validation rules (per block, in order):
  1. Sentinel preservation: the multiset of <<P#>> tokens in value_locale /
     comment_locale must equal that in the masked English value / comment.
  2. Mojibake check: value_locale and comment_locale must not contain
     CP-1252-misread UTF-8 markers or U+FFFD.
  3. No-op skip: if both locale fields are byte-identical to English, the row's
     comment gets an idempotent TODO(i18n) marker and is otherwise unchanged.

Patches are written UTF-8 (no BOM), LF endings, with a .bak copy per locale TSV.
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402

MOJIBAKE_MARKERS = ["\u00e2\u20ac", "\u00c2", "\u00c3", "\ufffd"]


def has_mojibake(s: str) -> bool:
    if not s:
        return False
    return any(m in s for m in MOJIBAKE_MARKERS)


def extract_sentinels(s: str) -> list[str]:
    if not s:
        return []
    return re.findall(r"<<P\d+>>", s)


def parse_legend(line: str) -> dict[str, str]:
    """Format: 'P0=val1; P1=val2; ...' -> {'<<P0>>': 'val1', ...}."""
    out: dict[str, str] = {}
    if not line or line == "(none)":
        return out
    parts = re.split(r";\s*(?=P\d+=)", line)
    for p in parts:
        m = re.match(r"^(P\d+)=(.*)$", p)
        if m:
            tok = f"<<{m.group(1)}>>"
            val = m.group(2).replace("\\n", "\n").replace("\\t", "\t")
            out[tok] = val
    return out


def unmask(s: str, legend: dict[str, str]) -> str:
    if not s:
        return s
    out = s
    for tok, val in legend.items():
        out = out.replace(tok, val)
    return out


def escape_tsv_comment(s: str | None) -> str:
    if s is None:
        return ""
    s = s.replace("\r\n", "\n")
    s = s.rstrip("\n")
    s = s.replace("\t", "\\t")
    s = s.replace("\n", "\\n")
    return s


def parse_blocks(content: str, fallback_locale: str) -> list[dict]:
    content = content.replace("\r\n", "\n")
    block_matches = re.findall(r"(?ms)^```bundle\s*\n(.*?)\n```\s*$", content)
    if not block_matches:
        raise SystemExit("No ```bundle blocks found.")

    blocks: list[dict] = []
    for body in block_matches:
        body_lines = body.split("\n")
        fields: dict[str, str] = {}
        i = 0
        while i < len(body_lines):
            ln = body_lines[i]
            m_block = re.match(r"^([a-z_]+):\s*\|\s*$", ln)
            if m_block:
                name = m_block.group(1)
                i += 1
                buf: list[str] = []
                while i < len(body_lines) and (body_lines[i].startswith("  ") or body_lines[i] == ""):
                    rest = body_lines[i]
                    rest = rest[2:] if len(rest) >= 2 else ""
                    buf.append(rest)
                    i += 1
                fields[name] = "\n".join(buf)
                continue
            m_kv = re.match(r"^([a-z_]+):\s*(.*)$", ln)
            if m_kv:
                fields[m_kv.group(1)] = m_kv.group(2)
                i += 1
                continue
            i += 1

        block_locale = fields.get("locale") or fallback_locale
        blocks.append({
            "locale": block_locale,
            "base_key": fields.get("base_key", ""),
            "parent_path": fields.get("parent_path", ""),
            "index": fields.get("index", ""),
            "relpath": fields.get("relpath", ""),
            "blank_before": fields.get("blank_before", ""),
            "sentinels": fields.get("sentinels", ""),
            "value_english": fields.get("value_english", ""),
            "comment_english": fields.get("comment_english", ""),
            "key_locale": fields.get("key_locale", ""),
            "value_locale": fields.get("value_locale", ""),
            "comment_locale": fields.get("comment_locale", ""),
        })
    return blocks


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--locale", default="")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--bundle-path", dest="bundle_path", default="")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--out-dir", dest="out_dir")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()

    do_all = args.all
    if not args.locale and not args.all:
        do_all = True
    if args.locale and args.all:
        raise SystemExit("Pass either --locale <code> or --all, not both.")

    if args.bundle_path:
        bundle_path = Path(args.bundle_path)
    else:
        bundle_path = out_dir / ("bundle-all.md" if do_all else f"bundle-{args.locale}.md")
    if not bundle_path.exists():
        raise SystemExit(f"Bundle not found at {bundle_path}.")

    if not do_all:
        tsv_path = out_dir / f"locale-{args.locale}.tsv"
        if not tsv_path.exists():
            raise SystemExit(f"Locale TSV not found at {tsv_path}.")

    blocks = parse_blocks(lc.read_text_no_bom(bundle_path), args.locale)
    print(f"Parsed {len(blocks)} bundle blocks from {bundle_path}.")

    tsv_store: dict[str, dict] = {}

    def load_locale_tsv(lang: str):
        if lang in tsv_store:
            return tsv_store[lang]
        path = out_dir / f"locale-{lang}.tsv"
        if not path.exists():
            return None
        lines = re.split(r"\r?\n", lc.read_text_no_bom(path))
        # header + data (drop a trailing empty line from the final newline)
        header = lines[0] if lines else "\t".join(lc.TSV_COLS)
        data = [ln for ln in lines[1:]]
        if data and data[-1] == "":
            data.pop()
        idx: dict[str, int] = {}
        for i, row in enumerate(data):
            c = row.split("\t", 7)
            if len(c) != 8:
                continue
            idx[f"{c[0]}|{c[1]}|{c[7]}|{c[3]}"] = i
        entry = {"path": path, "header": header, "data": data, "index": idx, "dirty": False}
        tsv_store[lang] = entry
        return entry

    applied = skipped_noop = err_sentinel = err_mojibake = err_no_match = 0
    err_no_locale = err_no_tsv = 0
    errors: list[str] = []

    for b in blocks:
        if not b["locale"]:
            err_no_locale += 1
            errors.append(f"[no-locale] {b['relpath']}::{b['base_key']} (missing 'locale:' field; only valid in --locale mode)")
            continue
        store = load_locale_tsv(b["locale"])
        if store is None:
            err_no_tsv += 1
            errors.append(f"[no-tsv] locale '{b['locale']}' has no scripts/out/locale-{b['locale']}.tsv; block {b['relpath']}::{b['base_key']} skipped")
            continue

        match_key = f"{b['relpath']}|{b['parent_path']}|{b['base_key']}|{b['index']}"
        if match_key not in store["index"]:
            err_no_match += 1
            errors.append(f"[no-match] {b['locale']}: {match_key}")
            continue

        val_en = sorted(extract_sentinels(b["value_english"]))
        val_lo = sorted(extract_sentinels(b["value_locale"]))
        cmt_en = sorted(extract_sentinels(b["comment_english"]))
        cmt_lo = sorted(extract_sentinels(b["comment_locale"]))
        if "|".join(val_en) != "|".join(val_lo) or "|".join(cmt_en) != "|".join(cmt_lo):
            err_sentinel += 1
            errors.append(f"[sentinel] {b['relpath']}::{b['base_key']} value:{','.join(val_en)}->{','.join(val_lo)} comment:{','.join(cmt_en)}->{','.join(cmt_lo)}")
            continue

        if has_mojibake(b["value_locale"]) or has_mojibake(b["comment_locale"]):
            err_mojibake += 1
            errors.append(f"[mojibake] {b['relpath']}::{b['base_key']}")
            continue

        is_noop = (b["value_locale"] == b["value_english"]) and (b["comment_locale"] == b["comment_english"])

        legend = parse_legend(b["sentinels"])
        new_value = unmask(b["value_locale"], legend)
        new_comment = unmask(b["comment_locale"], legend)

        if is_noop:
            todo = "# TODO(i18n): translation pending"
            if todo not in new_comment:
                new_comment = todo if not new_comment else todo + "\n" + new_comment
            skipped_noop += 1
        else:
            applied += 1

        row_idx = store["index"][match_key]
        cols = store["data"][row_idx].split("\t", 7)
        cols[2] = cols[2] if not b["key_locale"] else b["key_locale"]
        cols[4] = new_value
        cols[5] = escape_tsv_comment(new_comment)
        store["data"][row_idx] = "\t".join(cols)
        store["dirty"] = True

    print()
    print(f"Applied translations:   {applied}")
    print(f"Skipped (no-op + TODO): {skipped_noop}")
    print(f"Sentinel mismatches:    {err_sentinel}")
    print(f"Mojibake hits:          {err_mojibake}")
    print(f"Unmatched rows:         {err_no_match}")
    print(f"Missing locale field:   {err_no_locale}")
    print(f"Missing locale TSV:     {err_no_tsv}")

    if errors:
        print()
        print("Errors (block skipped, locale TSV row unchanged):")
        for e in errors:
            print(f"  {e}")

    if args.dry_run:
        print()
        print("Dry run: no changes written. Re-run without --dry-run to apply.")
        return 0

    written = 0
    for lang in sorted(tsv_store):
        store = tsv_store[lang]
        if not store["dirty"]:
            print(f"Skipped {store['path']} (no changes).")
            continue
        bak = Path(str(store["path"]) + ".bak")
        shutil.copyfile(store["path"], bak)
        lc.write_bytes_utf8(store["path"], "\n".join([store["header"]] + store["data"]) + "\n")
        written += 1
        print(f"Patched {store['path']}. Backup at {bak}.")

    print()
    print(f"Wrote {written} locale TSV file(s).")
    print("Next steps:")
    print("  python scripts/reconcile-locale-csvs.py")
    print("  python scripts/locale-files-from-csv.py")
    print('  ./gradlew :rtp-plugin:test --tests "*LocaleParityTest*"')
    print("  ./gradlew build")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
