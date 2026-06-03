# RTP — Random Teleport

The Folia-native, region-aware random teleport plugin for production Minecraft servers. Pre-generated, pre-validated locations resolve in 0-2 ticks with no main-thread chunk I/O and no region thrashing under load.

**Supported:** Spigot, Paper, Folia 1.20+, Fabric 1.20+/1.21+ (all stable).

🔗 **[Get RTP Pro on BuiltByBit](https://builtbybit.com/resources/leafrtp-pro.105418/)** — supports continued development and unlocks the Pro feature set.

[![Build](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml/badge.svg)](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml)
[![Release](https://img.shields.io/github/v/release/DailyStruggle/RTP)](https://github.com/DailyStruggle/RTP/releases)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-RTP-orange)](https://www.spigotmc.org/resources/leafrtp.94812/)
[![BuiltByBit](https://img.shields.io/badge/BuiltByBit-RTP%20Pro-blue)](https://builtbybit.com/resources/leafrtp-pro.105418/)
[![Modrinth](https://img.shields.io/badge/Modrinth-LeafRTP-green)](https://modrinth.com/plugin/leafrtp)

🔗 [SpigotMC Resource Page](https://www.spigotmc.org/resources/leafrtp.94812/)
🔗 [BuiltByBit Resource Page](https://builtbybit.com/resources/leafrtp-pro.105418/)
🔗 [Modrinth Resource Page](https://modrinth.com/plugin/leafrtp)

<div align="center">

<img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/teleport_title.webp" alt="An RTP teleport completing in 0 attempts, 49 ms" width="560" />

<sub>A real `/rtp` landing: a pre-verified destination served in <b>0 attempts, 49 ms</b> - no "Finding a safe location..." wait.</sub>

</div>

---

## Why RTP?

Legacy random-teleport plugins reroll random coordinates until one lands somewhere safe. Under load on Folia, each candidate triggers cross-region API calls (chunk lookups, block checks, biome queries), thrashing region threads and stretching teleports to seconds. RTP is designed the opposite way: every served location is pre-computed, pre-validated, and ready to hand out in 0-2 ticks.

- **Built for Folia, not retrofitted.** Region-aware scheduling end-to-end. No synchronous chunk I/O on the main thread, no foreign-region API calls in the hot path, no `ThreadAccessException` fallbacks dragging down teleport latency. Spigot and Paper adapters share the same bounded pipeline with platform-correct concurrency.
- **Pre-generation queue.** Safe locations are validated asynchronously *before* a player asks for one. Teleports resolve in 0-2 game ticks (≤ 100 ms) on average regardless of region size, biome complexity, or player count.
- **Bounded algorithms, not rerolling.** Location selection runs in deterministic time via a 2D-to-1D Archimedean-spiral mapping that preemptively subtracts known-invalid sectors from the candidate space. See the [mathematical writeup](https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/) and [ADR-001](docs/adr/ADR-001-archimedean-spiral-1d-mapping.md).
- **Spatial memory.** The plugin maps every region it serves and remembers what's invalid (oceans, solid blocks, claims, biome exclusions). `/rtp scan` proactively maps a region for deterministic performance and instant skips of unsafe territory. Memory survives server restarts.
- **Custom polygons.** Define teleport regions with any closed concave or convex polygon, not just circles and squares. Polygon shapes use the same spatial-memory and pre-validation pipeline as the built-in shapes.
- **Void-world and SkyBlock support.** The `vert:fixed` adjustor places players at a configured Y for void worlds, SkyBlock servers, and any region where the platform creates the foothold rather than the terrain.
- **Multi-region per world.** A single world can host any number of independent teleport regions, each with its own shape, distribution, permissions, queue, and economy cost.
- **Production diagnostics.** `MemoryTracker` accounts for chunk tickets and pipeline allocations on every exit path; spark-compatible profiling surface; per-region MSPT and queue telemetry for tuning under live load.
- **Extensible API.** Addon developers register custom shapes, vertical adjustors, and claim-check hooks without modifying the plugin.

---

## Supported Platforms

| Platform | Min Version | Notes |
|---|---|---|
| Spigot | 1.20 | Baseline adapter |
| Paper | 1.20 | Uses async chunk loading APIs |
| Folia | 1.20 | Full regional-thread scheduling support |
| Fabric | 1.20 | Native mod support - **first-class, stable**, tested regularly, at feature parity with the Bukkit family. Loom-remapped obf/unobf carriers cover 1.20.x, 1.21.x, and MC 26.x ([details](docs/dev/MULTI_PLATFORM_PLAN.md)) |
| Forge / NeoForge | — | No native adapter planned. Use a Bukkit-compatibility launcher (e.g. **Arclight** or **Mohist**) and run the Spigot/Paper build. |

**Runtime:** Java 21+

---

## Pregenerate First

For best results, pregenerate each RTP world (Chunky / WorldBorder) sized to your region radius before going live. RTP's pipeline is bounded by your server's chunk-generation throughput on first touch; pregeneration removes that bottleneck. See [QUICK_START Step 0](docs/admin/QUICK_START.md#step-0--prerequisites--pregenerate-the-world).

---

## Features

- **Shapes:** Circle, square, rectangle, and admin-authored **custom polygons** (convex or concave), each supporting flat, normal, and exponential distributions.
- **Distributions:** Tune where players land, such as uniform spread, center-weighted, or ring-shaped.
- **Biome filters:** Exclude specific biomes (e.g., ocean, nether_wastes) per region.
- **Void-world / SkyBlock support:** `vert:fixed` adjustor places players at a configured Y for void worlds, SkyBlock servers, and any region where the platform creates the foothold instead of the terrain.
- **Claim integration:** Works with GriefPrevention, WorldGuard, Towny, and any addon implementing the validation hook.
- **Economy support:** Optional Vault integration to charge players per teleport.
- **Per-region permissions:** Fine-grained permission nodes per region and per world.
- **Runtime config reload:** Adjust region settings by command without restarting the server.
- **Persistent state:** Spatial memory and region shape data survive server restarts, avoiding cold-start rebuild penalties.
- **Built-in diagnostics:** `MemoryTracker` accounts for chunk tickets and pipeline allocations on every exit path; spark-compatible profiling surface for production tuning.
- **Interactive `/rtp menu`:** A clickable in-game book UI for world/region selection and live config editing (Paper / Folia; chat-paginated fallback elsewhere).

<div align="center">

<img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/menu_1.png" alt="The /rtp menu admin panel book UI" width="280" /> <img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/menu_2.png" alt="The /rtp menu config-files browser" width="280" />

<sub>The clickable <code>/rtp menu</code> book UI: admin panel (left) and the in-game config-file browser (right).</sub>

<img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/mspt_map.webp" alt="In-game MSPT and heap visualization during a region scan" width="340" /> <img src="https://raw.githubusercontent.com/dailystruggle/RTP/V3/docs/assets/img/bad_locations_map.webp" alt="In-game land-vs-water map built from a region scan" width="340" />

<sub>Built-in scan visualizations: live MSPT / heap graphs (left) and a land-vs-water heatmap of the region (right).</sub>

</div>

---

## Shapes

Circle with exponential distribution (σ = 0.1, 1.0, 10.0):
![circle-exponential](https://user-images.githubusercontent.com/28832622/210043913-fd624a9f-8bdd-45de-b877-6a5f5e3bf40a.png)

Square with exponential distribution (σ = 0.1, 1.0, 10.0):
![square-exponential](https://user-images.githubusercontent.com/28832622/210043922-4d94e3d6-e829-4adc-a21a-74cce484f8e6.png)

Circle with normal distribution:
![circle-normal](https://user-images.githubusercontent.com/28832622/210043926-5c5013cf-032e-444c-9397-e381c17a4752.png)

Square with normal distribution:
![square-normal](https://user-images.githubusercontent.com/28832622/210043956-df964dde-4c70-460b-a377-ffd49a365e69.png)

Rectangle with flat distribution and rotation:
![rectangle-flat](https://user-images.githubusercontent.com/28832622/210043964-ca9725b8-be25-4e3c-a460-90f8b81326cb.png)

Custom shapes can be registered at runtime via the `rtp-api`. See the `addons/` directory for examples.

---

## Repository Structure

| Directory | Purpose |
|---|---|
| `rtp-api/` | Public API and shared models. Compile addons against this module only. |
| `commands-api/` | Unified multi-platform command framework. |
| `effects-api/` | Unified multi-platform visual/particle effects framework. |
| `rtp-core/` | Platform-agnostic core logic: regions, shapes, queues, database, memory tracking. |
| `rtp-plugin/` | Plugin entry point for Bukkit platforms. Bridges core with Spigot/Paper/Folia adapters. |
| `platforms/rtp-bukkit/` | Spigot platform adapter. |
| `platforms/rtp-paper/` | Paper platform adapter (async chunk loading). |
| `platforms/rtp-folia/` | Folia platform adapter (regional thread scheduling). |
| `platforms/rtp-fabric/` | Fabric platform adapter and mod entry point. |
| `addons/` | Example addons: Iris integration, Glide. (Claim plugin integrations are folded into the core). |
| `Python Test Scripts/` | Visualisation scripts for distribution math and geometry validation. |

---

## Documentation

**Choose your path:**

| I am a… | Start here |
|---|---|
| Server administrator installing or operating RTP | [docs/FOR_SERVER_ADMINS.md](docs/FOR_SERVER_ADMINS.md) |
| Addon developer extending RTP via `rtp-api` | [docs/FOR_ADDON_DEVELOPERS.md](docs/FOR_ADDON_DEVELOPERS.md) |
| Core contributor to `rtp-core`, `rtp-api`, or a platform adapter | [docs/FOR_CONTRIBUTORS.md](docs/FOR_CONTRIBUTORS.md) |

### Full Reference Index

See [docs/MAP.md](docs/MAP.md) for a one-line catalog of every document, or [docs/dev/INDEX.md](docs/dev/INDEX.md) for a task-to-file router. Root-level: [CONTRIBUTING.md](CONTRIBUTING.md), [CHANGELOG.md](CHANGELOG.md), [SECURITY.md](SECURITY.md).

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, code style rules, and the workflow for adding requirements.

New to the plugin? Start with [docs/FOR_SERVER_ADMINS.md](docs/FOR_SERVER_ADMINS.md). Want to extend it? See [docs/FOR_ADDON_DEVELOPERS.md](docs/FOR_ADDON_DEVELOPERS.md).

The short version:
1. `./gradlew build` (compile and run all tests)
2. `./gradlew spotlessApply` (format code before pushing)
3. If you add a requirement, add a row to `docs/dev/TRACEABILITY.md` in the same commit, as CI will fail otherwise.

---

## License

RTP is dual-licensed (open-core; see [ADR-061](docs/adr/ADR-061-open-core-dual-licensing.md)):

- **MIT** ([`LICENSE-MIT`](LICENSE-MIT)): the `rtp-api` and `rtp-core` modules and the **RTP (lite)** binary distribution. These may be used, modified, and redistributed, including commercially.
- **PolyForm Noncommercial 1.0.0** ([`LICENSE`](LICENSE)): all other source, i.e. the Pro-only features and the Pro plugin assembly. Provided for personal, noncommercial use only.

Where a module or file ships its own `LICENSE` or SPDX header, that grant governs the file; otherwise the root PolyForm Noncommercial terms apply.
