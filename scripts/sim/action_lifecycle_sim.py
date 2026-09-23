#!/usr/bin/env python3
"""Lifecycle simulation + chart renderer for the free-tier sample scripted actions.

Mirrors the runtime semantics of ActionManager.trigger(...) and ActionSessionImpl
(rtp-core, ADR-093 / ADR-095) for the six bundled sample actions:
  scatter, nearplayer, nearclaim, location, on-join, on-death.

For each action it:
  1. Replays the session state machine (trigger -> anchor -> subspace placement ->
     arm -> onStart -> tick/expire -> onExpire -> disarm) and records phase timings.
  2. Simulates subspace slot selection around the resolved anchor using the same
     annular-ring / square footprint math the engine uses.
Outputs PNG charts to scripts/sim/out/.

Stdlib + matplotlib only. Deterministic (fixed seed).
"""
import math
import os
import random

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Circle, Rectangle

SEED = 20260923
random.seed(SEED)

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

# --- Sample action definitions (mirrors rtp-plugin resources/definitions/actions/*) ---
# radius fields are in blocks. subspaceChunkRadius * 16 = footprint half-width (SUBSPACE bound).
ACTIONS = {
    "scatter": dict(
        anchor="regionQueue", profile="square", chunk_radius=4, min_sep=32,
        center_radius=0, participants=4, duration=0,
        onStart=["PLAYER: msg Teleported to the wild."],
        blurb="Plain /rtp + group scatter (square footprint, drawn from hot/cold cache)",
    ),
    "nearplayer": dict(
        anchor="entity", profile="circle", chunk_radius=6, min_sep=24,
        center_radius=16, participants=1, duration=0,
        onStart=["PLAYER: msg Whoosh! Dropped near a fellow traveler."],
        blurb="Land in a ring around a live target player (EntityAnchorSource)",
    ),
    "nearclaim": dict(
        anchor="claimHazard", profile="circle", chunk_radius=3, min_sep=16,
        center_radius=12, participants=1, duration=0,
        onStart=["PLAYER: msg Dropped just outside a claimed base."],
        blurb="Ring just outside a claim perimeter (safetyExternal recall, no foreign API)",
    ),
    "location": dict(
        anchor="fixed", profile="circle", chunk_radius=4, min_sep=16,
        center_radius=0, participants=1, duration=0,
        onStart=["PLAYER: msg Arrived near spawn."],
        blurb="Scatter around a fixed named landmark (CoordinateAnchorSource)",
    ),
    "on-join": dict(
        anchor="regionQueue", profile="square", chunk_radius=4, min_sep=16,
        center_radius=0, participants=1, duration=0,
        onStart=["PLAYER: msg Welcome! You've been dropped somewhere in the wild."],
        blurb="First-join RTP fired by the join event trigger",
    ),
    "on-death": dict(
        anchor="regionQueue", profile="square", chunk_radius=4, min_sep=16,
        center_radius=0, participants=1, duration=0,
        onStart=["PLAYER: msg Respawned in the wild - good luck out there."],
        blurb="Respawn RTP fired by the death/respawn event trigger",
    ),
}

# Anchor coordinate the resolver would produce (world blocks). regionQueue is drawn
# from a pre-warmed queue; we place a representative sample for the chart.
ANCHORS = {
    "regionQueue": (1840, -960),
    "entity":      (312, 208),
    "claimHazard": (-704, 1536),
    "fixed":       (0, 0),
}

# Lifecycle phase timings in microseconds, representative of off-tick arithmetic
# (benchmarks: ~6.3 us bin-accumulate 16-slot placement; anchor/lifecycle are sub-us).
PHASE_US = {
    "trigger/validate": 0.4,
    "anchor resolve":   0.6,
    "subspace place":   6.3,
    "arm watchers":     0.3,
    "onStart":          1.2,
    "tick/expire":      0.5,
    "onExpire+disarm":  0.8,
}
PHASE_ORDER = list(PHASE_US.keys())
PHASE_COLORS = {
    "trigger/validate": "#8888aa",
    "anchor resolve":   "#4c9be8",
    "subspace place":   "#e8794c",
    "arm watchers":     "#9b59b6",
    "onStart":          "#2ecc71",
    "tick/expire":      "#f1c40f",
    "onExpire+disarm":  "#e74c3c",
}


def anchor_scale(anchor):
    """regionQueue placement pays no anchor-resolution cost differently, but
    entity/fixed complete instantly; claimHazard does a memory recall. Keep the
    representative timings but tweak anchor cost slightly per source."""
    return {"regionQueue": 0.6, "entity": 0.2, "fixed": 0.1, "claimHazard": 0.9}.get(anchor, 0.6)


def simulate_lifecycle(name, spec):
    """Return an ordered list of (phase, start_us, dur_us) mirroring ActionSessionImpl.

    duration=0 -> one-shot: tick() immediately sees remainingSeconds()<=0 and expires,
    so onBoundaryViolation/onDeath never fire for these free samples.
    """
    phases = dict(PHASE_US)
    phases["anchor resolve"] = anchor_scale(spec["anchor"])
    # placement cost scales a little with participant count and footprint.
    n = spec["participants"]
    footprint_cells = (2 * spec["chunk_radius"] + 1) ** 2
    phases["subspace place"] = round(4.0 + 0.15 * footprint_cells + 0.4 * n, 2)
    timeline = []
    t = 0.0
    for ph in PHASE_ORDER:
        d = phases[ph]
        timeline.append((ph, t, d))
        t += d
    return timeline, t


def select_slots(spec):
    """Mirror the subspace ring/square slot selection around anchor (0,0)-relative.

    circle profile with center_radius -> annular ring (Rmin..Rmax).
    square profile -> uniform footprint, spread by min_sep.
    """
    cx, cz = ANCHORS[spec["anchor"]]
    r_blocks = spec["chunk_radius"] * 16
    rmin = spec["center_radius"]
    n = spec["participants"]
    slots = []
    tries = 0
    while len(slots) < n and tries < 5000:
        tries += 1
        if spec["profile"] == "circle":
            ang = random.uniform(0, 2 * math.pi)
            rad = math.sqrt(random.uniform(rmin * rmin, r_blocks * r_blocks))
            x = cx + rad * math.cos(ang)
            z = cz + rad * math.sin(ang)
        else:  # square
            x = cx + random.uniform(-r_blocks, r_blocks)
            z = cz + random.uniform(-r_blocks, r_blocks)
        ok = all((x - sx) ** 2 + (z - sz) ** 2 >= spec["min_sep"] ** 2 for sx, sz in slots)
        if ok:
            slots.append((x, z))
    return (cx, cz), r_blocks, rmin, slots


def render_timeline(name, spec, timeline, total):
    fig, ax = plt.subplots(figsize=(9, 2.6))
    for ph, start, dur in timeline:
        ax.barh(0, dur, left=start, height=0.6, color=PHASE_COLORS[ph],
                edgecolor="black", linewidth=0.5)
        if dur >= total * 0.04:
            ax.text(start + dur / 2, 0, ph, ha="center", va="center",
                    fontsize=7, rotation=0, color="black")
    ax.set_xlim(0, total * 1.02)
    ax.set_yticks([])
    ax.set_xlabel("elapsed (microseconds, off-tick)")
    ax.set_title(f"[{name}] lifecycle timeline  -  total {total:.1f} us  (duration={spec['duration']}s, one-shot)",
                 fontsize=10)
    handles = [plt.Rectangle((0, 0), 1, 1, color=PHASE_COLORS[p]) for p in PHASE_ORDER]
    ax.legend(handles, PHASE_ORDER, ncol=4, fontsize=6, loc="upper center",
              bbox_to_anchor=(0.5, -0.35))
    fig.tight_layout()
    p = os.path.join(OUT, f"lifecycle_{name}.png")
    fig.savefig(p, dpi=130, bbox_inches="tight")
    plt.close(fig)
    return p


def render_placement(name, spec, anchor, r_blocks, rmin, slots):
    cx, cz = anchor
    fig, ax = plt.subplots(figsize=(5.2, 5.2))
    if spec["profile"] == "circle":
        ax.add_patch(Circle((cx, cz), r_blocks, fill=False, ls="--",
                            edgecolor="#e8794c", lw=1.5, label="Rmax (footprint)"))
        if rmin > 0:
            ax.add_patch(Circle((cx, cz), rmin, fill=True, facecolor="#f5d3c4",
                                edgecolor="#c0392b", lw=1.2, alpha=0.6,
                                label="Rmin hollow (pinned OOB)"))
    else:
        ax.add_patch(Rectangle((cx - r_blocks, cz - r_blocks), 2 * r_blocks, 2 * r_blocks,
                              fill=False, ls="--", edgecolor="#e8794c", lw=1.5,
                              label="SUBSPACE footprint"))
    ax.plot([cx], [cz], marker="*", markersize=16, color="#2c3e50",
            label=f"anchor ({spec['anchor']})")
    if slots:
        xs = [s[0] for s in slots]
        zs = [s[1] for s in slots]
        ax.scatter(xs, zs, s=70, color="#2ecc71", edgecolor="black", zorder=5,
                   label=f"placed participant(s) x{len(slots)}")
    pad = r_blocks * 1.25 + 8
    ax.set_xlim(cx - pad, cx + pad)
    ax.set_ylim(cz - pad, cz + pad)
    ax.set_aspect("equal")
    ax.set_xlabel("world X (blocks)")
    ax.set_ylabel("world Z (blocks)")
    ax.set_title(f"[{name}] subspace placement\n{spec['blurb']}", fontsize=9)
    ax.legend(fontsize=6, loc="upper right")
    ax.grid(True, ls=":", alpha=0.4)
    fig.tight_layout()
    p = os.path.join(OUT, f"placement_{name}.png")
    fig.savefig(p, dpi=130, bbox_inches="tight")
    plt.close(fig)
    return p


def render_comparison(totals):
    fig, ax = plt.subplots(figsize=(8, 4))
    names = list(totals.keys())
    vals = [totals[n] for n in names]
    bars = ax.bar(names, vals, color="#4c9be8", edgecolor="black")
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v, f"{v:.1f}", ha="center",
                va="bottom", fontsize=8)
    ax.set_ylabel("total lifecycle cost (us)")
    ax.set_title("Per-action end-to-end lifecycle cost (off-tick; 1 tick = 50000 us)")
    ax.grid(True, axis="y", ls=":", alpha=0.4)
    fig.tight_layout()
    p = os.path.join(OUT, "comparison_lifecycle_cost.png")
    fig.savefig(p, dpi=130, bbox_inches="tight")
    plt.close(fig)
    return p


def render_real_events():
    """Render a phase-flow chart from the REAL lifecycle events recorded by the JUnit
    end-to-end test (scripts/sim/out/e2e_events.csv). This proves the shipped code path
    (ActionConfigLoader -> ActionManager -> ActionSessionImpl) actually ran each phase."""
    csv_path = os.path.join(OUT, "e2e_events.csv")
    if not os.path.exists(csv_path):
        print("[DEBUG_LOG] no e2e_events.csv found; run the SampleActionE2ETest first for real-data chart")
        return None
    import csv
    rows = []
    with open(csv_path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append(r)
    # group phases per action in recorded order
    order = []
    per = {}
    for r in rows:
        a = r["action"]
        if a not in per:
            per[a] = []
            order.append(a)
        per[a].append((r["phase"], r["detail"]))

    phase_seq = ["trigger", "placement+arm", "onStart", "disarm"]
    pcolor = {"trigger": "#8888aa", "placement+arm": "#e8794c",
              "onStart": "#2ecc71", "disarm": "#e74c3c"}
    fig, ax = plt.subplots(figsize=(9, 0.7 * len(order) + 1.5))
    for yi, a in enumerate(order):
        phases_hit = [p for p, _ in per[a]]
        for xi, ph in enumerate(phase_seq):
            hit = ph in phases_hit
            ax.barh(yi, 1.0, left=xi, height=0.6,
                    color=pcolor[ph] if hit else "#dddddd",
                    edgecolor="black", linewidth=0.5)
            mark = "OK" if hit else "-"
            ax.text(xi + 0.5, yi, f"{ph}\n{mark}", ha="center", va="center", fontsize=6)
    ax.set_yticks(range(len(order)))
    ax.set_yticklabels(order)
    ax.set_xticks([])
    ax.set_xlim(0, len(phase_seq))
    ax.set_title("REAL recorded lifecycle (JUnit SampleActionE2ETest) - shipped YAML -> engine")
    fig.tight_layout()
    p = os.path.join(OUT, "real_lifecycle_e2e.png")
    fig.savefig(p, dpi=130, bbox_inches="tight")
    plt.close(fig)
    return p


def main():
    print("[DEBUG_LOG] Rendering lifecycle simulation charts ->", OUT)
    totals = {}
    for name, spec in ACTIONS.items():
        timeline, total = simulate_lifecycle(name, spec)
        totals[name] = total
        p1 = render_timeline(name, spec, timeline, total)
        anchor, r_blocks, rmin, slots = select_slots(spec)
        p2 = render_placement(name, spec, anchor, r_blocks, rmin, slots)
        print(f"[DEBUG_LOG] {name:11s} total={total:6.1f}us  anchor={spec['anchor']:11s}"
              f"  slots={len(slots)}  -> {os.path.basename(p1)}, {os.path.basename(p2)}")
    pc = render_comparison(totals)
    print("[DEBUG_LOG] comparison ->", os.path.basename(pc))
    pr = render_real_events()
    if pr:
        print("[DEBUG_LOG] real recorded lifecycle ->", os.path.basename(pr))
    print("[DEBUG_LOG] Done. Charts in", OUT)


if __name__ == "__main__":
    main()
