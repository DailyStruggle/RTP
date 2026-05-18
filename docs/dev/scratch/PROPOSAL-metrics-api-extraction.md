# PROPOSAL - Extract `metrics-api/` subproject (deferred work)

**Status**: **ACTIVE** (user override 2026-05-17). Originally deferred; user explicitly directed "create metrics-api now" 2026-05-17 after the monorepo-reuse audit of sibling `*-api` modules. The C6 hard-prerequisite from §1.0 is honoured: C6 has been submitted in this session, so §1.0 sequencing is satisfied. The §7 "one beta in production" gate is **waived by user direction** with the M2-freeze risk acknowledged via §6 risk 5 option (a) (no external SPI consumers).
**Blocking rule**: D-005 (multi-module structural change). User approval recorded in chat history 2026-05-17.
**Related parallel work (deferred)**: `rtp-proxy-common` → `proxy-api` extraction. Per user direction 2026-05-17, the proxy module's generic transport/analytics tooling (heartbeat, transport, selector, in-memory binding, reservation slot) will be extracted into a neutral `proxy-api` module as a *simple file transfer* once `metrics-api` lands. RTP-specific dispatcher / request / message-key surface stays in `rtp-proxy-common`. Tracking note only; do not implement in this session.

---

## 1. Motivation

Bring the metrics SPI into architectural parity with `rtp-api`, `commands-api`, `effects-api`, `maps-api`. The current metrics surface (`Metrics`, `MetricsBinding`, `MetricsSnapshot`, `FoliaRegionSample`) lives inside `rtp-core` only because we hadn't bothered to extract it yet; it has zero `org.bukkit` / `net.minecraft` / Folia imports and is exactly the same shape of addon-facing, platform-neutral, stable-contract SPI that we already isolate elsewhere.

### 1.0 Relationship to Section C **C6** (cross-platform consolidation)

This extraction and C6 are **two halves of the same consolidation effort**, applied at different layers:

- **C6 (§1.6, call-site layer)** - routes every metric-shaped read (`getTPS`, `getOnlinePlayers().size()`, `getMaxPlayers`) through the binding boundary. Net effect: there is exactly one code path on every platform that produces a `MetricsSnapshot` field, and an ArchUnit guard prevents drift.
- **This proposal (module layer)** - moves the SPI those bindings implement into its own subproject. Net effect: the binding boundary that C6 enforces becomes a *module* boundary, not just a package one, and addons can consume the SPI without dragging `rtp-core` along.

C6 is therefore a **hard prerequisite** for this extraction, not just a "do it first" preference:

1. The §1.6.5 ArchUnit drift guard is written against package-level allow-lists today. Extracting before C6 lands means re-authoring that guard cross-module mid-flight, doubling the diff.
2. Doing C6 *after* the extraction means the consolidation PR has to touch both `rtp-core` and `metrics-api` plus every platform binding - the move has multiplied the surface area C6 needs to clean up.
3. Sequencing C6 first leaves this extraction as a near-pure module move (4 files + build wiring + deprecated shims), which is the lowest-risk shape for a structural change.

Put differently: **C6 finishes the consolidation in code; this proposal finishes the consolidation in module structure.** Treat the two as a single conceptual unit even though they ship in separate sessions.

Concrete wins:

1. **Addon ergonomics.** A future "RTP metrics exporter" addon (Prometheus / OpenTelemetry / Discord bridge / custom HUD) should depend on a thin, stable module - not the whole of `rtp-core` (spiral math, region pools, teleport pipeline, anvil prefilter glue).
2. **Cleaner dependency direction.** Platform bindings (`PaperMetricsBinding`, `BukkitTpsSampler`, `FoliaMetricsBinding`, `FabricMetricsBinding`) reach into `rtp-core` today; post-extraction they depend on `metrics-api` directly. The §1.6.5 ArchUnit guard from C6 becomes enforceable against a module, not a package.
3. **Docs surface.** A module-local `metrics-api/docs/` lets us write the "how to implement a binding" guide in the obvious place, with its own ADR sequence, and link to it from `METRICS_PLAN.md` / `AGENTS.md` / addon docs.
4. **Monorepo reuse (added 2026-05-17).** `metrics-api` is the first subproject in this repo explicitly designed for consumption by **multiple sibling plugins**, not just RTP. The surface must therefore be plugin-agnostic: host-runtime fields (TPS / MSPT / players / softCap / heap / Folia regions) belong on the shared snapshot; RTP-specific counters (queue depth, pending teleports, memory tracker, chunk-load backlog, pipeline-ms, DB latency) move to a typed extension slot (see §2.1). Without this split the module is mechanically shareable but carries RTP-named dead fields for every other consumer.

### 1.1 Monorepo-reuse decisions (locked 2026-05-17)

The following answers replace the previous "defer to extraction time" stance because they govern the **shape** of M2's `MetricsSnapshot`, which must be frozen *before* the first beta ships (per §6 risk 2 / §7 trigger). Recording them here so M2 doesn't lock in an unshareable shape:

- **Extension model**: `MetricsSnapshot` carries only host-runtime fields. Plugin-specific counters live on `MetricsExtension<T>` (typed slot, one per consumer plugin). RTP defines `RTPMetricsExtension` inside `rtp-core` carrying the current pipeline counters. See revised §2.1.
- **Dispatcher location**: **static registry inside `metrics-api`** (option (a) from the 2026-05-17 review). Any plugin in the monorepo can call `Metrics.registerBinding(MetricsBinding)` / `Metrics.registerExtension(MetricsExtension<?>)` without RTP being installed. Last-writer-wins with a `RTP.log(WARNING, ...)` (or platform-neutral equivalent) when a non-NOOP binding is overwritten. This subsumes the old `MetricsBindingDispatcher` role; the dispatcher class in `rtp-plugin` becomes a thin bootstrap caller, not a container.
- **Package root**: neutral - `io.github.dailystruggle.metrics.api.*` (not `io.github.dailystruggle.rtp.api.metrics.*`). Mirroring `rtp-api` would signal "RTP-owned", which is exactly the wrong signal for a cross-plugin module. Supersedes §3 Q2.
- **Lite-assembly**: `metrics-api` is **bundled** into the lite jar (shaded under a neutral coordinate). Other monorepo plugins depending on `metrics-api` must declare it `compileOnly` so dual-loading is impossible at runtime regardless of which sibling plugin is installed. Supersedes §3 Q3.
- **Version pinning**: all monorepo consumers pull `metrics-api` through the root Gradle composite build / version catalog only - no per-plugin override. Avoids classic SPI version-skew when two sibling plugins try to install bindings against incompatible snapshot shapes.

---

## 2. Scope

### 2.1 What moves to `metrics-api/`

Public SPI under the neutral root `io.github.dailystruggle.metrics.api.*` (per §1.1):

- `rtp-core/.../common/metrics/Metrics.java` -> `metrics-api/.../metrics/api/Metrics.java` (read-only accessor + **static binding/extension registry**, per §1.1 dispatcher decision; no longer just an interface)
- `rtp-core/.../common/metrics/MetricsBinding.java` -> `metrics-api/.../metrics/api/MetricsBinding.java` (host-runtime contract: `tps1m/5m/15m`, `mspt`, `playerCount`, `softCap`, `chunkLoadBacklog`, `databaseLatencyMs`, `foliaRegions()`)
- `rtp-core/.../common/metrics/MetricsSnapshot.java` -> `metrics-api/.../metrics/api/MetricsSnapshot.java` (**reshaped**: keeps only host-runtime fields - `tps1m/5m/15m`, `mspt`, `tickBudgetUtilisation`, `playerCount`, `softCap`, `heapUsed/MaxBytes`, `takenAtEpochMs`, `foliaRegions`; gains a typed extension lookup `<T extends MetricsExtension<T>> T extension(Class<T>)`)
- `rtp-core/.../common/metrics/FoliaRegionSample.java` -> `metrics-api/.../metrics/api/FoliaRegionSample.java`
- New `metrics-api/.../metrics/api/MetricsExtension.java` (marker + `name()` for diagnostics; one registered instance per consumer plugin)
- New `metrics-api/.../metrics/api/package-info.java`

**RTP-specific counters move OUT of the shared snapshot.** A new `RTPMetricsExtension implements MetricsExtension<RTPMetricsExtension>` lives in `rtp-core` and carries `queueDepth`, `pendingTeleports`, `memoryTrackerEntries`, `chunkLoadBacklog` (if RTP-attributed), `avgPipelineMs`, RTP-specific `databaseLatencyMs`. `CoreMetrics#snapshot()` populates it and registers via `Metrics.registerExtension(rtpExt)`. Reads in `rtp test full`, `/rtp info`, `RTPCostMetricsCharts` switch from `snapshot.queueDepth` to `snapshot.extension(RTPMetricsExtension.class).queueDepth`.

### 2.2 What stays in `rtp-core`

Internal implementation and pipeline-coupled counters:

- `CoreMetrics` - the concrete aggregator that assembles a `MetricsSnapshot` from the registered binding (host fields) + registered extensions (plugin fields) + `HeapSampler` + `PipelineHistogram`. Post-§1.1, the dispatcher no longer downcasts: `Metrics.registerBinding(...)` / `Metrics.registerExtension(...)` are public static calls on the API. `CoreMetrics` is one consumer of the registry like any other plugin; it just happens to also own histogram/heap sampling that no other plugin needs.
- `HeapSampler` - pipeline-internal helper, not on the public surface.
- `PipelineHistogram` - internal counter aggregation, not on the public surface.
- `MetricsKeys` enum + `metrics.yml` - sibling of `PerformanceKeys` / `EconomyKeys`, lives with the `Configs` wiring.

### 2.3 What stays in `rtp-plugin`

- `MetricsBindingDispatcher` - **demoted** per §1.1: keeps only the Bukkit-family reflective platform-probe (load `PaperMetricsBinding` vs `FoliaMetricsBinding` vs fallback), then calls `Metrics.registerBinding(...)` on the static registry now living in `metrics-api`. No longer owns the binding singleton.
- `RTPCostMetricsCharts`, `BStatsChartIds`, `RuntimeHealthBucketsTest` - bStats is a plugin-runtime concern, not API.

### 2.4 What stays in each platform module

- `PaperMetricsBinding` (rtp-paper-common)
- `BukkitTpsSampler` (rtp-bukkit-common)
- `FoliaMetricsBinding`, `FoliaRegionTpsSampler` (rtp-folia-common)
- `FabricMetricsBinding` (rtp-fabric-common)

All four become `implements io.github.dailystruggle.metrics.api.MetricsBinding` (neutral root per §1.1) and depend on `metrics-api` instead of reaching into `rtp-core`.

---

## 3. Open questions to resolve at extraction time

1. ~~**`Metrics.setBinding(...)` visibility**~~ - **Resolved by §1.1**: superseded by the static registry on `Metrics` (`registerBinding` / `registerExtension`). No downcast, no `MutableMetrics` sub-interface. The old `((CoreMetrics) RTP.metrics).setBinding(...)` pattern goes away.
2. ~~**Package layout**~~ - **Resolved by §1.1**: neutral root `io.github.dailystruggle.metrics.api.*`.
3. ~~**Lite-assembly behaviour**~~ - **Resolved by §1.1**: bundled (shaded) into rtp-lite; other monorepo consumers use `compileOnly`.
4. **Re-export from `rtp-core`** - for one release cycle, keep deprecated type-aliases / re-export shims under `io.github.dailystruggle.rtp.common.metrics.*` so old RTP addons compile. RTP-only concern; other monorepo plugins start fresh on the neutral root.
5. **`net-metrics` future-proofing** - `MULTI_SERVER_PLAN.md` Phase 2+ adds cross-backend / proxy-side counters. With the extension model from §1.1 these naturally live on a `NetworkMetricsExtension` registered by `rtp-proxy-common`, not on the shared `MetricsSnapshot`. Confirm at extraction time but the answer is now near-mechanical.
6. **Last-writer-wins vs explicit conflict on `registerBinding`** - default plan per §1.1 is last-writer-wins with a warning log. Decide at extraction time whether the second `registerBinding(NOOP)` call (e.g. a plugin unloading) should clear or be ignored.
7. **Extension cardinality** - one extension per `Class<? extends MetricsExtension<?>>` keyed by type. Re-registration overwrites with a warning. Document this contract on `MetricsExtension`'s Javadoc so two sibling plugins don't accidentally collide on a shared extension class.

---

## 4. Docs to create at extraction time

- `metrics-api/README.md` - short orientation: what the module is, who consumes it, how to install a binding.
- `metrics-api/docs/INDEX.md` - task router mirroring `commands-api/docs/INDEX.md` style.
- `metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md` - why we split out of `rtp-core`, what stayed behind, the read-only-vs-mutable boundary (§3 Q1), and the SPI contract.
- Row in root `docs/adr/README.md` *Subproject ADRs* table.
- Update `.junie/AGENTS.md` *Architecture Boundaries* and *Required Reading* to mention `metrics-api/`.
- Update `docs/dev/METRICS_PLAN.md` *Module Placement* section to reflect the new home.
- Update `docs/dev/TRACEABILITY.md` REQ-RTP-OBS-001/002/003 rows for moved class paths.
- Update `CHANGELOG.md` under the active unreleased version with a single net-delta bullet.

---

## 5. Implementation order (when triggered)

1. Create `metrics-api/build.gradle` (Java-only, no Loom, no platform deps). Wire into `settings.gradle`.
2. Move the 4 SPI files; add `package-info`.
3. Add deprecated re-export shims under `io.github.dailystruggle.rtp.common.metrics.*` in `rtp-core` (1-cycle compat).
4. `rtp-core` `build.gradle` depends on `metrics-api`. `CoreMetrics` updated to `implements io.github.dailystruggle.rtp.api.metrics.Metrics`.
5. Each platform module's `build.gradle` adds `api project(':metrics-api')` and the binding class is updated to import from the new package.
6. `MetricsBindingDispatcher` downcast adjusted if needed (§3 Q1).
7. ArchUnit guard from C6 §1.6.5 updated to reference module boundaries.
8. Docs deliverables from §4.
9. Full multi-module `.\gradlew build`. Run all metrics-related test classes listed in `TRACEABILITY.md` REQ-RTP-OBS-001/002/003.
10. Submit with CHANGELOG bullet.

---

## 6. Risks / things that could derail this

1. **Module proliferation cost.** ~30 subprojects already; adds build / IDE indexing overhead for a 4-file API. Mitigation: keep `metrics-api` deliberately small (no impl, no platform code).
2. **Signature churn risk.** Metrics surface has changed twice in six sessions (M1 binding shape; M2 `foliaRegions()` + 15->16-arg ctor). Wait until the M2 shape is exercised in production at least one release before extraction so we're not chasing API breaks immediately.
3. **Network-mode metrics direction.** If Phase 2 multi-server lands a separate metrics module before this proposal is executed, revisit whether to merge or keep parallel.
4. ~~**`CoreMetrics.setBinding` downcast (§3 Q1).**~~ Resolved by §1.1 static-registry decision; no downcast.
5. **M2 surface freeze risk (added 2026-05-17).** The extension-model split from §1.1 means the 16-arg `MetricsSnapshot` constructor currently shipping in beta carries RTP-specific fields that will be removed before extraction. Two practical options: (a) freeze M2 as-is in the next beta, accept that the extraction will be a *breaking* `MetricsSnapshot` change for anyone consuming the M2 ctor (no public addons yet, so blast radius is internal only); (b) pre-emptively reshape `MetricsSnapshot` in `rtp-core` *before* the beta - keep RTP fields temporarily but ship the `extension(Class)` lookup so future-proof callers can opt in. Recommend (a) - the beta has no external SPI consumers, the M2 freeze is internal-only, and option (b) leaks the extension model into `rtp-core` before the module boundary exists which contradicts §1.0 sequencing.
6. **Cross-plugin binding races (added 2026-05-17).** With the static registry from §1.1, two sibling plugins booting on the same server could call `registerBinding` in a non-deterministic order (server-load order is not stable across restarts). For host-runtime bindings this is harmless (every Paper/Folia binding produces the same TPS), but document the last-writer-wins contract clearly so plugin authors don't write a binding expecting exclusive ownership.

---

## 7. Trigger to revisit

**Superseded 2026-05-17**: user override directs immediate extraction. The original gate is preserved below for historical reference.

Original gate (waived):

- ~~Section C **C5** (TRACEABILITY) and **C6** (§1.6 cross-platform consolidation) are submitted.~~ C6 submitted this session; C5 is independent and does not block.
- ~~At least one beta release has shipped with the M2 metrics surface in production.~~ Waived per §6 risk 5 option (a): the M2 16-arg `MetricsSnapshot` is internal-only, no external SPI consumers exist, so the freeze risk is acceptable and the extension-model split lands cleanly in the same diff as the extraction rather than as a breaking follow-up.

## 8. Sibling extraction: `proxy-api` (deferred, tracked here for context)

Per user direction 2026-05-17, `rtp-proxy-common` will be split:

- **Extract** into a new `proxy-api/` subproject (simple file transfer, no reshaping):
  - Generic transport SPI: `NetworkTransport`, `ProxySender`, `ProxyHeartbeat`, `BackendHeartbeat`, `Subscription`
  - Analytics / observability shapes: `NetworkSnapshot`, `BackendSelector`, `LoadBalancerConfig`, `WeightedAverageBackendSelector`
  - In-memory default binding: `InMemoryNetworkStateBinding`
  - Generic outcome / reason enums: `DispatchOutcome`, `ReleaseReason`, `TransferOutcome`, `TriggerType`, `MessageKey`
- **Keep** in `rtp-proxy-common`: `RtpDispatcher`, `RtpRequest`, `ReservationClient`, `ReservationToken` (RTP-teleport-specific reservation semantics, REQ-RTP-NET-011/012/014).
- **Package root**: neutral, e.g. `io.github.dailystruggle.proxy.api.*` (mirrors the `metrics-api` decision from §1.1).
- **Timing**: after `metrics-api` lands and ships at least one full build green. Not in the same session.
- **Scope discipline**: *simple transfer* per user direction - no reshaping of types, no extension model, no static registry. The reservation slot stays where it is; if a sibling plugin later needs a non-teleport reservation primitive, that is a separate proposal.
