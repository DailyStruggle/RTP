<!--
Markdown mirror of FRONT_PAGE_LITE.bbcode (free LeafRTP front page).
Kept in sync with the BBCode source by hand; update both when changing copy.

Marketplace listing metadata (current, for SEO reference):
  Title:   "LeafRTP"
  Tagline: "Deterministic Random Teleportation engine"
-->

<div align="center">

# LeafRTP - Random Teleport for Paper, Spigot, Fabric, NeoForge

*Once upon a time, I wanted to explore a minecraft world. I asked for `/rtp` in servers I played on. They told me "no, that's laggy", and I took that personally.*

</div>

No menu, however polished, can make `/rtp` fast - only the engine behind it can. With most random-teleport plugins, **any** click can make the server load chunk after chunk after chunk: it picks a spot, loads it on the main thread, and - if it turns out unsafe - tries another, and another, with nothing telling it to stop. That lag spike never shows up in the menu. It shows up in your MSPT, in the stutter every other player feels, and eventually in the players who quietly stop logging in.

**LeafRTP was built on one idea: a teleport should cost the server nothing the player can feel. No spike. No spinner. No quiet churn.**

**Benchmarked, not asserted.** 19.8 TP/s sustained at a 4 ms worst-case main-thread tick on Paper (next-best plugin: 70 ms), with audited safety invariants - no unsafe blocks, no force-loaded chunks, no claim-bypassing teleports, no silent failures. Every number on this page is measured on a public harness you can rerun yourself.

<div align="center">

*Supported: Paper, Spigot, Fabric, and native NeoForge (1.21.x / 26.1.x) - Minecraft 1.20.x / 1.21.x / 26.x. Legacy Forge is not native: run this jar under Arclight / Mohist. Send your players to a safe, random spot, engineered to maintain 20.0 TPS without lag spikes.*

**100% Free.** Same engine as the paid [**LeafRTP-Pro**](https://builtbybit.com/resources/rtp-pro.105418) build. No paywalled `/rtp` or nag screens, and no ads - just anonymous [bStats](https://bstats.org/) usage stats (server-admin opt-out via `plugins/bStats/config.yml`).

</div>

---

## Why operators pick LeafRTP

<div align="center">

**19.8 TP/s sustained at 4 ms worst-case tick. Next-best plugin: 70 ms.**

</div>

**If `/rtp` is the top entry in your timings report, this is the fix.** LeafRTP is the fastest free Random Teleport plugin for Bukkit-native Minecraft servers (Paper, Bukkit, Arclight, Mohist, and other Bukkit-family forks), with Fabric supported as a first-class platform:

- **No lag spikes when players spam `/rtp`.** Worst-case main-thread tick stays at 4 ms (vs. 70-771 ms for the next plugins) - your TPS holds at 20.00 during a teleport burst.
- **Instant teleports, no "Finding a safe location..." wait.** Pre-verified location queue serves `/rtp` in one tick instead of loading chunks on demand.
- **Works on plain Bukkit servers at Paper-class speed.** Off-tick `.mca` Anvil pre-filter, worst tick stays at 3 ms while competitors spike past 3 seconds.
- **Clickable `/rtp menu` GUI world & region selection** - players pick worlds and regions from an interactive book menu (Paper / Folia; chat-paginated elsewhere), no commands to memorize.
- **Eight claim-plugin integrations bundled** (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect) - no add-ons to install.
- **Per-player cooldowns & usage limits** - per-permission cooldown and limit nodes out of the box, so `/rtp` spam is capped without an extra plugin.
- **Audited safety**: no unsafe blocks, no force-loaded chunks, no claim-bypassing teleports, no silent failures.

On **Paper 1.21**, measured on the in-repo benchmark harness, two clients spamming `/rtp` back-to-back:

| Plugin           | TP/s     | Worst tick (MSPT p99) | CPU per teleport |
|------------------|----------|-----------------------|------------------|
| **LeafRTP**          | **19.8** | **4 ms**              | **16.9 ms**      |
| JakesRTP         | 20.0     | 70 ms                 | 26.0 ms          |
| BetterRTP        | 7.1      | 771 ms                | 53.6 ms          |
| HuskHomes RTP    | 6.2      | 335 ms                | 52.2 ms          |

*Methodology: Paper 1.21, 2 OPed clients spamming `/rtp` continuously, in-repo harness linked below. Spigot and Folia results in the full benchmark section.*

Same throughput as the next-best plugin, **~17x lower worst-case tick spike, 35% less CPU per teleport.** On Spigot, LeafRTP's worst tick stays at 3 ms while competitors spike past 3 seconds. Reproduce on your own rig: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP).

**And free doesn't mean bare-bones.** The same off-tick architecture that keeps your TPS steady also runs the clickable `/rtp menu`, eight bundled claim integrations, the full effects engine, live map heatmaps, and built-in `/rtp info` diagnostics - speed is what makes the feature set affordable, not a trade against it.

Each capability below is paired with the part of that architecture that makes it cheap to run - the feature set and the performance are the same system, not opposite ends of a dial:

| Feature you get | What makes it cost the server nothing |
|-----------------|---------------------------------------|
| **Clickable `/rtp menu`** - book UI (Paper / Folia) or chat-paginated menu | Menu rendering runs off the main thread, so opening it never touches your MSPT. |
| **Lifecycle effects engine** - particles, sounds, fireworks, potions, titles | Effect math and packet work run async, so they never cost you TPS. |
| **8 bundled claim integrations** - GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect | Claim checks reroll inside the async pipeline, not on the tick that teleports the player. |
| **Live map heatmaps** (`/rtp scan`) - green/red safety painted on a real held map | The `.mca` Anvil pre-filter and chunk-load passes run off-tick and throttled, so a scan runs on a live server without tanking TPS. |
| **Built-in `/rtp info` diagnostics** - TPS/MSPT, heap, latency percentiles, rejection breakdown | Metrics are sampled without blocking a tick, so the dashboard reads pre-collected numbers at no hot-path cost. |
| **Instant teleports** - no "Finding a safe location..." wait | A pre-warmed, pre-verified location queue serves `/rtp` in one tick instead of loading chunks on demand. |

<details>
<summary><b>Verification & sources</b></summary>

Every number on this page is anchored in the repo:

- **Reproducible benchmarks** with raw CSVs and per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP)
- **Requirements traced to tests** - every requirement, including the safety prohibitions (no unsafe-block landings, no force-loaded chunks, no claim-bypassing teleports, no main-thread chunk loading, no silently swallowed failures), has an implementing class and regression test: [`TRACEABILITY.md`](https://github.com/dailystruggle/RTP/blob/V3/docs/dev/TRACEABILITY.md)
- **28+ ADRs**, dated and numbered, covering platform-in-scope decisions, the Anvil subsystem, the Brigadier bridge, supersession trails: [`docs/adr/`](https://github.com/dailystruggle/RTP/tree/V3/docs/adr)
- **bStats** enabled - anonymous usage stats help prioritize platform work.

</details>

---

## See the engine work: real-time visual diagnostics

Most random-teleport plugins are a black box - you find out a region is full of unsafe ground only when players start complaining. LeafRTP turns that into something you can *watch*.

Run `/rtp scan` on a region and hold the map: LeafRTP paints each sector green (safe) or red (unsafe) **as it verifies it**, live, on a real vanilla map item - while the chat readout streams `cps`, land %, and an **ETA** so you know exactly how long a pre-warm will take.

<!-- TODO(asset): replace with a short looping clip/GIF of the scan painting the held map; source video below. -->
[**Watch the scan paint a region live (video)**](https://youtu.be/Ftjy1zw_S04)

- **Two passes, and the heavy one runs off-tick.** The first pass reads `.mca` Anvil region files on a background pool - it never touches the main thread (S-005) and throws out most of a region (ocean, wrong biome, obviously unsafe) before any chunk is loaded. Only the survivors reach the second pass, a throttled live chunk-load verification; because the Anvil pre-filter has already discarded the bulk of the region, that live pass is a small fraction of what a naive pre-generator would load, and it is paced so a scan can run on a live server without tanking TPS.
- **It is a diagnostic, not a chore.** The red/green map and the rejection breakdown in `/rtp info` tell you *why* a region rejects coordinates (bad biome, unsafe block, claim), so you can tune `safety.yml` against evidence instead of guesswork.
- **Free, and cross-platform.** The held-map heatmap ships in the free build on Paper, Spigot, Fabric, and NeoForge - not a static PNG you export to disk and open over FTP.

---

## Runtime observability built in (`/rtp info`)

No metrics add-on, no external dashboard. `/rtp info` reports the numbers an operator actually watches, sampled without blocking a tick:

- **TPS (1m / 5m / 15m) and live server MSPT** with tick-budget utilisation.
- **JVM heap** used / max, and current chunk-ticket leak rate.
- **Pipeline latency percentiles** (P50 / P75 / P90 / P95 / P99) and slow-teleport count.
- **Generation success / failure rate** with the top coordinate-rejection cause and a full breakdown by cause.
- **Queue depth and growth warnings**, plus database round-trip latency.

<!-- TODO(asset): replace with a screenshot of /rtp info (TPS + MSPT + heap block). -->

This is the part competitors describe as "enterprise-grade" in their listing copy. Here it is a command you can run, with every field traced to a requirement in [`TRACEABILITY.md`](https://github.com/dailystruggle/RTP/blob/V3/docs/dev/TRACEABILITY.md).

---

## Requirements

A few hard requirements. If any are a **no**, EssentialsX `/rtp` or HuskHomes are fine free alternatives.

- ✅ **Java 21+** on your host (REQ-RTP-SYS-001, non-negotiable).
- ✅ **Paper, Spigot, or a Bukkit-family fork** (Arclight / Mohist supported for Forge bridges). Fabric and NeoForge (1.21.x / 26.1.x) are supported and regularly tested, with their featureset lagging the Bukkit family by a release or two.
- ✅ **In-game editing or YAML, your call.** Browse and tune config from the clickable `/rtp menu` (book on Paper / Folia, chat-paginated elsewhere), or edit the plain YAML files directly and version-control them.
- ❌ Folia, proxy/cross-server, SQL/Redis -> those live in **LeafRTP-Pro**. (Vault economy works in the free build.)

---

## Install (30 seconds)

1. Drop `LeafRTP-x.y.z.jar` into `plugins/`.
2. Start the server. A `default` region is generated for you.
3. Type **/rtp**.
4. Run **/rtp menu** to browse regions, worlds, and configuration in-game (clickable book on Paper / Folia, chat-paginated fallback elsewhere).

Tune `plugins/RTP/config.yml` and `plugins/RTP/regions/*.yml` later - via `/rtp menu` or by editing the YAML directly. Full admin guide is auto-unpacked into `plugins/RTP/docs/` on first run, and lives online at the [**admin guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_SERVER_ADMINS.md).

---

## Core capabilities

- **Deterministic spiral selection** - bounded math, no unbounded re-roll loops. Predictable on huge worlds.
- **Spatial memory that persists across restarts** - LeafRTP *learns* which sectors keep failing and stops trying them.
- Most `/rtp` calls serve instantly from a **pre-generated location queue** - destinations are chunk-loaded and verified before you type the command.
- **Safety pipeline** - radius check, invulnerability timer, optional landing platform, movement / damage cancel timers, material allow/deny list.
- **Optional PvP / combat-tag gate** - off by default; when enabled, `/rtp` is refused or delayed for players who recently dealt or took PvP damage, so a teleport can't be used to escape a fight. Native combat tracking, with optional PvPManager / CombatLogX / Simple Combat Log integration.
- **Per-region arrival schematics** - drop a `.schem` named after a region into `plugins/RTP/schematics/` and every teleport into that region pastes it (a lobby pad, arrival shrine, or custom platform) on the landing spot. Cross-platform Sponge `.schem`, decoded in-house (no WorldEdit required), claim-aware and audited.
- **Per-region, per-world config** - shapes (square / circle / rectangle), curve weighting, vertical adjustors, sky-light check, world overrides, hot-reloadable YAML.
- **Effects on every lifecycle phase** - particles, sounds, fireworks, potion, note effects via the in-tree `effects-api`, gated by `rtp.effects.<name>` permissions.
- **Eight claim-plugin integrations bundled** - GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect.
- Iris, Terra, and custom datapack generators work out of the box, with modded biome IDs preserved. **PlaceholderAPI** and **ProtocolLib** are supported as optional soft-deps.
- **Optional Vault economy** - charge players per `/rtp` with per-region price, `priceOther`, params/biome surcharges, and a balance floor (`economy.yml`); dormant when Vault is not installed.
- **Public `rtp-api`** - same surface as Pro. Trigger LeafRTP from a GUI, NPC, or quest; build your own UX without forking.
- **Live map / heatmap visualizations** - `/rtp scan` paints region safety state (green safe / red unsafe) onto a real held map item while the chat readout streams cps, land %, and ETA; see *Real-Time Visual Diagnostics* above. Free on Paper, Spigot, Fabric, and NeoForge.
- **Built-in runtime observability** - `/rtp info` reports TPS/MSPT, JVM heap, pipeline latency percentiles, and generation success/failure breakdown without a metrics add-on; see *Runtime observability* above.
- **12 locales, parity-enforced** - shipped locales are CI-verified (`LocaleParityTest`) so a translated server never silently falls back to English on a new key.
- **Unusually thorough engineering docs** - 28+ dated ADRs and a requirements-to-tests trace, public: [`docs/dev/INDEX.md`](https://github.com/dailystruggle/RTP/blob/V3/docs/dev/INDEX.md).

<details>
<summary><b>Already built in - the things operators usually bolt on with extra plugins</b></summary>

A lot of what people install companion plugins for is already in the free engine, under LeafRTP's own vocabulary. Check here before adding an add-on:

| Capability | How LeafRTP already covers it |
|---|---|
| Secure in-game menu (no chest-GUI exploits) | `/rtp menu` is a *read-only book* UI: clickable destinations and config with the same permission checks as a typed command, and **zero** inventory-dupe / click-exploit surface. The book is the deliberate, safer design - not a missing chest GUI. |
| Runtime region authoring (no config-file round-trip) | Ephemeral per-invocation overrides via `rtp.params` (`centerx=`/`centerz=`/`radius=`), or persistent edits via `/rtp config regions <name> centerX=...`. A named region is a full target - center + shape + radius + queue + permissions - not just a saved coordinate. |
| World overrides | Redirect a `/rtp` issued in the Nether or End to a safe world automatically via the `worlds.yml` `override` key - no teleport loops. |
| Open claim-integration API | Register any claim/region/biome check through `GlobalRegionVerifiers` / `RegionVerifierRegistry` - open, async, platform-neutral. Eight claim plugins are already bundled through it; addons add their own with one lambda. |
| First-join random teleport | Distribute new players across the map on login, served instantly from the pre-generated queue. |
| Built-in operator diagnostics in `/rtp info` | Live queue depth and growth, pipeline latency percentiles, chunk-ticket leak rate, TPS/MSPT, plus generation success/failure rate and the top coordinate-rejection cause (biome, unsafe block, claim, ...). No separate metrics add-on. |
| Command-block & console ready | The unified command framework parses player, console, and command-block callers with equal safety - drive `/rtp` from redstone, datapacks, or scripts. |
| Self-scheduling API tasks | `RTPRunnable` routes work onto the correct region / async thread automatically via `schedule()` - addon authors get correct scheduling for free. |
| Live config reload (no restart) | Retune regions/safety/effects without a restart: `/rtp reload` (all) or `/rtp reload <file>` (one file); `/rtp config <file> set k=v` saves and reloads automatically. |

*(Folia, multi-server / proxy, and SQL/Redis live in **LeafRTP-Pro** - not engine gaps.)*

</details>

---

## Platform support

| Platform                                                     | Status                  | Notes                                                                                       |
|-------------------------------------------------------------|-------------------------|---------------------------------------------------------------------------------------------|
| **Paper** (+ forks: Purpur, Pufferfish, Leaf, Leaves, DivineMC) | ✅ Recommended          | Fully async via native `getChunkAtAsync`.                                                    |
| **Spigot** (+ Spigot forks)                                  | ✅ Supported            | Off-tick `.mca` Anvil pre-filter -> Paper-class throughput on plain Spigot.                 |
| **Arclight / Mohist** (Forge bridges)                        | ✅ Officially supported | Use the Spigot/Paper jar. Recommended way to run on Forge.                                  |
| **Folia**                                                   | ❌ Not in this build    | Folia adapter ships in **LeafRTP-Pro**.                                                         |
| **Multi-server / proxy** (Velocity)                         | ❌ Not in this build    | Cross-server queue ships in **LeafRTP-Pro**.                                                    |
| **Fabric**                                                  | ✅ Supported            | First-class, stable, in-scope platform; tested regularly, at feature parity with the Bukkit family. |
| **Native NeoForge**                                         | ✅ Supported            | First-class adapter on Minecraft 1.21.x / 26.1.x.                                           |
| **Native Forge**                                            | 🔁 Use Arclight / Mohist | No native adapter planned.                                                                  |

---

<details>
<summary><b>Full benchmark vs. alternative random teleport plugins on Paper, Spigot, and Folia</b></summary>

*2 OPed real clients spamming `/rtp` back-to-back, queues enabled where the plugin offers them, cooldowns/delays zeroed. LeafRTP and LeafRTP-Pro share the same engine on Spigot/Paper; only the Folia adapter differs.*

**Confidence legend:** 🧪 = measured locally · 📖 = read from plugin docs · ❓ = inferred from architecture.

**Metrics:** Throughput (TP/s, higher better) · MSPT p99 (worst 1-in-100 main-thread tick in ms, lower better) · Min TPS (lowest TPS observed; 20.00 = no hiccup) · CPU / TP (main-thread CPU per successful teleport).

**Paper 1.20.1 / 1.21.11** - the reference dataset. Eight plugins, same harness, same world, same two OPed clients.

| Plugin                  | TP/s     | MSPT p99 (ms) | Min TPS   | CPU / TP (ms) | Success    |
|-------------------------|----------|---------------|-----------|----------------|------------|
| **🧪 LeafRTP**              | **19.8** | **4**         | **20.00** | **16.9**       | **100 %**  |
| 🧪 JakesRTP             | 20.0     | 70            | 20.00     | 26.0           | 100 %      |
| 🧪 BetterRTP            | 7.3      | 852           | 20.00     | 53.6           | 100 %      |
| 🧪 HuskHomes            | 6.2      | 372           | 20.00     | 52.2           | 100 %      |
| 🧪 AdvancedRTP          | 2.16     | 2 100         | 19.95     | 92.1           | 96.3 %     |
| 🧪 EzRTP                | 1.76     | 2 903         | 19.95     | 139.6          | 100 %      |
| 🧪 AsyRTP               | 1.67     | 4 534         | 19.95     | 38.8           | 100 %      |
| 🧪 EssentialsX `/tpr`   | 0.96     | 4 504         | 19.95     | 88.9           | 75.9 % §   |

§ EssentialsX `/tpr` is a teleport-*request* command (handshake + accept), not a teleport-*do* command; the harness's 5 s per-attempt deadline times out a fraction of the request-accept latencies. Numbers are dispatch-shaped, not plugin-broken.

**Spigot 1.20.1** - Spigot's platform-wide chunk-gen ceiling caps everyone in the 1-1.5 TP/s range during the burst; the latency tail is what matters.

| Plugin       | TP/s     | MSPT p99 (ms) | Min TPS  | CPU / TP (ms) |
|--------------|----------|---------------|----------|----------------|
| **🧪 LeafRTP**   | **1.52** | **3**         | **6.4**  | 572            |
| 🧪 JakesRTP  | 1.04     | 2 252         | 7.5*     | --*            |
| 🧪 BetterRTP | 1.33     | 3 790         | 2.18     | 584            |
| 🧪 HuskHomes | 0.93     | 4 939         | 2.59     | 868            |

**Folia 1.21.11** - *LeafRTP-Pro only; the Folia adapter is not bundled in the free build.*

| Plugin         | TP/s     | Region MSPT p99 (ms) | Success     | CPU / TP (ms) |
|----------------|----------|----------------------|-------------|----------------|
| **🧪 LeafRTP-Pro** | **9.87** | **157**              | **99.97 %** | **18.0**       |
| 🧪 BetterRTP   | 3.82     | 1 200                | 100 %       | 34.8           |
| 🧪 HuskHomes   | 3.32     | 901                  | 100 %       | 28.4           |

**Architecture support matrix** - *(Spigot / Paper-and-forks / Folia)*

- **LeafRTP** - ✅🧪 Off-tick Anvil pre-filter · ✅🧪 Fully async via `getChunkAtAsync` · ❌ See LeafRTP-Pro
- **LeafRTP-Pro** - ✅🧪 Same as base · ✅🧪 Same as base · ✅🧪 Region Scheduler + off-tick pre-filter, no 1-tick stalls
- **BetterRTP** - ⚠️📖 Sync chunk load on miss · ⚠️📖 No off-tick safety pre-filter · ✅🧪 Folia 1.21.11 functional, p99 ~1.2 s
- **EzRTP** - ❌🧪 `NoSuchMethodError` on Spigot 1.20.1 (Paper-only API) · ✅📖 Works on Paper · ❓📖 Not advertised
- **AsyRTP** - ❌🧪 Fails to enable on Spigot 1.20.1 (Paper-only API in `onEnable`) · ✅📖 Paper · ✅📖 Folia
- **SorekillRTP** - ⚠️❓ Designed for Redis cross-server, not single-server perf · ⚠️❓ Same · ❓📖
- **AdvancedRTP** - ⚠️📖 Safety-first, sync chunk load · ⚠️📖 Same · ❌📖
- **JakesRTP** - ⚠️📖 Async via flag; 10-slot cache · ✅📖 Same · ❌📖 No Folia
- **EssentialsX /rtp** - ✅📖 Main-thread chunk load per candidate · ✅📖 Same · ❌📖
- **HuskHomes RTP** - ✅📖 Bundled with homes suite · ⚠️📖 Same · ✅🧪 Folia functional, p99 ~900 ms

**Caveats.** 2 clients only (the number is a floor, not a ceiling); hardware, view distance, world state, and other plugins will move them. Paper LeafRTP row reproduced n=2; other rows are n=1 on a single rig. Competitor plugins update frequently - corrections welcome via GitHub issue with a contradicting repro or doc link. Feature breadth, GUI, and claim-integration counts are not benchmarked; several competitors trade speed for those, which is a legitimate design choice.

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

**Q: Why is it called "LeafRTP" now instead of just "RTP"?**
A: "RTP" is the generic term for random teleport, so the old name was nearly impossible to find - it collided with every other random-teleport plugin, command, and forum thread in search and marketplace indexes. "LeafRTP" is a distinct, indexable name that points unambiguously at this plugin while keeping the `/rtp` command, `rtp-api`, config paths, and data files exactly as they were. Nothing changes for existing installs - only the public name.

**Q: Does it work with Iris / Terra / custom datapack generators?**
A: Yes. Region files are read directly, so modded and namespaced biome and block IDs are preserved. No configuration needed.

**Q: What's the difference between LeafRTP and LeafRTP-Pro?**
A: Same engine, same source tree. The free build already ships multilingual locale switching and bundled (trimmed) `lang/**` packs. **LeafRTP-Pro** adds the Folia adapter, multi-server / proxy support (Velocity), SQL/Redis backends, the full curated `lang/**` locale set, the richer block-tag/state-predicate `safety.yml` grammar, and priority support. The free build is fully sufficient for single-server deployments. Drop in the Pro jar later - same config, same data, same commands.

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
- **Response time:** no SLA on the free build. Critical safety issues jump the queue regardless. Guaranteed response windows are reserved for **LeafRTP-Pro**.
- **Feature requests** via GitHub issues. Priority follows the published roadmap, not ticket volume.

</details>

<details>
<summary><b>Known limitations</b></summary>

- Free build ships multilingual locale switching and bundled (trimmed) `lang/**` packs, but does not ship Folia, SQL/Redis, multi-server, the full curated `lang/**` locale set, or the tag/state-predicate `safety.yml` grammar - those are in **LeafRTP-Pro**.
- `safety.yml` here accepts flat material names (`LAVA`, `MAGMA_BLOCK`, `CACTUS`, `FIRE`). Unknown materials log one warning, never silently dropped.
- Edits to `safety.yml` and biome filters do not yet invalidate the persisted shape cache - workaround: `/rtp scan reset <region>`.
- Emergency landing platform default is now `platformRadius: -1` (disabled). Set to `0` or higher to restore legacy 2.x behavior.

Live list: [CHANGELOG](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md#known-issues).

</details>


<details>
<summary><b>TODO list</b></summary>

- more book menus
- more charts
- better polygon shape setup
- finish translating
- verify against modded data
- drop 1.20.1 due to performance issues, favor 1.21-26.2 when it comes out
- create support mechanisms for transferring from other plugins
- steady-state and burst usage statistics for admins
- biome and player analytics for world builders

</details>

---

## Need Folia, a proxy network, or paid support?

[**LeafRTP-Pro**](https://builtbybit.com/resources/rtp-pro.105418) is a drop-in upgrade - same configuration, same data files, same commands. It adds:

- **Folia** adapter (Region Scheduler + off-tick pre-filter, no 1-tick stalls)
- **Multi-server / proxy** support (Velocity), validated on the in-repo devstack (2 proxies, 2 lobbies, 2 backends behind shared Redis)
- SQL / Redis shared-state backends (H2, SQLite, MySQL, PostgreSQL, Jedis)
- Full curated `lang/**` locale set (free ships a trimmed set), login-reserve cache, visitor mode
- The richer `safety.yml` grammar: vanilla block tags (`#minecraft:leaves`), state predicates (`OAK_SLAB[waterlogged=true]`), wildcards (`*[waterlogged=true]`)
- Earliest releases on each version + priority support within a documented response window

---

## Links

- [**LeafRTP admin & configuration guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_SERVER_ADMINS.md) - install, configure, command reference
- [**LeafRTP addon / API developer guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_ADDON_DEVELOPERS.md) - `rtp-api` and examples
- [**LeafRTP changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**LeafRTP source on GitHub**](https://github.com/dailystruggle/RTP) - star, watch, contribute, file issues
- [**LeafRTP-Pro (Folia, proxy, SQL/Redis)**](https://builtbybit.com/resources/rtp-pro.105418) - the high-performance paid build of this random teleport engine
