<!--
Markdown equivalent of FRONT_PAGE_LITE.bbcode (RTP free / lite front page).
Keep in sync with the BBCode source when copy changes.
Conversion-optimized layout: hook → proof → install → CTA → deep dive.
-->

<div align="center">

# RTP — Random Teleport, the engine

### Deterministic, low-latency Random Teleport for real servers
*Same engine as the paid **RTP-Pro** build, free and unrestricted for single-server Bukkit/Spigot/Paper deployments.*

**100% Free · No paywalled `/rtp` · No nag screens · No ads** — anonymous [bStats](https://bstats.org/) only (opt-out via `plugins/bStats/config.yml`).

[![Version](https://img.shields.io/github/v/release/dailystruggle/RTP?label=Version&style=for-the-badge&color=2C3E50)](https://github.com/dailystruggle/RTP) [![License](https://img.shields.io/github/license/dailystruggle/RTP?label=License&style=for-the-badge&color=2C3E50)](https://github.com/dailystruggle/RTP) [![Java](https://img.shields.io/badge/Java-21%2B-orange?style=for-the-badge&color=2C3E50)](https://adoptium.net/)

</div>

---

### Why operators pick RTP

**RTP is the fastest, lowest-latency free Random Teleport plugin for Bukkit-native Minecraft servers (Paper, Bukkit, Arclight, Mohist, and other Bukkit-family forks), with Fabric supported as a first-class platform.** In plain operator terms:

- **No lag spikes when players spam `/rtp`.** Worst-case main-thread tick holds at 3–4 ms (vs. 54–852 ms for the next plugins) - your TPS stays at 20.00 during a teleport burst.
- **Instant teleports, no "Finding a safe location..." wait.** Pre-verified location queue serves `/rtp` in one tick instead of loading chunks on demand.
- **Bukkit-family servers covered end-to-end.** Off-tick `.mca` Anvil pre-filter keeps safety evaluation off the tick loop, so the recommended Paper config below ships sub-5 ms worst ticks under sustained `/rtp` burst.
- **Eight claim-plugin integrations bundled** (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect) - no add-ons to install.
- **Audited safety**: no unsafe blocks, no force-loaded chunks, no claim-bypassing teleports, no silent failures (REQ-RTP-S-001..S-007).
- **Truly free.** No paywalled `/rtp`, no nag screens, no ads - only anonymous bStats usage stats (admin opt-out).

On **Paper 1.20.1**, two clients spamming `/rtp` back-to-back at the recommended config (`cacheCap: 100, period: 1`); reproduced n=2 (±2.5 % TP/s, ±1 ms p99):

| Plugin           | TP/s     | Worst tick (MSPT p99) | CPU per teleport |
|------------------|----------|-----------------------|------------------|
| **🧪 RTP**       | **19.9** | **3–4 ms**            | **14–17 ms**     |
| 🧪 JakesRTP      | 19.9     | 54–89 ms              | 19–26 ms         |
| 🧪 BetterRTP     | 7.3      | 722–852 ms            | 43–58 ms         |
| 🧪 HuskHomes RTP | 6.2      | 313–372 ms            | 47–52 ms         |

Same throughput as the next-best plugin, **~18× lower worst-case tick spike, ~30% less CPU per teleport** — and the only row with a published reproducibility band. Reproduce on your own rig: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3-beta/helpers/StressTestRTP).

<details open>
<summary><b>🧪 Engineering receipts (proof, not marketing)</b></summary>

Every number on this page is anchored in the repo:

- **Reproducible benchmarks** with raw CSVs and per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3-beta/helpers/StressTestRTP)
- **Requirements traced to tests** — every REQ-* (including the S-001…S-007 prohibition guards: no main-thread chunk loading, no silently swallowed teleport failures, …) has an implementing class and regression test: [`TRACEABILITY.md`](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/dev/TRACEABILITY.md)
- **28+ ADRs**, dated and numbered, covering platform-in-scope decisions, the Anvil subsystem, the Brigadier bridge, supersession trails: [`docs/adr/`](https://github.com/dailystruggle/RTP/tree/V3-beta/docs/adr)
- **bStats** enabled — anonymous usage stats help prioritize platform work.

</details>

---

### Operator fit (30-second check)

A few hard requirements. If any are a **no**, EssentialsX `/rtp` or HuskHomes are fine free alternatives.

- ✅ **Java 21+** on your host (REQ-RTP-SYS-001, non-negotiable).
- ✅ **Paper or a Bukkit-family fork** (Arclight / Mohist supported for Forge / NeoForge bridges). Fabric is supported and regularly tested, with its featureset lagging the Bukkit family by a release or two.
- ✅ **In-game editing or YAML, your call.** Browse and tune config from the clickable `/rtp menu` (book on Paper / Folia, chat-paginated elsewhere), or edit the plain YAML files directly and version-control them.
- ❌ Folia, proxy/cross-server, SQL/Redis, Vault economy → those live in **RTP-Pro**.

---

### Install (30 seconds)

1. Drop `RTP-x.y.z.jar` into `plugins/`.
2. Start the server. A `default` region is generated for you.
3. Type **`/rtp`**.
4. Run **`/rtp menu`** to browse regions, worlds, and configuration in-game (clickable book on Paper / Folia, chat-paginated fallback elsewhere).

That's it. Tune `plugins/RTP/config.yml` and `plugins/RTP/regions/*.yml` later — via `/rtp menu` or by editing the YAML directly. The full admin guide is auto-unpacked into `plugins/RTP/docs/` on first run, and lives online at the [**admin guide**](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/FOR_SERVER_ADMINS.md).

### Tuning for Paper (what the benchmark above assumes)

Defaults (`cacheCap: 10`, `period: 10`) are tuned for low-traffic servers. For sustained `/rtp` burst on Paper, raise the L1 queue depth and refill cadence:

```yaml
# plugins/RTP/regions/default.yml
cacheCap: 100         # L1 queue depth
activeChunkCap: 100   # L2 queue depth

# plugins/RTP/performance.yml
period: 1             # background scan/refill: every tick
```

This is the configuration that produces the headline numbers above. Background CPU rises proportionally to refill rate — back off `period` toward `10` if your server has spare TPS but limited CPU headroom.

---

### What's in the box

- 🌀 **Deterministic spiral selection** — bounded math, no unbounded re-roll loops. Predictable on huge worlds.
- 🧠 **Spatial memory that persists across restarts** — RTP *learns* which sectors keep failing and stops trying them.
- ⏱️ **Pre-generated location queue** — most `/rtp` calls serve from a ready, chunk-loaded destination.
- 🛡️ **Safety pipeline** — radius check, invulnerability timer, optional landing platform, movement / damage cancel timers, material allow/deny list.
- 🌍 **Per-region, per-world config** — shapes (square / circle / rectangle), curve weighting, vertical adjustors, sky-light check, world overrides, hot-reloadable YAML.
- ✨ **Effects on every lifecycle phase** — particles, sounds, fireworks, potion, note effects via the in-tree `effects-api`, gated by `rtp.effects.<name>` permissions.
- 🔗 **Eight claim-plugin integrations bundled** — GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect.
- 🧩 **PlaceholderAPI, ProtocolLib, custom generators** — Iris, Terra, datapacks work out of the box; modded biome IDs preserved.
- 🛠️ **Public `rtp-api`** — same surface as Pro. Trigger RTP from a GUI, NPC, or quest; build your own UX without forking.

---

### Platform support

| Platform | Status | Notes |
|---|---|---|
| **Paper** (+ forks: Purpur, Pufferfish, Leaf, Leaves, DivineMC) | ✅ Recommended | Fully async via native `getChunkAtAsync`. |
| **Other Bukkit-family servers** (Spigot, CraftBukkit-derived forks) | ✅ Supported | Off-tick `.mca` Anvil pre-filter keeps candidate verification off the tick loop. |
| **Arclight / Mohist** (Forge / NeoForge bridges) | ✅ Officially supported | Use the standard Bukkit jar. The recommended way to run on Forge/NeoForge. |
| **Folia** | ❌ Not in this build | Folia adapter ships in **RTP-Pro**. |
| **Multi-server / proxy** (Velocity, BungeeCord) | ❌ Not in this build | Cross-server queue ships in **RTP-Pro** (beta.3+). |
| **Fabric** | ✅ Supported | First-class in-scope platform; tested regularly. Featureset lags the Bukkit family by a release or two. |
| **Native Forge / NeoForge** | 🔁 Use Arclight / Mohist | No native adapter planned. |

---

<details>
<summary><b>📊 Full benchmark vs. 7 other RTP plugins on Paper (+ Folia, RTP-Pro)</b></summary>

*2 OPed real clients spamming `/rtp` back-to-back, queues enabled where the plugin offers them, cooldowns/delays zeroed. RTP and RTP-Pro share the same engine on Bukkit-family servers; only the Folia adapter differs.*

**Confidence legend:** 🧪 = measured locally · 📖 = read from plugin docs · ❓ = inferred from architecture.

**Metrics:** Throughput (TP/s, higher better) · MSPT p99 (worst 1-in-100 main-thread tick in ms, lower better) · Min TPS (lowest TPS observed; 20.00 = no hiccup) · CPU / TP (main-thread CPU per successful teleport).

**Paper 1.20.1 / 1.21.11** — canonical head-to-head. Eight plugins, same harness, same world, same two OPed clients.

| Plugin                  | TP/s     | MSPT p99 (ms) | Min TPS   | CPU / TP (ms) | Success    |
|-------------------------|----------|---------------|-----------|---------------|------------|
| **🧪 RTP**              | **19.8** | **4**         | **20.00** | **16.9**      | **100 %**  |
| 🧪 JakesRTP             | 20.0     | 70            | 20.00     | 26.0          | 100 %      |
| 🧪 BetterRTP            | 7.3      | 852           | 20.00     | 53.6          | 100 %      |
| 🧪 HuskHomes            | 6.2      | 372           | 20.00     | 52.2          | 100 %      |
| 🧪 AdvancedRTP          | 2.16     | 2 100         | 19.95     | 92.1          | 96.3 %     |
| 🧪 EzRTP                | 1.76     | 2 903         | 19.95     | 139.6         | 100 %      |
| 🧪 AsyRTP               | 1.67     | 4 534         | 19.95     | 38.8          | 100 %      |
| 🧪 EssentialsX `/tpr`   | 0.96     | 4 504         | 19.95     | 88.9          | 75.9 % §   |

§ EssentialsX `/tpr` is a teleport-*request* command (handshake + accept), not a teleport-*do* command; the harness's 5 s per-attempt deadline times out a fraction of the request-accept latencies. Numbers are dispatch-shaped, not plugin-broken.


**Folia 1.21.11** — *RTP-Pro only; the Folia adapter is not bundled in the free build.*

| Plugin         | TP/s | Region MSPT p99 (ms) | Success | CPU / TP (ms) |
|----------------|------|----------------------|---------|----------------|
| **🧪 RTP-Pro** | **9.87** | **157** | **99.97 %** | **18.0** |
| 🧪 BetterRTP   | 3.82 | 1 200 | 100 % | 34.8 |
| 🧪 HuskHomes   | 3.32 | 901  | 100 % | 28.4 |

**Architecture support matrix** — *(Bukkit / Paper-and-forks / Folia)*

- **RTP** — ✅🧪 Off-tick Anvil pre-filter · ✅🧪 Fully async via `getChunkAtAsync` · ❌ See RTP-Pro
- **RTP-Pro** — ✅🧪 Same as base · ✅🧪 Same as base · ✅🧪 Region Scheduler + off-tick pre-filter, no 1-tick stalls
- **BetterRTP** — ⚠️📖 Sync chunk load on miss · ⚠️📖 No off-tick safety pre-filter · ✅🧪 Folia 1.21.11 functional, p99 ~1.2 s
- **EzRTP** — ❌🧪 `NoSuchMethodError` on Bukkit 1.20.1 (Paper-only API) · ✅📖 Works on Paper · ❓📖 Not advertised
- **AsyRTP** — ❌🧪 Fails to enable on Bukkit 1.20.1 (Paper-only API in `onEnable`) · ✅📖 Paper · ✅📖 Folia
- **SorekillRTP** — ⚠️❓ Designed for Redis cross-server, not single-server perf · ⚠️❓ Same · ❓📖
- **AdvancedRTP** — ⚠️📖 Safety-first, sync chunk load · ⚠️📖 Same · ❌📖
- **JakesRTP** — ⚠️📖 Async via flag; 10-slot cache · ✅📖 Same · ❌📖 No Folia
- **EssentialsX `/rtp`** — ✅📖 Main-thread chunk load per candidate · ✅📖 Same · ❌📖
- **HuskHomes RTP** — ✅📖 Bundled with homes suite · ⚠️📖 Same · ✅🧪 Folia functional, p99 ~900 ms

**Caveats.** 2 clients only (the number is a floor, not a ceiling); hardware, view distance, world state, and other plugins will move them. Paper RTP row reproduced n=2; other rows are n=1 on a single rig. Competitor plugins update frequently — corrections welcome via GitHub issue with a contradicting repro or doc link. Feature breadth, GUI, and claim-integration counts are not benchmarked; several competitors trade speed for those, which is a legitimate design choice.

Full methodology, raw CSVs, per-run analyses: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3-beta/helpers/StressTestRTP).

</details>

<details>
<summary><b>🎬 Demonstration videos (RTP 2.x — same principles, older numbers)</b></summary>

*Recorded on the original RTP 2.x resource. Spatial learning and queue-paced throughput are unchanged in 3.0; the on-screen numbers predate the current release.*

- **Learning & deterministic-time test** — [YouTube: PY6W7DikhAI](https://www.youtube.com/watch?v=PY6W7DikhAI). The plugin "learns" bad sectors; selection time converges to a predictable lower bound.
- **Command-response & throughput test** — [YouTube: l6KSvKxsKAQ](https://www.youtube.com/watch?v=l6KSvKxsKAQ). Pre-generated queue → near-instant `/rtp` response under load.

</details>

<details>
<summary><b>🏛️ Architecture — five pillars + chunk-loading by platform</b></summary>

**Five pillars:**

- **Deterministic spiral selection.** Bounded math, uniform player distribution even on massive worlds; no unbounded re-roll loops.
- **Platform-native performance.** Each Bukkit-family server gets the threading model that fits its API surface, not the lowest-common-denominator.
- **Active resource watchdog.** A background sweep reclaims chunk tickets from abandoned teleports via WeakReferences; nothing stays force-loaded past its reservation window.
- **Direct region I/O pre-filter.** `.mca` region files parsed off-tick on a background pool, biomes included (custom namespaced biomes like Iris too). Async safety evaluation across the whole Bukkit family, including servers without an async chunk API.
- **Admin-driven background mapping.** `/rtp scan start|pause|resume|reset|cancel` walks the spiral during idle periods so spatial memory accumulates without player traffic.

**Chunk loading, by platform:**

- **Paper** — `World#getChunkAtAsync` used directly. No main-thread fallback. Reference platform.
- **Paper forks** (Purpur, Pufferfish, Leaf, Leaves, DivineMC) — inherit the Paper code path.
- **Other Bukkit-family servers** — `.mca` region files parsed off-tick (`isAir`, `isSafe`, surface-height, sky-light, biome). Async safety evaluation even where the platform lacks a native async chunk API.
- **Mohist / Arclight** — officially supported. Uses the off-tick Anvil pre-filter code path.
- **Fabric** — in-tree adapter, functional (unstable frontier).

**Operator takeaway:** Paper runs chunk I/O fully off-tick. On other Bukkit-family servers the Anvil pre-filter covers the common case; rare main-thread fallback is bounded by your configured tick-budget; no platform silently blocks the tick loop without reporting it.

</details>

<details>
<summary><b>🧮 Spatial memory — why the longer it runs, the faster it gets</b></summary>

Without spatial indexing, teleportation is a guessing game and the numbers are ugly. A typical Overworld is only **~45% safely teleportable**† (oceans, rivers, ravines, steep terrain eat the rest); the Nether is dominated by lava seas; the End is *almost entirely* void. Standard RTPs pay full chunk-load cost to rediscover this one candidate at a time, forever.

RTP plots your world's geometry as it evaluates candidates. When it hits a massive ocean or unsafe biome, it remembers that sector and shrinks the searchable area, so known-bad sectors never get loaded again. The longer it runs, the faster it gets — and `/rtp scan` keeps the learning going during idle periods.

**Persistence across restarts:** learned bad-sector state survives a JVM bounce via on-disk flat-file storage. (Shared multi-server state through H2 / SQLite / MySQL / PostgreSQL is **RTP-Pro**.)

<sub>† Local-profile estimate from a vanilla 1.21 seed set; Nether and End are still qualitative.</sub>

</details>

<details>
<summary><b>📚 Commands, placeholders, soft-deps</b></summary>

**Commands** (full reference: [admin guide](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/FOR_SERVER_ADMINS.md))

- `/rtp` — teleport to the default region for your current world.
- `/rtp [parameter]:[value]` — specify `region:`, `world:`, `player:`, or temporary overrides.
- `/rtp reload` — reload all configuration from disk.
- `/rtp scan start|pause|resume|reset|cancel` — pre-warm spatial memory by walking a region (renamed from `/rtp fill` in 2.x).
- `/rtp menu` — interactive admin menu; book on Paper / Folia, chat-paginated fallback elsewhere. Hardened in `3.0.0-beta.3`.

**PlaceholderAPI**

- `%rtp_player_status%` — idle, waiting, teleporting, …
- `%rtp_total_queue_length%`, `%rtp_public_queue_length%`, `%rtp_personal_queue_length%`
- `%rtp_teleport_world%`, `%rtp_teleport_x%`, `%rtp_teleport_y%`, `%rtp_teleport_z%`

**Soft dependencies (all optional):** PlaceholderAPI, ProtocolLib. *PaperLib is no longer required. Vault economy ships in **RTP-Pro**.*

</details>

<details>
<summary><b>❓ FAQ</b></summary>

**Why is this so much faster than other RTP plugins?**
Most `/rtp` calls serve from a pre-warmed queue — chunks are already loaded and safety-checked before you type the command. Two design choices make that queue cheap to keep full: a **persistent spatial memory** per region (the plugin remembers which sectors of the world failed safety checks, so the spiral selector skips known-bad ground instead of rerolling forever), and an **off-tick async pre-filter** (Anvil region files are read directly to reject unsafe biomes/blocks *before* any chunk is loaded, so candidate verification never blocks the main thread). The pre-warmed queue is just the visible tip — the spatial memory keeps candidate selection bounded, and the async pre-filter keeps verification off the tick loop.

**Does it work with Iris / Terra / custom datapack generators?**
Yes. Region files are read directly, so modded and namespaced biome and block IDs are preserved. No configuration needed.

**What's the difference between RTP and RTP-Pro?**
Same engine, same source tree. **RTP-Pro** adds the Folia adapter, multi-server / proxy support (beta.3+), SQL/Redis backends, Vault economy, multilingual `lang/**`, the richer block-tag/state-predicate `safety.yml` grammar, and priority support. The free build is fully sufficient for single-server deployments. Drop in the Pro jar later — same config, same data, same commands.

**I'm on Forge / NeoForge.**
Run **Arclight** or **Mohist** (officially supported) and use this jar. A native Forge adapter is not planned.

**Memory and MSPT — should I worry?**
RTP trades a bounded amount of RAM (the queue, bounded by cacheCap) for speed. TPS should not drop below ~19 from RTP alone on a healthy server; MSPT spikes during new-area generation are expected — that's the cost of generating chunks, not RTP.

**How do I report a bug?**
GitHub issue with server version, RTP version, platform, relevant config files, and the error log section. See the [admin guide](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/FOR_SERVER_ADMINS.md) for the full reproduction template.

</details>

<details>
<summary><b>📞 Community Support Policy (read before filing an issue)</b></summary>

Support for the free build is **community-tier and best-effort** — a solo maintainer ships fixes when properly-reported issues land. Respecting this is how the plugin stays fast and current.

- **Support covers bugs and configuration questions,** after you've read the admin guide.
- **Bug reports need a reproduction:** server version, RTP version, platform (Paper / Bukkit-family fork), `config.yml`, `regions/`, `safety.yml`, and the relevant `server.log` section. Reports without these are asked for them once, then closed.
- **"It doesn't work" is not a bug report.** Tell me what you did, what you expected, and what actually happened.
- **Unsupported on the free tier:** native Forge / NeoForge (use Arclight / Mohist), plugin conflicts I can't reproduce, general MC-server admin questions.
- **Response time:** no SLA on the free build. Critical safety issues (S-001…S-007 violations) jump the queue regardless. Guaranteed response windows are reserved for **RTP-Pro**.
- **Feature requests** via GitHub issues. Priority follows the published roadmap, not ticket volume.

</details>

<details>
<summary><b>⚠️ Known limitations in 3.0.0-beta.3</b></summary>

- Free build does not ship Folia, SQL/Redis, multi-server, Vault economy, multilingual `lang/**`, or the tag/state-predicate `safety.yml` grammar — all are in **RTP-Pro**.
- `safety.yml` here accepts flat material names (`LAVA`, `MAGMA_BLOCK`, `CACTUS`, `FIRE`). Unknown materials log one warning, never silently dropped.
- Edits to `safety.yml` and biome filters do not yet invalidate the persisted shape cache — workaround: `/rtp scan reset <region>`.
- Emergency landing platform default is now `platformRadius: -1` (disabled). Set to `0` or higher to restore legacy 2.x behavior.

Live list: [CHANGELOG](https://github.com/dailystruggle/RTP/blob/V3-beta/CHANGELOG.md#known-issues).

</details>

---

### Need Folia, a proxy network, or paid support?

**RTP-Pro** is a drop-in upgrade — same configuration, same data files, same commands. It adds:

- **Folia** adapter (Region Scheduler + off-tick pre-filter, no 1-tick stalls)
- **Multi-server / proxy** support (Velocity, BungeeCord) — planned in `3.0.0-beta.3`
- SQL / Redis shared-state backends (H2, SQLite, MySQL, PostgreSQL, Jedis)
- **Vault** economy, multilingual `lang/**`, login-reserve cache, visitor mode
- The richer `safety.yml` grammar: vanilla block tags (`#minecraft:leaves`), state predicates (`OAK_SLAB[waterlogged=true]`), wildcards (`*[waterlogged=true]`)
- Earliest releases on each version + priority support within a documented response window

---

### Links

- [**Admin guide**](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/FOR_SERVER_ADMINS.md) — install, configure, command reference
- [**Addon developer guide**](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/FOR_ADDON_DEVELOPERS.md) — API and examples
- [**Changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3-beta/CHANGELOG.md)
- [**Source on GitHub**](https://github.com/dailystruggle/RTP) — star, watch, contribute, file issues

---

> I'm a solo engineer maintaining a deterministic, high-performance teleport engine. The free build is the same core code that ships in RTP-Pro — released openly because single-server Bukkit/Paper operators deserve a fast, audited `/rtp` without a paywall. If RTP saves your TPS, a star on GitHub or a properly-filed bug report goes a long way.
