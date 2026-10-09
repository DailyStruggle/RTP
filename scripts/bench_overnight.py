#!/usr/bin/env python3
"""Unattended StressTestRTP ramp driver (stdlib only).

Drives `/rtpstress ramp <target> <stageSeconds> <rates>` over RCON for every
target in turn, restarting the server between targets (spigot.yml
restart-script) so no plugin inherits another's loaded chunks or heap.
Round order alternates forward/reverse to cancel order effects.

Each ramp writes its own <stamp>-ramp.csv / -ramp-summary.txt / -phases.csv on
the server; this script only sequences them and records a manifest
(scripts/tmp/overnight-<stamp>.csv) mapping round/target to wall-clock window
and outcome, plus a log beside it.

Server prerequisites: server.properties enable-rcon=true + rcon.password;
spigot.yml settings.restart-script pointing at a start script; the bot swarm
(devstack/clients/bench-swarm.js) running and auto-reconnecting.

Example:
  python scripts/bench_overnight.py --server-dir \\\\100.111.32.47\\UbuntuShare\\26.2 ^
      --host 100.111.32.47 --until 09:00
  python scripts/bench_overnight.py ... --dry-run
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import os
import re
import socket
import struct
import sys
import time

DEFAULT_TARGETS = "rtp,betterrtp,huskhomes,justrtp,jakesrtp,ezrtp"
# target label -> plugins/ jar filename prefix (case-insensitive). With isolation
# only the target's jar is enabled; the rest are renamed *.jar.off while the
# server is down, so no competitor's background work (pre-caches, queues) runs
# during another plugin's ramp.
DEFAULT_JARS = "rtp=LeafRTP,betterrtp=BetterRTP,huskhomes=HuskHomes,justrtp=justRTP,jakesrtp=JakesRTP,ezrtp=EzRTP"
OFF_SUFFIX = ".off"
DEFAULT_ROUNDS = ["100@120", "5,10,20,40,80,160,320@60", "100@120", "5,10,20,40,80,160,320@60"]

LOG_FH = None


def log(msg: str) -> None:
    line = f"{dt.datetime.now():%Y-%m-%d %H:%M:%S} {msg}"
    print(line, flush=True)
    if LOG_FH:
        LOG_FH.write(line + "\n")
        LOG_FH.flush()


# --- RCON (Source protocol) -------------------------------------------------

class RconError(Exception):
    pass


def _send(sock: socket.socket, req_id: int, ptype: int, body: str) -> None:
    payload = struct.pack("<ii", req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise RconError("connection closed")
        buf += chunk
    return buf


def _recv(sock: socket.socket) -> tuple[int, int, str]:
    (length,) = struct.unpack("<i", _recv_exact(sock, 4))
    if length < 10 or length > 1 << 20:
        raise RconError(f"bad packet length {length}")
    data = _recv_exact(sock, length)
    req_id, ptype = struct.unpack("<ii", data[:8])
    return req_id, ptype, data[8:-2].decode("utf-8", errors="replace")


def rcon(host: str, port: int, password: str, command: str, timeout: float = 30.0) -> str:
    """One connection per command: survives server restarts without state."""
    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            sock.settimeout(timeout)
            _send(sock, 1, 3, password)
            while True:
                rid, ptype, _ = _recv(sock)
                if rid == -1:
                    raise RconError("authentication failed (check rcon.password)")
                if rid == 1 and ptype == 2:
                    break
            _send(sock, 2, 2, command)
            # Marker: vanilla answers an unknown packet type in order, so every
            # fragment of the command reply arrives before the marker reply.
            _send(sock, 3, 0, "")
            parts = []
            while True:
                rid, _, body = _recv(sock)
                if rid == 3:
                    break
                if rid == 2:
                    parts.append(body)
            return "".join(parts)
    except (OSError, struct.error) as e:
        raise RconError(str(e)) from e


# --- driver -----------------------------------------------------------------

def strip_colors(s: str) -> str:
    return re.sub(r"\u00a7.", "", s)


def read_properties(server_dir: str) -> dict[str, str]:
    props = {}
    with open(os.path.join(server_dir, "server.properties"), encoding="utf-8") as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw or raw.startswith("#") or "=" not in raw:
                continue
            k, v = raw.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def parse_round(spec: str) -> tuple[str, int]:
    rates, _, secs = spec.partition("@")
    vals = [float(r) for r in rates.split(",") if r.strip()]
    if not vals or any(v <= 0 for v in vals):
        raise ValueError(f"bad rates in round '{spec}'")
    return ",".join(rates.replace(" ", "").split(",")), int(secs or 60)


class Driver:
    def __init__(self, a: argparse.Namespace, host: str, port: int, password: str):
        self.a, self.host, self.port, self.password = a, host, port, password
        self.jars: dict[str, str] = {}
        if a.isolate:
            for pair in a.jars.split(","):
                t, _, prefix = pair.partition("=")
                if t.strip() and prefix.strip():
                    self.jars[t.strip().lower()] = prefix.strip().lower()

    def plugins_dir(self) -> str:
        return os.path.join(self.a.server_dir, "plugins")

    def jar_files(self, prefix: str) -> list[str]:
        return [f for f in os.listdir(self.plugins_dir())
                if f.lower().startswith(prefix) and (f.lower().endswith(".jar") or f.lower().endswith(".jar" + OFF_SUFFIX))]

    def set_jars(self, active: str | None) -> bool:
        """Enable only `active`'s jar (None enables all). Server must be down. True if active's jar is on."""
        ok = active is None or active.lower() not in self.jars
        for t, prefix in self.jars.items():
            want_on = active is None or t == active.lower()
            for f in self.jar_files(prefix):
                is_on = f.lower().endswith(".jar")
                if is_on != want_on:
                    dst = f + OFF_SUFFIX if not want_on else f[: -len(OFF_SUFFIX)]
                    for attempt in range(12):
                        try:
                            os.replace(os.path.join(self.plugins_dir(), f), os.path.join(self.plugins_dir(), dst))
                            is_on = want_on
                            break
                        except OSError as e:
                            if attempt == 11:
                                log(f"jars: cannot rename {f} -> {dst}: {e}")
                            time.sleep(5)
                if want_on and is_on and active is not None:
                    ok = True
        on = sorted(f for p in self.jars.values() for f in self.jar_files(p) if f.lower().endswith(".jar"))
        log(f"jars: active={on}")
        return ok

    def cmd(self, command: str, timeout: float = 30.0) -> str:
        return strip_colors(rcon(self.host, self.port, self.password, command, timeout))

    def try_cmd(self, command: str) -> str | None:
        try:
            return self.cmd(command)
        except RconError:
            return None

    def wait_up(self, limit_s: float) -> bool:
        end = time.time() + limit_s
        while time.time() < end:
            if self.try_cmd("list") is not None:
                return True
            time.sleep(10)
        return False

    def wait_down(self, limit_s: float) -> bool:
        end = time.time() + limit_s
        while time.time() < end:
            if self.try_cmd("list") is None:
                return True
            time.sleep(5)
        return False

    def online(self) -> int:
        r = self.try_cmd("list")
        m = re.search(r"There are (\d+)", r or "")
        return int(m.group(1)) if m else -1

    def wait_players(self) -> int:
        end = time.time() + self.a.player_wait_s
        n = -1
        while time.time() < end:
            n = self.online()
            if n >= self.a.min_players:
                return n
            time.sleep(10)
        return n

    def restart(self, next_target: str | None = None) -> bool:
        log("restart: sending 'restart'")
        try:
            self.cmd("restart", timeout=15)
        except RconError:
            pass  # the connection often drops as the server stops
        if not self.wait_down(self.a.shutdown_wait_s):
            log(f"restart: server still answering after {self.a.shutdown_wait_s}s; continuing without restart")
            return False
        # RCON closes early in shutdown; the new JVM loads plugins minutes later.
        if self.jars and next_target is not None and not self.set_jars(next_target):
            log(f"restart: jar for {next_target} could not be enabled")
        log("restart: server down, waiting for it to come back")
        if not self.wait_up(self.a.startup_wait_s):
            log(f"restart: server did not return within {self.a.startup_wait_s}s")
            return False
        log("restart: server back")
        return True

    def targets_on_server(self) -> list[str] | None:
        r = self.cmd("rtpstress ramp __list_targets__")
        if "already in progress" in r:
            raise SystemExit("a StressTestRTP run is already in progress; stop it first")
        m = re.search(r"targets:\s*\[([^\]]*)\]", r)
        return [t.strip() for t in m.group(1).split(",") if t.strip()] if m else None

    def ramp_budget_s(self, rates: str, secs: int) -> int:
        n = len(rates.split(","))
        per_stage = secs + self.a.attempt_timeout_s + 1 + self.a.ramp_gap_s
        return self.a.ramp_idle_s + self.a.ramp_warmup_s + n * per_stage + self.a.margin_s

    def run_ramp(self, target: str, rates: str, secs: int) -> tuple[str, str]:
        n = self.wait_players()
        if n < self.a.min_players:
            log(f"{target}: only {n} players online after {self.a.player_wait_s}s (want {self.a.min_players}); running anyway")
        log(f"{target}: {n} players online; settling {self.a.settle_s}s")
        time.sleep(self.a.settle_s)
        start_cmd = f"rtpstress ramp {target} {secs} {rates}"
        r = self.try_cmd(start_cmd) or ""
        if "already in progress" in r:
            self.try_cmd("rtpstress stop")
            time.sleep(10)
            r = self.try_cmd(start_cmd) or ""
        log(f"{target}: > {start_cmd} | {r.strip()}")
        if "ramp started" not in r:
            return "not-started", r.strip()
        deadline = time.time() + self.ramp_budget_s(rates, secs)
        down_since = None
        last = ""
        while True:
            time.sleep(self.a.poll_s)
            s = self.try_cmd("rtpstress status")
            if s is None:
                down_since = down_since or time.time()
                if time.time() - down_since > 120:
                    log(f"{target}: server unreachable for 120s mid-ramp (crash?); waiting for it")
                    up = self.wait_up(self.a.startup_wait_s)
                    return ("crashed-recovered" if up else "crashed-down"), last
                continue
            down_since = None
            last = s.strip().replace("\n", " | ")
            if "running=false" in s or "no run yet" in s:
                return "finished", last
            if time.time() > deadline:
                log(f"{target}: over budget; sending stop")
                self.try_cmd("rtpstress stop")
                return "timed-out", last


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--server-dir", help="server root (reads rcon.port / rcon.password from server.properties)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int)
    p.add_argument("--password", default=os.environ.get("RCON_PASSWORD"))
    p.add_argument("--targets", default=DEFAULT_TARGETS, help="comma-separated target-commands labels")
    p.add_argument("--round", dest="rounds", action="append",
                   help="'rates@stageSeconds', repeatable; default: flat 100@120 and full ladder, twice each")
    p.add_argument("--until", help="HH:MM local; repeat the round list until then (no new ramp starts after it)")
    p.add_argument("--no-restart", action="store_true", help="do not restart between targets")
    p.add_argument("--no-isolate", dest="isolate", action="store_false",
                   help="keep every RTP plugin jar enabled for every ramp (default: only the target's)")
    p.add_argument("--jars", default=DEFAULT_JARS, help="target=jarPrefix pairs used by isolation")
    p.add_argument("--min-players", type=int, default=32)
    p.add_argument("--player-wait-s", type=int, default=600)
    p.add_argument("--settle-s", type=int, default=60)
    p.add_argument("--poll-s", type=int, default=20)
    p.add_argument("--shutdown-wait-s", type=int, default=1200, help="world save can take minutes")
    p.add_argument("--startup-wait-s", type=int, default=900)
    p.add_argument("--ramp-idle-s", type=int, default=30, help="ramp.idle-seconds, for the budget only")
    p.add_argument("--ramp-warmup-s", type=int, default=30, help="ramp.warmup-seconds, for the budget only")
    p.add_argument("--ramp-gap-s", type=int, default=5, help="ramp.gap-seconds, for the budget only")
    p.add_argument("--attempt-timeout-s", type=int, default=5, help="attempt-timeout-ms/1000, for the budget only")
    p.add_argument("--margin-s", type=int, default=300)
    p.add_argument("--out-dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp"))
    p.add_argument("--dry-run", action="store_true", help="print the plan and exit")
    a = p.parse_args()

    port, password = a.port, a.password
    if a.server_dir:
        props = read_properties(a.server_dir)
        if props.get("enable-rcon", "false").lower() != "true":
            log("warning: server.properties has enable-rcon=false (takes effect after a restart)")
        port = port or int(props.get("rcon.port", "25575"))
        password = password or props.get("rcon.password", "")
    if not port or not password:
        p.error("need --port and --password (or --server-dir with rcon settings)")
    if a.isolate and (not a.server_dir or a.no_restart):
        log("isolation needs --server-dir and restarts; disabled")
        a.isolate = False

    rounds = [parse_round(r) for r in (a.rounds or DEFAULT_ROUNDS)]
    targets = [t.strip() for t in a.targets.split(",") if t.strip()]
    until = None
    if a.until:
        hh, mm = (int(x) for x in a.until.split(":"))
        now = dt.datetime.now()
        until = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
        if until <= now:
            until += dt.timedelta(days=1)

    os.makedirs(a.out_dir, exist_ok=True)
    stamp = f"{dt.datetime.now():%Y%m%d-%H%M%S}"
    global LOG_FH
    LOG_FH = open(os.path.join(a.out_dir, f"overnight-{stamp}.log"), "a", encoding="utf-8")
    drv = Driver(a, a.host, port, password)

    restart_overhead = 0 if a.no_restart else 300
    one_pass = sum(len(targets) * (drv.ramp_budget_s(r, s) - a.margin_s + a.settle_s + restart_overhead)
                   for r, s in rounds)
    log(f"plan: targets={targets} rounds={rounds} restart={not a.no_restart} "
        f"until={until:%Y-%m-%d %H:%M}" if until else
        f"plan: targets={targets} rounds={rounds} restart={not a.no_restart} until=once")
    log(f"estimated one pass ~{one_pass / 3600:.1f} h (upper bound per ramp; ladders usually stop early)")
    if drv.jars:
        for t in targets:
            found = drv.jar_files(drv.jars.get(t.lower(), "\0"))
            log(f"isolate: {t} -> {found or 'NO JAR (ramp would run with no plugin)'}")
    if a.dry_run:
        return 0
    try:
        return run(a, drv, targets, rounds, until, stamp)
    finally:
        if drv.jars:
            log("restoring all plugin jars (takes effect on next start)")
            drv.set_jars(None)


def run(a, drv, targets, rounds, until, stamp) -> int:
    port = drv.port

    if not drv.wait_up(60):
        log(f"cannot reach RCON at {a.host}:{port}; is enable-rcon=true and the server restarted?")
        return 2
    on_server = drv.targets_on_server()
    if on_server is not None:
        missing = [t for t in targets if t.lower() not in {s.lower() for s in on_server}]
        if missing:
            log(f"skipping targets not in the server's target-commands: {missing}")
        targets = [t for t in targets if t not in missing]
    if not targets:
        log("no targets left")
        return 2

    manifest_path = os.path.join(a.out_dir, f"overnight-{stamp}.csv")
    with open(manifest_path, "w", newline="", encoding="utf-8") as mf:
        w = csv.writer(mf)
        w.writerow(["pass", "round", "order", "target", "rates", "stage_seconds", "start", "end",
                    "outcome", "restarted_before", "last_status"])
        mf.flush()
        # Clean slate before the first ramp and an early proof the restart path works.
        restarted = False if a.no_restart else drv.restart(targets[0])
        if not a.no_restart and not restarted:
            log("initial restart failed; continuing without restarts or isolation")
            a.no_restart = True
            if drv.jars and drv.try_cmd("list") is None:
                log("server is down after a failed restart; stopping")
                return 3
            drv.jars = {}
        pass_no = 0
        while True:
            pass_no += 1
            for ri, (rates, secs) in enumerate(rounds):
                order = targets if ri % 2 == 0 else list(reversed(targets))
                for ti, target in enumerate(order):
                    if until and dt.datetime.now() >= until:
                        log("reached --until; done")
                        return 0
                    if ti > 0 or ri > 0 or pass_no > 1:
                        restarted = False if a.no_restart else drv.restart(target)
                    log(f"pass {pass_no} round {ri + 1}/{len(rounds)} [{rates}@{secs}s] target {target}")
                    t0 = dt.datetime.now()
                    if drv.jars and not restarted:
                        # Previous target's jar set may still be live; its command may not exist.
                        outcome, last = "skipped-restart-failed", ""
                        if not drv.wait_up(drv.a.startup_wait_s):
                            outcome = "crashed-down"
                    else:
                        outcome, last = drv.run_ramp(target, rates, secs)
                    t1 = dt.datetime.now()
                    log(f"{target}: {outcome} | {last}")
                    w.writerow([pass_no, ri + 1, ti + 1, target, rates, secs,
                                t0.isoformat(timespec="seconds"), t1.isoformat(timespec="seconds"),
                                outcome, restarted, last])
                    mf.flush()
                    if outcome == "crashed-down":
                        log("server did not come back; stopping")
                        return 3
            if not until:
                log("all rounds done")
                return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log("interrupted")
        sys.exit(130)
