# Multi-Platform Minecraft Plugin & Mod SDK (`api/*`)

General-purpose, MIT-licensed, Java 17+ multi-platform Minecraft plugin and mod SDK. Every module under `api/` is usable standalone on Spigot, Paper, Folia, Fabric, NeoForge, and Velocity without depending on `rtp-core` or `rtp-api`.

All 14 modules uphold four shared architectural guarantees:
- **Java 17 bytecode baseline** ([ADR-113](../docs/adr/ADR-113-java-17-baseline-and-26x-support-boundary.md)) so artifacts load on every modern server runtime from Minecraft 1.18+ through 26.x+.
- **Zero `rtp-core` or `rtp-api` dependency**, enforced at bytecode level by ArchUnit package-boundary tests.
- **Zero raw thread creation** (`new Thread` / `Executors.new*ThreadPool`); asynchronous and regional work routes through caller-supplied executors or `TaskScheduler`.
- **Fail-closed contracts** on untrusted disk, YAML, JSON, and command inputs.

---

## Architecture Overview (5 Functional Layers)

### 1. Runtime & Platform Abstraction
- [`server-api`](server-api/README.md) - Platform-neutral server identity (`PlatformFamily`), version compatibility checks, logging, and regionalized/async task scheduling (`ServerAccessor`, `ServerAccessors`, `TaskScheduler`, `TaskHandle`).
- [`commands-api`](commands-api/README.md) - Permission-filtered command tree (`TreeCommand`), `key=value` parameter validation, tick-budgeted execution pipeline (`CommandsAPI`), and Brigadier bridge (`BrigadierCommandAdapter`).
- [`menu-api`](menu-api/README.md) - Declarative multi-page menu model (`MenuDescriptor`), click actions (`MenuAction`), session history and key-value staging cart (`MenuSession`), expiring click tokens (`MenuTokenService`), anvil text prompts (`AnvilInputOpener`), and `ServiceLoader` renderer discovery (`MenuBindingSupport`).
- [`effects-api`](effects-api/README.md) - Prototype-cloned visual and audio effects (`Effect`, `EffectFactory`), permission-node effect builders, hologram handles, and volumetric wireframe particle rendering.

### 2. Configuration, Units & Self-Testing (PBIT / CBIT / IBIT)
- [`yaml-api`](yaml-api/README.md) - Zero-dependency, block-comment-preserving YAML parser and atomic writer (`RtpYamlConfig`, `RtpYamlSection`, `RtpYamlReader`, `RtpYamlWriter`).
- [`units-api`](units-api/README.md) - Dependency-free human-friendly temporal (`20t`, `1d12h`), spatial (`256c`, `4r`, `2km`), and data-size (`256MB`, `1GiB`) parsers with context-aware auto-interpretation (`DurationParser`, `DistanceParser`, `DataSizeParser`, `CacheMemoryCost`).
- [`bit-api`](bit-api/README.md) - Plugin-agnostic Power-On, Continuous, and Initiated Built-In Test (`PBIT` / `CBIT` / `IBIT`) framework (`BitRunner`, `BitComponent`, `BitCodeRegistry`, `BitFinding`, `BitReport`, `BitOutputs`, `BitTable`, live JaCoCo/surface coverage).
- [`config-bit-api`](config-bit-api/README.md) - Declarative YAML `@type` / `@options` / `@range` / `@pattern` comment-directive schema validation (`DirectiveSchemaParser`, `DirectiveValidator`), cross-key distance constraints (`ConfigConstraint`, `GenericConstraintCheck`), `@<file>` reference checks (`ReferenceSettingCheck`), and locale key-parity verification (`MessageKeyParity`).

### 3. Stochastic Control, Allocation & Telemetry
- [`predict-api`](predict-api/README.md) - Zero-dependency online statistical estimators (`EwmaEstimator`, `BernoulliEstimator`, `EwmaLinearFit`), lag-aware AIMD stability limiters (`AimdLimiter`), tick-budget pacers (`BudgetPacer`), variance-gated comparative cost governors (`ComparativeCostGovernor`), max-min water-filling (`WaterFillingAllocator`), per-actor dual-EWMA work attribution (`WorkAttributionLedger`), and Folia probed regional share allocation (`ProbedShareAllocator`).
- [`metrics-api`](metrics-api/README.md) - Platform-portable runtime health SPI (`Metrics`, `MetricsBinding`, `MetricsSnapshot`, `MetricsExtension`, `FoliaRegionSample`) exposing TPS, MSPT, tick-budget utilization, heap usage, and per-region Folia samples.
- [`bstats-api`](bstats-api/README.md) - Dependency-free bStats v2 client (`BStatsService`, `SimplePie`, `AdvancedPie`, `DrilldownPie`, `SingleLineChart`, `MultiLineChart`) requiring zero shaded upstream libraries.

### 4. World Storage & Data Decode
- [`anvil-api`](anvil-api/README.md) - Off-tick read-only Minecraft region-file decoder (`.mca` sectors with gzip, zlib, uncompressed, in-house LZ4, and dynamic Zstd; pluggable `RegionFileReader` SPI via `RegionFormatRegistry`), selective NBT parser (`Nbt`), and load-free surface/biome probes (`AnvilPrefilter`, `AnvilChunkView`, `ColumnProbe`).
- [`tags-api`](tags-api/README.md) - Dependency-free Minecraft data-pack block-tag JSON loader (`DiskTagSource`, `TagFileParser`, `TinyJsonReader`), cycle-safe `#namespace:tag` fixed-point resolver (`TagResolver`), block-state string parser (`BlockStateString`), and ADR-017 block-predicate/subtraction compiler (`SafetyTokenParser`, `StatePredicate`, `CompiledUnsafeSet`, `SafetyCompilationCache`).

### 5. Cartography & Diagnostics Rendering
- [`maps-api`](maps-api/README.md) - Platform-neutral 128x128 in-game cartography and high-resolution PNG/BMP/Web-tile rendering SPI (`MapBinding`, `MapCanvas`, `ImageMapCanvas`, `ChartModel` hierarchy, `HeatmapRenderer`, `DualSparklineRenderer`, `WebTileRenderer`).

---

## Module Catalog

| Module | Gradle Path | Maven Artifact ID | Package Root | Runtime Dependencies | Guide |
|--------|-------------|-------------------|--------------|----------------------|-------|
| `server-api` | `:server-api` | `server-api` | `io.github.dailystruggle.server.api` | Zero (JDK 17 only) | [`server-api/README.md`](server-api/README.md) |
| `commands-api` | `:commands-api` | `commands-api` | `io.github.dailystruggle.commandsapi` | Zero at runtime (`brigadier` compileOnly) | [`commands-api/README.md`](commands-api/README.md) |
| `menu-api` | `:menu-api` | `menu-api` | `io.github.dailystruggle.menu.api` | `:server-api`, `:commands-api` | [`menu-api/README.md`](menu-api/README.md) |
| `effects-api` | `:effects-api` | `effects-api` | `io.github.dailystruggle.effectsapi` | Zero on `common` SPI (`spigot-api` compileOnly for `.bukkit`) | [`effects-api/README.md`](effects-api/README.md) |
| `yaml-api` | `:yaml-api` | `yaml-api` | `io.github.dailystruggle.rtp.common.configuration.yaml` | Zero (JDK 17 only) | [`yaml-api/README.md`](yaml-api/README.md) |
| `units-api` | `:units-api` | `units-api` | `io.github.dailystruggle.rtp.common.selection.region.util` | Zero (JDK 17 only) | [`units-api/README.md`](units-api/README.md) |
| `bit-api` | `:bit-api` | `bit-api` | `io.github.dailystruggle.bit` | Zero (JDK 17 only) | [`bit-api/README.md`](bit-api/README.md) |
| `config-bit-api` | `:config-bit-api` | `config-bit-api` | `io.github.dailystruggle.rtp.common.bit.config` | `:yaml-api`, `:bit-api`, `:units-api` | [`config-bit-api/README.md`](config-bit-api/README.md) |
| `predict-api` | `:predict-api` | `predict-api` | `io.github.dailystruggle.rtp.common.predict` | Zero (JDK 17 only) | [`predict-api/README.md`](predict-api/README.md) |
| `metrics-api` | `:metrics-api` | `metrics-api` | `io.github.dailystruggle.metrics.api` | Zero (JDK 17 only) | [`metrics-api/README.md`](metrics-api/README.md) |
| `bstats-api` | `:bstats-api` | `bstats-api` | `io.github.dailystruggle.bstats.api` | Zero (JDK 17 only) | [`bstats-api/README.md`](bstats-api/README.md) |
| `anvil-api` | `:anvil-api` | `anvil-api` | `io.github.dailystruggle.rtp.anvil` | `:bit-api` (JDK 17 only) | [`anvil-api/README.md`](anvil-api/README.md) |
| `tags-api` | `:tags-api` | `tags-api` | `io.github.dailystruggle.rtp.tags`, `io.github.dailystruggle.rtp.api.{block,safety}` | Zero (JDK 17 only) | [`tags-api/README.md`](tags-api/README.md) |
| `maps-api` | `:maps-api` | `maps-api` | `io.github.dailystruggle.mapsapi` | Zero on core/image (`spigot-api` compileOnly for `.bukkit`) | [`maps-api/README.md`](maps-api/README.md) |

> **Flat Gradle project paths:** On disk, these modules live under `api/<module>`, while `settings.gradle` keeps their Gradle project paths flat (`:<module>`, e.g. `./gradlew :predict-api:build`).

---

## Standalone Consumption (Maven Central & JitPack)

Standalone plugin and mod authors can pull individual SDK modules from **Maven Central** (`io.github.dailystruggle:<module>:<version>`) or **JitPack** (`com.github.DailyStruggle.RTP:<module>:<tag>`):

### Gradle (`build.gradle`)

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    // Maven Central coordinates
    implementation 'io.github.dailystruggle:server-api:3.3.1'
    implementation 'io.github.dailystruggle:yaml-api:3.3.1'
    implementation 'io.github.dailystruggle:units-api:3.3.1'
    implementation 'io.github.dailystruggle:bit-api:3.3.1'
    implementation 'io.github.dailystruggle:config-bit-api:3.3.1'
    implementation 'io.github.dailystruggle:predict-api:3.3.1'

    // Or via JitPack tag / branch snapshot
    // implementation 'com.github.DailyStruggle.RTP:predict-api:3.3.1'
}
```

### Related Documentation

- Authoring a standalone multi-loader plugin or mod with the SDK: [`../docs/FOR_PLUGIN_AUTHORS.md`](../docs/FOR_PLUGIN_AUTHORS.md)
- Building an RTP addon against `rtp-api`: [`../docs/FOR_ADDON_DEVELOPERS.md`](../docs/FOR_ADDON_DEVELOPERS.md)
- Publishing and artifact closure details: [`../docs/dev/PUBLISHING.md`](../docs/dev/PUBLISHING.md)
