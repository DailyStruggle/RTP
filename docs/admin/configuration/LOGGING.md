# Console Logging Reference (`logging.yml`)

`logging.yml` enables or disables individual console-log categories and sets the plugin's minimum log level. Every category key is a boolean; flip one to `true` to surface that category in the console, or to `false` to silence it.

Apply changes by editing on disk and running `/rtp reload`, or change one key at runtime with `/rtp config logging <key>=<value>`.

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
