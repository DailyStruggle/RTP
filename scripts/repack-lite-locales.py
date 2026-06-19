#!/usr/bin/env python3
"""repack-lite-locales.py

Cross-platform (Python 3.12) replacement for repack-lite-locales.ps1.

Build a Lite-shaped lang/ tree under rtp-plugin/src/lite/resources/lang/ by
pruning rtp-plugin/src/main/resources/lang/<loc>/ down to the keys Lite's
reduced English baselines actually carry.

Run order in the locale pipeline:
  1. python scripts/locale-files-to-csv.py
  2. python scripts/reconcile-locale-csvs.py
  3. (translate scripts/out/locale-<lang>.tsv)
  4. python scripts/locale-files-from-csv.py
  5. python scripts/repack-lite-locales.py     <-- this script
  6. ./gradlew :rtp-plugin:shadowLiteJar

Notes:
- The set of files to prune is discovered from rtp-plugin/src/lite/resources/
  (top-level *.yml). Files Lite does not ship are simply not emitted.
- messages.yml and lang/<loc>/messages.lang.yml are copied through unchanged
  (per ADR-024 lite ships the full messages.yml).
- Lookup chain: localeLangMap.get(key) -> baselineLangMap.get(key) -> identity.
- All output is UTF-8 (no BOM), LF line endings.
"""

from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402

_TOPKEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_\-]*)\s*:(\s|$)")
_LANGROW_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_\-]*)\s*:\s*(.+?)\s*$")
_LANGKEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_\-]*)\s*:")


def _read_lines(path: Path) -> list[str]:
    # Match .NET ReadAllLines: split on CRLF/CR/LF, drop a single trailing
    # empty element produced by a final newline.
    text = lc.read_text_no_bom(path)
    lines = re.split(r"\r\n|\r|\n", text)
    if lines and lines[-1] == "":
        lines.pop()
    return lines


def _write_lf_utf8(path: Path, text: str) -> None:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    if not normalized.endswith("\n"):
        normalized += "\n"
    lc.write_bytes_utf8(path, normalized)


def get_top_level_keys(yaml_path: Path) -> set[str]:
    out: set[str] = set()
    if not yaml_path.exists():
        return out
    for ln in _read_lines(yaml_path):
        m = _TOPKEY_RE.match(ln)
        if m:
            out.add(m.group(1))
    return out


def read_locale_lang_map(lang_map_path: Path) -> dict[str, str]:
    """translatedKey -> baseKey."""
    locale_to_base: dict[str, str] = {}
    if not lang_map_path.exists():
        return locale_to_base
    for ln in _read_lines(lang_map_path):
        if ln.lstrip().startswith("#"):
            continue
        m = _LANGROW_RE.match(ln)
        if m:
            base = m.group(1)
            rhs = m.group(2)
            rhs = re.sub(r"\s+#.*$", "", rhs).strip()
            rhs = rhs.strip('"').strip("'")
            if rhs and rhs.strip():
                locale_to_base[rhs] = base
    return locale_to_base


def repack_lang_map(src: Path, dst: Path, allowed: set[str]) -> None:
    out: list[str] = []
    for ln in _read_lines(src):
        trimmed = ln.lstrip()
        if ln.strip() == "" or trimmed.startswith("#"):
            out.append(ln)
            continue
        m = _LANGKEY_RE.match(ln)
        if m:
            if m.group(1) in allowed:
                out.append(ln)
            continue
        out.append(ln)
    _write_lf_utf8(dst, "\n".join(out) + ("\n" if out else ""))


def repack_locale_value_file(src: Path, dst: Path, locale_to_base: dict[str, str], allowed: set[str]) -> None:
    lines = _read_lines(src)

    top_idx: list[int] = []
    top_key: dict[int, str] = {}
    for i, ln in enumerate(lines):
        m = _TOPKEY_RE.match(ln)
        if m:
            top_idx.append(i)
            top_key[i] = m.group(1)

    block_start: dict[int, int] = {}
    for k, i in enumerate(top_idx):
        prev_top_end = -1 if k == 0 else top_idx[k - 1]
        s = i
        while s - 1 > prev_top_end:
            cand = lines[s - 1]
            if cand.strip() == "" or cand.lstrip().startswith("#"):
                s -= 1
            else:
                break
        while s < i and lines[s].strip() == "":
            s += 1
        block_start[i] = s

    block_end: dict[int, int] = {}
    for k, i in enumerate(top_idx):
        if k < len(top_idx) - 1:
            block_end[i] = block_start[top_idx[k + 1]] - 1
        else:
            block_end[i] = len(lines) - 1

    out: list[str] = []
    first_top_idx = top_idx[0] if top_idx else len(lines)
    first_block_start = block_start[first_top_idx] if top_idx else len(lines)
    for i in range(first_block_start):
        out.append(lines[i])

    for idx in top_idx:
        locale_key = top_key[idx]
        base_key = locale_to_base.get(locale_key, locale_key)
        if base_key not in allowed:
            continue
        for j in range(block_start[idx], block_end[idx] + 1):
            out.append(lines[j])

    _write_lf_utf8(dst, "\n".join(out) + ("\n" if out else ""))


def main() -> int:
    repo_root = lc.repo_root()
    pro_main = repo_root / "rtp-plugin" / "src" / "main" / "resources"
    lite_main = repo_root / "rtp-plugin" / "src" / "lite" / "resources"
    pro_lang_dir = pro_main / "lang"
    lite_lang_out = lite_main / "lang"

    if not lite_main.exists():
        raise SystemExit(f"Lite resources dir not found: {lite_main}")
    if not pro_lang_dir.exists():
        raise SystemExit(f"Pro lang dir not found: {pro_lang_dir}")

    lite_top_level = sorted(
        p.name for p in lite_main.glob("*.yml") if p.is_file() and p.name != "plugin.yml"
    )

    lite_regions: list[str] = []
    lite_regions_dir = lite_main / "regions"
    if lite_regions_dir.exists():
        lite_regions = sorted(f"regions/{p.name}" for p in lite_regions_dir.glob("*.yml") if p.is_file())

    if lite_lang_out.exists():
        shutil.rmtree(lite_lang_out)
    lite_lang_out.mkdir(parents=True, exist_ok=True)

    locales = sorted(
        p.name for p in pro_lang_dir.iterdir()
        if p.is_dir() and p.name not in ("shape", "vert")
    )
    print(f"Repacking lite locale tree for {len(locales)} locales: {', '.join(locales)}")

    for loc in locales:
        pro_loc = pro_lang_dir / loc
        lite_loc = lite_lang_out / loc
        lite_loc.mkdir(parents=True, exist_ok=True)

        for sub in ("shape", "vert"):
            src_sub = pro_loc / sub
            if src_sub.exists():
                shutil.copytree(src_sub, lite_loc / sub, dirs_exist_ok=True)

        for rel in lite_top_level:
            if rel == "messages.yml":
                for name in ("messages.yml", "messages.lang.yml"):
                    src = pro_loc / name
                    if src.exists():
                        shutil.copyfile(src, lite_loc / name)
                continue

            pro_val_path = pro_loc / rel
            if not pro_val_path.exists():
                continue

            lite_baseline_path = lite_main / rel
            allowed = get_top_level_keys(lite_baseline_path)
            if not allowed:
                continue

            dst_val_path = lite_loc / rel
            lang_sibling = re.sub(r"\.yml$", ".lang.yml", rel)
            pro_lang_path = pro_loc / lang_sibling
            dst_lang_path = lite_loc / lang_sibling

            if pro_lang_path.exists():
                repack_lang_map(pro_lang_path, dst_lang_path, allowed)
                locale_to_base = read_locale_lang_map(pro_lang_path)
                repack_locale_value_file(pro_val_path, dst_val_path, locale_to_base, allowed)
            else:
                repack_locale_value_file(pro_val_path, dst_val_path, {}, allowed)

        for rel in lite_regions:
            pro_val_path = pro_loc / rel
            if not pro_val_path.exists():
                continue
            lite_baseline_path = lite_main / rel
            allowed = get_top_level_keys(lite_baseline_path)
            if not allowed:
                continue
            dst_val_path = lite_loc / rel
            dst_val_path.parent.mkdir(parents=True, exist_ok=True)
            repack_locale_value_file(pro_val_path, dst_val_path, {}, allowed)

    print(f"Done. Lite locale tree at: {lite_lang_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
