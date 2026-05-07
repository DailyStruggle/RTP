# rtp-fabric-ADR-004 — DistanceManager API split at MC 1.21.5

- **Status:** Accepted
- **Date:** 2026-05-05
- **Supersedes:** none (extends rtp-fabric-ADR-001 layout, refines rtp-fabric-ADR-003 ticket implementation scope).

## Context

`rtp-fabric-ADR-003` introduced non-persistent chunk tickets via
`DistanceManager#addRegionTicket(TicketType, ChunkPos, int level, T value)` /
`#removeRegionTicket(...)`, implemented reflectively in
`V1_21_R1FabricVersionAdapter`. Its Javadoc and ADR-003 §4 claimed the
reflection target was *"stable across 1.21.x patch releases"*.

A user report against an MC **1.21.11** server (running the
`rtp-fabric-v1_21_R1` jar built for MC 1.21.1) produced a steady stream
of:

> `WARNING: [RTP][Fabric 1.21.1] applyTicket failed for chunk=…`
> `NoSuchMethodException: DistanceManager#addRegionTicket / #removeRegionTicket not found on net.minecraft.class_3898$class_3216`

After several rounds of strengthening the structural resolver (method →
field fallback for the `DistanceManager` accessor, relaxed parameter-type
checks on the 4-arg signature), a one-shot diagnostic dump of every
declared method on `class_3204` (`DistanceManager`) and its enclosing
`class_3898$class_3216` (`ChunkMap$DistanceManager`) revealed the actual
runtime shape:

```
-- net.minecraft.class_3204 --
    void method_17642(long, class_9259)        // = addTicket(long, Ticket)
    boolean method_38632(long)
    boolean method_38630(long)
    …no 4-arg `addRegionTicket`-shaped method…
```

The 4-arg `addRegionTicket(TicketType, ChunkPos, int, T)` pair was
**removed in MC 1.21.5** in favour of a redesigned API:

- `Ticket` becomes a concrete value object (yarn `ChunkTicket`,
  intermediary `class_9259`, Mojmap `net.minecraft.server.level.Ticket`)
  with constructors `(TicketType, int level)` and `(TicketType, int level, long timeout)`.
- `TicketType` drops its `<T>` type parameter (the `T value` field is
  baked into the `Ticket` itself).
- `DistanceManager` exposes only `addTicket(long packedChunkPos, Ticket)`
  and `removeTicket(long, Ticket)` — no method whose signature can be
  reflectively coerced into the old 4-arg shape.

Mappings cross-check (yarn 1.21.5+build.1, retrieved 2026-05-05):

```
ChunkTicket.<init>(ChunkTicketType, int)               // intermediary class_3228.<init>(class_3230, I)
ChunkTicket.<init>(ChunkTicketType, int, long)         // intermediary class_3228.<init>(class_3230, I, J)
ChunkTicketManager.method_17642(long, ChunkTicket)     // intermediary class_3204.method_17642(J, class_3228)
```

This is a genuine API break: no reflection trick on a 1.21.5+ runtime can
locate methods that have been deleted from the bytecode.

## Decision

1. **Split the 1.21 line at patch 5.** A new submodule
   `rtp-fabric/rtp-fabric-v1_21_R5` covers MC 1.21.5 through the 1.21.x
   tail. The existing `rtp-fabric-v1_21_R1` is narrowed in scope to MC
   1.21.0 – 1.21.4. Module names follow the existing
   `vMAJOR_MINOR_R<n>` convention (rtp-fabric-ADR-001), where `R5`
   identifies the patch number where the API break first appears
   (boundary marker, not a release identifier).

2. **Implement `V1_21_R5FabricVersionAdapter`** mirroring the R1 adapter
   shape, but routing `applyTicket` / `releaseTicket` through:
   - reflective `ServerChunkCache → DistanceManager` resolution (method-
     or-field, identical to R1);
   - structural lookup of `addTicket(long, Ticket)` / `removeTicket(long, Ticket)`
     by signature (void return, `(long, <reference>)` parameters), with
     declaration-order fallback for obfuscated names;
   - reflective `Ticket` constructor selection (`(TicketType, int)` first,
     then `(TicketType, int, long)`), so this adapter remains correct
     across the 1.21.5+ patch range without recompilation;
   - a structurally-discovered static `TicketType` constant (preferring
     `UNKNOWN`/`RTP`-named fields, otherwise any static of the right
     type) used as the ticket-type sentinel — equivalent to R1's
     `TicketType.create("rtp", …, 0)` because for our use case ticket-
     type *identity* is all that matters.

3. **Update `RTPFabricMod.adapterFqnFor`** to parse the patch component
   of `1.21.x` and route 1.21.0 – 1.21.4 to `v1_21_R1` and 1.21.5+ to
   `v1_21_R5`. Patch parsing tolerates pre-release suffixes
   (`1.21.5-rc1`) by stripping non-digits.

4. **Wire the new module** into `settings.gradle`,
   `rtp-plugin/build.gradle` (`implementation` so the bytecode is shaded
   into the final jar), and the parent `rtp-fabric` Gradle layout.

5. **Tighten R1 Javadoc** — replace the over-promised *"stable across
   1.21.x patch releases"* claim with the actual stable range
   (1.21.0 – 1.21.4) and a forward pointer to `v1_21_R5` /
   `rtp-fabric-ADR-004`. Keep all of R1's structural-resolver fallbacks
   and the diagnostic-dump path: they remain correct for genuine
   1.21.0 – 1.21.4 servers and are how this ADR's bisect was possible.

## Consequences

- **Positive.** RTP works correctly on MC 1.21.5+ Fabric servers. The
  adapter pattern (per-API-shape submodule rather than per-MC-version)
  scales naturally to future breaks: the next `DistanceManager` refactor
  produces `v1_21_R<patch>` or `v1_22_R1`, not a third reflection
  attempt inside an existing adapter.

- **Positive.** Both 1.21.x adapters use purely-structural reflection
  (no Mojmap/yarn name dependencies in the discovery path). They survive
  intermediary mappings at runtime — which is what production Fabric
  servers always run under, regardless of how the mod was compiled.

- **Negative — small.** The `rtp-plugin` shaded jar grows by one
  additional adapter's worth of bytecode (~10 KB). Acceptable: the
  alternative is shipping per-MC-version jars, which would multiply
  release artefacts by ~15× across the 1.20/1.21/26.1 lines.

- **Negative — minor.** The R5 adapter's reliance on reusing an existing
  `TicketType` static constant (instead of registering its own) means a
  scan of `level.dat#ForcedChunks` would not distinguish RTP-issued
  tickets from other tickets sharing that type. This is **not** an S-002
  hazard because the tickets are non-persistent (never written to
  `level.dat`); it only matters if a future diagnostic command wanted to
  count RTP-owned tickets specifically. Recorded as a known follow-up.

- **Operational.** Users on 1.21.5+ must rebuild and redeploy the
  shaded jar after this change lands. The dispatch update is
  source-incompatible with no-op deployments — pre-ADR-004 jars logged
  warnings on every chunk; post-ADR-004 jars on a 1.21.5+ runtime route
  to `V1_21_R5FabricVersionAdapter` and succeed silently.

## Alternatives considered

- **A. Bridge inside the R1 adapter** — add a third reflection pass
  inside `V1_21_R1FabricVersionAdapter` that detects the new shape, then
  reflectively constructs a `Ticket` and calls `addTicket(long, Ticket)`.
  Rejected: blurs the per-version-adapter contract from
  rtp-fabric-ADR-001 (one adapter per *MC API shape*), makes the R1
  class responsible for two unrelated APIs, and complicates future
  maintenance.

- **B. Pin RTP to 1.21.0 – 1.21.4 only.** Rejected: the user is on
  1.21.11 and the 1.21 line continues to receive Mojang point releases.
  Refusing 1.21.5+ would amount to abandoning the line.

- **C. Use an access-widener instead of reflection.** Considered, deferred:
  the reflective path already works and has been proven across two
  mappings systems and several patch levels. An access-widener buys a
  small perf win on first call only and adds a Loom-side concern. Worth
  revisiting once Loom 1.15+ stabilises (rtp-fabric-ADR-001 scope).

## References

- `rtp-fabric-ADR-001` — multiversion submodule layout.
- `rtp-fabric-ADR-003` — non-persistent chunk tickets (the R1 ticket
  implementation).
- Yarn 1.21.5+build.1 javadoc, classes
  `net.minecraft.server.world.ChunkTicket`,
  `net.minecraft.server.world.ChunkTicketManager`.
- The diagnostic dump that bisected the API break (logged from
  `V1_21_R1FabricVersionAdapter.resolveTicketMethodsOnce` on a
  1.21.11 runtime, 2026-05-05).



## Addendum (2026-05-05): auto-expiry & periodic refresh

Empirical follow-up while bringing the R5 adapter live on a 1.21.11 server: the runtime `DistanceManager` (`class_3204`) exposes only **`addTicket(long, Ticket)`** (`method_17642`) — there is no matching `removeTicket(long, Ticket)` in 1.21.5+. Tickets carry their own `ticksLeft` field and auto-expire via `tick()` / `isExpired()`.

Consequence for the R5 adapter:

- The structural resolver requires only `addTicket`; no remove pair is searched for. (Earlier drafts that required a `(long, Ticket)` add/remove pair never resolved on 1.21.11.)
- `Ticket` is constructed via the 3-arg ctor `(TicketType, int level, long ticksLeft)` with `ticksLeft = 200` (10 s) so the lifetime is independent of the chosen `TicketType`'s default expiry.
- `applyTicket(level, cx, cz)` issues the add and registers `(level-key, chunkKey)` in a static `ConcurrentHashMap` ("active set").
- A periodic refresh — registered by `RTPFabricMod.onInitialize` via `RTP.scheduler.runTaskTimer(..., 100, 100)` (every 5 s) — calls `FabricVersionAdapter.tickRefresh()`. The R5 adapter overrides this to re-issue `addTicket` for every entry still in the active set, so each held chunk always has ≥ 5 s remaining lifetime.
- `releaseTicket(level, cx, cz)` simply removes the entry from the active set; the chunk auto-expires within `REFRESH_TICKS_LEFT`. Teleportation is therefore not guaranteed to be timely on the very first `releaseTicket` call (per user requirement: "not guaranteed to be within a timely manner but some chunks must always be ready").
- All other adapters inherit the default no-op `tickRefresh()` from `FabricVersionAdapter`.

S-002 status preserved: ticket type is non-persistent (UNKNOWN-style); active set is in-memory only.

## Addendum 2 (2026-05-05): direct typed implementation (supersedes Addendum 1)

The Addendum-1 strategy (reflective `addTicket(long, Ticket)` + auto-expiry + periodic refresh) was based on a misidentification: the structural resolver kept latching onto unrelated `(long, NonPrimitive) -> void` methods on whatever sub-manager it walked into (PoiManager / ChunkMap.DistanceManager / ChunkTicketManager). The user's diagnostic dump on a 1.21.11 server eventually revealed that the parameter type the resolver had been treating as `Ticket` (intermediary `class_9259`) is actually an **interface with no fields and only static factories taking `Object`/`String`/`Supplier`/`Function`/`Consumer`** -- almost certainly Mojang's `DataResult`-shaped utility type, not a chunk-ticket value at all.

Ground truth, recovered by `javap`-ing the Loom-cached Mojmap-remapped 1.21.5 server jar:

- `ServerChunkCache` (Mojmap) exposes **public** `addTicketWithRadius(TicketType, ChunkPos, int)` and `removeTicketWithRadius(TicketType, ChunkPos, int)` directly. **Both add and remove exist; no auto-expiry workaround is needed.**
- `TicketType.UNKNOWN` is a public static constant; `TicketType` itself is now a record `(long timeout, boolean persist, TicketType.TicketUse use)` -- no generic type parameter.
- The legacy `DistanceManager#addRegionTicket` is gone, but it was never the right entry point for callers outside the chunk-system internals anyway.

Consequence for the R5 adapter: rewritten as direct typed Mojmap calls, no reflection, no active-set, no periodic refresh. `applyTicket` -> `cache.addTicketWithRadius(TicketType.UNKNOWN, new ChunkPos(cx, cz), 31)` and `releaseTicket` -> the matching remove. Adapter dropped from 678 LOC to ~145 LOC.

The `FabricVersionAdapter#tickRefresh()` SPI hook and the 5-second scheduler entry in `RTPFabricMod.onInitialize` remain in place (default no-op; cheap when the active set is unused) so a future adapter that genuinely needs auto-expiry can re-enable it without touching `rtp-plugin`.

S-002 status preserved: `TicketType.UNKNOWN` is non-persistent (`persist=false`), and `addTicketWithRadius` does not write to `level.dat`.
