#!/usr/bin/env python3
"""
spark_summary.py — Offline summariser for spark `.sparkprofile` files.

Reads a profile produced by `/spark profiler --output ...` (or equivalent),
extracts the fields useful for RTP stress-test write-ups (per-window MSPT,
TPS, CPU, plus aggregated rolling MSPT and the top per-thread CPU shares)
and emits a compact JSON document suitable for AI analysis or pasting
into a markdown table.

Self-contained: parses the protobuf wire format directly, no protoc / no
`protobuf` package required. Schema mirrored from
    https://github.com/lucko/spark/tree/master/spark-common/src/main/proto/spark
as of 2026-05.

Usage
-----
    python spark_summary.py <file.sparkprofile> [--label <phase_label>]
                                                [--json <out.json>]
                                                [--top-threads N]

Notes
-----
* `times[]` on ThreadNode is "sample-count weighted to ms" per time-window;
  the script sums across windows and reports total cpu-ms per thread (which
  is what spark's UI "thread CPU" panel shows). Sampling interval comes from
  `SamplerMetadata.interval` (microseconds).
* `WindowStatistics.cpu_process / cpu_system` are unitless ratios (0.0–1.0).
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import os
import struct
import sys
from typing import Any


# ---------------------------------------------------------------------------
# Tiny protobuf wire-format reader.
#
# We only implement what we need: varint, fixed64, fixed32, length-delimited,
# and skipping unknown fields. Repeated/packed handled by callers.
# ---------------------------------------------------------------------------

WIRE_VARINT = 0
WIRE_FIXED64 = 1
WIRE_LDELIM = 2
WIRE_FIXED32 = 5


def _read_varint(buf: memoryview, pos: int) -> tuple[int, int]:
    shift = 0
    result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def _zigzag_decode(n: int) -> int:
    return (n >> 1) ^ -(n & 1)


def _read_tag(buf, pos):
    tag, pos = _read_varint(buf, pos)
    return tag >> 3, tag & 0x7, pos


def _skip(buf, pos, wire):
    if wire == WIRE_VARINT:
        _, pos = _read_varint(buf, pos)
    elif wire == WIRE_FIXED64:
        pos += 8
    elif wire == WIRE_LDELIM:
        n, pos = _read_varint(buf, pos)
        pos += n
    elif wire == WIRE_FIXED32:
        pos += 4
    else:
        raise ValueError(f"unsupported wire type {wire}")
    return pos


def parse_message(data: bytes | memoryview, schema: dict[int, tuple[str, str]]) -> dict:
    """
    Parse a protobuf message given a {field_no: (name, kind)} schema.

    `kind` is one of:
      'varint', 'sint32', 'int32', 'int64', 'bool',
      'double', 'float', 'fixed32', 'fixed64',
      'string', 'bytes', 'submsg:<schema_name>', 'packed_double', 'packed_int32',
      'repeated_submsg:<schema_name>', 'repeated_string',
      'map:<key_kind>:<value_kind_or_submsg>'

    Returns a dict; repeated fields collected as lists. Unknown fields skipped.
    """
    if isinstance(data, (bytes, bytearray)):
        buf = memoryview(data)
    else:
        buf = data
    out: dict[str, Any] = {}
    pos = 0
    end = len(buf)
    while pos < end:
        field_no, wire, pos = _read_tag(buf, pos)
        spec = schema.get(field_no)
        if spec is None:
            pos = _skip(buf, pos, wire)
            continue
        name, kind = spec
        if kind == "varint" or kind == "int32" or kind == "int64" or kind == "bool":
            v, pos = _read_varint(buf, pos)
            if kind == "bool":
                v = bool(v)
            elif kind == "int32":
                # sign-extend if needed (proto3 int32 is encoded as 64-bit varint).
                v = v - (1 << 64) if v >= (1 << 63) else v
                if v > 0x7FFFFFFF: v -= (1 << 32)
            out[name] = v
        elif kind == "sint32":
            v, pos = _read_varint(buf, pos)
            out[name] = _zigzag_decode(v)
        elif kind == "double":
            out[name] = struct.unpack_from("<d", buf, pos)[0]
            pos += 8
        elif kind == "float":
            out[name] = struct.unpack_from("<f", buf, pos)[0]
            pos += 4
        elif kind == "fixed64":
            out[name] = struct.unpack_from("<Q", buf, pos)[0]
            pos += 8
        elif kind == "fixed32":
            out[name] = struct.unpack_from("<I", buf, pos)[0]
            pos += 4
        elif kind == "string":
            n, pos = _read_varint(buf, pos)
            out[name] = bytes(buf[pos:pos + n]).decode("utf-8", errors="replace")
            pos += n
        elif kind == "bytes":
            n, pos = _read_varint(buf, pos)
            out[name] = bytes(buf[pos:pos + n])
            pos += n
        elif kind.startswith("submsg:"):
            sub_name = kind.split(":", 1)[1]
            n, pos = _read_varint(buf, pos)
            sub = parse_message(buf[pos:pos + n], SCHEMAS[sub_name])
            out[name] = sub
            pos += n
        elif kind.startswith("repeated_submsg:"):
            sub_name = kind.split(":", 1)[1]
            n, pos = _read_varint(buf, pos)
            sub = parse_message(buf[pos:pos + n], SCHEMAS[sub_name])
            out.setdefault(name, []).append(sub)
            pos += n
        elif kind == "repeated_string":
            n, pos = _read_varint(buf, pos)
            out.setdefault(name, []).append(
                bytes(buf[pos:pos + n]).decode("utf-8", errors="replace"))
            pos += n
        elif kind == "packed_double":
            # Could be packed (LDELIM) or repeated unpacked.
            if wire == WIRE_LDELIM:
                n, pos = _read_varint(buf, pos)
                lst = out.setdefault(name, [])
                end_p = pos + n
                while pos < end_p:
                    lst.append(struct.unpack_from("<d", buf, pos)[0])
                    pos += 8
            else:
                out.setdefault(name, []).append(struct.unpack_from("<d", buf, pos)[0])
                pos += 8
        elif kind == "packed_int32":
            if wire == WIRE_LDELIM:
                n, pos = _read_varint(buf, pos)
                lst = out.setdefault(name, [])
                end_p = pos + n
                while pos < end_p:
                    v, pos = _read_varint(buf, pos)
                    if v > 0x7FFFFFFF: v -= (1 << 32)
                    lst.append(v)
            else:
                v, pos = _read_varint(buf, pos)
                if v > 0x7FFFFFFF: v -= (1 << 32)
                out.setdefault(name, []).append(v)
        elif kind.startswith("map:"):
            # map<K,V> is encoded as repeated submsg{key=1, value=2}.
            _, key_kind, val_kind = kind.split(":", 2)
            n, pos = _read_varint(buf, pos)
            entry_schema = {
                1: ("key", key_kind),
                2: ("value", val_kind),
            }
            entry = parse_message(buf[pos:pos + n], entry_schema)
            pos += n
            d = out.setdefault(name, {})
            d[entry.get("key")] = entry.get("value")
        else:
            raise AssertionError(f"unknown kind {kind}")
    return out


# ---------------------------------------------------------------------------
# Schemas (only the fields we read, mirrored from spark.proto / spark_sampler.proto)
# ---------------------------------------------------------------------------

SCHEMAS: dict[int, dict[int, tuple[str, str]]] = {}

SCHEMAS["RollingAverageValues"] = {
    1: ("mean", "double"),
    2: ("max", "double"),
    3: ("min", "double"),
    4: ("median", "double"),
    5: ("percentile95", "double"),
}

SCHEMAS["Tps"] = {
    1: ("last1m", "double"),
    2: ("last5m", "double"),
    3: ("last15m", "double"),
    4: ("game_target_tps", "int32"),
}

SCHEMAS["Mspt"] = {
    1: ("last1m", "submsg:RollingAverageValues"),
    2: ("last5m", "submsg:RollingAverageValues"),
    3: ("game_max_ideal_mspt", "int32"),
}

SCHEMAS["PlatformStatistics"] = {
    3: ("uptime", "int64"),
    4: ("tps", "submsg:Tps"),
    5: ("mspt", "submsg:Mspt"),
    7: ("player_count", "int64"),
    9: ("online_mode", "varint"),
}

SCHEMAS["PlatformMetadata"] = {
    1: ("type", "varint"),
    2: ("name", "string"),
    3: ("version", "string"),
    4: ("minecraft_version", "string"),
    7: ("spark_version", "int32"),
    8: ("brand", "string"),
}

SCHEMAS["WindowStatistics"] = {
    1:  ("ticks", "int32"),
    2:  ("cpu_process", "double"),
    3:  ("cpu_system", "double"),
    4:  ("tps", "double"),
    5:  ("mspt_median", "double"),
    6:  ("mspt_max", "double"),
    7:  ("players", "int32"),
    8:  ("entities", "int32"),
    9:  ("tile_entities", "int32"),
    10: ("chunks", "int32"),
    11: ("start_time", "int64"),
    12: ("end_time", "int64"),
    13: ("duration", "int32"),
}

SCHEMAS["SamplerMetadata"] = {
    2:  ("start_time", "int64"),
    3:  ("interval", "int32"),
    7:  ("platform_metadata", "submsg:PlatformMetadata"),
    8:  ("platform_statistics", "submsg:PlatformStatistics"),
    11: ("end_time", "int64"),
    12: ("number_of_ticks", "int32"),
    15: ("sampler_mode", "varint"),
    16: ("sampler_engine", "varint"),
}

SCHEMAS["StackTraceNode"] = {
    3: ("class_name", "string"),
    4: ("method_name", "string"),
    8: ("times", "packed_double"),
    9: ("children_refs", "packed_int32"),
}

SCHEMAS["ThreadNode"] = {
    1: ("name", "string"),
    3: ("children", "repeated_submsg:StackTraceNode"),
    4: ("times", "packed_double"),
    5: ("children_refs", "packed_int32"),
}

SCHEMAS["SamplerData"] = {
    1: ("metadata", "submsg:SamplerMetadata"),
    2: ("threads", "repeated_submsg:ThreadNode"),
    6: ("time_windows", "packed_int32"),
    7: ("time_window_statistics", "map:int32:submsg:WindowStatistics"),
}


# ---------------------------------------------------------------------------
# Summarisation
# ---------------------------------------------------------------------------

def _percentile(sorted_vals: list[float], q: float) -> float | None:
    if not sorted_vals:
        return None
    i = max(0, min(len(sorted_vals) - 1, int(round(q * (len(sorted_vals) - 1)))))
    return sorted_vals[i]


def summarise(msg: dict, label: str | None, top_threads: int = 8) -> dict:
    md = msg.get("metadata") or {}
    plat = md.get("platform_metadata") or {}
    pstats = md.get("platform_statistics") or {}
    mspt = pstats.get("mspt") or {}
    tps = pstats.get("tps") or {}

    # Per-thread total cpu-time across all windows.
    by_thread: dict[str, float] = {}
    for t in msg.get("threads") or []:
        name = t.get("name") or "?"
        total = sum(t.get("times") or [])
        by_thread[name] = by_thread.get(name, 0.0) + total
    total_cpu = sum(by_thread.values()) or 1.0
    top = sorted(by_thread.items(), key=lambda kv: -kv[1])[:top_threads]

    # Per-window stats — pull mspt_median/max + tps + cpu_process across windows.
    windows = msg.get("time_window_statistics") or {}
    window_rows = []
    mspt_medians = []
    mspt_maxes = []
    tps_vals = []
    cpu_proc = []
    for wid, w in sorted(windows.items()):
        w = w or {}
        window_rows.append({
            "window": wid,
            "duration_s": w.get("duration"),
            "tps": w.get("tps"),
            "mspt_median": w.get("mspt_median"),
            "mspt_max": w.get("mspt_max"),
            "cpu_process": w.get("cpu_process"),
            "cpu_system": w.get("cpu_system"),
            "players": w.get("players"),
            "entities": w.get("entities"),
            "chunks": w.get("chunks"),
        })
        if w.get("mspt_median") is not None: mspt_medians.append(w["mspt_median"])
        if w.get("mspt_max") is not None:    mspt_maxes.append(w["mspt_max"])
        if w.get("tps") is not None:         tps_vals.append(w["tps"])
        if w.get("cpu_process") is not None: cpu_proc.append(w["cpu_process"])

    mspt_medians.sort(); mspt_maxes.sort(); tps_vals.sort(); cpu_proc.sort()

    out = {
        "label": label,
        "platform": {
            "name": plat.get("name"),
            "version": plat.get("version"),
            "minecraft_version": plat.get("minecraft_version"),
            "brand": plat.get("brand"),
        },
        "capture": {
            "start_epoch_ms": md.get("start_time"),
            "end_epoch_ms": md.get("end_time"),
            "duration_s": (md.get("end_time", 0) - md.get("start_time", 0)) / 1000.0
                if md.get("start_time") and md.get("end_time") else None,
            "ticks": md.get("number_of_ticks"),
            "interval_us": md.get("interval"),
        },
        # Spark's rolling MSPT (server-internal, last-1m / last-5m). These are
        # computed by the server, not the sampler — same numbers as `/tps` / `/mspt`.
        "mspt_rolling": {
            "last1m": mspt.get("last1m"),
            "last5m": mspt.get("last5m"),
            "game_max_ideal_mspt": mspt.get("game_max_ideal_mspt"),
        },
        "tps_rolling": {
            "last1m": tps.get("last1m"),
            "last5m": tps.get("last5m"),
            "last15m": tps.get("last15m"),
            "game_target_tps": tps.get("game_target_tps"),
        },
        # Per-tick-window aggregates from spark's WindowStatistics.
        # mspt_median is the median over the window; aggregating across windows
        # gives a per-phase distribution.
        "mspt_window_aggregate": {
            "windows": len(mspt_medians),
            "median_of_medians": _percentile(mspt_medians, 0.5),
            "p95_of_medians":    _percentile(mspt_medians, 0.95),
            "max_of_medians":    mspt_medians[-1] if mspt_medians else None,
            "max_of_maxes":      mspt_maxes[-1] if mspt_maxes else None,
        },
        "tps_window_aggregate": {
            "windows": len(tps_vals),
            "median": _percentile(tps_vals, 0.5),
            "p05":    _percentile(tps_vals, 0.05),
            "min":    tps_vals[0] if tps_vals else None,
        },
        "cpu_process_window_aggregate": {
            "windows": len(cpu_proc),
            "median": _percentile(cpu_proc, 0.5),
            "p95":    _percentile(cpu_proc, 0.95),
            "max":    cpu_proc[-1] if cpu_proc else None,
        },
        "thread_cpu_top": [
            {"thread": t, "weight": v, "share_pct": 100.0 * v / total_cpu}
            for t, v in top
        ],
        "windows": window_rows,
    }
    return out


def load(path: str) -> dict:
    with open(path, "rb") as f:
        raw = f.read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    return parse_message(raw, SCHEMAS["SamplerData"])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("file")
    ap.add_argument("--label", default=None,
                    help="phase label embedded in the output JSON")
    ap.add_argument("--json", default=None,
                    help="write JSON to this path (in addition to stdout)")
    ap.add_argument("--top-threads", type=int, default=8)
    ap.add_argument("--quiet", action="store_true",
                    help="suppress stdout (use with --json)")
    args = ap.parse_args(argv)

    msg = load(args.file)
    s = summarise(msg, args.label or os.path.basename(args.file), args.top_threads)
    payload = json.dumps(s, indent=2)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            f.write(payload)
    if not args.quiet:
        print(payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
