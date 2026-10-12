"""Unit tests for scripts/folia_spark_check.py (region-thread sample check)."""
from __future__ import annotations

import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import folia_spark_check as fsc  # noqa: E402


class RegionWeightTest(unittest.TestCase):
    def test_region_threads_counted_server_thread_not(self):
        top = [{"thread": "Server thread", "weight": 500.0},
               {"thread": "Folia Region Scheduler Thread", "weight": 120.0},
               {"thread": "Region Scheduler Thread", "weight": 30.0},
               {"thread": "RTP-Anvil-IO", "weight": 9.0}]
        self.assertEqual(fsc.region_weight(top), 150.0)

    def test_paper_style_profile_has_no_region_weight(self):
        self.assertEqual(fsc.region_weight([{"thread": "Server thread", "weight": 59456.0}]), 0.0)
        self.assertEqual(fsc.region_weight([]), 0.0)


class NewestProfilesTest(unittest.TestCase):
    def test_filters_by_age_and_orders_newest_first(self):
        with tempfile.TemporaryDirectory() as d:
            now = time.time()
            for name, age in (("old", 7200), ("a", 600), ("b", 60)):
                p = os.path.join(d, name + ".sparkprofile")
                open(p, "wb").close()
                os.utime(p, (now - age, now - age))
            open(os.path.join(d, "x.txt"), "w").close()
            got = [os.path.basename(p) for p in fsc.newest_profiles(d, now - 1800, 5)]
            self.assertEqual(got, ["b.sparkprofile", "a.sparkprofile"])
            self.assertEqual(len(fsc.newest_profiles(d, now - 1800, 1)), 1)


if __name__ == "__main__":
    unittest.main()
