# Start Here — Addon Developers

**Current Plugin Version:** `3.0.0-beta.1`

This page guides third-party plugin developers who extend RTP using the `rtp-api` module.
If you are implementing a custom shape, vertical adjustor, biome filter, or claim-check hook, start here.

> **In a hurry?** Jump to the [Addon Quickstart](ADDON_QUICKSTART.md) - a one-page tutorial that
> registers a custom region shape in about 20 lines (Gradle dependency, `ServiceLoader` descriptor,
> and an `RTPAddon` that calls `RTP.addShape(...)`).
>
> **Building outside this repository?** Both `rtp-api` and `rtp-core` are published on JitPack as
> `com.github.DailyStruggle.RTP:rtp-api:<tag>` / `:rtp-core:<tag>`. See
> [dev/PUBLISHING.md](dev/PUBLISHING.md) for the dependency snippet and the Maven Central path.

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

[`addons/LeafRTPCountdownAddon/`](../addons/LeafRTPCountdownAddon/) is the canonical reference. It is a
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
bundled GUI addon ([`addons/LeafRTPGuiAddon/`](../addons/LeafRTPGuiAddon/)) is the worked example: it opens
a native Bukkit inventory, so it has a thin `rtp-gui-bukkit` module (a handful of files) sitting on
top of a platform-neutral `rtp-gui-common` module that holds the menu model, actions, and renderer
seam. Even then, the split is the point: the model and behavior stay in `common`, and only the
render/translate layer is platform-specific. A Fabric or NeoForge GUI would add a similarly thin
renderer over the same `common` model rather than re-implementing the addon.

Rule of thumb: **if your addon reacts to RTP or reconfigures RTP, it is one platform-agnostic
module. If it opens a platform-native UI or calls a platform-only API, isolate just that surface in
a thin platform module over a shared `common` core.**

The decision behind the addon SPI is [ADR-057](adr/ADR-057-platform-agnostic-addon-spi.md); the
addon-as-external-Gradle-project model is [ADR-013](adr/ADR-013-addons-as-external-gradle-projects.md).

---

## Recommended Reading Order

### 1. [CONCEPTS.md](dev/CONCEPTS.md)
Plain-language explanation of how RTP's queue, shapes, vertical adjustors, and teleport pipeline fit together.
Read this first to understand the execution model your addon will plug into.

### 2. [ARCHITECTURE.md](dev/ARCHITECTURE.md)
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
[ADR-057](adr/ADR-057-platform-agnostic-addon-spi.md).

---

## Reference Material

- [REQUIREMENTS.md](dev/REQUIREMENTS.md) — the `REQ-API-*` requirements define the stability contract your addon can rely on.
- [STAKEHOLDERS.md](dev/STAKEHOLDERS.md) — actor definitions; the "Addon Developer" section describes the goals and guarantees the API is designed to satisfy.

---

## Also Useful

- [`addons/LeafRTPCountdownAddon/`](../addons/LeafRTPCountdownAddon/) — the canonical addon template; among other things it contributes a safety (claim/biome/distance) verifier via `RTPAPI.hooks()`, which is the same seam a claim-check hook uses. The claim-plugin integrations that once shipped as a standalone `RTP_ClaimPluginIntegrations` addon are now bundled directly into the plugin (see [ADR-019](adr/ADR-019-claim-plugin-integrations-folded-into-plugin.md)).
- [CONTRIBUTING.md](../CONTRIBUTING.md) — if you want to upstream a change to `rtp-api` itself, follow the contribution workflow there.
