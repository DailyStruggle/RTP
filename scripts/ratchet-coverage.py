#!/usr/bin/env python3
"""Coverage ratchet script for build.gradle jacocoTestCoverageVerification floors.

Reads JaCoCo XML reports from a green test/coverage run and ratchets the
`coverageFloors` entries in root build.gradle upward. Never downward.
Satisfies ENTERPRISE_READINESS.md section 4.1 item 5.

Usage:
    python scripts/ratchet-coverage.py                  # dry run by default
    python scripts/ratchet-coverage.py --apply          # apply updates to build.gradle
    python scripts/ratchet-coverage.py --margin 0.05    # safety margin below measured (default: 0.05)
    python scripts/ratchet-coverage.py --report path/to/jacocoTestReport.xml

Stdlib only (Python 3.12+).
"""

from __future__ import annotations

import argparse
import math
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
BUILD_GRADLE = REPO_ROOT / "build.gradle"


@dataclass
class Ratio:
    covered: int
    missed: int

    @property
    def total(self) -> int:
        return self.covered + self.missed

    @property
    def ratio(self) -> float:
        return self.covered / self.total if self.total > 0 else 0.0


@dataclass
class Metrics:
    instruction: Ratio
    branch: Ratio


def parse_counters(element: ET.Element) -> Metrics:
    inst = Ratio(0, 0)
    br = Ratio(0, 0)
    for c in element.findall("counter"):
        t = c.get("type")
        covered = int(c.get("covered", "0"))
        missed = int(c.get("missed", "0"))
        if t == "INSTRUCTION":
            inst = Ratio(covered, missed)
        elif t == "BRANCH":
            br = Ratio(covered, missed)
    return Metrics(instruction=inst, branch=br)


def parse_report(report_path: Path) -> tuple[Metrics, dict[str, Metrics]]:
    """Parse JaCoCo XML into module-level metrics and per-package metrics."""
    root = ET.parse(report_path).getroot()
    module_metrics = parse_counters(root)
    pkg_metrics: dict[str, Metrics] = {}
    for p in root.findall("package"):
        raw_name = p.get("name", "")
        dotted_name = raw_name.replace("/", ".")
        pkg_metrics[dotted_name] = parse_counters(p)
    return module_metrics, pkg_metrics


def discover_reports(root: Path) -> dict[str, Path]:
    """Find JaCoCo XML reports and associate them with module paths."""
    reports: dict[str, Path] = {}
    for xml_path in sorted(root.rglob("build/reports/jacoco/**/*.xml")):
        parts = xml_path.parts
        # e.g., root/rtp-core/build/reports/jacoco/test/jacocoTestReport.xml
        # Find which subdirectory before 'build' is the module
        try:
            build_idx = parts.index("build")
        except ValueError:
            continue
        if build_idx == 0:
            continue
        rel_mod_parts = parts[:build_idx]
        # Calculate module path relative to root
        try:
            rel_path = xml_path.relative_to(root)
            mod_dirs = rel_path.parts[:rel_path.parts.index("build")]
            mod_path = ":" + ":".join(mod_dirs) if mod_dirs else ":"
            # If report is under a specific test task, prefer jacocoTestReport.xml
            if xml_path.name == "jacocoTestReport.xml":
                reports[mod_path] = xml_path
            elif mod_path not in reports:
                reports[mod_path] = xml_path
        except ValueError:
            continue
    return reports


def calculate_ratchet_floor(measured: float, margin: float, precision: int = 2) -> float:
    """Calculate floored threshold with safety margin, rounded to precision (e.g. 0.01)."""
    val = measured - margin
    if val <= 0:
        return 0.0
    factor = 10 ** precision
    # Use standard round to avoid floating-point representation drift (e.g. 0.65 - 0.05 = 0.6499999999999999)
    # while staying conservatively within 1e-9 tolerance before flooring
    return math.floor(round(val * factor, 8)) / factor


def ratchet_build_gradle(
    content: str,
    reports: dict[str, Path],
    margin: float = 0.05,
) -> tuple[str, list[str]]:
    """Inspect and ratchet coverageFloors in build.gradle content."""
    lines = content.splitlines(keepends=True)
    log_messages: list[str] = []

    # Parse reports
    module_data: dict[str, tuple[Metrics, dict[str, Metrics]]] = {}
    for mod_path, rep_path in reports.items():
        try:
            module_data[mod_path] = parse_report(rep_path)
        except Exception as e:
            log_messages.append(f"Warning: Failed to parse report {rep_path}: {e}")

    # Process build.gradle coverageFloors block
    in_floors = False
    current_mod: str | None = None
    current_pkg: str | None = None
    new_lines: list[str] = []

    re_mod = re.compile(r"^\s*'(:[^']+)'\s*:\s*\[")
    re_pkg = re.compile(r"^\s*'([a-zA-Z0-9_\.]+)'\s*:\s*\[")
    re_metric = re.compile(r"^(?P<indent>\s*)(?P<key>instruction|branch)\s*:\s*(?P<val>[0-9\.]+)(?P<comma>,?)(?P<rest>.*)$")

    for line in lines:
        if "def coverageFloors = [" in line:
            in_floors = True
            new_lines.append(line)
            continue
        if in_floors:
            if re.match(r"^\s*\]", line):
                in_floors = False
                current_mod = None
                current_pkg = None
                new_lines.append(line)
                continue

            m_mod = re_mod.search(line)
            if m_mod:
                current_mod = m_mod.group(1)
                current_pkg = None
                new_lines.append(line)
                continue

            if "packages:" in line:
                new_lines.append(line)
                continue

            m_pkg = re_pkg.search(line)
            if m_pkg and current_mod:
                current_pkg = m_pkg.group(1)
                # Check if it has inline instruction/branch
                # e.g.: 'pkg': [instruction: 0.65, branch: 0.45],
                inline_match = re.search(r"instruction\s*:\s*([0-9\.]+).*branch\s*:\s*([0-9\.]+)", line)
                if inline_match and current_mod in module_data:
                    mod_metrics, pkg_metrics = module_data[current_mod]
                    if current_pkg in pkg_metrics:
                        pkg_m = pkg_metrics[current_pkg]
                        orig_inst = float(inline_match.group(1))
                        orig_br = float(inline_match.group(2))
                        new_inst = orig_inst
                        new_br = orig_br

                        measured_inst = pkg_m.instruction.ratio
                        ratchet_inst = calculate_ratchet_floor(measured_inst, margin)
                        if ratchet_inst > orig_inst:
                            new_inst = ratchet_inst
                            log_messages.append(
                                f"Ratcheted {current_mod} package '{current_pkg}' instruction floor: "
                                f"{orig_inst:.2f} -> {new_inst:.2f} (measured {measured_inst:.1%})"
                            )

                        measured_br = pkg_m.branch.ratio
                        ratchet_br = calculate_ratchet_floor(measured_br, margin)
                        if ratchet_br > orig_br:
                            new_br = ratchet_br
                            log_messages.append(
                                f"Ratcheted {current_mod} package '{current_pkg}' branch floor: "
                                f"{orig_br:.2f} -> {new_br:.2f} (measured {measured_br:.1%})"
                            )

                        if new_inst != orig_inst or new_br != orig_br:
                            updated_line = re.sub(
                                r"instruction\s*:\s*[0-9\.]+",
                                f"instruction: {new_inst:.2f}",
                                line,
                            )
                            updated_line = re.sub(
                                r"branch\s*:\s*[0-9\.]+",
                                f"branch: {new_br:.2f}",
                                updated_line,
                            )
                            new_lines.append(updated_line)
                            continue

                new_lines.append(line)
                continue

            m_metric = re_metric.search(line)
            if m_metric and current_mod in module_data:
                key = m_metric.group("key")
                curr_val = float(m_metric.group("val"))
                mod_metrics, pkg_metrics = module_data[current_mod]

                metrics_to_check = (
                    pkg_metrics.get(current_pkg) if current_pkg else mod_metrics
                )
                target_ratio = (
                    metrics_to_check.instruction.ratio
                    if key == "instruction"
                    else metrics_to_check.branch.ratio
                ) if metrics_to_check else 0.0

                ratchet_val = calculate_ratchet_floor(target_ratio, margin)
                # CRITICAL: Only ratchet UPWARD. Never downward.
                if ratchet_val > curr_val:
                    target_name = (
                        f"{current_mod} package '{current_pkg}'"
                        if current_pkg
                        else current_mod
                    )
                    log_messages.append(
                        f"Ratcheted {target_name} {key} floor: "
                        f"{curr_val:.2f} -> {ratchet_val:.2f} (measured {target_ratio:.1%})"
                    )
                    indent = m_metric.group("indent")
                    comma = m_metric.group("comma")
                    rest = m_metric.group("rest")
                    new_line = f"{indent}{key}: {ratchet_val:.2f}{comma}{rest}\n"
                    new_lines.append(new_line)
                    continue

        new_lines.append(line)

    return "".join(new_lines), log_messages


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Ratchet coverageFloors in build.gradle upward from JaCoCo XML reports."
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Apply changes to build.gradle (default is dry-run mode).",
    )
    parser.add_argument(
        "--margin",
        type=float,
        default=0.05,
        help="Safety margin below measured ratio (default: 0.05, i.e. 5%%).",
    )
    parser.add_argument(
        "--report",
        action="append",
        default=[],
        help="Explicit JaCoCo XML report path (repeatable). Otherwise auto-discovered.",
    )
    parser.add_argument(
        "--gradle-file",
        type=Path,
        default=BUILD_GRADLE,
        help="Path to build.gradle file (default: root build.gradle).",
    )

    args = parser.parse_args(argv[1:])

    reports: dict[str, Path] = {}
    if args.report:
        for r in args.report:
            p = Path(r)
            if not p.is_file():
                print(f"Error: Report file not found: {p}", file=sys.stderr)
                return 1
            reports[f":{p.stem}"] = p
    else:
        reports = discover_reports(REPO_ROOT)

    if not reports:
        print(
            "No JaCoCo XML reports found. Run a coverage build first:\n"
            "  .\\gradlew.bat test jacocoTestReport -Pcoverage",
            file=sys.stderr,
        )
        return 1

    print(f"Found {len(reports)} JaCoCo report(s):")
    for mod, path in sorted(reports.items()):
        print(f"  {mod} -> {path}")

    gradle_file: Path = args.gradle_file
    if not gradle_file.is_file():
        print(f"Error: Gradle file not found: {gradle_file}", file=sys.stderr)
        return 1

    content = gradle_file.read_text(encoding="utf-8")
    new_content, logs = ratchet_build_gradle(content, reports, margin=args.margin)

    if not logs:
        print("\nAll coverageFloors are already at or above their measured values. No ratchet needed.")
        return 0

    print("\nProposed ratchet updates:")
    for msg in logs:
        print(f"  + {msg}")

    if args.apply:
        gradle_file.write_text(new_content, encoding="utf-8")
        print(f"\nApplied updates to {gradle_file}.")
    else:
        print("\nDry-run complete. Run with --apply to write changes to build.gradle.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
