# ADR-016 — Anvil Read-Only Pre-Filter for Vanilla Spigot Safety Checks

**Status:** Proposed
**Date:** 2026-04-18

## Context

On Paper and Folia, `World#getChunkAtAsync(int, int)` returns a
`CompletableFuture<Chunk>` that resolves off the tick thread, and the RTP
teleport pipeline exploits this to keep `LocationGenerator`'s block-sampling
and `vert.adjust(chunk)` work off the primary thread. On vanilla Spigot that
overload is not available — the only async chunk APIs on Spigot are the
`Consumer`-based overloads, all of which still dispatch the actual chunk load
back onto the server tick thread. The reflective probe introduced in
`BukkitRTPWorld.CHUNK_AT_ASYNC_FUTURE` (see previous session) only helps on
forks that expose the `CompletableFuture` overload at runtime.

Two concrete costs follow on vanilla Spigot:

1. **Main-thread pressure.** Every candidate evaluation for a player's
   teleport ends up scheduling a `runTask(...)` for `world.getChunkAt(cx, cz)`
   on the primary thread just to read a single block material, a height-map
   value, and the sky light level. Under `/rtp`-heavy workloads (public
   minigame servers, giveaway events) this directly competes with player tick
   work. The existing stale-chunk guard (ADR-015) and Count-Bound pipeline
   (ADR-004) both assume the underlying per-chunk read is cheap; on vanilla
   Spigot that assumption breaks.

2. **Wasted generation.** When a candidate lands in an ungenerated chunk, the
   sync `getChunkAt(...)` call forces the server to *generate* that chunk just
   so RTP can read a single column and (most of the time) reject the candidate
   anyway. Paper and Folia amortize this; vanilla Spigot does not.

For **chunks that are already generated on disk but currently unloaded**, the
block, biome, heightmap, and sky-light data that RTP needs for its safety
filter is already sitting in the region file (`<world>/region/r.<x>.<z>.mca`)
in the Anvil format. The Anvil format is a stable, documented serialization
of chunk data, and reading it is a pure file I/O operation — no tick thread
involvement is required.

The question posed by the maintainer: can RTP, on platforms that do not
expose a truly async chunk API, satisfy its **safety pre-filter** off the
tick thread by reading the region file directly, while still falling back
to a real chunk load for the final teleport?

## Decision

RTP shall introduce an optional **read-only Anvil pre-filter** layered on top
of the existing `RTPWorld.getChunkAt(cx, cz)` path. The pre-filter is
advisory — it never replaces the authoritative chunk load for a teleport
destination. Its purpose is to reject clearly-unsafe candidates off the tick
thread so that only plausibly-safe candidates incur a real chunk load.

The contract:

1. **Applicability gate (read-only).** The pre-filter runs for a candidate
   chunk `(cx, cz)` if and only if *all* of the following are true:
   - The platform adapter has opted into the pre-filter (default: enabled on
     `rtp-spigot-common`; Paper/Folia adapters keep their native async
     overrides and bypass the pre-filter entirely).
   - `RTPWorld.isChunkLoaded(cx, cz) == false`. This is the same non-blocking
     lookup added by ADR-015, and its use here is load-bearing: a loaded
     chunk may hold unflushed edits that disagree with the on-disk region
     file. Skipping the pre-filter for loaded chunks is the only cheap,
     correct way to avoid read/write desync without coordinating with the
     server's chunk I/O thread.
   - The region file for `(cx, cz)` exists on disk (checked via
     `Path.exists` on `<worldFolder>/region/r.<rx>.<rz>.mca`). An absent
     region file means the chunk is ungenerated; the candidate falls through
     to the existing real-load path, which will drive generation through the
     platform's native scheduler as today. This preserves the maintainer's
     explicit requirement that generation behavior is unchanged.
   - The active world does not declare a non-default custom generator
     (`World#getGenerator() == null`). When a custom generator is present,
     the block palette in the region file may reference data-pack-only
     block identifiers; the pre-filter abstains rather than risk a false
     "safe" decision, and the candidate falls through to the real-load
     path.

2. **Format detection with fallback.** The reader probes the chunk NBT for a
   `DataVersion` tag and dispatches to a registered format adapter. If no
   adapter matches, the pre-filter returns `Unknown` and the candidate falls
   through to the real-load path — never rejected, never a false positive.
   Format adapters are additive; a new Minecraft version that ships an
   incompatible format simply returns `Unknown` until an adapter for it is
   added, with no regression in teleport correctness.

3. **Information produced.** The pre-filter returns one of three verdicts:
   - `Reject` — the read succeeded and the landing-block column contains a
     material whose *normalized identifier* matches an entry in
     `SafetyKeys.unsafeBlocks` (or a biome whose normalized identifier
     matches the region's biome blacklist). Normalization is symmetric on
     both sides: palette entries shaped like `minecraft:lava` are resolved
     through `Material.matchMaterial(...)` (respectively `Registry.BIOME`)
     to the canonical enum `.name()` form (`LAVA`), and entries that do
     not resolve (modded identifiers) fall back to namespace-stripping
     plus upper-casing. The user-supplied unsafe list is put through the
     identical normalization once at config load, so `LAVA`,
     `minecraft:lava`, and `MINECRAFT:LAVA` are all accepted and compare
     equal. This way the pre-filter does not impose a namespace assumption
     of its own — it honors whatever identifier style the config already
     uses. The candidate is dropped through the existing "unsafe"
     rejection path (REQ-RTP-S-004 preserved: a WARN log is emitted on
     retry-budget exhaustion, never a silent discard).
   - `Accept` — the read succeeded and the column passes all pre-filter
     checks. The candidate still proceeds to a **real** chunk load and a
     **second** `isSafe` evaluation against the live chunk. The pre-filter's
     `Accept` is never the source of truth for a teleport.
   - `Unknown` — the read did not complete (format unsupported, I/O error,
     corrupted section, thread interruption). The candidate proceeds to a
     real chunk load exactly as it would today.

4. **Authoritative re-check preserved.** The existing safety check against
   the live `RTPChunk` inside `LocationGenerator.getLocation(...)` is **not**
   removed or weakened. Every candidate that clears the pre-filter still
   passes through `chunk.isSafe(xx, y, zz, unsafeBlocks)` on the real chunk
   before the pipeline commits to a teleport. REQ-RTP-S-001 is therefore
   satisfied by the existing check, identically to today; the pre-filter
   only ever strengthens the rejection side, never the acceptance side.

5. **Thread placement.** All region-file I/O runs on the RTP async executor
   pool (`RTP.serverAccessor.getScheduler()`'s async track on Spigot). No
   read shall be performed on the tick thread. A small LRU cache of recently
   decoded region files (`Map<RegionKey, WeakReference<Mca>>`) bounds file
   churn; eviction is driven by `MemoryTracker` to keep the footprint
   predictable (REQ-RTP-S-002 is unaffected — the pre-filter does not
   allocate chunk tickets).

6. **Scope boundary.** The pre-filter is a feature of `rtp-spigot-common`
   and its versioned submodules. It shall not be referenced from `rtp-core`
   or `rtp-api`: those modules remain platform-agnostic, and the
   `RTPWorld.getChunkAt(...)` contract is unchanged from their perspective.
   The pre-filter is an internal optimization of the Spigot adapter's
   pipeline — future adapters (Fabric in particular) are free to reuse the
   reader but are not obligated to.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Always read from the region file and never load the chunk until teleport commit | Region files lag behind live world state (unflushed edits, pending block updates); an `Accept` based purely on disk contents can legitimately disagree with the live world. Using disk as the source of truth would create a new class of "teleport into lava" bugs that REQ-RTP-S-001 explicitly forbids. Skipping the pre-filter for loaded chunks, as decided, sidesteps the desync class entirely. |
| Hold the pre-filter `Accept` verdict as the sole safety check and drop the post-load re-check | Same desync class as above; also breaks the existing test contract that `LocationGenerator` calls `chunk.isSafe(...)` on a live chunk. Pure strict-dominance violation. |
| Ship a full `RTPChunk` implementation backed by the Anvil reader and use it as a first-class candidate source | Massive surface: `isAir`, `getSkyLight`, `getSurfaceHeight`, `isSafe`, plus lifetime (`keep`, `unload`) would all need Anvil-backed semantics. The teleport pipeline would have to reason about "virtual" vs "live" chunks everywhere. Defer indefinitely; the pre-filter captures ~all of the real-world benefit without this complexity. |
| Vendor a third-party NBT/Anvil library (Querz, Hephaistos, ensgijs:mca) | Adds a runtime dependency to the plugin jar. The RTP-side needs (palette decode, heightmap decode, biome palette decode, sky-light array) are a small subset of those libraries' surface, and the licensing / shading story is non-trivial. A hand-rolled minimal reader scoped to these four needs is preferred, with the library path kept open as a future migration if the format tree becomes too painful to maintain. |
| Gate the pre-filter behind format detection only, without the `isChunkLoaded` check | Correct for ungenerated chunks but wrong for loaded chunks: the live chunk may have player-placed blocks (lava buckets, modified safety blocks) not yet flushed to the region file. Loaded chunks are also already fast to read — the pre-filter buys little there. Gating on `isChunkLoaded == false` is both cheaper and safer. |
| Pre-generate Anvil parsing eagerly for all candidate chunks during queue replenishment | Turns the pre-filter from an optimization into a background I/O burst. Conflicts with ADR-006 (async queue pre-generation). Keep the pre-filter lazy and per-candidate. |
| Skip vanilla Spigot entirely and tell users to install Paper | Not an architectural decision RTP gets to make. Spigot remains a supported platform (REQ-RTP-SYS-002). |

## Consequences

- **Positive:**
  - On vanilla Spigot with generated chunks, clearly-unsafe candidates (lava,
    void, magma, fire) are rejected without any tick-thread work. The tick
    thread only pays for the final `getChunkAt(...)` on candidates that
    already passed the disk-side filter, which is a large constant-factor
    improvement under RTP-heavy load.
  - Ungenerated chunks still drive generation through the platform's native
    scheduler exactly as today (maintainer's stated constraint preserved).
    The pre-filter is additive: removing it restores the previous behavior
    verbatim.
  - Loaded chunks are never read from disk, eliminating the desync /
    corruption risk class by construction. The `isChunkLoaded` gate is the
    single invariant that keeps the pre-filter correct.
  - Format detection with fall-through means a new Minecraft version never
    regresses teleport correctness — the pre-filter simply abstains until an
    adapter is written. Maintainers control the rollout per format.
  - Paper and Folia are entirely unaffected: their existing async overrides
    in `PaperRTPWorld` / `FoliaRTPWorld` short-circuit the pre-filter path.

- **Negative / Trade-offs:**
  - Adds a platform-side reader subsystem with its own test surface (at
    least: palette decode round-trip, heightmap decode, biome decode,
    format-version dispatch, and corrupted-file fallback). The test matrix
    needs fixture region files for each supported `DataVersion`.
  - Hand-rolled Anvil decoding is maintenance-bearing: section palette
    encoding, heightmap long-packing width, and biome storage layout have
    all changed across Minecraft versions. Format adapters must be kept
    current; a stale adapter that wrongly claims to support a newer
    `DataVersion` could produce `Accept` verdicts on misread data. This is
    mitigated by the post-load authoritative re-check (REQ-RTP-S-001) but
    still costs wasted chunk loads.
  - Does **not** assume the vanilla namespace. Block and biome identifiers
    from the region-file palette are resolved through a symmetric
    normalization: palette entries shaped like `minecraft:lava` are routed
    through `Material.matchMaterial(...)` (respectively `Registry.BIOME`)
    and the resulting enum `.name()` is used as the canonical key. Entries
    that do not resolve (e.g. modded identifiers shipped by Mohist or
    Arclight) fall back to a namespace-stripping + upper-casing rule
    (`mohist:copper_wire` → `COPPER_WIRE`). The user-supplied
    `SafetyKeys.unsafeBlocks` list is put through the identical
    normalization once at config load, so operators may write entries in
    any of the forms `LAVA`, `minecraft:lava`, or `MINECRAFT:LAVA` and all
    three compare equal to what the reader produces. The pre-filter
    therefore honors whatever identifier convention the config already
    uses; it does not introduce a vanilla-namespace assumption of its own.
  - Residual caveats (narrow): a palette entry whose identifier is not
    present in the normalized unsafe-block set is treated as "unknown
    safety" — the candidate is **not rejected by the pre-filter**, and the
    live `isSafe(...)` re-check on the real chunk remains authoritative
    (REQ-RTP-S-001 is satisfied identically to today). Likewise, identifier
    renames across Minecraft versions (e.g. `grass_path` → `dirt_path` in
    1.17) are the responsibility of each format adapter; an adapter that
    does not know an alias returns `Unknown` for the affected column, and
    the candidate falls through to the real-load path.
  - Adds a new configuration toggle
    (`safety.yml`: `anvilPrefilterEnabled`, default `true`) so operators on
    exotic forks can disable the pre-filter without rebuilding.

## References

- REQ-RTP-S-001 (no unsafe-block teleport destinations) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-S-002 (no permanently force-loaded chunks) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-S-004 (no silently discarded teleport failures) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-S-005 (no chunk loading on the main thread) — `docs/dev/REQUIREMENTS.md §3`.
- ADR-004 "Count-Bound Task Pipe on Folia".
- ADR-006 "Async Queue Pre-Generation".
- ADR-015 "Stale-Chunk Guard for Count-Bound Pipes" — reuses the
  `RTPWorld.isChunkLoaded(int, int)` contract as the applicability gate.
- Anvil format reference: Minecraft Wiki "Anvil file format" and "Chunk
  format" pages; cross-checked against vanilla `net.minecraft.world.chunk`
  serialization for each supported `DataVersion`.
- Planned implementation targets:
  - `rtp-spigot-common`: `io.github.dailystruggle.rtp.spigot.anvil`
    package — `AnvilRegionReader`, `AnvilChunkView`, `AnvilFormatAdapter`,
    `AnvilPrefilter`.
  - `rtp-spigot-common` wire-in: `BukkitRTPWorld.getChunkAt(cx, cz)` routes
    through `AnvilPrefilter.evaluate(cx, cz)` before falling back to the
    existing reflective-async / sync-load pipeline. Paper and Folia
    adapters do not invoke the pre-filter.
  - Configuration: `safety.yml` — new key `anvilPrefilterEnabled`
    (default `true`).
- Test plan:
  - Unit tests for each format adapter round-trip using checked-in fixture
    region files (one per supported `DataVersion`).
  - Corrupted-file fixture returning `Unknown` and asserting the candidate
    falls through to the real-load path with no exception raised.
  - Loaded-chunk fixture asserting the pre-filter is skipped entirely when
    `RTPWorld.isChunkLoaded` returns `true`.
  - ArchUnit rule: no class in `rtp-core` or `rtp-api` references the
    `io.github.dailystruggle.rtp.spigot.anvil` package.
  - Traceability entries added to `docs/dev/TRACEABILITY.md` under
    REQ-RTP-S-005.
