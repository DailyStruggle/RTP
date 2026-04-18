# ADR-009 — Configurable Spatial Distributions: Flat, Normal, Exponential

**Status:** Accepted
**Date:** 2026-04-15

## Context

When a player requests a random teleport, a radial distance `r` shall be selected from the region centre within the configured `[minRadius, maxRadius]` annulus. This selection is not inherently uniform — different server communities have different goals for *where* players land relative to the inner and outer boundaries of the region.

Three broad placement preferences exist:

1. **Spread players evenly across the region** — the default for survival/exploration servers where independent play is the goal.
2. **Concentrate players toward one edge** — useful when a spawn hub, market, or other central point of interest exists (bias toward the inner edge) or when the outer boundary is the point of interest, e.g. a wilderness border (bias toward the outer edge). The direction and strength of the bias are controlled by the exponent: an exponent < 1 (e.g. 0.5) expands probability toward the outer edge; an exponent > 1 (e.g. 2) contracts probability toward the inner edge.
3. **Concentrate players toward the middle of the annulus** — a bell-curve placement that avoids both the innermost and outermost extremes.

A single fixed uniform distribution cannot satisfy use cases 2 and 3 without operators resorting to multiple overlapping regions with different radii.

## Decision

The system shall expose three named probability distributions for radial distance selection, selectable per-region via configuration:

| Distribution | Placement behaviour |
|---|---|
| `Flat` | Uniform probability across `[minRadius, maxRadius]`; every sector equally likely. |
| `Exponential` | Applies a power-law transform to radial distance; the exponent controls bias direction and strength. Exponent < 1 (e.g. 0.5) biases toward the outer edge; exponent > 1 (e.g. 2) biases toward the inner edge. |
| `Normal` | Bell-curve probability centred on the midpoint of the annulus; players cluster toward the middle, away from both edges. |

`Flat` serves as the baseline uniform distribution.

The `Normal` distribution achieves parity with legacy plugins (e.g., JakesRTP) and satisfies operators who want a "natural-feeling" distribution that avoids hard edges.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Single fixed uniform distribution | Cannot satisfy operators who need centre-weighted or mid-weighted placement without workarounds. |
| Arbitrary user-supplied distribution function | Maximum flexibility but no reasonable configuration surface for non-technical operators; excessive implementation complexity. |
| Poisson disk / blue-noise sampling | Good for even coverage but not a probability distribution over a 1D radial axis; incompatible with the Archimedean spiral 1D mapping (see ADR-001). |
| Additional named distributions (e.g. power-law, triangular) | The three chosen distributions cover all three qualitatively distinct placement goals — inner-edge bias, outer-edge bias, and mid-annulus bias — making additional distributions redundant for practical server configurations. |

## Consequences

- **Positive:** Operators can match teleport scatter to their server's social design (exploration spread, hub proximity, or natural-feeling clustering) with a single config key, without needing multiple regions.
- **Positive:** The three distributions cover the full qualitative space of placement relative to the annulus: inner edge, middle, and outer edge.
- **Positive:** Parity with JakesRTP lowers the migration barrier for operators switching to RTP.
- **Negative / Trade-offs:** Three distributions shall be maintained and tested. The `Normal` distribution in particular has an unclear primary use-case and was added largely for feature parity; if it is found to be unused it could be deprecated in a future major version.
- **Negative / Trade-offs:** Operators shall understand the concept of a probability distribution to choose correctly; documentation in `CONCEPTS.md` and `CONFIGURATION.md` is required to make this accessible.

## References

- `REQUIREMENTS.md` — REQ-RTP-F-003 (Configurable Geometry / Statistical Distributions)
- `DESIGN.md` §3 — Deterministic Spatial Algorithms (shape and distribution pipeline)
- `ARCHITECTURE.md` — MemoryShape / Shape layer description
- ADR-001 — Archimedean Spiral 1D Mapping (the 1D curve over which distributions are applied)
- JakesRTP plugin — prior art for the Normal distribution option
