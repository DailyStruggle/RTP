package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.*;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tools.ChunkyChecker;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** class to hold relevant API functions, outside of Bukkit functionality */
public class RTP {
  public static final ConcurrentLinkedQueue<CompletableFuture<?>> futures =
      new ConcurrentLinkedQueue<>();

  public static final SelectionAPI selectionAPI = new SelectionAPI();

  public static EnumMap<factoryNames, Factory<?>> factoryMap = new EnumMap<>(factoryNames.class);

  /**
   * minimum number of teleportations to executeAsyncTasks per gametick, to prevent bottlenecking
   * during lag spikes
   */
  public static int minRTPExecutions = 1;

  /** only one of each of these objects */
  public static Configs configs;

  public static RTPServerAccessor serverAccessor;
  public static RTPScheduler scheduler;
  public static RTPEconomy economy = null;
  public static TreeCommand baseCommand;
  public static AtomicBoolean reloading = new AtomicBoolean(false);

  /** only one instance will exist at a time, reset on plugin load */
  private static RTP instance;

  private static ScheduledExecutorService diagnosticTimer;

  static {
    Factory<Shape<?>> shapeFactory = new Factory<>();
    factoryMap.put(factoryNames.shape, shapeFactory);

    Factory<VerticalAdjustor<?>> verticalAdjustorFactory = new Factory<>();
    factoryMap.put(factoryNames.vert, verticalAdjustorFactory);
    factoryMap.put(factoryNames.singleConfig, new Factory<ConfigParser<?>>());
    factoryMap.put(factoryNames.multiConfig, new Factory<MultiConfigParser<?>>());
  }

  public final ConcurrentHashMap<UUID, TeleportData> priorTeleportData = new ConcurrentHashMap<>();
  public final ConcurrentHashMap<UUID, TeleportData> latestTeleportData = new ConcurrentHashMap<>();
  public final ConcurrentSkipListSet<UUID> processingPlayers = new ConcurrentSkipListSet<>();
  public RTPTaskPipe miscSyncTasks;
  public RTPTaskPipe miscAsyncTasks;
  public RTPTaskPipe startupTasks;
  public RTPTaskPipe cancelTasks;
  public final Map<String, FillTask> fillTasks = new ConcurrentHashMap<>();
  public final ConcurrentHashMap<UUID, Long> invulnerablePlayers = new ConcurrentHashMap<>();
  public final ConcurrentLinkedQueue<RTPChunk<?>> chunksToUnload = new ConcurrentLinkedQueue<>();
  public DatabaseAccessor<?> databaseAccessor;
  public io.github.dailystruggle.rtp.common.network.RedisManager redisManager;

  public RTP() {
    if (serverAccessor == null) throw new IllegalStateException("null serverAccessor");
    if (scheduler == null) throw new IllegalStateException("null scheduler");

    miscSyncTasks = (RTPTaskPipe) serverAccessor.createTaskPipe();
    miscAsyncTasks = (RTPTaskPipe) serverAccessor.createTaskPipe();
    startupTasks = (RTPTaskPipe) serverAccessor.createTaskPipe();
    cancelTasks = (RTPTaskPipe) serverAccessor.createTaskPipe();
    if (cancelTasks instanceof TimeBoundTaskPipe timeBoundTaskPipe) {
      timeBoundTaskPipe.setAvailableTime(Long.MAX_VALUE);
    }
    RTPAPI.serverAccessor = serverAccessor;
    instance = this;

    RTPAPI.addShape(new Circle());
    RTPAPI.addShape(new Square());
    RTPAPI.addShape(new Rectangle());
    RTPAPI.addShape(new Circle_Normal());
    RTPAPI.addShape(new Square_Normal());
    new LinearAdjustor(new ArrayList<>()); // todo: make this work
    new JumpAdjustor(new ArrayList<>());

    configs = new Configs(serverAccessor.getPluginDirectory());

    startupTasks.add(new RTPRunnable(() -> {
      ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) configs.getParser(ConfigKeys.class);
      if (configParser == null) return;

      Map<String, Object> networkMap = configParser.getMap(ConfigKeys.network);
      Object redisObj = networkMap.get("redis");
      if (redisObj instanceof Map<?, ?> redisMap) {
        Object enabledObj = redisMap.get("enabled");
        boolean enabled = Boolean.parseBoolean(String.valueOf(enabledObj != null ? enabledObj : "false"));
        if (enabled) {
          Object hostObj = redisMap.get("host");
          String host = String.valueOf(hostObj != null ? hostObj : "127.0.0.1");
          Object portObj = redisMap.get("port");
          int port = portObj instanceof Number ? ((Number) portObj).intValue() : 6379;
          Object passwordObj = redisMap.get("password");
          String password = String.valueOf(passwordObj != null ? passwordObj : "");
          this.redisManager = new io.github.dailystruggle.rtp.common.network.RedisManager(host, port, password);
          this.redisManager.initializeAsync();
        }
      } else if (redisObj instanceof ConfigurationSection redisSection) {
        boolean enabled = redisSection.getBoolean("enabled", false);
        if (enabled) {
          String host = redisSection.getString("host", "127.0.0.1");
          int port = redisSection.getInt("port", 6379);
          String password = redisSection.getString("password", "");
          this.redisManager = new io.github.dailystruggle.rtp.common.network.RedisManager(host, port, password);
          this.redisManager.initializeAsync();
        }
      }
    }, 10));

    ChunkyChecker.loadChunky();

    diagnosticTimer = Executors.newSingleThreadScheduledExecutor();
    diagnosticTimer.scheduleAtFixedRate(
        io.github.dailystruggle.rtp.common.tools.MemoryTracker::runDiagnostics,
        60,
        60,
        TimeUnit.SECONDS);

    io.github.dailystruggle.rtp.common.tools.PerformanceTracker.start(scheduler);
    scheduler.runTaskTimerAsynchronously(
        () -> {
          if (databaseAccessor != null) databaseAccessor.flushDirtyCache();
        },
        6000,
        6000);
    scheduler.runTaskTimerAsynchronously(
        () -> {
          if (databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDatabaseAccessor) {
            sqlDatabaseAccessor.flush();
          }
        },
        60,
        60);
  }

  public static void handleMigration(String previousState, String currentState) {
    if (previousState.equalsIgnoreCase("yaml") &&
        (currentState.equalsIgnoreCase("sqlite") ||
            currentState.equalsIgnoreCase("h2") ||
            currentState.equalsIgnoreCase("mysql") ||
            currentState.equalsIgnoreCase("postgresql"))) {
      serverAccessor.log(Level.INFO, "&aDatabase engine change detected. Initiating background migration from YAML to SQL...");
      getInstance().miscAsyncTasks.add(new RTPRunnable(() -> {
        File pluginDir = serverAccessor.getPluginDirectory();
        File databaseDir = new File(pluginDir, "database");

        // 1. Migrate teleportData.yml
        io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase yamlDb = new io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase(databaseDir);
        Map<String, YamlFile> lookup = yamlDb.connect();
        YamlFile teleportFile = lookup.get("teleportData.yml");
        if (teleportFile != null) {
          Map<String, Object> mapValues = teleportFile.getMapValues(false);
          for (Map.Entry<String, Object> entry : mapValues.entrySet()) {
            Object val = entry.getValue();
            Map<String, Object> dataMap;
            if (val instanceof Map<?, ?> map) dataMap = (Map<String, Object>) map;
            else if (val instanceof ConfigurationSection section) dataMap = section.getMapValues(false);
            else continue;

            try {
              TeleportData teleportData = new TeleportData();
              teleportData.sender = serverAccessor.getSender(UUID.fromString(dataMap.get("senderId").toString()));
              teleportData.time = ((Number) dataMap.getOrDefault("time", 0L)).longValue();
              Object oWorld = dataMap.get("originalWorldName");
              if (oWorld != null) teleportData.originalCoords = new RTPCoords(oWorld.toString(), ((Number) dataMap.get("originalX")).intValue(), ((Number) dataMap.get("originalY")).intValue(), ((Number) dataMap.get("originalZ")).intValue());
              Object sWorld = dataMap.get("selectedWorldName");
              if (sWorld != null) teleportData.selectedCoords = new RTPCoords(sWorld.toString(), ((Number) dataMap.get("selectedX")).intValue(), ((Number) dataMap.get("selectedY")).intValue(), ((Number) dataMap.get("selectedZ")).intValue());
              teleportData.attempts = ((Number) dataMap.getOrDefault("attempts", 0)).longValue();
              teleportData.cost = ((Number) dataMap.getOrDefault("cost", 0.0)).doubleValue();
              teleportData.targetRegion = selectionAPI.getRegion(dataMap.getOrDefault("region", "default").toString());
              teleportData.completed = true;

              if (getInstance().databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDb) {
                sqlDb.cacheValue(teleportData);
              }
            } catch (Exception ignored) {}
          }
        }

        // 2. Migrate individual UUID.yml files in teleportData directory
        File teleportDataDir = new File(databaseDir, "teleportData");
        if (teleportDataDir.exists() && teleportDataDir.isDirectory()) {
          File[] files = teleportDataDir.listFiles((dir, filename) -> filename.endsWith(".yml"));
          if (files != null) {
            for (File file : files) {
              try {
                YamlFile yamlFile = new YamlFile(file);
                yamlFile.load();
                Map<String, Object> mapValues = yamlFile.getMapValues(false);

                List<Map<String, Object>> records = new ArrayList<>();
                if (mapValues.containsKey("senderId") || mapValues.containsKey("time") || mapValues.containsKey("selectedX")) {
                  mapValues.putIfAbsent("senderId", file.getName().substring(0, file.getName().lastIndexOf('.')));
                  records.add(mapValues);
                } else {
                  for (Object val : mapValues.values()) {
                    if (val instanceof Map<?, ?> map) records.add((Map<String, Object>) map);
                    else if (val instanceof ConfigurationSection section) records.add(section.getMapValues(false));
                  }
                }

                boolean success = false;
                for (Map<String, Object> dataMap : records) {
                  try {
                    TeleportData teleportData = new TeleportData();
                    Object senderIdObj = dataMap.get("senderId");
                    if (senderIdObj == null) continue;
                    teleportData.sender = serverAccessor.getSender(UUID.fromString(senderIdObj.toString()));
                    teleportData.time = ((Number) dataMap.getOrDefault("time", 0L)).longValue();
                    Object oWorld = dataMap.get("originalWorldName");
                    if (oWorld != null) teleportData.originalCoords = new RTPCoords(oWorld.toString(), ((Number) dataMap.get("originalX")).intValue(), ((Number) dataMap.get("originalY")).intValue(), ((Number) dataMap.get("originalZ")).intValue());
                    Object sWorld = dataMap.get("selectedWorldName");
                    if (sWorld != null) teleportData.selectedCoords = new RTPCoords(sWorld.toString(), ((Number) dataMap.get("selectedX")).intValue(), ((Number) dataMap.get("selectedY")).intValue(), ((Number) dataMap.get("selectedZ")).intValue());
                    teleportData.attempts = ((Number) dataMap.getOrDefault("attempts", 0)).longValue();
                    teleportData.cost = ((Number) dataMap.getOrDefault("cost", 0.0)).doubleValue();
                    teleportData.targetRegion = selectionAPI.getRegion(dataMap.getOrDefault("region", "default").toString());
                    teleportData.completed = true;

                    if (getInstance().databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDb) {
                      sqlDb.cacheValue(teleportData);
                      success = true;
                    }
                  } catch (Exception ignored) {}
                }
                if (success) {
                  file.renameTo(new File(file.getAbsolutePath() + ".migrated"));
                }
              } catch (Exception ignored) {}
            }
          }
        }

        if (getInstance().databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDb) {
          sqlDb.flush();
        }
      }, 0));
    }
  }

  public static void addShape(Shape<?> shape) {
    ((Factory<Shape<?>>) factoryMap.get(factoryNames.shape)).add(shape.name, shape);
  }

  public static void addVerticalAdjustor(VerticalAdjustor<?> verticalAdjustor) {
    ((Factory<VerticalAdjustor<?>>) factoryMap.get(factoryNames.vert))
        .add(verticalAdjustor.name, verticalAdjustor);
  }

  public static RTP getInstance() {
    return instance;
  }

  public static void log(Level level, String str) {
    serverAccessor.log(level, str);
  }

  public static void log(Level level, String str, Throwable throwable) {
    serverAccessor.log(level, str, throwable);
  }

  public static RTPWorld getWorld(RTPPlayer player) {
    // get region from world name, check for overrides
    Set<String> worldsAttempted = new HashSet<>();
    String worldName = player.getLocation().world().name();
    MultiConfigParser<WorldKeys> worldParsers =
        (MultiConfigParser<WorldKeys>) RTP.configs.multiConfigParserMap.get(WorldKeys.class);
    ConfigParser<WorldKeys> worldParser = worldParsers.getParser(worldName);
    boolean requirePermission =
        Boolean.parseBoolean(
            worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());

    while (requirePermission && !player.hasPermission("rtp.worlds." + worldName)) {
      if (worldsAttempted.contains(worldName))
        throw new IllegalStateException("infinite override loop detected at world - " + worldName);
      worldsAttempted.add(worldName);

      worldName =
          String.valueOf(worldParser.getConfigValue(WorldKeys.override, "DEFAULT.YML"))
              .toUpperCase();
      if (!worldName.equals(".YML")) worldName = worldName + ".YML";
      worldParser = worldParsers.getParser(worldName);
      requirePermission =
          Boolean.parseBoolean(
              worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());
    }

    return serverAccessor.getRTPWorld(worldName);
  }

  public static void stop() {
    if (diagnosticTimer != null) {
      diagnosticTimer.shutdown();
    }

    List<CompletableFuture<?>> validFutures = new ArrayList<>(futures.size());
    for (CompletableFuture<?> future : futures) {
      if (!future.isDone()) validFutures.add(future);
    }
    if (!validFutures.isEmpty()) {
      for (CompletableFuture<?> future : validFutures) {
        try {
          if (future.isDone()) continue;
          future.complete(null);
        } catch (CancellationException ignored) {

        }
      }
    }

    if (instance == null) return;

    for (Map.Entry<UUID, TeleportData> e : instance.latestTeleportData.entrySet()) {
      TeleportData data = e.getValue();
      if (data == null || data.completed) continue;
      new RTPTeleportCancel(e.getKey()).run();
    }

    if (instance.databaseAccessor != null) {
      if (instance.databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDatabaseAccessor) {
        sqlDatabaseAccessor.flush();
      }
      instance.databaseAccessor.flushDirtyCache();
      instance.databaseAccessor.stop.set(true);
    }

    instance.miscAsyncTasks.stop();
    instance.miscSyncTasks.stop();

    for (Region r : selectionAPI.permRegionLookup.values()) {
      r.shutDown();
    }
    selectionAPI.permRegionLookup.clear();

    for (Region r : selectionAPI.tempRegions.values()) {
      r.shutDown();
    }
    selectionAPI.tempRegions.clear();

    instance.latestTeleportData.forEach(
        (uuid, data) -> {
          if (!data.completed) new RTPTeleportCancel(uuid).run();
        });

    instance.processingPlayers.clear();

    FillTask.kill();

    if (instance.redisManager != null) {
      instance.redisManager.shutdown();
    }

    serverAccessor.stop();
  }

  /** dynamic factories for certain types */
  public enum factoryNames {
    shape,
    vert,
    singleConfig,
    multiConfig
  }
}
