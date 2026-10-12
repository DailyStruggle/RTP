#!/usr/bin/env python3
"""Per-plugin matrix report for a bench_matrix.py results directory (stdlib only).

Reads <out-dir>/manifest.csv, runs/<tag>/<stamp>-{ramp.csv,ramp-summary.txt,
phases.csv}, bandwidth.csv and logs/<tag>.log, and writes
  matrix.csv   long form: tag,target,pass,round,stage,metric,value
  matrix.md    one table per round (one row per target and stage), then outcomes
               and server-log findings

Bandwidth is the bot swarm's wire bytes (bandwidth.csv, this machine's clock).
Stage windows come from the harness's ramp.csv. The harness clock is mapped to
the driver clock with one offset per run: the first stage starts
idle_s + warmup_s after the ramp command was accepted. The idle baseline is the
second half of the idle window. Net bytes = bytes in the stage minus idle rate x
stage seconds; MiB per teleport divides by successful teleports. Values the
harness or the swarm could not measure print as NOT MEASURED, never as zero;
log columns print `unknown` when no log was captured.

Usage:  python scripts/bench_matrix_report.py <out-dir>
"""
from __future__ import annotations

import argparse
import bisect
import csv
import os
import re
import sys

MIB = 1 << 20
NOT_MEASURED = "NOT MEASURED"
# Plugin attribution by package prefix (same families as jfr-allocation.tracked-packages).
PLUGIN_PREFIXES = {
    "RTP": ("io.github.dailystruggle.rtp",),
    "BetterRTP": ("me.SuperRonanCraft.BetterRTP",),
    "EzRTP": ("com.skyblockexp.ezrtp",),
    "HuskHomes": ("net.william278.huskhomes",),
    "JustRTP": ("eu.kotori.justRTP",),
    "JakesRTP": ("biz.donvi.jakesRTP",),
    "StressTestRTP": ("io.github.dailystruggle.helpers.stresstestrtp",),
}
TARGET_PLUGIN = {"rtp": "RTP", "betterrtp": "BetterRTP", "ezrtp": "EzRTP", "huskhomes": "HuskHomes",
                 "justrtp": "JustRTP", "jakesrtp": "JakesRTP"}

LOG_LINE = re.compile(r"^\[(\d\d:\d\d:\d\d)\] \[(.+?)/(INFO|WARN|ERROR|FATAL|DEBUG|TRACE)\](?: \[[^\]]*\])?: ?(.*)$")
EXC_START = re.compile(r"^(?:Caused by: )?([A-Za-z_][\w.$]*(?:Exception|Error|Throwable))(?::|$)")
AT_LINE = re.compile(r"^\s+at ([\w.$<>/\-]+)\(")
WATCHDOG = re.compile(r"Tick region .*has not responded")
WATCHDOG_MS = re.compile(r"responded[^\d]{0,40}(\d+)\s*ms")
WATCHDOG_S = re.compile(r"responded[^\d]{0,40}(\d+(?:\.\d+)?)\s*s(?:ec|\b)")
CHUNK_ASYNC = re.compile(r"Cannot retrieve chunk asynchronously")
OFF_THREAD = re.compile(r"failed main thread check|non-owning thread|not owned by the current (?:thread|region)", re.I)
UNCAUGHT_TICK = re.compile(r"Uncaught exception in tick thread")
CANT_KEEP_UP = re.compile(r"Can't keep up!")
COULD_NOT_PASS = re.compile(r"Could not pass event \S+ to (\S+?)(?: v\S+)?$")
FOLIA_REFUSAL = re.compile(r"(?i)folia.*(?:not support|doesn't support|does not support|incompatible|not compatible)|"
                           r"(?:not support|incompatible).*folia")


# --- small helpers -----------------------------------------------------------

def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def load_csv(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def parse_summary(path: str) -> dict:
    out: dict[str, str] = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if ": " in line and not line.startswith("ramp "):
                k, _, v = line.partition(": ")
                out[k.strip()] = v.strip()
            elif line.startswith("ramp "):
                out["headline"] = line.strip()
    return out


def stage_of(label: str):
    m = re.search(r"@ramp(\d+)-", label or "")
    return int(m.group(1)) if m else None


def fmt(v, digits=1, suffix=""):
    if v is None:
        return "n/a"
    if isinstance(v, str):
        return v
    if v == -1:
        return NOT_MEASURED
    return f"{v:.{digits}f}{suffix}"


# --- bandwidth ---------------------------------------------------------------

class Series:
    """Cumulative swarm byte counters on the driver clock, safe against resets and -1 samples."""

    def __init__(self, rows: list[dict]):
        pts = []
        for r in rows:
            t, rx, tx = num(r.get("driver_epoch")), num(r.get("rx_bytes")), num(r.get("tx_bytes"))
            if t is None:
                continue
            valid = rx is not None and rx >= 0 and tx is not None and tx >= 0
            pts.append({"t": t, "valid": valid, "rx": rx if valid else 0.0, "tx": tx if valid else 0.0,
                        "online": num(r.get("online")) if valid else None,
                        "fresh": num(r.get("workers_fresh")), "seen": num(r.get("workers_seen"))})
        pts.sort(key=lambda p: p["t"])
        self.points = pts
        self.times = [p["t"] for p in pts]
        self.cum_t, self.cum_rx, self.cum_tx, self.reset_t = [], [], [], []
        prev = None
        c_rx = c_tx = 0.0
        for p in pts:
            if not p["valid"]:
                continue
            if prev is not None:
                d_rx, d_tx = p["rx"] - prev["rx"], p["tx"] - prev["tx"]
                if d_rx < 0 or d_tx < 0:
                    self.reset_t.append(p["t"])
                c_rx += max(d_rx, 0.0)
                c_tx += max(d_tx, 0.0)
            self.cum_t.append(p["t"])
            self.cum_rx.append(c_rx)
            self.cum_tx.append(c_tx)
            prev = p

    def _at(self, cum, t):
        if not self.cum_t or t < self.cum_t[0] or t > self.cum_t[-1]:
            return None
        i = bisect.bisect_left(self.cum_t, t)
        if self.cum_t[i] == t:
            return cum[i]
        t0, t1 = self.cum_t[i - 1], self.cum_t[i]
        return cum[i - 1] + (cum[i] - cum[i - 1]) * (t - t0) / (t1 - t0)

    def window(self, ta: float, tb: float) -> dict | None:
        """Bytes and health of [ta, tb], or None when the window is not covered by valid samples."""
        i, j = bisect.bisect_left(self.times, ta), bisect.bisect_right(self.times, tb)
        inside = self.points[i:j]
        if len(inside) < 2 or sum(1 for p in inside if p["valid"]) < 0.8 * len(inside):
            return None
        rx0, rx1 = self._at(self.cum_rx, ta), self._at(self.cum_rx, tb)
        tx0, tx1 = self._at(self.cum_tx, ta), self._at(self.cum_tx, tb)
        if None in (rx0, rx1, tx0, tx1):
            return None
        online = [p["online"] for p in inside if p["online"] is not None]
        return {"rx": rx1 - rx0, "tx": tx1 - tx0, "seconds": tb - ta,
                "online_first": online[0] if online else None, "online_min": min(online) if online else None,
                "resets": sum(1 for t in self.reset_t if ta <= t <= tb),
                "stale_workers": any(p["fresh"] is not None and p["seen"] is not None and p["fresh"] < p["seen"]
                                     for p in inside if p["valid"])}


def bandwidth_for_run(series: Series, t0: float | None, idle_s: float, warm_s: float, stages: list[dict]):
    """Per-stage bandwidth dicts keyed by stage index; each carries a `status` string."""
    out: dict[int, dict] = {}
    if t0 is None or not stages:
        return out, None
    first = stages[0]
    s0, e0 = num(first["ramp"].get("start_epoch_ms")), num(first["ramp"].get("end_epoch_ms"))
    if s0 is None or e0 is None:
        return out, None
    offset = s0 / 1000.0 - (t0 + idle_s + warm_s)  # harness clock minus driver clock
    idle = series.window(t0 + idle_s / 2.0, t0 + idle_s) if idle_s > 0 else None
    idle_rate = (idle["rx"] / idle["seconds"], idle["tx"] / idle["seconds"]) if idle else None
    for st in stages:
        s, e = num(st["ramp"].get("start_epoch_ms")), num(st["ramp"].get("end_epoch_ms"))
        if s is None or e is None:
            continue
        w = series.window(s / 1000.0 - offset, e / 1000.0 - offset)
        k = st["k"]
        if w is None:
            out[k] = {"status": NOT_MEASURED}
            continue
        d = dict(w)
        d["status"] = "ok"
        successes = num(st["ramp"].get("successes")) or 0.0
        if idle_rate:
            d["net_rx"] = w["rx"] - idle_rate[0] * w["seconds"]
            d["net_tx"] = w["tx"] - idle_rate[1] * w["seconds"]
            d["net_rx_per_tp"] = d["net_rx"] / successes / MIB if successes else None
        else:
            d["net_rx"] = d["net_tx"] = d["net_rx_per_tp"] = None
        d["idle_rx_rate"] = idle_rate[0] if idle_rate else None
        out[k] = d
    return out, offset


# --- log scan ----------------------------------------------------------------

def attribute(frames: list[str]) -> str:
    """Plugin of the topmost frame in a known package, else 'server/other'."""
    for f in frames:
        for plugin, prefixes in PLUGIN_PREFIXES.items():
            if any(f.startswith(p) for p in prefixes):
                return plugin
    return "server/other"


def mentions(plugin: str | None, text: str) -> bool:
    """Plugin named as a whole word, so `RTP` does not match `StressTestRTP` or `BetterRTP`."""
    return bool(plugin) and re.search(rf"(?<![A-Za-z0-9]){re.escape(plugin)}(?![A-Za-z0-9])", text) is not None


def scan_log(path: str, target: str) -> dict | None:
    """Counts and the first target-attributed problem in one server session log; None if missing."""
    if not path or not os.path.exists(path):
        return None
    target_plugin = TARGET_PLUGIN.get(target.lower())
    res = {"watchdog_stalls": 0, "watchdog_max_ms": 0, "chunk_async_errors": 0, "off_thread_errors": 0,
           "uncaught_tick_exceptions": 0, "cant_keep_up": 0, "could_not_pass_event": {}, "exceptions": {},
           "first_plugin_error": "", "folia_refusal": ""}
    first_line_no = [None]

    def note_first(line_no: int, text: str) -> None:
        if first_line_no[0] is None or line_no < first_line_no[0]:
            first_line_no[0] = line_no
            res["first_plugin_error"] = text[:220]

    header = ("", 0, "")  # time, line number, message of the last timestamped line
    block = None  # (line_no, header_text, [frames], message of the timestamped line above)

    def close_block() -> None:
        nonlocal block
        if block is None:
            return
        line_no, text, frames, ctx = block
        if "StacklessClosedChannelException" in text:
            who = "client-disconnect"
        elif re.search(r"Failed to load (?:function|advancement|recipe|loot)", ctx):
            who = "datapack"
        else:
            who = attribute(frames)
        res["exceptions"][who] = res["exceptions"].get(who, 0) + 1
        if target_plugin and who == target_plugin:
            note_first(line_no, text)
        block = None

    with open(path, encoding="utf-8", errors="replace") as fh:
        for line_no, raw in enumerate(fh, 1):
            line = raw.rstrip("\n")
            m = LOG_LINE.match(line)
            msg = line
            level = ""
            if m:
                close_block()
                header = (m.group(1), line_no, m.group(4))
                level, msg = m.group(3), m.group(4)
            if WATCHDOG.search(msg):
                res["watchdog_stalls"] += 1
                ms = WATCHDOG_MS.search(msg)
                sec = WATCHDOG_S.search(msg)
                val = int(ms.group(1)) if ms else int(float(sec.group(1)) * 1000) if sec else 0
                res["watchdog_max_ms"] = max(res["watchdog_max_ms"], val)
            if CHUNK_ASYNC.search(msg):
                res["chunk_async_errors"] += 1
            if OFF_THREAD.search(msg):
                res["off_thread_errors"] += 1
            if UNCAUGHT_TICK.search(msg):
                res["uncaught_tick_exceptions"] += 1
            if CANT_KEEP_UP.search(msg):
                res["cant_keep_up"] += 1
            if FOLIA_REFUSAL.search(msg) and not res["folia_refusal"]:
                res["folia_refusal"] = msg[:220]
            if m:
                cp = COULD_NOT_PASS.search(msg)
                if cp:
                    who = cp.group(1)
                    res["could_not_pass_event"][who] = res["could_not_pass_event"].get(who, 0) + 1
                    if target_plugin and who == target_plugin:
                        note_first(line_no, f"[{m.group(1)}] {msg}")
                elif level in ("ERROR", "FATAL") and mentions(target_plugin, msg):
                    note_first(line_no, f"[{m.group(1)}] {msg}")
            em = EXC_START.match(msg)
            if em and (not m or level in ("ERROR", "WARN", "FATAL")):
                if block is None or not msg.startswith("Caused by:"):
                    close_block()
                    block = (line_no, f"[{header[0]}] {msg}", [], header[2])
            elif block is not None:
                at = AT_LINE.match(line)
                if at:
                    block[2].append(at.group(1))
                elif not (line.startswith("\t") or line.startswith("Caused by:") or line.startswith("...")
                          or line.startswith("Suppressed:")):
                    close_block()
    close_block()
    return res


# --- run loading -------------------------------------------------------------

def load_attempts(path: str):
    """(dispatch_epoch_ms, latency_ms, success) per attempt from the harness's per-teleport CSV, or None."""
    if not os.path.exists(path):
        return None
    out = []
    for r in load_csv(path):
        d, lat = num(r.get("dispatch_epoch_ms")), num(r.get("latency_ms"))
        if d is not None and lat is not None:
            out.append((d, lat, str(r.get("success", "")).lower() == "true"))
    return out


def stage_latency(attempts: list, ramp_row: dict) -> dict | None:
    """Slowest successful teleport dispatched inside the stage window, and how many attempts fell in it."""
    lo, hi = num(ramp_row.get("start_epoch_ms")), num(ramp_row.get("end_epoch_ms"))
    if lo is None or hi is None:
        return None
    win = [(lat, ok) for d, lat, ok in attempts if lo <= d <= hi]
    ok_lat = [lat for lat, ok in win if ok]
    return {"max_ms": max(ok_lat) if ok_lat else None, "attempts": len(win),
            "slowest_failed_ms": max((lat for lat, ok in win if not ok), default=None)}


def load_run(out_dir: str, row: dict) -> dict:
    run = {"row": row, "stages": [], "summary": {}, "phases_found": False}
    stamp = row.get("run_stamp", "")
    if not stamp:
        return run
    base = os.path.join(out_dir, "runs", row["tag"], stamp)
    ramp = load_csv(base + "-ramp.csv")
    phases = {stage_of(p.get("phase_label")): p for p in load_csv(base + "-phases.csv")}
    run["phases_found"] = bool(phases)
    run["summary"] = parse_summary(base + "-ramp-summary.txt")
    attempts = load_attempts(base + ".csv")
    for r in ramp:
        k = int(num(r.get("stage_index")) or 0)
        run["stages"].append({"k": k, "ramp": r, "phase": phases.get(k, {}),
                              "latency": stage_latency(attempts, r) if attempts is not None else None})
    run["stages"].sort(key=lambda s: s["k"])
    return run


def stage_metrics(st: dict, bw: dict | None) -> dict:
    r, p = st["ramp"], st["phase"]
    attempts, succ = num(r.get("attempts")), num(r.get("successes"))
    m = {
        "offered_tps": num(r.get("offered_tps")), "achieved_tps": num(r.get("achieved_tps")),
        "attempts": attempts, "successes": succ,
        "success_rate": (succ / attempts) if attempts else None,
        "timeouts": num(r.get("timeouts")), "errors": num(r.get("errors")),
        "latency_p50_ms": num(r.get("latency_p50_ms")), "latency_p95_ms": num(r.get("latency_p95_ms")),
        "latency_p99_ms": num(r.get("latency_p99_ms")),
        "latency_max_ms": NOT_MEASURED, "latency_max_failed_attempt_ms": NOT_MEASURED,
        "mspt_p95": num(r.get("mspt_p95")), "tps_min": num(r.get("tps_min")),
        "main_cpu_ms_per_attempt": num(p.get("cpu_ms_per_attempt_main")),
        "process_cpu_ms_per_attempt": num(p.get("cpu_ms_per_attempt_total")),
        "net_main_cpu_ms_per_attempt": num(p.get("net_main_cpu_per_attempt")),
        "net_process_cpu_ms_per_attempt": num(p.get("net_process_cpu_per_attempt")),
        "chunks_per_attempt": num(p.get("chunks_per_attempt")),
        "gc_pause_ms": num(p.get("gc_total_time_ms")),
        "region_tps_min": num(p.get("region_tps_5s_min")), "region_tps_mean": num(p.get("region_tps_5s_mean")),
        "verdict": r.get("verdict", ""), "fail_criteria": r.get("fail_criteria", ""),
    }
    lat = st.get("latency")
    if lat is not None:
        m["latency_max_ms"] = lat["max_ms"] if lat["max_ms"] is not None else NOT_MEASURED
        m["latency_max_failed_attempt_ms"] = (lat["slowest_failed_ms"] if lat["slowest_failed_ms"] is not None
                                              else NOT_MEASURED)
    if bw is None or bw.get("status") != "ok":
        for k in ("rx_mib", "tx_mib", "net_rx_mib", "net_tx_mib", "net_rx_mib_per_teleport"):
            m[k] = NOT_MEASURED
        flags = []
    else:
        m["rx_mib"], m["tx_mib"] = bw["rx"] / MIB, bw["tx"] / MIB
        m["net_rx_mib"] = bw["net_rx"] / MIB if bw["net_rx"] is not None else NOT_MEASURED
        m["net_tx_mib"] = bw["net_tx"] / MIB if bw["net_tx"] is not None else NOT_MEASURED
        m["net_rx_mib_per_teleport"] = bw["net_rx_per_tp"] if bw["net_rx_per_tp"] is not None else NOT_MEASURED
        flags = []
        if bw["online_first"] is not None and bw["online_min"] is not None and bw["online_min"] < bw["online_first"]:
            flags.append(f"online-dip {int(bw['online_first'])}->{int(bw['online_min'])}")
        if bw["resets"]:
            flags.append("counter-reset")
        if bw["stale_workers"]:
            flags.append("swarm-worker-stale")
        if bw.get("idle_rx_rate") is None:
            flags.append("no-idle-baseline")
    crit = m["fail_criteria"]
    if "HARNESS_SATURATED" in crit:
        flags.append("HARNESS_SATURATED")
    if r.get("aborted", "").lower() == "true":
        flags.append("aborted")
    if r.get("complete", "true").lower() == "false":
        flags.append("incomplete")
    m["flags"] = ", ".join(flags)
    return m


# --- output ------------------------------------------------------------------

def long_rows(tag, target, pass_no, round_no, stage, metrics: dict):
    for k, v in metrics.items():
        yield [tag, target, pass_no, round_no, stage, k, "" if v is None else v]


def table(headers: list[str], rows: list[list[str]]) -> list[str]:
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return out


def build(out_dir: str) -> tuple[list[list], str]:
    manifest = load_csv(os.path.join(out_dir, "manifest.csv"))
    series = Series(load_csv(os.path.join(out_dir, "bandwidth.csv")))
    csv_rows: list[list] = []
    per_round: dict[str, list[list[str]]] = {}
    outcome_rows, log_rows, notes = [], [], []
    for row in manifest:
        tag, target = row["tag"], row["target"]
        p_no, r_no = row["pass"], row["round"]
        run = load_run(out_dir, row)
        idle_s, warm_s = num(row.get("idle_s")) or 0.0, num(row.get("warmup_s")) or 0.0
        t0 = num(row.get("ramp_t0_epoch"))
        bw, offset = bandwidth_for_run(series, t0, idle_s, warm_s, run["stages"])
        label = f"{target} (control, floor)" if target == "direct" else target
        for st in run["stages"]:
            m = stage_metrics(st, bw.get(st["k"]))
            stage_name = f"s{st['k']}@{fmt(m['offered_tps'], 0)}tps"
            csv_rows.extend(long_rows(tag, target, p_no, r_no, stage_name, m))
            flag = m["flags"] + ("" if not m["verdict"] else (", " if m["flags"] else "") + m["verdict"])
            main_cpu = fmt(m["main_cpu_ms_per_attempt"], 1)
            proc_cpu = fmt(m["process_cpu_ms_per_attempt"], 1)
            for key, label_net in (("net_main_cpu_ms_per_attempt", "main"), ("net_process_cpu_ms_per_attempt", "proc")):
                if m[key] is not None and m[key] != -1:
                    if label_net == "main":
                        main_cpu += f" (net {m[key]:.1f})"
                    else:
                        proc_cpu += f" (net {m[key]:.1f})"
            per_round.setdefault(r_no, []).append([
                f"p{p_no} {label}", stage_name, row["outcome"], fmt(m["achieved_tps"], 2),
                fmt(None if m["success_rate"] is None else m["success_rate"] * 100, 1, "%"),
                f"{fmt(m['latency_p50_ms'], 0)} / {fmt(m['latency_p95_ms'], 0)} / {fmt(m['latency_p99_ms'], 0)} / "
                f"{fmt(m['latency_max_ms'], 0)}",
                fmt(m["mspt_p95"], 1), main_cpu, proc_cpu, fmt(m["chunks_per_attempt"], 2), fmt(m["gc_pause_ms"], 0),
                f"{fmt(m['region_tps_min'], 1)} / {fmt(m['region_tps_mean'], 1)}",
                fmt(m["net_rx_mib"], 1), fmt(m["net_rx_mib_per_teleport"], 4), flag])
        log = scan_log(os.path.join(out_dir, row["log_file"]) if row.get("log_file") else "", target)
        prev = os.path.join(out_dir, "logs", f"{tag}.prev.log")
        if log is not None:
            for k, v in log.items():
                if isinstance(v, dict):
                    for name, n in v.items():
                        csv_rows.append([tag, target, p_no, r_no, "run", f"log.{k}.{name}", n])
                else:
                    csv_rows.append([tag, target, p_no, r_no, "run", f"log.{k}", v])
        csv_rows.append([tag, target, p_no, r_no, "run", "outcome", row["outcome"]])
        csv_rows.append([tag, target, p_no, r_no, "run", "cfg_hash_start", row.get("cfg_hash_start", "")])
        csv_rows.append([tag, target, p_no, r_no, "run", "cfg_hash_end", row.get("cfg_hash_end", "")])
        swaps = row.get("swaps", "")
        for k in ("swaps", "live_chunk_async", "live_watchdog", "live_first_error"):
            csv_rows.append([tag, target, p_no, r_no, "run", k, row.get(k, "")])
        outcome_rows.append([f"p{p_no} r{r_no}", label, row["outcome"], row.get("loaded_rtp_plugins") or "(none)",
                             row.get("run_stamp") or "-", swaps if swaps != "" else NOT_MEASURED,
                             (row.get("note") or "").strip() or "-"])
        if swaps not in ("", None) and int(float(swaps)) > 0:
            notes.append(f"{tag}: the swarm replaced {int(float(swaps))} stuck bot(s) (new names, new UUIDs) during the run. "
                         "Each swap is a rejoin, so it adds one join chunk burst to that stage's bandwidth, and the "
                         "replaced bot's last attempt counts as a timeout.")
        if row.get("live_first_error"):
            notes.append(f"{tag}: first problem seen live: `{row['live_first_error']}` "
                         f"(async chunk errors {row.get('live_chunk_async') or 0}, watchdog lines {row.get('live_watchdog') or 0}).")
        if log is None:
            log_rows.append([f"p{p_no} r{r_no}", label, "unknown", "unknown", "unknown", "unknown", "unknown", "no log captured"])
        else:
            exc = ", ".join(f"{k}:{v}" for k, v in sorted(log["exceptions"].items())) or "0"
            cnp = ", ".join(f"{k}:{v}" for k, v in sorted(log["could_not_pass_event"].items())) or "0"
            first = log["first_plugin_error"] or ("no plugin loaded" if target == "direct" else "none found")
            if row["outcome"] == "unsupported-not-loaded" and log["folia_refusal"]:
                first = "failed on this rig, this config: " + log["folia_refusal"]
            log_rows.append([f"p{p_no} r{r_no}", label,
                             f"{log['watchdog_stalls']} (max {log['watchdog_max_ms']} ms)" if log["watchdog_stalls"] else "0",
                             f"{log['chunk_async_errors']} / {log['off_thread_errors']}", exc, cnp,
                             log["uncaught_tick_exceptions"], first])
        if os.path.exists(prev):
            log_rows[-1][-1] += f" (crashed session log: logs/{tag}.prev.log)"
        a, b = row.get("cfg_hash_start", ""), row.get("cfg_hash_end", "")
        if a and b and a != b:
            notes.append(f"{tag}: plugin config files changed during the run ({a} -> {b}).")
        if run["stages"] and not run["phases_found"]:
            notes.append(f"{tag}: no phases.csv; CPU, chunk, GC and regional TPS columns are NOT MEASURED.")
        if offset is not None and abs(offset) > 5:
            notes.append(f"{tag}: harness clock differs from this machine by {offset:.1f} s; stage windows use that offset.")
    # a target's config seen differently at the start of a later run = reverted between runs
    last_end: dict[str, tuple[str, str]] = {}
    for row in manifest:
        t, a = row["target"], row.get("cfg_hash_start", "")
        if t in last_end and a and last_end[t][1] and a != last_end[t][1]:
            notes.append(f"{row['tag']}: {t} config at start differs from the end of {last_end[t][0]} "
                         f"({last_end[t][1]} -> {a}); something rewrote it across the restart.")
        if row.get("cfg_hash_end"):
            last_end[t] = (row["tag"], row["cfg_hash_end"])

    md = ["# Folia per-plugin matrix", "",
          "n=1 per target per pass on one rig; compare rows only within this run. A failure here means "
          "\"failed on this rig, this config\", not a defect in the plugin beyond what the log lines show. "
          "`direct` is the plugin-free control and the floor for bandwidth and cost.", "",
          "Latency is dispatch to arrival. The percentiles come from the harness's ramp summary; the maximum is the "
          "slowest successful teleport in the stage, read from the harness's per-teleport CSV (timed-out attempts "
          "are in `latency_max_failed_attempt_ms` in matrix.csv, not in the table). On Folia the harness's MSPT is a "
          "50 ms timer on the global region, so it sits at about 50 whatever the load and says nothing about the "
          "regions players are in; read the regional TPS column instead. CPU per attempt is gross "
          "unless a `net` value is shown. Bandwidth is wire MiB received by the bots (server to client), "
          "net of the idle baseline; MiB per teleport divides by successful teleports. "
          f"{NOT_MEASURED} means the harness or the swarm did not measure it.", ""]
    heads = ["target", "stage", "outcome", "achieved TP/s", "success", "latency p50 / p95 / p99 / max ms",
             "MSPT p95 (global timer)",
             "region/main CPU ms/attempt", "process CPU ms/attempt", "chunks/attempt", "GC pause ms",
             "regional TPS min / mean", "net rx MiB", "net rx MiB/teleport", "flags"]
    for r_no in sorted(per_round, key=int):
        md += [f"## Round {r_no}", ""] + table(heads, per_round[r_no]) + [""]
    md += ["## Outcomes", ""] + table(["pass/round", "target", "outcome", "loaded RTP plugins", "run stamp", "bots swapped", "note"],
                                      outcome_rows) + [""]
    md += ["## Server log findings (whole session, startup included)", ""] + table(
        ["pass/round", "target", "watchdog stalls", "async chunk / off-thread errors", "exceptions by plugin",
         "could not pass event", "uncaught tick-thread exceptions", "first plugin error"], log_rows) + [""]
    if notes:
        md += ["## Notes", ""] + [f"- {n}" for n in notes] + [""]
    return csv_rows, "\n".join(md)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("out_dir")
    a = p.parse_args(argv)
    if not os.path.exists(os.path.join(a.out_dir, "manifest.csv")):
        print(f"no manifest.csv in {a.out_dir}", file=sys.stderr)
        return 2
    rows, md = build(a.out_dir)
    with open(os.path.join(a.out_dir, "matrix.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["tag", "target", "pass", "round", "stage", "metric", "value"])
        w.writerows(rows)
    with open(os.path.join(a.out_dir, "matrix.md"), "w", encoding="utf-8") as fh:
        fh.write(md + "\n")
    print(f"wrote {os.path.join(a.out_dir, 'matrix.md')} and matrix.csv ({len(rows)} rows)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
