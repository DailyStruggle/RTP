# RTP — Random Teleport

**Current Version:** `3.0.0-beta`

A high-performance random teleportation plugin for Bukkit-derived Minecraft servers (Spigot, Paper, Folia).

[![Build](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml/badge.svg)](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml)
[![Release](https://img.shields.io/github/v/release/DailyStruggle/RTP)](https://github.com/DailyStruggle/RTP/releases)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-RTP-orange)](https://www.spigotmc.org/resources/rtp.94812/)

🔗 [SpigotMC Resource Page](https://www.spigotmc.org/resources/rtp.94812/)

---

## Why RTP?

Most random teleport plugins work by repeatedly rolling random coordinates until they find a valid spot, a naive approach that can stall the server under load. RTP takes a different approach, rooted in a [mathematical proof](https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/) that maps the 2D teleport region onto a 1D curve, which eliminates rerolling entirely and guarantees uniform spatial distribution:

- **Bounded algorithms, not rerolling.** Location selection runs in deterministic time (O(log n)) by preemptively subtracting known-invalid sectors from the candidate space. See the [original mathematical writeup](https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/) and [ADR-001](docs/adr/ADR-001-archimedean-spiral-1d-mapping.md) for the full rationale.
- **Pre-generation queue.** Safe locations are validated asynchronously *before* a player asks for one, so teleports resolve in 0–2 game ticks (≤ 100 ms) on average.
- **Spatial Memory.** The plugin maps the entire teleport region and remembers invalid areas, such as oceans, solid blocks, and claims. Use `/rtp scan` to proactively map out a region, ensuring deterministic performance and instant skips of unsafe territory.
- **Multi-region support.** A single world can have any number of independent teleport regions, each with its own shape, distribution, permissions, and queue.
- **Platform-aware concurrency.** Separate adapters for Spigot, Paper, and Folia ensure correct thread-safety on each server type, including Folia's region-based multithreading.
- **Extensible API.** Addon developers can register custom shapes, vertical adjustors, and claim-check hooks without modifying the plugin.

---

## Supported Platforms

| Platform | Min Version | Notes |
|---|---|---|
| Spigot | 1.20 | Baseline adapter |
| Paper | 1.20 | Uses async chunk loading APIs |
| Folia | 1.20 | Full regional-thread scheduling support |
| Fabric | 1.21 | Native mod support (Experimental) |

**Runtime:** Java 21+

---

## Features

- **Shapes:** Circle, square, rectangle, each supporting flat, normal, and exponential distributions.
- **Distributions:** Tune where players land, such as uniform spread, center-weighted, or ring-shaped.
- **Biome filters:** Exclude specific biomes (e.g., ocean, nether_wastes) per region.
- **Claim integration:** Works with GriefPrevention, WorldGuard, Towny, and any addon implementing the validation hook.
- **Economy support:** Optional Vault integration to charge players per teleport.
- **Per-region permissions:** Fine-grained permission nodes per region and per world.
- **Runtime config reload:** Adjust region settings by command without restarting the server.
- **Persistent state:** Spatial memory and region shape data survive server restarts, avoiding cold-start rebuild penalties.

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
| `rtp-spigot/` | Spigot platform adapter. |
| `rtp-paper/` | Paper platform adapter (async chunk loading). |
| `rtp-folia/` | Folia platform adapter (regional thread scheduling). |
| `rtp-fabric/` | Fabric platform adapter and mod entry point. |
| `addons/` | Example addons: Iris integration, Glide, claim plugin hooks. |
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

| Document | Purpose |
|---|---|
| [docs/admin/QUICK_START.md](docs/admin/QUICK_START.md) | Step-by-step installation, first region setup, and common troubleshooting. |
| [docs/admin/COMMANDS.md](docs/admin/COMMANDS.md) | Every `/rtp` subcommand, argument, and permission node explained. |
| [docs/admin/CONFIGURATION.md](docs/admin/CONFIGURATION.md) | Every config key across all files with type, default, and description. |
| [docs/admin/FAQ.md](docs/admin/FAQ.md) | Answers to common questions from server admins, including performance tuning and troubleshooting. |
| [docs/admin/MIGRATION.md](docs/admin/MIGRATION.md) | Upgrade instructions, config compatibility notes, and breaking change guidance per version. |
| [docs/admin/RUNBOOK.md](docs/admin/RUNBOOK.md) | Operator incident response — symptom, diagnosis, and resolution for common problems. |
| [docs/admin/FAILURE_MODES.md](docs/admin/FAILURE_MODES.md) | Failure mode catalog — per-component failure, detection, and defined system response. |
| [docs/admin/HAZARDS.md](docs/admin/HAZARDS.md) | Hazard register — known failure conditions, severity ratings, and mitigations. |
| [docs/dev/CONCEPTS.md](docs/dev/CONCEPTS.md) | Plain-language explanation of how RTP works: queue, shapes, pipeline, and platform differences. |
| [docs/dev/ARCHITECTURE.md](docs/dev/ARCHITECTURE.md) | Module breakdown, key concepts, and navigation to design docs. |
| [docs/dev/MULTI_PLATFORM_PLAN.md](docs/dev/MULTI_PLATFORM_PLAN.md) | Roadmap for Fabric and future multi-platform support. |
| [docs/dev/DESIGN.md](docs/dev/DESIGN.md) | High-reliability design: queue system, bounded execution, concurrency model, fault tolerance. |
| [docs/dev/GLOSSARY.md](docs/dev/GLOSSARY.md) | Definitions for all domain terms (pulse, region, tick, ChunkReservation, etc.). |
| [docs/dev/REQUIREMENTS.md](docs/dev/REQUIREMENTS.md) | Functional, non-functional, and system requirements with unique IDs. Includes scope and out-of-scope. |
| [docs/dev/STAKEHOLDERS.md](docs/dev/STAKEHOLDERS.md) | Actors (admin, player, addon dev, contributor, platform) and their goals. |
| [docs/dev/TRACEABILITY.md](docs/dev/TRACEABILITY.md) | Requirements → design → implementing class → test matrix. |
| [docs/adr/](docs/adr/README.md) | Architecture Decision Records — why key design choices were made and what alternatives were rejected. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Build instructions, code style, testing guidelines, and the requirement workflow. |
| [CHANGELOG.md](CHANGELOG.md) | Release history and notable changes per version. |
| [SECURITY.md](SECURITY.md) | Vulnerability disclosure policy and response timeline. |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, code style rules, and the workflow for adding requirements.

New to the plugin? Start with [docs/FOR_SERVER_ADMINS.md](docs/FOR_SERVER_ADMINS.md). Want to extend it? See [docs/FOR_ADDON_DEVELOPERS.md](docs/FOR_ADDON_DEVELOPERS.md).

The short version:
1. `./gradlew build` (compile and run all tests)
2. `./gradlew spotlessApply` (format code before pushing)
3. If you add a requirement, add a row to `docs/dev/TRACEABILITY.md` in the same commit, as CI will fail otherwise.
