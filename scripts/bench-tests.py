#!/usr/bin/env python3
"""Benchmark and categorize the JUnit test suites from Gradle's XML results.

Parses the `TEST-*.xml` reports Gradle writes under each module's
`build/test-results/<task>/` directory and prints a ranked, machine-readable
report of per-suite wall time, test count, and per-test average. It also flags
the suites that dominate the run so they can be considered for the two-tier
mechanism (`@Tag("slow")` / `@Tag("edge")`; see the root `build.gradle`
`subprojects` test block and ADR-080 for the opt-in `simulation` tier).

This does NOT run tests. Run the suite first, e.g.:

    .\\gradlew.bat :rtp-core:test            # default (main) tier
    .\\gradlew.bat :rtp-core:test -PfullTests  # main + edge/slow

then point this script at the results:

    python scripts/bench-tests.py rtp-core/build/test-results/test

With no argument it discovers every `build/test-results/*/` directory under the
repository root. Stdlib only (REQ-RTP-SYS environment: Python 3.12+).
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Suite:
    name: str
    tests: int
    failures: int
    errors: int
    skipped: int
    time: float

    @property
    def per_test(self) -> float:
        run = self.tests - self.skipped
        return self.time / run if run > 0 else 0.0


# Suites at or above this wall time (seconds) are called out as slow-tier
# candidates. Purely advisory: purpose (edge case vs. main path) still decides
# the tag, not wall time alone.
SLOW_SUITE_SECONDS = 8.0


def parse_suite(xml_path: Path) -> Suite | None:
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError:
        return None
    if root.tag != "testsuite":
        return None
    g = root.get
    return Suite(
        name=g("name", xml_path.stem),
        tests=int(g("tests", "0")),
        failures=int(g("failures", "0")),
        errors=int(g("errors", "0")),
        skipped=int(g("skipped", "0")),
        time=float(g("time", "0") or 0.0),
    )


def discover_result_dirs(root: Path) -> list[Path]:
    found = []
    for results in root.rglob("build/test-results"):
        for task_dir in results.iterdir():
            if task_dir.is_dir() and any(task_dir.glob("TEST-*.xml")):
                found.append(task_dir)
    return sorted(found)


def collect(result_dir: Path) -> list[Suite]:
    suites = []
    for xml_path in sorted(result_dir.glob("TEST-*.xml")):
        suite = parse_suite(xml_path)
        if suite is not None:
            suites.append(suite)
    return suites


def report(result_dir: Path, suites: list[Suite]) -> None:
    if not suites:
        print(f"(no TEST-*.xml suites found in {result_dir})")
        return

    total_time = sum(s.time for s in suites)
    total_tests = sum(s.tests for s in suites)
    total_skipped = sum(s.skipped for s in suites)
    total_fail = sum(s.failures + s.errors for s in suites)

    print(f"\n=== {result_dir} ===")
    print(
        f"suites={len(suites)} tests={total_tests} skipped={total_skipped} "
        f"failed={total_fail} cumulative-suite-time={total_time:,.1f}s"
    )
    print("(cumulative suite-time; wall time is lower under parallel forks)\n")

    ranked = sorted(suites, key=lambda s: s.time, reverse=True)
    width = max(len(s.name) for s in ranked)
    print(f"{'SUITE'.ljust(width)}  {'TESTS':>6}  {'TIME(s)':>9}  {'AVG(s)':>8}  FLAG")
    print("-" * (width + 32))
    for s in ranked:
        flag = "SLOW?" if s.time >= SLOW_SUITE_SECONDS else ""
        if s.failures or s.errors:
            flag = "FAIL"
        print(
            f"{s.name.ljust(width)}  {s.tests:>6}  {s.time:>9.3f}  "
            f"{s.per_test:>8.3f}  {flag}"
        )

    slow = [s for s in ranked if s.time >= SLOW_SUITE_SECONDS]
    if slow:
        print(
            f"\n{len(slow)} suite(s) >= {SLOW_SUITE_SECONDS:.0f}s. Consider "
            f'@Tag("slow") for characterization/benchmark suites or @Tag("edge") '
            f"for environment-specific ones so the default build tier skips them "
            f"(run the full set with -PfullTests)."
        )


def main(argv: list[str]) -> int:
    if len(argv) > 1:
        targets = [Path(argv[1])]
    else:
        targets = discover_result_dirs(Path.cwd())
        if not targets:
            print("No build/test-results/*/ directories with TEST-*.xml found.")
            print("Run a test task first, e.g. .\\gradlew.bat :rtp-core:test")
            return 1

    for target in targets:
        report(target, collect(target))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
