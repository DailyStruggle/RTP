# RTP Metrics Plan

This document is the canonical plan for **runtime metrics** in RTP — the platform-portable abstractions for sampling tick-rate, MSPT, heap, queue depths, pipeline latencies, and related health signals on every supported runtime.

> Status: **Phase M0 shipped (2026-05-01) — implementation in progress.** Approved for implementation; not D-005 gated as a whole (additive, low-risk surface needed for diagnostics: `rtp test full`, log analysis, future dashboards). Module-boundary or new-API decisions inside it still follow Rule D-005.

> Why a separate plan: the multi-server proxy plan needs aggregate TPS/MSPT/heap/queue-depth from each backend, but those signals are useful **on every backend RTP runs on, with or without proxy mode enabled**. Authoring them under `MULTI_SERVER_PLAN.md` would couple them to a D-005-gated track and delay general usefulness. This plan ships independently.

---

## Goals

- **Platform-portable metrics SPI.** A single `Metrics` (working name) facade in `rtp-core` that every safety-critical or ops-relevant signal flows through. Concrete sources are platform adapters (`rtp-spigot`, `rtp-paper`, `rtp-folia`, `rtp-fabric`); core never imports platform types.
- **Snapshot, not stream.** Each query returns a current value. Callers (telemetry publisher in the multi-server plan, `rtp test full`, `MemoryTracker` audits) compute deltas themselves. Keeps the surface trivially mockable.
- **No tick-thread blocking.** Sampling shall be either lock-free (in-memory counters) or run on the platform's async scheduler. S-005 spirit applies.
- **Already-published values reused.** Where the platform exposes a metric (Paper `Bukkit.getTPS()`, Folia per-region tick samplers), wrap it. Do not re-implement what the platform offers.

## Non-Goals (this plan)

- Network distribution / proxy publication of metrics — that lives in [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md). This plan only provides the *source*.
- Long-term metrics retention / time-series storage. The metrics SPI returns *current* snapshots; persistence is out of scope.
- Prometheus / OpenTelemetry exporters. Possible Phase 3+ extension; not v1.

---

## Metric Catalogue (v1)

| Metric | Type | Source per platform |
|--------|------|---------------------|
| `tps1m` / `tps5m` / `tps15m` | rolling double | Paper/Folia: `Bukkit.getTPS()`. Spigot 1.20.1: local sampler (see *Spigot TPS Fallback*). Fabric: server tick callback diff. |
| `mspt` (mean ms/tick over the last sample window) | double | Paper: `Bukkit.getAverageTickTime()`. Spigot: derived from local sampler. Folia: see *Folia Aggregation*. Fabric: tick-end callback diff. |
| `tickBudgetUtilisation` | `mspt / 50.0` | Derived; published for convenience. |
| `playerCount` | int | Each platform's player-list API. |
| `softCap` | int | Server config (max-players, or RTP-config override). |
| `heapUsedMb` / `heapMaxMb` | int | `ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()` — platform-portable. |
| `queueDepth` | int | `RegionQueueManager.playerQueue.size()` summed across regions. |
| `pendingTeleports` | int | `MemoryTracker` count of in-flight `TeleportPipelineTask`s. |
| `avgPipelineMs` | rolling double | `TeleportPipelineTask` completion-time histogram (added in this plan). |
| `chunkLoadBacklog` | int | Count of incomplete chunk-load `CompletableFuture`s tracked through the platform's async chunk API. |
| `memoryTrackerEntries` | int | `MemoryTracker.size()`. |
| `databaseLatencyMs` | int | Last write/read RTT against the configured `AbstractSQLDatabaseAccessor`. |

All values are accessible via a single read-only call: `Metrics.snapshot()` returns a `MetricsSnapshot` immutable record. Individual getters exist for callers that want a single field.

---

## Spigot TPS Fallback (1.20.1+ minimum)

Raw Spigot's `Bukkit.Server` does **not** expose `getTPS()` on the lowest-supported MC version (1.20.1). It is a Paper-only addition. The `rtp-spigot` adapter therefore ships a local sampler:

- Schedule a 1-tick-period repeating task on `RTP.scheduler` that records `System.nanoTime()` per fire.
- Maintain three exponential moving averages over the inter-fire deltas (1m / 5m / 15m windows).
- Convert to TPS as `1e9 / movingAverageNanos`, clamped to `[0, 20]`.
- Report MSPT directly from the moving average.

When the runtime is detected as Paper-or-derived (Paper, Purpur, Folia), prefer the native call and skip the sampler. Detection: reflective probe for `Bukkit.getTPS` at startup.

---

## Folia Aggregation (per-region → server-level)

Folia tick-rates are **per region**. The metrics SPI must collapse per-region samples into a single server-level signal for callers (telemetry publisher, `rtp test full`).

Decision (deferred from `MULTI_SERVER_PLAN.md` 2026-05-01):

- **Player-count-weighted aggregation is OUT.** Population is not a reliable proxy for region cost (an AFK farm with 30 players may be cheaper than a single-player chunk-gen frontier).
- **Two candidates remain**: `max` (single-region MSPT) and `mean` (arithmetic mean across regions).
- **Recommendation**: ship **`max`** as the default for `mspt` and `tickBudgetUtilisation` so a single struggling region surfaces; ship **`mean`** for `tps*` so transient single-region dips don't drag the headline number. Both behaviours are operator-overridable in `config.yml` under `metrics.folia.aggregation.{tps, mspt}`.
- Per-region detail remains accessible via `MetricsSnapshot.foliaRegions()` for ops who want to drill in (off by default to keep snapshots small).

This decision is internal to the metrics plan and does not need ADR-025 ratification. If multi-server work later requires a different aggregation, the proxy-side selector can apply its own transformation over the published per-region detail.

---

## Module Placement

```
rtp-api/                     -- (no metrics types; keeps API stable)
rtp-core/
  └── metrics/
      ├── Metrics              (facade, reads from RTP.serverAccessor + local samplers)
      ├── MetricsSnapshot      (immutable record)
      ├── PipelineHistogram    (rolling stats for avgPipelineMs)
      └── HeapSampler          (java.lang.management wrapper)
rtp-spigot/    -- SpigotTpsSampler (local 1-tick averager) + AbstractServerAccessor extension
rtp-paper/     -- PaperMetricsBinding (delegates to Bukkit.getTPS / getAverageTickTime)
rtp-folia/     -- FoliaMetricsBinding (per-region samplers + configurable aggregation)
rtp-fabric/    -- FabricMetricsBinding (server tick callbacks)
```

`MetricsSnapshot` lives in `rtp-core` so addons and the multi-server plan's `BackendStatePublisher` can both depend on it without dragging platform types.

`RTP.serverAccessor` gains a small additive surface (`getMetricsBinding()` returning the platform-specific source). This is the same "additive `serverAccessor` extension" pattern flagged in `MULTI_SERVER_PLAN.md > Module Placement`.

---

## Phased Roadmap

### Phase M0 — SPI shape *(docs only; light D-005)*

- [x] Define `Metrics`, `MetricsSnapshot`, `PipelineHistogram` in `rtp-core` — landed 2026-05-01 in `rtp-core/.../common/metrics/` (`Metrics`, `MetricsBinding`, `MetricsSnapshot`, `PipelineHistogram`, `HeapSampler`, `CoreMetrics`); covered by `MetricsSnapshotTest`, `PipelineHistogramTest`, `HeapSamplerTest`, `CoreMetricsTest`.
- [ ] Confirm field catalogue against `rtp test full`'s current output so the new SPI is a strict superset.
- [x] Add this plan to `INDEX.md` / `MAP.md` / `AGENTS.md` task router.

### Phase M1 — Core + Paper + Spigot fallback

- [x] Implement `Metrics` and `MetricsSnapshot` in `rtp-core`. *(landed in M0; carried forward.)*
- [x] `PipelineHistogram` integrated into `TeleportPipelineTask` completion path (additive; no behaviour change) — wired into `runCleanup` (single-shot via `pipelineHistogramRecorded` guard) on 2026-05-01; covered by `TeleportPipelineTaskPhaseTest#runCleanup_records_one_sample_into_pipeline_histogram` and `…_is_idempotent_for_pipeline_histogram`. Process-wide aggregator exposed as `RTP.metrics` (`CoreMetrics` instance, NOOP binding by default).
- [x] `PaperMetricsBinding` wraps `Bukkit.getTPS()` / `getAverageTickTime()` — landed 2026-05-01 in `rtp-paper/rtp-paper-common/.../paper/metrics/PaperMetricsBinding.java`; supplier-injection seam allows non-MockBukkit testing; Bukkit-bound production constructor returns documented sentinels (`MetricsSnapshot.UNSAMPLED` / `0`) when `Bukkit.getServer()` is null or the call throws. Covered by `PaperMetricsBindingTest` (3/3 green: supplier delegation, NaN-sentinel propagation, inherited-default preservation for the un-overridden `softCap`/`chunkLoadBacklog`/`databaseLatencyMs` fields). Plugin-enable wiring (`RTP.metrics.setBinding(new PaperMetricsBinding())`) deferred to the platform-bring-up slice that also wires `rtp test full` output.
- [x] `SpigotTpsSampler` for Spigot 1.20.1 fallback — landed 2026-05-01 in `rtp-spigot/rtp-spigot-common/.../spigot/metrics/SpigotTpsSampler.java`. Implements `MetricsBinding`; `tick()` is invoked once per server tick from a 1-tick repeating task on `RTP.scheduler` and feeds three EMAs (1m / 5m / 15m windows in ticks at nominal 20 TPS). TPS is clamped to `[0.0, 20.0]`; MSPT is the raw 1m EMA in ms; pre-tick / single-tick / non-progressing-clock paths return `MetricsSnapshot.UNSAMPLED`. Covered by `SpigotTpsSamplerTest` (7/7 green: pre-tick sentinel, seed-only first call, steady-50ms→20 TPS / 50 MSPT, slow-100ms→10 TPS / 100 MSPT, faster-than-20-clamp, non-progressing clock, 1m-vs-15m EMA divergence). Plugin-enable wiring (instantiate, `RTP.metrics.setBinding(sampler)`, `RTP.scheduler.runTaskTimer(sampler::tick, 1L, 1L)`) deferred to the same platform-bring-up slice as `PaperMetricsBinding` and `rtp test full`.
- [x] `HeapSampler` via `ManagementFactory`. *(landed in M0; carried forward.)*
- [ ] Wire `rtp test full` to print `MetricsSnapshot.toString()` (replace the ad-hoc dump).
- [x] Unit tests for the histogram, sampler, and snapshot immutability — `PipelineHistogramTest`, `HeapSamplerTest`, `MetricsSnapshotTest`, `CoreMetricsTest` (15/15 green) plus `TeleportPipelineTaskPhaseTest` histogram-wiring cases (22/22 green).

### Phase M2 — Folia + Fabric

- [ ] `FoliaMetricsBinding` with the `max` / `mean` defaults from *Folia Aggregation* and the `metrics.folia.aggregation.*` config keys.
- [ ] `FabricMetricsBinding` using the server tick callback chain wired in Step E2 of `MULTI_PLATFORM_PLAN.md`.
- [ ] Per-platform smoke tests confirming `MetricsSnapshot` returns sane values on each runtime.

### Phase M3 — Multi-server consumer (cross-plan)

- [ ] `BackendStatePublisher` in `MULTI_SERVER_PLAN.md > Backend Telemetry Publication` consumes `Metrics.snapshot()` directly. No new metric code; pure consumer.
- [ ] This phase is gated by the multi-server plan's Phase 2.

### Phase M4 — Optional exporters *(stretch)*

- [ ] Investigate Prometheus / OpenTelemetry exporters as an opt-in `addons/` integration. Not v1.

---

## Acceptance Contract

- **Zero behaviour change when ignored.** Existing code paths that don't call `Metrics` see no diff. `rtp test full` is the only required consumer in Phase M1.
- **No tick-thread blocking.** All samplers must be either O(1) local-counter reads or scheduled async.
- **No platform imports in `rtp-core/metrics/`.** Enforced by the existing ArchUnit guard on `rtp-core` package boundaries.
- **Lowest supported Spigot is 1.20.1** — the Spigot fallback sampler must work without any post-1.20.1 API.

---

## Open Items / Follow-Ups

- **`avgPipelineMs` window length and reset semantics** — straw-man: 256-sample ring buffer, never resets. Confirm during M1 review.
- **`databaseLatencyMs` measurement cadence** — sample on every write or only on a dedicated probe? Sampling on every write conflates pool-saturation with latency. Decide during M1.
- **Folia per-region detail opt-in key** — straw-man `metrics.folia.includeRegions: false`. Confirm during M2.
- **Memory cost of `MetricsSnapshot`** — must stay small enough to be cheap to publish at 1Hz under proxy mode (multi-server consumer constraint).

---

## `/rtp info` Surface

`/rtp info` (canonical: `InfoCmd` in `rtp-core/.../commands/info/`, permission node `rtp.info`, wired by every platform's `RTPCmd*` root) is the **operator-facing live health view**. It must surface the same `Metrics.snapshot()` data the bStats integration and the multi-server telemetry publisher consume — one source of truth, three audiences.

> Why include health here: ops working a live incident reach for `/rtp info` first. The previous output focused on configuration (regions, world list); a fleet-wide cache stall or pipeline backlog was invisible until the operator dug into `rtp test full` or the logs. Surfacing the health snapshot inline turns triage from minutes into seconds.

### Output groups (additive — existing fields unchanged)

The existing config-oriented output stays; a new **Health** block appends below it. All values pull from a single cached `MetricsSnapshot` so the command is O(1) regardless of how many regions or queues exist.

#### Health — server (always shown when `rtp.info` is granted)

- `tps`: `tps1m / tps5m / tps15m` (rounded to 0.01).
- `mspt`: current rolling mean (rounded to 0.1 ms) + `tickBudgetUtilisation` as a percentage.
- `heap`: `heapUsedMb / heapMaxMb` + percent used.
- `memoryTracker`: `memoryTrackerEntries` (highlighted if above a configurable threshold).
- `databaseLatency`: `databaseLatencyMs` (last RTT against the configured `AbstractSQLDatabaseAccessor`).

#### Health — pipeline (always shown)

- `queueDepth`: total `RegionQueueManager.playerQueue` size summed across regions.
- `pendingTeleports`: count of in-flight `TeleportPipelineTask`s.
- `avgPipelineMs`: rolling mean over the histogram window.
- `chunkLoadBacklog`: incomplete async chunk-load futures.

#### Health — cache (per region; collapsed/expanded per *Verbosity* below)

For each configured region, one row showing:

- `keptLocations` (L1) fill: `<used>/<cap>` (e.g. `12/16`).
- `unkeptLocations` (L2) fill: same.
- `loginLocations` fill, when present (ADR-023 reserve).
- A short status flag: `OK` / `LOW` (L1 below 25% of cap) / `EMPTY` (L1 = 0 *and* L2 = 0).

#### Health — network (only when `network.enabled: true`)

- `networkMode`: `proxy-backend` or `proxy`.
- `transport`: configured transport type + last-success timestamp / age (drives the operator's "is the proxy talking to me?" question).
- `recentReservations`: bucketed counts of reservation tokens issued in the last minute (`PENDING` / `CLAIMED` / `CONSUMED` / `EXPIRED`).
- `networkWaitQueue`: depth of the cross-server wait queue (per `MULTI_SERVER_PLAN.md > Network Wait Queue`).
- `lastSelectorPick`: serverId of the most recent backend chosen (or `self` if this host is a backend).
- Stale-backend warning: list of `serverIds` whose `last_seen_epoch_ms` exceeds `loadBalancer.staleAfterMs`.

The network block is silently omitted when `network.enabled: false` so single-server operators see no proxy noise.

### Verbosity

`/rtp info` defaults to a compact view. Two flags expand it:

- `/rtp info verbose` (or `-v`): expand the per-region cache table and show per-platform extras (Folia per-region TPS table when on Folia; raw sampler readings on Spigot 1.20.1).
- `/rtp info json` (machine-readable): emits the full `MetricsSnapshot` as JSON for piping into log aggregators or monitoring scripts. Implementation note: serialise the existing immutable record; do not invent a parallel schema.

Permission: both flags reuse `rtp.info` (existing node — no new permissions needed for v1). A more restrictive `rtp.info.network` may be split out later if ops want to hide proxy details from sub-admins; deferred until requested.

### Health colour coding (chat output)

When the platform supports legacy colour codes (or Adventure on Paper-derived runtimes via `SendMessage`), values render with thresholds:

- Green: within nominal band (`tps1m ≥ 19.5`, `mspt ≤ 30`, L1 fill ≥ 50%, etc.).
- Yellow: degraded (`tps1m ≥ 18`, `mspt ≤ 45`, L1 fill ≥ 25%, network-transport last-success age ≥ 5s).
- Red: alert (anything below the yellow band, plus any `EMPTY` cache row, plus any stale backend in network mode).

Thresholds live in `messages.yml` so operators can tune them without recompiling (REQ-RTP-F-013 already covers configurability of user-facing strings; this extends the same pattern to the colour-band thresholds, which are message-formatting concerns rather than algorithm tuning).

### Test surface (additive to `InfoCmdTest`)

The existing `InfoCmdTest` covers structural behaviour (subcommand permission, parameter lookup, invocation). The health additions need:

- A unit test asserting `/rtp info` calls `Metrics.snapshot()` exactly once per invocation (no N×region calls).
- A unit test asserting the network block is *omitted* when `network.enabled: false` (REQ-RTP-NET-005 surface).
- A snapshot test of the JSON output shape so ops scripts can rely on key stability across releases.

### Phasing

- **Phase M1**: ship the *server* and *pipeline* health groups (drives off the same M1 work).
- **Phase M2**: per-region cache table + Folia per-region detail (depends on `FoliaMetricsBinding` from M2).
- **Phase M3**: network block (depends on `MULTI_SERVER_PLAN.md` Phase 2 reservation-token table being live).
- **Out of band**: `json` output and `messages.yml` threshold tuning can land in any phase that ships the underlying group.

### Cross-references

- Canonical command: `InfoCmd` in `rtp-core/.../commands/info/`.
- Wiring: each platform's `RTPCmd*Root` (`RTPCmdBukkit`, `RTPCmdFabricRoot`, future Velocity / Bungee adapters).
- Tests: `InfoCmdTest` in `rtp-core/src/test/...`; multi-server-aware tests under whichever module owns `network.yml` parsing.

---

## bStats Integration

bStats already collects standard host facts (server software, MC version, online-mode, players, country, JVM). RTP's value-add is **plugin-specific configuration adoption and runtime health signals** that are interesting in aggregate without identifying any individual server or player.

> Constraints: bStats charts must be **anonymous and aggregable**, default submission cadence is roughly half-hourly (don't compute heavy state inside the lambda; cache last-known values), and operators can opt out at any time. Never include `serverId`, IP, hostnames, or anything proxy-mode-specific that could fingerprint a network. The `Metrics` SPI defined above is the single source of truth — bStats lambdas read `Metrics.snapshot()` (or its cached field), they do not introduce new sampling.

### Recommended chart catalogue

Mapped to bStats v3 chart types (`SimplePie`, `AdvancedPie`, `DrilldownPie`, `SingleLineChart`, `MultiLineChart`, `SimpleBarChart`, `AdvancedBarChart`).

#### Configuration adoption (categorical, low-cardinality)

| Chart ID (suggested) | Chart type | Value |
|----------------------|-----------|-------|
| `platform` | `SimplePie` | `spigot` / `paper` / `folia` / `fabric` (already provided by host stats; explicit chart confirms detection in our adapter) |
| `assembly_variant` | `SimplePie` | `full` / `lite` (per [ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md)) |
| `network_mode` | `SimplePie` | `disabled` / `single-server` / `proxy-backend` / `proxy` (`MULTI_SERVER_PLAN.md` adoption signal) |
| `transport_type` | `SimplePie` | `redis` / `postgres` / `sql` / `plugin-message` / `none` (only when `network.enabled`) |
| `database_backend` | `SimplePie` | `h2` / `sqlite` / `mysql` / `postgresql` (single-server persistence) |
| `default_strategy_curves` | `AdvancedPie` | per-curve usage count over the configured `loadBalancer.metrics` block (`linear`, `exponential`, `sigmoid`, …) |
| `region_shapes_in_use` | `AdvancedPie` | counts of `Square` / `Circle` / `Rectangle` shapes across configured regions |
| `safety_features_enabled` | `AdvancedPie` | counts of `claim-integration`, `anvil-prefilter`, `login-reserve-cache`, etc. |
| `addons_loaded` | `AdvancedPie` | per-addon presence (only addons that opt in to be advertised) |
| `lite_features_dropped` | `AdvancedPie` | for `assembly_variant=lite`, which subsystems are absent (per ADR-024) |

#### Runtime health (numeric; low-frequency snapshot is fine)

| Chart ID | Chart type | Value |
|----------|-----------|-------|
| `region_count` | `SingleLineChart` | total configured regions |
| `cache_pool_health` | `MultiLineChart` | `keptLocations` (L1) and `unkeptLocations` (L2) average fill ratio across regions |
| `tps_buckets` | `AdvancedPie` | bucketised: `<10` / `10-15` / `15-19` / `19-20+` over the last submission window (preserves shape without leaking exact values) |
| `mspt_buckets` | `AdvancedPie` | bucketised: `<25` / `25-50` / `50-100` / `100+` ms |
| `pipeline_latency_buckets` | `AdvancedPie` | bucketised `avgPipelineMs`: `<100` / `100-500` / `500-2000` / `2000+` |
| `memory_tracker_pressure` | `AdvancedPie` | bucketised `memoryTrackerEntries`: `<10` / `10-50` / `50-200` / `200+` |
| `chunk_load_backlog_pressure` | `AdvancedPie` | bucketised `chunkLoadBacklog`: `0` / `1-5` / `6-20` / `21+` |
| `s005_violations_recent` | `SingleLineChart` | count of S-005 attribution events surfaced via `ReqRtpS004NullChunkAttributionTest`-style runtime guards in the last submission window. **Rare but high-signal**: a sustained nonzero count across the bStats fleet flags a regression we'd want to know about. |

#### Feature-shape rollups (drilldowns)

| Chart ID | Chart type | Value |
|----------|-----------|-------|
| `selection_strategy_shape` | `DrilldownPie` | top-level: `single-server` / `proxy`; drill: which `loadBalancer.metrics` weights are non-zero |
| `region_topology` | `DrilldownPie` | top-level: shape kind; drill: bounded vs unbounded, biome-filtered vs not |
| `trigger_sources` | `DrilldownPie` | top-level: `command` / `join` / `event`; drill: configuration variants |

#### What to deliberately **omit**

- **Anything keyed by `serverId`** — would enable network fingerprinting.
- **Player counts as a numeric chart** — bStats already collects host player counts; duplicating them adds nothing and risks correlated identification.
- **Region names, world keys, paths** — operator-chosen identifiers, frequently include server names.
- **Reservation token totals or wait-queue depths** — proxy-only and small enough that combined with location data they could fingerprint a network. If we ever want this, sample bucket counts (`<10` / `10-50` / `>50`), never raw counts.
- **Database connection strings, credentials, or paths** — obvious, called out for completeness.

#### Implementation notes

- All bStats lambdas shall read pre-cached values from `Metrics.snapshot()` rather than invoking platform calls inline. Bucketisation happens once per snapshot, not once per chart fetch.
- Submission cadence respects bStats defaults; do not add custom timers.
- Chart IDs registered on bStats.org should be locked into a small constants class (`BStatsChartIds`) so a typo in one place doesn't silently break a chart.
- The bStats integration ships in `rtp-plugin` (Bukkit family) and `rtp-fabric` (when bStats-Fabric is wired in Phase M2). Velocity / BungeeCord proxies get a separate, smaller bStats chart set in Phase M3 once the proxy adapter exists — the proxy chart set deliberately omits backend-shape charts to avoid fingerprinting backend pools.

#### Phasing

- **Phase M1**: ship the *Configuration adoption* group (low effort, immediate insight).
- **Phase M2**: add the *Runtime health* group once `Metrics.snapshot()` is live on Folia/Fabric.
- **Phase M3**: add the *Feature-shape rollups* and the proxy-side chart set.

---

## Sufficiency Audit (2026-05-01)

Reviewed for implementer-sufficiency against `AGENTS.md`, `RULES.md`, and existing S-001…S-007 prohibitions. Findings:

- **Sufficient as-shipped**: SPI shape (records, package layout), Spigot-fallback algorithm, Folia aggregation defaults, `/rtp info` output groups, bStats catalogue with privacy guardrails, three-consumer model (publisher / bStats / `InfoCmd`).
- **Filled in this pass**: explicit thread-context for each sampler (publisher async; tick-counted samplers via `RTP.scheduler.runTaskTimer`; `Metrics.snapshot()` callable from any thread). See *Acceptance Contract* (existing) and *Spigot TPS Fallback* — both already enumerate thread requirements.
- **Carry-over open items** (already in *Open Items / Follow-Ups*): `avgPipelineMs` window length, `databaseLatencyMs` cadence, Folia per-region detail opt-in default, `MetricsSnapshot` memory cost. None block M1 implementation; each is annotated in the checklist file.
- **Deferred to consumer plans**: anonymisation rules for the bStats catalogue (covered in this plan's *bStats Integration > Constraints*); colour-band thresholds for `/rtp info` (covered in `messages.yml` per REQ-RTP-F-013).

**One unticked M0 box remains**: confirm the M1 metric catalogue is a strict superset of `rtp test full`'s current console output. This is mechanical and is the recommended first checklist row to execute (Section A row A1 in `docs/dev/scratch/CHECKLIST-metrics-and-multiserver.md`).

---

## Cross-References

- [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md) — primary downstream consumer (telemetry publisher).
- [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md) — Fabric Step E2 tick-callback hook is the basis for `FabricMetricsBinding`.
- [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) — any platform-specific surprise from sampler implementation lands here.
- [`AGENTS.md > Domain Analogies & Aliases`](../../.junie/AGENTS.md) — informal terms (`mspt`, `tps`, `the snapshot`) route here.

*Self-update note*: this plan describes the metrics SPI shape only. Implementation lore goes to `LESSONS_LEARNED.md`; new `Metrics` fields land via additive PRs without amending this plan unless the SPI shape changes.
