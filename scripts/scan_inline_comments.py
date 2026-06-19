#!/usr/bin/env python3
"""scan_inline_comments.py

Cross-platform (Python 3.12) replacement for scan_inline_comments.ps1.

Scan a resource tree for YAML lines that carry an inline (trailing) comment
after a value, i.e. `key: value  # comment` as opposed to a full-line `# ...`
comment. Inline comments on config values are the pattern ADR-025/ADR-042
migrated away from, so this is a recurring lint guard.

Usage:
  python scripts/scan_inline_comments.py [ROOT]

ROOT defaults to rtp-plugin/src/main/resources.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402

_QUOTED = re.compile(r'"[^"]*"')
_QUOTED_SINGLE = re.compile(r"'[^']*'")
_INLINE = re.compile(r"\S.*\s#")


def count_inline(text: str) -> int:
    count = 0
    for line in re.split(r"\r\n|\r|\n", text):
        stripped = line.lstrip()
        if stripped.startswith("#") or stripped == "":
            continue
        masked = _QUOTED.sub('""', line)
        masked = _QUOTED_SINGLE.sub("''", masked)
        if _INLINE.search(masked):
            count += 1
    return count


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else (
        lc.repo_root() / "rtp-plugin" / "src" / "main" / "resources"
    )
    if not root.exists():
        raise SystemExit(f"Root not found: {root}")

    files = sorted(root.rglob("*.yml"))
    total = 0
    with_inline = 0
    report: list[tuple[str, int]] = []
    base = lc.repo_root()
    for f in files:
        count = count_inline(lc.read_text_no_bom(f))
        total += count
        if count > 0:
            with_inline += 1
            try:
                rel = str(f.relative_to(base))
            except ValueError:
                rel = str(f)
            report.append((rel, count))

    print(f"Files with inline comments: {with_inline} / {len(files)}. Total inline: {total}")
    report.sort(key=lambda r: r[1], reverse=True)
    width = max((len(r[0]) for r in report), default=4)
    if report:
        print(f"{'File'.ljust(width)}  Inline")
        for rel, count in report:
            print(f"{rel.ljust(width)}  {count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
