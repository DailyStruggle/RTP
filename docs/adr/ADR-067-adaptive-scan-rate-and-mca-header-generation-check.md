# ADR-067 — Automatic PRESCAN, Adaptive Scan Rate Control, and `.mca`-Header Generation Check

**Status:** Proposed
**Date:** 2026-06-14

## Context

Fleet bStats data shows the aggregate generation success rate is below 50%, with only ~5% of servers reaching 99%+, and the dominant failure cause is `vert` (no safe vertical position found). This matches the case where an operator never runs `/rtp scan`: with no pre-computed bad-location bitmap, the vertical safety check brute-forces every candidate at teleport time. The scan is effectively mandatory for a healthy success rate but is shipped as an opt-in command, so the default first-run experience is a >50% failure rate.

The intended fix is to make scanning run automatically (auto-scan), which raises the question of *how fast* an automatic background scan may run without harming the live server. A fixed positions-per-tick rate is a guess made at config time; it either starves the server on slow hardware or under-utilises a pregenerated world (where a full anvil-only scan reaches 500–2000 cps and completes in seconds). We now have the runtime signals to do better:

- `ChunkLoadProfile` (ADR-related metrics work) tracks the running-minimum "floor" load time separately for already-generated vs ungenerated chunks, plus cumulative totals — a per-class cost estimate.
- Whole-server MSPT and the existing `queue_depth_pressure` / `chunk_load_backlog_pressure` signals expose tick headroom and pipeline saturation.

A key correction emerged during design: we cannot predict, before issuing a batch, which positions are generated, and `.mca` *file* presence is region-granular (a 32×32 chunk region whose columns may be heterogeneously generated). The generation status of an individual chunk column must be read either from the server API (`World.isChunkGenerated`) or from the region file's 4 KB sector-offset table. `ScanTask` already bins candidate positions by Minecraft region file (the PR-14 "region-file bin-and-batch" pass keyed on `(x>>5, z>>5)`) and already holds the raw `.mca` bytes for the current bin hot in `AnvilRegionByteCache` (16-entry LRU, ~1.0 hit rate within a bin). The per-chunk generation check is therefore a near-free in-memory lookup into bytes the scan already has, rather than a per-position API call (Option B below).

Two further constraints shape *what kind* of automatic scan is acceptable. First, full-load scanning (`PHASE_FULLSCAN`) force-loads and, on ungenerated terrain, generates chunks; running it automatically across a large RTP radius would force a full pregeneration of that radius, which is impossible to do without a major disk-space cost. An automatic scan therefore must be PRESCAN-only (anvil probe of terrain that already exists on disk) and must never promote to FULLSCAN. Second, beyond a scheduled background sweep, safety verdicts can be harvested opportunistically: whenever the server loads a chunk for its own reasons, that chunk's column data is already resident in memory, so verifying it (and a small bounded neighbourhood of already-resident chunks, clipped to the region's boundary) costs no disk I/O and no extra chunk load. Every part of this behaviour must be operator-configurable.

## Decision

Populate the bad-location safety map through three disk- and generation-neutral tiers, none of which force-generates terrain, governed by an adaptive per-tick budget and the cached `.mca`-header generation check. Every tier and every threshold below is exposed as a `performance.yml` key (point 5) so an operator can tune or fully disable each independently.

**Tier 1 — Scheduled automatic PRESCAN (`autoScan`).** When a region has no `.scan` coverage or coverage below a configurable threshold, a background scan starts automatically. It is PRESCAN-only: it sweeps the configured radius reading only `r.X.Z.mca` files that already exist on disk, records safe/unsafe verdicts for generated columns, and **leaves ungenerated columns unmarked** (so live verification or a later manual scan can still resolve them). It never promotes to FULLSCAN. The existing PRESCAN→FULLSCAN promotion paths in `ScanTask` (the up-front GENSCAN skip when `ungen > 0`, and the mid-sweep `compareAndSet(PHASE_PRESCAN, PHASE_FULLSCAN)`) are suppressed for an auto-scan unless the operator opts in via `autoScanFullLoad`. Manual `/rtp scan` retains its authoritative full-load behaviour unchanged.

**Tier 2 — Opportunistic on-load PRESCAN (`autoScanOnLoad`).** When the server loads a chunk for its own reasons, the already-resident column data is verified for free (S-005-safe: the chunk is already loaded, nothing is force-loaded or generated). The search optionally expands to a bounded neighbourhood of chunks that are *also* already resident (`autoScanOnLoadRadius`), never force-loading a neighbour, and is capped per load event (`autoScanOnLoadMaxColumns`). Verdicts are recorded only for columns inside the region's shape range / world border — out-of-region columns are clipped by a cheap range test before any safety check. Columns with a fresh existing verdict are skipped.

**Tier 3 — Live verification at teleport.** The existing fallback for columns still unknown after Tiers 1 and 2; unchanged.

The adaptive rate machinery below governs Tier 1 (and is the path a manual FULLSCAN is throttled by). Tier 2's per-event work cap is the analogous bound for the on-load path, but its budget is trivially small because it performs no I/O.

1. **Per-tick budget (two orthogonal throttle axes, combined by minimum).**
   - *MSPT headroom (reactive):* `headroom = max(0, msptBudget - currentMspt)`; the scan may consume `scanBudgetMs = headroom * scanMsptShare`. At or above the budget the scan pauses for that tick.
   - *Queue / chunk-load pressure (reactive):* when queue depth exceeds a high watermark the scan trickles (one position); between watermarks it scales down proportionally.
   - The effective per-tick allowance is the minimum of the two axes, never below one position (progress guaranteed) and never above an operator-configured ceiling.

2. **Check-then-allocate per batch (predictive pre-commit bound).** Inside the existing per-bin dispatch loop, for each candidate position about to be issued: read its generation status, pick the matching `ChunkLoadProfile` floor (generated vs ungenerated, with a conservative bootstrap default until samples exist), and stop allocating once the summed predicted cost would exceed the tick budget. Because the floor is a lower bound on real cost, the estimate errs toward doing less, structurally preventing scan-induced MSPT spikes rather than merely dampening them.

3. **Generation check via cached `.mca` header (Option B).** The per-chunk generated/ungenerated decision reads the region file's sector-offset table: the 4-byte entry at offset `4 * ((cx & 31) + (cz & 31) * 32)` is zero for an unwritten (ungenerated) column and non-zero for a generated one. The raw bytes come from `AnvilRegionByteCache`, which is already hot for the current bin. The decode lives behind an `anvil-api` helper (e.g. `isChunkPresent(byte[] regionBytes, int cx, int cz)`) so `rtp-core` carries no raw `.mca` layout constants and reaches it the same way it already reaches the anvil subsystem (ADR-026).

4. **PRESCAN vs FULLSCAN.** PRESCAN (anvil prefilter, no chunk loads, S-005-safe) does not contribute to chunk-load pressure and may run at near-full rate on any server; its budget is governed by measured anvil I/O cost per position. FULLSCAN loads chunks and competes with live teleports, so it is the path the chunk-load-cost budget throttles.

5. **Everything configurable; operator policy, not per-server tuning.** All behaviour is exposed through `performance.yml` keys, each with a default that reproduces current behaviour when the feature is off, so the operator sets a policy once and the controller measures the rate at runtime:
   - Auto-scan triggering: `autoScan` (enable scheduled background PRESCAN), `autoScanCoverageThreshold` (coverage fraction below which a region auto-scans), `autoScanFullLoad` (allow an auto-scan to promote PRESCAN→FULLSCAN, i.e. force-generate terrain; off by default).
   - Opportunistic on-load: `autoScanOnLoad` (enable on-load harvesting), `autoScanOnLoadRadius` (already-resident neighbourhood radius in chunks), `autoScanOnLoadMaxColumns` (hard per-load-event work cap).
   - Adaptive rate (Tier 1 / manual FULLSCAN): `scanAdaptive`, `scanMsptBudget`, `scanMsptShare`, `scanQueueHighWatermark`, `scanQueueLowWatermark`, `scanMaxPositionsPerTick`.

Scope: `rtp-core` (`ScanTask` rate logic + the PRESCAN-only auto-scan trigger and the on-load harvesting hook, reading the existing profile singletons) plus an `anvil-api` `isChunkPresent` helper and the `performance.yml` keys. The on-load tier consumes a chunk-load notification the platform adapters already surface; no `rtp-api` interface change and no new force-load path.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Fixed positions-per-tick rate (status quo) | A config-time guess: starves the server on weak hardware and wastes a pregenerated world's headroom; the fleet data shows the resulting opt-in scan is rarely run, leaving a >50% failure rate. |
| Automatic FULLSCAN (force-generate the whole radius) | Generating a large RTP radius up front imposes a major, often prohibitive disk-space cost; auto-scan must stay PRESCAN-only (existing terrain on disk) and leave force-generation to explicit operator action. |
| Scheduled PRESCAN only, no on-load harvesting | The scheduled sweep covers the whole radius but the far reaches (exactly where RTP sends players) may rarely be loaded; the opportunistic on-load tier fills hot areas and self-heals stale verdicts at zero marginal cost. Kept as a complement, not a replacement. |
| On-load harvesting only, no scheduled sweep | Only covers terrain that happens to load naturally; the cold far radius would never be verified, so the `vert` failure rate would stay high there. |
| Predict generated/ungenerated for a whole batch before issuing | Impossible: generation status is per chunk column and `.mca` presence is region-granular and heterogeneous; the status of a specific column is not knowable until checked. |
| `World.isChunkGenerated(cx,cz)` per position (Option A) | Correct but one native call per candidate; redundant given the scan already holds the region-file bytes hot in `AnvilRegionByteCache`, where the same answer is an O(1) in-memory read. |
| Throttle on whole-server MSPT alone | MSPT is whole-server tick utilisation, not pipeline saturation; a low-MSPT server with a saturated chunk-load queue would still extend player wait times. The queue-pressure axis covers this independently. |
| Throttle on a single combined cost figure (no gen/ungen split) | Hides the dominant cost driver (terrain generation), so the predictive bound cannot account for a batch that happens to hit ungenerated terrain; the per-class floor is what makes the pre-commit bound accurate. |

## Consequences

- **Positive:** Auto-scan runs safely on a live server without operator rate-tuning and without force-generating terrain, so it costs no extra disk space; on a pregenerated world it finishes in seconds (invisible), on an ungenerated world it backs off automatically and verifies only what already exists. The on-load tier harvests verdicts for free from chunks the server loads anyway and self-heals staleness. The predictive bound prevents scan-induced MSPT spikes structurally rather than after the fact, and the generation check is essentially free, reusing the existing region-file binning and cache. The three tiers degrade gracefully: unknown columns simply fall back to live verification (current behaviour), never worse than today.
- **Negative / Trade-offs:** Adds a small per-position in-memory lookup and a per-tick budget computation to the scan hot path, and a bounded per-load-event check to the chunk-load path. Introduces a larger operator-facing `performance.yml` surface (auto-scan trigger, on-load, and adaptive-rate key groups; mitigated by safe defaults and per-group off switches). The floor estimate is inaccurate until `ChunkLoadProfile` accumulates samples, so early scan ticks rely on conservative bootstrap defaults. PRESCAN-only auto-scan cannot verify ungenerated terrain, so a deliberately-ungenerated world keeps relying on live verification for the unloaded far radius. `isChunkPresent` reflects state at allocation time; a column generated by another player between check and load makes the actual cost lower than predicted (the safe direction).

## References

- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tasks/ScanTask.java` — PR-14 region-file bin-and-batch (`key = (x>>5)<<32 | (z>>5)`), per-bin dispatch loop.
- `api/anvil-api/src/main/java/io/github/dailystruggle/rtp/anvil/AnvilRegionByteCache.java` — cached raw `.mca` bytes (source of the sector-offset table).
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/metrics/ChunkLoadProfile.java` — per-class (generated/ungenerated) floor + totals.
- [ADR-016](ADR-016-anvil-subsystem.md) — anvil read-only subsystem (PRESCAN prefilter, S-005 safety).
- [ADR-026](ADR-026-external-hook-api-surface.md) — how `rtp-core` reaches the anvil subsystem.
- Prohibition S-005 (no chunk loading on the main thread): `docs/dev/REQUIREMENTS.md section 3`.
