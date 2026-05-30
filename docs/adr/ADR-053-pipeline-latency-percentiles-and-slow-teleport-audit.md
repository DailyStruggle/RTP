# ADR-053 — Pipeline Latency Percentiles and Slow-Teleport Audit

**Status:** Accepted
**Date:** 2026-05-29

## Context

[ADR-032](ADR-032-teleport-pipeline-latency-histogram.md) ratified the `PipelineHistogram` contract: a single process-wide, 256-sample, wait-free, never-reset ring of `TeleportPipelineTask` completion times in milliseconds, exposing a **mean-only** readout (`mean()`) plus two counters (`sampleCount()`, `totalRecorded()`). ADR-032 deliberately deferred percentiles, recording the reasons in its *Alternatives Considered* table: no v1 consumer requested them, and a sortable readout costs `O(n log n)` per call.

Two new operator-facing needs have since been confirmed (issue: *Slow-RTP warnings + user-facing performance stats*):

1. **Percentile readout.** A mean alone hides tail latency. An operator triaging "RTP feels slow for some players" needs the distribution shape (P50/P75/P90/P95/P99), not a single average that a handful of slow chunk-loads can drag without revealing how many players are affected. The raw data already exists in the ring; only a read path is missing.
2. **Slow-teleport audit.** Operators want to be told *when* a teleport crossed an unacceptable latency, not only that the rolling mean drifted. A per-completion threshold check that emits a `WARN` log and a cumulative counter turns a silent slow path into an auditable event, consistent with the no-silent-failure posture of REQ-RTP-S-004.

**A naive per-completion threshold misattributes queue wait as pipeline latency.** The recorded sample is `System.nanoTime() - pipelineStartNanos`, and `pipelineStartNanos` is fixed at `TeleportPipelineTask` *construction*. For a player who is enrolled in the public queue and served **at-rate** (the normal flow without `rtp.unqueued`), that window includes the time the request sat waiting for the rate-limited dequeue, not just the search/chunk-load/safety work. A busy queue would therefore trip a fixed latency threshold on essentially *every* queued teleport, drowning the operator in `WARN` lines that say nothing about pipeline health — the slowness is a throughput/backpressure condition, not a slow individual teleport. The correct signal for the queued path is **queue growth/backpressure**, not per-teleport latency. The audit must therefore distinguish the two regimes (see *Decision* §2).

Both feed a new `/rtp info performance` readout (the user-chosen surface; see *Decision*), reusing the existing `Metrics.snapshot()` / `CoreMetrics` aggregation. No new sampling subsystem and no new data collection are introduced — this ADR adds **read paths and a threshold counter over data the M0/M1 histogram already records**.

Constraints carried forward from ADR-032 and `METRICS_PLAN.md`:

- **S-005 spirit (no main-thread blocking).** The recorder (`TeleportPipelineTask.runCleanup`) runs on whatever thread completes the pipeline (Folia region thread, Paper/Spigot tick thread, Fabric server thread). The added threshold check must remain allocation-free, lock-free, and contention-free on that path.
- **Single-shot recording.** The slow-count increment must ride the existing `pipelineHistogramRecorded` single-shot guard so each task contributes at most one slow-event count, mirroring REQ-RTP-OBS-002.
- **Snapshot-not-stream readout.** Percentiles are computed on demand at read time, never streamed; the snapshot stays an immutable carrier.
- **Bounded cost.** Percentile computation is bounded by the fixed ring capacity (256), so it is `O(256 log 256)` — a constant independent of uptime, player count, or teleport history (REQ-RTP-OBS-003).
- **No platform imports in `rtp-core/metrics/`.** Enforced by the existing ArchUnit guard.

## Decision

### 1. Percentile readout on `PipelineHistogram` (additive)

`PipelineHistogram` gains read-only methods alongside the existing `mean()`:

1. `percentile(double p)` — `p` in `[0.0, 100.0]`; returns the value at the requested percentile over the currently-populated portion of the ring, or `Double.NaN` when no samples have been recorded.
2. A convenience accessor that returns the standard set **P50, P75, P90, P95, P99** plus `min` and `max` in a single immutable carrier, so a caller computing the full performance readout sorts the snapshot **once** rather than once per percentile.

Implementation contract:

- Reads take a point-in-time **copy** of the populated slots into a local `long[]`, sort the copy, and index by the nearest-rank method (`ceil(p/100 × n)`, 1-indexed, clamped to `[1, n]`). The ring itself is never mutated, sorted in place, or locked.
- Percentile reads tolerate concurrent `record(...)` writes: a copy taken during a write observes either the old or the new slot value, both valid samples. Temporal ordering within the copy is irrelevant to a percentile, exactly as it is irrelevant to the existing `mean()`.
- `record(long)` is **unchanged**: still a single `getAndIncrement` plus an `AtomicLongArray#set`. The wait-free write contract of ADR-032 is preserved verbatim.
- The window remains the same fixed 256-sample never-reset ring of ADR-032. Percentiles describe the same window the mean already describes.

This **supersedes only item 5 of ADR-032's Decision** ("Mean-only readout for v1") and the corresponding *Alternatives Considered* row. The ring shape, capacity, never-reset semantics, wait-free writes, and single-shot recording of ADR-032 are unchanged. ADR-032's rejection of a third-party percentile **sketch** (HdrHistogram, t-digest, KLL) still stands: percentiles here are computed by sorting the existing bounded ring, pulling in no new dependency.

### 2. Latency / backpressure audit (queue-aware, two distinct signals)

The audit splits into two **mutually exclusive per-teleport regimes**, chosen by whether the teleport bypassed the public queue. A teleport is treated as **immediate (unqueued)** when it was served without enrolling in the public wait queue — i.e. the `unqueuedFast` path in `QueueTask` (a custom-location request, or a sender holding `rtp.unqueued`). Otherwise it is a **queued** teleport served at-rate. The `TeleportPipelineTask` already knows which path produced it; the regime is carried as an immutable boolean on the task, set at construction, not re-derived at cleanup.

#### 2a. Slow-teleport threshold counter and `WARN` audit (immediate teleports only)

For **immediate/unqueued** teleports the recorded window contains no queue-wait time, so the elapsed duration is a faithful measure of pipeline latency and a fixed threshold is meaningful:

1. **Config key** `performance.yml > slowPipelineThresholdMs` (long, milliseconds). Default `5000`. A value `<= 0` **disables** this audit entirely (no comparison, no log, no counter increment) so operators who do not want the log noise pay nothing.
2. At the existing single-shot recorder site in `TeleportPipelineTask.runCleanup` (the `pipelineHistogramRecorded.compareAndSet(false, true)` branch that already calls `RTP.metrics.pipelineHistogram().record(elapsedMs)`), when the audit is enabled, **the teleport was immediate/unqueued**, and `elapsedMs > slowPipelineThresholdMs`:
   - increment a process-wide `slowPipelineCount` (`AtomicLong`/`LongAdder`) owned by `CoreMetrics`, and
   - emit `RTP.log(Level.WARNING, ...)` identifying the elapsed time, the threshold, and the player/region context already available at that site.
3. Queued teleports are **never** counted by `slowPipelineCount` and never emit this `WARN` — their elapsed time is contaminated by at-rate queue wait and would produce false positives. (They still contribute their sample to the `PipelineHistogram` exactly as before; only the *threshold audit* is gated. The percentile/mean readout is unchanged, since for the distribution the queue-wait inclusion is a pre-existing ADR-032 property, not introduced here.)
4. The counter is **cumulative since process start** (consistent with the never-reset histogram and the other cumulative counters in `METRICS_PLAN.md`); operators compute deltas across reads.
5. `slowPipelineCount` and the resolved `slowPipelineThresholdMs` are surfaced on `RTPMetricsExtension` so they travel with `Metrics.snapshot()` like the other RTP-specific counters.

This is **not** an S-004 failure (a slow teleport may still succeed); the `WARN` log is an operational audit of degraded latency, deliberately at the same severity floor S-004 mandates for failures so that a single log filter catches both classes.

#### 2b. Queue-growth threshold counter and `WARN` audit (queued path)

For the **queued at-rate** path the meaningful degradation signal is the wait queue outgrowing the rate at which locations are served, i.e. backpressure. This is surfaced as a separate, queue-depth-driven audit that does **not** depend on any single teleport's latency:

1. **Config key** `performance.yml > queueGrowthWarnThreshold` (int, number of players waiting). Default `0` = **disabled** (opt-in, since acceptable queue depth is highly server- and rate-dependent). A positive value `N` arms the audit.
2. The existing snapshot already carries `queueDepth` (total `RegionQueueManager.playerQueue` size summed across regions). When the audit is armed and a snapshot observes `queueDepth >= queueGrowthWarnThreshold`, the system increments a cumulative `queueGrowthWarnCount` and emits one `RTP.log(Level.WARNING, ...)` reporting the current depth and the threshold. To avoid log spam while the queue stays above the bound, the `WARN` is **edge-triggered**: it fires on the transition from below-threshold to at-or-above-threshold and re-arms only after the depth drops back below it. The counter still increments only on the arming edge.
3. The evaluation rides the existing metrics snapshot cadence (the periodic `CoreMetrics.snapshot()` publish), so it adds no work to the teleport hot path and no new scheduled task.
4. `queueGrowthWarnCount` and the resolved `queueGrowthWarnThreshold` are surfaced on `RTPMetricsExtension` alongside the slow-teleport pair.

Like §2a, this is an operational `WARN`-level audit, not an S-004 failure, and is cumulative since process start.

### 3. User-facing `/rtp info performance` readout

Per the maintainer's direction, the readout is surfaced under the existing **`/rtp info`** command (permission `rtp.info`) rather than as a new top-level `stats` verb:

- The `/rtp info` health-pipeline block renders recorded server-and-plugin state from a single cached `Metrics.snapshot()` plus `pipelineHistogram().percentiles()`: TPS 1m/5m/15m, MSPT + tick-budget utilisation, heap used/max, queue depth, pending teleports, **avg + P50/P75/P90/P95/P99 pipeline ms**, chunk-load backlog, DB latency, `memoryTrackerEntries`, the new `slowPipelineCount` (with its threshold, §2a), and the new `queueGrowthWarnCount` (with its threshold, §2b). Folia per-region samples are surfaced when present (`MetricsSnapshot.foliaRegions()`).
- The new percentile / slow-count / queue-growth fields ride the immutable snapshot extension, so the planned `/rtp info json` machine-readable flag (see `METRICS_PLAN.md > /rtp info Surface > Verbosity`) carries them automatically once that flag is implemented — no parallel schema.
- All user-facing strings route through `messages.yml` per REQ-RTP-F-013; the new keys are mirrored into every shipped locale per the *Locale Parity Maintenance* contract.

**Implementation note (2026-05-29):** per the maintainer's "append simple entries to `rtp info` where it fits" direction, the readout is implemented as additive rows folded into the existing `/rtp info` health-pipeline block (`infoPipelinePercentiles`, `infoSlowPipeline`, `infoQueueGrowth`) rather than a distinct `performance` sub-selector — no new command tree or permission node. The regime flag is realised as an immutable `immediateTeleport` boolean on `TeleportPipelineTask`, set `false` only by the 4-arg constructor used exclusively by the `Region.execute` queue-drain serve path and `true` for every other (immediate/unqueued/on-event) construction. The `/rtp info json` machine-readable flag is not yet implemented and remains deferred per `METRICS_PLAN.md`.

This readout reads `CoreMetrics.snapshot()`; it introduces **no new sampling**.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep mean-only; add no percentiles | Mean hides tail latency. The triage question ("how many players see a slow teleport, and how slow?") is unanswerable from a mean. The raw samples already exist; refusing to read them leaves the operator blind for no saving. |
| Pull in a percentile sketch (HdrHistogram / t-digest / KLL) | Already rejected by ADR-032 for the same reasons: a third-party dependency and an `O(log n)` write path for a readout served just as well by sorting a fixed 256-element copy at read time. The bounded ring makes the sort a constant-cost operation. |
| Maintain a running sorted structure so reads are `O(1)` | Moves cost onto the wait-free `record(...)` write path, violating ADR-032's wait-free write contract. Read-side sorting of a 256-element copy is cheap and keeps writes untouched. |
| New top-level `/rtp stats performance` verb | The maintainer chose to fold the readout under the existing `/rtp info` (either inline rows or an `info performance` sub-selector) to avoid a new command tree and a new permission node. Reuses `rtp.info`. |
| Track slow-teleport latency as an S-004 failure | A slow teleport is not a failed teleport; conflating the two would distort `pipelineFailureRate` and the failure breakdown. The audit is a distinct `WARN`-level latency signal with its own counter. |
| Apply one fixed latency threshold to *all* teleports (queued and unqueued alike) | The recorded window starts at `TeleportPipelineTask` construction, so a queued teleport's elapsed time includes at-rate queue wait. A single threshold would fire on essentially every queued teleport on a busy server — a backpressure condition mis-reported as per-teleport slowness, producing `WARN` spam that hides genuine slow unqueued teleports. Gating the latency audit to the immediate/unqueued path and using a separate queue-depth audit for the queued path keeps each signal honest. |
| Subtract queue-wait time so a latency threshold *can* apply to queued teleports | Requires threading a "dequeue timestamp" through the queue/pipeline boundary and re-defining the histogram sample, contradicting ADR-032's construction-time start. Even with a corrected latency, the operator-actionable signal for the queued path is depth/backpressure (add capacity or raise the serve rate), not a per-teleport latency the operator cannot influence per-player. Deferred as an additive future refinement if demand appears. |
| Rate-limit the slow-teleport `WARN` instead of splitting the signal | A blanket rate-limit would still misattribute the *cause* (queue backpressure vs a genuinely slow search) and would silently drop real slow-unqueued events to stay under the limit. The split addresses the root cause; the queue-growth audit is additionally edge-triggered to bound its own log volume. |
| Reset `slowPipelineCount` per snapshot read | Couples the producer to consumer cadence and races multiple consumers, the same defect ADR-032 calls out for reset-on-read histograms. Cumulative counter, consumers compute deltas. |
| Per-region percentile histograms | All current consumers want the fleet-wide distribution, matching ADR-032's process-wide aggregation decision. Per-region detail stays an additive future change. |

## Consequences

- **Positive:**
  - Tail latency (P50…P99) becomes a first-class operator-visible signal sourced from the same single histogram, with no new data collection and no change to the wait-free write path.
  - Slow teleports become auditable events (`WARN` log + cumulative counter) instead of a silent drift in the mean, consistent with the REQ-RTP-S-004 severity floor.
  - The latency audit is gated to immediate/unqueued teleports, so it no longer fires spuriously on every at-rate queued teleport; queue backpressure gets its own dedicated, edge-triggered `queueGrowthWarnCount` signal sourced from the existing `queueDepth`.
  - Percentile cost is bounded by the fixed ring capacity (`O(256 log 256)`), independent of uptime/players/history — satisfies REQ-RTP-OBS-003.
  - The slow-threshold audit is opt-out (`<= 0` disables) and defaults conservatively, so quiet servers incur no log noise.
  - `/rtp info performance` and `/rtp info json` reuse existing command, permission, snapshot, and locale machinery; no new subsystem.
- **Negative / Trade-offs:**
  - Each `/rtp info performance` / JSON read sorts a 256-element copy. This is off the teleport hot path (operator command cadence) and bounded, but it is non-zero work versus the mean-only readout.
  - Percentiles inherit ADR-032's window caveats: the effective wall-clock window depends on teleport throughput, and sub-millisecond completions register as `0`. The percentile readout is therefore meaningful only once `sampleCount()` is non-trivial; the readout surfaces `sampleCount` so operators can judge stability.
  - Two new baseline config keys (`slowPipelineThresholdMs`, `queueGrowthWarnThreshold`) and new `messages.yml` keys must be mirrored across every shipped locale (locale-parity maintenance cost).
  - The two audits answer different questions (a single slow unqueued teleport vs sustained queue backpressure); operators must understand the split. The `/rtp info performance` readout labels each counter with its threshold to make the distinction self-documenting.
  - The queued path retains no per-teleport latency alarm. An operator who wants to know a specific queued teleport was slow must read the percentile/mean readout (which still includes queued samples) rather than expecting a `WARN`. This is an accepted trade-off: per-queued-teleport latency is dominated by queue wait the operator tunes via rate/capacity, not per-player.

## References

- [ADR-032](ADR-032-teleport-pipeline-latency-histogram.md) — the histogram contract this ADR extends; specifically supersedes its Decision item 5 ("Mean-only readout for v1") and the matching *Alternatives Considered* row, leaving every other ADR-032 contract intact.
- [`docs/dev/METRICS_PLAN.md`](../dev/METRICS_PLAN.md) — *Metric Catalogue (v1)* (new `pipelineMsP*`, `slowPipelineThresholdMs`, `slowPipelineCount` rows), *`/rtp info` Surface* (the `performance` sub-selector), *Open Items / Follow-Ups* (percentile open item resolution), and phasing.
- [`docs/dev/REQUIREMENTS.md`](../dev/REQUIREMENTS.md) §1.8 — REQ-RTP-OBS-004 (percentile readout), REQ-RTP-OBS-005 (slow-teleport audit, immediate/unqueued only), and REQ-RTP-OBS-006 (queue-growth audit).
- REQ-RTP-S-004 (no silent failure), REQ-RTP-F-013 (configurable user messages), REQ-RTP-OBS-001/002/003 (non-blocking snapshot, single-sample recording, bounded sampling cost).
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/metrics/PipelineHistogram.java`, `CoreMetrics.java`, `RTPMetricsExtension`; `rtp-core/.../tasks/teleport/TeleportPipelineTask.java` `runCleanup` recorder site and the `unqueuedFast`/immediate-vs-queued regime flag carried from `rtp-core/.../selection/region/QueueTask.java`; `rtp-core/.../commands/info/InfoCmd.java`.
- [metrics-api-ADR-001](../../metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md) — extension-slot pattern that carries `slowPipelineCount` on `RTPMetricsExtension`.
