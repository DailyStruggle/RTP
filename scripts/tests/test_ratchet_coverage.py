#!/usr/bin/env python3
"""Unit tests for scripts/ratchet-coverage.py.

Validates:
1. XML parsing of JaCoCo reports (module and package counters).
2. Floor calculation logic (flooring with margin, never negative).
3. Monotonic ratcheting in build.gradle (floors only ratchet UPWARD, never downward).
4. Safety against missing reports or lower coverage runs.
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

# Load ratchet-coverage.py via importlib because of hyphen in filename
script_path = Path(__file__).resolve().parent.parent / "ratchet-coverage.py"
spec = importlib.util.spec_from_file_location("ratchet_coverage", script_path)
ratchet_coverage = importlib.util.module_from_spec(spec)
sys.modules["ratchet_coverage"] = ratchet_coverage
spec.loader.exec_module(ratchet_coverage)

calculate_ratchet_floor = ratchet_coverage.calculate_ratchet_floor
parse_report = ratchet_coverage.parse_report
ratchet_build_gradle = ratchet_coverage.ratchet_build_gradle


SAMPLE_JACOCO_XML = """<?xml version="1.0" encoding="UTF-8"?>
<report name="rtp-core">
    <package name="io/github/dailystruggle/rtp/common/tasks/teleport">
        <counter type="INSTRUCTION" missed="200" covered="800"/>
        <counter type="BRANCH" missed="40" covered="60"/>
    </package>
    <package name="io/github/dailystruggle/rtp/common/selection/region">
        <counter type="INSTRUCTION" missed="400" covered="600"/>
        <counter type="BRANCH" missed="50" covered="50"/>
    </package>
    <counter type="INSTRUCTION" missed="3000" covered="7000"/>
    <counter type="BRANCH" missed="400" covered="600"/>
</report>
"""

SAMPLE_BUILD_GRADLE = """
    def coverageFloors = [
            ':rtp-core': [
                    instruction: 0.55,
                    branch: 0.42,
                    packages: [
                            'io.github.dailystruggle.rtp.common.tasks.teleport': [instruction: 0.65, branch: 0.45],
                            'io.github.dailystruggle.rtp.common.selection.region': [instruction: 0.52, branch: 0.40],
                    ]
            ],
            ':yaml-api': [instruction: 0.92, branch: 0.80],
    ]
"""


class TestRatchetCoverage(unittest.TestCase):
    def test_calculate_ratchet_floor(self) -> None:
        # measured 0.80, margin 0.05 -> 0.75
        self.assertEqual(calculate_ratchet_floor(0.80, 0.05), 0.75)
        # measured 0.675, margin 0.05 -> 0.625 -> floor 0.62
        self.assertEqual(calculate_ratchet_floor(0.675, 0.05), 0.62)
        # measured 0.03, margin 0.05 -> 0.0
        self.assertEqual(calculate_ratchet_floor(0.03, 0.05), 0.0)

    def test_parse_report(self) -> None:
        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
            f.write(SAMPLE_JACOCO_XML)
            f_path = Path(f.name)

        try:
            mod_m, pkg_m = parse_report(f_path)
            # Module: 7000/(7000+3000) = 0.70
            self.assertAlmostEqual(mod_m.instruction.ratio, 0.70)
            self.assertAlmostEqual(mod_m.branch.ratio, 0.60)

            # Teleport: 800/(800+200) = 0.80
            teleport = pkg_m.get("io.github.dailystruggle.rtp.common.tasks.teleport")
            self.assertIsNotNone(teleport)
            self.assertAlmostEqual(teleport.instruction.ratio, 0.80)
            self.assertAlmostEqual(teleport.branch.ratio, 0.60)
        finally:
            f_path.unlink(missing_ok=True)

    def test_ratchet_upward_only(self) -> None:
        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
            f.write(SAMPLE_JACOCO_XML)
            f_path = Path(f.name)

        try:
            reports = {":rtp-core": f_path}
            updated, logs = ratchet_build_gradle(SAMPLE_BUILD_GRADLE, reports, margin=0.05)
            # Module instruction: 0.70 - 0.05 = 0.65 (was 0.55 -> ratcheted!)
            self.assertIn("instruction: 0.65", updated)
            # Module branch: 0.60 - 0.05 = 0.55 (was 0.42 -> ratcheted!)
            self.assertIn("branch: 0.55", updated)
            # Teleport package instruction: 0.80 - 0.05 = 0.75 (was 0.65 -> ratcheted!)
            self.assertIn("instruction: 0.75", updated)
            # Teleport package branch: 0.60 - 0.05 = 0.55 (was 0.45 -> ratcheted!)
            self.assertIn("branch: 0.55", updated)
            # Selection package instruction: 600/1000 = 0.60. 0.60 - 0.05 = 0.55 (was 0.52 -> ratcheted!)
            self.assertIn("instruction: 0.55", updated)
            # Selection package branch: 50/100 = 0.50. 0.50 - 0.05 = 0.45 (was 0.40 -> ratcheted!)
            self.assertIn("branch: 0.45", updated)
            self.assertTrue(len(logs) > 0)
        finally:
            f_path.unlink(missing_ok=True)

    def test_never_ratchet_downward(self) -> None:
        # Build gradle already has higher floors than measured
        high_floors_gradle = """
    def coverageFloors = [
            ':rtp-core': [
                    instruction: 0.90,
                    branch: 0.80,
                    packages: [
                            'io.github.dailystruggle.rtp.common.tasks.teleport': [instruction: 0.90, branch: 0.80],
                    ]
            ],
    ]
        """
        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
            f.write(SAMPLE_JACOCO_XML)
            f_path = Path(f.name)

        try:
            reports = {":rtp-core": f_path}
            updated, logs = ratchet_build_gradle(high_floors_gradle, reports, margin=0.05)
            # No changes should be made because measured coverage is lower than 0.90/0.80
            self.assertEqual(len(logs), 0)
            self.assertIn("instruction: 0.90", updated)
            self.assertIn("branch: 0.80", updated)
        finally:
            f_path.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
