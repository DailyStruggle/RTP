# PROPOSAL: Shared `CandidateValidator` - extract & deduplicate per-candidate location validation

Status: APPROVED - IMPLEMENTED (Rule D-005)
Date: 2026-08-27
Scope: `rtp-core` (+ narrow `rtp-api` seam), consumed by `LeafRTPGroupAddon` Phase 3

---

## 1. Problem

Phase 3 of `LeafRTPGroupAddon` needs to turn a candidate column `(x, z)` into a verified,
standable, claim-safe `RTPLocation`. The current `SubspaceShape.BlockValidator` seam
(`standableY(x, z)`) reinvents logic that already exists - and is already tested and
maintained - in three separate places on the real teleport path:

1. **Standable-Y resolution** - `VerticalAdjustor.adjust(...)` (`LinearAdjustor` /
   `JumpAdjustor`), reachable via `Region.getVert()`. Understands world min/max height,
   adjustor vertical bounds, and off-tick probe data.
2. **Block-clearance safety** - `QueueTask.runSafetyScan` + `RTPChunk.isSafe(x, y, z,
   unsafeBlocks)`. Body/head clearance, configured `unsafeBlocks` (lava/water/magma),
   `safetyRadius` cube scan, and ground-depth re-validation (`platformDepthCache`).
3. **Claim / global checks** - `GlobalRegionVerifiers.checkGlobalRegionVerifiers(coords)`
   (async, fail-safe; ADR-026). Every real path funnels through it: `QueueTask`,
   `PregenTask`, `TeleportPipelineTask`, `ScanTask`.

Leaving `BlockValidator` as a standalone functional interface invites each addon (and the
group addon's production wiring) to grow a *second* definition of "safe column" that will
drift from the canonical one. That is the exact fragmentation this feature set exists to
eliminate, and it is the S-001 / S-003 / S-005 surface most likely to rot.

---

## 2. Affected classes / modules

| Module | Element | Change |
|--------|---------|--------|
| `rtp-core` | `CandidateValidator` (new) | Reservation-agnostic service: given `(worldX, worldZ)`, chain vert -> safety scan -> global verifiers and return a resolved standable `RTPLocation` or a typed rejection. Off-tick, S-005 safe. |
| `rtp-core` | `QueueTask.runSafetyScan` / `evaluateSafety` | Extract the pure safety-scan logic (the `isSafe` cube + head/ground re-check) into a package-visible helper that both `QueueTask` and `CandidateValidator` call. `QueueTask` keeps its reservation/chunk-array orchestration; only the inner block-safety verdict is shared. |
| `rtp-core` | `Region` | Add a thin accessor (e.g. `Region.candidateValidator()`) so addons obtain the validator without touching internal queue/vert fields. |
| `rtp-core` | `SubspaceShape.BlockValidator` | Demote to a **test seam only**. Production `SubspaceShape` consumers pass a `CandidateValidator`-backed adapter; the interface stays for deterministic unit stubs. |
| `addons/LeafRTPGroupAddon` | `GroupPlacementEngine` | Consume `Region.candidateValidator()` instead of an injected ad-hoc `BlockValidator`. |
| (optional) `rtp-api` | `CandidateValidation` result contract | Only if we want the validator addon-facing beyond core; otherwise keep it `rtp-core`-internal and expose via `Region`. |

---

## 3. Before / after structure

```
[ BEFORE ]
QueueTask ── runSafetyScan (private) ── RTPChunk.isSafe + head/ground recheck
          └─ GlobalRegionVerifiers.checkGlobalRegionVerifiers
VerticalAdjustor.adjust ── (called separately upstream)
SubspaceShape ── BlockValidator.standableY  <-- SEPARATE, re-derived safety (drift risk)

[ AFTER ]
CandidateValidator.validate(worldX, worldZ)  (rtp-core, off-tick)
   1. VerticalAdjustor.adjust  -> standable Y (or reject)
   2. shared safety-scan helper -> RTPChunk.isSafe cube + head/ground (or reject)
   3. GlobalRegionVerifiers     -> claim/global async check (or reject)
   => resolved RTPLocation | typed rejection

QueueTask         ─┐
SubspaceShape/Group├─► all call the SAME CandidateValidator / shared safety helper
future addons     ─┘
SubspaceShape.BlockValidator = test double only
```

---

## 4. Relevant requirements / ADRs

- **S-001** (no unsafe destinations): single definition of "safe column", no relaxed path.
- **S-003** (no teleport into claim-protected land): group candidates now flow through
  `GlobalRegionVerifiers` like every other path.
- **S-004** (no silently discarded failures): validator returns *typed* rejections;
  capacity denial in `SubspaceShape` stays fail-closed.
- **S-005** (no main-thread chunk I/O): validator runs off-tick on pre-cached / probe data;
  Folia region-thread hop preserved for live `isSafe` reads (as `evaluateSafety` already does).
- **ADR-026** (external hooks / verifier registry): reuse, do not bypass.
- **ADR-015** (count-bound pipes / stale-chunk guard): shared helper must not reintroduce a
  redundant stale guard; mirror current `QueueTask` behavior.
- **`leafrtp-group-addon-ADR-001`**: update Stage 2 wording to reference the shared
  `CandidateValidator` instead of an addon-local `BlockValidator`.

---

## 5. Risks & trade-offs

- **Extraction coupling.** `runSafetyScan` is entangled with `ChunkReservation` and the
  `localChunks[]` neighbour grid. Mitigation: extract only the *pure* per-column verdict
  (inputs: chunk provider + coords + `unsafeBlocks` + radius/depth), leaving reservation
  orchestration in `QueueTask`. `QueueTask` behavior must be byte-for-byte preserved and
  re-verified by its existing tests.
- **Folia threading.** Live `isSafe` must run on the centre chunk's owning region thread.
  `CandidateValidator` must offer the same region-thread dispatch contract as
  `evaluateSafety`, or document that callers supply already-owned chunk access.
- **Chunk sourcing for the group path.** The subspace footprint is a bounded NxN chunk set;
  the validator needs a chunk provider for neighbours. For the group path the anchor chunk
  is warm (L1/L2) and neighbours are a bounded prefetch set - no unbounded I/O.
- **API exposure.** Deciding `rtp-core`-internal (via `Region`) vs `rtp-api` SPI. Recommend
  starting `rtp-core`-internal to avoid freezing a public contract prematurely; promote to
  `rtp-api` later if a third-party addon needs it.

---

## 6. Implementation checklist (post-approval)

- [x] Extract pure per-column safety verdict from `QueueTask.runSafetyScan` into a shared
      `SafetyScan.isColumnSafe` helper; `QueueTask` delegates to it (behavior-preserving).
- [x] Add `CandidateValidator` (rtp-core) + `RegionCandidateValidator` chaining
      vert (`adjustColumn`) -> shared `SafetyScan` verdict, returning a resolved `RTPLocation`
      or `null`.
- [x] Add `Region.candidateValidator()` accessor.
- [x] Rework `SubspaceShape` to consume a `CandidateValidator` (new overload); keep
      `BlockValidator` as an adapting test seam.
- [x] Wire `GroupPlacementEngine` to `Region.candidateValidator()` (new production overload).
- [x] Update this proposal / ADR / checklist wording.
- [x] Tests: existing `QueueTask`/`SubspaceShapeTest` green; added `CandidateValidator`
      overload tests (success + fail-closed). `RTPArchitectureTest` green.
- [ ] Full multi-module `./gradlew build` (blocked by unrelated jitpack SNAPSHOT 401 on
      `:rtp-plugin`, see Notes below).

## 8. Implementation notes (post-approval)

- **Async constraint discovered.** `RTPArchitectureTest.no_blocking_future_calls_in_core_or_api`
  forbids `CompletableFuture.get()/join()` in `rtp-core`. The validator is therefore fully
  non-blocking: it reads only resident chunks (`RTPWorld.getCachedChunk`) and fails closed if a
  required chunk is not resident (the dispatcher warms the bounded `NxN` footprint first).
- **Claim / global verifiers deferred to the caller.** Because
  `GlobalRegionVerifiers.checkGlobalRegionVerifiers` is inherently async and cannot be awaited in
  core without a forbidden `join()`, that stage is applied by the caller as a separate non-blocking
  stage on each selected slot - exactly as `QueueTask` does after its safety verdict - rather than
  inside `CandidateValidator.validate`. The shared, deduplicated definition is the block-safety
  verdict (`SafetyScan`) plus vertical resolution.

---

## 7. Open decisions for approval

1. Keep `CandidateValidator` `rtp-core`-internal (via `Region`) for now, or expose in
   `rtp-api` immediately?
2. Should `CandidateValidator` own the Folia region-thread hop, or require callers to
   invoke it from the owning region context?
3. Confirm the extraction preserves `QueueTask` semantics exactly (no behavioral change to
   the standard `/rtp` path is in scope for this proposal).
