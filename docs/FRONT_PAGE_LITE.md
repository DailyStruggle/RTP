<!--
Markdown equivalent of FRONT_PAGE_LITE.bbcode (RTP free / lite front page).
Keep in sync with the BBCode source when copy changes.
Conversion-optimized layout: hook → proof → install → CTA → deep dive.
-->

# RTP — Random Teleport, the engine

*Send your players to a safe, random spot — fast, fair, and engineered not to lag your server.*

**100% Free.** Same engine as the paid **RTP-Pro** build. No paywalled `/rtp`, no nag screens, no ads — just anonymous [bStats](https://bstats.org/) usage stats (server-admin opt-out via `plugins/bStats/config.yml`).

[![Version](https://img.shields.io/github/v/release/dailystruggle/RTP?label=Version&style=for-the-badge&color=2C3E50)](https://github.com/dailystruggle/RTP)
[![License](https://img.shields.io/github/license/dailystruggle/RTP?label=License&style=for-the-badge&color=2C3E50)](https://github.com/dailystruggle/RTP)

---

### Why operators pick RTP

On **Paper 1.21**, measured on the in-repo benchmark harness, two clients spamming `/rtp` back-to-back:

| Plugin           | TP/s     | Worst tick (MSPT p99) | CPU per teleport |
|------------------|----------|-----------------------|------------------|
| **🧪 RTP**       | **19.8** | **4 ms**              | **16.9 ms**      |
| 🧪 JakesRTP      | 20.0     | 70 ms                 | 26.0 ms          |
| 🧪 BetterRTP     | 7.1      | 771 ms                | 53.6 ms          |
| 🧪 HuskHomes RTP | 6.2     | 335 ms                | 52.2 ms          |

Same throughput as the next-best plugin, **~17× lower worst-case tick spike, 35% less CPU per teleport.** On Spigot, RTP's worst tick stays at 3 ms while competitors spike past 3 seconds. Reproduce on your own rig: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3-beta/helpers/StressTestRTP).

---

### Install (30 seconds)

1. Drop `RTP-x.y.z.jar` into `plugins/`.
2. Start the server. A `default` region is generated for you.
3. Type **`/rtp`**.

That's it. Tune `plugins/RTP/config.yml` and `plugins/RTP/regions/*.yml` later. Full admin guide is auto-unpacked into `plugins/RTP/docs/` on first run, and lives online at the [**admin guide**](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/admin/FOR_SERVER_ADMINS.md).

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
| **Spigot** (+ Spigot forks) | ✅ Supported | Off-tick `.mca` Anvil pre-filter → Paper-class throughput on plain Spigot. |
| **Arclight / Mohist** (Forge / NeoForge bridges) | ✅ Officially supported | Use the Spigot/Paper jar. The recommended way to run on Forge/NeoForge. |
| **Folia** | ❌ Not in this build | Folia adapter ships in **RTP-Pro**. |
| **Multi-server / proxy** (Velocity, BungeeCord) | ❌ Not in this build | Cross-server queue ships in **RTP-Pro** (beta.3+). |
| **Fabric** | 🧪 Functional (unstable frontier) | First-class in-scope platform; stabilization ongoing. |
| **Native Forge / NeoForge** | 🔁 Use Arclight / Mohist | No native adapter planned. |

---

<details>
<summary><b>📊 Full benchmark vs. 4 other RTP plugins on Paper, Spigot, and Folia</b></summary>

*2 OPed real clients spamming `/rtp` back-to-back, queues enabled where the plugin offers them, cooldowns/delays zeroed. RTP and RTP-Pro share the same engine on Spigot/Paper; only the Folia adapter differs.*

**Confidence legend:** 🧪 = measured locally · 📖 = read from plugin docs · ❓ = inferred from architecture.

**Metrics:** Throughput (TP/s, higher better) · MSPT p99 (worst 1-in-100 main-thread tick in ms, lower better) · Min TPS (lowest TPS observed; 20.00 = no hiccup) · CPU / TP (main-thread CPU per successful teleport).

**Paper 1.21.11** — canonical head-to-head.

| Plugin       | TP/s | MSPT p99 (ms) | Min TPS | CPU / TP (ms) |
|--------------|------|---------------|---------|----------------|
| **🧪 RTP**   | **19.8** | **4** | **20.00** | **16.9** |
| 🧪 JakesRTP  | 20.0 | 70  | 20.00 | 26.0 |
| 🧪 BetterRTP | 7.1  | 771 | 20.00 | 53.6 |
| 🧪 HuskHomes | 6.2  | 335 | 20.00 | 52.2 |

**Spigot 1.20.1** — Spigot's platform-wide chunk-gen ceiling caps everyone in the 1–1.5 TP/s range during the burst; the latency tail is what matters.

| Plugin       | TP/s | MSPT p99 (ms) | Min TPS | CPU / TP (ms) |
|--------------|------|---------------|---------|----------------|
| **🧪 RTP**   | **1.52** | **3** | **6.4** | 572 |
| 🧪 JakesRTP  | 1.04 | 2 252 | 7.5* | —* |
| 🧪 BetterRTP | 1.33 | 3 790 | 2.18 | 584 |
| 🧪 HuskHomes | 0.93 | 4 939 | 2.59 | 868 |

**Folia 1.21.11** — *RTP-Pro only; the Folia adapter is not bundled in the free build.*

| Plugin         | TP/s | Region MSPT p99 (ms) | Success | CPU / TP (ms) |
|----------------|------|----------------------|---------|----------------|
| **🧪 RTP-Pro** | **9.87** | **157** | **99.97 %** | **18.0** |
| 🧪 BetterRTP   | 3.82 | 1 200 | 100 % | 34.8 |
| 🧪 HuskHomes   | 3.32 | 901  | 100 % | 28.4 |

**Architecture support matrix** — *(Spigot / Paper-and-forks / Folia)*

- **RTP** — ✅🧪 Off-tick Anvil pre-filter · ✅🧪 Fully async via `getChunkAtAsync` · ❌ See RTP-Pro
- **RTP-Pro** — ✅🧪 Same as base · ✅🧪 Same as base · ✅🧪 Region Scheduler + off-tick pre-filter, no 1-tick stalls
- **BetterRTP** — ⚠️📖 Sync chunk load on miss · ⚠️📖 No off-tick safety pre-filter · ✅🧪 Folia 1.21.11 functional, p99 ~1.2 s
- **EzRTP** — ❌🧪 `NoSuchMethodError` on Spigot 1.20.1 (Paper-only API) · ✅📖 Works on Paper · ❓📖 Not advertised
- **AsyRTP** — ❌🧪 Fails to enable on Spigot 1.20.1 (Paper-only API in `onEnable`) · ✅📖 Paper · ✅📖 Folia
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
<summary><b>📚 Commands, placeholders, soft-deps</b></summary>

**Commands** (full reference: [admin guide](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/admin/FOR_SERVER_ADMINS.md))

- `/rtp` — teleport to the default region for your current world.
- `/rtp [parameter]:[value]` — specify `region:`, `world:`, `player:`, or temporary overrides.
- `/rtp reload` — reload all configuration from disk.
- `/rtp scan start|pause|resume|reset|cancel` — pre-warm spatial memory by walking a region (renamed from `/rtp fill` in 2.x).
- `/rtp config` — interactive config editor. *Experimental in `3.0.0-beta.1`*; edit YAML directly for production.

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
GitHub issue with server version, RTP version, platform, relevant config files, and the error log section. See the [admin guide](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/admin/FOR_SERVER_ADMINS.md) for the full reproduction template.

</details>

<details>
<summary><b>🧪 Engineering receipts</b></summary>

Every claim on this page is anchored in the repo:

- **Reproducible benchmarks** with raw artifacts: [`helpers/StressTestRTP/`](https://github.com/dailystruggle/RTP/tree/V3-beta/helpers/StressTestRTP)
- **Requirements traced to tests** — every REQ-* (including the S-001…S-007 prohibition guards: no main-thread chunk loading, no silently swallowed teleport failures, …) has an implementing class and regression test: [`TRACEABILITY.md`](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/dev/TRACEABILITY.md)
- **28+ ADRs**, dated and numbered, covering platform-in-scope decisions, the Anvil subsystem, the Brigadier bridge, supersession trails: [`docs/adr/`](https://github.com/dailystruggle/RTP/tree/V3-beta/docs/adr)
- **bStats** enabled — anonymous usage stats help prioritize platform work.

</details>

<details>
<summary><b>⚠️ Known limitations in 3.0.0-beta.2</b></summary>

- Free build does not ship Folia, SQL/Redis, multi-server, Vault economy, multilingual `lang/**`, or the tag/state-predicate `safety.yml` grammar — all are in **RTP-Pro**.
- Fabric is functional but an unstable frontier — usable, but expect rough edges; production-critical servers should prefer Paper/Folia/Spigot for now.
- `safety.yml` here accepts flat material names (`LAVA`, `MAGMA_BLOCK`, `CACTUS`, `FIRE`). Unknown materials log one warning, never silently dropped.
- `/rtp config` is experimental; edit YAML directly for production.
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

- [**Admin guide**](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/admin/FOR_SERVER_ADMINS.md) — install, configure, command reference
- [**Addon developer guide**](https://github.com/dailystruggle/RTP/blob/V3-beta/docs/dev/FOR_ADDON_DEVELOPERS.md) — API and examples
- [**Changelog & roadmap**](https://github.com/dailystruggle/RTP/blob/V3-beta/CHANGELOG.md)
- [**Source on GitHub**](https://github.com/dailystruggle/RTP) — star, watch, contribute, file issues

*Support for the free build is community-tier and best-effort. Include server version, RTP version, platform, configs, and `server.log` excerpt when reporting bugs. Priority and response-time guarantees are reserved for **RTP-Pro**.*
