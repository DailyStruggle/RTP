package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.effectsapi.common.EffectsGroupKeys;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/** Main configuration manager for RTP */
public class Configs {
  private static final List<Runnable> onReload = new ArrayList<>();

  /** The plugin directory */
  public final File pluginDirectory;

  /** The file database */
  public final YamlFileDatabase fileDatabase;

  /** Map of configuration parsers */
  public Map<Class<?>, ConfigParser<?>> configParserMap = new ConcurrentHashMap<>();

  /** Map of multi-configuration parsers */
  public Map<Class<?>, MultiConfigParser<?>> multiConfigParserMap = new ConcurrentHashMap<>();


  /**
   * Constructor for Configs
   *
   * @param pluginDirectory the plugin directory
   */
  public Configs(File pluginDirectory) {
    RTP.configs = this;
    this.pluginDirectory = pluginDirectory;
    this.fileDatabase = new YamlFileDatabase(pluginDirectory);
    this.fileDatabase.connect();
    //        this.fileDatabase.disconnect( connect );
  }

  /**
   * Register a runnable to be executed on reload
   *
   * @param runnable the runnable
   */
  public static void onReload(Runnable runnable) {
    onReload.add(runnable);
  }

  /**
   * Register a configuration parser
   *
   * @param instance the configuration parser instance
   */
  public void putParser(Object instance) {
    if (instance == null) throw new NullPointerException("instance is null");

    ConfigParser<LoggingKeys> logging;
    String name;
    if (instance instanceof ConfigParser<?>) {
      name = ((ConfigParser<?>) instance).name;
      if (((ConfigParser<?>) instance).myClass.equals(LoggingKeys.class))
        logging = ((ConfigParser<LoggingKeys>) instance);
      else logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
      configParserMap.put(((ConfigParser<?>) instance).myClass, (ConfigParser<?>) instance);
    } else if (instance instanceof MultiConfigParser<?>) {
      logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
      name = ((MultiConfigParser<?>) instance).name;
      multiConfigParserMap.put(
          ((MultiConfigParser<?>) instance).myClass, (MultiConfigParser<?>) instance);
    } else
      throw new IllegalArgumentException(
          "invalid type:" + instance.getClass().getSimpleName() + ", expected a config parser");

    boolean detailed_reload = true;
    if (logging != null) {
      Object o = logging.getConfigValue(LoggingKeys.detailed_reload, false);
      if (o instanceof Boolean) {
        detailed_reload = (Boolean) o;
      } else {
        detailed_reload = Boolean.parseBoolean(o.toString());
      }
    }

    if (detailed_reload) {
      RTP.log(Level.INFO, "&00FFFF[RTP] loaded " + name);
    }
  }

  /**
   * Get a configuration parser by its enum class
   *
   * @param <T> the type of the enum
   * @param parserEnumClass the enum class
   * @return the configuration parser, or null if not found
   */
  public <T extends Enum<T>> FactoryValue<T> getParser(Class<T> parserEnumClass) {
    if (configParserMap.containsKey(parserEnumClass))
      return (FactoryValue<T>) configParserMap.get(parserEnumClass);
    if (multiConfigParserMap.containsKey(parserEnumClass))
      return (FactoryValue<T>) multiConfigParserMap.get(parserEnumClass);
    return null;
  }

  /**
   * Get the configuration parser for a specific world
   *
   * @param worldName the name of the world
   * @return the configuration parser, or null if the world is not registered
   */
  @Nullable
  public ConfigParser<WorldKeys> getWorldParser(String worldName) {
    if (RTP.serverAccessor.getRTPWorld(worldName) == null) {
      return null;
    }

    MultiConfigParser<WorldKeys> multiConfigParser =
        (MultiConfigParser<WorldKeys>) multiConfigParserMap.get(WorldKeys.class);

    Objects.requireNonNull(multiConfigParser);

    if (!multiConfigParser.configParserFactory.contains(worldName)) {
      multiConfigParser.addParser(
          new ConfigParser<>(
              WorldKeys.class,
              worldName,
              "1.0",
              multiConfigParser.myDirectory,
              multiConfigParser.langMap,
              multiConfigParser.fileDatabase));
    }

    ConfigParser<WorldKeys> parser = multiConfigParser.getParser(worldName);
    return parser;
  }

  /**
   * Get a configuration value for a specific world
   *
   * @param worldName the name of the world
   * @param key the configuration key
   * @return the configuration value
   */
  public Object getWorldParserValue(String worldName, WorldKeys key) {
    if (RTP.serverAccessor.getRTPWorld(worldName) == null) {
      return null;
    }

    MultiConfigParser<WorldKeys> multiConfigParser =
        (MultiConfigParser<WorldKeys>) multiConfigParserMap.get(WorldKeys.class);

    Objects.requireNonNull(multiConfigParser);

    if (!multiConfigParser.configParserFactory.contains(worldName)) {
      multiConfigParser.addParser(
          new ConfigParser<>(
              WorldKeys.class,
              worldName,
              "1.0",
              multiConfigParser.myDirectory,
              multiConfigParser.langMap,
              multiConfigParser.fileDatabase));
    }

    ConfigParser<WorldKeys> parser = multiConfigParser.getParser(worldName);
    return parser;
  }

  /**
   * Reload all configurations
   *
   * @return true if successful
   */
  public boolean reload() {
    RTP.log(Level.FINE, "[RTP] reload(): flushing fileDatabase queries before parser swap");
    this.fileDatabase.processQueries(Long.MAX_VALUE);
    RTP.log(Level.FINER, "[RTP] reload(): fileDatabase.processQueries complete; reconnecting");
    this.fileDatabase.connect();
    RTP.log(Level.FINER, "[RTP] reload(): fileDatabase reconnected; entering reloadAction");
    reloadAction();
    RTP.log(Level.FINE, "[RTP] reload(): complete");
    return true;
  }

  // 1. Isolate Configuration Parsing
  public void reloadConfigs() {
    RTP.log(Level.FINE, "[RTP] reloadConfigs(): killing ScanTask and clearing processingPlayers");
    ScanTask.kill();
    RTP.getInstance().processingPlayers.clear();

    int cancelled = 0;
    for (Map.Entry<UUID, TeleportData> e : RTP.getInstance().latestTeleportData.entrySet()) {
      TeleportData data = e.getValue();
      if (data == null || data.completed) continue;
      RTP.log(Level.FINER, "[RTP] reloadConfigs(): cancelling in-flight teleport for " + e.getKey());
      new RTPTeleportCancel(e.getKey()).run();
      cancelled++;
    }
    RTP.log(Level.FINE, "[RTP] reloadConfigs(): cancelled " + cancelled + " in-flight teleport(s)");

    Map<Class<?>, ConfigParser<?>> newConfigParserMap = new ConcurrentHashMap<>();
    Map<Class<?>, MultiConfigParser<?>> newMultiConfigParserMap = new ConcurrentHashMap<>();

    // REQ-RTP-F-013 / ADR-020: resolve the active locale BEFORE any other config is
    // loaded so that every parser can extract its locale-specific YAML directly.
    String locale = LanguageBootstrap.resolve(pluginDirectory);
    RTP.log(Level.FINE, "[RTP] reloadConfigs(): active locale resolved as '" + locale + "'");

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser logging.yml");
    ConfigParser<LoggingKeys> logging =
            new ConfigParser<>(LoggingKeys.class, "logging.yml", "1.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(LoggingKeys.class, logging);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser config.yml");
    ConfigParser<ConfigKeys> config =
            new ConfigParser<>(ConfigKeys.class, "config.yml", "3.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(ConfigKeys.class, config);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser messages.yml");
    ConfigParser<MessagesKeys> lang =
            new ConfigParser<>(MessagesKeys.class, "messages.yml", "1.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(MessagesKeys.class, lang);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser economy.yml");
    ConfigParser<EconomyKeys> economy =
            new ConfigParser<>(EconomyKeys.class, "economy.yml", "1.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(EconomyKeys.class, economy);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser performance.yml");
    ConfigParser<PerformanceKeys> performance =
            new ConfigParser<>(PerformanceKeys.class, "performance.yml", "1.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(PerformanceKeys.class, performance);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser metrics.yml");
    ConfigParser<MetricsKeys> metrics =
            new ConfigParser<>(MetricsKeys.class, "metrics.yml", "1.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(MetricsKeys.class, metrics);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser safety/*.yml");
    ConfigParser<SafetyKeys> safety =
            new ConfigParser<>(SafetyKeys.class, "safety", "1.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(SafetyKeys.class, safety);

    // Resolve #namespace:tag tokens (e.g. #minecraft:leaves) in airBlocks /
    // unsafeBlocks now, while we have the parser in hand and before any
    // probe / scan / live isAir / isSafe call can observe the raw form.
    // Logs the resolved lists at INFO for operator-visible debugging of
    // "placed on leaves / water" reports. See SafetyTokenExpander javadoc.
    SafetyTokenExpander.expandAndApply(safety);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building MultiConfigParser regions/*.yml");
    MultiConfigParser<RegionKeys> regions =
            new MultiConfigParser<>(RegionKeys.class, "regions", "1.0", pluginDirectory);
    newMultiConfigParserMap.put(RegionKeys.class, regions);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building MultiConfigParser worlds/*.yml");
    MultiConfigParser<WorldKeys> worlds =
            new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", pluginDirectory);
    newMultiConfigParserMap.put(WorldKeys.class, worlds);

    // effects-api-ADR-005: declarative effect groups under <pluginDir>/effects/<group>.yml.
    // Each per-group file uses the EffectsGroupKeys schema (when / permission / players /
    // inherit / effects). Outer keys (group names) are admin-chosen, hence MultiConfigParser
    // (one ConfigParser per file). EffectsResolver in rtp-plugin reads this on every
    // teleport so /rtp reload is honored automatically by the parser-map atomic swap above.
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building MultiConfigParser effects/*.yml");
    MultiConfigParser<EffectsGroupKeys> effectsGroups =
            new MultiConfigParser<>(EffectsGroupKeys.class, "effects", "1.0", pluginDirectory);
    newMultiConfigParserMap.put(EffectsGroupKeys.class, effectsGroups);

    int worldCount = 0;
    for (RTPWorld world : RTP.serverAccessor.getRTPWorlds()) {
      RTP.log(Level.FINER, "[RTP] reloadConfigs(): adding world parser '" + world.name() + "'");
      worlds.addParser(world.name());
      worldCount++;
    }
    RTP.log(Level.FINE, "[RTP] reloadConfigs(): " + worldCount + " world parser(s) registered");

    RTP.log(Level.FINE, "[RTP] reloadConfigs(): atomic swap of configParserMap/multiConfigParserMap");
    this.configParserMap = newConfigParserMap;
    this.multiConfigParserMap = newMultiConfigParserMap;
    // The logging parser is now visible via RTP.configs.getParser(LoggingKeys.class),
    // so the platform sink can resolve logging.yml#min_level. Replay any sub-INFO
    // records buffered during bootstrap so the configured threshold gates them
    // retroactively instead of leaking past the (previously unfiltered) sink.
    RTP.flushPendingLogs();
    RTP.log(Level.FINE, "[RTP] reloadConfigs(): complete (in-flight tasks retain old snapshots)");
  }

  // 2. Isolate Region Instantiation
  public void reloadRegions() {
    RTP.log(Level.FINE,
        "[RTP] reloadRegions(): shutting down " + RTP.selectionAPI.permRegionLookup.size()
            + " permanent and " + RTP.selectionAPI.tempRegions.size() + " temp region(s)");
    for (Region r : RTP.selectionAPI.permRegionLookup.values()) {
      RTP.log(Level.FINER, "[RTP] reloadRegions(): shutDown perm region '" + r.name + "'");
      r.shutDown();
    }
    RTP.selectionAPI.permRegionLookup.clear();

    for (Region r : RTP.selectionAPI.tempRegions.values()) {
      RTP.log(Level.FINER, "[RTP] reloadRegions(): shutDown temp region '" + r.name + "'");
      r.shutDown();
    }
    RTP.selectionAPI.tempRegions.clear();

    @SuppressWarnings("unchecked")
    MultiConfigParser<RegionKeys> regions =
            (MultiConfigParser<RegionKeys>) this.multiConfigParserMap.get(RegionKeys.class);
    if (regions == null) return;

    @SuppressWarnings("unchecked")
    ConfigParser<LoggingKeys> logging =
            (ConfigParser<LoggingKeys>) this.configParserMap.get(LoggingKeys.class);

    boolean detailed_region_init = true;
    if (logging != null) {
      Object o = logging.getConfigValue(LoggingKeys.detailed_region_init, false);
      if (o instanceof Boolean) {
        detailed_region_init = (Boolean) o;
      } else {
        detailed_region_init = Boolean.parseBoolean(o.toString());
      }
    }

    int regionCount = 0;
    int dormantCount = 0;
    for (ConfigParser<RegionKeys> regionConfig : regions.configParserFactory.map.values()) {
      RTP.log(Level.FINER, "[RTP] reloadRegions(): loading RegionSettings from '" + regionConfig.name + "'");
      RegionSettings settings = RegionConfigLoader.load(regionConfig);
      String name = settings.name();
      regionCount++;

      // Detect "dormant region" — the configured world wasn't loaded yet (common when
      // automatic world generators like Multiverse load their worlds after plugin enable).
      // The region is constructed with a null world; OnWorldLoadUnload rebinds the real
      // world via Region.rebindWorld once WorldLoadEvent fires.
      String configuredWorldName =
          RegionConfigLoader.detectFallbackConfiguredWorld(regionConfig, settings);
      boolean worldFallback = configuredWorldName != null;
      if (worldFallback) dormantCount++;
      RTP.log(Level.FINER,
          "[RTP] reloadRegions(): region '" + name + "' dormant=" + worldFallback
              + (worldFallback ? " configuredWorld='" + configuredWorldName + "'" : ""));
      if (worldFallback) {
        RTP.log(
            Level.INFO,
            "[RTP] Region '" + name + "' configured for world '" + configuredWorldName
                + "' which isn't loaded yet; region is dormant and will activate when the "
                + "world loads.");
      }

      Region region = new Region(name, settings, worldFallback, configuredWorldName);

      RTP.selectionAPI.permRegionLookup.put(region.name, region);
      if (detailed_region_init) {
        RTP.log(
                Level.INFO,
                "&00FFFF[RTP] [" + name + "] successfully created teleport region - " + region.name);
      }
      RTP.getInstance()
              .miscAsyncTasks
              .add(
                      new RTPRunnable(
                              () -> {
                                // Dormant regions (world not yet loaded) must not select a
                                // shape region — the shape's data file hasn't been loaded.
                                if (region.getWorld() == null) {
                                  RTP.log(Level.FINER,
                                      "[RTP] reloadRegions(): skipping shape.select for dormant region '"
                                          + region.name + "'");
                                  return;
                                }
                                if (region.getShape() == null) {
                                  RTP.log(Level.FINER,
                                      "[RTP] reloadRegions(): skipping shape.select for region '"
                                          + region.name + "' (null shape)");
                                  return;
                                }
                                RTP.log(Level.FINER,
                                    "[RTP] reloadRegions(): shape.select for region '" + region.name + "'");
                                region.getShape().select();
                              },
                              60));
    }
    RTP.log(Level.FINE,
        "[RTP] reloadRegions(): built " + regionCount + " region(s) (" + dormantCount + " dormant)");
  }

  // 3. Preserve original method for standard reload commands
  public void reloadAction() {
    RTP.log(Level.FINE, "[RTP] reloadAction(): begin");
    reloadConfigs();
    reloadRegions();
    if (!onReload.isEmpty()) {
      RTP.log(Level.FINE, "[RTP] reloadAction(): firing " + onReload.size() + " onReload callback(s)");
      for (Runnable r : onReload) {
        RTP.log(Level.FINER, "[RTP] reloadAction(): onReload callback " + r.getClass().getName());
        r.run();
      }
    }
    RTP.log(Level.FINE, "[RTP] reloadAction(): end");
  }
}
