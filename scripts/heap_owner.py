#!/usr/bin/env python3
"""Name the owner of heap growth from two leak-check heap dumps (stdlib only).

Runs Eclipse Memory Analyzer headless (external tool, not a repo dependency)
on the c00 and last .hprof written by `/rtpstress leakcheck <label> dump`:

  1. Leak suspects by snapshot comparison (`org.eclipse.mat.api:suspects2`,
     final dump against the c00 baseline): what grew and the path that keeps
     it alive.
  2. Leak suspects on the final dump alone (`org.eclipse.mat.api:suspects`).

Every class name in each suspect's text is mapped to the plugin whose jar
under the server's plugins/ folder (*.jar and disabled *.jar.off) contains
that class, so bundled libraries and sub-packages count for their plugin.
A class shipped by several plugins' jars is owned by none; a class in no jar
falls back to the plugin.yml `main:` package. The first plugin-owned class
in a suspect names the owner; a suspect with none is reported as
`server/JDK`. No knowledge of any particular plugin is built in.

Example:
  python scripts/heap_owner.py --mat ~/mat/ParseHeapDump.sh \\
      --plugins-dir /mnt/threadripper-dir/26.2/plugins \\
      --baseline runs/leak-...-betterrtp-c00.hprof --dump runs/leak-...-betterrtp-c09.hprof

MAT needs a heap of roughly the dump size: set -Xmx in MemoryAnalyzer.ini
(next to ParseHeapDump.sh) or pass --xmx, which is forwarded as -vmargs.
"""
from __future__ import annotations

import argparse
import glob
import html
import os
import re
import subprocess
import sys
import time
import zipfile

CLASS_NAME = re.compile(r"\b((?:[a-zA-Z_$][\w$]*\.){2,}[A-Z$][\w$]*)")
MAIN_LINE = re.compile(r"^main:\s*['\"]?([\w.$]+)['\"]?\s*$", re.M)
NAME_LINE = re.compile(r"^name:\s*['\"]?([^'\"\n]+?)['\"]?\s*$", re.M)


def jar_entry_class(entry: str) -> str | None:
    """Binary class name for a jar entry; None for resources and module/package-info."""
    if not entry.endswith(".class"):
        return None
    if entry.startswith("META-INF/versions/"):
        parts = entry.split("/", 3)
        if len(parts) < 4:
            return None
        entry = parts[3]
    elif entry.startswith("META-INF/"):
        return None
    if entry.endswith(("module-info.class", "package-info.class")):
        return None
    return entry[:-len(".class")].replace("/", ".")


def plugin_index(plugins_dir: str) -> tuple[dict[str, str | None], list[tuple[str, str]]]:
    """({class name: plugin name, None if several jars ship it},
    [(main package prefix, plugin name)] longest first)."""
    owners: dict[str, str | None] = {}
    ambiguous: set[str] = set()
    pkgs = []
    for jar in glob.glob(os.path.join(plugins_dir, "*.jar")) + glob.glob(os.path.join(plugins_dir, "*.jar.off")):
        try:
            with zipfile.ZipFile(jar) as z:
                entries = z.namelist()
                names = [n for n in ("plugin.yml", "paper-plugin.yml") if n in entries]
                if not names:
                    continue
                text = z.read(names[0]).decode("utf-8", "replace")
        except (OSError, zipfile.BadZipFile):
            continue
        m, n = MAIN_LINE.search(text), NAME_LINE.search(text)
        name = n.group(1).strip() if n else os.path.basename(jar)
        if m:
            pkgs.append((m.group(1).rsplit(".", 1)[0] + ".", name))
        for e in entries:
            cls = jar_entry_class(e)
            if cls is None:
                continue
            prev = owners.setdefault(cls, name)
            if prev != name:
                ambiguous.add(cls)
    for cls in ambiguous:
        owners[cls] = None
    pkgs.sort(key=lambda p: -len(p[0]))
    return owners, pkgs


def owner_of(text: str, owners: dict[str, str | None], pkgs: list[tuple[str, str]]) -> tuple[str, str]:
    """(owner, first owned class) for one suspect's text."""
    for cls in CLASS_NAME.findall(text):
        host = cls.split("$$", 1)[0]  # lambda / hidden classes belong to their host
        if host in owners:
            if owners[host] is not None:
                return owners[host], cls
            continue  # shipped by several jars: not attributable by name
        for prefix, name in pkgs:
            if host.startswith(prefix):
                return name, cls
    return "server/JDK", ""


def html_text(raw: bytes) -> str:
    s = raw.decode("utf-8", "replace")
    s = re.sub(r"(?is)<(script|style).*?</\1>", " ", s)
    s = re.sub(r"(?i)<br\s*/?>|</p>|</li>|</tr>|</h\d>", "\n", s)
    s = re.sub(r"<[^>]+>", " ", s)
    return re.sub(r"[ \t]+", " ", html.unescape(s))


def suspects(zip_path: str) -> list[str]:
    """Suspect paragraphs from a MAT report zip ('Problem Suspect N' blocks)."""
    texts = []
    with zipfile.ZipFile(zip_path) as z:
        for n in z.namelist():
            if n.endswith(".html"):
                texts.append(html_text(z.read(n)))
    blocks = []
    for t in texts:
        for part in re.split(r"(?=Problem Suspect \d+|Hint \d+)", t):
            part = part.strip()
            if re.match(r"(Problem Suspect|Hint) \d+", part) and ("occup" in part or "accumulated" in part):
                blocks.append(re.sub(r"\n\s*\n+", "\n", part)[:1500])
    seen, out = set(), []
    for b in blocks:  # the same suspect appears on the index and its own page
        key = b[:200]
        if key not in seen:
            seen.add(key)
            out.append(b)
    return out


def run_mat(mat: str, dump: str, report: str, extra: list[str], xmx: str | None, timeout_s: int) -> list[str]:
    """Run one MAT report; returns zip files it created next to the dump."""
    before = set(glob.glob(os.path.splitext(dump)[0] + "*.zip"))
    cmd = [mat, dump] + extra + [report]
    if xmx:
        cmd += ["-vmargs", f"-Xmx{xmx}"]
    print(f"$ {' '.join(cmd)}", flush=True)
    t0 = time.time()
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout_s)
    if r.returncode != 0:
        print(r.stdout[-3000:], file=sys.stderr)
        print(f"MAT exited {r.returncode} for {report}", file=sys.stderr)
    after = set(glob.glob(os.path.splitext(dump)[0] + "*.zip"))
    made = sorted(after - before) or sorted(p for p in after if os.path.getmtime(p) >= t0 - 1)
    return made


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--mat", required=True, help="path to MAT's ParseHeapDump.sh (or .bat)")
    p.add_argument("--dump", required=True, help="final .hprof")
    p.add_argument("--baseline", help="first (c00) .hprof; enables the snapshot-comparison report")
    p.add_argument("--plugins-dir", required=True, help="server plugins/ folder (reads each jar's plugin.yml)")
    p.add_argument("--xmx", help="MAT heap, e.g. 24g")
    p.add_argument("--timeout-s", type=int, default=6 * 3600)
    p.add_argument("--out", help="write the report here as well")
    a = p.parse_args()

    owners, pkgs = plugin_index(a.plugins_dir)
    per_plugin: dict[str, int] = {}
    for n in owners.values():
        if n is not None:
            per_plugin[n] = per_plugin.get(n, 0) + 1
    lines = [f"heap owner report: dump={os.path.basename(a.dump)}"
             + (f" baseline={os.path.basename(a.baseline)}" if a.baseline else ""),
             f"plugin jar classes: {', '.join(f'{n}={c}' for n, c in sorted(per_plugin.items())) or 'none found'}",
             f"fallback packages: {', '.join(f'{n}={pre[:-1]}' for pre, n in pkgs) or 'none found'}", ""]
    reports = []
    if a.baseline:
        reports.append(("growth since baseline (suspects2)", "org.eclipse.mat.api:suspects2",
                        [f"-baseline={a.baseline}"]))
    reports.append(("final dump (suspects)", "org.eclipse.mat.api:suspects", []))
    for title, report, extra in reports:
        zips = run_mat(a.mat, a.dump, report, extra, a.xmx, a.timeout_s)
        lines.append(f"== {title}: {', '.join(os.path.basename(z) for z in zips) or 'no report produced'}")
        found = [s for z in zips for s in suspects(z)]
        if not found:
            lines.append("  no suspects parsed (open the zip's index.html)")
        for s in found:
            owner, cls = owner_of(s, owners, pkgs)
            lines.append(f"  OWNER: {owner}" + (f" (via {cls})" if cls else ""))
            lines += ["    " + ln.strip() for ln in s.splitlines() if ln.strip()][:14]
            lines.append("")
    lines.append("Owner = first class in the suspect's text (accumulation point and path) shipped in a "
                 "plugin's jar (main package as fallback); 'server/JDK' when no plugin class appears. "
                 "Read the full path in the report zip before concluding.")
    text = "\n".join(lines) + "\n"
    print(text)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as fh:
            fh.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
