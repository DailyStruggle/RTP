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
maintains this as a community project; write like that, not like a company.
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
    1 ms latency, single jar). Zero algorithmic exposition or derivations here.

Zone 2: Operational On-Ramp & Zero-Friction Migration (Immediate Action)
  - Audience: Action-oriented server operators ready to deploy or switch.
  - Scope: 6-step quick-start install, /rtp config import (zero-friction switch),
    /rtp admin setup / web editor, common setups, player & admin features.
  - Rule: Maximize net utility by minimizing time-to-deployment. An admin should
    be able to get a safe, lag-free setup running without scrolling past this zone.

Zone 3: Architectural Proof & Technical Transparency (Deep Foundation)
  - Audience: Folia operators, network architects, skeptics, and engineers.
  - Scope: Full benchmark tables (harness in helpers/StressTestRTP/), scatter
    charts, spatial memory math (Archimedean spiral, Hilbert curves, keyed
    permutations, Dyadic strides), Anvil .mca pre-filter, quality gates, and FAQ.
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

**{{name}} is a `/rtp` command.** It teleports a player to a random, safe spot in the world and tries to cost the server as little as possible while doing it. I've worked on it in my own time since 2021. The benchmarks and the test harness are in the repo, and every number below can be rerun from them.

<!-- only: bbb-pro -->
<!-- kind: promo -->
This listing is how you pay for support. The code is the same as the [free download](https://modrinth.com/plugin/rtpv3) and all of it is MIT licensed. Paying doesn't unlock anything in the plugin; details are in [What paying gets you](#what-paying-gets-you) below.
<!-- /kind -->
<!-- /only -->
<!-- only: bbb-lite -->
<!-- kind: promo -->
There is also a paid LeafRTP-Pro listing on BuiltByBit. It's the same code; paying for it gets you priority support and early builds, and nothing in the plugin is locked.
<!-- /kind -->
<!-- /only -->
<!-- only: modrinth -->
One jar for Paper, Folia and Spigot (1.20+), Fabric (needs Fabric API) and NeoForge (1.21.1+), up to 26.x, and it also loads as a Velocity plugin for cross-server `/rtp`. Java 21+.
<!-- /only -->
<!-- only: hangar -->
On Paper all candidate checking runs off the tick thread. On Folia, candidates are screened against the region files first, nothing waits on a synchronous chunk load, and only the final block check of a loaded chunk runs on its region thread. The same jar also loads on Velocity for cross-server `/rtp`.
<!-- /only -->

### The short version

- [**Non-blocking**](https://dailystruggle.github.io/RTP/admin/configuration/PERFORMANCE/): Search runs off-tick and pre-screens Anvil (`.mca`) files on disk before chunk loading. High concurrency won't stall tick loops or trip Folia watchdogs.
- [**Pre-cached**](https://dailystruggle.github.io/RTP/site/why/#background-processing-and-memory): Verified safe destinations wait in background queues, serving teleports in 1 ms median without waiting on chunk generation.
- [**Uniform**](https://dailystruggle.github.io/RTP/site/why/): Keyed space-filling spiral shuffling distributes players evenly (0 duplicate landings in 4,096 runs; closest pair 78 blocks) without tracking player positions.
- [**Persistent**](https://dailystruggle.github.io/RTP/admin/configuration/TTL/): Rejected oceans, lava, and claims persist across restarts (`advanced/ttl.yml`), skipping redundant I/O on known dead ground.
- [**Universal**](https://dailystruggle.github.io/RTP/admin/MIGRATION/#migrating-from-competitor-plugins-betterrtp-justrtp-ezrtp-jakesrtp): `/rtp config import` uses search-engine-style fuzzy matching and synonyms to translate configs from existing RTP plugins without touching original files.
- [**Visual**](https://dailystruggle.github.io/RTP/admin/WEB_EDITOR_GUIDE/): Draw polygon and donut regions over real terrain via `/rtp editor`, or use the guided in-game `/rtp admin setup` wizard.
- [**Unified**](https://dailystruggle.github.io/RTP/admin/QUICK_START/): One jar runs across Paper, Folia, Spigot, Fabric, NeoForge, and Velocity without separate builds or bridge plugins.
- [**Scriptable**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): Built-in YAML actions handle portals (`/rtp trigger`), PvP matchmaking duels (1v1, 2v2, teams), party scatter, shrinking arenas, and claim-relative drops.
- [**Exploit-safe**](https://dailystruggle.github.io/RTP/admin/CLAIM_PLUGIN_COMPATIBILITY/): Fail-closed claim checks, PvP damage cancel, zero open firewall ports.

Benchmark comparisons and hardware metrics are in [Performance](#performance).

## Common setups

Most people just want `/rtp` to work without lagging the server. Past that, these work natively without extra plugins:

- [**Spawn portals & launch pads**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): A cuboid trigger (`/rtp trigger`) over a portal frame or launch pad runs teleports with countdown holograms, sounds, and particle trails.
- [**Group & party scatter**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): The bundled `scatter.yml` action disperses queued parties to safe spots with guaranteed spacing (`minSeparation` blocks apart).
- [**First-join random spawn**](https://dailystruggle.github.io/RTP/admin/RECIPES/#rtp-on-first-join-random-spawn-for-new-players): Grant `rtp.onevent.firstjoin` to scatter new players across the wilderness on login; the login reserve cache keeps a destination pre-warmed so entry feels instant.
- [**1v1 and 2v2 PvP matchmaking**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): Bundled `challenge.yml`, `arena.yml`, and `teams.yml` actions queue and pair players for 1v1 duels or 2v2 squad battles into temporary bounded arenas with countdowns, border constraints, and automatic cleanup.
- [**Near-player & claim hunting**](https://dailystruggle.github.io/RTP/admin/ACTIONS/): Bundled `nearplayer.yml` and `nearclaim.yml` actions land players safely near random players or town and claim perimeters (`anchor: entity`, `anchor: claimboundary`) without inline claim-plugin overhead.
- [**Sky drops**](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/): A `Fixed` vertical adjustor at Y=250+ pairs with a slow-falling potion effect for aerial parachute drops.

---

## Install

**Requirements:** Java 21+. Paper, Folia, Spigot or another Bukkit-family server (Arclight / Mohist for Forge) on 1.20+, or Fabric (with Fabric API) / NeoForge on 1.21.1+, up to 26.x.

1. Drop `{{jar}}` into `plugins/` (or `mods/` on Fabric / NeoForge).
2. Start the server, join, and type `/rtp`. By default it opens the destination GUI (from the bundled GUI addon), which is an overview of its own: available regions, worlds, biomes, and queues.
3. Configure your regions and gameplay. Four routes edit the same underlying files and hot-reload at runtime: `/rtp admin setup` (guided console/chat wizard), `/rtp admin` (in-game operator book), `/rtp editor` (interactive web canvas), or the YAML files under `plugins/RTP/`. Suffixes set the unit: `radius: 10km`, `radius: 625c`, `cooldown: 30m`. See [Regions](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/) and [Worlds](https://dailystruggle.github.io/RTP/admin/configuration/WORLDS/).
4. If migrating from another rtp plugin, run `/rtp config import` to translate existing worlds, radii, cooldowns, and permissions automatically without touching your original files.
5. Storage and network: `advanced/database.yml` stores data in local SQLite out of the box; set `type` to `mysql` or `postgresql` for a shared database. `advanced/network.yml` handles proxy and cross-server teleports across Velocity and Redis.
6. Optional spatial pregeneration: run `/rtp scan start` to pre-screen region files off-tick ahead of time. It maps and records oceans, lava, and claims into persistent memory (`advanced/ttl.yml`) so live player teleports never pay search or chunk loading costs during gameplay.

Start here: [**Quick start**](https://dailystruggle.github.io/RTP/admin/QUICK_START/) and [**Intended usage**](https://dailystruggle.github.io/RTP/site/intended-usage/). The full [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) covers the rest.

---

## Features

### Setting it up

Every server has different world borders, world routing, and gameplay rules. Sizing your regions and mapping them to worlds is the core operational step. LeafRTP provides two comprehensive administrative interfaces (one in-game and one browser-based), along with guided setup and migration tools. All routes edit the same underlying files, accept human units (`radius: 10km`, `cooldown: 30m`), and hot-reload at runtime without server restarts.

#### Complete Management Workspaces (In-Game or Dual-Screen)

- [**Web workspace**](https://dailystruggle.github.io/RTP/admin/WEB_EDITOR_GUIDE/) (`/rtp editor`): Browser-based visual IDE for dual-screen administration. Combines a 2D vector cartography canvas (drag polygon vertices, donut exclusion rings, and inspect real world terrain and biomes) with an in-browser configuration editor. Features real-time schema diagnostics, unit conversion chips (`256c` to blocks), staged YAML diff reviews, hybrid thesaurus search (`fee` to `price`), and a dynamic documentation drawer that tracks your cursor with version-pinned option guides directly from the running JAR.
- [**In-game admin book**](https://dailystruggle.github.io/RTP/admin/configuration/IN_GAME_CONFIG/#1-the-interactive-admin-panel-rtp-admin) (`/rtp admin`): Full in-game operator book for adjusting settings without leaving Minecraft. Includes interactive configuration editing with keyword search, setup prefabs, region management, scan controls, live engine diagnostics, and bundled offline documentation (`/rtp docs`).

#### Onboarding & Migration Fast-Tracks

- [**Setup wizard**](https://dailystruggle.github.io/RTP/admin/QUICK_START/#guided-setup-rtp-admin-setup) (`/rtp admin setup`): Guided console or chat questionnaire that sizes regions to world borders, configures gameplay defaults (cooldown, delay, pricing, safety toggles), tunes cache limits, previews the resulting configuration, and writes automatic `.bak` backups before saving.
- [**Universal config import**](https://dailystruggle.github.io/RTP/admin/MIGRATION/) (`/rtp config import`): Translates existing worlds, radii, centers, shapes, cooldowns, prices, and database connection blocks from older RTP plugins using fuzzy synonym matching. `/rtp config import permissions` generates corresponding LuckPerms commands.

#### Runtime Operations & Tooling

- [**Spatial pregeneration**](https://dailystruggle.github.io/RTP/admin/configuration/TTL/) (`/rtp scan`): On-demand offline pregeneration (`start`, `pause`, `resume`, `reset`, `cancel`) that pre-screens region files off-tick to discover and store oceans, lava, and claims into persistent TTL storage (`advanced/ttl.yml`). Live player teleports skip search overhead entirely without running continuous background tasks.
- [**Live visualizations**](https://dailystruggle.github.io/RTP/admin/configuration/IN_GAME_CONFIG/#c-diagnostics--monitoring) (`/rtp visualization`): Renders region boundaries, biome layouts, rejected locations, selection heatmaps, and performance graphs directly onto held in-game maps in real time.
- [**Engine diagnostics**](https://dailystruggle.github.io/RTP/admin/QUICK_START/#diagnostics-rtp-info) (`/rtp info`): Reports live queue depth, pipeline latency percentiles, chunk-ticket leak rates, TPS/MSPT, database round-trips, and per-region generation success rates.
- [**Units & hot reload**](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/): All configuration keys parse distance (`b`, `c`, `r`, `km`), time (`t`, `ms`, `s`, `m`, `h`, `d`), and memory (`kb`, `mb`, `gb`). Apply changes instantly with `/rtp reload` or `/rtp config <file> set <key>=<value>`.
- **Docs in the jar**: The admin guide for your exact version unpacks into `plugins/RTP/docs/` on first run, in case the website has moved on to a newer version.

![Web editor: 2D vector cartography canvas with space-filling Hilbert walk, invariant checks, and staged diff](../assets/img/web_editor_cartography.png)

*Visual region editor: real-world terrain overlay, space-filling Hilbert walk paths, mathematical invariant validation, and staged diff inspector.*

![Web editor: configuration IDE with contextual documentation tracker, setting schema, and inheritance materialization](../assets/img/web_editor_docs.png)

*Configuration IDE: in-browser YAML editor with contextual documentation tracking your cursor, setting schemas, gotcha warnings, and one-click reference materialization.*

<div align="center">

![Admin book](../assets/img/menu_1.png) ![Admin book: config files](../assets/img/menu_2.png)

*`/rtp admin`: the operator book and its config file list.*

</div>

### For players

- **`/rtp` and `/wild`**: to the world's default region, or pick a region, world or biome (`/rtp region:<name>`, `/rtp biome:<biome>`). `/rtp back` returns players to their prior location.
- **Menus & custom items**: `/rtp menu` provides an interactive player book on Paper, Folia, Fabric and NeoForge (chat pages on Spigot) for teleporting or picking a region, world or biome. The bundled GUI addon provides a chest-based destination picker with at-a-glance barrier indicators for blocked destinations, customizable hover lore, and prefix routing for custom items (`ia:`, `oraxen:`, `nexo:`, `hdb:`, Base64 heads, and CustomModelData; see [item compatibility](https://dailystruggle.github.io/RTP/admin/CUSTOM_ITEMS/)).
- **Per-player queues** (`rtp.personalqueue`): personal reserve queues alongside the shared queue so high concurrency never delays an individual player's teleport.
- **Effects**: particles, sounds, fireworks, potions, titles, action bar notices, console or player commands, and holograms on every teleport phase, gated by `rtp.effect.<stage>.*` permissions. Holograms use the 1.19.4+ text display entity directly; DecentHolograms and HolographicDisplays are supported if present.
- **Landing platforms & safety**: temporary landing platforms with automatic decay (preventing ocean, void, or mid-air falls), post-teleport invulnerability window, movement-cancel, damage-cancel, countdown holograms, and warmup messages.

![In-game destination menu with live server health tooltip](../assets/img/menu_server_health.png)

*`/rtp menu`: in-game chest menu with live server health and destination selection.*

### Gameplay and worlds

- **Regions**: any number per world with customizable shape (Square, Circle, Rectangle, Polygon), radius, center, curve weighting, vertical bounds, world override, permission gate, and price. Vertical adjustors (Linear, Jump, Fixed) for sky islands, void worlds, aerial parachute drops, and Nether ceilings. A world's `override` key (`definitions/worlds/<name>.yml`) routes Nether or End teleports to designated safe worlds.
- **Arrival schematics**: drop a Sponge `.schem` named after a region into `plugins/RTP/advanced/schematics/` and every teleport into that region pastes it centered on the landing spot. Decoded in-house, no WorldEdit needed, and claim-aware.
- **Auto-RTP on events**: join, first join, respawn, world change, move, and teleport (`rtp.onevent.*`). A login reserve cache keeps destinations ready so join-time teleports feel instantaneous.
- **Economy**: charge per `/rtp` through Vault, per-region pricing, refund on cancel, and `rtp.free` permission bypass.
- **PvP / combat-tag gate**: off by default; refuses or delays `/rtp` for players who recently dealt or took PvP damage. Includes built-in tracking or hooks into PvPManager, CombatLogX, or Simple Combat Log if installed.
- **Command blocks and console**: the same unified command parser handles player, console, and command-block callers.

### Action engine

LeafRTP includes a declarative action engine for multi-entity, confined, and anchor-driven teleports declared in YAML (`definitions/actions/<id>.yml`). It turns random teleportation into an adventure and event system without requiring separate minigame or portal plugins.

- **Physical trigger zones** (`/rtp trigger create|remove|list`): Define cuboid trigger boundaries over portal frames, launch pads, or thresholds. Stepping into the zone fires configured actions with countdown holograms, particle trails, sound stages, and combat verification.
- **Subspace group placement**: Teleports parties, squads, or rival duelists simultaneously with guaranteed minimum spacing (`minSeparation`) and elevation tolerance (`elevationTolerance`) off-tick, eliminating suffocation, entity stacking, and chunk-generation lag spikes.
- **Confinement and boundary enforcement**: Restricts participants to an active arena (`SUBSPACE`, `REGION`, `LEASH`, or custom `SHAPE`) with automatic pull-backs on boundary violations, session duration timers, and non-blocking disarm lifecycles.
- **Dynamic anchor resolution**: Roots teleports dynamically: `regionQueue` (pre-warmed world queue), `entity` (near a target or random player), `nearclaim` (near town or claim perimeters without inline claim-plugin overhead), or `location` (fixed landmark or dungeon coordinates).
- **PvP matchmaking queues**: Dedicated queueing for 1v1 duels, 2v2 squad matches, and team battles (`/rtp action <challenge|arena|teams|koth>`). Supports open matchmaking (auto-pairing any queued opponents) or targeted reciprocity (dueling a specific rival), match countdowns, and queue cancellation (`/rtp action cancel`).
- **Lifecycle scripts and scoreboards**: Scripted console, player, and action dispatches trigger on phase events (`onEnqueue`, `onStart`, `onBoundaryViolation`, `onExpire`, `onDeath`, `onCancel`). Isolated dummy scoreboards (`rtp_violations`, `rtp_time_left`, `rtp_in_bounds`, `rtp_alive`) update continuously for vanilla `@a[scores=...]` target selectors, datapacks, and command blocks.
- **Pre-warmed action queues**: Dedicated background candidate pools (`cacheSize`) keep multi-player placements pre-verified, so duel and event teleports dispatch instantly.

Full configuration reference, schema specifications, and bundled templates: [Actions & arenas](https://dailystruggle.github.io/RTP/admin/ACTIONS/).

### Claims and safety

- **Claim plugins**: 16 on Bukkit / Paper / Folia through the bundled claim addon (GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, HuskTowns, PlotSquared, RedProtect, CrashClaim, KingdomsX, Residence, UltimateClaims, MinePlots), plus FTB Chunks and Open Parties and Claims on Fabric / NeoForge. Claim checks run in the async pipeline, off the teleport tick. You can add your own through `RegionVerifierRegistry` with one lambda.
- **Claim- and faction-anchored destinations**: an action can land players relative to their own town, claim or faction land (`anchor: claimboundary`, `anchor: faction`). Towny, GriefPrevention and SaberFactions / FactionsUUID boundaries work out of the box; others plug in through `ClaimBoundaryProvider`. The anchor stays put while it's inside the claim and is recomputed at most once per cooldown, which keeps the spiral from drifting every time land changes hands.
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

An rtp plugin's cost hides inside server API calls, and a profiler files it under generic chunk functions. The public harness in [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP) attributes it back to the plugin. Rig: Ryzen 9 3900X for every row. Each other plugin ran on the latest version it supported at the time of the run, with cooldowns, delays and countdowns zeroed and its queue on at its default size where it has one. LeafRTP ran at its recommended settings.

<!-- only: bbb -->
Both LeafRTP downloads are built from the same code, and the LeafRTP rows apply to either.
<!-- /only -->

The number I care about most is **chunks loaded per teleport**, from the server's own chunk-load events. Up to 25 of them are the same for every plugin: the 5x5 area the server loads around the landing spot. The rest is the search. About half of random candidates are ocean, lava or void, and a plugin that loads a chunk to check one pays for every candidate it throws away. LeafRTP checks candidates against the region files first and rejects most of them there without loading a chunk.

**Folia 26.1.2, 6 GB pinned heap (`20260921-134507`), 3 clients, default harness pacing, 4,096 attempts per plugin:**

| | LeafRTP | EzRTP | JustRTP |
|---|---|---|---|
| Teleports completed | **4,096 / 4,096** | 4,030 / 4,096 | 4,020 / 4,096 |
| Wall time for 4,096 attempts | **4.9 min** | 10.0 min | 26.6 min |
| Region-thread CPU per attempt | **7.9 ms** | 21.4 ms | 79.6 ms |
| Process CPU per attempt | **182 ms** | 209 ms | 729 ms |
| Chunks loaded per attempt | 26.1 | **22.1** | 75.1 |
| GC pauses (count, total) | **128, 21.7 s** | 270, 47.9 s | 774, 138.8 s |
| Lowest 5 s TPS on the players' region | **18.79** | 13.04 | 15.25 |

JustRTP loaded about 3x the chunks and used 4x the process CPU and 10x the region-thread CPU. EzRTP loaded slightly fewer chunks than LeafRTP; its cost is on the region thread. In an earlier Folia 26.1 run (`20260617-191448`) EzRTP called the synchronous `World.loadChunk` on region threads and tripped Folia's watchdog 7 times, with one region unresponsive for 20.4 s. A second run reproduced the 7. LeafRTP had 0. The stall counts come from the server's own `latest.log`.

![Real-time engine diagnostics and radar dashboard](../assets/img/web_editor_diagnostics.png)

*Real-time engine diagnostics and radar: live tick budget, multi-tier location reservoirs, pipeline latency percentiles, stage duration breakdown, and safety watchdogs.*

The region-file check needs terrain that already exists; a chunk that has never been generated has to be generated whichever plugin asks for it.

![Cross-plugin destination scatter comparison](../assets/img/cross_plugin_destinations_scatter_chart.png)

*Where each plugin landed 4,096 players in the Paper 26.2 run (`20261008-003310`), every one set to the same 1,024 to 16,384 block circle around 0,0. The green box under each panel is the LeafRTP region shape that gives the same distance-from-center spread, so any of these distributions is a config change in LeafRTP, not a different plugin. Each config is replayed through LeafRTP's own shape code over the same terrain: LeafRTP and BetterRTP match CIRCLE, EzRTP and HuskHomes match CIRCLE_NORMAL, and JustRTP and JakesRTP match the spiral power curve.*

<details>
<summary><b>Full benchmark tables (Paper and Folia)</b></summary>

Only runs against current versions of the other plugins are listed; the older Spigot / Paper 1.20.1 runs were dropped once every plugin in them had shipped a newer release. Each run uses its own world and settings: compare plugins within a run.

**Metrics:** TP/s (higher is better) | latency percentiles (dispatch to teleport event, lower is better) | Min TPS (20.00 = no hiccup) | MSPT p99 / max (main-thread tick time in ms, lower is better) | Success (share of dispatched commands that produced a teleport).

A "success" is the harness seeing that player's `PlayerTeleportEvent` within 5 s of dispatching the command. It doesn't check where the player landed: a teleport onto a bad block counts as a success, and a plugin that gave up cleanly with a chat message counts as a failure. The two ways to fail are the 5 s timeout and a teleport event with no destination.

**Paper 26.2, unpaced (`20261008-003310`)** - 16 GB heap, 3 OPed clients, up to 4 teleports in flight, per-player gap 0, 4,096 attempts per plugin, 16,384-block radius circle around 0,0. EzRTP tested with biome checks active.

| Plugin | Wall time | TP/s | p50 | p95 | p99 | Min TPS | MSPT p99 (ms) | Chunks / att (attr / inc) | Success |
|---|---|---|---|---|---|---|---|---|---|
| **LeafRTP** | **1.6 min (97 s)** | **42.15** | **5 ms** | **7 ms** | **9 ms** | **16.19** | **80.6** | **0.18 / 2.92** | **4,096 / 4,096 (100 %)** |
| JakesRTP | 2.9 min (176 s) | 23.20 | 21 ms | 73 ms | 114 ms | 12.14 | 104.8 | 7.14 / 8.46 | 4,096 / 4,096 (100 %) |
| BetterRTP | 7.4 min (444 s) | 9.22 | 223 ms | 628 ms | 1,013 ms | 13.23 | 120.2 | 24.96 / 26.06 | 4,096 / 4,096 (100 %) |
| HuskHomes | 8.2 min (489 s) | 8.37 | 258 ms | 411 ms | 748 ms | 19.92 | 17.8 | 31.66 / 32.22 | 4,096 / 4,096 (100 %) |
| EzRTP | 8.6 min (518 s) | 7.91 | 240 ms | 748 ms | 1,125 ms | 18.01 | 88.3 | 30.94 / 32.07 | 4,096 / 4,096 (100 %) |
| JustRTP | 15.4 min (924 s) | 4.43 | 418 ms | 1,724 ms | 2,721 ms | 19.83 | 26.9 | 89.53 / 91.79 | 4,088 / 4,096 (99.8 %) |

**Paper 26.1, no pacing (`20260617-232754`)** - 16 GB heap, 3 OPed clients, up to 4 teleports in flight, per-player gap 0: every client sends the next `/rtp` as soon as the last one lands. About 600 s per plugin, after a warm-up.

| Plugin | TP/s | p50 | p95 | p99 | Min TPS | MSPT p99 / max (ms) | Success |
|---|---|---|---|---|---|---|---|
| **LeafRTP** | **18.7** | **1 ms** | **2 ms** | **46 ms** | **17.5** | **85.8 / 98.4** | **16,560 / 16,560 (100%)** |
| EzRTP | 13.1 | 30 ms | 189 ms | 322 ms | 10.3 | 156.8 / 292 | 7,018 / 7,137 (98.3%) |
| BetterRTP | 6.0 | 480 ms | 3,217 ms | 4,402 ms | 2.5 | 859 / 3,663 | 1,606 / 1,633 (98.3%) |

**Paper 26.1 and Folia 26.1, unpaced, 4,096 teleports per plugin.** 6 GB pinned heap, every pacing knob in the harness removed (`dispatch-interval = 0 ms`, `per-player-gap = 0 ms`, `immediate-redispatch = true`), up to 4 teleports in flight at once. 32,768 x 32,768 square border with a 1,024-block void around spawn: every landing has to be on natural terrain. EzRTP's water-surface platform option was turned off to hold it to the same rule. BetterRTP isn't in the Folia rows because it doesn't run there: its PaperLib fallback calls `CraftWorld.getChunkAt` from a global-region thread.

*Paper 26.1:*

| Plugin | Wall time | TP/s | Latency p50 | Latency p99 | Chunks loaded | Tick-thread allocation | Success |
|---|---|---|---|---|---|---|---|
| **LeafRTP** | **4.27 min (256 s)** | **15.98** | **82 ms** | **816 ms** | **70,165** | **21.7 GB (5.3 MB/att)** | **4,096 / 4,096 (100%)** |
| EzRTP | 12.86 min (771 s) | 5.31 | 283 ms | 1,009 ms | 103,506 | 77.9 GB (19.1 MB/att) | 4,088 / 4,096 (99.8%) |
| JustRTP | 24.09 min (1,445 s) | 2.83 | 926 ms | 2,059 ms | 311,967 | 238.2 GB (58.6 MB/att) | 4,064 / 4,096 (99.2%) |

*Folia 26.1:*

| Plugin | Wall time | TP/s | Latency p50 | Latency p99 | Chunks loaded | Tick-thread allocation | Success |
|---|---|---|---|---|---|---|---|
| **LeafRTP** | **5.17 min (310 s)** | **13.20** | **143 ms** | **851 ms** | **71,845** | **21.8 GB (5.4 MB/att)** | **4,096 / 4,096 (100%)** |
| EzRTP | 9.56 min (573 s) | 7.07 | 308 ms | 654 ms | 91,620 | 61.7 GB (15.4 MB/att) | 4,053 / 4,096 (98.9%) |
| JustRTP | 21.07 min (1,264 s) | 3.22 | 799 ms | 1,860 ms | 295,075 | 211.4 GB (52.9 MB/att) | 4,077 / 4,096 (99.5%) |

The chunk column points the same way as the pinned-heap run, with a wider gap on this world: LeafRTP loaded about 17 chunks per attempt here and JustRTP 76. JustRTP's search runs on the tick thread; on Paper that turned into 238 GB of tick-thread allocation, 10 full GC pauses and 6.4 s of GC stalls over the run. EzRTP is about 25% slower on Paper than on Folia for the same reason: with one tick thread there's nowhere else for its search to go. EzRTP's failed attempts on both platforms are timeouts from an unsynchronized message cache (`ConcurrentModificationException` in `MessageProvider.format`) and, on Folia, cross-region `CraftWorld.getHighestBlockYAt` calls. LeafRTP's candidate search is off-tick on both platforms, which is why its Paper and Folia numbers are close and its tick-thread allocation is 5 MB per attempt.

*Where the 4,096 players landed (Paper 26.1, `20260923-000854`):*

- **JustRTP:** 167 exact duplicate landings (4.1%) and 324 pairs within 48 blocks of each other. Clark-Evans R = 0.83 (clustered). With several accounts dispatching at once it handed the same coordinate to two players at the same time. As far as I can tell its cache has no reservation step during async chunk validation, which a single-client test would never show.
- **EzRTP:** 107 pairs within 48 blocks, closest pair 0 blocks apart (same chunk).
- **LeafRTP:** 0 duplicates, 0 pairs within 48 blocks, closest pair 78.4 blocks apart. Clark-Evans R = 1.04; a uniform random scatter reads about 1.0.

A wide-radius LeafRTP-only run on Folia (3 clients, hops out to 40k blocks) reached 26 TP/s with cache-served teleports at p50 1 ms; why I don't publish 26 as a ceiling is in the harness notes.

</details>

**Caveats.** 3 clients with up to 4 teleports in flight is a small load; more clients may push the throughput figures higher. The Folia watchdog count reproduced across two runs; everything else is n=1. Hardware, view distance, world state (how much of it is unsafe, whether it's pregenerated) and other plugins will move the numbers. The other plugins update often; if a number here is out of date, open a GitHub issue with a repro or a doc link and I'll correct it. Each plugin ran at its defaults, which is what most servers actually run. The harness measures dispatch-to-arrival latency, per-attempt cost, and success rate as defined above; it does not measure claim-plugin compatibility or safety rules.

Full methodology, per-run analyses, and the runs not shown here (equalized-radius Folia, pinned-heap GC profiling, the wide-radius single-plugin run): [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video of `/rtp` on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

---

## How it works

### Spatial memory

On the worlds I've measured, about 35-65% of the area is unsafe to land on (ocean, lava, void). Selections come off an Archimedean spiral, an indexed mapping from 1D to 2D. The index lets the plugin store what it learned about each segment (biome, why it was rejected) and skip it next time. Picking a location is a constant-time lookup with an occasional table rebuild, and `/rtp scan` pregenerates spatial memory for a region off-tick when started. What it learns is saved to disk and survives restarts. How long each kind of rejection is kept is set per cause in `advanced/ttl.yml`. The math, with distribution plots: [Why LeafRTP exists](https://dailystruggle.github.io/RTP/site/why/).

### Region-file pre-filter

An Anvil (`.mca`) pre-filter reads biome and block data straight from the region files on disk. Unloaded candidates are rejected without loading a chunk, off the tick thread on every platform. The files hold what the world actually contains. `getBiome` and `getHighestBlockAt` answer from the generator's noise map, which disagrees with the real terrain once a spot has been edited, pregenerated elsewhere, or carried across a Minecraft version. Custom generators (Iris, Terra, datapacks) are read the same way, with namespaced IDs kept. When the pre-filter can't answer (no region file, a region format it doesn't read, unknown data version, chunk already loaded) it falls back to the platform's own chunk API within the configured tick budget; the cost per platform is below.

### Pre-verified cache

Safe destinations are found ahead of time and a number of them are kept ready per region. Answering `/rtp` means handing back a coordinate that's already been checked. A background sweep takes back chunk tickets from abandoned teleports, and nothing stays force-loaded past its reservation window.

### Spacing between players

Candidates come from a keyed shuffle of every chunk that hasn't been rejected, so a spot doesn't come up again until the shuffle has cycled. The shuffle is re-keyed when the usable area changes. Spots drawn back to back sit one bin apart (512 blocks on the default 16,384-block circle). The exception is the corners of the spiral, where bins turn: there about 5% of spots on the smallest regions come closer, and fewer on larger ones. On the default circle the closest pair is 362 blocks. Rejected ground and the ready cache loosen this a little, by an amount that depends on the region's `mode`. The [Regions](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/#spacing-repeats-and-worst-case-by-mode) page has the numbers, when a spot can repeat, and each mode's worst case. There's no list of past destinations and no check of where players are. How the order is built is in *Selection model, in pictures* below.

A player who knows the world seed and every config value still can't compute the next landing spot. Each region's shuffle key is 64 bits of `SecureRandom` drawn when the region loads, never written to disk, and re-derived when the usable area grows. The shuffle is my own construction and nobody has cryptanalysed it; I only claim it's hard to guess.

### No shaded dependencies

I don't shade libraries. The SQL connection pool, the Redis client, cross-server messaging, bStats and the region-file reader are written in-house on plain Java. The web workspace connects outward to an ephemeral relay (like spark and LuckPerms) rather than embedding an HTTP server daemon, so there are no open listening ports and the jar stays lean despite bundling full cartography, packed docs, and offline tooling. The jar doesn't fight other plugins over HikariCP, Jedis or Commons Pool versions, and there's no native code to crash. The trade-off is that I maintain all of that code myself.

### Cartography, image exports, and visual diagnostics

LeafRTP includes a standalone mapping engine (`maps-api`) that generates live in-game maps and high-resolution diagnostic images without main-thread chunk I/O.

- **High-resolution image exports** (`/rtp visualization export <type>`): Exports standalone PNG and BMP image files directly to `plugins/RTP/charts/` with customizable dimensions and zoom factors (`width=1920 height=1080 zoom=2.0`). Supported export layers include regional biome distributions, bad-location hazard masks (water, lava, claims, void), candidate selection heatmaps, Archimedean spiral walk paths, and pipeline composite views.
- **Comprehensive diagnostic charts** (`/rtp visualization export comprehensive`): Renders an all-in-one diagnostic panel combining a desaturated biome field, a translucent hazard overlay, the Archimedean spiral chunk progression path, markers for all pre-warmed queue candidates (L1 Hot, L2 Cold, L3 Backlog), Clark-Evans spatial dispersion statistics, nearest-neighbor distance distributions, and paired JSON telemetry metadata.
- **Held in-game maps** (`/rtp visualization`): Paints real-time biome layouts, rejected terrain, candidate selection heatmaps, and MSPT performance sparklines directly onto held Minecraft map items. Maps redraw dynamically while held in your hand.
- **External web map integration**: Provides non-blocking raster tile providers and vector region boundary overlays for Dynmap, BlueMap, and Pl3xMap via `maps-api` and `anvil-api`, reading directly from disk without loading chunks through the server engine.

### Chunk loading, by platform

- **Paper** and forks (Purpur, Pufferfish, Leaf, Leaves, DivineMC, ...): `World#getChunkAtAsync`; if the async load fails or returns no chunk, it falls back to a main-thread load and logs a warning.
- **Folia**: the pre-filter runs before the Region Scheduler, and rejected candidates never hop a thread. Confirmed candidates load through Folia's async API and teleport through the Entity Scheduler.
- **Spigot** (and Arclight / Mohist): `.mca` pre-filter off-tick. Throughput is still capped by Spigot's chunk generator, and a candidate the pre-filter can't answer costs one on-tick chunk load.
- **Fabric** and **NeoForge**: in-tree adapters running the same core. I test them less than the Bukkit family.

<details>
<summary><b>Selection model, in pictures</b></summary>

These charts are rendered by the visualizer tests in `rtp-core` and regenerated from the current shape and cache code on every run. More of them (native-vs-unique placement, the older spiral-vs-polar and circle-boundary plots) are on the [docs site](https://dailystruggle.github.io/RTP/site/why/).

**Spiral-Hilbert walk across radii.** The selector walks a coarse Archimedean spiral of macro-cells and fills each cell with a Hilbert curve whose edge grows with the region radius: a plain 1-chunk spiral at R=32, 2x2 cells at R=64-126, 8x8 at R=256, 16x16 at R=512. Square (Chebyshev) and Circle (Euclidean) shapes share the same path; only the boundary test differs.

![Spiral-Hilbert path progression across radii](../assets/img/path_progression_radii_chart.png)

**One region file, chunk by chunk.** Zoom into a single 32x32 Anvil region (1,024 chunks) at cell edges 32, 8 and 4. Each tile picks a rotation or reflection that puts its Hilbert exit next to the next tile's entry. Every panel reports 0 jumps and unit steps only: consecutive keys stay in the same region file and the mapping stays a bijection.

![32x32 region file zoom](../assets/img/region_32x32_zoom_path_chart.png)

**Cost of a candidate draw.** Picking the next candidate is fixed arithmetic; the only loop is the shuffle's cycle-walk, which averages under 4 passes. A draw measures 1.56 us on one thread and allocates nothing. The stride (1, 4, 16, 64, 256 or 1,024) is chosen from the region size and capped at the bin area. Within a stride group the order is a keyed pseudorandom permutation, cycle-walked to the exact domain size. That's what makes the sequence non-repeating without keeping a set of visited keys. When the stride equals the bin area, a stride group holds one chunk per bin, so its members are a bin edge apart except at the spiral's corners. A stride smaller than the bin doesn't keep a floor, because the Hilbert quadrants inside a bin are mirrored. Groups are visited in shuffled bit-reversal order, and a small randomized batch window keeps the pattern from forming a predictable lattice.

**Stride and minimum player distance.** The same 1,000-teleport run at S=1, S=64 and S=256. Without a stride the closest pair of players landed 1 chunk apart and 3.7% of all pairs were under 8 chunks; at S=64 the closest pair is 8 chunks and at S=256 it's 16, with no pair under that floor. The average nearest neighbor barely moves (40 to 48 chunks) because the stride only removes the close pairs and leaves the rest where they were. The bottom table is the memory cost of tracking used chunks under each strategy. A stride replaces the per-player exclusion stamp with a permutation: the used-key set at S=256 is 2 KB as a bitmask against 3.6 MB as flat arrays.

![Dyadic stride vs minimum player spacing](../assets/img/side_by_side_downsampling_comparison_chart.png)

**The backlog cache, filled.** A 1,024-chunk-radius region (32,768 blocks across) split into its 4,096 region files, with 10,000 pre-screened candidates in the backlog. Each bin gives up 2 to 4 candidates at least 320 blocks apart, which keeps any one region file from dominating the queue. The 505 bins that are all ocean were discarded from the map alone and never cost a file read. Filling the 10,000 took under 35 ms off-tick, and the whole region's state persists in under 500 KB. This is where the "chunks loaded" column comes from: candidates are screened here before a chunk is ever loaded for a player.

![Backlog cache state across the region](../assets/img/full_l3_state_chart.png)

</details>

<details>
<summary><b>Testing and quality gates</b></summary>

Coverage floors fail the build when they're missed, and a floor can't be lowered without a commit. Gates that don't enforce yet say so in their row. Coverage percentages are from the `-Pcoverage` run of 2026-09-15. The badge at the top is SonarCloud's whole-tree number, which includes the platform adapters and addons these per-module gates leave out; it reads lower than the table.

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
| Binary API compatibility | japicmp check against a released baseline is wired up but not enforcing yet: the configured baseline isn't a published version and the check skips. Removals follow a 2-minor deprecation policy | `gradle.yml` (japicmp) |
| Dependency CVEs | OWASP dependency-check fails at CVSS >= 4 on the scanned files; scanning the resolved Gradle dependency graph and shaded jar isn't in place yet | `dependency-check.yml`, weekly and on dependency changes |
| Release provenance | SLSA build provenance on every release; GitHub releases also carry a CycloneDX SBOM and SHA-256 / SHA-512 checksums | `release.yml`, `release-bbb.yml` |
| Runtime acceptance | nightly multi-server devstack: 2 Velocity proxies, 2 lobbies, 2 backends over Redis, on Velocity + Paper + Folia + Fabric (MC 1.21.11, Java 25) and NeoForge (MC 1.21.1, Java 21) | `devstack-acceptance.yml` |
| Encoding and docs | tracked text files scanned for UTF-8 mojibake; the docs site builds from the same tree | `gradle.yml`, `docs.yml` |

**What I haven't finished.** `rtp-core` and `rtp-proxy-common` are still under the 90 / 80 branch target; their floors sit a point or two under the measured numbers and only ever go up. What's left in `rtp-core` is mostly platform bootstrap and dispatch code, which I'm covering from the nightly devstack instead of with mock-only tests. Spigot, NeoForge, BungeeCord and non-LTS Java have no scheduled live CI suite and are listed as best-effort.

</details>

---

## Why I made it

I wanted to explore Minecraft worlds, but on server after server `/rtp` was disabled or met with 'no, that's laggy'. Later, when running and profiling servers myself, it took days of work to confirm that random teleport plugins were the trigger: the biggest cost in any profiler is never labeled by who called the API. The thousands of extra chunks in memory get attributed to the internal chunk system, so operators blame player exploration. I went through that 'blame the users' phase myself before realizing it is better to fix the tool than to tell people not to explore.

In 2021 this started as a demonstration of mathematical principles and a high-difficulty optimization puzzle. It was received as a product instead, and a lot of features were requested, so I worked on design elegance: a few design details that produce a very large number of possible configurations. "Chunks" are the unit of measurement because the cost to the server is chunk-based rather than block-based, and checking adjacent blocks is free so long as it does not leave a chunk boundary. For V2 I refactored to swappable suppliers and consumers so safety checks, biome checks, and shapes can be replaced programmatically. Frankly "clean code" is a regret, as it increased input latency, but the structure was a good launch point for reorganization. V3 was the update for modern game versions and platforms: cache locality, data access, cross-platform support via SPI, and active tracking to catch the "memory leak" I kept hearing about but could never reproduce on my rig.

After V3 I built a test bench that drives up to one `/rtp` per gametick. Every pure reroll implementation I tested (pick a coordinate, load the chunk, check it, try again with no bound on the retries) fell off under that load except this one. The bench also showed that the common shortcut of "use loaded chunks" tends to place players in each other's bases, which turns into griefing or rerolling depending on the claim integration. That finding is why the pre-verified cache exists.

Design decisions, alternatives considered, and what superseded what are recorded [here](https://dailystruggle.github.io/RTP/adr/).

<!-- only: bbb-pro -->
<!-- kind: promo -->
---

## What paying gets you

Same code, config and commands as the free download, under the same MIT licence. Nothing in the plugin is locked; most of the development has been on my own dime, so paying shows support and buys dedicated support in response:

| This listing | Free download |
|---|---|
| Priority on my support queue | Community support, best effort |
| New Minecraft versions and platforms first | Stable builds, once they've settled here |
| Shows support and buys dedicated support in response | - |

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
| `/rtp trigger create\|remove\|list` | Cuboid triggers that fire an action on entry | `rtp.trigger` |
| `/rtp centerx=<x> centerz=<z> radius=<r>` | One-off overrides for this call; units allowed (`radius=10km`) | `rtp.params` |
| `/rtp menu` | Player book menu | `rtp.use` |
| `/rtp admin` | Operator book menu | `rtp.menu.admin` |
| `/rtp editor [local\|trust\|apply]` | Web editor link, offline copy, browser trust, token apply | `rtp.editor` |
| `/rtp info` | Operator diagnostics | `rtp.info` |
| `/rtp reload [file]` | Reload all configuration, or one file | `rtp.reload` |
| `/rtp config <file> view\|set <k>=<v>` | View or set a config key, then reload | `rtp.config` |
| `/rtp config import [source] [path=<dir>] [overwrite=true]` | Translate another rtp plugin's config; never overwrites without `overwrite=true` | `rtp.config` |
| `/rtp config import permissions [source] [apply=true]` | Dry-run the LuckPerms commands that copy BetterRTP, JustRTP or EzRTP nodes onto `rtp.*`; `apply=true` runs them | `rtp.config` |
| `/rtp admin setup <world\|gameplay\|perf\|preview\|confirm>` | Guided first-time setup from prefabs | `rtp.admin.setup` |
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

**Configuration files** under `plugins/RTP/`. Every file reloads at runtime and can be edited in-game. The everyday files sit at the top level; things you write yourself live in `definitions/`, and rarely-touched tuning in `advanced/`.

| File | Scope | Example keys |
| --- | --- | --- |
| `config.yml` | Teleport behavior, menu renderer | `teleportDelay`, `teleportCooldown`, `cancelDistance`, `menu.renderer` |
| `safety.yml` | Block / biome safety filter | `unsafeBlocks`, `airBlocks` |
| `economy.yml` | Vault pricing | `price`, `priceOther`, `biomePrice`, `balanceFloor`, `refundOnCancel` |
| `language.yml` | Locale selection | `language` |
| `definitions/regions/<name>.yml` | Per-region shape, radius, center, queue, price, gates | `shape`, `vert`, `radius`, `cacheCap`, `price`, `requirePermission` |
| `definitions/actions/<name>.yml` | Scripted actions and arenas | `minSeparation`, `elevationTolerance`, `confinement.initialSize`, `confinement.damage`, `lifecycle` |
| `definitions/worlds/<name>.yml` | Per-world region routing and overrides | `region`, `override`, `requirePermission` |
| `definitions/effects/<name>.yml` | Lifecycle effects | per-phase effect definitions |
| `advanced/database.yml` | SQL / Redis persistence | `database.type`, connection settings |
| `advanced/network.yml` | Proxy / multi-server | `network.enabled`, `transport`, `routing`, `reservation` |
| `advanced/performance.yml` | Tick budgets, queue caps, caches | `maxAttempts`, `syncAllottedTime`, `minTPS`, `loginCacheEnabled` |
| `advanced/biomes.yml`, `advanced/blocks.yml`, `advanced/ttl.yml` | Biome whitelist and weighting, block-tag catalog, spatial-memory expiry | toggles and durations |
| `advanced/logging.yml`, `advanced/metrics.yml` | Log verbosity, runtime metrics | toggles |
| `advanced/messages/*.yml`, `lang/**` | User-facing text (`commands`, `player`, `network`, `placeholders`, `system`) and bundled translations | message templates |
| `addons/integrations.yml` | Claim-plugin reroll toggles | `rerollWorldGuard`, `rerollGriefPrevention`, ... |

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
A: No. Sizing and configuring your regions takes a couple of minutes through the guided setup wizard (`/rtp admin setup`), the web canvas (`/rtp editor`), or in-game book (`/rtp admin`). If you are migrating from another plugin, `/rtp config import` translates your existing files automatically.

**Q: How is the web editor secured? Does it open ports on my server?**
A: No. The web editor opens zero listening ports on your server. It communicates outbound-only via TLS to an ephemeral WebSocket relay, or over a token-gated loopback socket (`127.0.0.1`) for local edits. All messages require RSA-2048 signatures with strictly increasing sequence counters and single-use, expiring trust nonces.

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
A: MSPT, no. Spatial memory stays compact (about 26 bytes per sector segment). Locations kept in the hot queue hold chunk tickets so teleports are instant, and `MemoryTracker` makes sure those tickets are released. Under heap pressure (`maxHeapPercent`, or under 512 MB free) background filling pauses and the held tickets are dropped back to cold storage, which frees the pinned chunks right away.

**Q: Can I use ItemsAdder, Oraxen, Nexo, or CustomModelData in the GUI menu?**
A: Yes. Icon strings in `guimenu.yml` accept prefix routing for `ia:<id>`, `oraxen:<id>`, `nexo:<id>`, `hdb:<id>`, `head:<player>`, `base64:<hash>`, and `<MATERIAL>:<cmd>`. Because commercial plugins carry closed-source licenses and commercial price tags, automated CI cannot run proprietary binaries; testing relies on mock contract stubs and open-source equivalents, so third-party proprietary compatibility is maintained on a best-effort basis. If an item plugin is absent or changes unexpectedly, LeafRTP degrades gracefully to vanilla fallback materials without crashing. See [Custom Item & Menu Integration](https://dailystruggle.github.io/RTP/admin/CUSTOM_ITEMS/).

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
