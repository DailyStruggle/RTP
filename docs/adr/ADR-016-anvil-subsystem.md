# ADR-016 — Anvil Read-Only Subsystem (Prefilter, Backed Chunk View, Shared Module)

**Status:** Accepted

## Context

On Paper and Folia, `World#getChunkAtAsync(int, int)` returns a
`CompletableFuture<Chunk>` that resolves off the tick thread, and the RTP
teleport pipeline exploits this to keep `LocationGenerator`'s block sampling
and `vert.adjust(chunk)` work off the primary thread. On vanilla Spigot that
overload is not available — the only async chunk APIs on Spigot are the
`Consumer`-based overloads, all of which still dispatch the actual chunk
load back onto the server tick thread. The reflective probe in
`BukkitRTPWorld.CHUNK_AT_ASYNC_FUTURE` only helps on forks that expose the
`CompletableFuture` overload at runtime.

Two concrete costs follow on vanilla Spigot:

1. **Main-thread pressure.** Every candidate evaluation schedules a
   `runTask(...)` for `world.getChunkAt(cx, cz)` on the primary thread just
   to read a single block material, a height-map value, and the sky light
   level. Under `/rtp`-heavy workloads this directly competes with player
   tick work.
2. **Wasted generation.** When a candidate lands in an ungenerated chunk,
   the sync `getChunkAt(...)` forces the server to generate the chunk just
   so RTP can read a single column and (most of the time) reject the
   candidate anyway.

For chunks that are already generated on disk but currently unloaded, the
block, biome, heightmap, and sky-light data that RTP needs is already sitting
in the region file (`<world>/region/r.<x>.<z>.mca`) in the Anvil format.
Reading it is pure file I/O — no tick thread involvement is required.

For a populated chunk the on-disk palette is *strictly more accurate* than
the Bukkit live view: `org.bukkit.Material` and `org.bukkit.block.Biome` are
closed enums that silently collapse modded identifiers (e.g.
`create:crushing_wheel`) and generator-native biome names (e.g.
`iris:volcanic_ash_plains`) to their nearest vanilla cousin, whereas the
`.mca` palette preserves the full namespaced identifier the generator
wrote. The subsystem therefore applies equally to worlds that use a custom
`ChunkGenerator`, so long as the target chunk has already been populated
to disk. Ungenerated chunks under a custom generator surface as
`Verdict.UNKNOWN` (empty region-file slot) and fall through to the live
load as normal.

The minimum supported Minecraft version is 1.20.1 (REQ-RTP-SYS-002). Format
adapters shall target 1.20.1 and later; pre-1.18 Anvil variants are out of
scope.

A key failure mode shapes the verdict semantics below: in the nether,
`WORLD_SURFACE` resolves at the lava column for essentially every chunk.
A design that short-circuits `REJECT` verdicts (e.g. by returning a null
chunk from `BukkitRTPWorld.getChunkAt`) therefore produces a
false-negative storm even though the vert adjustor would have found a
safe Y below the surface. The verdict must be advisory, not a gate.

Confining the decode stack to `rtp-spigot-common` would also force
duplication of ~10 source files plus tests across every other platform
that wants to consume it.

## Decision

RTP shall ship a **read-only Anvil subsystem** that serves selection-phase
block reads from the on-disk region file as an advisory data source, while
the live `chunk.isSafe(...)` re-check at teleport-commit time remains the
authoritative arbiter. The subsystem lives in a platform-neutral Gradle
module, `rtp-anvil`, and is consumed by platform adapters.

### 1. Applicability gate (read-only)

The subsystem runs for a candidate chunk `(cx, cz)` iff *all* of the
following are true:

- The platform adapter has opted in (`rtp-spigot-common` is the initial
  consumer; Paper/Folia adapters keep their native async overrides and
  bypass the subsystem entirely).
- `RTPWorld.isChunkLoaded(cx, cz) == false`. A loaded chunk may hold
  unflushed edits that disagree with the on-disk region file; skipping the
  subsystem for loaded chunks is the only cheap, correct way to avoid
  read/write desync without coordinating with the chunk I/O thread.
- The region file for `(cx, cz)` exists on disk. An absent region file
  means the chunk is ungenerated; the candidate falls through to the
  real-load path, which will drive generation through the platform's
  native scheduler. Generation behaviour is unchanged.
- *(No custom-generator abstention.)* Earlier drafts of this ADR gated the
  subsystem off whenever `world.getGenerator()` was non-null. That gate is
  removed: for a populated chunk on disk the `.mca` palette is strictly
  more accurate than the Bukkit enum view (Context, above), and for a
  chunk the custom generator has not yet populated the subsystem already
  returns `Verdict.UNKNOWN` from the empty region-file slot and falls
  through to the live load. The live `chunk.isSafe(...)` re-check (§5)
  remains authoritative, so any residual disk-vs-live divergence under a
  custom generator is bounded to "extra retries", never "unsafe teleport".

### 2. Format detection with fallback

The reader probes the chunk NBT for a `DataVersion` tag and dispatches to
a registered format adapter. Unsupported `DataVersion`s, I/O errors,
corrupted sections, or thread interruption produce a `Verdict.UNKNOWN`
outcome with a null view; the candidate falls through to the real-load
path, never rejected, never a false positive. Format adapters are
additive.

### 3. Verdict enum (advisory only)

`AnvilPrefilter.probeSyncDetailed(...)` returns one of:

- `ACCEPT` — read succeeded, heightmap-surface column is safe under the
  normalized unsafe-block set.
- `REJECT` — read succeeded, heightmap-surface column contains a
  configured-unsafe block.
- `UNKNOWN` — no decode was possible.

**The verdict is advisory telemetry. It does not gate control flow.** The
adapter routes on `view != null`, not on verdict. `ACCEPT` and `REJECT` both
carry the decoded `AnvilChunkView`; only `UNKNOWN` carries a null view.
`AnvilPrefilterMetrics` continues to count `ACCEPT` / `REJECT` / `UNKNOWN`
so `/rtp test anvil-prefilter` remains useful, but "REJECT" now reads as
"surface-unsafe column handed to the vert adjustor" rather than
"candidate dropped".

### 4. Data-source routing in the platform adapter

`BukkitRTPWorld.getChunkAt(cx, cz)`:

1. If the applicability gate (§1) fails, delegate to the existing reflective
   async / sync fallback path unchanged.
2. Otherwise call `AnvilPrefilter.probeSyncDetailed(...)` with the
   platform-supplied reconciler (`PaletteNormalizer::reconcile`).
3. If the probe returns a non-null view (any of `ACCEPT` / `REJECT`),
   publish it into `anvilCache` and return a completed future with the
   live-shaped chunk key. The immediately-following `getCachedChunk(key)`
   call in `LocationGenerator` receives a source-union `BukkitRTPChunk`.
4. If the probe returns a null view (`UNKNOWN`), fall through to the
   reflective async / sync fallback.

There is **no null-key short-circuit**. The previous
`CompletableFuture.completedFuture((Long) null)` branch on `REJECT` is
deleted outright; there is no feature flag for the old semantics.

### 5. Authoritative re-check preserved

At teleport-commit time, `LocationGenerator` force-loads the selected chunk
via `ChunkSet` and the live `chunk.isSafe(...)` re-check runs against the
live world. REQ-RTP-S-001 is satisfied by the live re-check, identically
to the pre-subsystem baseline; the Anvil subsystem only ever strengthens
the rejection side, never the acceptance side. An Anvil-backed chunk is
**never** handed to the teleport-commit path.

### 6. Source-union `BukkitRTPChunk`

`BukkitRTPChunk` is a source-union over a live `org.bukkit.Chunk` and/or
an `AnvilChunkView`:

- Anvil-mode answers `isAir / isSafe / getSkyLight / getSurfaceHeight`
  from the view across the full decoded Y range (including palette
  sections below the heightmap surface), so the vert adjustor can locate
  a safe air pocket below a lava surface without loading the chunk.
- Any query that leaves the decoded Y window, or that arrives after the
  live chunk has been loaded in the meantime, falls through to the live
  `Chunk`. Cache promotion on live load evicts the Anvil entry so disk
  is never a source of truth.
- `RTPChunk#isSelfContained()` returns `true` for Anvil-mode instances so
  the stale-chunk guard (ADR-015) in `LocationGenerator` does not
  mis-attribute Anvil-backed candidates to the `nullChunk` bucket when
  `world.isChunkLoaded(cx, cz)` returns `false` (the expected state for
  an Anvil-backed chunk).

### 7. Palette identifier reconciliation

Palette reconciliation is symmetric on both sides:

- Block/biome palette entries shaped like `minecraft:lava` are resolved via
  the platform registry (Bukkit `Material.matchMaterial(...)` /
  `Registry.BIOME`) to the canonical enum `.name()` (`LAVA`). Entries that
  do not resolve (modded identifiers on forks like Mohist, Arclight) fall
  back to namespace-stripping plus upper-casing.
- The user-supplied `SafetyKeys.unsafeBlocks` / biome blacklist is put
  through the identical normalization once at config load, so operators
  may write entries as `LAVA`, `minecraft:lava`, or `MINECRAFT:LAVA` and
  all three compare equal.

`rtp-anvil` ships a platform-neutral `DEFAULT_RECONCILER` (namespace-strip
+ `Locale.ROOT` upper-case). Platform adapters that need a registry-aware
reconciler (e.g. Bukkit `Material.matchMaterial` in the Spigot-side
`PaletteNormalizer`) supply it as a `UnaryOperator<String>` on the
reconciler-aware overloads of `probeDetailed` / `probeSyncDetailed`.
`PaletteNormalizer` itself stays in `rtp-spigot-common` because it is
genuinely platform-coupled.

### 8. Shared module layout

The Anvil decode stack lives in a new top-level Gradle module, `rtp-anvil`,
peer to `commands-api` and `effects-api`, with the package
`io.github.dailystruggle.rtp.anvil`.

**Module invariants** — `rtp-anvil` shall not import:

- any RTP module (`rtp-api`, `rtp-core`, `commands-api`, `effects-api`,
  or any platform adapter);
- any platform package (`org.bukkit.*`, `io.papermc.*`, `net.minecraft.*`,
  `net.fabricmc.*`).

Public types shall exchange only platform-neutral values: `byte[]`,
`java.nio.file.Path`, `java.util.Optional`, primitives, and the module's
own decode types. The boundary is enforced at the bytecode level by
`AnvilPackageBoundaryArchTest`.

Module contents:

- `AnvilReader`, `AnvilChunkView`, `AnvilPrefilter`,
  `AnvilPrefilterMetrics`, `PackedPaletteDecoder`, `PaletteSection`,
  `DataVersionSupport`, `Verdict`, `Nbt`,
  `UnsupportedAnvilFormatException`.
- Test fixtures (`r.X.Z.mca` binaries) for 1.20.1 / 1.21.1 / 26.1
  `DataVersion`s.

`PaletteNormalizer` remains in `rtp-spigot-common` (Bukkit-coupled).
Spigot-specific glue — `BukkitRTPWorld#getChunkAt` orchestration,
`BukkitRTPChunk` source-union, `dimensionRegionSubpath(World)` — likewise
stays in `rtp-spigot-common`.

### 9. Thread placement

All region-file I/O runs on `ForkJoinPool.commonPool()` (or an explicit
RTP async executor) and never on the tick thread. A bounded LRU cache of
recently decoded `AnvilChunkView` instances in `BukkitRTPWorld.anvilCache`
keeps memory predictable (REQ-RTP-S-002 is unaffected — the subsystem does
not allocate chunk tickets).

### 10. REQ-RTP-S-004 attribution

The `FailTypes.nullChunk` bucket (with sub-keys `reason=asyncLoadNull` /
`reason=neighborNull`) in `LocationGenerator` covers every `chunk == null`
exit in the pregen summary. Because §4 deletes the null-key short-circuit,
the bucket attributes only genuine async-load failures — `prefilterReject`
is no longer a reachable sub-key and does not exist. The regression guard is
`ReqRtpS004NullChunkAttributionTest`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **A.** Always read from the region file and never load the chunk until teleport commit (i.e. elide the live `chunk.isSafe(...)` re-check for ACCEPT verdicts). | Region files can lag behind live world state for loaded chunks (unflushed block updates, player-placed blocks); the §1 `isChunkLoaded` gate handles that case by design, but an ACCEPT-without-relive-check would still risk disagreeing with per-tick updates that landed after the region file was written and before the chunk was unloaded. Keeping the live re-check preserves REQ-RTP-S-001 at the cost of one authoritative block read. |
| **B.** Hold the pre-filter ACCEPT verdict as the sole safety check and drop the post-load re-check. | Same desync class as A; also breaks the existing test contract that `LocationGenerator` calls `chunk.isSafe(...)` on a live chunk. |
| **C.** Keep `REJECT` as a gate and add a separate "probe-only" path for the vert adjustor. | Two parallel decode paths for the same region file; more code, more caches, more invariants. Collapsing into a single data-source path (§4) is strictly simpler. |
| **D.** Demote REJECT to ACCEPT in the prefilter (no code path difference except verdict label). | Loses the telemetry hint; `/rtp test anvil-prefilter` can no longer show how often the surface would have been rejected under the old gate. The data-source model keeps the verdict as advisory at zero additional cost. |
| **E.** Gate REJECT on "region has no vert adjustor" — thread a hint from `LocationGenerator` through `BukkitRTPWorld.getChunkAt` so surface-only regions still short-circuit. | Crosses the `rtp-core` ↔ `rtp-spigot` module boundary to preserve an optimisation for a case that is nearly empty in production (almost every region uses a vert adjustor). |
| **F.** Remove the prefilter entirely. | Throws away the §6 source-union benefit on the only Spigot path where off-tick safety evaluation is achievable. Regresses REQ-RTP-S-005 coverage on pure Spigot. |
| **G.** Vendor a third-party NBT/Anvil library (Querz, Hephaistos, ensgijs:mca). | Adds a runtime dependency whose surface far exceeds RTP's needs (palette, heightmap, biome palette, sky-light). Shading/licensing non-trivial. A hand-rolled minimal reader is preferred. |
| **H.** Keep the decode stack under `rtp-spigot/` and add `rtp-folia` / `rtp-fabric` Gradle dependencies on it. | Uglier module hierarchy; implies Folia/Fabric transitively depend on Spigot-specific artifacts. The peer-module placement (`rtp-anvil` next to `commands-api`) is honest about the shared-library role. |
| **I.** Gate the new semantics behind a config toggle. | Leaving the old REJECT short-circuit behind a flag re-creates the diagnostic black hole the ADR exists to close. Deleting the branch outright avoids future dead-branch cruft. |
| **J.** Skip vanilla Spigot entirely and require Paper. | Not an architectural decision RTP gets to make. Spigot remains supported (REQ-RTP-SYS-002). |

## Consequences

**Positive:**

- On vanilla Spigot with generated chunks, `LocationGenerator`'s
  `isAir / isSafe / getSkyLight / getSurfaceHeight` and the vert adjustor
  all run off the tick thread for candidates whose region file exists and
  decodes. This includes the nether, where surface-unsafe chunks now
  carry a usable view for sub-surface scanning (prior false-negative
  storm is closed).
- Ungenerated chunks still drive generation through the platform's native
  scheduler; the subsystem is additive and removal restores pre-subsystem
  behaviour verbatim.
- Loaded chunks are never read from disk — desync class eliminated by
  construction (§1 `isChunkLoaded` gate).
- Format detection with fall-through means new Minecraft versions never
  regress teleport correctness; the subsystem abstains until an adapter
  is written.
- Paper and Folia remain unaffected by the Spigot code path; their
  existing `@Override`-based async routing short-circuits the subsystem.
- `rtp-anvil` is reusable by other platform adapters (see §11 for the
  Folia application) without code duplication; DataVersion compatibility
  and format-adapter maintenance live in a single module.
- REQ-RTP-S-004 attribution is complete: every `chunk == null` path in the
  pregen summary increments a `failMap` bucket with a descriptive
  sub-key, and genuine anomalies emit WARN logs.

**Negative / trade-offs:**

- Anvil bytes are decoded for candidates that will ultimately be rejected
  at the live re-check, which is worse than the old surface-unsafe
  short-circuit for purely surface-unsafe rejections. Net CPU cost is on
  `ForkJoinPool.commonPool()` (off-tick) and is paid back by eliminating
  the sync `getChunkAt(...)` on the tick thread for the same candidates.
- Hand-rolled Anvil decoding is maintenance-bearing: section palette
  encoding, heightmap long-packing width, and biome storage layout have
  all changed across Minecraft versions. A stale format adapter that
  wrongly claims to support a newer `DataVersion` could produce ACCEPT
  verdicts on misread data; mitigated by the post-load authoritative
  re-check.
- Modded identifiers must go through the namespace-strip + upper-case
  fallback; the reconciler does not assume the vanilla namespace.
- Future contributors may forget the "no platform imports" invariant in
  `rtp-anvil`; mitigated by `AnvilPackageBoundaryArchTest`.
- The `UnaryOperator<String>` reconciler parameter is slightly more
  verbose at the call site than the previous `World`-typed overload.
  Mitigated by keeping a three-arg `probeSyncDetailed` overload that uses
  the `DEFAULT_RECONCILER`; only platform adapters that need registry-aware
  reconciliation pay the verbosity cost.

**Neutral:**

- `AnvilPrefilterMetrics` counters remain at their prior cardinality;
  only their operational reading changes (see §3).
- Test fixture binaries move with the tests into `rtp-anvil/src/test/resources/`;
  contents unchanged.
- The stale-chunk guard (ADR-015) and async queue pre-generation
  (ADR-006) are unaffected — the subsystem does not allocate chunk
  tickets and does not interact with queue replenishment.

## 11. Folia applicability

On Folia, `FoliaRTPWorld extends RTPWorld<World>` directly (not
`BukkitRTPWorld`) and uses Paper's native `World#getChunkAtAsync`
`CompletableFuture` overload, which resolves off the tick thread. That
satisfies REQ-RTP-S-005 *in letter*: no Folia path drives `getChunkAt`
on the primary thread. However, two Folia-specific costs survive the
native async overload and motivate re-using the Anvil subsystem as a
source of truth for selection-phase reads:

1. **Region-thread affinity.** On Folia, every live `Chunk` read
   (`isAir`, `isSafe`, `getBlock`, heightmap, sky-light) must execute on
   the region thread that owns `(cx >> 4, cz >> 4)`. `LocationGenerator`
   runs under `AsyncTaskProcessing`, so each live-chunk query forces a
   hop onto the owning region's scheduler, where it competes with entity
   ticking and player action processing. Under `/rtp`-heavy workloads
   the selection loop effectively re-enters the region thread once per
   candidate column sample.
2. **Wasted generation of rejected candidates.** As on Spigot, an
   ungenerated candidate chunk that will be rejected at the surface-block
   check still drives a full generation pass through the region
   scheduler before RTP ever reads the palette. The Anvil applicability
   gate (§1) rules this out: the subsystem only runs when the region
   file already exists on disk, so ungenerated chunks fall through
   unchanged.

### 11.1 Decision

Folia shall consume `rtp-anvil` through a Folia-side source-union
(`FoliaRTPChunk` over `org.bukkit.Chunk` and/or `AnvilChunkView`),
mirroring the Spigot-side `BukkitRTPChunk` / `BukkitRTPWorld`
orchestration from §4 and §6. The decision is structurally identical to
the Spigot path; only the platform glue differs.

The applicability gate (§1) is unchanged:

- `RTPWorld.isChunkLoaded(cx, cz) == false` — live chunks on Folia may
  hold unflushed edits that disagree with the region file; the same
  desync class documented under §1 applies without modification.
- Region file for `(cx, cz)` exists on disk — ungenerated chunks fall
  through to the native `getChunkAtAsync(cx, cz)` path, which drives
  generation through Folia's region scheduler (behaviour unchanged).
- Custom `ChunkGenerator` worlds (Iris, Terra, datapack, void) are *in
  scope* on Folia for the same reason as §1: populated on disk ⇒ `.mca`
  is authoritative; not populated ⇒ `Verdict.UNKNOWN` ⇒ native
  `getChunkAtAsync` path drives generation unchanged.

The verdict semantics (§3) are unchanged: `ACCEPT` / `REJECT` / `UNKNOWN`,
with `ACCEPT` and `REJECT` both carrying a view and `UNKNOWN` carrying
null. The live re-check at teleport-commit time (§5) remains the
authoritative arbiter on Folia exactly as on Spigot — the Anvil subsystem
is advisory on every platform that consumes it.

### 11.2 Folia-specific orchestration

`FoliaRTPWorld.getChunkAt(cx, cz)` shall:

1. If the applicability gate fails, delegate to the native
   `world.getChunkAtAsync(cx, cz)` path unchanged.
2. Otherwise call
   `AnvilPrefilter.probeDetailed(worldFolder, dimensionSubpath, cx, cz,
   normalizedUnsafe, reconciler)` on `ForkJoinPool.commonPool()`.
3. If the probe returns a non-null view (`ACCEPT` or `REJECT`), publish
   it into a Folia-side `anvilCache` (peer of `BukkitRTPWorld.anvilCache`,
   bounded LRU, §9 thread-placement invariant inherited) and return a
   completed future with the live-shaped chunk key. The immediately
   following `getCachedChunk(key)` call in `LocationGenerator` receives
   a source-union `FoliaRTPChunk`.
4. If the probe returns a null view (`UNKNOWN`), fall through to
   `world.getChunkAtAsync(cx, cz)`.

There is **no null-key short-circuit** on Folia either; the §4
data-source model applies uniformly.

### 11.3 `FoliaRTPChunk` source-union

`FoliaRTPChunk` shall be a source-union over a live `org.bukkit.Chunk`
and/or an `AnvilChunkView` with the same semantics as
`BukkitRTPChunk` (§6):

- Anvil-mode answers `isAir / isSafe / getSkyLight / getSurfaceHeight`
  from the view across the full decoded Y range, so the vert adjustor
  can scan for a safe sub-surface air pocket without entering the
  region thread.
- Queries outside the decoded Y window, or that arrive after the live
  chunk has been loaded, fall through to the live `Chunk` — and
  therefore back onto the region thread, as required by Folia's
  threading model. This is the fallback path, not the common path.
- Live-load cache promotion evicts the Anvil entry (§6 invariant
  preserved).
- `RTPChunk#isSelfContained()` returns `true` for Anvil-mode instances
  so the stale-chunk guard (ADR-015) does not mis-attribute Folia
  Anvil-backed candidates to the `nullChunk` bucket when
  `FoliaRTPWorld.isChunkLoaded(cx, cz)` returns `false`.

### 11.4 Dimension folder resolution

Folia inherits Bukkit's `World#getWorldFolder()` and the same
`DIM-1/region/` (nether) / `DIM1/region/` (end) layout. The
`dimensionRegionSubpath(World)` helper that lives in `rtp-spigot-common`
(§8) must either be duplicated in `rtp-folia-common` or extracted to a
shared Bukkit-family utility; the choice is an implementation decision
for the follow-up, not an architectural one. `rtp-anvil` itself does not
move — the module invariants (§8) forbid `org.bukkit.*` imports.

### 11.5 Consequences on Folia

**Positive:**

- Selection-phase `isAir / isSafe / getSkyLight / getSurfaceHeight` and
  the vert adjustor run entirely on `ForkJoinPool.commonPool()` for
  candidates whose region file exists and decodes, removing the
  per-candidate region-thread hop. Under `/rtp`-heavy load this yields a
  measurable reduction in region-thread utilisation without adding any
  main-thread work (Folia has no single main thread in the Spigot sense).
- Ungenerated-chunk behaviour is unchanged: the applicability gate
  abstains and Folia's native async path drives generation through the
  region scheduler exactly as before.
- The stale-chunk guard (ADR-015), the Count-Bound task pipe (ADR-004),
  and `MemoryTracker` accounting are all unaffected — the subsystem
  does not allocate chunk tickets (§9).

**Negative / trade-offs:**

- Folia now carries the same "decode wasted on candidates that will be
  rejected at the live re-check" cost as Spigot (see §Consequences
  above). Net CPU is off-region and off-tick, paid for by the eliminated
  region-thread hop.
- A second platform `*RTPChunk` source-union must track any future
  refinement of the §6 invariants (cache-eviction semantics, decoded-Y
  window rules). Mitigated by keeping the two implementations
  structurally identical and covering both under the same ArchUnit
  boundary test.

### 11.6 Alternatives considered (Folia-specific)

| Alternative | Why rejected |
|-------------|--------------|
| **F1.** Leave Folia on the native async path only; no Anvil consumption. | Keeps the per-candidate region-thread hop for every live-chunk query. Under `/rtp`-heavy load this is the dominant region-thread cost, and REQ-RTP-S-005's *spirit* (no tick-thread chunk I/O) is only fully honoured on Folia if the selection-phase reads stay off-region. |
| **F2.** Route Folia through the Spigot `BukkitRTPWorld` hierarchy to share the subsystem wiring. | `FoliaRTPWorld` extends `RTPWorld<World>` directly, not `BukkitRTPWorld`. Changing Folia's super-class would entangle Bukkit-scheduler assumptions into the Folia adapter — strictly worse than copying the ~30-line `getChunkAt` orchestration. |
| **F3.** Skip the source-union on Folia and use Anvil `REJECT` as a hard gate that short-circuits `getChunkAt` with a null key. | Re-introduces the nether false-negative storm documented in §Context. The advisory-only verdict rule (§3) applies to every consumer of `rtp-anvil`. |
| **F4.** Move `BukkitRTPChunk` up into a shared Bukkit-family module. | Tempting but out of scope for this merge. Tracked as a follow-up; does not block Folia enablement. |

## 12. Observational cache-fill mode (Phase 3, 2026-04-20)

Amends `BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md` §2 and §4a. Records the
read-only, off-tick, discard-on-success contract that extends the
Anvil pre-filter into a continuous observation loop, and makes the
universal-platform biome contract an explicit invariant of the
subsystem.

### 12.1 Contract

`RegionCacheTask` carries an `observationalOnly` flag. When set, the
task:

- Runs only when `queueManager.unkeptLocations.size() >= cacheCap`
  (strict inversion of the default-mode gate, so default-mode cache
  fill always has priority for queue headroom).
- Selects a candidate via the existing `MemoryShape.select()` +
  `LocationGenerator.getLocation(...)` pipeline — no new scheduler,
  no separate cursor, no visitor throttle.
- On a safe candidate, **discards** the result. The candidate is not
  pushed to `unkeptLocations` and is not handed to any teleport
  pipeline.
- Emits no new sink calls. The side effects that matter —
  `MemoryShape#addBadLocation(long)` for rejected cells along the
  selection walk, and `MemoryShape#biomePrefixSumsCache` updates for
  every evaluated candidate — already occur inside
  `LocationGenerator` and `MemoryShape.select()` under default mode
  and are inherited unchanged.

The observational task is registered alongside the default-mode task
on the same `period` scheduler. A single `PerformanceKeys.visitorEnabled`
yaml key gates it; no `visitorRateTicks`, no `visitor.rateHz`, no
cursor persistence.

### 12.2 Relationship to the Anvil pre-filter

Because observational mode *is* the cache-fill pipeline run under a
flag, every safety or biome-resolution refinement to
`LocationGenerator` is inherited automatically. In particular:

- The Anvil-first getter chain established by
  `ANVIL_BIOME_PLAN.md` §6 (woven into
  `BukkitRTPWorld#getBiome` / `FoliaRTPWorld#getBiome`) is traversed
  by observational mode without any new wiring. The biome
  observations accumulated in `biomePrefixSumsCache` therefore
  reflect on-disk palettes whenever disk has an answer, on **every
  Bukkit-family platform**, not only vanilla Spigot.
- The applicability gate (§1) is unchanged. Observational mode
  cannot trigger force-generation: it calls the same
  `RTPWorld#getChunkAt` that the teleport pipeline calls, which on
  vanilla Spigot short-circuits to `UNKNOWN → skip` for ungenerated
  chunks and on Paper/Folia routes to the native async overload.

### 12.3 Biome-filter evaluation model

`LocationGenerator`'s biome filter evaluates the user/config-supplied
set **directly** against each candidate's biome, with a
`biomeWhitelist` polarity flag. Earlier revisions inverted a blacklist
against a world-level enumeration (`RTPServerAccessor#getBiomes` or
`AnvilRegionScanner.scanBiomes`) to materialise a "good-biomes" set;
that approach was retired because:

- The closed vanilla `Biome` enum collapses Iris/Terra/datapack
  biomes to their nearest vanilla neighbour, producing false
  negatives in the inverted set.
- An `AnvilRegionScanner` union was a cold-start remedy for the enum
  collapse but paid full-region-file I/O at startup and still
  reflected whatever was on disk, not what a live seed might produce.
- Observational cache-fill (§12.1) provides a strictly tighter
  enumeration (`MemoryShape#getObservedBiomes()`) but is empty on
  cold start, which would block every teleport in blacklist mode
  until observations accumulated.

Direct whitelist/blacklist evaluation is enumeration-free and
therefore correct on cold start on every platform. The scanner
remains available in `rtp-anvil` as a diagnostic tool invokable
through an admin command; its runtime-getter union callers
(`BukkitRTPWorld#getBiomes`, `FoliaRTPWorld#getBiomes`) have been
retired.

### 12.4 Platform-coverage invariant

The Anvil-first biome pre-step applies to **every Bukkit-family
server**. Paper inherits it through the Spigot class hierarchy
(`BukkitRTPWorld`); Folia inherits it through `FoliaRTPWorld`. No
platform adapter is permitted to bypass the pre-step on the grounds
that its live `getBiome` is "cheap" or "async-safe." This guarantees
that when an operator upgrades a Paper/Folia server across a
Minecraft version boundary and Mojang's seed-based biome assignment
drifts, the biome the player actually lands in (the `.mca` palette)
takes precedence over the live `getBiome(loc)` answer synthesised on
the new seed. Any future PR that narrows Anvil-first to a subset of
platforms shall be rejected and shall require a superseding ADR.

### 12.5 Consequences

**Positive:**

- Cold-start biome filtering is correct on every platform without a
  one-shot startup scan.
- Bad-location cache warms continuously even when no player has
  teleported into the region recently.
- Upgrade-drift regressions (player-visible biome diverging from the
  allow-list after a Paper/Folia MC upgrade) are eliminated by
  construction.

**Negative / trade-offs:**

- Observational mode contributes to `pendingBadLocations` without an
  originating teleport request. Documented at the call site; the
  escape hatch is `visitor.enabled: false`.
- Claim-plugin predicates run on observational candidates. Operators
  with expensive claim-plugin integrations can disable the feature
  via the same flag.

## 13. Chunk-data precedence over live world getters (2026-04-20f)

Amends §12.4 by elevating the Anvil-first rule from a **biome-only**
pre-step to a **general precedence rule** over every live
`org.bukkit.World` getter that the selection and safety pipeline
consults.

### 13.1 Precedence rule

Every adapter on a Bukkit-family platform in scope of this ADR shall
resolve chunk-scoped data — blocks, biomes, heightmaps, light — from
one of the following sources, in strict priority order:

1. **A currently loaded `org.bukkit.Chunk`** cached in the adapter's
   live `chunkCache` (`WeakReference<Chunk>`). This is the
   authoritative source once a live load has resolved.
2. **An `AnvilChunkView`** decoded from the on-disk `.mca` region file
   and held in the per-world `AnvilProbeSupport` cache.
3. **A live `org.bukkit.World` getter** (`world.getBiome(x,y,z)`,
   `world.getBlockAt(...)`, etc.) — permitted **only** under the
   vanilla-generator exemption below (§13.3).

The rule applies uniformly to `getBiome`, block reads, and any future
chunk-scoped getter added to `RTPWorld` or `RTPChunk`. A decoded
`.mca` palette is, by construction, a strictly more faithful
representation of what the player will see than any live-getter
answer synthesised from the current world seed and generation
algorithm — the on-disk bytes are the ground truth the client
receives when it streams the chunk.

### 13.2 Platforms in scope

- **Spigot** — required; already implemented via `BukkitRTPWorld`.
- **Paper** — required; **compliant as of 2026-04-20**. The
  previous `PaperRTPWorld#getChunkAt(int, int)` override in
  `rtp-paper-v1_20_R1`, `rtp-paper-v1_21_R1`, and
  `rtp-paper-v26_1_R1` — which short-circuited directly to
  `world.getChunkAtAsync(cx, cz)` without invoking
  `AnvilProbeSupport#probeAndPublish` — has been removed. Paper
  now inherits `BukkitRTPWorld#getChunkAt` verbatim, which routes
  every candidate through `anvilProbeSupport.probeAndPublish(...)`
  before falling through to the reflective async live-load (which
  resolves to Paper's native `World#getChunkAtAsync(int, int)` via
  the `CHUNK_AT_ASYNC_FUTURE` reflective handle in
  `BukkitRTPWorld`). The regression is pinned by
  `ReqRtpAnvilFirstTest` under `rtp-paper-v1_20_R1`.
- **Folia** — required; already implemented via `FoliaRTPWorld`.
- **Fabric** — **out of scope for this ADR.** The `rtp-fabric` port
  is an active development frontier with known blockers
  (`MULTI_PLATFORM_PLAN.md`); an Anvil-first equivalent on Fabric
  shall be specified in a separate plan / ADR and shall not be
  back-constrained by this section.

### 13.3 Vanilla-generator exemption

Falling back to the live `world.getBiome(x, y, z)` (or any equivalent
live getter) is permitted **only** when **all** of the following hold
for the world in question:

1. The world's `ChunkGenerator` is the vanilla Minecraft generator —
   no custom `ChunkGenerator`, no mod-installed generator, no Iris /
   Terra / Chunky-configured generator, no datapack world preset
   that introduces non-vanilla biomes.
2. The world's biome source yields no custom / namespaced biomes
   outside the vanilla `minecraft:*` namespace — no datapack biomes,
   no mod biomes, no Iris biome names.
3. The Anvil source has returned UNKNOWN for the target chunk
   (no region file, unsupported DataVersion, decode miss, or the
   chunk is not yet populated). UNKNOWN is load-bearing: on an
   unpopulated chunk there is no persisted truth to defer to.
4. The target chunk has **not** yet been generated and persisted to
   disk (the adapter's non-blocking `RTPWorld#isChunkGenerated(cx,cz)`
   returns `false`). Worlds frequently outlive their seed's stability:
   a server upgraded across MC versions can hold persisted `.mca`
   palettes that disagree with the new seed's synthesised biome source
   for already-generated chunks — even on pure-vanilla worlds. The
   persisted palette is the source of truth for any chunk already on
   disk, so the live-getter fallback is forbidden there and the §13.1
   precedence chain (loaded chunk → AnvilChunkView → live getter)
   applies without exemption.

When any of 1, 2, or 4 is unknown at runtime, the adapter shall treat
the world / chunk as **non-exempt** and shall not fall back to the
live getter except under condition 3. Detection is implementation-level
(reflective generator check, biome-source namespace sniff,
operator-set override, native `World#isChunkGenerated` delegation) and
out of scope for this ADR; the ADR mandates only the contract.

### 13.4 Rationale

- **Upgrade drift** (already documented in §12.4 and
  `BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md §4a`) — Mojang's
  seed-based biome assignment can shift across MC version
  boundaries. On-disk data remains consistent with what the player
  sees; the live getter does not.
- **Custom generators** (Iris, Terra, mod-backed generators) — the
  live `Material` / `Biome` enum views silently collapse namespaced
  identifiers to their nearest vanilla cousin, producing false
  negatives in both the biome allow-list and the unsafe-blocks
  list. The `.mca` palette preserves namespaced identifiers
  verbatim; `PaletteNormalizer.reconcile` is the single place where
  reconciliation against operator strings happens.
- **The Iris addon's remaining `setBiomeGetter` hook** exists today
  *only* because Paper bypasses Anvil-first (§13.2). Once Paper
  complies, the per-coord resolver hook becomes redundant on every
  in-scope platform and the addon can be reduced further.
- **The vanilla exemption is a pragmatic escape hatch**, not a
  permanent concession. It exists so that servers running pure
  vanilla generation on a stable MC version — where upgrade drift
  and namespace collapse cannot arise by construction — do not pay
  unnecessary `.mca` I/O for cache-warm live chunks. It is
  explicitly **not** a license to introduce new live-getter code
  paths on non-vanilla worlds.

### 13.5 Enforcement

- New code that introduces a live `world.getBiome(...)` or
  equivalent live getter call from `rtp-spigot-common`,
  `rtp-paper-*`, `rtp-folia-common`, or `rtp-folia-*` shall be
  rejected at review unless it is gated on the §13.3 exemption
  **and** on an `AnvilProbeSupport` UNKNOWN outcome for the chunk.
- An ArchUnit-style guard covering this precedence may be added as a
  follow-up; its absence does not soften the rule.
- Any future ADR that narrows §13.1 — including the one specifying
  Fabric — shall explicitly supersede this section.

## References

- REQ-RTP-S-001 (no unsafe-block teleport destinations) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-S-002 (no permanently force-loaded chunks) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-S-004 (no silently discarded teleport failures) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-S-005 (no chunk loading on the main thread) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-SYS-002 (Spigot support) — `docs/dev/REQUIREMENTS.md §0`.
- ADR-004 "Count-Bound Task Pipe on Folia".
- ADR-006 "Async Queue Pre-Generation".
- ADR-015 "Stale-Chunk Guard for Count-Bound Pipes".
- Plan: `docs/dev/ANVIL_PREFILTER_PLAN.md`, `docs/dev/ANVIL_SHARED_MODULE_PLAN.md`.
- Regression guards: `AnvilPrefilterTest`, `AnvilFixtureParityTest`,
  `AnvilChunkViewTest`, `PaletteNormalizerTest`,
  `AnvilPackageBoundaryArchTest`, `ReqRtpS004NullChunkAttributionTest`.

## Follow-ups

- Author `rtp-anvil/REQUIREMENTS.md` covering the DataVersion compatibility
  policy and the "advisory only — never authoritative" invariant.
- Implement §11 on Folia (`FoliaRTPChunk` source-union,
  `FoliaRTPWorld.getChunkAt` probe-then-fall-through, Folia-side
  `anvilCache`).
- Phase 2 biome-palette resolution: see
  `docs/dev/ANVIL_BIOME_PLAN.md` for the design of
  `AnvilChunkView#getBiomeAt` / `getBiomesPresent`, the `.mca`-first
  composite biome getter, and the resulting shrink of
  `RTP_Iris_integration`.
- **§13 compliance on Paper (landed 2026-04-20).** The redundant
  `PaperRTPWorld#getChunkAt` override was deleted in every
  `rtp-paper-v*` module, so Paper now inherits the Spigot
  pre-filter orchestration unchanged. The reflective
  `CHUNK_AT_ASYNC_FUTURE` live-load path in `BukkitRTPWorld`
  resolves to Paper's native `World#getChunkAtAsync(int, int)` at
  runtime, so the former override was providing no behavioural
  difference beyond skipping the Anvil probe. Regression guard:
  `ReqRtpAnvilFirstTest` under `rtp-paper-v1_20_R1` (asserts
  `PaperRTPWorld` does not re-declare `getChunkAt`/`getBiome`). The
  stale in-code comment on `BukkitRTPWorld.java:160` ("Paper and
  Folia @Override this method and never enter the prefilter") has
  been rewritten to reflect §13.2.
- **§13 vanilla-generator detection + upgrade-drift gate (landed 2026-04-20).**
  `RTPWorld#isVanilla()` is defined in `rtp-api` with a
  conservative `false` default; `BukkitRTPWorld` and `FoliaRTPWorld`
  override it as
  `world.getGenerator() == null && world.getBiomeProvider() == null`
  with `Throwable → false` safety. Companion API
  `RTPWorld#isChunkGenerated(cx,cz)` is defined alongside with a
  conservative `true` default ("assume generated → skip pre-check"),
  overridden on Bukkit/Folia to delegate to the native non-blocking
  `org.bukkit.World#isChunkGenerated(int,int)` with `Throwable → true`
  safety. `LocationGenerator` consults both per candidate: the
  pre-chunk-load biome pre-check runs only when
  `isVanilla() && !isChunkGenerated(cx,cz)`. Vanilla worlds keep
  the optimisation for ungenerated candidates (rejected before the
  live chunk load using the seed-synthesised biome that matches
  what the client will see on first generation); non-vanilla worlds
  (Iris, Terra, datapack presets, or any world that installs a
  custom `ChunkGenerator` / `BiomeProvider`) and already-generated
  chunks on any world skip the pre-check and defer to the post-load
  biome read, which is routed through the §13.1
  chunk-data-precedence chain. The vanilla detection does not cover
  mods that replace generation via NMS mixins without touching the
  public Bukkit hooks — such worlds are misidentified as vanilla,
  which is a known accepted gap (the post-load read still corrects
  the answer, so the only cost is a wasted chunk load per candidate
  whose seed-biome happens to pass the filter while the palette
  biome would not).
- **§13.1 biome-source runtime telemetry (landed 2026-04-20).**
  `rtp-anvil/BiomeSourceMetrics` exposes two process-global
  `AtomicLong` counters — `anvilHits` (biome read answered from the
  cached `AnvilChunkView`) and `liveHits` (fell through to
  `world.getBiome(...)`). `BukkitRTPWorld#getBiome` and
  `FoliaRTPWorld#getBiome` each call `BiomeSourceMetrics.record(...)`
  exactly once per read so the two counters sum to the total biome-read
  count on every in-scope Bukkit-family adapter (Paper inherits via
  §13.2). Surfaced at runtime by `rtp test biome-source` (and the
  umbrella `rtp test full`); a sustained non-zero `anvilHits` count is
  the live evidence that §13.1 precedence is active rather than merely
  architecturally asserted. Companion unit guard:
  `TestBiomeSourceCmdTest` (snapshot arithmetic + `record` dispatch).
  Remaining gap: no test reproduces the §13 upgrade-drift scenario
  end-to-end (persisted palette disagrees with new seed) against a
  real world fixture; scoped as a separate follow-up along with CI
  matrix execution of `rtp test full` per platform (ROADMAP line 80).
- **Fabric §13 equivalent (deferred).** A separate plan / ADR shall
  specify the chunk-data-precedence contract for `rtp-fabric`. It must
  not be back-constrained by this ADR's Bukkit-family assumptions and
  shall explicitly supersede §13 for its platform when authored.
- **§13.1 reason-keyed biome-source telemetry + silent-fallthrough logs (landed 2026-04-20).**
  Operator reported `anvil-hits=0` on a Chunky-pregenned 1.21.x server with
  no log lines visible. Root-cause audit found three structural bypasses
  that made the "why" invisible: (1) `LocationGenerator` pre-chunk-load
  biome reads fire before the probe runs for that key; (2)
  `BukkitRTPWorld#getBiome` / `FoliaRTPWorld#getBiome` silently fell through
  to the live getter on every no-view path; (3) the existing per-reason
  INFO log budget of 20 was exhausted after warmup, demoting every
  subsequent diagnostic to FINE. Fixes applied across options A+B+C:
    - `BiomeSourceMetrics` extended with a reason-keyed
      `ConcurrentHashMap<String, AtomicLong>` alongside the two legacy
      `anvilHits` / `liveHits` totals. New canonical reasons:
      `anvil-hit`, `no-view-cached`, `view-missing-biome`, `anvil-throw`.
      `record(String)` updates both views in lock-step; `record(boolean)`
      is retained as a delegating shim. `reasonCounters()` returns an
      iteration-order-stable `LinkedHashMap` snapshot.
    - `BukkitRTPWorld#getBiome` and `FoliaRTPWorld#getBiome` now dispatch
      `record(reason)` on every exit path and emit a rate-limited
      `[RTP] Anvil biome fallthrough reason=<reason> world=<w> chunk=(cx,cz)`
      line per `BIOME_LOG_BUDGET_PER_REASON = 200` (INFO, then FINE).
    - `AnvilPrefilter.DIAG_LOG_BUDGET_PER_REASON` and the two adapter
      `GATE_SKIP_BUDGET_PER_REASON` constants raised from `20` to `200`
      so operators running `rtp test biome-source` minutes into a session
      still see the first few distinct-reason events at INFO.
    - `TestBiomeSourceCmd` prints the reason breakdown on one extra
      `[RTP test/biome-source] reasons: anvil-hit=<n> no-view-cached=<n> ...`
      line (option C), so the attribution is available persistently at
      any log level.
  Regression guards: existing `TestBiomeSourceCmdTest` (6 tests, still
  exercises `record(boolean)`) and `TestFullCmdTest` (8 tests, sweep
  continuity) remain green. Remaining ADR follow-ups: end-to-end
  upgrade-drift reproducer fixture and CI-matrix execution of
  `rtp test full` per platform (ROADMAP line 80).
- **DataVersion gate widened from exact-values to a range (landed 2026-04-20).**
  `DataVersionSupport.isSupported` previously admitted only three
  fixture-validated integers (`3465`, `4671`, `4788`), so every real
  server running a patch version other than the exact fixture build
  observed zero Anvil probe hits — the probe returned `UNKNOWN` for
  every chunk, defeating §13.1 precedence in practice. The gate now
  accepts any DataVersion in the inclusive range
  `[MIN_SUPPORTED_DATA_VERSION (3454), MAX_SUPPORTED_DATA_VERSION (5000)]`,
  which covers 1.20.x, 1.21.x, and the 26.x family (and leaves room
  for forward patch releases). The three fixture constants are
  retained verbatim for parity-test traceability; the range form does
  not weaken the "unsupported → UNKNOWN → live load" fall-through
  contract for pre-1.20 formats or future DataVersions beyond the
  ceiling. Detection path: operator reported sustained `0` Anvil hits
  on `rtp test biome-source` / `rtp test anvil-prefilter` after a
  Chunky pre-generation pass on a 1.21.x server — the §13.1 telemetry
  that landed in the prior follow-up made the regression visible.
  Follow-up: when a MC release ships a breaking chunk NBT layout
  change (section Y-range shift, heightmap bit-width change, or a new
  compression mode beyond 4/LZ4), tighten `MAX_SUPPORTED_DATA_VERSION`
  and add a fresh fixture per `ANVIL_PREFILTER_PLAN.md` §8.2 before
  raising it again.
- **§13.3 pre-chunk-load biome pre-check retired (landed 2026-04-20).**
  Operator reported sustained `reason=no-view-cached` lines during
  `rtp test biome-source` on a Chunky-pregenned vanilla world. Root
  cause: `LocationGenerator.java:549,627` called
  `world.getBiome(blockX, midY, blockZ)` BEFORE the Anvil probe ran
  for that candidate's chunk key, so every such read bypassed the
  §13.1 precedence chain and fell straight to the live
  `world.getBiome(...)`. The pre-check was gated on
  `isVanilla() && !isChunkGenerated(cx,cz)`, but on pregenerated
  worlds the gate passed whenever the random candidate fell outside
  the pregen radius — a common case. The authoritative post-load
  biome read at `LocationGenerator` line ~724 (now through the §13.1
  three-tier chain) already covers correctness, so the pre-check was
  a pure optimisation whose observable effect was the telemetry noise.
  The active path now sets `currBiome = ""` unconditionally before
  the world-border check and defers all biome validation to the
  post-load read. The pre-check block is preserved intact under a
  `private static final boolean PRE_CHUNK_BIOME_PRECHECK_ENABLED =
  false` constant so it can be flipped back on without code
  archaeology if a future workload (e.g. bounded biome-targeted
  search on very large regions) needs the short-circuit. The §13.3
  vanilla-exemption + upgrade-drift-gate contract on `RTPWorld`
  (`isVanilla`, `isChunkGenerated`) is retained — both hooks remain
  available to the preserved pre-check block and to any future
  caller that wants to consult them. Regression surface: the
  `rtp-core` region suite (423 passed, 1 pre-existing ignored) plus
  `rtp-spigot-common`, `rtp-folia-common`, and `rtp-paper-v1_20_R1`
  continue green with the pre-check disabled, confirming the
  post-load read fully covers the biome-filter contract.
