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
import re
import shutil
import subprocess
import sys
import tempfile
import time
import types
import unittest
import unittest.mock
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import bench_fuzz as bf  # noqa: E402

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


if __name__ == "__main__":
    unittest.main()
