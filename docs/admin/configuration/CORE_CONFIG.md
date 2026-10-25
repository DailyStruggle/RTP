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
| `teleportDelay` | Integer / Duration | `2` | Wait time before teleport occurs. Accepts raw seconds (`2`) or duration units (e.g. `2s`, `40t`, `1500ms`). `0` teleports immediately (skips the cancel-on-move window). Serves as the global fallback when a region does not configure an explicit `delay`. |
| `cancelDistance` | Integer | `2` | Max distance (blocks) a player can move during the delay before cancellation. |
| `teleportCooldown` | Integer / Duration | `300` | Wait time between successful RTP uses. Accepts raw seconds (`300`) or duration units (e.g. `5m`, `300s`, `1h`). Bypassed by `rtp.nocooldown`. Serves as the global fallback when a region does not configure an explicit `cooldown`. |
| `lockAfterUses` | Integer | `0` | Max successful `/rtp` uses within the `lockAfterResetSeconds` window before lockout (BetterRTP `LockAfter` parity). `0` disables. Bypassed by `rtp.nolock`. |
| `lockAfterResetSeconds` | Integer / Duration | `0` | Length of the rolling usage-cap window for `lockAfterUses`. Accepts raw seconds or duration units (e.g. `24h`, `1d`). `0` means the cap never resets (a hard lifetime cap). |
| `setRespawnOnTeleport` | Boolean | `false` | When `true`, a successful `/rtp` sets the landed location as the player's persistent spawn anchor (BetterRTP `SetAsRespawn` parity). |

### Duration Unit Suffixes & Parsing

Any duration setting (such as `teleportCooldown`, `teleportDelay`, `lockAfterResetSeconds`, cache TTLs in `advanced/ttl.yml`, the timing keys in [`advanced/performance.yml`](PERFORMANCE.md#duration-values) and [`safety.yml`](SAFETY.md#duration-values), and the `*Ms` / `maxAgeDays` keys in [`advanced/network.yml`](../proxies/CONFIGURATION.md#duration-values)) accepts human-readable **temporal unit suffixes** as well as composite strings:

- **Game Ticks:**
  - `t`, `tick`, `ticks`: Minecraft game ticks (1 tick = 50ms = 0.05s, 20 ticks = 1s). E.g. `teleportDelay: 40t` (2 seconds).
- **Metric & Standard Units:**
  - `ms`, `milli`, `millis`, `millisecond`, `milliseconds`: Milliseconds (1,000 ms = 1s). E.g. `teleportDelay: 2500ms` (2.5 seconds).
  - `s`, `sec`, `second`, `seconds`: Seconds. E.g. `teleportCooldown: 300s`.
  - `m`, `min`, `minute`, `minutes`: Minutes (60s). E.g. `teleportCooldown: 5m`.
  - `h`, `hr`, `hour`, `hours`: Hours (3,600s). E.g. `teleportCooldown: 1h`.
  - `d`, `day`, `days`: Days (86,400s). E.g. `lockAfterResetSeconds: 1d`.
  - `w`, `week`, `weeks`: Weeks (604,800s). E.g. `uniquePlacement: 4w`.
- **Composite Durations:**
  - You can combine multiple units without punctuation: e.g. `teleportCooldown: 2h30m`, `1d12h`, `1m30s`, `45s500ms`.
- **Special Values:**
  - `-1`, `infinite`, `permanent`: Infinite / no expiration (used in TTL configs).
- **Auto-Interpretation of Dimensionless Values:**
  - Plain numeric values (e.g. `300`) maintain 100% backward compatibility with their historical unit defaults (seconds for cooldowns/delays, ticks for queue intervals).
  - Unusually large dimensionless numbers entered where seconds or ticks are expected (such as entering `5000` assuming milliseconds) are auto-detected and safely interpreted with a helpful console notice. Explicit suffixes (e.g. `5000ms` or `5s`) are always recommended.

## Commands

List of commands to execute after a successful teleport. Use the `[player]` placeholder for the player's name.

- `consoleCommands`: Executed by the server console.
- `playerCommands`: Executed by the player.

## Menu

Configures the menu interface. Nested under the `menu:` block.

| Key | Type | Default | Description |
|---|---|---|---|
| `renderer` | List | `[ "book" ]` | Ordered preference list of renderer ids. On exception or a missing adapter the framework walks the list and falls back to the next entry. Available ids: `book`, `chat`. If the list is exhausted, the no-token open-page path falls back to the configurable `menuInvalid` message. |

## Defaults (starter fallback inheritance)

Nested under the `defaults:` block. Holds starter fallback templates and values that region/world files inherit when left at `"@config"`.

> **Recommended workflow (`/rtp editor` and `definitions/regions/` first):** `config.yml` holds basic global timers (`teleportDelay`, `teleportCooldown`, `cancelDistance`) and starter fallback defaults so a fresh install works out of the box. When configuring your server's teleport areas, **start with `/rtp editor` (the browser map workspace), `/rtp admin setup`, and `definitions/regions/<name>.yml` (`shape` and `vert` settings)** rather than treating `config.yml` as the primary place to configure region geometry.

| Key | Type | Default | Description |
|---|---|---|---|
| `shape` | Block | (`CIRCLE` block) | Starter fallback shape block inherited whole by a region whose `shape` is still `"@config"`. Holds `radius` (default `4096b` / `256c`) and `centerRadius` (default `64` chunks / `1,024` blocks). Prefer setting `shape` per region via `/rtp editor` or `definitions/regions/<name>.yml`. See [REGIONS.md → Region Size](REGIONS.md#region-size-radius-and-centerradius). |
| `vert` | Block | (`LINEAR` block) | Starter fallback vertical adjustor block (`minY`, `maxY`, `requireSkyLight`, `maxHillAngle`) inherited whole by a region whose `vert` is still `"@config"`. Prefer configuring `vert` per region in `/rtp editor` or `definitions/regions/<name>.yml` (especially for Nether `maxY: 120`, `requireSkyLight: false`). |
| `cacheCap` | Integer | `50` | Default region `cacheCap` (max pre-calculated safe locations). |
| `backlogCacheCap` | Integer | `1000` (lite: `0`) | Default region `backlogCacheCap` (L3 backlog buffer; `0` disables). |
| `activeChunkCap` | Integer | `10` | Default region `activeChunkCap` (chunks kept loaded for zero-latency). |
| `spatialResolution` | Integer / String | `"auto"` | Default region `spatialResolution` (bad-location tracking precision, positive integer or `"auto"`). |
| `requirePermission` | Boolean | `false` | Default `requirePermission` for regions/worlds. |
| `cooldown` | Integer / Duration | (global `teleportCooldown`) | Default region `cooldown` inherited by regions configuring `cooldown: "@config"`. If omitted from `defaults:`, falls back to global `teleportCooldown`. |
| `delay` | Integer / Duration | (global `teleportDelay`) | Default region `delay` inherited by regions configuring `delay: "@config"`. If omitted from `defaults:`, falls back to global `teleportDelay`. |

> The type-bearing `shape`/`vert` settings inherit as a **whole named block**, while type-free scalars (`cacheCap`, `backlogCacheCap`, `activeChunkCap`, `spatialResolution`, `requirePermission`, `cooldown`, `delay`) inherit individually. Other source files own their own defaults: e.g. a region's `price` can reference `@economy` to inherit from [economy.yml](ECONOMY.md).

> **Configuring teleport distance and vertical bounds:** Use `/rtp editor`, `/rtp admin setup`, or `/rtp config regions <region> shape.radius=<value>` to configure `definitions/regions/<region>.yml` directly. Sizes default to **chunks** (1 chunk = 16 blocks), or accept explicit spatial unit suffixes such as `4096b` (`4096m`), `256c`, `4r`, or `5km` (prefer explicit unit suffixes on `shape.radius` rather than enabling `worldBorderOverride: true`). Full reference: [REGIONS.md → Region Size](REGIONS.md#region-size-radius-and-centerradius).

---

## Related Files
- Database persistence settings: [`advanced/database.yml`](CONFIGURATION.md#database-persistence-advanceddatabaseyml).
- Multi-server network settings: [`advanced/network.yml`](../proxies/CONFIGURATION.md).

---

## Versioning
- `version`: Internal config version (do not change).
