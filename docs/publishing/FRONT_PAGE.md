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

Layout: what it is and the short version (LeafRTP's own numbers only) first, then
what people build with it, then features grouped by who uses them. Head-to-head
numbers against other plugins live in one place only: the Performance section.

Listing metadata (for the marketplace form fields, not emitted):
  Paid BuiltByBit title:  "LeafRTP-Pro"   tagline: "Off-tick Random Teleportation engine"
  Free title:             "LeafRTP"       tagline: "Off-tick random teleport engine with spatial memory"
  (Modrinth's short description still reads "Fast Random Teleportation for everyone,
   configurable and extensible" - update it there to match.)
-->

<div align="center">

# {{name}} - Random Teleport

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
There's also a paid LeafRTP-Pro listing on this site. It's the same code; paying for it gets you priority support and early builds, and nothing in the plugin is locked.
<!-- /kind -->
<!-- /only -->
<!-- only: modrinth -->
One jar for Paper, Folia and Spigot (1.20+), Fabric (needs Fabric API) and NeoForge (1.21.1+), up to 26.x, and it also loads as a Velocity plugin for cross-server `/rtp`. Java 21+.
<!-- /only -->
<!-- only: hangar -->
On Paper all candidate checking runs off the tick thread. On Folia, candidates are screened against the region files first, nothing waits on a synchronous chunk load, and only the final block check of a loaded chunk runs on its region thread. The same jar also loads on Velocity for cross-server `/rtp`.
<!-- /only -->

### The short version

- **0 failed teleports** in every benchmark run on this page: 16,560 of 16,560 on Paper with no pacing, and 4,096 of 4,096 on both Paper and Folia.
- **7.9 ms of region-thread time per teleport on Folia, and 0 watchdog stalls.** The search runs off the tick thread and checks candidates against the region files on disk first; most are rejected there without a chunk load.
- **1 ms median, 2 ms p95** on Paper under unpaced load. Most teleports come out of a queue of spots that were checked before anyone typed `/rtp`.
- **0 duplicate landings out of 4,096.** The closest two players were 78 blocks apart, and nothing checks where anyone is.
- **Rejected ground is remembered.** Ocean, lava and void it has already turned down is saved across restarts and skipped for as long as `advanced/ttl.yml` keeps that kind of rejection.
- **One jar** for Paper, Folia, Spigot, Fabric and NeoForge, up to 26.x, plus Velocity for cross-server `/rtp`.
- **One command to move over.** `/rtp config import` reads the config BetterRTP, JustRTP, EzRTP, JakesRTP, AsyncRTP or AdvancedRTP left behind and writes mine next to it.

How the other plugins did in the same runs is in [Performance](#performance).

## Common setups

Most people just want `/rtp` to work without lagging the server. Past that, these work without extra plugins:

- **Spawn portals:** a cuboid trigger (`/rtp trigger`) over a portal frame or launch pad. Effects add a countdown hologram and sounds.
- **Group and party drops:** a party or duel queue sent to random spots at least `minSeparation` blocks apart (32 in the bundled `scatter` and `arena` actions).
- **Sky drops:** a `Fixed` vertical adjustor at Y=250 or higher, plus a slow-falling potion effect.
- **Spawning near a town or faction:** an action with `anchor: claimboundary` (the bundled `nearclaim` action) in place of a fixed map center.
- **1v1 arenas:** two queued players in a temporary bounded area with a countdown, cleaned up when they're done.

---

## Features

### For players

- **`/rtp` and `/wild`** - to the world's default region, or pick a region, world or biome (`/rtp region:<name>`, `/rtp biome:<biome>`). `/rtp back` returns them to where they were.
- **Menus** - `/rtp menu` is a player book on Paper, Folia, Fabric and NeoForge (chat pages on Spigot) for teleporting or picking a region, world or biome. The bundled GUI addon adds a chest picker.
- **Per-player queues** (`rtp.personalqueue`) next to the shared queue. One player's bad luck doesn't hold up anyone else's teleport.
- **Effects** - particles, sounds, fireworks, potions, titles, console or player commands, and holograms on every teleport phase, gated by `rtp.effect.<stage>.*` permissions. Holograms use the 1.19.4+ text display entity directly; DecentHolograms and HolographicDisplays are used if you already run them.
- **Movement-cancel, damage-cancel, invulnerability after teleport, landing platform with decay**, countdown and warmup messages.

![GUI addon: destination picker](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/addongui.png)

### Setting it up

- **Setup wizard** - `/rtp admin setup` walks you through prefabs from console or chat: pick the world and map its border, set gameplay defaults (cooldown, delay, price, safety toggles), tune cache limits and I/O threads, `preview` the result, then `confirm`. Nothing is written until you confirm, and it backs up the files it touches first.
- **Admin book** - `/rtp admin` is the operator side: config editor with search, setup prefabs, region and MSPT/heap visualizations, scan control, diagnostics, and the bundled docs (`/rtp docs`). Gated on `rtp.menu.admin`; holders also see it as an extra row in `/rtp menu`.
- **Web editor** - `/rtp editor` gives you a link to a temporary web page where you draw polygon or donut regions over the world's terrain, overlay the selector's walk path and review the YAML diff. Trust the browser once with the code the page shows (`/rtp editor trust nonce=<code>`) and it applies changes directly over a signed channel; `/rtp editor apply token=<token>` works without trusting it. The server only connects outward, to a paste store and a WebSocket relay set in `advanced/network.yml`. `/rtp editor local` writes an offline single-file copy that talks to the server over a token-gated socket on 127.0.0.1.
- **Import from another rtp plugin** - `/rtp config import` translates worlds, radius and center, shape, cooldowns, prices, database connection blocks and arrival effects, then reloads. It only replaces an existing file when you pass `overwrite=true`, and backs the old one up first. Written against sample configs from BetterRTP, JustRTP, EzRTP, JakesRTP, AsyncRTP and AdvancedRTP; anything else goes through a generic keyword matcher. `/rtp config import permissions` lists the LuckPerms commands that would copy BetterRTP, JustRTP or EzRTP permission nodes onto mine, and `apply=true` runs them.
- **Units in config** - `radius: 10km`, `radius=625c`, `teleportCooldown: 2h30m`, `cacheCap: 500mb`. Distances take blocks (`b`), chunks (`c`), region files (`r`), km and miles; times take ticks (`t`), ms, s, m, h, d, w; sizes take kb / mb / gb. Plain numbers still mean what they always did.
- **Hot reload** - `/rtp reload [file]`, or `/rtp config <file> set k=v`, which saves and reloads.
- **Diagnostics** - `/rtp info`: queue depth and growth, pipeline latency percentiles, chunk-ticket leak rate, TPS/MSPT, database latency, per-region Folia table, generation success rate and top rejection cause. No metrics add-on needed.
- **Live maps** - `/rtp visualization` draws a region onto a held map: biomes, bad locations, selection heatmap, the pipeline view, or an MSPT/heap graph. Biome, bad-location and MSPT/heap maps redraw while you hold them, so a bad-location map held during `/rtp scan` fills in as the scan rejects ground. [Video](https://youtu.be/Ftjy1zw_S04).
- **Docs in the jar** - the admin guide for your exact version unpacks into `plugins/RTP/docs/` on first run, in case the website has moved on to a newer version.

![Web editor: a donut region drawn over the world's real terrain, with shape settings, staged diff and detected biomes](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/web_editor.png)

<div align="center">

![Admin book](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/menu_1.png) ![Admin book: config files](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/menu_2.png)

*`/rtp admin`: the operator book and its config file list.*

</div>

### Gameplay and worlds

- **Regions** - any number per world: shape (Square, Circle, Rectangle, Polygon), radius, center, curve weighting, vertical bounds, world override, permission gate, price. Vertical adjustors (Linear, Jump, Fixed) for sky islands, void worlds and Nether ceilings. A world's `override` key (`definitions/worlds/<name>.yml`) sends a Nether or End `/rtp` to a safe world.
- **Scripted actions and arenas** - multi-player placement and confinement written in YAML (`definitions/actions/<name>.yml`): minimum player spacing, elevation tolerance, moving or static borders, leash radius, damage on breach, and lifecycle triggers (`onStart`, `onBoundaryViolation`, `onExpire`, `onDeath`). Vanilla scoreboards (`rtp_violations`, `rtp_time_left`, `rtp_in_bounds`) are kept updated for command blocks and datapacks. Cuboid triggers (`/rtp trigger`) fire an action when a player walks in.
- **Arrival schematics** - drop a Sponge `.schem` named after a region into `plugins/RTP/advanced/schematics/` and every teleport into that region pastes it centered on the landing spot. Decoded in-house, no WorldEdit needed, and claim-aware.
- **Auto-RTP on events** - join, first join, respawn, world change, move, teleport (`rtp.onevent.*`). A login reserve cache keeps destinations ready so join-time teleports don't wait.
- **Economy** - charge per `/rtp` through Vault, per-region pricing, refund on cancel, `rtp.free` bypass.
- **PvP / combat-tag gate** - off by default; refuses or delays `/rtp` for players who recently dealt or took PvP damage. Built-in tracking, or PvPManager / CombatLogX / Simple Combat Log if you run one.
- **Command blocks and console** - the same parser handles player, console and command-block callers.

### Claims and safety

- **Claim plugins** - 16 on Bukkit / Paper / Folia through the bundled claim addon (GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, HuskTowns, PlotSquared, RedProtect, CrashClaim, KingdomsX, Residence, UltimateClaims, MinePlots), plus FTB Chunks and Open Parties and Claims on Fabric / NeoForge. Claim checks run in the async pipeline, off the teleport tick. You can add your own through `RegionVerifierRegistry` with one lambda.
- **Claim- and faction-anchored destinations** - an action can land players relative to their own town, claim or faction land (`anchor: claimboundary`, `anchor: faction`). Towny, GriefPrevention and SaberFactions / FactionsUUID boundaries work out of the box; others plug in through `ClaimBoundaryProvider`. The anchor stays put while it's inside the claim and is recomputed at most once per cooldown, which keeps the spiral from drifting every time land changes hands.
- **Safety filter** - `safety.yml` takes plain materials, block-state predicates (`OAK_SLAB[waterlogged=true]`), numeric ranges (`WATER[level>=5]`), vanilla and datapack block tags (`#minecraft:leaves`) and wildcards (`*[waterlogged=true]`). Grammar is in the details below.

### Networks

- **Cross-server `/rtp`** - Velocity with plugin messaging, or SQL / Redis for reservation tokens. Tested nightly on the in-repo devstack: 2 Velocity proxies, 2 lobbies, 2 backends behind Redis.

### For developers

- **API and addons** - `rtp-api` (pre / mid / post teleport hooks, region verifiers, config, scheduling) and `effects-api`. Addons compile against `rtp-api` only and load through a `ServiceLoader` SPI; one addon jar runs on Spigot, Paper, Folia, Fabric and NeoForge. The bundled addons (GUI picker, claim integrations, Countdown reference, scripted actions) unpack into `plugins/RTP/addons/` on first run; delete one from that folder to turn it off. Their source, and the Rift warmup-effect addon's, is under `addons/` as a starting point.
- **Swappable parts** - server backend, economy, location validity, shapes, world border, PvP checks, region file format and commands can all be replaced through SPI.
- **PlaceholderAPI** - queue depth (total / public / personal), last-teleport coordinates, player status.
- **bStats** - on by default; anonymous usage counts help me decide which platforms to spend time on.

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

The region-file check needs terrain that already exists; a chunk that has never been generated has to be generated whichever plugin asks for it.

![Cross-plugin destination scatter comparison](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/cross_plugin_destinations_scatter_chart.png)

*Where 4,096 players landed in the unpaced Paper 26.1 run (`20260923-000854`). The scatter numbers are at the end of the tables below.*

<details>
<summary><b>Full benchmark tables (Paper and Folia)</b></summary>

Only runs against current versions of the other plugins are listed; the older Spigot / Paper 1.20.1 runs were dropped once every plugin in them had shipped a newer release. Each run uses its own world and settings: compare plugins within a run.

**Metrics:** TP/s (higher is better) | latency percentiles (dispatch to teleport event, lower is better) | Min TPS (20.00 = no hiccup) | MSPT p99 / max (main-thread tick time in ms, lower is better) | Success (share of dispatched commands that produced a teleport).

A "success" is the harness seeing that player's `PlayerTeleportEvent` within 5 s of dispatching the command. It doesn't check where the player landed: a teleport onto a bad block counts as a success, and a plugin that gave up cleanly with a chat message counts as a failure. The two ways to fail are the 5 s timeout and a teleport event with no destination.

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

**Caveats.** 3 clients with up to 4 teleports in flight is a small load; more clients may push the throughput figures higher. The Folia watchdog count reproduced across two runs; everything else is n=1. Hardware, view distance, world state (how much of it is unsafe, whether it's pregenerated) and other plugins will move the numbers. The other plugins update often; if a number here is out of date, open a GitHub issue with a repro or a doc link and I'll correct it.

**What this doesn't claim.** It doesn't claim the other plugins are bad: they ran at their defaults, which is what most people actually run. It doesn't measure correctness, safety or claim-plugin compatibility, only dispatch-to-arrival latency, per-attempt cost and success rate as defined above.

Full methodology, per-run analyses, and the runs not shown here (equalized-radius Folia, pinned-heap GC profiling, the wide-radius single-plugin run): [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video of `/rtp` on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

---

## How it works

### Spatial memory

On the worlds I've measured, about 35-65% of the area is unsafe to land on (ocean, lava, void). Selections come off an Archimedean spiral, an indexed mapping from 1D to 2D. The index lets the plugin store what it learned about each segment (biome, why it was rejected) and skip it next time. Picking a location is a constant-time lookup with an occasional table rebuild, and `/rtp scan` maps a region in the background once you start it. What it learns is saved to disk and survives restarts. How long each kind of rejection is kept is set per cause in `advanced/ttl.yml`. The math, with distribution plots: [Why LeafRTP exists](https://dailystruggle.github.io/RTP/site/why/).

### Region-file pre-filter

An Anvil (`.mca`) or Linear (`.linear`) pre-filter reads biome and block data straight from the region files on disk. Unloaded candidates are rejected without loading a chunk, off the tick thread on every platform. The files hold what the world actually contains. `getBiome` and `getHighestBlockAt` answer from the generator's noise map, which disagrees with the real terrain once a spot has been edited, pregenerated elsewhere, or carried across a Minecraft version. Custom generators (Iris, Terra, datapacks) are read the same way, with namespaced IDs kept. When the pre-filter can't answer (no region file, unknown data version, chunk already loaded) it falls back to the platform's own chunk API within the configured tick budget; the cost per platform is below.

### Pre-verified cache

Safe destinations are found ahead of time and a number of them are kept ready per region. Answering `/rtp` means handing back a coordinate that's already been checked. A background sweep takes back chunk tickets from abandoned teleports, and nothing stays force-loaded past its reservation window.

### Spacing between players

Candidates come from a keyed shuffle of every chunk that hasn't been rejected, so a spot doesn't come up again until the shuffle has cycled. The shuffle is re-keyed when the usable area changes. On regions past about 720 blocks of radius, consecutive spots are at least 256 blocks apart; smaller regions get a smaller gap. There's no list of past destinations and no check of where players are. How the order is built is in *Selection model, in pictures* below.

A player who knows the world seed and every config value still can't compute the next landing spot. Each region's shuffle key is 64 bits of `SecureRandom` drawn when the region loads, never written to disk, and re-derived when the usable area grows. The shuffle is my own construction and nobody has cryptanalysed it; I only claim it's hard to guess.

### Few dependencies

I avoid shading libraries. The SQL connection pool, the Redis client, cross-server messaging, bStats and the region-file readers are written in-house on plain Java. The one exception is the `.linear` ZStandard decoder: airlift's aircompressor, pure Java, relocated into LeafRTP's own package. The jar doesn't fight other plugins over HikariCP, Jedis or Commons Pool versions, and there's no native code to crash. The trade-off is that I maintain all of that code myself.

### Chunk loading, by platform

- **Paper** and forks (Purpur, Pufferfish, Leaf, Leaves, DivineMC, ...) - `World#getChunkAtAsync`; if the async load fails or returns no chunk, it falls back to a main-thread load and logs a warning. Linear-format servers get the `.linear` pre-filter.
- **Folia** - the pre-filter runs before the Region Scheduler, and rejected candidates never hop a thread. Confirmed candidates load through Folia's async API and teleport through the Entity Scheduler.
- **Spigot** (and Arclight / Mohist) - `.mca` pre-filter off-tick. Throughput is still capped by Spigot's chunk generator, and a candidate the pre-filter can't answer costs one on-tick chunk load.
- **Fabric** and **NeoForge** - in-tree adapters running the same core. I test them less than the Bukkit family.

<details>
<summary><b>Selection model, in pictures</b></summary>

These charts are rendered by the visualizer tests in `rtp-core` and regenerated from the current shape and cache code on every run. More of them (native-vs-unique placement, the older spiral-vs-polar and circle-boundary plots) are on the [docs site](https://dailystruggle.github.io/RTP/site/why/).

**Spiral-Hilbert walk across radii.** The selector walks a coarse Archimedean spiral of macro-cells and fills each cell with a Hilbert curve whose edge grows with the region radius: a plain 1-chunk spiral at R=32, 2x2 cells at R=64-126, 8x8 at R=256, 16x16 at R=512. Square (Chebyshev) and Circle (Euclidean) shapes share the same path; only the boundary test differs.

![Spiral-Hilbert path progression across radii](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/path_progression_radii_chart.png)

**One region file, chunk by chunk.** Zoom into a single 32x32 Anvil region (1,024 chunks) at cell edges 32, 8 and 4. Each tile picks a rotation or reflection that puts its Hilbert exit next to the next tile's entry. Every panel reports 0 jumps and unit steps only: consecutive keys stay in the same region file and the mapping stays a bijection.

![32x32 region file zoom](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/region_32x32_zoom_path_chart.png)

**Cost of a candidate draw.** Picking the next candidate is fixed arithmetic; the only loop is the shuffle's cycle-walk, which averages under 4 passes. A draw measures 1.56 us on one thread and allocates nothing. The stride (1, 4, 16, 64 or 256) is chosen from the region size and capped at half the bin area. Within a stride group the order is a keyed pseudorandom permutation: a balanced Feistel network whose 4 rounds each mix with a SipHash-style add-rotate-xor function, cycle-walked to the exact domain size. That's what makes the sequence non-repeating without keeping a set of visited keys. Consecutive players are at least sqrt(S) chunks apart (16 chunks at S=256) because the stride phases are visited in shuffled bit-reversal order. A small randomized batch window keeps the pattern from forming a predictable lattice.

**Stride and minimum player distance.** The same 1,000-teleport run at S=1, S=64 and S=256. Without a stride the closest pair of players landed 1 chunk apart and 3.7% of all pairs were under 8 chunks; at S=64 the closest pair is 8 chunks and at S=256 it's 16, with no pair under that floor. The average nearest neighbor barely moves (40 to 48 chunks) because the stride only removes the close pairs and leaves the rest where they were. The bottom table is the memory cost of tracking used chunks under each strategy. A stride replaces the per-player exclusion stamp with a permutation: the used-key set at S=256 is 2 KB as a bitmask against 3.6 MB as flat arrays.

![Dyadic stride vs minimum player spacing](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/side_by_side_downsampling_comparison_chart.png)

**The backlog cache, filled.** A 1,024-chunk-radius region (32,768 blocks across) split into its 4,096 region files, with 10,000 pre-screened candidates in the backlog. Each bin gives up 2 to 4 candidates at least 320 blocks apart, which keeps any one region file from dominating the queue. The 505 bins that are all ocean were discarded from the map alone and never cost a file read. Filling the 10,000 took under 35 ms off-tick, and the whole region's state persists in under 500 KB. This is where the "chunks loaded" column comes from: candidates are screened here before a chunk is ever loaded for a player.

![Backlog cache state across the region](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/full_l3_state_chart.png)

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
| `rtp-core` | 81.6% / 65.8% | 0.80 / 0.64 | not met - branch coverage still climbing |
| `rtp-proxy-common` | 87.0% / 68.8% | 0.85 / 0.67 | not met - branch coverage still climbing |

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

I wanted to explore Minecraft worlds. I asked for `/rtp` on servers I played on and was told "no, that's laggy", and I took that personally. It took me days of profiling to confirm that a random teleport plugin was the trigger, because the biggest cost in any profiler is never labeled by who called the api. The thousands of extra chunks in memory get attributed to the chunk system, so the operator blames the players. I went through the "blame the users" phase myself before realizing it is better to fix the tool than to tell people not to use it.

In 2021 this started as a demonstration of mathematical principles and a high-difficulty optimization puzzle. It was received as a product instead, and a lot of features were requested, so I worked on design elegance: a few design details that produce a very large number of possible configurations. "Chunks" are the unit of measurement because the cost to the server is chunk-based rather than block-based, and checking adjacent blocks is free so long as it does not leave a chunk boundary. For V2 I refactored to swappable suppliers and consumers so safety checks, biome checks, and shapes can be replaced programmatically. Frankly "clean code" is a regret, as it increased input latency, but the structure was a good launch point for reorganization. V3 was the update for modern game versions and platforms: cache locality, data access, cross-platform support via SPI, and active tracking to catch the "memory leak" I kept hearing about but could never reproduce on my rig.

After V3 I built a test bench that drives up to one `/rtp` per gametick. Every pure reroll implementation I tested (pick a coordinate, load the chunk, check it, try again with no bound on the retries) fell off under that load except this one. The bench also showed that the common shortcut of "use loaded chunks" tends to place players in each other's bases, which turns into griefing or rerolling depending on the claim integration. That finding is why the pre-verified cache exists.

Design decisions, alternatives considered, and what superseded what are recorded [here](https://dailystruggle.github.io/RTP/adr/).

<!-- only: bbb-pro -->
<!-- kind: promo -->
---

## What paying gets you

Same code, config and commands as the free download, under the same MIT licence. Nothing in the plugin is locked; what you're paying for is my time:

| This listing | Free download |
|---|---|
| Priority on my support queue | Community support, best effort |
| New Minecraft versions and platforms first | Stable builds, once they've settled here |
| Pays for the test rig | - |

New platforms and backends go out here first because they need hands-on support while they settle, and this is the only place I promise that. Once something is stable it goes to the free channel. A native BungeeCord proxy adapter is planned; for now BungeeCord networks use plugin messaging on the backend.

Switching between the free download and this one is a jar swap. Config, data files and commands stay the same.
<!-- /kind -->
<!-- /only -->

---

## Install

**Requirements:** Java 21+. Paper, Folia, Spigot or another Bukkit-family server (Arclight / Mohist for Forge) on 1.20+, or Fabric (with Fabric API) / NeoForge on 1.21.1+, up to 26.x.

1. Drop `{{jar}}` into `plugins/` (or `mods/` on Fabric / NeoForge).
2. Start the server. A `default` region is written for you.
3. Type `/rtp`. To change anything there are three routes: `/rtp admin setup` (guided, works from console), `/rtp admin` (config editor book) or the YAML under `plugins/RTP/`. All three edit the same files and reload at runtime; mix them freely. `/rtp menu` is the player book, for teleporting or picking a region, world or biome.
4. **Size the region to your world**, and point each world at a region. Set `radius`, `centerX` and `centerZ` in the region's `shape:` block. Suffixes set the unit: `radius: 10km`, `radius: 10000b` (blocks) or `radius: 625c` (chunks, the default if you leave it off). See [Regions](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/) and [Worlds](https://dailystruggle.github.io/RTP/admin/configuration/WORLDS/).
5. `advanced/database.yml` stores data in local SQLite out of the box; set `type` to `mysql` or `postgresql` for a shared database. `advanced/network.yml` does nothing until you enable it. In network mode `rtp.servers.*` defaults to `true`: every player can reach an open cross-server region unless you take the node away.

Start here: [**Quick start**](https://dailystruggle.github.io/RTP/admin/QUICK_START/) and [**Intended usage**](https://dailystruggle.github.io/RTP/site/intended-usage/). The full [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) covers the rest.

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
| `/rtp action:<name> [players]` | Trigger a scripted action or arena | `rtp.action` / `rtp.action.<name>` |
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
| `/rtp scan start\|pause\|resume\|reset\|cancel` | Background spatial-memory crawl (was `/rtp fill` in 2.x) | `rtp.scan` |

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
- **Wildcard + state predicate:** `*[waterlogged=true]` - one line instead of listing every waterloggable block.

Unknown tags and properties fail open: a config written for a newer MC version still loads on an older one. Malformed tokens are never silent: `[WARNING] [safety.yml] rejected token '<token>': <reason>`. Block states are only read when a token needs them, and plain-material configs cost nothing extra. Plain-material entries from an older `safety.yml` keep working unchanged.

</details>

<details>
<summary><b>FAQ</b></summary>

**Q: Will players land on top of each other or in each other's bases?**
A: No. A spot doesn't come up again until the shuffle has cycled, and on regions past about 720 blocks of radius consecutive spots are at least 256 blocks apart. See [Spacing between players](#spacing-between-players).

**Q: How does it avoid lagging the server?**
A: Most `/rtp` calls are served from a queue of locations checked before anyone typed the command. The queue refills off the main thread: it skips ground it has already rejected and checks the rest against the region files before loading any chunk. Details are in [How it works](#how-it-works).

**Q: Is it complicated to set up?**
A: No. A `default` region is written on first start, and the only thing you have to do is size it to your world (Install, step 4). Everything else (economy, menus, effects, heatmaps, claim integrations) is optional.

**Q: Why is it called "LeafRTP" now instead of just "RTP"?**
A: "RTP" is the generic name for random teleport, and the old name was just the function. That worked for years with older search and word of mouth - it passed 400k downloads under that name - but current search and marketplace indexes lump it in with every other random-teleport plugin, command and forum thread, and it stopped showing up. "LeafRTP" is a name that points at this plugin. The `/rtp` command, `rtp-api`, config paths and data files stay exactly as they were, and nothing changes for existing installs.

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

**Q: I'm on NeoForge.**
A: There's a native NeoForge adapter for 1.21.x / 26.x running the same core as every other platform. I test it less than the Bukkit family and Fabric, and broader testing on the 26.x carrier is still ongoing. If you hit something there, it goes to the front of my list.

**Q: I'm on Forge.**
A: Run Arclight or Mohist and use this jar. A native Forge adapter isn't planned.

**Q: Memory and MSPT - should I worry?**
A: MSPT, no. Spatial memory stays compact (about 26 bytes per sector segment). Locations kept in the hot queue hold chunk tickets so teleports are instant, and `MemoryTracker` makes sure those tickets are released. Under heap pressure (`maxHeapPercent`, or under 512 MB free) background filling pauses and the held tickets are dropped back to cold storage, which frees the pinned chunks right away.

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

- [**Documentation site**](https://dailystruggle.github.io/RTP/) - every doc in one searchable place
- [**Admin and configuration guide**](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) - install, configure, command reference
- [**Addon / API developer guide**](https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/) - `rtp-api` and examples
- [**Changelog**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**Source on GitHub**](https://github.com/dailystruggle/RTP) - issues, contributions, the benchmark harness
<!-- only: bbb-pro -->
- [**Free download**](https://modrinth.com/plugin/rtpv3)
<!-- /only -->
