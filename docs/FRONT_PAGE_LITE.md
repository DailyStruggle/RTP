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

**Setup is the easy part: drop the jar in, start the server, type `/rtp`.** That's the whole install. A ready-to-go `default` region is already there, so the command works on first boot with nothing to configure.

Want to change something? You never leave the game. `/rtp menu` opens a clickable menu. Browse worlds, pick regions, tune settings live. No YAML to edit. No restart. Nothing to memorize.

Make it yours, no code needed. Square, circle, rectangle, or polygon regions. Per-world rules. Your own prices, your own arrival effects. Set it from the menu or a YAML file, then `/rtp reload`. It's live, no restart. Got more than one world? One click in the menu sets them all up. Same for low-end hosts, Folia, skyblock - pick a preset, done.

Outgrown the defaults? LeafRTP is an engine you can build on - the same public `rtp-api` the plugin itself runs on. One addon jar works on every platform: Spigot, Paper, Folia, Fabric, NeoForge. Add your own claim or biome check in a line. Code a whole new region shape and register it. Hook any stage of a teleport. Schedule your own work and it lands on the right thread for you. It's a starting point, not a dead end.

And it stays smooth, no matter how hard your players hit it. The expensive part of a teleport - loading chunks, running the safety checks - happens off the tick loop, long before anyone types the command, so serving `/rtp` is mostly just handing back a coordinate that's already verified. No lag spike. No "Finding a safe location..." spinner. No stutter when things get busy.

It runs the same on a four-friend box or a packed network. Don't take our word on speed: every claim on this page comes from a public benchmark you can rerun yourself. Safety is audited too: no unsafe blocks, no force-loaded chunks, no claim bypass, no silent failures.

<div align="center">

*Supported: Paper, Spigot, Folia, Fabric, native NeoForge (1.21.x / 26.1.x), and Velocity proxy - Minecraft 1.20.x / 1.21.x / 26.x. Legacy Forge is not native: run this jar under Arclight / Mohist. Send your players to a safe, random spot, engineered to maintain 20.0 TPS without lag spikes.*

**Trusted since 2021: 4.6 stars over 30+ reviews, 430k+ downloads.**

</div>

---

## Why operators pick LeafRTP

<div align="center">

**p95 2 ms, p99 ~2 ticks at 18.7 TP/s and 100% success on Paper. 12.5 TP/s on Folia, zero region stalls.**

</div>

**If `/rtp` is the top entry in your timings report, this is the fix.** LeafRTP is the fastest free Random Teleport plugin for Bukkit-native Minecraft servers (Paper, Bukkit, Arclight, Mohist, and other Bukkit-family forks), with Fabric supported as a first-class platform:

- **No lag spikes when players spam `/rtp`.** Worst-case main-thread tick stays at 4 ms (vs. 70-852 ms for the next plugins) - your TPS holds at 20.00 during a teleport burst.
- **Instant teleports, no "Finding a safe location..." wait.** Pre-verified location queue serves `/rtp` in one tick instead of loading chunks on demand.
- **Works on plain Bukkit servers at Paper-class speed.** Off-tick `.mca` Anvil pre-filter, worst tick stays at 3 ms while competitors spike past 3 seconds.
- **Best Folia support of any free `/rtp`, out of the box.** Regionized scheduling + async teleport, no extra config - and the free build still out-teleports every other Folia-capable plugin tested: in the latest run it sustained 12.5 TP/s at 100% success, a 4.15 ms main-thread cost per teleport, and zero region-watchdog stalls, while EzRTP managed 5.3 TP/s and froze Folia region threads for up to 20 seconds. No region-thread freezes, no second-scale stalls - no paid tier required to get it.
- **Cross-server `/rtp` without Redis or SQL** - the `proxy-direct` transport lets a Velocity lobby send players to a backend region over a lightweight TCP socket, no database required.
- **Clickable `/rtp menu` GUI world & region selection** - players pick worlds and regions from an interactive book menu (Paper / Folia; chat-paginated elsewhere), no commands to memorize.
- **Twelve claim-plugin integrations bundled** (GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, RedProtect, CrashClaim, KingdomsX, Residence) - no add-ons to install.
- **Per-player cooldowns & usage limits** - per-permission cooldown and limit nodes out of the box, so `/rtp` spam is capped without an extra plugin.
- **Audited safety**: no unsafe blocks, no force-loaded chunks, no claim-bypassing teleports, no silent failures.

On **Paper 26.1**, measured on the in-repo benchmark harness, 3 OPed clients spamming `/rtp` back-to-back with the per-player throttle removed (worst case the engine can be hit with), every plugin radius-matched at 4096 blocks:

| Plugin        | TP/s     | p50      | p95      | p99 (latency) | Worst tick (MSPT p99) | Success   |
|---------------|----------|----------|----------|---------------|-----------------------|-----------|
| **LeafRTP**   | **18.7** | **1 ms** | **2 ms** | **46 ms**     | **86 ms**             | **100 %** |
| EzRTP         | 13.1     | 30 ms    | 189 ms   | 322 ms        | 157 ms                | 98.3 %    |
| BetterRTP     | 6.0      | 480 ms   | 3217 ms  | 4402 ms       | 859 ms                | 98.3 %    |

*Methodology: Paper 26.1, 3 OPed clients spamming `/rtp` continuously with `per-player-gap-ticks: 0`, ~600 s per plugin, in-repo harness linked below. Spigot and Folia results, and the throttled 8-plugin reference dataset, are in the full benchmark section.*

**~95% of teleports land in 1-2 ms** straight from the pre-verified queue, and even the slowest 1% (p99) is a single bounded async chunk load at ~2 ticks, never a stall - TPS never dropped below 17.5. At the same offered load BetterRTP's p95 was **3.2 seconds** (~1600x LeafRTP) with TPS crashing to 2.5, and EzRTP's was 189 ms (~95x). Reproduce on your own rig: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP).

**Free doesn't mean bare-bones - everything you'd reach for another plugin to do is already in the box, free:**

- ✅ **Economy** - charge per `/rtp` (Vault), per-region pricing, auto-refund on cancel.
- ✅ **Clickable GUI menu** - pick worlds and regions from a book menu (Paper / Folia), chat-paginated elsewhere.
- ✅ **Effects engine** - particles, sounds, fireworks, potions, titles on every teleport phase.
- ✅ **Live map heatmaps** - `/rtp scan` paints region safety onto a real held map.
- ✅ **12 claim integrations** - GriefDefender, GriefPrevention, Lands, WorldGuard, TownyAdvanced, SaberFactions, FactionsBridge, HuskClaims, RedProtect, CrashClaim, KingdomsX, Residence.
- ✅ **PvP / combat-tag gate, PlaceholderAPI, per-player cooldowns & limits, multi-world overrides.**

It's the rare RTP that's both the fastest *and* the most complete - none of it is held back for speed or ease, and the same off-tick architecture is what keeps it all cheap. (How each feature stays cheap: [Architecture](https://github.com/dailystruggle/RTP/wiki/Architecture).)

<details>
<summary><b>Verification & sources</b></summary>

Every number on this page is anchored in the repo:

- **Reproducible benchmarks** with raw CSVs and per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP)
- **Requirements traced to tests** - every requirement, including the safety prohibitions (no unsafe-block landings, no force-loaded chunks, no claim-bypassing teleports, no main-thread chunk loading, no silently swallowed failures), has an implementing class and regression test: [`TRACEABILITY.md`](https://github.com/dailystruggle/RTP/blob/V3/docs/dev/TRACEABILITY.md)
- **28+ ADRs**, dated and numbered, covering platform-in-scope decisions, the Anvil subsystem, the Brigadier bridge, supersession trails: [`docs/adr/`](https://github.com/dailystruggle/RTP/tree/V3/docs/adr)
- **bStats** enabled - anonymous usage stats help prioritize platform work.

</details>

---

## See the engine work, and watch it live

LeafRTP isn't a black box. `/rtp scan` paints region safety onto a real in-game map (green safe / red unsafe) **as it verifies it**, and `/rtp info` reports live TPS/MSPT, heap, latency percentiles, and rejection causes - no metrics add-on. The heavy verification runs off-tick on every platform. [Watch the scan paint a region live (video)](https://youtu.be/Ftjy1zw_S04) - details: [Scan & spatial memory](https://github.com/dailystruggle/RTP/wiki/Scan-and-Spatial-Memory), [Diagnostics](https://github.com/dailystruggle/RTP/wiki/Diagnostics).

---

## Requirements

A few hard requirements. If any are a **no**, EssentialsX `/rtp` or HuskHomes are fine free alternatives.

- ✅ **Java 21+** on your host (REQ-RTP-SYS-001, non-negotiable).
- ✅ **Paper, Spigot, or a Bukkit-family fork** (Arclight / Mohist supported for Forge bridges). Fabric and NeoForge (1.21.x / 26.1.x) are supported and regularly tested, at feature parity with the Bukkit family in the latest builds.
- ✅ **In-game editing or YAML, your call.** Browse and tune config from the clickable `/rtp menu` (book on Paper / Folia, chat-paginated elsewhere), or edit the plain YAML files directly and version-control them.
- ✅ **Best free Folia support, out of the box** - regionized scheduling + async teleport, no extra config; the latest run sustained 12.5 TP/s at 100% success with zero watchdog stalls, multiples ahead of every other Folia-capable `/rtp` (see the full benchmark section).
- ✅ **Vault economy** works in the free build - charge players per `/rtp` with per-region price, biome surcharges, and a balance floor; cost refunded automatically on cancel or pipeline failure; dormant when Vault is not installed.
- ✅ **Cross-server `/rtp` without Redis or SQL** - the `proxy-direct` transport (Velocity) ships in the free build.

---

## Install (30 seconds)

1. Drop `LeafRTP-x.y.z.jar` into `plugins/`.
2. Start the server. A `default` region is generated for you.
3. Type **/rtp**.
4. Run **/rtp menu** to browse regions, worlds, and configuration in-game (clickable book on Paper / Folia, chat-paginated fallback elsewhere).

Tune `plugins/RTP/config.yml` and `plugins/RTP/regions/*.yml` later - via `/rtp menu` or by editing the YAML directly. Full admin guide is auto-unpacked into `plugins/RTP/docs/` on first run, and lives online at the [**admin guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_SERVER_ADMINS.md).

---

<details>
<summary><b>Full free feature list (everything's free; full list on the wiki)</b></summary>

Deterministic spiral selection, persistent spatial memory, pre-generated location queue, full safety pipeline, per-region/per-world config and shapes, arrival schematics, the effects engine, Vault economy, 12 claim integrations, PvP/combat-tag gate, the public `rtp-api`, platform-agnostic addons, live heatmaps, `/rtp info` diagnostics, and 12 parity-enforced locales. Full reference: [Home](https://github.com/dailystruggle/RTP/wiki/Home) | [Commands](https://github.com/dailystruggle/RTP/wiki/Commands) | [Economy](https://github.com/dailystruggle/RTP/wiki/Economy) | [Effects](https://github.com/dailystruggle/RTP/wiki/Effects) | [Integrations](https://github.com/dailystruggle/RTP/wiki/Integrations) | [API](https://github.com/dailystruggle/RTP/wiki/API).

<details>
<summary><b>Already built in - the things operators usually bolt on with extra plugins</b></summary>

A lot of what people install companion plugins for is already in the free engine, under LeafRTP's own vocabulary. Check here before adding an add-on:

| Capability                                           | How LeafRTP already covers it                                                                                                                                                                                                                                          |
|------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Secure in-game menu (no chest-GUI exploits)          | `/rtp menu` is a *read-only book* UI: clickable destinations and config with the same permission checks as a typed command, and **zero** inventory-dupe / click-exploit surface. The book is the deliberate, safer design, not a missing chest GUI.                    |
| Runtime region authoring (no config-file round-trip) | Ephemeral per-invocation overrides via `rtp.params` (`centerx=`/`centerz=`/`radius=`), or persistent edits via `/rtp config regions <name> centerX=...`. A named region is a full target - center + shape + radius + queue + permissions, not just a saved coordinate. |
| World overrides                                      | Redirect a `/rtp` issued in the Nether or End to a safe world automatically via the `worlds.yml` `override` key - no teleport loops.                                                                                                                                   |
| Open claim-integration API                           | Register any claim/region/biome check through `GlobalRegionVerifiers` / `RegionVerifierRegistry` - open, async, platform-neutral. Twelve claim plugins are already bundled through it; addons add their own with one lambda.                                            |
| First-join random teleport                           | Distribute new players across the map on login, served instantly from the pre-generated queue.                                                                                                                                                                         |
| Built-in operator diagnostics in `/rtp info`         | Live queue depth and growth, pipeline latency percentiles, chunk-ticket leak rate, TPS/MSPT, plus generation success/failure rate and the top coordinate-rejection cause (biome, unsafe block, claim, ...). No separate metrics add-on.                                |
| Command-block & console ready                        | The unified command framework parses player, console, and command-block callers with equal safety - drive `/rtp` from redstone, datapacks, or scripts.                                                                                                                 |
| Self-scheduling API tasks                            | `RTPRunnable` routes work onto the correct region / async thread automatically via `schedule()` - addon authors get correct scheduling for free.                                                                                                                       |
| Platform-agnostic addons (one module, every platform) | Addons compile against `rtp-api` only and load via the `RTPAddon` `ServiceLoader` SPI, so the same addon jar runs on Spigot, Paper, Folia, Fabric, and NeoForge - config, safety hooks, and teleport-pipeline callbacks are all platform-neutral. A platform module is needed only when the addon opens a platform-native UI (see the bundled `RTP_ExampleAddon`). |
| Live config reload (no restart)                      | Retune regions/safety/effects without a restart: `/rtp reload` (all) or `/rtp reload <file>` (one file); `/rtp config <file> set k=v` saves and reloads automatically.                                                                                                 |

</details>

</details>

---

## Platform support

| Platform                                                        | Status                   | Notes                                                                                                                    |
|-----------------------------------------------------------------|--------------------------|--------------------------------------------------------------------------------------------------------------------------|
| **Paper** (+ forks: Purpur, Pufferfish, Leaf, Leaves, DivineMC) | ✅ Recommended            | Fully async via native `getChunkAtAsync`.                                                                                |
| **Spigot** (+ Spigot forks)                                     | ✅ Supported              | Off-tick `.mca` Anvil pre-filter -> Paper-class throughput on plain Spigot.                                              |
| **Arclight / Mohist** (Forge bridges)                           | ✅ Officially supported   | Use the Spigot/Paper jar. Recommended way to run on Forge.                                                               |
| **Folia**                                                       | ✅ Best-in-class (free)   | Regionized scheduling + async teleport, zero config. Out-teleports every other Folia `/rtp` tested (latest run: 12.5 TP/s, 100% success, 4.15 ms main-thread/teleport) with zero region-watchdog stalls.                                                                |
| **Multi-server / proxy** (Velocity)                             | ✅ proxy-direct           | `proxy-direct` transport ships in the free build: cross-server `/rtp` over a lightweight TCP socket, no Redis/SQL.       |
| **Fabric**                                                      | ✅ Supported              | First-class, stable, in-scope platform; tested regularly, at feature parity with the Bukkit family in the latest builds. |
| **Native NeoForge**                                             | ✅ Supported              | First-class adapter on Minecraft 1.21.x / 26.1.x.                                                                        |
| **Native Forge**                                                | 🔁 Use Arclight / Mohist | No native adapter planned.                                                                                               |

---

<details>
<summary><b>Full benchmark vs. alternative random teleport plugins on Paper, Spigot, and Folia</b></summary>

*Paper rows: 2 OPed clients spamming `/rtp` back-to-back, queues enabled where the plugin offers them, cooldowns/delays zeroed. Folia run: 3 OPed clients, radius equalized to 4096 blocks, ~600 s per plugin.*

**Confidence legend:** 🧪 = measured locally · 📖 = read from plugin docs · ❓ = inferred from architecture.

**Metrics:** Throughput (TP/s, higher better) · MSPT p99 (worst 1-in-100 main-thread tick in ms, lower better) · Min TPS (lowest TPS observed; 20.00 = no hiccup) · CPU / TP (main-thread CPU per successful teleport).

**Paper 1.20.1 / 1.21.11** - the reference dataset. Eight plugins, same harness, same world, same two OPed clients.

| Plugin                | TP/s     | MSPT p99 (ms) | Min TPS   | CPU / TP (ms) | Success   |
|-----------------------|----------|---------------|-----------|---------------|-----------|
| **🧪 LeafRTP**        | **19.8** | **4**         | **20.00** | **16.9**      | **100 %** |
| 🧪 JakesRTP           | 20.0     | 70            | 20.00     | 26.0          | 100 %     |
| 🧪 BetterRTP          | 7.3      | 852           | 20.00     | 53.6          | 100 %     |
| 🧪 HuskHomes          | 6.2      | 372           | 20.00     | 52.2          | 100 %     |
| 🧪 AdvancedRTP        | 2.16     | 2 100         | 19.95     | 92.1          | 96.3 %    |
| 🧪 EzRTP              | 1.76     | 2 903         | 19.95     | 139.6         | 100 %     |
| 🧪 AsyRTP             | 1.67     | 4 534         | 19.95     | 38.8          | 100 %     |
| 🧪 EssentialsX `/tpr` | 0.96     | 4 504         | 19.95     | 88.9          | 75.9 % §  |

§ EssentialsX `/tpr` is a teleport-*request* command (handshake + accept), not a teleport-*do* command; the harness's 5 s per-attempt deadline times out a fraction of the request-accept latencies. Numbers are dispatch-shaped, not plugin-broken.

**Spigot 1.20.1** - Spigot's platform-wide chunk-gen ceiling caps everyone in the 1-1.5 TP/s range during the burst; the latency tail is what matters.

| Plugin         | TP/s     | MSPT p99 (ms) | Min TPS |
|----------------|----------|---------------|---------|
| **🧪 LeafRTP** | **1.52** | **3**         | **6.4** |
| 🧪 JakesRTP    | 1.04     | 2 252         | 7.5*    |
| 🧪 BetterRTP   | 1.33     | 3 790         | 2.18    |
| 🧪 HuskHomes   | 0.93     | 4 939         | 2.59    |

**Folia 26.1** - the free build alone, no Pro adapter. Even running the correctness-first Folia fallback (the tuned `rtp-folia` adapter is a Pro extra), the free jar sustained 12.5 TP/s at 100% success with zero region watchdog stalls; on Folia, throughput plus the server-emitted region watchdog are the discriminators (global MSPT is a single-region sample and not meaningful).

| Plugin         | TP/s     | CPU / TP (ms) | Watchdog stalls       | Success     |
|----------------|----------|---------------|-----------------------|-------------|
| **🧪 LeafRTP** | **12.5** | **4.15**      | **0**                 | **100 %**   |
| 🧪 EzRTP       | 5.3      | 6.34          | 7 (one region 20.4 s) | 96.2 %      |

EzRTP's 7 watchdog stalls (one region unresponsive 20.4 s) are the server's own record of synchronous `World.loadChunk` calls on region threads; LeafRTP issued none. For reference, the Pro Folia adapter cleared the same run at 13.5 TP/s - the shared `rtp-core` engine, not a Pro-only adapter, carries the free build's Folia result.

**Architecture support matrix** - *(Spigot / Paper-and-forks / Folia)*

- **LeafRTP** - ✅🧪 Off-tick Anvil pre-filter · ✅🧪 Fully async via `getChunkAtAsync` · ✅🧪 Region Scheduler + off-tick pre-filter, no 1-tick stalls
- **BetterRTP** - ⚠️📖 Sync chunk load on miss · ⚠️📖 No off-tick safety pre-filter · ✅🧪 Folia 1.21.11 functional, p99 ~1.2 s
- **EzRTP** - ❌🧪 `NoSuchMethodError` on Spigot 1.20.1 (Paper-only API) · ✅🧪 Works on Paper, no off-tick pre-filter · ⚠️🧪 Folia: sync `World.loadChunk` on region threads, 7 watchdog stalls, 20.4 s freeze
- **AsyRTP** - ❌🧪 Fails to enable on Spigot 1.20.1 (Paper-only API in `onEnable`) · ✅📖 Paper · ✅📖 Folia
- **SorekillRTP** - ⚠️❓ Designed for Redis cross-server, not single-server perf · ⚠️❓ Same · ❓📖
- **AdvancedRTP** - ⚠️📖 Safety-first, sync chunk load · ⚠️📖 Same · ❌📖
- **JakesRTP** - ⚠️📖 Async via flag; 10-slot cache · ✅📖 Same · ❌📖 No Folia
- **EssentialsX /rtp** - ✅📖 Main-thread chunk load per candidate · ✅📖 Same · ❌📖
- **HuskHomes RTP** - ✅📖 Bundled with homes suite · ⚠️📖 Same · ✅🧪 Folia functional, p99 ~900 ms

**Caveats.** Small client counts only (2 on Paper, 3 on Folia; the number is a floor, not a ceiling); hardware, view distance, world state, and other plugins will move them. Paper rows are 2-client runs (LeafRTP reproduced n=2; others n=1 on a single rig); the Folia run used 3 clients and its EzRTP failure is corroborated by the server's own watchdog log, independent of the harness. Competitor plugins update frequently - corrections welcome via GitHub issue with a contradicting repro or doc link. This table measures performance only; feature breadth is not benchmarked here. LeafRTP ships the clickable GUI menu, Vault economy, the lifecycle effects engine, and twelve bundled claim integrations alongside these numbers - it does not trade features for speed.

Full methodology, raw CSVs, per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video benchmark of `/rtp` on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

</details>


<details>
<summary><b>Commands, placeholders, soft-deps</b></summary>

**Commands** (full reference: [admin guide](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_SERVER_ADMINS.md))

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
A: GitHub issue with server version, LeafRTP version, platform, relevant config files, and the error log section. See the [admin guide](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_SERVER_ADMINS.md) for the full reproduction template.

</details>

<details>
<summary><b>Community Support Policy (read before filing an issue)</b></summary>

Support for the free build is **community-tier and best-effort** - a solo maintainer ships fixes when properly-reported issues land. Respecting this is how the plugin stays fast and current.

- **Support covers bugs and configuration questions,** after you've read the admin guide.
- **Bug reports need a reproduction:** server version, LeafRTP version, platform (Spigot / Paper / fork), `config.yml`, `regions/`, `safety.yml`, and the relevant `server.log` section. Reports without these are asked for them once, then closed.
- **"It doesn't work" is not a bug report.** Tell me what you did, what you expected, and what actually happened.
- **Unsupported on the free tier:** native Forge (use Arclight / Mohist), plugin conflicts I can't reproduce, general MC-server admin questions.
- **Response time:** no SLA on the free build. Critical safety issues jump the queue regardless.
- **Feature requests** via GitHub issues. Priority follows the published roadmap, not ticket volume.

</details>

---

## Links

- [**LeafRTP admin & configuration guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_SERVER_ADMINS.md) - install, configure, command reference
- [**LeafRTP addon / API developer guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_ADDON_DEVELOPERS.md) - `rtp-api` and examples
- [**LeafRTP changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**LeafRTP source on GitHub**](https://github.com/dailystruggle/RTP) - star, watch, contribute, file issues
