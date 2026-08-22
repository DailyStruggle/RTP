# Message Customisation Reference (`advanced/messages/`)

User-facing and console-facing messages in RTP are organised across categorical message files located in `advanced/messages/`. Edit these files to reword, recolour, or rebrand any message.

---

## Updating Messages

You can update messages through:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Messages**.
2. **Command line**: Use `/rtp config messages <key>=<value>`.
3. **Direct editing**: Edit files under `advanced/messages/` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

---

## Message Files

| File | Contents |
|---|---|
| `player.yml` | Player-facing teleport flow: delay, arrival, cancel, cooldown, lockout, queue notifications, economy cost/failure. |
| `commands.yml` | Command descriptions, help entries, command-line parameter error messages, and menu interface rows. |
| `system.yml` | System notices: reload, startup/shutdown, permissions, admin inspection, and administrative audit logs. |
| `network.yml` | Multi-server and proxy network teleport messages, routing notices, and cross-server queue messages. |
| `placeholders.yml` | Shared placeholder definitions and time unit formatting. |

---

## Formatting

Message values accept, in any combination:

| Form | Example | Notes |
|---|---|---|
| Bukkit legacy color codes | `&a`, `&e`, `&c` | Supported on every platform. |
| Hex color codes | `#2636ef` | |
| MiniMessage tags | `<gradient:#5e4fa2:#f79459>`, `<rainbow>`, `<#rrggbb>` | Full support on Paper/Folia. On Spigot, `gradient`/`rainbow`/`transition` are expanded to per-character hex; other tags are stripped by the legacy pipeline. |
| PlaceholderAPI placeholders | `%player_name%` | Resolved only when PlaceholderAPI is installed. |

## Placeholders

- **Shared `[Pn]` placeholders** are defined in the `placeholders:` list inside `advanced/messages/placeholders.yml`. Every `[Pn]` placeholder corresponds to the entry at index `n` of that list (`[P0]` is the 0th item, `[P1]` is the 1st item, and so on).
- **Per-message placeholders** (e.g. `[attempts]`, `[world]`, `[delay]`, `[remainingCooldown]`, `[money]`, `[spot]`, `[filename]`) are documented in comments directly above each message that uses them. Only the placeholders listed for a given key are substituted in that message.

## Notes

- **Icon glyphs are intentional.** Some values embed single-codepoint UI icons (type-a-value, paginator arrows, run/row markers, the section sign). Preserve them verbatim; do not ASCII-fold or delete them.
- **Never change `version`** - it is used internally for config migration.
