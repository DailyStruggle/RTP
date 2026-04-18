# Anvil Read-Only Pre-Filter Implementation Plan

> Status: Living document. Tracks the implementation work for ADR-016
> (`docs/adr/ADR-016-anvil-readonly-prefilter.md`). This plan is amended each
> time a phase lands. ADR-016 defines the *what* and *why*; this document
> defines the *where* and *in what order*.
>
> Scope boundary: everything described here is **Spigot-exclusive**. Paper
> and Folia use their native async chunk APIs and MUST NOT inherit or import
> the pre-filter code path.

## 1. Motivation

On vanilla Spigot forks, `BukkitRTPWorld.getChunkAt(cx, cz)` cannot avoid the
tick thread: the reflective `World#getChunkAtAsync(int,int) → CompletableFuture`
probe returns a future that Spigot resolves on the primary thread, and every
downstream `RTPChunk` read (`isSafe`, `isAir`, `getSkyLight`,
`getSurfaceHeight`) bounces through `org.bukkit.Chunk`, which is tick-bound.
The result is that under RTP load on vanilla Spigot, a significant fraction
of candidate-evaluation work ends up on the tick thread — pressuring
REQ-RTP-S-005 in letter and in spirit.

ADR-016 formalises the mitigation: for candidates whose chunk is **not
currently loaded**, read the block palette and motion-blocking heightmap
**directly from the region file on disk** on an async I/O thread. Rejected
candidates never trigger a live chunk load; accepted and unknown candidates
fall through to the existing async/sync load path, where the authoritative
`chunk.isSafe(...)` re-check remains the source of truth.

This plan enumerates the exact files touched, the exclusivity mechanism, the
ArchUnit boundary rules, and the landing order.

## 2. Exclusivity Mechanism

Platform exclusivity is enforced **structurally, not by runtime branching**.
No `instanceof`, no server-brand sniff, no feature flag keyed on adapter
identity.

    RTPWorld<World>                (rtp-api)
    ├── BukkitRTPWorld             (rtp-spigot-common)   ← pre-filter lives HERE
    │   └── PaperRTPWorld          (rtp-paper-*)         ← @Override getChunkAt — bypass
    └── FoliaRTPWorld              (rtp-folia-common)    ← parallel tree — never touched

* `PaperRTPWorld.getChunkAt` is a hard `@Override` that never delegates to
  `super`; it calls Paper's native `world.getChunkAtAsync(cx, cz)` directly.
  Paper is therefore immune by construction.
* `FoliaRTPWorld extends RTPWorld<World>` directly, not `BukkitRTPWorld`.
  Folia is therefore immune by construction.
* The pre-filter code lives in a dedicated package
  (`io.github.dailystruggle.rtp.spigot.anvil`) guarded by an ArchUnit rule so
  that a future contributor cannot import it from `rtp-paper`, `rtp-folia`,
  `rtp-core`, or `rtp-api`.

## 3. Verdict Contract (summary — see ADR-016 §3)

The pre-filter returns exactly one of three verdicts:

| Verdict | Meaning | Pipeline effect |
|---|---|---|
| `REJECT` | Region file is readable, format is supported, and at least one sampled block in the motion-blocking surface column is in the normalized unsafe set. | `getChunkAt` completes with `null`; candidate is rejected off-thread. No live chunk load. |
| `ACCEPT` | Region file is readable, format is supported, no unsafe blocks found in the sampled surface column. | `getChunkAt` proceeds with the existing live load path. The authoritative `chunk.isSafe(...)` re-check still runs. |
| `UNKNOWN` | Region file missing, custom generator present, `DataVersion` unsupported, chunk is currently loaded, decompression mode unsupported (e.g. LZ4 phase 1), or any I/O error. | `getChunkAt` proceeds with the existing live load path unchanged. |

Applicability gates (pre-filter runs only when all hold):

1. `SafetyKeys.anvilPrefilterEnabled` is `true` (default `true`, kill switch).
2. `world.isChunkLoaded(cx, cz)` is `false` (avoids stale disk vs. live-edit divergence — your directive).
3. `world.getGenerator() == null` (no custom `ChunkGenerator`, e.g. Iris).
4. Full cache miss — **both** `keptLocations` **and** `unkeptLocations` for the
   target region are authoritative for previously verified-safe candidates.
   `unkeptLocations` (the "Cold Queue" in `RegionQueueManager`) holds
   locations that have already passed the live `chunk.isSafe(...)` re-check;
   their chunks are simply no longer force-loaded. Re-running the Anvil
   pre-filter against a location already in either queue would be wasted
   I/O at best and a spurious `REJECT` at worst (the disk snapshot may lag
   a live edit that the authoritative live check already accepted). The
   pre-filter therefore runs only when the candidate is absent from both
   queues.

## 4. New Files

All new production code lives in:
`rtp-spigot/rtp-spigot-common/src/main/java/io/github/dailystruggle/rtp/spigot/anvil/`

| File | Purpose |
|---|---|
| `AnvilPrefilter.java` | Public entry point. `CompletableFuture<Verdict> probe(World world, int cx, int cz, Set<String> normalizedUnsafe)`. Dispatches to the async I/O pool. |
| `Verdict.java` | Sealed enum: `REJECT`, `ACCEPT`, `UNKNOWN`. |
| `RegionFileLocator.java` | Pure filesystem resolver: `Path regionFileFor(World, int cx, int cz)`. Handles nether (`DIM-1/region/`) and end (`DIM1/region/`) dimension layouts. |
| `AnvilReader.java` | Minimal MCA/NBT reader. Parses region-file header, decompresses the requested chunk (Deflate/GZip), parses just enough NBT to reach `sections[].block_states.palette` and `Heightmaps.MOTION_BLOCKING_NO_LEAVES`. Returns an `AnvilChunkView` or throws `UnsupportedAnvilFormatException`. |
| `AnvilChunkView.java` | Immutable record: `record AnvilChunkView(int dataVersion, PaletteSection[] sections, long[] motionBlockingNoLeaves)`. |
| `PaletteSection.java` | Immutable record for a single 16×16×16 subchunk palette view. |
| `DataVersionSupport.java` | Whitelist of supported `DataVersion` values. Unsupported → `UNKNOWN`. |
| `PaletteNormalizer.java` | Symmetric normalization. `minecraft:lava` → `LAVA` via `Material.matchMaterial(...).name()`; unknown IDs fall back to namespace-strip + upper-case. |
| `UnsupportedAnvilFormatException.java` | Signalling exception for format issues caught by `AnvilPrefilter` and converted to `UNKNOWN`. |

Test tree:
`rtp-spigot/rtp-spigot-common/src/test/java/io/github/dailystruggle/rtp/spigot/anvil/`

| File | Purpose |
|---|---|
| `AnvilPrefilterTest.java` | Covers: (a) surface lava → `REJECT`; (b) plains grass → `ACCEPT`; (c) unsupported `DataVersion` → `UNKNOWN`; (d) missing region file → `UNKNOWN`; (e) custom generator detected → short-circuit to `UNKNOWN`; (f) symmetric normalization (`minecraft:lava` palette vs. `LAVA` config entry). |
| `AnvilReaderTest.java` | Parser-level tests against the test fixtures. |
| `PaletteNormalizerTest.java` | Round-trip equivalence of `minecraft:lava` / `LAVA` / `MINECRAFT:LAVA` / `FABRIC_MOD:CUSTOM_LAVA`. |
| `AnvilTestFixtures.java` | **Generator** for `.mca` fixtures at test time (see §8 decision 2). Produces tiny synthetic region files into a JUnit `@TempDir`. |

## 5. Modified Files

| File | Change | Justification |
|---|---|---|
| `rtp-spigot/rtp-spigot-common/src/main/java/.../world/BukkitRTPWorld.java` | `getChunkAt(int,int)`: after the reflective `CHUNK_AT_ASYNC_FUTURE` branch and before the sync fallback, insert the four applicability gates + `AnvilPrefilter.probe(...)`. On `REJECT`, complete the returned future with `null`. On `ACCEPT` / `UNKNOWN`, continue to the existing load path unchanged. | Single integration point; mirrors the ADR-015 stale-chunk-guard insertion pattern. |
| `rtp-core/src/main/java/.../configuration/enums/SafetyKeys.java` | Add `anvilPrefilterEnabled` enum entry. | Kill switch per ADR-016; lives in core so adapters read it uniformly. |
| `rtp-spigot/rtp-spigot-common/src/main/resources/safety.yml` *(or adapter-local default)* | Add `anvilPrefilterEnabled: true` and a short comment referencing ADR-016. | Documented default. |
| `rtp-core/src/main/java/.../configuration/ConfigParser.java` *(or the specific loader for `SafetyKeys.unsafeBlocks`)* | At load time, pass each configured unsafe-block string through the normalizer hook (see §8 decision 1) and cache the normalized set alongside the raw set. | Required so palette reads and config reads compare in identical form. |
| `rtp-spigot/rtp-spigot-common/src/main/java/.../world/BukkitRTPChunk.java` | Update `isSafe(...)` to compare against the normalized set produced above (currently compares raw `Material.name()` only). | Eliminates the semantic drift the ADR called out. |
| `docs/adr/ADR-016-anvil-readonly-prefilter.md` | Flip `Status: Proposed` → `Accepted` with today's date when PR #3 lands. Add an "Implementation" references section pointing at the new package. | ADR lifecycle hygiene. |
| `docs/adr/README.md` | Update ADR-016 status column. | Index hygiene. |
| `docs/dev/TRACEABILITY.md` | Add REQ-RTP-S-005 rows for `AnvilPrefilter`, `AnvilReader`, `AnvilPrefilterTest`. | Per `.junie/AGENTS.md` self-updating protocol. |
| `docs/dev/DESIGN.md` | Add a short subsection documenting the pre-filter's thread placement (async I/O pool; never Region Thread; never tick thread). | Design, not requirements. |
| `.junie/AGENTS.md` | Update the *Already satisfied by* note under REQ-RTP-S-005 to mention the Anvil pre-filter as an additional off-thread mitigation on vanilla Spigot. | Per self-updating protocol. |
| `rtp-core/src/testFixtures/...` (ArchUnit host) | Add the three rules in §6. | Structural lock on exclusivity. |

### 5.1 Files explicitly NOT modified (by design)

* `rtp-paper/**/PaperRTPWorld.java` — Paper's native async path is the right
  answer; pre-filter would be pure overhead. Override remains untouched.
* `rtp-folia/**/FoliaRTPWorld.java` — Parallel hierarchy; structurally
  immune.
* `rtp-api/**` — No interface changes. The April 2026 cross-platform gap
  analysis in `.junie/AGENTS.md` confirmed the abstractions are sufficient;
  this is an adapter-internal optimisation.
* `rtp-core/**/LocationGenerator.java` — Pre-filter is hidden inside
  `BukkitRTPWorld.getChunkAt`'s returned future. Core sees only "the async
  load returned null → candidate rejected → spiral advances". No new core
  path, no new core branches.
* `rtp-fabric/**` — Out of scope per `REQUIREMENTS.md §0`. Fabric, if ever
  in scope, has its own region-file access through Mojang's `RegionFile`.

## 6. ArchUnit Rules

**Host module (shipped — Phase 1a):** `rtp-spigot-common` (`AnvilPackageBoundaryArchTest`).
ArchUnit was added to `rtp-spigot-common`'s `testImplementation` set alongside
MockBukkit. Two rules are currently enforced; a third originally proposed rule
(`paper_folia_core_do_not_import_anvil`) was dropped after review — see note below.

| Rule | Shipped | Assertion |
|---|---|---|
| `anvilPackageIsSpigotOnly` | ✅ Phase 1a | `classes().that().resideInAPackage("..spigot.anvil..").should().onlyHaveDependentClassesThat().resideInAnyPackage("..spigot.anvil..", "..spigot..")` |
| `anvilDoesNotDependOnBukkitChunk` | ✅ Phase 1a | `noClasses().that().resideInAPackage("..spigot.anvil..").should().dependOnClassesThat().haveFullyQualifiedName("org.bukkit.Chunk")` — the reader deliberately operates on unloaded chunks; importing `Chunk` would indicate a regression to the live path. |
| `paper_folia_core_do_not_import_anvil` | ⛔ Dropped | An ArchUnit rule hosted in `rtp-core` or `rtp-paper-common` would scan classpaths that don't contain `..spigot.anvil..` at all (rtp-core has no `rtp-spigot-common` dep; `rtp-paper-common` transitively does but never references anvil classes), so the rule would be vacuously satisfied and actively misleading. The direction is already enforced structurally — `rtp-paper`'s `PaperRTPWorld.getChunkAt` is a hard `@Override` that never delegates to `super`, `rtp-folia` extends `RTPWorld` directly, and `rtp-core` / `rtp-api` have no path to a `..spigot..` class at all. |

## 7. Traceability

| REQ | Verification |
|---|---|
| REQ-RTP-S-005 (no chunk loading on main thread) | `AnvilPrefilterTest`, `AnvilReaderTest`. The pre-filter removes the remaining tick-thread exposure for rejected candidates on vanilla Spigot. Live-load fallback retains existing S-005 compliance via the reflective async probe + primary-thread bounce. |
| REQ-RTP-S-004 (no silent discards) | `AnvilPrefilterTest` (c)–(d) verify that exceptions and unsupported formats log at `Level.WARNING` and fall through to the live path, never silently succeed or silently fail. |
| REQ-RTP-S-001 (no unsafe teleports) | The pre-filter can only *reject* candidates; it cannot *accept* in the authoritative sense. The live `BukkitRTPChunk.isSafe(...)` re-check remains the final arbiter. Covered by existing `LocationGenerator` tests. |

## 8. Resolved Decisions

Both architectural decisions below were resolved by the maintainer on
2026-04-18 and are recorded here for future reference. PR #1 may proceed.

### 8.1 `PaletteNormalizer` placement — **Resolved: (a) split normalization**

`rtp-core` MUST NOT depend on `rtp-spigot-common`, but `rtp-core` is where
`SafetyKeys.unsafeBlocks` is parsed.

* **✅ Chosen — (a) split normalization.** A pure string normalizer
  (namespace-strip + upper-case, zero Bukkit deps) lives in `rtp-api`. The
  Spigot adapter performs the `Material.matchMaterial(...)` reconciliation
  at plugin startup and pushes the reconciled set back into a core-visible
  cache. Preserves the architecture rule with no new accessor surface.
* ❌ Rejected — (b) accessor hook on `RTPServerAccessor`: unnecessary API
  surface for what is ultimately an adapter-internal optimisation.
* ❌ Rejected — (c) reflection against `org.bukkit.Material` inside
  `rtp-core`: violates the architecture rule.

Implementation note for PR #1: the `rtp-api` normalizer is the canonical
entry point; `PaletteNormalizer` in `io.github.dailystruggle.rtp.spigot.anvil`
becomes a thin Spigot-side reconciler that delegates the pure-string step
to the `rtp-api` helper and then layers `Material.matchMaterial(...)` on
top.

### 8.2 Test fixtures — **Resolved and SATISFIED 2026-04-18**

`.mca` fixtures are binary and noisy in git diffs.

* **✅ Chosen — hybrid.** A small set of real server-produced `r.0.0.mca`
  files (trimmed via a one-shot utility to a single chunk each, ≤16 KB)
  lives under `src/test/resources/anvil/real/<mc-version>/`, one per
  supported `DataVersion`. They anchor the parity gate. The generator
  (`AnvilTestFixtures`) then produces synthetic chunks in-memory for
  subsequent pre-filter unit tests without committing new binaries.
* ❌ Rejected — pure generator without real-data anchors: a silently
  wrong generator would mask a silently wrong reader.
* ❌ Rejected — committing full 32×32-chunk region files: opaque diffs
  and 8–10 MB repo-size hit per version.

**Parity-gate evidence (satisfied):** `AnvilFixtureParityTest` in
`rtp-spigot-common` covers three real fixtures at data versions
3465 (MC 1.20.4), 4671 (MC 1.21.5), and 4788 (MC 26.1):

1. `realFixtureDecodesAtExpectedDataVersion` — each real fixture parses
   through `AnvilReader.readChunk(...)`, produces the expected
   `DataVersion`, uses zlib compression, exposes a non-empty `sections`
   list and a non-null `Heightmaps.MOTION_BLOCKING_NO_LEAVES` long
   array. The `DataVersionSupport` whitelist is asserted to contain each
   observed version, so any future drift between whitelist and fixtures
   fails the test.
2. `realRootSurvivesSemanticRoundTrip` — each real root compound, after
   `Nbt.writeNamedRoot(...) → Nbt.readRootCompound(...)`, yields identical
   `DataVersion`, identical `MOTION_BLOCKING_NO_LEAVES` long array,
   identical section count, and identical root-key iteration order.
   Proves the codec is a true inverse on real server bytes.
3. `syntheticChunkRoundTripsThroughReader` — `AnvilTestFixtures`
   generates a synthetic chunk (three-entry palette, two sections,
   37-long heightmap), `AnvilReader` parses it back, and all fields
   match including palette order and heightmap contents.

Subsequent format-version additions repeat the gate by dropping a fresh
real `r.0.0.mca` under `src/test/resources/anvil/real/<newver>/` and
extending the parameterised test — no new infrastructure required.

## 9. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| MCA compression mode 4 (LZ4, Minecraft 1.20.2+) not in Java stdlib | Phase 1: treat LZ4 chunks as `UNKNOWN`; fall through to live load. Revisit once telemetry indicates high LZ4 miss rate. Adding `org.lz4:lz4-java` is a deferred dependency decision. |
| Anvil format changes across supported versions (1.20.x / 1.21.x / 26.1.x) | `DataVersionSupport` is an explicit whitelist. Unknown versions → `UNKNOWN`. New MC releases opt in by adding their `DataVersion` and a fixture-backed test. |
| Custom `ChunkGenerator` (Iris, datapacks) emits non-vanilla NBT | Applicability gate 3 (`world.getGenerator() == null`) short-circuits to `UNKNOWN` before we ever parse. |
| Modded namespace identifiers (Mohist/Arclight palettes) | `PaletteNormalizer` falls back to namespace-strip + upper-case. Unknown modded IDs are simply not in the unsafe set, so they are not `REJECT`ed — live `isSafe(...)` remains the source of truth. ADR-016 Decision §3 covers this. |
| Stale disk vs. live-edit divergence | Applicability gate 2 (`!world.isChunkLoaded(cx, cz)`) — if the chunk is loaded, the live state may differ from disk, so we skip the pre-filter. Your directive. |
| `world.getWorldFolder()` thread-safety on exotic forks | Documented as thread-safe on Bukkit. Defensive: `AnvilPrefilter.probe(...)` resolves the folder on the calling thread (which is already off-tick) before dispatching to the I/O pool. |

## 10. Landing Order

Each phase is a self-contained PR. Earlier phases must compile and test
green before the next begins.

| # | Phase | Content | Gate |
|---|---|---|---|
| 1a | Scaffolding (no fixtures) — **SHIPPED 2026-04-18** | `rtp-api` `PaletteIdentifierNormalizer` + `PaletteIdentifierNormalizerTest` (11 cases). `io.github.dailystruggle.rtp.spigot.anvil` package skeleton: `package-info`, `Verdict`, `UnsupportedAnvilFormatException`, `DataVersionSupport` (empty whitelist — populated in Phase 2), `PaletteNormalizer` (Spigot-side reconciler). `PaletteNormalizerTest` (MockBukkit, 8 cases). `AnvilPackageBoundaryArchTest` (2 ArchUnit rules). No wire-in. | ✅ `:rtp-api:test` green. ✅ `:rtp-spigot:rtp-spigot-common:test` green (10/10 including ArchUnit). ✅ `:rtp-core:test` `RTPArchitectureTest` unaffected (6/6). |
| 1b | Reader + fixtures — **SHIPPED 2026-04-18** | `Nbt` codec (13 tag types, Modified-UTF-8, read/write symmetric). `AnvilReader` (MCA header parser, zlib/gzip/uncompressed decompression, LZ4 → `UnsupportedAnvilFormatException`, root-compound accessors for `DataVersion` / `sections` / `Heightmaps`). `DataVersionSupport` whitelist populated with observed fixture versions {3465, 4671, 4788}. `AnvilTestFixtures` synthetic-chunk writer. `AnvilFixtureParityTest` (7 cases — 3 real-decode + 3 semantic round-trips + 1 synthetic). Three trimmed real `r.0.0.mca` fixtures (~44 KB total) committed under `src/test/resources/anvil/real/`. Still not wired in. | ✅ `:rtp-api:test` green (11/11). ✅ `:rtp-spigot:rtp-spigot-common:test` green (17/17 — 10 Phase-1a + 7 parity). ✅ `:rtp-core:RTPArchitectureTest` unaffected (6/6). ✅ §8.2 parity gate satisfied. |
| 2 | `AnvilChunkView` + palette semantics — **SHIPPED 2026-04-18** | `PaletteSection` / `AnvilChunkView` immutable records (raw identifier strings, YZX-major section layout). `PackedPaletteDecoder` for the Anvil 1.16+ no-cross-long packed layout (`bitsPerEntry = max(4, ceil(log2(size)))`, `entryIndex = (y<<8) \| (z<<4) \| x`). `AnvilReader.readChunkView(...)` + `toView(...)` lift raw NBT into the typed view; malformed sections silently skipped per ADR-016 "never crash" posture. `AnvilTestFixtures.section(byte, palette, data)` overload + `packIndices(bits, flat[4096])` helper. `AnvilChunkViewTest` 10 cases (unit: bitsPerEntry table, entryIndex YZX ordering, single-entry-palette guard; synthetic: 2-entry placement at targeted coords, 17-entry bits=5 placement, single-entry palette stone fill, out-of-range Y → null; real: three parameterised fixture sanity decodes). Still not wired in. | ✅ `:rtp-spigot:rtp-spigot-common:test` green (27/27 — 17 Phase-1 + 10 Phase-2). ✅ `:rtp-api:test` green (11/11). ✅ ArchUnit `anvilDoesNotDependOnBukkitChunk` still holds — no record imports `org.bukkit.Chunk`. |
| 3 | Wire-in | `AnvilPrefilter`, `BukkitRTPWorld.getChunkAt` insertion, `SafetyKeys.anvilPrefilterEnabled`, `safety.yml` default, `BukkitRTPChunk.isSafe` normalization update, `AnvilPrefilterTest`. ADR-016 → Accepted. `TRACEABILITY.md`, `.junie/AGENTS.md`, `DESIGN.md` updated. | `:rtp-spigot:*`, `:rtp-paper:*` (sanity), `:rtp-folia:*` (sanity), `:rtp-core:*` green. |
| 4 | Hardening *(optional)* | `DataVersion` whitelist expansion, LZ4 support (adds `org.lz4:lz4-java` dependency — separate decision), telemetry counters for `REJECT` / `ACCEPT` / `UNKNOWN` rates. | Post-acceptance based on real-world data. |

## 11. Out of Scope

* **Biome pre-filter.** Phase 1 only pre-filters block-level unsafe hits.
  The biome-string filter keeps using the live path. Revisit as a follow-up
  once the block path is proven in production.
* **Paper / Folia integration.** Native async paths already satisfy the
  motivation on those forks. ArchUnit enforces this.
* **Fabric.** Out of scope per `REQUIREMENTS.md §0` and `MULTI_PLATFORM_PLAN.md`.
* **Using the pre-filter as a teleport source-of-truth.** Pre-filter output
  is advisory only; the live `chunk.isSafe(...)` re-check is the sole
  authoritative verdict. ADR-016 Decision §4.

## 12. References

* `docs/adr/ADR-016-anvil-readonly-prefilter.md` — authoritative decision.
* `docs/adr/ADR-015-stale-chunk-guard-countbound-pipes.md` — related stale-chunk protection; shares the `isChunkLoaded` gate.
* `docs/dev/REQUIREMENTS.md §3` — REQ-RTP-S-001, S-004, S-005.
* `docs/dev/ARCHITECTURE.md` — module boundary rules enforced by §6 ArchUnit rules.
* `docs/dev/MULTI_PLATFORM_PLAN.md` — cross-platform roadmap context.
* `.junie/AGENTS.md` — self-updating protocol for TRACEABILITY / AGENTS notes under §5.
