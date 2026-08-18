# ADR-016 — Anvil Read-Only Subsystem (Prefilter, Backed Chunk View, Shared Module)

**Status:** Accepted

## Context

RTP's selection pipeline samples blocks, biomes, heightmaps, and sky-light for candidate chunks. On vanilla Spigot, only `Consumer`-based async chunk APIs exist and they dispatch the load back onto the tick thread; Paper/Folia expose `World#getChunkAtAsync(int,int)` returning a `CompletableFuture` that resolves off-tick. Even with the async overload, Folia's region-thread affinity forces each live-chunk read onto the owning region scheduler, and every platform pays a full generation pass for candidates that will be rejected.

For a chunk already persisted on disk, the block / biome / heightmap / sky-light data RTP needs is in the Anvil region file (`<world>/region/r.<x>.<z>.mca`). Reading it is pure file I/O — no tick or region thread is required. The on-disk palette is also *strictly more accurate* than the live view: closed enums (`org.bukkit.Material`, `org.bukkit.block.Biome`) collapse modded and generator-native identifiers (`create:crushing_wheel`, `iris:volcanic_ash_plains`) to their nearest vanilla cousin, while the `.mca` palette preserves full namespaced identifiers. Persisted palettes also remain consistent with what the client renders after a Minecraft version upgrade shifts Mojang's seed-based biome assignment; live `getBiome(...)` does not.

In the nether, `WORLD_SURFACE` resolves at the lava column for essentially every chunk. Any design that short-circuits `REJECT` verdicts (e.g. by returning a null chunk) produces a false-negative storm even though the vert adjustor would find a safe Y below the surface. The verdict must be advisory, not a gate.

Minimum supported Minecraft version is 1.20.1 (REQ-RTP-SYS-002); format adapters target 1.20.1 and later.

## Decision

RTP ships a **read-only Anvil subsystem** that serves selection-phase reads from the on-disk region file as an advisory data source. The live `chunk.isSafe(...)` re-check at teleport-commit time remains the authoritative arbiter. The subsystem lives in a platform-neutral Gradle module, `rtp-anvil`, consumed by every Bukkit-family adapter (Spigot, Paper, Folia).

### 1. Applicability gate

The subsystem runs for a candidate chunk `(cx, cz)` iff all of:

- `RTPWorld.isChunkLoaded(cx, cz) == false` — loaded chunks may hold unflushed edits that disagree with the region file; skipping them is the only cheap, correct way to avoid read/write desync without coordinating with the chunk I/O thread.
- The region file for `(cx, cz)` exists on disk. An absent file means the chunk is ungenerated; the candidate falls through to the real-load path, which drives generation through the platform's native scheduler.

Custom-generator worlds (Iris, Terra, datapacks) are in scope: populated-on-disk ⇒ `.mca` is authoritative; not populated ⇒ `Verdict.UNKNOWN` ⇒ live-load fallback. The live re-check (section 5) bounds any residual divergence to "extra retries", never "unsafe teleport".

#### 1.1 Call-site ordering on Paper chunk-system-v2 and Folia

Paper's chunk-system-v2 (and Folia's region scheduler) resolve `World#getChunkAtAsync(cx, cz)` by handing back a live — though non-ticking — `Chunk` reference; Paper's own post-load unloading behaviour then becomes a liveness concern for downstream stages (see ADR-015). As a consequence, any invocation of `RTPWorld#getChunkAt(cx, cz)` that follows an upstream `getChunkAtAsync` resolution observes `isChunkLoaded(cx, cz) == true` at the section 1 gate and skips the Anvil probe with `reason=chunk-already-loaded`. The prefilter therefore delivers its intended benefit — *reject the candidate before paying the live-load cost* — only when the probe runs **upstream** of `getChunkAtAsync`, not inside the adapter's `getChunkAt` path.

`LocationGenerator` invokes `AnvilPrefilter.probeAndPublish(world, cx, cz, ForkJoinPool.commonPool(), PaletteNormalizer::reconcile)` as the first chunk-touching stage of each attempt, ahead of any `world.getChunkAtAsync(cx, cz)` call. The `AnvilProbeSupport` cache publishes the view before the adapter's `getChunkAt` is reached; when the publisher returns a non-null view the attempt short-circuits through the source-union `RTPChunk` (section 6) without a live load; when it returns `UNKNOWN` the attempt proceeds to `getChunkAtAsync`. The adapter-internal section 4 gate remains as a defensive no-op for direct adapter callers that bypass `LocationGenerator` (e.g. `RegionCacheTask` observational mode, future API consumers), and is expected to record `reason=chunk-already-loaded` at `FINE` on the Paper/Folia common path where `LocationGenerator` has already resolved the chunk upstream.

### 2. Format detection with fallback

The reader probes chunk NBT for a `DataVersion` tag and dispatches to a registered format adapter. `DataVersionSupport.isSupported` accepts any value in `[MIN_SUPPORTED_DATA_VERSION (3454), MAX_SUPPORTED_DATA_VERSION (5000)]`, covering 1.20.x, 1.21.x, and 26.x with headroom for forward patch releases. Unsupported DataVersions, I/O errors, corrupted sections, or thread interruption produce `Verdict.UNKNOWN` with a null view; the candidate falls through, never rejected, never a false positive. Format adapters are additive.

### 3. Verdict enum (advisory only)

`AnvilPrefilter.probeSyncDetailed(...)` returns one of:

- `ACCEPT` — read succeeded, heightmap-surface column is safe under the normalized unsafe-block set.
- `REJECT` — read succeeded, heightmap-surface column contains a configured-unsafe block.
- `UNKNOWN` — no decode was possible.

The verdict is advisory telemetry. Adapters route on `view != null`, not on verdict: `ACCEPT` and `REJECT` both carry the decoded `AnvilChunkView`; only `UNKNOWN` carries a null view. `AnvilPrefilterMetrics` counts `ACCEPT` / `REJECT` / `UNKNOWN` for `/rtp test anvil-prefilter`; `REJECT` reads as "surface-unsafe column handed to the vert adjustor", not "candidate dropped". There is no null-key short-circuit on any platform.

### 4. Data-source routing in platform adapters

Every Bukkit-family `RTPWorld#getChunkAt(cx, cz)` implementation:

1. If the applicability gate (section 1) fails, delegate to the adapter's native async/sync live-load path.
2. Otherwise call `AnvilPrefilter.probeSyncDetailed(...)` (or the async `probeDetailed(...)` on Folia) on `ForkJoinPool.commonPool()`, with the platform-supplied `PaletteNormalizer::reconcile`.
3. If the probe returns a non-null view (`ACCEPT` or `REJECT`), publish it into the adapter's `anvilCache` (bounded LRU) and return a completed future with the live-shaped chunk key. The immediately-following `getCachedChunk(key)` call in `LocationGenerator` receives a source-union `RTPChunk`.
4. If the probe returns a null view (`UNKNOWN`), fall through to the adapter's native live-load path.

`BukkitRTPWorld#getChunkAt` is the canonical implementation. `PaperRTPWorld` and `FoliaRTPWorld` inherit this orchestration (Paper extends `BukkitRTPWorld`; Folia reimplements the same structure atop its native async overload).

On Paper chunk-system-v2 and Folia, this adapter-internal routing is a defensive fallback; the effective prefilter entry point for the selection pipeline is the call-site-level probe in `LocationGenerator` specified in section 1.1. The adapter path still runs for direct adapter callers (observational cache-fill, addon API consumers) and for Spigot vanilla where no upstream `getChunkAtAsync` resolution precedes the call.

### 5. Authoritative re-check preserved

At teleport-commit time, `LocationGenerator` force-loads the selected chunk via `ChunkSet` and the live `chunk.isSafe(...)` re-check runs against the live world. REQ-RTP-S-001 is satisfied by the live re-check; the Anvil subsystem only ever strengthens the rejection side. An Anvil-backed chunk is **never** handed to the teleport-commit path.

### 6. Source-union `RTPChunk`

Each Bukkit-family adapter ships an `RTPChunk` implementation (`BukkitRTPChunk`, `FoliaRTPChunk`) that is a source-union over a live `org.bukkit.Chunk` and/or an `AnvilChunkView`:

- Anvil-mode answers `isAir / isSafe / getSkyLight / getSurfaceHeight` from the view across the full decoded Y range (including palette sections below the heightmap surface), so the vert adjustor can locate a safe air pocket below a lava surface without loading the chunk or hopping onto a region thread.
- Queries outside the decoded Y window, or arriving after the live chunk has loaded, fall through to the live `Chunk`. Cache promotion on live load evicts the Anvil entry — disk is never a source of truth for a loaded chunk.
- `RTPChunk#isSelfContained()` returns `true` for Anvil-mode instances so the stale-chunk guard (ADR-015) does not mis-attribute Anvil-backed candidates to the `nullChunk` bucket when `world.isChunkLoaded(cx, cz) == false`.

### 7. Palette identifier reconciliation

Reconciliation is symmetric:

- Palette entries shaped `minecraft:lava` are resolved via the platform registry (Bukkit `Material.matchMaterial(...)`, `Registry.BIOME`) to the canonical enum `.name()` (`LAVA`). Unresolved entries (modded identifiers on Mohist, Arclight) fall back to namespace-strip + `Locale.ROOT` upper-case.
- User-supplied `SafetyKeys.unsafeBlocks` and biome lists go through the identical normalization once at config load, so `LAVA`, `minecraft:lava`, and `MINECRAFT:LAVA` compare equal.

`rtp-anvil` ships a platform-neutral `DEFAULT_RECONCILER` (namespace-strip + upper-case). Platform adapters needing a registry-aware reconciler (`PaletteNormalizer` in `rtp-bukkit-common`) supply it as a `UnaryOperator<String>` on the reconciler-aware overloads of `probeDetailed` / `probeSyncDetailed`.

### 8. Shared module layout

The decode stack lives in `rtp-anvil` (peer of `commands-api` and `effects-api`), package `io.github.dailystruggle.rtp.anvil`.

**Module invariants** — `rtp-anvil` shall not import:

- any RTP module (`rtp-api`, `rtp-core`, `commands-api`, `effects-api`, any platform adapter);
- any platform package (`org.bukkit.*`, `io.papermc.*`, `net.minecraft.*`, `net.fabricmc.*`).

Public types exchange only platform-neutral values: `byte[]`, `java.nio.file.Path`, `java.util.Optional`, primitives, and the module's own decode types. Enforced at the bytecode level by `AnvilPackageBoundaryArchTest`.

Contents: `AnvilReader`, `AnvilChunkView`, `AnvilPrefilter`, `AnvilPrefilterMetrics`, `BiomeSourceMetrics`, `PackedPaletteDecoder`, `PaletteSection`, `BiomePaletteSection`, `DataVersionSupport`, `Verdict`, `Nbt`, `UnsupportedAnvilFormatException`, `AnvilRegionScanner` (diagnostic), plus test fixtures (`r.X.Z.mca`) for 1.20.1 / 1.21.1 / 26.1. `PaletteNormalizer` and `dimensionRegionSubpath(World)` stay in `rtp-bukkit-common` (Bukkit-coupled).

### 9. Thread placement

All region-file I/O runs on `ForkJoinPool.commonPool()` (or an explicit RTP async executor) and never on the tick or region thread. Each adapter holds a bounded LRU `anvilCache`; REQ-RTP-S-002 is unaffected — the subsystem allocates no chunk tickets.

### 10. REQ-RTP-S-004 attribution

The `FailTypes.nullChunk` bucket (sub-keys `reason=asyncLoadNull` / `reason=neighborNull`) in `LocationGenerator` covers every `chunk == null` exit in the pregen summary. Since section 4 has no null-key short-circuit, the bucket attributes only genuine async-load failures; `prefilterReject` is not a reachable sub-key. Regression guard: `ReqRtpS004NullChunkAttributionTest`.

### 11. Chunk-data precedence over live world getters

Every Bukkit-family adapter resolves chunk-scoped data — blocks, biomes, heightmaps, light — in strict priority order:

1. **A currently loaded `org.bukkit.Chunk`** cached in the adapter's live `chunkCache` (`WeakReference<Chunk>`).
2. **An `AnvilChunkView`** decoded from `.mca` and held in the per-world `AnvilProbeSupport` cache.
3. **A live `org.bukkit.World` getter** — permitted only under the vanilla-generator exemption below.

The rule applies uniformly to `getBiome`, block reads, and any future chunk-scoped getter on `RTPWorld` / `RTPChunk`. A decoded `.mca` palette is a strictly more faithful representation of what the player will see than any live-getter answer synthesised from the current seed — the on-disk bytes are what the client receives when it streams the chunk.

#### 11.1 Vanilla-generator exemption

Falling back to a live getter is permitted only when **all** of:

1. The world's `ChunkGenerator` is the vanilla Minecraft generator.
2. The world's biome source yields no custom / namespaced biomes outside `minecraft:*`.
3. The Anvil source has returned `UNKNOWN` for the target chunk.
4. The target chunk has **not** yet been generated and persisted to disk (`RTPWorld#isChunkGenerated(cx,cz)` returns `false`).

`RTPWorld#isVanilla()` (default `false`; overridden on Bukkit/Folia as `world.getGenerator() == null && world.getBiomeProvider() == null` with `Throwable → false` safety) and `RTPWorld#isChunkGenerated(cx,cz)` (default `true`; overridden on Bukkit/Folia to delegate to the native non-blocking `World#isChunkGenerated` with `Throwable → true` safety) supply the runtime signals. When any of 1, 2, or 4 is unknown at runtime, the adapter treats the world / chunk as **non-exempt**. Detection of mods that replace generation via NMS mixins without touching the public Bukkit hooks is out of scope — such worlds are misidentified as vanilla; the post-load re-check still corrects the answer, so the only cost is a wasted chunk load for candidates where the seed-biome passes the filter while the palette biome does not.

Fabric is out of scope for this ADR; an equivalent contract for `rtp-fabric` shall be specified in a separate ADR.

#### 11.2 Enforcement

New code introducing a live `world.getBiome(...)` or equivalent from `rtp-bukkit-common`, `rtp-paper-*`, or `rtp-folia-*` shall be rejected at review unless gated on the section 11.1 exemption **and** on an `AnvilProbeSupport` `UNKNOWN` outcome. Any ADR that narrows section 11 shall explicitly supersede this section. Regression guard: `ReqRtpAnvilFirstTest` under `rtp-paper-v1_20_R1` asserts `PaperRTPWorld` does not re-declare `getChunkAt` / `getBiome`.

### 12. Biome-filter evaluation model

`LocationGenerator`'s biome filter evaluates the user/config-supplied set **directly** against each candidate's biome with a `biomeWhitelist` polarity flag — enumeration-free and correct on cold start on every platform. The post-load biome read is the sole biome-validation site; the pre-chunk-load biome pre-check is retained as dead code behind `PRE_CHUNK_BIOME_PRECHECK_ENABLED = false` so it can be re-enabled for workloads (e.g. bounded biome-targeted search on very large regions) that need the short-circuit. `AnvilRegionScanner` remains available as an admin-command diagnostic.

### 13. Observational cache-fill mode

`RegionCacheTask` carries an `observationalOnly` flag. When set:

- Runs only when `queueManager.unkeptLocations.size() >= cacheCap` (strict inversion of default-mode gate, so default-mode cache fill has priority for queue headroom).
- Selects a candidate via the existing `MemoryShape.select()` + `LocationGenerator.getLocation(...)` pipeline.
- On a safe candidate, **discards** the result — not pushed to `unkeptLocations`, not handed to any teleport pipeline.
- Emits no new sink calls. Existing side effects (`MemoryShape#addBadLocation(long)` for rejected cells, `biomePrefixSumsCache` updates for every evaluated candidate) are inherited from `LocationGenerator` and `MemoryShape.select()` under default mode.

Gated by `PerformanceKeys.visitorEnabled`. Observational mode inherits the section 11 precedence chain automatically, so biome observations reflect on-disk palettes whenever disk has an answer on every Bukkit-family platform.

### 14. Telemetry

`BiomeSourceMetrics` exposes two process-global `AtomicLong` totals (`anvilHits`, `liveHits`) plus a reason-keyed `ConcurrentHashMap<String, AtomicLong>` with canonical reasons `anvil-hit`, `no-view-cached`, `view-missing-biome`, `anvil-throw`. `BukkitRTPWorld#getBiome` and `FoliaRTPWorld#getBiome` call `record(reason)` exactly once per read and emit a rate-limited `[RTP] Anvil biome fallthrough reason=<reason> world=<w> chunk=(cx,cz)` line per `BIOME_LOG_BUDGET_PER_REASON = 200` (INFO, then FINE). `AnvilPrefilter.DIAG_LOG_BUDGET_PER_REASON` and the adapter `GATE_SKIP_BUDGET_PER_REASON` constants share the same budget. Surfaced at runtime by `rtp test biome-source` and `rtp test full`.

The adapter `logGateSkip` suppresses `reason=chunk-already-loaded` entirely — the `RTP.log(...)` call is skipped while the counter in `GATE_SKIP_COUNTERS` still increments, preserving the metric surface for `rtp test/anvil-prefilter` and future telemetry consumers. On Paper/Folia this reason is the steady-state outcome for every candidate that reaches the adapter path after `LocationGenerator`'s call-site probe (section 1.1), so surfacing it at any operator-visible level carries no diagnostic signal and drowns the log. All other gate-skip reasons (`dimension-unsupported`, `world-save-disabled`, `config-disabled(...)`) retain the rate-limited INFO→FINE budget and remain operator-actionable.

## Consequences

**Positive:**

- Selection-phase `isAir / isSafe / getSkyLight / getSurfaceHeight` and the vert adjustor run off-tick (Spigot/Paper) or off-region (Folia) for candidates whose region file exists and decodes. The nether false-negative storm is closed because surface-unsafe chunks carry a usable view for sub-surface scanning.
- Ungenerated chunks drive generation through the platform's native scheduler; the subsystem is additive.
- Loaded chunks are never read from disk — desync class eliminated by construction (section 1 gate).
- Format detection with fall-through means new Minecraft versions never regress teleport correctness; the subsystem abstains until an adapter is written.
- `rtp-anvil` is reusable by any Bukkit-family adapter without code duplication.
- REQ-RTP-S-004 attribution is complete: every `chunk == null` path increments a descriptive `failMap` bucket.
- Upgrade-drift correctness (player-visible biome after a Paper/Folia MC upgrade) is preserved by construction via section 11.
- Cold-start biome filtering is correct on every platform without a one-shot startup scan (section 12).

**Negative / trade-offs:**

- Anvil bytes are decoded for candidates that will ultimately be rejected at the live re-check, which is worse than a pure surface-unsafe short-circuit for purely surface-unsafe rejections. Net CPU cost is off-tick and off-region and is paid back by eliminating the sync `getChunkAt(...)` or region-thread hop.
- Hand-rolled Anvil decoding is maintenance-bearing: palette encoding, heightmap long-packing width, and biome storage layout have changed across Minecraft versions. A stale adapter wrongly claiming to support a newer `DataVersion` could produce `ACCEPT` on misread data; mitigated by the post-load authoritative re-check.
- Modded identifiers go through namespace-strip + upper-case fallback; the reconciler does not assume the vanilla namespace.
- Two adapter `*RTPChunk` source-unions must track any future refinement of the section 6 invariants. Mitigated by keeping implementations structurally identical and covering both under the same ArchUnit boundary test.
- Observational mode contributes to `pendingBadLocations` without an originating teleport request, and claim-plugin predicates run on observational candidates. Escape hatch: `visitor.enabled: false`.

**Neutral:**

- `AnvilPrefilterMetrics` counters retain their prior cardinality; only their operational reading changes (section 3).
- The stale-chunk guard (ADR-015) and async queue pre-generation (ADR-006) are unaffected — the subsystem allocates no chunk tickets.

## References

- REQ-RTP-S-001 (no unsafe-block teleport destinations) — `docs/dev/REQUIREMENTS.md section 3`.
- REQ-RTP-S-002 (no permanently force-loaded chunks) — `docs/dev/REQUIREMENTS.md section 3`.
- REQ-RTP-S-004 (no silently discarded teleport failures) — `docs/dev/REQUIREMENTS.md section 3`.
- REQ-RTP-S-005 (no chunk loading on the main thread) — `docs/dev/REQUIREMENTS.md section 3`.
- REQ-RTP-SYS-002 (Spigot support) — `docs/dev/REQUIREMENTS.md section 0`.
- ADR-004 "Count-Bound Task Pipe on Folia".
- ADR-006 "Async Queue Pre-Generation".
- ADR-015 "Stale-Chunk Guard for Count-Bound Pipes".
- Regression guards: `AnvilPrefilterTest`, `AnvilFixtureParityTest`, `AnvilChunkViewTest`, `PaletteNormalizerTest`, `AnvilPackageBoundaryArchTest`, `ReqRtpS004NullChunkAttributionTest`, `ReqRtpAnvilFirstTest`, `TestBiomeSourceCmdTest`, `TestFullCmdTest`.
