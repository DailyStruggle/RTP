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
| `activeChunkCap` | Integer | `10` | Maximum number of chunks to keep loaded for zero-latency teleports. |
| `price` | Double | `0.0` | Economy cost to use this specific region (overrides global `price`). |
| `spatialResolution` | Integer | `3` | Precision for spatial memory (bad location tracking). 1 is coarse, 5 is extremely fine. |
| `version` | String | `"1.0"` | Internal config version. **Do not modify.** |

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
- `uniquePlacements`: If true, a spot is never reused once a player lands there.

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
- `direction`: The scan strategy:
  - `0`: **DOWN** — Start at `maxY` and scan down to `minY`. Best for surface landings.
  - `1`: **UP** — Start at `minY` and scan up to `maxY`. Best for underground/cave landings.
  - `2`: **OUT_IN** — Start at both ends and meet in the middle.
  - `3`: **IN_OUT** — Start at the middle and scan toward both ends.
  - `4`: **SHUFFLE** — Scan all levels in a randomized order. Best for "anywhere in this range" logic.

---

## Tips for Customization

1. **Nether Support**: Use `vert: JUMP` with `maxY: 120` and `requireSkyLight: false` to land on the nether floor rather than the roof.
2. **Cave Teleports**: Use `vert: LINEAR` with `direction: 1` (UP) and a low `maxY` to favor underground locations.
3. **Massive Radii**: If your radius is > 50,000 blocks, use `mode: NONE` to avoid long pre-calculation times on startup.
