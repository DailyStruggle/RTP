# Core Configuration Reference (`config.yml`)

This document provides a detailed reference for the core settings in `config.yml` (under `plugins/RTP/` on the Bukkit family, or `config/rtp/` on Fabric / NeoForge).

---

## Language

Locale selection now lives in [`language.yml`](LANGUAGE.md) (loaded before `config.yml`). See [LANGUAGE.md](LANGUAGE.md) for the full reference.

## Teleportation Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `teleportDelay` | Integer | `2` | Wait time (seconds) before teleport occurs. `0` teleports immediately (skips the cancel-on-move window). |
| `cancelDistance` | Integer | `2` | Max distance (blocks) a player can move during the delay before cancellation. |
| `teleportCooldown` | Integer | `300` | Wait time (seconds) between successful RTP uses. Bypassed by `rtp.nocooldown`. |
| `lockAfterUses` | Integer | `0` | Max successful `/rtp` uses within the `lockAfterResetSeconds` window before lockout (BetterRTP `LockAfter` parity). `0` disables. Bypassed by `rtp.nolock`. |
| `lockAfterResetSeconds` | Integer | `0` | Length (seconds) of the rolling usage-cap window for `lockAfterUses`. `0` means the cap never resets (a hard lifetime cap). |
| `setRespawnOnTeleport` | Boolean | `false` | When `true`, a successful `/rtp` sets the landed location as the player's persistent spawn anchor (BetterRTP `SetAsRespawn` parity). |

## Commands

List of commands to execute after a successful teleport. Use the `[player]` placeholder for the player's name.

- `consoleCommands`: Executed by the server console.
- `playerCommands`: Executed by the player.

## Database

Where to store cached locations and player data. Nested under the `database:` block.

| Key | Type | Default | Description |
|---|---|---|---|
| `type` | String | `"sqlite"` | Backend type: `yaml`, `sqlite`, `mysql`, or `postgresql`. `yaml`/`sqlite` are file-backed and need no extra config. |
| `host` | String | `"127.0.0.1"` | Database host. Used by `mysql`/`postgresql` only. |
| `port` | Integer | `3306` | Database port. Used by `mysql`/`postgresql` only. |
| `name` | String | `"rtp"` | Database / schema name. Used by `mysql`/`postgresql` only. |
| `username` | String | `"root"` | Database username. Used by `mysql`/`postgresql` only. |
| `password` | String | `"password"` | Database password. Used by `mysql`/`postgresql` only. |

> **Fabric note:** the Fabric build currently supports **flat-file (`yaml`) only**. JDBC drivers (H2, SQLite, MySQL, PostgreSQL) are not bundled and Fabric servers do not ship them on the classpath, so any non-`yaml` `type` falls back to flat-file at startup with a `WARNING` in the server log. Track Fabric platform status in [`MULTI_PLATFORM_PLAN.md`](../../dev/MULTI_PLATFORM_PLAN.md).
>
> **Bukkit-family note:** as of `3.0.0-beta.2`, no JDBC driver is shaded into the RTP jar. To use `h2`/`sqlite`/`mysql`/`postgresql`, drop the corresponding driver jar onto the server classpath; otherwise the handler falls back along requested → `h2` → `yaml` and logs a `WARNING` for each fallback.

## Menu

Generalized menu framework. Nested under the `menu:` block.

| Key | Type | Default | Description |
|---|---|---|---|
| `renderer` | List | `[ "book" ]` | Ordered preference list of renderer ids. On exception or a missing adapter the framework walks the list and falls back to the next entry. Available ids: `book`, `chat`. If the list is exhausted, the no-token open-page path falls back to the configurable `menuInvalid` message. |

## Network (Redis)

The `network.redis` block under `config.yml` enables the Redis-backed network state binding for multi-server synchronization of cached locations.

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | Boolean | `false` | Enable the Redis-backed network state binding. |
| `host` | String | `"127.0.0.1"` | Redis host. |
| `port` | Integer | `6379` | Redis port. |
| `password` | String | `""` | Redis password. Empty string disables auth. |

> **Full multi-server / multi-proxy network mode** (the dedicated `network.yml` file: transports, routing, wait queue, reservation tokens, load balancer) is documented separately in [`proxies/CONFIGURATION.md`](../proxies/CONFIGURATION.md).

## Defaults (inheritance)

Nested under the `defaults:` block. Holds global default templates and values that region/world files can inherit instead of repeating, so a single-world server changes a knob once here rather than editing every region file.

A region/world setting whose value is the token `@config` resolves to the matching entry under `defaults:`; a literal value in the region/world file always wins. References resolve at read time (the file is never rewritten), and an unresolvable reference falls back to a safe built-in default with a `WARNING` in the log.

| Key | Type | Default | Description |
|---|---|---|---|
| `shape` | Block | (`CIRCLE` block) | Default shape block inherited whole by a region whose `shape` is `@config`. |
| `vert` | Block | (`LINEAR` block) | Default vertical adjustor block inherited whole by a region whose `vert` is `@config`. |
| `cacheCap` | Integer | `50` | Default region `cacheCap` (max pre-calculated safe locations). |
| `backlogCacheCap` | Integer | `1000` (lite: `0`) | Default region `backlogCacheCap` (L3 backlog buffer; `0` disables). |
| `activeChunkCap` | Integer | `10` | Default region `activeChunkCap` (chunks kept loaded for zero-latency). |
| `spatialResolution` | Integer | `3` | Default region `spatialResolution` (bad-location tracking precision, 1-5). |
| `requirePermission` | Boolean | `false` | Default `requirePermission` for regions/worlds. |

> The type-bearing `shape`/`vert` settings inherit as a **whole named block** (a per-type parameter set is only coherent as a unit), while type-free scalars inherit individually. Other source files own their own defaults: e.g. a region's `price` can reference `@economy` to inherit from [economy.yml](ECONOMY.md).

---

## Versioning
- `version`: Internal config version (do not change).
