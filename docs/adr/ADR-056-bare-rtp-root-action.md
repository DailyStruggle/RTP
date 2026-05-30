# ADR-056 - Pluggable bare-`/rtp` root action

**Status:** Accepted
**Date:** 2026-05-30

## Context

Server owners want a bare `/rtp` (no arguments) to open a destination menu (e.g. the GUI addon) instead of immediately teleporting, while keeping every subcommand (`/rtp admin`, `/rtp config`, ...) working and keeping the classic teleport reachable. Competing plugins (BetterRTP + its companion "Rtp GUI") achieve this by having a separate GUI plugin claim `/rtp` and shell out to the underlying teleport command; the menu is a pure presentation layer.

RTP already exposes a stable, addon-facing surface (`RTPAPI`, `RTPHooks`) and a single platform-neutral command tree (`commands-api`) dispatched identically on Bukkit-family adapters and via the Brigadier bridge. A bare `/rtp` runs the command root's own `onCommand`; subcommands resolve to child nodes *before* that no-arg branch. This ADR records the design (Rule D-005) for letting an addon override only the bare-root behaviour.

Scope note: renaming/aliasing the command label itself (`rtp` -> `tpr`) is a separate, deferred task and is **not** covered here.

## Decision

Introduce an optional, single-binding root-action hook on the same replaceable-provider pattern as the biome/anvil/PvP seams:

1. **`rtp-api` SPI.** `RootActionRegistry` (single-binding `bind`/`current`/`clear`, mirroring `PvPCombatStateRegistry`) with a functional `Action#run(UUID, Consumer<String>)` returning `boolean handled`, reachable via `RTPAPI.hooks().rootAction()`.
2. **No new configuration.** The behaviour is supplied entirely by the registered action; there is no `default-action` enum or config map. When no action is bound (`current() == null`) a bare `/rtp` performs the classic random teleport, so stock behaviour is unchanged.
3. **Core dispatch (`rtp-core`).** `RTPCmd.onCommand(...)` consults the registry only on the `!hasSubCommand` (bare-root) branch, before the teleport-specific guards. A handled action (`return true`) suppresses the classic teleport and bypasses cooldown / `processingPlayers` registration - exactly as a subcommand does, because a menu open is not a teleport. An action that returns `false` defers to the classic teleport.
4. **Platform-neutral.** Because the lookup lives in the shared command root, it resolves identically for the Bukkit executor and the Brigadier bridge (Fabric, planned Velocity); no platform-adapter change is required.
5. **Reusing classic behaviour.** An action that wants the original teleport for some inputs/player-state can return `false`, or call `RTPAPI.teleport(uuid, target)` itself (which re-applies every safety/permission check) and return `true`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| `default-action` enum + config map (`label -> action-id`) | The owner explicitly rejected new config; the registered action alone determines behaviour. Label renaming is a separate, deferred concern. |
| Last-registered-wins multi-action registry | Unnecessary now (addon-only consumer); a single override slot is deterministic and load-order-independent. |
| Addon claims `/rtp` via Bukkit `CommandMap` (BetterRTP-GUI approach) | Fights registration order with core's `plugin.yml` command, is Bukkit-only, and risks reload leaks; the in-tree hook is platform-neutral and safe. |
| Hard-code a built-in `menu` action | The menu/GUI is addon-supplied; core stays ignorant of GUI specifics. |

## Consequences

- **Positive:** One small, replaceable seam consistent with the existing hook architecture; subcommands provably untouched (resolved before the branch); platform-neutral; off by default so no behaviour change; safe failure mode.
- **Negative / Trade-offs:** Adds one read on the bare-`/rtp` hot path (a single volatile-slot lookup, negligible). The menu itself is addon-supplied - a bare install without an addon sees no behaviour change. An eventual addon-loader / built-in menu action can layer on later without changing this SPI.

## References

- [ADR-026 - external hook API surface](ADR-026-external-hook-api-surface.md), [EXTERNAL_HOOKS.md](../dev/EXTERNAL_HOOKS.md)
- `PvPCombatStateRegistry` / `AnvilPrefilterRegistry` (the single-binding precedent), REQ-RTP-S-004, REQ-API-F-006
- Code: `rtp-api/.../hooks/RootActionRegistry.java`; `rtp-core/.../common/hooks/DefaultRTPHooks.java`; `rtp-core/.../common/commands/RTPCmd.java`
- Test: `rtp-core/.../common/commands/ReqApiF006RootActionTest.java`
