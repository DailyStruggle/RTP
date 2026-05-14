<!--
Markdown equivalent of FRONT_PAGE.bbcode (RTP-Pro front page).
Kept in sync with the BBCode source by hand; update both when changing copy.
Conversion-optimized: hook → benchmark proof → buyer fit → CTA → deep dive.
-->

<div align="center">

# RTP-Pro

### Deterministic, zero-latency Random Teleport for technical networks
*Folia-ready · multi-server / proxy (beta.3+) · audited safety · same engine as the free RTP build, with the enterprise pieces turned on.*

**3.0.0 — Early Access (beta.2)** · **Beta price $7.50** · $15 at GA · Early buyers keep the resource at no extra cost.

[![Version](https://img.shields.io/github/v/release/dailystruggle/RTP?label=Version&style=for-the-badge&color=2980B9)](https://github.com/dailystruggle/RTP) [![License](https://img.shields.io/github/license/dailystruggle/RTP?label=License&style=for-the-badge&color=2C3E50)](https://github.com/dailystruggle/RTP)

</div>

---

### Why operators buy Pro

On **Paper 1.21**, measured on the in-repo harness, 2 OPed clients spamming `/rtp` back-to-back:

| Plugin           | TP/s     | Worst tick (MSPT p99) | CPU per teleport |
|------------------|----------|-----------------------|------------------|
| **🧪 RTP-Pro**   | **19.8** | **4 ms**              | **16.9 ms**      |
| 🧪 JakesRTP      | 20.0     | 70 ms                 | 26.0 ms          |
| 🧪 BetterRTP     | 7.1      | 771 ms                | 53.6 ms          |
| 🧪 HuskHomes RTP | 6.2     | 335 ms                | 52.2 ms          |

Same throughput as the next-best plugin at **~17× lower worst-case tick spike and 35% less CPU per teleport.** On Folia, RTP-Pro is the only plugin in the field that hits double-digit TP/s (9.87 TP/s @ 99.97% success). Raw harness: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/main/helpers/StressTestRTP).

---

### What Pro adds over the free RTP build

| | Free RTP | **RTP-Pro** |
|---|---|---|
| Spigot + Paper engine, queues, spiral, Anvil pre-filter | ✅ | ✅ |
| 8 claim plugins bundled (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect) | ✅ | ✅ |
| `effects-api`, `rtp-api`, PlaceholderAPI, ProtocolLib | ✅ | ✅ |
| **Folia** (Region Scheduler + off-tick pre-filter, no 1-tick stalls) | ❌ | ✅ |
| **Multi-server / proxy** (Velocity, BungeeCord) — *planned `3.0.0-beta.3`* | ❌ | ✅ |
| **SQL / Redis** shared-state backends (H2, SQLite, MySQL, PostgreSQL, Jedis) | ❌ | ✅ |
| **Vault** economy (charge for `/rtp`) | ❌ | ✅ |
| Multilingual `lang/**`, login-reserve cache, visitor mode | ❌ | ✅ |
| **`safety.yml` token grammar** — vanilla block tags, state predicates, wildcards | ❌ | ✅ |
| Earliest release on each MC version + priority support | ❌ | ✅ |

Same configuration, same data files, same commands as the free build — **upgrade is drop-in.**

---

### Buyer fit (read before purchase)

A few hard requirements. If any are a **no**, EssentialsX `/rtp` or HuskHomes are solid free alternatives that will probably serve you better.

- ✅ **Java 21+** on your host (REQ-RTP-SYS-001, non-negotiable).
- ✅ **Paper, Folia, Spigot, or Fabric** — or Arclight / Mohist for Forge / NeoForge.
- ✅ **You edit YAML.** No GUI by design — plain files you can version and diff.
- ✅ **You read the admin guide before asking for help.** Support Policy is enforced (see below).
- ✅ **Real server, not a 2-player LAN.** For a friends-only SMP this is overkill — grab the free build.

---

<details open>
<summary><b>📊 Full benchmark — Paper, Spigot, Folia head-to-head</b></summary>

**Confidence:** 🧪 measured · 📖 from plugin docs · ❓ inferred from architecture.

**Metrics:** Throughput (TP/s, higher better) · MSPT p99 (worst 1-in-100 main-thread tick in ms; <~50 ms = no dropped tick) · Min TPS (20.00 = no hiccup) · CPU / TP (main-thread CPU per successful teleport).

**Paper 1.21.11** — recommended platform.

| Plugin         | TP/s | MSPT p99 (ms) | Min TPS | CPU / TP (ms) |
|----------------|------|---------------|---------|----------------|
| **🧪 RTP-Pro** | **19.8** | **4** | **20.00** | **16.9** |
| 🧪 JakesRTP   | 20.0 | 70  | 20.00 | 26.0 |
| 🧪 BetterRTP  | 7.1  | 771 | 20.00 | 53.6 |
| 🧪 HuskHomes  | 6.2  | 335 | 20.00 | 52.2 |

**Spigot 1.20.1** — platform-wide chunk-gen ceiling caps everyone at 1–1.5 TP/s; the latency tail is what matters.

| Plugin         | TP/s | MSPT p99 (ms) | Min TPS | CPU / TP (ms) |
|----------------|------|---------------|---------|----------------|
| **🧪 RTP-Pro** | **1.52** | **3** | **6.4** | 572 |
| 🧪 JakesRTP   | 1.04 | 2 252 | —† | —† |
| 🧪 BetterRTP  | 1.33 | 3 790 | 2.18 | 584 |
| 🧪 HuskHomes  | 0.93 | 4 939 | 2.59 | 868 |

**Folia 1.21.11** — Region MSPT = per-region tick (a bad region doesn't stall the server).

| Plugin         | TP/s | Region MSPT p99 (ms) | Success | CPU / TP (ms) |
|----------------|------|----------------------|---------|----------------|
| **🧪 RTP-Pro** | **9.87** | **157** | **99.97 %** | **18.0** |
| 🧪 BetterRTP   | 3.82 | 1 200 | 100 % | 34.8 |
| 🧪 HuskHomes   | 3.32 | 901  | 100 % | 28.4 |

**Architecture support matrix:**

| Plugin | Spigot | Paper (+ forks) | Folia |
|---|---|---|---|
| **RTP-Pro** | ✅🧪 Off-tick Anvil pre-filter; rare bounded fallback | ✅🧪 Fully async via `getChunkAtAsync` | ✅🧪 Region Scheduler + off-tick pre-filter |
| BetterRTP | ⚠️📖 Sync chunk load on miss | ⚠️📖 Paper async; no safety pre-filter | ✅🧪 p99 ~1.2 s |
| EzRTP | ❌🧪 Paper-only API crashes on Spigot 1.20.1 | ✅📖 | ❓📖 |
| AsyRTP | ❌🧪 Fails to enable on Spigot 1.20.1 | ✅📖 | ✅📖 |
| SorekillRTP | ⚠️❓ Redis-cross-server focus | ⚠️❓ Same | ❓📖 |
| AdvancedRTP | ⚠️📖 Safety-first, sync chunk load | ⚠️📖 Same | ❌📖 |
| JakesRTP | ⚠️📖 Async via flag; 10-slot cache | ✅📖 Widely deployed | ❌📖 |
| EssentialsX `/rtp` | ✅📖 Main-thread chunk load per candidate | ✅📖 Same | ❌📖 |
| HuskHomes RTP | ✅📖 | ⚠️📖 Same | ✅🧪 p99 ~900 ms |

**Memory & TPS:** queue bounded (bounded by `cacheCap`); each entry is a small POJO. Server TPS held at **20.00** across every Paper and Folia run. On Spigot, every plugin saturates to the same chunk-gen ceiling; RTP-Pro just spends those ticks doing fewer things.

‡ Paper RTP-Pro row reproduced n=2; other rows are n=1 on a single rig. † JakesRTP Spigot row ran in slot 4 of a 4-phase chain — Min TPS / CPU-per-TP confounded by carry-over from the prior HuskHomes phase; dispatch-time numbers (throughput, p99) remain valid.

**Caveats.** 2 clients only (number is a floor, not a ceiling). Hardware, view distance, world state, and other plugins will move the numbers. Competitor plugins update frequently; corrections welcome via GitHub issue with a contradicting repro or doc link. Feature breadth, GUI, and claim-integration count are not benchmarked — several competitors trade speed for those, which is a legitimate choice.

Full methodology, raw CSVs, per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/main/helpers/StressTestRTP).

</details>

<details>
<summary><b>🏛️ Architecture — five pillars + chunk-loading by platform</b></summary>

**Five pillars:**

- **Deterministic spiral selection.** Bounded math, uniform player distribution even on massive worlds; no unbounded re-roll loops.
- **Platform-native performance.** Spigot, Paper, and Folia each get the threading model that fits them — not the lowest-common-denominator.
- **Active resource watchdog.** Background sweep reclaims chunk tickets from abandoned teleports via WeakReferences; nothing stays force-loaded past its reservation window.
- **Direct region I/O pre-filter.** `.mca` region files parsed off-tick on a background pool, including biomes (custom namespaced biomes like Iris included). Paper-grade safety evaluation on Spigot; skips per-candidate Region-Thread hops on Folia.
- **Admin-driven background mapping.** `/rtp scan start|pause|resume|reset|cancel` walks the spiral during idle periods so spatial memory accumulates without player traffic. Fully-automatic self-warming is on the roadmap for `3.0.0` final.

**Chunk loading, by platform:**

- **Paper** — `World#getChunkAtAsync` used directly. No pre-filter, no main-thread fallback. Reference platform.
- **Paper forks** (Leaf, Leaves, Purpur, Pufferfish, Airplane, DivineMC, …) — inherit the Paper code path.
- **Folia** — Anvil read-only pre-filter on `ForkJoinPool.commonPool()` *before* the Region Scheduler; rejected candidates never hop a thread. Confirmed candidates load through Folia's native async API; teleports dispatch through the Entity Scheduler.
- **Spigot** — `.mca` region files parsed off-tick (`isAir`, `isSafe`, surface-height, sky-light, biome). Paper-class throughput on plain Spigot.
- **Mohist / Arclight** — officially supported. Spigot code path applies.
- **Fabric** — in-tree adapter, functional (unstable frontier — see roadmap). Loom-remapped obf/unobf carriers cover 1.20.x, 1.21.x, and MC 26.x runtimes.

**Honest fallback caveats.** The Anvil pre-filter is a data source, not a universal gate. It falls through to the platform's native chunk API in two cases: (1) the chunk is already loaded (live data wins), or (2) the probe returns *unknown* (no region file, unsupported data version, decode error, un-populated). On Spigot the fallback is one on-tick `getChunkAt`. On Folia it's one Region-Scheduler hop. Custom generators (Iris, Terra, datapacks) do **not** trigger the fallback — populated `.mca` palettes are read directly, preserving modded and namespaced IDs that the Bukkit enum would collapse.

**Upgrade-drift-proof biome filtering.** When you bump your Paper or Folia server across MC versions, Mojang's seed-based biome assignment can change for already-written coordinates. The Anvil-first biome read keeps the `.mca` palette authoritative for populated chunks — the biome a player lands in is the biome your `biomes:` allow-list was written against. No re-pregen, no re-tuning after an upgrade.

**Operator takeaway:** Paper and Folia run chunk I/O fully off-tick. On vanilla Spigot the Anvil pre-filter covers the common case; rare main-thread fallback is bounded by your configured tick-budget; no platform silently blocks the tick loop without reporting it.

</details>

<details>
<summary><b>🧮 Spatial memory — why the longer it runs, the faster it gets</b></summary>

Without spatial indexing, teleportation is a guessing game and the numbers are ugly. A typical Overworld is only **~45% safely teleportable**† (oceans, rivers, ravines, steep terrain eat the rest); the Nether is dominated by lava seas and wall-to-wall stone; the End is *almost entirely* void. Standard RTPs pay full chunk-load cost to rediscover this one candidate at a time, forever.

RTP-Pro plots your world's geometry as it evaluates candidates. When it hits a massive ocean or unsafe biome, it remembers that sector and shrinks the searchable area, so known bad sectors never get loaded again. The longer it runs, the faster it gets — and `/rtp scan` keeps the learning going during idle periods.

**Persistence across restarts:** learned bad-sector state survives a JVM bounce — `MemoryShape.save`/`load` writes per-region spatial memory to disk on shutdown and reloads on startup, alongside the configuration cache (compiled safety sets, region geometry). H2, SQLite, MySQL, and PostgreSQL backends support shared multi-server state.

<sub>† Local-profile estimate from a vanilla 1.21 seed set; Nether and End are still qualitative. Anonymous opt-in telemetry is on the roadmap to source this number properly.</sub>

</details>

<details>
<summary><b>🛡️ `safety.yml` token grammar — Pro exclusive</b></summary>

`safety.yml` has a first-class token grammar. Five shapes can be mixed freely in `unsafeBlocks` and `airBlocks`:

- **Plain material:** `LAVA`, `MAGMA_BLOCK`.
- **Material + state predicate:** `OAK_SLAB[waterlogged=true]`. Multiple predicates AND together: `OAK_SLAB[waterlogged=true,type=top]`.
- **Vanilla block tag:** `#minecraft:leaves`, `#minecraft:fire`, `#minecraft:campfires`. Live-registry expansion at config-load lands in `3.0.0` final — use explicit names alongside tag tokens during the beta.
- **Tag + state predicate:** `#minecraft:slabs[waterlogged=true]` — "any slab, but only when waterlogged".
- **Wildcard + state predicate:** `*[waterlogged=true]` — "any block, matched when waterlogged". One line replaces the entire waterloggable enumeration.

**Properties:**

- **Fail-open on unknown tags/properties** — missing tags on older MC versions silently reduce coverage rather than breaking startup. Configs stay portable.
- **Never silent on malformed tokens** — typos like `OAK_SLAB[waterlogged=true` surface as `[WARNING] [safety.yml] rejected token '…': <reason>`.
- **Zero hot-path cost when unused** — block-state extraction only fires when a token actually needs it; plain-material configs pay nothing.
- **Platform-portable tag resolution** — Bukkit reads from `Bukkit.getTag`; the standalone `rtp-tags` module parses tag JSON directly from data packs and jars for future non-Bukkit platforms.

**Default `safety.yml` is ~25% shorter:** `FIRE`+`SOUL_FIRE` → `#minecraft:fire`; every waterloggable block → `*[waterlogged=true]`; dozens of decorative plants → `#minecraft:flowers`, `#minecraft:saplings`, `#minecraft:crops`, etc.

</details>

<details>
<summary><b>📦 Full feature suite & integrations</b></summary>

**Engine:**

- Any number of teleport regions per world; per-region shape (Square, Circle, Rectangle), radius, center, curve weighting, vertical bounds, world override, permission gates.
- Vertical adjustors (Linear, Jump) for sky islands, void worlds, Nether ceilings.
- Multi-dimensional (Overworld, Nether, End, custom).
- Hot-reloadable YAML; experimental `/rtp config` editor.
- Fully async chunk loading on Paper / Folia; off-tick Anvil pre-filter on Spigot.
- **Per-player isolated queues** alongside a global queue — one player's bad luck never starves another's teleport.
- Administrative scan lifecycle (`start`/`pause`/`resume`/`reset`/`cancel`) to pre-populate spatial memory without teleporting players.
- Persistent learned state via H2 / SQLite / MySQL / PostgreSQL.
- Per-tick time budgets and (on Folia) per-tick task-count caps on the region-bound pipe.

**Player polish (UX):**

- Configurable countdown/warmup messages during pre-teleport.
- Particles, sounds, fireworks, potions, note-block effects via `effects-api`, attached to lifecycle phases, gated by `rtp.effects.<name>` permissions.
- Movement-cancel, damage-cancel, invulnerability-after-teleport timers.
- Optional landing platform with configurable material and decay timer.
- PlaceholderAPI: queue depth (total/public/personal), last-teleport coordinates, player status.
- Public `rtp-api` (same surface as Free) — trigger RTP from GUI, NPC, quest reward; build your own UX without forking.

**Bundled claim integrations** (no extra download, folded in per ADR-019): Factions, GriefDefender, GriefPrevention, HuskTowns, Lands, RedProtect, TownyAdvanced, WorldGuard.

**Pro-only configuration:** Vault per-region pricing, complete localization (`lang/**`), login-reserve cache, visitor mode.

</details>

<details>
<summary><b>🚧 Roadmap — known limitations and planned work in 3.0.0-beta.2</b></summary>

Rough edges I already know about, with direction:

- **Spigot main-thread fallback** — when the Anvil pre-filter can't resolve a chunk, one on-tick load fills the gap. Planned: per-minute fallback counters and an optional strict mode that skips the fallback entirely.
- **Folia scheduler hop** for un-pre-resolvable candidates — cost currently unmeasured. Planned: publish p50/p95, look at amortising across adjacent candidates.
- **Un-populated chunks** always fall through to a live load (correct by design). Planned: dedicated attribution bucket so operators can distinguish this from other fallbacks.
- **Multi-server / proxy** (Velocity, BungeeCord) — planned for `3.0.0-beta.3`. Cross-network UUID queue + reservation tokens in the network-state DB.
- **Fabric** — functional first-class platform (unstable frontier). Stabilization work continues on scheduled-task processor parity, permissions, and full Brigadier tree. Native Forge/NeoForge remains lower priority than multi-server; Arclight/Mohist + the Spigot/Paper jar is the supported path there.
- **Telemetry-sourced marketing numbers.** ~45% Overworld-safe is local-rig profiling; Nether/End are qualitative. Planned: anonymous opt-in telemetry + a published reference benchmark.
- **Demo videos predate this release.** Underlying principles unchanged; numbers aren't current. Planned: fresh footage before `-beta` comes off.

Everything above is a known issue with a plan — not a surprise. If you hit something not on this list, please open a GitHub issue.

</details>

<details>
<summary><b>❓ FAQ</b></summary>

**Why does this respond faster than other RTP plugins?**
Pre-warmed queue. In most cases a verified destination is ready before you type `/rtp`. Two design choices keep that queue cheap to refill: a **persistent spatial memory** per region (the plugin remembers which sectors failed safety checks, so the spiral selector skips known-bad ground instead of rerolling indefinitely) and an **off-tick async pre-filter** (Anvil region files are read directly to reject unsafe biomes/blocks *before* any chunk is loaded, so candidate verification never blocks the main thread).

**How do I set up teleportation between worlds?**
See the admin guide. Resolution order: player's current world (or `world:` param) → world's target region → region's target world.

**Iris / Terra / custom datapack generators?**
Yes — deliberate 3.0 design goal. The Anvil pre-filter reads `.mca` palette data directly, so populated custom-generator chunks evaluate off-tick like vanilla. That's *strictly more accurate* than the live Bukkit view, which collapses modded IDs to vanilla cousins. Un-populated chunks fall through to a live load as the authoritative safety net.

**Existing `safety.yml` on upgrade?**
Plain-material entries keep working unchanged; the new grammar is strictly additive.

**Best setup for performance?**
Paper, with memory to spare for the location cache. RTP works particularly well on pre-generated worlds.

**Can I downgrade to the free RTP build?**
Yes — same configuration, same data files, same commands. You lose Folia, proxy, SQL/Redis, Vault, multilingual, and the tag/state-predicate grammar.

</details>

<details>
<summary><b>🎬 Demonstration videos (RTP 2.x — same principles, older numbers)</b></summary>

*Recorded on earlier versions. Spatial learning and queue-paced throughput are unchanged in 3.0; on-screen numbers aren't current-release benchmarks.*

- **Learning & deterministic-time test** — [YouTube: PY6W7DikhAI](https://www.youtube.com/watch?v=PY6W7DikhAI). The plugin "learns" bad sectors; selection time converges to a predictable lower bound.
- **Command-response & throughput test** — [YouTube: l6KSvKxsKAQ](https://www.youtube.com/watch?v=l6KSvKxsKAQ). Pre-generated queue → near-instant `/rtp` response under load.

</details>

<details>
<summary><b>📞 Support Policy (enforced — read before purchase)</b></summary>

- **Support covers bugs and configuration questions,** after you've read the admin guide.
- **Bug reports need a reproduction:** server version, plugin version, platform (Spigot/Paper/Folia), `config.yml`, `regions/`, `safety.yml`, and the relevant `server.log` section. Reports without these are asked for them once, then closed.
- **"It doesn't work" is not a bug report.** Tell me what you did, what you expected, and what actually happened.
- **Unsupported:** native Forge/NeoForge (use Arclight/Mohist), plugin conflicts I can't reproduce, general MC-server admin questions. Fabric is supported on a best-effort basis as an unstable frontier.
- **Response time:** solo maintainer. 24–72 h on weekdays for properly-filed reports. Critical safety issues (S-001…S-007 violations) jump the queue.
- **Feature requests** via GitHub issues, not the resource thread. Priority follows the published roadmap, not ticket volume.

This policy exists because focused engineering time is what keeps the plugin fast and current. Respecting it is how you get the most out of your purchase.

</details>

---

### Links

- [**Admin guide**](https://github.com/dailystruggle/RTP/blob/main/docs/admin/FOR_SERVER_ADMINS.md) — install, configure, command reference
- [**Addon developer guide**](https://github.com/dailystruggle/RTP/blob/main/docs/dev/FOR_ADDON_DEVELOPERS.md) — API & examples
- [**Changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/main/CHANGELOG.md)
- [**Source on GitHub**](https://github.com/dailystruggle/RTP) — star, watch, contribute, file issues

---

> I'm a solo engineer maintaining a deterministic, high-performance teleport engine. Development time stays focused on Folia concurrency, proxy correctness, and zero-latency execution. Choose RTP-Pro if you care about engineering quality, RAM efficiency, and compounding TPS stability more than feature-padding or GUI gimmicks.
