# Safety Configuration Reference (`safety.yml`)

This document provides a detailed reference for all configuration options available in `plugins/RTP/safety.yml`.

---

## Landing Protection

| Key | Type | Default | Description |
|---|---|---|---|
| `invulnerabilityTime` | Integer | `5` | Seconds of invulnerability granted after teleporting. Prevents fall, fire, or drowning damage on arrival. |
| `safetyRadius` | Integer | `0` | Block radius around the landing point to check for hazards. `0` = check only the landing block. |
| `staleChunkRetryLimit` | Integer | `2` | Bounded retry budget for the stale-chunk guard (ADR-015). Prevents race conditions where a chunk unloads before evaluation. |
| `anvilPrefilterEnabled` | Boolean | `true` | Off-thread anvil prefilter. |

## Landing Platforms

Used as a legacy fallback if no solid ground is found.

| Key | Type | Default | Description |
|---|---|---|---|
| `platformRadius` | Integer | `-1` | Radius of the emergency platform. Set to `-1` to disable (recommended). |
| `platformDepth` | Integer | `1` | Depth (downward) of the platform. |
| `platformAirHeight` | Integer | `2` | Height of air to ensure above the platform. |
| `platformMaterial` | String | `GLASS` | Block type used if no solid block exists. |

---

## Block Filters & Token Grammar

The `airBlocks` and `unsafeBlocks` lists use a specific grammar (ADR-017) to match blocks and properties.

### Grammar (Token Syntax)
- `MATERIAL` — Plain material (e.g., `LAVA`).
- `MATERIAL[prop=val,prop2=val2]` — Match only when block-state properties match.
- `#namespace:tag` — Expands to every material in that block tag (e.g., `#minecraft:logs`).
- `#namespace:tag[prop=val]` — Tag members only when properties match.
- `*[prop=val]` — ANY material when properties match (wildcard, e.g., `*[waterlogged=true]`).

### `airBlocks`
Materials treated as walkable / non-blocking space above the landing.
- Includes: `AIR`, `VOID_AIR`, `#minecraft:flowers`, `#minecraft:saplings`, `#minecraft:crops`, `#minecraft:leaves` (to allow landing beneath canopies), etc.

### `unsafeBlocks`
Materials the plugin will reject as a landing target.
- Includes: `LAVA`, `WATER`, `*[waterlogged=true]`, `MAGMA_BLOCK`, `CACTUS`, `#minecraft:logs` (to prevent landing on top of trees), etc.

---

## Biome Filter

| Key | Type | Default | Description |
|---|---|---|---|
| `biomeWhitelist` | Boolean | `false` | If `true`, the `biomes` list is a **whitelist**. If `false`, it is a **blacklist**. |
| `biomes` | List | *(Ocean, etc.)* | Biomes to skip or allow. Default blacklist excludes oceans, rivers, and void biomes. |

---

## Versioning
- `version`: Internal config version (do not change).
