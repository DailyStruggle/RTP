<!--
Markdown mirror of FRONT_PAGE.bbcode (LeafRTP-Pro front page, located in scripts/release/FRONT_PAGE.bbcode).
Kept in sync with the BBCode source by hand; update both when changing copy.

Marketplace listing metadata (current, for SEO reference):
  Title:   "LeafRTP-Pro"
  Tagline: "Deterministic Random Teleportation engine"
-->

<div align="center">

# LeafRTP-Pro - Random Teleport for Folia, Paper, Spigot, Fabric, and NeoForge

[![Build](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml/badge.svg)](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=DailyStruggle_RTP&metric=coverage)](https://sonarcloud.io/summary/overall?id=DailyStruggle_RTP)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=DailyStruggle_RTP&metric=bugs)](https://sonarcloud.io/summary/overall?id=DailyStruggle_RTP)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=DailyStruggle_RTP&metric=vulnerabilities)](https://sonarcloud.io/summary/overall?id=DailyStruggle_RTP)
[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=DailyStruggle_RTP&metric=sqale_rating)](https://sonarcloud.io/summary/overall?id=DailyStruggle_RTP)

</div>

## Purpose

**LeafRTP-Pro is a `/rtp` command.** It teleports a player to a random, safe spot in the world, in the most cpu-efficient way it can, and I keep the proof in the repo.

## Who this is for

Large servers, Folia deployments, Velocity networks, and anyone who wants a support response time and early access to new platforms. If you run one Paper server and want `/rtp` to stop showing up in your timings report, the [free build](https://modrinth.com/plugin/rtpv3) is the same engine and will do that; Pro is what I sell to fund the work and to guarantee the parts that carry a hands-on support burden.

Configuration, data files, and commands are identical between the two builds. Upgrading or downgrading is a jar swap.

## Origin

I wanted to explore Minecraft worlds. I asked for `/rtp` on servers I played on and was told "no, that's laggy", and I took that personally. It took me days of profiling to confirm that a random teleport plugin was the trigger, because the biggest cost in any profiler is never labeled by who called the api. The thousands of extra chunks in memory get attributed to the chunk system, so the operator blames the players. I went through the "blame the users" phase myself before realizing it is better to fix the tool than to tell people not to use it.

In 2021 this started as a demonstration of mathematical principles and a high-difficulty optimization puzzle. It was received as a product instead, and a lot of features were requested, so I worked on design elegance: a few design details that produce a very large number of possible configurations. "Chunks" are the unit of measurement because the cost to the server is chunk-based rather than block-based, and checking adjacent blocks is free so long as it does not leave a chunk boundary. For V2 I refactored to swappable suppliers and consumers so safety checks, biome checks, and shapes can be replaced programmatically. Frankly "clean code" is a regret, as it increased input latency, but the structure was a good launch point for reorganization. V3 was the update for modern game versions and platforms: cache locality, data access, cross-platform support via SPI, and active tracking to catch the "memory leak" I kept hearing about but could never reproduce on my rig.

After V3 I built a test bench that drives up to one `/rtp` per gametick. Every pure reroll implementation I tested (pick a coordinate, load the chunk, check it, try again with no bound on the retries) fell off under that load except this one. The bench also showed that the common shortcut of "use loaded chunks" tends to place players in each other's bases, which turns into griefing or rerolling depending on the claim integration. That finding is why the pre-verified cache exists.

What fell out of that, in order:

- a spatial memory that learns which parts of the world are unsafe (completable via `/rtp scan`)
- pre-verified locations cached per region, mixing live-loaded and pre-filtered candidates
- fewer file operations and less scheduling overhead
- cpu cache locality
- less heap churn:
  - off-tick prefiltering, one region file at a time in a statically allocated buffer; the file format is swappable via SPI (`.mca` Anvil and `.linear`)
  - memoryless O(1) non-repetition and nearest-neighbor spacing from the Archimedean spiral mapping
  - O(n+m) invalidity memory in roaring bitmaps, measured at 53.5 KB for a 32k square worldborder
  - invalidity bins are freed automatically, keeping an integer `invalid` count and rebuilding on demand
- selection spacing: nearest-neighbor distance, block palette probability, non-overlapping selections
- coordinates from a CSPRNG, so a client that knows the world seed cannot predict where the next player lands

Design decisions, alternatives considered, and what superseded what are recorded [here](https://dailystruggle.github.io/RTP/adr/).

<div align="center">

*Paper, Folia, Spigot, Fabric, and native NeoForge on Minecraft 1.20.x / 1.21.x / 26.x. Forge is not native: run the Spigot/Paper jar under Arclight or Mohist. Java 21+.*

</div>

---

## What Pro adds

Same engine, same config, same commands. The free jar is the Pro jar with the following removed:

| Pro only                                                                 | What the free build does instead                                        |
|--------------------------------------------------------------------------|-------------------------------------------------------------------------|
| Shaded SQL / Redis drivers and the persistence they enable (H2, SQLite, MySQL, PostgreSQL, Jedis), including the SQL / Redis cross-server transports with reservation tokens and shared state | Flat-file YAML database; cross-server `/rtp` over plugin-messaging only |
| Login reserve cache (a pre-warmed destination for join-time `/rtp`)      | Join-time `/rtp` pulls from the normal region queue                     |
| Tuned Folia adapter (Region Scheduler + off-tick pre-filter, no per-candidate region hops) | Basic regionized scheduler: correct, always hops to the owning region, async teleport |
| Earliest release on each MC version, new platforms first, priority support | Community-tier support, releases after Pro                            |

Everything else ships in both: the spiral selector, spatial memory and `/rtp scan`, the Anvil and Linear pre-filter, the pre-verified queue, `safety.yml` block tags and state predicates, auto-RTP on events, Vault economy, PlaceholderAPI, `lang/**` translations, the 12-plugin claim addon, the effects engine, book menus, Fabric and NeoForge, and the `rtp-api` / `effects-api` surface.

Pro is the early-access tier. New platforms and scaling backends land here first because each one needs hands-on support while it stabilizes, and I only promise that on the paid tier; once something is stable it graduates to the free build. A native BungeeCord proxy adapter is planned; today BungeeCord networks use plugin-messaging on the backend.

---

## What makes it different

Any rtp plugin is often the most demanding piece of software on a server and the most hidden. The heavy lifting happens inside api calls, so a profiler shows generic server functions instead of named plugin functions. That is why there is a test bench in the repo instead of a screenshot of a profile: the harness attributes cost back to the caller, which spark can't do for me.

### Spatial memory

Measurements show me about 35-65% of a world is unsafe for placement, based on oceans, lava, and void. Selections come off an Archimedean spiral, an indexed mapping from 1D to 2D, which lets the plugin store what it learned about each segment (biome, invalidity cause) and skip it next time. Location selection is a constant-time lookup with occasional table rebuilds; cost per teleport falls with uptime, and `/rtp scan` continues the mapping while the server is idle. Learned state survives restarts and can be shared across servers through the SQL backends. The math, with distribution plots: [Why LeafRTP exists](https://dailystruggle.github.io/RTP/site/why/).

### Anvil pre-filter

An Anvil (`.mca`) or Linear (`.linear`) pre-filter reads biome and block data straight from the region files on disk, so unloaded candidates can be rejected without loading a chunk, off the tick thread on every platform. It also reads what the world actually contains rather than what the generator predicts: `getBiome` and `getHighestBlockAt` answer from the noise map, which disagrees with real terrain once a spot has been edited, pregenerated elsewhere, or carried across a Minecraft version. Custom generators (Iris, Terra, datapacks) are read the same way, with namespaced IDs preserved. When the pre-filter cannot answer (no region file, unknown data version, chunk already loaded) it falls through to the platform's native chunk API - one on-tick load on Spigot, one Region Scheduler hop on Folia, bounded by the configured tick budget.

### Pre-verified cache

Safe destinations are prepared at-rate and a number of them are kept ready per region, so serving `/rtp` is handing back a coordinate that is already checked. A background sweep reclaims chunk tickets from abandoned teleports; nothing stays force-loaded past its reservation window.

### Chunk loading, by platform

- **Paper** and forks (Purpur, Pufferfish, Leaf, Leaves, DivineMC, ...) - `World#getChunkAtAsync`, no main-thread fallback. Linear-format servers get the `.linear` pre-filter.
- **Folia** - pre-filter runs before the Region Scheduler; rejected candidates never hop a thread. Confirmed candidates load through Folia's async API and teleport through the Entity Scheduler.
- **Spigot** (and Arclight / Mohist) - `.mca` pre-filter off-tick; throughput is still capped by Spigot's chunk generator, but the main-thread cost per candidate drops to the 3 ms p99 in the table below.
- **Fabric** and **NeoForge** - in-tree adapters running the same `rtp-core`. I test them less than the Bukkit family.

---

## Performance

Every number below comes from a public harness: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Rig: Ryzen 9 3900X, 16 GiB allocated to the server, one rig for every row. Each competing plugin ran on the latest version it supported at the time of the run, with cooldowns, delays, and countdowns zeroed and queues enabled where the plugin offers them. 60 s warm-up before measurement.

The number I care about most isn't in the headline columns: **chunks loaded per attempt**. That's the cost a profiler hands to the server's chunk system instead of to the plugin that asked for it. On the Spigot run it came out at 1.07 for LeafRTP, 35.9 for BetterRTP, 64.0 for HuskHomes. Latency and CPU per teleport are downstream of that ratio.

MSPT p99 leads the tables because throughput saturates: at 1 `/rtp` per gametick the harness itself is the ceiling, so TP/s can tie at the top and tell you nothing. The tail is where the plugins separate.

**Metrics:** MSPT p99 (worst 1-in-100 main-thread tick in ms, lower better) | TP/s (higher better) | Min TPS (20.00 = no hiccup) | CPU / TP (main-thread CPU per successful teleport) | Success (share of dispatched commands that produced a teleport).

A "success" is the harness seeing that player's `PlayerTeleportEvent` within 5 s of dispatching the command. It does not check where the player landed: a teleport onto a bad block counts as a success, and a plugin that gave up cleanly with a chat message counts as a failure. The two ways to fail are the 5 s timeout and a teleport event with no destination.

**Paper 1.20.1 / 1.21.11** - eight plugins, same harness, same world, same two OPed clients.

| Plugin                | MSPT p99 (ms) | TP/s     | Min TPS   | CPU / TP (ms) | Success   |
|-----------------------|---------------|----------|-----------|---------------|-----------|
| **LeafRTP-Pro**       | **4**         | **19.8** | **20.00** | **16.9**      | **100 %** |
| JakesRTP              | 70            | 20.0     | 20.00     | 26.0          | 100 %     |
| HuskHomes             | 372           | 6.2      | 20.00     | 52.2          | 100 %     |
| BetterRTP             | 852           | 7.3      | 20.00     | 53.6          | 100 %     |
| AdvancedRTP           | 2 100         | 2.16     | 19.95     | 92.1          | 96.3 %    |
| EzRTP                 | 2 903         | 1.76     | 19.95     | 139.6         | 100 %     |
| EssentialsX `/tpr`    | 4 504         | 0.96     | 19.95     | 88.9          | 75.9 % *  |
| AsyRTP                | 4 534         | 1.67     | 19.95     | 38.8          | 100 %     |

* EssentialsX `/tpr` is a teleport-*request* command (handshake + accept), not a teleport-*do* command; the harness's 5 s per-attempt deadline times out a fraction of the request-accept latencies. Numbers are dispatch-shaped, not plugin-broken.

**Spigot 1.20.1** - the chunk generator caps every plugin at 1-1.5 TP/s; the latency tail is what separates them.

| Plugin         | MSPT p99 (ms) | TP/s     | Min TPS |
|----------------|---------------|----------|---------|
| **LeafRTP-Pro** | **3**        | **1.52** | **6.4** |
| JakesRTP       | 2 252         | 1.04     | 7.5*    |
| BetterRTP      | 3 790         | 1.33     | 2.18    |
| HuskHomes      | 4 939         | 0.93     | 2.59    |

* JakesRTP ran last in the phase order and inherited chunk pressure left over from the earlier phases, so its TPS floor is not cleanly attributable to JakesRTP alone.

**Paper 26.1 and Folia 26.1, unpaced, 4,096 teleports per plugin.** 6 GB pinned heap, every pacing knob in the harness removed (`dispatch-interval = 0 ms`, `per-player-gap = 0 ms`, `immediate-redispatch = true`), up to 4 teleports in flight at once. 32,768 x 32,768 square border with a 1,024-block void around spawn, so every landing has to be on natural terrain; EzRTP's water-surface platform option was turned off so it plays by the same rule. BetterRTP is absent from the Folia rows because it does not run there: its PaperLib fallback calls `CraftWorld.getChunkAt` from a global-region thread.

*Paper 26.1:*

| Plugin | Wall time | TP/s | Latency p50 | Latency p99 | Chunks loaded | Tick-thread allocation | Success |
|---|---|---|---|---|---|---|---|
| **LeafRTP-Pro** | **4.27 min (256 s)** | **15.98** | **82 ms** | **816 ms** | **70,165** | **21.7 GB (5.3 MB/att)** | **4096 / 4096 (100 %)** |
| EzRTP | 12.86 min (771 s) | 5.31 | 283 ms | 1,009 ms | 103,506 | 77.9 GB (19.1 MB/att) | 4088 / 4096 (99.8 %) |
| JustRTP | 24.09 min (1445 s) | 2.83 | 926 ms | 2,059 ms | 311,967 | 238.2 GB (58.6 MB/att) | 4064 / 4096 (99.2 %) |

*Folia 26.1:*

| Plugin | Wall time | TP/s | Latency p50 | Latency p99 | Chunks loaded | Tick-thread allocation | Success |
|---|---|---|---|---|---|---|---|
| **LeafRTP-Pro** | **5.17 min (310 s)** | **13.20** | **143 ms** | **851 ms** | **71,845** | **21.8 GB (5.4 MB/att)** | **4096 / 4096 (100 %)** |
| EzRTP | 9.56 min (573 s) | 7.07 | 308 ms | 654 ms | 91,620 | 61.7 GB (15.4 MB/att) | 4053 / 4096 (98.9 %) |
| JustRTP | 21.07 min (1264 s) | 3.22 | 799 ms | 1,860 ms | 295,075 | 211.4 GB (52.9 MB/att) | 4077 / 4096 (99.5 %) |

The chunk column is the same story as the Spigot run, just bigger: JustRTP loaded 76 chunks per attempt and its search runs on the tick thread, so on Paper that turned into 238 GB of tick-thread allocation, 10 full GC pauses, and 6.4 s of GC stalls over the run. EzRTP is about 25 % slower on Paper than on Folia for the same reason; with one tick thread there is nowhere else for its search to go. EzRTP's failed attempts on both platforms are timeouts from an un-synchronized message cache (`ConcurrentModificationException` in `MessageProvider.format`) and, on Folia, cross-region `CraftWorld.getHighestBlockYAt` calls. LeafRTP's candidate search is off-tick on both platforms, which is why its Paper and Folia numbers are close to each other and its tick-thread allocation is 5 MB per attempt. The Folia row is the Pro adapter; the free build runs the basic regionized scheduler on Folia at about 12.5 TP/s on this harness, and is identical on Paper.

*Where the 4,096 players landed (Paper 26.1 scatter data):*
- **JustRTP:** 167 exact duplicate landings (4.1 %) and 324 pairs within 48 blocks of each other. Clark-Evans R = 0.83, clustered. With several accounts dispatching at once it handed the same coordinate to two players at the same time; as far as I can tell its cache has no reservation step during async chunk validation, so a single-client test would never show this.
- **EzRTP:** 107 pairs within 48 blocks, nearest pair 0 blocks apart (same chunk).
- **LeafRTP-Pro:** 0 duplicates, 0 pairs within 48 blocks, nearest pair 78.4 blocks apart. Clark-Evans R = 1.04, which is what a uniform random scatter reads.

![Cross-plugin destination scatter comparison](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/cross_plugin_destinations_scatter_chart.png)

A wide-radius LeafRTP-only run on Folia (3 clients, hops out to 40k blocks) reached 26 TP/s with cache-served teleports at p50 1 ms; an earlier draft of that run read 15 TP/s because the harness was timing to the destination region's next tick rather than the plugin's own teleport event. The full write-up and the reason I do not publish 26 as a ceiling are in the harness notes.

**Caveats.** Small client counts (2 on Paper, 3 on Folia), so the throughput figures are floors, not ceilings. The Paper 1.20.1 / 1.21.11 rows reproduced across two consecutive runs (n=2, about 2.5 % on TP/s and 1 ms on p99); everything else is n=1. Hardware, view distance, world state, and other plugins will move the numbers. Competitor plugins update frequently; corrections welcome via GitHub issue with a contradicting repro or doc link.

**What this doesn't claim.** It doesn't claim the other plugins are bad - they're tested at their default queue configuration against LeafRTP at its recommended one, which is what most users actually get. It doesn't extrapolate past 2-4 concurrent clients. It doesn't measure correctness, safety, or claim-plugin compatibility - only dispatch-to-arrival latency, per-attempt cost, and success rate as defined above (a teleport within 5 s, not a safe landing).

Full methodology, raw CSVs, per-run analyses, and the runs not shown here (equalized-radius Folia, pinned-heap GC profiling, the wide-radius single-plugin run): [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video benchmark on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

### Selection model, in pictures

The charts below are rendered by the visualizer tests in `rtp-core` and regenerated on every run, so they show the current shape and cache code, not a mockup. The full set (native-vs-unique placement, the older spiral-vs-polar and circle-boundary plots) is on the [docs site](https://dailystruggle.github.io/RTP/site/why/); the engine is identical in both builds.

**Spiral-Hilbert walk across radii.** The selector walks a coarse Archimedean spiral of macro-cells and fills each cell with a Hilbert curve whose edge grows with the region radius: a plain 1-chunk spiral at R=32, 2x2 cells at R=64-126, 8x8 at R=256, 16x16 at R=512. Square (Chebyshev) and Circle (Euclidean) shapes share the same path; the boundary test is the only difference.

![Spiral-Hilbert path progression across radii](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/path_progression_radii_chart.png)

**One region file, chunk by chunk.** Zoom into a single 32x32 Anvil region (1,024 chunks) at cell edges 32, 8, and 4. Each tile picks a rotation or reflection so its Hilbert exit lands next to the next tile's entry; every panel reports 0 jumps and unit steps only, so consecutive keys stay in the same region file and the mapping stays a bijection.

![32x32 region file zoom](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/region_32x32_zoom_path_chart.png)

**Cost of a candidate draw.** Picking the next candidate coordinate is a closed-form calculation, not a search: there is no retry loop anywhere in the selector, and the draw measures 1.56 us on one thread. Per-draw state is a 64-bit atomic counter, so a draw allocates nothing. The stride (1, 4, 16, 64, or 256) is chosen from the region size and capped at half the bin area; within a stride group the order is a keyed 4-round Feistel permutation with cycle-walking, which is what makes the sequence non-repeating without keeping a set of visited keys. Consecutive players are separated by at least sqrt(S) chunks (16 chunks at S=256) by the bit-reversal order, with a small randomized batch window so the pattern is not a predictable lattice.

**Stride and minimum player distance.** The same 1,000-teleport run at S=1, S=64, and S=256. Without a stride the closest pair of players landed 1 chunk apart and 3.7 % of all pairs were under 8 chunks; at S=64 the closest pair is 8 chunks and at S=256 it is 16, with no pair under that floor. The average nearest neighbor barely moves (40 to 48 chunks) because the stride only removes the close pairs, it does not spread the rest. The bottom table is the memory cost of tracking used chunks under each strategy: a stride replaces the per-player exclusion stamp with a permutation, so the used-key set at S=256 is 2 KB as a bitmask against 3.6 MB as flat arrays.

![Dyadic stride vs minimum player spacing](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/side_by_side_downsampling_comparison_chart.png)

**The backlog cache, filled.** A 1,024-chunk-radius region (32,768 blocks across) split into its 4,096 region files, with 10,000 pre-screened candidates in the backlog. Each bin gives up 2 to 4 candidates at least 320 blocks apart, so no single region file dominates the queue; the 505 bins that are all ocean were discarded from the map alone and never cost a file read. Filling the 10,000 took under 35 ms off-tick, and the whole region's state persists in under 500 KB. This is where the "chunks loaded" column above comes from: candidates are screened here before a chunk is ever loaded for a player.

![Backlog cache state across the region](https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/full_l3_state_chart.png)

---

## Quality gates

Every threshold below is enforced by a build gate that fails the run when it is missed. Coverage percentages are from the `-Pcoverage` run of 2026-09-15; the floors next to them are what the build enforces, and a floor cannot be lowered without a commit. The coverage badge at the top of the page is SonarCloud's single whole-tree number, which includes the platform adapters and addons that these per-module gates exclude, so it reads lower than the table.

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

Safety-critical packages inside `rtp-core` carry higher floors on top of the module number: the teleport pipeline at 0.87 / 0.75, region selection at 0.82 / 0.68, and world-border math at 0.99 / 0.89.

| Gate | Number it enforces | Where it runs |
|---|---|---|
| Test suite | 651 committed JUnit test classes; `rtp-core` alone carries roughly 3,700 test cases | `gradle.yml` on every push and pull request |
| Mutation testing (PIT) | >= 60 % mutation score on safety packages; measured 83 % (world border), 85 % (vertical adjustors), 81 % (region cache), 77 % (memory table), 61 % (teleport pipeline) | `mutation-testing.yml` |
| Safety prohibitions (ArchUnit) | 10 rules: no unsafe destinations, no permanently force-loaded chunks, no claim bypass, no swallowed teleport failures, no main-thread chunk I/O, no null-guard no-ops on the public API, no hardcoded failure messages | `gradle.yml` |
| Changed-line coverage | new and modified lines must be >= 80 % covered | `gradle.yml` (`scripts/diff-coverage.py`) |
| Static analysis | SpotBugs plus a PMD ruleset with a project-specific rule that flags blocking constructs | `gradle.yml` (`-PstaticAnalysis`) |
| Binary API compatibility | all 7 public API modules compared against the last release; an unannounced break fails the build, removals need a 2-minor deprecation window | `gradle.yml` (japicmp) |
| Dependency CVEs | OWASP dependency-check fails at CVSS >= 4; every accepted finding needs written justification in the suppression file | `dependency-check.yml`, weekly and on dependency changes |
| Release provenance | CycloneDX SBOM, SHA-256 and SHA-512 checksums, GPG signatures, SLSA build provenance, and the acceptance-run evidence bundle on every release | `release.yml` |
| Runtime acceptance | nightly multi-server devstack: 2 Velocity proxies, 2 lobbies, 2 backends over Redis, on Velocity + Paper + Folia + Fabric, Java 21 LTS and 25+ | `devstack-acceptance.yml` |
| Encoding and docs | tracked text files scanned for UTF-8 mojibake; the docs site builds from the same tree | `gradle.yml`, `docs.yml` |

**What I haven't finished.** `rtp-core` and `rtp-proxy-common` are still under the 90 / 80 target on branch coverage. Their floors sit a point or two below the measured numbers, because a partially recompiled tree makes JaCoCo discard stale execution data and read low; the floors only ever ratchet upward. What's left uncovered in `rtp-core` is concentrated in the platform bootstrap and dispatch seams a plain-JVM test can't reach honestly, so I'm closing those with runtime-attested coverage from the nightly devstack instead of writing tests that only exercise mocks. Spigot, NeoForge, BungeeCord, and non-LTS Java releases run fine but have no scheduled live CI suite, so the support matrix lists them as best-effort.

**Scope of these numbers.** Coverage and mutation scores measure how thoroughly the tests exercise the code. They are not a bug-free claim, and they say nothing about feature correctness on your world, your generator, or your plugin list; the nightly devstack run and your own staging server cover that.

---

## Features

- **In-game menus** - two books (Paper / Folia; chat-paginated elsewhere). `/rtp menu` is the player side: teleport, or pick a region, world, or biome. `/rtp admin` is the operator side: config editor with search, setup prefabs, region and MSPT/heap visualizations, scan control, diagnostics. Gated on `rtp.menu.admin`. The book is a read-only UI with the same permission checks as a typed command, so there is no inventory-click exploit surface; a chest-GUI picker ships as a bundled addon for operators who want one.
- **Regions** - any number per world; shape (Square, Circle, Rectangle, Polygon), radius, center, curve weighting, vertical bounds, world override, permission gate, price. Vertical adjustors (Linear, Jump, Fixed) for sky islands, void worlds, Nether ceilings. `worlds.yml` `override` redirects a Nether or End `/rtp` to a safe world.
- **Per-player queues** alongside the global queue (`rtp.personalqueue`), so one player's bad luck does not starve another's teleport.
- **Effects engine** - particles, sounds, fireworks, potions, titles on every teleport phase, gated by `rtp.effects.<name>`. The Rift addon under `addons/` in the repo is a worked example.
- **Per-region arrival schematics** - drop a Sponge `.schem` named after a region into `plugins/RTP/advanced/schematics/` and every teleport into that region pastes it centered on the landing spot. Decoded in-house, no WorldEdit required, claim-aware.
- **Economy** - charge per `/rtp` (Vault), per-region pricing, auto-refund on cancel, `rtp.free` bypass.
- **12 claim integrations** via the bundled claim addon - GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, RedProtect, CrashClaim, KingdomsX, Residence. Claim checks run inside the async pipeline, not on the teleport tick. Add your own through `RegionVerifierRegistry` with one lambda.
- **PvP / combat-tag gate** - off by default; refuses or delays `/rtp` for players who recently dealt or took PvP damage. Native tracking, optional PvPManager / CombatLogX / Simple Combat Log integration.
- **Movement-cancel, damage-cancel, invulnerability-after-teleport timers, landing platform with decay**, countdown and warmup messages.
- **Cross-server `/rtp`** - Velocity, with SQL / Redis reservation tokens and shared state (Pro) or plugin-messaging (both builds). Validated on the in-repo devstack: 2 Velocity proxies, 2 lobbies, 2 backends behind Redis.
- **Auto-RTP on events** - join, first join, respawn, world change, move, teleport (`rtp.onevent.*`). Pro adds a login reserve cache so join-time teleports are served from a pre-warmed destination.
- **Diagnostics** - `/rtp info`: queue depth and growth, pipeline latency percentiles, chunk-ticket leak rate, TPS/MSPT, database latency, per-region Folia table, generation success rate and top rejection cause. `/rtp visualization` paints region and biome maps and bad-location heatmaps onto real map items. No metrics add-on needed.
- **PlaceholderAPI** - queue depth (total / public / personal), last-teleport coordinates, player status.
- **Hot reload** - `/rtp reload [file]`, or `/rtp config <file> set k=v` which saves and reloads.
- **Command-block and console ready** - the same parser handles player, console, and command-block callers.
- **Docs in jar** - the admin guide unpacks into `plugins/RTP/docs/` on first run.
- **API and addons** - `rtp-api` (pre / mid / post teleport hooks, region verifiers, config, scheduling) and `effects-api`. Addons compile against `rtp-api` only and load through the `RTPAddon` `ServiceLoader` SPI, so one jar runs on Spigot, Paper, Folia, Fabric, and NeoForge. Three bundled addons (GUI picker, claim integrations, Countdown reference) self-extract into `plugins/RTP/addons/` on first run; those and the other addons in the repo (Rift, Group, Linear, Tether) ship with full source under `addons/` as copy-paste starting points. Delete one from the folder to turn it off.
- **Swappable SPI** - server backend, economy, location validity, shapes, world border, PvP checks, region file format, commands.

---

## Install

1. Drop `LeafRTP-Pro-x.y.z.jar` into `plugins/` (or `mods/`). Upgrading from the free build: replace the jar, keep the data folder.
2. Start the server. A `default` region is written for you.
3. Type `/rtp`. Change anything in-game via `/rtp admin` or in the YAML under `plugins/RTP/`; both edit the same state and reload at runtime.
4. **Size the region to your world.** `radius`, `centerX`, and `centerZ` live inside the region's `shape:` block and are measured in **chunks**, not blocks - a `radius` of `625` reaches 10,000 blocks. A radius left in block-think looks like a broken plugin. See [Regions](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/) and [Worlds](https://dailystruggle.github.io/RTP/admin/configuration/WORLDS/).
5. For SQL / Redis or a proxy network, fill in `advanced/database.yml` and `advanced/network.yml`. Both are inert until enabled.

Start here: [**Quick start**](https://dailystruggle.github.io/RTP/admin/QUICK_START/) and [**Intended usage**](https://dailystruggle.github.io/RTP/site/intended-usage/).

<details>
<summary><b>Commands, permissions, configuration files</b></summary>

**Commands** (aliases: `/rtp`, `/wild`):

| Command | Description | Permission |
| --- | --- | --- |
| `/rtp` / `/wild` | Random teleport to the default region for your current world | `rtp.use` |
| `/rtp region:<name>` | Teleport to a named region | `rtp.region` / `rtp.regions.*` |
| `/rtp world:<world>` | Teleport within a specific world | `rtp.world` / `rtp.worlds.*` |
| `/rtp player:<name>` | Teleport another player | `rtp.other` |
| `/rtp biome:<biome>` | Teleport to a chosen biome | `rtp.biome` / `rtp.biome.*` |
| `/rtp centerx=<x> centerz=<z> radius=<r>` | Ephemeral per-call overrides | `rtp.params` |
| `/rtp menu` | Player book menu | `rtp.use` |
| `/rtp admin` | Operator book menu | `rtp.menu.admin` |
| `/rtp info` | Operator diagnostics | `rtp.info` |
| `/rtp reload [file]` | Reload all configuration, or one file | `rtp.reload` |
| `/rtp config <file> view\|set <k>=<v>` | View or set a config key, then reload | `rtp.config` |
| `/rtp scan start\|pause\|resume\|reset\|cancel` | Background spatial-memory crawl | `rtp.scan` |

**Permissions** (full set in `plugin.yml`):

| Permission | Default | Grants |
| --- | --- | --- |
| `rtp.use` | `true` | Use `/rtp` and `/wild` |
| `rtp.see` | `true` | Tab-complete `/rtp` |
| `rtp.free` | `op` | Skip the Vault charge |
| `rtp.params` | `op` | Per-call overrides (`centerx=`, `centerz=`, `radius=`) |
| `rtp.personalqueue` | `false` | Reserve a per-player pre-warmed location |
| `rtp.onevent.*` | `false` | Auto-RTP on join / firstjoin / respawn / changeworld / move / teleport |
| `rtp.admin` | `op` | `reload`, `config`, `scan`, `info` |
| `rtp.*` | `op` | Everything |

**Configuration files** under `plugins/RTP/`. Every file is hot-reloadable and editable in-game. The everyday files sit at the top level; things you author live in `definitions/`, rarely-touched tuning in `advanced/`.

| File | Scope | Example keys |
| --- | --- | --- |
| `config.yml` | Teleport behavior, menu renderer | `teleportDelay`, `teleportCooldown`, `cancelDistance`, `menu.renderer` |
| `safety.yml` | Block / biome safety filter | `unsafeBlocks`, `airBlocks` |
| `economy.yml` | Vault pricing | `price`, `priceOther`, `biomePrice`, `balanceFloor`, `refundOnCancel` |
| `language.yml` | Locale selection | `language` |
| `definitions/regions/<name>.yml` | Per-region shape, radius, center, queue, price, gates | `shape`, `vert`, `radius`, `cacheCap`, `price`, `requirePermission` |
| `definitions/worlds/<name>.yml` | Per-world region routing and overrides | `region`, `override`, `requirePermission` |
| `definitions/effects/<name>.yml` | Lifecycle effects | per-phase effect definitions |
| `advanced/database.yml` | SQL / Redis persistence | `database.type`, connection settings |
| `advanced/network.yml` | Proxy / multi-server | `network.enabled`, `transport`, `routing`, `reservation` |
| `advanced/performance.yml` | Tick budgets, queue caps, caches | `maxAttempts`, `syncAllottedTime`, `minTPS`, `loginCacheEnabled` |
| `advanced/biomes.yml`, `advanced/blocks.yml`, `advanced/ttl.yml` | Biome whitelist and weighting, block-tag catalog, spatial-memory expiry | toggles and durations |
| `advanced/logging.yml`, `advanced/metrics.yml` | Log verbosity, runtime metrics | toggles |
| `advanced/messages/*.yml`, `lang/**` | User-facing text (`commands`, `player`, `network`, `placeholders`, `system`) and bundled translations | message templates |
| `addons/integrations.yml` | Claim-plugin reroll toggles | `rerollWorldGuard`, `rerollGriefPrevention`, ... |

</details>

<details>
<summary><b>safety.yml token grammar</b></summary>

Six token shapes can be mixed freely in `unsafeBlocks` and `airBlocks`:

- **Plain material:** `LAVA`, `MAGMA_BLOCK`.
- **Material + state predicate:** `OAK_SLAB[waterlogged=true]`. Multiple predicates AND together: `OAK_SLAB[waterlogged=true,type=top]`.
- **Numeric range predicate:** `WATER[level>=5]`, `LIGHT[level<8]`. Fail-open on an absent or non-numeric value.
- **Vanilla block tag:** `#minecraft:leaves`, `#minecraft:fire`, `#minecraft:campfires`. Expanded from the server's live block-tag registry at config load, so datapack and modded tags resolve too.
- **Tag + state predicate:** `#minecraft:slabs[waterlogged=true]`.
- **Wildcard + state predicate:** `*[waterlogged=true]` - one line replaces the whole waterloggable enumeration.

Unknown tags and properties fail open, so a config written for a newer MC version still loads on an older one. Malformed tokens are never silent: `[WARNING] [safety.yml] rejected token '<token>': <reason>`. Block-state extraction only runs when a token needs it, so plain-material configs pay nothing. Plain-material entries from an older `safety.yml` keep working unchanged.

</details>

<details>
<summary><b>Roadmap</b></summary>

- **Fully-automatic self-warming** - background spatial-memory accumulation without the `/rtp scan` verb.
- **Anonymous opt-in telemetry** - reference benchmark sourced from real deployments.
- **Chunky-driven scan generation** - let a bulk pre-generator lay chunks down first, then scan reads them through the Anvil pre-filter.
- **Leaner scan path** - measure how accurately the pre-filter alone trims candidates and, where it is accurate enough, skip the full-load verification pass during scans.
- **Accelerated scan compute (exploration)** - offloading the bulk safety sweep to native SIMD, GPU, or an external generator, with a fallback to the current path.
- **Native BungeeCord proxy adapter.**

File a GitHub issue if you hit something not on the list.

</details>

<details>
<summary><b>FAQ</b></summary>

**Q: Should I buy Pro or use the free build?**
A: Free, unless you need one of the rows in the table above. The engine and the Paper benchmark are the same. Pro pays for SQL / Redis, the login reserve cache, the tuned Folia adapter, and a support response time.

**Q: Does LeafRTP work on Folia?**
A: Both builds. The free build uses a correctness-first regionized scheduler that always hops to the owning region. Pro's adapter runs the pre-filter before the Region Scheduler so rejected candidates never hop, and teleports through the Entity Scheduler. On the unpaced Folia run above the measured difference is 13.2 vs about 12.5 TP/s; I expect it to widen under per-region contention but have not measured that yet.

**Q: How do I set up teleportation between worlds?**
A: Resolution order: player's current world (or `world:` param) -> world's target region -> region's target world. See the admin guide.

**Q: Do you support triangle / diamond region shapes?**
A: Use the `Polygon` shape. A triangle is a 3-vertex polygon and a diamond is a rotated square.

**Q: Do I need Chunky or another pre-generator?**
A: No, but they work well together. `/rtp scan` walks a region off-tick, verifies safety, and generates chunks that aren't on disk yet while recording which sectors are unsafe. Run Chunky first if you want the whole map on disk up front, and scan will read those chunks through the Anvil pre-filter instead of generating them.

**Q: Biome targeting on a pregenerated or upgraded world?**
A: Biome data comes from the populated `.mca` files, so `/rtp biome:<x>` reflects what is actually on disk, not what the current seed would generate. This is the case that trips plugins using the live noise-map lookup after a version migration.

**Q: Iris / Terra / custom datapack generators?**
A: Yes. Region files are read directly, so modded and namespaced IDs are preserved. Un-populated chunks fall through to a live load.

**Q: Memory and MSPT - should I worry?**
A: MSPT, no. Memory, know what you are buying: LeafRTP trades heap for tick time, on purpose. Every cache tier is capacity-limited; about 68 bytes per cached location in the hot buffers and about 26 bytes per known-bad chunk in the compressed spatial segments, so 8 192 cached locations cost well under a megabyte. Background generation pauses under heap pressure (`maxHeapPercent`). If you are tight on RAM, lower `cacheCap`.

**Q: Can I downgrade to the free build?**
A: Yes. Same configuration, same data files, same commands. Swap the jar; `advanced/database.yml` and `advanced/network.yml` become inert and SQL-backed state is not read.

</details>

<details>
<summary><b>Support</b></summary>

Support comes from the maintainer who wrote the code.

- **Priority.** Pro tickets are handled ahead of free-build tickets, and this is the tier on which early-access platforms and scaling backends are guaranteed.
- **Response time:** 24-72 h on weekdays. Critical safety issues jump the queue.
- **Bug reports** with server version, plugin version, platform, relevant configs, and log lines are resolved fastest. Most setup questions are answered in the admin guide.
- **Feature requests** via GitHub issues. Priority follows the published roadmap.
- **Native NeoForge** is supported on 1.21.x / 26.x. **Native Forge** is not: use Arclight or Mohist with the Spigot/Paper jar.

</details>

---

## Links

- [**LeafRTP documentation site**](https://dailystruggle.github.io/RTP/) - searchable, every doc in one place
- [**LeafRTP admin & configuration guide**](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) - install, configure, command reference
- [**LeafRTP addon / API developer guide**](https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/) - `rtp-api` and examples
- [**LeafRTP changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**LeafRTP source on GitHub**](https://github.com/dailystruggle/RTP) - star, watch, contribute, file issues
- [**Free LeafRTP plugin (download)**](https://modrinth.com/plugin/rtpv3)
