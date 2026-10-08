# RTP Roadmap

**Scope:** This roadmap tracks known shortfalls in the current release and the concrete work planned
to address them, plus forward-looking features that are not driven by a known caveat. Version
anchor: `3.1.2` (see [`REQUIREMENTS.md`](REQUIREMENTS.md)).

Each item is expressed as a checklist line so completed work can be struck through with the
commit/ADR that closed it.

Tier ordering reflects priority, not chronology:

- **Tier 0** — must-ship items before `3.0.0` loses the `-beta.1` tag. *(All Tier 0 items complete; `3.0.0` shipped.)*
- **Tier 1** — directly narrows a caveat currently documented in `docs/publishing/FRONT_PAGE.md` or
  `docs/admin/`.
- **Tier 2** — new capability that earns a `3.1.0` / `3.1.x` release note.
- **Tier 3** — polish, long-tail, and infrastructure.

---

## Tier 0 — Release blockers for `3.0.0` final

- [x] ~~**Record current-release demo footage.** The "Historical Demonstrations" section on the
  front page is self-labelled as dated. Produce two ≤30s clips on `3.0.0-beta.1` showing (a) the
  Anvil pre-filter's effect on MSPT under Spigot, (b) queue saturation and per-player isolation on
  Folia. Replace the YouTube IDs in `docs/FRONT_PAGE.bbcode` and retitle the section back to
  "Performance Proofs & Demos".~~
- [x] ~~**Close out every `@Ignored` / `@Disabled` test.** Per project guidelines, releases must not
  ship with muted tests. Audit `rtp-core`, all platform adapters, and `rtp-plugin` before tagging.~~
- [x] ~~**Freeze the ADR set for 3.0.0.** Any architectural work started after `beta.1` either lands
  before the final tag or is deferred to a `3.1.0` ADR file. Mixed-state ADRs confuse external
  reviewers reading the repo top-down.~~
- [x] ~~**`CHANGELOG.md` Keep-A-Changelog pass.** The `3.0.0` entry should read as a release
  announcement, not a git log — grouped by Added / Changed / Fixed / Removed with operator-visible
  framing.~~

---

## Tier 1 — Caveat mitigations

Each subsection below matches a caveat that the front page currently admits. The goal for each is to
convert the caveat from a trust claim ("bounded in practice") to an observable one ("here is the
number, bound it yourself").

### 1.A — Spigot fallback: one on-tick `getChunkAt`

The Anvil pre-filter falls through to a live load only when the probe returns `UNKNOWN` (no region
file, unsupported data version, decode error, or un-populated chunk). On vanilla Spigot this costs
one on-tick chunk load per fallback.

- [x] ~~**Document un-populated-chunk warming as the operator remedy.** The `REQ-RTP-F-012`
  world-scan lifecycle (`start` / `pause` / `resume` / `reset` / `cancel`) populates every candidate
  on disk and collapses the fallback to near-zero for warmed regions. Write this up in
  `docs/admin/QUICK_START.md` as the recommended cold-start workflow.~~
- [x] ~~**Add a `--until-populated` convenience flag to the admin scan command** (if not already
  present) so the cold-start workflow is one command, not a cron job.~~
- [x] ~~**Tick-budget telemetry.** Surface a rolling counter — fallbacks/minute and total μs spent
  on the main thread per fallback — via `/rtp test full` and a dedicated stats subcommand. A
  `SpigotPrefilterStats` companion to `MemoryTracker` is the likely shape.~~
- [x] ~~**Optional "reject on unknown" mode.** Config flag (default off) that skips candidates when
  the probe returns `UNKNOWN` instead of paying the on-tick fallback. For operators who prefer a
  guaranteed zero main-thread cost over maximum throughput.~~

### 1.B — Folia fallback: one Region-Scheduler hop

On Folia, a confirmed candidate that the Anvil probe could not resolve pays one Region-Scheduler hop
to the authoritative live load.

- [x] ~~**Measure the hop cost.** Add a traceable timing test (`FoliaRegionHopTimingTest`, traceable
  to `REQ-RTP-NF-002`) that captures nanoseconds between Anvil-probe-complete and
  Region-Scheduler-ready on a representative candidate. Publish the p50/p95 numbers in the release
  notes; until then the caveat is unquantified.~~
- [x] ~~**Investigate hop amortization.** Can consecutive candidates within the same region share a
  single Region-Scheduler entry? If yes, implement and document with an ADR. If no, write the ADR
  explaining why — closing the question is as valuable as fixing it.~~

### 1.C — Un-populated chunks fall through to live load

- [x] ~~**Regression test: live-load safety net cannot re-admit a prior reject.** Assert that a
  chunk the Anvil probe rejected on populated data is never subsequently accepted by the live-load
  path. Must hold under Folia region-stealing and concurrent player teleports.~~
- [x] ~~**Failure attribution bucket.** Extend the `FailTypes` taxonomy (or the equivalent telemetry
  surface) with `unpopulatedFallthrough` so `/rtp test full` can report its frequency distinctly
  from other fallbacks. Currently invisible.~~

### 1.D — Fabric: supported (stable as of 2026-05-26; multi-platform expansion completed)

Fabric (`rtp-fabric`) and NeoForge (`rtp-neoforge`) are first-class, in-scope, stable platforms (ADR-022, ADR-033). All phases of the multi-platform expansion are completed and verified. Standing blockers documented in prior revisions are resolved; the front page and `REQUIREMENTS.md section 0` confirm full first-class support.

- [x] ~~**Resolve the three standing blockers during platform porting:**~~
  - [x] ~~**S-005 violation in `FabricRTPWorld.getChunkAt`.**~~ — `getChunkAt` returns
    `CompletableFuture<Long>` and routes through an async chunk-load path; S-005 compliant.
  - [x] ~~**Null stub in `FabricServerAccessor.getLocationGenerator`.**~~ — fully wired; throws
    `IllegalStateException` per `REQ-RTP-S-006` when called pre-init.
  - [x] ~~**Unresolved Loom dependency.**~~ — resolved via the obf/unobf carrier split
    ([rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)).
- [x] ~~**Update front-page / requirements wording.** `docs/FRONT_PAGE.bbcode` and `REQUIREMENTS.md
  section 0 Out of Scope` still describe Fabric as unsupported; reframe to first-class supported
  platform.~~
- [x] ~~**Re-run the `rtp-api` interface-sufficiency analysis** and record the result as an ADR
  (April 2026 gap analysis concluded interfaces are sufficient; promote the finding from
  the platform evaluation to an Accepted ADR).~~

### 1.E — Unsourced statistics on the front page

The Spatial Memory paragraph now cites a concrete ~45% Overworld-safe figure from a local profiling
pass on a vanilla 1.21 seed set; Nether and End ratios remain qualitative ("dominated by lava seas",
"almost entirely void"). The caveat has narrowed from "no numbers anywhere" to "one number, not yet
reproducible by readers".

- [x] ~~**Publish the reference profiling run** in `docs/admin/BENCHMARKS.md` — seed list, sample
  size, methodology, and per-dimension safe-fraction columns for Overworld / Nether / End. Until
  this exists, the ~45% figure is an author claim, not a reproducible one.~~
- [x] ~~**Extend the existing bStats integration with custom charts.** Default metrics are already
  wired (bStats ID `30865`, now sent by the in-tree `bstats-api` client per
  [bstats-api-ADR-001](../../api/bstats-api/docs/adr/bstats-api-ADR-001-dependency-free-client.md)); what is missing is `addCustomChart(...)` for RTP-specific
  aggregates — platform split, region count, queue depth, and observed safe-fraction histograms per
  dimension.~~
- [x] ~~**Replace the qualitative Nether/End phrasing** on the front page with measured figures once
  either path above lands.~~

---

## Tier 2 — Upcoming features (not caveat-driven)

- [x] ~~**Multi-entity subspace teleport addon (`LeafRTPGroupAddon`).**~~ Superseded and folded into
  the declarative scripted actions architecture ([ADR-093](../adr/ADR-093-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md))
  and implemented via `LeafRTPActionAddon` (`definitions/actions/*.yml`). Replaces separate
  search paths for party, duel, skirmish, and pursuit with a single relative subspace shape that
  captures spatial memory from the parent region for O(1) candidate selection.
- [x] ~~**Region-confinement (tether) addon (`LeafRTPTetherAddon`).**~~ Superseded and folded into
  the active scripted actions and confinement architecture ([ADR-093](../adr/ADR-093-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md),
  [ADR-095](../adr/ADR-095-subspace-anchor-providers-and-near-teleport-primitives.md)). Confinement
  is actively functional via the action engine (`confinement.enabled: true`, tether pull-back,
  `MemoryShape.contains` boundary enforcement) using `PlayerMoveDispatcher` (ADR-075); the stand-alone
  addon experiment is abandoned.
- [ ] **Visual region creation & interactive cartography bridges ([ADR-099](../adr/ADR-099-visual-region-creation-and-interactive-cartography-bridges.md)).**
  Eliminates manual coordinate entry for region setup via three zero-server primitives:
  - In-game selection bridge (`SelectionBridge` SPI) hooking existing WorldEdit/FAWE (`Polygonal2DRegion`, `CuboidRegion`) across Paper/Folia/Fabric/NeoForge, and FTB Chunks claim contours on modded (`/rtp region fromselection <name>`).
  - Zero-backend web map vector drawing tool (`leaf-rtp-map-tool.js`) generating one-click clipboard YAML snippets and in-game commands.
  - Cartography map item visual inspection (`maps-api` `RegionBoundaryRenderer` via `/rtp map create region_boundary`).
- [ ] **External web map raster layers ([ADR-086](../adr/ADR-086-external-web-map-integration.md)).**
  Zero-chunk-load on-demand raster heatmaps, coverage layers, and vector polygon overlays for Pl3xMap, BlueMap, and Dynmap.
- [ ] **Seamless loading-screen-free Velocity transfers ([rtp-proxy-ADR-021](../../platforms/rtp-proxy/docs/adr/rtp-proxy-ADR-021-seamless-in-play-velocity-transfers.md)).**
  Eliminate the vanilla Minecraft "Downloading terrain..." loading screen during cross-server transfers by swapping backend Netty pipes in `PLAY` mode and streaming pre-loaded chunks before origin teardown.
- [ ] **Adaptive tick-budget governor ([ADR-087](../adr/ADR-087-tick-budget-governor-and-per-tick-dispatch.md)).**
  Dynamic backpressure regulation and per-tick dispatch limits across Folia's regional ticking threads.
- [x] ~~**Platform-neutral player-move event SPI (core, D-005 gated).**~~ **Complete:**
  `PlayerMoveDispatcher` and `PlayerMoveEvent` landed in `rtp-api`, exposed via
  `RTPAPI.playerMoveEvents` and `RTPAPI.watchPlayerMove(...)` with per-player subscription scaling
  and comprehensive unit tests (`PlayerMoveDispatcherTest`). (ADR-075).
- [ ] **Investigate: spectator-during-wait (deferred, low priority).** Some competitor plugins place
  a player in spectator mode while a destination is resolved. This is a poor architectural fit here -
  the default model is search-and-serve-else-queue (destinations are prepared ahead of time, so there
  is no command-then-search "limbo" window to paper over) - and forced spectator carries real risk
  (fall-through, exploit windows, gamemode restoration on disconnect/crash). Kept as a future
  investigation only; if pursued at all it belongs as an optional effect, not a core behavior.
- [x] ~~**Config-file organization + discoverability (operator feedback).**~~ **Complete:**
  Delivered and superseded by [ADR-076](../adr/ADR-076-config-folder-consolidation.md) (consolidated
  7-object root layout with `definitions/` and `advanced/` doors), [ADR-071](../adr/ADR-071-config-organization-and-discoverability.md)
  (relevance ordering and per-file enums), [ADR-073](../adr/ADR-073-config-default-inheritance-references.md)
  (`@config` defaults inheritance), and the guided `/rtp admin setup` wizard suite.
  - [x] ~~**Order settings by common relevance.**~~ Everyday teleport knobs sort to the top of root files.
  - [x] ~~**Group all teleportation-related settings in one place.**~~ Consolidated via root files and `definitions/`.
  - [x] ~~**`messages.yml` reorganization.**~~ Split into `advanced/messages/{commands, network, placeholders, player, system}.yml` with full 12-locale co-located dotfile rename maps and green `LocaleParityTest` in CI.
  - [x] ~~**Constraints & migration.**~~ Non-destructive read-legacy-warn migration, dotfile rename maps (ADR-076), and setup wizard.
- [x] ~~**World-scan UX polish.** The admin lifecycle exists; operator affordances around it do not.
  Concretely: progress indication (both console and in-game bossbar), resume-across-restart
  semantics, and a per-region "warmth report" export. This is what converts the feature from
  *implemented* to *sellable*.~~ Resolved: the boss-bar progress indicator landed, resume-across-restart
  was already shipped (`ScanTask.save`/`loadProgress` + `Region` wiring), and the "warmth report" need
  is covered by the delivered learned-state inspector. See the sub-items below.
  - [x] ~~**In-game boss-bar progress indicator.** A `BossBar` (green, solid) is shown to all
    players with the `rtp.scan` permission while any world scan is active. The bar fills from 0
    to 1 as `latestAbsolutePos / latestAbsoluteTotal` across all active `ScanTask`s; the title
    is the configurable `scanBossBar` key in `messages.yml` (same placeholders as `scanStatus`:
    `[scan_regions]`, `[scan_chunks]`, `[scan_totalChunks]`, `[scan_cps]`,
    `[scan_landPercentage]`, `[scan_eta]`). Set `scanBossBar` to an empty string to disable.
    Bars are removed automatically when the scan finishes or is cancelled. Implemented in
    `ScanTaskProcessing` (Bukkit-family adapter); Fabric uses the existing console/chat path.~~
  - [x] ~~**Resume-across-restart semantics.**~~ Already shipped. `ScanTask.save()` persists scan
    progress (`scanIter`, `spatialResolution`, `currentOffset`, `isFine`, `scanPhase`; transient
    `GENSCAN` collapsed to `PRESCAN` on disk so a resumed scan skips the chunk-generation pre-pass)
    to `database/regionData/<region>_<cacheKey>.scan`, and `ScanTask.loadProgress()` reads it back.
    `Region` reschedules a partial scan at construction and again in `rebindWorld(...)` when
    `0 < iter < range`; the `cacheKey()` suffix (ADR-022) invalidates the resume on a config/seed
    change, and the `MemoryShape` bad-location bitmap survives the restart alongside it.
  - [ ] ~~Per-region "warmth report" export.~~ Dropped: the operator-facing "how warmed-up is this
    region" need is already met by the delivered learned-state inspector on `/rtp info
    region:<name>` (`[memCoveragePct]`, `[memBadPct]`, `[memBadCount]`, `[memTopCause]`,
    `[memTopCausePct]`) plus the auto-exported `database/regionData/debug/<region>.json` scan dump.
    No distinct "warmth report" artifact was ever specified; reopen with a concrete definition (e.g.
    per-region L1/L2/L3 queue depths + coverage % to JSON) if a separate export is actually wanted.
- [x] ~~**Persistent learned-state inspector.** A `/rtp memory dump <region>` subcommand producing a
  human-readable summary — flagged-bad sector count, coverage %, age of oldest entry, last-write
  timestamp. The H2/SQLite persistence is a front-page promise; inspection is the operator's
  confirmation.~~ Delivered as a per-region learned-state summary on the existing `/rtp info
  region:<name>` surface rather than a redundant dump command (raw runs are already auto-exported to
  `database/regionData/debug/<region>.json` on every scan). `MemoryShape.learnedStateSummary()`
  feeds new `regionInfo` placeholders `[memCoveragePct]`, `[memBadPct]`, `[memBadCount]`,
  `[memTopCause]`, `[memTopCausePct]`; documented in `docs/admin/COMMANDS.md`.
- [x] ~~**(v3.2) Region-sampled scan: region-major traversal + Hilbert-within-region.**~~ Superseded
  by the dual-layer spiral-addressed Hilbert model (ADR-085, ADR-088, CircleOptimizedDualLayer,
  SquareOptimizedDualLayer, SegmentedKeyRunTable). Compacts run tables and guarantees spatial
  cache locality without disrupting 1D spiral bijections.
- [x] ~~**Anvil PRESCAN accuracy measurement → conditional FULLSCAN retirement.**~~ Retired:
  the scan path in `ScanTask` has been streamlined into a single hybrid pass. Generated chunks are
  probed off-tick via Anvil (`.mca`, or an addon-registered format), while ungenerated chunks route to targeted on-demand
  generation without globally switching the scan phase or requiring an unconditional Pass 2 FULLSCAN
  sweep. S-001 safety remains authoritatively enforced at teleport selection time.
- [ ] **Chunky-driven generation pass for `/rtp scan` (near-term focus, D-005 + ADR gated).** Today
  the full-load sub-step of a scan forces generation on an Anvil miss one chunk at a time through the
  server chunk manager (`ScanTask.runFullLoadPath` → `RTPWorld.getOrLoadChunk`). When Chunky (or any
  bulk pre-generator) is present, drive/sequence *it* to lay the region's chunks down on disk first,
  then let scan walk the already-written `.mca` through the cheap off-tick Anvil PRESCAN path instead
  of paying per-chunk worldgen stalls. The throughput win comes entirely from the pre-generator's own
  speed (a faster — incl. future GPU/CPU-accelerated — Chunky build makes it strictly better); the
  RTP-side value is **UX/orchestration**, not raw generation speed. Design constraints:
  - **Soft-depend only, no hard dependency.** Reuse the existing Chunky integration seam
    (`ChunkyChecker` / `ChunkyRTPShape`) and route through `RTPHooks` per
    [ADR-026](../adr/ADR-026-external-hook-api-surface.md); degrade cleanly to the current
    generate-as-you-go full load when Chunky is absent. Do not put inline pre-generator calls in the
    scan pipeline.
  - **One command surface.** Let an admin kick off + size a Chunky pregen for a region and then run
    the scan consistently sized to it, instead of juggling `/chunky` and `/rtp scan` separately
    (progress display, auto-sizing scan to the pregen radius).
  - **Honest framing.** Advertise as a convenience/sequencing hook, never as "Chunky makes scan
    faster" — the speedup is a property of the installed Chunky build, available with or without the
    hook.
  - **Gating.** Crosses the external-integration surface and module boundaries → D-005 proposal +
    dedicated ADR (sibling to [ADR-026](../adr/ADR-026-external-hook-api-surface.md)) before
    implementation.
- [ ] **Accelerated Anvil verification compute (exploration, D-005 + ADR gated).** Offload the bulk
  biome/material rejection sweep over *decoded* `.mca` tiles to a high-throughput compute backend.
  The backend is deliberately **left open** — a native SIMD/vectorized pass, a GPU/OpenCL kernel,
  or an external accelerated generator/verifier (e.g. a standalone Rust worldgen+verify engine, the
  kind of project reportedly hitting ~17k cps on CPU alone) are all candidates; the headline is "go
  faster", not "use a GPU". This does **not** require LeafRTP to write a chunk generator: worldgen
  stays the server's / Chunky's job, and this only accelerates LeafRTP's own off-tick verification
  pass over already-written region files. Note that profiling shows off-tick scanning is bound by
  storage I/O and decompression; PCIe bus transmission overhead makes GPU/OpenCL offload
  economically unfavorable compared to host-side CPU vectorization (AVX-512/SIMD). Boundaries and
  gating:
  - The NBT/`.mca` decode stays on the CPU (branchy, I/O-bound; already parallelized across
    `AnvilIoPool`); only the decoded arrays are handed to the accelerated backend.
  - FULLSCAN / live-load paths cannot move (S-005 threading + server chunk manager).
  - Any accelerated path is a new subsystem with its own pool/contract; a GPU or external-process
    backend additionally introduces a hard external dependency surface (OpenCL runtime/driver, or
    an out-of-process engine + IPC) that must degrade gracefully to the current CPU path when
    absent — D-005 proposal + a dedicated ADR (sibling to
    [ADR-016](../adr/ADR-016-anvil-subsystem.md)) required before any implementation.
  - Profile first: only worthwhile if predicate evaluation (not NBT decode, not disk) is shown to
    be the bottleneck on large scans. The backend choice should follow that profile (a native
    vectorized pass may close the gap with none of the GPU/IPC dependency cost).
- [ ] **Safety-list grammar expansion.** The token grammar shipped in `3.0.0-beta.1` is the
  foundation; follow-ups:
  - [x] ~~Tag-group composition with set subtraction (`#minecraft:slabs - OAK_SLAB`).~~ — ADR-017 amendment (v3.3.0).
  - [x] ~~Numeric range predicates (`[level>=5]`) for fluids and light levels.~~ — ADR-017 amendment
    (2026-05-30); operators `>=`/`<=`/`>`/`<` with integer bounds, fail-open on absent/non-numeric
    live values.
  - [ ] Hot-reload on `safety.yml` file edit (currently requires `/rtp reload`).
  - [ ] Relative ground-distance predicate (`#minecraft:leaves[_groundDistance>3]`) so ground-level
    leaves/roots stay safe while tall-tree canopy is rejected. Design ADR accepted as Proposed:
    [ADR-059](../adr/ADR-059-relative-ground-distance-safety-predicate.md) — a synthetic,
    pipeline-injected `_groundDistance` block-state property consumed by the numeric-range grammar
    above; lazily computed (zero cost when unused), bounded probe
    (`safety.yml::groundDistanceMaxProbe`), fail-open, full-edition only.
- [x] ~~**Claim-plugin integration audit.**~~ **Complete:** Published
  [`docs/admin/CLAIM_PLUGIN_COMPATIBILITY.md`](../admin/CLAIM_PLUGIN_COMPATIBILITY.md) with complete
  compatibility and version matrices across all 18 claim checkers (16 Bukkit/Folia claim checkers, boundary providers,
  and 2 mod-side protection integrations: OPAC, FTB Chunks), verifying fail-closed execution, reflection safety,
  and Folia threading contracts.
- [ ] **CI matrix across platforms.** The Jenkinsfile builds, but `rtp test full` should run against
  Spigot + Paper + Folia + Fabric in parallel matrix form, even with mock servers where necessary.
  This is the step that converts `TRACEABILITY.md` from "documented" to "continuously enforced".
- [x] ~~**Addon-developer quickstart.** `docs/FOR_ADDON_DEVELOPERS.md` is linked from the front
  page, but a one-page *"register a custom shape in 20 lines"* tutorial is the document that
  actually drives third-party adoption.~~ Added [`docs/ADDON_QUICKSTART.md`](../ADDON_QUICKSTART.md)
  (Gradle dep + `ServiceLoader` descriptor + `RTPAddon.onLoad()` calling `RTP.addShape(...)`),
  linked from `FOR_ADDON_DEVELOPERS.md`.
- [x] ~~**Region-specific schematic (`.schem`) support.** Per-region arrival structures (small
  platform, lobby pad, arrival shrine) pasted at the chosen `RTPLocation` from a `.schem` file.~~
  Design ADR accepted: [ADR-058](../adr/ADR-058-region-specific-schematic-paste.md) (Amendment 1,
  2026-05-30: single cross-platform `.schem` format decoded in-house; native block-state paste; no
  WorldEdit hard-dependency; the `.nbt`-on-Fabric split is withdrawn). **Foundation landed:** the
  platform-neutral `SchematicPaster` SPI (`SchematicPaster`, `LoadedSchematic`, `SchematicSource`,
  `PasteOptions`, `PasteAnchor`, `PasteResult`, `NoOpSchematicPaster`) plus the swappable
  per-adapter `setSchematicPaster`/`getSchematicPaster` holder (mirroring `setBiomeGetter`) on
  `BukkitRTPWorld`, `FoliaRTPWorld`, `FabricRTPWorld`. **Decode + plan landed (Amendment 1):** the
  dependency-free `SpongeSchematicDecoder` (Sponge v2/v3) + `DecodedSchematic` + `BlockEntityData`,
  the platform-neutral `SchematicPlacementPlanner` (anchor math + air-skip),
  `AbstractFileSchematicPaster` base, and the shared
  `io.github.dailystruggle.rtp.api.block.BlockStateString` tokenizer extracted from
  `SafetyTokenParser` (ADR-017); pinned by `SpongeSchematicDecoderTest` +
  `SkyblockIslandFixtureTest` against the committed `skyblock_island.schem`. **Bukkit-family native
  paster landed:** `BukkitSchematicPaster` (native `Bukkit.createBlockData`, S-004 audited)
  installed at bootstrap via `AbstractServerAccessor` (inert until core invokes it). **Core wiring
  landed (no config knob - the presence of a file is the knob):** `RegionSchematicService` resolves
  `<pluginDir>/schematics/<region>.schem` (or `.schematic`) by file presence, and
  `TeleportPipelineTask` loads/decodes it off the region thread (`runLoad`) then pastes it at
  `SURFACE_CENTER` on the arrival region's owning thread (`runTeleport`, dispatched via
  `RTP.scheduler.runTask(location, ...)` for Folia region-safety) in place of the emergency
  platform, falling back to the platform whenever nothing is pasted (S-004); core reaches the active
  paster through a new instance accessor `RTPWorld.schematicPaster()` (default no-op, overridden on
  `BukkitRTPWorld`/`FoliaRTPWorld`/`FabricRTPWorld`). Verified end-to-end by
  `RegionSchematicServiceTest` against the committed `skyblock_island.schem`. **Also landed:** the
  Folia native paster (`BukkitSchematicPaster` installed by `AbstractFoliaServerAccessor`; the
  support gate keys on the underlying `org.bukkit.World` so `FoliaRTPWorld` is accepted); container
  block-entity restore (chest inventories rebuilt from the decoded `Items` NBT straight into the
  live tile); the `SURFACE_CENTER` anchor (drops the structure to a standable surface in one pass,
  no re-paste, keeping players inside roofed structures); and a decode cache in
  `AbstractFileSchematicPaster` (each `.schem` decoded once, cleared on `/rtp reload`).
  **Deferred follow-ups (tracked separately):** the footprint claim check (S-003) ahead of the paste;
  non-container block-entity NBT (sign text, custom data - still placed as empty blocks, audited);
  and a `docs/admin/` page plus traceability rows for the paste-on-region-thread regression test.
  The Fabric native paster landed in `3.1.2` via the platform-neutral `WorldBlockSchematicPaster`
  (`rtp-api`) driving `RTPWorld.setBlocks` / `restoreBlockEntities`; the deobf 26.x Fabric carriers
  implement `setBlocks` via `BlockStateParser` + `ServerLevel.setBlock` on the server thread
  (S-005-clean). The former `BukkitSchematicPaster` was removed and collapsed onto the same shared
  translator (`BukkitBlockWriter`).
- [x] ~~**Optional PvP / combat-tag check.** Optional pre-flight check that refuses (or delays)
  `/rtp` when the requesting player has recently taken or dealt PvP damage, so players cannot `/rtp`
  to escape mid-fight. Off by default.~~ Delivered via
  [ADR-055](../adr/ADR-055-pvp-combat-gate.md): a `PvPCombatStateRegistry` SPI + `PvPCombatAction`
  in
  `rtp-api`, the native `NativePvPCombatTracker`, and the `PvPGate` evaluator in `rtp-core`, gated
  by
  `safety.yml` knobs (`pvpCheckEnabled` default false, `pvpCombatTagSeconds`, `pvpOnCombat`
  DENY/DELAY, and a combat-state source preference) and the configurable `messages.yml#pvpInCombat`
  (REQ-RTP-F-013, with full locale parity). The gate is consulted at the `/rtp` pre-dispatch surface
  (`RTPCmd.compute`) and again in `TeleportPipelineTask` ahead of enrolment, fails open, and emits
  an
  S-004 audit on refusal. Soft-depend adapters for PvPManager / CombatLogX / Simple Combat Log
  (`PvPIntegrations` + per-plugin checkers) with a catalog row in
  [`EXTERNAL_HOOKS.md`](EXTERNAL_HOOKS.md) per
  [ADR-026](../adr/ADR-026-external-hook-api-surface.md). Tests: `PvPGateTest`,
  `NativePvPCombatTrackerTest`, `RTPCmdPvPGateTest`, `PvPCombatAdapterTest`.
- [x] ~~**NeoForge platform (`rtp-neoforge`).** First-class NeoForge support as a fifth platform
  family.~~ **Complete as of 2026-06-13 — NeoForge is a complete, runtime-functional, first-class
  platform.** Phase N0 (scope unlock, D-005 approval, [ADR-033](../adr/ADR-033-neoforge-platform-in-scope.md),
  [rtp-neoforge-ADR-001](../../platforms/rtp-neoforge/docs/adr/rtp-neoforge-ADR-001-platform-in-scope.md))
  complete 2026-06-01. Phase N1 module skeleton (`rtp-neoforge-common` + `rtp-neoforge-v1_21_R1`,
  `RTPNeoForgeMod`, `NeoForgeScheduler`, `NeoForgeCommandRegistrar`) landed 2026-06-02. All Phase N2
  steps (NA async chunk load, NB `getLocationGenerator`, NC scheduler, ND database, NE event bridge,
  NE-perf anvil parity, NF permissions, NG Brigadier command tree, NH stabilization + runtime smoke,
  NI book-menu + chat-prompt, NJ network-mode backend parity incl. live boot + reservation-token
  redemption, NK maps API + metrics binding + backend-state sampler) and Phase N3 (docs,
  traceability, beta release) are complete. See
  [ADR-033](../adr/ADR-033-neoforge-platform-in-scope.md) and [rtp-neoforge-ADR-001](../../platforms/rtp-neoforge/docs/adr/rtp-neoforge-ADR-001-platform-in-scope.md) for architectural records (multi-platform expansion completed and archived).

- [ ] **Rich book-menu drawing via resource-pack soft-dependencies.** The written-book menu renderer
  (Paper's `BookMenuRenderer`, Fabric's `FabricBookMenuRenderer` on the 1.21+ / deobf 26.x carriers)
  currently renders text, colour, and click/hover only. Native books cannot draw images; plugins
  like vBestiary achieve "images in a book" purely through resource-pack glyph fonts supplied by
  ItemsAdder / Nexo / Oraxen. Add optional soft-dependencies on those glyph providers and, when one
  is detected at runtime, conditionally substitute their custom-font glyphs (icons, dividers, region
  thumbnails) into the book menu fragments; when none is present, fall back to the current
  text-and-numbered rendering with no behaviour change. Scope notes: soft-depend only (no hard
  dependency, no bundled assets), catalog each provider in
  [`EXTERNAL_HOOKS.md`](EXTERNAL_HOOKS.md) per [ADR-026](../adr/ADR-026-external-hook-api-surface.md),
  and route detection through `RTPHooks` so the chat renderer and unsupported carriers degrade
  cleanly. Fabric 1.20.x stays on the chat renderer (book renderer off there for performance
  reasons) and therefore opts out of this feature.
- [x] ~~**Claim/faction-anchored RTP (`/rtp faction`-style).**~~ **Shipped:**
  - `ClaimBoundary`, `ClaimBoundaryProvider`, and `ClaimBoundaryRegistry` SPI in `rtp-api` and `DefaultRTPHooks`.
  - `ClaimAnchoredRegionTracker` in `rtp-core` with pinned center stability within claim boundaries, recomputation cooldowns, and cross-region hazard memory ingestion into `SubspaceShape`.
  - `AnchorSource.claimBoundary(...)` and `SubspaceAnchorResolver` integration.
  - `ActionManager` automated claim boundary resolution for `anchor: faction` / `anchor: claimboundary`.
  - `LeafRTPClaimAddon` v1.1.0 boundary providers for Towny Advanced, GriefPrevention, and SaberFactions/FactionsUUID.
- [x] ~~**Cross-platform destination-selector seam + bundled default menu.**~~ Shipped:
  `SelectionMenuBuilder` provides cross-platform curated selection pages (`/rtp menu world|region|biome|prefab`)
  in `rtp-core` delegating cleanly without inventory GUIs.
- [ ] **BetterRTP API compatibility shim (absorb the inventory-GUI menu ecosystem).** Inventory-GUI
  menu plugins (which capture more admin attention than book menus) are commonly built against
  BetterRTP. Rather than rebuild that ecosystem, RTP can register a stand-in for BetterRTP's public
  surface so those menu plugins transparently drive RTP. Design constraints:
  - **Absent-only, default off.** Register only when BetterRTP is not installed/enabled
    (`!isPluginEnabled("BetterRTP")`) to avoid command/service collisions; stand down completely if
    the real plugin is present. Never shadow BetterRTP.
  - **Thin adapter, correct module.** Translate BetterRTP's public entry points (its `/rtp [player]
    [world]` command forms and any Bukkit-Services API menu plugins call) into our existing `RTPCmd`
    / selection path. Lives in `rtp-plugin` (Bukkit-family) or an addon, never in `rtp-core` /
    `rtp-api`.
  - **Pin a documented subset.** Emulate only the API surface menu plugins actually call (player /
    world targeting, biome / price flags), not all of BetterRTP; document the emulated version.
  - **Permissions + honesty.** Map `betterrtp.*` permission checks onto our permission model (or
    honor both); be explicit in logs/metrics that RTP is serving the request, not BetterRTP.
  - **Catalog + ADR.** Third-party-accommodating surface: catalog in
    [`EXTERNAL_HOOKS.md`](EXTERNAL_HOOKS.md) per
    [ADR-026](../adr/ADR-026-external-hook-api-surface.md); emulating a competitor's API is a
    cross-module, D-005-gated decision requiring a dedicated ADR before implementation. Complements
    (does not replace) the selector seam + bundled menu item above.
- [x] ~~**BetterRTP parity: cooldown usage cap (`LockAfter` equivalent).**~~ Shipped:
  `UsageCapTracker`, `TeleportLimitStore`, `ConfigKeys.lockAfterUses`, and `ClearLimitCmd`.
- [x] ~~**BetterRTP parity: persist RTP destination as a permanent spawn anchor (`SetAsRespawn`
  equivalent).**~~ Shipped: `ConfigKeys.setAsRespawn`.
- [x] ~~**BetterRTP parity: widen built-in claim-plugin coverage.**~~ **Complete:** LeafRTP ships 16
  claim integrations in `addons/LeafRTPClaimAddon` (`ClaimIntegrations`: SaberFactions, FactionsBridge,
  GriefDefender, GriefPrevention, Lands, RedProtect, Residence, CrashClaim, HuskClaims, HuskTowns,
  PlotSquared, KingdomsX, TownyAdvanced, WorldGuard, UltimateClaims, MinePlots) with bidirectional `ClaimBoundaryRegistry` anchor support.
- [ ] **Locale coverage expansion (close the gap with BetterRTP, weighted by real server traffic).**
  RTP ships 12 parity-enforced locales (`en`, `de`, `es`, `fr`, `it`, `ja`, `ko`, `nl`, `pl`, `pt`,
  `ru`, `zh`) plus the `cat` novelty dialect, against BetterRTP's ~21 community-contributed locales.
  RTP's depth advantages stand (re-keying config keys via `<file>.lang.yml` rename maps across every
  config file, and CI-enforced parity via `LocaleParityTest`), so this item is purely about breadth.
  Weighting candidate languages by two observed "Server Location" distributions (rather than raw
  count or global speaker totals) shows RTP already covers ~70-82% of measurable server traffic with
  its 12 locales, and the whole gap to BetterRTP (4-6 points) is driven by the same short list:
  - **Add the highest-value missing locales:** `vi` (Vietnamese), `tr` (Turkish), `cs` (Czech), and
    `fi` (Finnish - the single largest language *neither* plugin ships, ~3.4% of servers in one
    sampled distribution). Lower-priority follow-ups: `id` (Indonesian), `th` (Thai), and splitting
    `zh` into Simplified/Traditional if demand warrants.
  - **Caveat (do not over-trust server-location data):** location charts measure where the *server*
    is hosted, not where players/admins are. Remote/cloud-hosted communities (Korea is the clearest
    example - large player base, frequently hosted in Singapore/Japan/US hubs or folded into "Other")
    are systematically undercounted, so `ko` and similar locales carry latent value the charts cannot
    see. Treat the per-distribution weighting as a *floor* on a language's value, not the value
    itself; do not drop or deprioritize an existing locale on the strength of a location chart alone.
  - Each new locale follows the co-located dotfile rename-map workflow (ADR-076), must pass
    `LocaleParityTest`, and should prefer native-speaker review over machine translation per
    `TRANSLATION_GUIDE.md`. No architecture change; pure content + parity work.
- [x] **Foreign config importer (`rtp config import <plugin>`, one-shot migration aid).** Design
  settled in [ADR-066](../adr/ADR-066-foreign-config-importer.md) (Accepted, D-005). Lower the
  switching cost for operators moving off a competitor by translating its on-disk config into RTP's
  config tree. This is a *migration aid*, not the live API shim above: it reads the competitor's YAML
  once, writes our files, and then RTP owns the config. A generic `ConfigImporter` seam (in `rtp-core`,
  since file-only reading via the in-house `RtpYamlConfig` parser is platform-neutral) with BetterRTP
  as the first source and EzRTP / JakesRTP to follow; `rtp config import` with no argument auto-detects
  when exactly one source's files are present. Design constraints:
  - **Explicit, non-destructive, dry-run-first.** Trigger via an explicit `rtp config import <plugin>`
    (never silent auto-overwrite on startup); `rtp config import` with no argument auto-detects when
    exactly one source is present, else lists candidates. Default to a preview that lists every mapped
    key, every approximation, every deferred mapping, and every dropped key; only write on a `confirm`
    second phase (parallel to `rtp prefab` `apply`/`confirm`). Back up any RTP file we touch (reuse the
    `prefab` `<file>.yml.bak.<epochMillis>` mechanism). Refuse to clobber a customized RTP config
    without confirmation.
  - **No hard dependency.** Parse the competitor's YAML straight off disk via the in-house
    `RtpYamlConfig` parser; do not link competitor classes or soft-depend on the plugin being
    installed. Because file-only reading is platform-neutral, the `ConfigImporter` seam lives in
    `rtp-core`. Target each competitor's latest config schema.
  - **Honest, lossy translation.** Competitors and RTP model regions differently; classify each key
    `MAPPED` / `APPROXIMATED` / `DEFERRED` / `DROPPED` and log every approximation rather than implying
    parity. Known **clean** (`MAPPED`) mappings: BetterRTP `Shape: square` → RTP `SQUARE` shape (RTP
    ships both square and circle); arbitrary outlines → `Polygon` (ADR-034) vertices (`APPROXIMATED`);
    `CenterX`/`CenterZ`, `MaxRadius`/`MinRadius` → region center + radius/minRadius; `Cooldown`/`Delay`
    → `teleportCooldown`/`teleportDelay` (seconds, 1:1); `MaxAttempts` → `performance.yml#maxAttempts`
    (1:1, same default 32); `PreloadRadius` → `performance.yml#viewDistanceSelect`/`viewDistanceTeleport`;
    per-world enable list → worlds/regions; `Price`/economy → `economy.yml`; biome/block blocklists →
    `safety.yml` filters.
  - **Parity-dependent keys (`DEFERRED`, not dropped).** `SetAsRespawn` (persistent spawn anchor) and
    `LockAfter` (cooldown usage cap) map onto RTP parity features that are planned but not yet landed
    (their own ROADMAP items above). Until each target ships, the importer reports the key as
    `DEFERRED` ("recognized, target not yet available"); when the parity feature lands, flip its row
    from `DEFERRED` to `MAPPED` in the same change.
  - **D-005 + ADR.** Design settled in
    [ADR-066](../adr/ADR-066-foreign-config-importer.md) (Proposed): generic `ConfigImporter` seam
    with BetterRTP first, EzRTP / JakesRTP to follow.

- [x] ~~**3D Archimedean helix coordinate generator (1.18+ vertical biome targeting).**~~ **Declined / Redundant:**
  Closed as redundant. Mathematical analysis proved that coupling a vertical pitch term $y_n$ to the 1D spiral
  index segments biomes into thin helical ribbons (causing coverage loss), whereas stacked $[minY, maxY]$ regions
  provide full horizontal coverage at zero architectural complexity. Vertical interval skipping without chunk loads
  is already cleanly handled off-tick by the Anvil probe fast-path (`VerticalAdjustor.adjustFromProbe`).
- [x] ~~**"Virtual Rift" warmup effects addon (`addons/LeafRTPRiftAddon`).**~~ **Complete:**
  Shipped as a standalone, platform-neutral reference addon in `addons/LeafRTPRiftAddon` (`RTPRiftAddon`,
  `RiftEffect`). Registers the `RIFT` effect prototype (`RIFT.<radius>.<seconds>`) with `EffectFactory` in
  `effects-api`, installing an out-of-the-box `effects/rift.yml` demo group. Carves client-side fake air,
  draws a portal gateway lower shell and obsidian catch-bowl, and snaps the world back on teleport completion or cancellation.
- [ ] **Adaptive queue demand scaler (in-memory, zero-config).** Track the rate of change of queue
  consumption (dQ/dt) and player connection frequency in-memory using an exponentially-weighted
  moving average. When a surge is detected, dynamically scale up `AnvilIoPool`'s thread count (via
  `ThreadPoolExecutor.setMaximumPoolSize`) and expand `backlogCacheCap` on-the-fly; throttle back
  when command velocity returns to baseline. No new dependencies - `AnvilIoPool` is already a
  documented carve-out from the raw-executor prohibition ([ADR-016](../adr/ADR-016-anvil-subsystem.md))
  and `MetricsBinding` already captures queue depth and TPS data the scaler would consume. The scaler
  reads from `RTP.metrics` and writes only to the pool size and the existing `backlogCacheCap` knob;
  all periodic work routes through `RTP.scheduler` (no raw threads). Requires a D-005 proposal before
  implementation; the EWMA window and floor/ceiling pool sizes must be configurable with safe defaults.
- [ ] **LeafRTP engine workspace setup panel ([ADR-104](../adr/ADR-104-ephemeral-web-editor-and-packed-docs-integration.md)).**
  Append a visual interactive Setup Wizard panel to the browser-based LeafRTP Web Workspace / ephemeral editor
  (`docs/editor/`) that achieves full feature and workflow parity with the in-game `/rtp admin setup` command
  wizard ([ADR-038](../adr/ADR-038-rtpadmin-setup-wizards.md)). Operators can configure first-run or ongoing
  server topologies through a guided multi-stage UI:
  - **5-Stage Setup Parity:** Replicate stages 1–5 (Stage 1: World Topology single vs. multi-world auto-expansion; Stage 2: Gameplay Style templates survival / arena / skyblock / oneblock; Stage 3: Performance Profile low / high / folia-tuned; Stage 4: Addon & Effect toggles claim integrations, economy, and cinematic effects; Stage 5: Dry-Run Diff Preview & Atomic Apply).
  - **Prefab & Recipe Compilation:** Compose user answers into the identical ordered sparse `Prefab` recipe overlay pipeline as `SetupRecipe`, rendering live cumulative deltas across `performance.yml`, `safety.yml`, and `definitions/regions/*.yml` in the editor's Staging Diff Inspector before commit.
  - **Dual Commit Path:** Support both direct WebSocket hot-apply and offline token-based command fallback (`/rtp editor apply token=<token>`) with server-side validation against geometry invariants (ADR-034) and automated non-destructive `.bak` file backups.
- [ ] **Shared spatial memory across servers.** Let backends that serve the same world share what
  each one has learned about it (rejected segments and their causes, biome results) through the
  SQL / Redis backends, so a scan or a rejection on one server saves the others the same work. Only
  valid between servers whose world files match; the design must say how that is detected and how
  concurrent writes merge. Requires a D-005 proposal and an ADR before implementation. Until it
  ships, the front page shall not describe spatial memory as shareable.

---

## Tier 3 — Polish and long tail

- [ ] **Pre-release runtime verification of `/rtp back`.** Command implementation is landed in `rtp-core` (`BackCmd` with unit coverage in `BackCmdTest`), but requires runtime verification across live platform adapters and multi-server devstack before release (origin coordinate toggling, cross-server network mode target return, permission gating on `rtp.back`, and cooldown tracking).
- [ ] **Standalone `rtp-anvil` publication.** The module is genuinely reusable outside RTP (any
  plugin wanting off-tick region-file reads could depend on it). If pursued, add a Maven Central
  publish target and write the "why this lives alone" ADR.
- [ ] **`SECURITY.md` audit before release traffic hits.** Confirm contact address, disclosure
  window, and scope are all current.
- [ ] **Glossary hygiene.** Now that Fabric is an in-scope, supported platform (see 1.D), audit
  `docs/dev/GLOSSARY.md` and the Multipurpose Terms table for platform-specific terms that now need
  disambiguation (`Region` means different things on Folia and Fabric).
- [ ] **`README.md` shapes section refresh.** The `user-images.githubusercontent.com` URLs for the
  shape-distribution plots are pinned to a legacy account hash; re-host in the repo's own
  `docs/img/` to survive future GitHub UI changes.
- [ ] **Retire `.bak` files after every release.** Project policy is to keep `.bak` copies during
  in-flight edits only; a release tag is a natural cleanup checkpoint.

---

## How to update this document

- Completed items: do **not** delete the line. Strike it through and append the commit short-SHA or
  ADR number that closed it. This preserves the velocity signal.
- New caveats discovered between releases: add under the appropriate Tier 1 subsection and mirror
  it in the Caveats or FAQ section of `docs/publishing/FRONT_PAGE.md` (then rebuild with
  `scripts/release/build_front_pages.py`) as a plain, non-technical summary (no REQ-*, no ADR
  identifiers — those stay in this document).
- Features requested by operators: land in Tier 2 with the requesting issue linked, or get
  explicitly declined with a one-line rationale.
- This file is the only TODO source of truth for release planning. Do not duplicate it into
  CHANGELOG or per-module requirements.


