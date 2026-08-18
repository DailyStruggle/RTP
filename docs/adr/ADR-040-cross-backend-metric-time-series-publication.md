# ADR-040 — Cross-Backend Metric Time-Series Publication via `MemoryTracker` Tier Promotion

**Status:** Proposed
**Date:** 2026-05-13
**Target release:** `3.0.0-beta.5` (post-beta.4; gated on ADR-039 acceptance and `MemoryTracker`'s in-memory time-series ring landing)
**Supersedes:** —
**Superseded by:** —
**Related:** ADR-035 (Interactive Menus via Written Book), ADR-036 (Network Mode: Multi-Server, Multi-Proxy RTP), ADR-037 (Harden RTP Config Commands), ADR-038 (`/rtpadmin` Setup Wizards), ADR-039 (`/rtpadmin` Diagnostic Surfaces), [`docs/dev/METRICS_PLAN.md`](../dev/METRICS_PLAN.md), [`docs/dev/MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md)

---

## Context

ADR-039 introduced a per-metric **multi-tier in-memory time-series ring** (`5m@1s, 1h@10s, 24h@1m` by default), sampled and condensed in place by a repurposed `MemoryTracker`. It powers the `/rtpadmin diag chart <metric> [window]` surface — **on the originating backend only**. ADR-039 was deliberately scoped to read-only and explicitly rejected publishing the ring cross-backend, deferring that question.

That deferral leaves a real gap once ADR-036 network mode is enabled:

- `/rtpadmin diag chart <metric> --backend <id>` cannot plot a time series for another backend, because the ring is local to each node.
- `/rtpadmin diag metrics --backend <id>` shows only the **latest** value the ADR-036 heartbeat carries — no shape, no trend, no extrema.
- Operators investigating a cross-backend incident ("backend-3 was lagging at 14:02, what was the L1 fill curve there?") have to SSH to that node and read its logs.

ADR-036's network snapshot is a **point-in-time** row, refreshed on a heartbeat. It is the wrong shape for a chart: it carries the current value, not the history. Asking the heartbeat to widen its payload to include 24 h of bucketed samples is the wrong layer — it would couple snapshot freshness to time-series volume and bloat every heartbeat write whether anyone is looking at a chart or not.

What is needed is a **separate, narrow, write-only path** — driven by the same `MemoryTracker` periodic pass that already condenses the local ring — that publishes **already-condensed tier-promotion outputs** to the shared store as bounded batch rows. Each row is small, idempotent, and reader-friendly: cross-backend chart reads become a single time-range SQL/Redis query against a table whose write rate is bounded by the metrics catalogue size, not the tick rate.

ADR-039 stays read-only and zero-writes. ADR-040 owns the writer.

## Decision

Adopt a **batch-mode, tier-promotion-driven, hybrid-payload publisher** of metric time-series buckets to ADR-036's existing shared store. The publisher is driven by `MemoryTracker`'s existing periodic pass, runs only when ADR-036 network mode is enabled, and writes only what the local ring has **already condensed** into stable bucket rows. ADR-039's `NetworkSnapshotReader` is extended (read-side only) to consume these rows for `/rtpadmin diag chart --backend <id|all>` and `/rtpadmin diag metrics --backend <id|all>` history pages.

### The contract

1. **Batch, not per-sample.** The publisher emits one row per `(backend_id, metric_key, window_start_epoch)` triple. The cadence is the **slowest published tier** — by default the 1-minute tier ("`1h@10s` and `24h@1m` tiers exist locally; only the `24h@1m` tier is published"). Per-tick / per-sample writes are forbidden.
2. **Hybrid payload (mean and worst-case in one row).** Every published bucket carries `(count, mean, min, max)` and optionally `p95` if the local ring tracks it. Single-statistic payloads (mean-only or worst-case-only) are forbidden because they preclude either chart rendering or incident postmortem; the row write dominates cost, the extra columns do not.
3. **Tier-promotion drives publication, not a new scheduler.** A row is queued for write the moment the local ring promotes a tier-0 → tier-1 boundary (and/or tier-1 → tier-2, per config) — i.e., when the bucket the row represents becomes immutable. No new thread, no new scheduler, no new heartbeat. `MemoryTracker`'s existing pass flushes the queue once per pass under a hard per-pass write cap.
4. **Idempotent writes.** The natural primary key `(backend_id, metric_key, window_start_epoch)` makes re-publication after restart a no-op (`INSERT … ON CONFLICT DO NOTHING` on Postgres/MySQL; `SET … NX` with TTL on Redis). Backends never compete for each other's rows; CAS contention is structurally impossible.
5. **Bounded write rate.** Maximum write rate per backend is `metric_count × published_tier_buckets_per_minute`. With the beta.4 default tier set, that is `N_metrics × 1 /min` per backend — well under any sane shared-store budget even at 50 metrics × 20 backends (~1000 writes/min cluster-wide). A safety throttle in the publisher caps the per-pass batch (default 64 rows) and defers overflow to the next pass; sustained overflow is an audit warning, never a drop without a log line (S-004).
6. **Bounded retention.** A periodic `DELETE WHERE window_start_epoch < now - retention` runs on the same `MemoryTracker` pass as part of the same flush. Default retention is 7 days, configurable. Retention is local-effect (each backend reaps its own rows by `backend_id`), so reaping work scales with the originating node's metric count, not cluster size.
7. **Reader is ADR-039.** No new consumer command, no new menu surface. ADR-039's `NetworkSnapshotReader` gains an additional method to range-scan these rows; `MetricChartSnapshot` gains an optional `(min, max)` series the chart renderer draws as a high-water band behind the mean line. Origin-only chart behavior is unchanged when network mode is off or the publisher is disabled.
8. **Origin-only opt-out.** A single config key (`metrics.networkTimeSeries.publish`, default `false`) gates the entire writer. With it off, ADR-040 is dormant; ADR-039 keeps working exactly as in beta.4. With it on and network mode off, the writer logs one structured warning and stays dormant — it does not invent a transport.

### What ties this to existing ADRs

- **ADR-035** — no UI in this ADR; the chart-with-cross-backend-history is rendered by the existing `MetricChartRenderer` against the existing `MenuModel`. Token model unchanged.
- **ADR-036** — the publisher reuses the same `AbstractSQLDatabaseAccessor` / Redis driver, the same connection pool, the same `backend_id` / `generation` semantics, and the same store-down "warn and degrade" policy ADR-036 already defines for the heartbeat. **No** new transport, **no** new connection pool, **no** new heartbeat, **no** new scheduler.
- **ADR-037** — `metrics.networkTimeSeries.*` config keys are governed by the ADR-037 hardened config command surface (`/rtp set metrics.networkTimeSeries.publish true` runs through the normal validation + audit + transaction path).
- **ADR-038** — wizard `performance.tune` and a new `network.tune` flow may toggle the publisher. ADR-040 itself adds no wizard.
- **ADR-039** — strict read/write split: ADR-039 reads, ADR-040 writes. The chart surface composes both at render time. ADR-039's "zero writes" contract (REQ-RTP-DIAG-008) remains satisfied — ADR-040's writer is owned by `rtp-core/.../metrics/` (where the metrics subsystem lives), not by `rtp-core/.../diagnostics/`.

## Module placement (Architecture Boundaries)

| Concern | Module | Notes |
|---|---|---|
| `MetricTimeSeriesPublisher` interface, `MetricBucketRow` (POJO row shape) | `rtp-api` | Stable for addons that want to read the table directly; deliberately not for them to register their own publisher in beta.5. |
| Tier-promotion → write-queue plumbing, batched flush, retention sweep | `rtp-core` | New `metrics/networkTimeSeries/` subpackage. Reads the existing `MetricTimeSeriesRing` (ADR-039) on promotion events; writes via the existing ADR-036 accessor. |
| Schema + SQL DDL (Postgres / MySQL / H2 / SQLite) | `rtp-core` | Single table `rtp_metric_bucket` with composite PK `(backend_id, metric_key, window_start_epoch)`; created on first publisher-enabled startup, idempotent. |
| Redis driver mapping | `rtp-core` | Sorted-set per `(backend_id, metric_key)` keyed by `window_start_epoch`, member = packed payload; TTL on each set = retention. |
| `NetworkSnapshotReader` range-scan extension (read-only) | `rtp-core` (ADR-039 package) | One new method; consumed only by `ChartSnapshotAssembler` and `MetricsSnapshotAssembler`. No menu code change required. |
| Tab-complete / config-validation hooks for `metrics.networkTimeSeries.*` | `rtp-core` (ADR-037 grammar) | Reuse the shared grammar; no surface-layer change. |

No change to `rtp-api`'s diagnostic snapshot interfaces from ADR-039. The writer is below the snapshot layer.

## Row shape (`rtp_metric_bucket`)

```
backend_id          STRING   NOT NULL  -- ADR-036 server id
metric_key          STRING   NOT NULL  -- enumerated from the live samplers (no static catalog)
window_start_epoch  LONG     NOT NULL  -- seconds, UTC, aligned to tier boundary
window_seconds      INT      NOT NULL  -- the bucket width (e.g. 60 for the 24h@1m tier)
sample_count        INT      NOT NULL
mean                DOUBLE   NOT NULL
min                 DOUBLE   NOT NULL
max                 DOUBLE   NOT NULL
p95                 DOUBLE   NULLABLE  -- present only if the local ring tracks it
generation          LONG     NOT NULL  -- reused from ADR-036 heartbeat for staleness checks
PRIMARY KEY (backend_id, metric_key, window_start_epoch)
INDEX       (metric_key, window_start_epoch)  -- supports --backend all range scans
```

Redis representation: `ZADD rtp:metric:<backend_id>:<metric_key> <window_start_epoch> <packed>` with `EXPIRE` set to retention; range scans use `ZRANGEBYSCORE`.

The row carries **no metric-specific schema** — every metric writes the same shape. New metrics added in `METRICS_PLAN.md` are automatically publishable without a schema change (contract 6 of ADR-039 carries through: dynamic enumeration end-to-end).

## What this ADR is **not**

- **Not** a streaming protocol. There is no subscription, no push, no notification fan-out. Readers poll on chart render.
- **Not** a replacement for `METRICS_PLAN.md`. Metrics definitions, units, and sampling cadences continue to live there. This ADR specifies only the cross-backend persistence shape.
- **Not** a writer for the heatmap or biome surfaces. Those remain in-memory and origin-only (ADR-039 contracts unchanged).
- **Not** an addon extension point. Third parties may read `rtp_metric_bucket` rows but cannot register their own publisher in beta.5.
- **Not** an arbitrary-resolution archive. The published tier is **one** tier, chosen by config. Raw 1-second samples are never published, by design.
- **Not** a backfill mechanism. On startup, the publisher resumes from the local ring's current state; pre-restart buckets that were never published are lost (the local ring is in-memory). Acceptable; the cluster keeps running, and gaps render as `— (no report)` on the reader side.
- **Not** a transport for `network` / `biome` / `heatmap` diagnostic snapshots. Those stay where ADR-039 put them.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Per-sample publication (one row per `MemoryTracker` tick per metric) | Floods the shared store: 50 metrics × 20 backends × 1 Hz ≈ 1000 writes/sec cluster-wide. No reader needs this fidelity; the local ring already condenses it away. |
| Worst-case-only payload (publish `max` only) | Cannot draw a sparkline — a max-only chart is a flat ceiling during steady state and visually misleading during sporadic load. Cannot be re-aggregated to other windows. |
| Mean-only payload | Hides incidents (the operator use case that justified the writer in the first place). Cannot answer "how bad did it get" from the row alone. |
| Publish every tier (`5m@1s`, `1h@10s`, `24h@1m`) | Triples write rate for no reader benefit; the reader downsamples on the way out anyway. Pick the coarsest tier the network reader needs. |
| Widen ADR-036's heartbeat row to carry 24 h of bucketed samples | Couples snapshot freshness to time-series volume; every heartbeat would be paying the time-series tax whether anyone is charting or not. Wrong layer. |
| Push-based notification (write triggers reader update) | Conflicts with ADR-035's no-live-update constraint and would require an out-of-game subscriber. The reader queries on book-page render, same as the rest of ADR-039. |
| Publish from a new dedicated scheduler / thread | `MemoryTracker` already runs a periodic pass and already owns the tier-promotion event. A second scheduler duplicates that surface and complicates Folia region-scheduler ownership. |
| New transport (gRPC, plugin-messaging channel, Kafka) for time-series only | Duplicates ADR-036's shared-store accessor; adds a second consistency model and a second outage mode. Rejected on the same grounds as ADR-039's diagnostic-transport rejections. |
| Persist locally to disk first, then ship | Reintroduces filesystem-as-transport with no atomicity, and a shutdown-flush hazard ADR-036 already avoids. The shared store **is** the durable copy. |
| Cross-backend write of biome tally / heatmap ring | Reaffirmed rejection from ADR-039. Out of scope; revisit only if `METRICS_PLAN.md` covers those samples natively. |
| Per-metric custom schemas | Every new metric would need a migration. The uniform `MetricBucketRow` shape (`count, mean, min, max, optional p95`) is sufficient for every numeric metric the codebase currently samples; non-numeric metrics (e.g. enum counters) are out of scope for charting. |
| Unbounded retention | Table growth scales with `metric_count × backends × time`; unbounded is a latent operational hazard for shared databases. Retention default 7 days with a configurable knob covers the realistic incident-postmortem horizon. |
| Have ADR-039 own this writer | Violates ADR-039's "zero writes" contract (REQ-RTP-DIAG-008). The writer belongs in the metrics subsystem, not the diagnostic surface layer. |

## Consequences

**Positive**

- Cross-backend `chart` and historical `metrics --backend` queries return real data shape (trend + extrema), not just the latest heartbeat snapshot.
- The hybrid `(count, mean, min, max)` payload supports both routine charting and incident postmortem from the same row — operators do not need a second tool.
- Write cost is bounded analytically (`N_metrics / minute / backend`) and tested at the publisher batch cap.
- No new transport, no new scheduler, no new heartbeat, no new connection pool. The change is structurally narrow.
- Idempotent inserts make restart-and-resume safe; readers tolerate gaps natively (`— (no report)`).
- Retention sweep is local-effect: a clean shutdown of one backend does not abandon another backend's rows.

**Negative / accepted trade-offs**

- Cross-backend chart data lags by up to one published-tier bucket width (default 1 minute). Acceptable; ADR-035 already commits to no-live-update.
- Pre-restart buckets that hadn't yet promoted to the published tier are lost. Acceptable; the local ring is in-memory by ADR-039 design.
- Adds a database table (or Redis key family). Shared-store schema surface grows by one entity. Mitigated by uniform row shape (no per-metric tables).
- Retention default of 7 days assumes operators investigating incidents within a week. Configurable; documented.
- When the shared store is unreachable, the publisher buffers up to its per-pass cap and then drops with a structured S-004 warning (the local ring keeps working unchanged; only cross-backend visibility lapses).

## Migration / Rollout

- **Phase 1 (beta.5):** Ship the writer behind `metrics.networkTimeSeries.publish` (default `false`). Add `metrics.networkTimeSeries.{tier,retentionDays,perPassWriteCap}` config keys (defaults: tier = `24h@1m`, retention = 7 days, cap = 64 rows/pass). Schema bootstrap on first enabled startup. ADR-039's `NetworkSnapshotReader` extended to range-scan `rtp_metric_bucket`; `MetricChartRenderer` draws the `(min, max)` band when present. Document the new table in `MULTI_SERVER_PLAN.md` and `METRICS_PLAN.md section Phased Roadmap`.
- **Phase 2 (post-beta.5):** Optional per-metric publish allowlist (cap shared-store cost when only a subset of metrics is operationally interesting). Optional second published tier (`1h@10s`) for high-resolution chart pages, gated on shared-store budget.
- **Phase 3 (post-network-mode-GA):** Evaluate live push (event-bus or similar) for sub-minute cross-backend visibility; gated on a separate ADR and on ADR-035 relaxing its no-live-update stance.

Traceability rows to add when implementation lands (not in this ADR per CHANGELOG/TRACEABILITY hygiene):

- `REQ-RTP-METTS-001` Publisher writes exactly one row per `(backend_id, metric_key, window_start_epoch)` and re-publication is a no-op → `MetricBucketIdempotentInsertTest`.
- `REQ-RTP-METTS-002` Publisher writes only from the configured published tier on promotion; per-sample and per-tick writes never occur → `MetricBucketTierPromotionOnlyTest`.
- `REQ-RTP-METTS-003` Row payload always carries `(count, mean, min, max)`; mean-only / max-only payloads are rejected at the publisher boundary → `MetricBucketHybridPayloadTest`.
- `REQ-RTP-METTS-004` Per-pass batch cap is honored; overflow defers without silent drop and emits a structured warning (S-004) → `MetricBucketBatchCapAndOverflowTest`.
- `REQ-RTP-METTS-005` Retention sweep deletes rows older than `metrics.networkTimeSeries.retentionDays` for the local `backend_id` only → `MetricBucketRetentionSweepTest`.
- `REQ-RTP-METTS-006` Reader range-scan returns rows in `window_start_epoch` order and is consumed by `ChartSnapshotAssembler` without copy on the main/region thread → `MetricBucketReaderRangeScanTest` (S-005 regression).
- `REQ-RTP-METTS-007` Writer is dormant when `metrics.networkTimeSeries.publish=false` or when ADR-036 network mode is disabled (one structured warning at startup in the mismatch case, zero writes otherwise) → `MetricBucketWriterDormantTest`.
- `REQ-RTP-METTS-008` Schema bootstrap is idempotent across Postgres / MySQL / H2 / SQLite drivers and the Redis layout matches the documented sorted-set shape → `MetricBucketSchemaBootstrapTest`.

## Cross-references to existing rules

- **S-004** Publisher overflow, store-down, and dormant-with-network-mode-on mismatches all emit one structured `RTP.log` line; no silent drops.
- **S-005** Reader range-scan and writer flush both run off the main/region thread, on the same `MemoryTracker` pass that already does so for the local ring.
- **S-006** API entry points throw `IllegalStateException` when called before core load.
- **S-007** All operator-visible error states (`store-unavailable`, `publisher-disabled`, `network-mode-off`) are configurable via `messages.yml` keys layered on the existing `menu.surface.network.*` set; no new top-level message section is required.
- **D-005** This ADR is the proposal; implementation follows acceptance.
- **REQ-RTP-F-013** All operator-visible strings live in `messages.yml`.
- **ADR-035** Reader changes are confined to existing `MenuModel`/`MetricChartRenderer`; no UI ADR is required.
- **ADR-036** Reuse the shared-store accessor, connection pool, `backend_id`, `generation`, and store-down policy; add no new transport.
- **ADR-037** Config keys go through the hardened config command surface; no bespoke parser.
- **ADR-039** Strict read/write split: ADR-039 is the reader, ADR-040 is the writer. The chart surface composes both.
- **`METRICS_PLAN.md`** Single source of truth for the metric catalogue; this ADR adds no metric, only a persistence shape for the existing ones.

## References

- ADR-035 — Interactive Menus via Written Book (Book-First, Chat Fallback)
- ADR-036 — Network Mode: Multi-Server, Multi-Proxy RTP
- ADR-037 — Harden RTP Config Commands
- ADR-038 — `/rtpadmin` Setup Wizards
- ADR-039 — `/rtpadmin` Diagnostic Surfaces
- [`docs/dev/METRICS_PLAN.md`](../dev/METRICS_PLAN.md)
- [`docs/dev/MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md)
- [`docs/dev/REQUIREMENTS.md section 3`](../dev/REQUIREMENTS.md) (S-00x prohibitions)
