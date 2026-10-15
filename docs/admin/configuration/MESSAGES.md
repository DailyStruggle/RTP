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

---

## Useful UI Glyphs & Icons

RTP menus, prompts, and message configurations utilize decorative glyphs and icons to deliver an intuitive, polished in-game interface. Because messages may be displayed both in-game and on the server terminal, consider the target medium when selecting glyphs:

- **In-Game Client Displays (Book Menus, Chat, Titles, Boss Bars, Action Bars)**: Rendered natively by the Minecraft client font engine across all operating systems. Unicode symbols and emojis display cleanly.
- **Console-Facing Messages (Logs, Startup, Console Command Feedback, Background Tasks)**: Displayed in the server host terminal. On Windows servers running legacy OEM code pages (such as Code Page 437 / CP437), multi-byte UTF-8 glyphs can result in mojibake (e.g., `»` rendering as `┬╗`). For messages that output to the console (such as `scanStatus`), use console-safe ASCII alternatives (`>`, `-`, `|`).
- **Book Menu Color Contrast:** Adventure / Paper `Book` pages render on parchment-yellow backgrounds. Never combine glyphs with yellow (`&e`, `&6`) or white (`&f`) in book pages. Use dark high-contrast colors like `#3E6B45` (green), `#1F6FB2` (blue), `#7A4FB8` (purple), `&0` (black), or `&8` (dark gray). Chat messages (`SendMessage`) are exempt.

### Glyph Reference Table

| Glyph | Unicode | Name / Meaning | Recommended Context | Console Safe? | ASCII Alternative | Notes & Best Practices |
|:---:|:---:|---|---|:---:|:---:|---|
| `»` | `U+00BB` | Right double angle quote | Menu dividers, section headers | No | `>` | Used in `/rtp menu` section dividers (`menuFrontPageSectionTeleport`). Avoid in console logs. |
| `«` | `U+00AB` | Left double angle quote | Previous page, return button | No | `<` | Used in paginated menus (`menuPagePrev`, `menuAdminPanelRowBack`). |
| `▶` | `U+25B6` | Black right-pointing triangle | Action row, execute button | No | `>` | Standard row bullet for clickable menu actions (`menuAdminPanelRowConfig`). |
| `◀` | `U+25C0` | Black left-pointing triangle | Previous action, back button | No | `<` | Alternative back/previous arrow for compact navigation. |
| `▲` | `U+25B2` | Black up-pointing triangle | Ascend, scroll up, increment | No | `^` | Useful for multi-page scroll up or incrementing numeric values. |
| `▼` | `U+25BC` | Black down-pointing triangle | Descend, scroll down, dropdown | No | `v` | Useful for sub-menus and numeric decrement controls. |
| `→` | `U+2192` | Rightwards arrow | Transition, dispatch, redirect | No | `->` | Indicates teleport routing or setting flow (e.g., `spawn -> wilderness`). |
| `←` | `U+2190` | Leftwards arrow | Return, source location | No | `<-` | Indicates origin or previous state. |
| `↔` | `U+2194` | Left-right arrow | Bidirectional sync, range | No | `<->` | Suitable for range displays (e.g., min/max radius bounds). |
| `\|` | `U+007C` | Vertical line / pipe | Delimiter, column separator | Yes | `\|` | Universal delimiter for console logs and status lines (`scanStatus`). |
| `•` | `U+2022` | Bullet | List item separator | No | `*` | Ideal for in-game lore lists and sub-options. |
| `·` | `U+00B7` | Middle dot | Subtle separator, grid dot | No | `.` | Used as empty-cell representation in ASCII map visualizers. |
| `✔` | `U+2714` | Heavy check mark | Success, enabled, valid | No | `[+]` or `Y` | Indicates active features, passed safety checks, or claim clearance. |
| `✓` | `U+2713` | Check mark | Success, completed | No | `[+]` or `v` | Lighter check mark for confirmation notices. |
| `✖` | `U+2716` | Heavy multiplication X | Failure, disabled, invalid | No | `[-]` or `X` | Indicates blocked biomes, unsafe blocks, or rejected attempts. |
| `✗` | `U+2717` | Ballot X | Cancellation, error | No | `[x]` | Indicates cancelled queue requests or failed checks. |
| `⚙` | `U+2699` | Gear | Admin panel, configuration | No | `*` | Primary title icon for `/rtp admin` (`menuAdminPanelTitle`). |
| `⚡` | `U+26A1` | High voltage / lightning | Instant teleport, RTP menu | No | `!` | Primary title icon for player `/rtp menu` (`menuRootTitle`). |
| `⌖` | `U+2316` | Position indicator | Visualizations, coordinates | No | `+` | Title icon for operator visualization tools (`menuVisualizationsTitle`). |
| `✎` | `U+270E` | Pencil | Custom value input | No | `_` | Prompts player to enter custom parameters in chat (`menuTypeValue`). |
| `⌛` | `U+231B` | Hourglass | Cooldown, delay, ETA | No | `T` | Represents active cooldowns or teleport warmup delays. |
| `⏳` | `U+23F3` | Hourglass with flowing sand | Pending task, queue progress | No | `...` | Indicates queue wait time or pipeline processing. |
| `ℹ` | `U+2139` | Information source | Info notice, command tip | No | `i` | Header for `/rtp info` summaries and general tips. |
| `❓` | `U+2753` | Question mark | Help menu, unknown state | No | `?` | Row icon for `/rtp help` (`menuFrontPageRowHelp`). |
| `⚠` | `U+26A0` | Warning sign | Diagnostic caution, notice | No | `!` | Warnings for costly diagnostics (`menuAdminPanelHoverDiagnostics`). |
| `⛔` | `U+26D4` | No entry | Lockout, restricted region | No | `X` | Indicates permission denied or world border lockdown. |
| `🔒` | `U+1F512` | Closed lock | Permission required, claim | No | `[L]` | Indicates a region requiring special permission nodes. |
| `🔓` | `U+1F513` | Open lock | Public access, unlocked | No | `[U]` | Indicates unrestricted or claim-bypassed areas. |
| `🎲` | `U+1F3B2` | Game die | Random teleportation | No | `*` | Primary action icon for instant teleport (`menuFrontPageRowTeleport`). |
| `🌍` | `U+1F30D` | Globe (Europe-Africa) | Region selector | No | `@` | Menu row icon for picking a region (`menuFrontPageRowRegion`). |
| `🌐` | `U+1F310` | Globe with meridians | World selector, network mode | No | `@` | Menu row icon for world selection (`menuFrontPageRowWorld`). |
| `🌳` | `U+1F333` | Deciduous tree | Biome selector | No | `Y` | Menu row icon for biome selection (`menuFrontPageRowBiome`). |
| `⛏` | `U+26CF` | Pickaxe | Mining / resource world | No | `/` | Ideal for resource/mining world presets in custom menus. |
| `⚔` | `U+2694` | Crossed swords | Combat, PvP arena | No | `x` | Used for PvP or danger-tier zone labels. |
| `🛡` | `U+1F6E1` | Shield | Protection, safe landing | No | `[S]` | Indicates claim protection or invulnerability period. |
| `💰` | `U+1F4B0` | Money bag | Economy cost, fee | No | `$` | Displays transaction costs when Vault economy is enabled. |
| `█` | `U+2588` | Full block | 100% bar fill, solid cell | No | `#` | Progress bars or dense spatial memory map cells. |
| `▓` | `U+2593` | Dark shade | 75% fill, high fail rate | No | `+` | High-density heatmap / scan status indicator. |
| `▒` | `U+2592` | Medium shade | 50% fill, medium fail rate | No | `=` | Moderate-density heatmap / scan status indicator. |
| `░` | `U+2591` | Light shade | 25% fill, low fail rate | No | `-` | Low-density heatmap / scan status indicator. |
| `■` | `U+25A0` | Black square | Solid badge, checkbox (checked) | No | `[X]` | Sub-menu badge or option indicator. |
| `□` | `U+25A1` | White square | Empty badge, checkbox (open) | No | `[ ]` | Inactive sub-menu badge or unfilled option indicator. |
| `§` | `U+00A7` | Section sign | Minecraft internal format code | No | `&` | Used internally by the engine; use `&` or `#RRGGBB` in configs. |

---

## Notes

- **Icon glyphs are intentional.** Some values embed single-codepoint UI icons (type-a-value, paginator arrows, run/row markers, the section sign). Preserve them verbatim; do not ASCII-fold or delete them unless optimizing a console-facing string for legacy terminal compatibility. See the [Useful UI Glyphs & Icons](#useful-ui-glyphs--icons) table above.
- **Never change `version`** - it is used internally for config migration.
