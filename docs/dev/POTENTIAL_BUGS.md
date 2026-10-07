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


### 2026-10-06 — Arrived players sync-load their landing chunk on the main thread (Paper)

- **Severity:** High
- **Status:** In Progress
- **Discovered during:** spark analysis of stress run `20261006-195124` (Paper 26.2, LeafRTP phase)
- **Location:** `platforms/rtp-bukkit/rtp-bukkit-common/.../entity/BukkitRTPPlayer.java` `setLocation` (sync `player.teleport` path) and the arrival-chunk ticket lifetime in `TeleportPipelineTask` / `BukkitRTPWorld.setForceLoadedImpl`
- **Symptom / hypothesis:** 14.1 s of the 115.7 s phase (about 3.4 ms per teleport) is the server thread parked in `ServerChunkCache.syncLoad`, reached from the arrived player's first tick (`LivingEntity.baseTick` > `Entity.isInWall` > `getChunk`). The landing chunk is not at FULL when the player first ticks: either the arrival ticket is released before the player's own ticket holds it, or `viewDistanceTeleport: 0` skips the pre-load. BetterRTP and EzRTP show about 0.1 ms per teleport on this path. The harness reports `chunks_sync_requested = 0` because `SyncLoadAttributor` only charges plugin-initiated loads.
- **Impact:** S-005 violation on every cache-served teleport. It is the largest LeafRTP-attributable main-thread cost (more than LeafRTP's own 1.8 ms per teleport) and caps main-thread throughput.
- **Suggested next step:** Cause: the pipeline released the arrival ticket in the teleport tick, and with `delay-chunk-unloads-by: 0s` the chunk unloaded before the player's first tick. Fix pending bench verification: `TeleportPipelineTask.holdArrivalReservation` keeps it for `ARRIVAL_HOLD_TICKS`. Remove this entry once a spark run shows the `isInWall > syncLoad` stack gone in the LeafRTP phase.
- **Linear:** RTP-4







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
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (Jazzer fuzz targets)
- **Location:** `api/anvil-api/src/test/.../AnvilRegionFuzzTest.java` (four `@FuzzTest` methods); `.github/workflows/fuzzing.yml` ~46-55; root `build.gradle` `tasks.withType(Test)` ~147-172
- **Symptom / hypothesis:**
  - Jazzer 0.30.0 fuzzes one `@FuzzTest` per run ("Only one fuzz test can be run at a time"). A local `JAZZER_FUZZ=1` run of the class fuzzed `fuzzNbtReaders` for about 3 s and reported the LZ4, column-probe and chunk-view targets as SKIPPED; the task still passed.
  - The workflow passes `-Djazzer.duration`, but the option is `jazzer.max_duration`, and Gradle `-D` sets a property on the Gradle JVM, not on the forked test JVM. Every target runs for the annotation's `maxDuration = "2s"`, not the advertised 5 minutes.
  - `JAZZER_FUZZ` is not a `Test` task input, so a cached `:anvil-api:test` result can be reused and no fuzzing happens at all; a second local run did exactly that.
  - No seed corpus or `<Class>Inputs/` regression files are committed, so the normal build runs each target on an empty input only.
- **Impact:** The fuzzing badge gives false assurance: the LZ4 decoder and region view builders (ADR-016's "fuzzable" rationale for the in-house decoder) have effectively never been fuzzed in CI.
- **Suggested next step:** One `@FuzzTest` per class (or one matrix entry per method with `--tests Class.method`); forward `jazzer.max_duration` with `systemProperty` and declare `JAZZER_FUZZ` as a task input (or `outputs.upToDateWhen { false }` in fuzz mode); seed corpora from the existing `.mca` test fixtures; commit any crash reproducers under `<Class>Inputs/`. The future Linear addon (ADR-077) shall carry its own fuzz target with a working setup.
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


<!-- Append new entries above this comment, ordered by priority (highest severity first). Resolved entries are deleted, not archived. -->
