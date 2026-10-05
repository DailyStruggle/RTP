# ADR-095 - Subspace Anchor Providers, Near-Teleport Primitives, and External Verifier Perimeter Extraction

**Status:** Accepted (2026-09-22). Record reconstructed 2026-10-05 from the shipped implementation; the original file was never committed.
**Date:** 2026-09-22
**Extends:** [ADR-093](ADR-093-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md) (Declarative Scripted Actions and Subspace Placement)
**Related:** [ADR-079](ADR-079-cause-based-ttl-and-staged-expiration.md) (Cause-Based TTL and Spatial Memory Learning), [ADR-097](ADR-097-per-action-pre-validated-caches-and-bounded-retries.md) (Per-Action Caches and Bounded Retries), [ADR-069](ADR-069-claim-integrations-extracted-to-bundled-addon.md) (Claim Addon), S-003, S-004, S-005

## Context

ADR-093 places a group of participants into safe slots around one anchor coordinate `(X0, Z0)`. Its MVP drew that anchor only from the region's pre-warmed queue. Competitor "near" teleports (`nearplayer`, near a claim, near a landmark) need other anchor origins, but they must keep the core guarantees: no main-thread chunk I/O (S-005), no landing inside protected land (S-003), and no silent failure (S-004).

A claim-adjacent anchor also needs a claim position. Inline claim-plugin calls in the pipeline are prohibited (S-003 common wrong move), but spatial memory already records every external-verifier rejection under the `safetyExternal` fail cause (ADR-079).

## Decision

1. **`AnchorSource` SPI (`rtp-api`, `io.github.dailystruggle.rtp.api.group`).** A `@PublicApi` functional interface returning `CompletableFuture<RTPCoords>`; resolution never blocks the main thread. Built-in factories:

   | Factory | Origin |
   |---|---|
   | `regionQueue()` (default) | One verified location from the region's shared kept/hot queue. |
   | `fixed(coords)` | A configured landmark coordinate. |
   | `entity(supplier)` | A live entity / player position. |
   | `claimHazard()` | A claim perimeter recalled from spatial memory (item 4). |
   | `claimBoundary(boundary)` | A `ClaimBoundary` supplied by a claim integration. |

2. **Resolution (`rtp-core`, `SubspaceAnchorResolver.resolveAnchor`).**
   - `regionQueue` polls `RegionQueueManager.poll(null)` (no owning player; `poll` shall not throw on a `null` uuid) and falls back to on-demand `Region.getLocation` when the queue is empty.
   - `claimBoundary` resolves through `ClaimAnchoredRegionTracker` (centre preservation) and ingests the region's known hazard memory inside the boundary.
   - Other sources resolve asynchronously; a missing world name inherits the region's world.
   - Every null or exceptional result is logged and surfaces as a placement failure (`NO_ANCHOR`), never a stalled future (S-004).

3. **YAML selector (`placement.anchor`, `ActionManager.resolveAnchorSource`).**

   | Value(s) | Source | When unresolved |
   |---|---|---|
   | `regionQueue` (default, and any unknown value incl. `scatter`) | `regionQueue()` | on-demand generation |
   | `fixed`, `location`, `landmark` | `fixed` from `anchorX/Y/Z/World` (context metadata first, then placement parameters) | `regionQueue()` |
   | `entity`, `player`, `nearplayer` | `entity`; with no coordinates, a random online non-participant | fails closed (no silent random fallback) |
   | `claim`, `claimhazard`, `nearclaim` | `claimHazard()` | `NO_ANCHOR` |
   | `claimboundary`, `faction` | `claimBoundary` via context metadata or `RTPAPI.hooks.claimBoundaries()` (namespace `factions` for `faction`, overridable by `namespace`) | `claimHazard()` |

4. **External verifier perimeter extraction.** `SubspaceAnchorResolver.resolveClaimPerimeter` scans the parent `MemoryShape`'s bad-run snapshot (`badKeysSnapshot` / `badCausesSnapshot`) for a `safetyExternal` run and anchors at that chunk's centre. Core thus learns claim positions from verifier rejections without calling claim plugins; slot validation still runs every global verifier, so S-003 holds for each landing slot.

5. **Near-teleport primitives are actions, not commands.** `nearplayer`, `nearclaim` and `location` ship as bundled sample definitions (`definitions/actions/*.yml`) on the ADR-093 engine; no dedicated command or pipeline exists for them.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Dedicated `/rtp near*` commands with their own pipelines | Duplicates queueing, validation and confinement; ADR-093 is capability, not catalogue. |
| Query claim plugins for perimeters inside core | Violates the S-003 boundary; claim integrations live in the bundled claim addon (ADR-069). |
| Fall back to a random region anchor when `nearplayer` has no target | Silently changes the action's meaning; fail closed instead (S-004). |

## Consequences

- **Positive:** One placement engine serves standard group, near-player, near-claim and landmark actions; addons can supply custom `AnchorSource` implementations; claim anchoring needs no claim-plugin calls in core.
- **Negative / Trade-offs:**
  - `claimHazard` returns the first remembered `safetyExternal` run, so it needs prior rejections in that region and is not uniformly distributed across claims.
  - `scatter` is an alias of the region-queue anchor, not a separate world-wide sampler; `docs/admin/ACTIONS.md` should describe it that way.

## References

- `rtp-api`: `AnchorSource`, `GroupPlacementRequest`, `GroupProfileSpec`, `ClaimBoundary`, `ClaimBoundaryRegistry`.
- `rtp-core`: `SubspaceAnchorResolver`, `ActionManager.resolveAnchorSource`, `ClaimAnchoredRegionTracker`, `GroupPlacementDispatcher`.
- Tests: `ActionManagerTest#testAnchorSourceSelection`, `SampleActionE2ETest`, `SampleActionPlacementChartTest`.
- [`docs/architecture/14-group-placement-anchor-flow.md`](../architecture/14-group-placement-anchor-flow.md), [`docs/admin/ACTIONS.md`](../admin/ACTIONS.md).
