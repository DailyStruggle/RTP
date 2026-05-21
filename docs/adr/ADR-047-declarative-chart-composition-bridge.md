# ADR-047 - Declarative Chart Composition Bridge

**Status:** Accepted
**Date:** 2026-05-20

## Context

Two halves of the runtime cartography stack already exist but face away from
each other:

1. **The pixel side** ([ADR-046](ADR-046-maps-api-module.md), `maps-api`).
   A sealed `ChartModel` hierarchy (`Heatmap2D`, `CategoryDistribution`,
   `TimeSeries`, `RegionCoverage`, `MermaidChart`), stateless
   `ChartRenderer<M>` implementations, and a `MapBinding` SPI. Renderers are
   intentionally ignorant of where their model came from; an ArchUnit rule in
   `ReqRtpMap002NoChunkIoTest` forbids `mapsapi.render..` from depending on
   `org.bukkit..` / `net.minecraft..` or blocking on futures.
2. **The data side** (`metrics-api`, [ADR-040](ADR-040-cross-backend-metric-time-series-publication.md),
   plus the `MemoryShape.badKeysCache`, `RegionQueueManager` pool sizes,
   `FailTypes` counters, and the spiral model from
   [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)). Snapshots are
   non-blocking and already populated on every supported backend.

What is missing is a renderer-neutral way for a command, menu click, or addon
to say "give me a chart of X over Y" without coupling to a specific
`ChartRenderer`, a specific `MapBinding`, or a specific data source. The
existing options are all wrong:

- Imperative `Supplier<ChartModel>` wiring per command site duplicates the
  data-source-to-model adapter five times and prevents addons from supplying
  their own chart kinds.
- Folding the bridge into [ADR-039](ADR-039-rtpadmin-diagnostic-surfaces.md)
  (`/rtpadmin diag` Diagnostic Surfaces) couples `MapBinding` delivery to the
  `MenuRenderer` model and inverts the dependency direction: ADR-039 surfaces
  are a *consumer* of the bridge, not its home.
- Folding it into ADR-046 puts a data-side concern into a pixel-side module
  and would force `maps-api` to depend on `rtp-core` (forbidden by
  *Architecture Boundaries*).

The user-facing trigger is the approved proposal
[`docs/dev/scratch/PROPOSAL-metrics-to-maps.md`](../dev/scratch/PROPOSAL-metrics-to-maps.md)
(approved 2026-05-20), Stage 1 of which ships a bad-points heatmap clickable
from `/rtp info`. The same bridge is required by the four additional chart
kinds (`REGION_COVERAGE`, `FAIL_RATE_HEATMAP`, `CACHE_OCCUPANCY`,
`METRIC_SPARKLINE`) that Stage 3 of
[`CHECKLIST-metrics-to-maps.md`](../dev/scratch/CHECKLIST-metrics-to-maps.md)
adds; locking the contract up-front keeps every later resolver additive.

## Decision

Introduce a three-part declarative bridge whose surfaces live in the modules
that already own each concern:

1. **`ChartSpec` (in `rtp-api`).** A renderer-neutral record describing a
   chart request: `(Kind kind, String regionName, @Nullable UUID viewer,
   int tilesRows, int tilesCols, @Nullable String metricKey,
   int windowSeconds)`. `Kind` is a Java enum (not sealed; enums cannot be
   sealed) with one Stage-1 value (`BAD_POINTS_HEATMAP`) and four Stage-3
   reserved values. The record's compact constructor null-rejects required
   fields and range-checks tiles/window. No `maps-api` or platform import.
2. **`ChartSpecResolver` SPI (in `rtp-core`).** A functional interface
   `CompletableFuture<ChartModel> resolve(ChartSpec spec, RTPCtx ctx)`, plus
   a `ChartSpecResolvers` registry keyed by `ChartSpec.Kind`. Resolvers run
   on `RTP.scheduler.runTaskAsynchronously` (or the Folia equivalent), may
   not block on `Future#get`/`#join`, and may not perform synchronous chunk
   I/O (S-005). Resolvers read only in-memory state already maintained by
   `rtp-core` / `metrics-api`; no new sampler, no new schema, no new DB
   column.
3. **`MapDispatch` orchestrator (in `rtp-core`).** A single entry point that
   takes a `ChartSpec`, looks up the resolver, awaits the resolved
   `ChartModel`, picks the matching `ChartRenderer` by model subtype, and
   delivers via the installed `MapBinding`. Failure paths surface through
   configurable `messages.yml` keys (REQ-RTP-F-013, S-007): missing binding,
   missing resolver, resolver exception. Silent swallowing is forbidden
   (S-004) - every failure path logs via `RTP.log(WARNING, ...)`.

A new `MenuAction.Kind.OPEN_MAP` (added in Stage 2 of the checklist) carries
a short-lived `ChartSpec` token (60 s TTL by default, single-use) consumed by
a platform-side handler in `rtp-plugin`. Tokens live in a `ChartSpecTokens`
store in `rtp-core` and mirror the existing menu-redeem token pattern from
[ADR-035](ADR-035-menu-framework.md).

The bridge is **strictly downstream** of `MetricsSnapshot` and any future
ADR-039 `SurfaceModel`. Maps cannot widen the data contract; if a new chart
needs new data, that data must land in `metrics-api` / `rtp-core` first.

### Module edges

- `rtp-api` -> nothing new (adds the `maps` package).
- `rtp-core` -> `maps-api` (types only: `ChartModel`, `ChartRenderer`,
  `MapBinding`). The reverse edge is forbidden by the existing ArchUnit rule
  in `ReqRtpMap002NoChunkIoTest`, extended in Stage 1 to whitelist
  `MapDispatch`'s reference to a single renderer singleton
  (`HeatmapRenderer.INSTANCE`).
- `rtp-plugin` -> `rtp-core` (existing). Platform delivery callback for
  `OPEN_MAP` actions lives here.

## Alternatives Considered

1. **Imperative `Supplier<ChartModel>` per command site.** Rejected: every
   admin command would re-implement the data-source-to-model adapter, and
   addons could not register new chart kinds without forking the command
   class. Also conflicts with the Stage 3 goal of one resolver per kind.
2. **Fold the bridge into ADR-039 (`DiagnosticSurface` + `SurfaceModel`).**
   Rejected: ADR-039 is renderer-pluggable for *menus*, not maps; coupling
   `MapBinding` delivery to `MenuRenderer` inverts the dependency direction
   and forces every diagnostic surface to either ship a map flavour or
   declare it does not have one. A `MapBinding` peer renderer alongside
   `MenuRenderer` is the right shape for ADR-039 *to consume*, but it
   belongs in its own ADR.
3. **Fold the bridge into ADR-046 (`maps-api`).** Rejected: the resolver
   touches `rtp-core` state (`MemoryShape`, `RegionQueueManager`, anvil
   biome raster) that `maps-api` is forbidden from importing. Putting the
   resolver in `maps-api` would force a downward edge that ADR-046's ArchUnit
   rule explicitly forbids.
4. **Single resolver registry vs. per-kind class.** Chose per-kind class
   (one `ChartSpecResolver` implementation per `Kind`) keyed by enum value.
   A single registry-of-functions would compile, but per-kind classes give
   each resolver a clean testing surface (one test class per resolver,
   matching the `BadPointsHeatmapResolverTest` / `RegionCoverageResolverTest`
   / ... pattern) and let resolvers declare their own dependencies via
   constructor injection.
5. **Make `MenuAction.Kind.OPEN_MAP` carry the `ChartSpec` inline.**
   Rejected: `MenuAction` is serialised into book/tellraw payloads and
   round-tripped through `BookMenuRenderer`; embedding a multi-field spec
   bloats every menu page and exposes internal fields to click-handlers that
   should not interpret them. The token store keeps the menu payload to a
   single UUID and centralises spec lifetime management.
6. **Skip the token store; resolve on `/rtp info` page composition.**
   Rejected: composing the page is the wrong time to fan out to all
   resolvers (it inflates response time and forces synchronous work on the
   command thread); the click is the right trigger, and the token is the
   minimum mechanism that survives a re-render between page paint and click.

## Consequences

**Required:**

- One new module edge: `rtp-core` -> `maps-api` (types only). Documented in
  the *Architecture Boundaries* decision order; this is the only place where
  `rtp-core` references `maps-api`, and it remains forbidden from importing
  `mapsapi.render..` beyond a single renderer-singleton reference.
- One new `RTPHooks` slot for `MapBinding` (owned by
  [`CHECKLIST-maps-api.md`](../dev/scratch/CHECKLIST-maps-api.md) Stage 2.6;
  this ADR depends on it but does not own its catalog row in
  [`EXTERNAL_HOOKS.md`](../dev/EXTERNAL_HOOKS.md)).
- Four new `messages.yml` keys (`mapBindingMissing`, `mapResolverMissing`,
  `mapUnavailable`, `mapBusy`) under REQ-RTP-F-013, propagated through the
  locale TSV pipeline and `LocaleParityTest`.
- One new requirement (`REQ-RTP-MAP-006`) and one TRACEABILITY row.

**Enabled:**

- ADR-039 `DiagnosticSurface` consumers can render to a `MapBinding` by
  building a `ChartSpec` instead of authoring a per-surface map renderer.
- Stage 3 resolvers add a single class plus one test class each; no command
  rewrite required.
- Addons can register their own `ChartSpec.Kind` (via a future `Kind`
  extension point) and resolver without modifying `rtp-core`.

**Trade-offs:**

- Two indirections between the click and the painted map: token lookup, then
  resolver dispatch, then renderer pick. The latency cost is one async
  scheduler hop (resolver) plus one main-region hop (binding commit); both
  are already mandatory under S-005 / Folia rules.
- `MapDispatch` becomes a single coordination point. Its failure modes are
  exhaustively enumerated by `MapDispatchTest` and surfaced through
  configurable messages; we accept a single hotspot in exchange for keeping
  every command site free of the dispatch logic.
- The Stage-3 `Kind` values exist in `rtp-api` before their resolvers exist
  in `rtp-core`. A `ChartSpec` constructed with a reserved kind will dispatch
  to `mapResolverMissing` until the resolver lands - intentional, and
  matches how `effects-api` ships new effect kinds ahead of their bindings.

## References

- [`docs/dev/scratch/PROPOSAL-metrics-to-maps.md`](../dev/scratch/PROPOSAL-metrics-to-maps.md) - approved scope (2026-05-20).
- [`docs/dev/scratch/CHECKLIST-metrics-to-maps.md`](../dev/scratch/CHECKLIST-metrics-to-maps.md) - multi-session implementation plan.
- [`docs/dev/REQUIREMENTS.md`](../dev/REQUIREMENTS.md) - host of REQ-RTP-MAP-006 (added in Stage 0).
- [`docs/dev/TRACEABILITY.md`](../dev/TRACEABILITY.md) - REQ-RTP-MAP-006 row.
- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) - spiral model consumed by the bad-points resolver via `Shape.locationToXZ`.
- [ADR-026](ADR-026-external-hook-api-surface.md) - `RTPHooks` registry pattern.
- [ADR-035](ADR-035-menu-framework.md) - menu framework; `MenuAction.Kind.OPEN_MAP` extension and token pattern.
- [ADR-039](ADR-039-rtpadmin-diagnostic-surfaces.md) - diagnostic surfaces; future consumer of the bridge.
- [ADR-040](ADR-040-cross-backend-metric-time-series-publication.md) - cross-backend metrics; sourced read-only by Stage-3 resolvers.
- [ADR-044](ADR-044-command-tree-menu-reflector.md) - command-tree menu reflector; entry-point composition site.
- [ADR-046](ADR-046-maps-api-module.md) - `maps-api` module providing `ChartModel`, `ChartRenderer`, `MapBinding`.
