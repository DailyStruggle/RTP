# ADR-039 — `/rtpadmin` Diagnostic Surfaces (Biome Map, Bad-Selection Heatmap, Metrics Readouts)

**Status:** Proposed
**Date:** 2026-05-13
**Target release:** `3.0.0-beta.4` (read-only surfaces only, including cross-backend aggregation via ADR-036's shared store and in-memory time-series charts sampled by a repurposed `MemoryTracker`; deeper visualizations may slip to beta.5)
**Supersedes:** —
**Superseded by:** —
**Related:** ADR-035 (Interactive Menus via Written Book), ADR-036 (Network Mode: Multi-Server, Multi-Proxy RTP), ADR-037 (Harden RTP Config Commands), ADR-038 (`/rtpadmin` Setup Wizards), ADR-016 (Anvil Subsystem), ADR-028 (L3 Backlog Cache), [`docs/dev/METRICS_PLAN.md`](../dev/METRICS_PLAN.md)

---

## Context

ADR-038 gives admins **wizards that change configuration**. It does not give them surfaces that **answer questions** — "why is this region rerolling so often?", "what biome distribution will players actually see inside this shape?", "which areas of the bounded region are failing safety the most?", "is the pipeline starved or is the L1/L2/L3 cache the bottleneck?".

Today, the only way to answer those questions is:

- `/rtp info` — short text dump, no spatial information.
- `/rtp test full` — synthetic sample, not a real distribution.
- Tail the server log and read `MemoryTracker` / scan-task lines.
- Read the YAML and reason about it.

That is fine for the author of the plugin. It is not acceptable as the only diagnostic surface for the admins ADR-038 is supposed to empower. Without a diagnostic layer, the wizards in ADR-038 become "guess, commit, test, repeat" — the very workflow ADR-037's preview/dry-run was designed to avoid.

This ADR introduces **diagnostic surfaces**: read-only views, opened from the same book-menu primitive ADR-035 defines, that present spatial and metric information to admins. It does **not** add any new configurable knob, and it does **not** open a write path. Diagnostic surfaces are a sibling of wizards, not a replacement.

## Decision

Adopt a **read-only diagnostic surface layer**, mounted under `/rtpadmin diag <surface>`, rendered through the ADR-035 `MenuRenderer` (book first, chat fallback), and sourced exclusively from data the codebase already produces or can produce without new I/O on the main thread.

Four surfaces ship in `3.0.0-beta.4` (three spatial/tabular plus one time-series chart surface backed by a repurposed `MemoryTracker` sampler). Additional surfaces are explicitly deferred (see *What this ADR is not*).

### The four surfaces (beta.4)

1. **Biome map** — `/rtpadmin diag biomes <region>`
   For the named region's shape, render biome distribution **from region data the running pipeline already retains** — the verified entries in `keptLocations`/`unkeptLocations`/`backlogLocations` for that region (their stored biome attribution). **No** anvil sweep, **no** chunk loads, **no** TTL-driven re-scan. Render as:
   - A pageable table: biome → observed share of retained cells → permitted/blocked under current `biomes`/`badBiomes` config.
   - An optional **ASCII map page** (book-friendly, ~14×19 grid): one glyph per retained cell bucketed onto the shape's bounding box, colored by biome family (warm/cold/wet/dry/nether-ish/end-ish). Empty buckets render as `·`; resolution is whatever the bounding box downsamples to under the page budget.
   - One annotation row per page: retained cells counted, cells excluded by current bad-biome list, cells excluded by water/lava prefilter (all read off the existing region entries, not re-computed).
2. **Bad-selection heatmap** — `/rtpadmin diag heatmap <region> [window]`
   For the same shape, render the **per-cell failure rate** observed by the live pipeline over the last `window` (default: 1 hour, max: 24 h on Folia/Paper, capped by the sampler's ring buffer). Same ~14×19 grid; glyph intensity (`·` → `▒` → `█`) encodes fail-rate. A second page legends the **dominant failure mode per quadrant** (`badBiome`, `nullChunk`, `unsafeBlock`, `claimBlocked`, `vertNoLand`). Source: `MemoryTracker` + `FailTypes` counters, sampled by a new lock-free ring per region, **not** persisted across restarts in beta.4.
3. **Metrics readouts** — `/rtpadmin diag metrics [section]`
   A guided view of the live values defined in `METRICS_PLAN.md`. Sections:
   - `tps` — TPS / MSPT (per-region on Folia), with the `tickCpuBudget` analytical bound.
   - `cache` — L1/L2/L3 fill, hit %, refill rate, stalls.
   - `pipeline` — active `TeleportPipelineTask` count, chunk-ticket count, queue depth, fairness FIFO depth.
   - `network` — only when ADR-036 network mode is enabled: snapshot age, peer count, reservation token in-flight, per-backend L1/L2/L3 fill and pipeline depth read from the shared network snapshot.
   Each section is one book page. Numbers refresh by **re-opening** the surface (ADR-035's no-live-update constraint); a `[refresh]` click runs `/rtpadmin diag metrics <section>` again with a fresh token.
4. **Metric chart (time-series)** — `/rtpadmin diag chart <metric> [window]`
   Renders a **per-metric time-series chart vs time** for any key the live metrics subsystem currently exposes (enumerated dynamically per contract 6). The chart is sourced from an **in-memory ring** that `MemoryTracker` — repurposed as a periodic sampler/condenser — fills on its existing scheduler tick: each tick, every live sampler key is read once and appended to its per-metric ring; older samples are **condensed** in place (downsampled to coarser buckets at fixed boundaries — e.g. raw 1 s for the last 5 min, 10 s for the last 1 h, 1 min for the last 24 h) so total per-metric memory stays bounded regardless of window. No new scheduler is introduced; `MemoryTracker`'s existing periodic pass gains one cheap read-and-append per registered key, with sampling already off the main/region thread (S-005). Render as a single book page: an ASCII sparkline grid (~14×19) sized to the chosen window, with y-axis auto-scaled to the observed range, a min/avg/max annotation row, and a legend naming the metric, its unit (from the metrics subsystem), and the active condensation tier. Windows are bounded enums (`5m|1h|6h|24h`) sized to the ring tiers; values beyond the ring's retention render as `— (outside retention)`. Like the heatmap, the time-series ring is **in-memory only** in beta.4 — no new DB writes, no new schema, no new heartbeat.

### Cross-backend aggregation (network mode enabled)

When ADR-036 network mode is enabled, the `metrics`, `chart`, and `network` surfaces gain an optional `--backend <id|all>` selector and **read** from the **same shared store** ADR-036's network snapshot and the metrics subsystem (`METRICS_PLAN.md`) already write (Postgres / MySQL / Redis, dispatched via `AbstractSQLDatabaseAccessor` or the Redis driver). Diagnostic surfaces are a **strict consumer** of that store; they do not write, do not introduce a publisher, do not introduce a new transport, schema, table, or dependency.

- **Source rows.** Cross-backend reads consume only rows that the **metrics subsystem** and **ADR-036 network snapshot** already publish on their own cadence (the `network_snapshot` / `serverIds` heartbeat row described in `METRICS_PLAN.md` and ADR-036). If a metric isn't currently written to the shared store, the cross-backend page renders that field as `—`; this ADR adds no new column, no new key, and no new write.
- **Consumption.** `/rtpadmin diag metrics --backend <id>` reads that backend's existing network-snapshot row directly. `--backend all` reads every non-expired peer row and renders one page per backend plus an aggregate page (sum for counts, weighted mean for rates, max-age for staleness). The `chart` surface is **origin-only by default**; `--backend <id>` plots **only** if the chosen peer's metric key is present in its already-published network-snapshot row (the per-metric time-series ring is local to each backend and is **not** transmitted), and renders `— (cross-backend chart unavailable)` otherwise. `--backend all` on `chart` overlays the latest per-backend value the network snapshot does carry, not the time-series. Origin-only behavior (no selector) is preserved as the default.
- **Surfaces that do not aggregate.** The `biomes` and `heatmap` surfaces are **origin-only**, with or without network mode: neither retained-region-entry biome tallies nor the in-memory fail-rate ring are written to the shared store by any subsystem, and this ADR does not propose to start writing them. `--backend` on those surfaces is rejected with `menu.surface.network.unsupported` (REQ-RTP-F-013). The `chart` surface's **time-series** is likewise origin-only (the per-metric ring is in-memory and not published); cross-backend `chart` invocations fall back to the per-tick value already in the network snapshot per the bullet above.
- **Staleness.** Each row's `generation` / `last_seen_epoch` (as already published by ADR-036) is rendered verbatim so admins can see when a backend last reported. Stale rows past the existing network-snapshot TTL render as `— (stale)` with the age; missing backends render as `— (no report)`. No surface ever blocks on a peer.
- **Store-down behavior.** If the shared store is unreachable, cross-backend pages collapse to origin-only with a one-line `menu.surface.network.unavailable` notice (REQ-RTP-F-013). The origin surface keeps working.
- **Write boundary.** Surfaces write **nothing** to the shared store — not on click, not on a heartbeat, not at all. The only persistent side effect of a surface invocation is the structured audit line on the local node (ADR-037 audit stream). All cross-backend data shown was put there by the metrics subsystem or ADR-036, not by this feature.

### What ties them together

All four surfaces share:

- **Source-of-truth contract.** A surface may only read data the codebase already exposes through `MemoryTracker`, `FailTypes`, the region/queue accessors, the metrics samplers, or — when ADR-036 network mode is enabled — the peer rows that the **metrics subsystem and ADR-036 already write** to the shared store on their existing cadence. **No** surface may trigger chunk loads, anvil reads, new DB writes, or *new* DB queries beyond reading rows other subsystems already maintain; the shared-store read piggybacks on ADR-036's existing accessor and adds no new transport, schema, or write path. If a number isn't already being computed or already being written, the surface reports `—` and names the metrics-plan phase that would unlock it.
- **Threading.** Surface rendering runs off the main/region thread. On Folia the snapshot is taken via `Bukkit.getGlobalRegionScheduler()` for cross-region aggregates; per-region values are read from the owning region scheduler. S-005 applies in full.
- **Token model.** Surfaces reuse ADR-035's UUID-bound, single-use, TTL'd token registry. A `[refresh]` button mints a new token; a stale token returns a configurable `menu.surface.expired` message (REQ-RTP-F-013).
- **Audit.** Every surface invocation logs one structured `RTP.log(INFO, …)` line with `(actor, surface, region?, window?)`, reusing ADR-037's audit stream (no new sink).
- **Permissions.** `rtp.admin.diag.<surface>` per surface, plus the umbrella `rtp.admin.diag` matching ADR-038's `rtp.admin.wizard.*` shape.
- **No writes.** Diagnostic surfaces have **no** `[apply]` button. A surface that wants to drive a change ends with a `[open wizard …]` click that hands off to ADR-038. The handoff is the only coupling.

## Module placement (Architecture Boundaries)

| Concern | Module | Notes |
|---|---|---|
| `DiagnosticSurface` interface, `SurfaceModel` (POJO snapshot), `BiomeSampleSnapshot`, `FailHeatSnapshot`, `MetricsSnapshot` | `rtp-api` | No platform imports. Stable for addons to read; deliberately not for them to register their own surfaces in beta.4. |
| Per-region fail-rate ring buffer, snapshot assembly, biome tally over existing region entries | `rtp-core` | New `diagnostics/` subpackage. Reads `MemoryTracker` / `FailTypes` / `keptLocations` / `unkeptLocations` / `backlogLocations`; **no new I/O paths**. |
| `/rtpadmin diag` subcommand, surface → `MenuModel` rendering | `rtp-plugin` | Composes `commands-api`, ADR-035 renderer, and the snapshots from `rtp-core`. |
| Adapter-specific concerns (e.g., Folia per-region TPS) | platform adapters | Same pattern as existing `rtp-folia` aggregation in `METRICS_PLAN.md section Folia Aggregation`. |

No change to `rtp-api`'s wizard interfaces from ADR-038. Surfaces are a sibling, not a subclass.

## The six contracts

1. **Read-only.** A surface shall not mutate config, queues, caches, or any persistent state. Audit log writes and the menu token registry are the only side effects permitted.
2. **No new I/O hotpaths.** A surface shall not introduce chunk loads, anvil-file reads, or new transports. Local data must already be in memory because the running pipeline (region entries, `MemoryTracker`, `FailTypes`, metrics samplers) put it there. Cross-backend data is read from the **same** shared-store accessor ADR-036 already uses for network snapshots; surfaces add no new connection pool, scheduler, or schema beyond ADR-036's published row shape. Sampling is piggyback-only.
3. **Snapshot, not stream.** A surface renders one immutable snapshot per token. Live-update is out of scope (consistent with ADR-035's no-live-update note). Refresh is explicit.
4. **Bounded memory.** Per-region fail-rate rings are fixed-size (configurable, default 4096 samples ≈ 1–24 h depending on traffic). The biome surface allocates only a transient snapshot view; it caches nothing and adds no new per-region state.
5. **Graceful degradation.** When a data source isn't available (e.g., metrics phase not shipped, anvil disabled for the world, network mode off), the surface renders the row as `—` with a one-line reason and a doc link, never an error.
6. **Dynamic enumeration (no hardcoded catalogs).** A surface shall not bake in static lists of metric keys, command/parameter names, region names, biome families, fail-mode labels, or backend ids. Every enumeration is resolved at render time from the live source already used elsewhere in the codebase:
   - **Metric rows** — enumerated from whatever the metrics subsystem currently exposes (live sampler keys for the origin surface; the column/key set actually present on the shared-store rows for cross-backend pages). New metrics added by `METRICS_PLAN.md` phases appear in `/rtpadmin diag metrics` without an ADR-039 code change.
   - **Subcommands, parameters, and value enums** — sourced from the same tab-complete supplier `/rtpadmin` and `/rtp` config commands already use (ADR-037's shared parse/tab-complete grammar). Adding a config knob in ADR-037 makes it visible to relevant surface pickers automatically; surfaces never maintain a parallel list.
   - **Region names** — read from the live in-memory region registry, the same source `/rtp info` consults. Newly created regions (including those created mid-session by an ADR-038 wizard) are pickable on next surface open without a reload or hardcoded refresh.
   - **Fail-mode labels** — enumerated from the `FailTypes` enum / registry actually populating the ring; new fail modes appear in legends without renderer edits.
   - **Backend ids** — enumerated from the peer rows present in ADR-036's network snapshot at read time; no static `serverIds` list in the surface code.
   A surface that needs a list shall obtain it through one of the above providers or render `—` with the configurable `menu.surface.source.unavailable` reason (REQ-RTP-F-013). Hardcoded catalogs are a contract violation.

## What this ADR is **not**

- **Not** a metrics-plan rewrite. It consumes `METRICS_PLAN.md`; it does not redefine any metric, bound, or sampler. Metrics phases that haven't shipped render as `—`.
- **Not** a live dashboard. No websocket, no HTTP endpoint, no auto-refresh, no Prometheus exporter. Those are explicitly out of scope and tracked in `METRICS_PLAN.md section Phased Roadmap` / a future ADR.
- **Not** a write path. No `[apply]` from a diagnostic page. Surfaces hand off to ADR-038 wizards or to ADR-037 config commands; they never bypass either.
- **Not** the place for the heatmap to gain persistence. The fail-rate ring is in-memory only in beta.4. Persisting samples across restarts (and the schema work that implies) is deferred.
- **Not** an addon extension point in beta.4. The interface is sealed against third-party surfaces until the API has soaked one release cycle.
- **Not** restricted to origin-only for surfaces whose data is already written cross-backend. When ADR-036 network mode is enabled, the `metrics` and `network` surfaces read peer rows the **metrics subsystem and ADR-036 already publish** (no new writer) and render per-backend and aggregate pages. The `biomes` and `heatmap` surfaces remain origin-only because their data is not written to the shared store by any subsystem and this ADR adds none. When network mode is disabled, origin-only is the only possible behavior and is the default.
- **Not** a database writer. This ADR introduces zero new persisted state: no new table, no new column, no new Redis key, no new publisher, no new heartbeat. All cross-backend reads consume rows other subsystems already maintain.

## Initial surface catalog (beta.4)

| Surface | Command | Source | Refresh cost |
|---|---|---|---|
| `biomes` | `/rtpadmin diag biomes <region>` | Existing region entries (`keptLocations` / `unkeptLocations` / `backlogLocations`) | O(retained cell count) snapshot copy |
| `heatmap` | `/rtpadmin diag heatmap <region> [window]` | In-memory fail-rate ring (`MemoryTracker` + `FailTypes`) | O(ring size) snapshot copy |
| `metrics` | `/rtpadmin diag metrics [section]` | Live metrics samplers | O(section size) read |
| `chart` | `/rtpadmin diag chart <metric> [window]` | In-memory per-metric time-series ring (`MemoryTracker` periodic sampler/condenser) | O(window bucket count) snapshot copy |
| `network` | `/rtpadmin diag network [--backend <id\|all>]` | Existing ADR-036 network-snapshot rows (read-only) | O(peer count) row reads |

`--backend <id|all>` is accepted on `metrics`, `chart`, and `network` when ADR-036 network mode is enabled (defaulting to the origin backend); `biomes` and `heatmap` are origin-only and reject the selector with a configurable message. On `chart`, `--backend` plots only the per-tick value the network snapshot already carries; the time-series itself is origin-only because the per-metric ring is not published cross-backend.

Deferred surfaces (named here so they don't get re-proposed informally): `claims` (overlay of claim-plugin-blocked cells; gated on EXTERNAL_HOOKS/ADR-026 surface), `pipeline-trace` (per-attempt timeline; needs persistent sample store), `players` (per-player teleport history; privacy review required).

## `/rtpadmin diag` command grammar

```
/rtpadmin diag biomes  <region>
/rtpadmin diag heatmap <region> [window=1h|6h|24h]
/rtpadmin diag metrics [tps|cache|pipeline|network] [--backend <id|all>]
/rtpadmin diag chart   <metric>             [window=5m|1h|6h|24h] [--backend <id|all>]
/rtpadmin diag network [--backend <id|all>]
```

`--backend` is rejected with a configurable `menu.surface.network.disabled` message when network mode is off, and with `menu.surface.network.unsupported` when used on the `biomes` / `heatmap` surfaces (S-007).

Tab completion uses ADR-037's shared parse/tab-complete grammar end-to-end — `<region>` completes against the **live in-memory region registry** (same source `/rtp info` reads), `<window>` against the bounded enum, sections against the **metric keys actually present in the live samplers / shared-store rows**, and `--backend` against the **peer ids present in ADR-036's current network snapshot**. None of these lists are hardcoded in the surface layer (contract 6).

## Permissions

| Node | Default | Grants |
|---|---|---|
| `rtp.admin.diag` | op | Umbrella; required by every surface |
| `rtp.admin.diag.biomes` | op | Biome map surface |
| `rtp.admin.diag.heatmap` | op | Bad-selection heatmap |
| `rtp.admin.diag.metrics` | op | Metrics readouts |
| `rtp.admin.diag.chart` | op | Per-metric time-series chart surface |
| `rtp.admin.diag.network` | op | Cross-backend `network` surface and `--backend` selector on other surfaces |

Permission denials emit the configurable `menu.surface.denied` message (REQ-RTP-F-013); they are not silent (S-007).

## Concrete affected classes (informational; final shape decided during implementation)

- New: `rtp-api/.../diagnostics/DiagnosticSurface.java`, `SurfaceModel.java`, `BiomeSampleSnapshot.java`, `FailHeatSnapshot.java`, `MetricsSnapshot.java`.
- New: `rtp-core/.../diagnostics/FailRateRing.java` (per-region lock-free ring; bounded; piggybacks on `FailTypes` increments).
- New: `rtp-core/.../diagnostics/BiomeSnapshotAssembler.java` (iterates existing region entries — `keptLocations` / `unkeptLocations` / `backlogLocations` — and tallies their already-attributed biomes; no anvil read, no chunk load, no cache).
- New: `rtp-core/.../diagnostics/MetricsSnapshotAssembler.java` (assembles the per-section snapshot from existing samplers; **enumerates** the metric keys actually present rather than reading a static list).
- New: `rtp-core/.../diagnostics/MetricTimeSeriesRing.java` (per-metric multi-tier in-memory ring: e.g. tier-0 = raw last 5 min, tier-1 = 10 s last 1 h, tier-2 = 1 min last 24 h; condensation pushes oldest tier-0 buckets into tier-1 averages, oldest tier-1 buckets into tier-2 averages; fixed-size, lock-free append, bounded heap regardless of window).
- New: `rtp-core/.../diagnostics/ChartSnapshotAssembler.java` (snapshots a single metric's ring into a `MetricChartSnapshot` POJO for the renderer; no I/O).
- New: `rtp-api/.../diagnostics/MetricChartSnapshot.java` (POJO: metric key, unit, tier label, bucket timestamps, bucket values, min/avg/max).
- Reused (no new class): the live region registry (same accessor `/rtp info` uses) for region-name enumeration, ADR-037's shared parse/tab-complete supplier for subcommand/parameter/value enumeration, the `FailTypes` registry for fail-mode labels, and ADR-036's network-snapshot row reader for backend-id enumeration. Surfaces obtain these by reference; they do not maintain parallel catalogs.
- New: `rtp-core/.../diagnostics/NetworkSnapshotReader.java` (reads peer rows the metrics subsystem and ADR-036 already write, via the existing `AbstractSQLDatabaseAccessor` or Redis driver; no new connection pool, no writer counterpart).
- New: `rtp-plugin/.../commands/diag/DiagCmd.java` (parses `/rtpadmin diag …`), `DiagBiomesCmd`, `DiagHeatmapCmd`, `DiagMetricsCmd`, `DiagChartCmd`.
- New: `rtp-plugin/.../menu/surfaces/{BiomeMapRenderer,HeatmapRenderer,MetricsRenderer,MetricChartRenderer}.java` (translate snapshots to ADR-035 `MenuModel`; the chart renderer turns a `MetricChartSnapshot` into a book-page ASCII sparkline).
- Touched: `MemoryTracker` — repurposed as the **diagnostic periodic sampler/condenser**. Two cheap additions: (a) the existing notification hook for the per-region fail-rate ring (heatmap surface), and (b) a new per-tick pass over the dynamically enumerated metrics keys that appends one sample per key to its `MetricTimeSeriesRing` and runs the tier-condensation step in place. No new scheduler, no new thread, no new allocation on the teleport hot path; the pass runs on `MemoryTracker`'s existing periodic cadence and reads samplers that already exist.
- No touch: `rtp-core` selection/pipeline core logic, ADR-037 config command surface, ADR-038 wizards. Cross-coupling is one-way (surface → wizard handoff only).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Web dashboard / HTTP endpoint | New attack surface, new dependency, multi-platform burden, conflicts with ADR-035's "menus reuse the command pipeline" stance. |
| Prometheus / OpenTelemetry exporter | Valuable but a different audience (ops, not in-game admins). Tracked in `METRICS_PLAN.md`; not blocking beta.4. |
| Render inside `/rtp info verbose` only | Text-only, no spatial view; admins already have this and it doesn't answer the biome/heatmap questions. |
| Inventory map item / filled map | Reintroduces exactly the inventory-desync class ADR-035 was created to avoid. |
| Persist fail-rate samples to DB | New schema, new migration, new shutdown-flush hazard. Deferred; in-memory ring is sufficient for "is this region misconfigured right now?". |
| Persist the per-metric time-series ring to DB | Same hazards as persisting heatmap samples (schema, migration, shutdown-flush); the in-memory multi-tier ring already gives 24 h of bounded retention with no I/O. Deferred to phase 2. |
| Add a dedicated scheduler/thread for time-series sampling | `MemoryTracker` already runs a periodic pass; adding a second scheduler duplicates that surface and complicates Folia region-scheduler ownership. Rejected — repurpose the existing pass. |
| Sample every metric every tick at raw resolution for 24 h | Unbounded heap (`O(metrics × tickrate × 24h)`). Multi-tier in-place condensation keeps heap bounded by tier sizes, not by window length. |
| Render charts as a Bukkit map item or external image | Reintroduces the inventory-surface / external-dependency classes ADR-035 already rejected. Book-page ASCII sparklines are sufficient for triage and stay inside the menu primitive. |
| Live websocket-pushed surface | No live updates per ADR-035 section no-live-update; would also require an out-of-game client. |
| Sample biomes via real chunk loads | S-005 violation; defeats the point of the prefilter. |
| Trigger a fresh anvil sweep on first open per TTL window | New I/O hotpath; violates contract 2 and duplicates work the prefilter already did when the region's entries were generated. The retained region entries are the authoritative cache. |
| Make surfaces an addon extension point in beta.4 | Premature; the snapshot POJOs need one release cycle to stabilize before third parties depend on them. |
| Combine diagnostic + write in one page | Violates contract 1 (read-only); muddles the audit story; re-opens the "guess and apply" workflow ADR-037/038 closed. |
| Hardcode the metric / region / subcommand / backend / fail-mode catalogs in each surface | Violates contract 6 (dynamic enumeration); every `METRICS_PLAN.md` phase, ADR-037 grammar change, ADR-038 region creation, new `FailTypes` entry, and new ADR-036 backend would require a parallel edit in `rtp-plugin/.../menu/surfaces/`. Surfaces consume the same providers `/rtp info` and the tab-complete supplier already use. |
| New transport (gRPC / plugin-messaging channel) for diag aggregation | Duplicates ADR-036's shared-store accessor; adds a second consistency model; rejected. |
| Push-based live diag stream across backends | Conflicts with ADR-035's no-live-update constraint and would require an out-of-game subscriber. |
| Per-backend file dump scraped centrally | Reintroduces filesystem-as-transport with no atomicity; rejected. |
| Have ADR-039 publish its own `DiagSnapshot` rows to the shared store | Duplicates data the metrics subsystem and ADR-036 already write; introduces a new schema, a new heartbeat, and a new shutdown-flush hazard for no new information. Rejected — this ADR is strictly a reader. |
| Persist biome tally / heatmap ring cross-backend so they aggregate too | Requires a new writer and new schema purely for diagnostics; out of scope for a read-only surface ADR. Revisit after `METRICS_PLAN.md` covers them natively. |

## Consequences

**Positive**

- Admins gain spatial answers (biome distribution, failure hotspots) without leaving the game, with **zero** new I/O hot paths.
- Wizards (ADR-038) become "look, decide, apply" instead of "apply, log-dive, repeat". The diagnostic surfaces close the perceive→act loop.
- The fail-rate ring is reusable: future ADRs (persistence, network aggregation, alerting) can read the same snapshot contract without re-instrumenting the pipeline.
- Read-only contract keeps the change blast radius small; the regression surface is rendering correctness, not state mutation.

**Negative / accepted trade-offs**

- Per-region fail-rate ring adds bounded heap (default ~4096 × 16 B per region ≈ 64 KB/region; configurable). Acceptable; tracked under `MemoryTracker`.
- Heatmap data is lost on restart. Acceptable for beta.4; persistence deferred.
- Book pages are a coarse rendering medium; ~14×19 glyphs cap heatmap resolution. Acceptable — the surface is for triage, not cartography.
- Surfaces that depend on unshipped `METRICS_PLAN.md` phases render `—`; a few rows will look empty until those phases land.

## Migration / Rollout

- **Phase 1 (beta.4):** Ship `biomes`, `heatmap`, `metrics`, `chart`, and `network` surfaces. Add `rtp.admin.diag.*` permissions including `rtp.admin.diag.chart` and `rtp.admin.diag.network`. Add `messages.yml → menu.surface.*` (REQ-RTP-F-013) including `menu.surface.network.{disabled,unsupported,unavailable,stale,no-report}` and `menu.surface.chart.{outside-retention,unknown-metric}`. New config keys: `metrics.failRateRing.{size,windowCap}` and `metrics.timeSeriesRing.tiers` (the multi-tier bucket spec for the per-metric chart ring, defaulting to `5m@1s,1h@10s,24h@1m`). No `diagnostics.network.publish` key — this ADR introduces no writer. Staleness is judged against the existing ADR-036 network-snapshot TTL; no new TTL knob is added. The biome surface introduces no new config keys.
- **Phase 2 (post-beta.4):** Persist fail-rate samples at raw resolution (schema TBD), add deferred surfaces (`claims`, `pipeline-trace`, `players`), open the surface interface to addons. Cross-backend per-metric time-series (history for `chart --backend <id|all>`) is owned by a separate writer ADR — see ADR-040; ADR-039 stays strictly the reader.
- **Phase 3 (post-network-mode-GA):** Live cross-backend deltas (push, not pull), gated on ADR-036 phase 2+.

Traceability rows to add when implementation lands (not in this ADR per CHANGELOG/TRACEABILITY hygiene):

- `REQ-RTP-DIAG-001` Read-only contract → `DiagnosticSurfaceReadOnlyTest`.
- `REQ-RTP-DIAG-002` No new I/O on diag invocation → `DiagInvocationNoChunkLoadTest` (S-005 regression).
- `REQ-RTP-DIAG-003` Token reuse + permission denial → `DiagTokenAndPermissionTest`.
- `REQ-RTP-DIAG-004` Fail-rate ring bounded + thread-safe → `FailRateRingBoundedConcurrentTest`.
- `REQ-RTP-DIAG-005` Graceful `—` rendering when metric phase absent → `DiagGracefulDegradationTest`.
- `REQ-RTP-DIAG-006` Cross-backend aggregation reads only via ADR-036 shared store; no new transport → `DiagCrossBackendSharedStoreTest`.
- `REQ-RTP-DIAG-007` Store-down collapse to origin-only with configurable notice → `DiagStoreDownDegradationTest`.
- `REQ-RTP-DIAG-008` Diagnostic surfaces perform zero writes to the shared store under any invocation → `DiagNoSharedStoreWriteTest`.
- `REQ-RTP-DIAG-009` Surface enumerations (metrics, regions, subcommands/parameters, fail modes, backend ids) are resolved at render time from the live source; no static catalogs → `DiagDynamicEnumerationTest`.
- `REQ-RTP-DIAG-010` Per-metric time-series ring is bounded across all windows via multi-tier in-place condensation; sampling is piggybacked on `MemoryTracker`'s existing periodic pass with no new scheduler → `MetricTimeSeriesRingBoundedCondensationTest`.
- `REQ-RTP-DIAG-011` Chart surface renders only metric keys currently exposed by the live samplers (contract 6) and reports `— (outside retention)` / `— (unknown metric)` with configurable messages otherwise → `DiagChartDynamicMetricTest`.

## Cross-references to existing rules

- **S-004** Diag invocations emit one structured audit line; no silent paths.
- **S-005** Surfaces shall not trigger main-thread chunk I/O. The biome surface reads only data already attached to retained region entries; the heatmap reads in-memory counters; the metrics surface reads existing samplers.
- **S-006** `/rtpadmin diag` before core load throws `IllegalStateException` via existing API entry guard.
- **S-007** All surface-side error messages (`expired`, `denied`, `unavailable`, `bad parameter`) are configurable.
- **D-005** This ADR is the proposal; implementation follows acceptance.
- **REQ-RTP-F-013** All surface strings live in `messages.yml`.
- **ADR-035** Surfaces are renderers over `MenuModel`; reuse the same token registry.
- **ADR-036** Cross-backend surfaces **read only** the peer rows ADR-036 (and the metrics subsystem per `METRICS_PLAN.md`) already write to the shared store; this ADR adds no writer, no new row shape, no new transport, and no new connection pool.
- **ADR-037** Audit stream and parse/tab-complete grammar are reused as-is.
- **ADR-038** One-way handoff: surface `[open wizard …]` button invokes a wizard; wizards never invoke surfaces.
- **`METRICS_PLAN.md`** Single source of truth for the `metrics` surface's contents and phase status.

## References

- ADR-035 — Interactive Menus via Written Book (Book-First, Chat Fallback)
- ADR-036 — Network Mode: Multi-Server, Multi-Proxy RTP
- ADR-037 — Harden RTP Config Commands
- ADR-038 — `/rtpadmin` Setup Wizards
- ADR-016 — Anvil Subsystem
- ADR-028 — L3 Backlog Cache
- [`docs/dev/METRICS_PLAN.md`](../dev/METRICS_PLAN.md)
- [`docs/dev/REQUIREMENTS.md section 3`](../dev/REQUIREMENTS.md) (S-00x prohibitions)
- commands-api-ADR-001 — Brigadier Bridge
