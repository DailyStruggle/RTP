#!/usr/bin/env python3
"""scan-locale-comment-leaks.py

Cross-platform (Python 3.12) replacement for scan-locale-comment-leaks.ps1.

Scans scripts/out/locale-<lang>.tsv files for preceding_comment cells whose
prose is still English (untranslated). Doc-tag lines (@type/@range/@default/
@unit/@options/@source), URL, banner and empty-comment lines are stripped
before scanning the residual prose. A row is reported when its residual prose
contains at least two distinct English markers.

  python scripts/scan-locale-comment-leaks.py
  python scripts/scan-locale-comment-leaks.py --locales ko --sample-count 50
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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--locales", nargs="*", default=None)
    ap.add_argument("--sample-count", type=int, default=5)
    ap.add_argument("--out-dir", dest="out_dir")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    locales = args.locales if args.locales else lc.list_locales()

    for loc in locales:
        path = out_dir / f"locale-{loc}.tsv"
        if not path.exists():
            print(f"{loc:<4}  (no TSV)")
            continue

        rows = lc.read_tsv(path)
        total = 0
        leaks: list[dict] = []
        for r in rows:
            cmt = r["preceding_comment"]
            if not cmt or not cmt.strip():
                continue
            total += 1
            residual: list[str] = []
            for ln in cmt.split("\n"):
                stripped = ln.lstrip()
                if re.search(r"^#\s*@(type|range|unit|default|options|source):", stripped):
                    continue
                if re.search(r"https?://", stripped):
                    continue
                if re.search(r"^#\s*#+\s*$", stripped):
                    continue
                if re.search(r"^#\s*$", stripped):
                    continue
                residual.append(stripped)
            if not residual:
                continue
            hay = " " + " ".join(residual).replace("#", " ") + " "
            matched = sorted({w.strip() for w in EN_MARKERS if w in hay})
            if len(matched) >= 2:
                leaks.append({"relpath": r["relpath"], "parent": r["parent_path"],
                              "key": r["key"], "index": r["index"], "comment": cmt,
                              "markers": ",".join(matched)})

        print(f"{loc:<4}  comments={total:4}  english-leak={len(leaks):4}")
        if args.sample_count > 0 and leaks:
            for e in leaks[:args.sample_count]:
                snippet = e["comment"].replace("\n", " / ")
                if len(snippet) > 160:
                    snippet = snippet[:160] + "..."
                print(f"       [{e['relpath']}] {e['parent']}::{e['key']}[{e['index']}] <{e['markers']}>")
                print(f"           {snippet}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
