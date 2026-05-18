# D-005 Proposal — Section C (Metrics Phase M2: Folia + Fabric)

**Source checklist**: [`CHECKLIST-metrics-and-multiserver.md > Section C`](CHECKLIST-metrics-and-multiserver.md) (rows C1–C5).
**Plan section**: [`METRICS_PLAN.md > Phase M2 — Folia + Fabric`](../METRICS_PLAN.md) (lines 417–423), [`Folia Aggregation`](../METRICS_PLAN.md) (lines 361–372), [`Module Placement`](../METRICS_PLAN.md) (lines 376–394).
**Predecessor work**: Section B closed 2026-05-17. `MetricsBinding` interface + `CoreMetrics.setBinding(...)` push pattern (B6); `PaperMetricsBinding` + `MetricsBindingDispatcher` (B7, B9); `InfoCmd` snapshot caching (B11); `ColourBands` (B12); `RTPCostMetricsCharts` (B13, runtime-cost charts already landed ahead of the gate — reconcile when wiring C4).
**Mode**: `[CODE]` once approved.

---

## 1. Affected classes / modules

### 1.1 `rtp-core` (SPI additive change — D-005 in its own right)

| Element | Kind | Change |
|---|---|---|
| `metrics/MetricsSnapshot.java` | record-like final class | **Add** `public final java.util.List<FoliaRegionSample> foliaRegions` field + accessor `foliaRegions()`. New constructor parameter at end (additive; old 15-arg constructor retained or migrated — see *§3 Constructor migration*). |
| `metrics/FoliaRegionSample.java` | **new** final class (immutable carrier) | Fields: `String regionId` (opaque, stable per server lifetime per the `regionQueueStatus` redaction note in `METRICS_PLAN.md`), `double tps1m`, `double mspt`, `int playerCount`, `long queueDepth`. No platform imports. |
| `metrics/MetricsBinding.java` | interface | **Add** `default java.util.List<FoliaRegionSample> foliaRegions() { return java.util.Collections.emptyList(); }`. Non-Folia bindings inherit the empty list. |
| `metrics/CoreMetrics.java` | class | `snapshot()` reads `binding.foliaRegions()` once and forwards into the new `MetricsSnapshot` parameter. |
| `configuration/enums/MetricsKeys.java` | **new** enum | Members: `foliaIncludeRegions` (default `true`, A4-resolved), `foliaAggregationTps` (default `mean`), `foliaAggregationMspt` (default `max`), `foliaAggregationTickBudget` (default `max`). Mirrors `EconomyKeys` / `LoggingKeys` shape. |
| `configuration/Configs.java` | bootstrap | Register `MetricsKeys.class → "metrics.yml"` next to `PerformanceKeys` line ~243. |

### 1.2 `rtp-folia/rtp-folia-common`

| Element | Kind | Change |
|---|---|---|
| `folia/metrics/FoliaMetricsBinding.java` | **new** class implementing `MetricsBinding` | Per-region sampler registry (one `BukkitTpsSampler`-shaped EMA per `RegionKey`, lazily created on first tick of that region). Aggregation per the `MetricsKeys.foliaAggregation*` keys; defaults match `METRICS_PLAN.md > Folia Aggregation`. |
| `folia/metrics/FoliaRegionTpsSampler.java` | **new** package-private | Single-region 1m/5m/15m EMA + MSPT. Lifted shape from `BukkitTpsSampler`, but the per-region scheduler hook is Folia's `RegionScheduler.runAtFixedRate`. |
| Test: `folia/metrics/FoliaMetricsBindingTest.java` | **new** | Aggregation correctness (max / mean / weighted-rejected); empty-region-list returns sentinels; `foliaIncludeRegions=false` returns empty `foliaRegions()`. |

### 1.3 `rtp-fabric/rtp-fabric-common`

| Element | Kind | Change |
|---|---|---|
| `fabric/metrics/FabricMetricsBinding.java` | **new** class implementing `MetricsBinding` | Hook into the server-tick callback chain wired by `MULTI_PLATFORM_PLAN.md > Step E2`. Reuses `BukkitTpsSampler`-shape EMAs (the math is platform-agnostic — extract a small `TpsEmaSampler` helper into `rtp-bukkit-common` or duplicate; see *§3 Open questions*). MSPT measured around the tick callback's start/end `System.nanoTime()`. |
| Test: `fabric/metrics/FabricMetricsBindingTest.java` | **new** | Synthetic-clock convergence test analogous to `BukkitTpsSamplerTest`. Fabric dev-server smoke deferred to C2 verification, not gating. |

### 1.4 Wiring (existing files, additive edits only)

| File | Edit |
|---|---|
| `MetricsBindingDispatcher` (`rtp-plugin/`) | Add Folia branch: if `Bukkit.isFoliaPresent()`-equivalent reflective probe succeeds, install `FoliaMetricsBinding` instead of `PaperMetricsBinding`. Keep Paper/Spigot precedence ordering intact. |
| `RTPFabricMod` / fabric bootstrap | Add `CoreMetrics.setBinding(new FabricMetricsBinding(...))` at server-start; teardown on server-stop. (Exact file confirmed during implementation — `rtp-fabric/rtp-fabric-common` entry point.) |
| `InfoCmd` (`rtp-core`) | Add a *verbose* path (gated on a new `/rtp info verbose` argument or `-v` flag) that, when `MetricsSnapshot.foliaRegions()` is non-empty, renders the per-region table behind a new `MessagesKeys.infoHealthCacheFoliaRegionRow` template. Non-Folia platforms render nothing extra — existing behaviour preserved. |
| `RTPCostMetricsCharts` (already landed) | Add the Phase M2 *Runtime health* chart group: `region_count`, `cache_pool_health`, `tps_buckets`, `mspt_buckets`, `pipeline_latency_buckets`, `memory_tracker_pressure`, `chunk_load_backlog_pressure`, `s005_violations_recent`. Bucketisation runs once per `Metrics.snapshot()` and is cached for the chart fetch tick. |

### 1.6 Cross-platform metrics consolidation (parity, added 2026-05-17)

User direction: "if we organize metrics we should organize metrics." The M1 bindings cover the `MetricsSnapshot` *surface* on every platform, but several metric-shaped reads still bypass the binding. Section C is the right moment to consolidate so all four platforms (Paper / Bukkit / Folia / Fabric) read every metric through the same boundary.

Audit findings (2026-05-17):

- **TPS**: three independent readers. `MetricsBinding#tps*m` (real, per-platform sampled), `RTPServerAccessor#getTPS(int)` (Folia returns a hard-coded constant at `AbstractFoliaServerAccessor.java:468`; Fabric returns nominal `20.0` at `FabricServerAccessor.java:1673` pending `MULTI_PLATFORM_PLAN.md > Step C`), and a redundant reflective probe in `RTPCostMetricsCharts#register` line 358.
- **Player count**: five readers across `PaperMetricsBinding:126`, `BukkitTpsSampler:104`, `RTPBukkitPlugin#onEnable:275`, `AbstractServerAccessor:365`, `AbstractFoliaServerAccessor:297`. The latter two are legitimate iterator reads (need `Player` instances); the first three are size-only reads.
- **Soft cap / max players**: four readers across `PaperMetricsBinding:136`, `BukkitTpsSampler:114`, `RTPBukkitPlugin:249`, `FabricEventBridge:477-484` (reflective `getMaxPlayers` / intermediary `method_3802`). Folia inherits `AbstractServerAccessor`.
- **Heap**: already consolidated via `HeapSampler` in `rtp-core`. No work.
- **MSPT**: no outside-the-binding reader exists. No work.

Sub-proposal:

- **1.6.1 Route `RTPServerAccessor#getTPS(int)` through `Metrics.snapshot()`.** Per-platform `getTPS(int)` implementations (`AbstractServerAccessor`, `AbstractFoliaServerAccessor`, `FabricServerAccessor`) delegate to `RTP.metrics.snapshot().tps*m` when the binding is non-NOOP; keep the existing body as a fallback when `Metrics.NOOP` is active (e.g. pre-`onEnable` callers). Closes the throttle-vs-metrics divergence: `rtp-core/.../tasks/TPS.java` and `MetricsSnapshot` will now read the same value on every platform.
- **1.6.2 Replace size-only player-count read at `RTPBukkitPlugin.java:275`** with `RTP.metrics.snapshot().playerCount`. Iterator reads at `AbstractServerAccessor:365` and `AbstractFoliaServerAccessor:297` stay (they need `Player` instances). One-line edit; snapshot field already exists.
- **1.6.3 Reconcile soft-cap reads.** Replace `Bukkit.getMaxPlayers()` fallback at `RTPBukkitPlugin.java:249` with `RTP.metrics.snapshot().softCap`. Pull the reflective `MinecraftServer.getMaxPlayers()` block at `FabricEventBridge.java:477-484` into `FabricMetricsBinding#softCap()` so the reflective probe lives at the binding boundary, not in event-bridge code. Login-reserve cache initialisation (the consumer in both sites) reads the snapshot.
- **1.6.4 Delete redundant reflective `getTPS()` fallback** at `RTPCostMetricsCharts#register` line 358. The `MetricsBindingDispatcher.install()` call site (B9) runs before bStats submission; the fallback is dead code now that `PaperMetricsBinding` is provably installed when the chart fetches.
- **1.6.5 ArchUnit drift guard.** Forbid the following raw calls outside the listed allow-list, in modules `rtp-core` + `rtp-bukkit` + `rtp-paper` + `rtp-folia` + `rtp-fabric` + `rtp-plugin`:
  - `Bukkit.getOnlinePlayers().size()` - allowed only inside `*MetricsBinding`, `*TpsSampler`.
  - `Bukkit.getMaxPlayers()` / `Server#getMaxPlayers()` - allowed only inside `*MetricsBinding`, `*TpsSampler`.
  - `Bukkit.getServer().getTPS()` / `Server#getTPS()` - allowed only inside `*MetricsBinding`; `RTPCostMetricsCharts` is removed from this list once 1.6.4 lands.
  - `MinecraftServer.getMaxPlayers()` reflective probe - allowed only inside `FabricMetricsBinding`.
  - Iterator reads (`for (Player p : Bukkit.getOnlinePlayers())`) are **out of scope** for the guard; they are a legitimate access pattern.
  - **Exempt modules**: `helpers/` (test harness), `commands-api/` (tab-complete reads via `OnlinePlayerParameter` are intentional), `addons/` (third-party scope).

Tests for 1.6:

- `RTPServerAccessorTpsParityTest` (new, in `rtp-core` test fixtures or `rtp-plugin`): synthetic non-NOOP binding produces a TPS value `T`; `RTPServerAccessor#getTPS(1)` returns `T` (not the platform fallback constant). Fallback path covered separately with `Metrics.NOOP` installed.
- `MetricsConsolidationArchTest` (new ArchUnit test in `rtp-core`'s arch-guard suite): asserts 1.6.5 allow-list rules.
- No new REQ-* row needed - these are implementation rearrangements under the existing REQ-RTP-OBS-001/002/003 contracts.

### 1.7 Docs / traceability

- `docs/dev/TRACEABILITY.md` — rows for new tests under REQ-RTP-OBS-001 (snapshot non-blocking) and REQ-RTP-OBS-003 (bounded sampler cost). No new REQ ID needed — Folia/Fabric are platform implementations of the obs-001/002/003 contracts.
- `docs/dev/METRICS_PLAN.md > Phase M2` — tick C1/C2/C3/C4 items.
- `CHECKLIST-metrics-and-multiserver.md` — tick C1–C5 as each lands.

---

## 2. Before / after structure

### 2.1 `MetricsSnapshot` constructor — additive, single migration

**Before** (current, 15 args):
```
new MetricsSnapshot(tps1m, tps5m, tps15m, mspt, playerCount, softCap,
    heapUsed, heapMax, queueDepth, pendingTeleports, memoryTrackerEntries,
    chunkLoadBacklog, avgPipelineMs, databaseLatencyMs, takenAtEpochMs);
```

**After** (16 args, append-only):
```
new MetricsSnapshot(tps1m, tps5m, tps15m, mspt, playerCount, softCap,
    heapUsed, heapMax, queueDepth, pendingTeleports, memoryTrackerEntries,
    chunkLoadBacklog, avgPipelineMs, databaseLatencyMs, takenAtEpochMs,
    foliaRegions);
```

Per the A5 D-005 ratification: "Later phases extend via additive constructor changes contained to the single binding constructor." Single call site (`CoreMetrics.snapshot()`) updates; all reflection-free downstream consumers (`InfoCmd`, `TestFullCmd`, `RTPCostMetricsCharts`, tests) read the new field through the accessor and tolerate `emptyList()`.

### 2.2 Push-binding lifecycle (unchanged from B6/B9)

```
server start →
  MetricsBindingDispatcher.install():
    if Folia detected   → CoreMetrics.setBinding(new FoliaMetricsBinding(...))
    elif Paper detected → CoreMetrics.setBinding(new PaperMetricsBinding())
    else                → CoreMetrics.setBinding(new BukkitTpsSampler(...))
server stop →
  MetricsBindingDispatcher.uninstall() → CoreMetrics.setBinding(MetricsBinding.NOOP)
```

Fabric uses its own bootstrap path (no `Bukkit.*`), invoking `CoreMetrics.setBinding(new FabricMetricsBinding(...))` from the mod entry point. No interface change required.

### 2.3 `MetricsKeys` shape (mirrors `EconomyKeys` precedent)

```yaml
# metrics.yml (auto-generated defaults)
foliaIncludeRegions: true        # A4-resolved; per-region detail in MetricsSnapshot.foliaRegions()
foliaAggregationTps: mean        # mean | max
foliaAggregationMspt: max        # mean | max
foliaAggregationTickBudget: max  # mean | max
version: "1.0"
```

---

## 3. Open questions for D-005 approval

1. **Constructor breakage tolerance.** The current `MetricsSnapshot` ctor is called from `CoreMetrics`, `PaperMetricsBinding` test fixtures, and `MetricsSnapshotTest`. Adding a 16th arg breaks all of them at compile time. Two options:
   - **(a)** Append the 16th param; fix the 3 call sites. Single atomic commit. Recommended.
   - **(b)** Add an overload `MetricsSnapshot(... 15 args ...)` that delegates with `Collections.emptyList()`. Avoids touching tests but locks a second public constructor signature.
   *Default chosen unless overridden:* **(a)**.

2. **Shared TPS EMA helper.** Folia per-region sampler and Fabric sampler both want the EMA math already present in `BukkitTpsSampler`. Two options:
   - **(a)** Extract `TpsEmaSampler` into `rtp-core/.../metrics/` (no platform imports — it's pure math). Recommended.
   - **(b)** Duplicate the math per platform.
   *Default chosen unless overridden:* **(a)**, with `BukkitTpsSampler` refactored to delegate (still trivial; existing tests stay green).

3. **`/rtp info verbose` invocation surface.** Two options:
   - **(a)** New subcommand `verbose` (`/rtp info verbose`) — Brigadier-friendly, tab-completable.
   - **(b)** New flag `-v` parsed inside `InfoCmd` — terser but inconsistent with the rest of the command surface.
   *Default chosen unless overridden:* **(a)**.

4. **`FoliaRegionSample.regionId` stability.** Per `METRICS_PLAN.md > regionQueueStatus redaction in network mode`, region ids should be "stable opaque ids (`region-0`, `region-1`, …) assigned at startup, never crosses the wire as the human name." I'll mint ids from a per-`FoliaMetricsBinding` `AtomicInteger` keyed off the Folia `RegionKey`. Confirm this is acceptable for the M2 console / `/rtp info verbose` audience (the network-mode redaction concern is M3 and downstream).

5. **C4 chart-group landing reconciliation.** The B-section closing note in the checklist says `RTPCostMetricsCharts` runtime-cost charts already landed ahead of the gate. Confirm the M2 *Runtime health* chart group is additive (new chart IDs in `BStatsChartIds`, new helper methods), not a rewrite of the cost charts.

---

## 4. Relevant REQs / ADRs

- **REQ-RTP-OBS-001 / 002 / 003** (`REQUIREMENTS.md §1.8`) — non-blocking snapshot, single-sample pipeline recording, bounded sampler cost. C1/C2 must respect all three on every platform.
- **REQ-RTP-F-013** — all user-facing messages configurable. The new `MessagesKeys.infoHealthCacheFoliaRegionRow` row template must ship EN+ES defaults and survive `LocaleResourceParityTest`.
- **REQ-RTP-S-005** — no main-thread chunk I/O. Folia binding's per-region sampler runs on the region scheduler (already region-owned); no chunk access required.
- **ADR-032** — pipeline histogram never-resets. Unaffected (C-section doesn't touch the histogram).
- **`AGENTS.md > Architecture Boundaries`** — `MetricsKeys` and `FoliaRegionSample` land in `rtp-core` (no platform imports); `FoliaMetricsBinding` lands in `rtp-folia-common` (platform-specific); ArchUnit guard continues to pass.
- **`METRICS_PLAN.md > Folia Aggregation`** — `max` for mspt/tickBudget, `mean` for tps*. Operator-overridable via `metrics.folia.aggregation.*`.
- **`METRICS_PLAN.md > Module Placement`** — module destinations match exactly.

---

## 5. Risks and trade-offs

- **Risk: per-region sampler count on extreme-region-count Folia deployments.** Mitigation: `foliaIncludeRegions: false` flips off the per-region rendering (A4 carve-out); the aggregation itself remains O(regions) per snapshot read, which is acceptable at the documented 1Hz snapshot cadence.
- **Risk: Folia API surface drift across MC versions (1.20.x / 1.21.x / 26.x).** Mitigation: the platform-specific scheduling lives in `rtp-folia-common` only, behind the existing Folia version-shim modules (`rtp-folia-v1_20_R1`, `rtp-folia-v1_21_R1`, `rtp-folia-v26_1_R1`). If a per-version shim is needed for `RegionScheduler.runAtFixedRate` signature changes, it lands in the per-version module.
- **Risk: Fabric tick-callback wiring not yet ratified.** Per `MULTI_PLATFORM_PLAN.md > Step E2`. If E2 lands after C2, C2 ships as a stub returning `UNSAMPLED` sentinels and is finalised when E2 is approved. The interface/binding registration stays correct.
- **Trade-off: separate `metrics.yml` vs. folding into `performance.yml`.** Chose separate file: keeps reporting knobs (`metrics.*`) cleanly separated from throttle/tuning knobs (`performance.*`). One-time `/rtp reload` cost on first launch (file generation) is negligible.
- **No ADR supersession.** No existing ADR contradicted; no superseding ADR required.

---

## 6. Out of scope (deferred or already done)

- **Real-time per-tick CPU budget** (`tickCpuBudgetMsAnalytical`, `tickCpuMsP99`, `tickCpuMsP999`, `tickCpuOvershoots`) — deferred per `METRICS_PLAN.md` Phase M2 last bullet, gated on a dedicated ADR not yet drafted.
- **`/rtp info json`** — deferred to a follow-up; verbose human-readable rendering is enough for the M2 gate.
- **Multi-server network telemetry consumers** — Phase M3, not Section C.
- **`RTPCostMetricsCharts` runtime-cost group** — already landed ahead of the gate per the B-section closing note; C4 only adds the *Runtime health* group beside it.

---

## 7. Verification gate (matches checklist row C "Gate")

> dual-runtime smoke test — one JAR loads on Paper and Fabric, `/rtp info verbose` prints sane health on both. Coordinates with `MULTI_PLATFORM_PLAN.md > Phase 2 acceptance gate`.

Per-row verification commands:

- **C1**: `.\gradlew :rtp-folia:rtp-folia-common:test --tests "*FoliaMetricsBinding*"` green; `MetricsSnapshot.foliaRegions()` populated on a Folia devstack; `metrics.folia.includeRegions: false` empties the list without breaking the snapshot.
- **C2**: `.\gradlew :rtp-fabric:rtp-fabric-common:test --tests "*FabricMetricsBinding*"` green; Fabric dev-server `/rtp info` shows non-zero TPS.
- **C3**: `.\gradlew :rtp-core:test --tests "*InfoCmd*"` green; new test asserts the per-region rows are emitted exactly when `verbose` is the trailing arg and `foliaRegions()` is non-empty.
- **C4**: new `BStatsChartIdsTest` rows confirming the *Runtime health* chart IDs are present, non-fingerprinting (UUID/IPv4/hostname regex still clean), and that bucketisation runs exactly once per snapshot fetch.
- **C5**: `.\check_traceability.sh` clean.
- **Section gate**: `.\gradlew build` green (the *Final Full Build* rule); dual-runtime smoke transcript captured in the checklist's section-C gate note.

---

## 8. Implementation order (once approved)

1. SPI change first (lowest risk, highest blast radius): `FoliaRegionSample` + `MetricsSnapshot` 16-arg ctor + `MetricsBinding.foliaRegions()` default + `CoreMetrics` wire-up. Update `MetricsSnapshotTest` / `CoreMetricsTest` / `PaperMetricsBindingTest` for the new arg. Run `:rtp-core:test`.
2. `MetricsKeys` + `metrics.yml` registration in `Configs`. Run `:rtp-core:test`.
3. (Optional from §3 Q2): extract `TpsEmaSampler` shared helper; refactor `BukkitTpsSampler` to delegate; re-run its 7 tests.
4. `FoliaMetricsBinding` + `FoliaRegionTpsSampler` + test. Run `:rtp-folia:rtp-folia-common:test`.
5. `FabricMetricsBinding` + test (stub if E2 not yet ratified). Run `:rtp-fabric:rtp-fabric-common:test`.
6. `MetricsBindingDispatcher` Folia branch + fabric-bootstrap call. Run `:rtp-plugin:test --tests "*MetricsBindingDispatcher*"`.
7. **Cross-platform consolidation (§1.6)**. Apply 1.6.1 → 1.6.4 in order; land `MetricsConsolidationArchTest` (1.6.5) last so the guard catches any residual stray reads. Run `:rtp-core:test`, `:rtp-paper:rtp-paper-common:test`, `:rtp-folia:rtp-folia-common:test`, `:rtp-fabric:rtp-fabric-common:test`, `:rtp-plugin:test`.
8. `InfoCmd verbose` path + EN/ES message templates + `InfoCmdTest` row. Run `:rtp-core:test --tests "*InfoCmd*"`.
9. `RTPCostMetricsCharts` *Runtime health* additions + `BStatsChartIds` constants + anti-fingerprinting test rows.
10. `TRACEABILITY.md` rows + `METRICS_PLAN.md` Phase M2 boxes + checklist C1–C5 ticks (plus a new C6 row capturing §1.6 — see C-section checklist amendment below).
11. `.\gradlew build` (Final Full Build gate).

---

*Approval status (2026-05-17): §1.6 *Cross-platform metrics consolidation* approved in-session ("go") with ArchUnit guard scope confirmed: `rtp-core` + `rtp-bukkit` + `rtp-paper` + `rtp-folia` + `rtp-fabric` + `rtp-plugin` in scope; `helpers/`, `commands-api/`, `addons/` exempt. §1.1–§1.5 and §3 open questions still awaiting explicit approval before code lands; defaults (constructor option (a), `TpsEmaSampler` extraction option (a), `/rtp info verbose` subcommand option (a), opaque `region-N` ids, additive C4 chart group) stand unless overridden.*
