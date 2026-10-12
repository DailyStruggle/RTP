#!/usr/bin/env python3
"""Black-box memory-leak check driver (stdlib only).

Detects growth without knowing anything about the plugin under test, the same
way for every target (LeafRTP included), and subtracts a control that does the
same work without any RTP plugin:

  none    no RTP plugin jar, bots idle (platform baseline)
  direct  no RTP plugin jar, the harness teleports bots at the same rate
          (`/rtpstress tp`, needs the commented `direct` entry enabled in
          StressTestRTP's target-commands). Subtracts world, chunk and landing
          growth that any RTP plugin causes. Preferred control when present.

Every target gets one restart with only its jar enabled. Two modes:

  soak    (default) c00 checkpoint (+ heap dump), then fixed-rate segments
          (`--rate` TP/s for `--segment-s`) for `--soak-hours`, a full churn
          every `--churn-every` segments and a checkpoint every
          `--checkpoint-every` segments, then a final checkpoint (+ dump).
  cycles  baseline, then N x (exercise ramp, churn, rejoin, settle, checkpoint).

Each checkpoint is a full GC plus class histogram, native memory (NMT when the
JVM runs with -XX:NativeMemoryTracking=summary), RSS, descriptors, threads by
name group, per-plugin tasks / listeners / live objects and workload counters.
Rows land in runs/leak.csv and runs/leak-metrics.csv on the server.

Analysis fits a least-squares slope per metric against elapsed hours (and per
1000 teleports), subtracts the control's slope for the same metric, and flags
metrics whose excess slope is beyond 3 standard errors and still growing. It
also diffs the first and last class histograms per target, net of the
control's per-hour growth. Owner attribution: run scripts/heap_owner.py on the
two .hprof files.

Example:
  python scripts/bench_leakcheck.py --server-dir /mnt/threadripper-dir/26.2 \\
      --targets direct,betterrtp --soak-hours 4 --rate 5 --dump
  python scripts/bench_leakcheck.py --server-dir ... --analyze-only 20261010-150000
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench_overnight as bo  # noqa: E402  (shared RCON, restart and jar isolation)

CONTROL = "none"
DIRECT = "direct"
CONTROLS = (DIRECT, CONTROL)
DEFAULT_TARGETS = "direct,betterrtp"
# Counters and pause timings grow by construction; they are x-axes, not leak evidence.
NOT_LEAK_METRICS = re.compile(r"(_total$|^uptime_s$|^heap_dump_|^histogram_ms$|^nmt_enabled$|"
                              r"^plugin_tasks_measured$|^epoch_ms$|^online$|^distinct_uuids$|_peak)")
HISTO_LINE = re.compile(r"^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)")
# G1 heap padding (filler arrays/objects that plug unused region tails); not application state.
GC_FILLER = re.compile(r"jdk\.internal\.vm\.Filler(Element|Object)")


def wait_leakcheck(drv: bo.Driver, before: int, limit_s: float) -> str | None:
    """Poll `leakcheck status` until the completed counter passes `before`."""
    end = time.time() + limit_s
    while time.time() < end:
        time.sleep(5)
        s = drv.try_cmd("rtpstress leakcheck status")
        if s is None:
            continue
        m = re.search(r"completed=(\d+)", s)
        if m and int(m.group(1)) > before and "busy=false" in s:
            return s.split("|", 1)[-1].strip()
    return None


def completed(drv: bo.Driver) -> int:
    s = drv.try_cmd("rtpstress leakcheck status") or ""
    m = re.search(r"completed=(\d+)", s)
    return int(m.group(1)) if m else -1


def checkpoint(drv: bo.Driver, label: str, limit_s: float, dump: bool = False) -> str:
    before = completed(drv)
    if before < 0:
        return "leakcheck unavailable (StressTestRTP without LeakProbe?)"
    r = drv.try_cmd(f"rtpstress leakcheck {label}" + (" dump" if dump else "")) or ""
    if "started" not in r:
        return f"not started: {r.strip()}"
    return wait_leakcheck(drv, before, limit_s) or "timed out waiting for leakcheck"


def churn_and_rejoin(a, drv: bo.Driver) -> int:
    online_before = drv.online()
    r = drv.try_cmd(f"rtpstress churn all {a.churn_prefix}".strip()) or ""
    bo.log(f"churn: {r.strip()}")
    # Wait for the departures to register before waiting for the rejoin.
    end = time.time() + 60
    while time.time() < end and drv.online() >= online_before > 0:
        time.sleep(2)
    n = drv.wait_players()
    time.sleep(a.settle_s)
    return n


def prepare_target(a, drv: bo.Driver, target: str, w) -> bool:
    """Restart with only `target`'s jar (controls: none) and verify the plugin set."""
    restarted = False if a.no_restart else drv.restart(target)
    ok, loaded = drv.jar_set_ok(target)
    if target in CONTROLS and loaded:
        ok = False  # a control with an RTP plugin loaded measures that plugin
    if not a.no_restart and not (restarted and ok):
        bo.log(f"{target}: restart/jar set not ok (loaded=[{loaded}]); skipping")
        w.writerow([target, "skip", "", dt.datetime.now().isoformat(timespec="seconds"), "", loaded])
        return False
    if target == DIRECT:
        listed = drv.targets_on_server() or []
        if DIRECT not in listed:
            bo.log(f"{target}: no '{DIRECT}' entry in StressTestRTP target-commands (have {listed}); "
                   "uncomment it in plugins/StressTestRTP/config.yml. Skipping.")
            w.writerow([target, "skip", "", dt.datetime.now().isoformat(timespec="seconds"),
                        "direct target not configured", loaded])
            return False
    return True


def record(drv, a, w, target: str, idx: int, stamp: str, dump: bool = False) -> None:
    label = f"{stamp}-{target}-c{idx:02d}"
    res = checkpoint(drv, label, a.leakcheck_wait_s, dump)
    bo.log(f"{target}: checkpoint c{idx:02d} ({drv.online()} online){' +dump' if dump else ''} {res}")
    w.writerow([target, idx, label, dt.datetime.now().isoformat(timespec="seconds"), res, ""])


def run_target_cycles(a, drv: bo.Driver, target: str, stamp: str, rates: str, secs: int, w) -> None:
    if not prepare_target(a, drv, target, w):
        return
    drv.wait_players()
    time.sleep(a.settle_s)
    record(drv, a, w, target, 0, stamp, a.dump)
    for i in range(1, a.cycles + 1):
        if target != CONTROL:
            outcome, last = drv.run_ramp(target, rates, secs)
            bo.log(f"{target}: cycle {i} exercise {outcome} | {last}")
        churn_and_rejoin(a, drv)
        record(drv, a, w, target, i, stamp, a.dump and i == a.cycles)


def run_target_soak(a, drv: bo.Driver, target: str, stamp: str, w) -> None:
    if not prepare_target(a, drv, target, w):
        return
    drv.wait_players()
    time.sleep(a.settle_s)
    record(drv, a, w, target, 0, stamp, a.dump)
    end = time.time() + a.soak_hours * 3600
    seg, idx = 0, 0
    while time.time() < end:
        seg += 1
        if target == CONTROL:
            time.sleep(a.segment_s)
        else:
            outcome, last = drv.run_ramp(target, f"{a.rate:g}", a.segment_s)
            bo.log(f"{target}: segment {seg} {outcome} | {last}")
            if outcome.startswith("crashed"):
                bo.log(f"{target}: server crashed mid-soak; stopping this target (heap state is gone)")
                return
        if a.churn_every > 0 and seg % a.churn_every == 0:
            churn_and_rejoin(a, drv)
        if seg % a.checkpoint_every == 0 and time.time() < end:
            idx += 1
            record(drv, a, w, target, idx, stamp)
    record(drv, a, w, target, idx + 1, stamp, a.dump)


# --- analysis -----------------------------------------------------------------

def fit(xs: list[float], ys: list[float]) -> tuple[float, float] | None:
    """Least-squares slope and its standard error; None below 3 points."""
    n = len(xs)
    if n < 3:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    resid = sum((y - (my + b * (x - mx))) ** 2 for x, y in zip(xs, ys))
    se = math.sqrt(resid / (n - 2) / sxx) if n > 2 else float("inf")
    return b, se


def slope(xs: list[float], ys: list[float]) -> float | None:
    r = fit(xs, ys)
    return None if r is None else r[0]


def load_checkpoints(runs_dir: str, stamp: str) -> dict[str, list[dict]]:
    """target -> checkpoints (oldest first), each a dict of metric -> float plus
    `_epoch` and `_histo`. Merges leak.csv columns with leak-metrics.csv rows."""
    by_label: dict[str, dict] = {}
    pat = re.compile(re.escape(stamp) + r"-(.+)-c(\d+)")
    with open(os.path.join(runs_dir, "leak.csv"), encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            m = pat.fullmatch(r.get("label", ""))
            if not m:
                continue
            d: dict = {"_target": m.group(1), "_epoch": int(r["epoch_ms"]), "_histo": r.get("histogram_file", "")}
            for k, v in r.items():
                try:
                    if k not in ("label", "histogram_file"):
                        d[k] = float(v)
                except (TypeError, ValueError):
                    pass
            by_label[r["label"]] = d
    mpath = os.path.join(runs_dir, "leak-metrics.csv")
    if os.path.exists(mpath):
        with open(mpath, encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                d = by_label.get(r.get("label", ""))
                if d is not None:
                    try:
                        d[r["metric"]] = float(r["value"])
                    except (TypeError, ValueError):
                        pass
    out: dict[str, list[dict]] = {}
    for d in by_label.values():
        # Older harness builds did not record it; derive so controls compare.
        if "rss_minus_nmt_mb" not in d and "rss_mb" in d and "nmt.total.committed_mb" in d:
            d["rss_minus_nmt_mb"] = d["rss_mb"] - d["nmt.total.committed_mb"]
        out.setdefault(d["_target"], []).append(d)
    for rs in out.values():
        rs.sort(key=lambda d: d["_epoch"])
    return out


def hours(rs: list[dict]) -> list[float]:
    return [(d["_epoch"] - rs[0]["_epoch"]) / 3.6e6 for d in rs]


def metric_slopes(rs: list[dict]) -> dict[str, tuple[float, float, float, float]]:
    """metric -> (slope/h, stderr, first, last) over checkpoints that report it."""
    out = {}
    names = set().union(*(d.keys() for d in rs)) if rs else set()
    for name in names:
        if name.startswith("_") or NOT_LEAK_METRICS.search(name):
            continue
        pts = [(h, d[name]) for h, d in zip(hours(rs), rs) if isinstance(d.get(name), float) and d[name] >= 0]
        r = fit([p[0] for p in pts], [p[1] for p in pts])
        if r is not None:
            out[name] = (r[0], r[1], pts[0][1], pts[-1][1])
    return out


def flag_metrics(target_rs: list[dict], control_rs: list[dict] | None) -> list[tuple]:
    """Metrics growing faster than the control: excess slope beyond 3 combined
    standard errors, a net rise over the run, and a change worth reporting
    (>= 2 % of the start value, or any rise from zero)."""
    t = metric_slopes(target_rs)
    c = metric_slopes(control_rs) if control_rs else {}
    flagged = []
    for name, (b, se, first, last) in t.items():
        cb, cse = (c[name][0], c[name][1]) if name in c else (0.0, 0.0)
        excess = b - cb
        se_all = math.sqrt(se * se + cse * cse)
        z = excess / se_all if se_all > 0 else (float("inf") if excess > 0 else 0.0)
        material = (last - first) >= 0.02 * abs(first) if first else last > first
        if excess > 0 and z >= 3 and last > first and material:
            flagged.append((name, b, cb, excess, z, first, last, name in c))
    flagged.sort(key=lambda f: -f[4])
    return flagged


def read_histo(path: str) -> dict[str, tuple[int, int]]:
    out = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = HISTO_LINE.match(line)
                if m and not GC_FILLER.search(m.group(3)):
                    out[m.group(3)] = (int(m.group(1)), int(m.group(2)))
    except OSError:
        pass
    return out


def histo_rates(runs_dir: str, rs: list[dict]) -> tuple[dict[str, tuple[float, float]], float] | None:
    """Per-class (instances/h, bytes/h) between the first and last readable histogram."""
    have = [d for d in rs if d["_histo"] and os.path.exists(os.path.join(runs_dir, d["_histo"]))]
    if len(have) < 2:
        return None
    span = (have[-1]["_epoch"] - have[0]["_epoch"]) / 3.6e6
    a = read_histo(os.path.join(runs_dir, have[0]["_histo"]))
    b = read_histo(os.path.join(runs_dir, have[-1]["_histo"]))
    if not a or not b or span <= 0:
        return None
    out = {}
    for k in set(a) | set(b):
        ia, ba = a.get(k, (0, 0))
        ib, bb = b.get(k, (0, 0))
        out[k] = ((ib - ia) / span, (bb - ba) / span)
    return out, span


def pick_control(rows: dict[str, list[dict]]) -> str | None:
    for c in CONTROLS:
        if len(rows.get(c, [])) >= 3:
            return c
    return None


def analyze(runs_dir: str, stamp: str, out_path: str | None, top: int = 20, start: int = 0) -> str:
    rows = load_checkpoints(runs_dir, stamp)
    if start > 0:
        # A c00 taken on a server that already ran another target is not a cold baseline.
        rows = {t: rs[start:] for t, rs in rows.items() if len(rs) > start}
    ctl = pick_control(rows)
    lines = [f"leak check {stamp} (source {runs_dir}; control = {ctl or 'NONE - slopes are absolute'}"
             + (f"; from checkpoint index {start}" if start else "") + ")", ""]
    lines.append("target | checkpoints | hours | teleports | departures | retained players first->last | "
                 "heap after GC MB first->last (MB/h) | RSS MB first->last | NMT | flagged metrics")
    for target in sorted(rows, key=lambda t: (t not in CONTROLS, t)):
        rs = rows[target]
        g = lambda k, i: rs[i].get(k, -1.0)  # noqa: E731
        hb = slope(hours(rs), [d.get("heap_used_after_gc_mb", 0.0) for d in rs])
        flagged = flag_metrics(rs, rows.get(ctl) if ctl and target != ctl else None)
        lines.append(" | ".join([
            target, str(len(rs)), f"{hours(rs)[-1]:.2f}",
            f"{g('teleports_total', -1) - g('teleports_total', 0):.0f}",
            f"{g('quits_total', -1) - g('quits_total', 0):.0f}",
            f"{g('retained_player_objects', 0):.0f}->{g('retained_player_objects', -1):.0f}",
            f"{g('heap_used_after_gc_mb', 0):.0f}->{g('heap_used_after_gc_mb', -1):.0f} "
            f"({'n/a' if hb is None else f'{hb:+.1f}'})",
            f"{g('rss_mb', 0):.0f}->{g('rss_mb', -1):.0f}",
            "on" if g("nmt_enabled", -1) == 1 else "NOT MEASURED",
            str(len(flagged))]))
    for target in sorted(rows, key=lambda t: (t not in CONTROLS, t)):
        rs = rows[target]
        is_ctl = target == ctl
        flagged = flag_metrics(rs, None if is_ctl else rows.get(ctl) if ctl else None)
        lines += ["", f"== {target}{' (control)' if is_ctl else ''}: metrics growing "
                      f"{'(absolute)' if is_ctl or not ctl else 'faster than ' + ctl} =="]
        if not flagged:
            lines.append("  none")
        for name, b, cb, excess, z, first, last, in_ctl in flagged[:40]:
            lines.append(f"  {name:<58} {first:>12.2f} -> {last:>12.2f}  slope {b:+.3f}/h"
                         + ("" if is_ctl else f"  control {cb:+.3f}/h{'' if in_ctl else ' (absent)'}")
                         + f"  excess {excess:+.3f}/h  z={z:.1f}")
        tel = rs[-1].get("teleports_total", 0) - rs[0].get("teleports_total", 0)
        if tel > 0 and hours(rs)[-1] > 0:
            per_h = tel / hours(rs)[-1]
            heap = [f for f in flagged if f[0] == "heap_used_after_gc_mb"]
            if heap:
                lines.append(f"  heap excess per 1000 teleports: {heap[0][3] / per_h * 1000:+.2f} MB")
        hr = histo_rates(runs_dir, rs)
        if hr is None:
            continue
        rates, span = hr
        crates = {}
        if ctl and not is_ctl:
            c = histo_rates(runs_dir, rows[ctl])
            crates = c[0] if c else {}
        ranked = sorted(rates.items(), key=lambda kv: -(kv[1][1] - crates.get(kv[0], (0, 0))[1]))
        lines.append(f"  top classes by live-byte growth over {span:.2f} h"
                     + ("" if is_ctl or not crates else f", net of {ctl}") + " (first vs last histogram):")
        for k, (ir, br) in ranked[:top]:
            ci, cbr = crates.get(k, (0.0, 0.0))
            net = br - cbr
            if net <= 0:
                break
            lines.append(f"    {net / 1e6:+9.3f} MB/h  {ir - ci:+11.0f} inst/h  {k}")
    lines += ["",
              "Slopes are least-squares against elapsed hours; flagged = excess over the control beyond "
              "3 standard errors, rising over the run, and >= 2 % of the start value. Counters (*_total) "
              "are the x-axes, not evidence. Owner attribution: python scripts/heap_owner.py on the "
              "c00 and last .hprof files in the runs directory."]
    text = "\n".join(lines) + "\n"
    if out_path:
        with open(out_path, "w", encoding="utf-8") as fh:
            fh.write(text)
    return text


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--server-dir", required=True, help="server root (RCON settings, plugins/, runs/leak.csv)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int)
    p.add_argument("--password", default=os.environ.get("RCON_PASSWORD"))
    p.add_argument("--targets", default=DEFAULT_TARGETS,
                   help=f"comma-separated labels; '{CONTROL}' = no RTP plugin, '{DIRECT}' = harness teleports")
    p.add_argument("--mode", choices=("soak", "cycles"), default="soak")
    p.add_argument("--soak-hours", type=float, default=4.0)
    p.add_argument("--rate", type=float, default=5.0, help="soak offered rate, TP/s")
    p.add_argument("--segment-s", type=int, default=600,
                   help="one flat ramp stage per segment; keep <= spark.timeout-seconds")
    p.add_argument("--churn-every", type=int, default=1, help="segments between full churns (0 = never)")
    p.add_argument("--checkpoint-every", type=int, default=2, help="segments between checkpoints")
    p.add_argument("--dump", action="store_true", help="heap dump at the first and last checkpoint")
    p.add_argument("--cycles", type=int, default=8, help="cycles mode: churn cycles after the baseline")
    p.add_argument("--exercise", default="5@60", help="cycles mode: ramp 'rates@stageSeconds' before each churn")
    p.add_argument("--churn-prefix", default="bench_", help="only kick players whose name starts with this")
    p.add_argument("--no-restart", action="store_true")
    p.add_argument("--no-isolate", dest="isolate", action="store_false")
    p.add_argument("--jars", default=bo.DEFAULT_JARS)
    p.add_argument("--names", default=bo.DEFAULT_NAMES)
    p.add_argument("--restart-retries", type=int, default=2)
    p.add_argument("--min-players", type=int, default=32)
    p.add_argument("--player-wait-s", type=int, default=600)
    p.add_argument("--settle-s", type=int, default=60)
    p.add_argument("--poll-s", type=int, default=20)
    p.add_argument("--shutdown-wait-s", type=int, default=1200)
    p.add_argument("--startup-wait-s", type=int, default=900)
    p.add_argument("--leakcheck-wait-s", type=int, default=900, help="full GC + heap dump on a large heap")
    p.add_argument("--ramp-idle-s", type=int, default=30)
    p.add_argument("--ramp-warmup-s", type=int, default=30)
    p.add_argument("--ramp-gap-s", type=int, default=5)
    p.add_argument("--attempt-timeout-s", type=int, default=5)
    p.add_argument("--margin-s", type=int, default=300)
    p.add_argument("--output-subdir", default="runs", help="StressTestRTP output-subdir")
    p.add_argument("--out-dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp"))
    p.add_argument("--top", type=int, default=20, help="classes listed per target in the histogram diff")
    p.add_argument("--from-checkpoint", type=int, default=0,
                   help="analysis: drop each target's first N checkpoints (e.g. a non-cold c00)")
    p.add_argument("--analyze-only", metavar="STAMP", help="skip the run; analyze the server's runs/ for STAMP")
    a = p.parse_args()

    runs_dir = os.path.join(a.server_dir, "plugins", "StressTestRTP", a.output_subdir)
    os.makedirs(a.out_dir, exist_ok=True)
    if a.analyze_only:
        print(analyze(runs_dir, a.analyze_only, os.path.join(a.out_dir, f"leakcheck-{a.analyze_only}.txt"),
                      a.top, a.from_checkpoint))
        return 0

    props = bo.read_properties(a.server_dir)
    port = a.port or int(props.get("rcon.port", "25575"))
    password = a.password or props.get("rcon.password", "")
    if not password:
        p.error("no RCON password (server.properties rcon.password or --password)")
    if a.no_restart:
        a.isolate = False
    if a.checkpoint_every < 1:
        p.error("--checkpoint-every must be >= 1")
    rates, secs = bo.parse_round(a.exercise)
    targets = [t.strip() for t in a.targets.split(",") if t.strip()]
    if a.no_restart:
        for c in CONTROLS:
            if c in targets:
                bo.log(f"'{c}' needs restarts with jar isolation; dropping it")
                targets.remove(c)

    stamp = f"{dt.datetime.now():%Y%m%d-%H%M%S}"
    bo.LOG_FH = open(os.path.join(a.out_dir, f"leakcheck-{stamp}.log"), "a", encoding="utf-8")
    drv = bo.Driver(a, a.host, port, password)
    plan = (f"soak {a.soak_hours}h at {a.rate:g} TP/s, segments {a.segment_s}s, churn every {a.churn_every}, "
            f"checkpoint every {a.checkpoint_every}" if a.mode == "soak"
            else f"cycles={a.cycles} exercise={rates}@{secs}s")
    bo.log(f"plan: stamp={stamp} targets={targets} {plan} dump={a.dump} "
           f"churn-prefix='{a.churn_prefix}' restart={not a.no_restart}")
    if not drv.wait_up(60):
        bo.log(f"cannot reach RCON at {a.host}:{port}")
        return 2
    if "running=true" in (drv.try_cmd("rtpstress status") or ""):
        bo.log("a StressTestRTP run is in progress; stop it first")
        return 2
    manifest = os.path.join(a.out_dir, f"leakcheck-{stamp}.csv")
    try:
        with open(manifest, "w", newline="", encoding="utf-8") as mf:
            w = csv.writer(mf)
            w.writerow(["target", "cycle", "label", "time", "result", "loaded_rtp_plugins"])
            for target in targets:
                if a.mode == "soak":
                    run_target_soak(a, drv, target, stamp, w)
                else:
                    run_target_cycles(a, drv, target, stamp, rates, secs, w)
                mf.flush()
    finally:
        drv.stop_ramp()
        if drv.jars:
            bo.log("restoring all plugin jars (takes effect on next start)")
            drv.set_jars(None)
    if os.path.exists(os.path.join(runs_dir, "leak.csv")):
        text = analyze(runs_dir, stamp, os.path.join(a.out_dir, f"leakcheck-{stamp}.txt"), a.top,
                       a.from_checkpoint)
        bo.log("\n" + text)
    else:
        bo.log(f"{runs_dir}/leak.csv not readable from here; rerun with --analyze-only {stamp}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        bo.log("interrupted")
        sys.exit(130)
