# ADR-032 — Teleport Pipeline Latency Histogram: 256-Sample Wait-Free Ring, Never Reset

**Status:** Proposed
**Date:** 2026-05-05

## Context

`METRICS_PLAN.md` introduces a portable metrics SPI in `rtp-core`. Most of its catalogue wraps signals the platform already publishes (TPS, MSPT, heap, player count). One field is **RTP-specific** and has no platform analogue: `avgPipelineMs` — the rolling mean completion time of `TeleportPipelineTask`, the per-attempt teleport pipeline (shape → chunk → vert → biome → safety) defined in `rtp-core`.

This signal is the only direct measurement of how long an RTP teleport actually takes end-to-end. It feeds three downstream consumers, all of which are now in the plan of record:

- `/rtp info` Health — pipeline group (`METRICS_PLAN.md > /rtp info Surface`).
- bStats `pipeline_latency_buckets` chart (`METRICS_PLAN.md > bStats Integration`).
- The `BackendStatePublisher` selector input in `MULTI_SERVER_PLAN.md` (Phase 2 cross-plan consumer).

The `Metrics`/`MetricsSnapshot`/`PipelineHistogram` types landed in Phase M0 (2026-05-01) under `rtp-core/.../common/metrics/`. The histogram design — **256-sample ring buffer, never resets, wait-free writes, mean-only readout** — was shipped as the working straw-man, with `METRICS_PLAN.md > Open Items` flagging the window length and reset semantics for confirmation during the M1 review. The implementation in `PipelineHistogram.java` matches that straw-man.

Constraints driving the decision:

- **S-005 spirit (no main-thread blocking).** `TeleportPipelineTask.runCleanup` runs on whatever thread completes the pipeline — on Folia that may be a region thread, on Paper/Spigot it may be a tick thread, and on Fabric it is the server thread. The recorder must not allocate, lock, or contend.
- **Idempotent recording.** `runCleanup` is invoked from multiple exit paths (success, failure, cancel, exception). The wiring must record exactly one sample per `TeleportPipelineTask` regardless of how it terminates. The current code uses a `pipelineHistogramRecorded` guard; the histogram itself must tolerate the resulting once-per-task call pattern.
- **Snapshot-not-stream readout (`METRICS_PLAN.md > Goals`).** Callers compute deltas. The histogram exposes a single `mean()` plus counters, never an event stream.
- **Memory cost bounded.** The snapshot must remain cheap to publish at 1 Hz under proxy mode (`METRICS_PLAN.md > Open Items > Memory cost of MetricsSnapshot`).
- **Process-wide aggregation.** A single `CoreMetrics` instance lives behind `RTP.metrics`; per-region histograms were rejected as needless complexity for v1 and would not match the bStats / proxy consumer shape (both want a fleet-wide single number).

## Decision

The teleport pipeline latency signal is recorded by a single process-wide `PipelineHistogram` with the following fixed contract:

1. **Capacity: 256 samples.** Power-of-two so the ring index reduces to a bitmask. Sized to give the rolling mean roughly the last few minutes of teleport activity on a busy server (hundreds of teleports/min) and roughly the last hour on a quiet one — both scales are useful and neither requires tuning.
2. **Never resets.** No periodic clear, no per-snapshot reset, no sliding-time-window eviction. Old samples are overwritten only when a new sample arrives at the same ring slot. This eliminates a class of "the dashboard went blank after the snapshot tick" bugs and keeps the readout meaningful immediately after process start.
3. **Wait-free writes.** `record(long)` is a single `AtomicLong#getAndIncrement` plus an `AtomicLongArray#set`. No locks, no allocations, no CAS retry loops. Safe to call from any thread, including Folia region threads and Fabric server-tick callbacks.
4. **Negative samples clamped to zero.** Defensive: clock skew on `System.currentTimeMillis()`-based deltas can produce small negatives. Clamping is preferred over rejecting, so the sample count stays a faithful tally of completions.
5. **Mean-only readout for v1.** `mean()` returns the arithmetic mean of the populated portion of the ring (or `NaN` when empty). Percentiles (p50/p95/p99) are deliberately deferred — they require a sortable snapshot that costs O(n log n) per readout and are not consumed by any v1 audience. *(Amended by [ADR-053](ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md), 2026-05-29: percentiles P50/P75/P90/P95/P99 are now exposed as an additive read path that sorts a point-in-time copy of the bounded ring; the wait-free write contract, capacity, and never-reset semantics below are unchanged.)*
6. **Two counters exposed alongside the mean.** `sampleCount()` (capped at capacity, drives "is the mean stable yet?") and `totalRecorded()` (uncapped, drives long-run sanity checks and the bStats `<100/100-500/500-2000/2000+` bucketisation cadence).
7. **Recorder wiring is single-shot per task.** `TeleportPipelineTask.runCleanup` uses a `pipelineHistogramRecorded` flag so each task contributes exactly one sample. Multiple `runCleanup` invocations from concurrent exit paths shall not double-count.
8. **Sample value is wall-clock milliseconds** from the task's enqueue timestamp to the first `runCleanup` invocation. Wall-clock is preferred over `System.nanoTime()` because the published unit (`avgPipelineMs`) is milliseconds and the rolling resolution (1 ms) is well above the noise floor of a teleport pipeline (typically tens of ms minimum due to async chunk loads).

This ADR ratifies the implementation already in `rtp-core` (`PipelineHistogram.java`, M0) and closes the corresponding open item in `METRICS_PLAN.md`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Sliding-time-window histogram (e.g., last 5 min) | Requires either timestamped samples (extra `long` per slot, doubling memory) or periodic eviction (background task, extra threading surface). The fixed-count window approximates a time window adequately on the loaded servers that care about the metric, and degrades gracefully (longer effective window) on quiet ones. |
| Per-region `PipelineHistogram` | All v1 consumers (`/rtp info` Health, bStats, proxy publisher) want a single fleet-wide number. Per-region detail can be added later as an additive `MetricsSnapshot` field without changing the process-wide histogram. |
| Reset on every `MetricsSnapshot` read | Couples the producer to consumer cadence; multiple consumers (info command, bStats, proxy publisher, `rtp test full`) would race to "drain" the histogram. The never-reset model lets every consumer read the same stable mean. |
| Percentile sketch (HdrHistogram, t-digest, KLL) | Pulls in a third-party dependency and an O(log n) write path for a v1 readout that nobody requested. Revisit only if a consumer explicitly needs p95/p99 (e.g., a future SLA report); recorder shape lets us swap the internals without changing `Metrics.snapshot()`. |
| `LongAdder`-backed running sum + count (cumulative mean since boot) | Cheap, but the mean drifts toward an all-time average and stops reflecting current health within an hour of uptime — exactly the failure mode the dashboard is meant to detect. |
| Lock-free MPMC ring with read-side ordering guarantees | Strictly stronger than v1 needs. The mean is order-independent; samples within a snapshot do not need to be temporally ordered. The simpler `AtomicLong + AtomicLongArray` design is sufficient and documented as such. |

## Consequences

- **Positive:**
  - Teleport pipeline latency is a first-class signal across `/rtp info`, bStats, and (Phase M3) the proxy selector — all reading from one source of truth.
  - Wait-free recorder is safe on every supported runtime, including Folia region threads (S-005 spirit) and Fabric server-tick callbacks.
  - Bounded memory: 256 × 8 bytes = 2 KiB for the ring plus a couple of `AtomicLong`s. Trivially cheap to publish at 1 Hz.
  - Implementation already shipped (M0); this ADR ratifies the contract so future PRs cannot quietly drift (e.g., shrink the capacity, add a reset, swap to nanos).
- **Negative / Trade-offs:**
  - No percentiles in v1. A future operator request for "p95 teleport time" requires either a sketch swap or a parallel structure; the current shape does not support it. *(Resolved by [ADR-053](ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md): percentiles are read by sorting a copy of the existing ring — no sketch and no parallel structure, the write path is untouched.)*
  - Effective window length depends on teleport throughput. On servers with very low RTP usage the mean covers a much longer wall-clock window than on busy ones; the bStats bucketisation deliberately tolerates this.
  - Single process-wide histogram cannot answer per-region or per-world questions. Adding that later is additive but is a real future change.
  - Wall-clock millisecond resolution is coarse; a teleport completing in under 1 ms (cache hit, no chunk load) registers as 0 in the histogram. Acceptable: the buckets of interest are tens-of-ms and up.

## References

- [`docs/dev/METRICS_PLAN.md`](../dev/METRICS_PLAN.md) — *Metric Catalogue (v1)* row `avgPipelineMs`, *Phase M1* histogram wiring entry, *Open Items* "`avgPipelineMs` window length and reset semantics".
- [`docs/dev/MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md) — Phase 2 consumer (`BackendStatePublisher` / load-balancer selector input).
- [ADR-008](ADR-008-memory-tracker-active-gc.md) — `MemoryTracker` lifecycle for `TeleportPipelineTask`; the recorder fires from the same `runCleanup` exit paths.
- [ADR-015](ADR-015-stale-chunk-guard-countbound-pipes.md), [ADR-016](ADR-016-anvil-subsystem.md) — pipeline stages whose latency is being summed.
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/metrics/PipelineHistogram.java` — implementation (Phase M0, 2026-05-01).
- `rtp-core/.../TeleportPipelineTask` `runCleanup` — recorder wiring with `pipelineHistogramRecorded` single-shot guard.
- Tests: `PipelineHistogramTest`, `CoreMetricsTest`, `TeleportPipelineTaskPhaseTest#runCleanup_records_one_sample_into_pipeline_histogram`, `…_is_idempotent_for_pipeline_histogram`.
