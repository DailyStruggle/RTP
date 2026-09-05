#!/usr/bin/env python3
"""Read-only analyzer for MemoryShape ``.bin`` spatial-memory files.

Reports the on-disk split between the bad-location table and the biome table,
the coalescing rate (average run width), and what a varint delta encoding of
the biome section would cost. Stdlib only; never writes to the inspected file.

Layout (see MemoryShape.save / MemoryShape.load):
  v>=2: magic(4) version(4) worldLen(4) world stride(8) badCount(4)
        badCount * (key 8 + delta 8 [+ cause 1 if v>=2] [+ expiresAt 8 if v>=3])
        biomeCount(4) then per biome: nameLen(4) name count(4) count*(key 8 + delta 8)
  v1:   worldLen(4) world stride(8) badCount(4) badCount*(key 8 + delta 8) then biomes

Usage:
  python scripts/analyze_memoryshape_bin.py <file-or-dir> [...]
  python scripts/analyze_memoryshape_bin.py --simulate [biomeCount]
  python scripts/analyze_memoryshape_bin.py --project
"""

import math
import os
import struct
import sys

BIN_MAGIC = 0x52545031


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def remaining(self):
        return len(self.data) - self.pos

    def i32(self):
        (v,) = struct.unpack_from(">i", self.data, self.pos)
        self.pos += 4
        return v

    def i64(self):
        (v,) = struct.unpack_from(">q", self.data, self.pos)
        self.pos += 8
        return v

    def u8(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def raw(self, n):
        v = self.data[self.pos:self.pos + n]
        self.pos += n
        return v


def varint_len(value):
    """Bytes needed by an unsigned LEB128 varint."""
    if value < 0:
        raise ValueError("negative value passed to unsigned varint: %d" % value)
    n = 1
    value >>= 7
    while value:
        n += 1
        value >>= 7
    return n


def parse(path):
    with open(path, "rb") as fh:
        data = fh.read()
    if len(data) < 4:
        return None

    r = Reader(data)
    first = r.i32()
    if first == BIN_MAGIC:
        version = r.i32()
        w_len = r.i32()
    else:
        version = 1
        w_len = first

    world = r.raw(w_len).decode("utf-8", "replace")
    stride = r.i64()
    bad_count = r.i32()

    bad_entry_width = 25 if version >= 3 else (17 if version >= 2 else 16)
    bad_bytes = bad_count * bad_entry_width

    bad_keys = []
    bad_widths = []
    for _ in range(bad_count):
        bad_keys.append(r.i64())
        bad_widths.append(r.i64())
        if version >= 2:
            r.u8()
        if version >= 3:
            r.i64()

    biome_start = r.pos
    biomes = {}
    if r.remaining() >= 4:
        biome_count = r.i32()
        for _ in range(biome_count):
            n_len = r.i32()
            name = r.raw(n_len).decode("utf-8", "replace")
            count = r.i32()
            keys = []
            widths = []
            for _ in range(count):
                keys.append(r.i64())
                widths.append(r.i64())
            biomes[name] = (keys, widths)
    biome_bytes = r.pos - biome_start

    return {
        "path": path,
        "size": len(data),
        "version": version,
        "world": world,
        "stride": stride,
        "bad_count": bad_count,
        "bad_bytes": bad_bytes,
        "bad_width_total": sum(bad_widths),
        "bad_keys": bad_keys,
        "biomes": biomes,
        "biome_bytes": biome_bytes,
    }


def unified_varint_bytes(biomes):
    """Cost of one sorted run table with a parallel biome id, varint encoded.

    Runs from every biome are merged into a single ascending stream; each run
    emits varint(keyDelta) + varint(width) + varint(biomeId).
    """
    runs = []
    for bid, name in enumerate(sorted(biomes)):
        keys, widths = biomes[name]
        for k, w in zip(keys, widths):
            runs.append((k, w, bid))
    runs.sort(key=lambda t: t[0])

    total = 0
    prev = 0
    deltas = []
    for key, width, bid in runs:
        delta = key - prev
        deltas.append(delta)
        total += varint_len(delta if delta >= 0 else 0)
        total += varint_len(max(0, width))
        total += varint_len(bid)
        prev = key
    # name table: varint count + per name (varint len + utf8 bytes)
    total += varint_len(len(biomes))
    for name in biomes:
        nb = name.encode("utf-8")
        total += varint_len(len(nb)) + len(nb)
    return total, len(runs), deltas


def report(info):
    biomes = info["biomes"]
    biome_runs = sum(len(v[0]) for v in biomes.values())
    biome_width = sum(sum(v[1]) for v in biomes.values())

    print("=" * 78)
    print("file        : %s" % info["path"])
    print("size        : %d bytes   version %d   world '%s'   stride %d"
          % (info["size"], info["version"], info["world"], info["stride"]))
    print("bad runs    : %d  (%d bytes, %.1f%% of file)  total width %d"
          % (info["bad_count"], info["bad_bytes"],
             100.0 * info["bad_bytes"] / max(1, info["size"]),
             info["bad_width_total"]))
    if info["bad_count"]:
        print("              avg run width %.2f cells"
              % (info["bad_width_total"] / info["bad_count"]))
    print("biomes      : %d distinct, %d runs  (%d bytes, %.1f%% of file)  total width %d"
          % (len(biomes), biome_runs, info["biome_bytes"],
             100.0 * info["biome_bytes"] / max(1, info["size"]), biome_width))
    if biome_runs:
        print("              avg run width %.2f cells   %.1f bytes/run"
              % (biome_width / biome_runs, info["biome_bytes"] / biome_runs))
        print("              runs per biome: min %d  max %d  mean %.1f"
              % (min(len(v[0]) for v in biomes.values()),
                 max(len(v[0]) for v in biomes.values()),
                 biome_runs / len(biomes)))

    if info["bad_count"] and biome_runs:
        print("biome/bad   : %.2fx runs, %.2fx bytes"
              % (biome_runs / info["bad_count"],
                 info["biome_bytes"] / max(1, info["bad_bytes"])))

    if biome_runs:
        packed, runs, deltas = unified_varint_bytes(biomes)
        print("unified+varint biome section: %d bytes (%.2f B/run) vs %d now -> %.2fx smaller"
              % (packed, packed / runs, info["biome_bytes"],
                 info["biome_bytes"] / max(1, packed)))
        pos = [d for d in deltas if d >= 0]
        if pos:
            pos.sort()
            print("              key deltas: p50 %d  p90 %d  max %d"
                  % (pos[len(pos) // 2], pos[int(len(pos) * 0.9)], pos[-1]))
        max_key = max(max(v[0]) for v in biomes.values() if v[0])
        print("              max biome key %d  (int32 headroom %.2fx)"
              % (max_key, (2 ** 31 - 1) / max(1, max_key)))
        # Heap: every run is stored twice today (own biome table + union), 16 B each.
        print("              heap now ~%d B (per-biome %d + union) vs unified ~%d B"
              % (biome_runs * 32, biome_runs * 16, biome_runs * 18))


def ring_walk(cell_radius):
    """Yield (x, z) cells in ascending 1D-location order.

    Square.xzToLocation maps to concentric square rings
    (``location = 4*(R^2 - cr^2) + perimeterStep``), walked octant by octant, so
    consecutive 1D locations are neighbours along one ring perimeter. Ring R
    contributes 8R cells, and the rings sum to the area - which is why run count
    tracks area rather than biome boundary length.
    """
    yield 0, 0
    for r in range(1, cell_radius + 1):
        for z in range(0, r):                # oct 1: x=r, z 0..r-1
            yield r, z
        for x in range(r, -r, -1):           # oct 2/3: z=r
            yield x, r
        for z in range(r, -r, -1):           # oct 4/5: x=-r
            yield -r, z
        for x in range(-r, r, 1):            # oct 6/7: z=-r
            yield x, -r
        for z in range(-r, 0):               # oct 8: x=r, back to z=0
            yield r, z


class VoronoiBiomes:
    """Jittered-grid Voronoi biome field: one random site per patch_scale cell."""

    def __init__(self, patch_scale, biome_count, seed=20260904):
        self.s = patch_scale
        self.n = biome_count
        self.seed = seed

    def _site(self, gx, gz):
        h = (gx * 0x9E3779B1) ^ (gz * 0x85EBCA77) ^ self.seed
        h &= 0xFFFFFFFF
        h = (h ^ (h >> 15)) * 0x2C1B3C6D & 0xFFFFFFFF
        h = (h ^ (h >> 12)) * 0x297A2D39 & 0xFFFFFFFF
        h ^= h >> 15
        jx = (h & 0xFF) / 255.0
        jz = ((h >> 8) & 0xFF) / 255.0
        bid = (h >> 16) % self.n
        return (gx + jx) * self.s, (gz + jz) * self.s, bid

    def at(self, x, z):
        gx = x // self.s
        gz = z // self.s
        best = None
        best_d = None
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                sx, sz, bid = self._site(gx + dx, gz + dz)
                d = (sx - x) ** 2 + (sz - z) ** 2
                if best_d is None or d < best_d:
                    best_d = d
                    best = bid
        return best


def simulate(cell_radius, patch_cells, biome_count):
    """Count biome runs produced by walking the real 1D order over a Voronoi field.

    A run breaks whenever the walk enters a different biome, exactly as
    flushAndRebuild coalesces only 1D-adjacent same-biome observations.
    """
    field = VoronoiBiomes(patch_cells, biome_count)
    runs = 0
    cells = 0
    prev_bid = None
    run_starts = []
    loc = 0
    for x, z in ring_walk(cell_radius):
        bid = field.at(x, z)
        cells += 1
        if bid != prev_bid:
            runs += 1
            run_starts.append((loc, bid))
            prev_bid = bid
        loc += 1
    return runs, cells, run_starts


def varint_cost(run_starts):
    total = 0
    prev = 0
    deltas = []
    for i, (loc, bid) in enumerate(run_starts):
        delta = loc - prev
        deltas.append(delta)
        width = (run_starts[i + 1][0] - loc) if i + 1 < len(run_starts) else 1
        total += varint_len(delta) + varint_len(width) + varint_len(bid)
        prev = loc
    return total, deltas


def run_simulation(biome_count):
    print("=" * 78)
    print("SIMULATED growth: real Square ring-walk order over a jittered-Voronoi")
    print("biome field. patch_cells = mean biome patch width in *cells* (cell =")
    print("spatialResolution blocks). %d biomes." % biome_count)
    print("")
    header = ("cell_radius", "cells", "runs", "runs/cell", "avg_width",
              "B/run(now)", "B/run(varint)")
    print("%12s %12s %12s %10s %10s %11s %14s" % header)

    prev = None
    for cell_radius in (128, 256, 512, 1024):
        runs, cells, starts = simulate(cell_radius, 16, biome_count)
        packed, _deltas = varint_cost(starts)
        print("%12d %12d %12d %10.4f %10.2f %11d %14.2f"
              % (cell_radius, cells, runs, runs / cells, cells / runs, 16,
                 packed / runs))
        if prev is not None:
            pr, pc = prev
            exp_runs = math.log(runs / pr) / math.log(2.0)
            print("%12s growth vs previous: cells %.2fx, runs %.2fx -> run-count "
                  "exponent %.2f (2.00 = area, 1.00 = perimeter)"
                  % ("", cells / pc, runs / pr, exp_runs))
        prev = (runs, cells)
    print("")
    print("Note: exponent ~2 means run count tracks AREA, so bytes/run is the only")
    print("lever besides spatialResolution - a constant factor on a quadratic term.")


def project():
    """Project biome-table size from the measured constants.

    Simulation gives ``avg_run_width ~= WIDTH_FACTOR * patch_cells`` independent of
    radius, so::

        cells = (2 * radius / res)^2
        runs  = cells / (WIDTH_FACTOR * patch_blocks / res)
              = 4 * radius^2 / (WIDTH_FACTOR * patch_blocks * res)

    Run count is therefore quadratic in radius but only *linear* in resolution -
    coarsening the grid helps half as much as shrinking the radius.
    """
    width_factor = 0.852     # measured: 13.63 avg width / 16 patch cells
    varint_b = 3.0           # measured bytes/run for unified varint layout
    now_disk_b = 16.0        # key 8 + delta 8
    now_heap_b = 32.0        # stored twice: per-biome table + union table
    new_heap_b = 18.0        # unified long keys + long sums + short id

    print("=" * 78)
    print("PROJECTED biome-table size (patch = mean biome patch width in blocks)")
    print("runs = 4*radius^2 / (%.3f * patch * res)" % width_factor)
    print("")
    print("%8s %5s %7s %14s %11s %11s %11s %11s"
          % ("radius", "res", "patch", "runs", "disk now", "disk varint",
             "heap now", "heap unified"))
    for radius in (4096, 16384):
        for res in (1, 16, 64):
            for patch in (256,):
                runs = 4.0 * radius * radius / (width_factor * patch * res)
                print("%8d %5d %7d %14s %11s %11s %11s %11s"
                      % (radius, res, patch, human_count(runs),
                         human_bytes(runs * now_disk_b),
                         human_bytes(runs * varint_b),
                         human_bytes(runs * now_heap_b),
                         human_bytes(runs * new_heap_b)))
    print("")
    print("Radius 4096 -> 16384 is 16x the area, so 16x the runs at fixed res.")
    print("int32 keys overflow once radius/res > 23170, so a narrowed key array")
    print("would cap the 1-block-resolution case that is already the largest.")


def human_count(n):
    for unit, div in (("G", 1e9), ("M", 1e6), ("k", 1e3)):
        if n >= div:
            return "%.2f%s" % (n / div, unit)
    return "%.0f" % n


def human_bytes(n):
    for unit, div in (("GiB", 1024 ** 3), ("MiB", 1024 ** 2), ("KiB", 1024)):
        if n >= div:
            return "%.2f %s" % (n / div, unit)
    return "%.0f B" % n


def collect(targets):
    files = []
    for t in targets:
        if os.path.isdir(t):
            for root, _dirs, names in os.walk(t):
                for n in names:
                    if n.endswith(".bin"):
                        files.append(os.path.join(root, n))
        else:
            files.append(t)
    return files


def main(argv):
    targets = argv[1:]
    if not targets:
        print(__doc__)
        return 2
    if targets[0] == "--simulate":
        biome_count = int(targets[1]) if len(targets) > 1 else 16
        run_simulation(biome_count)
        project()
        return 0
    if targets[0] == "--project":
        project()
        return 0
    files = collect(targets)
    if not files:
        print("no .bin files found")
        return 1
    for path in sorted(files):
        try:
            info = parse(path)
        except Exception as exc:  # noqa: BLE001 - diagnostic tool
            print("=" * 78)
            print("file        : %s" % path)
            print("PARSE FAILED: %s: %s" % (type(exc).__name__, exc))
            continue
        if info is None:
            print("=" * 78)
            print("file        : %s (empty/truncated)" % path)
            continue
        report(info)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
