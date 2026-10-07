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


### 2026-10-05 — Release workflows interpolate step outputs and inputs into shell scripts

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (CI/release)
- **Location:** `.github/workflows/release-bbb.yml` lines ~111, ~168 (`TAG="${{ steps.version.outputs.tag }}"`); `release.yml` lines ~177, ~233, ~329-333; `fuzzing.yml` line ~50 (`inputs.duration`); `gradle.yml` lines ~100-110 (`github.base_ref`)
- **Symptom / hypothesis:** The version comes from a loosely matched branch name (`^[vV]([0-9]+\.[0-9]+.*)$`) or a dispatch input and is template-substituted into later `run:` blocks, so `$(...)` or quotes in it execute.
- **Impact:** Runner code execution in jobs that hold publish and signing secrets. Practical exposure is limited: fork PRs get no secrets and a read-only token, and dispatch needs write access. Hardening rather than an open hole.
- **Suggested next step:** Validate the version against `^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$` and fail otherwise; pass outputs and inputs only via `env:` and expand as `"$VAR"`.
- **Linear:** RTP-11

### 2026-10-05 — Release jobs can publish partial or unsigned releases

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (CI/release)
- **Location:** `.github/workflows/release-bbb.yml` lines ~107-120 (tag pushed before the build at ~137); `release.yml` lines ~123-129 (missing `.asc` only logged); `scripts/release/update_modrinth_description.py` ~78-81, `update_hangar_description.py` ~117-120 (missing token exits 0)
- **Symptom / hypothesis:** A failed Pro build leaves a public `v*` tag behind. A missing signing key still publishes the Lite jar to GitHub, Modrinth and Hangar. A missing marketplace token silently skips that channel.
- **Impact:** Unverifiable or inconsistent releases across channels; manual cleanup of orphan tags.
- **Suggested next step:** Push the tag only after build and upload succeed; fail when `${LITE_JAR}.asc` is missing (as `maven-central.yml` does); check required tokens at job start (`: "${HANGAR_TOKEN:?}"`).
- **Linear:** RTP-7


### 2026-10-06 — Claim boundary providers disable on any error and under-cover large or irregular claims

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (claim addon boundary providers)
- **Location:** `addons/LeafRTPClaimAddon/.../TownyBoundaryProvider.java` ~149, `FactionsBoundaryProvider.java` ~152 and ~181-250, `GriefPreventionBoundaryProvider.java` ~57-64 and ~91, `AdaptiveClaimProber.java` ~18-49
- **Symptom / hypothesis:** Any `Throwable` sets `exists = false` for the session (the checker fail-open pattern fixed earlier). The prober samples only the four axes through the hit and stops at 512 blocks, so L-shaped or large claims are truncated. Towny keys town blocks as 16-block chunks and ignores a non-default town block size. Factions `getBoundaryAt` excludes only wilderness, so a SafeZone/WarZone hit enumerates every claim reflectively with no cap. GriefPrevention anchors a player to the first claim in the world. Providers call Towny/GP/Factions/Bukkit APIs from async completion threads (the same pattern as the existing checkers).
- **Impact:** Availability and placement quality, not S-003: per-location claim checkers still veto every final destination. Effects: claim hazards under-marked (more rerolls), claim anchors silently empty after one error, wrong-claim anchors, and long reflective scans on large system factions.
- **Suggested next step:** Disable only on linkage errors (reuse `ClaimCheckFailure`); flood-fill claimed chunks with a cap instead of axis probing; read Towny's town block size; skip or locally bound SafeZone/WarZone; prefer the claim containing the player.
- **Linear:** RTP-15

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

### 2026-10-06 — YAML depth, modded-platform startup noise, API enum additions, devstack leftovers

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (yaml-api, API surface, devstack run)
- **Location:** `api/yaml-api/.../RtpYamlReader.java` (`parseMappingBody` / `parseChildBlock` / `FlowParser`), `RtpYamlWriter.emitScalar` PLAIN branch; `platforms/rtp-neoforge/.../utils/NeoForgeJarUtils.java` ~78; `addons/LeafRTPGuiAddon/rtp-gui-common/.../RTPGuiCommonAddon.java` ~188; `rtp-api` `RtpTarget.Kind`, `RTPResult.Reason`, `RTPAPI.checkPermission`; `devstack/run-acceptance.ps1` ~837-846 (`Test-Gui`)
- **Symptom / hypothesis:** The YAML reader has no nesting-depth or size cap (a deeply nested or `[[[[...` document from an import or a trusted editor apply throws `StackOverflowError`), and the writer emits PLAIN scalars verbatim (section setters already force DOUBLE). NeoForge cannot extract the bundled docs ("URI scheme is not file" from the union filesystem). On Fabric 1.21.x the GUI addon's 26.x-only renderer logs a `NoClassDefFoundError` stack trace although it is caught and skipped. New enum constants (`COORDINATE`, `ACTION`, `NO_PERMISSION`) break addons that switch over them without a default; every other 3.2.1 API change is additive (final classes with private constructors and factories, no new abstract methods). `RTPAPI.checkPermission` returns `false` before init instead of throwing (S-006 style). The GUI acceptance step copies the 26.x-only `LeafRTPGuiAddon.jar` into the 1.21.x `backend-c`/`backend-d` `mods/` folders, where Fabric Loader and FML abort the server; the stale copies were renamed `*.disabled-by-audit` on 2026-10-06.
- **Impact:** Thread death on a pathological config; no offline docs on NeoForge; alarming log line on Fabric 1.21.x; addon compatibility caveat for the release notes; devstack runs fail on the modded backends after any GUI run.
- **Suggested next step:** Thread a depth counter (max ~128) and a byte cap through the YAML reader; read docs through `Files`/`FileSystem` instead of `new File(uri)`; log the skipped renderer at FINE without a stack trace; mention the enum additions in the CHANGELOG; only stage the GUI jar into `mods/` for 26.x backends.
- **Linear:** RTP-26

### 2026-10-06 — Action and GUI polish: bundled definitions, hardcoded text, dead settings

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (action and GUI addons)
- **Location:** bundled `addons/LeafRTPActionAddon/.../actions/*.yml`; `ActionCommand.java` ~202/280/285, `ActionCancelCmd.java` ~101-158, `ActionSubCmd.java` ~59-77; `ActionManager.processWaitQueue` ~462-483; `rtp-gui-common/.../MenuIcons.java` ~116, `MenuModel.java` nav/operator labels, `GuiMenuConfig.rows()` ~107; Fabric/NeoForge `DestinationPickerMenu` filler ~96/130; `FabricMenuRenderer` ~172/184; `RTP.getTargetStatus` ~903-912 and `getAllowedTargets` ~681-723
- **Symptom / hypothesis:**
  - Bundled definitions: titles are `PLAYER:` commands, so non-op players never see them (`/title` needs op); `broadcast` is not a vanilla command; teams has no win condition, and the first death sets every participant back to survival.
  - Action command failures, cancel results and raw internal reasons ("Revalidation error: ...") are hardcoded English (S-007). After a reload, renamed aliases keep pointing at the old definition; `alias: cancel` replaces the built-in subcommand; `/rtp action cancel [session_id]` is documented but not accepted. A single queued entry is never re-evaluated alone, and one player can queue twice.
  - GUI: `textReady`/`textUnavailable` are never used (lore is hardcoded), status shows raw enum names, nav and operator labels are hardcoded, `menuRows` has no effect, a mistyped filler fills slots with compasses on Fabric/NeoForge, and Fabric logs two INFO lines per open. Buttons show READY for players locked out by `lockAfterUses` or already teleporting, and the biome page offers biomes the blacklist excludes.
- **Impact:** Cosmetic or confusing behaviour on the features advertised for 3.3.0; no safety or security impact.
- **Suggested next step:** Use `CONSOLE: title` and `say`/`tellraw` in the bundled files and add a team win check; move strings to `messages.yml`/`guimenu.yml` with locale parity; drop stale aliases and reserve built-in names; report lock-out in `getTargetStatus` and honour `biomeWhitelist` in `getAllowedTargets`.
- **Linear:** RTP-25

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


### 2026-10-05 — Editor apply parser is structure-blind and has no file-count cap

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (web editor)
- **Location:** `rtp-core/.../commands/editor/EditorSessionManager.java` lines ~1244-1267 (`indexOf("\"files\"")`), `parseJsonStringMap`; `ApplyCmd` offline drop read
- **Symptom / hypothesis:** The byte-store, session and offline apply paths locate `files` by substring rather than parsing the JSON root. They have no `MAX_FILES` or per-file budget (the channel path caps at 256), and the offline `editor/<token>.json` is read with no size cap. The embedded `sha256` is self-declared, so it guards only against corruption.
- **Impact:** An admin applying a hostile token can trigger thousands of YAML parses, `.bak` writes and a reload. Path checks still hold, so there's no write outside the allow-list.
- **Suggested next step:** Parse the root with `EditorLoopbackJson`, take `files` from the root object only, and apply the channel caps (256 files, 4 MiB) to every apply path.
- **Linear:** RTP-30

### 2026-10-05 — `trusted-editors.json` is not owner-only and concurrent writers drop entries

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (web editor)
- **Location:** `rtp-core/.../commands/editor/channel/TrustedEditors.java` lines ~154-184; new instance per channel in `EditorChannelWiring.trusted()`
- **Symptom / hypothesis:** The trust file is written without `EditorKeys.restrictToOwner`, unlike the channel keys. Each open channel holds its own in-memory copy and rewrites the whole file, so the last writer wins.
- **Impact:** On shared hosts another local account could pre-trust a browser key (defense in depth; write access to the data folder is already strong). Two concurrent sessions can erase each other's trust, which brings back the prompt.
- **Suggested next step:** Apply `restrictToOwner` to the temp file and the target; use a process-wide instance, or reload and merge under a lock before each write.
- **Linear:** RTP-27

### 2026-10-05 — Small lifecycle and bounds gaps from the v3.3.0 audit

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (bStats, commands, editor, CI)
- **Location:** `rtp-core/.../metrics/bstats/RtpBStatsCatalogue.java` ~159, ~480-494 and `RtpBStats.shutdown`; `commands/docs/DocsExportSubCmd.java` ~61-69; `commands/menu/MenuRedeemSubcommand.java` ~3074-3075; `commands/editor/BiomeBinCodec.java` ~145-155 / `WorldBiomeStore` palette; `visualization/ComprehensiveRegionImageExporter.java` ~115-117; `.github/workflows/fuzzing.yml` ~59 and `mutation-testing.yml` ~52; `devstack/docker-compose.yml` ports; `FabricServerAccessor` / `NeoForgeServerAccessor` `executeCommandWithCapture` (10 s timeout); `RTP.java` `NO_PERMISSION` results
- **Symptom / hypothesis:** The bStats cost sampler is never cancelled and its static handle blocks rescheduling after a reload. Docs export runs on `CompletableFuture.runAsync` rather than `RTP.scheduler`. `dispatchRun` treats a null permission probe as allow-all. The biome palette and `histogram` grow with the largest biome index. Export filenames embed the unsanitized `region.name`. Two `upload-artifact@v4` refs are unpinned. Devstack publishes an open Redis and an offline-mode proxy on all interfaces. On Fabric/NeoForge a capture that times out still runs later and appends to the caller's list after the caller has moved on. The API's `NO_PERMISSION` failure text (`Missing permission for <target>`) is hardcoded English and shown verbatim by the GUI addon (S-007-adjacent).
- **Impact:** Individually minor: reload leaks, a rule violation, latent fail-open paths, admin-only path oddities, and supply-chain/dev-host hygiene.
- **Suggested next step:** Cancel and null the sampler in shutdown; use `RTP.scheduler`; fail closed on a null probe; cap the palette; sanitize export names to `[A-Za-z0-9._-]`; pin the SHAs; bind devstack ports to `127.0.0.1`; drop late capture output once the caller has timed out; give `NO_PERMISSION` a configurable message key.
- **Linear:** RTP-29

<!-- Append new entries above this comment, ordered by priority (highest severity first). Resolved entries are deleted, not archived. -->
