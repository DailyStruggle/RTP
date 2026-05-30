# TODO - Verification of Gemini "RTP V3 UX Enhancements" Analysis

This file records the outcome of fact-checking the external (Gemini) architectural
analysis against the actual RTP codebase as of 2026-05-29. Each suggestion is
classified: **Done** (already implemented), **Partial**, **Not present** (candidate
work), or **Rejected** (factually wrong or violates project architecture).

> Source: `<issue_description>` ("Architectural Analysis of RTP V3: Developer and
> Operator UX Enhancements"). The analysis is broadly directionally reasonable but
> contains several claims that are already satisfied, and at least one concrete code
> proposal that contradicts the repo's hard architecture rules.

---

## Correctness notes on the analysis itself

- **`ClaimValidator` SPI uses `org.bukkit.Location`** in the proposed snippet. This is
  **rejected as written**: `rtp-core` and `rtp-api` must have **no `org.bukkit.*`
  imports** (see `.junie/AGENTS.md` "Logging & Feedback" / "Architecture Boundaries").
  Any claim SPI living in `rtp-api` must route through `RTPLocation` / `RTPServerAccessor`,
  not Bukkit types.
- **Sealed `permits WorldGuardHook, GriefPreventionHook, CustomAddonHook`** is also
  rejected: a sealed interface that enumerates concrete hooks defeats the open
  addon-extensibility model already provided by `RegionVerifierRegistry` (third-party
  addons cannot be `permits` members of a core sealed type).
- The "persistent spatial memory database" + Archimedean-spiral + O(log n) +
  pre-generation queue claims **match the real design** (`MemoryShape`,
  `RegionQueueManager`, ADR-001). Accurate.
- "First-join often throws console errors / forces delay countdown" is a description of
  *competitors*; RTP already handles first-join cleanly (see below).
- **Recurring pattern - "recommend a feature we already ship."** A rescan confirmed the
  analysis repeatedly proposes capabilities that already exist under a different name. Net
  result of the rescan: **Override worlds** (= `WorldKeys.override`), **Named centers**
  (= `rtp.params` per-invocation overrides + `/rtp config regions ...` + named regions),
  **First-join** (= `rtp.onevent.firstjoin`/ADR-023), **CI traceability**, the decoupled
  **event/core split**, and the **claim-integration SPI** (= `RegionVerifierRegistry`) are all
  already done. The one genuinely-new item, `RTPRunnable` spatial context, is now **implemented**
  (ADR-054, see Phase 1); the diagnostics asks are partially covered (small gaps); and
  the GUI/heatmap asks are rejected. Treat new "competitor parity" suggestions skeptically and
  grep the config-key/permission surface (`worlds.yml`, `rtp.params`, `rtp.config`) before
  classifying anything as missing.

---

## Phase 1 - Core API & Concurrency

- [x] **`RTPRunnable` spatial-context routing** - *Done (**ADR-054**).* `RTPRunnable`
  (`rtp-api/...common/tasks/RTPRunnable.java`) now carries an optional `RTPPlayer target`
  and `RTPLocation location` (the actual thread-routing context - not `RtpTarget`, which is
  only a destination *name* selector and cannot resolve a tick thread) plus a `schedule()` /
  `schedule(long)` that self-dispatches: target player -> entity scheduler; else location ->
  region/chunk thread; else -> async (or delayed main thread). Core installs the static
  `RTPRunnable.scheduler` hook in the `RTP` constructor (same pattern as `trackHook`); calling
  `schedule(...)` before core loads throws `IllegalStateException`. Covered by
  `RTPRunnableScheduleRoutingTest`.
- [x] **Decoupled platform / core split (no Bukkit events in core)** - *Done.* Core fires
  platform-agnostic runnables; Bukkit-native events live in `rtp-plugin`/adapters. The
  "Event Translation Layer" pattern is effectively already the architecture.
- [~] **Claim-integration SPI** - *Partial / different shape.* Already implemented as
  `RegionVerifierRegistry` (`rtp-api/.../hooks`) + `GlobalRegionVerifiers` (rtp-core) +
  per-plugin checkers in `rtp-plugin/.../softdepends/claims` (GriefPrevention, WorldGuard,
  Towny, Lands, Factions, GriefDefender, RedProtect, HuskTowns), folded in per **ADR-019**,
  surfaced per **ADR-026**. The proposed sealed `ClaimValidator` is **rejected** (Bukkit
  import + sealed-permits closes extensibility). No action unless a redesign is justified.
- [x] **CI traceability enforcement** - *Done.* `docs/dev/TRACEABILITY.md` + REQ-traced
  tests + architecture tests (`RTPArchitectureTest`) already enforce this.

## Phase 2 - Administrator Options

- [x] **Override worlds (redirect `/rtp` in Nether/End to Overworld)** - *Done (already
  shipped).* This is `WorldKeys.override` (the `override` key in `worlds.yml`). The world
  resolution loop in `SelectionAPI`, `RTPCmd`, and `RTP.java` redirects a `/rtp` issued in a
  world the caller lacks permission for (or that points elsewhere) to the configured override
  world/region - documented in `docs/dev/CODE_TOUR.md` ("World resolution loop") and
  `docs/architecture/07-rtp-command-region-selection.md` (nested world-level + region-level
  override loops). The analysis's premise that this is missing is wrong; I originally
  mis-scanned for a `overrideWorld` key name.
- [x] **First-join teleport** - *Done.* Handled via `rtp.onevent.firstjoin` /
  `rtp.onevent.join` (`OnEventTeleports`, Fabric `FabricOnEventTeleports`) plus the
  login-reserve cache (**ADR-023**, `loginCacheEnabled`). The analysis's premise that this
  is missing/buggy does not apply.
- [-] **Named centers / `addcenter`** - *Redundant (reject). REMOVABLE.* RTP already
  provides runtime authoring of arbitrary-centered teleport targets two ways: (1) **ephemeral**
  per-invocation overrides via the `rtp.params`-gated shape `CommandParameter`s (`centerx`,
  `centerz`, `radius`, `weight`, `expand`, ... registered in `Circle`/`Square`/`Rectangle`/
  `Ellipse`/`Polygon`), e.g. `/rtp ... centerx=100 centerz=-200 radius=512`; and (2)
  **persistent** region editing via `/rtp config regions <name> centerX=... centerZ=... ...`
  (`rtp.config`, `ConfigCmd`/`SubConfigCmd`), also driven by the book menu's Apply path.
  A standalone `addcenter` registry would be a strictly weaker third concept (a bare label on a
  coordinate, with no shape/queue/permission/price). A named **region** is the persistent,
  full-featured form of a "named center." Only absent micro-nicety: inferring the center from
  the caller's current position - a tiny optional extension of `/rtp config`/`rtp.params`, not
  a new subsystem.
- [~] **Command-block / console execution safety** - *Likely Done.* Unified `commands-api`
  already dispatches across console/player; needs a quick confirmation test for command-block
  senders before claiming full parity. Low risk.

> Pruning legend: `[-]` = **removable / pruned** (rejected on usability, viability, or
> security grounds). Remaining `[ ]` items survived the scan as viable.

## Phase 3 - Operational UX

- [-] **Native `gui.yml` chest GUI** - *Rejected (security + redundancy). REMOVABLE.* An
  inventory-based menu is a real container and a recurring dupe/exploit surface: every
  `InventoryClickEvent` path (shift-click, hotbar number swap, drag, double-click collect,
  drop-outside, `MOVE_TO_OTHER_INVENTORY`, offhand/creative-middle-click) must be cancelled
  or the decorative icon becomes a takeable item, and the proposed `action:` command-dispatch
  is a privilege-escalation vector. It also adds an entity/region-thread state surface on
  Folia (S-005 risk). RTP already ships a **read-only book menu** (`/rtp menu`,
  `AdminPanelBuilder`, `CommandTreeMenuBuilder`) that delivers the same operator value with
  no container, a single permission-checked `commands-api` click channel, and no thread
  surface. Prune from the backlog.
- [~] **Telemetry dashboards (`/rtp diagnostics general|biome|visual`)** - *Partial; see
  the dedicated gap analysis + plan below.* The operator diagnostic surface is **already**
  `/rtp info` and `/rtp admin`, not a missing `/rtp diagnostics` verb:
  - **`/rtp info`** (`InfoCmd` + `InfoBookBuilder`) renders the live health block - chunk
    tickets, total/teleport counts, leak rate, live TPS + MSPT, soft cap, **queue depth**
    (`infoQueueDepth`), **queue-growth warnings** (`infoQueueGrowth`), pending teleports,
    avg pipeline ms, ADR-053 **pipeline percentiles** + **slow-pipeline** counters, heap,
    **database latency**, and the Folia per-region table - all read from one atomic
    `MetricsSnapshot` via `PlaceholderProvider.withSnapshot`.
  - **`/rtp admin`** (`AdminCmd`, `rtp.menu.admin`) opens the curated admin panel
    (`AdminPanelBuilder`) whose **diagnostics** section row runs `/rtp test full`.
  - `MemoryTracker`, `metrics-api`/`CoreMetrics`, the `FailTypes` vocabulary, **ADR-039**
    (proposed `/rtpadmin diag` spatial surfaces), and **ADR-053** back the above.
  Correcting the earlier note: the **queue-fill-rate "gap" is already closed**
  (`infoQueueDepth` + `infoQueueGrowth`). The **one genuine remaining gap** is a
  **failure-cause (biome-rejection) breakdown** in the operator surface - see plan below.
- [-] **Visual heatmap / in-game map export** - *Rejected (low viability + low usability).
  REMOVABLE.* PNG export is not a natural server-side artifact and a `MapView`/map-item
  renderer is high-effort, niche, and largely cosmetic. The actionable signal it would carry
  (rejection reasons, scan coverage, queue fill) is already available textually via
  `FailTypes`, `MemoryTracker`, `CoreMetrics`, and the ADR-039/ADR-053 surfaces. Prune;
  fold any genuine demand into the diagnostics-command gap below instead of a renderer.

---

## Recommended next steps (prioritized, all D-005-gated where multi-module)

1. ~~**`RTPRunnable` spatial context**~~ - **done** (ADR-054): `target`/`location` routing
   fields + `schedule()` self-dispatch (entity/region/async), wired via the static
   `RTPRunnable.scheduler` hook; covered by `RTPRunnableScheduleRoutingTest`.
2. **Diagnostics gap (failure-cause / biome-rejection breakdown in `/rtp info`)** - the
   only confirmed remaining gap; see the dedicated plan section below. Queue-fill is
   already surfaced (`infoQueueDepth`/`infoQueueGrowth`), so it is dropped from this item.
3. ~~**Override worlds**~~ - **removed**: already shipped as `WorldKeys.override` (see Phase 2).
4. ~~**Named centers**~~ - **removed**: redundant with `rtp.params` + `/rtp config regions ...`
   and with named regions (see Phase 2).
5. ~~**Chest GUI / heatmap**~~ - **removed** from the backlog (see Phase 3): chest GUI is a
   security/redundancy reject vs. the existing book menu; heatmap/map export is low-viability,
   low-usability cosmetic scope. Do not pursue without explicit user direction.

**Do not** implement the analysis's `ClaimValidator` snippet verbatim (Bukkit import in
core + sealed-permits). If a claim-SPI refactor is desired, it must build on
`RegionVerifierRegistry` and stay Bukkit-free in `rtp-api`/`rtp-core`.

---

## Diagnostic-gap scan + plan (2026-05-29)

### Where diagnostic info already lives (verified)

- **`/rtp info`** (`rtp-core/.../commands/info/InfoCmd.java`, paged via `InfoBookBuilder`)
  is the primary operator diagnostic surface. Per-world / per-region sections plus a global
  **health block** rendered from a single atomic `MetricsSnapshot`
  (`PlaceholderProvider.withSnapshot`): chunk tickets, total/teleport counts, MSPT, total
  loads + loads-by-origin, **leak rate**, live **TPS** + **MSPT**, **soft cap**,
  **queue depth** (`infoQueueDepth`), **pending teleports**, **avg pipeline ms**,
  **pipeline percentiles** + **slow-pipeline** + **queue-growth** (ADR-053), **heap**,
  **database latency**, and a **Folia per-region** table (`FoliaRegionSample`).
- **`/rtp admin`** (`rtp-core/.../commands/admin/AdminCmd.java`, `rtp.menu.admin`) opens the
  curated admin panel (`AdminPanelBuilder`); its **diagnostics** section row runs the full
  `/rtp test full` suite. `/rtp test ...` (`TestChunkTicketCmd`, etc.) are the deep probes.
- **Data already collected but not all surfaced:** `RtpOutcomeStats.GLOBAL` (ADR-052)
  records success/failure counts and `failureBreakdown()` bucketed by
  `LocationGenerator.FailTypes`; `PregenState.failMap` holds the same per-region. The
  fixed-shape rollup is specified as `pipelineFailureRate` + `pipelineFailureBreakdown` in
  `METRICS_PLAN.md` **Phase M1** (not yet a `MetricsSnapshot` field).
- **Proposed-but-unimplemented:** **ADR-039** `/rtpadmin diag biomes|heatmap|metrics|chart|network`
  (status **Proposed**; no `DiagCmd` exists). Large blast radius (rtp-api + rtp-core +
  rtp-plugin + adapters), spatial ASCII surfaces.

### Remaining gap (one, confirmed)

- **Failure-cause / biome-rejection breakdown is not surfaced to operators.** The counts
  exist (`RtpOutcomeStats.failureBreakdown()`), but neither `/rtp info` nor the admin panel
  renders "why are selections being rejected" (biome / prefilterBiome / unsafeBlock /
  nullChunk / vert / safety / claim). This is exactly the analysis's
  "`/rtp diagnostics biome` / unsafe-stats" ask, and the only ask not already met.
- Non-gaps (closed): **queue-fill-rate** (= `infoQueueDepth` + `infoQueueGrowth`),
  **percentile latency / stats performance** (= ADR-053), **chunk-ticket / leak**
  (= `MemoryTracker` + `infoTickets`/`infoLeakRate`), **scan coverage** (`/rtp scan` status).

### Plan (minimal, in-surface; preferred over a new `/rtp diagnostics` verb)

Land the failure-cause breakdown on the **existing** surfaces rather than a new command:

1. **Metric (rtp-core):** implement `METRICS_PLAN.md` M1 - expose `pipelineFailureRate`
   and a fixed-shape `pipelineFailureBreakdown` (`Map<FailKind,Long>`, bounded ~5 buckets)
   on `MetricsSnapshot`, sourced from `RtpOutcomeStats` / `PregenState.failMap` (already
   wait-free; no new hot-path work). Enumerate `FailKind` from `FailTypes` (no hardcoded
   catalog, ADR-039 contract 6 spirit).
2. **Surface (rtp-core + messages):** add `/rtp info` health-block lines
   (`infoFailureRate`, `infoFailureBreakdown` / `infoTopRejectionCause`) gated on the same
   "empty template skips silently" pattern already used for the M-keys, so locales without
   the keys are unaffected.
3. **Locale parity:** add the new baseline keys via the **Locale Config TSV pipeline**
   (edit baseline -> `locale-files-to-csv` -> `reconcile-locale-csvs` -> translate ->
   `locale-files-from-csv` -> `LocaleParityTest`). Never hand-edit `lang/<locale>/*.yml`.
4. **Tests + traceability:** extend `InfoCmdTest` for the new lines (present when populated,
   skipped when blank); add a `MetricsSnapshot` shape test; add `REQ`/ADR-052 traceability
   rows. Full `.\gradlew build`.
5. **D-005 gate:** crosses `rtp-core` (metric + render) + `metrics-api` (snapshot field) +
   resources/locales. Author/extend an ADR (ADR-052/METRICS_PLAN M1) and get approval
   **before** coding. The broad **ADR-039** spatial surfaces remain a separate, larger,
   lower-priority track - do not start them to close this gap; the textual breakdown is the
   proportionate fix.
