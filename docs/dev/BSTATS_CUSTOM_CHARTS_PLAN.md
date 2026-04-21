# Plan: bStats Custom Charts for RTP

**Status:** Proposal — no code changes yet.
**Owner:** _unassigned_
**Related:** `docs/dev/ROADMAP.md` §1.E (unsourced statistics caveat), `docs/FRONT_PAGE.bbcode` ("~45% Overworld safe" claim), REQ-RTP-F-013 (no telemetry shall expose user-identifying data — see §5 below).

---

## 1. Problem Statement

RTP already registers with bStats on every Bukkit-family platform:

    metrics = new Metrics(this, 30865);   // RTPBukkitPlugin.onEnable

The `org.bstats` package is relocated to `io.github.dailystruggle.rtp.bstats`
so it will not collide with a host plugin that bundles its own copy. Default
charts are therefore already published: server platform, MC version, Java
version, core count, plugin version, OS family, player count buckets.

**What is missing is every RTP-specific signal.** The front page and
`ROADMAP.md` both make quantitative claims ("~45% Overworld safe",
"bounded fallback rate", "queue saturation is rare") that today rest on
author-run profiling. Custom charts turn those into a continuously updated,
community-sourced distribution — both as release-copy evidence and as the
fastest way to learn which features operators actually use.

A secondary problem: we have no telemetry for rare failure modes
(Anvil probe `UNKNOWN` fallbacks, Folia region-hops, MemoryTracker
reclaims). Without bStats aggregates, these only surface in manual
`/rtp test full` runs on servers whose operators volunteer logs.

## 2. Goals

1. **Replace every unsourced statistic on the front page with a measured one**
   within one release cycle after custom charts ship.
2. **Make caveat rates observable across the whole deployed fleet**, not just
   on the author's test boxes — specifically Spigot on-tick fallback rate,
   Folia region-hop rate, and un-populated fallthrough attribution.
3. **Produce adoption signal** (platform split, claim-plugin mix, shape
   distribution) that lets us rank future compatibility work by real install
   share rather than GitHub star count.
4. **Ship zero personally-identifying data.** See §5. If a chart cannot be
   expressed without world names, player names, seeds, IPs, or file paths,
   it does not ship.

## 3. Non-Goals

- Real-time dashboards. bStats aggregates on a 30-minute cadence; anything
  needing sub-minute resolution belongs in `/rtp test full` telemetry or a
  future Prometheus exporter, not here.
- Per-player analytics. RTP is player-agnostic at the telemetry layer and
  must stay that way.
- Replacing `TRACEABILITY.md` or `COVERAGE_PLAN.md`. bStats is a population
  survey, not a correctness oracle.

## 4. Chart Catalog

Three tiers by priority. Tier A ships with the first custom-chart PR,
Tier B ships when the underlying instrumentation is ready, Tier C is
opportunistic.

bStats chart types referenced below:

- **SimplePie** — one categorical value per server, rendered as a pie slice.
- **AdvancedPie** — a `Map<String,Integer>` per server, aggregated across the
  fleet.
- **DrilldownPie** — two-level pie (outer category → inner breakdown).
- **SingleLineChart** — one integer per server, aggregated into a daily
  line chart.
- **MultiLineChart** — a `Map<String,Integer>` rendered as stacked lines.

### 4.A Tier A — Platform and configuration shape (ship first)

Covers "who is running RTP, on what, configured how". Zero runtime
instrumentation required; every value is readable from config + platform
detection on chart callback.

| Chart ID | Type | Value | Why it ships first |
|---|---|---|---|
| `platform_flavor` | SimplePie | `spigot` / `paper` / `folia` / `purpur` / `other` | bStats's default `bukkit.name` collapses Paper and Folia; this splits them. Directly answers "do I need to keep the Spigot fallback path?" |
| `fabric_present` | SimplePie | `yes` / `no` / `bukkit_only` | Surfaces Fabric-preview uptake once §1.D of `ROADMAP.md` lands. Until then this reports `bukkit_only` for every server, which is itself a baseline. |
| `region_count_bucket` | SimplePie | `1` / `2-5` / `6-10` / `11-25` / `26+` | Single-region vs. many-region installs behave very differently; today we guess the split. |
| `shape_mix` | AdvancedPie | `{ "circle": N, "square": N, "custom": N }` counted across configured regions | Justifies (or retires) the per-shape engineering investment in `Python Test Scripts/`. |
| `dimension_mix` | AdvancedPie | `{ "overworld": N, "nether": N, "end": N, "custom": N }` | Answers "does anyone actually RTP in the End?" |
| `custom_generator_mix` | AdvancedPie | `{ "vanilla": N, "iris": N, "terra": N, "other": N }` detected per configured world | Feeds the Iris/Terra marketing claim with real numbers; also flags compatibility regressions if `other` spikes after a Mojang update. |
| `queue_kind_mix` | AdvancedPie | `{ "per_player": N, "global": N }` across configured regions | Validates the ADR-007 per-user-isolation emphasis. |
| `claim_integrations_active` | AdvancedPie | `{ "WorldGuard": 0/1, "Towny": 0/1, … }` for each of the seven supported plugins | Ranks future upgrade work (§2 of `ROADMAP.md` Tier 2) by real install share. |
| `economy_enabled` | SimplePie | `vault_and_cost` / `vault_no_cost` / `no_vault` | |
| `papi_present` | SimplePie | `yes` / `no` | |
| `effects_in_use` | AdvancedPie | `{ "blindness": N, "particles": N, "sound": N, ... }` drawn from EffectsAPI registration | |
| `safety_list_features` | AdvancedPie | `{ "tags_used": 0/1, "state_predicates_used": 0/1, "exclusions_used": 0/1 }` | Shows whether the ADR-017 grammar investment is being used in anger. |

### 4.B Tier B — Runtime distributions and caveat telemetry

Requires lightweight counters in `rtp-core`. All counters reset on each
bStats push (30-minute window) and are never persisted between restarts.

| Chart ID | Type | Value | Narrows which caveat |
|---|---|---|---|
| `teleports_per_interval` | SingleLineChart | teleports completed since last push | Headline adoption signal; complements player-count. |
| `teleport_outcome_mix` | AdvancedPie | `{ "success": N, "failed_safety": N, "failed_claim": N, "failed_cancelled": N, "failed_other": N }` | Quantifies REQ-RTP-S-004 compliance in the wild (no silent failures means every bucket except `success` is observable here). |
| `queue_saturation_events` | SingleLineChart | count of "player had to wait for generation" | Tests the Pillar #1 claim on real traffic. |
| `queue_depth_avg_bucket` | SimplePie | `0` / `1-5` / `6-20` / `21+` | Average queue depth at push time, bucketed for privacy. |
| `anvil_probe_outcome` | AdvancedPie | `{ "safe": N, "unsafe": N, "unknown": N }` | Directly sizes §1.A / §1.C caveats. |
| `spigot_fallback_rate` | SingleLineChart | on-tick `getChunkAt` fallbacks per interval, Spigot-only | Measures §1.A caveat fleet-wide. |
| `folia_region_hops` | SingleLineChart | region-scheduler hops per interval, Folia-only | Measures §1.B caveat fleet-wide. Complements the proposed `FoliaRegionHopTimingTest`. |
| `memory_tracker_reclaims` | SingleLineChart | `WeakReference` sweep reclamations per interval | Validates the Pillar #3 claim; a flat-zero line is the good outcome. |
| `safe_fraction_overworld` | SimplePie | `<20%` / `20-40%` / `40-60%` / `60-80%` / `>80%` bucket of observed safe samples | Turns "~45% Overworld safe" into a reproducible distribution. |
| `safe_fraction_nether` | SimplePie | same buckets | Replaces the qualitative "dominated by lava seas" line. |
| `safe_fraction_end` | SimplePie | same buckets | Replaces the qualitative "almost entirely void" line. |

### 4.C Tier C — Opportunistic / diagnostic

Ship only if cheap; remove at the next release if they produce no signal.

- `db_backend` — SimplePie `h2` / `sqlite` / `memory_only` — confirms
  persistence uptake (Pillar #1 promise).
- `async_chunk_api_path` — SimplePie `paper_async` / `folia_entity_scheduler`
  / `spigot_prefilter_only` — clarifies which code path actually runs in the
  wild per platform flavour.
- `scan_lifecycle_usage` — SimplePie `never` / `used_once` / `active` —
  operator uptake of the `REQ-RTP-F-012` admin scan.
- `startup_warning_count_bucket` — SimplePie `0` / `1-3` / `4+` — any
  non-zero skew here is a docs-or-defaults bug.

## 5. Privacy Rules (hard constraints)

These bound every chart above and any future addition.

1. **No world names.** Worlds are bucketed by dimension type or custom-ness.
   `world-survival-2024` never leaves the server.
2. **No player names, UUIDs, IPs, or player counts beyond bStats defaults.**
3. **No seeds.** Detecting Iris/Terra is done via generator class / plugin
   presence, never by sampling world data.
4. **No file paths, no config diffs, no `messages.yml` content.**
5. **All counters reset per push window.** No cumulative lifetime totals
   (they would grow with uptime, which leaks server age).
6. **Buckets, not raw values,** for anything that could fingerprint a
   server (queue depth, region count, safe fraction). Exact integers are
   reserved for genuinely fleet-aggregate quantities (platform mix, outcome
   histogram).
7. **Every chart must be disable-able** via `config.yml`
   `metrics.customCharts: false` (default `true`). bStats's top-level opt-out
   is already respected; this is an RTP-level override for operators who
   want defaults only.

## 6. Implementation Sketch

No code in this plan — only the shape.

- A new class `RTPMetricsCharts` in `rtp-plugin/.../bukkit/` owns every
  `addCustomChart(...)` call. `RTPBukkitPlugin.onEnable` invokes it once
  after `new Metrics(this, 30865)`.
- Counters for Tier B live in `rtp-core` (platform-neutral) and are polled
  by the chart callbacks — no pushing from the runtime into bStats, no
  synchronous work on the bStats thread. Each callback reads `AtomicLong`s
  and resets them.
- Platform-specific counters (Spigot fallback, Folia region-hop) live in
  their respective adapters and expose an `RTPServerAccessor`-level read
  method so `rtp-core` stays platform-free.
- The disable switch reads a new `PerformanceKeys.customCharts` enum value;
  if `false`, `RTPMetricsCharts.register()` returns early.
- Tests: a new `ReqRtpF013CustomChartPrivacyTest` (added to
  `TRACEABILITY.md`) asserts no chart value contains a world name, UUID,
  or path. Snapshot-tests the callback output against a frozen allow-list
  of string values per chart.

## 7. Rollout

1. Land Tier A in one PR — config-only callbacks, no new counters.
2. After one bStats 30-minute cycle, verify data appears at
   `https://bstats.org/plugin/bukkit/RTP/30865` and privacy snapshot
   test passes.
3. Land Tier B counters incrementally, one caveat at a time, each
   accompanied by the matching `ROADMAP.md` §1.x bullet flipping to
   "measurable" (but not yet struck through — measurement is not
   mitigation).
4. After two full release cycles of data, revisit the Tier C list and
   either promote the survivors or delete them. Charts that produce a
   flat distribution or a single-dominant slice in 99% of servers are
   not earning their slot.
5. Once Tier B safe-fraction charts have ≥ 50 reporting servers, replace
   the remaining qualitative Nether/End phrasing on `FRONT_PAGE.bbcode`
   with the aggregate bucket and strike through `ROADMAP.md` §1.E last
   bullet.

## 8. Open Questions

- **Do we want per-MC-version drilldown on the caveat charts?** A
  `DrilldownPie` of `anvil_probe_outcome` by MC version would reveal
  whether a specific Mojang data-version change silently regressed the
  probe. Costs one extra chart slot; probably worth it.
- **Claim-integration chart granularity.** Reporting "integration
  present" vs. "integration configured with at least one region" — the
  latter is more honest but requires walking region config. Start with
  presence; promote if the signal looks noisy.
- **Fabric.** Custom charts are Bukkit-family-only in this plan. When
  `rtp-fabric` ships, `FabricRTPMod` will need its own `Metrics` handle
  and an equivalent chart module; either a shared `rtp-metrics` module
  or a duplicated pair of registrars, decided at that time.
