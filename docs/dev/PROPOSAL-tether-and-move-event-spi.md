# Proposal (D-005): Platform-Neutral Player-Move Event SPI + Tether (Region-Confinement) Addon

**Status:** Approved (2026-07-25)
**Date:** 2026-07-25
**Rule:** D-005 (propose before implementation; this change crosses module boundaries and adds new
`rtp-api` surface).

This proposal has two layers:

1. A **core** change: add a platform-neutral player-move event SPI to `rtp-api` / `rtp-core` and
   implement it in every platform adapter. Recorded as [ADR-075](../adr/ADR-075-platform-neutral-player-move-event-spi.md).
2. An **addon** that consumes it: `LeafRTPTetherAddon`, which keeps a player inside the RTP region
   they were teleported into ("tether" = region confinement). Recorded as
   [leafrtp-tether-addon-ADR-001](../../addons/LeafRTPTetherAddon/docs/adr/leafrtp-tether-addon-ADR-001-cross-platform-region-confinement.md).

Only the addon **scaffold** (config + lifecycle no-op) exists today. This proposal is **approved** -
the D-005 gate is cleared and the rollout in section 6 may proceed. The move-event surface lands in the
server-independent `rtp-api`, consistent with the tentative plan to later decouple `rtp-api` into a
standalone library.

## 1. Motivation

Servers want to keep a randomly-teleported player inside a bounded area (competitor plugins call it a
"zone"/"arena"). On Bukkit this is trivially done with WorldGuard's `entry`/`exit` region trap, but
WorldGuard is Bukkit-family only - there is no WorldGuard on Fabric or NeoForge, both first-class RTP
platforms. To make confinement work on every platform, RTP must own the mechanism.

Two observations shape the design:

- A "zone" is ~90% just an existing **RTP region** (a bounded area with shape/geometry). The
  non-redundant capability is **confinement**, which we name **tether**. No new area model is needed.
- Confinement needs a **move signal** RTP owns. RTP already has platform-neutral teleport/queue/chunk
  events dispatched from `rtp-core` via per-platform bridges; the only missing piece is movement.

## 2. Affected classes / modules

**Core (new surface - the D-005 subject):**

- `rtp-api`: a new neutral player-move event type + an opt-in per-player subscription surface, added
  alongside the existing event/hook APIs. (Exact type names to be settled during implementation
  review; the SPI shape is: subscribe(player) / unsubscribe(player) + a callback carrying player,
  previous block coord, new block coord.)
- `rtp-core`: dispatch/plumbing for the new event, mirroring existing event dispatch.
- Platform adapters, one concrete producer each:
  - Bukkit family (`rtp-plugin` `events/` package): filter `PlayerMoveEvent` to block-granularity.
  - `rtp-fabric` (`FabricEventBridge`): sample position on the server tick, diff against last block.
  - `rtp-neoforge` (`NeoForgeEventBridge`): same tick-sampled diff.

**Addon (already scaffolded, consumes the core SPI):**

- `addons/LeafRTPTetherAddon/` - `RTPTetherAddon`, `TetherKeys`, `addons/tether.yml`, service file,
  `README.md`, `REQUIREMENTS.md`, `docs/adr/leafrtp-tether-addon-ADR-001-...md`. Currently a no-op.

**Docs / index (this change, already applied):**

- `docs/adr/ADR-075-...md`, this proposal, `docs/adr/README.md` (index + subproject rows),
  `settings.gradle`, `docs/dev/ROADMAP.md`, and the `GLOSSARY.md` synonym note for "zone".

## 3. Intended before/after structure

**Before:** RTP has no move signal. Region confinement is only achievable per-platform (WorldGuard on
Bukkit) with no Fabric/NeoForge story. The `LeafRTPZoneAddon` scaffold framed the feature as
"named zones + optional claim-plugin bound", which is redundant with RTP regions and does not solve
confinement.

**After:** RTP exposes one normalized, opt-in, per-player move event across all platforms. The renamed
`LeafRTPTetherAddon` consumes it to enforce region confinement identically everywhere:

- **Membership** is edge-triggered by RTP's own `PostTeleportEvent` (arm on teleport into a tethered
  region; disarm on a teleport out or explicit release). The move event is only the *enforcement*
  channel.
- **Watching** is limited to the tethered set (opt-in subscription), so per-move cost scales with
  confined players, not total online.
- **Containment** is a chunk-free math test against the region's own geometry.
- **Enforcement** is safe pull-back: on a boundary crossing the player is returned to a fresh
  verified destination inside the region (drawn from the supply pipeline, passing S-001..S-005; never
  silently dropped, S-004). Pull-back, not move-veto, because veto is Bukkit-only.
- **Persistence** (optional) via the core database interface so tethers survive restart/relog.
- External region sources (WorldGuard/claim mods) remain optional bounds via the ADR-026 hook, never
  required.

## 4. Relevant requirements / ADRs

- [ADR-075](../adr/ADR-075-platform-neutral-player-move-event-spi.md) - the core SPI (this proposal).
- [leafrtp-tether-addon-ADR-001](../../addons/LeafRTPTetherAddon/docs/adr/leafrtp-tether-addon-ADR-001-cross-platform-region-confinement.md) + [`addons/LeafRTPTetherAddon/REQUIREMENTS.md`](../../addons/LeafRTPTetherAddon/REQUIREMENTS.md) - the consumer.
- [ADR-057](../adr/ADR-057-platform-agnostic-addon-spi.md) - addon SPI (`RTPAddon` + ServiceLoader).
- [ADR-026](../adr/ADR-026-external-hook-api-surface.md) - external hook surface (optional bounds).
- [ADR-054](../adr/ADR-054-rtprunnable-self-scheduling-thread-routing.md) - thread routing reused for pull-back.
- [ADR-002](../adr/ADR-002-h2-sqlite-over-flat-file-cache.md) - the database interface tethers persist through.
- S-001..S-005 (REQUIREMENTS.md section 3) - pull-back destinations must satisfy all teleport safety rules; no chunk I/O on tick/region threads; no silently dropped enforcement.

## 5. Risks and trade-offs

- **Movement is a performance footgun.** Mitigated by (a) block-granularity, (b) opt-in per-player
  subscription so only tethered players are watched, (c) no chunk I/O in the signal path.
- **Fabric/NeoForge observe at tick resolution**, not true event resolution. Acceptable for
  confinement (a one-tick-late pull-back is fine); documented as a known limitation.
- **New per-adapter maintenance obligation.** Every current and future platform adapter must produce
  the signal. This is the cost of a genuinely cross-platform primitive; the alternative (Bukkit-only)
  fails the goal.
- **Behavioral difference from WorldGuard.** Pull-back is a visible relocation rather than a silent
  wall with a `deny-message`. Judged acceptable (arguably clearer to the player); can be paired with a
  configurable message later.
- **Scope creep guard.** The SPI is a pure notification; it does not add move-veto or any Bukkit-only
  concept to the neutral surface. Enforcement policy stays in the addon.

## 6. Proposed rollout (only after approval)

1. Land the `rtp-api` event type + subscription surface and `rtp-core` dispatch, with tests.
2. Implement the Bukkit producer (block-filtered `PlayerMoveEvent`); test on Paper + Folia
   (region-thread dispatch).
3. Implement the Fabric and NeoForge tick-sampled producers; test on each.
4. Implement the tether addon enforcement against the new SPI (arm/disarm, containment, safe
   pull-back, optional DB persistence), with tests; flip it from no-op to active.
5. Full multi-module build (`./gradlew build`) before submit.

## 7. Decision requested

Approve (or amend) the core move-event SPI shape in section 2/3 so implementation can proceed under
D-005. Until then, the tether addon ships as a safe no-op and no core code is written.
