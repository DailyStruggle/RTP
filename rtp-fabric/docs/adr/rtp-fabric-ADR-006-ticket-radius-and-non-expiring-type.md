# rtp-fabric-ADR-006 — Chunk-ticket radius / distance is `3`, not `31`

- **Status:** Accepted
- **Date:** 2026-05-06
- **Supersedes:** none (addendum to `rtp-fabric-ADR-003` and `rtp-fabric-ADR-004`)
- **Scope:** `rtp-fabric-v1_21_R1`, `rtp-fabric-v1_21_R5` (and any future per-version adapter that pins kept-cache chunks via the vanilla ticket APIs)

## Context

`RegionQueueManager.keptLocations` ("L1" / "hot queue") relies on the
invariant that every entry has its chunk currently loaded — `keep(true)` is
applied via `RTPWorld#keepChunkAt`, which on Fabric routes through
`FabricVersionAdapter#applyTicket`. The Bukkit/Folia equivalent is
`World#addPluginChunkTicket`, which produces an effective ticket level of
`30` (`ENTITY_TICKING`) — chunk fully loaded, block reads valid, no
auto-expiry until the matching `removePluginChunkTicket` is called.

The Fabric adapters introduced in ADR-003 / ADR-004 attempted to mirror
this contract but used the wrong numeric argument:

- `V1_21_R1FabricVersionAdapter` passed `31` into the **distance** slot of
  `DistanceManager#addRegionTicket(TicketType, ChunkPos, int distance, T value)`.
- `V1_21_R5FabricVersionAdapter` passed `31` into the **radius** slot of
  `ServerChunkCache#addTicketWithRadius(TicketType, ChunkPos, int radius)`.

Both APIs convert their numeric argument to an effective ticket level via
`effectiveLevel = ChunkMap.MAX_CHUNK_DISTANCE - radius` (i.e. `33 - radius`).
Vanilla `TicketType.FORCED` uses `radius = 3` → effective level `30`
(`ENTITY_TICKING`). The Fabric adapters' `radius = 31` either:

1. **R1 path (distance argument):** would have requested an effective level
   of `2`, which is at the spawn-chunks tier and is clamped/rejected by the
   chunk system on the runtime tested. The ticket either never lands at
   the expected level or fails the bounds check entirely; the chunk is not
   pinned at `FULL`.
2. **R5 path (radius argument):** would force-load a `(2·31 + 1)² = 3969`
   chunk square per kept location, which is also clamped/rejected and
   competes catastrophically with player tickets if accepted.

In both cases the `RTPWorld#chunkTickets` ref-count map still believes the
ticket is held (the adapter returned a successful future), but vanilla has
either never pinned the chunk or has evicted it as soon as no other ticket
overlapped. This silently breaks the `keptLocations ⇒ chunk loaded`
invariant — users observe `/rtp` lag spikes (the consumer path falls back
to `FabricRTPWorld.getChunkAt`, which synchronously generates the chunk
on the server tick, the same ~14 ms cost analysed in
`rtp-fabric-ADR-005`'s context).

The misleading driver was the original Javadoc:

> Ticket level we apply. `31` matches `TicketType.FORCED`'s load level on 1.21
> — chunk fully loaded, no entity ticking.

This conflates the **distance/radius** argument that the public API takes
with the **internal effective ticket level**. They are related by
`level = 33 - distance`, not equal.

## Decision

Both adapters use **`radius = 3`** (parity with `TicketType.FORCED` and
with Bukkit's `addPluginChunkTicket` end state):

```
private static final int RTP_TICKET_DISTANCE = 3;   // R1 — addRegionTicket arg #3
private static final int RTP_TICKET_RADIUS   = 3;   // R5 — addTicketWithRadius arg #3
```

This resolves to effective ticket level `30` (`ENTITY_TICKING`) on every
1.21.x runtime, which is the documented FULL-tracked tier.

The R1 adapter retains its lazily-allocated custom `TicketType.create("rtp",
…, /*timeout*/ 0)` (non-persistent, no-auto-expiry) per ADR-003.

The R5 adapter constructs its own static `TicketType` instance rather
than reusing `TicketType.UNKNOWN`. Verified via `javap` on the
Mojmap-remapped 1.21.5 server jar
(`minecraft-merged-1.21.5-loom.mappings…-v2.jar`):

```
public final class net.minecraft.server.level.TicketType extends java.lang.Record {
  public TicketType(long timeout, boolean persist, TicketType.TicketUse use);
  public static final long NO_TIMEOUT;
  public static final TicketType FORCED;   // (timeout=0, persist=true,  use=LOADING_AND_SIMULATION)
  public static final TicketType UNKNOWN;  // (timeout=1, persist=false, use=LOADING)
}
public enum TicketType.TicketUse { LOADING, SIMULATION, LOADING_AND_SIMULATION; }
```

`UNKNOWN` carries a **1-tick** auto-expiry (not the historically
rumoured 1 s) and `LOADING`-only — both wrong for kept-cache pinning:
the chunk evicts on the next tick after `applyTicket`, and `LOADING`
does not enable entity ticking, so the chunk would not match the
Bukkit `addPluginChunkTicket` `ENTITY_TICKING` end state even if the
timeout were ignored.

The R5 adapter therefore allocates a single static instance:

```java
private static final TicketType RTP_TICKET_TYPE =
        new TicketType(TicketType.NO_TIMEOUT, /*persist=*/ false, TicketUse.LOADING_AND_SIMULATION);
```

This is `TicketType.FORCED`'s shape minus the `persist = true` flag —
non-persistent (S-002 preserved, no `level.dat` write), no auto-expiry
(matches the R1 adapter's `TicketType.create("rtp", …, 0)`), and
`LOADING_AND_SIMULATION` (effective `ENTITY_TICKING` after the radius-3
math). The public record constructor is sufficient; no registry call,
and therefore no class-init ordering hazard against the chunk
subsystem. Identity-equality across `addTicketWithRadius` /
`removeTicketWithRadius` is preserved because every adapter call
reuses the same static instance.

## Consequences

- **Positive.** `keptLocations` invariant restored on Fabric: chunks
  remain `FULL`-loaded for the lifetime of their kept-cache entry. The
  consumer `/rtp` path no longer pays a synchronous chunk-generation
  cost at teleport time. Combined with `rtp-fabric-ADR-005`'s anvil
  pre-filter parity, the Fabric tick profile matches Bukkit/Folia for
  both refill (anvil-served) and consumer (kept-pinned) paths.
- **Negative.** None — `radius = 3` is the same value vanilla itself
  uses for `TicketType.FORCED` and that Bukkit ultimately hands to the
  chunk system. The change is strictly bug-for-bug.
- **Neutral.** Per-version SPI documented in code: every future
  per-version adapter implementing `applyTicket` MUST pass `3` (or
  whatever value matches the runtime's `ChunkLevel.FULL` threshold)
  into the public API's distance/radius slot, not the underlying
  effective level number.

## Verification

- `:rtp-fabric:rtp-fabric-v1_21_R1:compileJava` and
  `:rtp-fabric:rtp-fabric-v1_21_R5:compileJava` BUILD SUCCESSFUL after
  the change.
- Operator-side smoke test on a real Fabric server (per ADR-005's same
  template): after burning through one kept-cache cycle, sample
  `world.getChunkSource().hasChunk(cx, cz)` for each `keptLocations`
  entry. Expected: `true` for the entire lifetime of the kept entry,
  matching Bukkit. Pre-fix: `false` shortly after the ticket is issued.
- `MemoryTracker` accounting unchanged; no changes to `RTPWorld#chunkTickets`
  ref-counting.

## References

- `rtp-fabric-ADR-003-non-persistent-chunk-tickets.md` (R1 path; original
  misuse).
- `rtp-fabric-ADR-004-distance-manager-api-split.md` (R5 path; same
  misuse re-introduced under the new API surface).
- `rtp-fabric-ADR-005-anvil-prefilter-parity.md` (companion fix on the
  refill path).
- `docs/dev/LESSONS_LEARNED.md` — entry: *Vanilla `addRegionTicket` /
  `addTicketWithRadius` third arg is distance/radius, not level.*
