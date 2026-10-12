"""Unit tests for scripts/bench_matrix.py (per-plugin Folia matrix sequencer).

Covers the off-server logic: swarm stats-file tail parsing, bandwidth poller
staleness (NOT MEASURED as -1), plan order across passes, ramp idle/warm-up
config parsing, resume keys, and run_matrix against a fake driver and server
directory: outcomes, run-file and log capture, one-attempt handling of an
unsupported plugin, and resume skipping finished rows.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import bench_matrix as bm  # noqa: E402
import bench_overnight as bo  # noqa: E402


def write_stats(path, rows):
    with open(path, "a", encoding="utf-8") as fh:
        for w, t, rx, tx, online in rows:
            fh.write(json.dumps({"t": t, "w": w, "rx": rx, "tx": tx, "online": online}) + "\n")


class SwarmStatsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "s.jsonl")

    def tearDown(self):
        self.tmp.cleanup()

    def test_latest_per_worker_and_partial_line_ignored(self):
        write_stats(self.path, [(0, 1000, 10, 1, 4), (1, 1000, 20, 2, 4), (0, 2000, 15, 3, 5)])
        with open(self.path, "a", encoding="utf-8") as fh:
            fh.write('{"t": 3000, "w": 1, "rx"')
        got = bm.read_swarm_stats(self.path)
        self.assertEqual(got[0]["rx"], 15)
        self.assertEqual(got[1]["rx"], 20)

    def test_missing_file_is_empty(self):
        self.assertEqual(bm.read_swarm_stats(os.path.join(self.tmp.name, "none")), {})

    def test_poller_sums_workers_then_flags_stale(self):
        write_stats(self.path, [(0, 1000, 100, 10, 3), (1, 1000, 50, 5, 2)])
        p = bm.BandwidthPoller(self.path, os.path.join(self.tmp.name, "bw.csv"), 1.0, 20.0)
        p.label = "tag"
        row = p.sample(now=100.0)
        self.assertEqual(row[1:7], ["tag", 150, 15, 5, 2, 2])
        self.assertEqual(row[7], -1, "no swaps field: swarm not run with --swap-stuck")
        row = p.sample(now=110.0)  # unchanged, still within stale_s
        self.assertEqual(row[2], 150)
        row = p.sample(now=125.0)  # unchanged for 25 s
        self.assertEqual(row[2:6], [-1, -1, -1, 0])
        write_stats(self.path, [(0, 2000, 160, 12, 3), (1, 2000, 60, 6, 2)])
        self.assertEqual(p.sample(now=130.0)[2], 220)


class SwapsAndLogWatcherTest(unittest.TestCase):
    def test_poller_sums_swaps_when_reported(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "s.jsonl")
            for w, swaps in ((0, 2), (1, 1)):
                with open(path, "a", encoding="utf-8") as fh:
                    fh.write(json.dumps({"t": 1, "w": w, "rx": 1, "tx": 1, "online": 1, "swaps": swaps}) + "\n")
            p = bm.BandwidthPoller(path, os.path.join(d, "bw.csv"), 1.0, 20.0)
            self.assertEqual(p.sample(now=1.0)[7], 3)
            self.assertEqual(p.swaps, 3)

    def test_log_watcher_counts_and_first_line(self):
        w = bm.LogWatcher("/nonexistent", "ezrtp", tag="t")
        w.feed("[12:00:00] [Server thread/INFO]: all fine\n"
               "[12:00:01] [Server thread/ERROR]: Cannot retrieve chunk asynchronously from a region thread\n"
               "[12:00:02] [Server thread/ERROR]: [EzRTP] teleport failed\n"
               "[12:00:03] [Server thread/ERROR]: [StressTestRTP] not the target\n"
               "[12:00:04] [Server thread/ERROR]: Tick region located in world 'w' has not responded in 6000ms\n"
               "[12:00:05] [Server thread/ERROR]: Could not pass event X to EzRTP v3.4.3\n"
               "[12:00:06] [Server thread/ERROR]: [EzRTP] partial")
        self.assertEqual(w.counts, {"chunk_async": 1, "watchdog": 1, "plugin_errors": 2})
        self.assertIn("Cannot retrieve chunk asynchronously", w.first_line)
        w.feed(" line done\n")  # the incomplete line completes and counts
        self.assertEqual(w.counts["plugin_errors"], 3)

    def test_log_watcher_follows_appends_and_restarts(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "latest.log")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("[12:00:00] [Server thread/ERROR]: [EzRTP] old session line\n")
            w = bm.LogWatcher(path, "ezrtp")  # starts at the current end: old lines are not counted
            w.poll()
            self.assertEqual(w.counts["plugin_errors"], 0)
            with open(path, "a", encoding="utf-8") as fh:
                fh.write("[12:00:01] [Server thread/ERROR]: [EzRTP] new line\n")
            w.poll()
            self.assertEqual(w.counts["plugin_errors"], 1)
            with open(path, "w", encoding="utf-8") as fh:  # rotated: shorter file restarts at 0
                fh.write("[12:00:02] [Server thread/ERROR]: [EzRTP] x\n")
            w.poll()
            self.assertEqual(w.counts["plugin_errors"], 2)


class PlanTest(unittest.TestCase):
    def test_even_pass_reverses_targets(self):
        plan = bm.build_plan(["a", "b"], [("5", 60), ("10", 60)], 2)
        self.assertEqual(plan[:4], [(1, 1, "a"), (1, 2, "a"), (1, 1, "b"), (1, 2, "b")])
        self.assertEqual(plan[4:], [(2, 1, "b"), (2, 2, "b"), (2, 1, "a"), (2, 2, "a")])

    def test_ramp_cfg_parse(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "plugins", "StressTestRTP"))
            with open(os.path.join(d, "plugins", "StressTestRTP", "config.yml"), "w", encoding="utf-8") as fh:
                fh.write("sequence:\n  warmup-seconds: 99\nramp:\n  stage-seconds: 60\n  # c\n  idle-seconds: 12\n"
                         "  warmup-seconds: 7\nspark:\n  enabled: true\n")
            self.assertEqual(bm.read_ramp_cfg(d), (12, 7))
            self.assertTrue(bm.spark_enabled(d))

    def test_done_keys(self):
        rows = [{"pass": "1", "round": "1", "target": "rtp", "outcome": "finished"},
                {"pass": "1", "round": "2", "target": "rtp", "outcome": "timed-out"},
                {"pass": "1", "round": "1", "target": "jakesrtp", "outcome": "unsupported-not-loaded"}]
        self.assertEqual(bm.done_keys(rows), {(1, 1, "rtp"), (1, 1, "jakesrtp")})


class FakeDriver(bm.MatrixDriver):
    """No RCON: restart/ramp are scripted and drop the files a real run would leave."""

    def __init__(self, a, server_dir, outcomes):
        super().__init__(a, "127.0.0.1", 1, "x")
        self.server_dir, self.outcomes, self.restarts, self.loaded = server_dir, outcomes, [], set()
        self.counter = 0

    def restart(self, next_target=None):
        self.restarts.append(next_target)
        want = self.names.get(next_target)
        self.loaded = {want} if want and next_target != "jakesrtp" else set()
        with open(os.path.join(self.server_dir, "logs", "latest.log"), "w", encoding="utf-8") as fh:
            fh.write(f"session for {next_target}\n")
        return want is None or next_target != "jakesrtp"

    def loaded_plugins(self):
        return set(self.loaded)

    def jar_set_ok(self, target):
        want = self.names.get(target)
        rtp = sorted(self.loaded)
        return (want is None or want in self.loaded), ",".join(rtp)

    def try_cmd(self, command):
        return "There are 48 of a max 100 players online" if command == "list" else ""

    def run_ramp(self, target, rates, secs):
        self.counter += 1
        stamp = f"20261011-00000{self.counter}"
        runs = os.path.join(self.server_dir, "plugins", "StressTestRTP", "runs")
        for suffix in ("-ramp.csv", "-ramp-summary.txt", "-phases.csv"):
            with open(os.path.join(runs, stamp + suffix), "w", encoding="utf-8") as fh:
                fh.write("x\n")
        self.ramp_t0 = 1000.0
        if target == "ezrtp":
            with open(os.path.join(self.server_dir, "logs", "latest.log"), "a", encoding="utf-8") as fh:
                fh.write("[12:00:01] [Server thread/ERROR]: Cannot retrieve chunk asynchronously\n")
        return self.outcomes.get(target, "finished"), "running=false"


class RunMatrixTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.server = os.path.join(self.tmp.name, "server")
        self.out = os.path.join(self.tmp.name, "out")
        for sub in ("logs", os.path.join("plugins", "StressTestRTP", "runs"), os.path.join("plugins", "RTP")):
            os.makedirs(os.path.join(self.server, sub))
        os.makedirs(self.out)
        with open(os.path.join(self.server, "plugins", "RTP", "config.yml"), "w", encoding="utf-8") as fh:
            fh.write("a: 1\n")
        self.a = argparse.Namespace(
            isolate=True, jars=bo.DEFAULT_JARS, names=bo.DEFAULT_NAMES, restart_retries=2, min_players=1,
            player_wait_s=1, settle_s=0, poll_s=1, shutdown_wait_s=1, startup_wait_s=1, ramp_idle_s=30,
            ramp_warmup_s=30, ramp_gap_s=5, attempt_timeout_s=5, margin_s=0, server_dir=self.server,
            min_free_gb=0.0, log_poll_s=0.05)
        self.poller = bm.BandwidthPoller(os.path.join(self.tmp.name, "none"), os.path.join(self.out, "bw.csv"), 1, 1)
        bo.LOG_FH = None

    def tearDown(self):
        self.tmp.cleanup()

    def manifest(self):
        return bm.read_manifest(os.path.join(self.out, "manifest.csv"))

    def test_outcomes_files_and_unsupported(self):
        drv = FakeDriver(self.a, self.server, {"betterrtp": "timed-out"})
        rounds = [("5", 60), ("10,20", 60)]
        plan = bm.build_plan(["direct", "rtp", "betterrtp", "jakesrtp"], rounds, 1)
        rc = bm.run_matrix(self.a, drv, self.poller, self.out, plan, rounds, ["jakesrtp"])
        self.assertEqual(rc, 0)
        rows = {(r["target"], r["round"]): r for r in self.manifest()}
        self.assertEqual(rows[("direct", "1")]["outcome"], "finished")
        self.assertEqual(rows[("rtp", "1")]["outcome"], "finished")
        self.assertEqual(rows[("rtp", "1")]["loaded_rtp_plugins"], "RTP")
        self.assertEqual(rows[("direct", "1")]["loaded_rtp_plugins"], "")
        self.assertEqual(rows[("betterrtp", "2")]["outcome"], "timed-out")
        self.assertEqual(rows[("jakesrtp", "1")]["outcome"], "unsupported-not-loaded")
        self.assertEqual(rows[("jakesrtp", "2")]["outcome"], "unsupported-not-loaded")
        self.assertEqual(drv.restarts.count("jakesrtp"), 1, "refused plugin is loaded once, not per round")
        self.assertEqual(drv.a.restart_retries, 0, "last target was unsupported")
        stamp = rows[("rtp", "1")]["run_stamp"]
        self.assertTrue(os.path.exists(os.path.join(self.out, "runs", "p1-r1-rtp", stamp + "-phases.csv")))
        self.assertEqual(rows[("rtp", "1")]["ramp_t0_epoch"], "1000.000")
        with open(os.path.join(self.out, rows[("rtp", "1")]["log_file"]), encoding="utf-8") as fh:
            self.assertIn("rtp", fh.read())
        self.assertTrue(rows[("rtp", "1")]["cfg_hash_start"])
        self.assertEqual(rows[("rtp", "1")]["cfg_hash_start"], rows[("rtp", "1")]["cfg_hash_end"])

    def test_live_log_findings_are_recorded(self):
        drv = FakeDriver(self.a, self.server, {})
        rounds = [("5", 60)]
        plan = bm.build_plan(["rtp", "ezrtp"], rounds, 1)
        bm.run_matrix(self.a, drv, self.poller, self.out, plan, rounds, [])
        rows = {r["target"]: r for r in self.manifest()}
        self.assertEqual(rows["ezrtp"]["live_chunk_async"], "1")
        self.assertIn("Cannot retrieve chunk asynchronously", rows["ezrtp"]["live_first_error"])
        self.assertEqual(rows["rtp"]["live_chunk_async"], "0")
        self.assertEqual(rows["rtp"]["live_first_error"], "")

    def test_resume_skips_done_rows(self):
        rounds = [("5", 60)]
        plan = bm.build_plan(["rtp", "betterrtp"], rounds, 1)
        drv = FakeDriver(self.a, self.server, {"betterrtp": "timed-out"})
        bm.run_matrix(self.a, drv, self.poller, self.out, plan, rounds, [])
        drv2 = FakeDriver(self.a, self.server, {})
        bm.run_matrix(self.a, drv2, self.poller, self.out, plan, rounds, [])
        self.assertEqual(drv2.restarts, ["betterrtp"], "only the timed-out row reruns")
        with open(os.path.join(self.out, "manifest.csv"), newline="", encoding="utf-8") as fh:
            self.assertEqual(len(list(csv.reader(fh))), 1 + 3)


if __name__ == "__main__":
    unittest.main()
