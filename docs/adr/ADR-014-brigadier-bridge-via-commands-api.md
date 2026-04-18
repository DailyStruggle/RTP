# ADR-014 — Brigadier Bridge via `commands-api` Adapter Layer

**Status:** Accepted
**Date:** 2026-04-17

## Context

RTP's command tree is defined once in `commands-api` and executed on Bukkit-family platforms via a Bukkit command dispatcher. Fabric uses Minecraft's native Brigadier command system.

Duplicating the command structure in Brigadier terms across platforms forces any change to the command tree (new subcommand, renamed argument, permission change) to be applied in multiple places. This causes divergence. The `commands-api` module shall be the unified, platform-agnostic command framework and the single source of truth.

## Decision

A `BrigadierCommandAdapter` inside `commands-api` shall be provided to convert the `commands-api` tree into Brigadier nodes. Platform adapters (e.g., `rtp-fabric`) shall be thin registration shims that delegate to this adapter. The adapter shall carry a `compileOnly` dependency on Brigadier and shall not load on Bukkit platforms.

For implementation and code-level details, see [DESIGN.md — Brigadier Bridge](../dev/DESIGN.md#brigadier-bridge-commands-api).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Keep manually duplicating the command tree in `rtp-fabric` | Any command tree change required two edits; divergence was inevitable. |
| Move all command logic into `rtp-fabric` and drop `commands-api` for Fabric | Defeated the purpose of `commands-api` as a shared framework; broke the architecture boundary (ARCHITECTURE.md). |
| Use Brigadier directly in `rtp-core` | `rtp-core` does not import platform-specific classes (ArchUnit-enforced). Brigadier is bundled with Minecraft, not a standalone library available to `rtp-core`. |
| Adopt Architectury as a cross-platform command abstraction | Architectury is a large transitive dependency and introduces its own abstraction layer on top of Fabric/Forge. Premature until Forge support is actively planned; re-evaluate when `rtp-forge` work begins (see MULTI_PLATFORM_PLAN.md §Future). |

## Consequences

- **Positive:**
    - Single source of truth for the RTP command tree across all platforms.
    - Adding a new subcommand or changing permissions requires one edit in `commands-api`, automatically reflected on both Bukkit and Fabric.
    - `rtp-fabric`'s command registration code is reduced to a few lines.
    - Brigadier's client-side tab completion is available for free since the adapter produces correct argument nodes.

- **Negative / Trade-offs:**
    - `commands-api` carries a `compileOnly` dependency on Brigadier. This is acceptable because Brigadier is a stable, MIT-licensed library bundled with every Minecraft server; it does not add a runtime dependency for Bukkit users.
    - The adapter maps `commands-api` argument types (string, player, integer, etc.) to Brigadier argument types. This mapping is updated as `commands-api` evolves.
    - Brigadier's `CommandContext` and `commands-api`'s execution context are different types; the adapter bridges them without leaking Brigadier types into `commands-api` core interfaces.

## References

- `commands-api/src/main/` — command tree definition
- `rtp-fabric/src/main/java/.../fabric/commands/RTPCmdFabric.java` — registration shim
- [MULTI_PLATFORM_PLAN.md §Phase 3](../dev/MULTI_PLATFORM_PLAN.md#phase-3-command-system-refinement)
- [ARCHITECTURE.md](../dev/ARCHITECTURE.md) — dependency rule: `rtp-core` and `rtp-api` do not import platform-specific classes (ArchUnit-enforced)
