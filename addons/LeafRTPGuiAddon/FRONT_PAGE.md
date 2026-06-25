<!--
Markdown mirror of FRONT_PAGE.bbcode (LeafRTP GUI Addon front page).
Kept in sync with the BBCode source by hand; update both when changing copy.

Marketplace listing metadata (for SEO reference):
  Title:   "LeafRTP GUI - Clickable Destination Picker"
  Tagline: "Clickable /rtp menu for Paper, Spigot, Folia, Fabric & NeoForge"
-->

# LeafRTP GUI - a clickable `/rtp` destination picker

this is an add-on for [LeafRTP](https://modrinth.com/plugin/leafrtp), not a standalone plugin. install LeafRTP first, then drop this jar in. once it loads, the bare `/rtp` (and `/rtpgui`) opens a clickable menu instead of teleporting straight away.

every destination a player is allowed to use shows up as a slot, decorated with its live status straight from the engine: ready, on cooldown, its cost, or unavailable. click a slot and it teleports. the menu only draws things, every line of safety and validation still lives inside the LeafRTP engine.

the default layout is a DonutSMP-style chest menu, so an admin gets a working ui with zero config. if you want to change it, `guimenu.yml` is there.

---

## what it does

- type `/rtp` or `/rtpgui` to open the menu. each slot is one destination the player has permission for: the default rtp, configured regions, and per-world targets.
- icons are decorated from the engine, so players see ready / on-cooldown / the Vault cost / unavailable before they click.
- clicking a slot submits the teleport through LeafRTP's own validated path. it always completes with a result, never a silent no-op.
- a dashboard tile at the bottom shows live tps, mspt, and player count (toggleable).
- one multi-loader jar runs everywhere: Bukkit/Paper/Folia render it as a chest inventory, Fabric and NeoForge render it as a native server-side container screen, all from the same menu model.

---

## configuration (`guimenu.yml`)

config registers with LeafRTP's own config system, so the file is created on first boot in the RTP data folder, reloads on `/rtp reload`, and never needs manual pasting. the defaults produce the DonutSMP-style layout; every key is optional and falls back to that default.

you can tune:

- **`menuStyle`** - which renderer opens (e.g. `chest`); swap the gui without touching code.
- title, row count, and filler material.
- per-target-kind and per-availability icon materials (platform-neutral names; each renderer maps them to its own item type).
- the server-health dashboard toggle.
- all user-facing messages.

### region biome-map icons (opt-in)

set `regionIconStyle: biome-map` to draw each named-region row's icon as that region's live biome render - the same chart `/rtp visualization biomes` produces - instead of a static icon material. it reuses LeafRTP's bundled map path, repaints lazily behind a staleness window, and falls back to the static icon if the map subsystem is unavailable or the region has no scanned data yet. the default is the static `material` icon, so this is strictly opt-in.

---

## safety

the engine owns safety, the menu just draws it.

- permission gating, cooldown and cost resolution, and the full safety pipeline all live behind the api. the only mutating call the addon makes is the teleport submit, which re-validates everything server-side and always returns a result.
- the chest gui is read-only: every click is cancelled, so there's no inventory-dupe or click-exploit surface, and the addon identifies its own inventory by a custom holder, never by a spoofable title.

the picker is built entirely on LeafRTP's public `rtp-api`:

| need | api call |
|------|----------|
| list the player's allowed destinations (permission-gated) | `RTPAPI.getAllowedTargets(UUID)` |
| decorate each icon (availability / cooldown / cost) | `RTPAPI.getTargetStatus(UUID, RtpTarget)` |
| trigger the teleport on click | `RTPAPI.teleport(UUID, RtpTarget)` |
| server-health dashboard tile | `RTPAPI.getMetricsSnapshot()` |

---

## requirements

- LeafRTP installed (this is an add-on, not a standalone plugin).
- Java 21+ on Bukkit/Paper/Folia; the Fabric/NeoForge carriers target Minecraft 26.x.
- no config required - the menu works the instant the jar loads.

---

## install

1. install [LeafRTP](https://modrinth.com/plugin/leafrtp).
2. drop `LeafRTPGuiAddon-x.y.z.jar` into `plugins/` (Bukkit/Paper/Folia) or `mods/` (Fabric/NeoForge).
3. start the server.
4. type `/rtp` (or `/rtpgui`) and the picker opens.

tune `plugins/RTP/addons/guimenu.yml` later if you want; `/rtp reload` applies changes live, no restart.

---

## platform support

| platform | notes |
|----------|-------|
| Paper (+ forks) | chest-inventory renderer + click listener. |
| Spigot (+ Bukkit-family forks) | same chest renderer. |
| Folia | opens on the correct thread via LeafRTP's scheduler. |
| Fabric (MC 26.x) | native server-side container (chest) screen. |
| NeoForge (MC 26.x) | native server-side container (chest) screen. |

the `chest` style key is shared across every platform, so a single `guimenu.yml` works everywhere, no per-platform config.

---

*built on the same platform model as LeafRTP itself: a platform-neutral core plus thin per-platform renderers. a renderer is just a small class registered under a style key, so a book gui could ship alongside the chest gui without a code change to the core.*
