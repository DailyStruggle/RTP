# ADR-065 — World-Override Regions and the `/rtp` World Menu

**Status:** Accepted
**Date:** 2026-06-11

## Context

The `/rtp` menu ([ADR-035](ADR-035-interactive-menus-book-first.md), [ADR-044](ADR-044-command-tree-menu-reflector.md), [ADR-050](ADR-050-concrete-menu-commands-supersede-tokens.md), [ADR-063](ADR-063-biome-first-menu-and-auto-region-by-biome.md)) exposes region and biome picker rows but no world picker row. Adding one surfaced a latent bug in how `/rtp` handles a world target.

`world` exists as **two distinct parameters** with different permissions:

1. **Top-level `rtp world:<x>`** - `WorldParameter` (`rtp.world`, value-gated on `rtp.worlds.<name>`). In `RTPCmd` this does not override anything: it looks up the target world's config and reads `WorldKeys.region`, which is commonly preconfigured to redirect back to the default world's region. So `/rtp world:nether` resolves to the overworld's region and the player is sent to the overworld, not the nether.
2. **Region sub-parameter `rtp region:<r> world:<x>`** - `regionParameter.put("world", WorldParameter("rtp.params", ...))`, the "modify xz selection" overload. The command framework flattens region sub-parameters into the same value map under their bare name (`TreeCommand`), so this arrives as `world=[x]` alongside `region=[r]`. `RTPCmd` consumed only the **shape** sub-parameters into a cloned region; the `world` sub-parameter was ignored, and `SelectionAPI.tempRegion(...)` (which can override `RegionKeys.world`) was never called in production. So `region:default world:nether` was also a no-op.

The result: there is no working way to RTP into a specific world unless that world happens to have its own region whose configured world is itself.

The infrastructure to fix this already exists:

- `SelectionAPI.tempRegion(...)` builds a region from a base region's settings with selected `RegionKeys.*` overridden, including `world`.
- `RTPServerAccessor.getShape(worldName)` returns a shape bound to the target world's data (the world-border-override path already uses it to rebind a cloned region's shape).
- `tempRegions` already has a shutdown/flush/clear lifecycle on reload ([Configs.reloadRegions]) and server stop ([RTP.stop]).

## Decision

*Accepted and implemented (2026-06-11).*

1. **World-override regions.** When a `world:<w>` argument is present (from either grammar), `RTPCmd` resolves the base region as today, then replaces it with a **world-override region**: the base region's settings with `world` set to the requested `RTPWorld` and `shape` re-resolved for that world via `getShape(w)`. This is the single shared mechanism for both `rtp world:<w>` and `rtp region:<r> world:<w>`, keeping CLI and menu in parity (the menu emits the plain `/rtp world:<w>`).

2. **Stable per-world naming and caching.** World-override regions are named `"<baseRegion>_<world>"` (e.g. `default_nether`) and cached in the existing `SelectionAPI.tempRegions` map keyed by the target world's UUID (`RTPWorld.id()`), so repeated requests reuse the same region rather than minting a throwaway each call. Reusing `tempRegions` means they inherit the existing temp-region shutdown/flush/clear lifecycle (reload and server stop) and DB dump with no new bookkeeping or new map. The two key spaces in `tempRegions` (sender UUID for shape/vert overrides, world UUID for world overrides) do not collide in practice. When the base region already targets the requested world, the base region is returned unchanged (no synthetic region is created).

3. **No new permissions.** The existing value-time gates remain authoritative: `rtp.world` + `rtp.worlds.<name>` for the top-level path, `rtp.params` + `rtp.worlds.<name>` for the region sub-parameter path. The override only runs for a world value that already passed the parameter's relevance check, so it cannot widen access.

4. **World menu row.** `FrontPageBuilder` gains a `world` picker row (mirroring the region/biome rows: gated on parameter permission and non-empty `relevantValues`), emitting `OpenParamPicker(world)`. Picker rows are colored by `MenuColor.worldColorPrefix` (ADR-063 follow-up: the observation-count-weighted average of the biomes observed in that world, parchment-clamped). A new configurable message key `menuFrontPageRowWorld` labels the row.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Make top-level `world:<x>` follow `WorldKeys.region` but force the resolved region's world | That is exactly the override; doing it without a distinct named region would mutate a shared `permRegionLookup` region and corrupt its cache for normal `/rtp`. |
| One-shot per-sender temp region (like the shape-override path) | A new region every command throws away the previous one's cache; keying the same `tempRegions` map by world UUID instead reuses one region per world. |
| Register world-override regions in `permRegionLookup` | They would leak into region tab-completion and `regionNames()`, and be indistinguishable from configured regions. `tempRegions` keeps them internal while reusing the existing lifecycle. |
| A separate `worldRegions` map | Would duplicate the temp-region shutdown/flush/clear/DB-dump wiring across `RTP.stop`, `Configs.reloadRegions`, and the metrics/introspection iterations. Keying `tempRegions` by world UUID needs none of that. |
| Menu-only fix (emit `region:<auto> world:<x>` from the menu) | Breaks CLI/menu parity; bare `/rtp world:<x>` would still be broken and could not serve as the menu's fallback. |

## Consequences

- **Positive:** `/rtp world:<x>` and `/rtp region:<r> world:<x>` finally teleport into world `x`. CLI and menu share one behavior, so the menu degrades to the plain command. World-override regions are cached per world (reused across requests) and cleaned up on reload/stop by the existing temp-region lifecycle. No new permissions, no new map, no platform code.
- **Negative / Trade-offs:** Each target world allocates a long-lived `Region` (queues, pipelines, scan-progress, async DB hydrate) the first time it is requested, bounded by the number of accessible worlds. World-override regions are stored in `tempRegions`, which the `AsyncTaskProcessing` background pulse does not tick (only `permRegionLookup` is pulsed), so they fill on demand via the teleport's own cold path rather than warming ahead of time - consistent with the existing shape-override temp-region behavior. Keying by world UUID means one region per world regardless of base region (acceptable: the player-facing flow only ever uses the default base). Changing a public command's region resolution requires a regression test (`/rtp world:nether` lands in the nether's region, not the default).

## References

- [ADR-035](ADR-035-interactive-menus-book-first.md) - book-first interactive menus.
- [ADR-044](ADR-044-command-tree-menu-reflector.md) - command-tree menu reflector.
- [ADR-050](ADR-050-concrete-menu-commands-supersede-tokens.md) - concrete `/rtp` menu commands.
- [ADR-063](ADR-063-biome-first-menu-and-auto-region-by-biome.md) - biome-first menu, auto-region, `MenuColor` world coloring.
- `rtp-core/.../selection/SelectionAPI.java` - `tempRegion`, `worldRegion`, `tempRegions` (world-UUID-keyed world overrides).
- `rtp-core/.../commands/RTPCmd.java` - world-override application in the region-resolution path.
- `rtp-core/.../commands/menu/FrontPageBuilder.java` - world picker row.
