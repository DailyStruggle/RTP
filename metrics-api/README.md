# metrics-api

Platform-portable metrics SPI for the RTP monorepo. A pure-Java module (no Bukkit,
Paper, Folia, Fabric, or Loom dependency) that defines the read-only contract for
runtime health signals: TPS, MSPT, tick-budget utilisation, player count, soft cap,
heap usage, and optional Folia per-region samples.

The package root is deliberately neutral (`io.github.dailystruggle.metrics.api`) so
sibling plugins in the same monorepo can consume the SPI without depending on
`rtp-core`.

## Who consumes this

- **Host plugins** (RTP) own a concrete aggregator (`CoreMetrics`) that implements
  `Metrics` and mirrors its active binding into the static registry.
- **Platform adapters** implement `MetricsBinding` (e.g. `PaperMetricsBinding`,
  `BukkitTpsSampler`, `FoliaMetricsBinding`, `FabricMetricsBinding`) and register it.
- **Addons / sibling plugins** read `Metrics.currentBinding()` or a host-provided
  `Metrics.snapshot()`, and may contribute plugin-specific counters through a
  `MetricsExtension`.

## Surface

- `Metrics` &mdash; read-only facade (`snapshot()`) plus a thread-safe static registry
  (`registerBinding` / `currentBinding` / `registerExtension` / `registeredExtensions`).
  Binding registration is last-writer-wins with a `WARNING` log when a non-NOOP binding
  is displaced; extension registration is additive.
- `MetricsBinding` &mdash; host-runtime contract supplied by each platform adapter. All
  methods are defaulted, so a binding implements only the fields its platform can
  produce. `MetricsBinding.NOOP` is the default until one is installed.
- `MetricsSnapshot` &mdash; immutable host-runtime snapshot. Carries only platform-neutral
  fields; plugin-specific counters live on a `MetricsExtension` attached via
  `withExtension(...)` and read back type-safely via `extension(Class<T>)`.
- `MetricsExtension<SELF>` &mdash; typed slot for plugin-specific counters; one registered
  instance per concrete extension class.
- `FoliaRegionSample` &mdash; per-region detail carrier (empty list on non-Folia runtimes).

## How to implement a binding

1. Implement `MetricsBinding`, overriding only the host-runtime methods your platform
   can answer (e.g. `tps1m()`, `mspt()`, `playerCount()`).
2. From your platform adapter's startup path, call
   `Metrics.registerBinding(new MyPlatformBinding(...))`.
3. Readers obtain values through the host plugin's `Metrics.snapshot()` (which folds the
   binding plus any registered extensions into one immutable snapshot) or directly via
   `Metrics.currentBinding()`.

Concrete bindings and the host aggregator never live in this module &mdash; they belong in
their respective platform adapters and the host plugin. An ArchUnit guard
(`MetricsConsolidationArchTest`) enforces that no `org.bukkit.*` / `net.minecraft.*`
type leaks into `metrics-api` and that concrete bindings stay out of `rtp-core` /
`rtp-api`.

## Build

```
.\gradlew :metrics-api:build
```

## Docs

- ADR: [`docs/adr/metrics-api-ADR-001-module-extraction.md`](docs/adr/metrics-api-ADR-001-module-extraction.md)
- Task router: [`docs/INDEX.md`](docs/INDEX.md)
