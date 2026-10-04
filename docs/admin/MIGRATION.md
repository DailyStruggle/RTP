# Migration Guide

**Current Plugin Version:** `@version@`

This document provides upgrade instructions for server operators and addon developers when moving between RTP versions.

> 📎 For the **mechanics** of how RTP upgrades on-disk YAML files (what `.old1`/`.old2` files are, how your customizations are read into memory before the file is replaced, and how they are overlaid onto the new defaults), see [CONFIG_LIFECYCLE.md](configuration/CONFIG_LIFECYCLE.md).

---

## Upgrading to 3.x (Current Release: 3.3.0)

> ⚠️ **Major Version Architecture:** The 3.x series introduces fundamental architectural modernization over legacy 2.x releases. This includes breaking `rtp-api` changes, a streamlined tiered configuration directory layout (ADR-076), unified unit parsing, space-filling Hilbert curve spatial memory persistence (ADR-085, ADR-088), pluggable Bare-`/rtp` root action menus (ADR-056), and multi-platform support (Bukkit, Paper, Folia, Fabric, NeoForge, and Velocity/BungeeCord proxy clusters).

---

### Key Upgrade Pathways at a Glance

| If upgrading from... | Recommended path & primary actions |
|----------------------|-------------------------------------|
| **Legacy 2.x (e.g. 2.0.18 or earlier)** | Full major upgrade. Review breaking API changes, rename permissions/commands (`rtp.fill` -> `rtp.scan`, parameter delimiter `:` -> `=`), remove PaperLib on Paper servers, verify tiered configuration directory migration, and allow cache files to upgrade automatically. |
| **Early 3.0.x / 3.0.0-beta.x** | Update command syntax to `key=value`, adjust `uniquePlacements` (now chunk radius integer rather than boolean), review bundled add-on jar auto-extraction (`LeafRTPGuiAddon`, `LeafRTPClaimAddon`), and check `rtp.admin` permission umbrella. |
| **3.1.x** | Note that in 3.2.0+ bare `/rtp` opens the GUI picker by default if an inventory/menu renderer is available; claim integrations moved to bundled `LeafRTPClaimAddon` (`plugins/RTP/addons/LeafRTPClaimAddon.jar`); `LINEAR` vertical search defaults to middle-out (`direction: 2`) with sky light requirement (`vert.requireSkyLight: true`). |
| **3.2.x** | Config files and definitions are organized in tiered folders (`definitions/`, `advanced/`, `addons/`). Database configuration lives in `advanced/database.yml`, biome weighting in `advanced/biomes.yml`, and messages in `advanced/messages/*.yml`. Spatial memory `.bin` cache auto-migrates to Format Version 5 with continuous Hilbert curves and segment TTLs (`BIN_VERSION 3`). |

---

## Detailed Version Upgrade Notes

### Upgrading to 3.3.0 (from 3.2.x or earlier)

1. **Network Mode Permissions (`rtp.servers.*`):**
   - The permission node `rtp.servers.*` now defaults to `true` (previously `op`) in `plugin.yml`. In network/proxy mode, every player can reach open cross-server regions by default.
   - If your network configuration relied on `rtp.servers.*` defaulting to operators only, configure explicit negative permissions (e.g. `-rtp.servers.<server>` or `-rtp.servers.*`) in your permissions manager.
   - Cross-server peer regions only require `rtp.regions.<region>` if the owning backend explicitly configured `requirePermission: true`.

2. **Spatial Memory Persistence (Format Version 5 & `BIN_VERSION 3`):**
   - Spatial memory `.bin` files and region cache files now support continuous spiral-addressed Hilbert curves (`CIRCLE_OPTIMIZED_DUAL_LAYER`, `SQUARE_OPTIMIZED_DUAL_LAYER`) with dynamic 4-byte/8-byte chunk address keys and cause-based expiration epochs.
   - Existing cache files are automatically upgraded in place on first load with upward ratchet migration.
   - *Rollback note:* Older RTP versions (3.1.x and earlier) cannot read Format 5 or `BIN_VERSION 3` binary files. Always make a backup of `plugins/RTP/` before upgrading in case you need to roll back.

3. **In-Game Packed Documentation & Offline HTML Export (`/rtp docs`):**
   - Introduces `/rtp docs [topic]`, `/rtp docs list`, and `/rtp docs export` under permission `rtp.admin.docs`.
   - Operators can browse version-matched manuals in-game or export self-contained offline documentation bundles (`docs-bundle.html`).

4. **Tag-Group Set Subtraction in `safety.yml`:**
   - Block safety lists support subtraction expressions (e.g. `#minecraft:slabs - OAK_SLAB`, `#minecraft:leaves - AZALEA_LEAVES`).
   - Existing syntax and unmodified lists remain fully backward-compatible.

5. **Unified Unit Parsing:**
   - Configuration files and command parameters accept explicit units for lengths/distances (`b`/`blocks`, `c`/`chunks`, `r`/`regions`), durations (`t`, `ms`, `s`, `m`, `h`, `d`, composite `2h30m`), and memory sizes (`kib`, `mib`, `gib`). Plain unitless numbers remain backward-compatible and auto-interpreted in context.

---

### Upgrading to 3.2.x (from 3.1.x or earlier)

1. **Bare `/rtp` Opens GUI Menu by Default (ADR-056):**
   - `LeafRTPGuiAddon` is bundled in the RTP jar and self-extracts into `plugins/RTP/addons/` on first startup when the folder does not exist.
   - By default, executing `/rtp` without arguments now opens the visual destination-picker GUI menu on platforms where a menu renderer is available (Paper, Folia, Fabric, NeoForge).
   - *To restore instant teleportation on `/rtp`:* Delete `plugins/RTP/addons/LeafRTPGuiAddon.jar` (the `addons/` directory prevents re-extraction) or configure the bare root action in `config.yml`. The classic command remains directly accessible via `/rtp teleport` or menu items.

2. **Tiered Configuration Directory Layout (ADR-076):**
   - The plugin data directory is reorganized into clean, tiered subdirectories:
     - Root: everyday operational files (`config.yml`, `economy.yml`, `language.yml`, `safety.yml`).
     - `definitions/`: authored game content (`definitions/regions/`, `definitions/worlds/`, `definitions/effects/`, `definitions/actions/`).
     - `advanced/`: infrastructure and tuning (`advanced/database.yml`, `advanced/biomes.yml`, `advanced/performance.yml`, `advanced/logging.yml`, `advanced/metrics.yml`, `advanced/ttl.yml`).
     - `advanced/messages/`: modular localization files (`commands.yml`, `network.yml`, `placeholders.yml`, `player.yml`, `system.yml`).
     - `addons/`: bundled and external addon configurations (`addons/countdown.yml`, `addons/guimenu.yml`, `addons/integrations.yml`).
   - *Automatic relocation:* On first load, RTP automatically relocates existing flat files to their new tiered paths without overwriting files that already exist.
   - *Manual settings check:* If you customized database settings in `config.yml` or biome weights in `performance.yml`, verify those values in `advanced/database.yml` and `advanced/biomes.yml`.

3. **Claim Integrations Extracted to Bundled Addon (ADR-069):**
   - Claim and land-protection integrations (WorldGuard, GriefPrevention, Towny, Lands, etc.) have moved from the core plugin into the bundled `LeafRTPClaimAddon` (`plugins/RTP/addons/LeafRTPClaimAddon.jar`).
   - Claim protection remains active out of the box with zero configuration required.

4. **Surface Teleport Defaults (`LINEAR` Middle-Out & Sky Light):**
   - Overworld vertical adjustment defaults to `LINEAR` with middle-out scan (`direction: 2`) and `requireSkyLight: true` (`vert.requireSkyLight: true`) in default region templates.
   - Eliminates players landing in roofed caves or dark overhangs while preserving Nether/End roof ceilings.

---

### Upgrading to 3.1.x (from 3.0.x)

1. **`uniquePlacements` is now an Integer Chunk Radius:**
   - The `uniquePlacements` parameter on memory shapes changed from a boolean flag to an integer chunk radius (default `0` = off).
   - Setting `uniquePlacements: 3` clears a square area of chunks around previous teleport spots to spread players out.
   - Legacy boolean values (`true`/`false`) in older configs automatically coerce to `1` and `0` without breaking.

2. **PvP Combat Gate (ADR-055):**
   - Optional combat gate in `safety.yml` (`pvpCheckEnabled: false` by default). When enabled, prevents players in active combat from teleporting, supporting native damage tracking or soft-depend hooks (PvPManager, CombatLogX, SimpleCombatLog).

3. **Unified Admin Tooling (`rtp.admin` & `/rtp clear`):**
   - The master permission node `rtp.admin` now groups administrative sub-permissions (`rtp.reload`, `rtp.config`, `rtp.scan`, `rtp.info`).
   - `/rtp clear <cache|cooldown|queue|limit|invuln>` provides single-shot cache and state purging.

---

### Upgrading from Legacy 2.x to 3.x

If you are upgrading from 2.0.18 or earlier:

1. **Command & Permission Renames:**
   - `/rtp fill` is now `/rtp scan` (permission `rtp.scan`).
   - Command parameter syntax uses key-value equations (e.g. `/rtp world=world region=default` rather than colon syntax `world:world`).
2. **PaperLib Removal (Paper & Folia Servers):**
   - `rtp-paper` and `rtp-folia` use native asynchronous chunk scheduling directly.
   - If PaperLib was installed in `plugins/` solely for RTP, remove `PaperLib.jar`.
3. **Platform Additions:**
   - First-class native support is available for Spigot, Paper, Folia, Fabric (via mod jar), and NeoForge.
4. **Cache & Database Ingestion:**
   - Legacy 2.x `.bin` files and spatial memory rows are ingested cleanly. If you desire a full reset for fresh terrain shapes, run `/rtp scan reset`.

---

### Addon Developers (`rtp-api` Breaking Changes)

Addons built against `rtp-api` 2.x must recompile against 3.x:

1. **`ChunkReservation`:** Chunk ticket lifecycles are managed via `ChunkReservation` (implements `AutoCloseable`). Replace manual ticket code with try-with-resources.
2. **`CachedLocation`:** Refactored into an immutable Java `record`. Construct new instances instead of mutating fields.
3. **Menu & Addon SPIs:** New platform-agnostic `RTPAddon` interface and ServiceLoader-based `AddonRegistry` allow addons to run across Bukkit, Paper, Folia, Fabric, and NeoForge without platform imports.
4. **Personal Queue API (ADR-043):** Deprecated `Region.queue(UUID)` is replaced by `openPersonalQueue(UUID)` (opt-in bucket) and `requestTeleport(UUID)` (waitlist enqueue).

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

RTP integrates non-destructive permission migration directly into `/rtp config import`. When importing configs, competitor permission nodes assigned to groups in your permissions provider (such as LuckPerms) are automatically mapped and applied (unless `permissions=false` is explicitly passed).

#### Command Syntax
```
/rtp config import [source] [overwrite=true|false] [permissions=true|false] [path=<dir>]
/rtp config import permissions [source] [apply=true|false]
```
- **Unified Import (`/rtp config import`)**:
  - `[source]`: Competitor plugin to import (`betterrtp`, `justrtp`, `ezrtp`, `jakesrtp`), or omit to auto-detect.
  - `[overwrite]`: When `true`, writes configuration files and applies permission changes to your permission provider. Defaults to `false` (dry-run preview for both config and permissions).
  - `[permissions]`: Defaults to `true`. Pass `permissions=false` or `--no-permissions` to skip permission migration.
- **Standalone Permission Subcommand (`/rtp config import permissions`)**:
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
