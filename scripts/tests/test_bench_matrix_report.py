"""Unit tests for scripts/bench_matrix_report.py (per-plugin matrix report).

Uses a captured Paper ramp run (fixtures/bench_matrix: EzRTP ladder, three
stages) with a synthetic manifest, bandwidth series and server log. Covers the
parsed stages, the idle-baseline bandwidth arithmetic (exact on a piecewise
constant byte rate), counter resets and -1 samples, the log patterns and
plugin attribution, and the NOT MEASURED / unknown markers.
"""
from __future__ import annotations

import csv
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import bench_matrix_report as rep  # noqa: E402

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "bench_matrix"
STAMP = "20261009-051201"
T0 = 1791522726.0  # stage 0 starts at server time 1791522786.891 = T0 + 60 + 0.891 clock offset
BASE_RATE = 1000
STAGE0_RATE = 100000

LOG = """\
[12:00:00] [Server thread/INFO]: [EzRTP] Enabling EzRTP v3.4.3
[12:00:05] [Folia Region Scheduler Thread #1/ERROR]: Tick region located in world 'world' has not responded in 5026ms
[12:00:06] [Folia Region Scheduler Thread #2/WARN]: Tick region located in world 'world' has not responded in 12000ms
[12:00:07] [Server thread/ERROR]: Cannot retrieve chunk asynchronously from a region thread
[12:00:07] [Server thread/ERROR]: Cannot retrieve chunk asynchronously from a region thread
[12:00:08] [Server thread/ERROR]: Thread Folia Region Scheduler Thread #3 failed main thread check: Cannot read world asynchronously
[12:00:09] [Server thread/ERROR]: Could not pass event PlayerTeleportEvent to EzRTP v3.4.3
java.lang.IllegalStateException: boom
\tat com.skyblockexp.ezrtp.teleport.Foo.run(Foo.java:10) ~[EzRTP.jar:?]
\tat org.bukkit.plugin.EventExecutor.execute(EventExecutor.java:1) ~[?:?]
Caused by: java.lang.NullPointerException: inner
\tat java.base/java.util.Objects.requireNonNull(Objects.java:233) ~[?:?]
\t... 3 more
[12:00:10] [Server thread/ERROR]: [StressTestRTP] harness hiccup
java.lang.RuntimeException: harness
\tat io.github.dailystruggle.helpers.stresstestrtp.Runner.go(Runner.java:5) ~[?:?]
[12:00:11] [Server thread/ERROR]: Error in vanilla
java.lang.IllegalArgumentException: vanilla
\tat net.minecraft.server.Foo.bar(Foo.java:1) ~[?:?]
[12:00:12] [Folia Region Scheduler Thread #1/ERROR]: Uncaught exception in tick thread "Folia Region Scheduler Thread #1"
[12:00:13] [Server thread/WARN]: Can't keep up! Is the server overloaded?
"""


def write_manifest(out, rows):
    cols = ["pass", "round", "target", "rates", "stage_seconds", "tag", "outcome", "wall_start", "wall_end",
            "ramp_t0_epoch", "ramp_t1_epoch", "idle_s", "warmup_s", "run_stamp", "loaded_rtp_plugins",
            "cfg_hash_start", "cfg_hash_end", "log_file", "last_status", "note", "swaps", "live_chunk_async",
            "live_watchdog", "live_first_error"]
    with open(os.path.join(out, "manifest.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(cols)
        for r in rows:
            w.writerow([r.get(c, "") for c in cols])


def write_bandwidth(out, samples):
    with open(os.path.join(out, "bandwidth.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(rep_cols := ["driver_epoch", "label", "rx_bytes", "tx_bytes", "online", "workers_fresh", "workers_seen"])
        for s in samples:
            w.writerow(s)
    return rep_cols


def synthetic_samples(dip=True):
    """rx rate BASE_RATE except STAGE0_RATE during stage 0 (T0+60..T0+120); tx is rx / 10."""
    out, cum, t = [], 0.0, T0 - 5.0
    while t <= T0 + 260.0:
        out.append([f"{t:.3f}", "x", int(cum), int(cum / 10), 45 if T0 + 140 <= t < T0 + 150 else 48, 1, 1])
        rate = STAGE0_RATE if T0 + 60 <= t < T0 + 120 else BASE_RATE
        cum += rate * 0.5
        t += 0.5
    return out


class ReportBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = self.tmp.name
        run_dir = os.path.join(self.out, "runs", "p1-r1-ezrtp")
        os.makedirs(run_dir)
        for f in FIXTURE.iterdir():
            shutil.copy(f, run_dir)
        os.makedirs(os.path.join(self.out, "logs"))
        with open(os.path.join(self.out, "logs", "p1-r1-ezrtp.log"), "w", encoding="utf-8") as fh:
            fh.write(LOG)
        self.row = {"pass": 1, "round": 1, "target": "ezrtp", "rates": "5,10,20", "stage_seconds": 60,
                    "tag": "p1-r1-ezrtp", "outcome": "finished", "ramp_t0_epoch": f"{T0:.3f}", "idle_s": 30,
                    "warmup_s": 30, "run_stamp": STAMP, "loaded_rtp_plugins": "EzRTP", "cfg_hash_start": "aa",
                    "cfg_hash_end": "aa", "log_file": "logs/p1-r1-ezrtp.log"}

    def tearDown(self):
        self.tmp.cleanup()

    def metric(self, rows, stage, name):
        for r in rows:
            if r[4].startswith(stage) and r[5] == name:
                return r[6]
        self.fail(f"no {name} for {stage}")


class StageParsingTest(ReportBase):
    def test_stages_and_phase_columns(self):
        write_manifest(self.out, [self.row])
        write_bandwidth(self.out, synthetic_samples())
        rows, md = rep.build(self.out)
        self.assertAlmostEqual(self.metric(rows, "s0", "achieved_tps"), 4.983)
        self.assertAlmostEqual(self.metric(rows, "s0", "success_rate"), 1.0)
        self.assertAlmostEqual(self.metric(rows, "s1", "success_rate"), 429 / 599)
        self.assertEqual(self.metric(rows, "s1", "latency_p99_ms"), 4813.0)
        self.assertAlmostEqual(self.metric(rows, "s0", "main_cpu_ms_per_attempt"), 81.154)
        self.assertAlmostEqual(self.metric(rows, "s0", "chunks_per_attempt"), 1.783)
        self.assertEqual(self.metric(rows, "s2", "gc_pause_ms"), 282.0)
        self.assertEqual(self.metric(rows, "s0", "region_tps_min"), -1.0)
        self.assertIn("HARNESS_SATURATED", self.metric(rows, "s2", "flags"))
        self.assertIn("NOT MEASURED", md)
        self.assertIn("## Round 1", md)

    def test_latency_max_from_per_teleport_csv(self):
        write_manifest(self.out, [self.row])
        rows, md = rep.build(self.out)
        self.assertEqual(self.metric(rows, "s0", "latency_max_ms"), 1504.0)
        self.assertEqual(self.metric(rows, "s1", "latency_max_ms"), 4954.0)
        self.assertEqual(self.metric(rows, "s2", "latency_max_ms"), 4968.0)
        self.assertGreater(self.metric(rows, "s2", "latency_max_failed_attempt_ms"), 4968.0)
        self.assertIn("p50 / p95 / p99 / max ms", md)

    def test_latency_max_not_measured_without_per_teleport_csv(self):
        os.remove(os.path.join(self.out, "runs", "p1-r1-ezrtp", STAMP + ".csv"))
        write_manifest(self.out, [self.row])
        rows, _ = rep.build(self.out)
        self.assertEqual(self.metric(rows, "s0", "latency_max_ms"), rep.NOT_MEASURED)
        self.assertEqual(self.metric(rows, "s0", "latency_p99_ms"), 908.0)

    def test_idle_baseline_net_bandwidth(self):
        write_manifest(self.out, [self.row])
        write_bandwidth(self.out, synthetic_samples())
        rows, _ = rep.build(self.out)
        mib = 1 << 20
        self.assertAlmostEqual(self.metric(rows, "s0", "rx_mib"), STAGE0_RATE * 60 / mib, places=4)
        net = (STAGE0_RATE - BASE_RATE) * 60
        self.assertAlmostEqual(self.metric(rows, "s0", "net_rx_mib"), net / mib, places=4)
        self.assertAlmostEqual(self.metric(rows, "s0", "net_rx_mib_per_teleport"), net / 299 / mib, places=6)
        self.assertAlmostEqual(self.metric(rows, "s0", "net_tx_mib"), net / 10 / mib, places=4)
        # stage 1 (off the sample grid by 50 ms) sits in the base rate: net is about zero
        self.assertLess(abs(self.metric(rows, "s1", "net_rx_mib")), 0.05 * 100000 * 60 / mib)

    def test_online_dip_is_flagged(self):
        write_manifest(self.out, [self.row])
        write_bandwidth(self.out, synthetic_samples())
        rows, _ = rep.build(self.out)
        self.assertIn("online-dip 48->45", self.metric(rows, "s1", "flags"))
        self.assertNotIn("online-dip", self.metric(rows, "s0", "flags"))

    def test_no_bandwidth_is_not_measured_not_zero(self):
        write_manifest(self.out, [self.row])
        rows, md = rep.build(self.out)
        self.assertEqual(self.metric(rows, "s0", "net_rx_mib"), rep.NOT_MEASURED)
        self.assertEqual(self.metric(rows, "s0", "rx_mib"), rep.NOT_MEASURED)

    def test_minus_one_samples_over_the_stage_are_not_measured(self):
        samples = synthetic_samples()
        for s in samples:
            if T0 + 60 <= float(s[0]) <= T0 + 120:
                s[2] = s[3] = s[4] = -1
        write_manifest(self.out, [self.row])
        write_bandwidth(self.out, samples)
        rows, _ = rep.build(self.out)
        self.assertEqual(self.metric(rows, "s0", "net_rx_mib"), rep.NOT_MEASURED)
        self.assertNotEqual(self.metric(rows, "s2", "net_rx_mib"), rep.NOT_MEASURED)

    def test_counter_reset_adds_nothing_and_is_flagged(self):
        samples = synthetic_samples()
        reset_at = T0 + 90
        base = None
        for s in samples:  # swarm restarted at reset_at: counters restart from zero
            if float(s[0]) >= reset_at:
                base = base if base is not None else int(s[2])
                s[2], s[3] = int(s[2]) - base, int(s[3]) - base // 10
        write_manifest(self.out, [self.row])
        write_bandwidth(self.out, samples)
        rows, _ = rep.build(self.out)
        self.assertIn("counter-reset", self.metric(rows, "s0", "flags"))
        self.assertLess(self.metric(rows, "s0", "rx_mib"), STAGE0_RATE * 60 / (1 << 20) + 0.01)


class SeriesTest(unittest.TestCase):
    def test_window_interpolates_and_needs_samples(self):
        rows = [{"driver_epoch": str(t), "rx_bytes": str(t * 10), "tx_bytes": str(t), "online": "1",
                 "workers_fresh": "1", "workers_seen": "1"} for t in range(0, 11)]
        s = rep.Series(rows)
        w = s.window(2.5, 7.5)
        self.assertAlmostEqual(w["rx"], 50.0)
        self.assertIsNone(s.window(20, 30))
        self.assertIsNone(s.window(3.2, 3.4))


class LogScanTest(ReportBase):
    def test_counts_and_attribution(self):
        res = rep.scan_log(os.path.join(self.out, "logs", "p1-r1-ezrtp.log"), "ezrtp")
        self.assertEqual(res["watchdog_stalls"], 2)
        self.assertEqual(res["watchdog_max_ms"], 12000)
        self.assertEqual(res["chunk_async_errors"], 2)
        self.assertEqual(res["off_thread_errors"], 1)
        self.assertEqual(res["uncaught_tick_exceptions"], 1)
        self.assertEqual(res["cant_keep_up"], 1)
        self.assertEqual(res["could_not_pass_event"], {"EzRTP": 1})
        self.assertEqual(res["exceptions"], {"EzRTP": 1, "StressTestRTP": 1, "server/other": 1})
        self.assertIn("Could not pass event PlayerTeleportEvent to EzRTP", res["first_plugin_error"])

    def test_rtp_target_does_not_match_other_rtp_names(self):
        res = rep.scan_log(os.path.join(self.out, "logs", "p1-r1-ezrtp.log"), "rtp")
        self.assertEqual(res["first_plugin_error"], "")

    def test_missing_log_is_none(self):
        self.assertIsNone(rep.scan_log(os.path.join(self.out, "logs", "none.log"), "rtp"))

    def test_folia_refusal_line(self):
        p = os.path.join(self.out, "refuse.log")
        with open(p, "w", encoding="utf-8") as fh:
            fh.write("[12:00:00] [Server thread/ERROR]: Could not load 'plugins/JakesRTP-0.14.9.jar' in folder 'plugins'\n"
                     "[12:00:00] [Server thread/ERROR]: Plugin JakesRTP does not support Folia\n")
        res = rep.scan_log(p, "jakesrtp")
        self.assertIn("does not support Folia", res["folia_refusal"])
        self.assertIn("JakesRTP", res["first_plugin_error"])


class MarkdownTest(ReportBase):
    def test_outcomes_unknown_logs_and_direct_floor(self):
        direct = dict(self.row, target="direct", tag="p1-r1-direct", run_stamp="", log_file="", loaded_rtp_plugins="")
        jakes = dict(self.row, target="jakesrtp", tag="p1-r1-jakesrtp", outcome="unsupported-not-loaded",
                     run_stamp="", log_file="", note="failed on this rig, this config")
        write_manifest(self.out, [self.row, direct, jakes])
        write_bandwidth(self.out, synthetic_samples())
        rows, md = rep.build(self.out)
        self.assertIn("unsupported-not-loaded", md)
        self.assertIn("direct (control, floor)", md)
        self.assertIn("unknown", md)
        self.assertIn("2 (max 12000 ms)", md)
        self.assertEqual(sum(1 for r in rows if r[5] == "log.watchdog_stalls"), 1)

    def test_swaps_and_live_error_notes(self):
        row = dict(self.row, swaps=3, live_chunk_async=12, live_watchdog=1,
                   live_first_error="[12:00:01] Cannot retrieve chunk asynchronously")
        write_manifest(self.out, [row, dict(self.row, round=2, tag="p1-r2-ezrtp", run_stamp="", log_file="")])
        rows, md = rep.build(self.out)
        self.assertEqual(self.metric(rows, "run", "swaps"), "3")
        self.assertIn("replaced 3 stuck bot(s)", md)
        self.assertIn("first problem seen live", md)
        self.assertIn("| bots swapped |", md)

    def test_cli_writes_files_and_config_change_note(self):
        row2 = dict(self.row, round=2, tag="p1-r2-ezrtp", cfg_hash_start="bb", cfg_hash_end="bb", run_stamp="",
                    log_file="")
        write_manifest(self.out, [self.row, row2])
        self.assertEqual(rep.main([self.out]), 0)
        self.assertTrue(os.path.exists(os.path.join(self.out, "matrix.md")))
        with open(os.path.join(self.out, "matrix.csv"), newline="", encoding="utf-8") as fh:
            self.assertEqual(next(csv.reader(fh)), ["tag", "target", "pass", "round", "stage", "metric", "value"])
        with open(os.path.join(self.out, "matrix.md"), encoding="utf-8") as fh:
            self.assertIn("something rewrote it across the restart", fh.read())


if __name__ == "__main__":
    unittest.main()
