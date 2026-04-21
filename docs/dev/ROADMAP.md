# RTP Roadmap

**Scope:** This roadmap tracks known shortfalls in the current release and the concrete work planned to address them, plus forward-looking features that are not driven by a known caveat. Version anchor: `3.0.0-beta.1` (see [`REQUIREMENTS.md`](REQUIREMENTS.md)).

Each item is expressed as a checklist line so completed work can be struck through with the commit/ADR that closed it.

Tier ordering reflects priority, not chronology:

- **Tier 0** — must-ship items before `3.0.0` loses the `-beta.1` tag.
- **Tier 1** — directly narrows a caveat currently documented in `docs/FRONT_PAGE.bbcode` or `docs/admin/`.
- **Tier 2** — new capability that earns a `3.1.0` release note.
- **Tier 3** — polish, long-tail, and infrastructure.

---

## Tier 0 — Release blockers for `3.0.0` final

- [ ] **Record current-release demo footage.** The "Historical Demonstrations" section on the front page is self-labelled as dated. Produce two ≤30s clips on `3.0.0-beta.1` showing (a) the Anvil pre-filter's effect on MSPT under Spigot, (b) queue saturation and per-player isolation on Folia. Replace the YouTube IDs in `docs/FRONT_PAGE.bbcode` and retitle the section back to "Performance Proofs & Demos".
- [ ] **Close out every `@Ignored` / `@Disabled` test.** Per project guidelines, releases must not ship with muted tests. Audit `rtp-core`, all platform adapters, and `rtp-plugin` before tagging.
- [ ] **Freeze the ADR set for 3.0.0.** Any architectural work started after `beta.1` either lands before the final tag or is deferred to a `3.1.0` ADR file. Mixed-state ADRs confuse external reviewers reading the repo top-down.
- [ ] **`CHANGELOG.md` Keep-A-Changelog pass.** The `3.0.0` entry should read as a release announcement, not a git log — grouped by Added / Changed / Fixed / Removed with operator-visible framing.

---

## Tier 1 — Caveat mitigations

Each subsection below matches a caveat that the front page currently admits. The goal for each is to convert the caveat from a trust claim ("bounded in practice") to an observable one ("here is the number, bound it yourself").

### 1.A — Spigot fallback: one on-tick `getChunkAt`

The Anvil pre-filter falls through to a live load only when the probe returns `UNKNOWN` (no region file, unsupported data version, decode error, or un-populated chunk). On vanilla Spigot this costs one on-tick chunk load per fallback.

- [ ] **Document un-populated-chunk warming as the operator remedy.** The `REQ-RTP-F-012` world-scan lifecycle (`start` / `pause` / `resume` / `reset` / `cancel`) populates every candidate on disk and collapses the fallback to near-zero for warmed regions. Write this up in `docs/admin/QUICK_START.md` as the recommended cold-start workflow.
- [ ] **Add a `--until-populated` convenience flag to the admin scan command** (if not already present) so the cold-start workflow is one command, not a cron job.
- [ ] **Tick-budget telemetry.** Surface a rolling counter — fallbacks/minute and total μs spent on the main thread per fallback — via `/rtp test full` and a dedicated stats subcommand. A `SpigotPrefilterStats` companion to `MemoryTracker` is the likely shape.
- [ ] **Optional "reject on unknown" mode.** Config flag (default off) that skips candidates when the probe returns `UNKNOWN` instead of paying the on-tick fallback. For operators who prefer a guaranteed zero main-thread cost over maximum throughput.

### 1.B — Folia fallback: one Region-Scheduler hop

On Folia, a confirmed candidate that the Anvil probe could not resolve pays one Region-Scheduler hop to the authoritative live load.

- [ ] **Measure the hop cost.** Add a traceable timing test (`FoliaRegionHopTimingTest`, traceable to `REQ-RTP-NF-002`) that captures nanoseconds between Anvil-probe-complete and Region-Scheduler-ready on a representative candidate. Publish the p50/p95 numbers in the release notes; until then the caveat is unquantified.
- [ ] **Investigate hop amortization.** Can consecutive candidates within the same region share a single Region-Scheduler entry? If yes, implement and document with an ADR. If no, write the ADR explaining why — closing the question is as valuable as fixing it.

### 1.C — Un-populated chunks fall through to live load

- [ ] **Regression test: live-load safety net cannot re-admit a prior reject.** Assert that a chunk the Anvil probe rejected on populated data is never subsequently accepted by the live-load path. Must hold under Folia region-stealing and concurrent player teleports.
- [ ] **Failure attribution bucket.** Extend the `FailTypes` taxonomy (or the equivalent telemetry surface) with `unpopulatedFallthrough` so `/rtp test full` can report its frequency distinctly from other fallbacks. Currently invisible.

### 1.D — Fabric: not supported

The front page honestly says "Not Supported". The requirements explicitly scope Fabric out. Both are correct today; the roadmap is to eventually lift the restriction.

- [ ] **Resolve the three standing blockers in `MULTI_PLATFORM_PLAN.md`:**
  - [ ] **S-005 violation in `FabricWorld.getChunkAt`.** Route through `ServerWorld#getChunkManager().getWorldChunk(..., load=false)` with a `CompletableFuture` wrapper, or share the `rtp-anvil` pre-filter path already used by Bukkit adapters.
  - [ ] **Null stub in `FabricServerAccessor.getLocationGenerator`.** Replace with a functioning implementation; pre-core-load must throw `IllegalStateException` per `REQ-RTP-S-006`.
  - [ ] **Unresolved Loom dependency.** Pin a Loom version in `settings.gradle`; document the Fabric build prerequisites in `CONTRIBUTING.md`.
- [ ] **Decide the shipping model.** Either: (a) Fabric ships in `3.1.0`; (b) Fabric ships as a separate `rtp-fabric-preview` artifact on its own release line. Pick one, then update `REQUIREMENTS.md §0 Out of Scope` so the document does not contradict the shipping artifact.
- [ ] **Re-run the `rtp-api` interface-sufficiency analysis** once the three blockers are resolved. Record the result as an ADR regardless of outcome — interface gaps that turn out to exist are more valuable to document than interface adequacy that turns out to hold.

### 1.E — Unsourced statistics on the front page

The Spatial Memory paragraph now cites a concrete ~45% Overworld-safe figure from a local profiling pass on a vanilla 1.21 seed set; Nether and End ratios remain qualitative ("dominated by lava seas", "almost entirely void"). The caveat has narrowed from "no numbers anywhere" to "one number, not yet reproducible by readers".

- [ ] **Publish the reference profiling run** in `docs/admin/BENCHMARKS.md` — seed list, sample size, methodology, and per-dimension safe-fraction columns for Overworld / Nether / End. Until this exists, the ~45% figure is an author claim, not a reproducible one.
- [ ] **Extend the existing bStats integration with custom charts.** Default metrics are already wired (`RTPBukkitPlugin` → bStats ID `30865`, relocated `org.bstats` → `io.github.dailystruggle.rtp.bstats`); what is missing is `addCustomChart(...)` for RTP-specific aggregates — platform split, region count, queue depth, and observed safe-fraction histograms per dimension.
- [ ] **Replace the qualitative Nether/End phrasing** on the front page with measured figures once either path above lands.

---

## Tier 2 — Upcoming features (not caveat-driven)

- [ ] **World-scan UX polish.** The admin lifecycle exists; operator affordances around it do not. Concretely: progress indication (both console and in-game bossbar), resume-across-restart semantics, and a per-region "warmth report" export. This is what converts the feature from *implemented* to *sellable*.
- [ ] **Persistent learned-state inspector.** A `/rtp memory dump <region>` subcommand producing a human-readable summary — flagged-bad sector count, coverage %, age of oldest entry, last-write timestamp. The H2/SQLite persistence is a front-page promise; inspection is the operator's confirmation.
- [ ] **Safety-list grammar expansion.** The token grammar shipped in `3.0.0-beta.1` is the foundation; follow-ups:
  - [ ] Tag-group composition with set subtraction (`#minecraft:slabs - OAK_SLAB`).
  - [ ] Numeric range predicates (`[level>=5]`) for fluids and light levels.
  - [ ] Hot-reload on `safety.yml` file edit (currently requires `/rtp reload`).
- [ ] **Claim-plugin integration audit.** The front page lists seven integrations. Audit each against current upstream releases (Factions forks, GriefDefender 2.x, Lands 7.x, HuskTowns 3.x, TownyAdvanced 0.x, WorldGuard 7.x, GriefPrevention 16.x) and publish `docs/admin/CLAIM_PLUGIN_COMPATIBILITY.md` with per-plugin version matrices. At least one integration is almost certainly lagging.
- [ ] **CI matrix across platforms.** The Jenkinsfile builds, but `rtp test full` should run against Spigot + Paper + Folia (and eventually Fabric) in parallel matrix form, even with mock servers where necessary. This is the step that converts `TRACEABILITY.md` from "documented" to "continuously enforced".
- [ ] **Addon-developer quickstart.** `docs/dev/FOR_ADDON_DEVELOPERS.md` is linked from the front page, but a one-page *"register a custom shape in 20 lines"* tutorial is the document that actually drives third-party adoption.

---

## Tier 3 — Polish and long tail

- [ ] **Standalone `rtp-anvil` publication.** The module is genuinely reusable outside RTP (any plugin wanting off-tick region-file reads could depend on it). If pursued, add a Maven Central publish target and write the "why this lives alone" ADR.
- [ ] **`SECURITY.md` audit before release traffic hits.** Confirm contact address, disclosure window, and scope are all current.
- [ ] **Glossary hygiene.** Once Fabric re-enters scope, audit `docs/dev/GLOSSARY.md` and the Multipurpose Terms table for platform-specific terms that now need disambiguation (`Region` means different things on Folia and Fabric).
- [ ] **`README.md` shapes section refresh.** The `user-images.githubusercontent.com` URLs for the shape-distribution plots are pinned to a legacy account hash; re-host in the repo's own `docs/img/` to survive future GitHub UI changes.
- [ ] **Retire `.bak` files after every release.** Project policy is to keep `.bak` copies during in-flight edits only; a release tag is a natural cleanup checkpoint.

---

## How to update this document

- Completed items: do **not** delete the line. Strike it through and append the commit short-SHA or ADR number that closed it. This preserves the velocity signal.
- New caveats discovered between releases: add under the appropriate Tier 1 subsection and mirror the front-page entry in `docs/FRONT_PAGE.bbcode`'s "Roadmap" block with a vague, non-technical summary (no REQ-*, no ADR identifiers — those stay in this document).
- Features requested by operators: land in Tier 2 with the requesting issue linked, or get explicitly declined with a one-line rationale.
- This file is the only TODO source of truth for release planning. Do not duplicate it into CHANGELOG or per-module requirements.
