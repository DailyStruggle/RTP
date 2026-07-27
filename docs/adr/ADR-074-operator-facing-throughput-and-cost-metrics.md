# ADR-074 - Operator-Facing RTP Throughput and Cost Metrics (Paired Rates, EWMA Windows, Color-Coded Readout)

**Status:** Proposed
**Date:** 2026-07-23

## Context

[ADR-032](ADR-032-teleport-pipeline-latency-histogram.md) established the `PipelineHistogram`: a process-wide, 256-sample, wait-free, never-reset ring of `TeleportPipelineTask` completion times, exposing a mean readout. [ADR-053](ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md) extended it with on-demand percentiles and a slow-teleport audit, and confirmed the operator surface: a `/rtp info performance` readout aggregated through `Metrics.snapshot()` / `CoreMetrics`, mirrored into `PlaceholderProvider` and bStats. [ADR-052](ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md) covers per-outcome/bad-location attribution.

These give a developer-grade view of *pipeline latency*, but they do not answer the questions a server operator actually asks about RTP behavior:

1. **How long does the whole command take?** `avgPipelineMs` measures from `TeleportPipelineTask` construction to cleanup, which (per ADR-053) folds in queue-wait time for at-rate players. It is not the same as the end-to-end wall-clock a player experiences from running `/rtp` to arriving.
2. **How expensive is a good destination in this world?** There is no cost-per-accepted-location signal, which is the number that exposes a badly-configured world (ocean, void, heavy claims) as expensive-to-serve.
3. **Is RTP keeping up?** There is no usage rate, no refill rate, and therefore no way to see whether the cache is draining faster than it refills.

The architecture charts under `docs/architecture/` name the individual behaviors (SETUP, LOAD, safety-eval, teleport, cleanup, cache refill). Full per-chart-node profiling was considered (see *Alternatives*), but the operator-facing need is coarser and more legible: a small fixed set of "behavior time" and "throughput/cost" figures, not a stage-by-stage flame graph.

**Bursty refill is the hard representation problem.** The L3 backlog refill loop (`Region.processBacklog`, ADR-028) is hysteresis-gated: `backlogRefillActive` latches off once the backlog reaches `backlogRefillThreshold * capacity` and back on once it drains below the dead-band. Consequently refill runs in bursts and its rate legitimately falls to zero at steady state. A standalone "refill rate" is therefore ambiguous: `0/s` can mean "cache full and healthy" or "refill starved and failing." Precedent for making a bursty rate legible converges on one pattern:

- **Unix load average** shows the same quantity over three EWMA windows (1/5/15 min); the reader infers trend/burstiness from the divergence between windows.
- **Prometheus `rate()` vs `irate()`** defaults to a windowed average (smooth) and reserves the last-two-sample instantaneous form for zoom; the counter is stored, the rate is derived over an explicit window.
- **Token-bucket fill level** surfaces current fill percent rather than instantaneous refill rate, because fill level is stable and directly answers "are we about to run dry?"
- **RRDtool / Grafana / interface throughput meters** store a monotonic counter and compute rate = delta / interval at read time.

The common lesson: never show a lone instantaneous rate for a bursty source; show a smoothed figure with a stated window, pair it against the demand it serves, and add a stable level/state gauge so a zero rate is not misread.

Constraints carried forward from ADR-032 / ADR-053 / `METRICS_PLAN.md`:

- **S-005 spirit.** Sampling runs on whatever thread completes the work; recording must be allocation-free, lock-free, and contention-free on hot paths.
- **Snapshot-not-stream.** Rates and derived indicators are computed on demand at read time; the snapshot stays an immutable carrier.
- **Bounded cost.** No per-uptime or per-player growth in state.
- **No platform imports in `rtp-core/metrics/`.** Enforced by the existing ArchUnit guard.
- **No new sampling subsystem.** Reuse the `MetricsSnapshot` / `CoreMetrics` aggregation and the `RTPMetricsExtension` carrier.

## Decision

Add a compact, operator-facing "RTP throughput and cost" readout as an additive extension of the ADR-032/ADR-053 metrics, surfaced through the existing `/rtp info performance`, `PlaceholderProvider`, and bStats channels.

### 1. Metric set (four axes plus derived indicators)

1. **Command latency (end-to-end).** Wall-clock from `/rtp` to teleport completion, recorded into an ADR-032-style ring. This value already exists per teleport: `TeleportPipelineTask.runTeleport` computes `teleportData.processingTime = System.currentTimeMillis() - teleportData.time` and surfaces it to the player via the `[processingTime]` placeholder in `teleportMessage`; today it is discarded after the message. The metric simply records that existing value into a ring at the same completion point (ideally next to the `pipelineHistogramRecorded` single-shot CAS). Note that `teleportData.time` is stamped at pipeline-task construction, so the captured window matches what the player is shown; a true dispatch-to-arrival window would require stamping the time in the `commands-api` handler instead. Labeled distinctly from pipeline latency so the queue-wait-inclusive `avgPipelineMs` is not confused with the player-perceived number.
2. **Cost per valid location.** Compute cost amortized per *valid location* - a location classified safe-enough-to-teleport that reaches the user, whether served directly or via the L1 cache. Backed by cumulative counters, computed as a ratio at read time. The attribution model is non-trivial (L3 is batch-shaped, not per-location) and is specified in detail in section 1a.
3. **Usage rate.** Teleports served per unit time (with a served-from-cache vs generated-on-demand split), as an EWMA over a named window.
4. **Refill rate.** Locations refilled per unit time, as an EWMA over the **same named window** as usage rate.

Derived, read-time-only indicators:

- **Usage/refill ratio** (acute:chronic style): the headline "keeping up?" number over the shared window. `<= 1` keeping up, `> 1` draining.
- **Backlog fill percent** and a **refill state label** (`full` / `refilling` / `draining` / `starved`) read from the backlog level and the `backlogRefillActive` latch, so a zero refill rate is disambiguated by a stable gauge rather than by the rate alone.

### 1a. Cost-per-valid-location attribution model

"Valid location" is defined at the **accept boundary**: a coordinate is counted once, at the moment it is confirmed teleport-safe and handed to the player, regardless of which tier produced it. A location promoted L3 -> L2 -> L1 is therefore counted exactly once, avoiding double-counting.

Two producers of valid locations, both incrementing the same `acceptedLocations` denominator:

1. **Search pipeline (common path):** L3 backlog -> L2 unkept -> L1 kept -> final-verification. A location becomes valid when it passes final-verification and is served. This is the first implementation target.
2. **Direct computation (special case):** the unqueued path. Rare per current bStats observation, but it still produces a valid location, so it must increment `acceptedLocations` or the cost denominator undercounts. Operators may promote it to the common path, so it is kept as a first-class-but-flagged axis via a separate `directComputeCount` / `directComputeNanos` pair, letting the readout show the direct-vs-search split (and confirm the "exceedingly rare" observation at runtime).

The numerator (cost) is **staged and split by attribution shape**, because not all compute is per-location-attributable:

- **L3 is batch/amortized, not per-location.** The L3 stage does not search per coordinate; it multi-selects and performs binned verification one `.mca` file at a time. The file open/close overhead and every failed selection check are real compute that no single successful location owns. Attributing them to a specific accepted coordinate would be arbitrary. They are therefore accumulated into a separate `l3OverheadNanos` batch counter (wrapping the whole binned-verification span in each `Region.execute()` bin pulse - pass and fail alike) and reported as **amortized L3 cost = `l3OverheadNanos / acceptedLocations`**. This naturally spikes for badly-configured worlds where most bins fail, which is the intended operator signal. Like refill, L3 overhead is bursty (one bin per pulse, stops when the backlog is full), so an EWMA-over-window view pairs with the accept rate the same way usage pairs with refill.
- **Direct search cost (L2/L1/final-verification) is per-location-attributable** and accumulated into `searchNanos`, reported as **direct search cost = `searchNanos / acceptedLocations`**.
- **Command-pipeline sections fold in later.** The user-side pipeline is minimal; once search cost lands, the SETUP/LOAD/teleport spans attributable per served location are added into a `commandNanos` counter as a small delta.

The amortized-L3 and direct-search figures are reported as **two separate cost numbers, never summed into one**: they have different attribution shapes (batch vs per-location) and diagnose different problems (high amortized-L3 = prefilter thrashing bins / config-biome problem; high direct-search = chunk-load/final-verify expense / disk-generation problem). Summing them would misrepresent the batch overhead as per-location and lose that diagnostic split.

All figures are ratios-of-counters computed at read time (no stored averages), consistent with the ADR-053 percentiles and the usage/refill rates, and S-005-safe.

### 2. Refill is always presented paired with usage

Usage rate and refill rate are a single presentation unit: they are computed over the same window, rendered on the same readout line, and never shown independently. The backlog fill percent / state label travels with the pair. This makes every steady state legible:

| usage | refill | reading |
|-------|--------|---------|
| ~0 | ~0 | idle at cap - healthy |
| >0 | ~0 (fill high) | serving a full cache, no refill needed - healthy |
| >0 | >0, refill >= usage | refill burst keeping up - healthy |
| >0 | >0, refill < usage (fill low) | refill cannot keep pace - the one bad state |

### 3. Rate representation

- Rates are EWMAs (single field each, O(1), lock-free), computed from cumulative counters, exposed over a **named, documented window** so the readout is self-explanatory (the recurring mistake being a rate with no stated interval). Optionally a short and a long window are shown side by side (load-average style) to surface burstiness as the divergence.
- The `PipelineHistogram` distribution machinery from ADR-032/053 is reused for the two latency metrics (command latency, cost-per-location); only the rate/ratio/level fields are new EWMA/counter/gauge additions.

### 4. Color coding

The readout is color-coded on the lower-is-better / keeping-up axes, reusing the existing `messages.yml` color-band threshold mechanism already established for the ADR-053 `/rtp info` health block (and the `avgPipelineMsColoured` placeholder). Bands: green (healthy), amber (warning), red (unhealthy). The usage/refill ratio and command latency drive the headline band; per-field coloring follows the same threshold configuration so operators can retune without code changes. Color thresholds ship as configurable keys, consistent with REQ-RTP-F-013 (all user-facing messages configurable).

### 5. Surfaces and configuration

- New fields are carried on `RTPMetricsExtension` and read via `snapshot.extension(RTPMetricsExtension.class)`, exactly like the existing health signals.
- Rendered through `/rtp info performance`, new `PlaceholderProvider` placeholders, and bStats charts.
- New `messages.yml` keys (labels + color-band thresholds) are added to the English baseline and mirrored into every shipped locale per *Locale Parity Maintenance* (identity rows acceptable for first-pass), gated by `LocaleParityTest`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Full per-chart-node profiling (one ring per architecture-chart behavior) | Higher memory/complexity, couples the operator readout to the developer charts, and answers a developer question, not the operator's "is RTP fast and keeping up?" The coarse set is cheaper and more legible. Can still be layered later without conflicting with this ADR. |
| Show refill rate as a standalone number | Ambiguous: hysteresis (`backlogRefillActive`) makes `0/s` a legitimate healthy steady state indistinguishable from starvation without the paired usage rate and fill/state gauge. |
| Instantaneous (last-two-sample) rate only | Flickers between hysteresis bursts; unreadable. EWMA over a named window (Prometheus `rate()` precedent) is the smooth default. |
| Reuse `avgPipelineMs` as "command latency" | Includes queue-wait for at-rate players (per ADR-053); it is not the player-perceived end-to-end time. A distinct sample point is required. |
| No color coding (numbers only) | Operators triage at a glance; a green/amber/red band on the headline is the fastest legible signal and reuses the ADR-053 threshold mechanism at near-zero cost. |

## Consequences

- **Positive:** Operators get four independent, legible axes - fast (command latency), efficient (cost per valid location), busy (usage rate), sustainable (usage/refill ratio) - plus an at-a-glance color band. Bursty refill becomes self-documenting via the paired usage rate and fill/state gauge. Reuses existing sampling, aggregation, rendering, and threshold-config machinery; no new subsystem.
- **Negative / Trade-offs:** New fields on `RTPMetricsExtension` and its constructors (backward-compatible overloads required, as with the ADR-053 additions). New `messages.yml` keys incur locale-parity work across all shipped locales. Command latency reuses the already-computed `teleportData.processingTime`, so it adds only one recording call at the existing teleport-completion site (no new `commands-api` sample point unless a true dispatch-to-arrival window is later desired). Cost-per-location introduces several cumulative counters spread across the search pipeline (`acceptedLocations`, `searchNanos`, `commandNanos`) and the L3 bin pulse (`l3OverheadNanos`), plus the direct-compute split (`directComputeCount`, `directComputeNanos`); each increment must stay lock-free/allocation-free on the hot path. Cross-module change surface (`rtp-core/metrics`, `rtp-core` region/queue paths, `rtp-api` message keys, placeholders, bStats, locales) makes this D-005 gated.

## References

- [ADR-032](ADR-032-teleport-pipeline-latency-histogram.md) - Teleport Pipeline Latency Histogram (the reused ring contract).
- [ADR-053](ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md) - Pipeline Latency Percentiles and Slow-Teleport Audit (the `/rtp info performance` surface and color-band mechanism this extends).
- [ADR-052](ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md) - Outcome Metrics and Cause-Tagged Bad Locations (related attribution signals).
- [ADR-028](ADR-028-l3-backlog-cache.md) - L3 Backlog Cache (the hysteresis-gated refill loop; `backlogRefillThreshold`, `backlogRefillActive`).
- [`docs/dev/METRICS_PLAN.md`](../dev/METRICS_PLAN.md) - Metric Catalogue and the metrics SPI.
- `rtp-core/.../metrics/RTPMetricsExtension.java`, `PlaceholderProvider.java`, `commands/info/InfoCmd.java` - existing carrier and render sites.
- `rtp-core/.../tasks/teleport/TeleportPipelineTask.java` (`teleportData.processingTime` computation at the completion site) - the already-computed command-latency value to record.
- `rtp-core/.../selection/region/Region.java` (`execute` bin pulse / `processBacklog`) and the `RegionQueueManager` L1/L2/L3 accept boundaries - the counter-increment sites for cost-per-location, usage, and refill.
- Prior art: Unix load average (multi-window EWMA), Prometheus `rate()`/`irate()`, token-bucket fill level, RRDtool/Grafana counter-rate rendering.
