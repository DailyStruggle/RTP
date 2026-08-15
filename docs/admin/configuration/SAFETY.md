# Safety Configuration Reference (`safety.yml`)

This document provides a detailed reference for all configuration options available in `plugins/RTP/safety.yml`.

---

## Landing Protection

| Key | Type | Default | Description |
|---|---|---|---|
| `invulnerabilityTime` | Integer | `5` | Seconds of invulnerability granted after teleporting. Prevents fall, fire, or drowning damage on arrival. |
| `safetyRadius` | Integer | `0` | Block radius around the landing point to check for hazards. `0` = check only the landing block. |
| `staleChunkRetryLimit` | Integer | `2` | Bounded retry budget for the stale-chunk guard. Prevents race conditions where a chunk unloads before evaluation. |
| `anvilPrefilterEnabled` | Boolean | `true` | Off-thread anvil prefilter: screens candidate destinations by reading region files (`.mca`) from disk, dropping known-unsafe ones before they cost a chunk load. A chunk it cannot read (e.g. not yet generated) is not rejected - it passes through to the normal live-load safety check. See [REGION_FILE_READING.md](../REGION_FILE_READING.md). |

## Landing Platforms

Used as a legacy fallback if no solid ground is found.

| Key | Type | Default | Description |
|---|---|---|---|
| `platformRadius` | Integer | `-1` | Radius of the emergency platform. Set to `-1` to disable (recommended). |
| `platformDepth` | Integer | `1` | Depth (downward) of the platform. |
| `platformAirHeight` | Integer | `2` | Height of air to ensure above the platform. |
| `platformMaterial` | String | `GLASS` | Block type used if no solid block exists. |
| `platformRestoreSeconds` | Integer | `-1` | Optional timeout after which a built platform's footprint is restored to its original blocks. `-1` disables restoration (permanent platform). `0` restores as soon as the footprint chunk is loaded again; a positive value restores after that many seconds. The countdown only advances while the footprint chunk is loaded (it pauses while the area is unloaded), and pending restores survive a server restart. |

> **Note on `platformRestoreSeconds`.** Restoration writes the captured original blocks back; every restore (and any failure) is logged, never silently dropped. Available on the Bukkit/Spigot/Paper and Folia platforms; the Fabric platform does not build emergency platforms, so the timeout has no effect there. Because the platform is only ever built on land the safety pipeline already cleared of claims, the footprint starts in unclaimed terrain; if you expect players to build on top of the temporary platform before it expires, leave restoration disabled (`-1`).

---

## Block Filters & Token Grammar

The `airBlocks` and `unsafeBlocks` lists use a specific grammar to match blocks and properties.

> **Edition note (Pro only).** The plain-material rows below work in every edition, but the **block-tag and block-state-predicate grammar** (`#namespace:tag`, `MATERIAL[...]`, `*[...]`, including the numeric range predicates) ships only in the full (Pro) edition. The **rtp-lite** build parses `unsafeBlocks` / `airBlocks` as a **flat material allow/deny list** and does not honour any `#tag` token or `[...]` predicate. See the bundled lite docs (`SAFETY.md` inside the lite jar) for the lite-only surface.

### Grammar (Token Syntax)
- `MATERIAL` — Plain material (e.g., `LAVA`). *(All editions.)*
- **(Pro)** `MATERIAL[prop=val,prop2=val2]` — Match only when block-state properties match (string equality).
- **(Pro)** `MATERIAL[prop>=n]` — Match only when a numeric block-state property satisfies a range comparison. Operators: `>=`, `<=`, `>`, `<`. The bound `n` must be a whole number (e.g., `LAVA[level<=3]` for near-source lava, `FIRE[age>=10]`).
- **(Pro)** `#namespace:tag` — Expands to every material in that block tag (e.g., `#minecraft:logs`).
- **(Pro)** `#namespace:tag[prop=val]` — Tag members only when properties match.
- **(Pro)** `*[prop=val]` — ANY material when properties match (wildcard, e.g., `*[waterlogged=true]`).

#### Predicate notes
- Multiple predicates inside one `[ ... ]` combine with logical **AND** (e.g., `WATER[falling=true,level>=5]`).
- Two range bounds on the same key form an interval, e.g., `LAVA[level>=2,level<=5]`.
- Equality (`=`) compares values as case-insensitive strings; the range operators parse both sides as integers. A live block whose property is absent or non-numeric is treated as a **miss** (fail-open) - it is never rejected by a predicate it cannot satisfy.
- Malformed tokens (non-integer bound, empty value, duplicate `key`+operator, unbalanced brackets) are dropped at load with a single startup `WARNING` and never silently ignored.

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
