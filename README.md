# RTP - Real-Time Process / Program for Minecraft

**Tick-budgeted plugins, multi-loader SDK libraries, and verification tooling for Paper, Spigot, Folia, Fabric, NeoForge, and Velocity.**

`RTP` (*Real-Time Process / Program*) is a multi-product Minecraft monorepo built around strict main-thread and Folia regional-thread budgets, zero synchronous chunk I/O (`S-005`), and fail-closed self-testing (`PBIT` / `CBIT` / `IBIT`). It hosts standalone plugins under [`resource/`](resource/), 16 reusable platform-neutral SDK libraries under [`api/`](api/README.md), shared server carriers under [`platforms/`](platforms/), and black-box verification/fuzzing harnesses under [`helpers/`](helpers/) and [`scripts/`](scripts/).

[![Build](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml/badge.svg)](https://github.com/DailyStruggle/RTP/actions/workflows/gradle.yml)
[![Release](https://img.shields.io/github/v/release/DailyStruggle/RTP)](https://github.com/DailyStruggle/RTP/releases)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Modrinth](https://img.shields.io/badge/Modrinth-LeafRTP-green)](https://modrinth.com/plugin/leafrtp)
[![BuiltByBit](https://img.shields.io/badge/BuiltByBit-RTP%20Pro-blue)](https://builtbybit.com/resources/leafrtp-pro.105418/)

**[LeafRTP (`resource/LeafRTP`)](resource/LeafRTP/README.md)** · **[LeafTreeGen (`resource/LeafTreeGen`)](resource/LeafTreeGen/README.md)** · **[SDK Catalog (`api/*`)](api/README.md)** · **[Plugin Author Guide](docs/FOR_PLUGIN_AUTHORS.md)** · **[Documentation Site](https://dailystruggle.github.io/RTP/)** · **[Get Help](SUPPORT.md)**

---

## Where do I start?

| I want to... | Start here |
|---|---|
| **Install or configure LeafRTP (`/rtp`) on my server** | **[LeafRTP README](resource/LeafRTP/README.md)** · [Quick Start](resource/LeafRTP/docs/admin/QUICK_START.md) · [Recipes](resource/LeafRTP/docs/admin/RECIPES.md) · [Download on Modrinth](https://modrinth.com/plugin/leafrtp) |
| **Generate custom procedural trees & jigsaw worldgen datapacks** | **[LeafTreeGen README](resource/LeafTreeGen/README.md)** · [Biome Tree Catalog](resource/LeafTreeGen/docs/biome-trees.md) · [Changelog](resource/LeafTreeGen/CHANGELOG.md) |
| **Build a standalone multi-loader plugin/mod using the `api/*` SDK** | **[SDK Catalog (`api/README.md`)](api/README.md)** · [Plugin Author Guide](docs/FOR_PLUGIN_AUTHORS.md) · [Architecture](docs/dev/ARCHITECTURE.md) |
| **Write an addon for LeafRTP (`:rtp-api`)** | **[Addon Quickstart](resource/LeafRTP/docs/ADDON_QUICKSTART.md)** · [For Addon Developers](resource/LeafRTP/docs/FOR_ADDON_DEVELOPERS.md) · [Bundled Addons (`resource/LeafRTP`)](resource/LeafRTP/addons/) |
| **Fuzz or benchmark a Minecraft server or plugin** | **[Whole-Server Fuzzer (`ADR-125`)](docs/adr/ADR-125-whole-server-discovery-fuzzing.md)** · [StressTestRTP Harness](helpers/StressTestRTP/README.md) · [Devstack Acceptance](devstack/README.md) |
| **Contribute code, translations, or documentation** | **[For Contributors](docs/FOR_CONTRIBUTORS.md)** · [Developer Index](docs/dev/INDEX.md) · [Testing Guide](docs/dev/TESTING_GUIDE.md) · [ADR Index](docs/adr/README.md) |

---

## 1. Plugins in this Monorepo (`resource/*`)

Each plugin under `resource/` ships its own deliverable jar, changelog, and release pipeline ([ADR-126](docs/adr/ADR-126-multi-product-monorepo-release-architecture.md)):

| Plugin | Path | Loaders | Tag Namespace | Description |
|---|---|---|---|---|
| **[LeafRTP](resource/LeafRTP/README.md)** | [`resource/LeafRTP`](resource/LeafRTP/README.md) | Paper, Spigot, Folia, Fabric, NeoForge, Velocity | `v<semver>` / `leafrtp-v<semver>` | Zero-wait random teleportation (`/rtp`) with background queue preparation, Hilbert/spiral spatial memory, browser map editor (`/rtp editor`), and cross-server proxy routing. |
| **[LeafTreeGen](resource/LeafTreeGen/README.md)** | [`resource/LeafTreeGen/`](resource/LeafTreeGen/README.md) | Paper, Folia, Fabric, NeoForge | `leaftreegen-v<semver>` | Pure-Java procedural tree generator that bakes `.nbt` structure variants into native vanilla jigsaw worldgen datapacks and custom saplings (`/leaftree`). |
| **[LeafSleep](resource/LeafSleep/README.md)** | [`resource/LeafSleep/`](resource/LeafSleep/README.md) | Paper, Folia, Fabric, NeoForge, Velocity | `leafsleep-v<semver>` | Tick-budgeted multiplayer sleep and night-skip coordination built on the shared `api/*` SDK. |

<details>
<summary><b>LeafRTP (`resource/LeafRTP/`) - Modules, bundled addons, and documentation</b></summary>

<br>

**[Full LeafRTP README](resource/LeafRTP/README.md)** · **[Download](https://modrinth.com/plugin/leafrtp)** · **[Quick Start](resource/LeafRTP/docs/admin/QUICK_START.md)** · **[Commands & Permissions](resource/LeafRTP/docs/admin/COMMANDS.md)** · **[Web Editor Guide](resource/LeafRTP/docs/admin/WEB_EDITOR_GUIDE.md)** · **[Proxy Mode](resource/LeafRTP/docs/admin/proxies/INDEX.md)** · **[FAQ](resource/LeafRTP/docs/admin/FAQ.md)**

| Module / Directory | Gradle Path | Purpose |
|---|---|---|
| [`resource/LeafRTP`](resource/LeafRTP/rtp-api/) | `:rtp-api` | Public SPI for addon developers (custom shapes, vertical adjustors, landing checks, editor extensions). |
| [`resource/LeafRTP`](resource/LeafRTP/rtp-core/) | `:rtp-core` | Platform-independent engine: region queues, spiral/Hilbert math, SQLite/MySQL/Postgres storage, web editor, PBIT/CBIT/IBIT. |
| [`resource/LeafRTP`](resource/LeafRTP/rtp-neoforge/) | `:rtp-neoforge` | LeafRTP NeoForge integration entrypoint and event bindings. |
| [`resource/LeafRTP`](resource/LeafRTP/rtp-plugin/) | `:rtp-plugin` | Unified multi-loader shaded jar (`LeafRTP-<version>.jar` and `LeafRTP-Pro-<version>.jar`) and 12-locale resource baseline. |
| [`resource/LeafRTP`](resource/LeafRTP/addons/LeafRTPGuiAddon/README.md) | `:addons:LeafRTPGuiAddon:rtp-gui` | Multi-loader chest-inventory destination picker (`/rtp`) with ItemsAdder, Oraxen, Nexo, and HeadDatabase support. |
| [`resource/LeafRTP`](resource/LeafRTP/addons/LeafRTPClaimAddon/README.md) | `:addons:LeafRTPClaimAddon` | Automatic claim-protection filtering (WorldGuard, GriefPrevention, GriefDefender, Towny, Lands, HuskTowns, FactionsUUID). |
| [`resource/LeafRTP`](resource/LeafRTP/addons/LeafRTPActionAddon/README.md) | `:addons:LeafRTPActionAddon` | Declarative event/arena/challenge lifecycle engine (`definitions/actions/*.yml`, schematic pasting, scoreboard gates). |
| [`resource/LeafRTP`](resource/LeafRTP/addons/LeafRTPCountdownAddon/README.md) | `:addons:LeafRTPCountdownAddon` | Boss-bar warmup countdowns and movement/damage cancellation hooks. |
| [`resource/LeafRTP`](resource/LeafRTP/addons/LeafRTPRiftAddon/README.md) | `:addons:LeafRTPRiftAddon` | Physical portal/rift particle effects and trigger volumes. |

</details>

<details>
<summary><b>LeafTreeGen (`resource/LeafTreeGen/`) - Modules and worldgen pipeline</b></summary>

<br>

**[Full LeafTreeGen README](resource/LeafTreeGen/README.md)** · **[Biome Tree Catalog](resource/LeafTreeGen/docs/biome-trees.md)** · **[ADR Index](resource/LeafTreeGen/docs/adr/README.md)** · **[Changelog](resource/LeafTreeGen/CHANGELOG.md)**

| Module | Gradle Path | Purpose |
|---|---|---|
| `resource/LeafTreeGen/treegen-common/` | `:resource:LeafTreeGen:treegen-common` | Procedural voxel tree geometry, `.nbt` structure writer, jigsaw datapack generator, and `/leaftree` command tree. |
| `resource/LeafTreeGen/treegen-paper/` | `:resource:LeafTreeGen:treegen-paper` | Paper/Folia sapling growth hooks,PDC item tagging, and runtime placement adapter. |
| `resource/LeafTreeGen/treegen-fabric/` | `:resource:LeafTreeGen:treegen-fabric` | Fabric server adapter (`26.1+` unobf & `1.21.1` Mojmap). |
| `resource/LeafTreeGen/treegen-neoforge/` | `:resource:LeafTreeGen:treegen-neoforge` | NeoForge server adapter (`26.1+` & `1.21.1`). |
| `resource/LeafTreeGen/treegen-plugin/` | `:resource:LeafTreeGen:treegen-plugin` | Unified shaded deliverable (`LeafTreeGen-<version>.jar`) with SHA-256/512 checksums and PGP signing. |

</details>

---

## 2. Real-Time Multi-Platform SDK (`api/*`) & Shared Carriers (`platforms/*`)

All 14 modules under [`api/`](api/README.md) are general-purpose, MIT-licensed, Java 17+ libraries published to **Maven Central** (`io.github.dailystruggle:<module>:<version>`) and **JitPack** (`com.github.DailyStruggle.RTP:<module>:<tag>`). Any Minecraft plugin or mod can consume them without pulling in `LeafRTP`:

- **Java 17 bytecode baseline** ([ADR-113](docs/adr/ADR-113-java-17-baseline-and-26x-support-boundary.md)) across Minecraft 1.18+ through 26.x+.
- **Zero `rtp-core` or `rtp-api` dependency**, enforced at build time by ArchUnit (`ADR-115`).
- **Zero raw thread creation**; work runs on caller-supplied executors or regional `TaskScheduler` handles.
- **Fail-closed contracts** on untrusted YAML, JSON, NBT, and command inputs.

<details>
<summary><b>Browse all 14 `api/*` SDK modules (5 functional layers)</b></summary>

<br>

Full documentation and Maven/Gradle snippets: **[`api/README.md`](api/README.md)** and **[`docs/FOR_PLUGIN_AUTHORS.md`](docs/FOR_PLUGIN_AUTHORS.md)**.

#### Layer 1: Runtime & Platform Abstraction
| Module | Gradle / Maven ID | Summary & Guide |
|---|---|---|
| [`api/server-api/`](api/server-api/README.md) | `server-api` | Platform identity (`PlatformFamily`), version range checks, regional/async `TaskScheduler`, and capability sub-interfaces (`ServerAccessor`). |
| [`api/commands-api/`](api/commands-api/README.md) | `commands-api` | Permission-filtered `TreeCommand` hierarchy, `key=value` parameter validation, tick-budgeted dispatch, and Brigadier bridge. |
| [`api/menu-api/`](api/menu-api/README.md) | `menu-api` | Declarative multi-page menus (`MenuDescriptor`), click actions, staging carts (`MenuSession`), expiring tokens, and `ServiceLoader` binding. |
| [`api/effects-api/`](api/effects-api/README.md) | `effects-api` | Prototype-cloned sound, particle, title, bossbar, potion, firework, hologram, and volumetric wireframe effects. |

#### Layer 2: Configuration, Units & Self-Testing (`PBIT` / `CBIT` / `IBIT`)
| Module | Gradle / Maven ID | Summary & Guide |
|---|---|---|
| [`api/yaml-api/`](api/yaml-api/README.md) | `yaml-api` | Zero-dependency, comment-preserving YAML parser and atomic writer (`RtpYamlConfig`). |
| [`api/units-api/`](api/units-api/README.md) | `units-api` | Human-friendly duration (`20t`, `5m`), distance (`256c`, `4r`, `2km`), and data-size (`256MB`) parsers. |
| [`api/bit-api/`](api/bit-api/README.md) | `bit-api` | Power-On (`PBIT`), Continuous (`CBIT`), and Initiated (`IBIT`) Built-In Test runner, code registry (`BitCodeRegistry`), and diagnostic tables. |
| [`api/config-bit-api/`](api/config-bit-api/README.md) | `config-bit-api` | YAML `@type` / `@range` / `@options` comment-directive schema validator, cross-key constraints, and locale parity checks. |

#### Layer 3: Stochastic Control, Allocation & Telemetry
| Module | Gradle / Maven ID | Summary & Guide |
|---|---|---|
| [`api/predict-api/`](api/predict-api/README.md) | `predict-api` | Lock-free `EwmaEstimator`, `BernoulliEstimator`, `AimdLimiter`, `BudgetPacer`, `ComparativeCostGovernor`, `WaterFillingAllocator`, and `ProbedShareAllocator`. |
| [`api/metrics-api/`](api/metrics-api/README.md) | `metrics-api` | Portable TPS, MSPT, tick-budget utilization, heap, and Folia regional health SPI (`Metrics`, `MetricsSnapshot`). |
| [`api/bstats-api/`](api/bstats-api/README.md) | `bstats-api` | Zero-dependency bStats v2 telemetry client (`BStatsService` and chart builders). |

#### Layer 4: World Storage & Data Decode
| Module | Gradle / Maven ID | Summary & Guide |
|---|---|---|
| [`api/anvil-api/`](api/anvil-api/README.md) | `anvil-api` | Off-tick read-only `.mca` / `.linear` region decoder (gzip, zlib, LZ4, Zstd), selective NBT reader, and load-free column/biome prefilters. |
| [`api/tags-api/`](api/tags-api/README.md) | `tags-api` | Datapack block-tag JSON loader (`DiskTagSource`), cycle-safe `#namespace:tag` resolver, and `ADR-017` block-safety predicate compiler. |

#### Layer 5: Cartography & Diagnostics Rendering
| Module | Gradle / Maven ID | Summary & Guide |
|---|---|---|
| [`api/maps-api/`](api/maps-api/README.md) | `maps-api` | 128x128 in-game map-item canvas and PNG/BMP/Web-tile cartography (`HeatmapRenderer`, `DualSparklineRenderer`, `WebTileRenderer`). |

</details>

<details>
<summary><b>Shared platform carriers & adapters (`platforms/*`)</b></summary>

<br>

[`platforms/`](platforms/) hosts the generic server and proxy platform carriers shared across plugins in the monorepo:

| Directory | Gradle Path | Scope |
|---|---|---|
| `platforms/bukkit/` | `:bukkit:*` | Bukkit/Spigot `ServerAccessor`, `WorldEventHook`, and version carriers (`bukkit-common`, `v1_20_R1`, `v1_21_R1`, `v26_1_R1`). |
| `platforms/paper/` | `:paper:*` | Paper async-chunk and adventure carriers (`paper-common`, `v1_20_R1`, `v1_21_R1`, `v26_1_R1`). |
| `platforms/folia/` | `:folia:*` | Folia regionized-thread scheduler and entity-thread carriers (`folia-common`, `v1_20_R1`, `v1_21_R1`, `v26_1_R1`). |
| `platforms/fabric/` | `:fabric:*` | Fabric `ServerAccessor`, `TaskScheduler`, lifecycle hooks, and unobfuscated 26.x carriers (`1.20.1`, `1.21.x`, `26.1+`). |
| `platforms/neoforge/` | `:neoforge:*` | [NeoForge server carriers and adapters](platforms/neoforge/README.md) (`neoforge-common`, `1.21.1+`, `26.1+`). |
| `platforms/proxy/` | `:proxy:*` | [Cross-server proxy orchestration](platforms/proxy/README.md) (`proxy-common`, `proxy-velocity`, `proxy-bungee`). |

</details>

---

## 3. Verification, Fuzzing & Developer Tooling

This repository treats verification as a first-class engineering product ([`docs/dev/VERIFICATION_GATES.md`](docs/dev/VERIFICATION_GATES.md)).

<details>
<summary><b>Whole-Server Discovery Fuzzer & Black-Box Benchmarks (`scripts/bench_fuzz.py`, `helpers/*`)</b></summary>

<br>

- **Whole-Server Discovery Fuzzer ([`scripts/bench_fuzz.py`](scripts/bench_fuzz.py), [`ADR-125`](docs/adr/ADR-125-whole-server-discovery-fuzzing.md)):**
  - `python3 scripts/bench_fuzz.py --mode server` fuzzes an entire installed plugin/mod set with zero hardcoded command names or package prefixes.
  - Discovers commands per permission tier (`default` vs `op`) from the live Brigadier `declare_commands` packet, drives a tier-stratified UCB1 bandit over command and GUI window-click sequences, detects acute tick stalls and sustained MSPT inflation against a same-seed vanilla baseline, captures host `jcmd` histograms and thread dumps, and runs `ddmin` jar isolation to emit both an operator mitigation and an author reproducer.
- **[`helpers/StressTestRTP/`](helpers/StressTestRTP/README.md) (`:helpers:StressTestRTP`):**
  - Standalone black-box benchmark plugin (`/rtpstress`) that measures cold-start latency, warm p50/p95/p99 latency, minimum TPS under burst, p95 MSPT, and heap footprint across any `/rtp`-style plugin (LeafRTP, BetterRTP, HuskHomes, EzRTP).
- **[`helpers/PeriodicWorldSaver/`](helpers/PeriodicWorldSaver/README.md) (`:helpers:PeriodicWorldSaver`):**
  - Tick-budgeted incremental world-save scheduler for benchmark and production servers.
- **[`helpers/MockItemPlugins/`](helpers/MockItemPlugins/README.md) (`:helpers:MockItemPlugins`):**
  - Lightweight stub jars (`MockItemsAdder`, `MockOraxen`, `MockNexo`, `MockHeadDatabase`) for headless custom-item integration tests.

</details>

<details>
<summary><b>Multi-Server Devstack & Runtime Acceptance (`devstack/`)</b></summary>

<br>

**[Full Devstack Guide (`devstack/README.md`)](devstack/README.md)** · **[Testing Guide (`docs/dev/TESTING_GUIDE.md`)](docs/dev/TESTING_GUIDE.md)**

- **`./devstack/run-acceptance.sh` (or `.\devstack\run-acceptance.ps1`):** Boots a multi-server Docker network (Velocity proxies, Paper/Folia/Fabric/NeoForge backends, Redis, MySQL, PostgreSQL) and drives a headless Mineflayer bot (`devstack/clients/mineflayer-bot.js`) through cross-server teleports, GUI chest menus, effect packet assertions, and mid-flight disconnect/killswitch recovery.
- **`./devstack/run-versions-smoke.sh` & `./devstack/run-version-sweep.sh`:** Automated single-server smoke and version-matrix sweeps across Minecraft `1.18.2` through `26.3` (`ADR-113`).
- **`./devstack/test-item-compat.sh`:** Single-server Paper harness verifying custom item resolution against stub item plugins.

</details>

<details>
<summary><b>Repository AST, Local CI Gate & Support Bot (`corpus-atlas/`, `scripts/`, `discord-support-bot/`)</b></summary>

<br>

- **Repository AST & Decision-Preservation Hypergraph ([`corpus-atlas/`](corpus-atlas/), [`scripts/repo_ast.py`](scripts/repo_ast.py), [`ADR-115`](docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md)):**
  - Sub-25ms SQLite FTS5 + AST index mapping every Java class, SPI tier, config key, BIT code, `REQ-*` requirement, `S-00x` safety rule, and `ADR-*` record.
  - Query from CLI or MCP (`corpus-atlas`):
    ```sh
    python3 scripts/repo_ast.py context TeleportPipelineTask
    python3 scripts/repo_ast.py search "exponential moving average estimator"
    python3 scripts/repo_ast.py impact RTPScheduler
    python3 scripts/repo_ast.py audit
    ```
- **Local CI-Parity Gate ([`scripts/agent-gate.py`](scripts/agent-gate.py), [`scripts/ci-scope.py`](scripts/ci-scope.py)):**
  - `python3 scripts/agent-gate.py` scopes uncommitted working-tree changes against the target branch and runs traceability, mojibake, front-page drift, Spotless, affected project-set builds (`-Pcoverage -PstaticAnalysis`), PBIT config checks, and the 80% changed-line coverage gate (`scripts/diff-coverage.py`).
- **Discord Support & Release Bot ([`discord-support-bot/`](discord-support-bot/)):**
  - Multi-product support triage and release announcer (`support-bot.yml`), with tag-prefix release discovery (`ADR-126`) and zero-hallucination config/BIT diagnosis backed by `corpus-atlas`.

</details>

---

## 4. Build from Source & Repository Layout

**Prerequisites:** Git and **JDK 21** (Gradle automatically provisions JDK 25 for the Minecraft 26.x carrier modules, or pass `-PexcludeJdk25` on a JDK-21-only host).

```sh
git clone https://github.com/DailyStruggle/RTP.git
cd RTP

# Build all deliverables (copied to build/libs/)
./gradlew assemble                         # Windows: .\gradlew.bat assemble

# Build a specific plugin or SDK module
./gradlew :rtp-plugin:remapJar             # LeafRTP unified jar -> resource/LeafRTP/rtp-plugin/build/libs/
./gradlew :resource:LeafTreeGen:treegen-plugin:build  # LeafTreeGen jar -> resource/LeafTreeGen/treegen-plugin/build/libs/
./gradlew :predict-api:build               # Single api/* SDK module

# Run verification gates
python3 scripts/agent-gate.py              # Scoped PR gate (Spotless, tests, PMD, SpotBugs, JaCoCo)
./gradlew spotlessApply                    # Auto-format Java/Gradle sources
```

<details>
<summary><b>Complete monorepo directory layout</b></summary>

<br>

| Directory | Purpose |
|---|---|
| [`resource/LeafRTP`](resource/LeafRTP/README.md) | **LeafRTP** (`rtp-api`, `rtp-core`, `rtp-neoforge`, `rtp-plugin`) and its addons (`resource/LeafRTP/addons`). |
| [`resource/LeafTreeGen/`](resource/LeafTreeGen/README.md) | **LeafTreeGen** (`treegen-common`, `treegen-paper`, `treegen-fabric`, `treegen-neoforge`, `treegen-plugin`). |
| [`api/`](api/README.md) | 16 standalone, platform-neutral SDK libraries (`server-api`, `commands-api`, `menu-api`, `effects-api`, `yaml-api`, `units-api`, `bit-api`, `config-bit-api`, `predict-api`, `metrics-api`, `bstats-api`, `anvil-api`, `tags-api`, `maps-api`, `database-api`, `schematics-api`). |
| [`platforms/`](platforms/) | Generic `bukkit`, `paper`, `folia`, `fabric`, `neoforge`, and `proxy` platform carriers and adapters. |
| [`helpers/`](helpers/) | Standalone verification and server tools (`StressTestRTP`, `PeriodicWorldSaver`, `MockItemPlugins`). |
| [`corpus-atlas/`](corpus-atlas/) | Deterministic repository AST indexer (`build_doc_index.py`), query engine (`corpus_atlas.py`), and MCP server (`mcp_server.py`). |
| [`discord-support-bot/`](discord-support-bot/) | Multi-project Discord support bot, log/config doctor, and `#releases` publisher. |
| [`scripts/`](scripts/) | Whole-server fuzzer (`bench_fuzz.py`), CI scope/gate (`ci-scope.py`, `agent-gate.py`), web editor generator (`generate_web_editor.py`), locale pipeline, and storefront builder (`scripts/release/`). |
| [`devstack/`](devstack/README.md) | Docker Compose multi-server acceptance and version-sweep testbed. |
| [`docs/`](docs/dev/INDEX.md) | User documentation ([`docs/admin/`](resource/LeafRTP/docs/admin/QUICK_START.md)), contributor guides ([`docs/dev/INDEX.md`](docs/dev/INDEX.md)), and Architecture Decision Records ([`docs/adr/README.md`](docs/adr/README.md)). |

> **Flat Gradle coordinates:** `settings.gradle` maps moved directories (`api/*`, `platforms/*`, `resource/LeafRTP`) to flat Gradle project paths (`:server-api`, `:rtp-api`, `:rtp-core`, `:rtp-plugin`, `:addons:*`) so Maven Central, JitPack, and `project(':...')` dependencies remain stable.

</details>

---

## Contributing & Support

- **Contributing:** Read [CONTRIBUTING.md](CONTRIBUTING.md), [FOR_CONTRIBUTORS.md](docs/FOR_CONTRIBUTORS.md), and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Run `python3 scripts/agent-gate.py` and `./gradlew spotlessApply` before opening a pull request.
- **Support:** Ask questions in [GitHub Discussions](https://github.com/DailyStruggle/RTP/discussions) or the `#support` Forum in the `LeafCraft Plugin Support` Discord. See [SUPPORT.md](SUPPORT.md) for bug report templates and [SECURITY.md](SECURITY.md) for private vulnerability disclosure.
- **Priority Support:** **[RTP Pro on BuiltByBit](https://builtbybit.com/resources/leafrtp-pro.105418/)** provides contractual priority support (`@Pro Supporter`) and funds open-source development ([ADR-108](docs/adr/ADR-108-all-mit-licensing-pro-as-support.md), [leafrtp-ADR-087](resource/LeafRTP/docs/adr/leafrtp-ADR-087-release-cadence-contractual-pro-discretionary-lite.md)).

---

## License

**MIT** ([`LICENSE`](LICENSE)). You may use, modify, and redistribute all source code in this repository and all shipped jars, including commercially ([ADR-108](docs/adr/ADR-108-all-mit-licensing-pro-as-support.md)). Where a module or file ships its own `LICENSE` or SPDX header (for example the GPL-3.0-or-later NeoForge carriers), that grant governs the file.
