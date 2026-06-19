#!/usr/bin/env python3
"""recolor-locales.py

Cross-platform (Python 3.12) replacement for recolor-locales.ps1.

One-off recolor sweep over scripts/out/locale-*.tsv: remaps the legacy gold
(&6) menu colors to the parchment-safe palette (see the Book Menu Color
Contrast rule in .junie/AGENTS.md), preserving the run/row arrow glyph (U+25B6)
and healing the admin-panel row. Writes each file back UTF-8 (no BOM), no
trailing newline added.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def main() -> int:
    out_dir = lc.out_dir()
    arrow = "\u25b6"
    for f in sorted(out_dir.glob("locale-*.tsv")):
        c = lc.read_text_no_bom(f)
        c = c.replace("&6&l", "#5FB3B3&l")
        c = c.replace(f"&6{arrow}", f"#9D7CD8{arrow}")
        c = c.replace(f"&7{arrow} admin", f"#9D7CD8{arrow} admin")
        c = c.replace("&6", "&7")
        lc.write_bytes_utf8(f, c)
        print(f"Updated {f.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
