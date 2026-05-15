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
import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.*;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.fixed.FixedAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tools.ChunkyChecker;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Core class for the RTP (Random Teleport) plugin.
 * This class serves as the central hub for the plugin's platform-agnostic logic, separating
 * the core functionality from Bukkit/Spigot/Paper-specific APIs.
 *
 * <p>Key responsibilities include:
 * <ul>
 *   <li>Managing the selection API (Shapes, Vertical Adjustors).</li>
 *   <li>Maintaining references to server accessors, schedulers, and economy integrations.</li>
 *   <li>Tracking queued players and teleport data.</li>
 *   <li>Handling synchronization and task pipelining across the plugin.</li>
 * </ul>
 *
 * <p>This class acts as a singleton, accessible via {@link #getInstance()}, and delegates
 * platform-specific operations to the injected `serverAccessor` and `scheduler`.
 */
public class RTP {
  public static final ConcurrentLinkedQueue<CompletableFuture<?>> futures =
      new ConcurrentLinkedQueue<>();

  public static SelectionAPI selectionAPI = new SelectionAPI();

  public static EnumMap<factoryNames, Factory<?>> factoryMap = new EnumMap<>(factoryNames.class);

  public final Set<UUID> queuedPlayers = ConcurrentHashMap.newKeySet();

  /**
   * minimum number of teleportations to executeAsyncTasks per gametick, to prevent bottlenecking
   * during lag spikes
   */
  public static int minRTPExecutions = 1;

  /** only one of each of these objects */
  public static Configs configs;

  public static final UUID serverId = RTPAPI.serverId;

  public static RTPServerAccessor serverAccessor;
  public static RTPScheduler scheduler;
  public static RTPEconomy economy = null;

  /**
   * Platform-supplied carrier for the {@code /rtp test ...} umbrella SPI
   * (sender + deferred scheduler + audit sink). Populated by each platform
   * plugin during startup; remains {@code null} until then. Callers inside
   * {@code rtp-core} should resolve this via
   * {@link io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext#require()}
   * which enforces S-006 (throws {@link IllegalStateException} rather than
   * silently no-opping when the umbrella runs before core load).
   *
   * <p>See {@code docs/dev/scratch/CHECKLIST-fabric-rtp-test-full.md} Phase 1.
   */
  public static volatile io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext
      testUmbrellaContext;

  /**
   * Process-wide runtime metrics aggregator. Defaults to a {@link CoreMetrics} with a
   * {@link io.github.dailystruggle.rtp.common.metrics.MetricsBinding#NOOP NOOP} binding so
   * callers never have to null-check; platform adapters install a real binding via
   * {@link CoreMetrics#setBinding} during startup. See {@code METRICS_PLAN.md}.
   */
  public static final CoreMetrics metrics = new CoreMetrics();

  public static final ThreadLocal<RTPWorld> worldContext = new ThreadLocal<>();
  public static final ThreadLocal<Region> regionContext = new ThreadLocal<>();

  public static TreeCommand baseCommand;
  public static AtomicBoolean reloading = new AtomicBoolean(false);

  /** only one instance will exist at a time, reset on plugin load */
  private static RTP instance;

  private static ScheduledExecutorService diagnosticTimer;
  private static final List<Object> trackedTasks = new ArrayList<>();

  static {
    Factory<Shape<?>> shapeFactory = new Factory<>();
    selectionAPI.shapeFactory = shapeFactory;
    factoryMap.put(factoryNames.shape, shapeFactory);

    Factory<VerticalAdjustor<?>> verticalAdjustorFactory = new Factory<>();
    factoryMap.put(factoryNames.vert, verticalAdjustorFactory);
    factoryMap.put(factoryNames.singleConfig, new Factory<ConfigParser<?>>());
    factoryMap.put(factoryNames.multiConfig, new Factory<MultiConfigParser<?>>());

    // --- INJECT IMPLEMENTATIONS INTO THE API ---
    // This safely routes RTPAPI calls directly to the RTP core methods
    io.github.dailystruggle.rtp.api.RTPAPI.shapeAdder = shapeObj -> {
      if (shapeObj instanceof Shape<?>) addShape((Shape<?>) shapeObj);
    };

    io.github.dailystruggle.rtp.api.RTPAPI.vertAdder = vertObj -> {
      if (vertObj instanceof VerticalAdjustor<?>) addVerticalAdjustor((VerticalAdjustor<?>) vertObj);
    };

    // ADR-026: expose the unified external-hook facade. Third-party plugins call
    // RTPAPI.hooks() to register claim verifiers, economy, placeholders, world
    // border, and anvil pre-filter providers without depending on rtp-core
    // internals. See docs/dev/EXTERNAL_HOOKS.md.
    io.github.dailystruggle.rtp.api.RTPAPI.hooks =
        new io.github.dailystruggle.rtp.common.hooks.DefaultRTPHooks();
  }

  public final ConcurrentHashMap<UUID, TeleportData> priorTeleportData = new ConcurrentHashMap<>();
  public final ConcurrentHashMap<UUID, TeleportData> latestTeleportData = new ConcurrentHashMap<>();
  public final ConcurrentSkipListSet<UUID> processingPlayers = new ConcurrentSkipListSet<>();
  public RTPTaskPipe miscSyncTasks;
  public RTPTaskPipe miscAsyncTasks;
  public RTPTaskPipe startupTasks;
  public RTPTaskPipe cancelTasks;
  public final Map<String, ScanTask> scanTasks = new ConcurrentHashMap<>();
  public final ConcurrentHashMap<UUID, Long> invulnerablePlayers = new ConcurrentHashMap<>();
  public final ConcurrentLinkedQueue<RTPChunk<?>> chunksToUnload = new ConcurrentLinkedQueue<>();
  public DatabaseAccessor<?> databaseAccessor;
  /**
   * Optional cross-server messaging bus. Field type is the {@code RTPNetworkManager}
   * interface so {@code rtp-core} carries no symbolic reference to any concrete driver
   * class (ADR-024). Constructed reflectively below when network YAML enables a backend.
   */
  public io.github.dailystruggle.rtp.common.network.RTPNetworkManager networkManager;

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
    RTPAPI.setServerAccessor(serverAccessor);
    instance = this;

    RTPAPI.addShape(new Circle());
    RTPAPI.addShape(new Ellipse());
    RTPAPI.addShape(new Square());
    RTPAPI.addShape(new Rectangle());
    RTPAPI.addShape(new Circle_Normal());
    RTPAPI.addShape(new Square_Normal());
    RTPAPI.addShape(new Polygon());
    RTPAPI.addVerticalAdjustor(new LinearAdjustor(new ArrayList<>())); // todo: make this work
    RTPAPI.addVerticalAdjustor(new JumpAdjustor(new ArrayList<>()));
    RTPAPI.addVerticalAdjustor(new FixedAdjustor(new ArrayList<>()));

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
          this.networkManager = createRedisNetworkManager(host, port, password);
          if (this.networkManager != null) this.networkManager.initializeAsync();
        }
      } else if (redisObj instanceof ConfigurationSection redisSection) {
        boolean enabled = redisSection.getBoolean("enabled", false);
        if (enabled) {
          String host = redisSection.getString("host", "127.0.0.1");
          int port = redisSection.getInt("port", 6379);
          String password = redisSection.getString("password", "");
          this.networkManager = createRedisNetworkManager(host, port, password);
          if (this.networkManager != null) this.networkManager.initializeAsync();
        }
      }
    }, 10));

    ChunkyChecker.loadChunky();

    trackedTasks.add(scheduler.runTaskTimerAsynchronously(
            MemoryTracker::runDiagnostics,
      1200L,
      1200L));

    io.github.dailystruggle.rtp.common.tools.PerformanceTracker.start(scheduler);
    trackedTasks.add(scheduler.runTaskTimerAsynchronously(
        () -> {
          if (databaseAccessor != null) {
            // Rewrite the cached-locations table from authoritative in-memory
            // state so already-consumed locations cannot leak across restarts.
            // Must run BEFORE flushDirtyCache so the wipe-then-write ordering
            // survives into the shared writeQueue drain.
            databaseAccessor.rebuildCachedLocationsFromMemory();
            databaseAccessor.flushDirtyCache();
          }
        },
        6000,
        6000));
    trackedTasks.add(scheduler.runTaskTimerAsynchronously(
        () -> {
          if (databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDatabaseAccessor) {
            sqlDatabaseAccessor.flush();
          }
        },
        60,
        60));

    long syncTime = TimeUnit.MILLISECONDS.toNanos(5);
    trackedTasks.add(scheduler.runTaskTimer(new io.github.dailystruggle.rtp.common.tasks.tick.SyncTaskProcessing(syncTime), 1, 1));

    long asyncTime = TimeUnit.MILLISECONDS.toNanos(25); // Bumped to 5ms since async has more headroom
    trackedTasks.add(scheduler.runTaskTimerAsynchronously(new io.github.dailystruggle.rtp.common.tasks.tick.AsyncTaskProcessing(asyncTime), 1, 1));

  }

  /**
   * Reflectively construct the Redis-backed {@code RTPNetworkManager}, returning
   * {@code null} if the class (or its Jedis dependency) is not on the classpath.
   *
   * <p>ADR-024: the lite assembly excludes {@code RedisManager} and the Jedis
   * driver entirely. Resolving the class via {@code Class.forName} keeps the
   * symbolic reference inside a string literal so {@code RTP.class} itself can
   * load on a verifier-strict JVM without the driver present.
   */
  private static io.github.dailystruggle.rtp.common.network.RTPNetworkManager createRedisNetworkManager(String host, int port, String password) {
    try {
      Class<?> redisManagerClass = Class.forName("io.github.dailystruggle.rtp.common.network.RedisManager");
      return (io.github.dailystruggle.rtp.common.network.RTPNetworkManager)
          redisManagerClass.getDeclaredConstructor(String.class, int.class, String.class)
              .newInstance(host, port, password);
    } catch (ClassNotFoundException | NoClassDefFoundError missing) {
      log(Level.WARNING, "[NETWORK] redis enabled in config but RedisManager/Jedis is not on the classpath; skipping network bus init");
      return null;
    } catch (ReflectiveOperationException e) {
      log(Level.WARNING, "[NETWORK] failed to construct RedisManager", e);
      return null;
    }
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

  public Object getPlugin() {
    return serverAccessor.getPlugin();
  }

  /**
   * Bounded buffer for sub-INFO log entries emitted before the {@code logging.yml}
   * parser is built. Without this, FINE/FINER/FINEST/CONFIG records emitted during
   * the bootstrap window (plugin enable, very early {@code reloadConfigs}) bypass
   * the configured {@code min_level} gate because the platform sink (e.g.
   * {@code SendMessage#getMinLevel()} on Bukkit) falls back to {@link Level#ALL}
   * when the parser is unavailable, causing them to render as INFO on the
   * console. After the parser is wired, {@link #flushPendingLogs()}
   * drains the buffer through the normal sink so the configured threshold gates
   * them retroactively.
   */
  private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> pendingLogs =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  /** Hard cap so a misconfigured/late bootstrap can't grow the buffer unbounded. */
  private static final int PENDING_LOGS_CAP = 4096;

  /**
   * True once {@link #flushPendingLogs()} has run; subsequent calls bypass the
   * buffer and emit directly. Kept volatile so the gate is visible across threads
   * without locking the hot log path.
   */
  private static volatile boolean logsBuffered = true;

  /**
   * Returns true when the {@code logging.yml} parser hasn't been built yet, so
   * sub-INFO records would otherwise leak past the (yet-unfiltered) sink.
   */
  private static boolean loggingParserUnavailable() {
    Configs c = configs;
    if (c == null) return true;
    try {
      return c.getParser(
              io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class)
          == null;
    } catch (Throwable t) {
      return true;
    }
  }

  /**
   * Drains any sub-INFO records buffered during bootstrap through the live sink.
   * Idempotent: callers (notably {@link Configs#reloadConfigs()} after the atomic
   * parser swap) may invoke this freely. Once drained, future {@link #log} calls
   * bypass the buffer.
   */
  public static void flushPendingLogs() {
    logsBuffered = false;
    RTPServerAccessor accessor = serverAccessor;
    Object[] entry;
    while ((entry = pendingLogs.poll()) != null) {
      Level lvl = (Level) entry[0];
      String msg = (String) entry[1];
      Throwable t = (Throwable) entry[2];
      try {
        if (accessor == null) {
          if (t == null) java.util.logging.Logger.getLogger("RTP").log(lvl, msg);
          else java.util.logging.Logger.getLogger("RTP").log(lvl, msg, t);
        } else {
          if (t == null) accessor.log(lvl, msg);
          else accessor.log(lvl, msg, t);
        }
      } catch (Throwable ignored) {
        // Never let replay failures break the caller.
      }
    }
  }

  public static void log(Level level, String str) {
    RTPServerAccessor accessor = serverAccessor;
    if (logsBuffered && level != null && level.intValue() < Level.INFO.intValue()
        && loggingParserUnavailable()) {
      if (pendingLogs.size() < PENDING_LOGS_CAP) {
        pendingLogs.offer(new Object[] {level, str, null});
      }
      return;
    }
    if (accessor == null) {
      // Bootstrap fallback: serverAccessor is wired during onEnable, but lifecycle
      // tracing fires before/after that window (e.g. early onEnable, onDisable after a
      // failed onEnable). Route through JUL so we never NPE during plugin enable/disable.
      java.util.logging.Logger.getLogger("RTP").log(level, str);
      return;
    }
    accessor.log(level, str);
  }

  public static void log(Level level, String str, Throwable throwable) {
    RTPServerAccessor accessor = serverAccessor;
    if (logsBuffered && level != null && level.intValue() < Level.INFO.intValue()
        && loggingParserUnavailable()) {
      if (pendingLogs.size() < PENDING_LOGS_CAP) {
        pendingLogs.offer(new Object[] {level, str, throwable});
      }
      return;
    }
    if (accessor == null) {
      java.util.logging.Logger.getLogger("RTP").log(level, str, throwable);
      return;
    }
    accessor.log(level, str, throwable);
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
    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop ENTER");
    if (diagnosticTimer != null) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop diagnosticTimer.shutdown()");
      diagnosticTimer.shutdown();
    }

    List<CompletableFuture<?>> validFutures = new ArrayList<>(futures.size());
    for (CompletableFuture<?> future : futures) {
      if (!future.isDone()) validFutures.add(future);
    }
    if (!validFutures.isEmpty()) {
      log(Level.FINE,
          "[SHUTDOWN_TRACE] RTP.stop completing outstanding futures count=" + validFutures.size());
      for (CompletableFuture<?> future : validFutures) {
        try {
          if (future.isDone()) continue;
          future.complete(null);
        } catch (CancellationException ignored) {

        }
      }
    }

    if (instance == null) {
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop instance==null EARLY-RETURN");
      return;
    }

    int inflight = 0;
    for (Map.Entry<UUID, TeleportData> e : instance.latestTeleportData.entrySet()) {
      TeleportData data = e.getValue();
      if (data == null || data.completed) continue;
      log(Level.FINER,
          "[SHUTDOWN_TRACE] RTP.stop cancelInflight playerId=" + e.getKey());
      new RTPTeleportCancel(e.getKey()).run();
      inflight++;
    }
    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop CancelInflight cancelled=" + inflight);

    if (instance.databaseAccessor != null) {
      if (instance.databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDatabaseAccessor) {
        log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop SQL accessor flush (WAL checkpoint)");
        sqlDatabaseAccessor.flush();
      }
      // Rewrite the cached-locations table from authoritative in-memory state
      // BEFORE flushing the dirtyCache. Without this, rows for locations that
      // were consumed in prior flush cycles (or whose delete enqueue lost the
      // write-vs-delete ordering race in processQueries) survive into the next
      // startup and get re-hydrated as ghosts.
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop rebuildCachedLocationsFromMemory");
      instance.databaseAccessor.rebuildCachedLocationsFromMemory();
      // Drain dirtyCache -> writeQueue (this enqueues async writes via setValue().thenAccept(),
      // but getTable returns a completed future synchronously, so the enqueue runs inline).
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop flushDirtyCache");
      instance.databaseAccessor.flushDirtyCache();
      // CRITICAL: actually drain the writeQueue (and deleteQueue) to disk. Previously this
      // step was missing on shutdown, so every cached-location save/delete that accumulated
      // between the 5-minute periodic flushDirtyCache cycle and server stop was lost —
      // which is why the kept cache appeared empty after restart. Must happen BEFORE
      // stop.set(true) below, because processQueries bails immediately if stop is set.
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop processQueries(MAX) drain BEFORE stop.set(true)");
      instance.databaseAccessor.processQueries(Long.MAX_VALUE);
    }

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop StopPipes miscAsyncTasks/miscSyncTasks");
    instance.miscAsyncTasks.stop();
    instance.miscSyncTasks.stop();

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop CancelTracked count=" + trackedTasks.size());
    for (Object task : trackedTasks) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop cancelTrackedTask task=" + task);
      scheduler.cancelTask(task);
    }
    trackedTasks.clear();

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop permRegionLookup shutDown count="
            + selectionAPI.permRegionLookup.size());
    for (Region r : selectionAPI.permRegionLookup.values()) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop permRegion.shutDown name=" + r.name);
      r.shutDown();
    }
    selectionAPI.permRegionLookup.clear();

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop tempRegions shutDown count=" + selectionAPI.tempRegions.size());
    for (Region r : selectionAPI.tempRegions.values()) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop tempRegion.shutDown name=" + r.name);
      r.shutDown();
    }
    selectionAPI.tempRegions.clear();

    if (instance.databaseAccessor != null) {
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop databaseAccessor.stop.set(true) + close()");
      instance.databaseAccessor.stop.set(true);
      instance.databaseAccessor.close();
    }

    instance.latestTeleportData.forEach(
        (uuid, data) -> {
          if (!data.completed) {
            log(Level.FINER,
                "[SHUTDOWN_TRACE] RTP.stop CancelAgain late-cancel playerId=" + uuid);
            new RTPTeleportCancel(uuid).run();
          }
        });

    instance.processingPlayers.clear();

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop ScanTask.kill");
    ScanTask.kill();

    if (instance.networkManager != null) {
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop networkManager.shutdown");
      instance.networkManager.shutdown();
    }

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop serverAccessor.stop");
    serverAccessor.stop();
    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop EXIT");
  }

  /** dynamic factories for certain types */
  public enum factoryNames {
    shape,
    vert,
    singleConfig,
    multiConfig
  }
}
