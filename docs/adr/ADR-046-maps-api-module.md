# ADR-046 — `maps-api` Module for Runtime Cartography Chart Generation

**Status:** Accepted (amended 2026-06-04 - REQ-RTP-MAP-004 Lite posture reversed; see *Amendments*)
**Date:** 2026-05-15

## Context

RTP increasingly wants to surface live 2D imagery to operators and players —
region density, cache pool (L1/L2/L3) occupancy, spiral coverage progress,
scan-task crawler footprint, biome distribution from the `rtp-anvil` prefilter,
and time-series of TPS / MSPT / pipeline latency captured under
[`METRICS_PLAN.md`](../dev/METRICS_PLAN.md). The native Minecraft cartography
map (`MapView` on Bukkit, `MapItemSavedData` + `ClientboundMapItemDataPacket`
on Fabric) is the only in-world 2D imaging surface available without a client
mod, and it is reachable from every supported platform.

Three structural facts shape the decision:

1. The chart-generation logic (pixel buffers, palettes, layout) is
   platform-neutral; it is pure data → pixels.
2. The *delivery* of those pixels is platform-specific: Bukkit
   `MapRenderer.render(...)`, Folia map-items with no owning region, Fabric
   NM-typed packets that must be dispatched through obf/unobf carriers
   ([rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md),
   [effects-api-ADR-006](../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md)).
3. The chart data sources (`MemoryTracker` samples, `FailTypes` counters,
   anvil-prefilter biome rasters, the Archimedean spiral
   [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)) already live in
   `rtp-core` and `rtp-anvil`.

Folding this into `rtp-core` would force a platform import there (forbidden by
*Architecture Boundaries §2*). Folding it into `effects-api` or `commands-api`
would conflate concerns: `effects-api` dispatches discrete sensory effects
(sounds, particles), `commands-api` dispatches Brigadier nodes, neither owns a
persistent 2D pixel surface or a per-viewer refresh cadence.

The shape that *does* fit is the one `effects-api` and `commands-api` already
prove: a top-level module with platform-neutral SPI, a Noop default for
test/Lite, and per-platform bindings — including a Mojmap obf/unobf carrier
split for Fabric per
[rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md).

The Folia case requires special note: a `MapView` is not entity-owned and has
no region. Refresh writes must therefore route through the Global Region
Scheduler rather than an entity scheduler, in contrast to teleport dispatch
which uses the entity scheduler per *AGENTS.md > Folia Threading*.

## Decision

A new top-level module **`maps-api/`** (sibling to `effects-api/` and
`commands-api/`) shall host the runtime cartography chart-generation
subsystem. It is structured in three layers:

1. **Layer 1 — Models** (`maps-api/.../mapsapi/model/`): immutable
   `sealed interface ChartModel` permitting `Heatmap2D`,
   `CategoryDistribution`, `TimeSeries`, `RegionCoverage`, `MermaidChart`.
   No platform imports. No `rtp-core` imports.
2. **Layer 2 — Renderers** (`maps-api/.../mapsapi/render/`):
   `ChartRenderer<M extends ChartModel>` with `void render(MapCanvas, M)`.
   Pure functions; no I/O, no platform imports. Built-ins: `HeatmapRenderer`,
   `CategoryPieRenderer`, `SparklineRenderer`, `RegionCoverageRenderer`,
   `MermaidRenderer` (see *Mermaid output* below).
3. **Layer 3 — Bindings** (per-platform): `MapBinding` SPI with
   `allocate(MapAllocationRequest)`, `renderEphemeral(...)`,
   `bindLive(handle, renderer, modelSupplier, refresh) → Cancellation`.
   Implementations:
   - `NoopMapBinding` in `maps-api/src/main/java/...noop/` — default; throws
     `IllegalStateException` on use to satisfy *Require-by-contract* (mirrors
     S-006). Installed as the fallback when no platform binding is present.
     (Amended 2026-06-04: the Lite assembly now ships the platform-backed
     binding, not only this Noop. See *Amendments*.)
   - `InMemoryMapBinding` in `maps-api/src/test/java/...` — test double that
     writes into a `byte[128*128]` buffer for renderer assertions.
   - `BukkitMapBinding` in `maps-api/.../mapsapi/bukkit/` — uses
     `MapView` + `MapRenderer`. The only file in this module permitted to
     import `org.bukkit.*`. Shared by Spigot, Paper, Folia.
   - `FoliaMapBinding` in `platforms/rtp-folia/rtp-folia-common/` — thin override that
     routes the `commit()` step through the **Global Region Scheduler**
     (map-items have no owning region) and entity-scheduler hops to the
     viewer for any per-viewer mutation. Refresh ticks use
     `RTP.scheduler.runTaskTimerAsynchronously`.
   - `FabricMapBinding` via dispatcher in `maps-api/.../mapsapi/fabric/`,
     with a Mojmap-unobf carrier `maps-api-fabric-unobf/` and per-version
     NM-typed obf carriers under `platforms/rtp-fabric/rtp-fabric-common/.../maps/`,
     following the dispatch contract in
     [effects-api-ADR-006](../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md)
     and [rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md).

The single admin command is wired through the existing **`commands-api`
Brigadier bridge** ([commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)),
not per-platform command classes:

```
/rtp map create <chartId> [--viewer <player>] [--world <world>] [--refresh <duration>]
/rtp map list
/rtp map cancel <chartId>
```

All user-facing strings route through `messages.yml` per **REQ-RTP-F-013**;
the "busy" and "invalid command" responses remain configurable per **S-007**.
All logging routes through `RTP.log()` / `RTPServerAccessor.log()`. The
binding implementation registers with `MemoryTracker` at `bindLive` and
releases on cancel, viewer disconnect, and plugin disable, covering every
exit path per the `MemoryTracker` lifecycle rule.

Four new requirements are introduced (to be authored in
`docs/dev/REQUIREMENTS.md` during Stage 1 — see the multi-session checklist
at [`docs/dev/scratch/CHECKLIST-maps-api.md`](../dev/scratch/CHECKLIST-maps-api.md)):

| REQ | Statement (target wording) |
|-----|-----------------------------|
| `REQ-RTP-MAP-001` | A `MapBinding` implementation shall throw `IllegalStateException` when invoked before RTP core is loaded. |
| `REQ-RTP-MAP-002` | A `ChartRenderer` shall not perform chunk I/O nor block on `CompletableFuture.get()` / `.join()`. |
| `REQ-RTP-MAP-003` | A live binding shall release every `MemoryTracker` allocation it acquired on cancel, viewer disconnect, and plugin disable. |
| `REQ-RTP-MAP-004` | The Lite assembly variant shall ship the same platform-backed `MapBinding` as the full assembly; `NoopMapBinding` is the fallback only. *(Original target wording was "ship `NoopMapBinding` only"; reversed by the 2026-06-04 amendment - see below.)* |
| `REQ-RTP-MAP-005` | A `MermaidRenderer` shall accept the documented Mermaid subset (flowchart `LR`/`TD`, rectangular/rounded/diamond nodes, labelled directed edges) and rasterize to the active `MapCanvas` palette without invoking any external process or scripting engine. |

### Mermaid output

`MermaidChart` is a `ChartModel` record carrying the raw Mermaid source text
plus an optional `title`. `MermaidRenderer` lowers a **constrained Mermaid
subset** authored in-house — no Node.js, no headless browser, no JavaScript
runtime, no third-party Java Mermaid library in `maps-api`. The renderer's
output target is the same `MapCanvas` byte-palette surface as every other
renderer; the same `MapBinding` chain delivers it to in-game cartography
maps (`MapView` on Bukkit-family, `MapItemSavedData` on Fabric). Mermaid
output is therefore a *first-class peer* of the heatmap / pie / sparkline /
coverage renderers, not a separate disk-image pipeline.

Supported v1 subset (mirrors what RTP's own developer docs use):

- `flowchart LR` and `flowchart TD` direction tokens.
- Rectangular `id[label]`, rounded `id(label)`, and diamond `id{label}`
  node shapes.
- Directed edges `-->`, `--> |label|`, dashed `-.->`, and thick `==>`.
- Comments (`%% …`) and blank-line separators.

Out of subset (rejected at parse time with a `messages.yml`-routed user
message per S-007): subgraphs, class diagrams, sequence diagrams, ER
diagrams, Gantt, mindmaps, click-handlers, theming directives. These are
deliberate omissions — adding them is an additive ADR amendment when a real
authoring need lands.

Layout uses an in-house deterministic algorithm (longest-path layering for
`TD`, left-to-right Sugiyama-lite for `LR`); no graph-layout dependency.
For diagrams that exceed one 128×128 tile, the renderer paginates onto a
**tile grid** of `MapCanvas` surfaces — the `MapBinding` allocates N tiles
in a row × column layout supplied by `MapAllocationRequest.tiles(rows, cols)`,
and operators frame the tiles into a wall in-world. Multi-tile composition
formally promotes Stage 5.1 (item-frame mosaic) from "deferred follow-up"
to "required for Mermaid output beyond a single tile"; see
[`docs/dev/scratch/CHECKLIST-maps-api.md`](../dev/scratch/CHECKLIST-maps-api.md)
Stage 4 for the implementation slot.

A `RTPHooks` registry slot for `MapBinding` allows addons to swap the
implementation per [ADR-026](ADR-026-external-hook-api-surface.md); the
catalog row lands in [`docs/dev/EXTERNAL_HOOKS.md`](../dev/EXTERNAL_HOOKS.md)
during Stage 2.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Fold the SPI into `rtp-core` directly | Forces a platform import (`org.bukkit.map.MapView`) into `rtp-core`, breaking *Architecture Boundaries §2*. |
| Fold into `effects-api` | `effects-api` dispatches discrete sensory events with no persistent surface, no per-viewer cadence, and no pixel buffer. The two concerns share neither a model nor a delivery path. |
| Fold into `commands-api` | `commands-api` is a Brigadier bridge; pixel rendering is not a command concern. The `/rtp map` command is one consumer, not the subsystem. |
| Fold the `MapCanvas` SPI into `rtp-api` (public addon surface) only | `rtp-api` is the addon-facing contract; binding implementations carry platform imports and a non-trivial lifecycle that addons should consume, not author. Re-evaluate after Phase 3 if addons ask for it. |
| Per-platform fork (separate map renderer in each adapter) | Already proven anti-pattern by `effects-api` and `commands-api`; *Architecture Boundaries §3* explicitly says "extend these, don't fork per-platform." |
| Skip the Fabric obf/unobf split and use reflection in a single common module | Loom's intermediary remap fundamentally requires the split for 1.20.x / 1.21.x runtimes; reflection from deobf MC 26.x is unsafe ([rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)). |
| Render to a custom client-side surface (resource pack / mod overlay) | Requires a client mod, breaking vanilla-compatibility. Cartography maps work for every connecting client out of the box. |
| Defer to bStats only | bStats charts are off-server analytics, anonymized and aggregated. They cannot answer "what does the spiral look like on *my* server right now" or be inspected in-world by operators. The two pipelines stay independent. |
| Render Mermaid via a bundled Node.js / headless-browser / GraalJS runtime | Adds a multi-MB native or scripting dependency, breaks Java-21-only toolchain promise, breaks reproducibility across MC platforms, breaks Lite assembly trim. The in-house subset covers every diagram RTP itself authors. |
| Render Mermaid to disk PNG only (skip in-world delivery) | Contradicts the user-stated requirement: Mermaid output shall use in-game maps. A disk-only pipeline also bypasses `MapBinding` lifecycle, `MemoryTracker` accounting, and the `RTPHooks` registry — duplicating infrastructure for no operator-visible gain. |
| Embed a full Mermaid Java port (e.g. `mermaid-java` derivatives) | None of the surveyed ports cover the subset RTP needs without also pulling AWT/Batik/SVG transitively; license posture is mixed. An in-house parser+layout for the constrained subset is bounded scope (one renderer + its test). |

## Consequences

- **Positive:**
  - Adds an in-world 2D imaging surface usable by any RTP subsystem
    (`MemoryTracker`, `rtp-anvil`, `RegionQueueManager`, scan task) without
    coupling those subsystems to a platform.
  - Renderers are pure functions, trivially unit-testable via
    `InMemoryMapBinding` — no MockBukkit, no server harness.
  - Mirrors the established `effects-api` / `commands-api` shape, so
    contributor onboarding and Fabric obf/unobf carriers reuse already-proven
    patterns.
  - Establishes `REQ-RTP-MAP-001..004`, giving the subsystem the same
    require-by-contract / lifecycle / no-chunk-I/O posture as the rest of
    RTP from day one.
- **Negative / Trade-offs:**
  - Vanilla map palette is 256 colours, limiting chart fidelity; renderers
    must pick a fixed RTP palette mapping to stay deterministic across MC
    versions.
  - Fabric carriers duplicate code across `maps-api-fabric-unobf/` and
    per-version `platforms/rtp-fabric/rtp-fabric-common/.../maps/` modules. Same cost
    as `effects-api`; mitigated by mechanical translation rather than
    inventing a second pattern.
  - Folia map-items have no owning region, requiring the binding to take an
    explicit dependency on the Global Region Scheduler and document the hop
    in `BukkitMapBinding`'s javadoc + a Folia test.
  - Phasing is non-trivial — Phases 1–4 span at least four sessions per the
    multi-session checklist. Phase 3 is gated on Fabric platform stability
    (Fabric is "unstable" per *AGENTS.md > Current Development Focus* as of
    2026-04-30) and may slip behind Phases 2 and 4.
  - Mermaid output is limited to a constrained subset of the upstream Mermaid
    grammar. Diagrams authored against full Mermaid will be rejected at parse
    time rather than silently rendered wrong (S-004). The subset is bounded
    by what RTP's own docs use; expanding it is an additive ADR amendment.
  - Mermaid diagrams larger than one 128×128 tile require operators to frame
    a tile grid; the renderer cannot magic-shrink a complex graph onto a
    single map. The chosen layouts (flowchart `LR`/`TD`) make this trade-off
    visible: operators see a tile-count up front in `/rtp map create`.

## References

- [`docs/dev/scratch/CHECKLIST-maps-api.md`](../dev/scratch/CHECKLIST-maps-api.md) — multi-session implementation plan.
- [`docs/dev/REQUIREMENTS.md`](../dev/REQUIREMENTS.md) — host of REQ-RTP-MAP-001..004 (added in Stage 1).
- [`docs/dev/TRACEABILITY.md`](../dev/TRACEABILITY.md) — REQ → class → test rows (added incrementally per stage).
- [`docs/dev/METRICS_PLAN.md`](../dev/METRICS_PLAN.md) — time-series sources for `SparklineRenderer`.
- [`docs/dev/EXTERNAL_HOOKS.md`](../dev/EXTERNAL_HOOKS.md) — `MapBinding` `RTPHooks` slot (Stage 2).
- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) — spiral model consumed by `RegionCoverageRenderer`.
- [ADR-016](ADR-016-anvil-subsystem.md) — biome raster source for `Heatmap2D`.
- [ADR-024](ADR-024-rtp-lite-assembly-variant.md) — Lite-assembly trim posture; per the 2026-06-04 amendment the Lite jar does **not** exclude `mapsapi/**`, so map rendering ships in Lite (REQ-RTP-MAP-004).
- [ADR-026](ADR-026-external-hook-api-surface.md) — `RTPHooks` registry pattern.
- [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge for `/rtp map`.
- [effects-api-ADR-006](../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md) — obf/unobf split precedent.
- [rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) — Fabric carrier dispatch precedent.

## Amendments

### 2026-06-04 - Map rendering ships in the Lite assembly (REQ-RTP-MAP-004 reversed)

The original decision gated the concrete `MapBinding` implementations behind
the full (Pro) assembly: REQ-RTP-MAP-004 read "the Lite assembly variant
shall ship `NoopMapBinding` only", and this ADR planned a `liteJarStructureCheck`
clause excluding `mapsapi/bukkit/**`, `mapsapi/fabric/**`, and the renderer
packages from the Lite jar.

That trim was never an intentional monetization boundary (mirroring the
Vault/economy reversal in [ADR-024](ADR-024-rtp-lite-assembly-variant.md),
2026-06-01). The Lite jar ships the full `mapsapi/**` tree, and the Lite
bootstraps install the real platform-backed bindings: `RTPBukkitLitePlugin#onEnable`
installs `BukkitMapBinding` (and `BukkitBiomeColorSource`) unconditionally,
`RTPFabricMod` installs `FabricMapBinding`, and `RTPNeoForgeMod` installs
`NeoForgeMapBinding`. So `/rtp visualization ...` renders the heatmap onto a
real cartography map item on the free build across every supported platform.

Revised contract:

- REQ-RTP-MAP-004 now requires the Lite assembly to ship the same
  platform-backed `MapBinding` as the full assembly.
- `NoopMapBinding` remains the require-by-contract default (REQ-RTP-MAP-001)
  and the fallback installed only when no platform binding is available; it is
  no longer the *sole* binding on Lite.
- The `liteJarStructureCheck` audit's forbidden-entry list carries **no**
  `mapsapi/**` clause, so the maps packages are retained in the Lite jar
  (rather than excluding the concrete bindings as originally planned).

This amendment is documentation-only at the ADR level; the code already
behaves as described. `maps-api-ADR-001` §3 (Lite-assembly inclusion /
exclusion) is amended in lockstep.
