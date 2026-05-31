# ADR-060 — Emergency-Platform Block-Restoration Timeout (DB-Persisted, Chunk-Loaded Countdown)

**Status:** Accepted
**Date:** 2026-05-30

## Context

`RTPWorld.platform(RTPLocation)` (declared abstract in `rtp-api`, implemented per adapter in `BukkitRTPWorld`, `FoliaRTPWorld`, and the Fabric world peer) builds an emergency landing platform at the confirmed arrival location by overwriting world blocks: it sets a disc of `platformMaterial` below the player (radius `platformRadius`, thickness `platformDepth`) and clears `platformAirHeight` blocks of air above. The knobs live in `safety.yml` and the whole feature is opt-in (`platformRadius: -1` disables it; the shipped default is `-1`).

This write is **destructive and permanent**: the original blocks at the footprint are lost. Operators who use the platform purely as a *temporary* safe-landing aid (so a player never spawns embedded in terrain, suffocates, or falls into a cave) are left with permanent glass discs scattered across the world wherever players have teleported. There is currently no way to say "build the platform, but put the terrain back once the player has safely moved on."

The request: add an **optional timeout** after which the platform's footprint is restored to the blocks that were there before the platform was built.

Several constraints shape the design:

- **Optional, additive, `-1` disables.** Mirrors the existing `platformRadius: -1` idiom. With the timeout unset/`-1`, `platform(...)` behaves exactly as today (permanent platform) and no diff is recorded, no DB row is written, no reaper work is scheduled.
- **Survive restarts.** A timeout that only lives in memory would leak permanent platforms across every server restart, crash, or `/reload` that happens during the countdown - precisely the failure mode the feature exists to prevent. The pending restore (footprint + original blocks + remaining time) must be **persisted** so it resumes after a restart.
- **Countdown only while the chunk is loaded.** The timeout is a "give the player time to leave, then tidy up" timer, not a wall-clock deadline. If the destination chunk is unloaded (no players nearby), there is nothing to tidy and forcing a chunk load to run the timer would violate S-005 and waste I/O. Each entry's remaining time shall decrement **only on ticks where its chunk is already loaded**; while the chunk is unloaded the countdown is frozen.
- **Remove the DB row on completion.** Once a platform is restored (or its entry is otherwise retired), its persisted row must be deleted so the table does not grow without bound and a restored footprint is never restored twice.
- **S-005 (no main-thread / region-thread chunk I/O).** The restore writes blocks and therefore must run on the region-owning thread, but it must never *force-load* a chunk to do so - it acts only when the chunk is already loaded (which is also the countdown gate).
- **S-004 (no silently discarded failures).** A restore that cannot complete (world unloaded, decode error, claim now intersects) is audited via `RTP.log`, never swallowed.
- **No schematic-paster hook; record the diff directly.** Per the scope decision for this work, restoration is implemented by recording the *block diff* that `platform(...)` is about to apply (the original block at each footprint coordinate), independent of the ADR-058 `SchematicPaster` path. No platform schematics are shipped in code; this ADR concerns only the emergency safety platform.
- **No per-region config.** The timeout is a single `safety.yml` knob, consistent with the other `platform*` keys, not a per-region knob.

Rule D-005 requires a proposal before implementation for any change crossing module boundaries; this touches `rtp-api` (the `platform` contract + a small restoration model), `rtp-core` (the new `SafetyKeys` entry, the persistence table on `AbstractSQLDatabaseAccessor`, and the chunk-loaded countdown reaper), and every backend adapter's `platform(...)` implementation. Hence this ADR. This document is the proposal; implementation is gated on its acceptance.

## Decision

Add an optional `safety.yml` knob `platformRestoreSeconds` (`-1` = disabled, the default). When set `>= 0`, `platform(...)` records the original block at every footprint coordinate before overwriting it, enrolls a **pending restore** entry (footprint + recorded original blocks + remaining seconds), and the entry is persisted to a new DB table. A periodic reaper decrements each entry's remaining time **only on ticks where the entry's chunk is loaded**; when it reaches zero the reaper restores the recorded original blocks on the region-owning thread and deletes the DB row.

### 1. Config knob (`safety.yml` + `SafetyKeys`)

- New key `platformRestoreSeconds` added to `safety.yml` next to the other `platform*` keys, and a matching enum constant in `SafetyKeys`.
  - `@type: integer`, `@range: [-1, null]`, `@unit: seconds`, `@default: -1`.
  - `-1` (default) disables restoration entirely: the platform is permanent, exactly as today. No diff recorded, no DB row, no reaper cost.
  - `0` restores on the first tick at which the footprint's chunk is loaded after the platform is built.
  - `> 0` restores after that many seconds of *chunk-loaded* time have elapsed.
- The key is mirrored into every shipped locale via the Locale Config TSV pipeline (the value is numeric; only the leading comment is translated). The comment must stand on its own in operator terms (no internal doc/ADR citations beyond an optional `REQ-RTP-*` / top-level `ADR-NNN`).
- `platformRestoreSeconds` is added to `RegionCacheKey`'s safety-key allowlist only if it can affect cached-location validity; since it affects post-arrival behavior rather than candidate selection, it shall **not** invalidate the shape cache (unlike `platformRadius`). Confirmed during implementation.

### 2. Restoration model (`rtp-api`)

A small, platform-neutral value model so the diff and the pending entry are expressible without `org.bukkit.*` / Minecraft types in `rtp-api`:

- `BlockDelta` - one recorded original block: world-relative coordinates `(x, y, z)` plus a platform-opaque, serializable block-state token (the adapter's canonical block-state string, e.g. the Bukkit `BlockData#getAsString()` form, or the Fabric block-state string). The token round-trips through the adapter; `rtp-core` treats it as an opaque `String`.
- `PendingPlatformRestore` - one enrolled restore: world id, the footprint's primary chunk key (`cx`,`cz`, used as the countdown gate), the list of `BlockDelta`, the remaining seconds, and a stable id (UUID). Carries no live world references.
- The adapter is responsible for *capturing* the `BlockDelta` list (reading the current block-state token at each footprint coordinate before overwriting) and for *applying* it (writing the token back). `rtp-core` owns enrollment, persistence, the countdown, and deletion.

`platform(RTPLocation)` keeps its signature. Internally each adapter's implementation, when `platformRestoreSeconds >= 0`, captures the original block at each coordinate it is about to overwrite and hands the resulting `PendingPlatformRestore` to a core entry point (`RTP`-level registry) rather than discarding it. When `platformRestoreSeconds == -1` the capture path is skipped entirely (zero added cost).

### 3. Persistence (`AbstractSQLDatabaseAccessor`)

A new table, e.g. `rtp_platform_restores`, reusing the existing HikariCP-backed accessor and its `cacheValue` / `flush` / `delete` write path and a dedicated load query (mirroring `loadCachedLocations` / `purgeStaleLocations`):

- Columns: `id` (UUID/text, primary key), `world` (text), `cx` / `cz` (int, the countdown-gate chunk), `remaining_seconds` (int), `blocks` (text/BLOB - the serialized `BlockDelta` list, e.g. newline-delimited `x,y,z,token` records).
- Written when an entry is enrolled; `remaining_seconds` is periodically updated (or written lazily on shutdown - see §4) so a restart resumes from approximately the right remaining time; **deleted** when the restore completes or is abandoned.
- On core startup, all rows are loaded and re-enrolled into the in-memory reaper so countdowns resume across restarts. This is the "persist across restarts" requirement.
- Not part of any network/proxy state (`NetworkStateBinding`); this is backend-local world mutation.

### 4. Countdown reaper (`rtp-core`, chunk-loaded gate)

A single periodic task scheduled through `RTP.scheduler` (never a raw executor - per the Scheduler Usage rule), in the spirit of the `MemoryTracker` active-GC sweep:

1. Once per second (configurable cadence internally; the unit of `platformRestoreSeconds` is seconds), iterate the enrolled `PendingPlatformRestore` entries.
2. For each entry, consult `RTPWorld.isChunkLoaded`-style state for its `(cx, cz)`. **The reaper never force-loads a chunk** (S-005). If the chunk is not loaded, the entry is skipped this pulse and its `remaining_seconds` is left unchanged (countdown frozen).
3. If the chunk is loaded, decrement `remaining_seconds`. When it reaches `<= 0`, schedule the restore on the region-owning thread via `RTP.scheduler.runTask(RTPLocation, ...)` (Folia) / `runTask(...)` (Bukkit/Paper) / the Fabric server-thread executor. The scheduled task calls the adapter to apply the recorded `BlockDelta` list (write the original tokens back).
4. On successful restore: remove the in-memory entry and `delete` the DB row. Audit the outcome via `RTP.log` (S-004).
5. On failure (world gone, token decode error, etc.): audit via `RTP.log(Level.WARNING, ...)`, retire the entry, and delete the DB row (a malformed entry must not wedge the reaper or re-fire forever).
6. `remaining_seconds` is persisted back to the DB either periodically or at shutdown drain so a restart does not reset a partially-elapsed countdown to its full value.

Folia note: the per-entry restore task is keyed to the destination `RTPLocation`, so it runs on the region that owns the footprint and never touches a foreign region.

### 5. Claim re-check on restore (S-003)

The original-block capture happens at platform-build time, but the world may change by restore time (a player may have built/claimed there). The intended design re-runs the folded-in claim-intersection check (ADR-019) over the footprint before applying and skips (audited) when it now intersects a claim.

**Implementation note (2026-05-30):** the platform is only ever built on land the safety pipeline already cleared of claims (S-003), so the footprint starts in unclaimed terrain. The folded claim integration lives in `rtp-plugin` and is not reachable from the `rtp-core` reaper without a new SPI hook; the claim re-check at restore time is therefore **deferred** to a follow-up. Operators who expect players to build on the temporary platform before it expires should leave restoration disabled (`-1`), per `docs/admin/SAFETY.md`.

### 6. Tests + docs

- A reaper unit test (using `MockRTPScheduler` + `MockRTPWorld`): an enrolled entry whose chunk is **unloaded** does not decrement; the same entry decrements and finally restores once its chunk is reported loaded; the DB row is deleted on completion.
- A persistence round-trip test: enroll -> persist -> simulate restart (reload from DB) -> countdown resumes -> restore -> row gone.
- An S-004 test: a restore failure (e.g. forced token decode error) emits an `RTP.log` audit and retires the entry without re-firing.
- An S-003 test: a footprint that intersects a claim at restore time yields a skip and an unmodified world inside the claim.
- A `-1`/disabled test: `platform(...)` with `platformRestoreSeconds == -1` records no diff, enrolls nothing, and writes no DB row (parity with today).
- New `REQ-RTP-*` rows in `TRACEABILITY.md` for the chunk-loaded-countdown guard, the persistence round-trip, and the S-004 restore-audit.
- A `docs/admin/SAFETY.md` row for `platformRestoreSeconds` documenting the `-1`/`0`/`> 0` semantics, the chunk-loaded countdown (timer pauses while the area is unloaded), the restart-resume behavior, and the claim-skip-on-restore caveat.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Wall-clock timeout (decrement regardless of chunk-loaded state) | Would force-load the footprint chunk to restore (S-005 violation) or restore into an unloaded area no player is observing - wasted I/O for no benefit. The chunk-loaded gate ties the cost to where players actually are. |
| In-memory only, no DB persistence | A restart/crash/`/reload` mid-countdown leaks a permanent platform forever - the exact failure the feature prevents. Persistence is required for the "survive restarts" requirement. |
| Hook restoration into the ADR-058 `SchematicPaster` | Out of scope per the work's scope decision: no platform schematics are shipped in code, and the emergency platform is a plain block-disc write, not a schematic. Recording the block diff directly is simpler and decoupled from the (not-yet-wired) paste path. |
| Per-region `platformRestoreSeconds` | The other `platform*` knobs are global `safety.yml` keys; a per-region knob adds config surface and locale-parity cost for no requested benefit. Scope decision: use `safety.yml`. |
| Snapshot whole chunks instead of a per-coordinate diff | Far larger DB payload and restore blast radius; the platform footprint is small and bounded (`radius`,`depth`,`airHeight`), so a per-coordinate `BlockDelta` list is compact and surgical. |
| Restore without re-checking claims | Could overwrite blocks a player legitimately placed/claimed after arrival (S-003 regression). Re-checking at restore time is mandatory. |
| Never delete the DB row (keep history) | Unbounded table growth and risk of double-restore; the row's only purpose is resumability, so it is deleted on completion. |

## Consequences

- **Positive:** The emergency platform becomes a genuinely *temporary* safety aid: players land safely, then the terrain is tidied automatically, with no permanent glass scars. Fully additive (`-1` default = today's behavior, zero added cost). Countdown cost is bounded to loaded chunks (S-005-safe, no force-loads). Restores survive restarts (DB-persisted, resumed on startup). Claim land is protected at restore time (S-003); every skip/failure is audited (S-004). DB rows are self-cleaning (deleted on completion).
- **Negative / Trade-offs:** Adds a `safety.yml` key to keep in locale parity, a new `rtp-api` value model, a new DB table on `AbstractSQLDatabaseAccessor`, and one periodic reaper task. When enabled, `platform(...)` does one extra block-state read per footprint coordinate (capture) and the reaper does a per-entry chunk-loaded check per pulse. Each adapter's `platform(...)` must implement capture + apply for its block-state token format (Bukkit `BlockData` string, Fabric block-state string), adding a per-platform surface. A footprint whose chunk stays loaded forever but whose player never returns will still restore on schedule (intended).

## References

- Emergency platform implementation: `RTPWorld#platform` (`rtp-api`), `BukkitRTPWorld#platform`, `FoliaRTPWorld#platform`, Fabric world peer; knobs `platformRadius` / `platformDepth` / `platformAirHeight` / `platformMaterial` in `safety.yml` (`SafetyKeys`).
- Persistence layer: `AbstractSQLDatabaseAccessor` (`cacheValue` / `flush` / `delete` / `loadCachedLocations` / `purgeStaleLocations`).
- Scheduling contract: [`.junie/AGENTS.md`](../../.junie/AGENTS.md) *Scheduler Usage*; `RTPScheduler` (`runTask(RTPLocation, ...)`); active-GC prior art: `MemoryTracker`.
- S-003 / S-004 / S-005: [`REQUIREMENTS.md §3`](../dev/REQUIREMENTS.md); claim integration: [ADR-019](ADR-019-claim-plugin-integrations-folded-into-plugin.md).
- Decoupled-from-paste rationale: [ADR-058](ADR-058-region-specific-schematic-paste.md) (the `SchematicPaster` path this feature deliberately does **not** use).
- Roadmap context: [`docs/dev/ROADMAP.md`](../dev/ROADMAP.md).
