# ADR-063 — Biome-First Menu with Auto-Region Selection by Biome Availability

**Status:** Accepted
**Date:** 2026-06-11

## Context

The interactive `/rtp` menu ([ADR-035](ADR-035-interactive-menus-book-first.md), [ADR-044](ADR-044-command-tree-menu-reflector.md), [ADR-050](ADR-050-concrete-menu-commands-supersede-tokens.md)) already exposes a biome picker row. `FrontPageBuilder` emits an `OpenParamPicker(new String[0], "biome")` row, and `CommandTreeMenuBuilder.buildParamPicker(...)` renders one value row per suggestion drawn from `CommandParameter.relevantValues(senderId)`. Today the underlying `BiomeParameter.values()` returns the whole-server biome set (`RTPServerAccessor#getBiomes()`), which has two problems:

1. **Over-broad list.** It offers biomes that may not exist in any region the player can actually reach. Selecting such a biome runs `/rtp biome:<x>` as a hard filter that can reroll for a long time (or never succeed), which is exactly the bounded-latency failure mode RTP exists to avoid.
2. **No region awareness.** When `/rtp biome:<x>` is invoked without a region, `RTPCmd` resolves the region from the player's current world (`world → WorldKeys.region`). It does not check whether that region has ever *observed* the requested biome, so a player standing in a world whose default region lacks the biome gets a degenerate teleport.

Two pieces of infrastructure already exist that make a better design cheap:

- **Per-region observed-biome enumeration.** `MemoryShape.getObservedBiomes()` returns the union of biome identifiers that have actually produced a candidate within a region's shape. It is a live, chunk-I/O-free view (REQ-RTP-S-005 safe), is strictly tighter than `getBiomes(world)`, and is upgrade/datapack-drift proof because it reflects what the populated `.mca` palettes contain rather than what the live noise map would synthesize (see [ADR-016](ADR-016-anvil-subsystem.md), [ADR-062](ADR-062-biome-probability-weighting.md)).
- **Biome → color resolution.** `maps-api`'s `BiomeColorSource.resolve(name)` returns a 24-bit RGB for any biome name, using the platform's real vanilla map color where known and a deterministic 16-entry categorical hash fallback for unknown / datapack / custom-generator biomes ([ADR-046](ADR-046-maps-api-module.md)). It is a pure, thread-safe function with no chunk I/O.

A design constraint from the discussion: the menu must not introduce **player-controlled disk writes** (e.g. on-demand sampling of biome locations triggered by opening the menu). The pre-scan empty state is acceptable because an automatic background sampler populates the observed-biome data over time.

## Decision

*Accepted and implemented (2026-06-11).*

1. **Biome-first selection model.** The menu biome picker lists biomes, not regions. Players pick a biome directly; the region is chosen for them. Region-first drill-down is explicitly not adopted for the player-facing flow.

2. **Observed-biome list source.** The biome picker's values are the union of `MemoryShape.getObservedBiomes()` over the regions the viewer can access (filtered by `rtp.regions.<name>` and `rtp.biome.<name>` permissions), replacing the whole-server `getBiomes()` source for the menu path. If the union is empty (nothing sampled yet), the front-page biome row hides via the existing `parameterHasSuggestions` gating.

3. **Auto-region by biome availability, in the shared command path.** The region-selection logic lives in the `/rtp biome:<x>` resolution path in `RTPCmd`, not solely in the menu. When `biome:<x>` is supplied without an explicit `region`/`world`, the command resolves a region whose observed biomes include `<x>`:
   - prefer the player's world-default region when it already qualifies (preserving current behavior where it works);
   - otherwise pick the accessible region with the strongest observation of that biome;
   - fall back to today's world-default resolution when no region has observed the biome (cold-data graceful degradation).
   This guarantees **CLI/menu parity**: the menu emits the plain `/rtp biome:<x>` command (no special menu-only region argument), so the bare command is a faithful fallback and there is a single source of truth for the behavior.

4. **Row colorization.** Each biome row is colored from `BiomeColorSource.resolve(name)`, converted to a chat color. For the book/parchment renderer the luminance is clamped (bright biome colors darkened) to satisfy the book color-contrast rule; the chat-channel renderer may use the raw hex. **World rows** reuse the same primitive transitively: a world's row color is the observation-count-weighted average of the `BiomeColorSource` colors of the biomes observed in that world (`BiomeMenuSource.biomeWeightsForWorld`), run through the same parchment luminance clamp + nearest-dark-legacy-code match so a pale world average (e.g. desert/plains) is darkened to a readable code rather than washing out on the parchment background. A world with no observed biomes yet (cold data) falls back to the neutral default row color.

5. **No player-controlled disk writes.** Opening or interacting with the menu performs no biome sampling, no `.mca` reads on demand, and no DB writes. The observed-biome data is filled exclusively by the existing background sampler / organic traffic.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Region-first drill-down (pick region, then biomes scoped to it) | Adds a click and exposes the admin-facing "region" concept to ordinary players; biome-first is simpler and the region can be inferred from biome availability. |
| Keep the whole-server `getBiomes()` list, color only | Still offers unreachable biomes and leaves the long-reroll / wrong-region failure mode in place. |
| Put the auto-region logic only in the menu (emit `region:<auto> biome:<x>`) | Breaks CLI/menu parity: bare `/rtp biome:<x>` would behave differently from the menu, and the menu could not use the plain command as a fallback. Logic must be shared. |
| Sample biome locations on disk while the menu is open to enrich targets | Introduces player-controlled disk I/O and risks S-005; explicitly rejected. The background sampler already fills this data. |
| Hand-maintained biome→color table or AI-driven coloring | `BiomeColorSource` already provides authoritative colors plus a deterministic hash fallback for unknown biomes; a table needs maintenance and AI coloring is non-deterministic and untestable. |

## Consequences

- **Positive:** Players choose biomes that are actually reachable; selections resolve to a region that contains the biome, keeping teleports bounded. CLI and menu share one behavior, so the menu degrades to the plain command cleanly. Reuses existing drift-proof, chunk-I/O-free primitives (`getObservedBiomes`, `BiomeColorSource`) with no new platform code. Colored rows improve readability without a maintained table.
- **Negative / Trade-offs:** Biome rows are empty until the background sampler has observed biomes in at least one accessible region (acceptable pre-scan UX). Computing the accessible-region union and best-region map on each menu render adds in-memory work (bounded by region × observed-biome-set size). Changing `/rtp biome:<x>` region resolution is a behavior change to a public command and requires a regression test (region auto-picked when the world-default region lacks the biome). The book renderer needs a luminance-clamp step for biome colors.

## References

- [ADR-016](ADR-016-anvil-subsystem.md) - Anvil `.mca` pre-filter; biome source of truth.
- [ADR-035](ADR-035-interactive-menus-book-first.md) - book-first interactive menus.
- [ADR-044](ADR-044-command-tree-menu-reflector.md) - command-tree menu reflector.
- [ADR-046](ADR-046-maps-api-module.md) - `maps-api`; `BiomeColorSource`.
- [ADR-050](ADR-050-concrete-menu-commands-supersede-tokens.md) - concrete `/rtp` menu commands.
- [ADR-062](ADR-062-biome-probability-weighting.md) - per-region biome occupancy and Anvil-sourced biome accuracy.
- `rtp-core/.../selection/region/selectors/memory/shapes/MemoryShape.java` - `getObservedBiomes()`.
- `rtp-core/.../commands/menu/CommandTreeMenuBuilder.java` - `buildParamPicker`, `safeSuggestions`.
- `rtp-core/.../commands/menu/FrontPageBuilder.java` - biome picker row + `parameterHasSuggestions` gating.
- `rtp-core/.../commands/parameters/BiomeParameter.java` - biome parameter values / relevance.
- `rtp-core/.../commands/RTPCmd.java` - `/rtp biome:<x>` region resolution path.
