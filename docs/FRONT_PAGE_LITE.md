<!--
Markdown mirror of FRONT_PAGE_LITE.bbcode (free LeafRTP front page).
Kept in sync with the BBCode source by hand; update both when changing copy.

Marketplace listing metadata (current, for SEO reference):
  Title:   "LeafRTP"
  Tagline: "Deterministic Random Teleportation engine"
-->

<div align="center">

# LeafRTP - Random Teleport

</div>

## Purpose

**LeafRTP is a `/rtp` command.** It teleports a player to a random, safe spot in the world, in the most cpu-efficient way it can.

## Origin

We were all players once, and we've all complained about "lag" on servers. Many of us tried to pinpoint where it came from and tbh in my studies it took days of work just to find out that a random teleport plugin was triggering performance issues. The biggest cost in any profiling tool was never labeled based on who called the api - that part is obscured, so the source of the thousands of extra chunks in memory is misattributed. I went through the "blame the users" phase and wised up to realize that it's better to fix the tool than to tell people not to use it. 

In 2021, this project started as a demonstration of mathematical principles and as a high-difficulty optimization puzzle. I wanted to make something for the community to reference and create a new performance standard, make a name for myself as an obsessive developer. It was received instead as a product and a bunch of features were requested, so I worked on the design elegance so that a few design details resulted in an exponential number of possible configurations. As a result it's a little off-meta but not difficult to fully understand. Measure in chunks, define some regions, and access them via command or api, and anything in between is server design nuance.

"Chunks" were selected as the measurement because the cost to the server is chunk-based rather than block-based and checking adjacent blocks is "optimal" if it doesn't leave a chunk boundary.

I don't like regulating how to use it nor what to use it with, so in V2 I refactored to use more swappable suppliers and consumers, making it easy to programmatically swap safety checks, biome checks, shapes, etc.. There wasn't much optimization to do, so I studied coding practices. Frankly "clean code" is a regret as it increased input latency but the structure gave me a pretty good launch point for reorganization.

For v3 I needed to update for modern game versions and modern web platforms. I got some bright ideas about cache locality optimizations, data access optimizations, cross-platform support via SPI (service provider interface) concepts, and active tracking to catch any "memory leak" that I heard about but could never seem to reproduce on my rig. 

Following the V3 update and micro optimizing the selection process, I've created a test bench plugin to assist with testing throughput up to 1 `rtp` call per gametick (20/s) which has demonstrated performance falloff in the pure reroll model, in every implementation I tested, except this one. I was also able to verify that a common optimization to "use loaded chunks" tends towards placing users in each others' bases to exacerbate either griefing or rerolling depending on claim integration.

<div align="center">

*Paper, Spigot, Folia, Fabric, NeoForge, and Velocity, on Minecraft 1.21.x / 26.x*

</div>

---

## What makes it different

Most `/rtp` plugins are designed around a script: "pick a spot, check it, try, try again" + "add x feature". This creates non-deterministic compute costs around "try again". LeafRTP is a reconstruction for engineering rigor in the foundations, prioritizing stability under load.

I have documented design details more precisely [here](https://dailystruggle.github.io/RTP/adr/), denoting design/feature decisions, superseding decisions, and alternatives considered.

### Spatial mapping and memory

Measurements show me about 35-65% of a world is "unsafe" for placement, based on oceans, lava, void.

Selections come off a space-filling Archimedean spiral curve, an indexed mapping from 1D to 2D. The math, with distribution plots: [Why LeafRTP exists](https://dailystruggle.github.io/RTP/site/why/).

The spatial mapping enables storing and recalling information about prior selections, including biome and invalidity cause, using segments rather than image compression, as this enables a specific optimization - offset selections. The location selection phase is a constant-time lookup with occasional table rebuilding that excludes invalid locations, e.g. oceans, lava, void. 

### Anvil pre-filter

An Anvil (`.mca`) pre-filter reads biome and block data straight from the region files on disk, so batches of locations can be filtered if a world is generated and those locations are unloaded. It also helps with reading what the world actually contains, rather than what the generator predicts. The common shortcuts (`getBiome`, `getHighestBlockAt`) answer from the generation noise map, which can disagree with the real terrain once a spot has been edited or carried across a Minecraft version. [Architecture](https://dailystruggle.github.io/RTP/FOR_CONTRIBUTORS/).

### Pre-verified cache

Safe destinations are prepared at-rate and a number of them are kept ready in a cache per defined region, so serving `/rtp` is handing back a coordinate that's already checked. The numbers are in [Performance](#performance) below.

---

## Features

- **In-game tuning** - `/rtp menu` opens a clickable book (Paper / Folia; chat-paginated elsewhere) to browse worlds, pick regions, and change settings live. Includes search function.
- **Effects engine** - particles, sounds, fireworks, potions, titles on every teleport phase.
- **Live map heatmaps** - `/rtp scan` paints region safety onto a real held map.
- **Economy** - charge per `/rtp` (Vault), per-region pricing, auto-refund on cancel.
- **12 claim integrations** - GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, RedProtect, CrashClaim, KingdomsX, Residence.
- **PvP / combat-tag gate, PlaceholderAPI, per-player cooldowns & limits, multi-world overrides.**
- **Cross-server `/rtp`** - Running on Velocity enables cross-server communication via tcp socket, extensible to addons.
- **Platform-independent engine** - core code runs on pure java and custom implementations, enabling cross-server support via lightweight suppliers
- **Docs in jar** - in V3 I started including version-specific docs with the jar in case I update the wiki for newer versions
- **Reproducible benchmarks** with raw CSVs and per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP)
- **bStats** enabled - anonymous usage stats help prioritize platform work.
- **Thorough API with examples** - packed addons function as examples of hooking in to add checks or change `/rtp` behavior.
- **Modifiable SPI** - Most compatibility-related parts are swappable, e.g. server backend, economy, location validity, shapes, world border, pvp checks, platform creation, commands.

---

## Watch it work

`/rtp scan` paints region safety onto a real in-game map (green safe, red unsafe) **as it verifies it**. `/rtp info` reports live TPS/MSPT, heap, latency percentiles, and rejection causes, no metrics add-on. The heavy verification runs off-tick on every platform. [Watch the scan paint a region live (video)](https://youtu.be/Ftjy1zw_S04). Details: [Scan & spatial memory](https://dailystruggle.github.io/RTP/admin/COMMANDS/), [Diagnostics](https://dailystruggle.github.io/RTP/admin/RUNBOOK/).

---

## Requirements

- **Java 21+**
- **A supported server** - Paper, Spigot, or a Bukkit-family fork (Arclight / Mohist for Forge bridges), or Fabric / NeoForge (1.21.x / 26.x).

---

## Install

1. Drop `LeafRTP-x.y.z.jar` into `plugins/` (or `mods/`).
2. Start the server. A `default` region is written for you.
3. Type **/rtp**. It works.
4. **Size the region to your world**, and point each world at a region. `radius`, `centerX`, and `centerZ` live *inside* the region's `shape:` block and are measured in **chunks**, not blocks - a `radius` of `625` reaches 10,000 blocks. See [Regions](https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/) and [Worlds](https://dailystruggle.github.io/RTP/admin/configuration/WORLDS/).

I recommend trying `/rtp admin`

Start here: [**Quick start**](https://dailystruggle.github.io/RTP/admin/QUICK_START/) and [**Intended usage**](https://dailystruggle.github.io/RTP/site/intended-usage/). The full admin guide is auto-unpacked into `plugins/RTP/docs/` on first run, and lives online at the [**admin guide**](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/).

---

## Performance

Every number below comes from a public harness: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP).

<details>
<summary><b>Full benchmark vs. alternative random teleport plugins on Paper, Spigot, and Folia</b></summary>

*Paper rows: 2 OPed clients spamming `/rtp` back-to-back, queues enabled where the plugin offers them, cooldowns/delays zeroed. Folia run: 3 OPed clients, radius equalized to 4096 blocks, ~600 s per plugin.*

*Every benchmark row below was measured locally on the same rig. In the support matrix, cells read from plugin docs or inferred from architecture are noted inline.*

**Metrics:** Throughput (TP/s, higher better) | MSPT p99 (worst 1-in-100 main-thread tick in ms, lower better) | Min TPS (lowest TPS observed; 20.00 = no hiccup) | CPU / TP (main-thread CPU per successful teleport).

**Paper 1.20.1 / 1.21.11** - the reference dataset. Eight plugins, same harness, same world, same two OPed clients.

| Plugin                | TP/s     | MSPT p99 (ms) | Min TPS   | CPU / TP (ms) | Success   |
|-----------------------|----------|---------------|-----------|---------------|-----------|
| **LeafRTP**           | **19.8** | **4**         | **20.00** | **16.9**      | **100 %** |
| JakesRTP              | 20.0     | 70            | 20.00     | 26.0          | 100 %     |
| BetterRTP             | 7.3      | 852           | 20.00     | 53.6          | 100 %     |
| HuskHomes             | 6.2      | 372           | 20.00     | 52.2          | 100 %     |
| AdvancedRTP           | 2.16     | 2 100         | 19.95     | 92.1          | 96.3 %    |
| EzRTP                 | 1.76     | 2 903         | 19.95     | 139.6         | 100 %     |
| AsyRTP                | 1.67     | 4 534         | 19.95     | 38.8          | 100 %     |
| EssentialsX `/tpr`    | 0.96     | 4 504         | 19.95     | 88.9          | 75.9 % *  |

* EssentialsX `/tpr` is a teleport-*request* command (handshake + accept), not a teleport-*do* command; the harness's 5 s per-attempt deadline times out a fraction of the request-accept latencies. Numbers are dispatch-shaped, not plugin-broken.

**Spigot 1.20.1**

| Plugin         | TP/s     | MSPT p99 (ms) | Min TPS |
|----------------|----------|---------------|---------|
| **LeafRTP**    | **1.52** | **3**         | **6.4** |
| JakesRTP       | 1.04     | 2 252         | 7.5*    |
| BetterRTP      | 1.33     | 3 790         | 2.18    |
| HuskHomes      | 0.93     | 4 939         | 2.59    |

* JakesRTP ran last in the phase order and inherited chunk pressure left over from the earlier phases, so its TPS floor is not cleanly attributable to JakesRTP alone.

**Folia 26.1**

(only EzRTP also completed the Folia run; the other tested plugins had compatibility or performance issues there)

| Plugin         | TP/s     | CPU / TP (ms) | Watchdog stalls       | Success     |
|----------------|----------|---------------|-----------------------|-------------|
| **LeafRTP**    | **12.5** | **4.15**      | **0**                 | **100 %**   |
| EzRTP          | 5.3      | 6.34          | 7 (one region 20.4 s) | 96.2 %      |

EzRTP's 7 watchdog stalls (one region unresponsive 20.4 s) are the server's own record of synchronous `World.loadChunk` calls on region threads; LeafRTP issued none. For reference, the Pro Folia adapter cleared the same run at 13.5 TP/s. The shared `rtp-core` engine, not a Pro-only adapter, carries the free build's Folia result.

**Caveats.** Small client counts (2 on Paper, 3 on Folia); the Folia run's EzRTP failure is corroborated by the server's own watchdog log, independent of the harness. Competitor plugins update frequently; corrections welcome via GitHub issue with a contradicting repro or doc link.

Full methodology, raw CSVs, per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video benchmark of `/rtp` on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

</details>


<details>
<summary><b>Commands, placeholders, soft-deps</b></summary>

**Commands** (full reference: [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/))

- **/rtp** - teleport to the default region for your current world or open gui (depending on addons).
- **/rtp [parameter]=[value]** - specify `region=`, `world=`, `player=`, or temporary overrides.
- **/rtp reload** - reload all configuration from disk.
- **/rtp scan start|pause|resume|reset|cancel** - build spatial memory by walking a region (renamed from `/rtp fill` in 2.x). Demo: [youtu.be/Ftjy1zw_S04](https://youtu.be/Ftjy1zw_S04).
- **/rtp menu** - interactive book menu.

**PlaceholderAPI**

- **%rtp_player_status%** - idle, waiting, teleporting, ...
- **%rtp_total_queue_length%**, **%rtp_public_queue_length%**, **%rtp_personal_queue_length%**
- **%rtp_teleport_world%**, **%rtp_teleport_x%**, **%rtp_teleport_y%**, **%rtp_teleport_z%**

**Soft dependencies (all optional):** Vault (for the optional economy charge), PlaceholderAPI, ProtocolLib. *PaperLib is no longer required.*

</details>

<details>
<summary><b>FAQ</b></summary>

**Q: How do I stop `/rtp` from lagging my server, and why is LeafRTP faster than other random teleport plugins?**
A: Most `/rtp` calls serve from a pre-warmed queue - chunks are already loaded and safety-checked before you type the command. Two design choices make that queue cheap to keep full: a **persistent spatial memory** per region (the plugin remembers which sectors of the world failed safety checks, so the spiral selector skips known-bad ground instead of rerolling forever), and an **off-tick async pre-filter** (Anvil region files are read directly to reject unsafe biomes/blocks *before* any chunk is loaded, so candidate verification never blocks the main thread). The pre-warmed queue is just the visible tip - the spatial memory keeps candidate selection bounded, and the async pre-filter keeps verification off the tick loop.

**Q: Is LeafRTP complicated to set up, and does it have economy, a GUI, and particle effects?**
A: No, and yes. Drop the jar in and `/rtp` works immediately, on a `default` region written for you. Size that region to your world - shape, radius, center - and `/rtp menu` does it in-game without editing YAML. It includes a clickable GUI menu, Vault economy (charge per teleport with per-region pricing), a particle / sound / firework effects engine, live map heatmaps, and twelve claim-plugin integrations.

**Q: Why is it called "LeafRTP" now instead of just "RTP"?**
A: "RTP" is the generic term for random teleport, so the old name was nearly impossible to find - it collided with every other random-teleport plugin, command, and forum thread in search and marketplace indexes. "LeafRTP" is a distinct, indexable name that points unambiguously at this plugin while keeping the `/rtp` command, `rtp-api`, config paths, and data files exactly as they were. Nothing changes for existing installs - only the public name.

**Q: Does it work with Iris / Terra / custom datapack generators?**
A: Yes. Region files are read directly, so modded and namespaced biome and block IDs are preserved. No configuration needed. Because biome data comes from the populated `.mca` files rather than the live generator/noise-map lookup, `/rtp biome=<name>` stays correct even on worlds that were pregenerated elsewhere or migrated across a Minecraft version, where seed-based biome assignment has drifted.

**Q: Do you support triangle / diamond region shapes?**
A: Use the `Polygon` shape - a triangle is a 3-vertex polygon and a diamond is a rotated square, so both are already expressible without a separate shape type.

**Q: Do I need Chunky or another pre-generator?**
A: No, but they work well together. `/rtp scan` walks a region off-tick, verifies safety, and generates any chunks it reaches that aren't on disk yet (via the server's own world generator) while recording which sectors are unsafe in persistent spatial memory. A separate pre-generator stays optional: run Chunky first if you want the whole map on disk up front, and scan will then read those chunks cheaply through the Anvil pre-filter instead of generating them as it goes.

**Q: I'm on NeoForge.**
A: NeoForge is a first-class supported platform on Minecraft 1.21.x / 26.x - just drop the mod in.

**Q: I'm on Forge.**
A: Run **Arclight** or **Mohist** (officially supported) and use this jar. A native Forge adapter is not planned.

**Q: Memory and MSPT - should I worry?**
A: LeafRTP trades a bounded amount of RAM (the queue, bounded by `cacheCap`) for speed. TPS should not drop below ~19 from LeafRTP alone on a healthy server; MSPT spikes during new-area generation are expected - that's the cost of generating chunks, not RTP.

**Q: How do I report a bug?**
A: GitHub issue with server version, LeafRTP version, platform, relevant config files, and the error log section. See the [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) for the full reproduction template.

</details>

<details>
<summary><b>Community Support Policy (read before filing an issue)</b></summary>

Support for the free build is **community-tier and best-effort**. A solo maintainer ships fixes when properly-reported issues land.

- **Support covers bugs and configuration questions,** after you've read the admin guide.
- **Bug reports need a reproduction:** server version, LeafRTP version, platform (Spigot / Paper / fork), `config.yml`, `regions/`, `safety.yml`, and the relevant `server.log` section. Reports without these are asked for them once, then closed.
- **"It doesn't work" is not a bug report.** Tell me what you did, what you expected, and what actually happened.
- **Response time:** no SLA on the free build. Critical safety issues jump the queue regardless.
- **Feature requests** via GitHub issues. Priority follows the published roadmap, not ticket volume.

</details>

---

## Links

- [**LeafRTP admin & configuration guide**](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) - install, configure, command reference
- [**LeafRTP addon / API developer guide**](https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/) - `rtp-api` and examples
- [**LeafRTP changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**LeafRTP source on GitHub**](https://github.com/dailystruggle/RTP) - star, watch, contribute, file issues
