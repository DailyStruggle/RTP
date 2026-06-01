# RTP Metrics Plan

This document is the canonical plan for **runtime metrics** in RTP — the platform-portable abstractions for sampling tick-rate, MSPT, heap, queue depths, pipeline latencies, and related health signals on every supported runtime.

> Status: **Phase M0 shipped (2026-05-01) — implementation in progress.** Approved for implementation; not D-005 gated as a whole (additive, low-risk surface needed for diagnostics: `rtp test full`, log analysis, future dashboards). Module-boundary or new-API decisions inside it still follow Rule D-005.

> Why a separate plan: the multi-server proxy plan needs aggregate TPS/MSPT/heap/queue-depth from each backend, but those signals are useful **on every backend RTP runs on, with or without proxy mode enabled**. Authoring them under `MULTI_SERVER_PLAN.md` would couple them to a D-005-gated track and delay general usefulness. This plan ships independently.

---

## Goals

- **Platform-portable metrics SPI.** A single `Metrics` (working name) facade in `rtp-core` that every safety-critical or ops-relevant signal flows through. Concrete sources are platform adapters (`rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`); core never imports platform types.
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
| `pipelineMsP50` / `pipelineMsP75` / `pipelineMsP90` / `pipelineMsP95` / `pipelineMsP99` | double (ms) | Percentiles of the same `PipelineHistogram` window, computed on demand by sorting a point-in-time copy of the bounded 256-sample ring (no new sampling; no change to the wait-free write path). `NaN` until the first sample lands. See [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md). |
| `slowPipelineCount` | long | Cumulative count (since process start) of **immediate/unqueued** `TeleportPipelineTask` completions whose latency exceeded `slowPipelineThresholdMs`. Queued (at-rate) teleports are excluded because their elapsed window includes queue-wait time and would false-positive (see ADR-053 §2a). Incremented once per task at the existing single-shot `runCleanup` recorder site, alongside a `WARN` log. `0` when the audit is disabled. See [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md) and REQ-RTP-OBS-005. |
| `slowPipelineThresholdMs` | long (ms) | The resolved slow-teleport audit threshold from `performance.yml > slowPipelineThresholdMs` (default `5000`; `<= 0` disables the audit). Published in the snapshot so the `/rtp info performance` readout can render `slowPipelineCount` against the threshold that produced it. |
| `queueGrowthWarnCount` | long | Cumulative count (since process start) of edge-triggered queue-backpressure warnings: incremented once each time the summed `queueDepth` transitions from below `queueGrowthWarnThreshold` to at-or-above it (re-armed only after it falls back below). Paired with a `WARN` log. `0` when the audit is disabled. Distinct from `slowPipelineCount`: this is the queued-path signal where per-teleport latency is dominated by at-rate queue wait. See [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md) §2b and REQ-RTP-OBS-006. |
| `queueGrowthWarnThreshold` | int (players) | The resolved queue-growth audit threshold from `performance.yml > queueGrowthWarnThreshold` (default `0` = disabled/opt-in; a positive value arms the edge-triggered audit). Published in the snapshot so the `/rtp info performance` readout can render `queueGrowthWarnCount` against the threshold that produced it. |
| `chunkLoadBacklog` | int | Count of incomplete chunk-load `CompletableFuture`s tracked through the platform's async chunk API. |
| `memoryTrackerEntries` | int | `MemoryTracker.size()`. |
| `databaseLatencyMs` | int | Last write/read RTT against the configured `AbstractSQLDatabaseAccessor`. |
| `commandRatePerMin` | rolling double | `/rtp` (and aliased) command-entry counter sampled per-second in `BaseRTPCmd`, exposed as 1m / 5m / 15m EMAs scaled to per-minute units (mirrors the TPS shape so operators can read the two side-by-side). |
| `refillRatePerMin` | rolling double | Net rate at which `keptLocations` (L1) + `unkeptLocations` (L2) gain entries across all regions, sampled by `RegionQueueManager` on the same async cadence. EMA over the same 1m / 5m / 15m windows. |
| `commandOverflowRatePerMin` | rolling double | `max(0.0, commandRatePerMin - refillRatePerMin)` — by how much sustained command demand exceeds the cache's ability to refill. Drives the *Command-Demand vs Refill Tracking* admin readout below and is the input signal for any future auto-adjustment of pre-gen / scan budgets. |
| `commandOverflowEvents` | long | Cumulative count of sample windows in which `commandRatePerMin > refillRatePerMin` for at least `metrics.demand.overflowMinSamples` consecutive samples (default 3). Surfaces sustained pressure as a single integer for alerting. |
| `tickStressEvents` | long | Cumulative count of sample windows in which `mspt` exceeded `metrics.demand.stressMsptThreshold` (default 45 ms) on the same async cadence. Joined with `commandOverflowEvents` to attribute pressure (server-bound vs cache-bound). |
| `biomeRerolls` | `Map<String, Long>` (destination biome name → cumulative user-reroll count) | Counted at command entry in `BaseRTPCmd` (and platform overrides) by inspecting the invoking player's prior `TeleportData` in `RTP.priorTeleportData` (extended with a `destinationBiome` field). If the player re-issues `/rtp` within a configurable window after their last successful landing, the destination biome of the *previous* teleport is incremented. Keyed by biome to surface which **outcomes** drive players to re-roll, informing server-design decisions about biome allow/deny tuning. See *Biome Reroll Tracking* below. |
| `sustainableRatePerMin` | rolling double | Trailing p95 of `refillRatePerMin` over samples in which `tickStressEvents` did **not** increment. Computed in `CoreMetrics` alongside the existing demand sampler. The denominator a proxy needs for capacity headroom; cannot be derived externally because it conditions one signal on another's *non*-increment within a single host's history. See *Cross-Server Load-Balancing Inputs* below. |
| `cacheServeRateLast60s` | rolling double | Successful `keptLocations.poll()` per second over the last 60 s, summed across regions. Counted at the *poll* sites in `LockFreeLocationBuffer` / `RegionQueueManager` (distinct from the existing `refillRatePerMin` *add*-side counter). Detects "cache full but nobody served" — invisible from snapshot rates alone. |
| `coldServeRatio` | double `[0.0, 1.0]` | `unkeptServes / (keptServes + unkeptServes)` over the same 60 s window. High value ⇒ host is paying L2 chunk-reload cost on most teleports. Hot-vs-cold split is internal to `RegionQueueManager`; the proxy cannot reconstruct it from `queueDepth` or `refillRatePerMin`. |
| `pregenSaturation` | double `[0.0, 1.0]` | `min(1.0, scanTaskBudgetUsed / scanTaskBudget)` sampled from the active `ScanTask` budgeting state. Tells the LB whether a host is coasting (room to refill faster) or already flat-out — a backend-internal control surface, not externally observable. |
| `pipelineFailureRate` | rolling double | `pipelineFailures / pipelineCompletions` over the last sample window. `pipelineFailures` is the sum across `PregenState.failMap` increments since the previous sample; `pipelineCompletions` is incremented on the existing `TeleportPipelineTask.runCleanup` success path (next to `pipelineHistogram.record(...)`). |
| `pipelineFailureBreakdown` | fixed-shape `Map<FailKind, Long>` | Cumulative `PregenState.failMap` rollup, keyed by a small fixed enum (`biome`, `prefilterBiome`, `unsafe`, `nullChunk`, `other`) — not raw `FailTypes` to keep the publish shape stable across versions. Lets the LB deprioritise hosts failing on `nullChunk` (S-005 spirit) vs. hosts failing only on `biome` (operator filter config). |
| `loginReserveExhaustion` | long | Cumulative count of `firstjoin` / `join`-triggered RTPs that fell through an empty `loginLocations` (ADR-023) and had to use the general queue. Incremented at the fall-through branch in the login-reserve consumer; distinct from `queueDepth`, which lumps all waiters together. |
| `gcPauseRecent` | int (ms) | Largest GC pause observed in the last sample window. Sourced from `java.lang.management.GarbageCollectorMXBean` deltas (cumulative `getCollectionTime()` per collector) on the same 1 s async cadence as the demand sampler. Distinguishes GC stall (recovers fast) from chunk-I/O stall (persistent) — different LB routing implications even when both produce identical TPS dips. |
| `effectiveQueueWaitMs` | rolling double | EMA of (player-enqueue → pipeline-start) latency observed in `RegionQueueManager`. Stamp time at `playerQueue` enqueue; sample at the dequeue site that hands off to `TeleportPipelineTask`. The proxy proxy-estimate `queueDepth × avgPipelineMs` is wrong on heterogeneous regions; only the host has the per-enqueue timestamps. |
| `regionQueueStatus` | fixed-shape `Map<RegionKey, RegionQueueRow>` | Per-region rollup of queue / cache fill so a cross-server selector can route to a *specific* region rather than only to the host. Each row carries `playerQueueDepth`, `keptFill` / `keptCap`, `unkeptFill` / `unkeptCap`, `loginFill` / `loginCap` (nullable when no login reserve is configured), and a derived `status` enum (`OK` / `LOW` / `EMPTY` / `SATURATED`). `RegionKey` is the existing region identifier (world key + region name); see *Per-Region Queue Status* below for cardinality bounds and the privacy posture in network mode. The summed `queueDepth` and host-level `cacheServeRateLast60s` hide which region is hot vs. starved; this row is the per-region detail an LB cannot reconstruct from host-level scalars. |
| `tickCpuBudgetMsAnalytical` | fixed-shape `Map<TickConsumer, Double>` | Analytical worst-case CPU time (ms) RTP can consume on a single region/main tick, computed as `Σ (cap × wcetPerIteration)` for each count-bounded per-tick consumer (region pulse, pipeline stages, L3 anvil verification, active-GC sweep, periodic replenishment). Computed at startup from configuration (caps) and a small set of microbenchmarked per-iteration WCETs persisted as build-time constants; recomputed on `/rtp reload`. Constant for a given configuration — published as a *contract*, not a measurement. See *Real-Time Per-Tick CPU Budget* below. |
| `tickCpuMsP99` / `tickCpuMsP999` | rolling double | Empirical p99 / p99.9 of the same per-tick CPU time, sampled per tick from `System.nanoTime()` deltas around each tracked consumer's per-tick entry/exit and aggregated into a wait-free reservoir over a configurable window (straw-man 60 s at 20 Hz = 1200 samples). The realistic expectation operators see, alongside the analytical contract. |
| `tickCpuOvershoots` | long | Cumulative count of ticks in which the *measured* per-tick total exceeded `tickCpuBudgetMsAnalytical`. Should be zero; a non-zero count is a contract regression and is surfaced as red in `/rtp info` (S-005-spirit alarm — count-bound caps are no longer holding). |

All values are accessible via a single read-only call: `Metrics.snapshot()` returns a `MetricsSnapshot` immutable record. Individual getters exist for callers that want a single field.

---

## Biome Reroll Tracking

The high-signal question this metric answers is **"which destination biomes do players reject by re-rolling?"** — i.e., the biomes whose teleport outcome makes a player immediately type `/rtp` again. This is a *user-driven* satisfaction signal, distinct from the internal location-selection bounces tracked by `LocationGenerator.FailTypes.biome` / `FailTypes.prefilterBiome` (those remain in `PregenState.failMap` for pipeline diagnostics and are **not** what this metric measures).

**Data flow:**

No new per-player map is introduced. The existing `RTP.priorTeleportData: ConcurrentHashMap<UUID, TeleportData>` already retains the most recent completed `TeleportData` per player (populated in `RTPCmd` on success and consumed by `RTPTeleportCancel`). `TeleportData` is extended with a single nullable `String destinationBiome` field, set when the pipeline resolves a final `selectedCoords`. The existing `TeleportData.time` field (command-initiation epoch ms) is reused as the window anchor — no separate timestamp is added.

1. When a `TeleportPipelineTask` completes successfully, the resolved destination biome name is written to `teleportData.destinationBiome` before the task's existing `priorTeleportData.put(uuid, data)` call in `RTPCmd`. No new storage; the field rides along with the record that is already kept.
2. On every `/rtp` command entry (canonical: `BaseRTPCmd.execute`, plus the Bukkit-family `BukkitBaseRTPCmd` override), before any new selection runs, the metric reads `RTP.priorTeleportData.get(uuid)`. If the entry is non-null, has a non-null `destinationBiome`, and `now - prior.time` is within `metrics.biomeRerolls.windowSeconds` (default 300s), increment `biomeRerolls[prior.destinationBiome]` and `biomeRerollsTotal`.
3. The metric does **not** remove the entry — `priorTeleportData` is owned by the teleport-cancel / disconnect-mid-flight machinery and must not be mutated from the metrics path. To prevent double-counting, the metric clears `prior.destinationBiome` (sets it to `null`) after the increment; a subsequent `/rtp` within the same window finds a null biome and counts as a fresh invocation.
4. Successful completion of the new teleport overwrites the same `TeleportData` slot with the new destination biome, restarting the window via the new `time` value.

This instrumentation is additive: the recording step is a single field assignment at task-completion in `TeleportPipelineTask`/`RTPCmd`, and the increment step is a small read-and-clear at command entry. Both ride existing data structures (`TeleportData`, `priorTeleportData`); no new map, no new lifecycle hook, and no new disconnect-cleanup path are needed (the existing `priorTeleportData` lifecycle already covers quit / cancel / mid-flight disconnect).

**Aggregation rules:**

- Keys are the canonical biome identifier as supplied by the platform (matches the biome strings used elsewhere in `failMap`). The metrics SPI does not normalise — if upstream biome naming changes, this metric follows.
- `ConcurrentHashMap` plus per-key `LongAdder` so increments are wait-free; snapshot is a cheap `keySet()` walk with a defensive copy at read time.
- A separate `biomeRerollsTotal` long counter is exposed alongside the map for the common case of "what fraction of `/rtp` invocations are rerolls?" — avoids forcing every consumer to sum the map. Pair with `biomeAcceptancesTotal` (incremented when a player's window expires *without* a reroll, so the ratio `rerolls / (rerolls + acceptances)` is well-defined).
- The reroll window is configurable (`metrics.biomeRerolls.windowSeconds`, default 300). Outside the window the previous destination is discarded and the next `/rtp` counts as a fresh invocation, not a reroll.
- Bounding follows the existing `priorTeleportData` map — no separate sweep is added; the metric is a pure reader of state already maintained for teleport-cancel and disconnect-mid-flight handling.

**Cardinality bound.** Same as the prior internal-retry map: ~60 vanilla biomes, low hundreds for modded servers. Naturally bounded; no cap needed in v1. If a modpack ever pushes cardinality high enough to matter, add `metrics.biomeRerolls.topN` (deferred — see *Open Items*).

**Privacy / bStats.** The raw map is **not** suitable for bStats because biome lists can fingerprint custom biome packs. The bStats chart instead reports the *shape* of the distribution (top-3 biome share, long-tail share, overall reroll rate) — see *bStats Integration > Recommended chart catalogue*. Per-player UUIDs never leave the host: `priorTeleportData` (and the new `destinationBiome` field) is purely an in-memory join key and is never serialised into a snapshot, bStats payload, or proxy telemetry frame.

**Consumers:**

- `/rtp info verbose` — shown as a small "top biomes by reroll" table (top 5 biomes, plus a `(other: N)` rollup, plus the overall reroll rate), gated behind `verbose` to keep the compact view tight.
- `rtp test full` — printed in full as part of the `MetricsSnapshot.toString()` dump.
- `BackendStatePublisher` (Phase M3 cross-plan) — published as a small fixed-shape rollup (`top1Share`, `top3Share`, `rerollRate`, `total`) rather than the raw map, so cross-server traffic stays bounded and the published shape doesn't fingerprint backend biome configuration.

**Distinction from internal retries.** `PregenState.failMap` continues to bucket `FailTypes.biome` and `FailTypes.prefilterBiome` for pipeline diagnostics (`rtp test full` deep dives, anvil-prefilter tuning). Those buckets count *generator* rejections — candidates the system threw away. `biomeRerolls` counts *player* rejections — outcomes the human threw away. Both are useful; they answer different questions and must not be conflated.

Instrumentation cost is minimal: one new field on `TeleportData` (`destinationBiome`), one assignment at successful pipeline exit, and one read-and-clear at command entry. Phase M1 wiring covers those plus the `metrics.biomeRerolls.windowSeconds` config key. No new player-quit hook is needed — the existing `priorTeleportData` lifecycle already handles disconnects.

---

## Command-Demand vs Refill Tracking

Operators repeatedly hit the same diagnostic question during a player rush: *"is RTP slow because the server is struggling, or because the cache cannot refill fast enough for the rate of `/rtp` invocations?"* The metrics here answer that locally, on every backend, with no proxy or external monitoring required. The same signal is the input lever for any future internal auto-adjustment of pre-gen pacing (scan budget, pipeline parallelism, login-reserve sizing) — recording it now keeps the door open without committing to a control loop in v1.

**Why local + admin-visible first.** Auto-tuning a chunk-gen workload from a single rolling sample is risky (chunk-gen cost is bursty and asymmetric — slowing pre-gen during a tick stall makes the stall worse, not better). Surfacing the raw signal first lets operators sanity-check thresholds against real load before any closed-loop adjustment ships. v1 is therefore *measurement only*; auto-adjustment is gated behind a follow-up ADR.

**Data flow:**

1. **Command counter.** `BaseRTPCmd.execute` increments a process-wide `LongAdder` (`commandInvocations`) at entry, before permission / cooldown / parameter checks — counts *intent*, not successful teleports, since intent is what stresses the cache.
2. **Refill counter.** `RegionQueueManager` increments a process-wide `LongAdder` (`cacheRefills`) every time a location is *added* to `keptLocations` or `unkeptLocations` (the existing add path in `LockFreeLocationBuffer`). Removals are not counted — net depletion shows up as an empty L1/L2 in the existing cache health row.
3. **Sampler.** A 1-second async tick on `RTP.scheduler` reads both adders, computes per-second deltas, and feeds three EMAs each (1m / 5m / 15m windows) following the same shape as the Spigot TPS fallback. Snapshots expose the EMAs scaled to per-minute units (`× 60`) so the readout matches operator intuition (`/rtp` per minute is more useful than per second).
4. **Overflow & stress events.** On every sample, if `commandRatePerMin > refillRatePerMin` for `metrics.demand.overflowMinSamples` consecutive samples, increment `commandOverflowEvents`. Independently, if `mspt > metrics.demand.stressMsptThreshold` on the same sample, increment `tickStressEvents`. Joining the two counters lets operators distinguish *cache-bound* pressure (overflow up, stress flat) from *server-bound* pressure (stress up, overflow may follow).
5. **Joint attribution at snapshot read.** `MetricsSnapshot` exposes both rates and both event counters; the `/rtp info` health view (and `rtp test full`) prints the pair on adjacent lines so the comparison is one glance.

**Sampler placement.** The 1-second tick lives in `CoreMetrics` (process-wide singleton already created in M0) and is registered by each platform's bring-up step alongside the existing `Metrics.setBinding(...)` call. It does not run on a region thread (Folia: scheduled via `RTP.scheduler` async path) and reads only `LongAdder` counters, so S-005 / Folia threading rules are trivially satisfied.

**Configuration keys (all under `metrics.demand`):**

- `metrics.demand.overflowMinSamples` — consecutive samples (1s each) before an overflow window counts. Default `3` (3 s sustained).
- `metrics.demand.stressMsptThreshold` — MSPT (ms) above which a sample counts as a tick-stress event. Default `45.0`.
- `metrics.demand.emaWindowsSeconds` — straw-man `[60, 300, 900]`, mirroring TPS windows. Reconfigurable but not expected to change.
- `metrics.demand.autoAdjust.enabled` — reserved for the future control loop, default `false`. Reading and exposing the metric does not require this flag; the flag only gates internal feedback wiring once it ships.

**Admin readout (`/rtp info`).** Adds a small *Demand* sub-block to the existing *Health — pipeline* group:

- `commandRate (1m/5m/15m)` — `/rtp` invocations per minute.
- `refillRate (1m/5m/15m)` — net cache refill per minute (L1 + L2 across all regions).
- `overflow` — current `commandOverflowRatePerMin`, plus the cumulative `commandOverflowEvents` counter.
- `stress` — cumulative `tickStressEvents` counter, alongside the already-shown `mspt`.

Colour coding follows the existing thresholds (green/yellow/red); a non-zero `overflow` paints yellow, and a non-zero `overflow` *combined* with a recent stress event paints red.

**bStats.** Bucketised only — never raw rates (could fingerprint server population). New charts:

- `command_rate_buckets` (`AdvancedPie`) — bucketised `commandRatePerMin`: `<5` / `5-30` / `30-120` / `120+`.
- `refill_overflow_rate` (`AdvancedPie`) — bucketised `commandOverflowRatePerMin / commandRatePerMin`: `0%` / `<10%` / `10-30%` / `30%+`. Captures *what fraction of demand the cache cannot service in real time*, independent of absolute server size.
- `stress_overflow_correlation` (`SimplePie`) — categorical: `neither` / `overflow-only` / `stress-only` / `both` over the submission window. Lets fleet analysis confirm whether RTP-bound pressure is driving tick stress or merely co-occurring with it.

**Future auto-adjustment hook (deferred).** When (and if) auto-adjustment ships, the inputs are already published: `commandOverflowRatePerMin` (the error term), `tickStressEvents` (the safety brake — never tighten pre-gen during tick stress), and `chunkLoadBacklog` / `memoryTrackerEntries` (secondary safety brakes). The control surfaces would be `ScanTask` budget, `TeleportPipelineTask` parallelism cap, and login-reserve top-up cadence. This requires a dedicated ADR (open item below) before any code lands; v1 of this plan only commits to *measuring* the signal.

**Distinction from existing counters.** `queueDepth` measures *current* size; `pendingTeleports` measures *in-flight pipeline* count; this section measures *flow rates* — the time derivatives that determine whether the steady-state queue is shrinking or growing. The three together fully describe the cache's behaviour under load.

---

## Cross-Server Load-Balancing Inputs

A proxy / selector ranking RTP backends against each other does not need every backend-internal counter — most of what an LB would compute (capacity headroom, ETA, slope/trend, eligibility flags, composite scores, hot/cold fill ratios) is **derivable on the proxy** from `MetricsSnapshot` fields the publisher already sends: ratios, simple subtractions, and short snapshot histories on the proxy side handle it. Keeping that math on the proxy avoids hard-coding LB policy into backends and lets operators tune weights centrally.

What the proxy **cannot** reconstruct, even given a stream of snapshots, is signal that depends on (a) per-event timing only the backend observes, (b) one counter conditioned on another counter's *non*-increment, (c) backend-internal control-surface state, or (d) JVM-level facts external to the published fields. The catalogue rows added above (`sustainableRatePerMin`, `cacheServeRateLast60s`, `coldServeRatio`, `pregenSaturation`, `pipelineFailureRate` + `pipelineFailureBreakdown`, `loginReserveExhaustion`, `gcPauseRecent`, `effectiveQueueWaitMs`) are exactly that residue: each is a thing only the backend can compute, and each is a first-class input to the cross-server selector spec'd in `MULTI_SERVER_PLAN.md > Backend Telemetry Publication`.

**Why these and not more.** The previous draft of this section also listed `rtpCapacityHeadroom`, `firstAvailableEtaMs`, `routableCapacityNow`, `loadScore`, `eligibility`, `tps*Trend`, `mspt*Trend`, `heapPressureTrend`, `chunkLoadBacklogTrend`, `playerCountDelta1m`, `keptFillRatio` minima, and `lastSelfReportEpochMs`. All were dropped because the proxy can compute each from existing snapshot fields plus a short snapshot history (or the publish frame's own timestamp). Publishing them on the backend would duplicate logic and freeze LB policy at backend release time; leaving them to the proxy keeps `loadBalancer.metrics` weights as the single tuning surface.

**Sampler placement.** All seven additions ride existing infrastructure:

- `sustainableRatePerMin`: extends the 1 s demand sampler in `CoreMetrics`. Maintains a rolling reservoir (e.g. 900-sample circular buffer for 15 m at 1 Hz) of `refillRatePerMin` values *gated* on "did `tickStressEvents` increment this sample?". p95 is read at snapshot time.
- `cacheServeRateLast60s`, `coldServeRatio`: two `LongAdder`s in `RegionQueueManager`/`LockFreeLocationBuffer` incremented at the L1 and L2 *poll-success* sites; sampled by the same 1 s tick that already reads the refill adders. No new schedulers.
- `pregenSaturation`: read directly from `ScanTask` budget state at snapshot time (no sampler needed; `ScanTask` already maintains `budgetUsed` / `budget` for its own pacing).
- `pipelineFailureRate`, `pipelineFailureBreakdown`: failure side already exists in `PregenState.failMap`. Add a `pipelineCompletions` `LongAdder` next to the existing `PipelineHistogram.record(...)` call in `TeleportPipelineTask.runCleanup`. The breakdown is a pure read of `failMap` mapped through the fixed `FailKind` enum at snapshot time.
- `loginReserveExhaustion`: one `LongAdder` increment at the fall-through branch in the login-reserve consumer (per ADR-023). No sampler.
- `gcPauseRecent`: read on each demand-sampler tick — sum `(now - prev)` deltas of `GarbageCollectorMXBean.getCollectionTime()` across collectors, track the max within the current 1 s window, expose at snapshot time. No allocation per sample.
- `effectiveQueueWaitMs`: enqueue timestamp stored in the existing `playerQueue` entry (the entry already carries a UUID; widen the value type to a small holder). EMA updated at the dequeue site that hands off to `TeleportPipelineTask`.

S-005 / Folia threading: every sampler reads only `LongAdder` / volatile state on the demand-sampler async tick and does no chunk I/O. None of the increment sites add main-thread blocking work.

**Snapshot shape.** All scalars; `pipelineFailureBreakdown` is the only map and is bounded to ~5 fixed `FailKind` keys (chosen so the publish shape stays stable even if `FailTypes` gains internal cases — new `FailTypes` values without a `FailKind` mapping fall under `other`). Total memory footprint added to `MetricsSnapshot` is on the order of 8 doubles + 1 small map; well within the *Memory cost of `MetricsSnapshot`* open item bound.

**Privacy.** Same posture as the rest of the catalogue: scalars and a fixed-shape enum-keyed map. No biome names, region names, world keys, player UUIDs, or `serverId`s involved. bStats publication, if added, must bucketise the rates (matching the `command_rate_buckets` precedent) — never raw values — to avoid fingerprinting backend hardware.

**Phasing.**

- **Phase M1**: `pipelineFailureRate` + `pipelineFailureBreakdown` (rides existing `failMap` + the histogram-completion site already wired this phase); `loginReserveExhaustion` (single increment site); `gcPauseRecent` (one MXBean read per sampler tick).
- **Phase M2**: `cacheServeRateLast60s`, `coldServeRatio`, `effectiveQueueWaitMs` (touch `RegionQueueManager` / `LockFreeLocationBuffer` instrumentation, which is more invasive than the M1 sites).
- **Phase M2/M3**: `sustainableRatePerMin`, `pregenSaturation` — `sustainableRatePerMin` depends on the demand-tracking p95 reservoir landing first; `pregenSaturation` depends on confirming `ScanTask` exposes `budgetUsed` / `budget` cleanly (single-line accessor; no design work).

**Distinction from `commandOverflowRatePerMin`.** Demand tracking (added in the previous section) tells operators *whether the cache is keeping up locally*. The fields here tell a remote selector *which of N backends is best to send the next teleport to*. Operators see the demand block; selectors consume the LB block. Both flow through the same `MetricsSnapshot` so there is no second SPI.

---

## Per-Region Queue Status

The LB-input fields above are **host-level** scalars (one number per backend). For cross-server load balancing they answer *which backend* should serve the next teleport, but they cannot answer *which region on that backend* should serve it. A host with two configured regions — one starved (`keptLocations` empty, long `playerQueue`) and one idle (`keptLocations` full, empty `playerQueue`) — looks healthy on every host-level scalar (averaged refill rate, mean serve ratio, summed queue depth) while one of its regions is in fact unservable. A selector that only sees host-level data either over-routes to the starved region or under-utilises the idle one.

`regionQueueStatus` exposes the per-region detail the host already maintains, in a single fixed-shape map keyed by `RegionKey`. It is the **only** field in the catalogue that a proxy genuinely cannot reconstruct from host-level scalars: averaging across regions on the publisher side throws away exactly the signal the selector needs.

**Row shape (`RegionQueueRow`).** All ints / shorts; no floats, no allocation per sample beyond the row record itself:

- `playerQueueDepth` — `RegionQueueManager.playerQueue` size for this region (the per-region slice of the existing summed `queueDepth`).
- `keptFill` / `keptCap` — current `keptLocations` (L1) size and configured cap.
- `unkeptFill` / `unkeptCap` — current `unkeptLocations` (L2) size and configured cap.
- `loginFill` / `loginCap` — current `loginLocations` size and cap, both null when no login reserve is configured for this region (ADR-023).
- `status` — derived enum:
  - `EMPTY` when `keptFill == 0 && unkeptFill == 0` (next teleport will pay full pipeline cost).
  - `LOW` when `keptFill < keptCap / 4` (about to fall through to L2).
  - `SATURATED` when `playerQueueDepth > 0 && keptFill == 0` (waiters present, no hot cache to drain).
  - `OK` otherwise.
  - Status is computed at snapshot time from the other fields; the enum is published alongside them so consumers don't re-derive it.

**Sampler placement.** No new sampler. `regionQueueStatus` is built lazily inside `Metrics.snapshot()` by walking `RTP.selectionAPI.regions` and reading each `RegionQueueManager`'s already-exposed `LockFreeLocationBuffer.size()` / configured caps / `playerQueue.size()`. All reads are O(1) wait-free; no chunk I/O; no region thread is touched (S-005 / Folia threading rules trivially satisfied). Cost scales linearly with region count, which is bounded — see cardinality below.

**Cardinality bound.** Region count is operator-configured and small in practice (single-digit on most servers, low double-digit on the largest). A hard cap of `metrics.regionQueueStatus.maxRegions` (straw-man `64`) protects the snapshot shape against pathological configs; regions beyond the cap are summarised under a synthetic `__overflow__` row carrying summed `playerQueueDepth` / `keptFill` / `unkeptFill` and the worst per-region `status`. The cap is well above any realistic deployment and is documented as a safety bound, not a tuning surface.

**Privacy / network publication.** `RegionKey` (world key + region name) is operator-chosen and may be sensitive (event servers, staging worlds). Two posture rules:

- **Local consumers** (`/rtp info`, `rtp test full`, in-process bStats lambda): full keys, no redaction.
- **Network publication** (`BackendStatePublisher` per `MULTI_SERVER_PLAN.md`): keys are replaced with stable opaque ids (`region-0`, `region-1`, …) assigned at backend startup and held constant for the process lifetime. The selector only needs *identity* (so subsequent reservation tokens can target the same region), not the human-readable name. The id ↔ name mapping never crosses the wire.
- **bStats**: `regionQueueStatus` is never published raw to bStats. The existing `cache_pool_health` chart already covers the average-fill question without per-region detail; adding per-region rollups would risk fingerprinting unique world layouts.

**Reservation-token interaction.** When `MULTI_SERVER_PLAN.md` Phase 2 reservation tokens land, the token allocator on the backend uses the same per-region read to pick which region's `keptLocations`/`unkeptLocations` to draw the reserved coordinate from. Publishing `regionQueueStatus` lets the proxy hint the desired region in the reservation request (e.g. "reserve from `region-2`") without the proxy ever learning the region's real name.

**Snapshot memory cost.** Each row is ~40 bytes (8 small ints + 1 enum + 1 nullable int pair). At the `64`-region cap, total worst case is ~2.5 KB per snapshot — comfortably within the *Memory cost of `MetricsSnapshot`* open-item bound and well under the 1 Hz publish budget for proxy mode.

**Consumers.**

- `/rtp info` *Health — cache* sub-block (already specified) becomes a direct render of `regionQueueStatus` rather than re-walking regions; one source of truth.
- `BackendStatePublisher` (Phase M3 cross-plan) — published as the redacted-key map described above.
- `rtp test full` — printed as a small table for operator triage.

**Phasing.** Phase M2, alongside the other `RegionQueueManager` instrumentation (`cacheServeRateLast60s`, `coldServeRatio`, `effectiveQueueWaitMs`). The read path is trivial — the M2 cost is the snapshot-shape and `MetricsSnapshotTest` updates plus the redaction layer in `BackendStatePublisher` (which is itself M3 cross-plan, so the redaction code lands when the publisher does).

**Distinction from existing fields.** `queueDepth` is the *summed* `playerQueue` size; `regionQueueStatus[k].playerQueueDepth` is its per-region slice. `cacheServeRateLast60s` is a *host-level rate*; `regionQueueStatus[k].keptFill` is the *current per-region inventory*. The two layers complement each other — rates describe motion, inventories describe state — and a selector needs both.

---

## Real-Time Per-Tick CPU Budget

A defensible firm-real-time claim for `/rtp` requires a **published worst-case CPU budget per tick** for the RTP-owned compute path — not just "we count-bound, trust us." Every per-tick consumer in `rtp-core` is already count-bounded by an explicit configuration value or a format-fixed constant; multiplying each cap by a microbenchmarked per-iteration WCET yields an analytical upper bound that holds under any input. This section defines the methodology, the sampler, and the contract.

> Status: **planned (Phase M2, gated on M1 sampler infrastructure).** This is *measurement and publication only* — no behaviour change, no new caps, no new tuning surface beyond reading the config that already exists. The analytical bound is computed from values RTP already holds; the empirical p99 / p99.9 ride the same 1 s async sampler that *Command-Demand vs Refill Tracking* and *Cross-Server Load-Balancing Inputs* already establish.

### What we publish

Two numbers per per-tick consumer, plus a host-level total:

- **Analytical WCET** — `Σ (cap × wcetPerIteration)`. The contract. Constant for a given configuration. Recomputed on `/rtp reload`. Published as `tickCpuBudgetMsAnalytical[<consumer>]` and the host total `tickCpuBudgetMsAnalytical[__total__]`.
- **Empirical p99 / p99.9** — measured per tick via `System.nanoTime()` around each consumer's per-tick entry/exit, aggregated into a wait-free reservoir over the configured window. Published as `tickCpuMsP99[<consumer>]` / `tickCpuMsP999[<consumer>]` and host totals.
- **Overshoot counter** — `tickCpuOvershoots`, incremented when the measured total exceeds the analytical bound. Should never increment; non-zero is a regression (a count-bound cap leaked or a per-iteration WCET regressed past its build-time constant).

### Tracked consumers (`TickConsumer` enum)

Fixed-shape, stable enum so the publish shape doesn't drift:

| Consumer | Cap source | WCET-per-iteration source | Notes |
|---|---|---|---|
| `REGION_PULSE` | ADR-015 count-bound pipe iteration cap (`Region.execute` per-pulse iterations) | Microbenchmark of one bounded `Region.execute` iteration excluding nested consumers | Outer envelope; inner consumers below are subtracted to avoid double-counting. |
| `PIPELINE_SHAPE` | Spiral 1D bounded math (ADR-001) — single-iteration | Microbenchmark of one shape-stage call | Constant-bounded by ADR-001. |
| `PIPELINE_VERT` | Count-bound per pulse | Microbenchmark of one vert-stage call | |
| `PIPELINE_BIOME` | Count-bound per pulse | Microbenchmark of one biome-stage call | |
| `PIPELINE_SAFETY` | Count-bound per pulse | Microbenchmark of one safety-stage call | |
| `L3_ANVIL_VERIFY` | **Format-fixed**: 1 `.mca` file per pulse, ≤ 1024 chunks per region | NBT-parse cost (file size constant) + per-chunk verify cost (anvil prefilter) | Analytically bounded with no measurement needed — see *L3 analytical bound* below. |
| `ACTIVE_GC_SWEEP` | `MemoryTracker` tracked-set cap | Microbenchmark of one `MemoryTracker` entry release | Bounded by the tracked-set cap, which is itself bounded by `pendingTeleports` cap. |
| `SCAN_REPLENISH` | ADR-006 fixed-cadence scan budget | Microbenchmark of one scan iteration | Periodic, jitter-bounded by ADR-006. |
| `DISPATCH_OVERHEAD` | Capped by `BRIDGE_DISPATCH_DEADLINE_MS` and `LIVE_LOAD_DEADLINE_MS` | Direct read of the deadline constants | Wraps non-RT `getChunk`; the deadline *is* the WCET contract for this row. |

New `FailKind`-style values default to a synthetic `OTHER` row so the publish shape stays stable when a future consumer is added. The mapping locks in M2 alongside the catalogue rows.

### L3 analytical bound (no measurement required)

`L3_ANVIL_VERIFY` is the cleanest row in the table because every input is format-fixed:

```
WCET_L3 = WCET_mca_read + 1024 × WCET_chunk_verify
```

- `WCET_mca_read`: bounded by `.mca` format (32×32 chunks, header + section table + section payload, ≤ a few MB; read is one I/O on a memory-mapped file).
- `WCET_chunk_verify`: bounded by the anvil prefilter's per-chunk NBT walk (ADR-016), independent of biome.
- `1024`: maximum chunks per region file (32×32).

No biome, no structure, no chunk-system call influences this number. L3 contributes a fixed analytical constant to the per-tick budget — the strongest contract in the table. See [ADR-016](../adr/ADR-016-anvil-subsystem.md) (anvil subsystem) and [ADR-028](../adr/ADR-028-l3-backlog-cache.md) (L3 backlog cache).

### Sampler placement

No new schedulers; the budget sampler rides the infrastructure already specified above:

- **Per-tick measurement**: each tracked consumer's per-tick entry/exit captures a `System.nanoTime()` delta into a per-consumer `LongAdder` (sum) and a per-consumer reservoir (wait-free; same shape as `sustainableRatePerMin`'s 900-sample circular buffer, sized at `metrics.tickCpuBudget.reservoirSamples`, straw-man 1200 = 60 s at 20 Hz).
- **Per-tick total**: a single `volatile long` for the most recent tick's measured total, compared against `tickCpuBudgetMsAnalytical[__total__]`; on overshoot, increment `tickCpuOvershoots` (no allocation, no logging — surfaces in the next snapshot).
- **Snapshot read**: at `Metrics.snapshot()` time, walk each reservoir for p99 / p99.9. O(samples × consumers) but bounded by the reservoir size × ~9 consumers — negligible cost on a snapshot path that already does worse work for `regionQueueStatus`.
- **Folia**: each region thread maintains its own reservoir; aggregation follows the existing *Folia Aggregation* policy (`max` for `mspt`-shaped values, so the loudest region surfaces). A `metrics.folia.tickCpuBudget.aggregation` key parallels `metrics.folia.aggregation.mspt` (default `max`).

S-005 / Folia threading: every measurement is `nanoTime()` + `LongAdder.add()` + a single bounded-array write — no chunk I/O, no main-thread blocking, no allocation per sample.

### Configuration keys (all under `metrics.tickCpuBudget`)

- `metrics.tickCpuBudget.enabled` — straw-man `true`. Off by default on lite (the lite assembly has no L3 and a smaller pipeline; the budget is uninteresting there).
- `metrics.tickCpuBudget.reservoirSamples` — straw-man `1200` (60 s at 20 Hz). Larger windows trade memory for stability.
- `metrics.tickCpuBudget.publishPercentiles` — straw-man `[99, 99.9]`. Locked at v1; a future addition (e.g. p99.99) would be additive.
- `metrics.tickCpuBudget.overshootMargin` — straw-man `0.0` (strict). A small positive margin (e.g. `0.10` = 10%) absorbs measurement jitter from `nanoTime()` resolution if false-positive overshoots prove noisy in beta.

### Computing the analytical bound at startup

1. On every `RTP.metrics` bring-up (and on `/rtp reload`), the platform adapter reads each cap from configuration (`Region.execute` iteration cap, anvil bin size, `MemoryTracker` cap, `ScanTask` budget, …).
2. Per-iteration WCETs are persisted as `wcetPerIterationNs[TickConsumer]` build-time constants, generated once by a microbenchmark harness in `helpers/StressTestRTP` (or a dedicated `helpers/PerTickWcetBenchmark` slice — TBD in the M2 ADR) and committed under `rtp-core/.../metrics/TickCpuWcet.java`. Regenerated when a stage's per-iteration cost changes materially.
3. `tickCpuBudgetMsAnalytical[<consumer>]` = `cap × wcetPerIterationNs / 1_000_000.0`. The `__total__` row is the sum.

The build-time WCETs are themselves a published contract: any PR that regresses a per-iteration WCET past its constant must update the constant *and* the `CHANGELOG.md` entry under the next release. A regression test (`TickCpuWcetRegressionTest`, M2) asserts the microbenchmarked values against the persisted constants within a configurable margin and fails the build on regression.

### Admin readout (`/rtp info verbose`)

Under a new sub-block of *Health — server* (verbose only):

- `tickCpuBudget`: `<measured-total-p99-ms> / <analytical-total-ms>` (e.g. `3.2 / 8.5 ms`). Green when p99 ≤ 50% of analytical; yellow when ≤ 100%; red on any non-zero `tickCpuOvershoots`.
- `tickCpuOvershoots`: cumulative counter; red on non-zero.
- A breakdown table (verbose only) of per-consumer `analytical / p99 / p99.9` so operators can see which row is dominating.

### bStats integration

Bucketised only — never raw values:

- `tick_cpu_budget_headroom` (`AdvancedPie`) — bucketised `1 - (p99 / analytical)`: `≥75%` / `50–75%` / `25–50%` / `<25%`. Captures *how much of the analytical budget servers actually use* without leaking absolute numbers.
- `tick_cpu_overshoot_seen` (`SimplePie`) — `none` / `≥1` / `≥10` / `≥100` over the submission window. Surfaces contract regressions across the fleet without identifying individual hosts.

### Cross-server load-balancing input

`tickCpuBudgetMsAnalytical[__total__]` (constant per backend) and `tickCpuMsP99[__total__]` (per-snapshot scalar) are exposed in the publish frame so the proxy can compute *headroom* (`analytical - p99`) without re-deriving it from per-consumer detail. Per-consumer detail stays local — the LB needs the rollup, not the breakdown.

Privacy: the analytical and empirical numbers are floats with no operator-chosen identifiers; safe to publish. The build-time WCET constants are public anyway (committed in source). No new redaction layer required.

### Phasing

- **Phase M1 (preparatory)**: persist the per-iteration WCET constants in `TickCpuWcet.java` (or equivalent) once the M1 sampler infrastructure (`CoreMetrics`, demand-tracking 1 Hz tick) is in place. No publish surface yet.
- **Phase M2**: ship the catalogue rows (`tickCpuBudgetMsAnalytical`, `tickCpuMsP99`, `tickCpuMsP999`, `tickCpuOvershoots`), the per-tick measurement, the `/rtp info verbose` sub-block, and the `TickCpuWcetRegressionTest`. Folia aggregation defaults to `max` (loudest region surfaces).
- **Phase M2 (tail)**: bStats `tick_cpu_budget_headroom` and `tick_cpu_overshoot_seen` charts.
- **Phase M3 (cross-plan)**: include the host-total scalars in the cross-server publish frame.

### Distinction from existing fields

- `mspt` is the *whole server's* per-tick time, including everything other plugins and vanilla do. `tickCpuBudgetMsAnalytical[__total__]` is **only RTP's per-tick consumption** — the part we control and contract.
- `pipelineFailureRate` measures *attempt-level* failure; this measures *resource* consumption. Both can be high simultaneously (cache miss → many failed attempts → high CPU) or independently (steady cache hit at high command rate → high CPU, zero failures).
- `tickBudgetUtilisation` (`mspt / 50.0`) is host-level utilisation; this is RTP-attributable utilisation. Their *ratio* is RTP's share of the host tick — useful for capacity planning, computed on the proxy as `tickCpuMsP99[__total__] / mspt`.

### Open Items / Follow-Ups (specific to this section)

Added to *Open Items / Follow-Ups* below:

- WCET microbenchmark harness location (`helpers/PerTickWcetBenchmark` vs extending `helpers/StressTestRTP`) — decide during M2.
- Whether to expose per-stage WCET *constants* in the snapshot (so the proxy can recompute the analytical bound itself) or only the precomputed total — straw-man: total only, simpler publish shape, recomputation on the proxy is unnecessary because the constant is constant.
- Lite-assembly baseline: confirm during M2 that the lite-assembly subset of consumers (no L3, smaller pipeline) still produces a meaningful headroom number, or default `metrics.tickCpuBudget.enabled` to `false` on lite.

### ADR linkage

A dedicated project-wide ADR (`docs/adr/ADR-NNN-rtp-per-tick-cpu-budget-contract.md`) records the methodology and locks the `TickConsumer` enum shape. Recommended title: *RTP per-tick CPU budget contract*. Should be drafted alongside the M2 implementation slice and ratified before the M2 PR lands so the build-time WCET constants have a home.

---

## Spigot TPS Fallback (1.20.1+ minimum)

Raw Spigot's `Bukkit.Server` does **not** expose `getTPS()` on the lowest-supported MC version (1.20.1). It is a Paper-only addition. The `rtp-bukkit` adapter therefore ships a local sampler:

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

> **Updated 2026-05-17**: the SPI was extracted into the standalone `metrics-api/` module (neutral root `io.github.dailystruggle.metrics.api.*`) per [metrics-api-ADR-001](../../metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md). The diagram below reflects the post-extraction home. RTP-specific counters now live on `RTPMetricsExtension` (in `rtp-core`) rather than on the shared snapshot.

```
metrics-api/                 -- platform-neutral SPI (io.github.dailystruggle.metrics.api.*)
  ├── Metrics              (read-only facade + cross-plugin static registry)
  ├── MetricsBinding       (host-runtime contract, all methods defaulted)
  ├── MetricsSnapshot      (immutable host-runtime snapshot + typed extension slot)
  ├── MetricsExtension     (typed plugin-counter slot)
  └── FoliaRegionSample    (per-region detail carrier)
rtp-api/                     -- (no metrics types; keeps API stable)
rtp-core/
  └── metrics/
      ├── CoreMetrics          (aggregator implementing metrics-api Metrics)
      ├── RTPMetricsExtension  (RTP-specific counters: queueDepth, pendingTeleports, ...)
      ├── PipelineHistogram    (rolling stats for avgPipelineMs)
      ├── MetricsSnapshotRing  (1 Hz MSPT+heap ring)
      └── HeapSampler          (java.lang.management wrapper)
platforms/rtp-bukkit/    -- BukkitTpsSampler (local 1-tick averager)
platforms/rtp-paper/     -- PaperMetricsBinding (delegates to Bukkit.getTPS / getAverageTickTime)
platforms/rtp-folia/     -- FoliaMetricsBinding (per-region samplers + configurable aggregation)
platforms/rtp-fabric/    -- FabricMetricsBinding (server tick callbacks)
```

`MetricsSnapshot` lives in `metrics-api` so addons, sibling monorepo plugins, and the multi-server plan's `BackendStatePublisher` can all depend on it without dragging `rtp-core` or platform types.

`RTP.serverAccessor` gains a small additive surface (`getMetricsBinding()` returning the platform-specific source). This is the same "additive `serverAccessor` extension" pattern flagged in `MULTI_SERVER_PLAN.md > Module Placement`.

---

## Phased Roadmap

### Phase M0 — SPI shape *(docs only; light D-005)*

- [x] Define `Metrics`, `MetricsSnapshot`, `PipelineHistogram` in `rtp-core` — landed 2026-05-01 in `rtp-core/.../common/metrics/` (`Metrics`, `MetricsBinding`, `MetricsSnapshot`, `PipelineHistogram`, `HeapSampler`, `CoreMetrics`); covered by `MetricsSnapshotTest`, `PipelineHistogramTest`, `HeapSamplerTest`, `CoreMetricsTest`.
- [x] Confirm field catalogue against `rtp test full`'s current output so the new SPI is a strict superset — verified 2026-05-15: `rtp test full` (`rtp-plugin/.../bukkit/commands/test/TestFullCmd.java`) emits only per-subcommand audit pass/fail lines via `FullAudit`; it does not print any TPS / MSPT / heap / queue / pipeline numeric fields, so the v1 `MetricsSnapshot` catalogue is trivially a strict superset. No plan amendment needed.
- [x] Add this plan to `INDEX.md` / `MAP.md` / `AGENTS.md` task router.

### Phase M1 — Core + Paper + Spigot fallback

- [x] Implement `Metrics` and `MetricsSnapshot` in `rtp-core`. *(landed in M0; carried forward.)*
- [x] `PipelineHistogram` integrated into `TeleportPipelineTask` completion path (additive; no behaviour change) — wired into `runCleanup` (single-shot via `pipelineHistogramRecorded` guard) on 2026-05-01; covered by `TeleportPipelineTaskPhaseTest#runCleanup_records_one_sample_into_pipeline_histogram` and `…_is_idempotent_for_pipeline_histogram`. Process-wide aggregator exposed as `RTP.metrics` (`CoreMetrics` instance, NOOP binding by default).
- [x] `PaperMetricsBinding` wraps `Bukkit.getTPS()` / `getAverageTickTime()` — landed 2026-05-01 in `platforms/rtp-paper/rtp-paper-common/.../paper/metrics/PaperMetricsBinding.java`; supplier-injection seam allows non-MockBukkit testing; Bukkit-bound production constructor returns documented sentinels (`MetricsSnapshot.UNSAMPLED` / `0`) when `Bukkit.getServer()` is null or the call throws. Covered by `PaperMetricsBindingTest` (3/3 green: supplier delegation, NaN-sentinel propagation, inherited-default preservation for the un-overridden `softCap`/`chunkLoadBacklog`/`databaseLatencyMs` fields). Plugin-enable wiring (`RTP.metrics.setBinding(new PaperMetricsBinding())`) deferred to the platform-bring-up slice that also wires `rtp test full` output.
- [x] `BukkitTpsSampler` for Spigot 1.20.1 fallback — landed 2026-05-01 in `platforms/rtp-bukkit/rtp-bukkit-common/.../spigot/metrics/BukkitTpsSampler.java`. Implements `MetricsBinding`; `tick()` is invoked once per server tick from a 1-tick repeating task on `RTP.scheduler` and feeds three EMAs (1m / 5m / 15m windows in ticks at nominal 20 TPS). TPS is clamped to `[0.0, 20.0]`; MSPT is the raw 1m EMA in ms; pre-tick / single-tick / non-progressing-clock paths return `MetricsSnapshot.UNSAMPLED`. Covered by `BukkitTpsSamplerTest` (7/7 green: pre-tick sentinel, seed-only first call, steady-50ms→20 TPS / 50 MSPT, slow-100ms→10 TPS / 100 MSPT, faster-than-20-clamp, non-progressing clock, 1m-vs-15m EMA divergence). Plugin-enable wiring (instantiate, `RTP.metrics.setBinding(sampler)`, `RTP.scheduler.runTaskTimer(sampler::tick, 1L, 1L)`) deferred to the same platform-bring-up slice as `PaperMetricsBinding` and `rtp test full`.
- [x] `HeapSampler` via `ManagementFactory`. *(landed in M0; carried forward.)*
- [ ] Wire `rtp test full` to print `MetricsSnapshot.toString()` (replace the ad-hoc dump).
- [ ] **Amend `InfoCmd` to render the M1 health groups** (*server*, *pipeline*, *demand*) from a single `Metrics.snapshot()` call per invocation. Compact view by default; `verbose` / `-v` flag expands to the full per-region cache table (deferred to M2 for Folia detail). Reuse `rtp.info` permission. New `InfoCmdTest` rows: (a) snapshot is read exactly once per invocation, (b) demand block is suppressed when `metrics.demand` is disabled, (c) compact-vs-verbose output divergence.
- [x] Unit tests for the histogram, sampler, and snapshot immutability — `PipelineHistogramTest`, `HeapSamplerTest`, `MetricsSnapshotTest`, `CoreMetricsTest` (15/15 green) plus `TeleportPipelineTaskPhaseTest` histogram-wiring cases (22/22 green).

### Phase M2 — Folia + Fabric

- [ ] `FoliaMetricsBinding` with the `max` / `mean` defaults from *Folia Aggregation* and the `metrics.folia.aggregation.*` config keys.
- [ ] `FabricMetricsBinding` using the server tick callback chain wired in Step E2 of `MULTI_PLATFORM_PLAN.md`.
- [ ] Per-platform smoke tests confirming `MetricsSnapshot` returns sane values on each runtime.
- [ ] **Extend `InfoCmd`** with the per-region *Health — cache* table (L1/L2/login fills + status flag), the verbose Folia per-region TPS/MSPT table, and the *load-balancer inputs* sub-block (`cacheServeRateLast60s`, `coldServeRatio`, `pregenSaturation`, `sustainableRatePerMin`). Add the `/rtp info json` output path emitting the full `MetricsSnapshot` record.
- [ ] **Real-time per-tick CPU budget** (per *Real-Time Per-Tick CPU Budget*): persist per-iteration WCET constants in `rtp-core/.../metrics/TickCpuWcet.java`; ship `tickCpuBudgetMsAnalytical` / `tickCpuMsP99` / `tickCpuMsP999` / `tickCpuOvershoots` catalogue rows; wire per-tick `nanoTime()` measurement around each `TickConsumer`; add the verbose `/rtp info` sub-block; land `TickCpuWcetRegressionTest`. Folia aggregation defaults to `max`. Gated on the dedicated ADR (`docs/adr/ADR-NNN-rtp-per-tick-cpu-budget-contract.md`) being ratified before the implementation PR lands.

### Phase M3 — Multi-server consumer (cross-plan)

- [ ] `BackendStatePublisher` in `MULTI_SERVER_PLAN.md > Backend Telemetry Publication` consumes `Metrics.snapshot()` directly. No new metric code; pure consumer.
- [ ] **`InfoCmd` network block** — `networkMode`, `transport` last-success age, `recentReservations`, `networkWaitQueue`, `lastSelectorPick`, stale-backend warnings. Suppressed when `network.enabled: false`. Gated by the multi-server plan's Phase 2 reservation-token table.
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

- **`avgPipelineMs` window length and reset semantics** — *resolved (2026-05-15)* by [ADR-032](../adr/ADR-032-teleport-pipeline-latency-histogram.md): 256-sample wait-free ring, never resets, mean-only readout.
- **Pipeline latency percentiles (p50/p75/p90/p95/p99)** — *resolved (2026-05-29)* by [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md): exposed as an additive on-demand read path that sorts a point-in-time copy of the existing bounded ring (no sketch dependency, no change to the wait-free write path). Amends ADR-032's deferred "mean-only readout" item.
- **Slow-teleport audit threshold** — *resolved (2026-05-29)* by [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md): `performance.yml > slowPipelineThresholdMs` (default `5000` ms; `<= 0` disables). Drives a once-per-task `WARN` log and the cumulative `slowPipelineCount`, **gated to immediate/unqueued teleports only** (queued at-rate teleports include queue-wait time in their elapsed window and would false-positive — see ADR-053 §2a / REQ-RTP-OBS-005). Confirm the default during the implementation slice from beta-server data; lite-assembly servers on lower-end hardware may want a higher default.
- **Queue-growth audit threshold** — *resolved (2026-05-29)* by [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md) §2b / REQ-RTP-OBS-006: `performance.yml > queueGrowthWarnThreshold` (int players; default `0` = disabled/opt-in). Edge-triggered `WARN` + cumulative `queueGrowthWarnCount` evaluated on the existing snapshot cadence against the summed `queueDepth`. This is the queued-path backpressure signal that replaces a naive per-queued-teleport latency alarm. Confirm the default arming value (and whether to ship a non-zero default) during the implementation slice from beta-server data — acceptable queue depth is highly rate- and capacity-dependent.
- **`biomeRerolls` cap / top-N policy** — straw-man: no cap (vanilla biome cardinality is naturally bounded). Revisit if a modpack pushes the map past ~500 keys, at which point introduce `metrics.biomeRerolls.topN` (default 32). Confirm during M1.
- **`biomeRerolls` reset semantics** — straw-man: cumulative since process start. Operators wanting deltas compute them from successive snapshots, consistent with the *Snapshot, not stream* goal.
- **`biomeRerolls` window default** — straw-man: 300s. Short enough to attribute the next `/rtp` to dissatisfaction with the prior outcome, long enough to absorb a player looking around before deciding. Confirm during M1 from beta-server data.
- **`databaseLatencyMs` measurement cadence** — *resolved (2026-05-15)*: sample on every write (Option 1) routed through a single function on `AbstractSQLDatabaseAccessor` so a dedicated probe can be substituted later without touching call sites. Accepted limitation: under pool saturation the reading conflates queue wait with wire RTT; that's surfaced in the field's Javadoc and in `/rtp info` colour-band guidance. Migration path to a dedicated probe (gated by a future `metrics.database.dedicatedProbe` key) stays open and does not require changing the `MetricsSnapshot` shape.
- **`metrics.demand.overflowMinSamples` / `stressMsptThreshold` defaults** — straw-man `3` and `45.0` ms. Confirm during M1 from beta-server data; expect lite-assembly servers to want a higher MSPT threshold (lower-end hardware baseline).
- **Auto-adjustment control loop** — deferred behind a dedicated ADR. Required before `metrics.demand.autoAdjust.enabled` does anything; the metric publishes the inputs in v1, but no closed-loop adjustment ships until the ADR specifies error term, safety interlocks (never tighten during `tickStressEvents` rise), and which control surfaces (`ScanTask` budget, pipeline parallelism, login-reserve cadence) are eligible.
- **Folia per-region detail opt-in key** — *resolved (2026-05-15)*: `metrics.folia.includeRegions: true` by default. Folia operators are the audience that most wants per-region detail, and the snapshot memory cost is bounded by the region count (see the dedicated *Memory cost of `MetricsSnapshot`* open item). Operators on extreme-region-count Folia deployments can flip the key to `false`.
- **Memory cost of `MetricsSnapshot`** — must stay small enough to be cheap to publish at 1Hz under proxy mode (multi-server consumer constraint).
- **`sustainableRatePerMin` reservoir window & percentile** — straw-man 900-sample circular buffer (15 m at 1 Hz), p95. Confirm during M2 from beta-server data; very small fleets may want p90 to react faster, very large fleets p99 to absorb noise.
- **`cacheServeRateLast60s` / `coldServeRatio` window length** — straw-man 60 s. Long enough to absorb a single quiet minute, short enough to react to a config change. Confirm during M2.
- **`FailKind` enum mapping** — fixed-shape rollup over `FailTypes` (`biome`, `prefilterBiome`, `unsafe`, `nullChunk`, `other`). Lock the mapping during M1 alongside the `pipelineFailureBreakdown` field; new `FailTypes` values default to `other` until the mapping is amended.
- **`gcPauseRecent` window** — straw-man "max within the current 1 s sample window". Decide during M1 whether to expose a longer rolling max (e.g. 60 s) for the LB consumer or leave windowing to the proxy.
- **`effectiveQueueWaitMs` enqueue-stamp storage** — `playerQueue` entries currently carry a UUID; the cheap option is to wrap into a `(UUID, long enqueueNanos)` holder. Confirm during M2 that no caller depends on the raw `UUID` element type.
- **`regionQueueStatus.maxRegions` cap** — straw-man `64`. Well above any realistic deployment; confirm during M2 that no production config exceeds it before locking the default. Overflow rows are summarised under a synthetic `__overflow__` key.
- **`regionQueueStatus` redaction in network mode** — straw-man: stable opaque ids (`region-0`, `region-1`, …) assigned at startup, never crosses the wire as the human name. Confirm during M3 that the reservation-token allocator can round-trip the opaque id back to the local `RegionQueueManager` without the proxy needing the real name.
- **Per-tick CPU budget WCET harness** — `helpers/PerTickWcetBenchmark` (new slice) vs extending `helpers/StressTestRTP`. Decide during M2 alongside the *Real-Time Per-Tick CPU Budget* implementation. Microbenchmarked values feed `TickCpuWcet.java` build-time constants.
- **Per-tick CPU budget reservoir size & percentile set** — straw-man `1200` samples (60 s at 20 Hz) and `[p99, p99.9]`. Confirm during M2 from beta-server data; very large fleets may want a longer window.
- **Per-tick CPU budget overshoot margin** — straw-man `0.0` (strict). Bump to `0.10` if `nanoTime()`-resolution jitter produces false-positive overshoots in beta.
- **`tickCpuBudget` lite-assembly default** — straw-man: same `true` default as full. Confirm during M2 that the lite consumer subset still produces a meaningful headroom number; flip to `false` on lite if not.

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
- `pipelineMs` percentiles: `p50 / p75 / p90 / p95 / p99` over the same histogram window (per [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md)). Shown alongside the mean so a tail-latency problem (mean fine, p99 high) is visible at a glance; `sampleCount` is printed next to them so an operator can judge whether the distribution is stable yet.
- `slowPipelineCount`: cumulative count of **immediate/unqueued** completions over `slowPipelineThresholdMs`, rendered as `slow: <count> (> <threshold> ms)`. Paints yellow when non-zero; the per-event detail already went to the `WARN` log at completion time. Omitted (or shown as `slow: disabled`) when the threshold is `<= 0`. Queued at-rate teleports are deliberately excluded here (their elapsed time includes queue wait); their pressure signal is `queueGrowthWarnCount` below.
- `queueGrowthWarnCount`: cumulative count of edge-triggered queue-backpressure warnings, rendered as `queue-growth: <count> (>= <threshold> players)`. Paints yellow when the current `queueDepth` is at-or-above `queueGrowthWarnThreshold`. Omitted (or shown as `queue-growth: disabled`) when the threshold is `<= 0`. This is the queued-path counterpart to `slowPipelineCount`.
- `chunkLoadBacklog`: incomplete async chunk-load futures.
- `effectiveQueueWaitMs`: rolling EMA of player-enqueue → pipeline-start latency. Shown next to `queueDepth` so operators can compare *size* vs *experienced wait*.
- `pipelineFailureRate`: rolling `failures / completions` over the last sample window, plus the top-3 entries of `pipelineFailureBreakdown` (e.g. `biome:42, unsafe:9, nullChunk:1`). A non-zero `nullChunk` count paints red regardless of overall rate (S-005 spirit; surfaces a regression even when raw throughput looks fine).
- `gcPauseRecent`: largest GC pause (ms) observed in the last sample window. Distinguishes a GC stall (recovers fast) from a chunk-I/O stall (`chunkLoadBacklog` rising) when both look identical in a TPS dip.

#### Health — demand (always shown)

Direct readout of the *Command-Demand vs Refill Tracking* counters; one block, four lines, designed to answer the rush-hour question at a glance:

- `commandRate (1m/5m/15m)`: `/rtp` invocations per minute.
- `refillRate (1m/5m/15m)`: net cache refill per minute (L1 + L2 across regions).
- `overflow`: current `commandOverflowRatePerMin` + cumulative `commandOverflowEvents`. Yellow when non-zero; red when paired with a recent `tickStressEvents` increment.
- `stress`: cumulative `tickStressEvents` alongside the already-shown `mspt`.
- `sustainableRatePerMin` (verbose only): the trailing p95 of `refillRatePerMin` while `tickStressEvents` did not increment — the operator-visible "how much could this host service if asked?" number.

#### Health — player satisfaction (verbose only)

- `biomeRerolls`: top-5 destination biomes by reroll count, plus an `(other: N)` rollup, plus the overall reroll rate `biomeRerollsTotal / (biomeRerollsTotal + biomeAcceptancesTotal)`. Gated to verbose to keep the compact view tight.
- `loginReserveExhaustion`: cumulative count of join-time RTPs that fell through an empty `loginLocations` (ADR-023). Highlighted yellow if it incremented in the last sample window.

#### Health — load-balancer inputs (verbose only, network mode only)

When `network.enabled: true`, expose the LB-only fields a proxy consumes so an operator can see what's being published:

- `cacheServeRateLast60s` / `coldServeRatio`: hot-vs-cold serve split.
- `pregenSaturation`: `ScanTask` budget utilisation `[0.0, 1.0]`.
- `regionQueueStatus` summary: count of regions in each `status` bucket (`OK` / `LOW` / `EMPTY` / `SATURATED`). The full per-region table is rendered by *Health — cache* (above); this line is the one-glance rollup the LB sees.

Rendered as a single line in compact view (`lb-inputs: hot=…, cold=…%, pregen=…%, regions=ok/low/empty/saturated`); broken out per-line under verbose.

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

`/rtp info` defaults to a compact view. Three flags expand or focus it:

- `/rtp info verbose` (or `-v`): expand the per-region cache table and show per-platform extras (Folia per-region TPS table when on Folia; raw sampler readings on Spigot 1.20.1).
- `/rtp info performance` (sub-selector): focus the readout on the recorded server-and-plugin runtime state — the *Health — server*, *Health — pipeline* (including the `pipelineMs` percentiles, the immediate-only `slowPipelineCount`, and the queued-path `queueGrowthWarnCount` lines above), and *Health — demand* groups — without the configuration-oriented region/world output. This is the maintainer-chosen surface for the slow-RTP / percentile readout (per [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md)); it reads the same single cached `Metrics.snapshot()` (plus `pipelineHistogram().percentiles()`) and adds no new sampling. Reuses `rtp.info`. Composable with `verbose` and `json` (`/rtp info performance json`).
- `/rtp info json` (machine-readable): emits the full `MetricsSnapshot` as JSON for piping into log aggregators or monitoring scripts, including the `pipelineMsP*` percentile fields, `slowPipelineCount` / `slowPipelineThresholdMs`, and `queueGrowthWarnCount` / `queueGrowthWarnThreshold`. Implementation note: serialise the existing immutable record; do not invent a parallel schema.

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
- A snapshot test of the JSON output shape (including the `pipelineMsP*` percentile fields and `slowPipelineCount` / `slowPipelineThresholdMs`) so ops scripts can rely on key stability across releases.
- A unit test asserting the `pipelineMs` percentiles render from `PipelineHistogram.percentiles()` and that `/rtp info performance` reads exactly one `Metrics.snapshot()` (REQ-RTP-OBS-004 surface).
- A unit test asserting `slowPipelineCount` increments once per slow **immediate/unqueued** completion and emits a `WARN` log, that a **queued** (at-rate) completion over the threshold does **not** increment it (no false positive from queue-wait time), and that a `<= 0` threshold disables the audit (REQ-RTP-OBS-005 surface).
- A unit test asserting `queueGrowthWarnCount` increments once on the below→at-or-above `queueGrowthWarnThreshold` transition (edge-triggered, not once per evaluation), re-arms after the depth drops below, and that a `<= 0` threshold disables the audit (REQ-RTP-OBS-006 surface).

### Phasing

- **Phase M1**: ship the *server*, *pipeline*, and *demand* health groups (drives off the same M1 work). Includes `effectiveQueueWaitMs`, `pipelineFailureRate` + breakdown top-3, and `gcPauseRecent` lines once their catalogue rows land. The `pipelineMs` percentiles, `slowPipelineCount` / `slowPipelineThresholdMs`, and the `/rtp info performance` sub-selector ride this same M1 slice (per [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md)) — they read data the M0 histogram already records and need no new sampler.
- **Phase M1 (gated on biome-reroll wiring)**: the *player satisfaction* group's `biomeRerolls` table; `loginReserveExhaustion` rides the same M1 slice as the rest of the LB-input M1 candidates.
- **Phase M2**: per-region cache table + Folia per-region detail (depends on `FoliaMetricsBinding` from M2). Adds `cacheServeRateLast60s` / `coldServeRatio` / `pregenSaturation` to the *load-balancer inputs* sub-block once their catalogue rows land. `sustainableRatePerMin` joins the *demand* group (verbose) when the reservoir lands.
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
| `biome_reroll_distribution_shape` | `AdvancedPie` | bucketised reroll-share concentration from `biomeRerolls`: `top1>=80%` / `top1>=50%` / `top3>=80%` / `flat`. Reports **shape** of the distribution, not biome names — answers "do most servers have one biome that players reject, or is it spread out?" for server-design guidance. Biome names are deliberately omitted to avoid fingerprinting modded biome packs. |
| `biome_reroll_rate` | `AdvancedPie` | bucketised reroll rate `biomeRerollsTotal / (biomeRerollsTotal + biomeAcceptancesTotal)` per submission window: `<5%` / `5-15%` / `15-30%` / `30%+`. Indicates how often players are dissatisfied with their `/rtp` outcome. |

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
- **Carry-over open items** (already in *Open Items / Follow-Ups*): `avgPipelineMs` window length *(resolved 2026-05-15 by ADR-032)*, `databaseLatencyMs` cadence *(resolved 2026-05-15 — every-write via a function, dedicated-probe migration path preserved)*, Folia per-region detail opt-in default *(resolved 2026-05-15 — `metrics.folia.includeRegions: true`)*, `MetricsSnapshot` memory cost *(still open; revisit during M2 once per-region rows + foliaRegions land)*. None block M1 implementation.
- **Deferred to consumer plans**: anonymisation rules for the bStats catalogue (covered in this plan's *bStats Integration > Constraints*); colour-band thresholds for `/rtp info` (covered in `messages.yml` per REQ-RTP-F-013).

**M0 fully closed (2026-05-15)**: the `rtp test full` superset confirmation (Section A row A1 in `docs/dev/scratch/CHECKLIST-metrics-and-multiserver.md`) is ticked. `MetricsSnapshot` shape D-005 review completed (Section A row A5) — the 16-field on-disk record (`tps{1,5,15}m`, `mspt`, `tickBudgetUtilisation`, `playerCount`, `softCap`, `heapUsed/MaxBytes`, `queueDepth`, `pendingTeleports`, `memoryTrackerEntries`, `chunkLoadBacklog`, `avgPipelineMs`, `databaseLatencyMs`, `takenAtEpochMs`) is the approved M0 SPI; later phases add fields via additive constructor changes contained to the single binding constructor.

---

## Cross-References

- [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md) — primary downstream consumer (telemetry publisher).
- [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md) — Fabric Step E2 tick-callback hook is the basis for `FabricMetricsBinding`.
- [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) — any platform-specific surprise from sampler implementation lands here.
- [`AGENTS.md > Domain Analogies & Aliases`](../../.junie/AGENTS.md) — informal terms (`mspt`, `tps`, `the snapshot`) route here.

*Self-update note*: this plan describes the metrics SPI shape only. Implementation lore goes to `LESSONS_LEARNED.md`; new `Metrics` fields land via additive PRs without amending this plan unless the SPI shape changes.
