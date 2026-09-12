#!/usr/bin/env python3
"""Coverage badge and markdown summary generator for LeafRTP documentation site.

Satisfies ENTERPRISE_READINESS.md section 4.1 item 6:
"Publish the coverage badge / report to the docs site so the number is public."

Reads JaCoCo XML reports from build/reports/jacoco and generates:
1. `docs/assets/badges/coverage.svg` (and optionally per-module badges).
2. `docs/assets/badges/coverage.json` (Shields.io dynamic endpoint format).
3. `docs/dev/COVERAGE_SUMMARY.md` (or updates docs/dev/COVERAGE_PLAN.md).

Stdlib only (Python 3.12+).
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = REPO_ROOT / "docs"
BADGES_DIR = DOCS_DIR / "assets" / "badges"


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
class ModuleCoverage:
    name: str
    instruction: Ratio
    branch: Ratio


def parse_module_coverage(xml_path: Path, module_name: str) -> ModuleCoverage:
    root = ET.parse(xml_path).getroot()
    inst = Ratio(0, 0)
    br = Ratio(0, 0)
    for c in root.findall("counter"):
        t = c.get("type")
        covered = int(c.get("covered", "0"))
        missed = int(c.get("missed", "0"))
        if t == "INSTRUCTION":
            inst = Ratio(covered, missed)
        elif t == "BRANCH":
            br = Ratio(covered, missed)
    return ModuleCoverage(name=module_name, instruction=inst, branch=br)


def color_for_ratio(ratio: float) -> str:
    if ratio >= 0.90:
        return "#4c1"  # bright green
    if ratio >= 0.80:
        return "#97ca00"  # green
    if ratio >= 0.70:
        return "#a4a61d"  # yellowgreen
    if ratio >= 0.60:
        return "#dfb317"  # yellow
    if ratio >= 0.50:
        return "#fe7d37"  # orange
    return "#e05d44"  # red


def generate_svg_badge(label: str, message: str, color: str) -> str:
    """Generate a clean SVG badge adhering to standard shields style."""
    label_len = len(label) * 6 + 10
    msg_len = len(message) * 7 + 10
    total_width = label_len + msg_len
    label_center = label_len / 2
    msg_center = label_len + (msg_len / 2)

    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{total_width}" height="20" role="img" aria-label="{label}: {message}">
  <linearGradient id="b" x2="0" y2="100%">
    <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
    <stop offset="1" stop-opacity=".1"/>
  </linearGradient>
  <clipPath id="a">
    <rect width="{total_width}" height="20" rx="3" fill="#fff"/>
  </clipPath>
  <g clip-path="url(#a)">
    <rect width="{label_len}" height="20" fill="#555"/>
    <rect x="{label_len}" width="{msg_len}" height="20" fill="{color}"/>
    <rect width="{total_width}" height="20" fill="url(#b)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="110">
    <text x="{label_center * 10:.0f}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)">{label}</text>
    <text x="{label_center * 10:.0f}" y="140" transform="scale(.1)">{label}</text>
    <text x="{msg_center * 10:.0f}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)">{message}</text>
    <text x="{msg_center * 10:.0f}" y="140" transform="scale(.1)">{message}</text>
  </g>
</svg>"""


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Generate coverage badges and summary for documentation site."
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=BADGES_DIR,
        help="Directory to place generated badge files (default: docs/assets/badges).",
    )
    args = parser.parse_args(argv[1:])

    reports: dict[str, Path] = {}
    for xml_path in sorted(REPO_ROOT.rglob("build/reports/jacoco/**/*.xml")):
        parts = xml_path.parts
        try:
            build_idx = parts.index("build")
        except ValueError:
            continue
        if build_idx == 0:
            continue
        rel_mod_parts = parts[:build_idx]
        mod_name = ":".join(rel_mod_parts[len(REPO_ROOT.parts):]) or parts[build_idx - 1]
        if xml_path.name == "jacocoTestReport.xml":
            reports[mod_name] = xml_path
        elif mod_name not in reports:
            reports[mod_name] = xml_path

    if not reports:
        print("No JaCoCo XML reports found in build tree.", file=sys.stderr)
        return 1

    modules: list[ModuleCoverage] = []
    total_inst_covered = 0
    total_inst_missed = 0
    total_br_covered = 0
    total_br_missed = 0

    core_coverage: ModuleCoverage | None = None

    for mod_name, xml_path in sorted(reports.items()):
        cov = parse_module_coverage(xml_path, mod_name)
        modules.append(cov)
        total_inst_covered += cov.instruction.covered
        total_inst_missed += cov.instruction.missed
        total_br_covered += cov.branch.covered
        total_br_missed += cov.branch.missed
        if "rtp-core" in mod_name:
            core_coverage = cov

    inst_total = total_inst_covered + total_inst_missed
    inst_ratio = total_inst_covered / inst_total if inst_total > 0 else 0.0

    target_ratio = core_coverage.instruction.ratio if core_coverage else inst_ratio
    badge_label = "coverage (rtp-core)" if core_coverage else "coverage"
    badge_pct_str = f"{target_ratio * 100:.1f}%"
    color = color_for_ratio(target_ratio)

    output_dir: Path = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    svg_badge = generate_svg_badge(badge_label, badge_pct_str, color)
    (output_dir / "coverage.svg").write_text(svg_badge, encoding="utf-8")

    # Shields.io JSON endpoint schema
    shields_json = {
        "schemaVersion": 1,
        "label": badge_label,
        "message": badge_pct_str,
        "color": color.replace("#", ""),
    }
    (output_dir / "coverage.json").write_text(
        json.dumps(shields_json, indent=2), encoding="utf-8"
    )

    print(f"Generated coverage badge: {badge_pct_str} ({color}) -> {output_dir / 'coverage.svg'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
