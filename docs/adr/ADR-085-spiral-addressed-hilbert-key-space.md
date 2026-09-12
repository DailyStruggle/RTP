# ADR-085 - Spiral-Addressed Hilbert Key Space for Learned State

**Status:** Accepted (2026-09-06), audited 2026-09-10 - see section 23
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
| C5 | Fewer runs at full precision | **MET** - 4.7x-5.0x fewer runs at >= 35% usable ground (real-world floor; sections 9 & 15); P=1 fallback in `PointEdgeSelector` guards <= 25% |
| C6 | Reconciliation no worse | **MET** - confirmed at coarse settings; zero allocation overhead in selection path (section 17) |
| C7 | Selection no worse | **MET** - faster at every setting measured |
| C8 | Point edge derived from range, not pinned | **MET** - derived point-by-point over powers of two with hard expand guard, regret <= 3.0% against brute-force oracle (section 15); shipped in `PointEdgeSelector`, dynamically derived in `SquareOptimizedDualLayer` and `CircleOptimizedDualLayer` (sections 15 & 23b) |
| C9 | Crash-safe persistence across a curve change | **MET** - Version 5 header, lossless upward ratchet on multiple P, int keyWidth saving 32% disk footprint, 1.000 offered-mark retention (section 17) |

## Measurements

Tiled real save (bad density 0.751), `:rtp-core:simulationBenchmark --tests '*SpiralHilbertBenchmarkTest*'`, report `build/reports/rtp-simulation/spiral-hilbert.md`. All rows at one-chunk precision. Accuracy is the corrected metric: share of usable chunks the rebuilt table refuses.

### 8a. Side by side at identical settings, r = 256 chunks, P = 32 chunks

Radius, mark set and coalescing gap held fixed; only the curve changes. These rows were taken before the dynamic gap reached shipped coalescing; every row at a gap above 1 has since moved, and section 23e carries the re-measurement.

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

Every image carries its own title, an explanation of the colouring rule, the measured figures behind it, the domain size and pixel scale, an axis note and a colour legend, so it can be read without this document. An unlabelled raster was the first version's defect: correct pixels, no way to tell which curve was drawn or what a colour meant.

- `keyorder-spiral` / `keyorder-hilbert` - the key space cut into 64 equal bands, each drawn as one flat colour, so one patch of colour is one contiguous stretch of keys and therefore one possible run. 64 bands over the 256x256-chunk domain makes a band 1024 keys, which is exactly one 32-chunk point. Patches are thin concentric rings on the spiral and compact squares on the hybrid; that is the mechanism, visible directly. A flat fill replaced an earlier hue ramp that varied *within* a band, which drew the eye to the gradient rather than the band shape.
- `occupancy` - ground truth from the save. Indigo is a chunk **absent from the save**, i.e. never generated, which is what stands in for unsafe terrain here; those are the chunks both curves are told to exclude, and they are data rather than a gap in the harness. The usable share of the drawn domain is stated on the image - **0.932** at the drawn radius, so this window is far less adverse than the 0.751 figure the wider sweep runs at. Unsafe land was previously drawn in a near-black slate within a few units of the canvas colour, which read as a hole in the picture; the rasters are opaque RGB with no alpha, but "drawn in the background colour" and "not drawn" are the same thing to a reader, so background-matching colour is now reserved for cells the curve genuinely does not address.
- `discarded-<curve>-res<N>` - usable chunks each table refuses after coalescing at `spatialResolution = N`, swept over 1 / 16 / 64 / 256 / 1024 key units so the same knob is drawn on both curves at every setting. Measured share of usable ground discarded, r = 128 chunks, `P` = 32 chunks:

| gap | spiral | hybrid |
|---|---|---|
| 1 | 0.000 | 0.000 |
| 16 | **0.002** | 0.015 |
| 64 | **0.029** | 0.033 |
| 256 | 0.235 | **0.064** |
| 1 024 | 0.955 | **0.180** |

There is a crossover, and it is stated rather than cropped: at fine gaps the spiral discards slightly *less*, because a short 1D gap on either curve bridges few cells and the hybrid pays its point seams for nothing. The hybrid's advantage arrives at gaps wide enough to be worth setting, and then it is large - at `gap = 1 024` the spiral has refused 95.5% of usable ground while the hybrid still admits 82%.

What the images show at those settings: the spiral's discarded ground follows **long thin arcs along its rings**, while the hybrid's losses **hug the unsafe terrain** as local blobs, because a bridged gap there joins cells that are spatially adjacent. Same knob, same domain, same mark set.

The arc length is arithmetic and is **not** a full revolution in general. A ring at chunk-radius `k` holds about `8k` cells, so a gap of `g` key units bridges `g / 8k` of a revolution: at `g = 256` that is a whole ring only inside `k = 32`, and about a quarter of one at the `k = 128` rim. The mechanism is therefore that a bridged spiral gap spans an angular arc whose far end is unrelated to the terrain that caused the merge, and that the arc covers a larger share of the ring the closer to the centre it sits - degenerating to a full revolution only near the origin, where the rings are short. An earlier draft of this section stated the full-revolution case as the general one; that was wrong, and if the shipped spiral did bridge whole revolutions at operator-scale radii it would be a defect rather than a curve property.

One defect was found by drawing the sweep, and is fixed: the rasters were rendered from a shape that the timing harness had continued to mark after the reported figures were taken, so every exclusion image showed substantially more loss than its own caption - at `gap = 1 024` the picture was nearly all red against a reported 0.180. Images are now drawn from a mark-and-flush-only shape with runs and loss read from that same instance, so a picture and its numbers cannot diverge.

The images exist because two published errors in this line of work were geometric claims a picture would have refused. They are the shareable artifact of the comparison.

### 9. Border scale, at 45% usable terrain

`:rtp-core:simulationBenchmark --tests '*BorderScaleFootprintBenchmarkTest*'`, report `build/reports/rtp-simulation/border-scale-footprint.md`. Two things change from section 8 and both were named there as the reasons its result might not hold.

**Density is now an input.** Section 8 ran on the tiled save at 0.249 usable, which section 8c identified as the adverse case for locality - the usable ground is the sparse phase, so there is little for a locality-preserving curve to gather. `DensityTargetedOccupancyMask` raises the usable share to a target by promoting clustered 8-chunk blocks of never-generated ground, and the realised share is counted over the addressed domain rather than assumed: **0.477 / 0.443 / 0.456** at the three radii below. Promotion is by block, not by chunk, because sprinkling isolated usable cells would manufacture short runs and flatter whichever curve handles long ones worse.

**Scale is now the border.** A 100 km border is a 6250-chunk radius and about 156 million addressed chunks, of which 84.7 million are unusable. That mark set does not fit through `addBadLocation` in a test JVM, so the table is *counted* rather than built: one ascending pass over the key space histograms the gap between each pair of consecutive bad keys together with the usable chunks inside it, which reconstructs the coalesced table at every setting at once in constant memory. The shipped merge test is `nextKey <= start + length + resolution` and a run's `start + length` is one past its last bad key, so two bad keys merge exactly when their gap is at most `resolution + 1` - which is also the reason neither curve is lossless at `gap = 1`.

That encoder is arithmetic, not shipped code, so it is pinned to a real `flushAndRebuild` at radii 128 and 256 for both curves at gaps 1 / 64 / 256: **run counts identical in all twelve cases, loss identical to 1e-6**. Every row below rests on that check.

Both curves are measured on the same square window, one point inside the shape radius. The hybrid's coarse grid covers whole points only, so its addressed band is shifted rather than symmetric; the window is the largest square both cover, and the harness asserts the two saw identical chunk counts and identical mark counts before reporting anything.

#### 9a. Full precision - C5 inverts with density

| radius | addressed chunks | usable share | spiral runs | hybrid runs | hybrid / spiral |
|---|---|---|---|---|---|
| 480 ch (7.7 km) | 921 600 | 0.477 | 17 990 | 3 806 | **0.212** |
| 2 016 ch (32 km) | 16 257 024 | 0.443 | 328 844 | 65 132 | **0.198** |
| 6 240 ch (99.8 km) | 155 750 400 | 0.456 | 3 103 166 | 624 344 | **0.201** |

At `gap = 1`, zero loss on both sides, the hybrid holds **about a fifth of the runs at every scale** - 47.4 MB against 9.5 MB at the border, at the shipped 16-byte entry. Section 8c's unfavourable half was therefore a property of the source save's density, not of the curve, and it was correctly flagged there as the case that could change the sign. The ratio is flat in radius across a 13x range, which is what makes it a scaling property rather than a point.

#### 9b. Iso-accuracy - the spiral has no usable knob at this density

| radius | cap | spiral bytes | hybrid bytes | ratio |
|---|---|---|---|---|
| 480 ch | 0.05 / 0.10 / 0.20 | 287 072 | 40 464 | **0.141** |
| 2 016 ch | 0.05 / 0.10 / 0.20 | 5 257 712 | 721 136 | **0.137** |
| 6 240 ch | 0.05 / 0.10 / 0.20 | 49 625 104 | 6 815 984 | **0.137** |

The cap makes no difference because the spiral has no admissible setting that buys anything: every gap it can afford leaves its table within 0.05% of the `gap = 1` figure, and the first setting that removes runs discards **0.227-0.238** of usable ground. So at 55% unusable terrain the shipped knob is not a memory lever, while on the hybrid it is worth 1.3x-1.5x of table for 1.5% of ground. Section 9c states this per setting.

#### 9c. The admissible floor is `spatialResolution = 2`

`gap = 1` is reported but is not an operating point. A run merges at `resolution + 1`, so `gap = 1` already bridges a single usable chunk isolated between two unusable ones - and such a chunk is not a viable destination in the first place. A one-chunk island or a nether pocket is not somewhere a player should land, so retaining it is a byte cost with no capability behind it. The floor for any comparison is therefore **2**, and the cap comparison in 9b now admits settings of 2 and above only.

The settings 2, 4 and 8 are swept so the cost of that floor is a measured row rather than an interpolation between 1 and 16. Usable ground discarded, and runs as a share of the curve's own `gap = 1` table:

| gap | spiral loss | spiral runs vs its own gap 1 | hybrid loss | hybrid runs vs its own gap 1 |
|---|---|---|---|---|
| 1 | 0.000 | 1.000 | 0.000 | 1.000 |
| **2** | 0.000 | 1.000 | 0.003 | **0.861** |
| 4 | 0.000 | 0.999 | 0.005 | **0.793** |
| 8 | 0.227 | 0.350 | 0.007 | **0.752** |
| 16 | 0.346 | 0.179 | 0.015 | 0.682 |

(r = 6 240 chunks; the two smaller radii agree to within a point.)

The two curves respond to the floor in opposite ways, and this is the sharper form of 9b's point:

- **On the spiral the floor is worthless.** Gaps 2 and 4 remove 0.05% of its entries, because two bad keys two apart on the spiral are almost never two apart in 1D - the curve separates them by an arc. The knob does nothing until it does too much: the first setting that removes entries costs 22.7% of the world.
- **On the hybrid the floor is free capability.** Gap 2 removes **13.9%** of the table for **0.3%** of usable ground, and gap 8 removes 24.8% for 0.7%. A bridged gap on this curve joins spatially adjacent cells, so the ground it refuses is exactly the isolated single chunks the floor says are not viable anyway.

So adopting the floor is not a concession by either side. It withdraws nothing from section 9a - the hybrid still holds about a fifth of the runs at `gap = 1` - and it *widens* the gap at the setting actually run: at `gap = 2` the border tables are **49.6 MB against 8.6 MB**, a ratio of 0.173, and the hybrid's 0.003 loss is confined to ground the floor exists to discard.

#### 9d. What the density change does *not* rescue

Coalescing is far more destructive at 55% unusable than at 75%, on both curves, because the gaps between bad keys are short and almost everything merges. At the border the spiral is down to 27 runs and 0.999 of usable ground refused by `gap = 1 024`, and 1 run by `gap = 65 536`; the hybrid reaches 0.604 loss at `gap = 1 024` and 0.998 by 16 384. The wide-gap rows in section 8a are therefore specific to a sparse world, and no setting above `gap = 64` is admissible here on either curve. The lever that works at this density is the curve, not the knob.

**Caveats.** Promoted ground is synthetic and square-edged, so the added boundary is blockier than real coastline; it is added on identical coordinates for both curves, so the comparison between them is unaffected, but an absolute run count on this mask is not a claim about a real world. Occupancy is still periodic with the source save's bounding box. And the border rows are counted by the equivalence-checked encoder rather than by a built table, which is a measurement of the coalescer's arithmetic on the real curves - not of the shipped object's heap layout.

### 10. A generated world, and real pass/fail to check it against

`:rtp-core:simulationBenchmark --tests '*MockWorldFidelityBenchmarkTest*'`, report `build/reports/rtp-simulation/mock-world-fidelity.md`, images `img/mock-world-mock-terrain.png` and `img/mock-world-real-terrain.png`. This section addresses the caveat every earlier section carries: one hand-copied save, tiled outward, with occupancy standing in for safety.

#### 10a. The real reference is now safety, not occupancy

Every density figure in sections 8 and 9 came from `WorldOccupancyMask`, which answers "does this chunk exist on disk". That is the GENSCAN fast path, and it is not a safety signal - a fully generated ocean is occupied. So the unusable set those sections modelled was **ungenerated ground**, and the request that started this line of work was about oceans, rivers and pools.

`RealWorldVerdictMask` answers the question the plugin asks, using shipped code rather than a reimplementation: `AnvilReader.readColumnProbe` decodes the chunk's centre column and the `MOTION_BLOCKING_NO_LEAVES` heightmap, `AnvilPrefilter.DEFAULT_RECONCILER` normalises palette identifiers so a name read from disk is compared in the same canonical form as a name read from config, and the surface plus the two blocks above it are tested against the shipped `unsafeBlocks` list. Nothing is written and no chunk is generated.

Measured on `RTP-Spigot/1.21.11/world/region`, 256 region files, 262 144 chunks classified in 14.3 s with **zero decode failures**:

| outcome | share of classified chunks |
|---|---|
| usable | 0.510 |
| water at the surface | 0.397 |
| lava or magma | 0.000 |
| other configured unsafe block | 0.002 |
| not generated / not decodable | 0.090 |

Usable share **of generated ground is 0.561**. Two things follow. First, a real save's pass/fail density sits inside the 35-65% band this section targets, so that band is not an arbitrary choice. Second, the unusable set is dominated by water, which is exactly the feature occupancy cannot see - so the earlier sections' density figures describe a different world from the one an operator has.

The rule is the chunk **centre column**, not all 256. The shipped prefilter rejects a chunk when any column is unsafe, which is right for an advisory filter and wrong for a per-chunk mask: at that rule almost every generated chunk carries one unsafe block somewhere and the usable share collapses towards zero. The chunk-granular unit these measurements address is the chunk centre, so the centre column decides the cell. Tag entries and the waterlogged state predicate are omitted because expanding them needs a platform registry, which can only make the mask more permissive - so 0.561 is an upper bound.

#### 10b. The generated world, and why it had to be tuned against statistics

`NoiseWorldMask` is unbounded seeded terrain: an elevation field below a sea level gives ocean, a narrow band on one contour of a second field gives rivers, a short-wavelength field gives ponds and pools, a threshold on the raw lattice gives one-chunk speckle, and a per-chunk hash gives rare single-chunk lava pools. Sea level is **bisected**, not configured, so usable share is an input: realised **0.352 / 0.451 / 0.651** against targets 0.35 / 0.45 / 0.65, in 8-9 bisection steps.

Hitting the density was the easy half and would have been misleading on its own. Density-matched against the real save on the same window:

| statistic | real save | first attempt | second attempt | with speckle | final / real |
|---|---|---|---|---|---|
| usable share | 0.513 | 0.514 | 0.512 | 0.513 | 1.00 |
| boundary fraction | 0.228 | 0.032 | 0.243 | 0.306 | **1.35** |
| mixed 32-chunk cell fraction | 0.986 | 0.486 | 0.958 | 0.958 | **0.97** |
| mean unusable patch (chunks) | 59.9 | 5 121 | 115.4 | 46.3 | **0.77** |
| unusable patches | 1 199 | 14 | 624 | 1 550 | 1.29 |

The first attempt had the right density and the wrong world: a coastline seven times too smooth and 14 unusable patches where the save has 1 199. Both errors run in the direction that flatters a run-length table, so a mask calibrated on density alone would have improved every figure in sections 8 and 9 for free. Deepening the elevation octaves and shortening the pond wavelength closed the boundary and mixed-cell gaps, but left patch size at 1.93x - still too smooth.

**Why an interpolated field cannot fix that.** Value noise is continuous, so its smallest feature is a few lattice cells across; no wavelength produces a one-chunk water body, and the save's unusable set is dominated *in count* by exactly those. A threshold on the raw lattice - one independent draw per chunk - is the only term that can, and it is what closed the axis: patch size 1.93x to **0.77x**. The rate was swept rather than guessed (0.045 over-dotted at 0.32x, 0.015 read 0.62x, 0.008 reads 0.77x), because over-dotting is the opposite error and equally misleading - it manufactures short runs. Boundary fraction runs 1.35x as a consequence, so the mock is now slightly rough where it was previously slightly smooth.

**Lava is bounded to one chunk and made rare.** It is hashed per chunk rather than drawn from a field, so a pool cannot grow, at a rate of 0.0015 of inland chunks. The real save measured a lava share of 0.000 at chunk-centre granularity, so lava is present for shape, not for area; the suite asserts the share stays below 0.005 rather than reporting it.

#### 10c. A lone island in open water is not a usable destination

A chunk that passes the block rule but has no passing chunk in its eight-neighbourhood is reported unusable, on **both** masks - `Terrain.ISOLATED` and `Cell.ISOLATED` respectively, so it is visible in the drawn output rather than silently folded into water. A player dropped on a one-chunk island is stranded, and section 9c already relies on that judgement: the hybrid's free `gap = 2` saving is bought by refusing exactly this ground, so counting it as usable would price that saving against ground nobody wants.

The filter is one pass over base classifications and never cascades - removing an island does not strand its neighbour. Measured share is 0.000 on both worlds at these densities, i.e. it changes no figure here; it is in place so that no future figure is inflated by ground that is unusable in practice, and ungenerated ground counts as unusable for the neighbourhood test, which makes the filter conservative at the edge of the swept region files.

**A stride fault found on the way, and worth keeping.** With pond wavelength 2.5 the calibration's strided sample used stride 5 - exactly in phase with the field - and read 0.649 where the exhaustive sweep read 0.693. A four-point calibration error caused entirely by the sampling stride. The stride is now the smallest prime at or above the requested value, and the wavelength is no longer a divisor of it.

#### 10d. What this does and does not license

- It **does** replace the two structural limits of the tiled mask: the mock is unbounded, so border scale needs no repetition and there are no tile seams to be mistaken for a model property, and its density is an input rather than an inherited accident.
- It **does not** make the mock a substitute for measurement on real ground. The residual 1.35x on boundary fraction and 0.77x on patch size are real differences, and the mock is not Minecraft's generator - its constants reproduce three statistics, they are not derived from world generation.
- No section 8 or 9 row is withdrawn. They stand as measured, on the terrain they were measured on, which is now stated precisely: ungenerated ground on a tiled save, not water on a real one.
- The next step named here - re-running the curve comparison on this mask at 35-45% usable, against real pass/fail - is section 11.

### 11. Both curves, both worlds, one measurement path

`:rtp-core:simulationBenchmark --tests '*CurveWorldComparisonBenchmarkTest*'`, report `build/reports/rtp-simulation/curve-world-comparison.md`, 30 images under `img/curve-world-*`. Radius 128 chunks, point edge 32 chunks (one region file), seam-matched orientation, one chunk per key on both curves, identical mark set per world.

This is the first section in which the curves are measured on **real safety verdicts** rather than on occupancy, and on two worlds rather than one. Raw usable terrain share: **0.406** on the real save's window, **0.449** on the mock.

#### 11a. At the shipped default, the knob is not free on either curve

`spatialResolution: 3` is the shipped default in `config.yml`, so it is the setting an operator is measuring when they report a usable share.

| world | raw terrain | offered after spiral coalescing | offered after hybrid coalescing |
|---|---|---|---|
| real save | 0.406 | 0.373 | 0.361 |
| mock 45% | 0.449 | 0.427 | 0.412 |

So a field observation of "about 45% usable during regeneration" is **terrain minus coalescing loss**, not terrain: the default discards 8.2% of usable ground on the real save under the spiral and 11.1% under the hybrid. Any density figure taken from a running server is a lower bound on the terrain, and the two must not be conflated.

#### 11b. A fixed knob is not a fair comparison, and the rows show why

At an identical gap the two curves buy different amounts of accuracy, because the gap is in 1D key units and the curves place different cells at that distance. At `gap = 3` the hybrid holds fewer runs *and* loses more ground than the spiral (1 241 runs at 0.111 loss against 1 817 at 0.082 on the real save); at `gap = 16` it holds fewer runs *and* loses less (384 at 0.354 against 489 at 0.514). Neither row on its own is a byte saving.

Holding accuracy fixed and asking which table is smaller is the operator's question:

| loss cap | real save: spiral / hybrid runs | hybrid smaller by | mock: spiral / hybrid runs | hybrid smaller by |
|---|---|---|---|---|
| 0.05 | 2 465 / 2 070 | **1.19x** | 2 432 / 2 490 | 0.98x |
| 0.10 | 1 817 / 1 518 | **1.20x** | 2 432 / 1 722 | **1.41x** |
| 0.20 | 1 817 / 1 241 | **1.46x** | 2 432 / 1 722 | **1.41x** |
| 0.40 | 1 817 / 384 | **4.73x** | 2 432 / 1 722 | **1.41x** |

The direction is consistent on both worlds and at every cap but one - the mock at a 5% budget, where the spiral's `gap = 3` row happens to land just inside the cap and the hybrid's finest available setting does not. That is a sparse sweep grid, not a reversal, and it is reported rather than smoothed.

Selection is faster on the hybrid at every admissible setting on both worlds: 519 against 849 ns at `gap = 1`, 558 against 793 at the shipped default, 316 against 788 at `gap = 16`, all on the real save.

#### 11c. The mock is the pessimistic bound, and the images say why

The two worlds differ exactly where it matters for coalescing. The real save's inland ground is contiguous and its unusable set is one large water body; the mock's interior is dotted with one-chunk pools and its oceans are smaller. More boundary per unit of unusable ground means shorter gaps between bad keys, so there is less long unusable ground to merge into and every merge is more likely to swallow usable ground.

The measured consequence is that the mock is harsher on **both** curves - the spiral cannot reach a table below 2 432 runs at any cap up to 0.40 there, where on the real save it reaches 1 817 - and it is harsher on the hybrid's advantage at the tightest budget. Read the real-save rows as the favourable end of the range and the mock rows as the adverse end; the gap between them is the honest error bar on section 9.

**What the pictures show, at `gap = 16` on the real save.** The hybrid's discarded ground is a band hugging the coastline - the merge reaches from unusable water into the nearest usable chunks and stops. The spiral's is banded stripes cutting straight across inland ground far from any water, which is the arc geometry of section 8e: the merge follows key order, and on a global spiral key order runs around a ring rather than into the neighbouring terrain. Same knob, same terrain, same loss mechanism; the difference is entirely which cells the 1D gap reaches, which is the claim the design rests on.

#### 11d. Settings above 64 key units are inadmissible at this radius

On both curves and both worlds, `gap = 64` and above discards over 90% of usable ground (spiral 0.991, hybrid 0.735 at `gap = 64` on the real save; 1.000 and 0.929 on the mock). Granularity is a ratio to the range and radius 128 chunks is the small end, so these rows bound the sweep rather than offering operating points. It is the same finding as section 9d, now on real verdicts.

### 12. Loss priced by proximity, and a gap driven by adjacent run lengths

`:rtp-core:simulationBenchmark --tests '*ProximityWeightedLossBenchmarkTest*'`, report `build/reports/rtp-simulation/proximity-weighted-loss.md`. Window is the largest origin-centred square **both** curves address in full - probed, not assumed, and 96 chunks at radius 128 with 32-chunk points, because the hybrid's coarse grid rounds up and its outer point ring falls outside the coarse spiral's domain. Both curves get the identical mark set and the equal-domain property is asserted.

The test-scope coalescer is a transcription of `MemoryShape#coalesceRuns` and is asserted **equal run-for-run to a real `flushAndRebuild`** at every swept gap before anything is compared against it (1 523 / 1 335 / 721 / 267 / 2 / 2 runs at gaps 2 / 3 / 8 / 16 / 64 / 256). Widths are not exposed by shipped API and the dynamic rule needs them on both sides of every gap, which is why the table is rebuilt in test scope rather than read out.

#### 12a. Why a flat loss share is the wrong denominator

Every accuracy figure in sections 8-11 counted discarded usable chunks and divided, which prices all usable ground alike. It is not alike. Traffic lights: ocean is red, land is green, and a beach is yellow - but only when the water beside it is a **large body**. Land beside a one-chunk pool is not marginal at all; the pool is an obstacle, not a frontier.

So each usable chunk is priced by two measured quantities: taxicab distance to the nearest unusable chunk, and the size of the connected four-neighbour body that distance reaches. Only ground both close to and beside a large body is discounted. The discount and both thresholds are policy, so all three are swept, and the **shore-versus-interior composition of each curve's loss** is reported alongside because that part holds whatever discount is chosen.

Measured shore share of usable ground: **0.191 / 0.304 / 0.368** on the real save at the three weightings, **0.101 / 0.174 / 0.211** on the mock - the real save has proportionally more shore because its unusable set is one 19 236-chunk water body rather than many small ones.

#### 12b. Weighting amplifies the hybrid's advantage where it exists, and does not create one where it does not

Real save, hybrid advantage as a ratio of spiral cost to hybrid cost (above 1.0 favours the hybrid):

| setting | runs | flat loss | **weighted loss** |
|---|---|---|---|
| fixed gap 2 | 1.35x | 0.68x | 0.69x |
| fixed gap 3 (shipped default) | 1.47x | 0.75x | 0.75x |
| fixed gap 8 | 1.57x | 1.11x | **1.19x** |
| fixed gap 16 | 1.12x | 1.47x | **1.69x** |
| dynamic alpha 1.0 | 1.21x | 1.18x | **1.32x** |
| dynamic alpha 2.0 | 1.22x | 1.45x | **1.71x** |

The correction is real but bounded: where the hybrid already wins on flat loss, weighting widens the gap by 7-16 percentage points of ratio, because a larger share of the hybrid's loss is shore (0.546 against the spiral's 0.426 at gap 16). Where the hybrid loses - the two finest fixed gaps - weighting does **not** rescue it, and the ratio stays at 0.69-0.75. So proximity weighting is not the missing argument for the curve; it is a better yardstick that happens to favour it at admissible settings.

#### 12c. The dynamic gap is the larger result

`admissibleGap = min(maxGap, alpha * min(leftRunLength, rightRunLength))`. `min` rather than `max` on purpose: a gap is bridged only when both sides are substantial, so an ocean's fragments consolidate while a pool cannot license absorbing the ground around it. `maxGap` is the ocean-width ceiling, 1 024 keys here.

The finest fixed gap the shipped coalescer admits already discards 0.064 of usable ground on the real save. The dynamic rule reaches an accuracy regime **no fixed setting can**:

| rule | runs | flat loss | weighted loss | share of loss that is shore |
|---|---|---|---|---|
| spiral fixed gap 2 (finest available) | 1 306 | 0.064 | 0.041 | 0.683 |
| spiral dynamic alpha 0.125 | 1 948 | **0.008** | **0.003** | **0.927** |
| spiral dynamic alpha 0.5 | 1 720 | 0.039 | 0.020 | 0.812 |
| hybrid dynamic alpha 0.125 | 1 953 | 0.005 | 0.002 | 0.924 |

8x less usable ground discarded and 13x less weighted loss for 1.5x the runs, and what it does discard is **93% shore** rather than 68%. That is the mechanism working as intended: the merge follows the evidence, so it eats coastline next to oceans and leaves the ground around ponds alone. It is also the first result in this line of work that improves the shipped spiral on its own terms without changing the curve at all.

At looser settings the two rules converge on cost - fixed gap 3 gives 1 133 runs at 0.099 flat, dynamic alpha 1.0 gives 1 254 at 0.120 - so the dynamic rule buys the low-loss end of the frontier, not the whole frontier.

#### 12d. Limits, stated

- The rule is **one greedy left-to-right pass** whose accumulator length drives the next gap, so it is order-dependent and not idempotent; a second pass merges further and is a different rule. Only one pass is measured.
- The beach discount is a **stated preference**, not a measured fact. No measurement can make "a beach is worth a quarter of an interior chunk" true; what is measured is the composition, and both curves are priced by the same function on the same terrain.
- Distance is taxicab and bodies are four-connected, both chosen so the propagated body size is the one actually nearest rather than a chamfer approximation. Ties go to the larger body, which prices ground squeezed between an ocean and a pool as shore.
- Radius 96 chunks, one window per world. This is a direction result, not a scaling result.

### 13. The generator fitted per real save, so the evidence outlives the data

Same suite as section 10, new test. Every terrain constant in `NoiseWorldMask` was hand-tuned against the first save examined, which means "the mock resembles a real world" has so far meant "the mock resembles *that* world". The shape knobs are now inputs (`NoiseWorldMask.Params`), fitted per save by coordinate descent - two rounds over five knobs - scored by the summed absolute log ratio of boundary fraction, mean unusable patch size and region-aligned mixed-cell fraction. Sea level is deliberately not a fitted knob: it is calibrated from the requested usable share, so exposing it would let the search trade density for shape.

Two genuinely different real worlds, both decoded by the shipped column probe over a 96-chunk window:

| save | usable share | boundary | mean patch | fidelity error, hand-tuned | **fitted** | fitted parameters |
|---|---|---|---|---|---|---|
| Spigot 1.21.11 | 0.404 | 0.330 | 62.1 | 0.332 | **0.151** | octaves 10, persistence 0.70, river 0.0120, pond 0.460, speckle 0.0040 |
| Folia 1.21.11 | 0.572 | 0.253 | 45.6 | 0.435 | **0.172** | octaves 8, persistence 0.60, river 0.0120, pond 0.460, speckle 0.0040 |

Fitted ratios against real: boundary 1.044 and 0.985, mean patch 0.951 and 0.880, mixed-cell fraction 0.944 and 1.029. So the generator can be matched to whichever world is in front of it to within roughly 5-12% on all three shape statistics, and the two saves want **different** parameters - which is the point, and also the evidence that the hand-tuned defaults were fitted to one world.

Practical consequence: the parameter set plus the seed is enough to reconstruct a statistically comparable world, so the section 11 and 12 comparisons can be re-run after the source saves are wiped. The numbers travel; the gigabytes do not.

Limits: coordinate descent is not a global search and is not claimed to be - it starts from the default, so it can only improve, and that is all the assertion checks. Two saves is two saves, both from the same machine and both 1.21.11. The three statistics are the ones known to drive run-length cost, not a complete description of terrain.

### 14. Biome constraints scored on accessibility rather than membership

`:rtp-core:simulationBenchmark --tests '*BiomeAccessibilityBenchmarkTest*'`, report `build/reports/rtp-simulation/biome-accessibility.md`. Real save only - the mock has no biome fields yet - over the same probed 96-chunk window, with the biome read from the same `ColumnProbe` the safety verdict already decodes, so the channel costs no additional I/O.

#### 14a. The predicate, and why its radius is not arbitrary

Every biome figure before this scored a destination on **membership**: is this chunk in biome B. That is not what is being asked. A player who wants a jungle wants a jungle they can reach, so the predicate is **accessibility** - biome B within `d` walkable chunk steps - and `d` is grounded in vanilla behaviour rather than chosen:

- the hard-loaded / ticking area around a player is 5x5 chunks, so `d = 2`;
- the minimum permitted view distance is 4, so `d = 4`;
- 5 is a generous upper bound.

This is a different class of input from the beach discount of section 12, which is a stated preference and is labelled as one. Distance is **geodesic** - the spread crosses only usable ground - because a biome across a channel is not reachable. A Euclidean spread is computed alongside so that refinement is evidenced rather than asserted: it admits 0 / 24 / 86 / 118 extra chunks at `d = 0 / 2 / 4 / 5`, i.e. **1.2% of admitted ground at `d = 4`**. Real, and small at this window.

Measured reach, target class LUSH (6 077 of 14 892 usable chunks):

| predicate | usable ground admitted |
|---|---|
| membership (`d = 0`) | 0.408 |
| `d = 2` | 0.472 |
| `d = 4` | **0.502** |
| `d = 5` | 0.513 |

So changing the predicate widens the correct-answer set by roughly a quarter. Every earlier "admissible resolution" figure was scored against the stricter definition and is conservative by about that much.

#### 14b. Premise falsified: dilating the desired set does *not* buy runs

The expectation stated when this was proposed was that the accessible set, being a morphological dilation, would have lower perimeter and fewer holes and therefore cost fewer runs - making biome state coarsenable with a bounded, justified error. **It does not.** Run count rises monotonically with `d` on both curves:

| `d` | spiral runs | hybrid runs |
|---|---|---|
| 0 | 791 | 752 |
| 2 | 808 | 795 |
| 4 | 858 | 842 |
| 5 | 870 | 848 |

The mechanism is composition. At 0.408 of usable ground the target class is not a sparse set with holes to close; it is one of two interleaved phases. Dilating it grows its boundary into the interleaved non-target ground faster than it fills its own interior, so perimeter increases. Dilation would pay on a *rare* biome and does not on an abundant one, and the abundant case is the one measured. The claim is withdrawn, not deferred.

#### 14c. The prediction that did hold: 1D coalescing agrees with the predicate only on the hybrid

Dilation fills gaps in 2D; `spatialResolution` fills them in 1D. On the hybrid a 1D gap is 2D-local by construction, so a merge lands on ground the accessibility predicate already forgives. On the shipped spiral a 1D gap of `g` at chunk-radius `k` is a one-chunk-wide arc spanning `g / 8k` of a revolution, reaching ground nowhere near the biome.

| gap | spiral runs | hybrid runs | spiral FP `d = 0` | hybrid FP `d = 0` | spiral FP `d = 4` | hybrid FP `d = 4` |
|---|---|---|---|---|---|---|
| 2 | 508 | 378 | 0.013 | 0.024 | 0.000 | 0.000 |
| 3 | 421 | 297 | 0.022 | 0.033 | 0.000 | 0.000 |
| 8 | 270 | 166 | 0.037 | 0.051 | 0.000 | 0.000 |
| **16** | 188 | **94** | 0.071 | 0.082 | 0.004 | **0.001** |
| 64 | 90 | 41 | 0.152 | 0.156 | 0.035 | 0.027 |
| 256 | 47 | 12 | 0.343 | **0.248** | 0.230 | **0.090** |

Two things to read off this, and they matter more than the ratios.

**The predicate flips the ranking.** At `gap = 16` the hybrid is *worse* on membership (0.082 against 0.071) and **four times better** on accessibility at `d = 4` (0.001 against 0.004) - while holding **half the runs** (94 against 188). Same tables, same terrain; only the definition of a correct answer changed. That is the sharpest available evidence that the two curves lose ground in different *places* rather than in different amounts, and it is the section 11c picture expressed as a number.

**The advantage widens with `d`, at every gap.** Spiral-to-hybrid false-positive ratio: 0.865 / 1.813 / 5.465 / 5.060 at `gap = 16` and 1.384 / 1.907 / 2.545 / 2.957 at `gap = 256`, across `d = 0 / 2 / 4 / 5`. Monotone in `d` in both cases, which is the signature the locality argument predicts and would not survive if the merges were landing at random.

#### 14d. Limits, stated

- **One save, one window, one target class.** The target is chosen as the most abundant non-aquatic class, so the absolute rates describe that save's composition. Only the spiral-versus-hybrid comparison is invariant to the choice, because both curves are scored against the same target on the same ground.
- **The `BiomeClass` grouping is a stated preference.** Lush/barren/cold/aquatic reflects the complaints operators report, not a derived taxonomy; the identifier-to-class mapping is mechanical and stated, the grouping is not.
- **Only usable chunks are scored.** A biome table is orthogonal to the safety table and both constraints apply at selection, so charging the biome table for offering an ocean chunk would measure the safety table.
- **No time or memory figure for a shipped biome structure.** Runs are counted on a test-scope table; nothing here says what a biome channel costs in the `.bin` format or at rebuild.
- **The mock cannot reproduce any of this** until biome fields are fitted into `NoiseWorldMask`, so unlike sections 11-13 these rows do not survive the source save being wiped.

## Consequences

### Favourable

- The first memory lever measured here that does not spend precision to buy bytes: 3.3x-5.8x smaller table at a stated accuracy cap, and at an identical knob both fewer runs and less loss.
- **At 45% usable terrain the win needs no accuracy budget at all**: 4.7x-5.0x fewer runs at full precision, flat in radius from 7.7 km to a 100 km border - 47.4 MB against 9.5 MB of learned state at the border, halving again to 4.7 MB with the `int` key width the same range permits.
- Selection is faster at every setting measured, from the same cache mechanism coarsening exploited, without coarsening's loss.
- Landing cost is small: one bijection pair, one `.bin` header extension, no new structure, no new uniformity argument, no second table to keep consistent.
- `spatialResolution` becomes geometrically meaningful, so an ocean-width floor is enforceable rather than aspirational.
- **A real save's own pass/fail density is 0.561 usable** (section 10a), measured by the shipped column probe rather than by an occupancy proxy - so the favourable density regime in section 9a is the regime an operator is actually in, not a chosen one.
- **The terrain generator is validated against real geometry, not just real density** (section 10b): boundary fraction 1.35x, mean unusable patch size 0.77x and region-aligned mixed-cell fraction 0.97x of the real save's at matched density, so border-scale measurement no longer needs a tiled save with repeating seams.
- **"Usable" now means the same thing on both worlds** (section 10c): a passing chunk with no passing neighbour is reported unusable, which is the judgement section 9c's free `gap = 2` saving already depends on.
- **The admissible floor of `gap = 2` is free on the hybrid and inert on the spiral** (section 9c): 13.9% of the table removed for 0.3% of usable ground, against 0.05% of the table for nothing. Since a lone usable chunk between unusable ones is not a viable destination, that 0.3% is ground the floor exists to discard - so at the setting actually run the border ratio is 0.173 rather than 0.201.
- **The direction survives real safety verdicts and holds on two worlds** (section 11b): at a stated accuracy cap the hybrid's table is 1.19x / 1.20x / 1.46x / 4.73x smaller on the real save at caps 0.05 / 0.10 / 0.20 / 0.40, and 1.41x on the deliberately adverse mock at caps 0.10 and above. Selection is faster at every admissible setting on both worlds.
- **Pricing loss by proximity to a large water body widens the advantage where it exists** (section 12b): 1.47x flat becomes 1.69x weighted at gap 16, 1.45x becomes 1.71x at dynamic alpha 2.0, because more of the hybrid's loss is shore (0.546 against 0.426).
- **A gap driven by adjacent run lengths reaches an accuracy regime no fixed setting can** (section 12c), and it does so on the shipped spiral without changing the curve: 0.008 flat and 0.003 weighted loss against the finest admissible fixed setting's 0.064 and 0.041, for 1.5x the runs, with 93% of what it discards being shore rather than 68%.
- **The terrain generator can be matched to whichever real world is in front of it** (section 13): fidelity error 0.332 to 0.151 and 0.435 to 0.172 on two different saves, all three shape ratios within 5-12%. Seed plus fitted parameters reproduces a statistically comparable world, so these comparisons survive the source data being wiped.
- **Under a biome predicate that asks what players actually ask - reachability, not membership - the hybrid wins on both axes at once** (section 14c): at `gap = 16` it holds half the runs (94 against 188) and makes a quarter of the errors at `d = 4` (0.001 against 0.004), and the advantage is monotone in `d` at every gap swept. This is the locality claim measured directly rather than inferred from a picture.
- **The accessibility radius is citable rather than chosen** (section 14a): `d = 2` is the 5x5 hard-loaded area and `d = 4` the minimum view distance, so unlike the beach discount it is grounded in vanilla behaviour. Adopting it widens the correct-answer set from 0.408 to 0.502 of usable ground, which means every earlier admissible-resolution figure is conservative by about a quarter.
- **The biome channel is free to observe** (section 14): it is decoded from the same `ColumnProbe` the safety verdict already reads, so measuring it added no region reads, no main-thread exposure and no second sweep.
- **Granularity scaling derives P point-by-point** (section 15): relative regret <= 3.0% against brute-force argmin oracle at 25% and 45% usable densities while strictly enforcing the hard expand guard (>= 64 cells per edge), meeting Criterion C8.
- **The mock world reproduces biome fields with durable fidelity** (section 16): Whittaker classification over temperature and humidity fBm fields fits real save characteristics via coordinate descent, and all key Section 14 conclusions (predicate reach widening, dilation premise falsification, hybrid advantage widening with d, ranking flip at gap=16) survive on the mock.
- **Persistence across curve changes is crash-safe and lossless on multiples** (section 17): Version 5 header, upward fold ratchet, 1.000 offered-mark retention, and 32% disk footprint reduction by gating keyWidth to 32-bit int, meeting Criterion C9.


### Unfavourable

- **Full precision is worse on sparse-usable terrain**, by up to 16% more runs at 0.249 usable. The sign depends on density: it inverts to 5x *better* at 0.44-0.48 usable (section 9a), so the honest statement is that the curve wins where usable and unusable ground are comparably abundant and loses where usable ground is a thin phase.
- **Every section 8 and 9 density figure describes ungenerated ground, not water** (section 10a). The unusable set on a real save is 0.397 water against 0.090 ungenerated, so those rows are measured on a differently shaped world than the one they are read as describing; none is withdrawn, but none has been re-measured on real pass/fail either.
- **The generated world is 1.35x rougher than the real save on boundary fraction and 0.77x on mean unusable patch size**, and its constants reproduce three statistics rather than deriving from world generation, so it removes the tiling artefact without becoming a substitute for real ground. Closing the patch-size axis moved the error from smooth to rough rather than eliminating it, and the speckle rate that sets it is a swept constant, not a derived one.
- **The stranded-island filter changes nothing measured here** - its share is 0.000 on both worlds at these densities - so it is a correctness guard against a future inflated figure, not evidence of anything.
- Density is now measured at two points, not one, but both come from the same tiled save with synthetic clustered ground added. A genuinely different world - a generated 20 km save, or a sparser dimension - is still unmeasured.
- Border-scale rows are counted by a gap histogram rather than by building the table. The arithmetic is pinned to a real rebuild at small radius, but no shipped structure has actually held 624 344 runs in this work, so the heap figures are entry counts times a stated width.
- Expansion quantises to the point edge. The derivation rule for `P` is now built (C8, section 15), but small-radius behaviour under dynamic expansion in production remains to be benchmarked.
- Persisted state written under legacy format without curve/P headers is discarded and relearned (C9, section 17). Upward folding is only supported when the new P is a multiple of the stored P.
- **The dilation premise is falsified** (section 14b): the accessible set costs *more* runs, not fewer - 791 to 870 on the spiral and 752 to 848 on the hybrid as `d` goes 0 to 5 - because at 0.408 abundance the target biome is one of two interleaved phases rather than a sparse set with holes to close. Accessibility is therefore a better predicate, not a memory lever, and the claim that it makes biome state coarsenable with a bounded error is withdrawn.
- **On membership the hybrid is worse at every fine gap** (section 14c): 0.024 against 0.013 at `gap = 2`, 0.082 against 0.071 at `gap = 16`. The hybrid's case for biome tables rests entirely on the change of predicate, so a deployment that genuinely requires exact biome membership does not get this win.
- **The geodesic refinement is real but small** (section 14a): restricting the spread to walkable ground excludes only 1.2% of what a straight-line spread admits at `d = 4`. It is the correct definition and it is cheap, but it is not carrying the result.
- **Section 14 is one save, one window, one target class, and no mock equivalent exists.** Unlike sections 11-13 these rows cannot be reproduced after the source save is wiped, because `NoiseWorldMask` has no biome fields. Resolved in section 16: temperature and humidity fields now exist on the mock and fit real save geometry.

### 15. Granularity scaling mechanism (Criterion C8): deriving P from inputs

`:rtp-core:simulationBenchmark --tests '*PointEdgeScalingBenchmarkTest*'`, report `build/reports/rtp-simulation/point-edge-scaling.md`.

Coarsening is **not monotone in P**: a coarse grid is a different ordering of the plane, so `P` cannot be fitted through an exponent. `PointEdgeSelector` evaluates candidate powers of two (`P in {1, 2, 4, 8, 16, 32, 64, 128}`) point-by-point.

#### 15a. Constraints and sampling method

- **Hard expand guard:** The domain must contain at least 64 coarse cells per edge (`(2 * radiusChunks) / P >= 64`). This is a hard admissibility constraint, not a soft penalty term.
- **Conservative upper bound:** Sampling draws 32x32-chunk region blocks across the domain and computes a delete-one-block jackknife estimator. Selection picks the argmin of the conservative upper bound (`mean + 2 * SE`).
- **Lossless ratchet transitions:** On reload or domain expansion, coarsening is permitted only when the target `P` is a multiple of the stored `P` (`targetP % storedP == 0`), which folds upward losslessly. Non-multiple transitions or refinement require discarding and relearning.

#### 15b. Held-out radii scored against brute-force argmin oracle

Scored on held-out radii (`r in {256, 512, 1024}`) across both 25% and 45% usable densities against an exhaustive ground-truth oracle:

| density | radius | chosen P | oracle P | cells / edge | chosen runs | oracle min runs | regret | relative regret |
|---|---|---|---|---|---|---|---|---|
| 25% | 256 ch | 8 | 2 | 64 | 5 765 | 5 624 | +141 | **2.5%** |
| 25% | 512 ch | 16 | 2 | 64 | 22 014 | 21 370 | +644 | **3.0%** |
| 25% | 1 024 ch | 32 | 2 | 64 | 84 831 | 83 825 | +1 006 | **1.2%** |
| 45% | 256 ch | 8 | 2 | 64 | 9 356 | 9 140 | +216 | **2.4%** |
| 45% | 512 ch | 16 | 2 | 64 | 36 589 | 35 952 | +637 | **1.8%** |
| 45% | 1 024 ch | 32 | 2 | 64 | 142 358 | 140 915 | +1 443 | **1.0%** |

At every radius and density, the selector respects the hard granularity guard (exactly 64 cells per edge), chooses the coarse scale that minimizes upper-bound risk, and realizes a relative regret of **1.0% to 3.0%** against the exhaustive search. Criterion C8 is **MET**.

---

### 16. Reproducibility: Biome fields in the mock world and Section 14 re-run

`:rtp-core:simulationBenchmark --tests '*BiomeMockFidelityBenchmarkTest*'`, report `build/reports/rtp-simulation/biome-mock-fidelity.md`. Image raster `build/reports/rtp-simulation/img/biome-mock-fitted-mock.png`.

Temperature and humidity fBm fields were added to `NoiseWorldMask` with Whittaker-style classification into `BiomeClass` (`LUSH`, `BARREN`, `COLD`, `AQUATIC`). Parameters were fitted against real save Anvil probes via coordinate descent.

#### 16a. Coordinate descent fitting on biome fidelity

Evaluating biome per-class shares, boundary fraction, and mean patch size:

| metric | real save | fitted mock | mock / real ratio |
|---|---|---|---|
| LUSH share | 0.189 | 0.285 | 1.508 |
| BARREN share | 0.126 | 0.082 | 0.651 |
| COLD share | 0.217 | 0.165 | 0.760 |
| AQUATIC share | 0.468 | 0.468 | 1.000 |
| Biome boundary fraction | 0.215 | 0.283 | **1.316** |
| Mean biome patch size | 153.1 ch | 78.7 ch | **0.514** |
| Fidelity error | - | **5.718** (down from 7.182 default) | - |

Fitted parameters: `tempW=192.0, tempO=4, humidW=384.0, humidO=4` alongside tuned terrain knobs.

#### 16b. Section 14 re-run: which conclusions survive on the mock

The Section 14 accessibility experiment was re-run across distances `d in {0, 2, 4, 5}` and fixed gaps `gap in {2, 3, 8, 16, 64, 256}` on both real save and fitted mock:

| Section 14 conclusion | Real save result | Fitted mock result | Status on mock |
|---|---|---|---|
| **Predicate reach widens with d** | 0.392 -> 0.495 (+26%) | 0.918 -> 0.951 (+3.6%) | **Survives** (reach widens monotonically with d) |
| **Dilation premise falsified** | Runs rise with d (377 -> 399) | Runs rise with d (802 -> 822) | **Survives** (neither world buys runs by dilating) |
| **Hybrid holds fewer runs across coalescing gaps** | 29 vs 106 runs at gap=16 (3.65x smaller) | 39 vs 116 runs at gap=16 (2.97x smaller) | **Survives** (hybrid 3.0x-3.6x fewer runs at gap=16) |
| **Ranking flips under accessibility at gap=16** | Hybrid FP 0.002 vs Spiral 0.003 | Hybrid FP 0.000 vs Spiral 0.002 | **Survives** (hybrid FP strictly lower than spiral) |
| **Advantage widens at coarse gaps** | Gap=256: 0.021 hybrid FP vs 0.378 spiral FP (18x) | Gap=256: 0.000 hybrid FP vs 0.050 spiral FP | **Survives** (locality keeps hybrid FP bounded while spiral arcs blow out) |

All primary Section 14 qualitative and architectural conclusions survive on the mock world.

---

### 17. Persistence translation (Criterion C9): on-disk to in-memory

`:rtp-core:simulationBenchmark --tests '*CurvePersistenceBenchmarkTest*'`, report `build/reports/rtp-simulation/curve-persistence.md`.

Format version bumped to **BIN_VERSION = 5**. Header specification records: `magic (0x52545031)`, `version (5)`, `curve (string)`, `P (int)`, `keyWidth (int: 4 or 8)`, `worldName`, `scanStride`, `badSize`.

#### 17a. Key width 32-bit vs 64-bit disk footprint

At chunk precision, keys fit into an unsigned 32-bit `int` (`range <= Integer.MAX_VALUE`, covering up to 46,340 chunks radius, well beyond a 100 km border of 6,272 chunks).

- **keyWidth = 8 (long):** 25 bytes per run on disk (69 115 bytes for 2 762 runs).
- **keyWidth = 4 (int):** 17 bytes per run on disk (47 019 bytes for 2 762 runs).
- **Measured saving:** **0.680** (32.0% byte reduction across the entire file payload).

#### 17b. Round-trip equivalence and offered-mark retention

- **Round-trip equivalence:** Evaluated on full test tables. Reloaded table is **run-for-run identical** (`keys[i]` and `deltas[i]` match exactly).
- **Offered-mark retention:** Every decoded run was probed against the ground-truth occupancy mask: **offered-mark retention = 1.000** (0 retention faults).
- **Codec throughput:** Serialize throughput 1 201 ns/run; deserialize throughput 715 ns/run.

#### 17c. Unit mismatch and upward fold ratchet

- **Multiple coarsening (P=16 -> P=32):** Lossless upward fold succeeds, producing a valid coalesced table with **1.000 offered-mark retention**.
- **Non-multiple mismatch (P=16 -> P=24):** Incompatible spatial unit detected; table safely discarded and relearned without crashing or silently corrupting keys.
- **Replaceable seam:** Implemented as `CurvePersistenceSeam.PersistenceBackend` (in-memory, file, or remote). Enables future cross-server database sharing where servers sharing identical seeds and regions share learned state directly.

Criterion C9 is **MET**.

---

### 18. Circle geometry and normal distribution variants in key space

`:rtp-core:simulationBenchmark --tests '*CircleAndNormalDistributionBenchmarkTest*'`, report `build/reports/rtp-simulation/circle-and-normal-distribution.md`.

#### 18a. Polar angle quantization and multi-preimages in shipped Circle

Unlike `Square` which establishes a strict 1-to-1 bijection between 1D keys and 2D Cartesian chunks, shipped `Circle` parameterises space via polar Archimedean spiral coordinates:
$$R = \sqrt{\frac{\text{location}}{\pi} + cr^2}, \quad \theta = 2\pi \cdot \text{rotation}$$
When mapped into integer chunk coordinates $(cx, cz) = (\lfloor R \cos \theta + 0.5 \rfloor, \lfloor R \sin \theta + 0.5 \rfloor)$, continuous angle quantization across diagonal chunks causes up to two 1D location keys to map to the exact same 2D chunk:
- Measured on shipped `Circle` ($r=128, cr=8$): **99.1% of chunks within the annulus hold exactly 2 preimage location keys** (`chunkToLocations(cx, cz).length == 2`), while 0.9% hold 1 preimage, and exactly 0 chunks hold $> 2$ preimages.
- When an unusable chunk is marked via `addBadChunk`, `Circle` correctly marks both preimages, preserving full exclusion guarantees despite diagonal multiplicity.

#### 18b. Radial monotonicity and bounded jitter under hybrid addressing

`NormalMemoryShape` and `Circle_Normal` sample destinations using a truncated Gaussian draw over $[0, \text{range})$ scaled by an empirical exponent:
- Because the Archimedean spiral sweeps outward, 1D key progression correlates with radial distance:
  - Shipped `Circle`: Area-to-index correlation $= 1.000$ (exact $k = \pi r^2$).
  - Hybrid Curve ($P=8$): Area-to-index correlation $= 0.909$ (macro-spiral outward order).
- **Bounded intra-point jitter:** Inside any coarse point of edge $P$, local Hilbert traversal scrambles $P \times P$ chunks. The maximum radial jitter is strictly bounded by the point's diagonal:
  $$\Delta R_{\max} \le \sqrt{2} \cdot P \quad (\approx 11.31 \text{ chunks for } P=8)$$
  Measured maximum intra-point jitter on $r=256, P=8$ is **$9.90$ chunks**. Because this jitter is bounded and tiny relative to world radius, Gaussian radial bell curves sampled over 1D keys translate to 2D space without distorting distribution peaks (verified: $N=20\,000$ samples on `Circle_Normal` with $\text{mean}=0.5$ placed peak density squarely in deciles 4 and 5).

#### 18c. Run reduction on circular domains

On circular domains bounded by $cx^2 + cz^2 \le r^2$ with 45% usable terrain:
- Shipped `Circle` (polar spiral): $28\,044$ exact runs, $28\,044$ runs at $\text{gap}=2$.
- Hybrid Curve ($P=8$): $24\,741$ exact runs (**0.882 ratio**), and $16\,304$ runs at $\text{gap}=2$ (**0.581 ratio**).
- Hybrid addressing produces **42% fewer coalesced runs** on circular domains, confirming that boundary clipping along circular perimeters does not fragment the Hilbert ordering.
- Reconciliation at full precision is not established: the coarse-setting figures are favourable, the `gap = 1` figures are within noise of the spiral's.
- **At a fixed knob the hybrid can be strictly worse on accuracy** (section 11b): at the shipped default it discards 0.111 of usable ground against the spiral's 0.082 on the real save. The saving only exists once accuracy is held fixed, so no figure at a fixed `spatialResolution` may be quoted as a byte win.
- **The advantage narrows to nothing at a tight budget on adverse terrain** (section 11b): 0.98x on the mock at a 5% cap. The sweep grid is coarse there and neither curve has a setting between 1 and 2, so the crossover is bracketed rather than located.
- **The shipped default already costs usable ground on both curves** (section 11a), so any density an operator measures on a running server is terrain minus coalescing loss. Several earlier sections' densities came from masks, not from running servers, and the two are not interchangeable.
- Section 11 is measured at radius 128 chunks only, so it tests the direction on real verdicts and says nothing new about scaling; the border rows remain those of section 9, on occupancy.
- **Proximity weighting does not rescue the settings where the hybrid loses** (section 12b): at the two finest fixed gaps the ratio stays at 0.69-0.75 on both yardsticks, so weighting is a better metric rather than a new argument for the curve.
- **The beach discount is a stated preference, not a measurement**, and the dynamic rule is one greedy non-idempotent pass measured at a single radius on one window per world (section 12d). The dynamic rule is also not part of this decision - it is an orthogonal improvement to the shipped coalescer and would need its own.
- **The per-save fit is a local search over three statistics on two saves from one machine** (section 13), both 1.21.11, so "the generator can be matched" is demonstrated rather than general, and no curve figure has yet been re-measured on a fitted world.

### 19. Hardware cache-hierarchy secondary tables and anti-thrashing hysteresis

`:rtp-core:simulationBenchmark --tests '*SegmentedKeyRunTableBenchmarkTest*' --tests '*ThreeWayScaleBenchmarkTest*' --tests '*AdaptiveTableControllerBenchmarkTest*'`, reports `build/reports/rtp-simulation/segmented-key-run-table.md`, `three-way-scale-benchmark.md`.

#### 19a. Active span pinning and elimination of backward search

Splitting the continuous Hilbert key space into secondary bins of size $B$ (e.g. 256–1024 chunks) introduces the classic interval boundary hazard: an ocean-sized bad run longer than $B$ leaves intermediate bins with no start-key. To prevent $O(N)$ backward pointer chasing, **active span pinning** clamps every run overlapping bin interval $[b \cdot B, (b+1) \cdot B)$ to the bin's bounds. Any run starting prior to the bin is pinned at offset 0.

- **Equivalence:** 100.00% exact boolean query match against the flat table across all keys.
- **Search speedup:** In-bin binary searches across 6–22 local runs execute inside L1 data cache in **13.9–35.4 ns/op** (up to 2.31x faster than global flat binary search).
- **Negligible run amplification:** Splitting runs across bin boundaries adds only **+2.6% runs** at $B=1024$ (560 vs 546 runs) and **+10.4%** at $B=256$.

#### 19b. Near-full bin collapse threshold

When a bin's remaining un-bad chunks fall within the configured `spatialResolution` coalescing tolerance (e.g. $\le 3$ usable chunks), the bin collapses to `FULL_BAD`:
- Discards all local array allocations and replaces them with a singleton shared reference.
- Saves $\sim 48\text{ B}$ per ocean/near-ocean bin while eliminating binary search in that bin completely.

#### 19c. Three-way benchmark across scales

| Scale | Model | Stored Runs | Heap Footprint | Lookup Latency | Reconcile Latency | Speedup vs Original |
|---|---|---|---|---|---|---|
| **0.8 km (spawn)**<br>`r = 50 chunks` | **Original Spiral**<br>**Flat Hilbert**<br>**Segmented Hilbert** | 331<br>189<br>210 | 5.3 kB<br>3.0 kB<br>4.7 kB | 75.3 ns<br>67.2 ns<br>**62.0 ns** | 2,422 ns/mark<br>1,380 ns/mark<br>**519 ns/mark** | 1.0x<br>1.12x<br>**1.21x** |
| **4.1 km (medium)**<br>`r = 256 chunks` | **Original Spiral**<br>**Flat Hilbert**<br>**Segmented Hilbert** | 1,937<br>1,273<br>1,322 | 31.0 kB<br>20.4 kB<br>**14.1 kB** | 34.9 ns<br>26.8 ns<br>**20.6 ns** | 4,966 ns/mark<br>4,232 ns/mark<br>**1,148 ns/mark** | 1.0x<br>1.30x<br>**1.70x** |
| **8.2 km (large)**<br>`r = 512 chunks` | **Original Spiral**<br>**Flat Hilbert**<br>**Segmented Hilbert** | 1,937<br>1,273<br>1,506 | 31.0 kB<br>20.4 kB<br>**24.4 kB** | 20.9 ns<br>18.4 ns<br>**13.4 ns** | 27,904 ns/mark<br>32,043 ns/mark<br>**1,292 ns/mark** | 1.0x<br>1.14x<br>**1.56x** |

#### 19d. Domain-proportional bin sizing and wrapper overhead elimination

When bin size is derived adaptively according to domain scale via `deriveOptimalBinSize(totalRange)` (targeting 32–128 total bins, powers of two in $[128, 4096]$) alongside near-full bin collapse:
- **Heap collapses from 121.9 kB down to 24.4 kB at 8.2 km** — Segmented Hilbert is now **smaller than the original shipped spiral (24.4 kB vs 31.0 kB)** while remaining within a hair of Flat Hilbert (20.4 kB)!
- **At 4.1 km, Segmented Hilbert is 14.1 kB** — strictly beating both the original spiral (31.0 kB) and flat Hilbert (20.4 kB) because near-full ocean bins collapse to singleton references without local arrays.
- Lookup latency stays at **13.4–20.6 ns/op** (1.56x–1.70x faster than original).
- Reconciliation latency drops to **1,292 ns/mark** (an incredible **21.6x faster than original** 27,904 ns/mark, and 24.8x faster than flat Hilbert 32,043 ns/mark) because marks rebuild only a single bin of 10–25 runs off-tick from dirty cache.

#### 19e. Anti-thrashing hysteretic controller (`AdaptiveTableController`)

To protect the "lowest RAM footprint on the market" claim on memory-constrained servers, the system does not run segmented unconditionally:
- **Default / Low Headroom (< 15% free heap):** Instantly drops to `FLAT_COMPACT` (20.4 kB).
- **High Headroom (> 35% free heap):** Promotes to `SEGMENTED_ACCELERATED` only after remaining stable for $\ge 5$ consecutive pulse epochs.
- **Dead-band (15%–35%):** Mode never switches. Verified: 50 cycles of rapid memory oscillation produced **zero unwanted mode transitions**.

---

### 20. Native bad-coordinate ACCUMULATE offset resolution and ADR-079 staged probation coherence

`:rtp-core:simulationBenchmark --tests '*AccumulateOffsetResolutionBenchmarkTest*'`

#### 20a. Inversion elimination: counting bad chunks instead of good chunks

Earlier conceptual iterations explored calculating `binGoodCount` and positive good-chunk prefix sums. This inverted the engine's core model:
- RTP never tracks "good" coordinates; it discovers and records bad sectors (`badKeysCache`, `badPrefixSumsCache`, `badSum`).
- Inverting to "good counts" required subtracting from domain capacity, computing prefix sums of the inverse, and fighting S-004 failure semantics.
- Maintaining native bad prefix sums across bins (`dirBadPrefixSums[i] = total bad chunks in bins[0..i]`) eliminates domain inversion while matching on-disk `.bin` format directly.

#### 20b. Two-tier ACCUMULATE resolution latency (11.2x speedup)

In `ACCUMULATE` mode, a raw sample $T \in [0, \text{totalGood})$ is mapped to physical coordinate space by skipping preceding bad runs:
- **Shipped Flat Loop (`MemoryShape#resolve`):** Performs an iterative fixed-point loop of binary searches over the global array. Across real Minecraft save data ($r=96$ chunks), flat resolution averaged **1 571.4 ns/op**.
- **Segmented Two-Tier Resolution:** Tier 1 locates the target bin via binary search on `dirBadPrefixSums` ($\le 64$ entries in L1 cache), and Tier 2 resolves locally within the bin's 6–15 local bad runs. Segmented resolution averaged **140.0 ns/op** — an **11.22x speedup**.
- **Equivalence:** 10,000 random samples across real world data and exhaustive synthetic tests confirmed **100.00% exact coordinate match** against the shipped flat loop, with zero out-of-bounds or missed runs.

#### 20c. ADR-079 staged probation coherence

Active span pinning and two-tier bad sums cohere cleanly with ADR-079 cause-based TTL and staged probation:
1. **Active Avoidance vs Probation Omission:** Candidate selection avoids only active bad runs (`dirBadPrefixSums`). Probationary runs (expired dynamic claims awaiting re-verification) are omitted from active avoidance and tracked in local bin probation arrays.
2. **Local $O(1)$ Probation Restoration:** When a candidate location fails pipeline verification, `checkAndRestoreFromProbation(key)` resolves target bin $b = \text{key} / B$ via arithmetic and inspects only Bin $b$'s local probation array ($\le 3$ entries in L1 cache), replacing the global $O(\log M)$ binary search over the entire world probation table.
3. **Localized Dirty Cache:** Restoring or expiring a dynamic claim mutates only the affected bin and updates 64 ints in `dirBadPrefixSums` ($< 20\text{ ns}$), leaving all other bins untouched.

---

### 21. Extreme planetary domains (100km to 30,000km): Paged bins, optimistic empty default for new land, and single-page swapping

`:rtp-core:simulationBenchmark --tests '*PagedSegmentedKeyRunTableBenchmarkTest*'`

#### 21a. Planetary scale (14 trillion chunks) and the optimistic empty default for new land

A vanilla Minecraft world border ($\pm 30,000\text{ km}$, radius $1,875,000\text{ chunks}$) contains $(3,750,000)^2 \approx 14.06 \times 10^{12}$ chunks.
- **The Optimistic Empty Default (`SOLID_EMPTY`):** Untouched and ungenerated bins default to `SOLID_EMPTY` (0 bad chunks, 100% available for selection).
  - An assumed-full default would paralyze server exploration, permanently trapping players in spawn or causing S-004 starvation on new servers.
  - Defaulting to `SOLID_EMPTY` preserves the engine's core purpose: allowing players to discover, select, and generate new land frontiers.
  - **RAM footprint of untouched space:** **0 bytes**. Uses a shared singleton `EMPTY_BIN` with zero array allocations.
- **`SOLID_FULL` is reserved for proven rejection:** Whole ocean regions and out-of-bounds space collapse to `SOLID_FULL` (also 0 bytes) only after verification.
- **Ungenerated frontier bypasses L3 file compute:** Because ungenerated frontiers have no `.mca` files on disk, L3 Anvil pre-filtering is cleanly bypassed without wasted file I/O, falling back gracefully to engine chunk generation.
- **Future Tagging Note:** On promotion from backlog to cold/hot queue, an explicit enum tag (`UNSCREENED_FRONTIER` vs `PREFILTER_VALIDATED`) will allow the teleport pipeline to apply cautious vs fast-track generation timeouts and smart queue balancing.

#### 21b. Single-page swapping with bounded resident capacity

For extreme servers where active mixed bins accumulate, `PagedSegmentedKeyRunTable` bounds resident heap memory:
- **Bounded Resident Pages:** Retains at most $M$ mixed bin arrays in JVM heap (e.g. 64 to 256 pages).
- **Single-Page Eviction Under Budget:** When a new mixed bin is loaded or dirtied, cold mixed bins are dropped **one page at a time** to disk storage (or Redis), maintaining strictly bounded RAM without full table invalidation.
- **Page Fault Speed:** When a candidate query hits a paged bin, the bin faults in its exact 4 kB page from storage. Because active spans are pinned at offset 0, each paged bin is completely self-contained (zero cross-page dependencies).

#### 21c. Low-thrashing hysteresis and equivalent page cycling

- **Anti-Thrashing Eviction:** Drops pages one by one only when exceeding capacity, avoiding destructive cache wipes.
- **Equivalent Page Cycling:** Periodically evicts an idle LRU page to storage and warms up an alternate paged page of equivalent access weight, preventing static residency lock-in and distributing cache warming across the planetary domain.
- **Verified:** Synthetic and scaled benchmark suites confirmed 100.00% coordinate equivalence under active page evictions, faults, and cycling.

---

### 22. Variant extensions: Polygon, Ellipse, and Normal (Gaussian) distributions

`:rtp-core:simulationBenchmark --tests '*PolygonEllipseAndNormalBenchmarkTest*'`

#### 22a. Polygon under dual-layer Hilbert: 33% run reduction and 2.4x selection speedup

In the legacy implementation (`Polygon`), outside-polygon space within the AABB is populated by an async mask walker. Under the legacy pure Archimedean spiral, cutting an arbitrary polygon boundary (e.g. concave star, arrowhead) with 1D concentric rings causes severe run fragmentation across ring seams.

Under `PolygonOptimizedDualLayer`:
- **Run Fragmentation Collapses:** On a 512x512 star polygon with 125,736 outside chunks, runs dropped from **647 down to 431** (a **0.666 ratio**, 33.4% fewer runs). 2D Hilbert locality groups outside AABB corners into long contiguous runs.
- **Selection Latency:** ACCUMULATE candidate selection latency dropped from **788.4 ns/op down to 326.7 ns/op** (**2.41x speedup**).
- **Zero-Byte Solid Bins:** Bins completely outside the polygon collapse to singleton references (0 bytes heap), avoiding array allocation entirely.

#### 22b. Ellipse inscribed in dual-layer Circle: arc lengths vs Hilbert clustering

In `Ellipse`, the shape is inscribed in a bounding circle. Because circular polar spirals trace continuous smooth circular arcs, they intersect an ellipse boundary at only 2–4 points per revolution.

Under `EllipseOptimizedDualLayer` (inscribed in `CircleOptimizedDualLayer`):
- **Full-Precision Run Count:** At full precision with terrain noise, runs were **485 (legacy polar) vs 540 (dual-layer circle)** (ratio **1.113**). Hilbert sub-traversal across macro-polar blocks cuts slightly more across the smooth radial arc before coalescing.
- **Selection Latency:** Dual-layer selection ran at **846.7 ns/op vs 878.5 ns/op** (1.04x speedup), benefiting from segmented L1 cache residency while maintaining 100% exact boundary containment.

#### 22c. Normal (Gaussian) distribution: ACCUMULATE vs REROLL under ocean holes

`NormalMemoryShape` defaults to `REROLL` mode. However, when ocean holes or invalid terrain occupy the domain (e.g. 40% ocean):
- **REROLL Mode:** Experienced **40.8% candidate rejections** (only 11,841 valid candidates out of 20,000 draws), taking **875.7 ns/op** and wasting CPU on reroll loops.
- **ACCUMULATE Mode with Pre-Limited Range:** Achieved **20,000 / 20,000 valid candidates (100.0% validity)** with zero failed draws, executing in **715.4 ns/op** (**1.22x faster** than REROLL).
- **Scale Invariance:** As range expands to large scales, shifting ocean holes smooth out in 1D prefix space, allowing Gaussian `ACCUMULATE` to deliver exact bell-curve probability without candidate reroll loops.

---

## Tried and declined

Retained as evidence, not as options:

- **2D geodesic blobbing prior to run-length merging.** Declined. Attempting 2D morphological dilation/clustering across usable ground before merging biome runs—rather than simply bounding run lengths by observable distance (within 3 chunks, per vanilla ticket window)—worsened memory efficiency by 10-15% and increased compute time by >50%. The curve's intrinsic locality and 1D coalescing already capture the spatial envelope.
- **ADR-083, two-tier probabilistic routing.** Rejected. Its area-growth premise is measured false (perimeter-order, exponent 0.500), its `PI_MIN` subsampling discards precision where demotion would preserve it, and its `supportsExpand() = false` was an unforced concession.
- **ADR-084, layered directory over per-cell blobs.** Rejected. The resident-fraction argument was an artifact of a sub-chunk unit; at chunk precision the directory is 0.56-0.62 of total state and the shipped spiral's whole table is smaller than the directory alone.
- **Coarsening the addressed unit.** Declined as a default. 46-53% of usable ground discarded at one region file per cell.
- **Thresholded collapse of near-uniform cells.** Declined as a default, retained as a possible operator knob. It does not compose with coarsening - most of its saving is already taken.

## Maybe later

- **Roaring bitmap containers keyed by region.** Still the best full-precision encoder candidate, orthogonal to the curve, and the one a competitor's engineer would recognise.
- **Storage-tier residency planning.** Relevant the moment persistence of a subsection is real.
- **k2-tree, Elias-Fano** on the key arrays.

## Status of the gate

Superseded by the audit below. The paragraphs that stood here described a closed gate - test scope only, nothing registered with the shape factory - which the shipped tree no longer matches. They are retained in git history rather than in the record, because a stale gate is worse than none: it tells a reader the opposite of what is running.

## 23. Audit against the shipped tree (2026-09-10)

Re-run of the whole ADR-080 tier on the current tree: `:rtp-core:simulationBenchmark`, 120 tests, **2 failed**. Findings ordered by consequence.

### 23a. The bijection shipped, and the gate text did not follow it

`CircleOptimizedDualLayer` and `SquareOptimizedDualLayer` live in `rtp-core` main and are registered in `RTP.java` alongside `CIRCLE_DEPRECATED_PURE_SPIRAL` / `SQUARE_DEPRECATED_PURE_SPIRAL` aliases. They are advertised to operators in `docs/admin/configuration/CONFIGURATION.md`, `REGIONS.md`, `docs/dev/CONCEPTS.md` and `CHANGELOG.md`, catalogued in ADR-034, and carried by the REQ-RTP-F-002 row of `TRACEABILITY.md`. Of the four conditions the old gate set for approval:

| Condition | State |
|---|---|
| Bijection in a shipped shape variant | **Done** - two registered shape engines |
| `.bin` header fields plus version bump | **Done** - `BIN_VERSION = 5`, `curve` / `P` / `keyWidth` written by `MemoryShape` |
| `P` derived from range | **Done** - shipped `PointEdgeSelector`, dynamically derived in `SquareOptimizedDualLayer` and `CircleOptimizedDualLayer` (see 23b) |
| `TRACEABILITY.md` rows | **Done** - `PointEdgeSelector` shipped in `rtp-core` main under REQ-RTP-F-002 |

### 23b. Criterion C8 derivation in shipped code (updated 2026-09-12)

Previously, C8 was recorded MET in test scope but NOT MET in shipped code because the shapes pinned `pointEdgeChunks = 32`.

**Resolved (2026-09-12):**
1. `PointEdgeSelector` is shipped in `rtp-core` main under `common/selection/region/selectors/memory/shapes/util/PointEdgeSelector.java`.
2. `SquareOptimizedDualLayer` and `CircleOptimizedDualLayer` derive `P` dynamically when not explicitly configured, using `MemoryShape.derivePointEdgeChunks(radiusChunks)` which delegates directly to `PointEdgeSelector.derivePFromRadius(radiusChunks)`.
3. If radius changes, $P$ is recalculated while maintaining the hard guard `(2 * radius) / P >= 64` (falling back to $P=1$ for sub-64 chunk domains).
4. The benchmark-tier test copy was de-forked to delegate directly to production `PointEdgeSelector`, and `ADR085ProductionIntegrationTest` verifies derivation, guards, and transition ratchets in production. C8 is now **MET** in shipped code.

### 23c. The dynamic gap: literal ceiling plus a run-length driver, measured on both coalescing paths (updated 2026-09-10)

Section 12c's rule is now `MemoryShape.computeAdmissibleGap`, called from five shipped coalescing sites (biome union fold, in-place mark extension, merge, and the ADR-079 staged pass), unit-tested by `MemoryShapeTest`, and referenced by ADR-092. The shipped form is now:

```
if (spatialResolution <= 3) return max(1, spatialResolution);
return max(1, min(spatialResolution, min(leftLength, rightLength)));
```

The earlier `res / 4` floor is gone and the ceiling is now literal: `spatialResolution: 16` caps the bridge at 16, not at 4. This is a deliberate operator-facing decision - the configured number is a clear upper bound rather than a square-root-style assumption about the 1D-to-2D relationship. The prior audit text flagged this rule as "not the measured winner" against section 12c's `alpha = 0.125`; that framing is retired, because it measured the wrong path. The correction:

**The `min(leftLength, rightLength)` driver has two regimes, and section 12c only measured one.**

1. **Unit-width mark stream (inert).** The shipped mark-and-flush rebuild feeds unit-width pending runs in ascending key order, so `rightLength` is always 1 and `min(accumulator, 1) = 1` on any terrain. Every no-floor alpha variant collapses onto the same curve here - `alpha = 0.125`, `0.5` and `1.0` produce byte-identical output - because there is nothing wider than one cell to bridge. Section 12c's `alpha` sweep and the `AdmissibleGapPolicyBenchmarkTest` chart both run on this path, which is why they read the driver as inert. That reading is correct for that path and says nothing about the other.

2. **Variable-width re-coalesce (load-bearing).** `coalesceRuns` also runs on already-formed runs with real `nextLength` - the biome-union fold and the staged re-coalesce - where the driver carries the terrain's structure. `AdmissibleGapPolicyBenchmarkTest.minDriverOnVariableWidthRuns` forms exact runs first, then re-coalesces the variable-width runs, on the seeded noise mock and on a real on-disk save (`testdata-world` overworld, 256 region files, inscribed radius 192 chunks, usable share 0.624):

   | terrain | res | fixed(res) runs / flat loss | shipped min-driven runs / flat loss |
   |---|---|---|---|
   | real r=128 | 8 | 451 / 0.378 | 878 / 0.217 |
   | real r=128 | 16 | 150 / **0.661** | 818 / **0.266** |
   | real r=128 | 32 | 28 / 0.892 | 818 / 0.266 |
   | noise 45% | 16 | 554 / 0.320 | 1418 / 0.148 |
   | noise 45% | 32 | 262 / 0.509 | 1407 / 0.154 |

   On real terrain at `res = 16` a fixed gap discards 66% of usable ground; the min-driven rule holds discard to 27% and then **saturates** - past `res = 16` the loss does not grow, because a short shoreline run beside a long inland run caps the bridge at the short one no matter how high the ceiling rises. This is the "visible land from the selection point" objective stated directly: a bridge is licensed only up to the smaller neighbour, so coarse resolutions cannot eat the coast. Section 12c argued this from flat aggregate loss on the inert path; the objective it was reaching for is the variable-width behaviour measured here.

**What still stands from the prior audit:** the rule remains unrecorded as an Accepted decision outside section 12 and ADR-092 (Proposed), and section 3's promise that `spatialResolution` keeps "its existing meaning" is no longer literally true - the number is now a bridging ceiling driven below by run length. That is a real semantics change and this section is the record of it.

### 23d. Both failing tests are oracle-fidelity assertions, and they fail for the same reason

- `BorderScaleFootprintBenchmarkTest.encoderAgreesWithShippedRebuild`: shipped rebuild 142 runs, `KeySpaceRunEncoder` 103, at spiral r = 128 ch, res = 64.
- `ProximityWeightedLossBenchmarkTest.fixedCoalescerMatchesShipped`: shipped 1 523 runs, test-scope fixed coalescer 721, at gap 8.

Both model coalescers still bridge on a static gap. Both fail in the direction the shipped dynamic rule predicts - fewer bridges, more runs - so these are not flaky measurements, they are the assertions that exist to catch precisely this fork, doing their job. They shall be fixed by porting the models onto `computeAdmissibleGap`, not by relaxing the tolerance.

**Resolved (2026-09-10):** both models were ported onto `MemoryShape.computeAdmissibleGap` with the accumulator as the running length, and the benchmark-tier private copies were de-forked. The full ADR-080 tier is now green (0 failed), including these two oracle-fidelity assertions.

### 23e. Section 8a's baseline predates the shipped coalescer

Re-measured on the current tree, same settings (r = 256 chunks, P = 32, tiled real save, bad density 0.751), from `build/reports/rtp-simulation/spiral-hilbert.md`:

| gap | curve | runs (8a) | runs (now) | loss (8a) | loss (now) |
|---|---|---|---|---|---|
| 1 | spiral | 1 164 | 1 164 | 0.000 | 0.000 |
| 1 | hilbert seam-matched | 1 226 | 1 226 | 0.000 | 0.000 |
| 16 | spiral | 1 102 | 1 149 | 0.002 | 0.000 |
| 16 | hilbert seam-matched | 352 | 637 | 0.021 | 0.007 |
| 256 | spiral | 255 | 686 | **0.368** | **0.064** |
| 256 | hilbert seam-matched | 116 | 198 | **0.098** | **0.047** |
| 4 096 | spiral | 1 | 15 | 0.989 | 0.880 |
| 4 096 | hilbert seam-matched | 13 | 47 | 0.621 | 0.264 |

Full precision is unmoved, which is the part of C3 that matters. Everything at a coalescing gap moved, both curves in the same direction - more runs, less loss - as the dynamic rule requires. Two published headlines do not survive:

- **"2.2x fewer runs while discarding 3.8x less usable ground" at gap 256 is now 3.46x fewer runs at 1.36x less loss.** The run advantage grew; the accuracy advantage mostly evaporated, because the dynamic gap gives the *spiral* most of what the curve was buying (0.368 to 0.064).
- **The gap = 4 096 row inverts.** The spiral now holds fewer runs than the hybrid (15 against 47), so "the spiral has destroyed the world while the hybrid still admits 37.9% of it" is no longer the trade being made at that setting.

Iso-accuracy (8b) survives and improves: at a 0.05 loss cap the hybrid is 5.80x smaller (1 149 against 198), against 5.27x published; at 0.10, 3.46x against 3.28x. C4 and C7 hold on the re-run; C5's sign at r = 512 / P = 32 also holds (0.924).

### 23f. A shipped class is measured through a benchmark-tier fork

`SegmentedKeyRunTable` exists twice - shipped at `common/selection/region/selectors/memory/table/` and copied under `common/benchmark/`. `SegmentedKeyRunTableBenchmarkTest` and the section 19 rows measure the copy. That is the same fork that produced 23d, one step earlier: a section 19 figure no longer attests to the class an operator runs. `PagedSegmentedKeyRunTable` (section 21) has no shipped counterpart at all, which is fine as long as section 21 says so.

### 23g. Drawn output is sound, and is the part that aged best

Thirteen rasters regenerate from `SpiralHilbertBenchmarkTest`, and every caption agrees with its own report row - spiral res = 1 024 states 83 runs at 0.235, hybrid states 29 at 0.064, both matching the `drawn` rows. The mark-and-flush-only fix recorded in 8e holds. The geometric claim reads true at a glance on the current tree: the spiral's loss is concentric square arcs spanning open ground, the hybrid's is a red fringe hugging the unsafe body. The sections added since - 18 (circle and normal), 22 (polygon, ellipse, gaussian) - carry no rasters, which is a gap given 8e's own argument that the images exist because two earlier published errors were geometric claims a picture would have refused.

### 23h. Not regression tested

The tier is opt-in and no CI job runs it, so nothing above would have been caught automatically. Every one of 23b, 23c, 23d and 23f is a divergence between a published figure and the tree, and all four appeared inside one release line. The minimum guard is the two oracle-fidelity assertions of 23d running on every change to `MemoryShape` coalescing; they already encode the invariant, they are simply not scheduled.

D-005 note: the shipped state described above went in without the before/after proposal this record's gate required. The audit does not retroactively approve it; it records what is running so the next change starts from the truth.

### 23i. Container representation for L3 usage state (2026-09-10)

An **exact** period-modulated bitmask ("period P, offset r, bit i set means position `r + i*P` is a hit" - one narrow bitmask per residue lane, lossless) was prototyped against the real L3 selection path (`selectL3Candidate`, dyadic stride + Feistel PRP) rather than terrain, and measured per 1024-chunk sub-bin from 1k to 500k non-repeating marks (`FrequencyContainerPrototypeBenchmarkTest`, ADR-080 tier). Result: the period encoding **never beats all three exact containers** (sole minimum in 0 of 4,096 bins at every density), and at saturation its per-lane headers even exceed a raw bitmask (532,470 vs 524,288 B). The cause is structural: the dyadic resonance spaces marks **between** bins, not within one - within a bin `key % 1024` ranges over all offsets, so a filling bin occupies every residue mod P and there is no single coarse sublattice to collapse onto. Exact **gap-coded / Elias-Fano keys dominate at every density** (2.7x under the bitmask cap even at 122 marks/bin); Elias-Fano's high-bit layer already *is* a period-P bitmask, keeping the within-lane offset in cheap low bits instead of splitting into separate lanes. The measured win for L3 usage state is therefore exact bit-packed-delta coding of the sparse key set, not a period container. Full numbers and the container-tier consequence are in the ADR-092 supplement.

## References

- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) - the shipped spiral mapping
- [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md) - benchmark tier and provenance tiers
- [ADR-083](ADR-083-two-tier-probabilistic-region-routing.md), [ADR-084](ADR-084-layered-spiral-hilbert-rle-index.md) - alternatives considered, with their measurement records
- `docs/dev/DESIGN.md`, `docs/dev/REQUIREMENTS.md` section 3
