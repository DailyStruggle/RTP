# Start Here — Addon Developers

**Current Plugin Version:** `@version@`

This page guides third-party plugin developers who extend RTP using the `rtp-api` module.
If you are implementing a custom shape, vertical adjustor, biome filter, or claim-check hook, start here.

> **In a hurry?** Jump to the [Addon Quickstart](ADDON_QUICKSTART.md) - a one-page tutorial that
> registers a custom region shape in about 20 lines (dependency, `ServiceLoader` descriptor,
> and an `RTPAddon` that calls `RTP.addShape(...)`).
>
> **Your addon is its own project** (the normal case). Both `rtp-api` and `rtp-core` are published
> to **Maven Central** as `io.github.dailystruggle:rtp-api` / `:rtp-core` (currently `3.2.1`), so
> add them with `mavenCentral()` (Gradle) or a plain `<dependency>` (Maven) - no repository block or
> credentials. They are also served on JitPack (`com.github.DailyStruggle.RTP:rtp-api:<tag>`) for
> tracking a git tag / branch / commit. The Quickstart has copy-paste Gradle **and** Maven
> (`pom.xml`) snippets; [dev/PUBLISHING.md](../../../docs/dev/PUBLISHING.md) documents every channel.

---

## Addons Are Platform-Agnostic by Default

The single most important thing to understand before you write an RTP addon: **most addons need no
platform-specific code at all.** You write one module, compile it against `rtp-api` (and `rtp-core`
where you need core types), and the same jar loads and runs unchanged on Spigot, Paper, Folia,
Fabric, and NeoForge. You do **not** write a Bukkit plugin, a Fabric `ModInitializer`, or a NeoForge
`@Mod` entry point, and you do **not** re-solve threading per platform.

This works because RTP abstracts the platform behind a set of platform-neutral seams that your addon
talks to instead of talking to the server directly:

| You want to... | Use this (platform-neutral) | You do **not** touch |
|---|---|---|
| Be discovered and loaded on every platform | Implement `RTPAddon` + a `META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon` descriptor (`ServiceLoader`) | Bukkit `plugin.yml`, Fabric `fabric.mod.json`, NeoForge `@Mod` |
| Schedule async / delayed / repeating / region-correct work | `RTP.scheduler` (the `RTPScheduler` SPI) and `RTPRunnable.schedule()` | `Bukkit.getScheduler()`, Folia entity/region schedulers, Fabric server-thread executors, raw `Thread` / `Executors` |
| Add or veto a teleport destination | `RTPAPI.hooks().verifiers().register(...)` (the claim/biome/distance verifier seam, ADR-026) | Direct claim-plugin API calls in the hot path |
| React to a completed teleport | `TeleportPipelineTask.teleportPostActions` | Bukkit `PostTeleportEvent` listeners |
| Read/write your own config + honor `/rtp reload` | `RTP.configs.putParser(...)`, `Configs.onReload(...)` | Platform config loaders |
| Register a custom shape or vertical adjustor | `RTP.addShape(...)` / the adjustor registry | Platform-specific anything |
| Resolve players / worlds / locations | `RTP.serverAccessor` (`RTPServerAccessor`) and the `rtp-api` `RTPPlayer` / `RTPWorld` / `RTPLocation` wrappers | `org.bukkit.*`, `net.minecraft.*` types |
| Log | `RTP.log(...)` | `Bukkit.getLogger()`, `System.out` |

### The proof: `LeafRTPCountdownAddon`

[`addons/LeafRTPCountdownAddon/`](https://github.com/dailystruggle/RTP/tree/V3/plugins/leafrtp/addons/LeafRTPCountdownAddon) is the canonical reference. It is a
**single module with no platform sub-module** - three Java files, one `example.yml`, and one
`ServiceLoader` descriptor line - and it has **zero `org.bukkit.*` imports**. It is discovered by
`rtp-core` through the `RTPAddon` SPI and runs identically on Bukkit/Paper/Folia, Fabric, and
NeoForge. Despite that, it exercises the real addon surface: registering a `ConfigParser`,
re-registering on `/rtp reload`, contributing a safety verifier via `RTPAPI.hooks()`, observing
post-teleport via `TeleportPipelineTask.teleportPostActions`, and registering countdowns. Read it
end-to-end before writing your own - it is intentionally a working template, not a toy.

### Concurrency is inherited, not re-implemented

The scheduling seam is worth calling out on its own. Because `RTP.scheduler` / `RTPRunnable` route
your work onto the correct thread for the platform (the main thread on Spigot/Paper, the owning
player's entity scheduler or a region thread on Folia, the server-thread executor on Fabric), an
addon gets Folia-correct, region-safe scheduling **for free** - without importing a single platform
scheduler API, and without learning each platform's threading model. The same code is also
testable through `MockRTPScheduler` instead of mocking three different platform schedulers. This is
the heaviest cross-platform burden in most plugins, and RTP solves it once on your behalf. The hard
rules still apply (choose the right method - async vs. main vs. per-location/per-chunk - and never
do synchronous chunk I/O on the main thread, S-005); what you skip is the per-platform plumbing.

### When you *do* need a platform shim

A platform-specific module is required only when your addon reaches for a surface RTP deliberately
does **not** abstract - typically a platform-native UI or API with no cross-platform equivalent. The
bundled GUI addon ([`addons/LeafRTPGuiAddon/`](https://github.com/dailystruggle/RTP/tree/V3/plugins/leafrtp/addons/LeafRTPGuiAddon)) is the worked example: it opens
a native Bukkit inventory, so it has a thin `rtp-gui-bukkit` module (a handful of files) sitting on
top of a platform-neutral `rtp-gui-common` module that holds the menu model, actions, and renderer
seam. Even then, the split is the point: the model and behavior stay in `common`, and only the
render/translate layer is platform-specific. A Fabric or NeoForge GUI would add a similarly thin
renderer over the same `common` model rather than re-implementing the addon.

Rule of thumb: **if your addon reacts to RTP or reconfigures RTP, it is one platform-agnostic
module. If it opens a platform-native UI or calls a platform-only API, isolate just that surface in
a thin platform module over a shared `common` core.**

The decision behind the addon SPI is [leafrtp-ADR-040](adr/leafrtp-ADR-040-platform-agnostic-addon-spi.md); the
addon-as-external-Gradle-project model is [leafrtp-ADR-010](adr/leafrtp-ADR-010-addons-as-external-gradle-projects.md).

---

## Recommended Reading Order

### 1. [CONCEPTS.md](dev/CONCEPTS.md)
Plain-language explanation of how RTP's queue, shapes, vertical adjustors, and teleport pipeline fit together.
Read this first to understand the execution model your addon will plug into.

### 2. [ARCHITECTURE.md](../../../docs/dev/ARCHITECTURE.md)
Module breakdown: what lives in `rtp-api` vs `rtp-core` vs platform adapters, and why.
Explains the boundary your addon must respect — compile against `rtp-api` only, never `rtp-core`.

### 3. [DESIGN.md](dev/DESIGN.md)
Deep-dive into the bounded execution model, concurrency guarantees, and fault-tolerance contracts.
Read this to understand what guarantees RTP makes to your addon and what it expects in return.

### 4. [GLOSSARY.md](dev/GLOSSARY.md)
Definitions for every domain term used across the codebase and documentation:
`ChunkReservation`, `MemoryShape`, `RTPPipeline`, `pulse`, `sector`, and more.

### 5. [docs/adr/](adr/README.md)
Architecture Decision Records — the *why* behind key design choices.
Particularly relevant: ADR-001 (spiral mapping), ADR-006 (async queue), ADR-011 (`rtp-api` as a separate module), ADR-013 (addons as external Gradle projects).

---

## How to Load an Addon

Once your addon is written, see [dev/ADDON_LOADING.md](dev/ADDON_LOADING.md) for how RTP
discovers, loads, and unloads it on every platform (the `RTPAddon` SPI + `ServiceLoader`
descriptor, classpath placement, and lifecycle). The decision behind it is
[leafrtp-ADR-040](adr/leafrtp-ADR-040-platform-agnostic-addon-spi.md).

---

## Building on the API

- [dev/ADDON_MENUS.md](dev/ADDON_MENUS.md) — build your own destination menu / GUI on the stable `rtp-api` surface (`getAllowedTargets` / `getTargetStatus` / `teleport`), with the `LeafRTPGuiAddon` reference.
- [dev/ADDON_CROSS_SERVER.md](dev/ADDON_CROSS_SERVER.md) — offer remote (network-mode) destinations from an addon via the `rtp-proxy-common` transport SPI (`readSnapshot` / `claim` / `ReservationToken`).
- [dev/EXTERNAL_HOOKS.md](dev/EXTERNAL_HOOKS.md) — the behavior-modification hook catalog (root action, safety verifiers) via `RTPAPI.hooks()`.

---

## Bundled SDK Libraries (Resupplied to Addons at Compile & Runtime)

When your addon depends on `io.github.dailystruggle:rtp-api` (or `rtp-core`), the underlying LeafCraft SDK modules are declared as `api` dependencies on the POM and shaded directly into `LeafRTP` at runtime. You can call any of these libraries in your addon without adding extra dependencies or shading them yourself (and every published Maven artifact embeds its `README.md` and ADRs under `META-INF/leaf-sdk/<module>/` as well as `docs/sdk/<module>/README.md` in projected repositories):

| Bundled Maven Artifact | Available to Addons at Runtime | Documented Purpose |
|---|---|---|
| `io.github.dailystruggle:server-api` | Yes (`rtp-api` transitive + shaded) | Platform-neutral scheduling (`TaskScheduler`), Folia region checks, version gates, and `ServerAccessor` capability SPIs |
| `io.github.dailystruggle:commands-api` | Yes (`rtp-api` transitive + shaded) | Permission-filtered command tree (`TreeCommand`), `key=value` parameters, and Brigadier bridge |
| `io.github.dailystruggle:menu-api` | Yes (`rtp-api` transitive + shaded) | Declarative multi-page chest/book menus (`MenuDescriptor`), click actions, session cart, and anvil prompts |
| `io.github.dailystruggle:yaml-api` | Yes (`rtp-api` transitive + shaded) | Block-comment-preserving YAML parser and atomic writer (`RtpYamlConfig`) |
| `io.github.dailystruggle:units-api` | Yes (`rtp-api` transitive + shaded) | Temporal (`20t`, `1d12h`), spatial (`256c`, `2km`), and data-size (`256MB`) parsers |
| `io.github.dailystruggle:tags-api` | Yes (`rtp-api` transitive + shaded) | Minecraft data-pack block-tag JSON resolver (`TagResolver`) and block-state predicate compiler |
| `io.github.dailystruggle:schematics-api` | Yes (`rtp-api` transitive + shaded) | Sponge Schematic v2/v3 (`.schem`) and Litematica (`.litematic`) decoder and 3D placement planner |
| `io.github.dailystruggle:anvil-api` | Yes (`rtp-api` transitive + shaded) | Off-tick read-only `.mca` / `.linear` region-file decoder (`RegionFileReader`) and selective NBT probes |
| `io.github.dailystruggle:metrics-api` | Yes (`rtp-api` transitive + shaded) | TPS, MSPT, tick-budget utilization, heap, and Folia regional telemetry SPI (`MetricsSnapshot`) |
| `io.github.dailystruggle:bit-api` | Yes (`rtp-core` transitive + shaded) | Power-On, Continuous, and Initiated Built-In Test (`PBIT` / `CBIT` / `IBIT`) framework (`BitRunner`) |
| `io.github.dailystruggle:config-bit-api` | Yes (`rtp-core` transitive + shaded) | Declarative YAML `@type` / `@range` / `@options` comment-directive schema validation (`DirectiveValidator`) |
| `io.github.dailystruggle:effects-api` | Yes (`rtp-core` transitive + shaded) | Prototype-cloned visual/audio effects (`Effect`, `EffectFactory`), holograms, and wireframe particles |
| `io.github.dailystruggle:predict-api` | Yes (`rtp-core` transitive + shaded) | Online statistical estimators (`EwmaEstimator`), `AimdLimiter`, `BudgetPacer`, and `WaterFillingAllocator` |
| `io.github.dailystruggle:maps-api` | Yes (`rtp-core` transitive + shaded) | 128x128 in-game cartography and PNG/BMP/Web-tile rendering SPI (`MapCanvas`, `HeatmapRenderer`) |

---

## Reference Material

- [REQUIREMENTS.md](dev/REQUIREMENTS.md) — the `REQ-API-*` requirements define the stability contract your addon can rely on.
- [STAKEHOLDERS.md](dev/STAKEHOLDERS.md) — actor definitions; the "Addon Developer" section describes the goals and guarantees the API is designed to satisfy.

---

## Also Useful

- [`addons/LeafRTPCountdownAddon/`](https://github.com/dailystruggle/RTP/tree/V3/plugins/leafrtp/addons/LeafRTPCountdownAddon) — the canonical addon template; among other things it contributes a safety (claim/biome/distance) verifier via `RTPAPI.hooks()`, which is the same seam a claim-check hook uses. The claim-plugin integrations that once shipped as a standalone `RTP_ClaimPluginIntegrations` addon are now bundled directly into the plugin (see [leafrtp-ADR-012](adr/leafrtp-ADR-012-claim-plugin-integrations-folded-into-plugin.md)).
- [CONTRIBUTING.md](https://github.com/dailystruggle/RTP/blob/V3/CONTRIBUTING.md) — if you want to upstream a change to `rtp-api` itself, follow the contribution workflow there.
