# Core Configuration Reference (`config.yml`)

This document provides a detailed reference for the core settings in `plugins/RTP/config.yml`.

---

## Language

| Key | Type | Default | Description |
|---|---|---|---|
| `language` | String | `"en"` | Language for user-facing messages. Baseline locales live under `lang/<locale>/messages.yml`. |

## Teleportation Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `teleportDelay` | Integer | `2` | Wait time (seconds) before teleport occurs. |
| `cancelDistance` | Integer | `2` | Max distance a player can move during delay before cancellation. Set `-1` to disable. |
| `teleportCooldown` | Integer | `300` | Wait time (seconds) between successful RTP uses. |

## Commands

List of commands to execute after a successful teleport. Use the `[player]` placeholder for the player's name.

- `consoleCommands`: Executed by the server console.
- `playerCommands`: Executed by the player.

## Database

Where to store cached locations and player data.

| Key | Type | Default | Description |
|---|---|---|---|
| `type` | String | `"sqlite"` | `yaml`, `sqlite`, `mysql`, `postgresql`, or `h2`. |
| `host` | String | — | Database host (for remote types). |
| `port` | Integer | — | Database port. |
| `name` | String | — | Database name. |
| `username` | String | — | Database username. |
| `password` | String | — | Database password. |

> **Fabric note:** the Fabric build currently supports **flat-file (`yaml`) only**. JDBC drivers (H2, SQLite, MySQL, PostgreSQL) are not bundled and Fabric servers do not ship them on the classpath, so any non-`yaml` `type` falls back to flat-file at startup with a `WARNING` in the server log. Track Fabric platform status in [`MULTI_PLATFORM_PLAN.md`](../dev/MULTI_PLATFORM_PLAN.md).
>
> **Bukkit-family note:** as of `3.0.0-beta.2`, no JDBC driver is shaded into the RTP jar. To use `h2`/`sqlite`/`mysql`/`postgresql`, drop the corresponding driver jar onto the server classpath; otherwise the handler falls back along requested → `h2` → `yaml` and logs a `WARNING` for each fallback.

## Network (Redis)

Redis is used for multi-server synchronization of cached locations.

- `enabled`: `false` (default).
- `host`, `port`, `password`: Connection details.

---

## Versioning
- `version`: Internal config version (do not change).
