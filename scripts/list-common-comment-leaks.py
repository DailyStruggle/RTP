#!/usr/bin/env python3
"""list-common-comment-leaks.py

Cross-platform (Python 3.12) replacement for list-common-comment-leaks.ps1.

Lists baseline (relpath, base_key, index) rows whose preceding_comment is still
English in MULTIPLE shipped locales, sorted by the number of affected locales
(descending) - the "translate once, apply to every locale" candidates.

Reuses the heuristic from scan-locale-comment-leaks.py (strip doc-tag lines,
URLs, banners, blank-comments; require >=2 distinct English connector markers).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402

EN_MARKERS = [
    " the ", " and ", " for ", " with ", " when ", " this ", " that ", " use ", " used ",
    " will ", " must ", " should ", " does ", " from ", " into ", " before ", " after ",
    " between ", " player ", " players ", " server ", " available ", " enable ", " enables ",
    " disable ", " disabled ", " enabled ", " if ", " wait ", " time ", " set ", " default ",
    " number ", " file ", " files ", " empty ", " list ", " value ", " values ",
    " command ", " commands ", " world ", " chunks ", " loaded ", " Documentation",
    " Configuration", " Wait ", " Time ", " Database ", " database ", " password ",
    " username ", " host ", " port ", " Used by ", " Empty string ", " executed ",
    " successful ", " target ", " name ", " ignored ", " string ",
]


def is_english_leak(cmt: str) -> bool:
    if not cmt or not cmt.strip():
        return False
    residual: list[str] = []
    for ln in cmt.split("\n"):
        s = ln.lstrip()
        if re.search(r"^#\s*@(type|range|unit|default|options|source):", s):
            continue
        if re.search(r"https?://", s):
            continue
        if re.search(r"^#\s*#+\s*$", s):
            continue
        if re.search(r"^#\s*$", s):
            continue
        residual.append(s)
    if not residual:
        return False
    hay = " " + " ".join(residual).replace("#", " ") + " "
    matched = {w.strip() for w in EN_MARKERS if w in hay}
    return len(matched) >= 2


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--locales", nargs="*", default=None)
    ap.add_argument("--min-locales", type=int, default=2)
    ap.add_argument("--out-dir", dest="out_dir")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    locales = args.locales if args.locales else lc.list_locales()

    shared: dict[str, dict] = {}
    for loc in locales:
        path = out_dir / f"locale-{loc}.tsv"
        if not path.exists():
            continue
        for r in lc.read_tsv(path):
            relpath = r["relpath"]
            idx = r["index"]
            cmt = r["preceding_comment"]
            base_key = r["base_key"]
            base_rel = relpath
            prefix = f"lang/{loc}/"
            if base_rel.startswith(prefix):
                tail = base_rel[len(prefix):]
                if tail.startswith("shape/") or tail.startswith("vert/"):
                    base_rel = f"lang/{tail}"
                else:
                    base_rel = tail
            if not is_english_leak(cmt):
                continue
            sig = f"{base_rel}|{base_key}|{idx}"
            if sig not in shared:
                shared[sig] = {"locales": [], "sample": cmt, "relpath": base_rel,
                               "base_key": base_key, "index": idx}
            shared[sig]["locales"].append(loc)

    rows = []
    for e in shared.values():
        if len(e["locales"]) < args.min_locales:
            continue
        rows.append({
            "Count": len(e["locales"]),
            "Relpath": e["relpath"],
            "BaseKey": e["base_key"],
            "Index": e["index"],
            "Locales": ",".join(sorted(set(e["locales"]))),
            "Comment": e["sample"].replace("\n", " / "),
        })

    rows.sort(key=lambda r: (-r["Count"], r["Relpath"], r["BaseKey"], r["Index"]))

    print(f"Shared English-leak comments (present in >= {args.min_locales} locales):")
    print(f"  total signatures: {len(rows)}")
    print()
    for r in rows:
        snippet = r["Comment"]
        if len(snippet) > 140:
            snippet = snippet[:140] + "..."
        print(f"{r['Count']:2}x  [{r['Relpath']}] {r['BaseKey']}[{r['Index']}]   ({r['Locales']})")
        print(f"        {snippet}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
