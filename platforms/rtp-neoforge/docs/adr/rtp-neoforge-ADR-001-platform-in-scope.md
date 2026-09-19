# rtp-neoforge-ADR-001 — NeoForge Platform Is In Scope (Per-MC-Version Submodule Layout, Mojmap-at-Runtime)

**Status:** Accepted (2026-06-01; D-005 bring-up proposal approved by project lead)
**Date:** 2026-06-01

> Subproject ADR numbering restarts at `001` per the *Self-Updating Protocol* in [`.junie/AGENTS.md`](../../../../.junie/AGENTS.md). This record is the subproject-scoped companion to the project-wide [ADR-033](../../../../docs/adr/ADR-033-neoforge-platform-in-scope.md) (NeoForge in-scope, gated). It mirrors the shape of [rtp-fabric-ADR-002](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md).

## Context

[ADR-033](../../../../docs/adr/ADR-033-neoforge-platform-in-scope.md) made NeoForge an in-scope target platform but **hard-gated** activation behind Fabric stabilization (ADR-033 section 2) and required a bring-up sequence (ADR-033 section 3): a D-005 proposal, this subproject ADR, `MULTI_PLATFORM_PLAN.md` phase rows, and TRACEABILITY rows for the S-005 / S-006 guards before any code lands.

As of 2026-06-01 the Fabric platform is **confirmed stable**: the Phase 2 Step H dual-runtime smoke test passed, the historical S-005 / null-stub / Loom blockers are resolved, and the Fabric beta shipped (Phase 3, 2026-05-31). The activation gate is therefore clear, and the D-005 proposal ([`docs/dev/scratch/PROPOSAL-neoforge-bringup.md`](../../../../docs/dev/scratch/PROPOSAL-neoforge-bringup.md)) is on file. This ADR ratifies the NeoForge adapter's structural decisions so Phase N1 code can begin once a maintainer is assigned and the proposal is approved.

The landscape analysis, API-surface delta from Fabric, reuse map, risks, and S-00x mapping are recorded in [`NEOFORGE_NOTES.md`](../../../../docs/dev/NEOFORGE_NOTES.md); this ADR converts the relevant conclusions into binding structural decisions.

## Decision

### 1. Scope

NeoForge is a **first-class, in-scope target platform** for RTP, alongside Spigot, Paper, Folia, and Fabric. Legacy Forge (≤1.20.1), Sponge, Minestom, hybrid servers (Mohist / Magma / Arclight), and Bedrock-native servers remain out of scope. Hybrid servers are covered transitively via `rtp-paper` as a runtime-compatibility courtesy only, not a native adapter.

### 2. Module layout — per-MC-version submodules, sibling to `rtp-fabric`

The `platforms/rtp-neoforge/` module group mirrors the Bukkit-family and Fabric per-version adapter pattern ([rtp-fabric-ADR-001](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-001-multiversion-submodule-layout.md), [ADR-010](../../../../docs/adr/ADR-010-versioned-platform-adapter-submodules.md)). It is a **sibling** of `rtp-fabric`; it is **not** nested under or sharing a source tree with it (ADR-033 alternatives; `NEOFORGE_NOTES.md` section 11 — divergent build systems and event models make a shared tree a trap).

```
platforms/rtp-neoforge/
  docs/adr/                  # subproject ADRs (this file)
  REQUIREMENTS.md            # REQ-NEOFORGE-F-* / REQ-NEOFORGE-ARCH-*
  rtp-neoforge-common/       # version-agnostic; rtp-api / rtp-core / stable NeoForge API only; no net.minecraft.* beyond stable surfaces
  rtp-neoforge-v1_21_R1/     # MC 1.20.4+ / 1.21.x carrier
  rtp-neoforge-v26_2_R1/     # MC 26.x carrier (year-based versioning)
```

Each per-version carrier owns the version-specific concrete classes (`NeoForgeServerAccessor<ver>`, `NeoForgeRTPWorld<ver>`, `NeoForgeRTPPlayer<ver>`, `NeoForgeEventBridge<ver>`) in a **distinct top-level package** `io.github.dailystruggle.rtp.neoforge_<ver>.*` so MC-mapping symbols from different versions never collide.

### 3. Mappings — Mojmap-at-runtime; no obf carrier expected

NeoForge runs against Mojang mappings at runtime on 1.20.4+. Unlike Fabric (which needs the obf carrier split of [rtp-fabric-ADR-009](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) for its intermediary namespace), NeoForge most likely needs **no obf carrier**. The **structural** separation still applies: NM-typed surfaces stay isolated in per-version carriers, never linked from `rtp-core` / `rtp-api`, following the Mojmap-decoupling discipline of [rtp-fabric-ADR-007](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-007-mojmap-name-decoupling.md). If NeoForge ever ships an SRG/intermediate-mapped runtime, this decision is revisited.

### 4. Build toolchain — ModDevGradle

The NeoForge Gradle plugin is **ModDevGradle** (the newer official path, closer to Loom in spirit) rather than NeoGradle, pending a confirmation spike (`NEOFORGE_NOTES.md` section 8). It is applied **only** under `platforms/rtp-neoforge/**` (and `rtp-plugin` only if a combined multi-loader artifact is pursued). Java 21+ toolchain per REQ-RTP-SYS-001. Platform-neutral modules (`rtp-core`, `rtp-api`, `rtp-anvil`, `commands-api`, `effects-api`, `metrics-api`, `maps-api`) are reused 1:1 with no NeoForge coupling.

### 5. Entry point, event bus, and command registration

- `@Mod`-annotated entry point + `META-INF/neoforge.mods.toml`. Mod-bus / game-bus `IEventBus` subscriptions replace Fabric's callback registries.
- A `NeoForgeVersionAdapter` classpath-probe dispatcher selects the matching per-version carrier (mirrors `RTPFabricMod` / `RTPBukkitPlugin`); the entry point references no `net.minecraft.*` symbol.
- Command registration reuses the `commands-api` `BrigadierCommandAdapter` ([commands-api-ADR-001](../../../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)); only the registration trampoline differs (`RegisterCommandsEvent` on the mod bus vs. Fabric's `CommandRegistrationCallback`). No per-platform command-tree duplication.

### 6. Threading & S-00x invariants preserved

NeoForge servers are single-main-thread vanilla — **no Folia region-ownership analog; do not invent one.** All S-00x prohibitions apply on the same terms as every other platform (ADR-033 section 5, no grandfathering):

- **S-005 (critical):** `NeoForgeRTPWorld.getChunkAt` returns `CompletableFuture` and routes through `MinecraftServer#submit` / the server-tick executor; never a synchronous `ServerLevel#getChunk(..., load=true)` on the tick thread. The Fabric `getChunkAt` S-005 regression must **not** be re-introduced.
- **S-006:** API entry points throw `IllegalStateException` pre-init, never null / no-op. NeoForge mod-loading phases make this fail-loud contract a natural fit.
- **S-002:** non-persistent `DistanceManager` chunk tickets, porting the Fabric ticket pattern ([rtp-fabric-ADR-003](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-003-non-persistent-chunk-tickets.md)).
- **S-001 / S-004:** block-safety logic and `FailTypes.nullChunk` attribution are platform-neutral (`rtp-core`); no fork-API second check, no swallowed failures.
- **S-003:** mod-side claim integrations (FTB Chunks, OpenPartiesAndClaims, Argonauts) are reflection-gated soft hooks per [ADR-026](../../../../docs/adr/ADR-026-external-hook-api-surface.md), cataloged in `EXTERNAL_HOOKS.md`; no claim-mod code in the pipeline.

### 7. Architectural invariants (ArchUnit-enforceable)

- `rtp-core`, `rtp-api`, `commands-api`, `effects-api`, `metrics-api`, `maps-api` contain zero platform imports.
- `rtp-neoforge-common` does not import `net.minecraft.*` beyond stable NeoForge API; only `rtp-api` / `rtp-core` / stable NeoForge surfaces.
- `rtp-neoforge-v<ver>` modules contain NeoForge and MC-mapping imports for that version only; they do not import `org.bukkit.*` or `net.fabricmc.*`, and do not cross-reference each other.
- The NeoForge entry-point package and the Fabric / Bukkit entry-point packages in `rtp-plugin` (if a combined artifact ships) remain mutually disjoint.

### 8. Ownership

A **named maintainer** shall own the NeoForge adapter end-to-end (build, mappings, CI toolchain, S-00x proofs, ongoing maintenance) before Phase N1 begins (ADR-033 / ADR-022). **Owner: project lead (`@leaf_26`), assigned 2026-06-01.**

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Fold NeoForge into a shared "modded" tree with Fabric. | Build systems (ModDevGradle vs. Loom), event models, and registration trampolines diverge enough to couple two unrelated dependency graphs and slow both. Keep adapters sibling, not nested (ADR-033). |
| Cover NeoForge via a hybrid server (Mohist / Arclight) instead of a native adapter. | Hybrid servers are unstable, lag MC versions, and violate both ecosystems' assumptions. Acceptable transitively via `rtp-paper`, not a substitute for a native adapter. |
| Cover the audience via legacy Forge. | Forge ≤1.20.1 is sunsetting; the active ecosystem has moved to NeoForge. |
| Adopt an obf/intermediary carrier like Fabric's. | NeoForge is Mojmap-at-runtime on 1.20.4+; only the structural per-version split is needed, not an intermediary-namespace carrier. Revisit only if NeoForge ships an SRG-mapped runtime. |
| Adopt Architectury for cross-loader sharing. | Produces N binaries from one source tree, not one adapter covering N runtimes; re-evaluate only if maintaining parallel Fabric + NeoForge carrier trees proves costly (`NEOFORGE_NOTES.md` section 11). |

## Consequences

- **Positive:**
  - Aligns the adapter structure with the proven Fabric / Bukkit-family per-version layout; adding a new MC version is a mechanical scaffold step.
  - Reuses `rtp-core` / `rtp-api` / `commands-api` / anvil / metrics / maps with zero new interfaces.
  - The Mojmap-at-runtime path is simpler than Fabric's (no obf carrier expected).
- **Negative / Trade-offs:**
  - Supported platform matrix grows to five families, expanding release-test and lite-jar ([ADR-024](../../../../docs/adr/ADR-024-rtp-lite-assembly-variant.md)) surfaces.
  - A second mod-loader build system (ModDevGradle) enters the build; CI cache pressure grows.
  - Per-version mapping drift must be absorbed by carriers and kept out of `rtp-core`.
- **Guardrails preserved:** do not backport NeoForge-specific patterns into `rtp-core` / `rtp-api`; do not add speculative `rtp-api` interfaces for NeoForge; do not invent a Folia-style region model.

## References

- [ADR-033](../../../../docs/adr/ADR-033-neoforge-platform-in-scope.md) — project-wide NeoForge in-scope (gated) decision this ADR implements.
- [`NEOFORGE_NOTES.md`](../../../../docs/dev/NEOFORGE_NOTES.md) — landscape, API-surface delta, reuse map, risks, S-00x mapping.
- [`docs/dev/scratch/PROPOSAL-neoforge-bringup.md`](../../../../docs/dev/scratch/PROPOSAL-neoforge-bringup.md) — the D-005 bring-up proposal.
- [rtp-fabric-ADR-002](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) — precedent shape (Fabric in-scope, multi-version single-JAR).
- [rtp-fabric-ADR-001](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-001-multiversion-submodule-layout.md), [rtp-fabric-ADR-003](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-003-non-persistent-chunk-tickets.md), [rtp-fabric-ADR-007](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-007-mojmap-name-decoupling.md), [rtp-fabric-ADR-009](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) — reused patterns and contrasts.
- [ADR-010](../../../../docs/adr/ADR-010-versioned-platform-adapter-submodules.md), [ADR-024](../../../../docs/adr/ADR-024-rtp-lite-assembly-variant.md), [ADR-026](../../../../docs/adr/ADR-026-external-hook-api-surface.md), [commands-api-ADR-001](../../../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md).
- [`MULTI_PLATFORM_PLAN.md`](../../../../docs/dev/MULTI_PLATFORM_PLAN.md) — Phase 4 (`rtp-neoforge`) phase rows.
- REQ-RTP-S-005 / REQ-RTP-S-006 — S-005/S-006 guards (`ReqRtpNeoforgeS005ChunkLoadingTest`, `ReqRtpNeoforgeS006EarlyApiTest`) per ADR-033 section 3.4.
