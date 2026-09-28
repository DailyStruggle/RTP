# ADR-097 — Per-Action Pre-Validated Caches, Anvil Region Pre-Screening, and Bounded Subspace Placement Retries

**Status:** Accepted (amended 2026-09-25)
**Date:** 2026-09-25 (amended; accepted 2026-09-23)
**Extends:** [ADR-016](ADR-016-anvil-subsystem.md) (Anvil Subsystem), [ADR-028](ADR-028-l3-backlog-cache.md) (L3 Backlog Cache), [ADR-078](ADR-078-composable-cache-pipeline-stages.md) (Composable Cache Pipeline Stages), [ADR-093](ADR-093-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md) (Scripted Actions), [ADR-095](ADR-095-subspace-anchor-providers-and-near-teleport-primitives.md) (Subspace Anchor Providers)
**Related:** [ADR-077](ADR-077-multi-format-region-support.md) (Multi-Format Region Support), [ADR-079](ADR-079-cause-based-ttl-and-staged-expiration.md) (Cause-Based TTL and Spatial Memory Learning), S-001..S-005 (Safety Invariants)

---

## Context

In RTP's subspace placement architecture (ADR-093, ADR-095), a placement request selects an anchor $(X_0, Z_0)$, derives a lattice of safe candidate columns, and performs async external verification (`GlobalRegionVerifiers`, claim checks under S-003).

Three operational challenges arise when subspace candidate evaluation encounters real-world server environments and non-flat Minecraft terrain:

1. **Unloaded-Chunk Cache Misses in Single-Try Validation:** Subspace candidate evaluation previously queried `world.getCachedChunk(...)`. When candidate chunks fell outside the loaded server radius or on the perimeter of the pre-warmed footprint, `getCachedChunk` returned `null`, triggering a premature fail-closed drop (`INSUFFICIENT_SAFE_SLOTS`) even when valid ground existed on disk.
2. **Single-Column Fragility vs. Intra-Chunk Multipoint Geometry:** Candidate slots evaluated only a single discrete block column $(X_i, Z_i)$. In natural terrain, minor obstructions (e.g. tree leaves, single-block water puddles, steep ledges) caused `adjustColumn` to fail, discarding the entire chunk. However, `AbstractVerticalAdjustor` canonically defines 5 spatial probe coordinates across every chunk (`(7,7)` center plus four quadrant coordinates `(2,2)`, `(12,12)`, `(2,12)`, `(12,2)`). Failing to probe these fallback coordinates causes artificial candidate attrition.
3. **Rigid Lattice Decimation & Encapsulating Claims:** Rigid mathematical grid evaluation without local spatial exploration drops 70–80% of candidates on natural slopes and claims, terminating early before a viable landing cluster can be selected.

---

## Decision

### 1. Two-Stage Subspace Candidate Pipeline (Anvil Region Pre-Screening Binned with L3)

Subspace placement adopts a decoupled two-stage pipeline operating alongside the L3 Backlog cache (ADR-028) and the Anvil/Linear pre-filter (ADR-016, ADR-077):

```
[Stage 1: Off-Tick Anvil Region Pre-Screening]
Lattice / Sector Generators
       │
       ▼ Over-provision ~2x potential placement points (2N)
Map to .mca / .linear Region File Bins (32x32 chunks, ADR-028)
       │
       ▼ AnvilRegionByteCache / AnvilIoPool (ADR-016 / ADR-077) - Off-tick NBT read
Check Biome, Ocean/River, Surface Heightmap, & 5-point Columns
       │
       ▼ ~2N Candidates Pre-Filtered with zero server chunk loads (S-005)
[Stage 2: On-Demand Chunk Loading & Final Revalidation]
Select Best-Fit Candidate Set (Mutual elevation & spacing)
       │
       ▼ Asynchronous world.getChunkAt(...) on target candidates only
Live SafetyScan & Vertical Column Re-validation (trying TEST_COORDS if primary blocked)
       │
       ▼ Final Selection of N mutually safe slots
Teleport Dispatch & Ticket Release (S-002)
```

- **Over-Provisioning Factor ($2\times$):** For an action requiring $N$ participant slots, Stage 1 generates and pre-filters approximately $2N$ candidates across the subspace footprint. This over-provisioning absorbs natural terrain attrition without aborting the placement attempt.
- **Off-Tick Anvil / Linear Pre-Filtering:** Rather than calling `world.getCachedChunk`, candidates are grouped by $32 \times 32$ chunk macro-bins (`RegionFileCoord`) and inspected off-tick via `AnvilRegionByteCache`. Surface heightmaps, ocean/river flags, and biome masks are validated directly from disk without loading chunks into the server runtime (S-005).
- **Mutual Elevation Clustering:** With surface heights pre-screened in Stage 1, the engine calculates the median surface elevation across candidate nodes, pruning outliers before live chunk loading. This prevents anchor pinning at valley floors or canyon rims from discarding valid plateaus.

### 2. Bin-Memory Propagation & Finite-Bin Selection

Because a typical subspace footprint (e.g. radius $R = 32\text{–}128$ blocks) is spatially compact relative to a $512 \times 512$ block ($32 \times 32$ chunk) Anvil region file, any subspace intersects a finite, bounded set of macro-bins—typically only **1, 2, or at most 4** distinct `RegionFileCoord` bins.

Subspace candidate compute leverages this finite bin intersection through a dual-path strategy:

- **Path A — Bin Memory Propagation (Known / Populated Bins):**
  When the subspace footprint intersects bins already tracked in `WorldBacklogBinIndex` (ADR-028) or parent `MemoryShape` bin tables:
  1. The subspace generator queries existing pre-screened candidate locations from those specific intersecting bins.
  2. Rather than rolling arbitrary coordinates from scratch, the engine selects candidate slots from the known, pre-verified points within the finite bins that satisfy the subspace profile spacing and boundary constraints.
  3. This provides near-instantaneous $O(1)$ candidate discovery by directly reusing the spatial memory of the L3 backlog.

- **Path B — Unexplored Bin Fallback (Fresh / Unpopulated Bins):**
  When the subspace footprint touches a region or `.mca` coordinate that has no active bin entries (e.g. newly generated chunks, unpopulated backlog, or custom off-world dimensions):
  1. The engine treats the area as an unexplored bin.
  2. It falls back to standard candidate generation: synthesizing lattice candidates, inspecting the Anvil/Linear header off-tick via `AnvilRegionByteCache` if on disk, and populating fresh entries without blocking.

### 3. 5-Point Chunk Column Probe Strategy (`TEST_COORDS`)

Every candidate chunk incorporates the 5 canonical probe offsets defined in `AbstractVerticalAdjustor`:
- Center: `(7, 7)`
- Quadrant 1 (South-West): `(2, 2)`
- Quadrant 2 (North-East): `(12, 12)`
- Quadrant 3 (North-West): `(2, 12)`
- Quadrant 4 (South-East): `(12, 2)`

If the primary nominal column $(X_i, Z_i)$ is obstructed by vegetation, surface liquid, or irregular block geometry, the candidate validator iterates through the alternate coordinates within the same chunk before marking the slot invalid. This provides up to a $5\times$ survival multiplier on candidate chunks at zero additional chunk-load cost.

### 4. Bounded Asynchronous Placement Retries (`GroupPlacementDispatcher`)

`GroupProfileSpec` and `GroupPlacementDispatcher` support bounded placement retries (`retries`, clamped $\ge 1$, default $1$ for raw group profiles, default $3$ for scripted actions):
- When `allocate(...)` fails with `INSUFFICIENT_SAFE_SLOTS` (whether due to Stage-1 lattice culling or live verifier rejection):
  1. Any rejected chunks are dynamically recorded into the parent region's `MemoryShape` tagged as `FailTypes.safetyExternal` (ADR-079).
  2. If the current attempt index is less than `retries`, a fresh attempt is asynchronously chained off-tick via `SubspaceAnchorResolver.resolveAnchor(...)`.
  3. Because the prior failure shifted the boundary conditions in `MemoryShape`, subsequent lattice selections immediately prune the newly discovered claim chunks in $O(1)$ (or select a different perimeter edge for `ClaimHazardAnchorSource`).
  4. If any attempt succeeds, it proceeds to ticket handover and teleport dispatch. If all attempts are exhausted, it fails closed cleanly (`INSUFFICIENT_SAFE_SLOTS`).

### 5. Per-Action Pre-Validated Cache Sinks (`ActionHotSink`)

Under ADR-078's composable cache stage architecture:
- Action definitions can specify a small, bounded pre-validated cache (`placement.cacheSize`, default $0$, typically $1\text{–}3$ for stationary actions like `scatter`, `location`, `nearclaim`).
- Pre-validated entries hold prepared `GroupPlacementResult` placements with live `ChunkReservation`s, populated asynchronously in the background via `ActionCacheWarmTask`.
- Dynamic entity actions (`nearplayer` targeting a moving player) bypass pre-validated caching and run on-demand with bounded retries.

### 6. Mandatory Pre-Flight Revalidation Prior to Placement (S-001, S-003)

Because world state can change while a candidate sits in cache (e.g., players build claims, place hazardous blocks, or ignite fires):
- Upon cache hit, before any participant is dispatched, the engine performs a non-blocking **Pre-Flight Recheck**:
  1. **Resident Block Recheck:** Evaluates column standability against the already-resident chunk via `region.candidateValidator()`.
  2. **External Claim Recheck (S-003):** Re-evaluates `GlobalRegionVerifiers.checkGlobalRegionVerifiers(dest)`.
- If revalidation fails:
  - The entry is immediately disposed and its chunk reservation closed (S-002).
  - The failed chunk is tagged into `MemoryShape` as `FailTypes.safetyExternal`.
  - The engine pops the next cached entry or falls back to live bounded-retry placement.

---

## Safety & Invariant Guarantees

- **S-001 & S-003 (No Unsafe Destinations / No Claim Incursions):** Pre-flight revalidation guarantees that cached and freshly prepared entries are verified against live world blocks and external claims at dispatch time.
- **S-002 (No Leaked Chunk Tickets):** All retry discard paths, cache evictions, excess over-provisioned slots, and failed revalidations immediately release their reservations.
- **S-004 (Fail-Closed Contract):** If all retries are exhausted or all cached entries are invalid, the action fails closed with an explicit error reason.
- **S-005 (Zero Main-Thread Chunk I/O):** Stage 1 Anvil pre-screening, retries, anchor re-draws, and pre-flight rechecks execute asynchronously off-tick.
