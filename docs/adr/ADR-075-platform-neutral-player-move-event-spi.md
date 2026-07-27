# ADR-075 - Platform-Neutral Player-Move Event SPI

**Status:** Accepted
**Date:** 2026-07-25 (Accepted 2026-07-25)

## Context

RTP already exposes a set of platform-neutral lifecycle events - teleport (`PreTeleportEvent` /
`PostTeleportEvent`), queue push/pop, chunk-load - dispatched uniformly from `rtp-core` and bridged to
each runtime by the per-platform event bridges (`FabricEventBridge`, `NeoForgeEventBridge`, the Bukkit
`events/` package). There is no equivalent for **player movement**.

A player-move signal is needed to build features that react to *where a player is* between teleports.
The immediate driver is cross-platform region confinement (the "tether" addon,
`leafrtp-tether-addon-ADR-001`): keep a player inside the RTP region they were teleported into. On
Bukkit this is usually delegated to WorldGuard's `entry`/`exit` region trap, but WorldGuard is
Bukkit-family only - there is no WorldGuard on Fabric or NeoForge, both first-class RTP platforms.
Building confinement on RTP's own primitives instead of WorldGuard requires a move signal RTP owns.

The platforms expose movement very differently:

- **Bukkit/Paper/Folia:** a native, very high-frequency `PlayerMoveEvent` (fires on sub-block motion;
  on Folia it fires on the moving entity's region thread).
- **Fabric/NeoForge:** no native per-move event; movement is observed by reading player position on the
  server tick and diffing against the last observed position.

Movement is also the single most common performance footgun in server plugins: naively delivering
every sub-block move to every listener for every player is wasteful. Any core SPI must make the cheap
path the default.

This is a cross-module change (new `rtp-api` surface, `rtp-core` dispatch, an implementation in every
platform adapter) and was therefore **D-005 gated**. The proposal is **approved**: the added surface
lives in the server-independent `rtp-api`, which there are tentative plans to decouple from RTP into a
standalone library later, so a clean platform-neutral move-event SPI is a deliberate step toward that
split. The full rationale and rollout plan are in
[`docs/dev/PROPOSAL-tether-and-move-event-spi.md`](../dev/PROPOSAL-tether-and-move-event-spi.md).

## Decision

Add a platform-neutral player-move event to the existing RTP event surface, with three properties that
keep it cheap and portable:

1. **Normalized to block-granularity.** The event fires when a watched player crosses into a new block
   (its `RTPCoords` block position changes), not on every sub-block motion. Each adapter is
   responsible for producing this normalized signal from whatever its runtime offers (filter
   `PlayerMoveEvent` down to block changes on Bukkit; diff tick-sampled position on Fabric/NeoForge).
   `rtp-core` and consumers see one uniform callback carrying the player, the previous block
   coordinate, and the new block coordinate.

2. **Opt-in, per-player subscription.** A consumer registers interest in specific players (or
   withdraws it), and adapters do movement work only for the watched set. Cost scales with the number
   of watched players, not the total online count. This is the deliberate inverse of a "listen to
   everyone" model and is what makes edge-triggered features (arm on teleport, watch only the armed
   set) cheap.

3. **Thread-correct dispatch.** The signal is delivered on the platform's natural thread for that
   player (e.g. the entity's region thread on Folia); consumers that relocate a player route through
   `RTP.scheduler` / `RTPServerAccessor` as they already must, so no new threading contract is
   introduced. The move event itself performs no chunk I/O.

Placement follows the existing event architecture: the neutral event type and subscription surface in
`rtp-api`, dispatch in `rtp-core`, and one concrete producer per platform adapter alongside the other
bridges. Consumers (such as the tether addon) enforce by relocating the player (safe pull-back), never
by vetoing movement, because move-veto is Bukkit-only.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Depend on WorldGuard's `entry`/`exit` region trap | Bukkit-family only; no equivalent on Fabric/NeoForge, so it cannot be the portable mechanism. Remains available as an *optional* external bound via the ADR-026 hook where present. |
| Put move handling only in the Bukkit adapter / a Bukkit-only addon | Fails the cross-platform goal; the whole point is one addon that behaves identically on every platform. |
| Deliver every sub-block move to all listeners for all players | Movement is the classic performance footgun; block-granularity + opt-in per-player subscription is required to keep the cheap path the default. |
| Sweep all player positions on a central core tick (geofence) | Cost scales with total players and duplicates work adapters can do more cheaply from their native movement source; opt-in per-player subscription bounds cost to watched players. |
| Enforce confinement by cancelling the movement event | Move-veto does not exist cleanly off Bukkit; the SPI stays a pure notification and leaves enforcement (safe pull-back) to consumers, which is portable. |

## Consequences

- **Positive:** A reusable, platform-neutral movement primitive that unblocks cross-platform region
  confinement without any WorldGuard dependency, and is available to future move-driven features; it
  extends the existing neutral-event architecture rather than inventing a new pattern; the cheap path
  (block-granularity, opt-in, watched-set-only) is the default.
- **Negative / Trade-offs:** Adds surface to `rtp-api` and requires a concrete producer in every
  platform adapter, including the tick-sampled Fabric/NeoForge path (which observes at tick resolution
  rather than true event resolution). It introduces an ongoing per-adapter maintenance obligation for
  a new signal. Because it is D-005 gated, no core code lands until this ADR is approved.

## References

- [`docs/dev/PROPOSAL-tether-and-move-event-spi.md`](../dev/PROPOSAL-tether-and-move-event-spi.md) - the D-005 proposal this ADR records.
- [leafrtp-tether-addon-ADR-001](../../addons/LeafRTPTetherAddon/docs/adr/leafrtp-tether-addon-ADR-001-cross-platform-region-confinement.md) - the first consumer (cross-platform tether).
- [ADR-057](ADR-057-platform-agnostic-addon-spi.md) - Platform-agnostic addon SPI (how consumers load).
- [ADR-026](ADR-026-external-hook-api-surface.md) - External hook API surface (optional external region bounds).
- [ADR-054](ADR-054-rtprunnable-self-scheduling-thread-routing.md) - Self-scheduling thread routing (the dispatch/relocation threading model consumers reuse).
- `FabricEventBridge`, `NeoForgeEventBridge`, and the Bukkit `events/` package - the existing per-platform event bridges this SPI extends.
