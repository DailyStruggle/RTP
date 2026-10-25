# units-api

Dependency-free temporal, spatial, and data-size unit parsers and cache memory footprint estimators (`io.github.dailystruggle.rtp.common.selection.region.util`). Pure Java 17 (`java.base` and `java.logging` only; zero platform or `rtp-core` dependencies), allowing any standalone plugin or mod (e.g. `OnePlayerSleep`, `TreeGen`, `WorldGuardLite`) to parse human-friendly durations (`20t`, `1d12h`), Minecraft distances (`256c`, `4r`, `2km`), and memory budgets (`64MB`, `1GiB`) with context-aware auto-interpretation.

## Coordinates

- **Maven Central:** `io.github.dailystruggle:units-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:units-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:units-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:units-api:3.3.1'
}
```

## Surface

- `DurationParser` & `TemporalUnit` - parses single (`"20t"`, `"5s"`, `"100ms"`) and composite (`"1d12h"`, `"2h 30m 10s"`) duration strings into `DurationParser.ParsedDuration(magnitude, unit, explicitUnit)` with `toSeconds()`, `toMillis()`, `toTicks()`, and heuristic `autoInterpret(parsed, expectedUnit, contextName[, logInfo])`. `TemporalUnit` covers `TICK` (`0.05s`), `MILLISECOND`, `SECOND`, `MINUTE`, `HOUR`, `DAY`, and `WEEK`.
- `DistanceParser` & `SpatialUnit` - parses distance tokens (`"256c"`, `"4096b"`, `"4r"`, `"2km"`) into `DistanceParser.ParsedDistance(magnitude, unit, explicitUnit)` with `toBlocks()`, `toChunks()`, `toRegions()`, and world-border-aware `autoInterpret(parsed, worldBorderRadius, contextName[, logInfo])`. `SpatialUnit` covers Minecraft units (`BLOCK`, `NETHER_BLOCK`, `CHUNK` = 16 blocks, `REGION` = 512 blocks), metric (`MILLIMETER`..`KILOMETER`), imperial (`INCH`..`NAUTICAL_MILE`), and astronomical/historical units.
- `DataSizeParser` & `DataSizeUnit` - parses byte/memory strings (`"256MB"`, `"1GiB"`, `"64KB"`, `"unlimited"`) into `DataSizeParser.ParsedDataSize` (`toBytes()`, `toMegabytes()`, `toMebibytes()`, `toGibibytes()`) or directly to `long` via `DataSizeParser.parseBytes(Object input, long defBytes)`. `DataSizeUnit` covers decimal (`BYTES`, `KILOBYTES`, `MEGABYTES`, `GIGABYTES`) and binary (`KIBIBYTES`, `MEBIBYTES`, `GIBIBYTES`) units.
- `CacheMemoryCost` - converts either an explicit memory cap (`"64MB"`, `"1GiB"`) or a raw entry count into bounded item capacity (`resolveCapacity(rawValue, fallbackCount, bytesPerEntry)` / `resolveCapacityInt(...)`) using per-entry footprint constants (`HOT_CACHE_BYTES_PER_ENTRY = 1 MiB`, `COLD_CACHE_BYTES_PER_ENTRY = 128 B`, `BACKLOG_CACHE_BYTES_PER_ENTRY = 128 B`).

## Usage

```java
import io.github.dailystruggle.rtp.common.selection.region.util.CacheMemoryCost;
import io.github.dailystruggle.rtp.common.selection.region.util.DataSizeParser;
import io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser;
import io.github.dailystruggle.rtp.common.selection.region.util.DurationParser;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;

// Standalone plugin (e.g. WorldGuardLite) parsing operator config values:
DurationParser.ParsedDuration cooldown = DurationParser.parse("1m 30s", TemporalUnit.SECOND);
long cooldownTicks = Math.round(cooldown.toTicks()); // 1800 ticks

DistanceParser.ParsedDistance border = DistanceParser.parse("16c", SpatialUnit.BLOCK);
double borderBlocks = border.toBlocks(); // 256.0 blocks

long maxFileBytes = DataSizeParser.parseBytes("64MiB", 16L * 1024L * 1024L);
int maxHotChunks = CacheMemoryCost.resolveCapacityInt(
        "32MiB", 16, CacheMemoryCost.HOT_CACHE_BYTES_PER_ENTRY); // 32 entries
```

## Build

```
./gradlew :units-api:build
```

## Docs

- ADR-113: [`../../docs/adr/ADR-113-java-17-baseline-and-26x-support-boundary.md`](../../docs/adr/ADR-113-java-17-baseline-and-26x-support-boundary.md)
