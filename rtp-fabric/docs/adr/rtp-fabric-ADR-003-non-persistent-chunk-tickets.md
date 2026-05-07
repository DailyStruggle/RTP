# rtp-fabric-ADR-003 — Non-persistent chunk tickets via `DistanceManager.addRegionTicket`

- Status: Accepted
- Date: 2026-05-05
- Supersedes: —
- Superseded by: —

## Context

The Fabric platform adapter originally implemented `RTPWorld.setForceLoadedImpl(cx, cz, forceLoad)` by delegating to vanilla `ServerLevel#setChunkForced(cx, cz, forceLoad)`. That call mutates the server's `ForcedChunks` set, which is **persisted to `level.dat`** — the same backing store that `/forceload add` writes through.

This is asymmetric to every other supported platform:

- **Bukkit / Spigot / Paper** use `World#addPluginChunkTicket(Plugin)` — non-persistent, cleared automatically on plugin disable / server shutdown.
- **Folia** inherits Bukkit semantics.
- **Fabric** had no equivalent abstraction in the platform glue, so the simplest available API (`setChunkForced`) was used during platform bring-up.

Two consequences manifested during the 2026-05-05 Fabric `/rtp` smoke test:

1. After a watchdog crash mid-pipeline, the next server start logged `13 force loaded chunks were found in minecraft:overworld at: [...]` — RTP-owned chunks survived the unclean shutdown via `level.dat` persistence and re-applied on world load. This is an **S-002 violation** (`No permanently force-loaded chunks`): even if every code path correctly pairs `keep(true)` / `keep(false)`, persistence makes graceful release impossible across crashes.
2. RTP-owned forced chunks are indistinguishable from admin-issued `/forceload add` entries (both live in the same vanilla `ForcedChunks` set) — `forgetChunks()` on shutdown cannot safely clear them without risking the admin's manual entries.

The same hazard does not exist on Bukkit/Folia because `addPluginChunkTicket` allocates a per-plugin `Ticket<Plugin>` in `DistanceManager` that is keyed by the plugin instance and cleared at `JavaPlugin#onDisable` (or JVM exit).

## Decision

Issue chunk tickets on Fabric via the **chunk-system primitive** (`DistanceManager#addRegionTicket(TicketType, ChunkPos, int level, T value)`) using a custom non-persistent `TicketType<ChunkPos>`, and route this through the existing per-MC-version SPI (`FabricVersionAdapter`).

Concretely:

1. **SPI surface** — Two methods are added to `FabricVersionAdapter`:
   ```java
   default CompletableFuture<Void> applyTicket(ServerLevel level, int cx, int cz);
   default CompletableFuture<Void> releaseTicket(ServerLevel level, int cx, int cz);
   ```
   Default implementations return failed futures (`UnsupportedOperationException`) per S-006 (no silent no-ops).

2. **Reference implementation (`V1_21_R1FabricVersionAdapter`)**
   - Process-wide `TicketType<ChunkPos> RTP_TICKET_TYPE = TicketType.create("rtp", Comparator.comparingLong(ChunkPos::toLong), 0)`. Timeout `0` = "no auto-expiry, lives until removed" (matches Bukkit `addPluginChunkTicket` lifetime).
   - `TicketType.create(...)` produces a non-persistent type. The persistent flag is opt-in via the deprecated `TicketType#createPersistent` factory; the public `#create` factory never marks the type as persistent.
   - Reflective access to `DistanceManager#addRegionTicket` and `#removeRegionTicket` (both package-private on Mojmap), resolved once on first use via `cache.getClass().getMethod("getDistanceManager")` plus `Class#getDeclaredMethod` walking up the class hierarchy. Handles cached in `volatile Method` fields.
   - Ticket level constant `31` (matches `TicketType.FORCED`'s load level on 1.21 — chunk fully loaded, no entity ticking).

3. **Caller wiring** — `FabricRTPWorld.setForceLoadedImpl` is rewritten to delegate to `FabricVersionAdapterRegistry.peek().applyTicket(...)` / `releaseTicket(...)` inside `MinecraftServer#submit`, replacing the `world.setChunkForced(...)` call. The class- and method-level Javadocs are updated to document why `setChunkForced` is forbidden on this path.

## Consequences

### Positive

- **S-002 hazard closed.** A server crash drops every RTP-issued ticket because `DistanceManager` is in-memory and not serialised to disk. Restart starts from a clean slate.
- **Admin/plugin separation.** RTP-owned tickets and admin-issued `/forceload add` entries live in disjoint `TicketType` namespaces; `forgetChunks()` (and `/forceload query`) cannot accidentally cross-contaminate.
- **Bukkit parity.** RTP's chunk-ticket lifetime contract is now uniform across Bukkit, Folia, and Fabric.
- **Observable through `MemoryTracker`.** Existing `chunkTickets` ref-counting in `RTPWorld` continues to work unchanged because the `setForceLoadedImpl` contract is preserved.

### Negative / Trade-offs

- **Reflection.** `DistanceManager#addRegionTicket` is package-private on Mojmap, so the v-submodule uses one-shot reflection rather than a direct call. An access-widener (`accesstransformer.cfg` or Fabric `accesswidener`) is the longer-term fix, deferred until the Loom build is stable per `MULTI_PLATFORM_PLAN.md` line 60. The reflection is consistent with `FabricRTPWorld.resolveReflectionOnce`'s existing pattern.
- **Per-version SPI work.** Each future `rtp-fabric-vXX_YY_R1` submodule must override the two new SPI methods. Default failed-future behaviour ensures unimplemented adapters fail loudly during ticket application rather than silently degrading. v1_20_R1 and v26_1_R1 currently inherit the default and will throw on `keep(true)` until ported — see "Open work" below.
- **Migration of pre-existing leaked entries.** Servers that ran an earlier Fabric build will still have the persistent RTP-owned entries in `level.dat`. There is no automatic migration; admins must `/forceload remove` the leaked entries (or use `/forceload remove all` if no admin entries exist). Documented in `CHANGELOG.md`.

## Alternatives considered

### A. Defensive cleanup on world load + clean shutdown

Snapshot `world.getForcedChunks()` to a side file `world/rtp-forced-chunks.dat` on apply, walk it on shutdown / next world load to reverse-apply `setChunkForced(..., false)` for stale RTP-owned entries.

**Rejected.** Doesn't change the runtime hazard — RTP entries still appear in `/forceload query` while live and still race against admin `/forceload`. Adds a side-file format to maintain. Crash between apply and side-file write still leaks. Strictly worse on every axis except diff size.

### B. Access-widener for `DistanceManager`

Ship a Fabric `accesswidener` declaring `addRegionTicket` / `removeRegionTicket` public. Eliminates reflection.

**Deferred, not rejected.** The Loom build is currently unstable in this repo (`MULTI_PLATFORM_PLAN.md` line 60); adding an accesswidener now would couple this fix to that work. Once Loom is green, the reflection in the v-adapter should be replaced with a direct call. Tracked under Step E-tail / Phase 2 Step F.

### C. Use `TicketType.UNKNOWN` (vanilla's "load chunk for command" type)

`TicketType.UNKNOWN` is non-persistent and public. Could be used directly without reflection or a new type.

**Rejected.** `UNKNOWN` is reserved by vanilla for command-driven loads (`/tp`, `/spreadplayers`) and has a very short timeout (1 tick on 1.21.1). Doesn't match RTP's "hold chunk loaded for the duration of the safety pipeline" requirement, and reusing it would conflict with vanilla's own usage.

## Open work

- `V1_20_R1FabricVersionAdapter` and `V26_1_R1FabricVersionAdapter` inherit the SPI default (failed future). Port the `V1_21_R1` implementation to both before declaring those MC versions ready for `/rtp`. Tracked under `MULTI_PLATFORM_PLAN.md` Step E-tail.
- Replace reflection with an accesswidener once Loom is green.
- Add a JUnit test that asserts `RTP_TICKET_TYPE` is non-persistent (introspect `TicketType#PERSISTENT` set) once a Loom-aware test harness exists.

## References

- Issue: `MULTI_PLATFORM_PLAN.md` Step E3 follow-up #1 (S-002 audit).
- Pre-fix evidence: 2026-05-05 user smoke-test log line `13 force loaded chunks were found in minecraft:overworld at: [...]`.
- Related ADRs: `rtp-fabric-ADR-001` (multiversion submodule layout), `rtp-fabric-ADR-002` (platform in scope), `ADR-022` (region cache key).
- Relevant requirements: `REQ-RTP-S-002` (no permanently force-loaded chunks), `REQ-RTP-S-006` (no silent NPE on early-API misuse).
