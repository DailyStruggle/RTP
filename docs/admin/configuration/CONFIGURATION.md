# Configuration Reference

**Applies to Plugin Version:** `3.0.0-beta.1`

All configuration files live in `plugins/RTP/` after the first server start. Edit them directly and run `/rtp reload` to apply changes, as this will update settings without a server restart.

> 📎 **How RTP loads, reloads, and upgrades these files** (including what the `.old1`/`.old2` files are, and how your customizations are preserved across version bumps and locale switches): see [CONFIG_LIFECYCLE.md](CONFIG_LIFECYCLE.md).

---

## File Overview

| File | Purpose | Detailed Reference |
|---|---|---|
| `config.yml` | Core plugin settings (language, delays, database) | [CORE_CONFIG.md](CORE_CONFIG.md) |
| `regions/*.yml` | Per-region teleport area, shape, queue settings | [REGIONS.md](REGIONS.md) |
| `worlds/*.yml` | Per-world default region and permission settings | [WORLDS.md](WORLDS.md) |
| `performance.yml` | Background task timing, cache behaviour, TPS thresholds | [PERFORMANCE.md](PERFORMANCE.md) |
| `economy.yml` | Teleport costs and refund policy (requires Vault) | [ECONOMY.md](ECONOMY.md) |
| `safety.yml` | Landing safety checks, invulnerability, biome filters | [SAFETY.md](SAFETY.md) |
| `messages.yml` | All player-facing message strings | [MESSAGES.md](MESSAGES.md) |
| `logging.yml` | Console logging verbosity | [LOGGING.md](LOGGING.md) |
| `language.yml` | Locale selection (loaded before all other files) | [LANGUAGE.md](LANGUAGE.md) |
| `integrations.yml` | Claim/region protection plugin reroll toggles | [INTEGRATIONS.md](INTEGRATIONS.md) |
| `metrics.yml` | Runtime-health metrics SPI reporting knobs | [METRICS.md](METRICS.md) |
| `network.yml` | Multi-server / multi-proxy network mode | [proxies/CONFIGURATION.md](../proxies/CONFIGURATION.md) |
| `effects/*.yml` | Per-event teleport effects (sounds, particles, potions) | [EVENTS_AND_EFFECTS.md](EVENTS_AND_EFFECTS.md) |

---

## `regions/<name>.yml` — Region Configuration

Each file in the `regions/` folder defines one teleport region. The filename (without `.yml`) is the region's name.

> 📎 **Detailed Reference:** See [REGIONS.md](REGIONS.md) for a full breakdown of every key and engine parameter in the region configuration.

### Top-level keys

| Key | Type | Default | Description |
|---|---|---|---|
| `world` | String | `"[0]"` | Target world name. `[0]` = main world, `[1]` = the_nether, `[2]` = the_end, or a literal world name. |
| `worldBorderOverride` | Boolean | `false` | If `true`, uses the vanilla `/worldborder` radius instead of the shape radius. |
| `requirePermission` | Boolean | `false` | If `true`, players need `rtp.regions.<regionName>` to use this region. |
| `override` | String | `"default"` | Region to redirect to if a player lacks permission for this region. |
| `cacheCap` | Integer | `50` | Maximum pre-generated safe locations to hold in the queue. Set to `0` to disable background caching. |
| `backlogCacheCap` | Integer | `1000` (lite: `0`) | Maximum **unverified** candidates staged upstream of `cacheCap`. Spiral picks land here without chunk I/O, then a per-region pulse verifies one `.mca` bin at a time and promotes in insertion order. Set to `0` to disable. See [REGIONS.md → Backlog Cache (L3)](REGIONS.md#backlog-cache-l3) and [ADR-028](../../adr/ADR-028-l3-backlog-cache.md). |
| `activeChunkCap` | Integer | `10` | Maximum chunks kept loaded simultaneously for zero-latency teleportation. |
| `price` | Double | `0.0` | Economy cost override for this region (overrides the global `economy.yml` price). |
| `spatialResolution` | Integer | `3` | Precision level for storing spatial memory in the database. Higher = more memory, finer detail. |
| `version` | String | `"1.0"` | Internal config version (do not change). |

---

### `shape` section

The `shape` block defines how horizontal coordinates are selected. The `name` key selects the shape engine; the remaining keys configure it.

#### Keys common to all shapes

| Key | Type | Description |
|---|---|---|
| `name` | String | Shape engine: `CIRCLE`, `CIRCLE_NORMAL`, `SQUARE`, `SQUARE_NORMAL`, `RECTANGLE`. |
| `mode` | String | Selection logic. See table below. |
| `centerX` | Integer | Chunk X coordinate of the region centre (default `0`). |
| `centerZ` | Integer | Chunk Z coordinate of the region centre (default `0`). |
| `uniquePlacements` | Integer | Chunk radius cleared around each used location so it is never reused. `0` = off (default), `1` = the landing chunk only, `N` = an `(2N-1)x(2N-1)` chunk square centred on the landing chunk. (Legacy `true`/`false` are still accepted and map to `1`/`0`.) |

**`mode` options:**

| Value | Behaviour |
|---|---|
| `ACCUMULATE` | Even distribution; accumulates all sectors up to the current point to compute a shift, with distances precomputed at region load. Recommended default. |
| `NEAREST` | Tries the closest non-blocked spot. Fast, but can cause clustering near edges. |
| `REROLL` | Re-selects when a bad sector is hit. Even distribution and fast, but not strictly bounded. |
| `NONE` | No pre-check at all. Best for massive radii where pre-checking is impractical. |

#### `CIRCLE` and `SQUARE` — additional keys

| Key | Type | Default | Description |
|---|---|---|---|
| `radius` | Integer | `256` | Maximum distance from the centre in **chunks**. |
| `centerRadius` | Integer | `64` | Minimum distance from the centre (donut hole) in **chunks**. Players won't land inside this radius. |
| `weight` | Double | `1.0` | Distribution weight. `> 1.0` shifts landings toward the centre; `< 1.0` shifts toward the edge. |
| `expand` | Boolean | `false` | If `true`, the radius grows automatically as locations are consumed. |

#### `CIRCLE_NORMAL` and `SQUARE_NORMAL` — additional keys

Normal-distribution variants replace `weight` with explicit statistical parameters.

| Key | Type | Default | Description |
|---|---|---|---|
| `radius` | Integer | `256` | Maximum distance from the centre in **chunks**. |
| `centerRadius` | Integer | `64` | Minimum distance from the centre in **chunks** (donut hole). |
| `mean` | Double | — | Mean of the normal distribution (0.0 = centre, 1.0 = edge). |
| `deviation` | Double | — | Standard deviation. Smaller = tighter cluster; larger = wider spread. |
| `expand` | Boolean | `false` | If `true`, the radius grows automatically as locations are consumed. |

#### `RECTANGLE` — additional keys

| Key | Type | Default | Description |
|---|---|---|---|
| `width` | Integer | — | Half-width of the rectangle in **chunks** (X-axis extent from centre). |
| `height` | Integer | — | Half-height of the rectangle in **chunks** (Z-axis extent from centre). |
| `rotation` | Double | `0.0` | Rotation of the rectangle in degrees around the centre. |

---

### `vert` section

The `vert` block controls how the Y coordinate (height) is chosen once a horizontal position is selected.

#### Keys common to all vert adjustors

| Key | Type | Default | Description |
|---|---|---|---|
| `name` | String | `"JUMP"` | Vertical adjustor engine: `JUMP` or `LINEAR`. |
| `minY` | Integer | `32` | Minimum Y level a player can land at. |
| `maxY` | Integer | `255` | Maximum Y level a player can land at. |
| `requireSkyLight` | Boolean | `false` | If `true`, only accept locations with direct sky access (above-ground only). |

#### `JUMP` — additional keys

| Key | Type | Default | Description |
|---|---|---|---|
| `step` | Integer | `16` | Number of blocks to skip per vertical search iteration. Larger = faster but coarser scan. |

#### `LINEAR` — additional keys

| Key | Type | Default | Description |
|---|---|---|---|
| `direction` | Integer | `2` | Search direction: `0` = bottom-up (scan from `minY` to `maxY`), `1` = top-down (scan from `maxY` to `minY`), `2` = middle-out (default), `3` = edges-in, any other integer = randomized order. |

---

**Full example — a nether region:**
```yaml
world: "world_nether"
worldBorderOverride: false
requirePermission: false
override: "default"
cacheCap: 10
activeChunkCap: 5
price: 0.0
spatialResolution: 3
shape:
  name: "CIRCLE"
  mode: "ACCUMULATE"
  radius: 128
  centerRadius: 32
  centerX: 0
  centerZ: 0
  weight: 1.0
  uniquePlacements: 0
  expand: false
vert:
  name: "JUMP"
  minY: 32
  maxY: 120
  step: 16
  requireSkyLight: false
```

---

## `worlds/<name>.yml` — World Configuration

Each file in the `worlds/` folder maps a world to its default region and permission settings.

| Key | Type | Default | Description |
|---|---|---|---|
| `region` | String | `"default"` | Default region used when a player runs `/rtp` in this world. |
| `requirePermission` | Boolean | `false` | If `true`, players need `rtp.worlds.<name>` to RTP in this world. |
| `override` | String | `"[0]"` | World to redirect to if a player lacks permission. |
| `version` | String | `"1.0"` | Internal version — **do not change**. |

---

## `performance.yml` — Performance Settings

| Key | Type | Default | Description                                                                           |
|---|---|---|---------------------------------------------------------------------------------------|
| `maxAttempts` | Integer | `32` | Maximum location search attempts before giving up. Higher = more CPU per request.     |
| `viewDistanceSelect` | Integer | `0` | Chunk radius to pre-load around a candidate location during selection. `0` = minimum. |
| `viewDistanceTeleport` | Integer | `0` | Chunk radius to pre-load around the final destination before teleporting.             |
| `syncAllottedTime` | Integer | `50` | Max milliseconds per tick spent on synchronous RTP tasks. Range: 0–50.                |
| `period` | Integer | `100` | Ticks between background cache cycles (where 20 ticks equal 1 second).                     |
| `asyncAllottedTime` | Integer | `50` | Max milliseconds per tick spent on asynchronous RTP tasks.                            |
| `minTPS` | Double | `19.0` | Minimum server TPS before the plugin pauses background generation. Range: 0.0–20.0.   |
| `postTeleportQueueing` | Boolean | `false` | If `true`, immediately tries to refill the cache after each teleport.                 |
| `syncLoading` | Boolean | `false` | Use synchronous chunk loading. This is **not recommended**, as it can cause server hangs.          |
| `onEventParsing` | Boolean | `false` | Parse permissions on each event. This is incompatible with wildcard (`*.*`) permissions.      |
| `effectParsing` | Boolean | `true` | Parse effect permissions on startup.                                                  |
| `biomeRecall` | Boolean | `true` | Reuse previously found biome locations from cache.                                    |
| `biomeRecallForced` | Boolean | `false` | Only teleport to biomes already in cache (never search for new ones).                 |
| `checkOnChunkLoads` | Boolean | `false` | Check for safe locations in chunks as they load. This has a **high impact** on busy servers.     |

---

## `economy.yml` — Economy Settings

Requires **Vault** and a compatible economy plugin. If Vault is absent, all economy settings are ignored.

| Key | Type | Default | Description |
|---|---|---|---|
| `refundOnCancel` | Boolean | `true` | Refund the cost and reset cooldown if the teleport is cancelled (e.g., player moves). |
| `price` | Double | `50.0` | Base cost for `/rtp`. Set to `0.0` to disable. |
| `priceOther` | Double | `200.0` | Cost to teleport another player (`/rtp <player>`). |
| `paramsPrice` | Double | `0.0` | Additional cost for using parameters (world, region, shape, vert). |
| `biomePrice` | Double | `0.0` | Additional cost for specifying a biome target. |
| `balanceFloor` | Double | `0.0` | Minimum balance a player must retain after paying. Prevents going negative. |

---

## `safety.yml` — Safety Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `invulnerabilityTime` | Integer | `5` | Seconds of invulnerability granted after teleporting. Prevents fall/fire/drown damage on arrival. |
| `safetyRadius` | Integer | `0` | Block radius around the landing point to check for hazards. `0` = check only the landing block. |
| `platformRadius` | Integer | `0` | Radius of a platform created at the landing point. Set to `-1` to disable platforms entirely. |
| `platformDepth` | Integer | `1` | Depth (downward) of the platform. |
| `platformAirHeight` | Integer | `2` | Height of air to ensure above the platform. |
| `platformMaterial` | String | `GLASS` | Block type used for the platform if no solid block exists at the landing point. |
| `airBlocks` | List | *(flowers, grass, etc.)* | Block types treated as passable air during safety checks. |
| `unsafeBlocks` | List | *(lava, fire, etc.)* | Block types that make a location unsafe. |
| `biomeWhitelist` | Boolean | `false` | If `true`, the `biomes` list is a **whitelist** (only allow listed biomes). If `false`, it is a **blacklist**. |
| `biomes` | List | *(ocean, nether, end biomes)* | Biomes to exclude (blacklist) or exclusively allow (whitelist). |

**Default biome blacklist includes:** all ocean variants, deep dark, mushroom fields, nether biomes (nether wastes, soul sand valley, crimson/warped forest, basalt deltas), and end biomes (the end, end highlands/midlands/barrens, small end islands, the void).

---

## Custom Shapes and Addons

All five built-in shape engines (`CIRCLE`, `CIRCLE_NORMAL`, `SQUARE`, `SQUARE_NORMAL`, `RECTANGLE`) are configured inline inside each region's `shape:` block, as there are no separate per-shape config files.

Custom shapes can be registered at runtime via `rtp-api`. See [`addons/`](../../addons/) for examples. A registered custom shape appears as a valid `shape.name` value in any region config.

---

## `worlds/<name>.yml` — World Configuration

See [WORLDS.md](WORLDS.md) for a full breakdown of the world configuration.

---

## `logging.yml` — Console Logging Verbosity

Enables or disables individual console-log categories and sets the plugin's minimum log level. See [LOGGING.md](LOGGING.md) for the complete category list and the `min_level` filter.

---

## `messages.yml` — Message Customisation

All player-facing strings are defined here. Supports `&` colour codes, hex codes, MiniMessage tags, and PlaceholderAPI placeholders. Edit any value to localise or rebrand messages. See [MESSAGES.md](MESSAGES.md) for the formatting rules, the `[Pn]` placeholder system, and the section-by-section layout.

---

## Tips

- **Run `/rtp reload` after every edit**; no restart needed.
- **Never change `version:` fields**, as they are used internally for config migration.
- **Use `[0]`, `[1]`, `[2]`** as world placeholders instead of hardcoded names if your world names may change.
- **Set `cacheCap` to match your player count** — a server with 50 concurrent players benefits from a larger cache than a server with 5.
- **Lower `minTPS`** (e.g., `18.0`) on busy servers to prevent the plugin from adding load during lag spikes.
