# Potential Bugs Backlog

A queue of incidental discoveries — suspected bugs, latent races, missing validations, stale comments — that were spotted while working on an **unrelated** task and deliberately **not** fixed in-line, per the *Stay-On-Task Policy* in [`.junie/AGENTS.md`](../../.junie/AGENTS.md).

This file is a backlog, not a tracker. Promote an entry to a Linear issue (`RTP-<n>`, see *Issue Tracking (Linear)* in [`.junie/AGENTS.md`](../../.junie/AGENTS.md)) or fold it into a future task's `Effective Issue` when it is ready to be worked on. Once filed, the entry keeps its technical detail and gains a `**Linear:**` line; Linear owns its status from then on. Once an entry is resolved, **delete it** — this file does not maintain a resolved-bug archive.

## What this file is — and is not

**Yes:** "I was doing X, I noticed Y looks broken, Y is *not* part of X, and I am walking away from Y. Recording it here so a future task can pick it up."

**No** — do not use this file for any of:

- Work you are doing or just finished as part of the current task. Use the `<UPDATE>` checklist, the `submit` summary, the commit message, and `CHANGELOG.md` for user-visible changes.
- A diary of your own fix attempts, build outputs, packaging chains, or per-session follow-ups. If you opened the entry and resolved it in the same session, **delete the entry** — it never belonged here. Do not annotate it with `**Resolved:**` / `**Follow-up:**` bullets.
- Durable engineering lore or repro recipes → [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md).
- Roadmap items or deferred design → the relevant plan doc or an ADR.
- Session resumption state → your `<UPDATE>` checklist or `docs/dev/scratch/CHECKLIST-<slug>.md`.
- Test failures or CI noise from the current change → fix them or escalate; not here.

A correct entry describes **someone else's future problem** that the current task is choosing not to solve. If you catch yourself writing a multi-paragraph resolution log on an entry you authored this session, that is the misuse signature — remove the entry instead.

## How to add an entry

Append to the *Open* section below using the template. Keep entries short — one paragraph each. If a deeper analysis is warranted, link to a separate doc rather than inlining it here.

Entries in the *Open* section are ordered by **priority** (highest first): runtime crashes and safety/thread-safety hazards first, then correctness/maintainability and operator-facing config issues, then performance, and finally cosmetic / log-noise / test-noise findings. When adding a new entry, insert it at the position matching its severity rather than strictly by date.

### Template

```markdown
### YYYY-MM-DD — <short title>

- **Severity:** Critical | High | Medium | Low | Cosmetic
- **Status:** Open | Triaged | Under Investigation | In Progress | Blocked
- **Discovered during:** <issue ref / short task description>
- **Location:** `<path/to/File.java>` line <N> (or symbol name)
- **Symptom / hypothesis:** <one or two sentences>
- **Impact:** <user-visible effect, best guess>
- **Suggested next step:** <minimal investigation or fix sketch>
- **Linear:** RTP-<n> (add only once filed; omit before)
```

## Open

### 2026-10-21 - NeoForge 1.21.1 watchdog crash: main thread stuck on a vanilla chunk load while RTP fill thrashes the heap

- **Severity:** High
- **Status:** Open
- **Discovered during:** live bot check of RTP-78 effects on a standalone NeoForge 1.21.1 container (podman, `MEMORY=6G`, seed 8675309, `teleportDelay` 4)
- **Location:** NeoForge background cache fill (`RTP-NeoForge-Async-*` workers); crash report `ServerHangWatchdog` stack: `ServerPlayer.doTick` -> `Entity.updateFluidOnEyes` -> `Level.getChunkAt` -> `ServerChunkCache.getChunk` -> `managedBlock`
- **Symptom / hypothesis:** After `/rtp reload` and a restart, the log alternated `Heap usage 85-95% ... pausing` / `Heap pressure subsided` every few seconds, the container sat at ~640% CPU, a bot login hung, and the watchdog killed the server after `MAX_TICK_TIME` 180 s. The main thread was waiting on a vanilla load of the player's own chunk, so the chunk workers were starved; RTP's fill (or the GC load it causes) is the likely starver. Not reproduced on the first boot with 4G; not yet compared against the `HEAD` jar.
- **Impact:** A NeoForge server can hang and crash with no player action beyond joining.
- **Vanilla reproduction without RTP (2026-10-21, same world, all mods removed):** `forceload add` on random chunks within +-10,000 blocks, sent one at a time over RCON, does the same thing. 105 of 130 forceloads went in before one `forceload add` held the main thread in `ServerChunkCache.getChunk` -> `managedBlock` for 180 s, and the watchdog crashed the server (`crash-2026-10-21_20.54.42`, 22 of 23 `Worker-Main` threads parked, no mod frames). In a second copy, 40 forceloads then a bot join gave a 60.8 s ("1215 ticks behind") join tick; the bot stayed connected. So the hang is vanilla 1.21.1 behaviour once roughly 40-100 ticketed chunks in fresh terrain are queued; RTP's part is how many tickets its fill holds, which is the budget to fix.
- **Attribution (2026-10-21, same world copied twice, bot joins 60 s after start):** with RTP removed the join costs one 2 s "Can't keep up" and the bot stays connected; with RTP (including the generation budget and stuck-tick pause added that day) the join tick runs 129 s and the bot times out. RTP-driven chunk writes in the first two minutes: about 14,000 with RTP, about 600 without. The budget counts only the centre chunk future of each RTP load, and the check lags the load (8 promotions pass one pulse's check before the async probe reaches the counter). Likely uncounted sources: the restart re-pin of saved kept locations and the generation cascade each RTP ticket causes around its chunk (`RTP_TICKET_DISTANCE` 1, so about radius 11 of partial generation per ticket).
- **Partial fix (2026-10-22, in the working tree):** `LoginCacheTask` promotions and the `RegionQueueManager.validateTickets` re-pin now take a `GenerationBudget` slot before they dispatch a load. The re-pin runs as one sweep that resumes through `RTP.scheduler` when slots free up. On NeoForge, `TicketGenerationWatch` counts each ticket's non-resident footprint until it is resident, cancelled or past its TTL.
- **A/B rerun (2026-10-22, `neoforge-ctrl` world copied per run, 6G, bot joins 60 s after boot and stays 150 s):** no RTP: bot spawns at 1.0 s, no stall. Jar from 2026-10-21 15:37: bot never spawns (socket closed at 31 s), 180 s tick, watchdog crash (`crash-2026-10-22_15.57.12`). Current build, two runs: bot spawns at 12.9 s and 13.5 s and stays connected, worst ticks 12.5 s and 13.0 s, no crash. RTP's tick-stuck / unhealthy pauses fired and generation resumed once the tick recovered. Chunk writes over about 3.5 minutes were 27,859 and 18,001 with the current build and 810 without RTP; the old jar's 1,945 is not comparable because it crashed before its final save. Harness: `scripts/tmp/livecheck/ab.sh` (gitignored).
- **Suggested next step:** The crash is gone, but the join still stalls about 13 s where vanilla stalls 0 s. Find what fills the chunk workers in the first minute: count ticket applications and generation-cascade chunks against the budget during the A/B, and try a lower budget cap or a later fill start after boot on hold-back hosts. Clears when a rerun shows a join tick comparable to the no-RTP baseline.
- **Linear:** RTP-80

### 2026-10-08 — Probe governor's probe time includes coalescer queue wait

- **Severity:** Medium
- **Status:** In Progress
- **Discovered during:** stress run `20261008-170548` (`[RTP][probe-gov]` lines: `Tp = 10.2 ms` vs `Tf|direct = 8.5 ms` at `p = 0.073`)
- **Location:** `rtp-core/.../selection/region/ProbeFirstGovernor.java` (sample recording) and the probe call sites in `PregenTask` / `QueueTask`
- **Symptom / hypothesis:** `Tp` is measured from dispatch to result, so a probe parked in the per-region-file coalescer counts idle wait as cost. In skip mode the 1-in-32 trial probes rarely join a group and each pays a full open, so the losing option is sampled at its worst (ADR-109 measurement section).
- **Impact:** The fill path can settle on skipping the probe for the wrong reason on fresh worlds, where same-file grouping is worth the most.
- **Suggested next step:** Fix pending bench verification: probes carry their drain's per-chunk share and `groupSize` (`ColumnProbe` / `ChunkColumnProbe.groupSize()`); `ProbeFirstGovernor` fits `Tp(g) = perChunk + open/g` and evaluates it at the default-probing group size; SKIP-mode trials run up to 8 candidates of one region file (`binKey`); `ScanTask` completion resets the region's governors (reload already did). Covered by `ProbeFirstGovernorTest.groupedTrialsJudgedAtDefaultGroupSize`. Remove this entry once a fresh-world stress run shows `[RTP][probe-gov]` `Tp` near the PROBE-mode value while in SKIP (and a flip back where `p` warrants it).

### 2026-10-08 — StressTestRTP spark thread selector matches no thread; saved profiles are empty

- **Severity:** Medium
- **Status:** In Progress
- **Discovered during:** Linux A/B stress runs `20261009-005522` and `20261009-010359` (spark async mode, "save complete" logged)
- **Location:** `helpers/StressTestRTP/.../SparkHook.java` `buildStartCommand` (~175-178); default `spark.threads: "Server thread,RTP-Anvil-IO"` in `config.yml`
- **Symptom / hypothesis:** The comma list is appended as a single `--thread Server thread,RTP-Anvil-IO`. Spark re-joins space-split tokens (the space is harmless) but takes the whole comma list as one name, so no thread matches. The `.sparkprofile` holds only server metadata (config, plugins, datapacks) and no thread samples. The pool threads are named `RTP-Anvil-IO-<n>`, so an exact name would not match them either.
- **Impact:** Every profiled run since the selector default was added has produced no samples; `thread_cpu_top` in the summary is empty. Runs remain valid as unprofiled benchmarks.
- **Suggested next step:** Fix pending bench verification: `buildStartCommand` emits one `--thread` per entry (unquoted; quotes would become part of the name) and adds `--regex` when an entry has a `*` glob, since spark resolves exact names to thread IDs once at start and would miss pool threads spawned later. Default is now `Server thread,RTP-Anvil-IO-*`; covered by `SparkHookTest`. Remove this entry once a saved profile shows non-zero thread nodes for both threads.

### 2026-10-06 — ACCUMULATE repeats used chunks; landing spacing loosens on dense learned bad area

- **Severity:** Medium
- **Status:** Triaged
- **Discovered during:** cross-plugin destination chart for the Paper 26.2 stress run `20261006-135938`
- **Location:** `MemoryShape.resolve` (ACCUMULATE good-index remap; uniquePlacements gated on `expand`); `AbstractDualLayerShape.selectL3Candidate` / `keyForCounter` (BINNED_AMORTIZED)
- **Symptom / hypothesis:** In teleport order, 79 of LeafRTP's 4,096 landings fell within 256 blocks of one of the previous 64 (35 by the same player), against about 69 for a random scatter. Modelled by `ConsecutiveLandingSpacingSimTest` on the shipped default circle (P=32, S=1024 now that the stride cap is the full bin, ADR-088). The raw draw order keeps spacing: no lane holds two keys under 256 blocks apart, and there are 0 back-to-back pairs under 256 blocks per 4,096 draws (uniform 0.88). Per 4,096 landings with ~40% synthetic bad terrain (uniform: back-to-back 1.13, 64-lookback 64.1, repeats 3.94):
  - **Spacing holds under rejection and cache mixing:** cold-only ACCUMULATE gives back-to-back 0.06 and 64-lookback 50.0; FAST/COLD interleaving gives 0.06 and 54.4. The remap offset between two nearby picks is only the rejections learned between them, a few Hilbert steps, so the P-chunk lattice is nudged, not lost; the exact stride residue (0.5% kept) is stricter than spacing. FAST and COLD share one `rand()` stream, so mixing adds no repeats (3.56 against 4.44 cold-only). The 64-lookback metric is not a lane promise: windows of 16-64 draws change lane.
  - **Repeats under ACCUMULATE (known limit):** every merged rejection renumbers good indices, so a used chunk can reappear at an undrawn index; with `expand: false` landings are never marked, so repeats sit at the uniform rate (4.44 vs 3.94, about 0.1% of landings). REROLL never renumbers. Marking landings is gated on `expand` on purpose: without expand the range never grows back, so uniquePlacements marks drain the good domain until search tasks pile up without finding a location (`MemoryShape.resolve`).
  - **Dense learned bad area (open, not simulated):** where scan or pregen has learned large bad regions, the offset difference between nearby picks approaches a full bin (1,024) and in-bin spacing falls back toward uniform.
  - **BINNED_AMORTIZED (open):** this non-default backlog runs its own counter, stride rule (capped at 256, no phase windows) and per-draw phase, so it collides with the main ordering and comes out worse than uniform (1.19x).
  - No model reproduces the stress run's excess over uniform (79 vs ~69); real terrain clustering is the likely cause.
- **Impact:** With `expand: false`, consecutive players can land on a chunk an earlier player used, about as often as with random picks. Back-to-back spacing holds on typical terrain and loosens where learned bad area is dense. Disclosed in `docs/admin/configuration/REGIONS.md` (*Spacing, repeats and worst case by mode*); FRONT_PAGE.md links there.
- **Suggested next step:** Keep the repeat limit documented; both obvious fixes are ruled out. Physical-order lanes need per-lane occupancy state instead of run tables (the encoding that failed earlier performance tests, and it breaks C8), and marking landings without `expand` exhausts the region. Any future fix must consume no good area and keep the run encoding. Route BINNED_AMORTIZED through the main counter and phase windows. Flip the `dups > 0` pin in `ConsecutiveLandingSpacingSimTest.terrainRejectionKeepsBackToBackSpacing` only with such a fix.
- **Linear:** RTP-2

### 2026-10-06 — Weekly Jazzer job fuzzes one Anvil target for 2 s and passes

- **Severity:** Medium
- **Status:** In Progress
- **Discovered during:** v3.3.0 pre-release audit (Jazzer fuzz targets)
- **Location:** `.github/workflows/fuzzing.yml`; `api/anvil-api/src/test/.../AnvilRegionFuzzTest.java` and its `AnvilRegionFuzzTestInputs/` seeds; root `build.gradle` `tasks.withType(Test)`
- **Symptom / hypothesis:** Jazzer 0.30.0 fuzzes one `@FuzzTest` per run, the old workflow set a property the forked test JVM never saw, and a cached `:anvil-api:test` result could skip fuzzing entirely.
- **Impact:** The fuzzing badge gave false assurance until the workflow ran each target for its configured duration.
- **Suggested next step:** Fix pending the first scheduled CI run: the workflow runs one job per `@FuzzTest` method with `JAZZER_MAX_DURATION`, the root `Test` config declares the Jazzer env vars as inputs and disables up-to-date checks in fuzz mode, and `fuzzReadChunkView` / `fuzzReadColumnProbe` carry real-region seeds (local 15 s runs of both passed). Remaining: seeds for the LZ4 and NBT targets (needs extracted payloads); crash reproducers go under `<Class>Inputs/`. Remove this entry once a CI run shows all five jobs fuzzing for the set duration.
- **Linear:** RTP-21

### 2026-10-18 - `effectsapi.bukkit` and `HandleRegistry` remain in `api/effects-api` pending carrier relocation (`ADR-116` Decisions 2 & 4)

- **Severity:** Medium
- **Status:** In Progress
- **Discovered during:** review of the claim that `TITLE` is only wired on Fabric and NeoForge
- **Location:** `api/effects-api/src/main/java/io/github/dailystruggle/effectsapi/bukkit/`; `api/effects-api/src/main/java/io/github/dailystruggle/effectsapi/common/spi/HandleRegistry.java`; `api/effects-api/build.gradle` (`spigot-api` dependency)
- **Symptom / hypothesis:** `api/effects-api` now depends on `api/server-api`, `EffectFactory.registerBuiltInEffects(...)` registers the full 11-effect canonical key set once across Bukkit, Fabric (obf + 26.x unobf), and NeoForge, `NeoForgeHandles` implements `sendTitle`, `playNote`, and `spawnFirework` with `EffectFactory.reportUnsupportedPrimitive` diagnostics, and `EffectRegistrationParityTest` + `EffectsApiCommonNoPlatformImportsTest` guard cross-platform parity and `effectsapi.common` package boundaries ([ADR-116](../adr/ADR-116-effects-through-neutral-server-accessor.md), implemented 2026-10-24). The remaining slice of `ADR-116` Decisions 2 and 4 is relocating `io.github.dailystruggle.effectsapi.bukkit` (`BukkitHandles`, Bukkit listeners, `/effectsapi` Bukkit commands) to the Bukkit carrier, removing `spigot-api` from `api/effects-api/build.gradle`, and retiring `HandleRegistry` / `PlayerHandle` in favor of direct `ServerAccessor` primitive methods.
- **Impact:** `api/effects-api` still compiles against `spigot-api` for the `effectsapi.bukkit` package and retains the `HandleRegistry` seam alongside `ServerAccessor`.
- **Suggested next step:** Move `io.github.dailystruggle.effectsapi.bukkit` to the Bukkit carrier, drop `spigot-api` from `api/effects-api/build.gradle`, and fold the remaining `HandleRegistry` / `PlayerHandle` calls onto the neutral `ServerAccessor` contract per [ADR-116](../adr/ADR-116-effects-through-neutral-server-accessor.md) Decisions 2 and 4 (`RTP-70`).
- **Linear:** RTP-70 (3.3.x stopgap RTP-78 complete; glide without an elytra: RTP-79)

### 2026-10-18 - Web editor paints whole walk-path squares red when hazard runs exceed the cap

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** user report of solid red squares on the walk path (SQUARE region, radius 830c, centerRadius 121, P=16, Paper 26.2)
- **Location:** `rtp-core/.../commands/editor/EditorCurveModel.java` `MAX_HAZARD_RUN_BYTES`; `EditorSessionManager.generateVisualizationPayload` (`hazardRunsSkipped: "hazard-cap"`); `EditorLiveFeed` / `HazardTiles`; `docs/editor/index.html` `pathHazardSampler`
- **Symptom / hypothesis:** 100,140 bad runs exceed the 256 KiB cap, so the page colours the path from the export-time 2D grid (128x128 cells, about 13 chunks each); one bad chunk turns a whole cell red, and live hazard updates stop. Curve, scan data and `ScanTask` checked and ruled out; at 512c the exact runs fit and the render is correct.
- **Impact:** Misleading solid-red blocks in the editor on large regions; display only, selection unaffected.
- **Suggested next step:** Ship hazard runs coarse to fine like biomes (`BiomeBinCodec` levels 32/4/1, finer levels bin by bin via a level field on `hazard-delta`, bad share per stretch). Stopgap: raise the cap to about 1 MiB.
- **Linear:** RTP-72

### 2026-10-14 - Folia safety scan reads live neighbour chunks off-thread when the centre is an Anvil view

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** dividing Folia region-thread work per region (ADR-112)
- **Location:** `rtp-core/.../selection/region/PregenTask.java` `loadSafetyNeighbours` / `evaluateSafety` (~1157-1300)
- **Symptom / hypothesis:** When the centre chunk is an Anvil view (`reservation == null`), `evaluateSafety` runs on the async worker, but neighbour slots can hold live resident chunks whose `isSafe` reads touch live block state. Neighbours that belong to another Folia region than the centre were also read from the centre's thread before ADR-112.
- **Impact:** Possible `ThreadAccessException` or racy reads at `safetyRadius > 0` near loaded areas; candidates are rejected rather than accepted, so S-001 holds.
- **Suggested next step:** Read neighbours from Anvil views or snapshots only, or dispatch each live neighbour's column check to its own region and join the results off-thread.
- **Linear:** RTP-92

### 2026-10-14 - Folia arrival platform writes blocks in one unbudgeted destination-region task

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** dividing Folia region-thread work per region (ADR-112)
- **Location:** `platforms/rtp-folia/rtp-folia-common/.../entity/FoliaRTPPlayer.java` `teleportAsync(...).thenCompose` (~188-228); `FoliaRTPWorld#platform` (~1029-1105)
- **Symptom / hypothesis:** After `teleportAsync` completes on the destination region, the emergency platform is built inline with nested `runTask` per chunk and `setType` loops sized by `platformRadius`. It is player-facing, so the new per-region budget only charges it.
- **Impact:** Many simultaneous arrivals in one region with large platforms can still extend that region's tick.
- **Suggested next step:** Measure with the stress harness; if it shows, split block writes across ticks per chunk or cap writes per tick.
- **Linear:** RTP-93

### 2026-10-16 — Package tangles: selection.region is mutually coupled with shapes

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** SonarQube tangle audit on branch `v3.3.0` / PR #57 (ADR-115 Phase 2)
- **Location:** `rtp-core/.../selection/region/` (`Region`, `LockFreeLocationBuffer`, `LocationGenerator`, `RegionSettings`, `RegionQueueManager`) and its importers in `selection.region.selectors.memory.shapes`
- **Symptom / hypothesis:** The original hypothesis (relocating `RTPLocation` breaks ~6 cycles) is wrong. Measured with `jdeps -verbose:class` on compiled `rtp-core` (2026-10-19): moving `RTPLocation` alone removes 0 of 45 mutual package pairs, because every importer also uses `Region`, `LockFreeLocationBuffer`, `LocationGenerator` or `RegionSettings`; `DatabaseAccessor` does not use `RTPLocation` at all (only `Region`). Moving `LockFreeLocationBuffer` too breaks only `region<->cache`, and the new package then depends on `common.RTP`. Moving to `rtp-api` would also collide with the existing `api.world.RTPLocation`. Seam 1 (`region -> cache`, `region -> claim`, `region -> database`) is now closed via `RingBufferDelegate`, `RegionClaimTracker`, and `RegionLocationStore` and enforced by ArchUnit Rules 23-25 in `RTPArchitectureTest.java` (alongside Rule 19 `tools_must_not_depend_on_region_classes` and Rule 20 `runtime_holder_must_not_depend_on_core`). Remaining reverse edge: `region <-> shapes` (`MemoryShape` and subclasses).
- **Impact:** Partial violation of the Tier 1-4 package DAG in ADR-115; `selection.region` remains coupled to `selection.region.selectors.memory.shapes`.
- **Suggested next step:** Break the remaining `region <-> shapes` reverse edge by routing shape-specific region queries through a region-owned port interface.
- **Linear:** RTP-67

### 2026-10-14 - LocationGenerator.joinSafely waits on work queued to the same pool

- **Severity:** Low
- **Status:** Open
- **Discovered during:** bounding the Fabric/NeoForge async pools
- **Location:** `rtp-core/.../selection/region/LocationGenerator.java` `joinSafely`
- **Symptom / hypothesis:** Blocks a pool thread on a future whose work is submitted to the same bounded pool. Only tests call it today, and it has a timeout.
- **Impact:** None in production; a future production caller could stall a bounded pool until the timeout.
- **Suggested next step:** Remove it or make callers compose the future instead of joining.

### 2026-10-06 — Pregen biome extraction reads surface biomes with the Anvil probe for every format

- **Severity:** Low
- **Status:** Open
- **Discovered during:** removing the built-in Linear reader (ADR-077 revision)
- **Location:** `rtp-core/.../commands/editor/PregenBiomeExtractor.java` `sampleRegionFile` (~264-300): `reader.isChunkGenerated(...)` then `AnvilReader.readColumnProbe(bytes, ...)`
- **Symptom / hypothesis:** The generated check uses the registered reader, but the surface-biome probe always parses the bytes as Anvil. For an addon-registered format the probe throws (caught), so every chunk falls back to the shape's biome cache or `minecraft:plains`.
- **Impact:** None today (only `.mca` is built in). Once a Linear addon registers a reader, the editor's land and biome backdrop for Linear worlds would show plains or cached biomes instead of the real ones.
- **Suggested next step:** Probe through the registered reader: `reader.readChunk(...)` then `AnvilReader.toView(entry.root)` for the surface biome, or add a column-probe method to the `RegionFileReader` SPI with an Anvil default.
- **Linear:** RTP-22

### 2026-10-06 — Fuzz targets: unreachable paths, exception-only oracles, missing parsers

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (Jazzer fuzz targets)
- **Location:** `AnvilRegionFuzzTest.java`; `rtp-proxy-common/src/test/.../RespProtocolFuzzTest.java`
- **Symptom / hypothesis:**
  - The Anvil targets always read chunk `(0, 0)`, so other location-table slots are never exercised. `readChunkView` is only constructed, never queried, so lazy palette decoding is not exercised.
  - Oracles only check which exception escapes. The open `palette[0]` fail-open returns normally and cannot be detected; no target asserts "malformed input never yields ACCEPT" through `AnvilPrefilter`.
  - `RespProtocolFuzzTest` tolerates `NumberFormatException` and the Anvil targets tolerate `IllegalArgumentException`, although the parsers convert or never throw these; a regression that leaks them would go unnoticed.
  - Parsers of untrusted or semi-trusted input with no target: `RtpYamlReader` (imported configs; would find the open depth issue at once), `EditorLoopbackJson`, `EditorSessionManager.parseJsonStringMap`, `PluginMessageEnvelope.open`, `ProxyDirectWire`, `BiomeBinCodec`/`WorldBiomeStore.load`, `TinyJsonReader`, `SafetyTokenParser`, and the Bukkit plugin-message length reader.
- **Impact:** Coverage gap only; no production defect.
- **Suggested next step:** Let the fuzzer pick the chunk coordinates (`FuzzedDataProvider`); add a prefilter target asserting the verdict is never ACCEPT on malformed data; narrow the tolerated exceptions; add targets for YAML, editor JSON and the network envelope first.
- **Linear:** RTP-28

### 2026-10-07 — Web editor reference materialize does not support native undo/redo

- **Severity:** Low
- **Status:** Open
- **Discovered during:** LeafRTP Web Editor testing (`docs/editor/index.html`)
- **Location:** `docs/editor/index.html` line 3421 (`materializeCurrentReference`)
- **Symptom / hypothesis:** In the Config & Prefabs editor panel (`#cfg-editor`), typing characters creates browser-native undo history and responds to `Ctrl+Z`, but clicking `⚡ Materialize` on an inherited reference (e.g. `@config`) replaces text via direct `.value` assignment (`cfgEditor.value = before + replacement + after`). Direct property assignment to a `<textarea>` wipes the browser's native undo manager stack, preventing operators from reverting the materialization or restoring the original reference token via `Ctrl+Z` / `Cmd+Z`.
- **Impact:** Operators who unpack inherited references in the config editor cannot undo the replacement using keyboard shortcuts (`Ctrl+Z`), leaving the configuration file mutated unless manually rewritten or completely reverted.
- **Suggested next step:** Replace editor line text using `document.execCommand('insertText', false, replacement)` across the selected line range (with fallback to `setRangeText`), and dispatch an `input` event. This preserves the browser's native undo stack on the textarea while ensuring geometry synchronization (`applyYamlGeometry`), gutter diagnostics, and diff updates execute cleanly without requiring extra server-roundtrip data.
- **Linear:** RTP-31

### 2026-10-12 - ActionContext gate validators are not consulted by in-session lifecycle gates

- **Severity:** Low
- **Status:** Open
- **Discovered during:** SonarCloud PR 57 coverage tests for `ActionSessionImpl`
- **Location:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/action/ActionSessionImpl.java` `triggerBoundaryViolation` / `triggerStart` (`new ActionGateContext(...)` without the session's `ActionContext`); `GateEvaluator.evaluate` lines 75-79, 123-127
- **Symptom / hypothesis:** `GateEvaluator` applies `context.context().gateValidators()`, but the session builds its `ActionGateContext` without an `ActionContext`, so validators added via `ActionContext.withValidator` gate only queue admission in `ActionManager` and never lifecycle steps. Unclear whether that is intended.
- **Impact:** An addon that expects its validator to suppress `onBoundaryViolation` / `onStart` steps sees them run anyway.
- **Suggested next step:** Decide the contract; either pass `context` into the session's gate contexts or document `withValidator` as admission-only. `ActionSessionImplCoverageTest#gatesAndDelayedSteps` pins the current behaviour.

### 2026-10-13 - Devstack demo regions: two untracked, one with a Bukkit world name on Fabric

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release docs audit (`devstack/README.md` backend counts)
- **Location:** `devstack/.gitignore` lines 78-79 (`backend-a/rtp-config/**`, `backend-b/rtp-config/**`); `devstack/backend-c/rtp-config/regions/default.yml` (`world: "world_the_end"`, header comment still says Folia)
- **Symptom / hypothesis:** The README and `setup-multiworld-demo.ps1` describe one committed demo region per backend, but the backend-a (Verdant Wilds) and backend-b (Ashen Wastes) files exist only in local working copies. backend-c runs Fabric, whose adapters name worlds by registry key (`minecraft:the_end`, see `FabricRTPWorld` region-dir mapping); backend-d already uses `minecraft:overworld`. `world_the_end` probably does not resolve there.
- **Impact:** On a fresh clone the lobby GUI demo shows fewer cross-server destinations than documented, and the Fabric End region may fall back or fail to load.
- **Suggested next step:** Add `!backend-a/rtp-config/regions/default.yml` and the backend-b equivalent to `devstack/.gitignore` and commit the two files. Boot backend-c and check `/rtp info`; switch to `minecraft:the_end` if the region is not bound.

<!-- Append new entries above this comment, ordered by priority (highest severity first). Resolved entries are deleted, not archived. -->
