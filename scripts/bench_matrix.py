#!/usr/bin/env python3
"""One rtp plugin at a time on a Folia (or Paper) server: paced ramp, ladder,
bandwidth and server-log capture (stdlib only).

Reuses bench_overnight.Driver for RCON, jar isolation, restart and the
loaded-plugin-set check, so a run never measures the wrong plugin set. Before
every (target, round) the server is restarted with only that target's jar
enabled. The `direct` control has no jar: every RTP jar is disabled and the
loaded RTP set must be empty.

Per (target, round) it records, in <out-dir>:
  manifest.csv        target, round, outcome, wall window, run stamp, config hashes
  bandwidth.csv       swarm wire-byte samples on this machine's clock (see below)
  runs/<tag>/         copies of the harness's per-teleport <stamp>.csv (latency of every attempt),
                      -ramp.csv, -ramp-summary.txt and -phases.csv
  logs/<tag>.log      the server session's latest.log, snapshotted when the ramp ends
                      (<tag>.prev.log = the previous session, kept after a crash)
and finally writes matrix.csv / matrix.md through bench_matrix_report.

Bandwidth comes from the bot swarm, which must be started with
`--stats-file <path>` (devstack/clients/bench-swarm.js) and keep running across
server restarts; pass the same path as --swarm-stats. Start it with `--swap-stuck`
too: a bot the server stops teleporting while it teleports the others (a plugin
that locks a player out after an error, as EzRTP does on Folia) is replaced by a
new-named bot, and the count is recorded per run (manifest `swaps`). A live log
watcher records the first plugin problem and the watchdog / async-chunk counts per run. A background thread
samples that file every --bw-poll-s on this machine's clock, so the server's
clock never enters the measurement. If the file stops advancing the samples are
written as -1 (NOT MEASURED) and the run continues.

Outcomes: finished, unsupported-not-loaded, crashed-recovered, not-started,
timed-out (plus the Driver's crashed-down and skipped-* for infrastructure
failures). Re-running with --resume <out-dir> skips (pass, round, target) rows
that already ended in finished, unsupported-not-loaded or crashed-recovered.

Example:
  node devstack/clients/bench-swarm.js --host <server> --port 25677 --count 48 \\
       --stats-file /tmp/swarm-stats.jsonl --swap-stuck --churn-rename
  python scripts/bench_matrix.py --server-dir ~/threadripper-dir/folia/26.2 \\
       --host <server> --swarm-stats /tmp/swarm-stats.jsonl --dry-run
  python scripts/bench_matrix.py ... --targets direct,rtp --round 5@60   # smoke
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import glob
import gzip
import hashlib
import json
import os
import re
import shutil
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench_matrix_report as rep  # noqa: E402
import bench_overnight as bo  # noqa: E402

PROFILES = {
    "folia": {
        "targets": "direct,rtp,huskhomes,justrtp,ezrtp,betterrtp,jakesrtp",
        # Paced ramp (4,096 teleports, same as the earlier Folia sequence run), then a short ladder.
        "rounds": ["5@820", "10,20,40@60"],
        # No folia-supported in plugin.yml: one load attempt, no retries.
        "unsupported": ["jakesrtp"],
    },
}
DONE_OUTCOMES = {"finished", "unsupported-not-loaded", "crashed-recovered"}
MANIFEST_COLS = ["pass", "round", "target", "rates", "stage_seconds", "tag", "outcome", "wall_start",
                 "wall_end", "ramp_t0_epoch", "ramp_t1_epoch", "idle_s", "warmup_s", "run_stamp",
                 "loaded_rtp_plugins", "cfg_hash_start", "cfg_hash_end", "log_file", "last_status", "note",
                 "swaps", "live_chunk_async", "live_watchdog", "live_first_error"]
BANDWIDTH_COLS = ["driver_epoch", "label", "rx_bytes", "tx_bytes", "online", "workers_fresh", "workers_seen", "swaps"]
STATS_TAIL_BYTES = 1 << 17


# --- swarm stats file --------------------------------------------------------

def read_swarm_stats(path: str, tail_bytes: int = STATS_TAIL_BYTES) -> dict[int, dict]:
    """Latest {t, rx, tx, online, swaps} per worker from the JSONL tail; swaps is None unless the swarm
    runs with --swap-stuck. A partial last line is ignored."""
    latest: dict[int, dict] = {}
    try:
        with open(path, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - tail_bytes))
            data = fh.read().decode("utf-8", errors="replace")
    except OSError:
        return latest
    for line in data.splitlines():
        try:
            d = json.loads(line)
            latest[int(d["w"])] = {"t": float(d["t"]), "rx": int(d["rx"]), "tx": int(d["tx"]),
                                   "online": int(d["online"]),
                                   "swaps": int(d["swaps"]) if "swaps" in d else None}
        except (ValueError, KeyError, TypeError):
            continue
    return latest


class BandwidthPoller(threading.Thread):
    """Samples the swarm stats file on this machine's clock into bandwidth.csv.

    Staleness is judged by the file's own counters not changing for stale_s of
    local time, never by comparing the swarm's timestamps to this clock.
    """

    def __init__(self, stats_path: str, out_csv: str, interval_s: float, stale_s: float):
        super().__init__(daemon=True, name="bandwidth-poller")
        self.stats_path, self.out_csv = stats_path, out_csv
        self.interval_s, self.stale_s = interval_s, stale_s
        self.label = ""
        self._halt = threading.Event()
        self._last_t: dict[int, float] = {}
        self._changed_at: dict[int, float] = {}
        self._warned = False
        self.swaps = -1  # bots swapped so far (sum over workers); -1 = swarm not run with --swap-stuck

    def sample(self, now: float | None = None) -> list:
        now = time.time() if now is None else now
        stats = read_swarm_stats(self.stats_path)
        for w, s in stats.items():
            if self._last_t.get(w) != s["t"]:
                self._last_t[w] = s["t"]
                self._changed_at[w] = now
        fresh = [w for w in stats if now - self._changed_at.get(w, 0) <= self.stale_s]
        if not fresh:
            if not self._warned:
                bo.log(f"bandwidth: swarm stats file {self.stats_path} is not advancing; recording -1 (NOT MEASURED)")
                self._warned = True
            return [f"{now:.3f}", self.label, -1, -1, -1, 0, len(stats), self.swaps]
        self._warned = False
        reported = [s["swaps"] for s in stats.values() if s["swaps"] is not None]
        self.swaps = sum(reported) if reported else -1
        return [f"{now:.3f}", self.label, sum(stats[w]["rx"] for w in stats), sum(stats[w]["tx"] for w in stats),
                sum(stats[w]["online"] for w in fresh), len(fresh), len(stats), self.swaps]

    def run(self) -> None:
        new = not os.path.exists(self.out_csv) or os.path.getsize(self.out_csv) == 0
        with open(self.out_csv, "a", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            if new:
                w.writerow(BANDWIDTH_COLS)
            while not self._halt.is_set():
                w.writerow(self.sample())
                fh.flush()
                self._halt.wait(self.interval_s)

    def stop(self) -> None:
        self._halt.set()
        self.join(timeout=5)


class LogWatcher(threading.Thread):
    """Follows the server's latest.log during a ramp and records the first sign of trouble.

    Counts the Folia watchdog line, `Cannot retrieve chunk asynchronously`, `Could not pass event`
    for the target plugin and ERROR lines naming it, and keeps the first such line, so a broken
    target (EzRTP's off-thread chunk lookup) shows up in the manifest and the live log. The run
    continues; the offline report does the full scan.
    """

    def __init__(self, path: str, target: str, interval_s: float = 5.0, tag: str = ""):
        super().__init__(daemon=True, name="log-watcher")
        self.path, self.interval_s, self.tag = path, interval_s, tag
        self.plugin = rep.TARGET_PLUGIN.get(target.lower())
        self.counts = {"chunk_async": 0, "watchdog": 0, "plugin_errors": 0}
        self.first_line = ""
        self._halt = threading.Event()
        self._offset = os.path.getsize(path) if os.path.exists(path) else 0
        self._tail = ""

    def feed(self, text: str) -> None:
        text = self._tail + text
        lines = text.split("\n")
        self._tail = lines.pop()  # incomplete last line waits for the next read
        for line in lines:
            m = rep.LOG_LINE.match(line)
            msg = m.group(4) if m else line
            level = m.group(3) if m else ""
            hit = None
            if rep.CHUNK_ASYNC.search(msg):
                self.counts["chunk_async"] += 1
                hit = "chunk_async"
            if rep.WATCHDOG.search(msg):
                self.counts["watchdog"] += 1
                hit = "watchdog"
            if m and (rep.COULD_NOT_PASS.search(msg) and rep.mentions(self.plugin, msg)
                      or level in ("ERROR", "FATAL") and rep.mentions(self.plugin, msg)):
                self.counts["plugin_errors"] += 1
                hit = "plugin_errors"
            if hit:
                n = self.counts[hit]
                if not self.first_line:
                    self.first_line = line.strip()[:220]
                    bo.log(f"{self.tag}: log: first problem: {self.first_line}")
                elif n in (10, 100, 1000):
                    bo.log(f"{self.tag}: log: {hit} reached {n}")

    def poll(self) -> None:
        try:
            size = os.path.getsize(self.path)
            if size < self._offset:  # rotated or restarted
                self._offset, self._tail = 0, ""
            with open(self.path, "rb") as fh:
                fh.seek(self._offset)
                data = fh.read()
            self._offset += len(data)
        except OSError:
            return
        if data:
            self.feed(data.decode("utf-8", errors="replace"))

    def run(self) -> None:
        while not self._halt.is_set():
            self.poll()
            self._halt.wait(self.interval_s)

    def stop(self) -> None:
        self._halt.set()
        self.join(timeout=5)
        self.poll()


# --- driver ------------------------------------------------------------------

class MatrixDriver(bo.Driver):
    """Driver that notes when the server accepted the ramp command (this machine's clock)."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.ramp_t0: float | None = None

    def try_cmd(self, command: str) -> str | None:
        before = time.time()
        r = super().try_cmd(command)
        if command.startswith("rtpstress ramp ") and r and "ramp started" in r:
            self.ramp_t0 = (before + time.time()) / 2
        return r

    def ramp_started_hint(self, sent_at: float) -> None:
        self.ramp_t0 = sent_at + 1.0


def read_ramp_cfg(server_dir: str) -> tuple[int, int]:
    """(idle_seconds, warmup_seconds) of the harness ramp block; harness defaults when unreadable."""
    idle, warm = 30, 30
    try:
        with open(os.path.join(server_dir, "plugins", "StressTestRTP", "config.yml"), encoding="utf-8") as fh:
            text = fh.read()
    except OSError:
        return idle, warm
    m = re.search(r"^ramp:\s*\n((?:[ \t].*\n|\s*\n)*)", text + "\n", re.M)
    block = m.group(1) if m else ""
    mi = re.search(r"^\s+idle-seconds:\s*(\d+)", block, re.M)
    mw = re.search(r"^\s+warmup-seconds:\s*(\d+)", block, re.M)
    return (int(mi.group(1)) if mi else idle), (int(mw.group(1)) if mw else warm)


def spark_enabled(server_dir: str) -> bool:
    try:
        with open(os.path.join(server_dir, "plugins", "StressTestRTP", "config.yml"), encoding="utf-8") as fh:
            text = fh.read()
    except OSError:
        return False
    m = re.search(r"^spark:\s*\n((?:[ \t].*\n|\s*\n)*)", text + "\n", re.M)
    return bool(m and re.search(r"^\s+enabled:\s*true", m.group(1), re.M))


def config_hash(drv: bo.Driver, target: str) -> str:
    """SHA-256 over the target plugin's yml/toml/properties files; '' when it has no data dir."""
    name = drv.names.get(target.lower())
    if not name:
        return ""
    base = None
    for d in os.listdir(drv.plugins_dir()):
        if d.lower() == name.lower() and os.path.isdir(os.path.join(drv.plugins_dir(), d)):
            base = os.path.join(drv.plugins_dir(), d)
            break
    if base is None:
        return "no-data-dir"
    h = hashlib.sha256()
    for root, _dirs, files in sorted(os.walk(base)):
        for f in sorted(files):
            if f.lower().endswith((".yml", ".yaml", ".toml", ".properties", ".json")):
                p = os.path.join(root, f)
                try:
                    with open(p, "rb") as fh:
                        h.update(os.path.relpath(p, base).encode() + b"\0" + fh.read())
                except OSError:
                    h.update(f.encode() + b"\0unreadable")
    return h.hexdigest()[:16]


def verify_set(drv: bo.Driver, target: str) -> tuple[bool, str]:
    """Target's plugin loaded and no other RTP plugin; `direct` needs an empty RTP set."""
    ok, loaded = drv.jar_set_ok(target)
    if target.lower() not in drv.names:
        return (ok and loaded == ""), loaded
    return ok, loaded


def snapshot_log(server_dir: str, dest: str) -> bool:
    src = os.path.join(server_dir, "logs", "latest.log")
    try:
        shutil.copyfile(src, dest)
        return True
    except OSError as e:
        bo.log(f"log: cannot copy {src}: {e}")
        return False


def snapshot_previous_log(server_dir: str, dest: str) -> bool:
    """Newest rotated log (the session before latest.log), decompressed."""
    files = glob.glob(os.path.join(server_dir, "logs", "*.log.gz"))
    if not files:
        return False
    newest = max(files, key=os.path.getmtime)
    try:
        with gzip.open(newest, "rb") as src, open(dest, "wb") as out:
            shutil.copyfileobj(src, out)
        return True
    except OSError as e:
        bo.log(f"log: cannot read {newest}: {e}")
        return False


def ramp_csvs(server_dir: str) -> set[str]:
    return set(glob.glob(os.path.join(server_dir, "plugins", "StressTestRTP", "runs", "*-ramp.csv")))


def copy_run_files(server_dir: str, stamp: str, dest_dir: str) -> list[str]:
    os.makedirs(dest_dir, exist_ok=True)
    runs = os.path.join(server_dir, "plugins", "StressTestRTP", "runs")
    copied = []
    for suffix in (".csv", "-ramp.csv", "-ramp-summary.txt", "-phases.csv"):
        src = os.path.join(runs, stamp + suffix)
        if os.path.exists(src):
            shutil.copyfile(src, os.path.join(dest_dir, stamp + suffix))
            copied.append(suffix)
    return copied


# --- manifest ----------------------------------------------------------------

def read_manifest(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def done_keys(rows: list[dict]) -> set[tuple[int, int, str]]:
    return {(int(r["pass"]), int(r["round"]), r["target"]) for r in rows if r["outcome"] in DONE_OUTCOMES}


# --- plan --------------------------------------------------------------------

def build_plan(targets: list[str], rounds: list[tuple[str, int]], passes: int) -> list[tuple[int, int, str]]:
    """(pass, round index 1-based, target); even passes run the target list reversed."""
    plan = []
    for p in range(1, passes + 1):
        order = targets if p % 2 == 1 else list(reversed(targets))
        for t in order:
            for ri in range(1, len(rounds) + 1):
                plan.append((p, ri, t))
    return plan


def estimate_s(drv: bo.Driver, a: argparse.Namespace, rounds: list[tuple[str, int]], ri: int) -> int:
    rates, secs = rounds[ri - 1]
    return a.restart_estimate_s + a.settle_s + drv.ramp_budget_s(rates, secs) - a.margin_s


# --- main --------------------------------------------------------------------

def make_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--profile", choices=sorted(PROFILES), default="folia")
    p.add_argument("--server-dir", required=True, help="server root (reads rcon.port / rcon.password from server.properties)")
    p.add_argument("--host", default="127.0.0.1", help="server host for RCON")
    p.add_argument("--port", type=int)
    p.add_argument("--password", default=os.environ.get("RCON_PASSWORD"))
    p.add_argument("--swarm-stats", help="JSONL file the bot swarm writes (bench-swarm.js --stats-file); required for a live run")
    p.add_argument("--bw-poll-s", type=float, default=2.0)
    p.add_argument("--log-poll-s", type=float, default=5.0, help="how often the live server-log watcher reads latest.log")
    p.add_argument("--swarm-stale-s", type=float, default=20.0, help="stats file counters unchanged this long = NOT MEASURED")
    p.add_argument("--targets", help="comma-separated; default: the profile's list")
    p.add_argument("--round", dest="rounds", action="append", help="'rates@stageSeconds', repeatable; default: the profile's rounds")
    p.add_argument("--passes", type=int, default=1, help="repeat the full list; even passes run it in reverse order")
    p.add_argument("--spark", action="store_true", help="require spark: jar present and StressTestRTP spark.enabled: true (never edits configs)")
    p.add_argument("--out-dir", help="results directory; default scripts/tmp/matrix-<stamp>")
    p.add_argument("--resume", metavar="DIR", help="continue a previous --out-dir, skipping rows already done")
    p.add_argument("--jars", default=bo.DEFAULT_JARS)
    p.add_argument("--names", default=bo.DEFAULT_NAMES)
    p.add_argument("--restart-retries", type=int, default=2, help="extra restarts on a wrong plugin set (0 for unsupported targets)")
    p.add_argument("--restart-estimate-s", type=int, default=600)
    p.add_argument("--min-free-gb", type=float, default=20.0)
    p.add_argument("--min-players", type=int, default=32)
    p.add_argument("--player-wait-s", type=int, default=600)
    p.add_argument("--settle-s", type=int, default=60)
    p.add_argument("--poll-s", type=int, default=20)
    p.add_argument("--shutdown-wait-s", type=int, default=1200)
    p.add_argument("--startup-wait-s", type=int, default=900)
    p.add_argument("--restart-command", default="stop",
                   help="Folia has no /restart: 'stop' exits the JVM and the relaunch loop (scripts/folia-start-loop.sh, started "
                        "once on the server host) starts it again; use 'restart' on Paper")
    p.add_argument("--ramp-idle-s", type=int)
    p.add_argument("--ramp-warmup-s", type=int)
    p.add_argument("--ramp-gap-s", type=int, default=5)
    p.add_argument("--attempt-timeout-s", type=int, default=5)
    p.add_argument("--margin-s", type=int, default=300)
    p.add_argument("--dry-run", action="store_true", help="print the plan and time estimate; no RCON traffic")
    return p


def preflight(a: argparse.Namespace, drv: MatrixDriver, targets: list[str], unsupported: list[str]) -> list[str]:
    """Returns the list of failures; each check is logged."""
    fails: list[str] = []

    def check(ok: bool, what: str, detail: str = "") -> None:
        bo.log(f"preflight: {'ok  ' if ok else 'FAIL'} {what}{(' - ' + detail) if detail else ''}")
        if not ok:
            fails.append(what)

    for t in targets:
        if t.lower() in drv.jars:
            found = [f for f in drv.jar_files(drv.jars[t.lower()])]
            check(bool(found), f"jar for {t}", ", ".join(found) or "none in plugins/")
    check(shutil.disk_usage(a.server_dir).free / 2**30 >= a.min_free_gb, "free disk",
          f"{bo.free_gb(a.server_dir):.0f} GB, need {a.min_free_gb:.0f}")
    up = drv.wait_up(60)
    check(up, f"RCON reachable at {a.host}:{drv.port}")
    if up:
        on_server = drv.targets_on_server()
        if on_server is not None:
            missing = [t for t in targets if t.lower() not in {s.lower() for s in on_server}]
            check(not missing, "targets known to the harness", f"missing: {missing}" if missing else ",".join(on_server))
        n = drv.online()
        check(n >= a.min_players, "swarm online", f"{n} players, want >= {a.min_players}")
    first = read_swarm_stats(a.swarm_stats)
    time.sleep(3.5)
    second = read_swarm_stats(a.swarm_stats)
    advancing = bool(second) and any(second[w]["t"] > first.get(w, {"t": -1})["t"] for w in second)
    check(advancing, "swarm stats file advancing", a.swarm_stats)
    if not any(s["swaps"] is not None for s in second.values()):
        bo.log("preflight: warn swarm is not running with --swap-stuck; bots locked out by a plugin stay stuck for the run")
    if a.spark:
        sj = glob.glob(os.path.join(drv.plugins_dir(), "spark*.jar"))
        check(bool(sj) and spark_enabled(a.server_dir), "spark jar present and spark.enabled: true",
              "jar missing or StressTestRTP/config.yml spark.enabled is not true")
    elif spark_enabled(a.server_dir):
        bo.log("preflight: note StressTestRTP spark.enabled is true but --spark was not given; profiles will still be taken")
    jakes = [t for t in unsupported if t in [x.lower() for x in targets]]
    if jakes:
        bo.log(f"preflight: unsupported list {jakes}: one load attempt each, no retries")
    return fails


def write_row(w, mf, row: dict) -> None:
    w.writerow([row.get(c, "") for c in MANIFEST_COLS])
    mf.flush()


def run_matrix(a, drv: MatrixDriver, poller: BandwidthPoller, out_dir: str, plan, rounds, unsupported) -> int:
    manifest_path = os.path.join(out_dir, "manifest.csv")
    prior = read_manifest(manifest_path)
    skip = done_keys(prior)
    idle_s, warm_s = read_ramp_cfg(a.server_dir)
    if a.ramp_idle_s is not None:
        idle_s = a.ramp_idle_s
    if a.ramp_warmup_s is not None:
        warm_s = a.ramp_warmup_s
    refused: set[tuple[int, str]] = {(int(r["pass"]), r["target"]) for r in prior if r["outcome"] == "unsupported-not-loaded"}
    new_file = not prior
    default_retries = a.restart_retries
    with open(manifest_path, "a", newline="", encoding="utf-8") as mf:
        w = csv.writer(mf)
        if new_file:
            w.writerow(MANIFEST_COLS)
            mf.flush()
        for pass_no, ri, target in plan:
            rates, secs = rounds[ri - 1]
            tag = f"p{pass_no}-r{ri}-{target}"
            if (pass_no, ri, target) in skip:
                bo.log(f"{tag}: already done in the manifest; skipping")
                continue
            if bo.free_gb(a.server_dir) < a.min_free_gb:
                bo.log(f"free space {bo.free_gb(a.server_dir):.0f} GB < {a.min_free_gb:.0f} GB; stopping")
                return 4
            row = {"pass": pass_no, "round": ri, "target": target, "rates": rates, "stage_seconds": secs, "tag": tag,
                   "idle_s": idle_s, "warmup_s": warm_s, "wall_start": dt.datetime.now().isoformat(timespec="seconds")}
            if (pass_no, target) in refused:
                row.update(outcome="unsupported-not-loaded", wall_end=row["wall_start"],
                           note="not restarted: the plugin was refused earlier this pass")
                write_row(w, mf, row)
                bo.log(f"{tag}: unsupported-not-loaded (refused earlier this pass)")
                continue
            is_unsupported = target.lower() in unsupported
            drv.a.restart_retries = 0 if is_unsupported else default_retries
            bo.log(f"=== {tag}: rates={rates} stage={secs}s{' (unsupported: single load attempt)' if is_unsupported else ''}")
            poller.label = ""
            restarted = drv.restart(target)
            ok, loaded = verify_set(drv, target) if restarted else (False, "")
            row["loaded_rtp_plugins"] = loaded
            runs_dir = os.path.join(out_dir, "runs", tag)
            log_path = os.path.join(out_dir, "logs", f"{tag}.log")
            os.makedirs(os.path.dirname(log_path), exist_ok=True)
            if not restarted or not ok:
                up = drv.try_cmd("list") is not None or drv.wait_up(a.startup_wait_s)
                if not up:
                    outcome, note = "crashed-down", "server did not come back after the restart"
                else:
                    _, loaded = drv.jar_set_ok(target)
                    row["loaded_rtp_plugins"] = loaded
                    want = drv.names.get(target.lower())
                    if is_unsupported and want and want not in (drv.loaded_plugins() or set()):
                        outcome, note = "unsupported-not-loaded", "failed on this rig, this config: plugin not in the loaded set after one load attempt"
                        refused.add((pass_no, target))
                    else:
                        outcome, note = ("skipped-wrong-plugins" if restarted else "skipped-restart-failed"), ""
                row.update(outcome=outcome, note=note, wall_end=dt.datetime.now().isoformat(timespec="seconds"))
                if snapshot_log(a.server_dir, log_path):
                    row["log_file"] = os.path.relpath(log_path, out_dir)
                write_row(w, mf, row)
                bo.log(f"{tag}: {outcome} {note}")
                if outcome == "crashed-down":
                    return 3
                continue
            row["cfg_hash_start"] = config_hash(drv, target)
            before = ramp_csvs(a.server_dir)
            drv.ramp_t0 = None
            watcher = LogWatcher(os.path.join(a.server_dir, "logs", "latest.log"), target, a.log_poll_s, tag)
            watcher.start()
            swaps0 = poller.swaps
            poller.label = tag
            outcome, last = drv.run_ramp(target, rates, secs)
            row["ramp_t1_epoch"] = f"{time.time():.3f}"
            poller.label = ""
            watcher.stop()
            row["swaps"] = poller.swaps - swaps0 if poller.swaps >= 0 and swaps0 >= 0 else ""
            row.update(live_chunk_async=watcher.counts["chunk_async"], live_watchdog=watcher.counts["watchdog"],
                       live_first_error=watcher.first_line)
            if row["swaps"]:
                bo.log(f"{tag}: swarm replaced {row['swaps']} stuck bot(s) during the run")
            row["ramp_t0_epoch"] = f"{drv.ramp_t0:.3f}" if drv.ramp_t0 else ""
            new = sorted(ramp_csvs(a.server_dir) - before)
            if new:
                row["run_stamp"] = os.path.basename(new[-1])[: -len("-ramp.csv")]
                copied = copy_run_files(a.server_dir, row["run_stamp"], runs_dir)
                if "-phases.csv" not in copied:
                    row["note"] = "no phases.csv (partial run)"
            elif outcome != "not-started":
                row["note"] = "no new ramp.csv on the server"
            row["cfg_hash_end"] = config_hash(drv, target)
            if snapshot_log(a.server_dir, log_path):
                row["log_file"] = os.path.relpath(log_path, out_dir)
            if outcome.startswith("crashed") and snapshot_previous_log(a.server_dir, log_path[:-4] + ".prev.log"):
                row["note"] = (row.get("note", "") + " crashed session log: " + f"logs/{tag}.prev.log").strip()
            row.update(outcome=outcome, last_status=last, wall_end=dt.datetime.now().isoformat(timespec="seconds"))
            write_row(w, mf, row)
            bo.log(f"{tag}: {outcome} | stamp={row.get('run_stamp', '-')} | {last}")
            if outcome == "crashed-down":
                bo.log("server did not come back; stopping")
                return 3
    return 0


def main(argv: list[str] | None = None) -> int:
    p = make_parser()
    a = p.parse_args(argv)
    prof = PROFILES[a.profile]
    a.isolate = True
    props = bo.read_properties(a.server_dir)
    port = a.port or int(props.get("rcon.port", "25575"))
    password = a.password or props.get("rcon.password", "")
    if not a.dry_run and not (port and password):
        p.error("need rcon.port and rcon.password (server.properties) or --port/--password")
    if not a.dry_run and not a.swarm_stats:
        p.error("--swarm-stats is required for a live run (start bench-swarm.js with --stats-file)")
    if props.get("enable-rcon", "false").lower() != "true":
        bo.log("warning: server.properties has enable-rcon=false (takes effect after a restart)")
    if a.ramp_idle_s is None or a.ramp_warmup_s is None:
        idle, warm = read_ramp_cfg(a.server_dir)
        a.ramp_idle_s = idle if a.ramp_idle_s is None else a.ramp_idle_s
        a.ramp_warmup_s = warm if a.ramp_warmup_s is None else a.ramp_warmup_s

    targets = [t.strip() for t in (a.targets or prof["targets"]).split(",") if t.strip()]
    rounds = [bo.parse_round(r) for r in (a.rounds or prof["rounds"])]
    unsupported = [t.lower() for t in prof["unsupported"]]
    plan = build_plan(targets, rounds, max(1, a.passes))

    stamp = f"{dt.datetime.now():%Y%m%d-%H%M%S}"
    default_out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp", f"matrix-{stamp}")
    out_dir = a.resume or a.out_dir or default_out
    drv = MatrixDriver(a, a.host, port, password)

    total = sum(estimate_s(drv, a, rounds, ri) for _p, ri, _t in plan)
    if not a.dry_run:
        os.makedirs(out_dir, exist_ok=True)
        bo.LOG_FH = open(os.path.join(out_dir, "matrix.log"), "a", encoding="utf-8")
    bo.log(f"plan: profile={a.profile} targets={targets} rounds={rounds} passes={a.passes} unsupported={unsupported}")
    for pass_no, ri, t in plan:
        rates, secs = rounds[ri - 1]
        found = drv.jar_files(drv.jars.get(t.lower(), "\0")) if t.lower() in drv.jars else []
        what = ("control: all RTP jars off" if t.lower() not in drv.jars else f"jar {found or 'NO JAR'}")
        bo.log(f"  pass {pass_no} round {ri}: {t:<10} {rates}@{secs}s ~{estimate_s(drv, a, rounds, ri) / 60:.0f} min max ({what})")
    bo.log(f"estimated total ~{total / 3600:.1f} h (upper bound; restart={a.restart_estimate_s}s, n=1 per target per pass)")
    bo.log(f"free space in server dir: {bo.free_gb(a.server_dir):.0f} GB (stop below {a.min_free_gb:.0f} GB)")
    if a.dry_run:
        bo.log("dry run: no RCON traffic, nothing written")
        return 0

    fails = preflight(a, drv, targets, unsupported)
    if fails:
        bo.log(f"preflight failed: {fails}; aborting before any restart")
        return 2

    poller = BandwidthPoller(a.swarm_stats, os.path.join(out_dir, "bandwidth.csv"), a.bw_poll_s, a.swarm_stale_s)
    poller.start()
    rc = 1
    try:
        rc = run_matrix(a, drv, poller, out_dir, plan, rounds, unsupported)
    finally:
        poller.stop()
        drv.stop_ramp()
        bo.log("restoring all plugin jars (takes effect on next start)")
        drv.set_jars(None)
    try:
        import bench_matrix_report as report
    except ImportError:
        bo.log("bench_matrix_report not found; run it by hand on the out dir")
    else:
        try:
            report.main([out_dir])
        except Exception as e:  # report must never hide a finished run
            bo.log(f"report failed: {e!r}; rerun bench_matrix_report.py {out_dir}")
    bo.log(f"results in {out_dir}")
    return rc


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        bo.log("interrupted")
        sys.exit(130)
