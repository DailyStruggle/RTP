# Checklist — `maps-api` runtime cartography chart generation

**Effective Issue:** Design and ship a map creation interface and implementations for runtime chart generation onto Minecraft cartography maps (2D imaging via `MapView` on Bukkit-family, `MapItemSavedData` / `ClientboundMapItemDataPacket` on Fabric).

**Mode:** `[CODE]` — multi-session, D-005 (approved 2026-05-15).

**Governing ADRs:** [ADR-046](../../adr/ADR-046-maps-api-module.md) (Accepted, this session). Stage 1 will also author `maps-api/docs/adr/maps-api-ADR-001-bootstrap.md` for the in-module bootstrap.

**Approved scope answers (locked 2026-05-15):**
- A. New top-level module `maps-api/` (sibling to `effects-api`, `commands-api`). Not folded into `rtp-core`, `rtp-api`, `effects-api`, or `commands-api`.
- B. Fabric obf/unobf carrier split mirrors `effects-api` per [effects-api-ADR-006](../../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md) and [rtp-fabric-ADR-009](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md).
- C. Five REQs (`REQ-RTP-MAP-001..005`) authored in `REQUIREMENTS.md` during Stage 1 — `005` covers the Mermaid subset/no-external-runtime contract added by the 2026-05-16 ADR-046 amendment.
- D. `/rtp map` command goes through the existing `commands-api` Brigadier bridge — no per-platform command class.
- E. Lite assembly ships `NoopMapBinding` only (REQ-RTP-MAP-004).
- F. `RTPHooks` registry slot for `MapBinding` added in Stage 2 alongside the `EXTERNAL_HOOKS.md` catalog row.
- G. ADR-046 authored up-front (this session), document-only — no code touched.

**Out of scope (will not be added without a fresh D-005 amendment):**
- Animated / video-style charts (per-tick recomputation beyond the bounded refresh cadence).
- ~~Item-frame mosaics spanning more than one `MapView` (multi-map composition is a Phase-5 follow-up at earliest).~~ **Now in scope at Stage 4.4.c/d** (2026-05-16 ADR-046 amendment) — required for Mermaid diagrams that exceed one 128×128 tile. Auto-frame placement and sign labelling remain Stage 5 polish.
- Persisting chart state across server restarts (charts are recomputed from live state on `bindLive`).
- A public `rtp-api` re-export of `MapCanvas` (re-evaluate after Phase 3).

---

## Stage 0 — Documentation (THIS SESSION)

Goal: lock the design so subsequent sessions can be reviewed against a frozen contract. No code, no `settings.gradle` edits, no module skeleton.

- [x] 0.1 `docs/adr/ADR-046-maps-api-module.md` — Accepted, 2026-05-15. Context, Decision (3 layers + 5 bindings + `/rtp map` command + 4 REQs), Alternatives (8 rows), Consequences, References.
- [x] 0.2 `docs/dev/scratch/CHECKLIST-maps-api.md` — this file.

---

## Stage 1 — `maps-api` SPI skeleton + Noop + tests (NEXT SESSION)

No platform code touched. No `rtp-core` or `rtp-api` edits beyond REQ/TRACEABILITY rows.

- [ ] 1.1 `settings.gradle` — `include 'maps-api'`. Same Java toolchain as `effects-api` (Java 21 source, Java 21 target).
- [ ] 1.2 `maps-api/build.gradle` — `java-library`, no Bukkit/Fabric runtime deps, JUnit Jupiter test deps only. Mirrors `effects-api/build.gradle` shape.
- [ ] 1.3 `maps-api/src/main/java/io/github/dailystruggle/mapsapi/MapCanvas.java` — interface: `width()`, `height()`, `setPixel`, `fillRect`, `drawText`, `clear`, `commit`. Vanilla 128×128, byte map-colour palette.
- [ ] 1.4 `MapHandle` record `(String chartId, UUID viewer /* nullable for world maps */, int mapId)`.
- [ ] 1.5 `MapAllocationRequest` record + nested `Locking { LOCKED, EDITABLE }` enum.
- [ ] 1.6 `Cancellation` interface — `void cancel()`, `boolean cancelled()`.
- [ ] 1.7 `MapBinding` interface — `allocate`, `renderEphemeral`, `bindLive`. Javadoc: S-006 contract, MemoryTracker lifecycle contract, Folia commit-thread contract.
- [ ] 1.8 `ChartRenderer<M extends ChartModel>` interface — `render(MapCanvas, M)`. Javadoc: REQ-RTP-MAP-002 (no chunk I/O, no `.get()` / `.join()`).
- [ ] 1.9 Models: `model/ChartModel.java` (sealed), `Heatmap2D`, `CategoryDistribution`, `TimeSeries`, `RegionCoverage`, `MermaidChart(String source, String title)`. All records, all defensively copied.
- [ ] 1.10 `noop/NoopMapBinding.java` — every entry-point throws `IllegalStateException("RTP core not loaded — register a MapBinding via RTPHooks before use.")`.
- [ ] 1.11 `render/HeatmapRenderer.java` — first concrete renderer (proves the SPI shape).
- [ ] 1.12 `package-info.java` for `mapsapi`, `mapsapi.model`, `mapsapi.render`, `mapsapi.noop` cross-referencing ADR-046, REQ-RTP-MAP-001..005, and the inherited S-004 / S-005 / S-006 / S-007 / F-013 requirements.
- [ ] 1.13 `src/test/java/.../InMemoryMapBinding.java` — test double; `byte[128*128]` pixel buffer + `byte[][] snapshot()`; no `org.bukkit.*` imports.
- [ ] 1.14 `ReqRtpMap001RequireByContractTest` — every `NoopMapBinding` method throws `IllegalStateException` with the documented message prefix.
- [ ] 1.15 `ReqRtpMap002NoChunkIoTest` — ArchUnit rule: no class under `mapsapi.render` references `org.bukkit`, `net.minecraft`, `world.getChunkAt`, `CompletableFuture#get`, or `CompletableFuture#join`.
- [ ] 1.16 `HeatmapRendererTest` — drives `HeatmapRenderer` against `InMemoryMapBinding`, asserts on the resulting pixel buffer (palette mapping, bounds, clear behavior).
- [ ] 1.17 `MapsApiSurfaceTest` — sealed-shape reflection over `ChartModel`, defensive-copy assertions on each record, null-arg rejection on each public ctor.
- [ ] 1.18 `docs/dev/REQUIREMENTS.md` — append `REQ-RTP-MAP-001..005` rows in the wording from ADR-046's *Decision* table.
- [ ] 1.19 `docs/dev/TRACEABILITY.md` — five new rows (one per REQ) pointing at the Stage 1 test classes (`REQ-RTP-MAP-005` points at the Stage 4 `MermaidRendererTest` once authored — leave the test column noting "Stage 4" in Stage 1).
- [ ] 1.20 `maps-api/docs/adr/maps-api-ADR-001-bootstrap.md` — in-module ADR documenting package layout, palette policy, and the Lite-assembly exclusion. Subproject ADR row added to `docs/adr/README.md` *Subproject ADRs* table.
- [ ] 1.21 `CHANGELOG.md` — bullet under the current `[3.0.0-beta.N] - Unreleased ### Added` describing the `maps-api` SPI surface and the deferred binding / `/rtp map` follow-ups.
- [ ] 1.22 `.\gradlew :maps-api:build` green; `.\gradlew build` green.

---

## Stage 2 — Bukkit binding + `/rtp map` Brigadier command (LATER SESSION)

Wires the SPI to the Bukkit-family platforms. No Fabric code yet.

- [ ] 2.1 `BukkitMapBinding` in `maps-api/src/main/java/.../mapsapi/bukkit/`. Only file in the module permitted to `import org.bukkit.*`. `allocate` reuses or creates a `MapView`; `renderEphemeral` dispatches via a one-shot `MapRenderer`; `bindLive` registers a `MapRenderer` whose `render(...)` reads from the supplied `modelSupplier` at the configured cadence.
- [ ] 2.2 `BukkitMapBinding` `MemoryTracker` lifecycle: register on `bindLive`, release on `Cancellation.cancel()`, on `PlayerQuitEvent` for any viewer, on `RTP.disable()`. Covers REQ-RTP-MAP-003.
- [ ] 2.3 `FoliaMapBinding` thin override in `rtp-folia/rtp-folia-common/.../maps/`. Refresh dispatch via `Bukkit.getGlobalRegionScheduler()`; per-viewer pixel commits via `EntityScheduler`. Scheduler ticks use `RTP.scheduler.runTaskTimerAsynchronously`. No blocking `.get()`.
- [ ] 2.4 `/rtp map create / list / cancel` Brigadier nodes through `commands-api` Brigadier bridge. Subcommands are `BaseRTPCmdImpl` subclasses, registered the same way as the menu redeem subcommand.
- [ ] 2.5 `messages.yml` keys: `mapCreated`, `mapCancelled`, `mapBusy`, `mapInvalidArg`, `mapUnknownId`. Configurable per REQ-RTP-F-013 and S-007. New `MessagesKeys` enum entries.
- [ ] 2.6 `RTPHooks` registers a `MapBinding` slot; default is `NoopMapBinding`; `rtp-plugin` (Bukkit-family entry point) installs `BukkitMapBinding` (or `FoliaMapBinding` on Folia) at `onEnable`.
- [ ] 2.7 `docs/dev/EXTERNAL_HOOKS.md` catalog row for the `MapBinding` hook ([ADR-026](../../adr/ADR-026-external-hook-api-surface.md)).
- [ ] 2.8 First real chart wired in: `RegionCoverageRenderer` + `RegionCoverage` model, driven from the live spiral state in `rtp-core` ([ADR-001](../../adr/ADR-001-archimedean-spiral-1d-mapping.md)). The chart-data adapter lives in `rtp-core` (no platform import); the binding consumes a `Supplier<ChartModel>`.
- [ ] 2.9 Tests: `BukkitMapBindingTest` (lifecycle: register / release / disconnect / disable), `FoliaMapBindingThreadingTest` (asserts commit hops through Global Region Scheduler), `RtpMapCommandTest` (happy / busy / unknown-id paths drive the configurable messages).
- [ ] 2.10 `docs/dev/TRACEABILITY.md` rows updated for REQ-RTP-MAP-001..003 with the new platform bindings + tests.
- [ ] 2.11 `CHANGELOG.md` bullet — Bukkit/Paper/Folia bindings + `/rtp map` command.
- [ ] 2.12 `.\gradlew build` green.

---

## Stage 3 — Fabric obf/unobf carriers (GATED ON FABRIC STABILITY)

**Gating note:** Fabric is *unstable* per *AGENTS.md > Current Development Focus* as of 2026-04-30. Do not start Stage 3 until the existing blockers there are resolved (S-005 in `FabricWorld.getChunkAt`, `FabricServerAccessor.getLocationGenerator` null stub, unresolved Loom dependency). Stage 4 may run before Stage 3 without issue.

- [ ] 3.1 `maps-api-fabric-unobf/` Mojmap-unobf carrier module; Loom-built, no `mappings` line, Java toolchain matching `effects-api/effects-api-fabric-unobf/`.
- [ ] 3.2 Per-version NM-typed obf carriers under `rtp-fabric/rtp-fabric-common/.../maps/` for each supported runtime (1.20.x, 1.21.x). MC 26.x runs from the unobf carrier per [rtp-fabric-ADR-009](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md).
- [ ] 3.3 `FabricMapBindingDispatcher` in `maps-api/.../mapsapi/fabric/` — selects obf or unobf carrier based on runtime, mirroring `FabricVersionAdapter#installEffectsWiring`.
- [ ] 3.4 `RTPHooks` installs `FabricMapBinding` on the Fabric entry point.
- [ ] 3.5 Tests: `FabricMapCarriersDisjointTest` (mirror of `EffectsApiFabricCarriersDisjointTest`), `FabricMapBindingTest`.
- [ ] 3.6 `maps-api/docs/adr/maps-api-ADR-002-fabric-obf-unobf-split.md` referencing [rtp-fabric-ADR-009](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) and [effects-api-ADR-006](../../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md). Add row to `docs/adr/README.md` *Subproject ADRs* table.
- [ ] 3.7 `CHANGELOG.md` bullet — Fabric carrier ship.
- [ ] 3.8 `.\gradlew build` green across every Fabric runtime variant.

---

## Stage 4 — Additional renderers + metrics integration + Mermaid (PARALLEL WITH STAGE 3)

- [ ] 4.1 `CategoryPieRenderer` + `CategoryDistribution` consumer for L1/L2/L3 cache occupancy and `FailTypes` breakdown.
- [ ] 4.2 `SparklineRenderer` + `TimeSeries` consumer for TPS / MSPT / pipeline-latency sources defined in [`METRICS_PLAN.md`](../METRICS_PLAN.md). Ring-buffer sampler in `rtp-core` (no platform import).
- [ ] 4.3 Renderer-level palette tests asserting deterministic byte output across MC versions.
- [ ] 4.4 `MermaidRenderer` (REQ-RTP-MAP-005) in `maps-api/.../mapsapi/render/mermaid/`. Sub-checklist:
  - [ ] 4.4.a `MermaidParser` — hand-written recursive-descent parser for the subset locked in ADR-046 §*Mermaid output*: `flowchart LR`/`TD`, `id[label]` / `id(label)` / `id{label}` nodes, `-->` / `--> |label|` / `-.->` / `==>` edges, `%% …` comments. Out-of-subset constructs throw `MermaidParseException` carrying line/column for the configurable `messages.yml → mapMermaidParseError` message.
  - [ ] 4.4.b `MermaidLayout` — longest-path layering (`TD`) and left-to-right Sugiyama-lite (`LR`); deterministic, no external graph-layout library. Produces `LaidOutGraph(List<NodeBox>, List<EdgeRoute>)` in unit-pixel space.
  - [ ] 4.4.c `MermaidRasterizer` — scan-converts `NodeBox` rectangles/rounded-rects/diamonds and `EdgeRoute` polylines (with arrowheads + optional inline labels) to the active `MapCanvas` palette. Tile-grid output: when `MapAllocationRequest.tiles(rows, cols)` is set, the rasterizer slices the laid-out graph across the grid; otherwise it scales-to-fit one 128×128 tile and falls back to the `mapMermaidTruncated` message + log if any node would be < 6 px wide post-scale (S-004).
  - [ ] 4.4.d `MapAllocationRequest` extended with `tiles(int rows, int cols)` (default `1,1`). `MapBinding.allocate` returns a `List<MapHandle>` in row-major order; existing single-tile callers receive a one-element list.
  - [ ] 4.4.e `/rtp map create` Brigadier node accepts `--source <file>` (loads Mermaid text from `<dataFolder>/maps/<file>.mmd`) and `--tiles <rows>x<cols>`. New `messages.yml` keys: `mapMermaidParseError`, `mapMermaidTruncated`, `mapMermaidFileMissing`. Added to `MessagesKeys`.
  - [ ] 4.4.f Tests: `MermaidParserTest` (subset accept matrix + 6 out-of-subset reject cases with line/column assertions), `MermaidLayoutTest` (determinism + topological correctness for `LR` and `TD`), `MermaidRasterizerTest` (drives `InMemoryMapBinding`; palette + arrowhead + rounded-corner pixel assertions; tile-grid slicing covers a 3×2 layout), `RtpMapMermaidCommandTest` (happy / parse-error / missing-file / tile-grid paths drive the configurable messages).
- [ ] 4.5 `docs/dev/TRACEABILITY.md` — rows for the new renderers (REQ-RTP-MAP-002 coverage extended; REQ-RTP-MAP-005 row points at `MermaidParserTest` + `MermaidRasterizerTest`).
- [ ] 4.6 `CHANGELOG.md` bullet — additional renderers + Mermaid.
- [ ] 4.7 `.\gradlew build` green.

---

## Stage 5 — Deferred follow-ups (post-beta cycle)

- [ ] 5.1 ~~Multi-`MapView` mosaic composition (item-frame walls).~~ **Promoted to Stage 4.4.c/d (2026-05-16 ADR-046 amendment)** — required for Mermaid diagrams that exceed one 128×128 tile. Any post-Stage-4 polish on the mosaic (auto-frame placement, sign labelling) lands here as a follow-up.
- [ ] 5.2 Public `rtp-api` re-export of `MapCanvas` if addons request it.
- [ ] 5.3 Renderer-id config list (parallel to `menu.renderer: [book, chat]` in [`CHECKLIST-generalized-menu.md`](CHECKLIST-generalized-menu.md) Stage 5.3) for operator preference.
- [ ] 5.4 Delete this scratch checklist after Stage 4 ships and Stage 5 items are either tracked elsewhere or explicitly cancelled.

---

## Notes / open questions

- Palette policy not yet locked. Stage 1 (`maps-api-ADR-001-bootstrap.md`) will pick a fixed RTP byte-palette mapping for the renderers; vanilla 256-colour palette deltas across MC versions are the main concern.
- Folia map-item ownership: a `MapView` has no region, so the binding takes an explicit dependency on the Global Region Scheduler. This is exercised by `FoliaMapBindingThreadingTest` (Stage 2.9), and documented in the `BukkitMapBinding` javadoc.
- `RegionCoverage` model wiring (Stage 2.8) is the only place where `rtp-core` reaches into `maps-api`. The reverse (`maps-api` → `rtp-core`) shall not occur — Stage 1 ArchUnit rule (1.15) enforces it.
- Mermaid output (Stage 4.4) stays inside `maps-api`: parser, layout, and rasterizer are all platform-neutral and consume only `MapCanvas`. The Mermaid source itself is plain text loaded by the Bukkit-family `/rtp map create --source` path under `<dataFolder>/maps/*.mmd`; on Fabric the same loader runs via the standard `FabricServerAccessor` data-folder hook (no NM-typed surface required for Mermaid).
- Phase 3 may slip indefinitely behind Phases 1, 2, and 4 if Fabric instability persists. That is acceptable: Bukkit-family ship is independent.
