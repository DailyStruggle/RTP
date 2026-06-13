# How RTP Works — Core Concepts

**Current Plugin Version:** `3.0.0-beta.1`

This document explains what happens under the hood when a player runs `/rtp`.
No Java knowledge required — it's written for server administrators and curious contributors alike.

---

## The Problem RTP Solves

Most random teleport plugins work like this:

1. Player runs `/rtp`.
2. Plugin rolls two random numbers (X and Z).
3. Plugin checks if that spot is safe (not in lava, not underground, not in a bad biome).
4. If it's bad, go back to step 2 and try again.

This works, but it has a fatal flaw: **the server has no idea how long it will take**. In a world full of oceans or nether wastes, it might reroll hundreds of times, stalling the main thread and causing lag spikes every time someone teleports.

RTP eliminates the unbounded reroll loop entirely: selection cost is bounded regardless of how many bad sectors exist.

---

## The Two Big Ideas

### 1. Bounded Selection (No Rerolling)

Instead of picking a random point and checking if it's valid, RTP knows *in advance* which sectors of the region are bad (e.g., previously tried locations that produced unsafe spots). When it selects a point, it mathematically skips over those sectors in a single O(log n) lookup — no rejection-sampling retry loop whose iteration count depends on how many bad sectors exist.

This is what the `mode: ACCUMULATE` setting does. At region load time, RTP precomputes a cumulative map of the valid sectors. The lookup cost is O(log n) in the number of sectors — it does not grow with how many sectors are marked bad.

The core insight — mapping a 2D annular teleport region bijectively onto a 1D Archimedean spiral curve — was worked out and published by the plugin's developer in a [detailed mathematical writeup on r/admincraft](https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/). The full architectural rationale (including alternatives considered) is recorded in [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md).

The 1D ↔ 2D map is many-to-one in the chunk direction (multiple spiral indices can decode to the same chunk), but the **inverse is bounded by ≤ 2 results per chunk** for `CIRCLE`/`SQUARE`: the spiral's inter-turn radial spacing is 1 chunk while a chunk's diagonal is √2, so at most two consecutive turns can intersect a single unit-square chunk. This bound is what makes `MemoryShape.chunkToLocations` an O(1) inverse — implemented as an angular walk plus a single radial probe per shape, with no unbounded search.

> **Analogy:** Imagine picking a random page from a book, but some pages are torn out. Instead of flipping to a random page and checking if it's there, you count how many intact pages remain and map your random number directly to an intact page — one step, no retries.
>
> **Analogy:** This is the same principle used in HDD data recovery: a hard drive marks physically damaged areas as "bad sectors" and the operating system maintains a map of them, routing all reads and writes around them without ever attempting to use them again. RTP's MemoryShape does the same thing — bad sectors are recorded persistently and mathematically excluded from future selections without ever being retried.

### 2. The Pre-Generation Queue

Even a fast single-step selection still has to load a chunk to verify it's safe (no void, no lava surface, correct biome). Chunk loading is inherently slow.

RTP solves this by doing all the slow work *before* anyone asks for it:

- A background task continuously pre-generates and validates locations, storing them in a queue.
- When a player runs `/rtp`, the plugin pops the next ready location off the queue — instant.
- The background task then refills the queue asynchronously.

This is why the first teleport after a cold server start might take a moment (the queue is empty), but subsequent teleports are instant. Running `/rtp scan` manually pre-warms the queue.

---

## Regions

A **region** is an independent teleport zone. Each region has:
- Its own target world.
- Its own shape (circle, square, rectangle) and size.
- Its own queue of pre-generated locations.
- Its own permission requirements and economy cost.

A single server can have any number of regions. The `worlds/<name>.yml` file maps each world to its default region, so players who run `/rtp` in the nether can automatically land in a nether-specific region.

> **Common gotcha:** RTP ships with only one region (`default`), which targets the overworld. If you do not create a `worlds/world_nether.yml` (and a matching region file), every world — including the nether and the end — will fall back to the overworld region. See [FAQ.md](../admin/FAQ.md#why-does-rtp-always-send-me-to-the-overworld-even-when-im-in-the-nether-or-the-end) for the full setup steps.

---

## Shapes and Distributions

The **shape** controls *where* in the region a location can be selected — it defines the two-dimensional boundary on the X/Z plane.

| Shape | Description |
|---|---|
| `CIRCLE` | A ring between `centerRadius` and `radius`. Players land anywhere in the donut. |
| `SQUARE` | Same idea but with a square boundary instead of circular. |
| `RECTANGLE` | A rectangular boundary, optionally rotated. Useful for corridor-shaped areas. |
| `CIRCLE_NORMAL` / `SQUARE_NORMAL` | Uses a normal (bell curve) distribution — players cluster toward the mean. |

The **`weight`** parameter on `CIRCLE`/`SQUARE` skews the flat distribution:
- `weight: 1.0` — perfectly uniform (every point equally likely).
- `weight: 2.0` — centre-weighted (more landings near spawn distance).
- `weight: 0.5` — edge-weighted (more landings far from spawn).

---

## Vertical Selection (vert)

Once a horizontal position (X, Z) is chosen, RTP still needs a safe Y level. This is handled by the **vert adjustor**:

- **`JUMP`** — scans vertically in steps of `step` blocks, looking for solid ground with air above. Fast and suitable for most overworld and nether configurations.
- **`LINEAR`** — scans linearly upward or downward. Useful when a more predictable scan order is needed.

The `minY` and `maxY` keys constrain where the scan operates. The `requireSkyLight` flag restricts landings to above-ground locations (useful to prevent landing in caves).

---

## The Full Teleport Pipeline

Here is every step that happens from the moment a player runs `/rtp`:

```
Player runs /rtp
       │
       ▼
 Permission check ──✗──► Send "no permission" message, stop.
       │ ✓
       ▼
 Economy check ──✗──► Send "insufficient funds" message, stop.
       │ ✓
       ▼
 Dequeue pre-generated location
       │
   ┌───┴────────────────────────────────────────────────┐
   │ Queue has a ready location                         │ Queue is empty
   │ (normal case — instant)                            │ (cold start / scan not run)
   ▼                                                    ▼
 Teleport player immediately            Generate location on-demand (may take
       │                                1–2 ticks while chunk loads)
       │                                       │
       └───────────────────────────────────────┘
                          │
                          ▼
              Apply invulnerability timer
                          │
                          ▼
              Background task refills queue
              asynchronously for next player
```

---

## Memory and Persistence

RTP remembers which locations are bad across server restarts. This is stored in a small database under `rtp-core/database/`. The `spatialResolution` setting in the region config controls how precisely bad sectors are stored — higher values use more memory but allow finer-grained exclusion zones.

On startup, RTP loads this memory, so the background queue can begin filling immediately using knowledge from previous sessions. The plugin never starts completely cold after the first run.

---

## Platform Differences (Spigot / Paper / Folia)

RTP ships three platform adapters:

| Platform | Concurrency model |
|---|---|
| **Spigot** | Single-threaded; async tasks dispatched via Bukkit scheduler |
| **Paper** | Uses Paper's async chunk loading API for faster chunk pre-generation |
| **Folia** | Each chunk region runs on its own thread; RTP schedules tasks on the correct regional thread |

The core logic in `rtp-core` is identical across all three. Only the scheduling and chunk-loading calls differ, encapsulated in each adapter module. This means bug fixes and algorithm improvements automatically benefit all platforms.

---

## Where to Go Next

- [QUICK_START.md](../admin/QUICK_START.md) — set up the plugin in 5 minutes
- [CONFIGURATION.md](../admin/CONFIGURATION.md) — every config key explained
- [COMMANDS.md](../admin/COMMANDS.md) — command and permission reference
- [FAQ.md](../admin/FAQ.md) — common questions and gotchas
- [DESIGN.md](DESIGN.md) — deep-dive into the bounded execution and concurrency design
- [ARCHITECTURE.md](ARCHITECTURE.md) — module breakdown and code structure
- [SpigotMC resource page](https://www.spigotmc.org/resources/rtp.94812/) — releases, reviews, and community support
- [Original mathematical writeup](https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/) — the proof behind the bounded selection algorithm
