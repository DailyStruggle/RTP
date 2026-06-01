# RTP Roadmap

**Scope:** This roadmap tracks known shortfalls in the current release and the concrete work planned
to address them, plus forward-looking features that are not driven by a known caveat. Version
anchor: `3.0.0-beta.1` (see [`REQUIREMENTS.md`](REQUIREMENTS.md)).

Each item is expressed as a checklist line so completed work can be struck through with the
commit/ADR that closed it.

Tier ordering reflects priority, not chronology:

- **Tier 0** — must-ship items before `3.0.0` loses the `-beta.1` tag.
- **Tier 1** — directly narrows a caveat currently documented in `docs/FRONT_PAGE.bbcode` or
  `docs/admin/`.
- **Tier 2** — new capability that earns a `3.1.0` release note.
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

### 1.D — Fabric: supported (stable as of 2026-05-26)

Fabric (`rtp-fabric`) is a first-class, in-scope, stable platform. The three standing blockers
documented in prior revisions of this section are resolved; the front page and `REQUIREMENTS.md §0`
should be updated to reflect that Fabric is supported, not out of scope.

- [x] ~~**Resolve the three standing blockers in `MULTI_PLATFORM_PLAN.md`:**~~
  - [x] ~~**S-005 violation in `FabricRTPWorld.getChunkAt`.**~~ — `getChunkAt` returns
    `CompletableFuture<Long>` and routes through an async chunk-load path; S-005 compliant.
  - [x] ~~**Null stub in `FabricServerAccessor.getLocationGenerator`.**~~ — fully wired; throws
    `IllegalStateException` per `REQ-RTP-S-006` when called pre-init.
  - [x] ~~**Unresolved Loom dependency.**~~ — resolved via the obf/unobf carrier split
    ([rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)).
- [x] ~~**Update front-page / requirements wording.** `docs/FRONT_PAGE.bbcode` and `REQUIREMENTS.md
  §0 Out of Scope` still describe Fabric as unsupported; reframe to first-class supported
  platform.~~
- [x] ~~**Re-run the `rtp-api` interface-sufficiency analysis** and record the result as an ADR
  (April 2026 gap analysis concluded interfaces are sufficient; promote the finding from
  `MULTI_PLATFORM_PLAN.md` to an Accepted ADR).~~

### 1.E — Unsourced statistics on the front page

The Spatial Memory paragraph now cites a concrete ~45% Overworld-safe figure from a local profiling
pass on a vanilla 1.21 seed set; Nether and End ratios remain qualitative ("dominated by lava seas",
"almost entirely void"). The caveat has narrowed from "no numbers anywhere" to "one number, not yet
reproducible by readers".

- [x] ~~**Publish the reference profiling run** in `docs/admin/BENCHMARKS.md` — seed list, sample
  size, methodology, and per-dimension safe-fraction columns for Overworld / Nether / End. Until
  this exists, the ~45% figure is an author claim, not a reproducible one.~~
- [x] ~~**Extend the existing bStats integration with custom charts.** Default metrics are already
  wired (`RTPBukkitPlugin` → bStats ID `30865`, relocated `org.bstats` →
  `io.github.dailystruggle.rtp.bstats`); what is missing is `addCustomChart(...)` for RTP-specific
  aggregates — platform split, region count, queue depth, and observed safe-fraction histograms per
  dimension.~~
- [x] ~~**Replace the qualitative Nether/End phrasing** on the front page with measured figures once
  either path above lands.~~

---

## Tier 2 — Upcoming features (not caveat-driven)

- [ ] **World-scan UX polish.** The admin lifecycle exists; operator affordances around it do not.
  Concretely: progress indication (both console and in-game bossbar), resume-across-restart
  semantics, and a per-region "warmth report" export. This is what converts the feature from
  *implemented* to *sellable*.
- [x] ~~**Persistent learned-state inspector.** A `/rtp memory dump <region>` subcommand producing a
  human-readable summary — flagged-bad sector count, coverage %, age of oldest entry, last-write
  timestamp. The H2/SQLite persistence is a front-page promise; inspection is the operator's
  confirmation.~~ Delivered as a per-region learned-state summary on the existing `/rtp info
  region:<name>` surface rather than a redundant dump command (raw runs are already auto-exported to
  `database/regionData/debug/<region>.json` on every scan). `MemoryShape.learnedStateSummary()`
  feeds new `regionInfo` placeholders `[memCoveragePct]`, `[memBadPct]`, `[memBadCount]`,
  `[memTopCause]`, `[memTopCausePct]`; documented in `docs/admin/COMMANDS.md`.
- [ ] **Safety-list grammar expansion.** The token grammar shipped in `3.0.0-beta.1` is the
  foundation; follow-ups:
  - [ ] Tag-group composition with set subtraction (`#minecraft:slabs - OAK_SLAB`).
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
- [ ] **Claim-plugin integration audit.** The front page lists seven integrations. Audit each
  against current upstream releases (Factions forks, GriefDefender 2.x, Lands 7.x, HuskTowns 3.x,
  TownyAdvanced 0.x, WorldGuard 7.x, GriefPrevention 16.x) and publish
  `docs/admin/CLAIM_PLUGIN_COMPATIBILITY.md` with per-plugin version matrices. At least one
  integration is almost certainly lagging.
- [ ] **CI matrix across platforms.** The Jenkinsfile builds, but `rtp test full` should run against
  Spigot + Paper + Folia (and eventually Fabric) in parallel matrix form, even with mock servers
  where necessary. This is the step that converts `TRACEABILITY.md` from "documented" to
  "continuously enforced".
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
  **Deferred follow-ups (tracked separately):** the footprint claim check (S-003) ahead of the paste; the Fabric native paster
  (`BlockArgumentParser` through the obf/unobf carrier per
  [rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md));
  non-container block-entity NBT (sign text, custom data - still placed as empty blocks, audited);
  and a `docs/admin/` page plus traceability rows for the paste-on-region-thread regression test.
- [x] ~~**Optional PvP / combat-tag check.** Optional pre-flight check that refuses (or delays)
  `/rtp` when the requesting player has recently taken or dealt PvP damage, so players cannot `/rtp`
  to escape mid-fight. Off by default.~~ Delivered via
  [ADR-055](../adr/ADR-055-pvp-combat-gate.md): a `PvPCombatStateRegistry` SPI + `PvPCombatAction`
  in
  `rtp-api`, the native `NativePvPCombatTracker`, and the `PvPGate` evaluator in `rtp-core`, gated
  by
  `safety.yml` knobs (`pvpCheckEnabled` default false, `pvpCombatTagSeconds`, `pvpOnCombat`
  DENY/DELAY, and a combat-state source preference) and the configurable `messages.yml#pvpInCombat`
  (locale TSV pipeline, REQ-RTP-F-013). The gate is consulted at the `/rtp` pre-dispatch surface
  (`RTPCmd.compute`) and again in `TeleportPipelineTask` ahead of enrolment, fails open, and emits
  an
  S-004 audit on refusal. Soft-depend adapters for PvPManager / CombatLogX / Simple Combat Log
  (`PvPIntegrations` + per-plugin checkers) with a catalog row in
  [`EXTERNAL_HOOKS.md`](EXTERNAL_HOOKS.md) per
  [ADR-026](../adr/ADR-026-external-hook-api-surface.md). Tests: `PvPGateTest`,
  `NativePvPCombatTrackerTest`, `RTPCmdPvPGateTest`, `PvPCombatAdapterTest`.
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

---

## Tier 3 — Polish and long tail

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
  the front-page entry in `docs/FRONT_PAGE.bbcode`'s "Roadmap" block with a vague, non-technical
  summary (no REQ-*, no ADR identifiers — those stay in this document).
- Features requested by operators: land in Tier 2 with the requesting issue linked, or get
  explicitly declined with a one-line rationale.
- This file is the only TODO source of truth for release planning. Do not duplicate it into
  CHANGELOG or per-module requirements.


