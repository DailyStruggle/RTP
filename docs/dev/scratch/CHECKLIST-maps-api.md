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

- [x] 1.1 `settings.gradle` — `include 'maps-api'` landed 2026-05-16 with a four-line comment block immediately above (pure-Java SPI, no Bukkit/Fabric/Loom dep, concrete bindings deferred to Stage 2/3). Inherits the Java 21 toolchain from the root `subprojects {}` block. Verification: `.\gradlew :maps-api:build` resolves the project.
- [x] 1.2 `maps-api/build.gradle` authored 2026-05-16: `id 'java-library'`, JUnit Jupiter + Mockito inherited from root `subprojects {}`, ArchUnit added locally (`com.tngtech.archunit:archunit-junit5:1.3.0`, matches rtp-core / rtp-anvil pin). `withJavadocJar()` + `withSourcesJar()` + `Xdoclint:syntax,html` mirroring `rtp-api`. No Bukkit / Fabric / Loom plugin. Verification: `.\gradlew :maps-api:build` BUILD SUCCESSFUL in 25s.
- [x] 1.3 `MapCanvas.java` authored: 7-method interface (`width`, `height`, `setPixel`, `fillRect`, `drawText`, `clear`, `commit`) + `VANILLA_WIDTH` / `VANILLA_HEIGHT = 128` constants. Logical-palette-now contract documented in the type javadoc per maps-api-ADR-001. Verification: compiled clean inside `:maps-api:compileJava`.
- [x] 1.4 `MapHandle.java` authored: record `(String chartId, UUID viewer, int mapId)`, compact constructor rejects null / blank `chartId`, `viewer` nullable. Verification: `MapsApiSurfaceTest.mapHandleNullArg` green.
- [x] 1.5 `MapAllocationRequest.java` authored: record `(String chartId, UUID viewer, Locking locking)` with nested `Locking { LOCKED, EDITABLE }` enum. Compact ctor rejects null `chartId` / null `locking`. Verification: `MapsApiSurfaceTest.allocationRequestNullArgs` green.
- [x] 1.6 `Cancellation.java` authored: two-method interface (`cancel`, `cancelled`), idempotency contract documented in javadoc (REQ-RTP-MAP-003). Verification: implemented and exercised by `InMemoryMapBinding.LiveSubscription`.
- [x] 1.7 `MapBinding.java` authored: 3-method interface (`allocate`, `renderEphemeral`, `bindLive`) with full contracts javadoc covering REQ-RTP-MAP-001 (extends S-006), REQ-RTP-MAP-002 (extends F-008 / S-005), REQ-RTP-MAP-003 (MemoryTracker lifecycle), and Folia commit-thread rule (Global Region Scheduler for `MapView` commits, Entity Scheduler for per-viewer pixels). Verification: implemented by `NoopMapBinding` + `InMemoryMapBinding`, exercised by all 4 Stage-1 tests.
- [x] 1.8 `ChartRenderer.java` authored: `@FunctionalInterface` parameterised over `M extends ChartModel` with single `render(MapCanvas, M)` method. Javadoc enumerates the REQ-RTP-MAP-002 prohibitions (no chunk I/O, no blocking futures, no `org.bukkit.*` / `net.minecraft.*`) and cross-references `ReqRtpMap002NoChunkIoTest`. Verification: implemented by `HeatmapRenderer`; both ArchUnit rules in `ReqRtpMap002NoChunkIoTest` green.
- [x] 1.9 Model package authored: sealed `ChartModel` permits exactly the 5 Stage-1 records (asserted by reflection in `MapsApiSurfaceTest.chartModelIsSealedWithExpected5Permits`). `Heatmap2D(int width, int height, double[] values, double minValue, double maxValue)`, `CategoryDistribution(List<String> labels, List<Long> counts)`, `TimeSeries(String label, double[] samples, double yMin, double yMax)`, `RegionCoverage(String regionName, int centerX, int centerZ, int radius, byte[] states)`, `MermaidChart(String source, String title)`. Every array/collection field is defensively copied in the compact ctor and in the accessor override; `MapsApiSurfaceTest` proves source-mutation isolation and accessor-aliasing isolation for all four array-bearing records.
- [x] 1.10 `NoopMapBinding.java` authored as a `final class` with public `NOT_LOADED_MESSAGE_PREFIX` constant (`"RTP core not loaded — register a MapBinding via RTPHooks before use."`). All three entry-points throw `IllegalStateException(NOT_LOADED_MESSAGE_PREFIX + " (allocate|renderEphemeral|bindLive)")`. Verification: `ReqRtpMap001RequireByContractTest` (4 tests, all green) asserts prefix match and entry-point self-naming.
- [x] 1.11 `HeatmapRenderer.java` authored: nearest-neighbour mapping of `Heatmap2D` samples to the 32-symbol logical palette (`RAMP_MIN=0`, `RAMP_MAX=31`). Clears the canvas before drawing; clamps out-of-range samples to ramp endpoints; null-rejects both arguments. Pure pixel maths — no chunk I/O, no futures, no `org.bukkit.*`, no `net.minecraft.*` (enforced by `ReqRtpMap002NoChunkIoTest` ArchUnit rules). Verification: `HeatmapRendererTest` (7 cases) green: uniform-zero, uniform-max, clamp-up, clamp-down, 128x128 invariance, top-vs-bottom gradient, clear-before-draw.
- [x] 1.12 Four `package-info.java` files authored under `mapsapi/`, `mapsapi/model/`, `mapsapi/render/`, `mapsapi/noop/`. Root cross-references ADR-046, maps-api-ADR-001, REQUIREMENTS.md §1.7 (MAP-001..005), and inherited S-004 / S-005 / S-006 / S-007 / F-013. Render package re-states the REQ-RTP-MAP-002 prohibition list and points at `ReqRtpMap002NoChunkIoTest`. Noop package documents the REQ-RTP-MAP-001 / S-006 require-by-contract behaviour and the REQ-RTP-MAP-004 Lite-only shipping policy. Model package documents the sealed-permits + defensive-copy contract.
- [x] 1.13 `InMemoryMapBinding.java` authored under `src/test/java/.../mapsapi/testfixtures/`: per-handle `byte[128*128]` pixel buffer in a private `InMemoryCanvas`, `byte[][] snapshot(MapHandle)` accessor, `commitCount(MapHandle)` for commit-frequency assertions, manual `tick()` driver for `bindLive` subscriptions, idempotent `LiveSubscription.cancel()`. No `org.bukkit.*` / `net.minecraft.*` imports (enforced module-wide by the `ReqRtpMap002NoChunkIoTest.mapsApiStage1HasNoPlatformImports` ArchUnit rule).
- [x] 1.14 `ReqRtpMap001RequireByContractTest` — 4 test cases, all green. Asserts `IllegalStateException` from `allocate`, `renderEphemeral`, `bindLive`, that every message starts with `NoopMapBinding.NOT_LOADED_MESSAGE_PREFIX`, and that each entry-point names itself in the thrown message tail.
- [x] 1.15 `ReqRtpMap002NoChunkIoTest` — 3 cases, all green. (a) ArchUnit `noClasses().that().resideInAPackage(mapsapi.render..).should().dependOnClassesThat().resideInAnyPackage(org.bukkit..|io.papermc..|net.minecraft..|net.fabricmc..)` rule; (b) manual `JavaClass.getMethodCallsFromSelf()` scan rejecting any call to `CompletableFuture#get` / `#join` / `Future#get` / `Future#join` from `mapsapi.render..` (the `ArchRule.callMethodWhere(...)` shape isn't stable in `archunit 1.3.0` for `noMethods()`); (c) module-wide rule forbidding `org.bukkit..` / `net.minecraft..` until Stage 2 / 3 land the `mapsapi.bukkit` / `mapsapi.fabric` subpackages.
- [x] 1.16 `HeatmapRendererTest` — 7 cases, all green: uniform-zero → all `RAMP_MIN`, uniform-max → all `RAMP_MAX`, clamp-up at 999 sample → `RAMP_MAX`, clamp-down at -999 sample → `RAMP_MIN`, 128×128 canvas invariance regardless of model size, top-vs-bottom gradient with explicit `pixels[0][64] < pixels[127][64]`, and clear-before-draw (frame-1 all-max overwritten by frame-2 all-zero).
- [x] 1.17 `MapsApiSurfaceTest` — 11 cases, all green: sealed-permits set equality vs. the documented 5 records, every permitted subtype `isRecord()`, every permitted subtype is `final`, defensive-copy assertions on `Heatmap2D` / `RegionCoverage` / `TimeSeries`, `CategoryDistribution` immutable-view via `List.copyOf` (throws `UnsupportedOperationException` on add), null-arg rejection on `MapHandle` / `MapAllocationRequest` / `Heatmap2D` / `MermaidChart`, plus shape-mismatch and inverted-`[min,max]` rejection on `Heatmap2D`.
- [x] 1.18 `docs/dev/REQUIREMENTS.md` — appended `REQ-RTP-MAP-001..005` as a new §1.7 *Maps / Runtime Cartography* block (2026-05-16). Wording matches ADR-046's *Decision* table; cross-references REQ-RTP-S-006 (001), REQ-RTP-F-008 / REQ-RTP-S-005 (002), REQ-RTP-F-013 + REQ-RTP-S-004 (005). Verification: `docs/dev/REQUIREMENTS.md` §1.7 contains five `- **REQ-RTP-MAP-00N — …:**` bullets.
- [x] 1.19 `docs/dev/TRACEABILITY.md` — five new rows appended to the *Root / System Requirements* table after `REQ-RTP-S-006` (2026-05-16). Stage gating annotated in each cell: MAP-001 → `NoopMapBinding` + `ReqRtpMap001RequireByContractTest` (Stage 1); MAP-002 → `ChartRenderer` / `HeatmapRenderer` + `ReqRtpMap002NoChunkIoTest` ArchUnit (Stage 1); MAP-003 → `BukkitMapBinding` / `FoliaMapBinding` + `BukkitMapBindingTest` (Stage 2); MAP-004 → Lite-assembly audit (Stage 2); MAP-005 → `MermaidParserTest` + `MermaidRasterizerTest` (Stage 4).
- [x] 1.20 `maps-api/docs/adr/maps-api-ADR-001-bootstrap.md` — authored 2026-05-16 (Accepted). Locks: package layout (`mapsapi` root + `model` / `render` / `render.mermaid` / `noop` / `bukkit` / `fabric`, only `bukkit` permitted `org.bukkit.*`); palette policy = **logical-palette-now, concrete-table-Stage-2** (32-symbol `PaletteIndex` contract carried per-binding; closes the open question in *Notes* line 127); Lite-assembly trim posture = **assembly-level shadow-jar exclusion** in `rtp-plugin`, audited by an extended `liteJarStructureCheck` clause in Stage 2. Subproject ADR row added to `docs/adr/README.md` *Subproject ADRs* table; ADR-046 also added to the main *Index* table in the same edit.
- [x] 1.21 `CHANGELOG.md` — bullet appended under `[3.0.0-beta.3] - Unreleased ### Added` (2026-05-16). Scoped to the **documentation surface only** (REQs + TRACEABILITY + in-module ADR + Subproject ADR table row) since no Gradle module / SPI source has landed yet; the bullet explicitly defers SPI skeleton + Noop binding + `InMemoryMapBinding` + ArchUnit guards to the subsequent commit per Stage 1 rows 1.1–1.17.
- [x] 1.22 `.\gradlew :maps-api:build` BUILD SUCCESSFUL in 25s; `.\gradlew :maps-api:test --rerun-tasks` BUILD SUCCESSFUL in 23s with **25/25 tests passing** (4 from `ReqRtpMap001RequireByContractTest`, 3 from `ReqRtpMap002NoChunkIoTest`, 7 from `HeatmapRendererTest`, 11 from `MapsApiSurfaceTest`). `.\gradlew build` BUILD SUCCESSFUL in 47s (full multi-module, every Fabric obf/unobf carrier included).

---

## Stage 2 — Bukkit binding + `/rtp map` Brigadier command (LATER SESSION)

Wires the SPI to the Bukkit-family platforms. No Fabric code yet.

- [x] 2.1 `BukkitMapBinding` in `maps-api/src/main/java/.../mapsapi/bukkit/` (2026-05-21). Only file in the module that imports `org.bukkit.*` (ArchUnit carve-out added in `ReqRtpMap002NoChunkIoTest#mapsApiCoreHasNoPlatformImports`). `allocate` creates a `MapView` via `Bukkit.createMap` (dedup by `chartId`); `renderEphemeral` installs a self-removing one-shot `MapRenderer` that translates the 32-symbol logical palette to vanilla bytes via a black-to-red-to-yellow-to-white ramp through `MapPalette.matchColor`. `bindLive` is DEFERRED to Stage 3 of `CHECKLIST-metrics-to-maps.md` and throws `UnsupportedOperationException` until then.
- [x] 2.2 `BukkitMapBinding` `MemoryTracker` lifecycle: register on `bindLive`, release on `Cancellation.cancel()`, on `PlayerQuitEvent` for any viewer, on `RTP.disable()`. Covers REQ-RTP-MAP-003. **Landed via the registration pattern mirroring `commands-api` / `effects-api`**: new `MapBindingLifecycle` SPI in `maps-api` (`onPlayerQuit(UUID)`, `onDisable()`), `BukkitMapBinding` implements it with a viewer-indexed handle cache, `MapDispatch` adds `registerLifecycle` / `unregisterLifecycle` / `firePlayerQuit` / `fireDisable` (auto-registers any `MapBindingLifecycle` passed to `setMapBinding`), and `paint()` now wraps `allocate` + `renderEphemeral` in a `MemoryTracker.track`/`untrack` pair (label `BukkitMapBinding`, TTL 30s). Bridge: `OnPlayerQuit` listener calls `MapDispatch.firePlayerQuit`; `RTPBukkitPlugin.onDisable` calls `MapDispatch.fireDisable`. `bindLive` deferral is unchanged (Stage 3). Tests: 3 new in `BukkitMapBindingTest` (onPlayerQuit, unknown-quit no-op, onDisable refusal) + 3 new in `MapDispatchTest` (auto-register, firePlayerQuit fan-out + exception isolation, fireDisable clears registry).
- [x] 2.3 `FoliaMapBinding` thin (2026-05-21). Subclass of `BukkitMapBinding` (which lost `final`); `allocate` / `renderEphemeral` inherit verbatim (region-safe on Folia); `bindLive` overrides with Folia-specific UOE message; new `dispatchToViewerRegion` best-effort hop is the Stage 3 seam. `RTPBukkitPlugin#onEnable` branches on `isFolia()` and installs `FoliaMapBinding` on Folia, plain `BukkitMapBinding` elsewhere. `FoliaMapBindingTest` 5/5 green. override in `rtp-folia/rtp-folia-common/.../maps/`. Refresh dispatch via `Bukkit.getGlobalRegionScheduler()`; per-viewer pixel commits via `EntityScheduler`. Scheduler ticks use `RTP.scheduler.runTaskTimerAsynchronously`. No blocking `.get()`.
- [ ] 2.4 `/rtp map create / list / cancel` Brigadier nodes through `commands-api` Brigadier bridge. Subcommands are `BaseRTPCmdImpl` subclasses, registered the same way as the menu redeem subcommand.
- [ ] 2.5 `messages.yml` keys: `mapCreated`, `mapCancelled`, `mapBusy`, `mapInvalidArg`, `mapUnknownId`. Configurable per REQ-RTP-F-013 and S-007. New `MessagesKeys` enum entries.
- [x] 2.6 Binding slot owned by `MapDispatch` (rtp-core, ADR-047) via static `AtomicReference<MapBinding>` with `setMapBinding`/`getMapBinding` accessors (default `NoopMapBinding`). `RTPBukkitPlugin.onEnable` (after `setupBukkitEvents`) calls `MapDispatch.setMapBinding(new BukkitMapBinding())`. A separate `RTPHooks#mapBinding()` accessor was judged redundant since `MapDispatch` already centralises the slot and ADR-047 owns the orchestration contract. Folia override (item 2.3) still deferred -- Folia inherits the Bukkit binding for now.
- [x] 2.7 (2026-05-21). Added a row in the `Hooks not (yet) routed through RTPHooks` table of `EXTERNAL_HOOKS.md` documenting the `MapBinding` slot, why `MapDispatch.setMapBinding` is the source of truth instead of an `RTPHooks#mapBinding()` accessor, and pointing at ADR-046 + ADR-047. `docs/dev/EXTERNAL_HOOKS.md` catalog row for the `MapBinding` hook ([ADR-026](../../adr/ADR-026-external-hook-api-surface.md)).
- [ ] 2.8 First real chart wired in: `RegionCoverageRenderer` + `RegionCoverage` model, driven from the live spiral state in `rtp-core` ([ADR-001](../../adr/ADR-001-archimedean-spiral-1d-mapping.md)). The chart-data adapter lives in `rtp-core` (no platform import); the binding consumes a `Supplier<ChartModel>`.
- [x] 2.9 `BukkitMapBindingTest` shipped (7/7 green, MockBukkit fixture): allocate idempotency, `renderEphemeral` installs exactly one renderer, `renderEphemeral` on an unknown handle throws `IllegalStateException`, `bindLive` throws `UnsupportedOperationException`, logical palette index 0 maps to `MapPalette.TRANSPARENT`, ramp 1..31 is distinct from transparent. `FoliaMapBindingThreadingTest` and `RtpMapCommandTest` still deferred (gate on item 2.3 / 2.4).
- [x] 2.10 (2026-05-21). REQ-RTP-MAP-003 row refreshed: `FoliaMapBinding` listed live alongside `BukkitMapBinding`; `FoliaMapBindingTest` (5) added to the test column. `docs/dev/TRACEABILITY.md` rows updated for REQ-RTP-MAP-001..003 with the new platform bindings + tests.
- [x] 2.11 (2026-05-21). New CHANGELOG section `[3.0.0-beta.4] ### Added (Stage 2.3 - FoliaMapBinding override)` summarises the override + offline-safe `dispatchToViewerRegion` hop + `isFolia()` install branch. `CHANGELOG.md` bullet — Bukkit/Paper/Folia bindings + `/rtp map` command.
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
