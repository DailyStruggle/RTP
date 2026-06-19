#!/usr/bin/env python3
"""analyze-long-comments.py

Cross-platform (Python 3.12) replacement for analyze-long-comments.ps1.

Find excessively long / verbose comments in Java sources to help strip the
"AI-generated" feel from the codebase. Scans .java files under one or more
roots, extracts every comment block ("//" runs coalesced into one block;
"/* ... */" and Javadoc are one block each), ranks them by a verbosity
heuristic, writes a CSV report, and prints a console top-N summary.

Flags:  L=long  C=many-chars  A=AI-tells  B=banner  R=ratio-heavy-file  T=TODO/FIXME

Usage:
  python scripts/analyze-long-comments.py [--roots rtp-core rtp-api ...]
        [--min-lines 6] [--min-chars 400] [--top-n 30]
        [--out-csv scripts/out/long-comments.csv]
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402

DEFAULT_ROOTS = ["rtp-core", "rtp-api", "rtp-plugin", "platforms", "api", "addons"]

AI_TELLS = [
    "let's", "let us ", "we'll ", "we will ",
    "in summary", "in conclusion", "to summarize",
    "it's worth noting", "it is worth noting",
    "note that", "please note", "as mentioned",
    "as you can see", "as we can see",
    "this method is responsible for",
    "this class is responsible for",
    "this function is responsible for",
    "essentially,", "basically,", "fundamentally,",
    "furthermore,", "moreover,", "additionally,",
    "in other words", "that is to say",
    "step-by-step", "step by step",
    "high-level overview", "at a high level",
    "for the sake of", "out of the box",
    "robust", "seamless", "leverage", "leverages",
    "comprehensive", "elegant solution",
    "best practice", "industry standard",
]

_BANNER_RE = re.compile(r"^[\s/*#]*[-=*#_]{6,}[\s/*#]*$")
_TODO_RE = re.compile(r"\b(todo|fixme|xxx|hack)\b", re.IGNORECASE)
_BLOCK_RE = re.compile(r"/\*[\s\S]*?\*/")
_EXCLUDE_RE = re.compile(r"[\\/](build|target|out|\.gradle|\.idea)[\\/]")
_LINE_RE = re.compile(r"^\s*//")


class Block:
    __slots__ = ("file", "kind", "start", "end", "text", "raw")

    def __init__(self, file: str, kind: str, start: int, end: int, text: str, raw: list[str]):
        self.file = file
        self.kind = kind
        self.start = start
        self.end = end
        self.text = text
        self.raw = raw

    @property
    def chars(self) -> int:
        return len(self.text)

    @property
    def lines(self) -> int:
        return self.end - self.start + 1

    @property
    def ai_tells(self) -> int:
        lower = self.text.lower()
        return sum(1 for t in AI_TELLS if t in lower)

    @property
    def banner_ln(self) -> int:
        return sum(1 for ln in self.raw if _BANNER_RE.match(ln))

    @property
    def is_todo(self) -> bool:
        return bool(_TODO_RE.search(self.text))

    @property
    def preview(self) -> str:
        p = re.sub(r"\s+", " ", self.text).lstrip()
        return p[:120]


def get_comments_from_file(path: Path) -> list[Block]:
    text = lc.read_text_no_bom(path)
    lines = re.split(r"\r?\n", text)
    n = len(lines)
    results: list[Block] = []

    for m in _BLOCK_RE.finditer(text):
        start_idx = m.start()
        end_idx = m.end() - 1
        start_line = len(re.split("\n", text[:start_idx]))
        end_line = len(re.split("\n", text[:end_idx + 1]))
        kind = "javadoc" if m.group(0).startswith("/**") else "block"
        raw = re.split(r"\r?\n", m.group(0))
        results.append(Block(str(path), kind, start_line, end_line, m.group(0), raw))

    i = 0
    while i < n:
        if _LINE_RE.match(lines[i]):
            start = i
            buf: list[str] = []
            while i < n and _LINE_RE.match(lines[i]):
                buf.append(lines[i])
                i += 1
            end = i - 1
            if (end - start + 1) >= 2 or len("\n".join(buf)) >= 80:
                results.append(Block(str(path), "line", start + 1, end + 1, "\n".join(buf), buf))
        else:
            i += 1
    return results


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Analyze long/verbose Java comments.")
    parser.add_argument("--roots", nargs="+", default=DEFAULT_ROOTS)
    parser.add_argument("--min-lines", type=int, default=6)
    parser.add_argument("--min-chars", type=int, default=400)
    parser.add_argument("--top-n", type=int, default=30)
    parser.add_argument("--out-csv", default="scripts/out/long-comments.csv")
    args = parser.parse_args(argv[1:])

    repo_root = lc.repo_root()
    resolved = [(repo_root / r) for r in args.roots if (repo_root / r).exists()]
    if not resolved:
        raise SystemExit("No valid roots found.")

    print("Scanning Java sources under:")
    for r in resolved:
        print(f"  {r}")

    java_files: list[Path] = []
    for r in resolved:
        for f in r.rglob("*.java"):
            if not _EXCLUDE_RE.search(str(f)):
                java_files.append(f)
    print(f"Files: {len(java_files)}")

    all_blocks: list[Block] = []
    for f in java_files:
        try:
            all_blocks.extend(get_comments_from_file(f))
        except Exception as e:  # noqa: BLE001
            print(f"WARNING: Failed to scan {f}: {e}", file=sys.stderr)

    file_size = {str(f): f.stat().st_size for f in java_files}
    comment_chars: dict[str, int] = defaultdict(int)
    for b in all_blocks:
        comment_chars[b.file] += b.chars

    rows = []
    for b in all_blocks:
        flags = ""
        if b.lines >= args.min_lines:
            flags += "L"
        if b.chars >= args.min_chars:
            flags += "C"
        if b.ai_tells > 0:
            flags += "A"
        if b.banner_ln >= 2:
            flags += "B"
        if b.is_todo:
            flags += "T"
        fsize = max(1, int(file_size.get(b.file, 1)))
        density = comment_chars[b.file] / fsize
        if density >= 0.30 and b.lines >= args.min_lines:
            flags += "R"

        score = b.lines + (b.chars / 40) + (b.ai_tells * 5) + (b.banner_ln * 3)
        if b.is_todo:
            score = score / 2

        rel = b.file
        if rel.startswith(str(repo_root)):
            rel = rel[len(str(repo_root)):].lstrip("\\/")
        rows.append({
            "Score": round(score, 1),
            "Flags": flags,
            "Lines": b.lines,
            "Chars": b.chars,
            "AITells": b.ai_tells,
            "BannerLn": b.banner_ln,
            "Kind": b.kind,
            "File": rel,
            "StartLine": b.start,
            "EndLine": b.end,
            "Preview": b.preview,
        })

    candidates = [
        r for r in rows
        if r["Lines"] >= args.min_lines or r["Chars"] >= args.min_chars or r["AITells"] > 0
    ]
    candidates.sort(key=lambda r: r["Score"], reverse=True)

    csv_path = repo_root / args.out_csv
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = ["Score", "Flags", "Lines", "Chars", "AITells", "BannerLn",
                  "Kind", "File", "StartLine", "EndLine", "Preview"]
    with csv_path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(candidates)

    print()
    print(f"Wrote {len(candidates)} candidate comment blocks to:")
    print(f"  {csv_path}")
    print()
    print("=== Summary ===")
    print(f"Total comment blocks scanned : {len(all_blocks)}")
    print(f"Candidates (>= thresholds)   : {len(candidates)}")
    print(f"  with AI-tells (A)          : {sum(1 for r in candidates if 'A' in r['Flags'])}")
    print(f"  banners (B)                : {sum(1 for r in candidates if 'B' in r['Flags'])}")
    print(f"  ratio-heavy files (R)      : {sum(1 for r in candidates if 'R' in r['Flags'])}")
    print(f"  TODO/FIXME (T, deprio)     : {sum(1 for r in candidates if 'T' in r['Flags'])}")
    print()

    print("=== Top files by total candidate comment chars ===")
    by_file: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])  # blocks, lines, chars
    for r in candidates:
        agg = by_file[r["File"]]
        agg[0] += 1
        agg[1] += r["Lines"]
        agg[2] += r["Chars"]
    top_files = sorted(by_file.items(), key=lambda kv: kv[1][2], reverse=True)[:20]
    print(f"{'File':<70} {'Blocks':>6} {'Lines':>6} {'Chars':>8}")
    for fname, (blocks, lines, chars) in top_files:
        print(f"{fname:<70} {blocks:>6} {lines:>6} {chars:>8}")
    print()

    print(f"=== Top {args.top_n} worst offenders (by score) ===")
    for r in candidates[:args.top_n]:
        loc = f"{r['File']}:{r['StartLine']}-{r['EndLine']}"
        print(f"{r['Score']:>7} {r['Flags']:<6} L{r['Lines']:<4} C{r['Chars']:<5} "
              f"A{r['AITells']:<2} {r['Kind']:<7} {loc}")
        print(f"        {r['Preview']}")
    print()
    print("Flag legend: L=long  C=many-chars  A=AI-tells  B=banner  R=ratio-heavy-file  T=TODO/FIXME(deprio)")
    print("Next step: open the CSV, sort by Score desc, and triage top entries by hand.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
