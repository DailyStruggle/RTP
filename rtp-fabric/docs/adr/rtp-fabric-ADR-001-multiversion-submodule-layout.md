# rtp-fabric-ADR-001 — Fabric Multiversion Submodule Layout

*(Renumbered from project-wide ADR-027 on 2026-05-05 when subproject ADRs were given per-directory numbering. Prior commits and historical references may still say "ADR-027".)*

**Status:** Accepted
**Date:** 2026-05-01

## Context

[rtp-fabric-ADR-002 — Fabric Platform In Scope](rtp-fabric-ADR-002-platform-in-scope.md) (originally numbered ADR-022, then ADR-031) established `rtp-fabric` as a first-class platform, with one submodule (`rtp-fabric-common`) pinning a single Minecraft version (1.21.1 at the time of writing). The Bukkit-family adapters have long since adopted a `<platform>-common` plus `<platform>-vXX_YY_R1` layout (e.g. `rtp-paper-common`, `rtp-paper-v1_20_R1`, `rtp-paper-v1_21_R1`, `rtp-paper-v26_1_R1`). Fabric did not.

Three forces now require Fabric to follow the same shape:

1. **Cross-version glue.** Even within the obfuscated era (1.20 → 1.21.x), mojmap rename drift exists — most prominently `ChunkStatus` moving from `net.minecraft.world.level.chunk.ChunkStatus` to `net.minecraft.world.level.chunk.status.ChunkStatus` in 1.21.3, and registry-access patterns shifting (`Registries.BIOME` vs. `BuiltInRegistries.BIOME` etc.). A single common module can compile against only one mojmap line.
2. **MC 26.1 is deobfuscated.** Per [Fabric's 26.1 porting guide](https://docs.fabricmc.net/develop/porting/), 26.1 is the first MC release shipped without obfuscation. That changes the Loom build script materially: plugin id changes from `fabric-loom` to `net.fabricmc.fabric-loom`, the `mappings` line is removed, `modImplementation` / `modCompileOnly` collapse to `implementation` / `compileOnly`, Loom 1.15+, Gradle 9.4+, and Java 25 are required. None of these can coexist with the 1.20 / 1.21 build script in the same submodule.
3. **Java toolchain split.** MC 26.1 mandates Java 25 minimum. The rest of the project targets Java 21 (REQ-RTP-SYS-001). A per-submodule toolchain pin is the only way to keep contributors without a JDK 25 install able to build the rest of the project.

## Decision

Adopt a Fabric submodule layout that mirrors the Bukkit-family pattern:

| Module | MC version pin | Mappings | Loom | Java toolchain | Fabric API pin (current) |
|---|---|---|---|---|---|
| `rtp-fabric:rtp-fabric-common` | (compile-only) 1.21.1 mojmap | mojmap | 1.11+ | 21 | (compileOnly) 0.115.0+1.21.1 |
| `rtp-fabric:rtp-fabric-v1_20_R1` | 1.20.1 | mojmap | 1.11+ | 21 | 0.92.x+1.20.1 |
| `rtp-fabric:rtp-fabric-v1_21_R1` | 1.21.1 | mojmap | 1.11+ | 21 | 0.115.0+1.21.1 |
| `rtp-fabric:rtp-fabric-v26_1_R1` | 26.1.2 | (none — deobfuscated) | 1.15+ | 25 | 0.143.5+26.1 |

### Common module rules

- `rtp-fabric-common` keeps `fabric-loom` applied (so it can compile against MC types) but declares Minecraft and `fabric-api` as `compileOnly` / `modCompileOnly` rather than `modImplementation`. **No MC or fabric-api classes are shipped from common.** Each v-submodule supplies the actual runtime mod jar.
- Common defines a small SPI — `FabricVersionAdapter` and a handful of helper sub-interfaces — that absorb the version-volatile call sites identified in the symbol-surface inventory done during this ADR's (formerly ADR-027) drafting:
  - **Registry access** (block / biome / dimension `ResourceLocation` lookups). Wrapper rationale: `BuiltInRegistries` field names and the `Registries`-vs-`BuiltInRegistries` split shifted across 1.20 → 1.21 → 26.1.
  - **`ChunkStatus` location** (package move at 1.21.3).
  - **Chunk loading entrypoint** (`ServerChunkCache#getChunk` signature is stable, but the `boolean load` / `boolean generate` argument semantics drift across versions; the adapter normalises them).
  - **Biome key lookup at a `BlockPos`** (the `Holder<Biome>` → `ResourceKey` indirection changed in 1.20.5).
  - **Permissions API surface** (Fabric Permissions API is itself versioned per MC line).
- Everything else (Brigadier command building, lifecycle event subscription, `MinecraftServer` / `ServerLevel` / `ServerPlayer` / `BlockPos` / `ChunkPos` / `BlockState` / `Heightmap` / `LightLayer` references) stays in common because those types are mojmap-stable across the supported range.

### V-submodule rules

- Each v-submodule applies Loom with its own pinned MC, fabric-api, loader, and (for the obf era) mappings versions.
- Each v-submodule supplies a single `FabricVersionAdapter` implementation class living under `io.github.dailystruggle.rtp.fabric.vXX_YY_R1` (parallel to Bukkit-family `nms.vXX_YY_R1` packages).
- V-submodules **may** include MC-version-specific `fabric.mod.json` / `mixins.json` if needed, but baseline behavior reuses common assets via Loom's resource processing.
- V-submodules **shall not** contain business logic. Anything that does not require MC-version-specific symbols belongs in common.

### Runtime selection (`rtp-plugin`)

- The Fabric bootstrap (`RTPFabricMod`) reads `net.minecraft.SharedConstants.getCurrentVersion().getName()` at server-start, classifies it into `{1.20.x, 1.21.x, 26.1+}`, and reflectively instantiates the matching v-submodule's adapter via `Class.forName(...).getDeclaredConstructor().newInstance()`.
- Reflection — not direct symbol reference — is mandatory: a Java 21 server starting on 1.21.1 must not resolve any `rtp-fabric-v26_1_R1` class (Java 25 bytecode), which would throw `UnsupportedClassVersionError`. Class loading is lazy; classes that are never named directly are never resolved.
- If no adapter matches, the bootstrap falls back to a `NoOpFabricVersionAdapter` and logs a warning per S-006 (do not silently no-op API entry points; throw on user-visible RTP calls).

### Forge / NeoForge

Out of scope. rtp-fabric-ADR-002 (formerly ADR-031) §2 keeps Forge / NeoForge deferred until Fabric stabilises, and this ADR does not change that.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Pure-Java SPI common (zero MC types in common)** | Forces ~30 SPI methods to use `Object` + casts because the abstraction targets MC types like `ServerLevel`. Roughly doubles the code size for ~5% additional isolation, since the MC types we use are 95% mojmap-stable across 1.20 → 26.1. Cost greatly exceeds value. |
| **Single common module, lift Loom to 1.15 + Java 25** | Forces every contributor (and every CI runner) to install JDK 25, including those only modifying `rtp-core` or `rtp-bukkit`. Couples the project's overall Java baseline to Fabric's bleeding edge. |
| **Defer 26.1 entirely; ship only v1_20_R1 + v1_21_R1** | User explicitly requested whole-MC-line support. A future-proof skeleton that can absorb 26.1 with one new submodule is cheaper than retrofitting later, even if v26_1_R1 ships initially as a stub. |
| **Bytecode-rewriting / multi-release JARs** | Loom doesn't support multi-release JARs cleanly across mappings boundaries; pre-release Loom 1.15 changes its mod-remapping pipeline enough that mixing it with 1.11 in the same jar is fragile. |

## Consequences

### Positive
- Each MC version pin is isolated; updating fabric-api or porting a single MC line never touches the others.
- 26.1's deobf / Java 25 / Loom 1.15 build prerequisites are confined to `rtp-fabric-v26_1_R1`, leaving the rest of the project on Java 21.
- Symmetric with `rtp-bukkit` / `rtp-paper` / `rtp-folia`, lowering the cognitive cost of adding a new MC version (one new `vXX_YY_R1` submodule).
- The SPI surface is minimal (only the version-volatile bits), so future MC versions usually need no new SPI methods.

### Negative / Trade-offs
- More modules means more `build.gradle` files to keep in lockstep on shared concerns (Java toolchain, dependency wiring). Mitigated by Gradle convention plugins or a shared `subprojects` block scoped to `rtp-fabric:**`.
- Reflective version selection means a typo in an adapter class name surfaces only at server start, not at compile time. Mitigated by a smoke test (`ReqRtpFabricMultiversionTest`) that does `Class.forName` on each expected adapter against the test classpath.
- Common's `compileOnly` mojmap-1.21.1 dep means common compiles against 1.21.1 names; if a deobf rename in 26.1 breaks one of those names at runtime in v26_1_R1, the v26_1_R1 adapter must shim it. Listed as a known risk per the inventory.

## References

- [rtp-fabric-ADR-002 — Fabric platform in scope](rtp-fabric-ADR-002-platform-in-scope.md) (originally ADR-022, then ADR-031)
- [commands-api-ADR-001 — Brigadier bridge via commands-api](../../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)
- [ADR-016 — Anvil subsystem](../../../docs/adr/ADR-016-anvil-subsystem.md)
- [`docs/dev/MULTI_PLATFORM_PLAN.md`](../../../docs/dev/MULTI_PLATFORM_PLAN.md) — Fabric phase entry
- Fabric 26.1 porting guide — <https://docs.fabricmc.net/develop/porting/>
- Fabric for Minecraft 26.1 (2026-03-14) — <https://fabricmc.net/2026/03/14/261.html>
- Symbol-surface inventory (recorded in the issue thread for this ADR's drafting (formerly ADR-027)): 10 files in `rtp-fabric-common/src/main/java`, MC type usage limited to: `MinecraftServer`, `ServerLevel`, `ServerPlayer`, `Component`, `BuiltInRegistries`/`Registries`, `Registry`, `ResourceLocation`, `BlockPos`, `ChunkPos`, `ChunkAccess`, `ChunkStatus`, `LightLayer`, `Block`, `BlockState`, `Heightmap`, `ServerChunkCache`, `Util`.
