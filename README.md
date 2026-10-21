# LeafRTP - Random Teleport

Random teleport (`/rtp`) for Minecraft servers. Players type `/rtp` and land somewhere safe, far from spawn, right away. No "Finding a safe location..." wait and no lag spike for everyone else.

One jar runs on Paper, Spigot, Folia, Fabric and NeoForge. It's free and MIT-licensed.

[![Build](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml/badge.svg)](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml)
[![Release](https://img.shields.io/github/v/release/DailyStruggle/RTP)](https://github.com/DailyStruggle/RTP/releases)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![Modrinth](https://img.shields.io/badge/Modrinth-LeafRTP-green)](https://modrinth.com/plugin/leafrtp)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-RTP-orange)](https://www.spigotmc.org/resources/leafrtp.94812/)
[![BuiltByBit](https://img.shields.io/badge/BuiltByBit-RTP%20Pro-blue)](https://builtbybit.com/resources/leafrtp-pro.105418/)

**[Download](https://modrinth.com/plugin/leafrtp)** · **[Documentation](https://dailystruggle.github.io/RTP/)** · **[Quick start](docs/admin/QUICK_START.md)** · **[Commands](docs/admin/COMMANDS.md)** · **[FAQ](docs/admin/FAQ.md)** · **[Get help](SUPPORT.md)**

<div align="center">

<img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/teleport_title.webp" alt="An RTP teleport completing in 0 attempts, 49 ms" width="560" />

<sub>A real <code>/rtp</code>: the spot was checked ahead of time, so the player landed after <b>0 attempts, in 49 ms</b>.</sub>

</div>

---

## Contents

- [What servers use it for](#what-servers-use-it-for)
- [Quick start](#quick-start)
- [Features](#features)
- [Supported platforms](#supported-platforms)
- [Build from source](#build-from-source)
- [How it works](#how-it-works)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Support](#support)
- [License](#license)

---

## What servers use it for

- **Spreading players out on survival servers**, so new players don't all build next to spawn.
- **Resource and mining worlds** that get reset regularly. `/rtp` can be set to send players to fresh land instead of spots that were already used.
- **Random spawn on first join, death or world change** ([recipes](docs/admin/RECIPES.md#rtp-on-first-join-random-spawn-for-new-players)).
- **Hardcore, deathban and UHC-style scatter**, including teleporting a whole group at once.
- **SkyBlock, OneBlock and void worlds**, where RTP places players at a fixed height and the island plugin builds the ground.
- **Arenas and 1v1 duels**, with matched spawn points for each side ([recipe](docs/admin/RECIPES.md#symmetrical-1v1-duels--arena-battles-subspace-placement)).
- **Networks behind Velocity**: `/rtp` on one server sends the player to a region on another ([proxy mode](docs/admin/proxies/INDEX.md)).
- **Paid teleports, NPCs, signs and menus**: charge through Vault, or start a teleport from any plugin that runs a command ([recipes](docs/admin/RECIPES.md)).

---

## Quick start

1. **Download** the jar from [Modrinth](https://modrinth.com/plugin/leafrtp). One jar covers every platform.
2. **Install:** put it in `plugins/` (Paper, Spigot, Folia) or `mods/` (Fabric, NeoForge), then restart the server.
3. **Teleport:** run `/rtp`. That's it.

The defaults work as they are. Most servers only change how far players go:

```yaml
# plugins/RTP/definitions/regions/default.yml
shape:
  radius: 4096b        # farthest a player can land from the centre
  centerRadius: 1024b  # closest a player can land (keeps people off spawn)
```

Or change it in game, without opening any files:

```
/rtp config regions default shape.radius=10km shape.centerRadius=1km
```

Optional extras:
- `/rtp admin setup` is a guided wizard for single or multi-world servers, SkyBlock, arenas and economy. It shows a preview before it writes anything.
- `/rtp editor` opens a web editor with a map you can drag the region border on.
- `/rtp scan start` checks a region ahead of time, so even the first teleports are instant.

Full walkthrough: **[Quick start guide](docs/admin/QUICK_START.md)**. Config folder is `plugins/RTP/` (Bukkit family) or `config/rtp/` (Fabric, NeoForge).

### Everyday commands

| Command | What it does |
|---|---|
| `/rtp` | Teleport yourself (permission `rtp.use`) |
| `/rtp player=Steve` | Teleport another player |
| `/rtp world=world_nether` / `/rtp region=mining` | Teleport into a specific world or region |
| `/rtp menu` | In-game menu for picking regions and editing settings |
| `/rtp reload` | Apply config changes without a restart |
| `/rtp info` | Region status, queue size and diagnostics |

All commands and permissions: [COMMANDS.md](docs/admin/COMMANDS.md).

---

## Features

- **Instant teleports.** RTP finds and checks safe spots before anyone asks, so a teleport is usually served in 0-2 ticks.
- **Doesn't lag the server.** No chunk loading on the main thread, and it's built for Folia's regional threads, not patched to work on them.
- **Safe landings.** No lava, water, mid-air drops or suffocation. You choose which blocks and biomes count as unsafe.
- **Respects claims.** Skips land protected by GriefPrevention, GriefDefender, WorldGuard, Towny, Lands and others, detected automatically.
- **Learns your map.** Remembers oceans, claims and other bad areas so it stops retrying them, and keeps that across restarts.
- **Fresh spots.** Optionally never reuses a landing area, so players keep finding new land.
- **Any shape.** Circles, squares, rectangles or custom polygons, with uniform, centre-weighted or ring-shaped spread. Any number of regions per world.
- **Biome filters.** Leave out oceans, or send players only to the biomes you list.
- **Economy and cooldowns.** Optional Vault cost per teleport, plus cooldowns, use limits and per-region permissions.
- **Configurable everything.** Every message is editable, and ships in 11 languages besides English. Teleport effects, sounds and countdowns are optional.
- **Web editor and in-game menu.** Edit config from a browser or a clickable book menu instead of by hand.
- **PlaceholderAPI** support for cooldowns, queues and region info on scoreboards, tab lists and holograms.

<div align="center">

<img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/menu_1.png" alt="The /rtp menu admin panel book UI" width="280" /> <img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/menu_2.png" alt="The /rtp menu config-files browser" width="280" />

<sub>The clickable <code>/rtp menu</code>: admin panel (left) and config-file browser (right).</sub>

<img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/bad_locations_map.webp" alt="In-game land-vs-water map built from a region scan" width="340" />

<sub>A region scan's map of where players can and can't land.</sub>

</div>

More detail: [feature overview](docs/site/features.md).

---

## Supported platforms

| Platform | Versions | Notes |
|---|---|---|
| Paper / Spigot | 1.18+ | Paper is recommended. |
| Folia | 1.21.11+ | Current Folia builds only. |
| Fabric | 1.20.1, 1.21 - 26.3 | Not 1.20.2 - 1.20.6. |
| NeoForge | 1.21.1+ | [Details](platforms/rtp-neoforge/README.md) |
| Velocity (proxy) | - | Optional, for cross-server `/rtp`. [Proxy mode](docs/admin/proxies/INDEX.md) |
| Forge (legacy) | - | Run the Paper/Spigot jar under Arclight or Mohist. |

**Java 17+**, or newer if your Minecraft version needs it (Java 21 from 1.20.5, Java 25 on 26.x). Bug reports are accepted for 1.21.11 through the latest release; older versions are best effort. Per-version status: [support matrix](docs/dev/SUPPORT_MATRIX.md).

**Tips:** pregenerating the world (for example with Chunky) makes the first teleports faster. RTP holds its prepared spots in memory, so give the server some heap headroom ([performance settings](docs/admin/configuration/PERFORMANCE.md)).

---

## Build from source

Most people should [download a release](https://modrinth.com/plugin/leafrtp). To build it yourself:

**You need:** Git and a JDK 21. Gradle and the Java 25 toolchain for the 26.x modules are downloaded automatically by the wrapper.

```sh
git clone https://github.com/DailyStruggle/RTP.git
cd RTP
./gradlew assemble        # Windows: .\gradlew.bat assemble
```

The jars land in `build/libs/`:

| Jar | What it is |
|---|---|
| `LeafRTP-<version>.jar` | The plugin/mod. Install this one. |
| `LeafRTP-Pro-<version>.jar` | The same engine plus the bundled SQL/Redis drivers for proxy mode and a Folia-tuned scheduler. |
| `LeafRTPGuiAddon-*.jar`, `LeafRTPCountdownAddon-*.jar` | Optional addons. |

Other useful commands:

```sh
./gradlew build                  # compile and run every test
./gradlew :rtp-core:test         # test one module
./gradlew -PexcludeJdk25 build   # skip the 26.x modules on a JDK-21-only machine
./gradlew spotlessApply          # format before opening a PR
```

The first build downloads the Minecraft and loader dependencies and takes a while; later builds are cached. IntelliJ IDEA opens the folder as a Gradle project directly. More detail: [FOR_CONTRIBUTORS.md](docs/FOR_CONTRIBUTORS.md).

### Building an addon

Compile against `rtp-api` only. It lets you register custom shapes, landing rules and claim checks without touching the plugin. Start with the [addon quickstart](docs/ADDON_QUICKSTART.md) and [FOR_ADDON_DEVELOPERS.md](docs/FOR_ADDON_DEVELOPERS.md).

---

## How it works

Most RTP plugins pick random coordinates and check them while the player waits, then pick again whenever a spot is unsafe. RTP does that work ahead of time. It keeps a queue of checked, safe spots for each region, refills it in the background, and remembers areas that failed so it doesn't test them again. When a player runs `/rtp`, the next spot in the queue is handed out.

For the details (selection algorithm, threading model, design decisions), see [How it works](docs/site/how-it-works.md), [Why LeafRTP exists](docs/site/why.md) and the [shape algorithm charts](docs/site/shape-algorithms.md).

<details>
<summary>Repository layout</summary>

| Directory | Purpose |
|---|---|
| `rtp-api/` | Public API for addon developers. |
| `rtp-core/` | Platform-independent engine: regions, shapes, queues, database. |
| `commands-api/`, `effects-api/` | Shared command and effects frameworks. |
| `rtp-plugin/` | Bukkit-family entry point and the shipped jar. |
| `platforms/` | Spigot, Paper, Folia, Fabric, NeoForge and proxy adapters. |
| `addons/` | Optional addons (GUI menu, countdown, integrations). |
| `docs/` | Everything published on the documentation site. |
| `devstack/` | Docker-based multi-server test environment. |

</details>

---

## Documentation

**<https://dailystruggle.github.io/RTP/>** - searchable, and rebuilt on every merge. The same pages also ship inside the jar under `plugins/RTP/docs/`, so they work offline and always match your installed version.

| I want to... | Start here |
|---|---|
| Install and run RTP on my server | [FOR_SERVER_ADMINS.md](docs/FOR_SERVER_ADMINS.md) |
| Copy a setup for a common task | [Recipes](docs/admin/RECIPES.md) |
| Write an addon | [FOR_ADDON_DEVELOPERS.md](docs/FOR_ADDON_DEVELOPERS.md) |
| Work on RTP itself | [FOR_CONTRIBUTORS.md](docs/FOR_CONTRIBUTORS.md) |

---

## Contributing

Bug fixes, translations, docs and addons are all welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow and code style, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). In short: run `./gradlew build` and `./gradlew spotlessApply` before you open a pull request, and add a line to [CHANGELOG.md](CHANGELOG.md).

---

## Support

- Questions: [GitHub Discussions](https://github.com/DailyStruggle/RTP/discussions)
- Bugs: [open an issue](https://github.com/DailyStruggle/RTP/issues/new?template=bug_report.md), after checking the [FAQ](docs/admin/FAQ.md) and confirming it still happens on the latest release (`/rtp version`)
- Security problems: see [SECURITY.md](SECURITY.md); please don't open a public issue

The full guide is in [SUPPORT.md](SUPPORT.md).

**[RTP Pro on BuiltByBit](https://builtbybit.com/resources/leafrtp-pro.105418/)** buys priority support, including fixes for the problems you report, and funds development. It's the same MIT-licensed code as the free build.

---

## License

**MIT** ([`LICENSE`](LICENSE)). You may use, modify and redistribute all source in this repository and both the free and Pro jars, including commercially. Pro is a support tier, not a different licence ([ADR-108](docs/adr/ADR-108-all-mit-licensing-pro-as-support.md)).

Where a module or file ships its own `LICENSE` or SPDX header (for example the GPL-3.0-or-later NeoForge carriers), that grant governs the file. Releases published before 2026-10-05 keep the licence they shipped with.
