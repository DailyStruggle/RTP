#!/usr/bin/env python3
"""Detect and repair mojibake: UTF-8 bytes mis-decoded as CP1252 / Latin-1.

Shared by scripts/check-mojibake.py and the .git/hooks/pre-commit autofix.

Repair is generic: a UTF-8 lead char followed by the right number of
continuation chars is mapped back to its CP1252 bytes and strictly decoded as
UTF-8, repeated for double encoding. Sequences that do not form valid UTF-8 are
left alone, so ordinary accented text survives. Lossy leftovers (a dropped
byte, U+FFFD) cannot be repaired and are reported with replacement=None.

Exemptions (intentional mentions of markers):
- any line containing the pragma ``mojibake-ok``;
- in Markdown, text inside inline backtick code spans.

Markers are written as escapes so this file stays clean under its own scan.
Stdlib only.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

PRAGMA = "mojibake-ok"


def _build_byte_map() -> dict[str, int]:
    m: dict[str, int] = {}
    for b in range(0x80, 0x100):
        try:
            m[bytes([b]).decode("cp1252")] = b
        except UnicodeDecodeError:
            pass
    # Latin-1 mis-decodes yield C1 controls; CP1252 leaves 0x81/0x8D/0x8F/0x90/0x9D undefined.
    for b in range(0x80, 0xA0):
        m.setdefault(chr(b), b)
    return m


_BYTE = _build_byte_map()
_CONT = "".join(re.escape(ch) for ch, b in _BYTE.items() if 0x80 <= b <= 0xBF)
_SEQ_RE = re.compile(
    "[\u00c2-\u00df][{c}]|[\u00e0-\u00ef][{c}]{{2}}|[\u00f0-\u00f4][{c}]{{3}}".format(c=_CONT)
)
# Maximal runs of chars that can come from a mis-decoded high byte.
_RUN_RE = re.compile("[{}]+".format("".join(re.escape(ch) for ch in _BYTE)))
_CODE_SPAN_RE = re.compile(r"(`+)(?!`).*?(?<!`)\1(?!`)")

# Lossy residue the generic decoder cannot undo.
RESIDUAL_MARKERS = [
    "\u00e2\u20ac", "\u00e2\u201d", "\u00e2\u2013", "\u00e2\u0161", "\u00e2\u0153",
    "\u00e2\u2020", "\u00e2\u2030", "\u00e2\u02c6", "\u00c3\u0192", "\ufffd",
]
_RESIDUAL_RE = re.compile("|".join(re.escape(m) for m in RESIDUAL_MARKERS))
_MAX_PASSES = 3


@dataclass(frozen=True)
class Finding:
    line: int  # 1-based
    col: int  # 1-based; residue columns refer to the repaired line
    text: str
    replacement: str | None  # None: not repairable automatically


# Code points a repair may produce. Valid UTF-8 landing elsewhere is ordinary text:
# U+00D7 U+2013 (multiplication sign, en dash) would decode to Hebrew, U+00DF U+201C
# (sharp s, German closing quote) to NKo.
_PLAUSIBLE = (
    (0x00A0, 0x024F),  # Latin-1 supplement, Latin extended
    (0x0370, 0x04FF),  # Greek, Cyrillic
    (0x2000, 0x2BFF),  # punctuation, arrows, math, box drawing, symbols, dingbats
    (0x3000, 0x9FFF),  # CJK, kana
    (0xAC00, 0xD7AF),  # Hangul
    (0xFE00, 0xFE0F),  # variation selectors (emoji presentation)
    (0xFF00, 0xFFEF),  # fullwidth forms
    (0x1F000, 0x1FAFF),  # emoji
)


def _plausible(ch: str) -> bool:
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in _PLAUSIBLE)


def _decode_seq(match: re.Match) -> str:
    seq = match.group(0)
    try:
        decoded = bytes(_BYTE[ch] for ch in seq).decode("utf-8")
    except UnicodeDecodeError:
        return seq
    # A CP1252 high char may be an intermediate layer of double encoding.
    return decoded if _plausible(decoded) or decoded in _BYTE else seq


def _fix_run(run: str) -> str:
    original = run
    for _ in range(_MAX_PASSES):
        fixed = _SEQ_RE.sub(_decode_seq, run)
        if fixed == run:
            break
        run = fixed
    # Reject the run if decoding stopped on an intermediate layer.
    if any(not _plausible(ch) and ch not in original for ch in run):
        return original
    return run


def _segments(line: str, markdown: bool) -> list[tuple[int, int, bool]]:
    """(start, end, checked) spans covering the line."""
    if PRAGMA in line:
        return [(0, len(line), False)]
    if not markdown:
        return [(0, len(line), True)]
    out, pos = [], 0
    for m in _CODE_SPAN_RE.finditer(line):
        if m.start() > pos:
            out.append((pos, m.start(), True))
        out.append((m.start(), m.end(), False))
        pos = m.end()
    if pos < len(line):
        out.append((pos, len(line), True))
    return out


def _residuals(line_no: int, segment: str, origin: int) -> list[Finding]:
    return [Finding(line_no, origin + m.start() + 1, m.group(0), None)
            for m in _RESIDUAL_RE.finditer(segment)]


def repair(text: str, markdown: bool = False) -> tuple[str, list[Finding]]:
    """Return (repaired text, findings). Line endings are preserved."""
    findings: list[Finding] = []
    out_lines = []
    for line_no, line in enumerate(text.splitlines(keepends=True), 1):
        parts = []
        for start, end, checked in _segments(line, markdown):
            seg = line[start:end]
            if not checked:
                parts.append(seg)
                continue
            pieces, pos = [], 0
            for m in _RUN_RE.finditer(seg):
                fixed = _fix_run(m.group(0))
                pieces.append(seg[pos:m.start()])
                if fixed != m.group(0):
                    findings.append(Finding(line_no, start + m.start() + 1, m.group(0), fixed))
                    pieces.append(fixed)
                else:
                    pieces.append(m.group(0))
                pos = m.end()
            pieces.append(seg[pos:])
            new_seg = "".join(pieces)
            # Columns of residue are reported against the repaired segment.
            findings.extend(_residuals(line_no, new_seg, start))
            parts.append(new_seg)
        out_lines.append("".join(parts))
    return "".join(out_lines), findings


def scan(text: str, markdown: bool = False) -> list[Finding]:
    """Findings without modifying anything."""
    return repair(text, markdown)[1]


def is_markdown(path: str) -> bool:
    return path.lower().endswith((".md", ".markdown"))


def describe(path: str, f: Finding) -> str:
    if f.replacement is None:
        return (f"{path}:{f.line}:{f.col}: unrepairable {f.text!r} "
                f"(retype the intended character, or add '{PRAGMA}' / a code span if intentional)")
    return f"{path}:{f.line}:{f.col}: {f.text!r} -> {f.replacement!r}"
