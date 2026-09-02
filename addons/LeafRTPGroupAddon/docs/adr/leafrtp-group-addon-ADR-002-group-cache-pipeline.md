# leafrtp-group-addon-ADR-002 - Group Placement Cache Pipeline (Group L3/L2/L1) and Unit-Scaled Shape-Lattice Selector

**Status:** Accepted
**Date:** 2026-08-28
**Refines:** [`leafrtp-group-addon-ADR-001`](leafrtp-group-addon-ADR-001-subspace-group-teleport.md) (Stage 2 selection mechanics)

## Context

ADR-001 established the `SubspaceShape` primitive and a two-stage (chunk pre-filter + block bin)
selection, screened by the shared `CandidateValidator` obtained from `Region.candidateValidator()`.
That validator is deliberately non-blocking: it reads only **resident** chunks and fails closed for
any non-resident column (S-005; core forbids blocking on load futures).

Two problems surface when this is used as the sole basis for group placement:

1. **Validation coverage depends on incidental residency.** With only the resident-chunk validator,
   a subspace can validate just the columns whose chunks happen to be loaded (the anchor's
   reservation neighbourhood). A real `N x N` footprint under-fills - not for safety reasons, but
   because most cells are simply not resident. The region-file (`.mca`/`.linear`) off-tick read is
   therefore not an optimization; it is the mechanism that actually *produces* validated coordinates
   for a group, mirroring the anvil prefilter (ADR-016/077).
2. **Groups need prepared, chunk-resident destinations.** A group can only be *placed* once its
   destination chunks are resident and ticketed. Borrowing single-placement caches (which cache one
   `RTPLocation`) does not prepare a *group-capable subspace*. Group placement needs its own tiered
   cache with its own chunk-ticket lifecycle, exactly as single placement has L3/L2/L1.

Additionally, ADR-001's Stage 2 sampled the footprint on a `minSeparation/2` grid and ran a greedy
O(n^2) separation dedup. This is replaced (see Decision 1).

## Decision

### 1. Unit-scaled, shape-masked lattice selector (refines ADR-001 Stage 2)
The oversample+greedy Stage 2 and the `BlockValidator` test seam are removed. Selection becomes:
* **Unit = placement distance.** `d = max(1, minSeparation)`; lattice cell `(i, j)` maps to world
  column `anchor + (i*d, j*d)`, clamped to the `(2*subspaceChunkRadius+1)^2` chunk footprint.
  Separation is guaranteed by construction (no greedy dedup).
* **Shape as a mask.** A cell is a candidate iff `shape.contains(i, j)`, where `shape` is a
  registered `Shape` cloned and **fully parameterized from the group preset** (missing params are a
  fail-closed config error, never silently defaulted). `null` = full square lattice. Square is the
  base; circle/ring/etc. are masks over it.
* **Arithmetic capacity pre-check.** `availableUpperBound = latticeCells - knownBadChunkCells`;
  below `memberCount` denies fail-closed (`INSUFFICIENT_SAFE_SLOTS`) with no column work. This is a
  chunk-granular **upper bound**: per-cell validation still confirms every used slot (S-004).
* **Single unique-cell pass.** Each cell is validated at most once; landing `Y` is resolved by the
  region `VerticalAdjustor`; a cell is kept iff `|Y - anchorY| <= elevationTolerance`. Seeded-spread
  order so early picks do not cluster before the dirty cache is computed.

### 2. Group placement cache pipeline (group L3/L2/L1)
Introduce a group-scoped cache parallel to the single-placement `RegionQueueManager`, whose unit of
caching is a **group-capable subspace**, not a single `RTPLocation`:

* **Group L3 (backlog / unverified).** FIFO of candidate anchors/subspaces, screened one `.mca`
  region-file bin per pulse off-tick. Reuses the anvil `.mca`/`.linear` reader and `AnvilIoPool`
  (ADR-016/077); per bin it runs the shape lattice's column scans (multiple columns per file) -
  adding column compute, **not** extra file I/O, since MCA compute is already binned per file. Not
  persisted (as with single-placement L3, ADR-028).
* **Group L2 (cold / pre-verified).** Subspaces whose lattice cells passed column validation, with
  chunk tickets released; re-loaded on promotion to L1.
* **Group L1 (kept / hot).** Subspaces with `keep(true)` loaded footprint chunks, ready for an
  immediate group teleport. This is the tier `/rtp group` polls.

Chunk tickets for the whole footprint are tracked in `MemoryTracker` and released on all exit paths
(S-002, no double-release), exactly as single placement tracks its tickets.

### 3. Dispatcher integration
`GroupPlacementDispatcher` changes from "draw one anchor + validate live" to "poll a prepared
group-capable subspace from group L1 (fallback: promote L2 / screen L3), then run the per-slot async
global-verifier stage and chunk-binned teleport dispatch." The public `GroupPlacementService`
(rtp-api) contract is unchanged.

## Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| **Resident-only validation (no group cache)** | Validation coverage is accidental (only loaded chunks); large footprints under-fill for non-safety reasons. |
| **Warm live chunks per request, then validate** | Forces real chunk loads/ticket churn per request and drags Folia region threads; no reuse across requests. |
| **Reuse single-placement L3/L2/L1 directly** | Those cache single `RTPLocation`s, not group-capable subspaces; cannot express footprint residency or capacity. |

## Consequences

* **Positive:**
  * Validation is produced off-tick from region files (anvil reuse), independent of incidental
    residency; groups fill deterministically up to lattice capacity.
  * Group destinations are prepared and chunk-resident before a teleport, amortizing cost across
    requests like single placement does.
  * Shape-driven layouts (square/circle/ring/...) with no enum switch; operators can add shapes.
* **Negative / Trade-offs:**
  * A parallel multi-tier cache with its own chunk-ticket lifecycle is real added surface area and
    must honor S-002/S-004/S-005 on every path.
  * Group L1 holds `keep(true)` footprint chunks, so a warm group reserve has a higher resident-chunk
    footprint than a single-placement reserve; sizing must be operator-tunable.

## References

* [`leafrtp-group-addon-ADR-001`](leafrtp-group-addon-ADR-001-subspace-group-teleport.md) - subspace primitive this refines.
* [ADR-015](../../../../docs/adr/ADR-015-stale-chunk-guard-countbound-pipes.md) - count-bound pipes / stale-chunk guard.
* [ADR-016](../../../../docs/adr/ADR-016-anvil-subsystem.md) - anvil off-tick `.mca`/`.linear` reader + `AnvilIoPool` reused by group L3.
* [ADR-028](../../../../docs/adr/ADR-028-l3-backlog-cache.md) - single-placement L3 backlog cache this mirrors.
* [ADR-077](../../../../docs/adr/ADR-077-multi-format-region-support.md) - multi-format region file support.
* [`REQUIREMENTS.md`](../../REQUIREMENTS.md) - subproject requirements.
