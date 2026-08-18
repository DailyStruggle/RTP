# rtp-fabric-ADR-005 — Fabric anvil pre-filter parity

- **Status:** Accepted
- **Date:** 2026-05-06
- **Subproject:** `rtp-fabric` (`rtp-fabric-common` adapter)
- **Related:** [ADR-016 — Anvil subsystem](../../../../docs/adr/ADR-016-anvil-subsystem.md), [rtp-fabric-ADR-002 — Platform in-scope](rtp-fabric-ADR-002-platform-in-scope.md), [`docs/dev/MULTI_PLATFORM_PLAN.md`](../../../../docs/dev/MULTI_PLATFORM_PLAN.md), `BIOME_LOOKUP_PERF_PLAN.md`
- **Supersedes / Superseded by:** —

## Context

`rtp-core`'s background prefill loop (`ScanTask`, `PregenTask`, `QueueTask`) takes a probe-first fast path: each candidate chunk is asked for a column probe via `RTPWorld#probeChunkColumn(cx, cz, minY, maxY)` before any live chunk load. On Bukkit/Folia this is overridden by `BukkitRTPWorld#probeChunkColumn` (and `FoliaRTPWorld#probeChunkColumn`), which reads the persisted `r.X.Z.mca` region files directly via `rtp-anvil` on a dedicated I/O pool (`AnvilIoPool`) and returns an `AnvilColumnProbeAdapter`. The dominant ScanTask candidate cost on those platforms is therefore an off-tick `.mca` byte read.

`rtp-fabric-common` did **not** override `probeChunkColumn`. The default in `RTPWorld` returns a completed `null` future (UNKNOWN), which `ScanTask.tryProbeFirstScan` interprets as "fall through to `runFullLoadPath`". `runFullLoadPath` then calls `FabricRTPWorld.getChunkAt`, whose own javadoc admits that on a cache miss it dispatches `cache.getChunk(cx, cz, ChunkStatus.FULL, /*load=*/true)` inside `MinecraftServer#submit` — i.e. a synchronous chunk *generation* on the server tick thread. Every refill candidate burned ~7–14 ms of tick time, producing the user-reported "rtp commands take 14 ms instead of 1 ms" symptom under steady-state queue refill — even though the consumer side (`/rtp` polling `keptLocations`) was cheap.

The ADR-016 `rtp-anvil` module is platform-neutral by construction (no `org.bukkit.*` dependency, all I/O surface keyed off `java.nio.file.Path`), so wiring it into Fabric is a structural change confined to `rtp-fabric-common` and one Gradle dep.

## Decision

Implement Fabric anvil pre-filter parity as an override of `probeChunkColumn` in `FabricRTPWorld`, mirroring the Bukkit implementation:

1. Add `api project(':rtp-anvil')` to `rtp-fabric/rtp-fabric-common/build.gradle`.
2. Override `FabricRTPWorld#probeChunkColumn(cx, cz, minY, maxY)`:
   - Apply the same gates as Bukkit: skip when the chunk is currently loaded (live state is authoritative) and when `SafetyKeys.anvilPrefilterEnabled` is falsy. Return `CompletableFuture.completedFuture(null)` (UNKNOWN) on a closed gate so the pipeline falls through to the live path, preserving the `.mca`-as-advisory invariant of ADR-016.
   - Resolve the world folder via `MinecraftServer#getWorldPath(LevelResource.ROOT)` (the Fabric/vanilla equivalent of Bukkit's `World#getWorldFolder()`).
   - Derive the dimension subpath from `ServerLevel#dimension().location()` (vanilla has no `World.Environment` enum):
     - `minecraft:overworld` → `""`
     - `minecraft:the_nether` → `"DIM-1"`
     - `minecraft:the_end` → `"DIM1"`
     - anything else → `"dimensions/<namespace>/<path>"` (Fabric-API custom-dimension layout)
   - Dispatch the actual `AnvilRegionByteCache.get(...)` + `AnvilReader.readColumnProbe(...)` work onto `AnvilIoPool.get()` so the read never executes on a server tick or region thread (S-005). All exceptions resolve to `null` (UNKNOWN) — never to a propagated failure.
3. Add `FabricAnvilColumnProbeAdapter` in `io.github.dailystruggle.rtp.fabric.anvil`, adapting `rtp-anvil`'s `ColumnProbe` to `ChunkColumnProbe`. Identifier reconciliation uses `PaletteIdentifierNormalizer` (the platform-neutral namespace-strip + upper-case helper in `rtp-api`) instead of Spigot's `Material`-aware `PaletteNormalizer`, because Fabric has no `Material` enum and `SafetyKeys.unsafeBlocks` entries are normalised the same way at config-read time. `isAirAt(...)` is overridden to tolerate both reconciled (`AIR` / `CAVE_AIR` / `VOID_AIR`) and raw (`minecraft:air` etc.) forms — the same regression mode previously caught on Spigot by `AnvilColumnProbeAdapterIsAirAtTest`.

`rtp-anvil` itself is **not** modified — the platform-neutral surface (`AnvilPrefilter.regionFileFor`, `AnvilRegionByteCache`, `AnvilReader.readColumnProbe`, `AnvilIoPool`) is consumed unchanged.

The full anvil-backed `RTPChunk` view (the equivalent of Spigot's `BukkitRTPChunk` anvil mode for the safety stage) is intentionally **out of scope** for this ADR. The column probe alone moves the bulk of `ScanTask` work off the tick thread; an anvil-backed `FabricRTPChunk` is a follow-up.

## Consequences

### Positive
- ScanTask refill on Fabric serves most candidates from `.mca` bytes off-tick instead of triggering synchronous `FULL` chunk generation on the server tick. The consumer-side ~14 ms `/rtp` cost is a downstream symptom of that tick saturation; resolving the refill cost should restore parity with the Bukkit ~1 ms baseline.
- ADR-016 invariants (advisory probe, live-path is authoritative, REJECT is non-binding) are preserved end-to-end: any failure or gate-skip resolves to UNKNOWN and the existing live-load fallback runs.
- Custom dimensions registered via the Fabric-API datapack layout (`<world>/dimensions/<ns>/<path>/region/`) are handled by the same code path; modded namespaces round-trip through `PaletteIdentifierNormalizer` exactly as on Spigot.

### Negative / Risks
- `MinecraftServer#getWorldPath(LevelResource.ROOT)` and `ServerLevel#dimension().location()` are Mojang-mapped APIs. They have been stable across the 1.20–1.26 line covered by the existing `rtp-fabric-v*_R*` submodules, but a future renaming would surface as a `FINE` log + a clean fall-through to the live load path (no S-004 violation, no behavior regression) until updated.
- Adds a per-tick scheduler hop (probe future → ScanTask completion callback) on every prefill candidate, but this is dominated by the cost it replaces (~7–14 ms `cache.getChunk(...,FULL,true)` on the tick thread, vs ~tens of µs of dispatch + an off-tick `.mca` byte read). Same trade-off Spigot made in `BIOME_LOOKUP_PERF_PLAN` PR-9.

### Alternatives considered
- **Switch `FabricRTPWorld#getChunkAt` to a non-blocking generation API** instead of adding the prefilter. Rejected by the existing javadoc on `getChunkAt` (lines 111–120 prior to this change): the reflective `getChunkFutureMainThread` path produced its own `nullChunk/asyncLoadNull` regression and is mapping-fragile across MC patch releases. The prefilter targets the actual hot path (refill) instead of the fallback.
- **Hoist `AnvilColumnProbeAdapter` into `rtp-anvil`** and reuse it on both Bukkit and Fabric. Attractive but requires moving `PaletteNormalizer` (Bukkit-`Material`-aware) out of `rtp-anvil`'s coupling surface, which is a wider refactor than the user's "fix the lag" intent. Recorded as a follow-up; not blocking.

## Verification

- `./gradlew :rtp-fabric:rtp-fabric-common:compileJava` — green.
- Existing Spigot regression guards (`AnvilColumnProbeAdapterIsAirAtTest`, `AnvilPrefilterTest`, `AnvilFixtureParityTest`) cover the platform-neutral pieces this ADR consumes.
- Operator-side validation: with prefilter enabled, `ScanTask`'s `probeOutcomeRejected + probeOutcomeAccepted` dominates `probeOutcomeProbeNull` on a Fabric server, mirroring the Bukkit metric profile. A Fabric-side parity test belongs with the follow-up that hoists the shared adapter.

## Addendum 2026-05-06 — Full anvil-backed `FabricRTPChunk` (deferral lifted)

Triggered by user report that chunk loading is still slow on Fabric: the column-probe path of this ADR moved `ScanTask` *prefilter verdicts* off-tick, but every accepted candidate still hit `FabricRTPWorld.getChunkAt` → `cache.getChunk(..., FULL, true)` on the server tick thread because `getCachedChunk` had no anvil fallback. The deferred "full anvil-backed `RTPChunk` view" item from the section above is therefore lifted.

### What changed

1. `FabricRTPChunk` is now dual-mode (mirrors `BukkitRTPChunk`):
   - **Live** — `(ChunkAccess, ServerLevel, UUID)` constructor, unchanged behaviour.
   - **Anvil** — `(AnvilChunkView, cx, cz, UUID, Set<String> reconciledUnsafe)` constructor. Every block-data accessor (`isAir`, `getBiome`, `getSkyLight`, `getSurfaceHeight`, both `isSafe` overloads) dispatches to the decoded `AnvilChunkView` instead of the (null) live `ChunkAccess`. `keep` / `unload` are no-ops in anvil mode (no chunk ticket allocated → nothing to release; preserves S-002).
   - `isSelfContained()` returns `true` in anvil mode so the ADR-015 stale-chunk guard correctly skips its "is the live chunk still loaded?" check for self-sufficient anvil snapshots.

2. `FabricRTPWorld` wires `AnvilProbeSupport` (the same helper Spigot uses) into `getChunkAt` and `getCachedChunk`:
   - `getChunkAt` — when `shouldPrefilter` is open, dispatch to `AnvilProbeSupport.probeAndPublish(worldFolder, dimensionRegionSubpath, cx, cz, key, currentUnsafeBlocks(), FabricPaletteNormalizer::reconcile)`. Whenever the probe yields a decoded `AnvilChunkView` (regardless of advisory ACCEPT/REJECT verdict) the view is cached and the future completes with the chunk key — the candidate's evaluation then runs entirely against that view via `getCachedChunk`. Only the UNKNOWN case (no region file / unsupported `DataVersion` / decode error / no emitted sections) falls through to the original `ServerChunkCache#getChunk(..., FULL, true)` live path.
   - `getCachedChunk` — three-tier resolution: live wrapper cache → live `ChunkAccess` cache (lazy re-wrap) → `AnvilProbeSupport.takeCached(key)` returning a fresh anvil-backed `FabricRTPChunk` constructed with a pre-reconciled unsafe set.
   - Live precedence is preserved: any successful live load evicts the matching anvil entry, so a once-loaded chunk can never be served from a stale palette snapshot. `forgetChunkAt` and `forgetChunks` propagate the eviction.

3. New `FabricPaletteNormalizer` (`io.github.dailystruggle.rtp.fabric.anvil`) is the Fabric-side reconciler companion to Spigot's `PaletteNormalizer`. Pure-string (no `Material` enum); delegates per-id work to `rtp-api`'s `PaletteIdentifierNormalizer` and exposes a `reconcileAll` collection helper plus a `matches` cross-form lookup. Bukkit-style bare `Material` names like `"LAVA"` continue to match vanilla palette IDs like `"minecraft:lava"` after reconciliation, preserving config compatibility for operators migrating between platforms.

### Out of scope (still deferred)

- Hoisting `AnvilColumnProbeAdapter` into `rtp-anvil` for cross-platform reuse — same rationale as the original ADR.
- Sky-light decoding from anvil snapshots — anvil-backed `getSkyLight` returns the vanilla "fully lit" default (15); the live re-check at teleport commit (ADR-016 section 4) remains authoritative if any caller gates on sky light.
- State-predicate parity in the `CompiledUnsafeSet` overload — anvil-mode delegates to the plain-material bucket only, mirroring Spigot's Slice-2 behaviour.

### Verification

- `:rtp-fabric:rtp-fabric-common:compileJava` — green.
- New `FabricPaletteNormalizerTest` (8/8) — namespaced/bare/modded reconciliation, cross-form `matches`, immutable-set contract.
- New `ReqRtpS005FabricAnvilChunkTest` (6/6) — anvil-mode dispatch flags, view-routed accessors (`isAir` / `getSkyLight` / `getSurfaceHeight`), short-circuit safety with empty unsafe set, view-delegating safety with non-empty unsafe set, no-op `keep` / `unload`, null-view rejection. Bootstrap-free: any future regression that leaks live-path access into anvil mode trips the registry-not-bootstrapped exception immediately.
- Pre-existing Spigot anvil regression guards (`AnvilColumnProbeAdapterIsAirAtTest`, `AnvilPrefilterTest`, `AnvilFixtureParityTest`) remain unmodified — the change is additive at the Fabric layer.
