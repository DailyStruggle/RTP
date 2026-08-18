package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.effectsapi.common.EffectsGroupKeys;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
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
      RTP.log(Level.INFO, "&#00FFFF[RTP] loaded " + name);
    }
  }

  /**
   * Get a configuration parser by its enum class
   *
   * @param <T> the type of the enum
   * @param parserEnumClass the enum class
   * @return the configuration parser, or null if not found
   */
  /**
   * Generic value shortcut: resolve configured value for any config enum key (ADR-071).
   *
   * @param key the config enum constant identifying the owning parser
   * @param def the fallback value
   * @return the resolved value, or {@code def}
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public Object getConfigValue(Enum<?> key, Object def) {
    if (key == null) return def;
    FactoryValue<?> fv = getParser((Class) key.getDeclaringClass());
    if (fv instanceof ConfigParser<?> cp) {
      return ((ConfigParser) cp).getConfigValue((Enum) key, def);
    }
    return def;
  }

  public <T extends Enum<T>> FactoryValue<T> getParser(Class<T> parserEnumClass) {
    if (configParserMap.containsKey(parserEnumClass))
      return (FactoryValue<T>) configParserMap.get(parserEnumClass);
    if (multiConfigParserMap.containsKey(parserEnumClass))
      return (FactoryValue<T>) multiConfigParserMap.get(parserEnumClass);
    return null;
  }

  /**
   * Resolve database settings map (ADR-071: split from config.yml into database.yml).
   *
   * @return the {@code database:} settings map; never {@code null}
   */
  public Map<String, Object> getDatabaseConfig() {
    Map<String, Object> newMap = new HashMap<>();
    FactoryValue<DatabaseKeys> fv = getParser(DatabaseKeys.class);
    if (fv instanceof ConfigParser<DatabaseKeys> parser) {
      newMap = parser.getMap(DatabaseKeys.database);
    }

    // ADR-071: read-legacy -> apply-new -> warn-once migration.
    Map<String, Object> legacy = readLegacyConfigSection("database");
    if (legacy.isEmpty()) {
      return newMap;
    }
    Map<String, Object> merged =
        mergeLegacyConfig(newMap, legacy, readBundledDatabaseDefaults());
    warnLegacyDatabaseOnce();
    return merged;
  }

  // ADR-071: warn-once latches for deprecation logging per JVM.
  private static volatile boolean warnedLegacyDatabase = false;
  private static volatile boolean warnedLegacyNetworkRedis = false;

  /**
   * Merges a deprecated legacy config block into the new map (ADR-071).
   * Legacy values apply only where the new map still holds bundled defaults.
   *
   * @param newMap authoritative new-file settings (never {@code null})
   * @param legacy deprecated legacy block read from config.yml
   * @param defaults bundled defaults of the new file
   * @return merged settings map
   */
  static Map<String, Object> mergeLegacyConfig(
      Map<String, Object> newMap, Map<String, Object> legacy, Map<String, Object> defaults) {
    Map<String, Object> merged = new LinkedHashMap<>(newMap);
    for (Map.Entry<String, Object> e : legacy.entrySet()) {
      Object current = merged.get(e.getKey());
      Object def = defaults.get(e.getKey());
      boolean newIsDefault =
          current == null || Objects.equals(String.valueOf(current), String.valueOf(def));
      if (newIsDefault) {
        merged.put(e.getKey(), e.getValue());
      }
    }
    return merged;
  }

  /**
   * Reads a top-level section from the on-disk {@code config.yml} using the in-house
   * YAML reader (runtime SnakeYAML is not bundled). Returns an empty map when the
   * file or section is absent or unreadable.
   *
   * @param sectionKey the top-level section name
   * @return the section as a flat map; never {@code null}
   */
  Map<String, Object> readLegacyConfigSection(String sectionKey) {
    File configFile = new File(pluginDirectory, "config.yml");
    if (!configFile.exists()) return new HashMap<>();
    try {
      RtpYamlConfig yaml = RtpYamlConfig.load(configFile);
      RtpYamlSection section = yaml.getConfigurationSection(sectionKey);
      if (section == null) return new HashMap<>();
      return section.getMapValues(false);
    } catch (Exception e) {
      RTP.log(Level.FINER,
          "[RTP] readLegacyConfigSection(" + sectionKey + ") failed: " + e.getMessage());
      return new HashMap<>();
    }
  }

  /**
   * Reads the legacy nested {@code network.redis} block from the on-disk
   * {@code config.yml}. Returns an empty map when absent.
   *
   * @return the {@code network.redis} settings; never {@code null}
   */
  Map<String, Object> readLegacyNetworkRedis() {
    File configFile = new File(pluginDirectory, "config.yml");
    if (!configFile.exists()) return new HashMap<>();
    try {
      RtpYamlConfig yaml = RtpYamlConfig.load(configFile);
      RtpYamlSection network = yaml.getConfigurationSection("network");
      if (network == null) return new HashMap<>();
      RtpYamlSection redis = network.getConfigurationSection("redis");
      if (redis == null) return new HashMap<>();
      return redis.getMapValues(false);
    } catch (Exception e) {
      RTP.log(Level.FINER, "[RTP] readLegacyNetworkRedis() failed: " + e.getMessage());
      return new HashMap<>();
    }
  }

  // ADR-071 rule 4: whole-file relocations into advanced/.
  private static final java.util.Set<String> warnedLegacyRootFiles =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * Vacates a legacy config file or directory by renaming to {@code <name>.migrated}.
   * Uses {@link java.nio.file.Files#move} to avoid silent rename failures (ADR-071/ADR-076).
   *
   * @param legacy legacy file/directory to vacate
   * @param legacyName base name of legacy path
   * @return {@code true} when the legacy path no longer exists after the call
   */
  private boolean archiveLegacy(File legacy, String legacyName) {
    File archived = new File(pluginDirectory, legacyName + ".migrated");
    for (int i = 1; archived.exists() && i < 10000; i++) {
      archived = new File(pluginDirectory, legacyName + ".migrated." + i);
    }
    try {
      java.nio.file.Files.move(legacy.toPath(), archived.toPath());
      return true;
    } catch (Exception moveEx) {
      RTP.log(Level.FINER,
          "[RTP] archiveLegacy(" + legacyName + ") could not archive legacy path: "
              + moveEx.getMessage());
    }
    // Last resort: remove the legacy path so it cannot be re-migrated on the next boot.
    try {
      java.nio.file.Files.deleteIfExists(legacy.toPath());
    } catch (Exception delEx) {
      RTP.log(Level.FINER,
          "[RTP] archiveLegacy(" + legacyName + ") could not delete legacy path: "
              + delEx.getMessage());
    }
    return !legacy.exists();
  }

  /**
   * Migrates a whole config file relocated into a subdirectory (ADR-071).
   * Customized values are folded into the new parser, and the legacy file is archived.
   *
   * @param parser the relocated parser (authoritative new location)
   * @param legacyFileName the legacy root file name to migrate from
   */
  void migrateLegacyRootConfig(ConfigParser<?> parser, String legacyFileName) {
    File legacy = new File(pluginDirectory, legacyFileName);
    if (!legacy.exists() || !legacy.isFile()) return;
    try {
      RtpYamlConfig yaml = RtpYamlConfig.load(legacy);
      Map<String, Object> legacyMap = yaml.getMapValues(false);
      boolean changed = false;
      for (Map.Entry<String, Object> e : legacyMap.entrySet()) {
        if (e.getKey() == null || e.getKey().equalsIgnoreCase("version")) continue;
        try {
          parser.set(e.getKey(), e.getValue());
          changed = true;
        } catch (RuntimeException ignored) {
          // legacy key no longer recognized; drop it rather than fail the migration
        }
      }
      if (changed) {
        try {
          parser.save();
        } catch (Exception saveEx) {
          RTP.log(Level.FINER, "[RTP] migrateLegacyRootConfig(" + legacyFileName
              + ") save failed: " + saveEx.getMessage());
        }
      }
      archiveLegacy(legacy, legacyFileName);
      if (warnedLegacyRootFiles.add(legacyFileName)) {
        RTP.log(Level.WARNING, "[RTP] '" + legacyFileName + "' has moved to 'advanced/"
            + legacyFileName + "' (ADR-071). Your settings were migrated; the old file was "
            + "archived as '" + legacyFileName + ".migrated'.");
      }
    } catch (Exception ex) {
      RTP.log(Level.FINER,
          "[RTP] migrateLegacyRootConfig(" + legacyFileName + ") failed: " + ex.getMessage());
    }
  }

  // ADR-071 rule 4: split-key migrations warning latch per category.
  private static final java.util.Set<String> warnedLegacySplitKeys =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * Migrates subset of extracted keys while legacy file remains at original path (ADR-071).
   * Folds customized values into relocated parser without archiving source file.
   *
   * @param parser the relocated parser (authoritative new location)
   * @param legacySourceFile the file the keys were extracted from (left in place)
   * @param categoryLabel a short label for the warn-once latch / log message
   */
  void migrateLegacyKeysFromFile(ConfigParser<?> parser, String legacySourceFile, String categoryLabel) {
    File legacy = new File(pluginDirectory, legacySourceFile);
    if (!legacy.exists() || !legacy.isFile()) return;
    try {
      RtpYamlConfig yaml = RtpYamlConfig.load(legacy);
      Map<String, Object> legacyMap = yaml.getMapValues(false);
      boolean migrated = false;
      for (Map.Entry<String, Object> e : legacyMap.entrySet()) {
        if (e.getKey() == null || e.getKey().equalsIgnoreCase("version")) continue;
        try {
          // parser.set rejects keys the relocated enum does not own; only the
          // moved keys (e.g. airBlocks) are honored here.
          parser.set(e.getKey(), e.getValue());
          migrated = true;
        } catch (RuntimeException ignored) {
          // key not owned by this parser; skip
        }
      }
      if (migrated) {
        try {
          parser.save();
        } catch (Exception saveEx) {
          RTP.log(Level.FINER, "[RTP] migrateLegacyKeysFromFile(" + categoryLabel
              + ") save failed: " + saveEx.getMessage());
        }
        if (warnedLegacySplitKeys.add(categoryLabel)) {
          RTP.log(Level.WARNING, "[RTP] the '" + categoryLabel + "' settings have moved out of "
              + "'" + legacySourceFile + "' into 'advanced/" + categoryLabel + ".yml' (ADR-071). "
              + "Your customized values were migrated; you may remove them from '"
              + legacySourceFile + "'.");
        }
      }
    } catch (Exception ex) {
      RTP.log(Level.FINER,
          "[RTP] migrateLegacyKeysFromFile(" + categoryLabel + ") failed: " + ex.getMessage());
    }
  }

  /** ADR-071 rule 4: one-time latch for legacy flat messages.yml deprecation log. */
  private static volatile boolean warnedLegacyFlatMessages = false;

  /**
   * Migrates legacy flat {@code messages.yml} into {@code messages/} files (ADR-071).
   * Routes top-level keys to owning parsers, saves files, and archives legacy file.
   *
   * @param parserMap parser map with message parsers registered
   */
  void migrateLegacyFlatMessages(Map<Class<?>, ConfigParser<?>> parserMap) {
    File legacy = new File(pluginDirectory, "messages.yml");
    if (!legacy.exists() || !legacy.isFile()) return;
    try {
      RtpYamlConfig yaml = RtpYamlConfig.load(legacy);
      Map<String, Object> legacyMap = yaml.getMapValues(false);
      java.util.Set<Class<?>> touched = new java.util.HashSet<>();
      for (Map.Entry<String, Object> e : legacyMap.entrySet()) {
        String key = e.getKey();
        if (key == null || key.equalsIgnoreCase("version")) continue;
        Enum<?> mk = Messages.byName(key);
        if (mk == null) continue; // key no longer recognized; drop rather than fail
        Class<?> owner = mk.getDeclaringClass();
        ConfigParser<?> parser = parserMap.get(owner);
        if (parser == null) continue;
        try {
          parser.set(key, e.getValue());
          touched.add(owner);
        } catch (RuntimeException ignored) {
          // unrecognized key; skip
        }
      }
      for (Class<?> owner : touched) {
        try {
          parserMap.get(owner).save();
        } catch (Exception saveEx) {
          RTP.log(Level.FINER, "[RTP] migrateLegacyFlatMessages() save failed for "
              + owner.getSimpleName() + ": " + saveEx.getMessage());
        }
      }
      archiveLegacy(legacy, "messages.yml");
      if (!warnedLegacyFlatMessages) {
        warnedLegacyFlatMessages = true;
        RTP.log(Level.WARNING, "[RTP] the flat 'messages.yml' has been split into the 'messages/' "
            + "directory (ADR-071). Your customized strings were migrated; the old file was "
            + "archived as 'messages.yml.migrated'.");
      }
    } catch (Exception ex) {
      RTP.log(Level.FINER, "[RTP] migrateLegacyFlatMessages() failed: " + ex.getMessage());
    }
  }

  // ADR-076 rule 4: MultiConfigParser relocations warning latch.
  private static final java.util.Set<String> warnedLegacyMultiDirs =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * Relocates legacy MultiConfigParser folder (e.g. {@code regions/}) to {@code definitions/} (ADR-076).
   * Moves {@code .yml} definitions, archives empty legacy directory, and logs deprecation notice.
   *
   * @param legacyDir legacy top-level directory name
   * @param newDir new directory path under {@code definitions/}
   */
  void migrateLegacyMultiDir(String legacyDir, String newDir) {
    File legacy = new File(pluginDirectory, legacyDir);
    if (!legacy.exists() || !legacy.isDirectory()) return;
    File target = new File(pluginDirectory, newDir.replace('/', File.separatorChar));
    try {
      if (!target.exists() && !target.mkdirs()) {
        RTP.log(Level.FINER,
            "[RTP] migrateLegacyMultiDir(" + legacyDir + ") could not create '" + newDir + "'");
        return;
      }
      File[] files = legacy.listFiles();
      boolean movedAny = false;
      if (files != null) {
        for (File f : files) {
          if (!f.isFile()) continue;
          File dest = new File(target, f.getName());
          if (dest.exists()) continue; // never clobber a newer copy at the target
          try {
            java.nio.file.Files.move(f.toPath(), dest.toPath());
            movedAny = true;
          } catch (Exception moveEx) {
            RTP.log(Level.FINER, "[RTP] migrateLegacyMultiDir(" + legacyDir + ") move of '"
                + f.getName() + "' failed: " + moveEx.getMessage());
          }
        }
      }
      // Archive the legacy directory so a second run does not re-trigger the migration.
      File[] remaining = legacy.listFiles();
      if (remaining == null || remaining.length == 0) {
        archiveLegacy(legacy, legacyDir);
      }
      if (movedAny && warnedLegacyMultiDirs.add(legacyDir)) {
        RTP.log(Level.WARNING, "[RTP] the '" + legacyDir + "/' folder has moved to '"
            + newDir + "/' (ADR-076). Your files were migrated; the old folder was archived as '"
            + legacyDir + ".migrated'.");
      }
    } catch (Exception ex) {
      RTP.log(Level.FINER,
          "[RTP] migrateLegacyMultiDir(" + legacyDir + ") failed: " + ex.getMessage());
    }
  }

  /**
   * Reads the bundled {@code database.yml} {@code database} defaults from the JAR
   * resource. Returns an empty map for assemblies that ship no database.yml (lite).
   *
   * @return the bundled default settings; never {@code null}
   */
  private Map<String, Object> readBundledDatabaseDefaults() {
    try (java.io.InputStream in =
        RTP.class.getClassLoader().getResourceAsStream("advanced/database.yml")) {
      if (in == null) return new HashMap<>();
      RtpYamlConfig yaml = new RtpYamlConfig();
      yaml.loadConfiguration(in, true);
      RtpYamlSection section = yaml.getConfigurationSection("database");
      if (section == null) return new HashMap<>();
      return section.getMapValues(false);
    } catch (Exception e) {
      return new HashMap<>();
    }
  }

  private void warnLegacyDatabaseOnce() {
    if (warnedLegacyDatabase) return;
    warnedLegacyDatabase = true;
    RTP.log(Level.WARNING,
        "[RTP] config.yml's 'database' block is deprecated and has moved to database.yml "
            + "(ADR-071). Your existing values are still honored, but database.yml takes "
            + "precedence; please move your settings into database.yml and remove the "
            + "'database' block from config.yml.");
  }

  /**
   * Emits a one-time deprecation warning when a legacy {@code network.redis} block is
   * still present in {@code config.yml} (ADR-071 rule 5). The authoritative transport
   * surface is network.yml; the legacy block carries no runtime effect.
   */
  public void warnLegacyNetworkRedisOnce() {
    if (warnedLegacyNetworkRedis) return;
    if (readLegacyNetworkRedis().isEmpty()) return;
    warnedLegacyNetworkRedis = true;
    RTP.log(Level.WARNING,
        "[RTP] config.yml's 'network.redis' block is deprecated and has moved to network.yml "
            + "(ADR-071); the network.yml transport settings are authoritative. Please remove "
            + "the redundant 'network.redis' block from config.yml.");
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
              multiConfigParser.fileDatabase,
              multiConfigParser.locale));
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
              multiConfigParser.fileDatabase,
              multiConfigParser.locale));
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

  /**
   * Reloads all configuration parsers from disk, replacing the current parser maps atomically.
   * Cancels any in-flight teleports and kills the scan task before reloading.
   */
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

    // ADR-071: logging.yml is advanced tuning, relocated under advanced/. A legacy
    // root logging.yml left by an older install is migrated and archived (rule 4).
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/logging.yml");
    ConfigParser<LoggingKeys> logging =
            new ConfigParser<>(LoggingKeys.class, "advanced/logging.yml", "1.1", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(LoggingKeys.class, logging);
    migrateLegacyRootConfig(logging, "logging.yml");

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser config.yml");
    ConfigParser<ConfigKeys> config =
            new ConfigParser<>(ConfigKeys.class, "config.yml", "3.1", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(ConfigKeys.class, config);

    // ADR-071: database/persistence settings live in their own advanced/database.yml,
    // split out of config.yml so config.yml is purely teleport behavior, and tiered
    // under advanced/ as a backend-tuning file. The parser is lite-tolerant: the
    // rtp-lite assembly (ADR-024) ships no database.yml resource, so register it only
    // when the bundled resource (or an on-disk copy) is present. When absent, the
    // database handlers fall back to the yaml/flat-file default. A legacy root
    // database.yml left by an older install is migrated and archived (rule 4).
    boolean databaseResourcePresent =
            RTP.class.getClassLoader().getResource("advanced/database.yml") != null
                    || new File(pluginDirectory, "advanced" + File.separator + "database.yml").exists()
                    || new File(pluginDirectory, "database.yml").exists();
    if (databaseResourcePresent) {
      RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/database.yml");
      ConfigParser<DatabaseKeys> database =
              new ConfigParser<>(DatabaseKeys.class, "advanced/database.yml", "1.1", pluginDirectory, fileDatabase, locale);
      newConfigParserMap.put(DatabaseKeys.class, database);
      migrateLegacyRootConfig(database, "database.yml");
    } else {
      RTP.log(Level.FINER, "[RTP] reloadConfigs(): database.yml resource absent (lite); skipping parser");
    }

    // ADR-071 rule 3: messages.yml is split, per concern, into a messages/
    // directory, and the former monolithic MessagesKeys enum into one small enum
    // per file. Each message file is an ordinary subpathed single-file parser
    // (one enum, one file, one parser) - no merge-loader.
    // ADR-076: that messages/ directory is relocated under the advanced/ door. A
    // legacy root messages/ folder left by an older (ADR-071) install is relocated
    // first so an operator's customized per-concern files are not stranded (rule 4).
    migrateLegacyMultiDir("messages", "advanced/messages");
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/messages/placeholders.yml");
    newConfigParserMap.put(PlaceholderMessages.class,
            new ConfigParser<>(PlaceholderMessages.class, "advanced/messages/placeholders.yml", "1.0", pluginDirectory, fileDatabase, locale));
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/messages/player.yml");
    newConfigParserMap.put(PlayerMessages.class,
            new ConfigParser<>(PlayerMessages.class, "advanced/messages/player.yml", "1.0", pluginDirectory, fileDatabase, locale));
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/messages/network.yml");
    newConfigParserMap.put(NetworkMessages.class,
            new ConfigParser<>(NetworkMessages.class, "advanced/messages/network.yml", "1.0", pluginDirectory, fileDatabase, locale));
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/messages/commands.yml");
    newConfigParserMap.put(CommandMessages.class,
            new ConfigParser<>(CommandMessages.class, "advanced/messages/commands.yml", "1.0", pluginDirectory, fileDatabase, locale));
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/messages/system.yml");
    newConfigParserMap.put(SystemMessages.class,
            new ConfigParser<>(SystemMessages.class, "advanced/messages/system.yml", "1.1", pluginDirectory, fileDatabase, locale));
    // ADR-071 rule 4: a legacy flat messages.yml left by an older install is read
    // once, its operator customizations folded into the matching per-concern
    // files, archived (renamed, not deleted), and logged.
    migrateLegacyFlatMessages(newConfigParserMap);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser economy.yml");
    ConfigParser<EconomyKeys> economy =
            new ConfigParser<>(EconomyKeys.class, "economy.yml", "1.1", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(EconomyKeys.class, economy);

    // ADR-071: performance.yml is advanced tuning, relocated under advanced/. A legacy
    // root performance.yml left by an older install is migrated and archived (rule 4).
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/performance.yml");
    ConfigParser<PerformanceKeys> performance =
            new ConfigParser<>(PerformanceKeys.class, "advanced/performance.yml", "1.1", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(PerformanceKeys.class, performance);
    migrateLegacyRootConfig(performance, "performance.yml");

    // ADR-071: metrics.yml is advanced tuning, relocated under advanced/. A legacy
    // root metrics.yml left by an older install is migrated and archived (rule 4).
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/metrics.yml");
    ConfigParser<MetricsKeys> metrics =
            new ConfigParser<>(MetricsKeys.class, "advanced/metrics.yml", "1.1", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(MetricsKeys.class, metrics);
    migrateLegacyRootConfig(metrics, "metrics.yml");

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser safety.yml");
    ConfigParser<SafetyKeys> safety =
            new ConfigParser<>(SafetyKeys.class, "safety", "1.1", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(SafetyKeys.class, safety);

    // ADR-071: the landing block lists (airBlocks / unsafeBlocks) and the biome
    // filter (biomeWhitelist / biomes) are split out of safety.yml into advanced/
    // tier files, each its own enum + file + parser. safety.yml keeps only the
    // high-touch safety knobs.
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/blocks.yml");
    ConfigParser<BlocksKeys> blocks =
            new ConfigParser<>(BlocksKeys.class, "advanced/blocks.yml", "1.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(BlocksKeys.class, blocks);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building parser advanced/biomes.yml");
    ConfigParser<BiomesKeys> biomes =
            new ConfigParser<>(BiomesKeys.class, "advanced/biomes.yml", "1.0", pluginDirectory, fileDatabase, locale);
    newConfigParserMap.put(BiomesKeys.class, biomes);

    // ADR-071 rule 4: an older install carries airBlocks/unsafeBlocks and
    // biomeWhitelist/biomes inside the root safety.yml. Fold any operator
    // customizations into the new parsers before the safety parser rewrites
    // safety.yml on its version bump (which would otherwise drop them), warn
    // once, and leave safety.yml in place (it still owns the remaining knobs).
    migrateLegacyKeysFromFile(blocks, "safety.yml", "blocks");
    migrateLegacyKeysFromFile(biomes, "safety.yml", "biomes");

    // Resolve #namespace:tag tokens (e.g. #minecraft:leaves) in airBlocks /
    // unsafeBlocks now, while we have the parser in hand and before any
    // probe / scan / live isAir / isSafe call can observe the raw form.
    // Logs the resolved lists at INFO for operator-visible debugging of
    // "placed on leaves / water" reports. See SafetyTokenExpander javadoc.
    SafetyTokenExpander.expandAndApply(blocks);

    // ADR-076: the region/world/effect definition folders are grouped under
    // definitions/. The kind string stays "regions"/"worlds"/"effects" (so the menu,
    // reload and removal-guards resolve unchanged); only the on-disk directory moves.
    // A legacy root regions/ folder left by an older install is relocated first so an
    // operator's authored region files are not stranded (rule 4).
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building MultiConfigParser definitions/regions/*.yml");
    migrateLegacyMultiDir("regions", "definitions/regions");
    // The shared shape/vertical-adjustor rename-map catalogs now live hidden inside the
    // regions folder (definitions/regions/.shape and definitions/regions/.vert). Relocate
    // any older definitions/shape and definitions/vert dotfiles so an operator's authored
    // catalog translations are not stranded on upgrade.
    migrateLegacyMultiDir("definitions/shape", "definitions/regions/.shape");
    migrateLegacyMultiDir("definitions/vert", "definitions/regions/.vert");
    MultiConfigParser<RegionKeys> regions =
            new MultiConfigParser<>(RegionKeys.class, "regions", "1.1", pluginDirectory, "definitions/regions", locale);
    newMultiConfigParserMap.put(RegionKeys.class, regions);

    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building MultiConfigParser definitions/worlds/*.yml");
    migrateLegacyMultiDir("worlds", "definitions/worlds");
    MultiConfigParser<WorldKeys> worlds =
            new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", pluginDirectory, "definitions/worlds", locale);
    newMultiConfigParserMap.put(WorldKeys.class, worlds);

    // effects-api-ADR-005: declarative effect groups under <pluginDir>/effects/<group>.yml.
    // Each per-group file uses the EffectsGroupKeys schema (when / permission / players /
    // inherit / effects). Outer keys (group names) are admin-chosen, hence MultiConfigParser
    // (one ConfigParser per file). EffectsResolver in rtp-plugin reads this on every
    // teleport so /rtp reload is honored automatically by the parser-map atomic swap above.
    RTP.log(Level.FINER, "[RTP] reloadConfigs(): building MultiConfigParser definitions/effects/*.yml");
    migrateLegacyMultiDir("effects", "definitions/effects");
    MultiConfigParser<EffectsGroupKeys> effectsGroups =
            new MultiConfigParser<>(EffectsGroupKeys.class, "effects", "1.0", pluginDirectory, "definitions/effects", locale);
    newMultiConfigParserMap.put(EffectsGroupKeys.class, effectsGroups);

    // ADR-076: region arrival schematics (.schem files, resolved by file presence -
    // see RegionSchematicService) live under advanced/schematics/. A legacy root
    // schematics/ folder left by an older install is relocated (rule 4). There is no
    // parser for schematics; this is a pure directory relocation.
    migrateLegacyMultiDir("schematics", "advanced/schematics");

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
    // ADR-071 rule 5: surface a one-time deprecation warning if the operator still
    // carries the redundant network.redis block in config.yml (authoritative surface
    // is network.yml).
    warnLegacyNetworkRedisOnce();
    RTP.log(Level.FINE, "[RTP] reloadConfigs(): complete (in-flight tasks retain old snapshots)");
  }

  /**
   * Reloads all regions from the current region config parsers.
   * Shuts down existing regions and re-instantiates them from disk.
   */
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

    // Lobby backends never serve teleports to local coords - they only
    // dispatch players to peer backends via cross-server /rtp. Registering the
    // default (or any) local region here would advertise a local destination via
    // BackendStateSampler.regions and let /rtp resolve a no-arg request to the
    // local region instead of routing it through the network. Skip the entire
    // per-region instantiation loop; permRegionLookup stays empty on a lobby.
    if (RTP.lobbyMode) {
      RTP.log(Level.FINE,
          "[RTP] reloadRegions(): lobbyMode=true, skipping local region registration");
      return;
    }

    int regionCount = 0;
    int dormantCount = 0;
    for (ConfigParser<RegionKeys> regionConfig : regions.configParserFactory.map.values()) {
      RTP.log(Level.FINER, "[RTP] reloadRegions(): loading RegionSettings from '" + regionConfig.name + "'");
      RegionSettings settings = RegionConfigLoader.load(regionConfig);
      String name = settings.name();
      regionCount++;

      // Detect "dormant region" - the configured world wasn't loaded yet (common when
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
                "&#00FFFF[RTP] [" + name + "] successfully created teleport region - " + region.name);
      }
      RTP.getInstance()
              .miscAsyncTasks
              .add(
                      new RTPRunnable(
                              () -> {
                                // Dormant regions (world not yet loaded) must not select a
                                // shape region - the shape's data file hasn't been loaded.
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

  /**
   * Performs a full reload: configs, then regions, then fires all registered
   * {@link #onReload} callbacks.
   */
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
