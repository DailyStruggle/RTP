# Migration Guide

**Current Plugin Version:** `@version@`

This document provides upgrade instructions for server operators and addon developers when moving between RTP versions.

> 📎 For the **mechanics** of how RTP upgrades on-disk YAML files (what `.old1`/`.old2` files are, how your customizations are read into memory before the file is replaced, and how they are overlaid onto the new defaults), see [CONFIG_LIFECYCLE.md](configuration/CONFIG_LIFECYCLE.md).

---

## Upgrading to 3.0.0-beta.1

> ⚠️ **This is a MAJOR version release.** The `rtp-api` public interface has breaking changes. Addon developers must recompile against the new `rtp-api` jar and review the source changes listed below.

### Summary of Breaking Changes

| Area | Change | Action Required |
|------|--------|-----------------|
| `rtp.fill` -> `rtp.scan` | The `rtp.fill` permission and `/rtp fill` command have been renamed to `rtp.scan` and `/rtp scan`. | Update your permission plugin (e.g., LuckPerms) to use `rtp.scan` instead of `rtp.fill`. |
| `rtp-api`: `ChunkReservation` added | Chunk ticket lifecycle is now managed via the `ChunkReservation` class (implements `AutoCloseable`) in `rtp-api`. | Addons that previously managed chunk tickets directly must migrate to `ChunkReservation`. |
| `rtp-api`: `CachedLocation` is now a record | `CachedLocation` has been refactored from a mutable class to an immutable Java record. | Any addon code that mutated `CachedLocation` fields directly must be updated to construct a new instance instead. |
| PaperLib removed | The `rtp-paper` adapter no longer depends on PaperLib. Native Paper async chunk APIs are used directly. | Remove PaperLib from your server's `plugins/` folder if RTP was its only consumer. |
| Folia support added | A new `rtp-folia` adapter is available for Folia servers. | Folia operators: use the new `rtp-folia` build. |
| Platform version targets | Spigot, Paper, and Folia targets updated to 26.1. | Ensure your server software is on a 26.1-compatible build. |

### Configuration Files

No configuration keys were renamed, removed, or restructured in 3.0.0-beta.1. Existing `config.yml`, `performance.yml`, `safety.yml`, `economy.yml`, `worlds/`, and `regions/` files are fully forward-compatible — no edits required.

### Database / Spatial Memory Cache

The spatial memory format (the bad-sector index ranges the plugin persists per region) is unchanged. Your existing cache will be read correctly after upgrade — no rebuild required.

If you want a clean slate (e.g., after significantly changing a region's geometry), delete the relevant database entries or run:
```
/rtp scan reset
```

### Addon Developers (`rtp-api` consumers)

This is a **MAJOR** bump. You must recompile your addon against the new `rtp-api` jar. Review the following source-level changes:

1. **`ChunkReservation`** is now part of `rtp-api`. If your addon previously interacted with chunk tickets directly, replace that logic with `ChunkReservation` (use try-with-resources — it implements `AutoCloseable`).
3. **`CachedLocation`** is now an immutable record. Replace any field-mutation code with construction of a new `CachedLocation` instance.
4. All other `rtp-api` interfaces (`RTPEconomy`, `RTPCommandSender`, `RTPPlayer`, `RTPScheduler`, `ILocationGenerator`, `RTPServerAccessor`, `RTPWorld`, `RTPChunk`) remain unchanged.

---

## Upgrading from 2.0.18 to 3.0.0-beta.1

> The changes that shipped in the `2.0.18` tag are now fully documented under [Upgrading to 3.0.0-beta.1](#upgrading-to-300-beta1) above. `2.0.18` was the last 2.x release; its changes (PaperLib removal, Folia adapter, platform target upgrade) were subsequently re-tagged as `3.0.0-beta.1` due to the breaking `rtp-api` changes introduced at the same time. Follow the 3.0.0-beta.1 instructions above.

---

## Upgrading from versions before 2.0.18

Detailed per-commit history is available via `git log`. For versions prior to 2.0.18, consult the [SpigotMC resource page](https://www.spigotmc.org/resources/rtp.94812/) changelog or open a [GitHub issue](https://github.com/DailyStruggle/RTP/issues) for upgrade assistance.

---

## General Upgrade Procedure

1. **Back up** your `plugins/RTP/` folder (configs, database files).
2. **Stop** the server.
3. **Replace** the RTP jar with the new version.
4. **Remove PaperLib** from `plugins/` if upgrading to 3.0.0-beta.1+ on a Paper server and PaperLib was only used by RTP.
5. **Start** the server. RTP will load existing config and cache files automatically.
6. Run `/rtp info` to confirm the new version is active.
7. Run `/rtp reload` if you want to force a full config re-read.

---

## Migrating from Competitor Plugins (BetterRTP, JustRTP, EzRTP, JakesRTP)

RTP provides a non-destructive, one-shot foreign configuration importer and permission migration seam (ADR-066). This allows server administrators migrating from competing plugins to ingest existing world definitions, boundaries, shapes, cooldowns, delays, economy prices, database connections, effect profiles, and portal/lobby trigger zones into LeafRTP without manual translation.

### Overview of Imported Systems

| Source Plugin | Detected Files | Output Target in RTP |
|---------------|----------------|----------------------|
| **BetterRTP** | `plugins/BetterRTP/config.yml` | `regions/<world>.yml`, `advanced/database.yml`, `definitions/effects/imported_betterrtp_*.yml` |
| **JustRTP** | `plugins/justRTP/config.yml`, `custom_locations.yml`, `rtp_zones.yml` | `regions/<world>.yml`, `regions/<id>.yml`, `definitions/actions/zone_<id>.yml`, `advanced/database.yml`, `definitions/effects/imported_justrtp_*.yml` |
| **EzRTP** | `plugins/EzRTP/rtp.yml`, `config.yml`, `limits.yml` | `regions/<world>.yml`, `definitions/effects/imported_ezrtp_*.yml` |
| **JakesRTP** | `plugins/JakesRTP/config.yml`, `rtpSettings/*.yml`, `distributions/*.yml` | `regions/<profile>_region.yml`, `worlds/<world>.yml`, `config.yml`, `performance.yml` |

---

### Step-by-Step Foreign Configuration Import

#### Step 1: Pre-Import Verification
1. Ensure the competitor plugin's configuration directory exists under your server's `plugins/` folder (e.g. `plugins/BetterRTP/`, `plugins/justRTP/`, or `plugins/EzRTP/`).
2. The competitor plugin **does not need to be running or enabled**. RTP reads the files directly from disk.
3. If importing custom directory paths, verify the path is accessible.

#### Step 2: Auto-Detection or Targeted Dry-Run
Run the import command in dry-run mode (default, `overwrite=false`) to preview what files and configurations will be created or mapped:

- **Auto-detection** (probes `plugins/` for competitor folders):
  ```
  /rtp config import
  ```
  *Note:* If multiple competitor folders are present, RTP lists the candidates and requests an explicit source name.

- **Explicit source preview:**
  ```
  /rtp config import betterrtp
  /rtp config import justrtp
  /rtp config import ezrtp
  /rtp config import jakesrtp
  ```

- **Specifying an external or custom directory path:**
  ```
  /rtp config import betterrtp path "C:/backups/plugins/BetterRTP"
  ```

#### Step 3: Review and Confirm (Apply Writes)
By default, the importer writes only files that do not yet exist, preserving your existing configuration. To execute and allow overwriting or updating existing destination configs, supply `overwrite=true`:
```
/rtp config import betterrtp overwrite true
```
Every file touched or written is reported in the console and command sender chat. Any approximations or deferred features are explicitly flagged in warning lines.

---

### Migration Reference by Plugin

#### 1. BetterRTP Migration Guide

**Source Location:** `plugins/BetterRTP/config.yml`

- **World & Region Mapping:**
  - `Default` configuration maps to region defaults.
  - `CustomWorlds` entries generate per-world region files (`regions/<world>.yml`).
  - `Shape: SQUARE` maps cleanly to RTP `SQUARE`.
  - `Shape: CIRCLE` / `ROUND` maps cleanly to RTP `CIRCLE`.
  - `Shape: RECTANGLE` maps to RTP `RECTANGLE`.
  - `MinRadius` and `MaxRadius` map to `minRadius` and `radius`.
  - `CenterX` and `CenterZ` map to `center` coordinates.
- **Timing & Economy:**
  - `Cooldown` maps to `teleportCooldown` (seconds).
  - `Delay` maps to `teleportDelay` (seconds).
  - `Price` maps to `price` in `economy.yml` or region economy configuration.
- **Database Settings:**
  - `Database.Type` (MYSQL, POSTGRESQL, SQLITE), host, port, database name, username, and password map into `advanced/database.yml` without overwriting existing credentials unless requested.
- **Visuals & Audio:**
  - Potion effects (blindness, resistance, slow falling) and teleport titles/sounds map into `definitions/effects/imported_betterrtp_<name>.yml`.
- **Approximations & Differences:**
  - Biome and block lists are mapped into `safety.yml` filter definitions.

#### 2. JustRTP Migration Guide

**Source Locations:** `plugins/justRTP/config.yml`, `custom_locations.yml`, and `rtp_zones.yml`

- **World & Location Mapping:**
  - `worlds` sections map to `regions/<world>.yml`.
  - `custom_locations.yml` targets map to individual custom region configs (`regions/<id>.yml`).
  - Shapes (`ROUND` -> `CIRCLE`, `SQUARE` -> `SQUARE`) and inner/outer radius boundaries translate cleanly.
- **SQL Mirroring:**
  - MySQL / SQLite connection pools in `config.yml` mirror into `advanced/database.yml`.
- **Spatial RTP Zones & Portals:**
  - `rtp_zones.yml` entries convert automatically into declarative Action files: `definitions/actions/zone_<id>.yml`.
  - Trigger bounding boxes (`world`, `pos1`, `pos2`), entry modes (`STEP_IN`, `PORTAL`, `PRESSURE_PLATE`), cooldowns, and wave batch intervals are embedded directly into the action trigger spec.
- **Visual Effects:**
  - Title, sound, action bar, and particle feedback map to `definitions/effects/imported_justrtp_<name>.yml`.

#### 3. EzRTP Migration Guide

**Source Locations:** `plugins/EzRTP/rtp.yml`, `config.yml`, and `limits.yml`

- **Region Mapping:**
  - `rtp.yml` and `config.yml` per-world settings map to `regions/<world>.yml`.
  - Radius (`min`, `max`), center offsets, and world lists map to RTP region parameters.
- **Limits & Cooldowns:**
  - `limits.yml` cooldown and delay settings map into core timing configurations.
- **Arrival Effects:**
  - Arrival sounds and potion buffs translate to `definitions/effects/imported_ezrtp_*.yml`.

#### 4. JakesRTP Migration Guide

**Source Locations:** `plugins/JakesRTP/config.yml`, `rtpSettings/*.yml`, and `distributions/*.yml`

- **Settings & Distributions Mapping:**
  - Each `rtpSettings/<profile>.yml` translates to a dedicated region file (`regions/<profile>_region.yml`) and links to the landing world (`worlds/<world>.yml`).
  - Distribution files (`distributions/<name>.yml`) map `shape` (`square` -> `SQUARE`, `circle` -> `CIRCLE`, `rectangle` -> `SQUARE` / `RECTANGLE`), `radius.min`/`max` to `centerRadius`/`radius`, and custom center coordinates (`c-custom.x`, `c-custom.z`).
  - Gaussian distribution toggle (`gaussian-distribution.enabled: true`) automatically activates LeafRTP's `NORMAL` spatial distribution shapes (`CIRCLE_NORMAL` or `SQUARE_NORMAL`).
- **Timing, Economy & Bounds:**
  - `cooldown` maps to `teleportCooldown` in `config.yml`.
  - `warmup.time` maps to `teleportDelay` in `config.yml`.
  - `cost` maps to `price` in region definitions.
  - `bounds.low` and `bounds.high` map to `vert.minY` and `vert.maxY`.
  - `max-attempts.value` maps to `performance.yml#maxAttempts`.
  - `preparations.cache-locations` maps to `performance.yml#queue.targetSize`.

---

### Physical Zone Action Configurations (Lobby Portals & Spatial Waves)

Spatial zones imported from JustRTP (or created manually) are defined under `<dataFolder>/definitions/actions/zone_<id>.yml`. These actions use native physical trigger volumes and support wave accumulation, lobby countdown feedback, volumetric particle boundaries, and floating holograms.

#### Example Zone Action (`definitions/actions/zone_lobby.yml`):
```yaml
id: "zone_lobby"
enabled: true

# Physical volume trigger configuration
triggers:
  - type: "STEP_IN" # Options: STEP_IN, PORTAL, PRESSURE_PLATE
    world: "world"
    min: { x: 100, y: 64, z: 100 }
    max: { x: 110, y: 70, z: 110 }
    batchInterval: "15s" # Group wave accumulator: gathers players and dispatches every 15s
    cooldown: "5s"       # Per-player re-entry cooldown

# Spatial rendering and lobby feedback
display:
  wireframe:
    type: "OUTLINE" # Options: OUTLINE, BEAM, SWIRL, PULSE, DUST_WALL
    particle: "VILLAGER_HAPPY"
    render_distance: 48
    max_particles_per_tick: 200
  hologram:
    enabled: true
    title: "&a&lRTP Portal"
    countdown_format: "&eTeleporting wave in &b{seconds}s"
    offset_y: 2.5

# Destination & execution pipeline
placement:
  world: "world"
  shape: "CIRCLE"
  radius: 5000
  minRadius: 500
  minSeparation: 16

lifecycle:
  onEnter:
    sound: "BLOCK_NOTE_BLOCK_PLING"
    actionbar: "&aEntered RTP portal! Teleporting soon..."
  onTeleport:
    sound: "ENTITY_ENDERMAN_TELEPORT"
    title: "&6&lTELEPORTED!"
```

- **Wave Accumulator:** Players inside the trigger box are accumulated. When `batchInterval` elapses, all occupants are dispatched together in a coordinated teleport wave.
- **Volumetric Wireframes:** Boundaries are traced with particle geometry (`OUTLINE`, `BEAM`, `SWIRL`, `PULSE`, or `DUST_WALL`) with distance culling and rate limits to prevent client lag.
- **Holograms:** Floating display entities render above the center of the zone box showing live wave countdowns.

---

### Non-Destructive Permission Migration

RTP includes a non-destructive permission migration service to translate permission nodes assigned to groups in your permissions provider (such as LuckPerms).

#### Command Syntax
```
/rtp config import permissions [source] [apply=true|false]
```
- `[source]`: Optional source filter (`betterrtp`, `justrtp`, `ezrtp`, or `jakesrtp`). If omitted, all supported competitor nodes are scanned.
- `[apply]`: `false` (default) performs a non-destructive **DRY-RUN**, logging planned migrations. `true` executes the appropriate permission set commands directly through your server's command dispatcher.

#### Permission Mapping Table

| Competitor Node | LeafRTP Equivalent | Notes |
|-----------------|--------------------|-------|
| `betterrtp.use` / `ezrtp.use` / `justrtp.use` | `rtp.use` | Base teleport command access |
| `betterrtp.world` / `ezrtp.world` / `justrtp.world` | `rtp.world` | Multi-world teleport access |
| `betterrtp.world.<world>` / `justrtp.world.<world>` | `rtp.worlds.<world>` | Specific world targeting |
| `betterrtp.world.*` / `justrtp.world.*` | `rtp.worlds.*` | Wildcard for all worlds |
| `betterrtp.biome` / `justrtp.biome` | `rtp.biome.*` | Biome targeting access |
| `betterrtp.biome.<biome>` / `justrtp.biome.<biome>` | `rtp.biome.<biome>` | Specific biome targeting |
| `betterrtp.bypass.cooldown` / `ezrtp.bypass.cooldown` | `rtp.noCooldown` | Cooldown bypass |
| `betterrtp.bypass.delay` / `ezrtp.bypass.delay` | `rtp.noDelay` | Teleport delay bypass |
| `betterrtp.bypass.economy` / `betterrtp.bypass.hunger` | `rtp.free` | Free teleportation bypass |
| `betterrtp.player` / `ezrtp.player` / `justrtp.player` | `rtp.other` | Teleporting other players |
| `betterrtp.reload` / `justrtp.admin.reload` | `rtp.reload` | Reloading configurations |
| `betterrtp.admin` / `ezrtp.admin` / `justrtp.admin` | `rtp.admin` | Full administrative access |
| `jakesrtp.use` / `jakesrtp.usebyname` | `rtp.use` | Base teleport command access |
| `jakesrtp.use.<settings>` | `rtp.regions.<settings>` | Specific settings profile access |
| `jakesrtp.nocooldown` | `rtp.noCooldown` | Cooldown bypass |
| `jakesrtp.nowarmup` | `rtp.noDelay` | Warmup delay bypass |
| `jakesrtp.others` / `jakesrtp.forcertp` | `rtp.other` | Teleporting other players |
| `jakesrtp.rtpondeath` | `rtp.onEvent.respawn` | Respawn event random teleportation |
| `jakesrtp.admin` / `jakesrtp.permpack.admin` | `rtp.admin` | Administrative control |

#### Customizing Permission Command Templates (`addons/integrations.yml`)
The permission migration scanner uses configurable command templates to query and mutate groups in your permission plugin. These can be adjusted in `addons/integrations.yml`:

```yaml
permissions:
  command_templates:
    group_list: "lp listgroups"
    group_get: "lp group [group] permission info"
    group_set: "lp group [group] permission set [permission] [value] [contexts]"
    group_unset: "lp group [group] permission unset [permission] [contexts]"
    user_list: "lp listusers"
    user_get: "lp user [user] permission info"
    user_set: "lp user [user] permission set [permission] [value] [contexts]"
    user_unset: "lp user [user] permission unset [permission] [contexts]"
```
- Existing competitor permissions are **never deleted or unset** during migration. LeafRTP appends equivalent nodes alongside the existing nodes, preserving contexts (e.g. `world=survival server=lobby`).
