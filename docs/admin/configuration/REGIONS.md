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
| `spatialResolution` | Integer | `3` | Precision for spatial memory (bad location tracking). 1 is coarse, 5 is extremely fine. |
| `displayName` | String | (region name) | Optional cosmetic display name shown in menus and messages; does not change the region's identity or the permission node. |
| `biomeWhitelist` / `biomes` | Boolean / List | (inherited from `safety.yml`) | Optional per-region override of the global biome filter. `biomeWhitelist: true` makes `biomes` an allow-list; `false` makes it a block-list. See [SAFETY.md](SAFETY.md). |
| `version` | String | `"1.1"` | Internal config version. **Do not modify.** |

> **Inheritance (`@config`).** Most of the keys above accept the token `@config` instead of a literal value, in which case they inherit the matching global default from the `defaults:` block of `config.yml`. The type-bearing `shape`/`vert` keys inherit as a whole named block; type-free scalars (`requirePermission`, `cacheCap`, `backlogCacheCap`, `activeChunkCap`, `spatialResolution`) inherit individually; `price` may reference `@economy`. See [CORE_CONFIG.md → Defaults (inheritance)](CORE_CONFIG.md#defaults-inheritance).

> **"Zone"/"arena" synonym.** Other plugins call a bounded random-teleport area a "zone" or "arena"; in RTP that concept *is* a region - there is no separate object to configure. A region controls *where a player lands*, not *whether they can walk back out*. To keep a teleported player confined to the area, either pair the region with a WorldGuard region whose `exit` flag is `deny` (Bukkit family only), or use the cross-platform tether addon (LeafRTPTetherAddon), which enforces confinement on RTP's own geometry with no WorldGuard dependency.

---

## `shape` Section

The `shape` block defines the horizontal area where players can land.

### Region Size: `radius` and `centerRadius`

**Every distance in the `shape` block is measured in chunks, not blocks.** A chunk is 16x16 blocks, so multiply by 16 to get blocks. This is the single most common configuration mistake: `radius: 5000` is not a 5,000-block region, it is an 80,000-block one.

| Key | Meaning | In blocks |
|---|---|---|
| `radius` | **Outer** bound. Players never land farther than this from the center. | `radius x 16` |
| `centerRadius` | **Inner** bound (the donut hole). Players never land closer than this to the center. `0` means the center itself is fair game. | `centerRadius x 16` |
| `centerX` / `centerZ` | Center of the region, in chunk coordinates. `0, 0` is the chunk containing blocks `0..15`. | `centerX x 16` |

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
- Radius is **not** clamped to the vanilla world border unless you ask for it. A `radius` that reaches past the border wastes selection attempts on unreachable land; either shrink it or set `worldBorderOverride: true`.
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
  - `ACCUMULATE`: (Recommended) Even distribution, pre-calculated sectors. Best for most cases.
  - `NEAREST`: Finds the closest non-blocked spot. Fast but may cause clustering.
  - `REROLL`: Simple random selection with retries. Even but unbounded.
  - `NONE`: No pre-check. Fastest but ignores pre-computed safety data.
- `centerX` / `centerZ`: The center of the region in **chunks**.
- `uniquePlacements`: Chunk radius cleared around a spot once a player lands there so it is never reused. `0` = off, `1` = the landing chunk only, `N` = an `(2N-1)x(2N-1)` chunk square. (Legacy `true`/`false` still work and map to `1`/`0`.)

### Shape Engines and Parameters

#### `CIRCLE` / `SQUARE`
Standard shapes with uniform or weighted distribution.
- `radius`: Outer radius in **chunks**. For `CIRCLE` it is the disk radius; for `SQUARE` it is the half-extent, so the square spans `2 x radius` chunks per side.
- `centerRadius`: Inner radius (donut hole) in **chunks**. Must be less than `radius`. `CIRCLE` becomes a ring, `SQUARE` becomes a square frame.
- `weight`: `> 1.0` pulls landings toward center; `< 1.0` pushes toward edges. Applies within the `centerRadius`-to-`radius` band; it does not move the bounds themselves.
- `expand`: If true, radius grows as locations are used.

#### `CIRCLE_NORMAL` / `SQUARE_NORMAL`
Gaussian distribution variants.
- `radius` / `centerRadius`: Same as above - still chunks, still the hard outer and inner bounds.
- `mean`: Center of the bell curve, expressed as a fraction of the band (0.0 = at `centerRadius`, 1.0 = at `radius`).
- `deviation`: Spread of the bell curve. Smaller = tighter clustering around `mean`.

#### `RECTANGLE`
Uses explicit side lengths instead of a radius.
- `width` / `height`: Full X-axis and Z-axis extent in **chunks**, centred on `centerX` / `centerZ` (so `width: 256` reaches 128 chunks / 2,048 blocks either side). There is no `centerRadius` hole for this shape.
- `rotation`: Rotation in degrees around the center.

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
- Each region tick pulses the backlog: the oldest unverified entry is picked, the region file (32×32 chunk bin: `.mca` Anvil or `.linear` Linear) it falls in is identified, and *every* unverified entry that shares that bin is classified in one pass via the region pre-filter. This amortises the per-bin cost over many candidates.
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
