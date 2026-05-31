# ADR-059 — Relative Ground-Distance Safety Predicate (synthetic block-state property)

**Status:** Proposed
**Date:** 2026-05-30

## Context

The ADR-017 safety-list grammar matches a candidate block by its `Material.name()`, its block tags, and its `BlockData` block-state properties (string equalities since `beta.1`, plus the numeric range predicates added in the ADR-017 amendment of 2026-05-30). Every signal it can read is **intrinsic to the single block** under inspection. It cannot express anything about the block's **spatial position relative to the surrounding terrain**.

That gap produces a concrete, recurring admin request that no current token can satisfy:

> "Leaves and roots sitting on the ground should be a safe place to land, but the canopy of a tall tree should not."

Today an admin has only two blunt instruments, both wrong at the extremes:

- Put `#minecraft:leaves` (and `#minecraft:logs`) in `airBlocks` so the player can land *beneath* a canopy. This also lets the player land **on top of** a 20-block-high oak, suspended in the canopy.
- Put `#minecraft:logs` in `unsafeBlocks` to forbid tree-top landings. This also forbids landing next to a perfectly safe ground-level log, stump, root, fallen trunk, or a 1-block decorative leaf pile.

The distinguishing fact between "leaf block on a stump" and "leaf block in a canopy" is **how far above the nearest solid ground the block sits** - a derived, column-spatial quantity, not a property of the block itself. The grammar has no way to read it, and `StatePredicate` (correctly) only sees the `liveProperties` map handed to `CompiledUnsafeSet.isUnsafe(...)`.

Two architectural constraints (inherited from ADR-011 / ADR-017) bound any solution:

- `rtp-api` shall not depend on `org.bukkit.*`. Any computation that walks a live chunk column belongs in the platform adapter (`rtp-bukkit-common`, `rtp-folia-common`, `rtp-fabric-*`) or the Anvil path (`rtp-anvil`), not in the pure compiler.
- S-005: no synchronous chunk I/O on the main thread. A ground-distance probe must read only blocks already resident in the candidate's chunk (the live `BukkitRTPChunk`/`FoliaRTPChunk` already holds it) or the Anvil NBT section already in memory - never trigger a fresh load.

## Decision

Introduce a **synthetic, pipeline-computed block-state property** that the safety evaluator injects into the live property map, so the *existing* numeric range predicate grammar (ADR-017 amendment) can bound it with **zero new grammar**. The canonical reserved key is:

```
_groundDistance      # lowercased to _grounddistance at parse + eval time
```

The leading underscore marks it as an **RTP-synthetic reserved property**: real Minecraft block-state property names never start with `_`, so there is no collision with `BlockData.getAsString()` output, and the existing `PROPERTY_KEY` pattern `[A-Za-z0-9_]+` already admits it unchanged. Admins write, for example:

```yaml
unsafeBlocks:
  - "#minecraft:leaves[_groundDistance>3]"   # canopy leaves unsafe; ground-level leaves fine
  - "#minecraft:logs[_groundDistance>1]"     # tree trunks unsafe above the base log; a stump is fine
```

`_groundDistance` is defined as **the number of blocks between the candidate block and the first solid (collision-bearing, non-air, non-`airBlocks`) block beneath it**, measured downward in the same column. `0` means the candidate sits directly on solid ground (or is itself the ground); a leaf pile on a stump reads `0`-`1`; canopy leaves read the trunk height. The probe is **bounded** by a configurable max scan depth (see below) and **fail-open**: if no ground is found within the probe budget, or the column cannot be read, the property is **absent**, and per ADR-017 §4 an absent property is a *miss* - the predicate cannot reject, so the candidate falls through to the rest of the pipeline (never an over-rejection, never an S-001 weakening because the authoritative ground/headroom sweep in `VerticalAdjustor` still runs).

### Computation site and laziness

- **Where:** computed in the platform safety check that already materialises the property map - `BukkitRTPChunk.isSafe(int,int,int,CompiledUnsafeSet)` (and the Folia/Fabric peers). The candidate's chunk is already resident there, so the downward column walk is pure in-memory block reads (S-005 safe). On the Anvil off-tick path (`AnvilChunkView`/`rtp-anvil`), the same offset is computed from the NBT section palette already in memory.
- **Lazy gating (zero cost for plain configs):** `CompiledUnsafeSet` gains a compile-time boolean `requiresGroundDistance()` (true iff any retained predicate references the `_grounddistance` key), mirroring the existing `hasWildcardStatePredicate()` flag. The column walk runs **only** when that flag is set *and* a state-predicate bucket applies to the candidate material/tag. A server with no `_groundDistance` token pays nothing - the flag is `false`, the probe is never entered, no extra allocation, the ADR-017 §4 hot-path fast-exit is preserved verbatim.
- **Injection:** when the flag is set, the adapter computes the integer offset and puts `_grounddistance -> Integer.toString(offset)` into the same lowercase `props` map before calling `isUnsafe(...)`. The numeric range predicate then parses it exactly like any other integer property. No change to `StatePredicate`, `SafetyTokenParser`, or `CompiledUnsafeSet.isUnsafe` evaluation is required beyond the new flag and its compile-time detection.

### Configuration

- `safety.yml::groundDistanceMaxProbe` (integer, default e.g. `16`, clamp `[1, world.maxHeight]`): the bounded downward scan budget. Caps the per-candidate cost and the worst-case column walk.
- Honours the existing `airBlocks` set as "not solid ground" when scanning, so a leaf canopy above another leaf layer measures to the real trunk/ground, not to the first leaf.

### Edition gating

This is a **full-edition (Pro)** feature, consistent with the rest of the ADR-017 token grammar: the rtp-lite assembly (ADR-024) parses `unsafeBlocks`/`airBlocks` as a flat material list and ships neither the predicate grammar nor this synthetic property. A lite token containing `[_groundDistance>n]` is treated as a plain (unmatched) material name.

### Scope of this ADR

This ADR records the **design only** (Rule D-005). No production code is changed by accepting it. Implementation is a follow-up increment and shall include: the `requiresGroundDistance()` compile flag + detection, the bounded column probe in each platform `isSafe(...,CompiledUnsafeSet)` and the Anvil view, the `groundDistanceMaxProbe` knob propagated through the locale TSV pipeline, REQ-traceable tests (a `Req*GroundDistance*` guard asserting fail-open on probe-exhaustion and a canopy-vs-stump discrimination case on both the live and Anvil paths), `TRACEABILITY.md` rows, `docs/admin/SAFETY.md` reference, and a CHANGELOG entry tagged `**(Pro)**`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **In-token spatial operator** (e.g. a new `@above(N)` form on the token) | Adds a second, position-aware sub-grammar with its own parser, breaking the "every condition is a `key op value` predicate" uniformity; the numeric-range predicate already added in the ADR-017 amendment expresses the bound for free once the value is a property. |
| **Dedicated sibling config key** (e.g. `maxTreeLandingHeight`) | Narrow, single-purpose, and not composable with material/tag scoping; cannot say "leaves above N but logs above M", cannot reuse for water/snow depth or any other column-relative future signal. The synthetic-property approach generalises (e.g. `_depthBelowSurface`, `_skyAccess` could follow the same pattern). |
| **Compute ground distance eagerly for every candidate** | Violates the zero-cost-for-plain-configs invariant of ADR-017 §4; an unbounded or always-on column walk is a per-candidate hot-path tax for a feature most servers never enable. |
| **Reject the request; tell admins to use vertical adjustors** | `VerticalAdjustor` chooses *where in the column* to land; it does not let the admin express "this material is safe near ground but not in a canopy". The two are orthogonal - the adjustor picks the Y, the safety filter vetoes the block - and the request is squarely a safety-filter concern. |
| **Real block-state key without the `_` prefix** (e.g. `groundDistance`) | Risks colliding with a future real Minecraft block-state property of the same name and silently shadowing it. The reserved underscore prefix is collision-proof against vanilla naming. |

## Consequences

- **Positive:** Closes the "ground leaves/roots safe, tall trees unsafe" request with no new grammar - it is one reserved property name plus one bounded probe. Reuses the numeric-range predicate machinery added in the ADR-017 amendment. Stays pure/Bukkit-free in `rtp-api`; the only platform work is an in-memory column read on an already-resident chunk. Generalises to future column-relative synthetic signals.
- **Negative / Trade-offs:** Introduces the concept of *synthetic* (pipeline-injected) properties to a grammar that was previously a faithful mirror of `BlockData`; the reserved `_`-prefix convention must be documented so admins do not expect it to come from the block. The Anvil-path offset must be kept in parity with the live-path offset (an ADR-016-style divergence guard test is required). The probe adds bounded per-candidate cost, but only for servers that opt in via a `_groundDistance` token.

## References

- [ADR-017](ADR-017-block-tags-and-state-predicates-in-safety-lists.md) — Block Tags and Block-State Predicates in Safety Lists (this ADR extends its grammar via a synthetic property; the numeric range amendment is the operator this feature consumes).
- [ADR-016](ADR-016-anvil-subsystem.md) — Anvil Read-Only Subsystem (live-vs-Anvil parity contract for the offset computation).
- [ADR-024](ADR-024-rtp-lite-assembly-variant.md) — RTP Lite Assembly Variant (edition gating).
- `docs/dev/REQUIREMENTS.md §3` — REQ-RTP-S-001 (unsafe-block prohibition), REQ-RTP-S-004 (never-silent failure), REQ-RTP-S-005 (no main-thread chunk I/O).
- `rtp-bukkit-common/.../world/BukkitRTPChunk.java#isSafe(int,int,int,CompiledUnsafeSet)` and the Folia/Fabric peers — proposed injection site.
- `docs/dev/ROADMAP.md` Tier 2 "Safety-list grammar expansion".
