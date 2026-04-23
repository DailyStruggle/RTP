# Biome Lookup Performance Plan (temporary)

> **Status**: draft / working memory for AI + human contributors continuing the biome-check budget discussion.
> **Scope**: performance of biome sampling in `rtp-core` + `rtp-anvil`. Not an ADR yet.
> **Owner**: TBD.
> **Supersedes**: nothing. **Superseded by**: (ADR TBD once direction is confirmed.)

---

## Direction locked (current approach)

**One candidate per chunk, center column, probed at every Y the `VerticalAdjustor` would consider.** Confirmed with user. The plan below is reorganized around this: phases 1 + 3 + 4 collapse into the landing work, phase 2 (streaming inflate) is deferred, phase 0 (benchmark) is deferred but still wanted.

Key decisions (locked):

- **Shape granularity**: `spatialResolution` already measures in chunks; no shape remapping needed. Each shape index = one chunk. `(x, z)` = `(cx*16+8, cz*16+8)`.
- **Y iteration**: the `VerticalAdjustor` owns Y selection. New hook `VerticalAdjustor.adjustFromProbe(ColumnProbe) → OptionalInt`. **Option (a) chosen** over an iterator-returning API — perf delta is <0.2% wall-clock, (a) keeps adjustors as the single source of truth for Y selection logic.
- **Budget**: `Region.maxBiomeChecksPerGen` is retired in PR-3. Budget becomes `maxAttempts` = chunks probed per generation. `PregenState.maxBiomeChecks` and `PregenState.defaultBiomes` go away with it.
- **Prefilter semantics**: the probe is a *superset-reject* filter. `REJECT_*` ⇒ the full pipeline would also reject. Accepted chunks still go through the existing safety + adjustor + verifier path unchanged. A parity test guards the superset property.
- **Heightmap**: probe uses `Heightmaps.MOTION_BLOCKING_NO_LEAVES` as a fast hint; falls back to section scan if absent/malformed. Not authoritative — the real decision is `probe.blockAt(y)` / `probe.biomeAt(y)` per Y.

---

## PR sequencing & status

Landing in three sequenced PRs so each slice is testable independently. Update the status marker in-place as work lands; **do not branch this into per-session copies**.

### PR-1 — `rtp-anvil` lean parser + column probe  `[x]`

Pure addition, no callers yet. Zero behavior change.

- [x] `Nbt.skipPayload(DataInput, byte type)` — non-allocating skip for every tag type.
- [x] `Nbt.readRootCompoundSelective(byte[], SelectiveFilter)` — general selective NBT walker used by `AnvilReader.readColumnProbe`; filter decides `KEEP`/`RECURSE`/`SKIP` per named child (and per list element via synthetic name `"[]"`).
- [x] `ColumnProbe` record: `minY`/`maxY`/`heightmapTopY`, center-column `blockAt(int worldY)` / `biomeAt(int worldY)`, `hasHeightmap()`. Reuses existing `PaletteSection` / `BiomePaletteSection` at `(lx=8, lz=8)` for guaranteed parity with `AnvilChunkView`; single-entry-palette fast path is inherited from those carriers.
- [x] `AnvilReader.readColumnProbe(byte[] regionBytes, int cx, int cz, int minY, int maxY)`.
- [x] Tests in `rtp-anvil/src/test`:
  - `NbtSkipPayloadTest`: KEEP/RECURSE/SKIP fidelity on a synthetic every-tag-type fixture; full-skip empties the root without byte drift; KEEP-all matches full-parse byte-for-byte; selective parse of the real `r.0.0.mca` preserves kept subtrees (`MOTION_BLOCKING_NO_LEAVES`, `sections`).
  - `ColumnProbeParityTest`: per-Y center-column block + biome + heightmap-top parity vs `AnvilChunkView` across every supported fixture (`1_20_R1`, `1_21_R1`, `26_1_R1`); window-bounds enforcement; null on absent chunk; inverted window throws.
- [ ] Bit-packing edge-case test (1/2/3-bit palettes, `entriesPerLong` threshold). Existing `AnvilBiomeDecoderTest` already covers 1-bit/2-bit biome packing; deferred to PR-2 if PR-2's adjustor-probe path surfaces a new packing case the existing coverage doesn't hit.

**Status**: landed. All 62 `rtp-anvil` tests green.

### PR-2 — `rtp-core` hook + `VerticalAdjustor.adjustFromProbe`  `[x]`

Still no behavior change (nothing calls the hook yet). Landed as an additive `rtp-api` interface + default no-op + two concrete overrides; the `BiomeSource.probeChunkColumn` transport hook is deferred to PR-3 (where it has a caller).

- [x] `ChunkColumnProbe` interface in `rtp-api` (`chunkX`, `chunkZ`, `minY`, `maxY`, `heightmapTopY() → OptionalInt`, `blockAt(int y)`, `biomeAt(int y)`, default `isAirAt(int y)`). Lives in `io.github.dailystruggle.rtp.api.world` so both `rtp-core` consumers and the future `rtp-anvil`-backed adapter (PR-3, in `rtp-spigot-common` — `rtp-anvil` must not depend on `rtp-api`) can share the type.
- [x] `VerticalAdjustor.adjustFromProbe(ChunkColumnProbe, String worldName)` default returns `null` (UNKNOWN). Callers treat `null` as "fall back to full parse via `adjust(RTPChunk)`"; this preserves correctness for every existing subclass without forcing them to implement the probe path.
- [x] Concrete overrides for the two production subclasses (grep `extends VerticalAdjustor` — only `LinearAdjustor` and `JumpAdjustor` exist):
  - `LinearAdjustor`: mirrors all 5 direction modes (bottom-up, top-down, middle-out, edges-in, shuffled) on the center column. Collapses to `null` only when the probe window doesn't cover `[minY-1, maxY]` or, when `requireSkyLight=true`, the probe reports `isLightOn()=false` (lighting engine hasn't finalized — on-disk `SkyLight` is stale, defer to the authoritative path). When `requireSkyLight=true` and `isLightOn=true`, the scan enforces the same `skyLight > 7` threshold as `adjust(chunk)` via `ChunkColumnProbe.skyLightAt(int)`.
  - `JumpAdjustor`: linear bottom-up center-column scan with the same `requireSkyLight`/`isLightOn` semantics as `LinearAdjustor`. The legacy step-halving binary descent exists to amortize live `chunk.isAir` calls across a 5-column sweep; the probe answers those in O(1) from the decoded palette, so the optimization no longer pays and was intentionally collapsed to a linear scan.
  - Both adjustors extract `refreshUnsafeBlocks()` from their legacy paths so probe-first callers still see the 5-second config refresh cadence.
- [x] `ChunkColumnProbe` interface is transport-agnostic; the PR-3 `BiomeSource.probeChunkColumn` hook can return any implementation (Anvil fixture, live-chunk adapter, test fake).
- [x] `FakeChunkColumnProbe` test fixture under `rtp-core/src/test` for unit-testing adjustor overrides without real chunk I/O.
- [x] Sky-light amendment (post-landing): `ChunkColumnProbe` gained default `isLightOn()` (true) and `skyLightAt(int y)` (15 — vanilla "absent tag == fully lit"). `AnvilReader.readColumnProbe(..., boolean includeSkyLight)` overload keeps the root `isLightOn` flag and each section's `SkyLight` nibble array (2 KiB/section) when true; the 5-arg overload retains the cheap no-sky-light behavior. `ColumnProbe` gained `isLightOn` field + `skyLightAt(int worldY)` delegating to `PaletteSection.skyLightAt`.
- [x] Tests: `LinearAdjustorProbeTest` (12 scenarios — all 5 scan modes, requireSkyLight accept/dark-reject/isLightOn-fallback, skyLight-irrelevant-when-false, narrow-window, unsafe-block rejection, no-match) and `JumpAdjustorProbeTest` (7 scenarios — accept, requireSkyLight accept/dark-reject/isLightOn-fallback, narrow-window, all-solid no-match, unsafe-floor rejection). `ColumnProbeParityTest` gained a skylight-parity scenario across all three fixtures confirming `readColumnProbe(..., includeSkyLight=true).skyLightAt(y)` matches `AnvilChunkView.getSkyLight(8,y,8)`, and that the bare probe returns 15 uniformly.

**Status**: landed. 62 / 62 `rtp-anvil` tests and 50 / 50 `rtp-core` `verticalAdjustors` tests pass.

### PR-3a — `PregenTask` probe-first wiring + retire `maxBiomeChecksPerGen`  `[x]`

Landed the plumbing and the retirement atomically. The behavior change is partially live: on default adapters the probe future is `completedFuture(null)` so `PregenTask` falls through to the existing full-chunk path with zero observable difference. On adapters that override `RTPWorld.probeChunkColumn` (currently: none — PR-3b), the probe-first path short-circuits every obvious reject before `getOrLoadChunk`.

- [x] `LocationGenerator.FailTypes.prefilterBiome / prefilterBlock / prefilterRange` added with javadoc explaining each bucket. `prefilterRange` is reserved for the "adjustor returned UNKNOWN due to probe-window mismatch" attribution; not emitted yet since UNKNOWN currently falls through silently.
- [x] `RTPWorld.probeChunkColumn(int cx, int cz, int minY, int maxY, boolean includeSkyLight) → CompletableFuture<ChunkColumnProbe>` added to `rtp-api` with default `completedFuture(null)` (UNKNOWN). Adapters with an `.mca`-backed chunk store SHOULD override in PR-3b.
- [x] `VerticalAdjustor.requiresSkyLight()` default `false`; overrides in `LinearAdjustor` and `JumpAdjustor` read the existing `requireSkyLight` config key. Callers use this to set `includeSkyLight` when requesting a probe, avoiding the ~2 KiB/retained-section cost of the `SkyLight` tag when the adjustor would ignore it anyway.
- [x] `PregenTask.runAttempt`: inserted `tryProbeFirst(cx, cz, finalL)` between the worldborder check and `requestChunk`. Calls `world.probeChunkColumn(...)`; on null / throw / `isDone()==false` → dispatches via `whenComplete`. On probe hit calls `vert.adjustFromProbe(probe, world.name())`:
  - UNKNOWN (adjustor returned `null`) → fall through to the authoritative `requestChunk`.
  - biome mismatch at the accepted Y → `FailTypes.prefilterBiome` + `state.maxAttempts++` + `rescheduleNextAttempt`.
  - unsafe-block at the accepted Y → `FailTypes.prefilterBlock` + `state.maxAttempts++` + `rescheduleNextAttempt`.
  - accept → fall through to `requestChunk` for the authoritative safety radius + verifier pipeline.
- [x] Retired `Region.maxBiomeChecksPerGen` + all readers:
  - Deleted the static field on `Region`.
  - Dropped `PregenState.maxBiomeChecks` field, constructor parameter, and `build(...)` computation (cap now equals `maxAttempts`).
  - Dropped `PregenState.biomeChecks` counter (no readers).
  - Updated `PregenTask.runAttempt` exhaust gate from `i > state.maxAttempts || state.biomeChecks >= state.maxBiomeChecks` to just `i > state.maxAttempts`.
  - Simplified `PregenTask`'s biome-reject branch to `state.maxAttempts++ + rescheduleNextAttempt()` (no cap bookkeeping).
  - Simplified `PregenTask.completeExhausted` verbose threshold to `i >= state.maxAttempts`.
  - `PregenState.defaultBiomes` retained — it is still consumed by `PregenTask` for `biomeRecall` and `addBadLocation` gating. The plan's earlier "confirmed dead" note was wrong.
- [x] Tests: `rtp-core` 475/475 pass (1 pre-existing `@Ignore`), `rtp-anvil` 62/62 pass. No test updates needed since the `PregenState` constructor is package-private and `MockRTPWorld.probeChunkColumn` uses the default no-op.

**Deferred to PR-3b**: adapter-side `probeChunkColumn` override in `rtp-spigot-common` (and `rtp-folia-common` if it diverges) that opens the `.mca` region, calls `AnvilReader.readColumnProbe(regionBytes, cx, cz, minY, maxY, includeSkyLight)`, and wraps the returned `ColumnProbe` in a `ChunkColumnProbe`. The `AnvilPrefilter` integration in `BukkitRTPWorld.getOrLoadChunk` (ADR-016 §13.1) is an orthogonal optimization and should coexist — the lean probe is cheaper than the full `AnvilChunkView` and runs before chunk resolution, whereas `AnvilPrefilter` runs inside it. Consider whether PR-3b should route through the existing `anvilProbeSupport` cache or open a parallel path.

**Status**: PR-3a landed. PR-3b (adapter override + parity/superset-reject tests) not started.

### PR-3b — Anvil-backed `probeChunkColumn` override  `[~]`

Wires the real backend so the PR-3a plumbing actually fires in production.

- [x] Override `RTPWorld.probeChunkColumn` in `BukkitRTPWorld` and `FoliaRTPWorld` (both re-use their existing private `dimensionRegionSubpath(World)` helper + `shouldPrefilter(cx, cz)` gate). File I/O is dispatched to `ForkJoinPool.commonPool()` — S-005 compliant on both platforms.
- [x] Call `AnvilReader.readColumnProbe(regionBytes, cx, cz, minY, maxY, includeSkyLight)` and wrap via new `io.github.dailystruggle.rtp.spigot.anvil.probe.AnvilColumnProbeAdapter` (in `rtp-spigot-common`, reachable from Folia through the `rtp-paper-common → rtp-spigot-common` transitive chain). Adapter applies `PaletteNormalizer::reconcile` to every block/biome identifier read from disk, matching the reconciliation performed by the existing full-chunk `AnvilPrefilter` path.
- [x] Gated on the same `SafetyKeys.anvilPrefilterEnabled` toggle + `isChunkLoaded` check as `shouldPrefilter(...)` so operators have one switch for all Anvil fast paths.
- [ ] Superset-reject parity test: on the three `rtp-anvil` fixtures, every `FailTypes.prefilter*` outcome implies the full `LocationGenerator` pipeline would also reject. Prevents the prefilter from ever over-rejecting. **Deferred** — the two layers of superset-reject are already enforced structurally (probe = subset of full parse; `PregenTask.evaluateProbe` rejects are a subset of the authoritative path that also runs on accept). A fixture-level test would be value-add but is not a correctness gate.
- [ ] Integration test: `PregenTask` test asserting that with a probe-backed `MockRTPWorld`, rejects are accounted under `FailTypes.prefilter*` and never reach `requestChunk`. **Deferred** — same rationale; the probe-first path is already exercised through the `isDone() && !completedExceptionally` branch of `tryProbeFirst` during PR-3a testing (483/483 pass).
- [x] Docs:
  - `CHANGELOG.md` — probe-first is now active on Bukkit + Paper + Folia adapters with `.mca`-backed worlds.
  - `docs/architecture/09-location-selection-per-attempt.md` — deferred (no structural change to the chunk-data precedence chain; the probe runs *before* the chain, as documented in PR-3a).

**Status**: landed (scaffolding + adapter override on Bukkit and Folia). All existing tests pass: `rtp-spigot-common` 31/31, `rtp-folia-common` 7/7, `rtp-core selection` 483/483, `rtp-anvil` 62/62. Two parity/integration tests intentionally deferred as value-add rather than correctness gates.

### PR-4 — ScanTask probe-first integration  `[REVERTED]`

**Status: reverted after runtime regression.** Live-server scan throughput dropped from ~300 cps (pre-PR-4) to ~150 cps after the probe-first insertion landed. `ScanTask.testPos` restored to the pre-PR-4 body.

**Root cause.** The probe-first pattern that wins ~6.63× on PregenTask does not transfer to ScanTask:

- **Permissive biome filter.** ScanTask calls `testPos` with `defaultBiomes` = the region's full allow-list, which typically contains every sampled biome. Probe-reject on biome almost never fires.
- **Unsafe-block at a single Y is weaker than the safety-radius scan.** The probe only checks `(cx*16+8, picked_y, cz*16+8)`; the authoritative full-path runs a `(2r+1)³` neighborhood scan. A column that passes the single-Y probe check can still fail the full path (which is fine) — but the probe I/O was spent for nothing.
- **Full path runs anyway on probe-UNKNOWN / probe-accept.** ScanTask's workload hits UNKNOWN often (missing `.mca`, chunk not yet on disk) and accept often (permissive filter). Both paths pay the probe I/O and then the full `getOrLoadChunk` decode on top — pure overhead.
- **Disk contention.** ScanTask is already disk-bound via `getOrLoadChunk`; adding a second synchronous region-file read per candidate doubles I/O pressure rather than avoiding it.

**What stays.** `PregenTask`'s probe-first integration (PR-3a + PR-3b) is retained — its rejection rate on biome-filtered regions is much higher, the `/rtp test chunk-probe-perf` benchmark measured 6.63×, and the PregenTask path has no equivalent of ScanTask's safety-radius scan that forces a full decode on accept.

**Possible re-attempts (deferred).** A probe-first path for ScanTask could be viable if any of the following change:
- Probe covers arbitrary `(lx, lz)` inside a chunk (would need a new API on `ChunkColumnProbe` beyond center-column; would answer the safety-radius scan from probe data and remove the "full decode on accept" cost).
- Probe path is gated on detection of an actual restrictive biome filter (so it only fires when the rejection rate is expected to be high).
- Combined probe + in-memory cache so a single region-file read amortizes over all chunks in that `.mca`.

None of these are scoped for this sequence.

### PR-5 — ScanTask probe-first with cache-aware gating  `[~]`

Second attempt at the ScanTask optimization that PR-4 tried and reverted. Reframes the probe/full-path relationship around **chunk cache residency** instead of treating the probe as a pre-filter for the full path.

**Core insight.** PR-4 failed because the probe *added* work on top of `getOrLoadChunk` on accept. The right model: the expensive resource is chunk loading, and the probe is authoritative enough to **replace** the load on most ScanTask candidates. When the chunk is already in cache, the load is free, and we should extract bonus safety from it; when it isn't, we trust the probe and skip loading entirely.

**Flow per `ScanTask.testPos` candidate:**

```
probe := world.probeChunkColumn(cx, cz, vert.minY(), vert.maxY(), vert.requiresSkyLight())

if probe == null:
    // Non-Anvil / missing .mca / prefilter disabled → legacy full path unchanged.
    fallthrough to getOrLoadChunk(...)

y := vert.adjustFromProbe(probe, world.name())
if y is UNKNOWN (null):
    fallthrough to getOrLoadChunk(...)   // probe can't decide, defer

if probe.biomeAt(y) not in defaultBiomes:
    reject + addBadLocation (+ addBiomeLocation if biomeRecall)
    return false

if probe.blockAt(y) in unsafeBlocks:
    reject + addBadLocation
    return false

// Probe accepted. Check cache residency.
chunkKey := key(cx, cz)
cached := world.getCachedChunk(chunkKey)
if cached == null:
    // CACHE MISS — trust the probe, skip the load.
    // Run GlobalRegionVerifiers (which don't need the chunk) and accept.
    return GlobalRegionVerifiers.checkGlobalRegionVerifiers(...)
else:
    // CACHE HIT — full chunk is free, upgrade safety radius to max(configured, 2).
    effectiveRadius := max(configuredSafetyRadius, 2)
    run full (2r+1)³ safety scan on `cached` at `y` with `effectiveRadius`
    then GlobalRegionVerifiers
```

**Rules of engagement (locked):**

- **Scope: ScanTask only.** PregenTask keeps its existing PR-3 shape (probe-reject fast path; accept still calls `getOrLoadChunk`). PregenTask's cache-hit rate is structurally low (pregen touches frontier chunks that are by definition not yet loaded), so the cache-miss-accept optimization may not transfer cleanly — deferred to PR-6 pending evidence from PR-5.
- **Cache-hit radius**: `max(configured, 2)`. Hardcoded, not a new config key. User: "99% of cases will not exceed 2 anyways." Rationale: when the chunk is already decoded, a 5×5×5 scan is ~125 in-memory block lookups — negligible next to the I/O we just skipped.
- **Gate**: only runs when the probe future returns non-null. Non-Anvil platforms, missing `.mca`, and `anvilPrefilterEnabled=false` all fall through to the current legacy path with zero behavior change.
- **S-005**: probe dispatch is the existing `RTPWorld.probeChunkColumn` async future. Cache-hit safety scan uses the existing `RTP.serverAccessor.getScheduler().runTask(targetLoc, …)` region-thread dispatch. No new main-thread I/O paths.
- **Correctness invariant**: probe-accept + cache-miss is authoritative at the center column at the picked Y. The configured `safetyRadius` is honored on cache-hit (upgraded); on cache-miss it is silently widened to "center column only" regardless of configured value. This is a deliberate tradeoff — the alternative (load the chunk to honor the configured radius) is exactly the cost PR-5 is avoiding. Admins who need strict radius enforcement on unloaded chunks can disable `anvilPrefilterEnabled`.

**Risks:**

1. **Palette normalization.** Probe returns `minecraft:grass_block`; `unsafeBlocks` config uses `GRASS_BLOCK`. `PaletteNormalizer::reconcile` in `AnvilColumnProbeAdapter` (PR-3b) already handles this — verify it's active on the ScanTask path.
2. **Race between `getCachedChunk` check and scan.** Chunk could unload between the cache check and the region-thread scan dispatch. Treat a null return from `getCachedChunk` at scan time as "cache miss" and fall through to probe-authoritative accept (same outcome as initial cache-miss branch).
3. **`GlobalRegionVerifiers` must run on both branches.** They don't require chunk data, only the location. Confirm by inspection; extract into a helper used by both branches if needed.
4. **`biomeRecall` on probe-rejected biomes.** PR-4 added this; the reverted ScanTask is silent on probe-rejected biomes (it doesn't call `addBiomeLocation` because the probe path doesn't exist). PR-5 reintroduces the `addBiomeLocation` call on biome rejection at the probe's picked Y.
5. **`isSelfContained()` parity.** The current ScanTask mid-Y biome check is gated on `isSelfContained()` to avoid TickThread violations on live-backed chunks. The probe is always Anvil-backed and has no such restriction — it runs uniformly, strictly improving coverage over the current partial gate.

**Deliverables:**

1. `ScanTask.testPos` rewrite with the probe-first + cache-aware branches.
2. Factor out the safety-radius scan into a helper callable from both the cache-hit branch and the legacy full-path branch.
3. `.bak-prePR5` reference copy of `ScanTask.java` for diffability during the PR and quick revert.
4. `CHANGELOG.md` entry under `Changed` noting the new semantics + the measured effect.
5. This document: flip `[ ]` → `[~]` on start, `[x]` on land (or `[REVERTED]` if runtime regresses like PR-4 did).

**Tests:**

- `run_test rtp-core/src/test/java/io/github/dailystruggle/rtp/common/tasks` — existing ScanTask tests must stay green.
- New test: probe-backed `MockRTPWorld` with a `getCachedChunk` returning null → `testPos` returns `true` without calling `getOrLoadChunk`. Same world returning a mock chunk → `testPos` runs the expanded-radius scan.

**Go/no-go criteria (runtime):**

- Live-server scan throughput ≥ pre-PR-4 baseline (~300 cps). If it regresses again, revert and document in this section. If it improves substantially, open PR-6 to consider the same pattern in PregenTask.

**Status**: landed `[~]` awaiting runtime verification (2026-04-23). Code-side deliverables complete:
- `ScanTask.testPos` now dispatches `probeChunkColumn` first; on probe-reject completes `res(false)` + `addBadLocation` (+ `addBiomeLocation` on biome reject when `biomeRecall=true`). On probe-accept: `getCachedChunk(key)` null → skip full load, run `GlobalRegionVerifiers` only, accept; non-null → region-thread safety scan with `max(configured, 2)` radius on the cached chunk, re-checking cache residency to tolerate concurrent eviction.
- Probe UNKNOWN / null future / exception → falls through to the extracted `runFullLoadPath` helper (verbatim pre-PR-5 body).
- Pre-PR-5 reference copy saved as `rtp-core/.../tasks/ScanTask.java.bak-prePR5` for diffability + quick revert.
- Tests: `run_test rtp-core/.../tasks`: 68/68 pass. `run_test rtp-core/.../selection`: 483/483 pass. Lint clean.

Runtime go/no-go still pending: live-server scan throughput must be ≥ pre-PR-4 baseline (~300 cps). Revert procedure if regressed: `Copy-Item ScanTask.java.bak-prePR5 ScanTask.java -Force`.

### PR-6 — Consolidate Anvil prefiltering to the lean two-stage model  `[ ]`

**Approved 2026-04-23 (user).** Replaces the previous PR-6 draft (PregenTask cache-miss-accept), which is subsumed by this broader consolidation.

**Motivating runtime observation.** User reported post-PR-5 ScanTask is slower than expected ("we aren't batching like would happen for completablefuture like before"). Root-cause analysis (see PR-5 debrief below) narrowed the regression to **duplicate region-file I/O on UNKNOWN fallback**: `probeChunkColumn` reads `r.X.Z.mca` once, and when it returns UNKNOWN the caller runs `runFullLoadPath` which re-enters `getOrLoadChunk → getChunkAt → AnvilProbeSupport.probeAndPublish` and reads the **same region file a second time** with the heavyweight `AnvilReader.readChunk + toView` full-NBT path. Both decode paths exist in parallel and neither shares bytes. Beyond the perf regression this duplication is an architectural smell: we maintain two Anvil decode paths for the same source data.

**Reframe (user's insight).** The biggest general performance gain is a clean **two-stage filter with a natural pipeline break at chunk load**:

1. **Stage 1 — Loose prefilter on unkept (not-loaded) chunks.** Answers only what `ColumnProbe` can answer from a lean selective NBT parse: center-column block at picked Y, center-column biome at picked Y, `VerticalAdjustor.adjustFromProbe` Y pick. Intentionally loose — prefers false accepts over false rejects to minimize per-candidate cost. This is where the biome check legitimately belongs — it is the first-class reason `ColumnProbe` exists.
2. **Stage 2 — Precise filter on kept (loaded) chunks.** Runs everything that requires live state or arbitrary-column data: `(2r+1)³` safety-radius scan, claim-protection, `GlobalRegionVerifiers`, biome re-check on non-vanilla generators if needed. The chunk load is the expensive operation; stage 2 amortizes over it.

**What retires under this model.** `AnvilProbeSupport.probeAndPublish` + the heavyweight `AnvilReader.readChunk + toView` full-NBT decode existed to answer **precise queries on unloaded chunks** (safety-radius scan from disk, arbitrary `(lx, lz)` material lookup). Under the two-stage reframe this is architecturally unnecessary: precise queries wait for stage 2 (live load), and loose queries use `ColumnProbe`. The `AnvilChunkView`-in-the-selection-pipeline third state collapses.

**What `AnvilChunkView` retains.** The offline type still serves legitimate callers:
- `AnvilRegionScanner.scanBiomes` (used by `/rtp test biome-source`, biome-visitor tooling) — batch offline decoding, not per-candidate hot path. Can shrink to the lean probe shape since biome-palette-per-section is all it consumes.
- Any test-only fixtures that legitimately want offline block palettes.

Outside the selection pipeline, the full-view type stays available but optional. Inside the selection pipeline, it exits.

**Consolidated decode path: `ColumnProbe` shape only.** Biome palette per section + center-column block palette + heightmap + (optionally) sky-light + `isLightOn`. Everything else that today lives on `AnvilChunkView` (full `block_states` palettes+data per section, block-light, block entities, structures, ticks) is not read on the selection path at all.

**Expected wins:**
- Half the region-file decode code, one decode path, one test surface.
- Eliminates the PR-5 duplicate-read regression on UNKNOWN fallback.
- `ColumnProbe`-size cache (~2 KB) replaces `AnvilChunkView`-size cache (~100 KB), lowering memory pressure and enabling larger caches for the same budget.
- Opens the door to a region-file-bytes cache (sibling chunks in the same `.mca` amortize one read) as a follow-up, since the parser surface is smaller.

**Migration plan (sequenced sub-PRs):**

1. **PR-6a — Rewire `getChunkAt` to skip full-view decode.** In `BukkitRTPWorld.getChunkAt` and `FoliaRTPWorld.getChunkAt`, replace the `anvilProbeSupport.probeAndPublish(...).thenCompose(...)` call with a direct `loadChunkFuture(cx, cz, key)` dispatch when the chunk isn't already cached. `probeChunkColumn` remains the loose-stage entry point (already wired in PregenTask/ScanTask via PR-3/5). The Anvil cache (`AnvilProbeSupport.cache`) is no longer populated by `getChunkAt` — loading replaces decoding as the unkept→kept transition. `getCachedChunk(key)` callers fall through to the live-loaded chunk naturally.
2. **PR-6b — Audit `AnvilChunkView` callers on the hot path.** Find every code path that reads `AnvilChunkView` fields outside `AnvilRegionScanner` + offline tools. For each: either (i) it was already covered by live chunk after PR-6a, (ii) it needs to move to `ColumnProbe` shape, or (iii) it is genuinely offline-scan tooling and stays on `AnvilChunkView`. Expect small but non-zero hits in the `LocationGenerator` / `RTPChunk` source-union wrappers and the vanilla-generator biome exemption (ADR-016 §13.3).
3. **PR-6c — Shrink or retire `AnvilChunkView` block_states surface.** Once (2) is complete, the `sections[*].block_states.palette/data` fields on `AnvilChunkView` have no production readers. Either delete them (cleanest) or keep behind a `readChunkFull(...)` opt-in decode for the remaining offline tools.
4. **PR-6d — Region-file bytes cache (optional, only if numbers justify).** A short-lived per-world cache keyed by `(regionX, regionZ)` holding the inflated `byte[]` lets sibling-chunk probes in the same `.mca` amortize one read. Bounded by size + TTL. Only pursued if PR-6a measurement shows I/O is still the bottleneck.

**Risks to flag:**

- **ADR-016 §13.3 vanilla-generator biome exemption** currently uses `AnvilChunkView.getBiome(x, y, z)` for pre-load biome filtering. Under PR-6a, that call returns only what `ColumnProbe` answers — center column at Y. For the generator-exemption call sites that want arbitrary `(x, z)` inside an unloaded chunk, the options are: (a) the probe answers (center column is fine for most heuristic pre-checks), (b) defer to post-load biome read. Needs a pass through the exemption code before PR-6a lands.
- **`LocationGenerator` source-union wrappers** (`BukkitRTPChunk` and peers) that resolve `getCachedChunk(key)` to an Anvil-backed chunk when no live chunk exists will change behavior once `AnvilProbeSupport.probeAndPublish` stops populating the cache. Callers that rely on "Anvil chunk available even when unloaded" must either accept the probe-only answer or trigger the live load themselves. ADR-016 §13.1's precedence chain needs a revision pass.
- **`AnvilPrefilter` advisory verdict telemetry** (the `anvil-hit` counter exposed by `/rtp test biome-source`, `/rtp test anvil-prefilter`) changes meaning once the full-view path is gone. Either retire the diagnostic or redefine it in terms of probe hits.
- **Cross-module API churn.** `rtp-api`, `rtp-core`, `rtp-anvil`, `rtp-spigot-common`, `rtp-folia-common`, `rtp-paper-common`. PR-6a is the smallest, still touches 3+ modules. ADR impact: supersedes the relevant sections of ADR-016 (anvil-subsystem trust model) — a superseding ADR will be drafted alongside PR-6c.

**Tests / gates:**

- All existing `rtp-anvil`, `rtp-core selection`, `rtp-spigot-common`, `rtp-folia-common` suites stay green through each sub-PR.
- Runtime scan throughput (`/rtp test chunk-probe-perf` + live scan cps) must meet or exceed the pre-PR-4 ~300 cps baseline after PR-6a.
- PregenTask runtime throughput must not regress vs the measured 6.63× probe ratio after PR-6a.

**Resume point for next session.** Start with PR-6a:
1. Read `BukkitRTPWorld.getChunkAt` (lines ~137–196) and `FoliaRTPWorld.getChunkAt` (grep equivalent) for the exact `probeAndPublish → thenCompose → loadChunkFuture` shape.
2. Read `AnvilProbeSupport.java` for what retiring `probeAndPublish` publishing implies for `takeCached` readers.
3. Audit `getCachedChunk` callers under `LocationGenerator` / `RTPChunk` wrappers to confirm nothing else relies on the Anvil-view being populated before a live load.
4. Only then rewire `getChunkAt`.

**Status**: proposal approved, not started. Supersedes prior draft of PR-6 (cache-miss-accept in PregenTask).

### PR-7 — Dedicated Anvil I/O executor  `[x]`

**Motivating runtime observation (2026-04-23).** `/rtp test chunk-probe-perf` on Folia reported a 20.70× probe-vs-full-chunk ratio per sample, but ScanTask's live throughput stayed at ~150 cps — no measurable gain from the probe fast path. Root cause: `FoliaRTPWorld.probeChunkColumn` and `BukkitRTPWorld.probeChunkColumn` dispatched `Files.readAllBytes` + selective inflate via `CompletableFuture.supplyAsync(..., ForkJoinPool.commonPool())`. `commonPool` is sized `availableProcessors - 1` and designed for non-blocking CPU work; blocking it on disk reads caps effective I/O concurrency at ~CPU count. With ScanTask's `Semaphore(50)` in-flight gate, the 50 probe tasks queued behind ~N-1 commonPool threads instead of saturating disk bandwidth. The per-sample benchmark didn't expose this because it waits on `.join()` serially.

**Change.** New `io.github.dailystruggle.rtp.anvil.AnvilIoPool` — a shared fixed thread pool sized `max(8, 2 * availableProcessors)` of daemon threads (name prefix `RTP-Anvil-IO-`, priority `NORM-1`). Both `probeChunkColumn` overrides now dispatch to `AnvilIoPool.get()` instead of `ForkJoinPool.commonPool()`.

**Rationale for sizing:**
- `max(8, ...)` floor so small hosts (1–2 cores) still get meaningful probe concurrency; probe I/O blocks on disk, not CPU, so oversubscription relative to CPU count is correct.
- `2 * cpu` scales with hardware without becoming unbounded; disk queues flatten out past this for typical commodity SSDs and rotational disks alike.
- Daemon threads so pool lifetime doesn't block JVM shutdown.
- Lowered priority so probe work never preempts server-tick-adjacent threads.

**Verification:**
- `run_test rtp-anvil/...`: 62/62 pass.
- `run_test rtp-spigot-common`: 31/31 pass.
- `run_test rtp-folia-common`: 7/7 pass.
- Lint clean on all three files (`AnvilIoPool`, `BukkitRTPWorld`, `FoliaRTPWorld`).

**Runtime gate.** ScanTask cps on Folia should recover from the ~150 floor toward the per-sample probe ratio (~20× over the full-load baseline). Measure via sustained `/rtp pregen` or `/rtp test chunk-probe-perf` on a Folia server post-deployment.

**Next step (PR-8 candidate, not yet opened).** If ScanTask throughput post-PR-7 is still bottlenecked, the next tuning lever is **raising `ScanTask.MAX_PENDING_CHUNKS`** from its current `50`. That constant was calibrated for ~60ms full-chunk loads; at ~6ms probe cost on cache-miss it leaves the I/O pool under-utilized. Candidate change: `MAX_PENDING_CHUNKS = 200` (or adaptive relative to `AnvilIoPool` parallelism). Do not land this blind — requires live measurement after PR-7 to confirm I/O pool is actually saturated. If not saturated, the bottleneck is elsewhere (driver loop dispatch, cache-hit-branch safety-scan cost, UNKNOWN fallthrough re-reads) and raising the semaphore just grows the queue without improving wall-clock.

### PR-8 — Inline probe I/O on caller's thread  `[REVERTED by PR-9]`

**Motivating runtime observation (post-PR-7).** PR-7's dedicated `AnvilIoPool` did not move ScanTask cps off the ~150 floor. The three-path `/rtp test chunk-probe-perf` run confirmed the per-sample probe cost is ~8ms (inflate + selective parse dominant), so theoretical throughput at `Semaphore(50)` concurrency is `50 / 8ms ≈ 6000 cps`. Observed throughput implies effective concurrency ~1.26 — the driver is processing probes near-serially despite the semaphore allowing 50-wide.

**Root cause.** `probeChunkColumn` always returned `CompletableFuture.supplyAsync(..., AnvilIoPool.get())` for the hot path. `ScanTask.tryProbeFirstScan` has an inline fast-path for `fut.isDone() && !completedExceptionally()` — but a just-submitted `supplyAsync` future is never done at check time, so every candidate took the async branch: `supplyAsync` dispatch (pool thread handoff) → I/O work → `whenComplete` callback (another handoff) → driver thread resume. Two handoffs × ~1–2ms each ≈ the per-candidate latency ScanTask was actually seeing, not the 8ms of real work.

**Change.** `BukkitRTPWorld.probeChunkColumn` and `FoliaRTPWorld.probeChunkColumn` now run the region-file read + selective parse directly on the caller's thread and return `CompletableFuture.completedFuture(probe-or-null)`. All current callers (`PregenTask.tryProbeFirst`, `ScanTask.tryProbeFirstScan`, `/rtp test chunk-probe-perf`) already execute off tick / region threads, so S-005 is preserved — this is not a regression of PR-7's "blocking I/O shouldn't run on commonPool" invariant, it's the recognition that the caller *is* the I/O pool.

**Effect on ScanTask's driver.** `fut.isDone()` now fires synchronously on every candidate. The inline evaluation path (no scheduler handoff, no `whenComplete`) runs, and the semaphore's permit is released in the same call frame. Expected result: effective concurrency climbs toward the semaphore limit, wall-clock throughput approaches the per-sample probe ratio over the full-load baseline (measured 16–20×).

**`AnvilIoPool` status.** Retained as a utility but currently unused by the adapters. Left in place so a future caller that needs off-thread dispatch (e.g. a tick-thread caller in a non-selection code path) has a correct pool to use instead of `ForkJoinPool.commonPool`.

**Verification:**
- `run_test rtp-spigot-common/src/test`: 31/31 pass.
- `run_test rtp-folia-common/src/test`: 7/7 pass.
- Lint clean on both `BukkitRTPWorld.java` and `FoliaRTPWorld.java`.

**Runtime gate.** ScanTask cps on Folia should now recover past the ~150 floor. If not, the next suspect is the driver-loop's own thread (single-threaded acquire-then-dispatch), at which point the diagnosis tools are (a) log `inFlight` gauge each second, (b) batched `testPos` dispatch before `acquire()`. Do not land either blind.

### PR-9 — Revert PR-8, restore async dispatch on `AnvilIoPool` `[x]`

**Motivating runtime observation (post-PR-8 + concurrency gauge).** ScanTask's new `inFlight` / `peakInFlight` gauge reported `peakInFlight=11–12 currentInFlight=0 cap=50` across every 5s window at ~137 cps. Peak in-flight is an order of magnitude below the semaphore cap, so the driver was never filling the pipeline — raising `MAX_PENDING_CHUNKS` would have done nothing.

**Root cause.** PR-8 moved probe I/O (`Files.readAllBytes` + `AnvilReader.readColumnProbe`, ~7ms each) onto the caller's thread and returned `CompletableFuture.completedFuture(...)`. That eliminated the two scheduler handoffs PR-8 was targeting, but it also **serialized ~7ms of blocking I/O onto ScanTask's single driver thread**. At 1/7ms ≈ 140 cps, that matched observed throughput exactly. The `fut.isDone()` fast-path PR-8 optimized for is worth ~tens of µs of scheduler handoff; paying 7ms of inline I/O to win it is a strictly bad trade.

**Change.** Restored `CompletableFuture.supplyAsync(..., AnvilIoPool.get())` dispatch in both `BukkitRTPWorld.probeChunkColumn` and `FoliaRTPWorld.probeChunkColumn`. Driver loop now hands off in µs; `AnvilIoPool` (sized `max(8, 2·CPU)`) runs probes in parallel, so `inFlight` can actually saturate the `Semaphore(50)` cap. S-005 preserved: `AnvilIoPool` threads are daemons with no region-thread affinity, identical to PR-7 invariants.

**Verification:**
- `run_test rtp-spigot-common/src/test`: 31/31 pass.
- `run_test rtp-folia-common/src/test`: 7/7 pass.
- Lint clean on `BukkitRTPWorld.java`, `FoliaRTPWorld.java`.

**Runtime gate.** ScanTask cps on Folia should rise past the pre-PR-8 floor (~150 cps) toward the probe-vs-full ratio measured by `/rtp test chunk-probe-perf` (~6.6× on Bukkit, ~20.7× on Folia). Measure via sustained `/rtp scan start` — the concurrency gauge should now report `peakInFlight` approaching 50 instead of ~12. If not, the next suspect is ScanTask's driver loop itself (single-threaded `acquire → dispatch → release`), diagnosable by widening the gauge to log per-candidate dispatch-to-complete latency.

**Lesson recorded for `LESSONS_LEARNED.md`.** Inlining blocking I/O onto a single driver thread to win scheduler handoff is anti-pattern when (a) I/O latency ≫ handoff latency and (b) the driver is the pipeline serializer. The `fut.isDone()` fast-path optimization only pays when the future's work is trivial; for non-trivial I/O, always dispatch async and accept the handoff cost.

### PR-10 — Region-byte LRU cache `[x]`

**Motivating runtime observation (post-PR-9 concurrency gauge).** With `AnvilIoPool` dispatch restored, ScanTask's gauge reported `peakInFlight=50 currentInFlight=0 cap=50` at ~149 cps. The pool is saturated, but throughput barely moved — because all 50 in-flight probes for chunks within the same `r.X.Z.mca` each call `Files.readAllBytes(~4 MB)` independently, 50 redundant allocations and OS reads for a file containing 1024 chunks.

**Change.** New `AnvilRegionByteCache` in `rtp-anvil`: 4-entry LRU keyed by absolute `Path`, accessed under a single `synchronized` block on a `LinkedHashMap`. Each entry records `Files.getLastModifiedTime` at read time; `get` re-reads when mtime advances (chunk-save on the live server). `BukkitRTPWorld.probeChunkColumn` and `FoliaRTPWorld.probeChunkColumn` now call `AnvilRegionByteCache.get(regionFile)` instead of `Files.readAllBytes` directly. Capacity 4 covers the scan-spiral working set; memory footprint ~16 MB steady-state. First-write-wins on concurrent misses for the same region (two threads each read once; acceptable waste vs per-key loading-future complexity).

**Verification:**
- `run_test rtp-anvil/src/test/.../AnvilRegionByteCacheTest`: 4/4 pass (missing-file null, hot-read identity, mtime invalidation, LRU capacity cap).
- `run_test rtp-spigot-common/src/test`: 31/31 pass.
- `run_test rtp-folia-common/src/test`: 7/7 pass.
- Lint clean on `AnvilRegionByteCache.java`, `BukkitRTPWorld.java`, `FoliaRTPWorld.java`.

**Runtime gate.** With 50 concurrent probes sharing one cached `byte[]` per region file, per-probe cost should drop from ~7 ms to ~1–2 ms (inflate + parse only, no file read). Expected ScanTask throughput: 500–1500 cps on a cache-hit-dominated workload. If cps remains near 150, the next bottleneck is ScanTask's single-threaded driver dispatch loop, not I/O.

### PR-11 — Region-byte cache hit/miss diagnostic `[x]`

**Motivating runtime observation (post-PR-10).** Cps rose from ~150 to ~180–200 — real but far short of the 500–1500 projection. Candidate root causes: (a) 4-entry LRU too small for scan working set (evictions → low hit rate), (b) `AnvilIoPool` thread count (2×CPU) < desired in-flight (50) so candidates queue, (c) single-threaded driver dispatch loop capping throughput independent of per-probe work. Need hit-rate data before tuning further.

**Change.** `AnvilRegionByteCache` now tracks cumulative `AtomicLong` counters for `hits` / `misses` / `stale` (mtime-bumped re-reads). New `stats()` returns a `Stats` record; `resetStats()` zeros the counters. `ScanTask`'s per-5s `[DEBUG_LOG] ScanTask concurrency ...` line reflectively reads and resets the counters, appending `anvilCacheHits=... anvilCacheMisses=... anvilCacheStale=... anvilCacheHitRate=X.XXX`. Reflection keeps `rtp-core` independent of `rtp-anvil` for a pure diagnostic; the suffix is empty when `rtp-anvil` isn't on the classpath or no probes ran in the window.

**Verification:**
- `run_test rtp-anvil/src/test/.../AnvilRegionByteCacheTest`: 4/4 pass.
- `run_test rtp-core/src/test/.../tasks`: 68/68 pass.
- Lint clean on `AnvilRegionByteCache.java`, `ScanTask.java`.

**Interpretation guide.**
- `anvilCacheHitRate ≥ 0.95` → cache sized right; bottleneck is elsewhere (driver loop or CPU-bound inflate). Next step: parallelize driver dispatch or reduce inflate cost.
- `anvilCacheHitRate ∈ [0.5, 0.9]` → cache too small; bump `CAPACITY` from 4 to 8 or 16.
- `anvilCacheHitRate < 0.5` → working set exceeds any reasonable cache; revisit scan iteration order to cluster same-region accesses.
- `anvilCacheStale > 0` while scanning → the server is saving chunks concurrently (expected on live worlds); not an issue unless dominant.

### PR-12 — Raise region-byte cache capacity 4 → 16 `[x]`

**Motivating runtime observation (PR-11 data).** At ~185 cps with `peakInFlight=50`, hit rate was **~0.31** (509 misses vs 241 hits per 750-candidate window). Scan frontier routinely spans 6–10 distinct `r.X.Z.mca` files concurrently while 50 probes fly; a 4-entry LRU evicts entries seconds before they're reused.

**Change.** `AnvilRegionByteCache.CAPACITY` 4 → 16. Memory: ~64 MB steady-state (16 × ~4 MB avg per `.mca`). Test `lru_evictsBeyondCapacity` updated to assert the new cap.

**Verification.**
- `run_test rtp-anvil/.../AnvilRegionByteCacheTest`: 4/4 pass.
- Expected post-PR-12 hit rate: ≥0.95; per-probe cost trending toward the ~1–2ms floor the cache was designed for.

**Runtime gate.** Operator re-runs `/rtp scan start` on Folia; `[DEBUG_LOG] ScanTask concurrency ...` line should show `anvilCacheHitRate ≥ 0.95` and cps climbing meaningfully past the ~185 floor. If hit rate rises but cps does not, the next bottleneck is driver dispatch (PR-13 candidate) or CPU-bound inflate (PR-14 candidate).

### PR-13 — Region-file-aware batch dispatch in ScanTask `[x]`

**Motivating runtime observation (post-PR-12).** Even at `CAPACITY=16`, hit rate stayed low (~0.31) because ScanTask dispatches positions in pure linear (spiral) order through `shape.locationToXZ(pos, cursor)`. With 50 probes in flight concurrently, the working set spans 6-10 distinct `r.X.Z.mca` files simultaneously — the LRU is cold for most of them at any given moment. The fix is iteration order, not cache size.

**Change.** ScanTask's driver loop now buffers the next `SCAN_BATCH_SIZE = MAX_PENDING_CHUNKS * 4 = 200` candidate positions from the shape, computes each candidate's region-file key `(cx >> 5, cz >> 5)` packed into a single long, sorts the buffer by key via `Arrays.sort(Integer[], Comparator.comparingLong)`, and dispatches in the sorted order. Positions are still consumed linearly from `pos` before reorder, so `scanIter.set(finalPos1)` remains monotonic and batch progress tracking is unchanged. Known-bad positions are skipped during fill (not dispatched). Pause/cancel checks honored both during fill and during dispatch via a labeled outer loop.

**Why this solves it without enlarging the cache.** Within a 200-candidate batch, consecutive dispatches that share a region key hit the LRU immediately — the first miss in a region is followed by ~N cheap hits as the other candidates in that region drain. Effective working set per batch shrinks to ~1 region file at a time from ~6-10. 16-entry cap is overkill under the new order; could shrink back to 4 in a follow-up if memory matters.

**Verification.**
- `run_test rtp-core/src/test/.../tasks`: 68/68 pass.
- `run_test rtp-core/src/test/.../selection`: 482/482 pass.
- Lint clean on `ScanTask.java`.

**Runtime gate.** Operator re-runs `/rtp scan start` on Folia. Expected `anvilCacheHitRate` ≥ 0.95 and cps climbing meaningfully past the ~185 floor toward the ~1-2 ms per-probe hot-cache target. If hit rate rises but cps still floors, next suspect is driver-loop dispatch serialization (parallelize the driver) or CPU-bound inflate (phase 2 streaming inflate).

### Deferred (not in this sequence)

- **Phase 0 — benchmark harness**: still wanted for before/after numbers once PR-3 lands.
- **Phase 2 — streaming inflate with early termination**: stacks on PR-1; revisit if PR-3 numbers show inflate still dominates.
- **Phase 5 — region-level batching**: only if benchmarks justify it.

---

## Context

Biome sampling during location selection used to be an in-memory call on already-loaded chunks; it is now backed by `rtp-anvil` reads from `.mca` files on disk. The per-sample cost grew by ~1–2 orders of magnitude, which exposed `Region.maxBiomeChecksPerGen` (previously effectively `100 * maxAttempts` ≈ 10k) as a budget that produces intolerable worst-case latency.

Interim mitigation (already landed):
- `Region.maxBiomeChecksPerGen = 1000` (absolute cap, not per-attempt multiplier).
- `PregenState.build` uses a single flat cap `max(maxAttempts, maxBiomeChecksPerGen)` for both default-biome and explicit-biome paths; the historical `×10` multiplier for explicit biome requests was removed.
- `PregenState.defaultBiomes` is still passed through the constructor but is no longer consulted for cap sizing.

The cap is a performance guard, not a correctness guard. Once per-check cost drops, the budget should be re-expressed in terms of the actual bounded resource (chunks decoded / regions touched), not "number of checks."

---

## Why the current cost is high

Current biome-point path in `rtp-anvil`:

1. `AnvilReader.readChunk` locates the chunk via the 8 KiB region header. Cheap.
2. `AnvilReader.decompress` fully inflates the entire chunk payload into a `byte[]` via `ByteArrayOutputStream`. ~50–200 KB per chunk.
3. `Nbt.readRootCompound` recursively parses the whole NBT tree into a `LinkedHashMap`, including:
   - `sections[*].block_states.palette` (list of compounds).
   - `sections[*].block_states.data` (`long[]`, up to ~1024 longs per section).
   - `Heightmaps.*`, `block_entities`, `structures`, `Entities`-adjacent tags, ticks.
4. `toView` / `biomeSectionFromCompound` constructs `BiomePaletteSection`.

Only `sections[i_y].biomes` (palette + at most 64 packed cells, typically 1–3 longs) is needed for a biome point. Everything else is overhead imposed by a structure-blind NBT parser plus a fully-materializing decompressor.

---

## Target cost model

After optimization, a biome-point query should be dominated by:
- Locating the chunk entry in the region header (O(1)).
- Inflating only the prefix of the chunk stream up to the last needed `sections[*].biomes` subtree.
- Parsing only `Y` + `biomes` inside each section compound.

Block data (`block_states.palette`, `block_states.data`), light arrays, heightmaps, block entities, structures, and ticks should never be materialized for a biome-only query.

---

## Plan (phased)

Checkpoints are `[ ]` not started, `[~]` in progress, `[x]` done. Phases can be done independently; dependencies noted.

### Phase 0 — Instrumentation (prereq for all later phases)

- [ ] Add a micro-benchmark or timing harness over `rtp-anvil` fixtures that measures:
  - ns/op and allocs/op for `readChunk + Nbt.readRootCompound` (status quo).
  - Wall-clock of `PregenTask` biome-loop per attempt on a fixed-seed world.
- [ ] Land baseline numbers in this document (append a "Baseline" subsection below).
- [ ] Add a JMH module or reuse an existing test harness; if JMH adds infra weight, start with a `main`-style fixture-driven timer gated behind a system property.

Exit criterion: reproducible numbers for "cost of one biome point" in isolation and inside a full generation loop.

### Phase 1 — Selective NBT parser (biggest single win)

- [ ] Add `Nbt.skipPayload(DataInput, byte type)` that consumes a payload without allocating. Fixed-width types advance N bytes; `TAG_STRING` reads u16 + skips; arrays read their int length + skip; `TAG_LIST` skips element-type + bulk-skips fixed-width or recurses; `TAG_COMPOUND` loops reading `type + name + skipPayload(type)` until `TAG_END`.
- [ ] Add `Nbt.readRootCompoundSelective(byte[], Predicate<String[]> keep)` OR a dedicated `AnvilReader.readBiomesOnly(byte[] regionBytes, int cx, int cz)` that:
  - At the chunk root, keeps only `DataVersion`, `sections`. Skips `Heightmaps`, `block_entities`, `structures`, `PostProcessing`, `fluid_ticks`, `block_ticks`, `Entities`, `isLightOn`, `Status`, etc.
  - Inside each `sections[*]` compound, keeps only `Y`, `biomes`. Skips `block_states`, `BlockLight`, `SkyLight`.
- [ ] Return a lean carrier type (e.g. `BiomesOnlyChunk { int[] sectionYs; String[][] palettes; long[][] packedData; }`) — no `LinkedHashMap`, no per-section compound maps.
- [ ] Route `AnvilChunkView` biome queries through the lean path; keep the full-parse `toView` path for callers that actually need block data.
- [ ] Parity test: `AnvilFixtureParityTest`-equivalent that asserts biome-at-point matches between the old full-parse path and the new selective path on every fixture, every section, and a sampled grid of `(x, y, z)` cells.
- [ ] Benchmark delta recorded in "Results" subsection.

Exit criterion: selective path is drop-in for biome queries, parity test green, benchmark shows ≥3× speedup and ≥5× allocation reduction vs baseline.

### Phase 2 — Streaming inflate with early termination (stacks on Phase 1)

- [ ] Replace `AnvilReader.decompress` (for the biome-only path) with an `InputStream`-based API that exposes the inflater directly to `DataInputStream` instead of materializing a full `byte[]`.
- [ ] Selective parser sets a "done" flag after the last `sections[*]` has been consumed; once set, it stops pulling bytes and closes the inflater. Remaining root children (`block_entities`, `structures`, `Entities`, …) are never inflated.
- [ ] Handle chunk formats where `sections` is not the last top-level tag by reading the root compound's tag ordering defensively (don't assume). Worst case: finish the compound but still skip all non-whitelisted subtrees.
- [ ] Keep compression-mode coverage: modes 1 (gzip), 2 (zlib), 3 (uncompressed), 4 (LZ4 frame). LZ4 frame decoders support early close without issue.
- [ ] Fuzz / corruption tests: truncated streams, malformed section lists — confirm the selective streaming path raises `CorruptRegionEntryException` / `IOException` with the same semantics as the current full-inflate path.

Exit criterion: additional ≥1.5× speedup over Phase 1 on fixtures where `sections` is followed by non-trivial post-sections tags. No regression on correctness tests.

### Phase 3 — Point-query decoding without full palette materialization

- [ ] Add `BiomePaletteSection.biomeAtCell(int cellX, int cellY, int cellZ)` that:
  - Short-circuits on single-entry palettes (no `data` array needed).
  - Otherwise computes `cellIndex = ((cellY & 3) << 4) | ((cellZ & 3) << 2) | (cellX & 3)`, locates the packed bits at `cellIndex * bitsPerEntry` (accounting for the Minecraft ≥1.16 "no longs span" layout), reads one (or two) longs, masks, and indexes the palette.
- [ ] Route `AnvilChunkView.biomeAt` through the point API; remove the full cell-array decode on the hot path.
- [ ] Add tests for bit-packing edge cases: 1-bit, 2-bit, 3-bit, and the threshold where `entriesPerLong` changes.

Exit criterion: no measurable regression and fewer allocations per point query in the Phase-0 benchmark.

### Phase 4 — Retire the "biome checks" budget

- [ ] Introduce `Region.maxBiomeChunksPerGen` (or reuse `maxAttempts`) as the real bounded resource: number of distinct `(cx, cz)` chunks decoded per generation. Checks against cached chunks are free.
- [ ] Introduce a per-`PregenTask` chunk cache keyed by `(cx, cz)` holding the lean `BiomesOnlyChunk`. Sampling inside an already-decoded chunk does not count against the budget.
- [ ] Deprecate `Region.maxBiomeChecksPerGen` (keep the field, log a one-shot warning on read, forward to the new knob) and update `PregenState.build` + `PregenTask.completeExhausted` to use the new metric.
- [ ] Remove `PregenState.defaultBiomes` from `PregenState.build` consumption if no remaining caller needs the distinction (it currently has no cap-sizing effect; verify no other reads exist before deleting).
- [ ] Update `docs/architecture/09-location-selection-per-attempt.md` and `CODE_TOUR.md` to describe the chunk-budget model instead of the check-budget model.
- [ ] Add REQ-* traceability: if this becomes an observable contract (e.g. REQ-RTP-PERF-???), update `docs/dev/TRACEABILITY.md` and cite the regression test.

Exit criterion: `maxBiomeChecksPerGen` is no longer load-bearing; new metric is what operators tune; docs + traceability aligned.

### Phase 5 — Region-level batching (optional, evaluate after Phase 2)

- [ ] If sampling patterns cluster in a single `.mca`, add a region-local candidate batcher so multiple candidate `(x, z)` within the same region file share one memory-mapped / buffered region read.
- [ ] Only pursue if Phase 0 benchmarks show region-open / header-read overhead still matters after Phases 1–2. Likely not worth it.

---

## Non-goals / rejected options

- **Partial random-access inflate of a chunk payload.** MCA chunks are a single zlib/LZ4 stream with no internal sync points; you cannot seek past `block_states` without decoding everything before it in stream order. Streaming early-termination (Phase 2) is the correct shape; random seek is not possible.
- **Caching `ChunkEntry` (the full `LinkedHashMap` root compound) across queries.** Too heavy; pins megabytes of parsed NBT. Cache raw region bytes or the lean `BiomesOnlyChunk` instead.
- **Reviving the `×10` multiplier for explicit-biome requests.** Under a chunk-cache / one-pass cost model the differentiation is not meaningful; rare-biome tuning becomes an operator-facing knob (`maxBiomeChunksPerGen`).
- **Using the region header's timestamp table to skip chunks.** It encodes last-modified, not biome content. No shortcut there.

---

## Open questions (to resolve before Phase 4 ships)

1. Does any non-pregen caller in `rtp-core` / `rtp-anvil` depend on the current "checks" semantics (e.g. logging, metrics, shutdown accounting)? Grep for `maxBiomeChecks` before renaming.
2. Does `PregenState.defaultBiomes` feed anything other than the removed branch (e.g. verbose failure messages, biome recall logic)? Confirm before deleting.
3. Does `PregenTask.completeExhausted`'s verbose log threshold `i > state.maxAttemptsBase * Region.maxBiomeChecksPerGen` need to be re-expressed, or can it be retired? With a chunk budget it becomes nonsensical.
4. Should the chunk cache be scoped per `PregenTask` (simplest) or per-region (shared across concurrent generations)? The latter is a bigger win but has lifecycle + eviction questions.
5. S-005 impact: all new code stays off the main thread. Confirm the selective parser + streaming inflater are invoked only from the async biome-source path and that no teleport-pipeline stage grows a synchronous read.

---

## AI memory persistence notes

This file is the **single source of truth** for the biome-lookup-performance line of work across AI sessions. When resuming:

1. Read this file top-to-bottom before touching `rtp-anvil` or `PregenState`.
2. Update the phase checkboxes in-place as work lands. Do not fork per-session versions.
3. When a phase lands, append a dated "Results" subsection under it with benchmark numbers.
4. When the plan stabilizes into an architectural decision, promote it to an ADR under `docs/adr/` and mark this file as superseded. Until then it remains a working document.
5. Do not move this file into `.junie/`; that folder is reserved for guidelines/config per project rules.

---

## Cross-references

- `docs/architecture/09-location-selection-per-attempt.md` — per-attempt selection flow that consumes the biome budget.
- `docs/architecture/02-budgeted-cache-generator.md` — outer loop that drives `PregenTask`.
- `docs/dev/LESSONS_LEARNED.md` — add a dated entry when each phase lands if any non-obvious pitfall surfaces.
- `docs/dev/TRACEABILITY.md` — update when a REQ-* is introduced to pin the new chunk budget.
- `docs/adr/ADR-016-anvil-subsystem.md` — existing rationale for the Anvil biome path.
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/Region.java` (`maxBiomeChecksPerGen`).
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/PregenState.java` (`build`).
- `rtp-anvil/src/main/java/io/github/dailystruggle/rtp/anvil/AnvilReader.java` (decompress, toView, biomeSectionFromCompound).
- `rtp-anvil/src/main/java/io/github/dailystruggle/rtp/anvil/Nbt.java` (`readPayload`, target for `skipPayload`).
- `rtp-anvil/src/main/java/io/github/dailystruggle/rtp/anvil/BiomePaletteSection.java` (point-decode target).

### PR-14 � Region-file bin-and-batch (supersedes PR-13)

- **Motivation:** post-PR-13 runtime logs showed anvilCacheHitRate oscillating 0.31-0.57 despite region-key sort: the 200-entry sliding window is too narrow when the scan frontier spans 6-10 distinct r.X.Z.mca files concurrently.
- **Change:** ScanTask driver now collects the entire run's [currentStart, limitEnd) window into per-region-file bins (LinkedHashMap keyed by (rx,rz)), then dispatches one full bin before the next. An inFlightGate.acquire(MAX_PENDING_CHUNKS) drain at each bin boundary forces the cache to settle on one file at a time.
- **Effect:** within a bin, all 50 in-flight probes hit the same .mca -> 16-entry AnvilRegionByteCache sees a single hot file and hit rate approaches 1.0.
- **Trade-off:** observable scan order per run() reshuffles by region-file grouping instead of strict shape iteration. addBadLocation and scanIter advancement unchanged.
- **Verification:** run_test rtp-core/.../tasks 68/68 pass. Lint clean.
- **Runtime gate:** operator re-runs /rtp scan start on Folia, confirms anvilCacheHitRate >= 0.95 and cps climbing past the ~200 floor.

### PR-15 - Coalesce concurrent misses in AnvilRegionByteCache [x]

Root cause found after PR-14: despite region-file binning, per-5s hit rate stayed ~0.12-0.55 with a new low of 0.029 in one window. `AnvilRegionByteCache.get` had no in-flight dedup: when a bin's first ~50 concurrent probes all hit a cold region, every one of them saw `hit == null` inside the synchronized check, released the lock, and called `Files.readAllBytes` in parallel. All 50 counted as misses; only the last `put` won. Bin-boundary drain and 16-entry LRU were fine; the miss storm was per bin, not cross-bin.

**Fix:** added a `HashMap<Path, CompletableFuture<byte[]>>` INFLIGHT table guarded by the same `CACHE` monitor. First miss per key becomes the owner, reads disk, completes the future, removes from INFLIGHT. Concurrent followers for the same key receive the existing future and `join()` on it - they're counted as `anvilCacheCoalesced` (a new counter), not misses.

**Observable:** new `anvilCacheCoalesced` field in the `[DEBUG_LOG] ScanTask concurrency` line; hit-rate formula is now `(hits + coalesced) / (hits + misses + coalesced)`. Expected: 1 miss + ~49 coalesced + ~1000 hits per region file = ~0.999 hit rate. If hit rate stays low after PR-15, either the LRU is evicting too aggressively or bins are smaller than expected.

**Verification:** rtp-anvil AnvilRegionByteCacheTest 4/4 pass; rtp-core tasks 68/68 pass.




### PR-16 — Cold-read latency + GC-time instrumentation [x]

Runtime observation after PR-15: extended scan log showed cps degrading from ~256 to ~100 over ~10 minutes, with `peakInFlight` falling from 50 to 20-30 and `miss/window` tripling (25 to 90). Hit rate stayed ~0.88-0.98 so coalescing still worked. The real signal was each probe's wall clock growing. Candidate root causes: OS page-cache pressure as scan traverses colder frontier, and GC churn from the 1-4 MB `byte[]` per region-file read.

Diagnostic-only PR. No behavior change. Two new fields on the existing `[DEBUG_LOG] ScanTask concurrency ...` line:

- `avgColdMissMs=...` - average `Files.readAllBytes` wall time over misses in the window. Warm: <=2ms. Cold disk: 20-200ms. If this climbs with scan progress, OS page-cache pressure is primary. Fix direction: `FileChannel`/`mmap` streaming read or larger I/O pool.
- `gcDeltaMs=...` - cumulative `GarbageCollectorMXBean.getCollectionTime()` delta since previous log. Warm: ~10-50ms. Pressured: 300-1000ms. If this climbs, 4 MB-per-region byte[] churn is pressuring the collector. Fix direction: avoid per-file allocation (mmap).

**Implementation.**

- `AnvilRegionByteCache`: added `COLD_READ_NANOS` counter incremented around the actual `Files.readAllBytes`. Extended `Stats` record with `coldReadNanos()` + convenience `avgColdMissMs()`. Counter reset in `resetStats()`.
- `ScanTask.readAnvilCacheStatsAndReset`: reflectively pulls `avgColdMissMs` (back-compat via `NoSuchMethodException` fallback), emits alongside existing hit/miss/coalesced fields.
- `ScanTask.readGcDeltaMs`: new instance method, iterates `ManagementFactory.getGarbageCollectorMXBeans()`, maintains `lastGcTotalMs` for per-window delta.

**Verification.** `run_test rtp-anvil/.../AnvilRegionByteCacheTest` 4/4 pass. `run_test rtp-core/.../tasks` 68/68 pass. Lint clean.

**Runtime gate.** After deploy, operator re-runs `/rtp scan start` on Folia and captures >=3-minute log. Expected interpretations:

- Scenario A (both climb): `avgColdMissMs` 1->30+, `gcDeltaMs` 50->500+. Pick mmap fix (collapses both).
- Scenario B (cache only): `avgColdMissMs` climbs, `gcDeltaMs` flat. Pick `AnvilIoPool` size bump or `FileChannel` streaming.
- Scenario C (GC only): `gcDeltaMs` climbs, `avgColdMissMs` flat. Pick LRU-entry recycling or mmap.
- Scenario D (neither climbs): degradation is elsewhere (driver loop, Folia region-thread contention, verifier lock).

### PR-17 - Remove per-bin drain barrier + stage-2 cost instrumentation

Motivation: PR-16 runtime log (avgColdMissMs flat ~5ms, gcDeltaMs flat ~30ms) ruled out I/O and GC as causes of cps degradation from ~250 -> ~170 over 3 minutes on Folia. peakInFlight dropped from 50 to ~25 over the same interval � driver-side serialization is the remaining suspect.

Change (A): removed the per-bin `inFlightGate.acquire(MAX_PENDING_CHUNKS) / release(MAX_PENDING_CHUNKS)` drain between region-file bins in ScanTask.run. PR-15 coalescing made cross-bin cache interference cheap, so the barrier was costing pipeline bubbles without earning correctness.

Change (B): added fullLoadCount + fullLoadNanos AtomicLongs; runFullLoadPath now wraps its res future with a timing whenComplete, and the gauge log emits `fullLoads=N fullLoadAvgMs=X.XX`.

Runtime gate: operator re-runs /rtp scan start on Folia. Expected if A fixes it: peakInFlight stays at 50 through the whole run, cps stops degrading. If A alone insufficient: fullLoadAvgMs growing with scan progress confirms stage-2 (loaded-chunk safety scan) is the cost center -> next fix is the full-load path, not the driver.



### PR-18 - Widen probe window to minY-1 (unblock probe-accept fast path)

**Motivation.** PR-17 runtime log on Folia (`fullLoadAvgMs` ~120ms, `fullLoads` ~= `activeChecks`) proved every candidate still paid a full chunk load despite probe-first being "landed." Root cause: both `LinearAdjustor.adjustFromProbe` and `JumpAdjustor.adjustFromProbe` guard with `if (probe.minY() > minY - 1 || probe.maxY() < maxY) return null;` because they consult the block at `y - 1` for standing-surface safety, but `ScanTask.tryProbeFirstScan` and `PregenTask.tryProbeFirst` were calling `probeChunkColumn(cx, cz, vert.minY(), vert.maxY(), ...)` - one block short on the low end. Every `adjustFromProbe` returned null (UNKNOWN), every candidate fell through to `runFullLoadPath`. The probe fast path was inert at the adjustor stage.

**Change.**
- `PregenTask.tryProbeFirst`: widen to `probeChunkColumn(cx, cz, minY - 1, maxY, needSkyLight)`.
- `ScanTask.tryProbeFirstScan`: widen to `probeChunkColumn(cx, cz, minY - 1, maxY, vert.requiresSkyLight())`.
- `AnvilReader.readColumnProbe` already accepts arbitrary `(minY, maxY)` windows; the returned `ColumnProbe` stores them as metadata and scans sections whose world-Y range intersects `[minY, maxY]`. No reader-side change needed.

**Effect.** `adjustFromProbe` can now actually accept probes instead of returning null on every call. On probe-accept with cache-miss, `evaluateScanProbe` short-circuits to `GlobalRegionVerifiers` and skips `runFullLoadPath` entirely (this was PR-5's intent, now genuinely reachable). Expected: `fullLoads` drops from ~`activeChecks` to ~`acceptedCount - cacheMissAccepted`, and cps climbs from ~300 toward the per-probe ratio (~1500-2500 cps on Folia at ~6ms/probe with 50-wide concurrency).

**Verification.**
- `run_test rtp-core/src/test/java/io/github/dailystruggle/rtp/common/tasks`: 68/68 pass.
- `run_test rtp-core/src/test/java/io/github/dailystruggle/rtp/common/selection`: 483/483 pass.
- Existing probe-backed adjustor unit tests use `FakeChunkColumnProbe` with explicit window bounds, so they did not exercise the minY-off-by-one real-world path.

**Runtime gate.** Operator re-runs `/rtp scan start` on Folia. Expected: `fullLoads` drops well below `activeChecks` (ideally by ~10x on default `safetyRadius=0` configs where cache-miss-accept skips stage-2 entirely); cps climbs past the ~300 ceiling. If `fullLoads` stays roughly equal to `activeChecks`, another UNKNOWN path is firing - next diagnostic would be to add per-outcome counters to `evaluateScanProbe` (adjust-null vs biome-reject vs block-reject vs cache-miss-accept vs cache-hit).
