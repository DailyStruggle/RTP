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

Listing metadata (for the marketplace form fields, not emitted):
  Paid BuiltByBit title:  "LeafRTP-Pro"   tagline: "Deterministic Random Teleportation engine"
  Free title:             "LeafRTP"       tagline: "Deterministic, off-tick random teleport engine with spatial memory"
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

**{{name}} is a `/rtp` command.** It teleports a player to a random, safe spot in the world and tries to cost the server as little as possible while doing it. I've worked on it in my own time since 2021. The benchmarks and the test harness are in the repo, so you don't have to take my word for any of the numbers below.

<!-- only: bbb-pro -->
<!-- kind: promo -->
This listing is how you pay for support. The code is the same as the [free download](https://modrinth.com/plugin/rtpv3) and all of it is MIT licensed, so paying doesn't unlock anything in the plugin. Details are in [What paying gets you](#what-paying-gets-you) below.
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
On Paper and Folia all candidate checking runs off the tick thread, and on Folia nothing blocks a region thread on a synchronous chunk load. The same jar also loads on Velocity for cross-server `/rtp`.
<!-- /only -->

## Common setups

Most people just want `/rtp` to work without lagging the server. Past that, these work without extra plugins:

- **Portal zones at spawn:** put a cuboid trigger (`/rtp trigger`) over a portal frame. Players walk in, get a countdown hologram (vanilla 1.19.4+ text displays) and note-block chimes, and get teleported. No hologram plugin or armor stands needed.
- **Group and party drops:** send a party or duel queue to random spots with a minimum distance between players (`minSeparation: 32`), so nobody lands on top of anyone else.
- **Sky drops:** drop players in high above the terrain (Y=250 and up) with slow falling or an elytra glide.
- **Spawning near a town or faction:** land players relative to their own town, claim or faction land (`anchor: claimboundary`) instead of a fixed map center.
- **1v1 arenas:** put two queued players in a temporary bounded area with a countdown, and clean it up when they're done.
- **Moving from another rtp plugin:** `/rtp config import` finds the config BetterRTP, JustRTP, EzRTP or JakesRTP left behind and translates worlds, shapes, radii, cooldowns and portal zones into LeafRTP files, without replacing any of yours.

## Why I made it

I wanted to explore Minecraft worlds. I asked for `/rtp` on servers I played on and was told "no, that's laggy", and I took that personally. It took me days of profiling to confirm that a random teleport plugin was the trigger, because the biggest cost in any profiler is never labeled by who called the api. The thousands of extra chunks in memory get attributed to the chunk system, so the operator blames the players. I went through the "blame the users" phase myself before realizing it's better to fix the tool than to tell people not to use it.

In 2021 this started as a demonstration of some math and a hard optimization puzzle. People picked it up as a plugin instead and asked for a lot of features, so I worked on keeping the design small: a few building blocks that combine into a lot of possible configurations. "Chunks" are the unit because the cost to the server is per chunk, not per block, and checking neighbouring blocks is nearly free as long as it doesn't cross a chunk boundary. For V2 I refactored to swappable suppliers and consumers so safety checks, biome checks and shapes can be replaced in code. Frankly "clean code" is a regret, it added input latency, but the structure was a good starting point for reorganizing. V3 was the update for modern game versions and platforms: cache locality, data access, cross-platform support through SPI, and active tracking to catch the "memory leak" I kept hearing about but could never reproduce on my rig.

After V3 I built a test bench that drives up to one `/rtp` per game tick. Every pure reroll implementation I tested (pick a coordinate, load the chunk, check it, try again with no limit on retries) fell off under that load, except this one. The bench also showed that the common shortcut of "use loaded chunks" tends to put players in each other's bases, which turns into griefing or rerolling depending on the claim integration. That finding is why the pre-verified cache exists.

Design decisions, the alternatives I considered, and what replaced what are written down [here](https://dailystruggle.github.io/RTP/adr/).

<div align="center">

*Paper, Folia, Spigot, Fabric and NeoForge on Minecraft 1.20.x / 1.21.x / 26.x, plus Velocity. Forge isn't native: run the jar under Arclight or Mohist. Java 21+.*

</div>

<!-- only: bbb-pro -->
<!-- kind: promo -->
---

## What paying gets you

Same engine, same config, same commands, same MIT licence as the free download. Nothing in the plugin is locked, so what you're paying for is my time:

| This listing | Free download |
|---|---|
| Priority on my support queue | Community support, best effort |
| New Minecraft versions and platforms first | Stable builds, once they've settled here |
| Pays for the test rig, the platforms and the hours | - |

New platforms and backends go out here first because they need hands-on support while they settle, and this is the only place I promise that. Once something is stable it goes to the free channel. A native BungeeCord proxy adapter is planned; for now BungeeCord networks use plugin messaging on the backend.

Switching between the free download and this one is a jar swap. Config, data files and commands stay the same.
<!-- /kind -->
<!-- /only -->

---

## How it works

An rtp plugin is often the heaviest thing on a server and the hardest to spot. The work happens inside API calls, so a profiler shows generic server functions instead of the plugin's name. That's why there's a test bench in the repo instead of a profiler screenshot: the harness attributes the cost back to the caller, which spark can't do.

### Spatial memory

On the worlds I've measured, about 35-65% of the area is unsafe to land on (ocean, lava, void). Selections come off an Archimedean spiral, an indexed mapping from 1D to 2D, which lets the plugin store what it learned about each segment (biome, why it was rejected) and skip it next time. Picking a location is a constant-time lookup with an occasional table rebuild, so the cost per teleport goes down the longer the server runs, and `/rtp scan` keeps mapping while the server is idle. What it learns survives restarts and can be shared between servers through the SQL backends. The math, with distribution plots: [Why LeafRTP exists](https://dailystruggle.github.io/RTP/site/why/).

### Region-file pre-filter

An Anvil (`.mca`) or Linear (`.linear`) pre-filter reads biome and block data straight from the region files on disk, so unloaded candidates can be rejected without loading a chunk, off the tick thread on every platform. It also reads what the world actually contains rather than what the generator predicts: `getBiome` and `getHighestBlockAt` answer from the noise map, which disagrees with the real terrain once a spot has been edited, pregenerated elsewhere, or carried across a Minecraft version. Custom generators (Iris, Terra, datapacks) are read the same way, with namespaced IDs kept. The `.linear` reader uses a pure-Java ZStandard decoder bundled in the jar, so there's no native code and nothing extra to install. When the pre-filter can't answer (no region file, unknown data version, chunk already loaded) it falls back to the platform's own chunk API: one on-tick load on Spigot, one Region Scheduler hop on Folia, limited by the configured tick budget.

### Pre-verified cache

Safe destinations are found ahead of time and a number of them are kept ready per region, so answering `/rtp` means handing back a coordinate that's already been checked. A background sweep takes back chunk tickets from abandoned teleports, so nothing stays force-loaded past its reservation window.

### Spacing between players

Candidates are ordered by a keyed permutation over a Hilbert curve (a 4-round Feistel network), so every chunk in the region comes up once before any repeats, and nothing has to remember where people went. Back-to-back teleports jump across quadrants, at least sqrt(S) chunks apart (128 blocks at S=64, 256 blocks at S=256) and about 4,600 blocks per hop on average on a 4k border. The whole selection state is one 64-bit counter: no distance checks against online players, no history of recent destinations, and no allocation per draw.

### Few dependencies

I avoid shading libraries. The SQL connection pool, the Redis client, cross-server messaging, bStats and the region-file readers are written in-house on plain Java, except for the `.linear` ZStandard decoder (airlift's aircompressor, moved into LeafRTP's own package). That keeps the jar from fighting other plugins over HikariCP, Jedis or Commons Pool versions, and there's no native code to crash. The trade-off is that I maintain all of that code myself.

### Chunk loading, by platform

- **Paper** and forks (Purpur, Pufferfish, Leaf, Leaves, DivineMC, ...) - `World#getChunkAtAsync`, no main-thread fallback. Linear-format servers get the `.linear` pre-filter.
- **Folia** - the pre-filter runs before the Region Scheduler, so rejected candidates never hop a thread. Confirmed candidates load through Folia's async API and teleport through the Entity Scheduler.
- **Spigot** (and Arclight / Mohist) - `.mca` pre-filter off-tick. Throughput is still capped by Spigot's chunk generator, and a candidate the pre-filter can't answer costs one on-tick chunk load.
- **Fabric** and **NeoForge** - in-tree adapters running the same core. I test them less than the Bukkit family.

---

## Performance

Every number below comes from a public harness: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Rig: Ryzen 9 3900X, 16 GiB allocated to the server, one rig for every row. Each other plugin ran on the latest version it supported at the time of the run, with cooldowns, delays and countdowns zeroed and queues enabled where the plugin has them. 60 s warm-up before measuring. Both LeafRTP downloads are built from the same code, so the LeafRTP rows apply to either.

The number I care about most isn't in the headline columns: **chunks loaded per teleport**, counted from the server's own chunk-load events. Part of it is the same for every plugin: the server loads the area around where the player lands, a 5x5 block of up to 25 chunks. The rest is what a plugin spends finding that spot. How much of a world is unsafe depends on the world, but it's usually close to a coin toss - about half of random candidates are ocean, lava or void - so a plugin that loads a chunk to check it also pays for every candidate it throws away. LeafRTP checks candidates against the region files first, so under 5 % of them ever get loaded, and it pins one chunk: the destination.

In the pinned-heap Folia run (`20260921-134507`, 4,096 teleports per plugin) that came out at **26.1 chunks per teleport for LeafRTP and 75.1 for JustRTP, about 3x**. CPU was measured in the same run: JustRTP used **4x the process CPU (729 vs 182 ms per teleport)** and **10x the region-thread CPU (79.6 vs 7.9 ms)**. EzRTP loaded 22.1 chunks per teleport, slightly fewer than LeafRTP, so chunk count isn't where it falls behind - its cost is on the region thread (21.4 ms per teleport). The region-file check needs terrain that already exists; a chunk that has never been generated has to be generated whichever plugin asks for it.

![Cross-plugin destination scatter comparison](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/cross_plugin_destinations_scatter_chart.png)

*Where 4,096 players landed on Paper 26.1: LeafRTP had 0 duplicate landings and its closest pair was 78.4 blocks apart; JustRTP had 167 exact duplicates. Full numbers are in the tables below.*

<details>
<summary><b>Full benchmark tables (Paper and Folia)</b></summary>

Only runs against versions of other plugins that are still current are listed; older Spigot / Paper 1.20.1 runs were dropped once every plugin in them had shipped a newer release.

**Metrics:** TP/s (higher is better) | latency percentiles (dispatch to teleport event, lower is better) | Min TPS (20.00 = no hiccup) | MSPT p99 / max (main-thread tick time in ms, lower is better) | Success (share of dispatched commands that produced a teleport).

A "success" is the harness seeing that player's `PlayerTeleportEvent` within 5 s of dispatching the command. It doesn't check where the player landed: a teleport onto a bad block counts as a success, and a plugin that gave up cleanly with a chat message counts as a failure. The two ways to fail are the 5 s timeout and a teleport event with no destination.

**Paper 26.1, no pacing (`20260617-232754`)** - 3 OPed clients, up to 4 teleports in flight, per-player gap set to 0, so every client sends the next `/rtp` as soon as the last one lands. About 600 s per plugin.

| Plugin | TP/s | p50 | p95 | p99 | Min TPS | MSPT p99 / max (ms) | Success |
|---|---|---|---|---|---|---|---|
| **LeafRTP** | **18.7** | **1 ms** | **2 ms** | **46 ms** | **17.5** | **85.8 / 98.4** | **16560 / 16560 (100 %)** |
| EzRTP | 13.1 | 30 ms | 189 ms | 322 ms | 10.3 | 156.8 / 292 | 7018 / 7137 (98.3 %) |
| BetterRTP | 6.0 | 480 ms | 3 217 ms | 4 402 ms | 2.5 | 859 / 3 663 | 1606 / 1633 (98.3 %) |

**Paper 26.1 and Folia 26.1, unpaced, 4,096 teleports per plugin.** 6 GB pinned heap, every pacing knob in the harness removed (`dispatch-interval = 0 ms`, `per-player-gap = 0 ms`, `immediate-redispatch = true`), up to 4 teleports in flight at once. 32,768 x 32,768 square border with a 1,024-block void around spawn, so every landing has to be on natural terrain; EzRTP's water-surface platform option was turned off so it plays by the same rule. BetterRTP isn't in the Folia rows because it doesn't run there: its PaperLib fallback calls `CraftWorld.getChunkAt` from a global-region thread.

*Paper 26.1:*

| Plugin | Wall time | TP/s | Latency p50 | Latency p99 | Chunks loaded | Tick-thread allocation | Success |
|---|---|---|---|---|---|---|---|
| **LeafRTP** | **4.27 min (256 s)** | **15.98** | **82 ms** | **816 ms** | **70,165** | **21.7 GB (5.3 MB/att)** | **4096 / 4096 (100 %)** |
| EzRTP | 12.86 min (771 s) | 5.31 | 283 ms | 1,009 ms | 103,506 | 77.9 GB (19.1 MB/att) | 4088 / 4096 (99.8 %) |
| JustRTP | 24.09 min (1445 s) | 2.83 | 926 ms | 2,059 ms | 311,967 | 238.2 GB (58.6 MB/att) | 4064 / 4096 (99.2 %) |

*Folia 26.1:*

| Plugin | Wall time | TP/s | Latency p50 | Latency p99 | Chunks loaded | Tick-thread allocation | Success |
|---|---|---|---|---|---|---|---|
| **LeafRTP** | **5.17 min (310 s)** | **13.20** | **143 ms** | **851 ms** | **71,845** | **21.8 GB (5.4 MB/att)** | **4096 / 4096 (100 %)** |
| EzRTP | 9.56 min (573 s) | 7.07 | 308 ms | 654 ms | 91,620 | 61.7 GB (15.4 MB/att) | 4053 / 4096 (98.9 %) |
| JustRTP | 21.07 min (1264 s) | 3.22 | 799 ms | 1,860 ms | 295,075 | 211.4 GB (52.9 MB/att) | 4077 / 4096 (99.5 %) |

The chunk column tells the same story as the pinned-heap run: LeafRTP loaded about 17 chunks per attempt here and JustRTP 76, and JustRTP's search runs on the tick thread, so on Paper that turned into 238 GB of tick-thread allocation, 10 full GC pauses and 6.4 s of GC stalls over the run. EzRTP is about 25 % slower on Paper than on Folia for the same reason: with one tick thread there's nowhere else for its search to go. EzRTP's failed attempts on both platforms are timeouts from an unsynchronized message cache (`ConcurrentModificationException` in `MessageProvider.format`) and, on Folia, cross-region `CraftWorld.getHighestBlockYAt` calls. LeafRTP's candidate search is off-tick on both platforms, which is why its Paper and Folia numbers are close and its tick-thread allocation is 5 MB per attempt. At the time of this run the free download still used a simpler Folia scheduler (about 12.5 TP/s on this harness); both downloads now ship the same Folia adapter.

*Where the 4,096 players landed (Paper 26.1 scatter data):*

- **JustRTP:** 167 exact duplicate landings (4.1 %) and 324 pairs within 48 blocks of each other. Clark-Evans R = 0.83, clustered. With several accounts dispatching at once it handed the same coordinate to two players at the same time; as far as I can tell its cache has no reservation step during async chunk validation, so a single-client test would never show this.
- **EzRTP:** 107 pairs within 48 blocks, closest pair 0 blocks apart (same chunk).
- **LeafRTP:** 0 duplicates, 0 pairs within 48 blocks, closest pair 78.4 blocks apart. Clark-Evans R = 1.04, which is what a uniform random scatter reads.

A wide-radius LeafRTP-only run on Folia (3 clients, hops out to 40k blocks) reached 26 TP/s with cache-served teleports at p50 1 ms. An earlier draft of that run read 15 TP/s because the harness was timing to the destination region's next tick rather than the plugin's own teleport event. The write-up, and why I don't publish 26 as a ceiling, are in the harness notes.

</details>

**Caveats.** Small client counts (3 clients, up to 4 teleports in flight), so the throughput figures are floors, not ceilings. The Folia freeze count reproduced across two runs; everything else is n=1. Hardware, view distance, world state (how much of it is unsafe, whether it's pregenerated) and other plugins will move the numbers. The other plugins update often; if a number here is out of date, open a GitHub issue with a repro or a doc link and I'll correct it.

**What this doesn't claim.** It doesn't claim the other plugins are bad - they're tested at their default queue settings against LeafRTP at its recommended ones, which is what most people actually run. It doesn't extrapolate past 3-4 concurrent clients. It doesn't measure correctness, safety, or claim-plugin compatibility - only dispatch-to-arrival latency, per-attempt cost and success rate as defined above (a teleport within 5 s, not a safe landing).

Full methodology, per-run analyses, and the runs not shown here (equalized-radius Folia, pinned-heap GC profiling, the wide-radius single-plugin run): [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video of `/rtp` on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

<details>
<summary><b>Selection model, in pictures</b></summary>

These charts are rendered by the visualizer tests in `rtp-core` and regenerated on every run, so they show the current shape and cache code, not a mockup. More of them (native-vs-unique placement, the older spiral-vs-polar and circle-boundary plots) are on the [docs site](https://dailystruggle.github.io/RTP/site/why/).

**Spiral-Hilbert walk across radii.** The selector walks a coarse Archimedean spiral of macro-cells and fills each cell with a Hilbert curve whose edge grows with the region radius: a plain 1-chunk spiral at R=32, 2x2 cells at R=64-126, 8x8 at R=256, 16x16 at R=512. Square (Chebyshev) and Circle (Euclidean) shapes share the same path; only the boundary test differs.

![Spiral-Hilbert path progression across radii](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/path_progression_radii_chart.png)

**One region file, chunk by chunk.** Zoom into a single 32x32 Anvil region (1,024 chunks) at cell edges 32, 8 and 4. Each tile picks a rotation or reflection so its Hilbert exit lands next to the next tile's entry; every panel reports 0 jumps and unit steps only, so consecutive keys stay in the same region file and the mapping stays a bijection.

![32x32 region file zoom](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/region_32x32_zoom_path_chart.png)

**Cost of a candidate draw.** Picking the next candidate is a closed-form calculation, not a search: there's no retry loop anywhere in the selector, and a draw measures 1.56 us on one thread. Per-draw state is a 64-bit atomic counter, so a draw allocates nothing. The stride (1, 4, 16, 64 or 256) is chosen from the region size and capped at half the bin area; within a stride group the order is a keyed 4-round Feistel permutation with cycle-walking, which is what makes the sequence non-repeating without keeping a set of visited keys. Consecutive players are at least sqrt(S) chunks apart (16 chunks at S=256) because of the bit-reversal order, with a small randomized batch window so the pattern isn't a predictable lattice.

**Stride and minimum player distance.** The same 1,000-teleport run at S=1, S=64 and S=256. Without a stride the closest pair of players landed 1 chunk apart and 3.7 % of all pairs were under 8 chunks; at S=64 the closest pair is 8 chunks and at S=256 it's 16, with no pair under that floor. The average nearest neighbor barely moves (40 to 48 chunks) because the stride only removes the close pairs, it doesn't spread the rest. The bottom table is the memory cost of tracking used chunks under each strategy: a stride replaces the per-player exclusion stamp with a permutation, so the used-key set at S=256 is 2 KB as a bitmask against 3.6 MB as flat arrays.

![Dyadic stride vs minimum player spacing](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/side_by_side_downsampling_comparison_chart.png)

**The backlog cache, filled.** A 1,024-chunk-radius region (32,768 blocks across) split into its 4,096 region files, with 10,000 pre-screened candidates in the backlog. Each bin gives up 2 to 4 candidates at least 320 blocks apart, so no single region file dominates the queue; the 505 bins that are all ocean were discarded from the map alone and never cost a file read. Filling the 10,000 took under 35 ms off-tick, and the whole region's state persists in under 500 KB. This is where the "chunks loaded" column comes from: candidates are screened here before a chunk is ever loaded for a player.

![Backlog cache state across the region](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/full_l3_state_chart.png)

</details>

<details>
<summary><b>Testing and quality gates</b></summary>

Every threshold below is enforced by a build gate that fails the run when it's missed. Coverage percentages are from the `-Pcoverage` run of 2026-09-15; the floors next to them are what the build enforces, and a floor can't be lowered without a commit. The coverage badge at the top is SonarCloud's single whole-tree number, which includes the platform adapters and addons these per-module gates leave out, so it reads lower than the table.

| Module | Instruction / branch measured | Enforced floor | Target (90 / 80) |
|---|---|---|---|
| `metrics-api` | 100 % / 94.6 % | 0.95 / 0.85 | met |
| `tags-api` | 98.2 % / 90.9 % | 0.95 / 0.85 | met |
| `yaml-api` | 97.0 % / 88.1 % | 0.92 / 0.80 | met |
| `rtp-api` | 95.7 % / 81.3 % | 0.90 / 0.80 | met |
| `maps-api` | 94.7 % / 82.1 % | 0.90 / 0.78 | met |
| `commands-api` | 93.6 % / 80.3 % | 0.90 / 0.80 | met |
| `anvil-api` | 90.5 % / 80.5 % | 0.86 / 0.75 | met |
| `rtp-core` | 81.6 % / 65.8 % | 0.80 / 0.64 | not met - branch coverage still climbing |
| `rtp-proxy-common` | 87.0 % / 68.8 % | 0.85 / 0.67 | not met - branch coverage still climbing |

Safety-critical packages inside `rtp-core` have higher floors on top of the module number: the teleport pipeline at 0.87 / 0.75, region selection at 0.82 / 0.68, and world-border math at 0.99 / 0.89.

| Gate | What it enforces | Where it runs |
|---|---|---|
| Test suite | 651 committed JUnit test classes; `rtp-core` alone has roughly 3,700 test cases | `gradle.yml` on every push and pull request |
| Mutation testing (PIT) | weekly PIT run over the teleport pipeline, region cache and world-border packages; the scheduled teleport-pipeline shard gates at 40 % while I work toward 60 % | `mutation-testing.yml` |
| Safety prohibitions | one automated test per prohibition (unsafe destinations, force-loaded chunks, claim bypass, swallowed teleport failures, main-thread chunk I/O, null-guard no-ops on the public API, hardcoded failure messages), plus ArchUnit layering and chunk-ticket rules | `gradle.yml` |
| Changed-line coverage | new and modified lines must be >= 80 % covered | `gradle.yml` (`scripts/diff-coverage.py`) |
| Static analysis | SpotBugs plus a PMD ruleset with a project-specific rule that flags blocking constructs | `gradle.yml` (`-PstaticAnalysis`) |
| Binary API compatibility | japicmp check against a released baseline is wired up but not enforcing yet: the configured baseline isn't a published version, so the check skips. Removals follow a 2-minor deprecation policy | `gradle.yml` (japicmp) |
| Dependency CVEs | OWASP dependency-check fails at CVSS >= 4 on the scanned files; scanning the resolved Gradle dependency graph and shaded jar isn't in place yet | `dependency-check.yml`, weekly and on dependency changes |
| Release provenance | SLSA build provenance on every release; GitHub releases also carry a CycloneDX SBOM and SHA-256 / SHA-512 checksums | `release.yml`, `release-bbb.yml` |
| Runtime acceptance | nightly multi-server devstack: 2 Velocity proxies, 2 lobbies, 2 backends over Redis, on Velocity + Paper + Folia + Fabric (MC 1.21.11, Java 25) and NeoForge (MC 1.21.1, Java 21) | `devstack-acceptance.yml` |
| Encoding and docs | tracked text files scanned for UTF-8 mojibake; the docs site builds from the same tree | `gradle.yml`, `docs.yml` |

**What I haven't finished.** `rtp-core` and `rtp-proxy-common` are still under the 90 / 80 target on branch coverage. Their floors sit a point or two below the measured numbers, because a partially recompiled tree makes JaCoCo discard stale execution data and read low; the floors only ever go up. What's left uncovered in `rtp-core` is mostly the platform bootstrap and dispatch seams that a plain-JVM test can't reach honestly, so I'm covering those with runtime-attested coverage from the nightly devstack instead of writing tests that only exercise mocks. Spigot, NeoForge, BungeeCord and non-LTS Java releases run fine but have no scheduled live CI suite, so the support matrix lists them as best-effort.

**What these numbers mean.** Coverage and mutation scores say how thoroughly the tests exercise the code. They're not a claim that there are no bugs, and they say nothing about how it behaves on your world, your generator or your plugin list; the nightly devstack and your own staging server cover that.

</details>

---

## Features

- **In-game menus** - two books (Paper / Folia; chat pages elsewhere). `/rtp menu` is the player side: teleport, or pick a region, world, or biome. `/rtp admin` is the operator side: config editor with search, setup prefabs, region and MSPT/heap visualizations, scan control, diagnostics, and the bundled docs (`/rtp docs`). Gated on `rtp.menu.admin`; holders also see it as an extra row in `/rtp menu`.
- **Web editor** - `/rtp editor` gives you a link to a temporary web page where you can draw polygon or donut regions on a map, see the YAML diff, and apply it. It doesn't open a port on your server, and there's a single-file HTML export if you want to use it offline.
- **Setup wizard** - `/rtp admin setup` walks you through the same prefabs from console or chat: pick the world and map its border, set gameplay defaults (cooldown, delay, price, safety toggles), tune cache limits and I/O threads, `preview` the result, then `confirm`. Nothing is written until you confirm, and it backs up the files it touches first.
- **Import from another rtp plugin** - `/rtp config import` reads the config another plugin left in `plugins/` and translates worlds, radius and center, shape, cooldowns, prices, database connection blocks and arrival effects. It finds the source folder itself, writes new files next to yours, never replaces an existing file unless you pass `overwrite=true` (and then backs the old one up first), and reloads when done. Written against sample configs from BetterRTP, JustRTP, EzRTP, JakesRTP, AsyncRTP and AdvancedRTP; anything else goes through a generic keyword matcher. `/rtp config import permissions` prints the LuckPerms commands that would mirror their permission nodes onto mine; it doesn't run them for you.
- **Units you can read** - `radius: 10km`, `radius=625c`, `teleportCooldown: 2h30m`, `cacheCap: 500mb`. Distances take blocks (`b`), chunks (`c`), region files (`r`), km and miles; times take ticks (`t`), ms, s, m, h, d, w; sizes take kb / mb / gb. Plain numbers still mean what they always did.
- **Regions** - any number per world: shape (Square, Circle, Rectangle, Polygon), radius, center, curve weighting, vertical bounds, world override, permission gate, price. Vertical adjustors (Linear, Jump, Fixed) for sky islands, void worlds and Nether ceilings. A `worlds.yml` `override` sends a Nether or End `/rtp` to a safe world.
- **Scripted actions and arenas** - multi-player placement and confinement written in YAML (`definitions/actions/<name>.yml`) instead of minigame code: minimum player spacing, elevation tolerance, moving or static borders, leash radius, damage on breach, and lifecycle triggers (`onStart`, `onBoundaryViolation`, `onExpire`, `onDeath`). Vanilla scoreboards (`rtp_violations`, `rtp_time_left`, `rtp_in_bounds`) are kept updated for command blocks and datapacks. Cuboid triggers (`/rtp trigger`) fire an action when a player walks in, and `/rtp back` returns them.
- **Per-player queues** alongside the shared queue (`rtp.personalqueue`), so one player's bad luck doesn't hold up someone else's teleport.
- **Effects** - particles, sounds, fireworks, potions, titles, console or player commands, and holograms on every teleport phase, gated by `rtp.effects.<name>`. Holograms use the 1.19.4+ text display entity directly, so no hologram plugin is needed; DecentHolograms and HolographicDisplays are used if you already run them. The Rift addon under `addons/` in the repo is a worked example.
- **Arrival schematics** - drop a Sponge `.schem` named after a region into `plugins/RTP/advanced/schematics/` and every teleport into that region pastes it centered on the landing spot. Decoded in-house, no WorldEdit needed, and claim-aware.
- **Economy** - charge per `/rtp` through Vault, per-region pricing, refund on cancel, `rtp.free` bypass.
- **Claim plugins** - 16 on Bukkit / Paper / Folia through the bundled claim addon (GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, HuskTowns, PlotSquared, RedProtect, CrashClaim, KingdomsX, Residence, UltimateClaims, MinePlots), plus FTB Chunks and Open Parties and Claims on Fabric / NeoForge. Claim checks run in the async pipeline, not on the teleport tick. You can add your own through `RegionVerifierRegistry` with one lambda.
- **Claim- and faction-anchored destinations** - an action can land players relative to their own town, claim or faction land (`anchor: claimboundary`, `anchor: faction`) instead of a fixed center. Towny, GriefPrevention and SaberFactions / FactionsUUID expose their boundaries out of the box; others plug in through `ClaimBoundaryProvider`. The anchor stays put while it's still inside the claim, with a cooldown on recomputing it, so the spiral doesn't drift every time land changes hands, and the anchored region starts with the known-bad chunks of the world region it overlaps.
- **PvP / combat-tag gate** - off by default; refuses or delays `/rtp` for players who recently dealt or took PvP damage. Built-in tracking, or PvPManager / CombatLogX / Simple Combat Log if you run one.
- **Movement-cancel, damage-cancel, invulnerability after teleport, landing platform with decay**, countdown and warmup messages.
- **Cross-server `/rtp`** - Velocity, using plugin messaging, or SQL / Redis for reservation tokens and shared state. Tested on the in-repo devstack: 2 Velocity proxies, 2 lobbies, 2 backends behind Redis.
- **Auto-RTP on events** - join, first join, respawn, world change, move, teleport (`rtp.onevent.*`). A login reserve cache keeps destinations ready so join-time teleports don't wait.
- **Diagnostics** - `/rtp info`: queue depth and growth, pipeline latency percentiles, chunk-ticket leak rate, TPS/MSPT, database latency, per-region Folia table, generation success rate and top rejection cause. No metrics add-on needed.
- **Live map heatmaps** - `/rtp scan` paints region safety onto a real held map (green safe, red unsafe) as it verifies it, and `/rtp visualization` draws biome maps, bad-location heatmaps and the selector's walk path the same way. [Video of a scan painting a region](https://youtu.be/Ftjy1zw_S04).
- **PlaceholderAPI** - queue depth (total / public / personal), last-teleport coordinates, player status.
- **Hot reload** - `/rtp reload [file]`, or `/rtp config <file> set k=v`, which saves and reloads.
- **Command blocks and console** - the same parser handles player, console and command-block callers.
- **Docs in the jar** - the admin guide for your exact version unpacks into `plugins/RTP/docs/` on first run, in case the website has moved on to a newer version.
- **API and addons** - `rtp-api` (pre / mid / post teleport hooks, region verifiers, config, scheduling) and `effects-api`. Addons compile against `rtp-api` only and load through a `ServiceLoader` SPI, so one addon jar runs on Spigot, Paper, Folia, Fabric and NeoForge. The bundled addons (GUI picker, claim integrations, Countdown reference, scripted actions) unpack into `plugins/RTP/addons/` on first run; delete one from that folder to turn it off. They and the other addons in the repo (Rift, Group, Linear, Tether) have full source under `addons/` if you want a starting point.
- **Swappable parts** - server backend, economy, location validity, shapes, world border, PvP checks, region file format and commands can all be replaced through SPI.
- **bStats** - on by default; anonymous usage counts help me decide which platforms to spend time on.

![Web editor: region drawing with live YAML staging](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/web_editor_region_staging_preview.png)

---

## Install

**Requirements:** Java 21+. Paper, Folia, Spigot or another Bukkit-family server (Arclight / Mohist for Forge) on 1.20+, or Fabric (with Fabric API) / NeoForge on 1.21.1+, up to 26.x.

1. Drop `{{jar}}` into `plugins/` (or `mods/` on Fabric / NeoForge).
2. Start the server. A `default` region is written for you.
3. Type `/rtp`. To change anything there are three routes: `/rtp admin setup` (guided, works from console), `/rtp admin` (config editor book) or the YAML under `plugins/RTP/`. All three edit the same state and reload at runtime, so you can mix them. Coming from another rtp plugin: `/rtp config import` reads its config and writes mine next to it, without replacing anything you already have. `/rtp menu` isn't a settings screen - it's the player book for teleporting or picking a region, world or biome.
4. **Size the region to your world**, and point each world at a region. Set `radius`, `centerX` and `centerZ` in the region's `shape:` block. Suffixes set the unit: `radius: 10km`, `radius: 10000b` (blocks) or `radius: 625c` (chunks, the default if you leave it off). See [Regions](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/) and [Worlds](https://dailystruggle.github.io/RTP/admin/configuration/WORLDS/).
5. For SQL / Redis or a proxy network, fill in `advanced/database.yml` and `advanced/network.yml`. Both do nothing until you enable them. In network mode `rtp.servers.*` defaults to `true`, so every player can reach an open cross-server region unless you take it away.

Start here: [**Quick start**](https://dailystruggle.github.io/RTP/admin/QUICK_START/) and [**Intended usage**](https://dailystruggle.github.io/RTP/site/intended-usage/). The full [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) is also unpacked into `plugins/RTP/docs/` on first run.

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
| `/rtp trigger create\|remove\|list` | Cuboid zones that fire an action on entry | `rtp.trigger` |
| `/rtp centerx=<x> centerz=<z> radius=<r>` | One-off overrides for this call; units allowed (`radius=10km`) | `rtp.params` |
| `/rtp menu` | Player book menu | `rtp.use` |
| `/rtp admin` | Operator book menu | `rtp.menu.admin` |
| `/rtp editor` | Web editor link | `rtp.menu.admin` |
| `/rtp info` | Operator diagnostics | `rtp.info` |
| `/rtp reload [file]` | Reload all configuration, or one file | `rtp.reload` |
| `/rtp config <file> view\|set <k>=<v>` | View or set a config key, then reload | `rtp.config` |
| `/rtp config import [source] [path=<dir>] [overwrite=true]` | Translate another rtp plugin's config; never overwrites without `overwrite=true` | `rtp.config` |
| `/rtp config import permissions` | Print the LuckPerms commands that would mirror another plugin's nodes onto `rtp.*` | `rtp.config` |
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
| `rtp.servers.*` | `true` | Cross-server destinations in network mode (was `op` before 3.3.0) |
| `rtp.admin` | `op` | `reload`, `config`, `scan`, `info` |
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
| `definitions/actions/<name>.yml` | Scripted actions and arenas | `placement.playerSeparation`, `confinement.initialSize`, `confinement.damage`, `lifecycle` |
| `definitions/worlds/<name>.yml` | Per-world region routing and overrides | `region`, `override`, `requirePermission` |
| `definitions/effects/<name>.yml` | Lifecycle effects | per-phase effect definitions |
| `advanced/database.yml` | SQL / Redis persistence | `database.type`, connection settings |
| `advanced/network.yml` | Proxy / multi-server | `network.enabled`, `transport`, `routing`, `reservation` |
| `advanced/performance.yml` | Tick budgets, queue caps, caches | `maxAttempts`, `syncAllottedTime`, `minTPS`, `loginCacheEnabled` |
| `advanced/biomes.yml`, `advanced/blocks.yml`, `advanced/ttl.yml` | Biome whitelist and weighting, block-tag catalog, spatial-memory expiry | toggles and durations |
| `advanced/logging.yml`, `advanced/metrics.yml` | Log verbosity, runtime metrics | toggles |
| `advanced/messages/*.yml`, `lang/**` | User-facing text (`commands`, `player`, `network`, `placeholders`, `system`) and bundled translations | message templates |
| `addons/integrations.yml` | Claim-plugin reroll toggles | `rerollWorldGuard`, `rerollGriefPrevention`, ... |

**Soft dependencies (all optional):** Vault (for the economy charge), PlaceholderAPI, ProtocolLib. PaperLib is no longer needed.

</details>

<details>
<summary><b>safety.yml token grammar</b></summary>

Six token shapes can be mixed freely in `unsafeBlocks` and `airBlocks`:

- **Plain material:** `LAVA`, `MAGMA_BLOCK`.
- **Material + state predicate:** `OAK_SLAB[waterlogged=true]`. Multiple predicates AND together: `OAK_SLAB[waterlogged=true,type=top]`.
- **Numeric range predicate:** `WATER[level>=5]`, `LIGHT[level<8]`. Fails open on a missing or non-numeric value.
- **Vanilla block tag:** `#minecraft:leaves`, `#minecraft:fire`, `#minecraft:campfires`. Expanded from the server's live block-tag registry when the config loads, so datapack and modded tags work too.
- **Tag + state predicate:** `#minecraft:slabs[waterlogged=true]`.
- **Wildcard + state predicate:** `*[waterlogged=true]` - one line instead of listing every waterloggable block.

Unknown tags and properties fail open, so a config written for a newer MC version still loads on an older one. Malformed tokens are never silent: `[WARNING] [safety.yml] rejected token '<token>': <reason>`. Block states are only read when a token needs them, so plain-material configs cost nothing extra. Plain-material entries from an older `safety.yml` keep working unchanged.

</details>

<details>
<summary><b>FAQ</b></summary>

**Q: Will players land on top of each other or in each other's bases?**
A: No. Candidates come off a keyed permutation of the region, so no chunk is handed out twice until the whole region has been used, and back-to-back teleports jump across quadrants, at least 8 to 16 chunks (128-256 blocks) apart. That comes from the order the selector walks in; it doesn't check where online players are or keep a list of past destinations, just a 64-bit counter.

**Q: How does it avoid lagging the server?**
A: Most `/rtp` calls are served from a queue of locations that were already checked before anyone typed the command. Two things keep that queue cheap to refill: the spatial memory (it remembers which parts of the world failed safety checks, so it skips them instead of rerolling) and the region-file pre-filter (it rejects unsafe biomes and blocks from the files on disk before any chunk is loaded, off the main thread).

**Q: Is it complicated to set up?**
A: A `default` region is written on first start, and the only thing you have to do is size it to your world: `/rtp admin setup` asks you the questions, `/rtp admin` is the config editor book, and the YAML under `plugins/RTP/` is right there with the docs for your version next to it. If you're switching from another rtp plugin, `/rtp config import` translates its config. Everything else (economy, menus, effects, heatmaps, claim integrations) is optional.

**Q: Why is it called "LeafRTP" now instead of just "RTP"?**
A: "RTP" is the generic name for random teleport, so the old name was just the function. That worked for years with older search and word of mouth - it passed 400k downloads under that name - but current search and marketplace indexes lump it in with every other random-teleport plugin, command and forum thread, so it stopped showing up. "LeafRTP" is a name that points at this plugin, while the `/rtp` command, `rtp-api`, config paths and data files stay exactly as they were. Nothing changes for existing installs.

**Q: Does it work on Folia?**
A: Yes. Candidates are checked against the region files before anything is scheduled on a region thread, confirmed ones load through Folia's async chunk API, and the teleport goes through the Entity Scheduler. In the Folia benchmark above it had no watchdog stalls.

**Q: How do I set up teleportation between worlds?**
A: Resolution order: the player's current world (or the `world:` parameter) -> that world's target region -> the region's target world. See the admin guide.

**Q: Do you support triangle / diamond region shapes?**
A: Use the `Polygon` shape. A triangle is a 3-vertex polygon and a diamond is a rotated square.

**Q: Do I need Chunky or another pre-generator?**
A: No, but they work well together. `/rtp scan` walks a region off-tick, verifies safety and generates chunks that aren't on disk yet while recording which sectors are unsafe. Run Chunky first if you want the whole map on disk up front, and scan will read those chunks through the region-file pre-filter instead of generating them.

**Q: Iris / Terra / custom datapack generators, or a world upgraded from an older version?**
A: Yes. Region files are read directly, so modded and namespaced IDs are kept, and `/rtp biome:<x>` reflects what's actually on disk rather than what the current seed would generate. That's the case that trips up plugins using the live noise-map lookup after a version migration. Chunks that were never populated fall back to a live load.

**Q: I'm on NeoForge.**
A: There's a native NeoForge adapter for 1.21.x / 26.x running the same core as every other platform. I test it less than the Bukkit family and Fabric, and broader testing on the 26.x carrier is still ongoing, so if you hit something there it goes to the front of my list.

**Q: I'm on Forge.**
A: Run Arclight or Mohist and use this jar. A native Forge adapter isn't planned.

**Q: Memory and MSPT - should I worry?**
A: MSPT, no. Spatial memory stays compact (about 26 bytes per sector segment). Locations kept in the hot queue hold chunk tickets so teleports are instant, and `MemoryTracker` makes sure those tickets are released. Under heap pressure (`maxHeapPercent`, or under 512 MB free) background filling pauses and the held tickets are dropped back to cold storage, which frees the pinned chunks right away.

<!-- only: bbb-pro -->
<!-- kind: promo -->
**Q: Should I pay for this or use the free download?**
A: The free one, unless you want priority support or early builds. The code is the same.

**Q: Can I switch back to the free download?**
A: Yes. Swap the jar; config, data files and commands are the same.
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
