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

### PR-2 — `rtp-core` hook + `VerticalAdjustor.adjustFromProbe`  `[ ]`

Still no behavior change (nothing calls the hook yet).

- [ ] Define `ColumnProbe` interface in `rtp-api` (so adjustors in `rtp-core` can reference it without pulling `rtp-anvil`); Anvil side implements it.
- [ ] `BiomeSource.probeChunkColumn(RTPWorld, int cx, int cz, int minY, int maxY) → CompletableFuture<Optional<ColumnProbe>>`. Anvil impl delegates to `AnvilReader.readColumnProbe`. Default no-op for non-Anvil platforms returns `Optional.empty()` → caller falls back to current path.
- [ ] `VerticalAdjustor.adjustFromProbe(ColumnProbe) → OptionalInt`. Default impl bridges to existing `adjust(RTPChunk)` via a probe-backed `RTPChunk` adapter (so every subclass works out of the box; overrides are optional optimization).
- [ ] Concrete overrides for the hot adjustor subclasses. Grep for `extends VerticalAdjustor` before coding to scope this.
- [ ] Unit tests per subclass: `adjustFromProbe` produces the same Y as `adjust(RTPChunk)` on a fixture-backed probe.

**Status**: not started. Depends on PR-1 `ColumnProbe` surface.

### PR-3 — `PregenTask` rewire + retire `maxBiomeChecksPerGen`  `[ ]`

Behavior change lands atomically here.

- [ ] New `LocationGenerator.FailTypes.prefilterBiome / prefilterBlock / prefilterRange`.
- [ ] `PregenTask`: per attempt, resolve `(cx, cz)` from shape, call `BiomeSource.probeChunkColumn`, then `vert.adjustFromProbe(probe)`, then biome check, then unsafe-block check on the center column (and same-chunk safety-radius cells — free, same probe). Full pipeline (existing safety eval + verifiers + teleport) runs only on accept.
- [ ] Delete `Region.maxBiomeChecksPerGen` + all readers:
  - `PregenState.build` (drop `maxBiomeChecks` construction).
  - `PregenState.maxBiomeChecks` field + constructor param.
  - `PregenState.defaultBiomes` field + constructor param (confirmed dead after PR-1+2 land).
  - `PregenTask.completeExhausted` verbose log threshold using the old constant.
  - Any test asserting the old value.
- [ ] Docs:
  - `docs/architecture/09-location-selection-per-attempt.md` — update per-attempt flow.
  - `docs/dev/BIOME_LOOKUP_PERF_PLAN.md` — tick phases 1 + 3 + 4; keep 0, 2, 5 open.
  - `CHANGELOG.md` — user-visible: `maxBiomeChecksPerGen` removed, budget is now `maxAttempts` (chunks probed).
- [ ] Tests:
  - `PregenTask` test asserting chunk-granular iteration and `FailTypes.prefilter*` accounting.
  - Superset-reject parity: on fixture-backed probes, every `REJECT_*` outcome implies the old pipeline also rejected (prevents the prefilter from ever over-rejecting).
- [ ] Run the full rtp-core + rtp-anvil test suites; do not ship PR-3 with failures.

**Status**: not started. Depends on PR-1 and PR-2.

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
