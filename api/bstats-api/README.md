# bstats-api

Dependency-free [bStats](https://bStats.org) v2 client for the RTP monorepo. Pure Java
(JDK only, no Bukkit, Fabric, Loom or RTP dependency), so any sibling server plugin or
mod can report to bStats without shading the upstream library.

The package root is neutral (`io.github.dailystruggle.bstats.api`), mirroring
`metrics-api`.

## Surface

- `BStatsService` &mdash; builds the payload, schedules submissions and posts them.
  Payload, endpoint (`/api/v2/data/<platform>`), cadence (first send after 3-6 min, then
  every 30 min) and opt-out match the upstream library; neither cadence nor opt-out is
  configurable, because bStats bans services that change them.
- `SimplePie`, `AdvancedPie`, `DrilldownPie`, `SingleLineChart`, `MultiLineChart` &mdash;
  same constructors as `org.bstats.charts`. A throwing or empty supplier drops only that
  chart from that submission.
- `BStatsPlatform` &mdash; `BUKKIT`: endpoint, default config format, and mapping of a
  neutral `ServerInfo` onto the root fields. Modded hosts report through it too (bStats
  has no modded endpoint). Proxies are out of scope: they have no server metrics to report.
- `BStatsConfig` &mdash; reads the shared `bStats/config.yml` / `config.txt` beside the
  plugin folder; never rewrites an existing file, so the operator's opt-out and server
  UUID are preserved.
- `BStatsScheduler`, `BStatsLog` &mdash; host seams. The client never creates threads.

## Usage

```java
BStatsService bstats = BStatsService.builder(BStatsPlatform.BUKKIT, MY_SERVICE_ID)
        .pluginDirectory(plugin.getDataFolder())
        .pluginVersion(() -> VERSION)
        .serverInfo(() -> new ServerInfo(Bukkit.getOnlinePlayers().size(), Bukkit.getOnlineMode(),
                Bukkit.getName(), Bukkit.getVersion()))
        .collectOnMainThread(true)    // serverInfo touches the Bukkit API
        .scheduler(myScheduler)       // adapt the platform scheduler
        .log(BStatsLog.of(logger))
        .build();
bstats.addCustomChart(new SimplePie("mode", () -> mode));
bstats.start();                       // false when the operator opted out
// on disable
bstats.shutdown();
```

Set `collectOnMainThread(true)` when chart suppliers or `serverInfo` touch a
main-thread-only server API; the send always runs async.

RTP itself does not call the builder directly: backends go through
`rtp-core`'s `RtpBStats` entry point with a `RtpBStatsCatalogue.Host`.

## Build

```
.\gradlew :bstats-api:build
```

## Docs

- ADR: [`docs/adr/bstats-api-ADR-001-dependency-free-client.md`](docs/adr/bstats-api-ADR-001-dependency-free-client.md)
