# Core Configuration Reference (`config.yml`)

This document provides a detailed reference for the core settings in `config.yml` (under `plugins/RTP/` on the Bukkit family, or `config/rtp/` on Fabric / NeoForge).

---

## Updating Settings

You can view and update core settings using:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Admin Panel**.
2. **Command line**: Use `/rtp config <key>=<value>` (for instance, `/rtp config teleportCooldown=60`).
3. **Direct editing**: Edit `config.yml` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

---

## Language

Language selection is configured in [`language.yml`](LANGUAGE.md). See [LANGUAGE.md](LANGUAGE.md) for the full reference.

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

## Menu

Configures the menu interface. Nested under the `menu:` block.

| Key | Type | Default | Description |
|---|---|---|---|
| `renderer` | List | `[ "book" ]` | Ordered preference list of renderer ids. On exception or a missing adapter the framework walks the list and falls back to the next entry. Available ids: `book`, `chat`. If the list is exhausted, the no-token open-page path falls back to the configurable `menuInvalid` message. |

## Defaults (inheritance)

Nested under the `defaults:` block. Holds global default templates and values that region/world files inherit when configured with `@config`.

| Key | Type | Default | Description |
|---|---|---|---|
| `shape` | Block | (`CIRCLE` block) | Default shape block inherited whole by a region whose `shape` is `@config`. Holds the teleport size knobs: `radius` (outer edge, default `256`) and `centerRadius` (inner edge / donut hole, default `64`), both in **chunks**. See [REGIONS.md → Region Size](REGIONS.md#region-size-radius-and-centerradius). |
| `vert` | Block | (`LINEAR` block) | Default vertical adjustor block inherited whole by a region whose `vert` is `@config`. |
| `cacheCap` | Integer | `50` | Default region `cacheCap` (max pre-calculated safe locations). |
| `backlogCacheCap` | Integer | `1000` (lite: `0`) | Default region `backlogCacheCap` (L3 backlog buffer; `0` disables). |
| `activeChunkCap` | Integer | `10` | Default region `activeChunkCap` (chunks kept loaded for zero-latency). |
| `spatialResolution` | Integer | `3` | Default region `spatialResolution` (bad-location tracking precision, 1-5). |
| `requirePermission` | Boolean | `false` | Default `requirePermission` for regions/worlds. |

> The type-bearing `shape`/`vert` settings inherit as a **whole named block**, while type-free scalars inherit individually. Other source files own their own defaults: e.g. a region's `price` can reference `@economy` to inherit from [economy.yml](ECONOMY.md).

> **Teleport distance lives here.** On a single-world server, `defaults.shape.radius` is the one place to set how far `/rtp` throws players, because the bundled `regions/default.yml` ships `shape: "@config"`. Sizes are in **chunks** (1 chunk = 16 blocks), so `radius: 256` is 4,096 blocks and `radius: 625` is 10,000 blocks; `centerRadius` is the matching minimum distance. Full reference, including per-region and per-command overrides: [REGIONS.md → Region Size](REGIONS.md#region-size-radius-and-centerradius).

---

## Related Files
- Database persistence settings: [`advanced/database.yml`](CONFIGURATION.md#database-persistence-advanceddatabaseyml).
- Multi-server network settings: [`advanced/network.yml`](../proxies/CONFIGURATION.md).

---

## Versioning
- `version`: Internal config version (do not change).
