# metrics-api-ADR-001: Module extraction from rtp-core

- **Status:** Accepted (2026-05-17).
- **Supersedes:** none.
- **Superseded by:** none.
- **Related:**
  - [ADR-032](../../../docs/adr/ADR-032-teleport-pipeline-latency-histogram.md): the pipeline-latency histogram that stays in `rtp-core`.
  - [`docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md): tracks the deferred sibling `proxy-api` extraction (see *Deferred sibling extraction* below) and the future `NetworkMetricsExtension`.
  - [`docs/dev/METRICS_PLAN.md`](../../../docs/dev/METRICS_PLAN.md): project-wide metrics design, catalogue, and `/rtp info` surface.
  - REQ-RTP-OBS-001/002/003 in [`docs/dev/REQUIREMENTS.md`](../../../docs/dev/REQUIREMENTS.md) and [`docs/dev/TRACEABILITY.md`](../../../docs/dev/TRACEABILITY.md).
  - [maps-api-ADR-001](../../../maps-api/docs/adr/maps-api-ADR-001-bootstrap.md): sibling `*-api` module-bootstrap precedent.

---

## Context

The metrics SPI (`Metrics`, `MetricsBinding`, `MetricsSnapshot`, `FoliaRegionSample`,
`MetricsExtension`) lived inside `rtp-core` only because it had not yet been extracted. It
carries zero `org.bukkit.*` / `net.minecraft.*` / Folia imports and is the same shape of
addon-facing, platform-neutral, stable-contract SPI already isolated as `rtp-api`,
`commands-api`, `effects-api`, and `maps-api`.

Two pressures motivated the move:

1. **Addon ergonomics.** A future metrics-exporter addon (Prometheus / OpenTelemetry /
   Discord bridge / HUD) should depend on a thin, stable module, not the whole of
   `rtp-core` (spiral math, region pools, teleport pipeline, anvil glue).
2. **Monorepo reuse.** `metrics-api` is the first subproject explicitly designed for
   consumption by multiple sibling plugins, not just RTP. The surface therefore has to be
   plugin-agnostic: host-runtime fields belong on the shared snapshot; plugin-specific
   counters move to a typed extension slot.

This extraction is the module-layer half of the metrics consolidation; the call-site half
(routing every metric-shaped read through the binding boundary) shipped as C6.

## Decision

### 1. New module

A pure-Java `metrics-api/` subproject (`java-library`, no Bukkit / Fabric / Loom
dependency), wired into `settings.gradle` next to `maps-api`. ArchUnit is a
`testImplementation` dependency, mirroring the sibling `*-api` modules.

### 2. Neutral package root

The SPI lives under `io.github.dailystruggle.metrics.api.*`, not
`io.github.dailystruggle.rtp.api.metrics.*`. An RTP-scoped root would signal "RTP-owned",
which is the wrong signal for a cross-plugin module.

### 3. What moved

`Metrics`, `MetricsBinding`, `MetricsSnapshot`, `FoliaRegionSample`, and `MetricsExtension`
moved into the module. `package-info.java` documents the surface.

`MetricsSnapshot` carries only host-runtime fields (`tps{1,5,15}m`, `mspt`,
`tickBudgetUtilisation`, `playerCount`, `softCap`, `heapUsed/MaxBytes`, `takenAtEpochMs`,
`foliaRegions`) plus a typed extension map reachable via `withExtension(...)` /
`extension(Class<T>)`.

`Metrics` gained a thread-safe static registry (`registerBinding` / `currentBinding` /
`registerExtension` / `registeredExtensions` / `resetRegistryForTesting`) so any monorepo
plugin can install a binding or extension supplier without owning RTP's `CoreMetrics`
instance. Binding registration is last-writer-wins with a `WARNING` log when a non-NOOP
binding is displaced; extension registration is additive.

### 4. What stayed in rtp-core

- `CoreMetrics`: the concrete aggregator implementing the neutral `Metrics`. It mirrors its
  binding into the static registry and composes registered extension suppliers per snapshot.
- `HeapSampler`, `PipelineHistogram`, `MetricsSnapshotRing`: internal samplers, not on the
  public surface.
- `RTPMetricsExtension`: the RTP-specific counter slot (`queueDepth`, `pendingTeleports`,
  `memoryTrackerEntries`, `chunkLoadBacklog`, `avgPipelineMs`, `databaseLatencyMs`),
  implementing the neutral `MetricsExtension<RTPMetricsExtension>`.

### 5. Clean break, no shim layer

An initial type-alias shim approach was abandoned: Java generic invariance plus the
non-inheritance of interface static methods produced cascading conflicts in same-package
tests. Instead, all call sites were rewired directly to import from
`io.github.dailystruggle.metrics.api.*` and the old `rtp-core` SPI files were deleted.

### 6. Drift guard

`MetricsConsolidationArchTest` (in `rtp-plugin`) was re-authored against the module
classpath: concrete `MetricsBinding` implementations must live in platform adapters, not in
`rtp.common.*` / `rtp.api.*`, and `metrics-api` itself must stay free of platform imports.

### 7. Registry contract: last-writer-wins and extension cardinality

Binding registration is last-writer-wins: a later `registerBinding(...)` replaces the
current binding and logs a `WARNING` when a non-NOOP binding is displaced (host-runtime
bindings on a given platform all produce the same TPS/MSPT, so on a single server this is
harmless; the warning exists to surface accidental double-installs). On a server hosting
two sibling monorepo plugins the boot order is not stable across restarts, so binding
authors must not assume exclusive ownership.

Extensions are keyed by their concrete `Class<? extends MetricsExtension<?>>`: exactly one
instance per type, re-registration overwrites with a warning. Two sibling plugins must not
share an extension class, or they collide on that single slot.

### 8. Distribution: lite bundling, `compileOnly` for siblings, single-version pinning

`metrics-api` is shaded into the RTP lite assembly under its neutral coordinate. Any other
monorepo plugin that consumes the SPI declares it `compileOnly`, so the classes are never
dual-loaded at runtime regardless of which sibling plugin is installed. All consumers pull
`metrics-api` through the root Gradle composite build only (no per-plugin version override)
to avoid SPI version skew between sibling plugins installing bindings against incompatible
snapshot shapes.

### 9. Network-mode counters: a future `NetworkMetricsExtension`, not snapshot fields

The `MULTI_SERVER_PLAN.md` Phase 2+ cross-backend / proxy-side counters land on a
`NetworkMetricsExtension` registered by `rtp-proxy-common`, following the same typed-slot
model as `RTPMetricsExtension`. They do not widen the shared `MetricsSnapshot`.

## Deferred sibling extraction: `proxy-api`

The `rtp-proxy-common` module carries a generic transport/analytics surface
(`NetworkTransport`, `ProxySender`, `ProxyHeartbeat`, `BackendHeartbeat`, `Subscription`,
`NetworkSnapshot`, `BackendSelector`, `LoadBalancerConfig`,
`WeightedAverageBackendSelector`, `InMemoryNetworkStateBinding`, and the
`DispatchOutcome` / `ReleaseReason` / `TransferOutcome` / `TriggerType` / `MessageKey`
enums) that is the same shape of plugin-agnostic SPI as `metrics-api`. A future `proxy-api/`
extraction (neutral root `io.github.dailystruggle.proxy.api.*`) is a *simple file transfer*
with no reshaping: the RTP-teleport-specific reservation surface (`RtpDispatcher`,
`RtpRequest`, `ReservationClient`, `ReservationToken`) stays in `rtp-proxy-common`. This
remains deferred and is tracked in [`MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md);
it is recorded here only because it mirrors this ADR's neutral-root decision.

## Alternatives Considered

| Alternative | Why rejected |
|-------------|--------------|
| Keep the SPI in `rtp-core` | Forces every metrics-exporter addon and every sibling plugin to drag the whole core onto its classpath. |
| RTP-scoped package (`rtp.api.metrics.*`) | Signals RTP ownership of a deliberately cross-plugin module; misleads sibling consumers. |
| Type-alias / re-export shims under the old package for one cycle | Java generics invariance + interface static-method non-inheritance made the shims conflict in same-package tests; the clean break was lower-risk since there are no external SPI consumers yet. |
| Keep RTP-specific counters on the shared snapshot | Ships RTP-named dead fields to every other monorepo consumer; the typed extension slot keeps the shared snapshot plugin-agnostic. |

## Consequences

- **Positive:**
  - Addons and sibling plugins depend on a thin, stable module instead of `rtp-core`.
  - The binding boundary that C6 enforced at the package layer is now a module boundary.
  - The shared snapshot is plugin-agnostic; RTP counters live on `RTPMetricsExtension`, and
    future network-mode counters land on their own extension without touching the snapshot.
- **Negative / trade-offs:**
  - One more subproject to index and build (mitigated by keeping the module impl-free).
  - The clean break means any code still importing the old `rtp.common.metrics.*` SPI no
    longer compiles; this was acceptable because there are no external SPI consumers yet.

## References

- [`docs/dev/METRICS_PLAN.md`](../../../docs/dev/METRICS_PLAN.md)
- [`docs/dev/REQUIREMENTS.md`](../../../docs/dev/REQUIREMENTS.md) (REQ-RTP-OBS-001/002/003)
- [`docs/dev/TRACEABILITY.md`](../../../docs/dev/TRACEABILITY.md)
