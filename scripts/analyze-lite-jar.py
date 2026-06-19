#!/usr/bin/env python3
"""analyze-lite-jar.py

Cross-platform (Python 3.12) replacement for analyze-lite-jar.ps1.

Audit a built RTP jar's size composition: per top-level segment, class-vs-
resource split, the largest resource files, the YAML payload, and the heaviest
class packages. Helps track the lite-jar size budget (ADR-024).

Usage:
  python scripts/analyze-lite-jar.py [PATH_TO_JAR]

If no jar is given, the newest rtp-plugin/build/libs/*.jar is used.
"""

from __future__ import annotations

import sys
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402


def _kb(n: float, digits: int = 1) -> float:
    return round(n / 1024.0, digits)


def _default_jar() -> Path | None:
    libs = lc.repo_root() / "rtp-plugin" / "build" / "libs"
    if not libs.exists():
        return None
    jars = sorted(libs.glob("*.jar"), key=lambda p: p.stat().st_mtime, reverse=True)
    return jars[0] if jars else None


def main(argv: list[str]) -> int:
    if len(argv) > 1:
        jar = Path(argv[1])
    else:
        jar = _default_jar()
        if jar is None:
            raise SystemExit("No jar specified and none found under rtp-plugin/build/libs.")
    if not jar.exists():
        raise SystemExit(f"JAR not found: {jar}")

    entries: list[tuple[str, int, int]] = []  # name, compressed, size
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            entries.append((info.filename, info.compress_size, info.file_size))

    total_comp = sum(e[1] for e in entries)
    total_size = sum(e[2] for e in entries)

    print(f"JAR: {jar}")
    print(f"File size on disk: {_kb(jar.stat().st_size)} KB")
    print(f"Entries: {len(entries)}")
    print(f"Total uncompressed: {_kb(total_size)} KB")
    print(f"Total compressed:   {_kb(total_comp)} KB")
    print()

    print("=== Top-level segment by compressed size ===")
    seg: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])  # count, comp, size
    for name, comp, size in entries:
        top = name.split("/")[0]
        seg[top][0] += 1
        seg[top][1] += comp
        seg[top][2] += size
    rows = sorted(seg.items(), key=lambda kv: kv[1][1], reverse=True)
    print(f"{'Top':<40} {'Count':>6} {'CompKB':>10} {'SizeKB':>10}")
    for top, (count, comp, size) in rows:
        print(f"{top:<40} {count:>6} {_kb(comp):>10} {_kb(size):>10}")
    print()

    print("=== Class vs resource split ===")
    cls = [e for e in entries if e[0].endswith(".class")]
    res = [e for e in entries if not e[0].endswith(".class") and not e[0].endswith("/")]
    print(f"Classes: {len(cls)} entries, comp={_kb(sum(e[1] for e in cls))} KB, "
          f"size={_kb(sum(e[2] for e in cls))} KB")
    print(f"Resources: {len(res)} entries, comp={_kb(sum(e[1] for e in res))} KB, "
          f"size={_kb(sum(e[2] for e in res))} KB")
    print()

    print("=== Top 30 resource files ===")
    print(f"{'CompKB':>10} {'SizeKB':>10}  Name")
    for name, comp, size in sorted(res, key=lambda e: e[1], reverse=True)[:30]:
        print(f"{_kb(comp, 2):>10} {_kb(size, 2):>10}  {name}")
    print()

    print("=== YAML resource files (.yml / .yaml) ===")
    yaml = [e for e in res if e[0].endswith(".yml") or e[0].endswith(".yaml")]
    print(f"{'CompKB':>10} {'SizeKB':>10}  Name")
    for name, comp, size in sorted(yaml, key=lambda e: e[1], reverse=True):
        print(f"{_kb(comp, 2):>10} {_kb(size, 2):>10}  {name}")
    print(f"YAML totals: {len(yaml)} files, comp={_kb(sum(e[1] for e in yaml), 2)} KB, "
          f"size={_kb(sum(e[2] for e in yaml), 2)} KB")
    print()

    print("=== Class packages by compressed size (depth 4) ===")
    pkg: dict[str, list[int]] = defaultdict(lambda: [0, 0])  # count, comp
    for name, comp, size in cls:
        key = "/".join(name.split("/")[:4])
        pkg[key][0] += 1
        pkg[key][1] += comp
    prows = sorted(pkg.items(), key=lambda kv: kv[1][1], reverse=True)[:25]
    print(f"{'Pkg':<50} {'Count':>6} {'CompKB':>10}")
    for key, (count, comp) in prows:
        print(f"{key:<50} {count:>6} {_kb(comp):>10}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
