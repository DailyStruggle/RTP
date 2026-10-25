# ADR-119 - Slope Detection at the Landing Spot; Elevation and Slope Memory Recorded as Alternatives

**Status:** Accepted
**Date:** 2026-10-20
**Extends:** [ADR-016](ADR-016-anvil-subsystem.md) (live/probe acceptance parity). **Relates to:** [ADR-059](ADR-059-relative-ground-distance-safety-predicate.md), [ADR-062](ADR-062-biome-probability-weighting.md), [ADR-081](ADR-081-unified-blocked-biome-run-table.md), [ADR-085](ADR-085-spiral-addressed-hilbert-key-space.md), [ADR-092](ADR-092-hybrid-bit-packed-and-16-bit-run-container-hazard-table.md).

## Context

Admins want to tell placements apart by slope, so a region can refuse cliff edges and steep hillsides. Three facts constrain the design:

- **Y comes from the vertical adjustor (vert), not from a heightmap.** `LinearAdjustor`, `JumpAdjustor` and `FixedAdjustor` pick feet-Y by sweeping a column between the vert's `minY`/`maxY`. Heightmaps describe the topmost surface, which is wrong in caves and the Nether, where the picked floor is under a roof.
- **Selection is first-hit over a 5-point stencil.** Linear and Jump scan the columns `(7,7), (2,2), (12,12), (2,12), (12,2)` and return the first accepted Y; Fixed checks `(7,7)` only. Both the live path (`RTPChunk`) and the Anvil probe path (`ChunkColumnProbe`) follow this order and shall agree (ADR-016).
- **Reads are limited to data already in memory (S-005).** The probe can read any column of its own chunk, but not its neighbours. The live path additionally holds the `safetyRadius` neighbour grid, which the probe path does not.

Spatial memory already stores per-chunk categorical runs (biomes, ADR-081) in the spiral-addressed Hilbert key space (ADR-085), and `docs/adr/README.md` requires new spatial-memory work to extend that lineage rather than open a parallel design.

## Decision

**Path B - a slope check at the landing spot - is adopted.** It is an extra acceptance test applied to a candidate `(x, y, z)` that has already passed the vert's existing acceptance.

1. **Keys.** Two optional vert keys, accepted by `linear` and `jump`. `fixed` is excluded: it places players mid-air by design (skyblock, platform built afterwards), so there is no floor to measure and every candidate would fail.
   - `maxSlope` - largest allowed floor-height difference in blocks. Absent or `< 0` disables the check.
   - `slopeRadius` - horizontal sample distance in blocks. Default `2`, clamped to `[1, 8]`.

   Neither key has a defaults entry, so an unconfigured vert keeps the same `getData()` and the same `RegionCacheKey`; upgrading does not rebuild caches.
2. **Samples.** The check reads the 4 cardinal columns at `±slopeRadius`, clamped to chunk-local `[0, 15]`. A side whose distance clamps to 0 is skipped.
3. **Layer-following window.** In each sample column the check looks for a floor (a non-air block with air above it) nearest to `y`, within `y ± maxSlope`, never scanning from the sky. A floor on another cave layer or terrace outside the window is ignored, so the check works in caves and the Nether.
4. **Verdict.** The candidate fails if any sample has no floor within the window, which covers both a drop and a wall larger than `maxSlope`.
5. **Selection semantics.** A failed candidate does not end the column: the existing Y sweep continues (for `jump`, within the window its coarse jumps narrowed to), then the next stencil column is tried. First-of-5 becomes "first column and Y that is also flat enough". No new `ProbeRejectReason` is added; a slope-only miss is a `SCAN_MISS`.
6. **One definition, both paths.** The shared helper `AbstractVerticalAdjustor.slopeOk` reads through a `FloorReader` adapter. The live adapter uses `RTPChunk.isAir`, the probe adapter uses `ChunkColumnProbe.isAirAt`, so the same blocks give the same verdict (ADR-016). Safety of the landing cell itself stays with the existing acceptance (S-001); the slope check measures terrain only and never accepts a cell the existing test rejects.
7. **Bounded, in-memory.** At most `4 × (2·maxSlope + 1)` floor tests per accepted candidate, each two block reads, all inside the chunk already resident or probed (S-005). Disabled, the cost is one int compare per adjust call.
8. **Cache key.** Slope keys live in vert data, which `RegionCacheKey.canonicalize` hashes, so bad-chunk marks caused by slope cannot outlive a change to the slope settings.

## Alternatives Considered

The rows below are **considered, not rejected, and not adopted**. They remain the intended direction for slope memory and steering and may be adopted by a later ADR without superseding this one.

| Alternative | Status and reasoning |
|-------------|----------------------|
| **Path A - chunk slope from the 5-point stencil.** Scan all 5 columns, read the floor of each within `±k` of the first picked Y, derive a per-chunk slope (max pairwise delta over ~10 blocks). | Not adopted now. Up to 5× the vertical scans on the success path, worst in caves and the Nether. Needs a missing-point rule (e.g. fewer than 3 floors = "unknown", treated as a layer break). Changes selection: the chunk becomes the unit, and the flattest column may win over the first. It measures every chunk the same way, so it is the preferred input for a per-chunk slope-class table (below). |
| **Y-band categorical union table.** Generalize `MemoryShape.BiomeUnionTable` (ADR-081) into a categorical run table with id `band = floor((y - minY) / bandHeight)`, written beside `addBiomeLocation` at the `PregenTask` / `ScanTask` vert-success sites. | Not adopted now. Fits the existing run mechanics (identity merging, clipping, copy-on-write, LEB128 runs) and covers caves and the Nether because Y is the vert's pick. Macro-slope comes from 2D neighbour lookups `bandAt(cx±1, cz±1)` via `xzToLocation` (key order is not 2D adjacency, ADR-085): `Δband · bandHeight / (16 · Δchunks)`, with `|Δband| > k` classed as a layer break, not a gradient. Run growth (ADR-081 measured ~quadratic for biomes) must be measured first with the ADR-080 simulation tier or ADR-090 data, to pick `bandHeight`. |
| **Slope-class table fed by Path A.** A second categorical table (`flat` / `gentle` / `steep` / `cliff`), separate from bands so `band × class` does not multiply runs. | Not adopted now. Depends on Path A and the generalized table. |
| **`.bin` v5 with optional band / slope sections.** | Not adopted now. Needed only with a memory table; files at version ≤ 4 would load with no bands and fill from traffic or `/rtp scan`, as biomes do. |
| **ADR-062 steering over band or slope views.** | Not adopted now. The bounded weighted draw works unchanged over band or slope views ("prefer flat", "prefer highlands"); inert until a table is populated. |
| **Cross-chunk reads via the live `safetyRadius` grid.** | Not adopted now. Would remove the chunk-edge underestimate, but the probe path cannot read neighbours, so live and probe would disagree (ADR-016). Requires a probe-side neighbour source first. |
| **ADR-059-style synthetic `_slope[...]` safety property.** | Not adopted now. ADR-059 is Proposed and not built; when it is, the same `slopeOk` helper can back a `_slope` property in the ADR-017 grammar. |

## Measured, Deferred (2026-10-20)

Offline measurements on a real 26.3 Paper overworld save (~398k chunks, one floor per chunk centre read from block data; no heightmaps) informed the alternatives above. Memory-based slope rejection is **deferred**; nothing here changes the Decision.

- **Mountainside definition.** A chunk is a mountainside when its floor rises or falls at >= 30° toward at least 4 of its 8 neighbour directions, measured 1-2 chunks (16-32 blocks) away. This targets "climb without a pickaxe" terrain; plateaus and basins do not trigger it. Block-scale `slopeOk` at radius 2 mostly flags harmless plains dips and is not a terrain-scale signal. With full data, 5.6% of chunks qualify (1.5% at 45°).
- **Sparse elevation memory does not pay.** With 5-25% of chunk floors known (IDW or local plane fit), recall was 13-70%, and loads saved were at most ~3.8%. Even a perfect filter caps at ~5.6% saved. Per-chunk filters scored against block-scale `slopeOk` saved ~3-4% of loads at 74-87% false rejects.
- **Biome rules are not portable.** About 80% of mountainsides sit in ordinary biomes (forest, plains, savanna). Name-based families do not carry over to Iris, Terralith or datapack generators, and Iris builds steep relief inside single biomes. The best biome rule saved ~2% of loads.
- **Band compression.** An 8-band named contour model is ~4.5x smaller than one raw height per chunk. It is recorded as a world-shape compression format for a future map viewer, outside RTP selection.
- **Most promising next step (taken up by [ADR-120](ADR-120-mountainside-check-over-pinned-square.md)).** Apply the mountainside rule exactly on the `viewDistanceTeleport` square (5x5 at radius 2) already pinned by `TeleportPipelineTask` and held by queued `ChunkReservation`s: 24 extra floor scans in resident chunks, no new chunk I/O (S-005), re-selection on failure, landing-spot checks unchanged (S-001). Adoption requires a D-005 proposal and a successor ADR; measure it first with a simulated run that reveals 5x5 patches, not a random sample.
- **Block checks on the pinned 5x5 square (2026-10-20).** Same save, 1,962 standable land landings on vert-stencil columns, block reads only (no generator noise, so the result carries to custom generators). Mountainsides are 10.0% of land landings; cliff edges (a surface within 4 blocks differs by >= 5) are 28.2%.
  - The mountainside rule applied at the landing column, with layer-following floors at 16/32 blocks in 8 directions: 89.8% recall, 0.2% false rejects, 9.1% re-picks, ~234 block reads (~1.9 us on classified blocks). It misses most block-scale cliff edges (27% recall).
  - A plane fit over +-8 blocks at >= 30 deg catches cliff edges at 62% recall, 4.1% false rejects (~610 reads). Together with the rule: 96% mountainside and 66% cliff recall, 22.5% re-picks.
  - Walkability fills (+1/-3 or +1/-1, 150-1000 columns) reject 1-3.4% and catch 4-18% of mountainsides at 500-3,500 reads. One-block steps climb slopes up to 45 deg, so a fill finds traps, not mountainsides.
  - The shipped `slopeOk` (radius 2, `maxSlope` 1-3) has 13-39% false rejects against the mountainside truth and 8-28% against the cliff truth. The ruggedness and relative-height variants did not beat the plane fit.
- **Square clipped to one region file (2026-10-20).** The L2/L3 pre-compute reads one region file per probe drain, so a chunk whose region-local coordinate is 0, 1, 30 or 31 sees a partial square: 21.9% of chunks are on an edge, 1.6% on a corner. Measured on the same landings plus 1,168 oversampled edge and corner landings; directions and plane points outside the file are left out, never guessed.
  - At distance 1 from the file edge all 8 directions are still measured (the 2-chunk sample falls back to the 1-chunk one); at distance 0 an edge chunk measures 5 and a corner chunk 3.
  - Rule with the threshold scaled to the measured directions (reject at >= half): 83% mountainside recall on uniform landings (90% on the full square), 0.5% false rejects; edge 70%, corner 65%. A fixed ">= 4 of 8" threshold drops to 58% on edges and 33% on corners; deferring chunks with fewer than 8 directions drops further.
  - The plane fit over +-8 blocks barely changes (verdict flips on 1-2% of edge and corner landings). Scaled rule plus plane: 96% mountainside recall, 66% cliff recall, 22.5% re-picks, matching the full square.
  - Cost is decoding, not checking: one chunk costs about 220 us to decode from in-memory region bytes (full view or column probe alike), the check itself 1-4 us. The rule reads 14-15 neighbour chunks, so it pays only when the drain decodes those chunks anyway and keeps a compact per-chunk result. The rule samples each neighbour at the landing's own stencil column, so per-chunk floors at the 5 stencil columns are enough to serve it.
- **Harness.** Test-only classes under `rtp-core/src/test/java/io/github/dailystruggle/rtp/common/visualization/` (`ElevationMapVisualizationTest`, `SlopeQuickRejectMeasurementTest`, `MountainsideDetectionMeasurementTest`, `LandingTerrainCheckMeasurementTest`) skip when the reference save is absent. `LandingTerrainCheckMeasurementTest` writes a per-landing CSV to `build/reports/adr-119/landing-terrain-checks.csv`.

## Consequences

- **Positive:** Admins can refuse steep landing spots per region with two keys. Unconfigured regions behave and hash exactly as before. Works in caves and the Nether. No new grammar, messages or locale keys. The memory design is recorded so later work extends ADR-081/085/092.
- **Negative / Trade-offs:**
  - In steep terrain, opting in raises `SCAN_MISS` and bad-chunk counts.
  - Near chunk edges the window is lopsided (at `(2,2)` only 2 blocks reach west), so slope is underestimated there; at `slopeRadius > 2` the stencil corners clamp. Cross-chunk reads are the recorded remedy.
  - Slope is a block delta over a radius, not an angle; a 1-block step at radius 1 and at radius 8 count the same.
- **Future work:** Measure Y-band run growth (ADR-080 / ADR-090) before any memory table; then a successor ADR may adopt Path A, the band and slope-class tables, `.bin` v5 and ADR-062 steering.

## References

- REQ-RTP-F-015 (`docs/dev/REQUIREMENTS.md`); S-001, S-005.
- `rtp-core/.../verticalAdjustors/AbstractVerticalAdjustor.java` (`slopeOk`, `FloorReader`), `linear/LinearAdjustor.java`, `jump/JumpAdjustor.java`.
- `rtp-core/.../selection/region/RegionCacheKey.java` (`canonicalize` hashes vert data).
- ADR-016, ADR-017, ADR-059, ADR-062, ADR-080, ADR-081, ADR-085, ADR-090, ADR-092.
