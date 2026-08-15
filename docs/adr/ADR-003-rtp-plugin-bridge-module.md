# ADR-003 — rtp-plugin as a Separate Bridge Module from rtp-core

**Status:** Accepted
**Date:** 2026-04-15

## Context

The plugin shall be operational on multiple server platforms (Spigot, Paper, Folia) while keeping its core teleportation logic platform-agnostic. A decision was needed on how to structure the boundary between pure logic and server-implementation concerns: specifically, whether the `JavaPlugin` entry point and Bukkit lifecycle wiring should live inside `rtp-core` or in a dedicated module.

`rtp-core` is designed as a pure logic layer — it contains region management, shape algorithms, queue management, and database interactions, with no imports of Bukkit, Spigot, Paper, or Folia classes. This constraint is enforced by the `core_must_not_depend_on_platform_apis` ArchUnit test.

However, the plugin entry point (`RTPBukkitPlugin`) necessarily derives from both server implementation classes (e.g., `JavaPlugin`) and from `rtp-core` logic. Placing it inside `rtp-core` would violate the platform-agnosticism constraint and couple the core to a specific server API.

## Decision

The `rtp-plugin` module shall be a dedicated bridge module that sits between `rtp-core` and the platform adapters.

`rtp-plugin` is the only module permitted to depend on both `rtp-core` logic and server implementation classes simultaneously. It owns:
- The `JavaPlugin` subclass (`RTPBukkitPlugin`) and its lifecycle hooks (`onEnable`, `onDisable`)
- Configuration initialisation and command registration
- Standard Bukkit event listeners that are common across all platforms
- The wiring that selects and activates the correct platform adapter at runtime

This keeps `rtp-core` a pure logic layer, easing the addition of new server platform adapters without touching core logic.

## Consequences

- **Positive:**
  - `rtp-core` remains fully platform-agnostic and unit-testable without a Minecraft server.
  - Adding a new platform adapter (e.g., a future `rtp-purpur`) requires only a new adapter module and a wiring change in `rtp-plugin` — no changes to core logic.
  - The ArchUnit test `core_must_not_depend_on_platform_apis` can enforce the boundary automatically in CI.

- **Negative / Trade-offs:**
  - One additional module in the build graph increases build complexity slightly.
  - Contributors shall understand which module owns which concern; the module breakdown in `ARCHITECTURE.md` documents this explicitly.

## References

- Architecture overview: [`ARCHITECTURE.md` — Module Breakdown](../dev/ARCHITECTURE.md)
- ArchUnit enforcement: `RTPArchitectureTest#core_must_not_depend_on_platform_apis` (`rtp-core`)
- Implementing class: `RTPBukkitPlugin.java` (`rtp-plugin`)
- Requirements: `REQ-RTP-S-001` (platform compatibility), `REQ-CORE-NF-001` (platform agnosticism)
