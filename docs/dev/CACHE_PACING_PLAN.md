# Cache Pacing Plan — Dynamic `RegionCacheTask` Throttling

**Status:** Stage A landed 2026-04-23. Stages B and C are follow-ups.
**Owner:** `rtp-core` — `AsyncTaskProcessing`, `Region.execute`, `RegionCacheTask`.
**Related:**
- `docs/dev/QUEUETASK_PROBE_FIRST_PLAN.md` (Slice 1 probe-first fast path — the speedup this plan paces against).
- `docs/dev/BIOME_LOOKUP_PERF_PLAN.md` (upstream Stage 1 / Stage 2 model).
- ADR-015 (count-bound pipes), ADR-016 (Anvil subsystem).

---

## Background

After the `JumpAdjustor` tag-expansion fix and the `QueueTask.tryProbeFirstQueue`
probe-first gate (Slice 1 of `QUEUETASK_PROBE_FIRST_PLAN.md`), per-candidate
cache-fill cost dropped by roughly **1.5×–2.2×** steady-state. The filler now
rejects ~40–55% of candidates for ~20–40 ms probe cost instead of ~80–120 ms
full-load cost.

The global `performance.yml → period` knob previously defaulted to `100` ticks
(5 s) — chosen conservatively for the pre-probe cost profile. With the new
speedup that default leaves `unkeptLocations` chronically under-filled on fresh
worlds and over-idle on warm worlds.

This plan moves pacing from a single static period to a short default + a
runtime-adaptive skip counter that respects a tick-time budget ceiling.

---

## Stage A — Short default period **[Landed 2026-04-23]**

1. `rtp-plugin/src/main/resources/performance.yml` — `period: 100` → `period: 20`.
2. `rtp-plugin/src/main/resources/lang/es/performance.yml` — `periodo: 100` → `periodo: 20`.

Rationale: 20 ticks (1 second) is short enough to exploit the Slice-1 speedup
on warm worlds yet long enough to guarantee MSPT headroom for catch-up on
struggling ticks. `AsyncTaskProcessing` already clamps `if (period < size)
period = size`, so multi-region setups degrade gracefully toward "one region
per tick" without overrunning the budget. No code change required —
`AsyncTaskProcessing.run()` reads the yml value directly via
`perf.getNumber(PerformanceKeys.period, 0)`.

**Verification:** live scan — expect `unkeptLocations` deficit to close within
~seconds on warm worlds; MSPT must stay below `asyncAllottedTime` (50 ms
default). Roll back to `period: 100` if MSPT budget is exceeded on any tested
server profile.

---

## Stage B — Dynamic adjust via `cachePacing` block **[Planned]**

### Intent

Add a runtime feedback loop that:

- **Runs MORE** when `unkeptLocations` deficit is high *and* the probe fast-path
  is rejecting cheaply (high reject ratio ⇒ cheap candidates ⇒ safe to push).
- **Runs LESS** when deficit is satisfied (at/above `targetFillRatio`), the
  previous tick overran its budget, or server TPS drops below `minTPS`.
- **Respects `period` as the hard ceiling** — adaptive loop only inserts skips
  on top of the configured period, never decreases below it.

### Signals (all already measurable)

| Signal | Source | Used for |
|---|---|---|
| `unkeptLocations.size() / cacheCap` | `RegionCacheTask:113` | Fill-ratio error term |
| Tick wall-time vs `asyncAllottedTime` | `AsyncTaskProcessing` budget math | Overrun backoff |
| `probeReject / (probeReject + fullLoad)` | `JumpAdjustor` / `QueueTask` counters | Cheap-candidate headroom (Stage C refinement) |
| Server TPS vs `minTPS` (already configured) | `PerformanceKeys.minTPS` | Global backpressure |

### Config surface (`performance.yml`)

```yaml
# Dynamic Cache Pacing (adaptive throttling)
# When enabled, the cache-fill task skips additional ticks on top of `period`
# based on runtime signals (queue fill, tick overruns, server TPS).
# Disable to revert to strict-period behaviour.
cachePacing:
  enabled: true
  targetFillRatio: 0.8    # Keep unkeptLocations at 80% of cacheCap.
  maxSkipTicks: 8         # Upper bound on adaptive backoff (ticks).
  overrunBackoff: 2       # Ticks to skip after a tick exceeds asyncAllottedTime.
```

Backward-compatible default: `cachePacing.enabled: false` → behaves identically
to Stage A.

### Implementation sketch

1. `PerformanceKeys` additions: `cachePacingEnabled`, `cachePacingTargetFill`,
   `cachePacingMaxSkip`, `cachePacingOverrunBackoff`.
2. `Region` gets a per-region `AtomicInteger skipCounter` and
   `AtomicInteger skipFactor`.
3. `Region.execute(long)` — before the `cachePipeline.add(new RegionCacheTask(...))`
   line (~`Region.java:610`), consult `skipCounter`:
   - `skipCounter > 0` → decrement, skip the enqueue this tick.
   - `skipCounter == 0` → enqueue as today, then recompute `skipFactor`:
     ```
     ratio = unkeptSize / cacheCap
     error = targetFillRatio - ratio
     if error > +0.1 → skipFactor = max(0, skipFactor - 1)
     if error < -0.1 → skipFactor = min(maxSkip, skipFactor + 1)
     if lastTickOverran   → skipFactor = max(skipFactor, overrunBackoff)
     skipCounter = skipFactor
     ```
4. Overrun detection: `AsyncTaskProcessing.run()` already measures
   `currentAvailableTime - (System.nanoTime() - start)`; stash a per-region
   `volatile boolean lastTickOverran` in `Region` from that site.
5. Observational-mode `RegionCacheTask.observe(...)` branch uses the same
   counter — no separate cadence (matches `visitorEnabled` contract).

### Tests

- `cachePacing_disabled_mirrorsStageABehaviour` — flag off, skipCounter never
  increments.
- `cachePacing_highDeficit_decrementsSkipFactor` — unkeptLocations empty ⇒
  skipFactor trends to 0.
- `cachePacing_fullQueue_incrementsSkipFactor` — unkeptLocations at cap ⇒
  skipFactor trends toward `maxSkipTicks`.
- `cachePacing_budgetOverrun_forcesBackoff` — synthetic overrun ⇒ skipFactor
  ≥ `overrunBackoff`.
- `cachePacing_respectsPeriodCeiling` — adaptive skip never makes the task run
  more often than `period` permits.

### Risks

- **Oscillation.** `targetFillRatio ± 0.1` dead-band keeps skipFactor stable;
  if oscillation appears in practice, tighten to ±0.05 and/or add EMA on the
  ratio.
- **Per-region independence.** Each region keeps its own counter; crowded
  regions can still starve quiet ones. Mitigation: `AsyncTaskProcessing`
  already round-robins regions, so the counter only gates enqueue, not turn.
- **S-005 / thread safety.** All counters are `AtomicInteger`; no new chunk
  I/O; inherits the existing async-dispatch guarantees.
- **MemoryTracker.** No allocation changes — enqueue is the only gated op.

### Telemetry

Extend the existing `[DEBUG_LOG] ScanTask concurrency` log line (or introduce
a parallel `CachePacing` log) with `unkeptRatio`, `skipFactor`, `skipCounter`,
`overrunCount`. Required to watch the loop converge and to tune defaults.

---

## Stage C — Probe-reject ratio feedback **[Planned, optional]**

### Intent

Worlds where the probe fast-path rejects a high fraction of candidates (e.g.
flower-heavy biomes, ocean-adjacent regions) are paying mostly probe cost per
candidate — cheap work. Worlds where probe-accept dominates are paying full
chunk-load cost per candidate — expensive work. Pacing should account for this.

### Signal

Per-region `probeRejectRatio = probeRejects / (probeRejects + probeAccepts)`
sampled over a rolling window (1 s or 20 probes, whichever is larger) from
`JumpAdjustor` and `QueueTask.tryProbeFirstQueue` counters.

### Rule

Multiplicative factor on `skipFactor` decrement:

```
if probeRejectRatio > 0.5  → allow skipFactor to decrement faster (× 2)
if probeRejectRatio < 0.2  → dampen decrement (× 0.5)
```

### Implementation sketch

1. Wire `FailTypes.prefilter*` counters from `QueueTask` through to
   `Region` (currently lost on the probe-reject branch — `QUEUETASK_PROBE_FIRST_PLAN.md`
   Slice 2 adds these).
2. Maintain per-`Region` rolling accumulator (AtomicLong pair).
3. Consume the ratio in the `skipFactor` update in Stage B.

### Dependency

**Requires `QUEUETASK_PROBE_FIRST_PLAN.md` Slice 2 to land first** — the
probe-reject counters need to be wired before Stage C can read them.

### Risks

- Window tuning: too small ⇒ jitter; too large ⇒ slow adaptation. Start with
  1 s window, reassess with telemetry.
- Feedback coupling with Stage B — validate with a combined integration test
  that both loops converge together on a synthetic workload.

---

## Rollout order

1. ✅ Stage A — period default 100 → 20 (this change).
2. ⏳ Measure live: fill latency, MSPT budget, deficit convergence on warm +
   cold worlds. Roll back if MSPT regresses.
3. ⏳ Stage B — `cachePacing` block + adaptive skipFactor.
4. ⏳ `QUEUETASK_PROBE_FIRST_PLAN.md` Slice 2 (probe-reject telemetry).
5. ⏳ Stage C — probe-reject ratio feedback into Stage B loop.

Each stage is independently shippable and independently revertable via its
config flag.
