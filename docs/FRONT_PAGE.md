<!--
Markdown mirror of FRONT_PAGE.bbcode (RTP-Pro front page).
Kept in sync with the BBCode source by hand; update both when changing copy.
-->

<div align="center">

# RTP-Pro

### Deterministic, bounded-latency Random Teleport for production Minecraft servers
*For operators who edit YAML, version-control configs, and read profiler output.*
*Folia-native - multi-server / proxy - audited safety - drop-in upgrade from the free RTP build.*

</div>

---

## Why operators buy Pro

<div align="center">

### 19.8 TP/s sustained at 4 ms worst-case tick. Next-best plugin: 70 ms.

</div>

**If `/rtp` is the top entry in your timings report, this is the fix.** RTP-Pro is the polygon-bounded, Folia-native Random Teleport engine for high-load and custom-world Minecraft servers. In plain operator terms:

- **No lag spikes when players spam `/rtp`.** Worst-case main-thread tick stays at 4 ms (vs. 70-771 ms for the next plugins) - your TPS holds at 20.00 during a teleport burst.
- **Instant teleports, no "Finding a safe location..." wait.** Pre-verified location queue serves `/rtp` in one tick instead of loading chunks on demand.
- **The only RTP plugin that runs natively on Folia at double-digit TP/s** (9.87 TP/s @ 99.97 % success) - every competitor stalls regions or fails to load.
- **Automatic performance the moment the jar drops in.** Pre-warmed location queue, persistent spatial memory, off-tick Anvil pre-filter and async chunk loading kick in on first start - no profiling, no tuning, no per-world hand-holding. Same `config.yml`, commands, and claim-plugin integrations (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect) as the free build, so the upgrade itself is risk-free.
- **Audited safety**: no unsafe blocks, no force-loaded chunks, no claim-bypassing teleports, no silent failures (REQ-RTP-S-001..S-007).

On **Paper 1.21**, measured on the in-repo harness, 2 OPed clients spamming `/rtp` back-to-back:

| Plugin           | TP/s     | Worst tick (MSPT p99) | CPU per teleport |
|------------------|----------|-----------------------|------------------|
| **RTP-Pro**      | **19.8** | **4 ms**              | **16.9 ms**      |
| JakesRTP         | 20.0     | 70 ms                 | 26.0 ms          |
| BetterRTP        | 7.1      | 771 ms                | 53.6 ms          |
| HuskHomes RTP    | 6.2      | 335 ms                | 52.2 ms          |

*Methodology: Paper 1.21, 2 OPed clients spamming `/rtp` continuously, in-repo harness linked below. Folia and Spigot results in the full benchmark section.*

Same throughput as the next-best plugin at **~17x lower worst-case tick spike and 35% less CPU per teleport.** On Folia, RTP-Pro is the only plugin in the field that hits double-digit TP/s (9.87 TP/s @ 99.97% success). Raw harness: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP).

---

## What Pro adds over the free RTP build

|                                                                                                                  | Free RTP | **RTP-Pro** |
|------------------------------------------------------------------------------------------------------------------|----------|-------------|
| **Folia** (Region Scheduler + off-tick pre-filter, no 1-tick stalls)                                             | No       | **Yes**     |
| **Multi-server / proxy** (Velocity)                                                                              | No       | **Yes**     |
| **SQL / Redis** shared-state backends (H2, SQLite, MySQL, PostgreSQL, Jedis)                                     | No       | **Yes**     |
| **Vault** economy (charge for `/rtp`)                                                                            | No       | **Yes**     |
| Multilingual `lang/**`, login-reserve cache, visitor mode                                                        | No       | **Yes**     |
| **`safety.yml` token grammar** - vanilla block tags, state predicates, wildcards                                 | No       | **Yes**     |
| Earliest release on each MC version + priority support                                                           | No       | **Yes**     |
| *Spigot + Paper engine, queues, spiral, Anvil pre-filter*                                                        | *Yes*    | *Yes*       |
| *8 claim plugins bundled (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect)* | *Yes*    | *Yes*       |
| *`effects-api`, `rtp-api`, PlaceholderAPI, ProtocolLib*                                                          | *Yes*    | *Yes*       |

Same configuration, same data files, same commands as the free build - **upgrade is drop-in.**

---

## Built for

RTP-Pro is engineered for the servers that push the platform hardest. If any of these describe your setup, it's the right tool:

- **High-concurrency servers** where `/rtp` spam used to spike MSPT and shake TPS.
- **Folia deployments** that need region-aware scheduling and zero foreign-region API hops in the teleport hot path.
- **Multi-server networks (Velocity)** that need cross-network RTP with reservation tokens and shared state. Validated on the in-repo devstack: **2 Velocity proxies, 2 lobby servers, 2 backend servers** behind a shared Redis transport.
- **Large or pregenerated worlds** where chunk-load cost dominates - the pre-validated queue and Anvil pre-filter pay for themselves on the first burst.
- **Custom-generator worlds (Iris, Terra, datapacks)** where the Anvil-first biome read keeps your safety config authoritative across MC upgrades.
- **Operators who edit YAML, version-control configs, and read profiler output.** In-game `/rtp menu` is there when you want it; nothing is hidden behind it.

**Platform requirements:** Java 21+, on Paper / Folia / Spigot / Fabric (or Arclight / Mohist for Forge / NeoForge).

---

<details>
<summary><b>Full benchmark - Paper, Spigot, Folia head-to-head</b></summary>

**Confidence:** measured; from plugin docs; inferred from architecture.

**Metrics:** Throughput (TP/s, higher better) - MSPT p99 (worst 1-in-100 main-thread tick in ms; <~50 ms = no dropped tick) - Min TPS (20.00 = no hiccup) - CPU / TP (main-thread CPU per successful teleport).

**Paper 1.20.1 / 1.21.11** - recommended platform. Eight plugins, same harness, same world, same two OPed clients.

| Plugin                | TP/s     | MSPT p99 (ms) | Min TPS   | CPU / TP (ms) | Success    |
|-----------------------|----------|---------------|-----------|----------------|------------|
| **RTP-Pro**           | **19.8** | **4**         | **20.00** | **16.9**       | **100 %**  |
| JakesRTP              | 20.0     | 70            | 20.00     | 26.0           | 100 %      |
| BetterRTP             | 7.3      | 852           | 20.00     | 53.6           | 100 %      |
| HuskHomes             | 6.2      | 372           | 20.00     | 52.2           | 100 %      |
| AdvancedRTP           | 2.16     | 2 100         | 19.95     | 92.1           | 96.3 %     |
| EzRTP                 | 1.76     | 2 903         | 19.95     | 139.6          | 100 %      |
| AsyRTP                | 1.67     | 4 534         | 19.95     | 38.8           | 100 %      |
| EssentialsX `/tpr`    | 0.96     | 4 504         | 19.95     | 88.9           | 75.9 % §   |

§ EssentialsX `/tpr` is a teleport-*request* command (handshake + accept), not a teleport-*do* command; the harness's 5 s per-attempt deadline times out a fraction of the request-accept latencies. Numbers are dispatch-shaped, not plugin-broken.

**Spigot 1.20.1** - platform-wide chunk-gen ceiling caps everyone at 1-1.5 TP/s; the latency tail is what matters.

| Plugin       | TP/s     | MSPT p99 (ms) | Min TPS | CPU / TP (ms) |
|--------------|----------|---------------|---------|----------------|
| **RTP-Pro**  | **1.52** | **3**         | **6.4** | 572            |
| JakesRTP     | 1.04     | 2 252         | --      | --             |
| BetterRTP    | 1.33     | 3 790         | 2.18    | 584            |
| HuskHomes    | 0.93     | 4 939         | 2.59    | 868            |

**Folia 1.21.11** - Region MSPT = per-region tick (a bad region doesn't stall the server).

| Plugin       | TP/s     | Region MSPT p99 (ms) | Success     | CPU / TP (ms) |
|--------------|----------|----------------------|-------------|----------------|
| **RTP-Pro**  | **9.87** | **157**              | **99.97 %** | **18.0**       |
| BetterRTP    | 3.82     | 1 200                | 100 %       | 34.8           |
| HuskHomes    | 3.32     | 901                  | 100 %       | 28.4           |

**Architecture support matrix:**

| Plugin              | Spigot                                        | Paper (+ forks)                  | Folia                                       |
|---------------------|-----------------------------------------------|----------------------------------|---------------------------------------------|
| **RTP-Pro**         | Off-tick Anvil pre-filter; rare bounded fallback | Fully async via `getChunkAtAsync` | Region Scheduler + off-tick pre-filter      |
| BetterRTP           | Sync chunk load on miss                       | Paper async; no safety pre-filter | p99 ~1.2 s                                  |
| EzRTP               | Paper-only API crashes on Spigot 1.20.1       | N/A                              | N/A                                         |
| AsyRTP              | Fails to enable on Spigot 1.20.1              | N/A                              | N/A                                         |
| SorekillRTP         | Redis-cross-server focus                      | Same                             | N/A                                         |
| AdvancedRTP         | Safety-first, sync chunk load                 | Same                             | N/A                                         |
| JakesRTP            | Async via flag; 10-slot cache                 | Widely deployed                  | N/A                                         |
| EssentialsX `/rtp`  | Main-thread chunk load                        | Same                             | N/A                                         |
| HuskHomes RTP       | Supported                                     | Same                             | p99 ~900 ms                                 |

**Memory & TPS:** queue bounded (bounded by `cacheCap`); each entry is a small POJO. Server TPS held at **20.00** across every Paper and Folia run. On Spigot, every plugin saturates to the same chunk-gen ceiling; RTP-Pro just spends those ticks doing fewer things.

- Paper RTP-Pro row reproduced n=2; other rows are n=1 on a single rig. - JakesRTP Spigot row ran in slot 4 of a 4-phase chain - Min TPS / CPU-per-TP confounded by carry-over; dispatch-time numbers (throughput, p99) remain valid.

**Caveats.** 2 clients only (the number is a floor, not a ceiling). Hardware, view distance, world state, and other plugins will move the numbers. Competitor plugins update frequently; corrections welcome via GitHub issue with a contradicting repro or doc link. Feature breadth, GUI, and claim-integration count are not benchmarked - several competitors trade speed for those, which is a legitimate choice.

Full methodology, raw CSVs, per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP).

</details>

<details>
<summary><b>Architecture - five pillars + chunk-loading by platform</b></summary>

**Five pillars:**

- **Deterministic spiral selection.** Bounded math, uniform player distribution even on massive worlds; no unbounded re-roll loops.
- **Platform-native performance.** Spigot, Paper, and Folia each get the threading model that fits them - not the lowest-common-denominator.
- **Active resource watchdog.** Background sweep reclaims chunk tickets from abandoned teleports via WeakReferences; nothing stays force-loaded past its reservation window.
- **Direct region I/O pre-filter.** `.mca` region files parsed off-tick on a background pool, including biomes (custom namespaced biomes like Iris included). Paper-grade safety evaluation on Spigot; skips per-candidate Region-Thread hops on Folia.
- **Admin-driven background mapping.** `/rtp scan start|pause|resume|reset|cancel` walks the spiral during idle periods so spatial memory accumulates without player traffic. Fully-automatic self-warming is on the roadmap for `3.0.0` final.

**Chunk loading, by platform:**

- **Paper** - `World#getChunkAtAsync` used directly. No pre-filter, no main-thread fallback. Reference platform.
- **Paper forks** (Leaf, Leaves, Purpur, Pufferfish, Airplane, DivineMC, ...) - inherit the Paper code path.
- **Folia** - Anvil read-only pre-filter on `ForkJoinPool.commonPool()` *before* the Region Scheduler; rejected candidates never hop a thread. Confirmed candidates load through Folia's native async API; teleports dispatch through the Entity Scheduler.
- **Spigot** - `.mca` region files parsed off-tick (`isAir`, `isSafe`, surface-height, sky-light, biome). Paper-class throughput on plain Spigot.
- **Mohist / Arclight** - officially supported. Spigot code path applies.
- **Fabric** - in-tree adapter, supported and regularly tested; featureset lags the Bukkit family by a release or two. Loom-remapped obf/unobf carriers cover 1.20.x, 1.21.x, and MC 26.x runtimes.

**Honest fallback caveats.** The Anvil pre-filter is a data source, not a universal gate. It falls through to the platform's native chunk API in two cases: (1) the chunk is already loaded (live data wins), or (2) the probe returns *unknown* (no region file, unsupported data version, decode error, un-populated). On Spigot the fallback is one on-tick `getChunkAt`. On Folia it's one Region-Scheduler hop. Custom generators (Iris, Terra, datapacks) do **not** trigger the fallback - populated `.mca` palettes are read directly, preserving modded and namespaced IDs that the Bukkit enum would collapse.

**Upgrade-drift-proof biome filtering.** When you bump your Paper or Folia server across MC versions, Mojang's seed-based biome assignment can change for already-written coordinates. The Anvil-first biome read keeps the `.mca` palette authoritative for populated chunks - the biome a player lands in is the biome your `biomes:` allow-list was written against. No re-pregen, no re-tuning after an upgrade.

**Operator takeaway:** Paper and Folia run chunk I/O fully off-tick. On vanilla Spigot the Anvil pre-filter covers the common case; rare main-thread fallback is bounded by your configured tick-budget; no platform silently blocks the tick loop without reporting it.

</details>

<details>
<summary><b>Spatial memory - why the longer it runs, the faster it gets</b></summary>

Without spatial indexing, teleportation is a guessing game and the numbers are ugly. A typical Overworld is only **~45% safely teleportable** (oceans, rivers, ravines, steep terrain eat the rest); the Nether is dominated by lava seas and wall-to-wall stone; the End is *almost entirely* void. Standard RTPs pay full chunk-load cost to rediscover this one candidate at a time, forever.

RTP-Pro plots your world's geometry as it evaluates candidates. When it hits a massive ocean or unsafe biome, it remembers that sector and shrinks the searchable area, so known bad sectors never get loaded again. The longer it runs, the faster it gets - and `/rtp scan` keeps the learning going during idle periods.

**Persistence across restarts:** learned bad-sector state survives a JVM bounce - `MemoryShape.save` / `load` writes per-region spatial memory to disk on shutdown and reloads on startup, alongside the configuration cache (compiled safety sets, region geometry). H2, SQLite, MySQL, and PostgreSQL backends support shared multi-server state.

*Local-profile estimate from a vanilla 1.21 seed set; Nether and End are still qualitative. Anonymous opt-in telemetry is on the roadmap to source this number properly.*

</details>

<details>
<summary><b>safety.yml token grammar - Pro exclusive</b></summary>

`safety.yml` has a first-class token grammar. Five shapes can be mixed freely in `unsafeBlocks` and `airBlocks`:

- **Plain material:** `LAVA`, `MAGMA_BLOCK`.
- **Material + state predicate:** `OAK_SLAB[waterlogged=true]`. Multiple predicates AND together: `OAK_SLAB[waterlogged=true,type=top]`.
- **Vanilla block tag:** `#minecraft:leaves`, `#minecraft:fire`, `#minecraft:campfires`. Live-registry expansion at config-load lands in `3.0.0` final - use explicit names alongside tag tokens during the beta.
- **Tag + state predicate:** `#minecraft:slabs[waterlogged=true]` - "any slab, but only when waterlogged".
- **Wildcard + state predicate:** `*[waterlogged=true]` - "any block, matched when waterlogged". One line replaces the entire waterloggable enumeration.

**Properties:**

- **Fail-open on unknown tags/properties** - missing tags on older MC versions silently reduce coverage rather than breaking startup. Configs stay portable.
- **Never silent on malformed tokens** - typos like `OAK_SLAB[waterlogged=true` surface as `[WARNING] [safety.yml] rejected token '<token>': <reason>`.
- **Zero hot-path cost when unused** - block-state extraction only fires when a token actually needs it; plain-material configs pay nothing.
- **Platform-portable tag resolution** - Bukkit reads from `Bukkit.getTag`; the standalone `rtp-tags` module parses tag JSON directly from data packs and jars for future non-Bukkit platforms.

*Default `safety.yml` is ~25% shorter: `FIRE`+`SOUL_FIRE` -> `#minecraft:fire`; every waterloggable block -> `*[waterlogged=true]`; dozens of decorative plants -> `#minecraft:flowers`, `#minecraft:saplings`, `#minecraft:crops`, etc.*

</details>

<details>
<summary><b>Full feature suite & integrations</b></summary>

**Engine:**

- Any number of teleport regions per world; per-region shape (Square, Circle, Rectangle), radius, center, curve weighting, vertical bounds, world override, permission gates.
- Vertical adjustors (Linear, Jump) for sky islands, void worlds, Nether ceilings.
- Multi-dimensional (Overworld, Nether, End, custom).
- Hot-reloadable YAML; clickable `/rtp menu` (book on Paper / Folia, chat-paginated fallback elsewhere) hardened in 3.0.0-beta.3, with `/rtp config <file> view` as a per-file deep-link.
- Fully async chunk loading on Paper / Folia; off-tick Anvil pre-filter on Spigot.
- **Per-player isolated queues** alongside a global queue - one player's bad luck never starves another's teleport.
- Administrative scan lifecycle (`start`/`pause`/`resume`/`reset`/`cancel`) to pre-populate spatial memory without teleporting players.
- Persistent learned state via H2 / SQLite / MySQL / PostgreSQL.
- Per-tick time budgets and (on Folia) per-tick task-count caps on the region-bound pipe.

**Player polish (UX):**

- Configurable countdown/warmup messages during pre-teleport.
- Particles, sounds, fireworks, potions, note-block effects via `effects-api`, attached to lifecycle phases, gated by `rtp.effects.<name>` permissions.
- Movement-cancel, damage-cancel, invulnerability-after-teleport timers.
- Optional landing platform with configurable material and decay timer.
- PlaceholderAPI: queue depth (total/public/personal), last-teleport coordinates, player status.
- Public `rtp-api` (same surface as Free) - trigger RTP from GUI, NPC, quest reward; build your own UX without forking.

**Bundled claim integrations** (no extra download, folded in per ADR-019): Factions, GriefDefender, GriefPrevention, HuskTowns, Lands, RedProtect, TownyAdvanced, WorldGuard.

**Pro-only configuration:** Vault per-region pricing, complete localization (`lang/**`), login-reserve cache, visitor mode.

</details>

<details>
<summary><b>Already built in - the things operators usually bolt on with extra plugins</b></summary>

RTP-Pro ships, in the core engine, a long list of capabilities that competing setups reach for add-ons to cover. Before installing a companion plugin, check here - it is probably already in the box, under RTP's own vocabulary:

| Capability | How RTP already covers it |
|---|---|
| Secure in-game menu (no chest-GUI exploits) | `/rtp menu` and `/rtp admin` are a *read-only book* UI: clickable destinations and admin actions with the same permission checks as a typed command, **zero** inventory-dupe / click-exploit surface, and no Folia entity-thread state. The book is the deliberate, safer design - not a missing chest GUI. |
| Runtime region authoring (no config-file round-trip) | Ephemeral per-invocation overrides via `rtp.params` (`centerx=`/`centerz=`/`radius=`), or persistent edits via `/rtp config regions <name> centerX=...`. A named region is a full target - center + shape + radius + queue + permissions + price - not just a saved coordinate. |
| World overrides | Redirect a `/rtp` issued in the Nether or End to a safe world automatically via the `worlds.yml` `override` key - no teleport loops. |
| Open claim-integration API | Register any claim/region/biome check through `GlobalRegionVerifiers` / `RegionVerifierRegistry` - open, async, platform-neutral. Eight claim plugins are already bundled through it; addons add their own with one lambda. |
| First-join random teleport | Distribute new players across the map on login, served instantly from the pre-generated queue. |
| Built-in operator diagnostics in `/rtp info` | Live queue depth and growth, pipeline latency percentiles, chunk-ticket leak rate, TPS/MSPT, database latency, a per-region Folia table, plus generation success/failure rate and the top coordinate-rejection cause (biome, unsafe block, claim, ...). No separate metrics add-on. |
| Command-block & console ready | The unified command framework parses player, console, and command-block callers with equal safety - drive `/rtp` from redstone, datapacks, or scripts. |
| Self-scheduling API tasks | `RTPRunnable` routes work onto the correct entity / region / async thread automatically via `schedule()` - addon authors get Folia-correct scheduling for free. |
| Live config reload (no restart) | Retune regions/safety/effects without a restart: `/rtp reload` (all) or `/rtp reload <file>` (one file); `/rtp config <file> set k=v` saves and reloads automatically. |

</details>

<details>
<summary><b>Roadmap - active development</b></summary>

What's on deck, with direction:

- **Spigot main-thread fallback diagnostics** - per-minute fallback counters and an optional strict mode that skips the fallback entirely.
- **Folia scheduler-hop telemetry** - publish p50/p95 for un-pre-resolvable candidates and amortise across adjacent candidates where possible.
- **Un-populated chunk attribution bucket** - distinguish first-touch live loads from other fallbacks in the reports.
- **Fully-automatic self-warming** - background spatial-memory accumulation without the `/rtp scan` verb.
- **Anonymous opt-in telemetry** - reference benchmark sourced from real deployments.

File a GitHub issue if you hit something not on the list.

</details>

<details>
<summary><b>FAQ</b></summary>

**Q: Why does this respond faster than other RTP plugins?**
A: Pre-warmed queue. In most cases a verified destination is ready before you type `/rtp`. Two design choices keep that queue cheap to refill: a **persistent spatial memory** per region (the plugin remembers which sectors failed safety checks, so the spiral selector skips known-bad ground instead of rerolling indefinitely) and an **off-tick async pre-filter** (Anvil region files are read directly to reject unsafe biomes/blocks *before* any chunk is loaded, so candidate verification never blocks the main thread).

**Q: How do I set up teleportation between worlds?**
A: See the admin guide. Resolution order: player's current world (or `world:` param) -> world's target region -> region's target world.

**Q: Iris / Terra / custom datapack generators?**
A: Yes - deliberate 3.0 design goal. The Anvil pre-filter reads `.mca` palette data directly, so populated custom-generator chunks evaluate off-tick like vanilla. That's *strictly more accurate* than the live Bukkit view, which collapses modded IDs to vanilla cousins. Un-populated chunks fall through to a live load as the authoritative safety net.

**Q: Existing `safety.yml` on upgrade?**
A: Plain-material entries keep working unchanged; the new grammar is strictly additive.

**Q: Best setup for performance?**
A: Paper, with memory to spare for the location cache. RTP works particularly well on pre-generated worlds.

**Q: Can I downgrade to the free RTP build?**
A: Yes - same configuration, same data files, same commands. You lose Folia, proxy, SQL/Redis, Vault, multilingual, and the tag/state-predicate grammar.

</details>


<details>
<summary><b>Support</b></summary>

- **Bug reports and configuration questions** are welcome - the admin guide answers most setup questions, and a clear repro (server version, plugin version, platform, relevant configs and log lines) gets a fast resolution.
- **Response time:** 24-72 h on weekdays. Critical safety issues (S-001 through S-007 violations) jump the queue.
- **Feature requests** via GitHub issues. Priority follows the published roadmap.
- **Native Forge / NeoForge** is not currently supported - use Arclight or Mohist with the Spigot/Paper jar.

</details>

---

## Links

- [**Admin guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_SERVER_ADMINS.md) - install, configure, command reference
- [**Addon developer guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_ADDON_DEVELOPERS.md) - API & examples
- [**Changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**Source on GitHub**](https://github.com/dailystruggle/RTP) - star, watch, contribute, file issues

---

> *A deterministic, high-performance teleport engine for serious Minecraft servers. Folia-native concurrency, audited safety invariants, and bounded-latency execution.*
