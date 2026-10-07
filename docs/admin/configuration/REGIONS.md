# Region Configuration Reference (`regions/*.yml`)

A **region** is a named, reusable teleport destination: a target world plus the geometry players are placed in (`shape`), the vertical window (`vert`), safety and biome overrides, caching, and price. It is the unit RTP pre-generates locations for, and it is where teleport distance lives - see [Region Size: `radius` and `centerRadius`](#region-size-radius-and-centerradius).

Regions are a separate concept from worlds on purpose. A [world file](WORLDS.md) carries no geometry of its own; it only names the region that answers `/rtp` there. So one region can serve many worlds, one world can be served by many regions (permission tiers, or an explicit `region=<name>` on the command), and a region can send players into a world other than the one they ran the command in.

This document provides a detailed reference for all configuration options available in a region file (e.g., `plugins/RTP/definitions/regions/default.yml`).

---

## Updating Settings

You can create and update regions through:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Regions**.
2. **Command line**: Use `/rtp config region <name> <key>=<value>` (e.g. `/rtp config region default shape.radius=625`).
3. **Direct editing**: Edit `definitions/regions/<name>.yml` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

---

## Top-Level Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `world` | String | `"[0]"` | The target world for this region. Supports `[0]`, `[1]`, `[2]` placeholders or exact names. |
| `worldBorderOverride` | Boolean | `false` | If true, the `shape` block is replaced by a square matching the world's vanilla `/worldborder`, and the configured `radius` / `centerRadius` / `centerX` / `centerZ` are ignored. See [Region Size](#region-size-radius-and-centerradius). |
| `requirePermission` | Boolean | `false` | If true, players need `rtp.regions.<name>` permission to use this region. |
| `override` | String | `"default"` | If a player lacks permission, they are redirected to this region instead. |
| `cacheCap` | Integer | `50` | Maximum number of safe locations to pre-calculate and store in the background. |
| `backlogCacheCap` | Integer | `1000` (lite: `0`) | Maximum number of **unverified** candidate locations to stage upstream of `cacheCap`. See *Backlog Cache (L3)* below. Set to `0` to disable. |
| `activeChunkCap` | Integer | `10` | Maximum number of chunks to keep loaded for zero-latency teleports. |
| `price` | Double | `0.0` | Economy cost to use this specific region (overrides global `price`). |
| `spatialResolution` | Integer / String | `"auto"` | Precision for spatial memory (bad location tracking). Can be `"auto"` or any positive integer (e.g. `1`, `3`, `4`). Values > 1 in dual-layer shapes also dictate dyadic candidate downsampling grids (e.g. 4 -> 4x4 chunk macro-cells, 8 -> 8x8 macro-cells). |
| `displayName` | String | (region name) | Optional cosmetic display name shown in menus and messages; does not change the region's identity or the permission node. |
| `biomeWhitelist` / `biomes` | Boolean / List | (inherited from `safety.yml`) | Optional per-region override of the global biome filter. `biomeWhitelist: true` makes `biomes` an allow-list; `false` makes it a block-list. See [SAFETY.md](SAFETY.md). |
| `version` | String | `"1.1"` | Internal config version. **Do not modify.** |

> **Inheritance (`@config`).** Most of the keys above accept the token `@config` instead of a literal value, in which case they inherit the matching global default from the `defaults:` block of `config.yml`. The type-bearing `shape`/`vert` keys inherit as a whole named block; type-free scalars (`requirePermission`, `cacheCap`, `backlogCacheCap`, `activeChunkCap`, `spatialResolution`) inherit individually; `price` may reference `@economy`. See [CORE_CONFIG.md → Defaults (inheritance)](CORE_CONFIG.md#defaults-inheritance).

> **"Zone"/"arena" synonym.** Other plugins call a bounded random-teleport area a "zone" or "arena"; in RTP that concept *is* a region - there is no separate object to configure. A region controls *where a player lands*, not *whether they can walk back out*. To keep a teleported player confined to the area, either pair the region with a WorldGuard region whose `exit` flag is `deny` (Bukkit family only), or use the cross-platform tether addon (LeafRTPTetherAddon), which enforces confinement on RTP's own geometry with no WorldGuard dependency.

---

## `shape` Section

The `shape` block defines the horizontal area where players can land.

### Region Size: `radius` and `centerRadius`

**By default, numeric distances in the `shape` block are measured in chunks (1 chunk = 16 blocks).**
However, RTP supports **spatial unit suffixes** on any distance parameter, as well as automatic interpretation of ambiguous numbers and world-border overflow checking.

#### Spatial Unit Suffixes

You can explicitly specify distance units in config files or command parameters:

- **Minecraft Native Units:**
  - `c`, `chunk`, `chunks`: Chunks (1 chunk = 16 blocks). E.g. `radius: 256c` (4,096 blocks).
  - `b`, `block`, `blocks`: Minecraft blocks (1 block = 1 meter). E.g. `radius: 4096b` (256 chunks).
  - `nb`, `netherblock`, `netherblocks`: Nether coordinate blocks (8 Overworld blocks). E.g. `radius: 500nb` (4,000 blocks).
  - `r`, `region`, `regions`: Region files (1 region = 32 chunks = 512 blocks). E.g. `radius: 4r` (128 chunks = 2,048 blocks).
- **Metric Units (1 block = 1 meter):**
  - `m`, `meter`, `meters`, `metre`, `metres`: Meters (1 meter = 1 block). E.g. `radius: 5000m`.
  - `km`, `k`, `kilo`, `kilos`, `kilometer`, `kilometers`, `kilometre`, `kilometres`: Kilometers. E.g. `radius: 5km` (5,000 blocks = 312.5 chunks).
- **Imperial & Survey Units:**
  - `mi`, `mile`, `miles`: Statute miles (1,609.344 blocks). E.g. `radius: 3mi`.
  - `yd`, `yard`, `yards`: Yards (0.9144 blocks).
  - `ft`, `foot`, `feet`, `'`: Feet (0.3048 blocks). E.g. `radius: 1000ft` or `radius: 1000'`.
  - `in`, `inch`, `inches`, `"`: Inches (0.0254 blocks).
  - `nmi`, `nm`, `nauticalmile`, `nauticalmiles`: Nautical miles (1,852 blocks).
  - `furlong`, `furlongs` (201.168 blocks), `chain`, `chains` (20.1168 blocks), `rod`, `rods`, `pole`, `perch` (5.0292 blocks).
- **Easter Egg Units:**
  - `smoot`, `smoots`: Smoots (1.7018 blocks).
  - `fathom`, `fathoms`: Fathoms (1.8288 blocks).
  - `league`, `leagues`: Leagues (~4,828.032 blocks).
  - `cubit`, `cubits`: Royal Cubits (0.4572 blocks).
  - `au`, `aus`, `astronomicalunit`: Astronomical Units (149,597,870,700 blocks).
  - `ly`, `lightyear`, `lightyears`: Light-years.
  - `pc`, `parsec`, `parsecs`: Parsecs.

*Note: Group-placement sub-regions (`SubspaceShape`) use unitless lattice cell coordinates and do not use spatial units.*

#### Auto-Interpretation of Dimensionless Numbers

If you omit the unit suffix and provide a plain number (e.g. `radius: 16` or `radius: 5000`):
- Plain numbers historically defaulted to chunks.
- If a value is unusually small (e.g. `4`, `8`, or `16`), treating it as single blocks would yield an area barely 1 chunk wide. RTP detects this against the world border and auto-interprets it as chunks or regions, outputting an informative log notice explaining the conversion and how to make it explicit with `c` or `b`.
- If a value is unusually large (e.g. `5000`) and interpreting it as chunks would overshoot the world border or world limits, RTP auto-interprets it as blocks.

#### World Border Overflow Warning & Chunk Snapping

- **Border Overflow Audit:** On startup and reload, RTP audits configured region extents against the world border (`/worldborder`). If a region's outer radius extends beyond the border, RTP logs a warning alerting operators so selection attempts are not wasted on unreachable coordinates outside the border.
- **Chunk-Inscribed Bounding:** To guarantee that all blocks within selectable chunks stay strictly within bounds (and never leak past a block radius or world border), chunk inscription scales block radii down to the largest whole chunk grid completely contained within the boundary (`(R - 15) / 16`).

| Key | Meaning | In blocks (default chunk units) |
|---|---|---|
| `radius` | **Outer** bound. Players never land farther than this from the center. Supports suffixes (e.g. `4096b`, `256c`, `4r`, `5km`). | `radius x 16` (if no suffix) |
| `centerRadius` | **Inner** bound (donut hole). Players never land closer than this to the center. Supports suffixes (e.g. `1000b`, `64c`). | `centerRadius x 16` (if no suffix) |
| `centerX` / `centerZ` | Center of the region in chunks (or with explicit unit suffixes). | `centerX x 16` (if no suffix) |

Handy conversions:

| `radius` (chunks) | Max distance from center (blocks) | Widest span, edge to edge (blocks) |
|---|---|---|
| `64` | 1,024 | 2,048 |
| `256` (default) | 4,096 | 8,192 |
| `625` | 10,000 | 20,000 |
| `1875` | 30,000 | 60,000 |
| `3750` | 60,000 | 120,000 |

Rules and gotchas:

- `centerRadius` must be **smaller** than `radius`. If the two are equal, or `centerRadius` is larger, there is no band left to pick from and the region cannot produce locations.
- The pickable band is `radius - centerRadius` chunks wide. Raising `centerRadius` to push players away from spawn without raising `radius` shrinks the usable land, so raise both together.
- Total selectable area is roughly `pi x (radius^2 - centerRadius^2)` chunks for `CIRCLE`, and `(2 x radius)^2 - (2 x centerRadius)^2` chunks for `SQUARE`.
- Radius is **not** clamped to the vanilla world border unless you ask for it. A `radius` that reaches past the border triggers a startup/reload audit warning and wastes selection attempts on unreachable land; either shrink it, use chunk-inscribed bounding, or set `worldBorderOverride: true`.
- `worldBorderOverride: true` **replaces the whole `shape` block** with a square derived from the world's `/worldborder` (chunk radius = border size / 32). Your `radius`, `centerRadius`, `centerX`, and `centerZ` are ignored while it is on.
- Large radii cost pre-calculation time, not memory: see *Massive Radii* under [Tips for Customization](#tips-for-customization) and the *Backlog Cache (L3)* section below.

#### Where to set the radius

Four places, in increasing precedence:

1. **Shared default** - `defaults.shape.radius` in `config.yml`. Applies to every region whose `shape` key is the literal `"@config"`. This is the right place on a single-world server: set it once.
2. **Per region** - replace `shape: "@config"` in `regions/<name>.yml` with an inline block. An inline block wins over the shared default and must be complete (copy the `defaults.shape` block from `config.yml` and edit it).
   ```yaml
   shape:
     name: "CIRCLE"
     mode: "ACCUMULATE"
     radius: 625          # 10,000 blocks from the center
     centerRadius: 64     # keep players 1,024+ blocks away from the center
     centerX: 0
     centerZ: 0
     weight: 1.0
     uniquePlacements: 0
     expand: false
   ```
3. **Persistent edit from in-game** - `/rtp config regions <region> shape.radius=625` writes the value to the region file. Add `--dry-run` to preview it first.
4. **One-off teleport** - `/rtp region=default shape=SQUARE radius=256` applies to that teleport only and changes nothing on disk.

Changing a radius invalidates cached locations for that region, so the first few `/rtp` calls afterwards may be slower while the cache refills.

### Common Shape Keys
- `name`: The shape engine to use.
- `mode`: The selection logic.
  - `ACCUMULATE`: (Recommended) Draws only from chunks not known to be bad, so learned bad ground costs nothing. Best for most cases. With `expand: false` a spot can occasionally come up again; see [Spacing, repeats and worst case by mode](#spacing-repeats-and-worst-case-by-mode).
  - `NEAREST`: Finds the closest non-blocked spot. Fast but may cause clustering.
  - `REROLL`: Skips known-bad chunks and draws again. On `CIRCLE` and `SQUARE` no spot comes up twice until the shuffle has cycled, and spacing stays exact; each known-bad chunk costs one in-memory check. See [Spacing, repeats and worst case by mode](#spacing-repeats-and-worst-case-by-mode).
  - `NONE`: No pre-check. Fastest but ignores pre-computed safety data.
- `centerX` / `centerZ`: The center of the region in **chunks**.
- `uniquePlacements`: Chunk radius cleared around a spot once a player lands there so it is never reused. `0` = off, `1` = the landing chunk only, `N` = an `(2N-1)x(2N-1)` chunk square. (Legacy `true`/`false` still work and map to `1`/`0`.) It only takes effect with `expand: true`, where the region grows to replace the cleared area; with `expand: false` it is ignored so the region cannot run out of destinations. Setting `auto` automatically derives the radius from the server's effective view distance (lowest power of 2 at or under view distance, e.g. 10 -> 8 chunks). When paired with `expand: true` in dual-layer shapes, it enables zero-memory dyadic stride downsampling ($S = (2R_u-1)^2$), keeping concurrent players isolated by view distance while driving rapid outward frontier expansion.

### Spacing, repeats and worst case by mode

This applies to `CIRCLE` and `SQUARE` (the dual-layer shapes). The other shapes don't use the keyed shuffle described here.

Candidates come from a keyed shuffle of the region, drawn in lanes that take the same spot in each bin. On the default 16,384-block circle, spots drawn back to back sit one bin apart (512 blocks). Where the spiral turns at its corners, bins change orientation and spots can come closer: about 5% of spots on the smallest regions, fewer on larger ones. On the default circle the closest pair is 362 blocks. A new lane starts every 16 to 64 draws, and spots from different lanes fall at random relative to each other. RTP keeps no list of past destinations and doesn't check where players are.

The two modes handle known-bad ground differently:

| | `ACCUMULATE` (default) | `REROLL` |
|---|---|---|
| What it draws from | Only chunks not known to be bad | Every chunk; a known-bad chunk is skipped and the next one drawn |
| Back-to-back spacing | In a simulation with 40% bad ground, pairs under 256 blocks were about 95% rarer than with random picks. Loosens toward random where the region has learned large bad areas (from `/rtp scan` or pregen) | Exact. A bad chunk removes one spot from the lane and moves no other |
| Repeat landings, `expand: false` | Possible, about as often as random picks (around 0.1% of landings in the simulation) | None until the shuffle has cycled |
| Cost of known-bad ground | None | One in-memory check per known-bad chunk, roughly a microsecond |
| Worst case | Bounded | Bounded: each known-bad chunk is drawn at most once per pass, and progress carries over between requests |

The ready cache is filled from the same draw order as live searches, so mixing cached and live answers adds no repeats and keeps back-to-back spacing.

**Why `ACCUMULATE` can repeat.** It numbers only the good chunks. Every newly learned rejection renumbers them, so a chunk a player already used can land on a number that hasn't been drawn yet. Landings are marked as used only with `uniquePlacements` and `expand: true`. Marking them with `expand: false` isn't an option: the region never grows back, so the marks would use it up until searches stop finding locations.

**`REROLL` limits.**

- One request skips at most 10,000 known-bad chunks and then fails. The next request carries on where it stopped. On a region that is nearly all bad, a player can see a failed attempt even though good ground is left.
- With `expand: true`, every new bad mark grows the range and restarts the shuffle with a new key. The pass starts over, so the once-per-pass ceiling no longer holds.
- Unexplored chunks cost a region-file read or a chunk load in either mode. Each one is checked once, and the result is remembered.

For comparison, picking a random spot and retrying has no ceiling. Each retry fails with the same odds as the last, and the same bad chunk can be loaded again on a later request.

### Shape Engines and Parameters

> **What is available on *your* server.** The engines documented below ship with RTP, but addons may register more. On every startup and `/rtp reload`, RTP writes the live catalog of registered shapes and their settings to `plugins/RTP/definitions/regions/SHAPES.md` (and vertical adjustors to `VERT.md`), in your configured language. Those files are generated from the running registry, so they are authoritative for your install - read them rather than guessing, and do not edit them (edits are overwritten on reload). The same catalog drives the type picker in the in-game menu.

#### `CIRCLE` / `SQUARE`
Standard shapes with uniform or weighted distribution.
- `radius`: Outer radius in **chunks**. For `CIRCLE` it is the disk radius; for `SQUARE` it is the half-extent, so the square spans `2 x radius` chunks per side.
- `centerRadius`: Inner radius (donut hole) in **chunks**. Must be less than `radius`. `CIRCLE` becomes a ring, `SQUARE` becomes a square frame.
- `weight`: `> 1.0` pulls landings toward center; `< 1.0` pushes toward edges. Applies within the `centerRadius`-to-`radius` band; it does not move the bounds themselves.
- `expand`: If true, radius grows as locations are used.

#### `CIRCLE_OPTIMIZED_DUAL_LAYER` / `SQUARE_OPTIMIZED_DUAL_LAYER`
Optimized dual-layer shapes implementing the continuous spiral-addressed Hilbert key space (ADR-085).
- Expands coarse spiral points into intra-point Hilbert traversals mapped to travel direction, eliminating run fragmentation across ring seams and drastically reducing memory footprint at one-chunk precision.
- Backed by hardware-cache segmented secondary tables (`SegmentedKeyRunTable`), providing up to 2x-11x faster coordinate selection under `ACCUMULATE` mode.
- Accepts the exact same parameters as `CIRCLE` and `SQUARE` (`radius`, `centerRadius`, `centerX`, `centerZ`, `weight`, `uniquePlacements`, `expand`, `mode`).

#### `CIRCLE_DEPRECATED_PURE_SPIRAL` / `SQUARE_DEPRECATED_PURE_SPIRAL`
Legacy pure 1D Archimedean spiral mapping shapes.
- Preserved for backwards compatibility, regression testing, and side-by-side performance benchmarking against dual-layer Hilbert shapes.
- Accepts identical parameters to `CIRCLE` and `SQUARE`.

#### `CIRCLE_NORMAL` / `SQUARE_NORMAL`
Gaussian distribution variants.
- `radius` / `centerRadius`: Same as above - still chunks, still the hard outer and inner bounds.
- `mean`: Center of the bell curve, expressed as a fraction of the band (0.0 = at `centerRadius`, 1.0 = at `radius`).
- `deviation`: Spread of the bell curve. Smaller = tighter clustering around `mean`.

#### `ELLIPSE`
A circle with independent X and Z semi-axes, so it can cover a non-square world border without wasting a corner.
- `radius` / `radius2`: The two outer semi-axes in **chunks**. The wider of the two sets the bounding circle the spiral mapping walks; the ellipse predicate rejects everything outside the true ellipse.
- `centerRadius` / `centerRadius2`: The two semi-axes of the inner exclusion ellipse, also in **chunks**. Both default to `0` (no hole).
- `rotation`: Rotation of both the outer and inner ellipse in degrees around `centerX` / `centerZ`.
- `weight`, `uniquePlacements`, `expand`, `mode`, `centerX`, `centerZ`: Same meaning as `CIRCLE`.

#### `RECTANGLE`
Uses explicit side lengths instead of a radius.
- `width` / `height`: Full X-axis and Z-axis extent in **chunks**, centred on `centerX` / `centerZ` (so `width: 256` reaches 128 chunks / 2,048 blocks either side). There is no `centerRadius` hole for this shape.
- `rotation`: Rotation in degrees around the center.

#### `POLYGON`
An arbitrary closed boundary, including concave ones, defined by a vertex list instead of a radius. It inherits the square sized to the polygon's bounding box for the spiral index and the spatial-memory store, then masks off everything outside the polygon.
- `vertices`: List of `[x, z]` pairs in traversal order, written Chunky-style - one bracketed pair per list item:

  ```yaml
  shape:
    name: POLYGON
    vertices:
      - [-125c, 187c]
      - [2000b, 3000b]
      - [10, -4]
  ```

  The single-line form `vertices: [[-125c, 187c], [2000b, 3000b], [10, -4]]` is equivalent. Like other distance and coordinate parameters, each coordinate supports spatial unit suffixes (`b` for blocks, `c` for chunks, `r` for regions, `km`, `m`, etc.). A coordinate **without a suffix is in chunks** (1 chunk = 16 blocks), so `[10, -4]` means `[10c, -4c]`; write explicit units to avoid ambiguity.
  - Block coordinates (Chunky / world coordinates): e.g. `[-2000b, 3000b]`, `[2000b, 3000b]`. Block values are converted to chunks and rounded to the nearest chunk (`3000b` = 187.5 chunks -> `188c`).
  - Chunk coordinates: e.g. `[-125c, 187c]` (same as `[-125, 187]`).
  - Needs at least 3 vertices, not all collinear, and no self-intersecting edges - any of those is rejected with a warning and falls back to the bounding square.
- `centerX` / `centerZ`: Optional. Defaults to the center of the vertex bounding box.
- `weight`, `uniquePlacements`, `mode`: Same meaning as `SQUARE`.
- `expand` is not supported here and is ignored (with a warning if set) - the boundary is yours, and expanding it would push selections outside the polygon you authored.

---

## `vert` Section

The `vert` block controls the Y-coordinate (height) selection.

### Common Vert Keys
- `name`: The vertical adjustor engine.
- `minY` / `maxY`: The allowed Y-range for teleportation.
- `requireSkyLight`: If true, only accepts locations with direct access to the sky (surface-only).

### Vert Engines and Parameters

#### `JUMP`
Scans vertically using fixed steps. Efficient for finding the first safe surface.
- `step`: Number of blocks to skip per search iteration. Default `16`.
- **Caveat**: Because it advances in fixed `step`-block jumps, it can skip over thin (one- or two-block-thick) platforms. In the Nether, where such platforms are common, prefer `vert: LINEAR` (see *Tips for Customization*).

#### `LINEAR`
A thorough scan of every Y level in a specific order.
- `direction`: Integer scan strategy (default `2`):
  - `0`: **Bottom-up** — Start at `minY` and scan up to `maxY`. Best for underground/cave landings.
  - `1`: **Top-down** — Start at `maxY` and scan down to `minY`. Best for surface landings.
  - `2`: **Middle-out** — Start at the middle of the range and scan outward toward both ends.
  - `3`: **Edges-in** — Start at both ends of the range and meet in the middle.
  - Any other integer: **Random** — Scan all Y levels in a randomized order. Best for "anywhere in this range" logic.

#### `FIXED`
Places the player at a single configured Y level in **mid-air**, with no terrain scan. Designed for skyblock-style worlds where the platform tool builds a foothold around the player after teleport.
- `y`: The exact Y-level for placement. Default `64`.
- The destination cell `(x, y, z)` and the head cell `(x, y+1, z)` must both be air; any non-air block at either cell is treated as unsafe and the chunk is rejected so a different one is rolled.
- Ignores `minY`, `maxY`, `direction`, `requireSkyLight`, and the `unsafeBlocks` ground sweep — none of those apply to mid-air placement.
- **Enable a platform builder** when using `FIXED`. Without one the player will fall straight through air.

---

## Backlog Cache (L3)

The backlog cache (controlled by `backlogCacheCap`) is an optional **unverified** staging buffer that sits upstream of the verified location queues (`cacheCap` / "kept" / "unkept"). It lets the region pre-pick spiral coordinates without paying chunk-I/O cost up front, then amortises verification across periodic pulses.

### How it works

- The spiral selector drops unverified candidates straight into the backlog — **no chunk load, no database write**.
- Each region tick pulses the backlog: the oldest unverified entry is picked, the region file (32×32 chunk bin: `.mca` Anvil, or another format registered by an addon) it falls in is identified, and *every* unverified entry that shares that bin is classified in one pass via the region pre-filter. This amortises the per-bin cost over many candidates.
- Entries are promoted into the verified queue **in insertion order**. An unverified head blocks promotion; an invalidated head is dropped silently and the next entry is considered. This preserves spiral order without stalling on failed candidates.
- The backlog is **not** persisted across restarts by design — entries are re-selected fresh on startup, so the cost of dropping them is bounded.

### When to enable or tune it

- **Leave at the default `1000`** if you have a large radius and want `/rtp` to feel instantaneous over long sessions: the backlog absorbs spiral selection pressure so that `cacheCap` rarely empties.
- **Lower or set to `0`** on very small radii (< 1000 chunks) where the spiral exhausts quickly and the backlog mostly duplicates work, or on memory-tight servers.
- **The lite jar** ships with `backlogCacheCap` omitted from `regions/default.yml`, so the in-code fallback resolves to `0` (disabled). Add the key explicitly to opt in on a lite deployment.
- The backlog holds no chunk tickets and no in-flight teleport tasks, so a high cap has minimal runtime memory cost beyond the raw coordinate records themselves.

### Relationship to other caches

Candidates flow through three tiers: the backlog (L3, unverified) → the cold cache (L2, verified, chunks released) → the hot cache (L1, verified, chunks held). `/rtp` polls the hot cache first, falls back to the cold cache (which re-loads chunks on use), and the backlog pulse keeps the cold cache supplied.

---

## Tips for Customization

1. **Nether Support**: Use `vert: LINEAR` with `direction: 0` (bottom-up), `maxY: 120`, and `requireSkyLight: false` to land on the nether floor rather than the roof. Avoid `vert: JUMP` here: its coarse `step` (default `16`) skips over the thin one- and two-block-thick platforms that are common in the Nether, so it frequently fails to find otherwise-valid footing. `LINEAR` scans every Y level and reliably catches those thin platforms.
2. **Cave Teleports**: Use `vert: LINEAR` with `direction: 0` (bottom-up) and a low `maxY` to favor underground locations.
3. **Massive Radii**: If your radius is > 50,000 blocks (roughly 3,125 chunks), use `mode: NONE` to avoid long pre-calculation times on startup.
4. **Skyblock / Mid-Air Drops**: Use `vert: FIXED` with `y: 128` and a platform tool enabled. The platform spawns under the player so they don't fall through the void.
