# maps-api-ADR-001 — Module Bootstrap, Package Layout, and Palette Policy

- **Status:** Accepted (2026-05-16); §Palette policy and §Package layout amended 2026-05-23 (additive: see *Amendments* below).
- **Supersedes:** —
- **Superseded by:** —
- **Related:**
  - [ADR-046](../../../docs/adr/ADR-046-maps-api-module.md) — umbrella ADR establishing the `maps-api/` module, its three-layer (Models / Renderers / Bindings) shape, the five `REQ-RTP-MAP-001..005` requirements, and the Mermaid subset.
  - [ADR-024](../../../docs/adr/ADR-024-rtp-lite-assembly-variant.md) — Lite-assembly trim posture; this ADR specifies how `maps-api` participates.
  - [ADR-026](../../../docs/adr/ADR-026-external-hook-api-surface.md) — `RTPHooks` registry pattern; the `MapBinding` slot lands in Stage 2 per the checklist.
  - [effects-api-ADR-003](../../../effects-api/docs/adr/effects-api-ADR-003-platform-split-bukkit-fabric.md) — in-module platform-split precedent that `maps-api` mirrors.
  - [commands-api-ADR-001](../../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge precedent for the `/rtp map` subcommand (wired in Stage 2).
  - [`docs/dev/scratch/CHECKLIST-maps-api.md`](../../../docs/dev/scratch/CHECKLIST-maps-api.md) — multi-session implementation plan; this ADR satisfies row 1.20.

---

## Context

[ADR-046](../../../docs/adr/ADR-046-maps-api-module.md) (Accepted, 2026-05-15) decided **that** `maps-api/` exists as a top-level sibling of `effects-api/` and `commands-api/`, and decided **what** it must host: three layers (Models, Renderers, Bindings), five REQs (`REQ-RTP-MAP-001..005`), a `NoopMapBinding` default, Bukkit-family and Fabric obf/unobf bindings in later stages, a Lite-only `NoopMapBinding` posture, an `RTPHooks` slot, and the in-house Mermaid subset.

It deliberately did **not** lock the in-module bootstrap decisions that Stage 1 of the checklist needs to make concrete:

1. **Package layout** under `io.github.dailystruggle.mapsapi.*` — how the three layers, the Noop default, and the platform-binding subpackages partition the source tree, and which subpackage is permitted to import `org.bukkit.*` / `net.minecraft.*`.
2. **Palette policy** — vanilla cartography maps use a 256-entry colour palette (`MapColor` on Bukkit, `MapColor.Brightness` × base colour on Mojmap) whose byte values are stable across MC versions for the documented entries but whose set has been extended in newer versions. Renderers must commit to *some* palette to produce deterministic byte output (per the existing Stage 1 deliverable 4.3 in the checklist).
3. **Lite-assembly inclusion / exclusion mechanics** — `REQ-RTP-MAP-004` says the Lite assembly ships `NoopMapBinding` only; this ADR pins down whether the Lite trim is enforced at the assembly level (jar filtering, like `effects-api/effects-api-fabric-unobf`) or at the source level.

These three are scoped to the module's bootstrap, do not affect the umbrella decisions in ADR-046, and do not impose a cross-module change. ADR-046 explicitly defers them here ("renderers must pick a fixed RTP palette mapping to stay deterministic across MC versions" appears in its Consequences but is not resolved).

## Decision

### 1. Package layout

`maps-api/src/main/java/io/github/dailystruggle/mapsapi/` is partitioned as follows. Each subpackage owns its `package-info.java` referencing this ADR, the relevant ADR-046 section, and the inherited safety-rule numbers per [`.junie/AGENTS.md`](../../../.junie/AGENTS.md) *Prohibition Requirements*.

| Subpackage | Layer | Allowed imports | Notes |
|------------|-------|-----------------|-------|
| `mapsapi` (root) | Cross-cutting SPI | `rtp-api`, JDK only | Hosts `MapCanvas`, `MapBinding`, `MapHandle`, `MapAllocationRequest`, `Cancellation`. No platform imports. |
| `mapsapi.model` | Layer 1 — Models | `rtp-api`, JDK only | Hosts the sealed `ChartModel` and its permitted records (`Heatmap2D`, `CategoryDistribution`, `TimeSeries`, `RegionCoverage`, `RegionBadLocations` *(amendment, 2026-05-23)*, `MermaidChart`). All records, all defensively copied at construction. |
| `mapsapi.render` | Layer 2 — Renderers | `rtp-api`, `mapsapi.model`, JDK only | Hosts `ChartRenderer<M extends ChartModel>` plus concrete renderers (`HeatmapRenderer` in Stage 1; `CategoryPieRenderer`, `SparklineRenderer`, `RegionCoverageRenderer`, and the Mermaid subpackage in Stage 4). No platform imports. Enforced by `ReqRtpMap002NoChunkIoTest` (ArchUnit). |
| `mapsapi.render.mermaid` | Layer 2 — Mermaid renderer | Same as `mapsapi.render` | Hosts `MermaidParser`, `MermaidLayout`, `MermaidRasterizer`, `MermaidRenderer`. Self-contained — no external runtime, no scripting engine, no third-party graph-layout dependency (REQ-RTP-MAP-005). |
| `mapsapi.noop` | Layer 3 — default binding | `rtp-api`, `mapsapi`, JDK only | Hosts `NoopMapBinding`. Every entry-point throws `IllegalStateException` with the documented message prefix (REQ-RTP-MAP-001). Ships in every assembly variant including Lite. |
| `mapsapi.bukkit` | Layer 3 — Bukkit-family binding | `rtp-api`, `mapsapi`, `org.bukkit.*` | Hosts `BukkitMapBinding`. The **only** subpackage of the module permitted to import `org.bukkit.*`. Compile dependency is `compileOnly` Spigot 1.20.1, mirroring `effects-api`. Lands in Stage 2. |
| `mapsapi.fabric` | Layer 3 — Fabric dispatcher | `rtp-api`, `mapsapi`, Loom-managed MC | Hosts `FabricMapBindingDispatcher` and the NM-typed surfaces consumed by the obf carrier in `rtp-fabric/rtp-fabric-common/.../maps/` and the unobf carrier in `maps-api-fabric-unobf/`. Lands in Stage 3. |

`maps-api/src/test/java/io/github/dailystruggle/mapsapi/` hosts `InMemoryMapBinding` (test-only `byte[128*128]` double; no `org.bukkit.*` imports) and the Stage 1 test classes (`ReqRtpMap001RequireByContractTest`, `ReqRtpMap002NoChunkIoTest`, `HeatmapRendererTest`, `MapsApiSurfaceTest`).

Folia does **not** receive its own subpackage inside `maps-api`. `FoliaMapBinding` lives under `rtp-folia/rtp-folia-common/.../maps/` because it depends on Folia-specific scheduler types (`Bukkit.getGlobalRegionScheduler()`, `EntityScheduler`) that the Spigot 1.20.1 `compileOnly` dependency does not expose. This mirrors ADR-046's *Layer 3* explicit Folia placement.

### 2. Palette policy

Vanilla cartography maps render through a fixed-size colour palette. Across MC 1.20.x and 1.21.x the lower 248 indices are stable, and 26.x adds a small number of new base colours. A renderer that hard-codes an MC 1.20.x palette will produce visibly wrong colours on 26.x; a renderer that switches palette by MC version will produce non-deterministic byte output that defeats the renderer-level palette tests required by Stage 4.3 of the checklist.

The decision in this ADR:

**The `MapCanvas` SPI is palette-agnostic at the byte layer.** `MapCanvas.setPixel(int x, int y, byte color)` and `MapCanvas.fillRect(...)` accept raw vanilla map-palette bytes. The translation from "renderer intent" (e.g. "the second-hottest bin of a heatmap") to a concrete palette byte lives in a **palette table** owned by `mapsapi.render` rather than baked into `MapCanvas`.

The **active palette table** is chosen by the binding, not the renderer:

- `NoopMapBinding` — table is irrelevant; every entry-point throws.
- `InMemoryMapBinding` (tests) — uses a deterministic test palette, `MapsApiTestPalette`, defined in `maps-api/src/test/java/.../MapsApiTestPalette.java`. Renderer-level palette tests assert against this table.
- `BukkitMapBinding` (Stage 2) — translates through `MapPalette.matchColor(...)` or the equivalent Paper API on the destination `MapView`. The binding owns the call so the renderer never imports `org.bukkit.*`.
- `FabricMapBinding` (Stage 3) — translates through the per-version NM-typed `MapColor` table dispatched by the obf/unobf carrier.

Renderer-side, the **Stage 1 RTP palette index** is a 32-symbol logical palette (`PaletteIndex.BACKGROUND`, `EDGE`, `TEXT`, `HEAT_0..HEAT_15`, `PIE_0..PIE_11`, `ARROW`, `GRID`, `MUTED`, `SELECTED`) defined in `mapsapi.render.Palette`. Each binding's palette table maps these 32 logical symbols to concrete vanilla palette bytes. The logical palette is the contract; the vanilla palette bytes are the carrier. This:

- Keeps the renderers MC-version-agnostic (they only know `PaletteIndex`).
- Keeps the byte output deterministic *per binding* (`InMemoryMapBinding` is the testbed; `BukkitMapBinding` byte output is allowed to drift across MC versions for the same logical symbol because the binding's table is version-aware).
- Defers the actual mapping table values to Stage 2 (`BukkitMapBinding` implementation), where the first real renderer lands on a real `MapView` and operator-visible colour choices can be reviewed in-world.

This ADR therefore picks a **logical-palette-now, concrete-table-Stage-2** posture, resolving the open question recorded in [`docs/dev/scratch/CHECKLIST-maps-api.md`](../../../docs/dev/scratch/CHECKLIST-maps-api.md) *Notes* (line 127). Expanding the logical palette beyond 32 symbols is an additive amendment to this ADR; shrinking it is breaking and requires a superseding ADR.

### 3. Lite-assembly inclusion / exclusion

Per REQ-RTP-MAP-004, the Lite assembly ships `NoopMapBinding` only. This is enforced **at the assembly level**, not at the source level:

- The `maps-api` Gradle module always compiles every subpackage listed in §1. There is no source-level Lite branch.
- The `rtp-plugin` Lite shadow-jar excludes `io/github/dailystruggle/mapsapi/bukkit/**`, `io/github/dailystruggle/mapsapi/fabric/**`, `io/github/dailystruggle/mapsapi/render/mermaid/**`, and `io/github/dailystruggle/mapsapi/render/**` *except* `Palette.class` and a documented minimal subset (revisited in Stage 2). `mapsapi`, `mapsapi.model`, and `mapsapi.noop` are always included so addons that compile against the SPI still resolve at runtime.
- The `RTPHooks` registry default (`NoopMapBinding`) is the only `MapBinding` reachable on Lite, satisfying REQ-RTP-MAP-001 (early-access calls throw `IllegalStateException` until — on Pro — `BukkitMapBinding`/`FabricMapBinding` installs).
- The existing `liteJarStructureCheck` Gradle audit task (see [ADR-024](../../../docs/adr/ADR-024-rtp-lite-assembly-variant.md)) gains a `maps-api` clause in Stage 2 that asserts the excluded packages are absent from the Lite jar and `NoopMapBinding` is present.

This mirrors the `effects-api` / `commands-api` pattern: one source tree, two assemblies, exclusions at the shadow-jar layer.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Embed the palette table in `MapCanvas` itself | Forces every renderer to know the active MC version, defeats `MapCanvas`'s role as a thin pixel-byte SPI, and pushes platform concerns into Layer 1. |
| Bake a fixed MC-1.20.1 palette into every renderer | Produces visibly wrong colours on 26.x and breaks the renderer-level determinism test (Stage 4.3) on any version other than 1.20.1. |
| Carry the logical palette as `int` RGB and dither inside renderers | Dithering against a 248-entry palette is expensive per refresh, and the vanilla palette is not perceptually uniform — dither output is worse than hand-picked indices. The hand-picked 32-symbol table is bounded scope. |
| Source-level Lite branch (separate Gradle module `maps-api-lite/`) | Doubles the number of modules to ship and to keep ABI-consistent; the `effects-api` / `commands-api` precedent already proves the assembly-level trim works. |
| Defer the palette decision entirely to Stage 2 | The Stage 1 `HeatmapRenderer` deliverable (checklist row 1.11) needs *some* logical palette to compile against. Deferring the *table* values is fine; deferring the *symbol set* would block Stage 1. |
| Place `FoliaMapBinding` inside `maps-api/.../mapsapi/folia/` for symmetry with `bukkit/` and `fabric/` | The Spigot 1.20.1 `compileOnly` artifact does not expose Folia scheduler types; pulling Folia into `maps-api` would force a Folia compile dependency on the module. The existing `effects-api` pattern keeps Folia overrides in `rtp-folia/rtp-folia-common/`. |

## Consequences

- **Positive:**
  - Stage 1 has every bootstrap decision it needs in writing before code lands. `MapCanvas`, `PaletteIndex`, `NoopMapBinding`, and `InMemoryMapBinding` can be authored in parallel by different contributors without coordination on palette bytes.
  - The renderer-level palette tests (Stage 4.3) are well-defined: they assert against the deterministic test palette in `MapsApiTestPalette`, not against per-MC-version vanilla bytes.
  - Lite enforcement is mechanical (shadow-jar exclusions audited by `liteJarStructureCheck`), not a contributor discipline burden.
  - The Bukkit-family subpackage is the only place `org.bukkit.*` may appear inside the module, mirroring the `effects-api` precedent and keeping the ArchUnit rule simple.

- **Negative / Trade-offs:**
  - The logical palette commits the project to a 32-symbol vocabulary. Renderers that need a 33rd symbol require an additive amendment to this ADR and a coordinated update to every binding's palette table. The 32-symbol cap was sized for the Stage 1 / Stage 4 renderer catalogue plus a small headroom; emerging chart types may push back on it.
  - Per-binding palette tables mean operator-visible chart colours can differ between Bukkit and Fabric for the same logical symbol. This is acceptable for the v1 scope (each operator deploys one platform) but will require coordination if a future addon ships charts that span both.
  - Lite-jar exclusion patterns must be maintained in lockstep with the package layout. A renamed subpackage that misses the exclusion list ships Pro-only code in Lite. Mitigated by the `liteJarStructureCheck` audit in Stage 2.

## Amendments

### 2026-05-23 — `RegionBadLocations` permit + concrete logical-palette layout

This amendment realises the `PaletteIndex` symbol contract that §Palette policy promised as "the Stage 1 RTP palette index" and adds one new `ChartModel` permit. It is additive: it does not retract or supersede any decision in this ADR, and the slot count remains 32 (logical bytes `0..31`).

**1. `mapsapi.model` (§1):** the sealed `ChartModel` permits clause gains a sixth record, `RegionBadLocations(String regionName, int centerX, int centerZ, int radius, long[] badKeys)`. It carries a snapshot of a region's bad-location set (the same packed-long `chunkKey` encoding used by `MemoryShape.pendingBadLocations`) and is consumed by `RegionBadLocationsRenderer` to paint the admin `Visualizations` -> `Region shape` map: red for bad cells, green for the rest of the inscribed disk, black outside. The biome-overlay variant remains future work (deferred per issue thread, 2026-05-23).

**2. `mapsapi.PaletteIndex` (§2):** the planned 32-symbol logical palette was originally drafted as `BACKGROUND / EDGE / TEXT / HEAT_0..HEAT_15 / PIE_0..PIE_11 / ARROW / GRID / MUTED / SELECTED`. That exact vocabulary was never authored into code; the Stage 1 `HeatmapRenderer` shipped instead with integer constants `RAMP_MIN=0 / RAMP_MAX=31`. The amendment chooses a layout aligned with what is actually used, sized for the renderers shipped (`HeatmapRenderer`) and about to ship (`RegionBadLocationsRenderer`):

| Index | Symbol | Role |
|------:|--------|------|
| `0` | `TRANSPARENT` | Unfilled / cleared canvas pixel. Maps to `MapPalette.TRANSPARENT` on Bukkit. |
| `1..27` | Heat ramp (`RAMP_MIN..RAMP_MAX`) | Walk a black -> red -> yellow -> white gradient. Consumed by `HeatmapRenderer`. |
| `28` | `BLACK` | Categorical: outside-region / unexplored backdrop. |
| `29` | `RED` | Categorical: bad / failure / hazard. |
| `30` | `GREEN` | Categorical: good / success / safe interior. |
| `31` | `WHITE` | Categorical: high-contrast emphasis, chart frames. |

Rationale for the deviation from the original draft vocabulary:

- The original draft pre-allocated 16 heat tones and 12 pie tones; in practice the heat ramp wants more granularity (27 steps) and pie / categorical renderers want fewer, distinctly-named non-ramp colors. The shipped layout reflects what `BukkitMapBinding.buildPalette()` actually produces and what `MapPalette.matchColor` actually resolves cleanly on vanilla maps.
- The named categorical slots (`BLACK`, `RED`, `GREEN`, `WHITE`) are exposed as `public static final byte` constants on `mapsapi.PaletteIndex` so renderers reference them by name rather than by integer. The four-slot count is sized for the bad-locations renderer (3 slots used: BLACK/RED/GREEN) plus one headroom slot (`WHITE`) and is intentionally small; adding a fifth (e.g. `YELLOW`, `BLUE`) is itself an additive amendment.
- The slot count remains 32. The `PALETTE` table in every binding still has exactly 32 entries.

`HeatmapRenderer.RAMP_MIN` / `RAMP_MAX` are retained as renderer-local constants but now delegate to `PaletteIndex.RAMP_MIN` / `PaletteIndex.RAMP_MAX`, preserving the existing `HeatmapRendererTest` call sites and decoupling the renderer from the numeric values.

**3. Future Fabric / Noop / InMemory bindings (§2):** every binding's palette table shall mirror the layout above. Stage 3 `FabricMapBinding` lands the same shape under the obf/unobf carriers; the test-only `InMemoryMapBinding` already writes raw logical bytes (no per-binding remap) and is unaffected.

**4. ADR-046 cross-reference:** the umbrella ADR-046 §Palette policy "renderers must pick a fixed RTP palette mapping to stay deterministic across MC versions" open question is now resolved by the concrete table above. No amendment to ADR-046 itself is required.

## References

- [ADR-046](../../../docs/adr/ADR-046-maps-api-module.md) — umbrella ADR.
- [`docs/dev/REQUIREMENTS.md`](../../../docs/dev/REQUIREMENTS.md) §1.7 — `REQ-RTP-MAP-001..005`.
- [`docs/dev/TRACEABILITY.md`](../../../docs/dev/TRACEABILITY.md) — REQ → class → test rows for the maps surface.
- [`docs/dev/scratch/CHECKLIST-maps-api.md`](../../../docs/dev/scratch/CHECKLIST-maps-api.md) — multi-session implementation plan.
- [ADR-024](../../../docs/adr/ADR-024-rtp-lite-assembly-variant.md) — Lite-assembly trim posture.
- [ADR-026](../../../docs/adr/ADR-026-external-hook-api-surface.md) — `RTPHooks` registry pattern.
- [effects-api-ADR-003](../../../effects-api/docs/adr/effects-api-ADR-003-platform-split-bukkit-fabric.md) — in-module platform-split precedent.
- [rtp-fabric-ADR-009](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) — Fabric obf/unobf carrier dispatch precedent.
- [effects-api-ADR-006](../../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md) — Fabric obf/unobf split applied to `effects-api`.
- [commands-api-ADR-001](../../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge for `/rtp map` (Stage 2).
