# ADR-109 - Measured-Cost Probe-First Gate

**Status:** Proposed
**Date:** 2026-10-08
**Extends:** [ADR-016](ADR-016-anvil-subsystem.md) (Anvil read-only subsystem; center-column probe stage)
**Related:** [ADR-110](ADR-110-purpose-gated-live-loads.md) (on speculative fill, native-load time is excluded from this governor's samples)

## Context

The [ADR-016](ADR-016-anvil-subsystem.md) pipeline runs a center-column probe (`RTPWorld.probeChunkColumn`) on every candidate before the full chunk load, so cheap rejects (ocean, lava, wrong biome) skip the load. The probe and the full load both open, read and inflate the same chunk entry; only the decode differs. The probe therefore saves work only on the candidates it rejects, and every accepted candidate pays for it a second time.

Let `p` be the probe reject rate, `Tp` the probe cost, `Tf|probe` the full-load cost after a passing probe, and `Tf|direct` the full-load cost without one. Probing first costs `Tp + (1 - p) * Tf|probe` per candidate; skipping costs `Tf|direct`. Neither side is a constant:

- `p` depends on the region's terrain, shape, biome filter and vertical settings, and on the path: queued locations (`QueueTask`) were already verified, so a probe there rarely, if ever, rejects.
- `Tp` and `Tf` depend on the OS (a Windows open with real-time scanning costs about 8x a positioned read, `AnvilFileWaitSimulationTest`), page-cache warmth, coalescing (`ColumnProbeCoalescer`), and whether the full load comes from the Anvil view or a live load.

A hardcoded threshold or config key would be wrong for most servers some of the time.

## Decision

1. **One governor per (region, path).** `ProbeFirstGovernor` (`rtp-core`, `selection.region`) decides per candidate whether the probe runs. Paths: `fill` (`PregenTask`: queue fill and the empty-queue fallback) and `consume` (`QueueTask`). `ScanTask` always probes: recording rejects is its purpose.
2. **Decision from measured cost only.** All four terms are wall-clock moving averages (EWMA, ~256 samples). Loads are measured from dispatch to completion, so pool queueing and live-load cost are included. A coalesced probe is charged its drain's duration divided by the group size `g` (capped by its own wall time), never its wait in the coalescer queue. One drain pays one region-file open, so `Tp` is fitted as `Tp(g) = perChunk + open / g` (least squares on `1/g`, open term never negative, plain mean until `1/g` varies) and evaluated at the mean `1/g` seen while probing is the default, frozen while skipping; the losing arm is not judged at the group sizes its sparse trials happen to get. The mode flips only when `costProbe - costDirect` stays beyond 3 standard errors for 128 consecutive samples (one EWMA correlation time), and never within 256 samples of the previous flip. No thresholds are configurable; none are needed.
3. **Starts as before.** The gate starts in `PROBE` and probes every candidate until the probe arm has 32 samples, then skips 1 in 4 until the direct arm has 32.
4. **Exploration floor.** The losing arm keeps running on a share of candidates `~1 / (1 + |z|)`, bounded to `[1/32, 1/4]`, so disk, cache or terrain drift can flip the decision back. In `SKIP` the share accrues as credit and each trial probes up to 8 candidates of one region file (`ProbeFirstGovernor.binKey`) within 64 decisions, so trial probes can share a drain and span a range of `g`; the long-run share is unchanged.
5. **Reset.** Governors for a region are dropped when its scan completes (scan-time cache warmth, coalescing and learned rejects no longer apply) and all governors on region reload.
6. **The probe may only reject.** A probe never accepts a candidate; the full check stays authoritative (S-001). Skipping the probe therefore changes cost, never outcome validity. A `null` result from `VerticalAdjustor.adjustFromProbe` means miss/unknown on every path (the full load decides).
7. **Observable.** Each governor is published as a `ProbeGovernorRow` in `RTPMetricsExtension.probeGovernors` (mode, `p`, `Tp` at the default group size, `Tf|probe`, `Tf|direct`, both costs, `z`, skip share, flips, sample counts). Flips and a 60 s per-governor summary log at INFO with the `[RTP][probe-gov]` tag (adding raw `Tp`, default `g` and the fitted open cost); the summary is temporary verification output and may be lowered to FINE once the gate is accepted.

## Consequences

- Where the probe rejects little (queued locations, mostly-land worlds, Windows opens), candidates skip it and avoid the double read; where it rejects a lot (ocean-heavy worlds, strict biome filters), behaviour is unchanged.
- The probe checks only the center column, so a skipped probe can let through a candidate it would have rejected; the full check then rejects it at full-load cost, which the measurement already accounts for.
- On the `consume` path the probe currently cannot reject, so the governor converges to `SKIP` with the 1-in-32 floor. If that holds in stress runs, removing the consume probe is a follow-up.
- Governors are process-local and in-memory; a restart re-learns within a few hundred candidates.
- Status moves to Accepted once a stress run shows the gate converges and MSPT / throughput do not regress.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Always probe (status quo) | Pays a second read and inflate on every accepted candidate even where `p` is near zero. |
| Never probe | Loses the cheap reject on ocean- and lava-heavy worlds, where it is a clear win. |
| Config key or fixed `p` / ms threshold | The break-even moves with OS, disk, cache warmth and terrain; any constant is wrong somewhere. |
| Probe, then promote the decoded column into the full view | Removes the double inflate rather than choosing between paths; larger change to `anvil-api`. Compatible with this gate and still open. |
