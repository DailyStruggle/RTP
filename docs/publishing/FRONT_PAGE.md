<!--
Single source for every storefront page (BuiltByBit paid + free, Modrinth, Hangar).
Never paste this file anywhere directly. Build it with
    python scripts/release/build_front_pages.py
and use the files in scripts/release/generated/ (gitignored, so build before use):
paste the two .bbcode files into BuiltByBit by hand; release.yml builds and pushes
the Modrinth and Hangar pages. CI fails if any target doesn't build (`--check`).

Markers, each alone on its line, nestable (written here without the comment
delimiters, because a closing delimiter would end this note):
  kind: promo | purchase   ... /kind   what the block IS; POLICY in the script decides
                                       which targets keep it (promo is dropped on
                                       Modrinth and Hangar)
  only: <targets>          ... /only   audience copy for the listed targets
  not: <targets>           ... /not    audience copy for every other target
  targets: bbb-pro, bbb-lite, modrinth, hangar; aliases: bbb, markdown
  {{name}} and {{jar}} expand per target (LeafRTP-Pro on bbb-pro, LeafRTP elsewhere).
Hangar does not allow paid-edition advertising: anything about paying goes in a
`kind: promo` block. The build also fails if a Modrinth or Hangar page still
mentions Pro, buying, sponsorship or a price.

Voice (RULES.md D-006): first person, plain, no sales language. One person
maintains this as a community project; write like that.
Prefer numbers and caveats over adjectives. ASCII punctuation only.
Images: local relative paths (e.g. ![alt](../assets/img/pic.png)); build_front_pages.py
validates existence on disk and rewrites them to raw.githubusercontent.com URLs.

Architecture & Layout (Invariant 3-Zone Utility Hierarchy):
To eliminate churn between beginner accessibility and engineering precision,
the document is partitioned into three non-overlapping functional zones. Do not
blur boundaries between zones when updating copy:

Zone 1: Verified Outcomes & Universal Guarantees (Above the Fold)
  - Audience: 100% of visitors (evaluators, beginners, power users).
  - Scope: Title, badges, 'What it is', platform compatibility, 'The short version'.
  - Rule: State empirical outcomes only (0 stalls, 0 failed teleports, off-tick,
    6 ms median /rtp-to-arrival latency on Paper, single jar). Use the same
    measured figure everywhere it appears. Zero algorithmic exposition or
    derivations here.

Zone 2: Operational On-Ramp & Zero-Friction Migration (Immediate Action)
  - Audience: Action-oriented server operators ready to deploy or switch.
  - Scope: 6-step quick-start install, /rtp config import (zero-friction switch),
    /rtp admin setup / web editor, common setups, player & admin features.
  - Rule: Maximize net utility by minimizing time-to-deployment. An admin should
    be able to get a safe, lag-free setup running without scrolling past this zone.

Zone 3: Architectural Proof & Technical Transparency (Deep Foundation)
  - Audience: Folia operators, network architects, skeptics, and engineers.
  - Scope: Full benchmark tables (harness in helpers/StressTestRTP/), scatter
    charts, spatial memory benchmarks, Anvil .mca pre-filter, quality gates, and FAQ.
  - Rule: Retain 100% mathematical and technical precision. Never dilute or
    summarize; keep deep derivations inside collapsible <details> blocks or
    docs site links so rigor is fully preserved without imposing cognitive load
    on Zone 1 and 2 readers. Head-to-head competitor comparisons live strictly
    in the Performance section.

Listing metadata (for the marketplace form fields, not emitted):
  Paid BuiltByBit title:  "LeafRTP-Pro"   tagline: "Off-tick Random Teleportation engine"
  Free title:             "LeafRTP"       tagline: "Off-tick random teleport engine with spatial memory"
  (Modrinth's short description still reads "Fast Random Teleportation for everyone,
   configurable and extensible" - update it there to match.)

Private / Author notes:
  - Run a future memory leak test using the behaviour leak fuzzer (`scripts/bench_fuzz.py`) across all candidate plugins on Folia/Paper to evaluate long-duration heap bounds and update the memory retention comparisons.
-->

<div align="center">

# {{name}}: Random Teleport

[![Build](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml/badge.svg)](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml)
[![Java](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![Platforms](https://img.shields.io/badge/Platforms-Paper%20%7C%20Folia%20%7C%20Fabric%20%7C%20NeoForge%20%7C%20Velocity-orange)](https://dailystruggle.github.io/RTP/admin/QUICK_START/)
[![Web Editor](https://img.shields.io/badge/Tooling-Visual%20Web%20Editor-2ea44f)](https://dailystruggle.github.io/RTP/editor/)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=DailyStruggle_RTP&metric=coverage)](https://sonarcloud.io/summary/overall?id=DailyStruggle_RTP)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=DailyStruggle_RTP&metric=bugs)](https://sonarcloud.io/summary/overall?id=DailyStruggle_RTP)
[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=DailyStruggle_RTP&metric=sqale_rating)](https://sonarcloud.io/summary/overall?id=DailyStruggle_RTP)

</div>

## What it is

**{{name}} is a `/rtp` command.** It teleports a player to a random, safe spot in the world. Candidate checking runs off the tick thread. I've worked on it in my own time since 2021. The benchmarks and the test harness are in the repo, and every number below can be rerun from them.

<!-- only: bbb-pro -->
<!-- kind: promo -->
This listing is how you pay for support. The code is the same as the [free download](https://modrinth.com/plugin/rtpv3) and all of it is MIT licensed. Paying doesn't unlock anything in the plugin; details are in [What paying gets you](#what-paying-gets-you) below.
<!-- /kind -->
<!-- /only -->
<!-- only: bbb-lite -->
<!-- kind: promo -->
There is also a paid LeafRTP-Pro listing on BuiltByBit. It is the same code, and nothing in the plugin is locked. Paying gets you priority support and early builds.
<!-- /kind -->
<!-- /only -->
<!-- only: modrinth -->
One jar for Paper, Folia and Spigot (1.20+), Fabric (needs Fabric API) and NeoForge (1.21.1+), up to 26.x, and it also loads as a Velocity plugin for cross-server `/rtp`. Java 21+.
<!-- /only -->
<!-- only: hangar -->
On Paper all candidate checking runs off the tick thread. On Folia, candidates are screened against the region files first, nothing waits on a synchronous chunk load, and only the final block check of a loaded chunk runs on its region thread. The same jar also loads on Velocity for cross-server `/rtp`.
<!-- /only -->

### The short version

- [**Tick stability**](https://dailystruggle.github.io/RTP/admin/configuration/PERFORMANCE/): No ticks over 50 ms across 4,096 paced teleports in the Paper run; candidates are checked against region files off-tick before any chunk loads.
- [**Low latency**](https://dailystruggle.github.io/RTP/site/why/#background-processing-and-memory): 6 ms median from `/rtp` to arrival on Paper, served from pre-verified candidate queues.
- [**Throughput**](#performance): Sustained 100 TP/s on Paper and Folia, scaling to a detected maximum of 180 TP/s on Folia.
- [**Even player spread**](https://dailystruggle.github.io/RTP/site/why/): Zero duplicate landings in 4,096 teleports, with no player coordinates tracked.
- [**Persistent memory**](https://dailystruggle.github.io/RTP/admin/configuration/TTL/): Rejected oceans, lava and claims are remembered across restarts.
- [**One-command import**](https://dailystruggle.github.io/RTP/admin/MIGRATION/#migrating-from-competitor-plugins-betterrtp-justrtp-ezrtp-jakesrtp): Auto-translates BetterRTP, EzRTP, and JustRTP configs.
- [**Web workspace**](https://dailystruggle.github.io/RTP/admin/WEB_EDITOR_GUIDE/): Edit region borders and configs in your browser, with live tick, queue and latency stats (`/rtp editor`).
- [**One jar**](https://dailystruggle.github.io/RTP/admin/QUICK_START/): Runs on Paper, Folia, Spigot, Fabric, NeoForge, and Velocity.
- [**Portals & events**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): Templates for step-in portals, party scatter, and 1v1 arenas.
- [**Claim checks**](https://dailystruggle.github.io/RTP/admin/CLAIM_PLUGIN_COMPATIBILITY/): Fail-closed claim checks, PvP damage cancel, zero open firewall ports.

Benchmark comparisons and hardware metrics are in [Performance](#performance).

## Common setups

These work without extra plugins:

- [**Spawn portals & launch pads**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): Step-in trigger zones over portal frames or launch pads run teleports with countdown holograms, sounds, and particle trails.
- [**Group & party scatter**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): The bundled `scatter.yml` action sends queued parties to safe spots at least `minSeparation` blocks apart.
- [**First-join random spawn**](https://dailystruggle.github.io/RTP/admin/RECIPES/#rtp-on-first-join-random-spawn-for-new-players): Grant `rtp.onevent.firstjoin` to send new players to random spots on login; the login reserve cache keeps a destination pre-warmed so arrival is quick.
- [**1v1 and 2v2 PvP matchmaking**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): Bundled `challenge.yml`, `arena.yml`, and `teams.yml` actions queue and pair players for 1v1 duels or 2v2 squad battles into temporary bounded arenas with countdowns and border constraints, cleaned up automatically.
- [**Near-player & claim hunting**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): Bundled `nearplayer.yml` and `nearclaim.yml` actions land players safely near random players or town and claim perimeters (`anchor: entity`, `anchor: claimboundary`) without inline claim-plugin overhead.
- [**Sky drops**](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/): A `Fixed` vertical adjustor at Y=250+ pairs with a slow-falling potion effect for aerial parachute drops.
- [**Survival and resource worlds**](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/#spacing-repeats-and-worst-case-by-mode): Destinations do not repeat within a run (0 of 4,096 landings within 48 blocks), preventing base hunting without tracking players.

---

## Install

**Requirements:** Java 21+. Paper, Folia, Spigot on 1.20+, or Fabric / NeoForge on 1.21.1+, up to 26.x.

1. Drop `{{jar}}` into `plugins/` (or `mods/`).
2. Type `/rtp`.

**Customize (optional):**
- **Chat wizard:** `/rtp admin setup`
- **Web workspace:** `/rtp editor` in your browser
- **YAML files:** `plugins/RTP/` (`radius: 10km`, `cooldown: 30s`)
- **Migrate:** `/rtp config import <BetterRTP|EzRTP|JustRTP>`

(Advanced database, network proxy, and cache tuning: see the [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/).)

---

## Features

### Setting it up

All configuration routes edit the same files, accept human units (`radius: 10km`, `cooldown: 30s`), and hot-reload via `/rtp reload`.

- [**Web Workspace**](https://dailystruggle.github.io/RTP/admin/WEB_EDITOR_GUIDE/) (`/rtp editor`): Browser admin with no port forwarding. Drag region boundaries on a map and edit YAML with the setting docs beside it. A diagnostics tab shows live tick, queue and latency stats.
- [**In-Game Admin Book**](https://dailystruggle.github.io/RTP/admin/configuration/IN_GAME_CONFIG/#1-the-interactive-admin-panel-rtp-admin) (`/rtp admin`): Clickable operator book for inspecting queues and reloading configs in game.
- [**Chat Wizard**](https://dailystruggle.github.io/RTP/admin/QUICK_START/#guided-setup-rtp-admin-setup) (`/rtp admin setup`): 60-second guided questionnaire sizing regions to world borders with automatic backups.
- [**Config Import**](https://dailystruggle.github.io/RTP/admin/MIGRATION/) (`/rtp config import`): Translates worlds, radii, and cooldowns from BetterRTP, EzRTP, and JustRTP.
- [**Spatial Pregeneration**](https://dailystruggle.github.io/RTP/admin/configuration/TTL/) (`/rtp scan`): Checks region files off-tick ahead of time and records the oceans, lava and claims it finds.
- [**Live Visualizations**](https://dailystruggle.github.io/RTP/admin/configuration/IN_GAME_CONFIG/#c-diagnostics--monitoring) (`/rtp visualization`): Renders boundaries, biome layouts, and candidate heatmaps onto held in-game maps.
- [**Engine Diagnostics**](https://dailystruggle.github.io/RTP/admin/QUICK_START/#diagnostics-rtp-info) (`/rtp info`): Live queue depth, latency percentiles, and per-region generation rates.

![Web editor: region map](../assets/img/web_editor_cartography.png)

*Region editor: polygon boundaries over the terrain, with landing candidate previews.*

![Web editor: configuration editor with built-in docs](../assets/img/web_editor_docs.png)

*Settings editor with each setting's description alongside and a diff of staged changes.*

![Web editor: diagnostics tab](../assets/img/web_editor_diagnostics.png)

*Diagnostics tab: tick budget, queue depth per tier, and pipeline latency percentiles.*

<div align="center">

![Admin book](../assets/img/menu_1.png) ![Admin book: config files](../assets/img/menu_2.png)

*`/rtp admin`: the operator book and its config file list.*

</div>

### For players

- **`/rtp` and `/wild`**: teleport to the world's default region, or pick a region, world or biome (`/rtp region:<name>`, `/rtp biome:<biome>`). `/rtp back` returns players to their prior location.
- **Menus & custom items**: `/rtp menu` opens an interactive player book on Paper, Folia, Fabric and NeoForge (chat pages on Spigot) for teleporting or picking a region, world or biome. The bundled GUI addon adds a chest-based destination picker with barrier indicators for blocked destinations, customizable hover lore, and prefix routing for custom items (`ia:`, `oraxen:`, `nexo:`, `hdb:`, Base64 heads, and CustomModelData; see [item compatibility](https://dailystruggle.github.io/RTP/admin/CUSTOM_ITEMS/)).
- **Per-player queues** (`rtp.personalqueue`): personal reserve queues alongside the shared queue, so a surge doesn't delay a player who has one reserved.
- **Effects**: particles, sounds, fireworks, potions, titles, action bar notices, console or player commands, and holograms on every teleport phase, each controlled by `rtp.effect.<stage>.*` permissions. Holograms use the 1.19.4+ text display entity directly; DecentHolograms and HolographicDisplays are supported if present.
- **Landing platforms & safety**: temporary landing platforms decay automatically so nobody drops into ocean, void or open air, paired with a post-teleport invulnerability window, cancel on movement or damage, countdown holograms and warmup messages.

![In-game destination menu with live server health tooltip](../assets/img/menu_server_health.png)

*`/rtp menu`: in-game chest menu with live server health and destination selection.*

### Gameplay and worlds

- **Regions**: any number per world with customizable shape (Square, Circle, Rectangle, Polygon), radius, center, curve weighting, vertical bounds, world override, required permission, and price. Vertical adjustors (Linear, Jump, Fixed) for sky islands, void worlds, aerial parachute drops, and Nether ceilings. A world's `override` key (`definitions/worlds/<name>.yml`) routes Nether or End teleports to designated safe worlds.
- **Arrival schematics**: drop a Sponge `.schem` named after a region into `plugins/RTP/advanced/schematics/` and every teleport into that region pastes it centered on the landing spot. Decoded in-house, no WorldEdit needed, and claim-aware.
- **Auto-RTP on events**: join, first join, respawn, world change, move, and teleport (`rtp.onevent.*`). A login reserve cache keeps destinations ready so join-time teleports are served from the queue.
- **Economy**: charge per `/rtp` through Vault, per-region pricing, refund on cancel, and `rtp.free` permission bypass.
- **PvP / combat-tag check**: off by default; refuses or delays `/rtp` for players who recently dealt or took PvP damage. Includes built-in tracking or hooks into PvPManager, CombatLogX, or Simple Combat Log if installed.
- **Command blocks and console**: the same command parser handles player, console, and command-block callers.

### Portals, party scatter & PvP duels

Bundled action templates:

- **Step-in portals**: Configurable trigger zones for portals and launch pads with countdown holograms, particles, and sounds.
- **Party scatter**: Sends a queued party out together, with a minimum block spacing between teammates.
- **1v1 & 2v2 duels** (`/rtp action challenge <player>`): Temporary bounded arenas with countdowns, cleaned up when the match ends.
- **Anchor drops**: Land players near friends (`anchor: entity`) or outside town claims (`anchor: claimboundary`).
- **Event scripts**: Trigger console commands, titles, sounds, or potion effects on match start, boundary violation, or finish.

*Templates and configuration options: [Actions & arenas guide](https://dailystruggle.github.io/RTP/admin/ACTIONS/).*

### Claims and safety

- **Claim plugins**: 16 on Bukkit / Paper / Folia through the bundled claim addon (GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, HuskTowns, PlotSquared, RedProtect, CrashClaim, KingdomsX, Residence, UltimateClaims, MinePlots), plus FTB Chunks and Open Parties and Claims on Fabric / NeoForge. Claim checks run in the async pipeline, off the teleport tick. You can add your own through `RegionVerifierRegistry` with one lambda.
- **Claim and faction anchors**: an action can land players relative to their own town, claim or faction land (`anchor: claimboundary`, `anchor: faction`). Towny, GriefPrevention and SaberFactions / FactionsUUID boundaries are supported directly; others plug in through `ClaimBoundaryProvider`. The anchor stays put while it's inside the claim and is recomputed at most once per cooldown, which keeps destination anchors stable every time land changes hands.
- **Safety filter**: `safety.yml` takes plain materials, block-state predicates (`OAK_SLAB[waterlogged=true]`), numeric ranges (`WATER[level>=5]`), vanilla and datapack block tags (`#minecraft:leaves`) and wildcards (`*[waterlogged=true]`). Grammar is in the details below.

### Networks

- **Cross-server `/rtp`**: Velocity with plugin messaging, or SQL / Redis for reservation tokens. Tested nightly on the in-repo devstack: 2 Velocity proxies, 2 lobbies, 2 backends behind Redis.

### For developers

- **API and addons**: `rtp-api` (pre / mid / post teleport hooks, region verifiers, config, scheduling) and `effects-api`. Addons compile against `rtp-api` only and load through a `ServiceLoader` SPI; one addon jar runs on Spigot, Paper, Folia, Fabric and NeoForge. The bundled addons (GUI picker, claim integrations, Countdown reference, scripted actions) unpack into `plugins/RTP/addons/` on first run; delete one from that folder to turn it off. Their source, and the Rift warmup-effect addon's, is under `addons/` as a starting point.
- **Swappable parts**: server backend, economy, location validity, shapes, world border, PvP checks, region file format and commands can all be replaced through SPI.
- **PlaceholderAPI**: queue depth (total / public / personal), last-teleport coordinates, player status.
- **bStats**: on by default; anonymous usage counts help me decide which platforms to spend time on.

---

## Performance

Most of an rtp plugin's work happens inside server API calls, so a profiler lists it under generic chunk functions. The public harness in [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP) attributes it back to the plugin. Rig: Ryzen 9 3900X for every row of the first table; the open-loop stress test further down ran on a second, Linux rig. Each other plugin ran on the latest version it supported at the time of the run, with cooldowns, delays and countdowns zeroed and its queue on at its default size where it has one. LeafRTP ran at its recommended settings.

<!-- only: bbb -->
Both LeafRTP downloads are built from the same code, and the LeafRTP rows apply to either.
<!-- /only -->

I compare four things: throughput under load, latency from `/rtp` to arrival, tick health (MSPT and ticks over 50 ms), and how far apart players land. Load comes from scripted bot clients that join the server like players, and the harness profiles each run with spark and JFR so a plugin's cost can be read off the main thread. Run IDs in the tables match the output folders in the harness.

About half of random candidates in Minecraft are ocean, lava or void. Checking a candidate by loading its chunk means generating terrain and stalling the tick whenever the candidate is bad. LeafRTP checks candidates against the `.mca` region files off-tick and keeps a queue of verified ones, so searching doesn't load chunks or block the main thread.

**Paper 26.2 & Folia 26.2 Benchmark Comparison (4,096 teleports per plugin, 16k radius circle, 3 clients):**

Peak throughput is measured under unpaced burst saturation (Paper Test A: `20261008-003310`). Responsiveness, tick stability, total main-thread CPU, and how far apart players land are measured under steady-state 5.0 TP/s pacing (Paper Test B: `20261008-024412`). Folia status is from the same paced workload on Folia 26.2 (Folia Test B: `20261008-052141`). Latency is dispatch to arrival.

| Plugin | Max Tested Throughput | Latency p50 / p99 | MSPT p50 / max | Ticks > 50 ms | Total Main-Thread CPU | Landing Pairs <= 48 blocks | Nearest Pair | Folia 26.2 Status |
|---|---|---|---|---|---|---|---|---|
| **LeafRTP** | **42.15 TP/s** (harness limit) | 6 / **14 ms** | **16.4 / 24.5 ms** | **0** | **227 s** (0.26 cores) | **0** (random: 81) | **70.0 blocks** | **Native** (4,096 / 4,096, 0 timeouts; sustained 180 TP/s on Linux) |
| JakesRTP | 23.20 TP/s | **0** / 76 ms | 28.8 / 92.1 ms | 271 | 285 s (0.32 cores) | 127 (random: 84) | 4.5 blocks | Refused (not marked as supporting Folia) |
| BetterRTP | 9.22 TP/s | 203 / 898 ms | 27.9 / 166.6 ms | 329 | 248 s (0.27 cores) | 126 (random: 84) | 2.2 blocks | Inoperable (0 of 5,686 on Linux, 100% async chunk errors) |
| HuskHomes | 8.37 TP/s | 264 / 626 ms | 21.1 / 37.8 ms | 0 | 298 s (0.33 cores) | 105 (random: 82) | 2.2 blocks | Paced only (4,095 / 4,096; drops >60 TP/s on Linux from turnaround and SQLite locks) |
| EzRTP | 7.91 TP/s | 171 / 923 ms | 25.7 / 49.4 ms | 0 | 261 s (0.29 cores) | 142 (random: 129) | 4.1 blocks | Fails (cross-region chunk errors, stuck bot lockup) |
| JustRTP | 4.43 TP/s | 345 / 2,729 ms | 23.4 / 36.3 ms | 0 | 395 s (0.37 cores) | 129 (random: 85) | 4.0 blocks | Degraded (caps at 10 TP/s on Linux; Nether ceiling check rejects Overworld Y>=127) |

- **Max tested throughput:** LeafRTP finished all 4,096 teleports in 97 seconds (42.15 TP/s) with requests sent back to back. Treat that as a floor: the 3-client harness ran out before LeafRTP did. The next highest was JakesRTP at 23.20 TP/s. With 48 bots on the Linux rig below, LeafRTP completed 96-99 TP/s against 100 offered.
- **Main-thread CPU vs. rate:** The table gives total main-thread CPU over the 4,096-teleport phase. A per-teleport figure varies with the rate, since the server keeps doing background tick work as time passes: at 42 TP/s saturation LeafRTP used 9.9 ms per teleport, while at 5 TP/s pacing it used about 0.26 of a core. Compare total CPU and MSPT across plugins.
- **Latency & lag spikes:** At 5 TP/s, LeafRTP answered from its pre-verified queue at a 6 ms median, dispatch to arrival, and no tick went over 50 ms. In the same run BetterRTP logged 329 ticks over 50 ms (max 166.6 ms) and JakesRTP 271 (max 92.1 ms).
- **Spacing:** In the paced run each other plugin landed 105-142 pairs of arrivals within 48 blocks of each other, more than a random scatter over the same land gives (82-129), with nearest pairs of 2.2-4.5 blocks. LeafRTP had **0 pairs within 48 blocks** against 81 expected at random, and its closest two arrivals were 70.0 blocks apart.
- **Folia** (`20261008-052141`, same 5 TP/s pacing): LeafRTP completed 4,096 of 4,096 and HuskHomes 4,095 of 4,096. BetterRTP threw `Cannot retrieve chunk asynchronously` on 6 of 6 warm-up attempts and was dropped from the run. EzRTP threw the same exception from `getHighestBlockYAt` on region threads; after that, 2 of the 3 test clients stayed stuck in an active attempt for the rest of the phase, which hit the 1,800 s cap with 1,415 successes and 949 timeouts. JakesRTP was refused by the Folia plugin loader (`not marked as supporting Folia`).

**Open-loop stress test, Linux (Paper 26.2, Threadripper 7970X, Ubuntu, Java 25, 16 GB heap, 48 bot accounts):**

The runs above wait for each teleport before sending the next, so a slow plugin is never asked for more than it can answer. Here the harness sends `/rtp` at a fixed rate per stage whether or not earlier ones have finished. A stage passes if at least 95% of the offered rate completes, MSPT p95 stays at or under 50 ms, and at most 1% of attempts time out or error. The stress point is the highest stage that passes.

| Plugin | Offered | Achieved | Failed | Latency p50 / p99 | MSPT p95 | Min TPS | Result | Run |
|---|---|---|---|---|---|---|---|---|
| **LeafRTP** | 100 TP/s | **98.7 TP/s** | **0 / 11,843** | 63 / 778 ms | 103.3 ms | 8.6 | Fails MSPT only | `20261009-054148` |
| **LeafRTP** | 100 TP/s | **97.7 TP/s** | **0 / 11,722** | 86 / 948 ms | 111.1 ms | 7.5 | Fails MSPT only | `20261009-070736` |
| LeafRTP, all six plugins loaded | 100 TP/s | 96.2 TP/s | 0 / 11,548 | 129 / 967 ms | 121.8 ms | 7.3 | Fails MSPT only | `20261009-044336` |
| EzRTP | 5 TP/s | 4.98 TP/s | 0 / 299 (both runs) | 155-156 / 908-918 ms | 23.3-23.7 ms | 20.0 | Passes (stress point) | `20261009-051201`, `-063819` |
| EzRTP | 10 TP/s | 7.2-7.7 TP/s | 23-28% timed out | 2,261-2,710 / 4,809-4,813 ms | 26.4-27.4 ms | 19.9-20.0 | Fails | same two runs |
| EzRTP | 20 TP/s | 8.2-8.3 TP/s | 37-38% timed out | 3,505 / 4,357-4,415 ms | 29.2-29.3 ms | 16.6-19.9 | Fails | same two runs |

- **LeafRTP at 100 TP/s on Paper:** no failed teleports in 35,113 and the server stayed up. The tick budget ran out first: MSPT p95 went to 103-122 ms and TPS dipped to 7-9. So 100 TP/s is past LeafRTP's stress point on Paper on this rig, because Paper executes all entity handoffs on a single main thread.
- **EzRTP on Paper** passed 5 TP/s in both runs. Asked for 10 or 20 it completed about 8 TP/s and timed out on 23-38% of attempts while MSPT stayed under 30 ms, so its limit is its own pipeline. That matches its 7.91 TP/s ceiling on the 3900X.

**Open-loop stress test, Linux (Folia 26.2, Threadripper 7970X, Ubuntu, 16 GB heap, 48 bot accounts):**

On Folia, world ticking is distributed across independent regional worker threads instead of one main thread. LeafRTP's off-tick Anvil pre-filter keeps candidate screening on background I/O threads (`RTP-Anvil-IO`, 94.1% of execution share in spark), so region threads only handle the final block check and player transfer.

| Plugin | Offered | Achieved | Dispatched Success | Latency p50 / p99 | Regional TPS min / mean | Process CPU | Result |
|---|---|---|---|---|---|---|---|
| **LeafRTP** | 60 TP/s | **59.88 TP/s** | 3,593 / 3,599 (99.8%) | 192 / 251 ms | 13.2 / 19.8 | 115.9 ms/att | Passes |
| **LeafRTP** | 100 TP/s | **99.97 TP/s** | 5,998 / 5,998 (100.0%) | 197 / 254 ms | 12.5 / 19.7 | 73.7 ms/att | Passes |
| **LeafRTP** | 140 TP/s | **139.97 TP/s** | 8,398 / 8,398 (100.0%) | 193 / 260 ms | 12.8 / 19.7 | 72.6 ms/att | Passes |
| **LeafRTP** | 180 TP/s | **175.22 TP/s** | 10,513 / 10,513 (100.0%) | 198 / 300 ms | 12.0 / 19.6 | 78.4 ms/att | Passes (stress point) |
| **LeafRTP** | 200 TP/s | **180.15 TP/s** | 10,809 / 10,809 (100.0%) | 200 / 308 ms | 11.7 / 19.5 | 81.0 ms/att | Client swarm saturated |
| HuskHomes | 40 TP/s | 39.50 TP/s | 2,370 / 2,385 (99.4%) | 409 / 1,205 ms | 16.0 / 20.0 | 124.6 ms/att | Passes |
| HuskHomes | 60 TP/s | 57.50 TP/s | 3,450 / 3,468 (99.5%) | 400 / 1,002 ms | 16.1 / 20.0 | 124.6 ms/att | Passes (stress point; 131 shed) |
| HuskHomes | 80 TP/s | 30.98 TP/s | 1,859 / 1,891 (98.3%) | 400 / 1,099 ms | 18.4 / 20.0 | 131.2 ms/att | Fails (2,907 shed; SQLite lock collision) |
| JustRTP | 10 TP/s | 9.90 TP/s | 594 / 599 (99.2%) | 1,055 / 3,576 ms | 19.4 / 20.0 | 261.1 ms/att | Passes (stress point) |
| JustRTP | 20 TP/s | 18.65 TP/s | 1,118 / 1,197 (93.4%) | 1,048 / 3,953 ms | 19.1 / 20.0 | 242.4 ms/att | Fails (failure rate > 1%) |
| JustRTP | 40 TP/s | 14.42 TP/s | 865 / 1,029 (84.1%) | 1,102 / 3,851 ms | 18.9 / 20.0 | 196.0 ms/att | Fails (1,370 shed; Nether ceiling bug) |
| EzRTP | 20 TP/s | 19.72 TP/s | 1,183 / 1,199 (98.7%) | 348 / 853 ms | 15.3 / 20.0 | 239.5 ms/att | Fails (25 stuck bots hot-swapped) |
| BetterRTP | 10-40 TP/s | 0.00 TP/s | 0 / 1,800 (0.0%) | NOT MEASURED | 19.7 / 20.0 | - | Fails (100% async chunk check errors) |
| JakesRTP | any | 0.00 TP/s | - | - | - | - | Refused by Folia plugin loader |

- **Max throughput detected on Folia:** LeafRTP sustained 100 TP/s (99.97 TP/s achieved, 100.0% completion) with nominal regional tickrates (19.7 TPS, 0.75 ms median region tick work). It scaled to 180 TP/s (175.22 TP/s achieved) with 198 ms median latency and 19.6 regional TPS. The max throughput detected was 180.15 TP/s at 200 TP/s offered, where all 10,809 dispatched teleports succeeded with zero errors before the 48-bot client turnaround saturated (at ~265 ms turnaround, 48 bots can absorb at most ~181 TP/s). Spark sampled 94.12% of CPU off-tick on `RTP-Anvil-IO (x128)`.
- **Competitors on Folia (Linux rerun):**
  - **HuskHomes:** Uses asynchronous chunk loading and in-memory `ChunkSnapshot` queries, which avoids Folia thread traps and holds 5-40 TP/s. However, because its median latency is ~400-450 ms (tail >1.2 s), 48 bots saturated at 60 TP/s. At 80 TP/s, achieved rate collapsed to 30.98 TP/s (2,907 shed attempts), and concurrent regional arrivals caused SQLite lock collisions (`SQLITE_BUSY`) during `/back` position logging.
  - **JustRTP:** Capped at 10 TP/s. At 20 and 40 TP/s it collapsed, shedding 1,370 attempts. Its `findSafeInNormal()` has a bug that rejects Overworld coordinates at Y >= 127 as alleged Nether ceiling spawns, causing repetitive retry loops and forcing over 10,000 synchronous chunk promotions.
  - **EzRTP:** Calling `getHighestBlockYAt` across regional thread boundaries triggered repeated `Cannot retrieve chunk asynchronously` exceptions, crashing commands mid-flight and leaving bots locked in an 'already teleporting' state that required 25 hot-swaps.
  - **BetterRTP 3.6.13:** Inoperable on Folia (0 of 5,686 teleports completed across both rounds). Throws `Cannot retrieve chunk asynchronously` on 100% of attempts and locks player states.
  - **JakesRTP 0.14.9:** Unsupported; Folia's plugin loader refused to load the jar (`not marked as supporting Folia`).

**Session leak in BetterRTP 3.6.13:** in my leak fuzzer, it kept whole player sessions in memory after players disconnected, and the count only went up. LeafRTP had the same kind of bug until 3.3.0; the same fuzzer found it, and it is fixed and covered by a test.

Full benchmark tables, with historical runs, are in [`RESULTS.md`](https://github.com/dailystruggle/RTP/blob/V3/helpers/StressTestRTP/RESULTS.md), and the full analysis is in the [harness notes](https://github.com/dailystruggle/RTP/blob/V3/helpers/StressTestRTP/PRE_WRITEUP.md).

**Where each plugin lands players (Paper 26.2, 4,096 teleports):**

![Cross-plugin destination scatter comparison](../assets/img/cross_plugin_destinations_scatter_chart.png)

*Where each plugin landed 4,096 players in the Paper 26.2 run (`20261008-003310`), every one set to the same 1,024 to 16,384 block circle around 0,0. The green box under each panel is the LeafRTP region shape that gives the same distance-from-center spread, so any of these distributions is a config change in LeafRTP. Each config is replayed through LeafRTP's own shape code over the same terrain: LeafRTP and BetterRTP match CIRCLE, EzRTP and HuskHomes match CIRCLE_NORMAL, and JustRTP and JakesRTP match the spiral power curve.*


**Caveats.** The region-file check only works on terrain that exists; any plugin has to generate a chunk that was never generated. The 3900X table used 3 clients with up to 4 teleports in flight, which is a small load; LeafRTP's 42.15 TP/s there is where that harness stopped. The Linux stress test used 48 idle bots with server view distance capped at 2: real connections and chunk sends, with nobody moving or building. It is n=3 for LeafRTP (one run with every plugin loaded) and n=2 for EzRTP, and its CPU-per-teleport columns lack an idle baseline, so I don't quote them here. Heap reached the 16 GB limit in all three LeafRTP 100 TP/s runs; I haven't yet separated retention from G1 simply not collecting until it has to. The Folia watchdog count reproduced across two runs; everything else in the 3900X table is n=1. Hardware, view distance, world state (how much of it is unsafe, whether it's pregenerated) and other plugins will move the numbers. The other plugins update often; if a number here is out of date, open a GitHub issue with a repro or a doc link and I'll correct it. Each plugin ran at its defaults. The harness measures dispatch-to-arrival latency, per-attempt cost, and success rate as defined above; it does not measure claim-plugin compatibility or safety rules.

Full methodology, per-run analyses, and the runs not shown here (equalized-radius Folia, pinned-heap GC profiling, the wide-radius single-plugin run): [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video of `/rtp` on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

---

## How it works

### Spatial memory

About 35-65% of typical Minecraft terrain is ocean, lava or void. LeafRTP remembers rejected ground across server restarts so it doesn't check the same dead ground twice. Locations are mapped in constant time, and `/rtp scan` pregenerates spatial memory off-tick. Mathematical derivations and distribution benchmarks: [Why LeafRTP exists](https://dailystruggle.github.io/RTP/site/why/).

### Region-file pre-filter

An Anvil (`.mca`) pre-filter reads biome and block data directly from region files on disk. Unloaded candidates are checked and rejected off-tick without chunk loading. The files hold the actual terrain, so custom generators (Iris, Terra, datapacks) and edited ground are checked as they are. When the pre-filter cannot answer (unpopulated chunk, unknown format), it falls back to the server engine's async chunk API within a strict tick budget.

### Pre-verified cache

Safe destinations are verified in advance and held in background queues. Answering `/rtp` serves an already-checked coordinate without waiting for chunk generation; on Paper the median from command to arrival was 6 ms in the paced benchmark. Automatic sweeps release chunk tickets from abandoned teleports, so nothing stays force-loaded past its reservation window.

### Spacing between players

Destinations cycle across available land, and a spot doesn't come up again until the shuffle has cycled. Consecutive landings are normally hundreds of blocks apart (closest pair 70 blocks at 16k radius in the Paper 26.2 run), so `/rtp` spam is a poor way to find someone's base, and no player coordinates are tracked. Each region shuffle uses a random seed generated at startup, so players can't predict landing spots from the world seed. Full spacing statistics and repetition rules: [Regions guide](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/#spacing-repeats-and-worst-case-by-mode).

### No shaded dependencies

I don't shade libraries. The SQL connection pool, Redis client, cross-server messaging, bStats and region-file reader are written in-house on plain Java. The web workspace connects outward to an ephemeral relay (like spark and LuckPerms) with no embedded HTTP server daemon, so there are no open listening ports and no library version conflicts.

### Maps and image exports

LeafRTP includes a standalone map renderer (`maps-api`) that generates live in-game maps and high-resolution diagnostic images without main-thread chunk I/O:

- **High-resolution image exports** (`/rtp visualization export <type>`): Exports standalone PNG and BMP image files to `plugins/RTP/charts/` (biomes, hazards, heatmaps, pipeline composites).
- **Combined diagnostic chart** (`/rtp visualization export comprehensive`): One panel with biome layouts, hazard overlays, candidate markers, and player spread metrics.
- **Held in-game maps** (`/rtp visualization`): Real-time biome layouts, rejected terrain, and candidate heatmaps on held Minecraft map items.
- **External web map integration**: Non-blocking tile providers and vector region borders for Dynmap, BlueMap, and Pl3xMap via `maps-api` and `anvil-api`.

### Chunk loading, by platform

- **Paper** and forks such as Purpur and Pufferfish: `World#getChunkAtAsync`; falls back to a main-thread load only if async loading returns no chunk.
- **Folia**: Pre-filter runs before the Region Scheduler; rejected candidates never hop a thread. Safe candidates load through Folia's async API and teleport through the Entity Scheduler.
- **Spigot** (and Arclight / Mohist): `.mca` pre-filter off-tick. Throughput is capped by Spigot's chunk generator, and unreadable candidates cost one on-tick chunk load.
- **Fabric** and **NeoForge**: In-tree adapters running the same core engine.

<details>
<summary><b>Charts: how LeafRTP avoids clumping and base hunting</b></summary>

These charts come from the visualizer test fixtures in `rtp-core`, which assert candidate distribution and spacing on real terrain:

**Coverage across radii.** The selector fills regions from small 32-chunk zones up to 512-chunk ones without clustering at the center or thinning out at the edge.

![Candidate path progression across radii](../assets/img/path_progression_radii_chart.png)

**Local stepping.** Zoomed into a single 32x32 Anvil region file (1,024 chunks), candidates are checked chunk by chunk with no jumps, so disk reads stay local.

![32x32 region file zoom](../assets/img/region_32x32_zoom_path_chart.png)

**Player separation.** With unconstrained random search, 35.8% of players land within render distance of an existing arrival. With LeafRTP's spaced distribution, 0% do within 8 chunks, and the minimum separation is 16 chunks.

![Player spacing comparison chart](../assets/img/side_by_side_downsampling_comparison_chart.png)

**Pre-screened candidates.** In a 32,768-block world with 10,000 cached candidates, 505 ocean-only region files are discarded directly from the map header without reading chunk data from disk. Candidates are spread evenly across all viable land.

![Backlog cache state across the region](../assets/img/full_l3_state_chart.png)

*For the derivations, algorithm benchmarks and unit test code, see [Shape Algorithm Verification](https://dailystruggle.github.io/RTP/site/shape-algorithms/) and [Why LeafRTP Exists](https://dailystruggle.github.io/RTP/site/why/).*

</details>

<details>
<summary><b>How I test it, and what the tests have found</b></summary>

A plugin can pass one of these tools and fail another.

| Tool | What it drives | What it catches |
|---|---|---|
| Stress harness (`helpers/StressTestRTP/`) | Scripted bot clients that join like players, in closed-loop, open-loop and paced modes, with spark and JFR attached | Throughput ceiling, MSPT and ticks over 50 ms, chunks and CPU per teleport, and which thread pays for them |
| Behaviour fuzzer (`scripts/bench_fuzz.py`) | Seeded bot programs: `/rtp`, move, quit, rejoin under a new name, die, console `reload`. Coverage-guided search, automatic replay and shrinking | Memory the server still holds after a full GC, scored against a control run with no rtp plugin. Every plugin gets the same seeds, bots and server |
| Jazzer targets | Region-file readers, the compression decoder, the NBT readers and the proxy wire parser | Crashes and unexpected exceptions on malformed input; failing inputs are saved as reproducers |
| Spacing simulations | Landing coordinates from real stress runs and from the shape models | Repeats, clustering and distance to the nearest earlier landing |
| Safety and mutation tests | One test per safety prohibition, plus weekly PIT on the teleport pipeline | Rules that must never break, and tests that wouldn't notice a broken rule |

What they found:

- **Retained player sessions (fuzzer).** LeafRTP kept a disconnected player's objects alive through its last-teleport records. The fuzzer flagged it and it reproduced on replay; it is fixed in 3.3.0 and covered by a regression test. The latest 3.3.0 run retained 0 players across 86 episodes, including config reloads. The run found nothing within its budget, which doesn't rule a leak out. The same method found a session-retention leak in BetterRTP 3.6.13 (see [Performance](#performance)).
- **Main-thread chunk load on arrival (spark).** A profile of the Paper stress run put 14.1 s of a 115.7 s phase (about 3.4 ms per teleport) in the arriving player's first tick loading its landing chunk synchronously. The cause was the arrival chunk ticket being released before the player's own ticket held it. The fix keeps the ticket through the first ticks. A later spark profile of the Linux 100 TP/s run (`20261009-085505`, 5.8k teleports per profile window) shows `Entity.isInWall` at 0.02-0.03 s of 59.5 s on the server thread, with no chunk load under it.
- **Repeats under spacing (simulation).** Modelling consecutive landings showed that with `expand: false` a used chunk can come up again at about the random-pick rate. This is disclosed in [the region spacing notes](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/#spacing-repeats-and-worst-case-by-mode). Both obvious fixes were tested and ruled out.
- **Harness errors.** An overnight driver loaded the wrong plugin set for four Linux rows, so they measured a server without the plugin. I caught it on inspection and threw those rows out.
- **Fuzzer scoring noise.** An object count the control run lacks can score as a leak while retained memory matches the control. I read verdicts against bytes retained and replay every candidate before trusting it.

I reproduce every finding from a recorded seed before counting it. The fuzzer method, limits and run logs are in [`LEAK_FUZZING.md`](https://github.com/dailystruggle/RTP/blob/V3/helpers/StressTestRTP/LEAK_FUZZING.md).

</details>

<details>
<summary><b>Testing and quality gates</b></summary>

The build fails when coverage drops below a floor, and lowering a floor takes a commit. Gates that don't enforce yet say so in their row. Coverage percentages are from the `-Pcoverage` run of 2026-09-15. The badge at the top is SonarCloud's whole-tree number, which includes the platform adapters and addons these per-module gates leave out; it reads lower than the table.

| Module | Instruction / branch measured | Enforced floor | Target (90 / 80) |
|---|---|---|---|
| `metrics-api` | 100% / 94.6% | 0.95 / 0.85 | met |
| `tags-api` | 98.2% / 90.9% | 0.95 / 0.85 | met |
| `yaml-api` | 97.0% / 88.1% | 0.92 / 0.80 | met |
| `rtp-api` | 95.7% / 81.3% | 0.90 / 0.80 | met |
| `maps-api` | 94.7% / 82.1% | 0.90 / 0.78 | met |
| `commands-api` | 93.6% / 80.3% | 0.90 / 0.80 | met |
| `anvil-api` | 90.5% / 80.5% | 0.86 / 0.75 | met |
| `rtp-core` | 81.6% / 65.8% | 0.80 / 0.64 | not met (branch coverage still climbing) |
| `rtp-proxy-common` | 87.0% / 68.8% | 0.85 / 0.67 | not met (branch coverage still climbing) |

Safety-critical packages inside `rtp-core` have higher floors on top of the module number: the teleport pipeline at 0.87 / 0.75, region selection at 0.82 / 0.68, and world-border math at 0.99 / 0.89.

| Gate | What it enforces | Where it runs |
|---|---|---|
| Test suite | over 650 committed JUnit test classes; `rtp-core` alone has roughly 3,700 test cases | `gradle.yml` on every push and pull request |
| Mutation testing (PIT) | weekly PIT run over the teleport pipeline, region cache and world-border packages; the scheduled teleport-pipeline shard gates at 40% while I work toward 60% | `mutation-testing.yml` |
| Safety prohibitions | one automated test per prohibition (unsafe destinations, force-loaded chunks, claim bypass, swallowed teleport failures, main-thread chunk I/O, null-guard no-ops on the public API, hardcoded failure messages), plus ArchUnit layering and chunk-ticket rules | `gradle.yml` |
| Changed-line coverage | new and modified lines must be >= 80% covered | `gradle.yml` (`scripts/diff-coverage.py`) |
| Static analysis | SpotBugs plus a PMD ruleset with a project-specific rule that flags blocking constructs | `gradle.yml` (`-PstaticAnalysis`) |
| Fuzzing | Jazzer targets for the region-file readers, the compression decoder and the proxy wire parser. A separate behaviour fuzzer drives seeded bot players against a server and checks what stays in memory after a full GC, against a control run with no rtp plugin | `fuzzing.yml` (weekly, Jazzer); the behaviour fuzzer (`scripts/bench_fuzz.py`) is run by hand |
| Profiling and load | Stress harness with spark and JFR attached, bot clients, open-loop and paced modes. It is the source of every benchmark number above | run by hand from `helpers/StressTestRTP/` |
| Binary API compatibility | japicmp check against a released baseline is wired up but not enforcing yet: the configured baseline isn't a published version and the check skips. Removals follow a 2-minor deprecation policy | `gradle.yml` (japicmp) |
| Dependency CVEs | OWASP dependency-check fails at CVSS >= 4 on the scanned files; scanning the resolved Gradle dependency graph and shaded jar isn't in place yet | `dependency-check.yml`, weekly and on dependency changes |
| Release provenance | SLSA build provenance on every release; GitHub releases also carry a CycloneDX SBOM and SHA-256 / SHA-512 checksums | `release.yml`, `release-bbb.yml` |
| Runtime acceptance | nightly multi-server devstack: 2 Velocity proxies, 2 lobbies, 2 backends over Redis, on Velocity + Paper + Folia + Fabric (MC 1.21.11, Java 25) and NeoForge (MC 1.21.1, Java 21) | `devstack-acceptance.yml` |
| Encoding and docs | tracked text files scanned for UTF-8 mojibake; the docs site builds from the same tree | `gradle.yml`, `docs.yml` |

**Coverage scope.** The `rtp-core` and `rtp-proxy-common` floors sit a point or two under the measured numbers and only go up. The uncovered remainder in `rtp-core` is mostly platform bootstrap and dispatch code, which the nightly devstack exercises. Spigot, NeoForge, BungeeCord and non-LTS Java have no scheduled live CI suite and are best-effort. The weekly Jazzer job runs each fuzz target as its own job for the configured duration, seeded from real region files.

</details>

---

## Why I made it

I started this project for the challenge of solving a hard problem. Every time a player ran `/rtp`, the plugin took the blame for server lag. After watching 3Blue1Brown videos, I set out to prove mathematically that spatial memory could fix that, using a custom space-filling curve to spread candidates evenly without rerolling.

I built the rest in stages:
- **v1:** Proved the math. Built the space-filling spiral to map 2D territory uniformly, so picks don't duplicate or clump and no player positions need tracking.
- **v2:** Tackled initial SPI models and platform compatibility, though latency degraded significantly in the process.
- **v3:** Rebuilt so one core runs on every platform. Switched from chunk forceloading to ticketing, added explicit task tracking and cleanup so nothing leaks, split features into modular SPIs, made it testable without a server, and set rules for AI tooling: I design the seams and make the decisions recorded in the ADRs, and AI writes code inside those seams (repetitive wrappers, platform APIs I didn't know well, unit tests, devstack YAML) that I review before it lands.
- **3.3.0:** Added static analysis, fuzzing, mutation testing and CPU and memory profiling, and used them to remove the bottlenecks behind the benchmark numbers above.

Architecture decisions, benchmarks, and design history are recorded in the [Architecture Decision Records](https://dailystruggle.github.io/RTP/adr/) and [Why LeafRTP Exists](https://dailystruggle.github.io/RTP/site/why/). [AI Usage](https://github.com/DailyStruggle/RTP/blob/V3/docs/dev/AI_USAGE.md) covers what AI tooling writes here and what I don't use it for: system design, code review and git operations.

<!-- only: bbb-pro -->
<!-- kind: promo -->
---

## What paying gets you

Same code, config and commands as the free download, under the same MIT licence. Nothing in the plugin is locked. Most of the development has been on my own dime, and paying helps fund it:

| This listing | Free download |
|---|---|
| Priority on my support queue | Community support, best effort |
| New Minecraft versions and platforms first | Stable builds, once they've settled here |

New platforms and backends go out here first because they need hands-on support while they settle, and this is the only place I promise that. Once something is stable it goes to the free channel. A native BungeeCord proxy adapter is planned; for now BungeeCord networks use plugin messaging on the backend.

Switching between the free download and this one is a jar swap. Config, data files and commands stay the same.
<!-- /kind -->
<!-- /only -->

---

<details>
<summary><b>Commands, permissions, configuration files</b></summary>

**Commands** (aliases: `/rtp`, `/wild`):

| Command | Description | Permission |
| --- | --- | --- |
| `/rtp` / `/wild` | Random teleport to the default region for your current world (or the GUI picker, if that addon is installed) | `rtp.use` |
| `/rtp region:<name>` | Teleport to a named region | `rtp.region` / `rtp.regions.*` |
| `/rtp world:<world>` | Teleport within a specific world | `rtp.world` / `rtp.worlds.*` |
| `/rtp player:<name>` | Teleport another player | `rtp.other` |
| `/rtp biome:<biome>` | Teleport to a chosen biome | `rtp.biome` / `rtp.biome.*` |
| `/rtp action <name> [player]` | Trigger a scripted action, arena, or matchmaking duel | `rtp.action` / `rtp.action.<name>` |
| `/rtp action cancel [action]` | Leave a matchmaking queue or cancel an action entry | `rtp.action.cancel` |
| `/rtp back` | Return to where you were before the last `/rtp` | `rtp.back` |
| `/rtp centerx=<x> centerz=<z> radius=<r>` | One-off overrides for this call; units allowed (`radius=10km`) | `rtp.params` |
| `/rtp menu` | Player book menu | `rtp.use` |
| `/rtp admin` | Operator book menu | `rtp.menu.admin` |
| `/rtp editor [local\|trust\|apply]` | Web editor link, offline copy, browser trust, token apply | `rtp.editor` |
| `/rtp info` | Operator diagnostics | `rtp.info` |
| `/rtp reload [file]` | Reload all configuration, or one file | `rtp.reload` |
| `/rtp config <file> view\|set <k>=<v>` | View or set a config key, then reload | `rtp.config` |
| `/rtp config import [source] [path=<dir>] [overwrite=true]` | Translate another rtp plugin's config; never overwrites without `overwrite=true` | `rtp.config` |
| `/rtp config import permissions [source] [apply=true]` | Dry-run the LuckPerms commands that copy BetterRTP, JustRTP or EzRTP nodes onto `rtp.*`; `apply=true` runs them | `rtp.config` |
| `/rtp admin setup <world\|gameplay\|perf\|preview\|confirm>` | Guided first-time setup from preset configs | `rtp.admin.setup` |
| `/rtp scan start\|pause\|resume\|reset\|cancel` | Offline spatial memory pregeneration (was `/rtp fill` in 2.x) | `rtp.scan` |
| `/rtp visualization [held\|export <type>]` | Render live held map charts or export high-resolution PNG/BMP diagnostic images | `rtp.admin` |

**Permissions** (full set in `plugin.yml`):

| Permission | Default | Grants |
| --- | --- | --- |
| `rtp.use` | `true` | Use `/rtp` and `/wild` |
| `rtp.see` | `true` | Tab-complete `/rtp` |
| `rtp.free` | `op` | Skip the Vault charge |
| `rtp.params` | `op` | Per-call overrides (`centerx=`, `centerz=`, `radius=`) |
| `rtp.personalqueue` | `false` | Reserve a per-player pre-warmed location |
| `rtp.onevent.*` | `false` | Auto-RTP on join / firstjoin / respawn / changeworld / move / teleport |
| `rtp.back` | `op` | `/rtp back` (not declared in `plugin.yml`; op by server default) |
| `rtp.editor` | `op` | `/rtp editor` and its subcommands (not declared in `plugin.yml`; op by server default) |
| `rtp.servers.*` | `true` | Cross-server destinations in network mode (was `op` before 3.3.0) |
| `rtp.admin` | `op` | `/rtp clear`, plus `rtp.reload`, `rtp.config`, `rtp.scan`, `rtp.info` and `rtp.menu.admin` |
| `rtp.admin.setup` | `op` | `/rtp admin setup` wizard |
| `rtp.*` | `op` | Everything |

**Configuration files** under `plugins/RTP/`. Every file reloads at runtime (`/rtp reload`) and accepts human units like `10km` or `30s`. Most servers only edit the core files:

| File | Controls | Example keys |
| --- | --- | --- |
| `config.yml` | Delays, cooldowns, move-cancel distance | `teleportDelay`, `teleportCooldown`, `cancelDistance` |
| `definitions/regions/<name>.yml` | Shape, radius, center, price, permissions | `shape`, `radius`, `price`, `requirePermission` |
| `safety.yml` | Unsafe blocks (lava, water) and block tags | `unsafeBlocks`, `airBlocks` |
| `economy.yml` | Vault pricing and cancellation refunds | `price`, `refundOnCancel` |
| `definitions/actions/<name>.yml` | Portals, party spacing, and arena bounds | `minSeparation`, `confinement.damage` |

(Advanced database, network proxy, custom biomes, and message templates come with working defaults. See the [full configuration guide](https://dailystruggle.github.io/RTP/admin/configuration/CONFIGURATION/) for details.)

**Soft dependencies (all optional):** Vault (for the economy charge), PlaceholderAPI, and the claim, combat-tag and hologram plugins listed above. PaperLib is no longer needed.

</details>

<details>
<summary><b>safety.yml token grammar</b></summary>

Six token shapes can be mixed freely in `unsafeBlocks` and `airBlocks`:

- **Plain material:** `LAVA`, `MAGMA_BLOCK`.
- **Material + state predicate:** `OAK_SLAB[waterlogged=true]`. Multiple predicates AND together: `OAK_SLAB[waterlogged=true,type=top]`.
- **Numeric range predicate:** `WATER[level>=5]`, `LIGHT[level<8]`. Fails open on a missing or non-numeric value.
- **Vanilla block tag:** `#minecraft:leaves`, `#minecraft:fire`, `#minecraft:campfires`. Expanded from the server's live block-tag registry when the config loads; datapack and modded tags work too.
- **Tag + state predicate:** `#minecraft:slabs[waterlogged=true]`.
- **Wildcard + state predicate:** `*[waterlogged=true]`: one line instead of listing every waterloggable block.

Unknown tags and properties fail open: a config written for a newer MC version still loads on an older one. Malformed tokens are never silent: `[WARNING] [safety.yml] rejected token '<token>': <reason>`. Block states are only read when a token needs them, and plain-material configs cost nothing extra. Plain-material entries from an older `safety.yml` keep working unchanged.

</details>

<details>
<summary><b>FAQ</b></summary>

**Q: Will players land on top of each other or in each other's bases?**
A: No. A spot doesn't come up again until the shuffle has cycled, and on regions past about 720 blocks of radius consecutive spots are at least 256 blocks apart. See [Spacing between players](#spacing-between-players).

**Q: How does it avoid lagging the server?**
A: Most `/rtp` calls are served from a queue of locations checked before anyone typed the command. The queue refills off the main thread: it skips ground it has already rejected and checks the rest against the region files before loading any chunk. Details are in [How it works](#how-it-works).

**Q: Is it complicated to set up?**
A: No. Sizing and configuring your regions takes a couple of minutes through the guided setup wizard (`/rtp admin setup`), the web workspace (`/rtp editor`), or in-game book (`/rtp admin`). If you are migrating from another plugin, `/rtp config import` translates your existing files automatically.

**Q: How is the web editor secured? Does it open ports on my server?**
A: No. The web editor opens zero listening ports on your server. It communicates outbound-only via TLS to an ephemeral WebSocket relay, or over a token-authenticated loopback socket (`127.0.0.1`) for local edits. All messages require RSA-2048 signatures with strictly increasing sequence counters and single-use, expiring trust nonces.

**Q: Why is it called "LeafRTP" now instead of just "RTP"?**
A: "RTP" is the generic name for random teleport, and the old name was just the function. That worked for years with older search and word of mouth (passing 400k downloads under that name), but current search and marketplace indexes lump it in with every other random-teleport plugin, command and forum thread, and it stopped showing up. "LeafRTP" is a name that points at this plugin. The `/rtp` command, `rtp-api`, config paths and data files stay exactly as they were, and nothing changes for existing installs.

**Q: Does it work on Folia?**
A: Yes. How it loads chunks there is under [Chunk loading, by platform](#chunk-loading-by-platform). In the Folia benchmarks it had 0 watchdog stalls.

**Q: How do I set up teleportation between worlds?**
A: Resolution order: the player's current world (or the `world:` parameter) -> that world's target region -> the region's target world. See the admin guide.

**Q: Do you support triangle / diamond region shapes?**
A: Use the `Polygon` shape. A triangle is a 3-vertex polygon and a diamond is a rotated square.

**Q: Do I need Chunky or another pre-generator?**
A: No, but they work well together. `/rtp scan` walks a region off-tick, verifies safety and generates chunks that aren't on disk yet while recording which sectors are unsafe. Run Chunky first if you want the whole map on disk up front; scan then reads those chunks through the region-file pre-filter.

**Q: Iris / Terra / custom datapack generators, or a world upgraded from an older version?**
A: Yes. Region files are read directly: modded and namespaced IDs are kept, and `/rtp biome:<x>` matches what's on disk. Plugins that use the live noise-map lookup get this wrong after a version migration. Chunks that were never populated fall back to a live load.

**Q: What about the Linear (.linear) region format?**
A: Region pre-filtering reads standard Anvil (`.mca`) files directly because 4 KiB sectors allow random-access chunk checks without decompressing whole regions or shading third-party libraries. For `.linear` or any other unsupported format, the pre-filter returns `UNKNOWN` and falls back to live chunk loading on the server engine. The reader is pluggable through `RegionFileReader` SPI for addons.

**Q: I'm on NeoForge.**
A: There's a native NeoForge adapter for 1.21.x / 26.x running the same core as every other platform. I test it less than the Bukkit family and Fabric, and broader testing on the 26.x carrier is still ongoing. If you hit something there, it goes to the front of my list.

**Q: I'm on Forge.**
A: Run Arclight or Mohist and use this jar. A native Forge adapter isn't planned.

**Q: Memory and MSPT: should I worry?**
A: MSPT, no. Spatial memory stays compact (about 26 bytes per sector segment). Locations kept in the hot queue hold chunk tickets so arrival doesn't wait on chunk loading, and `MemoryTracker` makes sure those tickets are released. Under heap pressure (`maxHeapPercent`, or under 512 MB free) background filling pauses and the held tickets are dropped back to cold storage, which frees the pinned chunks right away.

**Q: Can I use ItemsAdder, Oraxen, Nexo, or CustomModelData in the GUI menu?**
A: Yes. Icon strings in `guimenu.yml` accept prefix routing for `ia:<id>`, `oraxen:<id>`, `nexo:<id>`, `hdb:<id>`, `head:<player>`, `base64:<hash>`, and `<MATERIAL>:<cmd>`. Because commercial plugins carry closed-source licenses and commercial price tags, automated CI cannot run proprietary binaries; testing relies on mock contract stubs and open-source equivalents, so third-party proprietary compatibility is maintained on a best-effort basis. If an item plugin is absent or changes unexpectedly, LeafRTP falls back to vanilla materials. See [Custom Item & Menu Integration](https://dailystruggle.github.io/RTP/admin/CUSTOM_ITEMS/).

<!-- only: bbb-pro -->
<!-- kind: promo -->
**Q: Should I pay for this or use the free download?**
A: The free one, unless you want priority support or early builds. The code is the same.
<!-- /kind -->
<!-- /only -->

**Q: How do I report a bug?**
A: Open a GitHub issue with your server version, LeafRTP version, platform, the relevant config files and the error from the log. The [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) has a full reproduction template.

</details>

<details>
<summary><b>Support</b></summary>

<!-- not: bbb-pro -->
Support is me, in my spare time, so it's best effort.

- I help with bugs and config questions once you've had a look at the admin guide.
- A bug report needs: server version, LeafRTP version, platform, `config.yml`, `regions/`, `safety.yml`, and the relevant part of the server log. If something's missing I'll ask for it.
- "It doesn't work" isn't something I can act on. Tell me what you did, what you expected, and what happened instead.
- There's no guaranteed response time. Safety bugs (bad landings, chunk leaks, claim bypasses) get looked at first.
- Feature requests go in GitHub issues.
<!-- /not -->
<!-- only: bbb-pro -->
<!-- kind: promo -->
Support comes from me, the person who wrote the code.

- Tickets from this listing go ahead of free-download tickets, and this is where early-access platforms and backends get supported while they settle.
- Response time: 24-72 h on weekdays. Safety bugs go first regardless.
- Bug reports with server version, plugin version, platform, configs and log lines get fixed fastest. Most setup questions are answered in the admin guide.
- Feature requests go in GitHub issues.
<!-- /kind -->
<!-- /only -->

</details>

---

## Links

- [**Documentation site**](https://dailystruggle.github.io/RTP/): every doc in one searchable place
- [**Admin and configuration guide**](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/): install, configure, command reference
- [**Addon / API developer guide**](https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/): `rtp-api` and examples
- [**Changelog**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**Source on GitHub**](https://github.com/dailystruggle/RTP): issues, contributions, the benchmark harness
<!-- only: bbb-pro -->
- [**Free download**](https://modrinth.com/plugin/rtpv3)
<!-- /only -->
