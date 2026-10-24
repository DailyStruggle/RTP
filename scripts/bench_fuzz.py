#!/usr/bin/env python3
"""Behaviour fuzzer for memory leaks and runtime faults (stdlib only).

Runs short seeded *episodes* of player behaviour against the bench server and
records the exact post-GC count deltas each one causes, without knowing
anything about the plugin under test. One target at a time: every target gets
a restart with only its jar enabled (jar isolation from bench_overnight.py),
then per seed:

  baseline   quick checkpoint (`/rtpstress leakcheck <label> quick`)
  episode    2-8 bots (devstack/clients/bench-swarm.js --fuzz-control) each run
             a seeded program of rtp / wait / move / quit / rejoin / die / rtp2;
             wait until every bot is gone plus --quiet-s, quick checkpoint,
             deltas of every exact count and of the per-class histogram;
             --console adds timed RCON commands (e.g. a plugin reload)
  repeat     --reps - 1 more runs of the same programs (fresh names), a
             checkpoint after each: slope.<metric> = growth per repetition
             of generic resources (live bytes, RSS, metaspace, loaded
             classes, buffers, threads, fds, per-plugin census). A cache
             fills in the first run and flattens; a leak keeps its slope,
             so slopes score (positive excess over the control only) like
             any other count and need no knowledge of what leaks
  trace      one JSON line per episode in <run>/<target>/episodes.jsonl

Programs come from a parameterised generator, or as novelty (fresh samples
from a widened prior) and mutations of the corpus (--novelty, 25 %). After
each --generation episodes a ridge fit per count (delta ~ behaviour
features) gives coefficients with 95 % intervals; a feature is flagged when
its interval excludes 0 and the `direct` control's does not. Every histogram
class is a candidate count: all non-zero class deltas go to classes.jsonl,
and a cumulative screen (growth net of the control's per-episode mean, over
noise x sqrt n) adds the --max-classes most growing classes to the fit, the
flags (Bonferroni z over classes x features) and the score (capped,
positive excess only). Each episode's score is its net leak per minute:
(delta - control prediction) / control noise, summed over the counts. A
cross-entropy method moves the generator's parameters towards the top
--elite episodes (smoothed, variance floor, restart after --stall-gens
without a new best); each seed is an independent restart.

Discovery first, since most targets do not leak: while nothing is confirmed
the search is `searching`, the novelty share rises by --novelty-step per
stalled generation up to --novelty-max, and CEM restarts draw a fresh point
from the prior. An episode that grows a count by --surprise-z noise units
beyond the control is replayed --surprise-n times at once; --surprise-k
reproductions make it a candidate and switch to `exploiting`, as does a
flag. --shrink N delta-debugs candidates to the fewest actions that still
reproduce the count k of n times. Candidates (and the learnt parameters)
climb --rungs (bots x reps) by successive halving (--eta); the top rung is
the confirmation: FAIL when the count grows by >= half the prediction and
heap after GC beyond the control's, with heap dumps and heap_owner.py
(--mat). Without candidates the ladder's budget goes to a fresh seed; a
PASS means no leak found within budget. Per target: episodes.jsonl,
classes.jsonl, optimiser.jsonl, fit.json, surprises.jsonl,
unexplained.jsonl, shrunk.jsonl, ladder.jsonl, verdict.json; the control's
fits are <run>/control*.json (so `direct` should be among --targets); the
summary is <run>/report.txt.

An episode joins the corpus when it reaches a new (previous action, plugin
reply) pair, a new finding signature, changes a count in a way no corpus
episode did, or moves a count its features do not explain (unexplained
finds). Replies are normalised: colour codes stripped, bot names -> {self},
digits -> #.

Finding oracles run beside the leak path on every episode: the server log
(uncaught exceptions with a stack trace, `Could not pass event`, tick stalls)
and permission denials. A finding's signature is its class plus owning plugin,
root exception type and first frame in that plugin (no digits), so repeats
dedupe. A new signature attributed to the target (--names / --jars entry;
--plugin-packages adds package prefixes) and absent from the control's
(<run>/control-findings.json) is replayed --finding-n times, one run each;
--finding-k reproductions confirm it, --finding-shrink delta-debugs it, and a
confirmed finding makes the verdict FAIL with no ladder. Per target:
findings.jsonl (every finding), finding-replays.jsonl; verdict.json lists one
entry per signature and `confirmed_by` (leak and/or oracle names).

The command each bot sends is the only per-target input (`--cmd`): `direct`,
the plugin-free control, maps /rtp to `/rtpstress tp {self}`. Bots get their
permissions from LuckPerms group `default` (`*` or `--grant`); the previous
value of every node is read first and restored on exit (also on Ctrl+C and
SIGTERM); the revert commands are printed at start for a kill -9. LuckPerms
replies asynchronously, so its RCON replies are empty: the previous values
are read from an `lp export` file in plugins/LuckPerms/ (needs --server-dir
to be the server's directory). On exit a ramp this process started is
stopped (`rtpstress stop`) and the swarm's bots disconnect.

Example:
  python3 scripts/bench_fuzz.py --server-dir /mnt/threadripper-dir/26.2 --host 10.0.0.5 \\
      --targets direct,betterrtp,rtp --seeds 1 --episodes 80
  python3 scripts/bench_fuzz.py ... --replay scripts/tmp/runs/fuzz-X/betterrtp/episodes.jsonl --ep 17

MAT for the owner lookup: --mat ~/mat/ParseHeapDump.sh (see heap_owner.py).
"""
from __future__ import annotations

import argparse
import atexit
import csv
import datetime as dt
import gzip
import hashlib
import json
import math
import os
import queue
import random
import re
import signal
import socket
import subprocess
import sys
import threading
import time
from statistics import NormalDist

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench_overnight as bo  # noqa: E402  (RCON driver, restart, jar isolation)
import bench_leakcheck as lc  # noqa: E402  (prepare_target, histogram parsing)

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SWARM_JS = os.path.join(REPO, "devstack", "clients", "bench-swarm.js")
DIRECT = lc.DIRECT
# Per-target command template; {self} is the bot's current name.
# Namespaced (from each jar's plugin.yml) so a stray jar can never answer for another.
DEFAULT_CMDS = {DIRECT: "rtpstress tp {self}", "rtp": "rtp:rtp", "betterrtp": "betterrtp:rtp",
                "huskhomes": "huskhomes:rtp", "justrtp": "justrtp:justrtp", "jakesrtp": "jakesrtp:rtp",
                "ezrtp": "ezrtp:rtp"}
FALLBACK_CMD = "rtp"

# Generator actions; rejoin_same / rejoin_new become {"a":"rejoin","rename":bool}.
ACTIONS = ("rtp", "wait", "move", "quit", "rejoin_same", "rejoin_new", "die", "rtp2")
# Waits inserted right after an rtp, keyed by the action that follows it.
AFTER_RTP = {"quit": "quit_after_rtp_ms", "move": "move_after_rtp_ms", "rtp2": "rtp2_after_rtp_ms"}
# Log-normal waits are (mu, sd) of ln(ms).
DEFAULT_PARAMS = {
    "w_rtp": 1.0, "w_wait": 0.5, "w_move": 0.0, "w_quit": -0.3, "w_rejoin_same": -0.5,
    "w_rejoin_new": -0.5, "w_die": -1.0, "w_rtp2": -0.7,
    "after_rtp_ms": {"mu": math.log(1500), "sd": 1.2},
    "quit_after_rtp_ms": {"mu": math.log(800), "sd": 1.5},
    "move_after_rtp_ms": {"mu": math.log(500), "sd": 1.2},
    "rtp2_after_rtp_ms": {"mu": math.log(300), "sd": 1.2},
    "wait_ms": {"mu": math.log(2000), "sd": 1.0},
}
NOVELTY_SD_SCALE = 1.5
# Swarm limits (bench-swarm.js validation).
MAX_WAIT_MS, MAX_MOVE_MS, MAX_MOVE_D, MAX_PROGRAM, NAME_MAX = 120000, 60000, 64, 500, 16
PROGRAM_CAP = 200
# Planning estimates only (the swarm measures real times).
EST_MS = {"rtp": 50, "rtp2": 50, "quit": 100, "rejoin": 2500, "die": 1500}
SPAWN_EST_MS = 3000

# Counts whose change marks an episode as new for the corpus: exact integers,
# not heap megabytes or timings.
# Kinds of resource only: which objects leak is screened from the histogram.
EXACT_COUNT = re.compile(r"^(threads_live|threads_daemon|fd_open|"
                         r"plugin\.[^.]+\.(pending_tasks|listeners|live_instances))$")
NOT_DELTA = re.compile(lc.NOT_LEAK_METRICS.pattern + r"|^checkpoint_quick$|\.owned_classes$|^old_gen_after_gc_mb$")
ALWAYS_DELTA = ("heap_used_after_gc_mb",)
HARNESS_PLUGIN = "plugin.stresstestrtp."
ATTRIBUTION_METRIC = re.compile(r"^(slope\.)?plugin\.[^.]+\.(pending_tasks|listeners|live_instances)$")


def is_attribution(c: str) -> bool:
    return bool(ATTRIBUTION_METRIC.match(c))
FUZZ_NAME = re.compile(r"^fz", re.IGNORECASE)
LABEL_SAFE = re.compile(r"[^A-Za-z0-9_-]")
COLOR = re.compile(r"(?:\u00a7|&)(?:#[0-9a-f]{6}|[0-9a-fk-orx])", re.IGNORECASE)
NUMBER = re.compile(r"\d+")


def log(msg: str) -> None:
    bo.log(msg)


# --- grammar --------------------------------------------------------------------

def rng_for(seed: int, *parts) -> random.Random:
    """Deterministic across processes: str seeds hash with SHA-512, not hash()."""
    return random.Random(":".join(str(p) for p in (seed,) + parts))


def bot_name(run: str, seed: int, tag, i: int) -> str:
    """<= 12 chars so a rename (base + 'g' + generation) still fits 16. `run`
    (stamp:target) makes names, hence offline UUIDs and saved playerdata,
    unique per run and target: the same (seed, ep) under `direct` and a
    target starts both as new players. Names never feed the program RNG."""
    name = f"fz{seed}x{tag}_{i}"
    if run or len(name) > 12:
        name = "fz" + hashlib.sha1(f"{run}:{seed}:{tag}".encode()).hexdigest()[:7] + f"_{i}"
    return name[:NAME_MAX]


def softmax_choice(rng: random.Random, params: dict, allowed=ACTIONS) -> str:
    ws = [math.exp(max(-30.0, min(30.0, float(params.get("w_" + a, 0.0))))) for a in allowed]
    return rng.choices(list(allowed), weights=ws, k=1)[0]


def draw_ms(rng: random.Random, spec: dict, cap: int = MAX_WAIT_MS) -> int:
    v = rng.lognormvariate(float(spec["mu"]), max(1e-6, float(spec["sd"])))
    return int(max(0, min(cap, round(v))))


def make_action(rng: random.Random, kind: str, params: dict, rtp_args: list[str]) -> dict:
    if kind in ("rtp", "rtp2"):
        args = rng.choice(rtp_args) if rtp_args else ""
        return {"a": kind, "args": args} if args else {"a": kind}
    if kind == "wait":
        return {"a": "wait", "ms": draw_ms(rng, params["wait_ms"])}
    if kind == "move":
        return {"a": "move", "dx": round(rng.uniform(-8, 8), 1), "dz": round(rng.uniform(-8, 8), 1),
                "ms": rng.randint(300, 3000)}
    if kind == "rejoin_same":
        return {"a": "rejoin", "rename": False}
    if kind == "rejoin_new":
        return {"a": "rejoin", "rename": True}
    return {"a": kind}


def action_kind(act: dict) -> str:
    if act["a"] == "rejoin":
        return "rejoin_new" if act.get("rename") else "rejoin_same"
    return act["a"]


def action_ms(act: dict) -> int:
    if act["a"] in ("wait", "move"):
        return int(act.get("ms", 0))
    return EST_MS.get(act["a"], 0)


def planned_ms(program: list[dict]) -> int:
    return sum(action_ms(x) for x in program)


def action_cost(act: dict) -> int:
    """Planned time including the connect-to-spawn estimate of a rejoin."""
    return action_ms(act) + (SPAWN_EST_MS if act["a"] == "rejoin" else 0)


def est_ms(program: list[dict]) -> int:
    """Planned bot time: first join, actions and every rejoin's spawn."""
    return SPAWN_EST_MS + sum(action_cost(x) for x in program)


def gen_program(rng: random.Random, params: dict, duration_ms: int, rtp_args: list[str]) -> list[dict]:
    """Actions within duration_ms of planned time (est_ms, joins included):
    waits and moves are clipped to the time left, and generation stops at the
    first action that does not fit. Offline (after quit) only wait or rejoin
    are drawn; rtp2 only directly after an rtp; the wait between an rtp and
    the next action uses the follower's after-rtp spec."""
    prog: list[dict] = []
    t, online, last = SPAWN_EST_MS, True, None
    while t < duration_ms and len(prog) < PROGRAM_CAP - 2:
        allowed = ACTIONS if online else ("wait", "rejoin_same", "rejoin_new")
        kind = softmax_choice(rng, params, allowed)
        if kind == "rtp2" and last not in ("rtp", "rtp2"):
            kind = "rtp"
        if last in ("rtp", "rtp2") and kind != "wait":
            ms = min(draw_ms(rng, params[AFTER_RTP.get(kind, "after_rtp_ms")]), duration_ms - t)
            prog.append({"a": "wait", "ms": ms})
            t += ms
            if t >= duration_ms:
                break  # the follower no longer fits; a clipped wait is never followed
        act = make_action(rng, kind, params, rtp_args)
        if act["a"] in ("wait", "move"):
            act["ms"] = min(act["ms"], duration_ms - t)
        if t + action_cost(act) > duration_ms:
            break
        prog.append(act)
        t += action_cost(act)
        if kind == "quit":
            online = False
        elif kind.startswith("rejoin"):
            online = True
        last = kind
    return prog


def novelty_params(params: dict) -> dict:
    """Uniform action mix and widened waits: the prior's exploration share."""
    out = {k: (0.0 if k.startswith("w_") else v) for k, v in params.items()}
    for k, v in params.items():
        if isinstance(v, dict):
            out[k] = {"mu": v["mu"], "sd": v["sd"] * NOVELTY_SD_SCALE}
    return out


def gen_episode(seed: int, ep, params: dict, cfg: dict) -> list[dict]:
    """One duration per episode, uniform in [min_s, max_s], shared by every
    bot: the episode lasts as long as its longest bot, so per-bot draws would
    push the mean towards max_s."""
    rng = rng_for(seed, ep, "episode")
    n = rng.randint(cfg["min_bots"], cfg["max_bots"])
    duration = int(rng.uniform(cfg["min_s"], cfg["max_s"]) * 1000)
    bots = []
    for i in range(n):
        prog = gen_program(rng, params, duration, cfg.get("rtp_args") or [])
        bots.append({"name": bot_name(cfg.get("run", ""), seed, ep, i), "program": prog or [{"a": "rtp"}]})
    return bots


def clamp_action(act: dict) -> dict:
    a = dict(act)
    if a["a"] == "wait":
        a["ms"] = int(max(0, min(MAX_WAIT_MS, a.get("ms", 0))))
    elif a["a"] == "move":
        a["ms"] = int(max(0, min(MAX_MOVE_MS, a.get("ms", 1000))))
        for k in ("dx", "dz"):
            a[k] = round(max(-MAX_MOVE_D, min(MAX_MOVE_D, float(a.get(k, 0)))), 1)
    return a


def mutate(rng: random.Random, parent: list[dict], donors: list[list[dict]], params: dict,
           cfg: dict, seed: int, ep) -> tuple[list[dict], list[str]]:
    """1-3 edits of a corpus episode: insert, delete, retime, duplicate a slice,
    swap, splice a donor's tail, add or drop a bot. Names are re-issued for `ep`."""
    bots = [[dict(x) for x in b["program"]] for b in parent] or [[{"a": "rtp"}]]
    ops = []
    for _ in range(rng.randint(1, 3)):
        op = rng.choice(("insert", "delete", "retime", "dup", "swap", "splice", "add_bot", "drop_bot"))
        prog = bots[rng.randrange(len(bots))]
        if op == "insert":
            prog.insert(rng.randint(0, len(prog)), make_action(rng, softmax_choice(rng, params), params,
                                                              cfg.get("rtp_args") or []))
        elif op == "delete" and len(prog) > 1:
            del prog[rng.randrange(len(prog))]
        elif op == "retime":
            timed = [x for x in prog if x["a"] in ("wait", "move")]
            if not timed:
                prog.insert(rng.randint(0, len(prog)), make_action(rng, "wait", params, []))
            else:
                x = rng.choice(timed)
                x["ms"] = int(max(0, x["ms"] * math.exp(rng.gauss(0, 0.8))))
        elif op == "dup" and prog:
            i = rng.randrange(len(prog))
            j = min(len(prog), i + rng.randint(1, 6))
            prog[j:j] = [dict(x) for x in prog[i:j]]
        elif op == "swap" and len(prog) > 1:
            i = rng.randrange(len(prog) - 1)
            prog[i], prog[i + 1] = prog[i + 1], prog[i]
        elif op == "splice" and donors:
            donor = rng.choice(donors)
            if donor:
                cut, dcut = rng.randint(0, len(prog)), rng.randrange(len(donor))
                prog[cut:] = [dict(x) for x in donor[dcut:]]
        elif op == "add_bot" and len(bots) < cfg["max_bots"]:
            bots.append([dict(x) for x in rng.choice(bots)])
        elif op == "drop_bot" and len(bots) > cfg["min_bots"]:
            del bots[rng.randrange(len(bots))]
        else:
            continue
        ops.append(op)
    out = []
    cap_ms = int(cfg["max_s"] * 1000)
    for i, prog in enumerate(bots):
        prog = [clamp_action(x) for x in prog][:PROGRAM_CAP]
        while len(prog) > 1 and est_ms(prog) > cap_ms:
            prog.pop()
        if est_ms(prog) > cap_ms and prog[0]["a"] in ("wait", "move"):
            prog[0]["ms"] = max(0, prog[0]["ms"] - (est_ms(prog) - cap_ms))
        out.append({"name": bot_name(cfg.get("run", ""), seed, ep, i), "program": prog or [{"a": "rtp"}]})
    return out, ops


def plan_episode(seed: int, ep: int, params: dict, corpus: "Corpus", cfg: dict, cem: "Cem | None" = None) -> dict:
    """Origin and bots for (seed, ep). Mutations depend on the corpus, which the
    trace records, so --replay uses the traced programs, not a re-derivation.
    With a CEM, each generator episode draws its own parameters from it
    (rng_for(seed, ep, 'cem')); novelty widens and mutation edits around `params`."""
    r = rng_for(seed, ep, "origin")
    if r.random() < cfg["novelty"]:
        if corpus.entries and r.random() < 0.5:
            parent = corpus.entries[r.randrange(len(corpus.entries))]
            donors = [b["program"] for e in corpus.entries for b in e["bots"]]
            bots, ops = mutate(rng_for(seed, ep, "mutate"), parent["bots"], donors, params, cfg, seed, ep)
            plan = {"origin": "mutation", "parent": parent["ep"], "ops": ops, "bots": bots, "params": None}
        else:
            p = novelty_params(params)
            plan = {"origin": "novelty", "parent": None, "bots": gen_episode(seed, ep, p, cfg), "params": p}
    else:
        p = cem.sample(rng_for(seed, ep, "cem")) if cem is not None else params
        plan = {"origin": "cem", "parent": None, "bots": gen_episode(seed, ep, p, cfg), "params": p}
    console = plan_console(rng_for(seed, ep, "console"), cfg, plan["bots"])
    if console:
        plan["console"] = console
    return plan


def plan_console(rng: random.Random, cfg: dict, bots: list[dict]) -> list[dict]:
    """Console (RCON) actions of one episode: with probability --console-p,
    1..--console-max commands from the target's --console list, each at a
    uniform offset within the episode's planned length (so a reload can land
    while teleports are in flight)."""
    cmds = cfg.get("console") or []
    if not cmds or rng.random() >= cfg.get("console_p", 0.0):
        return []
    span = max([planned_ms(b["program"]) for b in bots] + [1000])
    acts = [{"at_ms": int(rng.uniform(0, span)), "cmd": rng.choice(cmds)}
            for _ in range(rng.randint(1, max(1, cfg.get("console_max", 1))))]
    return sorted(acts, key=lambda x: x["at_ms"])


def command_for(target: str, overrides: dict[str, str]) -> str:
    return overrides.get(target, DEFAULT_CMDS.get(target, FALLBACK_CMD)).lstrip("/")


def parse_console(spec: str | None) -> dict[str, list[str]]:
    """'target=cmd one|cmd two,other=...' -> {target: [commands]} (leading / dropped)."""
    out: dict[str, list[str]] = {}
    for pair in (spec or "").split(","):
        if "=" not in pair:
            continue
        t, cmds = pair.split("=", 1)
        lst = [c.strip().lstrip("/") for c in cmds.split("|") if c.strip().lstrip("/")]
        if t.strip() and lst:
            out.setdefault(t.strip().lower(), []).extend(lst)
    return out


def parse_cmds(spec: str | None) -> dict[str, str]:
    out = {}
    for pair in (spec or "").split(","):
        t, sep, c = pair.partition("=")
        if sep and t.strip() and c.strip():
            out[t.strip().lower()] = c.strip()
    return out


# --- states and corpus ----------------------------------------------------------

def normalise_state(text: str, names=()) -> str:
    s = COLOR.sub("", text or "")
    for n in sorted({n for n in names if n}, key=len, reverse=True):
        s = re.sub(re.escape(n), "{self}", s, flags=re.IGNORECASE)
    s = NUMBER.sub("#", s)
    return re.sub(r"\s+", " ", s).strip()[:160]


def extract_states(events: list[dict], names=(), originals=None) -> list[list[str]]:
    """Ordered unique (previous action, state) pairs. A state is a chat reply
    (`<kind>:<text>`), a kick (`kick:<text>`) or `death`; the previous action is
    the bot's last `act`: `join` before any, `rejoin` under a name that is not
    one of `originals` (a rename; default: every name counts as original)."""
    names = set(names) | {e.get("bot", "") for e in events}
    originals = set(originals) if originals is not None else None
    last: dict[str, str] = {}
    out, seen = [], set()
    for e in events:
        bot, ev = e.get("bot", ""), e.get("ev")
        if ev == "act":
            last[bot] = action_kind(e)
            continue
        if ev == "chat":
            st = f"{e.get('kind') or 'chat'}:{normalise_state(e.get('text', ''), names)}"
        elif ev == "kick":
            st = "kick:" + normalise_state(e.get("text", ""), names)
        elif ev == "death":
            st = "death"
        else:
            continue
        pair = (last.get(bot, "join" if originals is None or bot in originals else "rejoin"), st)
        if pair not in seen:
            seen.add(pair)
            out.append(list(pair))
    return out


def console_states(events: list[dict], names=()) -> list[list[str]]:
    """(console command, normalised reply) pairs: a new subcommand outcome is
    coverage like a new chat reply."""
    out, seen = [], set()
    for e in events:
        if e.get("ev") != "console":
            continue
        pair = ("console " + str(e.get("cmd", "")), "console:" + normalise_state(e.get("text", ""), names))
        if pair not in seen:
            seen.add(pair)
            out.append(list(pair))
    return out


class Corpus:
    """Keep-what's-new: an episode is kept when it reaches an unseen
    (previous action, state) pair, an unseen finding signature, or moves an
    exact count in a direction or by a magnitude no kept episode did. Plain
    'any count changed' keeps nearly every episode, since some plugin's live
    instances always move."""

    def __init__(self, cap: int = 500):
        self.cap = cap
        self.pairs: set[tuple[str, str]] = set()
        self.signatures: set[str] = set()
        self.probes: set[str] = set()
        self.max_up: dict[str, float] = {}
        self.max_down: dict[str, float] = {}
        self.entries: list[dict] = []

    def consider(self, ep, bots: list[dict], pairs: list[list[str]], deltas: dict | None,
                 signatures=(), probes=()) -> dict:
        new_pairs = [p for p in pairs if tuple(p) not in self.pairs]
        new_sigs = list(dict.fromkeys(s for s in signatures or () if s not in self.signatures))
        new_probes = [p for p in dict.fromkeys(probes or ()) if p not in self.probes]
        new_counts = {}
        for k, v in (deltas or {}).items():
            if not EXACT_COUNT.match(k) or k.startswith(HARNESS_PLUGIN) or not v:
                continue
            book = self.max_up if v > 0 else self.max_down
            if abs(v) > book.get(k, 0):
                new_counts[k] = v
        kept = bool(new_pairs or new_counts or new_sigs or new_probes)
        if kept:
            self.pairs.update(tuple(p) for p in new_pairs)
            self.signatures.update(new_sigs)
            self.probes.update(new_probes)
            for k, v in new_counts.items():
                book = self.max_up if v > 0 else self.max_down
                book[k] = abs(v)
            self.entries.append({"ep": ep, "bots": bots})
            if len(self.entries) > self.cap:
                self.entries.pop(0)
        res = {"kept": kept, "new_states": new_pairs, "new_counts": new_counts}
        if new_sigs:
            res["new_findings"] = new_sigs
        if new_probes:
            res["new_probes"] = len(new_probes)
        return res


# --- deltas ---------------------------------------------------------------------

def count_deltas(before: dict, after: dict) -> dict:
    out = {}
    for k, v in after.items():
        if k.startswith("_") or NOT_DELTA.search(k) or not isinstance(v, float):
            continue
        b = before.get(k)
        if not isinstance(b, float) or b < 0 or v < 0:
            continue  # -1 = not measured
        d = v - b
        if d or k in ALWAYS_DELTA:
            out[k] = int(d) if d == int(d) and abs(d) < 1e15 else round(d, 4)
    return out


def class_deltas(h0: dict, h1: dict, top: int = 0) -> dict:
    """Post-GC instance delta of every class that changed (top > 0: only the
    `top` largest by magnitude). Sparse: an absent class moved by 0."""
    ds = []
    for k in set(h0) | set(h1):
        d = h1.get(k, (0, 0))[0] - h0.get(k, (0, 0))[0]
        if d:
            ds.append((k, d))
    ds.sort(key=lambda kv: (-abs(kv[1]), kv[0]))
    return {"cls:" + k: d for k, d in (ds[:top] if top > 0 else ds)}


CLS = "cls:"
CLASSES_FILE = "classes.jsonl"


def split_classes(rec: dict) -> tuple[dict, dict | None]:
    """(trace line without class deltas, classes.jsonl line or None). Class
    deltas go to the sidecar to keep episodes.jsonl small; a record measured
    with a histogram always gets a sidecar line, even with no class moved."""
    ds = rec.get("deltas")
    if ds is None:
        return rec, None
    line = dict(rec)
    line["deltas"] = {k: v for k, v in ds.items() if not k.startswith(CLS)}
    if rec.get("no_histogram"):
        return line, None
    side = {"seed": rec.get("seed"), "ep": rec.get("ep"), "rung": rec.get("rung", 0),
            "d": {k[len(CLS):]: v for k, v in ds.items() if k.startswith(CLS)}}
    return line, side


def _rec_key(r: dict) -> tuple:
    return r.get("seed"), str(r.get("ep")), r.get("rung", 0)


def read_records(tdir: str) -> list[dict]:
    """episodes.jsonl with each record's class deltas from classes.jsonl
    merged back as `cls:` deltas (missing class = 0)."""
    recs = read_trace(os.path.join(tdir, "episodes.jsonl"))
    side = {_rec_key(s): s.get("d") or {} for s in read_trace(os.path.join(tdir, CLASSES_FILE))}
    for r in recs:
        d = side.get(_rec_key(r))
        if d is not None and r.get("deltas") is not None:
            r["deltas"].update({CLS + k: v for k, v in d.items()})
    return recs


# --- features and prediction ----------------------------------------------------

# Per-episode behaviour features (summed over bots): action counts, quits
# bucketed by time since that bot's last /rtp in the session (exclusive
# buckets; quit_no_rtp when there was none), and moves while a teleport is
# pending (after an /rtp, before its forced position packet). The buckets
# partition `quit`, so the fit uses them instead of the total: with both, the
# ridge penalty would split a bucket's effect onto the collinear total.
QUIT_BUCKETS = ((250, "quit_lt250"), (1000, "quit_lt1s"), (3000, "quit_lt3s"), (None, "quit_later"))
FEATURES = ACTIONS + tuple(b for _, b in QUIT_BUCKETS) + ("quit_no_rtp", "move_pending_tp", "console")
FIT_FEATURES = tuple(k for k in FEATURES if k != "quit")
# Counts every score sums over; a control fit adds the counts it measured.
# No class is named: object-level evidence comes from the class screens.
DEFAULT_SCORE_COUNTS = ("threads_live", "threads_daemon", "fd_open")
# Growth per repetition of the same program (--reps): the leak signal that
# does not depend on knowing what leaks. A cache fills in the first run and its
# slope goes to 0; a leak keeps the slope up. Kinds of resource, not objects:
# live bytes, native memory, metaspace and classes (reload / classloader
# leaks), buffers, threads, files, per-plugin census.
SLOPE = "slope."
SLOPE_METRIC = re.compile(r"^(histogram_live_mb|heap_used_after_gc_mb|rss_mb|pool\.metaspace\.used_mb|"
                          r"pool\.compressed_class_space\.used_mb|buffer\.(direct|mapped)\.(used_mb|count)|"
                          r"classes_loaded|threads_live|threads_daemon|fd_open|"
                          r"plugin\.[^.]+\.(pending_tasks|listeners|live_instances))$")
Z95 = 1.96
NOISE_FLOOR = 0.5  # a count that never moved under the control still has integer noise


def scorable(c: str) -> bool:
    """A fixed (non-class) count the fit and score may use; keys from older
    traces or control fits that no longer qualify are dropped."""
    if c.startswith(SLOPE):
        return bool(SLOPE_METRIC.match(c[len(SLOPE):]))
    return c in DEFAULT_SCORE_COUNTS or bool(EXACT_COUNT.match(c))


def quit_bucket(delay_ms: float) -> str:
    for lim, name in QUIT_BUCKETS:
        if lim is None or delay_ms < lim:
            return name
    return QUIT_BUCKETS[-1][1]


def extract_features(rec: dict) -> dict:
    """Features from the swarm's timed events (`t` = ms since episode start).
    The first `tp` after each `spawn` is the login/respawn position, not a
    teleport. Per-bot state is keyed by the current name: a rename starts a
    new session, as does any rejoin."""
    f = {k: 0 for k in FEATURES}
    last_rtp: dict[str, float | None] = {}
    pending: dict[str, bool] = {}
    login_tp: dict[str, bool] = {}
    for e in rec.get("events") or []:
        bot, ev, t = e.get("bot", ""), e.get("ev"), float(e.get("t", 0) or 0)
        if ev == "console":
            f["console"] += 1
        elif ev == "spawn":
            login_tp[bot] = True
        elif ev == "tp":
            if login_tp.get(bot):
                login_tp[bot] = False
            else:
                pending[bot] = False
        elif ev == "end":
            last_rtp[bot], pending[bot] = None, False
        elif ev == "act":
            kind = action_kind(e)
            if kind not in f:
                continue
            f[kind] += 1
            if kind in ("rtp", "rtp2"):
                last_rtp[bot], pending[bot] = t, True
            elif kind == "move" and pending.get(bot):
                f["move_pending_tp"] += 1
            elif kind == "quit" or kind.startswith("rejoin"):
                if kind == "quit":
                    f[quit_bucket(t - last_rtp[bot]) if last_rtp.get(bot) is not None else "quit_no_rtp"] += 1
                last_rtp[bot], pending[bot] = None, False
    return f


def _mat_inv(a: list[list[float]]) -> list[list[float]]:
    """Gauss-Jordan with partial pivoting (ridge keeps the matrix SPD)."""
    n = len(a)
    m = [list(map(float, row)) + [1.0 if i == j else 0.0 for j in range(n)] for i, row in enumerate(a)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(m[r][c]))
        if abs(m[p][c]) < 1e-12:
            raise ValueError("singular matrix")
        m[c], m[p] = m[p], m[c]
        piv = m[c][c]
        m[c] = [x / piv for x in m[c]]
        for r in range(n):
            if r != c and m[r][c]:
                k = m[r][c]
                m[r] = [x - k * y for x, y in zip(m[r], m[c])]
    return [row[n:] for row in m]


def ridge_fit(x: list[list[float]], y: list[float], lam: float = 1.0):
    """Ridge least squares by normal equations; column 0 is the unpenalised
    intercept. Returns (beta, standard errors). The covariance is
    sigma^2 (X'X + lam I)^-1 X'X (X'X + lam I)^-1 with sigma^2 = RSS/(n-p),
    so a feature never varied gets a ~0 coefficient and no false certainty
    from the penalty alone."""
    n, p = len(x), len(x[0])
    xtx = [[sum(x[r][i] * x[r][j] for r in range(n)) for j in range(p)] for i in range(p)]
    a = [row[:] for row in xtx]
    for i in range(1, p):
        a[i][i] += lam
    ainv = _mat_inv(a)
    xty = [sum(x[r][i] * y[r] for r in range(n)) for i in range(p)]
    beta = [sum(ainv[i][k] * xty[k] for k in range(p)) for i in range(p)]
    rss = sum((y[r] - sum(x[r][i] * beta[i] for i in range(p))) ** 2 for r in range(n))
    sigma2 = rss / max(1, n - p)
    mid = [[sum(ainv[i][k] * xtx[k][j] for k in range(p)) for j in range(p)] for i in range(p)]
    cov_diag = [sigma2 * sum(mid[i][k] * ainv[k][i] for k in range(p)) for i in range(p)]
    return beta, [math.sqrt(max(0.0, v)) for v in cov_diag]


def usable(rec: dict, count: str = "") -> bool:
    """Records a fit may use: measured, not excluded; class counts need a histogram."""
    if rec.get("excluded") or rec.get("deltas") is None:
        return False
    return not (count.startswith("cls:") and rec.get("no_histogram"))


def fit_counts(records: list[dict], counts, lam: float = 1.0, min_n: int = 4) -> dict:
    """Per count: coefficient (delta per unit of feature), 95 % interval, the
    intercept (ambient drift per episode) and the count's noise (sd of its
    deltas, floored). Counts with fewer than min_n usable episodes are left out."""
    out = {}
    for c in counts:
        rows = [r for r in records if usable(r, c)]
        if len(rows) < min_n:
            continue
        feats = [r.get("features") or extract_features(r) for r in rows]
        x = [[1.0] + [float(f.get(k, 0)) for k in FIT_FEATURES] for f in feats]
        y = [float(r["deltas"].get(c, 0) or 0) for r in rows]
        beta, se = ridge_fit(x, y, lam)
        mean = sum(y) / len(y)
        sd = math.sqrt(sum((v - mean) ** 2 for v in y) / len(y))
        noise = max(NOISE_FLOOR, sd)
        out[c] = {"n": len(y), "noise": round(noise, 6), "sd": round(sd, 6), "intercept": round(beta[0], 6),
                  "coef": {k: round(beta[i + 1], 6) for i, k in enumerate(FIT_FEATURES)},
                  "ci": {k: [round(beta[i + 1] - Z95 * se[i + 1], 6), round(beta[i + 1] + Z95 * se[i + 1], 6)]
                         for i, k in enumerate(FIT_FEATURES)}}
    return out


def fit_from_records(records: list[dict], lam: float = 1.0, classes=()) -> dict:
    """Fit the fixed exact counts plus the screened `classes` (bare names)."""
    counts = set(DEFAULT_SCORE_COUNTS)
    for r in records:
        for k in (r.get("deltas") or {}):
            if scorable(k) and HARNESS_PLUGIN not in k:
                counts.add(k)
    counts.update(CLS + c for c in classes)
    return fit_counts(records, sorted(counts), lam)


def class_rows(records: list[dict]) -> list[dict]:
    return [r for r in records if usable(r, CLS)]


def class_stats(records: list[dict]) -> dict:
    """{n, classes: {name: [per-episode mean, sd]}} over usable records with a
    histogram, a class absent from a record counting as 0."""
    rows = class_rows(records)
    s: dict[str, float] = {}
    sq: dict[str, float] = {}
    for r in rows:
        for k, v in r["deltas"].items():
            if k.startswith(CLS):
                c = k[len(CLS):]
                s[c] = s.get(c, 0.0) + v
                sq[c] = sq.get(c, 0.0) + v * v
    n = len(rows)
    out = {}
    for c in s:
        mean = s[c] / n
        out[c] = [round(mean, 6), round(math.sqrt(max(0.0, sq[c] / n - mean * mean)), 6)]
    return {"n": n, "classes": out}


def screen_classes(records: list[dict], control: dict | None, max_classes: int = 64,
                   min_n: int = 4) -> tuple[list[dict], int]:
    """(screened classes, classes seen). Cumulative z per class: (sum of its
    deltas - control per-episode mean * n) / (noise * sqrt n). A real leak is
    post-GC growth that accumulates; noise mean-reverts and stays near 0. The
    noise is the control's sd for that class, or the target's own sd for a class
    the control never saw (plugin classes); without any control the mean is 0.
    At most max_classes with the largest positive z."""
    rows = class_rows(records)
    n = len(rows)
    if n < min_n:
        return [], 0
    own = class_stats(rows)["classes"]
    ctl = (control or {}).get("classes") or {}
    out = []
    for c, (mean_t, sd_t) in own.items():
        cum = mean_t * n
        if c in ctl:
            mean, sd = ctl[c]
        else:
            mean, sd = 0.0, sd_t
        sd = max(NOISE_FLOOR, sd)
        z = (cum - mean * n) / (sd * math.sqrt(n))
        if z > 0:
            out.append({"cls": c, "cum": round(cum, 3), "z": round(z, 4), "n": n,
                        "mean": round(mean, 6), "noise": round(sd, 6), "in_control": c in ctl})
    out.sort(key=lambda d: (-d["z"], d["cls"]))
    return out[:max(0, max_classes)], len(own)


def departures(before: dict, after: dict) -> int | None:
    """Player sessions that ended between two checkpoints (the probe's quit
    counter); None when either side did not measure it."""
    b, a = before.get("quits_total"), after.get("quits_total")
    if not isinstance(b, (int, float)) or not isinstance(a, (int, float)) or b < 0 or a < b:
        return None
    return int(a - b)


def departure_slopes(records: list[dict]) -> tuple[dict, int]:
    """({class: (post-GC instances per departed session, standard error)},
    episodes used). Least squares through the origin over histogram episodes
    with at least one departure: a class that something keeps per ended
    session grows with departures whatever its name or platform."""
    rows = [r for r in class_rows(records) if (r.get("departures") or 0) > 0]
    n = len(rows)
    if n < 2:
        return {}, n
    qs = [float(r["departures"]) for r in rows]
    qq = sum(q * q for q in qs)
    names = {k[len(CLS):] for r in rows for k in r["deltas"] if k.startswith(CLS)}
    out = {}
    for c in names:
        ys = [float(r["deltas"].get(CLS + c, 0) or 0) for r in rows]
        b = sum(y * q for y, q in zip(ys, qs)) / qq
        rss = sum((y - b * q) ** 2 for y, q in zip(ys, qs))
        sd = max(NOISE_FLOOR, math.sqrt(rss / (n - 1)))
        out[c] = (b, sd / math.sqrt(qq))
    return out, n


def screen_departures(records: list[dict], control_records: list[dict] | None, max_classes: int = 64,
                      min_n: int = 4) -> list[dict]:
    """Classes whose per-departure growth exceeds the control's by the
    Bonferroni z over every class tested; a class the control never saw is
    compared against 0. At most max_classes, largest z first."""
    own, n = departure_slopes(records)
    if n < min_n:
        return []
    ctl, _ = departure_slopes(control_records or [])
    stats = class_stats([r for r in class_rows(records) if (r.get("departures") or 0) > 0])["classes"]
    zc = bonferroni_z(len(own))
    out = []
    for c, (b, se) in own.items():
        cb, cse = ctl.get(c, (0.0, 0.0))
        z = (b - cb) / math.sqrt(se * se + cse * cse)
        if z > zc:
            out.append({"cls": c, "per_departure": round(b, 4), "control_per_departure": round(cb, 4),
                        "z": round(z, 4), "n": n, "noise": round(max(NOISE_FLOOR, stats.get(c, (0, 0))[1]), 6),
                        "in_control": c in ctl})
    out.sort(key=lambda d: (-d["z"], d["cls"]))
    return out[:max(0, max_classes)]


def bonferroni_z(tests: int, alpha: float = 0.05) -> float:
    """Two-sided z for family-wise alpha over `tests` comparisons."""
    return NormalDist().inv_cdf(1 - alpha / (2 * max(1, tests)))


CONTROL_FILE = "control.json"
CONTROL_CLASSES_FILE = "control-classes.json"
CONTROL_HEAP_FILE = "control-heap.json"
CONTROL_FINDINGS_FILE = "control-findings.json"
FINDING_REPLAYS_FILE = "finding-replays.jsonl"
# Episodes that re-run another episode's programs: never a source of new replays.
RERUN_ORIGINS = ("replay", "shrink", "rung", "finding", "leakrerun")
VERDICT_FILE = "verdict.json"
HEAP = "heap_used_after_gc_mb"
CLASS_TERM_CAP = 5.0  # max score contribution of one class per episode, in noise units


def load_json(path: str):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def save_json(path: str, obj) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, sort_keys=True, indent=1)
    os.replace(tmp, path)


def append_jsonl(path: str, obj) -> None:
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(obj, separators=(",", ":"), sort_keys=True) + "\n")


def action_label(act: dict) -> str:
    """Compact action text for reports: rtp, wait120, move800, rejoin+rename."""
    if act["a"] in ("wait", "move"):
        return f"{act['a']}{int(act.get('ms', 0))}"
    if act["a"] == "rejoin":
        return "rejoin+rename" if act.get("rename") else "rejoin"
    return act["a"] + (f" {act['args']}" if act.get("args") else "")


def read_trace(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as fh:
        return [json.loads(x) for x in fh if x.strip()]


def predict(fit_c: dict | None, feats: dict) -> float:
    if not fit_c:
        return 0.0
    return fit_c.get("intercept", 0.0) + sum(v * feats.get(k, 0) for k, v in fit_c["coef"].items())


def excludes_zero(ci) -> bool:
    return bool(ci) and (ci[0] > 0 or ci[1] < 0)


def significant(coef: float, ci, z: float) -> bool:
    """|coef| beyond z standard errors, the se recovered from the 95 % interval."""
    if not ci:
        return False
    se = (ci[1] - ci[0]) / (2 * Z95)
    return abs(coef) > z * se if se > 0 else coef != 0


def flag_features(fit: dict, control: dict | None, classes_tested: int = 0) -> list[dict]:
    """Features whose interval excludes 0 for the target while the control's
    interval for the same count and feature does not, sorted by |coef|/noise.
    A count missing from the control fit counts as unflagged there. Class
    counts need the Bonferroni z over classes_tested x features: with
    thousands of classes, a 95 % interval excludes 0 somewhere by chance."""
    out = []
    zc = bonferroni_z(max(1, classes_tested) * len(FIT_FEATURES))
    for c, fc in fit.items():
        if is_attribution(c):
            continue
        cc = (control or {}).get(c) or {}
        for k in FIT_FEATURES:
            hit = (significant(fc["coef"][k], fc["ci"].get(k), zc) if c.startswith(CLS)
                   else excludes_zero(fc["ci"].get(k)))
            if hit and not excludes_zero((cc.get("ci") or {}).get(k)):
                out.append({"count": c, "feature": k, "coef": fc["coef"][k], "ci": fc["ci"][k],
                            "control_coef": (cc.get("coef") or {}).get(k), "n": fc["n"]})
    out.sort(key=lambda d: (-abs(d["coef"]) / fit[d["count"]]["noise"], d["count"], d["feature"]))
    return out


def net_leak(feats: dict, deltas: dict, counts, control: dict | None, class_z: float = Z95,
             class_cap: float = CLASS_TERM_CAP, target_fit: dict | None = None) -> float:
    """Sum over counts of (delta - control prediction) / control noise: how many
    control-noise units an episode moved the counts beyond what the plugin-free
    control does for the same behaviour (its intercept is the ambient drift).
    A class adds only its excess beyond class_z, capped: one noisy class cannot
    dominate the score, a newly growing one still lifts it. When live histogram
    byte growth is available, class contributions are capped by the byte-level net
    growth so small objects cannot outscore large byte leaks. Counts absent from
    the control use the target's own spread when available; per-plugin census
    metrics are excluded from leak scoring (attribution only)."""
    total = 0.0
    hist_delta = None
    for hk in ("slope.histogram_live_mb", "histogram_live_mb"):
        if hk in (deltas or {}):
            h_cc = (control or {}).get(hk)
            h_pred = predict(h_cc, feats) if h_cc else 0.0
            hist_delta = max(0.0, float(deltas.get(hk, 0) or 0) - h_pred)
            break

    for c in counts:
        if is_attribution(c):
            continue
        cc = (control or {}).get(c)
        fc = (target_fit or {}).get(c)
        if cc:
            noise = cc.get("sd", cc.get("noise", 1.0))
            pred = predict(cc, feats)
        elif fc:
            noise = fc.get("sd", fc.get("noise", 1.0))
            pred = predict(fc, feats)
        else:
            noise = 1.0
            pred = 0.0

        term = (float((deltas or {}).get(c, 0) or 0) - pred) / max(NOISE_FLOOR, noise)
        if c.startswith(CLS):
            effective_cap = min(class_cap, hist_delta) if hist_delta is not None else class_cap
            term = min(effective_cap, max(0.0, term - class_z))
        elif c.startswith(SLOPE):
            term = max(0.0, term)  # a draining cache is not negative leak
        total += term
    return total


def ls_slope(ys: list[float]) -> float:
    """Least-squares slope of ys against 0, 1, 2, ..."""
    n = len(ys)
    if n < 2:
        return 0.0
    mx, my = (n - 1) / 2.0, sum(ys) / n
    return sum((i - mx) * (y - my) for i, y in enumerate(ys)) / sum((i - mx) ** 2 for i in range(n))


def slope_metrics(d: dict) -> dict:
    """The SLOPE_METRIC values of one checkpoint row (-1 = not measured)."""
    return {k: float(v) for k, v in d.items()
            if isinstance(v, (int, float)) and v >= 0 and SLOPE_METRIC.match(k) and HARNESS_PLUGIN not in k}


def rep_slopes(series: list[dict]) -> dict:
    """`slope.<metric>` -> growth per repetition over the checkpoints after
    each repetition (the baseline is left out: the first run fills caches).
    Only metrics measured at every checkpoint."""
    if len(series) < 2:
        return {}
    keys = set(series[0]).intersection(*series[1:])
    return {SLOPE + k: round(ls_slope([s[k] for s in series]), 4) for k in sorted(keys)}


def episode_ms(rec: dict) -> int:
    """The swarm's measured run time (done.ms), else the harness's wall time."""
    return int((rec.get("done") or {}).get("ms") or rec.get("ms") or 0)


def score_episode(rec: dict, counts, control: dict | None, class_z: float = Z95,
                  target_fit: dict | None = None) -> float | None:
    """Net leak per minute of episode time. Class counts are skipped for a
    record without a histogram (their delta is unknown, not 0)."""
    ms = episode_ms(rec)
    if not usable(rec) or ms <= 0:
        return None
    if rec.get("no_histogram"):
        counts = [c for c in counts if not c.startswith(CLS)]
    feats = rec.get("features") or extract_features(rec)
    return round(net_leak(feats, rec["deltas"], counts, control, class_z, target_fit=target_fit) / (ms / 60000.0), 6)


def unexplained_counts(rec: dict, fit: dict, z: float = 3.0, class_z: float | None = None) -> dict:
    """Counts an episode moved by more than z noise (and at least 1) beyond
    what the target's own fit predicts from its features; class counts use
    max(z, class_z) (multiple-comparison corrected)."""
    if not usable(rec):
        return {}
    feats = rec.get("features") or extract_features(rec)
    out = {}
    for c, fc in fit.items():
        if not usable(rec, c):
            continue
        zz = max(z, class_z or z) if c.startswith(CLS) else z
        resid = float(rec["deltas"].get(c, 0) or 0) - predict(fc, feats)
        if abs(resid) >= max(1.0, zz * fc["noise"]):
            out[c] = round(resid, 3)
    return out


SURPRISE_FLOOR = 0.25  # an exact count the control never moved: one object is already surprising


def surprises(rec: dict, counts, ref: dict | None, fit: dict | None, z: float,
              class_z: float = Z95) -> dict:
    """count -> growth beyond the control's prediction, for counts the episode
    grew by at least z noise units (and one object) beyond the control, or
    beyond the target's own fit once it has 8 episodes (unexplained). Growth
    only: a leak never shrinks a count. Class counts need max(z, class_z).
    Counts absent from the control must be evaluated against the target's own
    spread once enough episodes exist; attribution counts are skipped."""
    if not usable(rec):
        return {}
    feats = rec.get("features") or extract_features(rec)
    out = {}
    for c in sorted(set(counts) | set(fit or {})):
        if not usable(rec, c) or is_attribution(c):
            continue
        zz = max(z, class_z) if c.startswith(CLS) else z
        d = float(rec["deltas"].get(c, 0) or 0)
        cc = (ref or {}).get(c)
        fc = (fit or {}).get(c)
        net = d - predict(cc, feats)
        noise = max(SURPRISE_FLOOR, cc.get("sd", cc["noise"])) if cc else (
            max(SURPRISE_FLOOR, fc.get("sd", fc["noise"])) if fc and fc.get("n", 0) >= 8 else 1.0)
        hit = net >= max(1.0, zz * noise)
        if not hit and fc and fc.get("n", 0) >= 8:
            hit = d - predict(fc, feats) >= max(1.0, zz * max(SURPRISE_FLOOR, fc.get("sd", fc["noise"])))
        if hit:
            out[c] = round(net, 3)
    return out


class SearchState:
    """Explore/exploit switch driven by evidence, not time. The base rate is
    no leak, so a run starts `searching`: every generation without a new best
    score raises the novelty share by `step` up to `cap`, and CEM restarts
    draw a fresh mean from the prior. A reproduced surprise or a flagged
    feature/class switches to `exploiting` (novelty back to `base`, CEM
    narrows on the signal). Never switches back."""

    def __init__(self, base: float = 0.25, cap: float = 0.6, step: float = 0.1):
        self.base, self.cap, self.step = base, max(base, cap), step
        self.state, self.novelty, self.reason = "searching", base, None
        self.best: float | None = None
        self.stalls = 0

    @property
    def searching(self) -> bool:
        return self.state == "searching"

    def generation(self, best: float | None) -> bool:
        """Record a generation's best score; True when it improved."""
        improved = best is not None and (self.best is None or best > self.best + 1e-9)
        if improved:
            self.best = best
        elif self.searching:
            self.stalls += 1
            self.novelty = round(min(self.cap, self.novelty + self.step), 6)
        return improved

    def confirm(self, reason: str) -> bool:
        if not self.searching:
            return False
        self.state, self.novelty, self.reason = "exploiting", self.base, reason
        return True


# --- optimiser (cross-entropy method over generator parameters) ------------------

PARAM_SD_MIN = 0.2  # generator log-normal sd floor: waits never collapse to one value
PARAM_MU_RANGE = (math.log(5), math.log(MAX_WAIT_MS))


def flatten_params(params: dict) -> dict:
    out = {}
    for k in sorted(params):
        v = params[k]
        if isinstance(v, dict):
            out[k + ".mu"], out[k + ".sd"] = float(v["mu"]), float(v["sd"])
        else:
            out[k] = float(v)
    return out


def unflatten_params(flat: dict, template: dict) -> dict:
    out = {}
    for k, v in template.items():
        if isinstance(v, dict):
            mu = min(PARAM_MU_RANGE[1], max(PARAM_MU_RANGE[0], flat.get(k + ".mu", v["mu"])))
            out[k] = {"mu": round(mu, 6), "sd": round(max(PARAM_SD_MIN, flat.get(k + ".sd", v["sd"])), 6)}
        else:
            out[k] = round(max(-30.0, min(30.0, flat.get(k, v))), 6)
    return out


def cem_spread(key: str) -> tuple[float, float]:
    """(prior sd, floor) of the search distribution per parameter kind."""
    if key.endswith(".sd"):
        return 0.4, 0.1
    if key.endswith(".mu"):
        return 0.8, 0.25
    return 1.0, 0.3


class Cem:
    """Diagonal-Gaussian CEM: each generation the elite (top elite_frac by
    score) refit mean and sd, blended with the old ones by alpha, sd floored.
    The elite is drawn from the scored samples of the last `memory`
    generations: with ~6 generator episodes per generation an elite of one
    noisy score would steer alone. After `stall` generations without a new
    best the sd is widened back to the prior (a restart around the current
    mean). Sampling uses caller-supplied RNGs (rng_for(seed, ep, 'cem')), so a
    run is reproducible from the seed."""

    def __init__(self, prior: dict, elite_frac: float = 0.2, alpha: float = 0.7, stall: int = 3,
                 memory: int = 2):
        self.template = prior
        self.mean = flatten_params(prior)
        self.mean0 = dict(self.mean)
        self.sd0 = {k: cem_spread(k)[0] for k in self.mean}
        self.floor = {k: cem_spread(k)[1] for k in self.mean}
        self.sd = dict(self.sd0)
        self.elite_frac, self.alpha, self.stall, self.memory = elite_frac, alpha, stall, max(1, memory)
        self.pool: list[list[tuple[dict, float]]] = []
        self.best: float | None = None
        self.stall_n = 0
        self.gen = 0

    def params(self) -> dict:
        return unflatten_params(self.mean, self.template)

    def sample(self, rng: random.Random) -> dict:
        return unflatten_params({k: rng.gauss(self.mean[k], self.sd[k]) for k in sorted(self.mean)},
                                self.template)

    def update(self, samples: list[dict], scores: list[float], fresh: random.Random | None = None) -> dict:
        """samples: the generator params each scored episode used. With
        `fresh`, a stall restart re-draws the mean from the prior (a new random
        point, for discovery) instead of widening around the current mean."""
        if not scores:
            return {"elite_scores": [], "best_score": None, "restarted": False, "elite": 0}
        self.pool = (self.pool + [[(flatten_params(s), float(v)) for s, v in zip(samples, scores)]])[-self.memory:]
        pool = [x for g in self.pool for x in g]
        order = sorted(range(len(pool)), key=lambda i: (-pool[i][1], i))
        n_elite = max(1, int(round(self.elite_frac * len(pool))))
        elite = [pool[i][0] for i in order[:n_elite]]
        for k in self.mean:
            vals = [f.get(k, self.mean[k]) for f in elite]
            mu = sum(vals) / len(vals)
            sd = math.sqrt(sum((v - mu) ** 2 for v in vals) / len(vals))
            self.mean[k] = self.alpha * mu + (1 - self.alpha) * self.mean[k]
            self.sd[k] = max(self.floor[k], self.alpha * sd + (1 - self.alpha) * self.sd[k])
        best = max(scores)
        restarted = False
        if self.best is None or best > self.best + 1e-9:
            self.best, self.stall_n = best, 0
        else:
            self.stall_n += 1
            if self.stall_n >= self.stall:
                if fresh is not None:
                    drawn = {k: fresh.gauss(self.mean0[k], self.sd0[k]) for k in sorted(self.mean0)}
                    self.mean = flatten_params(unflatten_params(drawn, self.template))
                    self.sd = dict(self.sd0)
                    self.pool = []  # the old elite would pull the fresh point back
                else:
                    self.sd = {k: max(self.sd[k], self.sd0[k]) for k in self.sd}
                self.stall_n, restarted = 0, ("fresh" if fresh is not None else "widen")
        self.gen += 1
        return {"elite_scores": [round(pool[i][1], 6) for i in order[:n_elite]], "best_score": round(best, 6),
                "restarted": restarted, "elite": n_elite, "pool": len(pool)}

    def state(self) -> dict:
        return {"mean": {k: round(v, 6) for k, v in self.mean.items()},
                "sd": {k: round(v, 6) for k, v in self.sd.items()}, "best": self.best, "stall": self.stall_n}


# --- shrinking ------------------------------------------------------------------

def ddmin(items: list, test) -> list:
    """Zeller's delta debugging: a 1-minimal sublist (order kept) for which
    test() still holds; test(items) is assumed to hold."""
    cur, n = list(items), 2
    while len(cur) >= 2:
        size = max(1, len(cur) // n)
        chunks = [cur[i:i + size] for i in range(0, len(cur), size)]
        for ch in chunks:
            if len(ch) < len(cur) and test(ch):
                cur, n = ch, 2
                break
        else:
            for i in range(len(chunks)):
                comp = [x for j, ch in enumerate(chunks) if j != i for x in ch]
                if comp and test(comp):
                    cur, n = comp, max(n - 1, 2)
                    break
            else:
                if n >= len(cur):
                    break
                n = min(len(cur), n * 2)
    return cur


def regroup(items: list[tuple[int, dict]], names: list[str]) -> list[dict]:
    """(bot index, action) items back into bots; a bot left without actions is dropped."""
    progs: dict[int, list[dict]] = {}
    for bi, act in items:
        progs.setdefault(bi, []).append(dict(act))
    return [{"name": names[bi], "program": progs[bi]} for bi in sorted(progs)]


def shrink_bots(bots: list[dict], probe) -> dict:
    """Smallest set of actions (across bots) that probe() still reproduces.
    probe(bots) -> (hits, n, passed); results are cached per candidate so a
    candidate is replayed at most once. Returns the minimal bots, the k of n
    of the final candidate and every probe tried."""
    names = [b["name"] for b in bots]
    items = [(bi, dict(a)) for bi, b in enumerate(bots) for a in b["program"]]
    tried: dict[str, tuple[int, int, bool]] = {}

    def key(sub):
        return json.dumps([[bi, a] for bi, a in sub], sort_keys=True)

    def test(sub):
        k = key(sub)
        if k not in tried:
            tried[k] = tuple(probe(regroup(sub, names)))
        return tried[k][2]

    if not test(items):
        hits, n, _ = tried[key(items)]
        return {"bots": bots, "hits": hits, "n": n, "reproduced": False, "probes": len(tried),
                "actions": len(items), "of": len(items)}
    best = ddmin(items, test)
    hits, n, _ = tried[key(best)]
    return {"bots": regroup(best, names), "hits": hits, "n": n, "reproduced": True, "probes": len(tried),
            "actions": len(best), "of": len(items)}


def required_effect(rec: dict, count: str, control: dict | None, frac: float = 0.5,
                    target_fit: dict | None = None) -> float:
    """Net effect a replay must reach to count as a reproduction: half the
    original episode's control-subtracted delta, and at least 1 (one object),
    so ambient drift alone (the control intercept) never reproduces. When
    absent from the control, target_fit provides the baseline prediction."""
    feats = rec.get("features") or extract_features(rec)
    ref = (control or {}).get(count) or (target_fit or {}).get(count)
    net = float((rec.get("deltas") or {}).get(count, 0) or 0) - predict(ref, feats)
    return max(1.0, frac * net)


# --- fidelity ladder and verdict ------------------------------------------------

def parse_rungs(spec: str) -> list[tuple[int, int]]:
    """'4x1,12x3,48x10' -> [(bots, reps), ...]; bots within the swarm's 2..64."""
    out = []
    for part in (spec or "").split(","):
        part = part.strip().lower()
        if not part:
            continue
        b, _, r = part.partition("x")
        bots, reps = int(b), int(r or 1)
        if not (2 <= bots <= 64 and reps >= 1):
            raise ValueError(f"bad rung '{part}': need 2 <= bots <= 64 and reps >= 1")
        out.append((bots, reps))
    if not out:
        raise ValueError("no rungs")
    return out


def scale_bots(bots: list[dict], n: int, run: str, seed: int, tag) -> list[dict]:
    """n bots cycling through the candidate's programs, under fresh names."""
    progs = [b["program"] for b in bots] or [[{"a": "rtp"}]]
    return [{"name": bot_name(run, seed, tag, i), "program": [dict(x) for x in progs[i % len(progs)]]}
            for i in range(n)]


def interval(vals: list[float]) -> dict:
    """Mean and 95 % interval of per-repetition nets (no interval below 2)."""
    n = len(vals)
    if not n:
        return {"n": 0, "mean": None, "ci": None}
    mean = sum(vals) / n
    if n < 2:
        return {"n": 1, "mean": round(mean, 6), "ci": None}
    se = math.sqrt(sum((v - mean) ** 2 for v in vals) / (n - 1) / n)
    return {"n": n, "mean": round(mean, 6), "ci": [round(mean - Z95 * se, 6), round(mean + Z95 * se, 6)]}


def holds(res: dict) -> bool:
    """A rung keeps a candidate's signal: the interval of its per-rep nets
    excludes 0 upwards, or with one rep the net reaches half the prediction
    (and one object)."""
    if not res.get("n"):
        return False
    if res.get("ci"):
        return res["ci"][0] > 0
    return res["mean"] >= max(1.0, 0.5 * (res.get("pred_mean") or 0))


def promote(results: list[dict], eta: int) -> list[dict]:
    """Successive halving: of the candidates whose signal holds, the top
    ceil(len/eta) by mean net in noise units."""
    ok = [r for r in results if holds(r)]
    ok.sort(key=lambda r: (-(r["mean"] / max(NOISE_FLOOR, r.get("noise") or 1.0)), str(r["cand"])))
    return ok[:max(1, math.ceil(len(results) / max(1, eta)))] if ok else []


def confirm_rule(count_growth: float, predicted: float, heap_net: float, heap_noise: float, reps: int) -> dict:
    """Top-rung pass/fail for one candidate: the count grew by at least half
    the predicted amount over the reps (net of the control) and heap after GC
    grew beyond the control's for the same behaviour (by more than its 95 %
    noise over the reps)."""
    count_ok = predicted > 0 and count_growth >= 0.5 * predicted
    heap_ok = heap_net > Z95 * max(NOISE_FLOOR, heap_noise) * math.sqrt(max(1, reps))
    return {"count_ok": count_ok, "heap_ok": heap_ok, "fail": count_ok and heap_ok,
            "count_growth": round(count_growth, 3), "predicted": round(predicted, 3),
            "heap_net_mb": round(heap_net, 3)}


def episodes_per_hour(executed: int, explore_s: float) -> float | None:
    """Executed planned episodes per wall-clock hour of exploration. The clock is the
    run_seed loop: episodes, surprise and finding replays, leak re-runs and generation
    refits. Ladder climbs, shrinks and the direct control are not in it. None if untimed."""
    return round(executed * 3600 / explore_s, 2) if explore_s > 0 else None


def target_verdict(searching: bool, confirmed: list[dict], had_candidates: bool, findings=()) -> str:
    """FAIL: a top-rung leak confirmation or a reproduced target finding.
    PASS: the whole budget ran without any signal (nothing found within
    budget, not proof of none). INCONCLUSIVE: a signal that did not confirm
    (a leak candidate lost at scale, a target finding that did not reproduce).
    `findings` are summarise_findings() entries."""
    if any(c["fail"] for c in confirmed) or any(f.get("reproduced") for f in findings):
        return "FAIL"
    signal = had_candidates or any(f.get("target_attributed") and not f.get("in_control") for f in findings)
    if searching and not signal:
        return "PASS"
    return "INCONCLUSIVE"


def heap_stop_mb(a, server_dir: str) -> float:
    """--heap-stop-mb, else --heap-stop-frac of -Xmx read from start.sh/start.bat."""
    if a.heap_stop_mb:
        return a.heap_stop_mb
    for name in ("start.sh", "start.bat"):
        try:
            with open(os.path.join(server_dir, name), encoding="utf-8", errors="replace") as fh:
                m = re.search(r"-Xmx(\d+)([gGmM])", fh.read())
        except OSError:
            continue
        if m:
            mb = int(m.group(1)) * (1024 if m.group(2).lower() == "g" else 1)
            return mb * a.heap_stop_frac
    return 0.0


# --- server-side run files ------------------------------------------------------

class CsvTail:
    """Rows appended to a CSV since construction (from_end) or since the start."""

    def __init__(self, path: str, from_end: bool = True):
        self.path, self.header, self.offset, self.buf = path, None, 0, b""
        if from_end and os.path.exists(path):
            self._header()
            self.offset = max(self.offset, os.path.getsize(path))

    def _header(self) -> None:
        with open(self.path, "rb") as fh:
            line = fh.readline()
        if line.endswith(b"\n"):
            self.header = next(csv.reader([line.decode("utf-8", "replace").rstrip("\r\n")]))
            self.offset = max(self.offset, len(line))

    def poll(self) -> list[dict]:
        if not os.path.exists(self.path):
            return []
        if self.header is None:
            self._header()
            if self.header is None:
                return []
        size = os.path.getsize(self.path)
        if size < self.offset:  # truncated or replaced
            self.offset, self.buf, self.header = 0, b"", None
            return self.poll()
        if size == self.offset:
            return []
        with open(self.path, "rb") as fh:
            fh.seek(self.offset)
            data = fh.read(size - self.offset)
        self.offset = size
        lines = (self.buf + data).split(b"\n")
        self.buf = lines.pop()
        rows = []
        for raw in lines:
            raw = raw.rstrip(b"\r")
            if not raw:
                continue
            vals = next(csv.reader([raw.decode("utf-8", "replace")]))
            rows.append(dict(zip(self.header, vals)))
        return rows


class RunsReader:
    """Checkpoint values by label from runs/leak.csv + runs/leak-metrics.csv."""

    def __init__(self, runs_dir: str):
        self.runs_dir = runs_dir
        self.leak = CsvTail(os.path.join(runs_dir, "leak.csv"))
        self.metrics = CsvTail(os.path.join(runs_dir, "leak-metrics.csv"))
        self.rows: dict[str, dict] = {}
        self.extra: dict[str, dict] = {}

    def _pump(self) -> None:
        for r in self.leak.poll():
            d: dict = {"_epoch": int(r.get("epoch_ms") or 0), "_histo": r.get("histogram_file", "")}
            for k, v in r.items():
                if k in ("label", "histogram_file", "epoch_ms"):
                    continue
                try:
                    d[k] = float(v)
                except (TypeError, ValueError):
                    pass
            self.rows[r.get("label", "")] = d
        for r in self.metrics.poll():
            try:
                self.extra.setdefault(r.get("label", ""), {})[r["metric"]] = float(r["value"])
            except (KeyError, TypeError, ValueError):
                pass

    def fetch(self, label: str, wait_s: float, poll_s: float = 0.25) -> dict | None:
        """leak.csv row merged with its metrics rows; None if absent after wait_s."""
        end = time.time() + wait_s
        while True:
            self._pump()
            if label in self.rows and label in self.extra:
                time.sleep(poll_s)  # metrics rows of one checkpoint may span two writes
                self._pump()
                d = dict(self.rows.pop(label))
                d.update(self.extra.pop(label))
                return d
            if time.time() >= end:
                return None
            time.sleep(poll_s)

    def histo(self, d: dict) -> dict:
        return lc.read_histo(os.path.join(self.runs_dir, d["_histo"])) if d.get("_histo") else {}


def quick_checkpoint(drv, label: str, limit_s: float, poll_s: float = 0.5, mode: str = "quick") -> str | None:
    """Start `leakcheck <label> <mode>` (quick, or dump: full checkpoint plus a
    heap dump); None when done, else the failure text."""
    before = lc.completed(drv)
    if before < 0:
        return "leakcheck unavailable (StressTestRTP without LeakProbe?)"
    r = drv.try_cmd(f"rtpstress leakcheck {label} {mode}") or ""
    if "started" not in r:
        return f"not started: {r.strip()}"
    end = time.time() + limit_s
    while time.time() < end:
        time.sleep(poll_s)
        s = drv.try_cmd("rtpstress leakcheck status")
        if s is None:
            continue
        m = re.search(r"completed=(\d+)", s)
        if m and int(m.group(1)) > before and "busy=false" in s:
            last = s.split("|", 1)[-1].strip()
            return None if label in last and "failed" not in last else f"checkpoint failed: {last}"
    return "timed out waiting for leakcheck"


# --- coverage (ADR-111 section 2.6) ----------------------------------------------

def coverage_probes(data: dict) -> set[str]:
    """`class#index` keys of the reached JaCoCo probes in one
    `/rtpstress coverage` dump. Probes are booleans, so a key is reached or not."""
    out: set[str] = set()
    for cls, idxs in ((data or {}).get("classes") or {}).items():
        for i in idxs or []:
            out.add(f"{cls}#{int(i)}")
    return out


class CoverageReader:
    """Reads the stdlib JSON written by `/rtpstress coverage <label>` from the
    runs folder. Keeps the reached probes seen so far, so a probe is new once."""

    def __init__(self, runs_dir: str):
        self.runs_dir = runs_dir
        self.seen: set[str] = set()

    def fetch(self, label: str, wait_s: float, poll_s: float = 0.25) -> set[str] | None:
        """Reached probes of one dump; None if the file is absent after wait_s."""
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", label)
        path = os.path.join(self.runs_dir, f"coverage-{safe}.json")
        end = time.time() + wait_s
        while True:
            if os.path.exists(path):
                try:
                    with open(path, encoding="utf-8") as f:
                        return coverage_probes(json.load(f))
                except (OSError, ValueError):
                    pass  # still being written: retry until the deadline
            if time.time() >= end:
                return None
            time.sleep(poll_s)

    def new_probes(self, probes: set[str]) -> list[str]:
        """Probes not seen in any earlier dump, marked seen."""
        new = sorted(probes - self.seen)
        self.seen.update(new)
        return new


# --- permissions ----------------------------------------------------------------

def parse_lp_check(reply: str | None, perm: str) -> tuple[str, str | None]:
    """('set', 'true'|'false') for a global node, ('unset', None), or ('unknown', None)."""
    if reply is None:
        return "unknown", None
    p = re.escape(perm)
    for m in re.finditer(rf"has\s+(?:permission\s+)?{p}\s+set\s+to\s+(true|false)"
                         rf"(?:\s+(?:in|for)\s+context\s+([^\n]+?))?\.?\s*$", reply, re.I | re.M):
        ctx = (m.group(2) or "global").strip().lower()
        if ctx == "global":
            return "set", m.group(1).lower()
    if re.search(rf"does\s+not\s+have\s+(?:permission\s+)?{p}\s+set", reply, re.I) or \
            re.search(rf"has\s+(?:permission\s+)?{p}\s+set\s+to", reply, re.I):
        return "unset", None  # absent, or set only in a non-global context
    return "unknown", None


def parse_lp_export(data: dict, group: str) -> dict[str, str] | None:
    """perm -> 'true'|'false' for the group's global nodes in an `lp export`
    document; None when the group is absent. Context-bound nodes are skipped:
    the grant only reads and restores the global value."""
    g = ((data or {}).get("groups") or {}).get(group)
    if g is None:
        return None
    out = {}
    for n in g.get("nodes") or []:
        if n.get("context") or "key" not in n:
            continue
        out[str(n["key"])] = "true" if n.get("value", True) else "false"
    return out


def lp_export_reader(drv, server_dir: str | None, timeout_s: float = 60, poll_s: float = 1.0):
    """group -> global nodes (parse_lp_export) via `lp export`, or None.
    LuckPerms answers its commands asynchronously, so over RCON the reply is
    empty; the export file in plugins/LuckPerms/ is readable from the share."""
    def read(group: str) -> dict[str, str] | None:
        if not server_dir:
            return None
        name = f"fuzz-probe-{os.getpid()}-{int(time.time() * 1000)}"
        path = os.path.join(server_dir, "plugins", "LuckPerms", name + ".json.gz")
        if drv.try_cmd(f"lp export {name} --without-users") is None:
            return None
        end = time.time() + timeout_s
        try:
            while time.time() < end:
                time.sleep(poll_s)
                try:
                    with gzip.open(path, "rt", encoding="utf-8") as fh:
                        return parse_lp_export(json.load(fh), group)
                except (OSError, EOFError, ValueError):
                    continue  # not written yet, or still being written
            return None
        finally:
            try:
                os.remove(path)
            except OSError:
                pass
    return read


class PermissionGrant:
    """Grants nodes to a LuckPerms group and restores each node's prior global
    value (or unsets it) exactly once, from finally, atexit or a signal.
    `read_nodes(group)` (lp_export_reader) supplies prior values when
    `permission check` returns nothing, and verifies the grant."""

    def __init__(self, drv, perms: list[str], group: str = "default", revert_path: str | None = None,
                 read_nodes=None):
        self.drv, self.perms, self.group, self.revert_path = drv, perms, group, revert_path
        self.read_nodes = read_nodes
        self.prior: list[tuple[str, str | None]] = []
        self.reverted = False

    def _state(self, p: str, nodes: list) -> tuple[str, str | None]:
        state, val = parse_lp_check(self.drv.try_cmd(f"lp group {self.group} permission check {p}"), p)
        if state != "unknown" or self.read_nodes is None:
            return state, val
        if not nodes:
            nodes.append(self.read_nodes(self.group))
        n = nodes[0]
        if n is None:
            return "unknown", None
        return ("set", n[p]) if p in n else ("unset", None)

    def revert_commands(self) -> list[str]:
        out = []
        for p, v in reversed(self.prior):
            out.append(f"lp group {self.group} permission unset {p}" if v is None
                       else f"lp group {self.group} permission set {p} {v}")
        return out

    def apply(self) -> list[str]:
        """Grants every node whose prior value can be read; returns the granted nodes."""
        granted = []
        nodes: list = []  # one export serves every node
        for p in self.perms:
            state, val = self._state(p, nodes)
            if state == "unknown":
                log(f"grant: cannot read LuckPerms state of '{p}' (no check reply, no readable "
                    f"`lp export` in plugins/LuckPerms; LuckPerms missing or --server-dir not the "
                    f"server's?); not granting it")
                continue
            if state == "set" and val == "true":
                log(f"grant: {self.group} already has {p}=true; leaving it")
                continue
            self.prior.append((p, val))
            self._write_revert()
            r = self.drv.try_cmd(f"lp group {self.group} permission set {p} true")
            if r is None:
                log(f"grant: no reply setting {p}; will still revert")
            granted.append(p)
        if self.prior:
            log("grant: if this process dies without cleanup, revert with: "
                + " ; ".join(self.revert_commands()))
        if granted and self.read_nodes is not None:
            after = self.read_nodes(self.group)
            bad = [p for p in granted if (after or {}).get(p) != "true"]
            if after is None:
                log("grant: could not re-read LuckPerms to verify the grant")
            elif bad:
                log(f"grant: WARNING {bad} not true on group {self.group} after setting them")
            else:
                log(f"grant: verified {granted} = true on group {self.group}")
        return granted

    def _write_revert(self) -> None:
        if self.revert_path:
            with open(self.revert_path, "w", encoding="utf-8") as fh:
                fh.write("# LuckPerms commands that undo this fuzz run's grants\n")
                fh.write("".join(c + "\n" for c in self.revert_commands()))

    def revert(self, wait_s: float = 0) -> bool:
        if self.reverted or not self.prior:
            self.reverted = True
            return True
        if wait_s > 0 and self.drv.try_cmd("list") is None:
            self.drv.wait_up(wait_s)
        ok = True
        for c in self.revert_commands():
            if self.drv.try_cmd(c) is None:
                ok = False
                log(f"grant: revert failed, run by hand: {c}")
        if ok:
            log(f"grant: reverted {len(self.prior)} node(s) on group {self.group}")
            if self.revert_path:
                with open(self.revert_path, "a", encoding="utf-8") as fh:
                    fh.write(f"# reverted {dt.datetime.now().isoformat(timespec='seconds')}\n")
        self.reverted = ok
        return ok


# --- behaviour oracles ----------------------------------------------------------
#
# A finding oracle watches one episode (begin() before the bots start,
# observe() after they finish) and returns findings. Each finding carries a
# stable `signature`: a hash of its class and a normalised key (owning plugin,
# root exception type, first frame in that plugin's code; digits and line
# numbers dropped), so the same bug seen twice is one finding. The episode
# loop keeps an episode that reaches a new signature, replays target-
# attributed ones, ignores those the `direct` control also produced, and
# fails the verdict on a reproduction. The leak path (checkpoints, fit,
# ladder) is the other oracle and keeps its own confirmation rule.

PLUGIN_PACKAGES = {
    "io.github.dailystruggle.rtp": "LeafRTP",
    "com.betterrtp": "BetterRTP",
    "me.jake.jakesrtp": "JakesRTP",
    "net.william278.huskhomes": "HuskHomes",
    "me.apick.ezrtp": "EzRTP",
    "com.justrtp": "JustRTP",
    "net.luckperms": "LuckPerms",
}


# Server, JDK and library frames: never the key frame of a plugin's bug.
PLATFORM_FRAME = re.compile(r"^(java|javax|jdk|sun|com\.sun|kotlin|org\.bukkit|org\.spigotmc|net\.minecraft|"
                            r"io\.papermc|com\.destroystokyo|net\.md_5|io\.netty|com\.mojang|co\.aikar|"
                            r"org\.slf4j|org\.apache)\.")
# A qualified throwable type (`java.lang.NullPointerException`), not a bare word.
EXC_TYPE = re.compile(r"(?<![\w$.])((?:[a-z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error|Throwable))(?=$|[:\s])")
# `at [module/]pkg.Class.method(` with an optional `java.base/` or `app//` loader prefix.
FRAME = re.compile(r"^\s*at\s+(?:\S*/)?([\w.$<>]+)\(")
# `[12:00:00 WARN]: ` in front of every line a plugin prints with printStackTrace().
LOG_PREFIX = re.compile(r"^\[\d{1,2}:\d{2}:\d{2}[^\]]*\]:?\s?")
EVENT_ERROR = re.compile(r"Could not pass event (\S+) to (\S+)")


def _unprefixed(line: str) -> str:
    return LOG_PREFIX.sub("", line, count=1)


def trace_continues(line: str) -> bool:
    """A stack trace's frame, cause, suppressed or `... n more` line."""
    s = _unprefixed(line)
    t = s.lstrip()
    return bool(FRAME.match(s)) or t.startswith(("Caused by:", "Suppressed:", "... "))


def exception_header(line: str) -> bool:
    t = _unprefixed(line).lstrip()
    return bool(EXC_TYPE.search(t)) and not t.startswith(("at ", "Caused by:", "Suppressed:"))


def trace_frames(lines: list[str]) -> list[str]:
    out = []
    for ln in lines:
        m = FRAME.match(_unprefixed(ln))
        if m:
            out.append(m.group(1))
    return out


def root_exception(lines: list[str]) -> str:
    """Type of the innermost cause (the last `Caused by:`), else the first type."""
    typ = ""
    for ln in lines:
        t = _unprefixed(ln).lstrip()
        if typ and not t.startswith("Caused by:"):
            continue
        m = EXC_TYPE.search(t)
        if m:
            typ = m.group(1)
    return typ


def plugin_of_frame(frame: str, packages: dict[str, str]) -> str | None:
    for pkg in sorted(packages, key=len, reverse=True):
        if frame == pkg or frame.startswith(pkg + "."):
            return packages[pkg]
    return None


def key_frame(frames: list[str], packages: dict[str, str], plugin: str) -> str:
    """First frame in the owning plugin's packages, else the first non-platform
    frame, else the top frame; digits dropped (lambda$x$3, v1_21_R1)."""
    pick = next((f for f in frames if plugin_of_frame(f, packages) == plugin), None)
    if pick is None:
        pick = next((f for f in frames if not PLATFORM_FRAME.match(f)), frames[0] if frames else "")
    return NUMBER.sub("#", pick)


def attribute_plugin(text: str, fallback_target: str | None = None, packages: dict[str, str] | None = None) -> str:
    """Plugin a log block belongs to: the plugin named by `Could not pass event`,
    else the topmost frame in a known package, else a known package anywhere in
    the text, else fallback_target or `unknown`. Oracles pass no fallback, so a
    trace with no known frame is never blamed on the target."""
    packages = packages if packages is not None else PLUGIN_PACKAGES
    m = EVENT_ERROR.search(text)
    if m:
        name = m.group(2).rstrip("v0123456789.-_")
        for v in packages.values():
            if v.lower() == name.lower():
                return v
        return name
    for f in trace_frames(text.splitlines()):
        owner = plugin_of_frame(f, packages)
        if owner:
            return owner
    for pkg in sorted(packages, key=len, reverse=True):
        if pkg in text:
            return packages[pkg]
    return fallback_target or "unknown"


def make_finding(cls: str, severity: str, plugin: str, key: str, witness: list, evidence: dict) -> dict:
    """A finding whose id and signature depend only on its class and key."""
    sig = hashlib.sha1(f"{cls}|{key}".encode("utf-8")).hexdigest()[:12]
    return {"finding_id": f"FIND-{cls}-{sig}", "signature": sig, "key": key, "class": cls,
            "severity": severity, "witness_program": witness, "attributed_plugin": plugin, "evidence": evidence}


class FindingOracle:
    """One episode in, findings out (see the section comment)."""
    name = "oracle"

    def begin(self) -> None:
        """Called right before the episode's bots start."""

    def observe(self, events: list[dict], programs: list, target: str) -> list[dict]:
        return []


class ServerLogOracle(FindingOracle):
    """Server log (latest.log) lines written during an episode: uncaught
    exceptions with a stack trace, `Could not pass event` errors and tick
    stalls. Exceptions are attributed by their frames' packages; a stall is
    attributed to `Server` (one line cannot name the cause)."""
    name = "log"

    def __init__(self, log_path: str | None, packages: dict[str, str] | None = None, target_names=()):
        self.log_path = log_path
        self.packages = dict(packages if packages is not None else PLUGIN_PACKAGES)
        self.target_names = {n.lower() for n in target_names if n}
        self.offset = 0
        self.ident = None
        self.start = 0
        self.mark()

    def _stat(self):
        try:
            st = os.stat(self.log_path)
        except (OSError, TypeError, ValueError):
            return None, 0
        return (st.st_dev, st.st_ino), st.st_size

    def mark(self) -> int:
        """The log's current end (0 when absent); scan() reads from there."""
        if not self.log_path:
            return 0
        self.ident, self.offset = self._stat()
        return self.offset

    def begin(self) -> None:
        self.start = self.mark()

    def observe(self, events: list[dict], programs: list, target: str) -> list[dict]:
        return self.scan(self.start, programs, target)

    def scan(self, start_offset: int, witness_program: list, target_plugin: str | None = None) -> list[dict]:
        """Findings in the log since start_offset. A replaced log (restart:
        another file identity) or a shorter one is read from its start."""
        if not self.log_path or not os.path.exists(self.log_path):
            return []
        ident, size = self._stat()
        if ident != self.ident or size < start_offset:
            start_offset = 0
        try:
            with open(self.log_path, "rb") as fh:
                fh.seek(start_offset)
                data = fh.read()
        except OSError:
            return []
        self.ident, self.offset = ident, start_offset + len(data)
        return self.analyze_text(data.decode("utf-8", "replace"), witness_program, target_plugin)

    def is_target(self, plugin: str, target_plugin: str | None) -> bool:
        return plugin.lower() in (self.target_names | ({target_plugin.lower()} if target_plugin else set()))

    def analyze_text(self, text: str, witness_program: list, target_plugin: str | None = None) -> list[dict]:
        findings = []
        if not text:
            return findings
        lines = text.splitlines()
        i = 0
        while i < len(lines):
            line = lines[i]
            ev = EVENT_ERROR.search(line)
            if ev:
                # The listener's exception follows: its type line, then the trace.
                j = i + 1
                if j < len(lines) and exception_header(lines[j]):
                    j += 1
                while j < len(lines) and trace_continues(lines[j]):
                    j += 1
                block = lines[i:j]
                plugin = attribute_plugin("\n".join(block), packages=self.packages)
                key = "|".join((plugin, ev.group(1), root_exception(block),
                                 key_frame(trace_frames(block), self.packages, plugin)))
                findings.append(make_finding("EVENT_ERROR", "HIGH" if self.is_target(plugin, target_plugin) else "MEDIUM",
                                             plugin, key, witness_program,
                                             {"log_snippet": "\n".join(block[:15]), "line": line.strip()}))
                i = j
                continue
            # A qualified throwable type followed by at least one trace line;
            # an `Error:` word or a class name in a message is not one.
            if ("StressTestRTP: leakcheck" not in line and exception_header(line)
                    and i + 1 < len(lines) and trace_continues(lines[i + 1])):
                j = i + 1
                while j < len(lines) and trace_continues(lines[j]):
                    j += 1
                block = lines[i:j]
                plugin = attribute_plugin("\n".join(block), packages=self.packages)
                key = "|".join((plugin, root_exception(block), key_frame(trace_frames(block), self.packages, plugin)))
                findings.append(make_finding("UNCAUGHT_EXCEPTION", "CRITICAL" if "Server thread" in line else "HIGH",
                                             plugin, key, witness_program,
                                             {"log_snippet": "\n".join(block[:15]), "line": line.strip()}))
                i = j
                continue
            lag = re.search(r"Can't keep up!.*?Running (\d+)ms", line)
            hang = re.search(r"The server has not responded for (\d+) seconds", line)
            if lag or hang or "Can't keep up!" in line:
                ms_behind = int(lag.group(1)) if lag else (int(hang.group(1)) * 1000 if hang else 0)
                stall = bool(hang) or ms_behind >= 5000
                findings.append(make_finding("WATCHDOG_STALL", "HIGH" if stall else "MEDIUM", "Server",
                                             "watchdog" if hang else ("stall" if stall else "lag"), witness_program,
                                             {"log_snippet": line.strip(), "ms_behind": ms_behind}))
            i += 1
        return findings


class LeastPrivilegeOracle(FindingOracle):
    """Verifies that unprivileged bot accounts receive proper permission denials
    when executing administrative commands (the swarm's `denial_failure`
    events). Only meaningful with a narrow --grant: under `*` every command is
    allowed."""
    name = "permission"

    DENIED_ACTIONS = [
        "op", "deop", "stop", "restart", "reload", "lp", "luckperms",
        "bukkit:reload", "minecraft:op", "rtp reload", "rtp admin",
        "betterrtp reload", "betterrtp admin", "huskhomes reload", "jakesrtp reload"
    ]

    DENIAL_PATTERNS = re.compile(
        r"(no permission|unknown command|do not have permission|unknown or incomplete|cannot execute|denied|i'm sorry|not permitted)",
        re.IGNORECASE
    )

    def __init__(self, denied_actions: list[str] | None = None):
        self.denied_actions = denied_actions or list(self.DENIED_ACTIONS)

    def observe(self, events: list[dict], programs: list, target: str) -> list[dict]:
        return self.check_events(events, programs, target)

    def check_events(self, events: list[dict], witness_program: list, target_plugin: str) -> list[dict]:
        findings = []
        for ev in events:
            if ev.get("ev") == "denial_failure":
                cmd = normalise_state(str(ev.get("cmd") or "").lstrip("/"), [ev.get("bot", "")])
                findings.append(make_finding("PERMISSION_BYPASS", "HIGH", target_plugin, cmd, witness_program,
                                             {"cmd": ev.get("cmd"), "reply": ev.get("text"), "bot": ev.get("bot")}))
        return findings


def target_aliases(a, target: str) -> set[str]:
    """Lower-case plugin names that make a finding count against `target`: the
    label and its --names / --jars entries. Exact matches only, so `rtp` never
    claims BetterRTP's findings."""
    out = {target.lower()}
    for spec in (getattr(a, "names", None), getattr(a, "jars", None)):
        for pair in (spec or "").split(","):
            t, sep, n = pair.partition("=")
            if sep and t.strip().lower() == target.lower() and n.strip():
                out.add(n.strip().lower())
    return out


def plugin_packages(spec: str | None) -> dict[str, str]:
    """PLUGIN_PACKAGES plus --plugin-packages 'com.example.foo=Foo,...'."""
    out = dict(PLUGIN_PACKAGES)
    for pair in (spec or "").split(","):
        p, sep, n = pair.partition("=")
        if sep and p.strip() and n.strip():
            out[p.strip()] = n.strip()
    return out


def summarise_findings(findings: list[dict], tests: dict | None = None) -> list[dict]:
    """One entry per signature in first-seen order: how often it fired, where
    first, whether it counts against the target, and its replay result."""
    out: dict[str, dict] = {}
    for f in findings:
        s = out.get(f["signature"])
        if s is None:
            s = out[f["signature"]] = {k: f.get(k) for k in (
                "signature", "class", "oracle", "severity", "attributed_plugin", "key", "target_attributed",
                "in_control", "evidence")}
            s.update(first={"seed": f.get("seed"), "ep": f.get("ep")}, seen=0)
        s["seen"] += int(f.get("repeats") or 1)
    for sig, s in out.items():
        t = (tests or {}).get(sig)
        s["reproduced"] = bool(t and t.get("reproduced"))
        if t:
            s.update(k=t["k"], n=t["n"], shrunk=t.get("sequence"))
    return list(out.values())


def control_findings(findings: list[dict]) -> dict:
    """The control's signatures: harness or vanilla noise every target ignores."""
    return {"signatures": {s["signature"]: {"class": s["class"], "key": s["key"], "seen": s["seen"]}
                           for s in summarise_findings(findings)}}


# --- swarm ----------------------------------------------------------------------

class SwarmError(Exception):
    pass


class NoSpawnError(RuntimeError):
    pass


class Swarm:
    """bench-swarm.js in fuzz-control mode as a child process. stdin stays an
    open pipe: the swarm exits on its EOF, so it never outlives this process."""

    READY = re.compile(r"\[fuzz\] listening on 127\.0\.0\.1:(\d+)")

    def __init__(self, argv: list[str], log_path: str | None = None, ready_s: float = 30):
        self.argv, self.log_path, self.ready_s = argv, log_path, ready_s
        self.proc = None
        self.sock = None
        self.q: queue.Queue = queue.Queue()
        self.port = None

    def start(self) -> None:
        self.q = queue.Queue()
        ready = queue.Queue()
        proc = self.proc = subprocess.Popen(self.argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                            errors="replace")

        def drain():
            fh = open(self.log_path, "a", encoding="utf-8") if self.log_path else None
            try:
                for line in proc.stdout:
                    if fh:
                        fh.write(line)
                        fh.flush()
                    m = self.READY.search(line)
                    if m:
                        ready.put(int(m.group(1)))
            finally:
                ready.put(None)
                proc.stdout.close()
                if fh:
                    fh.close()

        threading.Thread(target=drain, daemon=True).start()
        try:
            self.port = ready.get(timeout=self.ready_s)
        except queue.Empty:
            self.port = None
        if not self.port:
            self.close()
            raise SwarmError(f"swarm did not report a control port within {self.ready_s:.0f}s")
        self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        self.sock.settimeout(None)
        q = self.q
        sock = self.sock

        def read():
            try:
                with sock.makefile("r", encoding="utf-8", errors="replace") as fh:
                    for line in fh:
                        line = line.strip()
                        if line:
                            try:
                                q.put(json.loads(line))
                            except ValueError:
                                pass
            except OSError:
                pass
            q.put(None)

        threading.Thread(target=read, daemon=True).start()

    def alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def send(self, obj: dict) -> None:
        if not self.sock:
            raise SwarmError("swarm not started")
        try:
            self.sock.sendall((json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8"))
        except OSError as e:
            raise SwarmError(f"control socket: {e}") from e

    def run_episode(self, req: dict, timeout_s: float) -> tuple[list[dict], dict]:
        """Events of episode req['id'] and its done record. Raises SwarmError on
        rejection, a dead swarm or no done within timeout_s."""
        while not self.q.empty():
            if self.q.get_nowait() is None:
                raise SwarmError("control socket closed")
        self.send(req)
        events: list[dict] = []
        end = time.time() + timeout_s
        while True:
            left = end - time.time()
            if left <= 0:
                raise SwarmError(f"no done for episode {req['id']} within {timeout_s:.0f}s")
            try:
                msg = self.q.get(timeout=min(1.0, left))
            except queue.Empty:
                if not self.alive():
                    raise SwarmError("swarm exited")
                continue
            if msg is None:
                raise SwarmError("control socket closed")
            if msg.get("id") != req["id"]:
                continue
            ev = msg.get("ev")
            if ev == "error":
                raise SwarmError(f"episode rejected: {msg.get('error')}")
            if ev == "done":
                return events, msg
            if ev != "accepted":
                events.append(msg)

    def discover(
        self,
        did: str,
        tier: str = "default",
        bot: str | None = None,
        max_ms: int = 15000,
        timeout_s: float | None = None,
    ) -> tuple[dict, dict]:
        """Run a read-only `discover` op and return `(commands_event, done_event)`.

        Raises `SwarmError` if the `commands` event is missing, `captured` is false,
        or `done['reason'] != 'complete'`.
        """
        req: dict = {"op": "discover", "id": did, "tier": tier, "max_ms": int(max_ms)}
        if bot is not None:
            req["bot"] = bot
        wait_s = timeout_s if timeout_s is not None else max(30.0, max_ms / 1000.0 + 10.0)
        events, done = self.run_episode(req, wait_s)
        cmd_ev = next((e for e in events if e.get("ev") == "commands"), None)
        reason = done.get("reason")
        if cmd_ev is None:
            raise SwarmError(f"discover {did} ({tier}): missing commands event (reason={reason!r})")
        if not cmd_ev.get("captured") or reason != "complete":
            bot_errors = [str(e.get("text")) for e in events if e.get("ev") == "bot_error" and e.get("text")]
            detail = f"; bot_errors={bot_errors}" if bot_errors else ""
            raise SwarmError(
                f"discover {did} ({tier}) failed to capture command graph: "
                f"captured={bool(cmd_ev.get('captured'))}, reason={reason!r}{detail}"
            )
        return cmd_ev, done

    def close(self) -> None:
        if self.sock:
            try:
                self.send({"op": "stop"})
            except SwarmError:
                pass
        if self.proc:
            try:
                if self.proc.stdin:
                    self.proc.stdin.close()
            except OSError:
                pass
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None
        self.proc = None

    def restart(self) -> None:
        self.close()
        self.start()


def swarm_bot_cap(a) -> int:
    """Bots per episode the swarm accepts: micro episodes (--max-bots) and every
    ladder rung; a lower cap rejects the rung and the verdict goes INCONCLUSIVE."""
    return max([a.max_bots, 8] + [b for b, _ in parse_rungs(a.rungs)])


def swarm_argv(a, mc_port: int) -> list[str]:
    argv = [a.node, a.swarm_js, "--fuzz-control", "0", "--host", a.mc_host or a.host, "--port", str(mc_port),
            "--join-delay-ms", str(a.join_delay_ms), "--fuzz-episode-max-ms", str(int(a.episode_max_s * 1000)),
            "--fuzz-max-bots", str(swarm_bot_cap(a))]
    if a.mc_version:
        argv += ["--version", a.mc_version]
    return argv


# --- episode loop ---------------------------------------------------------------

class FuzzDriver(bo.Driver):
    """The fuzz bots send the direct control's command themselves, so `direct`
    needs no StressTestRTP target-commands entry (prepare_target checks one)."""

    def targets_on_server(self):
        try:
            listed = super().targets_on_server() or []
        except bo.RconError:
            listed = []
        return listed + [DIRECT]


def online_names(drv) -> list[str] | None:
    r = drv.try_cmd("list")
    if r is None:
        return None
    _, sep, names = r.partition(":")
    return [n.strip() for n in names.split(",") if n.strip()] if sep else []


def foreign_online(names: list[str] | None) -> list[str]:
    return [n for n in (names or []) if not FUZZ_NAME.match(n)]


def wait_fuzz_gone(drv, limit_s: float) -> list[str]:
    end = time.time() + limit_s
    names = online_names(drv) or []
    while time.time() < end and any(FUZZ_NAME.match(n) for n in names):
        time.sleep(0.5)
        names = online_names(drv) or []
    return names


def episode_request(eid: str, cmd: str, bots: list[dict], cap_ms: int) -> dict:
    need = max((est_ms(b["program"]) for b in bots), default=0)
    return {"op": "episode", "id": eid, "cmd": cmd, "max_ms": int(min(cap_ms, need + 20000)), "bots": bots}


class TargetRun:
    """Episodes of one target: checkpoints, deltas, corpus and the trace file."""

    def __init__(self, a, drv, swarm: Swarm, reader: RunsReader, target: str, stamp: str, tdir: str):
        self.a, self.drv, self.swarm, self.reader = a, drv, swarm, reader
        self.target, self.stamp, self.tdir = target, stamp, tdir
        self.cmd = command_for(target, parse_cmds(a.cmd))
        self.empty_streak = 0
        self.probe_reader = CoverageReader(reader.runs_dir) if getattr(a, "coverage", False) else None
        # The direct control run always measures leaks (its fit is the reference for every target).
        self.faults = getattr(a, "mode", "leak") == "faults" and target != DIRECT
        self.trace = os.path.join(tdir, "episodes.jsonl")
        self.prev: dict | None = None
        self.prev_histo: dict = {}
        self.dirty = False  # faults mode: the last episode left players online
        self.promotions: list[dict] = []  # faults mode: promising episodes awaiting a leak re-run
        self.cfg = {"min_bots": a.min_bots, "max_bots": a.max_bots, "min_s": a.min_episode_s,
                    "max_s": a.max_episode_s, "novelty": a.novelty, "run": f"{stamp}:{target}",
                    "rtp_args": [x for x in (a.rtp_args or "").split(",")] if a.rtp_args else [],
                    "console": parse_console(a.console).get(target, []), "console_p": a.console_p,
                    "console_max": a.console_max}
        self.opt_path = os.path.join(tdir, "optimiser.jsonl")
        self.fit_path = os.path.join(tdir, "fit.json")
        # The control's fit (written by the `direct` run of the same session)
        # is the reference every other target's score and flags subtract.
        run_dir = os.path.dirname(tdir)
        self.control = None if target == DIRECT else load_json(os.path.join(run_dir, CONTROL_FILE))
        self.control_classes = None if target == DIRECT else load_json(os.path.join(run_dir, CONTROL_CLASSES_FILE))
        self.control_dir = os.path.join(run_dir, DIRECT)
        self._control_recs: list[dict] | None = None
        self.fixed_counts = (sorted(c for c in self.control if scorable(c)) if self.control
                             else list(DEFAULT_SCORE_COUNTS))
        self.score_counts = list(self.fixed_counts)
        self.screen: list[dict] = []
        self.departure_screen: list[dict] = []
        self.classes_seen = 0
        self.class_ref: dict = {}  # control reference per screened class count
        self.fit: dict = {}
        self.search = SearchState(a.novelty, a.novelty_max, a.novelty_step)
        self.candidates: list[dict] = []  # reproduced surprises: {seed, ep, count, k, n, rec}
        self.replay_spent = 0
        self.shrink_spent = 0
        self.control_heap = None if target == DIRECT else load_json(os.path.join(run_dir, CONTROL_HEAP_FILE))
        self.heap_stop = a.heap_stop_mb
        self.learnt: dict[int, dict] = {}  # seed -> final generator parameters
        self.coverage = {"episodes": 0, "planned": 0, "executed": 0, "explore_s": 0.0, "pairs": set(), "restarts": 0,
                         "seeds": [], "heap_stopped": False}
        log_path = os.path.join(a.server_dir, "logs", "latest.log") if getattr(a, "server_dir", None) else None
        self.target_names = target_aliases(a, target)
        self.log_oracle = ServerLogOracle(log_path, plugin_packages(getattr(a, "plugin_packages", None)),
                                          self.target_names)
        self.least_privilege_oracle = LeastPrivilegeOracle()
        self.oracles: list[FindingOracle] = [self.log_oracle, self.least_privilege_oracle]
        self.findings_path = os.path.join(tdir, "findings.jsonl")
        self.findings: list[dict] = []
        cf = None if target == DIRECT else load_json(os.path.join(run_dir, CONTROL_FINDINGS_FILE))
        self.control_findings: set[str] = set((cf or {}).get("signatures") or {})
        self.finding_tests: dict[str, dict] = {}  # signature -> replay result

    @property
    def ref(self) -> dict:
        """Control fits every score, flag and reproduction subtracts."""
        r = dict(self.control or {})
        r.update(self.class_ref)
        return r

    @property
    def class_z(self) -> float:
        """Per-episode excess a class needs before it scores (one test per class)."""
        return bonferroni_z(max(1, self.classes_seen))

    def control_records(self) -> list[dict]:
        if self._control_recs is None:
            self._control_recs = read_records(self.control_dir) if os.path.isdir(self.control_dir) else []
        return self._control_recs

    def rescreen(self, records: list[dict]) -> None:
        """Pick the classes to fit and score; the control run is the reference
        and is not screened against itself. Each screened class gets a control
        reference: the control episodes' fit of that class when they are on
        disk (subtracts exploration growth per behaviour), else the control's
        per-episode mean and noise; a class the control never saw gets mean 0
        and the target's own noise. Two screens feed it: cumulative growth per
        episode, and growth per departed session against the control."""
        if self.target == DIRECT or self.a.max_classes <= 0:
            return
        self.screen, self.classes_seen = screen_classes(records, self.control_classes, self.a.max_classes)
        self.departure_screen = screen_departures(records, self.control_records(), self.a.max_classes)
        names = list(dict.fromkeys([s["cls"] for s in self.screen] + [s["cls"] for s in self.departure_screen]))
        missing = [CLS + c for c in names if CLS + c not in self.class_ref]
        if missing and self.control_records():
            self.class_ref.update(fit_counts(self.control_records(), missing, self.a.ridge_lam))
        # One entry per class; the cumulative screen's mean wins over the departure screen's 0.
        by_cls = {s["cls"]: s for s in self.departure_screen}
        by_cls.update({s["cls"]: s for s in self.screen})
        for s in by_cls.values():
            c = CLS + s["cls"]
            ref = self.class_ref.get(c)
            if ref is None:
                ref = {"n": 0, "intercept": s.get("mean", 0.0), "coef": {}, "ci": {}, "noise": s["noise"]}
            if not s["in_control"]:
                ref = dict(ref, noise=max(ref["noise"], s["noise"]))
            self.class_ref[c] = ref
        self.score_counts = list(self.fixed_counts) + [CLS + c for c in names]

    def label(self, seed: int, tag: str) -> str:
        return LABEL_SAFE.sub("_", f"fz{self.stamp}-{self.target}-s{seed}-{tag}")

    def settle(self) -> tuple[list[str], list[str]]:
        """Quiet pause, then wait for fuzz bots to leave `list`; returns the
        (lingering fuzz bots, foreign players) that make a checkpoint dirty.
        Foreign players are tolerated with --allow-online."""
        time.sleep(self.a.quiet_s)
        left = wait_fuzz_gone(self.drv, self.a.quiet_wait_s)
        lingering = [n for n in left if FUZZ_NAME.match(n)]
        foreign = ([] if self.a.allow_online else foreign_online(left))
        if self.faults and (lingering or foreign):
            self.dirty = True
        return lingering, foreign

    def baseline(self, seed: int, tag: str, mode: str = "quick") -> bool:
        """Fresh reference checkpoint, only once no other player is online."""
        label = self.label(seed, tag)
        lingering, foreign = self.settle()
        if lingering or foreign:
            log(f"{self.target}: baseline {label} skipped: still online {(lingering + foreign)[:5]}")
            self.prev, self.prev_histo = None, {}
            return False
        wait = self.a.dump_wait_s if mode == "dump" else self.a.checkpoint_wait_s
        err = quick_checkpoint(self.drv, label, wait, self.a.checkpoint_poll_s, mode)
        d = None if err else self.reader.fetch(label, self.a.files_wait_s, min(0.25, self.a.checkpoint_poll_s))
        if d is None:
            log(f"{self.target}: baseline {label} unavailable: {err or 'row not in runs files'}")
            self.prev, self.prev_histo = None, {}
            return False
        self.prev, self.prev_histo = d, self.reader.histo(d)
        log(f"{self.target}: baseline {label} "
            f"heap={d.get('heap_used_after_gc_mb', -1):.0f}MB classes={len(self.prev_histo)}")
        return True

    def warm_up(self, seed: int) -> None:
        """Exercise the target before the first scored baseline; never checkpoint or trace it."""
        for i in range(getattr(self.a, "warmup", 1)):
            bots = [{"name": bot_name(self.cfg["run"], seed, f"warm{i}", j),
                     "program": [{"a": "rtp"}]} for j in range(self.a.min_bots)]
            eid = f"{self.target}-s{seed}-warm{i}"[:64]
            log(f"{self.target}: warm-up {i + 1}/{self.a.warmup} with {len(bots)} bot(s)")
            try:
                events, _ = self.run_bots(eid, bots, None)
            except SwarmError as e:
                # Same recovery as a scored episode; an unrecoverable swarm aborts via main's NoSpawnError path.
                try:
                    self.swarm_failed(f"warm{i}", e)
                except SwarmError as e2:
                    raise NoSpawnError(f"warm-up {eid}: {e2}") from e2
                continue
            if not any(e.get("ev") == "spawn" for e in events):
                errors = list(dict.fromkeys(str(e.get("text", "")) for e in events
                                            if e.get("ev") == "bot_error" and e.get("text")))
                self.log_bot_errors(eid, errors)
                raise NoSpawnError(self.no_spawn_message(eid, errors, warmup=True))
            self.empty_streak = 0
            self.settle()
        self.prev, self.prev_histo = None, {}

    @staticmethod
    def log_bot_errors(eid: str, errors: list[str]) -> None:
        if errors:
            log(f"swarm {eid} bot errors: " + " | ".join(errors))

    def no_spawn_message(self, eid: str, errors: list[str], warmup: bool = False) -> str:
        detail = (": " + " | ".join(errors)) if errors else ""
        hint = ("; version auto-detect failed, try --mc-version with a version supported by Mineflayer "
                "and ViaVersion/ViaBackwards on the bench") if any("No data available for version" in e
                                                                    for e in errors) else ""
        if warmup:
            return f"warm-up {eid}: no bot spawned{detail}{hint}"
        return (f"{self.empty_streak} consecutive episodes had no bot spawn (last: {eid})"
                f"{detail}{hint}")

    def write(self, rec: dict) -> None:
        line, side = split_classes(rec)
        with open(self.trace, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(line, separators=(",", ":"), sort_keys=True) + "\n")
        if side is not None:
            append_jsonl(os.path.join(self.tdir, CLASSES_FILE), side)

    def execute(self, seed: int, ep, plan: dict, corpus: Corpus | None, gen: int = 0, rung: int = 0,
                reps: int | None = None, leak: bool = False, replay: bool = False) -> dict:
        """Run one planned episode and write its trace line; returns the record.
        reps overrides --reps (finding replays need one run, not a slope).
        leak=True measures with checkpoints even in faults mode (leak re-runs, rungs,
        shrink and surprise replays). replay=True marks a finding replay: in faults
        mode it runs without a checkpoint and confirms by signature only."""
        faults = self.faults and not leak
        eid = f"{self.target}-s{seed}-e{ep}"[:64]
        bots = plan["bots"]
        rec = {"seed": seed, "ep": ep, "parent": plan.get("parent"), "gen": gen, "rung": rung,
               "origin": plan["origin"], "target": self.target, "cmd": self.cmd, "bots": bots,
               "params": plan.get("params"), "ops": plan.get("ops"), "events": [], "states": [],
               "features": None, "deltas": None, "score": None}
        if plan.get("replay_of") is not None:
            rec["replay_of"] = plan["replay_of"]
        if replay and faults:
            rec["replay"] = True
        if self.faults and self.dirty:
            # Faults mode has no baseline to refuse a dirty start: wait for the last episode's players to leave.
            lingering, foreign = self.settle()
            if lingering or foreign:
                rec["excluded"] = "other players online before the episode"
                self.prev, self.prev_histo = None, {}
                if lingering:
                    rec["lingering"] = lingering
                if foreign:
                    rec["foreign_online"] = foreign
                self.write(rec)
                return rec
            self.dirty = False
        if not faults and self.prev is None and not self.baseline(seed, f"e{ep}b"):
            rec["excluded"] = "no baseline checkpoint"
            self.write(rec)
            return rec
        if plan.get("console"):
            rec["console"] = plan["console"]
        for o in self.oracles:
            o.begin()
        t0 = time.time()
        rec["t_start"] = dt.datetime.now().isoformat(timespec="seconds")
        try:
            events, done = self.run_bots(eid, bots, plan.get("console"))
        except SwarmError as e:
            rec["excluded"] = f"swarm: {e}"
            self.swarm_failed(ep, e)
            self.write(rec)
            return rec
        rec["events"], rec["done"] = events, {k: done.get(k) for k in ("ms", "reason", "bots")}
        if bots and not any(e.get("ev") == "spawn" for e in events):
            self.empty_streak += 1
            rec["excluded"] = "no bot spawned"
            rec["bot_errors"] = list(dict.fromkeys(str(e.get("text", "")) for e in events
                                                    if e.get("ev") == "bot_error" and e.get("text")))
            self.prev, self.prev_histo = None, {}
            self.log_bot_errors(eid, rec["bot_errors"])
            log(f"{self.target}: episode {ep} excluded before checkpoint; "
                f"{self.empty_streak}/{self.a.max_empty} consecutive episodes had no spawn")
            self.write(rec)
            if self.empty_streak >= self.a.max_empty:
                raise NoSpawnError(self.no_spawn_message(eid, rec["bot_errors"]))
            return rec
        self.empty_streak = 0
        rec["ms"] = int((time.time() - t0) * 1000)
        new_findings = self.observe(seed, ep, rec["origin"], events, [b["program"] for b in bots])
        if new_findings:
            rec["findings"] = new_findings
            self.findings.extend(new_findings)
            for f in new_findings:
                append_jsonl(self.findings_path, f)
                tag = "target" if f["target_attributed"] else "other"
                log(f"{self.target}: FINDING {f['signature']} ({f['oracle']}, {tag}"
                    f"{', in control' if f['in_control'] else ''}): [{f['class']}] {f['severity']} "
                    f"{f['attributed_plugin']}: "
                    f"{f['evidence'].get('line') or f['evidence'].get('cmd') or f['evidence'].get('log_snippet', '')[:80]}")
        names = [b["name"] for b in bots] + [b.get("last", "") for b in done.get("bots") or []]
        rec["states"] = extract_states(events, names, [b["name"] for b in bots]) + console_states(events, names)
        lingering, foreign = self.settle()
        if lingering:
            rec["lingering"] = lingering
        if foreign:
            rec["foreign_online"] = foreign
        label = self.label(seed, f"e{ep}")
        rec["label"] = label
        if faults:
            self.prev, self.prev_histo = None, {}  # no chain: a later checkpointed run must rebaseline
            # No checkpoint chain: the next episode settles first while these players are still online.
            if foreign or lingering:
                rec["excluded"] = "other players online after the episode"
        else:
            c0 = time.time()
            err = quick_checkpoint(self.drv, label, self.a.checkpoint_wait_s, self.a.checkpoint_poll_s)
            d = None if err else self.reader.fetch(label, self.a.files_wait_s, min(0.25, self.a.checkpoint_poll_s))
            rec["checkpoint_ms"] = int((time.time() - c0) * 1000)
            if d is None:
                rec["excluded"] = f"no checkpoint: {err or 'row not in runs files'}"
                log(f"{self.target}: episode {ep}: {rec['excluded']}; excluded, rebaselining")
                self.prev = None
            else:
                h = self.reader.histo(d)
                deltas = count_deltas(self.prev, d)
                rec["departures"] = departures(self.prev, d)
                if h and self.prev_histo:
                    deltas.update(class_deltas(self.prev_histo, h, self.a.top_classes))
                else:
                    rec["no_histogram"] = True
                rec["deltas"] = deltas
                rec["online_at_checkpoint"] = int(d.get("online", -1))
                rec["features"] = extract_features(rec)
                if foreign or lingering:
                    # The next delta would start from this dirty state: rebaseline.
                    rec["excluded"] = "other players online at the checkpoint"
                    self.prev, self.prev_histo = None, {}
                else:
                    self.prev, self.prev_histo = d, (h or {})
                    reps = self.a.reps if reps is None else reps
                    if reps > 1:
                        self.repeat(seed, ep, rec, d, h or {}, reps)
        if "excluded" in rec:
            rec["features"] = None
        rec["score"] = score_episode(rec, self.score_counts, self.ref, self.class_z, target_fit=self.fit)
        if corpus is not None and "excluded" not in rec:
            probes = self.coverage_probes(label) if self.probe_reader is not None else ()
            res = corpus.consider(ep, bots, rec["states"], rec["deltas"],
                                  [f["signature"] for f in rec.get("findings") or []], probes)
            rec.update(res)
            if faults and rec["origin"] not in RERUN_ORIGINS and (res.get("new_probes") or res.get("new_findings")):
                self.promotions.append({"ep": ep, "bots": bots, "console": plan.get("console"),
                                        "probes": res.get("new_probes", 0),
                                        "signatures": res.get("new_findings", [])})
        self.write(rec)
        ds = rec["deltas"] or {}
        log(f"{self.target}: s{seed} e{ep} {rec['origin']:<8} bots={len(bots)} acts="
            f"{sum(len(b['program']) for b in bots)} {rec['ms'] / 1000:.1f}s states={len(rec['states'])}"
            f"{' new=' + str(len(rec.get('new_states') or [])) if rec.get('kept') else ''} "
            f"departed={rec.get('departures')} heap{ds.get('heap_used_after_gc_mb', 0):+.1f}MB"
            + (f" console={len(rec['console'])}" if rec.get("console") else "")
            + (f" live/rep{ds[SLOPE + 'histogram_live_mb']:+.2f}MB classes/rep"
               f"{ds.get(SLOPE + 'classes_loaded', 0):+.1f} x{rec.get('reps')}"
               if SLOPE + "histogram_live_mb" in ds else "")
            + (f" reps-cut({rec['reps_error']})" if rec.get("reps_error") else "")
            + (f" EXCLUDED({rec['excluded']})" if rec.get("excluded") else "")
            + (" no-histogram" if rec.get("no_histogram") else "")
            + (f" findings={len(rec['findings'])}" if rec.get("findings") else "")
            + (" kept" if rec.get("kept") else "")
            + (f" score={rec['score']:+.2f}/min" if rec.get("score") is not None else ""))
        return rec

    def coverage_probes(self, label: str) -> set[str]:
        """Reached probes of this episode's `/rtpstress coverage` dump; a missing
        dump is logged and yields no probes, so the episode is never excluded."""
        try:
            self.drv.try_cmd(f"rtpstress coverage {label}")
        except Exception as ex:  # noqa: BLE001  (coverage is best-effort evidence)
            log(f"{self.target}: coverage {label}: {type(ex).__name__}")
            return set()
        probes = self.probe_reader.fetch(label, self.a.files_wait_s, min(0.25, self.a.checkpoint_poll_s))
        if probes is None:
            log(f"{self.target}: coverage {label}: no dump")
            return set()
        return set(self.probe_reader.new_probes(probes))

    def observe(self, seed: int, ep, origin: str, events: list[dict], programs: list) -> list[dict]:
        """Every finding oracle's findings of one episode, one per signature
        (`repeats` = how often it fired), tagged with the episode, whether it
        counts against the target and whether the control produced it too."""
        out: dict[str, dict] = {}
        for o in self.oracles:
            for f in o.observe(events, programs, self.target):
                seen = out.get(f["signature"])
                if seen is not None:
                    seen["repeats"] += 1
                    continue
                f.update(oracle=o.name, seed=seed, ep=ep, origin=origin, repeats=1,
                         target_attributed=str(f.get("attributed_plugin") or "").lower() in self.target_names,
                         in_control=f["signature"] in self.control_findings)
                out[f["signature"]] = f
        return list(out.values())

    def swarm_failed(self, ep, e: Exception) -> None:
        log(f"{self.target}: episode {ep}: {e}; restarting the swarm")
        self.prev, self.prev_histo = None, {}  # bots may still be online: rebaseline before the next episode
        if self.faults:
            self.dirty = True  # faults mode settles before the next episode instead
        try:
            self.swarm.restart()
        except (SwarmError, OSError) as e2:
            raise SwarmError(f"swarm restart failed: {e2}") from e2

    def run_bots(self, eid: str, bots: list[dict], console: list[dict] | None) -> tuple[list[dict], dict]:
        """One swarm episode; console actions run over RCON on a side thread at
        their offsets (the main thread does not use RCON meanwhile). Their
        replies join the events as `console` events. Raises SwarmError."""
        req = episode_request(eid, self.cmd, bots, int(self.a.episode_max_s * 1000))
        con: list[dict] = []
        stop = threading.Event()
        t0 = time.time()

        def run_console():
            for c in console or []:
                if stop.wait(max(0.0, t0 + c["at_ms"] / 1000.0 - time.time())):
                    return
                try:
                    reply = self.drv.try_cmd(c["cmd"])
                except Exception as ex:  # noqa: BLE001  (a failing command is a state, not a crash)
                    reply = f"error: {type(ex).__name__}"
                if reply is None:
                    reply = "error: no rcon reply"
                con.append({"ev": "console", "bot": "", "t": int((time.time() - t0) * 1000),
                            "cmd": c["cmd"], "text": (reply or "")[:500]})

        th = threading.Thread(target=run_console, name="fuzz-console", daemon=True) if console else None
        if th:
            th.start()
        try:
            events, done = self.swarm.run_episode(req, req["max_ms"] / 1000.0 + self.a.done_slack_s)
        except SwarmError:
            stop.set()
            raise
        finally:
            if th:
                last = max(c["at_ms"] for c in console) / 1000.0
                th.join(max(0.0, t0 + last - time.time()) + self.a.console_timeout_s + 1)
        if con:
            events = sorted(list(events) + con, key=lambda e: float(e.get("t", 0) or 0))
        return events, done

    def repeat(self, seed: int, ep, rec: dict, d1: dict, h1: dict, reps: int | None = None) -> None:
        """--reps - 1 more runs of the episode's programs (fresh names, same
        console actions), a checkpoint after each; adds `slope.<metric>`
        deltas (growth per repetition) and the classes that grew from the
        first to the last repetition (`rep_classes`, for attribution)."""
        series, h_last = [slope_metrics(d1)], h1
        for j in range(1, self.a.reps if reps is None else reps):
            bots = [{"name": bot_name(self.cfg["run"], seed, f"{ep}k{j}", i), "program": b["program"]}
                    for i, b in enumerate(rec["bots"])]
            try:
                events, _ = self.run_bots(f"{self.target}-s{seed}-e{ep}k{j}"[:64], bots, rec.get("console"))
            except SwarmError as e:
                rec["reps_error"] = f"swarm: {e}"
                self.swarm_failed(ep, e)
                break
            if bots and not any(e.get("ev") == "spawn" for e in events):
                errors = list(dict.fromkeys(str(e.get("text", "")) for e in events
                                            if e.get("ev") == "bot_error" and e.get("text")))
                rec["reps_error"] = "no bot spawned"
                rec["reps_bot_errors"] = errors
                self.log_bot_errors(f"{self.target}-s{seed}-e{ep}k{j}", errors)
                break
            lingering, foreign = self.settle()
            label = self.label(seed, f"e{ep}k{j}")
            err = quick_checkpoint(self.drv, label, self.a.checkpoint_wait_s, self.a.checkpoint_poll_s)
            d = None if err else self.reader.fetch(label, self.a.files_wait_s, min(0.25, self.a.checkpoint_poll_s))
            if d is None or lingering or foreign:
                rec["reps_error"] = err or ("row not in runs files" if d is None else "players online")
                self.prev, self.prev_histo = None, {}
                break
            h_last = self.reader.histo(d) or {}
            series.append(slope_metrics(d))
            self.prev, self.prev_histo = d, h_last
        rec["reps"], rec["series"] = len(series), series
        if len(series) >= 2:
            rec["deltas"].update(rep_slopes(series))
            if h1 and h_last:
                rec["rep_classes"] = class_deltas(h1, h_last, 16)

    def check_surprise(self, seed: int, rec: dict, gen: int, out: list[dict]) -> dict | None:
        """Replay a surprising episode at once (--surprise-n times, fresh names,
        origin 'replay'): k >= --surprise-k reproductions make it a candidate
        and switch the search to exploiting, before the fit has enough episodes
        to flag it; otherwise it is logged as a one-off. The control is the
        reference and is never replayed. Replays are appended to `out`."""
        if self.target == DIRECT or self.a.surprise_n <= 0 or rec["origin"] in ("replay", "shrink"):
            return None
        found = surprises(rec, self.score_counts, self.ref, self.fit, self.a.surprise_z, self.class_z)
        if not found:
            return None
        count = max(found, key=lambda c: (found[c], c))
        need = required_effect(rec, count, self.ref, target_fit=self.fit)
        replays_to_run = self.a.surprise_n
        if getattr(self.a, "replay_budget", -1) >= 0:
            rem = self.a.replay_budget - self.replay_spent
            if rem < self.a.surprise_k:
                return None
            replays_to_run = min(replays_to_run, rem)
        log(f"{self.target}: s{seed} e{rec['ep']} surprise {found}; replaying {replays_to_run}x for {count}")
        hits, done, ok = self.reproduce(seed, f"{rec['ep']}x", rec["bots"], count, need, replays_to_run,
                                        self.a.surprise_k, origin="replay", replay_of=rec["ep"], gen=gen, out=out,
                                        console=rec.get("console"))
        self.replay_spent += done
        entry = {"seed": seed, "gen": gen, "ep": rec["ep"], "count": count, "net": found[count], "need": need,
                 "counts": found, "k": hits, "n": done, "reproduced": ok}
        append_jsonl(os.path.join(self.tdir, "surprises.jsonl"), entry)
        rec["surprise"] = {k: entry[k] for k in ("count", "k", "n", "reproduced")}
        if ok:
            self.candidates.append(dict(entry, rec=rec))
            if self.search.confirm(f"replay e{rec['ep']} {count} {hits} of {done}"):
                log(f"{self.target}: exploiting: {self.search.reason}")
        log(f"{self.target}: s{seed} e{rec['ep']} surprise {count} reproduced {hits} of {done}"
            + (" -> candidate" if ok else " (one-off)"))
        return entry

    def check_findings(self, seed: int, rec: dict, gen: int) -> list[dict]:
        """Replay an episode that reached an untested target finding signature
        (--finding-n times, fresh names, one run each, from the --replay-budget
        surprises use too): k >= --finding-k reproductions confirm it. A
        signature the control produced is harness or vanilla noise and is never
        replayed. Results go to finding-replays.jsonl."""
        if self.target == DIRECT or self.a.finding_n <= 0 or rec["origin"] in RERUN_ORIGINS:
            return []
        out = []
        sigs = [f["signature"] for f in rec.get("findings") or []
                if f.get("target_attributed") and not f.get("in_control") and f["signature"] not in self.finding_tests]
        for sig in sigs:
            n, k = self.a.finding_n, self.a.finding_k
            if getattr(self.a, "replay_budget", -1) >= 0:
                n = min(n, self.a.replay_budget - self.replay_spent)
                if n < k:
                    log(f"{self.target}: s{seed} e{rec['ep']} finding {sig} not replayed: replay budget spent")
                    break
            hits, done = self.reproduce_finding(seed, f"{rec['ep']}f{len(self.finding_tests)}", rec["bots"], sig, n, k,
                                                rec.get("console"), gen=gen)
            self.replay_spent += done
            f = next(x for x in rec["findings"] if x["signature"] == sig)
            entry = {"seed": seed, "gen": gen, "ep": rec["ep"], "signature": sig, "class": f["class"],
                     "oracle": f["oracle"], "key": f["key"], "k": hits, "n": done, "reproduced": hits >= k,
                     "bots": rec["bots"], "console": rec.get("console")}
            self.finding_tests[sig] = entry
            append_jsonl(os.path.join(self.tdir, FINDING_REPLAYS_FILE), entry)
            log(f"{self.target}: s{seed} e{rec['ep']} finding {sig} [{f['class']}] reproduced {hits} of {done}"
                + (" -> confirmed" if entry["reproduced"] else " (one-off)"))
            out.append(entry)
        return out

    def reproduce_finding(self, seed: int, tag: str, bots: list[dict], sig: str, n: int, k: int,
                          console: list[dict] | None, origin: str = "finding", gen: int = 0) -> tuple[int, int]:
        """(hits, runs): replays of bots under fresh names in which the oracles
        report `sig` again. A replay whose bots never ran counts as neither;
        stops once k hits are out of reach."""
        hits = done = 0
        for j in range(n):
            plan = {"origin": origin, "parent": tag, "params": None, "console": console,
                    "bots": [{"name": bot_name(self.cfg["run"], seed, f"{tag}r{j}", i), "program": b["program"]}
                             for i, b in enumerate(bots)]}
            r = self.execute(seed, f"{tag}r{j}", plan, None, gen=gen, reps=1, replay=origin == "finding")
            if "ms" not in r:
                continue
            done += 1
            hits += any(f["signature"] == sig for f in r.get("findings") or [])
            if hits + (n - j - 1) < k:
                break
        return hits, done

    def shrink_findings(self, seed: int, limit: int) -> list[dict]:
        """Delta-debug up to `limit` confirmed findings of this seed down to the
        fewest actions that still reproduce the signature k of n times
        (--shrink-n/--shrink-k, --shrink-probes, the shared --shrink-budget);
        written to shrunk.jsonl with count `finding:<signature>`."""
        out = []
        todo = [t for t in self.finding_tests.values() if t["seed"] == seed and t["reproduced"] and "sequence" not in t]
        for ti, t in enumerate(todo[:max(0, limit)]):
            left = self.a.shrink_probes
            if getattr(self.a, "shrink_budget", -1) >= 0:
                left = min(left, max(0, self.a.shrink_budget - self.shrink_spent))
            budget = {"left": left, "used": 0}

            def probe(bots, t=t, ti=ti, budget=budget):
                if budget["left"] <= 0:
                    return 0, 0, False
                budget["left"] -= 1
                budget["used"] += 1
                self.shrink_spent += 1
                hits, done = self.reproduce_finding(seed, f"{t['ep']}g{ti}s{budget['used']}", bots, t["signature"],
                                                    self.a.shrink_n, self.a.shrink_k, t.get("console"), origin="shrink")
                return hits, done, hits >= self.a.shrink_k

            res = shrink_bots(t["bots"], probe)
            seq = [[action_label(a) for a in b["program"]] for b in res["bots"]]
            line = {"seed": seed, "ep": t["ep"], "count": f"finding:{t['signature']}", "signature": t["signature"],
                    "budget_exhausted": budget["left"] <= 0, **res, "sequence": seq}
            append_jsonl(os.path.join(self.tdir, "shrunk.jsonl"), line)
            if res["reproduced"]:
                t["sequence"] = seq
            log(f"{self.target}: shrunk e{t['ep']} finding {t['signature']}: {res['actions']} of {res['of']} actions, "
                f"{res['hits']} of {res['n']} reproduced: {seq}")
            out.append(line)
        return out

    def end_generation(self, seed: int, gen: int, cem: "Cem | None", gen_recs: list[dict],
                       all_recs: list[dict], corpus: Corpus, gen_surprises=(), rescore=()) -> dict:
        """Refit on every usable episode so far, report unexplained finds (kept in
        the corpus), update the search state, then move the CEM towards this
        generation's elite."""
        self.rescreen(all_recs)
        self.fit = fit_from_records(all_recs, self.a.ridge_lam,
                                    [c[len(CLS):] for c in self.score_counts if c.startswith(CLS)])
        flags = flag_features(self.fit, self.ref, self.classes_seen)
        if flags and self.target != DIRECT and self.search.confirm(
                f"flag {flags[0]['count']}~{flags[0]['feature']}"):
            log(f"{self.target}: exploiting: {self.search.reason}")
        for r in list(gen_recs) + [r for r in rescore if r.get("deltas") is not None]:
            # Rescore with this generation's screen (the trace keeps the at-run score);
            # `rescore` holds the leak re-runs, which only screened classes can score.
            r["score"] = score_episode(r, self.score_counts, self.ref, self.class_z, target_fit=self.fit)
        for r in gen_recs:
            un = unexplained_counts(r, self.fit, class_z=self.class_z)
            if un:
                r["unexplained"] = un
                if not any(e["ep"] == r["ep"] for e in corpus.entries):
                    corpus.entries.append({"ep": r["ep"], "bots": r["bots"]})
                    if len(corpus.entries) > corpus.cap:
                        corpus.entries.pop(0)
                append_jsonl(os.path.join(self.tdir, "unexplained.jsonl"),
                             {"seed": seed, "gen": gen, "ep": r["ep"], "origin": r["origin"], "counts": un,
                              "features": r.get("features"), "label": r.get("label")})
                log(f"{self.target}: s{seed} e{r['ep']} unexplained {un}")
        scored = [r for r in gen_recs if r["origin"] == "cem" and r.get("score") is not None and r.get("params")]
        scores_all = [r["score"] for r in gen_recs if r.get("score") is not None]
        line = {"seed": seed, "gen": gen, "episodes": len(gen_recs), "scored": len(scores_all),
                "cem_scored": len(scored), "mean_score": round(sum(scores_all) / len(scores_all), 6)
                if scores_all else None, "flags": flags[:10], "optimise": cem is not None,
                "classes_seen": self.classes_seen,
                "screen": [{k: s[k] for k in ("cls", "cum", "z")} for s in self.screen],
                "departure_screen": [{k: s[k] for k in ("cls", "per_departure", "z")}
                                     for s in self.departure_screen],
                "surprises": [{k: s[k] for k in ("ep", "count", "k", "n")} for s in gen_surprises],
                "novelty_used": self.search.novelty}
        if cem is not None:
            line["sampled_from"] = cem.state()
            fresh = rng_for(seed, gen, "restart") if self.search.searching else None
            res = cem.update([r["params"] for r in scored], [r["score"] for r in scored], fresh)
            line.update(res)
            line["net_leak_per_min"] = res["best_score"]
            line["params"] = cem.params()
            line["next"] = cem.state()
        if line.get("restarted"):
            self.coverage["restarts"] += 1
        line["improved"] = self.search.generation(max(scores_all) if scores_all else None)
        line.update(state=self.search.state, novelty=self.search.novelty, reason=self.search.reason,
                    stalls=self.search.stalls)
        append_jsonl(self.opt_path, line)
        save_json(self.fit_path, {"fit": self.fit, "flags": flags, "control": bool(self.control),
                                  "score_counts": self.score_counts, "screen": self.screen,
                                  "departure_screen": self.departure_screen,
                                  "classes_seen": self.classes_seen})
        top = ", ".join(f"{f['count']}~{f['feature']} {f['coef']:+.2f}" for f in flags[:3]) or "none"
        log(f"{self.target}: s{seed} gen {gen}: mean score {line['mean_score']}, best "
            f"{line.get('best_score')}{' RESTART(' + str(line['restarted']) + ')' if line.get('restarted') else ''}; "
            f"flags: {top}; {self.search.state}"
            + (" (no signal yet)" if self.search.searching else "") + f", novelty next {self.search.novelty:.2f}")
        return line

    def drain_promotions(self, seed: int, gen: int) -> tuple[list[dict], list[dict]]:
        """Leak re-runs of the promising fault episodes queued in this generation:
        a fresh baseline, the same programs with checkpoints and --reps, then the
        surprise check. At most --promote-max run; the rest, and any without a
        clean baseline, are logged to ladder.jsonl as dropped. Re-runs never enter
        the corpus. Returns (re-runs and their replays, surprise entries); the
        re-runs climb the ladder like any other measured episode."""
        if not self.promotions:
            return [], []
        queue, self.promotions = self.promotions, []
        out, surprises = [], []
        for i, p in enumerate(queue):
            cand = f"s{seed}e{p['ep']}"
            why = None
            if i >= self.a.promote_max:
                why = "budget"
            elif not self.baseline(seed, f"p{p['ep']}b"):
                why = "baseline"
            if why:
                if why == "baseline":
                    self.dirty = True  # the failed baseline saw players online
                append_jsonl(os.path.join(self.tdir, "ladder.jsonl"),
                             {"cand": cand, "origin": "leakrerun", "gen": gen, "dropped": why})
                log(f"{self.target}: {cand} leak re-run dropped ({why})")
                continue
            self.dirty = False  # the baseline settled: no other player is online
            plan = {"origin": "leakrerun", "parent": p["ep"], "params": None, "console": p["console"],
                    "bots": scale_bots(p["bots"], len(p["bots"]), self.cfg["run"], seed, f"p{p['ep']}")}
            r = self.execute(seed, f"p{p['ep']}", plan, None, gen=gen, leak=True)
            batch = [r]
            if usable(r):
                replays: list[dict] = []
                s = self.check_surprise(seed, r, gen, replays)
                batch += replays
                if s:
                    surprises.append(s)
            out += batch
            if not usable(r):
                confirm = {"status": "not measured", "reason": r.get("excluded") or "no leak measurement"}
            else:
                confirm = {"status": "pending", "reason": "final confirm only on a ladder climb"}
            append_jsonl(os.path.join(self.tdir, "ladder.jsonl"),
                         {"cand": cand, "climb_cand": f"s{seed}ep{p['ep']}", "origin": "leakrerun",
                          "gen": gen, "probes": p["probes"],
                          "signatures": p["signatures"], "excluded": r.get("excluded"),
                          "reps": r.get("reps"), "reps_error": r.get("reps_error"), "score": r.get("score"),
                          "label": r.get("label"), "surprise": r.get("surprise"), "confirm": confirm})
            # Any player left online by the re-run, its repetitions or its replays: settle before the next episode.
            self.dirty = self.dirty or any(x.get("excluded") or x.get("reps_error") or x.get("lingering")
                                           or x.get("foreign_online") for x in batch)
        self.prev, self.prev_histo = None, {}
        return out, surprises

    def run_seed(self, seed: int, params: dict | None = None) -> list[dict]:
        """Generations of --generation episodes; each seed starts its own CEM
        from the prior (an independent restart)."""
        params = params or DEFAULT_PARAMS
        t0 = time.time()  # the exploration clock behind episodes_per_hour
        cem = None if self.a.no_optimise else Cem(params, self.a.elite, self.a.cem_alpha, self.a.stall_gens)
        corpus = Corpus()
        recs: list[dict] = []
        gen_recs: list[dict] = []
        size = max(1, self.a.generation)
        if not self.faults:
            self.baseline(seed, "base")
        self.replay_spent = 0
        self.shrink_spent = 0
        spent, ep = 0, 0
        gen_surprises: list[dict] = []
        # Replays on surprise do not consume the planned exploration budget.
        while spent < self.a.episodes:
            gen = spent // size
            self.cfg["novelty"] = self.search.novelty
            plan = plan_episode(seed, ep, cem.params() if cem else params, corpus, self.cfg, cem)
            rec = self.execute(seed, ep, plan, corpus, gen=gen)
            recs.append(rec)
            gen_recs.append(rec)
            spent += 1
            if "ms" in rec:  # bots ran: an early exclusion (dirty start, no baseline) is not an executed episode
                self.coverage["executed"] += 1
            replays: list[dict] = []
            s = self.check_surprise(seed, rec, gen, replays)
            if s:
                gen_surprises.append(s)
            recs += replays
            # Finding replays run once each (no slope) and stay out of the fit.
            self.check_findings(seed, rec, gen)
            ep += 1
            stop = self.over_heap()
            if len(gen_recs) == size or spent >= self.a.episodes or stop:
                extra, extra_surprises = self.drain_promotions(seed, gen)
                recs += extra
                gen_surprises += extra_surprises
                self.end_generation(seed, gen, cem, gen_recs, recs, corpus, gen_surprises, rescore=extra)
                gen_recs, gen_surprises = [], []
            if stop:
                log(f"{self.target}: heap after GC above {self.heap_stop:.0f} MB; stopping this target")
                self.coverage["heap_stopped"] = True
                break
        if cem is not None:
            self.learnt[seed] = cem.params()
        self.coverage["explore_s"] += time.time() - t0
        self.coverage["episodes"] += len(recs)
        self.coverage["planned"] += spent
        self.coverage["pairs"] |= corpus.pairs
        self.coverage["seeds"].append(seed)
        kept = len(corpus.entries)
        log(f"{self.target}: seed {seed}: {len(recs)} episodes, corpus {kept}, states {len(corpus.pairs)}")
        return recs

    def reproduce(self, seed: int, tag: str, bots: list[dict], count: str, need: float,
                  n: int, k: int, origin: str = "shrink", replay_of=None, gen: int = 0,
                  out: list | None = None, console: list[dict] | None = None) -> tuple[int, int, bool]:
        """Replays bots up to n times under fresh names; a run hits when the
        count's delta minus the control's prediction for the run's own
        features reaches `need`. Stops early once k hits are out of reach.
        The replay records are appended to `out` when given."""
        hits = done = 0
        for j in range(n):
            plan = {"origin": origin, "parent": tag, "params": None, "replay_of": replay_of,
                    "bots": [{"name": bot_name(self.cfg["run"], seed, f"{tag}r{j}", i), "program": b["program"]}
                             for i, b in enumerate(bots)], "console": console}
            r = self.execute(seed, f"{tag}r{j}", plan, None, gen=gen, leak=True)
            if out is not None:
                out.append(r)
            if not usable(r, count):
                continue  # a dirty run (or no histogram for a class) is neither a hit nor a miss
            done += 1
            net = float(r["deltas"].get(count, 0) or 0) - predict(self.ref.get(count) or self.fit.get(count), r["features"])
            hits += net >= need
            if hits + (n - j - 1) < k:
                break
        return hits, done, hits >= k

    def shrink(self, seed: int, rec: dict, count: str) -> dict:
        """Delta-debug a leaking episode down to the fewest actions that still
        reproduce `count` k of n times (--shrink-n/--shrink-k), within
        --shrink-probes candidates; written to shrunk.jsonl."""
        need = required_effect(rec, count, self.ref, target_fit=self.fit)
        probes_left = self.a.shrink_probes
        if getattr(self.a, "shrink_budget", -1) >= 0:
            rem = max(0, self.a.shrink_budget - self.shrink_spent)
            probes_left = min(probes_left, rem)
        budget = {"left": probes_left}

        def probe(bots):
            if budget["left"] <= 0:
                return 0, 0, False
            budget["left"] -= 1
            self.shrink_spent += 1
            tag = f"{rec['ep']}s{self.a.shrink_probes - budget['left']}"
            return self.reproduce(seed, tag, bots, count, need, self.a.shrink_n, self.a.shrink_k,
                                  console=rec.get("console"))

        res = shrink_bots(rec["bots"], probe)
        out = {"seed": seed, "ep": rec["ep"], "count": count, "need": need, "budget_exhausted": budget["left"] <= 0,
               **res, "sequence": [[action_label(a) for a in b["program"]] for b in res["bots"]]}
        append_jsonl(os.path.join(self.tdir, "shrunk.jsonl"), out)
        log(f"{self.target}: shrunk e{rec['ep']} {count}: {out['actions']} of {out['of']} actions, "
            f"{out['hits']} of {out['n']} reproduced{' (budget exhausted)' if out['budget_exhausted'] else ''}: "
            f"{out['sequence']}")
        return out

    def over_heap(self) -> bool:
        return bool(self.heap_stop and self.prev and self.prev.get(HEAP, 0) > self.heap_stop)

    def shrink_top(self, seed: int, recs: list[dict], limit: int) -> list[dict]:
        """Shrink up to `limit` episodes: reproduced surprises first, then the
        best-scoring episodes whose top count is flagged."""
        return [self.shrink(seed, r, c) for r, c, _ in self.pick_candidates(seed, recs, limit)
                if not is_attribution(c)]

    def pick_candidates(self, seed: int, recs: list[dict], limit: int) -> list[tuple[dict, str, str]]:
        """(episode, count, source): reproduced surprises first, then the
        best-scoring episodes whose largest control-subtracted count is flagged."""
        ref = self.ref
        flagged = {f["count"] for f in flag_features(self.fit, ref, self.classes_seen)
                   if not is_attribution(f["count"])}
        cands = [(math.inf, c["rec"], c["count"], "surprise") for c in self.candidates
                 if c["seed"] == seed and not is_attribution(c["count"])]
        seen = {str(c[1]["ep"]) for c in cands}
        for r in recs:
            if (r.get("score") is None or r["score"] <= 0 or r["origin"] in ("shrink", "replay")
                    or str(r["ep"]) in seen):
                continue
            nets = {c: (float(r["deltas"].get(c, 0) or 0) - predict(ref.get(c) or self.fit.get(c), r["features"]))
                    for c in flagged if usable(r, c)}
            c = max(nets, key=lambda x: (nets[x], x), default=None)
            if c is not None and nets[c] >= 1:
                cands.append((r["score"], r, c, "flag"))
        cands.sort(key=lambda x: (-x[0], str(x[1]["ep"])))
        return [(r, c, s) for _, r, c, s in cands[:limit]]

    def ladder_candidates(self, seed: int, recs: list[dict], shrunk: list[dict]) -> list[dict]:
        """Episodes to climb the ladder (the shrunk sequence when it reproduced),
        plus the learnt behaviour: a fresh episode from this seed's final
        generator parameters, aimed at the top candidate's count."""
        sh = {str(s["ep"]): s for s in shrunk if s.get("reproduced")}
        out = []
        for rec, count, source in self.pick_candidates(seed, recs, self.a.ladder_candidates):
            if is_attribution(count):
                continue
            s = sh.get(str(rec["ep"]))
            out.append({"cand": f"s{seed}e{rec['ep']}", "seed": seed, "ep": rec["ep"], "count": count,
                        "source": source, "shrunk": bool(s), "bots": s["bots"] if s else rec["bots"],
                        "console": rec.get("console"),
                        "net0": required_effect(rec, count, self.ref, 1.0, target_fit=self.fit),
                        "bots0": len(rec["bots"])})
        if out and self.learnt.get(seed):
            bots = gen_episode(seed, "learnt", self.learnt[seed], self.cfg)
            out.append(dict(out[0], cand=f"s{seed}learnt", ep=None, source="learnt", shrunk=False, bots=bots,
                            params=self.learnt[seed]))
        return out

    def dump_path(self, d: dict | None) -> str | None:
        h = (d or {}).get("_histo") or ""
        if not h.endswith(".histo.txt"):
            return None
        return os.path.join(self.reader.runs_dir, h[:-len(".histo.txt")] + ".hprof")

    def run_rung(self, c: dict, ri: int, bots_n: int, reps: int, top: bool) -> dict:
        """One candidate at one rung: its own baseline (a heap dump at the top
        rung with --dump), reps episodes of bots_n bots cycling its programs,
        per-rep nets of its count over the control's prediction for each
        rep's features. The top rung applies the pass/fail rule; on failure a
        final dump and heap_owner.py name the owner. Written to ladder.jsonl."""
        seed, count = c["seed"], c["count"]
        tag = f"{c['cand']}L{ri}"
        dump = top and self.a.dump
        self.baseline(seed, f"{tag}b", "dump" if dump else "quick")
        base_dump = self.dump_path(self.prev) if dump else None
        fc, rc, hc = self.fit.get(count), (self.ref.get(count) or self.fit.get(count)), (self.control_heap or {}).get(HEAP)
        nets, preds, heaps = [], [], []
        stopped = False
        for j in range(reps):
            plan = {"origin": "rung", "parent": c["cand"], "params": c.get("params"), "console": c.get("console"),
                    "bots": scale_bots(c["bots"], bots_n, self.cfg["run"], seed, f"{tag}r{j}")}
            r = self.execute(seed, f"{tag}r{j}", plan, None, rung=ri + 1, leak=True)
            if usable(r, count):
                f = r["features"]
                nets.append(float(r["deltas"].get(count, 0) or 0) - predict(rc, f))
                p = predict(fc, f) - predict(rc, f) if fc and rc is not fc else 0.0
                preds.append(p if p > 0 else c["net0"] * bots_n / max(1, c["bots0"]))
                heaps.append(float(r["deltas"].get(HEAP, 0) or 0) - predict(hc, f))
            if self.over_heap():
                stopped = True
                break
        res = interval(nets)
        noise = (rc or {}).get("sd", (rc or {}).get("noise", 1.0))
        res.update(cand=c["cand"], count=count, source=c["source"], rung=ri, bots=bots_n, reps=reps,
                   nets=[round(v, 3) for v in nets], pred_mean=round(sum(preds) / len(preds), 3) if preds else None,
                   noise=noise, heap_stop=stopped,
                   sequence=[[action_label(x) for x in b["program"]] for b in c["bots"]])
        res["holds"] = holds(res)
        if top:
            conf = confirm_rule(sum(nets), sum(preds), sum(heaps), (hc or {}).get("noise", 1.0), len(nets))
            if stopped:
                conf.update(fail=True, heap_stop=True)  # exhausting the heap is the leak, confirmed
            res["confirm"] = conf
            if conf["fail"] and dump:
                self.settle()
                label = self.label(seed, f"{tag}end")
                err = quick_checkpoint(self.drv, label, self.a.dump_wait_s, self.a.checkpoint_poll_s, "dump")
                d = None if err else self.reader.fetch(label, self.a.files_wait_s)
                res["dumps"] = {"baseline": base_dump, "final": self.dump_path(d), "error": err}
                res["owner"] = self.owner(base_dump, self.dump_path(d))
        append_jsonl(os.path.join(self.tdir, "ladder.jsonl"), res)
        log(f"{self.target}: rung {ri} {bots_n}x{reps} {c['cand']} {count}: mean net {res['mean']} ci {res['ci']} "
            f"pred {res['pred_mean']} -> {'holds' if res['holds'] else 'lost'}"
            + (f"; {'FAIL' if res['confirm']['fail'] else 'not confirmed'} {res['confirm']}" if top else ""))
        return res

    def owner(self, baseline: str | None, final: str | None) -> dict:
        """heap_owner.py on the two dumps when --mat is given, else the command to run."""
        if not final:
            return {"status": "no final dump"}
        out = os.path.join(self.tdir, "owner.txt")
        cmd = [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "heap_owner.py"),
               "--mat", self.a.mat or "<ParseHeapDump.sh>", "--dump", final,
               "--plugins-dir", os.path.join(self.a.server_dir, "plugins"), "--out", out]
        if baseline:
            cmd[cmd.index("--plugins-dir"):cmd.index("--plugins-dir")] = ["--baseline", baseline]
        if not self.a.mat:
            return {"status": "not run (no --mat)", "command": " ".join(cmd)}
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=self.a.owner_timeout_s)
        except (OSError, subprocess.TimeoutExpired) as e:
            return {"status": f"failed: {e}", "command": " ".join(cmd)}
        owners = sorted(set(re.findall(r"OWNER: ([^\n(]+)", p.stdout)))
        return {"status": f"exit {p.returncode}", "owners": [o.strip() for o in owners], "report": out}

    def climb(self, cands: list[dict]) -> list[dict]:
        """Successive halving up --rungs: each rung reruns the survivors with
        more bots and reps; only the top 1/--eta whose signal still holds go
        up. A candidate that loses its signal is a micro-only artefact."""
        rungs = parse_rungs(self.a.rungs)
        alive, out = list(cands), []
        for ri, (nb, reps) in enumerate(rungs):
            top = ri == len(rungs) - 1
            results = []
            for c in alive:
                results.append(self.run_rung(c, ri, nb, reps, top))
                if self.coverage["heap_stopped"] or self.over_heap():
                    self.coverage["heap_stopped"] = True
                    break
            out += results
            if top or self.coverage["heap_stopped"]:
                break
            kept = {r["cand"] for r in promote(results, self.a.eta)}
            for r in results:
                if r["cand"] not in kept:
                    why = "micro-only" if not r["holds"] else "halved"
                    append_jsonl(os.path.join(self.tdir, "ladder.jsonl"),
                                 {"cand": r["cand"], "dropped_at": ri, "why": why})
                    log(f"{self.target}: {r['cand']} dropped at rung {ri} ({why})")
            alive = [c for c in alive if c["cand"] in kept]
            if not alive:
                break
        return out

    def finish(self, cands: list[dict], ladder: list[dict]) -> dict:
        """verdict.json: the target's verdict, the confirmations and the coverage."""
        confirmed = [dict(r["confirm"], cand=r["cand"], count=r["count"], owner=r.get("owner"),
                          dumps=r.get("dumps"), sequence=r["sequence"]) for r in ladder if "confirm" in r]
        if self.coverage["heap_stopped"] and not confirmed:
            confirmed = [{"fail": True, "heap_stop": True, "cand": None, "count": HEAP}]
        findings = summarise_findings(self.findings, self.finding_tests)
        verdict = target_verdict(self.search.searching, confirmed, bool(cands or self.candidates), findings)
        by = (["leak"] if any(c["fail"] for c in confirmed) else []) + sorted(
            {f["oracle"] for f in findings if f.get("reproduced")})
        cov = dict(self.coverage, pairs=len(self.coverage["pairs"]), classes_tested=self.classes_seen,
                   finding_signatures=len(findings),
                   episodes_per_hour=episodes_per_hour(self.coverage["executed"], self.coverage["explore_s"]))
        out = {"target": self.target, "verdict": verdict, "confirmed_by": by, "state": self.search.state,
               "reason": self.search.reason,
               "candidates": [{k: c[k] for k in ("cand", "count", "source", "shrunk")} for c in cands],
               "confirmed": confirmed, "findings": findings, "coverage": cov, "learnt": self.learnt}
        save_json(os.path.join(self.tdir, VERDICT_FILE), out)
        log(f"{self.target}: verdict {verdict}" + (f" by {', '.join(by)}" if by else "")
            + (" (nothing found within budget)" if verdict == "PASS" else "") + f"; coverage {cov}")
        return out


def load_trace(path: str, ep, seed: int | None = None) -> dict:
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            if str(r.get("ep")) == str(ep) and (seed is None or r.get("seed") == seed):
                return r
    raise SystemExit(f"episode {ep} not in {path}")


def replay_plan(rec: dict, k: int, run: str = "") -> dict:
    """The traced programs under fresh names: repeats are new offline UUIDs."""
    seed = int(rec.get("seed", 0))
    bots = [{"name": bot_name(run, seed, f"{rec['ep']}r{k}", i), "program": b["program"]}
            for i, b in enumerate(rec["bots"])]
    return {"origin": "replay", "parent": rec["ep"], "bots": bots, "params": rec.get("params"),
            "console": rec.get("console")}


# --- report ---------------------------------------------------------------------

def render_report(run_dir: str, targets: list[str], meta: dict) -> str:
    """report.txt from each target's files: verdict, coverage, top predicted
    leaks, top growing classes net of the control, the leak-rate curve per
    generation, learnt parameters, shrunk sequences, surprises (k of n),
    unexplained finds, the ladder and the owner."""
    L = [f"behaviour fuzz report: {os.path.basename(run_dir)}",
         f"targets {targets}; seeds {meta.get('seeds')}; episodes/seed {meta.get('episodes')}; "
         f"grant {meta.get('grant')}; rungs {meta.get('rungs')}", ""]
    for t in targets:
        tdir = os.path.join(run_dir, t)
        v = load_json(os.path.join(tdir, VERDICT_FILE)) or {}
        verdict = v.get("verdict", "NOT RUN")
        L.append(f"=== {t}: {verdict}"
                 + (" - nothing found within budget (not proof that there is none)" if verdict == "PASS" else ""))
        cov = v.get("coverage") or {}
        if cov:
            L.append(f"  coverage: {cov.get('episodes', 0)} episodes, {cov.get('pairs', 0)} distinct (action, state) "
                     f"pairs, {cov.get('restarts', 0)} restarts, seeds {cov.get('seeds')}, "
                     f"{cov.get('classes_tested', 0)} classes tested"
                     + (", stopped at the heap limit" if cov.get("heap_stopped") else ""))
        if v.get("state"):
            L.append(f"  search: {v['state']}" + (f" ({v['reason']})" if v.get("reason") else " (no signal)"))
        if v.get("confirmed_by"):
            L.append(f"  confirmed by: {', '.join(v['confirmed_by'])}")
        findings = v.get("findings") or []
        if findings:
            L.append(f"  findings ({len(findings)} signatures, "
                     f"{sum(1 for f in findings if f.get('reproduced'))} reproduced):")
            for f in sorted(findings, key=lambda f: (not f.get("reproduced"), not f.get("target_attributed"),
                                                     bool(f.get("in_control"))))[:15]:
                ev = f.get("evidence") or {}
                ev_str = ev.get("line") or ev.get("cmd") or (ev.get("log_snippet") or "")[:80]
                if f.get("reproduced"):
                    status = f"reproduced {f.get('k')} of {f.get('n')}"
                elif f.get("n"):
                    status = f"one-off ({f.get('k')} of {f.get('n')})"
                elif f.get("in_control"):
                    status = "also in the control"
                elif f.get("target_attributed"):
                    status = "not replayed"
                else:
                    status = "not the target"
                L.append(f"    {f.get('signature', '?')} [{f.get('class')}] {f.get('severity')} "
                         f"({f.get('attributed_plugin')}) x{f.get('seen', 1)}, {status}: {ev_str}")
                if f.get("shrunk"):
                    L.append(f"      shrunk: {f['shrunk']}")
        for c in v.get("confirmed") or []:
            L.append(f"  confirmation {c.get('cand')} {c.get('count')}: {'FAIL' if c.get('fail') else 'not confirmed'}"
                     f" growth {c.get('count_growth')} vs predicted {c.get('predicted')}, heap net "
                     f"{c.get('heap_net_mb')} MB" + (" (heap limit reached)" if c.get("heap_stop") else ""))
            if c.get("sequence"):
                L.append(f"    sequence: {c['sequence']}")
            own = c.get("owner") or {}
            if own:
                L.append(f"    owner: {', '.join(own.get('owners') or []) or own.get('status')}"
                         + (f"; run: {own['command']}" if own.get("command") else ""))
        fit = load_json(os.path.join(tdir, "fit.json")) or {}
        flags = fit.get("flags") or []
        if flags:
            L.append("  top predicted leaks:")
            for f in flags[:5]:
                L.append(f"    {f['coef']:+.3f} {f['count']} per {f['feature']} (95 % {f['ci']}, control "
                         f"{f.get('control_coef')}, n={f['n']})")
        if fit.get("screen"):
            L.append("  top growing classes net of the control (cumulative z):")
            for s in fit["screen"][:8]:
                L.append(f"    {s['cls']}: +{s['cum']} over {s['n']} episodes, z {s['z']}"
                         + ("" if s.get("in_control") else " (absent from the control)"))
        opt = read_trace(os.path.join(tdir, "optimiser.jsonl"))
        if opt:
            L.append("  leak rate per generation (seed gen: mean / best net leak per min, state, novelty):")
            for x in opt:
                L.append(f"    s{x.get('seed')} g{x.get('gen')}: {x.get('mean_score')} / {x.get('best_score')} "
                         f"{x.get('state', '')} {x.get('novelty_used', '')}"
                         + (f" restart({x['restarted']})" if x.get("restarted") else ""))
        for s, p in sorted((v.get("learnt") or {}).items()):
            L.append(f"  learnt parameters seed {s}: {json.dumps(p, sort_keys=True)}")
        for s in read_trace(os.path.join(tdir, "shrunk.jsonl")):
            L.append(f"  shrunk e{s['ep']} {s['count']}: {s['sequence']} ({s['hits']} of {s['n']}, "
                     f"{s['actions']} of {s['of']} actions)")
        for s in read_trace(os.path.join(tdir, "surprises.jsonl")):
            L.append(f"  surprise e{s['ep']} {s['count']} +{s['net']}: reproduced {s['k']} of {s['n']}"
                     + (" -> candidate" if s["reproduced"] else " (one-off)"))
        un = read_trace(os.path.join(tdir, "unexplained.jsonl"))
        if un:
            L.append(f"  unexplained finds: {len(un)}; first: "
                     + "; ".join(f"e{u['ep']} {u['counts']}" for u in un[:3]))
        for r in read_trace(os.path.join(tdir, "ladder.jsonl")):
            if "dropped_at" in r:
                L.append(f"  ladder: {r['cand']} dropped at rung {r['dropped_at']} ({r['why']})")
            else:
                L.append(f"  ladder rung {r['rung']} {r['bots']}x{r['reps']} {r['cand']} {r['count']}: mean "
                         f"{r['mean']} ci {r['ci']} -> {'holds' if r['holds'] else 'lost'}")
        L.append("")
    L.append("FAIL = a leak (the count grew by >= half the predicted amount at the top rung and heap after GC "
             "grew beyond the control's for the same behaviour) or a finding attributed to the target, absent "
             "from the control, that replays reproduced k of n times. PASS = the full budget ran without any "
             "signal. INCONCLUSIVE = a signal that did not confirm (a leak lost at scale, a one-off finding).")
    return "\n".join(L) + "\n"


# --- main -----------------------------------------------------------------------

def order_targets(targets: list[str]) -> list[str]:
    """The direct control runs first, on the same seeds."""
    return ([t for t in targets if t == DIRECT] + [t for t in targets if t != DIRECT])


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--server-dir", required=True, help="server root (RCON settings, plugins/, runs files)")
    p.add_argument("--host", default="127.0.0.1", help="RCON host (and game host unless --mc-host)")
    p.add_argument("--port", type=int, help="RCON port (default server.properties rcon.port)")
    p.add_argument("--password", default=os.environ.get("RCON_PASSWORD"))
    p.add_argument("--mc-host", help="game host for the bots (default --host)")
    p.add_argument("--mc-port", type=int, help="game port (default server.properties server-port)")
    p.add_argument("--mc-version", help="pass --version to the swarm (default auto-detect)")
    p.add_argument("--targets", default="direct,betterrtp,rtp")
    p.add_argument("--cmd", help="target=command overrides, e.g. 'rtp=rtp,betterrtp=betterrtp:rtp'; "
                                 "{self} = bot name")
    p.add_argument("--rtp-args", help="comma list of argument strings sampled for rtp ('' allowed)")
    p.add_argument("--seeds", default="1")
    p.add_argument("--episodes", type=int, default=80, help="episodes per seed per target")
    p.add_argument("--generation", type=int, default=8, help="episodes per generation (trace 'gen')")
    p.add_argument("--novelty", type=float, default=0.25, help="share of novelty + mutation episodes")
    p.add_argument("--novelty-max", type=float, default=0.6, help="novelty cap while no signal is found")
    p.add_argument("--novelty-step", type=float, default=0.1, help="novelty rise per stalled generation")
    p.add_argument("--surprise-z", type=float, default=4.0,
                   help="growth beyond the control, in noise units, that triggers an immediate replay")
    p.add_argument("--surprise-n", type=int, default=3, help="replays of a surprising episode (0 = off)")
    p.add_argument("--surprise-k", type=int, default=2, help="replays that must reproduce it")
    p.add_argument("--replay-budget", type=int, default=20,
                   help="max surprise replay episodes per seed (-1 = unbounded)")
    p.add_argument("--shrink-budget", type=int, default=48,
                   help="max shrink probe executions per seed (-1 = unbounded)")
    p.add_argument("--no-optimise", action="store_true", help="keep the generator at its prior (no CEM)")
    p.add_argument("--elite", type=float, default=0.2, help="CEM elite share of a generation's scored episodes")
    p.add_argument("--cem-alpha", type=float, default=0.7, help="CEM smoothing: weight of the new elite fit")
    p.add_argument("--stall-gens", type=int, default=3, help="generations without a new best before a restart")
    p.add_argument("--ridge-lam", type=float, default=1.0, help="ridge penalty of the per-count fit")
    p.add_argument("--shrink", type=int, default=2,
                   help="shrink this many candidates (reproduced surprises, then flagged episodes) per seed")
    p.add_argument("--shrink-n", type=int, default=3, help="replays per shrink candidate")
    p.add_argument("--shrink-k", type=int, default=2, help="replays that must reproduce the count")
    p.add_argument("--shrink-probes", type=int, default=24, help="max shrink candidates per episode")
    p.add_argument("--finding-n", type=int, default=3,
                   help="replays of an episode that reached a new target finding signature (0 = off; "
                        "draws on --replay-budget)")
    p.add_argument("--finding-k", type=int, default=2, help="replays that must report the signature again")
    p.add_argument("--finding-shrink", type=int, default=1,
                   help="shrink this many reproduced findings per seed (draws on --shrink-budget)")
    p.add_argument("--plugin-packages",
                   help="extra package=plugin attributions for stack traces, e.g. 'com.example.rtp=ExampleRTP'")
    p.add_argument("--min-bots", type=int, default=2)
    p.add_argument("--max-bots", type=int, default=8)
    p.add_argument("--min-episode-s", type=float, default=20)
    p.add_argument("--max-episode-s", type=float, default=60)
    p.add_argument("--warmup", type=int, default=1,
                   help="unscored target warm-up episodes before the first baseline per target (0 = off)")
    p.add_argument("--max-empty", type=int, default=3,
                   help="abort after this many consecutive planned episodes with no bot spawn")
    p.add_argument("--episode-max-s", type=float, default=180, help="hard per-episode deadline in the swarm")
    p.add_argument("--done-slack-s", type=float, default=20, help="extra wait for done beyond max_ms")
    p.add_argument("--quiet-s", type=float, default=3, help="pause after done before the checkpoint")
    p.add_argument("--reps", type=int, default=3,
                   help="runs of each episode's programs, a checkpoint after each; >= 2 adds slope.<metric> "
                        "(growth per repetition of live bytes, RSS, metaspace, classes, threads, fds, ...) "
                        "to the score: a cache flattens, a leak keeps growing (1 = single delta)")
    p.add_argument("--console", default=None,
                   help="console (RCON) actions per target, e.g. 'rtp=rtp reload|rtp reload config,"
                        "betterrtp=betterrtp reload'; opt-in: they run with full console rights")
    p.add_argument("--console-p", type=float, default=0.3, help="share of episodes with console actions")
    p.add_argument("--console-max", type=int, default=2, help="max console actions per episode")
    p.add_argument("--console-timeout-s", type=float, default=30,
                   help="extra wait for console actions still running when the bots are done")
    p.add_argument("--quiet-wait-s", type=float, default=15, help="max wait for fuzz bots to leave `list`")
    p.add_argument("--checkpoint-wait-s", type=float, default=120)
    p.add_argument("--checkpoint-poll-s", type=float, default=0.5, help="leakcheck status poll interval")
    p.add_argument("--files-wait-s", type=float, default=30, help="wait for rows in the (shared) runs files")
    p.add_argument("--coverage", action="store_true",
                   help="JaCoCo probes as a corpus key source (needs the bench server's -javaagent; ADR-111 2.6)")
    p.add_argument("--mode", choices=("leak", "faults"), default="leak",
                   help="faults: one checkpoint-free run per episode (finding oracles and coverage only); "
                        "needs --coverage")
    p.add_argument("--promote-max", type=int, default=4,
                   help="faults: leak re-runs per generation for episodes that reached new probes or signatures")
    p.add_argument("--top-classes", type=int, default=0, help="per-class deltas kept per episode (0 = all)")
    p.add_argument("--max-classes", type=int, default=64,
                   help="classes screened into the fit and score per generation (0 = none)")
    p.add_argument("--heap-stop-mb", type=float, default=0,
                   help="stop a target above this heap after GC (default --heap-stop-frac of -Xmx in start.sh)")
    p.add_argument("--heap-stop-frac", type=float, default=0.75)
    p.add_argument("--rungs", default="4x1,12x3,48x10", help="fidelity ladder: bots x reps per rung")
    p.add_argument("--eta", type=int, default=3, help="successive halving: promote the top 1/eta per rung")
    p.add_argument("--ladder-candidates", type=int, default=3, help="candidate episodes per seed on the ladder")
    p.add_argument("--explore-extra", type=int, default=-1,
                   help="micro episodes on a fresh seed when no candidate turned up (-1 = --episodes / 2)")
    p.add_argument("--no-dump", dest="dump", action="store_false", help="no heap dumps at the top rung")
    p.add_argument("--dump-wait-s", type=float, default=600, help="wait for a full checkpoint with heap dump")
    p.add_argument("--mat", help="MAT ParseHeapDump.sh: run heap_owner.py on a failure's dumps")
    p.add_argument("--owner-timeout-s", type=float, default=3600)
    p.add_argument("--grant", default="*", help="LuckPerms nodes for group default ('' = none)")
    p.add_argument("--lp-group", default="default")
    p.add_argument("--allow-online", action="store_true", help="run with non-fuzz players online")
    p.add_argument("--replay", metavar="FILE", help="rerun one traced episode (with --ep)")
    p.add_argument("--ep", help="episode index for --replay")
    p.add_argument("--replay-seed", type=int, help="seed of the episode for --replay (default any)")
    p.add_argument("--replay-n", type=int, default=3)
    p.add_argument("--node", default="node")
    p.add_argument("--swarm-js", default=SWARM_JS)
    p.add_argument("--join-delay-ms", type=int, default=0, help="needs connection-throttle -1 at 0")
    p.add_argument("--no-restart", action="store_true")
    p.add_argument("--no-isolate", dest="isolate", action="store_false")
    p.add_argument("--jars", default=bo.DEFAULT_JARS)
    p.add_argument("--names", default=bo.DEFAULT_NAMES)
    p.add_argument("--restart-retries", type=int, default=2)
    p.add_argument("--settle-s", type=int, default=30)
    p.add_argument("--shutdown-wait-s", type=int, default=1200)
    p.add_argument("--startup-wait-s", type=int, default=900)
    p.add_argument("--min-players", type=int, default=0)
    p.add_argument("--player-wait-s", type=int, default=0)
    p.add_argument("--output-subdir", default="runs", help="StressTestRTP output-subdir")
    p.add_argument("--out-dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp"))
    return p


def run_target(a, drv, swarm, target, seeds, run_dir, stamp, w, replay_rec=None) -> None:
    if not lc.prepare_target(a, drv, target, w):
        return
    names = online_names(drv)
    if foreign_online(names) and not a.allow_online:
        log(f"{target}: non-fuzz players online {foreign_online(names)[:5]}; stop the passive swarm "
            "or pass --allow-online. Skipping.")
        w.writerow([target, "skip", "", dt.datetime.now().isoformat(timespec="seconds"), "players online", ""])
        return
    tdir = os.path.join(run_dir, target)
    os.makedirs(tdir, exist_ok=True)
    reader = RunsReader(os.path.join(a.server_dir, "plugins", "StressTestRTP", a.output_subdir))
    tr = TargetRun(a, drv, swarm, reader, target, stamp, tdir)
    log(f"{target}: command '/{tr.cmd}', settling {a.settle_s}s")
    time.sleep(a.settle_s)
    if replay_rec is None and a.warmup:
        tr.warm_up(seeds[0] if seeds else 0)
    t0 = time.time()
    if replay_rec is not None:
        seed = int(replay_rec.get("seed", 0))
        if not tr.faults:
            tr.baseline(seed, "base")
        for k in range(a.replay_n):
            tr.execute(seed, f"{replay_rec['ep']}r{k}", replay_plan(replay_rec, k, tr.cfg["run"]), None,
                       replay=True)
    else:
        tr.heap_stop = heap_stop_mb(a, a.server_dir)
        if tr.faults and tr.heap_stop:
            log(f"{target}: --mode faults has no checkpoint, so the heap limit ({tr.heap_stop:.0f} MB) is not "
                "enforced; watch the server heap or use --mode leak")
        all_recs: list[dict] = []
        by_seed: dict[int, tuple[list, list]] = {}
        for seed in seeds:
            recs = tr.run_seed(seed)
            all_recs += recs
            shrunk = tr.shrink_top(seed, recs, a.shrink) if a.shrink > 0 and target != DIRECT else []
            if target != DIRECT and a.finding_shrink > 0:
                tr.shrink_findings(seed, a.finding_shrink)
            by_seed[seed] = (recs, shrunk)
        if target != DIRECT:
            cands = [c for s, (r, sh) in by_seed.items() for c in tr.ladder_candidates(s, r, sh)]
            extra = a.episodes // 2 if a.explore_extra < 0 else a.explore_extra
            if not cands and extra > 0 and not tr.coverage["heap_stopped"]:
                # No signal: the ladder's budget goes back to exploring, on a fresh seed.
                seed = max(seeds) + 1000
                log(f"{target}: no candidate; {extra} more micro episodes on fresh seed {seed}")
                episodes, a.episodes = a.episodes, extra
                try:
                    recs = tr.run_seed(seed)
                finally:
                    a.episodes = episodes
                all_recs += recs
                shrunk = tr.shrink_top(seed, recs, a.shrink) if a.shrink > 0 else []
                if a.finding_shrink > 0:
                    tr.shrink_findings(seed, a.finding_shrink)
                cands = tr.ladder_candidates(seed, recs, shrunk)
            ladder = tr.climb(cands) if cands and not tr.coverage["heap_stopped"] else []
            tr.finish(cands, ladder)
        if target == DIRECT:
            # Fit over every seed: the reference later targets subtract.
            control = fit_from_records(all_recs, a.ridge_lam)
            save_json(os.path.join(run_dir, CONTROL_FILE), control)
            cstats = class_stats(all_recs)
            save_json(os.path.join(run_dir, CONTROL_CLASSES_FILE), cstats)
            save_json(os.path.join(run_dir, CONTROL_HEAP_FILE), fit_counts(all_recs, [HEAP], a.ridge_lam))
            save_json(os.path.join(run_dir, CONTROL_FINDINGS_FILE), control_findings(tr.findings))
            save_json(os.path.join(tdir, VERDICT_FILE), {"target": target, "verdict": "CONTROL",
                                                         "findings": summarise_findings(tr.findings),
                                                         "coverage": dict(tr.coverage, pairs=len(tr.coverage["pairs"]))})
            log(f"{target}: control class stats over {cstats['n']} episodes, {len(cstats['classes'])} classes")
            log(f"{target}: control fit over {max((c['n'] for c in control.values()), default=0)} "
                f"episodes, counts {sorted(control)}")
    w.writerow([target, "done", "", dt.datetime.now().isoformat(timespec="seconds"),
                f"{(time.time() - t0) / 60:.1f} min", tr.trace])


def main(argv=None) -> int:
    p = build_parser()
    a = p.parse_args(argv)
    props = bo.read_properties(a.server_dir)
    port = a.port or int(props.get("rcon.port", "25575"))
    password = a.password or props.get("rcon.password", "")
    mc_port = a.mc_port or int(props.get("server-port", "25565"))
    if not password:
        p.error("no RCON password (server.properties rcon.password or --password)")
    if not 2 <= a.min_bots <= a.max_bots <= 64:
        p.error("need 2 <= --min-bots <= --max-bots <= 64")
    if not 0 < a.min_episode_s <= a.max_episode_s:
        p.error("need 0 < --min-episode-s <= --max-episode-s")
    if a.warmup < 0 or a.max_empty < 1:
        p.error("need --warmup >= 0 and --max-empty >= 1")
    if not (0 < a.elite <= 1 and 0 < a.cem_alpha <= 1 and 1 <= a.shrink_k <= a.shrink_n and a.stall_gens >= 1):
        p.error("need 0 < --elite <= 1, 0 < --cem-alpha <= 1, 1 <= --shrink-k <= --shrink-n, --stall-gens >= 1")
    if a.surprise_n > 0 and not 1 <= a.surprise_k <= a.surprise_n:
        p.error("need 1 <= --surprise-k <= --surprise-n")
    if a.finding_n > 0 and not 1 <= a.finding_k <= a.finding_n:
        p.error("need 1 <= --finding-k <= --finding-n")
    if not 0 <= a.novelty <= a.novelty_max <= 1:
        p.error("need 0 <= --novelty <= --novelty-max <= 1")
    try:
        rungs = parse_rungs(a.rungs)
    except ValueError as e:
        p.error(f"--rungs: {e}")
    top_bots = max(b for b, _ in rungs)
    max_players = int(props.get("max-players", "0") or 0)
    if max_players and max(top_bots, a.max_bots) > max_players:
        p.error(f"--rungs/--max-bots need {max(top_bots, a.max_bots)} bots but server.properties "
                f"max-players={max_players}; lower them or raise max-players")
    if a.eta < 1:
        p.error("need --eta >= 1")
    if a.reps < 1:
        p.error("need --reps >= 1")
    if a.mode == "faults" and not a.coverage:
        p.error("--mode faults needs --coverage (promising episodes come from coverage probes)")
    if a.promote_max < 0:
        p.error("need --promote-max >= 0")
    if a.mode == "faults" and a.reps > 1:
        log(f"--mode faults: --reps {a.reps} applies only to leak re-runs of promising episodes; "
            "the fault episodes themselves run once")
    if not 0 <= a.console_p <= 1:
        p.error("need 0 <= --console-p <= 1")
    if a.replay and a.ep is None:
        p.error("--replay needs --ep")
    if a.no_restart:
        a.isolate = False
    seeds = [int(s) for s in a.seeds.split(",") if s.strip()]
    replay_rec = load_trace(a.replay, a.ep, a.replay_seed) if a.replay else None
    targets = [t.strip().lower() for t in a.targets.split(",") if t.strip()]
    if replay_rec is not None:
        targets = [replay_rec.get("target") or targets[0]]
    targets = order_targets(targets)

    stamp = f"{dt.datetime.now():%Y%m%d-%H%M%S}"
    run_dir = os.path.join(a.out_dir, "runs", f"fuzz-{stamp}")
    os.makedirs(run_dir, exist_ok=True)
    bo.LOG_FH = open(os.path.join(run_dir, "fuzz.log"), "a", encoding="utf-8")
    drv = FuzzDriver(a, a.host, port, password)
    perms = [x.strip() for x in a.grant.split(",") if x.strip()]
    log(f"plan: stamp={stamp} targets={targets} seeds={seeds} episodes={a.episodes} bots={a.min_bots}-"
        f"{a.max_bots} {a.min_episode_s:g}-{a.max_episode_s:g}s novelty={a.novelty} grant={perms} "
        f"restart={not a.no_restart} run_dir={run_dir}" + (f" replay={a.replay}#{a.ep}" if a.replay else ""))
    if not drv.wait_up(60):
        log(f"cannot reach RCON at {a.host}:{port}")
        return 2
    if "running=true" in (drv.try_cmd("rtpstress status") or ""):
        log("a StressTestRTP run is in progress; stop it first")
        return 2
    if foreign_online(online_names(drv)) and not a.allow_online:
        log(f"non-fuzz players online ({foreign_online(online_names(drv))[:5]}); stop the passive swarm "
            "or pass --allow-online")
        return 2

    grant = PermissionGrant(drv, perms, a.lp_group, os.path.join(run_dir, "grant-revert.txt"),
                            read_nodes=lp_export_reader(drv, a.server_dir))
    swarm = Swarm(swarm_argv(a, mc_port), os.path.join(run_dir, "swarm.log"))
    atexit.register(grant.revert)
    atexit.register(swarm.close)

    def on_term(signum, _frame):
        raise KeyboardInterrupt(f"signal {signum}")

    prev_term = signal.signal(signal.SIGTERM, on_term)
    try:
        swarm.start()
        log(f"swarm: control port {swarm.port} (pid {swarm.proc.pid})")
        grant.apply()
        try:
            with open(os.path.join(run_dir, "manifest.csv"), "w", newline="", encoding="utf-8") as mf:
                w = csv.writer(mf)
                w.writerow(["target", "status", "label", "time", "result", "detail"])
                for target in targets:
                    run_target(a, drv, swarm, target, seeds, run_dir, stamp, w, replay_rec)
                    mf.flush()
        except NoSpawnError as e:
            log(f"aborting fuzz run: {e}")
            return 3
    finally:
        signal.signal(signal.SIGTERM, prev_term)
        drv.stop_ramp()
        swarm.close()
        grant.revert(wait_s=120)
        if drv.jars:
            log("restoring all plugin jars (takes effect on next start)")
            drv.set_jars(None)
    log(f"done; traces under {run_dir}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        bo.log("interrupted")
        sys.exit(130)
