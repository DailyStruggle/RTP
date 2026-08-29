# PROPOSAL: `GroupPlacementDispatcher` - Phase 3.1 orchestration (rtp-core)

Status: DRAFT - AWAITING APPROVAL (Rule D-005)
Date: 2026-08-27
Scope: `rtp-core` (new `GroupPlacementDispatcher` + minimal seams), consumed by
`LeafRTPGroupAddon` Phase 3.2 commands. Builds on
[`PROPOSAL-candidate-validator-extraction.md`](PROPOSAL-candidate-validator-extraction.md)
(Phase 3.0, APPROVED - IMPLEMENTED) and
[`leafrtp-group-addon-ADR-001`](../../addons/LeafRTPGroupAddon/docs/adr/leafrtp-group-addon-ADR-001-subspace-group-teleport.md).

---

## 1. Audit summary (current state, pre-Phase-3.1)

Verified in-tree today:

- **`SubspaceShape`** (`rtp-core`): Stage 1 chunk-granularity `MemoryShape` pre-filter over the
  `NxN` footprint (`subspaceChunkRadius`), Stage 2 block-bin selection, fail-closed capacity.
  Has both `selectSafeSlots(..., BlockValidator)` (test seam) and
  `selectSafeSlots(..., CandidateValidator)` (production) overloads.
- **`GroupPlacementEngine`** (`addons/LeafRTPGroupAddon`): three `allocate(...)` overloads -
  `BlockValidator` (test), no-arg production (`parentRegion.candidateValidator()`), and explicit
  `CandidateValidator`. Returns `SubspaceAllocationResult` (SUCCESS / typed fail-closed status:
  `INVALID_ANCHOR`, `EXCEEDED_MAX_GROUP_SIZE`, `INSUFFICIENT_SAFE_SLOTS`).
- **`RegionCandidateValidator`** (`rtp-core`): non-blocking `vert.adjustColumn -> SafetyScan.isColumnSafe`,
  reads only resident chunks via `RTPWorld.getCachedChunk`, fails closed otherwise. Deliberately does
  **not** run the claim/global-verifier stage (see below).
- **`VerticalAdjustor.adjustColumn(chunk, lx, lz)`** base default returns `null` (fail-closed).
  **`JumpAdjustor` overrides it** (per-column resolution). **`LinearAdjustor` does NOT override it**
  and therefore inherits the null default - so the group `CandidateValidator` path resolves columns
  **only under a column-capable adjustor**. This is the confirmed Phase 3.1 gap (see section 6).
- **Anchor source**: `Region.getLocation(Set<String> biomeNames)` returns
  `CompletableFuture<GenerationResult>`, draining the L1/L2 queue into a verified anchor carrying a
  live `ChunkReservation` (warm center chunk) - the non-blocking anchor-draw path for the dispatcher.
- **Verifier-stage pattern to mirror**: `QueueTask.runSafetyScan` runs, after its safety verdict,
  `GlobalRegionVerifiers.checkGlobalRegionVerifiers(coords).whenComplete((ok, ex) -> ...)`, rejecting
  fail-closed on `ex != null || !TRUE.equals(ok)` and releasing the reservation.

---

## 2. Affected classes / modules

| Module | Element | Change |
|--------|---------|--------|
| `rtp-core` | `GroupPlacementDispatcher` (new) | Orchestrator: draw anchor -> warm `NxN` footprint off-tick -> build `SubspaceShape` -> capacity/candidate validation via `Region.candidateValidator()` -> per-slot async global-verifier stage -> per-participant Folia-region-scheduled teleports. Fully non-blocking (no `get()/join()`). |
| `rtp-core` | `GroupTeleportRequest` / `GroupTeleportResult` (new, small) | Immutable input (parent region, profile, participant handles, optional live target anchor) and typed outcome (per-participant SUCCESS / fail-closed reason), mirroring `SubspaceAllocationResult` vocabulary. |
| `rtp-core` | `Region` | (Optional, only if needed) expose a narrow warming seam for the bounded footprint, or reuse `RTPWorld.getChunkAt(cx,cz)` + `getCachedChunk`. Prefer no new public surface if `getLocation` + world chunk access suffice. |
| `addons/LeafRTPGroupAddon` | `GroupPlacementEngine` | Unchanged API; the dispatcher calls its `CandidateValidator` overload. Engine stays the pure allocation core; dispatcher owns I/O + scheduling + verifier stage. |
| `docs` | this proposal, `CHECKLIST-group-addon.md`, `leafrtp-group-addon-ADR-001` | Wording updates on approval. |

Rationale for `rtp-core` placement (not the addon): per the issue and prior discussion these
primitives (anchor draw, footprint warming, Folia dispatch, verifier stage) require internal
queue/world/scheduler access that addons must not reach into directly.

---

## 3. Before / after structure

```
[ BEFORE - Phase 3.0 ]
GroupPlacementEngine.allocate(anchor, region, profile, N)  <-- pure, in-memory
   └─ SubspaceShape.selectSafeSlots(N, minSep, region.candidateValidator())
       (CandidateValidator reads resident chunks only; caller must pre-warm)
No production caller wires: anchor draw, footprint warming, verifier stage, or dispatch.

[ AFTER - Phase 3.1 ]
GroupPlacementDispatcher.dispatch(GroupTeleportRequest)  (rtp-core, off-tick, non-blocking)
  1. anchor draw   : region.getLocation(biomes)  -> CF<GenerationResult>  (verified L1/L2 anchor)
                     (pursuit later: live target-entity / claim-boundary anchor)
  2. warm footprint: bounded NxN chunk prefetch via world.getChunkAt(cx,cz) (CF chained,
                     never joined); center already warm from the anchor's ChunkReservation
  3. allocate      : GroupPlacementEngine.allocate(anchor, region, profile, N)
                     -> SubspaceShape + region.candidateValidator()  (safety-verified slots)
  4. verifier stage: per slot, GlobalRegionVerifiers.checkGlobalRegionVerifiers(coords)
                     .whenComplete(...)  (fail-closed on reject/throw; S-003/ADR-026)
  5. dispatch      : per participant, RTP.scheduler -> owning Folia region scheduler
                     (RTPServerAccessor); no cross-region blocking (S-005)
  6. cleanup       : release unused anchor reservations / footprint tickets on partial
                     disconnect/cancel, fail-closed (S-004)
```

---

## 4. Anchor-draw path, footprint warming, dispatch, verifier stage (detail)

- **Anchor draw (non-blocking):** chain off `region.getLocation(biomes)`; on completion use the
  `GenerationResult` location as the subspace anchor and retain its `ChunkReservation` so the center
  chunk stays resident during allocation. No `join()`.
- **Footprint warming (bounded, S-005):** for the `(2*subspaceChunkRadius+1)^2` footprint, request
  each chunk via `world.getChunkAt(cx,cz)` (returns `CompletableFuture`), aggregate with
  `CompletableFuture.allOf(...).thenRun(...)` - never `.get()/.join()`. Only after all are resident
  does the `CandidateValidator` (which reads resident chunks) run, so its fail-closed
  "chunk not resident" branch is not hit during normal operation.
- **Global-verifier stage (per slot, S-003/ADR-026):** replicate `QueueTask`'s post-safety pattern
  exactly - `checkGlobalRegionVerifiers(coords).whenComplete((ok, ex) -> reject-or-accept)`; a throw
  or `false` rejects that slot fail-closed. Aggregate results before committing the group.
- **Folia dispatch (S-005):** for each participant teleport, hop to the destination's owning region
  scheduler via `RTP.scheduler` / `RTPServerAccessor` (as `QueueTask` does with
  `RTP.scheduler.runTask(world, cx, cz, ...)` on Folia; inline elsewhere). No cross-region blocking.
- **Partial failure / cancel (S-004):** if a participant disconnects or the group is cancelled after
  reservations/tickets are taken, release them fail-closed via `MemoryTracker`/`ChunkReservation.close()`
  on every exit path; never silently drop a participant without a typed reason.

---

## 5. Relevant requirements / ADRs

- **S-001**: every slot resolved through the shared `CandidateValidator` (single "safe column").
- **S-003 / ADR-026**: claim/global check applied per slot via `GlobalRegionVerifiers`, not bypassed.
- **S-004**: typed rejections + reservation/ticket release on all partial-failure paths.
- **S-005**: bounded async footprint warming; per-region-scheduler teleport dispatch; no main-thread I/O.
- **`RTPArchitectureTest`**: no `CompletableFuture.get()/join()` in `rtp-core` - the dispatcher chains
  futures only.
- **ADR-015**: reuse count-bound / stale-chunk-guard behavior; do not reintroduce a redundant guard.
- **`leafrtp-group-addon-ADR-001`**: dispatcher realizes the "orchestration" stage of the ADR.

---

## 6. LinearAdjustor.adjustColumn gap - resolution options (decision requested)

`RegionCandidateValidator` requires a column-capable `adjustColumn`. `JumpAdjustor` provides one;
`LinearAdjustor` does not (inherits the fail-closed `null` default). Options:

- **(A) Document + fail-closed (lowest risk, recommended for Phase 3.1):** require group-enabled
  regions to use a column-capable adjustor (e.g. `JumpAdjustor`); under `LinearAdjustor` the group
  path fails closed with a clear `INSUFFICIENT_SAFE_SLOTS`/config-guidance reason. No behavior change
  to the standard `/rtp` path. Defer implementing `LinearAdjustor.adjustColumn` to a follow-up.
- **(B) Implement `LinearAdjustor.adjustColumn`:** add per-column resolution mirroring its
  `adjust(chunk, output)` scan, restricted to the requested `(lx,lz)`. Larger surface, needs its own
  tests; crosses into standard-path adjustor behavior (wider D-005 blast radius).

Recommendation: **(A)** for Phase 3.1, with a `POTENTIAL_BUGS.md`/follow-up note for (B).

---

## 7. Risks & trade-offs

- **Anchor scarcity.** A cold L1/L2 queue yields no anchor; dispatcher must fail closed with a
  retriable "busy" reason (S-007 messaging), not block.
- **Footprint cost.** Large `subspaceChunkRadius` warms many chunks; keep bounded and released after
  use to avoid ticket leaks (mirror `QueueTask` reservation discipline; MemoryTracker).
- **Partial-group atomicity.** Decide whether the group teleports all-or-nothing or best-effort;
  proposal assumes **all-or-nothing after validation** (release everything if any slot fails), which
  is the safest S-004 posture. Confirm in approval.
- **Adjustor dependency.** See section 6.

---

## 8. Open decisions for approval

1. Approve option **(A)** (document + fail-closed under `LinearAdjustor`) vs **(B)** (implement
   `LinearAdjustor.adjustColumn`) for Phase 3.1?
2. All-or-nothing group commit (recommended) vs best-effort partial placement?
3. `GroupPlacementDispatcher` + result types stay `rtp-core`-internal (consumed by the addon via a
   thin entry point), or expose in `rtp-api` now?
4. Reuse `Region.getLocation(biomes)` as the sole anchor-draw path for Phase 3.1 (live
   target-entity/claim anchors for `pursuit` deferred), correct?
