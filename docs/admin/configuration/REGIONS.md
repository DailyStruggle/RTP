# Region Configuration Reference (`regions/*.yml`)

This document provides a detailed reference for all configuration options available in a region file (e.g., `plugins/RTP/regions/default.yml`).

---

## Top-Level Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `world` | String | `"[0]"` | The target world for this region. Supports `[0]`, `[1]`, `[2]` placeholders or exact names. |
| `worldBorderOverride` | Boolean | `false` | If true, the region radius is automatically set to match the vanilla world border. |
| `requirePermission` | Boolean | `false` | If true, players need `rtp.regions.<name>` permission to use this region. |
| `override` | String | `"default"` | If a player lacks permission, they are redirected to this region instead. |
| `cacheCap` | Integer | `50` | Maximum number of safe locations to pre-calculate and store in the background. |
| `backlogCacheCap` | Integer | `1000` (lite: `0`) | Maximum number of **unverified** candidate locations to stage upstream of `cacheCap`. See *Backlog Cache (L3)* below. Set to `0` to disable. |
| `activeChunkCap` | Integer | `10` | Maximum number of chunks to keep loaded for zero-latency teleports. |
| `price` | Double | `0.0` | Economy cost to use this specific region (overrides global `price`). |
| `spatialResolution` | Integer | `3` | Precision for spatial memory (bad location tracking). 1 is coarse, 5 is extremely fine. |
| `displayName` | String | (region name) | Optional cosmetic display name shown in menus and messages; does not change the region's identity or the permission node. |
| `biomeWhitelist` / `biomes` | Boolean / List | (inherited from `safety.yml`) | Optional per-region override of the global biome filter. `biomeWhitelist: true` makes `biomes` an allow-list; `false` makes it a block-list. See [SAFETY.md](SAFETY.md). |
| `version` | String | `"1.0"` | Internal config version. **Do not modify.** |

> **Inheritance (`@config`).** Most of the keys above accept the token `@config` instead of a literal value, in which case they inherit the matching global default from the `defaults:` block of `config.yml`. The type-bearing `shape`/`vert` keys inherit as a whole named block; type-free scalars (`requirePermission`, `cacheCap`, `backlogCacheCap`, `activeChunkCap`, `spatialResolution`) inherit individually; `price` may reference `@economy`. See [CORE_CONFIG.md → Defaults (inheritance)](CORE_CONFIG.md#defaults-inheritance).

> **"Zone"/"arena" synonym.** Other plugins call a bounded random-teleport area a "zone" or "arena"; in RTP that concept *is* a region - there is no separate object to configure. A region controls *where a player lands*, not *whether they can walk back out*. To keep a teleported player confined to the area, either pair the region with a WorldGuard region whose `exit` flag is `deny` (Bukkit family only), or use the cross-platform tether addon (LeafRTPTetherAddon), which enforces confinement on RTP's own geometry with no WorldGuard dependency.

---

## `shape` Section

The `shape` block defines the horizontal area where players can land.

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
- `radius`: Outer radius in **chunks**.
- `centerRadius`: Inner radius (donut hole) in **chunks**.
- `weight`: `> 1.0` pulls landings toward center; `< 1.0` pushes toward edges.
- `expand`: If true, radius grows as locations are used.

#### `CIRCLE_NORMAL` / `SQUARE_NORMAL`
Gaussian distribution variants.
- `radius` / `centerRadius`: Same as above.
- `mean`: Center of the bell curve (0.0 center, 1.0 edge).
- `deviation`: Spread of the bell curve.

#### `RECTANGLE`
- `width` / `height`: Half-extents in **chunks** (X and Z axis).
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
- Each region tick pulses the backlog: the oldest unverified entry is picked, the `.mca` file (32×32 chunk bin) it falls in is identified, and *every* unverified entry that shares that bin is classified in one pass via the anvil pre-filter. This amortises the per-bin cost over many candidates.
- Entries are promoted into the verified queue **in insertion order**. An unverified head blocks promotion; an invalidated head is dropped silently and the next entry is considered. This preserves spiral order without stalling on failed candidates.
- The backlog is **not** persisted across restarts by design — entries are re-selected fresh on startup, so the cost of dropping them is bounded.

### When to enable or tune it

- **Leave at the default `1000`** if you have a large radius and want `/rtp` to feel instantaneous over long sessions: the backlog absorbs spiral selection pressure so that `cacheCap` rarely empties.
- **Lower or set to `0`** on very small radii (< 1000 chunks) where the spiral exhausts quickly and the backlog mostly duplicates work, or on memory-tight servers.
- **The lite jar** ships with `backlogCacheCap` omitted from `regions/default.yml`, so the in-code fallback resolves to `0` (disabled). Add the key explicitly to opt in on a lite deployment.
- The backlog holds no chunk tickets and no in-flight teleport tasks, so a high cap has minimal runtime memory cost beyond the raw coordinate records themselves.

### Relationship to other caches

`backlogCacheCap` (L3, unverified) → `unkeptLocations` (L2, verified, chunks released) → `keptLocations` (L1, verified, chunks held). `/rtp` polls L1 first, falls back to L2 (which re-loads chunks on use), and the L3 pulse keeps L2 supplied. See [ADR-028](../../adr/ADR-028-l3-backlog-cache.md) for the full design.

---

## Tips for Customization

1. **Nether Support**: Use `vert: JUMP` with `maxY: 120` and `requireSkyLight: false` to land on the nether floor rather than the roof.
2. **Cave Teleports**: Use `vert: LINEAR` with `direction: 0` (bottom-up) and a low `maxY` to favor underground locations.
3. **Massive Radii**: If your radius is > 50,000 blocks, use `mode: NONE` to avoid long pre-calculation times on startup.
4. **Skyblock / Mid-Air Drops**: Use `vert: FIXED` with `y: 128` and a platform tool enabled. The platform spawns under the player so they don't fall through the void.
