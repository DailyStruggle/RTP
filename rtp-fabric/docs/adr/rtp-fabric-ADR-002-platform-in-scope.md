# rtp-fabric-ADR-002 — Fabric Platform Is In Scope (Multi-Version Single-JAR Multi-Loader Packaging)

> Renumber history: originally project-wide **ADR-022**; renumbered to **ADR-031** on 2026-05-05 to resolve a collision with [ADR-022 (Region Shape Cache Key)](../../../docs/adr/ADR-022-shape-cache-key-seed-plus-config-hash.md); restructured to subproject-scoped **rtp-fabric-ADR-002** the same day when subproject ADRs were given per-directory numbering. Pre-rename commits and external docs may still say "ADR-022" or "ADR-031".

**Status:** Accepted
**Date:** 2026-04-30
**Last revised:** 2026-05-01 (rewritten — single Fabric module replaced by per-MC-version submodules to mirror the Bukkit-family layout)

## Context

RTP has historically targeted Bukkit-derived server software only (Spigot, Paper, Folia), as recorded in `docs/dev/REQUIREMENTS.md §0` and `REQ-RTP-SYS-002`. Fabric work has nonetheless been carried in `docs/dev/MULTI_PLATFORM_PLAN.md` as an "active frontier".

The first iteration of this ADR (2026-04-30) committed to a **single** version-agnostic Fabric adapter, `rtp-fabric/rtp-fabric-common`, pinned to one Minecraft version. A 2026-05-01 deployment to a `26.2` (year-based versioning, see *Version Naming* below) Fabric server surfaced the structural flaw:

```
java.lang.NoClassDefFoundError: net/minecraft/class_2561
  at io.github.dailystruggle.rtp.fabric.RTPFabricMod.onInitialize
```

Root cause: the adapter was compiled against MC `1.21.1` Mojang mappings. Fabric Loader's intermediary→runtime remap is **per-MC-version**: a JAR remapped against the `1.21.1` intermediary table is not loadable on a `26.2` runtime, because the intermediary class name space (`class_2561` etc.) is not stable across MC releases. This is structurally identical to the Bukkit-family NMS-version problem — and was already solved there by per-version adapter submodules (`rtp-spigot/rtp-spigot-v1_20_R1`, `rtp-paper/rtp-paper-v1_21_R1`, `rtp-folia/rtp-folia-v26_1_R1`, etc.).

Four facts inform the rewritten decision:

1. **Abstraction sufficiency.** The April 2026 gap analysis (recorded in `MULTI_PLATFORM_PLAN.md` *What Does NOT Need to Change*) concluded that `RTPServerAccessor`, `RTPWorld`, `RTPPlayer`, `RTPScheduler`, and the `rtp-core` `DatabaseHandler` are sufficient for Fabric. The gap analysis stands; no new `rtp-api` interfaces are required.
2. **Per-MC-version mapping divergence is unavoidable on Fabric.** Loom statically remaps Mojang→intermediary at build time, in a single namespace per JAR. There is no Fabric-Loader hook for runtime mapping selection equivalent to Bukkit's reflective NMS access. Therefore each MC version that RTP supports needs its own Loom-built artifact in its own package.
3. **Bukkit-family precedent is reusable.** `RTPBukkitPlugin` already selects an `RTPServerAccessor` implementation at startup by classpath probe (`Class.forName("io.github.dailystruggle.rtp.spigot_v1_21_R1.…")` etc.). The same dispatch pattern is portable to Fabric: `RTPFabricMod` queries `SharedConstants.getCurrentVersion()` (or equivalent), then `Class.forName`s the matching `io.github.dailystruggle.rtp.fabric_<ver>.FabricServerAccessor<ver>`. Non-matching version classes never resolve, so their MC-mapping references never fail to link.
4. **Distribution constraint unchanged.** RTP ships through a single resource page on BuiltByBit, which awards one primary download per version entry. The multi-loader single-JAR mandate from the original ADR is preserved — extended with the multi-version dispatch layer on the Fabric side.

## Decision

### 1. Scope

Fabric is **a first-class, in-scope target platform** for RTP, alongside Spigot, Paper, and Folia. Forge, NeoForge, and other non-Fabric mod loaders remain out of scope and are deferred to Phase 4 of the multi-platform plan.

This decision **supersedes the relevant clause** of `docs/dev/REQUIREMENTS.md §0 Out of Scope` (the *Non-Bukkit platforms* bullet, only insofar as it names Fabric) and broadens `REQ-RTP-SYS-002` to include Fabric.

### 2. Module Layout — Per-MC-Version Submodules

The `rtp-fabric/` module group shall mirror the Bukkit-family per-version adapter pattern:

```
rtp-fabric/
  rtp-fabric-common/      # version-agnostic abstract surface; no MC symbols beyond stable Fabric-Loader API
  rtp-fabric-1_20/        # MC 1.20.x
  rtp-fabric-1_21/        # MC 1.21
  rtp-fabric-1_21_1/      # MC 1.21.1
  rtp-fabric-1_21_2/      # MC 1.21.2
  rtp-fabric-1_21_4/      # MC 1.21.4
  rtp-fabric-1_21_5/      # MC 1.21.5
  rtp-fabric-1_21_8/      # MC 1.21.8
  rtp-fabric-1_21_11/     # MC 1.21.11
  rtp-fabric-26_1/        # MC 26.1 (year-based; "Mounts of Mayhem" line, succeeds 1.21.11)
  rtp-fabric-26_2/        # MC 26.2 (current target as of 2026-05)
```

Each per-version module:

- Applies `fabric-loom` with the Loom version compatible with that MC line. (Loom version selection is delegated to the build script per module; the project does not pin a single Loom across all modules.)
- Pins its own `minecraftVersion`, `loaderVersion`, `fabricApiVersion`, and uses `loom.officialMojangMappings()`.
- Depends on `rtp-fabric/rtp-fabric-common`, `rtp-api`, `rtp-core`, and `commands-api`.
- Owns the version-specific concrete classes: `FabricServerAccessor<ver>`, `FabricRTPWorld<ver>`, `FabricRTPPlayer<ver>`, `FabricEventBridge<ver>`, `RTPCmdFabric<ver>`. Each lives in a **distinct top-level package** `io.github.dailystruggle.rtp.fabric_<ver>.*` so that intermediary class names from different MC versions never collide in the shadow JAR.
- Produces its own Loom `remapJar` output containing intermediary-mapped bytecode for that MC version.

`rtp-fabric/rtp-fabric-common` retains:

- Abstract base classes (`AbstractFabricServerAccessor`, `AbstractFabricEventBridge`, `AbstractFabricRTPWorld`, `AbstractFabricRTPPlayer`) that declare the per-version contract using only `rtp-api` types and stable Fabric-Loader API.
- `FabricScheduler` — uses only `MinecraftServer.execute(Runnable)` (a long-stable Fabric API surface) and is therefore version-agnostic.
- `FabricDatabaseHandler` — uses only `rtp-core` symbols and stable Fabric API.
- Version-detection utilities consumed by the dispatcher.

### 3. Packaging — Single JAR, Multi-Loader, Multi-Version Dispatch

RTP shall ship as a **single JAR** that loads on both Bukkit-family servers and Fabric servers, and on every supported Fabric MC version.

- `rtp-plugin` remains the multi-loader bootstrap module with two entry-point classes in disjoint packages:
  - `io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin` — `JavaPlugin`, declared in `plugin.yml`, dispatches per-NMS-version Bukkit-family adapter as today.
  - `io.github.dailystruggle.rtp.fabric.RTPFabricMod` — `ModInitializer`, declared in `fabric.mod.json`, dispatches per-MC-version Fabric adapter.
- `RTPFabricMod` shall reference **no `net.minecraft.*` symbols**. It uses only `net.fabricmc.*` (loader API) and `rtp-api` / `rtp-core` types. Any concrete `Component`, `ServerLevel`, `ServerPlayer`, etc. usage lives in the per-version submodules. This is the rule that lets Loom in `rtp-plugin` compile against any chosen MC version without that choice leaking into runtime selection.
- The shadow JAR ingests each per-version module's **`remapJar` output** (intermediary bytecode), not its raw `compileJava` output. This is the build-discipline change in §4.
- Both `plugin.yml` and `fabric.mod.json` ship at the JAR root. Each loader ignores the metadata file it does not recognize.

### 4. Build Discipline

- **Loom application.** `fabric-loom` shall be applied to every `rtp-fabric/rtp-fabric-<ver>` submodule, to `rtp-fabric/rtp-fabric-common-unobf` (the deobf-26.x unobfuscated common carrier; see `rtp-fabric-ADR-009`), to `rtp-plugin`, to `effects-api` (per `effects-api-ADR-003`, to support the in-module `effectsapi/fabric` subpackage), and to `effects-api/effects-api-fabric-unobf` (the deobf-26.x unobfuscated effects-api carrier; see `effects-api-ADR-006`). It shall **not** be applied at the root, to `rtp-core`, to `rtp-api`, to `commands-api`, or to any Bukkit-family adapter.
- **Per-version Loom plugin.** Each per-version module pins its own Loom plugin version compatible with the MC line it targets. The version-agnostic `rtp-fabric-common` does **not** apply Loom — it compiles as a plain Java module against `rtp-api`/`rtp-core` and the stable Fabric-Loader API surface only.
- **Shadow ingestion.** `rtp-plugin/build.gradle`'s `shadowJar` task shall include the **`remapJar` output** of each per-version module. Including raw compile output would re-mix Mojang-mapped class files across versions and produce a JAR Loader cannot load.
- **Mappings.** `loom.officialMojangMappings()` for every per-version module. Yarn is reserved for re-evaluation if the community requests it.
- **Dual-runtime end-to-end smoke test.** A CI matrix shall load the produced JAR on a Paper test server **and** on at least one Fabric test server per supported MC line, asserting that `/rtp` executes end-to-end. The matrix is the only automated guard against per-version remap regression. The gate is anchored at **Phase 2 Step H** (stabilization).

### 5. Architectural Invariants Preserved

The following hard lines remain unchanged and shall be enforced by existing ArchUnit guards:

- `rtp-core`, `rtp-api`, `commands-api`, `effects-api` shall contain zero platform imports.
- `rtp-spigot`, `rtp-paper`, `rtp-folia` shall contain Bukkit-family imports only.
- `rtp-fabric/rtp-fabric-common` shall not import `net.minecraft.*` (only stable Fabric-Loader API and `rtp-api`/`rtp-core`).
- `rtp-fabric/rtp-fabric-<ver>` modules shall contain Fabric imports and MC-mapping imports for that version only; they shall not import `org.bukkit.*` and shall not cross-reference each other.
- The `RTPBukkitPlugin` package and the `RTPFabricMod` package within `rtp-plugin` shall not import each other and shall not transitively reach the other platform's classes. `RTPFabricMod` additionally shall not import any `net.minecraft.*` symbol.
- The per-version Fabric package roots `io.github.dailystruggle.rtp.fabric_<ver>` shall be **mutually disjoint**.

### 6. Version Naming

The submodule suffix `<ver>` is the underscored MC version (`1_21_5`, `26_2`, ...). Mojang's year-based versioning (introduced for the 2026 release line — `26.1` succeeds `1.21.11`, `26.2` succeeds `26.1`; see https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system) maps directly: `26.2` → `26_2`. The dispatcher in `RTPFabricMod` translates `SharedConstants.getCurrentVersion().getName()` (e.g. `"26.2"`) to the underscored form (`"26_2"`) and then to the FQN `io.github.dailystruggle.rtp.fabric_26_2.FabricServerAccessor26_2`.

### 7. Implementation Order

The phased implementation order, acceptance gates, and risk mitigations are recorded in `docs/dev/MULTI_PLATFORM_PLAN.md` (Phase 0 through Phase 3). The 2026-05-01 restructure introduces an explicit *skeleton-first* sub-phase: scaffolds for all supported MC versions land before any version is fully implemented, so that `:rtp-plugin:remapJar` and `:rtp-plugin:shadowJar` succeed for the union of all version modules from day one. Implementation depth proceeds version-by-version, prioritized by user demand (currently `26_2`).

### 8. Ownership

A **named maintainer** shall own the Fabric adapter end-to-end (build, mappings, CI matrix, S-00x proofs, ongoing maintenance). The matrix grows with each new MC release; ownership is the gating factor on adding a new version to the supported set, not technical effort.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Single Fabric module pinned to one MC version (the original 2026-04-30 ADR-022 §2 design) | Confirmed unworkable: Loom intermediary→runtime mapping is per-MC, and Fabric Loader provides no runtime fallback equivalent to Bukkit's reflective NMS. Resulted in `NoClassDefFoundError: net.minecraft.class_2561` on a `26.2` server when the JAR was built against `1.21.1`. |
| Ship one Fabric JAR per MC version (10 separate downloads) | Same BuiltByBit / release-surface problem the original ADR §2 rejected. The per-version submodule structure delivers identical isolation guarantees inside a single JAR via package-disjoint dispatch. |
| Use Yarn or hashed mappings instead of Mojmap to share more bytecode across versions | Hashed mappings are not stable across MC versions either (the underlying obfuscation map changes); Yarn introduces a separate maintenance dependency without removing the per-version remap requirement. |
| Adopt Architectury for cross-loader sharing | Architectury produces N binaries from one source tree, not one binary covering N runtimes. Solves a different problem; reconsider at Phase 4 if Forge/NeoForge re-enter scope. |
| Move version dispatch into `rtp-fabric-common` rather than `rtp-plugin/RTPFabricMod` | Common would then have to know every version's FQN, defeating its version-agnostic role. Dispatch belongs at the entry point — same place Bukkit-family does it. |

## Consequences

- **Positive:**
  - Resolves the runtime `NoClassDefFoundError` crash by structurally aligning with how Fabric Loader handles per-MC mappings.
  - Each Fabric MC version is independently buildable, testable, and deployable. A regression in one version's mapping does not block the others.
  - Mirrors the Bukkit-family layout developers already understand.
  - Adding a new MC version is a mechanical scaffold step (copy a sibling, change the version pin, implement the abstract methods), not an architectural change.
- **Negative / Trade-offs:**
  - Build complexity grows: 10 Loom configurations, each with its own mappings download. CI cache pressure goes up correspondingly.
  - Shadow-jar plumbing must consume Loom `remapJar` outputs rather than raw compile outputs — a non-default Gradle pattern that needs documentation in `MULTI_PLATFORM_PLAN.md`.
  - The single-JAR size grows with each supported version. Per the precedent in the original ADR (Bukkit-family version adapters), the inert-bytecode cost is acceptable for the distribution-surface gain.
  - Per-version Loom plugin versions can drift; the build script must document the compatibility matrix.
- **Guardrails preserved:**
  - Do not backport Fabric-specific patterns into `rtp-core` or `rtp-api`.
  - Do not introduce new `rtp-api` interfaces speculatively for Fabric.
  - The two entry-point packages in `rtp-plugin` shall remain disjoint; `RTPFabricMod` shall remain free of `net.minecraft.*` imports; per-version Fabric packages shall remain disjoint.

## References

- `docs/dev/REQUIREMENTS.md §0` (scope), `REQ-RTP-SYS-002`, `REQ-RTP-NF-003` (entry-point isolation, applied per-entry-point under this ADR)
- `docs/dev/MULTI_PLATFORM_PLAN.md` — phased plan, abstraction gap summary, acceptance gates, multi-version skeleton-first sub-phase
- ADR-005 — PaperLib removal (preserves the "no async-chunk shim" stance Fabric must respect)
- ADR-010 — Versioned platform adapter submodules (this ADR brings Fabric into the same pattern)
- commands-api-ADR-001 (formerly ADR-014) — Brigadier bridge via `commands-api` (each per-version Fabric adapter consumes the bridge, does not duplicate it)
- ADR-018 — `AGENTS.md` public-release structure (compatible with multi-version single-JAR multi-loader)
- ADR-021 — Legacy Minecraft and Java support out of scope (untouched by this ADR; Fabric ≠ legacy)
- REQ-RTP-S-005 — No synchronous chunk I/O on the main thread (Step A acceptance gate)
- REQ-RTP-S-006 — No undefined behaviour on early API access (Step B acceptance gate)
- External precedent: Geyser, LuckPerms, ViaVersion, Floodgate — single-JAR multi-loader bootstrap in production. Per-MC-version Bukkit-family adapters in this project (`rtp-spigot/rtp-spigot-v*`, `rtp-paper/rtp-paper-v*`, `rtp-folia/rtp-folia-v*`) — internal precedent for the per-version submodule structure now extended to Fabric.
- Mojang version-numbering change (2026): https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system
- 2026-05-01 deployment crash log demonstrating the single-version structural flaw: `NoClassDefFoundError: net.minecraft.class_2561` at `RTPFabricMod.onInitialize:52` on a `26.2` Fabric server when JAR was built against `1.21.1`.
