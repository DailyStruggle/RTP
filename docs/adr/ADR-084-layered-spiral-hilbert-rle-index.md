# ADR-084 - Layered Learned-State Index: Coarse Spiral Directory over Per-Cell Hilbert RLE Blobs

**Status:** Rejected - superseded as a decision by [ADR-085](ADR-085-spiral-addressed-hilbert-key-space.md)
**Date:** 2026-09-05

> **Rejected with cause.** Three of this ADR's load-bearing claims did not survive measurement, and
> the corrections are retained below because the pattern is worth not repeating.
>
> 1. **The resident-fraction argument was an artifact of a fictitious unit** (section 12). The
>    "0.7% resident, 1.2 MB against 198 MB" figures were measured with an inner cell of 1-8 blocks,
>    but one chunk is the finest unit the safety signal distinguishes. At chunk precision the
>    directory is 0.56-0.62 of total state and the shipped spiral's entire table is smaller than the
>    directory alone. Criterion C5 FAILED.
> 2. **The accuracy metric divided by the wrong denominator** (section 15a). Over-exclusion was
>    reported against the bad area, so discarding roughly half of all usable ground scored as
>    `1.118x`.
> 3. **The absent boundary was an artifact of an asymmetric objective** (section 10e). The shipped
>    spiral was assigned infinite cost for exceeding a policy budget while the layered model paid a
>    finite miss term, which eliminated the one model that waits on nothing exactly where slow
>    storage should have favoured it.
>
> The two-level structure itself is declined for a simpler reason: it needs a directory, a Fenwick
> tree, per-cell blobs, a crash-consistency story between directory and blob generations, and a
> paging policy - none of which the measured reconciliation win at coarse settings requires.
> ADR-085 obtains the same locality by changing only the bijection, keeping one table and one draw.
> All measurement sections here remain valid as the evidence that redirected the work.

## Context

Three prior proposals ([ADR-081](ADR-081-unified-blocked-biome-run-table.md), [ADR-082](ADR-082-cache-conscious-b-plus-tree-primitive-arrays.md), [ADR-083](ADR-083-two-tier-probabilistic-region-routing.md)) attack `MemoryShape` learned-state storage. Calibration under [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md) established three things that change what a replacement has to be:

1. **The area-growth premise is false.** ADR-083's Context asserts that the spiral cuts a compact 2D feature once per revolution so run count scales with feature *area*. Measured, run count scales as `area^0.500` - one run per row, i.e. perimeter-order (ADR-083 section 8b).
2. **Cell memory efficiency is not the deciding axis.** No candidate beats the shipped spiral RLE on bits per marked cell, and at fixed mark population the spiral's footprint is flat in range (bits/mark exponent `0.014`). The operator-facing question is not "how few bits per cell" but **how much RAM a given range costs, and how much is left to spend on time optimization**.
3. **Reconciliation is the cost that scales.** The spiral's cost per reconciled mark grows as `radius^1.421`, because a mark must be merged against a global 1D run table.

Two further constraints come from the operating environment rather than from the encoding:

- **Cost is latency-bound, not throughput-bound.** A storage operation is priced by its per-operation latency, so the useful question is how many candidates one operation serves, not how many bytes it moves.
- **Granularity is a ratio.** A 512-block quantum is 50% of a 1 km border and 0.5% of a 100 km one. Any fixed granularity is simultaneously correct and fatal depending on range.

## Decision

Adopt a **two-level index**: a resident coarse directory addressed by the existing Archimedean spiral, over per-cell run-length-encoded Hilbert blobs that may be paged.

```
[Domain, 2 * radius square, unitless cells]
                 |
                 v  Level 1: Archimedean spiral (ADR-001) over OUTER cells of edge E
   outer cell   (weight = good inner-cell count, held in a Fenwick tree)
                 |
                 v  Level 2: that cell's blob - bad inner cells as coalesced runs
   rank/select over Hilbert order -> inner cell -> block coordinates
```

### 1. Level 1: the spiral, coarsened

The spiral is retained as the **addressing and expansion curve**, not replaced. Its domain is coarsened by a factor `E`, shrinking it by `E^2`. Consequences:

- The `atan` round trip in `xzToLocation` becomes a **per-selection** cost rather than a per-cell cost.
- `expand` remains supported and is driven by summed good counts per outer cell, which is a direct measurement of remaining capacity rather than the `badSum` proxy. ADR-083's `supportsExpand() = false` is rejected: `expand` declares a *capability*; spiral growth is one implementation of it.
- At `E = 512` an outer cell coincides with an Anvil region file, so a zero-good outer cell is storage that is never opened. This alignment is a caller-side benefit and is not normative here.

**`E` is derived, not configured.** The rule is: choose the coarsest `E` such that `E / radius` remains a small fraction (target: at least 64 outer cells per edge), floored at the point where the domain is a single outer cell, in which case the outer level is omitted entirely and the model degenerates to a single blob. Operators are not asked to pick this.

### 2. Directory: Fenwick tree of good counts

Per-outer-cell good counts in a Fenwick tree give `O(log n)` weighted selection and `O(log n)` update. This is the only structure that must stay resident.

### 3. Level 2: RLE over Hilbert order, one blob per outer cell

Bad inner cells are stored as coalesced `(start, length)` runs over the Hilbert index within the outer cell. Hilbert is chosen over Morton (ADR-083 section 4) for one reason that survives the unitless argument: it is locality-preserving **in both directions**, so a blob is simultaneously a compact encoding and a self-contained unit of storage covering a square sub-area. Rank/select over the run list answers "the k-th good inner cell" without a scan of the cell.

### 4. Uniformity

Selection is **exactly uniform over good cells in every epoch**, by construction: the outer draw is weighted by good count and the inner draw is uniform within the drawn cell. There is no rejection sampling, no `O(r^2)` sub-draw, and no subsampling. ADR-083's `PI_MIN` mechanism is rejected: it discards precision to bound memory, where paging *demotes* precision-preserving state instead.

### 5. Precision lever: Hilbert bit truncation

Coalescing is expressed as **dropping low-order Hilbert bits**, so a merged region is always a quadtree-aligned square. This is strictly better behaved than curve-order coalescing, which can merge spatially unrelated areas. A hard floor on inner resolution bounds over-exclusion to the inner cell area, per outer cell, so degradation is local and proportional rather than global.

### 6. Reconciliation is local

A mark touches one blob plus one Fenwick update. Nothing global is rebuilt. This is a direct attack on the measured `radius^1.421` cost and makes ADR-083 section 6's incremental delta map unnecessary rather than unimplemented: the partitioning *is* the delta mechanism.

## Criteria this model is required to meet

| # | Criterion | Status |
|---|---|---|
| C1 | Never select a marked cell | MEASURED - 0 of 200 000 draws |
| C2 | Exactly uniform over good cells | MEASURED - equal-weight cells within +/-4% of expectation |
| C3 | Full precision retained, no lossy coalescing by default | MEASURED - 16 384 marks yield 16 384 bad cells |
| C4 | Reconciliation cost independent of range | MEASURED - exponent `+0.232` vs. spiral `+0.783`; 51x-223x cheaper |
| C5 | Resident cost a small fraction of total state | **FAILED** - 0.56-0.62 at chunk precision, and the spiral's whole table is smaller than the directory alone (12b). The earlier 0.7% was measured in a sub-chunk unit that does not exist. |
| C6 | One storage operation serves many candidates | MEASURED - `E^2` inner cells per blob read |
| C7 | `expand` capability preserved | BY CONSTRUCTION - spiral retained as the expansion curve |
| C8 | Granularity adapts to range | MEASURED - derived by the cost planner; regret 0.000-0.001 against the measured optimum at radius 1 024 to 20 480 (section 9) |
| C9 | Configuration derived from the situation, not configured | MEASURED - worst-case regret `0.321` across 18 device / headroom / load points (section 9) |

## Measurements (section 8)

> **Unit correction.** Every row in sections 8 and 9 was measured with an inner cell edge of one *block*. That resolution does not exist - one chunk is the finest addressable cell (11a) - so footprint and precision rows here are superseded by **section 12**. Reconciliation, retention, selection latency and uniformity rows are unaffected, because they were measured on the structures rather than derived from a cell count.

Vehicle: `LayeredHilbertIndex` and `LayeredIndexScalingBenchmarkTest`, ADR-080 opt-in tier, excluded from `build`. Report: `build/reports/rtp-simulation/layered-index-scaling.md`. Mark model: **fixed density** (3% of the domain, clustered 64-cell features), because a fixed *population* cannot exhibit a memory ceiling. `E = 512`, full precision.

### 8a. Resident cost across small and mid range

| radius | domain cells | marks | layered runs | spiral runs | resident directory | pageable blobs | layered total | spiral total | select ns/op |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 128 | 65 536 | 4 096 | 49 | 54 | 8 | 596 | 604 | 864 | 182 |
| 256 | 262 144 | 4 096 | 48 | 64 | 8 | 584 | 592 | 1 024 | 127 |
| 512 | 1 048 576 | 28 672 | 394 | 512 | 32 | 4 760 | 4 792 | 8 192 | 172 |
| 1 024 | 4 194 304 | 122 880 | 1 830 | 1 972 | 128 | 22 056 | 22 184 | 31 552 | 226 |
| 2 048 | 16 777 216 | 499 712 | 7 662 | 7 664 | 512 | 92 392 | 92 904 | 122 624 | 166 |

Exponents vs. radius: layered fully-resident `1.976`, spiral run bytes `1.924`. Both are `radius^2`, which is expected: under fixed density the mark population itself grows as `radius^2`, and neither encoding can beat the information content. **The models are equivalent on total bytes** (layered ~0.75x, within encoding noise). The difference is the split.

### 8b. Extrapolation to large range

`MODELED` - fitted from the rows above, except the directory which is closed-form (`(2 * radius / E)^2` slots).

| projected radius | resident directory | total state | resident fraction |
|---:|---:|---:|---:|
| 8 192 | 8 KB | 1.2 MB | 0.7% |
| 32 768 | 128 KB | 19.0 MB | 0.7% |
| 100 000 | 1.2 MB | 172 MB | 0.7% |

This is the answer to the operator question. A 100 km border costs **1.2 MB that must stay resident**; the remaining 172 MB is pageable at the granularity of one blob per storage operation, each serving 262 144 candidates. Everything above the 1.2 MB floor is therefore discretionary and available to spend on time optimization.

### 8c. Reconciliation

> **Correction.** An earlier revision of this section reported a layered exponent of `-1.417` and a
> 146x advantage at radius 2 048. Those figures were wrong. The vehicle's `flush` scanned every
> outer cell to find staged marks, so the measurement was dominated by a domain-order scan that the
> model does not require. The scan is now replaced by a dirty set and the numbers below are the
> corrected ones. The direction of the result survives; the magnitude and, more importantly, the
> *shape* do not - reconciliation is flat in range, not falling.

| radius | layered ns/mark | spiral ns/mark | ratio | blobs rebuilt per mark |
|---:|---:|---:|---:|---:|
| 256 | 1 470 | 75 515 | 51.4 | 1.005 |
| 512 | 1 493 | 199 043 | 133.3 | 1.020 |
| 1 024 | 1 932 | 142 908 | 74.0 | 1.060 |
| 2 048 | 2 308 | 514 520 | 222.9 | 1.280 |

Exponent vs. radius: layered `+0.232`, spiral `+0.783`. The layered cost is **effectively flat** -
work is bounded by one blob, and the residual growth is the Fenwick depth plus the growth in blob
contents. The claim the model is entitled to is range-independence, not improvement with range.

**The single-outer-cell degeneracy remains real.** At radius 256 with `E = 512` the domain is one
outer cell, so the blob is the whole domain and locality buys nothing; the layered model is then
merely the spiral with extra indirection. This is why `E` must be derived from range, which
section 9 now does.

### 8d. Outer granularity, radius 1024

| `E` | outer cells | `E`/radius | runs | resident directory | pageable blobs | candidates per read | select ns/op |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 16 | 16 384 | 0.016 | 2 340 | 131 072 | 33 960 | 256 | 133 |
| 64 | 1 024 | 0.063 | 1 898 | 8 192 | 23 704 | 4 096 | 133 |
| 256 | 64 | 0.250 | 1 846 | 512 | 22 440 | 65 536 | 130 |
| 512 | 16 | 0.500 | 1 830 | 128 | 22 056 | 262 144 | 146 |

Boundary fragmentation is real but mild: 1 830 runs at `E = 512` against 2 340 at `E = 16`, a 28% penalty for a 32x finer expansion step. At `E = 16` the resident directory (131 KB) *exceeds* the pageable state (34 KB), which inverts the design - the fine-granularity regime is only viable because it coincides with small domains that fit resident anyway. Selection cost is flat across the sweep (130-146 ns/op), so granularity is not a latency knob.

### 8e. Precision lever, radius 1024

| inner resolution | bad inner cells | blocks excluded | runs | total bytes |
|---:|---:|---:|---:|---:|
| 1 | 122 880 | 122 880 | 1 830 | 22 184 |
| 2 | 31 590 | 126 360 | 982 | 12 008 |
| 4 | 8 383 | 134 128 | 533 | 6 620 |
| 8 | 2 376 | 152 064 | 259 | 3 332 |
| 16 | 735 | 188 160 | 139 | 1 892 |

Coarsening to 16 blocks cuts state by 11.7x for 53% over-exclusion. Over-exclusion is bounded by the inner cell area, which is what makes a resolution floor a guarantee rather than a hope.

## 9. The configuration is chosen by a cost heuristic

`E` and the inner resolution are **outputs of a planner**, not settings. Vehicle:
`IndexConfigPlanner` plus `HeuristicPlannerBenchmarkTest`, report
`build/reports/rtp-simulation/planner-regret-scale.md`.

### 9a. Objective

```
cost(c) = tSelect(c) + marksPerCandidate * tReconcile(c)
        + missFraction(c) * storageOpNanos / candidatesServedPerRead(c)
        + precisionPenalty(c)
  subject to residentDirectoryBytes(c) <= budget,  budget = 1% of measured heap headroom
             expandStep(c) / radius <= 1/8
             innerRes(c) <= precision floor
```

Three properties are deliberate:

- **The budget is derived from measured headroom**, not configured. Operators are not asked.
- **Storage latency can only raise residency.** The miss term is a cost of *not* being resident, so a
  slower device never argues for spilling. An operator on spinning rust is not asked to trade RAM
  away first.
- **Coarsening is charged only beyond the resolution of the incoming signal.** Chunk-derived marks
  carry one bit per 16 blocks, so an inner cell finer than that spends bytes on information that
  does not exist. A first version of the model charged coarsening from a 1-block baseline and
  consequently rejected free savings.

### 9b. Why a heuristic is necessary: the cost curve has an interior optimum

Reconciliation per mark against outer edge, radius 1 024, real occupancy at chunk precision:

| `E` | 16 | 32 | 64 | **128** | 256 | 512 | 1 024 |
|---|---:|---:|---:|---:|---:|---:|---:|
| ns/mark | 4 706 | 1 382 | 567 | **342** | 468 | 698 | 2 117 |

The curve is **U-shaped**, and fits `240 + 0.27 * outerCells + 6.2 * runsPerBlob` across the whole
sweep. Two costs oppose each other - a fine directory is cheap per blob and expensive per mark, a
coarse one the reverse - so no fixed `E` is correct and a single-factor rule cannot find the
minimum. This is the measured justification for a planner rather than a constant.

A second premise died here too: **runs per blob scale linearly with a blob's bad cells** (fitted
ratio `0.35`), not as a perimeter law. Run-length encoding of real chunk-granular occupancy buys
about 3x, because a save's generated area is ragged at chunk scale rather than smooth.

### 9c. Regret against the measured optimum

The planner's choice is scored against the best configuration *actually measured*, under the same
objective with measured terms substituted for fitted ones.

| radius | planner choice | measured optimum | regret | resident / total state | expand step / radius |
|---:|---|---|---:|---:|---:|
| 1 024 | `E=128` | `E=128` | 0.000 | 10.9% | 0.125 |
| 2 048 | `E=256` | `E=256` | 0.000 | 8.7% | 0.125 |
| 4 096 | `E=256` | `E=512` | 0.000 | 17.2% | 0.063 |
| 8 192 | `E=256` | `E=1024` | 0.001 | 24.5% | 0.031 |
| 20 480 | `E=512` | `E=1024` | 0.000 | 13.6% | 0.025 |

Worst-case regret across 18 device / headroom / load trace points at radius 1 024: **0.321**. Where
the planner's choice differs from the optimum the realized costs are within 0.1% - the objective has
a **plateau** across two to three adjacent outer edges, which is a favourable property: it means the
decision is insensitive to coefficient error over exactly the range where the coefficients are least
trustworthy.

One constraint had to be relaxed on evidence: an expansion-step limit of `radius / 32` admitted only
two outer edges at radius 1 024 and excluded the measured optimum outright, which turns a cost model
into a constant. It is now `radius / 8`, i.e. at least 16 outer cells across.

### 9d. Switching discipline

A planner that re-evaluates every pulse and switches on any improvement rebuilds the structure to
save nanoseconds. A challenger must therefore beat the incumbent by a margin (20%) and the switch
happens at a compile-pulse boundary, which is already the only place a blob is published.

### 9e. Large-range data without generating terrain

The radius 20 480 rows are **measured, not extrapolated**. The domain is the real save's region
footprint repeated outward with alternate tiles mirrored (`TiledOccupancyMask`). This is sound for
every quantity reported - runs, blob bytes, reconciliation locality and directory footprint depend on
the shape and density of clustering, not on where it sits - and the limitation is stated: occupancy
is periodic with the source save's bounding box, so no bad feature larger than one tile can exist,
which bounds run lengths from above and makes the run counts conservative. Precision at scale is one
inner cell per chunk, which is the native resolution of the signal.

## 10. The model boundary as a surface, not a radius

Sections 8 and 9 chose a *configuration* of this model. They never costed the shipped spiral under the same objective, so the "crossover near a 512-block radius" reported earlier was read off two byte columns rather than computed. Section 10 closes that: both models are candidates in one objective, and the boundary is bisected over range for each storage tier.

Vehicle: `ModelCrossoverPlanner` and `ModelCrossoverBenchmarkTest` (test scope, ADR-080 opt-in tier). Report: `build/reports/rtp-simulation/model-crossover-surface.md`.

### 10a. Cache locality is a separate term from footprint

Footprint alone does not price a read. The two models differ in access *pattern*:

- a spiral selection binary-searches one global run table - `log2(runs)` independent, unpredictable line touches, at whatever level of the hierarchy the table lives in;
- a layered selection descends a small directory and then walks one blob's runs forward, which a hardware prefetcher largely hides.

The objective therefore charges a three-level line latency (4 / 15 / 80 ns for private cache / last-level / main memory) against the working set of each access, and discounts a sequential touch to one eighth. Those constants are **stated assumptions, not measurements on this rig**, and are reported as such so the term can be recalibrated against a pointer-chase probe if it ever decides the boundary.

Modeled outcome (bad density from the tiled save, 1% of 8 GB headroom):

| radius | directory bytes | directory line ns | spiral table bytes | spiral table line ns |
|---|---|---|---|---|
| 1 024 | 2 048 | 4 | 3 168 | 4 |
| 8 192 | 32 768 | 4 | 202 790 | 4 |
| 65 536 | 131 072 | 4 | 12 978 588 | 15 |
| 262 144 | 2 097 152 | 15 | 207 657 418 | 80 |

The mechanism behind flat selection latency is visible here and it is not the heap argument: the part touched on **every** draw stops growing, while the part that grows is touched once and sequentially. At border scale the spiral's table is in main memory and is probed randomly `log2(runs)` times per selection.

### 10b. The boundary exists, and it is set by storage latency

An earlier revision of this section reported `none in range` at all 27 grid points and treated device-tier coincidence as a finding. That result was an artifact of the objective, not a property of the structures; the defect and its correction are in 10e. With memory priced rather than enforced, bisecting radius from 128 to 262 144 over four device tiers x four headroom values (4 MB / 16 MB / 512 MB / 8 GB) x three mark loads yields **four distinct boundary values**, and the chosen model varies across the grid - which the harness now asserts, because a planner that answers the same model everywhere is a constant wearing a cost model.

At light mark load (0.001 marks per candidate), 512 MB headroom:

| tier | per-op latency | crossover radius | below | above |
|---|---|---|---|---|
| NVMe | 120 us | none in range | LAYERED | LAYERED |
| SSD | 500 us | none in range | LAYERED | LAYERED |
| HDD | 9 ms | **103 937** | LAYERED | **SPIRAL** |
| network | 50 ms | **103 937** | LAYERED | **SPIRAL** |

The direction is the one a latency-dominated cost model predicts and the earliest revision could not express: **a structure that performs no I/O wins once I/O is expensive enough**. Above roughly a 100 km radius on spinning rust or a remote store, holding the whole run table resident beats paging blobs. On flash the layered model wins across the entire swept range.

Three qualifications, the first of which is a correction to this section's own earlier numbers:

1. **The boundary was first published at 10 241 and is now 103 937 - a 10x move, and the earlier figure was an artifact.** At that time the layered model had no choice about residency: bytes above the budget silently became a miss fraction, so it was charged for paging even where holding its blobs in heap was cheaper. Section 11d makes residency a costed branch, and once the layered index may also stay resident the boundary retreats by an order of magnitude. The pattern is the one 10e and 8c already record: a constraint imposed on one candidate and not the other reads as a property of the structures.
2. `none in range` is also produced at heavy mark load for an unrelated reason - local reconciliation then dominates every other term - so it means "one term swamped the others here", not "the models are equivalent".
3. The heap-pressure constant is policy, and the modeled layered reconciliation base is optimistic against the rows measured here. Both biases push the boundary the same way, so 103 937 is a **lower bound** on where the resident model starts winning.

### 10c. Retention, and the harness fault that once broke it

A footprint comparison between two structures that kept different amounts of information is meaningless, so both models are asked how many of the offered marks they retained, and the harness asserts the answer rather than reporting it.

| radius | marks offered | spiral retention | layered retention | spiral resident | layered resident |
|---|---|---|---|---|---|
| 512 | 512 | 1.000 | 1.000 | 128 B | 128 B |
| 1 024 | 3 392 | 1.000 | 1.000 | 1 504 B | 512 B |
| 2 048 | 4 456 | 1.000 | 1.000 | 3 760 B | 2 048 B |
| 4 096 | 46 953 | 1.000 | 1.000 | 18 512 B | 8 192 B |

An earlier revision recorded spiral retention of 0.000 / 0.001 / 0.240 / 0.929 across these radii and attributed it to the structure. That was wrong. A memory shape addresses the annulus `[centerRadius, radius)`, and the harness had left `centerRadius` at its default of 64, so every inner ring of an origin-centred footprint mapped outside the domain and was refused. With `centerRadius` pinned to zero the spiral retains every mark offered.

The correction matters in both directions: the spiral's footprint is **larger** than previously published, so the layered model's byte advantage at small and mid range was overstated; and its reconciliation figures now describe a structure that actually holds the problem. Any future row where retention is not one is a harness fault, not a footprint result.

### 10d. Direction checks that do hold

Two invariants the objective is required to preserve, and does:

- **A slower device never makes the pageable model look cheaper.** Layered cost at r=4096 is identical on NVMe and HDD at this operating point (224.771 ns), because the chosen configuration is fully resident and the storage term is zero. The storage term can only raise residency, never trade it away, so an operator on spinning rust is not asked to sacrifice RAM first.
- **Storage latency cannot buy back precision.** The chosen inner resolution is invariant across device tiers by assertion.

The first of those also confirms the section 9 caveat about *configuration* adaptivity: within the layered model, blob bytes are close to invariant in outer edge, so the storage term has little to act on when choosing a configuration. Between *models* it is now decisive (10b).

### 10e. Correction: an asymmetric constraint, not a result

The first version of this objective assigned **infinite** cost to a model whose resident bytes exceeded the derived budget of one percent of measured headroom. Applied to the spiral, that converted "uses more RAM than a policy fraction" into "is not a candidate", while the layered model was permitted to pay a finite storage price for the same overrun. The asymmetry, not the structures, is why every device curve previously coincided and why no boundary was found - and it produced exactly the wrong answer on slow storage, eliminating the one model that never waits on a device.

Three changes, all symmetric:

1. **Over-budget residency is priced, not forbidden.** Zero inside the soft budget, quadratic in the fraction of remaining headroom consumed, and infeasible only above measured headroom itself, which is an allocation failure rather than a trade.
2. **The spiral may coarsen its own precision**, because "coarsen until it fits" is the only lever a structure without pageable subsections has. Denying it that lever and then charging it for the footprint it was forced to keep is the same error in another form.
3. **The sweep crosses the budget cliff.** Headroom points of 4 MB and 16 MB were added; above the cliff the storage term is identically zero and the objective degenerates into a constrained memory check with three inert terms, which is where every earlier grid point sat.

The general lesson, and it is the second of its kind in this document after 8c: a hard constraint expressed as infinite cost is not a neutral encoding of a limit. It silently removes candidates the model was built to compare, and the removal is invisible in the output.

## 11. Addressing unit, key width and residency

Sections 8 to 10 held the spiral's addressing unit fixed and let residency fall out of a budget. Section 11 makes both explicit, because both turned out to be levers on the axis that matters - how much RAM the model costs - and one of them is nearly free.

Vehicle: `CoarsenedSpiralBenchmarkTest` (test scope, ADR-080 opt-in tier). Report: `build/reports/rtp-simulation/coarsened-spiral.md`. Populated from the same tiled real save, bad density 0.751.

### 11a. The base unit is one chunk

The finest cell the shipped shape addresses is **one chunk**, not one block: safety selection places a candidate at the centre of a chunk, and biome and safety are chunk-scale facts, so a sub-chunk key carries no information the signal can supply. Cell edge is therefore stated in chunks throughout this section. A 16-block edge is the *full-precision* case, not a coarsening step - an earlier note of mine listed it as one, which was wrong.

That leaves exactly two structurally privileged units: **1 chunk** (full precision) and **32 chunks** (one `.mca` region file, the unit that is actually fetched and screened). Intermediate edges align with nothing the system produces or reads, so they pay quantization for no structural payoff. They are swept anyway, to measure whether that costs anything.

### 11b. Coarsening: measured, and the cost is a ratio

Runs, bytes and over-exclusion against cell edge, at full precision as the baseline. Over-exclusion is measured, not modeled: a coarse cell is marked bad when **any** chunk inside it is bad, which is the only conservative rule available.

| radius | cell | cells addressed | runs | bytes (long / int) | reconcile ns/mark | over-exclusion |
|---|---|---|---|---|---|---|
| 2 048 | 1 ch | 65 536 | 235 | 3 760 / 1 880 | 146 299 | 1.000 |
| 2 048 | 8 ch | 1 024 | 34 | 544 / 272 | 22 480 | 1.838 |
| 2 048 | 32 ch | 64 | 6 | 96 / 48 | 1 252 | **5.284** |
| 8 192 | 1 ch | 1 048 576 | 2 260 | 36 160 / 18 080 | 52 582 | 1.000 |
| 8 192 | 8 ch | 16 384 | 265 | 4 240 / 2 120 | 1 347 | 1.027 |
| 8 192 | 32 ch | 1 024 | 28 | 448 / 224 | 384 | **1.118** |
| 20 480 | 1 ch | 6 553 600 | 18 809 | 300 944 / 150 472 | 162 709 | 1.000 |
| 20 480 | 4 ch | 409 600 | 4 625 | 74 000 / 37 000 | - | 1.012 |

Three findings:

1. **Granularity is a ratio, now measured rather than argued.** A region-file cell at r=8192 costs **1.118x** over-exclusion and buys **81x** fewer bytes and **137x** cheaper reconciliation. The same cell at r=2048 costs **5.284x** over-exclusion. Coarse is nearly free at large range and ruinous at small, which is precisely why the unit must be derived from radius and never pinned.
2. **Reconciliation falls hard with the unit** - 146 299 to 1 252 ns/mark at r=2048 - because the global flush is proportional to the domain. That is the same axis section 10 attributes to the layered model's partitioning, reached by a much cheaper route.
3. **Run count fell monotonically with cell edge at every point swept.** This does *not* establish monotonicity. The mechanism is real: a cell's spiral key is a function of the cell grid, so a coarser grid is a different ordering, and cells that were key-adjacent (one run) can land in coarse cells that are not (two runs). Aggregation is 2D, run-length encoding is 1D, and coarsening changes the map between them. This footprint's clustering simply did not expose it, so cell edge is evaluated point by point and no exponent is fitted through these rows.

### 11c. Half of every stored run is paying for range that cannot occur

The shipped table stores `long` keys and `long` lengths - sixteen bytes per run - and that width is only required above roughly two billion cells. Because the key space is counted in **chunks**, not blocks, it shrinks by 256x for free:

| cell edge | keys fit `int` at r=20 480 | at r=100 000 | at r=400 000 | at r=2 000 000 |
|---|---|---|---|---|
| 1 chunk | yes | yes | no | no |
| 32 chunks | yes | yes | yes | yes |

So at full precision `int` keys suffice out to a **100 km border**, and at region granularity out past 2 000 km. Narrowing the table is a flat **2x** footprint reduction at zero precision cost, available today and independent of every other decision in this document - subject to a `.bin` format version carrying the width and the unit, since a table is meaningless without knowing what its keys count.

### 11d. Residency is a decision, and it decides one way

Both branches are now priced under the same select, reconciliation, cache and precision terms: **resident** pays garbage collection over directory plus blobs and waits on nothing; **paged** pays a full storage term per miss and keeps only the directory in heap.

| tier | headroom | radius | resident ns | paged ns | branch |
|---|---|---|---|---|---|
| NVMe | 512 MB | 8 192 | 190.9 | 190.9 | RESIDENT |
| SSD | 512 MB | 65 536 | 574.0 | 2 184.1 | RESIDENT |
| HDD | 512 MB | 65 536 | 708.8 | 34 693.5 | RESIDENT |
| network | 512 MB | 65 536 | 708.8 | 190 888.1 | RESIDENT |
| HDD | 16 MB | 65 536 | infeasible | 35 557.1 | PAGED |
| network | 16 MB | 65 536 | infeasible | 195 685.6 | PAGED |

The result is one-sided and worth stating plainly: **paging is never cheaper than residency when residency is possible.** Every branch flip in the grid comes from residency being *infeasible* - 212 MB of state against 16 MB of headroom - not from a device being fast enough to make fetching attractive. Storage latency changes only the size of the penalty when residency is unavailable; it never argues for spilling.

This retires the framing that memory can be traded for latency in either direction. The honest rule: **hold it resident; coarsen the addressing unit until it fits; page only when even the coarsest legal unit does not.** 11b is what makes that rule affordable, since a region-file unit at large range costs 12% over-exclusion for an 81x byte reduction.

### 11e. Device classification was measuring the wrong statistic

`StorageLatencyProbe.classify()` buckets by throughput. For a latency-bound decision that is the wrong statistic: a device sustaining 40 MiB/s in 4 MiB reads and one sustaining the same rate in 4 KiB header reads are entirely different cost regimes, and the throughput classifier calls them identical. `classifyByLatency()` was added alongside it, classifying on per-operation latency, which is the term the objective multiplies. A regression test asserts the two classifiers **disagree** on a same-throughput pair (100 us / 4 KiB versus 100 ms / 4 MiB), because agreement there would mean the new classifier adds nothing.

The probe remains observe-only and fed from reads the prefilter performs anyway, so this adds no I/O and no main-thread exposure. It reported `UNKNOWN` in this run - no cold reads occurred - which is the correct answer and not a result.

## 12. Correction: sections 8 and 9 were measured in a unit that does not exist

Section 11a established that the finest addressable cell is one chunk. Sections 8 and 9 predate that and were measured with an **inner cell edge of one block**, sweeping `{1, 2, 4, 8, 16}` blocks as a precision lever. Four of those five points are the same physical resolution as the fifth: a candidate is placed at the centre of a chunk, and there is no curve inside a chunk to address - the inner Hilbert curve runs over the *chunks within* an outer cell. Sub-chunk cells therefore store identical information in up to 256x more cells.

Everything is re-measured at chunk precision. `IndexConfigPlanner.INNER_RESOLUTIONS` is now `{16}` - the inner cell is fixed, not tunable - and coarsening lives where it physically is: the outer edge for the layered model, `SPIRAL_CELL_EDGES` (1 to 32 chunks) for a global curve. The layered vehicle's radii and outer edge are counted in chunks, with the outer cell set to 32 chunks so it aligns with one `.mca` region file.

### 12a. Withdrawn rows

| figure as previously stated | status | corrected |
|---|---|---|
| resident fraction **0.007** at every projected range | **withdrawn** | **0.56 - 0.62** |
| 1.2 MB resident against 198 MB for the spiral at a 100 km border | **withdrawn** | 1.22 MB resident, 1.96 MB fully resident, spiral 0.88 MB |
| precision lever: **11.7x** bytes for **1.53x** over-exclusion | **withdrawn** | **1.53x** bytes for **2.18x** over-exclusion |
| 262 144 candidates per blob read | **withdrawn** | 1 024 chunks per region-sized cell |
| fitted per-doubling coarsening constant `COARSENING_ALPHA = 0.0353` | **withdrawn** | measured over-exclusion table (11b) |

Unaffected, because they were measured on the structures rather than derived from a cell count: reconciliation nanoseconds per mark, retention, selection latency, uniformity, and every row in section 11.

### 12b. What the corrected resident split actually says

Projected from the chunk-unit sweep, one outer cell per region file:

| radius | directory (resident) | fully resident | spiral table | resident fraction |
|---|---|---|---|---|
| 512 ch (8 192 blocks) | 8 192 B | 14 561 B | 7 438 B | 0.563 |
| 2 048 ch (32 768 blocks) | 131 072 B | 220 225 B | 104 980 B | 0.595 |
| 6 250 ch (100 000 blocks) | 1 223 048 B | 1 960 140 B | 883 815 B | 0.624 |

The headline claim of this ADR is therefore **wrong as stated**. The directory is not 0.7% of state; it is roughly **60%**, because a region-sized outer cell holds only 1 024 chunks and the per-cell directory slot is not amortized over the 262 144 blocks the old unit implied. And the **spiral's table is now smaller than the layered directory alone** at every projected range. Whatever case the layered model has, "160x less RAM at border scale" is not it - that number came entirely from the fictitious unit.

The reconciliation case (8c, 11b) is untouched and remains the strongest argument for partitioning. The memory case needs rebuilding from these rows, or abandoning.

### 12c. The precision lever is much weaker than reported

At r=1 024 chunks, coarsening the inner cell from 1 to 32 chunks:

| inner cell | bad inner cells | chunks excluded | runs | fully-resident bytes |
|---|---|---|---|---|
| 1 ch | 122 880 | 122 880 | 1 928 | 58 000 |
| 4 ch | 8 383 | 134 128 | 653 | 42 700 |
| 32 ch | 262 | 268 288 | 262 | 38 008 |

**1.53x** bytes for **2.18x** over-exclusion - a losing trade, and the inverse of the 11.7x-for-1.53x previously claimed. The reason is structural: bytes are floored by the directory, which does not shrink when the inner cell coarsens, so coarsening inside a fixed outer grid buys almost nothing. Coarsening the *outer* unit is the lever that works (11b), and the two are not interchangeable.

### 12d. The boundary surface, re-measured

With the spiral given its own chunk-multiple coarsening lever and a precision floor of one region file, the surface stops being nearly flat - **4 distinct boundary values** rather than 2:

| tier | headroom | load | crossover radius | above it |
|---|---|---|---|---|
| NVMe / SSD | any | any | none in range | LAYERED |
| network | 4 MB | 0.001 | **7 937** | SPIRAL |
| HDD / network | 16 MB | 0.001 | **18 433** | SPIRAL |
| HDD / network | 512 MB | 0.001 | **103 937** | SPIRAL |
| any | any | 0.05 or heavier | none in range | LAYERED |

This is the first run in which the boundary moves with **both** device tier and headroom, so the heuristic is multivariable in effect and not only in form. The direction is the one slow storage predicts: scarcer headroom and slower storage both pull the boundary **down**, toward the model that never waits on a device.

### 12e. The lesson, and it is the third of its kind here

After 8c (a domain-order scan in `flush()`) and 10e (an asymmetric infinite-cost constraint), this is the third correction in this document where a **modelling choice, not a structure, produced the headline**. The common shape: a quantity was measured in a unit the system does not use, and the error was invisible because every row scaled with it consistently. Consistency is not validity. Any figure here derived from a cell count rather than measured on a structure should be checked against the physical unit before it is quoted.

## 13. Collapsing mostly-unsafe sections: the memory argument, rebuilt

Section 12b left the memory case for this model absent: the directory holds one slot per *addressed* outer cell whether that cell is useful or not, so it is roughly 60% of state and larger than the shipped spiral's whole table. Section 12c showed that shrinking the inner cell does not help, because the directory floor does not move with it.

This section measures the lever that moves the floor itself. An outer cell whose bad fraction reaches a threshold **discards its run table and is carried as one bit** in a dense bitmap over the outer grid; it holds no Fenwick slot, has no blob, is never drawn, and is never fetched from storage. Directory cost then scales with *mixed* cells rather than addressed cells.

Vehicle: `LayeredHilbertIndex` gains a `collapseThreshold`; `CollapseThresholdBenchmarkTest`, ADR-080 opt-in tier, excluded from `build`. Report: `build/reports/rtp-simulation/collapse-threshold.md`. Occupancy is the real save tiled outward, bad density **0.751**; units are chunks throughout; marks are staged and flushed once, so a cell collapses on its final count.

### 13a. Thresholds at r=1 024 chunks (16 384 blocks)

Outer cell = 32 chunks = one `.mca` region. Uncollapsed directory for comparison: 4 096 cells x 8 B = **32 768 B**.

| threshold | collapsed cells | mixed fraction | directory | runs | fully resident | over-exclusion | select ns |
|---|---|---|---|---|---|---|---|
| **1.00** (lossless) | 2 440 / 4 096 | 0.404 | 13 760 B | 10 918 | 154 184 B | **1.000** | 164 |
| **0.75** | 2 896 | 0.293 | 10 112 B | 7 518 | 106 088 B | 1.015 | 131 |
| **0.50** | 3 136 | 0.234 | 8 192 B | 4 526 | 66 344 B | 1.043 | 132 |

At outer edge 16 chunks the same shape holds - 201 736 / 149 368 / 112 336 B for 1.000 / 1.008 / 1.022x over-exclusion.

### 13b. What the rows say

- **The lossless point is already the larger half of the win.** At threshold 1.00 nothing is given up - a collapsed cell has no good chunk left in it - and the directory still falls **32 768 B to 13 760 B**, a 2.4x reduction, purely because 60% of region-sized cells on a real save are entirely unusable. That is a clustering fact, not an encoding trick, and it is the first memory saving in this document that costs no precision at all.
- **The thresholded points are the first favourable precision trade measured here.** 0.50 gives **0.430x total bytes for 1.043x over-exclusion** - roughly 2.3x memory for 4% more area excluded. Compare 12c's inner-cell coarsening at the same radius: **1.53x bytes for 2.18x over-exclusion**. Same structure, same save, opposite verdict, because this lever removes directory slots and that one only shrinks blobs.
- **Selection does not pay for it.** 164 -> 131 ns; collapse removes Fenwick weight and run walking, so if anything it is faster.
- **Collapse and coarsening pull against each other, and the rows show it.** A coarser cell is less likely to be *uniformly* unsafe, so lossless collapse removes a falling share of cells as the grid coarsens - mixed fraction **0.283** at outer edge 8 chunks, **0.322** at 16, **0.404** at 32. A planner using both levers must resolve that interaction rather than apply them independently, and the coarsest grid is not automatically the best point.

### 13c. What this does and does not settle

It **rebuilds the memory argument that 12b withdrew**, but on a different basis than the original claim: not "the resident fraction is tiny" - it is 0.23-0.40 of cells, not 0.007 - but "resident cost scales with the boundary between safe and unsafe terrain rather than with the area addressed." That is a scaling claim and it is **not yet verified**; only two radii were swept and no exponent is fitted. Until mixed-cell count is measured against radius, criterion **C5 stays FAILED** rather than being restored on one favourable table.

Three further limits, stated rather than deferred: the saving is a property of the real save's clustering and periodic tiling cannot show correlation beyond one tile period; a thresholded collapse is **irreversible in this vehicle**, since discarding the run table destroys the information needed to un-collapse a cell if terrain knowledge later improves; and the shipped spiral has no equivalent lever, so a fair comparison must ask whether a global RLE curve already merges uniformly-unsafe stretches into single runs - which it partly does, and that comparison has not been made.

## 14. The precision budget decides, not a time-versus-memory trade

Measured by `PrecisionBudgetBenchmarkTest` on the tiled real save, cell edge in chunks, over-exclusion measured against the chunk-precision grid.

### 14a. Selection is faster at a coarse cell, and the mechanism is cache residency

The shipped spiral's own selection cost falls with cell edge, because `rand()` binary-searches the bad-run table and each probe is a random access:

| radius (blocks) | 1 chunk | 32 chunks | runs, 1 -> 32 | speed-up |
|---|---|---|---|---|
| 8 192 | 739 ns | 191 ns | 2 260 -> 28 | 3.9x |
| 20 480 | 1 738 ns | 601 ns | 18 809 -> 238 | 2.9x |

At 18 809 runs the table is far past L2; at 238 runs it is one or two cache lines. Probe count roughly halves, so the residency effect is the larger term. Reconciliation moves much harder over the same interval - 253 874 to 1 464 ns per mark at r=8192, 173x - so selection remains a secondary benefit.

### 14b. Where the curves cross, under a 1.2x cap

Each resource is charted as an elasticity - fraction of the full-precision cost removed per unit of over-exclusion added - and the crossing is where that reaches unity. Endpoint-normalised curves were rejected as vacuous: two curves forced from zero to one over the same interval meet only at its ends.

| radius | coarsest admissible cell | memory crossing | time crossing |
|---|---|---|---|
| 2 048 | 2 chunks | 1.118 | 1.000 |
| 8 192 | 32 chunks | 1.100 | 1.107 |
| 20 480 | 16 chunks | 1.121 | 1.121 |

The crossings sit inside the `[1.043, 1.2]` band the two measured levers bracket, and the cap binds at a different cell edge per range - which is the ratio argument again, now with the admissible set computed rather than asserted.

At r=2048 the time crossing is 1.000: the first coarsening step has time elasticity 0.638, so coarsening does not pay on time at small range even though it pays 4.175 on memory. That is the only point measured where the two resources disagree in direction.

### 14c. The heuristic is unnecessary within this budget

Scored on radii held out of the fit (4 096 and 12 288 blocks) across headroom and mark load, against a brute-force argmin over realised cost:

| rule | worst-case regret |
|---|---|
| coarsest cell the 1.2x budget admits | **0.000** |
| blended elasticity crossing | 0.468 |

Both resources fall monotonically with cell edge inside the cap, so there is no interior optimum: **the precision budget alone determines the pick, and a marginal-efficiency heuristic strictly loses by stopping short of it.** The elasticity crossings retain diagnostic value - they say how much of the saving arrives early - but they are not a decision rule at this budget. A heuristic becomes necessary only if the cap is raised far enough that the two curves diverge in direction, which at r=2048 they already do.

### 14d. The two levers overlap rather than compose

Region rejection applied to an already coarse grid, r=512 chunks:

| outer cell | threshold | collapsed cells | runs | resident bytes | over-excluded chunks |
|---|---|---|---|---|---|
| 8 chunks | 1.00 | 11 757 | 3 113 | 85 172 | 0 |
| 8 chunks | 0.50 | 12 367 | 1 404 | 54 904 | 10 840 |
| 32 chunks | 1.00 | 613 | 2 698 | 38 120 | 0 |
| 32 chunks | 0.50 | 784 | 1 110 | 16 328 | 33 650 |

Coarsening the grid already captures most of what lossless collapse would have removed: at 8 chunks a fully-unsafe cell is common, at 32 chunks it is not, so the lossless collapse falls from 11 757 cells to 613. The remaining collapse saving at 32 chunks comes almost entirely from the thresholded variant, and it costs 33 650 over-excluded chunks - three times the price at 8 chunks. **The levers must be planned jointly; applied independently they double-charge precision for bytes already saved.**

### 14e. Storage format

Reusing the shipped `.bin` is a header extension, not a new format: sorted keys plus lengths already carry the structure, so what is missing is a `cellEdge` field (a table is meaningless without its unit), a `keyWidth` field, and a version bump. Every row measured reports keys fitting `int` at chunk precision, so eight of the shipped sixteen bytes per run pay for range that cannot occur.

## 15. The accuracy metric was normalised wrongly, and correcting it reverses section 11b

Measured by `AddressingUnitBenchmarkTest` on the tiled real save, bad density **0.751**, units in chunks. Report: `build/reports/rtp-simulation/addressing-unit.md`.

### 15a. What was wrong

Every over-exclusion figure in this document up to section 14 was

```
legacy ratio = coarse excluded area / chunk-precision excluded area
```

That divides by the **bad** area, not by the domain. Two faults follow, and the second is the serious one:

1. **It is not stable.** The denominator moves with the domain, so the figure moved with radius over terrain that did not change - 1.118 at r=8192 blocks against 1.253 at r=20480 - and was read as the coarse unit becoming less accurate at range. It was a property of the ratio.
2. **It cannot be read as accuracy.** A world that is 1% bad and one that is 90% bad produce wildly different ratios for the same loss of usable ground. On a save that is three quarters bad, the denominator is enormous, so a very large loss scores as a very small ratio.

The corrected metric is the share of **usable** chunks the unit discards - bounded in `[0, 1]`, scale-free, and directly the "how over-eager is this" number an operator is entitled to a cap on.

### 15b. Corrected rows, with the legacy ratio alongside

| cell edge | usable ground discarded (r=256 / 512 / 1280 chunks) | legacy ratio, same points |
|---|---|---|
| 1 chunk | 0.000 / 0.000 / 0.000 | 1.000 |
| 2 chunks | 0.013 / 0.020 / 0.018 | 1.056 / 1.007 / 1.009 |
| 4 chunks | 0.037 / 0.060 / 0.053 | 1.167 / 1.020 / 1.026 |
| 8 chunks | 0.089 / 0.133 / 0.119 | 1.399 / 1.044 / 1.058 |
| 16 chunks | 0.200 / 0.275 / 0.251 | 1.900 / 1.091 / 1.122 |
| **32 chunks** (one `.mca`) | **0.465 / 0.529 / 0.507** | 3.091 / **1.175 / 1.247** |

**Section 11b is withdrawn.** "Coarsening the addressing unit to one region file is nearly free at large range - 1.118x over-exclusion for 81x fewer bytes" describes a configuration that **throws away roughly half of all usable ground**. The 81x byte saving and the 137x reconciliation saving are real and unaffected; the price was understated by more than an order of magnitude.

Under a 20% cap the coarsest defensible unit is **8 chunks - 128 blocks - not 32**. Section 14's `[1.043, 1.2]` band and its crossings are expressed in the withdrawn units and cannot be carried over.

### 15c. The radius dependence was composition, not range

Under the corrected metric the variation across radii is 1.14x-1.60x and **non-monotone**: the worst point is r=512 chunks at every unit, with r=1280 lower. The largest radius is never the worst, so coarsening does not become more lossy the further out the domain is addressed, and the cap can be applied per domain rather than per ring.

Tiling was wrongly blamed for this in discussion. Tiles are whole region files mirrored at region granularity, so a 32-chunk cell never straddles a tile join. What changes with radius is how much of the tile period the domain covers, which moves the sample's composition - and therefore the bad density sitting in the legacy ratio's denominator.

### 15d. Choosing the unit at startup, reload and rebuild

`AddressingUnitSelector`, test scope. The decision is **sampled from the world in front of it**, never read from a radius-keyed table: a radius-keyed table cannot distinguish an archipelago from a continent, and those have very different perimeter-to-area ratios at the same radius. That distinction is exactly what the deciding quantity is made of.

- **One sampling pass serves every candidate.** Region-aligned 32x32-chunk blocks are drawn uniformly; each yields 1 024 chunk facts, and every unit from 1 to 32 partitions the same block. All units are therefore estimated from identical evidence, and cost is one block per 1 024 facts regardless of the unit under test.
- **Blocks are the sampling unit, not chunks.** Chunks within a region are correlated - that is the entire reason run-length encoding works here - so treating 1 024 correlated facts as 1 024 independent draws would understate the interval by roughly the square root of the cluster size. Variance is taken by delete-one-block jackknife.
- **The decision uses the upper end of the interval.** A sampling error on the permissive side of the cap is a silent accuracy loss, which is the class of fault S-004 exists to prevent. Measured slack over truth: 0.000 at 1 chunk rising to 0.188 at 32.
- **Two constraints, and each binds somewhere.** The accuracy cap, and a guard requiring at least 64 cells per domain edge so `expand` does not quantize visibly. At r=256 chunks the guard refuses a unit accuracy would have allowed; at r=1280 accuracy refuses a unit the guard would have allowed. A rule carrying only one would be wrong at one end of the range.
- **No time-versus-memory weighting.** Inside the cap both resources fall monotonically with the unit (14c), so the coarsest admissible unit is the argmin.

Scored against exhaustive ground truth over the whole domain:

| radius (chunks) | sampled pick | exhaustive pick | realised loss | binding constraint |
|---|---|---|---|---|
| 256 | 4 chunks | 4 chunks | 0.037 | guard |
| 512 | 8 chunks | 8 chunks | 0.133 | accuracy |
| 1 280 | 8 chunks | 8 chunks | 0.119 | accuracy |

Exact agreement at every radius, and every pick inside the cap when checked against the full domain rather than against the sample.

### 15e. Transitions, so a reload cannot destroy learned state

Three rules, in order:

1. **A precision violation forces a change**, whatever it costs. Continuing on a stored unit now outside the cap means discarding ground the operator did not agree to lose.
2. **Coarsening is a lossless ratchet.** A coarser unit is adopted only when it is a multiple of the stored one, because then the table folds upward and nothing learned is thrown away. Measured: stored 1 chunk -> chosen 8, folded.
3. **Refinement is never chased.** A coarse table cannot supply fine detail - the information was destroyed - so going finer means relearning, and happens only under rule 1. Measured: stored 32 chunks at r=1280 is refused (bound 0.571 against a 0.20 cap) and the transition reports that state does not survive.

Persistence follows 14e: `cellEdge` and `keyWidth` in the `.bin` header plus a version bump. On a unit mismatch, fold up if the new unit is a multiple of the stored one, otherwise relearn.

### 15f. Two things this changes about the direction

- **The compression is much lossier than reported, so the memory case narrows further.** Section 12b already withdrew the resident-fraction claim; the byte savings that remained were priced against an understated precision cost. At a 20% cap the available saving is the 8-chunk row, not the 32-chunk row.
- **A cap is now genuinely necessary rather than decorative.** Under the legacy ratio no swept configuration looked dangerous. Under the corrected metric one region file per cell is unusable on this save, and nothing in the previous rounds of measurement would have caught it.

### 15g. On better compression, since this is a compression problem

Asked directly whether a better algorithm exists for this feature set. Honestly: **the lossy step is the problem, not the encoder.** Coarsening to 32 chunks costs ~50% of usable ground because a 1 024-chunk cell almost always contains one bad chunk at 0.751 density - that is a property of the *quantization*, and no entropy coder recovers information already discarded. So the ranking is:

1. **Keep full precision and encode better.** A **Roaring bitmap** keyed by region container is the strongest candidate: per-container density choice (array / bitmap / run) degrades gracefully at both sparse and dense extremes, where a single global RLE picks one, and its containers map onto region files so the partitioning this ADR argues for comes free. It is also citable - column stores and Lucene use it - which matters for a competitor claim. A **k2-tree** is the space-optimal option for a clustered sparse binary matrix and supports rank/select in place, which is what uniform selection needs; it is harder to update incrementally. **Elias-Fano** is near-optimal for the monotone key arrays specifically and composes with either.
2. **Lossless collapse of uniformly-unsafe cells** (section 13, threshold 1.00) - a 2.4x directory reduction at zero precision cost, and it is a clustering fact rather than an encoding trick.
3. **Lossy coarsening last**, capped, and now correctly priced.

None of these is proposed here; they are named so the next round measures alternatives rather than defends this one.

### 15h. Sharing state over a database

Planned optional cross-server sharing changes what the unit costs, in one direction that helps and one that does not:

- **Helps:** shared state means the learned table is populated by every server on the network, so full precision becomes affordable sooner and the incentive to coarsen weakens. A per-region container encoding is also the natural row key.
- **Does not:** a remote store puts the storage term at ~50 ms per operation (11d's network tier), which is 514x the layered reconciliation cost - so a shared table must be **replicated resident**, not paged, and the unit must be agreed network-wide. Two servers running different `cellEdge` values against one table would silently reinterpret each other's keys, which is the same fault as reading a `.bin` without its unit.
- **Unresolved:** who owns the unit decision when servers see different radii, and how a fold-up is coordinated so no server reads a half-folded table.

## 16. A locality-preserving key, measured: the win is in the existing lossy knob, not at full precision

Measured by `SpiralHilbertBenchmarkTest` on the tiled real save, bad density **0.751**, one chunk per key on both curves. Report: `build/reports/rtp-simulation/spiral-hilbert.md`.

Vehicle: `SpiralHilbertSquare`, a `Square` subclass whose **only** change is the bijection. The shipped spiral orders coarse points and each point's slice of the key space expands in place into that point's Hilbert traversal of the chunks inside it:

```
key = spiralIndex(point) * P^2 + hilbertIndex(chunk within point)
```

The key space stays one contiguous `[0, range)` interval, so marks, probation, coalescing, rebuild, `badSum`, `adjustRange`, `sample`, `resolve`, `nearestGood`, `expand`, `mode` and selection are all inherited unchanged. `spatialResolution` keeps its exact existing meaning - a coalescing gap in 1D key units - and is what the comparison turns on.

### 16a. At full precision the new curve is slightly worse

| radius (chunks) | spiral runs | best hybrid runs | hybrid vs spiral |
|---:|---:|---:|---:|
| 128 | 236 | 245 (point 32) | 0.96 |
| 256 | 1 164 | 1 231 (point 32) | 0.95 |
| 512 | 2 263 | 2 462 (point 32) | 0.92 |

The expected mechanism - a compact feature cut once per spiral revolution against O(1) runs under a Hilbert traversal - **does not appear at this density**. At 0.751 bad, the run count is set by the perimeter between usable and unusable ground rather than by compact features in an otherwise-clear domain, and the hybrid additionally pays a seam at every point boundary because point orientation is fixed rather than matched. Both effects push the wrong way and the second is a vehicle limitation, so these rows are a lower bound.

### 16b. At equal accuracy it is 3.3x-5.8x smaller, and that is the result

Comparing at the same knob setting compares nothing: one key unit is not the same distance on two different curves. Compared instead at a tolerated loss of usable ground (r=256 chunks, point 32 chunks):

| loss cap | spiral runs | hybrid runs | hybrid smaller by |
|---:|---:|---:|---:|
| 0.05 | 1 102 | 209 | **5.27x** |
| 0.10 | 686 | 209 | **3.28x** |
| 0.20 | 686 | 119 | **5.77x** |

The knob's behaviour on the two curves is qualitatively different, and the reason is geometric. Under the plain spiral a gap bridges keys that can be on different rings and far apart in 2D, so a merge sacrifices ground unrelated to any mark; at gap 1 024 the spiral is down to 15 runs having discarded **0.880** of the usable world. Under the Hilbert traversal a gap bridges keys that are spatially close, so at the same setting the hybrid holds 52 runs having discarded **0.266**.

This is the **first favourable memory trade in this document that requires no new structure and no new knob**. Section 13's collapse lever needed a bitmap and a threshold; section 11b's coarsening discarded 46-53% of usable ground. Here the lossy lever is the one already shipped, applied to a better-shaped key space.

### 16c. Selection is faster too, and precision is unchanged

Selection cost falls at every radius - 273 -> 177 ns at r=128, 715 -> 208 ns at r=512 - despite the hybrid holding *more* runs at full precision, which points at cache behaviour rather than table size: binary-search probes on a locality-preserving curve land in a narrower key neighbourhood. Reconciliation is within noise of the spiral and is not claimed either way.

Precision is asserted equal, not assumed: both curves are held to a loss of at most `1e-3` at the finest setting. Neither reaches exactly zero, and the reason is in the shipped coalescer rather than in either curve - `setSpatialResolution` clamps to a minimum of one and the merge test is `nextKey <= curEnd + resolution`, so a single usable chunk between two bad ones is bridged by both.

### 16d. What this does not establish

- **One density, one save, three radii.** The full-precision result in 16a is a density effect and would likely reverse on a sparser world, where compact features exist in clear ground. Untested.
- **Point orientation is not seam-matched.** Matching each point's Hilbert orientation to its predecessor across the spiral seam would raise the cross-boundary merge rate; the gain is unmeasured, so 16a and 16b are both lower bounds.
- **No secondary tables.** The cache-locality split into per-point leaves - the natural companion to this key space, and the mechanism ADR-082 argues for - is not built, so the 16c timings are for a single flat table.
- **Persistence is unchanged and would need the header extension of 14e**, now carrying the curve identity as well as the unit and key width. A table read under the wrong curve is silently reinterpreted, which is the same fault class as reading one without its unit.

## Consequences

**Positive:**
- **A locality-preserving key makes the shipped lossy knob 3.3x-5.8x more efficient** (16b), with no new structure, no new operator setting and no change to the addressed unit. It is the only memory result in this document that reuses machinery rather than adding it. The caveat travels with it: at full precision the same curve holds slightly *more* runs (16a), so the benefit lies in how `spatialResolution` behaves and not in the encoding itself.
- Reconciliation becomes range-independent (exponent `+0.232`), which is the axis ADR-083 claimed and did not deliver.
- Configuration is derived from measured headroom, storage latency, load and range, with regret against the measured optimum bounded and reported rather than asserted.
- Large-range behaviour is measured to a 20 km radius rather than extrapolated.
- Exact per-epoch uniformity, with no subsampling and no precision discard.
- ~~The resident floor is ~0.7% of total state at every projected range.~~ **Withdrawn - see 12b.** At chunk precision the resident directory is roughly 60% of total state and the spiral's whole table is smaller than that directory. The memory case for this model is not established.
- `expand` and the spiral's learned-state semantics survive intact; the change is additive to ADR-001 rather than a replacement.
- Precision degradation, when needed, is quadtree-aligned and locally bounded - but inside a fixed outer grid it buys 1.53x bytes for 2.18x over-exclusion (12c), so it is a last resort rather than a lever.
- **Narrowing the run table to `int` is a free 2x**, since the key space counts chunks and fits 32 bits out to a 100 km border at full precision (11c). It is independent of every other decision here.
- ~~**Coarsening the addressing unit to one region file is nearly free at large range** - 1.118x over-exclusion for 81x fewer bytes and 137x cheaper reconciliation at r=8192 (11b).~~ **Withdrawn - see 15b.** The byte and reconciliation savings stand; the price does not. A region-file cell discards **46-53% of all usable ground**, and the 1.118x figure was small only because it divided by a bad area covering three quarters of the domain. Under a 20% accuracy cap the coarsest defensible unit is **8 chunks**.
- **Collapsing uniformly-unsafe outer cells to one bit is a lossless 2.4x directory reduction** on the real save (13a), and the thresholded form is the only favourable precision trade measured in this document: 0.430x total bytes for 1.043x over-exclusion at a 50% threshold. A collapsed region-aligned cell is also a file that is never opened.
- **Coarsening also makes the shipped spiral's own selection faster** - 3.9x at r=8192 and 2.9x at r=20480 (14a) - because the run table shrinks from far past L2 to one or two cache lines. Cache residency, not probe count, is the larger term.
- ~~**The precision budget is the whole decision inside a 1.2x cap.**~~ The conclusion survives; the cap does not. Both resources still fall monotonically with the unit, so the coarsest admissible unit remains the argmin and no time-versus-memory planner is needed - but the `1.2x` bound is expressed in the withdrawn normalisation (15b) and is replaced by a cap on usable ground discarded.
- **The addressing unit can be chosen correctly from a sample of the world** (15d). Region-aligned block sampling with a jackknife upper bound reproduces the exhaustive pick at every radius measured - 4 / 8 / 8 chunks - and every pick holds inside the cap when checked against the full domain. A radius-keyed table is not needed, and could not distinguish an archipelago from a continent anyway.
- **A reload cannot destroy learned state** (15e). Coarsening is a lossless ratchet applied only to multiples of the stored unit; refinement, which a coarse table cannot supply, happens only when the stored unit breaches the cap.

**Negative / unresolved:**
- **The planner's coefficients are machine-relative and partly artifactual.** The `0.27 ns` per-outer-cell reconciliation term includes memory locality over the vehicle's array-of-arrays layout, so it is an upper bound on the fine-grid penalty and biases the planner toward coarser cells. A production layout would need recalibration.
- **The precision weight is a policy number, not a measurement.** Regret says the coefficients rank configurations correctly; it does not say the weights are right.
- **Device adaptivity is demonstrated between models, not within one.** Storage latency decides which model wins (10b) but barely moves the chosen configuration, because serialized blob bytes are close to invariant in outer edge. The boundary radius is also modeled, and scales with a policy heap-pressure constant.
- **The layered model is not the universal answer.** Above roughly a 100 km radius on HDD or network-backed storage at light mark load, the fully resident spiral is the argmin. Any deployment guidance must carry that carve-out rather than recommend one structure. The boundary radius has already moved 10x once, when residency became a costed branch (10b), so it should be treated as order-of-magnitude guidance and not as a threshold.
- **The single-outer-cell degeneracy still exists** below the point where two outer cells fit; the planner avoids it by construction, but the regression itself is unfixed.
- **Crash consistency has no design.** The directory must stay consistent with per-blob generations; the shipped `.bin` format has no story for a torn multi-blob write.
- **Paging policy is unspecified.** Skew is expected (spawn-adjacent cells are marked constantly), so a resident-set policy is required and reintroduces a tunable.
- **No storage measurements.** Blobs are heap objects in the vehicle; nothing is persisted, so blob sizes are computed from run counts and the paged path's real latency is unmeasured.
- Boundary fragmentation costs up to 28% more runs at fine granularity.
- **Paging is never the cheaper branch when residency is possible** (11d). Every branch flip measured comes from residency being infeasible, not from a device being fast. Storage latency sizes the penalty for spilling; it never recommends it. Any claim that memory can be traded for latency in both directions is unsupported.
- **Coarsening was monotone across every point swept, but is not proven monotone.** A coarser grid is a different spiral ordering, so run count can rise; this footprint did not expose it, and a planner must therefore evaluate cell edge point by point rather than fit an exponent (11b).
- **The addressing unit must be persisted with the table.** Both 11b and 11c change what a key means, so a `.bin` version carrying unit and key width is a prerequisite, not a detail.
- **The memory argument for this model is currently absent.** With sections 8 and 9 corrected to chunk precision (12b), the layered directory alone exceeds the shipped spiral's entire table at every projected range. The remaining case rests on reconciliation locality, not on footprint, and the ADR should be read that way until a corrected memory argument exists or the direction is abandoned.
- **Coarsening the inner cell is not the same lever as coarsening the outer cell** (12c) and the two were previously conflated. Only the outer unit moves footprint meaningfully.
- **The collapse result is a two-point table, not a scaling law** (13c). C5 stays FAILED until mixed-cell count is measured against radius; a thresholded collapse is irreversible as implemented; and no comparison has been made against the shipped spiral, whose global RLE already merges uniformly-unsafe stretches into single runs.
- **The elasticity heuristic is not justified at this budget** (14c). It was built to place the pick between 1.043x and 1.2x and it loses to the cap alone. The crossings - memory 1.100-1.121, time 1.000-1.121 - are diagnostic rather than a decision rule, and a heuristic becomes necessary only if the cap is raised until the curves diverge in direction, which they already do at r=2048 where the first coarsening step pays 4.175 on memory and 0.638 on time.
- **The two memory levers overlap rather than compose** (14d). Coarsening the grid already removes most of what a lossless collapse would have: the lossless collapse falls from 11 757 cells at outer edge 8 chunks to 613 at 32 chunks, and the residual saving at 32 chunks comes from the thresholded form at three times the precision price. Applied independently the two levers double-charge precision for bytes already saved.
- **Every over-exclusion figure before section 15 is expressed in a normalisation that cannot be read as accuracy** (15a). Dividing by the bad area rather than by the domain made a loss of half the usable world score as 1.118x, and made the figure move with radius over terrain that did not change. This is the fourth correction in this document where a modelling choice rather than a structure produced the headline, and the pattern is identical each time: consistency across rows is not validity.
- **The residual radius variation in the corrected metric is unexplained** (15c). It is 1.14x-1.60x, non-monotone, worst at the middle radius, and attributed to how much of the tile period the domain covers. That attribution is plausible and unproven; a non-tiled save at several radii would settle it, and no bound on the variation is asserted because three radii on one save cannot support one.
- **The accuracy cap and the granularity guard are policy numbers.** A 20% loss of usable ground and 64 cells per domain edge are stated rather than derived; the first should become an operator-visible guarantee, and the second is a judgement about how continuous `expand` must look.
- **The locality result rests on one density, one save and three radii** (16d). The full-precision regression in 16a is expected to be a density effect - at 0.751 bad, run count is set by the usable/unusable perimeter rather than by compact features in clear ground - but that is an explanation, not a measurement, and a sparser world is untested. Point orientation is also not matched across the spiral seam, so both the regression and the 3.3x-5.8x figure are lower bounds, and the per-point secondary tables the key space is designed to carry are not built.

## Relationship to prior ADRs

- **[ADR-083](ADR-083-two-tier-probabilistic-region-routing.md)** is superseded as a decision and retained as an alternative considered. Its measurement sections remain the evidence that redirected this work. Specifically rejected: the area-growth premise (measured false), `supportsExpand() = false`, and `PI_MIN` subsampling.
- **[ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)** is retained and reused, coarsened.
- **[ADR-081](ADR-081-unified-blocked-biome-run-table.md)** / **[ADR-082](ADR-082-cache-conscious-b-plus-tree-primitive-arrays.md)** remain applicable *within* a blob; this ADR decides the partitioning, not the per-blob encoding.

## Approval gate

**This ADR is Proposed and the D-005 gate is closed.** The vehicle is test-scope, unregistered, and referenced by no shipped code. Evidence is not permission. Before this could be accepted, the following must be settled: a crash-consistency design for the directory-to-blob relationship, a paging policy under hot-cell skew, persisted-blob storage measurements on a real device, and recalibration of the planner's coefficients against a production layout rather than the vehicle's.

One correction in this document (section 8c) shows why that order matters: a measurement artifact in the vehicle produced a favourable exponent that survived two rounds of discussion before being caught. Figures here should be treated as provisional until they have been reproduced against the layout that would actually ship.
