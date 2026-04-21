# Biome & Bad-Location Visitor Plan

**Status:** Draft — design-only. No code in the amendment below.
**Supersedes:** `ADR-016` (Steps 1–3 landed 2026-04-19
as building blocks; Steps 4–6 migrate here).
**Extends:** ADR-016 "Anvil subsystem" (block + biome palette pre-filter).
**Minimum supported format:** Minecraft 1.20.1 region files
(REQ-RTP-SYS-002).

> **Design pivot (2026-04-20c):** The separate `RegionDataVisitor` task,
> its pluggable gate, its monotonic cursor, and the `visitorRateTicks`
> throttle introduced in Phases 8.1 / 8.1b are **reverted and
> superseded** by a one-flag extension to the existing
> `RegionCacheTask`. The observational sampling reuses the cache-fill
> pipeline's random-point selection, `LocationGenerator`, and
> `MemoryShape` sinks verbatim; the only new behaviour is "continue
> past the cache cap, discarding the result." No new scheduler, no new
> cursor, no new sink wiring. See §2 and §8 for the revised design;
> §§3–7 are retained as historical context for the reverted approach
> and are no longer authoritative.

---

## 1. Motivation

Two converging user-visible costs motivate a single fix:

1. **Cold-start biome filtering.** Today the biome allow-list in
   `safety.yml` is validated against the world-level enumeration
   returned by `RTPServerAccessor#getBiomes(world)`. That enumeration is
   either a closed Bukkit enum (collapses Iris/datapack biomes) or a
   one-shot `AnvilRegionScanner` walk at region load (pays for every
   `.mca` file even when the operator only cares about a handful). The
   in-memory `MemoryShape#biomePrefixSumsCache` already carries the
   biomes a region has actually produced candidates in — a strictly
   tighter and more accurate set — but it is empty at first boot and
   warms only when live teleports occur.
2. **Bad-location cache cold-start.** `MemoryShape#pendingBadLocations`
   records a cell as unsafe only when a player teleport to that cell
   fails. Early users of a fresh region therefore pay a retry tax on
   every cell the spiral walks before any unsafe ground has been
   recorded.

Both problems dissolve if the system continuously and opportunistically
exercises the existing candidate-selection pipeline against
already-generated chunks in the background, recording both the biome
palette it sees and any cells rejected by the safety loop — without
ever taking a chunk ticket the cache wouldn't already take, and
without enqueuing the resulting candidate.

---

## 2. Architecture (revised 2026-04-20c)

The system shall extend `RegionCacheTask` with an **observational
mode** that reuses every existing element of the cache-fill pipeline
and differs from the default mode in exactly one behaviour: it runs
when the unkept-location cache is at or above `cacheCap`, and it
discards the selected candidate instead of enqueuing it.

Formally:

- `RegionCacheTask` shall accept an `observationalOnly: boolean`
  construction flag (or equivalent static factory, e.g.
  `RegionCacheTask.observe(region)`).
- When `observationalOnly` is `false` (the existing default), the task
  shall behave exactly as today: early-return when
  `queueManager.unkeptLocations.size() >= cacheCap`, otherwise select a
  random spiral index via `MemoryShape.select()`, invoke
  `LocationGenerator.getLocation(...)`, and push a successful
  candidate into `unkeptLocations`.
- When `observationalOnly` is `true`, the task shall:
    - Run **only** when `queueManager.unkeptLocations.size() >= cacheCap`
      (the strict inversion of the default gate). If the cache has
      headroom the observational task shall return immediately so the
      default-mode `RegionCacheTask` retains priority.
    - Select the next candidate via the existing
      `MemoryShape.select()` + `LocationGenerator.getLocation(...)`
      flow.
    - On a safe returned candidate: **drop it.** The candidate shall
      not be pushed to `unkeptLocations`, shall not be handed to any
      teleport pipeline, and shall be eligible for immediate GC.
    - Emit no new sink calls. All side effects that matter —
      `MemoryShape#addBadLocation(long)` for rejected cells along the
      selection walk, and `biomePrefixSumsCache` updates for every
      evaluated candidate — already occur inside `LocationGenerator`
      and `MemoryShape.select()` today, and are inherited for free.
- Scheduling shall reuse the existing `period` setting already
  consumed by `RegionCacheTask`. **No `visitorRateTicks`, no separate
  throttle, no `tickVisitor` helper.** The observational task shall be
  registered alongside the default-mode task on the same pipeline and
  shall self-gate on the inverted cache condition.

That is the entire architecture. Any future safety tag, verifier, or
biome-resolution change made to `LocationGenerator` is inherited by
observational mode automatically, because observational mode *is* the
cache-fill pipeline run under a flag.

### Invariants preserved from the earlier draft

- **No force-generation.** Inherited from `LocationGenerator`'s
  existing Anvil-first / `UNKNOWN → skip` behaviour on vanilla Spigot
  (ADR-016) and the async chunk-load contract on Paper / Folia.
  Observational mode adds no new chunk-loading code path.
- **Write-only sinks.** `addBadLocation` and `biomePrefixSumsCache`
  are already idempotent and append-only; observational mode does not
  call any remove or decay path.
- **Platform-neutrality.** The flag lives in `rtp-core` on
  `RegionCacheTask`; no platform imports introduced.
- **Idempotent interaction with teleport-time path.** Cells marked bad
  by observational mode and by a live teleport attempt collide
  harmlessly on the existing `ConcurrentHashMap`.

---

## 3. Configuration (revised 2026-04-20c)

A single key governs observational mode:

| Key                  | Default | Meaning                                                                                    |
|----------------------|---------|--------------------------------------------------------------------------------------------|
| `visitor.enabled`    | `true`  | Master switch. When `true`, one observational `RegionCacheTask` is scheduled per region on the existing cache-fill `period`. |

`visitorRateTicks`, `visitor.rateHz`, `visitor.queueFillThreshold`,
`visitor.maxBadLocationsPerShape`, and
`visitor.cursorPersistIntervalTicks` from the earlier draft are
**removed.** Scheduling cadence is whatever `period` is already set to
for the region's cache-fill pipeline; bad-location growth is bounded
by whatever policy already governs `pendingBadLocations`; no cursor
is persisted because no cursor exists.

The `PerformanceKeys.visitorEnabled` entry already added in Phase 8.1b
shall be retained. The `PerformanceKeys.visitorRateTicks` entry and
its `performance.yml` key shall be removed as part of the pivot
implementation.

---

## 4. Landing order (revised 2026-04-20c)

Each sub-step is independently shippable and testable.

1. **Revert Phase 8.1 / 8.1b code surface.** *[Landed 2026-04-20]*
    - Deleted `rtp-core/.../selection/region/RegionDataVisitor.java`.
    - Deleted `ReqRtpVisitorCacheGateTest` and
      `ReqRtpVisitorCursorMonotonicTest` from the region test package.
    - Removed `Region#dataVisitor`, `Region#visitorTickCounter`, and
      `Region#tickVisitor(long)` plus its `execute()` call site.
    - Removed `PerformanceKeys.visitorRateTicks` and the
      `visitorRateTicks` key from
      `rtp-plugin/src/main/resources/performance.yml`.
    - Removed the `REQ-CORE-PERF-001/002` placeholder rows from
      `docs/dev/TRACEABILITY.md`.
    - Retained `PerformanceKeys.visitorEnabled` and the `visitor.enabled`
      yaml key.
2. **Land observational flag on `RegionCacheTask`.** *[Landed 2026-04-20]*
    - Added an `observationalOnly` final field + `RegionCacheTask.observe(region, maxNanos)` factory.
    - Implemented the inverted gate + discard-on-success behaviour per §2.
3. **Schedule the observational task.** *[Landed 2026-04-20]*
    Registered in `Region#execute` via a single `cachePipeline.add(RegionCacheTask.observe(...))`
    guarded by `isObservationalModeEnabled()` (reads `PerformanceKeys.visitorEnabled`).
    Reuses the existing `period`; no new scheduler.
4. **Verification tests.** *[Landed 2026-04-20]*
    - `ReqRtpObservationalCacheTaskTest` (3 cases): default-mode early-return guard when cache at cap;
      observational-mode early-return when cache has headroom; `observe()` factory non-null + distinct identity.
    - The full region test suite (508 tests) passes with the pivot — the side-effect sinks
      (`addBadLocation`, `biomePrefixSumsCache`) remain covered by the pre-existing
      `RegionPipelineTest` / `MemoryShapeTest` coverage of `LocationGenerator`, which is the
      single source both modes share.
5. **Direct whitelist/blacklist biome evaluation (revised 2026-04-20e).**
   *[Landed 2026-04-20]* The original wording of this step proposed
   routing the blacklist inversion through
   `region.getShape().getObservedBiomes()` with a server-accessor
   fallback. That approach inherited the cold-start failure mode of
   the underlying enumeration (empty observed set or enum-collapsed
   server enumeration ⇒ blacklist materialised against nothing ⇒
   every teleport rejected). Superseded design:
   `LocationGenerator#getLocation(Region, Set<String>)` now carries
   a `boolean biomeWhitelist` polarity flag and evaluates each
   candidate's biome as `biomeFilterSet.contains(currBiome) ==
   biomeWhitelist`. No world-level biome enumeration is consulted on
   the filter path. `MemoryShape#getObservedBiomes()` was still
   added as `Collections.unmodifiableSet(biomePrefixSumsCache.keySet())`
   — retained for diagnostic / admin-command use — but is not read
   by the selection loop.
6. **Retire `AnvilRegionScanner.scanBiomes` from runtime.**
   *[Landed 2026-04-20]* Removed the scanner-union logic from
   `BukkitRTPWorld#getBiomes` and `FoliaRTPWorld#getBiomes`. The
   scanner remains available in `rtp-anvil` as a diagnostic tool for
   a future admin command.
7. **Retire the Iris addon's `setBiomesGetter` registration.**
   *[Landed 2026-04-20]* With the filter path no longer consulting a
   world-level biome enumeration, the addon's override is unreferenced.
   The addon's `setBiomeGetter` (per-coord resolver) remains and still
   surfaces namespaced Iris biome names.
8. **ADR-016 amendment.** *[Landed 2026-04-20]* Added §12
   "Observational cache-fill mode (Phase 3)" covering the read-only,
   off-tick, discard-on-success contract; §12.3 records the direct
   whitelist/blacklist biome-filter model; §12.4 records the
   universal-platform invariant.
9. **`FRONT_PAGE.bbcode` bullet.** *[Landed 2026-04-20]* Added the
   "Upgrade-drift proof biome filtering" bullet to the platform
   chunk-loading list alongside the existing self-warming sampler
   bullet. One-line operator-facing note: "RTP
   now learns which biomes your regions actually contain as players
   use it; first-boot filtering is automatic."

---

## 4a. Platform coverage & upgrade-drift invariant (2026-04-20d)

The Anvil-first biome resolution landed in `ADR-016` §10.3
shall apply to **every Bukkit-family server**, not only vanilla
Spigot. Concretely:

- **Spigot, Paper, and Folia** shall all resolve a populated chunk's
  biome through `AnvilChunkView#getBiomeAt` before any live
  `world.getBiome(loc)` call. Paper inherits this through the Spigot
  class hierarchy (`BukkitRTPWorld.getBiome`); Folia inherits it
  through `FoliaRTPWorld.getBiome`. No platform adapter is permitted
  to bypass the Anvil pre-step on the grounds that its live
  `getBiome` is "cheap" or "async-safe."
- **Rationale — upgrade-drift precedence.** When an operator upgrades
  a Paper (or Folia) server across a Minecraft version boundary,
  Mojang's seed-based biome assignment can change for coordinates
  that a prior version had generated and persisted to disk. The live
  `world.getBiome(loc)` call on the upgraded server reports the
  *new* algorithm's answer, which disagrees with the chunk the
  player will actually land in (the chunk on disk was not
  regenerated). The `.mca` palette is the only source that remains
  consistent with the ground truth the player sees. Anvil-first
  therefore guarantees that **old, on-disk data takes precedence
  over cheap live `getBiome` checks whose underlying seed /
  algorithm has drifted**. This is load-bearing for the
  biome-allow-list filter in `LocationGenerator`: without it, an
  allow-listed biome can evaporate from a region silently after an
  MC upgrade.
- **Fallback remains `world.getBiome(loc).name()`** only when the
  Anvil view returns `null` (unpopulated chunk, missing section,
  decode miss). Unpopulated chunks are by definition not subject to
  upgrade drift — they have no persisted answer to disagree with.
- **Observational cache-fill (§2) inherits this automatically.**
  Because observational mode reuses `LocationGenerator` verbatim,
  and `LocationGenerator` consults the Anvil-first getter chain, the
  biome observations written into `MemoryShape#biomePrefixSumsCache`
  are drawn from disk whenever disk has an answer — on every
  platform, not just Spigot. Step §4.5 (route biome-filter
  validation through `MemoryShape`) therefore does not need a
  per-platform override.
- **No new `setBiomeGetter` registration.** Per `ADR-016`
  §6, the Anvil-first step is woven into the adapter's default
  getter body. Addon precedence (`RTP_Iris_integration`) is
  preserved by construction on every platform.

This subsection does not introduce new code; it records that the
universal-platform contract already landed in Phase 2 Step 3 is an
**explicit, intentional invariant** of this plan, not an incidental
inheritance. Any future PR that narrows Anvil-first to a subset of
platforms shall be rejected and shall require a superseding ADR.

### Traceability
- Covered today by the existing `BukkitRTPWorld.getBiome` /
  `FoliaRTPWorld.getBiome` amendments (Phase 2 Step 3, landed
  2026-04-19). No new test class is required for the invariant
  itself, but the platform-adapter coverage tests already in
  `ADR-016` §9 exercise it on both Bukkit and Folia
  paths.

---

## 5. Risks & guardrails

1. **Claim-plugin churn.** Observational mode runs
   `GlobalRegionVerifiers` the same way the cache path does. On
   servers with expensive claim-plugin predicates, operators can
   disable the feature via `visitor.enabled: false`. The existing
   `period` already throttles cache-fill frequency; observational
   mode inherits the same throttle.
2. **Safety-log noise.** `LocationGenerator` already summarises
   `FailTypes` rather than logging per-event at WARN. Observational
   mode inherits this unchanged.
3. **Bad-location attribution.** Observational mode contributes to
   `pendingBadLocations` without an originating teleport request.
   Documented in the admin docs bullet (§4.9) and at the call site.
4. **Beta-default risk.** `visitor.enabled: true` by default per
   operator direction (2026-04-19) to maximise beta telemetry. The
   escape hatch is a single yaml toggle.

---

## 6. Historical sections (superseded)

The following sections described the `RegionDataVisitor`-based
approach that was reverted on 2026-04-20c. They are retained only for
historical reference; any future contributor should treat §§2–5 above
as authoritative and ignore the direction below.

### 6.1 (historical) Visitor-based architecture

> The system shall provide a per-region background task, hereafter the
> **Region Data Visitor**, that runs independently of the cache-filling
> pipeline and shares no mutable state with the teleport pipeline
> beyond the existing `MemoryShape` sinks. The visitor draws its next
> target cell from the same 1D Archimedean spiral mapping the region's
> shape already uses, via an independent cursor persisted in the
> shape's NBT; gates on `unkeptLocations.size() >= cacheCap`; calls
> `AnvilPrefilter.probeDetailed` directly; and writes verdicts into
> `addBadLocation` and the observed-biome set.

### 6.2 (historical) Dedicated configuration surface

> `visitor.rateHz`, `visitor.queueFillThreshold`,
> `visitor.maxBadLocationsPerShape`,
> `visitor.cursorPersistIntervalTicks`, and
> `visitorRateTicks` in `PerformanceKeys`.

### 6.3 (historical) Cursor persistence

> `visitorCursor: Long` tag in the shape NBT, flushed every
> `visitorCursorPersistIntervalTicks` ticks; Phase 8.1c would have
> landed the buffer format version bump.

### 6.4 (historical) Direct Anvil wiring

> Visitor calls `AnvilPrefilter.probeDetailed` directly rather than
> going through `LocationGenerator`. Required duplicating bad-location
> and biome-observation sink wiring across two code paths.

None of the above are to be implemented.
