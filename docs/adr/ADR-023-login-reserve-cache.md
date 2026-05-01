# ADR-023 — Login Reserve Cache (`loginLocations`)

- **Status**: Accepted
- **Date**: 2026-04-30
- **Supersedes**: —
- **Related**: ADR-015 (stale-chunk guard / count-bound pipes), ADR-016 (anvil subsystem), `REQ-RTP-S-005` (no chunk loading on the main thread)

## Context

Players who teleport via the existing `rtp.onevent.firstjoin` / `rtp.onevent.join`
permissions hit the regular `keptLocations` cache on join. On a busy server the
join-storm at peak hours can drain the cache faster than the per-region
`Region.execute()` deficit loop can refill it, causing measurable join-time
latency spikes for permission-gated auto-RTP.

A user request was raised to add a *secondary kept cache, sized to the remaining
empty player slots*, dedicated to login-time teleports.

## Decision

Introduce a third, optional location buffer per region — `loginLocations` —
maintained on its **own behavioral loop**, decoupled from `Region.execute()`.

### Scope

- Allocated **only** on the region attached to the default world
  (`Bukkit.getWorlds().get(0)`); `null` on every other region.
- Allocated **only** when `PerformanceKeys.loginCacheEnabled = true`.
- Hard-capped at `loginCacheCap` (or `Bukkit.getMaxPlayers()` snapshotted at
  plugin enable when `loginCacheCap = 0`).

### Fill loop (mirrors, does not modify, the existing kept-promotion path)

A new `LoginCacheTask` *mirrors* (does not refactor) the unkept→kept promotion
block from `Region.execute()`:

1. `unkeptLocations.pollSilently()` — same DB-key re-offer race protection.
2. Async chunk load via `RTPWorld.getChunkAtAsync`.
3. Second-pass `RTPChunk.isSafe` verification on the chunk's owning region
   thread (Folia) or inline (Spigot/Paper). Fail-closed on any verify
   exception.
4. On success: create `ChunkReservation`, `loginLocations.offer(...)`.
5. On rejection / unsafe verdict: close reservation, return location to
   `unkeptLocations` (or purge the now-unsafe DB row via `offer+poll` on the
   unsafe path).

The fill loop is triggered by exactly two events:

- **Startup burst**: `RTPBukkitPlugin.onEnable` → `LoginCacheTask.promoteUpTo(cap - online)`.
- **`PlayerQuitEvent`**: `OnPlayerQuit` → `LoginCacheTask.promoteUpTo(1)` per
  region with `loginLocations != null`.

`Region.execute()` is **not modified**. The existing kept-promotion path is
preserved bit-for-bit, protecting the invariants covered by
`ReqRtpS004NullChunkAttributionTest` and the ADR-015 / ADR-016 design.

### Consumption (join-time)

`OnEventTeleports.onPlayerJoin` invokes a new private `primeFromLoginCache`
helper before the existing `teleportAction` call. When the player's region has
a non-empty `loginLocations`, one entry is polled and stored in the player's
`fastLocations` future, so the existing `TeleportPipelineTask` consumes it
instantly without re-loading chunks.

The existing cooldown gate in `onPlayerJoin` is preserved; if the player is
within cooldown, `primeFromLoginCache` is never called and the buffer is
unaffected.

### Persistence (deliberate non-decision for v1)

The login buffer is **in-memory only**. On `RegionQueueManager.disableLoginCache`
(reload) and on shutdown, entries are drained back to `unkeptLocations` (with
reservations closed). The DB row already persisted under the unkept-bucket save
callback survives the round-trip. Behavior is indistinguishable from a
persisted login bucket — only DB row labelling would differ.

A future ADR may introduce a separate `rtp_login_cache` table if row-level
provenance becomes operationally useful (see follow-up tracking in
`POTENTIAL_BUGS.md` / a future ADR).

## Consequences

### Positive

- Zero risk of regression on the proven `Region.execute()` deficit loop.
- Two truly independent loops, each easy to reason about.
- Event-driven sizing (`+1 on disconnect, −1 on join consume`) naturally
  matches the "remaining empty slots" semantic without periodic recomputation.
- S-005 compliance — `LoginCacheTask` uses `getChunkAtAsync` exclusively;
  Folia region-thread verification is preserved.
- Default off — no behavioral change for existing deployments.

### Negative

- Code duplication: ~80 lines of promotion logic are mirrored, not extracted.
  Justified by the user requirement *"we should mirror it and run separately"*
  and by the regression-risk profile of the existing block.
- The login buffer competes with `keptLocations` for entries from
  `unkeptLocations`. On a small `cacheCap`, this can briefly starve the regular
  cache during the startup burst. Mitigated by the buffer's hard cap and by
  `cacheCap` typically being sized well above `maxPlayers` in practice.
- No persistence delta beyond the unkept bucket — a server restart loses
  ChunkReservation state on the login buffer (entries demote to unkept on
  shutdown and re-promote on next startup burst). Acceptable for v1.

### Neutral

- Fabric: stubbed. The fill task is platform-agnostic core code, but the
  startup-burst and quit-listener wiring lives in `rtp-plugin` (Bukkit-family).
  A Fabric-equivalent listener pair will be added when `rtp-fabric` graduates
  past the S-005 / `FabricServerAccessor` blockers tracked in
  `MULTI_PLATFORM_PLAN.md`.

## Configuration

`performance.yml`:

```yaml
loginCacheEnabled: false  # ADR-023 toggle
loginCacheCap: 0          # 0 = auto = Bukkit.getMaxPlayers() at plugin enable
```

## Implementation map

| Concern | Class / file |
|---|---|
| Config keys | `PerformanceKeys.loginCacheEnabled`, `PerformanceKeys.loginCacheCap` |
| Buffer | `RegionQueueManager.loginLocations` (nullable) |
| Lifecycle | `RegionQueueManager.enableLoginCache(int)`, `RegionQueueManager.disableLoginCache()` |
| Fill task | `io.github.dailystruggle.rtp.common.selection.region.LoginCacheTask` |
| Startup burst | `RTPBukkitPlugin.initLoginReserveCache` (called from `onEnable`) |
| Lazy refill | `OnPlayerQuit` → `LoginCacheTask.promoteUpTo(1)` |
| Consumption | `OnEventTeleports.primeFromLoginCache` |
| Template | `rtp-plugin/src/main/resources/performance.yml` |

## Follow-ups (out of v1 scope)

- Dedicated `rtp_login_cache` DB table for row-level provenance.
- `loginCacheMode = EVERY_LOGIN | FIRST_LOGIN` toggle (currently both modes
  are covered implicitly by the existing `rtp.onevent.firstjoin` /
  `rtp.onevent.join` permissions).
- Fabric port — wait until `rtp-fabric` is unblocked.
- REQ-traceable test classes: `LoginCachePromotionTest` (mirror of the kept
  promotion test against the new task) and `LoginCacheJoinPrimeTest`.
