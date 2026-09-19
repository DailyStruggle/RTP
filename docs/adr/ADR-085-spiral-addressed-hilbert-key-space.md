# ADR-085 - Spiral-Addressed Hilbert Key Space for Learned State

**Status:** Proposed
**Date:** 2026-09-06

## Context

Four proposals ([ADR-081](ADR-081-unified-blocked-biome-run-table.md), [ADR-082](ADR-082-cache-conscious-b-plus-tree-primitive-arrays.md), [ADR-083](ADR-083-two-tier-probabilistic-region-routing.md), [ADR-084](ADR-084-layered-spiral-hilbert-rle-index.md)) attacked `MemoryShape` learned-state storage. Measurement under [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md) rejected all four as decisions and left five results that any replacement must respect:

1. **Cell memory efficiency is not the axis.** No candidate beats the shipped spiral RLE on bits per marked cell, and at fixed mark population the spiral's footprint is flat in range. The operator-facing question is how much RAM a range costs and how much is then free for time optimisation.
2. **The accuracy metric was wrong until ADR-084 section 15a.** Over-exclusion was reported as a ratio against the *bad* area, which moves with the domain and scored a loss of roughly half the usable world as `1.118x`. Accuracy shall be stated as **the share of usable ground a table refuses**.
3. **Every memory lever measured so far spent precision.** Coarsening the addressed unit to one region file discards 46-53% of usable chunks; the collapse threshold discards more the lower it is set. A lever that buys bytes by throwing away usable ground is bounded by how much loss an operator will accept, which is small.
4. **The finest addressable unit is one chunk.** Safety and biome are chunk-scale facts and a candidate is placed at a chunk centre, so a sub-chunk key space is fictitious precision. ADR-084's `innerRes` sweep over 1-8 blocks measured nothing and voided that ADR's headline memory claim.
5. **Granularity is a ratio.** Any fixed quantum is simultaneously free at 100 km and fatal at 1 km, so a quantum must be derived from range, never pinned.

The remaining unexploited property is **spatial locality of the key space itself**. The shipped Archimedean spiral is monotone in radius, which is what makes `expand` work, but its 1D adjacency is angular: two cells one key apart are on the same ring and can be far apart in 2D, and a compact 2D feature is cut once per revolution. Every consequence of that - run fragmentation, and a `spatialResolution` gap that merges unrelated ground - is a property of the curve, not of the encoding.

## Decision

Keep the shipped spiral as the **coarse ordering** and expand each of its points in place into that point's Hilbert traversal of the chunks inside it, giving a single contiguous key space at **one-chunk precision**:

```
key = spiralIndex(point) * P^2 + hilbertIndex(chunk within point)
```

where `P` is the point edge in chunks, a power of two.

### 1. Only the bijection changes

`xzToLocation` and `locationToXZ` are the only curve-specific members of `MemoryShape`. Marks, probation, coalescing, `flushAndRebuild`, `badSum`, `adjustRange`, `sample`, `resolve`, `nearestGood`, `expand`, `mode` and selection all operate on 1D keys and are inherited unchanged. There is no directory, no Fenwick tree, no per-cell blob, no second table, and therefore no new uniformity proof: selection remains one random `long`, one binary search, one inverse map.

### 2. No precision is spent

The addressed unit stays one chunk. The saving is expected from **run count** instead: a feature contained within one point occupies a contiguous key interval regardless of its area. This is the first lever in this line of work whose memory win does not require discarding usable ground.

### 3. `spatialResolution` is reused with its existing meaning

It remains a coalescing gap in 1D key units, with the shipped merge test. What changes is the geometry the gap corresponds to: under the Hilbert traversal a bridged gap joins cells that are spatially close, so merges are approximately quadtree-aligned and an "ocean-width" floor is meaningful. The knob's semantics, config key, persistence and operator documentation are untouched.

### 4. Point orientation is matched to the spiral's travel direction

Consecutive spiral points are 2D adjacent, but the canonical Hilbert curve runs from `(0, 0)` to `(P - 1, 0)`, so its exit corner is not generally adjacent to the next point's entry corner. Each point's traversal is therefore mapped through one of the eight symmetries of the square, selected from the direction the spiral is travelling on that ring side (`+z` east, `-x` north, `-z` west, `+x` south, read off the shipped octant decomposition). The symmetry is **derived from the point coordinates**, so the bijection stays a closed-form pair of functions with no side table. Four points per ring sit on a corner where the direction changes within the point and are unmatched by construction.

### 5. `expand` is unaffected

The key space is still monotone in radius at point granularity, so `adjustRange` and expansion work as shipped. Expansion quantises to the point edge, which is the granularity-is-a-ratio constraint and the reason `P` must be derived from range rather than pinned. `supportsExpand()` stays true; ADR-083's concession is rejected for the reason recorded in ADR-084 - `expand` declares a capability, and spiral growth is one implementation of it.

### 6. Persistence is a header extension, not a format

The `.bin` already stores sorted keys plus lengths, which is exactly what a different curve produces. Landing this needs a version bump plus `curve` and `P` header fields, since a table is meaningless without its unit, plus `keyWidth`: chunk-counted keys fit `int` out to a 100 km border, so 8 of every shipped 16-byte run pays for range that cannot occur. On a header mismatch the table is discarded and relearned unless the stored unit divides the new one.

## Criteria

| # | Criterion | Status |
|---|---|---|
| C1 | Bijection over the whole domain, both orientations | **MET** - 0 collisions, 0 round-trip failures at `P` = 8/16/32 chunks |
| C2 | Local curve unit-step continuous | **MET** - asserted at orders 1-5 |
| C3 | No precision spent relative to the shipped spiral at the finest setting | **MET** - both curves lose the same near-zero share |
| C4 | Fewer runs at equal accuracy | **MET** - 3.3x-5.8x smaller table under a loss cap |
| C5 | Fewer runs at full precision | **NOT MET** - the hybrid holds 1.01x-1.16x *more* runs |
| C6 | Reconciliation no worse | **MET** at coarse settings, **UNPROVEN** at full precision |
| C7 | Selection no worse | **MET** - faster at every setting measured |
| C8 | Point edge derived from range, not pinned | **NOT BUILT** |
| C9 | Crash-safe persistence across a curve change | **NOT BUILT** |

## Measurements

Tiled real save (bad density 0.751), `:rtp-core:simulationBenchmark --tests '*SpiralHilbertBenchmarkTest*'`, report `build/reports/rtp-simulation/spiral-hilbert.md`. All rows at one-chunk precision. Accuracy is the corrected metric: share of usable chunks the rebuilt table refuses.

### 8a. Side by side at identical settings, r = 256 chunks, P = 32 chunks

Radius, mark set and coalescing gap held fixed; only the curve changes.

| gap | curve | runs | bytes | usable ground discarded | select ns/op |
|---|---|---|---|---|---|
| 1 | spiral | 1 164 | 18 624 | 0.000 | 721 |
| 1 | hilbert canonical | 1 231 | 19 696 | 0.000 | 490 |
| 1 | hilbert seam-matched | 1 226 | 19 616 | 0.000 | 223 |
| 16 | spiral | 1 102 | 17 632 | 0.002 | 298 |
| 16 | hilbert seam-matched | **352** | 5 632 | 0.021 | 174 |
| 256 | spiral | 255 | 4 080 | **0.368** | 312 |
| 256 | hilbert seam-matched | **116** | 1 856 | **0.098** | 235 |
| 4 096 | spiral | 1 | 16 | **0.989** | 142 |
| 4 096 | hilbert seam-matched | 13 | 208 | 0.621 | 179 |

The `gap = 256` row is the result: at the identical knob the hybrid holds **2.2x fewer runs while discarding 3.8x less usable ground**. Both halves of the trade move the same way, which no previous lever achieved. At `gap = 4 096` the spiral has destroyed the world (98.9% of usable ground refused, one run left) while the hybrid still admits 37.9% of it.

### 8b. Iso-accuracy, r = 256 chunks

Smallest table reachable without breaching a loss cap, over the whole gap sweep.

| loss cap | spiral runs | hybrid runs | hybrid smaller by |
|---|---|---|---|
| 0.05 | 1 102 | 209 | **5.27x** |
| 0.10 | 686 | 209 | 3.28x |
| 0.20 | 686 | 119 | **5.77x** |

### 8c. Full precision - the unfavourable half

At `gap = 1` the hybrid holds **more** runs than the spiral: 0.887-0.987 of the spiral's count at r = 128 chunks, 0.865 at r = 256 chunks with `P = 8`. Two mechanisms, both real:

- **Density.** At a bad density of 0.751 the *usable* ground is the sparse phase, so runs are counted against a mostly-bad background and locality has little to gather. A sparser world is the interesting case and is not measured.
- **Seams.** A point boundary cuts a run whenever the traversal leaves the point, and matching only helps on the straight sides.

So the design's claim is specifically about **byte cost at a stated accuracy**, not about run count at full precision. Stating it the other way round would be false.

### 8d. Seam matching

Matching the orientation to the spiral's travel direction removes runs by **1.000x-1.031x** - real, small, and far below what the mechanism suggested. The corner points are the stated structural limit; the rest is that most run cuts happen inside a point at this density, not at its boundary. Timing differences between the two orientations (up to 3x on selection at `gap = 1`) exceed what a 3% table difference explains and are treated as rig noise, not as a result.

### 8e. Drawn output

`build/reports/rtp-simulation/img/spiral-hilbert-*.png`, generated by the same run that emits the rows:

- `keyorder-spiral` / `keyorder-hilbert` - each chunk coloured by its position along that curve's key space in repeating bands, so one band is one contiguous stretch of keys. Bands are rings on the spiral and blobs on the hybrid; this is the mechanism, visible directly.
- `occupancy` - ground truth from the save.
- `discarded-spiral` / `discarded-hilbert` - usable chunks each table refuses after coalescing at an identical gap of 256 key units. Measured on the same domain: **0.235 against 0.064**.

What they show: the spiral's discarded ground follows **long thin arcs along its rings**, while the hybrid's losses **hug the unsafe terrain** as local blobs, because a bridged gap there joins cells that are spatially adjacent. Same knob, same domain, same mark set.

The arc length is arithmetic and is **not** a full revolution in general. A ring at chunk-radius `k` holds about `8k` cells, so a gap of `g` key units bridges `g / 8k` of a revolution: at `g = 256` that is a whole ring only inside `k = 32`, and about a quarter of one at the `k = 128` rim. The mechanism is therefore that a bridged spiral gap spans an angular arc whose far end is unrelated to the terrain that caused the merge, and that the arc covers a larger share of the ring the closer to the centre it sits - degenerating to a full revolution only near the origin, where the rings are short. An earlier draft of this section stated the full-revolution case as the general one; that was wrong, and if the shipped spiral did bridge whole revolutions at operator-scale radii it would be a defect rather than a curve property.

The images exist because two published errors in this line of work were geometric claims a picture would have refused. They are the shareable artifact of the comparison.

## Consequences

### Favourable

- The first memory lever measured here that does not spend precision to buy bytes: 3.3x-5.8x smaller table at a stated accuracy cap, and at an identical knob both fewer runs and less loss.
- Selection is faster at every setting measured, from the same cache mechanism coarsening exploited, without coarsening's loss.
- Landing cost is small: one bijection pair, one `.bin` header extension, no new structure, no new uniformity argument, no second table to keep consistent.
- `spatialResolution` becomes geometrically meaningful, so an ocean-width floor is enforceable rather than aspirational.

### Unfavourable

- **Full precision is worse**, by up to 16% more runs. An operator running at `gap = 1` gets a slightly larger table for faster selection, which is a trade and not a win.
- Every figure is one save at one density, tiled. Density 0.751 is the adverse case for locality and a sparse world is unmeasured, so the direction of C5 could change.
- Expansion quantises to the point edge and the derivation rule for `P` is not built (C8), so small-radius behaviour is unverified - the same gap that left ADR-084's C8 partial.
- Persisted state written under one curve is unreadable under another (C9). Until the header work exists, a curve change means relearning.
- Reconciliation at full precision is not established: the coarse-setting figures are favourable, the `gap = 1` figures are within noise of the spiral's.

## Tried and declined

Retained as evidence, not as options:

- **ADR-083, two-tier probabilistic routing.** Rejected. Its area-growth premise is measured false (perimeter-order, exponent 0.500), its `PI_MIN` subsampling discards precision where demotion would preserve it, and its `supportsExpand() = false` was an unforced concession.
- **ADR-084, layered directory over per-cell blobs.** Rejected. The resident-fraction argument was an artifact of a sub-chunk unit; at chunk precision the directory is 0.56-0.62 of total state and the shipped spiral's whole table is smaller than the directory alone.
- **Coarsening the addressed unit.** Declined as a default. 46-53% of usable ground discarded at one region file per cell.
- **Thresholded collapse of near-uniform cells.** Declined as a default, retained as a possible operator knob. It does not compose with coarsening - most of its saving is already taken.

## Maybe later

- **Roaring bitmap containers keyed by region.** Still the best full-precision encoder candidate, orthogonal to the curve, and the one a competitor's engineer would recognise.
- **Secondary per-point tables** for cache locality and reconciliation locality, sized in bytes to land a leaf in L1 and the directory in L2. Keys stay globally monotone so a split is a table boundary, not a key boundary.
- **Storage-tier residency planning.** Relevant the moment persistence of a subsection is real.
- **k2-tree, Elias-Fano** on the key arrays.

## Status of the gate

D-005 remains closed. `SpiralHilbertSquare` and `SpiralHilbertBenchmarkTest` are test-scope only, ADR-080 opt-in tier, excluded from `build`; nothing is registered with the shape factory or referenced by shipped code. Approval would mean moving the bijection into a shipped shape variant, adding the `.bin` header fields with a version bump, deriving `P` from range, and adding `TRACEABILITY.md` rows.

## References

- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) - the shipped spiral mapping
- [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md) - benchmark tier and provenance tiers
- [ADR-083](ADR-083-two-tier-probabilistic-region-routing.md), [ADR-084](ADR-084-layered-spiral-hilbert-rle-index.md) - alternatives considered, with their measurement records
- `docs/dev/DESIGN.md`, `docs/dev/REQUIREMENTS.md` section 3
