# Console Logging Reference (`advanced/logging.yml`)

`advanced/logging.yml` enables or disables individual console-log categories and sets the plugin's minimum log level. Every category key is a boolean; flip one to `true` to surface that category in the console, or to `false` to silence it.

---

## Updating Settings

You can update logging configuration through:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Logging**.
2. **Command line**: Use `/rtp config logging <key>=<value>` (e.g. `/rtp config logging detailed_reload=true`).
3. **Direct editing**: Edit `advanced/logging.yml` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

---

## Categories

| Key | Type | Default | Logs |
|---|---|---|---|
| `detailed_reload` | Boolean | `false` | Every file reloaded during `/rtp reload`. |
| `detailed_region_init` | Boolean | `false` | Region initialization details. |
| `command` | Boolean | `false` | Player-executed RTP commands. |
| `teleport` | Boolean | `true` | Successful teleport events. |
| `event_changeworld` | Boolean | `true` | RTP triggered by world changes. |
| `event_join` | Boolean | `true` | RTP triggered on join. |
| `event_respawn` | Boolean | `true` | RTP triggered on respawn. |
| `event_move` | Boolean | `true` | Move-event processing. |
| `event_teleport` | Boolean | `true` | Teleport-event processing. |
| `selection_failure` | Boolean | `true` | Location-selection failures. Frequent hits (more than ~1/second) usually indicate broken config or worldgen. |
| `system_memory_tracker` | Boolean | `false` | Debug logs for memory allocator/tracker activity. |
| `system_database` | Boolean | `false` | Debug logs for database queries and connection state. |

## Level filter

| Key | Type | Default | Purpose |
|---|---|---|---|
| `min_level` | Enum | `INFO` | Plugin-scoped minimum log level. The effective filter is the finer of this and the server logger's own level; `ALL` keeps behavior identical to the server logger. Options: `SEVERE`, `WARNING`, `INFO`, `CONFIG`, `FINE`, `FINER`, `FINEST`, `ALL`, `OFF`. |

## Notes

- **Never change `version`** - it is used internally for config migration.
