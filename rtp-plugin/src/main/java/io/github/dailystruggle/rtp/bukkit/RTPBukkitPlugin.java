package io.github.dailystruggle.rtp.bukkit;

import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.rtp.bukkit.commands.RTPCmdBukkit;
import io.github.dailystruggle.rtp.bukkit.database.BukkitDatabaseHandler;
import io.github.dailystruggle.rtp.bukkit.effects.BukkitEffectsHandler;
import io.github.dailystruggle.rtp.bukkit.events.*;
import io.github.dailystruggle.rtp.bukkit.server.BukkitServerProvider;
import io.github.dailystruggle.rtp.bukkit.spigotListeners.*;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.ChunkyBorderChecker;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.PAPI_expansion;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.VaultChecker;
import io.github.dailystruggle.rtp.bukkit.utils.JarUtils;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.ChunkUnloadProcessor;
import io.github.dailystruggle.rtp.spigot.server.AsyncTeleportProcessing;
import io.github.dailystruggle.rtp.spigot.server.DatabaseProcessing;
import io.github.dailystruggle.rtp.spigot.server.ScanTaskProcessing;
import io.github.dailystruggle.rtp.spigot.server.SyncTeleportProcessing;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
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
    // prepare sqlite capability
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException();
    }
  }

  /** whenever bukkit feels like enabling this plugin */
  @Override
  public void onEnable() {
    metrics = new Metrics(this, 12277);

    if (instance == null) {
      instance = this;

      BukkitServerProvider.ServerModel serverModel = BukkitServerProvider.resolveServerModel(this);

      try {
        RTP.serverAccessor =
            (io.github.dailystruggle.rtp.api.server.RTPServerAccessor)
                Class.forName(serverModel.accessorClassName).getDeclaredConstructor().newInstance();
        RTP.scheduler =
            (io.github.dailystruggle.rtp.api.scheduling.RTPScheduler)
                Class.forName(serverModel.schedulerClassName)
                    .getDeclaredConstructor(JavaPlugin.class)
                    .newInstance(this);
      } catch (Exception e) {
        e.printStackTrace();
        onDisable();
        return;
      }

      RTP rtp = new RTP(); // constructor updates API instance

      try {
        BukkitDatabaseHandler.setupDatabase(rtp);
      } catch (Exception e) {
        e.printStackTrace();
        onDisable();
        return;
      }
    }

    ChunkyBorderChecker.loadChunky();
    RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);

    RTPCmdBukkit mainCommand = new RTPCmdBukkit(this);
    RTP.baseCommand = mainCommand;

    org.bukkit.command.PluginCommand rtpCommand = getCommand("rtp");
    if (rtpCommand != null) {
      rtpCommand.setExecutor(mainCommand);
      rtpCommand.setTabCompleter(mainCommand);
    }

    org.bukkit.command.PluginCommand wildCommand = getCommand("wild");
    if (wildCommand != null) {
      wildCommand.setExecutor(mainCommand);
      wildCommand.setTabCompleter(mainCommand);
    }

    RTP.scheduler.runTaskLater(
        () -> {
          while (RTP.getInstance().startupTasks.size() > 0) {
            RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);
          }
        },
        1);

    RTP.scheduler.runTaskLater(() -> RTP.serverAccessor.start(this), 1);
    RTP.scheduler.runTaskLater(this::setupBukkitEvents, 1);
    RTP.scheduler.runTaskLater(this::setupIntegrations, 1);
    RTP.scheduler.runTaskLater(() -> BukkitEffectsHandler.setupEffects(this), 1);

    if (!isFolia()) {
      RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);
      DatabaseProcessing.start(this);
    }

    SendMessage.sendMessage(Bukkit.getConsoleSender(), "");

    while (RTP.getInstance().startupTasks.size() > 0) {
      RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);
    }

    if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
      new PAPI_expansion().register();
    }

    JarUtils.extractDocs(getDataFolder(), getDescription().getVersion());
  }

  /** whenever bukkit feels like disabling this plugin */
  @Override
  public void onDisable() {
    if (commandTimer != null) commandTimer.cancel();
    if (commandProcessing != null) commandProcessing.cancel();

    try {
      AsyncTeleportProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
    }
    try {
      SyncTeleportProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
    }
    try {
      ScanTaskProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
    }
    try {
      DatabaseProcessing.kill();
    } catch (NoClassDefFoundError ignored) {
    }

    metrics = null;

    try {
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
    for (BukkitTask pendingTask : pendingTasks) {
      pendingTask.cancel();
    }

    try {
      if (RTP.getInstance() != null && RTP.getInstance().databaseAccessor != null) {
        Map<String, Object> referenceData = new HashMap<>();
        referenceData.put("time", System.currentTimeMillis());
        referenceData.put("UUID", new UUID(0, 0).toString());
        RTP.getInstance().databaseAccessor.setValue("referenceData", referenceData);
        RTP.getInstance().databaseAccessor.processQueries(Long.MAX_VALUE);
      }
    } catch (NoClassDefFoundError ignored) {
    }

    if (RTP.serverAccessor != null) RTP.serverAccessor.releaseAllChunkTickets();

    super.onDisable();
  }

  private void setupBukkitEvents() {
    ConfigParser<PerformanceKeys> performance =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);

    boolean onEventParsing;
    Object o = performance.getConfigValue(PerformanceKeys.onEventParsing, false);
    if (o instanceof Boolean) onEventParsing = (Boolean) o;
    else onEventParsing = Boolean.parseBoolean(o.toString());

    if (onEventParsing) Bukkit.getPluginManager().registerEvents(new OnEventTeleports(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerChangeWorld(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerDamage(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerJoin(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerMove(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerQuit(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerRespawn(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerTeleport(), this);
    Bukkit.getPluginManager().registerEvents(new OnWorldLoadUnload(), this);

    if (RTP.serverAccessor.getServerIntVersion() > 12) EffectsAPI.init(this);
  }

  public void setupIntegrations() {
    if (RTP.economy == null && Bukkit.getServer().getPluginManager().getPlugin("Vault") != null) {
      VaultChecker.setupEconomy();
      VaultChecker.setupPermissions();
      if (VaultChecker.getEconomy() != null) RTP.economy = new VaultChecker();
      else RTP.economy = null;
    }
  }
}
