<!--
Markdown mirror of FRONT_PAGE_LITE.bbcode (free LeafRTP front page).
Kept in sync with the BBCode source by hand; update both when changing copy.

Marketplace listing metadata (current, for SEO reference):
  Title:   "LeafRTP"
  Tagline: "Deterministic Random Teleportation engine"
-->

<div align="center">

# LeafRTP - Random Teleport for Paper, Spigot, Folia, Fabric, NeoForge & Velocity

*Once upon a time, I wanted to explore a minecraft world. I asked for `/rtp` in servers I played on. They told me "no, that's laggy", and I took that personally.*

</div>

**LeafRTP is a `/rtp` command.** It teleports a player to a random, safe spot in the world, and it works out those safe spots ahead of time (off the main server thread) so the teleport itself doesn't lag the server.

<div align="center">

*Paper, Spigot, Folia, Fabric, NeoForge, and Velocity, on Minecraft 1.20.x / 1.21.x / 26.x. Legacy Forge isn't native: run this jar under Arclight / Mohist.*

**Trusted since 2021: 4.6 stars over 30+ reviews, 430k+ downloads.**

</div>

---

## What makes it different

Most `/rtp` plugins pick a random point, load the chunk, and check if it's safe, retrying until one sticks. That's what stalls the server. Async alone doesn't fix it; it just moves the stall. What LeafRTP does differently is the low-latency, high-throughput engineering behind the two lookups every `/rtp` depends on: where to send the player, and whether that spot is safe. Those are four named mechanisms.

### Archimedean spiral

Destinations come off a space-filling Archimedean spiral curve, a single indexed mapping from one number to a coordinate. Every reachable spot has equal odds instead of piling players near spawn, and picking one is a lookup rather than a reroll. The math, with distribution plots: [Why LeafRTP exists](https://dailystruggle.github.io/RTP/site/why/).

### Spatial memory

A persistent spatial memory records which sectors already failed safety checks, so the selector skips known-bad ground instead of rerolling forever. Search time stays predictable as a region fills up.

### Anvil pre-filter

An Anvil (`.mca`) pre-filter reads biome and block data straight from the region files on disk, so unsafe spots are rejected before a chunk is ever loaded. That is the biome lookup done in bulk and off the tick loop, which is where most of the throughput comes from. It also reads what the world actually contains, not what the generator predicts: the common shortcuts (`getBiome`, `getHighestBlockAt`) answer from the generation noise map, which can disagree with the real terrain once a spot has been edited or carried across a Minecraft version. Checking the saved data means the safety verdict matches the world the player lands in. How the engine stays off-tick: [Architecture](https://dailystruggle.github.io/RTP/FOR_CONTRIBUTORS/).

### Pre-verified cache

Because selection is cheap and the pre-filter is fast, safe destinations are kept ready in a cache, so serving `/rtp` is mostly handing back a coordinate that's already checked. The numbers are in [Performance](#performance) below.

---

## Features

- **Zero-config install** - drop the jar in, type `/rtp`, done. A `default` region is ready on first boot.
- **In-game tuning** - `/rtp menu` opens a clickable book (Paper / Folia; chat-paginated elsewhere) to browse worlds, pick regions, and change settings live. No YAML, no restart.
- **Economy** - charge per `/rtp` (Vault), per-region pricing, auto-refund on cancel.
- **Effects engine** - particles, sounds, fireworks, potions, titles on every teleport phase.
- **Live map heatmaps** - `/rtp scan` paints region safety onto a real held map.
- **12 claim integrations** - GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, RedProtect, CrashClaim, KingdomsX, Residence.
- **PvP / combat-tag gate, PlaceholderAPI, per-player cooldowns & limits, multi-world overrides.**
- **Folia, out of the box** - regionized scheduling + async teleport, no paid tier, no extra config.
- **Cross-server `/rtp` without Redis or SQL** - the `proxy-direct` Velocity transport ships in the free build.
- **Audited safety** - no unsafe blocks, no force-loaded chunks, no claim bypass, no silent failures.

## Build on it

The whole plugin is built on the public `rtp-api` - the same surface you get. Every feature above is wired through it, so you extend LeafRTP instead of forking it, and one addon jar runs unchanged on Spigot, Paper, Folia, Fabric, and NeoForge.

- **One-line hooks.** Veto a destination, charge economy, add placeholders, gate combat, or replace the bare `/rtp` action with a supplier.
- **New region shapes.** Register your own selection shape alongside square / circle / rectangle / polygon.
- **Any teleport stage.** Hook the shape, chunk, vertical, biome, or safety phases of the pipeline.
- **Correct scheduling for free.** `RTPRunnable.schedule()` lands your work on the right region / async thread on every platform.
- **Platform-agnostic addons.** Compile against `rtp-api` only and load via the `RTPAddon` ServiceLoader SPI; add a platform module only for a platform-native UI.

Four worked demo addons (GUI, claim, effect, reference) ship inside the jar and self-extract into `plugins/RTP/addons/` on first run; their source lives under `addons/` as copy-paste starting points. Full API guide: [addon / API developer guide](https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/).

<div align="center">

<img src="https://cdn.modrinth.com/data/TZNIQSHX/images/e349e726cc4e9ecfcd7823a3bc3c4bf0eeb85f0b.png" alt="The clickable /rtp menu: players pick a world or region by clicking, no commands to memorize">

*The clickable `/rtp menu`: players pick a world or region by clicking, no commands to memorize.*

</div>

<details>
<summary><b>Verification & sources</b></summary>

Every number on this page is anchored in the repo:

- **Reproducible benchmarks** with raw CSVs and per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP)
- **Requirements traced to tests** - every requirement, including the safety prohibitions (no unsafe-block landings, no force-loaded chunks, no claim-bypassing teleports, no main-thread chunk loading, no silently swallowed failures), has an implementing class and regression test: [`TRACEABILITY.md`](https://github.com/dailystruggle/RTP/blob/V3/docs/dev/TRACEABILITY.md)
- **28+ ADRs**, dated and numbered, covering platform-in-scope decisions, the Anvil subsystem, the Brigadier bridge, supersession trails: [`docs/adr/`](https://github.com/dailystruggle/RTP/tree/V3/docs/adr)
- **bStats** enabled - anonymous usage stats help prioritize platform work.

</details>

---

## Watch it work

`/rtp scan` paints region safety onto a real in-game map (green safe, red unsafe) **as it verifies it**. `/rtp info` reports live TPS/MSPT, heap, latency percentiles, and rejection causes, no metrics add-on. The heavy verification runs off-tick on every platform. [Watch the scan paint a region live (video)](https://youtu.be/Ftjy1zw_S04). Details: [Scan & spatial memory](https://dailystruggle.github.io/RTP/admin/COMMANDS/), [Diagnostics](https://dailystruggle.github.io/RTP/admin/RUNBOOK/).

---

## Requirements

Hard requirements. If any are a **no**, EssentialsX `/rtp` or HuskHomes are fine free alternatives.

- **Java 21+** (REQ-RTP-SYS-001, non-negotiable).
- **A supported server** - Paper, Spigot, or a Bukkit-family fork (Arclight / Mohist for Forge bridges), or Fabric / NeoForge (1.21.x / 26.1.x).
- **Vault** (optional) for the economy charge; dormant when not installed.

---

## Install (30 seconds)

1. Drop `LeafRTP-x.y.z.jar` into `plugins/`.
2. Start the server. A `default` region is generated for you.
3. Type **/rtp**.
4. Run **/rtp menu** to browse regions, worlds, and configuration in-game (clickable book on Paper / Folia, chat-paginated fallback elsewhere).

Tune `plugins/RTP/config.yml` and `plugins/RTP/regions/*.yml` later - via `/rtp menu` or by editing the YAML directly. Full admin guide is auto-unpacked into `plugins/RTP/docs/` on first run, and lives online at the [**admin guide**](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/).

**Bundled addons unpack themselves on first run.** Four addon jars ride inside the LeafRTP jar and self-extract into `plugins/RTP/addons/` the first time the plugin starts - no second download. Delete one from that folder to turn it off (the folder then exists, so it is never re-extracted):

- **LeafRTPGuiAddon** (GUI demo) - a clickable chest destination-picker. It binds the bare `/rtp` root action, so with a GUI renderer present typing `/rtp` opens the picker instead of teleporting immediately (instant teleport stays reachable from the menu and as a fallback), and adds `/rtp gui` (`rtp.gui`).
- **LeafRTPClaimAddon** (claim integrations) - the 12 claim/protection checkers, so claim-aware teleport works out of the box on the Bukkit family.
- **LeafRTPRiftAddon** (effect demo) - registers a "Virtual Rift" teleport effect that tears the terrain open into a void during warmup (presentation-only client-side blocks, so it can never compromise safety), usable by name in `effects/*.yml`.
- **LeafRTPCountdownAddon** (addon-system reference demo) - live teleport and queue-position countdowns, and the canonical copy-paste reference addon.

For those interested in writing their own: every one of these is shipped as a worked coding example. Their full source lives under `addons/` in the repo, and the Rift and Countdown demos are pure `rtp-api` / `effects-api` (zero platform imports), so they double as copy-paste starting points for your own addon.

---

<details>
<summary><b>Full free feature list (everything's free; full list in the docs)</b></summary>

Deterministic spiral selection, persistent spatial memory, pre-generated location queue, full safety pipeline, per-region/per-world config and shapes, arrival schematics, the effects engine, Vault economy, 12 claim integrations, PvP/combat-tag gate, the public `rtp-api`, platform-agnostic addons, live heatmaps, `/rtp info` diagnostics, and 12 parity-enforced locales. Full reference: [Home](https://dailystruggle.github.io/RTP/) | [Commands](https://dailystruggle.github.io/RTP/admin/COMMANDS/) | [Economy](https://dailystruggle.github.io/RTP/admin/configuration/ECONOMY/) | [Effects](https://dailystruggle.github.io/RTP/admin/configuration/EVENTS_AND_EFFECTS/) | [Integrations](https://dailystruggle.github.io/RTP/admin/configuration/INTEGRATIONS/) | [API](https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/).

<details>
<summary><b>Already built in - the things operators usually bolt on with extra plugins</b></summary>

A lot of what people install companion plugins for is already in the free engine, under LeafRTP's own vocabulary:

| Capability                                           | How LeafRTP already covers it                                                                                                                                                                                                                                          |
|------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Secure in-game menu (no chest-GUI exploits)          | `/rtp menu` is a *read-only book* UI: clickable destinations and config with the same permission checks as a typed command, and **zero** inventory-dupe / click-exploit surface. The book is the deliberate, safer design, not a missing chest GUI.                    |
| Runtime region authoring (no config-file round-trip) | Ephemeral per-invocation overrides via `rtp.params` (`centerx=`/`centerz=`/`radius=`), or persistent edits via `/rtp config regions <name> centerX=...`. A named region is a full target - center + shape + radius + queue + permissions, not just a saved coordinate. |
| World overrides                                      | Redirect a `/rtp` issued in the Nether or End to a safe world automatically via the `worlds.yml` `override` key - no teleport loops.                                                                                                                                   |
| First-join random teleport                           | Distribute new players across the map on login, served instantly from the pre-generated queue.                                                                                                                                                                         |
| Built-in operator diagnostics in `/rtp info`         | Live queue depth and growth, pipeline latency percentiles, chunk-ticket leak rate, TPS/MSPT, plus generation success/failure rate and the top coordinate-rejection cause (biome, unsafe block, claim, ...). No separate metrics add-on.                                |
| Command-block & console ready                        | The unified command framework parses player, console, and command-block callers with equal safety - drive `/rtp` from redstone, datapacks, or scripts.                                                                                                                 |
| Live config reload (no restart)                      | Retune regions/safety/effects without a restart: `/rtp reload` (all) or `/rtp reload <file>` (one file); `/rtp config <file> set k=v` saves and reloads automatically.                                                                                                 |

</details>

</details>

---

## Performance

Every number below comes from a public harness you can rerun yourself: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). How the engine keeps `/rtp` off the tick loop: [Architecture](https://dailystruggle.github.io/RTP/FOR_CONTRIBUTORS/).

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

**Spigot 1.20.1** - Spigot's platform-wide chunk-gen ceiling caps everyone in the 1-1.5 TP/s range during the burst; the latency tail is what matters.

| Plugin         | TP/s     | MSPT p99 (ms) | Min TPS |
|----------------|----------|---------------|---------|
| **LeafRTP**    | **1.52** | **3**         | **6.4** |
| JakesRTP       | 1.04     | 2 252         | 7.5*    |
| BetterRTP      | 1.33     | 3 790         | 2.18    |
| HuskHomes      | 0.93     | 4 939         | 2.59    |

**Folia 26.1** - the free build alone, no Pro adapter. Even running the correctness-first Folia fallback (the tuned `rtp-folia` adapter is a Pro extra), the free jar sustained 12.5 TP/s at 100% success with zero region watchdog stalls; on Folia, throughput plus the server-emitted region watchdog are the discriminators (global MSPT is a single-region sample and not meaningful).

| Plugin         | TP/s     | CPU / TP (ms) | Watchdog stalls       | Success     |
|----------------|----------|---------------|-----------------------|-------------|
| **LeafRTP**    | **12.5** | **4.15**      | **0**                 | **100 %**   |
| EzRTP          | 5.3      | 6.34          | 7 (one region 20.4 s) | 96.2 %      |

EzRTP's 7 watchdog stalls (one region unresponsive 20.4 s) are the server's own record of synchronous `World.loadChunk` calls on region threads; LeafRTP issued none. For reference, the Pro Folia adapter cleared the same run at 13.5 TP/s. The shared `rtp-core` engine, not a Pro-only adapter, carries the free build's Folia result.

**Architecture support matrix** - *(Spigot / Paper-and-forks / Folia)*

- **LeafRTP** - Off-tick Anvil pre-filter | Fully async via `getChunkAtAsync` | Region Scheduler + off-tick pre-filter, no 1-tick stalls
- **BetterRTP** - Sync chunk load on miss | No off-tick safety pre-filter | Folia 1.21.11 functional, p99 ~1.2 s
- **EzRTP** - `NoSuchMethodError` on Spigot 1.20.1 (Paper-only API) | Works on Paper, no off-tick pre-filter | Folia: sync `World.loadChunk` on region threads, 7 watchdog stalls, 20.4 s freeze
- **AsyRTP** - Fails to enable on Spigot 1.20.1 (Paper-only API in `onEnable`) | Paper | Folia
- **SorekillRTP** - Designed for Redis cross-server, not single-server perf | Same | Folia: untested
- **AdvancedRTP** - Safety-first, sync chunk load | Same | No Folia
- **JakesRTP** - Async via flag; 10-slot cache | Same | No Folia
- **EssentialsX /rtp** - Main-thread chunk load per candidate | Same | No Folia
- **HuskHomes RTP** - Bundled with homes suite | Same | Folia functional, p99 ~900 ms

**Caveats.** Small client counts only (2 on Paper, 3 on Folia; the number is a floor, not a ceiling); hardware, view distance, world state, and other plugins will move them. Paper rows are 2-client runs (LeafRTP reproduced n=2; others n=1 on a single rig); the Folia run used 3 clients and its EzRTP failure is corroborated by the server's own watchdog log, independent of the harness. Competitor plugins update frequently; corrections welcome via GitHub issue with a contradicting repro or doc link. This table measures performance only; feature breadth is not benchmarked here. LeafRTP ships the clickable GUI menu, Vault economy, the lifecycle effects engine, and twelve bundled claim integrations alongside these numbers. It does not trade features for speed.

Full methodology, raw CSVs, per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video benchmark of `/rtp` on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

</details>


<details>
<summary><b>Commands, placeholders, soft-deps</b></summary>

**Commands** (full reference: [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/))

- **/rtp** - teleport to the default region for your current world.
- **/rtp [parameter]:[value]** - specify `region:`, `world:`, `player:`, or temporary overrides.
- **/rtp reload** - reload all configuration from disk.
- **/rtp scan start|pause|resume|reset|cancel** - pre-warm spatial memory by walking a region (renamed from `/rtp fill` in 2.x). Demo: [youtu.be/Ftjy1zw_S04](https://youtu.be/Ftjy1zw_S04).
- **/rtp menu** - interactive admin menu; book on Paper / Folia, chat-paginated fallback elsewhere. Hardened in `3.0.0-beta.3`.

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
A: No, and yes. It works zero-config - drop the jar in and `/rtp` works immediately; regions, safety, and effects are all optional to tune later. And it ships, free: a clickable GUI menu, Vault economy (charge per teleport with per-region pricing), a particle / sound / firework effects engine, live map heatmaps, and twelve claim-plugin integrations. You don't trade features for speed - you get both.

**Q: Why is it called "LeafRTP" now instead of just "RTP"?**
A: "RTP" is the generic term for random teleport, so the old name was nearly impossible to find - it collided with every other random-teleport plugin, command, and forum thread in search and marketplace indexes. "LeafRTP" is a distinct, indexable name that points unambiguously at this plugin while keeping the `/rtp` command, `rtp-api`, config paths, and data files exactly as they were. Nothing changes for existing installs - only the public name.

**Q: Does it work with Iris / Terra / custom datapack generators?**
A: Yes. Region files are read directly, so modded and namespaced biome and block IDs are preserved. No configuration needed. Because biome data comes from the populated `.mca` files rather than the live generator/noise-map lookup, `/rtp biome:<x>` stays correct even on worlds that were pregenerated elsewhere or migrated across a Minecraft version, where seed-based biome assignment has drifted.

**Q: Do you support triangle / diamond region shapes?**
A: Use the `Polygon` shape - a triangle is a 3-vertex polygon and a diamond is a rotated square, so both are already expressible without a separate shape type.

**Q: Do I need Chunky or another pre-generator?**
A: No, but they work well together. `/rtp scan` walks a region off-tick, verifies safety, and generates any chunks it reaches that aren't on disk yet (via the server's own world generator) while recording which sectors are unsafe in persistent spatial memory. A separate pre-generator stays optional: run Chunky first if you want the whole map on disk up front, and scan will then read those chunks cheaply through the Anvil pre-filter instead of generating them as it goes.


**Q: I'm on NeoForge.**
A: NeoForge is a first-class supported platform on Minecraft 1.21.x / 26.1.x - just drop the mod in.

**Q: I'm on Forge.**
A: Run **Arclight** or **Mohist** (officially supported) and use this jar. A native Forge adapter is not planned.

**Q: Memory and MSPT - should I worry?**
A: LeafRTP trades a bounded amount of RAM (the queue, bounded by `cacheCap`) for speed. TPS should not drop below ~19 from LeafRTP alone on a healthy server; MSPT spikes during new-area generation are expected - that's the cost of generating chunks, not RTP.

**Q: How do I report a bug?**
A: GitHub issue with server version, LeafRTP version, platform, relevant config files, and the error log section. See the [admin guide](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) for the full reproduction template.

</details>

<details>
<summary><b>Community Support Policy (read before filing an issue)</b></summary>

Support for the free build is **community-tier and best-effort**. A solo maintainer ships fixes when properly-reported issues land. Respecting this is how the plugin stays fast and current.

- **Support covers bugs and configuration questions,** after you've read the admin guide.
- **Bug reports need a reproduction:** server version, LeafRTP version, platform (Spigot / Paper / fork), `config.yml`, `regions/`, `safety.yml`, and the relevant `server.log` section. Reports without these are asked for them once, then closed.
- **"It doesn't work" is not a bug report.** Tell me what you did, what you expected, and what actually happened.
- **Unsupported on the free tier:** native Forge (use Arclight / Mohist), plugin conflicts I can't reproduce, general MC-server admin questions.
- **Response time:** no SLA on the free build. Critical safety issues jump the queue regardless.
- **Feature requests** via GitHub issues. Priority follows the published roadmap, not ticket volume.

</details>

---

## Links

- [**LeafRTP admin & configuration guide**](https://dailystruggle.github.io/RTP/FOR_SERVER_ADMINS/) - install, configure, command reference
- [**LeafRTP addon / API developer guide**](https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/) - `rtp-api` and examples
- [**LeafRTP changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**LeafRTP source on GitHub**](https://github.com/dailystruggle/RTP) - star, watch, contribute, file issues
