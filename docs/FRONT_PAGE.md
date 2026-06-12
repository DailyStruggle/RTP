<!--
Markdown mirror of FRONT_PAGE.bbcode (LeafRTP-Pro front page).
Kept in sync with the BBCode source by hand; update both when changing copy.

Marketplace listing metadata (current, for SEO reference):
  Title:   "LeafRTP-Pro"
  Tagline: "Deterministic Random Teleportation engine"
-->

<div align="center">

# LeafRTP-Pro - High-Performance Random Teleport (RTP) Plugin for Folia, Paper & Spigot

*Once upon a time, I wanted to explore a minecraft world. I asked for `/rtp` in servers I played on. They told me "no, that's laggy", and I took that personally.*

</div>

No menu, however polished, can make `/rtp` fast - only the engine behind it can. With most random-teleport plugins, **any** click can make the server load chunk after chunk after chunk: it picks a spot, loads it on the main thread, and - if it turns out unsafe - tries another, and another, with nothing telling it to stop. That lag spike never shows up in the menu. It shows up in your MSPT, in the stutter every other player feels, and eventually in the players who quietly stop logging in.

**LeafRTP-Pro was built on one idea: a teleport should cost the server nothing the player can feel. No spike. No spinner. No quiet churn.**

**Enterprise performance, proven not asserted.** 19.8 TP/s sustained at a 4 ms worst-case main-thread tick on Paper (next-best plugin: 70 ms), with audited safety invariants - no unsafe blocks, no force-loaded chunks, no claim-bypassing teleports, no silent failures. Every number on this page is measured on a public harness, not a marketing claim - and bounded worst-case latency is the one axis a Paper-only, on-tick design cannot follow.

<div align="center">

*Supported: Paper, Folia, Spigot, Fabric, and native NeoForge (1.21.x / 26.1.x) - Minecraft 1.20.x / 1.21.x / 26.x. Legacy Forge is not native: run the Spigot/Paper jar under Arclight / Mohist. Works the moment the jar drops in - no tuning required - and rewards operators who like to dig into YAML and profiler output. Drop-in upgrade from the free LeafRTP build.*

</div>

---

## Why operators buy Pro

<div align="center">

### 19.8 TP/s sustained at 4 ms worst-case tick. Next-best plugin: 70 ms.

</div>

**If `/rtp` is the top entry in your timings report, this is the fix.** LeafRTP-Pro is the polygon-bounded, Folia-native Random Teleport engine for high-load and custom-world Minecraft servers:

- **No lag spikes when players spam `/rtp`.** Worst-case main-thread tick stays at 4 ms (vs. 70-771 ms for the next plugins) - your TPS holds at 20.00 during a teleport burst.
- **Instant teleports, no "Finding a safe location..." wait.** Pre-verified location queue serves `/rtp` in one tick instead of loading chunks on demand.
- **The only LeafRTP plugin that runs natively on Folia without stalling regions.** The engine's per-teleport work stays well under a single region tick; the throughput we measured (9.87 TP/s @ 99.97 % success) was bounded by Region-Scheduler hops in the harness, *not* by computation - so that figure is a floor set by the test rig, not a ceiling on the engine. Every competitor stalls regions or fails to load.
- **Automatic performance the moment the jar drops in.** Pre-warmed location queue, persistent spatial memory, off-tick Anvil pre-filter and async chunk loading kick in on first start - no profiling, no tuning, no per-world hand-holding. Same `config.yml`, commands, and claim-plugin integrations (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect) as the free build, so the upgrade itself is risk-free.
- **Audited safety**: no unsafe blocks, no force-loaded chunks, no claim-bypassing teleports, no silent failures.

On **Paper 1.21**, measured on the in-repo harness, 2 OPed clients spamming `/rtp` back-to-back:

| Plugin           | TP/s     | Worst tick (MSPT p99) | CPU per teleport |
|------------------|----------|-----------------------|------------------|
| **LeafRTP-Pro**      | **19.8** | **4 ms**              | **16.9 ms**      |
| JakesRTP         | 20.0     | 70 ms                 | 26.0 ms          |
| BetterRTP        | 7.1      | 771 ms                | 53.6 ms          |
| HuskHomes RTP    | 6.2      | 335 ms                | 52.2 ms          |

*Methodology: Paper 1.21, 2 OPed clients spamming `/rtp` continuously, in-repo harness linked below. Folia and Spigot results in the full benchmark section.*

Same throughput as the next-best plugin at **~17x lower worst-case tick spike and 35% less CPU per teleport.** Raw harness: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP).

**And speed isn't a trade against features.** The same off-tick architecture that holds your TPS also runs the full effects engine, eight bundled claim integrations, multi-server proxy support, SQL/Redis backends, and the `safety.yml` token grammar - performance is what makes the feature set affordable, not a compromise against it.

Each capability below is paired with the part of that architecture that makes it cheap to run - the feature set and the performance are the same system, not opposite ends of a dial:

| Feature you get | What makes it cost the server nothing |
|-----------------|---------------------------------------|
| **Lifecycle effects engine** - multi-phase particles, sounds, titles, fireworks, potions | Effect math and packet work run off the main thread, so they never show up in your MSPT. |
| **Per-region / per-world controls** - custom shapes, radii, centers, biome filters, safety per region | Locations are pre-warmed and validated in the background queue *before* anyone runs `/rtp`, so depth costs no tick time. |
| **8 bundled claim integrations** - GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect | Claim checks reroll inside the async pipeline, not on the tick that teleports the player. |
| **`safety.yml` token grammar** - block tags, state predicates, wildcards | The off-tick Anvil (`.mca`) pre-filter rejects unsafe biomes and oceans by reading region files directly, with no main-thread chunk loads. |
| **Multi-server proxy + SQL/Redis** - cross-network `/rtp`, reservation tokens, shared state | State sync runs on async backends; the teleport hot path stays free of foreign-region and blocking API hops. |
| **Built-in visualizations & observability** - `/rtp visualization` region/biome maps and bad-location heatmaps painted onto real map items, MSPT / heap / pipeline-latency sparklines, plus live `/rtp info` diagnostics | Charts render from in-memory state through the `maps-api` / `metrics-api` SPI, so analytics never touch the teleport hot path - no separate heatmap or metrics add-on needed. |
| **Optional PvP / combat-tag gate** - refuse or delay `/rtp` for players who recently dealt or took PvP damage, with native tracking plus PvPManager / CombatLogX / Simple Combat Log integration | Combat state is checked once at `/rtp` pre-dispatch through the `rtp-api` hook, so the anti-escape rule adds no per-tick cost to the teleport path. |
| **Developer API** (`rtp-api`, `effects-api`) - pre / mid / post teleport hooks | Hooks fire outside the critical loop, so downstream plugins can't stall your TPS. |

---

## What Pro adds over the free LeafRTP build

|                                                                                                                  | Free LeafRTP | **LeafRTP-Pro** |
|------------------------------------------------------------------------------------------------------------------|----------|-------------|
| **Folia** (tuned Region Scheduler + off-tick pre-filter, no 1-tick stalls)                                       | Basic    | **Tuned**   |
| **Multi-server / proxy** (Velocity)                                                                              | No       | **Yes**     |
| **SQL / Redis** shared-state backends (H2, SQLite, MySQL, PostgreSQL, Jedis)                                     | No       | **Yes**     |
| **Vault** economy (charge for `/rtp`)                                                                            | No       | **Yes**     |
| Multilingual `lang/**`, login-reserve cache, visitor mode                                                        | No       | **Yes**     |
| **`safety.yml` token grammar** - vanilla block tags, state predicates, wildcards                                 | No       | **Yes**     |
| Earliest release on each MC version + first access to new platforms/features + priority support                  | No       | **Yes**     |
| *Spigot + Paper engine, queues, spiral, Anvil pre-filter*                                                        | *Yes*    | *Yes*       |
| *8 claim plugins bundled (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect)* | *Yes*    | *Yes*       |
| *`effects-api`, `rtp-api`, PlaceholderAPI, ProtocolLib*                                                          | *Yes*    | *Yes*       |

Pro is the early-access tier: the tuned, throughput-optimized versions of new platforms and scaling backends land here first, because each one carries a real hands-on support burden that is only guaranteed on the paid tier. The free build still *runs* on Folia (basic regionized scheduling + async teleport); Pro's tuned Folia adapter is what graduates to the free build once stabilized. Same configuration, same data files, same commands as the free build - **upgrade is drop-in.**

---

## Built for

LeafRTP-Pro works on any server out of the box. It also shines for the setups that push the platform hardest - if any of these describe yours, it's the right tool:

- **High-concurrency servers** where `/rtp` spam used to spike MSPT and shake TPS.
- **Folia deployments** that need region-aware scheduling and zero foreign-region API hops in the teleport hot path.
- **Multi-server networks (Velocity)** that need cross-network LeafRTP with reservation tokens and shared state. Validated on the in-repo devstack: **2 Velocity proxies, 2 lobby servers, 2 backend servers** behind a shared Redis transport.
- **Large or pregenerated worlds** where chunk-load cost dominates - the pre-validated queue and Anvil pre-filter pay for themselves on the first burst.
- **Custom-generator worlds (Iris, Terra, datapacks)** where the Anvil-first biome read keeps your safety config authoritative across MC upgrades.
- **Operators of every stripe.** Set it and forget it, or version-control your YAML and read the profiler - both paths work. In-game `/rtp menu` is there when you want it; nothing is hidden behind it.

**Platform requirements:** Java 21+, on Paper / Folia / Spigot / Fabric / native NeoForge. Legacy Forge is not native - run the Spigot/Paper jar under Arclight / Mohist.

---

<details>
<summary><b>Commands &amp; permissions reference</b></summary>

**Commands** (registered aliases: `/rtp`, `/wild`):

| Command | Description | Permission |
| --- | --- | --- |
| `/rtp` / `/wild` | Random teleport to the default region for your current world | `rtp.use` |
| `/rtp region:<name>` | Teleport to a named region | `rtp.region` / `rtp.regions.*` |
| `/rtp world:<world>` | Teleport within a specific world | `rtp.world` / `rtp.worlds.*` |
| `/rtp player:<name>` | Teleport another player | `rtp.other` |
| `/rtp biome:<biome>` | Teleport to a chosen biome | `rtp.biome` / `rtp.biome.*` |
| `/rtp centerx=<x> centerz=<z> radius=<r>` | Ephemeral per-call overrides | `rtp.params` |
| `/rtp menu` | Interactive book (Paper / Folia) or chat-paginated menu | `rtp.use` |
| `/rtp info` | Operator diagnostics dashboard (queue, latency, leak rate, TPS/MSPT) | `rtp.info` |
| `/rtp reload [file]` | Reload all configuration, or one file | `rtp.reload` |
| `/rtp config <file> view\|set <k>=<v>` | View or set a config key, then auto-reload | `rtp.config` |
| `/rtp scan start\|pause\|resume\|reset\|cancel` | Background spatial-memory crawl | `rtp.scan` |

**Key permission nodes** (full set in `plugin.yml`):

| Permission | Default | Grants |
| --- | --- | --- |
| `rtp.use` | `true` | Use `/rtp` and `/wild` |
| `rtp.see` | `true` | Tab-complete `/rtp` |
| `rtp.free` | `op` | Skip the Vault economy charge |
| `rtp.params` | `op` | Custom per-call overrides (`centerx=`, `centerz=`, `radius=`) |
| `rtp.personalqueue` | `false` | Reserve a per-player pre-warmed location |
| `rtp.onevent.*` | `false` | Auto-RTP on join / firstjoin / respawn / changeworld / move / teleport |
| `rtp.admin` | `op` | All admin tooling (`reload`, `config`, `scan`, `info`) |
| `rtp.*` | `op` | Every subcommand |

</details>

<details>
<summary><b>Configuration files at a glance</b></summary>

LeafRTP-Pro splits configuration by concern under `plugins/RTP/`. Every file is hot-reloadable (`/rtp reload <file>`) and editable in-game via `/rtp config <file>`.

| File | Scope | Example keys |
| --- | --- | --- |
| `config.yml` | Core / global behavior, database, menu renderer | `teleportDelay`, `teleportCooldown`, `cancelDistance`, `database.type`, `menu.renderer` |
| `regions/<name>.yml` | Per-region shape, radius, center, queue, price, gates | `shape`, `vert`, `radius`, `cacheCap`, `price`, `requirePermission` |
| `worlds/<name>.yml` | Per-world region routing and overrides | `region`, `override`, `requirePermission` |
| `safety.yml` | Block / biome safety filter (Pro token grammar) | `unsafeBlocks`, `airBlocks` |
| `economy.yml` | Vault pricing (Pro) | `price`, `priceOther`, `biomePrice`, `balanceFloor`, `refundOnCancel` |
| `effects/<name>.yml` | Lifecycle effects (particles, sounds, fireworks, potions) | per-phase effect definitions |
| `performance.yml` | Tick budgets, queue caps, caches | `maxAttempts`, `syncAllottedTime`, `minTPS`, `loginCacheEnabled` |
| `network.yml` | Proxy / multi-server selector (Pro) | `enabled`, transport settings |
| `integrations.yml` | Claim-plugin reroll toggles | `rerollWorldGuard`, `rerollGriefPrevention`, ... |
| `logging.yml` | Log verbosity and channels | logging toggles |
| `metrics.yml` | Runtime-health metrics | metrics toggles |
| `language.yml` | Locale selection | `language` |
| `messages.yml` | User-facing text (fully localizable, REQ-RTP-F-013) | message templates |
| `lang/**` | Bundled translations (Pro) | per-locale value files |

</details>

<details>
<summary><b>Full benchmark - Paper, Spigot, Folia head-to-head</b></summary>

**Confidence:** measured; from plugin docs; inferred from architecture.

**Metrics:** Throughput (TP/s, higher better) - MSPT p99 (worst 1-in-100 main-thread tick in ms; <~50 ms = no dropped tick) - Min TPS (20.00 = no hiccup) - CPU / TP (main-thread CPU per successful teleport).

**Paper 1.20.1 / 1.21.11** - recommended platform. Eight plugins, same harness, same world, same two OPed clients.

| Plugin                | TP/s     | MSPT p99 (ms) | Min TPS   | CPU / TP (ms) | Success    |
|-----------------------|----------|---------------|-----------|----------------|------------|
| **LeafRTP-Pro**           | **19.8** | **4**         | **20.00** | **16.9**       | **100 %**  |
| JakesRTP              | 20.0     | 70            | 20.00     | 26.0           | 100 %      |
| BetterRTP             | 7.3      | 852           | 20.00     | 53.6           | 100 %      |
| HuskHomes             | 6.2      | 372           | 20.00     | 52.2           | 100 %      |
| AdvancedRTP           | 2.16     | 2 100         | 19.95     | 92.1           | 96.3 %     |
| EzRTP                 | 1.76     | 2 903         | 19.95     | 139.6          | 100 %      |
| AsyRTP                | 1.67     | 4 534         | 19.95     | 38.8           | 100 %      |
| EssentialsX `/tpr`    | 0.96     | 4 504         | 19.95     | 88.9           | 75.9 % §   |

§ EssentialsX `/tpr` is a teleport-*request* command (handshake + accept), not a teleport-*do* command; the harness's 5 s per-attempt deadline times out a fraction of the request-accept latencies. Numbers are dispatch-shaped, not plugin-broken.

**Spigot 1.20.1** - platform-wide chunk-gen ceiling caps everyone at 1-1.5 TP/s; the latency tail is what matters.

| Plugin       | TP/s     | MSPT p99 (ms) | Min TPS |
|--------------|----------|---------------|---------|
| **LeafRTP-Pro**  | **1.52** | **3**         | **6.4** |
| JakesRTP     | 1.04     | 2 252         | --      |
| BetterRTP    | 1.33     | 3 790         | 2.18    |
| HuskHomes    | 0.93     | 4 939         | 2.59    |

**Folia 1.21.11** - Region MSPT = per-region tick (a bad region doesn't stall the server).

| Plugin       | TP/s     | Region MSPT p99 (ms) | Success     | CPU / TP (ms) |
|--------------|----------|----------------------|-------------|----------------|
| **LeafRTP-Pro**  | **9.87** | **157**              | **99.97 %** | **18.0**       |
| BetterRTP    | 3.82     | 1 200                | 100 %       | 34.8           |
| HuskHomes    | 3.32     | 901                  | 100 %       | 28.4           |

*The LeafRTP-Pro Folia TP/s here is a harness floor, not an engine ceiling: at 18.0 ms compute per teleport the work is far from saturating a region tick, so throughput was bounded by Region-Scheduler hops in the 2-client rig rather than by computation. The competitors' lower TP/s come paired with second-scale region MSPT (compute/stall bound); ours does not.*

**Architecture support matrix:**

| Plugin              | Spigot                                        | Paper (+ forks)                  | Folia                                       |
|---------------------|-----------------------------------------------|----------------------------------|---------------------------------------------|
| **LeafRTP-Pro**         | Off-tick Anvil pre-filter; rare bounded fallback | Fully async via `getChunkAtAsync` | Region Scheduler + off-tick pre-filter      |
| BetterRTP           | Sync chunk load on miss                       | Paper async; no safety pre-filter | p99 ~1.2 s                                  |
| EzRTP               | Paper-only API crashes on Spigot 1.20.1       | N/A                              | N/A                                         |
| AsyRTP              | Fails to enable on Spigot 1.20.1              | N/A                              | N/A                                         |
| SorekillRTP         | Redis-cross-server focus                      | Same                             | N/A                                         |
| AdvancedRTP         | Safety-first, sync chunk load                 | Same                             | N/A                                         |
| JakesRTP            | Async via flag; 10-slot cache                 | Widely deployed                  | N/A                                         |
| EssentialsX `/rtp`  | Main-thread chunk load                        | Same                             | N/A                                         |
| HuskHomes RTP       | Supported                                     | Same                             | p99 ~900 ms                                 |

**Memory & TPS:** queue bounded (bounded by `cacheCap`); each entry is a small POJO. Server TPS held at **20.00** across every Paper and Folia run. On Spigot, every plugin saturates to the same chunk-gen ceiling; LeafRTP-Pro just spends those ticks doing fewer things.

- Paper LeafRTP-Pro row reproduced n=2; other rows are n=1 on a single rig. - JakesRTP Spigot row ran in slot 4 of a 4-phase chain - Min TPS / CPU-per-TP confounded by carry-over; dispatch-time numbers (throughput, p99) remain valid.

**Caveats.** 2 clients only (the number is a floor, not a ceiling). Hardware, view distance, world state, and other plugins will move the numbers. Competitor plugins update frequently; corrections welcome via GitHub issue with a contradicting repro or doc link. Feature breadth, GUI, and claim-integration count are not benchmarked - several competitors trade speed for those, which is a legitimate choice.

Full methodology, raw CSVs, per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3/helpers/StressTestRTP). Video benchmark of `/rtp` on a custom world generator: [youtu.be/V0NyNK9JydM](https://youtu.be/V0NyNK9JydM).

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
- **Fabric** - first-class, stable, in-tree adapter; supported and regularly tested, at feature parity with the Bukkit family. Loom-remapped obf/unobf carriers cover 1.20.x, 1.21.x, and MC 26.x runtimes.

**Honest fallback caveats.** The Anvil pre-filter is a data source, not a universal gate. It falls through to the platform's native chunk API in two cases: (1) the chunk is already loaded (live data wins), or (2) the probe returns *unknown* (no region file, unsupported data version, decode error, un-populated). On Spigot the fallback is one on-tick `getChunkAt`. On Folia it's one Region-Scheduler hop. Custom generators (Iris, Terra, datapacks) do **not** trigger the fallback - populated `.mca` palettes are read directly, preserving modded and namespaced IDs that the Bukkit enum would collapse.

**Upgrade-drift-proof biome filtering.** When you bump your Paper or Folia server across MC versions, Mojang's seed-based biome assignment can change for already-written coordinates. The Anvil-first biome read keeps the `.mca` palette authoritative for populated chunks - the biome a player lands in is the biome your `biomes:` allow-list was written against. No re-pregen, no re-tuning after an upgrade.

**Operator takeaway:** Paper and Folia run chunk I/O fully off-tick. On vanilla Spigot the Anvil pre-filter covers the common case; rare main-thread fallback is bounded by your configured tick-budget; no platform silently blocks the tick loop without reporting it.

</details>

<details>
<summary><b>Spatial memory - why the longer it runs, the faster it gets</b></summary>

Without spatial indexing, teleportation is a guessing game and the numbers are ugly. A typical Overworld is only **~45% safely teleportable** (oceans, rivers, ravines, steep terrain eat the rest); the Nether is dominated by lava seas and wall-to-wall stone; the End is *almost entirely* void. Standard RTPs pay full chunk-load cost to rediscover this one candidate at a time, forever.

LeafRTP-Pro plots your world's geometry as it evaluates candidates. When it hits a massive ocean or unsafe biome, it remembers that sector and shrinks the searchable area, so known bad sectors never get loaded again. The longer it runs, the faster it gets - and `/rtp scan` keeps the learning going during idle periods.

Watch the background scan crawl the spiral and accumulate spatial memory in real time: [youtu.be/Ftjy1zw_S04](https://youtu.be/Ftjy1zw_S04).

**Persistence across restarts:** learned bad-sector state survives a JVM bounce - `MemoryShape.save` / `load` writes per-region spatial memory to disk on shutdown and reloads on startup, alongside the configuration cache (compiled safety sets, region geometry). H2, SQLite, MySQL, and PostgreSQL backends support shared multi-server state.

*Local-profile estimate from a vanilla 1.21 seed set; Nether and End are still qualitative. Anonymous opt-in telemetry is on the roadmap to source this number properly.*

</details>

<details>
<summary><b>safety.yml token grammar - Pro exclusive</b></summary>

`safety.yml` has a first-class token grammar. Six shapes can be mixed freely in `unsafeBlocks` and `airBlocks`:

- **Plain material:** `LAVA`, `MAGMA_BLOCK`.
- **Material + state predicate:** `OAK_SLAB[waterlogged=true]`. Multiple predicates AND together: `OAK_SLAB[waterlogged=true,type=top]`.
- **Numeric range predicate:** `WATER[level>=5]`, `LIGHT[level<8]`. Operators `>=` / `<=` / `>` / `<` bound fluid levels, light levels, and any integer block-state property; fail-open on an absent or non-numeric value.
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
- Vertical adjustors (Linear, Jump, Fixed) for sky islands, void worlds, Nether ceilings.
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
- **Optional PvP / combat-tag gate.** Off by default; when enabled, `/rtp` is refused or delayed for players who recently dealt or took PvP damage, so a teleport can't be used to escape a fight. Native combat tracking works out of the box, with optional PvPManager / CombatLogX / Simple Combat Log integration; the refusal message is fully localizable (`messages.yml`).
- Optional landing platform with configurable material and decay timer.
- **Per-region arrival schematics.** Drop a `.schem` named after a region into `plugins/RTP/schematics/` (e.g. `schematics/default.schem`) and every teleport into that region pastes it - a lobby pad, arrival shrine, or custom platform - centered on the landing spot. Cross-platform Sponge `.schem`, decoded in-house (no WorldEdit required), claim-aware (never overwrites protected land) and audited; the bundled Skyblock prefab ships one ready to go.
- PlaceholderAPI: queue depth (total/public/personal), last-teleport coordinates, player status.
- Public `rtp-api` (same surface as Free) - trigger LeafRTP from GUI, NPC, quest reward; build your own UX without forking.

**Bundled claim integrations** (no extra download, folded in per ADR-019): Factions, GriefDefender, GriefPrevention, HuskTowns, Lands, RedProtect, TownyAdvanced, WorldGuard.

**Pro-only configuration:** Vault per-region pricing, complete localization (`lang/**`), login-reserve cache, visitor mode.

</details>

<details>
<summary><b>Already built in - the things operators usually bolt on with extra plugins</b></summary>

LeafRTP-Pro ships, in the core engine, a long list of capabilities that competing setups reach for add-ons to cover. Before installing a companion plugin, check here - it is probably already in the box, under LeafRTP's own vocabulary:

| Capability | How LeafRTP already covers it |
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

**Q: How do I stop `/rtp` from lagging my server, and why is LeafRTP-Pro faster than other random teleport plugins?**
A: Pre-warmed queue. In most cases a verified destination is ready before you type `/rtp`. Two design choices keep that queue cheap to refill: a **persistent spatial memory** per region (the plugin remembers which sectors failed safety checks, so the spiral selector skips known-bad ground instead of rerolling indefinitely) and an **off-tick async pre-filter** (Anvil region files are read directly to reject unsafe biomes/blocks *before* any chunk is loaded, so candidate verification never blocks the main thread).

**Q: How do I set up teleportation between worlds?**
A: See the admin guide. Resolution order: player's current world (or `world:` param) -> world's target region -> region's target world.

**Q: Does LeafRTP work on Folia?**
A: Yes, on both editions. The free build runs on Folia out of the box with a basic regionized scheduler (correctness-first: global/region/entity scheduling + async teleport). LeafRTP-Pro adds the tuned Folia adapter - the Region Scheduler with an off-tick pre-filter so no region stalls. Per-teleport work stays comfortably under one region tick; the 9.87 TP/s @ 99.97% success we measured was limited by Region-Scheduler hops in the test harness, not by the engine's computation, so treat it as a harness-imposed floor rather than a performance ceiling.

**Q: Do you support triangle / diamond region shapes?**
A: Use the `Polygon` shape. A triangle is a 3-vertex polygon and a diamond is a rotated square, so both are already expressible without a dedicated shape type - define the vertices you want and the bounded spiral fills it.

**Q: Do I need Chunky (or another pre-generator)?**
A: No. `/rtp scan start|pause|resume|reset|cancel` is a built-in, off-tick generator that walks the region and builds persistent spatial memory. The philosophy is different from whole-world pre-generation: instead of loading every chunk up front, LeafRTP pre-*verifies* and remembers which sectors are unsafe, so it avoids loading bad ground at all. You can still run Chunky alongside it if you want a fully pre-generated map.

**Q: How does biome targeting behave on a pregenerated or upgraded world?**
A: LeafRTP reads biome data from the populated `.mca` region files via the Anvil pre-filter, so `/rtp biome:<x>` reflects the biomes actually written to disk. Plugins that lean on the live generator/noise-map biome lookup can return the *wrong* biome on a world that was pregenerated elsewhere or migrated across a Minecraft version, where Mojang's seed-based assignment has drifted - the Anvil-first read stays authoritative in those cases.

**Q: Iris / Terra / custom datapack generators?**
A: Yes - deliberate 3.0 design goal. The Anvil pre-filter reads `.mca` palette data directly, so populated custom-generator chunks evaluate off-tick like vanilla. That's *strictly more accurate* than the live Bukkit view, which collapses modded IDs to vanilla cousins. Un-populated chunks fall through to a live load as the authoritative safety net.

**Q: Existing `safety.yml` on upgrade?**
A: Plain-material entries keep working unchanged; the new grammar is strictly additive.

**Q: Best LeafRTP plugin / setup for Paper 1.21 performance?**
A: Paper, with memory to spare for the location cache. LeafRTP works particularly well on pre-generated worlds.

**Q: Can I downgrade to the free LeafRTP build?**
A: Yes - same configuration, same data files, same commands. The free build still runs on Folia (basic regionized scheduling); what you give up is the *tuned* Folia adapter, plus proxy, SQL/Redis, Vault, multilingual, and the tag/state-predicate grammar.

</details>


<details>
<summary><b>Support</b></summary>

- **Bug reports and configuration questions** are welcome - the admin guide answers most setup questions, and a clear repro (server version, plugin version, platform, relevant configs and log lines) gets a fast resolution.
- **Response time:** 24-72 h on weekdays. Critical safety issues jump the queue.
- **Feature requests** via GitHub issues. Priority follows the published roadmap.
- **Native NeoForge** is supported on Minecraft 1.21.x / 26.1.x. **Native Forge** is not supported - use Arclight or Mohist with the Spigot/Paper jar.

</details>

---

## Links

- [**LeafRTP admin & configuration guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_SERVER_ADMINS.md) - install, configure, command reference
- [**LeafRTP addon / API developer guide**](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_ADDON_DEVELOPERS.md) - `rtp-api` & examples
- [**LeafRTP changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3/CHANGELOG.md)
- [**LeafRTP source on GitHub**](https://github.com/dailystruggle/RTP) - star, watch, contribute, file issues
- [**Free LeafRTP plugin (download)**](https://modrinth.com/plugin/rtpv3) - the open, single-server build of this same random teleport engine

---

> *A deterministic, high-performance teleport engine for serious Minecraft servers. Folia-native concurrency, audited safety invariants, and bounded-latency execution.*
