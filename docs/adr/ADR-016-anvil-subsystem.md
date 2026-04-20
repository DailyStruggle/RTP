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
