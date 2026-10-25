# yaml-api

Zero-dependency, block-comment-preserving YAML configuration substrate and atomic file writer (`io.github.dailystruggle.rtp.common.configuration.yaml`). Pure Java 17 (`java.base` only; no SnakeYAML, Simple-YAML, Bukkit, Fabric, or `rtp-core` dependencies), enabling any standalone plugin or mod (e.g. `OnePlayerSleep`, `TreeGen`, `WorldGuardLite`) to load, mutate, and atomically save commented YAML files with byte-for-byte round-trip idempotence.

## Coordinates

- **Maven Central:** `io.github.dailystruggle:yaml-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:yaml-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:yaml-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:yaml-api:3.3.1'
}
```

## Surface

### YAML AST & Atomic Writer (`io.github.dailystruggle.rtp.common.configuration.yaml`)

- `RtpYamlConfig` - top-level YAML document handle extending `RtpYamlSection` (`RtpYamlConfig.load(File)`, `parse(String)`, `parse(Reader)`, `empty(File)`, `createOrLoad()`, `save()`, `save(File)`, `saveToString()`, `addDefault(key, value)`). `save()` writes to a sibling temporary file and atomically renames over the target.
- `RtpYamlSection` - dotted-path (`"database.type"`) read/write view over a mapping (`get`, `getString`, `getInt`, `getLong`, `getDouble`, `getBoolean`, `getList`, `getStringList`, `getConfigurationSection`, `createSection`, `set`, `remove`, `getKeys(boolean deep)`, `getValues(boolean deep)`, `getComment(key)`, `setComment(key, comment)`).
- `RtpYamlMapping` - insertion-ordered (`LinkedHashMap`) AST mapping node (`keys()`, `entries()`, `get(key)`, `put(key, node)`, `trailingComments()`) extending `RtpYamlNode`.
- `RtpYamlSequence` - ordered AST sequence node (`items()`, `add(node)`, `size()`, `get(i)`, `isFlowStyle()`, `setFlowStyle(boolean)`) supporting both block (`- item`) and flow (`[a, b]`) styles.
- `RtpYamlScalar` - AST scalar node (`rawValue()`, `style()`, `value()`) preserving lexical quoting (`Style.PLAIN`, `Style.SINGLE`, `Style.DOUBLE`) and coercing plain tokens to Java primitives.
- `RtpYamlReader` & `RtpYamlWriter` - recursive-descent YAML parser (`RtpYamlReader.parse(String)` / `parse(Reader)`, bounded by `MAX_DEPTH = 128` and `MAX_SOURCE_BYTES = 4 MiB`, throwing `RtpYamlParseException` with stable `messageKey`s) and idempotent emitter (`RtpYamlWriter.emit(RtpYamlMapping)`).
- `RtpYamlOptions` - fluent options holder (`copyDefaults(boolean)`, `indent(int)`) returned by `RtpYamlConfig#options()`.
- `YamlFileCache` - modification-time-cached directory map of `RtpYamlConfig` documents (`cachedLookup`, `cachedLookupLastModified`, `lastParseErrors`, `directory()`, `connect()`, `disconnect(Map<String, RtpYamlConfig>)`).

### Enum-Keyed Configuration & Factory Framework (`io.github.dailystruggle.rtp.common.configuration` & `factory`)

- `ConfigParser<E extends Enum<E>>` - enum-keyed, locale-aware, comment-preserving single-file configuration manager extending `FactoryValue<E>` (`getConfigValue`, `getNumber`, `getDuration`, `getTime`, `getDataSize`, `getMap`, `set`, `save`, `update`). Supports pluggable `@<file>` cross-file default resolution via `ConfigParser.setDefaultReferenceResolver(ReferenceResolver)` and section factory resolution via `ConfigParser.setSectionFactoryResolver(BiFunction)`.
- `MultiConfigParser<E extends Enum<E>>` - directory-scanning manager for folders of same-schema `.yml` files (e.g. `regions/*.yml`, `worlds/*.yml`) with template cloning from `default.yml` (`getParser`, `addParser`, `removeParser`, `listParsers`, `setCanCreateDynamicParser`).
- `ConfigBackups` - FIFO rotating sibling backup manager (`<file>.bak.<epochMillis>`, `backup(File, int)`, `listBaks(File)`, `restoreLatest(File)`, `pruneBaks(File, int)`).
- `ConfigDirectives` - canonical ADR-064 block-comment directive parser (`@type`, `@options`, `@range`, `@pattern`, `@unit`, `@default`, `@source`; `parse(String)`, `parseLines(List<String>)`, `compiledPattern()`, `hasPattern()`, `patternMalformed()`, `hasRange()`, `rangeMalformed()`, `hasFiniteDomain()`).
- `ConfigLoader` & `LanguageBootstrap` - JAR resource extraction contract and active-locale (`language.yml`) bootstrap resolver.
- `Factory<T extends FactoryValue<?>>` & `FactoryValue<E extends Enum<E>>` - copy-on-write, enum-keyed prototype registry and base value carrier with fuzzy property matching (`FuzzySearchEngine`) and unit-aware numeric coercion (`units-api`).

## Usage

### 1. Low-Level `RtpYamlConfig`

```java
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.io.File;
import java.util.List;

// Standalone plugin (e.g. OnePlayerSleep) loading, commenting, and atomically saving config.yml:
File configFile = new File("plugins/OnePlayerSleep/config.yml");
RtpYamlConfig config = RtpYamlConfig.load(configFile);

config.addDefault("sleep.percentage", 50);
config.addDefault("sleep.clearWeather", true);
config.addDefault("sleep.exemptWorlds", List.of("world_nether", "world_the_end"));

config.setComment("sleep.percentage",
        "Percentage of online players required to skip the night.\n@type: integer\n@range: [1, 100]");

int requiredPercent = config.getInt("sleep.percentage", 50);
RtpYamlSection sleepSection = config.getConfigurationSection("sleep");
List<String> exemptWorlds = sleepSection != null
        ? sleepSection.getStringList("exemptWorlds")
        : List.of();

// Atomically writes via temp file + rename, preserving all block comments and quoting styles
config.save();
```

### 2. Enum-Keyed `ConfigParser<MyConfigKeys>` & `MultiConfigParser<MyDefinitionKeys>`

```java
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.yaml.YamlFileCache;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;
import java.io.File;

public enum MyConfigKeys {
    sleepDelay,
    maxCanopyRadius,
    cacheLimit,
    version
}

public enum MyZoneKeys {
    world,
    radius,
    enabled,
    version
}

File dataFolder = new File("plugins/TreeGen");
String locale = LanguageBootstrap.resolve(dataFolder);
YamlFileCache fileCache = new YamlFileCache(dataFolder);

// Single-file configuration with automatic version upgrade, locale migration, and .bak rotation:
ConfigParser<MyConfigKeys> config = new ConfigParser<>(
        MyConfigKeys.class, "config.yml", "1.0", dataFolder, null, fileCache,
        MyConfigKeys.class.getClassLoader(), locale);

long delayTicks = config.getDuration(MyConfigKeys.sleepDelay, TemporalUnit.SECOND, TemporalUnit.TICK, 40L);
int radius = config.getNumber(MyConfigKeys.maxCanopyRadius, 8).intValue();

// Folder of same-schema YAML definitions (e.g. plugins/TreeGen/zones/*.yml seeded from zones/default.yml):
MultiConfigParser<MyZoneKeys> zones = new MultiConfigParser<>(
        MyZoneKeys.class, "zones", "1.0", dataFolder,
        MyZoneKeys.class.getClassLoader(), "zones", locale);
ConfigParser<MyZoneKeys> spawnZone = zones.getParser("spawn");
```

## Build

```
./gradlew :yaml-api:build
```

## Docs

- ADR-025: [`../../docs/adr/ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md`](../../docs/adr/ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md)
- ADR-042: [`../../docs/adr/ADR-042-yaml-comment-preservation-block-only.md`](../../docs/adr/ADR-042-yaml-comment-preservation-block-only.md)
