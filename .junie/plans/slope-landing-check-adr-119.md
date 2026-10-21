---
sessionId: session-261020-112134-1qwa
---

# Requirements

### Overview & Goals
Let admins tell placements apart by slope, so a region can turn down steep landing spots. Build **Path B** now: a slope check at the landing spot, reading outward from the picked `(x, y, z)`. Write one ADR (**ADR-119**) for the whole slope detection and memory design. In that ADR, **Path A** (slope from the 5-point stencil) and the Y-band/slope memory tables are recorded as *considered, not rejected, not adopted*.

### Scope
**In scope**
- `docs/adr/ADR-119-slope-detection-and-elevation-memory.md` (status Proposed) plus a row in `docs/adr/README.md`. This is the D-005 proposal.
- Path B as an extra acceptance check per column in `LinearAdjustor`, `JumpAdjustor` and `FixedAdjustor`. It runs on both the live (`RTPChunk`) path and the Anvil probe (`ChunkColumnProbe`) path.
- Two new optional vert keys: `maxSlope` and `slopeRadius`.
- Admin docs (`docs/admin/configuration/REGIONS.md`), `CHANGELOG.md`, a new REQ id, and `TRACEABILITY.md` rows.

**Out of scope (written up in ADR-119 only)**
- Path A: slope from the 5-point stencil.
- Y-band categorical union table, slope-class table, `.bin` v5, and ADR-062 slope steering.
- Reads across chunk edges using the live `safetyRadius` neighbour grid.
- An ADR-059-style `_slope[...]` synthetic safety property. ADR-059 is still Proposed and not built.

### User Stories
- As an admin, I want to set `maxSlope` on a region's vert, so players don't land on cliff edges or steep hillsides.
- As an admin with no slope settings, I want selection and region caches to behave exactly as before.
- As a maintainer, I want the alternatives and future memory design written down, so later work builds on ADR-081/085/092 and doesn't start a parallel design.

### Functional Requirements
1. With `maxSlope` absent or `< 0`, the check is disabled. Selection is byte-for-byte unchanged, and so is `RegionCacheKey`.
2. When enabled, a candidate `(x, y, z)` that passes the existing `acceptColumnY` / probe acceptance is also slope-checked:
   - The check samples floors at the 4 cardinal offsets `±slopeRadius`, clamped to local `[0,15]`. A side with zero distance after clamping is skipped.
   - In each sample column it looks for a standable floor only within `y ± (maxSlope + 1)`. It follows the picked layer and never scans from the sky, so it works in caves and the Nether.
   - The candidate is rejected if any sample has `|floorY - y| > maxSlope`, or has no floor within the window (a drop or wall bigger than `maxSlope`).
3. A rejected candidate does not end the column. The existing sweep continues, and then the next stencil column is tried. In effect, "first of 5" becomes "first column/Y that is also flat enough".
4. Live path and probe path give the same verdict for the same blocks (ADR-016 parity).
5. A chunk that misses only because of slope is still a bad chunk. That is safe because slope keys sit in vert data, which `RegionCacheKey.canonicalize` hashes.
6. The check reads only data already in memory: the resident chunk or the probe's sections (S-005). It is bounded at ≤ 4 × `(2·maxSlope+3)` block reads per accepted candidate.

### Non-Functional Requirements
- Zero cost when disabled: one int compare per adjust call.
- No new grammar, no new messages, and no locale keys.

# Technical Design

### Current Implementation
- `LinearAdjustor` / `JumpAdjustor` loop over `testCoords` `(7,7),(2,2),(12,12),(2,12),(12,2)` and return the first Y accepted by `acceptColumnY(...)` (live) or `acceptProbeY`/the probe scan (`adjustFromProbeWithReason`). `adjustColumn(...)` re-validates one column for `RegionCandidateValidator`. `FixedAdjustor` checks `(7,7)` only.
- Keys: `GenericVerticalAdjustorKeys` (Linear), `JumpAdjustorKeys`, `FixedAdjustorKeys`. Defaults go in a static `EnumMap`, and tab-completion is built from `subParameters`.
- `RegionCacheKey.canonicalize` hashes `vert.getData()` in full, so any key present in that data invalidates caches when it changes.
- `ChunkColumnProbe.blockAt(lx, lz, y)` / `isAirAt(lx, lz, y)` can read any column in the probed chunk.

### Key Decisions
- **B now, A deferred.** B only adds work to columns that were already accepted and keeps the first-hit order. A (scan all 5) costs up to 5× the scans. ADR-119 records A as the chosen input for a future per-chunk slope-class memory table.
- **Settings live on the vert, not in `safety.yml`.** This matches "Y comes from vert settings". It also puts slope into the cache key automatically, so bad-chunk marks can't outlive a change to the slope settings.
- **The new keys get no defaults entry.** They are read with `getOrDefault`. Unconfigured regions keep the same `getData()` and the same cache key, so the upgrade doesn't trigger a cache rebuild.
- **Slope is a block delta over a radius**, not a gradient or an angle. It's integer, easy to explain, and has no floating point.
- **Layer-following window** of `y ± (maxSlope+1)`. This avoids the cave/Nether layer aliasing described earlier.
- **Reads stay inside the chunk.** That is the only read set the live and probe paths share, so parity holds. Reads across chunk edges are listed in the ADR as future work.
- **No new `ProbeRejectReason`.** A slope miss is reported as `SCAN_MISS`, so `ScanTask` buckets don't change.

### Data Models / Contracts
```java
// AbstractVerticalAdjustor (new, package-visible helpers)
@FunctionalInterface interface FloorReader { boolean isStandable(int lx, int y, int lz); }

/** Pre: 0<=lx,lz<=15; maxSlope>=0. Pure; reads only via reader (S-005). */
static boolean slopeOk(FloorReader r, int lx, int y, int lz, int radius, int maxSlope,
                       int minY, int maxY);
// GenericVerticalAdjustorKeys / JumpAdjustorKeys / FixedAdjustorKeys: + maxSlope, slopeRadius
// slopeRadius default 2 when maxSlope >= 0, clamp [1,8]
```
- In each adjustor, `FloorReader` is an adapter over the existing standability test: the live variant uses `RTPChunk.isAir`/`isSafe`, and the probe variant uses `ChunkColumnProbe.isAirAt` plus the unsafe set. Both reuse the adjustor's current "standable" definition, so S-001 has a single definition.

### Proposed Changes
- In `LinearAdjustor`, the success branches of `adjust`, `adjustColumn`/`scanColumnForY` and `adjustFromProbeWithReason` call `slopeOk(...)` when `maxSlope >= 0`. On a failure, the sweep continues.
- Apply the same change to `JumpAdjustor` (live and probe) and `FixedAdjustor` (single column).
- Add `subParameters` entries `maxslope` and `slopeRadius` for tab completion.

### File Structure
- New: `docs/adr/ADR-119-slope-detection-and-elevation-memory.md`
- Modified: `docs/adr/README.md`
- Modified: `rtp-core/.../verticalAdjustors/AbstractVerticalAdjustor.java`, `GenericVerticalAdjustorKeys.java`, `linear/LinearAdjustor.java`, `jump/JumpAdjustor.java`, `jump/JumpAdjustorKeys.java`, `fixed/FixedAdjustor.java`, `fixed/FixedAdjustorKeys.java`
- Modified: `docs/admin/configuration/REGIONS.md`, `CHANGELOG.md`, `docs/dev/REQUIREMENTS.md`, `docs/dev/TRACEABILITY.md`
- `.bak` is required for the dirty `JumpAdjustor.java` (and for `VerticalAdjustor.java` if it is touched).

### ADR-119 outline
- **Context:** differentiating placements by slope. Heightmaps don't work in caves or the Nether. Y comes from the vert.
- **Decision:** Path B as specified above.
- **Alternatives Considered (not adopted, not rejected):**
  - Path A: 5-point stencil chunk slope, with cost, missing-point rule and selection semantics.
  - Y-band categorical union table (a generalized `BiomeUnionTable`, `band = floor((y-minY)/bandHeight)`, written at the `PregenTask`/`ScanTask` vert-success sites). Macro-slope comes from 2D neighbour `bandAt` lookups, with `|Δband|>k` treated as a layer break.
  - Slope-class table fed by A.
  - `.bin` v5 and ADR-062 steering.
  - Cross-chunk reads via the `safetyRadius` grid.
  - ADR-059 `_slope` synthetic property.
- **Consequences / future work:** run-growth measurement via ADR-080/090 before any memory table, plus the cache-key notes.

### Risks
- Steep-terrain regions may see more `SCAN_MISS` and more bad chunks. This is documented, and it only happens when the admin opts in.
- Slope is underestimated near chunk edges, where the window is lopsided. This is documented, and the cross-chunk alternative is recorded in the ADR.
- Live and probe standability could diverge. A parity test covers this.

# Testing

### Validation Approach
Unit tests in `rtp-core` with the existing fake chunks and probes from `LinearAdjustorTest`, `LinearAdjustorProbeTest`, `JumpAdjustorProbeTest` and `FixedAdjustorTest`. Run `./gradlew :rtp-core:test`. The ADR is docs only, so it needs no build.

### Key Scenarios
- `slopeOk`:
  - Flat terrain passes.
  - A step of `maxSlope` passes.
  - A step of `maxSlope+1` fails.
  - A missing floor (cliff) fails.
- Linear live: column `(7,7)` sits on a ledge and `(2,2)` is flat. With slope on, `(2,2)` is picked. With slope off, `(7,7)` is picked, so behaviour is unchanged.
- Probe path gives the same pick as the live path for the same block layout (parity).
- Jump and Fixed get the same accept/reject cases.

### Edge Cases
- Cave or Nether layering: a ceiling or another floor outside `y±(maxSlope+1)` is ignored.
- Chunk edge: when the clamped radius is 0 on one side, that side is skipped.
- `RegionCacheKey.cacheKey` is unchanged for a vert with no slope keys and changes when `maxSlope` is set.

### Test Changes
- New `ReqRtpSlopeLandingTest` (REQ id in the name or `@DisplayName`) plus additions to the existing adjustor and probe tests.
- New `TRACEABILITY.md` rows.

# Delivery Steps

### ✓ Step 1: Write ADR-119 slope detection and elevation memory design
ADR-119 (Proposed) records Path B as the decision and keeps Path A and the memory-table design as considered alternatives that were not adopted.

- Create `docs/adr/ADR-119-slope-detection-and-elevation-memory.md` from `ADR-TEMPLATE.md`, with these sections:
  - Context
  - Decision (B: keys, layer window, in-chunk bound, cache-key note)
  - Alternatives Considered: Path A stencil, Y-band union table, slope-class table, `.bin` v5, ADR-062 steering, `safetyRadius` cross-chunk reads, ADR-059 `_slope`
  - Consequences
  - References (ADR-016/059/062/081/085/092, S-001/S-005)
- Add the index row to `docs/adr/README.md`.
- Add the new REQ id to `docs/dev/REQUIREMENTS.md`, in `shall` phrasing.
- Scan for mojibake.

### ✓ Step 2: Add shared slope check helper with unit tests
`AbstractVerticalAdjustor.slopeOk` exists as a pure, bounded function and is covered by tests.

- Add the `FloorReader` functional interface and `slopeOk(reader, lx, y, lz, radius, maxSlope, minY, maxY)` to `AbstractVerticalAdjustor`:
  - Samples the 4 cardinal offsets, clamped to `[0,15]`.
  - Searches for a floor only within `y±(maxSlope+1)`.
  - Fails on a delta above the limit or a missing floor.
- Add a helper to read `maxSlope`/`slopeRadius` from data via `getOrDefault`, with no defaults entry and `slopeRadius` clamped to `[1,8]`.
- Add `ReqRtpSlopeLandingTest`: flat, step-at-limit, step-over-limit, cliff, chunk-edge clamp and cave-layer cases.

### ✓ Step 3: Wire slope check into LinearAdjustor live and probe paths
With `maxSlope` set, `LinearAdjustor` skips candidates that are too steep on both paths. With it unset, behaviour is unchanged.

- Add `maxSlope` and `slopeRadius` to `GenericVerticalAdjustorKeys` and `subParameters`. Don't add them to defaults.
- Call `slopeOk` in the success branches of `adjust` (all `direction` modes), `scanColumnForY`/`adjustColumn` and `adjustFromProbeWithReason`. On a failure, continue the sweep or move to the next column.
- Build `FloorReader` adapters over the existing live (`RTPChunk`) and probe (`ChunkColumnProbe`) standability tests.
- Tests:
  - Ledge-vs-flat column pick in `LinearAdjustorTest`.
  - Live/probe parity in `LinearAdjustorProbeTest`.
  - `RegionCacheKey` is unchanged when slope keys are unset and changes when they are set.
- Run `./gradlew :rtp-core:test`.

### ✓ Step 4: Extend slope check to Jump and Fixed adjustors and document it
All three vert types support `maxSlope`/`slopeRadius`, and the admin docs, changelog and traceability are updated.

- Make a `.bak` of the dirty `JumpAdjustor.java`.
- Add the keys to `JumpAdjustorKeys`. `FixedAdjustor` is excluded (user decision 2026-10-20: it places mid-air, so there is no floor to measure); ADR-119 and docs say so.
- Wire `slopeOk` into the live and probe acceptance of `JumpAdjustor`.
- Add accept/reject cases to `JumpAdjustorTest` and `JumpAdjustorProbeTest`.
- Document the keys, their semantics and the chunk-edge caveat in `docs/admin/configuration/REGIONS.md`. Add a `CHANGELOG.md` entry with no edition tag. Add `TRACEABILITY.md` rows.
- Run `./gradlew :rtp-core:test` and the pre-commit hook. Delete the `.bak` files once verified.