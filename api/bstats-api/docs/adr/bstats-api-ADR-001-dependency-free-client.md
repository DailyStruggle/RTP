# bstats-api-ADR-001: Dependency-free bStats client module

- **Status:** Accepted (2026-10-05).
- **Supersedes:** none.
- **Superseded by:** none.
- **Related:**
  - [metrics-api-ADR-001](../../../metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md): sibling cross-plugin `*-api` precedent (neutral package root, ArchUnit guard).
  - [ADR-024](../../../../docs/adr/ADR-024-rtp-lite-assembly-variant.md): separate bStats service id for the lite assembly.
  - [`docs/dev/METRICS_PLAN.md`](../../../../docs/dev/METRICS_PLAN.md) *bStats Integration*: chart catalogue and privacy rules.
  - Rule F-001 in [`docs/dev/RULES.md`](../../../../docs/dev/RULES.md): no raw threads in backend code.

---

## Context

RTP shaded `org.bstats:bstats-bukkit`. The library spins up its own
`ScheduledThreadPoolExecutor` (a Rule F-001 violation inside our jar), requires a
relocation, and is one more third-party artifact the "no dependency vulnerabilities"
claim has to cover. Fabric and NeoForge used a separate home-grown sender that posted to
the wrong endpoint, so their data never arrived.

The bStats v2 wire protocol is small: one gzipped JSON POST every 30 minutes with a
fixed shape, plus a shared on-disk opt-out file. Sibling server plugins in this monorepo
want the same client without depending on `rtp-core`.

## Decision

### 1. New module

A pure-Java `api/bstats-api` subproject (Gradle path `:bstats-api`, `java-library`), JDK
dependencies only. Package root `io.github.dailystruggle.bstats.api.*`, neutral like
`metrics-api`. `BStatsApiArchTest` fails the build on any non-`java.*` dependency.

### 2. Surface

- `BStatsService` (+ `Builder`): payload, cadence, opt-out, send. Wire contract mirrors
  upstream `MetricsBase` 3.x: platform fields at the root,
  `service{pluginVersion,id,customCharts}`, `serverUUID`, `metricsVersion`; gzip POST to
  `/api/v2/data/<platform>`; first send after a random 3-6 min, then every 30 min from a
  random 0-30 min offset. bStats bans services that drop the opt-out or alter cadence, so
  neither is configurable.
- `CustomChart` + `SimplePie`, `AdvancedPie`, `DrilldownPie`, `SingleLineChart`,
  `MultiLineChart`: same names and constructors as `org.bstats.charts`, so migration is an
  import change. A throwing / `null` / empty supplier drops only that chart.
- `BStatsConfig`: reads the shared `bStats/config.yml` (`key: value`) or `config.txt`
  (`key=value`, both `serverUuid` and upstream `server-uuid` spellings). An existing file
  is never rewritten; a new one is written in the platform's upstream format and key names.
  The opt-out is re-checked before every send.
- `BStatsPlatform`: the only place platform wire names live. `BUKKIT` maps a neutral
  `ServerInfo` (players, online mode, name, version) onto the endpoint's root fields,
  mirrored from upstream `appendPlatformData`; unknown (`null`) facts are omitted, never
  guessed. Proxy endpoints (BungeeCord, Velocity) are deliberately not modelled: a proxy
  has no server metrics to report, and the enum can grow if that changes.
- Host seams: `BStatsScheduler` (main thread / async / delayed / async timer / cancel, in
  ticks) and `BStatsLog`. The module never creates threads.

### 3. RTP wiring

`rtp-core` keeps only RTP-specific code in `common.metrics.bstats`: `RtpBStatsCatalogue`
(the chart list), `BStatsChartIds`, and `RtpBStats`, the single entry point every backend
calls with a `RtpBStatsCatalogue.Host`. `RtpBStats` adapts `RTP.scheduler` / `RTP.log` and
always reports to the `bukkit` endpoint (ids 30865 full, 12277 lite): bStats has no modded
endpoint, and one service keeps the whole install base on one dashboard. The `platform`
chart and the software name keep loaders separable. Bukkit supplies `BukkitBStatsHost`
(main-thread collection, YAML config, add-on whitelist); Fabric and NeoForge use
`Host.of(loader)`.

## Alternatives Considered

| Alternative | Why rejected |
|-------------|--------------|
| Keep shading `bstats-bukkit` | Raw executor thread (F-001), relocation upkeep, extra artifact to vouch for; no modded support. |
| Client inside `metrics-api` | `metrics-api` is a snapshot SPI with no I/O; an HTTP client there breaks its interfaces-only scope and forces bStats on every metrics consumer. |
| Client inside `rtp-core` | Sibling plugins would drag the whole core in to send bStats data. |
| Separate bStats services for Fabric / NeoForge | Splits the install base across dashboards; the single service with a `platform` chart was chosen instead. |

## Consequences

- **Positive:**
  - No third-party bStats artifact ships; no relocation; no hidden thread pool.
  - Any monorepo server plugin or mod can report with one `compileOnly` / shaded
    dependency and its own scheduler.
  - Platform wire vocabulary is isolated in one enum with golden tests.
- **Negative / trade-offs:**
  - We own wire compatibility; upstream protocol changes must be mirrored by hand
    (`METRICS_VERSION` records the upstream version reproduced).
  - Acceptance by bStats.org is only observable on the live dashboards.

## References

- [`docs/dev/METRICS_PLAN.md`](../../../../docs/dev/METRICS_PLAN.md)
- [`docs/dev/TRACEABILITY.md`](../../../../docs/dev/TRACEABILITY.md)
