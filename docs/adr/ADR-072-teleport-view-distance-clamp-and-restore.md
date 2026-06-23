# ADR-072: Teleport View-Distance Clamp and Steady Restore

**Status:** Accepted
**Date:** 2026-06-23

## Context

The dominant cost of a random teleport is not selecting the coordinate (selection is
asynchronous, pre-verified, and anvil-prefiltered) but the *arrival*: when the player is
placed at the destination the server implicitly loads and begins tracking the per-player
view-distance ring of chunks around the new position, frequently into a cold area. These
are not loads RTP schedules - they happen under the hood inside the engine's placement and
chunk-tracking path, and RTP gets no per-chunk callback with which to pace them. The number
of chunks involved grows with the square of the view distance, so a player at view distance
10-12 triggers a several-hundred-chunk burst concentrated in the tick of arrival. On busy
servers, and especially on weaker hosts (and on Folia, where the destination region thread
absorbs this work), that synchronous burst is a measurable TPS/MSPT spike that can stall the
tick.

A direct rate limiter on these implicit loads is not viable: the scheduling belongs to the
server, not to RTP, there is no portable hook to pace it, and forcing a hold-back would mean
blocking a tick/region thread (an S-005 violation). The only portable lever that reduces the
implicit placement burst is the size of the tracked ring itself - i.e. the player's view
distance at the moment of placement. Shrinking that ring before placement caps the burst;
restoring it gradually afterward *is* the rate limit on the residual loads, spread across
many ticks instead of one.

RTP already preloads a configurable ring around the destination via
`performance.yml#viewDistanceTeleport` (consumed at `Region.java`, `ConfigCache.viewDistanceTeleport`).
That key governs how many chunks RTP itself loads server-side before placing the player;
it does not affect how many chunks the server then *delivers* to the client. The two
concerns are complementary: one decides what is resident, the other decides how fast it
is shipped.

Per-player view distance is purely runtime server state. `Player#setViewDistance(int)`
(Paper/Spigot) and the Fabric/NeoForge chunk-tracking-view equivalents are not persisted
to player data or to any database; a clean server restart re-negotiates every player to
the world/server default. The valid range is `[2, 32]` chunks - clients cannot render
below 2 - so 2 is the hard floor for any clamp.

This ADR introduces a lever that smooths the arrival burst by temporarily clamping the
player's *delivery* view distance at teleport time and then restoring it in single-chunk
steps spread over a configured total duration.

## Decision

Add an opt-in "teleport view-distance clamp and steady restore" behavior, gated and
configured through `performance.yml` with **one** new key, reusing the existing preload
radius as the initial clamp value.

Configuration surface:

- `viewDistanceTeleport` (existing): preload radius AND the initial clamp value for the
  arrival. No new "clamp start" key is introduced.
- `viewDistanceRestoreInterval` (new): the **total** time, in server ticks, over which
  the player's view distance is ramped from the clamp value back up to the value it held
  immediately before the teleport. Default `200` ticks (10 seconds). `0` disables the
  feature entirely (no clamp is applied).

Behavior:

1. **Capture** the player's effective pre-teleport view distance (per-player override if
   set, otherwise the world/server default) before applying any clamp. This captured
   value is the restore target.
   The clamp **must be applied before the teleport call itself**, not after arrival: the
   goal is to shrink the tracked ring the engine expands during placement, so a clamp
   applied after the player is already placed would miss the very burst the feature exists
   to smooth.
2. **Clamp value** = `max(viewDistanceTeleport, MIN_VD)` where `MIN_VD = 2` (the game's
   minimum renderable distance). A configured preload radius of `0` still resolves to a
   one-chunk destination load server-side, but the *delivered* clamp is floored at 2 so
   the player is never sent an unrenderably small view. The exact platform floor is
   validated against the platform API at apply time rather than assumed.
3. **Skip** when there is nothing to gain: if the clamp value is greater than or equal to
   the captured pre-teleport view distance (`steps <= 0`), apply no clamp and schedule no
   restore task. This is a normal, expected no-op, not an error.
4. **Ramp** (total-time, chunk-cost-weighted semantics): the view distance is raised one
   chunk at a time from `clampValue` up to `capturedVD` over the total
   `viewDistanceRestoreInterval`, but the dwell *between* successive increments is **not**
   uniform. Because the chunk ring delivered at view distance `r` grows as `(2r+1)^2`, the
   marginal cost of the step `r -> r+1` is proportional to `(2(r+1)+1)^2 - (2r+1)^2 = 8r+8`,
   i.e. it grows roughly linearly in `r` and the largest jumps are the last ones. A uniform
   `stepInterval` would therefore back-load the heaviest bursts into equal-length windows,
   re-concentrating cost exactly where it hurts most. Instead the scheduler allocates the
   total interval in proportion to each step's marginal chunk cost: the dwell granted to the
   step `r -> r+1` is
   `dwell(r) = max(1, round(viewDistanceRestoreInterval * (8r+8) / totalMarginalChunks))`
   where `totalMarginalChunks = (2*capturedVD+1)^2 - (2*clampValue+1)^2`. This spends more
   ticks before the expensive late increments and dispatches the cheap early ones quickly,
   so the *chunks-delivered-per-tick* rate stays roughly flat across the whole window - the
   "logarithmic-feeling" release the cost curve calls for. The task self-cancels and releases
   when it reaches `capturedVD`. An interval shorter than the step count simply collapses to
   one increment per tick (fastest bounded ramp).
5. **Defer to a larger current view distance.** Each increment reads the player's
   *current* view distance and sets the target to the maximum of the ramp's next value
   and the current value; the ramp never shrinks a view distance that another tool (a
   render-distance plugin, an admin command, a resource-aware limiter) has set higher in
   the meantime. If the current value already meets or exceeds the ramp's final target,
   the ramp completes early and releases.
6. **Guaranteed teardown.** The clamp/restore is session-scoped and tracked like a
   `MemoryTracker` allocation: registered on clamp, released on every exit path - normal
   completion, teleport failure, player disconnect mid-ramp, and shutdown/`/reload` drain.
   Because per-player view distance is never persisted, a restart cannot leak a clamp; the
   teardown obligation is strictly within the live session.

Architecture placement:

- The clamp/restore lives behind a platform-neutral player-facing SPI pair
  (`getViewDistance()` / `setViewDistance(int)`) on the existing platform abstraction.
  `rtp-core` sees only the interface; each adapter (`rtp-bukkit`, `rtp-paper`,
  `rtp-folia`, `rtp-fabric`, `rtp-neoforge`) implements the actual call. A platform with
  no per-player view-distance API no-ops the feature.
- The ramp task is scheduled exclusively through `RTP.scheduler` in tick units. On Folia
  it targets the player's entity/region scheduler and is ownership-gated
  (`Bukkit.isOwnedByCurrentRegion`); no raw `Thread` or `ScheduledExecutorService` is used.
- No database persistence. Per-session runtime state only.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Add three new keys (`clampStart`, `restoreStep`, `restoreIntervalTicks`) | Over-configured. The preload radius is already the natural clamp start, and total-time semantics derive the step cadence, so one key suffices. |
| `viewDistanceRestoreInterval` as a per-step interval | A per-step value forces operators to reason about step count to predict total restore time; total-time semantics are more intuitive and let the step cadence adapt to the clamp delta automatically. |
| Persist pre-teleport view distance to the configured database | Per-player view distance is runtime-only and is re-negotiated to the server default on restart, so persistence buys nothing across restarts while adding DB I/O to the latency-sensitive teleport path. Reserved as a possible future enhancement only if a clamp is ever required to survive relogs by design. |
| Lower world/server view distance globally during teleports | Penalizes every player for one player's teleport; per-player clamp isolates the cost. |
| Clamp but restore instantly (single jump) | Re-introduces the very burst the feature exists to spread out, just delayed by the dwell time. |
| Always shrink to the ramp's scheduled value each step | Would fight other view-distance tools and could shrink a player below what an admin/plugin set; the "defer to larger current VD" rule avoids this. |
| Uniform dwell per increment (`stepInterval = interval / steps`) | Ignores that the chunk ring grows quadratically, so the last `+1` increments deliver far more chunks than the first; equal time windows re-concentrate the heaviest bursts at the end. Chunk-cost-weighted dwell keeps chunks-per-tick roughly flat across the window. |

## Consequences

- **Positive:** Caps and spreads the implicit placement/chunk-tracking burst the engine
  performs when the player is placed, across the restore window instead of a single tick,
  reducing teleport-induced TPS/MSPT stalls - most visibly on weaker hosts, on Folia
  destination region threads, and on high-population servers. This is the project's
  portable answer to "rate-limiting the post-teleport implicit loads," which cannot be
  throttled directly.
- **Positive:** One added config key with a sane default; `0` disables. Reuses the
  existing preload radius, keeping the surface minimal and discoverable.
- **Positive:** Session-scoped with no persistence keeps the feature platform-uniform -
  Fabric/NeoForge need not wire view distance into any persistence layer to benefit.
- **Negative / Trade-offs:** Players see render distance "grow in" over the restore
  window after arrival, a deliberate, visible UX trade for the performance gain.
- **Negative / Trade-offs:** Requires a per-platform `getViewDistance`/`setViewDistance`
  SPI implementation; platforms lacking a per-player API silently no-op.
- **Negative / Trade-offs:** Adds a short-lived per-teleport scheduled task that must be
  reliably torn down on all exit paths to avoid a within-session clamp leak.

## References

- `rtp-core/.../configuration/enums/PerformanceKeys.java` - `viewDistanceTeleport`, `viewDistanceSelect`.
- `rtp-core/.../selection/region/Region.java` - existing `viewDistanceTeleport` preload consumption.
- `rtp-core/.../tasks/teleport/TeleportPipelineTask.java` - teleport completion hook point.
- `helpers/StressTestRTP/.../ChunkLoadCounter.java` - arrival-ring cost model used to quantify the burst.
- ADR-008 (MemoryTracker active GC) - lifecycle-tracking pattern reused for clamp register/release.
- ADR-054 (RTPRunnable self-scheduling thread routing) - scheduler routing the ramp task relies on.
- Minimum renderable view distance of 2 chunks confirmed against Paper per-player view-distance range `[2, 32]`.
