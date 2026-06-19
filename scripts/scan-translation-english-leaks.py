#!/usr/bin/env python3
"""scan-translation-english-leaks.py

Cross-platform (Python 3.12) replacement for scan-translation-english-leaks.ps1.

Scans scripts/translations/<locale>.tsv bundles for comment rows whose
translated comment is still English prose (used after a restore from git to
find translation gaps).

Heuristic: after stripping doc-tag (@type/@range/...), URL, banner, and
empty-comment lines, look for common English connector words in the residual
prose. Require >=2 distinct markers to flag a row. Doc-tags, banners and URLs
stay verbatim by policy and are ignored.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402

DEFAULT_LOCALES = ["cat", "de", "es", "fr", "ja", "ko", "nl", "pt", "ru", "zh"]

EN_MARKERS = [
    " the ", " and ", " for ", " with ", " when ", " this ", " that ", " use ", " used ",
    " will ", " must ", " should ", " does ", " from ", " into ", " before ", " after ",
    " between ", " player ", " players ", " server ", " available ", " enable ", " enables ",
    " disable ", " disabled ", " enabled ", " if ", " wait ", " time ", " set ", " default ",
    " number ", " file ", " files ", " empty ", " list ", " value ", " values ",
    " command ", " commands ", " world ", " chunks ", " loaded ", " Documentation",
    " Configuration", " Wait ", " Time ", " Database ", " database ", " password ",
    " username ", " host ", " port ", " Used by ", " Empty string ",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--locales", nargs="*", default=DEFAULT_LOCALES)
    ap.add_argument("--sample-count", type=int, default=3)
    ap.add_argument("--translations-dir", dest="translations_dir")
    args = ap.parse_args()

    translations_dir = (Path(args.translations_dir) if args.translations_dir
                        else lc.script_dir() / "translations")

    for loc in args.locales:
        path = translations_dir / f"{loc}.tsv"
        if not path.exists():
            print(f"{loc:<4}  (no translations file)")
            continue

        lines = re.split(r"\r?\n", lc.read_text_no_bom(path))[1:]  # skip header
        total = 0
        leaks: list[dict] = []
        for line in lines:
            if not line:
                continue
            c = line.split("\t")
            if len(c) < 5:
                continue
            total += 1
            cmt = c[4]
            if not cmt or not cmt.strip():
                continue
            residual: list[str] = []
            for ln in cmt.split("\\n"):
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
            matched = [w.strip() for w in EN_MARKERS if w in hay]
            if len(matched) >= 2:
                leaks.append({"relpath": c[0], "base_key": c[2], "comment": cmt,
                              "markers": ",".join(matched)})

        print(f"{loc:<4}  total={total:3}  english-leak={len(leaks):3}")
        if args.sample_count > 0 and leaks:
            for e in leaks[:args.sample_count]:
                snippet = e["comment"].replace("\\n", " / ")
                if len(snippet) > 140:
                    snippet = snippet[:140] + "..."
                print(f"       [{e['relpath']}] {e['base_key']} <{e['markers']}> :: {snippet}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
