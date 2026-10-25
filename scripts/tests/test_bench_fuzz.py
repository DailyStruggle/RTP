"""Unit tests for scripts/bench_fuzz.py (behaviour fuzzer episode loop).

Covers the off-server logic: seeded grammar and mutator determinism and
validity against the swarm's request limits, reply normalisation, (previous
action, state) extraction, keep-what's-new corpus selection, count and class
deltas, incremental reads of the runs CSVs, LuckPerms grant/revert with prior
values, the quick checkpoint handshake, one target's episode loop against a
fake server and swarm, and the real swarm's control channel with no server.
Prediction and learning: features from timed events, ridge recovery of a
planted coefficient, control-masked flags, net leak per minute, CEM
convergence on a simulated oracle, stall restarts, rare-leak discovery via
novelty, and delta-debugging shrinking with k of n reproduction.
"""
from __future__ import annotations

import contextlib
import io
import itertools
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time
import types
import unittest
import unittest.mock
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import bench_fuzz as bf  # noqa: E402
import bench_fuzz_server as bfs  # noqa: E402

CFG = {"min_bots": 2, "max_bots": 8, "min_s": 20, "max_s": 60, "novelty": 0.25, "rtp_args": []}
LEAK_HEADER = ("epoch_ms,label,online,joins_total,quits_total,distinct_uuids,heap_used_after_gc_mb,"
               "old_gen_after_gc_mb,histogram_ms,histogram_file")


def dumps(x) -> str:
    return json.dumps(x, sort_keys=True, separators=(",", ":"))


class GrammarTest(unittest.TestCase):
    def test_same_seed_and_episode_give_identical_programs(self):
        a = bf.gen_episode(1, 7, bf.DEFAULT_PARAMS, CFG)
        b = bf.gen_episode(1, 7, bf.DEFAULT_PARAMS, CFG)
        self.assertEqual(dumps(a), dumps(b))
        self.assertNotEqual(dumps(a), dumps(bf.gen_episode(1, 8, bf.DEFAULT_PARAMS, CFG)))
        self.assertNotEqual(dumps(a), dumps(bf.gen_episode(2, 7, bf.DEFAULT_PARAMS, CFG)))

    def test_programs_are_identical_across_processes(self):
        code = ("import sys,json; sys.path.insert(0, sys.argv[1]); import bench_fuzz as bf;"
                "print(json.dumps(bf.gen_episode(3, 11, bf.DEFAULT_PARAMS,"
                "{'min_bots':2,'max_bots':8,'min_s':20,'max_s':60,'novelty':0.25,'rtp_args':[]}),"
                "sort_keys=True, separators=(',', ':')))")
        outs = set()
        for hs in ("1", "2"):
            env = dict(os.environ, PYTHONHASHSEED=hs)
            outs.add(subprocess.run([sys.executable, "-c", code, str(SCRIPTS)], env=env, check=True,
                                    capture_output=True, text=True, timeout=30).stdout)
        self.assertEqual(len(outs), 1)
        self.assertEqual(outs.pop().strip(), dumps(bf.gen_episode(3, 11, bf.DEFAULT_PARAMS, CFG)))

    def assert_valid_bots(self, bots, cfg=CFG):
        self.assertTrue(cfg["min_bots"] <= len(bots) <= cfg["max_bots"])
        names = [b["name"] for b in bots]
        self.assertEqual(len(set(n.lower() for n in names)), len(names))
        for b in bots:
            self.assertRegex(b["name"], r"^[A-Za-z0-9_]{1,12}$")
            self.assertTrue(1 <= len(b["program"]) <= bf.MAX_PROGRAM)
            for x in b["program"]:
                self.assertIn(x["a"], {"rtp", "rtp2", "wait", "move", "quit", "rejoin", "die"})
                if x["a"] == "wait":
                    self.assertTrue(0 <= x["ms"] <= bf.MAX_WAIT_MS)
                if x["a"] == "move":
                    self.assertTrue(0 <= x["ms"] <= bf.MAX_MOVE_MS)
                    self.assertTrue(abs(x["dx"]) <= 64 and abs(x["dz"]) <= 64)
                if x["a"] == "rejoin":
                    self.assertIsInstance(x["rename"], bool)

    def test_generated_programs_are_valid_swarm_requests(self):
        for ep in range(60):
            bots = bf.gen_episode(5, ep, bf.DEFAULT_PARAMS, CFG)
            self.assert_valid_bots(bots)
            for b in bots:
                prog = b["program"]
                self.assertLessEqual(bf.est_ms(prog), CFG["max_s"] * 1000)
                online, prev = True, None
                for i, x in enumerate(prog):
                    if not online:
                        self.assertIn(x["a"], ("wait", "rejoin"), f"offline action in {prog}")
                    if x["a"] == "rtp2":
                        # second /rtp only while one is pending: rtp, after-rtp wait, rtp2
                        self.assertEqual(prog[i - 1]["a"], "wait")
                        self.assertIn(prog[i - 2]["a"], ("rtp", "rtp2"))
                    if x["a"] == "quit":
                        online = False
                    elif x["a"] == "rejoin":
                        online = True
                    prev = x
                self.assertIsNotNone(prev)

    def test_quit_after_rtp_wait_comes_from_its_own_spec(self):
        # no drawn waits: every wait is the one inserted after an rtp
        params = dict(bf.DEFAULT_PARAMS, w_rtp=5.0, w_quit=5.0, w_rejoin_same=2.0, w_wait=-30.0,
                      quit_after_rtp_ms={"mu": 4.0, "sd": 1e-6}, after_rtp_ms={"mu": 9.0, "sd": 1e-6})
        found = 0
        for k in range(5):
            prog = bf.gen_program(bf.rng_for(1, "t", k), params, 60000, [])
            for i in range(1, len(prog) - 1):  # a trailing wait may be clipped to the duration
                if prog[i - 1]["a"] == "rtp":
                    self.assertEqual(prog[i]["a"], "wait")
                    if prog[i + 1]["a"] == "quit":
                        self.assertEqual(prog[i]["ms"], 55)  # e^4
                        found += 1
                    elif i + 1 < len(prog):
                        self.assertEqual(prog[i]["ms"], 8103)  # e^9
        self.assertGreater(found, 0)

    def test_bot_names_fit_rename_room(self):
        self.assertEqual(bf.bot_name("", 1, 7, 3), "fz1x7_3")
        for run in ("", "20261010-200000:betterrtp"):
            long = bf.bot_name(run, 123456, 98765, 63)
            self.assertRegex(long, r"^fz[A-Za-z0-9_]{1,10}$")
            self.assertEqual(long, bf.bot_name(run, 123456, 98765, 63))

    def test_episode_length_fits_budget_and_throughput(self):
        # One shared duration per episode; every bot (joins included) fits it.
        lengths = []
        for ep in range(400):
            for params in (bf.DEFAULT_PARAMS, bf.novelty_params(bf.DEFAULT_PARAMS)):
                bots = bf.gen_episode(1, ep, params, CFG)
                longest = max(bf.est_ms(b["program"]) for b in bots)
                self.assertLessEqual(longest, CFG["max_s"] * 1000, f"ep {ep}")
                req = bf.episode_request("x", "rtp", bots, 180000)
                self.assertLessEqual(req["max_ms"], CFG["max_s"] * 1000 + 20000)
                lengths.append(longest)
        mean_s = sum(lengths) / len(lengths) / 1000
        self.assertTrue(35 <= mean_s <= 42, mean_s)
        # quiet 3 s + leave ~1 s + quick checkpoint ~3 s per episode
        self.assertGreaterEqual(3600 / (mean_s + 7), 74)
        donors = [b["program"] for b in bf.gen_episode(1, 1, bf.DEFAULT_PARAMS, CFG)]
        for ep in range(100):
            out, _ = bf.mutate(bf.rng_for(1, ep, "mutate"), bf.gen_episode(1, ep, bf.DEFAULT_PARAMS, CFG),
                               donors, bf.DEFAULT_PARAMS, CFG, 1, ep)
            self.assertLessEqual(max(bf.est_ms(b["program"]) for b in out), CFG["max_s"] * 1000)

    def test_names_differ_per_run_and_target_programs_do_not(self):
        cfgs = [dict(CFG, run=r) for r in ("20261010-200000:direct", "20261010-200000:betterrtp",
                                           "20261011-090000:direct")]
        eps = [bf.gen_episode(1, 4, bf.DEFAULT_PARAMS, c) for c in cfgs]
        progs = {dumps([b["program"] for b in e]) for e in eps}
        self.assertEqual(len(progs), 1)
        self.assertEqual(progs.pop(), dumps([b["program"] for b in bf.gen_episode(1, 4, bf.DEFAULT_PARAMS, CFG)]))
        names = [tuple(b["name"] for b in e) for e in eps]
        self.assertEqual(len(set(names)), 3)
        self.assertEqual(len({n for ns in names for n in ns}), sum(len(ns) for ns in names))
        for e in eps:
            self.assert_valid_bots(e)

    def test_mutation_is_deterministic_and_valid(self):
        parent = bf.gen_episode(1, 0, bf.DEFAULT_PARAMS, CFG)
        donors = [b["program"] for b in bf.gen_episode(1, 1, bf.DEFAULT_PARAMS, CFG)]
        for ep in range(40):
            a, ops_a = bf.mutate(bf.rng_for(1, ep, "mutate"), parent, donors, bf.DEFAULT_PARAMS, CFG, 1, ep)
            b, ops_b = bf.mutate(bf.rng_for(1, ep, "mutate"), parent, donors, bf.DEFAULT_PARAMS, CFG, 1, ep)
            self.assertEqual(dumps(a), dumps(b))
            self.assertEqual(ops_a, ops_b)
            self.assert_valid_bots(a)
            self.assertTrue(all(b["name"].startswith(f"fz1x{ep}_") for b in a))
        self.assertEqual(dumps(parent), dumps(bf.gen_episode(1, 0, bf.DEFAULT_PARAMS, CFG)), "parent mutated")

    def test_origin_mix_follows_novelty_share(self):
        corpus = bf.Corpus()
        corpus.entries.append({"ep": 0, "bots": bf.gen_episode(1, 0, bf.DEFAULT_PARAMS, CFG)})
        origins = [bf.plan_episode(9, ep, bf.DEFAULT_PARAMS, corpus, CFG)["origin"] for ep in range(800)]
        explore = sum(o != "cem" for o in origins) / len(origins)
        self.assertAlmostEqual(explore, 0.25, delta=0.05)
        self.assertGreater(origins.count("mutation"), 50)
        self.assertGreater(origins.count("novelty"), 50)
        empty = [bf.plan_episode(9, ep, bf.DEFAULT_PARAMS, bf.Corpus(), CFG)["origin"] for ep in range(200)]
        self.assertNotIn("mutation", empty)

    def test_novelty_widens_prior(self):
        p = bf.novelty_params(bf.DEFAULT_PARAMS)
        self.assertTrue(all(p[k] == 0.0 for k in p if k.startswith("w_")))
        self.assertAlmostEqual(p["wait_ms"]["sd"], bf.DEFAULT_PARAMS["wait_ms"]["sd"] * bf.NOVELTY_SD_SCALE)
        self.assertEqual(bf.DEFAULT_PARAMS["w_rtp"], 1.0)

    def test_command_templates(self):
        self.assertEqual(bf.command_for("direct", {}), "rtpstress tp {self}")
        self.assertEqual(bf.command_for("betterrtp", {}), "betterrtp:rtp")
        self.assertEqual(bf.command_for("huskhomes", {}), "huskhomes:rtp")
        self.assertEqual(bf.command_for("someotherrtp", {}), "rtp")
        self.assertEqual(bf.command_for("rtp", bf.parse_cmds("rtp=/leafrtp:rtp,x=")), "leafrtp:rtp")
        self.assertEqual(bf.order_targets(["rtp", "betterrtp", "direct"]), ["direct", "rtp", "betterrtp"])


class StateTest(unittest.TestCase):
    def test_numbers_collapse_to_one_state(self):
        self.assertEqual(bf.normalise_state("wait 12s"), bf.normalise_state("wait 3s"))
        self.assertEqual(bf.normalise_state("wait 12s"), "wait #s")

    def test_colours_names_and_whitespace(self):
        s = "\u00a7cPlease \u00a7lwait&r  fz1x2_0g1 &#ff00aa(12.5s)"
        self.assertEqual(bf.normalise_state(s, ["fz1x2_0", "fz1x2_0g1"]), "Please wait {self} (#.#s)")

    def test_pairs_use_previous_action_and_detect_renames(self):
        ev = [
            {"ev": "spawn", "bot": "fz1x0_0"},
            {"ev": "chat", "bot": "fz1x0_0", "kind": "system", "text": "Welcome fz1x0_0"},
            {"ev": "act", "bot": "fz1x0_0", "i": 0, "a": "rtp"},
            {"ev": "chat", "bot": "fz1x0_0", "kind": "system", "text": "Teleporting in 5s"},
            {"ev": "chat", "bot": "fz1x0_0", "kind": "system", "text": "Teleporting in 4s"},
            {"ev": "act", "bot": "fz1x0_0", "i": 1, "a": "rejoin", "rename": True},
            {"ev": "chat", "bot": "fz1x0_0g1", "kind": "game_info", "text": "Hi"},
            {"ev": "kick", "bot": "fz1x0_1", "text": "\u00a7cYou are banned"},
            {"ev": "death", "bot": "fz1x0_1"},
        ]
        pairs = bf.extract_states(ev, ["fz1x0_0", "fz1x0_1"], ["fz1x0_0", "fz1x0_1"])
        self.assertEqual(pairs, [["join", "system:Welcome {self}"], ["rtp", "system:Teleporting in #s"],
                                 ["rejoin", "game_info:Hi"], ["join", "kick:You are banned"], ["join", "death"]])


class CorpusTest(unittest.TestCase):
    def test_keeps_new_pairs_only_once(self):
        c = bf.Corpus()
        r = c.consider(0, [], [["rtp", "system:wait #s"]], {})
        self.assertTrue(r["kept"])
        self.assertFalse(c.consider(1, [], [["rtp", "system:wait #s"]], {})["kept"])
        r = c.consider(2, [], [["rtp", "system:wait #s"], ["quit", "system:x"]], {})
        self.assertTrue(r["kept"])
        self.assertEqual(r["new_states"], [["quit", "system:x"]])
        self.assertEqual([e["ep"] for e in c.entries], [0, 2])

    def test_corpus_keeps_a_new_coverage_probe_once(self):
        c = bf.Corpus()
        self.assertTrue(c.consider(0, [], [], {}, probes=["A#1", "A#2"])["kept"])
        res = c.consider(1, [], [], {}, probes=["A#2"])
        self.assertFalse(res["kept"])
        self.assertNotIn("new_probes", res)
        self.assertEqual(c.consider(2, [], [], {}, probes=["A#3"])["new_probes"], 1)

    def test_count_changes_kept_by_direction_and_magnitude(self):
        c = bf.Corpus()
        self.assertTrue(c.consider(0, [], [], {"threads_live": 1})["kept"])
        self.assertFalse(c.consider(1, [], [], {"threads_live": 1})["kept"])
        self.assertTrue(c.consider(2, [], [], {"threads_live": 3})["kept"])
        self.assertTrue(c.consider(3, [], [], {"threads_live": -1})["kept"])
        self.assertTrue(c.consider(4, [], [], {"plugin.betterrtp.live_instances": 12})["kept"])
        # noise-like or harness-owned values do not count
        self.assertFalse(c.consider(5, [], [], {"heap_used_after_gc_mb": 50.0, "cls:java.lang.String": 900,
                                                "plugin.stresstestrtp.live_instances": 99,
                                                "threads_live": 0})["kept"])

    def test_cap_drops_oldest(self):
        c = bf.Corpus(cap=2)
        for i in range(4):
            c.consider(i, [], [["a", str(i)]], {})
        self.assertEqual([e["ep"] for e in c.entries], [2, 3])


class DeltaTest(unittest.TestCase):
    def test_count_deltas(self):
        before = {"_epoch": 1, "threads_daemon": 2.0, "heap_used_after_gc_mb": 100.0, "fd_open": -1.0,
                  "teleports_total": 5.0, "threads_live": 40.0, "checkpoint_quick": 1.0,
                  "plugin.x.live_instances": 10.0, "plugin.x.owned_classes": 7.0}
        after = dict(before, _epoch=2, heap_used_after_gc_mb=101.25, fd_open=30.0, teleports_total=9.0,
                     threads_live=41.0, **{"plugin.x.live_instances": 13.0, "plugin.x.owned_classes": 9.0})
        # an unchanged count is sparse; the heap is always reported
        self.assertEqual(bf.count_deltas(before, after), {"heap_used_after_gc_mb": 1.25,
                                                          "threads_live": 1, "plugin.x.live_instances": 3})
        self.assertEqual(bf.count_deltas(before, dict(before, _epoch=2)), {"heap_used_after_gc_mb": 0})

    def test_class_deltas_top_by_magnitude(self):
        h0 = {"A": (10, 100), "B": (5, 50), "C": (1, 10)}
        h1 = {"A": (12, 120), "B": (1, 10), "D": (7, 70)}
        self.assertEqual(bf.class_deltas(h0, h1, 2), {"cls:D": 7, "cls:B": -4})
        self.assertEqual(bf.class_deltas(h0, h0, 5), {})


class CsvTailTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "x.csv")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_from_end_skips_history_and_handles_partial_lines(self):
        with open(self.path, "w", encoding="utf-8") as fh:
            fh.write("a,b\n1,old\n")
        t = bf.CsvTail(self.path)
        self.assertEqual(t.poll(), [])
        with open(self.path, "a", encoding="utf-8") as fh:
            fh.write('2,"x,y"\n3,pa')
        self.assertEqual(t.poll(), [{"a": "2", "b": "x,y"}])
        with open(self.path, "a", encoding="utf-8") as fh:
            fh.write("rt\n")
        self.assertEqual(t.poll(), [{"a": "3", "b": "part"}])

    def test_missing_file_then_created(self):
        t = bf.CsvTail(self.path)
        self.assertEqual(t.poll(), [])
        with open(self.path, "w", encoding="utf-8") as fh:
            fh.write("a\n1\n")
        self.assertEqual(t.poll(), [{"a": "1"}])

    def test_reader_merges_rows_and_times_out(self):
        r = bf.RunsReader(self.dir)
        with open(os.path.join(self.dir, "leak.csv"), "w", encoding="utf-8") as fh:
            fh.write(LEAK_HEADER + "\n5,L1,0,1,3,1,100.5,80,10,h.txt\n")
        with open(os.path.join(self.dir, "leak-metrics.csv"), "w", encoding="utf-8") as fh:
            fh.write("epoch_ms,label,metric,value\n5,L1,threads_live,40\n5,L2,threads_live,1\n")
        d = r.fetch("L1", 2, poll_s=0.01)
        self.assertEqual(d["quits_total"], 3.0)
        self.assertEqual(d["heap_used_after_gc_mb"], 100.5)
        self.assertEqual(d["threads_live"], 40.0)
        self.assertEqual(d["_histo"], "h.txt")
        t0 = time.time()
        self.assertIsNone(r.fetch("L9", 0.2, poll_s=0.05))
        self.assertLess(time.time() - t0, 2)


class FakeLp:
    """LuckPerms over a fake RCON: group default nodes in a dict."""

    def __init__(self, nodes=None, style="v5", server_dir=None):
        self.nodes = dict(nodes or {})
        self.style = style  # v5 | none | rcon (async replies: every reply empty, export works)
        self.server_dir = server_dir
        self.log: list[str] = []
        self.up = True

    def try_cmd(self, c):
        self.log.append(c)
        if not self.up:
            return None
        parts = c.split()
        if parts[:2] == ["lp", "export"] and self.server_dir:
            import gzip
            d = os.path.join(self.server_dir, "plugins", "LuckPerms")
            os.makedirs(d, exist_ok=True)
            nodes = [{"type": "permission", "key": k, "value": v == "true"} for k, v in self.nodes.items()]
            nodes.append({"type": "permission", "key": "ctx.only", "value": True,
                          "context": {"server": "lobby"}})
            with gzip.open(os.path.join(d, parts[2] + ".json.gz"), "wt", encoding="utf-8") as fh:
                json.dump({"metadata": {}, "groups": {"default": {"nodes": nodes}}, "tracks": {}}, fh)
            return ""
        if self.style == "rcon":
            r = self._apply(parts)
            return "" if r is not None else r
        return self._apply(parts)

    def _apply(self, parts):
        if parts[:4] == ["lp", "group", "default", "permission"]:
            op, perm = parts[4], parts[5]
            if op == "check":
                if self.style == "none":
                    return "Unknown command. Type \"/help\" for help."
                if perm in self.nodes:
                    return f"[LP] Permission information for {perm}:\n[LP] - default has {perm} set to " \
                           f"{self.nodes[perm]} in context global.\n[LP] - default does not inherit {perm}."
                return f"[LP] default does not have {perm} set."
            if op == "set":
                self.nodes[perm] = parts[6]
                return "[LP] Set"
            if op == "unset":
                self.nodes.pop(perm, None)
                return "[LP] Unset"
        return ""

    def wait_up(self, _s):
        return self.up


class PermissionTest(unittest.TestCase):
    def test_parse_variants(self):
        self.assertEqual(bf.parse_lp_check("[LP] default has * set to true.", "*"), ("set", "true"))
        self.assertEqual(bf.parse_lp_check("default has permission a.b set to false in context global.", "a.b"),
                         ("set", "false"))
        self.assertEqual(bf.parse_lp_check("default has a.b set to true in context server=lobby.", "a.b"),
                         ("unset", None))
        self.assertEqual(bf.parse_lp_check("[LP] default does not have * set.", "*"), ("unset", None))
        self.assertEqual(bf.parse_lp_check("Unknown command", "*"), ("unknown", None))
        self.assertEqual(bf.parse_lp_check(None, "*"), ("unknown", None))

    def test_grant_restores_prior_values(self):
        lp = FakeLp({"b.deny": "false", "c.on": "true", "keep": "true"})
        d = tempfile.mkdtemp()
        try:
            path = os.path.join(d, "revert.txt")
            g = bf.PermissionGrant(lp, ["a.new", "b.deny", "c.on"], revert_path=path)
            self.assertEqual(g.apply(), ["a.new", "b.deny"])
            self.assertEqual(lp.nodes, {"a.new": "true", "b.deny": "true", "c.on": "true", "keep": "true"})
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            self.assertIn("lp group default permission unset a.new", text)
            self.assertIn("lp group default permission set b.deny false", text)
            self.assertTrue(g.revert())
            self.assertEqual(lp.nodes, {"b.deny": "false", "c.on": "true", "keep": "true"})
            n = len(lp.log)
            self.assertTrue(g.revert())  # idempotent (finally + atexit)
            self.assertEqual(len(lp.log), n)
            with open(path, encoding="utf-8") as fh:
                self.assertIn("# reverted", fh.read())
        finally:
            shutil.rmtree(d, ignore_errors=True)

    def test_no_grant_when_state_unreadable(self):
        lp = FakeLp(style="none")
        g = bf.PermissionGrant(lp, ["*"])
        self.assertEqual(g.apply(), [])
        self.assertFalse(any(" set " in c for c in lp.log))
        self.assertTrue(g.revert())

    def test_parse_lp_export(self):
        data = {"groups": {"default": {"nodes": [
            {"type": "permission", "key": "*", "value": False},
            {"type": "permission", "key": "a.b", "value": True},
            {"type": "permission", "key": "c", "value": True, "context": {"world": "x"}}]}}}
        self.assertEqual(bf.parse_lp_export(data, "default"), {"*": "false", "a.b": "true"})
        self.assertIsNone(bf.parse_lp_export(data, "vip"))
        self.assertIsNone(bf.parse_lp_export({}, "default"))

    def test_grant_over_empty_rcon_replies_uses_export(self):
        d = tempfile.mkdtemp()
        try:
            lp = FakeLp({"b.deny": "false", "keep": "true"}, style="rcon", server_dir=d)
            g = bf.PermissionGrant(lp, ["*", "b.deny", "keep", "ctx.only"],
                                   read_nodes=bf.lp_export_reader(lp, d, timeout_s=5, poll_s=0.01))
            self.assertEqual(g.apply(), ["*", "b.deny", "ctx.only"])
            self.assertEqual(sum(c.startswith("lp export") for c in lp.log), 2)  # read once + verify
            self.assertEqual(lp.nodes["*"], "true")
            self.assertEqual(os.listdir(os.path.join(d, "plugins", "LuckPerms")), [])  # probe files removed
            self.assertTrue(g.revert())
            self.assertEqual(lp.nodes, {"b.deny": "false", "keep": "true"})
        finally:
            shutil.rmtree(d, ignore_errors=True)

    def test_export_unreadable_means_no_grant(self):
        lp = FakeLp(style="rcon")  # no server_dir: the export never appears
        g = bf.PermissionGrant(lp, ["*"], read_nodes=bf.lp_export_reader(lp, None))
        self.assertEqual(g.apply(), [])
        self.assertFalse(any(" set " in c for c in lp.log))

    def test_revert_failure_is_retryable(self):
        lp = FakeLp()
        g = bf.PermissionGrant(lp, ["*"])
        g.apply()
        lp.up = False
        self.assertFalse(g.revert())
        lp.up = True
        self.assertTrue(g.revert())
        self.assertEqual(lp.nodes, {})


class StopRampTest(unittest.TestCase):
    """An interrupted script must stop the ramp it started: the server runs it
    on its own and refuses the next run until its stages end."""

    def driver(self, replies):
        import argparse
        d = bf.bo.Driver(argparse.Namespace(isolate=False), "h", 1, "p")
        d.sent = []

        def try_cmd(c):
            d.sent.append(c)
            return replies.get(c, "")
        d.try_cmd = try_cmd
        return d

    def test_stops_only_own_ramp_once(self):
        d = self.driver({"rtpstress stop": "stopping"})
        d.stop_ramp()
        self.assertEqual(d.sent, [])  # never started one: someone else's run is left alone
        d.ramp_active = True
        d.stop_ramp()
        d.stop_ramp()
        self.assertEqual(d.sent, ["rtpstress stop"])
        self.assertFalse(d.ramp_active)

    def test_no_reply_keeps_it_pending(self):
        d = self.driver({})
        d.try_cmd = lambda c: None
        d.ramp_active = True
        d.stop_ramp()
        self.assertTrue(d.ramp_active)


LEAKY = "com.example.Leaky"
LEAK_CLS = bf.CLS + LEAKY


class FakeServer:
    """RCON + runs files: a quick checkpoint writes the leak.csv row, metrics
    rows and histogram at once. Each quit after an rtp retains one LEAKY
    instance in the histogram; every ended session counts in quits_total."""

    def __init__(self, runs_dir):
        self.runs = runs_dir
        self.completed = 0
        self.retained = 0
        self.quits = 0
        self.fds = 0  # file handles a fake plugin keeps open (fd_open = 100 + fds)
        self.last = "none"
        self.fail_labels: set[str] = set()
        self.no_histo_labels: set[str] = set()
        self.online: list[str] = []
        self.online_lists = None  # `list` calls left before `online` clears; None = never
        self.cmds: list[str] = []
        os.makedirs(runs_dir, exist_ok=True)
        with open(os.path.join(runs_dir, "leak.csv"), "w", encoding="utf-8") as fh:
            fh.write(LEAK_HEADER + "\n1,soak-c00,32,0,0,0,1,1,1,old.txt\n")
        with open(os.path.join(runs_dir, "leak-metrics.csv"), "w", encoding="utf-8") as fh:
            fh.write("epoch_ms,label,metric,value\n")

    def try_cmd(self, c):
        self.cmds.append(c)
        if c == "rtpstress leakcheck status":
            return f"StressTestRTP: leakcheck busy=false completed={self.completed} | {self.last}"
        if c == "list":
            names = list(self.online)
            if self.online_lists is not None:
                self.online_lists -= 1
                if self.online_lists <= 0:
                    self.online, self.online_lists = [], None
            return f"There are {len(names)} of a max of 100 players online: " + ", ".join(names)
        if c.startswith("rtpstress leakcheck ") and (c.endswith(" quick") or c.endswith(" dump")):
            label = c.split()[2]
            self.completed += 1
            if label in self.fail_labels:
                self.last = f"leakcheck {label} failed: IOException: disk"
                return f"StressTestRTP: leakcheck '{label}' started (full GC + class histogram, quick)"
            self.write(label)
            self.last = f"leakcheck {label}: online=0 retained={self.retained}"
            return f"StressTestRTP: leakcheck '{label}' started (full GC + class histogram, quick)"
        return ""

    def write(self, label):
        histo = f"leak-x-{label}.histo.txt"
        if label in self.no_histo_labels:
            histo = "missing-" + histo
        else:
            self.write_histo(histo)
        with open(os.path.join(self.runs, "leak.csv"), "a", encoding="utf-8") as fh:
            fh.write(f"{int(time.time() * 1000)},{label},0,0,{self.quits},0,{500 + self.retained},400,30,"
                     f"{histo}\n")
        with open(os.path.join(self.runs, "leak-metrics.csv"), "a", encoding="utf-8") as fh:
            for k, v in (("checkpoint_quick", 1), ("threads_live", 40), ("fd_open", 100 + self.fds),
                         ("plugin.target.listeners", 3)):
                fh.write(f"1,{label},{k},{v}\n")

    def write_histo(self, histo):
        with open(os.path.join(self.runs, histo), "w", encoding="utf-8") as fh:
            fh.write(" num     #instances         #bytes  class name (module)\n")
            fh.write(f"   1:          {100 + self.retained}       {8000 + self.retained}  java.lang.String\n")
            fh.write(f"   2:          {self.retained}       {self.retained * 64}  {LEAKY}\n")


class FakeSwarm:
    def __init__(self, server):
        self.server = server
        self.requests = []
        self.restarts = 0
        self.fail_next = False
        self.after = None  # hook(req) run once the episode's events are produced
        self.leak_quit = True  # quit right after rtp retains one LEAKY instance
        self.spawn_sequence = []  # optional per-episode spawn outcome
        self.no_spawn_error = "No data available for version 26.2"

    def run_episode(self, req, timeout_s):
        self.requests.append((req, timeout_s))
        if self.after:
            self.after(req)
        if self.fail_next:
            self.fail_next = False
            raise bf.SwarmError("no done")
        events = []
        spawned = self.spawn_sequence.pop(0) if self.spawn_sequence else True
        for b in req["bots"]:
            if not spawned:
                events.append({"ev": "bot_error", "id": req["id"], "bot": b["name"],
                               "text": self.no_spawn_error})
                continue
            last, online = None, True
            t = bf.SPAWN_EST_MS
            events.append({"ev": "spawn", "id": req["id"], "bot": b["name"], "t": t})
            for i, x in enumerate(b["program"]):
                events.append({"ev": "act", "id": req["id"], "bot": b["name"], "i": i, "t": t, **x})
                t += bf.action_cost(x)
                if x["a"] == "rtp":
                    events.append({"ev": "chat", "id": req["id"], "bot": b["name"], "kind": "system",
                                   "text": f"Teleporting {b['name']} in {i + 2}s"})
                if x["a"] == "quit" and last == "rtp" and self.leak_quit:
                    self.server.retained += 1
                if x["a"] in ("quit", "rejoin") and online:
                    self.server.quits += 1
                if x["a"] in ("quit", "rejoin"):
                    online = x["a"] == "rejoin"
                if x["a"] != "wait":
                    last = x["a"]
            if online:
                self.server.quits += 1  # the swarm disconnects every bot at the end
        done = {"ev": "done", "id": req["id"], "ms": req["max_ms"], "reason": "complete",
                "bots": [{"name": b["name"], "last": b["name"]} for b in req["bots"]]}
        return events, done

    def restart(self):
        self.restarts += 1


class LoopFixture:
    """Fake server + swarm around one TargetRun; extra_args() appends CLI flags."""

    def extra_args(self):
        return []

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.runs = os.path.join(self.dir, "plugins", "StressTestRTP", "runs")
        self.server = FakeServer(self.runs)
        self.swarm = FakeSwarm(self.server)
        # The fake leaks on every quit after rtp; replay on surprise is covered by SearchLoopTest.
        # One checkpoint per episode here; repetitions are covered by RepetitionTest.
        self.a = bf.build_parser().parse_args(["--server-dir", self.dir, "--episodes", "4", "--quiet-s", "0",
                                               "--quiet-wait-s", "0", "--checkpoint-wait-s", "3",
                                               "--checkpoint-poll-s", "0.05", "--files-wait-s", "1",
                                               "--surprise-n", "0", "--reps", "1"] + self.extra_args())
        self.tdir = os.path.join(self.dir, "out")
        os.makedirs(self.tdir)
        self.tr = bf.TargetRun(self.a, self.server, self.swarm, bf.RunsReader(self.runs), "betterrtp",
                               "20261010-200000", self.tdir)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def records(self):
        """Trace lines with the classes.jsonl deltas merged back."""
        return bf.read_records(self.tdir)


class EpisodeLoopTest(LoopFixture, unittest.TestCase):
    def test_empty_spawn_episode_is_excluded_and_not_counted_as_executed(self):
        self.a.episodes = 2
        self.swarm.spawn_sequence = [False, True]
        self.tr.run_seed(1)
        recs = self.records()

        self.assertEqual(recs[0]["excluded"], "no bot spawned")
        self.assertNotIn("ms", recs[0])
        self.assertEqual(recs[0]["bot_errors"], [self.swarm.no_spawn_error])
        self.assertIsNone(recs[0]["deltas"])
        self.assertEqual(self.tr.coverage["executed"], 1)
        self.assertEqual(len(self.swarm.requests), 2)
        labels = [c.split()[2] for c in self.server.cmds if c.startswith("rtpstress leakcheck ")]
        self.assertFalse(any("-e0" in label for label in labels))
        self.assertTrue(any("-e1" in label for label in labels))

    def test_empty_spawn_limit_counts_consecutive_episodes_and_resets_after_spawn(self):
        self.a.episodes = 4
        self.a.max_empty = 2
        self.swarm.spawn_sequence = [False, True, False, False]

        with self.assertRaisesRegex(bf.NoSpawnError, "2 consecutive episodes"):
            self.tr.run_seed(1)

        recs = self.records()
        self.assertEqual([r.get("excluded") for r in recs],
                         ["no bot spawned", None, "no bot spawned", "no bot spawned"])
        self.assertEqual(self.tr.coverage["executed"], 1)
        self.assertEqual(len(self.swarm.requests), 4)

    def test_warm_up_is_unscored_and_runs_before_the_baseline(self):
        self.a.warmup = 2
        run_dir = os.path.join(self.dir, "run")
        os.makedirs(run_dir)
        self.a.settle_s = 0

        with unittest.mock.patch.object(bf.lc, "prepare_target", return_value=True), \
                unittest.mock.patch.object(bf.time, "sleep"):
            bf.run_target(self.a, self.server, self.swarm, "direct", [1], run_dir,
                          "20261010-200000", unittest.mock.Mock())

        self.assertEqual(len(self.swarm.requests), self.a.episodes + 2)
        self.assertIn("warm0", self.swarm.requests[0][0]["id"])
        self.assertIn("warm1", self.swarm.requests[1][0]["id"])
        first_baseline = next(i for i, cmd in enumerate(self.server.cmds)
                              if cmd.startswith("rtpstress leakcheck "))
        self.assertLess(self.server.cmds.index("list"), first_baseline)
        trace = os.path.join(run_dir, "direct", "episodes.jsonl")
        self.assertEqual(len(bf.read_trace(trace)), self.a.episodes)
        self.assertFalse(any("warm" in str(r) for r in bf.read_trace(trace)))

    def test_warm_up_with_no_spawn_fails_before_a_baseline(self):
        self.a.warmup = 1
        self.swarm.spawn_sequence = [False]

        with self.assertRaisesRegex(bf.NoSpawnError, "No data available for version 26.2"):
            self.tr.warm_up(1)

        self.assertEqual(len(self.swarm.requests), 1)
        self.assertFalse(any(c.startswith("rtpstress leakcheck ") for c in self.server.cmds))

    def test_warm_up_swarm_failure_restarts_the_swarm_and_continues(self):
        self.a.warmup = 2
        self.swarm.fail_next = True

        self.tr.warm_up(1)

        self.assertEqual(self.swarm.restarts, 1)
        self.assertEqual(len(self.swarm.requests), 2)
        self.assertIsNone(self.tr.prev)

    def test_warm_up_swarm_restart_failure_aborts_cleanly(self):
        self.a.warmup = 1
        self.swarm.fail_next = True

        def broken_restart():
            raise bf.SwarmError("port gone")
        self.swarm.restart = broken_restart

        with self.assertRaisesRegex(bf.NoSpawnError, "swarm restart failed"):
            self.tr.warm_up(1)

    def test_repetition_with_no_spawn_is_not_checkpointed(self):
        self.a.episodes = 1
        self.a.reps = 2
        self.swarm.spawn_sequence = [True, False]

        self.tr.run_seed(1)

        rec = self.records()[0]
        self.assertEqual(rec["reps_error"], "no bot spawned")
        self.assertEqual(rec["reps_bot_errors"], [self.swarm.no_spawn_error])
        self.assertEqual(rec["reps"], 1)
        self.assertFalse(any("-e0k1" in label for label in
                             [c.split()[2] for c in self.server.cmds if c.startswith("rtpstress leakcheck ")]))

    def test_loop_writes_traces_with_exact_deltas(self):
        self.tr.run_seed(1)
        recs = self.records()
        # Class deltas live only in the sidecar, one line per measured episode.
        self.assertFalse(any(k.startswith("cls:") for r in bf.read_trace(self.tr.trace) for k in r["deltas"]))
        self.assertEqual(len(bf.read_trace(os.path.join(self.tdir, bf.CLASSES_FILE))), 4)
        self.assertEqual([r["ep"] for r in recs], [0, 1, 2, 3])
        for r in recs:
            self.assertNotIn("excluded", r)
            self.assertEqual(r["cmd"], "betterrtp:rtp")
            self.assertTrue(r["label"].startswith("fz20261010-200000-betterrtp-s1-e"))
            self.assertIn("heap_used_after_gc_mb", r["deltas"])
            self.assertNotIn("threads_live", r["deltas"])  # unchanged, not kept
            # The heap grows with the leaked instances the histogram counts.
            self.assertEqual(r["deltas"].get(LEAK_CLS, 0), r["deltas"]["heap_used_after_gc_mb"])
            self.assertGreaterEqual(r["departures"], len(r["bots"]))  # every bot leaves by the end
            self.assertTrue(all(len(p) == 2 for p in r["states"]))
        self.assertEqual(sum(r["deltas"].get(LEAK_CLS, 0) for r in recs), self.server.retained)
        self.assertGreater(self.server.retained, 0)
        self.assertEqual(sum(r["departures"] for r in recs), self.server.quits)
        self.assertTrue(recs[0]["kept"])  # first replies are always new
        # Episode requests are the planned programs, bound for the swarm's deadline.
        req, timeout = self.swarm.requests[0]
        self.assertEqual(req["bots"], recs[0]["bots"])
        self.assertLessEqual(req["max_ms"], self.a.episode_max_s * 1000)
        self.assertGreater(timeout, req["max_ms"] / 1000)
        labels = [c.split()[2] for c in self.server.cmds if c.endswith(" quick")]
        self.assertEqual(len(labels), len(set(labels)))

    def test_failed_checkpoint_excludes_and_rebaselines(self):
        self.server.fail_labels.add("fz20261010-200000-betterrtp-s1-e1")
        self.tr.run_seed(1)
        recs = self.records()
        self.assertIn("no checkpoint", recs[1]["excluded"])
        self.assertIsNone(recs[1]["deltas"])
        self.assertNotIn("excluded", recs[2])
        self.assertIn("rtpstress leakcheck fz20261010-200000-betterrtp-s1-e2b quick", self.server.cmds)

    def test_swarm_failure_restarts_and_rebaselines(self):
        self.swarm.fail_next = True
        rec = self.tr.execute(1, 0, bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), None) \
            if self.tr.baseline(1, "base") else None
        self.assertIn("swarm", rec["excluded"])
        self.assertEqual(self.swarm.restarts, 1)
        self.assertIsNone(self.tr.prev)

    def quick_labels(self):
        return [c.split()[2] for c in self.server.cmds if c.endswith(" quick")]

    def test_lingering_bots_exclude_and_rebaseline_once_gone(self):
        def linger(req):
            if req["id"].endswith("-e1"):
                self.server.online, self.server.online_lists = ["fzlinger_0", "bench_007"], 1
        self.swarm.after = linger
        self.tr.run_seed(1)
        recs = self.records()
        self.assertEqual(recs[1]["excluded"], "other players online at the checkpoint")
        self.assertEqual(recs[1]["lingering"], ["fzlinger_0"])
        self.assertEqual(recs[1]["foreign_online"], ["bench_007"])
        self.assertNotIn("kept", recs[1])  # excluded episodes never enter the corpus
        # Episode 2 is measured from a fresh, clean baseline, not from e1's dirty state.
        self.assertNotIn("excluded", recs[2])
        labels = self.quick_labels()
        base = "fz20261010-200000-betterrtp-s1-e2b"
        self.assertEqual(labels[labels.index("fz20261010-200000-betterrtp-s1-e1") + 1], base)
        i = self.server.cmds.index(f"rtpstress leakcheck {base} quick")
        self.assertIn("list", self.server.cmds[i - 3:i])  # settled before the baseline

    def test_baseline_refused_while_bots_linger(self):
        self.server.online = ["fzlinger_0"]
        self.assertFalse(self.tr.baseline(1, "base"))
        self.assertIsNone(self.tr.prev)
        self.assertEqual(self.quick_labels(), [])
        rec = self.tr.execute(1, 0, bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), None)
        self.assertEqual(rec["excluded"], "no baseline checkpoint")
        self.assertEqual(self.swarm.requests, [])  # no episode runs without a clean reference
        self.server.online = []
        self.assertTrue(self.tr.baseline(1, "base2"))

    def test_swarm_failure_rebaselines_after_bots_leave(self):
        self.assertTrue(self.tr.baseline(1, "base"))
        self.swarm.fail_next = True
        self.tr.execute(1, 0, bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), None)
        self.server.online, self.server.online_lists = ["fzleft_0"], 2
        n = len(self.server.cmds)
        a = bf.build_parser().parse_args(["--server-dir", self.dir, "--quiet-s", "0", "--quiet-wait-s", "3",
                                          "--checkpoint-wait-s", "3", "--checkpoint-poll-s", "0.05",
                                          "--files-wait-s", "1"])
        self.tr.a = a
        rec = self.tr.execute(1, 1, bf.plan_episode(1, 1, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), None)
        self.assertNotIn("excluded", rec)
        cmds = self.server.cmds[n:]
        i = cmds.index("rtpstress leakcheck fz20261010-200000-betterrtp-s1-e1b quick")
        self.assertGreaterEqual(cmds[:i].count("list"), 2)  # waited out the leaving bot first

    def test_missing_histogram_is_flagged(self):
        self.server.no_histo_labels.add("fz20261010-200000-betterrtp-s1-e1")
        self.tr.run_seed(1)
        recs = self.records()
        self.assertTrue(recs[1]["no_histogram"])
        self.assertFalse(any(k.startswith("cls:") for k in recs[1]["deltas"]))
        self.assertIn("heap_used_after_gc_mb", recs[1]["deltas"])  # exact counts still measured
        self.assertIsNotNone(recs[1]["departures"])
        # The next episode compares against an empty histogram: flagged too, then back to normal.
        self.assertTrue(recs[2]["no_histogram"])
        self.assertNotIn("no_histogram", recs[3])
        self.assertNotIn("no_histogram", recs[0])

    def test_names_are_scoped_to_run_and_target(self):
        plan = bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg)
        other = bf.TargetRun(self.a, self.server, self.swarm, bf.RunsReader(self.runs), "direct",
                             "20261010-200000", self.tdir)
        oplan = bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), other.cfg)
        self.assertEqual([b["program"] for b in plan["bots"]], [b["program"] for b in oplan["bots"]])
        self.assertFalse({b["name"] for b in plan["bots"]} & {b["name"] for b in oplan["bots"]})

    def test_generations_score_and_log_the_optimiser(self):
        self.a.episodes, self.a.generation = 8, 4
        recs = self.tr.run_seed(1)
        for r in recs:
            self.assertIsNotNone(r["features"])
            self.assertIsNotNone(r["score"])
            self.assertEqual(r["gen"], r["ep"] // 4)
        cem = [r for r in recs if r["origin"] == "cem"]
        self.assertTrue(cem)
        self.assertEqual(len({dumps(r["params"]) for r in cem}), len(cem))  # one draw per episode
        with open(self.tr.opt_path, encoding="utf-8") as fh:
            lines = [json.loads(x) for x in fh if x.strip()]
        self.assertEqual([x["gen"] for x in lines], [0, 1])
        for x in lines:
            for k in ("params", "elite_scores", "best_score", "net_leak_per_min", "restarted", "seed"):
                self.assertIn(k, x)
        self.assertNotEqual(lines[0]["sampled_from"]["mean"], lines[0]["next"]["mean"])  # moved
        self.assertEqual(lines[1]["sampled_from"], lines[0]["next"])
        fit = bf.load_json(self.tr.fit_path)
        self.assertIn(LEAK_CLS, fit["fit"])  # screened from the histogram, never named
        # Same seed, same server behaviour: the optimiser's trajectory is reproducible.
        other = bf.TargetRun(self.a, self.server, self.swarm, bf.RunsReader(self.runs), "betterrtp",
                             "20261010-200000", os.path.join(self.dir, "out2"))
        os.makedirs(other.tdir)
        recs2 = other.run_seed(1)
        self.assertEqual([dumps(r["params"]) for r in recs], [dumps(r["params"]) for r in recs2])

    def test_control_fit_is_loaded_by_later_targets(self):
        control = {"threads_live": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 0.5,
                                    "noise": 2.0, "ci": {}, "n": 9},
                   "plugin.x.listeners": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 0.0,
                                          "noise": 1.0, "ci": {}, "n": 9},
                   # a key from an older control fit that no longer qualifies is dropped
                   "retained_player_objects": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 0.0,
                                               "noise": 1.0, "ci": {}, "n": 9}}
        bf.save_json(os.path.join(self.dir, bf.CONTROL_FILE), control)
        tdir = os.path.join(self.dir, "betterrtp")
        os.makedirs(tdir)
        tr = bf.TargetRun(self.a, self.server, self.swarm, bf.RunsReader(self.runs), "betterrtp",
                          "20261010-200000", tdir)
        self.assertEqual(tr.score_counts, ["plugin.x.listeners", "threads_live"])
        ctl = bf.TargetRun(self.a, self.server, self.swarm, bf.RunsReader(self.runs), "direct",
                           "20261010-200000", os.path.join(self.dir, "direct"))
        self.assertIsNone(ctl.control)  # the control never subtracts itself

    def test_shrink_reproduces_k_of_n_and_writes_the_sequence(self):
        self.a.shrink_n, self.a.shrink_k, self.a.shrink_probes = 3, 2, 40
        self.assertTrue(self.tr.baseline(1, "base"))
        bots = [{"name": "fzS_0", "program": [{"a": "wait", "ms": 5}, {"a": "move", "dx": 1, "dz": 0, "ms": 5},
                                               {"a": "rtp"}, {"a": "wait", "ms": 5}, {"a": "quit"}]},
                {"name": "fzS_1", "program": [{"a": "wait", "ms": 5}, {"a": "die"}]}]
        rec = self.tr.execute(1, 0, {"origin": "cem", "parent": None, "params": None, "bots": bots}, None)
        self.assertEqual(rec["deltas"][LEAK_CLS], 1)
        out = self.tr.shrink(1, rec, LEAK_CLS)
        self.assertTrue(out["reproduced"])
        self.assertEqual(out["sequence"], [["rtp", "quit"]])  # the fake leaks on quit right after rtp
        self.assertEqual((out["hits"], out["n"]), (3, 3))
        shrunk = bf.read_trace(os.path.join(self.tdir, "shrunk.jsonl"))
        self.assertEqual(shrunk[0]["sequence"], [["rtp", "quit"]])
        # Replays ran under fresh names, never the original episode's.
        names = {b["name"] for r in bf.read_trace(self.tr.trace) if r["origin"] == "shrink" for b in r["bots"]}
        self.assertTrue(names)
        self.assertFalse(names & {"fzS_0", "fzS_1"})

    def test_ambient_drift_alone_does_not_reproduce(self):
        # Every checkpoint drifts the leaky class by +1 with no leak; the control's
        # intercept explains it, so a replay needs more than the drift.
        control = {LEAK_CLS: {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 1.0,
                              "noise": 1.0, "ci": {}, "n": 9}}
        self.tr.control = control
        self.assertTrue(self.tr.baseline(1, "base"))
        orig = self.server.write

        def drift(label):
            self.server.retained += 1
            orig(label)
        self.server.write = drift
        bots = [{"name": "fzD_0", "program": [{"a": "wait", "ms": 5}]}]
        hits, n, ok = self.tr.reproduce(1, "d0", bots, LEAK_CLS, 1.0, 3, 2)
        self.assertFalse(ok)
        self.assertEqual(hits, 0)
        self.assertGreater(n, 0)  # the replays were measured, not skipped

    def test_replay_reuses_programs_under_new_names(self):
        self.tr.run_seed(1)
        rec = bf.load_trace(self.tr.trace, 2, 1)
        p0, p1 = bf.replay_plan(rec, 0, self.tr.cfg["run"]), bf.replay_plan(rec, 1, self.tr.cfg["run"])
        self.assertEqual([b["program"] for b in p0["bots"]], [b["program"] for b in rec["bots"]])
        self.assertNotEqual([b["name"] for b in p0["bots"]], [b["name"] for b in p1["bots"]])
        self.assertFalse({b["name"] for b in p0["bots"]} & {b["name"] for b in rec["bots"]})
        self.assertNotEqual([b["name"] for b in p0["bots"]],
                            [b["name"] for b in bf.replay_plan(rec, 0, "20261011-090000:betterrtp")["bots"]])
        self.assertEqual(p0["origin"], "replay")
        self.assertEqual(p0["parent"], 2)


def act_events(program, bot="fzA_0", t0=3000, step=0):
    """Timed act/spawn/tp events for one bot's program, advancing the clock by
    each action's planned cost (+ a fixed `step` so a wait of 0 still moves)."""
    ev = [{"ev": "spawn", "bot": bot, "t": t0}, {"ev": "tp", "bot": bot, "t": t0 + 1}]
    t = t0 + 2
    for i, x in enumerate(program):
        ev.append({"ev": "act", "bot": bot, "i": i, "t": t, **x})
        t += bf.action_cost(x) + step
        if x["a"] == "rejoin":
            ev += [{"ev": "spawn", "bot": bot, "t": t}, {"ev": "tp", "bot": bot, "t": t + 1}]
            t += 2
    ev.append({"ev": "end", "bot": bot, "t": t})
    return ev


class FeatureTest(unittest.TestCase):
    def test_counts_quit_buckets_and_pending_move(self):
        prog = [{"a": "rtp"}, {"a": "wait", "ms": 100}, {"a": "quit"},        # quit 100 ms after rtp
                {"a": "rejoin", "rename": False},
                {"a": "rtp"}, {"a": "wait", "ms": 2000}, {"a": "quit"},       # quit ~2 s after rtp
                {"a": "rejoin", "rename": True},
                {"a": "rtp"}, {"a": "move", "dx": 1, "dz": 0, "ms": 300}, {"a": "wait", "ms": 10}]
        f = bf.extract_features({"events": act_events(prog)})
        self.assertEqual(f["rtp"], 3)
        self.assertEqual(f["quit"], 2)
        self.assertEqual(f["rejoin_same"], 1)
        self.assertEqual(f["rejoin_new"], 1)
        self.assertEqual(f["quit_lt250"], 1)
        self.assertEqual(f["quit_lt1s"], 0)
        self.assertEqual(f["quit_lt3s"], 1)
        self.assertEqual(f["move_pending_tp"], 1)  # the move is before any tp after the third rtp

    def test_login_tp_is_not_a_teleport(self):
        # A move after rtp but before the real forced-move counts as pending; a
        # move after the teleport tp does not.
        ev = [{"ev": "spawn", "bot": "b", "t": 0}, {"ev": "tp", "bot": "b", "t": 1},
              {"ev": "act", "bot": "b", "i": 0, "t": 10, "a": "rtp"},
              {"ev": "act", "bot": "b", "i": 1, "t": 20, "a": "move", "dx": 1, "dz": 0, "ms": 50},
              {"ev": "tp", "bot": "b", "t": 80},
              {"ev": "act", "bot": "b", "i": 2, "t": 90, "a": "move", "dx": 1, "dz": 0, "ms": 50}]
        self.assertEqual(bf.extract_features({"events": ev})["move_pending_tp"], 1)

    def test_quit_without_rtp_has_no_bucket(self):
        f = bf.extract_features({"events": act_events([{"a": "quit"}])})
        self.assertEqual(f["quit"], 1)
        self.assertEqual(sum(f[b] for _, b in bf.QUIT_BUCKETS), 0)
        self.assertEqual(f["quit_no_rtp"], 1)  # buckets + quit_no_rtp partition quit
        self.assertNotIn("quit", bf.FIT_FEATURES)


class RidgeTest(unittest.TestCase):
    def planted(self, coef, n=120, noise=0.6):
        """Episodes whose count delta is coef * quit_lt250 + integer noise."""
        rng = __import__("random").Random(7)
        recs = []
        for ep in range(n):
            q250, qlate, rtp = rng.randint(0, 4), rng.randint(0, 4), rng.randint(1, 5)
            feats = {k: 0 for k in bf.FEATURES}
            feats.update(rtp=rtp, quit=q250 + qlate, quit_lt250=q250, quit_later=qlate)
            d = coef * q250 + round(rng.gauss(0, noise))
            recs.append({"ep": ep, "features": feats, "deltas": {"threads_live": d}, "ms": 40000})
        return recs

    def test_recovers_planted_coefficient(self):
        fit = bf.fit_counts(self.planted(1.0), ["threads_live"])
        c = fit["threads_live"]
        self.assertAlmostEqual(c["coef"]["quit_lt250"], 1.0, delta=0.2)
        self.assertTrue(bf.excludes_zero(c["ci"]["quit_lt250"]))
        for other in ("rtp", "quit_later", "move", "die"):
            self.assertAlmostEqual(c["coef"][other], 0.0, delta=0.3)
            self.assertFalse(bf.excludes_zero(c["ci"][other]), other)

    def test_flag_needs_control_to_be_clean(self):
        target = bf.fit_counts(self.planted(1.0), ["threads_live"])
        # A control that also leaks on quit_lt250 masks the flag; a clean one does not.
        leaky = bf.fit_counts(self.planted(1.0), ["threads_live"])
        clean = bf.fit_counts(self.planted(0.0), ["threads_live"])
        self.assertTrue(any(f["feature"] == "quit_lt250" for f in bf.flag_features(target, clean)))
        self.assertFalse(any(f["feature"] == "quit_lt250" for f in bf.flag_features(target, leaky)))
        self.assertFalse(bf.flag_features(target, target))

    def test_flag_ignores_negative_slopes(self):
        # A shrinking count (negative slope, e.g. ring buffer / cache drain) is not a leak flag.
        shrinking = bf.fit_counts(self.planted(-2.0), ["threads_live"])
        clean = bf.fit_counts(self.planted(0.0), ["threads_live"])
        self.assertEqual(bf.flag_features(shrinking, clean), [])


class ScoreTest(unittest.TestCase):
    def test_net_leak_subtracts_control_and_is_per_minute(self):
        control = {"threads_live": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 2.0,
                                    "noise": 2.0, "ci": {}}}
        control["threads_live"]["coef"]["quit_lt250"] = 1.0
        feats = {k: 0 for k in bf.FEATURES}
        feats["quit_lt250"] = 3
        # control predicts 2 + 1*3 = 5; delta 11 -> net 6 over noise 2 = 3, in 30 s -> *2 per min
        rec = {"features": feats, "deltas": {"threads_live": 11}, "ms": 30000}
        self.assertAlmostEqual(bf.score_episode(rec, ["threads_live"], control), 6.0, places=4)
        # Matching the control exactly scores ~0.
        rec0 = {"features": feats, "deltas": {"threads_live": 5}, "ms": 30000}
        self.assertAlmostEqual(bf.score_episode(rec0, ["threads_live"], control), 0.0, places=4)
        self.assertIsNone(bf.score_episode({"excluded": "x", "deltas": None, "ms": 1}, ["x"], None))

    def test_unexplained_find(self):
        feats = {k: 0 for k in bf.FEATURES}
        fit = {"plugin.x.live_instances": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 0.0,
                                           "noise": 1.0, "ci": {}}}
        rec = {"features": feats, "deltas": {"plugin.x.live_instances": 9}, "ms": 40000}
        self.assertEqual(bf.unexplained_counts(rec, fit), {"plugin.x.live_instances": 9.0})
        rec2 = {"features": feats, "deltas": {"plugin.x.live_instances": 1}, "ms": 40000}
        self.assertEqual(bf.unexplained_counts(rec2, fit), {})

    def test_objects_do_not_outscore_byte_leak_per_repetition(self):
        # 10,000 objects totalling 0.3 MB must not outscore 5 MB per repetition.
        control = {
            "slope.histogram_live_mb": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 0.0, "noise": 1.0, "ci": {}},
            "histogram_live_mb": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 0.0, "noise": 1.0, "ci": {}},
        }
        feats = {k: 0 for k in bf.FEATURES}
        rec_small_objects = {
            "features": feats,
            "deltas": {
                "plugin.leaf.live_instances": 10000,
                "slope.plugin.leaf.live_instances": 10000,
                "cls:leaf.SmallObject": 10000,
                "slope.histogram_live_mb": 0.3,
            },
            "ms": 60000,
        }
        rec_byte_leak = {
            "features": feats,
            "deltas": {
                "slope.histogram_live_mb": 5.0,
            },
            "ms": 60000,
        }
        counts = [
            "plugin.leaf.live_instances",
            "slope.plugin.leaf.live_instances",
            "cls:leaf.SmallObject",
            "slope.histogram_live_mb",
        ]
        score_small = bf.score_episode(rec_small_objects, counts, control)
        score_byte = bf.score_episode(rec_byte_leak, counts, control)
        self.assertLess(score_small, score_byte)

    def test_attribution_metrics_excluded_from_leak_score(self):
        feats = {k: 0 for k in bf.FEATURES}
        deltas = {
            "plugin.rtp.live_instances": 50,
            "slope.plugin.rtp.live_instances": 20,
            "plugin.rtp.listeners": 5,
        }
        leak = bf.net_leak(feats, deltas, list(deltas), control=None)
        self.assertEqual(leak, 0.0)

    def test_count_absent_from_control_uses_target_fit_spread(self):
        feats = {k: 0 for k in bf.FEATURES}
        deltas = {"custom_resource": 100}
        target_fit = {"custom_resource": {"intercept": 10.0, "noise": 30.0, "coef": {k: 0.0 for k in bf.FEATURES}}}
        leak = bf.net_leak(feats, deltas, ["custom_resource"], control=None, target_fit=target_fit)
        # (100 - 10) / 30 = 3.0
        self.assertAlmostEqual(leak, 3.0, places=4)

    def test_attribution_metrics_excluded_from_surprises(self):
        rec = {"features": {k: 0 for k in bf.FEATURES},
               "deltas": {"plugin.rtp.live_instances": 100, "threads_live": 0}, "ms": 40000}
        fit = {"plugin.rtp.live_instances": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 0.0, "noise": 1.0, "ci": {}}}
        found = bf.surprises(rec, ["plugin.rtp.live_instances"], None, fit, z=3.0)
        self.assertNotIn("plugin.rtp.live_instances", found)


class ClassScreenTest(unittest.TestCase):
    """Every histogram class as a leak signal: screen, fit, Bonferroni flag, score."""

    NOISE = 2000

    def episodes(self, seed, n=80, planted=0.0, noise=NOISE, big=100):
        """Synthetic episodes with features and sparse class deltas: `noise`
        classes with a per-class drift and sd (identical for every seed), an
        exploration class growing +3 per rtp (target and control alike), `big`
        classes moving by hundreds (they own every episode's top 50), and P,
        growing `planted` per quit_lt250 (never among the 50 largest)."""
        rng = __import__("random").Random(seed)
        spec = __import__("random").Random(12345)
        cls = [(f"n.C{i}", spec.uniform(-0.5, 0.5), spec.uniform(0.3, 3.0)) for i in range(noise)]
        recs = []
        for ep in range(n):
            q250, qlate, rtp = rng.randint(0, 4), rng.randint(0, 4), rng.randint(1, 5)
            feats = {k: 0 for k in bf.FEATURES}
            feats.update(rtp=rtp, quit=q250 + qlate, quit_lt250=q250, quit_later=qlate)
            d = {"threads_live": 0}
            for name, m, s in cls:
                v = round(rng.gauss(m, s))
                if v:
                    d["cls:" + name] = v
            for i in range(big):
                d[f"cls:big.B{i}"] = rng.choice((-1, 1)) * rng.randint(500, 2000)
            d["cls:world.BlockPos"] = 3 * rtp + round(rng.gauss(0, 1))
            p = round(planted * q250 + rng.gauss(0, 0.5))
            if p:
                d["cls:plug.P"] = p
            recs.append({"seed": seed, "ep": ep, "rung": 0, "features": feats, "deltas": d, "ms": 40000})
        return recs

    def reference(self, control_recs, screen):
        """What TargetRun.rescreen builds: the control episodes' fit per screened class."""
        return bf.fit_counts(control_recs, ["cls:" + s["cls"] for s in screen])

    def test_planted_class_outside_top50_is_screened_flagged_and_scores(self):
        control = self.episodes(1, noise=300)
        target = self.episodes(2, planted=2.0, noise=300)
        top50 = [set(bf.class_deltas({}, {k[4:]: (v, 0) for k, v in r["deltas"].items()
                                          if k.startswith("cls:")}, 50)) for r in target]
        self.assertFalse(any("cls:plug.P" in t for t in top50))  # the old top-50 cut never saw it
        stats = bf.class_stats(control)
        screen, seen = bf.screen_classes(target, stats, 64)
        self.assertEqual(screen[0]["cls"], "plug.P")
        self.assertGreater(screen[0]["z"], 3 * screen[1]["z"])  # far above the best noise class
        fit = bf.fit_from_records(target, classes=[s["cls"] for s in screen])
        ref = self.reference(control, screen)
        flags = bf.flag_features(fit, ref, seen)
        self.assertIn(("cls:plug.P", "quit_lt250"), {(f["count"], f["feature"]) for f in flags})
        self.assertAlmostEqual(fit["cls:plug.P"]["coef"]["quit_lt250"], 2.0, delta=0.3)
        self.assertFalse([f for f in flags if f["count"] != "cls:plug.P"], flags[:3])
        # The class lifts the score of an episode that drives it, net of the control.
        rec = max(target, key=lambda r: r["features"]["quit_lt250"])
        ref["cls:plug.P"] = dict(ref["cls:plug.P"], noise=max(ref["cls:plug.P"]["noise"], screen[0]["noise"]))
        z = bf.bonferroni_z(seen)
        base = bf.score_episode(rec, ["threads_live"], ref, z)
        with_cls = bf.score_episode(rec, ["threads_live", "cls:plug.P"], ref, z)
        self.assertGreater(with_cls, base)
        self.assertLessEqual(with_cls - base, bf.CLASS_TERM_CAP * 60000 / rec["ms"] + 1e-9)

    def test_noise_classes_with_control_matched_drift_give_no_flag(self):
        control = self.episodes(1)
        target = self.episodes(2)
        screen, seen = bf.screen_classes(target, bf.class_stats(control), 64)
        self.assertEqual(len(screen), 64)
        self.assertGreater(seen, self.NOISE)
        fit = bf.fit_from_records(target, classes=[s["cls"] for s in screen])
        flags = [f for f in bf.flag_features(fit, self.reference(control, screen), seen)
                 if f["count"].startswith("cls:")]
        self.assertEqual(flags, [])
        # Exploration growth (+3 per rtp in both) is the control's, not a leak.
        self.assertNotIn("world.BlockPos", [s["cls"] for s in screen[:5]])

    def test_sparse_sidecar_equals_dense(self):
        recs = self.episodes(3, n=20, planted=1.0, noise=50, big=0)
        names = sorted({k for r in recs for k in r["deltas"] if k.startswith("cls:")})
        dense = [dict(r, deltas={**{k: 0 for k in names}, **r["deltas"]}) for r in recs]
        d = tempfile.mkdtemp()
        try:
            tr = types.SimpleNamespace(trace=os.path.join(d, "episodes.jsonl"), tdir=d)
            for r in recs:
                bf.TargetRun.write(tr, r)
            loaded = bf.read_records(d)
        finally:
            shutil.rmtree(d, ignore_errors=True)
        self.assertEqual([r["deltas"] for r in loaded], [r["deltas"] for r in recs])
        self.assertEqual(bf.fit_counts(loaded, names), bf.fit_counts(dense, names))
        self.assertEqual(bf.class_stats(loaded), bf.class_stats(dense))

    def test_no_histogram_record_is_not_a_zero(self):
        recs = self.episodes(4, n=10, planted=2.0, noise=5, big=0)
        recs[0]["no_histogram"] = True
        line, side = bf.split_classes(recs[0])
        self.assertIsNone(side)
        self.assertEqual(bf.class_stats(recs)["n"], 9)
        self.assertEqual(bf.fit_counts(recs, ["cls:plug.P"])["cls:plug.P"]["n"], 9)
        self.assertEqual(bf.fit_counts(recs, ["threads_live"])["threads_live"]["n"], 10)

    def test_target_run_rescreens_against_control_episodes_on_disk(self):
        run_dir = tempfile.mkdtemp()
        try:
            cdir = os.path.join(run_dir, bf.DIRECT)
            os.makedirs(cdir)
            control = self.episodes(1, noise=50, big=0)
            stub = types.SimpleNamespace(trace=os.path.join(cdir, "episodes.jsonl"), tdir=cdir)
            for r in control:
                bf.TargetRun.write(stub, r)
            bf.save_json(os.path.join(run_dir, bf.CONTROL_CLASSES_FILE), bf.class_stats(control))
            tdir = os.path.join(run_dir, "betterrtp")
            os.makedirs(tdir)
            a = bf.build_parser().parse_args(["--server-dir", run_dir])
            tr = bf.TargetRun(a, None, None, None, "betterrtp", "20261010-200000", tdir)
            tr.rescreen(self.episodes(2, planted=2.0, noise=50, big=0))
            self.assertEqual(tr.screen[0]["cls"], "plug.P")
            self.assertIn("cls:plug.P", tr.score_counts)
            self.assertEqual(tr.class_ref["cls:plug.P"]["n"], len(control))  # fitted on the control's episodes
            if "cls:world.BlockPos" in tr.class_ref:
                self.assertAlmostEqual(tr.class_ref["cls:world.BlockPos"]["coef"]["rtp"], 3.0, delta=0.3)
            self.assertGreater(tr.classes_seen, 50)
            ctl = bf.TargetRun(a, None, None, None, bf.DIRECT, "20261010-200000", cdir)
            ctl.rescreen(control)
            self.assertEqual(ctl.screen, [])  # the control is the reference, never screened
        finally:
            shutil.rmtree(run_dir, ignore_errors=True)

    def test_bonferroni_is_stricter_with_more_classes(self):
        self.assertAlmostEqual(bf.bonferroni_z(1), 1.96, delta=0.01)
        self.assertGreater(bf.bonferroni_z(10000 * len(bf.FIT_FEATURES)), 5.0)


class DepartureScreenTest(unittest.TestCase):
    """Growth per departed session: a class something keeps per ended session
    is found by its slope against the quit counter, whatever its name."""

    def episodes(self, seed, n=12, leaky=0.0, shared=2.0):
        """Histogram episodes with 1..10 departures each: x.Leaky grows `leaky`
        per departure, x.Shared `shared` per departure, n.Noise is departure-free."""
        rng = __import__("random").Random(seed)
        recs = []
        for ep in range(n):
            q = rng.randint(1, 10)
            d = {}
            for name, v in (("x.Leaky", leaky * q + rng.gauss(0, 0.5)), ("x.Shared", shared * q + rng.gauss(0, 0.5)),
                            ("n.Noise", rng.gauss(0, 2))):
                if round(v):
                    d[bf.CLS + name] = round(v)
            recs.append({"seed": seed, "ep": ep, "rung": 0, "features": {k: 0 for k in bf.FEATURES},
                         "deltas": d, "departures": q, "ms": 40000})
        return recs

    def test_departures_from_the_quit_counter(self):
        self.assertEqual(bf.departures({"quits_total": 3.0}, {"quits_total": 7.0}), 4)
        self.assertEqual(bf.departures({"quits_total": 7.0}, {"quits_total": 7.0}), 0)
        self.assertIsNone(bf.departures({"quits_total": -1.0}, {"quits_total": 7.0}))  # -1 = not measured
        self.assertIsNone(bf.departures({"quits_total": 3.0}, {"quits_total": -1.0}))
        self.assertIsNone(bf.departures({}, {"quits_total": 7.0}))
        self.assertIsNone(bf.departures({"quits_total": 3.0}, {}))
        self.assertIsNone(bf.departures({"quits_total": 10.0}, {"quits_total": 2.0}))  # counter reset

    def test_class_growing_per_departure_beyond_the_control_is_flagged(self):
        target, control = self.episodes(2, leaky=3.0), self.episodes(1)
        out = bf.screen_departures(target, control, 64)
        names = [s["cls"] for s in out]
        self.assertEqual(names, ["x.Leaky"])  # x.Shared grows as much in the control; n.Noise never
        self.assertAlmostEqual(out[0]["per_departure"], 3.0, delta=0.2)
        self.assertAlmostEqual(out[0]["control_per_departure"], 0.0, delta=0.2)
        self.assertGreater(out[0]["z"], bf.bonferroni_z(3))
        # Without the control's slope, the shared growth is indistinguishable from a leak.
        self.assertIn("x.Shared", [s["cls"] for s in bf.screen_departures(target, None, 64)])

    def test_noise_alone_flags_nothing(self):
        self.assertEqual(bf.screen_departures(self.episodes(2), self.episodes(1), 64), [])

    def test_too_few_departure_episodes_screen_nothing(self):
        target, control = self.episodes(2, n=3, leaky=3.0), self.episodes(1)
        self.assertEqual(bf.screen_departures(target, control, 64), [])
        self.assertTrue(bf.screen_departures(self.episodes(2, n=4, leaky=3.0), control, 64))
        # Episodes nobody left do not count towards min_n.
        idle = [dict(r, departures=0) for r in self.episodes(2, leaky=3.0)]
        self.assertEqual(bf.screen_departures(idle, control, 64), [])

    def test_scorable_drops_retired_counts_and_keeps_resources(self):
        self.assertFalse(bf.scorable("retained_player_objects"))
        self.assertFalse(bf.scorable("slope.retained_player_objects"))
        self.assertTrue(bf.scorable("threads_live"))
        self.assertTrue(bf.scorable("slope.histogram_live_mb"))
        self.assertFalse(bf.scorable(bf.CLS + "x.Leaky"))  # classes come from the screens

    def test_target_run_scores_a_class_only_the_departure_screen_finds(self):
        # Per episode the class grows by 10 in both runs, so the cumulative screen
        # sees nothing; the control keeps 2 per departed session, the target 10.
        def recs(seed, q):
            return [{"seed": seed, "ep": ep, "rung": 0, "features": {k: 0 for k in bf.FEATURES},
                     "deltas": {bf.CLS + "x.PerSession": 10}, "departures": q, "ms": 40000} for ep in range(8)]
        run_dir = tempfile.mkdtemp()
        try:
            cdir = os.path.join(run_dir, bf.DIRECT)
            os.makedirs(cdir)
            control = recs(1, 5)
            stub = types.SimpleNamespace(trace=os.path.join(cdir, "episodes.jsonl"), tdir=cdir)
            for r in control:
                bf.TargetRun.write(stub, r)
            bf.save_json(os.path.join(run_dir, bf.CONTROL_CLASSES_FILE), bf.class_stats(control))
            tdir = os.path.join(run_dir, "betterrtp")
            os.makedirs(tdir)
            a = bf.build_parser().parse_args(["--server-dir", run_dir])
            tr = bf.TargetRun(a, None, None, None, "betterrtp", "20261010-200000", tdir)
            tr.rescreen(recs(2, 1))
            self.assertNotIn("x.PerSession", [s["cls"] for s in tr.screen])
            self.assertEqual([s["cls"] for s in tr.departure_screen], ["x.PerSession"])
            self.assertAlmostEqual(tr.departure_screen[0]["per_departure"], 10.0)
            self.assertAlmostEqual(tr.departure_screen[0]["control_per_departure"], 2.0)
            self.assertIn(bf.CLS + "x.PerSession", tr.score_counts)
            self.assertEqual(tr.class_ref[bf.CLS + "x.PerSession"]["n"], len(control))  # fitted on the control
        finally:
            shutil.rmtree(run_dir, ignore_errors=True)


class SearchLoopTest(unittest.TestCase):
    """Discovery-first search against the fake server: no leak, a rare
    reproducible leak, and a one-off spike."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.runs = os.path.join(self.dir, "plugins", "StressTestRTP", "runs")
        self.server = FakeServer(self.runs)
        self.swarm = FakeSwarm(self.server)
        self.swarm.leak_quit = False
        self.a = bf.build_parser().parse_args(["--server-dir", self.dir, "--episodes", "24", "--generation", "3",
                                               "--quiet-s", "0", "--quiet-wait-s", "0",
                                               "--checkpoint-wait-s", "3", "--checkpoint-poll-s", "0.02",
                                               "--files-wait-s", "1"])
        self.tdir = os.path.join(self.dir, "betterrtp")
        os.makedirs(self.tdir)
        self.tr = bf.TargetRun(self.a, self.server, self.swarm, bf.RunsReader(self.runs), "betterrtp",
                               "20261010-200000", self.tdir)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def opt(self):
        return bf.read_trace(self.tr.opt_path)

    def test_no_leak_stays_searching_with_novelty_at_cap(self):
        recs = self.tr.run_seed(1)
        self.assertEqual(len(recs), 24)
        lines = self.opt()
        self.assertEqual({x["state"] for x in lines}, {"searching"})
        self.assertEqual(lines[-1]["novelty"], 0.6)
        self.assertEqual([x["novelty_used"] for x in lines[:3]], [0.25, 0.25, 0.35])  # rises per stall
        self.assertFalse(any(x["surprises"] for x in lines))
        self.assertFalse(self.tr.candidates)
        self.assertTrue(any(x.get("restarted") == "fresh" for x in lines))  # restarts draw a new point

    def test_rare_leak_is_replayed_reproduced_and_flips_the_state(self):
        target = {}

        def rare(req):
            # Leaks only for one program (e4's), wherever it runs: replays reproduce it.
            progs = dumps([b["program"] for b in req["bots"]])
            if req["id"].endswith("-e4"):
                target["progs"] = progs
            if target.get("progs") == progs:
                self.server.fds += 5
        self.swarm.after = rare
        recs = self.tr.run_seed(1)
        replays = [r for r in recs if r["origin"] == "replay"]
        self.assertEqual({r["replay_of"] for r in replays}, {4})
        planned = [r for r in recs if r["origin"] != "replay"]
        self.assertEqual(len(planned), 24)
        self.assertEqual(len(replays), 3)
        self.assertEqual(len(recs), 27)  # replays do not consume the planned episode budget
        self.assertEqual([(c["ep"], c["k"], c["n"]) for c in self.tr.candidates], [(4, 3, 3)])
        self.assertEqual(self.tr.candidates[0]["count"], "fd_open")
        lines = self.opt()
        self.assertEqual(lines[0]["state"], "searching")
        flip = next(x for x in lines if x["state"] == "exploiting")
        self.assertEqual(flip["gen"], 1)
        self.assertEqual([(s["ep"], s["k"], s["n"]) for s in flip["surprises"]], [(4, 3, 3)])
        self.assertEqual(lines[-1]["novelty"], 0.25)  # back to the base share to exploit
        names = {b["name"] for r in replays for b in r["bots"]}
        self.assertFalse(names & {b["name"] for b in recs[4]["bots"]})
        side = bf.read_trace(os.path.join(self.tdir, "surprises.jsonl"))
        self.assertTrue(side[0]["reproduced"])

    def test_budget_decoupling_with_capped_replay_budget(self):
        target = {}

        def rare(req):
            progs = dumps([b["program"] for b in req["bots"]])
            if req["id"].endswith("-e4"):
                target["progs"] = progs
            if target.get("progs") == progs:
                self.server.fds += 5
        self.swarm.after = rare
        self.a.replay_budget = 0  # no replays allowed by budget
        recs = self.tr.run_seed(1)
        replays = [r for r in recs if r["origin"] == "replay"]
        self.assertEqual(len(replays), 0)
        self.assertEqual(len(recs), 24)

    def test_one_off_spike_is_replayed_but_does_not_flip(self):
        def spike(req):
            if req["id"].endswith("-e4"):
                self.server.fds += 5
        self.swarm.after = spike
        self.tr.run_seed(1)
        side = bf.read_trace(os.path.join(self.tdir, "surprises.jsonl"))
        self.assertEqual([(s["ep"], s["k"], s["reproduced"]) for s in side], [(4, 0, False)])
        self.assertEqual(side[0]["n"], 2)  # gave up once 2 of 3 was out of reach
        self.assertEqual({x["state"] for x in self.opt()}, {"searching"})
        self.assertFalse(self.tr.candidates)

    def test_candidates_are_shrunk_first(self):
        self.tr.candidates.append({"seed": 1, "ep": 9, "count": "fd_open",
                                   "rec": {"ep": 9, "bots": [], "features": {}, "deltas": {}}})
        called = []
        self.tr.shrink = lambda seed, rec, count: called.append((rec["ep"], count)) or {}
        self.tr.shrink_top(1, [], 2)
        self.assertEqual(called, [(9, "fd_open")])


class SearchStateTest(unittest.TestCase):
    def test_novelty_rises_per_stall_to_cap_and_resets_on_confirm(self):
        s = bf.SearchState(0.25, 0.6, 0.1)
        self.assertTrue(s.generation(1.0))
        for _ in range(6):
            self.assertFalse(s.generation(0.5))
        self.assertEqual(s.novelty, 0.6)
        self.assertTrue(s.confirm("flag"))
        self.assertEqual((s.state, s.novelty), ("exploiting", 0.25))
        s.generation(0.1)
        self.assertEqual(s.novelty, 0.25)  # exploiting: no more rises
        self.assertFalse(s.confirm("again"))

    def test_fresh_restart_redraws_the_mean_from_the_prior(self):
        cem = bf.Cem(dict(bf.DEFAULT_PARAMS), stall=1)
        samples = [cem.sample(bf.rng_for(1, 0, i, "cem")) for i in range(6)]
        cem.update(samples, [1.0] * 6)
        narrowed = dict(cem.mean)
        r = cem.update(samples, [0.0] * 6, bf.rng_for(1, 1, "restart"))
        self.assertEqual(r["restarted"], "fresh")
        self.assertEqual(cem.sd, cem.sd0)
        self.assertNotEqual(cem.mean, narrowed)
        again = bf.Cem(dict(bf.DEFAULT_PARAMS), stall=1)
        again.update(samples, [1.0] * 6)
        again.update(samples, [0.0] * 6, bf.rng_for(1, 1, "restart"))
        self.assertEqual(again.mean, cem.mean)  # reproducible from (seed, generation)

    def test_surprise_detection(self):
        feats = {k: 0 for k in bf.FEATURES}
        ref = {"threads_live": {"coef": {}, "intercept": 0.0, "noise": 0.5, "sd": 0.0, "ci": {}}}
        rec = {"features": feats, "deltas": {"threads_live": 1}, "ms": 1}
        # A count the control never moved: a single object is surprising.
        self.assertEqual(bf.surprises(rec, ["threads_live"], ref, {}, 4.0), {"threads_live": 1.0})
        noisy = {"threads_live": dict(ref["threads_live"], sd=2.0)}
        self.assertEqual(bf.surprises(rec, ["threads_live"], noisy, {}, 4.0), {})
        down = dict(rec, deltas={"threads_live": -9})
        self.assertEqual(bf.surprises(down, ["threads_live"], noisy, {}, 4.0), {})


class LadderRulesTest(unittest.TestCase):
    def test_parse_rungs(self):
        self.assertEqual(bf.parse_rungs("4x1, 12x3,48x10"), [(4, 1), (12, 3), (48, 10)])
        for bad in ("1x3", "65x1", "4x0", ""):
            with self.assertRaises(ValueError):
                bf.parse_rungs(bad)

    def test_successive_halving_keeps_the_top_third_that_holds(self):
        def res(cand, nets, noise=1.0):
            r = bf.interval(nets)
            r.update(cand=cand, noise=noise, pred_mean=1.0)
            return r
        results = [res("leak", [6, 7, 5]), res("weak", [1.5, 2, 2.5]), res("noise1", [1, -1, 0]),
                   res("noise2", [0, 0, 1]), res("noise3", [-2, 1, 0]), res("noise4", [2, -2, 0])]
        self.assertEqual([r["cand"] for r in bf.promote(results, 3)], ["leak", "weak"])
        self.assertEqual([r["cand"] for r in bf.promote(results[2:], 3)], [])  # noise never climbs
        one = res("single", [3])
        self.assertIsNone(one["ci"])
        self.assertTrue(bf.holds(one))  # one rep: needs half the prediction and one object
        self.assertFalse(bf.holds(res("zero", [0.4])))

    def test_pass_fail_rule(self):
        # predicted 10 per rep x 10 reps; heap control noise 2 MB per episode
        fail = bf.confirm_rule(count_growth=60, predicted=100, heap_net=40, heap_noise=2, reps=10)
        self.assertTrue(fail["fail"])
        same = bf.confirm_rule(count_growth=3, predicted=100, heap_net=2, heap_noise=2, reps=10)
        self.assertFalse(same["fail"])  # matches the control
        count_only = bf.confirm_rule(count_growth=100, predicted=100, heap_net=5, heap_noise=2, reps=10)
        self.assertEqual((count_only["count_ok"], count_only["heap_ok"], count_only["fail"]), (True, False, False))
        self.assertFalse(bf.confirm_rule(5, 0, 99, 1, 3)["fail"])  # nothing predicted: nothing to confirm

    def test_target_verdict(self):
        self.assertEqual(bf.target_verdict(True, [], False), "PASS")
        self.assertEqual(bf.target_verdict(False, [{"fail": False}], True), "INCONCLUSIVE")
        self.assertEqual(bf.target_verdict(False, [{"fail": False}, {"fail": True}], True), "FAIL")
        self.assertEqual(bf.target_verdict(True, [], True), "INCONCLUSIVE")  # a candidate that fell off

    def test_heap_stop_from_start_script(self):
        d = tempfile.mkdtemp()
        try:
            with open(os.path.join(d, "start.sh"), "w", encoding="utf-8") as fh:
                fh.write("java -Xms16G -Xmx16G -jar paper.jar\n")
            a = bf.build_parser().parse_args(["--server-dir", d])
            self.assertEqual(bf.heap_stop_mb(a, d), 16384 * 0.75)
            a.heap_stop_mb = 900
            self.assertEqual(bf.heap_stop_mb(a, d), 900)
        finally:
            shutil.rmtree(d, ignore_errors=True)

    def test_scale_bots_cycles_programs_under_fresh_names(self):
        bots = [{"name": "a", "program": [{"a": "rtp"}]}, {"name": "b", "program": [{"a": "die"}]}]
        out = bf.scale_bots(bots, 5, "run", 1, "t")
        self.assertEqual([b["program"][0]["a"] for b in out], ["rtp", "die", "rtp", "die", "rtp"])
        self.assertEqual(len({b["name"] for b in out}), 5)
        self.assertFalse({"a", "b"} & {b["name"] for b in out})


class LadderLoopTest(unittest.TestCase):
    """The ladder against the fake server: a real leak climbs and fails at the
    top rung, noise is dropped at once, a 4-bot-only leak is micro-only."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.runs = os.path.join(self.dir, "plugins", "StressTestRTP", "runs")
        self.server = FakeServer(self.runs)
        self.swarm = FakeSwarm(self.server)
        self.a = bf.build_parser().parse_args(["--server-dir", self.dir, "--quiet-s", "0", "--quiet-wait-s", "0",
                                               "--checkpoint-wait-s", "3", "--checkpoint-poll-s", "0.02",
                                               "--files-wait-s", "1", "--rungs", "4x1,6x2,8x3", "--eta", "2"])
        self.run_dir = os.path.join(self.dir, "out")
        self.tdir = os.path.join(self.run_dir, "betterrtp")
        os.makedirs(self.tdir)
        self.tr = bf.TargetRun(self.a, self.server, self.swarm, bf.RunsReader(self.runs), "betterrtp",
                               "20261010-200000", self.tdir)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def cand(self, name, program):
        return {"cand": name, "seed": 1, "ep": 0, "count": LEAK_CLS, "source": "flag",
                "shrunk": False, "bots": [{"name": "x", "program": program}] * 2, "net0": 2.0, "bots0": 2}

    def test_climb_confirms_the_leak_and_drops_the_rest(self):
        def only4(req):
            if len(req["bots"]) == 4 and req["bots"][0]["program"] == [{"a": "die"}]:
                self.server.retained += 4
        self.swarm.after = only4
        cands = [self.cand("leak", [{"a": "rtp"}, {"a": "quit"}]),
                 self.cand("noise", [{"a": "wait", "ms": 5}]),
                 self.cand("micro4", [{"a": "die"}])]
        ladder = self.tr.climb(cands)
        lines = bf.read_trace(os.path.join(self.tdir, "ladder.jsonl"))
        dropped = {x["cand"]: (x["dropped_at"], x["why"]) for x in lines if "dropped_at" in x}
        self.assertEqual(dropped, {"noise": (0, "micro-only"), "micro4": (1, "micro-only")})
        top = [r for r in ladder if "confirm" in r]
        self.assertEqual([r["cand"] for r in top], ["leak"])
        self.assertEqual(top[0]["nets"], [8.0, 8.0, 8.0])  # 8 bots, one leaked instance each
        self.assertTrue(top[0]["confirm"]["fail"])
        self.assertTrue(top[0]["dumps"]["final"].endswith(".hprof"))
        self.assertIn("heap_owner.py", top[0]["owner"]["command"])  # no --mat: the command to run
        self.assertIn("--baseline", top[0]["owner"]["command"])
        dumps = [c for c in self.server.cmds if c.endswith(" dump")]
        self.assertEqual(len(dumps), 2)  # top-rung baseline and the failure's final dump
        v = self.tr.finish(cands, ladder)
        self.assertEqual(v["verdict"], "FAIL")
        rep = bf.render_report(self.run_dir, ["betterrtp"], {"seeds": [1]})
        self.assertIn("=== betterrtp: FAIL", rep)
        self.assertIn("dropped at rung 1 (micro-only)", rep)
        self.assertIn("['rtp', 'quit']", rep)

    def test_no_candidate_report_says_nothing_found_within_budget(self):
        self.swarm.leak_quit = False
        self.a.episodes, self.a.generation = 6, 3
        self.tr.run_seed(1)
        v = self.tr.finish([], [])
        self.assertEqual(v["verdict"], "PASS")
        self.assertEqual(v["coverage"]["episodes"], 6)
        self.assertGreater(v["coverage"]["pairs"], 0)
        rep = bf.render_report(self.run_dir, ["betterrtp", "rtp"], {"seeds": [1]})
        self.assertIn("=== betterrtp: PASS - nothing found within budget (not proof that there is none)", rep)
        self.assertIn("6 episodes", rep)
        self.assertIn("=== rtp: NOT RUN", rep)
        self.assertIn("leak rate per generation", rep)

    def test_run_rung_does_not_inflate_heap_net_from_negative_control_slope(self):
        # Negative control-heap feature slopes (noisy GC timing on 2..8 bots) must not
        # extrapolate to -1,000+ MB on 48-bot ladder rungs and fabricate a huge heap_net_mb.
        self.tr.control_heap = {
            bf.HEAP: {
                "intercept": 0.0,
                "noise": 20.0,
                "coef": {k: (-40.0 if k == "quit_no_rtp" else 0.0) for k in bf.FEATURES},
            }
        }
        self.swarm.leak_quit = False
        c = self.cand("clean_quit", [{"a": "quit"}])
        res = self.tr.run_rung(c, 2, 8, 3, True)
        self.assertLessEqual(res["confirm"]["heap_net_mb"], 10.0)
        self.assertFalse(res["confirm"]["heap_ok"])

    def test_default_shrink_matches_ladder_candidates(self):
        a = bf.build_parser().parse_args(["--server-dir", self.dir])
        self.assertEqual(a.shrink, a.ladder_candidates)


def _poisson(rng, lam):
    target, k, p = math.exp(-lam), 0, 1.0
    while True:
        p *= rng.random()
        if p <= target:
            return k
        k += 1


class CemTest(unittest.TestCase):
    PRIOR = dict(bf.DEFAULT_PARAMS)

    def oracle(self, params, rng):
        """Leaks only for quit delays in [50, 300] ms, plus Poisson(1) noise;
        the score is per a fixed 0.67 min episode (net leak per minute)."""
        spec = params["quit_after_rtp_ms"]
        leaks = sum(1 for _ in range(20) if 50 <= rng.lognormvariate(spec["mu"], spec["sd"]) <= 300)
        return (leaks + _poisson(rng, 1.0)) / 0.67

    def run_cem(self, seed, gens=12, pop=10):
        cem = bf.Cem(self.PRIOR)
        curve, logs = [], []
        for gen in range(gens):
            samples = [cem.sample(bf.rng_for(seed, gen, i, "cem")) for i in range(pop)]
            scores = [self.oracle(s, bf.rng_for(seed, gen, i, "oracle")) for i, s in enumerate(samples)]
            logs.append(cem.update(samples, scores))
            curve.append(sum(scores) / len(scores))
        return cem, curve, logs

    def test_converges_to_the_leaking_window(self):
        for seed in range(1, 5):
            cem, curve, _ = self.run_cem(seed)
            q = math.exp(cem.params()["quit_after_rtp_ms"]["mu"])
            self.assertTrue(50 <= q <= 300, f"seed {seed}: mu -> {q:.0f} ms")
            early = sum(curve[:3]) / 3
            late = sum(curve[-3:]) / 3
            self.assertGreater(late, early, f"seed {seed}: leak rate did not rise {curve}")
            # the variance floor keeps exploring: sd never collapses below the floor
            self.assertGreaterEqual(cem.sd["quit_after_rtp_ms.mu"], cem.floor["quit_after_rtp_ms.mu"] - 1e-9)

    def test_sampling_is_reproducible(self):
        a, _, la = self.run_cem(3)
        b, _, lb = self.run_cem(3)
        self.assertEqual(a.params(), b.params())
        self.assertEqual([x["best_score"] for x in la], [x["best_score"] for x in lb])

    def test_stall_triggers_restart_and_widens(self):
        cem = bf.Cem(self.PRIOR, stall=3)
        rng = __import__("random").Random(0)
        res, sds = [], []
        for gen in range(6):
            samples = [cem.sample(bf.rng_for(99, gen, i, "cem")) for i in range(8)]
            # the best never improves after gen 0 (a high score first, then lower)
            scores = [5.0 if gen == 0 else rng.uniform(0, 1) for _ in samples]
            res.append(cem.update(samples, scores))
            sds.append(dict(cem.sd))
        restarts = [i for i, r in enumerate(res) if r["restarted"]]
        self.assertEqual(restarts, [3])  # three stalled generations after the best
        self.assertLess(sds[2]["quit_after_rtp_ms.mu"], cem.sd0["quit_after_rtp_ms.mu"])  # had narrowed
        for k in cem.sd0:
            self.assertGreaterEqual(sds[3][k], cem.sd0[k] - 1e-9, k)  # widened back to the prior

    def test_flatten_roundtrip_clamps(self):
        flat = bf.flatten_params(self.PRIOR)
        self.assertIn("quit_after_rtp_ms.mu", flat)
        self.assertIn("w_rtp", flat)
        out = bf.unflatten_params({"quit_after_rtp_ms.sd": 1e-9, "w_rtp": 999.0}, self.PRIOR)
        self.assertGreaterEqual(out["quit_after_rtp_ms"]["sd"], bf.PARAM_SD_MIN)
        self.assertLessEqual(out["w_rtp"], 30.0)


class RareLeakTest(unittest.TestCase):
    def test_novelty_finds_a_rare_sequence_despite_collapsed_weights(self):
        # CEM has driven die/rejoin_new to near zero; novelty (uniform mix)
        # should still surface a die-then-rejoin+rename in most seeds.
        collapsed = dict(bf.DEFAULT_PARAMS, w_die=-8.0, w_rejoin_new=-8.0)

        def has_rare(bots):
            for b in bots:
                acts = [x for x in b["program"] if x["a"] != "wait"]
                for i in range(len(acts) - 1):
                    if acts[i]["a"] == "die" and acts[i + 1].get("a") == "rejoin" and acts[i + 1].get("rename"):
                        return True
            return False

        hits = 0
        for seed in range(1, 11):
            corpus = bf.Corpus()
            found = any(has_rare(bf.plan_episode(seed, ep, collapsed, corpus, CFG)["bots"]) for ep in range(40))
            hits += found
        self.assertGreaterEqual(hits, 8, f"{hits}/10 seeds found the rare sequence")


class ShrinkTest(unittest.TestCase):
    def flat_program(self, n=30):
        prog = [{"a": "rtp"}, {"a": "wait", "ms": 100}, {"a": "quit"}]  # the planted leaking triple
        filler = [{"a": "wait", "ms": 500}, {"a": "move", "dx": 1, "dz": 0, "ms": 300}, {"a": "rtp"}]
        while len(prog) < n:
            prog.insert(0, dict(filler[len(prog) % len(filler)]))
        return [{"name": "fzS_0", "program": prog}]

    def leaky_probe(self, bots):
        """Leaks iff some bot does rtp then quit with 1..250 ms of waits between
        (a rejoin in between resets it); n=3, needs all 3 (deterministic here)."""
        hit = False
        for b in bots:
            pending = None
            for x in b["program"]:
                if x["a"] in ("rtp", "rtp2"):
                    pending = 0
                elif x["a"] == "wait" and pending is not None:
                    pending += x.get("ms", 0)
                elif x["a"] == "quit":
                    if pending is not None and 1 <= pending <= 250:
                        hit = True
                    pending = None
                elif x["a"] in ("rejoin", "die"):
                    pending = None
        return (3, 3, hit) if hit else (0, 3, False)

    def test_shrinks_to_rtp_wait_quit(self):
        res = bf.shrink_bots(self.flat_program(30), self.leaky_probe)
        self.assertTrue(res["reproduced"])
        self.assertEqual(len(res["bots"]), 1)
        seq = [a["a"] for a in res["bots"][0]["program"]]
        self.assertEqual(seq, ["rtp", "wait", "quit"])
        self.assertEqual(res["hits"], 3)
        self.assertEqual(res["n"], 3)
        self.assertLess(res["actions"], 30)

    def test_non_reproducing_episode_is_reported(self):
        res = bf.shrink_bots([{"name": "fzS_0", "program": [{"a": "rtp"}, {"a": "move", "dx": 0, "dz": 0, "ms": 1}]}],
                             self.leaky_probe)
        self.assertFalse(res["reproduced"])
        self.assertEqual(res["hits"], 0)

    def test_required_effect_is_half_net_and_at_least_one(self):
        feats = {k: 0 for k in bf.FEATURES}
        control = {"threads_live": {"coef": {k: 0.0 for k in bf.FEATURES}, "intercept": 2.0,
                                    "noise": 1.0, "ci": {}}}
        rec = {"features": feats, "deltas": {"threads_live": 12}}  # net 10 over control intercept 2
        self.assertEqual(bf.required_effect(rec, "threads_live", control), 5.0)
        small = {"features": feats, "deltas": {"threads_live": 3}}
        self.assertEqual(bf.required_effect(small, "threads_live", control), 1.0)


class DdminTest(unittest.TestCase):
    def test_finds_a_minimal_pair(self):
        # The subset must contain both 2 and 7 to pass.
        need = {2, 7}
        out = bf.ddmin(list(range(10)), lambda s: need.issubset(set(s)))
        self.assertEqual(sorted(out), [2, 7])


class CheckpointTest(unittest.TestCase):
    def test_not_started_and_unavailable(self):
        class D:
            def __init__(self, replies):
                self.replies = replies

            def try_cmd(self, c):
                return self.replies(c)

        self.assertIn("unavailable", bf.quick_checkpoint(D(lambda c: "Unknown command"), "L", 1, 0.01))
        busy = D(lambda c: "leakcheck busy=true completed=3 | x" if "status" in c else "already running")
        self.assertIn("not started", bf.quick_checkpoint(busy, "L", 1, 0.01))

    def test_online_parsing(self):
        class D:
            def try_cmd(self, c):
                return "There are 3 of a max of 100 players online: fz1x0_0, bench_000, fz1x0_1g1"

        self.assertEqual(bf.online_names(D()), ["fz1x0_0", "bench_000", "fz1x0_1g1"])
        self.assertEqual(bf.foreign_online(bf.online_names(D())), ["bench_000"])


NODE = shutil.which("node")
MINEFLAYER = (SCRIPTS.parent / "devstack" / "clients" / "node_modules" / "mineflayer").is_dir()


@unittest.skipUnless(NODE and MINEFLAYER, "node or devstack/clients/node_modules missing")
class SwarmControlTest(unittest.TestCase):
    def test_episode_against_unreachable_server_reports_done(self):
        sw = bf.Swarm([NODE, bf.SWARM_JS, "--fuzz-control", "0", "--host", "127.0.0.1", "--port", "1",
                       "--join-delay-ms", "0", "--fuzz-spawn-timeout-ms", "3000"])
        sw.start()
        try:
            proc = sw.proc
            req = bf.episode_request("t-1", "rtp", [{"name": "fzT_0", "program": [{"a": "rtp"}, {"a": "quit"}]},
                                                    {"name": "fzT_1", "program": [{"a": "wait", "ms": 10}]}], 60000)
            events, done = sw.run_episode(req, 30)
            self.assertEqual(done["id"], "t-1")
            self.assertTrue(all(e.get("id") == "t-1" for e in events))
            self.assertEqual(sorted(b["name"] for b in done["bots"]), ["fzT_0", "fzT_1"])
            with self.assertRaises(bf.SwarmError):
                sw.run_episode({"op": "episode", "id": "t-2", "cmd": "rtp",
                                "bots": [{"name": "bad name", "program": []}]}, 10)
        finally:
            sw.close()
        self.assertIsNotNone(proc.poll())

    def test_discover_op_validation_and_unreachable_server_completion(self):
        sw = bf.Swarm([NODE, bf.SWARM_JS, "--fuzz-control", "0", "--host", "127.0.0.1", "--port", "1",
                       "--join-delay-ms", "0", "--fuzz-spawn-timeout-ms", "2000"])
        sw.start()
        try:
            events, done = sw.run_episode({"op": "discover", "id": "d-1", "tier": "default", "bot": "fzd_def"}, 30)
            self.assertEqual(done["id"], "d-1")
            self.assertEqual(done["tier"], "default")
            self.assertEqual(done["reason"], "no_commands")
            cmd_events = [e for e in events if e.get("ev") == "commands"]
            self.assertEqual(len(cmd_events), 1)
            self.assertEqual(cmd_events[0]["tier"], "default")
            self.assertFalse(cmd_events[0]["captured"])
            self.assertEqual(cmd_events[0]["rootIndex"], 0)
            self.assertEqual(cmd_events[0]["nodes"], [])
            with self.assertRaisesRegex(bf.SwarmError, "failed to capture command graph"):
                sw.discover("d-unreach", tier="default", bot="fzd_def2", max_ms=1500, timeout_s=15)
            with self.assertRaises(bf.SwarmError):
                sw.run_episode({"op": "discover", "id": "d-2", "tier": "default", "bot": "bad name"}, 10)
        finally:
            sw.close()

    def test_discover_happy_path_and_default_episode_no_telemetry_leak(self):
        server_js = r"""
const path = require('path');
const nm = path.join(path.dirname(process.argv[1]), 'node_modules');
const mc = require(path.join(nm, 'minecraft-protocol'));
const mcData = require(path.join(nm, 'minecraft-data'))('1.20.1');
const srv = mc.createServer({ 'online-mode': false, host: '127.0.0.1', port: 0, version: '1.20.1' });
srv.on('login', (client) => {
  client.write('login', {
    ...mcData.loginPacket,
    entityId: 1,
    isHardcore: false,
    gameMode: 0,
    previousGameMode: -1,
    hashedSeed: [0, 0],
    maxPlayers: 20,
    viewDistance: 2,
    simulationDistance: 2,
    reducedDebugInfo: false,
    enableRespawnScreen: true,
    isDebug: false,
    isFlat: true
  });
  const sendCmds = () => {
    try {
      client.write('declare_commands', {
        rootIndex: 0,
        nodes: [
          {
            flags: { command_node_type: 0, has_command: 0, has_redirect_node: 0, has_custom_suggestions: 0 },
            children: [1, 2]
          },
          {
            flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
            children: [],
            extraNodeData: { name: 'rtp' }
          },
          {
            flags: { command_node_type: 1, has_command: 0, has_redirect_node: 1, has_custom_suggestions: 0 },
            children: [],
            redirectNode: 1,
            extraNodeData: { name: 'betterrtp:rtp' }
          }
        ]
      });
    } catch (e) { /* client disconnected */ }
  };
  if (client.username === 'fzd_late') {
    setTimeout(sendCmds, 1200);
  } else {
    sendCmds();
  }
  client.write('position', { x: 0, y: 64, z: 0, yaw: 0, pitch: 0, flags: 0, teleportId: 1 });
  client.write('update_time', { age: [0, 100], time: [0, 100] });
});
srv.on('listening', () => {
  console.log(JSON.stringify({ port: srv.socketServer.address().port }));
});
"""
        srv_proc = subprocess.Popen([NODE, "-e", server_js, bf.SWARM_JS],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            line = srv_proc.stdout.readline()
            self.assertTrue(line, "fake server did not report listening port")
            port = json.loads(line)["port"]
            sw = bf.Swarm([NODE, bf.SWARM_JS, "--fuzz-control", "0", "--host", "127.0.0.1", "--port", str(port),
                           "--version", "1.20.1", "--join-delay-ms", "0", "--fuzz-spawn-timeout-ms", "1500"])
            sw.start()
            try:
                # 1. Immediate declare_commands (arrives before spawn): finishes right away with reason='complete'.
                cmd_fast_ev, done_fast = sw.discover("d-fast", tier="default", bot="fzd_fast", max_ms=5000, timeout_s=15)
                self.assertEqual(done_fast["reason"], "complete")
                self.assertEqual(done_fast["tier"], "default")
                self.assertTrue(cmd_fast_ev["captured"])
                self.assertEqual([n["name"] for n in cmd_fast_ev["nodes"]], ["", "rtp", "betterrtp:rtp"])
                self.assertEqual(cmd_fast_ev["nodes"][2]["redirect"], 1)

                # 2. Delayed declare_commands (>1s after login): waits up to max_ms and completes with reason='complete'.
                cmd_late_ev, done_late = sw.discover("d-late", tier="op", bot="fzd_late", max_ms=5000, timeout_s=15)
                self.assertEqual(done_late["reason"], "complete")
                self.assertEqual(done_late["tier"], "op")
                self.assertTrue(cmd_late_ev["captured"])
                self.assertEqual(len(cmd_late_ev["nodes"]), 3)

                merged_tree = bfs.merge_command_trees({"default": cmd_fast_ev, "op": cmd_late_ev})
                self.assertEqual(merged_tree.nodes["rtp"].aliases, ["rtp", "betterrtp:rtp"])
                self.assertEqual(merged_tree.nodes["rtp"].min_tier, "default")

                # 3. Default ADR-111 episode (no telemetry opt-in) emits zero commands, tick_gap, or window events.
                ep_req = bf.episode_request(
                    "ep-def", "rtp", [{"name": "fzE_0", "program": [{"a": "wait", "ms": 20}, {"a": "quit"}]}], 4000
                )
                ev_ep, done_ep = sw.run_episode(ep_req, 15)
                self.assertEqual(done_ep["id"], "ep-def")
                leaked_evs = [e["ev"] for e in ev_ep if e.get("ev") in ("commands", "tick_gap", "window")]
                self.assertEqual(leaked_evs, [])
            finally:
                sw.close()
        finally:
            srv_proc.terminate()
            try:
                srv_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                srv_proc.kill()
            if srv_proc.stdout:
                srv_proc.stdout.close()
            if srv_proc.stderr:
                srv_proc.stderr.close()

    def test_passive_protocol_event_parsing(self):
        script = r"""
const EventEmitter = require('events');
const path = require('path');
const sw = require(process.argv[1]);
const nm = path.join(path.dirname(process.argv[1]), 'node_modules');
const { createSerializer, createDeserializer } = require(path.join(nm, 'minecraft-protocol/src/transforms/serializer'));

function roundTrip(version, name, params) {
  const ser = createSerializer({ state: 'play', isServer: true, version });
  const des = createDeserializer({ state: 'play', isServer: false, version });
  const buf = ser.createPacketBuffer({ name, params });
  return des.parsePacketBuffer(buf).data.params;
}

const rawCmdParams = {
  rootIndex: 0,
  nodes: [
    {
      flags: { command_node_type: 0, has_command: 0, has_redirect_node: 0, has_custom_suggestions: 0 },
      children: [1, 4]
    },
    {
      flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
      children: [2, 3],
      extraNodeData: { name: 'rtp' }
    },
    {
      flags: { command_node_type: 2, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 1 },
      children: [],
      extraNodeData: {
        name: 'radius',
        parser: 'brigadier:integer',
        properties: { flags: { min_present: 1, max_present: 1 }, min: 1, max: 256 },
        suggestionType: 'minecraft:ask_server'
      }
    },
    {
      flags: { command_node_type: 2, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
      children: [5],
      extraNodeData: {
        name: 'seed',
        parser: 'brigadier:long',
        properties: {
          flags: { min_present: 1, max_present: 1 },
          min: -5000000000n,
          max: 9007199254740993n
        }
      }
    },
    {
      flags: { command_node_type: 1, has_command: 0, has_redirect_node: 1, has_custom_suggestions: 0 },
      children: [],
      redirectNode: 1,
      extraNodeData: { name: 'betterrtp:rtp' }
    },
    {
      flags: { command_node_type: 2, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
      children: [],
      extraNodeData: {
        name: 'target',
        parser: 'minecraft:entity',
        properties: { unused: 0, onlyAllowPlayers: 1, onlyAllowEntities: 1 }
      }
    }
  ]
};

const pkt120 = roundTrip('1.20.1', 'declare_commands', rawCmdParams);
const pkt121 = roundTrip('1.21.4', 'declare_commands', rawCmdParams);
const graph120 = sw.serializeCommandPacket(pkt120);
const graph121 = sw.serializeCommandPacket(pkt121);

// Verify generic 2-int arrays are NOT coerced into 64-bit integers.
const pairKept = sw.jsonSafeValue([1, 2]);

// Real open_window packets (no slotCount in protocol):
const win120 = sw.parseWindowPacket('fzP_0', roundTrip('1.20.1', 'open_window', {
  windowId: 2,
  inventoryType: 2, // minecraft:generic_9x3 -> 27 slots
  windowTitle: JSON.stringify({ text: 'RTP Menu 1.20' })
}), '1.20.1');
const win121 = sw.parseWindowPacket('fzP_0', roundTrip('1.21.4', 'open_window', {
  windowId: 3,
  inventoryType: 5, // minecraft:generic_9x6 -> 54 slots
  windowTitle: { type: 'compound', value: { text: { type: 'string', value: 'RTP Menu 1.21' } } }
}), '1.21.4');
const winUnknown = sw.parseWindowPacket('fzP_0', {
  windowId: 4,
  inventoryType: 999,
  windowTitle: JSON.stringify({ text: 'Custom Unknown' })
}, '1.20.1');

// Default episode (telemetry: false) must emit no commands, tick_gap, or window events.
const defaultEmitted = [];
const epDefault = { telemetry: false, emit: (obj) => defaultEmitted.push(obj) };
const clientDefault = new EventEmitter();
const connDefault = { username: 'fzDef_0', spawned: true };
sw.attachProtocolTelemetry(epDefault, connDefault, { _client: clientDefault, version: '1.20.1' });
clientDefault.emit('declare_commands', pkt120);
clientDefault.emit('keep_alive', { keepAliveId: 1n });
clientDefault.emit('update_time', { age: 100n, time: 100n });
clientDefault.emit('update_time', { age: 120n, time: 120n });
clientDefault.emit('open_window', { windowId: 1, inventoryType: 2, windowTitle: '{"text":"No"}' });

// Opt-in telemetry episode (telemetry: true) emits tick_gap and window, and discover callback receives graph.
const emitted = [];
const ep = { telemetry: true, emit: (obj) => emitted.push(obj) };
const client = new EventEmitter();
const bot = { _client: client, version: '1.21.4' };
const conn = { username: 'fzP_0', spawned: false };
let discovered = null;
sw.attachProtocolTelemetry(ep, conn, bot, (g) => { discovered = g; });

client.emit('declare_commands', pkt121);
// Before spawn, update_time only records the timestamp without emitting tick_gap.
client.emit('update_time', { age: 100n, time: 100n });
conn.spawned = true;
const t0 = Date.now();
conn.lastKeepAliveAt = t0 - 150;
client.emit('keep_alive', { keepAliveId: 1n });
conn.lastUpdateTimeAt = Date.now() - 1050;
client.emit('update_time', { age: 120n, time: 120n });
client.emit('open_window', roundTrip('1.21.4', 'open_window', {
  windowId: 2,
  inventoryType: 2,
  windowTitle: { type: 'compound', value: { text: { type: 'string', value: 'RTP Menu' } } }
}));

// Verify parseEpisode telemetry validation and action boundaries.
const epParsedDefault = sw.parseEpisode({ id: 1, cmd: 'rtp', bots: [{ name: 'b0', program: ['quit'] }] });
const epParsedTel = sw.parseEpisode({ id: 2, cmd: 'rtp', telemetry: true, bots: [{ name: 'b0', program: ['quit'] }] });
let badActionRejected = false;
try { sw.parseAction({ a: 'window_click', slot: 0 }, 'step0'); } catch (e) { badActionRejected = true; }

console.log(JSON.stringify({
  graph120,
  graph121,
  pairKept,
  win120,
  win121,
  winUnknown,
  defaultEmitted,
  discovered: !!discovered,
  emitted,
  epTelemetryDefault: epParsedDefault.telemetry,
  epTelemetryOptIn: epParsedTel.telemetry,
  badActionRejected
}));
"""
        proc = subprocess.run([NODE, "-e", script, bf.SWARM_JS], check=True, capture_output=True, text=True, timeout=30)
        out = json.loads(proc.stdout.strip())
        self.assertTrue(out["discovered"])
        self.assertTrue(out["badActionRejected"])
        self.assertFalse(out["epTelemetryDefault"])
        self.assertTrue(out["epTelemetryOptIn"])
        self.assertEqual(out["pairKept"], [1, 2])
        self.assertEqual(out["defaultEmitted"], [])
        for graph in (out["graph120"], out["graph121"]):
            self.assertEqual(graph["rootIndex"], 0)
            self.assertEqual(len(graph["nodes"]), 6)
            n0, n1, n2, n3, n4, n5 = graph["nodes"]
            self.assertEqual((n0["kind"], n0["children"], n0["executable"]), ("root", [1, 4], False))
            self.assertEqual((n1["kind"], n1["name"], n1["children"], n1["executable"]), ("literal", "rtp", [2, 3], True))
            self.assertEqual((n2["kind"], n2["name"], n2["parser"], n2["executable"], n2["suggestions"]),
                             ("argument", "radius", "brigadier:integer", True, "minecraft:ask_server"))
            self.assertEqual(n2["properties"]["min"], 1)
            self.assertEqual(n2["properties"]["max"], 256)
            self.assertEqual((n3["kind"], n3["name"], n3["parser"]), ("argument", "seed", "brigadier:long"))
            self.assertEqual(n3["properties"]["min"], -5000000000)
            self.assertEqual(n3["properties"]["max"], "9007199254740993")
            self.assertEqual((n4["kind"], n4["name"], n4["redirect"]), ("literal", "betterrtp:rtp", 1))
            self.assertEqual((n5["kind"], n5["name"], n5["parser"]), ("argument", "target", "minecraft:entity"))
            self.assertEqual(n5["properties"]["onlyAllowPlayers"], 1)
            self.assertEqual(n5["properties"]["onlyAllowEntities"], 1)
        self.assertEqual(out["win120"], {
            "ev": "window", "bot": "fzP_0", "window_id": 2, "window_type": 2, "slots": 27, "title": "RTP Menu 1.20"
        })
        self.assertEqual(out["win121"], {
            "ev": "window", "bot": "fzP_0", "window_id": 3, "window_type": 5, "slots": 54, "title": "RTP Menu 1.21"
        })
        self.assertEqual(out["winUnknown"], {
            "ev": "window", "bot": "fzP_0", "window_id": 4, "window_type": 999, "slots": None, "title": "Custom Unknown"
        })
        emitted = out["emitted"]
        tick_gaps = [e for e in emitted if e["ev"] == "tick_gap"]
        self.assertEqual(len(tick_gaps), 1)
        self.assertEqual(tick_gaps[0]["bot"], "fzP_0")
        self.assertGreaterEqual(tick_gaps[0]["ms"], 1000)
        self.assertGreater(tick_gaps[0]["at"], 1700000000000)
        self.assertGreaterEqual(tick_gaps[0]["keepalive_ms"], 100)
        windows = [e for e in emitted if e["ev"] == "window"]
        self.assertEqual(len(windows), 1)
        self.assertEqual(windows[0], {
            "ev": "window",
            "bot": "fzP_0",
            "window_id": 2,
            "window_type": 2,
            "slots": 27,
            "title": "RTP Menu"
        })


class RepetitionTest(LoopFixture, unittest.TestCase):
    """--reps: growth per repetition separates a leak from a cache that fills once."""

    def extra_args(self):
        return ["--reps", "3"]

    def run_plan(self, program, console=None):
        self.assertTrue(self.tr.baseline(1, "base"))
        plan = {"origin": "cem", "parent": None, "params": None,
                "bots": [{"name": "fzrep_0", "program": program}]}
        if console:
            plan["console"] = console
        return self.tr.execute(1, 0, plan, None)

    def test_leak_keeps_its_slope(self):
        def leak_fd(req):
            self.server.fds += 1
        self.swarm.after = leak_fd
        rec = self.run_plan([{"a": "rtp"}, {"a": "quit"}])
        self.assertEqual(3, rec["reps"])
        self.assertEqual(3, len(rec["series"]))
        self.assertEqual(1.0, rec["deltas"]["slope.fd_open"])
        self.assertEqual(1.0, rec["deltas"]["slope.heap_used_after_gc_mb"])  # one leaked instance per rep
        self.assertEqual(0.0, rec["deltas"]["slope.threads_live"])
        self.assertEqual(3, self.server.retained)  # every repetition leaked
        labels = [c.split()[2] for c in self.server.cmds if c.endswith(" quick")]
        self.assertIn("fz20261010-200000-betterrtp-s1-e0k2", labels)
        # Repetitions run under fresh names: new players, not rejoins.
        names = [b["name"] for req, _ in self.swarm.requests for b in req["bots"]]
        self.assertEqual(3, len(set(names)))
        # The next episode starts from the last repetition's checkpoint.
        self.assertEqual(103, self.tr.prev["fd_open"])

    def test_cache_that_fills_once_has_no_slope(self):
        self.swarm.leak_quit = False
        calls = []

        def fill_once(req):
            calls.append(req["id"])
            if len(calls) == 1:
                self.server.fds += 5

        self.swarm.after = fill_once
        rec = self.run_plan([{"a": "rtp"}, {"a": "quit"}])
        self.assertEqual(5, rec["deltas"]["fd_open"])  # first run: looks like a leak
        self.assertEqual(0.0, rec["deltas"]["slope.fd_open"])  # repetitions: flat

    def test_console_actions_run_every_repetition_and_count_as_feature_and_state(self):
        rec = self.run_plan([{"a": "rtp"}], console=[{"at_ms": 0, "cmd": "betterrtp reload"}])
        self.assertEqual(3, self.server.cmds.count("betterrtp reload"))
        self.assertEqual(1, rec["features"]["console"])
        self.assertIn(["console betterrtp reload", "console:"], rec["states"])
        self.assertEqual(rec["console"], [{"at_ms": 0, "cmd": "betterrtp reload"}])
        # Replays carry the console actions with the programs.
        self.assertEqual(rec["console"], bf.replay_plan(rec, 0)["console"])

    def test_dirty_repetition_cuts_the_series_and_rebaselines(self):
        def linger(req):
            if req["id"].endswith("k1"):
                self.server.online, self.server.online_lists = ["bench_007"], 1000
        self.swarm.after = linger
        rec = self.run_plan([{"a": "rtp"}, {"a": "quit"}])
        self.assertEqual("players online", rec["reps_error"])
        self.assertEqual(1, rec["reps"])
        self.assertFalse(any(k.startswith(bf.SLOPE) for k in rec["deltas"]))
        self.assertIsNone(self.tr.prev)


class SlopeScoreTest(unittest.TestCase):
    def test_rep_slopes_are_least_squares_over_repetitions(self):
        s = bf.rep_slopes([{"a": 10.0, "b": 5.0}, {"a": 12.0, "b": 5.0}, {"a": 14.0}])
        self.assertEqual({"slope.a": 2.0}, s)  # b missing at one checkpoint: not sloped
        self.assertEqual({}, bf.rep_slopes([{"a": 1.0}]))
        self.assertAlmostEqual(1.55, bf.ls_slope([0, 1, 3, 4.5]))

    def test_slope_metrics_are_generic_resources_without_the_harness(self):
        m = bf.slope_metrics({"histogram_live_mb": 900.0, "pool.metaspace.used_mb": 120.0, "classes_loaded": 25000.0,
                              "rss_mb": -1.0, "plugin.rtp.live_instances": 4.0,
                              "plugin.stresstestrtp.live_instances": 9.0, "uptime_s": 50.0, "_histo": "x"})
        self.assertEqual({"histogram_live_mb", "pool.metaspace.used_mb", "classes_loaded",
                          "plugin.rtp.live_instances"}, set(m))

    def test_slope_scores_only_positive_excess(self):
        ref = {"slope.histogram_live_mb": {"n": 8, "intercept": 0.2, "coef": {}, "ci": {}, "noise": 1.0}}
        up = bf.net_leak({}, {"slope.histogram_live_mb": 4.2}, list(ref), ref)
        down = bf.net_leak({}, {"slope.histogram_live_mb": -30.0}, list(ref), ref)
        self.assertAlmostEqual(4.0, up)
        self.assertEqual(0.0, down)

    def test_fit_includes_slope_counts(self):
        recs = [{"deltas": {"slope.classes_loaded": float(i % 2), "slope.plugin.stresstestrtp.x": 1.0},
                 "features": {k: 0 for k in bf.FEATURES}} for i in range(6)]
        fit = bf.fit_from_records(recs)
        self.assertIn("slope.classes_loaded", fit)
        self.assertNotIn("slope.plugin.stresstestrtp.x", fit)


class ConsolePlanTest(unittest.TestCase):
    def test_parse_console(self):
        self.assertEqual({"rtp": ["rtp reload", "rtp reload config"], "betterrtp": ["betterrtp reload"]},
                         bf.parse_console("RTP=/rtp reload|rtp reload config, betterrtp=betterrtp reload,bad"))
        self.assertEqual({}, bf.parse_console(None))

    def test_plan_console_is_seeded_bounded_and_opt_in(self):
        bots = [{"name": "a", "program": [{"a": "rtp"}, {"a": "wait", "ms": 5000}]}]
        cfg = {"console": ["rtp reload"], "console_p": 1.0, "console_max": 3}
        a = bf.plan_console(bf.rng_for(1, 2, "console"), cfg, bots)
        self.assertEqual(a, bf.plan_console(bf.rng_for(1, 2, "console"), cfg, bots))
        self.assertTrue(1 <= len(a) <= 3)
        self.assertTrue(all(0 <= x["at_ms"] <= bf.planned_ms(bots[0]["program"]) for x in a))
        self.assertEqual(a, sorted(a, key=lambda x: x["at_ms"]))
        self.assertEqual([], bf.plan_console(bf.rng_for(1, 2, "console"), dict(cfg, console_p=0.0), bots))
        self.assertEqual([], bf.plan_console(bf.rng_for(1, 2, "console"), dict(cfg, console=[]), bots))

    def test_plan_episode_attaches_console_only_when_configured(self):
        cfg = {"min_bots": 1, "max_bots": 2, "min_s": 5, "max_s": 10, "novelty": 0.0, "run": "r"}
        self.assertNotIn("console", bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), cfg))
        cfg.update(console=["x reload"], console_p=1.0, console_max=1)
        self.assertEqual("x reload", bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), cfg)["console"][0]["cmd"])


class TargetSetupTest(unittest.TestCase):
    def test_every_overnight_target_has_a_namespaced_command(self):
        import bench_overnight as bo
        for target in [t.split("=")[0] for t in bo.DEFAULT_NAMES.split(",")]:
            self.assertIn(":", bf.command_for(target, {}), target)
        self.assertEqual("x:y", bf.command_for("ezrtp", {"ezrtp": "/x:y"}))

    def test_swarm_cap_covers_the_largest_rung(self):
        a = bf.build_parser().parse_args(["--server-dir", ".", "--rungs", "4x1,12x3,48x10"])
        self.assertEqual(48, bf.swarm_bot_cap(a))
        argv = bf.swarm_argv(a, 25565)
        self.assertEqual("48", argv[argv.index("--fuzz-max-bots") + 1])
        a = bf.build_parser().parse_args(["--server-dir", ".", "--rungs", "4x1"])
        self.assertEqual(a.max_bots, bf.swarm_bot_cap(a))

    def test_rungs_above_max_players_fail_at_startup(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        with open(os.path.join(d, "server.properties"), "w", encoding="utf-8") as fh:
            fh.write("rcon.password=x\nmax-players=10\n")
        with self.assertRaises(SystemExit) as cm:
            bf.main(["--server-dir", d, "--rungs", "4x1,12x3"])
        self.assertEqual(2, cm.exception.code)


class BehaviorOraclesTest(unittest.TestCase):
    def test_server_log_oracle_finds_uncaught_exception_and_attributes_by_package(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        log_path = os.path.join(d, "latest.log")
        oracle = bf.ServerLogOracle(log_path)
        marker = oracle.mark()

        with open(log_path, "w", encoding="utf-8") as fh:
            fh.write("[12:00:00 ERROR]: Exception in thread \"Server thread\" java.lang.NullPointerException: Cannot invoke rtp\n"
                     "\tat com.betterrtp.command.RTP.teleport(RTP.java:42)\n"
                     "\tat net.minecraft.server.MinecraftServer.tick(MinecraftServer.java:1200)\n")

        witness = [{"a": "rtp"}, {"a": "quit"}]
        findings = oracle.scan(marker, witness, "betterrtp")
        self.assertEqual(1, len(findings))
        f = findings[0]
        self.assertEqual("UNCAUGHT_EXCEPTION", f["class"])
        self.assertEqual("BetterRTP", f["attributed_plugin"])
        self.assertEqual(witness, f["witness_program"])
        self.assertIn("NullPointerException", f["evidence"]["log_snippet"])

    def test_server_log_oracle_finds_event_error_and_attributes_to_plugin(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        log_path = os.path.join(d, "latest.log")
        oracle = bf.ServerLogOracle(log_path)
        marker = oracle.mark()

        with open(log_path, "w", encoding="utf-8") as fh:
            fh.write("[12:00:00 ERROR]: Could not pass event PlayerTeleportEvent to HuskHomes v4.7.2\n"
                     "org.bukkit.event.EventException: null\n"
                     "\tat org.bukkit.plugin.java.JavaPluginLoader$1.execute(JavaPluginLoader.java:310)\n"
                     "\tat net.william278.huskhomes.event.TeleportListener.onTeleport(TeleportListener.java:55)\n")

        findings = oracle.scan(marker, [{"a": "rtp"}], "huskhomes")
        self.assertEqual(1, len(findings))
        f = findings[0]
        self.assertEqual("EVENT_ERROR", f["class"])
        self.assertEqual("HuskHomes", f["attributed_plugin"])
        self.assertEqual("HIGH", f["severity"])

    def test_server_log_oracle_finds_watchdog_stall(self):
        oracle = bf.ServerLogOracle(None)
        text = "[12:00:00 WARN]: Can't keep up! Is the server overloaded? Running 5400ms or 108 ticks behind\n"
        findings = oracle.analyze_text(text, [{"a": "rtp"}], "direct")
        self.assertEqual(1, len(findings))
        f = findings[0]
        self.assertEqual("WATCHDOG_STALL", f["class"])
        self.assertEqual("HIGH", f["severity"])
        self.assertEqual(5400, f["evidence"]["ms_behind"])

    def test_least_privilege_oracle_flags_denial_failure(self):
        oracle = bf.LeastPrivilegeOracle()
        events = [
            {"ev": "act", "bot": "bench_01", "a": "cmd", "cmd": "op bench_01"},
            {"ev": "denial_failure", "bot": "bench_01", "cmd": "op bench_01", "text": "Made bench_01 a server operator"}
        ]
        witness = [{"a": "cmd", "cmd": "op bench_01"}]
        findings = oracle.check_events(events, witness, "LeafRTP")
        self.assertEqual(1, len(findings))
        f = findings[0]
        self.assertEqual("PERMISSION_BYPASS", f["class"])
        self.assertEqual("HIGH", f["severity"])
        self.assertEqual("op bench_01", f["evidence"]["cmd"])
        self.assertEqual(witness, f["witness_program"])


class PlantedBugLoopTest(LoopFixture, unittest.TestCase):
    def test_end_to_end_loop_detects_planted_log_error(self):
        log_dir = os.path.join(self.dir, "logs")
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, "latest.log")

        def plant_log_error(req):
            with open(log_file, "a", encoding="utf-8") as fh:
                fh.write("[12:00:00 ERROR]: Exception in thread \"Server thread\" java.lang.RuntimeException: Planted test error\n"
                         "\tat com.betterrtp.Core.test(Core.java:100)\n")

        self.swarm.after = plant_log_error
        plan = {"origin": "gen", "bots": [{"name": "bot1", "program": [{"a": "rtp"}]}]}
        rec = self.tr.execute(1, 0, plan, None)
        self.assertIn("findings", rec)
        self.assertEqual(1, len(rec["findings"]))
        self.assertEqual("UNCAUGHT_EXCEPTION", rec["findings"][0]["class"])
        self.assertEqual("BetterRTP", rec["findings"][0]["attributed_plugin"])
        self.assertTrue(os.path.exists(os.path.join(self.tdir, "findings.jsonl")))
        loaded_findings = bf.read_trace(os.path.join(self.tdir, "findings.jsonl"))
        self.assertEqual(1, len(loaded_findings))

    def test_end_to_end_loop_detects_planted_denial_failure(self):
        def plant_denial(req):
            # swarm hook triggers a denial failure event
            pass
        # Override swarm run_episode to produce a denial_failure event
        orig_run_episode = self.swarm.run_episode

        def fake_run_episode(req, timeout_s):
            events, done = orig_run_episode(req, timeout_s)
            events.append({"ev": "denial_failure", "bot": "b0", "cmd": "rtp reload", "text": "Reloaded!"})
            return events, done

        self.swarm.run_episode = fake_run_episode
        plan = {"origin": "gen", "bots": [{"name": "bot1", "program": [{"a": "cmd", "cmd": "rtp reload"}]}]}
        rec = self.tr.execute(1, 0, plan, None)
        self.assertIn("findings", rec)
        self.assertTrue(any(f["class"] == "PERMISSION_BYPASS" for f in rec["findings"]))


def planted_trace(line=42, exc="java.lang.IllegalStateException", pkg="com.betterrtp.command.RTP"):
    return (f"[12:00:00 ERROR]: Exception in thread \"Server thread\" {exc}: boom {line}\n"
            f"\tat {pkg}.lambda$teleport${line % 7}(RTP.java:{line})\n"
            "\tat net.minecraft.server.MinecraftServer.tick(MinecraftServer.java:1200)\n")


class CoverageReaderTest(unittest.TestCase):
    """Probe keys from a /rtpstress coverage JSON dump (ADR-111 section 2.6)."""

    def test_probe_keys_from_dump(self):
        data = {"label": "x", "classes": {"a.B": [0, 3], "a.C": []}}
        self.assertEqual(bf.coverage_probes(data), {"a.B#0", "a.B#3"})
        self.assertEqual(bf.coverage_probes({}), set())

    def test_reader_fetches_dump_and_marks_probes_seen(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "coverage-run1.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"label": "run1", "classes": {"a.B": [1]}}, f)
            r = bf.CoverageReader(d)
            probes = r.fetch("run1", wait_s=0.5)
            self.assertEqual(probes, {"a.B#1"})
            self.assertEqual(r.new_probes(probes), ["a.B#1"])
            self.assertEqual(r.new_probes(probes), [])
            self.assertIsNone(r.fetch("missing", wait_s=0.1, poll_s=0.05))


class FindingSignatureTest(unittest.TestCase):
    """Stable signatures, strict trace parsing and attribution of log findings."""

    def scan(self, text, target="betterrtp", **kw):
        return bf.ServerLogOracle(None, **kw).analyze_text(text, [], target)

    def test_signature_ignores_line_numbers_lambdas_and_messages(self):
        a, b = self.scan(planted_trace(42)), self.scan(planted_trace(97))
        self.assertEqual(1, len(a))
        self.assertEqual(a[0]["signature"], b[0]["signature"])
        self.assertEqual(a[0]["finding_id"], b[0]["finding_id"])
        self.assertEqual("BetterRTP|java.lang.IllegalStateException|com.betterrtp.command.RTP.lambda$teleport$#",
                         a[0]["key"])
        other = self.scan(planted_trace(42, exc="java.lang.NullPointerException"))
        self.assertNotEqual(a[0]["signature"], other[0]["signature"])
        self.assertEqual("CRITICAL", a[0]["severity"])  # on the main thread

    def test_root_cause_and_plugin_frame_pick_the_key(self):
        text = ("[12:00:00 ERROR]: Could not pass event PlayerQuitEvent to BetterRTP v3.6.13\n"
                "org.bukkit.event.EventException: null\n"
                "\tat org.bukkit.plugin.java.JavaPluginLoader$1.execute(JavaPluginLoader.java:310)\n"
                "Caused by: java.util.ConcurrentModificationException\n"
                "\tat java.base/java.util.HashMap$HashIterator.nextNode(HashMap.java:1605)\n"
                "\tat app//com.betterrtp.player.Queue.drain(Queue.java:88)\n"
                "\t... 12 more\n")
        f = self.scan(text)
        self.assertEqual(1, len(f))
        self.assertEqual("EVENT_ERROR", f[0]["class"])
        self.assertEqual("BetterRTP|PlayerQuitEvent|java.util.ConcurrentModificationException|"
                         "com.betterrtp.player.Queue.drain", f[0]["key"])

    def test_words_and_class_names_without_a_trace_are_not_exceptions(self):
        text = ("[12:00:00 INFO]: [BetterRTP] Error: no safe location found\n"
                "[12:00:00 INFO]: [BetterRTP] registered java.lang.String adapter\n"
                "[12:00:00 WARN]: Exception: none\n"
                "[12:00:01 INFO]: Done\n")
        self.assertEqual([], self.scan(text))

    def test_print_stack_trace_lines_with_log_prefixes_are_a_trace(self):
        text = ("[12:00:00 WARN]: java.lang.IllegalArgumentException: bad radius\n"
                "[12:00:00 WARN]: \tat com.betterrtp.region.Shape.pick(Shape.java:10)\n"
                "[12:00:00 WARN]: \tat org.bukkit.craftbukkit.scheduler.CraftTask.run(CraftTask.java:82)\n")
        f = self.scan(text)
        self.assertEqual(1, len(f))
        self.assertEqual("BetterRTP|java.lang.IllegalArgumentException|com.betterrtp.region.Shape.pick", f[0]["key"])
        self.assertEqual("HIGH", f[0]["severity"])

    def test_unknown_packages_are_never_blamed_on_the_target(self):
        text = planted_trace(pkg="org.example.other.Thing")
        f = self.scan(text)
        self.assertEqual("unknown", f[0]["attributed_plugin"])
        f = self.scan(text, packages=bf.plugin_packages("org.example.other=Other"))
        self.assertEqual("Other", f[0]["attributed_plugin"])

    def test_stalls_belong_to_the_server(self):
        f = self.scan("[12:00:00 WARN]: Can't keep up! Is the server overloaded? Running 2100ms or 42 ticks behind\n"
                      "[12:00:09 ERROR]: The server has not responded for 10 seconds! Creating thread dump\n")
        self.assertEqual([("WATCHDOG_STALL", "MEDIUM", "lag"), ("WATCHDOG_STALL", "HIGH", "watchdog")],
                         [(x["class"], x["severity"], x["key"]) for x in f])
        self.assertTrue(all(x["attributed_plugin"] == "Server" for x in f))

    def test_replaced_log_is_read_from_its_start(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        path = os.path.join(d, "latest.log")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("x" * 4000 + "\n")
        o = bf.ServerLogOracle(path)
        o.begin()
        os.remove(path)  # restart: latest.log rotated away, a fresh, shorter one written
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(planted_trace())
        self.assertEqual(1, len(o.observe([], [], "betterrtp")))
        o.begin()
        self.assertEqual([], o.observe([], [], "betterrtp"))  # nothing new since the mark

    def test_permission_signature_drops_the_bot_name(self):
        o = bf.LeastPrivilegeOracle()
        ev = [{"ev": "denial_failure", "bot": "fzA_0", "cmd": "/op fzA_0", "text": "Made fzA_0 a server operator"}]
        ev2 = [{"ev": "denial_failure", "bot": "fzB_1", "cmd": "/op fzB_1", "text": "Made fzB_1 a server operator"}]
        self.assertEqual(o.observe(ev, [], "rtp")[0]["signature"], o.observe(ev2, [], "rtp")[0]["signature"])
        self.assertEqual("op {self}", o.observe(ev, [], "rtp")[0]["key"])

    def test_target_aliases_are_exact(self):
        a = bf.build_parser().parse_args(["--server-dir", "."])
        self.assertEqual({"rtp", "leafrtp"}, bf.target_aliases(a, "rtp"))
        self.assertNotIn("betterrtp", bf.target_aliases(a, "rtp"))
        self.assertEqual({"direct"}, bf.target_aliases(a, "direct"))

    def test_corpus_keeps_a_new_signature(self):
        c = bf.Corpus()
        self.assertTrue(c.consider(0, [], [["join", "x"]], {}, ["s1"])["kept"])
        res = c.consider(1, [], [["join", "x"]], {}, ["s1"])
        self.assertFalse(res["kept"])
        res = c.consider(2, [], [["join", "x"]], {}, ["s1", "s2"])
        self.assertTrue(res["kept"])
        self.assertEqual(["s2"], res["new_findings"])

    def test_verdict_with_findings(self):
        target = {"target_attributed": True, "in_control": False}
        self.assertEqual("FAIL", bf.target_verdict(True, [], False, [dict(target, reproduced=True)]))
        self.assertEqual("INCONCLUSIVE", bf.target_verdict(True, [], False, [dict(target, reproduced=False)]))
        noise = [{"target_attributed": True, "in_control": True, "reproduced": False},
                 {"target_attributed": False, "in_control": False, "reproduced": False}]
        self.assertEqual("PASS", bf.target_verdict(True, [], False, noise))

    def test_summary_dedupes_by_signature(self):
        fs = self.scan(planted_trace(1)) + self.scan(planted_trace(2))
        for i, f in enumerate(fs):
            f.update(seed=1, ep=i, oracle="log", repeats=2, target_attributed=True, in_control=False)
        s = bf.summarise_findings(fs, {fs[0]["signature"]: {"k": 3, "n": 3, "reproduced": True}})
        self.assertEqual(1, len(s))
        self.assertEqual(4, s[0]["seen"])
        self.assertEqual({"seed": 1, "ep": 0}, s[0]["first"])
        self.assertTrue(s[0]["reproduced"])
        self.assertEqual({s[0]["signature"]}, set(bf.control_findings(fs)["signatures"]))


class FaultModeTest(LoopFixture, unittest.TestCase):
    """--mode faults: one checkpoint-free run per episode, coverage and finding oracles only."""

    def extra_args(self):
        return ["--mode", "faults", "--coverage"]

    def test_mode_defaults_to_leak_and_is_faults_only_for_targets(self):
        self.assertTrue(self.tr.faults)
        leak = bf.TargetRun(bf.build_parser().parse_args(["--server-dir", self.dir]), self.server, self.swarm,
                            bf.RunsReader(self.runs), "betterrtp", "20261010-200000", self.tdir)
        self.assertFalse(leak.faults)
        control = bf.TargetRun(self.a, self.server, self.swarm, bf.RunsReader(self.runs), bf.DIRECT,
                               "20261010-200000", self.tdir)
        self.assertFalse(control.faults)

    def test_episode_makes_no_checkpoint_and_writes_no_deltas(self):
        plan = bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg)
        rec = self.tr.execute(1, 0, plan, bf.Corpus())
        self.assertEqual(len(self.swarm.requests), 1)
        self.assertFalse(any("leakcheck" in c for c in self.server.cmds))
        self.assertNotIn("excluded", rec)
        self.assertIsNone(rec["deltas"])
        self.assertIsNone(self.tr.prev)
        self.assertFalse(os.path.exists(os.path.join(self.tdir, bf.CLASSES_FILE)))
        self.assertTrue(any(c.startswith("rtpstress coverage ") for c in self.server.cmds))
        self.assertEqual(self.records()[0]["ep"], 0)

    def test_run_seed_skips_baseline_and_keeps_every_episode(self):
        recs = self.tr.run_seed(1)
        self.assertEqual(len(recs), 4)
        self.assertEqual(len(self.swarm.requests), 4)
        self.assertFalse(any("leakcheck" in c for c in self.server.cmds))
        self.assertTrue(all(r["deltas"] is None for r in recs))

    def test_lingering_bots_still_exclude_the_episode(self):
        self.server.online, self.server.online_lists = ["fzlinger_0"], 1
        rec = self.tr.execute(1, 0, bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus())
        self.assertEqual(rec["excluded"], "other players online after the episode")
        self.assertEqual(rec["lingering"], ["fzlinger_0"])

    def write_properties(self):
        with open(os.path.join(self.dir, "server.properties"), "w", encoding="utf-8") as fh:
            fh.write("rcon.password=test\nrcon.port=25575\nserver-port=25565\n")

    def test_missing_coverage_flag_is_refused_before_any_server_call(self):
        self.write_properties()
        err = io.StringIO()
        with unittest.mock.patch.object(bf, "FuzzDriver", side_effect=AssertionError("server touched")), \
                contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as cm:
            bf.main(["--server-dir", self.dir, "--mode", "faults", "--out-dir", self.dir])
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("--mode faults needs --coverage", err.getvalue())

    def test_reps_above_one_runs_one_swarm_request_and_no_repetitions(self):
        self.a.reps = 3
        rec = self.tr.execute(1, 0, bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus())
        self.assertEqual(len(self.swarm.requests), 1)
        self.assertNotIn("reps", rec)
        self.assertNotIn("excluded", rec)
        self.assertFalse(any("leakcheck" in c for c in self.server.cmds))
        self.assertIsNone(rec["deltas"])

    def test_lingering_players_exclude_the_next_episode_before_it_starts(self):
        self.server.online, self.server.online_lists = ["fzlinger_0"], None
        first = self.tr.execute(1, 0, bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus())
        self.assertEqual(first["excluded"], "other players online after the episode")
        self.assertTrue(self.tr.dirty)
        second = self.tr.execute(1, 1, bf.plan_episode(1, 1, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus())
        self.assertEqual(second["excluded"], "other players online before the episode")
        self.assertEqual(second["lingering"], ["fzlinger_0"])
        self.assertEqual(len(self.swarm.requests), 1)  # no bots start beside the stray players
        self.assertTrue(self.tr.dirty)
        self.server.online = []
        third = self.tr.execute(1, 2, bf.plan_episode(1, 2, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus())
        self.assertNotIn("excluded", third)
        self.assertFalse(self.tr.dirty)
        self.assertEqual(len(self.swarm.requests), 2)

    def test_replay_in_faults_mode_takes_no_baseline(self):
        rec = {"ep": 0, "seed": 1, "bots": bf.gen_episode(1, 0, bf.DEFAULT_PARAMS, self.tr.cfg),
               "params": None, "console": None}
        self.a.replay_n = 2
        with unittest.mock.patch.object(bf, "online_names", return_value=[]), \
                unittest.mock.patch.object(bf.lc, "prepare_target", return_value=True), \
                unittest.mock.patch.object(bf.time, "sleep"):
            bf.run_target(self.a, self.server, self.swarm, "betterrtp", [1], self.tdir, "20261010-200000",
                          unittest.mock.Mock(), replay_rec=rec)
        self.assertEqual(len(self.swarm.requests), 2)
        self.assertFalse(any("leakcheck" in c for c in self.server.cmds))

    def test_planted_exception_is_replayed_without_checkpoint_and_confirmed(self):
        os.makedirs(os.path.join(self.dir, "logs"))
        log_file = os.path.join(self.dir, "logs", "latest.log")
        self.swarm.leak_quit = False  # no leak signal: only the planted exception matters

        def plant(req):
            with open(log_file, "a", encoding="utf-8") as fh:
                fh.write(planted_trace(41))
        self.swarm.after = plant
        self.tr.run_seed(1)
        replays = bf.read_trace(os.path.join(self.tdir, bf.FINDING_REPLAYS_FILE))
        self.assertEqual(1, len(replays))
        self.assertEqual((3, 3, True), (replays[0]["k"], replays[0]["n"], replays[0]["reproduced"]))
        recs = self.records()
        replay_recs = [r for r in recs if r["origin"] == "finding"]
        self.assertEqual(3, len(replay_recs))
        for r in replay_recs:
            self.assertTrue(r["replay"])
            self.assertIsNone(r["deltas"])  # no checkpoint: the replay confirms by signature only
            self.assertIn(replays[0]["signature"], [f["signature"] for f in r["findings"]])
        checkpoints = [c.split()[2] for c in self.server.cmds
                       if c.startswith("rtpstress leakcheck fz")]
        self.assertTrue(checkpoints)  # the promising episode gets its leak re-run
        self.assertTrue(all(re.search(r"-(p\d+b|ep\d+)$", label) for label in checkpoints), checkpoints)
        self.assertEqual(4, self.tr.coverage["planned"])  # replays and re-runs are not planned episodes
        self.assertGreater(self.tr.coverage["episodes"], self.tr.coverage["planned"])
        v = self.tr.finish([], [])
        self.assertEqual("FAIL", v["verdict"])
        self.assertEqual(["log"], v["confirmed_by"])

    def test_episodes_per_hour_counts_executed_planned_episodes_on_the_exploration_clock(self):
        ticks = itertools.count()
        with unittest.mock.patch.object(bf.time, "time", side_effect=lambda: 1000.0 + 0.5 * next(ticks)):
            self.tr.run_seed(1)
        cov = self.tr.coverage
        self.assertGreater(cov["explore_s"], 0)
        self.assertEqual(cov["executed"], 4)
        v = self.tr.finish([], [])
        self.assertEqual(v["coverage"]["executed"], 4)
        self.assertEqual(v["coverage"]["episodes_per_hour"], bf.episodes_per_hour(4, cov["explore_s"]))
        self.assertEqual(bf.episodes_per_hour(4, 1800.0), 8.0)
        self.assertEqual(bf.episodes_per_hour(0, 0.0), None)

    def test_dirty_start_exclusion_is_planned_but_not_executed(self):
        self.server.online, self.server.online_lists = ["fzlinger_0"], None
        self.tr.dirty = True
        self.tr.run_seed(1)
        cov = self.tr.coverage
        self.assertEqual(cov["planned"], 4)
        self.assertEqual(cov["executed"], 0)
        self.assertEqual(len(self.swarm.requests), 0)  # no bots started beside the stray player

    def promoting_tr(self, probes_by_ep):
        """TargetRun whose coverage dump reports `probes_by_ep[ep]` (a set) for each episode."""
        def fake_probes(label):
            ep = int(label.rsplit("e", 1)[1].rstrip("b"))
            return set(probes_by_ep.get(ep, ()))
        self.tr.coverage_probes = fake_probes
        return self.tr

    def ladder_rows(self):
        with open(os.path.join(self.tdir, "ladder.jsonl"), encoding="utf-8") as fh:
            return [json.loads(line) for line in fh]

    def test_promising_episode_gets_exactly_one_leak_rerun(self):
        tr = self.promoting_tr({0: {"p1", "p2"}})
        recs = tr.run_seed(1)
        self.assertEqual(len(tr.promotions), 0)  # drained at the generation end
        self.assertTrue(recs[0]["kept"])
        rerun = [r for r in self.records() if r["origin"] == "leakrerun"]
        self.assertEqual(len(rerun), 1)
        self.assertNotIn("excluded", rerun[0])
        self.assertEqual(rerun[0]["parent"], 0)
        base = "fz20261010-200000-betterrtp-s1-p0b"
        self.assertEqual(sum(1 for c in self.server.cmds if c == f"rtpstress leakcheck {base} quick"), 1)
        self.assertEqual(sum(1 for c in self.server.cmds if c == "rtpstress leakcheck fz20261010-200000-betterrtp-s1-ep0 quick"), 1)  # the checkpointed run
        self.assertIsNotNone(rerun[0]["deltas"])
        self.assertIn("heap_used_after_gc_mb", rerun[0]["deltas"])
        self.assertGreater(rerun[0]["departures"], 0)
        self.assertNotIn("kept", rerun[0])  # re-runs are never offered to the corpus
        mine = [x for x in self.ladder_rows() if x.get("origin") == "leakrerun"]
        self.assertEqual(len(mine), 1)
        self.assertEqual(mine[0]["cand"], "s1e0")
        self.assertEqual(mine[0]["probes"], 2)
        self.assertEqual(mine[0]["confirm"], {"status": "pending", "reason": "final confirm only on a ladder climb"})

    def test_leak_reruns_reach_the_fit(self):
        tr = self.promoting_tr({0: {"p1"}, 1: {"p2"}, 2: {"p3"}, 3: {"p4"}})
        tr.run_seed(1)
        with open(os.path.join(self.tdir, "fit.json"), encoding="utf-8") as fh:
            fit = json.load(fh)["fit"]
        self.assertEqual(fit["threads_live"]["n"], 4)  # the 4 re-runs; fault episodes have no deltas
        self.assertEqual(fit[LEAK_CLS]["n"], 4)  # the leaking class was screened from them

    def test_rung_on_a_faults_mode_candidate_takes_checkpointed_runs_and_a_confirm(self):
        c = {"cand": "s1e0", "seed": 1, "ep": 0, "count": LEAK_CLS, "source": "flag",
             "shrunk": False, "bots": bf.gen_episode(1, 0, bf.DEFAULT_PARAMS, self.tr.cfg), "console": None,
             "net0": 1.0, "bots0": 2, "params": None}
        res = self.tr.run_rung(c, 0, 2, 2, True)
        self.assertEqual(len(res["nets"]), 2)  # both rung runs were measured
        for j in range(2):
            self.assertEqual(sum(1 for x in self.server.cmds if x.startswith("rtpstress leakcheck") and f"L0r{j}" in x), 1)
        self.assertIn("fail", res["confirm"])
        self.assertEqual(self.ladder_rows()[-1]["cand"], "s1e0")

    def test_leak_rerun_contamination_blocks_the_next_fault_episode(self):
        tr = self.promoting_tr({})
        bots = bf.gen_episode(1, 0, bf.DEFAULT_PARAMS, tr.cfg)
        tr.promotions.append({"ep": 0, "bots": bots, "console": None, "probes": 1, "signatures": []})

        def leave_player(req):
            if req["id"].endswith("-ep0"):
                self.server.online = ["fzlinger_0"]

        self.swarm.after = leave_player
        reruns, _ = tr.drain_promotions(1, 0)
        self.assertEqual(len(reruns), 1)
        self.assertTrue(tr.dirty)
        requests = len(self.swarm.requests)
        rec = tr.execute(1, 1, bf.plan_episode(1, 1, bf.DEFAULT_PARAMS, bf.Corpus(), tr.cfg), bf.Corpus())
        self.assertEqual(rec["excluded"], "other players online before the episode")
        self.assertEqual(len(self.swarm.requests), requests)

    def test_rung_contamination_blocks_the_next_fault_episode(self):
        def leave_player(req):
            if req["id"].endswith("L0r0"):
                self.server.online = ["fzlinger_0"]

        self.swarm.after = leave_player
        c = {"cand": "s1e0", "seed": 1, "ep": 0, "count": LEAK_CLS, "source": "flag",
             "shrunk": False, "bots": bf.gen_episode(1, 0, bf.DEFAULT_PARAMS, self.tr.cfg), "console": None,
             "net0": 1.0, "bots0": 2, "params": None}
        rung = self.tr.run_rung(c, 0, 2, 1, False)
        self.assertEqual(rung["nets"], [])
        self.assertTrue(self.tr.dirty)
        requests = len(self.swarm.requests)
        rec = self.tr.execute(1, 1, bf.plan_episode(1, 1, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus())
        self.assertEqual(rec["excluded"], "other players online before the episode")
        self.assertEqual(len(self.swarm.requests), requests)

    def test_leak_reruns_flow_through_fit_pick_and_confirmed_climb(self):
        tr = self.promoting_tr({i: {f"p{i}"} for i in range(8)})
        tr.a.episodes = 8
        recs = tr.run_seed(1)
        reruns = [r for r in recs if r["origin"] == "leakrerun"]
        picked = tr.pick_candidates(1, recs, 4)
        self.assertTrue(reruns)
        self.assertTrue(any(r["origin"] == "leakrerun" for r, _, _ in picked))

        shrunk = tr.shrink_top(1, recs, 1)
        candidates = tr.ladder_candidates(1, recs, shrunk)
        self.assertTrue(candidates)
        climbed = tr.climb(candidates)
        self.assertEqual(tr.finish([], climbed)["verdict"], "FAIL")
        rerun_rows = [r for r in self.ladder_rows() if r.get("origin") == "leakrerun"]
        rr = {r["climb_cand"] for r in rerun_rows if "climb_cand" in r}  # dropped rows have none
        self.assertTrue(any(r.get("confirm", {}).get("fail") and r["cand"] in rr for r in climbed))

    def test_rerun_is_never_promoted_again(self):
        tr = self.promoting_tr({0: {"p1"}, 1: {"p1", "p2"}, 2: {"p1", "p3"}, 3: {"p1", "p4"}})
        tr.run_seed(1)
        reruns = [r for r in self.records() if r["origin"] == "leakrerun"]
        self.assertEqual(len(reruns), 4)  # each fault episode promotes once, never a re-run of a re-run
        self.assertEqual(sorted(r["parent"] for r in reruns), [0, 1, 2, 3])

    def test_promote_budget_caps_reruns_and_logs_dropped(self):
        self.a.promote_max = 1
        logged = []
        tr = self.promoting_tr({0: {"p1"}, 1: {"p2"}, 2: {"p3"}, 3: {"p4"}})
        with unittest.mock.patch.object(bf, "log", logged.append):
            tr.run_seed(1)
        self.assertEqual(len([r for r in self.records() if r["origin"] == "leakrerun"]), 1)
        dropped = [x for x in self.ladder_rows() if x.get("dropped") == "budget"]
        self.assertEqual(len(dropped), 3)
        self.assertTrue(any("leak re-run dropped (budget)" in m for m in logged))

    def test_foreign_player_at_rerun_baseline_excludes_candidate(self):
        tr = self.promoting_tr({0: {"p1"}})
        self.server.online = ["bench_007"]
        tr.promotions.append({"ep": 0, "bots": [], "console": None, "probes": 1, "signatures": []})
        logged = []
        with unittest.mock.patch.object(bf, "log", logged.append):
            out, surprises = tr.drain_promotions(1, 0)
        self.assertEqual((out, surprises), ([], []))
        self.assertFalse(any("leakcheck" in c and "-p0b" in c for c in self.server.cmds))
        self.assertEqual(self.ladder_rows()[-1]["dropped"], "baseline")
        self.assertTrue(any("leak re-run dropped (baseline)" in m for m in logged))
        self.assertTrue(tr.dirty)  # the failed baseline left players online: the next episode settles first

    def test_reps_three_gives_one_baseline_and_three_checkpoints_on_the_rerun(self):
        self.a.reps = 3
        tr = self.promoting_tr({0: {"p1"}})
        tr.run_seed(1)
        base = "fz20261010-200000-betterrtp-s1-p0b"
        self.assertEqual(sum(1 for c in self.server.cmds if c == f"rtpstress leakcheck {base} quick"), 1)
        ck = "fz20261010-200000-betterrtp-s1-ep0"
        for label in (ck, ck + "k1", ck + "k2"):
            self.assertEqual(sum(1 for c in self.server.cmds if c == f"rtpstress leakcheck {label} quick"), 1)
        rerun = next(r for r in self.records() if r["origin"] == "leakrerun")
        self.assertEqual(rerun["reps"], 3)
        self.assertTrue(any(k.startswith(bf.SLOPE) for k in rerun["deltas"]))
        faults = [r for r in self.records() if r["origin"] != "leakrerun"]
        self.assertTrue(all(r["deltas"] is None for r in faults))

    def test_promote_max_below_zero_is_refused(self):
        self.write_properties()
        err = io.StringIO()
        with unittest.mock.patch.object(bf, "FuzzDriver", side_effect=AssertionError("server touched")), \
                contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as cm:
            bf.main(["--server-dir", self.dir, "--mode", "faults", "--coverage", "--promote-max", "-1",
                     "--out-dir", self.dir])
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("need --promote-max >= 0", err.getvalue())

    def test_reps_two_contaminates_next_episode(self):
        self.a.reps = 2
        # Contamination at the second repetition (index 1)
        self.swarm.after = lambda req: setattr(self.server, "online", ["fzlinger_0"]) if "k1" in req["id"] else None

        # leak=True with reps=2 triggers repeat()
        rec = self.tr.execute(1, 0, bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus(), leak=True)

        self.assertTrue(self.tr.dirty)
        self.assertIn("reps_error", rec)  # from the repeat()

        # Next episode should be blocked:
        second = self.tr.execute(1, 1, bf.plan_episode(1, 1, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus())
        self.assertEqual(second["excluded"], "other players online before the episode")
        self.assertEqual(len(self.swarm.requests), 2)  # First episode had 1 run + 1 repetition

    def test_failed_baseline_contaminates_next_episode(self):
        # Baseline fails
        self.server.online = ["fzlinger_0"]

        # Execute an episode with leak=True, which triggers baseline()
        rec = self.tr.execute(1, 0, bf.plan_episode(1, 0, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus(), leak=True)

        self.assertTrue(self.tr.dirty)

        # Next episode should be blocked
        second = self.tr.execute(1, 1, bf.plan_episode(1, 1, bf.DEFAULT_PARAMS, bf.Corpus(), self.tr.cfg), bf.Corpus())
        self.assertEqual(second["excluded"], "other players online before the episode")
        self.assertEqual(len(self.swarm.requests), 0) # No swarm requests at all


class FindingLoopTest(LoopFixture, unittest.TestCase):
    """Findings drive the corpus, replays, shrinking and the verdict."""

    def setUp(self):
        super().setUp()
        self.swarm.leak_quit = False  # no leak signal: only findings decide
        self.log_file = os.path.join(self.dir, "logs", "latest.log")
        os.makedirs(os.path.dirname(self.log_file))
        self.planted = 0

    def plant_when(self, pred, trace=None):
        def hook(req):
            if pred(req):
                self.planted += 1
                with open(self.log_file, "a", encoding="utf-8") as fh:
                    fh.write(trace or planted_trace(40 + self.planted))
        self.swarm.after = hook

    def test_reproducible_target_exception_fails_the_verdict(self):
        self.plant_when(lambda req: True)
        self.tr.run_seed(1)
        replays = bf.read_trace(os.path.join(self.tdir, bf.FINDING_REPLAYS_FILE))
        self.assertEqual(1, len(replays))  # one signature, tested once
        self.assertEqual((3, 3, True), (replays[0]["k"], replays[0]["n"], replays[0]["reproduced"]))
        recs = bf.read_trace(self.tr.trace)
        self.assertEqual(3, sum(1 for r in recs if r["origin"] == "finding"))
        self.assertTrue(all(r.get("reps") is None for r in recs if r["origin"] == "finding"))
        first = next(r for r in recs if r["ep"] == 0)
        self.assertTrue(first["kept"])
        self.assertEqual([replays[0]["signature"]], first["new_findings"])  # the new signature kept it
        v = self.tr.finish([], [])
        self.assertEqual("FAIL", v["verdict"])
        self.assertEqual(["log"], v["confirmed_by"])
        self.assertEqual(1, len(v["findings"]))
        self.assertTrue(v["findings"][0]["reproduced"])
        self.assertEqual(1, v["coverage"]["finding_signatures"])
        rep = bf.render_report(os.path.dirname(self.tdir), ["out"], {"seeds": [1]})
        self.assertIn("=== out: FAIL", rep)
        self.assertIn("confirmed by: log", rep)
        self.assertIn("reproduced 3 of 3", rep)

    def test_one_off_target_exception_is_inconclusive(self):
        self.plant_when(lambda req: self.planted == 0)
        self.tr.run_seed(1)
        replays = bf.read_trace(os.path.join(self.tdir, bf.FINDING_REPLAYS_FILE))
        self.assertEqual([(0, 2, False)], [(r["k"], r["n"], r["reproduced"]) for r in replays])  # 2nd miss: out of reach
        v = self.tr.finish([], [])
        self.assertEqual("INCONCLUSIVE", v["verdict"])
        self.assertEqual([], v["confirmed_by"])

    def test_control_signature_is_never_replayed(self):
        self.plant_when(lambda req: True)
        self.tr.control_findings = {bf.ServerLogOracle(None).analyze_text(planted_trace(), [], "")[0]["signature"]}
        self.tr.run_seed(1)
        self.assertFalse(os.path.exists(os.path.join(self.tdir, bf.FINDING_REPLAYS_FILE)))
        v = self.tr.finish([], [])
        self.assertEqual("PASS", v["verdict"])
        self.assertTrue(v["findings"][0]["in_control"])

    def test_other_plugins_exception_is_not_replayed(self):
        self.plant_when(lambda req: True, planted_trace(pkg="com.justrtp.Core"))
        self.tr.run_seed(1)
        self.assertFalse(os.path.exists(os.path.join(self.tdir, bf.FINDING_REPLAYS_FILE)))
        v = self.tr.finish([], [])
        self.assertEqual("PASS", v["verdict"])
        self.assertEqual("JustRTP", v["findings"][0]["attributed_plugin"])
        self.assertFalse(v["findings"][0]["target_attributed"])

    def test_confirmed_finding_shrinks_to_the_triggering_action(self):
        self.plant_when(lambda req: any(x["a"] == "die" for b in req["bots"] for x in b["program"]))
        self.assertTrue(self.tr.baseline(1, "base"))
        plan = {"origin": "cem", "parent": None, "params": None,
                "bots": [{"name": "fzsh_0", "program": [{"a": "rtp"}, {"a": "wait", "ms": 100}]},
                         {"name": "fzsh_1", "program": [{"a": "move", "ms": 100, "dx": 1.0, "dz": 0.0},
                                                          {"a": "die"}, {"a": "rtp"}]}]}
        rec = self.tr.execute(1, 0, plan, None)
        tested = self.tr.check_findings(1, rec, 0)
        self.assertTrue(tested[0]["reproduced"])
        shrunk = self.tr.shrink_findings(1, 1)
        self.assertEqual(1, len(shrunk))
        self.assertTrue(shrunk[0]["reproduced"])
        self.assertEqual([["die"]], shrunk[0]["sequence"])
        self.assertEqual(f"finding:{tested[0]['signature']}", shrunk[0]["count"])
        v = self.tr.finish([], [])
        self.assertEqual([["die"]], v["findings"][0]["shrunk"])

    def test_bad_finding_k_fails_at_startup(self):
        with open(os.path.join(self.dir, "server.properties"), "w", encoding="utf-8") as fh:
            fh.write("rcon.password=x\n")
        with self.assertRaises(SystemExit) as cm:
            bf.main(["--server-dir", self.dir, "--finding-n", "2", "--finding-k", "3"])
        self.assertEqual(2, cm.exception.code)


class CommandTreeAndDenylistTest(unittest.TestCase):
    """Tests for ADR-125 passive command tree merging, structural alias folding,
    redirect cycle protection, minimum-visible-tier metadata, Denylist filtering,
    and commands.json serialization."""

    def test_merge_tiers_alias_folding_and_min_tier_metadata(self):
        default_pkt = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 2, 4]},
                {"index": 1, "kind": "literal", "name": "rtp", "executable": True, "children": [3]},
                {"index": 2, "kind": "literal", "name": "betterrtp:rtp", "executable": False, "redirect": 1, "children": []},
                {
                    "index": 3,
                    "kind": "argument",
                    "name": "world",
                    "parser": "brigadier:string",
                    "properties": {"type": "word"},
                    "executable": True,
                    "children": [],
                },
                {"index": 4, "kind": "literal", "name": "spawn", "executable": True, "children": []},
            ],
        }
        op_pkt = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 2, 5, 7]},
                {"index": 1, "kind": "literal", "name": "rtp", "executable": True, "children": [3, 6]},
                {"index": 2, "kind": "literal", "name": "betterrtp:rtp", "executable": False, "redirect": 1, "children": []},
                {
                    "index": 3,
                    "kind": "argument",
                    "name": "world",
                    "parser": "brigadier:string",
                    "properties": {"type": "word"},
                    "executable": True,
                    "children": [],
                },
                {"index": 5, "kind": "literal", "name": "spawn", "executable": True, "children": []},
                {"index": 6, "kind": "literal", "name": "reload", "executable": True, "children": []},
                {"index": 7, "kind": "literal", "name": "adminonly", "executable": True, "children": []},
            ],
        }

        with tempfile.TemporaryDirectory() as tmpdir:
            out_file = os.path.join(tmpdir, "commands.json")
            tree = bfs.merge_command_trees({"default": default_pkt, "op": op_pkt}, out_path=out_file)

            self.assertEqual(tree.tiers, ["default", "op"])
            self.assertIn("rtp", tree.root_children)
            self.assertNotIn("betterrtp:rtp", tree.root_children)
            self.assertEqual(tree.alias_map["rtp"], "rtp")
            self.assertEqual(tree.alias_map["betterrtp:rtp"], "rtp")

            rtp_node = tree.nodes["rtp"]
            self.assertEqual(rtp_node.aliases, ["rtp", "betterrtp:rtp"])
            self.assertEqual(rtp_node.tiers, ["default", "op"])
            self.assertEqual(rtp_node.min_tier, "default")
            self.assertTrue(rtp_node.executable)
            self.assertFalse(rtp_node.denied)

            world_node = tree.nodes["rtp <world>"]
            self.assertEqual(world_node.tiers, ["default", "op"])
            self.assertEqual(world_node.min_tier, "default")
            self.assertEqual(world_node.aliases, ["rtp <world>", "betterrtp:rtp <world>"])

            reload_node = tree.nodes["rtp reload"]
            self.assertEqual(reload_node.tiers, ["op"])
            self.assertEqual(reload_node.min_tier, "op")
            self.assertEqual(reload_node.aliases, ["rtp reload", "betterrtp:rtp reload"])

            admin_node = tree.nodes["adminonly"]
            self.assertEqual(admin_node.tiers, ["op"])
            self.assertEqual(admin_node.min_tier, "op")

            # Verify commands.json round-trip
            reloaded = bfs.CommandTree.read_json(out_file)
            self.assertEqual(reloaded.to_dict(), tree.to_dict())
            default_allowed = [n.node_id for n in reloaded.allowed_executable_nodes(["default"])]
            self.assertEqual(default_allowed, ["rtp", "rtp <world>", "spawn"])
            all_allowed = [n.node_id for n in reloaded.allowed_executable_nodes(["default", "op"])]
            self.assertEqual(all_allowed, ["adminonly", "rtp", "rtp <world>", "rtp reload", "spawn"])

    def test_max_nodes_truncates_without_dangling_references_and_roundtrips(self):
        packet = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 4]},
                {"index": 1, "kind": "literal", "name": "alpha", "children": [2, 3]},
                {"index": 2, "kind": "literal", "name": "first", "executable": True, "children": []},
                {"index": 3, "kind": "literal", "name": "second", "executable": True, "children": []},
                {"index": 4, "kind": "literal", "name": "beta", "executable": True, "children": []},
            ],
        }

        with tempfile.TemporaryDirectory() as tmpdir:
            out_file = os.path.join(tmpdir, "commands.json")
            tree = bfs.merge_command_trees({"default": packet}, out_path=out_file, max_nodes=2)

            self.assertEqual(len(tree.nodes), 2)
            self.assertEqual(tree.root_children, ["alpha"])
            self.assertEqual(tree.nodes["alpha"].children, ["alpha first"])
            self.assertTrue(all(root in tree.nodes for root in tree.root_children))
            self.assertTrue(all(child in tree.nodes for node in tree.nodes.values() for child in node.children))
            self.assertTrue(tree.truncated)

            reloaded = bfs.CommandTree.read_json(out_file)
            self.assertEqual(reloaded.to_dict(), tree.to_dict())
            self.assertTrue(reloaded.truncated)

    def test_cross_tier_alias_subtree_inequality_does_not_fold(self):
        # Case 1: Within the same tier, `pluginx:tp` and `tp` have different subtrees -> must stay separate.
        pkt_same_tier = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 2]},
                {"index": 1, "kind": "literal", "name": "tp", "executable": False, "children": [3]},
                {"index": 2, "kind": "literal", "name": "pluginx:tp", "executable": True, "children": [4]},
                {
                    "index": 3,
                    "kind": "argument",
                    "name": "target",
                    "parser": "minecraft:entity",
                    "properties": {"single": True},
                    "executable": True,
                    "children": [],
                },
                {
                    "index": 4,
                    "kind": "literal",
                    "name": "home",
                    "executable": True,
                    "children": [],
                },
            ],
        }
        tree1 = bfs.merge_command_trees({"default": pkt_same_tier})
        self.assertIn("tp", tree1.nodes)
        self.assertIn("pluginx:tp", tree1.nodes)
        self.assertEqual(tree1.nodes["tp"].aliases, ["tp"])
        self.assertEqual(tree1.nodes["pluginx:tp"].aliases, ["pluginx:tp"])

        # Case 2: `msg` is in default tier only, `adminmod:msg` is in op tier only (never paired in the same tier).
        # Even if both happen to be bare literals, cross-tier distinct commands without same-tier pairing must not fold.
        default_only_bare = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1]},
                {"index": 1, "kind": "literal", "name": "msg", "executable": True, "children": []},
            ],
        }
        op_only_ns = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1]},
                {"index": 1, "kind": "literal", "name": "adminmod:msg", "executable": True, "children": []},
            ],
        }
        tree2 = bfs.merge_command_trees({"default": default_only_bare, "op": op_only_ns})
        self.assertIn("msg", tree2.nodes)
        self.assertIn("adminmod:msg", tree2.nodes)
        self.assertEqual(tree2.nodes["msg"].min_tier, "default")
        self.assertEqual(tree2.nodes["adminmod:msg"].min_tier, "op")

        # Case 3: Same command in both tiers, but op tier has an argument with a different parser under the same name.
        def_arg = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1]},
                {"index": 1, "kind": "literal", "name": " warp", "executable": False, "children": [2]},
                {
                    "index": 2,
                    "kind": "argument",
                    "name": "dest",
                    "parser": "brigadier:string",
                    "properties": {"type": "word"},
                    "executable": True,
                    "children": [],
                },
            ],
        }
        op_arg = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1]},
                {"index": 1, "kind": "literal", "name": "warp", "executable": False, "children": [2]},
                {
                    "index": 2,
                    "kind": "argument",
                    "name": "dest",
                    "parser": "brigadier:integer",
                    "properties": {"min": 1, "max": 10},
                    "executable": True,
                    "children": [],
                },
            ],
        }
        tree3 = bfs.merge_command_trees({"default": def_arg, "op": op_arg})
        self.assertIn("warp <dest>", tree3.nodes)
        self.assertIn("warp <dest:integer>", tree3.nodes)
        self.assertEqual(tree3.nodes["warp <dest>"].min_tier, "default")
        self.assertEqual(tree3.nodes["warp <dest:integer>"].min_tier, "op")

        # Case 4: `default` has equal `rtp` and `x:rtp`, but `op` has ONLY `x:rtp` with an extra child `nuke`.
        # Folding must be rejected so `rtp` never inherits the undeclared `rtp nuke` subcommand.
        def_pair = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 2]},
                {"index": 1, "kind": "literal", "name": "rtp", "executable": True, "children": []},
                {"index": 2, "kind": "literal", "name": "x:rtp", "executable": True, "children": []},
            ],
        }
        op_ns_conflict = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1]},
                {"index": 1, "kind": "literal", "name": "x:rtp", "executable": True, "children": [2]},
                {"index": 2, "kind": "literal", "name": "nuke", "executable": True, "children": []},
            ],
        }
        tree4 = bfs.merge_command_trees({"default": def_pair, "op": op_ns_conflict})
        self.assertIn("rtp", tree4.nodes)
        self.assertIn("x:rtp", tree4.nodes)
        self.assertIn("x:rtp nuke", tree4.nodes)
        self.assertNotIn("rtp nuke", tree4.nodes)
        self.assertEqual(tree4.nodes["rtp"].tiers, ["default"])
        self.assertEqual(tree4.nodes["x:rtp"].tiers, ["default", "op"])

        # Case 5: `a:rtp` redirects to `rtp` AND also declares its own child `stop`.
        # Redirect with own children is not a pure alias and must not fold into `rtp`.
        redir_with_own_children = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 2]},
                {"index": 1, "kind": "literal", "name": "rtp", "executable": True, "children": []},
                {"index": 2, "kind": "literal", "name": "a:rtp", "executable": False, "redirect": 1, "children": [3]},
                {"index": 3, "kind": "literal", "name": "stop", "executable": True, "children": []},
            ],
        }
        tree5 = bfs.merge_command_trees({"default": redir_with_own_children})
        self.assertIn("rtp", tree5.nodes)
        self.assertIn("a:rtp", tree5.nodes)
        self.assertNotIn("rtp stop", tree5.nodes)
        self.assertEqual(tree5.nodes["rtp"].aliases, ["rtp"])

    def test_redirect_cycle_protection_and_unsafe_redirects(self):
        # Includes:
        # - `execute -> run -> root (0)` cycle
        # - mutual redirect cycle `loop_a <-> loop_b`
        # - self-redirect `self_loop -> self_loop`
        # - child cycle via `children` array (`child_cycle -> sub -> child_cycle`)
        cyclic_pkt = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 3, 4, 5, 6]},
                {"index": 1, "kind": "literal", "name": "execute", "executable": False, "children": [2]},
                {"index": 2, "kind": "literal", "name": "run", "executable": False, "redirect": 0, "children": []},
                {"index": 3, "kind": "literal", "name": "loop_a", "executable": False, "redirect": 4, "children": []},
                {"index": 4, "kind": "literal", "name": "loop_b", "executable": True, "redirect": 3, "children": []},
                {"index": 5, "kind": "literal", "name": "self_loop", "executable": False, "redirect": 5, "children": []},
                {"index": 6, "kind": "literal", "name": "child_cycle", "executable": True, "children": [7]},
                {"index": 7, "kind": "literal", "name": "sub", "executable": True, "children": [6]},
            ],
        }
        tree = bfs.merge_command_trees({"op": cyclic_pkt})
        self.assertIn("execute", tree.nodes)
        self.assertIn("execute run", tree.nodes)
        self.assertTrue(tree.nodes["execute run"].cycle_cut)
        self.assertTrue(tree.nodes["execute"].denied)
        self.assertTrue(tree.nodes["execute run"].denied)

        self.assertIn("loop_a", tree.nodes)
        self.assertTrue(tree.nodes["loop_a"].cycle_cut)
        # `loop_a` is not executable on its own and its redirect `loop_b -> loop_a` is a cycle,
        # so it must not inherit executable=True from a cyclic redirect chain.
        self.assertFalse(tree.nodes["loop_a"].executable)

        self.assertIn("self_loop", tree.nodes)
        self.assertTrue(tree.nodes["self_loop"].cycle_cut)
        self.assertFalse(tree.nodes["self_loop"].executable)

        self.assertIn("child_cycle sub", tree.nodes)
        self.assertTrue(tree.nodes["child_cycle sub"].cycle_cut)

    def test_denylist_default_rules_and_operator_extensions(self):
        pkt = {
            "rootIndex": 0,
            "nodes": [
                {
                    "index": 0,
                    "kind": "root",
                    "name": "",
                    "children": [1, 2, 3, 4, 5, 8, 15, 17, 19],
                },
                {"index": 1, "kind": "literal", "name": "stop", "executable": True, "children": []},
                {"index": 2, "kind": "literal", "name": "minecraft:stop", "executable": False, "redirect": 1, "children": []},
                {"index": 3, "kind": "literal", "name": "ban", "executable": True, "children": []},
                {"index": 4, "kind": "literal", "name": "ban-ip", "executable": True, "children": []},
                {"index": 5, "kind": "literal", "name": "reload", "executable": True, "children": [6, 7]},
                {"index": 6, "kind": "literal", "name": "confirm", "executable": True, "children": []},
                {"index": 7, "kind": "literal", "name": "status", "executable": True, "children": []},
                {"index": 8, "kind": "literal", "name": "tick", "executable": False, "children": [9, 10, 11, 12, 13, 14]},
                {"index": 9, "kind": "literal", "name": "query", "executable": True, "children": []},
                {"index": 10, "kind": "literal", "name": "freeze", "executable": True, "children": []},
                {"index": 11, "kind": "literal", "name": "unfreeze", "executable": True, "children": []},
                {"index": 12, "kind": "literal", "name": "rate", "executable": True, "children": []},
                {"index": 13, "kind": "literal", "name": "sprint", "executable": True, "children": []},
                {"index": 14, "kind": "literal", "name": "step", "executable": True, "children": []},
                {"index": 15, "kind": "literal", "name": "kill", "executable": True, "children": [16]},
                {
                    "index": 16,
                    "kind": "argument",
                    "name": "targets",
                    "parser": "minecraft:entity",
                    "properties": {"single": False},
                    "executable": True,
                    "children": [],
                },
                {"index": 17, "kind": "literal", "name": "customadmin", "executable": True, "children": [18]},
                {"index": 18, "kind": "literal", "name": "wipe", "executable": True, "children": []},
                {"index": 19, "kind": "literal", "name": "minecraft:pardon-ip", "executable": True, "children": []},
            ],
        }
        deny = bfs.Denylist(extra_entries=["customadmin wipe"])
        tree = bfs.merge_command_trees({"op": pkt}, denylist=deny)

        # `minecraft:stop` folds into `stop` and is denied by `stop`
        self.assertEqual(tree.nodes["stop"].aliases, ["stop", "minecraft:stop"])
        self.assertTrue(tree.nodes["stop"].denied)
        self.assertEqual(tree.nodes["stop"].denied_by, "stop")

        # Glob `ban*` and `pardon*` (even when namespaced without bare counterpart)
        self.assertTrue(tree.nodes["ban"].denied)
        self.assertEqual(tree.nodes["ban"].denied_by, "ban*")
        self.assertTrue(tree.nodes["ban-ip"].denied)
        self.assertTrue(tree.nodes["minecraft:pardon-ip"].denied)
        self.assertEqual(tree.nodes["minecraft:pardon-ip"].denied_by, "pardon*")

        # `reload confirm` is denied, but bare `reload` and `reload status` remain allowed
        self.assertFalse(tree.nodes["reload"].denied)
        self.assertFalse(tree.nodes["reload status"].denied)
        self.assertTrue(tree.nodes["reload confirm"].denied)
        self.assertEqual(tree.nodes["reload confirm"].denied_by, "reload confirm")

        # `tick` state-changing subcommands denied; `tick query` allowed
        self.assertFalse(tree.nodes["tick query"].denied)
        for sub in ("freeze", "unfreeze", "rate", "sprint", "step"):
            self.assertTrue(tree.nodes[f"tick {sub}"].denied, f"tick {sub} should be denied")
            self.assertEqual(tree.nodes[f"tick {sub}"].denied_by, f"tick {sub}")

        # `kill <entity>` denies `kill <targets>` (entity parser) while bare `kill` remains allowed
        self.assertFalse(tree.nodes["kill"].denied)
        self.assertTrue(tree.nodes["kill <targets>"].denied)
        self.assertEqual(tree.nodes["kill <targets>"].denied_by, "kill <entity>")

        # Operator `--deny` entry `customadmin wipe` denies `customadmin wipe`, leaving `customadmin` allowed
        self.assertFalse(tree.nodes["customadmin"].denied)
        self.assertTrue(tree.nodes["customadmin wipe"].denied)
        self.assertEqual(tree.nodes["customadmin wipe"].denied_by, "customadmin wipe")

        # Raw command-line checks (used for passive filtering / replay safety)
        self.assertTrue(deny.is_denied_command_line("/minecraft:stop", tree))
        self.assertTrue(deny.is_denied_command_line("kill @a", tree))
        self.assertFalse(deny.is_denied_command_line("kill", tree))
        self.assertTrue(deny.is_denied_command_line("reload confirm", tree))
        self.assertFalse(deny.is_denied_command_line("reload", tree))
        self.assertTrue(deny.is_denied_command_line("tick freeze", tree))
        self.assertFalse(deny.is_denied_command_line("tick query", tree))

    def test_redirect_to_denied_command_propagates_denial_and_command_line_matching(self):
        # 1. `tp -> teleport` with `--deny teleport`
        # 2. non-namespaced alias `slay -> kill` (built-in `kill <entity>`)
        # 3. non-namespaced alias `boot -> kick` and multi-hop `punt -> boot -> kick` (built-in `kick`)
        pkt = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 3, 4, 6, 7, 9]},
                {"index": 1, "kind": "literal", "name": "teleport", "executable": False, "children": [2]},
                {
                    "index": 2,
                    "kind": "argument",
                    "name": "t",
                    "parser": "minecraft:entity",
                    "properties": {"single": True},
                    "executable": True,
                    "children": [],
                },
                {"index": 3, "kind": "literal", "name": "tp", "executable": False, "redirect": 1, "children": []},
                {"index": 4, "kind": "literal", "name": "kill", "executable": True, "children": [5]},
                {
                    "index": 5,
                    "kind": "argument",
                    "name": "targets",
                    "parser": "minecraft:entity",
                    "properties": {"single": False},
                    "executable": True,
                    "children": [],
                },
                {"index": 6, "kind": "literal", "name": "slay", "executable": False, "redirect": 4, "children": []},
                {"index": 7, "kind": "literal", "name": "kick", "executable": False, "children": [8]},
                {
                    "index": 8,
                    "kind": "argument",
                    "name": "player",
                    "parser": "minecraft:entity",
                    "properties": {"single": True},
                    "executable": True,
                    "children": [],
                },
                {"index": 9, "kind": "literal", "name": "boot", "executable": False, "redirect": 7, "children": []},
                {"index": 10, "kind": "literal", "name": "punt", "executable": False, "redirect": 9, "children": []},
            ],
        }
        pkt["nodes"][0]["children"].append(10)
        deny = bfs.Denylist(extra_entries=["teleport"])
        tree = bfs.merge_command_trees({"op": pkt}, denylist=deny)

        # `teleport` and redirecting `tp` + `tp <t>` are all denied by `teleport`
        self.assertTrue(tree.nodes["teleport"].denied)
        self.assertTrue(tree.nodes["teleport <t>"].denied)
        self.assertTrue(tree.nodes["tp"].denied)
        self.assertEqual(tree.nodes["tp"].denied_by, "teleport")
        self.assertTrue(tree.nodes["tp <t>"].denied)
        self.assertEqual(tree.nodes["tp <t>"].denied_by, "teleport")
        self.assertTrue(deny.is_denied_command_line("tp @s", tree))
        self.assertTrue(deny.is_denied_command_line("/tp", tree))

        # `slay -> kill`: bare `slay` is allowed (like bare `kill`), while `slay <targets>` is denied by `kill <entity>`
        self.assertFalse(tree.nodes["slay"].denied)
        self.assertTrue(tree.nodes["slay <targets>"].denied)
        self.assertEqual(tree.nodes["slay <targets>"].denied_by, "kill <entity>")
        self.assertTrue(deny.is_denied_command_line("slay @a", tree))
        self.assertFalse(deny.is_denied_command_line("slay", tree))

        # `boot -> kick` and `punt -> boot -> kick`: denied by built-in `kick`
        for nid in ("kick", "kick <player>", "boot", "boot <player>", "punt", "punt <player>"):
            self.assertTrue(tree.nodes[nid].denied, f"{nid} should be denied via redirect to kick")
            self.assertEqual(tree.nodes[nid].denied_by, "kick")
        self.assertTrue(deny.is_denied_command_line("boot player1", tree))
        self.assertTrue(deny.is_denied_command_line("punt player1", tree))

    def test_json_roundtrip_preserves_steps_and_apply_denylist_recomputes_flags(self):
        pkt = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 3, 4]},
                {"index": 1, "kind": "literal", "name": "rtp", "executable": True, "children": [2]},
                {
                    "index": 2,
                    "kind": "argument",
                    "name": "world",
                    "parser": "brigadier:string",
                    "properties": {"type": "word"},
                    "executable": True,
                    "children": [],
                },
                {"index": 3, "kind": "literal", "name": "teleport", "executable": True, "children": []},
                {"index": 4, "kind": "literal", "name": "tp", "executable": False, "redirect": 3, "children": []},
            ],
        }
        with tempfile.TemporaryDirectory() as tmpdir:
            out_file = os.path.join(tmpdir, "commands.json")
            tree = bfs.merge_command_trees({"default": pkt}, out_path=out_file)
            self.assertFalse(tree.nodes["rtp <world>"].denied)
            self.assertFalse(tree.nodes["teleport"].denied)
            self.assertFalse(tree.nodes["tp"].denied)

            # Reload from commands.json: dataclass equality (including _steps and _root_id) holds
            reloaded = bfs.CommandTree.read_json(out_file)
            self.assertEqual(reloaded, tree)
            self.assertEqual(reloaded.nodes["rtp <world>"]._steps, (("argument", "world", "brigadier:string"),))
            self.assertEqual(reloaded.nodes["rtp <world>"]._root_id, "rtp")

            # Re-apply a stricter denylist on the reloaded tree
            new_deny = bfs.Denylist(extra_entries=["rtp <string>", "teleport"])
            reloaded.apply_denylist(new_deny)
            self.assertFalse(reloaded.nodes["rtp"].denied)
            self.assertTrue(reloaded.nodes["rtp <world>"].denied)
            self.assertEqual(reloaded.nodes["rtp <world>"].denied_by, "rtp <string>")
            self.assertTrue(reloaded.nodes["teleport"].denied)
            self.assertTrue(reloaded.nodes["tp"].denied)
            self.assertEqual(reloaded.nodes["tp"].denied_by, "teleport")

            # Also verify reconstruction when `steps` and `root_id` are absent from serialized dict
            raw_dict = tree.to_dict()
            for n_entry in raw_dict["nodes"]:
                n_entry.pop("steps", None)
                n_entry.pop("root_id", None)
            reconstructed = bfs.CommandTree.from_dict(raw_dict)
            reconstructed.apply_denylist(new_deny)
            self.assertTrue(reconstructed.nodes["rtp <world>"].denied)
            self.assertTrue(reconstructed.nodes["tp"].denied)

    def test_swarm_discover_raises_on_missing_or_uncaptured_or_timeout_graph(self):
        sw = bf.Swarm(["node"])
        # 1. Missing `commands` event
        with unittest.mock.patch.object(
            sw, "run_episode", return_value=([], {"ev": "done", "id": "d1", "reason": "complete"})
        ):
            with self.assertRaisesRegex(bf.SwarmError, "missing commands event"):
                sw.discover("d1", tier="default")

        # 2. `captured: False` with `reason: 'no_commands'`
        with unittest.mock.patch.object(
            sw,
            "run_episode",
            return_value=(
                [
                    {"ev": "bot_error", "text": "no spawn within 2000 ms"},
                    {"ev": "commands", "id": "d2", "tier": "default", "rootIndex": 0, "nodes": [], "captured": False},
                ],
                {"ev": "done", "id": "d2", "reason": "no_commands"},
            ),
        ):
            with self.assertRaisesRegex(bf.SwarmError, "failed to capture command graph.*no_commands"):
                sw.discover("d2", tier="default")

        # 3. `captured: True` but `reason: 'timeout'`
        with unittest.mock.patch.object(
            sw,
            "run_episode",
            return_value=(
                [{"ev": "commands", "id": "d3", "tier": "op", "rootIndex": 0, "nodes": [], "captured": True}],
                {"ev": "done", "id": "d3", "reason": "timeout"},
            ),
        ):
            with self.assertRaisesRegex(bf.SwarmError, "reason='timeout'"):
                sw.discover("d3", tier="op")

    def test_dag_command_graph_resolves_without_exponential_blowup(self):
        # Width-3, depth-16 diamond DAG (shared child indices at each level)
        nodes = [{"index": 0, "kind": "root", "name": "", "children": [1, 2, 3]}]
        idx = 1
        for depth in range(16):
            next_base = idx + 3
            children = [next_base, next_base + 1, next_base + 2] if depth < 15 else []
            for w in range(3):
                nodes.append(
                    {
                        "index": idx + w,
                        "kind": "literal",
                        "name": f"l{depth}_{w}",
                        "executable": depth == 15,
                        "children": list(children),
                    }
                )
            idx += 3
        pkt = {"rootIndex": 0, "nodes": nodes}
        t0 = time.monotonic()
        roots = bfs._resolve_raw_tier_graph(pkt)
        elapsed = time.monotonic() - t0
        self.assertEqual(sorted(roots.keys()), ["l0_0", "l0_1", "l0_2"])
        self.assertLess(elapsed, 1.0)


class JcmdAndStallOracleTest(unittest.TestCase):
    """ADR-125 section 2.4 & 2.6: JcmdObserver PID/container selection, exclusion windows, and StallOracle."""

    SAMPLE_HISTO = (
        " num     #instances         #bytes  class name (module)\n"
        "-------------------------------------------------------\n"
        "   1:         12000        1500000  [B (java.base@21.0.2)\n"
        "   2:           450          36000  jdk.internal.vm.FillerObject (java.base@21.0.2)\n"
        "   3:           300          24000  jdk.internal.vm.FillerElement (java.base@21.0.2)\n"
        "   4:            42           3360  com.example.alpha.SessionState\n"
        "   5:            10            800  [Lcom.example.alpha.SessionState;\n"
        "Total         12802        1564160\n"
    )

    SAMPLE_THREAD_DUMP = (
        "2026-10-24 01:00:00\n"
        "Full thread dump OpenJDK 64-Bit Server VM (21.0.2+13-LTS mixed mode):\n"
        "\n"
        '"Netty Epoll Server IO #0" #25 daemon prio=5 os_prio=0 tid=0x00007f01 nid=0x101 runnable\n'
        "   java.lang.Thread.State: RUNNABLE\n"
        "\tat io.netty.channel.epoll.Native.epollWait(Native Method)\n"
        "\n"
        '"Server thread" #30 prio=5 os_prio=0 tid=0x00007f02 nid=0x102 runnable\n'
        "   java.lang.Thread.State: RUNNABLE\n"
        "\tat java.base@21.0.2/java.lang.Thread.onSpinWait(Thread.java:310)\n"
        "\tat com.example.alpha.ScanTask.runBusyScan(ScanTask.java:88)\n"
        "\tat net.minecraft.server.MinecraftServer.tickChildren(MinecraftServer.java:1020)\n"
        "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:910)\n"
        "\tat net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:700)\n"
    )

    def test_jcmd_observer_pid_file_and_histogram_parsing_and_window_recording(self):
        clock_vals = [1000, 1400, 3000, 3120]
        calls = []

        def fake_clock():
            return clock_vals.pop(0) if clock_vals else 5000

        def fake_runner(cmd, timeout_s):
            calls.append((cmd, timeout_s))
            if cmd[-1] == "GC.class_histogram":
                return subprocess.CompletedProcess(cmd, 0, stdout=self.SAMPLE_HISTO, stderr="")
            if cmd[-1] == "Thread.print":
                return subprocess.CompletedProcess(cmd, 0, stdout=self.SAMPLE_THREAD_DUMP, stderr="")
            return subprocess.CompletedProcess(cmd, 1, stdout="", stderr="unexpected")

        with tempfile.TemporaryDirectory() as tmpdir:
            pid_path = os.path.join(tmpdir, "server.pid")
            with open(pid_path, "w", encoding="utf-8") as fh:
                fh.write(f"{os.getpid()}\n")

            obs = bfs.JcmdObserver(
                pid_file=pid_path,
                post_grace_ms=200,
                runner=fake_runner,
                clock_ms=fake_clock,
            )
            self.assertEqual(obs.resolve_pid(), os.getpid())

            histo = obs.class_histogram()
            # FillerObject and FillerElement are filtered; raw array `[Lcom.example.alpha.SessionState;` stays distinct
            self.assertNotIn("jdk.internal.vm.FillerObject", histo)
            self.assertNotIn("jdk.internal.vm.FillerElement", histo)
            self.assertEqual(histo["com.example.alpha.SessionState"], (42, 3360))
            self.assertEqual(histo["[Lcom.example.alpha.SessionState;"], (10, 800))
            self.assertEqual(histo["[B"], (12000, 1500000))

            dump = obs.thread_print()
            self.assertIn("com.example.alpha.ScanTask.runBusyScan", dump)

            # Exclusion windows recorded for both GC.class_histogram and Thread.print (+200ms post_grace_ms)
            self.assertEqual(len(obs.exclusion_windows), 2)
            self.assertEqual(obs.exclusion_windows[0], bfs.ExclusionWindow(1000, 1600, "GC.class_histogram"))
            self.assertEqual(obs.exclusion_windows[1], bfs.ExclusionWindow(3000, 3320, "Thread.print"))
            self.assertTrue(obs.overlaps_exclusion(1500, 1550))
            self.assertFalse(obs.overlaps_exclusion(2000, 2500))

            # Stale --pid-file pointing to a process whose cwd does not match server_dir is rejected
            stale_obs = bfs.JcmdObserver(
                pid_file=pid_path,
                server_dir="/srv/bench/expected-server",
                pid_cwd=lambda p: "/tmp/unrelated-cwd",
                runner=fake_runner,
            )
            with self.assertRaisesRegex(bfs.JcmdError, "does not match server_dir.*stale"):
                stale_obs.resolve_pid()

    def test_jcmd_observer_jcmd_list_matching_and_container_wrapping(self):
        jcmd_list_out = (
            "1011 jdk.jcmd/sun.tools.jcmd.JCmd -l\n"
            "2048 /opt/other/app.jar\n"
            "4096 /srv/bench/paper-server/paper.jar nogui\n"
        )
        recorded_cmds = []

        def fake_runner(cmd, timeout_s):
            recorded_cmds.append(cmd)
            if cmd == ["jcmd", "-l"]:
                return subprocess.CompletedProcess(cmd, 0, stdout=jcmd_list_out, stderr="")
            return subprocess.CompletedProcess(cmd, 0, stdout=self.SAMPLE_HISTO, stderr="")

        obs = bfs.JcmdObserver(
            server_dir="/srv/bench/paper-server",
            check_local_pid=False,
            pid_cwd=lambda p: None,
            runner=fake_runner,
        )
        self.assertEqual(obs.resolve_pid(), 4096)
        counts = obs.class_counts()
        self.assertEqual(counts["com.example.alpha.SessionState"], 42)
        self.assertEqual(counts["[Lcom.example.alpha.SessionState;"], 10)
        self.assertEqual(recorded_cmds[-1], ["jcmd", "4096", "GC.class_histogram"])

        # Basename-only collision test: server_dir='/srv/bench/paper' must NEVER match unrelated
        # `5000 /home/u/tools/paper-utils.jar`, and matches `4096 server.jar nogui` only when cwd matches.
        collision_list = (
            "4096 server.jar nogui\n"
            "5000 /home/u/tools/paper-utils.jar\n"
        )
        obs_no_cwd = bfs.JcmdObserver(
            server_dir="/srv/bench/paper",
            check_local_pid=False,
            pid_cwd=lambda p: "/home/u/tools" if p == 5000 else None,
            runner=lambda cmd, t: subprocess.CompletedProcess(cmd, 0, stdout=collision_list, stderr=""),
        )
        with self.assertRaisesRegex(bfs.JcmdError, "found no JVM matching"):
            obs_no_cwd.resolve_pid()

        obs_with_cwd = bfs.JcmdObserver(
            server_dir="/srv/bench/paper",
            check_local_pid=False,
            pid_cwd=lambda p: "/srv/bench/paper" if p == 4096 else "/home/u/tools",
            runner=lambda cmd, t: subprocess.CompletedProcess(cmd, 0, stdout=collision_list, stderr=""),
        )
        self.assertEqual(obs_with_cwd.resolve_pid(), 4096)

        # Container mode resolves child JVM PID via `docker exec <container> jcmd -l` (e.g. itzg/minecraft-server)
        container_cmds = []

        def container_runner(cmd, timeout_s):
            container_cmds.append(cmd)
            if cmd[-2:] == ["jcmd", "-l"]:
                return subprocess.CompletedProcess(cmd, 0, stdout="110 jdk.jcmd/sun.tools.jcmd.JCmd -l\n47 paper.jar nogui\n", stderr="")
            return subprocess.CompletedProcess(cmd, 0, stdout=self.SAMPLE_HISTO, stderr="")

        c_obs = bfs.JcmdObserver(container="mc-fabric-1", container_user="1000", runner=container_runner)
        c_obs.class_histogram()
        self.assertEqual(
            container_cmds,
            [
                ["docker", "exec", "-u", "1000", "mc-fabric-1", "jcmd", "-l"],
                ["docker", "exec", "-u", "1000", "mc-fabric-1", "jcmd", "47", "GC.class_histogram"],
            ],
        )

        # Container mode with host `server_dir` and `jar_pattern` both set ignores the host path
        # inside the container namespace and matches the in-container JVM by `jar_pattern`.
        multi_container_jcmd = (
            "110 jdk.jcmd/sun.tools.jcmd.JCmd -l\n"
            "7 /data/paper-1.21.jar nogui\n"
            "19 /opt/metrics/agent.jar\n"
        )
        c_both = bfs.JcmdObserver(
            container="mc-paper",
            server_dir="/home/u/RTP/devstack/data/paper",
            jar_pattern="paper",
            runner=lambda cmd, t: subprocess.CompletedProcess(
                cmd, 0, stdout=multi_container_jcmd if cmd[-2:] == ["jcmd", "-l"] else self.SAMPLE_HISTO, stderr=""
            ),
        )
        self.assertEqual(c_both.resolve_pid(), 7)

        # Container mode falls back to PID 1 when `jcmd -l` lists no candidate rows
        fallback_cmds = []
        c_fallback = bfs.JcmdObserver(
            container="mc-minimal",
            runner=lambda cmd, t: (
                fallback_cmds.append(cmd) or subprocess.CompletedProcess(cmd, 0, stdout=self.SAMPLE_HISTO, stderr="")
            ),
        )
        c_fallback.class_histogram()
        self.assertEqual(
            fallback_cmds,
            [
                ["docker", "exec", "mc-minimal", "jcmd", "-l"],
                ["docker", "exec", "mc-minimal", "jcmd", "1", "GC.class_histogram"],
            ],
        )

        # Invalid container name starting with `-` is rejected immediately
        with self.assertRaisesRegex(bfs.JcmdError, "invalid container name"):
            bfs.JcmdObserver(container="--privileged")

    def test_jcmd_observer_actionable_errors_and_read_only_enforcement(self):
        # 1. Missing binary raises JcmdError with hint
        def missing_bin_runner(cmd, timeout_s):
            raise FileNotFoundError("jcmd")

        obs_missing = bfs.JcmdObserver(pid=1234, check_local_pid=False, runner=missing_bin_runner)
        with self.assertRaisesRegex(bfs.JcmdError, "not found.*Hint"):
            obs_missing.class_histogram()
        # Even when jcmd fails, the exclusion window is recorded
        self.assertEqual(len(obs_missing.exclusion_windows), 1)

        # 2. Empty or unparseable histogram raises JcmdError (never returns `{}`)
        obs_empty = bfs.JcmdObserver(
            pid=1234,
            check_local_pid=False,
            runner=lambda cmd, t: subprocess.CompletedProcess(cmd, 0, stdout="1234:\nCommand executed successfully\n", stderr=""),
        )
        with self.assertRaisesRegex(bfs.JcmdError, "no readable class rows"):
            obs_empty.class_histogram()

        # 3. Permission / wrong user attach failure raises JcmdError with user hint
        obs_perm = bfs.JcmdObserver(
            pid=1234,
            check_local_pid=False,
            runner=lambda cmd, t: subprocess.CompletedProcess(
                cmd, 1, stdout="", stderr="com.sun.tools.attach.AttachNotSupportedException: Unable to open socket file"
            ),
        )
        with self.assertRaisesRegex(bfs.JcmdError, "Unable to open socket file.*Hint"):
            obs_perm.thread_print()

        # 4. Ambiguous `jcmd -l` raises JcmdError listing visible JVMs
        ambig_out = "2001 /srv/a/paper.jar\n2002 /srv/b/paper.jar\n"
        obs_ambig = bfs.JcmdObserver(
            jar_pattern="paper.jar",
            check_local_pid=False,
            runner=lambda cmd, t: subprocess.CompletedProcess(cmd, 0, stdout=ambig_out, stderr=""),
        )
        with self.assertRaisesRegex(bfs.JcmdError, "matched multiple JVMs.*--pid-file"):
            obs_ambig.resolve_pid()

        # 5. Disallowed jcmd operation is rejected
        with self.assertRaisesRegex(bfs.JcmdError, "disallowed jcmd operation"):
            obs_empty._build_command("VM.system_properties")

        # 6. Local PID ownership check surfaces PermissionError and ProcessLookupError with hints
        with unittest.mock.patch("os.kill", side_effect=PermissionError("Operation not permitted")):
            obs_wrong_user = bfs.JcmdObserver(pid=9999, check_local_pid=True)
            with self.assertRaisesRegex(bfs.JcmdError, "permission denied signalling JVM PID 9999.*same OS user"):
                obs_wrong_user.resolve_pid()

        with unittest.mock.patch("os.kill", side_effect=ProcessLookupError("No such process")):
            obs_dead = bfs.JcmdObserver(pid=9998, check_local_pid=True)
            with self.assertRaisesRegex(bfs.JcmdError, "JVM process PID 9998.*is not running"):
                obs_dead.resolve_pid()

    def test_stall_oracle_excludes_observer_windows_and_detects_acute_and_sustained_stalls(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            plugins_dir = os.path.join(tmpdir, "plugins")
            os.makedirs(plugins_dir)
            jar_path = os.path.join(plugins_dir, "AlphaPlugin.jar")
            with zipfile.ZipFile(jar_path, "w") as zf:
                zf.writestr("plugin.yml", "name: AlphaPlugin\nmain: com.example.alpha.AlphaMain\n")
                zf.writestr("com/example/alpha/ScanTask.class", b"\xca\xfe\xba\xbe")

            jar_index = bfs.JarIndex(plugins_dir)
            profile = bfs.get_platform_profile("paper")

            # Baseline tick gaps: steady ~1000ms per 20 ticks
            baseline_gaps = [
                {"ms": 995.0, "end_ms": 1000 * i} if i % 2 == 0 else {"ms": 1005.0, "end_ms": 1000 * i}
                for i in range(1, 25)
            ]

            clock_vals = [10000, 10500]
            obs = bfs.JcmdObserver(
                pid=1234,
                check_local_pid=False,
                post_grace_ms=200,
                clock_ms=lambda: clock_vals.pop(0) if clock_vals else 20000,
                runner=lambda cmd, t: subprocess.CompletedProcess(cmd, 0, stdout=self.SAMPLE_HISTO, stderr=""),
            )
            # Trigger a GC.class_histogram that records exclusion window [10000, 10700]
            obs.class_histogram()

            oracle = bfs.StallOracle(
                baseline_gaps=baseline_gaps,
                stall_ms=2000.0,
                profile=profile,
                jar_index=jar_index,
                observer=obs,
            )

            # Case 1: Real bench-swarm.js `tick_gap` event shape (`at` epoch ms) overlapping [10000, 10700]
            # and an untimed gap (when exclusion windows exist) both fail closed into `excluded` -> no stall
            gc_overlapping_gaps = [
                {"ev": "tick_gap", "bot": "fz_0", "ms": 1000.0, "at": 9000, "keepalive_ms": 980, "t": 500},
                {"ev": "tick_gap", "bot": "fz_0", "ms": 3500.0, "at": 11000, "keepalive_ms": 1000, "t": 2500},  # [7500, 11000] overlaps [10000, 10700]
                {"ev": "tick_gap", "bot": "fz_0", "ms": 4000.0},  # untimed gap rejected when exclusion windows exist
                {"ev": "tick_gap", "bot": "fz_0", "ms": 1002.0, "at": 14002, "keepalive_ms": 990, "t": 5502},
            ]
            res_gc = oracle.evaluate_episode(gc_overlapping_gaps, follow_up="quit_lt1s")
            self.assertFalse(res_gc["stalled"])
            self.assertEqual(res_gc["summary"]["excluded_count"], 2)
            self.assertEqual(res_gc["summary"]["unverifiable_count"], 1)
            self.assertEqual(res_gc["summary"]["count"], 2)

            # Case 2: Acute single-gap stall (3300ms > ~1005 + 2000ms) outside observer windows, attributed via Thread.print
            acute_gaps = [
                {"ev": "tick_gap", "bot": "fz_0", "ms": 1000.0, "at": 20000, "t": 1000},
                {"ev": "tick_gap", "bot": "fz_0", "ms": 3300.0, "at": 23300, "t": 4300},
                {"ev": "tick_gap", "bot": "fz_0", "ms": 1000.0, "at": 24300, "t": 5300},
            ]
            res_acute = oracle.evaluate_episode(
                acute_gaps,
                follow_up="quit_lt1s",
                thread_dumps=[self.SAMPLE_THREAD_DUMP],
            )
            self.assertTrue(res_acute["stalled"])
            self.assertTrue(res_acute["acute"])
            self.assertFalse(res_acute["sustained"])
            self.assertEqual(res_acute["kind"], "acute")
            self.assertEqual(res_acute["culprit_frame"], "com.example.alpha.ScanTask.runBusyScan")
            self.assertEqual(res_acute["first_suspect"], "AlphaPlugin.jar")
            self.assertEqual(res_acute["attribution_status"], "unconfirmed")

            # Verify "Server thread" takes priority over an earlier "main" thread, and Kotlin mangled frame names parse cleanly
            main_before_server_dump = (
                '"main" #1 prio=5 runnable\n'
                "\tat java.base/java.lang.Object.wait(Native Method)\n"
                "\n"
                '"Server thread" #30 prio=5 runnable\n'
                "\tat knot//com.example.alpha.ScanTask.runBusyScan-7a8b9c$lambda$2(ScanTask.kt:88)\n"
            )
            parsed_kt = bfs.parse_main_thread_frame(main_before_server_dump, profile=profile, jar_index=jar_index)
            self.assertEqual(parsed_kt["thread_name"], "Server thread")
            self.assertEqual(parsed_kt["culprit_frame"], "com.example.alpha.ScanTask.runBusyScan-7a8b9c$lambda$2")
            self.assertEqual(parsed_kt["first_suspect"], "AlphaPlugin.jar")

            # Case 3: Sustained tick inflation during `repeat` (e.g. 1150ms per 20 ticks over 8 intervals:
            # never crosses 3005ms acute threshold, but inflates mean by ~150ms and p95 by ~155ms)
            sustained_gaps = [{"ms": 1150.0, "end_ms": 30000 + i * 1150} for i in range(1, 9)]
            res_sustained = oracle.evaluate_episode(
                sustained_gaps,
                follow_up="repeat",
                thread_dumps=[self.SAMPLE_THREAD_DUMP],
            )
            self.assertTrue(res_sustained["stalled"])
            self.assertFalse(res_sustained["acute"])
            self.assertTrue(res_sustained["sustained"])
            self.assertEqual(res_sustained["kind"], "sustained")
            self.assertEqual(res_sustained["first_suspect"], "AlphaPlugin.jar")

            # Case 4: Stall with only JDK/Minecraft server frames stays attributed to `server`
            vanilla_only_dump = (
                '"Server thread" #30 prio=5 runnable\n'
                "\tat java.base/java.util.HashMap.getNode(HashMap.java:560)\n"
                "\tat net.minecraft.server.level.ServerChunkCache.tick(ServerChunkCache.java:400)\n"
                "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:910)\n"
            )
            res_server = oracle.evaluate_episode(
                acute_gaps,
                follow_up="quit_lt1s",
                thread_dumps=[vanilla_only_dump],
            )
            self.assertTrue(res_server["stalled"])
            self.assertIsNone(res_server["culprit_frame"])
            self.assertEqual(res_server["first_suspect"], "server")


class JarIndexAndLeakScreenTest(unittest.TestCase):
    """ADR-125 section 2.4 & 2.6: JarIndex class/dependency scanning and Bonferroni leak screen."""

    def test_jar_index_class_ownership_and_dependency_closure_across_paper_fabric_neoforge(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            # 1. Paper plugins directory: CoreLib -> MidPlugin -> LeafPlugin, plus SharedLib in two jars
            plugins_dir = os.path.join(tmpdir, "plugins")
            os.makedirs(plugins_dir)

            with zipfile.ZipFile(os.path.join(plugins_dir, "CoreLib-1.0.jar"), "w") as zf:
                zf.writestr("plugin.yml", "name: CoreLib\nprovides: [CoreAPI]\n")
                zf.writestr("com/example/core/CoreService.class", b"\xca\xfe")
                zf.writestr("META-INF/versions/21/com/example/core/MultiReleaseHelper.class", b"\xca\xfe")
                zf.writestr("META-INF/versions/21/module-info.class", b"\xca\xfe")
                zf.writestr("com/example/shared/ShadedUtil.class", b"\xca\xfe")

            with zipfile.ZipFile(os.path.join(plugins_dir, "MidPlugin-2.0.jar"), "w") as zf:
                zf.writestr(
                    "paper-plugin.yml",
                    "name: MidPlugin\n"
                    "dependencies:\n"
                    "  server:\n"
                    "    CoreAPI:\n"
                    "      required: true\n"
                    "    OptionalHook:\n"
                    "      required: false\n",
                )
                zf.writestr("com/example/mid/MidManager.class", b"\xca\xfe")
                zf.writestr("com/example/shared/ShadedUtil.class", b"\xca\xfe")

            with zipfile.ZipFile(os.path.join(plugins_dir, "LeafPlugin-3.0.jar"), "w") as zf:
                # Same-indent YAML list items (`depend:\n- MidPlugin`) are very common in Bukkit plugin.yml
                zf.writestr(
                    "plugin.yml",
                    "name: LeafPlugin\n"
                    "depend:\n"
                    "- MidPlugin\n"
                    "softdepend:\n"
                    "- SomeSoftPlugin\n",
                )
                zf.writestr("com/example/leaf/LeafSession.class", b"\xca\xfe")

            p_idx = bfs.JarIndex(plugins_dir)
            self.assertEqual(p_idx.owner_of_class("com.example.core.CoreService"), "CoreLib-1.0.jar")
            self.assertEqual(p_idx.owner_of_class("[Lcom.example.core.CoreService;"), "CoreLib-1.0.jar")
            self.assertEqual(p_idx.owner_of_class("com.example.core.MultiReleaseHelper"), "CoreLib-1.0.jar")
            # Inner / lambda class resolves via outer host class
            self.assertEqual(
                p_idx.owner_of_class("com.example.leaf.LeafSession$PendingTask$$Lambda$42/0x000001"),
                "LeafPlugin-3.0.jar",
            )
            # Class shipped by multiple jars is marked `ambiguous` (never arbitrarily blamed on one jar)
            self.assertEqual(p_idx.owner_of_class("com.example.shared.ShadedUtil"), "ambiguous")
            self.assertEqual(
                p_idx.jars_for_class("com.example.shared.ShadedUtil"),
                ["CoreLib-1.0.jar", "MidPlugin-2.0.jar"],
            )
            # Unknown / JDK / vanilla server class defaults to `server`
            self.assertEqual(p_idx.owner_of_class("net.minecraft.advancements.AdvancementProgress"), "server")
            # Transitive hard dependency closure: LeafPlugin -> MidPlugin -> CoreLib (via `provides: [CoreAPI]`)
            self.assertEqual(
                p_idx.close_deps(["LeafPlugin-3.0.jar"]),
                ["CoreLib-1.0.jar", "LeafPlugin-3.0.jar", "MidPlugin-2.0.jar"],
            )

            # 2. Fabric mods directory (including nested `META-INF/jars/*.jar` Jar-in-Jar)
            fabric_dir = os.path.join(tmpdir, "fabric_mods")
            os.makedirs(fabric_dir)
            nested_fabric_buf = io.BytesIO()
            with zipfile.ZipFile(nested_fabric_buf, "w") as nzf:
                nzf.writestr(
                    "fabric.mod.json",
                    json.dumps(
                        {
                            "schemaVersion": 1,
                            "id": "bundled-sublib",
                            "provides": ["bundled-subapi"],
                        }
                    ),
                )
                nzf.writestr("net/example/bundled/BundledHelper.class", b"\xca\xfe")

            with zipfile.ZipFile(os.path.join(fabric_dir, "base-lib.jar"), "w") as zf:
                zf.writestr(
                    "fabric.mod.json",
                    json.dumps(
                        {
                            "schemaVersion": 1,
                            "id": "baselib",
                            "provides": ["baselib-api"],
                            "depends": {"fabricloader": ">=0.15.0", "minecraft": "~1.21"},
                            "jars": [{"file": "META-INF/jars/bundled-sublib-1.0.jar"}],
                        }
                    ),
                )
                zf.writestr("net/example/baselib/BaseMod.class", b"\xca\xfe")
                zf.writestr("META-INF/jars/bundled-sublib-1.0.jar", nested_fabric_buf.getvalue())

            with zipfile.ZipFile(os.path.join(fabric_dir, "addon-mod.jar"), "w") as zf:
                zf.writestr(
                    "fabric.mod.json",
                    json.dumps(
                        {
                            "schemaVersion": 1,
                            "id": "addonmod",
                            "depends": {"fabricloader": ">=0.15.0", "bundled-subapi": "*"},
                            "recommends": {"optionalmod": "*"},
                        }
                    ),
                )
                zf.writestr("net/example/addon/AddonMod.class", b"\xca\xfe")

            f_idx = bfs.JarIndex(fabric_dir)
            self.assertEqual(f_idx.owner_of_class("net.example.addon.AddonMod"), "addon-mod.jar")
            # Nested jar class is credited to the outer `base-lib.jar`
            self.assertEqual(f_idx.owner_of_class("net.example.bundled.BundledHelper"), "base-lib.jar")
            # Dependency on `bundled-subapi` (provided by nested jar inside `base-lib.jar`) resolves to `base-lib.jar`
            self.assertEqual(f_idx.close_deps(["addon-mod.jar"]), ["addon-mod.jar", "base-lib.jar"])

            # 3. NeoForge mods directory (`META-INF/neoforge.mods.toml` + `META-INF/jarjar/*.jar`)
            neo_dir = os.path.join(tmpdir, "neo_mods")
            os.makedirs(neo_dir)
            nested_neo_buf = io.BytesIO()
            with zipfile.ZipFile(nested_neo_buf, "w") as nzf:
                nzf.writestr(
                    "META-INF/neoforge.mods.toml",
                    'modLoader="javafml"\n'
                    "[[mods]]\n"
                    'modId="neoshaded"\n',
                )
                nzf.writestr("net/example/neoshaded/ShadedCodec.class", b"\xca\xfe")

            with zipfile.ZipFile(os.path.join(neo_dir, "neocore.jar"), "w") as zf:
                zf.writestr(
                    "META-INF/neoforge.mods.toml",
                    'modLoader="javafml"\n'
                    "[[mods]]\n"
                    'modId="neocore"\n',
                )
                zf.writestr("META-INF/jarjar/neoshaded-1.0.jar", nested_neo_buf.getvalue())
            with zipfile.ZipFile(os.path.join(neo_dir, "neoaddon.jar"), "w") as zf:
                zf.writestr(
                    "META-INF/neoforge.mods.toml",
                    'modLoader="javafml"\n'
                    "[[mods]]\n"
                    'modId="neoaddon"\n'
                    "[[dependencies.neoaddon]]\n"
                    'modId="neoforge"\n'
                    'type="required"\n'
                    "[[dependencies.neoaddon]]\n"
                    'modId="neoshaded"\n'
                    'type="required"\n'
                    "[[dependencies.neoaddon]]\n"
                    'modId="optmod"\n'
                    'type="optional"\n',
                )
            n_idx = bfs.JarIndex(neo_dir)
            self.assertEqual(n_idx.owner_of_class("net.example.neoshaded.ShadedCodec"), "neocore.jar")
            self.assertEqual(n_idx.close_deps(["neoaddon.jar"]), ["neoaddon.jar", "neocore.jar"])

    def test_bonferroni_server_leak_screen_and_passive_histogram_summary(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            plugins_dir = os.path.join(tmpdir, "plugins")
            os.makedirs(plugins_dir)
            with zipfile.ZipFile(os.path.join(plugins_dir, "QueuePlugin.jar"), "w") as zf:
                zf.writestr("plugin.yml", "name: QueuePlugin\n")
                zf.writestr("com/example/queue/RetainedSession.class", b"\xca\xfe")
                zf.writestr("com/example/queue/AmbientDriftCache.class", b"\xca\xfe")

            jar_index = bfs.JarIndex(plugins_dir)

            # Vanilla baseline records: `net.minecraft.world.ChunkHolder` grows by ~50 per episode in vanilla,
            # so exploration growth of ~50 must be subtracted and NOT flagged.
            baseline_records = []
            for ep in range(8):
                baseline_records.append(
                    {
                        "ep": ep,
                        "node": "help",
                        "follow_up": "repeat",
                        "departures": 1,
                        "deltas": {
                            "cls:net.minecraft.world.ChunkHolder": 50.0 + (1.0 if ep % 2 == 0 else -1.0),
                            "cls:java.util.HashMap$Node": 2.0 if ep % 2 == 0 else -2.0,
                        },
                    }
                )

            # Exploration records across 48 episodes:
            # - 12 episodes run `queue` with `quit_lt1s` -> leaks +200 `com.example.queue.RetainedSession` each
            # - 36 episodes run `help` with `repeat` -> 0 `RetainedSession` growth
            # - `com.example.queue.AmbientDriftCache` (plugin class absent from baseline) grows by ~20 in EVERY episode
            # - `net.minecraft.world.ChunkHolder` grows by ~50 in all episodes (same as baseline)
            # - 15 noisy background classes fluctuate around 0
            records = []
            noise_rng = random.Random(3)
            for ep in range(48):
                is_leak_arm = ep % 4 == 0
                deltas = {
                    "cls:net.minecraft.world.ChunkHolder": 50.0 + (1.0 if ep % 3 == 0 else -1.0),
                    "cls:com.example.queue.AmbientDriftCache": 20.0 + noise_rng.uniform(-0.5, 0.5),
                    "cls:com.example.queue.RetainedSession": 200.0 if is_leak_arm else 0.0,
                }
                for n_idx in range(15):
                    deltas[f"cls:com.example.noise.NoiseClass{n_idx}"] = noise_rng.gauss(0.0, 1.0)
                records.append(
                    {
                        "ep": ep,
                        "node": "queue" if is_leak_arm else "help",
                        "follow_up": "quit_lt1s" if is_leak_arm else "repeat",
                        "departures": 1 if is_leak_arm else 0,
                        "deltas": deltas,
                    }
                )

            screen = bfs.screen_server_leaks(
                records=records,
                baseline_records=baseline_records,
                jar_index=jar_index,
                profile=bfs.get_platform_profile("paper"),
            )
            self.assertEqual(screen["usable_episodes"], 48)
            self.assertGreater(screen["total_tests"], 30)
            self.assertGreater(screen["bonferroni_z"], 3.0)

            flagged = screen["correlations"]
            self.assertTrue(flagged, "expected RetainedSession correlation above Bonferroni threshold")
            top = flagged[0]
            self.assertEqual(top["cls"], "com.example.queue.RetainedSession")
            self.assertEqual(top["feature"], "arm:queue:quit_lt1s")
            self.assertAlmostEqual(top["slope"], 200.0, places=2)
            self.assertEqual(top["first_suspect"], "QueuePlugin.jar")
            # Passive correlation never claims confirmed exploit attribution
            self.assertEqual(top["attribution_status"], "unconfirmed_passive_correlation")

            # 6 leak episodes out of 12 is only 1-in-924 by chance, above the Bonferroni level for this many
            # (class, feature) cells: the screen must report insufficient evidence, not a leak.
            small_records = [dict(r, deltas=dict(r["deltas"])) for r in records[:12]]
            for ep, r in enumerate(small_records):
                leak = ep % 2 == 0
                r["node"] = "queue" if leak else "help"
                r["follow_up"] = "quit_lt1s" if leak else "repeat"
                r["departures"] = 1 if leak else 0
                r["deltas"]["cls:com.example.queue.RetainedSession"] = 200.0 if leak else 0.0
            small = bfs.screen_server_leaks(records=small_records, baseline_records=baseline_records)
            self.assertGreaterEqual(small["features_tested"], 4)
            self.assertEqual(small["correlations"], [])

            # Negative assertions:
            # 1. Vanilla baseline class `ChunkHolder` is subtracted and never flagged
            # 2. Plugin ambient-drift class `AmbientDriftCache` (growing in every episode) is absorbed by intercept and not flagged
            # 3. Noise classes are never flagged
            # 4. `RetainedSession` is NEVER flagged on `node:help` or `arm:help:repeat`
            flagged_classes = {c["cls"] for c in flagged}
            self.assertEqual(flagged_classes, {"com.example.queue.RetainedSession"})
            flagged_features = {c["feature"] for c in flagged}
            self.assertNotIn("node:help", flagged_features)
            self.assertNotIn("arm:help:repeat", flagged_features)

            # 200-episode ambient-drift probe over 5 nodes: a plugin class growing ~20 in every episode
            # must have all 10 node/arm features absorbed by the intercept (0 features flagged).
            ambient_records = []
            for ep in range(200):
                node_idx = ep % 5
                ambient_records.append(
                    {
                        "ep": ep,
                        "node": f"cmd{node_idx}",
                        "follow_up": "repeat",
                        "departures": 0,
                        "deltas": {
                            "cls:com.example.queue.AmbientDriftCache": 20.0 + (0.5 if ep % 2 == 0 else -0.5),
                        },
                    }
                )
            ambient_screen = bfs.screen_server_leaks(
                records=ambient_records,
                baseline_records=[],
                jar_index=jar_index,
                profile=bfs.get_platform_profile("paper"),
            )
            self.assertEqual(ambient_screen["correlations"], [])

            # Correlated multi-arm episode probe: when `queue` and `help` run together in 6 episodes (+200),
            # `help` runs alone in 6 episodes (0), and `list` runs alone in 6 episodes (0),
            # joint ridge fitting blames `queue` and does NOT flag `help` or `list`.
            corr_records = []
            for ep in range(18):
                if ep < 6:
                    arms = [{"node": "queue", "follow_up": "quit_lt1s"}, {"node": "help", "follow_up": "repeat"}]
                    leak_val = 200.0
                elif ep < 12:
                    arms = [{"node": "help", "follow_up": "repeat"}]
                    leak_val = 0.0
                else:
                    arms = [{"node": "list", "follow_up": "repeat"}]
                    leak_val = 0.0
                corr_records.append(
                    {
                        "ep": ep,
                        "arms": arms,
                        "departures": 1 if ep < 6 else 0,
                        "deltas": {"cls:com.example.queue.RetainedSession": leak_val},
                    }
                )
            corr_screen = bfs.screen_server_leaks(
                records=corr_records,
                baseline_records=[],
                jar_index=jar_index,
                profile=bfs.get_platform_profile("paper"),
            )
            corr_feats = {c["feature"] for c in corr_screen["correlations"]}
            self.assertIn("arm:queue:quit_lt1s", corr_feats)
            self.assertIn("node:queue", corr_feats)
            self.assertNotIn("arm:help:repeat", corr_feats)
            self.assertNotIn("node:help", corr_feats)
            self.assertNotIn("arm:list:repeat", corr_feats)
            self.assertNotIn("node:list", corr_feats)

            # Passive snapshot summary test
            snapshots = [
                {
                    "epoch_ms": 0,
                    "histogram": {
                        "com.example.queue.RetainedSession": (10, 800),
                        "jdk.internal.vm.FillerObject": (100, 8000),
                    },
                },
                {
                    "epoch_ms": 1800_000,  # 0.5 hours later
                    "histogram": {
                        "com.example.queue.RetainedSession": (210, 16800),
                        "jdk.internal.vm.FillerObject": (500, 40000),
                    },
                },
            ]
            snap_summary = bfs.summarize_histogram_snapshots(snapshots, jar_index=jar_index)
            self.assertEqual(snap_summary["snapshot_count"], 2)
            self.assertEqual(len(snap_summary["top_growing_classes"]), 1)
            row = snap_summary["top_growing_classes"][0]
            self.assertEqual(row["cls"], "com.example.queue.RetainedSession")
            self.assertEqual(row["delta_instances"], 200)
            self.assertEqual(row["instances_per_hour"], 400.0)
            self.assertEqual(row["first_suspect"], "QueuePlugin.jar")
            self.assertEqual(row["attribution_status"], "unconfirmed_passive_correlation")

    def test_sparse_bursty_and_heavy_tail_null_vs_repeated_planted_leak(self):
        follow_ups = ["quit_lt1s", "quit_delay", "rejoin", "repeat", "concurrent", "window_click"]

        # 1. Sparse arms (200 episodes over 15 nodes x 6 follow-ups = 90 arms) with 15 bursty classes
        #    (2% per-episode chance of a +500 one-off burst, no real leak) across 3 seeds -> 0 false positives.
        for seed in (1, 2, 3):
            rng = random.Random(seed)
            burst_records = []
            for ep in range(200):
                node = f"n{rng.randrange(15)}"
                fu = follow_ups[rng.randrange(len(follow_ups))]
                deltas = {f"cls:com.example.burst.C{c}": (500.0 if rng.random() < 0.02 else 0.0) for c in range(15)}
                burst_records.append(
                    {
                        "ep": ep,
                        "node": node,
                        "follow_up": fu,
                        "departures": 1 if "quit" in fu else 0,
                        "deltas": deltas,
                    }
                )
            burst_screen = bfs.screen_server_leaks(burst_records)
            self.assertEqual(burst_screen["correlations"], [], f"unexpected false positive on burst seed {seed}")

        # 2. Heavy-tail exponential noise (mean 20, sd 20) over 200 episodes and 90 arms -> 0 false positives.
        for seed in (101, 102):
            rng = random.Random(seed)
            exp_records = []
            for ep in range(200):
                node = f"n{rng.randrange(15)}"
                fu = follow_ups[rng.randrange(len(follow_ups))]
                deltas = {f"cls:com.example.exp.C{c}": rng.expovariate(1.0 / 20.0) for c in range(15)}
                exp_records.append(
                    {
                        "ep": ep,
                        "node": node,
                        "follow_up": fu,
                        "departures": 1 if "quit" in fu else 0,
                        "deltas": deltas,
                    }
                )
            exp_screen = bfs.screen_server_leaks(exp_records)
            self.assertEqual(exp_screen["correlations"], [], f"unexpected false positive on exp seed {seed}")

        # 3. Same sparse bursty 200-episode environment WITH a genuine repeated planted leak on `n0` x `quit_lt1s`
        #    (active in 5 episodes, leaking +200 each time): only `RetainedSession` is flagged, 0 bursty classes.
        rng = random.Random(7)
        mixed_records = []
        for ep in range(200):
            if ep in (10, 40, 80, 120, 160):
                node, fu = "n0", "quit_lt1s"
            else:
                node = f"n{1 + rng.randrange(14)}"
                fu = follow_ups[rng.randrange(len(follow_ups))]
            deltas = {f"cls:com.example.burst.C{c}": (500.0 if rng.random() < 0.02 else 0.0) for c in range(15)}
            deltas["cls:com.example.queue.RetainedSession"] = 200.0 if (node == "n0" and fu == "quit_lt1s") else 0.0
            mixed_records.append(
                {
                    "ep": ep,
                    "node": node,
                    "follow_up": fu,
                    "departures": 1 if "quit" in fu else 0,
                    "deltas": deltas,
                }
            )
        mixed_screen = bfs.screen_server_leaks(mixed_records)
        flagged_cls = {c["cls"] for c in mixed_screen["correlations"]}
        self.assertEqual(flagged_cls, {"com.example.queue.RetainedSession"})
        self.assertEqual(mixed_screen["correlations"][0]["feature"], "arm:n0:quit_lt1s")
        self.assertAlmostEqual(mixed_screen["correlations"][0]["slope"], 200.0, places=1)

    @staticmethod
    def _synthetic_leak_records(seed, noise, arms_per_episode, planted=None, n_episodes=200, n_classes=15):
        """Episodes over 15 nodes x 6 follow-ups; `planted` leaks on 7 episodes of one extra arm."""
        follow_ups = ["quit_lt1s", "quit_delay", "rejoin", "repeat", "concurrent", "window_click"]
        rng = random.Random(seed)
        leak_eps = set(rng.sample(range(n_episodes), 7)) if planted else set()
        records = []
        for ep in range(n_episodes):
            arms = [
                {"node": f"n{rng.randrange(15)}", "follow_up": rng.choice(follow_ups)}
                for _ in range(arms_per_episode)
            ]
            if ep in leak_eps:
                arms[0] = {"node": "leaky", "follow_up": "quit_lt1s"}
            deltas = {}
            for c in range(n_classes):
                if noise == "gauss":
                    deltas[f"cls:com.example.bg.C{c}"] = rng.gauss(20.0, 10.0)
                else:
                    deltas[f"cls:com.example.bg.C{c}"] = float(rng.choice([0, 0, 0, 100]))
            deltas["cls:com.example.queue.RetainedSession"] = rng.gauss(0.0, 20.0) + (planted if ep in leak_eps else 0.0)
            records.append(
                {
                    "ep": ep,
                    "arms": arms,
                    "departures": sum(1 for a in arms if "quit" in a["follow_up"]),
                    "deltas": deltas,
                }
            )
        return records

    def test_leak_screen_empirical_familywise_rate_and_planted_sensitivity(self):
        # Exact hypergeometric top-rank test, Bonferroni over classes x features x thresholds:
        # nominal familywise false-positive rate is 2.5% per run; assert a loose 5% empirically on 100 null
        # seeds for continuous and quantised-bimodal deltas, with 1 and 3 arms per episode.
        for noise in ("gauss", "bimodal"):
            for arms_per_episode in (1, 3):
                false_runs = sum(
                    1
                    for seed in range(100)
                    if bfs.screen_server_leaks(self._synthetic_leak_records(seed, noise, arms_per_episode))[
                        "correlations"
                    ]
                )
                self.assertLessEqual(
                    false_runs, 5, f"{noise}/{arms_per_episode} arms: {false_runs}/100 runs with a false positive"
                )

        # A +80 per-activation leak over 7 active episodes (noise sd 20) is found in nearly every run, on the
        # right class and arm; weak signals (+40) are allowed to stay "insufficient evidence".
        hits = 0
        for seed in range(10):
            screen = bfs.screen_server_leaks(self._synthetic_leak_records(1000 + seed, "gauss", 3, planted=80.0))
            found = [
                c
                for c in screen["correlations"]
                if c["cls"] == "com.example.queue.RetainedSession" and c["feature"] == "arm:leaky:quit_lt1s"
            ]
            self.assertTrue(all(c["cls"] == "com.example.queue.RetainedSession" for c in screen["correlations"]))
            hits += 1 if found else 0
        self.assertGreaterEqual(hits, 9)

    def test_leak_screen_few_active_episodes_with_discrete_deltas_is_insufficient_evidence(self):
        # 4 of 4 active episodes landing in a 25% "high" mode is 0.4% likely by chance: far above the Bonferroni
        # level, so it must not be reported even though every active episode is high.
        rng = random.Random(9)
        records = []
        active = set(rng.sample(range(200), 4))
        for ep in range(200):
            high = ep in active or rng.random() < 0.25
            records.append(
                {
                    "ep": ep,
                    "arms": [{"node": "rare" if ep in active else f"n{ep % 10}", "follow_up": "quit_lt1s"}],
                    "departures": 1,
                    "deltas": {"cls:com.example.bg.Batch": 100.0 if high else 0.0},
                }
            )
        for c in range(14):
            for rec in records:
                rec["deltas"][f"cls:com.example.bg.Other{c}"] = float(rng.choice([0, 0, 0, 100]))
        self.assertEqual(bfs.screen_server_leaks(records)["correlations"], [])

    def test_leak_screen_runtime_bounded_at_thousands_of_classes(self):
        follow_ups = ["quit_lt1s", "quit_delay", "rejoin", "repeat", "concurrent", "window_click"]
        rng = random.Random(5)
        records = []
        for ep in range(300):
            records.append(
                {
                    "ep": ep,
                    "arms": [
                        {"node": f"n{rng.randrange(60)}", "follow_up": rng.choice(follow_ups)} for _ in range(3)
                    ],
                    "departures": 1,
                    "deltas": {f"cls:com.example.big.C{c}": rng.expovariate(1.0 / 20.0) for c in range(2000)},
                }
            )
        t0 = time.monotonic()
        screen = bfs.screen_server_leaks(records)
        self.assertLess(time.monotonic() - t0, 20.0)
        self.assertEqual(screen["correlations"], [])
        self.assertGreater(screen["total_tests"], 100000)


class ServerModePassiveAuditTest(unittest.TestCase):
    """ADR-125 `--mode server` passive local audit validation, read-only discovery,
    optional local JcmdObserver snapshot, offline JarIndex inventory, and report/verdict output."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.out_dir = tempfile.mkdtemp()
        self.plugins_dir = os.path.join(self.dir, "plugins")
        os.makedirs(self.plugins_dir, exist_ok=True)
        with open(os.path.join(self.dir, "server.properties"), "w", encoding="utf-8") as fh:
            fh.write("server-port=25565\nrcon.port=25575\nrcon.password=secret\nonline-mode=false\n")
        with open(os.path.join(self.dir, "bench-loop.sh"), "w", encoding="utf-8") as fh:
            fh.write("#!/bin/sh\nwhile true; do ./start.sh; sleep 2; done\n")
        self._write_jar(
            os.path.join(self.plugins_dir, "ViaVersion-5.0.0.jar"),
            plugin_yml="name: ViaVersion\nversion: 5.0.0\n",
            classes=["com/viaversion/viaversion/ViaManagerImpl.class"],
        )
        self._write_jar(
            os.path.join(self.plugins_dir, "ExamplePlugin-1.0.jar"),
            plugin_yml="name: ExamplePlugin\nversion: 1.0\ndepend:\n- ViaVersion\n",
            classes=[
                "com/example/plugin/ExamplePlugin.class",
                "com/example/plugin/SessionCache.class",
            ],
        )

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)
        shutil.rmtree(self.out_dir, ignore_errors=True)

    @staticmethod
    def _write_jar(path: str, plugin_yml: str, classes: list[str]) -> None:
        with zipfile.ZipFile(path, "w") as zf:
            zf.writestr("plugin.yml", plugin_yml)
            for cls_entry in classes:
                zf.writestr(cls_entry, b"\xca\xfe\xba\xbe")

    # Serverbound 1.20.1 packets a passive discovery session may send: handshake, client settings/brand,
    # movement acknowledgements and keep-alives. Any other name (chat, commands, tab-complete, clicks) is active.
    PASSIVE_SERVERBOUND = frozenset({
        "login_start", "encryption_begin", "settings", "custom_payload", "teleport_confirm",
        "position", "position_look", "look", "flying", "keep_alive", "message_acknowledgement",
    })

    @staticmethod
    def _read_protocol_events(events_log: str) -> list[dict]:
        time.sleep(0.3)
        if not os.path.exists(events_log):
            return []
        with open(events_log, "r", encoding="utf-8") as fh:
            return [json.loads(ln) for ln in fh if ln.strip()]

    def _assert_passive_session(self, events_log: str, start: int, label: str) -> int:
        new = self._read_protocol_events(events_log)[start:]
        logins = [e for e in new if e["ev"] == "login"]
        packets = {e["packet"] for e in new if e["ev"] == "packet"}
        self.assertEqual(len(logins), 1, f"{label}: expected exactly one login")
        self.assertEqual(sorted(packets - self.PASSIVE_SERVERBOUND), [], f"{label}: active serverbound packets")
        return start + len(new)

    def _assert_cli_rejected(self, extra_argv: list[str], expected_substr: str) -> None:
        err = io.StringIO()
        argv = ["--mode", "server", "--server-dir", self.dir, "--out-dir", self.out_dir] + extra_argv
        with (
            unittest.mock.patch.object(bf, "FuzzDriver", side_effect=AssertionError("RCON touched")),
            unittest.mock.patch.object(bf, "PermissionGrant", side_effect=AssertionError("LP touched")),
            unittest.mock.patch.object(bf, "Swarm", side_effect=AssertionError("Swarm touched")),
            contextlib.redirect_stderr(err),
            self.assertRaises(SystemExit) as cm,
        ):
            bf.main(argv)
        self.assertEqual(cm.exception.code, 2)
        self.assertIn(expected_substr, err.getvalue())

    def test_validates_explicit_profile_local_host_and_disposable_server_dir(self):
        # Missing explicit --profile
        self._assert_cli_rejected([], "requires an explicit --profile")

        # Unknown --profile
        self._assert_cli_rejected(["--profile", "spigot"], "invalid --profile")

        # Profile jar directory mismatch (fabric needs mods/, only plugins/ exists)
        self._assert_cli_rejected(["--profile", "fabric"], "requires jar directory")

        # Non-local --host / --mc-host
        self._assert_cli_rejected(["--profile", "paper", "--host", "10.0.0.5"], "local loopback --host")
        self._assert_cli_rejected(["--profile", "paper", "--mc-host", "192.168.1.20"], "local loopback --mc-host")

        # Non-disposable directory (remove bench-loop.sh marker)
        os.remove(os.path.join(self.dir, "bench-loop.sh"))
        self._assert_cli_rejected(["--profile", "paper"], "disposable bench copy")

        # Restore marker via spigot.yml no-restart-script, but set online-mode=true
        with open(os.path.join(self.dir, "spigot.yml"), "w", encoding="utf-8") as fh:
            fh.write("settings:\n  restart-script: ./no-restart-script.sh\n")
        with open(os.path.join(self.dir, "server.properties"), "w", encoding="utf-8") as fh:
            fh.write("server-port=25565\nonline-mode=true\n")
        self._assert_cli_rejected(["--profile", "paper"], "online-mode=true")

        # Missing online-mode key in server.properties (Minecraft defaults to online-mode=true) must also be rejected
        with open(os.path.join(self.dir, "server.properties"), "w", encoding="utf-8") as fh:
            fh.write("server-port=25565\nrcon.port=25575\n")
        self._assert_cli_rejected(["--profile", "paper"], "explicit online-mode=false")

        # Restore online-mode=false and check that passing both --pid and --pid-file is rejected
        with open(os.path.join(self.dir, "server.properties"), "w", encoding="utf-8") as fh:
            fh.write("server-port=25565\nrcon.port=25575\nonline-mode=false\n")
        pid_file = os.path.join(self.dir, "server.pid")
        with open(pid_file, "w", encoding="utf-8") as fh:
            fh.write(f"{os.getpid()}\n")
        self._assert_cli_rejected(
            ["--profile", "paper", "--pid", str(os.getpid()), "--pid-file", pid_file],
            "rejects passing both --pid and --pid-file",
        )

    def test_mc_port_must_match_server_port_and_pid_reverified_before_jcmd(self):
        # Explicit --mc-port must equal the validated server-port; omitted uses the configured port.
        self.assertEqual(bfs.resolve_server_port({"server-port": "25570"}, None), 25570)
        self.assertEqual(bfs.resolve_server_port({"server-port": "25570"}, 25570), 25570)
        with self.assertRaisesRegex(ValueError, "does not match server.properties server-port=25565"):
            bfs.resolve_server_port({"server-port": "25565"}, 25566)
        self._assert_cli_rejected(["--profile", "paper", "--mc-port", "25566"], "invalid --mc-port")

        # PID ownership is re-verified before every jcmd call, so a recycled PID is never attached.
        cwd = {"value": self.dir}
        calls: list[list[str]] = []

        def runner(cmd, timeout_s):
            calls.append(cmd)
            return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

        obs = bfs.JcmdObserver(
            pid=4242,
            server_dir=self.dir,
            runner=runner,
            pid_cwd=lambda pid: cwd["value"],
            pid_cmdline=lambda pid: None,
        )
        with unittest.mock.patch("os.kill", return_value=None):
            self.assertEqual(obs._build_command("Thread.print")[1], "4242")
            cwd["value"] = "/tmp/recycled-other-server"
            with self.assertRaisesRegex(bfs.JcmdError, "does not match server_dir"):
                obs._run_jcmd_op("GC.class_histogram")
        self.assertEqual(calls, [])

    @unittest.skipUnless(os.name == "posix", "local jcmd PID verification requires POSIX")
    def test_jcmd_observer_rejects_stale_hint(self):
        calls: list[list[str]] = []

        def runner(cmd, timeout_s):
            calls.append(cmd)
            if cmd == ["jcmd", "-l"]:
                return subprocess.CompletedProcess(cmd, 0, stdout=f"4242 {self.dir}/paper.jar\n", stderr="")
            return subprocess.CompletedProcess(cmd, 0, stdout='"Server"\nThread.run', stderr="")

        obs = bfs.JcmdObserver(
            server_dir=self.dir,
            runner=runner,
            pid_cwd=lambda pid: None,
            pid_cmdline=lambda pid: None,
        )
        with unittest.mock.patch("os.kill", return_value=None):
            with self.assertRaisesRegex(bfs.JcmdError, "cannot be positively verified"):
                obs.thread_print()

        self.assertEqual(calls, [["jcmd", "-l"]])

    def test_requires_default_tier_only_and_rejects_prohibited_flags(self):
        # Non-default tiers rejected
        for bad_tier in ("op", "default,op", "op,default", "admin"):
            self._assert_cli_rejected(["--profile", "paper", "--tiers", bad_tier], "requires --tiers default only")

        # Target-specific flags rejected (even when matching default value)
        for flag_args in (
            ["--cmd", "rtp=rtp"],
            ["--names", bf.bo.DEFAULT_NAMES],
            ["--jars", bf.bo.DEFAULT_JARS],
            ["--plugin-packages", "com.example=Example"],
            ["--targets", "direct,rtp"],
            ["--rtp-args", "world"],
        ):
            self._assert_cli_rejected(["--profile", "paper"] + flag_args, "rejects target-specific flag")

        # Unsafe / op / mutation / container flags rejected
        for flag_args in (
            ["--grant", "*"],
            ["--lp-group", "default"],
            ["--console", "rtp=rtp reload"],
            ["--allow-online"],
            ["--container", "mc-bench"],
            ["--mat", "/opt/mat/ParseHeapDump.sh"],
            ["--no-restart"],
            ["--no-isolate"],
        ):
            self._assert_cli_rejected(["--profile", "paper"] + flag_args, "rejects unsafe/op flag")

        # Replay flags rejected
        for flag_args in (
            ["--replay", "trace.jsonl", "--ep", "0"],
            ["--ep", "1"],
            ["--replay-seed", "1"],
            ["--replay-n", "3"],
            ["--replay-budget", "10"],
        ):
            self._assert_cli_rejected(["--profile", "paper"] + flag_args, "rejects replay flag")

        # Budget / active / stall-probe flags rejected
        for flag_args in (
            ["--episodes", "10"],
            ["--seeds", "1,2"],
            ["--reps", "2"],
            ["--rungs", "4x1"],
            ["--warmup", "0"],
            ["--shrink", "1"],
            ["--finding-n", "2"],
            ["--surprise-n", "2"],
            ["--coverage"],
            ["--stall-ms", "2000"],
            ["--default-budget-share", "0.75"],
        ):
            self._assert_cli_rejected(["--profile", "paper"] + flag_args, "rejects budget/active flag")

        # Abbreviated prohibited flags rejected via explicit-vs-default attribute comparison
        for flag_args, expected_cat in (
            (["--no-rest"], "rejects unsafe/op flag --no-restart"),
            (["--allow-on"], "rejects unsafe/op flag --allow-online"),
            (["--cont", "mc-bench"], "rejects unsafe/op flag --container"),
            (["--default-budget", "0.75"], "rejects budget/active flag --default-budget-share"),
        ):
            self._assert_cli_rejected(["--profile", "paper"] + flag_args, expected_cat)

        # Ambiguous prefix abbreviations rejected by argparse
        self._assert_cli_rejected(["--profile", "paper", "--repl", "trace.jsonl"], "ambiguous option")
        self._assert_cli_rejected(["--profile", "paper", "--episo", "10"], "ambiguous option")
        self._assert_cli_rejected(["--profile", "paper", "--stall", "1500"], "ambiguous option")

        # Server-only flags rejected in ADR-111 target modes
        err = io.StringIO()
        with (
            unittest.mock.patch.object(bf, "FuzzDriver", side_effect=AssertionError("server touched")),
            contextlib.redirect_stderr(err),
            self.assertRaises(SystemExit) as cm,
        ):
            bf.main(["--server-dir", self.dir, "--mode", "leak", "--profile", "paper", "--out-dir", self.out_dir])
        self.assertEqual(cm.exception.code, 2)
        self.assertIn("--profile is only valid with --mode server", err.getvalue())

    def test_passive_audit_captures_commands_once_and_writes_passive_only_verdict_and_report(self):
        discover_calls: list[dict] = []
        created_swarms: list[Any] = []

        class FakePassiveSwarm:
            def __init__(self, argv, log_path):
                self.argv = argv
                self.log_path = log_path
                self.started = False
                self.closed = False
                created_swarms.append(self)

            def start(self):
                self.started = True

            def close(self):
                self.closed = True

            def discover(self, did, tier="default", bot=None, max_ms=15000, timeout_s=None):
                discover_calls.append({"did": did, "tier": tier, "bot": bot, "max_ms": max_ms})
                pkt = {
                    "ev": "commands",
                    "id": did,
                    "tier": tier,
                    "captured": True,
                    "truncated": False,
                    "rootIndex": 0,
                    "nodes": [
                        {"index": 0, "kind": "root", "name": "", "children": [1, 2, 3, 4]},
                        {"index": 1, "kind": "literal", "name": "rtp", "executable": True, "children": []},
                        {"index": 2, "kind": "literal", "name": "betterrtp:rtp", "executable": False, "redirect": 1, "children": []},
                        {"index": 3, "kind": "literal", "name": "stop", "executable": True, "children": []},
                        {"index": 4, "kind": "literal", "name": "customdanger", "executable": True, "children": []},
                    ],
                }
                return pkt, {"ev": "done", "id": did, "reason": "complete"}

        with (
            unittest.mock.patch.object(bf, "FuzzDriver", side_effect=AssertionError("RCON must not be touched")),
            unittest.mock.patch.object(bf, "PermissionGrant", side_effect=AssertionError("LP must not be touched")),
            unittest.mock.patch.object(bf, "Swarm", side_effect=FakePassiveSwarm),
        ):
            rc = bf.main([
                "--mode", "server",
                "--server-dir", self.dir,
                "--profile", "paper",
                "--tiers", "default",
                "--deny", "customdanger",
                "--out-dir", self.out_dir,
            ])

        self.assertEqual(rc, 0)
        self.assertEqual(len(discover_calls), 1)
        self.assertEqual(discover_calls[0]["tier"], "default")
        self.assertEqual(len(created_swarms), 1)
        self.assertTrue(created_swarms[0].started)
        self.assertTrue(created_swarms[0].closed)

        # Verify no jars were renamed to .off
        plugin_files = sorted(os.listdir(self.plugins_dir))
        self.assertEqual(plugin_files, ["ExamplePlugin-1.0.jar", "ViaVersion-5.0.0.jar"])

        runs_root = os.path.join(self.out_dir, "runs")
        run_dirs = [os.path.join(runs_root, d) for d in os.listdir(runs_root)]
        self.assertEqual(len(run_dirs), 1)
        run_dir = run_dirs[0]

        commands_file = os.path.join(run_dir, "commands.json")
        verdict_file = os.path.join(run_dir, "verdict.json")
        report_file = os.path.join(run_dir, "report.txt")
        self.assertTrue(os.path.isfile(commands_file))
        self.assertTrue(os.path.isfile(verdict_file))
        self.assertTrue(os.path.isfile(report_file))

        tree = bfs.CommandTree.read_json(commands_file)
        self.assertEqual(tree.tiers, ["default"])
        self.assertEqual([n.node_id for n in tree.allowed_executable_nodes(["default"])], ["rtp"])
        self.assertTrue(tree.nodes["stop"].denied)
        self.assertTrue(tree.nodes["customdanger"].denied)
        self.assertEqual(tree.nodes["customdanger"].denied_by, "customdanger")

        with open(verdict_file, "r", encoding="utf-8") as fh:
            verdict = json.load(fh)
        self.assertEqual(verdict["mode"], "server")
        self.assertEqual(verdict["status"], "passive_only")
        self.assertEqual(verdict["verdict"], "PASSIVE_ONLY")
        self.assertEqual(verdict["profile"], "paper")
        self.assertEqual(verdict["tiers"], ["default"])
        self.assertEqual(verdict["baseline"]["available"], False)
        self.assertEqual(verdict["baseline"]["status"], "unavailable")
        self.assertEqual(verdict["confirmed_culprits"], [])
        self.assertEqual(verdict["blamed_jars"], [])
        self.assertEqual(verdict["findings"], [])
        self.assertEqual(verdict["jcmd_snapshot"]["requested"], False)
        self.assertEqual(verdict["jcmd_snapshot"]["status"], "not_requested")
        self.assertEqual(verdict["jars"]["installed_jars"], ["ExamplePlugin-1.0.jar", "ViaVersion-5.0.0.jar"])
        self.assertEqual(verdict["jars"]["prerequisite_jars"], ["ViaVersion-5.0.0.jar"])
        self.assertEqual(verdict["jars"]["candidate_jars"], ["ExamplePlugin-1.0.jar"])
        self.assertNotIn("replay_cmd", json.dumps(verdict))
        self.assertNotIn("operator_mitigation", json.dumps(verdict))
        self.assertNotIn("author_reproducer", json.dumps(verdict))

        with open(report_file, "r", encoding="utf-8") as fh:
            report_text = fh.read()
        self.assertIn("status: passive_only", report_text)
        self.assertIn("status: unavailable (available=False)", report_text)
        self.assertIn("confirmed_culprits: none (0)", report_text)
        self.assertNotIn("--replay", report_text)

    def test_passive_audit_optional_local_jcmd_snapshot_and_truncation_warning(self):
        pid_file = os.path.join(self.dir, "server.pid")
        with open(pid_file, "w", encoding="utf-8") as fh:
            fh.write(f"{os.getpid()}\n")

        histo_out = (
            " num     #instances         #bytes  class name (module)\n"
            "-------------------------------------------------------\n"
            "   1:           250          40000  com.example.plugin.SessionCache\n"
            "   2:           100           8000  java.lang.String (java.base@21.0.2)\n"
            "Total           350          48000\n"
        )
        tdump_out = (
            '2026-10-24 03:00:00\n'
            'Full thread dump OpenJDK 64-Bit Server VM:\n\n'
            '"Server thread" #25 prio=5 os_prio=0 tid=0x01 nid=0x02 runnable\n'
            '   java.lang.Thread.State: RUNNABLE\n'
            '\tat com.example.plugin.ExamplePlugin.onTick(ExamplePlugin.java:42)\n'
            '\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:100)\n'
        )

        def fake_runner(cmd, timeout_s):
            op = cmd[-1]
            if op == "GC.class_histogram":
                return subprocess.CompletedProcess(cmd, 0, stdout=histo_out, stderr="")
            if op == "Thread.print":
                return subprocess.CompletedProcess(cmd, 0, stdout=tdump_out, stderr="")
            raise AssertionError(f"unexpected jcmd call: {cmd}")

        obs = bfs.JcmdObserver(
            pid_file=pid_file,
            server_dir=self.dir,
            runner=fake_runner,
            pid_cwd=lambda p: os.path.realpath(self.dir) if p == os.getpid() else None,
        )

        class TruncatedSwarm:
            def discover(self, did, tier="default", bot=None, max_ms=15000, timeout_s=None):
                pkt = {
                    "ev": "commands",
                    "id": did,
                    "tier": tier,
                    "captured": True,
                    "truncated": True,
                    "rootIndex": 0,
                    "nodes": [
                        {"index": 0, "kind": "root", "name": "", "children": [1]},
                        {"index": 1, "kind": "literal", "name": "rtp", "executable": True, "children": []},
                    ],
                }
                return pkt, {"ev": "done", "id": did, "reason": "complete"}

        args = bf.build_parser().parse_args([
            "--mode", "server",
            "--server-dir", self.dir,
            "--profile", "paper",
            "--pid-file", pid_file,
            "--out-dir", self.out_dir,
        ])
        run_dir = os.path.join(self.out_dir, "audit-snap")
        runner = bfs.ServerRun(args, run_dir, swarm=TruncatedSwarm(), observer=obs)
        verdict = runner.run()

        self.assertEqual(verdict["status"], "passive_only")
        self.assertEqual(verdict["confirmed_culprits"], [])
        self.assertEqual(verdict["blamed_jars"], [])
        self.assertEqual(verdict["findings"], [])
        self.assertTrue(verdict["commands"]["truncated"])
        self.assertTrue(any("truncated" in w.lower() for w in verdict["warnings"]))

        snap = verdict["jcmd_snapshot"]
        self.assertTrue(snap["requested"])
        self.assertEqual(snap["status"], "collected")
        self.assertEqual(snap["pid"], os.getpid())
        self.assertEqual(snap["attribution_status"], "passive_snapshot_only")
        self.assertEqual(len(snap["exclusion_windows"]), 2)
        self.assertEqual(snap["top_classes"][0]["class_name"], "com.example.plugin.SessionCache")
        self.assertEqual(snap["top_classes"][0]["jar_owner"], "ExamplePlugin-1.0.jar")
        self.assertEqual(snap["main_thread"]["first_suspect"], "ExamplePlugin-1.0.jar")

        with open(os.path.join(run_dir, "report.txt"), "r", encoding="utf-8") as fh:
            report_text = fh.read()
        self.assertIn("non-causal", report_text)
        self.assertIn("truncated", report_text.lower())

        # Unowned / non-existent PID in --pid-file fails closed before starting Swarm
        stale_pid_file = os.path.join(self.dir, "stale.pid")
        with open(stale_pid_file, "w", encoding="utf-8") as fh:
            fh.write("99999999\n")
        err = io.StringIO()
        with (
            unittest.mock.patch.object(bf, "Swarm", side_effect=AssertionError("Swarm must not start on bad PID")),
            contextlib.redirect_stderr(err),
        ):
            rc = bf.main([
                "--mode", "server",
                "--server-dir", self.dir,
                "--profile", "paper",
                "--pid-file", stale_pid_file,
                "--out-dir", self.out_dir,
            ])
        self.assertEqual(rc, 2)
        self.assertIn("99999999", err.getvalue())

        # Failed discover fails closed without writing a false verdict.json and always closes owned Swarm
        failed_swarms: list[Any] = []

        class FailingSwarm:
            def __init__(self, argv, log_path):
                self.started = False
                self.closed = False
                failed_swarms.append(self)

            def start(self):
                self.started = True

            def close(self):
                self.closed = True

            def discover(self, did, tier="default", bot=None, max_ms=15000, timeout_s=None):
                raise bf.SwarmError("discover timed out before declare_commands")

        fail_out = os.path.join(self.out_dir, "fail-discover")
        err2 = io.StringIO()
        with (
            unittest.mock.patch.object(bf, "Swarm", side_effect=FailingSwarm),
            contextlib.redirect_stderr(err2),
        ):
            rc2 = bf.main([
                "--mode", "server",
                "--server-dir", self.dir,
                "--profile", "paper",
                "--out-dir", fail_out,
            ])
        self.assertEqual(rc2, 2)
        self.assertIn("discover timed out", err2.getvalue())
        self.assertEqual(len(failed_swarms), 1)
        self.assertTrue(failed_swarms[0].started)
        self.assertTrue(failed_swarms[0].closed)

        # Injected observer with container set or check_local_pid=False is rejected before discovery
        container_obs = bfs.JcmdObserver(container="mc-container", runner=fake_runner)
        with self.assertRaisesRegex(bfs.JcmdError, "container JcmdObserver.*not permitted"):
            bfs.ServerRun(
                args,
                os.path.join(self.out_dir, "audit-container-obs"),
                swarm=TruncatedSwarm(),
                observer=container_obs,
            ).run()

        unchecked_obs = bfs.JcmdObserver(pid=os.getpid(), check_local_pid=False, runner=fake_runner)
        with self.assertRaisesRegex(bfs.JcmdError, "check_local_pid=False is not permitted"):
            bfs.ServerRun(
                args,
                os.path.join(self.out_dir, "audit-unchecked-obs"),
                swarm=TruncatedSwarm(),
                observer=unchecked_obs,
            ).run()

        # Unknown cwd (e.g. non-Linux without /proc) and unreadable/non-matching cmdline fails closed before discovery
        unverified_obs = bfs.JcmdObserver(
            pid_file=pid_file,
            server_dir=self.dir,
            runner=fake_runner,
            pid_cwd=lambda p: None,
            pid_cmdline=lambda p: None,
        )
        with self.assertRaisesRegex(bfs.JcmdError, "cannot be positively verified"):
            bfs.ServerRun(
                args,
                os.path.join(self.out_dir, "audit-unverified-pid"),
                swarm=TruncatedSwarm(),
                observer=unverified_obs,
            ).run()

        # Windows / non-POSIX host never calls os.kill(pid, 0) (which sends CTRL_C_EVENT on Windows) and fails closed
        win_obs = bfs.JcmdObserver(
            pid_file=pid_file,
            server_dir=self.dir,
            runner=fake_runner,
            pid_cwd=lambda p: os.path.realpath(self.dir),
        )
        with (
            unittest.mock.patch.object(bfs.os, "name", "nt"),
            unittest.mock.patch.object(bfs.os, "kill", side_effect=AssertionError("os.kill must never run on Windows")),
        ):
            with self.assertRaisesRegex(bfs.JcmdError, "unsupported on non-POSIX host"):
                bfs.ServerRun(
                    args,
                    os.path.join(self.out_dir, "audit-win-pid"),
                    swarm=TruncatedSwarm(),
                    observer=win_obs,
                ).run()

    def test_tick_gap_timestamp_exclusion_with_actual_swarm_event_shape(self):
        # Verify JcmdObserver exclusion windows and filter_tick_gaps / summarize_tick_gaps / StallOracle
        # using the exact event payload emitted by bench-swarm.js:
        #   {ev: 'tick_gap', bot, ms, at, keepalive_ms, id, t}
        pid_file = os.path.join(self.dir, "server.pid")
        with open(pid_file, "w", encoding="utf-8") as fh:
            fh.write(f"{os.getpid()}\n")

        histo_out = (
            " num     #instances         #bytes  class name (module)\n"
            "-------------------------------------------------------\n"
            "   1:           120          19200  com.example.plugin.SessionCache\n"
            "Total           120          19200\n"
        )
        tdump_out = (
            "2026-10-24 03:00:00\n"
            "Full thread dump OpenJDK 64-Bit Server VM:\n\n"
            '"Server thread" #25 prio=5 os_prio=0 tid=0x01 nid=0x02 runnable\n'
            "   java.lang.Thread.State: RUNNABLE\n"
            "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:100)\n"
        )
        # GC.class_histogram runs across [50000, 50600] (+200ms grace -> [50000, 50800]);
        # Thread.print runs across [55000, 55100] (+200ms grace -> [55000, 55300]).
        clock_ticks = [50000, 50600, 55000, 55100]

        def fake_runner(cmd, timeout_s):
            if cmd[-1] == "GC.class_histogram":
                return subprocess.CompletedProcess(cmd, 0, stdout=histo_out, stderr="")
            if cmd[-1] == "Thread.print":
                return subprocess.CompletedProcess(cmd, 0, stdout=tdump_out, stderr="")
            raise AssertionError(f"unexpected jcmd call: {cmd}")

        obs = bfs.JcmdObserver(
            pid_file=pid_file,
            server_dir=self.dir,
            post_grace_ms=200,
            clock_ms=lambda: clock_ticks.pop(0) if clock_ticks else 60000,
            runner=fake_runner,
            pid_cwd=lambda p: os.path.realpath(self.dir) if p == os.getpid() else None,
        )
        obs.class_histogram()
        obs.thread_print()
        serialized_windows = [w.to_dict() for w in obs.exclusion_windows]
        self.assertEqual(
            serialized_windows,
            [
                {"start_ms": 50000, "end_ms": 50800, "kind": "GC.class_histogram"},
                {"start_ms": 55000, "end_ms": 55300, "kind": "Thread.print"},
            ],
        )

        # Actual bench-swarm.js tick_gap events include `id` and episode-relative `t` alongside epoch `at`.
        raw_tick_gaps = [
            # 1. Strictly before GC window: [48000, 49000] -> kept
            {"ev": "tick_gap", "bot": "fz_0", "ms": 1000, "at": 49000, "keepalive_ms": None, "id": "ep-1", "t": 1000},
            # 2. Overlaps GC window interior: [49000, 52200] overlaps [50000, 50800] -> excluded (observer_window)
            {"ev": "tick_gap", "bot": "fz_0", "ms": 3200, "at": 52200, "keepalive_ms": 1010, "id": "ep-1", "t": 4200},
            # 3. Touches GC window upper boundary: [50800, 51800] touches 50800 -> excluded (observer_window)
            {"ev": "tick_gap", "bot": "fz_0", "ms": 1000, "at": 51800, "keepalive_ms": 1000, "id": "ep-1", "t": 5200},
            # 4. Strictly between GC and Thread.print windows: [52000, 53000] -> kept
            {"ev": "tick_gap", "bot": "fz_0", "ms": 1000, "at": 53000, "keepalive_ms": 995, "id": "ep-1", "t": 6400},
            # 5. Touches Thread.print lower boundary: [52500, 55000] touches 55000 -> excluded (observer_window)
            {"ev": "tick_gap", "bot": "fz_0", "ms": 2500, "at": 55000, "keepalive_ms": 1005, "id": "ep-1", "t": 8400},
            # 6. Episode-relative `t` only (missing `at`): must NOT treat `t` as epoch timestamp -> excluded (unverifiable_timestamp)
            {"ev": "tick_gap", "bot": "fz_0", "ms": 3400, "keepalive_ms": 1000, "id": "ep-1", "t": 53500},
            # 7. Strictly after Thread.print window: [56000, 57010] -> kept
            {"ev": "tick_gap", "bot": "fz_0", "ms": 1010, "at": 57010, "keepalive_ms": 1020, "id": "ep-1", "t": 10410},
        ]

        kept, excluded = bfs.filter_tick_gaps(raw_tick_gaps, serialized_windows)
        self.assertEqual([g["at"] for g in kept], [49000, 53000, 57010])
        self.assertEqual(
            [(g.get("at"), g.get("excluded_reason")) for g in excluded],
            [
                (52200, "observer_window"),
                (51800, "observer_window"),
                (55000, "observer_window"),
                (None, "unverifiable_timestamp"),
            ],
        )

        summary = bfs.summarize_tick_gaps(raw_tick_gaps, serialized_windows)
        self.assertEqual(summary["count"], 3)
        self.assertEqual(summary["excluded_count"], 4)
        self.assertEqual(summary["unverifiable_count"], 1)
        self.assertEqual(summary["max_ms"], 1010.0)
        self.assertEqual(summary["max_keepalive_ms"], 1020.0)

        baseline_gaps = [{"ms": 1000.0, "at": 1000 * i} for i in range(1, 25)]
        oracle = bfs.StallOracle(baseline_gaps=baseline_gaps, stall_ms=2000.0, observer=obs)
        eval_res = oracle.evaluate_episode(raw_tick_gaps, follow_up="quit_lt1s")
        self.assertFalse(eval_res["stalled"])
        self.assertEqual(eval_res["summary"]["count"], 3)
        self.assertEqual(eval_res["summary"]["excluded_count"], 4)

    @unittest.skipUnless(NODE and MINEFLAYER, "node or devstack/clients/node_modules missing")
    def test_passive_audit_cli_against_local_fake_protocol_server_and_failure_cleanup(self):
        events_log = os.path.join(self.out_dir, "protocol_events.jsonl")
        drop_flag = os.path.join(self.out_dir, "drop_without_commands.flag")
        server_js = r"""
const fs = require('fs');
const path = require('path');
const nm = path.join(path.dirname(process.argv[1]), 'node_modules');
const mc = require(path.join(nm, 'minecraft-protocol'));
const mcData = require(path.join(nm, 'minecraft-data'))('1.20.1');
const eventsLog = process.argv[2];
const dropFlag = process.argv[3];

function recordEvent(obj) {
  fs.appendFileSync(eventsLog, JSON.stringify(obj) + '\n');
}

const srv = mc.createServer({ 'online-mode': false, host: '127.0.0.1', port: 0, version: '1.20.1' });
srv.on('login', (client) => {
  recordEvent({ ev: 'login', username: client.username });
  client.on('packet', (data, meta) => {
    recordEvent({ ev: 'packet', packet: meta.name, username: client.username });
  });
  client.write('login', {
    ...mcData.loginPacket,
    entityId: 1,
    isHardcore: false,
    gameMode: 0,
    previousGameMode: -1,
    hashedSeed: [0, 0],
    maxPlayers: 20,
    viewDistance: 2,
    simulationDistance: 2,
    reducedDebugInfo: false,
    enableRespawnScreen: true,
    isDebug: false,
    isFlat: true
  });
  if (fs.existsSync(dropFlag)) {
    setTimeout(() => { try { client.end('dropped before declare_commands'); } catch (e) {} }, 40);
    return;
  }
  client.write('declare_commands', {
    rootIndex: 0,
    nodes: [
      {
        flags: { command_node_type: 0, has_command: 0, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [1, 3, 4, 5, 6, 8, 11]
      },
      {
        flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [2],
        extraNodeData: { name: 'rtp' }
      },
      {
        flags: { command_node_type: 2, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 1 },
        children: [],
        extraNodeData: {
          name: 'radius',
          parser: 'brigadier:integer',
          properties: { flags: { min_present: 1, max_present: 1 }, min: 1, max: 256 },
          suggestionType: 'minecraft:ask_server'
        }
      },
      {
        flags: { command_node_type: 1, has_command: 0, has_redirect_node: 1, has_custom_suggestions: 0 },
        children: [],
        redirectNode: 1,
        extraNodeData: { name: 'betterrtp:rtp' }
      },
      {
        flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [],
        extraNodeData: { name: 'stop' }
      },
      {
        flags: { command_node_type: 1, has_command: 0, has_redirect_node: 1, has_custom_suggestions: 0 },
        children: [],
        redirectNode: 4,
        extraNodeData: { name: 'minecraft:stop' }
      },
      {
        flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [7],
        extraNodeData: { name: 'kill' }
      },
      {
        flags: { command_node_type: 2, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [],
        extraNodeData: {
          name: 'target',
          parser: 'minecraft:entity',
          properties: { unused: 0, onlyAllowPlayers: 0, onlyAllowEntities: 0 }
        }
      },
      {
        flags: { command_node_type: 1, has_command: 0, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [9, 10],
        extraNodeData: { name: 'tick' }
      },
      {
        flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [],
        extraNodeData: { name: 'query' }
      },
      {
        flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [],
        extraNodeData: { name: 'freeze' }
      },
      {
        flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [],
        extraNodeData: { name: 'customdanger' }
      }
    ]
  });
  client.write('position', { x: 0, y: 64, z: 0, yaw: 0, pitch: 0, flags: 0, teleportId: 1 });
  client.write('update_time', { age: [0, 100], time: [0, 100] });
});
srv.on('listening', () => {
  console.log(JSON.stringify({ port: srv.socketServer.address().port }));
});
"""
        t0 = time.monotonic()
        srv_proc = subprocess.Popen(
            [NODE, "-e", server_js, bf.SWARM_JS, events_log, drop_flag],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            line = srv_proc.stdout.readline()
            self.assertTrue(line, "fake protocol server did not report listening port")
            port = int(json.loads(line)["port"])
            with open(os.path.join(self.dir, "server.properties"), "w", encoding="utf-8") as fh:
                fh.write(f"server-port={port}\nrcon.port=25575\nrcon.password=secret\nonline-mode=false\n")

            spawned_swarms: list[Any] = []
            real_swarm_cls = bf.Swarm

            class TrackingSwarm(real_swarm_cls):
                def __init__(self, *a, **kw):
                    super().__init__(*a, **kw)
                    self.started_proc = None
                    spawned_swarms.append(self)

                def start(self):
                    super().start()
                    self.started_proc = self.proc

            def guarded() -> contextlib.ExitStack:
                stack = contextlib.ExitStack()
                stack.enter_context(unittest.mock.patch.object(bf, "FuzzDriver", side_effect=AssertionError("RCON must not be touched")))
                stack.enter_context(unittest.mock.patch.object(bf, "PermissionGrant", side_effect=AssertionError("LP must not be touched")))
                stack.enter_context(unittest.mock.patch.object(bf.bo, "rcon", side_effect=AssertionError("rcon must not be called")))
                stack.enter_context(unittest.mock.patch.object(bf, "Swarm", side_effect=TrackingSwarm))
                return stack

            live_out = os.path.join(self.out_dir, "live-passive")
            with guarded():
                rc = bf.main([
                    "--mode", "server",
                    "--server-dir", self.dir,
                    "--profile", "paper",
                    "--tiers", "default",
                    "--deny", "customdanger",
                    "--mc-port", str(port),
                    "--mc-version", "1.20.1",
                    "--join-delay-ms", "0",
                    "--episode-max-s", "4",
                    "--out-dir", live_out,
                ])

            self.assertEqual(rc, 0)
            self.assertEqual(len(spawned_swarms), 1)
            self.assertIsNotNone(spawned_swarms[0].started_proc)
            self.assertIsNone(spawned_swarms[0].proc)
            self.assertIsNotNone(
                spawned_swarms[0].started_proc.poll(), "bench-swarm.js child process must terminate cleanly"
            )

            cursor = self._assert_passive_session(events_log, 0, "discover run")

            # Verify no jars were renamed or modified
            self.assertEqual(sorted(os.listdir(self.plugins_dir)), ["ExamplePlugin-1.0.jar", "ViaVersion-5.0.0.jar"])

            runs_root = os.path.join(live_out, "runs")
            run_dirs = [os.path.join(runs_root, d) for d in os.listdir(runs_root)]
            self.assertEqual(len(run_dirs), 1)
            run_dir = run_dirs[0]

            tree = bfs.CommandTree.read_json(os.path.join(run_dir, "commands.json"))
            self.assertEqual(tree.tiers, ["default"])
            self.assertFalse(tree.truncated)
            self.assertEqual(tree.nodes["rtp"].aliases, ["rtp", "betterrtp:rtp"])
            self.assertFalse(tree.nodes["rtp"].denied)
            self.assertEqual(tree.nodes["rtp <radius>"].parser, "brigadier:integer")
            self.assertEqual(tree.nodes["rtp <radius>"].properties["min"], 1)
            self.assertEqual(tree.nodes["rtp <radius>"].properties["max"], 256)
            self.assertFalse(tree.nodes["rtp <radius>"].denied)
            self.assertFalse(tree.nodes["kill"].denied)
            self.assertTrue(tree.nodes["kill <target>"].denied)
            self.assertEqual(tree.nodes["kill <target>"].denied_by, "kill <entity>")
            self.assertFalse(tree.nodes["tick query"].denied)
            self.assertTrue(tree.nodes["tick freeze"].denied)
            self.assertEqual(tree.nodes["tick freeze"].denied_by, "tick freeze")
            self.assertTrue(tree.nodes["stop"].denied)
            self.assertEqual(tree.nodes["stop"].denied_by, "stop")
            self.assertIn("minecraft:stop", tree.nodes["stop"].aliases)
            self.assertTrue(tree.nodes["customdanger"].denied)
            self.assertEqual(tree.nodes["customdanger"].denied_by, "customdanger")
            self.assertEqual(
                sorted(n.node_id for n in tree.allowed_executable_nodes(["default"])),
                ["kill", "rtp", "rtp <radius>", "tick query"],
            )

            with open(os.path.join(run_dir, "verdict.json"), "r", encoding="utf-8") as fh:
                verdict = json.load(fh)
            self.assertEqual(verdict["status"], "passive_only")
            self.assertEqual(verdict["verdict"], "PASSIVE_ONLY")
            self.assertFalse(verdict["baseline"]["available"])
            self.assertEqual(verdict["confirmed_culprits"], [])
            self.assertEqual(verdict["blamed_jars"], [])
            self.assertEqual(verdict["findings"], [])
            self.assertNotIn("replay_cmd", json.dumps(verdict))
            self.assertNotIn("operator_mitigation", json.dumps(verdict))
            self.assertNotIn("author_reproducer", json.dumps(verdict))

            with open(os.path.join(run_dir, "report.txt"), "r", encoding="utf-8") as fh:
                report_text = fh.read()
            self.assertIn("status: passive_only", report_text)
            self.assertIn("confirmed_culprits: none (0)", report_text)
            self.assertNotIn("--replay", report_text)

            # Failure cleanup 1: protocol server drops connection before declare_commands -> Swarm closed, no verdict
            with open(drop_flag, "w", encoding="utf-8") as fh:
                fh.write("drop\n")
            drop_out = os.path.join(self.out_dir, "drop-passive")
            err_drop = io.StringIO()
            with guarded(), contextlib.redirect_stderr(err_drop):
                rc_drop = bf.main([
                    "--mode", "server",
                    "--server-dir", self.dir,
                    "--profile", "paper",
                    "--tiers", "default",
                    "--mc-port", str(port),
                    "--mc-version", "1.20.1",
                    "--join-delay-ms", "0",
                    "--episode-max-s", "2",
                    "--out-dir", drop_out,
                ])
            self.assertEqual(rc_drop, 2)
            self.assertIn("failed to capture command graph", err_drop.getvalue())
            cursor = self._assert_passive_session(events_log, cursor, "drop run")
            self.assertEqual(len(spawned_swarms), 2)
            self.assertIsNotNone(spawned_swarms[1].started_proc)
            self.assertIsNone(spawned_swarms[1].proc)
            self.assertIsNotNone(
                spawned_swarms[1].started_proc.poll(), "bench-swarm.js must be cleaned up on discover failure"
            )
            for root_dir, _, files in os.walk(drop_out):
                self.assertNotIn("verdict.json", files)
                self.assertNotIn("report.txt", files)

            # Failure cleanup 2: discover succeeds against protocol server, then post-discovery jcmd fails ->
            # Swarm closed, no verdict.json or report.txt written
            os.remove(drop_flag)
            pid_file = os.path.join(self.dir, "server.pid")
            with open(pid_file, "w", encoding="utf-8") as fh:
                fh.write(f"{os.getpid()}\n")
            jcmd_fail_dir = os.path.join(self.out_dir, "jcmd-fail-passive")
            failing_obs = bfs.JcmdObserver(
                pid_file=pid_file,
                server_dir=self.dir,
                pid_cwd=lambda p: os.path.realpath(self.dir) if p == os.getpid() else None,
                runner=lambda cmd, t: subprocess.CompletedProcess(
                    cmd, 1, stdout="", stderr="AttachNotSupportedException: target JVM refused attach"
                ),
            )
            args_jcmd_fail = bf.build_parser().parse_args([
                "--mode", "server",
                "--server-dir", self.dir,
                "--profile", "paper",
                "--tiers", "default",
                "--mc-port", str(port),
                "--mc-version", "1.20.1",
                "--join-delay-ms", "0",
                "--episode-max-s", "4",
                "--pid-file", pid_file,
                "--out-dir", jcmd_fail_dir,
            ])
            with guarded():
                with self.assertRaisesRegex(bfs.JcmdError, "AttachNotSupportedException"):
                    bfs.ServerRun(args_jcmd_fail, jcmd_fail_dir, observer=failing_obs).run()
            self.assertTrue(
                os.path.exists(os.path.join(jcmd_fail_dir, "commands.json")),
                "discovery must write commands.json before the jcmd attach fails",
            )
            cursor = self._assert_passive_session(events_log, cursor, "jcmd-fail run")
            self.assertEqual(len(spawned_swarms), 3)
            self.assertIsNotNone(spawned_swarms[2].started_proc)
            self.assertIsNone(spawned_swarms[2].proc)
            self.assertIsNotNone(
                spawned_swarms[2].started_proc.poll(), "bench-swarm.js must be closed before jcmd failure exits"
            )
            self.assertFalse(os.path.exists(os.path.join(jcmd_fail_dir, "verdict.json")))
            self.assertFalse(os.path.exists(os.path.join(jcmd_fail_dir, "report.txt")))

            # Positive control: a raw client sending active 1.20.1 chat packets must be recorded and flagged.
            probe_js = r"""
const path = require('path');
const nm = path.join(path.dirname(process.argv[1]), 'node_modules');
const mc = require(path.join(nm, 'minecraft-protocol'));
const client = mc.createClient({ host: '127.0.0.1', port: Number(process.argv[2]), username: 'probe_active', version: '1.20.1', 'online-mode': false });
client.on('error', () => process.exit(1));
client.on('login', () => {
  client.write('chat_message', { message: 'probe', timestamp: 0n, salt: 0n, signature: undefined, offset: 0, acknowledged: Buffer.alloc(3) });
  client.write('chat_command', { command: 'rtp', timestamp: 0n, salt: 0n, argumentSignatures: [], messageCount: 0, acknowledged: Buffer.alloc(3) });
  setTimeout(() => { client.end(); process.exit(0); }, 300);
});
setTimeout(() => process.exit(1), 15000);
"""
            probe = subprocess.run(
                [NODE, "-e", probe_js, bf.SWARM_JS, str(port)],
                capture_output=True, text=True, timeout=20,
            )
            self.assertEqual(probe.returncode, 0, probe.stderr)
            probe_events = self._read_protocol_events(events_log)[cursor:]
            probe_packets = {e["packet"] for e in probe_events if e["ev"] == "packet"}
            self.assertIn("chat_message", probe_packets)
            self.assertIn("chat_command", probe_packets)
            self.assertTrue(probe_packets - self.PASSIVE_SERVERBOUND, "allowlist must flag active packets")
            with self.assertRaises(AssertionError):
                self._assert_passive_session(events_log, cursor, "active control probe session")
            self.assertLess(time.monotonic() - t0, 20.0)
        finally:
            srv_proc.terminate()
            try:
                srv_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                srv_proc.kill()
            if srv_proc.stdout:
                srv_proc.stdout.close()
            if srv_proc.stderr:
                srv_proc.stderr.close()


class BoundaryLadderAndSchemaGenTest(unittest.TestCase):
    """Unit tests for Brigadier numeric ladder and schema-driven argument sampling (ADR-125 2.3)."""

    def _build_sample_tree(self) -> bfs.CommandTree:
        pkt = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1, 3, 5, 7, 9, 11, 13, 14]},
                {"index": 1, "kind": "literal", "name": "scan", "executable": False, "children": [2]},
                {
                    "index": 2,
                    "kind": "argument",
                    "name": "radius",
                    "parser": "brigadier:integer",
                    "properties": {"min": 1, "max": 2147483647},
                    "executable": True,
                    "children": [],
                },
                {"index": 3, "kind": "literal", "name": "ratio", "executable": False, "children": [4]},
                {
                    "index": 4,
                    "kind": "argument",
                    "name": "factor",
                    "parser": "brigadier:double",
                    "properties": {"min": -2.5, "max": 100.0},
                    "executable": True,
                    "children": [],
                },
                {"index": 5, "kind": "literal", "name": "toggle", "executable": False, "children": [6]},
                {
                    "index": 6,
                    "kind": "argument",
                    "name": "enabled",
                    "parser": "brigadier:bool",
                    "properties": {},
                    "executable": True,
                    "children": [],
                },
                {"index": 7, "kind": "literal", "name": "greet", "executable": False, "children": [8]},
                {
                    "index": 8,
                    "kind": "argument",
                    "name": "target",
                    "parser": "minecraft:entity",
                    "properties": {"amount": "single"},
                    "executable": True,
                    "children": [],
                },
                {"index": 9, "kind": "literal", "name": "spot", "executable": False, "children": [10]},
                {
                    "index": 10,
                    "kind": "argument",
                    "name": "pos",
                    "parser": "minecraft:block_pos",
                    "properties": {},
                    "executable": True,
                    "children": [],
                },
                {"index": 11, "kind": "literal", "name": "rtp", "executable": True, "children": [12]},
                {
                    "index": 12,
                    "kind": "argument",
                    "name": "world",
                    "parser": "brigadier:string",
                    "properties": {"type": "word"},
                    "executable": True,
                    "children": [],
                },
                {"index": 13, "kind": "literal", "name": "stop", "executable": True, "children": []},
                {"index": 14, "kind": "literal", "name": "kill", "executable": True, "children": [15]},
                {
                    "index": 15,
                    "kind": "argument",
                    "name": "victim",
                    "parser": "minecraft:entity",
                    "properties": {},
                    "executable": True,
                    "children": [],
                },
            ],
        }
        return bfs.merge_command_trees({"default": pkt})

    def test_numeric_ladder_boundaries_and_clamping(self):
        ladder = bfs.numeric_ladder(1, 2147483647, is_float=False, cap=4096)
        self.assertEqual(ladder, [1, 16, 64, 256, 1024, 4096, 2147483647])

        signed_ladder = bfs.numeric_ladder(-100, 100, is_float=False, cap=4096)
        self.assertEqual(signed_ladder, [-100, -1, 0, 1, 16, 64, 100])

        float_ladder = bfs.numeric_ladder(-2.5, 100.0, is_float=True, cap=50.0)
        self.assertEqual(float_ladder, [-2.5, -1.0, 0.0, 1.0, 16.0, 50.0, 64.0, 100.0])

    def test_gen_args_samples_parsers_completions_and_enforces_denylist(self):
        tree = self._build_sample_tree()
        rng = random.Random(42)

        # Integer argument samples both ladder points and uniform values within bounds
        scan_vals = set()
        for _ in range(40):
            cmd = bfs.gen_args(tree.nodes["scan <radius>"], tree, rng)
            self.assertTrue(cmd.startswith("scan "))
            val = int(cmd.split()[1])
            self.assertGreaterEqual(val, 1)
            self.assertLessEqual(val, 2147483647)
            scan_vals.add(val)
        self.assertTrue(scan_vals & {1, 16, 64, 256, 1024, 4096}, "ladder anchors must be sampled")

        # Float/double argument stays within bounds
        for _ in range(10):
            f_cmd = bfs.gen_args(tree.nodes["ratio <factor>"], tree, rng)
            f_val = float(f_cmd.split()[1])
            self.assertGreaterEqual(f_val, -2.5)
            self.assertLessEqual(f_val, 100.0)

        # Boolean argument
        bool_cmds = {bfs.gen_args(tree.nodes["toggle <enabled>"], tree, rng) for _ in range(16)}
        self.assertEqual(bool_cmds, {"toggle true", "toggle false"})

        # Entity selector resolves to {self} or peer bot name, never @a/@e
        entity_cmds = {
            bfs.gen_args(tree.nodes["greet <target>"], tree, rng, bot_names=["fz_0", "fz_1"])
            for _ in range(20)
        }
        self.assertTrue(entity_cmds <= {"greet {self}", "greet fz_0", "greet fz_1"})

        # Block position produces 3 relative ~ coordinates
        pos_cmd = bfs.gen_args(tree.nodes["spot <pos>"], tree, rng)
        parts = pos_cmd.split()
        self.assertEqual(parts[0], "spot")
        self.assertEqual(len(parts), 4)
        self.assertTrue(all(p.startswith("~") for p in parts[1:]))

        # Cached completions override generic string sampling
        comp_cmd = bfs.gen_args(
            tree.nodes["rtp <world>"],
            tree,
            rng,
            completions={"rtp <world>": ["world_nether", "world_the_end"]},
        )
        self.assertIn(comp_cmd, ("rtp world_nether", "rtp world_the_end"))

        # Bare `kill` is allowed, but `stop` and `kill <victim>` (matching `kill <entity>`) are rejected
        self.assertEqual(bfs.gen_args(tree.nodes["kill"], tree, rng), "kill")
        with self.assertRaisesRegex(ValueError, "denied"):
            bfs.gen_args(tree.nodes["stop"], tree, rng)
        with self.assertRaisesRegex(ValueError, "denied"):
            bfs.gen_args(tree.nodes["kill <victim>"], tree, rng)


class TierStratifiedBanditTest(unittest.TestCase):
    """Unit tests for tier-stratified UCB1 scheduling and reward updates in NodeBandit (ADR-125 2.3)."""

    def _build_two_tier_tree(self, n_default: int = 5, n_op_only: int = 50) -> bfs.CommandTree:
        default_nodes = [{"index": 0, "kind": "root", "name": "", "children": list(range(1, n_default + 1))}]
        for i in range(1, n_default + 1):
            default_nodes.append(
                {"index": i, "kind": "literal", "name": f"player_cmd_{i}", "executable": True, "children": []}
            )

        op_children = list(range(1, n_default + n_op_only + 1))
        op_nodes = [{"index": 0, "kind": "root", "name": "", "children": op_children}]
        for i in range(1, n_default + 1):
            op_nodes.append(
                {"index": i, "kind": "literal", "name": f"player_cmd_{i}", "executable": True, "children": []}
            )
        for j in range(1, n_op_only + 1):
            idx = n_default + j
            op_nodes.append(
                {"index": idx, "kind": "literal", "name": f"admin_cmd_{j}", "executable": True, "children": []}
            )

        return bfs.merge_command_trees(
            {
                "default": {"rootIndex": 0, "nodes": default_nodes},
                "op": {"rootIndex": 0, "nodes": op_nodes},
            }
        )

    def test_tier_stratification_preserves_default_share_against_large_op_tree(self):
        tree = self._build_two_tier_tree(n_default=5, n_op_only=50)

        # With both tiers active and default_budget_share=0.75, the 5 default nodes receive ~75% of pulls
        # despite 50 op-only nodes being present.
        bandit_both = bfs.NodeBandit(
            tree,
            tiers=("default", "op"),
            default_budget_share=0.75,
            rng=random.Random(7),
        )
        default_pulls = 0
        total_pulls = 400
        for _ in range(total_pulls):
            node_id, shape = bandit_both.select_arm()
            bandit_both.update(node_id, shape, reward=0.0)
            if tree.nodes[node_id].min_tier == "default":
                default_pulls += 1

        share = default_pulls / total_pulls
        self.assertGreaterEqual(share, 0.68)
        self.assertLessEqual(share, 0.82)

        # With tiers=("default",), 100% of pulls go to default-visible nodes
        bandit_default = bfs.NodeBandit(
            tree,
            tiers=("default",),
            default_budget_share=0.75,
            rng=random.Random(7),
        )
        self.assertEqual(len(bandit_default.op_arms), 0)
        for _ in range(50):
            node_id, _ = bandit_default.select_arm()
            self.assertEqual(tree.nodes[node_id].min_tier, "default")

    def test_ucb1_concentrates_pulls_on_high_reward_arm(self):
        tree = self._build_two_tier_tree(n_default=4, n_op_only=0)
        bandit = bfs.NodeBandit(tree, tiers=("default",), rng=random.Random(11))
        target_arm = ("player_cmd_2", "quit_lt1s")

        for _ in range(120):
            node_id, shape = bandit.select_arm()
            if (node_id, shape) == target_arm:
                bandit.update(node_id, shape, novelty=1.0, class_growth=200.0)
            else:
                bandit.update(node_id, shape, reward=0.0)

        target_pulls = bandit.arms[target_arm].pulls
        other_max = max(st.pulls for k, st in bandit.arms.items() if k != target_arm)
        self.assertGreater(target_pulls, other_max * 3)


class NumericBisectAndJarDdminTest(unittest.TestCase):
    """Unit tests for callback-driven numeric threshold bisection and dependency-closed jar ddmin (ADR-125 2.4)."""

    def _build_scan_tree(self, extra_deny: list[str] | None = None) -> bfs.CommandTree:
        pkt = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1]},
                {"index": 1, "kind": "literal", "name": "scan", "executable": False, "children": [2]},
                {
                    "index": 2,
                    "kind": "argument",
                    "name": "radius",
                    "parser": "brigadier:integer",
                    "properties": {"min": 1, "max": 256},
                    "executable": True,
                    "children": [],
                },
            ],
        }
        return bfs.merge_command_trees({"default": pkt}, denylist=bfs.Denylist(extra_deny))

    def test_bisect_numeric_arg_finds_exact_threshold_and_respects_denylist(self):
        tree = self._build_scan_tree(extra_deny=["scan 65"])
        node = tree.nodes["scan <radius>"]
        program = [{"a": "cmd", "cmd": "scan 256", "node": "scan <radius>"}, {"a": "wait", "ms": 200}]

        probed_cmds: list[str] = []

        def predicate(cand_prog: list[dict]) -> bool:
            cmd_str = cand_prog[0]["cmd"]
            probed_cmds.append(cmd_str)
            radius = int(cmd_str.split()[1])
            return radius >= 49

        minimized_prog, bound = bfs.bisect_numeric_arg(
            program,
            node,
            predicate,
            denylist=bfs.Denylist(["scan 65"]),
            tree=tree,
        )
        self.assertEqual(minimized_prog[0]["cmd"], "scan 49")
        self.assertEqual(bound, "radius >= 49")
        self.assertNotIn("scan 65", probed_cmds, "denied command must never be passed to predicate_fn")
        self.assertLessEqual(len(probed_cmds), 12)

        # Also works when program is structured as a bot list [{"name": ..., "program": [...]}]
        bot_prog = [{"name": "fz_0", "program": [{"a": "cmd", "cmd": "scan 256"}]}]
        min_bots, bot_bound = bfs.bisect_numeric_arg(
            bot_prog,
            node,
            lambda bots: int(bots[0]["program"][0]["cmd"].split()[1]) >= 49,
            tree=tree,
        )
        self.assertEqual(min_bots[0]["program"][0]["cmd"], "scan 49")
        self.assertEqual(bot_bound, "radius >= 49")

    def test_pre_ddmin_probes_guard_non_reproducible_and_prerequisites_only_server_findings(self):
        cands = ["PluginA.jar", "PluginB.jar", "PluginC.jar"]

        # Case 1: Full set fails to reach finding_k -> not_reproducible_after_restart, no second probe
        calls_non_repro: list[list[str]] = []

        def probe_non_repro(active: list[str]) -> tuple[int, bool]:
            calls_non_repro.append(list(active))
            return (1, True)

        res_non_repro = bfs.ddmin_jars(cands, None, probe_non_repro, finding_k=3, finding_n=3)
        self.assertEqual(res_non_repro["status"], "not_reproducible_after_restart")
        self.assertEqual(res_non_repro["verdict"], "INCONCLUSIVE")
        self.assertEqual(res_non_repro["blamed_jars"], [])
        self.assertEqual(len(calls_non_repro), 1)

        # Case 2: Prerequisites only ([]) reproduces -> labelled 'server', no jar blamed, no ddmin split
        calls_server: list[list[str]] = []

        def probe_server(active: list[str]) -> tuple[int, bool]:
            calls_server.append(list(active))
            return (3, True)

        res_server = bfs.ddmin_jars(
            cands, None, probe_server, finding_k=3, finding_n=3, first_suspect="PluginA.jar"
        )
        self.assertEqual(res_server["status"], "server")
        self.assertEqual(res_server["attribution_status"], "server")
        self.assertEqual(res_server["blamed_jars"], [])
        self.assertEqual(res_server["first_suspect"], "server")
        self.assertEqual(len(calls_server), 2)

    def test_ddmin_jars_single_culprit_two_jar_interaction_dependency_closure_and_budget_cap(self):
        with tempfile.TemporaryDirectory() as tmp:
            plugins_dir = os.path.join(tmp, "plugins")
            os.makedirs(plugins_dir, exist_ok=True)

            # Create 8 candidate jars; PluginB depends on PluginA
            jar_specs = [
                ("PluginA.jar", "PluginA", []),
                ("PluginB.jar", "PluginB", ["PluginA"]),
                ("PluginC.jar", "PluginC", []),
                ("PluginD.jar", "PluginD", []),
                ("PluginE.jar", "PluginE", []),
                ("PluginF.jar", "PluginF", []),
                ("PluginG.jar", "PluginG", []),
                ("PluginH.jar", "PluginH", []),
            ]
            for j_name, p_id, deps in jar_specs:
                j_path = os.path.join(plugins_dir, j_name)
                with zipfile.ZipFile(j_path, "w") as zf:
                    dep_line = f"depend: [{', '.join(deps)}]\n" if deps else ""
                    zf.writestr("plugin.yml", f"name: {p_id}\nversion: 1.0\n{dep_line}")
                    zf.writestr(f"com/example/{p_id.lower()}/Main.class", b"\xca\xfe\xba\xbe")

            jar_index = bfs.JarIndex(plugins_dir)
            cands = [j[0] for j in jar_specs]

            # 1. Single culprit (PluginB.jar) that requires PluginA.jar to load
            probes_single: list[list[str]] = []

            def probe_b_needs_a(active: list[str]) -> tuple[int, bool]:
                probes_single.append(list(active))
                if "PluginB.jar" in active and "PluginA.jar" not in active:
                    return (3, False)  # Unloaded dependency -> must not count as reproducing
                if "PluginB.jar" in active and "PluginA.jar" in active:
                    return (3, True)
                return (0, True)

            res_single = bfs.ddmin_jars(
                cands, jar_index, probe_b_needs_a, finding_k=3, finding_n=3, first_suspect="PluginB.jar"
            )
            self.assertEqual(res_single["attribution_status"], "confirmed")
            self.assertEqual(res_single["verdict"], "FAIL")
            self.assertEqual(res_single["blamed_jars"], ["PluginB.jar"])
            self.assertEqual(res_single["dependency_closed_jars"], ["PluginA.jar", "PluginB.jar"])
            self.assertLessEqual(res_single["probes_run"], 12)

            # Verify that an explicit unloaded subset (all_loaded=False) is treated as non-reproducing
            res_unloaded = bfs.ddmin_jars(
                ["PluginC.jar", "PluginD.jar"],
                jar_index,
                lambda active: (3, len(active) == 2),
                finding_k=3,
                finding_n=3,
            )
            self.assertEqual(res_unloaded["blamed_jars"], ["PluginC.jar", "PluginD.jar"])

            # 2. Two-jar interaction (PluginD.jar + PluginG.jar both required)
            def probe_two_jar(active: list[str]) -> tuple[int, bool]:
                return (3, True) if ("PluginD.jar" in active and "PluginG.jar" in active) else (0, True)

            res_pair = bfs.ddmin_jars(cands, jar_index, probe_two_jar, finding_k=3, finding_n=3)
            self.assertEqual(res_pair["attribution_status"], "confirmed")
            self.assertEqual(res_pair["blamed_jars"], ["PluginD.jar", "PluginG.jar"])

            # 3. Budget exhaustion (max_probes=3) falls back to INCONCLUSIVE / unconfirmed with first_suspect
            res_budget = bfs.ddmin_jars(
                cands,
                jar_index,
                probe_two_jar,
                finding_k=3,
                finding_n=3,
                max_probes=3,
                first_suspect="PluginD.jar",
            )
            self.assertEqual(res_budget["attribution_status"], "unconfirmed")
            self.assertEqual(res_budget["verdict"], "INCONCLUSIVE")
            self.assertEqual(res_budget["blamed_jars"], [])
            self.assertEqual(res_budget["first_suspect"], "PluginD.jar")
            self.assertTrue(res_budget["budget_exhausted"])


class OfflineTraceEvaluationTest(unittest.TestCase):
    """End-to-end unit tests for offline trace evaluation and structured verdict formatting (ADR-125 2.4)."""

    def test_evaluate_server_trace_screens_leak_and_stall_bisects_and_isolates_jars(self):
        with tempfile.TemporaryDirectory() as tmp:
            plugins_dir = os.path.join(tmp, "plugins")
            os.makedirs(plugins_dir, exist_ok=True)

            # Create prerequisite ViaVersion + candidate jars CoreLib, QueuePlugin (depends on CoreLib), ScanPlugin
            jars_spec = [
                ("ViaVersion-5.0.0.jar", "ViaVersion", [], ["com/viaversion/Via.class"]),
                ("CoreLib.jar", "CoreLib", [], ["com/example/core/CoreService.class"]),
                (
                    "QueuePlugin.jar",
                    "QueuePlugin",
                    ["CoreLib"],
                    ["com/example/queue/RetainedSession.class"],
                ),
                (
                    "ScanPlugin.jar",
                    "ScanPlugin",
                    [],
                    ["com/example/scan/RadiusScanner.class"],
                ),
            ]
            for j_name, p_id, deps, classes in jars_spec:
                with zipfile.ZipFile(os.path.join(plugins_dir, j_name), "w") as zf:
                    dep_str = f"depend: [{', '.join(deps)}]\n" if deps else ""
                    zf.writestr("plugin.yml", f"name: {p_id}\nversion: 1.0\n{dep_str}")
                    for cls_entry in classes:
                        zf.writestr(cls_entry, b"\xca\xfe\xba\xbe")

            jar_index = bfs.JarIndex(plugins_dir)

            # Build merged command tree with folded aliases (`queue` <-> `ff:queue`, `scan` <-> `ff:scan`)
            default_pkt = {
                "rootIndex": 0,
                "nodes": [
                    {"index": 0, "kind": "root", "name": "", "children": [1, 2, 3]},
                    {"index": 1, "kind": "literal", "name": "queue", "executable": True, "children": []},
                    {"index": 2, "kind": "literal", "name": "ff:queue", "executable": True, "children": []},
                    {"index": 3, "kind": "literal", "name": "help", "executable": True, "children": []},
                ],
            }
            op_pkt = {
                "rootIndex": 0,
                "nodes": [
                    {"index": 0, "kind": "root", "name": "", "children": [1, 2, 3, 4, 6]},
                    {"index": 1, "kind": "literal", "name": "queue", "executable": True, "children": []},
                    {"index": 2, "kind": "literal", "name": "ff:queue", "executable": True, "children": []},
                    {"index": 3, "kind": "literal", "name": "help", "executable": True, "children": []},
                    {"index": 4, "kind": "literal", "name": "scan", "executable": False, "children": [5]},
                    {
                        "index": 5,
                        "kind": "argument",
                        "name": "radius",
                        "parser": "brigadier:integer",
                        "properties": {"min": 1, "max": 256},
                        "executable": True,
                        "children": [],
                    },
                    {"index": 6, "kind": "literal", "name": "ff:scan", "executable": False, "children": [7]},
                    {
                        "index": 7,
                        "kind": "argument",
                        "name": "radius",
                        "parser": "brigadier:integer",
                        "properties": {"min": 1, "max": 256},
                        "executable": True,
                        "children": [],
                    },
                ],
            }
            tree = bfs.merge_command_trees({"default": default_pkt, "op": op_pkt})

            baseline_records = [
                {
                    "ep": ep,
                    "node": "help",
                    "follow_up": "repeat",
                    "departures": 0,
                    "deltas": {"cls:net.minecraft.server.ChunkHolder": 5.0},
                    "tick_gaps": [{"ms": 1000, "at": 2000 + ep * 1000}],
                }
                for ep in range(12)
            ]

            records = []
            for ep in range(36):
                is_leak = ep % 3 == 0
                is_stall = ep == 5
                node_name = "queue" if is_leak else ("scan <radius>" if is_stall else "help")
                follow_up = "quit_lt1s" if is_leak else "repeat"
                rec = {
                    "ep": ep,
                    "node": node_name,
                    "follow_up": follow_up,
                    "departures": 1 if is_leak else 0,
                    "deltas": {
                        "cls:com.example.queue.RetainedSession": 180.0 if is_leak else 0.0,
                        "cls:net.minecraft.server.ChunkHolder": 5.0,
                    },
                    "tick_gaps": [{"ms": 4200 if is_stall else 1000, "at": 50000 + ep * 5000}],
                    "thread_dumps": (
                        [
                            '"Server thread" #1 prio=5 os_prio=0 tid=0x01 nid=0x01 runnable\n'
                            "   java.lang.Thread.State: RUNNABLE\n"
                            "\tat com.example.scan.RadiusScanner.busyLoop(RadiusScanner.java:42)\n"
                            "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:100)\n"
                        ]
                        if is_stall
                        else []
                    ),
                    "bots": [
                        {
                            "name": "fz_0",
                            "program": [
                                {
                                    "a": "cmd",
                                    "cmd": "queue" if is_leak else ("scan 256" if is_stall else "help"),
                                    "node": node_name,
                                },
                                {"a": "wait", "ms": 300},
                            ],
                        }
                    ],
                }
                records.append(rec)

            def bisect_cb(cand_prog: list[dict], finding_ctx: dict) -> bool:
                if finding_ctx["oracle"] != "stall":
                    return True
                cmd_str = cand_prog[0]["program"][0]["cmd"]
                toks = cmd_str.split()
                return len(toks) >= 2 and int(toks[1]) >= 49

            def jar_probe_cb(active_jars: list[str], finding_ctx: dict) -> tuple[int, bool]:
                if finding_ctx["oracle"] == "leak":
                    if "QueuePlugin.jar" in active_jars and "CoreLib.jar" not in active_jars:
                        return (3, False)
                    return (3, True) if ("QueuePlugin.jar" in active_jars and "CoreLib.jar" in active_jars) else (0, True)
                if finding_ctx["oracle"] == "stall":
                    return (3, True) if "ScanPlugin.jar" in active_jars else (0, True)
                return (0, True)

            verdict = bfs.evaluate_server_trace(
                records,
                baseline_records,
                tree=tree,
                jar_index=jar_index,
                profile="paper",
                bisect_predicate_fn=bisect_cb,
                probe_fn=jar_probe_cb,
                server_dir=tmp,
            )

            self.assertEqual(verdict["verdict"], "FAIL")
            self.assertEqual(verdict["confirmed_culprits"], ["QueuePlugin.jar", "ScanPlugin.jar"])
            self.assertEqual(len(verdict["findings"]), 2)

            # Findings are ranked with default-tier (player-reachable) first, op-tier second
            f_leak, f_stall = verdict["findings"][0], verdict["findings"][1]
            self.assertEqual(f_leak["oracle"], "leak")
            self.assertEqual(f_leak["operator_mitigation"]["canonical_node"], "queue")
            self.assertIn("ff:queue", f_leak["operator_mitigation"]["aliases"])
            self.assertEqual(f_leak["operator_mitigation"]["min_tier"], "default")
            self.assertEqual(f_leak["author_reproducer"]["blamed_jars"], ["QueuePlugin.jar"])

            self.assertEqual(f_stall["oracle"], "stall")
            self.assertEqual(f_stall["operator_mitigation"]["canonical_node"], "scan")
            self.assertIn("ff:scan", f_stall["operator_mitigation"]["aliases"])
            self.assertEqual(f_stall["operator_mitigation"]["min_tier"], "op")
            self.assertEqual(f_stall["operator_mitigation"]["bisected_bound"], "radius >= 49")
            self.assertEqual(f_stall["author_reproducer"]["blamed_jars"], ["ScanPlugin.jar"])
            self.assertEqual(
                f_stall["author_reproducer"]["shrunk_program"][0]["program"][0]["cmd"],
                "scan 49",
            )
            self.assertIn("radius >= 49", verdict["report_text"])
            self.assertIn("QueuePlugin.jar", verdict["report_text"])
            self.assertIn("ScanPlugin.jar", verdict["report_text"])

    def test_evaluate_server_trace_prerequisites_only_and_shrink_callback(self):
        records = [
            {
                "ep": 0,
                "node": "scan <radius>",
                "follow_up": "repeat",
                "tick_gaps": [{"ms": 4000, "at": 10000}],
                "bots": [
                    {
                        "name": "fz_0",
                        "program": [
                            {"a": "wait", "ms": 100},
                            {"a": "cmd", "cmd": "scan 128", "node": "scan <radius>"},
                            {"a": "wait", "ms": 100},
                        ],
                    }
                ],
            }
        ]
        verdict = bfs.evaluate_server_trace(
            records,
            [],
            shrink_probe_fn=lambda bots: (
                3 if any(a.get("a") == "cmd" for b in bots for a in b.get("program", [])) else 0,
                3,
                any(a.get("a") == "cmd" for b in bots for a in b.get("program", [])),
            ),
            probe_fn=lambda active: (3, True),
        )
        self.assertEqual(verdict["verdict"], "PASS")
        self.assertEqual(verdict["confirmed_culprits"], [])
        self.assertEqual(len(verdict["findings"]), 1)
        f0 = verdict["findings"][0]
        self.assertEqual(f0["attribution_status"], "server")
        self.assertEqual(f0["blamed_jars"], [])
        self.assertEqual(len(f0["author_reproducer"]["shrunk_program"][0]["program"]), 1)


class ActiveServerModeAndPlayerTaxonomyTest(unittest.TestCase):
    """Tests for player-taxonomy bot shapes, anti-abuse gates, --ssh JcmdObserver, and live active ServerRun."""

    def test_player_taxonomy_shapes_build_valid_programs(self):
        pkt = {
            "rootIndex": 0,
            "nodes": [
                {"index": 0, "kind": "root", "name": "", "children": [1]},
                {"index": 1, "kind": "literal", "name": "rtp", "executable": True, "children": []},
            ],
        }
        tree = bfs.merge_command_trees({"default": pkt})
        rng = random.Random(19)

        for shape in ("homesteader_reroll", "explorer_wander", "coop_group", "window_click"):
            self.assertIn(shape, bfs.SCENARIO_SHAPES)
            bots = bfs.build_arm_program("rtp", shape, tree, rng, bot_names=["fz_0", "fz_1"])
            self.assertTrue(len(bots) >= 1)
            actions = [a["a"] for b in bots for a in b["program"]]
            self.assertIn("cmd", actions)
            if shape in ("homesteader_reroll", "explorer_wander", "coop_group"):
                self.assertIn("move", actions)
            if shape == "coop_group":
                self.assertEqual(len(bots), 2)
            if shape == "window_click":
                self.assertIn("click", actions)
                self.assertIn("close_window", actions)

    def test_anti_abuse_host_and_ownership_validation_in_active_mode(self):
        # Allowed bench hosts: loopback, RFC1918 LAN, Tailscale CGNAT, leaf26, bamboo, *.local / *.lan
        for allowed in (
            "127.0.0.1",
            "localhost",
            "::1",
            "10.0.0.5",
            "192.168.1.50",
            "172.16.4.2",
            "100.111.32.47",
            "leaf26",
            "bamboo",
            "bamboo.local",
            "leaf26.lan",
        ):
            ok, reason = bfs.is_allowed_bench_host(allowed)
            self.assertTrue(ok, f"expected {allowed!r} to be allowed, got: {reason}")

        # Rejected public WAN IPs and external domains
        for public_target in ("8.8.8.8", "1.1.1.1", "203.0.113.10", "play.example.com", "mc.hypixel.net"):
            ok, reason = bfs.is_allowed_bench_host(public_target)
            self.assertFalse(ok, f"expected {public_target!r} to be rejected")
            self.assertIn("refusing public", reason)

        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "plugins"), exist_ok=True)
            with open(os.path.join(tmp, "bench-loop.sh"), "w", encoding="utf-8") as fh:
                fh.write("#!/bin/sh\n")
            # Missing rcon.password must fail proof-of-ownership gate in --active mode
            with open(os.path.join(tmp, "server.properties"), "w", encoding="utf-8") as fh:
                fh.write("server-port=25565\nonline-mode=false\n")
            parser = bf.build_parser()
            argv = ["--mode", "server", "--active", "--profile", "paper", "--server-dir", tmp, "--host", "bamboo"]
            args = parser.parse_args(argv)
            with self.assertRaisesRegex(SystemExit, "2"):
                with contextlib.redirect_stderr(io.StringIO()):
                    bfs.validate_server_mode_args(args, parser=parser, argv=argv)

            # With rcon.password present, bamboo + --tiers default,op + --episodes 8 is accepted
            with open(os.path.join(tmp, "server.properties"), "w", encoding="utf-8") as fh:
                fh.write("server-port=25565\nrcon.port=25575\nrcon.password=secret\nonline-mode=false\n")
            argv_ok = [
                "--mode", "server", "--active", "--profile", "paper",
                "--server-dir", tmp, "--host", "bamboo", "--ssh", "bamboo",
                "--tiers", "default,op", "--episodes", "8",
            ]
            args_ok = parser.parse_args(argv_ok)
            prof, deny = bfs.validate_server_mode_args(args_ok, parser=parser, argv=argv_ok)
            self.assertEqual(prof.name, "paper")
            self.assertIn("stop", deny.entries)

            # Public WAN IP is rejected even when rcon.password is present
            argv_wan = [
                "--mode", "server", "--active", "--profile", "paper",
                "--server-dir", tmp, "--host", "8.8.8.8",
            ]
            args_wan = parser.parse_args(argv_wan)
            with self.assertRaisesRegex(SystemExit, "2"):
                with contextlib.redirect_stderr(io.StringIO()):
                    bfs.validate_server_mode_args(args_wan, parser=parser, argv=argv_wan)

    def test_ssh_jcmd_observer_resolves_remote_pid_and_collects_histogram(self):
        calls: list[list[str]] = []
        histo_out = (
            " num     #instances         #bytes  class name (module)\n"
            "-------------------------------------------------------\n"
            "   1:            50           4000  com.example.scan.RadiusScanner\n"
            "Total            50           4000\n"
        )

        def fake_runner(cmd, timeout_s):
            calls.append(cmd)
            if cmd[-1] == "-l":
                return subprocess.CompletedProcess(cmd, 0, stdout="7788 /srv/bamboo/paper.jar\n", stderr="")
            if cmd[-1] == "GC.class_histogram":
                return subprocess.CompletedProcess(cmd, 0, stdout=histo_out, stderr="")
            raise AssertionError(f"unexpected ssh jcmd: {cmd}")

        obs = bfs.JcmdObserver(
            ssh="bamboo",
            remote_server_dir="/srv/bamboo",
            runner=fake_runner,
        )
        self.assertEqual(obs.resolve_pid(), 7788)
        histo = obs.class_histogram()
        self.assertEqual(histo["com.example.scan.RadiusScanner"], (50, 4000))
        self.assertEqual(calls[0][:4], ["ssh", "-o", "BatchMode=yes", "bamboo"])
        self.assertEqual(calls[1], ["ssh", "-o", "BatchMode=yes", "bamboo", "jcmd", "7788", "GC.class_histogram"])

    def test_active_server_run_discovers_tiers_runs_bandit_and_isolates_culprit_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            plugins_dir = os.path.join(tmp, "plugins")
            os.makedirs(plugins_dir, exist_ok=True)
            with open(os.path.join(tmp, "bench-loop.sh"), "w", encoding="utf-8") as fh:
                fh.write("#!/bin/sh\n")
            with open(os.path.join(tmp, "server.properties"), "w", encoding="utf-8") as fh:
                fh.write("server-port=25565\nrcon.port=25575\nrcon.password=secret\nonline-mode=false\n")

            for j_name, p_id, cls_entry in (
                ("ViaVersion-5.0.0.jar", "ViaVersion", "com/viaversion/Via.class"),
                ("ScanPlugin.jar", "ScanPlugin", "com/example/scan/RadiusScanner.class"),
            ):
                with zipfile.ZipFile(os.path.join(plugins_dir, j_name), "w") as zf:
                    zf.writestr("plugin.yml", f"name: {p_id}\nversion: 1.0\n")
                    zf.writestr(cls_entry, b"\xca\xfe\xba\xbe")

            rcon_cmds: list[str] = []

            class FakeDriver:
                def try_cmd(self, cmd: str) -> str:
                    rcon_cmds.append(cmd)
                    if cmd == "list":
                        return "There are 0 of a max of 64 players online:"
                    if cmd == "plugins":
                        enabled = [
                            f[:-4]
                            for f in os.listdir(plugins_dir)
                            if f.endswith(".jar")
                        ]
                        return "Plugins: " + ", ".join(enabled)
                    return "ok"

                def restart_once(self) -> bool:
                    rcon_cmds.append("__restart__")
                    return True

            class FakeActiveSwarm:
                def discover(self, did, tier="default", bot=None, max_ms=15000, timeout_s=None):
                    nodes = [
                        {"index": 0, "kind": "root", "name": "", "children": [1]},
                        {"index": 1, "kind": "literal", "name": "scan", "executable": False, "children": [2]},
                        {
                            "index": 2,
                            "kind": "argument",
                            "name": "radius",
                            "parser": "brigadier:integer",
                            "properties": {"min": 1, "max": 64},
                            "executable": True,
                            "children": [],
                        },
                    ]
                    return (
                        {"ev": "commands", "id": did, "tier": tier, "captured": True, "rootIndex": 0, "nodes": nodes},
                        {"ev": "done", "id": did, "reason": "complete"},
                    )

                def run_episode(self, eid, cmd, bots, max_ms, timeout_s, telemetry=False):
                    scan_enabled = os.path.exists(os.path.join(plugins_dir, "ScanPlugin.jar"))
                    sent_cmds = [
                        a.get("cmd", "")
                        for b in bots
                        for a in (b.get("program") or ())
                        if isinstance(a, dict) and a.get("a") == "cmd"
                    ]
                    stalled = False
                    for c_line in sent_cmds:
                        parts = c_line.split()
                        if scan_enabled and len(parts) >= 2 and parts[0] == "scan" and int(parts[1]) >= 16:
                            stalled = True
                    gap_ms = 4200 if stalled else 1000
                    events = [
                        {"ev": "spawn", "bot": bots[0]["name"]},
                        {"ev": "tick_gap", "bot": bots[0]["name"], "ms": gap_ms, "at": 100000},
                    ]
                    return events, {"ev": "done", "id": eid, "ms": 500, "reason": "complete"}

            histo_out = (
                " num     #instances         #bytes  class name (module)\n"
                "-------------------------------------------------------\n"
                "   1:            10            800  com.example.scan.RadiusScanner\n"
                "Total            10            800\n"
            )
            tdump_out = (
                '"Server thread" #1 prio=5 os_prio=0 tid=0x01 nid=0x01 runnable\n'
                "   java.lang.Thread.State: RUNNABLE\n"
                "\tat com.example.scan.RadiusScanner.busyLoop(RadiusScanner.java:42)\n"
                "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:100)\n"
            )

            obs = bfs.JcmdObserver(
                ssh="bamboo",
                remote_server_dir=tmp,
                clock_ms=lambda: 1000,
                runner=lambda cmd, timeout_s: subprocess.CompletedProcess(
                    cmd,
                    0,
                    stdout=(
                        f"9001 {tmp}/paper.jar\n"
                        if cmd[-1] == "-l"
                        else (histo_out if cmd[-1] == "GC.class_histogram" else tdump_out)
                    ),
                    stderr="",
                ),
            )

            argv = [
                "--mode", "server",
                "--active",
                "--profile", "paper",
                "--server-dir", tmp,
                "--host", "bamboo",
                "--ssh", "bamboo",
                "--tiers", "default,op",
                "--episodes", "6",
                "--baseline-episodes", "2",
                "--finding-n", "2",
                "--finding-k", "2",
                "--out-dir", tmp,
            ]
            args = bf.build_parser().parse_args(argv)
            run_dir = os.path.join(tmp, "active-run")
            runner = bfs.ServerRun(
                args,
                run_dir,
                swarm=FakeActiveSwarm(),
                observer=obs,
                driver=FakeDriver(),
            )
            verdict = runner.run()

            self.assertEqual(verdict["status"], "active_completed")
            self.assertEqual(verdict["verdict"], "FAIL")
            self.assertEqual(verdict["confirmed_culprits"], ["ScanPlugin.jar"])
            self.assertEqual(verdict["findings"][0]["operator_mitigation"]["bisected_bound"], "radius >= 16")
            self.assertTrue(any(c.startswith("op ") for c in rcon_cmds))
            self.assertTrue(any(c.startswith("deop ") for c in rcon_cmds))
            # All candidate jars restored to enabled state after ddmin
            self.assertTrue(os.path.exists(os.path.join(plugins_dir, "ScanPlugin.jar")))

    @unittest.skipUnless(
        NODE and MINEFLAYER and shutil.which("javac") and shutil.which("java") and shutil.which("jcmd"),
        "node, mineflayer, javac, java, or jcmd missing",
    )
    def test_live_constructed_server_cli_with_real_swarm_rcon_and_jvm(self):
        import socket
        import struct
        import threading

        with tempfile.TemporaryDirectory() as tmp:
            plugins_dir = os.path.join(tmp, "plugins")
            src_dir = os.path.join(tmp, "javasrc")
            classes_dir = os.path.join(tmp, "javaclasses")
            os.makedirs(plugins_dir, exist_ok=True)
            os.makedirs(src_dir, exist_ok=True)
            os.makedirs(classes_dir, exist_ok=True)

            with open(os.path.join(tmp, "bench-loop.sh"), "w", encoding="utf-8") as fh:
                fh.write("#!/bin/sh\n")

            # 1. Compile real Java classes for paper.jar, ScanPlugin.jar, SafeUtility.jar, ViaVersion-5.0.0.jar
            scanner_java = os.path.join(src_dir, "RadiusScanner.java")
            with open(scanner_java, "w", encoding="utf-8") as fh:
                fh.write(
                    "package com.example.scan;\n"
                    "public class RadiusScanner {\n"
                    "    public static volatile long sink = 0;\n"
                    "    public static void busyLoop(long untilMs) {\n"
                    "        while (System.currentTimeMillis() < untilMs) {\n"
                    "            sink += 1;\n"
                    "            try { Thread.sleep(5); } catch (InterruptedException ignored) {}\n"
                    "        }\n"
                    "    }\n"
                    "}\n"
                )
            safe_java = os.path.join(src_dir, "SafeHelper.java")
            with open(safe_java, "w", encoding="utf-8") as fh:
                fh.write(
                    "package com.example.safe;\n"
                    "public class SafeHelper {\n"
                    "    public static String ping() { return \"ok\"; }\n"
                    "}\n"
                )
            via_java = os.path.join(src_dir, "Via.java")
            with open(via_java, "w", encoding="utf-8") as fh:
                fh.write("package com.viaversion;\npublic class Via {}\n")
            mc_srv_java = os.path.join(src_dir, "MinecraftServer.java")
            with open(mc_srv_java, "w", encoding="utf-8") as fh:
                fh.write(
                    "package net.minecraft.server;\n"
                    "import java.io.*;\n"
                    "import java.nio.file.*;\n"
                    "import com.example.scan.RadiusScanner;\n"
                    "public class MinecraftServer {\n"
                    "    public static void main(String[] args) throws Exception {\n"
                    "        Thread.currentThread().setName(\"Server thread\");\n"
                    "        Path stallPath = Paths.get(args[0]);\n"
                    "        Path readyPath = Paths.get(args[1]);\n"
                    "        Files.writeString(readyPath, Long.toString(ProcessHandle.current().pid()));\n"
                    "        while (true) {\n"
                    "            tickServer(stallPath);\n"
                    "            Thread.sleep(15);\n"
                    "        }\n"
                    "    }\n"
                    "    public static void tickServer(Path stallPath) {\n"
                    "        try {\n"
                    "            if (Files.exists(stallPath)) {\n"
                    "                String raw = Files.readString(stallPath).trim();\n"
                    "                if (!raw.isEmpty()) {\n"
                    "                    long until = Long.parseLong(raw);\n"
                    "                    if (System.currentTimeMillis() < until) {\n"
                    "                        RadiusScanner.busyLoop(until);\n"
                    "                    }\n"
                    "                }\n"
                    "            }\n"
                    "        } catch (Exception ignored) {}\n"
                    "    }\n"
                    "}\n"
                )

            subprocess.run(
                ["javac", "-d", classes_dir, scanner_java, safe_java, via_java, mc_srv_java],
                check=True,
                timeout=30,
            )

            with zipfile.ZipFile(os.path.join(plugins_dir, "ViaVersion-5.0.0.jar"), "w") as zf:
                zf.writestr("plugin.yml", "name: ViaVersion\nversion: 5.0.0\n")
                zf.write(
                    os.path.join(classes_dir, "com", "viaversion", "Via.class"),
                    "com/viaversion/Via.class",
                )
            with zipfile.ZipFile(os.path.join(plugins_dir, "SafeUtility.jar"), "w") as zf:
                zf.writestr("plugin.yml", "name: SafeUtility\nversion: 1.0\n")
                zf.write(
                    os.path.join(classes_dir, "com", "example", "safe", "SafeHelper.class"),
                    "com/example/safe/SafeHelper.class",
                )
            with zipfile.ZipFile(os.path.join(plugins_dir, "ScanPlugin.jar"), "w") as zf:
                zf.writestr("plugin.yml", "name: ScanPlugin\nversion: 1.0\n")
                zf.write(
                    os.path.join(classes_dir, "com", "example", "scan", "RadiusScanner.class"),
                    "com/example/scan/RadiusScanner.class",
                )
            paper_jar = os.path.join(tmp, "paper.jar")
            with zipfile.ZipFile(paper_jar, "w") as zf:
                zf.write(
                    os.path.join(classes_dir, "net", "minecraft", "server", "MinecraftServer.class"),
                    "net/minecraft/server/MinecraftServer.class",
                )
                zf.write(
                    os.path.join(classes_dir, "com", "example", "scan", "RadiusScanner.class"),
                    "com/example/scan/RadiusScanner.class",
                )

            stall_file = os.path.join(tmp, "stall_until.txt")
            pid_file = os.path.join(tmp, "server.pid")

            # 2. Start real TCP Source-RCON server on an ephemeral port (127.0.0.1:0)
            rcon_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            rcon_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            rcon_sock.bind(("127.0.0.1", 0))
            rcon_sock.listen(16)
            rcon_port = rcon_sock.getsockname()[1]
            rcon_state = {"down_until": 0.0, "stop": False, "restarts": 0, "ops": set()}

            def _rcon_send(conn: socket.socket, req_id: int, ptype: int, body: str) -> None:
                payload = struct.pack("<ii", req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
                conn.sendall(struct.pack("<i", len(payload)) + payload)

            def _rcon_client(conn: socket.socket) -> None:
                with conn:
                    conn.settimeout(2.0)
                    try:
                        while not rcon_state["stop"]:
                            hdr = conn.recv(4)
                            if len(hdr) < 4:
                                break
                            (length,) = struct.unpack("<i", hdr)
                            buf = b""
                            while len(buf) < length:
                                ch = conn.recv(length - len(buf))
                                if not ch:
                                    return
                                buf += ch
                            req_id, ptype = struct.unpack("<ii", buf[:8])
                            body = buf[8:-2].decode("utf-8", errors="replace").strip()
                            if time.time() < rcon_state["down_until"]:
                                return
                            if ptype == 3:
                                if body == "secret":
                                    _rcon_send(conn, req_id, 2, "")
                                else:
                                    _rcon_send(conn, -1, 2, "")
                                    return
                            elif ptype == 2:
                                if body == "list":
                                    _rcon_send(conn, req_id, 0, "There are 0 of a max of 64 players online:")
                                elif body == "plugins":
                                    enabled = []
                                    for fn in sorted(os.listdir(plugins_dir)):
                                        if fn.endswith(".jar"):
                                            enabled.append(fn[:-4])
                                    _rcon_send(conn, req_id, 0, "Plugins: " + ", ".join(enabled))
                                elif body.startswith("op "):
                                    rcon_state["ops"].add(body[3:].strip())
                                    _rcon_send(conn, req_id, 0, "Opped")
                                elif body.startswith("deop "):
                                    rcon_state["ops"].discard(body[5:].strip())
                                    _rcon_send(conn, req_id, 0, "De-opped")
                                elif body == "restart":
                                    rcon_state["restarts"] += 1
                                    rcon_state["down_until"] = time.time() + 0.45
                                    _rcon_send(conn, req_id, 0, "Restarting...")
                                    return
                                else:
                                    _rcon_send(conn, req_id, 0, "ok")
                            else:
                                _rcon_send(conn, req_id, 0, "")
                    except OSError:
                        pass

            def _rcon_accept_loop() -> None:
                rcon_sock.settimeout(0.25)
                while not rcon_state["stop"]:
                    try:
                        conn, _ = rcon_sock.accept()
                    except socket.timeout:
                        continue
                    except OSError:
                        break
                    threading.Thread(target=_rcon_client, args=(conn,), daemon=True).start()

            rcon_thread = threading.Thread(target=_rcon_accept_loop, daemon=True)
            rcon_thread.start()

            # 3. Start real Node.js minecraft-protocol 1.20.1 server on an ephemeral port
            server_js = r"""
const fs = require('fs');
const path = require('path');
const nm = path.join(path.dirname(process.argv[1]), 'node_modules');
const mc = require(path.join(nm, 'minecraft-protocol'));
const mcData = require(path.join(nm, 'minecraft-data'))('1.20.1');
const pluginsDir = process.argv[2];
const stallFile = process.argv[3];

let stallUntil = 0;
const clients = new Set();

const srv = mc.createServer({ 'online-mode': false, host: '127.0.0.1', port: 0, version: '1.20.1' });

setInterval(() => {
  const now = Date.now();
  if (now < stallUntil) return;
  for (const c of clients) {
    try { c.write('update_time', { age: [0, 100], time: [0, 100] }); } catch (e) {}
  }
}, 200).unref();

srv.on('login', (client) => {
  clients.add(client);
  client.on('end', () => clients.delete(client));
  client.on('error', () => clients.delete(client));

  client.write('login', {
    ...mcData.loginPacket,
    entityId: 1,
    isHardcore: false,
    gameMode: 0,
    previousGameMode: -1,
    hashedSeed: [0, 0],
    maxPlayers: 64,
    viewDistance: 2,
    simulationDistance: 2,
    reducedDebugInfo: false,
    enableRespawnScreen: true,
    isDebug: false,
    isFlat: true
  });
  client.write('declare_commands', {
    rootIndex: 0,
    nodes: [
      {
        flags: { command_node_type: 0, has_command: 0, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [1, 3]
      },
      {
        flags: { command_node_type: 1, has_command: 0, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [2],
        extraNodeData: { name: 'scan' }
      },
      {
        flags: { command_node_type: 2, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [],
        extraNodeData: {
          name: 'radius',
          parser: 'brigadier:integer',
          properties: { flags: { min_present: 1, max_present: 1 }, min: 1, max: 64 }
        }
      },
      {
        flags: { command_node_type: 1, has_command: 1, has_redirect_node: 0, has_custom_suggestions: 0 },
        children: [],
        extraNodeData: { name: 'stop' }
      }
    ]
  });
  client.write('position', { x: 0, y: 64, z: 0, yaw: 0, pitch: 0, flags: 0, teleportId: 1 });
  client.write('update_health', { health: 20, food: 20, foodSaturation: 5 });
  client.write('update_time', { age: [0, 100], time: [0, 100] });

  function handleCommand(rawCmd) {
    const clean = String(rawCmd || '').replace(/^\//, '').trim();
    const parts = clean.split(/\s+/);
    if (parts[0] === 'scan' && parts.length >= 2) {
      const r = parseInt(parts[1], 10);
      const scanJar = path.join(pluginsDir, 'ScanPlugin.jar');
      if (fs.existsSync(scanJar) && Number.isFinite(r) && r >= 16) {
        const until = Date.now() + 1150;
        stallUntil = until;
        try { fs.writeFileSync(stallFile, String(until)); } catch (e) {}
      }
    }
  }

  client.on('packet', (data, meta) => {
    if (meta.name === 'chat_command' && data && data.command) {
      handleCommand(data.command);
    } else if ((meta.name === 'chat' || meta.name === 'chat_message') && data && data.message) {
      handleCommand(data.message);
    }
  });
});

srv.on('listening', () => {
  console.log(JSON.stringify({ port: srv.socketServer.address().port }));
});
"""
            jvm_proc = subprocess.Popen(
                ["java", "-Xmx128m", "-cp", paper_jar, "net.minecraft.server.MinecraftServer", stall_file, pid_file],
                cwd=tmp,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            mc_proc = subprocess.Popen(
                [NODE, "-e", server_js, bf.SWARM_JS, plugins_dir, stall_file],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            try:
                deadline = time.time() + 10.0
                while time.time() < deadline and not os.path.exists(pid_file):
                    time.sleep(0.05)
                self.assertTrue(os.path.exists(pid_file), "constructed HotSpot JVM did not write server.pid")

                line = mc_proc.stdout.readline()
                self.assertTrue(line, "constructed MC protocol server did not report port")
                mc_port = int(json.loads(line)["port"])

                with open(os.path.join(tmp, "server.properties"), "w", encoding="utf-8") as fh:
                    fh.write(
                        f"server-port={mc_port}\n"
                        f"rcon.port={rcon_port}\n"
                        "rcon.password=secret\n"
                        "online-mode=false\n"
                    )

                out_dir = os.path.join(tmp, "live-out")
                rc = bf.main([
                    "--mode", "server",
                    "--active",
                    "--profile", "paper",
                    "--server-dir", tmp,
                    "--host", "127.0.0.1",
                    "--port", str(rcon_port),
                    "--mc-port", str(mc_port),
                    "--mc-version", "1.20.1",
                    "--pid-file", pid_file,
                    "--tiers", "default,op",
                    "--episodes", "2",
                    "--baseline-episodes", "1",
                    "--stall-ms", "700",
                    "--finding-n", "1",
                    "--finding-k", "1",
                    "--join-delay-ms", "0",
                    "--episode-max-s", "6",
                    "--out-dir", out_dir,
                ])
                self.assertEqual(rc, 0)

                runs_root = os.path.join(out_dir, "runs")
                run_dirs = [os.path.join(runs_root, d) for d in os.listdir(runs_root)]
                self.assertEqual(len(run_dirs), 1)
                with open(os.path.join(run_dirs[0], "verdict.json"), "r", encoding="utf-8") as fh:
                    verdict = json.load(fh)
                with open(os.path.join(run_dirs[0], "episodes.jsonl"), "r", encoding="utf-8") as fh:
                    ep_lines = [json.loads(line) for line in fh if line.strip()]

                self.assertEqual(verdict["status"], "active_completed")
                self.assertEqual(
                    verdict["verdict"],
                    "FAIL",
                    f"verdict={json.dumps(verdict, indent=2)}\nepisodes={json.dumps(ep_lines, indent=2)}",
                )
                self.assertEqual(verdict["confirmed_culprits"], ["ScanPlugin.jar"])
                self.assertEqual(verdict["findings"][0]["oracle"], "stall")
                self.assertEqual(verdict["findings"][0]["operator_mitigation"]["bisected_bound"], "radius >= 16")
                self.assertIn(
                    "com.example.scan.RadiusScanner.busyLoop",
                    verdict["findings"][0]["author_reproducer"]["evidence"],
                )
                self.assertGreaterEqual(rcon_state["restarts"], 2)
                self.assertTrue(os.path.exists(os.path.join(plugins_dir, "ScanPlugin.jar")))
                self.assertTrue(os.path.exists(os.path.join(plugins_dir, "SafeUtility.jar")))
            finally:
                rcon_state["stop"] = True
                try:
                    rcon_sock.close()
                except OSError:
                    pass
                for p in (mc_proc, jvm_proc):
                    if p.poll() is None:
                        p.terminate()
                        try:
                            p.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            p.kill()
                            p.wait(timeout=5)
                    if p.stdout:
                        p.stdout.close()
                    if p.stderr:
                        p.stderr.close()


class DeclarativeBitAndSurvivalRampTest(unittest.TestCase):
    def test_survival_ramp_rungs_and_100_player_wave_generation(self):
        self.assertEqual(bfs.build_survival_ramp_rungs(100), [10, 25, 50, 75, 100])
        self.assertEqual(bfs.build_survival_ramp_rungs(50), [10, 25, 50])
        self.assertEqual(bfs.build_survival_ramp_rungs(explicit_rungs="10, 40, 100"), [10, 40, 100])

        a = bf.build_parser().parse_args(["--server-dir", ".", "--target-players", "100"])
        self.assertEqual(bf.swarm_bot_cap(a), 100)

        tree = bfs.CommandTree(
            tiers=["default"],
            nodes={
                "rtp": bfs.CommandNode(
                    node_id="rtp",
                    name="rtp",
                    kind="literal",
                    executable=True,
                    min_tier="default",
                    aliases=["rtp"],
                ),
                "claim": bfs.CommandNode(
                    node_id="claim",
                    name="claim",
                    kind="literal",
                    executable=True,
                    min_tier="default",
                    aliases=["claim"],
                ),
            },
            root_children=["rtp", "claim"],
            alias_map={},
            denylist_entries=[],
        )
        wave = bfs.build_ramp_wave_program(100, tree, random.Random(42), bot_prefix="fzr100")
        self.assertEqual(len(wave), 100)
        self.assertEqual(len({b["name"] for b in wave}), 100)
        self.assertTrue(all(len(b["name"]) <= 16 for b in wave))
        all_actions = [a.get("a") for b in wave for a in b["program"]]
        self.assertIn("move", all_actions)
        self.assertIn("cmd", all_actions)
        self.assertIn("quit", all_actions)

    def test_declarative_counterfactuals_bit_report_and_aligned_csv(self):
        import csv

        with tempfile.TemporaryDirectory() as tmp:
            plugins_dir = os.path.join(tmp, "plugins")
            os.makedirs(plugins_dir, exist_ok=True)
            for j_name, p_id, cls_entry, cmd_name in (
                ("ViaVersion-5.0.0.jar", "ViaVersion", "com/viaversion/Via.class", "viaversion"),
                ("FuzzFixturePaper.jar", "FuzzFixture", "io/github/dailystruggle/fuzzfixture/FuzzFixturePlugin.class", "scan"),
                ("BetterRTP-3.6.13.jar", "BetterRTP", "me/SuperRonanCraft/BetterRTP/player/PlayerInfo.class", "rtp"),
            ):
                with zipfile.ZipFile(os.path.join(plugins_dir, j_name), "w") as zf:
                    zf.writestr("plugin.yml", f"name: {p_id}\nversion: 1.0\ncommands:\n  {cmd_name}:\n")
                    zf.writestr(cls_entry, b"\xca\xfe\xba\xbe")

            jar_index = bfs.JarIndex.from_server_dir(tmp, "paper")
            tree = bfs.CommandTree(
                tiers=["default", "op"],
                nodes={
                    "rtp": bfs.CommandNode(
                        node_id="rtp",
                        name="rtp",
                        kind="literal",
                        executable=True,
                        min_tier="default",
                        aliases=["rtp", "betterrtp:rtp"],
                    ),
                    "scan": bfs.CommandNode(
                        node_id="scan",
                        name="scan",
                        kind="literal",
                        executable=False,
                        min_tier="default",
                        aliases=["scan", "ff:scan"],
                        children=["scan <radius>"],
                    ),
                    "scan <radius>": bfs.CommandNode(
                        node_id="scan <radius>",
                        name="radius",
                        kind="argument",
                        parser="brigadier:integer",
                        properties={"min": 1, "max": 128},
                        executable=True,
                        min_tier="default",
                        aliases=["scan <radius>"],
                        _root_id="scan",
                    ),
                },
                root_children=["rtp", "scan"],
                alias_map={"betterrtp:rtp": "rtp", "ff:scan": "scan"},
                denylist_entries=[],
            )

            baseline_records = [
                {
                    "ep": f"base-{i}",
                    "follow_up": "idle",
                    "tick_gaps": [{"ms": 1000.0, "at": 10000 + i * 2000}],
                    "deltas": {},
                }
                for i in range(4)
            ]
            ramp_records = [
                {
                    "players": 25,
                    "tick_gaps": [{"ms": 1020.0, "at": 20000}],
                    "disconnects": 0,
                    "deltas": {},
                },
                {
                    "players": 50,
                    "tick_gaps": [{"ms": 1080.0, "at": 25000}],
                    "disconnects": 0,
                    "deltas": {},
                },
                {
                    "players": 75,
                    "tick_gaps": [{"ms": 3751.0, "at": 30000}],
                    "disconnects": 0,
                    "deltas": {"cls:me.SuperRonanCraft.BetterRTP.player.PlayerInfo": 4.0},
                },
            ]
            records = []
            for ep in range(24):
                is_leak = ep % 3 == 0
                is_stall = ep == 5
                node_name = "rtp" if is_leak else "scan <radius>"
                records.append(
                    {
                        "ep": ep,
                        "node": node_name,
                        "follow_up": "quit_lt1s" if is_leak else "repeat",
                        "departures": 1 if is_leak else 0,
                        "deltas": {
                            "cls:me.SuperRonanCraft.BetterRTP.player.PlayerInfo": 180.0 if is_leak else 0.0,
                        },
                        "tick_gaps": [{"ms": 3751.0 if is_stall else 1010.0, "at": 40000 + ep * 2000}],
                        "thread_dumps": (
                            [
                                '"Server thread" #1 prio=5 os_prio=0 tid=0x01 nid=0x01 runnable\n'
                                "   java.lang.Thread.State: RUNNABLE\n"
                                "\tat io.github.dailystruggle.fuzzfixture.FuzzFixturePlugin.runMainThreadScan(FuzzFixturePlugin.java:88)\n"
                                "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:100)\n"
                            ]
                            if is_stall
                            else []
                        ),
                        "bots": [
                            {
                                "name": "fz_0",
                                "program": [
                                    {
                                        "a": "cmd",
                                        "cmd": "rtp" if is_leak else "scan 128",
                                        "node": node_name,
                                    },
                                    {"a": "quit"} if is_leak else {"a": "wait", "ms": 200},
                                ],
                            }
                        ],
                    }
                )

            verdict = bfs.evaluate_server_trace(
                records,
                baseline_records,
                tree=tree,
                jar_index=jar_index,
                profile="paper",
                stall_ms=1500.0,
                bisect_predicate_fn=lambda prog, ctx: (
                    True
                    if ctx["oracle"] != "stall"
                    else int(prog[0]["program"][0]["cmd"].split()[1]) >= 49
                ),
                probe_fn=lambda active, ctx: (
                    (3, True)
                    if (
                        (ctx["oracle"] == "leak" and "BetterRTP-3.6.13.jar" in active)
                        or (ctx["oracle"] == "stall" and "FuzzFixturePaper.jar" in active)
                    )
                    else (0, True)
                ),
                server_dir=tmp,
                ramp_records=ramp_records,
                target_players=100,
            )

            # 1. Survival envelope
            env = verdict["survival_envelope"]
            self.assertEqual(env["target_players"], 100)
            self.assertEqual(env["max_surviving_players"], 50)
            self.assertEqual(env["first_failing_players"], 75)
            self.assertFalse(env["survived_target"])

            # 2. Declarative counterfactuals
            cfs = verdict["counterfactuals"]
            self.assertEqual(len(cfs), 2)
            cf_stall = next(c for c in cfs if c["oracle"] == "stall")
            self.assertEqual(cf_stall["parameter_boundary"]["argument"], "radius")
            self.assertEqual(cf_stall["parameter_boundary"]["pass_domain"], "[1, 48]")
            self.assertEqual(cf_stall["parameter_boundary"]["fail_domain"], "[49, 128]")
            self.assertEqual(cf_stall["jar_isolation"]["blamed_jars"], ["FuzzFixturePaper.jar"])
            self.assertEqual(cf_stall["jar_isolation"]["with_jar_present"]["status"], "FAIL")
            self.assertEqual(cf_stall["jar_isolation"]["with_jar_disabled"]["status"], "PASS")

            # 3. Main-thread frame accounting & command impact table
            mt = verdict["main_thread_attribution"]
            self.assertEqual(len(mt), 1)
            self.assertEqual(mt[0]["owner_jar"], "FuzzFixturePaper.jar")
            self.assertEqual(mt[0]["share_pct"], 100.0)

            impact = verdict["command_impact_table"]
            self.assertEqual(len(impact), 2)
            self.assertTrue(impact[0]["status"].startswith("FAIL"))

            # 4. BitReport JSON & BitTable aligned CSV
            bit_rep = verdict["bit_report"]
            self.assertEqual(bit_rep["schema"], 1)
            self.assertEqual(bit_rep["health"], "FAILED")
            codes = {f["code"] for f in bit_rep["findings"]}
            self.assertIn("BENCH_SURVIVAL_RAMP_PASS", codes)
            self.assertIn("BENCH_SURVIVAL_RAMP_FAIL", codes)
            self.assertIn("BENCH_STALL_ACUTE", codes)
            self.assertIn("BENCH_LEAK_POST_GC", codes)
            self.assertIn("BENCH_MAIN_THREAD_FRAME", codes)
            self.assertIn("BENCH_CMD_IMPACT", codes)

            parsed_rows = list(csv.DictReader(io.StringIO(verdict["bit_table_csv"]), skipinitialspace=True))
            self.assertGreaterEqual(len(parsed_rows), 6)
            self.assertEqual(
                list(parsed_rows[0].keys()),
                list(bfs.BIT_TABLE_COLUMNS),
            )
            components_seen = {r["component"] for r in parsed_rows}
            self.assertEqual(
                components_seen,
                {"bench.ramp", "bench.attribution", "bench.frames", "bench.surface"},
            )

    def test_competitor_package_attribution_and_isolated_targets_comparison(self) -> None:
        import csv
        import io
        expected_pkgs = {
            "me.SuperRonanCraft.BetterRTP.references.rtpinfo.RandomLocation": "BetterRTP",
            "biz.donvi.jakesRTP.JakesRtpPlugin": "JakesRTP",
            "com.skyblockexp.ezrtp.EzRtpPlugin": "EzRTP",
            "me.nik.ezrtp.storage.Database": "EzRTP",
            "eu.kotori.justRTP.JustRTP": "JustRTP",
            "com.ervan27.advancedrtp.AdvancedRTP": "AdvancedRTP",
            "asygames.asyrtp.AsyRtpPlugin": "AsyRTP",
            "net.chumbucket.sorekillrtp.SorekillRTPPlugin": "SorekillRTP",
        }
        for frame_cls, want_plugin in expected_pkgs.items():
            trace = (
                "java.lang.IllegalStateException: test\n"
                f"\tat {frame_cls}.run(Test.java:10)\n"
                "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:100)\n"
            )
            self.assertEqual(bf.attribute_plugin(trace), want_plugin)

        with tempfile.TemporaryDirectory() as tmp:
            plugins_dir = os.path.join(tmp, "plugins")
            os.makedirs(plugins_dir, exist_ok=True)
            for fname in (
                "LeafRTP-Pro-3.3.1.jar",
                "justRTP-3.7.0.jar",
                "EzRTP-3.4.3.jar.off",
                "JakesRTP-0.14.9.jar",
                "BetterRTP-3.6.13.jar",
                "FuzzFixturePaper.jar",
                "EssentialsX-2.22.1.jar",
                "GriefPrevention.jar",
            ):
                with open(os.path.join(plugins_dir, fname), "wb") as fh:
                    fh.write(b"x")

            roster = bfs._parse_roster_jar_prefixes()
            enabled_ez = bfs._isolate_single_roster_jar(plugins_dir, roster, "ezrtp")
            self.assertEqual(enabled_ez, ["EzRTP-3.4.3.jar"])
            active_jars = sorted(f for f in os.listdir(plugins_dir) if f.endswith(".jar"))
            self.assertEqual(
                active_jars,
                ["EssentialsX-2.22.1.jar", "EzRTP-3.4.3.jar", "GriefPrevention.jar"],
            )

            comp_csv, comp_txt = bfs.format_isolated_targets_comparison(
                {
                    "rtp": {
                        "focused_jar": "LeafRTP-Pro-3.3.1.jar",
                        "verdict": "PASS",
                        "bit_report": {"health": "HEALTHY"},
                        "survival_envelope": {"max_surviving_players": 100, "first_failing_players": None, "rungs": []},
                        "findings": [],
                        "command_impact_table": [{"p95_gap_ms": 1008.0, "max_gap_ms": 1024.0}],
                        "report_text": "verdict: PASS\n",
                    },
                    "ezrtp": {
                        "focused_jar": "EzRTP-3.4.3.jar",
                        "verdict": "FAIL",
                        "bit_report": {"health": "FAILED"},
                        "survival_envelope": {"max_surviving_players": 25, "first_failing_players": 50, "rungs": []},
                        "findings": [{"oracle": "stall", "reproduced": True}],
                        "confirmed_culprits": ["EzRTP-3.4.3.jar"],
                        "command_impact_table": [{"p95_gap_ms": 2100.0, "max_gap_ms": 3800.0}],
                        "report_text": "verdict: FAIL\n",
                    },
                }
            )
            rows = list(csv.DictReader(io.StringIO(comp_csv), skipinitialspace=True))
            self.assertEqual(len(rows), 2)
            self.assertEqual(rows[0]["target"], "rtp")
            self.assertEqual(rows[0]["verdict"], "PASS")
            self.assertEqual(rows[0]["max_surviving_players"], "100")
            self.assertEqual(rows[1]["target"], "ezrtp")
            self.assertEqual(rows[1]["verdict"], "FAIL")
            self.assertEqual(rows[1]["stalls"], "1")
            self.assertIn("CROSS-COMPETITOR DECLARATIVE BIT & SURVIVAL COMPARISON", comp_txt)


class PlatformProfileTest(unittest.TestCase):
    """ADR-122 Decision 6: platform profiles (bukkit/paper/folia vs fabric) in bench_overnight.py and bench_fuzz.py."""

    def test_profile_definitions_and_server_dir_autodetect(self) -> None:
        for name in ("bukkit", "paper", "folia"):
            prof = bf.get_platform_profile(name)
            self.assertEqual(prof.name, name)
            self.assertEqual(prof.family, "bukkit")
            self.assertEqual(prof.jar_subdir, "plugins")
            self.assertEqual(prof.loaded_check_cmd, "plugins")
            self.assertTrue(prof.event_error_oracle)
            self.assertEqual(prof.permission_strategy, "luckperms")

        fabric = bf.get_platform_profile("fabric")
        self.assertEqual(fabric.name, "fabric")
        self.assertEqual(fabric.family, "fabric")
        self.assertEqual(fabric.jar_subdir, "mods")
        self.assertEqual(fabric.loaded_check_cmd, "fabric mods")
        self.assertFalse(fabric.event_error_oracle)
        self.assertEqual(fabric.permission_strategy, "fabric")

        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "mods"))
            os.makedirs(os.path.join(tmp, "config", "StressTestRTP", "runs"))
            detected = bf.get_platform_profile(None, tmp)
            self.assertEqual(detected.name, "fabric")
            self.assertEqual(detected.jar_dir(tmp), os.path.join(tmp, "mods"))
            self.assertEqual(
                detected.harness_runs_dir(tmp, "runs"),
                os.path.join(tmp, "config", "StressTestRTP", "runs"),
            )

    def test_driver_jar_isolation_and_loaded_target_check_on_fabric_and_bukkit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            mods_dir = os.path.join(tmp, "mods")
            os.makedirs(mods_dir)
            for fname in ("LeafRTP-Fabric-3.3.0.jar", "BetterRTP-Fabric-1.0.jar", "fabric-api-0.100.0.jar"):
                with open(os.path.join(mods_dir, fname), "wb") as fh:
                    fh.write(b"jar")

            ns = argparse.Namespace(
                server_dir=tmp,
                platform="fabric",
                isolate=True,
                jars=bf.bo.DEFAULT_JARS,
                names=bf.bo.DEFAULT_NAMES,
                dry_run=False,
            )
            drv = bf.FuzzDriver(ns, "127.0.0.1", 25575, "secret")
            self.assertEqual(drv.plugins_dir(), mods_dir)
            drv.set_jars("rtp")
            self.assertTrue(os.path.exists(os.path.join(mods_dir, "LeafRTP-Fabric-3.3.0.jar")))
            self.assertTrue(os.path.exists(os.path.join(mods_dir, "BetterRTP-Fabric-1.0.jar.off")))
            self.assertTrue(os.path.exists(os.path.join(mods_dir, "fabric-api-0.100.0.jar")))

            # 1) RCON `fabric mods` bullet list reply
            drv.try_cmd = lambda cmd: "- fabric-api 0.100.0\n- leafrtp 3.3.0\n- stresstestrtp 1.0\n" if cmd == "fabric mods" else None
            ok, loaded = drv.jar_set_ok("rtp")
            self.assertTrue(ok)
            self.assertEqual(loaded, "leafrtp")
            ok_other, _ = drv.jar_set_ok("betterrtp")
            self.assertFalse(ok_other)

            # 2) Fallback when `fabric mods` is unknown on vanilla Fabric RCON
            drv.try_cmd = lambda cmd: "Unknown or incomplete command"
            ok_fb, loaded_fb = drv.jar_set_ok("rtp")
            self.assertTrue(ok_fb)
            self.assertEqual(loaded_fb, "leafrtp")

    def test_permission_grant_skips_cleanly_on_fabric_without_luckperms(self) -> None:
        cmds: list[str] = []

        class StubDrv:
            profile = bf.get_platform_profile("fabric")

            def try_cmd(self, cmd: str):
                cmds.append(cmd)
                return "Unknown or incomplete command"

        grant = bf.PermissionGrant(StubDrv(), ["rtp.use", "betterrtp.use"], profile="fabric")
        granted = grant.apply()
        self.assertEqual(granted, [])
        grant.revert()

    def test_log_oracle_scopes_event_error_to_bukkit_and_keeps_stack_trace_packages_on_all(self) -> None:
        event_log = (
            "[12:00:00 ERROR]: Could not pass event PlayerTeleportEvent to BetterRTP v3.6.13\n"
            "java.lang.NullPointerException: boom\n"
            "\tat me.SuperRonanCraft.BetterRTP.player.rtp.RTP.start(RTP.java:42)\n"
            "\tat net.fabricmc.loader.impl.FabricLoaderImpl.Init(FabricLoaderImpl.java:10)\n"
        )
        uncaught_log = (
            "[12:00:01 ERROR]: [Server thread] Unhandled exception in tick\n"
            "java.lang.IllegalStateException: fabric failure\n"
            "\tat net.fabricmc.fabric.api.event.EventFactory.invoke(EventFactory.java:20)\n"
            "\tat custom.fabric.rtpmod.TeleportHandler.tick(TeleportHandler.java:88)\n"
            "\tat net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:900)\n"
        )
        pkgs = bf.plugin_packages("custom.fabric.rtpmod=FabricRTP")

        bukkit_oracle = bf.ServerLogOracle(None, packages=pkgs, target_names={"betterrtp", "fabricrtp"}, profile="paper")
        fabric_oracle = bf.ServerLogOracle(None, packages=pkgs, target_names={"betterrtp", "fabricrtp"}, profile="fabric")

        bukkit_findings = bukkit_oracle.analyze_text(event_log, [{"a": "rtp"}], "betterrtp")
        self.assertEqual(len(bukkit_findings), 1)
        self.assertEqual(bukkit_findings[0]["class"], "EVENT_ERROR")
        self.assertEqual(bukkit_findings[0]["attributed_plugin"], "BetterRTP")

        # On Fabric, `Could not pass event` is not matched as EVENT_ERROR; stack trace still attributes via packages
        fabric_event_findings = fabric_oracle.analyze_text(event_log, [{"a": "rtp"}], "betterrtp")
        self.assertTrue(all(f["class"] != "EVENT_ERROR" for f in fabric_event_findings))

        # Stack-trace `--plugin-packages` attribution is active on both Bukkit and Fabric profiles
        for oracle in (bukkit_oracle, fabric_oracle):
            u_findings = oracle.analyze_text(uncaught_log, [{"a": "rtp"}], "fabricrtp")
            self.assertEqual(len(u_findings), 1)
            self.assertEqual(u_findings[0]["class"], "UNCAUGHT_EXCEPTION")
            self.assertEqual(u_findings[0]["attributed_plugin"], "FabricRTP")
            self.assertIn("custom.fabric.rtpmod.TeleportHandler.tick", u_findings[0]["key"])


if __name__ == "__main__":
    unittest.main()
