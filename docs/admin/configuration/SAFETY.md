# Safety Configuration Reference (`safety.yml`)

This document provides a detailed reference for all configuration options available in `plugins/RTP/safety.yml`.

---

## Updating Settings

You can update safety settings through:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Safety**.
2. **Command line**: Use `/rtp config safety <key>=<value>` (e.g. `/rtp config safety invulnerabilityTime=10`).
3. **Direct editing**: Edit `safety.yml` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

---

## Duration Values

Keys marked **Integer / Duration** below accept a plain number of seconds or a duration with unit suffixes (e.g. `100t`, `30s`, `10m`, `24h`, `1d`, or composites such as `1m30s`). A suffixed value is converted from its own units, so `invulnerabilityTime: 100t` is 5 seconds and `pvpCombatTagSeconds: 1m` is 60 seconds. Sentinels keep their meaning: `platformRestoreSeconds: -1` still disables restoration, and `0` keeps its documented meaning for each key. Full suffix list: [CORE_CONFIG.md -> Duration Unit Suffixes & Parsing](CORE_CONFIG.md#duration-unit-suffixes--parsing).

---

## Landing Protection

| Key | Type | Default | Description |
|---|---|---|---|
| `invulnerabilityTime` | Integer / Duration | `5` | Seconds of invulnerability granted after teleporting. Prevents **fall**, fire, or **drowning** damage on arrival. `0` **disables**. |
| `safetyRadius` | Integer | `0` | Block radius around the landing point to check for hazards. `0` = check only the landing block. |
| `staleChunkRetryLimit` | Integer | `2` | Bounded retry budget for the stale-chunk guard. Prevents race conditions where a chunk unloads before evaluation. |
| `anvilPrefilterEnabled` | Boolean | `true` | Off-thread region prefilter: screens candidate destinations by reading region files (`.mca` Anvil format, plus any format an addon registers) from disk, dropping known-unsafe ones before they cost a chunk load. A chunk it cannot read (e.g. not yet generated, or stored in an unregistered format such as `.linear`) is not rejected - it passes through to the normal live-load safety check. |

## Landing Platforms

Used as a legacy fallback if no solid ground is found.

| Key | Type | Default | Description |
|---|---|---|---|
| `platformRadius` | Integer | `-1` | Radius of the emergency platform. Set to `-1` to disable (recommended). |
| `platformDepth` | Integer | `1` | Depth (downward) of the platform. |
| `platformAirHeight` | Integer | `2` | Height of air to ensure above the platform. |
| `platformMaterial` | String | `GLASS` | Block type used if no solid block exists. |
| `platformRestoreSeconds` | Integer / Duration | `-1` | Optional timeout after which a built platform's footprint is restored to its original blocks. `-1` disables restoration (permanent platform). `0` restores as soon as the footprint chunk is loaded again; a positive value restores after that many seconds. The countdown only advances while the footprint chunk is loaded (it pauses while the area is unloaded), and pending restores survive a server restart. |

> **Note on `platformRestoreSeconds`.** Restoration writes the captured original blocks back; every restore (and any failure) is logged, never silently dropped. Available on the Bukkit/Spigot/Paper and Folia platforms; the Fabric platform does not build emergency platforms, so the timeout has no effect there. Because the platform is only ever built on land the safety pipeline already cleared of claims, the footprint starts in unclaimed terrain; if you expect players to build on top of the temporary platform before it expires, leave restoration disabled (`-1`).

---

## PvP / Combat Checks

Configure whether players can teleport while in combat.

| Key | Type | Default | Description |
|---|---|---|---|
| `pvpCheckEnabled` | Boolean | `false` | When `true`, checks if a player is in combat before teleporting. |
| `pvpCombatTagSeconds` | Integer / Duration | `15` | Duration (seconds) a player remains in combat after a PvP hit (built-in tracker). |
| `pvpOnCombat` | Enum | `"DENY"` | Action taken when a player in combat attempts `/rtp`. Options: `ALLOW`, `DENY`, `DELAY`, `CANCEL`. |
| `pvpSource` | Enum | `"AUTO"` | Source for combat state. Options: `AUTO` (external plugin if present, else native), `NATIVE` (built-in tracker only), `EXTERNAL` (plugin hook only). |
| `pvpTagVictim` | Boolean | `true` | Built-in tracker: tag the player taking damage. |
| `pvpTagAggressor` | Boolean | `true` | Built-in tracker: tag the attacking player. |

---

## Block Filters (`advanced/blocks.yml`)

Block filter settings are configured in [`advanced/blocks.yml`](../../admin/configuration/CONFIGURATION.md#blocks-advancedblocksyml).

The `airBlocks` and `unsafeBlocks` lists use a specific grammar to match blocks and properties.

> **Editions.** The full grammar below, including block tags and block-state predicates, works identically in the free and Pro builds. Tags resolve against the server's live block-tag registry when the config loads.

### Grammar (Token Syntax)

Six token shapes can be mixed freely in `unsafeBlocks` and `airBlocks` (`advanced/blocks.yml`):

- **Plain material (`MATERIAL`):** `LAVA`, `MAGMA_BLOCK`.
- **Material + state predicate (`MATERIAL[prop=val,prop2=val2]`):** `OAK_SLAB[waterlogged=true]`. Multiple predicates AND together: `OAK_SLAB[waterlogged=true,type=top]`.
- **Numeric range predicate (`MATERIAL[prop>=n]`):** `WATER[level>=5]`, `LIGHT[level<8]`, `LAVA[level<=3]`, `FIRE[age>=10]`. Operators: `>=`, `<=`, `>`, `<` (bound `n` must be a whole number). Fails open on a missing or non-numeric value.
- **Vanilla or datapack block tag (`#namespace:tag`):** `#minecraft:leaves`, `#minecraft:fire`, `#minecraft:campfires`, `#minecraft:logs`. Expanded from the server's live block-tag registry when the config loads; datapack and modded tags work too.
- **Tag + state predicate (`#namespace:tag[prop=val]`):** `#minecraft:slabs[waterlogged=true]`.
- **Wildcard + state predicate (`*[prop=val]`):** `*[waterlogged=true]` - one line instead of listing every waterloggable block.

#### Predicate notes
- Multiple predicates inside one `[ ... ]` combine with logical **AND** (e.g., `WATER[falling=true,level>=5]`).
- Two range bounds on the same key form an interval, e.g., `LAVA[level>=2,level<=5]`.
- Equality (`=`) compares values as case-insensitive strings; the range operators parse both sides as integers. Unknown tags and properties fail open: a config written for a newer MC version still loads on an older one, and a live block whose property is absent or non-numeric is treated as a **miss** (never rejected by a predicate it cannot satisfy).
- Malformed tokens (non-integer bound, empty value, duplicate `key`+operator, unbalanced brackets) are never silent: they are dropped at load with `[WARNING] [advanced/blocks.yml] rejected unsafe-blocks token '<token>': <reason>`.
- Block states are only read when a token needs them, so plain-material configs cost nothing extra. On an older install, lists kept in `safety.yml` are automatically moved into `advanced/blocks.yml` on load, and plain-material entries keep working unchanged.

### `airBlocks`
Materials treated as walkable / non-blocking space above the landing.
- Includes: `AIR`, `VOID_AIR`, `#minecraft:flowers`, `#minecraft:saplings`, `#minecraft:crops`, `#minecraft:leaves` (to allow landing beneath canopies), etc.

### `unsafeBlocks`
Materials the plugin will reject as a landing target.
- Includes: `LAVA`, `WATER`, `*[waterlogged=true]`, `MAGMA_BLOCK`, `CACTUS`, `#minecraft:logs` (to prevent landing on top of trees), etc.

---

## Biome Filters (`advanced/biomes.yml`)

Biome whitelist/blacklist settings are configured in [`advanced/biomes.yml`](../../admin/configuration/CONFIGURATION.md#biomes-advancedbiomesyml).

| Key | Type | Default | Description |
|---|---|---|---|
| `biomeWhitelist` | Boolean | `false` | If `true`, the `biomes` list is a **whitelist**. If `false`, it is a **blacklist**. |
| `biomeWeighted` | Boolean | `false` | If `true`, enables weighted biome selection. |
| `biomes` | List | *(Ocean, etc.)* | Biomes to skip or allow. Default blacklist excludes oceans, rivers, and void biomes. |

---

## Versioning
- `version`: Internal config version (do not change).
