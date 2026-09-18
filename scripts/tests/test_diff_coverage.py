"""Unit tests for scripts/diff-coverage.py (SonarQube-matching changed-line coverage gate).

Tests exclusion rules matching SonarQube / SonarCloud configuration:
- Global: **/test/**, **/testFixtures/**, **/*Test*.java, **/*Benchmark*.java, **/testing/**, **/mock/**
- Project directories: platforms/**, addons/**, helpers/**, rtp-plugin/**, rtp-proxy/rtp-proxy-velocity/**
- Longest-suffix path matching and report parsing.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

# Load diff-coverage.py via importlib because of hyphen in filename
script_path = Path(__file__).resolve().parent.parent / "diff-coverage.py"
spec = importlib.util.spec_from_file_location("diff_coverage", script_path)
diff_coverage = importlib.util.module_from_spec(spec)
sys.modules["diff_coverage"] = diff_coverage
spec.loader.exec_module(diff_coverage)

is_sonar_excluded = diff_coverage.is_sonar_excluded
coverage_for_file = diff_coverage.coverage_for_file
SONAR_GLOBAL_EXCLUSIONS = diff_coverage.SONAR_GLOBAL_EXCLUSIONS
SONAR_PROJECT_DIR_EXCLUSIONS = diff_coverage.SONAR_PROJECT_DIR_EXCLUSIONS


class SonarExclusionTest(unittest.TestCase):
    def test_global_test_directory_excluded(self):
        self.assertTrue(is_sonar_excluded("rtp-core/src/test/java/io/github/dailystruggle/rtp/MyTest.java"))
        self.assertTrue(is_sonar_excluded("rtp-core\\src\\test\\java\\io\\github\\dailystruggle\\rtp\\MyTest.java"))
        self.assertTrue(is_sonar_excluded("api/maps-api/src/test/java/io/github/dailystruggle/Test.java"))

    def test_global_test_fixtures_excluded(self):
        self.assertTrue(is_sonar_excluded("rtp-core/src/testFixtures/java/io/github/dailystruggle/Fixture.java"))

    def test_global_test_filename_patterns_excluded(self):
        self.assertTrue(is_sonar_excluded("rtp-core/src/main/java/io/github/dailystruggle/rtp/RTPTestHelper.java"))
        self.assertTrue(is_sonar_excluded("rtp-core/src/main/java/io/github/dailystruggle/rtp/SpiralBenchmark.java"))
        self.assertTrue(is_sonar_excluded("rtp-core/src/main/java/io/github/dailystruggle/rtp/benchmark/Runner.java"))

    def test_global_mock_and_testing_directories_excluded(self):
        self.assertTrue(is_sonar_excluded("rtp-core/src/main/java/io/github/dailystruggle/rtp/mock/MockServer.java"))
        self.assertTrue(is_sonar_excluded("rtp-core/src/main/java/io/github/dailystruggle/rtp/testing/Harness.java"))

    def test_platform_adapter_directories_excluded(self):
        self.assertTrue(is_sonar_excluded("platforms/rtp-bukkit/rtp-bukkit-common/src/main/java/Foo.java"))
        self.assertTrue(is_sonar_excluded("platforms/rtp-paper/rtp-paper-v1_21_R1/src/main/java/Bar.java"))
        self.assertTrue(is_sonar_excluded("platforms/rtp-folia/rtp-folia-common/src/main/java/Baz.java"))
        self.assertTrue(is_sonar_excluded("platforms/rtp-fabric/rtp-fabric-common/src/main/java/Qux.java"))
        self.assertTrue(is_sonar_excluded("platforms/rtp-neoforge/rtp-neoforge-common/src/main/java/Quux.java"))

    def test_addons_and_helpers_directories_excluded(self):
        self.assertTrue(is_sonar_excluded("addons/LeafRTPGroupAddon/src/main/java/Group.java"))
        self.assertTrue(is_sonar_excluded("addons/LeafRTPLinearAddon/src/main/java/Linear.java"))
        self.assertTrue(is_sonar_excluded("helpers/StressTestRTP/src/main/java/Stress.java"))

    def test_plugin_and_velocity_proxy_excluded(self):
        self.assertTrue(is_sonar_excluded("rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/RTPPlugin.java"))
        self.assertTrue(is_sonar_excluded("rtp-proxy/rtp-proxy-velocity/src/main/java/VelocityEntry.java"))

    def test_platform_neutral_production_code_included(self):
        self.assertFalse(is_sonar_excluded("rtp-core/src/main/java/io/github/dailystruggle/rtp/common/RTP.java"))
        self.assertFalse(is_sonar_excluded("rtp-api/src/main/java/io/github/dailystruggle/rtp/api/RTPAPI.java"))
        self.assertFalse(is_sonar_excluded("api/commands-api/src/main/java/io/github/dailystruggle/commandsapi/Command.java"))
        self.assertFalse(is_sonar_excluded("api/maps-api/src/main/java/io/github/dailystruggle/mapsapi/render/Canvas.java"))
        self.assertFalse(is_sonar_excluded("api/anvil-api/src/main/java/io/github/dailystruggle/rtp/anvil/AnvilReader.java"))
        self.assertFalse(is_sonar_excluded("api/metrics-api/src/main/java/io/github/dailystruggle/metrics/Metric.java"))
        self.assertFalse(is_sonar_excluded("api/tags-api/src/main/java/io/github/dailystruggle/tags/Tag.java"))
        self.assertFalse(is_sonar_excluded("api/yaml-api/src/main/java/io/github/dailystruggle/yaml/Yaml.java"))
        self.assertFalse(is_sonar_excluded("rtp-proxy/rtp-proxy-common/src/main/java/io/github/dailystruggle/proxy/Proxy.java"))

    def test_custom_extra_exclusions(self):
        path = "rtp-core/src/main/java/io/github/dailystruggle/rtp/common/experimental/Feature.java"
        self.assertFalse(is_sonar_excluded(path))
        self.assertTrue(is_sonar_excluded(path, extra_exclusions=["**/experimental/**"]))


class CoverageForFileTest(unittest.TestCase):
    def test_longest_suffix_match(self):
        coverage = {
            "common/RTP.java": {10: True, 11: False},
            "io/github/dailystruggle/rtp/common/RTP.java": {10: True, 11: True},
        }
        res = coverage_for_file("rtp-core/src/main/java/io/github/dailystruggle/rtp/common/RTP.java", coverage)
        self.assertIsNotNone(res)
        self.assertTrue(res[11])

    def test_no_match_returns_none(self):
        coverage = {"other/File.java": {1: True}}
        self.assertIsNone(coverage_for_file("rtp-core/src/main/java/Foo.java", coverage))


if __name__ == "__main__":
    unittest.main()
