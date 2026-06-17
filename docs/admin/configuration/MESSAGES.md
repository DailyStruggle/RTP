# Message Customisation Reference (`messages.yml`)

`messages.yml` holds **every** player- and console-facing string RTP emits. Edit it to reword, recolour, or rebrand any message. Read it before requesting formatting support: most "can this text be changed?" questions are answered by a single key here.

Apply changes by editing on disk and running `/rtp reload`, or change one key at runtime with `/rtp config messages <key>=<value>`.

> When a non-English locale is active (see [LANGUAGE.md](LANGUAGE.md)), the translated `messages.yml` is read from `lang/<code>/` instead of this baseline file.

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

- **Shared `[Pn]` placeholders** are defined in the `placeholders:` list at the top of the file. `[P0]` is the plugin prefix (a rainbow `[RTP]` by default) and is reused in nearly every message. Append entries to the list to define `[P1]`, `[P2]`, and so on.
- **Per-message placeholders** (e.g. `[attempts]`, `[world]`, `[delay]`, `[remainingCooldown]`, `[money]`, `[spot]`, `[filename]`) are documented in a comment directly above each message that uses them. Only the placeholders listed for a given key are substituted in that message.

## File layout

The file is split into commented sections so related strings stay together:

| Section | Contents |
|---|---|
| 1. General Configuration | The shared `placeholders` list. |
| 2. Teleportation | Player-facing teleport flow: delay, success, cancel, cooldown, lockout, no-locations-queued, queue position, not-enough-money. |
| 3. Time Formatting | Unit suffixes for time placeholders (`days`, `hours`, `minutes`, `seconds`, `millis`). |
| 4. System & Management | Reload, version-mismatch, permission-denied, and management strings. |
| 5. Administrative Logs | Console audit / log lines. |
| 6. Scan Command | `/rtp scan` progress and result strings. |
| 7. Info Command | `/rtp info` output. |
| 8 / 8b. Command Descriptions & Menu Framework | Help text and the generalized book/chat menu rows, headers, and dividers. |
| 9. PlaceholderAPI Support | PAPI-specific strings. |
| 10. Visuals | Visualization-related strings. |
| 11. Metadata | Internal `version` marker. |
| 12. Developer Options | Developer-facing toggles / strings. |

## Notes

- **Icon glyphs are intentional.** Some values embed single-codepoint UI icons (type-a-value, paginator arrows, run/row markers, the section sign). Preserve them verbatim; do not ASCII-fold or delete them.
- **Locale parity.** A custom key added to this baseline must be mirrored into every shipped locale under `lang/<code>/messages.yml`, or that locale silently falls back to English. See the [Translation Guide](../../dev/TRANSLATION_GUIDE.md).
- **Never change `version`** - it is used internally for config migration.
