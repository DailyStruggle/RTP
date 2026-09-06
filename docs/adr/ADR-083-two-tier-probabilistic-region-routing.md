# ADR-083 - Two-Tier Hierarchical Cell Routing: Probabilistic Macro-Cell Selection to Intra-Macro-Cell Manifold

**Status:** Proposed - superseded as a decision by [ADR-084](ADR-084-layered-spiral-hilbert-rle-index.md); retained as an alternative considered
**Date:** 2026-09-05

> **Supersession note.** The measurements in sections 8a and 8b falsified this record's own Context premise (run count against compact-feature area fits `area^0.500`, i.e. perimeter-order) and showed the descriptor's compile cost growing as `radius^1.857`. [ADR-084](ADR-084-layered-spiral-hilbert-rle-index.md) decides a layered spiral-plus-Hilbert-RLE index instead, and explicitly rejects three positions taken below: the area-growth premise, `supportsExpand() = false`, and `PI_MIN` subsampling. Sections 8a and 8b remain the evidence of record.

## Context

`MemoryShape` ([ADR-001](ADR-001-archimedean-spiral-1d-mapping.md), [ADR-081](ADR-081-unified-blocked-biome-run-table.md)) maps an entire unitless cell domain onto a single continuous 1D Archimedean spiral. In that monolithic model:

1. Valid and invalid space is tracked globally as absolute run-length encoded (RLE) intervals over the 1D key.
2. Every cell in the domain is bound to an absolute global index produced by `xzToLocation(long,long)`.
3. A compact 2D feature is cut once per spiral revolution, so its run count scales with feature **area** rather than **perimeter**.

At large `range` values the run population grows faster than the information it carries, and reconciling fine-grained 2D marks against the global 1D curve dominates rebuild cost.

### Unitlessness is a hard constraint on this ADR

`MemoryShape` names no unit. `spatialResolution` is a coalescing distance in cells, `xzToLocation` / `locationToXZ` / `getRange()` operate on abstract integer cells, and `DistanceParameterTest.testSubspaceShapeRemainsUnitless` pins that contract. Callers (`Region`, `QueueTask`, `SubspaceShape`, `GroupPlacementDispatcher`) own the unit.

Therefore this ADR is stated entirely in **cells**, **micro-cells** and **macro-cells**. No block, chunk, region-file, `.mca`, `.linear`, or world-border figure is normative here. Caller-side unit mappings are collected in one non-normative subsection (section 9) and belong in [`DESIGN.md`](../dev/DESIGN.md), not in the shape.

## Decision

Introduce a **two-tier hierarchical cell router** as an alternative `MemoryShape` selection strategy:

1. **Tier 1 (macro):** draw a macro-cell with an integer weight proportional to its compensated valid-cell count.
2. **Tier 2 (micro):** draw a micro-cell inside that macro-cell, then a cell inside that micro-cell.

```
[Unitless shape domain, |domain| = getRange() cells]
                  |
                  v  Tier 1: integer prefix-sum draw over included macro-cells
   macroIndex  (weight = compensated valid-cell count)
                  |
                  v  Tier 2a: integer prefix-sum draw over micro-cell counts
   microIndex  (weight = contained-and-good cell count, M x M per macro-cell)
                  |
                  v  Tier 2b: uniform sub-draw over that micro-cell's good cells
   cell -> xzToLocation(x, z) -> existing global 1D key
```

### 1. Parameterization (all knobs, no magic constants)

| Symbol | Meaning | Constraint | Default |
|--------|---------|------------|---------|
| `r` | micro-cell edge, in cells | power of two, `r >= 1` | `1` |
| `M` | macro-cell edge, in micro-cells | power of two, `M >= 2` | `32` |
| `B = M * r` | macro-cell edge, in cells | power of two | `32` |

- Powers of two are required so all routing is shift/mask arithmetic (`c >> log2(r)`, `c & (r - 1)`), never division.
- **Micro-cell state is a count, not a bit.** Each macro-cell carries `short[] microValidCells` of length `M * M` (contained-and-good cell count per micro-cell, `0 .. r * r`, so `short` suffices while `r <= 128` since `r` is a power of two; `int[]` above that) plus a running prefix sum `int[] microPrefixSums` of the same length (running up to `validCells <= B * B`, which exceeds `short` when `B > 181`). There is no validity bitmask and no `Long.bitCount` / `numberOfTrailingZeros` path. Cost is `2 * M * M` bytes for counts plus `4 * M * M` bytes for prefix sums, which is the price of exact uniformity - see section 4a.
- The per-macro-cell valid counter is `int validCells` (cells, not micro-cells) and is by definition the last prefix-sum entry, so it is width-correct for any admissible `B` and needs no "0..1024 inclusive" caveat.
- `r`, `M` are fixed at shape construction and are part of the shape's identity for persistence and parity comparison.
- **Naming note:** The filename `...-two-tier-probabilistic-region-routing.md` retains the historical term "region" from early design drafts; it predates the unitless rewrite and refers strictly to abstract macro-cells, not Minecraft region files (`.mca`).

### 2. Normative uniformity rule (resolves the ADR-083 contradiction)

The prior draft simultaneously claimed strict areal proportionality *and* whole-macro-cell exclusion. Those are incompatible within one epoch. The normative rule is:

> **Horvitz-Thompson compensated subsampling.** A macro-cell `R_i` is included in the compiled table for epoch `e` with inclusion probability `pi_i`. If included, its integer weight is its valid-cell count divided by `pi_i`. The estimator is therefore unbiased over the epoch ensemble, and exactly uniform when `pi_i = 1`.

Exact integer form (no floating point):

```
PI_ONE      = 1 << 20                      // fixed-point 1.0, 2^20
pi_i        in [PI_MIN, PI_ONE]            // integer inclusion probability
PI_MIN      = PI_ONE >> 6                  // floor: no macro-cell may drop below 1/64
integerWeight(R_i) = (long) validCells(R_i) * PI_ONE / pi_i     // integer division, floor
```

Consequences that are normative and testable:

- **Long-run uniformity:** pooled over the epoch ensemble the draw distribution over cells is uniform; the acceptance criteria (unsubsampled chi-square, subsampled coverage band) are defined in section 8. Tier 2 is exactly uniform in every epoch (section 4a), so the only source of per-epoch bias is Tier 1 inclusion.
- **Per-epoch bias is bounded, not zero.** Within a single epoch an excluded macro-cell has probability zero. The `PI_MIN` floor bounds the worst-case per-epoch starvation to 63/64 of epochs for any non-empty macro-cell, which is the price paid for the memory ceiling.
- **`pi_i` is a memory-pressure control, not a quality control.** `pi_i = PI_ONE` for every macro-cell whenever the uncompensated table fits the configured resident budget; subsampling engages only above that budget, cheapest-yield first (lowest `validCells` first). Small domains therefore behave exactly like the unsubsampled table, which is the dynamic-subsystem-selection principle of [ADR-082](ADR-082-cache-conscious-b-plus-tree-primitive-arrays.md).
- **Zero weight is absolute:** `validCells == 0` implies `integerWeight == 0` implies the macro-cell is never drawn. This is the only I/O-avoidance guarantee the shape makes; whether it saves a file open is the caller's concern.

### 3. Determinism primitives (concrete, so two implementers agree bit for bit)

- **Mixer:** SplitMix64 finalizer, exactly:

  ```
  long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
  ```

- **Inclusion test (systematic / rotational phase sampling):**
  Instead of independent Bernoulli trials (which fail to guarantee inclusion over small horizons), macro-cells rotate deterministically through an integer period:

  ```
  int period_i = PI_ONE / pi_i;                                               // in [1, 64]
  long cellSeed = mix(mix(seed) ^ (macroX * 0x9E3779B97F4A7C15L)) ^ (macroZ * 0xC2B2AE3D27D4EB4FL);
  int phase_i  = (int) Math.floorMod(mix(cellSeed), (long) period_i);        // in [0, period_i - 1]
  boolean included = (Math.floorMod(epoch, (long) period_i) == phase_i);
  ```

  This yields **exactly one inclusion every `period_i` epochs, guaranteed**, making "at least once per 64 epochs" an exact invariant while decorrelating neighbouring macro-cells via the `mix`-derived phase. The inclusion probability remains exactly `1 / period_i` (Horvitz-Thompson unbiased) with zero Bernoulli sampling variance across epochs.
- **Bit widths:** `seed`, `cellSeed`, `epoch`, and `macroPrefixSums` are `long`; `pi_i`, `validCells`, macro indices, and `microPrefixSums` are `int`. Macro prefix sums are `long` because `integerWeight` is compensated and can exceed `int`, whereas micro prefix sums are bounded by `validCells <= B * B` and fit in `int`.
- **Epoch advance & compile pulse definition:** `epoch` is a monotonic `long` incremented by exactly one on each successful `flushAndRebuild(...)` that publishes a new descriptor snapshot. A "compile pulse" is precisely an execution of `flushAndRebuild(spatialResolution)`. Because `maybeFlushAndRebuild` fires roughly every `MAX_PENDING_BEFORE_REBUILD = 256` pending marks, epochs advance quickly under heavy mark load and stay constant on an idle shape; the coverage guarantee is therefore mark-rate-dependent, not wall-clock dependent. Wall clock, standalone periodic timer ticks, and query counts are explicitly **not** inputs.
- **In-flight consistency:** a selection reads one immutable snapshot (section 7) and therefore observes exactly one `epoch`, one inclusion set, and one prefix-sum array. Snapshots are never mutated after publication.
- **No floating point on the selection path.** `rand()` is overridden wholesale, so the inherited `sample(double)` power curve (`Math.pow(rng().nextDouble(), weight)`) is not executed. The `weight` knob is consequently **not declared** by this shape; declaring it would advertise a distribution knob the router does not honor. `supportsExpand()` returns `false` for the same reason - `expand` adjusts a 1D range this shape does not sample.

### 4. Curve choice: Z-order, not Hilbert

The prior draft named a Hilbert curve without fixing a variant, orientation or base case, which is not implementable as written. Under the unitless reading the justification for Hilbert also disappears:

- Micro-cell locality only matters to whichever **caller** maps micro-cells onto an I/O unit. The shape itself never does I/O.
- Tier 2a draws by prefix-sum search over per-micro-cell counts, so the curve affects only the index-to-coordinate mapping, not selection cost or distribution.
- Z-order (Morton) is a pure bit interleave (`microIndex = interleave(mx, mz)`), is trivially invertible, and has no orientation ambiguity.

**Normative:** micro-cell numbering is Morton order over `(mx, mz)` with `mx` in the even bit positions and `mz` in the odd positions, least significant bit first. Curve order is an internal detail behind a single pair of functions; a Hilbert variant may be substituted later only if a caller demonstrates a measured I/O locality win, and would then require its own normative d2xy/xy2d definition.

### 4a. Exact uniformity at Tier 2 (counts, not bits)

A bitmask draw over set bits is **not** uniform over cells whenever `r > 1`. With `r = 16`, a micro-cell holding 256 good cells and one holding a single good cell both have their bit set and are drawn with equal probability `1 / popcount`, over-representing that single cell 256-fold. Tier 1 compensation cannot repair this because the bias is entirely intra-macro-cell, and it is larger than the domain-boundary bias section 5 goes to some length to avoid. The bias is invisible only at `r = 1`, which is also the configuration with no compression benefit - i.e. exactly the uninteresting case.

**Normative (fix option 1 of the audit): per-micro-cell counts with local prefix sums.**

1. **Tier 2a:** draw `t = rng().nextLong(validCells)` (or `nextInt` where the count fits), then binary-search the macro-cell's micro-cell prefix-sum array for the containing micro-cell. Cost `O(log(M * M))` over one contiguous primitive array; for `M = 32` that is at most 10 probes across 4 KiB.
2. **Tier 2b:** the residual `t - prefix[microIndex - 1]` is an index in `[0, microValidCells[microIndex])`, i.e. the *k*-th good cell of that micro-cell. Resolve it by scanning the micro-cell's `r * r` cells in Morton order against the **snapshot's run view** (the authoritative bad-run snapshot from which `microValidCells` was compiled) and taking the *k*-th cell not covered by a bad run. Consulting the snapshot run view rather than the live pending delta map guarantees that count and scan stay self-consistent by construction—the scan never runs off the end of the micro-cell. Newly pending bad cells added after snapshot publication are filtered by existing downstream validation, identical to every other shape in `MemoryShape`. Cost is `O(r^2 * log(runs))` due to binary-searching the snapshot run table during the scan. For `r == 1` the sub-draw is the identity.
3. **Interior and boundary micro-cells use the same representation.** A micro-cell fully inside the domain and fully good simply stores `r * r`; a micro-cell clipped by the domain edge or partly marked stores its exact contained-and-good count. One weighted-count representation therefore covers interior and boundary alike, and no boundary side table is needed at all.

**Consequences, both normative:**

- The draw is **exactly uniform over good cells** at both tiers, so the exact-uniformity claim is provable rather than asserted.
- **The sub-draw never returns a known-bad cell relative to the snapshot generation**, because a bad cell is not counted and therefore not addressable. There is no rejection loop, no re-roll bound to specify, and no reliance on downstream validation to silently absorb an internal routing failure - which keeps the design clear of the S-004 "silently discarded failure" shape. Option 2 of the audit (bitmask plus bounded rejection) is rejected on exactly that ground, and option 3 (pin `r = 1`) is rejected because it surrenders the memory win that motivates the ADR.

### 5. Integration contract with `MemoryShape`

- **`rand()` is overridden wholesale**, following the `ChunkyRTPShape` precedent. The template chain `maybeFlushAndRebuild -> adjustRange -> sample -> resolve -> postProcess` is not used: `adjustRange`/`sample`/`resolve` are 1D-scalar shaped and cannot express a `(macroIndex, microIndex, subCell)` route. `maybeFlushAndRebuild()` is still invoked first, and `postProcess(long)` is still applied last, so domain masking keeps working unchanged.
- **`xzToLocation(long,long)` / `locationToXZ(long)` are unchanged and remain the single canonical key.** The two-tier structure is an *accelerator over the same key space*, not a replacement key. The router resolves down to a concrete `(x, z)` cell and then calls the existing `xzToLocation` to produce the value `rand()` returns.
  - This is the deliberate load-bearing decision: `isKnownBad`, `causeAt`, `biomeAt`, `addBadLocation`, `save`/`load` and `PregenTask`'s ADR-062 weighted-biome draw all keep working with no API or format break.
  - There is consequently **no second index and no bijection to define**. The descriptor table is a derived, rebuildable cache keyed by macro-cell coordinate.
- **`getRange()`** continues to report the geometric cell count. Tier 1 enumerates macro-cells over the domain bounding box and masks them with `contains(int,int)`.
- **Domain-boundary policy (bias-critical):** a macro-cell that only partially intersects the domain is **included**, never clipped away. Its `validCells` counts contained cells exactly, and because Tier 2a is count-based (section 4a) a boundary micro-cell needs no side table at all - it simply stores its exact contained-and-good count. No boundary cell is over- or under-represented, and boundary micro-cells stay drawable (which is what a caller's outward neighbour test needs; the test itself is a `CandidateValidator` / `VerticalAdjustor` concern, not a shape concern).
- **Hooks that are deliberately bypassed** (stated so an operator mismatch is not silent):
  - `adjustRange`, `sample(double)` and `resolve(...)` are unreachable on this shape, and with `resolve` goes `nearestGood(...)`. The router *replaces* the "sample landed in a bad run, snap to the nearest good cell" fallback rather than inheriting it: by section 4a a bad cell is never addressable, so there is nothing to snap.
  - `mode()` (`ACCUMULATE` and friends) is likewise unused, since mode only ever influenced `adjustRange`/`resolve`. The shape does **not** declare a mode knob; if a mode is nevertheless configured on it, the shape logs a one-shot `RTP.log(Level.WARNING, ...)` mismatch exactly as `coerceUnsupportedExpand()` does for `expand`, and `coerceUnsupportedExpand()` itself is still invoked from the overridden `rand()` so the `expand` mismatch keeps its existing warning and write-back.
- **`clone()`:** the descriptor snapshot is **not** carried into a clone. `MemoryShape.clone()` hands the copy a fresh `biomeTableVersion` precisely because the clone starts with empty tables; the descriptor reference is set to `null` for the same reason, forcing a recompute on the clone's first pulse rather than presenting a stale table as current.

### 6. Bridge to learned state (mark -> micro-cell count -> weight)

The promotion rule is unit-free and is the core of the implementation:

```
mark cell c at (x, z), cause t, ttl:
  1. existing path unchanged: addBadLocation(xzToLocation(x, z), t, ttl)
  2. micro-cell:  mi = morton(x >> log2(r) & (M-1), z >> log2(r) & (M-1))
     macro-cell:  (mx, mz) = (x >> log2(B), z >> log2(B))
  3. n = number of cells the coalesced run actually contributes inside micro-cell mi
     (NOT 1: marks coalesce at spatialResolution, so one mark can cover many cells,
      and a run may straddle several micro-cells - split it and apply per micro-cell)
  4. microValidCells[mi] -= n   (floor at 0); validCells(mx, mz) -= n (floor at 0)
  5. the micro-cell becomes undrawable exactly when microValidCells[mi] reaches 0;
     no separate bit to clear
  6. micro-cell prefix sums and integerWeight are recomputed at the next compile pulse only
```

- **`spatialResolution` is load-bearing here.** The authoritative run tables coalesce marks at `spatialResolution`, so one mark is not one cell. Step 3 therefore decrements by the coalesced run's contained-cell count clipped to the micro-cell, which is also why the pending delta map (section 7) stores `(macroKey, microIndex, delta)` triples rather than single-cell events.

- **Writer:** `validCells` is written **only** by the descriptor compiler during `flushAndRebuild(spatialResolution)`, seeded from the authoritative bad-run table and the biome union table, plus the incremental decrements above between rebuilds. It is never an independent source of truth, so it cannot drift permanently - a rebuild always reconciles it.
- **Restoration** (`checkAndRestoreFromProbation`, `absorbIntoAdjacentRun`, TTL expiry per [ADR-079](ADR-079-cause-based-ttl-and-staged-expiration.md)) applies the inverse: add the restored run's contained-cell count back to `microValidCells[mi]` and `validCells`. Because a run restore can touch many cells across many micro-cells, restores mark the macro-cell dirty and defer to the next compile pulse rather than applying per cell.
- **Cause and biome remain block-granular.** The descriptor stores no cause and no biome id; those stay in the existing run tables. The descriptor is strictly a *count and reachability* summary.

### 7. Publication and concurrency model

- The descriptor table is an **immutable snapshot object** holding `long[] macroKeys`, `long[] macroPrefixSums`, `int[] validCells`, the per-macro-cell `microValidCells` count arrays with their local prefix sums, and `epoch`.
- It is published through a **single `volatile` reference**, exactly as `BiomeUnionTable` is published under [ADR-081](ADR-081-unified-blocked-biome-run-table.md). One read of that reference gives a self-consistent set of arrays, so the torn-read clamp `rand()` currently performs on `badKeysCache` / `badPrefixSumsCache` has no analogue here and is not needed.
- Compilation pulses build the next snapshot off-tick under the existing `ReentrantLock`, reusing unchanged per-macro-cell arrays copy-on-write, then publish with one reference store.
- Incremental decrements between rebuilds are applied to a small `ConcurrentHashMap`-backed pending delta map, not to the published snapshot.
- Incremental deltas are `(macroKey, microIndex, delta)` triples, so a coalesced run spanning several micro-cells produces several triples.
- **Blocked layout of `macroKeys` / `macroPrefixSums`** (not merely deferred to ADR-081): entries are grouped into blocks of 512, each block holding an absolute `long` base key and base sum plus `int[512]` key and sum offsets relative to those bases, exactly the base-delta scheme of [ADR-081](ADR-081-unified-blocked-biome-run-table.md). A block closes early if an offset would exceed `Integer.MAX_VALUE`. A small `long[]` block-base index (one entry per block) is binary-searched first, then the offsets inside the located block. Every array stays at or under 4 KiB so rebuilds stay in Eden and never trigger a G1 humongous allocation, which matters because a large domain has far more than 512 macro-cells.

### 8. Demonstration and comparison plan

**Scope of the first artifact:** a read-only, in-memory, `(r, M)`-parameterized two-tier shape, explicitly labelled a **measurement vehicle**. No persistence, no learned-state writes. Sections 6 and 7 are specified here so the follow-up is a fill-in rather than a redesign.

**Baselines:** the current spiral `MemoryShape` (ADR-001), the ADR-081 blocked union table, and the ADR-082 B+ tree variant when it exists.

**Metrics (one row per baseline per range):**

| Metric | Definition | Provenance tag |
|--------|------------|----------------|
| Resident bytes vs. range | retained size of the selection structures | MEASURED |
| Run / descriptor count vs. range | table entries at steady state | MEASURED |
| Selection cost | ns/op for `rand()`, common random numbers across baselines | MEASURED |
| Rebuild cost | ns per `flushAndRebuild` at fixed mark count | MEASURED |
| Uniformity (unsubsampled) | chi-square over equal-area bins with `pi_i = PI_ONE` for all macro-cells, `>= 200` expected counts per bin, accepted at p `>= 0.01` | MEASURED |
| Coverage / starvation (subsampled) | with subsampling engaged, every non-empty macro-cell is included at least once per `64` epochs, and pooled per-cell frequencies fall inside a stated tolerance band over `>= 4096` epochs | MEASURED |
| Zero-weight draw count | draws landing on a zero-weight macro-cell (invariant assertion: must be 0) | GUARD |

**Harness:** hook [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md)'s opt-in tier (`@Tag("simulation")` under `rtp-core/.../common/benchmark/`, run by `:rtp-core:simulationBenchmark`) with mandatory `MEASURED` / `DERIVED` / `MODELED` provenance per row. The natural scaffolds are the existing `MemoryShapeSelectionParityTest`, `MemoryShapeSelectionThroughputTest` and `MemoryShapeRebuildCostTest`.

**Uniformity assertions are split deliberately.** A single chi-square pooled over 64 epochs with `pi_i = PI_MIN` has an expected inclusion count of 1 per starved macro-cell, so it would fail from sampling noise rather than from bias - a flaky acceptance criterion, which is the worst possible outcome for a measurement artifact. Therefore: uniformity is asserted only in the unsubsampled regime (`pi_i = PI_ONE`), where the estimator is exact and power is controllable; the subsampled regime is asserted instead through the deterministic `PI_MIN` starvation bound plus a long-horizon coverage/frequency band.

**RNG source and the determinism claim.** `MemoryShape.rng()` falls back to `ThreadLocalRandom.current()`, which is not seedable, so section 3's bit-for-bit reproducibility is achievable **only** under the test-only `setRng` injection - the determinism claim is scoped to that, plus to the epoch/inclusion decision (which depends on the world seed and `epoch`, not on `rng()`, and is therefore reproducible in production). The integer draws are named explicitly: `RandomGenerator.nextLong(bound)` (Java 21, REQ-RTP-SYS-001) for both prefix-sum draws and `nextInt(bound)` for the residual sub-draw; `nextDouble()` - what the inherited `sample` used - is never called.

**New tests to add:** a per-epoch determinism test under injected RNG (same seed and epoch reproduces the same route bit for bit), a `PI_MIN` starvation-bound test, an unsubsampled exact-uniformity test at `r > 1` with deliberately unequal micro-cell occupancy (the regression guard for section 4a), a "sub-draw never returns a known-bad cell" test, and a unitlessness test mirroring `DistanceParameterTest.testSubspaceShapeRemainsUnitless`.

**Persistence (deferred, contract stated now):** the descriptor table is derived and rebuildable, so the demo writes nothing and the `.bin` format is untouched. If a later revision persists descriptors it shall add a new optional section under a version bump to 5, and `load` shall treat an absent descriptor section as "recompute on first pulse", preserving the existing legacy fallback (`readLegacyBiomeSections`) unchanged.

### 8a. First measurements (evidence only - not approval)

A measurement vehicle exists in the opt-in tier and has been run. It changes nothing about this ADR's status: the vehicle lives entirely in `rtp-core/src/test/.../common/benchmark/` (`TwoTierCellRouter`, `WorldOccupancyMask`, `TwoTierRouterModelBenchmarkTest`, `TwoTierRouterWorldDataBenchmarkTest`), ships in no jar, is registered with no factory, and is reachable from no configuration. The D-005 gate below is still closed.

**Model proof (synthetic adversarial mask, `r = 4`, `M = 8`, radius 64).** Micro-cell occupancy is varied deliberately over 1..4 good cells out of 16, which is the configuration a bitmask Tier 2a would bias fourfold.

| Assertion | Result |
|---|---|
| Descriptor good-cell count vs. brute force | equal (4960) |
| Uniformity over good cells, 32000 draws, 64 bins | chi-square 67.1 / 63 dof, p = 0.34 |
| Sub-draw misses (section 4a) | 0 |
| Zero-weight draws (GUARD) | 0 |
| Route reproducibility under injected RNG | 2048 / 2048 identical |
| `PI_MIN` coverage over 4096 epochs | all 16 macro-cells seen, worst inclusion gap exactly 64 epochs, mean inclusion rate 0.250 vs. expected 0.250 |

**Real-save use case.** Validity is a real Anvil save's chunk-occupancy mask (414 region files, 261346 chunks on disk, location tables only), domain radius 480 cells, 258597 good cells, 200000 draws per configuration.

| `(r, M)` | macro edge | drawable macro-cells | zero-weight macro-cells | descriptor bytes | bytes / good cell | selection ns/op | uniformity p |
|---|---|---|---|---|---|---|---|
| `(1, 32)` | 32 | 400 | 561 | 2 467 200 | 9.54 | 493 | 0.27 |
| `(4, 32)` | 128 | 48 | 16 | 296 064 | 1.15 | 1 292 | 0.06 |
| `(16, 32)` | 512 | 4 | 0 | 24 672 | 0.10 | 17 705 | 0.16 |

ADR-001 spiral baseline over the same mask at radius 256: 46953 marked cells coalesce to 1174 runs (18784 bytes), steady-state rebuild 0.02 ms.

**What the numbers actually say.**

1. Exact uniformity survives real data at every `(r, M)`, and the sub-draw never missed and never addressed a bad cell. Section 4a holds.
2. The `PI_MIN` bound is exact, not approximate: the worst observed inclusion gap is exactly 64 epochs, which is what systematic phase sampling promises and independent Bernoulli trials would not.
3. The memory win is real and large (100x from `r = 1` to `r = 16`) but is bought entirely with selection latency: `O(r^2)` Tier 2b scanning costs 36x at `r = 16`. The `r` knob is therefore not free, and the caller-side `r = 16` mapping in section 9 - the one that lines macro-cells up with region files - is the most expensive point measured, not the natural default it reads as.
4. Zero-weight macro-cells are abundant at small macro edges (561 of 961 at `r = 1`) and vanish at `r = 16`, so the I/O-avoidance guarantee and the memory-saving knob pull in opposite directions.

Caveats that bound how far these rows can be read: the vehicle uses flat arrays rather than section 7's blocked layout (footprint rows are upper bounds on the macro tier), implements no incremental delta map, and drives validity from a frozen oracle rather than a versioned run snapshot. The save is not committed; the world-data rows are only reproducible against a locally supplied save.

### 8b. Cost calibration, and a falsified premise (evidence only - not approval)

Section 8a measured selection latency and footprint. Neither chooses a model: this ADR's Context rests on a *rebuild* and *storage-growth* claim, so a second harness was added to measure that axis instead - `ModelCalibrationBenchmarkTest` in the same opt-in tier, together with an observe-only `StorageLatencyProbe` in `anvil-api` fed from the cold-read sites the prefilter already executes. Reports land in `build/reports/rtp-simulation/model-calibration.md`.

**The Context premise does not survive measurement.** The Context states that a compact 2D feature is "cut once per spiral revolution, so its run count scales with feature **area** rather than **perimeter**". Measured on the shipped `Square` at radius 1024, with square features placed off-centre:

| feature edge | marked cells | runs | runs / edge | bits / marked cell |
|---|---|---|---|---|
| 8 | 64 | 8 | 1.000 | 16.0 |
| 16 | 256 | 16 | 1.000 | 8.0 |
| 32 | 1024 | 32 | 1.000 | 4.0 |
| 64 | 4096 | 64 | 1.000 | 2.0 |
| 128 | 16384 | 128 | 1.000 | 1.0 |

Run count equals the feature's linear extent exactly at every size. The fitted exponent against area is **0.500**, i.e. perimeter-order, not area-order, and storage cost per marked cell *improves* as a feature grows. The motivating sentence for a hierarchical replacement is therefore unsupported on this axis, and any argument for a new model has to be made on some other ground.

**Coefficients across range, at a fixed learned-state population** (64 clustered 16x16 features = 16384 marks, held constant while the domain grows):

| radius | domain cells | runs | effective bad cells | bits / marked cell | t_rebuild ns/mark |
|---|---|---|---|---|---|
| 256 | 262 144 | 858 | 14 379 | ~7.6 | 332 874 |
| 512 | 1 048 576 | 1 016 | 16 048 | ~8.1 | 68 578 |
| 1 024 | 4 194 304 | 1 007 | 16 150 | ~8.0 | 99 172 |
| 2 048 | 16 777 216 | 1 040 | 16 400 | ~8.1 | 75 765 |
| 4 096 | 67 108 864 | 1 024 | 16 384 | ~8.0 | 85 826 |

Fitted exponents against radius: **bits/mark 0.014** (flat) and **t_rebuild -0.377** (mildly decreasing; the radius-256 row is a JIT-warmup outlier). The spiral's storage and reconciliation costs are set by the learned state, not by the size of the world - which is precisely the scaling property the ADR set out to obtain.

**The two-tier descriptor's compile cost, which section 8a did not measure:** fitted at **radius^1.86** over radii 128..1024. The descriptor recompiles per domain cell, so its rebuild term grows with world *area* while the spiral's does not. Section 6's incremental delta map is the only thing that would change this, and it is unimplemented.

**Storage versus compute asymmetry.** One avoided ~4 MiB region-file read is worth roughly 2 600 selections on NVMe, 16 000 on SATA SSD, and 66 000 on a spinning disk (nominal per-read figures; replaced at runtime by the probe's EWMA). Selection nanoseconds are therefore the wrong currency for a model decision, and a slow device is an argument for *raising* the resident budget, not for spilling.

**Consequences for this ADR, stated plainly:**

1. The Context's area-scaling premise is measurably false as written and must be rewritten or withdrawn.
2. Subsampling (`PI_MIN`) buys bytes by *discarding* precision for up to 63 epochs. A layered scheme that *demotes* cold descriptors and pays `t_io` on miss keeps precision instead, and the asymmetry above says the trade is affordable only on fast storage.
3. `supportsExpand() = false` (section 3) is an unforced concession: `expand` declares a capability, and spiral growth is merely one implementation of it. A model holding per-subsection valid counts can drive expansion on measured quantity rather than on the `badSum` proxy.
4. The open question is no longer "is the two-tier router fast enough" but "does any candidate beat a structure already at 1-8 bits per marked cell with domain-independent rebuild cost". That is the bar a replacement has to clear.

These measurements do **not** move the D-005 gate. They were taken to test the premise before any decision, and no shipped selection code was changed to obtain them.

### 9. Caller-side unit mappings (non-normative)

None of the following is visible to or assumed by the shape. It is recorded only to explain why the defaults are what they are, and belongs in [`DESIGN.md`](../dev/DESIGN.md).

- A Bukkit-family caller that sets `r = 16` and `M = 32` gets macro-cells of 512 cells edge, which coincides with Anvil / Linear region-file geometry ([ADR-016](ADR-016-anvil-subsystem.md), [ADR-077](ADR-077-multi-format-region-support.md)). The "reject before opening a region file" benefit is then a property of that mapping plus the shape's zero-weight guarantee, not a shape feature.
- A caller whose candidate validator probes some fixed radius outward is served by section 5's boundary policy, which keeps macro-cell-edge micro-cells drawable. Cross-macro-cell neighbour access remains an S-001 / S-005 concern for the validator and the async chunk path.
- Descriptor warming, if a caller ever drives it from real storage, is subject to S-005 (no synchronous chunk I/O on a main thread), S-002 (no permanently force-loaded chunks), and the count-bound pipe discipline of [ADR-015](ADR-015-stale-chunk-guard-countbound-pipes.md).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Monolithic global Archimedean spiral (ADR-001) | Slices compact 2D features into O(area) runs, one per revolution; table size grows faster than the information it holds at large range. Retained as the default for small domains. |
| Naive rejection sampling over macro-cells | Re-roll count decays exponentially in sparse domains, violating the bounded-execution guarantee. |
| Strict per-epoch uniformity with no exclusion | Removes the memory ceiling that motivates the ADR; equivalent to `pi_i = PI_ONE` everywhere, which is exactly what the design already does below the resident budget. |
| Uncompensated exclusion (the prior draft) | Silently biases the distribution toward high-yield macro-cells with no bound and no way to test correctness. |
| Global quadtree of objects | Pointer-heavy on the JVM, poor cache locality, and lock-free publication becomes non-trivial. |
| Hilbert micro-cell order | No locality benefit inside the shape; needs a normative d2xy/xy2d variant that Morton does not. Deferred pending a measured caller-side I/O win. |

## Consequences

- **Positive:**
  - Stated entirely in unitless cells, so no Minecraft unit leaks into `rtp-core`'s shape layer.
  - Eliminates area-scaling run fragmentation from global spiral winding.
  - Bounded resident memory via a compensated, testable subsampling rule.
  - Deterministic and reproducible: named mixer, integer-only selection path, explicit epoch rule.
  - No API or persistence break - `xzToLocation` / `locationToXZ` stay canonical and learned state is untouched.
- **Negative / Trade-offs:**
  - Per-epoch distribution is biased whenever subsampling engages; only the cross-epoch pooled distribution is uniform.
  - Adds a second summary structure that must be reconciled against the authoritative run tables on every rebuild.
  - Per-micro-cell counts cost `2 * M * M` bytes plus a prefix array per macro-cell instead of an `M * M / 8` bitmask; exact uniformity is paid for in resident bytes.
  - Tier 2b must scan a micro-cell's `r * r` cells in Morton order to locate the *k*-th good cell, bounding sub-draw cost at `O(r^2)` rather than `O(1)`.
  - **Open, explicitly deferred:** overlap with existing binning work ([ADR-028](ADR-028-l3-backlog-cache.md)'s per-pulse bin screening and the approved `PROPOSAL-shape-lattice-selector.md` region-binned candidate path). No consolidation is attempted in this ADR; the decision is deferred until the section 8 measurements exist, and is owned by whichever of the two paths the measurements favour.

## Approval gate (D-005)

This ADR crosses `rtp-core` shape, learned-state and benchmark surfaces, so per Rule D-005 it is a proposal only. Status stays **Proposed** and no implementation is written until the maintainer approves the scope in section 8 (read-only, in-memory, `(r, M)`-parameterized measurement vehicle; no persistence, no learned-state writes).

The section 8a measurements do **not** move this gate. They were taken from a test-scope vehicle precisely so the model could be checked before any decision; producing evidence is not the same as being granted permission to ship it, and no shipped code was changed to obtain them.

## References

- `docs/adr/ADR-001-archimedean-spiral-1d-mapping.md` (monolithic 1D spiral selection; comparison baseline).
- `docs/adr/ADR-015-stale-chunk-guard-countbound-pipes.md` (count-bound pipes for any caller-side warming path).
- `docs/adr/ADR-016-anvil-subsystem.md`, `docs/adr/ADR-077-multi-format-region-support.md` (caller-side unit mapping only).
- `docs/adr/ADR-028-l3-backlog-cache.md` (L3 backlog already screens one bin per pulse; a partial Tier 1 precedent to consolidate with).
- `docs/adr/ADR-079-cause-based-ttl-and-staged-expiration.md` (restore path feeding the weight increments).
- `docs/adr/ADR-080-opt-in-simulation-benchmark-tier.md` (comparison harness and provenance rules).
- `docs/adr/ADR-081-unified-blocked-biome-run-table.md` (blocked layout, copy-on-write, volatile snapshot publication).
- `docs/adr/ADR-082-cache-conscious-b-plus-tree-primitive-arrays.md` (dynamic subsystem selection by domain size).
- `docs/dev/scratch/PROPOSAL-shape-lattice-selector.md` (approved; already defines a bin-based candidate path sibling to L3).
