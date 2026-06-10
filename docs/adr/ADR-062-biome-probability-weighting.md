# ADR-062 — Biome-probability weighting for location selection

**Status:** Accepted
**Date:** 2026-06-10

## Context

A competitor (EzRTP) advertises "rare-biome optimization (weighted search + hotspot tracking)" - actively steering teleports toward scarce biomes (mushroom fields, cherry grove, ...) rather than rerolling until one happens to appear. During a feature-gap comparison this surfaced as something LeafRTP does not do.

Two distinct capabilities must not be conflated:

- **Bad-sector avoidance** (already shipped): the persistent spatial memory ([ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) spiral + the `.scan` bad-location bitmap) records sectors that failed safety checks so the spiral selector skips known-bad ground. This is *avoidance* - it shrinks the candidate space away from unsafe coordinates.
- **Biome steering** (not shipped): biasing selection *toward* a requested or rare target biome. This is the EzRTP feature.

The accuracy footing matters here and is a genuine LeafRTP advantage. Plugins that resolve biome via the live generator/noise-map lookup (`World#getBiome` without forcing a populated chunk) can return the *wrong* biome on a world pregenerated elsewhere or migrated across a Minecraft version, where Mojang's seed-based biome assignment has drifted from what is written to disk. LeafRTP already reads biome data from the populated `.mca` palette through the off-tick Anvil pre-filter ([ADR-016](ADR-016-anvil-subsystem.md)), so any weighting built on top of LeafRTP's biome data stays authoritative on exactly the worlds where the noise-map approach is wrong.

`/rtp biome:<x>` already exists as a hard filter (reject candidates whose biome != target). What is missing is a *soft, weighted* draw that biases the selection distribution toward one or more biomes without an unbounded reroll loop, and the per-region biome occupancy data needed to make that draw cheap.

## Decision

*Proposed - D-005 gated, awaiting approval before implementation.*

Add an optional, per-region biome-probability weighting layer to location selection:

1. **Biome occupancy map.** Extend the existing per-region spatial memory to record, per scanned sector, which biome(s) that sector yielded (sourced from the Anvil palette read that already happens during `/rtp scan` and pipeline verification - no new chunk loads). This turns spatial memory from a bad/good bitmap into a biome-tagged sector map.
2. **Weighted draw.** When a region defines biome weights (config) or a caller requests biome steering, draw the next spiral candidate sector from the occupancy map weighted by the configured biome probabilities, rather than uniformly. Selection stays bounded (a weighted pick over a finite, pre-mapped set) - no unbounded reroll.
3. **Graceful fallback.** With no occupancy data yet (cold region) or no biome weights configured, behavior is identical to today's uniform bounded spiral. Steering only activates once `/rtp scan` (or organic traffic) has populated enough of the occupancy map.

Bounded-algorithm and S-005 invariants are preserved: the draw operates over pre-mapped, Anvil-sourced sectors and never triggers a synchronous chunk load to *discover* a biome.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Do nothing; rely on the existing `/rtp biome:<x>` hard filter | Hard filtering can degrade to many rerolls when the target biome is rare, which is exactly the bounded-latency problem LeafRTP exists to avoid. A weighted draw over pre-mapped sectors stays bounded. |
| Copy EzRTP's live `World#getBiome` weighted search | Inaccurate on pregenerated / version-migrated worlds (noise-map drift), and re-introduces on-demand biome lookups. Contradicts the Anvil-first accuracy guarantee and risks S-005. |
| Add named "triangle"/"diamond" shapes and other surface-level EzRTP parity items in the same change | Out of scope and declined separately - a triangle is a 3-vertex `Polygon` and a diamond a rotated square; no new capability. |

## Consequences

- **Positive:** Matches the competitor's rare-biome feature while staying bounded and accurate; turns existing spatial memory into a richer biome-tagged map reusable by visualizations; the marketing story ("biome targeting that's correct on pregenerated and upgraded worlds, where noise-map plugins land you in the wrong biome") is defensible because it rests on the existing Anvil read.
- **Negative / Trade-offs:** Larger per-region spatial-memory footprint (biome tag per sector vs a single bad/good bit); occupancy map must be populated (scan or traffic) before steering is useful; new config surface (per-region biome weights) needs locale-parity and menu plumbing.

## References

- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) - bounded Archimedean-spiral selection.
- [ADR-016](ADR-016-anvil-subsystem.md) - Anvil `.mca` pre-filter (biome source of truth).
- [ADR-034](ADR-034-memory-shape-catalog.md) - memory-shape catalog (where per-region weighting params live).
- `rtp-core/.../commands/RTPCmd.java` - existing `/rtp biome:<x>` hard-filter path.
