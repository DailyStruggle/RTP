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

### 2026-10-18 - Rift fall trips `cancelDistance`; teleport cancels and the player is left in the rift

- **Severity:** High
- **Status:** Open
- **Discovered during:** verifying the countdown boss-bar clear on teleport cancel (demo server, `rift` preset)
- **Location:** `addons/LeafRTPRiftAddon/.../RiftEffect.java` `run()` (carve and restore); `rtp-plugin/.../bukkitListeners/OnPlayerMove.java` (cancel check)
- **Symptom / hypothesis:** With `RIFT.6.3` at `preload`, `teleportDelay` 4 and `cancelDistance` 2, a bot that stood still fell about 4 blocks into the rift and got "Teleport cancelled!" 0.5 s after `/rtp`. It stayed at the bottom of the rift, and after the restore its health flickered 20/19 (likely suffocation). The fall counts as movement now that `/rtp` records its origin, and the rift does not react to the cancel.
- **Impact:** With the Rift addon, every delayed `/rtp` cancels itself, and the player can end up inside the restored terrain.
- **Suggested next step:** Exempt the rift's own fall from the cancel check (origin recorded after the carve, or ignore movement inside an open rift), and on `RTPTeleportCancel.postActions` restore the rift with the player moved clear. Interim: `cancelDistance` above rift radius plus fall height.
- **Linear:** RTP-71

### 2026-10-19 - Claim checkers stay reflective where the plugin publishes an API (ADR-118 rule 1)

- **Severity:** Medium
- **Status:** Triaged
- **Discovered during:** 3.3.0 user review ("neural network code") of `MinePlotsChecker`, whose guessed API never detected a plot (fixed in `[Unreleased]`)
- **Location:** `addons/LeafRTPClaimAddon/.../HuskClaimsChecker.java`, `HuskTownsChecker.java`, `PlotSquaredChecker.java`, `ResidenceChecker.java`, `SaberFactionsChecker.java`; their stubs under `src/test/java/{net/william278,com/plotsquared,com/bekvon,com/massivecraft}`
- **Symptom / hypothesis:** All reflective checkers were compared with plugin source, fixed (UltimateClaims, PlotSquared, HuskClaims, KingdomsX), and now fail closed while the plugin is enabled; `ReflectiveClaimCheckerApiShapeS003Test` runs each against a hand-copied stub. These five plugins publish API artifacts (`net.william278.huskclaims:huskclaims-bukkit` and `net.william278.husktowns:husktowns-bukkit` on repo.william278.net, PlotSquared core on Maven Central / PaperMC, `com.github.Zrips:Residence` and `com.github.SaberLLC:Saber-Factions` on JitPack), so ADR-118 rule 1 wants them compiled against directly. A stub can drift from the real API without failing the build.
- **Impact:** None known today; a future upstream rename would be caught only at runtime (fail closed, SEVERE log), not at build time.
- **Suggested next step:** Per plugin, add the artifact as `compileOnly`/`testImplementation` with `content { includeGroup ... }`, call the API types directly, and delete the matching stub package (it would clash on the test classpath). UltimateClaims (repo.craftaro.com unresolvable), CrashClaim, KingdomsX and FactionsBridge publish nothing usable and stay reflective.

### 2026-10-20 - `JumpAdjustorExpandTokensTest` fails when run on its own

- **Severity:** Low
- **Status:** Open
- **Discovered during:** ADR-119 slope check (filtered `:rtp-core:test --tests "*JumpAdjustor*"` run)
- **Location:** `rtp-core/src/test/.../verticalAdjustors/jump/JumpAdjustorExpandTokensTest.java` `nullWorldGuards`, `keysAndParameters`; `VerticalAdjustor.<init>` line 45
- **Symptom / hypothesis:** Run alone (or with only adjustor tests), both tests throw `NullPointerException: vertAdjustorFactory is null` in the `VerticalAdjustor` constructor; they pass in the full suite. The test likely relied on `RTP.<clinit>` filling the factory registry, which the package-hub refactor made explicit (`RTP.ensureInitialized()`).
- **Impact:** Test-only; filtered runs report false failures.
- **Suggested next step:** Call `RTP.ensureInitialized()` (or `RTPTestSetup.install`) in the test's setup, as was done for the other tests listed in `docs/dev/RTP-HUB-RESUME.md` Wave 2.

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

### 2026-10-18 - Built-in effects registered per platform from hand-copied lists; `GLIDE`, `TITLE` and `DROP_*` missing on some platforms

- **Severity:** Medium
- **Status:** Triaged
- **Discovered during:** review of the claim that `TITLE` is only wired on Fabric and NeoForge
- **Location:** `api/effects-api/.../bukkit/BukkitEffectsInitializer.java` `registerAll()`; `api/effects-api/effects-api-fabric-unobf/.../FabricEffectsInitializer.java` `registerAll()`; `platforms/rtp-neoforge/rtp-neoforge-common/.../NeoForgeEffectsInitializer.java`; `NeoForgeHandles` (`sendTitle`, `playNote`, `spawnFirework` have empty bodies)
- **Symptom / hypothesis:** The shared effect classes are platform-neutral, but each platform registers them from its own `EffectFactory.addEffect(...)` list and no test compares the lists. Bukkit lacks `TITLE`; Fabric 26.x lacks `GLIDE`; NeoForge lacks `GLIDE`, `DROP_INVENTORY` and `DROP_EXP`. The cause is a second, effects-only platform seam (`HandleRegistry`) beside `RTPServerAccessor`.
- **Impact:** Unregistered effect tokens in `definitions/effects/*.yml` and `rtp.effect.*` nodes are skipped without a message; operators lose effects when moving between platforms. Disclosed in `docs/admin/configuration/EVENTS_AND_EFFECTS.md` (*Platform availability*).
- **Suggested next step:** 3.3.x patch: add the missing `addEffect` lines plus a registration parity test. Root fix: [ADR-116](../adr/ADR-116-effects-through-neutral-server-accessor.md) (Proposed) - neutral accessor module, platform primitives on the accessor, one registration in effects-api.
- **Linear:** RTP-70

### 2026-10-18 - Web editor paints whole walk-path squares red when hazard runs exceed the cap

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** user report of solid red squares on the walk path (SQUARE region, radius 830c, centerRadius 121, P=16, Paper 26.2)
- **Location:** `rtp-core/.../commands/editor/EditorCurveModel.java` `MAX_HAZARD_RUN_BYTES`; `EditorSessionManager.generateVisualizationPayload` (`hazardRunsSkipped: "hazard-cap"`); `EditorLiveFeed` / `HazardTiles`; `docs/editor/index.html` `pathHazardSampler`
- **Symptom / hypothesis:** 100,140 bad runs exceed the 256 KiB cap, so the page colours the path from the export-time 2D grid (128x128 cells, about 13 chunks each); one bad chunk turns a whole cell red, and live hazard updates stop. Curve, scan data and `ScanTask` checked and ruled out; at 512c the exact runs fit and the render is correct.
- **Impact:** Misleading solid-red blocks in the editor on large regions; display only, selection unaffected.
- **Suggested next step:** Ship hazard runs coarse to fine like biomes (`BiomeBinCodec` levels 32/4/1, finer levels bin by bin via a level field on `hazard-delta`, bad share per stretch). Stopgap: raise the cap to about 1 MiB.
- **Linear:** RTP-72

### 2026-10-18 - Devstack compose files set `RUN_AS_UID`/`RUN_AS_GID`, which the itzg image ignores

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** fixing the nightly `versions-smoke` jobs (`docker-compose.versions.yml` now uses `UID`/`GID`)
- **Location:** `devstack/docker-compose.yml` (backends), `docker-compose.compat.yml`, `docker-compose.fabric.yml`, `docker-compose.legacy.yml`, `docker-compose.sweep.yml`
- **Symptom / hypothesis:** The itzg `start` script reads `UID`/`GID` and drops to uid 1000 otherwise (`docker run -e RUN_AS_UID=0` logs `Running as uid=1000`); `user: "0:0"` does not help because the entrypoint still demotes via `gosu`. Works on Docker Desktop for Windows (permissive binds), fails on Linux where Docker creates missing bind dirs root-owned (`AccessDeniedException: ./world/session.lock`).
- **Impact:** The nightly `Devstack Acceptance Smoke` job (now past `npm ci`) and Linux contributors are likely to hit the same boot failure.
- **Suggested next step:** Replace `RUN_AS_UID`/`RUN_AS_GID` with `UID: "0"`/`GID: "0"` plus `SKIP_CHOWN_DATA: "TRUE"`, then re-run the nightly workflow.

### 2026-10-14 - Folia safety scan reads live neighbour chunks off-thread when the centre is an Anvil view

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** dividing Folia region-thread work per region (ADR-112)
- **Location:** `rtp-core/.../selection/region/PregenTask.java` `loadSafetyNeighbours` / `evaluateSafety` (~1157-1300)
- **Symptom / hypothesis:** When the centre chunk is an Anvil view (`reservation == null`), `evaluateSafety` runs on the async worker, but neighbour slots can hold live resident chunks whose `isSafe` reads touch live block state. Neighbours that belong to another Folia region than the centre were also read from the centre's thread before ADR-112.
- **Impact:** Possible `ThreadAccessException` or racy reads at `safetyRadius > 0` near loaded areas; candidates are rejected rather than accepted, so S-001 holds.
- **Suggested next step:** Read neighbours from Anvil views or snapshots only, or dispatch each live neighbour's column check to its own region and join the results off-thread.

### 2026-10-14 - Folia arrival platform writes blocks in one unbudgeted destination-region task

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** dividing Folia region-thread work per region (ADR-112)
- **Location:** `platforms/rtp-folia/rtp-folia-common/.../entity/FoliaRTPPlayer.java` `teleportAsync(...).thenCompose` (~188-228); `FoliaRTPWorld#platform` (~1029-1105)
- **Symptom / hypothesis:** After `teleportAsync` completes on the destination region, the emergency platform is built inline with nested `runTask` per chunk and `setType` loops sized by `platformRadius`. It is player-facing, so the new per-region budget only charges it.
- **Impact:** Many simultaneous arrivals in one region with large platforms can still extend that region's tick.
- **Suggested next step:** Measure with the stress harness; if it shows, split block writes across ticks per chunk or cap writes per tick.

### 2026-10-16 — Package tangles: selection.region is mutually coupled with cache, claim, database, tools and shapes

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** SonarQube tangle audit on branch `v3.3.0` / PR #57 (ADR-115 Phase 2)
- **Location:** `rtp-core/.../selection/region/` (`Region`, `LockFreeLocationBuffer`, `LocationGenerator`, `RegionSettings`, `RegionQueueManager`) and its importers in `selection.region.cache`, `selection.region.claim`, `selection.region.selectors.memory.shapes`, `database`, `tools`
- **Symptom / hypothesis:** The original hypothesis (relocating `RTPLocation` breaks ~6 cycles) is wrong. Measured with `jdeps -verbose:class` on compiled `rtp-core` (2026-10-19): moving `RTPLocation` alone removes 0 of 45 mutual package pairs, because every importer also uses `Region`, `LockFreeLocationBuffer`, `LocationGenerator` or `RegionSettings`; `DatabaseAccessor` does not use `RTPLocation` at all (only `Region`). Moving `LockFreeLocationBuffer` too breaks only `region<->cache`, and the new package then depends on `common.RTP`. Moving to `rtp-api` would also collide with the existing `api.world.RTPLocation`. Reverse edges: `region -> cache` (`CacheStage`, `HotSink`, `KeyedCacheStage`, `RingCacheStage`), `region -> claim` (`ClaimAnchoredRegionTracker`), `region -> database` (`DatabaseAccessor`), `region -> tools` (`CfDiag`, `HeapPressureMonitor`, `MemoryTracker`, `ServerHealthGate`), `region -> shapes` (`MemoryShape` and subclasses).
- **Impact:** Violates the Tier 1-4 package DAG in ADR-115; `selection.region` cannot be tested or reused apart from caches, storage and diagnostics.
- **Suggested next step:** Break the reverse edges, not the data type: have `Region` depend on interfaces it owns for its cache stages, claim tracker, persistence and health gates, injected by the composition root. Dissolving the `common.RTP` hub (ADR-115 seam 4) is the larger lever: modelled alone it cuts mutual pairs from 45 to 16.
- **Linear:** RTP-67

### 2026-10-16 — Package tangles: Region creates pipeline tasks that import Region

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** SonarQube tangle audit on branch `v3.3.0` / PR #57 (ADR-115 Phase 2)
- **Location:** `rtp-core/.../selection/region/Region.java` (`new ScanTask`, `TeleportPipelineTask`, `RTPTeleportCancel`, `TeleportPipelineTask.ConfigCache`); `RegionCacheTask` (`RTPTaskPipe`, `CompletionPaced`)
- **Symptom / hypothesis:** The `Configs` half is done: the reload steps on tasks and regions run in `lifecycle.ConfigReloadCoordinator` through `configuration.ConfigReloadLifecycle`, and `RTPArchitectureTest` rules 13-14 keep `configuration` off `tasks..` and `selection.region`. That removed `configuration<->tasks`, `<->tasks.teleport`, `<->tasks.tick` and `<->selection.region` (45 to 41 mutual package pairs). Still open: `Region` creates and calls pipeline tasks while those tasks import `Region`, so `selection.region<->tasks.teleport` and `selection.region<->tasks` remain.
- **Impact:** Violates the Tier 1-4 package DAG in ADR-115, tangling region lifecycle with pipeline task dispatching.
- **Suggested next step:** Invert task creation in `Region` through a dispatcher interface owned by `selection.region` and implemented in Tier 3/4 (modelled: removes `region<->tasks.teleport`); `region<->tasks` also needs `RTPTaskPipe` / `CompletionPaced` moved below `selection.region`.
- **Linear:** RTP-68

### 2026-10-18 - Quarantine warning drops the YAML parse reason

- **Severity:** Low
- **Status:** Open
- **Discovered during:** GIF demo presets (a trigger written with inline `pos1: { x: .. }` maps)
- **Location:** `rtp-core/.../configuration/ConfigParser.java` `check` (~1060-1072)
- **Symptom / hypothesis:** When the file database cannot load a YAML file, the log says only "could not be parsed; quarantining". The `RtpYamlParseException` key and line (here `rtpYaml.unsupported.flowMap`) never reach the operator, and for an action file with no shipped default the file just disappears into `*.corrupt-<ts>`.
- **Impact:** Operators who copy the inline-map trigger example from older docs lose the action with no hint why.
- **Suggested next step:** Carry the parse exception from the file database to this warning and log its message key and line.


### 2026-10-18 - `gif-preset.sh` exits silently when no LeafRTP-Pro jar is present

- **Severity:** Low
- **Status:** Open
- **Discovered during:** portal preset trigger box (testing `write_portal` against a scratch server dir)
- **Location:** `scripts/gif-preset.sh` line 26 (`PRO_JAR="$(ls ... | head -1)"`)
- **Symptom / hypothesis:** Under `set -euo pipefail` the failing `ls` makes the assignment fail, so the script exits with status 2 before printing anything, for every preset including `list` and `status`.
- **Impact:** On a demo dir without the Pro jar the script appears to do nothing.
- **Suggested next step:** Append `|| true` to the substitution; `want_addon` already reports a missing jar for the `gui` preset.

### 2026-10-14 - FoliaRegionProcessor is never constructed

- **Severity:** Low
- **Status:** Open
- **Discovered during:** auditing Folia tick-health sampling
- **Location:** `platforms/rtp-folia/rtp-folia-common/.../FoliaRegionProcessor.java`
- **Symptom / hypothesis:** Dead class; per-region samples now come from `FoliaMetricsBinding#observeRegion` probes, and the queue-depth field it would feed stays 0.
- **Impact:** None at runtime; `foliaRegions()` reports `queueDepth = 0`.
- **Suggested next step:** Delete it, or feed `regionQueueDepths` from `FoliaSchedulerImpl` cell depths.

### 2026-10-14 - LocationGenerator.joinSafely waits on work queued to the same pool

- **Severity:** Low
- **Status:** Open
- **Discovered during:** bounding the Fabric/NeoForge async pools
- **Location:** `rtp-core/.../selection/region/LocationGenerator.java` `joinSafely`
- **Symptom / hypothesis:** Blocks a pool thread on a future whose work is submitted to the same bounded pool. Only tests call it today, and it has a timeout.
- **Impact:** None in production; a future production caller could stall a bounded pool until the timeout.
- **Suggested next step:** Remove it or make callers compose the future instead of joining.

### 2026-10-13 - FabricRTPWorldUnobf.getMaxHeight returns the inclusive getMaxY()

- **Severity:** Low
- **Status:** Open
- **Discovered during:** fixing the same bound in the v26 Fabric world adapters
- **Location:** `platforms/rtp-fabric/rtp-fabric-common-unobf/src/main/java/io/github/dailystruggle/rtp/fabric/unobf/world/FabricRTPWorldUnobf.java` `getMaxHeight` (~1560-1569)
- **Symptom / hypothesis:** Returns `world.getMaxY()` (the top block, inclusive). The `RTPWorld` contract and the Bukkit, NeoForge, intermediary Fabric and v26 adapters return an exclusive bound, and core callers (`AbstractVerticalAdjustor`, `LinearAdjustor`, `FixedAdjustor`) subtract from it.
- **Impact:** On worlds served by the unobf carrier, vertical scans skip the top block row and headroom checks are one block too strict.
- **Suggested next step:** Return `world.getMaxY() + 1` and fix the comment, as done in `V26_x_R1FabricRTPWorld`.

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


### 2026-10-07 — Bare /rtp early guard enforces global cooldown when destination region override is shorter

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 bare `/rtp` cooldown review
- **Location:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/RTPCmd.java` lines 245-258 (`onCommand`)
- **Symptom / hypothesis:** When a region specifies a `cooldown` override that is shorter than the global `config.yml` cooldown (e.g. 5 seconds for a specific region vs 60 seconds global), bare `/rtp` invocations are blocked by the early spam guard in `onCommand` for the full global cooldown duration (`sender.cooldown()`). The early guard evaluates `sender.cooldown()` before the asynchronous `compute()` phase resolves the destination region and checks `RegionSettings.cooldownMillis()`.
- **Impact:** Players cannot take advantage of shorter region-specific cooldown overrides when executing bare `/rtp`; they are held to the longer global cooldown unless they invoke an explicit target override (e.g. `/rtp region:<name>`), which bypasses the early guard.
- **Suggested next step:** Resolve the sender's current world default region in `RTPCmd.onCommand` when evaluating the early cooldown guard for bare `/rtp`, or delegate cooldown enforcement for bare `/rtp` entirely to the `compute()` pipeline phase.
- **Linear:** RTP-32


### 2026-10-12 - FoliaSchedulerImpl.runTask regional cap is check-then-increment and re-queues at a fixed 50 ms

- **Severity:** Low
- **Status:** Open
- **Discovered during:** StressTestRTP Sched.cancel leak investigation (read-only scheduler review)
- **Location:** `platforms/rtp-folia/rtp-folia-common/src/main/java/io/github/dailystruggle/rtp/folia/scheduling/FoliaSchedulerImpl.java` lines 251-256 (`runTask(RTPWorld, int, int, Runnable)`)
- **Symptom / hypothesis:** The background regional path reads `inFlightRegionalTasks.get() >= maxRegionalInflight` and then calls `incrementAndGet()` as two separate steps, so concurrent callers can overshoot `maxRegionalInflight` (`availableProcessors() / 2`) by the number of racing threads. When the cap is reached the task is re-queued with a fixed 50 ms `runDelayed` and no backoff or jitter, so a saturated cap adds up to 50 ms per deferred task and every retry repeats the ownership check.
- **Impact:** Soft cap is not exact under load; above the cap, deferred background regional work gains a 50 ms quantised delay that can flatten achieved TP/s near `maxRegionalInflight / service time`.
- **Suggested next step:** Use a CAS loop (as `pumpQueue` does) for the permit, and consider routing deferred entries through `interactiveQueue`/`pumpQueue` instead of a timed re-queue. Confirm first with the cap-saturation counters from a >= 96-bot ramp at 200-320 TP/s.

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

### 2026-10-13 - TeleportPipelineTask.ConfigCache is global, not a per-task snapshot

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release bug fixes (pipeline audit item 10, deferred)
- **Location:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tasks/teleport/TeleportPipelineTask.java` `ConfigCache` (~143-163, refreshed by `Configs.onReload`)
- **Symptom / hypothesis:** Every in-flight task reads the same static fields, so a `/rtp reload` mid-teleport can give one attempt messages, `viewDistanceTeleport`, lock-after and respawn settings from two different config generations.
- **Impact:** Rare and cosmetic in practice (reload is already refused while it runs); a teleport that straddles a reload may mix old and new settings.
- **Suggested next step:** Snapshot the values into an immutable record at task construction (or at the SETUP-to-LOAD transition) and read that record in the later phases; keep the static cache only as the source for new snapshots.

### 2026-10-17 - Fabric/NeoForge `setBiomeGetter` override is stored but never read

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** SonarCloud medium-maintainability sweep (S1068 unused private field)
- **Location:** `platforms/rtp-fabric/rtp-fabric-common/.../server/FabricServerAccessor.java` field `biomeGetter` (~line 89) and `setBiomeGetter` (~line 2022); `platforms/rtp-neoforge/rtp-neoforge-common/.../server/NeoForgeServerAccessor.java` field `biomeGetter` (~line 60) and `setBiomeGetter` (~line 692)
- **Symptom / hypothesis:** `setBiomeGetter` assigns the field, but biome lookups never call it, so an addon-installed biome resolver is silently ignored. Bukkit and Folia forward the getter into `BukkitRTPWorld` / `FoliaRTPWorld`, where it is used.
- **Impact:** On Fabric and NeoForge, biome filters ignore addon overrides (e.g. custom world generators) and always use the vanilla registry lookup.
- **Suggested next step:** Route the world adapters' biome reads through `biomeGetter` (or forward it to the world class the way Bukkit/Folia do), and add a test that installs a stub getter and checks it is consulted. Then close Sonar's S1068 on these fields; it is a true positive.

### 2026-10-17 - `Factory.getOrDefault` throws on an empty map despite `@NotNull` contract

- **Severity:** Low
- **Status:** Open
- **Discovered during:** SonarCloud medium-maintainability sweep (S2589 in `MultiConfigParser.getParser`)
- **Location:** `rtp-core/.../common/factory/Factory.java` `getOrDefault` (~152-170); `rtp-core/.../configuration/MultiConfigParser.java` `getParser` (~283-291)
- **Symptom / hypothesis:** `getOrDefault` throws an NPE when the factory map is empty instead of returning a default, so the `parser != null` fallback (`new ConfigParser(...)`) in `MultiConfigParser.getParser` can never run.
- **Impact:** A multi-config file whose factory has no entries yet crashes the lookup instead of falling back to a fresh parser.
- **Suggested next step:** Decide the contract (throw `IllegalStateException` with a message, or return a fallback), make `getOrDefault` match its Javadoc, and add an empty-factory test; then close or fix the S2589 accordingly.

### 2026-10-17 - `VerticalAdjustor.equals` and `hashCode` disagree

- **Severity:** Low
- **Status:** Open
- **Discovered during:** SonarCloud medium-maintainability sweep (S3415 in `VerticalAdjustorBaseTest`)
- **Location:** `rtp-core/.../selection/worldborder/verticalAdjustors/VerticalAdjustor.java` (or its package) `equals` / `hashCode`
- **Symptom / hypothesis:** `equals` ignores `name`, but `hashCode` includes `name` and `super.hashCode()`, so two equal adjustors can hash differently.
- **Impact:** Adjustors used as map/set keys can be duplicated or not found.
- **Suggested next step:** Use the same fields in both methods and add an equal-implies-same-hash test.

### 2026-10-17 - `Rectangle` with `rotation=90` does not round-trip location to XZ and back

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** SonarCloud medium-maintainability sweep (S1854 dead store in `DegenerateShapeAndSpiralTest` ~153)
- **Location:** `rtp-core/.../selection/region/selectors/shapes/Rectangle.java` `locationToXZ` / `xzToLocation`
- **Symptom / hypothesis:** For location `500` with `rotation=90`, `xzToLocation(locationToXZ(500))` returns `-9549`. The two transforms are probably rotating in opposite or inconsistent directions.
- **Impact:** Any path that maps a placement back to its index (verification, coverage bookkeeping, backlog) mis-identifies placements on rotated rectangles.
- **Suggested next step:** Write a round-trip property test across rotations 0/45/90/180, then fix the inverse rotation.

### 2026-10-18 - `ActionLifecycleTerminalAndConfinementTest` fails when run after other `rtp-core` tests

- **Severity:** Low
- **Status:** Open
- **Discovered during:** `teleportDelay` seconds fix (filtered run `--tests '*ConfigParser*' '*TeleportPipelineTask*' '*Placeholder*' '*Delay*' '*Cooldown*'`)
- **Location:** `rtp-core/src/test/.../action/ActionLifecycleTerminalAndConfinementTest.java` ~123 and ~206 (casts `RTP.scheduler` to `MockRTPScheduler`); `RTP.java` ~1484-1488 wraps `RTP.scheduler` in `ProfilingRTPScheduler`
- **Symptom / hypothesis:** "Delayed terminal lifecycle step aborts if player went offline" throws `ClassCastException: ProfilingRTPScheduler cannot be cast to MockRTPScheduler` in a shared JVM and passes alone. An earlier test leaves the profiling wrapper installed on the static scheduler.
- **Impact:** Test-order flake only; no runtime effect.
- **Suggested next step:** Install a fresh `MockRTPScheduler` in the test's own setup (or unwrap `ProfilingRTPScheduler`) instead of casting the shared static.

### 2026-10-13 - Block-list rejection log still names `safety.yml`

- **Severity:** Cosmetic
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release front-page audit (block-list grammar section)
- **Location:** `PregenState` / `QueueTask` warnings `[safety.yml] rejected unsafe-blocks token ...`
- **Symptom / hypothesis:** `unsafeBlocks` / `airBlocks` live in `advanced/blocks.yml` (lists in an old `safety.yml` are migrated there, ADR-071), but the warning prefix still names `safety.yml`.
- **Impact:** Operators look in the wrong file for the rejected token. FRONT_PAGE.md quotes the current text and explains the mismatch, so it needs updating with the fix.
- **Suggested next step:** Change the prefix to `[advanced/blocks.yml]` (message text is a log string, not a `messages.yml` key) and update the quoted line in `docs/publishing/FRONT_PAGE.md`.

### 2026-10-13 - `/rtp config language` suggests 5 of the 11 bundled locales

- **Severity:** Cosmetic
- **Status:** Open
- **Discovered during:** front-page underselling pass (11-languages bullet)
- **Location:** `rtp-core/.../commands/config/LanguageCmd.java` `BUNDLED_LOCALES` (en, de, es, fr, nl); `LanguageBootstrap.writeDefault` fallback comment ("Bundled locales: en, de, es, fr")
- **Symptom / hypothesis:** `rtp-plugin/src/main/resources/lang/` ships de, es, fr, it, ja, ko, nl, pl, pt, ru, zh (plus the `cat` novelty locale). `sanitize` accepts all of them, but tab completion and the fallback `language.yml` comment list fewer.
- **Impact:** Operators may not know it, ja, ko, pl, pt, ru or zh exist unless they read the docs; typing them works.
- **Suggested next step:** Derive the suggestion set from the jar's `lang/` entries (or one shared constant checked by `LocaleParityTest`) and use it for the fallback comment.

### 2026-10-20 - `SUPPORT.md` says paid support is "Not offered"

- **Severity:** Cosmetic
- **Status:** Open
- **Discovered during:** README rewrite (support section)
- **Location:** `SUPPORT.md`, "Where to ask" table, row "Commercial / paid support"; also its resource link `spigotmc.org/resources/rtp.94812/` vs `leafrtp.94812` in `README.md`
- **Symptom / hypothesis:** The row predates the Pro support tier (ADR-100, ADR-108), which sells priority support. README and the storefront pages say it is offered.
- **Impact:** Buyers and readers get contradictory answers about paid support.
- **Suggested next step:** Point the row at the BuiltByBit Pro listing and describe what it covers, matching the README wording.

<!-- Append new entries above this comment, ordered by priority (highest severity first). Resolved entries are deleted, not archived. -->
