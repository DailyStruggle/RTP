# Discord Server Layout & Support Bot Blueprint (`LeafCraft Plugin Support`)

This document is the operational blueprint for the **`LeafCraft Plugin Support`** Discord server. Its machine-readable counterpart lives in [`embeds.json`](embeds.json), applied idempotently by [`discord_setup.py`](discord_setup.py) and served at runtime by [`discord_support_bot.py`](discord_support_bot.py).

All public channel embeds, pinned Forum guides, and automated bot responses follow **Rule D-006** ([`docs/dev/RULES.md`](../docs/dev/RULES.md)): first-person maintainer voice ("I"), plain engineering language, concrete numbers and caveats, ASCII hyphens (`-`) only, and zero sales buzzwords.

For the full retrieval, prompt-injection bottleneck, dual-translator, and causal-reasoning architecture of the support bot (and how to replicate it in another project), see [`DISCORD_SUPPORT_BOT_DESIGN.md`](DISCORD_SUPPORT_BOT_DESIGN.md).

---

## 1. Non-Destructive Reorganization Strategy

Previously, the server split traffic across flat text channels (`#rules`, `#welcome`, `#role-assignment`, `#info`, `#faq`, `#recommended-specs`, `#issues`, `#support`, `#feature-requests`) and separate per-plugin categories (`RTP support`, `OnePlayerSleep support`, `TreeGen support`). Users had to guess which text channel to post in, and concurrent troubleshooting conversations interleaved in a single scrolling stream.

To fix this without losing years of searchable troubleshooting history:

1. **Zero message deletion:** `discord_setup.py` never calls `DELETE` on populated text channels.
2. **Read-Only Archive Migration:** Legacy flat channels (`#support` renamed to `#old-support`, `#issues`, `#feature-requests`, `#faq`, `#recommended-specs`, `#role-assignment`, `#welcome`, `#info`, `#rules`, and legacy per-plugin text channels) are moved into a collapsed **`ARCHIVE (Read-Only)`** category at the bottom of the server.
3. **Searchable history:** `@everyone` keeps `VIEW_CHANNEL: true` and `READ_MESSAGE_HISTORY: true` on `ARCHIVE (Read-Only)`, with `SEND_MESSAGES: false`, `SEND_MESSAGES_IN_THREADS: false`, and `ADD_REACTIONS: false`. Old solutions remain searchable via Discord search (`in:old-support`, `in:faq`) while new posts are directed to the `#support` Forum.

---

## 2. Target Channel & Category Hierarchy

| Category | Channel | Type | Permissions & Purpose |
|---|---|---|---|
| **WELCOME & INFO** | `#start-here` | `GUILD_TEXT` (Read-Only) | Replaces `#rules`, `#welcome`, `#info`, and `#role-assignment`. Contains 4 Rule D-006 embeds: (1) Welcome & House Rules, (2) LeafRTP 60-Second Quick Start, (3) Downloads & Documentation, and (4) Support & Claiming `@Pro Supporter`. |
| **WELCOME & INFO** | `#announcements` | `GUILD_ANNOUNCEMENT` | Major cross-project news for `LeafRTP`, `OnePlayerSleep`, `TreeGen`, and server tools. |
| **WELCOME & INFO** | `#releases` | `GUILD_ANNOUNCEMENT` | Automated GitHub and Modrinth release notes with changelog summaries. |
| **WELCOME & INFO** | `#benchmarks` | `GUILD_TEXT` (Read-Only) | Replaces `#recommended-specs`. Displays measured Paper 26.2 and Folia 26.2 benchmark outcomes, spacing figures (`0` pairs within 48 blocks, `70 blocks` closest measured pair across 4,096 teleports), and heap/pregeneration guidance. |
| **SUPPORT & COMMUNITY** | `#support` | `GUILD_FORUM` | **Unified support hub.** `require_tag: true`, default reaction `✅`, list layout sorted by latest activity. Houses 5 pinned reference threads and required Project/Platform tags. |
| **SUPPORT & COMMUNITY** | `#general` | `GUILD_TEXT` | Community discussion for server owners and developers. |
| **DEVELOPMENT** | `#development` | `GUILD_TEXT` | Public engineering discussion, `rtp-api` addon development, and server fuzzing tools. |
| **ADMIN** (Private) | `#bbb-logs` | `GUILD_TEXT` (Staff/Bot) | Preserved BuiltByBit purchase and supporter role synchronization webhook channel. |
| **ADMIN** (Private) | `#bot-triage-queue` | `GUILD_TEXT` (Staff/Bot) | Escalation feed where `discord_support_bot.py` posts a 4-line digest once a thread has complete diagnostics (`/rtp version`, `/version`, `/rtp test`, `mclo.gs`) or `@Pro Supporter` priority. |
| **ARCHIVE (Read-Only)** | `#old-support`, `#issues`, `#faq`, etc. | `GUILD_TEXT` (Read-Only) | Historical text channels preserved intact with `SEND_MESSAGES: false` for `@everyone`. |

---

## 3. Unified `#support` Forum Tags & Pinned Reference Threads

### 3.1 Forum Tags (`require_tag = true`)

- **Project Tags (user picks 1):**
  - `🌿 LeafRTP`
  - `💤 OnePlayerSleep`
  - `🌲 TreeGen`
  - `🔬 Server Fuzzer / Tools`
  - `📦 API / Addons`
- **Platform Tags (user picks 1+):**
  - `Paper / Spigot`
  - `Folia`
  - `Fabric`
  - `NeoForge`
  - `Velocity / Proxy`
- **Status Tags (bot / maintainer managed):**
  - `✅ Solved` (applied when the author reacts `✅`, replies `solved`, clicks **[✅ Solved]**, or a maintainer closes the thread)
  - `⏳ Needs Info / Logs` (moderated; applied when the bot is investigating an uncertain question or waiting on diagnostic items before pinging the maintainer)
  - `🐛 Confirmed Bug` (moderated; applied after reproduction)
  - `💎 Pro Priority` (moderated; applied automatically when the thread author holds `@Pro Supporter`)

### 3.2 Five Pinned Forum Reference Guides

Defined in `discord-support-bot/embeds.json` under `pinned_forum_threads` and idempotently created/updated by `discord_setup.py`:

1. **`Read First: How to get fast help (and /rtp test)`** - Explains how the closed-world doc bot works, how to run `/rtp version`, `/rtp info`, and `/rtp test` (`[RTP bit]` self-test), and the 4 items needed for bug escalation.
2. **`Quick Fix: Setting Region Radius, Inner Spawn Hole & World Border`** - Covers `/rtp config regions default shape.radius=10km shape.centerRadius=1km`, `/rtp editor`, `/rtp admin setup`, `config.yml` vs. `definitions/regions/default.yml`, `worldBorderOverride: true`, and `RTP_SHAPE_RADIUS_NOT_ABOVE_CENTER`.
3. **`Quick Fix: Toggling Bare /rtp GUI vs. Instant Teleport (openOnBareRtp)`** - Explains setting `openOnBareRtp: false` in `addons/guimenu.yml` (while keeping `/rtp gui` active) and why deleting the individual jar inside `plugins/RTP/addons/` is required rather than deleting the `addons/` folder.
4. **`Quick Fix: 1-Command Migration from BetterRTP, EzRTP, or JustRTP (/rtp config import)`** - Covers `/rtp config import` dry-run preview, `overwrite=true`, `/rtp config import permissions apply=true`, and why per-world permissions (`requirePermission: false`) are not needed out of the box.
5. **`Folia, Fabric & NeoForge Platform Guide`** - Covers the Folia `1.21.11+` floor, `mods/` and `config/rtp/` paths on Fabric (`1.20.1`, `1.21 - 26.3`) and NeoForge (`1.21.1+`), the unsupported Fabric `1.20.2 - 1.20.6` gap, and Velocity `network.yml` secret safety.

---

## 4. Running the Provisioner & Support Bot

### 4.1 Build the SQLite FTS5 Index
```sh
python3 discord-support-bot/build_doc_index.py
```
Compiles `docs/admin/**`, `docs/site/*`, `docs/FOR_SERVER_ADMINS.md`, `docs/FOR_ADDON_DEVELOPERS.md`, `docs/dev/BIT_CODES.md`, `docs/dev/SUPPORT_MATRIX.md`, `docs/dev/bit-codes.lock`, and shipped `*.yml` files into `discord-support-bot/generated/doc-index.sqlite`.

### 4.2 Preview or Apply Server Reorganization
`discord_setup.py` and `discord_support_bot.py` read `DISCORD_BOT_TOKEN`, `DISCORD_GUILD_ID` (or `DISCORD_TEST_SERVER_ID`), and `DISCORD_MAINTAINER_ID` from environment variables or automatically from `~/.DISCORD_BOT_TOKEN`, `~/.DISCORD_TEST_SERVER_ID` (`~/.DISCORD_GUILD_ID`), and `~/.DISCORD_MAINTAINER_ID`.
```sh
# Dry-run diff against the live guild (zero mutations):
python3 discord-support-bot/discord_setup.py --dry-run --live

# Apply in-place via Discord REST API (requires Manage Guild + Manage Channels + Manage Roles):
python3 discord-support-bot/discord_setup.py --apply

# Clear non-reference user test threads in #support, #bot-triage-queue cards, and bot-state.json:
python3 discord-support-bot/discord_setup.py --reset-test-server
```

### 4.3 Run the Read-Only Forum Triage Bot
```sh
# Single sequential REST poll pass over active #support threads:
python3 discord-support-bot/discord_support_bot.py --once

# Continuous sequential REST polling loop (add --allow-maintainer-threads when posting test threads from the maintainer account):
python3 discord-support-bot/discord_support_bot.py --listen --interval 15 --settle-seconds 5 --allow-maintainer-threads
```
