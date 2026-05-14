# ADR-034 — Memory Shape Catalog and Polygon Shape

**Status:** Accepted
**Date:** 2026-05-11 (Proposed); 2026-05-12 (Accepted, implementation landed)

## Context

RTP's `MemoryShape` family (`rtp-core/.../selection/region/selectors/memory/shapes/`) provides the geometric bounding for every region the plugin samples from. Over the project's history a small set of shapes has accreted — `Circle`, `Circle_Normal`, `Square`, `Square_Normal`, `Rectangle`, `Ellipse` — each driven by `GenericMemoryShapeParams` (radii, center, weight, `uniquePlacements`, `expand`, `mode`) plus shape-specific extensions (`RectangleParams`, `NormalDistributionParams`).

There has never been a single document that catalogs these shapes, the parameters they accept, and the design constraints they share. As of this ADR a new shape — `Polygon` — is proposed, which materially differs from the existing set in that it is bounded by an admin-authored vertex list rather than a closed-form parametric primitive. This ADR cements the existing catalog and records the new shape's design decisions in the same place, so that future additions inherit a consistent contract.

All shapes in this ADR share two underlying mechanisms documented elsewhere:

- The 1D → 2D Archimedean-spiral mapping for bounded, repeatable index-to-coordinate selection ([ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)).
- The segmented bad-location storage on `MemoryShape` (`pendingBadLocations`, `rebuildingBadLocations`, segmented "bad" / "good" run accounting) that compresses the spiral's index space when columns are excluded, enabling the `expand` semantics and the cache-key model in [ADR-022](ADR-022-shape-cache-key-seed-plus-config-hash.md).

## Decision

Adopt the following catalog of memory shapes. New shapes added after this ADR must be appended (with their parameter surface, distribution, and any deviations from the shared contract) rather than retrofitted.

### Shared contract (applies to every shape below unless overridden)

- Backed by `MemoryShape`; sampling is index-driven via the Archimedean spiral mapping ([ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)).
- Bad-locations are stored in the segmented store on `MemoryShape`; runtime discoveries and pre-seeded geometric masks share the same structure.
- `expand=true` is permitted only for shapes whose outer extent is parametric (a single radius/half-extent the system can grow). Shapes with a fixed admin-authored boundary must force `expand=false`.
- All shapes are deterministic given a seed plus a canonical config hash ([ADR-022](ADR-022-shape-cache-key-seed-plus-config-hash.md)).
- All sampling work obeys S-005 (no synchronous chunk I/O on the main thread); pre-seeding work happens on async schedulers.

### Catalog

1. **`Circle`** — Uniform-by-area disk.
   - Params: `radius`, `centerRadius` (optional hollow center → annulus), `centerX`, `centerZ`, `weight`, `uniquePlacements`, `expand`, `mode`.
   - Distribution: flat over the disk (or annulus when `centerRadius > 0`).
   - Notes: the existing hollow-circle / annulus use case is covered here by `centerRadius`; no separate `Annulus` shape exists.

2. **`Circle_Normal`** — Gaussian-radial variant of `Circle`.
   - Params: `Circle`'s set plus `NormalDistributionParams` (sigma, etc.).
   - Distribution: rotationally symmetric, density falls off radially per the configured normal distribution ([ADR-009](ADR-009-configurable-spatial-distributions.md)).
   - Notes: shares all geometric concerns with `Circle`; only the index-to-radius transform differs.

3. **`Square`** — Uniform-by-area axis-aligned square.
   - Params: `radius` (treated as half-extent in both axes), `centerRadius` (optional hollow center → square frame), `centerX`, `centerZ`, `weight`, `uniquePlacements`, `expand`, `mode`.
   - Distribution: flat over the square (or square-frame when `centerRadius > 0`).
   - Notes: the hollow-square / square-frame use case is covered here by `centerRadius`; no separate `HollowSquare` shape exists.

4. **`Square_Normal`** — Gaussian variant of `Square`.
   - Params: `Square`'s set plus `NormalDistributionParams`.
   - Distribution: per-axis Gaussian within the square ([ADR-009](ADR-009-configurable-spatial-distributions.md)).

5. **`Rectangle`** — Uniform-by-area axis-aligned rectangle with independent half-extents.
   - Params: `RectangleParams` (independent X / Z extents), `centerX`, `centerZ`, `weight`, `uniquePlacements`, `expand`, `mode`.
   - Distribution: flat over the rectangle.
   - Notes: the natural generalization of `Square` when the world is not square-symmetric (e.g. corridor worlds, asymmetric border-aligned regions).

6. **`Ellipse`** — Uniform-by-area axis-aligned ellipse with independent semi-axes.
   - Params: `radius`, `radius2` (the two semi-axes; the wider sets the bounding circle), `centerX`, `centerZ`, `weight`, `uniquePlacements`, `expand`, `mode`.
   - Distribution: flat over the ellipse.
   - Notes: the natural generalization of `Circle` when world geometry is anisotropic.

7. **`Polygon`** *(new — this ADR)* — Admin-authored closed polygon, optionally concave; self-intersection rejected at load.
   - **Inheritance from `Square`.** `Polygon extends Square`, consistent with the established `MemoryShape` hierarchy (`Square_Normal extends Square`, `Circle_Normal extends Circle`, `Ellipse extends Circle`). The inherited `Square` sized to the polygon's axis-aligned bounding box carries the spiral index, the segmented bad-locations store, persistence, uniqueness, cache-key contribution ([ADR-022](ADR-022-shape-cache-key-seed-plus-config-hash.md)), and registry integration unchanged; `Polygon` adds the polygon predicate, the load-time mask population, and an `expand` override.
   - **Params (`PolygonMemoryShapeParams`):** `vertices` (Chunky-compatible list of `(x, z)` pairs), `centerX` / `centerZ` (optional override; defaults to AABB center), `weight`, `uniquePlacements`, `mode`. `expand` is **not** part of the declared param surface; the config layer does not surface it for `Polygon`, and `Polygon` overrides the expansion accessor to hard-return `false`. A warning is logged via `RTP.log(Level.WARNING, …)` if a config explicitly sets `expand=true` on a polygon region.
   - **Vertex format:** Chunky-parity. Admins paste the same vertex list they already use in `/chunky shape polygon`. Stored in YAML as a collection so it round-trips as a single value through the existing config layer.
   - **Validation at load:**
     - Reject polygons with fewer than 3 vertices.
     - Reject self-intersecting polygons. Detection is a naive O(n²) edge-pair check; admin-authored vertex counts make this trivial. The error message names the offending edge pair.
     - Concave polygons are accepted; the polygon predicate (ray cast / winding number) handles them natively.
   - **Bad-locations as the mask storage:** the per-column "outside polygon" mask is stored exclusively in the existing segmented bad-locations structure. No new persistence schema, no shadow data structure, no split between "static geometric" and "dynamic safety" bad-locations.
   - **Async curve-walk at load.** `Polygon.load()` (the standard `MemoryShape.load` extension point) inspects the segmented bad-locations store: if it is empty, an async task is scheduled that walks the inner `Square`'s spiral index, applies the polygon predicate per column, and emits runs of outside-polygon indices as segments into the segmented store. The walker:
     - Yields periodically to avoid starving the async pool.
     - Re-checks the "bad-locations empty" condition each batch and self-cancels the moment any path adds an entry; the walker is therefore preempted by either a prior session's serialized mask or by real runtime discoveries from the safety/biome path.
     - On Folia uses `RTP.scheduler.runTaskTimerAsynchronously`, consistent with the database-processing pattern.
   - **Warmup-window sampling.** While the walker is still running, the sampling path applies the polygon predicate as a tie-breaker after consulting the segmented store. As the walker fills the store, the predicate path is exercised less and eventually becomes redundant; once the walker completes, the segmented store alone is authoritative.
   - **Degenerate cases:**
     - Polygon whose vertex set is collinear → rejected at load.
     - Polygon whose area equals its AABB (i.e. it *is* a rectangle) → load-time detection demotes to the inner `Square` and skips the walker entirely.
   - **`clear bad-locations` semantics.** Falls out of the inference rule: clearing the segmented store leaves it empty, which on the next `load()`-style check re-spawns the walker. Documented behavior; no special case needed in the admin command.
   - **World-border composition.** Unchanged. Border layering (Chunky-shaped borders) already composes with any `MemoryShape` by masking samples after the shape produces them; `Polygon` requires no new integration there.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| `Polygon` encapsulates a `Square` (has-a) | Breaks the established `MemoryShape` inheritance pattern (`Square_Normal`, `Circle_Normal`, `Ellipse` all extend their primitive). Forces re-plumbing of cache-key, serialization, and registry paths that today key off the shape class, and requires re-exposing every `Square` accessor `MemoryShape`'s machinery touches. The one motivating concern — `expand` — is a configuration hint, not a structural contract; overriding the accessor to return `false` and omitting it from the declared param surface neutralizes it without a wrapper. |
| Eager synchronous mask population at load | Blocks region load on O(perimeter × height) work; visible to admins on large regions. Async walker amortizes the cost across the warmup window with no UX hit. |
| New persistent schema for the polygon mask | Redundant: the segmented bad-locations store already serializes ([ADR-002](ADR-002-h2-sqlite-over-flat-file-cache.md), `MemoryShape` serialization). The async walker reproduces the mask from vertices in one shot on first load. |
| Static-vs-dynamic split inside the segmented bad-locations store | Doubles the data structure and complicates every reader for a problem the inference rule (walker keyed on store-empty) solves at zero cost. |
| Per-sample polygon predicate without any pre-seeded mask | Per-sample O(vertices) cost forever. The mask-once approach pays it once at warmup and then degenerates to a single segment lookup per sample. |
| Convex-only polygons | Rejects the most common admin use case (drawing a continent outline, which is concave). Point-in-polygon handles concave shapes natively with no extra cost. |
| Self-intersecting polygons allowed | Point-in-polygon is undefined for self-intersecting shapes; results would be admin-confusing and seed-nondeterministic. Reject at load with a clear error. |
| RTP-native vertex format (not Chunky-compatible) | Forces admins who already authored a shape in Chunky to retype it. Chunky parity is a free interoperability win. |
| New `Annulus` / `HollowSquare` shape classes | The existing `Circle.centerRadius` / `Square.centerRadius` already cover the hollow use case; a separate class would duplicate the spiral mapping for no expressive gain. |

## Consequences

- **Positive:**
  - One canonical place documents every memory shape, including parameter surface and distribution. New shapes append here.
  - `Polygon` reuses every existing `MemoryShape` mechanism — segmented bad-locations, spiral indexing, cache-key, serialization, uniqueness, world-border layering — with no new persistence schema, no new admin command, and no changes outside `rtp-core/.../shapes/`.
  - Admin authoring is one paste from Chunky. The most-requested expressive use case (continent / island shapes, irregular survival zones) is unlocked without ad-hoc claim-plugin coupling (S-003).
  - Inheriting from `Square` keeps `Polygon` consistent with the rest of the `MemoryShape` hierarchy and slots into the existing cache-key, serialization, and registry paths without new plumbing; future predicate-bounded shapes can follow the same pattern (extend the closest primitive, add a predicate, mask via bad-locations, override any inapplicable knob).

- **Negative / Trade-offs:**
  - Warmup window: until the async walker completes, each sample pays an O(vertices) predicate cost. Acceptable at admin-authored vertex counts (tens, not thousands) and self-limiting as the segmented store fills.
  - The polygon mask is rebuilt on every `clear bad-locations` because the walker is keyed on store-emptiness. This is cheap and predictable but worth documenting in the admin command's help text.
  - Self-intersection detection is O(n²); fine for hand-authored shapes but would need replacing with Bentley–Ottmann if a future automated importer produced large vertex sets.
  - Walker cancellation when real runtime discoveries beat it means the mask may be partial in heavily-trafficked regions. The per-sample predicate continues to guarantee correctness; the only effect is that mask compression of the spiral index is incomplete, which is benign.

## References

- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) — Archimedean spiral 1D → 2D mapping (selection backbone for every shape above).
- [ADR-009](ADR-009-configurable-spatial-distributions.md) — Configurable spatial distributions (flat, normal, exponential); underlies `Circle_Normal` and `Square_Normal`.
- [ADR-016](ADR-016-anvil-subsystem.md) — Anvil pre-filter; composes with the polygon mask on the sampling path.
- [ADR-022](ADR-022-shape-cache-key-seed-plus-config-hash.md) — Region shape cache key model (seed + canonical config hash); applies to `Polygon` unchanged.
- [ADR-028](ADR-028-l3-backlog-cache.md) — L3 backlog cache; composes with `Polygon` because the mask is carried in the same segmented bad-locations structure the backlog consults.
- Code: `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/selectors/memory/shapes/` (`MemoryShape`, `Circle`, `Circle_Normal`, `Square`, `Square_Normal`, `Rectangle`, `Ellipse`, and the proposed `Polygon`).
- Params: `GenericMemoryShapeParams`, `NormalDistributionParams`, `RectangleParams`, and the proposed `PolygonMemoryShapeParams` (sibling enum carrying the `vertices` collection).
- External: [Chunky polygon shape](https://github.com/pop4959/Chunky) — vertex-list format mirrored for admin parity.
