# ADR-001 — Archimedean Spiral 1D Mapping for Location Selection

**Status:** Accepted
**Date:** 2021-08-01

## Context

Traditional random teleport plugins select candidate locations by independently rolling random X and Z coordinates within a bounding box or circle, then checking validity (safe block, correct biome, not in a claimed region). If the candidate is invalid, they reroll. This approach has two fundamental failure modes:

1. **Non-deterministic execution time.** As the fraction of invalid space grows (oceans, protected regions, biome filters), the expected number of rerolls grows without bound. In the worst case — a world that is mostly ocean with a small biome whitelist — the plugin can loop indefinitely, stalling the server thread or timing out.

2. **Distribution skew (clustering).** Naive 2D uniform sampling inside a circle produces a non-uniform spatial distribution: points cluster toward the centre because the area of an annular ring grows with radius. Correcting this with `sqrt(random())` scaling is non-obvious and rarely implemented correctly.

The standard teleport region shape is a **donut** (annulus): a circle with a minimum and maximum radius, centred on a world origin. The goal was to find a selection algorithm that:
- Eliminates unbounded rerolling entirely
- Guarantees uniform spatial distribution across the annulus
- Supports memory-efficient tracking of known-invalid sub-regions so they are never re-evaluated

## Decision

Map the 2D annular teleport region bijectively onto a 1D curve using an **Archimedean spiral**, then scan that curve by segment index rather than sampling random 2D coordinates.

The Archimedean spiral `r = a + bθ` naturally tiles an annulus with uniform arc-length spacing. By discretising the spiral into equal-length segments and assigning each segment a sequential integer index, the entire candidate space becomes a 1D integer range `[0, N)`. A candidate location is selected by choosing an index from this range, converting it back to polar coordinates via the inverse spiral formula, and then to world coordinates.

Invalid segments (known bad sectors — ocean, claimed land, out-of-bounds biome) are tracked as a sorted set of excluded index ranges. Selecting the next valid candidate becomes a subtraction problem on integer intervals: O(log n) in the number of excluded ranges, with no rerolling.

This approach was derived from and validated by the mathematical proof authored and published by the plugin's sole developer at:
https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/

## Consequences

- **Positive:**
  - Location selection executes in strictly bounded O(log n) time regardless of how much of the region is invalid.
  - Uniform spatial distribution across the annulus is guaranteed by the arc-length parameterisation of the spiral.
  - The 1D index representation allows the bad-sector memory (`MemoryShape`) to be stored as a compact sorted list of integer intervals, making persistence (database serialisation) and lookup both efficient.
  - The same 1D mapping generalises to SQUARE and RECTANGLE shapes by substituting the appropriate curve parameterisation.

- **Negative / Trade-offs:**
  - The inverse spiral formula (converting a 1D index back to polar coordinates) is non-trivial to implement and verify. Errors in this formula produce subtle distribution bugs that are hard to detect visually.
  - The segment granularity (number of discrete steps N) shall be chosen carefully: too coarse and the distribution has visible gaps; too fine and the index range overflows or the bad-sector set becomes large.
  - The algorithm is novel enough that new contributors are unlikely to be familiar with it, increasing onboarding cost. This ADR and the `DESIGN.md` §3 entry exist specifically to address that.

## References

- Original mathematical proof authored and published by the plugin's sole developer: https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/
- Implementing classes: `MemoryShape`, `Circle`, `Square` (`rtp-core`)
- Design reference: [`DESIGN.md` §3 — Deterministic Spatial Algorithms](../dev/DESIGN.md)
- Requirements: `REQ-RTP-F-005`, `REQ-RTP-F-006`, `REQ-RTP-F-007`, `REQ-CORE-F-003`, `REQ-CORE-F-004`, `REQ-CORE-F-005`
- Tests: `MemoryShapeTest`
