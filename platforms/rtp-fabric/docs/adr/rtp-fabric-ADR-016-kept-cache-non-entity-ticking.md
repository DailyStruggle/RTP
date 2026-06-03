# rtp-fabric-ADR-016 - Kept-cache chunks pin at non-entity-ticking level (32), not ENTITY_TICKING (30)

- **Status:** Accepted (experimental; pending devstack acceptance)
- **Date:** 2026-06-02
- **Supersedes:** the kept-cache ticket-level decision in `rtp-fabric-ADR-006` (the radius/distance-vs-level math and the non-persistent `TicketType` shape from ADR-006 are retained; only the target effective level changes).
- **Scope:** `rtp-fabric-v1_21_R1`, `rtp-fabric-v1_21_R5`, `rtp-fabric-v1_21_R11`, `rtp-fabric-v26_1_R1`, `rtp-fabric-v26_2_R1`, and the ported `rtp-neoforge-v1_21_R1`. `rtp-fabric-v1_20_R1` already pins at this level (distance 1) per its 2026-05-08 entity-ticking-leak fix and is unchanged.

## Context

`RegionQueueManager.keptLocations` (the "hot queue" / L1 cache) holds pre-generated locations whose chunks are kept loaded via `RTPWorld#keepChunkAt` -> `FabricVersionAdapter#applyTicket`. `rtp-fabric-ADR-006` set the kept-cache ticket to effective level **30** (`ENTITY_TICKING`), chosen at the time to match Bukkit's `World#addPluginChunkTicket` end-state.

A kept chunk has no player in it until a `/rtp` consumer is teleported there. At `ENTITY_TICKING` (level 30) such a chunk nevertheless runs the full simulation pipeline - mob spawning, entity AI, scheduled block ticks, fluid/redstone, block-entity ticking - for every entry in the hot queue. On a server with a large kept cache this is pure MSPT spent on chunks no player occupies. The 1.20.1 adapter already hit a severe form of this: `ENTITY_TICKING` kept chunks accumulated vanilla-internal `CompletableFuture` chains that were reachable from the held ticket and never released (~13 MB/s growth), forcing a drop to distance 1 (level 32) on 2026-05-08.

The chunk only needs to **tick** once a player actually arrives. Until then RTP only reads blocks from it (`RTPChunk#isSafe` at the teleport-commit re-check, ADR-016 in the global sequence), which is available at any loaded level.

## Decision

Pin kept-cache chunks at effective ticket level **32** (`FULL`/BORDER, below `ENTITY_TICKING`) by passing **distance/radius = 1** into the per-version chunk-ticket API:

- R1 / NeoForge R1 (`DistanceManager#addRegionTicket(TicketType, ChunkPos, int distance, T)`): `RTP_TICKET_DISTANCE = 1`.
- R5 / R11 / 26.x (`ServerChunkCache#addTicketWithRadius(TicketType, ChunkPos, int radius)`): `RTP_TICKET_RADIUS = 1`.

The effective level is `ChunkMap.MAX_CHUNK_DISTANCE - distance = 33 - 1 = 32` on every currently-supported runtime. At level 32 the chunk is:

- **Loaded and pinned** - `ServerChunkCache#hasChunk` returns true, block reads valid for `isSafe`. This is one step inside the eviction boundary (level 34), so it is not dropped while the non-expiring ticket is held.
- **Not entity-ticking** - mob spawning and entity AI do not run, so a player-less kept chunk costs effectively zero MSPT.

When a player is teleported in, vanilla adds the player's own view-distance ticket, which promotes the chunk to `ENTITY_TICKING` in the same operation. Because the chunk is already `FULL`, this is a level-bump, not a load/generate - immediacy of the `/rtp` response is preserved.

The non-persistent, no-timeout `TicketType` shape established by ADR-006 (and the R11/26.x flag-bitfield variant) is unchanged; S-002 (no permanently force-loaded persisted chunks) remains satisfied.

### Why not level 33 (distance 0)

Level 33 is the only strictly non-ticking loaded level, but it sits exactly on the eviction boundary (level 34 = unloaded). The user has previously observed chunk drops/reloads when the ticket level was insufficient. Level 32 keeps a one-level margin against eviction while still suppressing entity ticking, and has in-repo empirical precedent (the 1.20.1 fix). Level 33 is therefore deliberately avoided.

## Consequences

**Positive:**

- Kept-cache chunks no longer consume per-tick simulation budget while waiting for a consumer; MSPT under a large hot queue drops.
- Immediacy is preserved: teleport target is already `FULL`, promoted to ticking by the player's arrival ticket with no regeneration.
- Brings 1.21.x / 26.x / NeoForge in line with the 1.20.1 adapter, which already pins at level 32.

**Negative / risk (why this is experimental):**

- Level 32 is below the well-trodden `ENTITY_TICKING` path; chunk-system mods (C2ME, Lithium) reimplement ticket propagation and may behave differently at this tier. Requires devstack verification before it is trusted in production.
- If a future runtime renumbers the level ladder, `33 - radius` may not yield 32; the per-version adapters express the value as a literal radius/distance and must be re-checked at port time (consistent with ADR-006's per-version SPI note).

**Neutral:**

- `MemoryTracker` accounting and `RTPWorld#chunkTickets` ref-counting are unchanged.
- The non-persistent `TicketType` shape and S-002 guarantee are unchanged.

## Verification

- `.\gradlew build` compiles all touched adapters.
- Devstack acceptance (pending): on a Fabric backend, after burning one kept-cache cycle, sample for each `keptLocations` entry that (a) `world.getChunkSource().hasChunk(cx,cz)` stays true for the entry's lifetime (not dropped), and (b) the chunk holder level reads 32 (non-entity-ticking) before a player arrives and 31/30 immediately after a `/rtp` lands a player there. Repeat with C2ME installed.

## References

- `rtp-fabric-ADR-003`, `-004`, `-006` - prior ticket-flow decisions (non-persistent type, API split, radius-vs-level math).
- `rtp-fabric-v1_20_R1` adapter comment (2026-05-08) - the entity-ticking CF-graph leak that first motivated level 32 on 1.20.1.
- `docs/adr/ADR-016-anvil-subsystem.md` section 5 - the live `isSafe` re-check that block-readable kept chunks satisfy.
- REQ-RTP-S-002 (no permanently force-loaded chunks), REQ-RTP-S-005 (no main-thread chunk loading) - `docs/dev/REQUIREMENTS.md` section 3.
