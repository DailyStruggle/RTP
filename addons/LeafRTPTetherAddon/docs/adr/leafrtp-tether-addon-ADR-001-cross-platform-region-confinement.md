# leafrtp-tether-addon-ADR-001 - Tether: Cross-Platform Region Confinement Composed From RTP Primitives (No WorldGuard Trap)

**Status:** Proposed
**Date:** 2026-07-25

## Context

Event and minigame servers often want to keep a randomly-teleported player inside a bounded area -
what competitor plugins call a "zone" or "arena". On Bukkit this is trivially delegated to
WorldGuard's `entry`/`exit` region flags, which trap a player in (or out of) a region for free. But
WorldGuard is Bukkit-family only; there is no WorldGuard on Fabric or NeoForge, both of which are
first-class RTP platforms. A WorldGuard-based confinement is therefore not portable, and the
portability has to come from around it.

The word "zone" is also nearly redundant here: an RTP region already *is* a bounded distribution area
with all the shape/geometry machinery. A separate "zone" area model would duplicate that. The useful,
non-redundant capability is **confinement** - keep the player inside the region they were placed in -
which this addon names a **tether**.

LeafRTP already owns almost everything confinement needs:

1. **Region geometry** - an RTP region answers "is this coordinate inside?" with pure math, no chunk
   load, safe on any thread.
2. **Teleport events** (`PostTeleportEvent`, etc.) - the core already knows when it places a player
   in a region.
3. **A database interface** (`AbstractSQLDatabaseAccessor`) - for durable state.

The only missing primitive is a **platform-neutral player-move signal** to notice a boundary
crossing. That is a cross-module core addition, proposed separately and D-005 gated (see References);
this ADR covers only the addon that consumes it.

## Decision

Model a tether as: a player bound to an existing RTP region, enforced by RTP's own primitives.

- **Membership is edge-triggered by teleport events.** Arm a tether when the core teleports a player
  into a tethered region (`PostTeleportEvent`); disarm it when a later teleport removes the player or
  on explicit release. The move signal is only the *enforcement* channel, not the membership channel.
- **Only tethered players are move-watched.** Enforcement subscribes to the core's platform-neutral
  move signal (block-granularity) and acts only for the tracked set, so per-move cost scales with the
  number of tethered players, not the whole server. This is deliberately unlike a WorldGuard-style
  per-move region lookup for everyone.
- **Containment is a chunk-free geometry test** against the region's own bounds.
- **Enforcement is safe pull-back, not movement-veto.** On a boundary crossing the player is returned
  to a fresh destination inside the region, drawn from the supply pipeline so it passes the same
  safety verification as any RTP (S-001..S-005) and is never silently dropped (S-004). Movement-veto
  is avoided because it is Bukkit-only; pull-back works identically on every platform. On Folia the
  move fires on the entity's region thread, so the pull-back routes through the entity scheduler via
  the core scheduler/server-accessor.
- **State is optionally persisted** through the core database interface so a tether survives
  restart/relog; persisted tethers re-arm when the player is available again.
- **External region sources are optional accuracy boosters, never dependencies.** Where WorldGuard
  (Bukkit) or a Fabric/NeoForge claim mod is present, a tether may be further constrained through the
  shared hook surface (ADR-026); with none present, the RTP region geometry alone is authoritative.

Configuration (`addons/tether.yml`): `enabled`, `onExit` (`PULL_BACK`), `persistState`, registered
through the core `ConfigParser` and refreshed on `/rtp reload`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Delegate confinement to WorldGuard's `entry`/`exit` region trap | Bukkit-only; no equivalent on Fabric/NeoForge, so it cannot be the portable mechanism. Kept only as an optional bound via the ADR-026 hook where present. |
| Bespoke "zone"/area model with its own storage and editor | Duplicates the RTP region geometry and distribution the core already owns; "zone" is ~90% just a region. A tether references an existing region instead. |
| Geofence: sweep every online player's position each tick | The expensive shape WorldGuard's session state machine works hard to avoid; unnecessary here because membership is edge-triggered, so only tethered players need watching. |
| Enforce by cancelling the movement event | Movement-veto is Bukkit-only and does not exist cleanly on Fabric/NeoForge; safe pull-back is portable. |
| Put tether logic in `rtp-core` | Confinement is optional orchestration over core primitives, not core distribution logic; it belongs in an addon (ADR-013 / ADR-057). The *move-event primitive* it consumes does belong in core and is proposed as ADR-075. |

## Consequences

- **Positive:** True cross-platform confinement with no third-party dependency; cheap (edge-triggered
  membership + chunk-free geometry + bounded move-watching); safety preserved by reusing the supply
  pipeline for pull-back; naturally durable via the existing database interface.
- **Negative / Trade-offs:** Depends on a new core move-event SPI (ADR-075) that must be approved and
  land first; until then the addon is a safe no-op. Pull-back is a visible relocation rather than a
  silent boundary stop, which is a different feel from WorldGuard's `deny-message` wall (acceptable,
  and arguably clearer to the player). A very small region raises pull-back frequency; operators size
  regions accordingly.
- This ADR is confined to the addon; the cross-module move-event decision lives in the project-wide
  ADR-075.

## References

- [ADR-075](../../../../docs/adr/ADR-075-platform-neutral-player-move-event-spi.md) - Platform-neutral player-move event SPI (the core primitive this addon consumes; Proposed, D-005 gated).
- [`docs/dev/PROPOSAL-tether-and-move-event-spi.md`](../../../../docs/dev/PROPOSAL-tether-and-move-event-spi.md) - the D-005 proposal for the core change.
- [ADR-057](../../../../docs/adr/ADR-057-platform-agnostic-addon-spi.md) - Platform-agnostic addon SPI (`RTPAddon` + ServiceLoader).
- [ADR-026](../../../../docs/adr/ADR-026-external-hook-api-surface.md) - External hook API surface (optional external region bounds).
- [ADR-013](../../../../docs/adr/ADR-013-addons-as-external-gradle-projects.md) - Addons as external Gradle projects.
- [ADR-002](../../../../docs/adr/ADR-002-h2-sqlite-over-flat-file-cache.md) - H2/SQLite persistence (the database interface tethers persist through).
- [`REQUIREMENTS.md`](../../REQUIREMENTS.md) - the addon's requirements.
