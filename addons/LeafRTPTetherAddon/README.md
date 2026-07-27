# LeafRTPTetherAddon

Keep a player inside the RTP region they were teleported into - a cross-platform "tether" (region
confinement), with no dependency on WorldGuard or any other region plugin.

> "Zone" is the informal word other plugins use for this; here the behavior is a **tether** to an
> existing RTP region, because an RTP region already *is* the bounded area a "zone" would define.

## Why this fits LeafRTP

A WorldGuard `entry`/`exit` region trap solves confinement simply - but only on Bukkit; there is no
WorldGuard on Fabric or NeoForge. LeafRTP already owns the two pieces confinement actually needs:

1. **Region geometry.** An RTP region defines a bounded area, so "is this coordinate inside the
   region?" is pure math - no chunk load, safe on any thread.
2. **Teleport events + database.** RTP already knows when it teleports a player into a region, and
   already has a database interface to persist state.

The only missing piece is a **platform-neutral move signal** to notice when a tethered player crosses
the boundary. Adding that to the core (proposed) makes the whole feature cross-platform by
construction, so the same addon works identically on Bukkit/Paper/Folia and Fabric/NeoForge.

## Status

Scaffold. This module wires configuration and lifecycle only and loads as a safe no-op. Tether
enforcement depends on a platform-neutral player-move event SPI that does not yet exist in the core;
that addition is a cross-module change and is proposed (D-005 gated) in:

- [`docs/dev/PROPOSAL-tether-and-move-event-spi.md`](../../docs/dev/PROPOSAL-tether-and-move-event-spi.md)
- [`docs/adr/ADR-075-platform-neutral-player-move-event-spi.md`](../../docs/adr/ADR-075-platform-neutral-player-move-event-spi.md)

The addon's own contract is in [`REQUIREMENTS.md`](REQUIREMENTS.md) and
[`docs/adr/leafrtp-tether-addon-ADR-001-cross-platform-region-confinement.md`](docs/adr/leafrtp-tether-addon-ADR-001-cross-platform-region-confinement.md).

## How it works (design)

- **Arm** a tether from RTP's own `PostTeleport` event when a player is teleported into a tethered
  region - so only players who were actually placed in a region are tracked.
- **Watch** only the tethered set via the core's platform-neutral move signal (block-granularity), so
  the per-move cost is bounded to confined players, not the whole server.
- **Enforce** on a boundary crossing by pulling the player back to a fresh safe destination inside the
  region (drawn from the supply pipeline, so it passes the same safety checks as any RTP). Enforcement
  is pull-back, never movement-veto (veto is Bukkit-only).
- **Disarm** when another teleport takes the player out, or on explicit release.
- **Persist** active tethers through the core database interface (optional) so they survive
  restart/relog.
- Optional third-party region bounds (WorldGuard on Bukkit, a claim mod on Fabric/NeoForge) can
  tighten the containment through the rtp-api hook surface (ADR-026) where present, but are never
  required.

## Configuration (`addons/tether.yml`)

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `true` | Master on/off switch. |
| `onExit` | `PULL_BACK` | Boundary-crossing behavior (`PULL_BACK` = safe teleport back inside). |
| `persistState` | `true` | Persist tethers across restart/relog via the core database. |

## Install

Drop `LeafRTPTetherAddon-<version>.jar` into `plugins/RTP/addons/`.
