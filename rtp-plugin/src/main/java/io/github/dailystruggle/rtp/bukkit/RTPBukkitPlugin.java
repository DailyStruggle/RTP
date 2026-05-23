package io.github.dailystruggle.rtp.bukkit;

import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.rtp.bukkit.database.BukkitDatabaseHandler;
import io.github.dailystruggle.rtp.bukkit.network.NetworkModeBootstrap;
import io.github.dailystruggle.rtp.bukkit.effects.BukkitEffectsHandler;
import io.github.dailystruggle.rtp.bukkit.events.*;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.*;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.ChunkyBorderChecker;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.PAPI_expansion;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.VaultChecker;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims.ClaimIntegrations;
import io.github.dailystruggle.rtp.bukkit.utils.JarUtils;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.ChunkUnloadProcessor;
import io.github.dailystruggle.rtp.bukkitplatform.server.AsyncTeleportProcessing;
import io.github.dailystruggle.rtp.bukkitplatform.server.DatabaseProcessing;
import io.github.dailystruggle.rtp.bukkitplatform.server.ScanTaskProcessing;
import io.github.dailystruggle.rtp.bukkitplatform.server.SyncTeleportProcessing;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

/** A Random Teleportation Spigot/Paper plugin, optimized for operators */
@SuppressWarnings("unused")
public final class RTPBukkitPlugin extends JavaPlugin {
  private static RTPBukkitPlugin instance = null;
  private static Metrics metrics;
  /** Phase 2e-SQL: backend-side network mode lifecycle holder; never null after onLoad. */
  private final NetworkModeBootstrap networkBootstrap = new NetworkModeBootstrap();
  public BukkitTask commandTimer = null;
  public BukkitTask commandProcessing = null;

  /**
   * @return the single plugin instance initialized at bukkit startup, faster than bukkit api
   */
  public static RTPBukkitPlugin getInstance() {
    return instance;
  }

  /**
   * bukkit-specific method to find the correct region for the player's location and permissions
   *
   * @param player bukkit player
   * @return region object
   */
  public static Region getRegion(Player player) {
    return RTP.selectionAPI.getRegion(RTP.serverAccessor.getPlayer(player.getUniqueId()));
  }

  public boolean isPaper() {
    try {
      Class.forName("io.papermc.paper.configuration.PaperConfigurations");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  public boolean isFolia() {
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Override
  public void onLoad() {
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onLoad ENTER -- probing org.sqlite.JDBC");
    // prepare sqlite capability
    try {
      Class.forName("org.sqlite.JDBC");
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onLoad SQLite JDBC driver loaded successfully");
    } catch (ClassNotFoundException e) {
      RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onLoad FAIL_FAST -- org.sqlite.JDBC not found");
      throw new IllegalStateException();
    }
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onLoad EXIT");
  }

  /** whenever bukkit feels like enabling this plugin */
  @Override
  public void onEnable() {
    // Wire RTP.serverAccessor and RTP.scheduler FIRST (silently) so every subsequent
    // RTP.log(...) routes through the canonical accessor instead of the JUL bootstrap
    // fallback. Reflection itself cannot log via RTP.log because the accessor isn't ready.
    if (instance == null) {
      instance = this;
      // ADR-024: shared helper -- both bootstraps wire serverAccessor + scheduler the same way.
      if (!BootstrapSupport.wireServerAccessorAndScheduler(this, "LIFECYCLE")) {
        onDisable();
        return;
      }
    }

    // Phase 1.5 (CHECKLIST-fabric-rtp-test-full.md): install the Bukkit
    // implementations of the /rtp test ... umbrella SPI onto RTP. Doing
    // this once RTP.serverAccessor + RTP.scheduler are wired guarantees
    // both adapter dependencies are usable. Idempotent: the field is
    // volatile and may be overwritten on a hot reload.
    RTP.testUmbrellaContext =
        new io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext(
            new io.github.dailystruggle.rtp.bukkit.commands.test.BukkitTestUmbrellaSender(),
            new io.github.dailystruggle.rtp.bukkit.commands.test.BukkitTestUmbrellaScheduler(),
            null);

    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable ENTER -- initializing bStats Metrics(id=30865)");
    metrics = new Metrics(this, 30865);
    // Register the RTP cost-metrics chart catalogue. All chart lambdas read
    // RTP.metrics.snapshot() (METRICS_PLAN.md SPI) and bucketise to keep
    // submissions privacy-safe and low-cardinality. See METRICS_PLAN.md
    // > bStats Integration and BStatsChartIds for the catalogue.
    io.github.dailystruggle.rtp.bukkit.metrics.RTPCostMetricsCharts.register(metrics, "full");

    // CHECKLIST-metrics-and-multiserver.md row B9: install the platform-
    // appropriate MetricsBinding so /rtp info, RTPCostMetricsCharts, and
    // every other Metrics.snapshot() consumer report live values instead
    // of UNSAMPLED sentinels. Best-effort; never aborts plugin enable.
    io.github.dailystruggle.rtp.bukkit.metrics.MetricsBindingDispatcher.install();

    if (RTP.getInstance() == null) {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onEnable accessor/scheduler wired; starting accessor");
      RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable RTP.serverAccessor.start(plugin)");
      RTP.serverAccessor.start(this);
      RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable constructing new RTP() -- wires API instance");
      RTP rtp = new RTP(); // constructor updates API instance

      // L6 Slice J: read routing.lobbyMode from network.yml BEFORE setupDatabase
      // (which loads regions.yml and constructs Region instances). The flag gates
      // the per-region ScanTask scheduling, DB hydrate, and steady-state
      // Region.execute() pulse - all of which would otherwise begin spending CPU
      // and disk on a backend that should not be holding any local locations.
      // Defensive: any failure resolves to lobbyMode=false, preserving pre-J
      // behaviour byte-for-byte on non-lobby deployments.
      try {
        java.io.File earlyNetworkYml = NetworkModeBootstrap.ensureNetworkYml(
            getDataFolder(), RTPBukkitPlugin.class);
        RTP.lobbyMode = NetworkModeBootstrap.readLobbyModeEarly(earlyNetworkYml);
        if (RTP.lobbyMode) {
          RTP.log(java.util.logging.Level.INFO,
              "[LIFECYCLE] onEnable routing.lobbyMode=true -- local region processing"
                  + " (ScanTask pre-fill, DB hydrate, Region.execute pulse) will be"
                  + " skipped; this backend acts as a pure cross-server dispatcher.");
        }
      } catch (Throwable t) {
        // Never block plugin enable on this read.
        RTP.lobbyMode = false;
        RTP.log(java.util.logging.Level.FINE,
            "[LIFECYCLE] onEnable lobbyMode early-read failed; defaulting to false: "
                + t.getMessage());
      }

      try {
        RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable BukkitDatabaseHandler.setupDatabase");
        BukkitDatabaseHandler.setupDatabase(rtp);
        RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onEnable database setup complete");
        // Phase 2e-SQL: boot backend-side network mode AFTER the DB is up
        // (the SQL transport reuses the same accessor's DataSource). Strict
        // REQ-RTP-NET-002 parity: no-op when network.yml is absent or
        // network.enabled=false. Failure here is logged but never aborts
        // plugin enable (network mode is strictly optional).
        try {
          java.io.File networkYml = NetworkModeBootstrap.ensureNetworkYml(
              getDataFolder(), RTPBukkitPlugin.class);
          networkBootstrap.boot(networkYml);
        } catch (Throwable t) {
          RTP.log(java.util.logging.Level.WARNING,
              "[LIFECYCLE] onEnable network-mode boot failed; continuing without it: " + t.getMessage(), t);
        }
      } catch (Exception e) {
        RTP.log(java.util.logging.Level.WARNING,
            "[LIFECYCLE] onEnable database setup failure -- bailing out via onDisable", e);
        onDisable();
        return;
      }
    }

    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable ChunkyBorderChecker.loadChunky");
    ChunkyBorderChecker.loadChunky();
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable startupTasks drain #1 size="
        + RTP.getInstance().startupTasks.size());
    RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);

    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable binding /rtp and /wild executors and tab-completers");
    // ADR-024: shared helper -- identical command-binding logic between full and lite.
    BootstrapSupport.registerRtpAndWildCommands(this);

    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable scheduling deferred startupTasks drain (tick+1)");
    RTP.scheduler.runTaskLater(
        () -> {
          RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] deferred startupTasks drain firing size="
              + RTP.getInstance().startupTasks.size());
          while (RTP.getInstance().startupTasks.size() > 0) {
            RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);
          }
        },
        1);

    // Register event listeners synchronously so that WorldLoadEvents fired by
    // automatic world generators (e.g. Multiverse) during the first tick are not
    // missed. Previously this ran via runTaskLater(..., 1), which caused dormant
    // regions configured for a late-loaded world to never rebind.
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable setupBukkitEvents (synchronous)");
    setupBukkitEvents();
    // CHECKLIST-maps-api.md Stage 2.6 - install the Bukkit-family MapBinding
    // so that MapDispatch (ADR-047 / REQ-RTP-MAP-006) can satisfy chart
    // requests issued from /rtp info etc. Stage 2.3 selects FoliaMapBinding
    // on Folia (per-viewer EntityScheduler hop available for Stage 3 live
    // charts); other backends get the plain BukkitMapBinding. bindLive
    // remains deferred on both paths (Stage 3 of CHECKLIST-metrics-to-maps).
    try {
      io.github.dailystruggle.mapsapi.bukkit.BukkitMapBinding binding =
          isFolia()
              ? new io.github.dailystruggle.rtp.folia.maps.FoliaMapBinding()
              : new io.github.dailystruggle.mapsapi.bukkit.BukkitMapBinding();
      io.github.dailystruggle.rtp.common.commands.maps.MapDispatch.setMapBinding(binding);
      RTP.log(java.util.logging.Level.FINE,
          "[LIFECYCLE] onEnable installed " + binding.getClass().getSimpleName()
              + " via MapDispatch");
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.WARNING,
          "[LIFECYCLE] onEnable MapBinding install failed; MapDispatch will fall back to NoopMapBinding",
          t);
    }
    // Catch worlds that were already loaded by the time the listener registered
    // (either before RTP enabled, or between region config load and listener
    // registration), so dormant regions for those worlds are activated without
    // needing another WorldLoadEvent.
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable rebindFallbackRegionsForAllLoadedWorlds");
    io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnWorldLoadUnload
        .rebindFallbackRegionsForAllLoadedWorlds();
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable scheduling deferred setupIntegrations (tick+1)");
    RTP.scheduler.runTaskLater(() -> {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] deferred setupIntegrations firing");
      setupIntegrations();
    }, 1);
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable scheduling deferred BukkitEffectsHandler.setupEffects (tick+1)");
    RTP.scheduler.runTaskLater(() -> {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] deferred BukkitEffectsHandler.setupEffects firing");
      BukkitEffectsHandler.setupEffects(this);
    }, 1);

    if (!isFolia()) {
      RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable starting non-Folia ChunkUnloadProcessor timer");
      RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);
    } else {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onEnable Folia detected -- skipping ChunkUnloadProcessor");
    }
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable DatabaseProcessing.start");
    DatabaseProcessing.start(this);

    SendMessage.sendMessage(Bukkit.getConsoleSender(), "");

    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable startupTasks drain #2 size="
        + RTP.getInstance().startupTasks.size());
    while (RTP.getInstance().startupTasks.size() > 0) {
      RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);
    }

    if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
      RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable registering PAPI_expansion");
      new PAPI_expansion().register();
    } else {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onEnable PlaceholderAPI not present -- skipping PAPI_expansion");
    }

    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable JarUtils.extractDocs version=" + getDescription().getVersion());
    JarUtils.extractDocs(getDataFolder(), getDescription().getVersion());

    // ADR-023 — Login Reserve Cache: snapshot max-players at startup, allocate
    // the buffer on the default-world region (Bukkit.getWorlds().get(0)), and
    // dispatch the startup burst. Decoupled from Region.execute() per ADR-023.
    initLoginReserveCache();

    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onEnable EXIT -- plugin enabled");
  }

  /**
   * ADR-023 — initialise the Login Reserve Cache on the default-world region
   * if {@code PerformanceKeys.loginCacheEnabled=true}. Sized to
   * {@code loginCacheCap} (or {@code Bukkit.getMaxPlayers()} when
   * {@code loginCacheCap=0}). Idempotent: safe to call after reload.
   */
  @SuppressWarnings("unchecked")
  private void initLoginReserveCache() {
    try {
      ConfigParser<PerformanceKeys> perf =
          (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
      if (perf == null) return;
      Object enabledObj = perf.getConfigValue(PerformanceKeys.loginCacheEnabled, false);
      boolean enabled = (enabledObj instanceof Boolean)
          ? (Boolean) enabledObj
          : Boolean.parseBoolean(String.valueOf(enabledObj));
      if (!enabled) return;

      long configuredCap = perf.getNumber(PerformanceKeys.loginCacheCap, 0L).longValue();
      // C6 (Section C of CHECKLIST-metrics-and-multiserver) — read soft cap
      // from the M2 metrics snapshot when a binding is installed; fall back
      // to Bukkit.getMaxPlayers() only on the NOOP path (snapshot.softCap=0).
      io.github.dailystruggle.metrics.api.MetricsSnapshot bootSnap =
          RTP.metrics.snapshot();
      int snapshotCap = bootSnap.softCap;
      int cap = (configuredCap > 0)
          ? (int) Math.min(configuredCap, Integer.MAX_VALUE)
          : (snapshotCap > 0 ? snapshotCap : Bukkit.getMaxPlayers());
      if (cap <= 0) return;

      if (Bukkit.getWorlds().isEmpty()) {
        RTP.log(java.util.logging.Level.WARNING,
            "[ADR-023] login cache enabled but no worlds loaded; skipping");
        return;
      }
      String defaultWorldName = Bukkit.getWorlds().get(0).getName();
      Region region = RTP.selectionAPI.permRegionLookup.values().stream()
          .filter(r -> r.getWorld() != null && defaultWorldName.equals(r.getWorld().name()))
          .findFirst()
          .orElse(null);
      if (region == null) {
        RTP.log(java.util.logging.Level.WARNING,
            "[ADR-023] login cache enabled but no region attached to default world '"
                + defaultWorldName + "'; skipping");
        return;
      }

      region.queueManager.enableLoginCache(cap);
      RTP.log(java.util.logging.Level.INFO,
          "[ADR-023] login reserve cache enabled on region '" + region.name
              + "' cap=" + cap + " (default world '" + defaultWorldName + "')");

      // Startup burst: dispatch up to (cap - currentOnline) async promotions.
      // C6 — prefer the M2 snapshot when bound; fall back to the Bukkit API
      // on the NOOP path (snapshot.playerCount=0 on first boot before the
      // sampler has run, which is also the value Bukkit returns at this
      // lifecycle point, so the two paths are semantically aligned).
      io.github.dailystruggle.metrics.api.MetricsSnapshot burstSnap =
          RTP.metrics.snapshot();
      int online = (burstSnap.playerCount > 0)
          ? burstSnap.playerCount
          : Bukkit.getOnlinePlayers().size();
      int target = Math.max(0, cap - online);
      if (target > 0) {
        new io.github.dailystruggle.rtp.common.selection.region.LoginCacheTask(region)
            .promoteUpTo(target);
        RTP.log(java.util.logging.Level.FINE,
            "[ADR-023] startup burst dispatched count=" + target);
      }
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.WARNING,
          "[ADR-023] login reserve cache init failed: "
              + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }
  }

  /** whenever bukkit feels like disabling this plugin */
  @Override
  public void onDisable() {
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onDisable ENTER -- cancelling command timers");
    // Phase 2e-SQL: stop the backend heartbeat publisher + close the network
    // transport BEFORE any DB shutdown. Reverse-order teardown - publisher
    // first (so it cannot enqueue more upserts), then transport (releases
    // any subscribers / poll thread). Idempotent; safe if network mode was
    // never enabled this lifecycle.
    try {
      networkBootstrap.shutdown();
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.WARNING,
          "[LIFECYCLE] onDisable network-mode shutdown failed (continuing): " + t.getMessage(), t);
    }
    // CHECKLIST-metrics-and-multiserver.md row B9: tear down the metrics
    // binding and cancel the Spigot tick sampler (if installed) so a
    // /reload cycle reinstalls cleanly. Idempotent.
    try {
      io.github.dailystruggle.rtp.bukkit.metrics.MetricsBindingDispatcher.uninstall();
    } catch (NoClassDefFoundError ignored) {
    }
    // CHECKLIST-maps-api.md Stage 2.2 / REQ-RTP-MAP-003 - fan out the
    // host-plugin disable to every registered MapBindingLifecycle so the
    // active BukkitMapBinding (installed in onEnable) releases its cached
    // MapHandles and the active-GC tracker no longer sees the binding's
    // entries. Idempotent; safe if MapDispatch was never used this lifecycle.
    try {
      io.github.dailystruggle.rtp.common.commands.maps.MapDispatch.fireDisable();
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.WARNING,
          "[LIFECYCLE] onDisable MapDispatch.fireDisable failed (continuing): " + t.getMessage(), t);
    }
    if (commandTimer != null) {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onDisable commandTimer.cancel()");
      commandTimer.cancel();
    }
    if (commandProcessing != null) {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onDisable commandProcessing.cancel()");
      commandProcessing.cancel();
    }

    try {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onDisable AsyncTeleportProcessing.kill");
      AsyncTeleportProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
    }
    try {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onDisable SyncTeleportProcessing.kill");
      SyncTeleportProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
    }
    try {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] onDisable ScanTaskProcessing.kill");
      ScanTaskProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
    }
    try {
      RTP.log(java.util.logging.Level.FINER,
          "[LIFECYCLE] onDisable DatabaseProcessing.kill (stops periodic flush task)");
      DatabaseProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
    }

    metrics = null;

    try {
      RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onDisable RTP.stop() invoking core shutdown");
      RTP.stop();
    } catch (NoClassDefFoundError ignored) {
    }

    List<BukkitTask> pendingTasks =
        Bukkit.getScheduler().getPendingTasks().stream()
            .filter(
                b ->
                    b.getOwner().getName().equalsIgnoreCase("RTP")
                        && !b.isSync()
                        && !b.isCancelled())
            .collect(Collectors.toList());
    RTP.log(java.util.logging.Level.FINE,
        "[LIFECYCLE] onDisable cancelling pending RTP-owned async Bukkit tasks count="
            + pendingTasks.size());
    for (BukkitTask pendingTask : pendingTasks) {
      RTP.log(java.util.logging.Level.FINER,
          "[LIFECYCLE] onDisable cancelling pending Bukkit task id=" + pendingTask.getTaskId());
      pendingTask.cancel();
    }

    try {
      if (RTP.getInstance() != null && RTP.getInstance().databaseAccessor != null) {
        RTP.log(java.util.logging.Level.FINE,
            "[LIFECYCLE] onDisable writing referenceData sentinel + processQueries(MAX) final drain");
        Map<String, Object> referenceData = new HashMap<>();
        referenceData.put("time", System.currentTimeMillis());
        referenceData.put("UUID", new UUID(0, 0).toString());
        RTP.getInstance().databaseAccessor.setValue("referenceData", referenceData);
        RTP.getInstance().databaseAccessor.processQueries(Long.MAX_VALUE);
        RTP.log(java.util.logging.Level.FINER,
            "[LIFECYCLE] onDisable referenceData sentinel persisted");
      } else {
        RTP.log(java.util.logging.Level.FINER,
            "[LIFECYCLE] onDisable referenceData sentinel skipped (instance or accessor null)");
      }
    } catch (NoClassDefFoundError ignored) {
    }

    if (RTP.serverAccessor != null) {
      RTP.log(java.util.logging.Level.FINE,
          "[LIFECYCLE] onDisable releaseAllChunkTickets (S-002 guarantee)");
      RTP.serverAccessor.releaseAllChunkTickets();
    } else {
      RTP.log(java.util.logging.Level.FINER,
          "[LIFECYCLE] onDisable releaseAllChunkTickets skipped (serverAccessor null)");
    }

    // Folia (and Spigot's /reload) does not unregister a plugin's permissions
    // when the plugin instance is disabled. On re-enable Bukkit re-runs
    // SimplePluginManager.loadPlugin, which calls addPermission(...) for every
    // entry under plugin.yml's `permissions:` block; those calls collide with
    // the still-registered Permission objects from the previous lifecycle and
    // the server logs a "tried to register permission '...' but it's already
    // registered" warning per node. Defensively remove our declared permissions
    // here so the next enable starts from a clean slate.
    try {
      org.bukkit.plugin.PluginManager pm = Bukkit.getPluginManager();
      for (org.bukkit.permissions.Permission perm : getDescription().getPermissions()) {
        if (pm.getPermission(perm.getName()) != null) {
          pm.removePermission(perm.getName());
        }
      }
      RTP.log(java.util.logging.Level.FINER,
          "[LIFECYCLE] onDisable unregistered declared permissions to silence Folia re-enable warnings");
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.FINER,
          "[LIFECYCLE] onDisable permission cleanup skipped: "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] onDisable EXIT -- plugin disabled");
    super.onDisable();
  }

  private void setupBukkitEvents() {
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] setupBukkitEvents ENTER");
    ConfigParser<PerformanceKeys> performance =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);

    boolean onEventParsing;
    Object o = performance.getConfigValue(PerformanceKeys.onEventParsing, false);
    if (o instanceof Boolean) onEventParsing = (Boolean) o;
    else onEventParsing = Boolean.parseBoolean(o.toString());
    RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] setupBukkitEvents onEventParsing=" + onEventParsing);

    if (onEventParsing) Bukkit.getPluginManager().registerEvents(new OnEventTeleports(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerChangeWorld(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerDamage(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerJoin(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerMove(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerQuit(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerRespawn(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerTeleport(), this);
    Bukkit.getPluginManager().registerEvents(new OnWorldLoadUnload(), this);

    // L2 of CHECKLIST-cross-server-rtp.md: hand the network-mode bootstrap a
    // plugin reference so it can register its JoinTriggerSource alongside
    // the other listeners. No-op when network mode is disabled (boot() left
    // joinTriggerSource null).
    try {
      networkBootstrap.registerJoinTriggerSource(this);
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.WARNING,
          "[LIFECYCLE] setupBukkitEvents JoinTriggerSource registration failed; continuing: "
              + t.getMessage(), t);
    }

    // Slice 4 (ADR-015 / REQ-RTP-NET-015): register the cross-server
    // waitlist PlayerQuitEvent hook + install the command-lock sender
    // check on the live RTPCmdBukkit. Both are no-ops when network mode
    // is disabled (waitlistQuitListener / waitlistCommandGuard() are
    // null on the disabled-mode path) and idempotent on re-register.
    try {
      networkBootstrap.registerWaitlistQuitListener(this);
      java.util.function.Predicate<org.bukkit.command.CommandSender> guard =
          networkBootstrap.waitlistCommandGuard();
      if (guard != null
          && RTP.baseCommand instanceof io.github.dailystruggle.rtp.bukkit.commands.RTPCmdBukkit cmd) {
        cmd.addSenderCheck(guard);
      }
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.WARNING,
          "[LIFECYCLE] setupBukkitEvents waitlist wiring failed; continuing: "
              + t.getMessage(), t);
    }

    EffectsAPI.init(this);
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] setupBukkitEvents EXIT -- listeners registered");
  }

  public void setupIntegrations() {
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] setupIntegrations ENTER");
    if (RTP.economy == null && Bukkit.getServer().getPluginManager().getPlugin("Vault") != null) {
      VaultChecker.setupEconomy();
      VaultChecker.setupPermissions();
      // ADR-026: bind through the public RTPHooks facade rather than poking RTP.economy
      // directly. The facade also writes RTP.economy for read-path compatibility; see
      // docs/dev/EXTERNAL_HOOKS.md.
      if (VaultChecker.getEconomy() != null) {
        io.github.dailystruggle.rtp.api.RTPAPI.hooks().economy().bind(new VaultChecker());
      }
    }

    // Bundled claim-plugin integrations (formerly the RTP_ClaimPluginIntegrations addon).
    // Registers a GlobalRegionVerifier per enabled claim plugin; see ADR-019.
    try {
      RTP.log(java.util.logging.Level.FINER, "[LIFECYCLE] setupIntegrations invoking ClaimIntegrations.setup");
      ClaimIntegrations.setup(this);
    } catch (Throwable t) {
      RTP.log(
          java.util.logging.Level.WARNING,
          "[RTP] Failed to initialize claim-plugin integrations; continuing without them.",
          t);
    }
    RTP.log(java.util.logging.Level.FINE, "[LIFECYCLE] setupIntegrations EXIT");
  }
}
