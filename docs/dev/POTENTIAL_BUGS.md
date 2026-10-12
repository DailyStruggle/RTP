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


### 2026-10-08 — Mojibake in NeoForge build script and overly broad exclusion in check-mojibake.py

- **Severity:** Low
- **Status:** Open
- **Discovered during:** pre-commit mojibake violation resolution
- **Location:** `platforms/rtp-neoforge/rtp-neoforge-common/build.gradle` lines 1, 11, 17, 53, 61, 65; `scripts/check-mojibake.py` line 87 (`if exc in rel_path:`)
- **Symptom / hypothesis:** `platforms/rtp-neoforge/rtp-neoforge-common/build.gradle` contains several corrupted em dash sequences (double-encoded UTF-8). `scripts/check-mojibake.py` failed to detect this because `EXCLUDED_PATHS` contains `'build'`, and substring matching `if exc in rel_path:` unintentionally excludes any file named `build.gradle` or under a directory containing `build`.
- **Impact:** Lingering encoding anomalies in Gradle files; reduced coverage of mojibake detection across Gradle build definitions in the repository.
- **Suggested next step:** Replace the corrupted em dash sequences with clean em dashes or ASCII hyphens in `platforms/rtp-neoforge/rtp-neoforge-common/build.gradle`. Update `scripts/check-mojibake.py` to match directory components (`/build/` or Path parts) rather than simple substring matching.

<!-- Append new entries above this comment, ordered by priority (highest severity first). Resolved entries are deleted, not archived. -->
