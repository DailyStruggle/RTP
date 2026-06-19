#!/usr/bin/env python3
"""Shared helpers for the cross-platform (Python 3.12) locale config pipeline.

This module is the single, OS-agnostic replacement for the former
locale-changeset-common.ps1 / .sh dot-sourced helpers. It provides the TSV
read/write, RFC-4180 CSV read/write, comment-cell escaping, mojibake scan, and
langmap / relpath helpers used by every pipeline script:

    locale-files-to-csv.py      locale-changeset-to-csv.py
    locale-files-from-csv.py    locale-changeset-from-csv.py
    reconcile-locale-csvs.py

The byte-level semantics (escaping order, quoting, UTF-8 no BOM, LF endings)
mirror the PowerShell/bash originals exactly so generated TSV/CSV/YAML output is
identical regardless of platform.
"""

from __future__ import annotations

import os
import re
from pathlib import Path

# Column order for the internal per-locale / baseline pipeline TSVs.
TSV_COLS = [
    "relpath",
    "parent_path",
    "key",
    "index",
    "value",
    "preceding_comment",
    "blank_before",
    "base_key",
]

_PH = "\x01"  # placeholder for an escaped backslash during decode


# --- Path resolution --------------------------------------------------------

def script_dir() -> Path:
    return Path(__file__).resolve().parent


def repo_root() -> Path:
    return script_dir().parent


def resources_root() -> Path:
    return repo_root() / "rtp-plugin" / "src" / "main" / "resources"


def out_dir() -> Path:
    return script_dir() / "out"


def read_text_no_bom(path: str | os.PathLike) -> str:
    data = Path(path).read_bytes()
    text = data.decode("utf-8")
    if text and text[0] == "\ufeff":
        text = text[1:]
    return text


def write_bytes_utf8(path: str | os.PathLike, text: str) -> None:
    # UTF-8, no BOM, LF endings preserved (text already uses '\n').
    Path(path).write_bytes(text.encode("utf-8"))


# --- Internal pipeline TSV (tab-separated, backslash-escaped) ---------------

def _tsv_encode_cell(v: str) -> str:
    v = "" if v is None else str(v)
    # Escape a single literal backslash as a 2-backslash run; the decode side
    # collapses each 2-backslash run back to one. This keeps to-csv/from-csv a
    # byte-idempotent round-trip (1 backslash -> 2 in TSV -> 1 in YAML). The
    # former PowerShell pipeline emitted 4 backslashes here while decoding only
    # 2-runs, which doubled every backslash on each pass and corrupted locale
    # comments/values containing literal backslashes (e.g. zh @options lists).
    v = v.replace("\\", "\\\\")
    v = v.replace("\r\n", "\n")
    v = v.replace("\n", "\\n")
    v = v.replace("\t", "\\t")
    return v


def _tsv_decode_cell(v: str) -> str:
    if v is None:
        return ""
    v = v.replace("\\\\", _PH)
    v = v.replace("\\n", "\n")
    v = v.replace("\\t", "\t")
    v = v.replace(_PH, "\\")
    return v


def read_tsv(path: str | os.PathLike) -> list[dict]:
    text = read_text_no_bom(path)
    rows: list[dict] = []
    header_seen = False
    for line in re.split(r"\r?\n", text):
        if line == "":
            continue
        if not header_seen:
            header_seen = True
            continue
        parts = line.split("\t", len(TSV_COLS) - 1)
        while len(parts) < len(TSV_COLS):
            parts.append("")
        decoded = [_tsv_decode_cell(p) for p in parts]
        rows.append(dict(zip(TSV_COLS, decoded)))
    return rows


def write_tsv(rows: list[dict], path: str | os.PathLike) -> None:
    out = ["\t".join(TSV_COLS)]
    for r in rows:
        out.append("\t".join(_tsv_encode_cell(r.get(c, "")) for c in TSV_COLS))
    write_bytes_utf8(path, "\n".join(out) + "\n")


# --- Comment-cell escaping (single-line CSV transport) ----------------------

def to_comment_cell(s: str) -> str:
    if s is None:
        return ""
    v = s
    # Idempotent 1-backslash -> 2-backslash escape, matching _tsv_encode_cell
    # (see note there). The decode side collapses each 2-backslash run to one.
    v = v.replace("\\", "\\\\")
    v = v.replace("\r\n", "\n")
    v = v.replace("\n", "\\n")
    v = v.replace("\t", "\\t")
    return v


def from_comment_cell(s: str) -> str:
    if s is None:
        return ""
    v = s
    v = v.replace("\\\\", _PH)
    v = v.replace("\\n", "\n")
    v = v.replace("\\t", "\t")
    v = v.replace(_PH, "\\")
    return v


# --- User-facing changeset CSV (RFC-4180 comma-quoted, UTF-8 no BOM) --------

def _csv_field(s: str) -> str:
    if s is None:
        s = ""
    s = str(s)
    if re.search(r'[",\r\n]', s):
        return '"' + s.replace('"', '""') + '"'
    return s


def write_csv(rows: list[dict], columns: list[str], path: str | os.PathLike) -> None:
    out = [",".join(_csv_field(c) for c in columns)]
    for r in rows:
        out.append(",".join(_csv_field(r.get(c, "")) for c in columns))
    write_bytes_utf8(path, "\n".join(out) + "\n")


def split_csv_line(line: str) -> list[str]:
    fields: list[str] = []
    buf: list[str] = []
    in_quotes = False
    i = 0
    n = len(line)
    while i < n:
        ch = line[i]
        if in_quotes:
            if ch == '"':
                if i + 1 < n and line[i + 1] == '"':
                    buf.append('"')
                    i += 1
                else:
                    in_quotes = False
            else:
                buf.append(ch)
        else:
            if ch == '"':
                in_quotes = True
            elif ch == ",":
                fields.append("".join(buf))
                buf = []
            else:
                buf.append(ch)
        i += 1
    fields.append("".join(buf))
    return fields


def read_csv(path: str | os.PathLike) -> dict:
    text = read_text_no_bom(path)
    header: list[str] | None = None
    rows: list[dict] = []
    for line in re.split(r"\r?\n", text):
        if line == "":
            continue
        fields = split_csv_line(line)
        if header is None:
            header = fields
            continue
        o: dict = {}
        for i, name in enumerate(header):
            o[name] = fields[i] if i < len(fields) else ""
        rows.append(o)
    return {"Header": header or [], "Rows": rows}


# --- Mojibake / encoding hygiene scan ---------------------------------------

MOJIBAKE_MARKERS = [
    "\u00e2\u20ac",  # em/en dash, curly quotes
    "\u00e2\u0153",  # check-mark emoji family
    "\u00e2\u008c",  # cross-mark
    "\u00f0\u0178",  # 4-byte emoji
    "\u00c3\u00a9",  # accented Latin (e-acute)
    "\u00c2\u00a7",  # section sign
    "\ufffd",        # U+FFFD replacement character
]


def find_mojibake(text: str) -> list[str]:
    hits: list[str] = []
    if not text:
        return hits
    for m in MOJIBAKE_MARKERS:
        if m in text:
            hits.append(m)
    return hits


# --- Relpath / langmap helpers ----------------------------------------------

def list_locales() -> list[str]:
    """Shipped locale codes (subdirs of resources/lang, excluding shape/vert)."""
    lang_root = resources_root() / "lang"
    if not lang_root.is_dir():
        return []
    return sorted(
        p.name for p in lang_root.iterdir()
        if p.is_dir() and p.name not in ("shape", "vert")
    )


def get_locale_relpath(baseline_relpath: str, loc: str) -> str:
    if baseline_relpath.startswith("lang/"):
        tail = baseline_relpath[len("lang/"):]
        return f"lang/{loc}/{tail}"
    return f"lang/{loc}/{baseline_relpath}"


def get_baseline_relpath(locale_relpath: str, loc: str) -> str | None:
    prefix = f"lang/{loc}/"
    if not locale_relpath.startswith(prefix):
        return None
    tail = locale_relpath[len(prefix):]
    if tail.endswith(".lang.yml") or tail.startswith("shape/") or tail.startswith("vert/"):
        return f"lang/{tail}"
    return tail


def build_langmaps(locale_rows: list[dict], loc: str) -> dict[str, dict[str, str]]:
    """baseValueRelpath -> {baseKey -> translatedKey} from a locale's TSV rows."""
    langmaps: dict[str, dict[str, str]] = {}
    prefix = f"lang/{loc}/"
    for r in locale_rows:
        relpath = r["relpath"]
        if re.match(rf"^lang/{re.escape(loc)}/.*\.lang\.yml$", relpath):
            continue
        if not relpath.startswith(prefix):
            continue
        if not r["key"]:
            continue
        if not r["base_key"]:
            continue
        if r["base_key"] == r["key"]:
            continue
        tail = relpath[len(prefix):]
        if tail.startswith("shape/") or tail.startswith("vert/"):
            value_relpath = f"lang/{tail}"
        else:
            value_relpath = tail
        langmaps.setdefault(value_relpath, {})
        langmaps[value_relpath].setdefault(r["base_key"], r["key"])
    return langmaps
