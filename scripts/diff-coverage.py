#!/usr/bin/env python3
"""Diff (changed-line) coverage gate, SonarQube-style, with no server required.

Cross-references the lines changed since a git baseline ref against JaCoCo's
per-line coverage and fails (exit 1) when the covered fraction of *changed,
instrumented* lines falls below a threshold. This is the local + CI half of the
coverage story: it runs offline, works on GitHub Actions and on a LAN runner,
and its exit code / summary line can drive a PR status check later.

Prerequisite: produce JaCoCo XML first (coverage is opt-in in this build):

    .\\gradlew.bat test jacocoTestReport -Pcoverage          # logic tier
    .\\gradlew.bat test jacocoTestReport -Pcoverage -PfullTests

Then gate the diff:

    python scripts/diff-coverage.py                    # vs origin/main, 80%
    python scripts/diff-coverage.py --baseline HEAD~1 --min 0.9
    python scripts/diff-coverage.py --report path/to/jacocoTestReport.xml

Only changed lines that JaCoCo actually instrumented count toward the ratio;
blank lines, comments, and lines with no bytecode are ignored (they never
appear in the report). Stdlib only (Python 3.12+).
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


# ---------------------------------------------------------------------------
# JaCoCo XML -> {source-path-suffix: {line_no: covered_bool}}
# ---------------------------------------------------------------------------
def load_coverage(report_paths: list[Path]) -> dict[str, dict[int, bool]]:
    coverage: dict[str, dict[int, bool]] = {}
    for report in report_paths:
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as exc:
            print(f"warning: could not parse {report}: {exc}", file=sys.stderr)
            continue
        for package in root.iter("package"):
            pkg = package.get("name", "")
            for sourcefile in package.findall("sourcefile"):
                # JaCoCo keys files by package dir + file name, e.g.
                # "io/github/dailystruggle/rtp/common/RTP.java".
                suffix = f"{pkg}/{sourcefile.get('name')}" if pkg else sourcefile.get("name", "")
                lines = coverage.setdefault(suffix, {})
                for line in sourcefile.findall("line"):
                    nr = int(line.get("nr", "0"))
                    covered = int(line.get("ci", "0")) > 0
                    # If several suites report the same line, covered wins.
                    lines[nr] = lines.get(nr, False) or covered
    return coverage


def discover_reports(root: Path) -> list[Path]:
    return sorted(root.rglob("build/reports/jacoco/**/*.xml"))


# ---------------------------------------------------------------------------
# git diff -> {file-path: set(changed line numbers on the new side)}
# ---------------------------------------------------------------------------
def changed_lines(baseline: str) -> dict[str, set[int]]:
    # --unified=0 keeps hunks minimal so only genuinely changed lines are
    # attributed. Diffing the baseline against the working tree captures both
    # committed and uncommitted changes since the ref (best for local dev; in
    # CI pass the PR base, e.g. origin/main).
    out = subprocess.run(
        ["git", "diff", "--unified=0", "--no-color", baseline, "--", "*.java"],
        capture_output=True,
        text=True,
        check=False,
    )
    if out.returncode != 0:
        print(f"error: git diff failed:\n{out.stderr}", file=sys.stderr)
        raise SystemExit(2)

    result: dict[str, set[int]] = {}
    current: str | None = None
    for raw in out.stdout.splitlines():
        if raw.startswith("+++ b/"):
            current = raw[6:].strip()
            result.setdefault(current, set())
        elif raw.startswith("@@") and current is not None:
            # @@ -old,cnt +new,cnt @@
            plus = raw.split("+", 1)[1].split(" ", 1)[0]
            start_str, _, count_str = plus.partition(",")
            start = int(start_str)
            count = int(count_str) if count_str else 1
            for n in range(start, start + count):
                result[current].add(n)
    return {f: lines for f, lines in result.items() if lines}


def coverage_for_file(
    path: str, coverage: dict[str, dict[int, bool]]
) -> dict[int, bool] | None:
    # Match a git path (e.g. rtp-core/src/main/java/io/.../RTP.java) to a JaCoCo
    # suffix (io/.../RTP.java) by longest suffix match.
    best: dict[int, bool] | None = None
    best_len = -1
    for suffix, lines in coverage.items():
        if path.endswith(suffix) and len(suffix) > best_len:
            best, best_len = lines, len(suffix)
    return best


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Changed-line coverage gate.")
    parser.add_argument("--baseline", default="origin/main",
                        help="git ref to diff against (default: origin/main)")
    parser.add_argument("--min", type=float, default=0.80,
                        help="minimum covered fraction of changed lines (default: 0.80)")
    parser.add_argument("--report", action="append", default=[],
                        help="explicit JaCoCo XML path (repeatable); "
                             "otherwise auto-discovered under build/reports/jacoco")
    args = parser.parse_args(argv[1:])

    root = Path.cwd()
    reports = [Path(r) for r in args.report] or discover_reports(root)
    if not reports:
        print("No JaCoCo XML reports found. Run with -Pcoverage first, e.g.\n"
              "  .\\gradlew.bat test jacocoTestReport -Pcoverage", file=sys.stderr)
        return 2

    coverage = load_coverage(reports)
    diff = changed_lines(args.baseline)
    if not diff:
        print(f"No changed .java lines vs {args.baseline}; nothing to gate.")
        return 0

    total = 0
    covered = 0
    uncovered_report: list[str] = []
    for path in sorted(diff):
        file_cov = coverage_for_file(path, coverage)
        if file_cov is None:
            continue  # not instrumented (no matching report) -> skip
        misses: list[int] = []
        for line_no in sorted(diff[path]):
            if line_no not in file_cov:
                continue  # blank/comment/no-bytecode line
            total += 1
            if file_cov[line_no]:
                covered += 1
            else:
                misses.append(line_no)
        if misses:
            uncovered_report.append(f"  {path}: uncovered {misses}")

    print(f"=== diff coverage vs {args.baseline} ===")
    print(f"reports: {len(reports)}  instrumented changed lines: {total}")
    if total == 0:
        print("No instrumented changed lines to measure; passing.")
        return 0

    ratio = covered / total
    print(f"covered {covered}/{total} = {ratio:.1%}  (threshold {args.min:.0%})")
    if uncovered_report:
        print("uncovered changed lines:")
        print("\n".join(uncovered_report))

    if ratio < args.min:
        print(f"FAIL: changed-line coverage {ratio:.1%} < {args.min:.0%}")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
