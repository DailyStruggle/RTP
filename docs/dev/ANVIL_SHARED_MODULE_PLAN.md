# Anvil Shared Module Extraction Plan

**Status:** Shipped 2026-04-18 (ADR-016 accepted; all 7 phases landed in a single session)
**Owner:** TBD
**Drives ADR:** [ADR-016](../adr/ADR-016-anvil-shared-module.md) (Accepted 2026-04-18)
**Supersedes / amends:** `rtp-spigot/rtp-spigot-common`'s exclusive ownership of `io.github.dailystruggle.rtp.spigot.anvil.*` (introduced under ADR-016).
**Created:** 2026-04-18

---

## 1. Motivation

The Anvil decode stack currently lives in `rtp-spigot-common` for historical reasons (Spigot was the first platform that needed off-tick safety evaluation per ADR-016). Two upcoming workstreams will demand the same code on other platforms:

- **Folia:** apply the source-union pattern to bypass region-thread affinity for selection-phase block reads in `FoliaRTPWorld` / `FoliaRTPChunk`. See ADR-016 §11 for the decision, orchestration, and alternatives.
- **Fabric (later, gated on `MULTI_PLATFORM_PLAN.md` Phase 2 unblock):** identical decode requirements; `MinecraftServer#getWorldPath(LevelResource.ROOT)` supplies the world folder.

Without extraction, each platform port duplicates ~12 source files plus tests. With extraction, each port writes only the platform-glue (`*RTPChunk` source-union, `*RTPWorld#getChunkAt` probe-then-fall-through, world-folder lookup).

The current Spigot adapter is the canonical implementation. Extraction is a pure module move — **no semantic, contract, or behavioural changes**.

---

## 2. Scope

### 2.1 In scope (move into new `rtp-anvil` module)

Production sources currently under `rtp-spigot/rtp-spigot-common/src/main/java/io/github/dailystruggle/rtp/spigot/anvil/`:

- `AnvilReader.java`
- `AnvilChunkView.java`
- `AnvilPrefilter.java`
- `AnvilPrefilterMetrics.java`
- `DataVersionSupport.java`
- `Nbt.java`
- `PackedPaletteDecoder.java`
- `PaletteNormalizer.java`
- `PaletteSection.java`
- `UnsupportedAnvilFormatException.java`
- `Verdict.java` (retained for telemetry; advisory only per ADR-016)
- `package-info.java` (rewritten — see §6)

Tests currently under `rtp-spigot/rtp-spigot-common/src/test/java/io/github/dailystruggle/rtp/spigot/anvil/`:

- `AnvilChunkViewTest.java`
- `AnvilFixtureParityTest.java`
- `AnvilPackageBoundaryArchTest.java` — re-scoped (see §6)
- `AnvilPrefilterTest.java` (includes the `adr018_rejectRetainsView` regression guard)
- `AnvilTestFixtures.java`
- `PaletteNormalizerTest.java`

Test fixture binaries (under `rtp-spigot-common/src/test/resources/anvil/` if present) move with the tests.

### 2.2 Out of scope (stays in platform adapters)

- `BukkitRTPChunk` source-union dispatch (Bukkit `Chunk` import — platform-specific).
- `BukkitRTPWorld#getChunkAt` probe-then-fall-through orchestration (Bukkit `World#getWorldFolder()` — platform-specific).
- `BukkitRTPWorld#anvilCache` — the cache lifecycle is tied to `cacheChunk`, which is Bukkit-typed.
- `/rtp test anvil-prefilter` command — lives in `rtp-plugin` (Bukkit-family); after extraction it pulls metrics snapshots from the shared module but stays in the plugin command tree.

### 2.3 Non-goals

- No DataVersion table changes.
- No new public API on the moved classes.
- No Folia or Fabric integration code in this extraction (Folia is tracked under ADR-016 §11; Fabric is out of scope).
- No `AnvilPrefilterMetrics` semantic changes.

---

## 3. Target Module Layout

New top-level module, peer to `commands-api` and `effects-api`:

```
rtp-anvil/
├── build.gradle
└── src/
    ├── main/
    │   └── java/io/github/dailystruggle/rtp/anvil/
    │       ├── AnvilChunkView.java
    │       ├── AnvilPrefilter.java
    │       ├── AnvilPrefilterMetrics.java
    │       ├── AnvilReader.java
    │       ├── DataVersionSupport.java
    │       ├── Nbt.java
    │       ├── PackedPaletteDecoder.java
    │       ├── PaletteNormalizer.java
    │       ├── PaletteSection.java
    │       ├── UnsupportedAnvilFormatException.java
    │       ├── Verdict.java
    │       └── package-info.java
    └── test/
        └── java/io/github/dailystruggle/rtp/anvil/
            ├── AnvilChunkViewTest.java
            ├── AnvilFixtureParityTest.java
            ├── AnvilPackageBoundaryArchTest.java
            ├── AnvilPrefilterTest.java
            ├── AnvilTestFixtures.java
            └── PaletteNormalizerTest.java
```

Package rename: `io.github.dailystruggle.rtp.spigot.anvil` → `io.github.dailystruggle.rtp.anvil`.

---

## 4. Dependency Graph After Extraction

```
                             ┌──────────────┐
                             │  rtp-anvil   │  (depends on JDK only;
                             │              │   optional SLF4J for warnings)
                             └──────▲───────┘
                                    │
            ┌───────────────────────┼─────────────────────┐
            │                       │                     │
   rtp-spigot-common      rtp-folia-common*      rtp-fabric*
            │                       │                     │
   (Spigot adapter)       (ADR-016 §11, future) (Fabric, future)
```

`*` Folia and Fabric edges are added by the Folia integration work (ADR-016 §11) and the future Fabric integration work respectively; this extraction only establishes the edge from the Spigot adapter.

**Invariants:**

- `rtp-anvil` imports nothing from `rtp-api`, `rtp-core`, `commands-api`, `effects-api`, or any platform module.
- `rtp-anvil` imports nothing from `org.bukkit.*`, `io.papermc.*`, `net.minecraft.*`, or `net.fabricmc.*`.
- `rtp-anvil` exposes only platform-neutral types: `byte[]`, `Path`, `Optional`, primitives, and its own decode types.

---

## 5. Sequenced Work Items

### Phase 1 — Module scaffolding (no code moves)

- [ ] Add `include 'rtp-anvil'` to `settings.gradle` (immediately above `rtp-core`, since core is further up the consumer chain than spigot is).
- [ ] Create `rtp-anvil/build.gradle` mirroring `commands-api/build.gradle`'s shape (Java 21 toolchain, no plugin platform deps, JUnit 5 testRuntimeOnly).
- [ ] Add `rtp-anvil/.gitignore` if `commands-api` has one.
- [ ] Build the empty module — `.\gradlew :rtp-anvil:build` — to confirm the scaffolding compiles before any source moves.

### Phase 2 — Source move (mechanical, no behaviour change)

- [ ] Copy 12 production sources from `rtp-spigot-common` into `rtp-anvil/src/main/java/io/github/dailystruggle/rtp/anvil/`, rewriting the package declaration on each.
- [ ] Copy 6 test sources into `rtp-anvil/src/test/java/io/github/dailystruggle/rtp/anvil/`, rewriting the package declaration on each.
- [ ] Move test resource fixtures (if any) to `rtp-anvil/src/test/resources/`.
- [ ] Delete the old files from `rtp-spigot-common`.
- [ ] Rewrite `package-info.java` to reflect the new platform-neutral scope (drop the "Spigot-exclusive" wording introduced under ADR-016 §package-info; cite ADR-016 instead).

### Phase 3 — Consumer rewiring

- [ ] Add `implementation project(':rtp-anvil')` to `rtp-spigot-common/build.gradle`.
- [ ] Update imports in:
    - `BukkitRTPWorld.java`
    - `BukkitRTPChunk.java`
    - any other Bukkit-family file that referenced the old package (full sweep via `search_project "spigot.anvil"`).
- [ ] If `/rtp test anvil-prefilter` (in `rtp-plugin`) imports `AnvilPrefilterMetrics` directly, update its import.

### Phase 4 — ArchUnit guard

- [ ] Re-scope `AnvilPackageBoundaryArchTest`:
    - Old: enforced "no class outside `rtp-spigot.anvil` may import that package".
    - New: enforced inside `rtp-anvil` itself: "no class in `io.github.dailystruggle.rtp.anvil` may import `org.bukkit.*`, `io.papermc.*`, `net.minecraft.*`, `net.fabricmc.*`, `io.github.dailystruggle.rtp.api.*`, `io.github.dailystruggle.rtp.common.*`, or any other RTP-internal package."
- [ ] Add a complementary ArchUnit test (in whichever module owns the existing arch suite — likely `rtp-core` or a dedicated `arch-tests` module) asserting "any platform module that imports `rtp.anvil.*` must also resolve to a `RTPChunk`/`RTPWorld` source-union; no direct `LocationGenerator` import is allowed." (Optional; flag for follow-up if it exceeds the extraction's blast radius.)

### Phase 5 — Validation

- [ ] `.\gradlew :rtp-anvil:test` — all 6 tests green.
- [ ] `.\gradlew :rtp-spigot:rtp-spigot-common:test` — full pass; specifically `AnvilPrefilterTest` (in its new home) and any Bukkit-side test that exercised the anvil package boundary.
- [ ] `.\gradlew :rtp-core:test` — full pass; `ReqRtpS004NullChunkAttributionTest` continues to pass (no direct dependency on the anvil package, but a sanity check that `BukkitRTPWorld` rewiring didn't regress the null-key attribution path).
- [ ] `.\gradlew build` (full repo) — confirms no other module accidentally referenced the old package.
- [ ] Diff scan: `Get-ChildItem -Recurse -Filter "*.java" | Select-String "io.github.dailystruggle.rtp.spigot.anvil"` must return zero hits.

### Phase 6 — Documentation

- [ ] Author `docs/adr/ADR-016-anvil-shared-module.md` (use `docs/adr/ADR-TEMPLATE.md`). Decision: extract; rationale: pre-position for Folia/Fabric ports without code duplication; alternatives: keep in `rtp-spigot-common` and add reverse Gradle deps from Folia/Fabric (rejected as architecturally inverted).
- [ ] Update `docs/adr/README.md` index.
- [ ] Update `docs/dev/ARCHITECTURE.md`: add `rtp-anvil` to the module list, with the "no platform imports" invariant.
- [ ] Update `docs/dev/ANVIL_PREFILTER_PLAN.md §10`: add a new Phase 6 entry citing the move and ADR-016.
- [ ] Update `.junie/AGENTS.md`:
    - "Architecture Boundaries" decision order: insert a new tier between (3) `commands-api` / `effects-api` and (4) platform adapters: "**`rtp-anvil`** — Vanilla Anvil region-file decode. No platform imports. Consumed by any adapter that wants off-tick or off-region safety evaluation."
    - "Module-level requirement files" list: add `rtp-anvil/REQUIREMENTS.md`.
    - REQ-RTP-S-005 "Already satisfied by" note: update the file paths (`AnvilPrefilter` etc. now live under `io.github.dailystruggle.rtp.anvil`).
- [ ] Author `rtp-anvil/REQUIREMENTS.md` — short doc covering: DataVersion compatibility policy, the "advisory only — never authoritative" invariant inherited from ADR-016 §4 / ADR-016, the "no platform imports" rule, and the regression-test catalogue.
- [ ] Update `docs/dev/TRACEABILITY.md`: rewrite the REQ-RTP-S-005 row to point at the new test FQNs (`io.github.dailystruggle.rtp.anvil.AnvilPrefilterTest#adr018_rejectRetainsView`, etc.).

### Phase 7 — Submit

- [ ] Single commit (or PR) that contains the entire extraction, ADR-016, and doc updates. No partial landings — the ArchUnit re-scope and the source move must ship together to keep `master` green.

---

## 6. Risks & Mitigations

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Broken downstream addon imports (`addons/RTP_Iris_integration` etc.) | Low — addons depend on `rtp-api` only | Phase 5 full-repo build catches it. |
| Test fixture binary path drift | Medium | Move fixtures with tests in Phase 2; Phase 5 runs the full `:rtp-anvil:test` suite which exercises every fixture. |
| `AnvilPackageBoundaryArchTest` semantics inversion | Low | Phase 4 explicitly re-scopes it; reviewer must confirm the new direction matches §4 invariants. |
| Gradle module-cycle if `rtp-anvil` accidentally imports an RTP package | Low | The "no internal RTP imports" rule in Phase 4 catches it at test time. |
| Stale `package-info.java` advice ("Spigot-exclusive") leaks into the new module | Medium | Phase 2 explicitly rewrites `package-info.java`; reviewer must read the diff. |
| ADR-016 references to `io.github.dailystruggle.rtp.spigot.anvil` go stale | Medium | Phase 6 sweeps `docs/adr/ADR-016*`, `ADR-016*`, `ADR-016*` for the old FQN. |
| Downstream `/rtp test anvil-prefilter` formatter breaks on metrics enum rename | Very low (no enum rename planned) | Out of scope; defer to ADR-016 implementation review. |

---

## 7. Acceptance Criteria

The extraction is complete when **all** of the following hold:

1. `rtp-anvil` exists as a top-level Gradle module with the source layout in §3.
2. `rtp-spigot-common`'s `io.github.dailystruggle.rtp.spigot.anvil` package is empty (or deleted).
3. `BukkitRTPWorld` and `BukkitRTPChunk` import the moved types from `io.github.dailystruggle.rtp.anvil`.
4. `Get-ChildItem -Recurse -Filter "*.java" | Select-String "io.github.dailystruggle.rtp.spigot.anvil"` returns zero hits.
5. `.\gradlew :rtp-anvil:test :rtp-spigot:rtp-spigot-common:test :rtp-core:test build` is green end-to-end.
6. The ArchUnit boundary test (re-scoped per Phase 4) is green and asserts on the new direction.
7. ADR-016 is authored, indexed, and referenced from `ARCHITECTURE.md`, `AGENTS.md`, `ANVIL_PREFILTER_PLAN.md`, and `TRACEABILITY.md`.
8. `rtp-anvil/REQUIREMENTS.md` exists and is referenced from the Required Reading table in `AGENTS.md`.

---

## 8. What This Plan Deliberately Does Not Do

- **Does not port to Folia.** That's tracked under ADR-016 §11. This extraction is the prerequisite, not the implementation.
- **Does not touch `rtp-fabric`.** That module has open Phase 1 / Phase 2 issues per `MULTI_PLATFORM_PLAN.md`; pre-positioning the dependency edge is a future workstream.
- **Does not change the `Verdict` enum or `AnvilPrefilterMetrics` counters.** Their advisory-only semantics (ADR-016 §3) are unchanged.
- **Does not introduce new public API.** Anything that wasn't `public` before stays at its current visibility.
- **Does not change the DataVersion compatibility table.** Whatever 1.20.x / 1.21.x support exists today is preserved bit-for-bit.

---

## 9. Sign-off Checklist (for the implementer)

- [ ] Plan reviewed and approved by project owner.
- [ ] ADR-016 drafted and approved before Phase 2 begins (ADR-first per `.junie/AGENTS.md`).
- [ ] Branch cut from `master` after a clean baseline build.
- [ ] All Phase 5 builds green locally.
- [ ] ArchUnit re-scope reviewed by project owner.
- [ ] Single PR submitted with extraction + ADR-016 + doc sweep.
