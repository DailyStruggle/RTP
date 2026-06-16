#!/usr/bin/env python3
"""
plot_stress.py — turn StressTestRTP CSV runs into comparison charts.

Reads one or more per-attempt CSVs produced by `MetricsRecorder` (header:
attempt_id,player,world,target_label,dispatch_epoch_ms,teleport_epoch_ms,
latency_ms,success,fail_reason,from_x,from_z,to_x,to_z,distance,
tps_at_dispatch,mspt_at_dispatch,heap_used_mb_at_dispatch)

and emits, into an output directory:

  - latency_bars.png     (cold / p50 / p95 / p99 grouped bar per target_label)
  - latency_cdf.png      (CDF of successful-attempt latency per target_label)
  - tps_over_time.png    (TPS sampled at each dispatch, per target_label)
  - mspt_over_time.png   (MSPT sampled at each dispatch, per target_label)
  - heap_over_time.png   (heap MB sampled at each dispatch, per target_label)
  - heap_pressure_over_time.png (heap MB vs elapsed time, from <stamp>-heap.csv)
  - heap_per_attempt.png (heap MB vs cumulative /rtp attempts; slope = MB per /rtp)
  - success_rate.png     (success vs total attempts per target_label)
  - throughput_over_time.png (successful teleports per time bucket, per target_label)
  - throughput_bars.png  (mean successful teleports/sec per target_label)
  - cpu_per_tp_total.png (process CPU-ms per teleport, per target_label)
  - cpu_per_tp_main.png  (main-thread CPU-ms per teleport, per target_label)
  - summary.md           (front-page-aligned comparison table)

If a sibling `<stamp>-phases.csv` is present alongside each input CSV, its
phase-aggregate CPU columns (process_cpu_ms, main_thread_cpu_ms) are read and
folded into the summary, attributed by phase_label which matches target_label.
If no phases CSV is found, the CPU charts/columns are simply omitted.

Usage:
  python plot_stress.py path/to/run.csv [path/to/run2.csv ...] -o out_dir
  python plot_stress.py plugins/StressTestRTP/runs/ -o out_dir   # directory of CSVs

Requires: Python 3.9+, matplotlib (pip install matplotlib).
Pure stdlib otherwise — no pandas.
"""
from __future__ import annotations

import argparse
import csv
import math
import os
import sys
from collections import OrderedDict, defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except ImportError:
    sys.stderr.write("ERROR: matplotlib is required. Install with: pip install matplotlib\n")
    sys.exit(2)


# ---------------- IO ----------------

def expand_inputs(inputs: List[str]) -> List[Path]:
    out: List[Path] = []
    for s in inputs:
        p = Path(s)
        if p.is_dir():
            # Only per-attempt CSVs; the sibling `-phases.csv` / `-heap.csv`
            # series are loaded explicitly by their own loaders.
            out.extend(sorted(c for c in p.glob("*.csv")
                              if not (c.stem.endswith("-phases") or c.stem.endswith("-heap"))))
        elif p.is_file():
            out.append(p)
        else:
            sys.stderr.write(f"WARN: skipping missing path: {s}\n")
    return out


def load_rows(paths: Iterable[Path]) -> List[dict]:
    rows: List[dict] = []
    for p in paths:
        with p.open("r", newline="", encoding="utf-8") as fh:
            reader = csv.DictReader(fh)
            for r in reader:
                r["_source"] = p.name
                rows.append(r)
    return rows


def load_phase_rows(paths: Iterable[Path]) -> List[dict]:
    """Load sibling `<stamp>-phases.csv` files, if present, for each input CSV.

    Each phases-CSV row has columns: phase_label, start_epoch_ms, end_epoch_ms,
    wall_ms, attempts, successes, process_cpu_ms, main_thread_cpu_ms,
    cpu_ms_per_attempt_total, cpu_ms_per_attempt_main.
    """
    out: List[dict] = []
    seen: set = set()
    for p in paths:
        # `<stamp>.csv` → `<stamp>-phases.csv`
        sibling = p.with_name(p.stem + "-phases" + p.suffix)
        if sibling in seen or not sibling.is_file():
            continue
        seen.add(sibling)
        with sibling.open("r", newline="", encoding="utf-8") as fh:
            reader = csv.DictReader(fh)
            for r in reader:
                r["_source"] = sibling.name
                out.append(r)
    return out


def load_heap_rows(paths: Iterable[Path]) -> "OrderedDict[str, List[dict]]":
    """Load sibling `<stamp>-heap.csv` heap-pressure-over-time series.

    Returns an OrderedDict mapping the heap-CSV file name to its rows. Each
    row has columns: epoch_ms, elapsed_ms, heap_used_mb, heap_committed_mb,
    heap_max_mb, tps, mspt, attempts_completed, attempts_since_start,
    heap_delta_mb, mb_per_attempt. Missing siblings are skipped silently.
    """
    out: "OrderedDict[str, List[dict]]" = OrderedDict()
    seen: set = set()
    for p in paths:
        # `<stamp>.csv` -> `<stamp>-heap.csv`. Skip heap CSVs given directly.
        if p.stem.endswith("-heap") or p.stem.endswith("-phases"):
            continue
        sibling = p.with_name(p.stem + "-heap" + p.suffix)
        if sibling in seen or not sibling.is_file():
            continue
        seen.add(sibling)
        with sibling.open("r", newline="", encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        if rows:
            out[sibling.name] = rows
    return out


def plot_heap_pressure_over_time(heap_series, out_path: Path) -> None:
    """Heap-used (MB) vs elapsed run time, one line per run heap CSV."""
    if not heap_series:
        return
    fig, ax = plt.subplots(figsize=(9, 5))
    any_data = False
    for name, rows in heap_series.items():
        xs, ys = [], []
        for r in rows:
            t = to_float(r.get("elapsed_ms", ""))
            v = to_float(r.get("heap_used_mb", ""))
            if not math.isnan(t) and not math.isnan(v):
                xs.append(t / 1000.0)
                ys.append(v)
        if xs:
            ax.plot(xs, ys, label=name, linewidth=1, marker=".", markersize=3, alpha=0.85)
            any_data = True
    ax.set_xlabel("time since run start (s)")
    ax.set_ylabel("heap used (MB)")
    ax.set_title("Heap pressure over time")
    ax.grid(True, linestyle=":", alpha=0.5)
    if any_data:
        ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_heap_per_attempt(heap_series, out_path: Path) -> None:
    """Heap used (MB) vs cumulative /rtp attempts. The slope is RAM per /rtp;
    a least-squares fit is annotated per run as MB/attempt."""
    if not heap_series:
        return
    fig, ax = plt.subplots(figsize=(9, 5))
    any_data = False
    for name, rows in heap_series.items():
        xs, ys = [], []
        for r in rows:
            a = to_float(r.get("attempts_since_start", ""))
            v = to_float(r.get("heap_used_mb", ""))
            if not math.isnan(a) and not math.isnan(v):
                xs.append(a)
                ys.append(v)
        if not xs:
            continue
        # Least-squares slope (MB per attempt) when the attempt axis varies.
        slope = float("nan")
        n = len(xs)
        if n >= 2 and max(xs) > min(xs):
            mx = sum(xs) / n
            my = sum(ys) / n
            denom = sum((x - mx) ** 2 for x in xs)
            if denom > 0:
                slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / denom
        lbl = name if math.isnan(slope) else f"{name} (~{slope:.3f} MB/rtp)"
        ax.plot(xs, ys, label=lbl, linewidth=1, marker=".", markersize=3, alpha=0.85)
        any_data = True
    ax.set_xlabel("cumulative /rtp attempts since run start")
    ax.set_ylabel("heap used (MB)")
    ax.set_title("Heap usage vs /rtp attempts (slope = RAM per /rtp)")
    ax.grid(True, linestyle=":", alpha=0.5)
    if any_data:
        ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def aggregate_phase_cpu(phase_rows: List[dict]) -> "OrderedDict[str, dict]":
    """Sum CPU-ms and attempts per phase_label across all phase rows.

    Returns an OrderedDict mapping label → {process_cpu_ms, main_cpu_ms,
    attempts, cpu_per_tp_total, cpu_per_tp_main} where the per-TP figures are
    attempt-weighted means computed from the sums (not the per-row averages).
    """
    agg: "OrderedDict[str, dict]" = OrderedDict()
    for r in phase_rows:
        label = r.get("phase_label") or ""
        if not label:
            continue
        slot = agg.setdefault(label, {"process_cpu_ms": 0, "main_cpu_ms": 0,
                                       "attempts": 0, "have_proc": False, "have_main": False})
        att = to_int(r.get("attempts", ""))
        if att > 0:
            slot["attempts"] += att
        proc = to_int(r.get("process_cpu_ms", ""))
        if proc >= 0:
            slot["process_cpu_ms"] += proc
            slot["have_proc"] = True
        main = to_int(r.get("main_thread_cpu_ms", ""))
        if main >= 0:
            slot["main_cpu_ms"] += main
            slot["have_main"] = True
    for label, slot in agg.items():
        att = slot["attempts"]
        slot["cpu_per_tp_total"] = (slot["process_cpu_ms"] / att) if (att > 0 and slot["have_proc"]) else float("nan")
        slot["cpu_per_tp_main"]  = (slot["main_cpu_ms"]    / att) if (att > 0 and slot["have_main"]) else float("nan")
    return agg


def to_float(s: str) -> float:
    if s is None or s == "":
        return math.nan
    try:
        return float(s)
    except ValueError:
        return math.nan


def to_int(s: str) -> int:
    try:
        return int(s)
    except (TypeError, ValueError):
        return -1


# ---------------- Stats ----------------

def percentile(samples: List[float], p: float) -> float:
    if not samples:
        return float("nan")
    arr = sorted(samples)
    idx = max(0, min(len(arr) - 1, round((p / 100.0) * (len(arr) - 1))))
    return arr[int(idx)]


def group_by_target(rows: List[dict]) -> "OrderedDict[str, List[dict]]":
    groups: "OrderedDict[str, List[dict]]" = OrderedDict()
    for r in rows:
        label = r.get("target_label") or "default"
        groups.setdefault(label, []).append(r)
    return groups


def successful_latencies(rows: List[dict]) -> List[float]:
    out = []
    for r in rows:
        if r.get("success", "false").lower() != "true":
            continue
        v = to_int(r.get("latency_ms", ""))
        if v >= 0:
            out.append(float(v))
    return out


# ---------------- Plots ----------------

def plot_latency_bars(groups, out_path: Path) -> None:
    labels = list(groups.keys())
    metrics = ["cold", "p50", "p95", "p99"]
    values = {m: [] for m in metrics}
    for label in labels:
        lats = successful_latencies(groups[label])
        values["cold"].append(lats[0] if lats else 0.0)
        values["p50"].append(percentile(lats, 50) if lats else 0.0)
        values["p95"].append(percentile(lats, 95) if lats else 0.0)
        values["p99"].append(percentile(lats, 99) if lats else 0.0)

    x = list(range(len(labels)))
    w = 0.2
    fig, ax = plt.subplots(figsize=(max(8, 1.4 * len(labels)), 5))
    for i, m in enumerate(metrics):
        ax.bar([xi + (i - 1.5) * w for xi in x], values[m], width=w, label=m)
    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=30, ha="right")
    ax.set_ylabel("latency (ms)")
    ax.set_title("Per-target /rtp latency: cold vs p50/p95/p99")
    ax.legend()
    ax.grid(axis="y", linestyle=":", alpha=0.5)
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_latency_cdf(groups, out_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(8, 5))
    for label, rows in groups.items():
        lats = sorted(successful_latencies(rows))
        if not lats:
            continue
        ys = [(i + 1) / len(lats) for i in range(len(lats))]
        ax.plot(lats, ys, label=f"{label} (n={len(lats)})")
    ax.set_xlabel("latency (ms)")
    ax.set_ylabel("cumulative fraction")
    ax.set_title("Latency CDF (successful attempts)")
    ax.set_xscale("log")
    ax.grid(True, which="both", linestyle=":", alpha=0.5)
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def _time_series(rows: List[dict], col: str) -> Tuple[List[float], List[float]]:
    xs, ys = [], []
    if not rows:
        return xs, ys
    t0 = min(to_int(r.get("dispatch_epoch_ms", "0")) for r in rows)
    for r in rows:
        t = to_int(r.get("dispatch_epoch_ms", "0"))
        v = to_float(r.get(col, ""))
        if t > 0 and not math.isnan(v):
            xs.append((t - t0) / 1000.0)
            ys.append(v)
    pairs = sorted(zip(xs, ys))
    return [p[0] for p in pairs], [p[1] for p in pairs]


def plot_metric_over_time(groups, col: str, ylabel: str, title: str, out_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(9, 5))
    any_data = False
    for label, rows in groups.items():
        xs, ys = _time_series(rows, col)
        if xs:
            ax.plot(xs, ys, label=label, linewidth=1, marker=".", markersize=3, alpha=0.8)
            any_data = True
    ax.set_xlabel("time since first dispatch in run (s)")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.grid(True, linestyle=":", alpha=0.5)
    if any_data:
        ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def _success_completion_times(rows: List[dict]) -> List[float]:
    """Seconds-since-run-start of each successful teleport completion."""
    if not rows:
        return []
    dispatch_times = [to_int(r.get("dispatch_epoch_ms", "0")) for r in rows]
    dispatch_times = [t for t in dispatch_times if t > 0]
    if not dispatch_times:
        return []
    t0 = min(dispatch_times)
    out: List[float] = []
    for r in rows:
        if r.get("success", "false").lower() != "true":
            continue
        t = to_int(r.get("teleport_epoch_ms", "0"))
        if t <= 0:
            t = to_int(r.get("dispatch_epoch_ms", "0"))
            lat = to_int(r.get("latency_ms", "0"))
            if t > 0 and lat >= 0:
                t = t + lat
        if t > 0:
            out.append((t - t0) / 1000.0)
    out.sort()
    return out


def _run_duration_seconds(rows: List[dict]) -> float:
    ts = []
    for r in rows:
        for col in ("teleport_epoch_ms", "dispatch_epoch_ms"):
            v = to_int(r.get(col, "0"))
            if v > 0:
                ts.append(v)
    if len(ts) < 2:
        return 0.0
    return (max(ts) - min(ts)) / 1000.0


def plot_throughput_over_time(groups, out_path: Path, bucket_seconds: float = 5.0) -> None:
    fig, ax = plt.subplots(figsize=(9, 5))
    any_data = False
    for label, rows in groups.items():
        times = _success_completion_times(rows)
        if not times:
            continue
        end = times[-1]
        if end <= 0:
            continue
        n_buckets = max(1, int(math.ceil(end / bucket_seconds)) + 1)
        counts = [0] * n_buckets
        for t in times:
            idx = min(n_buckets - 1, int(t // bucket_seconds))
            counts[idx] += 1
        rates = [c / bucket_seconds for c in counts]
        xs = [i * bucket_seconds for i in range(n_buckets)]
        ax.plot(xs, rates, label=label, linewidth=1.2, marker=".", markersize=3, alpha=0.85)
        any_data = True
    ax.set_xlabel("time since first dispatch in run (s)")
    ax.set_ylabel(f"successful teleports per second (avg over {bucket_seconds:g}s)")
    ax.set_title("Throughput over time")
    ax.grid(True, linestyle=":", alpha=0.5)
    if any_data:
        ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_throughput_bars(groups, out_path: Path) -> None:
    labels = list(groups.keys())
    rates = []
    for label in labels:
        rows = groups[label]
        s = sum(1 for r in rows if r.get("success", "false").lower() == "true")
        dur = _run_duration_seconds(rows)
        rates.append((s / dur) if dur > 0 else 0.0)
    x = list(range(len(labels)))
    fig, ax = plt.subplots(figsize=(max(8, 1.2 * len(labels)), 5))
    ax.bar(x, rates, color="#1f77b4")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=30, ha="right")
    ax.set_ylabel("successful teleports / second")
    ax.set_title("Mean throughput per target (successful /rtp per second over run window)")
    ax.grid(axis="y", linestyle=":", alpha=0.5)
    for xi, v in zip(x, rates):
        ax.text(xi, v, f"{v:.2f}", ha="center", va="bottom", fontsize=9)
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_cpu_per_tp(cpu_agg, key: str, ylabel: str, title: str, out_path: Path) -> None:
    if not cpu_agg:
        return
    labels = list(cpu_agg.keys())
    vals = [cpu_agg[l][key] for l in labels]
    if all((v is None or (isinstance(v, float) and math.isnan(v))) for v in vals):
        return
    x = list(range(len(labels)))
    plot_vals = [0.0 if (isinstance(v, float) and math.isnan(v)) else v for v in vals]
    fig, ax = plt.subplots(figsize=(max(8, 1.2 * len(labels)), 5))
    ax.bar(x, plot_vals, color="#9467bd")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=30, ha="right")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.grid(axis="y", linestyle=":", alpha=0.5)
    for xi, v in zip(x, vals):
        if isinstance(v, float) and math.isnan(v):
            continue
        ax.text(xi, v, f"{v:.1f}", ha="center", va="bottom", fontsize=9)
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_success_rate(groups, out_path: Path) -> None:
    labels = list(groups.keys())
    succ, fail = [], []
    for label in labels:
        rows = groups[label]
        s = sum(1 for r in rows if r.get("success", "false").lower() == "true")
        succ.append(s)
        fail.append(len(rows) - s)
    x = list(range(len(labels)))
    fig, ax = plt.subplots(figsize=(max(8, 1.2 * len(labels)), 5))
    ax.bar(x, succ, label="success", color="#2ca02c")
    ax.bar(x, fail, bottom=succ, label="fail/timeout", color="#d62728")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=30, ha="right")
    ax.set_ylabel("attempts")
    ax.set_title("Success vs failure per target")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


# ---------------- Destinations ----------------

def _successful_destinations(rows: List[dict]) -> List[Tuple[float, float]]:
    """Return (to_x, to_z) pairs for successful attempts only."""
    out: List[Tuple[float, float]] = []
    for r in rows:
        if r.get("success", "false").lower() != "true":
            continue
        x = to_float(r.get("to_x", ""))
        z = to_float(r.get("to_z", ""))
        if math.isnan(x) or math.isnan(z):
            continue
        out.append((x, z))
    return out




def plot_destination_scatter(groups, out_path: Path) -> None:
    """One sub-panel per target; scatter of (to_x, to_z) successful destinations.

    Visualizes the spatial distribution each plugin actually produced — radius,
    centre, distribution shape (uniform / annular / clustered) all become
    visible at a glance.
    """
    n = len(groups)
    if n == 0:
        return
    cols = min(4, n)
    rows_n = (n + cols - 1) // cols
    fig, axes = plt.subplots(rows_n, cols, figsize=(4 * cols, 4 * rows_n), squeeze=False)
    for i, (label, rows) in enumerate(groups.items()):
        ax = axes[i // cols][i % cols]
        coords = _successful_destinations(rows)
        if coords:
            xs = [p[0] for p in coords]
            zs = [p[1] for p in coords]
            ax.scatter(xs, zs, s=12, alpha=0.6)
            ax.set_title(f"{label} (n={len(coords)})")
        else:
            ax.set_title(f"{label} (no successful attempts)")
        ax.set_xlabel("to_x")
        ax.set_ylabel("to_z")
        ax.set_aspect("equal", adjustable="datalim")
        ax.grid(True, linestyle=":", linewidth=0.5)
    # Hide unused subplots.
    for j in range(n, rows_n * cols):
        axes[j // cols][j % cols].axis("off")
    fig.suptitle("Destination distribution per target (successful teleports)")
    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)




# ---------------- Summary ----------------

def write_summary_md(groups, out_path: Path, cpu_agg=None) -> None:
    have_cpu = bool(cpu_agg)
    lines = [
        "# StressTestRTP comparison",
        "",
        "Front-page-aligned columns. Latency in ms; lower is better. Throughput is successful teleports/sec over the run window; higher is better. CPU/TP columns are present only if a sibling `*-phases.csv` was found.",
        "",
    ]
    if have_cpu:
        lines += [
            "| Target | Attempts | Success% | TP/s | Cold-start | Warm p50 | p95 | p99 | Min TPS | p95 MSPT | Peak heap MB | CPU/TP total ms | CPU/TP main ms |",
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    else:
        lines += [
            "| Target | Attempts | Success% | TP/s | Cold-start | Warm p50 | p95 | p99 | Min TPS | p95 MSPT | Peak heap MB |",
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    for label, rows in groups.items():
        lats = successful_latencies(rows)
        n = len(rows)
        s = sum(1 for r in rows if r.get("success", "false").lower() == "true")
        sr = (100.0 * s / n) if n else 0.0
        cold = lats[0] if lats else float("nan")
        p50 = percentile(lats, 50) if lats else float("nan")
        p95 = percentile(lats, 95) if lats else float("nan")
        p99 = percentile(lats, 99) if lats else float("nan")
        tps_vals = [to_float(r.get("tps_at_dispatch", "")) for r in rows]
        tps_vals = [v for v in tps_vals if not math.isnan(v) and v > 0]
        mspt_vals = [to_float(r.get("mspt_at_dispatch", "")) for r in rows]
        mspt_vals = [v for v in mspt_vals if not math.isnan(v) and v >= 0]
        heap_vals = [to_float(r.get("heap_used_mb_at_dispatch", "")) for r in rows]
        heap_vals = [v for v in heap_vals if not math.isnan(v) and v > 0]
        min_tps = min(tps_vals) if tps_vals else float("nan")
        p95_mspt = percentile(mspt_vals, 95) if mspt_vals else float("nan")
        peak_heap = max(heap_vals) if heap_vals else float("nan")

        def f(v: float, nd: int = 1) -> str:
            return "—" if (v is None or (isinstance(v, float) and math.isnan(v))) else f"{v:.{nd}f}"

        dur = _run_duration_seconds(rows)
        tps_throughput = (s / dur) if dur > 0 else float("nan")
        base = (
            f"| {label} | {n} | {sr:.1f} | {f(tps_throughput,2)} | {f(cold,0)} | {f(p50,0)} | {f(p95,0)} | {f(p99,0)} "
            f"| {f(min_tps,1)} | {f(p95_mspt,1)} | {f(peak_heap,0)} |"
        )
        if have_cpu:
            slot = cpu_agg.get(label) or {}
            cpt = slot.get("cpu_per_tp_total", float("nan"))
            cpm = slot.get("cpu_per_tp_main",  float("nan"))
            base += f" {f(cpt,1)} | {f(cpm,1)} |"
        lines.append(base)
    lines.append("")
    out_path.write_text("\n".join(lines), encoding="utf-8")


# ---------------- Main ----------------

def main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser(description="Convert StressTestRTP CSV runs to comparison charts.")
    ap.add_argument("inputs", nargs="+", help="CSV file(s) or directory containing CSVs")
    ap.add_argument("-o", "--out", default="stress_charts", help="output directory (created)")
    args = ap.parse_args(argv)

    paths = expand_inputs(args.inputs)
    if not paths:
        sys.stderr.write("ERROR: no CSV inputs found.\n")
        return 1
    rows = load_rows(paths)
    if not rows:
        sys.stderr.write("ERROR: CSV(s) contained no rows.\n")
        return 1

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    groups = group_by_target(rows)

    plot_latency_bars(groups, out / "latency_bars.png")
    plot_latency_cdf(groups, out / "latency_cdf.png")
    plot_metric_over_time(groups, "tps_at_dispatch", "TPS",
                          "TPS sampled at each dispatch", out / "tps_over_time.png")
    plot_metric_over_time(groups, "mspt_at_dispatch", "MSPT (ms)",
                          "MSPT sampled at each dispatch", out / "mspt_over_time.png")
    plot_metric_over_time(groups, "heap_used_mb_at_dispatch", "heap used (MB)",
                          "Heap usage sampled at each dispatch", out / "heap_over_time.png")
    plot_success_rate(groups, out / "success_rate.png")
    # Heap-pressure-over-time series (sibling `<stamp>-heap.csv`), if present.
    heap_series = load_heap_rows(paths)
    plot_heap_pressure_over_time(heap_series, out / "heap_pressure_over_time.png")
    plot_heap_per_attempt(heap_series, out / "heap_per_attempt.png")
    plot_throughput_over_time(groups, out / "throughput_over_time.png")
    plot_throughput_bars(groups, out / "throughput_bars.png")
    plot_destination_scatter(groups, out / "destinations_scatter.png")

    # Phase-aggregate CPU/TP charts + summary columns (only if sibling phases CSVs exist).
    phase_rows = load_phase_rows(paths)
    cpu_agg = aggregate_phase_cpu(phase_rows) if phase_rows else None
    if cpu_agg:
        # Re-order CPU aggregate to match the per-target groups order so charts align.
        ordered: "OrderedDict[str, dict]" = OrderedDict()
        for label in groups.keys():
            if label in cpu_agg:
                ordered[label] = cpu_agg[label]
        # Append any phase labels not seen in the per-attempt CSV (rare).
        for label, slot in cpu_agg.items():
            ordered.setdefault(label, slot)
        cpu_agg = ordered
        plot_cpu_per_tp(cpu_agg, "cpu_per_tp_total",
                        "process CPU-ms per teleport",
                        "Process CPU per /rtp (lower is better)",
                        out / "cpu_per_tp_total.png")
        plot_cpu_per_tp(cpu_agg, "cpu_per_tp_main",
                        "main-thread CPU-ms per teleport",
                        "Main-thread CPU per /rtp (lower is better — sync chunk I/O shows up here)",
                        out / "cpu_per_tp_main.png")
    write_summary_md(groups, out / "summary.md", cpu_agg=cpu_agg)

    print(f"Wrote {len(list(out.iterdir()))} artefacts to {out.resolve()}")
    print(f"  Targets: {', '.join(groups.keys())}")
    print(f"  Total rows: {len(rows)} from {len(paths)} CSV file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
