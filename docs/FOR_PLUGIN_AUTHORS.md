# Start Here - Standalone Plugin & Mod Authors (`api/*` SDK)

**Current SDK Version:** `@version@`

If you want to extend **LeafRTP** itself (for example with a custom region shape, safety verifier, or post-teleport hook), see [FOR_ADDON_DEVELOPERS.md](FOR_ADDON_DEVELOPERS.md) and the [Addon Quickstart](ADDON_QUICKSTART.md).

This guide is for developers building a **standalone Minecraft plugin or mod** - such as `OnePlayerSleep`, `TreeGen`, or `ChunkClaimLite` - that has nothing to do with random teleportation, but wants **Spigot, Paper, Folia, Fabric, NeoForge, and Velocity** support, comment-preserving YAML configs with self-testing directives, Brigadier commands, book/chest menus, adaptive Folia tick budgeting, and automated whole-server leak/stall fuzzing out of the box.

---

## 1. Why Use the LeafCraft `api/*` SDK?

Roughly 80% of this repository is **platform-neutral infrastructure** (`api/*`, multi-version platform carriers, headless fuzzers, and release tooling) rather than teleport-specific logic. Every subsystem that a production server plugin needs has been extracted into 14 standalone, MIT-licensed Java 17 libraries under [`api/`](../api/README.md):

- **Write once, run on 6 runtimes:** Your core plugin logic compiles against pure Java 17 interfaces (`server-api`, `commands-api`, `yaml-api`, `menu-api`, `effects-api`, `predict-api`). Thin platform carriers bind those interfaces to **Spigot, Paper, Folia, Fabric, NeoForge, and Velocity** without leaking `org.bukkit.*` or `net.minecraft.*` imports into your domain code.
- **Zero-dependency foundations:** Core SDK libraries (`server-api`, `yaml-api`, `units-api`, `tags-api`, `bit-api`, `predict-api`, `bstats-api`, `metrics-api`) have **zero external runtime dependencies** and compile to Java 17 bytecode ([ADR-113](adr/ADR-113-java-17-baseline-and-26x-support-boundary.md)).
- **Folia-native by construction:** Regional thread ownership (`TaskScheduler.isOwnedByCurrentRegion`, `runAtLocation`) and adaptive per-region share allocation (`ProbedShareAllocator`, `AimdLimiter`, `BudgetPacer`) are first-class primitives, eliminating raw thread creation (`F-001`) and main-thread stalls (`S-005`).
- **Built-In Test (PBIT / CBIT / IBIT):** Instead of waiting for operators to paste broken YAML files in Discord, `bit-api` and `config-bit-api` validate `@type`, `@options`, `@range`, `@pattern`, and locale key parity at startup (PBIT), track runtime anomalies without log spam (CBIT), and run interactive diagnostics on demand (IBIT; [ADR-121](adr/ADR-121-built-in-test-for-configuration-and-the-action-engine.md)).
- **Published to Maven Central & JitPack:** Consume only the modules your plugin needs via `io.github.dailystruggle:<module>:<version>` from `mavenCentral()` or `com.github.DailyStruggle.RTP:<module>:<tag>` from `jitpack.io` ([dev/PUBLISHING.md](dev/PUBLISHING.md)).

---

## 2. Module Selection Matrix

Pick only the SDK modules that match your plugin's requirements. Full module catalog: [`api/README.md`](../api/README.md).

### By Plugin Need

| Plugin Need | SDK Module(s) | Key Entry Points |
|---|---|---|
| Platform detection, version gating & Folia regional scheduling | `server-api` | `ServerAccessor`, `ServerAccessors`, `PlatformFamily`, `TaskScheduler`, `TaskHandle` |
| Brigadier command trees, `key=value` parameters & regex (`reg:`) tab completion | `commands-api` | `TreeCommand`, `CommandsAPICommand`, `CommandParameter`, `BrigadierCommandAdapter` |
| Comment-preserving YAML + `@type` / `@range` / `@options` self-tests & unit parsing | `yaml-api` + `units-api` + `bit-api` + `config-bit-api` | `RtpYamlConfig`, `DistanceParser`, `DurationParser`, `DataSizeParser`, `BitRunner`, `DirectiveValidator`, `MessageKeyParity` |
| Declarative chest GUIs, written-book menus, chat click tokens & anvil text prompts | `menu-api` | `MenuDescriptor`, `MenuRenderer`, `MenuSession`, `MenuTokenService`, `AnvilInputOpener` |
| Cross-platform particles, sounds, titles, potions, glide & virtual holograms | `effects-api` | `EffectsAPI`, `EffectFactory`, `ParticleEffect`, `SoundEffect`, `HologramRegistry`, `VolumetricParticleRenderer` |
| TPS / MSPT / heap snapshots, Folia region sampling & dependency-free bStats v2 | `metrics-api` + `bstats-api` | `Metrics`, `MetricsBinding`, `MetricsSnapshot`, `FoliaRegionSample`, `BStatsService`, `SimplePie` |
| Off-tick `.mca` / `.linear` region NBT decoding, block-tag JSON resolution & block-state predicate compilation | `anvil-api` + `tags-api` | `RegionFileReader`, `RegionFormatRegistry`, `AnvilReader`, `AnvilChunkView`, `TagResolver`, `DiskTagSource`, `BlockStateString`, `SafetyTokenParser`, `StatePredicate`, `CompiledUnsafeSet`, `SafetyCompilationCache` |
| EWMA cost estimation, AIMD throttling, max-min water-filling, Folia share allocation, unit-interval shaping curves, distribution samplers & polar spirals | `predict-api` | `EwmaEstimator`, `AimdLimiter`, `BudgetPacer`, `ComparativeCostGovernor`, `WaterFillingAllocator`, `WorkAttributionLedger`, `ProbedShareAllocator`, `UnitCurve`, `DistributionSampler`, `PolarCurve` |
| In-game 2D map canvases, spatial heatmaps, sparklines & Mermaid chart rendering | `maps-api` | `MapBinding`, `MapCanvas`, `HeatmapRenderer`, `DualSparklineRenderer`, `ChartRenderer` |

### All 14 `api/*` Maven Coordinates

| Module | Maven Central Coordinate | Inter-Module Dependencies |
|---|---|---|
| `server-api` | `io.github.dailystruggle:server-api:3.2.1` | *(none)* |
| `units-api` | `io.github.dailystruggle:units-api:3.2.1` | *(none)* |
| `tags-api` | `io.github.dailystruggle:tags-api:3.2.1` | *(none)* |
| `yaml-api` | `io.github.dailystruggle:yaml-api:3.2.1` | *(none)* |
| `bit-api` | `io.github.dailystruggle:bit-api:3.2.1` | *(none)* |
| `config-bit-api` | `io.github.dailystruggle:config-bit-api:3.2.1` | `yaml-api`, `bit-api`, `units-api` |
| `commands-api` | `io.github.dailystruggle:commands-api:3.2.1` | *(none)* |
| `menu-api` | `io.github.dailystruggle:menu-api:3.2.1` | `server-api`, `commands-api` |
| `effects-api` | `io.github.dailystruggle:effects-api:3.2.1` | `server-api` |
| `maps-api` | `io.github.dailystruggle:maps-api:3.2.1` | *(none)* |
| `metrics-api` | `io.github.dailystruggle:metrics-api:3.2.1` | *(none)* |
| `bstats-api` | `io.github.dailystruggle:bstats-api:3.2.1` | *(none)* |
| `predict-api` | `io.github.dailystruggle:predict-api:3.2.1` | *(none)* |
| `anvil-api` | `io.github.dailystruggle:anvil-api:3.2.1` | `bit-api` |

---

## 3. 5-File Multi-Platform Plugin Blueprint (`OnePlayerSleep` / `TreeGen` Example)

Below is a complete, minimal 5-part architecture for a standalone multi-platform plugin (`TreeGen` / `OnePlayerSleep`) that runs across Spigot, Paper, Folia, Fabric, and NeoForge without importing platform classes in its core module.

### Gradle Setup (`build.gradle`)

```gradle
plugins {
    id 'java-library'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    // Optional: use JitPack to track a specific git tag or V3-SNAPSHOT
    maven { url = 'https://jitpack.io' }
}

ext {
    leafSdkVersion = '3.2.1'
}

dependencies {
    api "io.github.dailystruggle:server-api:${leafSdkVersion}"
    api "io.github.dailystruggle:yaml-api:${leafSdkVersion}"
    api "io.github.dailystruggle:units-api:${leafSdkVersion}"
    api "io.github.dailystruggle:bit-api:${leafSdkVersion}"
    api "io.github.dailystruggle:config-bit-api:${leafSdkVersion}"
    api "io.github.dailystruggle:commands-api:${leafSdkVersion}"
    api "io.github.dailystruggle:predict-api:${leafSdkVersion}"
}
```

### File 1: `src/main/resources/config.yml` (Comment Directives for Self-Testing YAML)

Annotate your default YAML comments with `@type`, `@range`, `@options`, and `@unit` ([ADR-064](adr/ADR-064-comment-directives-for-finite-value-domains.md)). `RtpYamlConfig` preserves comments across saves, and `DirectiveValidator` turns those comments into executable startup checks:

```yaml
# Maximum canopy radius in blocks for custom tree generation.
# @type integer
# @range [2, 32]
# @default 8
maxCanopyRadius: 8

# Sleep fraction required to advance night in OnePlayerSleep mode.
# @type number
# @range [0.0, 1.0]
# @default 0.01
sleepThresholdRatio: 0.01

# Generation mode across biomes.
# @type enum
# @options [NATURAL, DENSE, SPARSE]
# @default NATURAL
generationStyle: NATURAL

# Per-tick background decoration budget in milliseconds.
# @type number
# @range [0.25, 10.0]
# @default 2.0
tickBudgetMs: 2.0
```

### File 2: `TreeGenConfig.java` (`yaml-api` + `units-api`)

Define an enum of your configuration keys and use `ConfigParser<MyConfigKeys>` for single-file loading, schema version migration (`.old1` backups), locale switching (`LanguageBootstrap`), `.bak.<epochMillis>` rollback snapshots (`ConfigBackups`), and unit-aware duration/data-size getters, plus `MultiConfigParser<MyZoneKeys>` for folders of `.yml` definitions (or use low-level `RtpYamlConfig` directly):

```java
package com.example.treegen;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.YamlFileCache;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;
import java.io.File;
import java.io.IOException;

public final class TreeGenConfig {
    public enum ConfigKeys {
        maxCanopyRadius,
        sleepThresholdRatio,
        generationStyle,
        tickBudgetMs,
        version
    }

    public enum BiomeProfileKeys {
        canopyDensity,
        allowCustomSaplings,
        regrowthDelay,
        version
    }

    private final File dataFolder;
    private ConfigParser<ConfigKeys> configParser;
    private MultiConfigParser<BiomeProfileKeys> biomeProfiles;

    public TreeGenConfig(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    public void loadOrCreateDefault(ClassLoader resourceLoader) throws IOException {
        String locale = LanguageBootstrap.resolve(dataFolder);
        YamlFileCache fileCache = new YamlFileCache(dataFolder);
        this.configParser = new ConfigParser<>(
                ConfigKeys.class,
                "config.yml",
                "1.0",
                dataFolder,
                null,
                fileCache,
                resourceLoader,
                locale);
        // Manages plugins/TreeGen/profiles/*.yml cloned from profiles/default.yml
        this.biomeProfiles = new MultiConfigParser<>(
                BiomeProfileKeys.class,
                "profiles",
                "1.0",
                dataFolder,
                resourceLoader,
                "profiles",
                locale);
    }

    public int maxCanopyRadius() {
        return configParser.getNumber(ConfigKeys.maxCanopyRadius, 8).intValue();
    }

    public double sleepThresholdRatio() {
        return configParser.getNumber(ConfigKeys.sleepThresholdRatio, 0.01).doubleValue();
    }

    public double tickBudgetMs() {
        return configParser.getNumber(ConfigKeys.tickBudgetMs, 2.0).doubleValue();
    }

    public ConfigParser<BiomeProfileKeys> profile(String profileName) {
        return biomeProfiles.getParser(profileName);
    }

    public long regrowthDelayTicks(String profileName) {
        return profile(profileName).getDuration(
                BiomeProfileKeys.regrowthDelay, TemporalUnit.SECOND, TemporalUnit.TICK, 200L);
    }

    public RtpYamlConfig raw() {
        return (RtpYamlConfig) configParser.getYamlRoot();
    }

    public File file() {
        return new File(dataFolder, "config.yml");
    }
}
```

### File 3: `TreeGenBitSuite.java` (`bit-api` + `config-bit-api`)

Register a Power-On Built-In Test (PBIT) that validates the on-disk `config.yml` against your bundled `config.yml` directives, plus a Continuous Built-In Test (CBIT) counter for runtime anomalies:

```java
package com.example.treegen;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitRunner;
import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.CbitCounter;
import io.github.dailystruggle.rtp.common.bit.config.ConfigSnapshot;
import io.github.dailystruggle.rtp.common.bit.config.DirectiveValidator;
import java.util.List;
import java.util.Map;

public final class TreeGenBitSuite {
    private final BitRunner runner = new BitRunner();
    private final CbitCounter skippedPlacements = new CbitCounter("treegen.placement.skipped");

    public TreeGenBitSuite(TreeGenConfig config, ClassLoader resourceLoader) {
        runner.register(new BitComponent(
                "treegen.config.directives",
                "config",
                BitTier.PBIT,
                BitSideEffect.SAFE,
                5_000L,
                () -> {
                    ConfigSnapshot.Document doc = new ConfigSnapshot.Document(
                            "config.yml", config.raw(), Map.of(), Map.of());
                    String bundled = DirectiveValidator.readResource(resourceLoader, "config.yml");
                    List<BitFinding> findings = DirectiveValidator.validate(doc, bundled);
                    return BitReport.of(findings);
                }));
    }

    public BitReport runStartupPbit() {
        List<BitResult> results = runner.run(BitSelector.tier(BitTier.PBIT), Runnable::run);
        return runner.aggregate(results);
    }

    public BitRunner runner() {
        return runner;
    }

    public CbitCounter skippedPlacements() {
        return skippedPlacements;
    }
}
```

### File 4: `TreeGenRootCommand.java` (`commands-api`)

Implement `TreeCommand` once to get subcommand routing, `key=value` parameter validation, regex token expansion (`reg:.*`), and Brigadier command-graph registration across Bukkit, Paper, Folia, Fabric, and NeoForge:

```java
package com.example.treegen;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class TreeGenRootCommand implements TreeCommand {
    private final Map<String, CommandParameter> parameters = new ConcurrentHashMap<>();
    private final Map<String, CommandsAPICommand> subCommands = new ConcurrentHashMap<>();
    private final TreeGenService service;

    public TreeGenRootCommand(TreeGenService service) {
        this.service = service;
        addParameter("radius", new IntegerParameter(
                "treegen.use",
                "Canopy radius in blocks (2..32)",
                (caller, value) -> {
                    int parsed = Integer.parseInt(value);
                    return parsed >= 2 && parsed <= 32;
                },
                2, 4, 8, 16, 32));
        addParameter("style", new EnumParameter<>(
                "treegen.use",
                "Canopy generation style",
                (caller, value) -> true,
                Style.class));
    }

    public enum Style { NATURAL, DENSE, SPARSE }

    @Override
    public String name() {
        return "treegen";
    }

    @Override
    public String permission() {
        return "treegen.use";
    }

    @Override
    public String description() {
        return "Generate a custom canopy or run built-in diagnostics";
    }

    @Override
    public CommandsAPICommand parent() {
        return null;
    }

    @Override
    public void msgBadParameter(UUID callerId, String parameterName, String parameterValue, Consumer<String> messageMethod) {
        messageMethod.accept("[TreeGen] Invalid parameter " + parameterName + "=" + parameterValue);
    }

    @Override
    public void msgNoPermission(UUID callerId, String permission, Consumer<String> messageMethod) {
        messageMethod.accept("[TreeGen] Missing permission: " + permission);
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand, Consumer<String> messageMethod) {
        if (nextCommand != null) {
            return true;
        }
        int radius = parameterValues.getOrDefault("radius", List.of("8"))
                .stream().findFirst().map(Integer::parseInt).orElse(8);
        service.scheduleCanopyJob(callerId, "world", 0, 0, radius, messageMethod);
        return true;
    }

    @Override
    public Map<String, CommandParameter> getParameterLookup() {
        return parameters;
    }

    @Override
    public Map<String, CommandsAPICommand> getCommandLookup() {
        return subCommands;
    }
}
```

### File 5: `TreeGenService.java` (`server-api` + `predict-api`)

Combine `ServerAccessor` / `TaskScheduler` (`server-api`) with `EwmaEstimator`, `AimdLimiter`, `BudgetPacer`, and `WaterFillingAllocator` (`predict-api`) so your plugin automatically paces its work on both single-thread servers and Folia regionalized servers:

```java
package com.example.treegen;

import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.common.predict.AimdLimiter;
import io.github.dailystruggle.rtp.common.predict.BudgetPacer;
import io.github.dailystruggle.rtp.common.predict.EwmaEstimator;
import io.github.dailystruggle.rtp.common.predict.WaterFillingAllocator;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import io.github.dailystruggle.server.api.TaskScheduler;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class TreeGenService {
    private final ServerAccessor server;
    private final TreeGenConfig config;
    private final TreeGenBitSuite bitSuite;
    private final EwmaEstimator stepCostEstimator = EwmaEstimator.ofWindow(32, TimeUnit.MICROSECONDS.toNanos(250));
    private final BudgetPacer pacer = BudgetPacer.ofEstimator(stepCostEstimator, 1.10);
    private final AimdLimiter budgetLimiter;

    public TreeGenService(ServerAccessor server, TreeGenConfig config, TreeGenBitSuite bitSuite) {
        this.server = server;
        this.config = config;
        this.bitSuite = bitSuite;
        this.budgetLimiter = new AimdLimiter(0.25, config.tickBudgetMs());
        ServerAccessors.setCurrent(server);
    }

    public void enable() {
        BitReport pbit = bitSuite.runStartupPbit();
        if (!pbit.findings().isEmpty()) {
            server.log(Level.WARNING, "[TreeGen BIT] Startup config findings: " + pbit.findings().size());
        }
    }

    public void scheduleCanopyJob(
            UUID actorId, String worldName, int chunkX, int chunkZ, int radius, Consumer<String> reply) {
        TaskScheduler scheduler = server.scheduler()
                .orElseThrow(() -> new IllegalStateException("TaskScheduler unavailable on " + server.getPlatform()));

        // Fairly divide tick budget across active worlds using max-min water-filling:
        double effectiveBudgetMs = budgetLimiter.publishedValue();
        Map<String, Double> worldBudgetsMs = WaterFillingAllocator.allocateRates(
                Map.of(worldName, (double) radius),
                Map.of(worldName, 1),
                effectiveBudgetMs);
        long availableNanos = Math.round(worldBudgetsMs.getOrDefault(worldName, 0.5) * 1_000_000.0);

        // Dispatch onto the owning region thread on Folia, or primary thread on Paper/Spigot/Fabric/NeoForge:
        scheduler.runAtLocation(worldName, chunkX, chunkZ, () -> {
            long startNanos = System.nanoTime();
            boolean ranAny = false;
            int placed = 0;
            while (placed < radius && pacer.canExecute(System.nanoTime() - startNanos, availableNanos, ranAny)) {
                long stepStart = System.nanoTime();
                // Perform region-safe block state placement here (already on owning region thread)
                placed++;
                ranAny = true;
                pacer.recordDuration(System.nanoTime() - stepStart);
            }
            reply.accept("[TreeGen] Placed " + placed + " canopy segments on " + server.getPlatform());
        });
    }
}
```

---

## 4. Folia Regional Safety & Adaptive Budgeting

Plugins that mutate blocks or entities on background threads crash Folia immediately, while plugins that run unbounded loops on a region thread cause acute MSPT spikes. The combination of `server-api` and `predict-api` solves both problems with zero platform imports.

### Rule `F-001` & Region Ownership (`server-api`)

1. **Never spawn raw threads (`new Thread(...)`, `Executors.new*ThreadPool`)** in plugin code. Always route asynchronous and regional work through `ServerAccessor.scheduler()` (`TaskScheduler`).
2. **Check or route by coordinates:** Before touching a chunk or entity at `(worldName, chunkX, chunkZ)`, either check `scheduler.isOwnedByCurrentRegion(worldName, chunkX, chunkZ)` or dispatch via `scheduler.runAtLocation(worldName, chunkX, chunkZ, task)`. On Paper/Spigot/Fabric/NeoForge, `runAtLocation` delegates to `runSync`; on Folia, it schedules onto the owning region's tick queue.
3. **Never block the tick thread on chunk I/O (`S-005`):** When scanning unloaded terrain (for example, searching for biomes or structures), read `.mca` region files off-tick using `anvil-api` (`RegionFileReader`, `AnvilReader`, `AnvilChunkView`) inside `scheduler.runAsync(...)`, and only jump to `scheduler.runAtLocation(...)` once a candidate chunk is selected.

### Adaptive Control & Multi-Tenant Fairness (`predict-api`)

`api/predict-api` provides five composable control primitives that keep background tasks within a strict tick budget ([ADR-087](adr/ADR-087-reactive-governor-closed-loop-control.md), [ADR-109](adr/ADR-109-empirical-cost-governor-for-anvil-prefilter.md), [ADR-123](adr/ADR-123-folia-regional-work-division-and-idle-thread-lending.md), [ADR-124](adr/ADR-124-multi-region-pareto-fair-governor.md)):

| Control Primitive | Role in a Standalone Plugin |
|---|---|
| `EwmaEstimator` & `BudgetPacer` | Tracks moving mean and Welford variance of per-step nanosecond cost. `BudgetPacer.canExecute(elapsedNanos, availableNanos, ranAny)` guarantees **Forward Progress** (at least 1 unit runs on any positive budget) and **Overrun Protection** (yields before exceeding the deadline). |
| `AimdLimiter` | Lag-aware Additive-Increase / Multiplicative-Decrease controller. Cuts the budget by `0.5x` on an MSPT/tick breach, holds during stale telemetry (`ADR-087` I6), and recovers linearly (`+10%` per `5 s` clean window) with an asymmetric deadband. |
| `WorkAttributionLedger` | Lock-free 4096-entry LRU ledger that tracks distinct active players per bucket/world ($n_b \ge 1$) over a 10-minute window and per-player dual-EWMA (`30 s` and `10 min` half-lives) attributed cost ($a_p$ in `ms/min`). Prevents one player spamming a command from starving everyone else. |
| `WaterFillingAllocator` | Weighted max-min water-filling (`allocateRates(demands, weights, totalBudget)`) plus `jainsFairnessIndex(values)`. Gives small worlds/tasks 100% of their demand while splitting congested budget proportionally to active consumer counts ($w_b = n_b$). |
| `ProbedShareAllocator` | Folia per-region share allocator (`ADR-123`). Maintains per-region AIMD limiters, down-weights regions with higher player populations ($w'_k = w_k / (1 + P_k / 4)$) to minimize bystander lag exposure, and lends idle region threads from a global lending pool ($\Pi$). |
| `UnitCurve`, `DistributionSampler` & `PolarCurve` | Stateless unit-interval shaping curves (`linear`, `power`, `sigmoid`, `smoothstep`, `parabolicCrown`, `gaussian`, `logarithmic`, `logarithmicTaper`, `harmonic`), continuous distribution samplers (`samplePowerLaw`, `samplePowerLawIndex`, `sampleTruncatedNormal`, `variantSeed`), and polar-spiral / multi-harmonic cross-section geometry (`spiralOffset`, `harmonicRadius`, `annularRingRadius`) shared between spatial region selectors and procedural worldgen plugins (`leaf-treegen`). |

```java
// Example: Fair per-player & per-world budget allocation with WorkAttributionLedger + WaterFillingAllocator
WorkAttributionLedger ledger = new WorkAttributionLedger();
long nowNanos = System.nanoTime();

// Record that player `actorId` triggered 180 microseconds of work in "world_nether":
ledger.recordConsumption("world_nether", actorId, 180_000.0, nowNanos);
ledger.recordActorCharge(actorId, 180_000L, nowNanos);

// Weight each world by its distinct active player count (n_b >= 1):
Map<String, Double> demandsMs = Map.of("world", 3.0, "world_nether", 3.0);
Map<String, Integer> weights = Map.of(
        "world", ledger.distinctConsumers("world", nowNanos),
        "world_nether", ledger.distinctConsumers("world_nether", nowNanos));

Map<String, Double> allocatedMs = WaterFillingAllocator.allocateRates(demandsMs, weights, 4.0);
double fairness = WaterFillingAllocator.jainsFairnessIndex(allocatedMs.values());
```

---

## 5. Day-0 Carrier Qualification & Whole-Server Fuzzing Reuse

When you build a plugin or mod using this repository's patterns, you can reuse its verification and support scripts directly against your own `.jar` without writing custom test harnesses.

### Target-Free Whole-Server Discovery Fuzzing (`scripts/bench_fuzz.py --mode server`)

Governed by [ADR-125](adr/ADR-125-whole-server-discovery-fuzzing.md) and documented in [`docs/dev/WHOLE_SERVER_FUZZING.md`](dev/WHOLE_SERVER_FUZZING.md), `scripts/bench_fuzz.py --mode server` fuzzes **any** Paper, Fabric, or NeoForge plugin/mod jar dropped into a disposable bench server - with zero hardcoded plugin names, commands, or package prefixes:

1. **Protocol Command Discovery (`declare_commands`):** Headless Mineflayer bots (`devstack/clients/bench-swarm.js`) log in under `default` and `op` permission tiers, capture the live Brigadier `declare_commands` packet, fold aliases, apply the safety denylist (`DEFAULT_DENYLIST`), and sample boundary-ladder arguments (`min`, `-1`, `0`, `1`, `16`, `max`) plus tab completions.
2. **Tier-Stratified UCB1 Bandit & Player Archetypes:** Explores `(command node, follow-up shape)` pairs including rapid quit-during-warmup (`quit_lt1s`, `quit_delay`), concurrent execution (`concurrent`), inventory clicks (`window_click`), and realistic player-taxonomy flows (`homesteader_reroll`, `explorer_wander`, `coop_group`).
3. **Agentless JVM Leak & Stall Oracles (`jcmd`):** Collects post-GC class histograms (`jcmd <pid> GC.class_histogram`) and main-thread stack dumps (`jcmd <pid> Thread.print`) compared against a same-seed vanilla baseline.
4. **Automated Shrinking & Delta-Debugging Attribution (`ddmin`):** When a memory leak slope or main-thread stall reproduces (`k` of `n` replays), the fuzzer shrinks the bot action sequence, bisects numeric arguments to find the exact failing threshold, and runs dependency-closed delta debugging (`ddmin_jars`) over `plugins/` or `mods/` to isolate the minimal culprit jar set.

```bash
# 1. Create a disposable bench copy of your Paper, Fabric, or NeoForge server containing your plugin jar:
python3 scripts/bench_setup.py /path/to/template-server /tmp/bench-myplugin --port 25566 --rcon-port 25576

# 2. Run a read-only passive audit (captures commands.json + offline JarIndex inventory):
python3 scripts/bench_fuzz.py --mode server \
    --profile paper \
    --server-dir /tmp/bench-myplugin \
    --host 127.0.0.1 \
    --mc-host 127.0.0.1 \
    --mc-port 25566 \
    --tiers default

# 3. Run active discovery fuzzing (baseline + bandit exploration + jcmd leak/stall screening + jar ddmin):
python3 scripts/bench_fuzz.py --mode server --active \
    --profile paper \
    --server-dir /tmp/bench-myplugin \
    --host 127.0.0.1 \
    --mc-host 127.0.0.1 \
    --mc-port 25566 \
    --tiers default,op \
    --episodes 40
```

### Additional Reusable Repository Tooling (`scripts/*` & `devstack/*`)

- **Zero-Backend Interactive Web Config Editor (`scripts/generate_web_editor.py`):** Generates a static HTML/JS configuration editor directly from the `@type`, `@options`, `@range`, and `@pattern` comment directives in your YAML files.
- **Single-Source Storefront Builder (`scripts/release/build_front_pages.py`):** Compiles a single tagged Markdown source ([`docs/publishing/FRONT_PAGE.md`](publishing/FRONT_PAGE.md)) into platform-tailored pages for Modrinth, Hangar, SpigotMC (BBCode), CurseForge, and BuiltByBit.
- **Multi-Server Docker Devstack (`devstack/`):** Scriptable containers and headless Mineflayer acceptance runners (`devstack/run-acceptance.sh`) for validating cross-server Velocity/Redis/MySQL topologies.
